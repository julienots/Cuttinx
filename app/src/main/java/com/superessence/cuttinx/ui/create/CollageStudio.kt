package com.superessence.cuttinx.ui.create

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.create.CollageStyle
import com.superessence.cuttinx.core.create.CreateKit
import com.superessence.cuttinx.core.create.PhotoFrame
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.design.cxChip
import com.superessence.cuttinx.ui.components.ValueSlider
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CollageMode(val label: String) { COLLAGE("Collage"), CAROUSEL("Carrousel Instagram") }

/** Drawing of the collages and carousels (the layout itself comes from [CreateKit]). */
object CollageRender {
    fun collage(photos: List<Bitmap>, w: Int, h: Int, style: CollageStyle, gap: Int, radius: Float, bg: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(bg)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        CreateKit.collage(photos.size, w, h, gap, style).forEachIndexed { i, cell ->
            val b = photos[i]
            val src = CreateKit.cover(b.width, b.height, cell.w, cell.h)
            val dst = RectF(cell.x.toFloat(), cell.y.toFloat(), (cell.x + cell.w).toFloat(), (cell.y + cell.h).toFloat())
            c.save()
            if (radius > 0) c.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
            c.drawBitmap(b, Rect(src.x, src.y, src.x + src.w, src.y + src.h), dst, paint)
            c.restore()
        }
        return out
    }

    /** The carousel slides, each [slideW] px wide. */
    fun carousel(pano: Bitmap, n: Int, frame: PhotoFrame, slideW: Int): List<Bitmap> =
        CreateKit.carousel(pano.width, pano.height, n, frame).map { b ->
            val part = Bitmap.createBitmap(pano, b.x, b.y, b.w, b.h)
            Bitmap.createScaledBitmap(part, slideW, maxOf(1, slideW * b.h / b.w), true)
        }

    /** Preview: the slides side by side with a thin gap, so the cuts are visible. */
    fun carouselPreview(slides: List<Bitmap>, gap: Int): Bitmap {
        val w = slides.sumOf { it.width } + gap * (slides.size - 1); val h = slides.maxOf { it.height }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(0xFF0B0C0F.toInt())
        var x = 0
        for (s in slides) { c.drawBitmap(s, x.toFloat(), 0f, null); x += s.width + gap }
        return out
    }
}

/**
 * Collage & carrousel: up to 9 photos in a grid, a mosaic, a strip or around a big one, or one
 * panorama cut into Instagram slides that join seamlessly when swiped.
 */
