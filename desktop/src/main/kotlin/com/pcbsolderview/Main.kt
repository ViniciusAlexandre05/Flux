package com.pcbsolderview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pcbsolderview.platform.AppDirs
import com.pcbsolderview.platform.BuildInfo
import com.pcbsolderview.platform.FileDialogs
import com.pcbsolderview.platform.WindowPrefs
import com.pcbsolderview.render.ViewMode
import com.pcbsolderview.ui.BackDispatcher
import com.pcbsolderview.ui.BoardScreen
import com.pcbsolderview.ui.DesktopCommands
import com.pcbsolderview.ui.PcbTheme
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent as AwtKeyEvent
import java.io.File

/** Flux for Windows. Arguments: files or folders to open. */
fun main(args: Array<String>) {
    val dirs = AppDirs.default()
    val saved = WindowPrefs.load(dirs)
    application {
        val windowState = rememberWindowState(
            placement = if (saved.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (saved.x != null && saved.y != null) WindowPosition(saved.x.dp, saved.y.dp) else WindowPosition.PlatformDefault,
            size = DpSize(saved.width.dp, saved.height.dp),
        )
        val vm = remember { BoardViewModel(dirs) }
        LaunchedEffect(Unit) {
            val files = args.map(::File).filter { it.exists() }
            if (files.isNotEmpty()) vm.open(files)
        }
        FluxWindow(vm, windowState, onClose = {
            WindowPrefs.save(dirs, windowState)
            vm.close()
            exitApplication()
        })
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ApplicationScope.FluxWindow(vm: BoardViewModel, windowState: WindowState, onClose: () -> Unit) {
    val state by vm.state.collectAsState()
    val appearance by vm.appearance.collectAsState()
    var about by remember { mutableStateOf(false) }
    val title = state.scene?.let { "${state.boardTitle ?: it.board.name} — Flux" } ?: "Flux"

    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = title,
        icon = painterResource("flux.svg"),
        onKeyEvent = { e -> handleKey(e, vm) },
    ) {
        window.minimumSize = java.awt.Dimension(800, 560)
        val openFiles = { FileDialogs.openFiles(window)?.let(vm::open); Unit }
        FluxMenuBar(vm, state, openFiles, onAbout = { about = true })

        val dropTarget = remember {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val t = event.awtTransferable
                    if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                    @Suppress("UNCHECKED_CAST")
                    val files = (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>).orEmpty()
                    if (files.isEmpty()) return false
                    vm.open(files)
                    return true
                }
            }
        }
        PcbTheme(appearance) {
            Box(
                Modifier.fillMaxSize().dragAndDropTarget(
                    shouldStartDragAndDrop = { e -> e.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
                    target = dropTarget,
                ),
            ) {
                BoardScreen(state = state, vm = vm, openFiles = openFiles)
            }
            if (about) {
                AlertDialog(
                    onDismissRequest = { about = false },
                    confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
                    title = { Text("Flux for Windows ${BuildInfo.VERSION}") },
                    text = {
                        Text(
                            "PCB viewer for the soldering bench.\n\n" +
                                "Matches the Android version at: ${BuildInfo.SYNCED_WITH}\n" +
                                "Data folder: ${AppDirs.default().root}",
                        )
                    },
                )
            }
        }
    }
}

/** Shortcuts that are not menu accelerators. Plain keys use KEY_TYPED, which text fields consume first. */
private fun handleKey(e: KeyEvent, vm: BoardViewModel): Boolean {
    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) return BackDispatcher.back()
    val native = e.nativeKeyEvent as? AwtKeyEvent ?: return false
    if (native.id != AwtKeyEvent.KEY_TYPED || native.isControlDown || native.isAltDown) return false
    if (vm.state.value.scene == null) return false
    return when (native.keyChar) {
        'f', 'F' -> DesktopCommands.fit?.invoke() != null
        // Same on/off switches as the Board and Schematic buttons.
        '1' -> { if (vm.state.value.hasSchematicView) vm.toggleBoardView(); true }
        '2' -> { if (vm.state.value.hasSchematicView) vm.toggleSchematicView(); true }
        else -> false
    }
}

