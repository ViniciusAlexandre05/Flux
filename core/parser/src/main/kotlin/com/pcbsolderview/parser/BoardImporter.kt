package com.pcbsolderview.parser

import com.pcbsolderview.model.Board
import com.pcbsolderview.parser.bom.Bom
import com.pcbsolderview.parser.bom.BomParser
import com.pcbsolderview.parser.bom.XlsxReader
import com.pcbsolderview.parser.bom.withBom
import com.pcbsolderview.parser.ipc2581.Ipc2581Parser
import com.pcbsolderview.parser.pnp.PickPlaceParser
import com.pcbsolderview.parser.text.Table
import com.pcbsolderview.parser.text.decodeText
import com.pcbsolderview.parser.text.normalizeHeader
import com.pcbsolderview.parser.xml.XmlParserFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class InputFile(val name: String, val bytes: ByteArray)

/** What a file is, decided from its content (see [BoardImporter.classify]). */
enum class FileKind {
    IPC2581, PICK_AND_PLACE, BOM, ZIP,
    /** Altium native board (.PcbDoc, binary compound file): recognised, not readable yet. */
    ALTIUM_PCBDOC,
    /** Altium native schematic (.SchDoc, binary compound file): recognised, not readable yet. */
    ALTIUM_SCHDOC,
    UNKNOWN,
}

/**
 * Entry point used by the app: give it every file the user picked (or the contents of a .zip)
 * and it produces one [Board]. Pick & Place / IPC-2581 give geometry; a BOM, if present,
 * fills in descriptions, part numbers and manufacturers by designator.
 */
class BoardImporter(private val xml: XmlParserFactory) {

    /**
     * @param onNotice short messages for the user about files that were skipped (e.g. a .PcbDoc
     *   chosen together with an IPC-2581 export); the import still succeeds.
     * @param onProgress 0..1 while parsing the geometry file (last, so a trailing lambda is progress).
     */
    fun import(files: List<InputFile>, onNotice: (String) -> Unit = {}, onProgress: (Float) -> Unit = {}): Board {
        val flat = files.flatMap { if (classify(it) == FileKind.ZIP) unzip(it) else listOf(it) }
        val kinds = flat.associateWith { classify(it) }
        val hasPcbDoc = FileKind.ALTIUM_PCBDOC in kinds.values
        val hasSchDoc = FileKind.ALTIUM_SCHDOC in kinds.values

        val geometryFile = kinds.entries.firstOrNull { it.value == FileKind.IPC2581 }?.key
            ?: kinds.entries.firstOrNull { it.value == FileKind.PICK_AND_PLACE }?.key
            ?: throw UnsupportedFormatException(
                when {
                    hasPcbDoc -> NativeFileMessages.PCBDOC_ONLY
                    hasSchDoc -> NativeFileMessages.SCHDOC_ONLY
                    else -> UnsupportedFormatException.DEFAULT
                },
            )
        if (hasPcbDoc) onNotice(NativeFileMessages.pcbDocSkipped(geometryFile.name))
        if (hasSchDoc) onNotice(NativeFileMessages.SCHDOC_SKIPPED)

        val board = when (kinds.getValue(geometryFile)) {
            FileKind.IPC2581 -> Ipc2581Parser(xml).parse(
                ByteArrayInputStream(geometryFile.bytes), geometryFile.name, geometryFile.bytes.size.toLong(), onProgress,
            )
            else -> PickPlaceParser.parse(decodeText(geometryFile.bytes), baseName(geometryFile.name))
        }

        val boms = kinds.filterValues { it == FileKind.BOM }.keys.map { readBom(it) }
        return boms.fold(board) { b, bom -> b.withBom(bom) }
    }

    fun classify(f: InputFile): FileKind {
        val b = f.bytes
        if (isCompoundFile(b)) return altiumNativeKind(f)
        if (b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()) {
            return if (zipHas(b) { it.startsWith("xl/") }) FileKind.BOM else FileKind.ZIP
        }
        val head = decodeText(b.copyOf(minOf(b.size, 64 * 1024)))
        val trimmed = head.trimStart()
        if (trimmed.startsWith("<")) {
            return if (trimmed.contains("IPC-2581", ignoreCase = true) || trimmed.contains("<Content", ignoreCase = true)) FileKind.IPC2581 else FileKind.UNKNOWN
        }
        if (Table.read(head) { PickPlaceParser.looksLikePickPlace(it) } != null) return FileKind.PICK_AND_PLACE
        if (Table.read(head) { BomParser.looksLikeBom(it) } != null) return FileKind.BOM
        return FileKind.UNKNOWN
    }

    private fun readBom(f: InputFile): Bom {
        val b = f.bytes
        if (b.size >= 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()) {
            val rows = XlsxReader(xml).readFirstSheet(b)
            val h = rows.indexOfFirst { r -> BomParser.looksLikeBom(r.map { normalizeHeader(it) }) }
            if (h < 0) throw BoardParseException("BOM spreadsheet ${f.name} has no Designator column.")
            return BomParser.fromTable(rows[h], rows.drop(h + 1))
        }
        return BomParser.parse(decodeText(b))
    }

    private fun unzip(f: InputFile): List<InputFile> {
        val out = ArrayList<InputFile>()
        ZipInputStream(ByteArrayInputStream(f.bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || e.name.startsWith("__MACOSX")) continue
                out += InputFile(e.name.substringAfterLast('/'), zip.readBytes())
            }
        }
        return out
    }

    private fun zipHas(bytes: ByteArray, pred: (String) -> Boolean): Boolean =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.any { pred(it.name) }
        }

    private fun baseName(n: String) = n.substringAfterLast('/').substringBeforeLast('.')

    companion object {
        private val OLE_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        /** Microsoft compound file (the container Altium uses for .PcbDoc / .SchDoc). */
        fun isCompoundFile(b: ByteArray) = b.size >= 8 && (0 until 8).all { b[it] == OLE_MAGIC[it] }

        /**
         * Board or schematic? By extension first; a renamed or shared file is told apart by the
         * records inside (`|RECORD=Board` in boards, `Schematic` header / `|RECORD=31|` sheet in
         * schematics). Other compound files are UNKNOWN.
         */
        fun altiumNativeKind(f: InputFile): FileKind {
            val ext = f.name.substringAfterLast('.', "").lowercase()
            if (ext == "pcbdoc") return FileKind.ALTIUM_PCBDOC
            if (ext == "schdoc") return FileKind.ALTIUM_SCHDOC
            val text = String(f.bytes, Charsets.ISO_8859_1)
            return when {
                "|RECORD=Board" in text || "PCB 6.0 Binary File" in text -> FileKind.ALTIUM_PCBDOC
                "Protel for Windows - Schematic" in text || "|RECORD=31|" in text -> FileKind.ALTIUM_SCHDOC
                else -> FileKind.UNKNOWN
            }
        }
    }
}
