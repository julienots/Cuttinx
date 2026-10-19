package com.superessence.cuttinx.ui.twitch

import com.superessence.cuttinx.icons.CxIcons
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
    data class Panel(val index: Int) : Target
    data object Banner : Target
}

/** Families of elements the streamer can edit, shown as a first row of chips. */
private enum class Family(val label: String) { SCENES("Scènes"), FRAMES("Cadres & widgets"), ALERTS("Alertes"), PROFILE("Profil Twitch"), STINGERS("Transitions") }

private fun familyOf(t: Target) = when (t) {
    is Target.Scene -> when { t.kind.fullBackground -> Family.SCENES; t.kind.isAlert -> Family.ALERTS; else -> Family.FRAMES }
    is Target.Stinger -> Family.STINGERS
    is Target.Panel, Target.Banner -> Family.PROFILE
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
    var tuto by remember { mutableStateOf<com.superessence.cuttinx.core.tutorial.Film?>(null) }
    tuto?.let { f -> com.superessence.cuttinx.ui.help.FilmPlayerDialog(f) { tuto = null } }
    Column(Modifier.fillMaxSize()) {
        // live preview (rendered by the same engine as the PC version and the exports)
        val ratio = when (target) { is Target.Panel -> 320f / 100f; Target.Banner -> 1200f / 480f; else -> 16f / 9f }
        OverlayPreview(vm, cfg, target, minutes, Modifier.fillMaxWidth().padding(horizontal = 14.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)), ratio)
        Column(Modifier.padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(8.dp))
            val fam = familyOf(target)
            BlueChips(Family.entries.toList(), { it == fam }, { it.label }, { f ->
                target = when (f) {
                    Family.SCENES -> Target.Scene(SceneKind.STARTING)
                    Family.FRAMES -> Target.Scene(SceneKind.GAMEPLAY)
                    Family.ALERTS -> Target.Scene(SceneKind.ALERT)
                    Family.PROFILE -> Target.Panel(0)
                    Family.STINGERS -> Target.Stinger(vm.theme.stinger)
                }
            })
            Spacer(Modifier.height(6.dp))
            when (fam) {
                Family.SCENES -> BlueChips(SceneKind.entries.filter { it.fullBackground }, { target == Target.Scene(it) }, { it.label }, { target = Target.Scene(it) })
                Family.FRAMES -> BlueChips(SceneKind.entries.filter { !it.fullBackground && !it.isAlert }, { target == Target.Scene(it) }, { it.label }, { target = Target.Scene(it) })
                Family.ALERTS -> BlueChips(SceneKind.entries.filter { it.isAlert }, { target == Target.Scene(it) }, { it.label }, { target = Target.Scene(it) })
                Family.PROFILE -> BlueChips(cfg.panels.indices.map { Target.Panel(it) } + Target.Banner, { target == it }, { if (it is Target.Panel) "Panneau « ${cfg.panels.getOrNull(it.index) ?: ""} »" else "Bannière de profil" }, { target = it })
                Family.STINGERS -> BlueChips(StingerStyle.entries.toList(), { target == Target.Stinger(it) }, { it.label + if (it == vm.theme.stinger) " (du thème)" else "" }, { target = Target.Stinger(it) })
            }
            Spacer(Modifier.height(6.dp))
            ScrollableTabRow(tab, containerColor = Color.Transparent, contentColor = TwitchBlue, edgePadding = 0.dp) {
                listOf("Thème", "Couleurs", "Style", "Textes", "Exporter").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
            msg?.let { m -> Text(m, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TwitchBlue.copy(alpha = 0.18f)).clickable { vm.message.value = null }.padding(10.dp), fontSize = 13.sp) }
            Row(Modifier.clip(RoundedCornerShape(50)).background(TwitchBlue.copy(alpha = 0.15f)).clickable { tuto = com.superessence.cuttinx.core.tutorial.Tutorials.of(com.superessence.cuttinx.core.tutorial.TutoSpace.TWITCH).first() }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(CxIcons.Play, null, Modifier.size(14.dp), tint = TwitchBlue); Spacer(Modifier.width(6.dp))
                Text("Tutos vidéo : créer le pack, l'installer dans OBS", color = TwitchBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            when (tab) {
                0 -> ThemePicker(vm, cfg)
                1 -> ColorsEditor(vm, cfg)
                2 -> StyleEditor(vm, cfg)
                3 -> TextsEditor(vm, cfg, target, minutes) { minutes = it }
                else -> ExportPanel(vm, target, minutes, progress)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------- preview

@Composable
private fun OverlayPreview(vm: StudioViewModel, cfg: OverlayConfig, target: Target, minutes: Int, modifier: Modifier, ratio: Float = 16f / 9f) {
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
                    is Target.Panel -> {
                        // panel centred at a readable size
                        val k = w * 0.8f / 320f
                        g.save(); g.translate(w * 0.1f, (h - 100f * k) / 2); g.scale(k, k)
                        vm.renderer.drawPanel(g, vm.theme, cfg, cfg.panels.getOrNull(target.index) ?: "")
                        g.restore()
                    }
                    Target.Banner -> {
                        val k = w * 0.9f / 1200f
                        g.save(); g.translate(w * 0.05f, (h - 480f * k) / 2); g.scale(k, k)
                        vm.renderer.drawBanner(g, vm.theme, cfg)
                        g.restore()
                    }
                }
            }
            image = f.bitmap.asImageBitmap()
            i++
            // static elements: no need to redraw 60 times per second
            delay(if (target is Target.Panel || target is Target.Banner) 400 else 16)
        }
    }
    val transparent = when (target) { is Target.Stinger -> true; is Target.Scene -> !target.kind.fullBackground; else -> true }
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
private fun BlueSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = TwitchBlue)) }

