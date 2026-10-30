package com.superessence.cuttinx.smart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.model.ClipKind
import com.superessence.cuttinx.core.model.MediaType
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.icons.CxIcons

private val Dim = Color.White.copy(alpha = 0.62f)
private val CardBg = Color.White.copy(alpha = 0.06f)

/**
 * "Studio intelligent" of the editor (Android and PC): the plain-French director, the
 * pre-publication quality check with one-click repairs, and the exclusive editing tools.
 * [onEdit] runs one undoable project edit.
 */
@Composable
fun SmartStudioPanel(
    project: Project?,
    selectedClipId: String?,
    accent: Color,
    onEdit: (label: String, op: (Project) -> Project) -> Unit,
    onSeek: (Long) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableIntStateOf(0) }
    var platform by remember { mutableStateOf(Platform.TIKTOK) }
    Column(modifier) {
        Text("Studio intelligent", fontWeight = FontWeight.Black, fontSize = 18.sp)
        Text("Exclusif CUTTINX : un réalisateur qui comprend tes phrases, un contrôle qualité minutieux et des outils de monteur pro, en un clic.", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Pills(listOf("Réalisateur IA", "Contrôle qualité", "Outils exclusifs"), section, accent) { section = it }
        Spacer(Modifier.height(6.dp))
        Text("Publication pour :", fontSize = 12.sp, color = Dim)
        Spacer(Modifier.height(4.dp))
        Pills(Platform.entries.map { it.label }, platform.ordinal, accent) { platform = Platform.entries[it] }
        Spacer(Modifier.height(10.dp))
        if (project == null) { Text("Ouvre un projet pour utiliser le studio.", color = Dim); return@Column }
        when (section) {
            0 -> Director(project, accent, onEdit)
            1 -> Quality(project, platform, accent, onEdit, onSeek)
            else -> Tools(project, platform, selectedClipId, accent, onEdit, onCopy)
        }
    }
}

