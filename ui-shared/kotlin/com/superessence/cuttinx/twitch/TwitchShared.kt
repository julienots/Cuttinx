package com.superessence.cuttinx.twitch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.gfx.Gfx
import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import com.superessence.cuttinx.icons.CxIcons
import kotlin.math.roundToInt

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

/** Scenes whose elements can be moved. */
fun SceneKind.movable() = !isAlert

private val speeds = listOf("Lent", "Normal", "Rapide", "Turbo")

@Composable
private fun SmallButton(label: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) =
    OutlinedButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.heightIn(min = 36.dp)) {
        Text(label, fontSize = 12.sp, color = if (danger) Color(0xFFFF6B6B) else Color.Unspecified)
    }

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = TwitchAccent, activeTrackColor = TwitchAccent))
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = TwitchAccent))
    }

private fun itemIcon(t: ItemType) = when (t) { ItemType.IMAGE -> CxIcons.Image; ItemType.TEXT -> CxIcons.Text; ItemType.STICKER -> CxIcons.Star }

/**
 * Elements of the scene (pictures, texts, stickers and the built-in camera / chat / title…), like
 * the layers of a graphics editor: select, show / hide, lock, reorder, duplicate, delete, and an
 * inspector with exact position, size, rotation, opacity, style and animation.
 * [onChange] receives a grouping key: quick changes with the same key make one undo step.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ElementsPanel(cfg: OverlayConfig, scene: SceneKind?, sel: Sel?, onSelect: (Sel?) -> Unit, onChange: (OverlayConfig, String?) -> Unit, onPickImages: (replaceId: String?) -> Unit,
                  /** true = a single canvas (Studio Créateur): no built-in elements, no "other scenes". */
                  canvasOnly: Boolean = false) {
    if (scene == null) {
        Text("Choisis une scène, un cadre ou une alerte au-dessus pour y ajouter des images, des textes et des stickers.", fontSize = 12.sp)
        return
    }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Text("Clique un élément sur l'aperçu (ou dans la liste) : glisse pour le déplacer, tire un coin pour l'agrandir, le rond pour le tourner. Sur mobile : deux doigts pour agrandir et tourner.",
        fontSize = 12.sp, color = dim)
    // new elements belong to this scene (or to the whole canvas in the Studio Créateur)
    val home: SceneKind? = if (canvasOnly) null else scene
    Heading(if (canvasOnly) "AJOUTER" else "AJOUTER SUR « ${scene.label.uppercase()} »")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button({ onPickImages(null) }, colors = ButtonDefaults.buttonColors(containerColor = TwitchAccent, contentColor = Color.White)) {
            Icon(CxIcons.Image, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Images")
        }
        Button({ val t = OverlayItems.text(cfg, home); onChange(OverlayItems.add(cfg, t), null); onSelect(Sel.Item(t.id)) },
            colors = ButtonDefaults.buttonColors(containerColor = TwitchAccent, contentColor = Color.White)) {
            Icon(CxIcons.Text, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Texte")
        }
    }
    Spacer(Modifier.height(6.dp))
    Text("Stickers :", fontSize = 12.sp, color = dim)
    TwitchChips(Sticker.entries.map { it.label }, null) { i ->
        val st = OverlayItems.sticker(cfg, home, Sticker.entries[i]); onChange(OverlayItems.add(cfg, st), null); onSelect(Sel.Item(st.id))
    }

    // ------------------------------------------------ layers
    Heading("CALQUES (LE PLUS HAUT EN PREMIER)")
    val items = cfg.itemsOf(scene).asReversed()
    val (lc, _) = twitchLayoutOf(cfg, scene)
    val zones = if (!canvasOnly && scene.movable()) OverlayLayout.zones(scene, lc).map { it.kind }.distinct() else emptyList()
    if (items.isEmpty() && zones.isEmpty()) Text("Aucun élément pour l'instant.", fontSize = 12.sp, color = dim)
    items.forEach { item ->
        LayerRow(itemIcon(item.type), item.name + if (item.type == ItemType.TEXT) " « ${item.text.take(18)} »" else "", if (item.scene == null && !canvasOnly) "toutes les scènes" else null,
            sel == Sel.Item(item.id), item.hidden, item.locked, { onSelect(Sel.Item(item.id)) },
            { onChange(cfg.updateItem(item.id) { x -> x.copy(hidden = !x.hidden) }, null) },
            { onChange(cfg.updateItem(item.id) { x -> x.copy(locked = !x.locked) }, null) },
            { onChange(OverlayItems.remove(cfg, item.id), null); if (sel == Sel.Item(item.id)) onSelect(null) })
    }
    zones.forEach { k ->
        LayerRow(CxIcons.Move, k.label, "élément du thème", sel == Sel.Zone(k), cfg.isHidden(scene, k), null, { onSelect(Sel.Zone(k)) },
            { onChange(OverlayLayout.setHidden(cfg, scene, k, !cfg.isHidden(scene, k)), null) }, null, null)
    }

    // ------------------------------------------------ inspector
    when (sel) {
        is Sel.Item -> cfg.item(sel.id)?.takeIf { it.scene == null || it.scene == scene }?.let { ItemInspector(cfg, scene, it, onSelect, onChange, onPickImages, canvasOnly) }
        is Sel.Zone -> if (sel.kind in zones) ZoneInspector(cfg, scene, sel.kind, onChange)
        null -> {}
    }
    if (zones.isNotEmpty() || items.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (zones.isNotEmpty()) SmallButton("Replacer les éléments du thème", { onChange(OverlayLayout.reset(cfg, scene), null) })
            if (canvasOnly) SmallButton("Tout effacer", { onChange(cfg.copy(items = emptyList()), null); onSelect(null) }, danger = true)
            else if (items.any { it.scene == scene }) SmallButton("Vider la scène", { onChange(cfg.copy(items = cfg.items.filter { it.scene != scene }), null); onSelect(null) }, danger = true)
        }
    }
}

