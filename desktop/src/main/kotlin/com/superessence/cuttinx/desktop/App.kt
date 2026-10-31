package com.superessence.cuttinx.desktop

import com.superessence.cuttinx.motion.spaceTransition
import com.superessence.cuttinx.icons.CxIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.brand.BrandKit
import com.superessence.cuttinx.core.overlay.OverlayConfig
import com.superessence.cuttinx.core.overlay.OverlayThemes
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import com.superessence.cuttinx.desktop.render.SkiaRaster
import com.superessence.cuttinx.desktop.ui.BrandStudio
import com.superessence.cuttinx.desktop.ui.GuideScreen
import com.superessence.cuttinx.desktop.ui.TwitchStudio

/** Brand colours of CUTTINX (same as the Android app). */
object Cx {
    val Yellow = Color(0xFFFFCC1F)
    val Bg = Color(0xFF0E0F12)
    val Panel = Color(0xFF17181D)
    val Panel2 = Color(0xFF22242B)
    val Line = Color(0xFF2E3038)
    val Dim = Color(0xFF9AA0AC)
}

/** The three spaces of CUTTINX PRO (same as the Android home screen) + the guide. */
enum class Section(val label: String, val icon: () -> androidx.compose.ui.graphics.vector.ImageVector, val color: Color) {
    EDITOR("Éditeur", { CxIcons.Editor }, Color(0xFFFFCC1F)), TWITCH("Twitch Editor", { CxIcons.Twitch }, Color(0xFF3D8BFF)), BRAND("ID Influenceur", { CxIcons.Identity }, Color(0xFFFF4FA3)),
    CREATOR("Studio Créateur", { CxIcons.Studio }, Color(0xFFFF5A36)), GUIDE("Guide", { CxIcons.Help }, Color(0xFF9AA0AC)),
}

/** The four spaces as a flat tab bar: icon and name, thin « / » separators, a short bar in the colour of the open space. */
@Composable
private fun SpacesBar(selected: Section, onSelect: (Section) -> Unit, modifier: Modifier) {
    Row(modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Section.EDITOR, Section.TWITCH, Section.BRAND, Section.CREATOR).forEachIndexed { i, s ->
            if (i > 0) Text("/", Modifier.padding(horizontal = 6.dp), fontSize = 26.sp, fontWeight = FontWeight.Light, color = Color.White.copy(alpha = 0.22f))
            val sel = s == selected
            val bar by androidx.compose.animation.core.animateFloatAsState(if (sel) 1f else 0f, androidx.compose.animation.core.tween(220), label = "bar")
            val tone by androidx.compose.animation.animateColorAsState(if (sel) Color.White else Color.White.copy(alpha = 0.55f), androidx.compose.animation.core.tween(180), label = "tone")
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).clickable { onSelect(s) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(s.icon(), null, Modifier.size(20.dp), tint = if (sel) s.color else tone)
                    Spacer(Modifier.width(8.dp))
                    Text(s.label, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp, maxLines = 1, softWrap = false, color = tone)
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.4f * bar).height(2.dp).background(s.color))
            }
        }
    }
}

/** State shared by the sections (the brand kit can feed the overlay generator). */
class AppState {
    var section by mutableStateOf(Section.EDITOR)

    // ------------------------------------------------------------------ Twitch Editor: settings saved on disk
    private val twitchDir = java.io.File(java.io.File(System.getProperty("user.home"), "CuttinxPRO"), "twitch")
    private val twitchStore = com.superessence.cuttinx.core.overlay.OverlayStore(twitchDir)
    private val history = com.superessence.cuttinx.core.overlay.UndoHistory<OverlayConfig>()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val saver = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cuttinx-twitch-save").apply { isDaemon = true } }
    private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null
    private var _config by mutableStateOf(runCatching { twitchStore.load() }.getOrNull() ?: OverlayConfig())

    /** Overlay settings; every change is undoable and saved automatically. */
    var config: OverlayConfig
        get() = _config
        set(v) = setConfig(v, null)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    /** Save state shown in the Twitch Editor header. */
    var saveStatus by mutableStateOf(if (twitchStore.exists()) "Enregistré" else "")
    var twitchMessage by mutableStateOf<String?>(null)
    /** Selected element of the Twitch preview, scene being edited, zoom of the preview. */
    var twitchSel by mutableStateOf<com.superessence.cuttinx.twitch.Sel?>(null)
    var twitchScene by mutableStateOf<com.superessence.cuttinx.core.overlay.SceneKind?>(null)
    val stage = com.superessence.cuttinx.twitch.StageState()

