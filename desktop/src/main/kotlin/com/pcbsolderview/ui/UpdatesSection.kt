package com.pcbsolderview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcbsolderview.update.Relation
import com.pcbsolderview.update.ReleaseChoice
import com.pcbsolderview.update.ReleaseManifest
import com.pcbsolderview.update.ReleaseNotes
import com.pcbsolderview.update.RunKind
import com.pcbsolderview.update.UpdateController
import com.pcbsolderview.update.UpdateMessages

/** Settings › Updates (ported from Android): version, Check now, what's new, install, all versions. */
@Composable
fun UpdatesSection(showTest: Boolean, onShowTest: (Boolean) -> Unit, vm: UpdateController = UpdateController.instance) {
    val ui by vm.ui.collectAsState()
    val busy = ui.checking || ui.loadingVersions || ui.download != null || ui.preparing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirmOlder by remember { mutableStateOf<ReleaseManifest?>(null) }

    Text("Flux for Windows ${ui.version}", style = MaterialTheme.typography.titleMedium)
    Text(
        when (ui.mode.kind) {
            RunKind.MSI -> "Installed version · updates come from the Flux page on GitHub"
            RunKind.PORTABLE -> "Portable folder ${ui.mode.dir?.path.orEmpty()} · updates replace this folder"
            RunKind.DEV -> "Run from the source code: you can check, but installing is off"
        },
        style = MaterialTheme.typography.bodySmall, color = muted,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.check(showTest) }, enabled = !busy, modifier = Modifier.height(44.dp).testTag("updates.check")) { Text("Check now") }
        Spacer(Modifier.width(12.dp))
        when {
            ui.checking -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            ui.status != null -> Text(ui.status!!, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
    }

    ui.available?.let { m ->
        Spacer(Modifier.height(10.dp))
        AvailableCard(m, ui.mode.assetOf(m)?.size ?: 0, enabled = !busy && ui.mode.canInstall) { vm.install(m) }
    }
    ui.download?.let { d ->
        Spacer(Modifier.height(10.dp))
        Text("Downloading ${d.tag.removePrefix("v")}… ${UpdateMessages.progress(d.done, d.total)}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f }, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::cancel) { Text("Cancel") }
        }
    }
    if (ui.preparing) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Flux closes now, installs the new version and starts again. Your boards, statuses and settings stay.",
            style = MaterialTheme.typography.bodySmall, color = muted,
        )
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
        modifier = Modifier.testTag("updates.all"),
    ) { Text(if (showAll) "Hide all versions" else "All versions…") }
    if (showAll) {
        if (ui.loadingVersions) CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 3.dp)
        ui.versions?.let { list ->
            list.forEach { c ->
                VersionRow(c, enabled = !busy && ui.mode.canInstall) {
                    if (c.relation == Relation.OLDER) confirmOlder = c.manifest else vm.install(c.manifest)
                }
            }
            if (list.any { it.relation == Relation.OLDER }) {
                Text(
                    "Going back to an older version keeps your data, but settings or annotations saved by a newer version may not all show in an older one.",
                    style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    confirmOlder?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmOlder = null },
            title = { Text("Go back to Flux ${m.desktop?.version}?") },
            text = {
                Text(
                    "Flux closes, installs the older version and starts again. Your boards, statuses and settings stay in " +
                        "%APPDATA%\\Flux, but things saved by a newer version may not all show in the older one.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmOlder = null; vm.install(m) }) { Text("Install ${m.desktop?.version}") } },
            dismissButton = { TextButton(onClick = { confirmOlder = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AvailableCard(m: ReleaseManifest, size: Long, enabled: Boolean, onInstall: () -> Unit) {
    val d = m.desktop ?: return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Flux ${d.version} is available" + if (m.prerelease) " (test version)" else "",
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
            Button(onClick = onInstall, enabled = enabled, modifier = Modifier.height(44.dp).testTag("updates.install")) {
                Text("Download, install and restart · %.0f MB".format(size / 1e6))
            }
        }
    }
}

@Composable
private fun VersionRow(c: ReleaseChoice, enabled: Boolean, onAction: () -> Unit) {
    val d = c.manifest.desktop ?: return
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.version + (if (c.entry.prerelease) " · test" else ""), style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(c.manifest.date, ReleaseNotes.plain(c.manifest.notes).lineSequence().firstOrNull { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when (c.relation) {
            Relation.NEWER -> FilledTonalButton(onClick = onAction, enabled = enabled) { Text("Install") }
            Relation.INSTALLED -> Text("Installed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
            Relation.OLDER -> OutlinedButton(onClick = onAction, enabled = enabled) { Text("Go back") }
        }
    }
}

/** Help › Check for updates…: the same section in a dialog; checks right away. */
@Composable
fun UpdatesDialog(showTest: Boolean, onShowTest: (Boolean) -> Unit, onDismiss: () -> Unit, vm: UpdateController = UpdateController.instance) {
    androidx.compose.runtime.LaunchedEffect(Unit) { if (vm.ui.value.available == null) vm.check(showTest) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Updates") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                UpdatesSection(showTest, onShowTest, vm)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Once, on the first start after an update: the new version and what's new, or why it failed. */
@Composable
fun UpdatedNotice(vm: UpdateController = UpdateController.instance) {
    val ui by vm.ui.collectAsState()
    val failed = ui.updateFailed
    val done = ui.updatedTo
    if (failed == null && done == null) return
    AlertDialog(
        onDismissRequest = vm::dismissUpdated,
        confirmButton = { TextButton(onClick = vm::dismissUpdated) { Text("OK") } },
        title = { Text(if (failed != null) "Update not finished" else "Flux updated to ${done!!.first}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(failed ?: done!!.second.ifBlank { "Your boards, statuses and settings were kept." }, style = MaterialTheme.typography.bodyMedium)
                if (failed != null) {
                    Text("Details: ${vm.updatesDir}\\update.log", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}
