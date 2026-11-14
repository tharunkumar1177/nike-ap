// TrackpadSurface.kt: trackpad area, scroll strip, and on-screen mouse buttons.
package dev.padlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.padlink.R
import dev.padlink.input.TabletMapping
import dev.padlink.settings.MouseButtonId
import dev.padlink.ui.theme.PadColors

/**
 * Blue touch surface. Compose draws the glyph and, when [showTabletArea] is set, the pen tablet frame
 * for [tabletAspect] (null stretches); a transparent [TrackpadView] on top takes all input.
 */
@Composable
fun TrackpadSurface(
    session: PadSession,
    hint: String,
    showTabletArea: Boolean,
    tabletAspect: Float?,
    tabletAreaPercent: Int,
    excludeAllGestures: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(PadColors.Trackpad),
    ) {
        if (showTabletArea) {
            Canvas(Modifier.fillMaxSize()) {
                val area = TabletMapping.area(size.width, size.height, tabletAspect, tabletAreaPercent)
                drawRect(PadColors.Letterbox, Offset.Zero, Size(size.width, area.top))
                drawRect(PadColors.Letterbox, Offset(0f, area.bottom), Size(size.width, size.height - area.bottom))
                drawRect(PadColors.Letterbox, Offset(0f, area.top), Size(area.left, area.height))
                drawRect(PadColors.Letterbox, Offset(area.right, area.top), Size(size.width - area.right, area.height))
                drawRect(
                    PadColors.OnTrackpad.copy(alpha = 0.5f),
                    Offset(area.left, area.top),
                    Size(area.width, area.height),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Mouse, contentDescription = null, tint = PadColors.OnTrackpad, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                color = PadColors.OnTrackpad,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        AndroidView(
            factory = { ctx -> TrackpadView(ctx, PadColors.OnTrackpad.toArgb()).also(session::attach) },
            update = { it.excludeAllGestures = excludeAllGestures },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Vertical scroll strip: arrows scroll one notch, dragging scrolls continuously. */
@Composable
fun ScrollStrip(session: PadSession, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(44.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(PadColors.Trackpad),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = { session.mouse.scrollNotches(1) }) {
            Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.scroll_up), tint = PadColors.OnTrackpad)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(session) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        session.mouse.onScroll(0f, dragAmount)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(PadColors.OnTrackpad.copy(alpha = 0.6f)))
        }
        IconButton(onClick = { session.mouse.scrollNotches(-1) }) {
            Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.scroll_down), tint = PadColors.OnTrackpad)
        }
    }
}

/** Row of hold-to-press mouse buttons in left, middle, right order. */
@Composable
fun MouseButtons(session: PadSession, visible: Set<MouseButtonId>, modifier: Modifier = Modifier) {
    val order = listOf(MouseButtonId.LEFT, MouseButtonId.MIDDLE, MouseButtonId.RIGHT).filter { it in visible }
    if (order.isEmpty()) return
    Row(modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (button in order) {
            MouseButton(session, button, Modifier.weight(1f))
        }
    }
}

/** One mouse button that stays pressed while touched. */
@Composable
private fun MouseButton(session: PadSession, button: MouseButtonId, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val label = when (button) {
        MouseButtonId.LEFT -> stringResource(R.string.button_left)
        MouseButtonId.MIDDLE -> stringResource(R.string.button_middle)
        MouseButtonId.RIGHT -> stringResource(R.string.button_right)
    }
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) PadColors.ButtonPressed else PadColors.Button)
            .pointerInput(session, button) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    session.mouse.press(button.mask)
                    try {
                        waitForUpOrCancellation()?.consume()
                    } finally {
                        pressed = false
                        session.mouse.release(button.mask)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}
