package com.pcbsolderview.data

import android.content.Context
import android.graphics.Bitmap
import com.pcbsolderview.parser.InputFile
import java.io.File
import java.security.MessageDigest

/**
 * Private copies of opened boards (filesDir/boards/<hash of board key>/), so a recent board can be
 * reopened even when the original file moved or came from a one-time share.
 */
class BoardStore(context: Context) {
    private val root = File(context.filesDir, "boards").apply { mkdirs() }

    fun dirFor(boardKey: String): File = File(root, sha1(boardKey))

    /** Replaces the stored files of [boardKey] with [files] and the thumbnail. */
    fun save(boardKey: String, files: List<InputFile>, thumbnail: Bitmap?): File {
        val dir = dirFor(boardKey)
        dir.deleteRecursively()
        dir.mkdirs()
        files.forEachIndexed { i, f ->
            // Keep the original name (the importer uses it); prefix to avoid collisions.
            File(dir, "%02d_%s".format(i, safeName(f.name))).writeBytes(f.bytes)
        }
        thumbnail?.let { bmp -> File(dir, THUMB).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
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
