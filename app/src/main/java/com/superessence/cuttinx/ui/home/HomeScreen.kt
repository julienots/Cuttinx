package com.superessence.cuttinx.ui.home

import com.superessence.cuttinx.icons.CxIcons
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.model.AspectPreset
import com.superessence.cuttinx.core.model.ProjectFormat
import com.superessence.cuttinx.core.project.ProjectSummary
import com.superessence.cuttinx.core.util.Time
import com.superessence.cuttinx.ui.components.*
import com.superessence.cuttinx.ui.theme.Cx
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(openProject: (String) -> Unit, openSettings: () -> Unit, openHelp: () -> Unit, openResources: () -> Unit) {
    val vm: HomeViewModel = viewModel()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val recoveries by vm.recoveries.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val settings by vm.c.settings.state.collectAsStateWithLifecycle()
    val cx = LocalCx.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var showNew by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<ProjectSummary?>(null) }
    var renameFor by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteFor by remember { mutableStateOf<ProjectSummary?>(null) }
    var exportFor by remember { mutableStateOf<ProjectSummary?>(null) }
    var exportWithMedia by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(StudioTab.EDITOR) }
    val studio: com.superessence.cuttinx.ui.twitch.StudioViewModel = viewModel()
    var quickFormat by remember { mutableStateOf(ProjectFormat.forAspect(settings.defaultAspect, fps = settings.defaultFps)) }

    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(Unit) { vm.refresh() }

    val quickPick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            vm.busy.value = "Import des médias…"
            val (p, errs) = vm.createFromMedia(uris, quickFormat)
            vm.busy.value = null
            if (errs.isNotEmpty()) vm.message.value = errs.joinToString("\n")
            p?.let { openProject(it.id) }
        }
    }
    val exportDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val p = exportFor; if (uri != null && p != null) vm.exportArchive(p.id, uri, exportWithMedia); exportFor = null
    }
    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importArchive(uri) }

    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { CuttinxMark(30.dp); Spacer(Modifier.width(8.dp)); CuttinxWordmark(24.sp); Spacer(Modifier.width(6.dp)); ProBadge() } },
                actions = {
                    // one menu instead of a row of icons
                    Box {
                        IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem({ Text("Aide & tutos vidéo") }, { menuOpen = false; openHelp() }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null) })
                            DropdownMenuItem({ Text("Paramètres") }, { menuOpen = false; openSettings() }, leadingIcon = { Icon(Icons.Outlined.Settings, null) })
                            DropdownMenuItem({ Text("Importer un projet") }, { menuOpen = false; importDoc.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) })
                            DropdownMenuItem({ Text("Ressources & licences") }, { menuOpen = false; openResources() }, leadingIcon = { Icon(Icons.Outlined.LibraryMusic, null) })
                        }
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
        StudioTabs(tab, { tab = it })
        // the chosen space slides in from the side of its tab
        androidx.compose.animation.AnimatedContent(tab, Modifier.weight(1f), transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (androidx.compose.animation.slideInHorizontally { it * dir / 3 } + androidx.compose.animation.fadeIn()) togetherWith
                (androidx.compose.animation.slideOutHorizontally { -it * dir / 3 } + androidx.compose.animation.fadeOut())
        }, label = "space") { shown ->
        when (shown) {
        StudioTab.TWITCH -> com.superessence.cuttinx.ui.twitch.TwitchScreen(studio)
        StudioTab.ID -> com.superessence.cuttinx.ui.brand.BrandScreen(studio) { tab = StudioTab.TWITCH }
        StudioTab.EDITOR -> {
        val visible = projects.filter { it.archived == showArchived && (query.isBlank() || it.name.contains(query, true)) }
        LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    // hero
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF1B1B1F), Color(0xFF3A2E00)))).clickable { showNew = true }.padding(20.dp)) {
                        Column {
                            Text("Nouveau montage", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Animations, effets, transitions, sons, IA — export 4K sans filigrane.", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AccentButton("Créer", { showNew = true }, icon = Icons.Default.Add)
                                OutlinedButton({ quickPick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, shape = RoundedCornerShape(14.dp)) {
                                    Icon(Icons.Default.VideoLibrary, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Depuis la galerie", color = Color.White)
                                }
                            }
                        }
                    }
                    busy?.let { Spacer(Modifier.height(10.dp)); ProgressCard(it, null) }
                    if (recoveries.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        recoveries.forEach { r ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Cx.Danger.copy(alpha = 0.15f)).padding(12.dp)) {
                                Text("Récupération après fermeture inattendue", fontWeight = FontWeight.Bold)
                                Text("« ${r.autosave.name} » contient des modifications non enregistrées (${DateFormat.getDateTimeInstance().format(Date(r.savedAtMs))}).", style = MaterialTheme.typography.bodySmall)
                                Row { TextButton({ vm.acceptRecovery(r) }) { Text("Récupérer") }; TextButton({ vm.discardRecovery(r) }) { Text("Ignorer") } }
                            }
                        }
                    }
                    SectionTitle("Modèles gratuits")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        vm.templates.list.forEach { t ->
                            Column(Modifier.width(150.dp).clip(RoundedCornerShape(14.dp)).background(cx.panel2).clickable { vm.fromTemplate(t) { openProject(it.id) } }.padding(12.dp)) {
                                Icon(Icons.Default.AutoAwesome, null, tint = cx.accent)
                                Text(t.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.description, style = MaterialTheme.typography.bodySmall, color = cx.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(t.aspect.label, style = MaterialTheme.typography.labelSmall, color = cx.accent)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(if (showArchived) "Projets archivés" else "Mes projets (${projects.count { !it.archived }})", Modifier.weight(1f))
                        TextButton({ showArchived = !showArchived }) { Text(if (showArchived) "Actifs" else "Archives") }
                    }
                    if (projects.size > 4) OutlinedTextField(query, { query = it }, placeholder = { Text("Rechercher un projet") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                    if (visible.isEmpty()) Text(if (showArchived) "Aucun projet archivé." else "Aucun projet pour l'instant. Créez-en un ou essayez le « Projet d'exemple ».", color = cx.dim, modifier = Modifier.padding(vertical = 20.dp))
                }
            }
            items(visible, key = { it.id }) { p ->
                ProjectCard(p, vm.thumbnailFile(p.id), onClick = { openProject(p.id) }, onMenu = { menuFor = p })
            }
        }
        }
        }
        }
        }
    }

    if (showNew) NewProjectDialog(settings.defaultAspect, settings.defaultFps, onDismiss = { showNew = false }) { name, fmt ->
        showNew = false; val p = vm.create(name, fmt); openProject(p.id)
    }
    menuFor?.let { p ->
        ModalBottomSheet({ menuFor = null }) {
            Column(Modifier.padding(16.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleLarge)
                Text("${p.width}×${p.height} • ${Time.format(p.durationUs, false)} • ${p.clipCount} éléments", color = cx.dim)
                Spacer(Modifier.height(8.dp))
                listOf(
                    Triple(Icons.Default.Edit, "Ouvrir") { openProject(p.id) },
                    Triple(Icons.Default.DriveFileRenameOutline, "Renommer") { renameFor = p },
                    Triple(Icons.Default.ContentCopy, "Dupliquer") { vm.duplicate(p.id) },
                    Triple(Icons.Default.Archive, if (p.archived) "Désarchiver" else "Archiver") { vm.archive(p.id, !p.archived) },
                    Triple(Icons.Default.Upload, "Exporter le projet (.cuttinx)") { exportFor = p },
                    Triple(Icons.Default.Delete, "Supprimer") { deleteFor = p },
                ).forEach { (ic, label, action) ->
                    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(ic, null) }, modifier = Modifier.clickable { menuFor = null; action() })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    renameFor?.let { p -> TextInputDialog("Renommer", p.name, { renameFor = null }) { vm.rename(p.id, it); renameFor = null } }
    deleteFor?.let { p -> ConfirmDialog("Supprimer « ${p.name} » ?", "Le projet sera supprimé. Vos fichiers vidéo, photos et musiques d'origine ne sont jamais effacés.", "Supprimer", { deleteFor = null }, { vm.delete(p.id); deleteFor = null }, danger = true) }
    exportFor?.let { p ->
        AlertDialog({ exportFor = null }, title = { Text("Exporter le projet") }, text = {
            Column { Text("Crée une archive .cuttinx que vous pourrez réimporter sur un autre appareil."); LabeledSwitch("Inclure les médias", exportWithMedia, { exportWithMedia = it }, description = "Archive plus lourde mais autonome") }
        }, confirmButton = { TextButton({ exportDoc.launch(p.name + ".cuttinx") }) { Text("Choisir l'emplacement") } }, dismissButton = { TextButton({ exportFor = null }) { Text("Annuler") } })
    }
    // step-by-step tutorial on first launch (can be replayed from the guide)
    if (!settings.tutorialDone) com.superessence.cuttinx.ui.help.TutorialOverlay { vm.c.settings.update { it.copy(tutorialDone = true) } }
}

@Composable
private fun ProjectCard(p: ProjectSummary, thumb: java.io.File, onClick: () -> Unit, onMenu: () -> Unit) {
    val cx = LocalCx.current
    val bmp = remember(p.modifiedAtMs) { if (thumb.exists()) runCatching { BitmapFactory.decodeFile(thumb.absolutePath)?.asImageBitmap() }.getOrNull() else null }
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(cx.panel).combinedClickableCompat(onClick, onMenu)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (p.width >= p.height) 16f / 10f else 4f / 5f).background(cx.panel2), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Default.Movie, null, tint = cx.dim, modifier = Modifier.size(40.dp))
            Text(Time.format(p.durationUs, false), color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(p.modifiedAtMs)) + " • ${p.width}×${p.height}", style = MaterialTheme.typography.bodySmall, color = cx.dim, maxLines = 1)
            }
            IconButton(onMenu) { Icon(Icons.Default.MoreVert, "Options") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLong: () -> Unit) = this.combinedClickable(onClick = onClick, onLongClick = onLong)

