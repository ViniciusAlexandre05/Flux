package com.pcbsolderview.desktop

import com.pcbsolderview.BoardViewModel
import com.pcbsolderview.UiState
import com.pcbsolderview.platform.AppDirs
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

object DesktopTestSupport {
    val samples: File = File(System.getProperty("samples.dir") ?: "../samples")
    val renders: File = File(System.getProperty("renders.dir") ?: "build/renders").apply { mkdirs() }

    fun sample(path: String): File = File(samples, path)

    /** A fresh data folder, as if %APPDATA%\Flux were empty. */
    fun tempDirs(): AppDirs = AppDirs(Files.createTempDirectory("flux-test").toFile())

    /**
     * One thread plays the Swing main thread: the view model runs its coroutines there, and tests
     * call it through [onVm] so they see the same ordering as the app (e.g. the board key is set in
     * the same step that publishes the board).
     */
    val main = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main").apply { isDaemon = true } }.asCoroutineDispatcher()

    fun vm(dirs: AppDirs = tempDirs()) = BoardViewModel(dirs, CoroutineScope(SupervisorJob() + main))

    /** Runs [block] on the view model's thread. */
    fun <T> onVm(block: () -> T): T = runBlocking(main) { block() }

    fun BoardViewModel.await(timeoutMs: Long = 60_000, until: (UiState) -> Boolean): UiState =
        runBlocking { withTimeout(timeoutMs) { state.first { until(it) } } }

    /** Waits until a board is open (or fails with the import error). */
    fun BoardViewModel.awaitBoard(): UiState {
        val s = await { (it.scene != null && it.loading == null) || it.error != null }
        check(s.error == null) { "Import failed: ${s.error}" }
        return s
    }
}
