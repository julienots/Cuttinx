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
import com.superessence.cuttinx.desktop.render.OverlayRenderer
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

enum class Section(val label: String, val icon: String) { TWITCH("Twitch Studio", "📺"), BRAND("Identité & DA", "🎨"), GUIDE("Guide", "📖") }

/** State shared by the sections (the brand kit can feed the overlay generator). */
class AppState {
    var section by mutableStateOf(Section.TWITCH)
    var config by mutableStateOf(OverlayConfig())
    var brandKit by mutableStateOf<BrandKit?>(null)
    val renderer = OverlayRenderer()
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
        Row(Modifier.fillMaxSize().background(Cx.Bg)) {
            // navigation
            Column(Modifier.width(96.dp).fillMaxHeight().background(Cx.Panel).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CX", color = Cx.Yellow, fontWeight = FontWeight.Black, fontSize = 26.sp)
                Text("PRO", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Cx.Yellow).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.height(24.dp))
                Section.entries.forEach { s ->
                    val sel = s == state.section
                    Column(Modifier.padding(vertical = 6.dp).width(80.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) Cx.Panel2 else Color.Transparent)
                        .clickable { state.section = s }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.icon, fontSize = 22.sp)
                        Text(s.label, fontSize = 11.sp, color = if (sel) Cx.Yellow else Cx.Dim, maxLines = 2, lineHeight = 13.sp)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (state.section) {
                    Section.TWITCH -> TwitchStudio(state)
                    Section.BRAND -> BrandStudio(state)
                    Section.GUIDE -> GuideScreen()
                }
            }
        }
    }
}
