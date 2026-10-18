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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    val config = MutableStateFlow(load("overlay") { json.decodeFromString(OverlayConfig.serializer(), it) } ?: OverlayConfig())
    val brandKit = MutableStateFlow(load("brand") { json.decodeFromString(BrandKit.serializer(), it) })
    val progress = MutableStateFlow<PackProgress?>(null)
    val message = MutableStateFlow<String?>(null)

    private fun <T> load(key: String, f: (String) -> T): T? = prefs.getString(key, null)?.let { runCatching { f(it) }.getOrNull() }

    val theme get() = OverlayThemes[config.value.themeId]

    fun update(f: (OverlayConfig) -> OverlayConfig) {
        config.value = f(config.value)
        prefs.edit().putString("overlay", json.encodeToString(OverlayConfig.serializer(), config.value)).apply()
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
                progress.value = PackProgress(0.96f, "Compression du pack…")
                getApplication<Application>().contentResolver.openOutputStream(target)!!.use { out ->
                    ZipOutputStream(out.buffered()).use { zip ->
                        dir.walkTopDown().filter { it.isFile }.forEach { f ->
                            zip.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path.replace('\\', '/')))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }.onSuccess { message.value = "Pack OBS enregistré ✅ Copie-le sur ton PC et ouvre LISEZ-MOI.txt" }
                .onFailure { message.value = "Export impossible : ${it.message}" }
            tmp.deleteRecursively()
            progress.value = null
        }
    }

    /** Saves one scene as a PNG. */
    fun exportScene(target: Uri, scene: SceneKind) = viewModelScope.launch(Dispatchers.Default) {
        runCatching {
            val cfg = config.value.copy(width = 1920, height = 1080)
            val png = ObsExporter(raster, renderer).renderPng(cfg) { g -> renderer.drawScene(g, theme, cfg, scene, 0.0) }
            getApplication<Application>().contentResolver.openOutputStream(target)!!.use { it.write(png) }
        }.onSuccess { message.value = "Image enregistrée ✅" }.onFailure { message.value = "Enregistrement impossible : ${it.message}" }
    }

    /** Saves the identity board (palette, logo, fonts, slogans) as a PNG. */
    fun exportBoard(target: Uri) = viewModelScope.launch(Dispatchers.Default) {
        val k = brandKit.value ?: return@launch
        runCatching {
            val fr = raster.frame(1600, 1000); board.draw(fr.gfx, k)
            getApplication<Application>().contentResolver.openOutputStream(target)!!.use { it.write(fr.png()) }
        }.onSuccess { message.value = "Planche d'identité enregistrée ✅" }.onFailure { message.value = "Enregistrement impossible : ${it.message}" }
    }
}
