package com.superessence.cuttinx.ui.twitch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Accent of the Twitch space. */
val TwitchBlue = Color(0xFF3D8BFF)

private sealed interface Target {
    data class Scene(val kind: SceneKind) : Target
    data class Stinger(val style: StingerStyle) : Target
}

@Composable
private fun <T> BlueChips(options: List<T>, selected: (T) -> Boolean, label: (T) -> String, onClick: (T) -> Unit) {
    val cx = LocalCx.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val sel = selected(o)
            Text(label(o), Modifier.clip(RoundedCornerShape(50)).background(if (sel) TwitchBlue else cx.panel2).clickable { onClick(o) }.padding(horizontal = 12.dp, vertical = 7.dp),
                color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(top = 14.dp, bottom = 6.dp), color = TwitchBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)

@Composable
fun TwitchScreen(vm: StudioViewModel) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<Target>(Target.Scene(SceneKind.STARTING)) }
    var tab by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(5) }
    val cx = LocalCx.current
    Column(Modifier.fillMaxSize()) {
        // live preview (rendered by the same engine as the PC version and the exports)
        OverlayPreview(vm, cfg, target, minutes, Modifier.fillMaxWidth().padding(horizontal = 14.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)))
        Column(Modifier.padding(horizontal = 14.dp)) {
            Label("SCÈNES")
            BlueChips(SceneKind.entries.toList(), { target == Target.Scene(it) }, { it.label }, { target = Target.Scene(it) })
            Label("TRANSITIONS OBS")
            BlueChips(StingerStyle.entries.toList(), { target == Target.Stinger(it) }, { it.label + if (it == vm.theme.stinger) " ★" else "" }, { target = Target.Stinger(it) })
            Spacer(Modifier.height(10.dp))
            TabRow(tab, containerColor = Color.Transparent, contentColor = TwitchBlue) {
                listOf("Thème", "Textes", "Exporter").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
            msg?.let { m -> Text(m, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TwitchBlue.copy(alpha = 0.18f)).clickable { vm.message.value = null }.padding(10.dp), fontSize = 13.sp) }
            when (tab) {
                0 -> ThemePicker(vm, cfg)
                1 -> TextsEditor(vm, cfg, target, minutes) { minutes = it }
                else -> ExportPanel(vm, target, minutes, progress)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------- preview

@Composable
private fun OverlayPreview(vm: StudioViewModel, cfg: OverlayConfig, target: Target, minutes: Int, modifier: Modifier) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val w = 768; val h = 432
    LaunchedEffect(cfg, target, minutes) {
        // two software bitmaps rendered alternately in the background (blurs and glows included)
        val frames = listOf(vm.raster.frame(w, h), vm.raster.frame(w, h))
        val pc = cfg.copy(width = w, height = h)
        val start = System.nanoTime(); var i = 0
        while (isActive) {
            val t = (System.nanoTime() - start) / 1e9
            val f = frames[i % 2]
            withContext(Dispatchers.Default) {
                val g = f.gfx
                g.clear(0)
                when (target) {
                    is Target.Scene -> {
                        val left = (minutes * 60 - t.toInt()).coerceAtLeast(0)
                        vm.renderer.drawScene(g, vm.theme, pc, target.kind, t, OverlayRenderer.Layer.ALL, countdown = "%02d:%02d".format(left / 60, left % 60))
                    }
                    is Target.Stinger -> vm.renderer.drawStinger(g, vm.theme, pc, target.style, (t % 2.2).coerceAtMost(OverlayAnim.STINGER_SEC))
                }
            }
            image = f.bitmap.asImageBitmap()
            i++
            delay(16)
        }
    }
    val transparent = when (target) { is Target.Stinger -> true; is Target.Scene -> !target.kind.fullBackground }
    Box(modifier.background(Color(0xFF111216))) {
        if (transparent) Canvas(Modifier.matchParentSize()) {
            // checkerboard = transparent areas (your game / camera in OBS)
            val sq = size.width / 32
            var y = 0f; var r = 0
            while (y < size.height) { var x = 0f; var c = 0; while (x < size.width) { drawRect(if ((r + c) % 2 == 0) Color(0xFF2A2C33) else Color(0xFF33363E), Offset(x, y), Size(sq, sq)); x += sq; c++ }; y += sq; r++ }
        }
        image?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds) }
    }
}

// ---------------------------------------------------------------------- themes

