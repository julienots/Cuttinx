package com.superessence.cuttinx.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.smart.DirectorStyle
import com.superessence.cuttinx.design.cxChip
import com.superessence.cuttinx.ui.theme.LocalCx

/** Before the autonomous director runs: the kind of film, its length, an optional hook. */
@UnstableApi
@Composable
fun DirectorDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val cx = LocalCx.current
    var style by remember { mutableStateOf(DirectorStyle.VIRAL) }
    var seconds by remember { mutableIntStateOf(30) }
    var hook by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Réalisateur autonome", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("CUTTINX regarde toutes les vidéos de la piste principale, écoute ta musique, puis monte le film entier. Tout reste modifiable et s'annule en un geste.",
                    fontSize = 13.sp, color = cx.dim)
                Text("Style", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in DirectorStyle.entries) Text(s.label, Modifier.cxChip(s == style, cx.accent) { style = s }, fontSize = 13.sp)
                }
                Text(style.pitch, fontSize = 12.sp, color = cx.dim, modifier = Modifier.padding(top = 6.dp))
                Text("Durée visée", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in listOf(15, 30, 45, 60, 90, 180)) Text(if (s < 60) "$s s" else "${s / 60} min" + if (s % 60 != 0) " ${s % 60}" else "", Modifier.cxChip(s == seconds, cx.accent) { seconds = s }, fontSize = 13.sp)
                }
                OutlinedTextField(hook, { hook = it.take(60) }, Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true,
                    label = { Text("Titre d'accroche (facultatif)") })
                Text("Astuce : ajoute d'abord une musique (bouton Audio) pour un montage calé sur ses temps et son drop.", fontSize = 12.sp, color = cx.dim, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton({ onDismiss(); EditorMagic.hyperDirector(vm, style, seconds * 1_000_000L, hook) }) { Text("Monter le film", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } })
}

/** What the director decided, in plain words. */
@Composable
fun DirectorReport(lines: List<String>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Ton film est monté", fontWeight = FontWeight.SemiBold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { lines.forEach { Text("• $it", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) } } },
        confirmButton = { TextButton(onDismiss) { Text("Voir le film") } })
}
