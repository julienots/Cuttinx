package com.superessence.cuttinx.twitch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.gfx.Gfx
import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import com.superessence.cuttinx.icons.CxIcons

/** Accent of the Twitch space (Android and PC). */
val TwitchAccent = Color(0xFF3D8BFF)

/** Extra Twitch assets (profile / chat), drawn square. */
enum class TwitchAsset(val label: String, val file: String) {
    AVATAR("Photo de profil", "avatar"),
    BADGES("Badges d'abonné", "badges-abonnes"),
    EMOTES("Emotes", "emotes"),
    POINTS("Points de chaîne", "points-de-chaine"),
    ;

    /** Draws the asset sheet (several badges / emotes side by side) on a [w]×[h] canvas. */
    fun draw(c: Gfx, r: OverlayRenderer, theme: OverlayTheme, cfg: OverlayConfig, w: Float, h: Float) {
        when (this) {
            AVATAR, POINTS -> {
                val s = minOf(w, h) * 0.8f
                c.save(); c.translate((w - s) / 2, (h - s) / 2)
                if (this == AVATAR) r.drawAvatar(c, theme, cfg, s) else r.drawPoints(c, theme, cfg, s)
                c.restore()
            }
            BADGES, EMOTES -> {
                val n = if (this == BADGES) OverlayConfig.SUB_TIERS.size else cfg.emotes.size.coerceAtLeast(1)
                val cols = minOf(n, 3); val rows = (n + cols - 1) / cols
                val s = minOf(w / cols, h / rows) * 0.82f
                for (i in 0 until n) {
                    val cx = w / 2 + (i % cols - (cols - 1) / 2f) * s * 1.18f
                    val cy = h / 2 + (i / cols - (rows - 1) / 2f) * s * 1.12f
                    c.save(); c.translate(cx - s / 2, cy - s / 2)
                    if (this == BADGES) r.drawSubBadge(c, theme, cfg, OverlayConfig.SUB_TIERS[i], s)
                    else r.drawEmote(c, theme, cfg, cfg.emotes[i], s, i)
                    c.restore()
                }
            }
        }
    }

    /** One exported image of the asset (the first badge / emote for the sheets). */
    fun drawSingle(c: Gfx, r: OverlayRenderer, theme: OverlayTheme, cfg: OverlayConfig, s: Float) = when (this) {
        AVATAR -> r.drawAvatar(c, theme, cfg, s)
        POINTS -> r.drawPoints(c, theme, cfg, s)
        BADGES -> r.drawSubBadge(c, theme, cfg, 1, s)
        EMOTES -> r.drawEmote(c, theme, cfg, cfg.emotes.firstOrNull() ?: "GG", s, 0)
    }
}

