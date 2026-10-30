@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.superessence.cuttinx.desktop.editor

import com.superessence.cuttinx.icons.CxIcons
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.desktop.Cx
import com.superessence.cuttinx.desktop.engine.EditorModel
import com.superessence.cuttinx.desktop.ui.LocalAccent
import kotlin.math.roundToInt

private val HeaderW = 150.dp

/** Ctrl held (multi-selection with Ctrl + click), tracked by the window key handler. */
var ctrlHeld = false
private val RulerH = 26.dp

private fun rowHeight(t: Track): Dp = when (t.kind) { TrackKind.VIDEO -> 58.dp; TrackKind.AUDIO -> 44.dp; else -> 36.dp }

private fun clipColor(t: Track, c: Clip): Color = when {
    c.kind == ClipKind.ADJUSTMENT -> Color(0xFF8E5CFF)
    c.kind == ClipKind.TEXT || c.kind == ClipKind.SUBTITLE -> Color(0xFFFF8A3D)
    c.kind == ClipKind.COLOR -> Color(c.color).copy(alpha = 1f)
    t.kind == TrackKind.AUDIO -> when (t.audioRole) { AudioRole.MUSIC -> Color(0xFF2FB67C); AudioRole.SFX -> Color(0xFFE0507A); AudioRole.VOICE_OVER -> Color(0xFF3DB6E0); else -> Color(0xFF3FA36B) }
    else -> Color(0xFF3D6FD9)
}

/** Display order: texts and effect layers on top, video tracks (main at the bottom), then sound. */
private fun displayOrder(p: Project): List<Track> =
    p.tracks.filter { it.kind == TrackKind.SUBTITLE } + p.tracks.filter { it.kind == TrackKind.TEXT }.reversed() + p.tracks.filter { it.kind == TrackKind.EFFECT }.reversed() +
        p.tracks.filter { it.kind == TrackKind.VIDEO }.reversed() + p.tracks.filter { it.kind == TrackKind.AUDIO }

