package com.superessence.cuttinx.ui.create

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.superessence.cuttinx.core.create.GifEncoder
import com.superessence.cuttinx.core.create.GifKit
import com.superessence.cuttinx.core.create.GifMode
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.design.cxChip
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** What the GIF studio makes. */
data class GifJob(
    val startS: Double, val endS: Double, val fps: Int, val maxSide: Int, val speed: Double,
    val mode: GifMode, val dither: Boolean, val text: String,
)

object GifRender {
    /** Grabs the frames of [uri] and writes the GIF to [out]; [progress] gets (done, total). */
    suspend fun render(ctx: Context, uri: Uri, job: GifJob, out: File, progress: (Int, Int) -> Unit) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot % 180 != 0) { val t = w; w = h; h = t }
            val (ow, oh) = GifKit.outputSize(w, h, job.maxSide)
            val times = GifKit.sampleTimes(job.startS, job.endS, job.fps)
            val frames = ArrayList<IntArray>(times.size)
            for ((i, t) in times.withIndex()) {
                coroutineContext.ensureActive()
                val us = (t * 1_000_000).toLong()
                val raw = (if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, ow, oh) else mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST))
                    ?: continue
                val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                val c = ACanvas(bmp)
                c.drawBitmap(raw, null, android.graphics.Rect(0, 0, ow, oh), Paint(Paint.FILTER_BITMAP_FLAG))
                raw.recycle()
                if (job.text.isNotBlank()) drawCaption(c, job.text, ow, oh)
                val px = IntArray(ow * oh); bmp.getPixels(px, 0, ow, 0, 0, ow, oh); bmp.recycle()
                frames += px
                progress(i + 1, times.size * 2)
            }
            if (frames.isEmpty()) error("Aucune image lisible dans cette vidéo")
            val order = GifKit.order(frames.size, job.mode)
            val delay = GifKit.delayCs(job.fps, job.speed)
            out.outputStream().buffered().use { os ->
                val enc = GifEncoder(os, ow, oh, dither = job.dither)
                for ((k, idx) in order.withIndex()) {
                    coroutineContext.ensureActive()
                    enc.addFrame(frames[idx], delay)
                    progress(frames.size + (k + 1) * frames.size / order.size, frames.size * 2)
                }
                enc.finish()
            }
        } finally { runCatching { mmr.release() } }
    }

    /** Meme-style caption: bold white letters with a black outline, at the bottom. */
    private fun drawCaption(c: ACanvas, text: String, w: Int, h: Int) {
        val size = (minOf(w, h) * 0.09f).coerceAtLeast(12f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = size; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        val stroke = Paint(fill).apply { color = android.graphics.Color.BLACK; style = Paint.Style.STROKE; strokeWidth = size * 0.14f; strokeJoin = Paint.Join.ROUND }
        // wrap on words to the width
        val lines = ArrayList<String>(); var line = ""
        for (word in text.trim().uppercase().split(Regex("\\s+"))) {
            val tryLine = if (line.isEmpty()) word else "$line $word"
            if (fill.measureText(tryLine) > w * 0.92f && line.isNotEmpty()) { lines += line; line = word } else line = tryLine
        }
        if (line.isNotEmpty()) lines += line
        var y = h - size * 0.5f - (lines.size - 1) * size * 1.1f
        for (l in lines.takeLast(3)) { c.drawText(l, w / 2f, y, stroke); c.drawText(l, w / 2f, y, fill); y += size * 1.1f }
    }

    fun saveGif(ctx: Context, src: File, name: String): Uri? = runCatching {
        val file = "$name.gif"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file); put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cuttinx"); put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            @Suppress("DEPRECATION") val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Cuttinx").apply { mkdirs() }
            val f = File(dir, file); src.copyTo(f, overwrite = true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf("image/gif"), null)
            Uri.fromFile(f)
        }
    }.getOrNull()

    fun share(ctx: Context, f: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_SEND).setType("image/gif").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Partager le GIF"))
        }
    }
}

