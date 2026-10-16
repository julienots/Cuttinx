package com.superessence.cuttinx.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superessence.cuttinx.core.model.AspectPreset
import com.superessence.cuttinx.data.AccentColor
import com.superessence.cuttinx.data.SettingsStore
import com.superessence.cuttinx.data.ThemeMode
import com.superessence.cuttinx.engine.PreviewQuality
import com.superessence.cuttinx.media.CodecInfo
import com.superessence.cuttinx.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, onBack: () -> Unit, openLicenses: () -> Unit) {
    val s by store.state.collectAsStateWithLifecycle()
    var showCodecs by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Paramètres") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            SectionTitle("Apparence")
            ChoiceChips(ThemeMode.entries.toList(), s.theme, { it.label }, { t -> store.update { it.copy(theme = t) } })
            Spacer(Modifier.height(6.dp))
            ChoiceChips(AccentColor.entries.toList(), s.accent, { it.label }, { a -> store.update { it.copy(accent = a) } })
            LabeledSwitch("Grandes commandes", s.largeControls, { v -> store.update { it.copy(largeControls = v) } }, description = "Textes et boutons plus grands")
            SectionTitle("Mode")
            LabeledSwitch("Mode débutant", s.beginnerMode, { v -> store.update { it.copy(beginnerMode = v) } }, description = "Ouvre les projets en « Montage rapide » avec moins d'outils affichés")
            LabeledSwitch("Astuces", s.showTips, { v -> store.update { it.copy(showTips = v) } })
            SectionTitle("Montage")
            LabeledSwitch("Magnétisme", s.snapping, { v -> store.update { it.copy(snapping = v) } }, description = "Aimante les clips aux bords, marqueurs et temps musicaux")
            LabeledSwitch("Piste principale magnétique", s.magneticMainTrack, { v -> store.update { it.copy(magneticMainTrack = v) }; com.superessence.cuttinx.core.timeline.TimelineEditor.magneticMainTrack = v })
            LabeledSwitch("Vibrations", s.haptics, { v -> store.update { it.copy(haptics = v) } })
            ValueSlider("Durée des images", s.defaultImageDurationMs / 1000f, 0.5f..15f, { v -> store.update { it.copy(defaultImageDurationMs = (v * 1000).toInt()) } }, unit = " s", decimals = 1, step = 0.5f)
            ValueSlider("Durée des transitions", s.defaultTransitionMs / 1000f, 0.1f..3f, { v -> store.update { it.copy(defaultTransitionMs = (v * 1000).toInt()) } }, unit = " s", decimals = 1, step = 0.1f)
            ChoiceChips(AspectPreset.entries.filter { it != AspectPreset.CUSTOM }, s.defaultAspect, { "Format ${it.label}" }, { a -> store.update { it.copy(defaultAspect = a) } })
            SectionTitle("Performances")
            Text("Qualité de l'aperçu (n'affecte pas l'export)")
            ChoiceChips(PreviewQuality.entries.toList(), s.previewQuality, { it.label }, { q -> store.update { it.copy(previewQuality = q) } })
            ValueSlider("Sauvegarde automatique toutes les", s.autosaveSeconds.toFloat(), 3f..120f, { v -> store.update { it.copy(autosaveSeconds = v.toInt()) } }, unit = " s", decimals = 0, step = 1f)
            SectionTitle("Appareil")
            TextButton({ showCodecs = !showCodecs }) { Text(if (showCodecs) "Masquer les formats" else "Formats pris en charge par ce téléphone") }
            if (showCodecs) {
                CodecInfo.decoderSummary().forEach { (n, ok) -> Text("${if (ok) "✓" else "✗"} Décodage $n") }
                CodecInfo.encoderCaps().forEach { c -> Text("✓ Export ${c.codec.label} jusqu'à ${c.maxWidth}×${c.maxHeight}, ${c.maxFps} i/s${if (c.hardware) " (matériel)" else ""}") }
            }
            SectionTitle("À propos")
            Text("CUTTINX — SUPERESSENCE CREATIVE STUDIO. Gratuit, sans abonnement, sans filigrane, sans publicité.")
            TextButton(openLicenses) { Text("Licences et ressources") }
            Spacer(Modifier.height(30.dp))
        }
    }
}
