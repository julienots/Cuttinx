package com.superessence.cuttinx.ui.editor

import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.render.FrameEvaluator
import com.superessence.cuttinx.engine.CompositionFactory
import com.superessence.cuttinx.engine.TextRenderer
import com.superessence.cuttinx.ui.theme.Cx
import kotlin.math.max
import kotlin.math.min

/** Video preview with direct manipulation of the selected layer (move, pinch-scale, rotate). */
@UnstableApi
@Composable
fun PreviewPane(vm: EditorViewModel, project: Project, positionUs: Long, selected: Clip?, buffering: Boolean, error: String?, modifier: Modifier = Modifier, notice: String? = null) {
    val ctx = LocalContext.current
    val surface = remember { SurfaceView(ctx) }
    DisposableEffect(surface) { vm.preview.attach(surface); onDispose { vm.preview.detach(surface) } }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val ratio = project.format.width.toFloat() / project.format.height
    Box(modifier.background(Color(0xFF050505)).onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = box.width.toFloat() / max(1, box.height) > ratio)) {
            AndroidView({ surface }, Modifier.fillMaxSize())
            val guides by vm.guides.collectAsState()
            if (guides != PreviewGuides.NONE) GuidesOverlay(guides)
            if (selected != null && (selected.kind != ClipKind.ADJUSTMENT) && project.track(project.findClip(selected.id)?.first?.id ?: "")?.kind != TrackKind.AUDIO) {
                Gizmo(vm, project, selected, positionUs)
            } else {
                // tap on the picture selects the top-most visual layer under the finger
                Box(Modifier.fillMaxSize().pointerInput(project, positionUs) {
                    detectTapGestures { o -> pickLayer(project, positionUs, o.x / size.width, o.y / size.height)?.let { vm.select(it) } }
                })
            }
            if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Cx.Yellow, strokeWidth = 2.dp)
        }
        val banner = error ?: notice
        if (banner != null) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background((if (error != null) Cx.Danger else Color(0xFF2A2A2A)).copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(banner, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.preview.retry() }) { Text(if (error != null) "Réessayer" else "Réactiver", color = Cx.Yellow) }
        }
    }
}

/** Canvas-space rectangle (centre, size, rotation) of a clip, in pixels of the project canvas. */
@UnstableApi
fun layerGeometry(p: Project, c: Clip, rel: Long): FloatArray? {
    val W = p.format.width; val H = p.format.height
    val lt = FrameEvaluator.transform(c, rel)
    return if (c.text != null) {
        val tf = TextRenderer.typeface(c.text!!.style)
        val key = c.text.hashCode() * 31 + W
        val (bw, bh) = textSizes.getOrPut(key) { val b = TextRenderer.render(c.text!!, W, H, 1.0, tf); (b.width to b.height).also { b.recycle() } }
        floatArrayOf((lt.posX * W).toFloat(), (lt.posY * H).toFloat(), (bw * lt.scaleX).toFloat(), (bh * lt.scaleY).toFloat(), lt.rotation.toFloat())
    } else {
        val (iw, ih) = CompositionFactory.itemSizeStatic(p, c, W, H)
        val pl = FrameEvaluator.placement(iw, ih, W, H, c.transform, lt)
        floatArrayOf(pl.centerX.toFloat(), pl.centerY.toFloat(), (iw * kotlin.math.abs(pl.scaleX)).toFloat(), (ih * kotlin.math.abs(pl.scaleY)).toFloat(), pl.rotation.toFloat())
    }
}

private val textSizes = HashMap<Int, Pair<Int, Int>>()

@UnstableApi
private fun pickLayer(p: Project, t: Long, nx: Float, ny: Float): String? {
    val layers = FrameEvaluator.visualLayers(p, t).reversed()
    for (l in layers) {
        if (l.clip.kind == ClipKind.ADJUSTMENT) continue
        val g = layerGeometry(p, l.clip, l.localUs) ?: continue
        val x = nx * p.format.width; val y = ny * p.format.height
        // inverse rotate
        val a = Math.toRadians(-g[4].toDouble())
        val dx = x - g[0]; val dy = y - g[1]
        val rx = dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a); val ry = dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a)
        if (kotlin.math.abs(rx) <= g[2] / 2 && kotlin.math.abs(ry) <= g[3] / 2) return l.clip.id
    }
    return null
}

