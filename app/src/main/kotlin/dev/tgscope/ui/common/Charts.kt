package dev.tgscope.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/** Minimal bar chart: one bar per day, labelled with first/last day and the maximum. */
@Composable
fun DailyBarChart(labels: List<String>, values: List<Int>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier) {
        Text(
            "макс. $max в день",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val gap = (slot * 0.2f).coerceAtMost(4.dp.toPx())
            values.forEachIndexed { i, v ->
                val h = size.height * v / max
                if (h > 0f) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(i * slot + gap / 2, size.height - h),
                        size = Size((slot - gap).coerceAtLeast(1f), h),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Text(labels.first(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(labels.last(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Horizontal bars for "top N" lists. */
@Composable
fun HorizontalBar(fraction: Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.height(6.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(
            color,
            size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
    }
}
