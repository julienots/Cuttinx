package com.superessence.cuttinx.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.brand.*
import com.superessence.cuttinx.core.overlay.OverlayThemes
import com.superessence.cuttinx.desktop.AppState
import com.superessence.cuttinx.desktop.Cx
import com.superessence.cuttinx.core.overlay.render.BrandBoard
import com.superessence.cuttinx.desktop.render.SkiaGfx

/** "Identité & DA": helps creators define a consistent image on social networks. */
@Composable
fun BrandStudio(state: AppState) {
    var name by remember { mutableStateOf(state.brandKit?.brief?.name ?: "") }
    var niche by remember { mutableStateOf(state.brandKit?.brief?.niche ?: Niche.GAMING) }
    var traits by remember { mutableStateOf(state.brandKit?.brief?.traits ?: setOf(Trait.FUN, Trait.AUTHENTIC)) }
    var audience by remember { mutableStateOf(state.brandKit?.brief?.audience ?: Audience.YOUNG_ADULTS) }
    var useHue by remember { mutableStateOf(false) }
    var hue by remember { mutableStateOf(280f) }
    var keywords by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf(0) }
    val board = remember { BrandBoard() }
    fun generate() {
        state.brandKit = BrandGenerator.generate(BrandBrief(name.ifBlank { "MaChaîne" }, niche, traits, audience, if (useHue) hue.toInt() else -1, keywords), seed)
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(380.dp).fillMaxHeight().background(Cx.Panel).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("ID Influenceur", fontSize = 24.sp, fontWeight = FontWeight.Black)
            Hint("Réponds à quelques questions : CUTTINX crée ta direction artistique (couleurs, polices, ton, bios, idées de contenus) pour avoir une image cohérente sur tous tes réseaux.")
            Title("1. TON NOM DE CRÉATEUR")
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("ex. Luna Gaming") })
            Title("2. TA NICHE")
            Niche.entries.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) { row.forEach { n -> Chip(n.label, n == niche) { niche = n } } } }
            Title("3. TA PERSONNALITÉ (plusieurs choix)")
            Trait.entries.chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) { row.forEach { t -> Chip(t.label, t in traits) { traits = if (t in traits) traits - t else traits + t } } } }
            Title("4. TON PUBLIC")
            Audience.entries.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) { row.forEach { a -> Chip(a.label, a == audience) { audience = a } } } }
            Title("5. OPTIONS")
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Imposer ma couleur préférée", Modifier.weight(1f)); Switch(useHue, { useHue = it }) }
            if (useHue) {
                Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color.hsv(hue, 0.8f, 0.9f)))
                Slider(hue, { hue = it }, valueRange = 0f..359f)
            }
            OutlinedTextField(keywords, { keywords = it }, Modifier.fillMaxWidth(), label = { Text("Mots-clés (jeux, thèmes…)") })
            Spacer(Modifier.height(12.dp))
            Button({ seed = 0; generate() }, Modifier.fillMaxWidth()) { Text("Créer mon identité") }
            OutlinedButton({ seed++; generate() }, Modifier.fillMaxWidth(), enabled = state.brandKit != null) { Text("Autre proposition") }
        }
        val kit = state.brandKit
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp)) {
            if (kit == null) {
                Spacer(Modifier.height(80.dp))
                Text("Ton univers de créateur apparaîtra ici", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Hint("Palette, polices, slogans, bios TikTok / Instagram / YouTube / Twitch, piliers de contenu, hashtags, ton à adopter, moodboard et logo.")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(110.dp)) { drawIntoCanvas { board.drawMonogram(SkiaGfx(it.nativeCanvas, state.fonts), kit, size.width / 2, size.height / 2, size.minDimension) } }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(kit.brief.name, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("${kit.archetype} — ${kit.archetypeDescription}", color = Cx.Dim)
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ state.useBrand(kit) }) { Text("Utiliser pour mes overlays Twitch (${OverlayThemes[kit.overlayThemeId].name})") }
                OutlinedButton({
                    val f = chooseFile("Enregistrer la planche d'identité", "identite-${kit.brief.name.lowercase().replace(' ', '-')}.png") ?: return@OutlinedButton
                    val fr = state.raster.frame(1600, 1000); board.draw(fr.gfx, kit)
                    f.writeBytes(fr.png())
                }) { Text("Planche PNG") }
                OutlinedButton({
                    val f = chooseFile("Enregistrer le guide d'identité", "identite-${kit.brief.name.lowercase().replace(' ', '-')}.md") ?: return@OutlinedButton
                    f.writeText(markdown(kit))
                }) { Text("Guide texte (.md)") }
            }
            Title("PALETTE")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                kit.palette.forEach { c ->
                    Column(Modifier.weight(1f).clickable { copyToClipboard(c.hex) }) {
                        Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(12.dp)).background(Color(c.argb.toInt())))
                        Text(c.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${c.role} · ${c.hex}", color = Cx.Dim, fontSize = 11.sp)
                    }
                }
            }
            Hint("Clique sur une couleur pour copier son code.")
            Title("POLICES")
            Text("Titres : ${kit.fonts.title}   ·   Texte : ${kit.fonts.body}", fontWeight = FontWeight.Bold)
            Hint(kit.fonts.why)
            Title("SLOGANS")
            kit.taglines.forEach { CopyLine("« $it »", it) }
            Title("BIOS PRÊTES À COLLER")
            kit.bios.forEach { (platform, bio) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Cx.Panel2).clickable { copyToClipboard(bio) }.padding(10.dp)) {
                    Text(platform, color = Color(0xFFFF4FA3), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(bio, fontSize = 13.sp)
                }
            }
            Title("PILIERS DE CONTENU")
            kit.pillars.forEach { p ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(p.name, fontWeight = FontWeight.Bold)
                    Hint(p.description)
                    Text(p.ideas.joinToString("  ·  ") { "💡 $it" }, fontSize = 13.sp)
                }
            }
            Title("HASHTAGS")
            CopyLine(kit.hashtags.joinToString(" "), kit.hashtags.joinToString(" "))
            Title("TON À ADOPTER")
            kit.toneDo.forEach { Text("✅ $it", fontSize = 13.sp) }
            kit.toneDont.forEach { Text("⛔ $it", fontSize = 13.sp) }
            Title("MOODBOARD")
            Text(kit.moodboard.joinToString("  ·  "), fontSize = 13.sp)
            Title("LOGO & RYTHME")
            Text(kit.logoIdea, fontSize = 13.sp)
            Text("📅 ${kit.postingRhythm}", fontSize = 13.sp)
        }
    }
}

