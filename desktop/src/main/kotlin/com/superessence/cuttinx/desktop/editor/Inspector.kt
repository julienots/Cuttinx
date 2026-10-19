package com.superessence.cuttinx.desktop.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.ParamDef
import com.superessence.cuttinx.core.effects.ParamType
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.desktop.Cx
import com.superessence.cuttinx.desktop.engine.EditorModel
import com.superessence.cuttinx.desktop.ui.Chips
import com.superessence.cuttinx.desktop.ui.Hint
import com.superessence.cuttinx.desktop.ui.LocalAccent
import com.superessence.cuttinx.desktop.ui.Title

private val swatches = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFCC1F, 0xFFFFE14D, 0xFFFF6A00, 0xFFFF3D3D, 0xFFFF3D8B, 0xFFB45CFF, 0xFF3D8BFF, 0xFF3DE0FF, 0xFF2FB67C, 0xFF9CFF3D)

@Composable
fun Inspector(m: EditorModel, modifier: Modifier) {
    val p by m.project.collectAsState()
    val sel by m.selection.collectAsState()
    val pr = p ?: return
    val c = sel.firstOrNull()?.let { pr.findClip(it) }
    Column(modifier.background(Cx.Panel).verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (c == null) { ProjectPanel(m, pr); return@Column }
        val (track, clip) = c
        val asset = pr.asset(clip.assetId)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clip.text?.text?.lines()?.firstOrNull() ?: clip.name.ifBlank { asset?.displayName ?: "Élément" }, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
            if (sel.size > 1) Text("+${sel.size - 1}", color = Cx.Dim)
        }
        Hint("${timecode(clip.startUs, true)} → ${timecode(clip.endUs, true)} · ${"%.2f".format(clip.durationUs / 1e6)} s")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Activé", Modifier.weight(1f), fontSize = 13.sp)
            Switch(clip.enabled, { e -> m.updateClip(if (e) "Activer" else "Désactiver") { it.copy(enabled = e) } })
        }
        val visual = clip.kind != ClipKind.ADJUSTMENT && track.kind != TrackKind.AUDIO
        if (clip.text != null) TextPanel(m, clip)
        if (visual) TransformPanel(m, clip)
        if (clip.kind == ClipKind.MEDIA && asset != null && asset.type != MediaType.IMAGE) SpeedPanel(m, clip)
        if (clip.kind == ClipKind.MEDIA && asset?.hasAudio == true) AudioPanel(m, clip)
        if (clip.kind != ClipKind.TEXT && clip.kind != ClipKind.SUBTITLE) EffectsPanel(m, clip)
        if (visual) MotionPanel(m, clip)
        clip.transitionIn?.let { TransitionPanel(m, clip, it) }
        if (visual && clip.kind == ClipKind.MEDIA) KeyPanel(m, clip)
    }
}

@Composable
private fun Prop(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String = { "%.2f".format(it) }, reset: Float? = null, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp, color = Cx.Dim)
        Text(format(value), fontSize = 12.sp, modifier = if (reset != null) Modifier.clickable { onChange(reset) } else Modifier)
    }
    Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, modifier = Modifier.height(28.dp))
}

@Composable
private fun Swatches(selected: Long?, onPick: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        swatches.forEach { c ->
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(Color(c)).border(if (selected == c) 2.dp else 0.5.dp, if (selected == c) LocalAccent.current else Cx.Line, RoundedCornerShape(5.dp)).clickable { onPick(c) })
        }
    }
}

