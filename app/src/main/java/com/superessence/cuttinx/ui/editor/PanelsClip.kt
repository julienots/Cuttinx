package com.superessence.cuttinx.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.anim.Interpolation
import com.superessence.cuttinx.core.anim.KeyframeEvaluator
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.effects.ParamType
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.util.Time
import com.superessence.cuttinx.ui.components.*
import com.superessence.cuttinx.ui.theme.Cx
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlin.math.ln
import kotlin.math.exp

@UnstableApi
@Composable
fun rememberPos(vm: EditorViewModel): Long = vm.previewState.collectAsStateWithLifecycle().value.positionUs

/** Slider bound to an animatable property: edits the base value or the keyframe under the playhead. */
@UnstableApi
@Composable
fun AnimSlider(vm: EditorViewModel, c: Clip, label: String, prop: String, default: Double, range: ClosedFloatingPointRange<Float>, unit: String = "", decimals: Int = 2, base: (Clip, Double) -> Clip) {
    val pos = rememberPos(vm)
    val rel = (pos - c.startUs).coerceIn(0, c.durationUs)
    val baseValue = when (prop) {
        Prop.POS_X -> c.transform.posX; Prop.POS_Y -> c.transform.posY; Prop.ROTATION -> c.transform.rotation; Prop.OPACITY -> c.transform.opacity
        Prop.SCALE -> 1.0; Prop.VOLUME -> c.audio.volume; Prop.PAN -> c.audio.pan; else -> default
    }
    val v = KeyframeEvaluator.evaluate(c.keyframes[prop], rel, baseValue)
    val hasKf = c.keyframes[prop]?.keyframes?.any { kotlin.math.abs(it.timeUs - rel) < 30_000 } == true
    ValueSlider(label, v.toFloat(), range, { vm.setAnimated(prop, it.toDouble(), base) }, default = default.toFloat(), unit = unit, decimals = decimals,
        onKeyframe = { vm.toggleKeyframe(prop, v) }, hasKeyframe = hasKf)
}

// ---------------------------------------------------------------------- text

@UnstableApi
@Composable
fun TextPanel(vm: EditorViewModel, c: Clip) {
    val tc = c.text ?: return InfoCard("Cet élément n'est pas un texte.")
    var text by remember(c.id) { mutableStateOf(tc.text) }
    OutlinedTextField(text, { text = it; vm.updateClip("Texte", "text_${c.id}") { cl -> cl.copy(text = cl.text!!.copy(text = it), name = it.take(24)) } }, Modifier.fillMaxWidth(), label = { Text("Texte") }, minLines = 2)
    SectionTitle("Police")
    FontChooser(vm, tc.style.fontFamily) { fam -> vm.updateClip("Police") { cl -> cl.copy(text = cl.text!!.copy(style = cl.text!!.style.copy(fontFamily = fam, fontAssetId = null))) } }
    val customFonts = vm.current.media.filter { it.type == MediaType.FONT }
    if (customFonts.isNotEmpty()) ChoiceChips(customFonts, customFonts.firstOrNull { it.id == tc.style.fontAssetId }, { it.displayName }, { f -> vm.updateClip("Police") { cl -> cl.copy(text = cl.text!!.copy(style = cl.text!!.style.copy(fontAssetId = f.id))) } })
    ValueSlider("Taille", tc.style.sizeSp.toFloat(), 8f..200f, { s -> vm.updateClip("Taille", "tsize_${c.id}") { cl -> cl.copy(text = cl.text!!.copy(style = cl.text!!.style.copy(sizeSp = s.toDouble()))) } }, default = 48f, decimals = 0)
    SectionTitle("Durée")
    ValueSlider("Durée", c.durationUs / 1e6f, 0.2f..60f, { vm.setDuration((it * 1e6).toLong()) }, unit = " s", decimals = 1, step = 0.1f)
    Row { TextButton({ vm.panel.value = Panel.TEXT_STYLE }) { Text("Style avancé →") }; TextButton({ vm.panel.value = Panel.TEXT_ANIM }) { Text("Animation →") } }
}

@UnstableApi
@Composable
fun FontChooser(vm: EditorViewModel, current: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.c.resources.fonts.forEach { f ->
            val fam = remember(f.family) { if (f.family.startsWith("asset:")) FontFamily(Font(f.family.removePrefix("asset:"), ctx.assets)) else when (f.family) { "serif" -> FontFamily.Serif; "monospace" -> FontFamily.Monospace; else -> FontFamily.SansSerif } }
            val sel = f.family == current
            Text(f.name, fontFamily = fam, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) LocalCx.current.accent else LocalCx.current.panel2).clickable { onPick(f.family) }.padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (sel) Color.Black else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun Modifier.horizontalScrollCompat() = this.then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()))

