package com.pcbsolderview.parser

/** Thrown with a message that can be shown to the user as-is. */
open class BoardParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UnsupportedFormatException(message: String = DEFAULT) : BoardParseException(message) {
    companion object {
        const val DEFAULT =
            "Unsupported format. In Altium, export File » Fabrication Outputs » IPC-2581, " +
                "or a Pick & Place CSV (optionally with a BOM CSV)."
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

    /** Only a .SchDoc was chosen. */
    const val SCHDOC_ONLY =
        "Schematics (.SchDoc) can't be shown yet; the schematic view is planned for version 0.1. " +
            "To see the board, export File » Fabrication Outputs » IPC-2581 Files from Altium."

    const val SCHDOC_SKIPPED = "The schematic (.SchDoc) was skipped; the schematic view is planned for version 0.1."
}
