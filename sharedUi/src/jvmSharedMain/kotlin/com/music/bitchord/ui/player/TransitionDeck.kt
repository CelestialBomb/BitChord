package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max

private val LowBand = Color(0xFF2E45E6)
private val MidBand = Color(0xFFE08B35)
private val HighBand = Color(0xFFF1DDC4)
private val RegionColor = Color(0xFF3A9BDC)
private val DeckWell = Color(0xFF1E1E1E)

/**
 * One end of a song as the transition editor draws it: the three-band
 * waveform the Automix popup uses, the beat grid, and the blend as a frame the
 * listener can drag (design doc 4.8).
 *
 * Times are seconds on the song's own timeline. [bands] cover [fromSeconds] to
 * [toSeconds]. [regionStart]..[regionEnd] is the blend; dragging reports where
 * its start would now be through [onRegionStart], unsnapped, and the caller
 * answers by moving the region (so it can snap to a beat and stay in bounds).
 * A null [onRegionStart] makes the deck read-only.
 */
@Composable
fun TransitionDeck(
    low: FloatArray,
    mid: FloatArray,
    high: FloatArray,
    fromSeconds: Double,
    toSeconds: Double,
    beats: List<Double>,
    regionStart: Double?,
    regionEnd: Double?,
    onRegionStart: ((Double) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val currentRegionStart by rememberUpdatedState(regionStart)
    val currentRegionEnd by rememberUpdatedState(regionEnd)
    val report by rememberUpdatedState(onRegionStart)
    val span = toSeconds - fromSeconds
    val drag = if (onRegionStart != null && span > 0) {
        Modifier.pointerInput(fromSeconds, toSeconds) {
            var grab = 0.0
            detectDragGestures(
                onDragStart = { at ->
                    val seconds = fromSeconds + at.x / size.width * span
                    val start = currentRegionStart
                    val end = currentRegionEnd
                    // Pressed inside the frame: keep the grabbed point under the finger.
                    // Outside it: the frame jumps to be centred on the finger.
                    grab = if (start != null && end != null && seconds in start..end) {
                        seconds - start
                    } else {
                        ((end ?: 0.0) - (start ?: 0.0)) / 2
                    }
                    report?.invoke(seconds - grab)
                },
                onDrag = { change, _ ->
                    change.consume()
                    val seconds = fromSeconds + change.position.x / size.width * span
                    report?.invoke(seconds - grab)
                },
            )
        }
    } else {
        Modifier
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(DeckHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(DeckWell)
            .then(drag),
    ) {
        val centre = size.height / 2f
        val half = size.height / 2f - 6.dp.toPx()
        val bins = low.size
        if (bins > 0 && span > 0) {
            val column = size.width / bins
            for (bin in 0 until bins) {
                val x = bin * column
                bar(x, column + 0.6f, centre, low[bin] * half, LowBand)
                bar(x, column + 0.6f, centre, mid[bin] * half, MidBand)
                bar(x, column + 0.6f, centre, high[bin] * half, HighBand)
            }
        } else {
            drawLine(
                color = MidBand.copy(alpha = 0.35f),
                start = Offset(0f, centre),
                end = Offset(size.width, centre),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (span > 0) {
            fun xOf(seconds: Double) = ((seconds - fromSeconds) / span * size.width).toFloat()
            // Beats: every one when they are far enough apart to tell, otherwise the bar lines.
            val pixelsPerBeat = if (beats.size > 1) xOf(beats[1]) - xOf(beats[0]) else 0f
            val step = if (pixelsPerBeat >= 5.dp.toPx()) 1 else 4
            beats.forEachIndexed { index, beat ->
                if (index % step != 0 || beat < fromSeconds || beat > toSeconds) return@forEachIndexed
                val x = xOf(beat)
                drawLine(Color.White.copy(alpha = 0.18f), Offset(x, 0f), Offset(x, 8.dp.toPx()), 1.dp.toPx())
            }
            val start = regionStart
            val end = regionEnd
            if (start != null && end != null && end > start) {
                val left = xOf(start).coerceIn(0f, size.width)
                val right = xOf(end).coerceIn(0f, size.width)
                drawRect(RegionColor.copy(alpha = 0.22f), Offset(left, 0f), Size(max(0f, right - left), size.height))
                drawLine(RegionColor, Offset(left, 0f), Offset(left, size.height), 2.dp.toPx())
                drawLine(RegionColor, Offset(right, 0f), Offset(right, size.height), 2.dp.toPx())
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.bar(x: Float, width: Float, centre: Float, extent: Float, color: Color) {
    if (extent <= 0f) return
    drawRect(color, Offset(x, centre - extent), Size(width, extent * 2f))
}

private val DeckHeight = 84.dp
