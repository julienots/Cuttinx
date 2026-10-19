@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.superessence.cuttinx.twitch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.icons.CxIcons
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What is selected in the Twitch preview: a built-in element of the scene or a free element. */
sealed interface Sel {
    data class Zone(val kind: ZoneKind) : Sel
    data class Item(val id: String) : Sel
}

/** Zoom / pan of the preview and the alignment guides shown while dragging. */
class StageState {
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    /** Snap guides: vertical centre line, horizontal centre line. */
    var guideV by mutableStateOf(false)
    var guideH by mutableStateOf(false)
    internal var viewW = 0f
    internal var viewH = 0f
    internal var baseW = 0f
    internal var baseH = 0f

    fun reset() { zoom = 1f; panX = 0f; panY = 0f }

    /** Zooms by [factor] keeping the point [focus] (relative to the view centre, null = centre) still. */
    fun zoomBy(factor: Float, focus: Offset? = null) {
        val z0 = zoom
        val z1 = (z0 * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z1 == z0) return
        val f = focus ?: Offset.Zero
        panX = f.x - (f.x - panX) * z1 / z0
        panY = f.y - (f.y - panY) * z1 / z0
        zoom = z1
        clamp()
    }

    fun setZoom(z: Float) = zoomBy(z / zoom)

    fun panBy(dx: Float, dy: Float) { panX += dx; panY += dy; clamp() }

    internal fun clamp() {
        val mx = max(0f, (baseW * zoom - viewW) / 2); val my = max(0f, (baseH * zoom - viewH) / 2)
        panX = panX.coerceIn(-mx, mx); panY = panY.coerceIn(-my, my)
    }

    companion object { const val MIN_ZOOM = 1f; const val MAX_ZOOM = 8f }
}

/**
 * Zoomable, pannable stage of the preview. [content] gets a modifier that sizes it at the zoomed
 * size: it keeps its own coordinates, so clicks land exactly on what is drawn at any zoom.
 * Mouse wheel = zoom around the cursor; two fingers = zoom / move the view (when no element is
 * being pinched); dragging an empty area of a zoomed preview moves the view.
 */
@Composable
fun ZoomStage(state: StageState, ratio: Float, modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds()
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type != PointerEventType.Scroll) continue
                    val ch = ev.changes.firstOrNull() ?: continue
                    val d = ch.scrollDelta.y
                    if (d == 0f || ch.isConsumed) continue
                    state.zoomBy(if (d < 0) 1.18f else 1f / 1.18f, Offset(ch.position.x - size.width / 2f, ch.position.y - size.height / 2f))
                    ev.changes.forEach { it.consume() }
                }
            }
        }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.isConsumed) return@awaitEachGesture
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty() || ev.changes.any { it.isConsumed }) break
                    if (pressed.size >= 2) {
                        val z = ev.calculateZoom(); val pan = ev.calculatePan(); val c = ev.calculateCentroid()
                        if (z != 1f) state.zoomBy(z, Offset(c.x - size.width / 2f, c.y - size.height / 2f))
                        state.panBy(pan.x, pan.y)
                        ev.changes.forEach { it.consume() }
                        moved = true
                    } else if (state.zoom > 1.01f) {
                        val ch = pressed.first()
                        if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) { val d = ch.positionChange(); state.panBy(d.x, d.y); ch.consume() }
                    }
                }
            }
        },
        contentAlignment = Alignment.Center) {
        val vw = constraints.maxWidth.toFloat()
        val vh = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else vw / ratio
        val bw = if (vh > 0 && vw / vh > ratio) vh * ratio else vw
        val bh = bw / ratio
        state.viewW = vw; state.viewH = vh; state.baseW = bw; state.baseH = bh
        val zw = with(density) { (bw * state.zoom).toDp() }; val zh = with(density) { (bh * state.zoom).toDp() }
        content(Modifier.offset { IntOffset(state.panX.roundToInt(), state.panY.roundToInt()) }.requiredSize(zw, zh))
    }
}

