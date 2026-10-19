package com.superessence.cuttinx.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.superessence.cuttinx.core.overlay.export.ObsExporter
import com.superessence.cuttinx.core.overlay.export.PackOptions
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import com.superessence.cuttinx.desktop.render.SkiaGfx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Surface
import java.io.File

/** What the big preview shows. */
private sealed interface Target {
    data class Scene(val kind: SceneKind) : Target
    data class Stinger(val style: StingerStyle) : Target
    data class Panel(val index: Int) : Target
    data object Banner : Target
}

/** Families of editable elements. */
private enum class Family(val label: String) { SCENES("Scènes"), FRAMES("Cadres & widgets"), ALERTS("Alertes"), PROFILE("Profil Twitch"), STINGERS("Transitions") }

private fun familyOf(t: Target) = when (t) {
    is Target.Scene -> when { t.kind.fullBackground -> Family.SCENES; t.kind.isAlert -> Family.ALERTS; else -> Family.FRAMES }
    is Target.Stinger -> Family.STINGERS
    is Target.Panel, Target.Banner -> Family.PROFILE
}

@Composable
fun TwitchStudio(state: AppState) {
    var target by remember { mutableStateOf<Target>(Target.Scene(SceneKind.STARTING)) }
    var minutes by remember { mutableStateOf(5) }
    Row(Modifier.fillMaxSize()) {
        ThemeGallery(state, Modifier.width(330.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Twitch Editor", fontSize = 26.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(12.dp))
                Text(state.theme.name, color = Color(0xFF3D8BFF), fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Hint("${state.config.width}×${state.config.height} · fond transparent pour OBS")
            }
            Spacer(Modifier.height(10.dp))
            Preview(state, target, minutes, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(10.dp))
            val fam = familyOf(target)
            Chips(Family.entries.toList(), fam, { it.label }, { f ->
                target = when (f) {
                    Family.SCENES -> Target.Scene(SceneKind.STARTING); Family.FRAMES -> Target.Scene(SceneKind.GAMEPLAY); Family.ALERTS -> Target.Scene(SceneKind.ALERT)
                    Family.PROFILE -> Target.Panel(0); Family.STINGERS -> Target.Stinger(state.theme.stinger)
                }
            })
            Spacer(Modifier.height(8.dp))
            when (fam) {
                Family.SCENES -> Chips(SceneKind.entries.filter { it.fullBackground }, (target as? Target.Scene)?.kind, { it.label }, { target = Target.Scene(it) })
                Family.FRAMES -> Chips(SceneKind.entries.filter { !it.fullBackground && !it.isAlert }, (target as? Target.Scene)?.kind, { it.label }, { target = Target.Scene(it) })
                Family.ALERTS -> Chips(SceneKind.entries.filter { it.isAlert }, (target as? Target.Scene)?.kind, { it.label }, { target = Target.Scene(it) })
                Family.PROFILE -> Chips(state.config.panels.indices.map { Target.Panel(it) } + Target.Banner, target, { if (it is Target.Panel) "Panneau « ${state.config.panels.getOrNull(it.index) ?: ""} »" else "Bannière de profil" }, { target = it })
                Family.STINGERS -> Chips(StingerStyle.entries.toList(), (target as? Target.Stinger)?.style, { it.label + if (it == state.theme.stinger) " ★" else "" }, { target = Target.Stinger(it) })
            }
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
                state.renderer.drawScene(SkiaGfx(s.canvas, state.fonts), t, cfg.copy(themeId = t.id), SceneKind.STARTING, 1.0)
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
                    .border(2.dp, if (sel) Color(0xFF3D8BFF) else Color.Transparent, RoundedCornerShape(12.dp))
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
                val nc = canvas.nativeCanvas
                val c = SkiaGfx(nc, state.fonts)
                c.save()
                c.scale(size.width / cfg.width, size.height / cfg.height)
                // checkerboard = transparent areas (where OBS shows your game / camera)
                val transparent = when (target) { is Target.Scene -> !target.kind.fullBackground; else -> true }
                if (transparent) {
                    val sq = 40f
                    val a = com.superessence.cuttinx.core.gfx.fill(0xFF2A2C33.toInt())
                    val b = com.superessence.cuttinx.core.gfx.fill(0xFF33363E.toInt())
                    var y = 0f; var row = 0
                    while (y < cfg.height) { var x = 0f; var col = 0; while (x < cfg.width) { c.rect(x, y, sq, sq, if ((row + col) % 2 == 0) a else b); x += sq; col++ }; y += sq; row++ }
                }
                when (target) {
                    is Target.Scene -> {
                        val left = (minutes * 60 - time.toInt()).coerceAtLeast(0)
                        state.renderer.drawScene(c, theme, cfg, target.kind, time, OverlayRenderer.Layer.ALL, countdown = "%02d:%02d".format(left / 60, left % 60))
                    }
                    is Target.Stinger -> state.renderer.drawStinger(c, theme, cfg, target.style, (time % 2.2).coerceAtMost(OverlayAnim.STINGER_SEC))
                    is Target.Panel -> {
                        val k = cfg.width * 0.6f / 320f
                        c.save(); c.translate(cfg.width * 0.2f, (cfg.height - 100f * k) / 2); c.scale(k, k)
                        state.renderer.drawPanel(c, theme, cfg, cfg.panels.getOrNull(target.index) ?: ""); c.restore()
                    }
                    Target.Banner -> {
                        val k = cfg.width * 0.9f / 1200f
                        c.save(); c.translate(cfg.width * 0.05f, (cfg.height - 480f * k) / 2); c.scale(k, k)
                        state.renderer.drawBanner(c, theme, cfg); c.restore()
                    }
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

    var tab by remember { mutableStateOf(0) }
    Column(modifier.background(Cx.Panel).verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Personnaliser", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Chips(listOf(0, 1, 2, 3), tab, { listOf("Textes", "Couleurs", "Style", "Exporter")[it] }, { tab = it })
        if (tab == 1) { ColorsTab(state); return@Column }
        if (tab == 2) { StyleTab(state); return@Column }
        if (tab == 0) {
        ElementTexts(state, target)
        Title("MA CHAÎNE (PARTOUT)")
        OutlinedTextField(cfg.channel, { v -> set { it.copy(channel = v) } }, Modifier.fillMaxWidth(), label = { Text("Nom de la chaîne") }, singleLine = true)
        OutlinedTextField(cfg.tagline, { v -> set { it.copy(tagline = v) } }, Modifier.fillMaxWidth(), label = { Text("Phrase d'accroche") }, singleLine = true)
        OutlinedTextField(cfg.socials.joinToString(", "), { v -> set { it.copy(socials = v.split(",").map(String::trim)) } }, Modifier.fillMaxWidth(), label = { Text("Réseaux (séparés par des virgules)") })
        OutlinedTextField(cfg.schedule, { v -> set { it.copy(schedule = v) } }, Modifier.fillMaxWidth(), label = { Text("Planning des lives") }, singleLine = true)
        Text("Compte à rebours : $minutes min", fontSize = 13.sp)
        Slider(minutes.toFloat(), { setMinutes(it.toInt().coerceIn(1, 30)) }, valueRange = 1f..30f, steps = 28)
        return@Column
        }

        Title("EXPORT POUR OBS")
        Hint("Le pack contient toutes les scènes animées (HTML + PNG animé), les cadres jeu / discussion / caméra / chat, la barre d'objectif, 4 alertes (follow, abonnement, raid, don), les panneaux et bannières du profil Twitch, les transitions choisies et un guide d'installation OBS.")
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
                    ObsExporter(state.raster, state.renderer).exportPack(state.theme, c, dir, PackOptions(stingers = stingers, fps = fps, countdownMinutes = minutes)) { p, l -> progress = p; progressLabel = l }
                }.onSuccess { lastExport = it; progress = null }.onFailure { error = "Export impossible : ${it.message}"; progress = null }
            }
        }, Modifier.fillMaxWidth(), enabled = progress == null) { Text("Exporter le pack OBS complet") }
        OutlinedButton({
            val s = (target as? Target.Scene)?.kind ?: SceneKind.GAMEPLAY
            val name = when (target) { is Target.Panel -> "panneau-${state.config.panels.getOrNull(target.index) ?: ""}"; Target.Banner -> "banniere-profil"; else -> s.label }
            val file = chooseFile("Enregistrer l'image", "${ObsExporter(state.raster).slug(name)}.png") ?: return@OutlinedButton
            scope.launch(Dispatchers.Default) {
                runCatching {
                    val ex = ObsExporter(state.raster, state.renderer)
                    val bytes = when (target) {
                        is Target.Panel -> state.raster.frame(640, 200).let { fr -> fr.gfx.clear(0); fr.gfx.scale(2f, 2f); state.renderer.drawPanel(fr.gfx, state.theme, state.config, state.config.panels.getOrNull(target.index) ?: ""); fr.png() }
                        Target.Banner -> state.raster.frame(1200, 480).let { fr -> state.renderer.drawBanner(fr.gfx, state.theme, state.config); fr.png() }
                        else -> ex.renderPng(state.config) { c -> state.renderer.drawScene(c, state.theme, state.config, s, 0.0) }
                    }
                    file.writeBytes(bytes)
                }.onSuccess { lastExport = file.parentFile }.onFailure { error = it.message }
            }
        }, Modifier.fillMaxWidth()) { Text("Exporter cet élément en PNG") }
        progress?.let {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF3D8BFF))
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

// ---------------------------------------------------------------------- element texts, colours, style

@Composable
private fun ElementTexts(state: AppState, target: Target) {
    val cfg = state.config
    fun set(f: (OverlayConfig) -> OverlayConfig) { state.config = f(state.config) }
    when (target) {
        is Target.Scene -> {
            val s = target.kind
            Title(s.label.uppercase())
            if (s.title.isNotEmpty()) OutlinedTextField(cfg.sceneTitles[s] ?: "", { v -> set { it.copy(sceneTitles = it.sceneTitles + (s to v)) } }, Modifier.fillMaxWidth(), label = { Text("Titre") }, placeholder = { Text(s.title) }, singleLine = true)
            if (s.fullBackground && s != SceneKind.STARTING || s.isAlert)
                OutlinedTextField(cfg.sceneSubtitles[s] ?: "", { v -> set { it.copy(sceneSubtitles = it.sceneSubtitles + (s to v)) } }, Modifier.fillMaxWidth(), label = { Text(if (s.isAlert) "Message sous le pseudo" else "Deuxième ligne") }, placeholder = { Text(cfg.copy(sceneSubtitles = emptyMap()).subtitleOf(s)) }, singleLine = true)
            if (s.isAlert) OutlinedTextField(cfg.alertUser, { v -> set { it.copy(alertUser = v) } }, Modifier.fillMaxWidth(), label = { Text("Pseudo d'exemple") }, singleLine = true)
            if (s == SceneKind.GOAL) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cfg.goalCurrent.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { n -> set { it.copy(goalCurrent = n) } } }, Modifier.weight(1f), label = { Text("Actuel") }, singleLine = true)
                OutlinedTextField(cfg.goalTarget.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { n -> set { it.copy(goalTarget = n) } } }, Modifier.weight(1f), label = { Text("Objectif") }, singleLine = true)
            }
        }
        is Target.Panel, Target.Banner -> {
            Title("PANNEAUX DU PROFIL (320×100)")
            cfg.panels.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(p, { v -> set { it.copy(panels = it.panels.mapIndexed { j, x -> if (j == i) v else x }) } }, Modifier.weight(1f), singleLine = true, label = { Text("Panneau ${i + 1}") })
                    TextButton({ set { it.copy(panels = it.panels.filterIndexed { j, _ -> j != i }) } }) { Text("✕") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("DISCORD", "MES JEUX", "FAQ", "PARTENAIRES", "BOUTIQUE").filter { it !in cfg.panels }.take(4).forEach { s -> Chip("+ $s", false) { set { it.copy(panels = it.panels + s) } } }
            }
        }
        else -> {}
    }
}