@Composable
private fun LayerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, tag: String?, selected: Boolean, hidden: Boolean, locked: Boolean?,
                     onClick: () -> Unit, onEye: () -> Unit, onLock: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
        .background(if (selected) TwitchAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
        .border(1.dp, if (selected) TwitchAccent else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (hidden) Color.Gray else TwitchAccent)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, maxLines = 1, color = if (hidden) Color.Gray else Color.Unspecified)
            if (tag != null) Text(tag, fontSize = 10.sp, color = Color.Gray)
        }
        IconButton(onEye) { Icon(if (hidden) CxIcons.EyeOff else CxIcons.Eye, if (hidden) "Afficher" else "Masquer", Modifier.size(18.dp)) }
        if (onLock != null) IconButton(onLock) { Icon(if (locked == true) CxIcons.Lock else CxIcons.Unlock, if (locked == true) "Déverrouiller" else "Verrouiller", Modifier.size(18.dp)) }
        if (onDelete != null) IconButton(onDelete) { Icon(CxIcons.Delete, "Supprimer", Modifier.size(18.dp), tint = Color(0xFFFF6B6B)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemInspector(cfg: OverlayConfig, scene: SceneKind, item: OverlayItem, onSelect: (Sel?) -> Unit, onChange: (OverlayConfig, String?) -> Unit, onPickImages: (String?) -> Unit, canvasOnly: Boolean) {
    val id = item.id
    fun up(key: String?, f: (OverlayItem) -> OverlayItem) = onChange(cfg.updateItem(id, f), key?.let { "$it:$id" })
    val (lc, _) = twitchLayoutOf(cfg, scene)
    val cw = lc.width.toFloat(); val ch = lc.height.toFloat()
    Heading("ÉLÉMENT SÉLECTIONNÉ")
    if (item.type == ItemType.TEXT) OutlinedTextField(item.text, { v -> up("text") { it.copy(text = v) } }, Modifier.fillMaxWidth(), label = { Text("Texte") }, singleLine = true)
    if (item.type == ItemType.IMAGE) SmallButton("Remplacer l'image", { onPickImages(id) })
    if (!canvasOnly) ToggleRow("Afficher sur toutes les scènes", item.scene == null) { v -> up(null) { it.copy(scene = if (v) null else scene) } }
    // position & size
    LabeledSlider("Position horizontale : ${(item.x * 100).roundToInt()} %", item.x, 0f..1f) { v -> up("x") { it.copy(x = v) } }
    LabeledSlider("Position verticale : ${(item.y * 100).roundToInt()} %", item.y, 0f..1f) { v -> up("y") { it.copy(y = v) } }
    LabeledSlider("Taille : ${(item.w * 100).roundToInt()} % de la largeur", item.w, OverlayItems.MIN_W..1.5f) { v -> up("w") { OverlayItems.resize(it, v) } }
    if (item.type == ItemType.TEXT) LabeledSlider("Hauteur du texte : ${(item.aspect * 100).roundToInt()} %", item.aspect, 0.08f..1f) { v -> up("aspect") { it.copy(aspect = v) } }
    LabeledSlider("Rotation : ${item.rotation.roundToInt()}°", item.rotation, -180f..180f) { v -> up("rot") { it.copy(rotation = v) } }
    LabeledSlider("Opacité : ${(item.opacity * 100).roundToInt()} %", item.opacity, 0.05f..1f) { v -> up("alpha") { it.copy(opacity = v) } }
    Text("Aligner", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Gauche" to (-1 to null), "Centre" to (0 to null), "Droite" to (1 to null), "Haut" to (null to -1), "Milieu" to (null to 0), "Bas" to (null to 1)).forEach { (l, a) ->
            SmallButton(l, { up(null) { OverlayItems.align(it, cw, ch, a.first, a.second) } })
        }
        val step = 0.005f
        listOf("←" to (-step to 0f), "→" to (step to 0f), "↑" to (0f to -step), "↓" to (0f to step)).forEach { (l, d) ->
            SmallButton(l, { up("nudge") { OverlayItems.place(it, it.x + d.first, it.y + d.second * cw / ch) } })
        }
        SmallButton("-90°", { up(null) { it.copy(rotation = ((it.rotation - 90f + 540f) % 360f) - 180f) } })
        SmallButton("0°", { up(null) { it.copy(rotation = 0f) } })
        SmallButton("+90°", { up(null) { it.copy(rotation = ((it.rotation + 90f + 540f) % 360f) - 180f) } })
        SmallButton(if (item.flipX) "Miroir ↔ ✓" else "Miroir ↔", { up(null) { it.copy(flipX = !it.flipX) } })
        SmallButton(if (item.flipY) "Miroir ↕ ✓" else "Miroir ↕", { up(null) { it.copy(flipY = !it.flipY) } })
    }
    // style
    Heading("STYLE")
    if (item.type == ItemType.IMAGE) TwitchChips(ItemShape.entries.map { it.label }, item.shape.ordinal) { i -> up(null) { it.copy(shape = ItemShape.entries[i]) } }
    if (item.type == ItemType.STICKER) TwitchChips(Sticker.entries.map { it.label }, item.sticker.ordinal) { i -> up(null) { it.copy(sticker = Sticker.entries[i], name = Sticker.entries[i].label) } }
    if (item.type == ItemType.TEXT) {
        Text("Police", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        TwitchChips(listOf("Du thème") + TitleFont.entries.map { OverlayLabels.font(it) }, item.font?.let { it.ordinal + 1 } ?: 0) { i -> up(null) { it.copy(font = if (i == 0) null else TitleFont.entries[i - 1]) } }
    }
    if (item.type != ItemType.IMAGE || item.border) {
        Text("Couleur", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Thème", Modifier.clip(RoundedCornerShape(6.dp)).background(if (item.color == null) TwitchAccent else Color.White.copy(alpha = 0.08f)).clickable { up(null) { it.copy(color = null) } }.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 11.sp)
            OverlayLabels.swatches.forEach { c ->
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Color(c.toInt()))
                    .border(if (c == item.color) 3.dp else 1.dp, if (c == item.color) TwitchAccent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .clickable { up(null) { it.copy(color = c) } })
            }
        }
    }
    ToggleRow(if (item.type == ItemType.TEXT) "Contour" else "Bordure", item.border) { v -> up(null) { it.copy(border = v) } }
    ToggleRow("Ombre portée", item.shadow) { v -> up(null) { it.copy(shadow = v) } }
    ToggleRow("Halo lumineux", item.glow) { v -> up(null) { it.copy(glow = v) } }
    ToggleRow("Derrière les textes et cadres", item.behind) { v -> up(null) { it.copy(behind = v) } }
    // animation
    Heading("ANIMATION EN BOUCLE")
    TwitchChips(ItemAnim.entries.map { it.label }, item.anim.ordinal) { i -> up(null) { it.copy(anim = ItemAnim.entries[i]) } }
    if (item.anim != ItemAnim.NONE) {
        Spacer(Modifier.height(6.dp))
        TwitchChips(speeds, (item.animSpeed - 1).coerceIn(0, 3)) { i -> up(null) { it.copy(animSpeed = i + 1) } }
    }
    // order & actions
    Heading("ORDRE ET ACTIONS")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("Premier plan", { onChange(OverlayItems.reorder(cfg, id, Int.MAX_VALUE), null) })
        SmallButton("Avancer", { onChange(OverlayItems.reorder(cfg, id, 1), null) })
        SmallButton("Reculer", { onChange(OverlayItems.reorder(cfg, id, -1), null) })
        SmallButton("Arrière-plan", { onChange(OverlayItems.reorder(cfg, id, Int.MIN_VALUE), null) })
        SmallButton("Dupliquer", { val d = OverlayItems.duplicate(item); onChange(OverlayItems.add(cfg, d), null); onSelect(Sel.Item(d.id)) })
        if (!canvasOnly) SmallButton("Copier sur toutes les scènes", { onChange(OverlayItems.add(cfg, OverlayItems.duplicate(item).copy(scene = null, x = item.x, y = item.y)), null) })
        SmallButton(if (item.locked) "Déverrouiller" else "Verrouiller", { up(null) { it.copy(locked = !it.locked) } })
        SmallButton("Réinitialiser", { up(null) { it.copy(rotation = 0f, opacity = 1f, flipX = false, flipY = false, anim = ItemAnim.NONE, x = 0.5f, y = 0.5f) } })
        SmallButton("Supprimer", { onChange(OverlayItems.remove(cfg, id), null); onSelect(null) }, danger = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneInspector(cfg: OverlayConfig, scene: SceneKind, z: ZoneKind, onChange: (OverlayConfig, String?) -> Unit) {
    val (lc, _) = twitchLayoutOf(cfg, scene)
    val m = cfg.zoneMoves[OverlayLayout.key(scene, z)] ?: ZoneMove()
    Heading("${z.label.uppercase()} (ÉLÉMENT DU THÈME)")
    ToggleRow("Masquer sur cette scène", cfg.isHidden(scene, z)) { v -> onChange(OverlayLayout.setHidden(cfg, scene, z, v), null) }
    LabeledSlider("Taille : ${(m.scale * 100).roundToInt()} %", m.scale, 0.3f..2.5f) { v ->
        onChange(cfg.copy(zoneMoves = OverlayLayout.move(lc, scene, z, 0f, 0f, v / m.scale.coerceAtLeast(0.01f)).zoneMoves), "zsize:$z")
    }
    val step = lc.width / 96f
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("←" to (-step to 0f), "→" to (step to 0f), "↑" to (0f to -step), "↓" to (0f to step)).forEach { (l, d) ->
            SmallButton(l, { onChange(cfg.copy(zoneMoves = OverlayLayout.move(lc, scene, z, d.first, d.second).zoneMoves), "znudge:$z") })
        }
        SmallButton("Replacer cet élément", { onChange(cfg.copy(zoneMoves = cfg.zoneMoves - OverlayLayout.key(scene, z), hiddenZones = cfg.hiddenZones - OverlayLayout.key(scene, z)), null) })
    }
}

// ---------------------------------------------------------------------- logo & assets

@Composable
fun LogoPanel(cfg: OverlayConfig, onPick: () -> Unit, onChange: (OverlayConfig) -> Unit) {
    Heading("MON LOGO")
    Text("Importe ton logo (PNG transparent conseillé) : il apparaît sur les scènes, les cadres, la photo de profil, les panneaux et la bannière. Tu peux le déplacer, l'agrandir ou le masquer scène par scène dans l'onglet Éléments.",
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
