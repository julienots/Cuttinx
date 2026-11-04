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
    data object Claude : Screen
}

@UnstableApi
class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val c = (application as CuttinxApp).container
        // pubs du Labo CUTTINX : consentement (RGPD) puis démarrage du SDK, sans bloquer l'appli
        window.decorView.postDelayed({ runCatching { com.superessence.cuttinx.ads.Ads.init(this) } }, 2500)
        if (Build.VERSION.SDK_INT >= 33) runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
        // Reconnexion automatique à Claude si l'utilisateur l'avait activée
        if (com.superessence.cuttinx.claude.ClaudeLink.isEnabled(this)) window.decorView.postDelayed({ runCatching { com.superessence.cuttinx.claude.ClaudeService.start(this) } }, 1500)
        setContent {
            val settings by c.settings.state.collectAsStateWithLifecycle()
            CuttinxTheme(settings) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // Labo CUTTINX: a locked technology was asked for -> full-screen advert, then unlock for 24 h
                com.superessence.cuttinx.lab.TechGate.pending?.let { req ->
                    var busy by remember(req) { mutableStateOf(false) }
                    var err by remember(req) { mutableStateOf<String?>(null) }
                    com.superessence.cuttinx.lab.TechUnlockDialog(req, com.superessence.cuttinx.ui.theme.LocalCx.current.accent, "une courte pub", busy, err,
                        onWatch = {
                            busy = true; err = null
                            com.superessence.cuttinx.ads.Ads.showRewarded(this@MainActivity,
                                onReward = { busy = false; com.superessence.cuttinx.lab.TechGate.grant(req.tech); android.widget.Toast.makeText(ctx, "${req.tech.label} débloqué pour 24 h", android.widget.Toast.LENGTH_SHORT).show() },
                                onError = { busy = false; err = it })
                        },
                        onDismiss = { com.superessence.cuttinx.lab.TechGate.cancel() })
                }
                // a task that failed was contained: tell the user, nothing else happens
                LaunchedEffect(Unit) { CuttinxApp.backgroundError.collect { m -> if (m != null) { android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_LONG).show(); CuttinxApp.backgroundError.value = null } } }
                var crashReport by remember { mutableStateOf(c.crashLog.unseen()) }
                val looping = remember { c.crashLog.looping() }
                LaunchedEffect(looping) {
                    // several closures in a row: lighten the preview so the next session is stable
                    if (looping && settings.previewQuality != com.superessence.cuttinx.engine.PreviewQuality.LOW) c.settings.update { it.copy(previewQuality = com.superessence.cuttinx.engine.PreviewQuality.LOW) }
                }
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
                crashReport?.let { f ->
                    if (stack.last() == Screen.Home) CrashNotice(looping, onCopy = {
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("CUTTINX", runCatching { f.readText() }.getOrDefault("")))
                        android.widget.Toast.makeText(ctx, "Rapport copié", android.widget.Toast.LENGTH_SHORT).show()
                    }, onClose = { c.crashLog.markSeen(f); crashReport = null })
                }
                when (val s = stack.last()) {
                    Screen.Splash -> com.superessence.cuttinx.motion.LaunchIntro(onDone = { if (stack.lastOrNull() == Screen.Splash) replace(Screen.Home) })
                    Screen.Home -> HomeScreen(openProject = { push(Screen.Editor(it)) }, openSettings = { push(Screen.Settings) }, openHelp = { push(Screen.Help) }, openResources = { push(Screen.Licenses) }, openClaude = { push(Screen.Claude) })
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
                    Screen.Claude -> { BackHandler { pop() }; com.superessence.cuttinx.claude.ClaudeScreen { pop() } }
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

/** Shown on the home screen after the app closed unexpectedly. */
@androidx.compose.runtime.Composable
private fun CrashNotice(looping: Boolean, onCopy: () -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(onDismissRequest = onClose,
        title = { androidx.compose.material3.Text("Fermeture inattendue") },
        text = {
            androidx.compose.material3.Text(
                "CUTTINX s'est fermé lors de la dernière utilisation. Ton travail est sauvegardé automatiquement : s'il y avait des modifications non enregistrées, une carte « Récupération » apparaît en haut de l'écran d'accueil." +
                    if (looping) "\n\nPlusieurs fermetures se sont produites de suite : la qualité de l'aperçu a été réduite pour plus de stabilité. Tu peux la remettre dans Paramètres. Ferme aussi les autres applications avant de monter." else "")
        },
        confirmButton = { androidx.compose.material3.TextButton(onClose) { androidx.compose.material3.Text("OK") } },
        dismissButton = { androidx.compose.material3.TextButton(onCopy) { androidx.compose.material3.Text("Copier le rapport") } })
}