@UnstableApi
@Composable
fun TextStylePanel(vm: EditorViewModel, c: Clip) {
    val st = c.text?.style ?: return
    fun up(label: String, key: String? = null, f: (TextStyle) -> TextStyle) = vm.updateSelected(label, key?.let { "${it}_${c.id}" }) { cl -> if (cl.text != null) cl.copy(text = cl.text!!.copy(style = f(cl.text!!.style))) else cl }
    var tab by remember { mutableIntStateOf(0) }
    ChoiceChips(listOf("Couleur", "Contour", "Ombre", "Fond", "Mise en page"), listOf("Couleur", "Contour", "Ombre", "Fond", "Mise en page")[tab], { it }, { tab = listOf("Couleur", "Contour", "Ombre", "Fond", "Mise en page").indexOf(it) })
    when (tab) {
        0 -> {
            ColorPicker(st.color, { col -> up("Couleur", "tcol") { it.copy(color = col) } })
            LabeledSwitch("Dégradé", st.gradientEnabled, { g -> up("Dégradé") { it.copy(gradientEnabled = g) } })
            if (st.gradientEnabled) { SectionTitle("Haut"); ColorPicker(st.gradientStart, { col -> up("Dégradé", "g1") { it.copy(gradientStart = col) } }, false); SectionTitle("Bas"); ColorPicker(st.gradientEnd, { col -> up("Dégradé", "g2") { it.copy(gradientEnd = col) } }, false) }
            Row { LabeledSwitch("Gras", st.bold, { b -> up("Gras") { it.copy(bold = b) } }, Modifier.weight(1f)); LabeledSwitch("Italique", st.italic, { b -> up("Italique") { it.copy(italic = b) } }, Modifier.weight(1f)) }
            LabeledSwitch("Majuscules", st.uppercase, { b -> up("Majuscules") { it.copy(uppercase = b) } })
            ValueSlider("Graisse", st.weight.toFloat(), 100f..900f, { w -> up("Graisse", "tw") { it.copy(weight = (w / 100).toInt() * 100) } }, default = 700f, decimals = 0, step = 100f)
        }
        1 -> { ValueSlider("Épaisseur du contour", st.strokeWidth.toFloat(), 0f..20f, { w -> up("Contour", "sw") { it.copy(strokeWidth = w.toDouble()) } }, default = 0f, decimals = 1); ColorPicker(st.strokeColor, { col -> up("Contour", "sc") { it.copy(strokeColor = col) } }) }
        2 -> {
            ValueSlider("Flou", st.shadowRadius.toFloat(), 0f..40f, { v -> up("Ombre", "sr") { it.copy(shadowRadius = v.toDouble()) } }, default = 6f, decimals = 0)
            ValueSlider("Décalage X", st.shadowDx.toFloat(), -30f..30f, { v -> up("Ombre", "sx") { it.copy(shadowDx = v.toDouble()) } }, default = 0f, decimals = 0)
            ValueSlider("Décalage Y", st.shadowDy.toFloat(), -30f..30f, { v -> up("Ombre", "sy") { it.copy(shadowDy = v.toDouble()) } }, default = 3f, decimals = 0)
            ColorPicker(st.shadowColor, { col -> up("Ombre", "shc") { it.copy(shadowColor = col) } })
        }
        3 -> {
            ColorPicker(st.backgroundColor, { col -> up("Fond", "bgc") { it.copy(backgroundColor = col) } })
            ValueSlider("Marge", st.backgroundPadding.toFloat(), 0f..60f, { v -> up("Fond", "bp") { it.copy(backgroundPadding = v.toDouble()) } }, default = 16f, decimals = 0)
            ValueSlider("Arrondi", st.backgroundCorner.toFloat(), 0f..60f, { v -> up("Fond", "br") { it.copy(backgroundCorner = v.toDouble()) } }, default = 12f, decimals = 0)
        }
        else -> {
            ChoiceChips(TextAlign.entries.toList(), st.align, { when (it) { TextAlign.LEFT -> "Gauche"; TextAlign.CENTER -> "Centre"; TextAlign.RIGHT -> "Droite" } }, { a -> up("Alignement") { it.copy(align = a) } })
            ValueSlider("Espacement des lettres", st.letterSpacing.toFloat(), -0.1f..1f, { v -> up("Espacement", "ls") { it.copy(letterSpacing = v.toDouble()) } }, default = 0f)
            ValueSlider("Interligne", st.lineSpacing.toFloat(), 0.6f..3f, { v -> up("Interligne", "lh") { it.copy(lineSpacing = v.toDouble()) } }, default = 1.1f)
            ValueSlider("Largeur max.", (c.text?.maxWidth ?: 0.9).toFloat(), 0.2f..1f, { v -> vm.updateSelected("Largeur", "mw_${c.id}") { cl -> cl.text?.let { cl.copy(text = it.copy(maxWidth = v.toDouble())) } ?: cl } }, default = 0.9f)
        }
    }
    TextButton({ val s = st; vm.edit("Style appliqué à tous les textes") { p -> com.superessence.cuttinx.core.timeline.TimelineEditor.mapClips(p) { cl -> if (cl.text != null && cl.kind == c.kind) cl.copy(text = cl.text!!.copy(style = s)) else cl } } }) { Text("Appliquer ce style à tous les ${if (c.kind == ClipKind.SUBTITLE) "sous-titres" else "textes"}") }
}

