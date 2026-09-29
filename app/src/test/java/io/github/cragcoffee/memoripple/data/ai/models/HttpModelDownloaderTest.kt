package io.github.cragcoffee.memoripple.data.ai.models

import io.github.cragcoffee.memoripple.domain.ai.models.DownloadEnd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny HTTP/1.0 fixture server for the downloader tests: one body, Range support, an optional
 * cut after N bytes, an optional status. Real sockets, no framework.
 */
class LocalHttpFixtureServer(private val body: ByteArray) {
    @Volatile var cutAfterBytes: Long = -1
    @Volatile var status: Int = 200
    @Volatile var honourRange: Boolean = true
    @Volatile var stallForever: Boolean = false
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val server = ServerSocket(0)
    val url: String get() = "http://127.0.0.1:${server.localPort}/model.gguf"
    private val worker = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { return@thread }
            thread(isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(socket: Socket) = socket.use { s ->
        val input = s.getInputStream().bufferedReader()
        val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        requests += head.joinToString(" | ")
        val range = head.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter("bytes=")?.substringBefore("-")?.trim()?.toLongOrNull()
        val out = s.getOutputStream()
        if (stallForever) { Thread.sleep(60_000); return@use }
        if (status != 200) { out.write("HTTP/1.0 $status Nope\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush(); return@use }
        val offset = if (range != null && honourRange) range else 0L
        val remaining = body.size - offset.toInt()
        val header = if (range != null && honourRange) {
            "HTTP/1.0 206 Partial Content\r\nContent-Length: $remaining\r\nContent-Range: bytes $offset-${body.size - 1}/${body.size}\r\nAccept-Ranges: bytes\r\n\r\n"
        } else {
            "HTTP/1.0 200 OK\r\nContent-Length: ${body.size}\r\nAccept-Ranges: bytes\r\n\r\n"
        }
        out.write(header.toByteArray())
        val end = if (cutAfterBytes >= 0) minOf(body.size.toLong(), offset + cutAfterBytes).toInt() else body.size
        out.write(body, offset.toInt(), end - offset.toInt())
        out.flush()
    }

    fun close() { server.close() }
}

/** Phase 5 RED (docs/AI_MODEL_MANAGEMENT.md §download): resumable, cancellable, progress-reporting, never trusting a short body. */
class HttpModelDownloaderTest {
    private val body = ByteArray(300_000) { (it % 251).toByte() }
    private lateinit var server: LocalHttpFixtureServer
    private val downloader = HttpModelDownloader(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000, allowLoopbackHttp = true)

    @Before fun start() { server = LocalHttpFixtureServer(body) }
    @After fun stop() { server.close() }

    private open class Counting : OutputStream() {
        val bytes = ByteArrayOutputStream()
        override fun write(b: Int) { bytes.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { bytes.write(b, off, len) }
    }

    @Test
    fun aWholeFileArrivesWithProgressFromZeroToTheTotal() {
        val sink = Counting()
        val progress = ArrayList<Pair<Long, Long>>()
        val end = runBlocking { downloader.download(server.url, 0, sink) { b, t -> progress += b to t } }
        assertEquals(DownloadEnd.Complete(body.size.toLong()), end)
        assertTrue(sink.bytes.toByteArray().contentEquals(body))
        assertTrue(progress.isNotEmpty())
        assertEquals(body.size.toLong(), progress.last().first)
        assertTrue(progress.all { it.second == body.size.toLong() })
    }

    @Test
    fun aResumeAsksForARangeAndDeliversOnlyTheRest() {
        val sink = Counting()
        val end = runBlocking { downloader.download(server.url, 100_000, sink) { _, _ -> } }
        assertEquals(DownloadEnd.Complete(body.size.toLong()), end)
        assertTrue(sink.bytes.toByteArray().contentEquals(body.copyOfRange(100_000, body.size)))
        assertTrue(server.requests.single().contains("Range: bytes=100000-"))
    }

    @Test
    fun aServerThatIgnoresTheRangeIsAFailureNotACorruptedResume() {
        server.honourRange = false
        val sink = Counting()
        val end = runBlocking { downloader.download(server.url, 100_000, sink) { _, _ -> } }
        assertTrue("$end", end is DownloadEnd.Failed)
        assertEquals(0, sink.bytes.size())
    }

    @Test
    fun aBodyCutShortIsAFailureWithWhatArrivedKnown() {
        server.cutAfterBytes = 120_000
        val sink = Counting()
        var last = 0L
        val end = runBlocking { downloader.download(server.url, 0, sink) { b, _ -> last = b } }
        assertTrue("$end", end is DownloadEnd.Failed)
        assertEquals(120_000, sink.bytes.size())
        assertEquals(120_000L, last)
    }

    @Test
    fun aNonSuccessStatusIsAFailure() {
        server.status = 503
        val end = runBlocking { downloader.download(server.url, 0, Counting()) { _, _ -> } }
        assertTrue("$end", end is DownloadEnd.Failed && (end as DownloadEnd.Failed).detail.contains("503"))
    }

    @Test
    fun cancellationStopsTheStreamAndPropagates() {
        server.stallForever = true
        val sink = Counting()
        val outcome = runBlocking {
            val job = async(Dispatchers.IO) { downloader.download(server.url, 0, sink) { _, _ -> } }
            delay(200)
            job.cancel()
            runCatching { job.await() }
        }
        assertTrue("$outcome", outcome.exceptionOrNull() is CancellationException)
    }

    @Test
    fun onlyHttpsOrLoopbackIsAccepted() {
        val end = runBlocking { downloader.download("http://example.com/model.gguf", 0, Counting()) { _, _ -> } }
        assertTrue("$end", end is DownloadEnd.Failed && (end as DownloadEnd.Failed).detail.contains("https"))
        val ftp = runBlocking { downloader.download("ftp://127.0.0.1/x", 0, Counting()) { _, _ -> } }
        assertTrue(ftp is DownloadEnd.Failed)
    }

    @Test
    fun theSinkIsNotClosedByTheDownloaderSoTheCallerOwnsTheFile() {
        val sink = object : Counting() { var closed = false; override fun close() { closed = true } }
        runBlocking { withContext(Dispatchers.IO) { downloader.download(server.url, 0, sink) { _, _ -> } } }
        assertEquals(false, sink.closed)
    }
}

/** Phase 7 RED: the shipped downloader speaks HTTPS only; loopback cleartext is a test-time opt-in, never the default. */
class HttpModelDownloaderReleaseTest {
    private val body = ByteArray(10_000) { (it % 7).toByte() }
    private lateinit var server: LocalHttpFixtureServer

    @Before fun start() { server = LocalHttpFixtureServer(body) }
    @After fun stop() { server.close() }

    @Test
    fun theDefaultDownloaderRefusesLoopbackCleartextBeforeConnecting() {
        val sink = java.io.ByteArrayOutputStream()
        val end = kotlinx.coroutines.runBlocking { HttpModelDownloader().download(server.url, 0, sink) { _, _ -> } }
        org.junit.Assert.assertTrue(end is io.github.cragcoffee.memoripple.domain.ai.models.DownloadEnd.Failed)
        org.junit.Assert.assertEquals(0, sink.size())
        org.junit.Assert.assertEquals("no request reached the server", 0, server.requests.size)
    }

    @Test
    fun theOptInAcceptsLoopbackAndAnyOtherPlainHttpIsStillRefused() {
        val sink = java.io.ByteArrayOutputStream()
        val end = kotlinx.coroutines.runBlocking { HttpModelDownloader(allowLoopbackHttp = true).download(server.url, 0, sink) { _, _ -> } }
        org.junit.Assert.assertEquals(io.github.cragcoffee.memoripple.domain.ai.models.DownloadEnd.Complete(body.size.toLong()), end)
        val other = kotlinx.coroutines.runBlocking { HttpModelDownloader(allowLoopbackHttp = true).download("http://example.invalid/model.gguf", 0, java.io.ByteArrayOutputStream()) { _, _ -> } }
        org.junit.Assert.assertTrue(other is io.github.cragcoffee.memoripple.domain.ai.models.DownloadEnd.Failed)
    }
}