/** − / zoom % / + / fit, with a slider: zoom up to 800 % without needing a pinch or the wheel. */
@Composable
fun ZoomBar(state: StageState, accent: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        // phones: icons only, the slider keeps room
        val compact = maxWidth < 420.dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ state.zoomBy(1 / 1.4f) }, enabled = state.zoom > StageState.MIN_ZOOM) { Icon(CxIcons.ZoomOut, "Dézoomer") }
            Slider(state.zoom, { state.setZoom(it) }, Modifier.weight(1f), valueRange = StageState.MIN_ZOOM..StageState.MAX_ZOOM,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
            IconButton({ state.zoomBy(1.4f) }, enabled = state.zoom < StageState.MAX_ZOOM) { Icon(CxIcons.ZoomIn, "Zoomer") }
            Text("${(state.zoom * 100).roundToInt()} %", Modifier.widthIn(min = 40.dp), fontSize = 12.sp)
            if (compact) IconButton({ state.reset() }) { Icon(CxIcons.Fit, "Ajuster") }
            else {
                TextButton({ state.reset() }) { Icon(CxIcons.Fit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Ajuster", fontSize = 12.sp) }
                TextButton({ state.setZoom(StageState.MAX_ZOOM) }) { Text("Max", fontSize = 12.sp) }
            }
        }
    }
}

private enum class Mode { NONE, MOVE_ITEM, SIZE_ITEM, TURN_ITEM, MOVE_ZONE, SIZE_ZONE }

/** Canvas of the scene being edited (the vertical live is a 9:16 frame centred in the preview). */
private fun canvasOf(cfg: OverlayConfig, scene: SceneKind) = twitchLayoutOf(cfg, scene)

/**
 * Direct editing on the preview, PC (mouse) and phones / tablets (touch):
 * click / tap = select, drag = move (follows the pointer exactly, from where it was grabbed),
 * corner handles = resize, round handle = rotate, two fingers on the selection = resize + rotate.
 * Elements snap to the centre lines. [cfg] must read the latest settings (not a recomposed copy),
 * so fast mouse moves are never lost.
 */