@Composable
private fun TextsEditor(vm: StudioViewModel, cfg: OverlayConfig, target: Target, minutes: Int, setMinutes: (Int) -> Unit) {
    // what is being edited first: the element shown in the preview
    when (target) {
        is Target.Scene -> {
            val s = target.kind
            Label(s.label.uppercase())
            if (s.title.isNotEmpty()) OutlinedTextField(cfg.sceneTitles[s] ?: "", { v -> vm.update { it.copy(sceneTitles = it.sceneTitles + (s to v)) } }, Modifier.fillMaxWidth(), label = { Text("Titre") }, placeholder = { Text(s.title) }, singleLine = true)
            if (s.fullBackground && s != SceneKind.STARTING || s.isAlert)
                OutlinedTextField(cfg.sceneSubtitles[s] ?: "", { v -> vm.update { it.copy(sceneSubtitles = it.sceneSubtitles + (s to v)) } }, Modifier.fillMaxWidth(), label = { Text(if (s.isAlert) "Message sous le pseudo" else "Deuxième ligne") }, placeholder = { Text(cfg.copy(sceneSubtitles = emptyMap()).subtitleOf(s)) }, singleLine = true)
            if (s.isAlert) OutlinedTextField(cfg.alertUser, { v -> vm.update { it.copy(alertUser = v) } }, Modifier.fillMaxWidth(), label = { Text("Pseudo d'exemple") }, singleLine = true)
            if (s == SceneKind.GOAL) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cfg.goalCurrent.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { n -> vm.update { it.copy(goalCurrent = n) } } }, Modifier.weight(1f), label = { Text("Actuel") }, singleLine = true)
                OutlinedTextField(cfg.goalTarget.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { n -> vm.update { it.copy(goalTarget = n) } } }, Modifier.weight(1f), label = { Text("Objectif") }, singleLine = true)
            }
            if (s == SceneKind.STARTING) {
                Text("Compte à rebours : $minutes min", fontSize = 13.sp)
                Slider(minutes.toFloat(), { setMinutes(it.toInt().coerceIn(1, 30)) }, valueRange = 1f..30f, steps = 28, colors = SliderDefaults.colors(thumbColor = TwitchBlue, activeTrackColor = TwitchBlue))
            }
        }
        is Target.Panel, Target.Banner -> {
            Label("PANNEAUX DU PROFIL (320×100)")
            cfg.panels.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(p, { v -> vm.update { it.copy(panels = it.panels.mapIndexed { j, x -> if (j == i) v else x }) } }, Modifier.weight(1f), singleLine = true, label = { Text("Panneau ${i + 1}") })
                    IconButton({ vm.update { it.copy(panels = it.panels.filterIndexed { j, _ -> j != i }) } }) { Icon(CxIcons.Close, "Retirer", tint = TwitchBlue) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("DISCORD", "MES JEUX", "FAQ", "PARTENAIRES", "BOUTIQUE", "CONTACT").filter { it !in cfg.panels }.take(4).forEach { s ->
                    Text("+ $s", Modifier.clip(RoundedCornerShape(50)).background(LocalCx.current.panel2).clickable { vm.update { it.copy(panels = it.panels + s) } }.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp)
                }
            }
        }
        else -> {}
    }
    Label("MA CHAÎNE (PARTOUT)")
    OutlinedTextField(cfg.channel, { v -> vm.update { it.copy(channel = v) } }, Modifier.fillMaxWidth(), label = { Text("Nom de la chaîne") }, singleLine = true)
    OutlinedTextField(cfg.tagline, { v -> vm.update { it.copy(tagline = v) } }, Modifier.fillMaxWidth(), label = { Text("Phrase d'accroche") }, singleLine = true)
    OutlinedTextField(cfg.socials.joinToString(", "), { v -> vm.update { it.copy(socials = v.split(",").map(String::trim)) } }, Modifier.fillMaxWidth(), label = { Text("Réseaux (séparés par des virgules)") })
    OutlinedTextField(cfg.schedule, { v -> vm.update { it.copy(schedule = v) } }, Modifier.fillMaxWidth(), label = { Text("Planning des lives") }, singleLine = true)
}