@UnstableApi
@Composable
fun TextAnimPanel(vm: EditorViewModel, c: Clip) {
    val tc = c.text ?: return
    val names = mapOf(TextAnimation.NONE to "Aucune", TextAnimation.FADE to "Fondu", TextAnimation.POP to "Pop", TextAnimation.SLIDE_UP to "Monte", TextAnimation.SLIDE_DOWN to "Descend",
        TextAnimation.SLIDE_LEFT to "Gauche", TextAnimation.SLIDE_RIGHT to "Droite", TextAnimation.TYPEWRITER to "Machine à écrire", TextAnimation.ZOOM to "Zoom", TextAnimation.BOUNCE to "Rebond", TextAnimation.BLUR_IN to "Douceur", TextAnimation.WAVE to "Vague")
    SectionTitle("Apparition")
    ChoiceChips(TextAnimation.entries.toList(), tc.animIn, { names[it]!! }, { a -> vm.updateClip("Animation d'entrée") { it.copy(text = it.text!!.copy(animIn = a)) } })
    ValueSlider("Durée d'apparition", tc.animInUs / 1e6f, 0.05f..3f, { v -> vm.updateClip("Animation", "ain_${c.id}") { it.copy(text = it.text!!.copy(animInUs = (v * 1e6).toLong())) } }, unit = " s", decimals = 2, step = 0.05f)
    SectionTitle("Disparition")
    ChoiceChips(TextAnimation.entries.toList(), tc.animOut, { names[it]!! }, { a -> vm.updateClip("Animation de sortie") { it.copy(text = it.text!!.copy(animOut = a)) } })
    ValueSlider("Durée de disparition", tc.animOutUs / 1e6f, 0.05f..3f, { v -> vm.updateClip("Animation", "aout_${c.id}") { it.copy(text = it.text!!.copy(animOutUs = (v * 1e6).toLong())) } }, unit = " s", decimals = 2, step = 0.05f)
    InfoCard("Pour un déplacement libre, utilisez les images clés (losange) sur la position, l'échelle ou la rotation.")
}

