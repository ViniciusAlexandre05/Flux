package com.pcbsolderview.parser

/** Thrown with a message that can be shown to the user as-is. */
open class BoardParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UnsupportedFormatException(message: String = DEFAULT) : BoardParseException(message) {
    companion object {
        const val DEFAULT =
            "Unsupported format. In Altium, export File » Fabrication Outputs » IPC-2581, " +
                "or a Pick & Place CSV (optionally with a BOM CSV). Schematics: open the .SchDoc files."
    }
}

/** User-facing texts for Altium's native files (.PcbDoc, .SchDoc). */
object NativeFileMessages {
    /** A .PcbDoc was chosen together with an IPC-2581 export (the IPC file is used). */
    fun pcbDocSkipped(openedFrom: String) =
        "Board opened from $openedFrom (IPC-2581); the .PcbDoc chosen with it was not needed."

    /** A .PcbDoc could not be read. */
    fun pcbDocUnreadable(name: String, reason: String?) =
        "Could not read $name" + (reason?.trimEnd('.')?.let { " ($it)" } ?: "") + ". In Altium, export File » Fabrication Outputs » " +
            "IPC-2581 Files and open that file instead; it contains the same board."

    /** A schematic sheet could not be read but the rest opened. */
    fun schDocSkipped(name: String, reason: String?) =
        "The schematic $name was skipped" + (reason?.let { ": $it" } ?: ".")

    const val SCHDOC_UNREADABLE = "None of the schematic files could be read."
}
