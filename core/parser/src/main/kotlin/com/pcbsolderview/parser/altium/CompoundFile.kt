package com.pcbsolderview.parser.altium

import com.pcbsolderview.parser.BoardParseException

/**
 * Reader for Microsoft compound files (MS-CFB, "OLE2"): the container Altium uses for .SchDoc,
 * .PcbDoc, .SchLib and .PcbLib. Pure Kotlin, read-only, whole file in memory.
 *
 * Streams are addressed by their path, storages separated by '/', e.g. `FileHeader` or
 * `Components6/Data`. Names are matched case-insensitively (as the format specifies).
 */
class CompoundFile(private val bytes: ByteArray) {

    /** One stream (file) inside the container. */
    class Entry internal constructor(val path: String, internal val start: Int, val size: Long)

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniStream: ByteArray
    private val entries: Map<String, Entry>

    init {
        if (bytes.size < 512 || !isCompoundFile(bytes)) throw BoardParseException("Not an Altium/compound file (bad signature).")
        val sectorShift = u16(0x1E)
        val miniShift = u16(0x20)
        if (sectorShift !in 7..16 || miniShift !in 1..sectorShift) throw BoardParseException("Damaged compound file (sector size).")
        sectorSize = 1 shl sectorShift
        miniSectorSize = 1 shl miniShift
        miniCutoff = u32(0x38).toLong() and 0xFFFFFFFFL

        // FAT sector list: 109 entries in the header, the rest in a DIFAT chain.
        val fatSectorCount = u32(0x2C)
        val fatSectors = ArrayList<Int>(fatSectorCount.coerceIn(0, 1 shl 20))
        for (i in 0 until 109) {
            if (fatSectors.size >= fatSectorCount) break
            fatSectors += u32(0x4C + 4 * i)
        }
        var difat = u32(0x44)
        var guard = 0
        while (fatSectors.size < fatSectorCount && difat >= 0 && guard++ < 1_000_000) {
            val off = sectorOffset(difat)
            val perSector = sectorSize / 4 - 1
            for (i in 0 until perSector) {
                if (fatSectors.size >= fatSectorCount) break
                fatSectors += u32(off + 4 * i)
            }
            difat = u32(off + 4 * perSector)
        }
        val perFat = sectorSize / 4
        fat = IntArray(fatSectors.size * perFat)
        fatSectors.forEachIndexed { k, s ->
            val off = sectorOffset(s)
            for (i in 0 until perFat) fat[k * perFat + i] = if (off + 4 * i + 4 <= bytes.size) u32(off + 4 * i) else FREESECT
        }

        val dir = readChain(u32(0x30), -1)
        val miniFatBytes = if (u32(0x40) > 0) readChain(u32(0x3C), -1) else ByteArray(0)
        miniFat = IntArray(miniFatBytes.size / 4) { le32(miniFatBytes, 4 * it) }

        // Directory: 128-byte entries; entry 0 is the root, whose chain is the mini stream.
        val count = dir.size / 128
        fun name(i: Int): String {
            val o = i * 128
            val len = ((dir[o + 0x40].toInt() and 0xFF) or ((dir[o + 0x41].toInt() and 0xFF) shl 8)).coerceIn(0, 64)
            return String(dir, o, (len - 2).coerceAtLeast(0), Charsets.UTF_16LE)
        }
        fun type(i: Int) = dir[i * 128 + 0x42].toInt()
        fun left(i: Int) = le32(dir, i * 128 + 0x44)
        fun right(i: Int) = le32(dir, i * 128 + 0x48)
        fun child(i: Int) = le32(dir, i * 128 + 0x4C)
        fun start(i: Int) = le32(dir, i * 128 + 0x74)
        fun size(i: Int): Long {
            val lo = le32(dir, i * 128 + 0x78).toLong() and 0xFFFFFFFFL
            // Version 3 files may leave garbage in the high half.
            val hi = if (sectorSize == 512) 0L else le32(dir, i * 128 + 0x7C).toLong() and 0xFFFFFFFFL
            return lo or (hi shl 32)
        }
        if (count == 0 || type(0) != 5) throw BoardParseException("Damaged compound file (no root entry).")
        miniStream = readChain(start(0), size(0))

        val found = LinkedHashMap<String, Entry>()
        val seen = HashSet<Int>()
        fun walk(i: Int, prefix: String) {
            if (i < 0 || i >= count || !seen.add(i)) return
            walk(left(i), prefix)
            val path = prefix + name(i)
            when (type(i)) {
                2 -> found[path.lowercase()] = Entry(path, start(i), size(i))
                1 -> walk(child(i), "$path/")
            }
            walk(right(i), prefix)
        }
        walk(child(0), "")
        entries = found
    }

    /** Every stream path, in directory order. */
    val streams: List<String> get() = entries.values.map { it.path }

    fun has(path: String) = path.lowercase() in entries

    /** Contents of the stream at [path], or null when there is none. */
    fun read(path: String): ByteArray? {
        val e = entries[path.lowercase()] ?: return null
        if (e.size > Int.MAX_VALUE) throw BoardParseException("Stream ${e.path} is too large.")
        return if (e.size < miniCutoff) readMini(e.start, e.size.toInt()) else readChain(e.start, e.size)
    }

    private fun sectorOffset(s: Int): Int {
        val off = (s.toLong() + 1) * sectorSize
        if (s < 0 || off + sectorSize > bytes.size + sectorSize.toLong()) throw BoardParseException("Damaged compound file (sector $s out of range).")
        return off.toInt()
    }

    /** Follows a FAT chain; [size] < 0 means "the whole chain". */
    private fun readChain(first: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(if (size > 0) size.toInt() else sectorSize)
        var s = first
        var guard = 0
        while (s >= 0 && s < fat.size && guard++ <= fat.size) {
            val off = sectorOffset(s)
            out.write(bytes, off, minOf(sectorSize, bytes.size - off).coerceAtLeast(0))
            if (size in 0..out.size().toLong()) break
            s = fat[s]
        }
        val all = out.toByteArray()
        return if (size in 0 until all.size) all.copyOf(size.toInt()) else all
    }

    private fun readMini(first: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var s = first
        var pos = 0
        var guard = 0
        while (pos < size && s >= 0 && guard++ <= miniFat.size) {
            val off = s * miniSectorSize
            if (off >= miniStream.size) break
            val n = minOf(miniSectorSize, size - pos, miniStream.size - off)
            System.arraycopy(miniStream, off, out, pos, n)
            pos += n
            s = if (s < miniFat.size) miniFat[s] else ENDOFCHAIN
        }
        return out
    }

    private fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(o: Int) = le32(bytes, o)

    companion object {
        private const val FREESECT = -1
        private const val ENDOFCHAIN = -2
        private val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        fun isCompoundFile(b: ByteArray) = b.size >= 8 && (0 until 8).all { b[it] == MAGIC[it] }

        internal fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
