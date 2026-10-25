package dev.tgscope.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/** 7×24 heatmap (rows Mon..Sun, columns hours) with intensity relative to the max cell. */
@Composable
fun WeekHourHeatmap(cells: List<List<Int>>, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val max = cells.maxOfOrNull { row -> row.maxOrNull() ?: 0 }?.coerceAtLeast(1) ?: 1
    val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    Column(modifier) {
        cells.forEachIndexed { d, row ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(days.getOrElse(d) { "" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(24.dp))
                Canvas(Modifier.weight(1f).height(16.dp)) {
                    val w = size.width / 24f
                    row.forEachIndexed { h, v ->
                        val alpha = if (v == 0) 1f else 0.15f + 0.85f * v / max
                        drawRoundRect(
                            color = if (v == 0) empty else base.copy(alpha = alpha),
                            topLeft = Offset(h * w + 1f, 1f),
                            size = Size(w - 2f, size.height - 2f),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp)) {
            listOf("0", "6", "12", "18", "23").forEachIndexed { i, label ->
                Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(if (i == 4) 0.5f else 1f))
            }
        }
    }
}

/** Simple line chart for a single series with min/max labels. */
@Composable
fun LineChart(values: List<Double>, labels: List<String>, modifier: Modifier = Modifier, format: (Double) -> String = { "%.0f".format(it) }) {
    val color = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = values.maxOrNull() ?: 0.0
    val min = (values.minOrNull() ?: 0.0).coerceAtMost(0.0)
    Column(modifier) {
        Text("макс. ${format(max)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            if (values.size < 2 || max <= min) return@Canvas
            val step = size.width / (values.size - 1)
            val path = androidx.compose.ui.graphics.Path()
            values.forEachIndexed { i, v ->
                val x = i * step
                val y = (size.height - (v - min) / (max - min) * size.height).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Text(labels.first(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(labels.last(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Label, bar and value in one row — used for every "top N" style list. */
@Composable
fun BarRow(label: String, value: Double, max: Double, valueText: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
            Text(valueText, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalBar((if (max <= 0) 0.0 else value / max).toFloat(), Modifier.fillMaxWidth().padding(top = 2.dp))
    }
}
