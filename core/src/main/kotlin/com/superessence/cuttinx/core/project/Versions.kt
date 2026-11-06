package com.superessence.cuttinx.core.project

import com.superessence.cuttinx.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream

/** One saved state of a project ("Historique des versions"). */
@Serializable
data class VersionInfo(
    val id: String,
    val projectId: String,
    val atMs: Long,
    val label: String,
    /** true = saved by the app (periodic / before a big operation), false = named by the user. */
    val auto: Boolean,
    val durationUs: Long,
    val clipCount: Int,
)

/**
 * Time machine of a project: full snapshots that can be browsed, compared with the current
 * montage and restored. Automatic snapshots are thinned out (the last [keepAuto] are kept, and
 * older ones only one per hour); versions named by the user are never removed.
 */
class VersionStore(private val root: File, private val keepAuto: Int = 24) {
    private fun dir(projectId: String) = File(root, projectId).also { it.mkdirs() }
    private fun file(info: VersionInfo) = File(dir(info.projectId), "${info.id}.json")
    private fun meta(info: VersionInfo) = File(dir(info.projectId), "${info.id}.meta")

    /** Saves [p]. Returns null when nothing changed since the previous snapshot. */
    @Synchronized
    fun snapshot(p: Project, label: String, auto: Boolean, nowMs: Long = System.currentTimeMillis()): VersionInfo? {
        val last = list(p.id).firstOrNull()
        val body = ProjectStore.encode(p.copy(modifiedAtMs = 0))
        val same = last != null && runCatching { ProjectStore.encode(load(last)!!.copy(modifiedAtMs = 0)) == body }.getOrDefault(false)
        if (same && (auto || last!!.label == label)) return null
        val info = VersionInfo("v%013d".format(nowMs), p.id, nowMs, label, auto, p.durationUs, p.allClips.size)
        write(file(info), ProjectStore.encode(p))
        write(meta(info), ProjectStore.json.encodeToString(info))
        prune(p.id)
        return info
    }

    /** Newest first. */
    fun list(projectId: String): List<VersionInfo> = dir(projectId).listFiles { f -> f.extension == "meta" }.orEmpty()
        .mapNotNull { runCatching { ProjectStore.json.decodeFromString<VersionInfo>(it.readText()) }.getOrNull() }
        .filter { File(dir(projectId), "${it.id}.json").exists() }
        .sortedByDescending { it.atMs }

    fun load(info: VersionInfo): Project? = runCatching { ProjectStore.decode(file(info).readText()) }.getOrNull()

    fun delete(info: VersionInfo) { file(info).delete(); meta(info).delete() }

    fun rename(info: VersionInfo, label: String): VersionInfo {
        val n = info.copy(label = label, auto = false)
        write(meta(n), ProjectStore.json.encodeToString(n))
        return n
    }

    fun deleteAll(projectId: String) { File(root, projectId).deleteRecursively() }

    private fun prune(projectId: String) {
        val autos = list(projectId).filter { it.auto }
        if (autos.size <= keepAuto) return
        val keep = HashSet<String>()
        autos.take(keepAuto / 2).forEach { keep += it.id }
        // older automatic versions: at most one per hour
        val hours = HashSet<Long>()
        for (v in autos.drop(keepAuto / 2)) if (hours.add(v.atMs / 3_600_000) && keep.size < keepAuto) keep += v.id
        autos.filter { it.id !in keep }.forEach { delete(it) }
    }

    private fun write(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(content.toByteArray(Charsets.UTF_8)); it.flush(); it.fd.sync() }
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
}

