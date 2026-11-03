package com.superessence.cuttinx.claude

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Base64
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.CuttinxApp
import com.superessence.cuttinx.core.analysis.BeatDetector
import com.superessence.cuttinx.core.analysis.HighlightDetector
import com.superessence.cuttinx.core.analysis.SceneDetector
import com.superessence.cuttinx.core.analysis.SilenceDetector
import com.superessence.cuttinx.core.analysis.VideoAnalysis
import com.superessence.cuttinx.core.audio.AudioAnalysis
import com.superessence.cuttinx.core.effects.EffectKind
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.ExportQuality
import com.superessence.cuttinx.core.model.MediaType
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.project.ExportPlanner
import com.superessence.cuttinx.engine.ExportState
import com.superessence.cuttinx.media.CodecInfo
import com.superessence.cuttinx.media.FrameSampler
import com.superessence.cuttinx.media.MediaProbe
import com.superessence.cuttinx.media.PcmDecoder
import com.superessence.cuttinx.media.Thumbnails
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Suivi d'un export lancé par Claude. */
class RenderJob(val id: String, val projectId: String, val name: String) {
    @Volatile var status = "running"
    @Volatile var progress = 0
    @Volatile var error: String? = null
    @Volatile var resultId: String? = null
    val createdAtMs = System.currentTimeMillis()
}

/**
 * Exécute sur le téléphone les outils MCP appelés par Claude et renvoie un résultat MCP
 * (`{content:[...], isError?}`).
 */
@UnstableApi
class ClaudeTools(private val ctx: Context) {
    private val c get() = (ctx.applicationContext as CuttinxApp).container
    private val lib = ClaudeLibrary.get(ctx)
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ format MCP
    private fun text(s: String) = JSONObject().put("type", "text").put("text", s)
    private fun image(b: Bitmap, q: Int = 72) = JSONObject().put("type", "image").put("data", jpeg(b, q)).put("mimeType", "image/jpeg")
    private fun ok(vararg parts: JSONObject) = JSONObject().put("content", JSONArray(parts.toList()))
    private fun json(o: Any) = ok(text(if (o is JSONObject) o.toString(2) else (o as JSONArray).toString(2)))
    private fun err(msg: String) = JSONObject().put("content", JSONArray().put(text(msg))).put("isError", true)

    fun handle(tool: String, a: JSONObject): JSONObject = try {
        when (tool) {
            "device_status" -> deviceStatus()
            "list_media" -> listMedia(a)
            "update_media" -> updateMedia(a)
            "view_frame" -> viewFrame(a)
            "view_storyboard" -> viewStoryboard(a)
            "detect_scenes" -> detectScenes(a)
            "find_highlights" -> findHighlights(a)
            "analyze_audio" -> analyzeAudio(a)
            "analyze_music" -> analyzeMusic(a)
            "list_effects" -> listEffects()
            "list_projects" -> listProjects()
            "get_project" -> json(ProjectSpecs.describe(c.store.load(a.getString("project_id"))))
            "create_project" -> createProject(a)
            "render_project" -> renderProject(a)
            "get_render" -> getRender(a)
            "import_from_url" -> importFromUrl(a)
            else -> err("Outil inconnu sur le téléphone : $tool (mets à jour CUTTINX)")
        }
    } catch (e: Throwable) {
        err(e.message ?: e.toString())
    }

    // ------------------------------------------------------------------ bibliothèque
    private fun mediaJson(m: SharedMedia) = JSONObject().apply {
        val a = m.asset
        put("id", a.id); put("name", a.displayName); put("type", ClaudeLibrary.typeName(a.type)); put("kind", m.kind)
        if (a.durationUs > 0) put("duration", r2(a.durationUs / 1e6))
        if (a.displayWidth > 0) { put("width", a.displayWidth); put("height", a.displayHeight) }
        if (a.frameRate > 0) put("fps", r2(a.frameRate.toDouble()))
        put("hasAudio", a.hasAudio)
        put("sizeMB", r2(a.sizeBytes / 1048576.0))
        if (m.notes.isNotBlank()) put("notes", m.notes)
        m.projectId?.let { put("projectId", it) }
    }