// ---------------------------------------------------------------------- colours & style

@Composable
private fun ColorsEditor(vm: StudioViewModel, cfg: OverlayConfig) {
    val pal = cfg.palette ?: vm.theme.palette
    Text("Touche une couleur pour la changer. Tout l'overlay suit : scènes, cadres, alertes, transitions, panneaux.", color = LocalCx.current.dim, fontSize = 12.sp)
    OverlayLabels.paletteRoles.forEachIndexed { i, role ->
        val cur = OverlayLabels.get(pal, i)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(cur.toInt())).border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(8.dp))
            Text(role, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OverlayLabels.swatches.forEach { c ->
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(Color(c.toInt()))
                    .border(if (c == cur) 3.dp else 1.dp, if (c == cur) TwitchBlue else Color.White.copy(alpha = 0.15f), RoundedCornerShape(7.dp))
                    .clickable { vm.update { it.copy(palette = OverlayLabels.set(it.palette ?: vm.theme.palette, i, c)) } })
            }
        }
    }
    Label("PRENDRE LES COULEURS D'UN AUTRE THÈME")
    BlueChips(OverlayThemes.all, { false }, { it.name }, { t -> vm.update { it.copy(palette = t.palette) } })
    if (cfg.palette != null) TextButton({ vm.update { it.copy(palette = null) } }) { Text("Revenir aux couleurs du thème", color = TwitchBlue) }
}