    /** Changes the overlay settings; [key] groups quick changes (a drag, a slider) into one undo step. */
    fun setConfig(v: OverlayConfig, key: String?) {
        if (v == _config) return
        history.record(_config, key)
        _config = v
        afterChange()
    }

    /** End of a drag on the preview: the next change starts a new undo step. */
    fun endGesture() = history.cut()

    fun undoTwitch() { history.undo(_config)?.let { _config = it; afterChange() } }
    fun redoTwitch() { history.redo(_config)?.let { _config = it; afterChange() } }

    private fun afterChange() {
        canUndo = history.canUndo; canRedo = history.canRedo
        saveStatus = "Modifications en cours…"
        pendingSave?.cancel(false)
        pendingSave = saver.schedule(Runnable { saveTwitch() }, 600, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Writes the overlay settings now (Ctrl + S, "Enregistrer" button, window closing). */
    fun saveTwitch(): Boolean = synchronized(twitchStore) {
        val c = _config
        runCatching { twitchStore.save(c) }
            .onSuccess { saveStatus = "Enregistré" }
            .onFailure { saveStatus = "Échec de l'enregistrement"; twitchMessage = "Impossible d'enregistrer dans ${twitchDir.absolutePath} : ${it.message}" }
            .isSuccess
    }

    /** Studio Créateur (thumbnails, covers, posts, banners), saved in ~/CuttinxPRO/createur. */
    val creator = com.superessence.cuttinx.creator.CreatorState(java.io.File(java.io.File(System.getProperty("user.home"), "CuttinxPRO"), "createur"))
    /** The launch animation is shown once per start. */
    var intro by mutableStateOf(true)

    private val brandFile = java.io.File(twitchDir, "identite.json")
    var brandKit by mutableStateOf(runCatching { json.decodeFromString(BrandKit.serializer(), brandFile.readText()) }.getOrNull())
        private set

    fun updateBrandKit(k: BrandKit?) {
        brandKit = k
        if (k != null) saver.execute { runCatching { twitchDir.mkdirs(); brandFile.writeText(json.encodeToString(BrandKit.serializer(), k)) } }
    }
    val renderer = OverlayRenderer()
    val raster = SkiaRaster()
    val fonts get() = raster.fonts
    val theme get() = OverlayThemes[config.themeId]
    /** The PC video editor (created on first use: it starts the preview threads). */
    // ------------------------------------------------------------------ crash protection
    val crashLog = com.superessence.cuttinx.core.project.CrashLog(java.io.File(java.io.File(System.getProperty("user.home"), "CuttinxPRO"), "crash"))
    /** Message about a task that failed and was contained (banner at the bottom of the window). */
    var notice by mutableStateOf<String?>(null)
    /** Report of the previous session's crash, shown once at start. */
    var crashReport by mutableStateOf<java.io.File?>(runCatching { crashLog.unseen() }.getOrNull())
    val crashLooping = runCatching { crashLog.looping() }.getOrDefault(false)

    /** Writes the open project to disk right now, from any thread (never waits for the io thread). */
    fun emergencySave() { if (editorLazy.isInitialized()) runCatching { editor.current?.let { editor.store.save(it) } } }

    /**
     * Whatever thread fails (export, decoding, analysis, drawing…), the open project is saved, a report
     * is written and the window stays open with an explanation instead of silently misbehaving.
     */
    private fun installCrashProtection() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashLog.write(t.name, e, "${System.getProperty("os.name")} · Java ${System.getProperty("java.version")}") }
            emergencySave()
            notice = when (e) {
                is OutOfMemoryError -> "Mémoire insuffisante : l'opération a été arrêtée. Ton projet est enregistré. Ferme d'autres programmes ou baisse la résolution d'export."
                else -> "Une opération a échoué (${e.javaClass.simpleName}). Ton projet est enregistré ; si l'affichage se fige, ferme et rouvre CUTTINX."
            }
        }
    }
    init { installCrashProtection() }

    private val editorLazy = lazy { com.superessence.cuttinx.desktop.engine.EditorModel() }
    val editor by editorLazy

    /** Files dropped on the window: media go to the open project, .cuttinx archives are opened. */
    fun onFilesDropped(files: List<java.io.File>): Boolean {
        val archive = files.firstOrNull { it.extension.lowercase() == "cuttinx" }
        if (archive != null) { section = Section.EDITOR; editor.importArchive(archive); return true }
        if (editor.current == null) { editor.message.value = "Ouvre ou crée un projet avant d'importer des médias"; section = Section.EDITOR; return false }
        section = Section.EDITOR
        editor.importFiles(files)
        return true
    }

    fun shutdown() {
        pendingSave?.cancel(false)
        saveTwitch()
        creator.saveNow()
        if (editorLazy.isInitialized()) runCatching { editor.saveNow(); editor.player.close() }
    }

    /** Shortcuts of the Studio Créateur: undo / redo / save, delete, duplicate, arrows, zoom. */
    private fun onCreatorKey(e: androidx.compose.ui.input.key.KeyEvent, ctrl: Boolean): Boolean {
        val c = creator
        val d = c.current ?: return false
        val sel = c.sel as? com.superessence.cuttinx.twitch.Sel.Item
        val step = if (e.isShiftPressed) 0.02f else 0.002f
        fun nudge(dx: Float, dy: Float) { if (sel != null) c.setConfig(d.asConfig().updateItem(sel.id) { com.superessence.cuttinx.core.overlay.OverlayItems.place(it, it.x + dx, it.y + dy * d.format.ratio) }, "nudge:${sel.id}") }
        when {
            ctrl && e.key == Key.Z && e.isShiftPressed -> c.redo()
            ctrl && e.key == Key.Z -> c.undo()
            ctrl && e.key == Key.Y -> c.redo()
            ctrl && e.key == Key.S -> c.saveNow()
            ctrl && e.key == Key.D && sel != null -> d.asConfig().item(sel.id)?.let { val x = com.superessence.cuttinx.core.overlay.OverlayItems.duplicate(it); c.setConfig(com.superessence.cuttinx.core.overlay.OverlayItems.add(d.asConfig(), x), null); c.sel = com.superessence.cuttinx.twitch.Sel.Item(x.id) }
            ctrl && (e.key == Key.Equals || e.key == Key.Plus || e.key == Key.NumPadAdd) -> c.stage.zoomBy(1.25f)
            ctrl && (e.key == Key.Minus || e.key == Key.NumPadSubtract) -> c.stage.zoomBy(0.8f)
            ctrl && e.key == Key.Zero -> c.stage.reset()
            (e.key == Key.Delete || e.key == Key.Backspace) && sel != null -> { c.setConfig(com.superessence.cuttinx.core.overlay.OverlayItems.remove(d.asConfig(), sel.id), null); c.sel = null }
            e.key == Key.Escape -> if (c.sel != null) c.sel = null else c.close()
            e.key == Key.DirectionLeft && sel != null -> nudge(-step, 0f)
            e.key == Key.DirectionRight && sel != null -> nudge(step, 0f)
            e.key == Key.DirectionUp && sel != null -> nudge(0f, -step)
            e.key == Key.DirectionDown && sel != null -> nudge(0f, step)
            else -> return false
        }
        return true
    }

    /** Shortcuts of the Twitch Editor: undo / redo / save, delete, move with the arrows, duplicate, zoom. */
    private fun onTwitchKey(e: androidx.compose.ui.input.key.KeyEvent, ctrl: Boolean): Boolean {
        val scene = twitchScene
        val sel = twitchSel
        val step = if (e.isShiftPressed) 0.02f else 0.002f
        fun nudge(dx: Float, dy: Float) {
            when (sel) {
                is com.superessence.cuttinx.twitch.Sel.Item -> setConfig(config.updateItem(sel.id) { com.superessence.cuttinx.core.overlay.OverlayItems.place(it, it.x + dx, it.y + dy * 16f / 9f) }, "nudge:${sel.id}")
                is com.superessence.cuttinx.twitch.Sel.Zone -> if (scene != null) {
                    val (lc, _) = com.superessence.cuttinx.twitch.twitchLayoutOf(config, scene)
                    setConfig(config.copy(zoneMoves = com.superessence.cuttinx.core.overlay.OverlayLayout.move(lc, scene, sel.kind, dx * lc.width, dy * lc.width).zoneMoves), "znudge:${sel.kind}")
                }
                null -> {}
            }
        }
        when {
            ctrl && e.key == Key.Z && e.isShiftPressed -> redoTwitch()
            ctrl && e.key == Key.Z -> undoTwitch()
            ctrl && e.key == Key.Y -> redoTwitch()
            ctrl && e.key == Key.S -> saveTwitch()
            ctrl && e.key == Key.D -> (sel as? com.superessence.cuttinx.twitch.Sel.Item)?.let { s ->
                config.item(s.id)?.let { val d = com.superessence.cuttinx.core.overlay.OverlayItems.duplicate(it); setConfig(com.superessence.cuttinx.core.overlay.OverlayItems.add(config, d), null); twitchSel = com.superessence.cuttinx.twitch.Sel.Item(d.id) }
            } ?: return false
            ctrl && (e.key == Key.Equals || e.key == Key.Plus || e.key == Key.NumPadAdd) -> stage.zoomBy(1.25f)
            ctrl && (e.key == Key.Minus || e.key == Key.NumPadSubtract) -> stage.zoomBy(0.8f)
            ctrl && e.key == Key.Zero -> stage.reset()
            e.key == Key.Delete || e.key == Key.Backspace -> when (sel) {
                is com.superessence.cuttinx.twitch.Sel.Item -> { setConfig(com.superessence.cuttinx.core.overlay.OverlayItems.remove(config, sel.id), null); twitchSel = null }
                is com.superessence.cuttinx.twitch.Sel.Zone -> if (scene != null) setConfig(com.superessence.cuttinx.core.overlay.OverlayLayout.setHidden(config, scene, sel.kind, true), null)
                null -> return false
            }
            e.key == Key.Escape -> if (sel != null) twitchSel = null else return false
            e.key == Key.DirectionLeft && sel != null -> nudge(-step, 0f)
            e.key == Key.DirectionRight && sel != null -> nudge(step, 0f)
            e.key == Key.DirectionUp && sel != null -> nudge(0f, -step)
            e.key == Key.DirectionDown && sel != null -> nudge(0f, step)
            else -> return false
        }
        return true
    }

    /** Keyboard shortcuts of the editor (only when no text field is using the key). */
    fun onKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        com.superessence.cuttinx.desktop.editor.ctrlHeld = e.isCtrlPressed || e.isMetaPressed
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        // navigation between the spaces, from anywhere
        when {
            ctrl && e.key == Key.One -> { section = Section.EDITOR; return true }
            ctrl && e.key == Key.Two -> { section = Section.TWITCH; return true }
            ctrl && e.key == Key.Three -> { section = Section.BRAND; return true }
            ctrl && e.key == Key.Four -> { section = Section.CREATOR; return true }
            e.key == Key.F1 -> { section = Section.GUIDE; return true }
            ctrl && e.key == Key.Tab -> { section = Section.entries[(section.ordinal + if (e.isShiftPressed) Section.entries.size - 1 else 1) % Section.entries.size]; return true }
        }
        if (section == Section.TWITCH) return onTwitchKey(e, ctrl)
        if (section == Section.CREATOR) return onCreatorKey(e, ctrl)
        if (section != Section.EDITOR) return false
        val m = editor
        if (ctrl && e.key == Key.N) { if (m.current != null) m.close(); return true }
        if (m.current == null) return false
        if (ctrl && e.key == Key.S) { m.saveNow(); m.message.value = "Projet enregistré"; return true }
        if (ctrl && e.key == Key.E) { m.exportRequest.value++; return true }
        val fps = m.current?.format?.frameRate ?: 30
        when {
            e.key == Key.Spacebar -> m.player.toggle()
            ctrl && e.key == Key.Z && e.isShiftPressed -> m.redo()
            ctrl && e.key == Key.Z -> m.undo()
            ctrl && e.key == Key.Y -> m.redo()
            ctrl && e.key == Key.D -> m.duplicateSelection()
            e.key == Key.S && !ctrl -> m.splitAtPlayhead()
            e.key == Key.Delete || e.key == Key.Backspace -> m.deleteSelection(ripple = e.isShiftPressed)
            e.key == Key.DirectionLeft -> m.player.seek(m.playhead - (if (e.isShiftPressed) 1_000_000L else 1_000_000L / fps))
            e.key == Key.DirectionRight -> m.player.seek(m.playhead + (if (e.isShiftPressed) 1_000_000L else 1_000_000L / fps))
            e.key == Key.MoveHome -> m.player.seek(0)
            e.key == Key.MoveEnd -> m.player.seek(m.current?.durationUs ?: 0)
            e.key == Key.M && !ctrl -> m.addMarker()
            e.key == Key.Escape -> if (m.selection.value.isEmpty()) m.close() else m.select(null)
            e.key == Key.DirectionUp -> m.jumpEdit(false)
            e.key == Key.DirectionDown -> m.jumpEdit(true)
            e.key == Key.Tab -> m.selectNext(e.isShiftPressed)
            ctrl && e.key == Key.A -> m.selectAll()
            ctrl && (e.key == Key.Equals || e.key == Key.Plus || e.key == Key.NumPadAdd) -> m.zoomBy(1.25f)
            ctrl && (e.key == Key.Minus || e.key == Key.NumPadSubtract) -> m.zoomBy(0.8f)
            ctrl && e.key == Key.Zero -> m.fitRequest.value++
            ctrl && e.key == Key.W -> m.close()
            else -> return false
        }
        return true
    }

    /** Applies a generated identity to the overlays: palette, theme and channel name. */
    fun useBrand(kit: BrandKit) {
        updateBrandKit(kit)
        config = config.copy(themeId = kit.overlayThemeId, palette = kit.overlayPalette(), channel = kit.brief.name.ifBlank { config.channel }, tagline = kit.taglines.first())
        section = Section.TWITCH
    }
}