@UnstableApi
@Composable
fun TemplatesPanel(vm: EditorViewModel) {
    val ctx = LocalContext.current
    Text("Titres, génériques et sous-titres prêts à l'emploi — tous modifiables.", color = LocalCx.current.dim, style = MaterialTheme.typography.bodySmall)
    vm.c.resources.titleTemplates.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            row.forEach { t ->
                val fam = remember(t.style.fontFamily) { if (t.style.fontFamily.startsWith("asset:")) FontFamily(Font(t.style.fontFamily.removePrefix("asset:"), ctx.assets)) else FontFamily.SansSerif }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF202026)).clickable { vm.addText(t.sample, t.style, t.animIn, t.animOut) }.padding(10.dp)) {
                    Text(t.sample.lines().first(), fontFamily = fam, color = Color(t.style.color.toInt()), fontSize = 18.sp, maxLines = 1)
                    Text(t.name, style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------------- speed

@UnstableApi
@Composable
fun SpeedPanel(vm: EditorViewModel, c: Clip) {
    var mode by remember(c.id) { mutableIntStateOf(if (c.speedCurve.isEmpty()) 0 else 1) }
    ChoiceChips(listOf("Constante", "Progressive"), listOf("Constante", "Progressive")[mode], { it }, { mode = if (it == "Constante") 0 else 1 })
    if (mode == 0) {
        // logarithmic slider 0.1x..100x
        val lv = ln(c.speed.coerceIn(0.1, 100.0)).toFloat()
        ValueSlider("Vitesse (×%.2f)".format(c.speed), lv, ln(0.1).toFloat()..ln(100.0).toFloat(), { vm.setSpeed(exp(it.toDouble())) }, default = 0f, decimals = 2)
        ChoiceChips(listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 4.0, 8.0), c.speed, { "×$it" }, { vm.setSpeed(it) })
    } else {
        ChoiceChips(SpeedMath.presets.keys.toList(), null, { it }, { vm.setSpeedCurve(SpeedMath.presets[it]!!) })
        SectionTitle("Courbe personnalisée")
        val pts = c.speedCurve.ifEmpty { listOf(SpeedPoint(0.0, 1.0), SpeedPoint(0.25, 1.0), SpeedPoint(0.5, 1.0), SpeedPoint(0.75, 1.0), SpeedPoint(1.0, 1.0)) }
        SpeedCurvePreview(pts)
        pts.forEachIndexed { i, sp ->
            ValueSlider("Point ${i + 1} (%d %%)".format((sp.position * 100).toInt()), ln(sp.speed).toFloat(), ln(0.1).toFloat()..ln(10.0).toFloat(), { v ->
                vm.edit("Courbe de vitesse", "curve_${c.id}") { p -> com.superessence.cuttinx.core.timeline.TimelineEditor.setSpeedCurve(p, c.id, pts.mapIndexed { j, q -> if (j == i) q.copy(speed = exp(v.toDouble())) else q }) }
            }, default = 0f)
        }
        TextButton({ vm.setSpeed(1.0) }) { Text("Supprimer la courbe") }
    }
    LabeledSwitch("Conserver la hauteur du son", c.audio.preservePitch, { b -> vm.updateClip("Hauteur") { it.copy(audio = it.audio.copy(preservePitch = b)) } })
    Text("Durée : ${Time.format(c.durationUs)} (source ${Time.format(c.sourceDurationUs)})", color = LocalCx.current.dim, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun SpeedCurvePreview(pts: List<SpeedPoint>) {
    Canvas(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF18181C))) {
        val path = Path()
        for (i in 0..60) {
            val x = i / 60f
            val s = SpeedMath.curveSpeedAt(pts, x.toDouble())
            val y = 1 - ((ln(s) - ln(0.1)) / (ln(10.0) - ln(0.1))).toFloat()
            if (i == 0) path.moveTo(x * size.width, y * size.height) else path.lineTo(x * size.width, y * size.height)
        }
        val oneY = (1 - ((0 - ln(0.1)) / (ln(10.0) - ln(0.1)))).toFloat() * size.height
        drawLine(Color.Gray, Offset(0f, oneY), Offset(size.width, oneY), 1f)
        drawPath(path, Cx.Yellow, style = Stroke(2.dp.toPx()))
    }
}

// ---------------------------------------------------------------------- transform / crop / blend

@UnstableApi
@Composable
fun TransformPanel(vm: EditorViewModel, p: Project, c: Clip) {
    AnimSlider(vm, c, "Position X", Prop.POS_X, 0.5, -0.5f..1.5f) { cl, v -> cl.copy(transform = cl.transform.copy(posX = v)) }
    AnimSlider(vm, c, "Position Y", Prop.POS_Y, 0.5, -0.5f..1.5f) { cl, v -> cl.copy(transform = cl.transform.copy(posY = v)) }
    AnimSlider(vm, c, "Échelle (animée)", Prop.SCALE, 1.0, 0.05f..5f) { cl, _ -> cl }
    ValueSlider("Échelle de base", c.transform.scaleX.toFloat(), 0.05f..5f, { v -> vm.updateClip("Échelle", "sc_${c.id}") { it.copy(transform = it.transform.copy(scaleX = v.toDouble(), scaleY = v.toDouble())) } }, default = 1f)
    AnimSlider(vm, c, "Rotation", Prop.ROTATION, 0.0, -360f..360f, "°", 0) { cl, v -> cl.copy(transform = cl.transform.copy(rotation = v)) }
    AnimSlider(vm, c, "Opacité", Prop.OPACITY, 1.0, 0f..1f) { cl, v -> cl.copy(transform = cl.transform.copy(opacity = v)) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ vm.updateClip("Miroir horizontal") { it.copy(transform = it.transform.copy(flipH = !it.transform.flipH)) } }) { Icon(Icons.Default.Flip, null); Text(" Horizontal") }
        OutlinedButton({ vm.updateClip("Miroir vertical") { it.copy(transform = it.transform.copy(flipV = !it.transform.flipV)) } }) { Text("Vertical") }
    }
    if (c.text == null) LabeledSwitch("Remplir le cadre (recadrer)", c.transform.fill, { f -> vm.updateClip("Ajustement au cadre") { it.copy(transform = it.transform.copy(fill = f)) } }, description = "Sinon l'image est entière avec des bandes")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton({ vm.updateClip("Rotation 90°") { it.copy(transform = it.transform.copy(rotation = (it.transform.rotation + 90) % 360)) } }) { Text("Pivoter 90°") }
        TextButton({ vm.updateClip("Réinitialiser") { it.copy(transform = Transform(fill = it.transform.fill, crop = it.transform.crop), keyframes = it.keyframes.filterKeys { k -> k !in Prop.transformProps }) } }) { Text("Réinitialiser") }
    }
    SectionTitle("Point d'ancrage")
    ValueSlider("Ancre X", c.transform.anchorX.toFloat(), 0f..1f, { v -> vm.updateClip("Ancre", "ax_${c.id}") { it.copy(transform = it.transform.copy(anchorX = v.toDouble())) } }, default = 0.5f)
    ValueSlider("Ancre Y", c.transform.anchorY.toFloat(), 0f..1f, { v -> vm.updateClip("Ancre", "ay_${c.id}") { it.copy(transform = it.transform.copy(anchorY = v.toDouble())) } }, default = 0.5f)
}

