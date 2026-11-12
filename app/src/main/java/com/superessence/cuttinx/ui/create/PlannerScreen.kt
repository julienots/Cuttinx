package com.superessence.cuttinx.ui.create

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superessence.cuttinx.core.create.CaptionTone
import com.superessence.cuttinx.core.create.Captions
import com.superessence.cuttinx.core.create.Network
import com.superessence.cuttinx.core.create.PlannedPost
import com.superessence.cuttinx.core.create.Planner
import com.superessence.cuttinx.core.create.PostStatus
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.design.cxChip
import com.superessence.cuttinx.planner.PlannerStore
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

fun Network.color(): Color = when (this) {
    Network.TIKTOK -> Color(0xFF25F4EE)
    Network.INSTAGRAM -> Color(0xFFE1306C)
    Network.YOUTUBE, Network.SHORTS -> Color(0xFFFF3B30)
    Network.SNAPCHAT -> Color(0xFFFFE600)
    Network.X -> Color(0xFFB0B0B0)
    Network.FACEBOOK -> Color(0xFF3D8BFF)
    Network.LINKEDIN -> Color(0xFF2D8CFF)
    Network.TWITCH -> Color(0xFF9146FF)
}

fun PostStatus.color(): Color = when (this) {
    PostStatus.IDEA -> Color(0xFF9AA0A6)
    PostStatus.FILMING -> Color(0xFFFF9F0A)
    PostStatus.EDITING -> Color(0xFF3D8BFF)
    PostStatus.READY -> Color(0xFF2FB67C)
    PostStatus.PUBLISHED -> Color(0xFFB57BFF)
}

/**
 * Planning: the week of publications on every network, the best moments to post, a reminder at
 * the planned time, and captions + hashtags written on the phone.
 */
