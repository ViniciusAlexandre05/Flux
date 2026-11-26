package com.pcbsolderview.update

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * A local stand-in for github.com and api.github.com: serves release lists, manifests and assets,
 * redirects asset downloads like GitHub does, and honours Range requests (unless [ignoreRange]).
 */
class FakeGitHub : AutoCloseable {
    data class Release(val tag: String, val prerelease: Boolean = false, val manifest: String? = null, val files: Map<String, ByteArray> = emptyMap())

    val releases = mutableListOf<Release>()
    var ignoreRange = false
    var rateLimited = false
    /** Stops a download after this many bytes (simulates a dropped connection). */
    var cutAfter: Int? = null
    val requests = mutableListOf<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            requests += path + (ex.requestHeaders.getFirst("Range")?.let { " [$it]" } ?: "")
            fun reply(code: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
                headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
            }
            val parts = path.trim('/').split('/')
            when {
                rateLimited && path.startsWith("/api/") -> reply(403, "{}".toByteArray(), mapOf("X-RateLimit-Remaining" to "0"))
                path == "/api/repos/o/r/releases" -> reply(200, listJson().toByteArray())
                // /o/r/releases/latest/download/<file>  -> redirect to the newest full release
                parts.size == 6 && parts[2] == "releases" && parts[3] == "latest" -> {
                    val r = releases.firstOrNull { !it.prerelease }
                    if (r == null) reply(404) else reply(302, headers = mapOf("Location" to "/o/r/releases/download/${r.tag}/${parts[5]}"))
                }
                // /o/r/releases/download/<tag>/<file> -> redirect to the "CDN", as GitHub does
                parts.size == 6 && parts[2] == "releases" && parts[3] == "download" ->
                    reply(302, headers = mapOf("Location" to "$base/cdn/${parts[4]}/${parts[5]}"))
                parts.size == 3 && parts[0] == "cdn" -> {
                    val r = releases.firstOrNull { it.tag == parts[1] }
                    val body = when {
                        r == null -> null
                        parts[2] == ReleaseManifest.FILE_NAME -> r.manifest?.toByteArray()
                        else -> r.files[parts[2]]
                    }
                    if (body == null) { reply(404); return@createContext }
                    val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
                    when {
                        range != null && !ignoreRange && range >= body.size -> reply(416)
                        range != null && !ignoreRange -> send(ex, 206, body.copyOfRange(range, body.size), mapOf("Content-Range" to "bytes $range-${body.size - 1}/${body.size}"))
                        else -> send(ex, 200, body, emptyMap())
                    }
                }
                else -> reply(404)
            }
        }
        server.start()
    }

    private fun send(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: ByteArray, headers: Map<String, String>) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        val cut = cutAfter
        if (cut != null && cut < body.size) {
            // Announce the full length, then drop the connection part way.
            ex.sendResponseHeaders(code, body.size.toLong())
            runCatching { ex.responseBody.write(body, 0, cut); ex.responseBody.flush() }
            ex.close()
            cutAfter = null
            return
        }
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun listJson() = releases.joinToString(",", "[", "]") { r ->
        val assets = buildList {
            if (r.manifest != null) add("""{"name":"${ReleaseManifest.FILE_NAME}","browser_download_url":"$base/o/r/releases/download/${r.tag}/${ReleaseManifest.FILE_NAME}"}""")
            r.files.keys.forEach { add("""{"name":"$it","browser_download_url":"$base/o/r/releases/download/${r.tag}/$it"}""") }
        }.joinToString(",")
        """{"tag_name":"${r.tag}","name":"Flux ${r.tag}","draft":false,"prerelease":${r.prerelease},"published_at":"2026-11-24T10:00:00Z","body":"notes ${r.tag}","assets":[$assets]}"""
    }

    fun source() = GitHubReleaseSource("o", "r", UrlConnectionHttp(), web = base, api = "$base/api")

    override fun close() = server.stop(0)

    companion object {
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** A manifest with an Android part for [code] and, optionally, a PC part. */
        fun manifest(tag: String, code: Int?, apk: ByteArray = ByteArray(0), desktop: String? = null, minSdk: Int = 28): String {
            val android = code?.let {
                """"android":{"versionCode":$it,"versionName":"${tag.removePrefix("v")}","minSdk":$minSdk,"asset":"Flux-$it.apk","size":${apk.size},"sha256":"${sha(apk)}"},"""
            }.orEmpty()
            val pc = desktop?.let {
                """"desktop":{"version":"$it","msi":{"asset":"Flux-$it-windows.msi","size":1,"sha256":"${"0".repeat(64)}"}},"""
            }.orEmpty()
            return """{$android$pc"schema":1,"tag":"$tag","date":"2026-11-24","notes":"What is new in $tag","future":{"x":1}}"""
        }
    }
}
