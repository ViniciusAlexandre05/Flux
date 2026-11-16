package com.pcbsolderview.data

import com.pcbsolderview.parser.InputFile
import com.pcbsolderview.platform.AppDirs
import java.io.File
import java.security.MessageDigest
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Private copies of opened boards (%APPDATA%\Flux\boards\<hash of board key>\), so a recent board
 * can be reopened even when the original file moved.
 */
class BoardStore(dirs: AppDirs) {
    private val root = dirs.boards

    fun dirFor(boardKey: String): File = File(root, sha1(boardKey))

    /** Replaces the stored files of [boardKey] with [files] and the thumbnail. */
    fun save(boardKey: String, files: List<InputFile>, thumbnail: Image?): File {
        val dir = dirFor(boardKey)
        dir.deleteRecursively()
        dir.mkdirs()
        files.forEachIndexed { i, f ->
            // Keep the original name (the importer uses it); prefix to avoid collisions.
            File(dir, "%02d_%s".format(i, safeName(f.name))).writeBytes(f.bytes)
        }
        thumbnail?.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(dir, THUMB).writeBytes(it) }
        return dir
    }

    fun load(dir: String): List<InputFile> =
        File(dir).listFiles().orEmpty()
            .filter { it.isFile && it.name != THUMB }
            .sortedBy { it.name }
            .map { InputFile(it.name.substringAfter('_'), it.readBytes()) }

    fun thumbnail(dir: String): File? = File(dir, THUMB).takeIf { it.exists() }

    fun delete(dir: String) { File(dir).deleteRecursively() }

    companion object {
        const val THUMB = "thumb.png"
        private fun safeName(n: String) = n.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "board" }
        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