@Composable
private fun ProjectPanel(m: EditorModel, p: Project) {
    Text("Projet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Hint("Clique sur un élément de la timeline pour le régler. Glisse sur l'aperçu pour le déplacer.")
    Title("FOND")
    Swatches(p.format.backgroundColor) { c -> m.edit("Couleur de fond") { it.copy(format = it.format.copy(backgroundColor = c)) } }
    Title("RACCOURCIS")
    listOf("Espace" to "Lecture / pause", "S" to "Couper à la tête de lecture", "Suppr" to "Supprimer", "Ctrl+D" to "Dupliquer", "Ctrl+Z / Ctrl+Y" to "Annuler / Rétablir", "← →" to "Image précédente / suivante", "Double-clic aperçu" to "Lecture / pause").forEach { (k, v) ->
        Row { Text(k, Modifier.width(120.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LocalAccent.current); Text(v, fontSize = 12.sp) }
    }
    Title("STATISTIQUES")
    Hint("${p.tracks.size} pistes · ${p.allClips.size} éléments · ${p.media.size} médias · ${timecode(p.durationUs)}")
}

@Composable
private fun TransformPanel(m: EditorModel, c: Clip) {
    val t = c.transform
    val id = c.id
    Title("POSITION & TAILLE")
    // values at the playhead: when a property has keyframes, moving the slider edits / adds the keyframe there
    m.player.position.collectAsState().value
    val animated = c.keyframes.isNotEmpty()
    if (animated) Hint("◆ Clip animé : les réglages créent des images clés à la tête de lecture.")
    Prop("Position horizontale", m.valueAt(Prop.POS_X, t.posX).toFloat(), -0.5f..1.5f, { "%.0f %%".format(it * 100) }, 0.5f) { v -> m.setAnimated(Prop.POS_X, v.toDouble()) { cl, x -> cl.copy(transform = cl.transform.copy(posX = x)) } }
    Prop("Position verticale", m.valueAt(Prop.POS_Y, t.posY).toFloat(), -0.5f..1.5f, { "%.0f %%".format(it * 100) }, 0.5f) { v -> m.setAnimated(Prop.POS_Y, v.toDouble()) { cl, x -> cl.copy(transform = cl.transform.copy(posY = x)) } }
    val k = m.valueAt(Prop.SCALE, 1.0)
    Prop("Échelle", (t.scaleX * k).toFloat(), 0.05f..4f, { "%.0f %%".format(it * 100) }, 1f) { v ->
        if (c.keyframes[Prop.SCALE]?.keyframes?.isNotEmpty() == true) m.setAnimated(Prop.SCALE, v / t.scaleX.coerceAtLeast(0.01)) { cl, _ -> cl }
        else m.updateClip("Échelle", "sc_$id") { it.copy(transform = it.transform.copy(scaleX = v.toDouble(), scaleY = v.toDouble())) }
    }
    Prop("Rotation", m.valueAt(Prop.ROTATION, t.rotation).toFloat(), -180f..180f, { "%.0f°".format(it) }, 0f) { v -> m.setAnimated(Prop.ROTATION, v.toDouble()) { cl, x -> cl.copy(transform = cl.transform.copy(rotation = x)) } }
    Prop("Opacité", m.valueAt(Prop.OPACITY, t.opacity).toFloat(), 0f..1f, { "%.0f %%".format(it * 100) }, 1f) { v -> m.setAnimated(Prop.OPACITY, v.toDouble()) { cl, x -> cl.copy(transform = cl.transform.copy(opacity = x)) } }
    if (animated) TextButton({ m.updateClip("Retirer l'animation") { it.copy(keyframes = it.keyframes.filterKeys { k2 -> k2.startsWith("fx:") }) } }) { Text("Retirer toutes les images clés", fontSize = 12.sp) }
    if (c.kind == ClipKind.MEDIA) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Remplir l'écran (recadrer)", Modifier.weight(1f), fontSize = 12.sp)
            Switch(t.fill, { f -> m.updateClip("Remplir") { it.copy(transform = it.transform.copy(fill = f)) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ m.updateClip("Miroir") { it.copy(transform = it.transform.copy(flipH = !it.transform.flipH)) } }) { Text("⇋ Miroir", fontSize = 12.sp) }
            OutlinedButton({ m.updateClip("Réinitialiser") { it.copy(transform = Transform(fill = it.transform.fill)) } }) { Text("Réinitialiser", fontSize = 12.sp) }
        }
        Title("FUSION")
        Chips(BlendMode.entries.toList(), c.blendMode, { when (it) { BlendMode.NORMAL -> "Normal"; BlendMode.ADD -> "Addition"; BlendMode.MULTIPLY -> "Produit"; BlendMode.SCREEN -> "Écran"; BlendMode.OVERLAY -> "Incrustation"; BlendMode.DARKEN -> "Obscurcir"; BlendMode.LIGHTEN -> "Éclaircir"; BlendMode.DIFFERENCE -> "Différence" } }, { b -> m.updateClip("Fusion") { it.copy(blendMode = b) } })
    }
}

@Composable
private fun TextPanel(m: EditorModel, c: Clip) {
    val tc = c.text ?: return
    val st = tc.style
    val id = c.id
    fun style(label: String, key: String? = null, f: (TextStyle) -> TextStyle) = m.updateClip(label, key) { it.copy(text = it.text!!.copy(style = f(it.text!!.style))) }
    Title("TEXTE")
    var value by remember(id) { mutableStateOf(tc.text) }
    OutlinedTextField(value, { v -> value = v; m.updateClip("Texte", "txt_$id") { it.copy(text = it.text!!.copy(text = v), name = v.take(24)) } }, Modifier.fillMaxWidth(), minLines = 2)
    Title("POLICE")
    Chips(EditorModel.fonts, EditorModel.fonts.firstOrNull { it.second == st.fontFamily }, { it.first }, { f -> style("Police") { it.copy(fontFamily = f.second, fontAssetId = null) } })
    Prop("Taille", st.sizeSp.toFloat(), 10f..200f, { "%.0f".format(it) }) { v -> style("Taille", "ts_$id") { it.copy(sizeSp = v.toDouble()) } }
    Text("Couleur", fontSize = 12.sp, color = Cx.Dim)
    Swatches(st.color) { col -> style("Couleur") { it.copy(color = col, gradientEnabled = false) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("MAJUSCULES", Modifier.weight(1f), fontSize = 12.sp); Switch(st.uppercase, { u -> style("Majuscules") { it.copy(uppercase = u) } })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dégradé", Modifier.weight(1f), fontSize = 12.sp); Switch(st.gradientEnabled, { g -> style("Dégradé") { it.copy(gradientEnabled = g) } })
    }
    Chips(TextAlign.entries.toList(), st.align, { when (it) { TextAlign.LEFT -> "Gauche"; TextAlign.CENTER -> "Centre"; TextAlign.RIGHT -> "Droite" } }, { a -> style("Alignement") { it.copy(align = a) } })
    Prop("Contour", st.strokeWidth.toFloat(), 0f..16f, { "%.0f".format(it) }) { v -> style("Contour", "tsw_$id") { it.copy(strokeWidth = v.toDouble()) } }
    if (st.strokeWidth > 0) Swatches(st.strokeColor) { col -> style("Couleur du contour") { it.copy(strokeColor = col) } }
    Prop("Ombre / lueur", st.shadowRadius.toFloat(), 0f..40f, { "%.0f".format(it) }) { v -> style("Ombre", "tsh_$id") { it.copy(shadowRadius = v.toDouble()) } }
    Prop("Espacement des lettres", st.letterSpacing.toFloat(), -0.1f..0.6f) { v -> style("Espacement", "tls_$id") { it.copy(letterSpacing = v.toDouble()) } }
    Text("Fond du texte", fontSize = 12.sp, color = Cx.Dim)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Swatches(if ((st.backgroundColor ushr 24) == 0L) null else st.backgroundColor) { col -> style("Fond du texte") { it.copy(backgroundColor = col) } }
        TextButton({ style("Sans fond") { it.copy(backgroundColor = 0) } }) { Text("Aucun", fontSize = 11.sp) }
    }
    Title("ANIMATION DU TEXTE")
    Text("Entrée", fontSize = 12.sp, color = Cx.Dim)
    Chips(TextAnimation.entries.toList(), tc.animIn, { it.label() }, { a -> m.updateClip("Animation d'entrée") { it.copy(text = it.text!!.copy(animIn = a)) } })
    Text("Sortie", fontSize = 12.sp, color = Cx.Dim)
    Chips(TextAnimation.entries.toList(), tc.animOut, { it.label() }, { a -> m.updateClip("Animation de sortie") { it.copy(text = it.text!!.copy(animOut = a)) } })
    Prop("Durée", (c.durationUs / 1e6).toFloat(), 0.3f..30f, { "%.1f s".format(it) }) { v -> m.edit("Durée", "dur_$id") { TimelineEditor.setDuration(it, id, (v * 1e6).toLong()) } }
}

private fun TextAnimation.label() = when (this) {
    TextAnimation.NONE -> "Aucune"; TextAnimation.FADE -> "Fondu"; TextAnimation.POP -> "Pop"; TextAnimation.SLIDE_UP -> "Glisse ↑"; TextAnimation.SLIDE_DOWN -> "Glisse ↓"
    TextAnimation.SLIDE_LEFT -> "Glisse ←"; TextAnimation.SLIDE_RIGHT -> "Glisse →"; TextAnimation.TYPEWRITER -> "Machine à écrire"; TextAnimation.ZOOM -> "Zoom"; TextAnimation.BOUNCE -> "Rebond"
    TextAnimation.BLUR_IN -> "Mise au point"; TextAnimation.WAVE -> "Vague"; TextAnimation.ELASTIC -> "Élastique"; TextAnimation.DROP -> "Chute"; TextAnimation.SPIN -> "Tourbillon"
    TextAnimation.FLICKER -> "Néon"; TextAnimation.GLITCH -> "Glitch"; TextAnimation.STRETCH -> "Étirement"; TextAnimation.ROLL -> "Roulade"; TextAnimation.RISE -> "Montée"
    TextAnimation.SWING -> "Balancier"; TextAnimation.SHAKE -> "Secousse"; TextAnimation.ZOOM_OUT -> "Zoom arrière"; TextAnimation.KARAOKE -> "Karaoké"
}

@Composable
private fun SpeedPanel(m: EditorModel, c: Clip) {
    Title("VITESSE")
    Prop("Vitesse", c.speed.toFloat(), 0.1f..8f, { "×%.2f".format(it) }, 1f) { v -> m.edit("Vitesse", "spd_${c.id}") { TimelineEditor.setSpeed(it, c.id, v.toDouble()) } }
    Chips(listOf(0.25, 0.5, 1.0, 1.5, 2.0, 4.0), c.speed, { "×$it" }, { s -> m.edit("Vitesse ×$s") { TimelineEditor.setSpeed(it, c.id, s) } })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Garder la hauteur de la voix", Modifier.weight(1f), fontSize = 12.sp)
        Switch(c.audio.preservePitch, { v -> m.updateClip("Hauteur") { it.copy(audio = it.audio.copy(preservePitch = v)) } })
    }
}

