package com.superessence.cuttinx.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.Snapping
import com.superessence.cuttinx.core.util.Time
import com.superessence.cuttinx.ui.theme.Cx
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Data needed to draw clip contents. */
class TimelineMedia(
    val thumb: (MediaAsset, Long) -> ImageBitmap?,
    val wave: (MediaAsset) -> FloatArray?,
    val bucketsPerSecond: Int,
    val requests: MutableSharedFlow<Pair<MediaAsset, Long>>,
)

class TimelineCallbacks(
    val onSeek: (Long) -> Unit,
    val onSelect: (String?) -> Unit,
    val onMove: (clipId: String, newStartUs: Long, trackId: String?) -> Unit,
    val onTrimStart: (String, Long) -> Unit,
    val onTrimEnd: (String, Long) -> Unit,
    val onTransitionTap: (String) -> Unit,
    val onTrackHeader: (String) -> Unit,
    val onScrubStart: () -> Unit,
    val onAddTrack: (TrackKind) -> Unit,
)

private enum class Hit { NONE, CLIP, HANDLE_L, HANDLE_R, JUNCTION, HEADER, ADD_ROW }
private data class HitResult(val hit: Hit, val clip: Clip? = null, val track: Track? = null, val kind: TrackKind? = null)

@Composable
fun TimelineView(
    project: Project,
    positionUs: Long,
    selection: Set<String>,
    pxPerSecond: Float,
    onZoom: (Float) -> Unit,
    media: TimelineMedia,
    cb: TimelineCallbacks,
    snapCandidates: (Set<String>) -> List<Long>,
    beats: List<Long>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val headerW = with(density) { 50.dp.toPx() }
    val rulerH = with(density) { 22.dp.toPx() }
    val handleW = with(density) { 16.dp.toPx() }
    val touchSlop = with(density) { 6.dp.toPx() }
    var vScroll by remember { mutableFloatStateOf(0f) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    // drag preview
    var dragClip by remember { mutableStateOf<String?>(null) }
    var dragDeltaUs by remember { mutableLongStateOf(0L) }
    var dragTrackId by remember { mutableStateOf<String?>(null) }
    var trimMode by remember { mutableStateOf<Hit?>(null) }
    var trimTimeUs by remember { mutableLongStateOf(0L) }
    var snapLine by remember { mutableStateOf<Long?>(null) }

    val tracks = remember(project.tracks) { orderedTracks(project) }
    fun rowH(t: Track): Float = rowHeightPx(t, project, density.density)
    val gap = with(density) { 4.dp.toPx() }
    val addRowH = with(density) { 34.dp.toPx() }

    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 30f; typeface = Typeface.DEFAULT_BOLD } }
    val smallPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A9AA6.toInt(); textSize = 24f } }
    with(density) { labelPaint.textSize = 11.dp.toPx(); smallPaint.textSize = 9.dp.toPx() }

    val pos = scrub ?: positionUs
    val latestPos by rememberUpdatedState(pos)
    val latestPx by rememberUpdatedState(pxPerSecond)
    val latestProject by rememberUpdatedState(project)
    val latestSel by rememberUpdatedState(selection)
    val rowHLatest: (Track) -> Float = { t -> rowHeightPx(t, latestProject, density.density) }

    Canvas(modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val w = size.width.toFloat()
            val centerX = headerW + (w - headerW) / 2
            fun xToUs(x: Float, p: Long = latestPos) = p + ((x - centerX) / latestPx * 1_000_000).roundToLong()
            val hit = hitTest(down.position, latestProject, tracks(latestProject), latestPos, latestPx, centerX, headerW, rulerH, vScroll, rowHLatest, gap, handleW, latestSel, addRowH)
            val startPos = latestPos
            var moved = false
            var pinching = false
            var mode: Hit? = null
            var longPressed = false
            // wait for long press if on a clip
            if (hit.hit == Hit.CLIP || hit.hit == Hit.HANDLE_L || hit.hit == Hit.HANDLE_R) {
                val lp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size > 1) { pinching = true; return@withTimeoutOrNull false }
                        val ch = ev.changes.first()
                        if (!ch.pressed) return@withTimeoutOrNull false
                        if ((ch.position - down.position).getDistance() > touchSlop) { moved = true; return@withTimeoutOrNull false }
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (lp == null) { longPressed = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress); cb.onSelect(hit.clip!!.id) }
            }
            val canEdit = hit.clip != null && (hit.clip.id in latestSel || longPressed)
            var lastX = down.position.x; var lastY = down.position.y
            var accumulated = Offset.Zero
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    pinching = true
                    val z = ev.calculateZoom()
                    if (z != 1f) onZoom((latestPx * z).coerceIn(4f, 3000f))
                    ev.changes.forEach { it.consume() }
                    continue
                }
                val ch = pressed.first()
                val d = ch.positionChange()
                accumulated += d
                if (!moved && accumulated.getDistance() > touchSlop) moved = true
                if (!moved || pinching) continue
                if (mode == null) {
                    mode = when {
                        hit.hit == Hit.HANDLE_L && canEdit -> Hit.HANDLE_L
                        hit.hit == Hit.HANDLE_R && canEdit -> Hit.HANDLE_R
                        (hit.hit == Hit.CLIP || hit.hit == Hit.HANDLE_L || hit.hit == Hit.HANDLE_R) && canEdit && hit.track?.locked == false -> Hit.CLIP
                        else -> Hit.NONE
                    }
                    if (mode == Hit.CLIP) { dragClip = hit.clip!!.id; dragDeltaUs = 0; dragTrackId = hit.track!!.id }
                    if (mode == Hit.HANDLE_L || mode == Hit.HANDLE_R) { dragClip = hit.clip!!.id; trimMode = mode; trimTimeUs = if (mode == Hit.HANDLE_L) hit.clip.startUs else hit.clip.endUs }
                    if (mode == Hit.NONE) cb.onScrubStart()
                }
                ch.consume()
                when (mode) {
                    Hit.CLIP -> {
                        val c = hit.clip!!
                        val raw = ((ch.position.x - down.position.x) / latestPx * 1_000_000).roundToLong()
                        val thr = (10.dp.toPx() / latestPx * 1_000_000).roundToLong()
                        val cands = snapCandidates(setOf(c.id))
                        val snappedStart = Snapping.snapRange(c.startUs + raw, c.durationUs, cands, thr)
                        snapLine = when (snappedStart) { c.startUs + raw -> null; else -> cands.firstOrNull { it == snappedStart || it == snappedStart + c.durationUs } }
                        dragDeltaUs = snappedStart - c.startUs
                        // vertical: target track of the same family
                        val target = trackAtY(ch.position.y, tracks(latestProject), rulerH, vScroll, rowHLatest, gap)
                        dragTrackId = if (target != null && compatible(target.kind, hit.track!!.kind) && !target.locked) target.id else hit.track!!.id
                    }
                    Hit.HANDLE_L, Hit.HANDLE_R -> {
                        val c = hit.clip!!
                        val base = if (mode == Hit.HANDLE_L) c.startUs else c.endUs
                        val raw = base + ((ch.position.x - down.position.x) / latestPx * 1_000_000).roundToLong()
                        val thr = (10.dp.toPx() / latestPx * 1_000_000).roundToLong()
                        val sn = Snapping.snap(raw, snapCandidates(setOf(c.id)), thr)
                        snapLine = if (sn != raw) sn else null
                        trimTimeUs = sn
                    }
                    else -> {
                        val dx = ch.position.x - lastX
                        val np = (latestPos - (dx / latestPx * 1_000_000).roundToLong()).coerceIn(0, max(0, latestProject.durationUs))
                        scrub = np; cb.onSeek(np)
                        vScroll = (vScroll - (ch.position.y - lastY)).coerceIn(0f, max(0f, contentHeight(tracks(latestProject), rowHLatest, gap, addRowH) - (size.height - rulerH)))
                    }
                }
                lastX = ch.position.x; lastY = ch.position.y
            }
            // release
            when {
                mode == Hit.CLIP && dragClip != null -> { val c = hit.clip!!; if (dragDeltaUs != 0L || dragTrackId != hit.track!!.id) cb.onMove(c.id, c.startUs + dragDeltaUs, dragTrackId) }
                (mode == Hit.HANDLE_L || mode == Hit.HANDLE_R) && dragClip != null -> { if (mode == Hit.HANDLE_L) cb.onTrimStart(dragClip!!, trimTimeUs) else cb.onTrimEnd(dragClip!!, trimTimeUs) }
                !moved && !pinching && !longPressed -> when (hit.hit) {
                    Hit.JUNCTION -> cb.onTransitionTap(hit.clip!!.id)
                    Hit.CLIP, Hit.HANDLE_L, Hit.HANDLE_R -> cb.onSelect(hit.clip!!.id)
                    Hit.HEADER -> cb.onTrackHeader(hit.track!!.id)
                    Hit.ADD_ROW -> cb.onAddTrack(hit.kind!!)
                    else -> if (down.position.y < rulerH) { val t = xToUs(down.position.x, startPos).coerceIn(0, latestProject.durationUs); cb.onSeek(t) } else cb.onSelect(null)
                }
            }
            dragClip = null; trimMode = null; snapLine = null; scrub = null
        }
    }) {
        val w = size.width
        val centerX = headerW + (w - headerW) / 2
        val pxPerUs = pxPerSecond / 1_000_000f
        fun x(t: Long) = centerX + (t - pos) * pxPerUs
        val visStart = pos - ((centerX - headerW) / pxPerUs).toLong()
        val visEnd = pos + ((w - centerX) / pxPerUs).toLong()
        val nc = drawContext.canvas.nativeCanvas

        // background
        drawRect(Color(0xFF0E0E10))
        // project end shade
        val endX = x(project.durationUs)
        if (endX < w) drawRect(Color(0xFF151518), Offset(max(endX, headerW), rulerH), Size(w - max(endX, headerW), size.height))

        // tracks
        clipRect(headerW, rulerH, w, size.height) {
            var y = rulerH - vScroll
            for (t in tracks) {
                val h = rowH(t)
                if (y + h > rulerH && y < size.height) {
                    drawRect(Color(0xFF121215), Offset(headerW, y), Size(w - headerW, h))
                    val sorted = t.sortedClips()
                    for ((ci, c) in sorted.withIndex()) {
                        if (c.endUs < visStart || c.startUs > visEnd) continue
                        val isDragged = dragClip == c.id
                        var cs = c.startUs; var ce = c.endUs; var cy = y
                        if (isDragged && trimMode == null) {
                            cs += dragDeltaUs; ce += dragDeltaUs
                            if (dragTrackId != t.id) { cy = trackTop(dragTrackId, tracks, rulerH, vScroll, rowHLatest, gap) ?: y }
                        }
                        if (isDragged && trimMode == Hit.HANDLE_L) cs = min(trimTimeUs, ce - 100_000)
                        if (isDragged && trimMode == Hit.HANDLE_R) ce = max(trimTimeUs, cs + 100_000)
                        val l = x(cs); val r = x(ce)
                        val rect = Rect(l + 1, cy + 2, r - 1, cy + h - 2)
                        drawClip(this, c, t, project, rect, media, nc, labelPaint, smallPaint, c.id in selection, isDragged, pos)
                        // junction / transition button between adjacent clips
                        if (t.kind == TrackKind.VIDEO && ci > 0) {
                            val prev = sorted[ci - 1]
                            if (abs(prev.endUs - c.startUs) < 50_000 || c.transitionIn != null) {
                                val jx = if (c.transitionIn != null) x(c.startUs + c.transitionIn!!.durationUs / 2) else x(c.startUs)
                                val js = 9.dp.toPx()
                                drawRoundRect(if (c.transitionIn != null) Cx.Yellow else Color.White, Offset(jx - js, cy + h / 2 - js), Size(js * 2, js * 2), CornerRadius(4.dp.toPx()))
                                if (c.transitionIn != null) {
                                    drawLine(Color.Black, Offset(jx - js * 0.5f, cy + h / 2 + js * 0.5f), Offset(jx + js * 0.5f, cy + h / 2 - js * 0.5f), 2.dp.toPx())
                                    drawRect(Cx.Yellow.copy(alpha = 0.25f), Offset(x(c.startUs), cy + 2), Size(c.transitionIn!!.durationUs * pxPerUs, h - 4))
                                } else {
                                    drawLine(Color.Black, Offset(jx - js * 0.5f, cy + h / 2), Offset(jx + js * 0.5f, cy + h / 2), 2.dp.toPx())
                                    drawLine(Color.Black, Offset(jx, cy + h / 2 - js * 0.5f), Offset(jx, cy + h / 2 + js * 0.5f), 2.dp.toPx())
                                }
                            }
                        }
                    }
                }
                y += h + gap
            }
            // "add track" row
            val labels = listOf("+ Vidéo", "+ Texte", "+ Audio", "+ Effets")
            val bw = (w - headerW) / 4f
            labels.forEachIndexed { i, lab ->
                val bx = headerW + i * bw
                drawRoundRect(Color(0xFF1C1C20), Offset(bx + 4.dp.toPx(), y + 4), Size(bw - 8.dp.toPx(), addRowH - 8), CornerRadius(8.dp.toPx()))
                nc.drawText(lab, bx + (bw - labelPaint.measureText(lab)) / 2, y + addRowH / 2 + labelPaint.textSize / 3, labelPaint)
            }
        }

        // headers
        clipRect(0f, rulerH, headerW, size.height) {
            drawRect(Color(0xFF0A0A0B), Offset(0f, rulerH), Size(headerW, size.height))
            var y = rulerH - vScroll
            for (t in tracks) {
                val h = rowH(t)
                val col = trackColor(t)
                drawRoundRect(col.copy(alpha = 0.35f), Offset(4f, y + 2), Size(headerW - 8, h - 4), CornerRadius(8.dp.toPx()))
                val name = when (t.kind) { TrackKind.VIDEO -> if (t.id == project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.id) "▶ Princ." else "▣ Vidéo"; TrackKind.AUDIO -> "♪ " + t.name.take(5); TrackKind.TEXT -> "T Texte"; TrackKind.SUBTITLE -> "≡ S-T"; TrackKind.EFFECT -> "✦ Effets" }
                nc.drawText(name, 8.dp.toPx(), y + h / 2, smallPaint.apply { color = android.graphics.Color.WHITE })
                val flags = (if (t.locked) "🔒" else "") + (if (t.hidden) "◌" else "") + (if (t.muted) "🔇" else "")
                if (flags.isNotEmpty()) nc.drawText(flags, 8.dp.toPx(), y + h / 2 + smallPaint.textSize * 1.3f, smallPaint)
                smallPaint.color = 0xFF9A9AA6.toInt()
                y += h + gap
            }
        }

        // ruler
        drawRect(Color(0xFF0A0A0B), Offset(0f, 0f), Size(w, rulerH))
        val secPerMajor = niceStep(90.dp.toPx() / pxPerSecond)
        var tick = (max(0L, visStart) / (secPerMajor * 1e6).toLong()) * (secPerMajor * 1e6).toLong()
        while (tick <= visEnd) {
            val tx = x(tick)
            if (tx >= headerW) {
                drawLine(Color(0xFF55555F), Offset(tx, rulerH * 0.55f), Offset(tx, rulerH), 1.dp.toPx())
                nc.drawText(Time.format(tick, secPerMajor < 1), tx + 3.dp.toPx(), rulerH * 0.5f, smallPaint)
                val minor = (secPerMajor * 1e6 / 4).toLong()
                for (k in 1..3) { val mx = x(tick + minor * k); if (mx > headerW) drawLine(Color(0xFF33333A), Offset(mx, rulerH * 0.75f), Offset(mx, rulerH), 1f) }
            }
            tick += (secPerMajor * 1e6).toLong()
        }
        // beats
        for (b in beats) { if (b in visStart..visEnd) { val bx = x(b); if (bx > headerW) drawCircle(Color(0xFF4FC3F7), 2.dp.toPx(), Offset(bx, rulerH - 3.dp.toPx())) } }
        // markers
        for (m in project.markers) {
            if (m.timeUs !in visStart..visEnd) continue
            val mx = x(m.timeUs); if (mx < headerW) continue
            val path = Path().apply { moveTo(mx - 5.dp.toPx(), 0f); lineTo(mx + 5.dp.toPx(), 0f); lineTo(mx, 8.dp.toPx()); close() }
            drawPath(path, Color(m.color.toInt()))
            drawLine(Color(m.color.toInt()).copy(alpha = 0.4f), Offset(mx, rulerH), Offset(mx, size.height), 1.dp.toPx())
        }
        // snap guide
        snapLine?.let { s -> val sx = x(s); drawLine(Cx.Yellow, Offset(sx, rulerH), Offset(sx, size.height), 1.5.dp.toPx()) }
        // playhead
        drawLine(Color.White, Offset(centerX, 0f), Offset(centerX, size.height), 2.dp.toPx())
        drawCircle(Color.White, 4.dp.toPx(), Offset(centerX, 3.dp.toPx()))
        if (trimMode != null) nc.drawText(Time.format(trimTimeUs), centerX + 6.dp.toPx(), rulerH + 14.dp.toPx(), labelPaint.apply { color = 0xFFFFCC1F.toInt() })
        labelPaint.color = android.graphics.Color.WHITE
    }
}

