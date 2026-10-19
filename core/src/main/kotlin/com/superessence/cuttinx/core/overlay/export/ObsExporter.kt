package com.superessence.cuttinx.core.overlay.export

import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.core.gfx.FontId
import com.superessence.cuttinx.core.gfx.Gfx
import com.superessence.cuttinx.core.gfx.Raster
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import java.io.File

/** What to include in an OBS pack. */
data class PackOptions(
    val scenes: Set<SceneKind> = SceneKind.entries.toSet(),
    val stingers: Set<StingerStyle> = emptySet(),
    /** Frame rate of the animated exports (scenes loop, alert, stingers). */
    val fps: Int = 24,
    /** Countdown of the "starting" scene in minutes (HTML version). */
    val countdownMinutes: Int = 5,
    /** Twitch profile panels and banners (profile + offline). */
    val profile: Boolean = true,
)

/**
 * Exports ready-to-use OBS files:
 *  - layouts (game / chatting / camera / chat frames) as transparent PNG,
 *  - animated scenes (starting, BRB, pause, end) as `scene.html` (background PNG + animated APNG +
 *    live countdown) and a flat `apercu.png`,
 *  - the follow alert and stinger transitions as transparent animated PNG,
 *  - a French step-by-step guide (LISEZ-MOI.txt).
 */
class ObsExporter(private val raster: Raster, private val renderer: OverlayRenderer = OverlayRenderer()) {

    fun renderPng(cfg: OverlayConfig, draw: (Gfx) -> Unit): ByteArray {
        val f = raster.frame(cfg.width, cfg.height)
        f.gfx.clear(0)
        draw(f.gfx)
        return f.png()
    }

