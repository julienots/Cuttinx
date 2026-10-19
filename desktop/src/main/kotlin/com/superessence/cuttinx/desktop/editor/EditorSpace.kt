package com.superessence.cuttinx.desktop.editor

import com.superessence.cuttinx.icons.CxIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.model.AspectPreset
import com.superessence.cuttinx.desktop.AppState
import com.superessence.cuttinx.desktop.Cx
import com.superessence.cuttinx.desktop.engine.EditorModel
import com.superessence.cuttinx.desktop.ui.Chips
import com.superessence.cuttinx.desktop.ui.Hint
import com.superessence.cuttinx.desktop.ui.LocalAccent
import com.superessence.cuttinx.desktop.ui.Title
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** "Éditeur" space of the PC app: the project list, then the full video editor. */
@Composable
fun EditorSpace(state: AppState) {
    val m = state.editor
    val project by m.project.collectAsState()
    if (project == null) ProjectsScreen(m) else EditorScreen(m)
    val msg by m.message.collectAsState()
    LaunchedEffect(msg) { if (msg != null) { kotlinx.coroutines.delay(3500); if (m.message.value == msg) m.message.value = null } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        msg?.let {
            Text(it, Modifier.padding(bottom = 24.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xEE000000)).padding(horizontal = 18.dp, vertical = 10.dp), color = Color.White)
        }
    }
}

private val formats = listOf(
    Triple(AspectPreset.PORTRAIT_9_16, "TikTok · Reels · Shorts", "9:16"),
    Triple(AspectPreset.LANDSCAPE_16_9, "YouTube · Twitch", "16:9"),
    Triple(AspectPreset.SQUARE_1_1, "Post carré", "1:1"),
    Triple(AspectPreset.PORTRAIT_4_5, "Post Instagram", "4:5"),
    Triple(AspectPreset.CINEMA_21_9, "Cinéma", "21:9"),
)

@Composable
private fun ProjectsScreen(m: EditorModel) {
    val projects by m.projects.collectAsState()
    var name by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(AspectPreset.PORTRAIT_9_16) }
    var fps by remember { mutableStateOf(30) }
    LaunchedEffect(Unit) { m.refreshProjects() }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(380.dp).fillMaxHeight().background(Cx.Panel).padding(20.dp)) {
            Text("Nouveau montage", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Hint("Montage vidéo pro : découpe, effets, transitions, textes animés, musique, bruitages, voix, export MP4.")
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nom du projet") })
            Title("FORMAT")
            formats.forEach { (a, label, ratio) ->
                val sel = a == aspect
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) LocalAccent.current else Cx.Panel2).clickable { aspect = a }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (w, h) = if (a.w >= a.h) 34 to (34 * a.h / a.w) else (34 * a.w / a.h) to 34
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(3.dp)).background(if (sel) Color.Black else Cx.Dim)) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(ratio, fontWeight = FontWeight.Bold, color = if (sel) Color.Black else Color.White)
                        Text(label, fontSize = 12.sp, color = if (sel) Color.Black else Cx.Dim)
                    }
                }
            }
            Title("IMAGES PAR SECONDE")
            Chips(listOf(24, 25, 30, 50, 60), fps, { "$it i/s" }, { fps = it })
            Spacer(Modifier.height(18.dp))
            Button({ m.create(name, aspect, fps); name = "" }, Modifier.fillMaxWidth().height(48.dp)) { Text("Créer le projet", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ openFiles("Importer un projet CUTTINX", listOf("cuttinx", "zip"), multiple = false).firstOrNull()?.let { m.importArchive(it) } }, Modifier.fillMaxWidth()) { Text("Importer un projet (.cuttinx du téléphone)") }
            Spacer(Modifier.weight(1f))
            Hint("Tes projets sont enregistrés automatiquement dans ${m.home.absolutePath}")
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
            Text("Mes projets", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            if (projects.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(CxIcons.Editor, null, Modifier.size(64.dp), tint = LocalAccent.current)
                        Text("Aucun projet pour l'instant", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Hint("Crée ton premier montage à gauche, ou importe un projet commencé sur ton téléphone.")
                    }
                }
            } else {
                val df = remember { SimpleDateFormat("dd/MM/yyyy HH:mm") }
                LazyVerticalGrid(GridCells.Adaptive(240.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(projects.filter { !it.archived }, key = { it.id }) { s ->
                        var confirm by remember { mutableStateOf(false) }
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Cx.Panel2).clickable { m.open(s.id) }.padding(14.dp)) {
                            Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0C0F)), contentAlignment = Alignment.Center) {
                                val ar = s.width.toFloat() / s.height.coerceAtLeast(1)
                                Box(Modifier.fillMaxHeight(0.8f).aspectRatio(ar.coerceIn(0.3f, 3f)).clip(RoundedCornerShape(4.dp)).background(Cx.Line), contentAlignment = Alignment.Center) { Icon(CxIcons.Play, null, Modifier.size(20.dp), tint = Cx.Dim) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(s.name, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("${timecode(s.durationUs)} · ${s.clipCount} éléments · ${s.width}×${s.height}", color = Cx.Dim, fontSize = 11.sp)
                            Text(df.format(Date(s.modifiedAtMs)), color = Cx.Dim, fontSize = 11.sp)
                            Row {
                                TextButton({ m.open(s.id) }) { Text("Ouvrir") }
                                TextButton({ m.duplicate(s.id) }) { Text("Dupliquer") }
                                TextButton({ if (confirm) m.delete(s.id) else confirm = true }) { Text(if (confirm) "Confirmer ?" else "Supprimer", color = if (confirm) Color(0xFFFF5A5A) else Cx.Dim) }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun timecode(us: Long, frames: Boolean = false, fps: Int = 30): String {
    val totalSec = us / 1_000_000
    val m = totalSec / 60; val s = totalSec % 60
    val base = "%d:%02d".format(m, s)
    return if (frames) base + ".%02d".format((us % 1_000_000) * fps / 1_000_000) else base
}

/** Native multi-file picker (AWT, looks like the system one on Windows). */
fun openFiles(title: String, extensions: List<String>, multiple: Boolean = true): List<File> {
    val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
    d.isMultipleMode = multiple
    if (extensions.isNotEmpty()) {
        d.setFilenameFilter { _, n -> extensions.any { n.lowercase().endsWith(".$it") } }
        // Windows ignores FilenameFilter: a wildcard pattern does the job there
        d.file = extensions.joinToString(";") { "*.$it" }
    }
    d.isVisible = true
    return d.files?.toList() ?: emptyList()
}

fun saveFile(title: String, suggested: String): File? {
    val d = FileDialog(null as Frame?, title, FileDialog.SAVE)
    d.file = suggested
    d.isVisible = true
    val f = d.file ?: return null
    return File(d.directory, f)
}