@Composable
private fun AudioPanel(m: EditorModel, c: Clip) {
    val id = c.id
    Title("SON")
    Prop("Volume", c.audio.volume.toFloat(), 0f..3f, { "%.0f %%".format(it * 100) }, 1f) { v -> m.updateClip("Volume", "vol_$id") { it.copy(audio = it.audio.copy(volume = v.toDouble())) } }
    val maxFade = (c.durationUs / 2e6).toFloat().coerceAtLeast(0.1f).coerceAtMost(10f)
    Prop("Fondu d'entrée", c.audio.fadeInUs / 1e6f, 0f..maxFade, { "%.1f s".format(it) }, 0f) { v -> m.updateClip("Fondu", "fin_$id") { it.copy(audio = it.audio.copy(fadeInUs = (v * 1e6).toLong())) } }
    Prop("Fondu de sortie", c.audio.fadeOutUs / 1e6f, 0f..maxFade, { "%.1f s".format(it) }, 0f) { v -> m.updateClip("Fondu", "fout_$id") { it.copy(audio = it.audio.copy(fadeOutUs = (v * 1e6).toLong())) } }
    Prop("Balance", c.audio.pan.toFloat(), -1f..1f, { if (it < -0.05) "G %.0f".format(-it * 100) else if (it > 0.05) "D %.0f".format(it * 100) else "Centre" }, 0f) { v -> m.updateClip("Balance", "pan_$id") { it.copy(audio = it.audio.copy(pan = v.toDouble())) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Couper le son", Modifier.weight(1f), fontSize = 12.sp)
        Switch(c.audio.muted, { v -> m.updateClip("Muet") { it.copy(audio = it.audio.copy(muted = v)) } })
    }
    if (c.audio.effects.isNotEmpty()) {
        Text("Effets audio / voix", fontSize = 12.sp, color = Cx.Dim)
        c.audio.effects.forEach { fx ->
            val def = EffectRegistry[fx.effectId]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(def?.name ?: fx.effectId, Modifier.weight(1f), fontSize = 13.sp)
                TextButton({ m.updateClip("Retirer ${def?.name}") { it.copy(audio = it.audio.copy(effects = it.audio.effects.filterNot { e -> e.id == fx.id })) } }) { Text("✕") }
            }
            def?.params?.forEach { pd -> ParamControl(pd, fx.params[pd.key] ?: pd.default) { v -> m.updateClip(pd.label, "ap_${fx.id}_${pd.key}") { it.copy(audio = it.audio.copy(effects = it.audio.effects.map { e -> if (e.id == fx.id) e.copy(params = e.params + (pd.key to v)) else e })) } } }
        }
    }
}