    /** Renders [seconds] of animation into an APNG file. */
    fun renderApng(cfg: OverlayConfig, file: File, seconds: Double, fps: Int, onFrame: (Int, Int) -> Unit = { _, _ -> }, draw: (Gfx, Double) -> Unit) {
        val n = OverlayAnim.frameCount(seconds, fps)
        val f = raster.frame(cfg.width, cfg.height)
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            val w = ApngWriter(out, n, fps)
            for (i in 0 until n) {
                f.gfx.clear(0)
                draw(f.gfx, i.toDouble() / fps)
                w.addFrame(f.png())
                onFrame(i + 1, n)
            }
            w.finish()
        }
    }

    fun slug(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "scene" }

    /** Exports the whole pack into [root]/CuttinxPRO_<chaîne>_<thème>. Returns the folder. */
    fun exportPack(theme0: OverlayTheme, cfg: OverlayConfig, root: File, opt: PackOptions = PackOptions(), progress: (Float, String) -> Unit = { _, _ -> }): File {
        val theme = cfg.styled(theme0)
        val dir = File(root, "CuttinxPRO_${slug(cfg.channel)}_${theme.id}").also { it.mkdirs() }
        val animated = opt.scenes.filter { it.fullBackground }
        val layouts = opt.scenes.filter { !it.fullBackground && !it.isAlert && !it.animated }
        val widgets = opt.scenes.filter { !it.fullBackground && !it.isAlert && it.animated }
        val alerts = opt.scenes.filter { it.isAlert }
        val stingers = (opt.stingers + theme.stinger).toList()
        val steps = (animated.size * 2 + layouts.size + widgets.size + alerts.size + stingers.size + (if (opt.profile) 2 else 0)).coerceAtLeast(1).toFloat()
        var done = 0f
        fun step(label: String) { progress(done / steps, label) }

        // fonts used by the HTML pages
        val fontDir = File(dir, "polices").also { it.mkdirs() }
        val fontId = FontId.of(theme.font)
        val fontFile = fontId.file
        raster.fontBytes(fontId)?.let { File(fontDir, fontFile).writeBytes(it) }

        // ------------------------------------------------ layouts
        for ((i, s) in layouts.withIndex()) {
            step("Cadre « ${s.label} »")
            val f = File(dir, "cadres/${"%02d".format(i + 1)}_${slug(s.label)}.png")
            f.parentFile.mkdirs()
            // the vertical live layout is exported upright (9:16)
            val sc = if (s == SceneKind.VERTICAL) cfg.copy(width = cfg.height * 9 / 16 / 2 * 2, height = cfg.height) else cfg
            f.writeBytes(renderPng(sc) { c -> renderer.drawScene(c, theme, sc, s, 0.0) })
            done++
        }
        // ------------------------------------------------ animated widgets (ticker, socials pop-up)
        for (s in widgets) {
            renderApng(cfg, File(dir, "widgets/${slug(s.label)}.png"), OverlayAnim.LOOP_SEC, opt.fps, { a, b -> progress((done + a / b.toFloat()) / steps, "${s.label} ($a/$b)") }) { c, t ->
                renderer.drawScene(c, theme, cfg, s, t)
            }
            done++
        }
        // ------------------------------------------------ animated scenes
        for ((i, s) in animated.withIndex()) {
            val sd = File(dir, "scenes/${"%02d".format(i + 1)}_${slug(s.label)}").also { it.mkdirs() }
            step("Scène « ${s.label} » (fond)")
            File(sd, "fond.png").writeBytes(renderPng(cfg) { c -> renderer.drawScene(c, theme, cfg, s, 0.0, OverlayRenderer.Layer.STATIC, countdown = null) })
            File(sd, "apercu.png").writeBytes(renderPng(cfg) { c -> renderer.drawScene(c, theme, cfg, s, 0.0, OverlayRenderer.Layer.ALL) })
            done++
            renderApng(cfg, File(sd, "animation.png"), OverlayAnim.LOOP_SEC, opt.fps, { a, b -> progress((done + a / b.toFloat()) / steps, "Scène « ${s.label} » (animation $a/$b)") }) { c, t ->
                renderer.drawScene(c, theme, cfg, s, t, OverlayRenderer.Layer.MOVING)
            }
            File(sd, "scene.html").writeText(sceneHtml(theme, cfg, s, fontFile, opt.countdownMinutes))
            done++
        }
        // ------------------------------------------------ alerts
        for (al in alerts) {
            renderApng(cfg, File(dir, "alertes/${alertFile(al)}.png"), OverlayAnim.LOOP_SEC, opt.fps, { a, b -> progress((done + a / b.toFloat()) / steps, "${al.label} ($a/$b)") }) { c, t ->
                renderer.drawScene(c, theme, cfg, al, t)
            }
            done++
        }
        // ------------------------------------------------ Twitch profile: panels + banners
        if (opt.profile) {
            step("Panneaux et bannières Twitch")
            cfg.panels.filter { it.isNotBlank() }.forEachIndexed { i, label ->
                val f = raster.frame(640, 200)
                f.gfx.clear(0); f.gfx.scale(2f, 2f)
                renderer.drawPanel(f.gfx, theme, cfg, label)
                File(dir, "twitch-profil/panneaux/${"%02d".format(i + 1)}_${slug(label)}.png").also { it.parentFile.mkdirs() }.writeBytes(f.png())
            }
            val b = raster.frame(1200, 480); b.gfx.clear(0); renderer.drawBanner(b.gfx, theme, cfg)
            File(dir, "twitch-profil/banniere-profil.png").writeBytes(b.png())
            val off = cfg.copy(width = 1920, height = 1080)
            File(dir, "twitch-profil/banniere-hors-ligne.png").writeBytes(renderPng(off) { c -> renderer.drawScene(c, theme, off, SceneKind.OFFLINE, 0.0) })
            done++
            step("Badges, emotes, points de chaîne, avatar")
            fun square(path: String, size: Int, draw: (Gfx) -> Unit) {
                val fr = raster.frame(size, size); fr.gfx.clear(0); draw(fr.gfx)
                File(dir, path).also { it.parentFile.mkdirs() }.writeBytes(fr.png())
            }
            for (m in OverlayConfig.SUB_TIERS) for (sz in listOf(72, 36, 18)) square("twitch-profil/badges-abonnes/${m}-mois_$sz.png", sz) { g -> renderer.drawSubBadge(g, theme, cfg, m, sz.toFloat()) }
            cfg.emotes.filter { it.isNotBlank() }.forEachIndexed { i, e -> for (sz in listOf(112, 56, 28)) square("twitch-profil/emotes/${slug(e)}_$sz.png", sz) { g -> renderer.drawEmote(g, theme, cfg, e, sz.toFloat(), i) } }
            for (sz in listOf(112, 56, 28)) square("twitch-profil/points-de-chaine_$sz.png", sz) { g -> renderer.drawPoints(g, theme, cfg, sz.toFloat()) }
            square("twitch-profil/avatar.png", 800) { g -> renderer.drawAvatar(g, theme, cfg, 800f) }
            done++
        }
        // ------------------------------------------------ stingers
        for (st in stingers) {
            renderApng(cfg, File(dir, "transitions/${slug(st.label)}.png"), OverlayAnim.STINGER_SEC, 30, { a, b -> progress((done + a / b.toFloat()) / steps, "Transition « ${st.label} » ($a/$b)") }) { c, t ->
                renderer.drawStinger(c, theme, cfg, st, t)
            }
            done++
        }
        File(dir, "LISEZ-MOI.txt").writeText(readme(theme, cfg, animated, layouts, stingers, alerts, opt.profile))
        progress(1f, "Terminé")
        return dir
    }

    private fun alertFile(s: SceneKind) = when (s) {
        SceneKind.ALERT_SUB -> "nouvel-abonne"; SceneKind.ALERT_RAID -> "raid"; SceneKind.ALERT_DONATION -> "don"; else -> "nouveau-follow"
    }

    private fun hex(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

    /** Browser source page: static background + animated layer + live countdown for the starting scene. */
    fun sceneHtml(theme: OverlayTheme, cfg: OverlayConfig, s: SceneKind, fontFile: String, minutes: Int): String {
        val p = cfg.palette ?: theme.palette
        val info = OverlayLayout.zone(s, cfg, ZoneKind.INFO)!!
        val k = cfg.width / OverlayLayout.REF_W
        val countdown = s == SceneKind.STARTING
        return """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><title>${cfg.titleOf(s)} — Cuttinx PRO</title>
<style>
@font-face { font-family: 'CxTitle'; src: url('../../polices/$fontFile'); }
html, body { margin: 0; width: ${cfg.width}px; height: ${cfg.height}px; overflow: hidden; background: transparent; }
img { position: absolute; left: 0; top: 0; width: ${cfg.width}px; height: ${cfg.height}px; }
#cd { position: absolute; left: ${info.x.toInt()}px; top: ${(info.y + 44 * k).toInt()}px; width: ${info.w.toInt()}px; height: ${(info.h - 50 * k).toInt()}px;
      display: flex; align-items: center; justify-content: center; font-family: 'CxTitle', sans-serif; font-size: ${(58 * k).toInt()}px; color: ${hex(p.text)};
      text-shadow: 0 0 ${(12 * k).toInt()}px ${hex(p.glow)}; letter-spacing: ${(3 * k).toInt()}px; }
</style></head>
<body>
<img src="fond.png" alt="">
<img src="animation.png" alt="">
${if (countdown) """<div id="cd"></div>
<script>
// Compte à rebours : ajoutez ?min=10 à l'adresse pour changer la durée (par défaut $minutes min).
const params = new URLSearchParams(location.search);
const total = (parseFloat(params.get('min')) || $minutes) * 60;
const start = Date.now();
function tick() {
  const left = Math.max(0, Math.round(total - (Date.now() - start) / 1000));
  const m = Math.floor(left / 60), sec = left % 60;
  document.getElementById('cd').textContent = left > 0 ? String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0') : "C'EST PARTI !";
}
tick(); setInterval(tick, 250);
</script>""" else ""}
</body></html>
"""
    }

    private fun readme(theme: OverlayTheme, cfg: OverlayConfig, scenes: List<SceneKind>, layouts: List<SceneKind>, stingers: List<StingerStyle>, alerts: List<SceneKind>, profile: Boolean) = """
CUTTINX PRO — Pack d'overlays « ${theme.name} » pour ${cfg.channel}
=====================================================================

Tout est en ${cfg.width}×${cfg.height}, fond transparent. Réglez OBS sur la même résolution
(Paramètres > Vidéo > Résolution de base).

1. SCÈNES ANIMÉES (dossier « scenes »)
   ${scenes.joinToString("\n   ") { "• " + it.label }}
   Dans OBS : Sources > + > « Navigateur » > cochez « Fichier local » > choisissez scene.html.
   Largeur ${cfg.width}, hauteur ${cfg.height}. Le compte à rebours de « Le live va commencer » démarre
   à l'ouverture de la scène ; pour une autre durée, utilisez une URL : scene.html?min=10
   (décochez « Fichier local » et collez : file:///chemin/vers/scene.html?min=10).
   Variante simple : « Image » > apercu.png (sans animation).

2. CADRES (dossier « cadres »)
   ${layouts.joinToString("\n   ") { "• " + it.label }}
   Sources > + > « Image » > le PNG voulu. Placez votre capture de jeu et votre caméra SOUS
   l'image (dans la liste des sources, l'overlay doit être au-dessus). Les zones transparentes
   indiquent où mettre l'écran, la caméra et le chat.

3. ALERTES (dossier « alertes »)
   ${alerts.joinToString("\n   ") { "• " + it.label + " : " + alertFile(it) + ".png" }}
   Animations transparentes : utilisez-les comme images d'alerte dans votre outil d'alertes
   (Streamlabs, StreamElements…), ou en « Source média » dans OBS. Le pseudo affiché est un
   exemple : votre outil d'alertes écrit le vrai pseudo par-dessus.

4. TRANSITIONS (dossier « transitions »)
   ${stingers.joinToString("\n   ") { "• " + it.label }}
   Transitions de scène (en bas à droite) > + > « Stinger » > Fichier vidéo : le PNG animé.
   Point de transition : ${(OverlayAnim.STINGER_COVER_SEC * 1000).toInt()} ms (c'est l'instant où l'écran est entièrement couvert).

${if (profile) """5. PROFIL TWITCH (dossier « twitch-profil »)
   • Panneaux : Tableau de bord Twitch > Paramètres > Chaîne > « À propos » > Ajouter un panneau >
     image du dossier « panneaux » (320×100, fournis en 640×200 pour les écrans haute définition).
   • banniere-profil.png : Paramètres > Profil > Bannière de profil (1200×480).
   • banniere-hors-ligne.png : Paramètres > Chaîne > Bannière vidéo (affichée quand vous êtes hors ligne).
   • avatar.png : Paramètres > Profil > Photo de profil.
   • badges-abonnes : Tableau de bord > Monétisation > Badges d'abonné (72, 36 et 18 px par durée).
   • emotes : Tableau de bord > Monétisation > Emotes (112, 56 et 28 px).
   • points-de-chaine : Tableau de bord > Points de chaîne > Icône personnalisée.

6. WIDGETS (dossier « widgets »)
   Bandeau défilant et pop-up réseaux : PNG animés transparents, en « Source média » dans OBS
   (cochez « Lecture en boucle »).

""" else ""}Astuce : gardez le même thème partout (scènes, cadres, transitions, miniatures) — c'est ce qui
rend une chaîne reconnaissable.
""".trimStart()
}
