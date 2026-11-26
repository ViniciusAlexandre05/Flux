package com.pcbsolderview.update

/** Why a check, download or install did not work. */
enum class Failure {
    OFFLINE, RATE_LIMITED, NO_RELEASE, BAD_MANIFEST, NEWER_FORMAT, HTTP, SIZE_MISMATCH, CHECKSUM, STORAGE,
}

class UpdateException(val failure: Failure, detail: String? = null, cause: Throwable? = null) :
    Exception(detail ?: failure.name, cause)

/** Every text the updater shows for a failure, in one place (both versions use these). */
object UpdateMessages {
    fun of(e: UpdateException): String = of(e.failure, e.message)

    fun of(f: Failure, detail: String? = null): String = when (f) {
        Failure.OFFLINE -> "No connection to GitHub. Check the Wi-Fi and try again."
        Failure.RATE_LIMITED -> "GitHub is limiting requests from this network for now. Try again in an hour."
        Failure.NO_RELEASE -> "No release with an update file was found on GitHub."
        Failure.BAD_MANIFEST -> "The release information on GitHub could not be read" + (detail?.let { " ($it)" } ?: "") + "."
        Failure.NEWER_FORMAT -> "This release needs a newer Flux to install. Update by hand from GitHub once."
        Failure.HTTP -> "GitHub answered with an error" + (detail?.let { " ($it)" } ?: "") + ". Try again later."
        Failure.SIZE_MISMATCH, Failure.CHECKSUM -> "The download was damaged and was deleted. Try again."
        Failure.STORAGE -> "Not enough space to save the update" + (detail?.let { " ($it)" } ?: "") + "."
    }

    /** "12.3 / 59.8 MB". */
    fun progress(done: Long, total: Long): String = "%.1f / %.1f MB".format(java.util.Locale.ROOT, done / 1e6, total / 1e6)
}

/** Release notes (a CHANGELOG section) as plain text for the app: no Markdown, wrapped lines joined. */
object ReleaseNotes {
    fun plain(markdown: String): String {
        val out = mutableListOf<String>()
        for (raw in markdown.replace("\r", "").lines()) {
            val line = raw.replace("**", "").replace("`", "")
            when {
                line.isBlank() -> if (out.lastOrNull()?.isNotEmpty() == true) out += ""
                line.startsWith("#") -> out += line.trimStart('#', ' ')
                // A wrapped line (indented, not a new bullet) continues the previous one.
                raw.startsWith(" ") && !line.trimStart().startsWith("- ") && out.lastOrNull()?.isNotEmpty() == true ->
                    out[out.lastIndex] = out.last() + " " + line.trim()
                else -> out += line.trimEnd()
            }
        }
        return out.joinToString("\n").trim()
    }
}
