package com.superessence.cuttinx.desktop

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
enum class Section(val label: String, val icon: String, val color: Color) {
    EDITOR("Éditeur", "🎬", Color(0xFFFFCC1F)), TWITCH("Twitch Editor", "📺", Color(0xFF3D8BFF)), BRAND("ID Influenceur", "✨", Color(0xFFFF4FA3)), GUIDE("Guide", "📖", Color(0xFF9AA0AC)),
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
fun App() {
    val state = remember { AppState() }
    MaterialTheme(colorScheme = scheme) {
        Column(Modifier.fillMaxSize().background(Cx.Bg)) {
            // top: logo + the three spaces facing the user + guide
            Row(Modifier.fillMaxWidth().background(Cx.Panel).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CUTTINX", color = Cx.Yellow, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 2.sp)
                Spacer(Modifier.width(6.dp))
                Text("PRO", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Cx.Yellow).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(28.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(Section.EDITOR, Section.TWITCH, Section.BRAND).forEach { s ->
                        val sel = s == state.section
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (sel) s.color else Cx.Panel2).clickable { state.section = s }.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text(s.icon, fontSize = 20.sp); Spacer(Modifier.width(8.dp))
                            Text(s.label, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (sel) Color.Black else Color.White)
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text("? Guide", Modifier.clip(RoundedCornerShape(12.dp)).background(if (state.section == Section.GUIDE) Cx.Panel2 else Color.Transparent).clickable { state.section = Section.GUIDE }.padding(horizontal = 12.dp, vertical = 8.dp), color = Cx.Dim)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val accent = state.section.color.takeIf { state.section != Section.GUIDE } ?: Cx.Yellow
                MaterialTheme(colorScheme = scheme.copy(primary = accent, secondary = accent, onPrimary = if (accent == Cx.Yellow) Color.Black else Color.White)) {
                androidx.compose.runtime.CompositionLocalProvider(com.superessence.cuttinx.desktop.ui.LocalAccent provides accent) {
                when (state.section) {
                    Section.EDITOR -> com.superessence.cuttinx.desktop.editor.EditorSpace(state)
                    Section.TWITCH -> TwitchStudio(state)
                    Section.BRAND -> BrandStudio(state)
                    Section.GUIDE -> GuideScreen()
                }
                }
                }
            }
        }
    }
}