// ---------------------------------------------------------------------- helpers (pure)

fun rowHeightPx(t: Track, p: Project, density: Float): Float = density * when (t.kind) {
    TrackKind.VIDEO -> if (t.id == p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.id) 58f else 46f
    TrackKind.AUDIO -> 44f; TrackKind.EFFECT -> 30f; else -> 32f
}

private fun orderedTracks(p: Project): List<Track> {
    // top to bottom on screen: subtitles, texts, effects, overlay videos (top-most first), main, audio
    val videos = p.tracks.filter { it.kind == TrackKind.VIDEO }
    return p.tracks.filter { it.kind == TrackKind.SUBTITLE } + p.tracks.filter { it.kind == TrackKind.TEXT } + p.tracks.filter { it.kind == TrackKind.EFFECT } +
        videos.drop(1).reversed() + videos.take(1) + p.tracks.filter { it.kind == TrackKind.AUDIO }
}

private fun tracks(p: Project) = orderedTracks(p)

private fun compatible(a: TrackKind, b: TrackKind) = a == b || (a == TrackKind.TEXT && b == TrackKind.SUBTITLE) || (a == TrackKind.SUBTITLE && b == TrackKind.TEXT)

private fun contentHeight(tracks: List<Track>, rowH: (Track) -> Float, gap: Float, addRow: Float) = tracks.sumOf { (rowH(it) + gap).toDouble() }.toFloat() + addRow + gap

