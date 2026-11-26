package com.pcbsolderview.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/** What Android reported about an install started by [ApkInstaller]. */
sealed interface InstallEvent {
    /** The system "Do you want to update Flux?" prompt is showing. */
    data object WaitingForUser : InstallEvent
    data class Finished(val status: Int, val message: String?) : InstallEvent
}

/** Install results travel from the receiver to the view model through here. */
object InstallEvents {
    private val flow = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<InstallEvent> get() = flow
    fun post(e: InstallEvent) { flow.tryEmit(e) }
}

/** Receives the result of a PackageInstaller session (declared in the manifest, not exported). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The system's confirmation screen; this receiver only gets intents from our own session.
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null) {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                InstallEvents.post(InstallEvent.WaitingForUser)
                return
            }
        }
        InstallEvents.post(InstallEvent.Finished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
    }
}

object ApkInstaller {
    /** Hands [apk] to Android's package installer; the result arrives through [InstallEvents]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("flux.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            // Mutable: the installer adds the status extras to this intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** Plain text for an install result. */
    fun message(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "Update installed."
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled. Nothing was changed."
        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "Android refused the update: it is signed with a different key than the Flux on this tablet. " +
                "Updates only install over a Flux built with the same key."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space on the tablet to install the update."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install" + (detail?.let { " ($it)" } ?: "") + "."
        PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded file is not a valid Flux app. Try again."
        else -> "Android could not install the update" + (detail?.let { " ($it)" } ?: "") + "."
    }

    /**
     * Copies an APK to the public Downloads folder (Android 10+), for going back to an older
     * version: Android only installs it after Flux is uninstalled. Returns false on Android 9.
     */
    fun saveToDownloads(context: Context, apk: File, name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } } ?: return false
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }
}
