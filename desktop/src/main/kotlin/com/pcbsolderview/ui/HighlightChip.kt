package com.pcbsolderview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcbsolderview.Status
import com.pcbsolderview.model.MountFilter

/**
 * The "Highlighting …" chip at the bottom of the board. Collapsed: label, status dots (set on every
 * highlighted part), clear-status dot and close. Tap the label to expand: All / SMD / PTH switch
 * and a search that narrows the highlight to matching parts of this group only.
 */
@Composable
fun HighlightChip(
    label: String,
    shown: Int,
    total: Int,
    /** Parts of the group on the side not being viewed (not highlighted, no status from the chip). */
    otherSide: Int = 0,
    otherSideName: String = "bottom",
    override: Boolean,
    filter: MountFilter,
    counts: Map<MountFilter, Int>,
    query: String,
    highlightColor: Color,
    onFilter: (MountFilter) -> Unit,
    onQuery: (String) -> Unit,
    onStatus: (Int?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp,
        modifier = modifier.testTag("highlight.chip"),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = if (expanded) 12.dp else 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clickable { expanded = !expanded }.padding(vertical = 10.dp).testTag("highlight.expand"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).background(highlightColor, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "Highlighting $label", style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp),
                        )
                        val detail = listOfNotNull(
                            if (shown != total) "$shown of $total shown" else null,
                            if (otherSide > 0) "$otherSide more on the $otherSideName side (Flip)" else null,
                            if (override) "status applies to all" else null,
                        )
                        if (detail.isNotEmpty()) {
                            Text(detail.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Hide filters" else "Show filters",
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Status for every highlighted part, plus clear.
                Status.entries.forEach { s ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(30.dp)
                            .background(Color(s.argb), CircleShape)
                            .clickable(enabled = shown > 0) { onStatus(s.argb) }
                            .testTag("highlight.status.${s.name}"),
                    )
                }
                Box(
                    Modifier.padding(horizontal = 3.dp).size(30.dp)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable(enabled = shown > 0) { onStatus(null) }
                        .testTag("highlight.status.CLEAR"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Block, contentDescription = "Clear status of highlighted parts", modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear highlight")
                }
            }
            if (expanded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MountFilter.entries.forEach { f ->
                        val on = f == filter
                        Box(
                            Modifier.height(40.dp)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
                                .clickable { onFilter(f) }
                                .padding(horizontal = 16.dp)
                                .testTag("highlight.filter.${f.name}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${f.label} ${counts[f] ?: 0}", style = MaterialTheme.typography.labelLarge,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { searching = !searching; if (!searching) onQuery("") },
                        modifier = Modifier.size(44.dp).testTag("highlight.search"),
                    ) { Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searching) "Close search" else "Search in highlighted parts") }
                }
                if (searching) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query, onValueChange = onQuery, singleLine = true,
                        placeholder = { Text("Search in these parts: R1, 100n, 0402…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.widthIn(min = 280.dp, max = 420.dp).padding(end = 10.dp).testTag("highlight.query"),
                    )
                }
            }
        }
    }
}
