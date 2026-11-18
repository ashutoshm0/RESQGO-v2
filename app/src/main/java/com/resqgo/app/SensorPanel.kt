package com.resqgo.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The g-force at which the demo rules call something a hit, drawn as the red line. */
private const val DEMO_LINE = 2.5f
private const val TOP_G = 8f

/**
 * The live panel shown in demo mode: what the phone's sensors see right now, so the people
 * watching can tie the shake they just saw to the alarm that follows.
 */
@Composable
fun SensorPanel(riding: Boolean, modifier: Modifier = Modifier) {
    val g by RideState.accelG.collectAsState()
    val gyro by RideState.gyroDps.collectAsState()
    val speed by RideState.speedKmh.collectAsState()
    val trace by RideState.trace.collectAsState()
    val impact by RideState.impact.collectAsState()
    // A shake is over in a tenth of a second, so hold the peak of the last second and a half:
    // otherwise the big number is back to 1.0 g before anyone watching has looked at it.
    val peak = maxOf(g, trace.takeLast(15).maxOrNull() ?: g)
    val hot = peak >= DEMO_LINE || impact

    val accent = MaterialTheme.colorScheme.primary
    val danger = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line by animateColorAsState(if (hot) danger else accent, label = "traceColour")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Reading(
                "Force", if (riding) "%.1f".format(peak) else "—",
                if (hot) "g peak" else "g", if (hot) danger else accent, Modifier.weight(1f),
            )
            Reading(
                "Rotation",
                if (!riding || gyro.isNaN()) "—" else "%.0f".format(gyro),
                if (gyro.isNaN() && riding) "no gyroscope" else "°/s",
                accent, Modifier.weight(1f),
            )
            Reading(
                "Speed",
                if (!riding || speed < 0) "—" else "%.0f".format(speed),
                if (riding && speed < 0) "no GPS" else "km/h",
                accent, Modifier.weight(1f),
            )
        }

        Box(
            Modifier.fillMaxWidth().height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                fun y(value: Float) = size.height * (1f - (value.coerceIn(0f, TOP_G) / TOP_G))
                drawLine(danger.copy(alpha = 0.6f), Offset(0f, y(DEMO_LINE)), Offset(size.width, y(DEMO_LINE)), 2f)
                drawLine(muted.copy(alpha = 0.3f), Offset(0f, y(1f)), Offset(size.width, y(1f)), 1f)
                if (trace.size > 1) {
                    val step = size.width / (TRACE_WIDTH - 1)
                    // Newest reading at the right edge, so the line sweeps leftwards as it fills.
                    val start = TRACE_WIDTH - trace.size
                    val path = Path().apply {
                        trace.forEachIndexed { i, v ->
                            val x = (start + i) * step
                            if (i == 0) moveTo(x, y(v)) else lineTo(x, y(v))
                        }
                    }
                    drawPath(path, line, style = Stroke(width = 4f))
                }
            }
            Text(
                "${DEMO_LINE.toInt()}g",
                Modifier.align(Alignment.TopEnd).padding(10.dp),
                color = danger, fontSize = 11.sp,
            )
            if (!riding) {
                Text(
                    "Start the ride to see the sensors",
                    Modifier.align(Alignment.Center), color = muted, textAlign = TextAlign.Center,
                )
            }
        }

        Text(
            when {
                !riding -> "Not monitoring."
                impact -> "Hit detected. Checking whether the phone goes still…"
                hot -> "Over the line."
                else -> "Watching. Shake the phone hard to set off the alarm."
            },
            color = if (impact || hot) danger else muted,
            fontWeight = if (impact) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val TRACE_WIDTH = 80

@Composable
private fun Reading(label: String, value: String, unit: String, colour: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colour)
        Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
