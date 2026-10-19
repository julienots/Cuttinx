package com.superessence.cuttinx.desktop.ui

import com.superessence.cuttinx.icons.CxIcons
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.tutorial.Film
import com.superessence.cuttinx.core.tutorial.FilmRenderer
import com.superessence.cuttinx.core.tutorial.TutoSpace
import com.superessence.cuttinx.core.tutorial.Tutorials
import com.superessence.cuttinx.desktop.AppState
import com.superessence.cuttinx.desktop.Cx
import com.superessence.cuttinx.desktop.render.SkiaGfx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Surface

private val filmRenderer = FilmRenderer()

fun spaceColor(s: TutoSpace) = when (s) { TutoSpace.EDITOR -> Color(0xFFFFCC1F); TutoSpace.TWITCH -> Color(0xFF3D8BFF); TutoSpace.BRAND -> Color(0xFFFF4FA3) }
private fun mmss(t: Double) = "%d:%02d".format(t.toInt() / 60, t.toInt() % 60)

/** Grid of the video tutorials (thumbnails drawn once in the background). */
@Composable
fun FilmGrid(state: AppState, onPlay: (Film) -> Unit) {
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            for (f in Tutorials.all) {
                val s = Surface.makeRasterN32Premul(384, 216)
                filmRenderer.draw(SkiaGfx(s.canvas, state.fonts), f, f.duration * 0.45, 384, 216)
                thumbs[f.id] = s.makeImageSnapshot().toComposeImageBitmap()
            }
        }
    }
    LazyVerticalGrid(GridCells.Adaptive(250.dp), Modifier.fillMaxWidth().heightIn(max = 2000.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(Tutorials.all, key = { it.id }) { f ->
            Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Cx.Panel).clickable { onPlay(f) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                    thumbs[f.id]?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(spaceColor(f.space)), contentAlignment = Alignment.Center) { Icon(CxIcons.Play, "Lire", Modifier.size(22.dp), tint = if (f.space == TutoSpace.EDITOR) Color.Black else Color.White) }
                    Text(mmss(f.duration), Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp), color = Color.White, fontSize = 11.sp)
                }
                Text(f.title, Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
                Text(f.space.label + " · " + f.summary, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), color = Cx.Dim, fontSize = 11.sp, maxLines = 2)
            }
        }
    }
}

/** Live player of a tutorial film (drawn by Skia at the window resolution, always sharp). */
@Composable
fun FilmPlayer(state: AppState, start: Film, onClose: () -> Unit) {
    var film by remember { mutableStateOf(start) }
    var time by remember(film) { mutableStateOf(0.0) }
    var playing by remember(film) { mutableStateOf(true) }
    LaunchedEffect(film) {
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            if (playing) time = (time + (now - last) / 1e9).coerceAtMost(film.duration)
            if (time >= film.duration) playing = false
            last = now
        }
    }
    val acc = spaceColor(film.space)
    Column(Modifier.fillMaxSize().background(Color(0xFF08090B)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(film.title, Modifier.weight(1f), fontWeight = FontWeight.Black, fontSize = 20.sp)
            TextButton(onClose) { Icon(CxIcons.Close, null, Modifier.size(16.dp), tint = acc); Spacer(Modifier.width(6.dp)); Text("Fermer", color = acc) }
        }
        Row(Modifier.weight(1f)) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.aspectRatio(16f / 9f).fillMaxSize().clip(RoundedCornerShape(10.dp)).clickable { if (time >= film.duration) time = 0.0; playing = !playing }) {
                    val t = time
                    drawIntoCanvas { filmRenderer.draw(SkiaGfx(it.nativeCanvas, state.fonts), film, t, size.width.toInt(), size.height.toInt()) }
                }
            }
            Column(Modifier.width(320.dp).fillMaxHeight().padding(start = 12.dp)) {
                Text("ÉTAPES", color = acc, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                val steps = remember(film) { (0..(film.duration * 10).toInt()).mapNotNull { film.caption(it / 10.0) }.distinct() }
                steps.forEachIndexed { i, cap ->
                    val cur = time >= cap.t0 && time < cap.t1
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).background(if (cur) acc.copy(alpha = 0.18f) else Cx.Panel).clickable { time = cap.t0; playing = true }.padding(10.dp)) {
                        Text("${i + 1}", Modifier.width(22.dp), color = acc, fontWeight = FontWeight.Black)
                        Text(cap.text, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                val idx = Tutorials.all.indexOf(film)
                Row {
                    TextButton({ film = Tutorials.all[(idx - 1 + Tutorials.all.size) % Tutorials.all.size] }) { Icon(CxIcons.Prev, null, Modifier.size(16.dp)); Text("Précédent") }
                    Spacer(Modifier.weight(1f))
                    TextButton({ film = Tutorials.all[(idx + 1) % Tutorials.all.size] }) { Text("Suivant"); Icon(CxIcons.Next, null, Modifier.size(16.dp)) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button({ if (time >= film.duration) time = 0.0; playing = !playing }, colors = ButtonDefaults.buttonColors(containerColor = acc, contentColor = if (film.space == TutoSpace.EDITOR) Color.Black else Color.White)) {
                Icon(if (playing) CxIcons.Pause else CxIcons.Play, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (playing) "Pause" else "Lecture", fontWeight = FontWeight.Bold)
            }
            TextButton({ time = 0.0; playing = true }) { Icon(CxIcons.Replay, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Revoir") }
            Text("${mmss(time)} / ${mmss(film.duration)}", Modifier.padding(horizontal = 10.dp), fontSize = 13.sp)
            Slider(time.toFloat(), { time = it.toDouble() }, Modifier.weight(1f), valueRange = 0f..film.duration.toFloat())
        }
    }
}
