package io.ajarara.flyingSaucer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.michaelbull.result.*
import io.ajarara.flyingSaucer.download.ChunkResult
import io.ajarara.flyingSaucer.download.HeadResponse
import io.ajarara.flyingSaucer.download.Headers
import io.ajarara.flyingSaucer.validation.ArchiveUrlValidationError
import io.ajarara.flyingSaucer.validation.parseMovie
import io.reactivex.*
import io.reactivex.functions.BiFunction
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object Main : CliktCommand() {

    private val tempDir = File(System.getProperty("java.io.tmpdir"))

    private val archiveUrl: String by option(help = "Mp4 to download from archive.org")
        .default("https://archive.org/download/Popeye_forPresident/Popeye_forPresident_512kb.mp4")

    private val concurrentRequestMax: Int by option(help = "Number of requests to run simultaneously")
        .int()
        .default(8)
        .validate {
            check(it > 0) {
                "Maximum of concurrent requests must be greater than 0!"
            }
        }

    private val noCache: Boolean by option(help = "Do not cache chunks on disk in between runs")
        .flag()
        .validate { shouldCache ->
            if (shouldCache) {
                require(tempDir.exists()) {
                    "This platform does not have a temporary directory, and cannot cache chunks."
                }
                require(tempDir.isDirectory) {
                    "This platform's temp directory is not a directory, and cannot cache chunks."
                }
                require(tempDir.canWrite()) {
                    "This platform's temp directory is not writeable, and cannot cache chunks."
                }
            }
        }

    private val outputDirectory: String by option(help = "Output directory of video")
        .default(System.getProperty("user.dir"))
        .validate { outputDirectory ->
            require(File(outputDirectory).exists()) {
                "$outputDirectory does not exist!"
            }
        }

    private const val chunkSize = 16384

    override fun run() {
        parseMovie(archiveUrl)
            .mapError {
                when (it) {
                    is ArchiveUrlValidationError.InvalidURLSyntax ->
                        println("Could not convert ${it.inputString}: ${it.reason}")
                    is ArchiveUrlValidationError.MissingScheme ->
                        println("Could not extract scheme from archiveUrl. Is this a web URL?")
                    is ArchiveUrlValidationError.IncorrectScheme ->
                        println("Only https is supported. Identified scheme: ${it.wrongScheme}")
                    is ArchiveUrlValidationError.NotADownload ->
                        println("Archive.org URL not a download link: ${it.path}")
                    is ArchiveUrlValidationError.UnknownHost ->
                        println("Only downloads from Archive.org are supported")
                }
                exitProcess(-1)
            }
            .map { downloadPath ->
                val fileRepoFolder = downloadPath.substringBefore("/")
                val outputLocation = reserveOutput(downloadPath)
                val etag = getEtag(downloadPath)
                val chunkRepo = createRepo(fileRepoFolder, etag)

                download(downloadPath, chunkRepo, etag, outputLocation)
            }
    }

    private fun reserveOutput(downloadPath: String): File {
        return File(outputDirectory, downloadPath.substringAfterLast("/")).apply {
            if (exists()) {
                println("File $name already exists in directory $outputDirectory! Aborting.")
                exitProcess(-1)
            }
        }
    }

    private fun getEtag(movie: String): HeadResponse.ETag {
        print("Checking if the download '$movie' exists: ")
        val headResponse = ArchiveAPI.Impl.check(movie)
            .map { HeadResponse.of(it.code(), it.headers().toMultimap()) }
            .blockingGet()
        return handleHeadResponse(headResponse)
    }

    private fun createRepo(fileRepoFolder: String, etag: HeadResponse.ETag): ChunkRepo =
        if (noCache) {
            InMemoryChunkRepo()
        } else {
            val projectDir = File(tempDir, "io.ajarara.flyingSaucer").apply { mkdir() }
            println("Stashing chunks in: ${projectDir.path}")
            DiskBackedChunkRepo(projectDir, fileRepoFolder, etag)
        }

    private fun download(
        downloadPath: String,
        chunkRepo: ChunkRepo,
        etag: HeadResponse.ETag,
        outputFile: File
    ) {
        val start = chunkRepo.firstGap()
        if (start != 0) println("Starting at chunk $start")
        println()

        val endOfFileReached = AtomicBoolean()
        Flowable.generate(Callable { start }, generatorFor(gate = endOfFileReached))
            .filter { chunkRepo.get(it) == null }
            .flatMapSingle({ chunkNo ->
                val bytes = Headers.bytesOf(chunkNo, chunkSize)

                ArchiveAPI.Impl.download(downloadPath, bytes, etag.etag)
                    .doOnSuccess { if (it.code() == 416) endOfFileReached.set(true) }
                    .map { ChunkResult.from(it.code(), chunkNo, it.body()) }
                    .retry(2)
            }, false, concurrentRequestMax)
            .blockingSubscribe(subscriberFor(chunkRepo))

        outputFile.apply {
            require(createNewFile()) {
                "$name was created after we checked but before we finished downloading!"
            }
            chunkRepo.chunks().forEach(::appendBytes)
        }

        println("\nDone!")
    }

    private fun handleHeadResponse(headResponse: HeadResponse): HeadResponse.ETag =
        when (headResponse) {
            is HeadResponse.Error -> {
                when (headResponse) {
                    is HeadResponse.Error.NotOk -> println(
                        "Got a non-200 response from the check: ${headResponse.code}"
                    )
                    is HeadResponse.Error.NoETag -> println(
                        "Did not get any ETag from the check!"
                    )
                    is HeadResponse.Error.MultipleETags -> println(
                        "Multiple ETags returned, ambiguous: ${headResponse.etags}"
                    )
                }
                exitProcess(-1)
            }
            is HeadResponse.ETag -> {
                println("It does! Downloading.")
                headResponse
            }
        }

    private fun subscriberFor(chunkRepo: ChunkRepo) = object : Subscriber<ChunkResult> {
        lateinit var subscription: Subscription

        override fun onSubscribe(subscription: Subscription) {
            this.subscription = subscription
            subscription.request(1)
        }

        override fun onNext(chunk: ChunkResult) =
            when (chunk) {
                is ChunkResult.Empty -> subscription.request(1)
                is ChunkResult.InvalidETag -> {
                    println()
                    println("ETag changed while downloading at chunk ${chunk.chunkNo}! All previous chunks are invalid.")
                    subscription.cancel()
                    exitProcess(-1)
                }
                is ChunkResult.UnknownCode -> {
                    println()
                    println("Unknown code returned from Archive.org. Aborting.")
                    subscription.cancel()
                    exitProcess(-1)
                }
                is ChunkResult.Chunk -> {
                    print("\rDownloaded chunk ${chunk.number}")
                    chunkRepo.set(chunk.number, chunk.data)
                    subscription.request(1)
                }
            }

        override fun onError(t: Throwable) {
            println(t.message)
            exitProcess(-1)
        }

        override fun onComplete() {}
    }

    private fun generatorFor(gate: AtomicBoolean) = BiFunction { chunkNo: Int, emitter: Emitter<Int> ->
        if (gate.get()) {
            emitter.onComplete()
            -1 // never fed back into the scan
        } else {
            emitter.onNext(chunkNo)
            chunkNo + 1
        }
    }
}

fun main(args: Array<String>) = Main.main(args)


