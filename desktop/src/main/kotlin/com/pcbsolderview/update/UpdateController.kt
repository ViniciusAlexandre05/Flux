package com.pcbsolderview.update

import com.pcbsolderview.platform.AppDirs
import com.pcbsolderview.platform.BuildInfo
import com.pcbsolderview.platform.writeAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class DownloadUi(val tag: String, val done: Long, val total: Long)

data class UpdateUi(
    val version: String,
    val mode: RunMode,
    val checking: Boolean = false,
    /** "Up to date · checked 14:05". */
    val status: String? = null,
    val available: ReleaseManifest? = null,
    val loadingVersions: Boolean = false,
    val versions: List<ReleaseChoice>? = null,
    val download: DownloadUi? = null,
    /** Unpacking the portable zip / starting the helper; Flux closes next. */
    val preparing: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    /** Shown once after Flux was updated: version and what's new. */
    val updatedTo: Pair<String, String>? = null,
    /** Shown once when the last update did not finish: the reason from the helper's log. */
    val updateFailed: String? = null,
)

/** Which of a release's files this copy can install. */
class WindowsPlatform(installed: Version, private val mode: RunMode) : Platform by DesktopPlatform(installed) {
    override fun compatible(m: ReleaseManifest) = mode.assetOf(m) != null
}

/**
 * Settings › Updates and Help › Check for updates… (docs/PLAN-updates.md, U3). Same flow as the
 * Android UpdateViewModel; installing hands over to a PowerShell helper ([UpdateScripts]) that
 * waits for Flux to close, installs the MSI or swaps the portable folder, and starts Flux again.
 * Older versions install the same way (data in %APPDATA%\Flux stays).
 */