@Composable
private fun CopyLine(display: String, value: String) {
    Row(Modifier.fillMaxWidth().clickable { copyToClipboard(value) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(display, Modifier.weight(1f), fontSize = 14.sp)
        Text("Copier", color = Color(0xFFFF4FA3), fontSize = 11.sp)
    }
}

/** Plain-text identity guide (Markdown) to keep or share. */
fun markdown(k: BrandKit): String = buildString {
    appendLine("# Identité de ${k.brief.name}")
    appendLine(); appendLine("**${k.archetype}** — ${k.archetypeDescription}")
    appendLine(); appendLine("## Palette"); k.palette.forEach { appendLine("- ${it.role} : ${it.name} `${it.hex}`") }
    appendLine(); appendLine("## Polices"); appendLine("- Titres : ${k.fonts.title}"); appendLine("- Texte : ${k.fonts.body}"); appendLine("- ${k.fonts.why}")
    appendLine(); appendLine("## Slogans"); k.taglines.forEach { appendLine("- $it") }
    appendLine(); appendLine("## Bios"); k.bios.forEach { (p, b) -> appendLine("### $p"); appendLine(b); appendLine() }
    appendLine("## Piliers de contenu"); k.pillars.forEach { appendLine("- **${it.name}** : ${it.description} (${it.ideas.joinToString(", ")})") }
    appendLine(); appendLine("## Hashtags"); appendLine(k.hashtags.joinToString(" "))
    appendLine(); appendLine("## Ton"); k.toneDo.forEach { appendLine("- ✅ $it") }; k.toneDont.forEach { appendLine("- ⛔ $it") }
    appendLine(); appendLine("## Moodboard"); appendLine(k.moodboard.joinToString(", "))
    appendLine(); appendLine("## Logo"); appendLine(k.logoIdea)
    appendLine(); appendLine("## Rythme de publication"); appendLine(k.postingRhythm)
}