    private fun listMedia(a: JSONObject): JSONObject {
        val type = a.optString("type"); val kind = a.optString("kind"); val q = a.optString("search")
        val items = lib.all().filter { m ->
            (type.isEmpty() || ClaudeLibrary.typeName(m.asset.type) == type) && (kind.isEmpty() || m.kind == kind) &&
                (q.isEmpty() || (m.asset.displayName + " " + m.notes).contains(q, true))
        }
        return json(JSONObject().put("count", items.size).put("items", JSONArray(items.map { mediaJson(it) }))
            .put("hint", if (items.isEmpty()) "Aucun média partagé : demande à l'utilisateur d'ouvrir CUTTINX → Claude → « Ajouter des médias »." else ""))
    }

    private fun updateMedia(a: JSONObject): JSONObject {
        val m = lib.update(a.getString("media_id")) { m ->
            m.copy(notes = if (a.has("notes")) a.getString("notes") else m.notes,
                asset = if (a.has("name")) m.asset.copy(displayName = a.getString("name")) else m.asset)
        }
        ClaudeLink.bump()
        return json(mediaJson(m))
    }

    private fun importFromUrl(a: JSONObject): JSONObject {
        val conn = URL(a.getString("url")).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true; conn.connectTimeout = 20000; conn.readTimeout = 60000
        if (conn.responseCode !in 200..299) throw IllegalStateException("Téléchargement impossible (HTTP ${conn.responseCode})")
        var name = a.optString("name").ifEmpty { Uri.parse(a.getString("url")).lastPathSegment ?: "import" }
        if (!name.contains('.')) android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(conn.contentType?.substringBefore(';') ?: "")?.let { name += ".$it" }
        val dir = File(ctx.filesDir, "media/claude").also { it.mkdirs() }
        val dest = File(dir, System.currentTimeMillis().toString(36) + "_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        conn.inputStream.use { i -> dest.outputStream().use { i.copyTo(it) } }
        val asset = MediaProbe.probe(ctx, Uri.fromFile(dest))
        val m = lib.add(SharedMedia(asset))
        ClaudeLink.bump("importe un fichier")
        return json(mediaJson(m))
    }

    private fun deviceStatus(): JSONObject {
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / max(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) }
        return json(JSONObject().put("connected", true).put("app", "CUTTINX").put("device", android.os.Build.MODEL)
            .put("freeSpaceGB", r2(st.availableBytes / 1e9)).put("batteryPercent", level)
            .put("sharedMedia", lib.all().size).put("projects", c.store.list().size)
            .put("exportRunning", c.export.isRunning))
    }

    // ------------------------------------------------------------------ images
    private fun jpeg(b: Bitmap, q: Int): String {
        val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, q, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    private fun fitSize(m: SharedMedia, width: Int): Pair<Int, Int> {
        val ar = max(1, m.asset.displayWidth).toDouble() / max(1, m.asset.displayHeight)
        return if (ar >= 1) width to (width / ar).roundToInt() else (width * ar).roundToInt() to width
    }

    private fun grab(r: MediaMetadataRetriever, tS: Double, w: Int, h: Int, exact: Boolean): Bitmap? {
        val us = (tS * 1e6).toLong()
        val opt = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        return r.getScaledFrameAtTime(us, opt, w, h) ?: r.getFrameAtTime(us, opt)
    }

    private fun <T> retriever(m: SharedMedia, f: (MediaMetadataRetriever) -> T): T {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(ctx, Uri.parse(m.asset.uri)); return f(r) } finally { r.release() }
    }

    private fun viewFrame(a: JSONObject): JSONObject {
        val m = lib.mustGet(a.getString("media_id"))
        val width = a.optInt("width", 768).coerceIn(160, 1280)
        val t = a.optDouble("time", 0.0)
        val bmp = when (m.asset.type) {
            MediaType.AUDIO -> throw IllegalArgumentException("Fichier audio : pas d'image.")
            MediaType.IMAGE -> Thumbnails.decodeImage(ctx, Uri.parse(m.asset.uri), width, m.asset.rotationDegrees)
            else -> { val (w, h) = fitSize(m, width); retriever(m) { grab(it, min(t, max(0.0, m.asset.durationUs / 1e6 - 0.05)), w, h, true) } }
        } ?: throw IllegalStateException("Impossible d'extraire l'image")
        return ok(text("${m.asset.displayName} @ ${fmt(t)}"), image(bmp))
    }

