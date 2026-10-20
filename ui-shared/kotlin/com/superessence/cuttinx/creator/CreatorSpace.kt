package com.superessence.cuttinx.creator

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.brand.BrandKit
import com.superessence.cuttinx.core.creator.*
import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.icons.CxIcons
import com.superessence.cuttinx.twitch.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Accent of the Studio Créateur (Android and PC). */
val CreatorAccent = Color(0xFFFF5A36)

/** A picture chosen by the user, already shrunk and encoded by the platform. */
data class PickedPicture(val b64: String, val width: Int, val height: Int, val name: String)

/**
 * State of the Studio Créateur, shared by Android and PC: the saved visuals, the one being
 * edited, its undo history and its automatic saving on disk.
 */
class CreatorState(dir: File) {
    private val store = CreatorStore(dir)
    private val history = UndoHistory<CreatorDesign>()
    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cuttinx-creator-save").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null

    var designs by mutableStateOf(runCatching { store.list() }.getOrDefault(emptyList()))
        private set
    var current by mutableStateOf<CreatorDesign?>(null)
        private set
    var sel by mutableStateOf<Sel?>(null)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var saveStatus by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
    val stage = StageState()

    fun open(d: CreatorDesign) { current = d; sel = null; stage.reset(); history.clear(); refreshHistory(); saveStatus = "Enregistré" }

    fun create(format: SocialFormat, template: CreatorTemplates.Template, kit: BrandKit?) {
        val d = template.build(format, kit)
        open(d); write(d); designs = listOf(d) + designs
    }

    fun close() { saveNow(); current = null; sel = null }

    fun delete(id: String) {
        saver.execute { runCatching { store.delete(id) } }
        designs = designs.filter { it.id != id }
        if (current?.id == id) { current = null; sel = null }
    }

    fun duplicate(d: CreatorDesign) {
        val copy = d.copy(id = OverlayItems.newId(), name = d.name + " (copie)", updatedAt = System.currentTimeMillis())
        write(copy); designs = listOf(copy) + designs
    }

    /** Changes the visual being edited; [key] groups quick changes (a drag, a slider) into one undo step. */
    fun set(d: CreatorDesign, key: String?) {
        val cur = current ?: return
        if (d == cur) return
        history.record(cur, key)
        apply(d)
    }

    fun setConfig(c: OverlayConfig, key: String?) { current?.let { set(it.withConfig(c), key) } }

    fun endGesture() = history.cut()
    fun undo() { val cur = current ?: return; history.undo(cur)?.let { apply(it) } }
    fun redo() { val cur = current ?: return; history.redo(cur)?.let { apply(it) } }

    private fun apply(d: CreatorDesign) {
        val now = d.copy(updatedAt = System.currentTimeMillis())
        current = now
        designs = designs.map { if (it.id == now.id) now else it }
        refreshHistory()
        saveStatus = "Modifications en cours…"
        pending?.cancel(false)
        pending = saver.schedule(Runnable { write(now) }, 500, TimeUnit.MILLISECONDS)
    }

    private fun refreshHistory() { canUndo = history.canUndo; canRedo = history.canRedo }

    fun saveNow() {
        pending?.cancel(false)
        current?.let { d -> saver.execute { write(d) } }
    }

    private fun write(d: CreatorDesign) {
        runCatching { store.save(d) }
            .onSuccess { if (current?.id == d.id) saveStatus = "Enregistré ✓" }
            .onFailure { saveStatus = "Échec de l'enregistrement"; message = "Enregistrement impossible : ${it.message}" }
    }

    /** Adds pictures to the visual, or replaces the picture [replaceId]. */
    fun addPictures(pics: List<PickedPicture>, replaceId: String?) {
        val d = current ?: return
        var c = d.asConfig()
        var last: String? = null
        for (p in pics) {
            if (replaceId != null) { c = c.updateItem(replaceId) { it.copy(data = p.b64, aspect = p.height.toFloat() / p.width, name = p.name) }; last = replaceId }
            else { val it = OverlayItems.image(c, null, p.b64, p.width, p.height, p.name); c = OverlayItems.add(c, it); last = it.id }
        }
        set(d.withConfig(c), null)
        last?.let { sel = Sel.Item(it) }
    }

    fun setBackgroundPicture(p: PickedPicture) {
        val d = current ?: return
        set(d.copy(bg = d.bg.copy(kind = BgKind.IMAGE, image = p.b64, imageAspect = p.height.toFloat() / p.width)), null)
    }
}