private fun trackAtY(y: Float, tracks: List<Track>, rulerH: Float, vScroll: Float, rowH: (Track) -> Float, gap: Float): Track? {
    var top = rulerH - vScroll
    for (t in tracks) { val h = rowH(t); if (y >= top && y < top + h + gap) return t; top += h + gap }
    return null
}

private fun trackTop(id: String?, tracks: List<Track>, rulerH: Float, vScroll: Float, rowH: (Track) -> Float, gap: Float): Float? {
    var top = rulerH - vScroll
    for (t in tracks) { if (t.id == id) return top; top += rowH(t) + gap }
    return null
}

private fun hitTest(p: Offset, project: Project, tracks: List<Track>, pos: Long, pxPerSec: Float, centerX: Float, headerW: Float, rulerH: Float, vScroll: Float,
                    rowH: (Track) -> Float, gap: Float, handleW: Float, selection: Set<String>, addRowH: Float): HitResult {
    if (p.y < rulerH) return HitResult(Hit.NONE)
    val pxPerUs = pxPerSec / 1_000_000f
    fun x(t: Long) = centerX + (t - pos) * pxPerUs
    var top = rulerH - vScroll
    for (t in tracks) {
        val h = rowH(t)
        if (p.y >= top && p.y < top + h) {
            if (p.x < headerW) return HitResult(Hit.HEADER, track = t)
            val sorted = t.sortedClips()
            // junctions first
            if (t.kind == TrackKind.VIDEO) for (i in 1 until sorted.size) {
                val c = sorted[i]
                val prev = sorted[i - 1]
                if (abs(prev.endUs - c.startUs) < 50_000 || c.transitionIn != null) {
                    val jx = if (c.transitionIn != null) x(c.startUs + c.transitionIn!!.durationUs / 2) else x(c.startUs)
                    if (abs(p.x - jx) < handleW * 0.8f && abs(p.y - (top + h / 2)) < h / 3) return HitResult(Hit.JUNCTION, c, t)
                }
            }
            // selected clip handles have priority
            for (c in sorted.sortedByDescending { it.id in selection }) {
                val l = x(c.startUs); val r = x(c.endUs)
                if (c.id in selection) {
                    if (abs(p.x - l) < handleW) return HitResult(Hit.HANDLE_L, c, t)
                    if (abs(p.x - r) < handleW) return HitResult(Hit.HANDLE_R, c, t)
                }
                if (p.x in l..r) return HitResult(Hit.CLIP, c, t)
            }
            return HitResult(Hit.NONE, track = t)
        }
        top += h + gap
    }
    if (p.y >= top && p.y < top + addRowH && p.x > headerW) {
        val idx = ((p.x - headerW) / ((centerX - headerW) * 2 / 4f)).toInt().coerceIn(0, 3)
        return HitResult(Hit.ADD_ROW, kind = listOf(TrackKind.VIDEO, TrackKind.TEXT, TrackKind.AUDIO, TrackKind.EFFECT)[idx])
    }
    return HitResult(Hit.NONE)
}