private val scheme = darkColorScheme(
    primary = Cx.Yellow, onPrimary = Color.Black, secondary = Cx.Yellow, background = Cx.Bg, surface = Cx.Panel,
    surfaceVariant = Cx.Panel2, onSurface = Color.White, onBackground = Color.White, outline = Cx.Line,
)

@Composable
fun App(state: AppState = remember { AppState() }) {
    val lastSection = remember { arrayOf(state.section) }
    MaterialTheme(colorScheme = scheme) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Cx.Bg)) {
            // top: logo + the three spaces facing the user + guide
            Row(Modifier.fillMaxWidth().background(Cx.Panel).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CUTTINX", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 4.sp)
                Spacer(Modifier.width(8.dp))
                Text("PRO", color = Cx.Dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(28.dp))
                SpacesBar(state.section, { state.section = it }, Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (state.section == Section.GUIDE) Cx.Panel2 else Color.Transparent).clickable { state.section = Section.GUIDE }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(CxIcons.Help, null, Modifier.size(18.dp), tint = Cx.Dim); Spacer(Modifier.width(6.dp)); Text("Guide & tutos", color = Cx.Dim)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val accent = state.section.color.takeIf { state.section != Section.GUIDE } ?: Cx.Yellow
                MaterialTheme(colorScheme = scheme.copy(primary = accent, secondary = accent, onPrimary = if (accent == Cx.Yellow) Color.Black else Color.White)) {
                androidx.compose.runtime.CompositionLocalProvider(com.superessence.cuttinx.desktop.ui.LocalAccent provides accent) {
                // the chosen space slides in from the side of its tab, with a swoosh in its colour
                androidx.compose.animation.AnimatedContent(state.section, Modifier.fillMaxSize(), transitionSpec = {
                    spaceTransition(targetState.ordinal > initialState.ordinal)
                }, label = "space") { sec ->
                when (sec) {
                    Section.EDITOR -> com.superessence.cuttinx.desktop.editor.EditorSpace(state)
                    Section.TWITCH -> TwitchStudio(state)
                    Section.BRAND -> BrandStudio(state)
                    Section.CREATOR -> com.superessence.cuttinx.desktop.ui.CreatorStudio(state)
                    Section.GUIDE -> GuideScreen(state)
                }
                }
                val forward = remember(state.section) { (state.section.ordinal >= lastSection[0].ordinal).also { lastSection[0] = state.section } }
                com.superessence.cuttinx.motion.SpaceSwoosh(state.section, state.section.color, forward)
                }
                }
            }
        }
        // launch animation over the whole window, once per start
        if (state.intro) com.superessence.cuttinx.motion.LaunchIntro(onDone = { state.intro = false })
        // a contained failure: explained, never silent
        state.notice?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(9000); if (state.notice == msg) state.notice = null }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(msg, Modifier.padding(bottom = 24.dp).widthIn(max = 640.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xF2201417)).clickable { state.notice = null }.padding(horizontal = 18.dp, vertical = 12.dp), color = Color.White, fontSize = 13.sp)
            }
        }
        // the app closed unexpectedly last time
        state.crashReport?.takeIf { !state.intro }?.let { f ->
            AlertDialog({ state.crashLog.markSeen(f); state.crashReport = null },
                title = { Text("Fermeture inattendue") },
                text = { Text("CUTTINX s'est arrêté lors de la dernière utilisation. Tes projets sont enregistrés automatiquement dans le dossier CuttinxPRO." + if (state.crashLooping) "\n\nPlusieurs incidents de suite : ferme les autres programmes, évite les très grandes résolutions d'aperçu et exporte en 1080p." else "") },
                confirmButton = { TextButton({ state.crashLog.markSeen(f); state.crashReport = null }) { Text("OK") } },
                dismissButton = { TextButton({ com.superessence.cuttinx.desktop.ui.copyToClipboard(runCatching { f.readText() }.getOrDefault("")); state.notice = "Rapport copié" }) { Text("Copier le rapport") } })
        }
    }
    }
}
