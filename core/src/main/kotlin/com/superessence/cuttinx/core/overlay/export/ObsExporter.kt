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
    /** Animated GIF copies (play everywhere: file explorer, phone, OBS « Image », Streamlabs alert box). */
    val gif: Boolean = true,
    /** Transparent video writer (PC: WebM VP9 with alpha), null when the platform has none. */
    val video: VideoSink? = null,
)

/** Writes the frames (non-premultiplied ARGB) as a transparent video file. */
fun interface VideoSink {
    fun write(file: File, width: Int, height: Int, fps: Int, frames: Sequence<IntArray>)
}

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

    /** Renders an animation as an animated GIF, at most [maxWidth] wide (light, plays everywhere). */
    fun renderGif(cfg: OverlayConfig, file: File, seconds: Double, fps: Int, maxWidth: Int = 960, draw: (Gfx, Double) -> Unit) {
        val k = minOf(1f, maxWidth.toFloat() / cfg.width)
        val w = (cfg.width * k).toInt().coerceAtLeast(1); val h = (cfg.height * k).toInt().coerceAtLeast(1)
        val n = OverlayAnim.frameCount(seconds, fps)
        val f = raster.frame(w, h)
        fun frame(i: Int): IntArray { f.gfx.clear(0); f.gfx.save(); f.gfx.scale(k, k); draw(f.gfx, i.toDouble() / fps); f.gfx.restore(); return f.argb() }
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            val gif = GifWriter(out, w, h, fps)
            gif.palette((0 until 6).map { frame(it * n / 6) })
            for (i in 0 until n) gif.addFrame(frame(i))
            gif.finish()
        }
    }

    /** Frames of an animation for a [VideoSink], rendered one by one. */
    fun frames(cfg: OverlayConfig, seconds: Double, fps: Int, draw: (Gfx, Double) -> Unit): Sequence<IntArray> = sequence {
        val f = raster.frame(cfg.width, cfg.height)
        for (i in 0 until OverlayAnim.frameCount(seconds, fps)) { f.gfx.clear(0); draw(f.gfx, i.toDouble() / fps); yield(f.argb()) }
    }

    /**
     * Every animated format of one element, side by side in [dir]: `base.png` (animated PNG),
     * `base.html` (OBS / Streamlabs browser source), `base.gif` and `base.webm` (transparent video, PC).
     */
    fun exportAnimation(cfg: OverlayConfig, dir: File, base: String, seconds: Double, opt: PackOptions, onFrame: (Int, Int) -> Unit = { _, _ -> }, draw: (Gfx, Double) -> Unit) {
        dir.mkdirs()
        renderApng(cfg, File(dir, "$base.png"), seconds, opt.fps, onFrame, draw)
        File(dir, "$base.html").writeText(animatedHtml(cfg, "$base.png"))
        if (opt.gif) renderGif(cfg, File(dir, "$base.gif"), seconds, minOf(opt.fps, 20), draw = draw)
        opt.video?.let { v -> runCatching { v.write(File(dir, "$base.webm"), cfg.width, cfg.height, opt.fps, frames(cfg, seconds, opt.fps, draw)) } }
    }

    /** Browser source page that plays an animated PNG in a loop, transparent around it. */
    fun animatedHtml(cfg: OverlayConfig, file: String) = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><title>Cuttinx PRO</title>
<style>html, body { margin: 0; width: ${cfg.width}px; height: ${cfg.height}px; overflow: hidden; background: transparent; }
img { position: absolute; left: 0; top: 0; width: ${cfg.width}px; height: ${cfg.height}px; }</style></head>
<body><img src="$file" alt=""></body></html>
"""

    /**
     * Exports one element on its own, animated when it moves: a scene, a frame, a widget, an
     * alert or a transition. Returns the folder.
     */
    fun exportElement(theme0: OverlayTheme, cfg: OverlayConfig, root: File, scene: SceneKind?, stinger: StingerStyle?, opt: PackOptions, progress: (Float, String) -> Unit = { _, _ -> }): File {
        val theme = cfg.styled(theme0)
        val name = slug(scene?.label ?: stinger?.label ?: "element")
        val dir = File(root, "CuttinxPRO_$name").also { it.mkdirs() }
        val onFrame: (Int, Int) -> Unit = { a, b -> progress(a / b.toFloat() * 0.9f, "Image $a / $b") }
        when {
            stinger != null -> exportAnimation(cfg, dir, name, OverlayAnim.STINGER_SEC, opt.copy(fps = 30), onFrame) { c, t -> renderer.drawStinger(c, theme, cfg, stinger, t) }
            scene != null && scene.fullBackground -> {
                exportAnimation(cfg, dir, name, OverlayAnim.LOOP_SEC, opt, onFrame) { c, t -> renderer.drawScene(c, theme, cfg, scene, t) }
                // the live countdown version for the starting scene
                File(dir, "fond.png").writeBytes(renderPng(cfg) { c -> renderer.drawScene(c, theme, cfg, scene, 0.0, OverlayRenderer.Layer.STATIC, countdown = null) })
                renderApng(cfg, File(dir, "animation.png"), OverlayAnim.LOOP_SEC, opt.fps) { c, t -> renderer.drawScene(c, theme, cfg, scene, t, OverlayRenderer.Layer.MOVING) }
                val fontId = FontId.of(theme.font)
                raster.fontBytes(fontId)?.let { File(dir, "polices/${fontId.file}").also { f -> f.parentFile.mkdirs() }.writeBytes(it) }
                File(dir, "scene.html").writeText(sceneHtml(theme, cfg, scene, fontId.file, opt.countdownMinutes).replace("../../polices/", "polices/"))
            }
            scene != null && (scene.animated || cfg.itemsOf(scene).any { !it.hidden && it.anim != ItemAnim.NONE }) -> {
                val sc = if (scene == SceneKind.VERTICAL) cfg.copy(width = cfg.height * 9 / 16 / 2 * 2, height = cfg.height) else cfg
                exportAnimation(sc, dir, name, OverlayAnim.LOOP_SEC, opt, onFrame) { c, t -> renderer.drawScene(c, theme, sc, scene, t) }
            }
            scene != null -> {
                val sc = if (scene == SceneKind.VERTICAL) cfg.copy(width = cfg.height * 9 / 16 / 2 * 2, height = cfg.height) else cfg
                File(dir, "$name.png").writeBytes(renderPng(sc) { c -> renderer.drawScene(c, theme, sc, scene, 0.0) })
            }
        }
        File(dir, "LISEZ-MOI.txt").writeText(elementReadme(scene, stinger, name, opt.video != null))
        progress(1f, "Terminé")
        return dir
    }

    private fun elementReadme(scene: SceneKind?, stinger: StingerStyle?, name: String, video: Boolean) = """