/** Human readable difference between two states of a project. */
object ProjectDiff {
    fun summary(old: Project, now: Project): List<String> {
        val out = ArrayList<String>()
        fun sec(us: Long) = "%.1f s".format(us / 1e6)
        if (old.durationUs != now.durationUs) {
            val d = now.durationUs - old.durationUs
            out += "Durée : ${sec(old.durationUs)} → ${sec(now.durationUs)} (${if (d > 0) "+" else "-"}${sec(kotlin.math.abs(d))})"
        }
        if (old.format.width != now.format.width || old.format.height != now.format.height || old.format.frameRate != now.format.frameRate)
            out += "Format : ${old.format.width}×${old.format.height} ${old.format.frameRate} i/s → ${now.format.width}×${now.format.height} ${now.format.frameRate} i/s"
        val oldIds = old.allClips.map { it.id }.toSet(); val nowIds = now.allClips.map { it.id }.toSet()
        val added = now.allClips.filter { it.id !in oldIds }
        val removed = old.allClips.filter { it.id !in nowIds }
        fun kinds(l: List<Clip>, p: Project): String {
            val texts = l.count { it.text != null }
            val audio = l.count { c -> p.tracks.any { t -> t.kind == TrackKind.AUDIO && t.clips.any { it.id == c.id } } }
            val rest = l.size - texts - audio
            return listOfNotNull(rest.takeIf { it > 0 }?.let { "$it plan(s)" }, texts.takeIf { it > 0 }?.let { "$it texte(s)" }, audio.takeIf { it > 0 }?.let { "$it son(s)" }).joinToString(", ")
        }
        if (added.isNotEmpty()) out += "Ajouté depuis : ${kinds(added, now)}"
        if (removed.isNotEmpty()) out += "Retiré depuis : ${kinds(removed, old)}"
        val oldById = old.allClips.associateBy { it.id }
        val common = now.allClips.filter { it.id in oldById }
        val moved = common.count { it.startUs != oldById[it.id]!!.startUs || it.durationUs != oldById[it.id]!!.durationUs }
        if (moved > 0) out += "$moved élément(s) déplacé(s) ou redécoupé(s)"
        val fx = common.count { it.effects.map { e -> e.effectId } != oldById[it.id]!!.effects.map { e -> e.effectId } }
        if (fx > 0) out += "Effets modifiés sur $fx élément(s)"
        val texts = common.count { it.text?.text != oldById[it.id]!!.text?.text }
        if (texts > 0) out += "Texte modifié sur $texts élément(s)"
        val anim = common.count { it.keyframes != oldById[it.id]!!.keyframes || it.motion != oldById[it.id]!!.motion }
        if (anim > 0) out += "Animations modifiées sur $anim élément(s)"
        if (old.markers.size != now.markers.size) out += "Marqueurs : ${old.markers.size} → ${now.markers.size}"
        if (old.tracks.size != now.tracks.size) out += "Pistes : ${old.tracks.size} → ${now.tracks.size}"
        return out
    }
}

/**
 * Crash reports and crash-loop detection, shared by the Android app and the PC app. A report is a
 * small text file kept next to the projects; [recentCrashes] tells the start screen whether the
 * app keeps closing, so it can open in safe mode.
 */
class CrashLog(private val dir: File, private val maxFiles: Int = 20) {
    init { dir.mkdirs() }

    fun write(thread: String, e: Throwable, context: String, nowMs: Long = System.currentTimeMillis()): File {
        val f = File(dir, "crash-%013d.txt".format(nowMs))
        val text = buildString {
            appendLine("CUTTINX — rapport d'incident")
            appendLine("Date : $nowMs")
            appendLine("Fil d'exécution : $thread")
            appendLine(context)
            appendLine()
            appendLine(e.stackTraceToString().take(12_000))
        }
        runCatching { f.writeText(text) }
        files().drop(maxFiles).forEach { it.delete() }
        return f
    }

    fun files(): List<File> = dir.listFiles { x -> x.name.startsWith("crash-") }.orEmpty().sortedByDescending { it.name }

    fun timeOf(f: File): Long = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0

    /** Reports written during the last [windowMs]. */
    fun recentCrashes(nowMs: Long = System.currentTimeMillis(), windowMs: Long = 10 * 60_000): List<File> = files().filter { nowMs - timeOf(it) in 0..windowMs }

    /** Three crashes in ten minutes: the app should start in safe mode. */
    fun looping(nowMs: Long = System.currentTimeMillis()) = recentCrashes(nowMs).size >= 3

    /** Last report not yet shown to the user, or null. */
    fun unseen(): File? = files().firstOrNull { !File(dir, it.name + ".seen").exists() }
    fun markSeen(f: File) { runCatching { File(dir, f.name + ".seen").writeText("1") } }
}
