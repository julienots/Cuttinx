package com.superessence.cuttinx.core.overlay

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Saves the Twitch Editor settings on disk (Android and PC). The settings file stays small: the
 * pictures (logo, free elements) are written once each in their own file, so saving after every
 * change stays instant even with many pictures. Every write goes to a temporary file first and
 * then replaces the old one, so a crash or a full disk never leaves a half-written file.
 */
class OverlayStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }
    private val file get() = File(dir, "overlay.json")
    private val pics get() = File(dir, "images")

    fun exists() = file.isFile

    @Synchronized fun load(): OverlayConfig? {
        // the previous save is used when the last one is damaged
        val cfg = listOf(file, File(dir, "overlay.json.bak")).firstNotNullOfOrNull { f ->
            runCatching { json.decodeFromString(OverlayConfig.serializer(), f.readText()) }.getOrNull()
        } ?: return null
        fun pic(ref: String?): String? = if (ref != null && ref.startsWith(REF)) runCatching { File(pics, ref.removePrefix(REF)).readText() }.getOrNull() else ref
        return cfg.copy(logo = pic(cfg.logo), items = cfg.items.map { it.copy(data = pic(it.data) ?: "") },
            backgrounds = cfg.backgrounds.mapValues { (_, b) -> b.copy(image = pic(b.image) ?: "") })
    }

    /** Writes [cfg]; throws when the disk refuses (the caller tells the user). */
    @Synchronized fun save(cfg: OverlayConfig) {
        dir.mkdirs(); pics.mkdirs()
        val used = HashSet<String>()
        fun ref(b64: String?): String? {
            if (b64.isNullOrEmpty() || b64.startsWith(REF)) return b64
            val name = names.getOrPut(b64) { digest(b64) }
            used += name
            val f = File(pics, name)
            if (!f.isFile || f.length() != b64.length.toLong()) write(f, b64)
            return REF + name
        }
        val slim = cfg.copy(logo = ref(cfg.logo), items = cfg.items.map { it.copy(data = ref(it.data) ?: "") },
            backgrounds = cfg.backgrounds.mapValues { (_, b) -> b.copy(image = ref(b.image) ?: "") })
        val text = json.encodeToString(OverlayConfig.serializer(), slim)
        if (file.isFile) runCatching { Files.copy(file.toPath(), File(dir, "overlay.json.bak").toPath(), StandardCopyOption.REPLACE_EXISTING) }
        write(file, text)
        // pictures deleted by the streamer are removed from the disk too
        pics.listFiles()?.forEach { if (it.name !in used && it.name.endsWith(".b64")) it.delete() }
    }

    /** File name of a picture (content hash), remembered so unchanged pictures are not hashed again. */
    private val names = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
    }

    private fun digest(b64: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(b64.toByteArray(Charsets.ISO_8859_1))
        return "p" + d.joinToString("") { "%02x".format(it) } + ".b64"
    }

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object { const val REF = "file:" }
}

/**
 * Undo / redo of the Twitch Editor. Changes sent with the same [key] in quick succession (a drag,
 * a slider, typing) make a single step.
 */
class UndoHistory<T>(private val limit: Int = 200) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    private var lastKey: String? = null
    private var lastAt = 0L

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Call before replacing [before] with a new value. */
    fun record(before: T, key: String? = null, now: Long = System.currentTimeMillis()) {
        val merge = key != null && key == lastKey && now - lastAt < 1500
        lastKey = key; lastAt = now
        redo.clear()
        if (merge) return
        undo.addLast(before)
        while (undo.size > limit) undo.removeFirst()
    }

    /** Ends the current group (next change starts a new step). */
    fun cut() { lastKey = null }

    /** Forgets every step (another document is opened). */
    fun clear() { undo.clear(); redo.clear(); lastKey = null }

    fun undo(current: T): T? = undo.removeLastOrNull()?.also { redo.addLast(current); lastKey = null }
    fun redo(current: T): T? = redo.removeLastOrNull()?.also { undo.addLast(current); lastKey = null }
}