@UnstableApi
@Composable
fun CropPanel(vm: EditorViewModel, c: Clip) {
    val cr = c.transform.crop
    fun up(f: (CropRect) -> CropRect) = vm.updateClip("Recadrer", "crop_${c.id}") { it.copy(transform = it.transform.copy(crop = f(it.transform.crop))) }
    val a = vm.current.asset(c.assetId)
    SectionTitle("Rapport")
    ChoiceChips(listOf("Libre", "1:1", "9:16", "16:9", "4:5", "Projet"), null, { it }, { r ->
        if (a == null || a.width <= 0) return@ChoiceChips
        val target = when (r) { "1:1" -> 1.0; "9:16" -> 9.0 / 16; "16:9" -> 16.0 / 9; "4:5" -> 0.8; "Projet" -> vm.current.format.aspectRatio; else -> null } ?: run { up { CropRect() }; return@ChoiceChips }
        val ar = a.displayWidth.toDouble() / a.displayHeight
        if (ar > target) { val keep = target / ar; val m = (1 - keep) / 2; up { CropRect(m, 0.0, m, 0.0) } } else { val keep = ar / target; val m = (1 - keep) / 2; up { CropRect(0.0, m, 0.0, m) } }
    })
    ValueSlider("Gauche", cr.left.toFloat(), 0f..0.9f, { v -> up { it.copy(left = v.toDouble().coerceAtMost(0.95 - it.right)) } }, default = 0f)
    ValueSlider("Droite", cr.right.toFloat(), 0f..0.9f, { v -> up { it.copy(right = v.toDouble().coerceAtMost(0.95 - it.left)) } }, default = 0f)
    ValueSlider("Haut", cr.top.toFloat(), 0f..0.9f, { v -> up { it.copy(top = v.toDouble().coerceAtMost(0.95 - it.bottom)) } }, default = 0f)
    ValueSlider("Bas", cr.bottom.toFloat(), 0f..0.9f, { v -> up { it.copy(bottom = v.toDouble().coerceAtMost(0.95 - it.top)) } }, default = 0f)
}

@UnstableApi
@Composable
fun BlendPanel(vm: EditorViewModel, c: Clip) {
    AnimSlider(vm, c, "Opacité", Prop.OPACITY, 1.0, 0f..1f) { cl, v -> cl.copy(transform = cl.transform.copy(opacity = v)) }
    InfoCard("Fusion : CUTTINX compose les calques en mode Normal (transparence). Les modes Produit/Écran/Incrustation sont disponibles comme effets sur le calque (Effets → Superposition → Dégradé/Filtre coloré). Un vrai mode de fusion entre calques nécessite un compositeur dédié : prévu dans une prochaine version.")
}

