package com.pcbsolderview.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

data class DownloadUi(val tag: String, val done: Long, val total: Long)

data class UpdateUi(
    val versionName: String,
    val versionCode: Int,
    val checking: Boolean = false,
    /** "Up to date · checked 14:05". */
    val status: String? = null,
    val available: ReleaseManifest? = null,
    val loadingVersions: Boolean = false,
    val versions: List<ReleaseChoice>? = null,
    val download: DownloadUi? = null,
    /** Android's install prompt is up. */
    val installing: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    /** Shown once after Flux was updated: version name and what's new. */
    val updatedTo: Pair<String, String>? = null,
)

/**
 * Settings › Updates (docs/PLAN-updates.md, U2). Goes online only when the user asks (D3);
 * downloads into the app cache and installs with Android's package installer.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("flux_update", 0)
    private val dir = File(app.cacheDir, "updates")
    private val source = GitHubReleaseSource(OWNER, REPO, UrlConnectionHttp())
    private val downloader = Downloader(UrlConnectionHttp())
    private var job: Job? = null
    /** Downloaded APK waiting for "Install unknown apps" to be allowed. */
    private var pending: File? = null

    private val installed = app.packageManager.getPackageInfo(app.packageName, 0)
    private val code = PackageInfoCompat.getLongVersionCode(installed).toInt()
    private val _ui = MutableStateFlow(UpdateUi(installed.versionName ?: "?", code))
    val ui: StateFlow<UpdateUi> = _ui

    init {
        // First start after an in-app update: say so once, with the notes saved before installing.
        val last = prefs.getInt(KEY_LAST_CODE, 0)
        if (last in 1 until code) {
            val notes = prefs.getString(KEY_NOTES, null).takeIf { prefs.getInt(KEY_NOTES_CODE, 0) == code }
            _ui.update { it.copy(updatedTo = it.versionName to ReleaseNotes.plain(notes.orEmpty())) }
            dir.deleteRecursively()
        }
        if (last != code) prefs.edit().putInt(KEY_LAST_CODE, code).apply()
        viewModelScope.launch {
            InstallEvents.events.collect { e ->
                when (e) {
                    InstallEvent.WaitingForUser -> _ui.update { it.copy(installing = true) }
                    is InstallEvent.Finished -> _ui.update {
                        val ok = e.status == PackageInstaller.STATUS_SUCCESS
                        it.copy(installing = false, error = if (ok) null else ApkInstaller.message(e.status, e.message), info = if (ok) ApkInstaller.message(e.status, null) else it.info)
                    }
                }
            }
        }
    }

    private fun checker() = UpdateChecker(source, AndroidPlatform(code, Build.VERSION.SDK_INT))

    fun check(includeTest: Boolean) {
        if (job?.isActive == true) return
        _ui.update { it.copy(checking = true, error = null, info = null) }
        job = viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { checker().check(includeTest) }
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
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
        job = viewModelScope.launch {
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

    /** Newer: download and install. Older: download and save to Downloads (Android can't downgrade in place). */
    fun install(m: ReleaseManifest) {
        val a = m.android ?: return
        if (job?.isActive == true) return
        val older = a.versionCode < code
        _ui.update { it.copy(error = null, info = null, download = DownloadUi(m.tag, 0, a.apk.size)) }
        job = viewModelScope.launch {
            try {
                val file = downloader.download(source.assetUrl(m.tag, a.apk), File(dir, a.apk.name), a.apk) { done, total ->
                    _ui.update { it.copy(download = DownloadUi(m.tag, done, total)) }
                }
                _ui.update { it.copy(download = null) }
                if (older) {
                    val saved = withContext(Dispatchers.IO) { ApkInstaller.saveToDownloads(ctx, file, a.apk.name) }
                    _ui.update {
                        it.copy(info = if (saved) "Saved ${a.apk.name} to Downloads. To go back to ${a.versionName}: uninstall Flux (this deletes its annotations and statuses), then open that file." else "Saving to Downloads needs Android 10 or newer.")
                    }
                    return@launch
                }
                prefs.edit().putInt(KEY_NOTES_CODE, a.versionCode).putString(KEY_NOTES, m.notes).apply()
                startInstall(file)
            } catch (e: CancellationException) {
                _ui.update { it.copy(download = null, info = "Download stopped. What arrived is kept; the next try continues from there.") }
                throw e
            } catch (e: UpdateException) {
                _ui.update { it.copy(download = null, error = UpdateMessages.of(e)) }
            }
        }
    }

    fun cancel() { job?.cancel() }

    private fun startInstall(apk: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // One-time Android permission ("Install unknown apps"); continue in onResume.
            pending = apk
            _ui.update { it.copy(info = "Allow Flux to install apps (Android asks once), then come back: the update continues.") }
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        pending = null
        _ui.update { it.copy(installing = true, info = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ApkInstaller.install(ctx, apk) }
            } catch (e: Exception) {
                _ui.update { it.copy(installing = false, error = "Android could not start the install (${e.message}).") }
            }
        }
    }

    /** Back from Android's settings or install prompt. */
    fun onResume() {
        val apk = pending ?: return
        if (ctx.packageManager.canRequestPackageInstalls()) startInstall(apk)
        else _ui.update { it.copy(info = "Flux may not install apps yet. Allow it in Android's settings, or tap Install again later.") }
    }

    fun dismissUpdated() = _ui.update { it.copy(updatedTo = null) }

    companion object {
        /** Releases come from this public repository (decision D1). */
        const val OWNER = "ViniciusAlexandre05"
        const val REPO = "Flux"
        private const val KEY_LAST_CODE = "last_version_code"
        private const val KEY_NOTES = "pending_notes"
        private const val KEY_NOTES_CODE = "pending_notes_code"
    }
}
