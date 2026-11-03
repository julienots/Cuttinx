package com.superessence.cuttinx.claude

import android.content.Context
import com.superessence.cuttinx.core.model.MediaAsset
import com.superessence.cuttinx.core.model.MediaType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Un média partagé avec Claude (vidéo, photo, musique) ou un rendu exporté pour Claude. */
@Serializable
data class SharedMedia(
    val asset: MediaAsset,
    val notes: String = "",
    /** upload = ajouté par l'utilisateur, render = vidéo exportée par Claude */
    val kind: String = "upload",
    val projectId: String? = null,
    val addedAtMs: Long = System.currentTimeMillis(),
)

/** Bibliothèque des médias que l'utilisateur a choisi de montrer à Claude (rien d'autre n'est visible). */
class ClaudeLibrary(ctx: Context) {
    private val file = File(ctx.filesDir, "claude/library.json").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val items = mutableListOf<SharedMedia>()

    init {
        runCatching { items.addAll(json.decodeFromString<List<SharedMedia>>(file.readText())) }
    }

    @Synchronized private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(items.toList()))
        tmp.renameTo(file)
    }

    @Synchronized fun all(): List<SharedMedia> = items.sortedByDescending { it.addedAtMs }

    @Synchronized fun get(id: String): SharedMedia? = items.firstOrNull { it.asset.id == id }

    fun mustGet(id: String): SharedMedia = get(id) ?: throw IllegalArgumentException("Média introuvable : $id (utilise list_media)")

    @Synchronized fun add(m: SharedMedia): SharedMedia {
        items.firstOrNull { it.asset.uri == m.asset.uri }?.let { return it }
        items.add(m); save(); return m
    }

    @Synchronized fun update(id: String, f: (SharedMedia) -> SharedMedia): SharedMedia {
        val i = items.indexOfFirst { it.asset.id == id }
        if (i < 0) throw IllegalArgumentException("Média introuvable : $id")
        items[i] = f(items[i]); save(); return items[i]
    }

    @Synchronized fun remove(id: String): SharedMedia? {
        val m = items.firstOrNull { it.asset.id == id } ?: return null
        items.remove(m); save(); return m
    }

    companion object {
        @Volatile private var instance: ClaudeLibrary? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: ClaudeLibrary(ctx.applicationContext).also { instance = it } }

        fun typeName(t: MediaType) = when (t) { MediaType.VIDEO -> "video"; MediaType.IMAGE -> "image"; MediaType.AUDIO -> "audio"; else -> t.name.lowercase() }
    }
}