@Composable
fun NewProjectDialog(defaultAspect: AspectPreset, defaultFps: Int, onDismiss: () -> Unit, onCreate: (String, ProjectFormat) -> Unit) {
    var name by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(defaultAspect) }
    var res by remember { mutableIntStateOf(1080) }
    var fps by remember { mutableIntStateOf(defaultFps) }
    var cw by remember { mutableIntStateOf(1080) }
    var ch by remember { mutableIntStateOf(1350) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nouveau projet") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SectionTitle("Format")
            ChoiceChips(AspectPreset.entries.toList(), aspect, { it.label }, { aspect = it })
            if (aspect == AspectPreset.CUSTOM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Largeur", cw, { cw = it.coerceIn(16, 4096) }, Modifier.weight(1f)); NumberField("Hauteur", ch, { ch = it.coerceIn(16, 4096) }, Modifier.weight(1f))
            } else {
                SectionTitle("Résolution de travail")
                ChoiceChips(listOf(720, 1080, 1440, 2160), res, { "${it}p" }, { res = it })
            }
            SectionTitle("Images par seconde")
            ChoiceChips(listOf(24, 25, 30, 50, 60), fps, { "$it" }, { fps = it })
            val f = ProjectFormat.forAspect(aspect, res, fps, cw, ch)
            Text("${f.width} × ${f.height} px", color = LocalCx.current.dim, modifier = Modifier.padding(top = 8.dp))
        }
    }, confirmButton = { TextButton({ onCreate(name, ProjectFormat.forAspect(aspect, res, fps, cw, ch)) }) { Text("Créer") } }, dismissButton = { TextButton(onDismiss) { Text("Annuler") } })
}