@Composable
fun PlannerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cx = LocalCx.current
    val zone = remember { ZoneId.systemDefault() }
    LaunchedEffect(Unit) { PlannerStore.load(ctx) }
    val posts by PlannerStore.posts.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    var monday by remember { mutableStateOf(Planner.weekStart(now, zone)) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var editing by remember { mutableStateOf<PlannedPost?>(null) }
    var showDone by remember { mutableStateOf(false) }
    val stats = remember(posts, now) { Planner.stats(posts, now, zone) }
    val week = remember(posts, monday) { Planner.week(posts, monday, zone) }

    fun newPost(at: Long? = null): PlannedPost {
        val net = posts.lastOrNull()?.network ?: Network.TIKTOK
        val slot = at ?: Planner.suggestSlots(net, now, zone, posts, 1).firstOrNull() ?: (now + 3_600_000)
        return PlannedPost("post_" + System.currentTimeMillis().toString(36), "", net, slot)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { StudioBar("Planning", "3.1", onBack, null) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = newPost(day?.let { d -> if (d.isBefore(LocalDate.now(zone))) null else d.atTime(19, 0).atZone(zone).toInstant().toEpochMilli().takeIf { it > now } }) },
                containerColor = cx.accent, contentColor = cx.onAccent, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Planifier", fontWeight = FontWeight.SemiBold) })
        }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("À venir", stats.upcoming.toString(), cx.accent, Modifier.weight(1f))
                    StatTile("En retard", stats.late.toString(), if (stats.late > 0) Color(0xFFFF6B5A) else cx.dim, Modifier.weight(1f))
                    StatTile("Publiés", "${stats.publishedThisWeek}/${stats.plannedThisWeek}", Color(0xFF2FB67C), Modifier.weight(1f))
                    StatTile("Série", if (stats.streakWeeks > 0) "${stats.streakWeeks} 🔥" else "0", Color(0xFFFF9F0A), Modifier.weight(1f))
                }
                stats.next?.let { n ->
                    Spacer(Modifier.height(8.dp))
                    Text("Prochaine publication : « ${n.title.ifBlank { "Sans titre" }} » sur ${n.network.label}, ${Planner.relative(n.atMs, now, zone)}", color = cx.dim, fontSize = 12.sp)
                }
            }
            item {
                // the week, one column per day, a dot per post in the colour of its network
                Column(Modifier.fillMaxWidth().clip(CxDesign.Card).background(cx.panel).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ monday = monday.minusWeeks(1); day = null }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Semaine précédente") }
                        Text("Semaine du %02d/%02d".format(monday.dayOfMonth, monday.monthValue), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton({ monday = monday.plusWeeks(1); day = null }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Semaine suivante") }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        val today = LocalDate.now(zone)
                        for (i in 0 until 7) {
                            val d = monday.plusDays(i.toLong())
                            val sel = d == day
                            Column(Modifier.weight(1f).clip(CxDesign.Chip).background(if (sel) cx.accent.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable { day = if (sel) null else d }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(Planner.dayName(d.dayOfWeek).removeSuffix("."), fontSize = 11.sp, color = cx.dim)
                                Text(d.dayOfMonth.toString(), fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal, color = if (d == today) cx.accent else MaterialTheme.colorScheme.onSurface)
                                Row(Modifier.height(10.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    week[i].take(4).forEach { p -> Box(Modifier.size(6.dp).clip(CircleShape).background(if (p.status == PostStatus.PUBLISHED) p.network.color().copy(alpha = 0.4f) else p.network.color())) }
                                }
                            }
                        }
                    }
                }
            }
            val shown = when {
                day != null -> week[java.time.temporal.ChronoUnit.DAYS.between(monday, day!!).toInt().coerceIn(0, 6)]
                showDone -> posts.filter { it.status == PostStatus.PUBLISHED }.sortedByDescending { it.atMs }
                else -> posts.filter { it.status != PostStatus.PUBLISHED }.sortedBy { it.atMs }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(day?.let { "Le %s %02d/%02d".format(Planner.dayName(it.dayOfWeek), it.dayOfMonth, it.monthValue) } ?: if (showDone) "Déjà publiés" else "À publier",
                        Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = cx.dim)
                    if (day == null) TextButton({ showDone = !showDone }) { Text(if (showDone) "À publier" else "Déjà publiés") } else TextButton({ day = null }) { Text("Tout voir") }
                }
            }
            if (shown.isEmpty()) item {
                Column(Modifier.fillMaxWidth().clip(CxDesign.Card).background(cx.panel).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (posts.isEmpty()) "Planifie ta première publication" else "Rien de prévu ici", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("CUTTINX propose les meilleurs moments pour chaque réseau, écrit la légende et te prévient à l'heure.", color = cx.dim, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            items(shown, key = { it.id }) { p ->
                PostCard(p, now, zone, onClick = { editing = p }, onDone = { PlannerStore.put(ctx, p.copy(status = if (p.status == PostStatus.PUBLISHED) PostStatus.READY else PostStatus.PUBLISHED)) })
            }
        }
    }
    editing?.let { p ->
        PostEditor(p, posts, now, zone, onDismiss = { editing = null },
            onSave = { PlannerStore.put(ctx, it); editing = null; if (it.remind && it.atMs > now) Toast.makeText(ctx, "Rappel ${Planner.relative(it.atMs, now, zone)}", Toast.LENGTH_SHORT).show() },
            onDelete = { PlannerStore.delete(ctx, p.id); editing = null })
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier) {
    val cx = LocalCx.current
    Column(modifier.clip(CxDesign.Card).background(cx.panel).padding(horizontal = 10.dp, vertical = 10.dp)) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = color, maxLines = 1)
        Text(label, fontSize = 11.sp, color = cx.dim, maxLines = 1)
    }
}

@Composable
private fun PostCard(p: PlannedPost, now: Long, zone: ZoneId, onClick: () -> Unit, onDone: () -> Unit) {
    val cx = LocalCx.current
    val late = p.status != PostStatus.PUBLISHED && p.atMs < now
    Row(Modifier.fillMaxWidth().clip(CxDesign.Card).background(cx.panel).border(1.dp, if (late) Color(0xFFFF6B5A).copy(alpha = 0.6f) else cx.line, CxDesign.Card)
        .clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(40.dp).clip(CxDesign.Chip).background(p.network.color()))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title.ifBlank { "Sans titre" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.network.label} · ${Planner.relative(p.atMs, now, zone)}", fontSize = 12.sp, color = if (late) Color(0xFFFF6B5A) else cx.dim, maxLines = 1)
            Text(p.status.label, fontSize = 11.sp, color = p.status.color(), fontWeight = FontWeight.SemiBold)
        }
        IconButton(onDone) {
            Icon(Icons.Default.Check, if (p.status == PostStatus.PUBLISHED) "Marquer non publié" else "Marquer publié",
                tint = if (p.status == PostStatus.PUBLISHED) PostStatus.PUBLISHED.color() else cx.dim)
        }
    }
}