@Composable
private fun FrameWindowScope.FluxMenuBar(vm: BoardViewModel, state: UiState, openFiles: () -> Unit, onAbout: () -> Unit) {
    val hasBoard = state.scene != null
    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("Open…", onClick = openFiles, shortcut = KeyShortcut(Key.O, ctrl = true))
            Item("Open folder…", onClick = { FileDialogs.openFolder(window)?.let { vm.open(listOf(it)) } }, shortcut = KeyShortcut(Key.O, ctrl = true, shift = true))
            Menu("Open recent", enabled = state.recents.isNotEmpty()) {
                state.recents.take(12).forEach { r -> Item(r.board.displayName, onClick = { vm.openRecent(r.board) }) }
            }
            Menu("Demos") {
                Item("Simple board", onClick = { vm.openDemo("simple") })
                Item("Double-sided board", onClick = { vm.openDemo("double-sided") })
                Item("Board + schematic", onClick = { vm.openDemo("ipc-mini") })
            }
            Separator()
            Item("Close board", enabled = hasBoard, onClick = vm::closeBoard, shortcut = KeyShortcut(Key.W, ctrl = true))
            Item("Exit", onClick = { window.dispatchEvent(java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING)) })
        }
        Menu("Edit", mnemonic = 'E') {
            Item("Undo", enabled = state.canUndo, onClick = vm::undo, shortcut = KeyShortcut(Key.Z, ctrl = true))
            Item("Find part…", enabled = hasBoard, onClick = { DesktopCommands.focusSearch?.invoke() }, shortcut = KeyShortcut(Key.F, ctrl = true))
            Item("Clear selection   Esc", enabled = state.selectedRef != null || state.selectedNet != null || state.schNet != null, onClick = vm::clearSelection)
        }
        Menu("View", mnemonic = 'V') {
            val hasSch = state.hasSchematicView
            CheckboxItem("Board   1", checked = hasBoard && state.viewLayout.showsBoard, enabled = hasSch && state.hasBoard, onCheckedChange = { vm.toggleBoardView() })
            CheckboxItem("Schematic   2", checked = hasSch && state.viewLayout.showsSchematic, enabled = hasSch, onCheckedChange = { vm.toggleSchematicView() })
            Separator()
            Item("Fit to window   F", enabled = hasBoard, onClick = { DesktopCommands.fit?.invoke() })
            Item("Flip board (bottom view)", enabled = hasBoard && state.viewLayout != ViewLayout.SCHEMATIC, onClick = { DesktopCommands.flip?.invoke() })
            CheckboxItem(
                "Mirror view", checked = state.mirrored, enabled = hasBoard && state.viewLayout != ViewLayout.SCHEMATIC,
                onCheckedChange = { DesktopCommands.mirror?.invoke() },
            )
            CheckboxItem(
                "Realistic view", checked = state.display.viewMode == ViewMode.REALISTIC, enabled = hasBoard,
                onCheckedChange = { on -> vm.setDisplay(state.display.copy(viewMode = if (on) ViewMode.REALISTIC else ViewMode.CAD)) },
            )
            Separator()
            CheckboxItem("Tools", checked = state.showToolsPanel, enabled = hasBoard, onCheckedChange = { vm.toggleToolsPanel() })
            CheckboxItem("Layers & view", checked = state.showLayersPanel, enabled = hasBoard, onCheckedChange = { vm.toggleLayersPanel() })
            // On the start screen the gear button opens settings; this panel exists only with a board open.
            CheckboxItem("Settings", checked = state.showSettingsPanel, enabled = hasBoard, onCheckedChange = { vm.toggleSettingsPanel() })
        }
        Menu("Help", mnemonic = 'H') {
            Item("Mouse and keys", onClick = { FileDialogs.showHelp(window) })
            Item("About Flux", onClick = onAbout)
        }
    }
}