@Composable
fun CollageStudio(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cx = LocalCx.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var mode by remember { mutableStateOf(CollageMode.COLLAGE) }
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var photos by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var pano by remember { mutableStateOf<Pair<Uri, Bitmap>?>(null) }
    var style by remember { mutableStateOf(CollageStyle.GRID) }
    var frame by remember { mutableStateOf(PhotoFrame.PORTRAIT) }
    var gap by remember { mutableFloatStateOf(16f) }
    var radius by remember { mutableFloatStateOf(12f) }
    var bg by remember { mutableIntStateOf(0xFF0B0C0F.toInt()) }
    var slides by remember { mutableFloatStateOf(3f) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { list ->
        if (list.isNotEmpty()) scope.launch {
            busy = "Ouverture des photos…"
            val bs = withContext(Dispatchers.IO) { list.mapNotNull { CreateIo.loadBitmap(ctx, it, 1200) } }
            uris = list; photos = bs; mode = CollageMode.COLLAGE; busy = null
        }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) scope.launch {
            busy = "Ouverture de la photo…"
            val b = withContext(Dispatchers.IO) { CreateIo.loadBitmap(ctx, u, 3000) }
            if (b != null) { pano = u to b; mode = CollageMode.CAROUSEL }
            busy = null
        }
    }
    val img = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    LaunchedEffect(mode, photos, pano, style, frame, gap, radius, bg, slides) {
        delay(60)
        preview = withContext(Dispatchers.Default) {
            when (mode) {
                CollageMode.COLLAGE -> if (photos.isEmpty()) null else {
                    val (w, h) = CreateKit.outputSize(1, 1, frame, 900)
                    val k = 900f / 2160f
                    CollageRender.collage(photos, w, h, style, (gap * k).toInt(), radius * k, bg)
                }
                CollageMode.CAROUSEL -> pano?.let { (_, b) -> CollageRender.carouselPreview(CollageRender.carousel(b, slides.toInt(), frame, 360), 8) }
            }
        }?.asImageBitmap()
    }

    fun export() = scope.launch {
        busy = "Enregistrement…"
        val n = withContext(Dispatchers.Default) {
            when (mode) {
                CollageMode.COLLAGE -> {
                    val big = uris.mapNotNull { CreateIo.loadBitmap(ctx, it, 2160) }
                    val (w, h) = CreateKit.outputSize(1, 1, frame, 2160)
                    if (big.isEmpty()) 0 else if (CreateIo.saveJpeg(ctx, CollageRender.collage(big, w, h, style, gap.toInt(), radius, bg), "CUTTINX_collage_${CreateIo.stamp()}") != null) 1 else 0
                }
                CollageMode.CAROUSEL -> pano?.let { (_, b) ->
                    val stamp = CreateIo.stamp()
                    CollageRender.carousel(b, slides.toInt(), frame, 1080).withIndex().count { (i, s) -> CreateIo.saveJpeg(ctx, s, "CUTTINX_carrousel_${stamp}_${i + 1}") != null }
                } ?: 0
            }
        }
        busy = null
        snack.showSnackbar(if (n > 0) "$n image(s) enregistrée(s) dans la galerie (dossier Cuttinx)" else "Enregistrement impossible")
    }

    val ready = (mode == CollageMode.COLLAGE && photos.isNotEmpty()) || (mode == CollageMode.CAROUSEL && pano != null)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        topBar = { StudioBar("Collage", "NOUVEAU", onBack, if (ready) "Exporter" else null, busy == null) { export() } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in CollageMode.entries) Text(m.label, Modifier.cxChip(m == mode, cx.accent) { mode = m }, color = if (m == mode) cx.accent else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                }
                Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                    val p = preview
                    if (p != null && ready) Image(p, null, Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
                    else if (mode == CollageMode.COLLAGE) StudioEmpty("Collage de photos", "2 à 9 photos en grille, mosaïque, bande ou autour d'une grande photo.", "Choisir les photos") { pickMany.launch(img) }
                    else StudioEmpty("Carrousel Instagram", "Une photo large découpée en 2 à 10 images qui se suivent sans raccord quand on fait défiler.", "Choisir une photo") { pickOne.launch(img) }
                }
                if (ready) Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).background(cx.panel).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val frames = if (mode == CollageMode.COLLAGE) listOf(PhotoFrame.SQUARE, PhotoFrame.PORTRAIT, PhotoFrame.STORY, PhotoFrame.WIDE) else listOf(PhotoFrame.PORTRAIT, PhotoFrame.SQUARE)
                        for (f in frames) Text(f.label, Modifier.cxChip(f == frame, cx.accent) { frame = f }, fontSize = 12.sp, color = if (f == frame) cx.accent else MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (mode == CollageMode.COLLAGE) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (s in CollageStyle.entries) Text(s.label, Modifier.cxChip(s == style, cx.accent) { style = s }, fontSize = 12.sp, color = if (s == style) cx.accent else MaterialTheme.colorScheme.onSurface)
                        }
                        ValueSlider("Espacement", gap, 0f..80f, { gap = it }, unit = " px", decimals = 0, step = 2f)
                        ValueSlider("Coins arrondis", radius, 0f..80f, { radius = it }, unit = " px", decimals = 0, step = 2f)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Fond", color = cx.dim, fontSize = 12.sp)
                            for (col in listOf(0xFF0B0C0F, 0xFFFFFFFF, 0xFFF4EDE1, 0xFFFFCC1F, 0xFFFF4FA3, 0xFF3D8BFF)) {
                                Box(Modifier.size(28.dp).clip(CxDesign.Chip).background(Color(col.toInt())).border(2.dp, if (bg == col.toInt()) cx.accent else cx.line, CxDesign.Chip).clickable { bg = col.toInt() })
                            }
                        }
                        TextButton({ pickMany.launch(img) }) { Text("Changer les photos (${photos.size})") }
                    } else {
                        ValueSlider("Nombre d'images", slides, 2f..10f, { slides = it }, decimals = 0, step = 1f)
                        Text("Publie les images dans l'ordre (1, 2, 3…) dans un même post : elles se suivent sans raccord.", color = cx.dim, fontSize = 12.sp)
                        TextButton({ pickOne.launch(img) }) { Text("Changer la photo") }
                    }
                }
            }
            busy?.let { BusyVeil(it) }
        }
    }
}
