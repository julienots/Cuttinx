package com.superessence.cuttinx.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.ui.components.*
import com.superessence.cuttinx.ui.theme.LocalCx

/** Ready-made animation combos used by creators (entry + loop + exit in one tap). */
private data class MotionCombo(val name: String, val description: String, val motion: ClipMotion)

private val combos = listOf(
    MotionCombo("Hook TikTok", "Pop d'entrée + zoom choc en rythme", ClipMotion(MotionPreset.POP, 350_000, MotionPreset.NONE, 400_000, LoopMotion.ZOOM_PUNCH, 2.0, 1.0)),
    MotionCombo("Zoom lent", "Zoom avant très doux sur tout le plan (style YouTube)", ClipMotion(loop = LoopMotion.SLOW_ZOOM_IN, loopAmount = 1.0)),
    MotionCombo("Cinématique", "Fondu, Ken Burns lent, fondu", ClipMotion(MotionPreset.FADE, 800_000, MotionPreset.FADE, 800_000, LoopMotion.KEN_BURNS, 1.0, 1.0)),
    MotionCombo("Énergie", "Fouet, tremblement caméra, fouet", ClipMotion(MotionPreset.WHIP, 300_000, MotionPreset.WHIP, 300_000, LoopMotion.SHAKE, 1.0, 0.8)),
    MotionCombo("Douceur", "Envol, flottement, fondu", ClipMotion(MotionPreset.RISE, 600_000, MotionPreset.FADE, 500_000, LoopMotion.FLOAT, 1.0, 1.0)),
    MotionCombo("Glitch", "Sauts glitch + néon", ClipMotion(MotionPreset.GLITCH, 450_000, MotionPreset.GLITCH, 450_000, LoopMotion.FLICKER, 1.0, 1.0)),
    MotionCombo("Rebond", "Chute rebondie + rebond continu", ClipMotion(MotionPreset.DROP, 700_000, MotionPreset.ZOOM_OUT, 350_000, LoopMotion.BOUNCE, 1.0, 0.7)),
    MotionCombo("Cœur", "Élastique + battement de cœur", ClipMotion(MotionPreset.ELASTIC, 600_000, MotionPreset.ZOOM_IN, 350_000, LoopMotion.HEARTBEAT, 1.0, 1.0)),
    MotionCombo("Tourbillon", "Entrée et sortie en tourbillon", ClipMotion(MotionPreset.SPIN_ZOOM, 600_000, MotionPreset.SPIN_ZOOM, 600_000, LoopMotion.NONE)),
)

/** Entry / exit / loop animations of any visual clip, plus pro combos. */
@UnstableApi
@Composable
fun MotionPanel(vm: EditorViewModel, c: Clip) {
    val m = c.motion
    val cx = LocalCx.current
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Entrée", "Sortie", "Boucle", "Combos")
    fun change(label: String, key: String? = null, preview: Long? = null, f: (ClipMotion) -> ClipMotion) {
        vm.updateClip(label, key?.let { "${it}_${c.id}" }) { it.copy(motion = f(it.motion)) }
        // show the result immediately
        preview?.let { vm.preview.seekTo(it); vm.preview.play() }
    }
    ChoiceChips(tabs, tabs[tab], { it }, { tab = tabs.indexOf(it) })
    Spacer(Modifier.height(8.dp))
    when (tab) {
        0, 1 -> {
            val exit = tab == 1
            val current = if (exit) m.animOut else m.animIn
            MotionPreset.entries.groupBy { it.group }.forEach { (group, items) ->
                Text(group, style = MaterialTheme.typography.labelSmall, color = cx.dim, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                ChoiceChips(items, current, { it.label }, { a ->
                    val start = if (exit) (c.endUs - m.outUs - 200_000).coerceAtLeast(c.startUs) else c.startUs
                    change(if (exit) "Animation de sortie" else "Animation d'entrée", preview = start) { if (exit) it.copy(animOut = a) else it.copy(animIn = a) }
                })
            }
            val us = if (exit) m.outUs else m.inUs
            val maxS = (c.durationUs / 1e6f).coerceIn(0.2f, 5f)
            ValueSlider(if (exit) "Durée de sortie" else "Durée d'entrée", (us / 1e6f).coerceAtMost(maxS), 0.1f..maxS, { v ->
                change("Durée d'animation", if (exit) "mout" else "min") { if (exit) it.copy(outUs = (v * 1e6).toLong()) else it.copy(inUs = (v * 1e6).toLong()) }
            }, default = 0.5f, unit = " s", decimals = 2, step = 0.05f)
        }
        2 -> {
            ChoiceChips(LoopMotion.entries.toList(), m.loop, { it.label }, { l -> change("Animation en boucle", preview = c.startUs) { it.copy(loop = l) } })
            if (m.loop != LoopMotion.NONE) {
                ValueSlider("Vitesse", m.loopSpeed.toFloat(), 0.1f..5f, { v -> change("Vitesse de boucle", "mls") { it.copy(loopSpeed = v.toDouble()) } }, default = 1f, unit = "×", decimals = 2)
                ValueSlider("Intensité", m.loopAmount.toFloat(), 0f..3f, { v -> change("Intensité de boucle", "mla") { it.copy(loopAmount = v.toDouble()) } }, default = 1f, unit = "×", decimals = 2)
                if (m.loop == LoopMotion.ZOOM_PUNCH || m.loop == LoopMotion.HEARTBEAT) {
                    val beats = vm.beats.collectAsState().value
                    if (beats.size >= 2) TextButton({
                        // align the loop on the detected tempo: one punch per beat
                        val bpm = 60_000_000.0 / beats.zipWithNext { a, b -> b - a }.sorted().let { it[it.size / 2] }.coerceAtLeast(1)
                        change("Synchroniser sur le tempo") { it.copy(loopSpeed = (bpm / 60.0).coerceIn(0.1, 5.0)) }
                    }) { Text("Caler sur le tempo de la musique") }
                }
            }
        }
        else -> {
            combos.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    row.forEach { combo ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(cx.panel2).clickable { change("Combo « ${combo.name} »", preview = c.startUs) { combo.motion } }.padding(10.dp)) {
                            Text(combo.name, fontWeight = FontWeight.Bold)
                            Text(combo.description, style = MaterialTheme.typography.labelSmall, color = cx.dim)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    Row(Modifier.padding(top = 8.dp)) {
        TextButton({
            val motion = m
            vm.edit("Animations appliquées à la piste") { p ->
                val ids = p.findClip(c.id)?.first?.clips?.map { it.id }?.toSet() ?: return@edit p
                TimelineEditor.mapClips(p) { cl -> if (cl.id in ids) cl.copy(motion = motion) else cl }
            }
        }) { Text("Appliquer à toute la piste") }
        TextButton({ change("Animations supprimées") { ClipMotion() } }) { Text("Réinitialiser") }
    }
    InfoCard("Les animations s'ajoutent aux images clés et sont rendues à l'identique dans l'aperçu et l'export.")
}
