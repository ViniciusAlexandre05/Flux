package com.pcbsolderview.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcbsolderview.Status
import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Mount
import com.pcbsolderview.model.MountFilter
import com.pcbsolderview.model.PartGroup
import com.pcbsolderview.model.partGroups
import com.pcbsolderview.render.ComponentMark

/** Progress of one checklist line: parts marked OK, parts with any other status, and the total. */
private data class GroupProgress(val ok: Int, val flagged: Int, val problems: Int, val total: Int) {
    val todo: Int get() = total - ok - flagged
    val finished: Boolean get() = ok == total
}

private fun progressOf(g: PartGroup, marks: Map<String, ComponentMark>): GroupProgress {
    var ok = 0; var flagged = 0; var problems = 0
    for (c in g.parts) {
        val argb = marks[c.refDes]?.argb ?: continue
        when (argb) {
            Status.OK.argb -> ok++
            Status.PROBLEM.argb -> { flagged++; problems++ }
            else -> flagged++
        }
    }
    return GroupProgress(ok, flagged, problems, g.parts.size)
}

/**
 * Soldering checklist: the board's parts grouped by kind (value + footprint, or part number), SMD
 * first and the biggest groups first. A tap highlights the parts of a line that have no status
 * yet, a long press all of them; the highlight chip then sets a status on them in one go.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChecklistPanel(
    board: Board,
    marks: Map<String, ComponentMark>,
    /** Key of the highlighted line ([com.pcbsolderview.BoardViewModel.groupKey]), if any. */
    highlightKey: String?,
    groupKey: (PartGroup) -> String,
    onGroup: (group: PartGroup, all: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(board) { board.partGroups() }
    var filter by rememberSaveable { mutableStateOf(MountFilter.ALL) }
    var hideFinished by rememberSaveable { mutableStateOf(false) }
    val progress = remember(groups, marks) { groups.associateWith { progressOf(it, marks) } }
    val shown = groups.filter { g ->
        val m = g.mount
        val mountOk = when (filter) {
            MountFilter.ALL -> true
            MountFilter.SMD -> m == Mount.SMD
            MountFilter.PTH -> m == Mount.THROUGH_HOLE || m == Mount.MIXED
        }
        mountOk && !(hideFinished && progress.getValue(g).finished)
    }
    val total = board.components.size
    val ok = progress.values.sumOf { it.ok }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp, shape = RoundedCornerShape(16.dp), modifier = modifier.width(380.dp)) {
        Column(Modifier.padding(12.dp).testTag("checklist")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Soldering checklist", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "$ok of $total parts OK · ${groups.size} kinds",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close checklist") }
            }
            Spacer(Modifier.height(6.dp))
            Segmented(MountFilter.entries.map { it.label }, filter.ordinal, tag = "checklist.filter") { filter = MountFilter.entries[it] }
            ToggleRow("Hide finished", hideFinished, "Lines where every part is OK.") { hideFinished = it }
            Text(
                "Tap a line to highlight the parts still to do; hold to highlight all of them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            if (shown.isEmpty()) {
                Text(if (groups.isEmpty()) "This board has no parts." else "Nothing to show with these filters.", modifier = Modifier.padding(vertical = 12.dp))
            }
            // weight(fill = false): the list takes what is left of the panel's max height and scrolls.
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.key }) { g ->
                    ChecklistRow(g, progress.getValue(g), highlighted = highlightKey == groupKey(g), onGroup = onGroup)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChecklistRow(g: PartGroup, p: GroupProgress, highlighted: Boolean, onGroup: (PartGroup, Boolean) -> Unit) {
    val c = g.first
    val bg = if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val dim = if (p.finished) 0.55f else 1f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(bg, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { onGroup(g, false) },
                onLongClick = { onGroup(g, true) },
                onLongClickLabel = "Highlight all ${p.total}",
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("checklist.row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.widthIn(min = 44.dp), contentAlignment = Alignment.Center) {
            Text("${p.total}×", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = fg.copy(alpha = dim))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (c.value ?: c.description).orDash(), style = MaterialTheme.typography.titleSmall, color = fg.copy(alpha = dim),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(c.footprint, mountLabel(g.mount)).joinToString(" · ").ifEmpty { DASH } + " · " + refList(g),
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { p.ok.toFloat() / p.total },
                color = Color(Status.OK.argb),
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("${p.ok}/${p.total}", style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = dim))
            if (p.problems > 0) Text("${p.problems} ${Status.PROBLEM.glyph}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(Status.PROBLEM.argb))
            else if (p.flagged > 0) Text("${p.flagged} flagged", style = MaterialTheme.typography.labelSmall, color = Color(Status.CHECK.argb))
        }
    }
}

private fun mountLabel(m: Mount?): String? = when (m) {
    Mount.SMD -> "SMD"
    Mount.THROUGH_HOLE -> "PTH"
    Mount.MIXED -> "Mixed"
    null -> null
}

/** "R1, R2, R5 …" (at most four designators). */
private fun refList(g: PartGroup): String =
    g.parts.take(4).joinToString(", ") { it.refDes } + if (g.parts.size > 4) " …" else ""
