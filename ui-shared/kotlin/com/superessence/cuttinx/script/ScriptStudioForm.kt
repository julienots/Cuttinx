package com.superessence.cuttinx.script

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.create.ScriptStudio
import com.superessence.cuttinx.core.create.ScriptStyle
import com.superessence.cuttinx.core.model.AspectPreset
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.design.cxChip

/** What the user asked the Studio Script for. */
data class ScriptRequest(
    val script: String,
    val style: ScriptStyle,
    val aspect: AspectPreset,
    val music: Boolean,
    val progressBar: Boolean,
    val endCard: Boolean,
)

/** Colours of the host app (the phone and PC apps have their own theme objects). */
data class ScriptColors(val accent: Color, val onAccent: Color, val panel: Color, val line: Color, val text: Color, val dim: Color)

/** Signature colours of each style, used by its card. */
fun styleColors(s: ScriptStyle): List<Color> = when (s) {
    ScriptStyle.MOTIVATION -> listOf(Color(0xFFFFC53D), Color(0xFFFF6A00))
    ScriptStyle.FACTS -> listOf(Color(0xFF6C4BFF), Color(0xFF00D1FF))
    ScriptStyle.STORY -> listOf(Color(0xFF1B2A6B), Color(0xFF7B5CFF))
    ScriptStyle.PROMO -> listOf(Color(0xFFB8C2D0), Color(0xFF3D4A5C))
    ScriptStyle.TECH -> listOf(Color(0xFFFF2BD6), Color(0xFF00E5FF))
    ScriptStyle.CHILL -> listOf(Color(0xFFFF9BEF), Color(0xFF6EC8FF))
    ScriptStyle.LYRICS -> listOf(Color(0xFFFF3D7F), Color(0xFFFFB13D))
}

/**
 * « Studio Script » form, shared by the phone and the PC: the text, the style, the format, the
 * options and a live summary (screens and length). [mediaSlot] holds the platform's picker of
 * photos / videos used as backgrounds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScriptStudioForm(
    colors: ScriptColors,
    busy: Boolean,
    onCreate: (ScriptRequest) -> Unit,
    modifier: Modifier = Modifier,
    mediaSlot: @Composable () -> Unit = {},
) {
    var style by remember { mutableStateOf(ScriptStyle.MOTIVATION) }
    var script by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(AspectPreset.PORTRAIT_9_16) }
    var music by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(true) }
    var endCard by remember { mutableStateOf(true) }
    val scenes = remember(script, style) { if (script.isBlank()) emptyList() else ScriptStudio.scenes(script, style) }
    val total = ScriptStudio.totalUs(scenes) + if (endCard && scenes.isNotEmpty()) ScriptStudio.END_US else 0L

    Column(modifier.verticalScroll(rememberScrollState())) {
        // ---- header
        Box(Modifier.fillMaxWidth().clip(CxDesign.Card).background(Brush.linearGradient(styleColors(style).map { it.copy(alpha = 0.35f) } + Color(0x22000000))).border(1.dp, colors.line, CxDesign.Card).padding(16.dp)) {
            Column {
                Text("ÉCRIS, CUTTINX FILME", color = colors.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text("Chaque phrase devient un écran animé, au rythme de la lecture, avec fond animé ou tes photos, transitions et musique. Une ligne qui commence par # devient un titre de chapitre.",
                    color = colors.text.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        // ---- style
        Text("STYLE", color = colors.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in ScriptStyle.entries) {
                val sel = s == style
                Column(Modifier.width(150.dp).clip(CxDesign.Card).background(colors.panel)
                    .border(if (sel) 2.dp else 1.dp, if (sel) colors.accent else colors.line, CxDesign.Card).clickable { style = s }) {
                    Box(Modifier.fillMaxWidth().height(46.dp).background(Brush.linearGradient(styleColors(s))), contentAlignment = Alignment.CenterStart) {
                        Text(s.label, Modifier.padding(horizontal = 10.dp), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
                    }
                    Text(s.description, Modifier.padding(10.dp), color = colors.dim, fontSize = 11.5.sp, maxLines = 3, lineHeight = 15.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // ---- text
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TON TEXTE", Modifier.weight(1f), color = colors.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            TextButton({ script = ScriptStudio.example(style) }) { Text("Exemple", color = colors.accent) }
            if (script.isNotEmpty()) TextButton({ script = "" }) { Text("Effacer", color = colors.dim) }
        }
        OutlinedTextField(script, { script = it.take(6000) }, Modifier.fillMaxWidth().heightIn(min = 180.dp),
            placeholder = { Text(if (style == ScriptStyle.LYRICS) "Une ligne de paroles par ligne…" else "Titre de ta vidéo\nPuis ton texte, phrase après phrase…") },
            textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = colors.text))
        Spacer(Modifier.height(8.dp))
        // live summary
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("${scenes.size}", "écrans", colors)
            Stat(if (scenes.isEmpty()) "–" else "%d:%02d".format(total / 60_000_000, total / 1_000_000 % 60), "durée", colors)
            Stat("${scenes.count { it.chapter || it.title }}", "titres", colors)
        }
        Spacer(Modifier.height(14.dp))
        // ---- format
        Text("FORMAT", color = colors.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((a, label) in listOf(AspectPreset.PORTRAIT_9_16 to "9:16 TikTok / Reels", AspectPreset.SQUARE_1_1 to "1:1 Carré", AspectPreset.PORTRAIT_4_5 to "4:5 Instagram", AspectPreset.LANDSCAPE_16_9 to "16:9 YouTube")) {
                Text(label, Modifier.cxChip(a == aspect, colors.accent, colors.line) { aspect = a }, color = if (a == aspect) colors.accent else colors.text, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        // ---- backgrounds & options
        Text("FONDS ET OPTIONS", color = colors.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        mediaSlot()
        Option("Musique du style (générée par CUTTINX, libre de droits)", music, colors) { music = it }
        Option("Barre de progression", progress, colors) { progress = it }
        Option("Carte de fin (« Merci d'avoir regardé »)", endCard, colors) { endCard = it }
        Spacer(Modifier.height(16.dp))
        Button({ onCreate(ScriptRequest(script, style, aspect, music, progress, endCard)) }, Modifier.fillMaxWidth().height(52.dp), enabled = scenes.isNotEmpty() && !busy,
            shape = CxDesign.Button, colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)) {
            Text(if (busy) "Création de la vidéo…" else "Créer la vidéo", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Stat(value: String, label: String, colors: ScriptColors) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.panel).border(1.dp, colors.line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(value, color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        Text(label, color = colors.dim, fontSize = 10.5.sp)
    }
}

@Composable
private fun Option(label: String, value: Boolean, colors: ScriptColors, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = colors.text, fontSize = 14.sp)
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent))
    }
}
