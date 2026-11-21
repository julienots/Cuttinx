package com.superessence.cuttinx.ui.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.AppContainer
import com.superessence.cuttinx.core.create.ScriptOptions
import com.superessence.cuttinx.core.create.ScriptStudio
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.model.ProjectFormat
import com.superessence.cuttinx.core.util.Ids
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.script.ScriptColors
import com.superessence.cuttinx.script.ScriptStudioForm
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** « Studio Script » (4.0) : a text becomes a complete, editable video, then opens in the editor. */
@UnstableApi
@Composable
fun ScriptStudioScreen(c: AppContainer, onBack: () -> Unit, openProject: (String) -> Unit) {
    val cx = LocalCx.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf(listOf<android.net.Uri>()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { if (it.isNotEmpty()) media = it }
    Scaffold(topBar = { StudioBar("Studio Script", "4.0", onBack, null) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            ScriptStudioForm(
                ScriptColors(cx.accent, cx.onAccent, cx.panel, cx.line, MaterialTheme.colorScheme.onSurface, cx.dim),
                busy = busy != null,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                mediaSlot = {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (media.isEmpty()) "Fonds animés (ou ajoute tes photos / vidéos)" else "${media.size} photo(s) / vidéo(s) en fond",
                            Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        if (media.isNotEmpty()) TextButton({ media = emptyList() }) { Text("Retirer", color = cx.dim) }
                        OutlinedButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, shape = CxDesign.Button) { Text("Choisir") }
                    }
                },
                onCreate = { req ->
                    scope.launch {
                        busy = "Création de la vidéo…"
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                val assets = if (media.isEmpty()) emptyList() else { busy = "Import des photos…"; c.importer.import(media).assets }
                                val music = if (req.music) { busy = "Composition de la musique…"; c.resources.musicAsset(req.style.musicId) } else null
                                busy = "Mise en scène…"
                                val now = System.currentTimeMillis()
                                val base = Project(Ids.new("prj"), "", now, now, ProjectFormat.forAspect(req.aspect))
                                val p = ScriptStudio.build(base, req.script, req.style, ScriptOptions(music = music, media = assets, progressBar = req.progressBar, endCard = req.endCard))
                                c.store.save(p)
                                p
                            }
                        }
                        busy = null
                        res.onSuccess { openProject(it.id) }.onFailure { error = it.message ?: "Création impossible" }
                    }
                },
            )
            busy?.let { BusyVeil(it) }
        }
    }
    error?.let { e -> AlertDialog({ error = null }, confirmButton = { TextButton({ error = null }) { Text("OK") } }, title = { Text("Studio Script") }, text = { Text(e) }) }
}