@Composable
private fun ColorsTab(state: AppState) {
    val cfg = state.config
    val pal = cfg.palette ?: state.theme.palette
    Hint("Clique une couleur : scènes, cadres, alertes, transitions et panneaux suivent.")
    OverlayLabels.paletteRoles.forEachIndexed { i, role ->
        val cur = OverlayLabels.get(pal, i)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(Color(cur)))
            Spacer(Modifier.width(8.dp)); Text(role, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OverlayLabels.swatches.forEach { c ->
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Color(c))
                    .border(if (c == cur) 3.dp else 1.dp, if (c == cur) Color(0xFF3D8BFF) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .clickable { state.config = state.config.copy(palette = OverlayLabels.set(state.config.palette ?: state.theme.palette, i, c)) })
            }
        }
    }
    Title("COULEURS D'UN AUTRE THÈME")
    Chips(OverlayThemes.all, null, { it.name }, { t -> state.config = state.config.copy(palette = t.palette) })
    if (cfg.palette != null) TextButton({ state.config = state.config.copy(palette = null) }) { Text("Revenir aux couleurs du thème") }
}

@Composable
private fun StyleTab(state: AppState) {
    val cfg = state.config
    val th = state.theme
    fun set(f: (OverlayConfig) -> OverlayConfig) { state.config = f(state.config) }
    Title("POLICE DES TITRES")
    Chips(TitleFont.entries.toList(), cfg.font ?: th.font, { OverlayLabels.font(it) }, { f -> set { it.copy(font = f) } })
    Text("Taille des titres : ${(cfg.textScale * 100).toInt()} %", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    Slider(cfg.textScale, { v -> set { it.copy(textScale = v) } }, valueRange = 0.6f..1.5f)
    Title("CADRES")
    Chips(FrameStyle.entries.toList(), cfg.frame ?: th.frame, { OverlayLabels.frame(it) }, { f -> set { it.copy(frame = f) } })
    Title("CAMÉRA")
    Chips(CameraShape.entries.toList(), cfg.cameraShape, { it.label }, { s -> set { it.copy(cameraShape = s) } })
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Pseudo sur la caméra", Modifier.weight(1f)); Switch(cfg.nameOnCamera, { v -> set { it.copy(nameOnCamera = v) } }) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Caméra à droite", Modifier.weight(1f)); Switch(cfg.cameraRight, { v -> set { it.copy(cameraRight = v) } }) }
    Title("DÉCORS ANIMÉS")
    val motifs = cfg.motifs ?: th.motifs
    Motif.entries.chunked(4).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            row.forEach { m -> Chip(OverlayLabels.motif(m), m in motifs) { val next = if (m in motifs) motifs - m else motifs + m; if (next.isNotEmpty()) set { it.copy(motifs = next) } } }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Particules animées", Modifier.weight(1f)); Switch(cfg.particles, { v -> set { it.copy(particles = v) } }) }
    Title("BADGE")
    OutlinedTextField(cfg.badge ?: "", { v -> set { it.copy(badge = v) } }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(th.badge) }, label = { Text("LIVE, EN DIRECT, 配信中…") })
    Spacer(Modifier.height(10.dp))
    OutlinedButton({ set { it.copy(font = null, frame = null, motifs = null, badge = null, palette = null, textScale = 1f, cameraShape = CameraShape.THEME) } }, Modifier.fillMaxWidth()) { Text("Revenir au style du thème") }
}
