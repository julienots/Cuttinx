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
import com.superessence.cuttinx.twitch.*
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
    data class Asset(val asset: TwitchAsset) : Target
}

/** Families of elements the streamer can edit, shown as a first row of chips. */
private enum class Family(val label: String) { SCENES("Scènes"), FRAMES("Cadres & widgets"), ALERTS("Alertes"), PROFILE("Profil & emotes"), STINGERS("Transitions") }

private fun familyOf(t: Target) = when (t) {
    is Target.Scene -> when { t.kind.fullBackground -> Family.SCENES; t.kind.isAlert -> Family.ALERTS; else -> Family.FRAMES }
    is Target.Stinger -> Family.STINGERS
    is Target.Panel, Target.Banner, is Target.Asset -> Family.PROFILE
}

private val tabs = listOf("IA", "Thème", "Couleurs", "Style & logo", "Textes", "Éléments & images", "Exporter")
private const val TAB_ELEMENTS = 5

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
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val saveStatus by vm.saveStatus.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<Target>(Target.Scene(SceneKind.STARTING)) }
    var tab by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(5) }
    var sel by remember { mutableStateOf<Sel?>(null) }
    val stage = remember { StageState() }
    var tuto by remember { mutableStateOf<com.superessence.cuttinx.core.tutorial.Film?>(null) }
    tuto?.let { f -> com.superessence.cuttinx.ui.help.FilmPlayerDialog(f) { tuto = null } }
    val scene = (target as? Target.Scene)?.kind
    LaunchedEffect(scene) { sel = null }
    // saved as soon as the app goes to the background (and on leaving the screen)
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.saveNow() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); vm.saveNow() }
    }
    // pictures: several at once to add, one to replace
    var replaceId by remember { mutableStateOf<String?>(null) }
    val addPics = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.importImages(uris, scene, null) { id -> sel = Sel.Item(id) }
    }
    val replacePic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = replaceId
        if (uri != null && id != null) vm.importImages(listOf(uri), scene, id) { sel = Sel.Item(it) }
    }

    val bgPic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val sc = scene
        if (uri != null && sc != null) vm.importSceneBackground(uri, sc)
    }

    // the preview + element pickers, and the editing tabs: side by side on tablets, stacked on phones
    val previewPart: @Composable (Modifier, Modifier) -> Unit = { mod, previewMod ->
        Column(mod) {
            ZoomStage(stage, 16f / 9f, previewMod.clip(RoundedCornerShape(14.dp))) { m ->
                Box(m.overlayEditing({ vm.config.value }, scene, { sel }, { s -> sel = s; if (s != null) tab = TAB_ELEMENTS }, stage,
                    { c, k -> vm.set(c, k) }, { vm.endGesture() })) {
                    OverlayPreview(vm, cfg, target, minutes, stage.zoom, Modifier.matchParentSize())
                    SelectionOverlay(cfg, scene, sel, stage, TwitchBlue, Modifier.matchParentSize())
                }
            }
            ZoomBar(stage, TwitchBlue, Modifier.fillMaxWidth())
            val fam = familyOf(target)
            BlueChips(Family.entries.toList(), { it == fam }, { it.label }, { f ->
                target = when (f) {
                    Family.SCENES -> Target.Scene(SceneKind.STARTING)
                    Family.FRAMES -> Target.Scene(SceneKind.GAMEPLAY)
                    Family.ALERTS -> Target.Scene(SceneKind.ALERT)
                    Family.PROFILE -> Target.Asset(TwitchAsset.AVATAR)
                    Family.STINGERS -> Target.Stinger(vm.theme.stinger)
                }
            })
            Spacer(Modifier.height(6.dp))
            when (fam) {
                Family.SCENES -> BlueChips(SceneKind.entries.filter { it.fullBackground }, { target == Target.Scene(it) }, { it.label }, { target = Target.Scene(it) })
                Family.FRAMES -> BlueChips(SceneKind.entries.filter { !it.fullBackground && !it.isAlert }, { target == Target.Scene(it) }, { it.label }, { target = Target.Scene(it) })
                Family.ALERTS -> BlueChips(SceneKind.entries.filter { it.isAlert }, { target == Target.Scene(it) }, { it.label }, { target = Target.Scene(it) })
                Family.PROFILE -> BlueChips(TwitchAsset.entries.map { Target.Asset(it) } + cfg.panels.indices.map { Target.Panel(it) } + Target.Banner, { target == it }, {
                    when (it) { is Target.Asset -> it.asset.label; is Target.Panel -> "Panneau « ${cfg.panels.getOrNull(it.index) ?: ""} »"; else -> "Bannière de profil" }
                }, { target = it })
                Family.STINGERS -> BlueChips(StingerStyle.entries.toList(), { target == Target.Stinger(it) }, { it.label + if (it == vm.theme.stinger) " (du thème)" else "" }, { target = Target.Stinger(it) })
            }
        }
    }
    val editPart: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            // undo / redo / save, always at hand
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 400.dp
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ vm.undo() }, enabled = canUndo) { Icon(CxIcons.Undo, "Annuler") }
                    IconButton({ vm.redo() }, enabled = canRedo) { Icon(CxIcons.Redo, "Rétablir") }
                    Text(saveStatus, Modifier.weight(1f), color = LocalCx.current.dim, fontSize = 11.sp, maxLines = 2, lineHeight = 13.sp)
                    TextButton({ vm.saveNow() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(CxIcons.Check, "Enregistrer", Modifier.size(18.dp), tint = TwitchBlue)
                        if (!narrow) { Spacer(Modifier.width(4.dp)); Text("Enregistrer", color = TwitchBlue) }
                    }
                    TextButton({ tab = tabs.lastIndex }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(CxIcons.Download, "Exporter", Modifier.size(18.dp), tint = TwitchBlue)
                        if (!narrow) { Spacer(Modifier.width(4.dp)); Text("Exporter", color = TwitchBlue) }
                    }
                }
            }
            // the tab row never scrolls away: only the content below it scrolls
            ScrollableTabRow(tab, containerColor = Color.Transparent, contentColor = TwitchBlue, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                msg?.let { m -> Text(m, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TwitchBlue.copy(alpha = 0.18f)).clickable { vm.message.value = null }.padding(10.dp), fontSize = 13.sp) }
                Row(Modifier.clip(RoundedCornerShape(50)).background(TwitchBlue.copy(alpha = 0.15f)).clickable { tuto = com.superessence.cuttinx.core.tutorial.Tutorials.of(com.superessence.cuttinx.core.tutorial.TutoSpace.TWITCH).first() }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(CxIcons.Play, null, Modifier.size(14.dp), tint = TwitchBlue); Spacer(Modifier.width(6.dp))
                    Text("Tutos vidéo : créer le pack, l'installer dans OBS", color = TwitchBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                when (tab) {
                    0 -> AiPanel(cfg, { c -> vm.update { c }; vm.message.value = "Style appliqué à toutes les scènes, cadres, alertes et assets" }) { c, m -> StaticPreview(vm, c, m) }
                    1 -> ThemePicker(vm, cfg)
                    2 -> ColorsEditor(vm, cfg)
                    3 -> StyleEditor(vm, cfg)
                    4 -> TextsEditor(vm, cfg, target, minutes) { minutes = it }
                    TAB_ELEMENTS -> ElementsPanel(cfg, scene, sel, { sel = it }, { c, k -> vm.set(c, k) }, onPickBackground = { bgPic.launch("image/*") }) { rid ->
                        if (rid == null) addPics.launch("image/*") else { replaceId = rid; replacePic.launch("image/*") }
                    }
                    else -> ExportPanel(vm, target, minutes, progress)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                previewPart(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()), Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                editPart(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            val maxPreview = maxHeight * 0.32f
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                previewPart(Modifier.fillMaxWidth(), Modifier.fillMaxWidth().heightIn(max = maxPreview).aspectRatio(16f / 9f, matchHeightConstraintsFirst = true).align(Alignment.CenterHorizontally))
                editPart(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------------- preview

/** Small still preview (assistant proposals). */
@Composable
private fun StaticPreview(vm: StudioViewModel, cfg: OverlayConfig, modifier: Modifier) {
    var image by remember(cfg) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cfg) {
        image = withContext(Dispatchers.Default) {
            val f = vm.raster.frame(384, 216)
            vm.renderer.drawScene(f.gfx, OverlayThemes[cfg.themeId], cfg.copy(width = 384, height = 216), SceneKind.STARTING, 1.0)
            f.bitmap.asImageBitmap()
        }
    }
    Box(modifier.background(Color(0xFF111216))) { image?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds) } }
}

@Composable
private fun OverlayPreview(vm: StudioViewModel, cfg: OverlayConfig, target: Target, minutes: Int, zoom: Float, modifier: Modifier) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    // sharper rendering when zoomed in (capped so phones stay fluid)
    val quality = when { zoom >= 2.4f -> 2.5f; zoom >= 1.6f -> 1.8f; zoom >= 1.15f -> 1.3f; else -> 1f }
    val w = (768 * quality).toInt(); val h = (432 * quality).toInt()
    val latest by rememberUpdatedState(cfg)
    // the loop keeps running while the settings change (no restart, no new bitmaps on every drag)
    LaunchedEffect(target, minutes, w) {
        // three software bitmaps used in turn: the one on screen is never drawn into
        val frames = List(3) { vm.raster.frame(w, h) }
        val static = target is Target.Panel || target is Target.Banner || target is Target.Asset
        val start = System.nanoTime(); var i = 0
        var drawn: OverlayConfig? = null; var drawnAt = 0L
        while (isActive) {
            val c = latest
            val now = System.nanoTime()
            if (static && c === drawn && now - drawnAt < 1_000_000_000L) { delay(30); continue }
            val t = (now - start) / 1e9
            val f = frames[i % 3]
            withContext(Dispatchers.Default) {
                val g = f.gfx
                g.clear(0)
                val pc = c.copy(width = w, height = h)
                val theme = OverlayThemes[c.themeId]
                when (target) {
                    is Target.Scene -> {
                        val left = (minutes * 60 - t.toInt()).coerceAtLeast(0)
                        vm.renderer.drawScene(g, theme, pc, target.kind, t, OverlayRenderer.Layer.ALL, countdown = "%02d:%02d".format(left / 60, left % 60))
                    }
                    is Target.Stinger -> vm.renderer.drawStinger(g, theme, pc, target.style, (t % 2.2).coerceAtMost(OverlayAnim.STINGER_SEC))
                    is Target.Panel -> {
                        // panel centred at a readable size
                        val k = w * 0.8f / 320f
                        g.save(); g.translate(w * 0.1f, (h - 100f * k) / 2); g.scale(k, k)
                        vm.renderer.drawPanel(g, theme, c, c.panels.getOrNull(target.index) ?: "")
                        g.restore()
                    }
                    Target.Banner -> {
                        val k = w * 0.9f / 1200f
                        g.save(); g.translate(w * 0.05f, (h - 480f * k) / 2); g.scale(k, k)
                        vm.renderer.drawBanner(g, theme, c)
                        g.restore()
                    }
                    is Target.Asset -> target.asset.draw(g, vm.renderer, theme, c, w.toFloat(), h.toFloat())
                }
            }
            image = f.bitmap.asImageBitmap()
            drawn = c; drawnAt = now
            i++
            delay(if (static) 30 else 16)
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
        is Target.Asset -> {
            if (target.asset == TwitchAsset.EMOTES) EmotesEditor(cfg) { c -> vm.update { c } }
            if (target.asset == TwitchAsset.AVATAR || target.asset == TwitchAsset.POINTS) Text("Importe ton logo dans l'onglet « Style & logo » pour le mettre sur ta photo de profil.", color = LocalCx.current.dim, fontSize = 12.sp)
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
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.importLogo(uri) }
    LogoPanel(cfg, { pick.launch("image/*") }) { c -> vm.update { c } }
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
            is Target.Asset -> vm.exportAsset(uri, target.asset)
            else -> vm.exportScene(uri, scene)
        }
    }
    val elementName = when (target) { is Target.Panel -> "Panneau ${cfg.panels.getOrNull(target.index) ?: ""}"; Target.Banner -> "Bannière de profil"; is Target.Asset -> target.asset.label; else -> scene.label }
    Text("Le pack contient les ${SceneKind.entries.count { it.fullBackground }} scènes animées (HTML + PNG animés), les cadres (jeu, discussion, duo, caméra, chat, live vertical), le bandeau défilant, le pop-up réseaux, la barre d'objectif, 4 alertes, la photo de profil, les badges d'abonné, les emotes, les points de chaîne, les panneaux et bannières, les transitions choisies et un guide OBS. Copie-le sur ton PC (ou utilise l'appli PC CUTTINX PRO).", color = LocalCx.current.dim, fontSize = 12.sp)
    Label("TRANSITIONS INCLUSES")
    BlueChips(StingerStyle.entries.toList(), { it in stingers || it == vm.theme.stinger }, { it.label }, { st -> if (st != vm.theme.stinger) stingers = if (st in stingers) stingers - st else stingers + st })
    Label("QUALITÉ")
    BlueChips(listOf(false, true), { it == hd }, { if (it) "1080p" else "720p (plus léger)" }, { hd = it })
    Spacer(Modifier.height(6.dp))
    BlueChips(listOf(24, 30), { it == fps }, { "$it i/s" }, { fps = it })
    Spacer(Modifier.height(12.dp))
    Button({ pack.launch("CuttinxPRO_${cfg.channel}_${vm.theme.id}.zip") }, Modifier.fillMaxWidth(), enabled = progress == null, colors = ButtonDefaults.buttonColors(containerColor = TwitchBlue, contentColor = Color.White)) { Text("Exporter le pack OBS / Streamlabs animé (.zip)") }
    val animatedTarget = target is Target.Scene || target is Target.Stinger
    val sceneOf = (target as? Target.Scene)?.kind; val stingerOf = (target as? Target.Stinger)?.style
    val elementZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportElement(uri, sceneOf, stingerOf, hd, fps, minutes) }
    val gif = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/gif")) { uri -> if (uri != null) vm.exportGif(uri, sceneOf, stingerOf) }
    if (animatedTarget) {
        Button({ elementZip.launch("$elementName (animé).zip") }, Modifier.fillMaxWidth(), enabled = progress == null, colors = ButtonDefaults.buttonColors(containerColor = TwitchBlue, contentColor = Color.White)) { Text("Exporter « $elementName » animé (.zip)") }
        OutlinedButton({ gif.launch("$elementName.gif") }, Modifier.fillMaxWidth(), enabled = progress == null) { Text("Enregistrer « $elementName » en GIF animé") }
        Text("Animé dans OBS et Streamlabs : .html (source Navigateur), .gif (source Image, Alert Box Streamlabs), PNG animé (source Média). Les vidéos WebM transparentes se font avec l'appli PC.", color = LocalCx.current.dim, fontSize = 11.sp)
    }
    OutlinedButton({ png.launch("$elementName.png") }, Modifier.fillMaxWidth()) { Text("Enregistrer « $elementName » en PNG (image fixe)") }
    progress?.let {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator({ it.fraction }, Modifier.fillMaxWidth(), color = TwitchBlue)
        Text(it.label, fontSize = 12.sp, color = LocalCx.current.dim)
    }
}