    private fun viewStoryboard(a: JSONObject): JSONObject {
        val m = lib.mustGet(a.getString("media_id"))
        if (m.asset.type != MediaType.VIDEO) throw IllegalArgumentException("view_storyboard ne fonctionne que sur les vidéos.")
        val dur = m.asset.durationUs / 1e6
        val start = max(0.0, a.optDouble("start", 0.0))
        val end = min(if (a.has("end")) a.getDouble("end") else dur, dur)
        val cols = a.optInt("columns", 4).coerceIn(1, 6); val rows = a.optInt("rows", 3).coerceIn(1, 6)
        val ar = max(1, m.asset.displayWidth).toDouble() / max(1, m.asset.displayHeight)
        val tileW = 320; val tileH = (min(tileW * 1.8, tileW / ar)).roundToInt()
        val n = cols * rows; val span = max(0.1, end - start)
        val times = List(n) { start + span / n * (it + 0.5) }
        val sheet = Bitmap.createBitmap(tileW * cols, tileH * rows, Bitmap.Config.ARGB_8888)
        val cv = Canvas(sheet); cv.drawColor(Color.BLACK)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        val box = Paint().apply { color = Color.argb(160, 0, 0, 0) }
        val (fw, fh) = fitSize(m, tileW)
        retriever(m) { r ->
            times.forEachIndexed { i, t ->
                val x = (i % cols) * tileW; val y = (i / cols) * tileH
                grab(r, t, fw, fh, true)?.let { f ->
                    val s = min(tileW.toFloat() / f.width, tileH.toFloat() / f.height)
                    val dw = f.width * s; val dh = f.height * s
                    cv.drawBitmap(f, null, RectF(x + (tileW - dw) / 2, y + (tileH - dh) / 2, x + (tileW + dw) / 2, y + (tileH + dh) / 2), null)
                    f.recycle()
                }
                val txt = fmt(t)
                cv.drawRect(x + 2f, y + 2f, x + 14f + label.measureText(txt), y + 30f, box)
                cv.drawText(txt, x + 8f, y + 24f, label)
            }
        }
        return ok(text("${m.asset.displayName} (${r2(dur)} s) — images aux instants (s), de gauche à droite puis de haut en bas : ${times.joinToString(", ") { r2(it).toString() }}"), image(sheet, 70))
    }

    // ------------------------------------------------------------------ analyses (moteur CUTTINX AI LAB)
    private fun frames(m: SharedMedia, stepS: Double) =
        FrameSampler.sample(ctx, m.asset, 0, m.asset.durationUs, (max(stepS, m.asset.durationUs / 1e6 / 900) * 1e6).toLong(), width = 64)

    private fun detectScenes(a: JSONObject): JSONObject {
        val m = lib.mustGet(a.getString("media_id"))
        if (m.asset.type != MediaType.VIDEO) throw IllegalArgumentException("Uniquement pour les vidéos.")
        val cuts = SceneDetector.detect(frames(m, a.optDouble("interval", 0.25)))
        return json(JSONObject().put("media_id", m.asset.id).put("duration", r2(m.asset.durationUs / 1e6)).put("sceneChanges", JSONArray(cuts.map { r2(it / 1e6) })))
    }

    private fun findHighlights(a: JSONObject): JSONObject {
        val m = lib.mustGet(a.getString("media_id"))
        if (m.asset.type != MediaType.VIDEO) throw IllegalArgumentException("Uniquement pour les vidéos.")
        val stats = VideoAnalysis.stats(frames(m, 0.25))
        var audioDb = DoubleArray(0)
        if (m.asset.hasAudio) runCatching {
            val pcm = PcmDecoder.decode(ctx, Uri.parse(m.asset.uri), downmix = true, maxSeconds = 900.0)
            audioDb = AudioAnalysis.envelopeDb(pcm.samples, pcm.sampleRate, pcm.channels, windowMs = 50)
        }
        val hl = HighlightDetector.detect(stats, audioDb, 50_000, segmentUs = (a.optDouble("segment_seconds", 3.0) * 1e6).toLong(), count = a.optInt("count", 6).coerceIn(1, 20))
        return json(JSONObject().put("media_id", m.asset.id).put("highlights", JSONArray(hl.map {
            JSONObject().put("start", r2(it.startUs / 1e6)).put("end", r2(it.endUs / 1e6)).put("score", r2(it.score)).put("reasons", JSONArray(it.reasons))
        })).put("note", "Score basé sur le mouvement, la netteté, les couleurs et le son : vérifie toujours visuellement avec view_storyboard."))
    }

