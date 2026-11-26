package com.pcbsolderview.update

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A response: status, lower-case headers and the body (the error body for 4xx/5xx). */
class HttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    val contentLength: Long get() = headers["content-length"]?.toLongOrNull() ?: -1
    fun text(): String = body.use { it.readBytes().toString(Charsets.UTF_8) }
    override fun close() { runCatching { body.close() }; onClose() }
}

/** The only network access of the updater, so tests can replace it. */
fun interface Http {
    /** GET [url], following redirects. Throws [UpdateException] ([Failure.OFFLINE]) when there is no connection. */
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** [Http] over `HttpURLConnection` (Android and the JVM). Sends nothing but the request itself. */
class UrlConnectionHttp(
    private val userAgent: String = "Flux-updater",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : Http {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        try {
            var target = URL(url)
            // GitHub's download links redirect to another host. Redirects are followed here so the
            // Range header goes along and only http(s) targets are accepted.
            repeat(6) {
                val c = target.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = connectTimeoutMs
                c.readTimeout = readTimeoutMs
                c.setRequestProperty("User-Agent", userAgent)
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw UpdateException(Failure.HTTP, "redirect without location")
                    c.disconnect()
                    target = URL(target, loc)
                    if (target.protocol != "https" && target.protocol != "http") throw UpdateException(Failure.HTTP, "bad redirect")
                    return@repeat
                }
                val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.lastOrNull().orEmpty() }
                val body = (if (code >= 400) c.errorStream else c.inputStream) ?: java.io.ByteArrayInputStream(ByteArray(0))
                return HttpResponse(code, h, body) { c.disconnect() }
            }
            throw UpdateException(Failure.HTTP, "too many redirects")
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(Failure.OFFLINE, e.message, e)
        }
    }
}
