package com.pcbsolderview.parser.altium

import com.pcbsolderview.parser.BoardParseException

/**
 * Little-endian cursor over [bytes] between [start] and [end], for Altium's binary primitive
 * streams. Reads past [end] throw [BoardParseException] naming the stream and offset (never an
 * IndexOutOfBounds), so one damaged record can be skipped and counted.
 */
internal class BinaryReader(
    private val bytes: ByteArray,
    private val stream: String,
    start: Int = 0,
    val end: Int = bytes.size,
) {
    var pos: Int = start
        private set

    val remaining: Int get() = end - pos
    val atEnd: Boolean get() = pos >= end

    private fun need(n: Int) {
        if (n < 0 || pos + n > end) fail("needs $n bytes, ${end - pos} left")
    }

    fun fail(msg: String): Nothing = throw BoardParseException("$stream: $msg at offset $pos")

    fun u8(): Int { need(1); return bytes[pos++].toInt() and 0xFF }

    fun u16(): Int { need(2); val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }

    fun i32(): Int {
        need(4)
        val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or ((bytes[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun f64(): Double {
        need(8)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
        pos += 8
        return java.lang.Double.longBitsToDouble(v)
    }

    fun skip(n: Int) { need(n); pos += n }

    /** [n] bytes as Windows-1252 text, trailing NULs removed. */
    fun text(n: Int): String { need(n); val s = String(bytes, pos, n, WINDOWS_1252); pos += n; return s.trimEnd('\u0000') }

    /**
     * A sub-record: `u32 length` + payload. Returns a reader bounded to the payload and moves this
     * reader past it, whatever the caller reads from it (unknown trailing fields are normal).
     */
    fun sub(): BinaryReader {
        val len = i32()
        if (len < 0) fail("negative sub-record length $len")
        need(len)
        val r = BinaryReader(bytes, stream, pos, pos + len)
        pos += len
        return r
    }

    private companion object {
        val WINDOWS_1252 = charset("windows-1252")
    }
}