@Composable
fun Timeline(m: EditorModel, modifier: Modifier) {
    val p by m.project.collectAsState()
    val pos by m.player.position.collectAsState()
    val sel by m.selection.collectAsState()
    val pr = p ?: return
    val zoomWanted by m.zoom.collectAsState() // dp per second
    val scope = rememberCoroutineScope()
    val fit by m.fitRequest.collectAsState()
    val hs = rememberScrollState()
    val vs = rememberScrollState()
    val tracks = displayOrder(pr)
    val totalSec = maxOf(30.0, pr.durationUs / 1e6 + 10)
    val density = LocalDensity.current
    // a layout cannot be wider than ~260 000 px: the deepest zoom depends on the length of the film
    val zoomCap = (240_000.0 / density.density / totalSec).toFloat().coerceIn(EditorModel.MIN_ZOOM, EditorModel.MAX_ZOOM)
    m.zoomCap = zoomCap
    val pps = zoomWanted.coerceAtMost(zoomCap)
    val contentW = (totalSec * pps).dp
    val accent = LocalAccent.current

    // zoom around a point of the view (mouse wheel), keeping the time under the cursor in place
    fun zoomAt(factor: Float, cursorPx: Float) {
        val old = pps
        m.zoomBy(factor)
        val k = m.zoom.value / old
        scope.launch { withFrameNanos { }; hs.scrollTo(((hs.value + cursorPx) * k - cursorPx).roundToInt().coerceAtLeast(0)) }
    }
    LaunchedEffect(fit) {
        if (fit > 0) {
            val view = hs.viewportSize / density.density
            if (view > 0) m.zoom.value = (view / ((pr.durationUs / 1e6).coerceAtLeast(1.0) + 1).toFloat()).coerceIn(EditorModel.MIN_ZOOM, 600f)
            withFrameNanos { }; hs.scrollTo(0)
        }
    }

    // keep the playhead visible while playing
    val playing by m.player.playing.collectAsState()
    LaunchedEffect(pos, playing) {
        if (playing) {
            val x = with(density) { (pos / 1e6 * pps).dp.toPx() }.toInt()
            val view = hs.viewportSize
            if (view > 0 && (x < hs.value || x > hs.value + view - 40)) hs.scrollTo((x - view / 4).coerceAtLeast(0))
        }
    }

    Column(modifier.background(Cx.Bg)) {
        // edit toolbar
        Row(Modifier.fillMaxWidth().background(Cx.Panel).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tool(CxIcons.Split, "Couper (S)") { m.splitAtPlayhead() }
            Tool(CxIcons.Delete, "Supprimer") { m.deleteSelection() }
            Tool(CxIcons.RippleDelete, "Supprimer et resserrer") { m.deleteSelection(ripple = true) }
            Tool(CxIcons.Duplicate, "Dupliquer") { m.duplicateSelection() }
            Tool(CxIcons.Marker, "Marqueur") { m.addMarker() }
            var addMenu by remember { mutableStateOf(false) }
            Box {
                Tool(CxIcons.Add, "Piste") { addMenu = true }
                DropdownMenu(addMenu, { addMenu = false }) {
                    listOf(TrackKind.VIDEO to "Vidéo / incrustation", TrackKind.AUDIO to "Audio", TrackKind.TEXT to "Texte", TrackKind.SUBTITLE to "Sous-titres", TrackKind.EFFECT to "Calque d'effets").forEach { (k, l) ->
                        DropdownMenuItem({ Text(l) }, { m.addTrack(k); addMenu = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Molette : défiler · Ctrl + molette : zoom · clic molette / droit glissé : déplacer", color = Cx.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Tool(CxIcons.ZoomOut, "") { m.zoomBy(0.8f) }
            // logarithmic slider: as precise for a whole film as for frame-by-frame editing
            Slider(kotlin.math.ln(pps), { m.zoom.value = kotlin.math.exp(it).coerceIn(EditorModel.MIN_ZOOM, zoomCap) }, Modifier.width(140.dp),
                valueRange = kotlin.math.ln(EditorModel.MIN_ZOOM)..kotlin.math.ln(zoomCap).coerceAtLeast(kotlin.math.ln(EditorModel.MIN_ZOOM) + 0.01f))
            Tool(CxIcons.ZoomIn, "") { m.zoomBy(1.25f) }
            Tool(CxIcons.Fit, "Tout voir") { m.fitRequest.value++ }
            Tool(CxIcons.ZoomIn, "Max") {
                // deepest zoom, centred on the playhead
                m.zoom.value = zoomCap
                scope.launch { withFrameNanos { }; val x = with(density) { (m.playhead / 1e6 * zoomCap).dp.toPx() }; hs.scrollTo((x - hs.viewportSize / 2).roundToInt().coerceAtLeast(0)) }
            }
        }
        Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()
            // mouse: wheel = scroll in time, Shift + wheel = tracks, Ctrl / Alt + wheel = zoom around the cursor
            .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { ev ->
                val ch = ev.changes.firstOrNull() ?: return@onPointerEvent
                val d = ch.scrollDelta
                val mods = ev.keyboardModifiers
                val cursor = ch.position.x - with(density) { HeaderW.toPx() }
                when {
                    mods.isCtrlPressed || mods.isMetaPressed || mods.isAltPressed -> zoomAt(if (d.y < 0) 1.15f else 1 / 1.15f, cursor.coerceAtLeast(0f))
                    mods.isShiftPressed -> scope.launch { vs.scrollBy(d.y * 60f) }
                    else -> scope.launch { hs.scrollBy((if (d.x != 0f) d.x else d.y) * 80f) }
                }
                ch.consume()
            }
            // middle / right button drag = move along the timeline
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var last: Offset? = null
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val pan = ev.buttons.isTertiaryPressed || ev.buttons.isSecondaryPressed
                        val ch = ev.changes.firstOrNull() ?: continue
                        if (pan) {
                            last?.let { l -> val dx = ch.position.x - l.x; val dy = ch.position.y - l.y; scope.launch { hs.scrollBy(-dx); vs.scrollBy(-dy) } }
                            last = ch.position; ch.consume()
                        } else last = null
                    }
                }
            }
            .verticalScroll(vs)) {
            // track headers
            Column(Modifier.width(HeaderW)) {
                Box(Modifier.height(RulerH).fillMaxWidth().background(Cx.Panel))
                tracks.forEach { t -> TrackHeader(m, t, Modifier.height(rowHeight(t))) }
            }
            Box(Modifier.weight(1f).horizontalScroll(hs)) {
                Column(Modifier.width(contentW)) {
                    Ruler(pr, pps, Modifier.fillMaxWidth().height(RulerH)) { us -> m.player.seek(us) }
                    tracks.forEachIndexed { i, t ->
                        Box(Modifier.fillMaxWidth().height(rowHeight(t)).background(if (i % 2 == 0) Color(0xFF14151A) else Color(0xFF121317))
                            .pointerInput(t.id) { detectTapGestures { off -> m.select(null); m.player.seek((off.x / density.density / pps * 1e6).toLong()) } }) {
                            t.clips.forEach { c -> key(c.id) { ClipBox(m, pr, t, tracks, c, pps, c.id in sel) } }
                        }
                    }
                }
                // markers and playhead
                val totalH = RulerH + tracks.fold(0.dp) { a, t -> a + rowHeight(t) }
                pr.markers.forEach { mk -> Box(Modifier.offset(x = (mk.timeUs / 1e6 * pps).dp).width(2.dp).height(totalH).background(Color(mk.color).copy(alpha = 0.6f))) }
                Box(Modifier.offset(x = (pos / 1e6 * pps).dp - 1.dp).width(2.dp).height(totalH).background(accent))
                Box(Modifier.offset(x = (pos / 1e6 * pps).dp - 6.dp).size(12.dp).clip(RoundedCornerShape(0.dp, 0.dp, 6.dp, 6.dp)).background(accent))
            }
        }
        androidx.compose.foundation.HorizontalScrollbar(androidx.compose.foundation.rememberScrollbarAdapter(hs), Modifier.align(Alignment.BottomStart).padding(start = HeaderW).fillMaxWidth().height(10.dp))
        androidx.compose.foundation.VerticalScrollbar(androidx.compose.foundation.rememberScrollbarAdapter(vs), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(10.dp))
        }
    }
}

