package com.superessence.cuttinx.lab

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.smart.Plus
import com.superessence.cuttinx.core.smart.Tech
import com.superessence.cuttinx.icons.CxIcons

private val Dim = Color.White.copy(alpha = 0.6f)

/** Small "CUTTINX Plus" card at the top of the Labo: status, or what Plus gives. Opens [PlusDialog]. */
@Composable
fun PlusBanner(accent: Color, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val v = TechGate.version
    val l = Plus.license?.takeIf { Plus.isValid(it) }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.06f))))
        .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
        .clickable { TechGate.openPlus() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(CxIcons.Star, null, Modifier.size(28.dp), tint = accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("CUTTINX Plus", fontWeight = FontWeight.Black, fontSize = 17.sp, color = Color.White)
            Text(if (l != null) "Actif jusqu'au ${Plus.until(l)} · zéro pub · tout est débloqué"
                else "Zéro pub, tout le Labo pour toujours et ${Tech.entries.count { it.plus }} technologies de pointe", fontSize = 12.sp, color = Dim)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (l != null) "Gérer" else "Découvrir", Modifier.clip(RoundedCornerShape(20.dp)).background(accent).padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

/** Place once at the root of the app: shows the CUTTINX Plus window when [TechGate.plusOpen]. */
@Composable
fun PlusHost(accent: Color) {
    if (TechGate.plusOpen) PlusDialog(accent) { TechGate.closePlus() }
}

/** What CUTTINX Plus gives, how to subscribe, and where to paste the activation code. */
@Composable
fun PlusDialog(accent: Color, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = TechGate.version
    val uri = LocalUriHandler.current
    val l = Plus.license?.takeIf { Plus.isValid(it) }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(CxIcons.Star, null, tint = accent) },
        title = { Text(if (l != null) "CUTTINX Plus est actif" else "CUTTINX Plus", fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (l != null) {
                    Text("Merci ! Ton abonnement est actif jusqu'au ${Plus.until(l)}${if (l.email.isNotBlank()) " (${l.email})" else ""}. Il se renouvelle tout seul tant qu'il est payé.", color = Dim, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                }
                for (perk in Plus.PERKS) Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Icon(CxIcons.Check, null, Modifier.size(16.dp).padding(top = 2.dp), tint = accent)
                    Spacer(Modifier.width(8.dp))
                    Text(perk, fontSize = 14.sp, color = Color.White)
                }
                Spacer(Modifier.height(10.dp))
                Text("TECHNOLOGIES PLUS", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                for (t in Tech.entries.filter { it.plus }) Column(Modifier.padding(top = 6.dp)) {
                    Text(t.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Text(t.pitch, fontSize = 12.sp, color = Dim)
                }
                if (l == null) {
                    Spacer(Modifier.height(14.dp))
                    if (Plus.siteUrl.isNotBlank()) {
                        Button({ runCatching { uri.openUri(Plus.siteUrl.trimEnd('/') + "/plus") } }, Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) { Text("S'abonner à CUTTINX Plus", fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (Plus.siteUrl.isBlank()) { Text("Abonne-toi sur le site CUTTINX PRO, page « Plus » : tu reçois aussitôt ton code d'activation.", color = Color.White, fontSize = 13.sp); Spacer(Modifier.height(10.dp)) }
                    Text("Déjà abonné ? Colle ici le code d'activation (il commence par CXP1.) :", color = Dim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(code, { code = it; msg = null }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("CXP1.…") })
                    msg?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Color(0xFFFF7A7A), fontSize = 13.sp) }
                }
            }
        },
        confirmButton = {
            if (l == null) Button({ msg = Plus.activate(code); if (msg == null) TechGate.plusChanged() }, enabled = code.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) { Text("Activer") }
            else Button(onDismiss, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) { Text("Super") }
        },
        dismissButton = {
            if (l == null) TextButton(onDismiss) { Text("Plus tard") }
            else TextButton({ Plus.deactivate(); TechGate.plusChanged() }) { Text("Retirer de cet appareil", color = Dim) }
        },
    )
}