@Composable
private fun ParamControl(pd: ParamDef, value: Double, onChange: (Double) -> Unit) {
    when (pd.type) {
        ParamType.TOGGLE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pd.label, Modifier.weight(1f), fontSize = 12.sp, color = Cx.Dim); Switch(value >= 0.5, { onChange(if (it) 1.0 else 0.0) })
        }
        ParamType.CHOICE -> { Text(pd.label, fontSize = 12.sp, color = Cx.Dim); Chips(pd.choices.indices.toList(), value.toInt(), { pd.choices[it] }, { onChange(it.toDouble()) }) }
        else -> Prop(pd.label, value.toFloat(), pd.min.toFloat()..pd.max.toFloat(), { "%.2f%s".format(it, pd.unit.let { u -> if (u.isBlank()) "" else " $u" }) }, pd.default.toFloat()) { onChange(it.toDouble()) }
    }
}

@Composable
private fun EffectsPanel(m: EditorModel, c: Clip) {
    if (c.effects.isEmpty()) { Title("EFFETS"); Hint("Ajoute des effets depuis l'onglet ✨ Effets à gauche."); return }
    Title("EFFETS (${c.effects.size})")
    c.effects.forEach { fx ->
        val def = EffectRegistry[fx.effectId]
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).background(Cx.Panel2).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(def?.name ?: fx.effectId, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Switch(fx.enabled, { e -> m.updateClip("Effet") { it.copy(effects = it.effects.map { x -> if (x.id == fx.id) x.copy(enabled = e) else x }) } })
                TextButton({ m.updateClip("Retirer ${def?.name}") { it.copy(effects = it.effects.filterNot { x -> x.id == fx.id }) } }) { Text("✕") }
            }
            def?.params?.forEach { pd ->
                ParamControl(pd, fx.params[pd.key] ?: pd.default) { v -> m.updateClip(pd.label, "fx_${fx.id}_${pd.key}") { it.copy(effects = it.effects.map { x -> if (x.id == fx.id) x.copy(params = x.params + (pd.key to v)) else x }) } }
            }
        }
    }
}

