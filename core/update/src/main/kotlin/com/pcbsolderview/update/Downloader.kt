package com.pcbsolderview.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads a release file to [Downloader.download]'s `dest`: streams into `dest.part`, resumes an
 * interrupted download with a Range request, and checks the size and SHA-256 from the manifest
 * before renaming. A damaged file is deleted. Cancel by cancelling the coroutine (the `.part`
 * file stays, so the next try resumes).
 */
class Downloader(private val http: Http) {

    suspend fun download(url: String, dest: File, asset: Asset, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            if (dest.isFile && dest.length() == asset.size && sha256(dest) == asset.sha256) {
                onProgress(asset.size, asset.size)
                return@withContext dest
            }
            dest.parentFile?.mkdirs()
            val part = File(dest.path + ".part")
            if (part.length() > asset.size) part.delete()
            if (part.length() < asset.size) fetch(url, part, asset.size, onProgress)
            onProgress(part.length(), asset.size)
            // Connection closed early: keep the part so the next try resumes.
            if (part.length() < asset.size) throw UpdateException(Failure.OFFLINE, "download interrupted at ${part.length()} of ${asset.size} bytes")
            if (sha256(part) != asset.sha256) { part.delete(); throw UpdateException(Failure.CHECKSUM) }
            dest.delete()
            if (!part.renameTo(dest)) throw UpdateException(Failure.STORAGE, "rename failed")
            dest
        }

    private suspend fun fetch(url: String, part: File, total: Long, onProgress: (Long, Long) -> Unit) {
        val start = part.length()
        val headers = if (start > 0) mapOf("Range" to "bytes=$start-") else emptyMap()
        http.get(url, headers).use { r ->
            val append = when (r.code) {
                206 -> true
                200 -> false // the server ignored Range: start over
                416 -> { part.delete(); return fetch(url, part, total, onProgress) }
                404 -> throw UpdateException(Failure.NO_RELEASE)
                else -> throw UpdateException(Failure.HTTP, "HTTP ${r.code}")
            }
            var done = if (append) start else 0L
            try {
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = r.body.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > total) throw UpdateException(Failure.SIZE_MISMATCH, "more than $total bytes")
                        if (done - lastReport >= 256 * 1024) { onProgress(done, total); lastReport = done }
                    }
                }
            } catch (e: UpdateException) {
                part.delete(); throw e
            } catch (e: IOException) {
                // Disk full or connection lost: keep what arrived so the next try resumes.
                val disk = e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("space", ignoreCase = true)
                throw UpdateException(if (disk) Failure.STORAGE else Failure.OFFLINE, e.message, e)
            }
        }
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
