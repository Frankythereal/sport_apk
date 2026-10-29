package de.endurofit.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.HrSample
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** Blendet Inhalte nacheinander ein (leicht von unten, mit Verzögerung je nach Position). */
@Composable
fun Appear(index: Int, content: @Composable () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 55L)
        anim.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer {
        alpha = anim.value
        translationY = (1f - anim.value) * 40f
        val s = 0.96f + 0.04f * anim.value
        scaleX = s; scaleY = s
    }) { content() }
}

/** Zahl, die beim Erscheinen hochzählt. */
@Composable
fun CountUp(
    value: Int,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 26.sp,
    color: Color = Color.Unspecified,
    prefix: String = "",
    suffix: String = "",
    format: (Int) -> String = { it.toString() },
) {
    var target by remember { mutableIntStateOf(0) }
    LaunchedEffect(value) { target = value }
    val shown by animateIntAsState(target, tween(900, easing = FastOutSlowInEasing), label = "count")
    Text(prefix + format(shown) + suffix, modifier, fontSize = fontSize, fontWeight = FontWeight.Bold, color = color)
}

/** Kachel mit leichtem "Eindrücken" beim Antippen. */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = Accent,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f), label = "press")
    Card(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null) { onClick() } else Modifier),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
            }
            content()
        }
    }
}

/** Zwei Kacheln nebeneinander, gleich hoch. */
@Composable
fun TileRow(height: Dp = 158.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(height).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

data class RingData(val progress: Float, val color: Color)

/** Konzentrische Ringe, die sich beim Erscheinen füllen (wie Aktivitätsringe). */
@Composable
fun Rings(rings: List<RingData>, modifier: Modifier = Modifier, stroke: Dp = 13.dp, gap: Dp = 4.dp) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val animated = rings.mapIndexed { i, r ->
        animateFloatAsState(
            if (started) r.progress.coerceIn(0f, 1.5f) else 0f,
            tween(1100, delayMillis = 150 + i * 120, easing = FastOutSlowInEasing),
            label = "ring$i",
        ).value
    }
    Canvas(modifier) {
        val sw = stroke.toPx()
        val g = gap.toPx()
        rings.forEachIndexed { i, r ->
            val inset = sw / 2 + i * (sw + g)
            val sz = Size(size.minDimension - 2 * inset, size.minDimension - 2 * inset)
            if (sz.width <= 0) return@forEachIndexed
            val tl = Offset((size.width - sz.width) / 2, (size.height - sz.height) / 2)
            drawArc(r.color.copy(alpha = 0.18f), 0f, 360f, false, tl, sz, style = Stroke(sw))
            val sweep = 360f * animated[i].coerceAtMost(1f)
            drawArc(r.color, -90f, sweep, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            // Über 100 %: zweite Runde etwas heller
            if (animated[i] > 1f) {
                drawArc(Color.White.copy(alpha = 0.35f), -90f, 360f * (animated[i] - 1f), false, tl, sz, style = Stroke(sw * 0.45f, cap = StrokeCap.Round))
            }
        }
    }
}

/** Wasserglas mit animierter Welle. */
@Composable
fun WaterGlass(fill: Float, modifier: Modifier = Modifier) {
    val level by animateFloatAsState(fill.coerceIn(0f, 1f), tween(1200, easing = FastOutSlowInEasing), label = "level")
    val wave = rememberInfiniteTransition(label = "wave")
    val phase by wave.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        val r = CornerRadius(14.dp.toPx())
        val glass = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, r)) }
        drawPath(glass, Color(0x2242A5F5))
        clipPath(glass) {
            val top = size.height * (1f - level)
            val amp = if (level in 0.02f..0.98f) 5.dp.toPx() else 0f
            for (layer in 0..1) {
                val p = Path().apply {
                    moveTo(0f, size.height)
                    var x = 0f
                    while (x <= size.width) {
                        val y = top + amp * sin(x / size.width * 2 * PI.toFloat() * 1.3f + phase + layer * 1.7f)
                        lineTo(x, y); x += 4f
                    }
                    lineTo(size.width, size.height); close()
                }
                drawPath(p, if (layer == 0) Color(0xFF42A5F5) else Color(0x8890CAF9))
            }
        }
        drawPath(glass, Color(0x5590CAF9), style = Stroke(2.dp.toPx()))
    }
}

/** Herz, das im Takt deines Ruhepulses schlägt. */
@Composable
fun BeatingHeart(bpm: Int?, modifier: Modifier = Modifier, size: TextUnit = 26.sp) {
    val period = (60_000 / (bpm ?: 60).coerceIn(35, 140))
    val t = rememberInfiniteTransition(label = "heart")
    val s by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = period
            1f at 0
            1.22f at (period * 0.12).toInt()
            1f at (period * 0.25).toInt()
            1.12f at (period * 0.35).toInt()
            1f at (period * 0.5).toInt()
        }),
        label = "beat",
    )
    Text("❤️", modifier.graphicsLayer { scaleX = s; scaleY = s }, fontSize = size)
}

/** Kleine Pulskurve, die sich von links nach rechts aufbaut. */
@Composable
fun Sparkline(points: List<HrSample>, modifier: Modifier = Modifier, color: Color = Color(0xFFFF7043), start: Long? = null, end: Long? = null) {
    if (points.size < 2) return
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points.size) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    val t0 = start ?: points.first().t
    val t1 = end ?: points.last().t
    val lo = points.minOf { it.bpm } - 3f
    val hi = points.maxOf { it.bpm } + 3f
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val path = Path()
        val fill = Path()
        var first = true
        val limitX = w * reveal.value
        for (p in points) {
            val x = (p.t - t0).toFloat() / (t1 - t0).coerceAtLeast(1) * w
            if (x > limitX) break
            val y = h - (p.bpm - lo) / (hi - lo) * h
            if (first) { path.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y); first = false } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        if (!first) {
            fill.lineTo(minOf(limitX, w), h); fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Fortschrittsbalken, der sich animiert füllt. */
@Composable
fun AnimatedBar(progress: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val p by animateFloatAsState(if (started) progress.coerceIn(0f, 1f) else 0f, tween(1000, easing = FastOutSlowInEasing), label = "bar")
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2))) {
        drawRect(color.copy(alpha = 0.18f))
        drawRoundRect(color, size = Size(size.width * p, size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}

/** Drehendes Symbol, solange synchronisiert wird. */
@Composable
fun SpinningSync(running: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Text("↻", modifier.graphicsLayer { rotationZ = if (running) angle else 0f }, fontSize = 20.sp, color = Accent)
}

@Composable
fun RingLegend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Text("  $label", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
