package io.github.cragcoffee.memoripple.data.ai.models

import io.github.cragcoffee.memoripple.domain.ai.models.DownloadEnd
import io.github.cragcoffee.memoripple.domain.ai.models.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A plain [HttpURLConnection] streamer with byte-range resume (docs/AI_MODEL_MANAGEMENT.md
 * §download): HTTPS only (loopback HTTP only when a test opts in), the platform's default redirect handling,
 * `Range: bytes=<offset>-` when resuming and a 206 required in that case, a short body reported
 * as a failure, cancellation checked between chunks. It never closes the caller's sink.
 */
class HttpModelDownloader(
    private val connectTimeoutMillis: Int = 30_000,
    private val readTimeoutMillis: Int = 60_000,
    /** Phase 7: loopback cleartext is a test-time opt-in (the fixture server); the product never sets it, so a release speaks HTTPS only. */
    private val allowLoopbackHttp: Boolean = false,
) : ModelDownloader {
    override suspend fun download(url: String, offset: Long, sink: OutputStream, onProgress: (Long, Long) -> Unit): DownloadEnd {
        val parsed = runCatching { URL(url) }.getOrElse { return DownloadEnd.Failed("bad url") }
        if (!allowed(parsed)) return DownloadEnd.Failed("only https is allowed")
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (parsed.openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMillis
                    readTimeout = readTimeoutMillis
                    instanceFollowRedirects = true
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                val code = connection.responseCode
                if (offset > 0 && code != HttpURLConnection.HTTP_PARTIAL) return@withContext DownloadEnd.Failed("range not honoured (HTTP $code)")
                if (code !in 200..206) return@withContext DownloadEnd.Failed("HTTP $code")
                val expected = connection.contentLengthLong
                val total = if (expected >= 0) offset + expected else -1L
                var received = 0L
                val buffer = ByteArray(256 * 1024)
                connection.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        received += n
                        onProgress(offset + received, total)
                    }
                }
                sink.flush()
                if (expected >= 0 && received < expected) DownloadEnd.Failed("short body: $received of $expected bytes")
                else DownloadEnd.Complete(offset + received)
            } catch (e: IOException) {
                DownloadEnd.Failed("${e::class.java.simpleName}: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun allowed(url: URL): Boolean = when (url.protocol) {
        "https" -> true
        "http" -> allowLoopbackHttp && url.host in LOOPBACK_HOSTS
        else -> false
    }

    companion object {
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")
    }
}