CUTTINX PRO — ${scene?.label ?: stinger?.label}
=================================================

Fichiers (fond transparent, animation en boucle) :
  • $name.html  → OBS : Sources > + > « Navigateur » > Fichier local.
                  Streamlabs : + Source > « Source navigateur » > Fichier local.
  • $name.png   → PNG animé (OBS « Source média », navigateurs, Discord).
  • $name.gif   → GIF animé : se lit partout. OBS : Source « Image ». Streamlabs : Alert Box > Image.
${if (video) VIDEO_LINE.replace("NAME", name) else ""}${if (stinger != null) TRANSITION_LINE else ""}${if (scene?.fullBackground == true) SCENE_LINE else ""}
Un élément sans animation est exporté en PNG simple (source « Image »).
""".trimStart()

    private companion object {
        const val VIDEO_LINE = """  • NAME.webm  → Vidéo transparente : OBS « Source média » (Lecture en boucle) ou « Stinger » pour une
                  transition. Streamlabs : Source média, Alert Box > Image/Vidéo, Transition Stinger.
"""
        val TRANSITION_LINE = "\nTransition : point de transition à ${(OverlayAnim.STINGER_COVER_SEC * 1000).toInt()} ms.\n"
        const val SCENE_LINE = "\nscene.html = fond + animation + compte à rebours en direct (scene.html?min=10 pour 10 minutes).\n"
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
            // frames with animated pictures / texts / stickers also get their animated versions
            if (cfg.itemsOf(s).any { !it.hidden && it.anim != ItemAnim.NONE })
                exportAnimation(sc, f.parentFile, f.nameWithoutExtension + "_anime", OverlayAnim.LOOP_SEC, opt) { c, t -> renderer.drawScene(c, theme, sc, s, t) }
            done++
        }
        // ------------------------------------------------ animated widgets (ticker, socials pop-up)
        for (s in widgets) {
            exportAnimation(cfg, File(dir, "widgets"), slug(s.label), OverlayAnim.LOOP_SEC, opt, { a, b -> progress((done + a / b.toFloat()) / steps, "${s.label} ($a/$b)") }) { c, t ->
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
            if (opt.gif) renderGif(cfg, File(sd, "scene.gif"), OverlayAnim.LOOP_SEC, minOf(opt.fps, 20)) { c, t -> renderer.drawScene(c, theme, cfg, s, t) }
            opt.video?.let { v -> runCatching { v.write(File(sd, "scene.webm"), cfg.width, cfg.height, opt.fps, frames(cfg, OverlayAnim.LOOP_SEC, opt.fps) { c, t -> renderer.drawScene(c, theme, cfg, s, t) }) } }
            done++
        }
        // ------------------------------------------------ alerts
        for (al in alerts) {
            exportAnimation(cfg, File(dir, "alertes"), alertFile(al), OverlayAnim.LOOP_SEC, opt, { a, b -> progress((done + a / b.toFloat()) / steps, "${al.label} ($a/$b)") }) { c, t ->
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
            opt.video?.let { v -> runCatching { v.write(File(dir, "transitions/${slug(st.label)}.webm"), cfg.width, cfg.height, 30, frames(cfg, OverlayAnim.STINGER_SEC, 30) { c, t -> renderer.drawStinger(c, theme, cfg, st, t) }) } }
            done++
        }
        File(dir, "LISEZ-MOI.txt").writeText(readme(theme, cfg, animated, layouts, stingers, alerts, opt.profile) + streamlabsReadme(opt.video != null))
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

    private fun streamlabsReadme(video: Boolean) = """

=====================================================================
STREAMLABS DESKTOP (et OBS) — GARDER TOUT ANIMÉ
=====================================================================
Chaque élément animé existe en plusieurs formats, à côté les uns des autres :
  • .html : « Source navigateur » (OBS : Navigateur) > cochez « Fichier local » > choisissez le .html.
            C'est la meilleure qualité, animation en boucle, fond transparent.
  • .gif  : se lit partout. Streamlabs : Alert Box > Paramètres de l'alerte > Image > importez le GIF.
            OBS / Streamlabs : Source « Image » (les GIF y sont animés).
${if (video) """  • .webm : vidéo transparente. Source « Média » > cochez « Lecture en boucle ». Pour les transitions :
            Transitions > Stinger > la vidéo .webm du dossier « transitions ».
""" else """  • Les vidéos .webm transparentes (transitions Stinger) sont créées par l'appli PC CUTTINX PRO.
"""}  • .png animé : Source média d'OBS, navigateurs, Discord.
Astuce : un .png animé ou un .gif ouvert dans la visionneuse de Windows peut sembler figé ;
ouvrez-le dans un navigateur (Chrome, Edge, Firefox) pour voir l'animation.
"""

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