@Composable
private fun Pills(options: List<String>, selected: Int, accent: Color, onClick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Text(o, Modifier.clip(RoundedCornerShape(50)).background(if (sel) accent else CardBg).clickable { onClick(i) }.padding(horizontal = 12.dp, vertical = 7.dp),
                color = if (sel) Color.Black else Color.White, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun SectionTitle(text: String, accent: Color) = Text(text, Modifier.padding(top = 14.dp, bottom = 6.dp), color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)

// ---------------------------------------------------------------------- director

@Composable
private fun Director(project: Project, accent: Color, onEdit: (String, (Project) -> Project) -> Unit) {
    var prompt by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<DirectorPlan?>(null) }
    var off by remember { mutableStateOf(setOf<Int>()) }
    fun prepare() { plan = SmartDirector.plan(prompt, project); off = emptySet() }
    Text("Écris ce que tu veux comme à un monteur : format, durée, ambiance, rythme, textes, son… Le réalisateur prépare un plan, tu valides, tout est appliqué en une fois (et annulable).", color = Dim, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(prompt, { prompt = it; plan = null }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Ta demande") },
        placeholder = { Text("ex. Optimise pour TikTok en 30 secondes, look cinéma, baisse la musique quand je parle") })
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SmartDirector.examples.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { e ->
                    Text(e, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(CardBg).clickable { prompt = e; prepare() }.padding(8.dp), fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Button({ prepare() }, Modifier.fillMaxWidth(), enabled = prompt.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) {
        Icon(CxIcons.Idea, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Préparer le plan", fontWeight = FontWeight.Bold)
    }
    val pl = plan ?: return
    if (pl.steps.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Je n'ai pas compris cette demande. Essaie avec des mots comme : TikTok, 30 secondes, look cinéma, transitions, sous-titres, musique, boucle, rythme, teaser, nettoie…", color = Color(0xFFFFB020), fontSize = 12.sp)
        return
    }
    SectionTitle("PLAN DU RÉALISATEUR (décoche ce que tu ne veux pas)", accent)
    pl.steps.forEachIndexed { i, s ->
        Row(Modifier.fillMaxWidth().clickable { off = if (i in off) off - i else off + i }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(i !in off, { off = if (i in off) off - i else off + i }, colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = Color.Black))
            Text("${i + 1}. ${s.label}", fontSize = 13.sp)
        }
    }
    val chosen = pl.steps.filterIndexed { i, _ -> i !in off }
    Button({ onEdit("Réalisateur IA : ${chosen.size} étapes") { p -> DirectorPlan(chosen, pl.platform, emptyList()).apply(p) }; plan = null },
        Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) {
        Icon(CxIcons.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Appliquer ${chosen.size} étape(s)", fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------- quality

private fun severityColor(s: Severity) = when (s) { Severity.CRITICAL -> Color(0xFFFF5A5A); Severity.MAJOR -> Color(0xFFFFB020); Severity.MINOR -> Color(0xFF3DB6E0); Severity.TIP -> Color(0xFF2FB67C) }

@Composable
private fun Quality(project: Project, platform: Platform, accent: Color, onEdit: (String, (Project) -> Project) -> Unit, onSeek: (Long) -> Unit) {
    val report = remember(project, platform) { QualityCoach.analyze(project, platform) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val col = when { report.score >= 85 -> Color(0xFF2FB67C); report.score >= 60 -> Color(0xFFFFB020); else -> Color(0xFFFF5A5A) }
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val st = Stroke(10.dp.toPx(), cap = StrokeCap.Round)
                val inset = 6.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.1f), 135f, 270f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = st)
                drawArc(col, 135f, 270f * report.score / 100f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = st)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${report.score}", fontWeight = FontWeight.Black, fontSize = 28.sp)
                Text("/ 100", fontSize = 10.sp, color = Dim)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(report.grade, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = col)
            Text("Pour ${platform.label}", fontSize = 12.sp, color = Dim)
            report.stats.forEach { (k, v) -> Text("$k : $v", fontSize = 11.sp, color = Dim) }
        }
    }
    val fixable = report.items.count { it.fix != null }
    if (fixable > 0) {
        Spacer(Modifier.height(8.dp))
        Button({ onEdit("Contrôle qualité : tout corriger") { QualityCoach.fixAll(it, platform) } }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) {
            Icon(CxIcons.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Tout corriger automatiquement", fontWeight = FontWeight.Bold)
        }
    }
    if (report.items.isEmpty()) { Spacer(Modifier.height(10.dp)); Text("Rien à signaler : ton montage est prêt à publier.", color = Color(0xFF2FB67C), fontWeight = FontWeight.Bold) }
    report.items.forEach { it ->
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it.severity.label.uppercase(), Modifier.clip(RoundedCornerShape(4.dp)).background(severityColor(it.severity).copy(alpha = 0.22f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    color = severityColor(it.severity), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(it.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            Text(it.detail, fontSize = 12.sp, color = Dim, modifier = Modifier.padding(top = 4.dp))
            Row {
                it.fix?.let { f -> TextButton({ onEdit(f.label) { p -> f.apply(p, platform) } }) { Text(f.label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
                it.atUs?.let { t -> TextButton({ onSeek(t) }) { Text("Voir (${SmartTools.clock(t)})", fontSize = 12.sp, color = Dim) } }
            }
        }
    }
}

// ---------------------------------------------------------------------- tools

@Composable
private fun Tool(icon: ImageVector, title: String, sub: String, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(CardBg).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(sub, fontSize = 11.sp, color = Dim, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun Tools(project: Project, platform: Platform, selectedClipId: String?, accent: Color, onEdit: (String, (Project) -> Project) -> Unit, onCopy: (String) -> Unit) {
    SectionTitle("DURÉE & FORMAT", accent)
    Text("Raccourcir intelligemment à… (chaque plan perd ses débuts et fins, le cœur de l'action reste)", fontSize = 12.sp, color = Dim)
    Spacer(Modifier.height(4.dp))
    val durations = listOf(15, 30, 45, 60, 90)
    Pills(durations.map { "$it s" }, -1, accent) { i -> onEdit("Raccourcir à ${durations[i]} s") { p -> SmartTools.fitToDuration(p, durations[i] * 1_000_000L) } }
    Spacer(Modifier.height(4.dp))
    Tool(CxIcons.Fit, "Adapter à ${platform.label}", "Format ${platform.aspect.label}, cadre rempli, textes hors des boutons, durée conseillée", accent) { onEdit("Adapter à ${platform.label}") { SmartTools.forPlatform(it, platform) } }
    Tool(CxIcons.Background, "Remplir le cadre", "Supprime toutes les bandes noires", accent) { onEdit("Remplir le cadre") { SmartTools.fillFrame(it) } }

    SectionTitle("SON DE PRO", accent)
    var duck by remember { mutableFloatStateOf(0.25f) }
    Tool(CxIcons.Voice, "Ducking automatique", "La musique baisse à ${(duck * 100).toInt()} % quand quelqu'un parle, remonte entre les phrases (images clés modifiables)", accent) { onEdit("Ducking automatique") { SmartTools.autoDuck(it, duck.toDouble()) } }
    Slider(duck, { duck = it }, valueRange = 0.05f..0.6f, colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
    Tool(CxIcons.NoiseOff, "Anti-clics", "Micro-fondus de 15 ms à chaque coupe son : plus aucun « clic »", accent) { onEdit("Anti-clics") { SmartTools.microFades(it) } }
    Tool(CxIcons.Music, "Musique calée sur la fin", "Coupe la musique avec la vidéo et ajoute un fondu de sortie", accent) { onEdit("Musique calée") { SmartTools.fitMusicToVideo(it) } }

    SectionTitle("RYTHME & ÉNERGIE", accent)
    var bpm by remember { mutableStateOf("120") }
    var every by remember { mutableIntStateOf(2) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(bpm, { bpm = it.filter(Char::isDigit).take(3) }, Modifier.width(110.dp), singleLine = true, label = { Text("BPM") })
        Spacer(Modifier.width(8.dp))
        Pills(listOf(1, 2, 4, 8).map { "coupe / $it temps" }, listOf(1, 2, 4, 8).indexOf(every), accent) { every = listOf(1, 2, 4, 8)[it] }
    }
    Tool(CxIcons.Split, "Montage au tempo", "Coupe les plans longs sur le rythme et alterne le cadrage à chaque coupe", accent) {
        val b = bpm.toDoubleOrNull() ?: 120.0
        onEdit("Montage au tempo") { SmartTools.tempoCut(it, SmartTools.beatsFromBpm(it, b), every) }
    }
    Tool(CxIcons.ZoomIn, "Zooms alternés (jump cuts)", "Les coupes dans une même vidéo deviennent dynamiques et voulues", accent) { onEdit("Zooms alternés") { SmartTools.punchIn(it) } }
    val target = selectedClipId?.let { id -> project.allClips.firstOrNull { it.id == id && it.kind == ClipKind.MEDIA } }
        ?: SmartTools.mainTrack(project)?.clips?.filter { project.asset(it.assetId)?.type == MediaType.VIDEO }?.maxByOrNull { it.durationUs }
    Text("Rampe de vitesse sur ${if (selectedClipId != null && target?.id == selectedClipId) "le clip sélectionné" else "le plan le plus long"} :", fontSize = 12.sp, color = Dim, modifier = Modifier.padding(top = 6.dp))
    Spacer(Modifier.height(4.dp))
    Pills(RampPreset.entries.map { it.label }, -1, accent) { i -> if (target != null) onEdit("Rampe ${RampPreset.entries[i].label}") { SmartTools.speedRamp(it, target.id, RampPreset.entries[i]) } }

    SectionTitle("STRUCTURE VIRALE", accent)
    Tool(CxIcons.Hook, "Teaser du meilleur moment", "Copie l'instant fort au tout début avec un flash et « ATTENDS LA FIN… »", accent) { onEdit("Teaser") { SmartTools.teaser(it) } }
    Tool(CxIcons.Replay, "Boucle parfaite", "La fin se raccorde à l'image d'ouverture : la vidéo se relance sans coupure visible", accent) { onEdit("Boucle parfaite") { SmartTools.perfectLoop(it) } }
    Tool(CxIcons.Transitions, "Fondus au noir début / fin", "Image et son, façon cinéma", accent) { onEdit("Fondus début / fin") { SmartTools.fadeInOut(it) } }

    SectionTitle("TEXTES MINUTIEUX", accent)
    Tool(CxIcons.Captions, "Sous-titres lisibles", "Découpe les textes trop longs en blocs de 2 lignes, durée selon la vitesse de lecture", accent) { onEdit("Sous-titres lisibles") { SmartTools.readableSubtitles(it) } }
    Tool(CxIcons.Eye, "Textes hors des boutons ${platform.label}", "Déplace titres et sous-titres hors des zones cachées par l'interface", accent) { onEdit("Zones sûres ${platform.label}") { SmartTools.safeZones(it, platform) } }
    Tool(CxIcons.Delete, "Nettoyage minutieux", "Fragments d'une image, flashs noirs entre plans, pistes vides, marqueurs en double", accent) { onEdit("Nettoyage minutieux") { SmartTools.cleanUp(it) } }

    SectionTitle("PUBLICATION", accent)
    val chapters = remember(project) { SmartTools.chapters(project) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg).clickable { onCopy(chapters) }.padding(10.dp)) {
        Text("Chapitres YouTube (clic = copier)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("Ajoute des marqueurs nommés ou des titres pour des chapitres précis.", fontSize = 11.sp, color = Dim)
        Spacer(Modifier.height(4.dp))
        Text(chapters, fontSize = 12.sp)
    }
    Spacer(Modifier.height(4.dp))
    Tool(CxIcons.Pro, "Passe magique", "Nettoyage, anti-clics, ducking, textes lisibles et sûrs, musique calée : tout d'un coup", accent) { onEdit("Passe magique") { SmartTools.magic(it, platform) } }
}