private fun niceStep(seconds: Float): Double {
    val steps = doubleArrayOf(0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0)
    return steps.firstOrNull { it >= seconds } ?: 1200.0
}

fun trackColor(t: Track): Color = when (t.kind) {
    TrackKind.VIDEO -> Cx.Video
    TrackKind.AUDIO -> when (t.audioRole) { AudioRole.MUSIC -> Cx.Music; AudioRole.VOICE_OVER -> Cx.Voice; else -> Cx.Audio }
    TrackKind.TEXT -> Cx.Text
    TrackKind.SUBTITLE -> Cx.Subtitle
    TrackKind.EFFECT -> Cx.Effect
}

private fun drawClip(ds: DrawScope, c: Clip, t: Track, p: Project, r: Rect, media: TimelineMedia, nc: android.graphics.Canvas, label: Paint, small: Paint, selected: Boolean, dragged: Boolean, pos: Long) = with(ds) {
    if (r.width < 1) return@with
    val radius = CornerRadius(6.dp.toPx())
    val base = trackColor(t).let { if (!c.enabled || t.hidden) it.copy(alpha = 0.35f) else it }
    drawRoundRect(base, r.topLeft, r.size, radius, alpha = if (dragged) 0.7f else 1f)
    val asset = p.asset(c.assetId)
    clipRect(r.left, r.top, r.right, r.bottom) {
        when {
            asset != null && asset.isVisual && (t.kind == TrackKind.VIDEO) -> {
                val th = r.height
                val tw = th * (asset.displayWidth.toFloat() / max(1, asset.displayHeight)).coerceIn(0.5f, 2.0f)
                var x = r.left
                val stepUs = (tw / (r.width / max(1L, c.durationUs))).toLong()
                var rel = 0L
                while (x < r.right && x < size.width + tw) {
                    if (x + tw > 0) {
                        val src = SpeedMath.timelineToSource(c, (rel + stepUs / 2).coerceAtMost(c.durationUs))
                        val img = media.thumb(asset, src)
                        if (img != null) drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), r.top.toInt()), dstSize = androidx.compose.ui.unit.IntSize(tw.toInt() + 1, th.toInt()))
                        else media.requests.tryEmit(asset to src)
                    }
                    x += tw; rel += stepUs
                }
            }
            asset != null && t.kind == TrackKind.AUDIO -> {
                val wf = media.wave(asset)
                if (wf == null) media.requests.tryEmit(asset to -1L)
                else {
                    val mid = r.center.y; val amp = r.height * 0.42f
                    val buckets = wf.size / 2
                    val stepPx = 2f
                    var x = max(r.left, 0f)
                    val gain = c.audio.volume.toFloat().coerceIn(0f, 2f)
                    while (x < min(r.right, size.width)) {
                        val rel = ((x - r.left) / r.width * c.durationUs).toLong()
                        val src = SpeedMath.timelineToSource(c, rel)
                        val b = (src * media.bucketsPerSecond / 1_000_000).toInt()
                        if (b in 0 until buckets) {
                            val mn = wf[b * 2] * gain; val mx = wf[b * 2 + 1] * gain
                            drawLine(Color.White.copy(alpha = 0.55f), Offset(x, mid - mx * amp), Offset(x, mid - mn * amp), stepPx)
                        }
                        x += stepPx
                    }
                }
            }
            c.kind == ClipKind.COLOR -> drawRect(Color(c.color.toInt()), r.topLeft, r.size)
        }
        // fades on audio
        if (c.audio.fadeInUs > 0) drawPath(Path().apply { moveTo(r.left, r.top); lineTo(r.left + c.audio.fadeInUs.toFloat() / c.durationUs * r.width, r.top); lineTo(r.left, r.bottom); close() }, Color.Black.copy(alpha = 0.35f))
        if (c.audio.fadeOutUs > 0) drawPath(Path().apply { moveTo(r.right, r.top); lineTo(r.right - c.audio.fadeOutUs.toFloat() / c.durationUs * r.width, r.top); lineTo(r.right, r.bottom); close() }, Color.Black.copy(alpha = 0.35f))
        // label
        val name = when { c.text != null -> c.text!!.text.replace('\n', ' '); c.kind == ClipKind.ADJUSTMENT -> "✦ " + c.effects.joinToString { com.superessence.cuttinx.core.effects.EffectRegistry[it.effectId]?.name ?: "" }.ifBlank { "Calque d'effets" }; else -> c.name }
        val badges = buildString {
            if (c.speedCurve.isNotEmpty()) append("⟿ ") else if (c.speed != 1.0) append("%.2gx ".format(c.speed))
            if (c.effects.isNotEmpty() && c.kind != ClipKind.ADJUSTMENT) append("✦${c.effects.size} ")
            if (c.audio.muted) append("🔇 ")
            if (c.chromaKey.enabled) append("◐ ")
            if (c.mask.shape != MaskShape.NONE) append("◍ ")
            if (asset?.missing == true) append("⚠ manquant ")
        }
        val txt = badges + name
        val maxW = r.width - 8.dp.toPx()
        if (maxW > 16) {
            val n = label.breakText(txt, true, maxW, null)
            val tx = max(r.left, 0f) + 4.dp.toPx()
            nc.drawText(txt, 0, n, tx, if (t.kind == TrackKind.VIDEO && asset?.isVisual == true) r.bottom - 4.dp.toPx() else r.center.y + label.textSize / 3, label.apply { setShadowLayer(3f, 0f, 1f, android.graphics.Color.BLACK) })
            label.clearShadowLayer()
        }
        // keyframes
        if (selected) {
            val times = c.keyframes.values.flatMap { k -> k.keyframes.map { it.timeUs } }.distinct()
            for (kt in times) {
                val kx = r.left + kt.toFloat() / max(1L, c.durationUs) * r.width
                val s = 4.dp.toPx(); val cy = r.top + s + 2
                drawPath(Path().apply { moveTo(kx, cy - s); lineTo(kx + s, cy); lineTo(kx, cy + s); lineTo(kx - s, cy); close() }, if (abs(c.startUs + kt - pos) < 30_000) Cx.Yellow else Color.White)
            }
        }
    }
    if (selected) {
        drawRoundRect(Cx.Yellow, r.topLeft, r.size, radius, style = Stroke(2.dp.toPx()))
        val hw = 10.dp.toPx()
        drawRoundRect(Cx.Yellow, Offset(r.left - 1, r.top), Size(hw, r.height), CornerRadius(4.dp.toPx()))
        drawRoundRect(Cx.Yellow, Offset(r.right - hw + 1, r.top), Size(hw, r.height), CornerRadius(4.dp.toPx()))
        drawLine(Color.Black, Offset(r.left + hw / 2, r.top + r.height * 0.3f), Offset(r.left + hw / 2, r.bottom - r.height * 0.3f), 1.5.dp.toPx())
        drawLine(Color.Black, Offset(r.right - hw / 2, r.top + r.height * 0.3f), Offset(r.right - hw / 2, r.bottom - r.height * 0.3f), 1.5.dp.toPx())
    }
}