@Composable
private fun PostEditor(start: PlannedPost, all: List<PlannedPost>, now: Long, zone: ZoneId, onDismiss: () -> Unit, onSave: (PlannedPost) -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val cx = LocalCx.current
    var p by remember(start.id) { mutableStateOf(start) }
    var tone by remember { mutableStateOf(CaptionTone.FUN) }
    var seed by remember { mutableIntStateOf(0) }
    var ideas by remember { mutableStateOf<List<String>>(emptyList()) }
    val isNew = all.none { it.id == start.id }
    val slots = remember(p.network, all) { Planner.suggestSlots(p.network, now, zone, all.filter { it.id != p.id }, 4) }
    val t: ZonedDateTime = Instant.ofEpochMilli(p.atMs).atZone(zone)
    val warnings = remember(p.caption, p.network) { if (p.caption.isBlank()) emptyList() else Captions.check(p.caption, p.network) }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Planifier une publication" else "Publication", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(p.title, { p = p.copy(title = it.take(120)) }, Modifier.fillMaxWidth(), label = { Text("Sujet de la vidéo") }, singleLine = true)
                FieldLabel("Réseau")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in Network.entries) Text(n.label, Modifier.cxChip(n == p.network, n.color()) { p = p.copy(network = n) }, fontSize = 12.sp)
                }
                FieldLabel("Quand")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({
                        DatePickerDialog(ctx, { _, y, m, d -> p = p.copy(atMs = t.withYear(y).withMonth(m + 1).withDayOfMonth(d).toInstant().toEpochMilli()) }, t.year, t.monthValue - 1, t.dayOfMonth).show()
                    }, shape = CxDesign.Button) { Text("%s %02d/%02d".format(Planner.dayName(t.dayOfWeek), t.dayOfMonth, t.monthValue)) }
                    OutlinedButton({
                        TimePickerDialog(ctx, { _, h, mi -> p = p.copy(atMs = t.withHour(h).withMinute(mi).withSecond(0).toInstant().toEpochMilli()) }, t.hour, t.minute, true).show()
                    }, shape = CxDesign.Button) { Text("%02d:%02d".format(t.hour, t.minute)) }
                }
                if (slots.isNotEmpty()) {
                    Text("Meilleurs moments sur ${p.network.label}", fontSize = 12.sp, color = cx.dim, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in slots) Text(Planner.relative(s, now, zone), Modifier.cxChip(s == p.atMs, cx.accent) { p = p.copy(atMs = s) }, fontSize = 12.sp)
                    }
                }
                FieldLabel("Où en est la vidéo")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in PostStatus.entries) Text(s.label, Modifier.cxChip(s == p.status, s.color()) { p = p.copy(status = s) }, fontSize = 12.sp)
                }
                FieldLabel("Légende")
                OutlinedTextField(p.caption, { p = p.copy(caption = it.take(5000)) }, Modifier.fillMaxWidth().heightIn(min = 96.dp), placeholder = { Text("Écris-la ou laisse CUTTINX t'aider") })
                Text("${p.caption.length} / ${p.network.captionLimit}", fontSize = 11.sp, color = if (p.caption.length > p.network.captionLimit) Color(0xFFFF6B5A) else cx.dim, modifier = Modifier.align(Alignment.End))
                warnings.forEach { Text("• $it", fontSize = 12.sp, color = Color(0xFFFF9F0A)) }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (tn in CaptionTone.entries) Text(tn.label, Modifier.cxChip(tn == tone, cx.accent) { tone = tn }, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ seed++; ideas = Captions.generate(p.title, p.network, tone, seed = seed).map { it.text } }) { Text(if (ideas.isEmpty()) "Écrire la légende" else "Autres idées") }
                    if (p.caption.isNotBlank()) TextButton({
                        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Légende", p.caption))
                        Toast.makeText(ctx, "Légende copiée", Toast.LENGTH_SHORT).show()
                    }) { Text("Copier") }
                }
                ideas.forEach { idea ->
                    Text(idea, Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(CxDesign.Card).background(cx.panel2).clickable { p = p.copy(caption = idea); ideas = emptyList() }.padding(10.dp), fontSize = 12.sp)
                }
                OutlinedTextField(p.note, { p = p.copy(note = it.take(1000)) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Notes (plans, musique, idées…)") })
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Me le rappeler", fontSize = 15.sp)
                        Text("Notification à l'heure prévue", fontSize = 12.sp, color = cx.dim)
                    }
                    Switch(p.remind, { p = p.copy(remind = it) }, colors = SwitchDefaults.colors(checkedThumbColor = cx.onAccent, checkedTrackColor = cx.accent))
                }
            }
        },
        confirmButton = { TextButton({ onSave(p.copy(title = p.title.trim())) }) { Text("Enregistrer", fontWeight = FontWeight.SemiBold) } },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onDelete) { Text("Supprimer", color = Color(0xFFFF6B5A)) }
                TextButton(onDismiss) { Text("Annuler") }
            }
        })
}

@Composable
private fun FieldLabel(t: String) = Text(t, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