@Composable
private fun StyleEditor(vm: StudioViewModel, cfg: OverlayConfig) {
    val th = vm.theme
    Label("POLICE DES TITRES")
    BlueChips(TitleFont.entries.toList(), { it == (cfg.font ?: th.font) }, { OverlayLabels.font(it) }, { f -> vm.update { it.copy(font = f) } })
    Text("Taille des titres : ${(cfg.textScale * 100).toInt()} %", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    Slider(cfg.textScale, { v -> vm.update { it.copy(textScale = v) } }, valueRange = 0.6f..1.5f, colors = SliderDefaults.colors(thumbColor = TwitchBlue, activeTrackColor = TwitchBlue))
    Label("CADRES")
    BlueChips(FrameStyle.entries.toList(), { it == (cfg.frame ?: th.frame) }, { OverlayLabels.frame(it) }, { f -> vm.update { it.copy(frame = f) } })
    Label("FORME DE LA CAMÉRA")
    BlueChips(CameraShape.entries.toList(), { it == cfg.cameraShape }, { it.label }, { s -> vm.update { it.copy(cameraShape = s) } })
    BlueSwitch("Pseudo sur la caméra", cfg.nameOnCamera) { v -> vm.update { it.copy(nameOnCamera = v) } }
    BlueSwitch("Caméra à droite", cfg.cameraRight) { v -> vm.update { it.copy(cameraRight = v) } }
    Label("DÉCORS ANIMÉS (plusieurs possibles)")
    val motifs = cfg.motifs ?: th.motifs
    BlueChips(Motif.entries.toList(), { it in motifs }, { OverlayLabels.motif(it) }, { m ->
        val next = if (m in motifs) motifs - m else motifs + m
        if (next.isNotEmpty()) vm.update { it.copy(motifs = next) }
    })
    BlueSwitch("Particules animées", cfg.particles) { v -> vm.update { it.copy(particles = v) } }
    Label("BADGE")
    OutlinedTextField(cfg.badge ?: "", { v -> vm.update { it.copy(badge = v) } }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(th.badge) }, label = { Text("Texte du badge (LIVE, EN DIRECT, 配信中…)") })
    Spacer(Modifier.height(10.dp))
    OutlinedButton({ vm.resetStyle() }, Modifier.fillMaxWidth()) { Text("Revenir au style du thème", color = TwitchBlue) }
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
    val png = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) when (target) {
            is Target.Panel -> vm.exportPanel(uri, cfg.panels.getOrNull(target.index) ?: "")
            Target.Banner -> vm.exportBanner(uri)
            else -> vm.exportScene(uri, scene)
        }
    }
    val elementName = when (target) { is Target.Panel -> "Panneau ${cfg.panels.getOrNull(target.index) ?: ""}"; Target.Banner -> "Bannière de profil"; else -> scene.label }
    Text("Le pack contient les scènes animées (HTML + PNG animés), les cadres jeu / discussion / caméra / chat, la barre d'objectif, 4 alertes (follow, abonnement, raid, don), les panneaux et bannières du profil Twitch, les transitions choisies et un guide OBS. Copie-le sur ton PC (ou utilise l'appli PC CUTTINX PRO).", color = LocalCx.current.dim, fontSize = 12.sp)
    Label("TRANSITIONS INCLUSES")
    BlueChips(StingerStyle.entries.toList(), { it in stingers || it == vm.theme.stinger }, { it.label }, { st -> if (st != vm.theme.stinger) stingers = if (st in stingers) stingers - st else stingers + st })
    Label("QUALITÉ")
    BlueChips(listOf(false, true), { it == hd }, { if (it) "1080p" else "720p (plus léger)" }, { hd = it })
    Spacer(Modifier.height(6.dp))
    BlueChips(listOf(24, 30), { it == fps }, { "$it i/s" }, { fps = it })
    Spacer(Modifier.height(12.dp))
    Button({ pack.launch("CuttinxPRO_${cfg.channel}_${vm.theme.id}.zip") }, Modifier.fillMaxWidth(), enabled = progress == null, colors = ButtonDefaults.buttonColors(containerColor = TwitchBlue, contentColor = Color.White)) { Text("Exporter le pack OBS (.zip)") }
    OutlinedButton({ png.launch("$elementName.png") }, Modifier.fillMaxWidth()) { Text("Enregistrer « $elementName » en PNG") }
    progress?.let {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator({ it.fraction }, Modifier.fillMaxWidth(), color = TwitchBlue)
        Text(it.label, fontSize = 12.sp, color = LocalCx.current.dim)
    }
}
