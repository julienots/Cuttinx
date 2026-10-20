package com.superessence.cuttinx.ui.twitch

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superessence.cuttinx.core.brand.BrandBrief
import com.superessence.cuttinx.core.brand.BrandGenerator
import com.superessence.cuttinx.core.brand.BrandKit
import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.core.overlay.export.ObsExporter
import com.superessence.cuttinx.core.overlay.export.PackOptions
import com.superessence.cuttinx.core.overlay.render.BrandBoard
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import com.superessence.cuttinx.overlay.AndroidRaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Export progress of the OBS pack (null = idle). */
data class PackProgress(val fraction: Float, val label: String)

/**
 * State of the "Twitch Editor" and "ID Influenceur" spaces (shared: an identity can be applied to
 * the overlays). The overlay settings and the last identity are kept between launches.
 */
class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("cuttinx_studio", 0)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    val raster = AndroidRaster(app.assets)
    val renderer = OverlayRenderer()
    val board = BrandBoard()

    /** Overlay settings live in files (pictures included): many pictures never slow down or break saving. */
    private val store = OverlayStore(File(app.filesDir, "twitch"))
    private val history = UndoHistory<OverlayConfig>()
    val config = MutableStateFlow(loadConfig())
    val brandKit = MutableStateFlow(load("brand") { json.decodeFromString(BrandKit.serializer(), it) })
    val progress = MutableStateFlow<PackProgress?>(null)
    val message = MutableStateFlow<String?>(null)
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)
    /** "" = nothing to save, otherwise the save state shown in the header. */
    val saveStatus = MutableStateFlow(if (store.exists()) "Enregistré" else "")
    private var saveJob: Job? = null

    private fun <T> load(key: String, f: (String) -> T): T? = prefs.getString(key, null)?.let { runCatching { f(it) }.getOrNull() }

    /** Settings file, or the settings of the previous versions (kept in the preferences), moved to the file once. */
    private fun loadConfig(): OverlayConfig {
        runCatching { store.load() }.getOrNull()?.let { return it }
        val old = load("overlay") { json.decodeFromString(OverlayConfig.serializer(), it) } ?: return OverlayConfig()
        runCatching { store.save(old) }.onSuccess { prefs.edit().remove("overlay").apply() }
        return old
    }

    val theme get() = OverlayThemes[config.value.themeId]

    fun update(f: (OverlayConfig) -> OverlayConfig) = set(f(config.value), null)

    /** Changes the settings; [key] groups quick changes (a drag, a slider, typing) into one undo step. */
    fun set(c: OverlayConfig, key: String?) {
        if (c == config.value) return
        history.record(config.value, key)
        config.value = c
        changed()
    }

    /** End of a drag on the preview: the next change starts a new undo step. */
    fun endGesture() = history.cut()

    fun undo() { history.undo(config.value)?.let { config.value = it; changed() } }
    fun redo() { history.redo(config.value)?.let { config.value = it; changed() } }

    private fun changed() {
        canUndo.value = history.canUndo; canRedo.value = history.canRedo
        saveStatus.value = "Modifications en cours…"
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) { delay(500); write() }
    }

    /** Saves now ("Enregistrer" button, app going to the background). */
    fun saveNow() {
        saveJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { write() }
    }

    private fun write() = synchronized(store) {
        runCatching { store.save(config.value) }
            .onSuccess { saveStatus.value = "Enregistré ✓" }
            .onFailure { saveStatus.value = "Échec de l'enregistrement"; message.value = "Enregistrement impossible : ${it.message}" }
    }

    override fun onCleared() { synchronized(store) { runCatching { store.save(config.value) } }; creator.saveNow() }

    /**
     * Adds pictures to [scene] (several at once) or replaces the picture [replaceId]. They are
     * shrunk to 1600 px (photos stay JPEG, the rest PNG with transparency).
     */
    fun importImages(uris: List<Uri>, scene: SceneKind?, replaceId: String?, onAdded: (String) -> Unit) = viewModelScope.launch(Dispatchers.Default) {
        var c = config.value
        var last: String? = null
        var failed = 0
        for (u in uris) {
            val pic = runCatching { encodePicture(u) }.getOrNull()
            if (pic == null) { failed++; continue }
            val (b64, w, h, name) = pic
            if (replaceId != null) { c = c.updateItem(replaceId) { it.copy(data = b64, aspect = h.toFloat() / w, name = name) }; last = replaceId }
            else { val item = OverlayItems.image(c, scene, b64, w, h, name); c = OverlayItems.add(c, item); last = item.id }
        }
        withContext(Dispatchers.Main) {
            set(c, null)
            last?.let(onAdded)
            if (failed > 0) message.value = "$failed image(s) illisible(s)"
        }
    }

    private data class Pic(val b64: String, val w: Int, val h: Int, val name: String)

    /** Studio Créateur: thumbnails, covers, posts and banners (saved in their own folder). */
    val creator = com.superessence.cuttinx.creator.CreatorState(File(app.filesDir, "createur"))

    /** Decodes pictures for the Studio Créateur off the main thread, then hands them over. */
    fun pickPictures(uris: List<Uri>, onDone: (List<com.superessence.cuttinx.creator.PickedPicture>) -> Unit) = viewModelScope.launch(Dispatchers.Default) {
        val pics = uris.mapNotNull { u -> runCatching { encodePicture(u) }.getOrNull()?.let { com.superessence.cuttinx.creator.PickedPicture(it.b64, it.w, it.h, it.name) } }
        withContext(Dispatchers.Main) {
            if (pics.size < uris.size) message.value = "${uris.size - pics.size} image(s) illisible(s)"
            if (pics.isNotEmpty()) onDone(pics)
        }
    }

    /** Saves a Studio Créateur visual as a PNG at the platform's exact size. */
    fun exportDesign(target: Uri, d: com.superessence.cuttinx.core.creator.CreatorDesign) =
        saveImage(target, d.format.width, d.format.height) { g -> creatorRenderer.draw(g, d, d.format.width.toFloat(), d.format.height.toFloat(), 0.0) }

    val creatorRenderer = com.superessence.cuttinx.core.creator.CreatorRenderer(renderer)

    private fun encodePicture(uri: Uri, max: Int = 1600): Pic? {
        val cr = getApplication<Application>().contentResolver
        val type = cr.getType(uri) ?: ""
        // read the size first, then decode already reduced (big photos never run out of memory)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val src = cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val k = minOf(1f, max.toFloat() / maxOf(src.width, src.height))
        val bmp = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true) else src
        val jpeg = type.contains("jpeg") || type.contains("jpg")
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(if (jpeg) android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG, 90, out)
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.take(40) ?: "Image"
        return Pic(java.util.Base64.getEncoder().encodeToString(out.toByteArray()), bmp.width, bmp.height, name)
    }

    fun generate(brief: BrandBrief, seed: Int) {
        val k = BrandGenerator.generate(brief, seed)
        brandKit.value = k
        prefs.edit().putString("brand", json.encodeToString(BrandKit.serializer(), k)).apply()
    }

    /** Applies the identity to the overlays (palette, matching theme, name, slogan). */
    fun useBrand(k: BrandKit) = update { it.copy(themeId = k.overlayThemeId, palette = k.overlayPalette(), channel = k.brief.name.ifBlank { it.channel }, tagline = k.taglines.first()) }

    /** Renders the whole OBS pack and writes it as a .zip into [target] (chosen with the system picker). */
    fun exportPack(target: Uri, hd: Boolean, fps: Int, stingers: Set<StingerStyle>, minutes: Int) {
        if (progress.value != null) return
        progress.value = PackProgress(0f, "Préparation…")
        viewModelScope.launch(Dispatchers.Default) {
            val tmp = File(getApplication<Application>().cacheDir, "obs_pack").also { it.deleteRecursively(); it.mkdirs() }
            runCatching {
                val cfg = if (hd) config.value.copy(width = 1920, height = 1080) else config.value.copy(width = 1280, height = 720)
                val dir = ObsExporter(raster, renderer).exportPack(theme, cfg, tmp, PackOptions(stingers = stingers, fps = fps, countdownMinutes = minutes)) { p, l -> progress.value = PackProgress(p * 0.95f, l) }
                progress.value = PackProgress(0.96f, "Compression du pack (HTML, GIF et PNG animés)…")
                getApplication<Application>().contentResolver.openOutputStream(target)!!.use { out ->
                    ZipOutputStream(out.buffered()).use { zip ->
                        dir.walkTopDown().filter { it.isFile }.forEach { f ->
                            zip.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path.replace('\\', '/')))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }.onSuccess { message.value = "Pack OBS enregistré. Copie-le sur ton PC et ouvre LISEZ-MOI.txt" }
                .onFailure { message.value = "Export impossible : ${it.message}" }
            tmp.deleteRecursively()
            progress.value = null
        }
    }

    /**
     * Exports one element animated, as a .zip for OBS / Streamlabs: HTML (browser source), animated
     * GIF (Image source, Streamlabs alert box) and animated PNG. A still element gives its PNG.
     */
    fun exportElement(target: Uri, scene: SceneKind?, stinger: StingerStyle?, hd: Boolean, fps: Int, minutes: Int) {
        if (progress.value != null) return
        progress.value = PackProgress(0f, "Préparation…")
        viewModelScope.launch(Dispatchers.Default) {
            val tmp = File(getApplication<Application>().cacheDir, "element").also { it.deleteRecursively(); it.mkdirs() }
            runCatching {
                val cfg = if (hd) config.value.copy(width = 1920, height = 1080) else config.value.copy(width = 1280, height = 720)
                val dir = ObsExporter(raster, renderer).exportElement(theme, cfg, tmp, scene, stinger, PackOptions(fps = fps, countdownMinutes = minutes)) { p, l -> progress.value = PackProgress(p * 0.95f, l) }
                zip(dir, target)
            }.onSuccess { message.value = "Élément animé enregistré (.html, .gif, PNG animé). Ouvre le .gif pour voir l'animation." }
                .onFailure { message.value = "Export impossible : ${it.message}" }
            tmp.deleteRecursively()
            progress.value = null
        }
    }

    /** Saves the element as an animated GIF (plays in the gallery, OBS « Image », Streamlabs alert box). */
    fun exportGif(target: Uri, scene: SceneKind?, stinger: StingerStyle?) {
        if (progress.value != null) return
        progress.value = PackProgress(0.1f, "GIF animé…")
        viewModelScope.launch(Dispatchers.Default) {
            val tmp = File(getApplication<Application>().cacheDir, "anim.gif")
            runCatching {
                val cfg = config.value.copy(width = 1280, height = 720)
                val th = cfg.styled(theme)
                val ex = ObsExporter(raster, renderer)
                when {
                    stinger != null -> ex.renderGif(cfg, tmp, OverlayAnim.STINGER_SEC, 20) { g, t -> renderer.drawStinger(g, th, cfg, stinger, t) }
                    scene != null -> ex.renderGif(cfg, tmp, OverlayAnim.LOOP_SEC, 15) { g, t -> renderer.drawScene(g, th, cfg, scene, t) }
                    else -> error("choisis une scène, une alerte ou une transition")
                }
                getApplication<Application>().contentResolver.openOutputStream(target)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            }.onSuccess { message.value = "GIF animé enregistré" }.onFailure { message.value = "Export impossible : ${it.message}" }
            tmp.delete()
            progress.value = null
        }
    }

    private fun zip(dir: File, target: Uri) {
        getApplication<Application>().contentResolver.openOutputStream(target)!!.use { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path.replace('\\', '/')))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Photo as the background of one animated scene. */
    fun importSceneBackground(uri: Uri, scene: SceneKind) = pickPictures(listOf(uri)) { pics ->
        val p = pics.first()
        update { c -> c.copy(backgrounds = c.backgrounds + (scene to c.backgroundOf(scene).copy(kind = SceneBgKind.IMAGE, image = p.b64, imageAspect = p.height.toFloat() / p.width))) }
    }

    /** Saves one scene as a PNG. */
    fun exportScene(target: Uri, scene: SceneKind) = viewModelScope.launch(Dispatchers.Default) {
        runCatching {
            val cfg = config.value.copy(width = 1920, height = 1080)
            val png = ObsExporter(raster, renderer).renderPng(cfg) { g -> renderer.drawScene(g, theme, cfg, scene, 0.0) }
            getApplication<Application>().contentResolver.openOutputStream(target)!!.use { it.write(png) }
        }.onSuccess { message.value = "Image enregistrée" }.onFailure { message.value = "Enregistrement impossible : ${it.message}" }
    }

    /** Saves one Twitch profile panel (640×200, i.e. 320×100 for high-density screens). */
    fun exportPanel(target: Uri, label: String) = saveImage(target, 640, 200) { g -> g.scale(2f, 2f); renderer.drawPanel(g, theme, config.value, label) }

    /** Saves the Twitch profile banner (1200×480). */
    fun exportBanner(target: Uri) = saveImage(target, 1200, 480) { g -> renderer.drawBanner(g, theme, config.value) }

    private fun saveImage(target: Uri, w: Int, h: Int, draw: (com.superessence.cuttinx.core.gfx.Gfx) -> Unit) = viewModelScope.launch(Dispatchers.Default) {
        runCatching {
            val fr = raster.frame(w, h); fr.gfx.clear(0); draw(fr.gfx)
            getApplication<Application>().contentResolver.openOutputStream(target)!!.use { it.write(fr.png()) }
        }.onSuccess { message.value = "Image enregistrée" }.onFailure { message.value = "Enregistrement impossible : ${it.message}" }
    }

    /** Saves one profile / chat asset at the size Twitch asks for. */
    fun exportAsset(target: Uri, a: com.superessence.cuttinx.twitch.TwitchAsset) {
        val s = when (a) { com.superessence.cuttinx.twitch.TwitchAsset.AVATAR -> 800; com.superessence.cuttinx.twitch.TwitchAsset.BADGES -> 72; else -> 112 }
        saveImage(target, s, s) { g -> a.drawSingle(g, renderer, theme, config.value, s.toFloat()) }
    }

    /** Imports the streamer's logo (resized to 512 px max, PNG to keep the transparency). */
    fun importLogo(uri: Uri) = viewModelScope.launch(Dispatchers.Default) {
        runCatching {
            val src = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) } ?: error("image illisible")
            val k = minOf(1f, 512f / maxOf(src.width, src.height))
            val bmp = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true) else src
            val out = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            withContext(Dispatchers.Main) { update { it.copy(logo = com.superessence.cuttinx.twitch.encodeLogo(out.toByteArray())) } }
        }.onSuccess { message.value = "Logo ajouté : scènes, cadres, photo de profil, panneaux et bannière" }.onFailure { message.value = "Logo impossible à lire : ${it.message}" }
    }

    /** Saves one identity visual (profile picture, YouTube banner, thumbnail, story highlight). */
    fun exportBrandVisual(target: Uri, v: BrandVisual, index: Int) {
        val k = brandKit.value ?: return
        when (v) {
            BrandVisual.AVATAR -> saveImage(target, 800, 800) { board.drawAvatar(it, k, 800f) }
            BrandVisual.YOUTUBE -> saveImage(target, 2560, 1440) { board.drawYoutubeBanner(it, k) }
            BrandVisual.THUMBNAIL -> saveImage(target, 1280, 720) { board.drawThumbnail(it, k) }
            BrandVisual.HIGHLIGHT -> saveImage(target, 1080, 1080) { board.drawHighlight(it, k, index, 1080f) }
        }
    }

    /** Resets the style overrides (font, frame, motifs, badge, colours) to the theme's. */
    fun resetStyle() = update { it.copy(font = null, frame = null, motifs = null, badge = null, palette = null, textScale = 1f, cameraShape = CameraShape.THEME) }

    /** Saves the identity board (palette, logo, fonts, slogans) as a PNG. */
    fun exportBoard(target: Uri) = viewModelScope.launch(Dispatchers.Default) {
        val k = brandKit.value ?: return@launch
        runCatching {
            val fr = raster.frame(1600, 1000); board.draw(fr.gfx, k)
            getApplication<Application>().contentResolver.openOutputStream(target)!!.use { it.write(fr.png()) }
        }.onSuccess { message.value = "Planche d'identité enregistrée" }.onFailure { message.value = "Enregistrement impossible : ${it.message}" }
    }
}

/** Identity visuals that can be exported as images. */
enum class BrandVisual(val file: String) { AVATAR("photo-profil"), YOUTUBE("banniere-youtube"), THUMBNAIL("miniature"), HIGHLIGHT("story-une") }
