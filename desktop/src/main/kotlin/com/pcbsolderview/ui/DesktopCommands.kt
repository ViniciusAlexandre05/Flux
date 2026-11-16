package com.pcbsolderview.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

// Desktop only: replacements for Android's back button and hooks for the window's menu bar and
// keyboard shortcuts (Main.kt).

/**
 * Esc plays the role of Android's Back button. Handlers registered later have priority (as with
 * androidx.activity's BackHandler); disabled ones are skipped.
 */
object BackDispatcher {
    internal class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    internal fun add(e: Entry) { entries += e }
    internal fun remove(e: Entry) { entries -= e }

    /** Runs the newest enabled handler; false when none took it. */
    fun back(): Boolean {
        val e = entries.lastOrNull { it.enabled } ?: return false
        e.onBack()
        return true
    }
}

/** Same signature as androidx.activity.compose.BackHandler, driven by Esc on desktop. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val entry = remember { BackDispatcher.Entry(enabled, onBack) }
    SideEffect { entry.enabled = enabled; entry.onBack = onBack }
    DisposableEffect(entry) {
        BackDispatcher.add(entry)
        onDispose { BackDispatcher.remove(entry) }
    }
}

/**
 * View actions the open board screen offers to the menu bar and keyboard shortcuts. Set while a
 * board is shown, null otherwise.
 */
object DesktopCommands {
    var fit: (() -> Unit)? = null
    var flip: (() -> Unit)? = null
    var mirror: (() -> Unit)? = null
    var focusSearch: (() -> Unit)? = null
}

/** Publishes [fit], [flip] and [focusSearch] to [DesktopCommands] while this composable is shown. */
@Composable
fun RegisterDesktopCommands(fit: () -> Unit, flip: () -> Unit, mirror: () -> Unit, focusSearch: (() -> Unit)?) {
    SideEffect {
        DesktopCommands.fit = fit
        DesktopCommands.flip = flip
        DesktopCommands.mirror = mirror
        if (focusSearch != null) DesktopCommands.focusSearch = focusSearch
    }
    DisposableEffect(Unit) {
        onDispose { DesktopCommands.fit = null; DesktopCommands.flip = null; DesktopCommands.mirror = null; DesktopCommands.focusSearch = null }
    }
}
