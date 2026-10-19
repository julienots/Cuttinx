package com.superessence.cuttinx.ui.help

import com.superessence.cuttinx.icons.CxIcons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.superessence.cuttinx.core.tutorial.Film
import com.superessence.cuttinx.core.tutorial.FilmRenderer
import com.superessence.cuttinx.core.tutorial.TutoSpace
import com.superessence.cuttinx.core.tutorial.Tutorials
import com.superessence.cuttinx.overlay.AndroidRaster
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private fun spaceColor(s: TutoSpace) = when (s) { TutoSpace.EDITOR -> Color(0xFFFFCC1F); TutoSpace.TWITCH -> Color(0xFF3D8BFF); TutoSpace.BRAND -> Color(0xFFFF4FA3) }

private fun mmss(t: Double) = "%d:%02d".format(t.toInt() / 60, t.toInt() % 60)

/** Shared renderer + raster (the films are drawn live, no video file is shipped). */
private object FilmEngine {
    val renderer = FilmRenderer()
    @Volatile var raster: AndroidRaster? = null
    fun raster(ctx: android.content.Context) = raster ?: AndroidRaster(ctx.applicationContext.assets).also { raster = it }
}

/** Grid of the video tutorials, with live-drawn thumbnails. */
@Composable
fun FilmGallery(space: TutoSpace? = null, onPlay: (Film) -> Unit) {
    val ctx = LocalContext.current
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    val films = if (space == null) Tutorials.all else Tutorials.of(space)
    LaunchedEffect(films) {
        withContext(Dispatchers.Default) {
            val r = FilmEngine.raster(ctx)
            for (f in films) {
                if (f.id in thumbs) continue
                val fr = r.frame(384, 216)
                FilmEngine.renderer.draw(fr.gfx, f, f.duration * 0.45, 384, 216)
                thumbs[f.id] = fr.bitmap.asImageBitmap()
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        films.forEach { f ->
            Column(Modifier.width(210.dp).clip(RoundedCornerShape(12.dp)).background(LocalCx.current.panel2).clickable { onPlay(f) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                    thumbs[f.id]?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(spaceColor(f.space)), contentAlignment = Alignment.Center) {
                        Icon(CxIcons.Play, "Lire", Modifier.size(20.dp), tint = if (f.space == TutoSpace.EDITOR) Color.Black else Color.White)
                    }
                    Text(mmss(f.duration), Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp), color = Color.White, fontSize = 10.sp)
                }
                Text(f.title, Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2, lineHeight = 15.sp)
                Text(f.space.label, Modifier.padding(start = 8.dp, bottom = 8.dp), color = spaceColor(f.space), fontSize = 10.sp)
            }
        }
    }
}

/** Full-screen player of a tutorial film: play / pause, timeline, steps, next film. */
@Composable
fun FilmPlayerDialog(start: Film, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var film by remember { mutableStateOf(start) }
    var time by remember(film) { mutableStateOf(0.0) }
    var playing by remember(film) { mutableStateOf(true) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val w = 960; val h = 540
    LaunchedEffect(film) {
        val r = FilmEngine.raster(ctx)
        val frames = listOf(r.frame(w, h), r.frame(w, h))
        var i = 0
        var last = System.nanoTime()
        while (isActive) {
            val now = System.nanoTime()
            if (playing) time = (time + (now - last) / 1e9).coerceAtMost(film.duration)
            last = now
            if (time >= film.duration) playing = false
            val f = frames[i % 2]
            val t = time
            withContext(Dispatchers.Default) { FilmEngine.renderer.draw(f.gfx, film, t, w, h) }
            image = f.bitmap.asImageBitmap()
            i++
            delay(if (playing) 16 else 120)
        }
    }
    val acc = spaceColor(film.space)
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF08090B)).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(film.title, Modifier.weight(1f), fontWeight = FontWeight.Black, fontSize = 17.sp, color = Color.White)
                TextButton(onDismiss) { Text("Fermer", color = acc) }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black).clickable { if (time >= film.duration) time = 0.0; playing = !playing }) {
                image?.let { Image(it, film.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ time = 0.0; playing = true }) { Icon(CxIcons.Replay, "Revoir", tint = Color.White) }
                Button({ if (time >= film.duration) time = 0.0; playing = !playing }, colors = ButtonDefaults.buttonColors(containerColor = acc, contentColor = if (film.space == TutoSpace.EDITOR) Color.Black else Color.White)) {
                    Icon(if (playing) CxIcons.Pause else CxIcons.Play, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (playing) "Pause" else "Lecture", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text("${mmss(time)} / ${mmss(film.duration)}", color = Color.White, fontSize = 12.sp)
            }
            Slider(time.toFloat(), { time = it.toDouble() }, valueRange = 0f..film.duration.toFloat(), colors = SliderDefaults.colors(thumbColor = acc, activeTrackColor = acc))
            Text("ÉTAPES", color = acc, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            val steps = remember(film) { (0..(film.duration * 10).toInt()).mapNotNull { film.caption(it / 10.0) }.distinct() }
            steps.forEachIndexed { i, cap ->
                val cur = time >= cap.t0 && time < cap.t1
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).background(if (cur) acc.copy(alpha = 0.18f) else Color(0xFF17181D)).clickable { time = cap.t0; playing = true }.padding(10.dp)) {
                    Text("${i + 1}", Modifier.width(22.dp), color = acc, fontWeight = FontWeight.Black)
                    Text(cap.text, color = Color.White, fontSize = 13.sp)
                }
            }
            Text("AUTRES TUTOS", Modifier.padding(top = 12.dp, bottom = 6.dp), color = acc, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            FilmGallery(null) { film = it }
        }
    }
}
