package de.endurofit.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import de.endurofit.R
import de.endurofit.data.Calc
import de.endurofit.data.Fit
import de.endurofit.data.HrSample
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs

/** Puls zu einem Zeitpunkt (nächster Wert innerhalb von 15 Sekunden). */
fun hrAt(samples: List<HrSample>, t: Long): Int? {
    if (samples.isEmpty()) return null
    val idx = samples.binarySearchBy(t) { it.t }
    if (idx >= 0) return samples[idx].bpm
    val ins = -idx - 1
    val a = samples.getOrNull(ins - 1)
    val b = samples.getOrNull(ins)
    val best = listOfNotNull(a, b).minByOrNull { abs(it.t - t) } ?: return null
    return if (abs(best.t - t) <= 15_000) best.bpm else null
}

fun nearestPoint(track: List<Fit.Point>, t: Long): Fit.Point? {
    if (track.isEmpty()) return null
    val idx = track.binarySearchBy(t) { it.t }
    if (idx >= 0) return track[idx]
    val ins = -idx - 1
    return listOfNotNull(track.getOrNull(ins - 1), track.getOrNull(ins)).minByOrNull { abs(it.t - t) }
}

/**
 * Strecke auf OpenStreetMap, eingefärbt nach Pulszone.
 * Extrempunkte werden als nummerierte Marker gezeigt, deine Markierungen als ⚑.
 */
@Composable
fun TrackMap(
    track: List<Fit.Point>,
    samples: List<HrSample>,
    maxHr: Int,
    peaks: List<Calc.Peak>,
    markers: List<Long>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 260.dp,
) {
    var fullscreen by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().height(height)) {
        MapContent(track, samples, maxHr, peaks, markers, Modifier.fillMaxSize())
        FilledTonalButton(
            onClick = { fullscreen = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) { Text("⛶ Vollbild") }
    }
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    MapContent(track, samples, maxHr, peaks, markers, Modifier.fillMaxSize())
                    Button(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Text("Schließen") }
                }
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun MapContent(
    track: List<Fit.Point>,
    samples: List<HrSample>,
    maxHr: Int,
    peaks: List<Calc.Peak>,
    markers: List<Long>,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                // Karte bekommt die Wischgesten, nicht die Seite drumherum
                setOnTouchListener { v, ev ->
                    if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    false
                }
                fill(this, ctx, track, samples, maxHr, peaks, markers)
            }
        },
        onRelease = { it.onDetach() },
    )
}

private fun fill(
    map: MapView, ctx: Context, track: List<Fit.Point>, samples: List<HrSample>, maxHr: Int,
    peaks: List<Calc.Peak>, markers: List<Long>,
) {
    if (track.size < 2) return
    // Strecke in Abschnitte gleicher Zone aufteilen
    var segment = ArrayList<GeoPoint>()
    var zone = -1
    fun flush() {
        if (segment.size >= 2) {
            map.overlays.add(Polyline(map).apply {
                setPoints(segment)
                outlinePaint.color = (if (zone < 0) Accent else zoneColor(zone)).toArgb()
                outlinePaint.strokeWidth = 9f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                isGeodesic = false
                infoWindow = null
            })
        }
    }
    for (p in track) {
        val bpm = hrAt(samples, p.t)
        val z = if (bpm == null) -1 else Calc.zoneOf(bpm, maxHr).coerceAtLeast(1)
        val gp = GeoPoint(p.lat, p.lon)
        if (z != zone) {
            if (segment.isNotEmpty()) segment.add(gp)
            flush()
            segment = arrayListOf(gp)
            zone = z
        } else {
            segment.add(gp)
        }
    }
    flush()

    fun marker(p: Fit.Point, title: String, snippet: String, iconRes: Int?, label: String?, bg: Int = android.graphics.Color.rgb(229, 57, 53)) {
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(p.lat, p.lon)
            this.title = title
            this.snippet = snippet
            if (iconRes != null) {
                ContextCompat.getDrawable(ctx, iconRes)?.let { icon = it }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            if (label != null) {
                setTextLabelFontSize(34)
                setTextLabelBackgroundColor(bg)
                setTextLabelForegroundColor(android.graphics.Color.WHITE)
                setTextIcon(label)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
        })
    }

    val first = track.first()
    val last = track.last()
    marker(first, "Start", Calc.fmtTime(first.t), R.drawable.ic_map_start, null)
    marker(last, "Ziel", Calc.fmtTime(last.t), R.drawable.ic_map_finish, null)

    peaks.sortedByDescending { it.maxBpm }.forEachIndexed { i, pk ->
        val p = nearestPoint(track, pk.maxAt) ?: return@forEachIndexed
        marker(
            p, "Extrempunkt ${i + 1}",
            "${Calc.fmtTime(pk.start)} Uhr · max ${pk.maxBpm} bpm · ${Calc.fmtDuration(pk.durationMs)} lang",
            null, " ${i + 1} "
        )
    }
    markers.forEachIndexed { i, m ->
        val p = nearestPoint(track, m) ?: return@forEachIndexed
        marker(
            p, "Markierung ${i + 1}", "${Calc.fmtTime(m)} Uhr" + (hrAt(samples, m)?.let { " · $it bpm" } ?: ""),
            null, " ⚑${i + 1} ", android.graphics.Color.rgb(55, 71, 79)
        )
    }

    val box = BoundingBox.fromGeoPointsSafe(track.map { GeoPoint(it.lat, it.lon) })
    map.addOnFirstLayoutListener { _, _, _, _, _ ->
        map.zoomToBoundingBox(box.increaseByScale(1.25f), false)
    }
}
