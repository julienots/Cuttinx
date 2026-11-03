package com.superessence.cuttinx.claude

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.CuttinxApp
import com.superessence.cuttinx.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Écran « Claude » : connexion au relais MCP et médias partagés avec Claude. */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = (ctx.applicationContext as CuttinxApp).container
    val lib = remember { ClaudeLibrary.get(ctx) }
    val state by ClaudeLink.state.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf(lib.all()) }
    var mcpUrl by remember { mutableStateOf(ClaudeLink.mcpUrl(ctx)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SharedMedia?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val enabled = ClaudeLink.isEnabled(ctx) || state.enabled
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(state.revision) { items = lib.all() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Ajout de ${uris.size} fichier(s)…"
            val res = withContext(Dispatchers.IO) { c.importer.import(uris) }
            res.assets.forEach { lib.add(SharedMedia(it)) }
            items = lib.all(); busy = null
            snack.showSnackbar(if (res.errors.isEmpty()) "${res.assets.size} fichier(s) partagé(s) avec Claude" else res.errors.first())
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(title = { Text("Claude") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picker.launch(arrayOf("video/*", "image/*", "audio/*")) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Ajouter des médias") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val dot = when (if (enabled) state.relay else "off") { "online" -> Color(0xFF30A46C); "connecting" -> Color(0xFFF5A524); "error" -> Color(0xFFE5484D); else -> Color.Gray }
                        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Connecté à Claude", fontWeight = FontWeight.Bold)
                            Text(
                                when (if (enabled) state.relay else "off") {
                                    "online" -> if (state.lastAction.isNotEmpty()) "En ligne · Claude ${state.lastAction}" else "En ligne : Claude peut voir tes médias partagés et créer des projets"
                                    "connecting" -> "Connexion…"
                                    "error" -> "Pas de réseau, nouvel essai… ${state.message}"
                                    else -> "Désactivé"
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(enabled, { on -> if (on) ClaudeService.start(ctx) else ClaudeService.stop(ctx) })
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ajouter CUTTINX dans Claude", fontWeight = FontWeight.Bold)
                        Text("Copie cette adresse et ajoute-la comme connecteur personnalisé dans Claude (une seule fois) :", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(mcpUrl, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            TextButton({ copy(ctx, mcpUrl); scope.launch { snack.showSnackbar("Adresse copiée") } }) { Text("Copier") }
                        }
                        Text("1. claude.ai ou l'app Claude : Paramètres → Connecteurs → Ajouter un connecteur personnalisé.\n2. Nom : CUTTINX, URL : l'adresse ci-dessus.\n3. Active « Connecté à Claude », ajoute tes médias, puis demande ton edit dans une conversation.", style = MaterialTheme.typography.bodySmall)
                        Text("Tes vidéos restent sur ton téléphone : Claude ne reçoit que des images d'aperçu. Les projets qu'il crée apparaissent dans tes projets CUTTINX et restent modifiables.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("⚠️ Cette adresse donne accès à tes médias partagés : ne la partage avec personne.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFF5A524))
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Médias partagés avec Claude (${items.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (items.isEmpty()) item {
                Text("Aucun média. Appuie sur « Ajouter des médias » : Claude ne voit que les fichiers ajoutés ici.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(items, key = { it.asset.id }) { m ->
                MediaRow(m, onEdit = { editing = m }, onRemove = { lib.remove(m.asset.id); items = lib.all() })
            }
            item { TextButton({ confirmReset = true }, Modifier.fillMaxWidth()) { Text("Créer une nouvelle adresse secrète") } }
        }
    }

    editing?.let { m ->
        var notes by remember(m.asset.id) { mutableStateOf(m.notes) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(m.asset.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { OutlinedTextField(notes, { notes = it }, label = { Text("Notes / instructions pour Claude") }, placeholder = { Text("Ex. : garder la planche vers 0:45") }, minLines = 3) },
            confirmButton = { TextButton({ lib.update(m.asset.id) { it.copy(notes = notes) }; items = lib.all(); editing = null }) { Text("Enregistrer") } },
            dismissButton = { TextButton({ editing = null }) { Text("Annuler") } })
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("Nouvelle adresse ?") },
        text = { Text("L'ancienne adresse ne fonctionnera plus : il faudra mettre à jour le connecteur dans Claude.") },
        confirmButton = { TextButton({
            ClaudeLink.newToken(ctx); mcpUrl = ClaudeLink.mcpUrl(ctx); confirmReset = false
            if (ClaudeLink.isEnabled(ctx)) { ClaudeService.stop(ctx); ClaudeService.start(ctx) }
        }) { Text("Créer") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Annuler") } })
}

@UnstableApi
@Composable
private fun MediaRow(m: SharedMedia, onEdit: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val c = (ctx.applicationContext as CuttinxApp).container
    var thumb by remember(m.asset.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(m.asset.id) {
        if (m.asset.type != MediaType.AUDIO) thumb = runCatching { c.thumbnails.frame(m.asset, minOf(1_000_000L, m.asset.durationUs / 2), 160) }.getOrNull()
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                val t = thumb
                if (t != null) Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(when (m.asset.type) { MediaType.AUDIO -> "🎵"; MediaType.IMAGE -> "🖼️"; else -> "🎞️" })
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text((if (m.kind == "render") "✨ " else "") + m.asset.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                val d = m.asset.durationUs / 1_000_000
                Text(listOfNotNull(if (d > 0) "%d:%02d".format(d / 60, d % 60) else null, m.notes.ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onEdit) { Icon(Icons.Outlined.EditNote, "Notes") }
            IconButton(onRemove) { Icon(Icons.Outlined.Delete, "Retirer") }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CUTTINX MCP", text))
}
