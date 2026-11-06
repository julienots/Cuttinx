package com.superessence.cuttinx.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superessence.cuttinx.BuildConfig
import com.superessence.cuttinx.core.model.AspectPreset
import com.superessence.cuttinx.core.smart.Plus
import com.superessence.cuttinx.data.AccentColor
import com.superessence.cuttinx.data.SettingsStore
import com.superessence.cuttinx.data.ThemeMode
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.engine.PreviewQuality
import com.superessence.cuttinx.media.CodecInfo
import com.superessence.cuttinx.ui.components.format
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlin.math.roundToInt

/**
 * Settings, laid out like a system settings page: grouped cards, one line per setting with its
 * current value on the right; choices open in place, sliders sit under their label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, onBack: () -> Unit, openLicenses: () -> Unit) {
    val s by store.state.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val plusV = com.superessence.cuttinx.lab.TechGate.version
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text("Paramètres", fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {

            Group("Apparence") {
                Choice(Icons.Outlined.DarkMode, "Thème", ThemeMode.entries, s.theme, { it.label }) { t -> store.update { it.copy(theme = t) } }
                Divider()
                AccentRow(s.accent) { a -> store.update { it.copy(accent = a) } }
                Divider()
                Toggle(Icons.Outlined.TextFields, "Grandes commandes", "Textes et boutons plus grands", s.largeControls) { v -> store.update { it.copy(largeControls = v) } }
            }

            Group("Utilisation") {
                Toggle(Icons.Outlined.School, "Mode débutant", "Les projets s'ouvrent en « Montage rapide », avec moins d'outils", s.beginnerMode) { v -> store.update { it.copy(beginnerMode = v) } }
                Divider()
                Toggle(Icons.Outlined.Lightbulb, "Astuces", null, s.showTips) { v -> store.update { it.copy(showTips = v) } }
                Divider()
                Toggle(Icons.Outlined.Vibration, "Vibrations", null, s.haptics) { v -> store.update { it.copy(haptics = v) } }
            }

            Group("Montage") {
                Toggle(Icons.Outlined.Straighten, "Magnétisme", "Les clips s'aimantent aux bords, marqueurs et temps", s.snapping) { v -> store.update { it.copy(snapping = v) } }
                Divider()
                Toggle(Icons.Outlined.ViewTimeline, "Piste principale magnétique", "Pas de trou entre les plans de la piste principale", s.magneticMainTrack) { v ->
                    store.update { it.copy(magneticMainTrack = v) }; com.superessence.cuttinx.core.timeline.TimelineEditor.magneticMainTrack = v
                }
                Divider()
                Choice(Icons.Outlined.AspectRatio, "Format des nouveaux projets", AspectPreset.entries.filter { it != AspectPreset.CUSTOM }, s.defaultAspect, { it.label }) { a -> store.update { it.copy(defaultAspect = a) } }
                Divider()
                Slide(Icons.Outlined.Image, "Durée des photos", s.defaultImageDurationMs / 1000f, 0.5f..15f, 0.5f, " s", 1) { v -> store.update { it.copy(defaultImageDurationMs = (v * 1000).roundToInt()) } }
                Divider()
                Slide(Icons.Outlined.SwapHoriz, "Durée des transitions", s.defaultTransitionMs / 1000f, 0.1f..3f, 0.1f, " s", 1) { v -> store.update { it.copy(defaultTransitionMs = (v * 1000).roundToInt()) } }
            }

            Group("Performances") {
                Choice(Icons.Outlined.Visibility, "Qualité de l'aperçu", PreviewQuality.entries, s.previewQuality, { it.label }, hint = "N'affecte pas la qualité de l'export") { q -> store.update { it.copy(previewQuality = q) } }
                Divider()
                Slide(Icons.Outlined.Save, "Sauvegarde automatique", s.autosaveSeconds.toFloat(), 3f..120f, 1f, " s", 0, prefix = "toutes les ") { v -> store.update { it.copy(autosaveSeconds = v.roundToInt()) } }
            }

            Group("Intégrations") {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val apps = remember { com.superessence.cuttinx.ui.components.Integrations.installed(ctx).map { it.first.name } }
                Info(Icons.Outlined.Share, "Publier directement", if (apps.isEmpty()) "Aucune appli de réseau social trouvée sur ce téléphone"
                    else apps.joinToString(", ") + " : un bouton par appli à la fin de l'export")
                Divider()
                Info(Icons.Outlined.Videocam, "Caméra CUTTINX", "Bouton « Filmer » en bas de l'accueil et dans l'éditeur : les prises vont directement dans le montage")
                Divider()
                Info(Icons.Outlined.CloudDownload, "Importer depuis le cloud", "Google Drive, Dropbox, OneDrive… depuis le sélecteur de fichiers (bouton Média)")
                Divider()
                Info(Icons.Outlined.Input, "Ouvrir avec CUTTINX", "Partage une vidéo, une photo ou un son depuis n'importe quelle appli vers CUTTINX")
            }

            Group("Appareil") { Codecs() }

            Group("CUTTINX") {
                val l = Plus.license?.takeIf { Plus.isValid(it) }
                Link(Icons.Outlined.WorkspacePremium, "CUTTINX Plus", if (l != null) "Actif jusqu'au ${Plus.until(l)}" else "Zéro pub et technologies exclusives") { com.superessence.cuttinx.lab.TechGate.openPlus() }
                Divider()
                Link(Icons.Outlined.Description, "Licences et ressources", null, openLicenses)
                Divider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RowIcon(Icons.AutoMirrored.Outlined.HelpOutline)
                    Column(Modifier.weight(1f)) {
                        Text("Version", style = MaterialTheme.typography.bodyLarge)
                        Text("CUTTINX PRO ${BuildConfig.VERSION_NAME} · SuperEssence Creative Studio", style = MaterialTheme.typography.bodySmall, color = LocalCx.current.dim)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val cx = LocalCx.current
    Text(title, Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = cx.dim, fontWeight = FontWeight.SemiBold)
    Column(Modifier.fillMaxWidth().clip(CxDesign.Card).background(cx.panel).border(1.dp, cx.line, CxDesign.Card), content = content)
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 1.dp, color = LocalCx.current.line.copy(alpha = 0.7f))

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(icon, null, Modifier.size(22.dp), tint = LocalCx.current.dim)
    Spacer(Modifier.width(16.dp))
}

@Composable
private fun Toggle(icon: ImageVector, title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cx = LocalCx.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon)
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = cx.dim)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = cx.onAccent, checkedTrackColor = cx.accent, checkedBorderColor = cx.accent,
            uncheckedTrackColor = Color.Transparent, uncheckedBorderColor = cx.line, uncheckedThumbColor = cx.dim))
    }
}

/** One line with the current value; a tap opens the options right under it. */
@Composable
private fun <T> Choice(icon: ImageVector, title: String, options: List<T>, selected: T, label: (T) -> String, hint: String? = null, onSelect: (T) -> Unit) {
    val cx = LocalCx.current
    var open by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = cx.dim)
            }
            Text(label(selected), style = MaterialTheme.typography.bodyMedium, color = cx.dim)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp).rotate(if (open) 90f else 0f), tint = cx.dim)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 52.dp, end = 14.dp, bottom = 8.dp)) {
                for (o in options) {
                    val sel = o == selected
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onSelect(o); open = false }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label(o), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (sel) cx.accent else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        if (sel) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = cx.accent)
                    }
                }
            }
        }
    }
}