// ---------------------------------------------------------------------- mask / chroma

@UnstableApi
@Composable
fun MaskPanel(vm: EditorViewModel, c: Clip) {
    val m = c.mask
    fun up(key: String, f: (Mask) -> Mask) = vm.updateClip("Masque", "mask_${key}_${c.id}") { it.copy(mask = f(it.mask)) }
    ChoiceChips(MaskShape.entries.toList(), m.shape, { when (it) { MaskShape.NONE -> "Aucun"; MaskShape.RECTANGLE -> "Rectangle"; MaskShape.ELLIPSE -> "Ellipse"; MaskShape.LINEAR -> "Linéaire"; MaskShape.MIRROR -> "Bande" } }, { s -> vm.updateClip("Masque") { it.copy(mask = it.mask.copy(shape = s)) } })
    if (m.shape != MaskShape.NONE) {
        AnimSlider(vm, c, "Centre X", Prop.MASK_X, m.centerX, 0f..1f) { cl, v -> cl.copy(mask = cl.mask.copy(centerX = v)) }
        AnimSlider(vm, c, "Centre Y", Prop.MASK_Y, m.centerY, 0f..1f) { cl, v -> cl.copy(mask = cl.mask.copy(centerY = v)) }
        AnimSlider(vm, c, "Largeur", Prop.MASK_W, m.width, 0f..2f) { cl, v -> cl.copy(mask = cl.mask.copy(width = v)) }
        AnimSlider(vm, c, "Hauteur", Prop.MASK_H, m.height, 0f..2f) { cl, v -> cl.copy(mask = cl.mask.copy(height = v)) }
        ValueSlider("Rotation", m.rotation.toFloat(), -180f..180f, { v -> up("r") { it.copy(rotation = v.toDouble()) } }, default = 0f, unit = "°", decimals = 0)
        AnimSlider(vm, c, "Contour progressif", Prop.MASK_FEATHER, m.feather, 0f..0.5f) { cl, v -> cl.copy(mask = cl.mask.copy(feather = v)) }
        LabeledSwitch("Inverser", m.invert, { b -> up("i") { it.copy(invert = b) } })
        InfoCard("Les losanges animent le masque dans le temps (suivi manuel d'un objet, révélation progressive…).")
    }
}

@UnstableApi
@Composable
fun ChromaPanel(vm: EditorViewModel, c: Clip) {
    val k = c.chromaKey
    fun up(key: String, f: (ChromaKey) -> ChromaKey) = vm.updateClip("Incrustation", "ck_${key}_${c.id}") { it.copy(chromaKey = f(it.chromaKey)) }
    LabeledSwitch("Incrustation (fond vert/bleu)", k.enabled, { b -> vm.updateClip("Incrustation") { it.copy(chromaKey = it.chromaKey.copy(enabled = b)) } })
    if (k.enabled) {
        ChoiceChips(listOf("Vert" to 0x00FF00L, "Bleu" to 0x0047BBL, "Vert studio" to 0x00B140L, "Magenta" to 0xFF00FFL), null, { it.first }, { pr -> up("c") { it.copy(color = pr.second) } })
        ColorPicker(0xFF000000 or k.color, { col -> up("col") { it.copy(color = col and 0xFFFFFF) } }, withAlpha = false)
        ValueSlider("Tolérance", k.tolerance.toFloat(), 0f..1f, { v -> up("t") { it.copy(tolerance = v.toDouble()) } }, default = 0.35f)
        ValueSlider("Douceur des bords", k.softness.toFloat(), 0f..1f, { v -> up("s") { it.copy(softness = v.toDouble()) } }, default = 0.1f)
        ValueSlider("Suppression des franges", k.spill.toFloat(), 0f..1f, { v -> up("sp") { it.copy(spill = v.toDouble()) } }, default = 0.5f)
        InfoCard("Placez la vidéo sur fond vert sur une piste au-dessus de la piste principale pour voir le décor à travers.")
    }
}

// ---------------------------------------------------------------------- keyframes

private val propNames = mapOf(Prop.POS_X to "Position X", Prop.POS_Y to "Position Y", Prop.SCALE to "Échelle", Prop.ROTATION to "Rotation", Prop.OPACITY to "Opacité", Prop.VOLUME to "Volume", Prop.PAN to "Balance",
    Prop.MASK_X to "Masque X", Prop.MASK_Y to "Masque Y", Prop.MASK_W to "Masque largeur", Prop.MASK_H to "Masque hauteur", Prop.MASK_FEATHER to "Masque contour")

