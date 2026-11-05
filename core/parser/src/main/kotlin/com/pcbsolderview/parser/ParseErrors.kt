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
    /** Only a .PcbDoc was chosen. */
    const val PCBDOC_ONLY =
        "Altium .PcbDoc files can't be opened yet, and they are not the recommended format: .PcbDoc is " +
            "Altium's private binary format. In Altium, export File » Fabrication Outputs » IPC-2581 Files " +
            "and open that file instead; it contains the same board."

    /** A .PcbDoc was chosen together with a supported board file. */
    fun pcbDocSkipped(openedFrom: String) =
        "The .PcbDoc was skipped (Altium's native format is not the recommended one). Board opened from $openedFrom."

    /** A .PcbDoc was chosen with schematics but no supported board file. */
    const val PCBDOC_WITH_SCHEMATIC =
        "The .PcbDoc was skipped (not readable yet). Showing the schematic only; to see the board too, " +
            "export File » Fabrication Outputs » IPC-2581 Files from Altium and open it with the .SchDoc."

    /** A schematic sheet could not be read but the rest opened. */
    fun schDocSkipped(name: String, reason: String?) =
        "The schematic $name was skipped" + (reason?.let { ": $it" } ?: ".")

    const val SCHDOC_UNREADABLE = "None of the schematic files could be read."
}