    private fun analyzeAudio(a: JSONObject): JSONObject {
        val m = lib.mustGet(a.getString("media_id"))
        val pcm = PcmDecoder.decode(ctx, Uri.parse(m.asset.uri), downmix = true, maxSeconds = 1800.0)
        val sil = SilenceDetector.detect(pcm.samples, pcm.sampleRate, pcm.channels,
            SilenceDetector.Params(thresholdDb = if (a.has("noise_db")) a.getDouble("noise_db") else null, minSilenceUs = (a.optDouble("min_silence", 0.45) * 1e6).toLong()))
        return json(JSONObject().put("media_id", m.asset.id)
            .put("loudnessLufs", r2(AudioAnalysis.integratedLoudness(pcm.samples, pcm.sampleRate, pcm.channels)))
            .put("silences", JSONArray(sil.map { JSONObject().put("start", r2(it.startUs / 1e6)).put("end", r2(it.endUs / 1e6)) })))
    }

    private fun analyzeMusic(a: JSONObject): JSONObject {
        val m = lib.mustGet(a.getString("media_id"))
        val start = a.optDouble("start", 0.0)
        val pcm = PcmDecoder.decode(ctx, Uri.parse(m.asset.uri), startUs = (start * 1e6).toLong(), downmix = true, maxSeconds = a.optDouble("max_seconds", 240.0))
        val b = BeatDetector.detect(pcm.samples, pcm.sampleRate, pcm.channels)
        fun list(l: List<Long>, cap: Int) = JSONArray(l.take(cap).map { r2(start + it / 1e6) })
        // Moments les plus forts de la musique (montées / drops) par blocs d'énergie de 0,5 s
        val env = AudioAnalysis.envelopeDb(pcm.samples, pcm.sampleRate, pcm.channels, windowMs = 500)
        val peaks = env.withIndex().sortedByDescending { it.value }.take(6).map { r2(start + it.index * 0.5) }.sorted()
        return json(JSONObject().put("media_id", m.asset.id).put("bpm", r2(b.bpm)).put("confidence", r2(b.confidence))
            .put("beats", list(b.beatsUs, 400)).put("downbeats", list(b.downbeatsUs, 120)).put("strongOnsets", list(b.onsetsUs, 200))
            .put("loudestMoments", JSONArray(peaks))
            .put("hint", "Pour un edit rythmé : fais tomber chaque coupe (fin de clip) sur un beat ou un downbeat. Les instants sont en secondes dans le fichier musical."))
    }

    private fun listEffects(): JSONObject {
        val fx = EffectRegistry.video().map { d ->
            JSONObject().put("id", d.id).put("name", d.name).put("category", d.category.label)
                .put("params", JSONArray(d.params.map { p -> JSONObject().put("key", p.key).put("min", p.min).put("max", p.max).put("default", p.default) }))
        }
        val tr = TransitionRegistry.all.map { d ->
            JSONObject().put("id", d.id).put("name", d.name).put("defaultDuration", r2(d.defaultDurationUs / 1e6))
                .put("params", JSONArray(d.params.map { p -> JSONObject().put("key", p.key).put("min", p.min).put("max", p.max).put("default", p.default) }))
        }
        val audioFx = EffectRegistry.all.filter { it.kind == EffectKind.AUDIO }.map { JSONObject().put("id", it.id).put("name", it.name) }
        return json(JSONObject().put("videoEffects", JSONArray(fx)).put("transitions", JSONArray(tr)).put("audioEffects", JSONArray(audioFx)))
    }

    // ------------------------------------------------------------------ projets
    private fun listProjects(): JSONObject = json(JSONArray(c.store.list().filter { !it.archived }.map {
        JSONObject().put("id", it.id).put("name", it.name).put("duration", r2(it.durationUs / 1e6)).put("clips", it.clipCount)
            .put("format", "${it.width}x${it.height}").put("modified", java.time.Instant.ofEpochMilli(it.modifiedAtMs).toString())
    }))