// ---------------------------------------------------------------------------------- UI

@Composable
private fun Head(text: String) = Text(text, Modifier.padding(top = 14.dp, bottom = 6.dp), color = CreatorAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) =
    Text(label, Modifier.clip(RoundedCornerShape(50)).background(if (selected) CreatorAccent else Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Pills(options: List<T>, selected: T?, label: (T) -> String, onClick: (T) -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { options.forEach { o -> Pill(label(o), o == selected) { onClick(o) } } }

@Composable
private fun Swatches(selected: Long?, onPick: (Long?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("Thème", selected == null) { onPick(null) }
        OverlayLabels.swatches.forEach { c ->
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(Color(c.toInt()))
                .border(if (c == selected) 3.dp else 1.dp, if (c == selected) CreatorAccent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(7.dp))
                .clickable { onPick(c) })
        }
    }
}

/**
 * The Studio Créateur: YouTube thumbnails and banners, TikTok / Reels covers, Instagram posts and
 * stories, Twitch, X, LinkedIn, podcast covers… with the element editor of the Twitch Editor.
 * The platform gives the [preview] of a visual (animated = true in the editor, still thumbnails elsewhere), the picture pickers and the PNG export.
 */
@Composable
fun CreatorSpace(
    state: CreatorState,
    kit: BrandKit?,
    preview: @Composable (CreatorDesign, Modifier, Boolean) -> Unit,
    pickPictures: (replaceId: String?) -> Unit,
    pickBackground: () -> Unit,
    export: (CreatorDesign) -> Unit,
) {
    val d = state.current
    if (d == null) Gallery(state, kit, preview) else Editor(state, d, kit, preview, pickPictures, pickBackground, export)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Gallery(state: CreatorState, kit: BrandKit?, preview: @Composable (CreatorDesign, Modifier, Boolean) -> Unit) {
    var format by remember { mutableStateOf(SocialFormat.YT_THUMB) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 1100.dp).padding(14.dp)) {
        Text("Studio Créateur", fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text("Miniatures YouTube, couvertures TikTok / Reels / Shorts, posts et stories Insta, bannières Twitch, X, LinkedIn, pochettes… au format exact de chaque réseau." +
            if (kit != null) " Tes couleurs et ton nom (ID Influenceur) sont déjà appliqués." else " Crée ton ID Influenceur pour que tout prenne tes couleurs.", fontSize = 13.sp, color = dim)
        Head("1. CHOISIS LE FORMAT")
        Pills(SocialFormat.entries.toList(), format, { "${it.platform} · ${it.label}" }) { format = it }
        Text("${format.width} × ${format.height} px" + (format.safeLabel.takeIf { it.isNotEmpty() }?.let { " · zone sûre : $it" } ?: ""), fontSize = 11.sp, color = dim, modifier = Modifier.padding(top = 6.dp))
        Head("2. CHOISIS UN MODÈLE")
        val templates = remember(format, kit) { CreatorTemplates.forFormat(format).map { it to it.build(format, kit) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            templates.forEach { (t, sample) ->
                Column(Modifier.width(200.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)).clickable { state.create(format, t, kit) }.padding(8.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(format.ratio.coerceIn(0.5f, 3f)).clip(RoundedCornerShape(8.dp))) { preview(sample, Modifier.fillMaxSize(), false) }
                    Spacer(Modifier.height(6.dp))
                    Text(t.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        if (state.designs.isNotEmpty()) {
            Head("MES VISUELS (${state.designs.size})")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.designs.forEach { x ->
                    Column(Modifier.width(200.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)).clickable { state.open(x) }.padding(8.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(x.format.ratio.coerceIn(0.5f, 3f)).clip(RoundedCornerShape(8.dp))) { preview(x, Modifier.fillMaxSize(), false) }
                        Text(x.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text(x.format.label, fontSize = 11.sp, color = dim, maxLines = 1)
                        Row {
                            TextButton({ state.duplicate(x) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(CxIcons.Duplicate, "Dupliquer", Modifier.size(16.dp)) }
                            TextButton({ state.delete(x.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(CxIcons.Delete, "Supprimer", Modifier.size(16.dp), tint = Color(0xFFFF6B6B)) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

private val tabs = listOf("Éléments", "Fond", "Couleurs", "Modèles", "Exporter")

@Composable
private fun Editor(state: CreatorState, d: CreatorDesign, kit: BrandKit?, preview: @Composable (CreatorDesign, Modifier, Boolean) -> Unit,
                   pickPictures: (String?) -> Unit, pickBackground: () -> Unit, export: (CreatorDesign) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var guides by remember { mutableStateOf(true) }
    val scene = SceneKind.STARTING // a single canvas: any scene without built-in elements does
    val stagePart: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            ZoomStage(state.stage, d.format.ratio, Modifier.fillMaxWidth().weight(1f)) { m ->
                Box(m.clip(RoundedCornerShape(10.dp)).overlayEditing({ state.current?.asConfig() ?: d.asConfig() }, scene, { state.sel }, { s -> state.sel = s; if (s != null) tab = 0 }, state.stage,
                    { c, k -> state.setConfig(c, k) }, { state.endGesture() }, builtIns = false)) {
                    preview(d, Modifier.matchParentSize(), true)
                    if (guides) SafeZone(d.format, Modifier.matchParentSize())
                    SelectionOverlay(d.asConfig(), scene, state.sel, state.stage, CreatorAccent, Modifier.matchParentSize())
                }
            }
            ZoomBar(state.stage, CreatorAccent, Modifier.fillMaxWidth())
        }
    }
    val panel: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            ScrollableTabRow(tab, containerColor = Color.Transparent, contentColor = CreatorAccent, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                state.message?.let { m -> Text(m, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CreatorAccent.copy(alpha = 0.18f)).clickable { state.message = null }.padding(10.dp), fontSize = 13.sp) }
                when (tab) {
                    0 -> ElementsPanel(d.asConfig(), scene, state.sel, { state.sel = it }, { c, k -> state.setConfig(c, k) }, pickPictures, canvasOnly = true)
                    1 -> BackgroundTab(state, d, pickBackground)
                    2 -> ColorsTab(state, d, kit)
                    3 -> TemplatesTab(state, d, kit, preview)
                    else -> ExportTab(d, guides, { guides = it }) { export(d) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // header: back, name, undo / redo, save, export — always visible
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 560.dp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ state.close() }) { Icon(CxIcons.Back, "Mes visuels") }
                var name by remember(d.id) { mutableStateOf(d.name) }
                OutlinedTextField(name, { name = it; state.set(d.copy(name = it), "name") }, Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp))
                IconButton({ state.undo() }, enabled = state.canUndo) { Icon(CxIcons.Undo, "Annuler") }
                IconButton({ state.redo() }, enabled = state.canRedo) { Icon(CxIcons.Redo, "Rétablir") }
                if (!narrow) Text(state.saveStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
                IconButton({ state.saveNow() }) { Icon(CxIcons.Check, "Enregistrer", tint = CreatorAccent) }
                Button({ export(d) }, colors = ButtonDefaults.buttonColors(containerColor = CreatorAccent, contentColor = Color.White), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(CxIcons.Download, null, Modifier.size(16.dp)); if (!narrow) { Spacer(Modifier.width(6.dp)); Text("Exporter PNG", fontWeight = FontWeight.Bold) }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 760.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                stagePart(Modifier.weight(1.3f).fillMaxHeight())
                panel(Modifier.weight(1f).fillMaxHeight())
            } else {
                val h = maxHeight * 0.42f
                Column(Modifier.fillMaxSize()) {
                    stagePart(Modifier.fillMaxWidth().height(h))
                    panel(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/** Dashed frame of the part always visible on the platform (banner centre, TikTok buttons…). */
@Composable
private fun SafeZone(f: SocialFormat, modifier: Modifier) {
    val s = f.safe ?: return
    Canvas(modifier) {
        val x = s[0] * size.width; val y = s[1] * size.height; val w = s[2] * size.width; val h = s[3] * size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        if (f == SocialFormat.PROFILE) drawCircle(Color.White.copy(alpha = 0.7f), size.minDimension / 2 * 0.98f, Offset(size.width / 2, size.height / 2), style = Stroke(2.dp.toPx(), pathEffect = dash))
        else drawRect(Color.White.copy(alpha = 0.7f), Offset(x, y), Size(w, h), style = Stroke(2.dp.toPx(), pathEffect = dash))
    }
}

@Composable
private fun BackgroundTab(state: CreatorState, d: CreatorDesign, pickBackground: () -> Unit) {
    fun up(key: String?, f: (DesignBg) -> DesignBg) = state.set(d.copy(bg = f(d.bg)), key)
    Head("TYPE DE FOND")
    Pills(BgKind.entries.toList(), d.bg.kind, { it.label }) { k -> if (k == BgKind.IMAGE && d.bg.image.isEmpty()) pickBackground() else up(null) { it.copy(kind = k) } }
    if (d.bg.kind == BgKind.IMAGE) {
        Spacer(Modifier.height(8.dp))
        Button(pickBackground, colors = ButtonDefaults.buttonColors(containerColor = CreatorAccent, contentColor = Color.White)) {
            Icon(CxIcons.Image, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (d.bg.image.isEmpty()) "Choisir une photo" else "Changer la photo")
        }
        Text("Assombrir la photo : ${(d.bg.dim * 100).roundToInt()} %", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Slider(d.bg.dim, { v -> up("dim") { it.copy(dim = v) } }, valueRange = 0f..0.8f, colors = SliderDefaults.colors(thumbColor = CreatorAccent, activeTrackColor = CreatorAccent))
    }
    if (d.bg.kind == BgKind.MOTIF) {
        Head("MOTIF")
        Pills(Motif.entries.toList(), d.bg.motif, { OverlayLabels.motif(it) }) { m -> up(null) { it.copy(motif = m) } }
    }
    if (d.bg.kind != BgKind.IMAGE) {
        Head("COULEUR 1")
        Swatches(d.bg.color) { c -> up(null) { it.copy(color = c) } }
        if (d.bg.kind != BgKind.SOLID) {
            Head("COULEUR 2")
            Swatches(d.bg.color2) { c -> up(null) { it.copy(color2 = c) } }
        }
    }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Vignettage (bords assombris)", Modifier.weight(1f), fontSize = 13.sp)
        Switch(d.bg.vignette, { v -> up(null) { it.copy(vignette = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = CreatorAccent))
    }
}

@Composable
private fun ColorsTab(state: CreatorState, d: CreatorDesign, kit: BrandKit?) {
    val pal = d.pal
    if (kit != null) Button({ state.set(d.copy(themeId = kit.overlayThemeId, palette = kit.overlayPalette()), null) }, Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = CreatorAccent, contentColor = Color.White)) { Text("Mettre les couleurs de mon ID Influenceur") }
    OverlayLabels.paletteRoles.forEachIndexed { i, role ->
        val cur = OverlayLabels.get(pal, i)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(Color(cur.toInt())))
            Spacer(Modifier.width(8.dp)); Text(role, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OverlayLabels.swatches.forEach { c ->
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Color(c.toInt()))
                    .border(if (c == cur) 3.dp else 1.dp, if (c == cur) CreatorAccent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .clickable { state.set(d.copy(palette = OverlayLabels.set(pal, i, c)), null) })
            }
        }
    }
    Head("STYLE D'UN THÈME (COULEURS + POLICE)")
    Pills(OverlayThemes.all, OverlayThemes.all.firstOrNull { it.id == d.themeId && d.palette == null }, { it.name }) { t -> state.set(d.copy(themeId = t.id, palette = null), null) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplatesTab(state: CreatorState, d: CreatorDesign, kit: BrandKit?, preview: @Composable (CreatorDesign, Modifier, Boolean) -> Unit) {
    Text("Remplace le contenu par un modèle (annulable avec Annuler).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    Spacer(Modifier.height(8.dp))
    val templates = remember(d.format, kit) { CreatorTemplates.forFormat(d.format).map { it to it.build(d.format, kit) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        templates.forEach { (t, sample) ->
            Column(Modifier.width(150.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f))
                .clickable { state.set(sample.copy(id = d.id, name = d.name), null); state.sel = null }.padding(6.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(d.format.ratio.coerceIn(0.5f, 3f)).clip(RoundedCornerShape(6.dp))) { preview(sample, Modifier.fillMaxSize(), false) }
                Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun ExportTab(d: CreatorDesign, guides: Boolean, setGuides: (Boolean) -> Unit, onExport: () -> Unit) {
    Text("Image PNG en ${d.format.width} × ${d.format.height} px, la taille exacte demandée par ${d.format.platform}.", fontSize = 13.sp)
    Spacer(Modifier.height(10.dp))
    Button(onExport, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = CreatorAccent, contentColor = Color.White)) { Text("Exporter en PNG", fontWeight = FontWeight.Bold) }
    if (d.format.safe != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Afficher la zone sûre (${d.format.safeLabel})", Modifier.weight(1f), fontSize = 13.sp)
        Switch(guides, setGuides, colors = SwitchDefaults.colors(checkedTrackColor = CreatorAccent))
    }
    Text("La zone sûre n'apparaît jamais dans l'image exportée.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp))
}
