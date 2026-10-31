package com.superessence.cuttinx.versions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.project.ProjectDiff
import com.superessence.cuttinx.core.project.VersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Dim = Color.White.copy(alpha = 0.62f)
private val CardBg = Color.White.copy(alpha = 0.06f)

/**
 * "Historique des versions" (Android and PC): every saved state of the montage, what changed
 * compared with now, and a one-tap restore (itself undoable). Versions are saved automatically
 * when a project opens, every five minutes, before the smart tools and before a restore.
 */
@Composable
fun VersionsPanel(
    current: Project,
    accent: Color,
    list: () -> List<VersionInfo>,
    load: (VersionInfo) -> Project?,
    onSaveNamed: (String) -> Unit,
    onRestore: (VersionInfo) -> Unit,
    onDelete: (VersionInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tick by remember { mutableIntStateOf(0) }
    var versions by remember { mutableStateOf<List<VersionInfo>>(emptyList()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(tick) { versions = withContext(Dispatchers.IO) { list() } }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Column(modifier) {
        Text("Historique des versions", fontWeight = FontWeight.Black, fontSize = 18.sp)
        Text("Reviens à n'importe quel moment du montage. Les versions sont enregistrées automatiquement ; celles que tu nommes ne sont jamais supprimées.", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it.take(40) }, Modifier.weight(1f), singleLine = true, label = { Text("Nom de la version") }, placeholder = { Text("ex. Avant le montage final") })
            Spacer(Modifier.width(8.dp))
            Button({ onSaveNamed(name.ifBlank { "Version du ${fmt.format(Date())}" }); name = ""; tick++ }, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) { Text("Enregistrer", fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(6.dp))
        if (versions.isEmpty()) Text("Aucune version pour l'instant.", color = Dim, modifier = Modifier.padding(vertical = 12.dp))
        versions.forEach { v ->
            val open = openId == v.id
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(CardBg).clickable { openId = if (open) null else v.id }.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${fmt.format(Date(v.atMs))} · ${"%.0f".format(v.durationUs / 1e6)} s · ${v.clipCount} éléments", fontSize = 11.sp, color = Dim)
                    }
                    if (!v.auto) Text("NOMMÉE", Modifier.clip(RoundedCornerShape(4.dp)).background(accent.copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 2.dp), color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                if (open) {
                    var diff by remember(v.id) { mutableStateOf<List<String>?>(null) }
                    LaunchedEffect(v.id, current) { diff = withContext(Dispatchers.IO) { load(v)?.let { ProjectDiff.summary(current, it) } ?: listOf("Version illisible") } }
                    Spacer(Modifier.height(6.dp))
                    val d = diff
                    when {
                        d == null -> Text("Comparaison…", fontSize = 12.sp, color = Dim)
                        d.isEmpty() -> Text("Identique au montage actuel.", fontSize = 12.sp, color = Dim)
                        else -> {
                            Text("En revenant à cette version :", fontSize = 11.sp, color = Dim)
                            d.forEach { Text("• $it", fontSize = 12.sp) }
                        }
                    }
                    Row {
                        TextButton({ onRestore(v); tick++; openId = null }) { Text("Restaurer cette version", color = accent, fontWeight = FontWeight.Bold) }
                        TextButton({ onDelete(v); tick++; openId = null }) { Text("Supprimer", color = Dim) }
                    }
                }
            }
        }
    }
}