@UnstableApi
@Composable
fun KeyframesPanel(vm: EditorViewModel, c: Clip) {
    val pos = rememberPos(vm)
    val rel = (pos - c.startUs).coerceIn(0, c.durationUs)
    Text("Placez la tête de lecture puis touchez le losange d'une propriété pour créer une image clé. Modifier une valeur entre deux images clés en crée une nouvelle.", style = MaterialTheme.typography.bodySmall, color = LocalCx.current.dim)
    SectionTitle("Ajouter à ${Time.format(rel)}")
    Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val lt = vm.evaluatedTransform(c)
        listOf(Prop.POS_X to lt.posX, Prop.POS_Y to lt.posY, Prop.SCALE to (KeyframeEvaluator.evaluate(c.keyframes[Prop.SCALE], rel, 1.0)), Prop.ROTATION to lt.rotation, Prop.OPACITY to lt.opacity, Prop.VOLUME to KeyframeEvaluator.evaluate(c.keyframes[Prop.VOLUME], rel, c.audio.volume))
            .forEach { (prop, v) -> AssistChip({ vm.toggleKeyframe(prop, v) }, label = { Text(propNames[prop]!!) }, leadingIcon = { Icon(Icons.Default.Diamond, null, Modifier.size(14.dp), tint = if (vm.keyframeAtPlayhead(prop)) Cx.Yellow else LocalCx.current.dim) }) }
    }
    if (c.keyframes.isEmpty()) return InfoCard("Aucune animation sur cet élément.")
    for ((prop, track) in c.keyframes) {
        val name = propNames[prop] ?: prop.split(":").lastOrNull()?.let { "Effet : $it" } ?: prop
        SectionTitle("$name (${track.keyframes.size})", action = { TextButton({ vm.edit("Effacer les images clés") { p -> com.superessence.cuttinx.core.timeline.TimelineEditor.clearKeyframes(p, c.id, prop) } }) { Text("Effacer") } })
        track.keyframes.forEach { k ->
            var showBezier by remember(k.timeUs, prop) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(LocalCx.current.panel2).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Diamond, null, Modifier.size(14.dp), tint = if (kotlin.math.abs(k.timeUs - rel) < 30_000) Cx.Yellow else Color.White)
                    Text(" ${Time.format(k.timeUs)}  →  %.3f".format(k.value), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton({ vm.preview.seekTo(c.startUs + k.timeUs) }, Modifier.size(30.dp)) { Icon(Icons.Default.MyLocation, "Aller", Modifier.size(16.dp)) }
                    IconButton({ vm.moveKeyframe(prop, k.timeUs, rel) }, Modifier.size(30.dp)) { Icon(Icons.Default.PinDrop, "Déplacer ici", Modifier.size(16.dp)) }
                    IconButton({ vm.edit("Supprimer l'image clé") { p -> com.superessence.cuttinx.core.timeline.TimelineEditor.removeKeyframe(p, c.id, prop, k.timeUs) } }, Modifier.size(30.dp)) { Icon(Icons.Default.Delete, "Supprimer", Modifier.size(16.dp)) }
                }
                ChoiceChips(listOf(EasingType.LINEAR, EasingType.EASE_IN_OUT, EasingType.EASE_IN, EasingType.EASE_OUT, EasingType.HOLD, EasingType.BEZIER), k.easing.type,
                    { when (it) { EasingType.LINEAR -> "Linéaire"; EasingType.EASE_IN_OUT -> "Douce"; EasingType.EASE_IN -> "Accélère"; EasingType.EASE_OUT -> "Ralentit"; EasingType.HOLD -> "Maintien"; EasingType.BEZIER -> "Bézier" } },
                    { t -> vm.setKeyframeEasing(prop, k.timeUs, k.easing.copy(type = t)); if (t == EasingType.BEZIER) showBezier = true })
                if (k.easing.type == EasingType.BEZIER || showBezier) BezierEditor(k.easing) { e -> vm.setKeyframeEasing(prop, k.timeUs, e) }
            }
        }
    }
    val selCount = vm.selection.collectAsStateWithLifecycle().value.size
    if (selCount > 1) TextButton({ vm.copyKeyframesToSelection() }) { Text("Copier ces animations vers les autres éléments sélectionnés") }
}

