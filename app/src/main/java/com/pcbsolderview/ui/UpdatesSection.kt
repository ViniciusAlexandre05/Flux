package com.pcbsolderview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pcbsolderview.update.Relation
import com.pcbsolderview.update.ReleaseChoice
import com.pcbsolderview.update.ReleaseManifest
import com.pcbsolderview.update.ReleaseNotes
import com.pcbsolderview.update.UpdateMessages
import com.pcbsolderview.update.UpdateViewModel

/** Settings › Updates: current version, Check now, what's new, install, all versions. */
@Composable
fun UpdatesSection(showTest: Boolean, onShowTest: (Boolean) -> Unit, vm: UpdateViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val busy = ui.checking || ui.loadingVersions || ui.download != null || ui.installing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Text("Flux ${ui.versionName}", style = MaterialTheme.typography.titleMedium)
    Text("Build ${ui.versionCode} · updates come from the Flux page on GitHub", style = MaterialTheme.typography.bodySmall, color = muted)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.check(showTest) }, enabled = !busy, modifier = Modifier.height(56.dp).testTag("updates.check")) { Text("Check now") }
        Spacer(Modifier.width(12.dp))
        when {
            ui.checking -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            ui.status != null -> Text(ui.status!!, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
    }

    ui.available?.let { m ->
        Spacer(Modifier.height(10.dp))
        AvailableCard(m, enabled = !busy) { vm.install(m) }
    }
    ui.download?.let { d ->
        Spacer(Modifier.height(10.dp))
        Text("Downloading ${d.tag.removePrefix("v")}… ${UpdateMessages.progress(d.done, d.total)}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f }, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::cancel, modifier = Modifier.height(48.dp)) { Text("Cancel") }
        }
    }
    if (ui.installing) {
        Spacer(Modifier.height(8.dp))
        Text("Confirm the update in Android's prompt. Flux closes and restarts on the new version; your boards, statuses and settings stay.", style = MaterialTheme.typography.bodySmall, color = muted)
    }
    ui.info?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) }
    ui.error?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

    Spacer(Modifier.height(4.dp))
    ToggleRow("Show test versions", showTest, "Also offer test versions, to try a fix before it is released. They may have bugs.") {
        vm.clearResults(); onShowTest(it)
    }
    var showAll by remember { mutableStateOf(false) }
    TextButton(
        onClick = { showAll = !showAll; if (showAll && ui.versions == null) vm.loadVersions(showTest) },
        enabled = !busy || showAll,
        modifier = Modifier.height(48.dp).testTag("updates.all"),
    ) { Text(if (showAll) "Hide all versions" else "All versions…") }
    if (showAll) {
        if (ui.loadingVersions) CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 3.dp)
        ui.versions?.let { list ->
            list.forEach { c -> VersionRow(c, enabled = !busy) { vm.install(c.manifest) } }
            if (list.any { it.relation == Relation.OLDER }) {
                Text(
                    "Android only installs an older version after Flux is uninstalled, which deletes its annotations and statuses. " +
                        "\"Save APK\" puts the file in Downloads for that.",
                    style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AvailableCard(m: ReleaseManifest, enabled: Boolean, onInstall: () -> Unit) {
    val a = m.android ?: return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Flux ${a.versionName} is available" + if (m.prerelease) " (test version)" else "",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            val notes = ReleaseNotes.plain(m.notes)
            if (notes.isNotBlank()) {
                var expanded by remember(m.tag) { mutableStateOf(false) }
                Text(
                    notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less" else "What's new…") }
            }
            Button(onClick = onInstall, enabled = enabled, modifier = Modifier.height(56.dp).testTag("updates.install")) {
                Text("Download and install · %.0f MB".format(a.apk.size / 1e6))
            }
        }
    }
}

@Composable
private fun VersionRow(c: ReleaseChoice, enabled: Boolean, onAction: () -> Unit) {
    val a = c.manifest.android ?: return
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                a.versionName + (if (c.entry.prerelease) " · test" else ""),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                listOfNotNull(c.manifest.date, ReleaseNotes.plain(c.manifest.notes).lineSequence().firstOrNull { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when (c.relation) {
            Relation.NEWER -> FilledTonalButton(onClick = onAction, enabled = enabled, modifier = Modifier.height(48.dp)) { Text("Install") }
            Relation.INSTALLED -> Text("Installed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
            Relation.OLDER -> OutlinedButton(onClick = onAction, enabled = enabled, modifier = Modifier.height(48.dp)) { Text("Save APK") }
        }
    }
}

/** Once, on the first start after an in-app update: the new version and what's new. */
@Composable
fun UpdatedNotice(vm: UpdateViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val (version, notes) = ui.updatedTo ?: return
    AlertDialog(
        onDismissRequest = vm::dismissUpdated,
        confirmButton = { TextButton(onClick = vm::dismissUpdated) { Text("OK") } },
        title = { Text("Flux updated to $version") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(notes.ifBlank { "Your boards, statuses and settings were kept." }, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}