/** Accent colour: real swatches, the chosen one ringed. */
@Composable
private fun AccentRow(selected: AccentColor, onSelect: (AccentColor) -> Unit) {
    val cx = LocalCx.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Outlined.Palette)
            Text("Couleur d'accent", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(selected.label, style = MaterialTheme.typography.bodyMedium, color = cx.dim)
        }
        Row(Modifier.padding(start = 38.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (a in AccentColor.entries) {
                val sel = a == selected
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, if (sel) MaterialTheme.colorScheme.onSurface else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onSelect(a) }.padding(4.dp).clip(RoundedCornerShape(5.dp)).background(Color(a.argb)))
            }
        }
    }
}

@Composable
private fun Slide(icon: ImageVector, title: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, unit: String, decimals: Int, prefix: String = "", onChange: (Float) -> Unit) {
    val cx = LocalCx.current
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(prefix + format(value, decimals).replace('.', ',') + unit, style = MaterialTheme.typography.bodyMedium, color = cx.accent, fontWeight = FontWeight.SemiBold)
        }
        val steps = ((range.endInclusive - range.start) / step).roundToInt() - 1
        Slider(value.coerceIn(range), { onChange(((it / step).roundToInt() * step).coerceIn(range)) }, Modifier.padding(start = 38.dp), valueRange = range,
            steps = steps.coerceIn(0, 200).takeIf { it <= 60 } ?: 0,
            colors = SliderDefaults.colors(thumbColor = cx.accent, activeTrackColor = cx.accent, inactiveTrackColor = cx.line, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
    }
}

@Composable
private fun Link(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    val cx = LocalCx.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = cx.dim)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = cx.dim)
    }
}

/** What this phone can decode and export, as a small table. */
@Composable
private fun Codecs() {
    val cx = LocalCx.current
    var open by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Outlined.Memory)
            Column(Modifier.weight(1f)) {
                Text("Formats pris en charge", style = MaterialTheme.typography.bodyLarge)
                Text("Lecture et export sur ce téléphone", style = MaterialTheme.typography.bodySmall, color = cx.dim)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp).rotate(if (open) 90f else 0f), tint = cx.dim)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 52.dp, end = 14.dp, bottom = 12.dp)) {
                Text("Lecture", style = MaterialTheme.typography.labelLarge, color = cx.dim, modifier = Modifier.padding(bottom = 4.dp))
                for ((n, ok) in CodecInfo.decoderSummary()) TableRow(n, if (ok) "Oui" else "Non", ok)
                Text("Export", style = MaterialTheme.typography.labelLarge, color = cx.dim, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                for (c in CodecInfo.encoderCaps()) TableRow(c.codec.label + if (c.hardware) " · matériel" else "", "${c.maxWidth}×${c.maxHeight} · ${c.maxFps} i/s", true)
            }
        }
    }
}

@Composable
private fun TableRow(name: String, value: String, ok: Boolean) {
    val cx = LocalCx.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (ok) MaterialTheme.colorScheme.onSurface else cx.dim)
    }
}

@Composable
private fun Info(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalCx.current.dim)
        }
    }
}