fun Modifier.overlayEditing(
    cfg: () -> OverlayConfig,
    scene: SceneKind?,
    selection: () -> Sel?,
    onSelect: (Sel?) -> Unit,
    stage: StageState,
    onChange: (OverlayConfig, String) -> Unit,
    onEnd: () -> Unit,
): Modifier = if (scene == null) this else this.pointerInput(scene) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val c0 = cfg()
        val (lc, ox) = canvasOf(c0, scene)
        val kx = c0.width / size.width.toFloat(); val ky = c0.height / size.height.toFloat()
        fun canvas(o: Offset) = Offset(o.x * kx - ox, o.y * ky)
        val p0 = canvas(down.position)
        val cw = lc.width.toFloat(); val ch = lc.height.toFloat()
        val tol = 18.dp.toPx() * kx
        val sel0 = selection()
        var mode = Mode.NONE
        var target: Sel? = null
        // 1. handles of the current selection
        val item0 = (sel0 as? Sel.Item)?.let { c0.item(it.id) }?.takeIf { !it.locked && !it.hidden && (it.scene == null || it.scene == scene) }
        if (item0 != null) {
            val b = OverlayItems.box(item0, cw, ch)
            val (rx, ry) = OverlayItems.rotateHandle(b, 34.dp.toPx() * kx)
            if (hypot(p0.x - rx, p0.y - ry) < tol) mode = Mode.TURN_ITEM
            else if (listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).any { (sx, sy) -> OverlayItems.corner(b, sx, sy).let { (x, y) -> hypot(p0.x - x, p0.y - y) < tol } }) mode = Mode.SIZE_ITEM
            if (mode != Mode.NONE) target = sel0
        }
        val zoneSel = (sel0 as? Sel.Zone)?.takeIf { scene.movable() }?.let { s -> OverlayLayout.zone(scene, lc, s.kind) }
        if (mode == Mode.NONE && zoneSel != null) {
            val near = listOf(zoneSel.x to zoneSel.y, zoneSel.right to zoneSel.y, zoneSel.right to zoneSel.bottom, zoneSel.x to zoneSel.bottom).any { (x, y) -> hypot(p0.x - x, p0.y - y) < tol }
            if (near) { mode = Mode.SIZE_ZONE; target = sel0 }
        }
        // 2. what is under the pointer: free elements first (they are on top), then the built-in ones
        if (mode == Mode.NONE) {
            OverlayItems.hit(c0, scene, p0.x, p0.y, cw, ch, 4.dp.toPx() * kx)?.let { mode = Mode.MOVE_ITEM; target = Sel.Item(it.id) }
        }
        if (mode == Mode.NONE && scene.movable()) {
            OverlayLayout.hit(scene, lc, p0.x, p0.y)?.let { mode = Mode.MOVE_ZONE; target = Sel.Zone(it) }
        }
        if (target != null && target != sel0) onSelect(target)
        val start = target?.let { t -> (t as? Sel.Item)?.let { c0.item(it.id) } }
        var dragging = false
        var pinched = false
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (mode == Mode.NONE) {
                if (pressed.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) dragging = true
                continue
            }
            // two fingers on the selected element: size (and rotation for free elements)
            if (pressed.size >= 2) {
                pinched = true; dragging = true
                val z = ev.calculateZoom(); val r = ev.calculateRotation()
                val cur = cfg()
                when (val t = target) {
                    is Sel.Item -> cur.item(t.id)?.let { it0 -> onChange(cur.updateItem(t.id) { OverlayItems.resize(it, it.w * z).copy(rotation = it.rotation + r) }, "pinch:${it0.id}") }
                    is Sel.Zone -> { val (l, _) = canvasOf(cur, scene); onChange(cur.copy(zoneMoves = OverlayLayout.move(l, scene, t.kind, 0f, 0f, z).zoneMoves), "pinch:${t.kind}") }
                    null -> {}
                }
                ev.changes.forEach { it.consume() }
                continue
            }
            if (pinched) { ev.changes.forEach { it.consume() }; continue }
            val pc = pressed.first()
            if (!dragging && (pc.position - down.position).getDistance() > viewConfiguration.touchSlop / 2) dragging = true
            if (!dragging) continue
            pc.consume()
            // always computed from the state at the start of the drag: no drift, no lost movement
            val p = canvas(pc.position)
            val d = p - p0
            when (mode) {
                Mode.MOVE_ITEM -> {
                    val it0 = start ?: continue
                    var nx = it0.x + d.x / cw; var ny = it0.y + d.y / ch
                    val snap = 10.dp.toPx() * kx
                    stage.guideV = abs(nx * cw - cw / 2) < snap; stage.guideH = abs(ny * ch - ch / 2) < snap
                    if (stage.guideV) nx = 0.5f
                    if (stage.guideH) ny = 0.5f
                    onChange(c0.updateItem(it0.id) { OverlayItems.place(it, nx, ny) }, "move:${it0.id}")
                }
                Mode.SIZE_ITEM -> {
                    val it0 = start ?: continue
                    val b = OverlayItems.box(it0, cw, ch)
                    val k = hypot(p.x - b.cx, p.y - b.cy) / max(1f, hypot(p0.x - b.cx, p0.y - b.cy))
                    onChange(c0.updateItem(it0.id) { OverlayItems.resize(it, it0.w * k) }, "size:${it0.id}")
                }
                Mode.TURN_ITEM -> {
                    val it0 = start ?: continue
                    val b = OverlayItems.box(it0, cw, ch)
                    val a = Math.toDegrees((atan2(p.y - b.cy, p.x - b.cx) - atan2(p0.y - b.cy, p0.x - b.cx)).toDouble()).toFloat()
                    var rot = ((it0.rotation + a + 540f) % 360f) - 180f
                    // magnetic angles: 0°, ±45°, ±90°, 180°
                    val near = (rot / 45f).roundToInt() * 45f
                    if (abs(rot - near) < 4f) rot = near
                    onChange(c0.updateItem(it0.id) { it.copy(rotation = rot) }, "turn:${it0.id}")
                }
                Mode.MOVE_ZONE -> {
                    val k = (target as Sel.Zone).kind
                    onChange(c0.copy(zoneMoves = OverlayLayout.move(lc, scene, k, d.x, d.y).zoneMoves), "zone:$k")
                }
                Mode.SIZE_ZONE -> {
                    val k = (target as Sel.Zone).kind
                    val z = zoneSel ?: continue
                    val cx = z.x + z.w / 2; val cy = z.y + z.h / 2
                    val f = hypot(p.x - cx, p.y - cy) / max(1f, hypot(p0.x - cx, p0.y - cy))
                    onChange(c0.copy(zoneMoves = OverlayLayout.move(lc, scene, k, 0f, 0f, f).zoneMoves), "zsize:$k")
                }
                Mode.NONE -> {}
            }
        }
        stage.guideV = false; stage.guideH = false
        // simple click / tap on an empty area: nothing selected anymore
        if (!dragging && mode == Mode.NONE) onSelect(null)
        onEnd()
    }
}

