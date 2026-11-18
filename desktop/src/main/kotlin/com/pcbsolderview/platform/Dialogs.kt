package com.pcbsolderview.platform

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.UIManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Native Windows dialogs (the Android SAF picker's role). */
object FileDialogs {
    private var lastDir: String? = null

    /** Multi-select open dialog filtered to the supported types; null when cancelled. */
    fun openFiles(owner: Frame): List<File>? {
        val d = FileDialog(owner, "Open board, schematic, BOM or PDF", FileDialog.LOAD).apply {
            isMultipleMode = true
            directory = lastDir
            // Windows ignores FilenameFilter; a wildcard list in the file name sets the filter.
            file = SupportedFiles.EXTENSIONS.joinToString(";") { "*.$it" }
            setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in SupportedFiles.EXTENSIONS }
        }
        d.isVisible = true
        val files = d.files.toList()
        if (files.isEmpty()) return null
        lastDir = d.directory
        return files
    }

    /** Folder chooser (an Altium project folder: every supported file in it is opened). */
    fun openFolder(owner: Frame): File? {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val c = JFileChooser(lastDir).apply {
            dialogTitle = "Open every board, schematic and BOM file in a folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (c.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return null
        return c.selectedFile?.also { lastDir = it.parent }
    }

    fun showHelp(owner: Frame) {
        JOptionPane.showMessageDialog(
            owner,
            """
            Mouse
              Wheel                     zoom at the cursor
              Ctrl + wheel              rotate 90°
              Left drag                 pan (draws with the Pen tool, erases with the Eraser)
              Shift + left drag         rotate (locks near 0/90/180/270°)
                                        (Settings › Rotation turns rotating off)
              Middle or right drag      pan
              Click                     select a part or net
              Double click              fit to window
              Right click on a part     status ring (OK / Problem / Check / Info)
              Right drag on empty board box selection
              Right click "Highlight…"   (part panel) highlight all identical parts

            Keys
              Ctrl+O / Ctrl+Shift+O     open files / open a folder
              Ctrl+F                    search parts
              Ctrl+Z                    undo
              Esc                       close panel, clear selection, leave drawing, then leave the board
              F                         fit
              1 / 2 / 3                 board / board + schematic / schematic
            Files can also be dropped on the window.
            """.trimIndent(),
            "Mouse and keys",
            JOptionPane.PLAIN_MESSAGE,
        )
    }
}

/** Window size, position and maximised state, remembered between runs (window.json). */
@Serializable
data class WindowPrefs(
    val x: Float? = null,
    val y: Float? = null,
    val width: Float = 1400f,
    val height: Float = 900f,
    val maximized: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(dirs: AppDirs): WindowPrefs =
            runCatching { json.decodeFromString<WindowPrefs>(dirs.window.readText()) }.getOrDefault(WindowPrefs())
                .let { if (it.width < 400f || it.height < 300f) it.copy(width = 1400f, height = 900f) else it }

        fun save(dirs: AppDirs, s: WindowState) {
            val pos = s.position
            val p = WindowPrefs(
                x = if (pos.isSpecified) pos.x.value else null,
                y = if (pos.isSpecified) pos.y.value else null,
                width = s.size.width.value,
                height = s.size.height.value,
                maximized = s.placement == WindowPlacement.Maximized,
            )
            runCatching { dirs.window.writeAtomically(json.encodeToString(serializer(), p)) }
        }
    }
}