    private fun createProject(a: JSONObject): JSONObject {
        val (p, warnings) = ProjectSpecs.build(a, lib, existing = a.optString("project_id").ifEmpty { null }?.let { runCatching { c.store.load(it) }.getOrNull() })
        c.store.save(p)
        ClaudeLink.bump("a créé le projet « ${p.name} »")
        return json(JSONObject().put("project_id", p.id).put("name", p.name).put("duration", r2(p.durationUs / 1e6))
            .put("format", "${p.format.width}x${p.format.height} @${p.format.frameRate}fps")
            .put("tracks", JSONArray(p.tracks.map { "${it.kind.name.lowercase()} (${it.clips.size} éléments)" }))
            .put("warnings", JSONArray(warnings))
            .put("next", "Le projet est dans CUTTINX (écran d'accueil), modifiable à la main. Pour le vérifier : render_project avec quality draft puis view_storyboard du rendu."))
    }

    // ------------------------------------------------------------------ export
    private fun renderProject(a: JSONObject): JSONObject {
        val p = c.store.load(a.getString("project_id"))
        if (p.durationUs <= 0) throw IllegalArgumentException("Projet vide.")
        if (c.export.isRunning) throw IllegalStateException("Un export est déjà en cours sur le téléphone, réessaie dans un moment.")
        val draft = a.optString("quality", "final") == "draft"
        val s = p.export.copy(
            resolutionShortSide = if (draft) 480 else a.optInt("resolution", p.export.resolutionShortSide),
            quality = if (draft) ExportQuality.DRAFT else ExportQuality.HIGH,
            frameRate = p.format.frameRate,
        )
        val resolved = ExportPlanner.resolve(s, p.format, p.durationUs, CodecInfo.encoderCaps())
        val name = (if (draft) "${p.name} (brouillon)" else p.name)
        val job = RenderJob("rnd_" + System.currentTimeMillis().toString(36), p.id, name)
        jobs[job.id] = job
        val started = CountDownLatch(1)
        main.post {
            try { c.export.reset(); c.export.start(p, resolved, s.destinationFolder, name) } finally { started.countDown() }
        }
        started.await(10, TimeUnit.SECONDS)
        watch(job, p)
        ClaudeLink.bump("exporte « $name »")
        return json(JSONObject().put("job_id", job.id).put("status", "running")
            .put("output", "${resolved.width}x${resolved.height}").put("hint", "Suis l'export avec get_render."))
    }

    private fun watch(job: RenderJob, p: Project) = Thread {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 3 * 3600_000L) {
            when (val st = c.export.state.value) {
                is ExportState.Running -> job.progress = st.progress.coerceAtMost(99)
                is ExportState.Done -> {
                    try {
                        val asset = MediaProbe.probe(ctx, st.uri).copy(displayName = job.name)
                        val m = lib.add(SharedMedia(asset, notes = "Export du projet CUTTINX « ${p.name} »", kind = "render", projectId = p.id))
                        job.resultId = m.asset.id
                    } catch (e: Exception) { job.error = "Export réussi mais illisible : ${e.message}" }
                    job.progress = 100; job.status = if (job.resultId != null) "done" else "error"
                    ClaudeLink.bump("a terminé l'export « ${job.name} »")
                    return@Thread
                }
                is ExportState.Failed -> { job.status = "error"; job.error = st.message + " (" + st.technical.take(300) + ")"; return@Thread }
                is ExportState.Cancelled -> { job.status = "error"; job.error = "Export annulé sur le téléphone"; return@Thread }
                else -> {}
            }
            Thread.sleep(400)
        }
    }.start()

    private fun getRender(a: JSONObject): JSONObject {
        val job = jobs[a.getString("job_id")] ?: return err("Export introuvable (l'app a peut-être redémarré).")
        val until = System.currentTimeMillis() + (a.optDouble("wait_seconds", 40.0).coerceIn(0.0, 90.0) * 1000).toLong()
        while (job.status == "running" && System.currentTimeMillis() < until) Thread.sleep(500)
        val o = JSONObject().put("job_id", job.id).put("project_id", job.projectId).put("name", job.name).put("status", job.status).put("progress", job.progress)
        job.error?.let { o.put("error", it) }
        job.resultId?.let { id -> lib.get(id)?.let { o.put("result", mediaJson(it)).put("hint", "Vérifie le rendu avec view_storyboard sur result.id. Il est aussi dans la galerie (Movies/Cuttinx).") } }
        return json(o)
    }

    companion object {
        val jobs = ConcurrentHashMap<String, RenderJob>()
        fun r2(x: Double) = (x * 100).roundToInt() / 100.0
        fun fmt(t: Double): String { val m = (t / 60).toInt(); return "%02d:%04.1f".format(java.util.Locale.US, m, t - m * 60) }
    }
}
