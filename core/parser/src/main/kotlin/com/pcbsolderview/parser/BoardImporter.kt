package com.pcbsolderview.parser

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Schematic
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.parser.altium.PcbDocParser
import com.pcbsolderview.parser.altium.SchDocParser
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

/**
 * What an import produced: always a [board], plus a [schematic] when .SchDoc sheets were among
 * the files. A schematic alone gives an empty board
 * ([SourceFormat.SCHEMATIC_ONLY]).
 */
data class ImportResult(
    val board: Board,
    val schematic: Schematic? = null,
    /** Altium Smart PDF (or any PDF) chosen without a .SchDoc: shown view-only, no selection sync. */
    val schematicPdf: InputFile? = null,
)

/** What a file is, decided from its content (see [BoardImporter.classify]). */
enum class FileKind {
    IPC2581, PICK_AND_PLACE, BOM, ZIP,
    /** Altium native board (.PcbDoc, binary compound file), read by PcbDocParser. */
    ALTIUM_PCBDOC,
    /** Altium schematic sheet (.SchDoc), native compound file or saved as ASCII. */
    ALTIUM_SCHDOC,
    /** PDF (e.g. Altium Smart PDF of the schematic): view-only fallback. */
    PDF,
    UNKNOWN,
}

/**
 * Entry point used by the app: give it every file the user picked (or the contents of a .zip)
 * and it produces one [ImportResult] (a [Board] and the schematic sheets, if any). Pick & Place / IPC-2581 give geometry; a BOM, if present,
 * fills in descriptions, part numbers and manufacturers by designator.
 */
class BoardImporter(private val xml: XmlParserFactory) {

    /**
     * @param onNotice short messages for the user about files that were skipped (e.g. a .PcbDoc
     *   chosen together with an IPC-2581 export); the import still succeeds.
     * @param onProgress 0..1 while parsing the geometry file (last, so a trailing lambda is progress).
     */
    fun import(files: List<InputFile>, onNotice: (String) -> Unit = {}, onProgress: (Float) -> Unit = {}): ImportResult {
        val flat = files.flatMap { if (classify(it) == FileKind.ZIP) unzip(it) else listOf(it) }
        val kinds = flat.associateWith { classify(it) }
        val hasPcbDoc = FileKind.ALTIUM_PCBDOC in kinds.values
        val schFiles = kinds.filterValues { it == FileKind.ALTIUM_SCHDOC }.keys.toList()

        // Board geometry: IPC-2581 > .PcbDoc > Pick & Place.
        val geometryFile = kinds.entries.firstOrNull { it.value == FileKind.IPC2581 }?.key
            ?: kinds.entries.firstOrNull { it.value == FileKind.ALTIUM_PCBDOC }?.key
            ?: kinds.entries.firstOrNull { it.value == FileKind.PICK_AND_PLACE }?.key
        val pdf = kinds.entries.firstOrNull { it.value == FileKind.PDF }?.key?.takeIf { schFiles.isEmpty() }
        if (geometryFile == null && schFiles.isEmpty() && pdf == null) {
            throw UnsupportedFormatException(UnsupportedFormatException.DEFAULT)
        }
        if (hasPcbDoc && geometryFile != null && kinds.getValue(geometryFile) == FileKind.IPC2581) onNotice(NativeFileMessages.pcbDocSkipped(geometryFile.name))

        // Schematic sheets: a damaged sheet next to a board is skipped with a notice.
        val sheets = schFiles.mapNotNull { f ->
            try {
                SchDocParser.parse(f.bytes, f.name)
            } catch (e: BoardParseException) {
                if (geometryFile == null && schFiles.size == 1) throw e
                onNotice(NativeFileMessages.schDocSkipped(f.name, e.message)); null
            }
        }
        if (geometryFile == null && sheets.isEmpty() && pdf == null) throw UnsupportedFormatException(NativeFileMessages.SCHDOC_UNREADABLE)
        val schematic = sheets.takeIf { it.isNotEmpty() }?.let { Schematic(schematicName(it.map { s -> s.name }), it) }
        schematic?.duplicateNotice()?.let(onNotice)

        val board = when {
            geometryFile == null -> Board(name = schematic?.name ?: baseName(pdf!!.name), source = SourceFormat.SCHEMATIC_ONLY)
            kinds.getValue(geometryFile) == FileKind.ALTIUM_PCBDOC -> readPcbDoc(
                geometryFile, kinds.entries.firstOrNull { it.value == FileKind.PICK_AND_PLACE }?.key, schematic, onNotice, onProgress,
            )
            kinds.getValue(geometryFile) == FileKind.IPC2581 -> Ipc2581Parser(xml).parse(
                ByteArrayInputStream(geometryFile.bytes), geometryFile.name, geometryFile.bytes.size.toLong(), onProgress,
            )
            else -> PickPlaceParser.parse(decodeText(geometryFile.bytes), baseName(geometryFile.name))
        }

        val boms = kinds.filterValues { it == FileKind.BOM }.keys.map { readBom(it) }
        return ImportResult(board = boms.fold(board) { b, bom -> b.withBom(bom) }, schematic = schematic, schematicPdf = pdf)
    }

    /**
     * A .PcbDoc board. When it cannot be read, a Pick & Place file opened with it is used instead,
     * else the schematic opens alone (empty board), each with a notice; alone, the error is shown.
     */
    private fun readPcbDoc(
        f: InputFile, pickPlace: InputFile?, schematic: Schematic?, onNotice: (String) -> Unit, onProgress: (Float) -> Unit,
    ): Board = try {
        PcbDocParser.parse(f.bytes, f.name, onNotice, onProgress)
    } catch (e: BoardParseException) {
        val message = NativeFileMessages.pcbDocUnreadable(f.name, e.message)
        when {
            pickPlace != null -> { onNotice(message); PickPlaceParser.parse(decodeText(pickPlace.bytes), baseName(pickPlace.name)) }
            schematic != null -> { onNotice(message); Board(name = schematic.name, source = SourceFormat.SCHEMATIC_ONLY) }
            else -> throw BoardParseException(message, e)
        }
    }

    /** One sheet: its name; several: their common prefix ("Power", "Power_IO" → "Power"), else the first. */
    private fun schematicName(names: List<String>): String {
        if (names.size == 1) return names[0]
        val prefix = names.reduce { a, b -> a.commonPrefixWith(b, ignoreCase = true) }.trimEnd(' ', '_', '-', '.')
        return prefix.ifBlank { names[0] }
    }

    fun classify(f: InputFile): FileKind {
        val b = f.bytes
        if (isCompoundFile(b)) return altiumNativeKind(f)
        if (b.size >= 5 && String(b, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return FileKind.PDF
        if (b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()) {
            return if (zipHas(b) { it.startsWith("xl/") }) FileKind.BOM else FileKind.ZIP
        }
        val head = decodeText(b.copyOf(minOf(b.size, 64 * 1024)))
        val trimmed = head.trimStart()
        if (SchDocParser.looksLikeAsciiSchDoc(trimmed)) return FileKind.ALTIUM_SCHDOC
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
