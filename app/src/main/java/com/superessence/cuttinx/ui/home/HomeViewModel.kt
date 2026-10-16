package com.superessence.cuttinx.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.CuttinxApp
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.project.ProjectArchive
import com.superessence.cuttinx.core.project.ProjectSummary
import com.superessence.cuttinx.core.project.RecoveryInfo
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import com.superessence.cuttinx.templates.ProjectTemplate
import com.superessence.cuttinx.templates.Templates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    val c = (app as CuttinxApp).container
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects
    private val _recoveries = MutableStateFlow<List<RecoveryInfo>>(emptyList())
    val recoveries: StateFlow<List<RecoveryInfo>> = _recoveries
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow<String?>(null)
    val templates = Templates(c.resources)
    private val thumbs = File(app.filesDir, "project_thumbs").also { it.mkdirs() }

    init { refresh(); viewModelScope.launch(Dispatchers.IO) { _recoveries.value = c.store.pendingRecoveries() } }

    fun refresh() { viewModelScope.launch(Dispatchers.IO) { _projects.value = c.store.list() } }

    fun create(name: String, format: ProjectFormat): Project {
        val now = System.currentTimeMillis()
        val p = Project(Ids.new("prj"), name.ifBlank { "Projet ${_projects.value.size + 1}" }, now, now, format)
        c.store.save(p)
        refresh()
        return p
    }

    /** Creates a project directly from picked media (quick start). */
    suspend fun createFromMedia(uris: List<Uri>, format: ProjectFormat): Pair<Project?, List<String>> = withContext(Dispatchers.IO) {
        val res = c.importer.import(uris)
        if (res.assets.isEmpty()) return@withContext null to res.errors
        var p = create("", format)
        for (a in res.assets) p = TimelineEditor.appendAsset(p, a).first
        // pick the format of the first visual media when requested by "auto"
        c.store.save(p)
        refresh()
        p to res.errors
    }

    fun fromTemplate(t: ProjectTemplate, onDone: (Project) -> Unit) {
        viewModelScope.launch {
            busy.value = "Préparation du modèle (génération des ressources)…"
            val p = withContext(Dispatchers.Default) { templates.build(t) }
            withContext(Dispatchers.IO) { c.store.save(p) }
            busy.value = null
            refresh(); onDone(p)
        }
    }

    fun rename(id: String, name: String) = io { c.store.rename(id, name) }
    fun duplicate(id: String) = io { val src = c.store.load(id); c.store.duplicate(id, src.name + " (copie)") }
    fun archive(id: String, archived: Boolean) = io { c.store.setArchived(id, archived) }
    fun delete(id: String) = io { c.store.delete(id) }

    fun acceptRecovery(r: RecoveryInfo) = io { c.store.acceptRecovery(r); _recoveries.value = _recoveries.value - r; message.value = "Projet « ${r.autosave.name} » récupéré" }
    fun discardRecovery(r: RecoveryInfo) = io { c.store.discardRecovery(r.projectId); _recoveries.value = _recoveries.value - r }

    fun exportArchive(id: String, dest: Uri, includeMedia: Boolean) = io {
        val p = c.store.load(id)
        val app = getApplication<Application>()
        app.contentResolver.openOutputStream(dest)!!.use { out ->
            ProjectArchive.write(out, p, includeMedia) { assetId ->
                val a = p.asset(assetId) ?: return@write null
                runCatching { a.displayName to app.contentResolver.openInputStream(Uri.parse(a.uri))!! }.getOrNull()
            }
        }
        message.value = "Projet exporté" + if (includeMedia) " avec ses médias" else " (références uniquement)"
    }

    fun importArchive(src: Uri) = io {
        val app = getApplication<Application>()
        val mediaDir = File(app.filesDir, "media/imported_${System.currentTimeMillis()}")
        val imp = app.contentResolver.openInputStream(src)!!.use { ProjectArchive.read(it, mediaDir) }
        var p = imp.project.copy(name = imp.project.name + " (importé)", modifiedAtMs = System.currentTimeMillis())
        for ((assetId, f) in imp.mediaFiles) p = TimelineEditor.relinkMedia(p, assetId, Uri.fromFile(f).toString())
        p = p.copy(media = c.importer.refreshMissing(p.media))
        c.store.save(p)
        message.value = if (p.missingMedia.isEmpty()) "Projet importé" else "Projet importé — ${p.missingMedia.size} média(s) à relier"
    }

    fun thumbnailFile(id: String) = File(thumbs, "$id.jpg")

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (e: Exception) { message.value = "Erreur : ${e.message}" }
            _projects.value = c.store.list()
        }
    }
}
