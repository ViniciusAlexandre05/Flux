package com.pcbsolderview.parser.altium

/**
 * One Altium property record, e.g. `|RECORD=2|OWNERINDEX=5|LOCATION.X=100|NAME=VCC`.
 * Keys are case-insensitive (native files mix `Text=` and `TEXT=`); stored upper-case.
 */
class AltiumRecord(val props: Map<String, String>) {
    operator fun get(key: String): String? = props[key.uppercase()]

    val type: Int? get() = this["RECORD"]?.trim()?.toIntOrNull()

    fun int(key: String, default: Int = 0): Int = this[key]?.trim()?.toIntOrNull() ?: this[key]?.trim()?.toDoubleOrNull()?.toInt() ?: default

    fun double(key: String): Double? = this[key]?.trim()?.toDoubleOrNull()

    fun bool(key: String): Boolean = this[key]?.trim()?.uppercase().let { it == "T" || it == "TRUE" }

    /**
     * Schematic coordinate in micrometres: value in 10 mil units plus the optional `_FRAC` part
     * (1/100000 of a unit), e.g. `LOCATION.X` + `LOCATION.X_FRAC`.
     */
    fun coordUm(key: String): Long {
        val whole = this[key]?.trim()?.toDoubleOrNull() ?: 0.0
        val frac = this["${key}_FRAC"]?.trim()?.toDoubleOrNull() ?: 0.0
        return Math.round((whole + frac / 100_000.0) * SCH_UNIT_UM)
    }

    override fun toString() = props.entries.joinToString("|", prefix = "|") { "${it.key}=${it.value}" }

    companion object {
        /** One schematic unit (10 mil) in micrometres. */
        const val SCH_UNIT_UM = 254.0
        private const val UTF8_PREFIX = "%UTF8%"
        private val LINE_BREAKS = Regex("[\\r\\n]+")
        private val WINDOWS_1252 = charset("windows-1252")

        /** Parses `|A=1|B=2` (leading/trailing pipes and a trailing NUL are optional). */
        fun parse(text: String): AltiumRecord {
            val map = LinkedHashMap<String, String>()
            for (part in text.trimEnd('\u0000', '\r', '\n').split('|')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).trim().uppercase()
                // First occurrence wins; Altium never repeats a key in one record.
                // Line breaks (seen in descriptions) cannot exist in ASCII files: one record per line.
                map.putIfAbsent(key, part.substring(eq + 1).replace(LINE_BREAKS, " "))
            }
            // `%UTF8%TEXT=` carries the exact text when the plain key had to be approximated.
            for (k in map.keys.filter { it.startsWith(UTF8_PREFIX) }) {
                val v = map.getValue(k)
                map[k.removePrefix(UTF8_PREFIX)] = runCatching { String(v.toByteArray(WINDOWS_1252), Charsets.UTF_8) }.getOrDefault(v)
            }
            return AltiumRecord(map)
        }
    }
}

object AltiumRecords {
    /**
     * Records of a native stream: each is a 4-byte little-endian header (low 24 bits = length,
     * high byte = 0 for text, 1 for binary) followed by the payload. Text payloads are
     * Windows-1252 `|KEY=value` lists ending in NUL; binary payloads are skipped here and
     * returned as empty records so record indices (OWNERINDEX) stay right.
     */
    fun fromStream(data: ByteArray): List<AltiumRecord> {
        val out = ArrayList<AltiumRecord>()
        var p = 0
        while (p + 4 <= data.size) {
            val header = CompoundFile.le32(data, p)
            val len = header and 0x00FFFFFF
            val binary = (header ushr 24) != 0
            p += 4
            if (len < 0 || p + len > data.size) break
            out += if (binary) AltiumRecord(emptyMap()) else AltiumRecord.parse(decode(data, p, len))
            p += len
        }
        return out
    }

    /** Records of an ASCII file (`.SchDoc` saved as ASCII): one record per line. */
    fun fromText(text: String): List<AltiumRecord> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("|") }.map { AltiumRecord.parse(it) }.toList()

    /** Text payload: Windows-1252 unless it is valid UTF-8 (Altium writes `%UTF8%` copies of keys). */
    private fun decode(b: ByteArray, off: Int, len: Int): String = String(b, off, len, WINDOWS_1252)

    private val WINDOWS_1252 = charset("windows-1252")
}