@Composable
private fun MotionPanel(m: EditorModel, c: Clip) {
    val mo = c.motion
    if (mo.isIdentity) return
    Title("ANIMATIONS")
    if (mo.animIn != MotionPreset.NONE) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Entrée : ${mo.animIn.label}", Modifier.weight(1f), fontSize = 13.sp); TextButton({ m.setMotion { it.copy(animIn = MotionPreset.NONE) } }) { Text("✕") } }
        Prop("Durée d'entrée", mo.inUs / 1e6f, 0.1f..3f, { "%.1f s".format(it) }) { v -> m.updateClip("Durée", "mi_${c.id}") { it.copy(motion = it.motion.copy(inUs = (v * 1e6).toLong())) } }
    }
    if (mo.animOut != MotionPreset.NONE) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Sortie : ${mo.animOut.label}", Modifier.weight(1f), fontSize = 13.sp); TextButton({ m.setMotion { it.copy(animOut = MotionPreset.NONE) } }) { Text("✕") } }
        Prop("Durée de sortie", mo.outUs / 1e6f, 0.1f..3f, { "%.1f s".format(it) }) { v -> m.updateClip("Durée", "mo_${c.id}") { it.copy(motion = it.motion.copy(outUs = (v * 1e6).toLong())) } }
    }
    if (mo.loop != LoopMotion.NONE) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Boucle : ${mo.loop.label}", Modifier.weight(1f), fontSize = 13.sp); TextButton({ m.setMotion { it.copy(loop = LoopMotion.NONE) } }) { Text("✕") } }
        Prop("Vitesse", mo.loopSpeed.toFloat(), 0.1f..4f, { "×%.1f".format(it) }, 1f) { v -> m.updateClip("Vitesse d'animation", "ls_${c.id}") { it.copy(motion = it.motion.copy(loopSpeed = v.toDouble())) } }
        Prop("Intensité", mo.loopAmount.toFloat(), 0.1f..3f, { "×%.1f".format(it) }, 1f) { v -> m.updateClip("Intensité", "la_${c.id}") { it.copy(motion = it.motion.copy(loopAmount = v.toDouble())) } }
    }
}

