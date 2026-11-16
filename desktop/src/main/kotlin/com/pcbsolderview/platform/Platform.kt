package com.pcbsolderview.platform

import com.pcbsolderview.parser.InputFile
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where the desktop version keeps its data: `%APPDATA%\Flux\` (settings.json, recents.json,
 * boards\<sha1(key)>\…). The system property `flux.home` overrides it (tests use a temp folder).
 */
class AppDirs(val root: File) {
    init { root.mkdirs() }

    val boards: File get() = File(root, "boards").apply { mkdirs() }
    val settings: File get() = File(root, "settings.json")
    val recents: File get() = File(root, "recents.json")
    val window: File get() = File(root, "window.json")

    companion object {
        fun default(): AppDirs {
            System.getProperty("flux.home")?.let { return AppDirs(File(it)) }
            val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".flux")
            return AppDirs(File(base, "Flux"))
        }
    }
}

/** Writes through a temporary file and a move, so a crash never leaves half a file. */
fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text, Charsets.UTF_8)
    try {
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** File types the open dialog offers and a folder is scanned for. */
object SupportedFiles {
    val EXTENSIONS = listOf("cvg", "xml", "csv", "txt", "tsv", "xlsx", "zip", "schdoc", "pcbdoc", "pdf")

    fun isSupported(f: File) = f.isFile && f.extension.lowercase() in EXTENSIONS

    /**
     * Files as given; a folder (an Altium project folder) becomes every supported file directly in it.
     * Plain text reports in a project folder ("Status Report.Txt") are not boards; the importer
     * ignores files it does not recognise, so they do no harm.
     */
    fun expand(files: List<File>): List<File> = files.flatMap { f ->
        when {
            f.isDirectory -> f.listFiles().orEmpty().filter(::isSupported).sortedBy { it.name.lowercase() }
            f.isFile -> listOf(f)
            else -> emptyList()
        }
    }.distinctBy { it.absolutePath }
}

/** The synthetic samples, packaged as resources (see desktop/build.gradle.kts, packageDemos). */
object Demos {
    private fun index(): List<String> =
        Demos::class.java.getResourceAsStream("/demos/index.txt")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun files(folder: String): List<InputFile> = index()
        .filter { it.startsWith("$folder/") && it.count { c -> c == '/' } == 1 }
        .mapNotNull { path ->
            Demos::class.java.getResourceAsStream("/demos/$path")?.use { InputFile(path.substringAfter('/'), it.readBytes()) }
        }
}

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", or the date (DateUtils replacement). */
fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - then).coerceAtLeast(0)
    val min = d / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        min < 48 * 60 -> "yesterday"
        min < 7 * 24 * 60 -> "${min / (24 * 60)} days ago"
        else -> java.time.Instant.ofEpochMilli(then).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
    }
}

/** Decodes a PNG/JPEG file into a Compose image (BitmapFactory replacement). */
fun decodeImageFile(path: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    org.jetbrains.skia.Image.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap()
}.getOrNull()
