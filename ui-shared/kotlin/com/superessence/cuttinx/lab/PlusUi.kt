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
import com.superessence.cuttinx.design.cxTag

private val Dim = Color.White.copy(alpha = 0.6f)

/** Small "CUTTINX Plus" card at the top of the Labo: status, or what Plus gives. Opens [PlusDialog]. */
@Composable
fun PlusBanner(accent: Color, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val v = TechGate.version
    val l = Plus.license?.takeIf { Plus.isValid(it) }
    val shape = com.superessence.cuttinx.design.CxDesign.Card
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(Color.White.copy(alpha = 0.04f))
        .border(1.dp, Color.White.copy(alpha = 0.10f), shape).clickable { TechGate.openPlus() }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CUTTINX Plus", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
                if (l != null) { Spacer(Modifier.width(8.dp)); Text("Actif", Modifier.cxTag(accent), color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(2.dp))
            Text(if (l != null) "Jusqu'au ${Plus.until(l)}. Zéro pub, tout est débloqué."
                else "Zéro pub, tout le Labo pour toujours et ${Tech.entries.count { it.plus }} technologies exclusives.", fontSize = 12.sp, color = Dim)
        }
        Text(if (l != null) "Gérer" else "Voir l'offre", Modifier.padding(end = 12.dp).clip(com.superessence.cuttinx.design.CxDesign.Button)
            .border(1.dp, accent.copy(alpha = 0.7f), com.superessence.cuttinx.design.CxDesign.Button).padding(horizontal = 12.dp, vertical = 7.dp),
            color = accent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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
                Text("Technologies Plus", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                for (t in Tech.entries.filter { it.plus }) Column(Modifier.padding(top = 6.dp)) {
                    Text(t.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Text(t.pitch, fontSize = 12.sp, color = Dim)
                }
                if (l == null) {
                    Spacer(Modifier.height(14.dp))
                    if (Plus.siteUrl.isNotBlank()) {
                        Button({ runCatching { uri.openUri(Plus.siteUrl.trimEnd('/') + "/plus") } }, Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black), shape = com.superessence.cuttinx.design.CxDesign.Button) { Text("S'abonner à CUTTINX Plus", fontWeight = FontWeight.Bold) }
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
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black), shape = com.superessence.cuttinx.design.CxDesign.Button) { Text("Activer") }
            else Button(onDismiss, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black), shape = com.superessence.cuttinx.design.CxDesign.Button) { Text("Super") }
        },
        dismissButton = {
            if (l == null) TextButton(onDismiss) { Text("Plus tard") }
            else TextButton({ Plus.deactivate(); TechGate.plusChanged() }) { Text("Retirer de cet appareil", color = Dim) }
        },
    )
}
