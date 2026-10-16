package com.superessence.cuttinx

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.ai.SpeechEngine
import com.superessence.cuttinx.core.project.ProjectStore
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
        // Last-chance autosave: the editor registers the current project here.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashSaver?.invoke() }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        /** Set by the editor; invoked when the app crashes to persist unsaved work. */
        @Volatile var crashSaver: (() -> Unit)? = null
    }
}