@Composable
private fun Tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) = TextButton(onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
    Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(label, fontSize = 12.sp)
}

@Composable
private fun Toggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean = true, onClick: () -> Unit) =
    Icon(icon, label, Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(3.dp).size(15.dp), tint = if (on) Color.White else Cx.Dim)

@Composable
private fun TrackHeader(m: EditorModel, t: Track, modifier: Modifier) {
    Row(modifier.fillMaxWidth().background(Cx.Panel).border(0.5.dp, Cx.Line).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = when (t.kind) { TrackKind.VIDEO -> CxIcons.Video; TrackKind.AUDIO -> if (t.audioRole == AudioRole.SFX) CxIcons.Sfx else CxIcons.Music; TrackKind.TEXT -> CxIcons.Text; TrackKind.SUBTITLE -> CxIcons.Captions; TrackKind.EFFECT -> CxIcons.Effects }
        Icon(icon, null, Modifier.size(15.dp), tint = Cx.Dim)
        Spacer(Modifier.width(4.dp))
        Text(t.name.ifBlank { t.kind.name }, Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (t.kind == TrackKind.AUDIO || t.kind == TrackKind.VIDEO)
            Toggle(if (t.muted) CxIcons.VolumeOff else CxIcons.Volume, "Son", !t.muted) { m.updateTrack(t.id, if (t.muted) "Son activé" else "Piste muette") { it.copy(muted = !it.muted) } }
        if (t.kind != TrackKind.AUDIO)
            Toggle(if (t.hidden) CxIcons.EyeOff else CxIcons.Eye, "Visible", !t.hidden) { m.updateTrack(t.id, if (t.hidden) "Piste visible" else "Piste masquée") { it.copy(hidden = !it.hidden) } }
        Toggle(if (t.locked) CxIcons.Lock else CxIcons.Unlock, "Verrou", t.locked) { m.updateTrack(t.id, if (t.locked) "Piste déverrouillée" else "Piste verrouillée") { it.copy(locked = !it.locked) } }
        if (t.clips.isEmpty()) Toggle(CxIcons.Close, "Supprimer la piste", false) { m.removeTrack(t.id) }
    }
}

@Composable
private fun Ruler(p: Project, pps: Float, modifier: Modifier, onSeek: (Long) -> Unit) {
    val density = LocalDensity.current
    val skFont = remember { org.jetbrains.skia.Font(org.jetbrains.skia.FontMgr.default.matchFamilyStyle(null, org.jetbrains.skia.FontStyle.NORMAL) ?: org.jetbrains.skia.Typeface.makeEmpty(), 11f) }
    val paint = remember { org.jetbrains.skia.Paint().apply { color = 0xFF9AA0AC.toInt() } }
    fun us(x: Float) = (x / density.density / pps * 1e6).toLong().coerceAtLeast(0)
    Canvas(modifier.background(Cx.Panel)
        .pointerInput(pps) { detectTapGestures { onSeek(us(it.x)) } }
        .pointerInput(pps) { detectDragGestures { ch, _ -> ch.consume(); onSeek(us(ch.position.x)) } }) {
        // tick spacing adapted to the zoom
        val stepSec = listOf(0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0).first { it * pps >= 60 }
        val stepPx = (stepSec * pps * density.density).toFloat()
        var i = 0
        while (i * stepPx < size.width) {
            val x = i * stepPx
            drawLine(Color(0xFF4A4D57), Offset(x, size.height * 0.45f), Offset(x, size.height), 1f)
            val sub = stepPx / 4
            for (k in 1..3) drawLine(Color(0xFF2E3038), Offset(x + sub * k, size.height * 0.75f), Offset(x + sub * k, size.height), 1f)
            val t = i * stepSec
            val label = if (stepSec < 1) "%d:%02d.%d".format((t / 60).toInt(), (t % 60).toInt(), ((t * 10) % 10).roundToInt() % 10) else "%d:%02d".format((t / 60).toInt(), (t % 60).toInt())
            drawIntoCanvas { it.nativeCanvas.drawString(label, x + 3, size.height * 0.42f, skFont, paint) }
            i++
        }
    }
}

@Composable
private fun ClipBox(m: EditorModel, p: Project, t: Track, tracks: List<Track>, c: Clip, pps: Float, selected: Boolean) {
    val density = LocalDensity.current
    var dx by remember(c.startUs, c.durationUs) { mutableStateOf(0f) }   // dp
    var dy by remember(c.startUs, c.durationUs) { mutableStateOf(0f) }
    var trimL by remember(c.startUs, c.durationUs) { mutableStateOf(0f) }
    var trimR by remember(c.startUs, c.durationUs) { mutableStateOf(0f) }
    val start = (c.startUs / 1e6 * pps).toFloat()
    val width = (c.durationUs / 1e6 * pps).toFloat()
    val color = clipColor(t, c)
    val accent = LocalAccent.current
    val dimmed = !selected && m.selection.collectAsState().value.isNotEmpty()
    val rowH = with(density) { rowHeight(t).toPx() }
    fun toUs(dp: Float) = (dp / pps * 1e6).toLong()
    Box(Modifier
        .offset(x = (start + dx + trimL).dp, y = dy.dp)
        .width((width - trimL + trimR).coerceAtLeast(10f).dp)
        .fillMaxHeight()
        .padding(vertical = if (selected) 1.dp else 3.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(when { !c.enabled -> color.copy(alpha = 0.35f); selected -> color; dimmed -> color.copy(alpha = 0.55f); else -> color.copy(alpha = 0.85f) })
        .border(if (selected) 3.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(6.dp))
        // selected as soon as the button is pressed (no need to aim for a clean click)
        .pointerInput(c.id) { detectTapGestures(onPress = { m.select(c.id, add = ctrlHeld) }, onDoubleTap = { m.select(c.id); m.player.seek(c.startUs) }) }
        .pointerInput(c.id, c.startUs, pps) {
            detectDragGestures(
                onDragStart = { if (c.id !in m.selection.value) m.select(c.id) },
                onDragEnd = {
                    val rows = (dy * density.density / rowH).roundToInt()
                    val idx = tracks.indexOfFirst { it.id == t.id } + rows
                    val target = tracks.getOrNull(idx)?.takeIf { it.kind == t.kind && it.id != t.id }?.id
                    var newStart = c.startUs + toUs(dx)
                    // magnet: snap to the playhead and to clip edges (within 8 dp)
                    val snap = (8f / pps * 1e6).toLong()
                    val edges = listOf(m.playhead, 0L) + p.allClips.filter { it.id != c.id }.flatMap { listOf(it.startUs, it.endUs) }
                    edges.minByOrNull { kotlin.math.abs(it - newStart) }?.let { if (kotlin.math.abs(it - newStart) < snap) newStart = it }
                    edges.minByOrNull { kotlin.math.abs(it - (newStart + c.durationUs)) }?.let { e -> if (kotlin.math.abs(e - (newStart + c.durationUs)) < snap) newStart = e - c.durationUs }
                    if (dx != 0f || target != null) m.moveClip(c.id, newStart, target)
                    dx = 0f; dy = 0f
                },
                onDragCancel = { dx = 0f; dy = 0f },
            ) { ch, d -> ch.consume(); dx += d.x / density.density; dy += d.y / density.density }
        }
    ) {
        if (selected) Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(3.dp).background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            val label = c.text?.text?.lines()?.firstOrNull() ?: c.name.ifBlank { p.asset(c.assetId)?.displayName ?: "" }
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // small badges: speed, effects, animation, voice, missing media
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val tint = Color.White.copy(alpha = 0.85f)
                if (c.speed != 1.0 || c.speedCurve.isNotEmpty()) Text("×%.2g".format(c.speed), color = tint, fontSize = 10.sp)
                if (c.effects.isNotEmpty()) { Icon(CxIcons.Effects, null, Modifier.size(11.dp), tint = tint); Text("${c.effects.size}", color = tint, fontSize = 10.sp) }
                if (!c.motion.isIdentity) Icon(CxIcons.Animation, null, Modifier.size(11.dp), tint = tint)
                if (c.audio.effects.isNotEmpty()) Icon(CxIcons.Voice, null, Modifier.size(11.dp), tint = tint)
                if (p.asset(c.assetId)?.missing == true) { Icon(CxIcons.Warning, null, Modifier.size(11.dp), tint = Color(0xFFFFB020)); Text("média introuvable", color = tint, fontSize = 10.sp, maxLines = 1) }
            }
        }
        // keyframes (diamonds), click = jump there
        c.keyframes.filterKeys { !it.startsWith("fx:") }.values.flatMap { k -> k.keyframes.map { it.timeUs } }.distinct().forEach { kt ->
            val kx = (kt / 1e6 * pps).toFloat() - trimL
            Box(Modifier.align(Alignment.BottomStart).offset(x = (kx - 5f).dp, y = (-4).dp).size(10.dp).graphicsLayer { rotationZ = 45f }
                .background(LocalAccent.current).border(1.dp, Color.Black).clickable { m.select(c.id); m.player.seek(c.startUs + kt) })
        }
        c.transitionIn?.let { tr ->
            Box(Modifier.align(Alignment.CenterStart).width((tr.durationUs / 1e6 * pps).toFloat().coerceAtLeast(6f).dp).fillMaxHeight().background(Color.White.copy(alpha = 0.22f)))
        }
        // trim handles
        Box(Modifier.align(Alignment.CenterStart).width(7.dp).fillMaxHeight().background(Color.White.copy(alpha = if (selected) 0.5f else 0.15f))
            .pointerInput(c.id, c.startUs, pps) {
                detectDragGestures(onDragStart = { m.select(c.id) }, onDragEnd = { if (trimL != 0f) m.trimStart(c.id, c.startUs + toUs(trimL)); trimL = 0f }, onDragCancel = { trimL = 0f }) { ch, d ->
                    ch.consume(); trimL = (trimL + d.x / density.density).coerceAtMost(width - 6f)
                }
            })
        Box(Modifier.align(Alignment.CenterEnd).width(7.dp).fillMaxHeight().background(Color.White.copy(alpha = if (selected) 0.5f else 0.15f))
            .pointerInput(c.id, c.startUs, c.durationUs, pps) {
                detectDragGestures(onDragStart = { m.select(c.id) }, onDragEnd = { if (trimR != 0f) m.trimEnd(c.id, c.endUs + toUs(trimR)); trimR = 0f }, onDragCancel = { trimR = 0f }) { ch, d ->
                    ch.consume(); trimR = (trimR + d.x / density.density).coerceAtLeast(-(width - 6f))
                }
            })
    }
}
