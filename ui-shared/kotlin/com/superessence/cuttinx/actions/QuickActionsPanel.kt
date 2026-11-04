package com.superessence.cuttinx.actions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.smart.EditContext
import com.superessence.cuttinx.core.smart.QuickAction
import com.superessence.cuttinx.core.smart.QuickActions
import com.superessence.cuttinx.icons.CxIcons

private val Dim = Color.White.copy(alpha = 0.6f)

/** Last actions used, shown first (kept for the session). */
object QuickActionsMemory {
    val recent = mutableStateListOf<String>()
    fun used(id: String) { recent.remove(id); recent.add(0, id); while (recent.size > 5) recent.removeAt(recent.size - 1) }
}

private sealed interface Item {
    data class Header(val text: String) : Item
    /** "Show the N other actions" of a folded group. */
    data class More(val group: String, val hidden: Int, val open: Boolean) : Item
    data class Row(val action: QuickAction, val enabled: Boolean) : Item
}

/**
 * Searchable list of every editing action (Android panel and PC Ctrl + K window). Typing filters,
 * arrow keys and Enter drive it on PC, a tap runs an action. Actions that need a selection stay
 * visible but dimmed, with the reason under their name. [scroll] = the list scrolls itself (needs a bounded height).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickActionsPanel(ctx: EditContext, accent: Color, onRun: (QuickAction) -> Unit, modifier: Modifier = Modifier, scroll: Boolean = false, autoFocus: Boolean = false, showCursor: Boolean = false) {
    // Labo technologies still locked go through the advert first, then run
    val run: (QuickAction) -> Unit = { a -> com.superessence.cuttinx.lab.TechGate.runOrRequest(a) { onRun(a) } }
    val unlocks = com.superessence.cuttinx.lab.TechGate.version
    var query by remember { mutableStateOf("") }
    var sel by remember { mutableIntStateOf(0) }
    // groups are folded to their first actions so the list stays short (270 actions in 2.0)
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }

    val items: List<Item> = remember(query, ctx.project, ctx.selection, ctx.playheadUs, QuickActionsMemory.recent.toList(), open.toMap(), unlocks) {
        if (query.isBlank()) buildList {
            val rec = QuickActionsMemory.recent.mapNotNull { QuickActions[it] }
            if (rec.isNotEmpty()) { add(Item.Header("RÉCENTES")); rec.forEach { add(Item.Row(it, it.available(ctx))) } }
            val usable = QuickActions.all.filter { it.available(ctx) }
            for (g in QuickActions.groups) {
                val inGroup = QuickActions.all.filter { it.group == g }
                if (inGroup.isEmpty()) continue
                add(Item.Header("${g.uppercase()} · ${inGroup.size}"))
                // available actions first inside each group
                val ordered = inGroup.filter { it in usable } + inGroup.filter { it !in usable }
                val unfolded = open[g] == true || ordered.size <= 6
                (if (unfolded) ordered else ordered.take(4)).forEach { add(Item.Row(it, it in usable)) }
                if (ordered.size > 6) add(Item.More(g, ordered.size - 4, unfolded))
            }
        } else QuickActions.search(query, ctx).map { Item.Row(it, it.available(ctx)) }
    }
    val runnable = items.filterIsInstance<Item.Row>().filter { it.enabled }
    LaunchedEffect(query) { sel = 0 }
    val current = runnable.getOrNull(sel.coerceIn(0, (runnable.size - 1).coerceAtLeast(0)))

    Column(modifier) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionDown -> { sel = (sel + 1).coerceAtMost((runnable.size - 1).coerceAtLeast(0)); true }
                Key.DirectionUp -> { sel = (sel - 1).coerceAtLeast(0); true }
                Key.Enter, Key.NumPadEnter -> { current?.let { run(it.action) }; true }
                else -> false
            }
        }, singleLine = true, leadingIcon = { Icon(CxIcons.Search, null, Modifier.size(20.dp)) },
            placeholder = { Text("Chercher : couper, écran partagé, karaoké, ralenti…") },
            trailingIcon = { if (query.isNotEmpty()) Icon(CxIcons.Close, "Effacer", Modifier.size(20.dp).clickable { query = "" }) })
        Spacer(Modifier.height(6.dp))
        val list: @Composable ColumnScope.() -> Unit = {
            if (items.isEmpty()) Text("Aucune action ne correspond à « $query ».", color = Dim, modifier = Modifier.padding(16.dp))
            items.forEach { item ->
                when (item) {
                    is Item.Header -> Text(item.text, Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    is Item.More -> Text(if (item.open) "Réduire" else "Voir les ${item.hidden} autres actions",
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open[item.group] = !item.open }.padding(horizontal = 10.dp, vertical = 8.dp),
                        color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    is Item.Row -> {
                        val hi = showCursor && item === current
                        val req = remember { BringIntoViewRequester() }
                        LaunchedEffect(hi, sel) { if (hi) runCatching { req.bringIntoView() } }
                        ActionRow(item.action, item.enabled, hi, accent, Modifier.bringIntoViewRequester(req)) { if (item.enabled) run(item.action) }
                    }
                }
            }
        }
        if (scroll) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = list) else Column(content = list)
    }
}

@Composable
private fun ActionRow(a: QuickAction, enabled: Boolean, highlighted: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(10.dp))
        .background(if (highlighted) accent.copy(alpha = 0.16f) else Color.Transparent).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(CxIcons.byName(a.icon), null, Modifier.size(20.dp), tint = if (enabled) (if (highlighted) accent else Color.White) else Dim.copy(alpha = 0.5f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, fontSize = 14.sp, fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal, color = if (enabled) Color.White else Dim.copy(alpha = 0.55f), maxLines = 1)
            if (!enabled) Text(a.need.hint, fontSize = 11.sp, color = Dim.copy(alpha = 0.5f), maxLines = 1)
        }
        if (a.locked) Row(Modifier.clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CxIcons.Lock, null, Modifier.size(11.dp), tint = accent)
            Spacer(Modifier.width(4.dp))
            Text("Pub · 24 h", fontSize = 11.sp, color = accent, fontWeight = FontWeight.Bold)
        } else if (a.tech != null) Text("Labo", fontSize = 11.sp, color = accent)
        a.shortcut?.let { Text(it, Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 11.sp, color = Dim) }
    }
}