@Composable
private fun ThemePicker(vm: StudioViewModel, cfg: OverlayConfig) {
    var category by remember { mutableStateOf<ThemeCategory?>(null) }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            val c = OverlayConfig(channel = "MaChaîne", width = 320, height = 180)
            for (t in OverlayThemes.all) {
                val f = vm.raster.frame(320, 180)
                vm.renderer.drawScene(f.gfx, t, c.copy(themeId = t.id), SceneKind.STARTING, 1.0)
                thumbs[t.id] = f.bitmap.asImageBitmap()
            }
        }
    }
    Text("${OverlayThemes.all.size} designs originaux pour tous les styles de live.", color = LocalCx.current.dim, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    BlueChips(listOf<ThemeCategory?>(null) + ThemeCategory.entries, { it == category }, { it?.label ?: "Tous" }, { category = it })
    Spacer(Modifier.height(8.dp))
    OverlayThemes.all.filter { category == null || it.category == category }.chunked(2).forEach { row ->
        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { t ->
                val sel = t.id == cfg.themeId
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(LocalCx.current.panel2)
                    .border(2.dp, if (sel) TwitchBlue else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { vm.update { it.copy(themeId = t.id, palette = null) } }) {
                    val img = thumbs[t.id]
                    if (img != null) Image(img, t.name, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(t.palette.background.toInt())))
                    Text(t.name, Modifier.padding(8.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------------- texts

@Composable
private fun TextsEditor(vm: StudioViewModel, cfg: OverlayConfig, target: Target, minutes: Int, setMinutes: (Int) -> Unit) {
    OutlinedTextField(cfg.channel, { v -> vm.update { it.copy(channel = v) } }, Modifier.fillMaxWidth(), label = { Text("Nom de la chaîne") }, singleLine = true)
    OutlinedTextField(cfg.tagline, { v -> vm.update { it.copy(tagline = v) } }, Modifier.fillMaxWidth(), label = { Text("Phrase d'accroche") }, singleLine = true)
    OutlinedTextField(cfg.socials.joinToString(", "), { v -> vm.update { it.copy(socials = v.split(",").map(String::trim)) } }, Modifier.fillMaxWidth(), label = { Text("Réseaux (séparés par des virgules)") })
    OutlinedTextField(cfg.schedule, { v -> vm.update { it.copy(schedule = v) } }, Modifier.fillMaxWidth(), label = { Text("Planning des lives") }, singleLine = true)
    (target as? Target.Scene)?.kind?.takeIf { it.title.isNotEmpty() }?.let { s ->
        OutlinedTextField(cfg.sceneTitles[s] ?: "", { v -> vm.update { it.copy(sceneTitles = it.sceneTitles + (s to v)) } }, Modifier.fillMaxWidth(), label = { Text("Titre de « ${s.label} »") }, placeholder = { Text(s.title) }, singleLine = true)
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Caméra à droite", Modifier.weight(1f)); Switch(cfg.cameraRight, { v -> vm.update { it.copy(cameraRight = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = TwitchBlue)) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Particules animées", Modifier.weight(1f)); Switch(cfg.particles, { v -> vm.update { it.copy(particles = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = TwitchBlue)) }
    Text("Compte à rebours : $minutes min", fontSize = 13.sp)
    Slider(minutes.toFloat(), { setMinutes(it.toInt().coerceIn(1, 30)) }, valueRange = 1f..30f, steps = 28, colors = SliderDefaults.colors(thumbColor = TwitchBlue, activeTrackColor = TwitchBlue))
    if (cfg.palette != null) TextButton({ vm.update { it.copy(palette = null) } }) { Text("Revenir aux couleurs du thème", color = TwitchBlue) }
}

// ---------------------------------------------------------------------- export

@Composable
private fun ExportPanel(vm: StudioViewModel, target: Target, minutes: Int, progress: PackProgress?) {
    var stingers by remember { mutableStateOf(setOf(StingerStyle.SPIDER_WEB)) }
    var hd by remember { mutableStateOf(false) }
    var fps by remember { mutableIntStateOf(24) }
    val cfg = vm.config.collectAsStateWithLifecycle().value
    val pack = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportPack(uri, hd, fps, stingers, minutes) }
    val scene = (target as? Target.Scene)?.kind ?: SceneKind.GAMEPLAY
    val png = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> if (uri != null) vm.exportScene(uri, scene) }
    Text("Le pack contient les scènes animées (HTML + PNG animés), les cadres jeu / discussion / caméra / chat, l'alerte de follow, les transitions choisies et un guide OBS. Copie-le sur ton PC (ou utilise l'appli PC CUTTINX PRO).", color = LocalCx.current.dim, fontSize = 12.sp)
    Label("TRANSITIONS INCLUSES")
    BlueChips(StingerStyle.entries.toList(), { it in stingers || it == vm.theme.stinger }, { it.label }, { st -> if (st != vm.theme.stinger) stingers = if (st in stingers) stingers - st else stingers + st })
    Label("QUALITÉ")
    BlueChips(listOf(false, true), { it == hd }, { if (it) "1080p" else "720p (plus léger)" }, { hd = it })
    Spacer(Modifier.height(6.dp))
    BlueChips(listOf(24, 30), { it == fps }, { "$it i/s" }, { fps = it })
    Spacer(Modifier.height(12.dp))
    Button({ pack.launch("CuttinxPRO_${cfg.channel}_${vm.theme.id}.zip") }, Modifier.fillMaxWidth(), enabled = progress == null, colors = ButtonDefaults.buttonColors(containerColor = TwitchBlue, contentColor = Color.White)) { Text("Exporter le pack OBS (.zip)") }
    OutlinedButton({ png.launch("${scene.label}.png") }, Modifier.fillMaxWidth()) { Text("Enregistrer « ${scene.label} » en PNG") }
    progress?.let {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator({ it.fraction }, Modifier.fillMaxWidth(), color = TwitchBlue)
        Text(it.label, fontSize = 12.sp, color = LocalCx.current.dim)
    }
}
