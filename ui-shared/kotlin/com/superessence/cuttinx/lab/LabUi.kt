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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.smart.EditContext
import com.superessence.cuttinx.core.smart.Plus
import com.superessence.cuttinx.core.smart.QuickAction
import com.superessence.cuttinx.core.smart.QuickActions
import com.superessence.cuttinx.core.smart.Tech
import com.superessence.cuttinx.core.smart.TechVault
import com.superessence.cuttinx.icons.CxIcons

private val Dim = Color.White.copy(alpha = 0.6f)

/**
 * Shared unlock state of the Labo technologies. An action that needs a locked technology calls
 * [request]; the app shows its advert (Android) or sponsor screen (PC), then calls [grant].
 */
object TechGate {
    /** Bumped on every unlock so the screens recompose. */
    var version by mutableIntStateOf(0)
        private set

    class Request(val tech: Tech, val then: (() -> Unit)?)

    var pending by mutableStateOf<Request?>(null)
        private set

    /** The CUTTINX Plus window is open (with what to run once Plus is on). */
    var plusOpen by mutableStateOf(false)
        private set
    private var plusThen: (() -> Unit)? = null

    /** Plus technologies open the Plus window; the others ask for an advert. */
    fun request(tech: Tech, then: (() -> Unit)? = null) {
        if (Plus.active) { runCatching { then?.invoke() }; return }
        if (tech.plus) openPlus(then) else pending = Request(tech, then)
    }
    fun cancel() { pending = null }

    /** Redraws the screens (e.g. after a licence was renewed in the background). */
    fun refresh() { version++ }

    fun openPlus(then: (() -> Unit)? = null) { plusThen = then; plusOpen = true }
    fun closePlus() { plusOpen = false; plusThen = null }

    /** Plus was just switched on (or off): refresh the screens and run what was waiting. */
    fun plusChanged() {
        version++
        if (Plus.active) { val t = plusThen; plusThen = null; plusOpen = false; pending = null; runCatching { t?.invoke() } }
    }

    /** The advert was watched to the end: unlock for 24 h and run what was asked. */
    fun grant(tech: Tech) {
        TechVault.unlock(tech)
        version++
        val r = pending
        pending = null
        if (r?.tech == tech) runCatching { r.then?.invoke() }
    }

    /** Runs [a] now, or asks for the advert first when its technology is locked. */
    fun runOrRequest(a: QuickAction, run: () -> Unit) {
        val t = a.tech
        if (t != null && !TechVault.isUnlocked(t)) request(t, run) else run()
    }
}

/** "Unlock with an advert" confirmation. [busy] while the advert loads, [error] when none could be shown. */
@Composable
fun TechUnlockDialog(req: TechGate.Request, accent: Color, adLabel: String, busy: Boolean, error: String?, onWatch: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(CxIcons.byName(req.tech.icon), null, tint = accent) },
        title = { Text(req.tech.label, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(req.tech.pitch)
                Spacer(Modifier.height(10.dp))
                Text("Technologie du Labo CUTTINX : regarde $adLabel jusqu'au bout pour la débloquer pendant ${TechVault.UNLOCK_HOURS} heures, avec toutes ses variantes.", color = Dim, fontSize = 13.sp)
                if (error != null) { Spacer(Modifier.height(8.dp)); Text(error, color = Color(0xFFFF7A7A), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(onWatch, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) {
                if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black); Spacer(Modifier.width(8.dp)) }
                Text(if (error != null) "Réessayer" else "Regarder et débloquer")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Plus tard") } },
    )
}

/**
 * The Labo CUTTINX: every technology with its status, a button to unlock it and its actions.
 * [onRun] receives free and unlocked actions; locked ones go through [TechGate.request].
 */
@Composable
fun LabPanel(ctx: EditContext, accent: Color, onRun: (QuickAction) -> Unit, modifier: Modifier = Modifier, scroll: Boolean = false) {
    @Suppress("UNUSED_VARIABLE") val v = TechGate.version   // recompose after an unlock
    val free = Tech.entries.filter { !it.plus }
    val unlockedCount = free.count { TechVault.isUnlocked(it) }
    val body: @Composable ColumnScope.() -> Unit = {
        PlusBanner(accent)
        Text("LABO CUTTINX", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 14.dp))
        Text(if (Plus.active) "${free.size} technologies, toutes débloquées avec CUTTINX Plus."
            else "${free.size} technologies exclusives. Une pub plein écran débloque une technologie pendant ${TechVault.UNLOCK_HOURS} h. Débloquées : $unlockedCount.",
            color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        for (t in free) TechCard(t, ctx, accent, onRun)
        Text("TECHNOLOGIES PLUS", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 14.dp))
        Text("Réservées aux abonnés CUTTINX Plus : précision à l'image et outils de pointe.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        for (t in Tech.entries.filter { it.plus }) TechCard(t, ctx, accent, onRun)
    }
    if (scroll) Column(modifier.verticalScroll(rememberScrollState()), content = body) else Column(modifier, content = body)
}

@Composable
private fun TechCard(t: Tech, ctx: EditContext, accent: Color, onRun: (QuickAction) -> Unit) {
    val open = TechVault.isUnlocked(t)
    val actions = remember(t) { QuickActions.byTech(t) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp))
        .border(1.dp, if (open) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
        .background(Color.White.copy(alpha = 0.04f)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(CxIcons.byName(t.icon), null, Modifier.size(22.dp), tint = if (open) accent else Color.White)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.label, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                Text(t.pitch, fontSize = 12.sp, color = Dim)
            }
            Spacer(Modifier.width(8.dp))
            if (open) Text(TechVault.remaining(t) ?: "", fontSize = 11.sp, color = accent, fontWeight = FontWeight.Bold)
            else Row(Modifier.clip(RoundedCornerShape(20.dp)).background(accent).clickable { TechGate.request(t) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(CxIcons.Lock, null, Modifier.size(14.dp), tint = Color.Black)
                Spacer(Modifier.width(4.dp))
                Text(if (t.plus) "Plus" else "Pub", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRowCompat {
            for (a in actions) {
                val ok = a.available(ctx)
                Text(a.label, Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (open && ok) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f))
                    .clickable(enabled = ok) { if (open) onRun(a) else TechGate.request(t) { onRun(a) } }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                    fontSize = 12.sp, color = if (ok) Color.White else Dim.copy(alpha = 0.5f))
            }
        }
    }
}

/** Minimal wrapping row (works on every Compose version the apps use). */
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { measurables, constraints ->
        val maxW = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        var x = 0; var y = 0; var rowH = 0
        val pos = placeables.map { pl ->
            if (x > 0 && x + pl.width > maxW) { x = 0; y += rowH; rowH = 0 }
            val p = x to y
            x += pl.width; rowH = maxOf(rowH, pl.height)
            p
        }
        layout(maxW, y + rowH) { placeables.forEachIndexed { i, pl -> pl.place(pos[i].first, pos[i].second) } }
    }
}
