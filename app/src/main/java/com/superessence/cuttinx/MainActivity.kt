package com.superessence.cuttinx

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.model.ProjectFormat
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import com.superessence.cuttinx.ui.editor.EditorScreen
import com.superessence.cuttinx.ui.editor.EditorViewModel
import com.superessence.cuttinx.ui.help.HelpScreen
import com.superessence.cuttinx.ui.help.LicensesScreen
import com.superessence.cuttinx.ui.home.HomeScreen
import com.superessence.cuttinx.ui.settings.SettingsScreen
import com.superessence.cuttinx.ui.splash.SplashScreen
import com.superessence.cuttinx.ui.theme.CuttinxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Splash : Screen
    data object Home : Screen
    data class Editor(val id: String) : Screen
    data object Settings : Screen
    data object Help : Screen
    data object Licenses : Screen
}

@UnstableApi
class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val c = (application as CuttinxApp).container
        if (Build.VERSION.SDK_INT >= 33) runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
        setContent {
            val settings by c.settings.state.collectAsStateWithLifecycle()
            CuttinxTheme(settings) {
                var stack by remember { mutableStateOf(listOf<Screen>(Screen.Splash)) }
                fun push(s: Screen) { stack = stack + s }
                fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else finish() }
                fun replace(s: Screen) { stack = stack.dropLast(1) + s }
                val sharedUris by shared
                LaunchedEffect(sharedUris, stack.last()) {
                    if (sharedUris.isNotEmpty() && stack.last() == Screen.Home) {
                        val uris = sharedUris; shared.value = emptyList()
                        val p = withContext(Dispatchers.IO) {
                            val res = c.importer.import(uris)
                            var pr = Project(Ids.new("prj"), "Projet partagé", System.currentTimeMillis(), System.currentTimeMillis(), ProjectFormat.forAspect(settings.defaultAspect))
                            res.assets.forEach { pr = TimelineEditor.appendAsset(pr, it).first }
                            c.store.save(pr); pr
                        }
                        push(Screen.Editor(p.id))
                    }
                }
                when (val s = stack.last()) {
                    Screen.Splash -> SplashScreen { replace(Screen.Home) }
                    Screen.Home -> HomeScreen(openProject = { push(Screen.Editor(it)) }, openSettings = { push(Screen.Settings) }, openHelp = { push(Screen.Help) }, openResources = { push(Screen.Licenses) })
                    is Screen.Editor -> key(s.id) {
                        val vm: EditorViewModel = viewModel(key = s.id, factory = EditorViewModel.Factory(application, s.id))
                        EditorScreen(vm, onBack = { pop() }, openProject = { id -> replace(Screen.Editor(id)) })
                    }
                    Screen.Settings -> { BackHandler { pop() }; SettingsScreen(c.settings, { pop() }, { push(Screen.Licenses) }) }
                    Screen.Help -> { BackHandler { pop() }; HelpScreen({ pop() }) {
                        val t = com.superessence.cuttinx.templates.Templates(c.resources)
                        val p = t.build(t.list.last()); c.store.save(p); replace(Screen.Editor(p.id))
                    } }
                    Screen.Licenses -> { BackHandler { pop() }; LicensesScreen { pop() } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        i ?: return
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) shared.value = uris
    }
}
