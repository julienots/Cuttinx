package com.superessence.cuttinx.desktop

import com.superessence.cuttinx.icons.CxIcons
import androidx.compose.foundation.background
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
    EDITOR("Éditeur", { CxIcons.Editor }, Color(0xFFFFCC1F)), TWITCH("Twitch Editor", { CxIcons.Twitch }, Color(0xFF3D8BFF)), BRAND("ID Influenceur", { CxIcons.Identity }, Color(0xFFFF4FA3)), GUIDE("Guide", { CxIcons.Help }, Color(0xFF9AA0AC)),
}

/** Slanted card echoing the big « / » separators. */
private val SlantShape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    val d = size.height * 0.22f
    moveTo(d, 0f); lineTo(size.width, 0f); lineTo(size.width - d, size.height); lineTo(0f, size.height); close()
}

/** Éditeur / Twitch Editor / ID Influenceur: big, separated by « / », the chosen one grows with a spring. */
@Composable
private fun SpacesBar(selected: Section, onSelect: (Section) -> Unit, modifier: Modifier) {
    Row(modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Section.EDITOR, Section.TWITCH, Section.BRAND).forEachIndexed { i, s ->
            if (i > 0) Text("/", Modifier.padding(horizontal = 4.dp), fontSize = 46.sp, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color.White.copy(alpha = 0.3f))
            val sel = s == selected
            val weight by androidx.compose.animation.core.animateFloatAsState(if (sel) 1.8f else 1f, androidx.compose.animation.core.spring(0.6f, 240f), label = "w")
            val scale by androidx.compose.animation.core.animateFloatAsState(if (sel) 1.1f else 0.92f, androidx.compose.animation.core.spring(0.42f, 320f), label = "s")
            val alpha by androidx.compose.animation.core.animateFloatAsState(if (sel) 1f else 0.6f, label = "a")
            val bounce = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(sel) { if (sel) { bounce.snapTo(-18f); bounce.animateTo(0f, androidx.compose.animation.core.spring(0.25f, 220f)) } }
            val bg = if (sel) androidx.compose.ui.graphics.Brush.linearGradient(listOf(s.color, s.color.copy(alpha = 0.6f))) else androidx.compose.ui.graphics.SolidColor(Cx.Panel2)
            Box(Modifier.weight(weight).fillMaxHeight().clip(SlantShape).background(bg).clickable { onSelect(s) }, contentAlignment = Alignment.Center) {
                Row(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(s.icon(), null, Modifier.size(28.dp).graphicsLayer { rotationZ = bounce.value; translationY = bounce.value * 0.6f },
                        tint = if (sel) (if (s == Section.EDITOR) Color.Black else Color.White) else Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(s.label, fontWeight = FontWeight.Black, fontSize = if (sel) 20.sp else 15.sp, maxLines = 1,
                        color = if (sel) (if (s == Section.EDITOR) Color.Black else Color.White) else Color.White)
                }
            }
        }
    }
}

/** State shared by the sections (the brand kit can feed the overlay generator). */
class AppState {
    var section by mutableStateOf(Section.EDITOR)
    var config by mutableStateOf(OverlayConfig())
    var brandKit by mutableStateOf<BrandKit?>(null)
    val renderer = OverlayRenderer()
    val raster = SkiaRaster()
    val fonts get() = raster.fonts
    val theme get() = OverlayThemes[config.themeId]
    /** The PC video editor (created on first use: it starts the preview threads). */
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

    fun shutdown() { if (editorLazy.isInitialized()) runCatching { editor.saveNow(); editor.player.close() } }

    /** Keyboard shortcuts of the editor (only when no text field is using the key). */
    fun onKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (section != Section.EDITOR || e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
        val m = editor
        if (m.current == null) return false
        val ctrl = e.isCtrlPressed || e.isMetaPressed
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
            e.key == Key.Escape -> m.select(null)
            else -> return false
        }
        return true
    }

    /** Applies a generated identity to the overlays: palette, theme and channel name. */
    fun useBrand(kit: BrandKit) {
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
    MaterialTheme(colorScheme = scheme) {
        Column(Modifier.fillMaxSize().background(Cx.Bg)) {
            // top: logo + the three spaces facing the user + guide
            Row(Modifier.fillMaxWidth().background(Cx.Panel).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CUTTINX", color = Cx.Yellow, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 2.sp)
                Spacer(Modifier.width(6.dp))
                Text("PRO", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Cx.Yellow).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(28.dp))
                SpacesBar(state.section, { state.section = it }, Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if (state.section == Section.GUIDE) Cx.Panel2 else Color.Transparent).clickable { state.section = Section.GUIDE }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(CxIcons.Help, null, Modifier.size(18.dp), tint = Cx.Dim); Spacer(Modifier.width(6.dp)); Text("Guide & tutos", color = Cx.Dim)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val accent = state.section.color.takeIf { state.section != Section.GUIDE } ?: Cx.Yellow
                MaterialTheme(colorScheme = scheme.copy(primary = accent, secondary = accent, onPrimary = if (accent == Cx.Yellow) Color.Black else Color.White)) {
                androidx.compose.runtime.CompositionLocalProvider(com.superessence.cuttinx.desktop.ui.LocalAccent provides accent) {
                androidx.compose.animation.Crossfade(state.section, label = "space") { sec ->
                when (sec) {
                    Section.EDITOR -> com.superessence.cuttinx.desktop.editor.EditorSpace(state)
                    Section.TWITCH -> TwitchStudio(state)
                    Section.BRAND -> BrandStudio(state)
                    Section.GUIDE -> GuideScreen(state)
                }
                }
                }
                }
            }
        }
    }
}
