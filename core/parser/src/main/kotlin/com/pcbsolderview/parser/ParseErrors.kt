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
