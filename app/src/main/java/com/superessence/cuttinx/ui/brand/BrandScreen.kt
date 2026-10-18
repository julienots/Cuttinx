package com.superessence.cuttinx.ui.brand

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superessence.cuttinx.core.brand.*
import com.superessence.cuttinx.core.overlay.OverlayThemes
import com.superessence.cuttinx.ui.theme.LocalCx
import com.superessence.cuttinx.ui.twitch.StudioViewModel

/** Accent of the identity space. */
val IdPink = Color(0xFFFF4FA3)

@Composable
private fun <T> PinkChips(options: List<T>, selected: (T) -> Boolean, label: (T) -> String, onClick: (T) -> Unit) {
    val cx = LocalCx.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val sel = selected(o)
            Text(label(o), Modifier.clip(RoundedCornerShape(50)).background(if (sel) IdPink else cx.panel2).clickable { onClick(o) }.padding(horizontal = 12.dp, vertical = 7.dp),
                color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(top = 16.dp, bottom = 6.dp), color = IdPink, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CUTTINX", text))
}

/** "ID Influenceur": builds a creator's visual identity and voice for every social network. */
@Composable
fun BrandScreen(vm: StudioViewModel, openTwitch: () -> Unit) {
    val kit by vm.brandKit.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(kit?.brief?.name ?: "") }
    var niche by remember { mutableStateOf(kit?.brief?.niche ?: Niche.GAMING) }
    var traits by remember { mutableStateOf(kit?.brief?.traits ?: setOf(Trait.FUN, Trait.AUTHENTIC)) }
    var audience by remember { mutableStateOf(kit?.brief?.audience ?: Audience.YOUNG_ADULTS) }
    var useHue by remember { mutableStateOf(false) }
    var hue by remember { mutableFloatStateOf(300f) }
    var keywords by remember { mutableStateOf(kit?.brief?.keywords ?: "") }
    var seed by remember { mutableIntStateOf(kit?.seed ?: 0) }
    var showForm by remember { mutableStateOf(kit == null) }
    val boardLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> if (uri != null) vm.exportBoard(uri) }
    fun generate() { vm.generate(BrandBrief(name.ifBlank { "MaChaîne" }, niche, traits, audience, if (useHue) hue.toInt() else -1, keywords), seed); showForm = false }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
        msg?.let { m -> Text(m, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(IdPink.copy(alpha = 0.18f)).clickable { vm.message.value = null }.padding(10.dp), fontSize = 13.sp) }
        if (showForm || kit == null) {
            Text("Crée ton univers de créateur", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Couleurs, polices, slogans, bios, idées de contenus et ton : une image cohérente sur TikTok, Instagram, YouTube et Twitch.", color = LocalCx.current.dim, fontSize = 13.sp)
            Label("1. TON NOM DE CRÉATEUR")
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("ex. Luna Gaming") })
            Label("2. TA NICHE")
            PinkChips(Niche.entries.toList(), { it == niche }, { it.label }, { niche = it })
            Label("3. TA PERSONNALITÉ (plusieurs choix)")
            Trait.entries.chunked(6).forEach { row -> PinkChips(row, { it in traits }, { it.label }, { t -> traits = if (t in traits) traits - t else traits + t }); Spacer(Modifier.height(6.dp)) }
            Label("4. TON PUBLIC")
            PinkChips(Audience.entries.toList(), { it == audience }, { it.label }, { audience = it })
            Label("5. OPTIONS")
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Imposer ma couleur préférée", Modifier.weight(1f)); Switch(useHue, { useHue = it }, colors = SwitchDefaults.colors(checkedTrackColor = IdPink)) }
            if (useHue) {
                Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color.hsv(hue, 0.8f, 0.9f)))
                Slider(hue, { hue = it }, valueRange = 0f..359f, colors = SliderDefaults.colors(thumbColor = IdPink, activeTrackColor = IdPink))
            }
            OutlinedTextField(keywords, { keywords = it }, Modifier.fillMaxWidth(), label = { Text("Mots-clés (jeux, thèmes…)") })
            Spacer(Modifier.height(12.dp))
            Button({ seed = 0; generate() }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = IdPink, contentColor = Color.White)) { Text("Créer mon identité") }
            if (kit != null) TextButton({ showForm = false }, Modifier.fillMaxWidth()) { Text("Voir mon identité actuelle", color = IdPink) }
            return@Column
        }
        val k = kit!!
        // ---------------------------------------------------------------- result
        Row(verticalAlignment = Alignment.CenterVertically) {
            val logo = remember(k) { vm.raster.frame(240, 240).also { vm.board.drawMonogram(it.gfx, k, 120f, 120f, 236f) }.bitmap.asImageBitmap() }
            Image(logo, "Logo", Modifier.size(84.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(k.brief.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(k.archetype, color = IdPink, fontWeight = FontWeight.Bold)
            }
        }
        Text(k.archetypeDescription, color = LocalCx.current.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ seed++; generate() }, colors = ButtonDefaults.buttonColors(containerColor = IdPink, contentColor = Color.White)) { Text("Autre proposition") }
            OutlinedButton({ showForm = true }) { Text("Modifier mes réponses") }
        }
        Label("PALETTE (touche pour copier)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            k.palette.forEach { c ->
                Column(Modifier.weight(1f).clickable { copy(ctx, c.hex); vm.message.value = "${c.hex} copié" }) {
                    Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(10.dp)).background(Color(c.argb.toInt())))
                    Text(c.role, fontSize = 10.sp, maxLines = 1, fontWeight = FontWeight.Bold)
                    Text(c.hex, fontSize = 10.sp, color = LocalCx.current.dim)
                }
            }
        }
        Label("POLICES")
        Text("Titres : ${k.fonts.title} · Texte : ${k.fonts.body}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(k.fonts.why, color = LocalCx.current.dim, fontSize = 12.sp)
        Label("SLOGANS")
        k.taglines.forEach { t -> Text("« $t »", Modifier.fillMaxWidth().clickable { copy(ctx, t); vm.message.value = "Slogan copié" }.padding(vertical = 4.dp), fontSize = 14.sp) }
        Label("BIOS PRÊTES À COLLER")
        k.bios.forEach { (platform, bio) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(LocalCx.current.panel2).clickable { copy(ctx, bio); vm.message.value = "Bio $platform copiée" }.padding(10.dp)) {
                Text(platform, color = IdPink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(bio, fontSize = 13.sp)
            }
        }
        Label("IDÉES DE CONTENU")
        k.pillars.forEach { p ->
            Text(p.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Text(p.description, color = LocalCx.current.dim, fontSize = 12.sp)
            p.ideas.forEach { Text("💡 $it", fontSize = 13.sp) }
        }
        Label("HASHTAGS")
        val tags = k.hashtags.joinToString(" ")
        Text(tags, Modifier.clickable { copy(ctx, tags); vm.message.value = "Hashtags copiés" }, fontSize = 13.sp)
        Label("TON À ADOPTER")
        k.toneDo.forEach { Text("✅ $it", fontSize = 13.sp) }
        k.toneDont.forEach { Text("⛔ $it", fontSize = 13.sp) }
        Label("MOODBOARD, LOGO, RYTHME")
        Text(k.moodboard.joinToString(" · "), fontSize = 13.sp)
        Text(k.logoIdea, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Text("📅 ${k.postingRhythm}", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))
        Button({ vm.useBrand(k); openTwitch() }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D8BFF), contentColor = Color.White)) {
            Text("Utiliser pour mes overlays Twitch (${OverlayThemes[k.overlayThemeId].name})")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ boardLauncher.launch("identite-${k.brief.name}.png") }, Modifier.weight(1f)) { Text("Planche PNG") }
            OutlinedButton({
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, guideText(k)), "Partager mon identité"))
            }, Modifier.weight(1f)) { Text("Partager le guide") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Plain-text identity guide (shared to notes, messages, e-mail...). */
fun guideText(k: BrandKit): String = buildString {
    appendLine("IDENTITÉ DE ${k.brief.name.uppercase()} — ${k.archetype}")
    appendLine(k.archetypeDescription); appendLine()
    appendLine("PALETTE"); k.palette.forEach { appendLine("• ${it.role} : ${it.name} ${it.hex}") }; appendLine()
    appendLine("POLICES : ${k.fonts.title} (titres) / ${k.fonts.body} (texte)"); appendLine()
    appendLine("SLOGANS"); k.taglines.forEach { appendLine("• $it") }; appendLine()
    k.bios.forEach { (p, b) -> appendLine("BIO $p"); appendLine(b); appendLine() }
    appendLine("IDÉES DE CONTENU"); k.pillars.forEach { appendLine("• ${it.name} : ${it.ideas.joinToString(", ")}") }; appendLine()
    appendLine("HASHTAGS : ${k.hashtags.joinToString(" ")}"); appendLine()
    appendLine("TON"); k.toneDo.forEach { appendLine("✅ $it") }; k.toneDont.forEach { appendLine("⛔ $it") }; appendLine()
    appendLine("LOGO : ${k.logoIdea}")
    appendLine("RYTHME : ${k.postingRhythm}")
}
