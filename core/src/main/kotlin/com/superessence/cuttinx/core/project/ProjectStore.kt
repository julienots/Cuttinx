package com.superessence.cuttinx.core.project

import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.util.Ids
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class ProjectSummary(val id: String, val name: String, val modifiedAtMs: Long, val durationUs: Long, val clipCount: Int, val archived: Boolean, val thumbnailPath: String?, val width: Int, val height: Int)

data class RecoveryInfo(val projectId: String, val autosave: Project, val savedAtMs: Long)

/**
 * File based project storage:
 *  root/<id>/project.json      current version (atomic writes)
 *  root/<id>/project.bak<N>     previous versions
 *  root/<id>/autosave.json      frequent autosave
 *  root/<id>/session.lock       present while the project is open (crash detection)
 */
class ProjectStore(val root: File, private val backups: Int = 5) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false; coerceInputValues = true }
        fun encode(p: Project): String = json.encodeToString(Project.serializer(), p)
        fun decode(s: String): Project = json.decodeFromString(Project.serializer(), s)
    }

    init { root.mkdirs() }

    fun dir(id: String) = File(root, id).also { it.mkdirs() }
    private fun main(id: String) = File(dir(id), "project.json")
    private fun autosave(id: String) = File(dir(id), "autosave.json")
    private fun lock(id: String) = File(dir(id), "session.lock")

    fun exists(id: String) = main(id).exists()

    fun list(): List<ProjectSummary> = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
        val p = runCatching { load(d.name) }.getOrNull() ?: return@mapNotNull null
        ProjectSummary(p.id, p.name, p.modifiedAtMs, p.durationUs, p.allClips.size, p.archived, p.thumbnailPath, p.format.width, p.format.height)
    }?.sortedByDescending { it.modifiedAtMs } ?: emptyList()

    fun load(id: String): Project {
        val f = main(id)
        val candidates = listOf(f) + (1..backups).map { File(dir(id), "project.bak$it") }
        var lastError: Exception? = null
        for (c in candidates) {
            if (!c.exists()) continue
            try { return decode(c.readText()) } catch (e: Exception) { lastError = e }
        }
        throw lastError ?: java.io.FileNotFoundException("Projet $id introuvable")
    }

    @Synchronized
    fun save(p: Project) {
        val f = main(p.id)
        if (f.exists()) rotateBackups(p.id)
        atomicWrite(f, encode(p))
        autosave(p.id).delete()
    }

    private fun rotateBackups(id: String) {
        val d = dir(id)
        File(d, "project.bak$backups").delete()
        for (i in backups - 1 downTo 1) File(d, "project.bak$i").takeIf { it.exists() }?.renameTo(File(d, "project.bak${i + 1}"))
        main(id).copyTo(File(d, "project.bak1"), overwrite = true)
    }

    /** Lightweight periodic save; does not rotate backups. */
    @Synchronized
    fun autosave(p: Project) = atomicWrite(autosave(p.id), encode(p))

    fun delete(id: String) = File(root, id).deleteRecursively()

    fun duplicate(id: String, newName: String, now: Long = System.currentTimeMillis()): Project {
        val p = load(id)
        val copy = p.copy(id = Ids.new("prj"), name = newName, createdAtMs = now, modifiedAtMs = now, thumbnailPath = null)
        save(copy)
        return copy
    }

    fun rename(id: String, name: String) { val p = load(id); save(p.copy(name = name)) }
    fun setArchived(id: String, archived: Boolean) { val p = load(id); save(p.copy(archived = archived)) }

    // --------------------------------------------------------------- crash recovery

    fun openSession(id: String) = atomicWrite(lock(id), System.currentTimeMillis().toString())
    fun closeSession(id: String) { lock(id).delete() }

    /** Projects whose session was not closed cleanly and that have a newer autosave. */
    fun pendingRecoveries(): List<RecoveryInfo> = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
        val id = d.name
        val a = autosave(id)
        if (!lock(id).exists() && !a.exists()) return@mapNotNull null
        if (!a.exists()) { lock(id).delete(); return@mapNotNull null }
        val auto = runCatching { decode(a.readText()) }.getOrNull() ?: return@mapNotNull null
        val mainTime = if (main(id).exists()) main(id).lastModified() else 0
        if (a.lastModified() >= mainTime) RecoveryInfo(id, auto, a.lastModified()) else null
    } ?: emptyList()

    fun acceptRecovery(info: RecoveryInfo) { save(info.autosave); closeSession(info.projectId) }
    fun discardRecovery(id: String) { autosave(id).delete(); closeSession(id) }

    // --------------------------------------------------------------- utils

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("Impossible d'écrire ${target.name}")
        }
    }
}

/**
 * Portable project archive (.cuttinx = zip): project.json + optionally the media files.
 * Media entries are named media/<assetId>/<fileName>.
 */
object ProjectArchive {
    data class Imported(val project: Project, val mediaFiles: Map<String, File>)

    fun write(out: OutputStream, project: Project, includeMedia: Boolean, openMedia: (assetId: String) -> Pair<String, InputStream>?) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(ProjectStore.encode(project).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write("CUTTINX project archive\nformat=1\nmedia=$includeMedia\n".toByteArray())
            zip.closeEntry()
            if (includeMedia) for (a in project.media) {
                val (name, input) = openMedia(a.id) ?: continue
                input.use {
                    zip.putNextEntry(ZipEntry("media/${a.id}/${name.replace('/', '_')}"))
                    it.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
    }

    /** Reads an archive; media are extracted into [mediaDir] and the project gets a new id. */
    fun read(input: InputStream, mediaDir: File): Imported {
        var project: Project? = null
        val media = HashMap<String, File>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (name.contains("..")) continue // zip-slip protection
                when {
                    name == "project.json" -> project = ProjectStore.decode(zip.readBytes().toString(Charsets.UTF_8))
                    name.startsWith("media/") && !e.isDirectory -> {
                        val parts = name.removePrefix("media/").split("/", limit = 2)
                        if (parts.size == 2) {
                            val f = File(File(mediaDir, parts[0]).also { it.mkdirs() }, parts[1])
                            f.outputStream().use { zip.copyTo(it) }
                            media[parts[0]] = f
                        }
                    }
                }
            }
        }
        val p = project ?: throw java.io.IOException("Archive invalide : project.json manquant")
        return Imported(p.copy(id = Ids.new("prj")), media)
    }
}