@UnstableApi
@Composable
private fun Gizmo(vm: EditorViewModel, p: Project, c: Clip, positionUs: Long) {
    val rel = (positionUs - c.startUs).coerceIn(0, c.durationUs)
    val active = c.contains(positionUs)
    val g = remember(c, rel, p.format) { layerGeometry(p, c, rel) } ?: return
    val latestClip by rememberUpdatedState(c)
    Canvas(Modifier.fillMaxSize().pointerInput(c.id) {
        detectTransformGestures { centroid, pan, zoom, rotation ->
            val cl = latestClip
            val r2 = (vm.position - cl.startUs).coerceIn(0, cl.durationUs)
            val lt = FrameEvaluator.transform(cl, r2, withMotion = false)
            val nx = (lt.posX + pan.x / size.width).coerceIn(-0.5, 1.5)
            val ny = (lt.posY + pan.y / size.height).coerceIn(-0.5, 1.5)
            if (pan != Offset.Zero) {
                vm.setAnimated(Prop.POS_X, nx) { x, v -> x.copy(transform = x.transform.copy(posX = v)) }
                vm.setAnimated(Prop.POS_Y, ny) { x, v -> x.copy(transform = x.transform.copy(posY = v)) }
            }
            if (zoom != 1f) {
                val baseScale = if (cl.keyframes[Prop.SCALE]?.isEmpty == false) com.superessence.cuttinx.core.anim.KeyframeEvaluator.evaluate(cl.keyframes[Prop.SCALE], r2, 1.0) else cl.transform.scaleX
                val ns = (baseScale * zoom).coerceIn(0.05, 10.0)
                if (cl.keyframes[Prop.SCALE]?.isEmpty == false) vm.setAnimated(Prop.SCALE, ns) { x, _ -> x }
                else vm.updateClip("Échelle", "gscale_${cl.id}", cl.id) { x -> val k = ns / x.transform.scaleX; x.copy(transform = x.transform.copy(scaleX = ns, scaleY = x.transform.scaleY * k)) }
            }
            if (rotation != 0f) vm.setAnimated(Prop.ROTATION, lt.rotation + rotation) { x, v -> x.copy(transform = x.transform.copy(rotation = v)) }
        }
    }.pointerInput(p, positionUs) {
        detectTapGestures { o -> vm.select(pickLayer(p, positionUs, o.x / size.width, o.y / size.height)) }
    }) {
        val sx = size.width / p.format.width; val sy = size.height / p.format.height
        val cx = g[0] * sx; val cy = g[1] * sy; val w = g[2] * sx; val h = g[3] * sy
        rotate(g[4], Offset(cx, cy)) {
            val col = if (active) Cx.Yellow else Color.White.copy(alpha = 0.5f)
            drawRect(col, Offset(cx - w / 2, cy - h / 2), androidx.compose.ui.geometry.Size(w, h), style = Stroke(1.5.dp.toPx(), pathEffect = if (active) null else PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
            val k = 6.dp.toPx()
            for ((px, py) in listOf(cx - w / 2 to cy - h / 2, cx + w / 2 to cy - h / 2, cx - w / 2 to cy + h / 2, cx + w / 2 to cy + h / 2)) drawCircle(col, k / 2, Offset(px, py))
        }
        // safe-area guides (centre lines) when near centre
        if (kotlin.math.abs(cx - size.width / 2) < 3) drawLine(Cx.Yellow.copy(alpha = 0.6f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1f)
        if (kotlin.math.abs(cy - size.height / 2) < 3) drawLine(Cx.Yellow.copy(alpha = 0.6f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
    }
}

/** Platform safe zones / grids drawn above the preview (never exported). */
@Composable
private fun GuidesOverlay(g: PreviewGuides) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val line = Color.White.copy(alpha = 0.55f)
        val zone = Color(0xFFFF3D8B).copy(alpha = 0.22f)
        fun block(l: Float, t: Float, r: Float, b: Float) = drawRect(zone, Offset(l * w, t * h), androidx.compose.ui.geometry.Size((r - l) * w, (b - t) * h))
        when (g) {
            PreviewGuides.NONE -> {}
            PreviewGuides.THIRDS -> for (i in 1..2) {
                drawLine(line, Offset(w * i / 3, 0f), Offset(w * i / 3, h), 1.5f)
                drawLine(line, Offset(0f, h * i / 3), Offset(w, h * i / 3), 1.5f)
            }
            // approximate areas covered by each app's interface (caption, buttons, header)
            PreviewGuides.TIKTOK -> { block(0f, 0f, 1f, 0.08f); block(0f, 0.8f, 1f, 1f); block(0.86f, 0.38f, 1f, 0.8f) }
            PreviewGuides.REELS -> { block(0f, 0f, 1f, 0.07f); block(0f, 0.78f, 1f, 1f); block(0.86f, 0.5f, 1f, 0.78f) }
            PreviewGuides.SHORTS -> { block(0f, 0f, 1f, 0.06f); block(0f, 0.8f, 1f, 1f); block(0.85f, 0.45f, 1f, 0.8f) }
            PreviewGuides.TITLE_SAFE -> drawRect(line, Offset(0.05f * w, 0.05f * h), androidx.compose.ui.geometry.Size(0.9f * w, 0.9f * h), style = Stroke(1.5f))
        }
    }
}