/** "PRO" edition badge shown next to the wordmark. */
@Composable
private fun ProBadge() {
    Text("PRO", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Cx.Yellow, Color(0xFFFF8A00)))).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** The three spaces of CUTTINX PRO, chosen at the top of the home screen. */
enum class StudioTab(val label: String, val short: String, val icon: () -> androidx.compose.ui.graphics.vector.ImageVector, val color: Color) {
    EDITOR("Éditeur", "Éditeur", { CxIcons.Editor }, Cx.Yellow),
    TWITCH("Twitch Editor", "Twitch", { CxIcons.Twitch }, Color(0xFF3D8BFF)),
    ID("ID Influenceur", "ID Influ", { CxIcons.Identity }, Color(0xFFFF4FA3)),
}

/** Slanted card echoing the big « / » separators. */
private val SlantShape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    val d = size.height * 0.2f
    moveTo(d, 0f); lineTo(size.width, 0f); lineTo(size.width - d, size.height); lineTo(0f, size.height); close()
}

/**
 * The three spaces, big, separated by large « / ». Tapping one makes it grow (spring), its emoji
 * bounces and the two others shrink and fade.
 */
@Composable
private fun StudioTabs(selected: StudioTab, onSelect: (StudioTab) -> Unit) {
    val cx = LocalCx.current
    Row(Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StudioTab.entries.forEachIndexed { i, t ->
            if (i > 0) Text("/", Modifier.padding(horizontal = 1.dp), fontSize = 58.sp, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color.White.copy(alpha = 0.3f))
            val sel = t == selected
            val weight by androidx.compose.animation.core.animateFloatAsState(if (sel) 1.9f else 1f, androidx.compose.animation.core.spring(0.6f, 240f), label = "w")
            val scale by androidx.compose.animation.core.animateFloatAsState(if (sel) 1.12f else 0.9f, androidx.compose.animation.core.spring(0.42f, 320f), label = "s")
            val alpha by androidx.compose.animation.core.animateFloatAsState(if (sel) 1f else 0.6f, label = "a")
            val bounce = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(sel) { if (sel) { bounce.snapTo(-18f); bounce.animateTo(0f, androidx.compose.animation.core.spring(0.25f, 220f)) } }
            val bg = if (sel) Brush.linearGradient(listOf(t.color, t.color.copy(alpha = 0.6f))) else androidx.compose.ui.graphics.SolidColor(cx.panel2)
            Box(Modifier.weight(weight).fillMaxHeight().clip(SlantShape).background(bg).clickable { onSelect(t) }, contentAlignment = Alignment.Center) {
                Column(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(t.icon(), null, Modifier.size(30.dp).graphicsLayer { rotationZ = bounce.value; translationY = bounce.value * 0.6f },
                        tint = if (sel) (if (t == StudioTab.EDITOR) Color.Black else Color.White) else MaterialTheme.colorScheme.onSurface)
                    Text(if (sel) t.label else t.short, fontWeight = FontWeight.Black, fontSize = if (sel) 15.sp else 12.sp, maxLines = 1,
                        color = if (sel) (if (t == StudioTab.EDITOR) Color.Black else Color.White) else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
