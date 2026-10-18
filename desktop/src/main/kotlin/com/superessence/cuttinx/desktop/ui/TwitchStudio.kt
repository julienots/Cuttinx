package com.superessence.cuttinx.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.desktop.AppState
import com.superessence.cuttinx.desktop.Cx
import com.superessence.cuttinx.desktop.export.ObsExporter
import com.superessence.cuttinx.desktop.export.PackOptions
import com.superessence.cuttinx.desktop.render.OverlayRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Surface
import java.io.File

/** What the big preview shows. */
private sealed interface Target {
    data class Scene(val kind: SceneKind) : Target
    data class Stinger(val style: StingerStyle) : Target
}

@Composable
fun TwitchStudio(state: AppState) {
    var target by remember { mutableStateOf<Target>(Target.Scene(SceneKind.STARTING)) }
    var minutes by remember { mutableStateOf(5) }
    Row(Modifier.fillMaxSize()) {
        ThemeGallery(state, Modifier.width(330.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Twitch Studio", fontSize = 26.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(12.dp))
                Text(state.theme.name, color = Cx.Yellow, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Hint("${state.config.width}×${state.config.height} · fond transparent pour OBS")
            }
            Spacer(Modifier.height(10.dp))
            Preview(state, target, minutes, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(10.dp))
            Title("SCÈNES")
            Chips(SceneKind.entries.toList(), (target as? Target.Scene)?.kind, { it.label }, { target = Target.Scene(it) })
            Title("TRANSITIONS DE SCÈNE (STINGERS)")
            Chips(StingerStyle.entries.toList(), (target as? Target.Stinger)?.style, { it.label + if (it == state.theme.stinger) " ★" else "" }, { target = Target.Stinger(it) })
        }
        Settings(state, target, minutes, { minutes = it }, Modifier.width(360.dp).fillMaxHeight())
    }
}

// ---------------------------------------------------------------------- gallery

@Composable
private fun ThemeGallery(state: AppState, modifier: Modifier) {
    var category by remember { mutableStateOf<ThemeCategory?>(null) }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(Unit) {
        // small static previews, rendered once in the background
        withContext(Dispatchers.Default) {
            val cfg = OverlayConfig(channel = "MaChaîne", width = 480, height = 270, particles = true)
            for (t in OverlayThemes.all) {
                val s = Surface.makeRasterN32Premul(480, 270)
                state.renderer.drawScene(s.canvas, t, cfg.copy(themeId = t.id), SceneKind.STARTING, 1.0)
                thumbs[t.id] = s.makeImageSnapshot().toComposeImageBitmap()
            }
        }
    }
    Column(modifier.background(Cx.Panel).padding(12.dp)) {
        Text("Thèmes", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Hint("${OverlayThemes.all.size} designs originaux : super-héros, geek, anime, Japon, mignon, aesthetic, saisons…")
        Spacer(Modifier.height(8.dp))
        Chips(listOf<ThemeCategory?>(null) + ThemeCategory.entries, category, { it?.label ?: "Tous" }, { category = it })
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(GridCells.Fixed(1), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(OverlayThemes.all.filter { category == null || it.category == category }, key = { it.id }) { t ->
                val sel = t.id == state.config.themeId
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Cx.Panel2)
                    .border(2.dp, if (sel) Cx.Yellow else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { state.config = state.config.copy(themeId = t.id, palette = null) }) {
                    val img = thumbs[t.id]
                    if (img != null) Image(img, t.name, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(t.palette.background.toInt())))
                    Column(Modifier.padding(10.dp)) {
                        Text(t.name, fontWeight = FontWeight.Bold)
                        Text(t.description, color = Cx.Dim, fontSize = 11.sp, maxLines = 2, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- live preview

@Composable
private fun Preview(state: AppState, target: Target, minutes: Int, modifier: Modifier) {
    var time by remember { mutableStateOf(0.0) }
    LaunchedEffect(target) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> time = (now - start) / 1e9 }
    }
    val cfg = state.config
    val theme = state.theme
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.aspectRatio(cfg.width / cfg.height.toFloat()).fillMaxSize().clip(RoundedCornerShape(10.dp))) {
            drawIntoCanvas { canvas ->
                val c = canvas.nativeCanvas
                c.save()
                c.scale(size.width / cfg.width, size.height / cfg.height)
                // checkerboard = transparent areas (where OBS shows your game / camera)
                val transparent = when (target) { is Target.Stinger -> true; is Target.Scene -> !target.kind.fullBackground }
                if (transparent) {
                    val sq = 40f
                    val a = org.jetbrains.skia.Paint().apply { color = 0xFF2A2C33.toInt() }
                    val b = org.jetbrains.skia.Paint().apply { color = 0xFF33363E.toInt() }
                    var y = 0f; var row = 0
                    while (y < cfg.height) { var x = 0f; var col = 0; while (x < cfg.width) { c.drawRect(org.jetbrains.skia.Rect.makeXYWH(x, y, sq, sq), if ((row + col) % 2 == 0) a else b); x += sq; col++ }; y += sq; row++ }
                }
                when (target) {
                    is Target.Scene -> {
                        val left = (minutes * 60 - time.toInt()).coerceAtLeast(0)
                        state.renderer.drawScene(c, theme, cfg, target.kind, time, OverlayRenderer.Layer.ALL, countdown = "%02d:%02d".format(left / 60, left % 60))
                    }
                    is Target.Stinger -> state.renderer.drawStinger(c, theme, cfg, target.style, (time % 2.2).coerceAtMost(OverlayAnim.STINGER_SEC))
                }
                c.restore()
            }
        }
    }
}

// ---------------------------------------------------------------------- settings & export

@Composable
private fun Settings(state: AppState, target: Target, minutes: Int, setMinutes: (Int) -> Unit, modifier: Modifier) {
    val cfg = state.config
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var progressLabel by remember { mutableStateOf("") }
    var lastExport by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var stingers by remember { mutableStateOf(setOf(StingerStyle.SPIDER_WEB)) }
    var hd by remember { mutableStateOf(true) }
    var fps by remember { mutableStateOf(24) }
    fun set(f: (OverlayConfig) -> OverlayConfig) { state.config = f(state.config) }

    Column(modifier.background(Cx.Panel).verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Personnaliser", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Title("TEXTES")
        OutlinedTextField(cfg.channel, { v -> set { it.copy(channel = v) } }, Modifier.fillMaxWidth(), label = { Text("Nom de la chaîne") }, singleLine = true)
        OutlinedTextField(cfg.tagline, { v -> set { it.copy(tagline = v) } }, Modifier.fillMaxWidth(), label = { Text("Phrase d'accroche") }, singleLine = true)
        OutlinedTextField(cfg.socials.joinToString(", "), { v -> set { it.copy(socials = v.split(",").map(String::trim)) } }, Modifier.fillMaxWidth(), label = { Text("Réseaux (séparés par des virgules)") })
        OutlinedTextField(cfg.schedule, { v -> set { it.copy(schedule = v) } }, Modifier.fillMaxWidth(), label = { Text("Planning des lives") }, singleLine = true)
        (target as? Target.Scene)?.kind?.takeIf { it.title.isNotEmpty() }?.let { s ->
            OutlinedTextField(cfg.sceneTitles[s] ?: "", { v -> set { it.copy(sceneTitles = it.sceneTitles + (s to v)) } }, Modifier.fillMaxWidth(), label = { Text("Titre de « ${s.label} »") }, placeholder = { Text(s.title) }, singleLine = true)
        }
        Title("MISE EN PAGE")
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Caméra à droite", Modifier.weight(1f)); Switch(cfg.cameraRight, { v -> set { it.copy(cameraRight = v) } }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Particules animées", Modifier.weight(1f)); Switch(cfg.particles, { v -> set { it.copy(particles = v) } }) }
        Text("Compte à rebours : $minutes min", fontSize = 13.sp)
        Slider(minutes.toFloat(), { setMinutes(it.toInt().coerceIn(1, 30)) }, valueRange = 1f..30f, steps = 28)
        if (cfg.palette != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("Couleurs de votre identité appliquées")
            TextButton({ set { it.copy(palette = null) } }) { Text("Couleurs du thème") }
        }

        Title("EXPORT POUR OBS")
        Hint("Le pack contient toutes les scènes animées (HTML + PNG animé), les cadres jeu / discussion / caméra / chat, l'alerte de follow, les transitions choisies et un guide d'installation OBS.")
        Spacer(Modifier.height(6.dp))
        Text("Transitions incluses :", fontSize = 13.sp)
        StingerStyle.entries.chunked(2).forEach { row ->
            Row { row.forEach { st ->
                Row(Modifier.weight(1f).clickable { stingers = if (st in stingers) stingers - st else stingers + st }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(st in stingers || st == state.theme.stinger, { stingers = if (st in stingers) stingers - st else stingers + st }, enabled = st != state.theme.stinger)
                    Text(st.label, fontSize = 12.sp)
                }
            } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip("1080p", hd) { hd = true }; Spacer(Modifier.width(6.dp)); Chip("720p (plus léger)", !hd) { hd = false }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip("24 i/s", fps == 24) { fps = 24 }; Spacer(Modifier.width(6.dp)); Chip("30 i/s", fps == 30) { fps = 30 }
        }
        Spacer(Modifier.height(10.dp))
        Button({
            val dir = chooseFolder("Où enregistrer le pack OBS ?") ?: return@Button
            error = null; progress = 0f
            scope.launch(Dispatchers.Default) {
                runCatching {
                    val c = if (hd) state.config.copy(width = 1920, height = 1080) else state.config.copy(width = 1280, height = 720)
                    ObsExporter(state.renderer).exportPack(state.theme, c, dir, PackOptions(stingers = stingers, fps = fps, countdownMinutes = minutes)) { p, l -> progress = p; progressLabel = l }
                }.onSuccess { lastExport = it; progress = null }.onFailure { error = "Export impossible : ${it.message}"; progress = null }
            }
        }, Modifier.fillMaxWidth(), enabled = progress == null) { Text("Exporter le pack OBS complet") }
        OutlinedButton({
            val s = (target as? Target.Scene)?.kind ?: SceneKind.GAMEPLAY
            val file = chooseFile("Enregistrer l'image", "${ObsExporter().slug(s.label)}.png") ?: return@OutlinedButton
            scope.launch(Dispatchers.Default) {
                runCatching { file.writeBytes(ObsExporter(state.renderer).renderPng(state.config) { c -> state.renderer.drawScene(c, state.theme, state.config, s, 0.0) }) }
                    .onSuccess { lastExport = file.parentFile }.onFailure { error = it.message }
            }
        }, Modifier.fillMaxWidth()) { Text("Exporter cette scène en PNG") }
        progress?.let {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = Cx.Yellow)
            Hint(progressLabel)
        }
        error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp) }
        lastExport?.let { f ->
            Spacer(Modifier.height(8.dp))
            Text("✅ Exporté dans ${f.absolutePath}", fontSize = 12.sp)
            TextButton({ openFolder(f) }) { Text("Ouvrir le dossier") }
        }
    }
}