@Composable
private fun TransitionPanel(m: EditorModel, c: Clip, tr: TransitionInstance) {
    val def = TransitionRegistry[tr.transitionId]
    Title("TRANSITION D'ENTRÉE")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(def?.name ?: tr.transitionId, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        TextButton({ m.removeTransition() }) { Text("Retirer") }
    }
    Prop("Durée", tr.durationUs / 1e6f, 0.1f..3f, { "%.2f s".format(it) }) { v -> m.edit("Durée de transition", "trd_${c.id}") { TimelineEditor.setTransition(it, c.id, tr.copy(durationUs = (v * 1e6).toLong())) } }
}

@Composable
private fun KeyPanel(m: EditorModel, c: Clip) {
    Title("FOND VERT & MASQUE")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Incrustation fond vert", Modifier.weight(1f), fontSize = 12.sp)
        Switch(c.chromaKey.enabled, { e -> m.updateClip("Fond vert") { it.copy(chromaKey = it.chromaKey.copy(enabled = e)) } })
    }
    if (c.chromaKey.enabled) {
        Chips(listOf(0x00FF00L to "Vert", 0x0000FFL to "Bleu"), c.chromaKey.color.let { col -> if (col == 0x0000FFL) 0x0000FFL to "Bleu" else 0x00FF00L to "Vert" }, { it.second }, { k -> m.updateClip("Couleur clé") { it.copy(chromaKey = it.chromaKey.copy(color = k.first)) } })
        Prop("Tolérance", c.chromaKey.tolerance.toFloat(), 0f..1f) { v -> m.updateClip("Tolérance", "ck_${c.id}") { it.copy(chromaKey = it.chromaKey.copy(tolerance = v.toDouble())) } }
        Prop("Douceur", c.chromaKey.softness.toFloat(), 0f..0.5f) { v -> m.updateClip("Douceur", "cks_${c.id}") { it.copy(chromaKey = it.chromaKey.copy(softness = v.toDouble())) } }
    }
    Chips(MaskShape.entries.toList(), c.mask.shape, { when (it) { MaskShape.NONE -> "Sans masque"; MaskShape.RECTANGLE -> "Rectangle"; MaskShape.ELLIPSE -> "Cercle"; MaskShape.LINEAR -> "Linéaire"; MaskShape.MIRROR -> "Miroir" } }, { s -> m.updateClip("Masque") { it.copy(mask = it.mask.copy(shape = s)) } })
    if (c.mask.shape != MaskShape.NONE) {
        Prop("Largeur", c.mask.width.toFloat(), 0.05f..1.5f) { v -> m.updateClip("Masque", "mw_${c.id}") { it.copy(mask = it.mask.copy(width = v.toDouble())) } }
        Prop("Hauteur", c.mask.height.toFloat(), 0.05f..1.5f) { v -> m.updateClip("Masque", "mh_${c.id}") { it.copy(mask = it.mask.copy(height = v.toDouble())) } }
        Prop("Adoucir le bord", c.mask.feather.toFloat(), 0f..0.5f) { v -> m.updateClip("Masque", "mf_${c.id}") { it.copy(mask = it.mask.copy(feather = v.toDouble())) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Inverser", Modifier.weight(1f), fontSize = 12.sp)
            Switch(c.mask.invert, { v -> m.updateClip("Masque inversé") { it.copy(mask = it.mask.copy(invert = v)) } })
        }
    }
}