@Composable
fun TwitchChips(options: List<String>, selected: Int?, onClick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Text(o, Modifier.clip(RoundedCornerShape(50)).background(if (sel) TwitchAccent else Color.White.copy(alpha = 0.08f)).clickable { onClick(i) }.padding(horizontal = 12.dp, vertical = 7.dp),
                color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, Modifier.padding(top = 14.dp, bottom = 6.dp), color = TwitchAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)

// ---------------------------------------------------------------------- mini AI

private val examples = listOf("horreur rouge", "cosy anime pastel", "minecraft", "esport bleu néon", "mignon rose", "noël neige", "rétro arcade", "chill lofi", "football", "japon sakura")

/**
 * Keyword assistant: the streamer types what they like ("valorant violet", "cosy café") and gets
 * three complete overlay setups (theme, colours, texts, panels, emotes) to apply in one click.
 */
@Composable
fun AiPanel(cfg: OverlayConfig, onApply: (OverlayConfig) -> Unit, preview: @Composable (OverlayConfig, Modifier) -> Unit) {
    var prompt by remember { mutableStateOf("") }
    var seed by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<AiProposal>>(emptyList()) }
    fun run() { if (prompt.isNotBlank()) results = OverlayAi.propose(prompt, cfg, 3, seed) }
    Text("Décris ton live en quelques mots : jeu, ambiance, couleurs. L'assistant crée tout seul les scènes, cadres, alertes, panneaux, emotes et badges assortis.",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("Mots-clés") }, placeholder = { Text("ex. horreur rouge, fortnite, cosy pastel…") }, singleLine = true)
    Spacer(Modifier.height(6.dp))
    TwitchChips(examples, null) { i -> prompt = examples[i]; seed = 0; run() }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ seed = 0; run() }, Modifier.weight(1f), enabled = prompt.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = TwitchAccent, contentColor = Color.White)) {
            Icon(CxIcons.Idea, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Générer", fontWeight = FontWeight.Bold)
        }
        if (results.isNotEmpty()) OutlinedButton({ seed++; run() }) { Icon(CxIcons.Replay, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Autres idées") }
    }
    results.forEach { r ->
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)).padding(10.dp)) {
            preview(r.config, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.height(6.dp))
            Text(r.themeName, fontWeight = FontWeight.Bold)
            r.reasons.forEach { Text("• $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) }
            Text("Accroche : « ${r.config.tagline} » · Emotes : ${r.config.emotes.joinToString(" ")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Button({ onApply(r.config.copy(width = cfg.width, height = cfg.height)) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = TwitchAccent, contentColor = Color.White)) { Text("Utiliser ce style partout") }
        }
    }
}

// ---------------------------------------------------------------------- positions

/** Config the layout of [scene] is computed on (the vertical live is shown as a centred 9:16 frame) + its x offset. */
fun twitchLayoutOf(c: OverlayConfig, scene: SceneKind): Pair<OverlayConfig, Float> =
    if (scene == SceneKind.VERTICAL && c.width > c.height) { val pw = (c.height * 9 / 16).coerceAtLeast(2); c.copy(width = pw) to (c.width - pw) / 2f } else c to 0f

/** Drag to move the zones directly on the preview (tap = select the element under the finger / mouse). */
fun Modifier.zoneDrag(cfg: () -> OverlayConfig, scene: SceneKind?, selected: (ZoneKind?) -> Unit, onChange: (OverlayConfig) -> Unit): Modifier =
    if (scene == null || !scene.movable()) this else this
        .pointerInput(scene) {
            detectTapGestures { o ->
                val c = cfg(); val (lc, ox) = twitchLayoutOf(c, scene)
                selected(OverlayLayout.hit(scene, lc, o.x * c.width / size.width - ox, o.y * c.height / size.height))
            }
        }
        .pointerInput(scene) {
            var kind: ZoneKind? = null
            detectDragGestures(onDragStart = { o ->
                val c = cfg(); val (lc, ox) = twitchLayoutOf(c, scene)
                kind = OverlayLayout.hit(scene, lc, o.x * c.width / size.width - ox, o.y * c.height / size.height); selected(kind)
            }) { ch, d ->
                val k = kind ?: return@detectDragGestures
                ch.consume()
                val c = cfg(); val (lc, _) = twitchLayoutOf(c, scene)
                val moved = OverlayLayout.move(lc, scene, k, d.x * c.width / size.width, d.y * c.height / size.height)
                onChange(c.copy(zoneMoves = moved.zoneMoves))
            }
        }

/** Scenes whose elements can be moved. */
fun SceneKind.movable() = !isAlert

@Composable
fun PositionPanel(cfg: OverlayConfig, scene: SceneKind?, zone: ZoneKind?, onZone: (ZoneKind) -> Unit, onChange: (OverlayConfig) -> Unit) {
    if (scene == null || !scene.movable()) {
        Text("Choisis une scène ou un cadre au-dessus pour déplacer ses éléments.", fontSize = 12.sp)
        return
    }
    Text("Fais glisser un élément directement sur l'aperçu pour le déplacer, ou choisis-le ici puis règle sa taille.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    val (lc, _) = twitchLayoutOf(cfg, scene)
    val zones = OverlayLayout.zones(scene, lc).map { it.kind }.distinct()
    Heading("ÉLÉMENT")
    TwitchChips(zones.map { it.label }, zones.indexOf(zone).takeIf { it >= 0 }) { onZone(zones[it]) }
    val z = zone?.takeIf { it in zones } ?: return
    val m = cfg.zoneMoves[OverlayLayout.key(scene, z)] ?: ZoneMove()
    Heading("TAILLE : ${(m.scale * 100).toInt()} %")
    Slider(m.scale, { v -> onChange(cfg.copy(zoneMoves = OverlayLayout.move(lc, scene, z, 0f, 0f, v / m.scale.coerceAtLeast(0.01f)).zoneMoves)) }, valueRange = 0.3f..2.5f,
        colors = SliderDefaults.colors(thumbColor = TwitchAccent, activeTrackColor = TwitchAccent))
    Heading("DÉPLACER")
    val step = lc.width / 96f
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("←" to (-step to 0f), "→" to (step to 0f), "↑" to (0f to -step), "↓" to (0f to step)).forEach { (l, d) ->
            OutlinedButton({ onChange(cfg.copy(zoneMoves = OverlayLayout.move(lc, scene, z, d.first, d.second).zoneMoves)) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(l, fontSize = 16.sp) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ onChange(cfg.copy(zoneMoves = cfg.zoneMoves - OverlayLayout.key(scene, z))) }) { Text("Replacer cet élément") }
        OutlinedButton({ onChange(OverlayLayout.reset(cfg, scene)) }) { Text("Tout replacer") }
    }
}

// ---------------------------------------------------------------------- logo & assets

@Composable
fun LogoPanel(cfg: OverlayConfig, onPick: () -> Unit, onChange: (OverlayConfig) -> Unit) {
    Heading("MON LOGO")
    Text("Importe ton logo (PNG transparent conseillé) : il apparaît sur les scènes, les cadres, la photo de profil, les panneaux et la bannière. Tu peux le déplacer dans l'onglet Position.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onPick, colors = ButtonDefaults.buttonColors(containerColor = TwitchAccent, contentColor = Color.White)) {
            Icon(CxIcons.Upload, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (cfg.logo == null) "Importer un logo" else "Changer le logo")
        }
        if (cfg.logo != null) OutlinedButton({ onChange(cfg.copy(logo = null)) }) { Text("Retirer") }
    }
    if (cfg.logo != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Logo sur les scènes et cadres", Modifier.weight(1f), fontSize = 13.sp)
        Switch(cfg.logoOnScenes, { v -> onChange(cfg.copy(logoOnScenes = v)) }, colors = SwitchDefaults.colors(checkedTrackColor = TwitchAccent))
    }
}

@Composable
fun EmotesEditor(cfg: OverlayConfig, onChange: (OverlayConfig) -> Unit) {
    Heading("EMOTES (TEXTE DESSINÉ DANS TON STYLE)")
    cfg.emotes.forEachIndexed { i, e ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(e, { v -> onChange(cfg.copy(emotes = cfg.emotes.mapIndexed { j, x -> if (j == i) v.take(6) else x })) }, Modifier.weight(1f), singleLine = true, label = { Text("Emote ${i + 1}") })
            IconButton({ onChange(cfg.copy(emotes = cfg.emotes.filterIndexed { j, _ -> j != i })) }) { Icon(CxIcons.Close, "Retirer", tint = TwitchAccent) }
        }
    }
    if (cfg.emotes.size < 9) TwitchChips(listOf("GG", "RIP", "OMG", "LUL", "POG", "<3", "EZ", "F").filter { it !in cfg.emotes }.map { "+ $it" }, null) { i ->
        val v = listOf("GG", "RIP", "OMG", "LUL", "POG", "<3", "EZ", "F").filter { it !in cfg.emotes }[i]
        onChange(cfg.copy(emotes = cfg.emotes + v))
    }
}

/** Stores an imported (already resized) PNG as the config's logo. */
fun encodeLogo(png: ByteArray): String = java.util.Base64.getEncoder().encodeToString(png)