/**
 * Studio GIF: a few seconds of a video become an animated GIF — normal, boomerang or reversed,
 * slowed down or sped up, with an optional meme caption — saved in Pictures › Cuttinx or shared.
 */
@Composable
fun GifStudio(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cx = LocalCx.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var durS by remember { mutableStateOf(0.0) }
    var poster by remember { mutableStateOf<Bitmap?>(null) }
    var srcSize by remember { mutableStateOf(0 to 0) }
    var range by remember { mutableStateOf(0f..1f) }
    var fps by remember { mutableIntStateOf(15) }
    var side by remember { mutableIntStateOf(480) }
    var speed by remember { mutableStateOf(1.0) }
    var mode by remember { mutableStateOf(GifMode.BOOMERANG) }
    var dither by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }
    var resultVersion by remember { mutableIntStateOf(0) }
    var work by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) scope.launch {
            busy = "Lecture de la vidéo…"
            val info = withContext(Dispatchers.IO) {
                runCatching {
                    val m = MediaMetadataRetriever()
                    try {
                        m.setDataSource(ctx, u)
                        val d = (m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                        var w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        var h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        if ((m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 180 != 0) { val t = w; w = h; h = t }
                        Triple(d, w to h, m.getFrameAtTime(0))
                    } finally { m.release() }
                }.getOrNull()
            }
            busy = null
            if (info == null || info.first <= 0.0 || info.second.first <= 0) { snack.showSnackbar("Cette vidéo ne peut pas être lue"); return@launch }
            uri = u; durS = info.first; srcSize = info.second; poster = info.third; result = null
            // first 3 s by default (a GIF is short)
            range = 0f..(3.0 / durS).toFloat().coerceIn(0.05f, 1f)
        }
    }
    LaunchedEffect(Unit) { if (uri == null) pick.launch(arrayOf("video/*")) }

    val lenS = (range.endInclusive - range.start) * durS
    val maxLen = 10.0
    val (ow, oh) = GifKit.outputSize(srcSize.first, srcSize.second, side)
    val frameCount = GifKit.order(GifKit.sampleTimes(0.0, lenS, fps).size, mode).size
    val estimate = GifKit.estimateBytes(ow, oh, frameCount, dither)

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        topBar = { StudioBar("Studio GIF", "3.1", onBack, if (result != null) "Enregistrer" else null, busy == null) {
            val f = result ?: return@StudioBar
            scope.launch {
                val saved = withContext(Dispatchers.IO) { GifRender.saveGif(ctx, f, "CUTTINX_gif_${CreateIo.stamp()}") }
                snack.showSnackbar(if (saved != null) "GIF enregistré dans Images › Cuttinx" else "Enregistrement impossible")
            }
        } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (uri == null) StudioEmpty("Fais un GIF", "Quelques secondes de vidéo deviennent un GIF animé : boomerang, ralenti, accéléré, avec ta légende.", "Choisir une vidéo") {
                pick.launch(arrayOf("video/*"))
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                // preview: the animated result once made, else the first image
                Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(CxDesign.Card).background(cx.panel), contentAlignment = Alignment.Center) {
                    val f = result
                    if (f != null && Build.VERSION.SDK_INT >= 28) key(resultVersion) {
                        AndroidView({ c -> ImageView(c).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER } }, Modifier.fillMaxWidth().heightIn(max = 320.dp)) { iv ->
                            if (Build.VERSION.SDK_INT >= 28) runCatching {
                                val d = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(f))
                                iv.setImageDrawable(d); (d as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                            }
                        }
                    } else poster?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit) }
                    if (result != null) Text("%.1f Mo".format((result?.length() ?: 0L) / 1_048_576.0), Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .clip(CxDesign.Chip).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
                        color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("Passage", fontWeight = FontWeight.SemiBold)
                RangeSlider(range, {
                    // keep at most 10 s: move the other end
                    var a = it.start; var b = maxOf(it.endInclusive, it.start + (0.3 / durS).toFloat())
                    val maxF = (maxLen / durS).toFloat()
                    if (b - a > maxF) { if (a != range.start) b = a + maxF else a = b - maxF }
                    range = a.coerceIn(0f, 1f)..b.coerceIn(0f, 1f); result = null
                }, Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = cx.accent, activeTrackColor = cx.accent, inactiveTrackColor = cx.line))
                Text("De %.1f s à %.1f s · %.1f s (10 s max)".format(range.start * durS, range.endInclusive * durS, lenS), color = cx.dim, fontSize = 12.sp)

                Spacer(Modifier.height(12.dp))
                ChipRow("Lecture") { for (m in GifMode.entries) Text(m.label, Modifier.cxChip(m == mode, cx.accent) { mode = m; result = null }, fontSize = 13.sp) }
                ChipRow("Vitesse") { for (v in listOf(0.5, 1.0, 1.5, 2.0, 3.0)) Text(if (v == 1.0) "1×" else "${v.toString().removeSuffix(".0")}×".replace('.', ','), Modifier.cxChip(v == speed, cx.accent) { speed = v; result = null }, fontSize = 13.sp) }
                ChipRow("Fluidité") { for (v in listOf(8, 12, 15, 20, 25)) Text("$v i/s", Modifier.cxChip(v == fps, cx.accent) { fps = v; result = null }, fontSize = 13.sp) }
                ChipRow("Taille") { for (v in listOf(240, 320, 480, 640, 800)) Text("${v}px", Modifier.cxChip(v == side, cx.accent) { side = v; result = null }, fontSize = 13.sp) }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(text, { text = it.take(80); result = null }, Modifier.fillMaxWidth(), label = { Text("Légende (facultatif)") }, singleLine = true)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dégradés lissés", fontSize = 15.sp)
                        Text("Plus beau sur le ciel et la peau, fichier un peu plus lourd", fontSize = 12.sp, color = cx.dim)
                    }
                    Switch(dither, { dither = it; result = null }, colors = SwitchDefaults.colors(checkedThumbColor = cx.onAccent, checkedTrackColor = cx.accent))
                }
                Spacer(Modifier.height(8.dp))
                Text("$ow × $oh · $frameCount images · ≈ %.1f Mo".format(estimate / 1_048_576.0), color = cx.dim, fontSize = 12.sp)
                if (estimate > 15L * 1_048_576) Text("Gros fichier : baisse la taille ou la fluidité pour WhatsApp et Discord.", color = cx.accent, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({
                        val u = uri ?: return@Button
                        work = scope.launch {
                            busy = "Préparation…"
                            val out = File(ctx.cacheDir, "cuttinx_gif_${System.currentTimeMillis()}.gif")
                            val ok = withContext(Dispatchers.Default) {
                                runCatching {
                                    GifRender.render(ctx, u, GifJob(range.start * durS, range.endInclusive * durS, fps, side, speed, mode, dither, text), out) { d, t ->
                                        busy = if (d * 2 <= t) "Images de la vidéo… ${100 * d / t} %" else "Création du GIF… ${100 * d / t} %"
                                    }
                                }
                            }
                            busy = null
                            ok.onSuccess { result?.delete(); result = out; resultVersion++ }
                                .onFailure { out.delete(); if (it !is kotlinx.coroutines.CancellationException) snack.showSnackbar(it.message ?: "Création impossible") }
                        }
                    }, shape = CxDesign.Button, colors = ButtonDefaults.buttonColors(containerColor = cx.accent, contentColor = cx.onAccent)) {
                        Text(if (result == null) "Créer le GIF" else "Recréer", fontWeight = FontWeight.SemiBold)
                    }
                    if (result != null) OutlinedButton({ result?.let { GifRender.share(ctx, it) } }, shape = CxDesign.Button) { Text("Partager") }
                    TextButton({ pick.launch(arrayOf("video/*")) }) { Text("Autre vidéo") }
                }
            }
            busy?.let { b ->
                BusyVeil(b)
                if (work?.isActive == true) TextButton({ work?.cancel(); busy = null }, Modifier.align(Alignment.BottomCenter).padding(32.dp)) {
                    Text("Annuler", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

@Composable
private fun ChipRow(title: String, content: @Composable RowScope.() -> Unit) {
    Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
