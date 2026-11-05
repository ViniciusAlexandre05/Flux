package com.pcbsolderview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Where the divider may go, as a fraction of the area (each pane keeps at least this much). */
object SplitFraction {
    const val MIN = 0.2f
    const val MAX = 0.8f

    fun clamp(f: Float) = f.coerceIn(MIN, MAX)

    /** Double-tap on the divider: back to an even split, or to 70/30 when already even. */
    fun toggle(f: Float) = if (kotlin.math.abs(f - 0.5f) < 0.02f) 0.7f else 0.5f
}

/**
 * Two panes side by side (landscape) or stacked ([vertical], portrait) with a draggable divider.
 * The split survives rotation and recomposition.
 */
@Composable
fun SplitPanes(
    vertical: Boolean,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val totalPx = with(LocalDensity.current) { (if (vertical) maxHeight else maxWidth).toPx() }
        val divider = @Composable {
            val handle = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .pointerInput(totalPx, vertical) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        fraction = SplitFraction.clamp(fraction + (if (vertical) drag.y else drag.x) / totalPx)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { fraction = SplitFraction.toggle(fraction) }) }
                .testTag("split.divider")
            Box(if (vertical) handle.fillMaxWidth().height(DIVIDER) else handle.fillMaxHeight().width(DIVIDER), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(if (vertical) 48.dp else 4.dp, if (vertical) 4.dp else 48.dp)
                        .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
                )
            }
        }
        if (vertical) {
            Column(Modifier.fillMaxSize()) {
                first(Modifier.weight(fraction).fillMaxWidth())
                divider()
                second(Modifier.weight(1f - fraction).fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                first(Modifier.weight(fraction).fillMaxHeight())
                divider()
                second(Modifier.weight(1f - fraction).fillMaxHeight())
            }
        }
    }
}

/** Wide enough to grab with a finger at the bench. */
private val DIVIDER = 14.dp
