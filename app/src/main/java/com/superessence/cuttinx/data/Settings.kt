package com.superessence.cuttinx.data

import android.content.Context
import com.superessence.cuttinx.core.model.AspectPreset
import com.superessence.cuttinx.engine.PreviewQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { DARK("Sombre"), AMOLED("Noir profond"), LIGHT("Clair"), SYSTEM("Système") }
enum class AccentColor(val label: String, val argb: Long) { YELLOW("Jaune Essence", 0xFFFFCC1F), CYAN("Cyan", 0xFF29D3FF), MAGENTA("Magenta", 0xFFFF3D8B), LIME("Citron vert", 0xFF9CFF3D), ORANGE("Orange", 0xFFFF8A1F) }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: AccentColor = AccentColor.YELLOW,
    val previewQuality: PreviewQuality = PreviewQuality.HIGH,
    val autosaveSeconds: Int = 10,
    val beginnerMode: Boolean = true,
    val magneticMainTrack: Boolean = true,
    val snapping: Boolean = true,
    val haptics: Boolean = true,
    val showTips: Boolean = true,
    val defaultImageDurationMs: Int = 3000,
    val defaultTransitionMs: Int = 500,
    val defaultAspect: AspectPreset = AspectPreset.PORTRAIT_9_16,
    val defaultFps: Int = 30,
    val copyImportedMedia: Boolean = false,
    val tutorialDone: Boolean = false,
    val largeControls: Boolean = false,
)

/** Persistent user preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cuttinx_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value get() = _state.value

    private fun load() = AppSettings(
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "DARK")!!) }.getOrDefault(ThemeMode.DARK),
        accent = runCatching { AccentColor.valueOf(prefs.getString("accent", "YELLOW")!!) }.getOrDefault(AccentColor.YELLOW),
        previewQuality = runCatching { PreviewQuality.valueOf(prefs.getString("preview", "HIGH")!!) }.getOrDefault(PreviewQuality.HIGH),
        autosaveSeconds = prefs.getInt("autosave", 10),
        beginnerMode = prefs.getBoolean("beginner", true),
        magneticMainTrack = prefs.getBoolean("magnetic", true),
        snapping = prefs.getBoolean("snap", true),
        haptics = prefs.getBoolean("haptics", true),
        showTips = prefs.getBoolean("tips", true),
        defaultImageDurationMs = prefs.getInt("imgdur", 3000),
        defaultTransitionMs = prefs.getInt("trdur", 500),
        defaultAspect = runCatching { AspectPreset.valueOf(prefs.getString("aspect", "PORTRAIT_9_16")!!) }.getOrDefault(AspectPreset.PORTRAIT_9_16),
        defaultFps = prefs.getInt("fps", 30),
        copyImportedMedia = prefs.getBoolean("copymedia", false),
        tutorialDone = prefs.getBoolean("tutorial", false),
        largeControls = prefs.getBoolean("large", false),
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_state.value)
        prefs.edit()
            .putString("theme", s.theme.name).putString("accent", s.accent.name).putString("preview", s.previewQuality.name)
            .putInt("autosave", s.autosaveSeconds).putBoolean("beginner", s.beginnerMode).putBoolean("magnetic", s.magneticMainTrack)
            .putBoolean("snap", s.snapping).putBoolean("haptics", s.haptics).putBoolean("tips", s.showTips)
            .putInt("imgdur", s.defaultImageDurationMs).putInt("trdur", s.defaultTransitionMs).putString("aspect", s.defaultAspect.name)
            .putInt("fps", s.defaultFps).putBoolean("copymedia", s.copyImportedMedia).putBoolean("tutorial", s.tutorialDone).putBoolean("large", s.largeControls)
            .apply()
        _state.value = s
    }
}