@Composable
fun BezierEditor(e: Easing, onChange: (Easing) -> Unit) {
    Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF16161A))) {
        val path = Path()
        for (i in 0..50) { val t = i / 50.0; val y = Interpolation.cubicBezier(e.x1, e.y1, e.x2, e.y2, t); val px = (t * size.width).toFloat(); val py = ((1 - y) * size.height * 0.8 + size.height * 0.1).toFloat(); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
        drawPath(path, Cx.Yellow, style = Stroke(2.dp.toPx()))
        fun pt(x: Double, y: Double) = Offset((x * size.width).toFloat(), ((1 - y) * size.height * 0.8 + size.height * 0.1).toFloat())
        drawLine(Color.Gray, pt(0.0, 0.0), pt(e.x1, e.y1)); drawLine(Color.Gray, pt(1.0, 1.0), pt(e.x2, e.y2))
        drawCircle(Color.White, 5.dp.toPx(), pt(e.x1, e.y1)); drawCircle(Color.White, 5.dp.toPx(), pt(e.x2, e.y2))
    }
    ValueSlider("x1", e.x1.toFloat(), 0f..1f, { onChange(e.copy(x1 = it.toDouble())) })
    ValueSlider("y1", e.y1.toFloat(), -1f..2f, { onChange(e.copy(y1 = it.toDouble())) })
    ValueSlider("x2", e.x2.toFloat(), 0f..1f, { onChange(e.copy(x2 = it.toDouble())) })
    ValueSlider("y2", e.y2.toFloat(), -1f..2f, { onChange(e.copy(y2 = it.toDouble())) })
}

// ---------------------------------------------------------------------- transitions

@UnstableApi
@Composable
fun TransitionPanel(vm: EditorViewModel, p: Project, c: Clip) {
    val track = p.findClip(c.id)?.first ?: return
    val prev = com.superessence.cuttinx.core.timeline.TimelineEditor.previousClip(track.sortedClips(), c)
    if (prev == null) return InfoCard("Une transition se place entre deux éléments qui se suivent. Sélectionnez le second élément (ou touchez le carré blanc entre deux plans).")
    val cur = c.transitionIn
    Text("Entre « ${prev.name} » et « ${c.name} »", style = MaterialTheme.typography.bodySmall, color = LocalCx.current.dim)
    val defs = TransitionRegistry.all
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        (listOf(null) + defs).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { d ->
                    val sel = d?.id == cur?.transitionId
                    Text(d?.name ?: "Aucune", Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) LocalCx.current.accent else LocalCx.current.panel2).border(1.dp, if (sel) LocalCx.current.accent else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { vm.setTransition(c.id, d?.id) }.padding(vertical = 12.dp, horizontal = 6.dp), color = if (sel) Color.Black else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (cur != null) {
        val maxD = minOf(prev.durationUs, c.durationUs + cur.durationUs) / 2
        ValueSlider("Durée", cur.durationUs / 1e6f, 0.1f..maxOf(0.2f, maxD / 1e6f), { v -> vm.setTransition(c.id, cur.transitionId, (v * 1e6).toLong()) }, unit = " s", decimals = 2, step = 0.05f)
        TransitionRegistry[cur.transitionId]?.params?.forEach { pd ->
            val v = (cur.params[pd.key] ?: pd.default).toFloat()
            if (pd.type == ParamType.CHOICE) ChoiceChips(pd.choices.indices.toList(), v.toInt(), { pd.choices[it] }, { i -> vm.setTransition(c.id, cur.transitionId, params = cur.params + (pd.key to i.toDouble())) })
            else ValueSlider(pd.label, v, pd.min.toFloat()..pd.max.toFloat(), { nv -> vm.setTransition(c.id, cur.transitionId, params = cur.params + (pd.key to nv.toDouble())) }, default = pd.default.toFloat())
        }
        ChoiceChips(listOf(EasingType.EASE_IN_OUT, EasingType.LINEAR, EasingType.EASE_IN, EasingType.EASE_OUT), cur.easing.type, { when (it) { EasingType.LINEAR -> "Linéaire"; EasingType.EASE_IN -> "Accélère"; EasingType.EASE_OUT -> "Ralentit"; else -> "Douce" } },
            { t -> vm.edit("Courbe de transition") { pr -> com.superessence.cuttinx.core.timeline.TimelineEditor.updateClip(pr, c.id) { it.copy(transitionIn = it.transitionIn?.copy(easing = Easing(t))) } } })
    }
    TextButton({ vm.setTransitionAll(cur?.transitionId ?: "fade") }) { Text("Appliquer à toutes les coupes de la piste principale") }
}
