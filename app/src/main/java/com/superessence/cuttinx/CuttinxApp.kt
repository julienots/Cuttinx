package com.superessence.cuttinx

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.ai.SpeechEngine
import com.superessence.cuttinx.core.project.CrashLog
import com.superessence.cuttinx.core.project.ProjectStore
import com.superessence.cuttinx.core.project.VersionStore
import kotlinx.coroutines.flow.MutableStateFlow
import com.superessence.cuttinx.data.SettingsStore
import com.superessence.cuttinx.data.UserPresets
import com.superessence.cuttinx.engine.CompositionFactory
import com.superessence.cuttinx.engine.ExportEngine
import com.superessence.cuttinx.engine.MediaResolver
import com.superessence.cuttinx.media.MediaImporter
import com.superessence.cuttinx.media.Thumbnails
import com.superessence.cuttinx.media.Waveforms
import com.superessence.cuttinx.resources.ResourceLibrary
import java.io.File

/** Application-wide services. */
@UnstableApi
class AppContainer(val app: Application) {
    val settings = SettingsStore(app)
    val store = ProjectStore(File(app.filesDir, "projects"))
    val importer = MediaImporter(app)
    val thumbnails = Thumbnails(app)
    val waveforms = Waveforms(app)
    val resources = ResourceLibrary(app)
    val presets = UserPresets(File(app.filesDir, "presets/user_presets.json"))
    val speech = SpeechEngine(app)
    val resolver = MediaResolver { a -> runCatching { Uri.parse(a.uri) }.getOrNull() }
    val factory = CompositionFactory(app, resolver) { id -> File(app.filesDir, "fonts/$id.ttf") }
    val export = ExportEngine(app, factory)
    val crashLog = CrashLog(File(app.filesDir, "crash"))
    val versions = VersionStore(File(app.filesDir, "versions"))
}

@UnstableApi
class CuttinxApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        com.superessence.cuttinx.engine.TextRenderer.assets = assets
        com.superessence.cuttinx.engine.TextRenderer.defaultFontFile = { id -> File(filesDir, "fonts/$id.ttf") }
        installCrashProtection()
        // Labo CUTTINX: unlock times survive restarts
        val labPrefs = getSharedPreferences("cuttinx_lab", MODE_PRIVATE)
        com.superessence.cuttinx.core.smart.TechVault.store = object : com.superessence.cuttinx.core.smart.TechVault.Store {
            override fun get(key: String) = labPrefs.getLong(key, 0L)
            override fun put(key: String, value: Long) { labPrefs.edit().putLong(key, value).apply() }
            override fun getText(key: String): String? = labPrefs.getString(key, null)
            override fun putText(key: String, value: String) { labPrefs.edit().putString(key, value).apply() }
        }
        // CUTTINX Plus: renew the licence before it ends, redraw when it changes
        BuildConfig.CUTTINX_SITE.takeIf { it.isNotBlank() }?.let { com.superessence.cuttinx.core.smart.Plus.siteUrl = it }
        com.superessence.cuttinx.core.smart.Plus.onChange = { com.superessence.cuttinx.lab.TechGate.refresh() }
        com.superessence.cuttinx.core.smart.Plus.renewInBackground()
    }

    /**
     * Crash protection. A failure inside a background task or a coroutine (export, analysis, import,
     * thumbnails…) only cancels that task: the work is saved, a message is shown and the app keeps
     * running. A failure of the interface thread itself cannot be survived: the project is saved
     * first, a report is written, and the next launch offers the recovery.
     */
    private fun installCrashProtection() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashSaver?.invoke() }
            runCatching { container.crashLog.write(t.name, e, "Android ${android.os.Build.VERSION.SDK_INT} · ${android.os.Build.MODEL}") }
            val onMain = t === android.os.Looper.getMainLooper().thread
            val reportedByCoroutine = Thread.currentThread().stackTrace.any { it.className.startsWith("kotlinx.coroutines") && it.methodName.contains("handleCoroutineException", true) }
            val survivable = reportedByCoroutine || (!onMain && e !is VirtualMachineError)
            if (survivable) {
                runCatching { container.thumbnails.trim(); }
                backgroundError.value = when (e) { is OutOfMemoryError -> "Mémoire insuffisante : l'opération a été arrêtée. Ton projet est sauvegardé."; else -> "Une opération a échoué (${e.javaClass.simpleName}). Ton projet est sauvegardé." }
            } else previous?.uncaughtException(t, e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // the system asks for memory back: drop the picture caches, they are rebuilt on demand
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) runCatching { container.thumbnails.trim() }
    }

    companion object {
        /** Set by the editor; invoked when the app crashes to persist unsaved work. */
        @Volatile var crashSaver: (() -> Unit)? = null

        /** Message about a task that failed and was contained (shown once by the main screen). */
        val backgroundError = MutableStateFlow<String?>(null)
    }
}