/** Outline, resize corners, rotation handle and snap guides of the selection, drawn over the preview. */
@Composable
fun SelectionOverlay(cfg: OverlayConfig, scene: SceneKind?, sel: Sel?, stage: StageState, accent: Color, modifier: Modifier) {
    if (scene == null) return
    Canvas(modifier) {
        val (lc, ox) = canvasOf(cfg, scene)
        val k = size.width / cfg.width
        fun v(x: Float, y: Float) = Offset((x + ox) * k, y * k)
        val stroke = 2.dp.toPx(); val handle = 6.dp.toPx()
        val cw = lc.width.toFloat(); val ch = lc.height.toFloat()
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        if (stage.guideV) drawLine(Color(0xFFFF4FA3), v(cw / 2, 0f), v(cw / 2, ch), stroke, pathEffect = dash)
        if (stage.guideH) drawLine(Color(0xFFFF4FA3), v(0f, ch / 2), v(cw, ch / 2), stroke, pathEffect = dash)
        fun knob(o: Offset) { drawCircle(Color.White, handle, o); drawCircle(accent, handle, o, style = Stroke(stroke)) }
        when (sel) {
            is Sel.Item -> {
                val item = cfg.item(sel.id)?.takeIf { x -> x.scene == null || x.scene == scene } ?: return@Canvas
                val b = OverlayItems.box(item, cw, ch)
                val corners = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (sx, sy) -> OverlayItems.corner(b, sx, sy).let { (x, y) -> v(x, y) } }
                for (i in 0 until 4) drawLine(accent, corners[i], corners[(i + 1) % 4], stroke)
                if (!item.locked) {
                    corners.forEach { o -> knob(o) }
                    val top = OverlayItems.corner(b, 0, -1).let { (x, y) -> v(x, y) }
                    val rot = OverlayItems.rotateHandle(b, 34.dp.toPx() / k).let { (x, y) -> v(x, y) }
                    drawLine(accent, top, rot, stroke)
                    drawCircle(accent, handle * 1.1f, rot)
                    drawCircle(Color.White, handle * 0.45f, rot)
                }
            }
            is Sel.Zone -> {
                val z = OverlayLayout.zone(scene, lc, sel.kind) ?: return@Canvas
                val a = v(z.x, z.y); val b = v(z.right, z.bottom)
                drawRect(accent, a, androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y), style = Stroke(stroke))
                if (!cfg.isHidden(scene, sel.kind)) listOf(a, Offset(b.x, a.y), b, Offset(a.x, b.y)).forEach { o -> knob(o) }
            }
            null -> {}
        }
    }
}