class UpdateController(
    private val dirs: AppDirs,
    val mode: RunMode = RunMode.detect(),
    private val installed: String = BuildInfo.VERSION,
    private val source: ReleaseSource = GitHubReleaseSource(OWNER, REPO, UrlConnectionHttp()),
    private val downloader: Downloader = Downloader(UrlConnectionHttp()),
    val updatesDir: File = defaultUpdatesDir(dirs),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private var job: Job? = null
    private val stateFile = File(dirs.root, "update.json")
    private val installedVersion = Version.parse(installed) ?: Version(listOf(0))

    private val _ui = MutableStateFlow(UpdateUi(installed, mode))
    val ui: StateFlow<UpdateUi> = _ui

    /** Closes Flux the normal way (saves the window, closes the board). Set by Main.kt. */
    var quit: (() -> Unit)? = null

    init { afterStart() }

    /** The result of the last update, once, and cleanup of what it left behind. */
    private fun afterStart() {
        val s = readState()
        val pendingTo = s["pending_to"]
        if (pendingTo != null) {
            if (pendingTo == installed) {
                _ui.update { it.copy(updatedTo = installed to ReleaseNotes.plain(s["pending_notes"].orEmpty())) }
            } else {
                val reason = File(updatesDir, LOG).takeIf { it.exists() }?.readLines()?.lastOrNull { "FAILED" in it }
                    ?.substringAfter("FAILED:")?.trim() ?: "Flux did not start on the new version."
                _ui.update { it.copy(updateFailed = "The update to $pendingTo did not finish: $reason Flux $installed is still installed.") }
            }
        }
        if (pendingTo != null || s["last_version"] != installed) writeState(mapOf("last_version" to installed))
        if (pendingTo == installed) {
            // Downloads are not needed any more; keep the log of the last run.
            updatesDir.listFiles()?.filter { it.name != LOG && it.name != "msiexec.log" }?.forEach { it.deleteRecursively() }
        }
        if (mode.kind == RunKind.PORTABLE) mode.dir?.let { d ->
            d.parentFile?.listFiles()?.filter { f ->
                f.isDirectory && (f.name.startsWith("${d.name}.old-") || f.name.startsWith(STAGE_PREFIX))
            }?.forEach { it.deleteRecursively() }
        }
    }

    private fun checker() = UpdateChecker(source, WindowsPlatform(installedVersion, mode))

    fun check(includeTest: Boolean) {
        if (job?.isActive == true) return
        _ui.update { it.copy(checking = true, error = null, info = null) }
        job = scope.launch {
            val s = withContext(Dispatchers.IO) { checker().check(includeTest) }
            val time = LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            _ui.update {
                when (s) {
                    UpdateState.UpToDate -> it.copy(checking = false, available = null, status = "Up to date · checked $time")
                    is UpdateState.Available -> it.copy(checking = false, available = s.release, status = null)
                    is UpdateState.Failed -> it.copy(checking = false, error = s.message)
                }
            }
        }
    }

    fun loadVersions(includeTest: Boolean) {
        if (job?.isActive == true) return
        _ui.update { it.copy(loadingVersions = true, error = null) }
        job = scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { checker().list(includeTest) }
                _ui.update { it.copy(loadingVersions = false, versions = list, info = if (list.isEmpty()) "No versions with in-app install yet (they start with 0.1.3)." else it.info) }
            } catch (e: UpdateException) {
                _ui.update { it.copy(loadingVersions = false, error = UpdateMessages.of(e)) }
            }
        }
    }

    /** The test-version switch changed: earlier results may be wrong now. */
    fun clearResults() = _ui.update { it.copy(available = null, versions = null, status = null, error = null, info = null) }

    /** Download [m], hand over to the helper and close Flux. Newer and older versions alike. */
    fun install(m: ReleaseManifest) {
        val d = m.desktop ?: return
        val asset = mode.assetOf(m) ?: return
        if (job?.isActive == true) return
        if (!mode.canInstall) {
            _ui.update { it.copy(error = "Installing is off when Flux runs from the source code (gradlew :desktop:run).") }
            return
        }
        _ui.update { it.copy(error = null, info = null, download = DownloadUi(m.tag, 0, asset.size)) }
        job = scope.launch {
            try {
                val file = downloader.download(source.assetUrl(m.tag, asset), File(updatesDir, asset.name), asset) { done, total ->
                    _ui.update { it.copy(download = DownloadUi(m.tag, done, total)) }
                }
                _ui.update { it.copy(download = null, preparing = true) }
                withContext(Dispatchers.IO) { handOver(file, d.version, m.notes) }
                quit?.invoke()
            } catch (e: CancellationException) {
                _ui.update { it.copy(download = null, preparing = false, info = "Download stopped. What arrived is kept; the next try continues from there.") }
                throw e
            } catch (e: UpdateException) {
                _ui.update { it.copy(download = null, preparing = false, error = UpdateMessages.of(e)) }
            } catch (e: Exception) {
                _ui.update { it.copy(download = null, preparing = false, error = "Could not start the update (${e.message ?: e.javaClass.simpleName}).") }
            }
        }
    }

    /** Writes and starts the helper script. Blocking; throws when something is missing. */
    private fun handOver(file: File, to: String, notes: String) {
        val exe = mode.exe!!.absoluteFile
        val dir = mode.dir!!
        val log = File(updatesDir, LOG)
        log.delete()
        val pid = ProcessHandle.current().pid()
        val script = when (mode.kind) {
            RunKind.PORTABLE -> {
                val stage = File(dir.parentFile, STAGE_PREFIX + UpdateScripts.safe(to))
                if (!dir.parentFile.canWrite()) throw IllegalStateException("Flux may not write next to its folder ${dir.parentFile}")
                val staged = unzipPortable(file, stage, exe.name)
                UpdateScripts.portable(stage, staged, dir, exe, installed, to, log, pid)
            }
            else -> {
                // jpackage's MSI refuses to downgrade, and a test version shares its MSI version with
                // the release (0.1.4-test1 and 0.1.4 are both 0.1.4): uninstall first in those cases.
                val target = Version.parse(to) ?: Version(listOf(0))
                val uninstallFirst = target < installedVersion || target.numbers == installedVersion.numbers
                UpdateScripts.msi(file, dir, exe, installed, to, uninstallFirst, log, pid)
            }
        }
        val ps1 = File(updatesDir, "update.ps1")
        ps1.writeText("﻿" + script, Charsets.UTF_8)
        writeState(mapOf("last_version" to installed, "pending_to" to to, "pending_notes" to notes))
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", ps1.path,
        ).redirectErrorStream(true).redirectOutput(File(updatesDir, "update-output.txt")).start()
    }

    fun cancel() { job?.cancel() }

    fun dismissUpdated() = _ui.update { it.copy(updatedTo = null, updateFailed = null) }

    private fun readState(): Map<String, String> = runCatching {
        Json.parseToJsonElement(stateFile.readText()).jsonObject.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeState(values: Map<String, String>) {
        stateFile.writeAtomically(Json.encodeToString(JsonObject.serializer(), buildJsonObject { values.forEach { (k, v) -> put(k, v) } }))
    }

    companion object {
        const val OWNER = "ViniciusAlexandre05"
        const val REPO = "Flux"
        const val LOG = "update.log"
        /** Folder next to the portable Flux folder where the new version is unpacked. */
        const val STAGE_PREFIX = ".flux-update-"

        /** %LOCALAPPDATA%\Flux\updates (downloads don't belong in the roaming profile); tests: <flux.home>\updates. */
        fun defaultUpdatesDir(dirs: AppDirs): File {
            val local = System.getenv("LOCALAPPDATA")
            return if (System.getProperty("flux.home") == null && local != null) File(local, "Flux\\updates") else File(dirs.root, "updates")
        }

        /** One for the whole app, so a download keeps going while settings are closed. */
        val instance: UpdateController by lazy { UpdateController(AppDirs.default()) }
    }
}
