package com.pcbsolderview.parser.text

/**
 * Minimal CSV / TSV / space-aligned table reader (RFC 4180 quoting). Altium writes CSV with
 * quoted fields, and its ".txt" pick & place variant as space-aligned columns.
 */
object DelimitedText {

    enum class Mode { COMMA, SEMICOLON, TAB, WHITESPACE }

    fun detectMode(headerLine: String): Mode {
        val counts = mapOf(
            Mode.COMMA to countOutsideQuotes(headerLine, ','),
            Mode.SEMICOLON to countOutsideQuotes(headerLine, ';'),
            Mode.TAB to countOutsideQuotes(headerLine, '\t'),
        )
        val best = counts.maxBy { it.value }
        return if (best.value == 0) Mode.WHITESPACE else best.key
    }

    fun split(line: String, mode: Mode): List<String> = when (mode) {
        Mode.COMMA -> splitDelimited(line, ',')
        Mode.SEMICOLON -> splitDelimited(line, ';')
        Mode.TAB -> splitDelimited(line, '\t')
        Mode.WHITESPACE -> splitWhitespace(line)
    }

    /**
     * Splits a whole document into logical lines, keeping newlines that are inside quotes.
     */
    fun lines(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> { inQuotes = !inQuotes; sb.append(c) }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    out.add(sb.toString()); sb.setLength(0)
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var n = 0; var q = false
        for (c in line) { if (c == '"') q = !q else if (c == d && !q) n++ }
        return n
    }

    private fun splitDelimited(line: String, d: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (q) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else q = false
                } else sb.append(c)
            } else when (c) {
                '"' -> q = true
                d -> { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun splitWhitespace(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var has = false
        for (c in line) {
            when {
                c == '"' -> { q = !q; has = true }
                c.isWhitespace() && !q -> { if (has) { out.add(sb.toString()); sb.setLength(0); has = false } }
                else -> { sb.append(c); has = true }
            }
        }
        if (has) out.add(sb.toString())
        return out
    }

    /** Joins two-word Altium headers ("Mid X", "Ref Y", "Pad X") so whitespace splitting works. */
    fun joinMultiWordHeaders(line: String): String =
        line.replace(Regex("""\b(Mid|Ref|Pad|Center|Centre)[ \-]+([XY])\b""", RegexOption.IGNORE_CASE), "$1$2")
}

/** A parsed table: header cells (original text) plus data rows, all the same length as the header. */
class Table(val header: List<String>, val rows: List<List<String>>, val preamble: List<String>) {
    private val normalized = header.map { normalizeHeader(it) }

    /** Index of the first column whose normalized name equals one of [aliases] (in priority order). */
    fun column(vararg aliases: String): Int? {
        for (a in aliases) {
            val i = normalized.indexOf(a)
            if (i >= 0) return i
        }
        // Allow a unit suffix: "centerxmm", "midxmil".
        for (a in aliases) {
            val i = normalized.indexOfFirst { n ->
                n.startsWith(a) && n.removePrefix(a).let { it in setOf("mm", "mil", "mils", "in", "inch", "um") }
            }
            if (i >= 0) return i
        }
        return null
    }

    companion object {
        /**
         * Finds the header row (first row for which [isHeader] is true) and returns the table.
         * Rows shorter than the header are padded with ""; extra cells are joined into the last column.
         */
        fun read(text: String, isHeader: (List<String>) -> Boolean): Table? {
            val lines = DelimitedText.lines(text)
            for ((idx, raw) in lines.withIndex()) {
                if (raw.isBlank()) continue
                val mode = DelimitedText.detectMode(raw)
                val headerLine = if (mode == DelimitedText.Mode.WHITESPACE) DelimitedText.joinMultiWordHeaders(raw) else raw
                val header = DelimitedText.split(headerLine, mode)
                if (!isHeader(header.map { normalizeHeader(it) })) continue

                val rows = lines.drop(idx + 1).filter { it.isNotBlank() }.map { line ->
                    val cells = DelimitedText.split(line, mode)
                    when {
                        cells.size == header.size -> cells
                        cells.size < header.size -> cells + List(header.size - cells.size) { "" }
                        else -> cells.take(header.size - 1) +
                            cells.drop(header.size - 1).joinToString(if (mode == DelimitedText.Mode.WHITESPACE) " " else ",")
                    }
                }
                return Table(header, rows, lines.take(idx))
            }
            return null
        }
    }
}
