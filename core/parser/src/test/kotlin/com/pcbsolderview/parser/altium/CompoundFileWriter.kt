package com.pcbsolderview.parser.altium

import java.io.ByteArrayOutputStream

/**
 * Test-only writer of minimal compound files (version 3, 512-byte sectors, one level of
 * storages), so the reader can be checked against known content. Streams under 4096 bytes go
 * to the mini stream, like Altium's small streams.
 */
object CompoundFileWriter {
    private const val SECTOR = 512
    private const val MINI = 64
    private const val END = -2
    private const val FREE = -1
    private const val FATSECT = -3

    /** [streams]: path ("Name" or "Storage/Name") → bytes. */
    fun write(streams: Map<String, ByteArray>): ByteArray {
        // Directory entries: root, storages, streams.
        class E(val name: String, val type: Int, var start: Int = END, var size: Int = 0, val kids: MutableList<Int> = ArrayList())
        val dir = arrayListOf(E("Root Entry", 5))
        val storages = HashMap<String, Int>()
        for ((path, data) in streams) {
            val parts = path.split('/')
            var parent = 0
            if (parts.size == 2) parent = storages.getOrPut(parts[0]) { dir += E(parts[0], 1); dir[0].kids += dir.size - 1; dir.size - 1 }
            dir += E(parts.last(), 2, size = data.size)
            dir[parent].kids += dir.size - 1
        }
        val dataOf = streams.values.toList()

        // Mini stream for small streams.
        val mini = ByteArrayOutputStream(); val miniFat = ArrayList<Int>()
        val big = ArrayList<Pair<Int, ByteArray>>()
        var k = 0
        for (i in dir.indices) {
            if (dir[i].type != 2) continue
            val data = dataOf[k++]
            if (data.size < 4096) {
                val first = miniFat.size
                val n = maxOf(1, (data.size + MINI - 1) / MINI)
                for (j in 0 until n) miniFat += if (j == n - 1) END else first + j + 1
                mini.write(data); mini.write(ByteArray(n * MINI - data.size))
                dir[i].start = first
            } else big += i to data
        }
        val sectors = ArrayList<ByteArray>()
        val fat = ArrayList<Int>()
        fun chain(data: ByteArray): Int {
            val first = sectors.size
            val n = maxOf(1, (data.size + SECTOR - 1) / SECTOR)
            for (j in 0 until n) {
                sectors += data.copyOfRange(j * SECTOR, minOf(data.size, (j + 1) * SECTOR)).copyOf(SECTOR)
                fat += if (j == n - 1) END else first + j + 1
            }
            return first
        }
        for ((i, data) in big) dir[i].start = chain(data)
        val miniBytes = mini.toByteArray()
        if (miniBytes.isNotEmpty()) { dir[0].start = chain(miniBytes); dir[0].size = miniBytes.size }
        val miniFatStart = if (miniFat.isEmpty()) END else chain(le(miniFat.toIntArray()))
        val miniFatSectors = (miniFat.size * 4 + SECTOR - 1) / SECTOR

        // Directory: siblings as a right-leaning chain (valid tree, colour ignored by readers).
        val dirBytes = ByteArray(((dir.size + 3) / 4) * 4 * 128)
        for ((i, e) in dir.withIndex()) {
            val o = i * 128
            val name = e.name.toByteArray(Charsets.UTF_16LE)
            System.arraycopy(name, 0, dirBytes, o, name.size)
            put16(dirBytes, o + 0x40, name.size + 2)
            dirBytes[o + 0x42] = e.type.toByte(); dirBytes[o + 0x43] = 1
            put32(dirBytes, o + 0x44, -1); put32(dirBytes, o + 0x48, -1); put32(dirBytes, o + 0x4C, e.kids.firstOrNull() ?: -1)
            put32(dirBytes, o + 0x74, e.start); put32(dirBytes, o + 0x78, e.size)
        }
        for (e in dir) for ((a, b) in e.kids.zipWithNext()) put32(dirBytes, a * 128 + 0x48, b)
        for (i in dir.size until dirBytes.size / 128) { put32(dirBytes, i * 128 + 0x44, -1); put32(dirBytes, i * 128 + 0x48, -1); put32(dirBytes, i * 128 + 0x4C, -1) }
        val dirStart = chain(dirBytes)

        // FAT sectors at the end (marked FATSECT in the FAT itself).
        var fatSectors = 1
        while ((fat.size + fatSectors) > fatSectors * (SECTOR / 4)) fatSectors++
        val fatStart = sectors.size
        repeat(fatSectors) { fat += FATSECT }
        while (fat.size < fatSectors * (SECTOR / 4)) fat += FREE
        val fatBytes = le(fat.toIntArray())
        repeat(fatSectors) { j -> sectors += fatBytes.copyOfRange(j * SECTOR, (j + 1) * SECTOR) }

        val h = ByteArray(SECTOR)
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()).copyInto(h)
        put16(h, 0x18, 0x3E); put16(h, 0x1A, 3); put16(h, 0x1C, 0xFFFE); put16(h, 0x1E, 9); put16(h, 0x20, 6)
        put32(h, 0x2C, fatSectors); put32(h, 0x30, dirStart); put32(h, 0x38, 4096)
        put32(h, 0x3C, miniFatStart); put32(h, 0x40, miniFatSectors); put32(h, 0x44, END); put32(h, 0x48, 0)
        for (i in 0 until 109) put32(h, 0x4C + 4 * i, if (i < fatSectors) fatStart + i else FREE)
        val out = ByteArrayOutputStream(); out.write(h); sectors.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Altium record stream: 4-byte length header + NUL-terminated text, per record. */
    fun recordStream(records: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        for (r in records) {
            val b = r.toByteArray(charset("windows-1252")) + 0
            out.write(le(intArrayOf(b.size)))
            out.write(b)
        }
        return out.toByteArray()
    }

    private fun le(v: IntArray) = ByteArray(v.size * 4).also { b -> v.forEachIndexed { i, x -> put32(b, i * 4, x) } }
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun put32(b: ByteArray, o: Int, v: Int) { for (i in 0..3) b[o + i] = (v shr (8 * i)).toByte() }
}
