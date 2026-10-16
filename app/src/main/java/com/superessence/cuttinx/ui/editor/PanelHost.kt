package com.superessence.cuttinx.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.model.Clip
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.model.Track
import com.superessence.cuttinx.ui.components.InfoCard
import com.superessence.cuttinx.ui.theme.LocalCx

/** Bottom panel container: header + scrollable content. */
@UnstableApi
@Composable
fun PanelHost(vm: EditorViewModel, panel: Panel, p: Project, sel: Clip?, track: Track?, openProject: (String) -> Unit) {
    val cx = LocalCx.current
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    Column(Modifier.fillMaxWidth().background(cx.panel).heightIn(max = maxH)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.panel.value = null }) { Icon(Icons.Default.Close, "Fermer") }
            Text(panel.title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
            IconButton({ vm.panel.value = null }) { Icon(Icons.Default.Check, "Valider", tint = cx.accent) }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 14.dp)) {
            val needsClip = isClipPanel(panel) || panel == Panel.ADJUST
            if (needsClip && sel == null) InfoCard("Sélectionnez d'abord un élément dans la timeline.")
            else when (panel) {
                Panel.TEXT -> TextPanel(vm, sel!!)
                Panel.TEXT_STYLE -> TextStylePanel(vm, sel!!)
                Panel.TEXT_ANIM -> TextAnimPanel(vm, sel!!)
                Panel.TEMPLATES -> TemplatesPanel(vm)
                Panel.SPEED -> SpeedPanel(vm, sel!!)
                Panel.TRANSFORM -> TransformPanel(vm, p, sel!!)
                Panel.CROP -> CropPanel(vm, sel!!)
                Panel.BLEND -> BlendPanel(vm, sel!!)
                Panel.MASK -> MaskPanel(vm, sel!!)
                Panel.CHROMA -> ChromaPanel(vm, sel!!)
                Panel.KEYFRAMES -> KeyframesPanel(vm, sel!!)
                Panel.TRANSITION -> TransitionPanel(vm, p, sel!!)
                Panel.EFFECTS -> EffectsPanel(vm, sel)
                Panel.FILTERS -> FiltersPanel(vm, sel)
                Panel.ADJUST -> AdjustPanel(vm, sel!!)
                Panel.ADJUST_LAYER -> AdjustLayerPanel(vm)
                Panel.AUDIO -> AudioPanel(vm, p, sel!!)
                Panel.MIXER -> MixerPanel(vm, p)
                Panel.MEDIA -> MediaPanel(vm, p)
                Panel.MUSIC -> MusicPanel(vm)
                Panel.STICKERS -> StickersPanel(vm)
                Panel.BACKGROUND -> BackgroundPanel(vm, p)
                Panel.FORMAT -> FormatPanel(vm, p)
                Panel.VOICE -> VoicePanel(vm)
                Panel.TRACKS -> TracksPanel(vm, p)
                Panel.MARKERS -> MarkersPanel(vm, p)
                Panel.HISTORY -> HistoryPanel(vm)
                Panel.SUBTITLES -> SubtitlesPanel(vm, p)
                Panel.AI -> AiPanel(vm, p, sel, openProject)
                Panel.EXPORT -> ExportPanel(vm, p)
                Panel.PRESETS -> FiltersPanel(vm, sel)
            }
        }
    }
}
