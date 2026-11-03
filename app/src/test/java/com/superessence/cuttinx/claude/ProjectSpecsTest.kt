package com.superessence.cuttinx.claude

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.project.ProjectStore
import com.superessence.cuttinx.engine.CompositionFactory
import com.superessence.cuttinx.engine.LiveProject
import com.superessence.cuttinx.engine.MediaResolver
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** La recette envoyée par Claude doit produire un projet CUTTINX valide, sérialisable et rendable. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProjectSpecsTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private lateinit var lib: ClaudeLibrary

    @Before fun setup() {
        lib = ClaudeLibrary.get(ctx)
        lib.all().forEach { lib.remove(it.asset.id) }
        lib.add(SharedMedia(MediaAsset("v1", "file:///sdcard/v1.mp4", "v1.mp4", MediaType.VIDEO, "video/mp4", 60_000_000, 1080, 1920, hasAudio = true, hasVideo = true)))
        lib.add(SharedMedia(MediaAsset("v2", "file:///sdcard/v2.mp4", "v2.mp4", MediaType.VIDEO, "video/mp4", 60_000_000, 1080, 1920, hasAudio = true, hasVideo = true)))
        lib.add(SharedMedia(MediaAsset("m", "file:///sdcard/m.mp3", "m.mp3", MediaType.AUDIO, "audio/mpeg", 10_000_000, hasAudio = true, hasVideo = false)))
    }

    private val recipe = """
    {"name":"Edit calisthenics","format":{"aspect":"9:16","fps":30},
     "clips":[
       {"media_id":"v1","start":43.1,"end":46.5,"zoom":{"from":1.0,"to":1.2},"effects":[{"id":"zoom_blur","params":{"amount":0.2}}]},
       {"media_id":"v2","start":31.2,"end":35.4,"speed":0.5,"transition_in":{"id":"whip","duration":0.3}},
       {"media_id":"v1","start":30,"end":34.6,"speed_ramp":[{"position":0,"speed":0.5},{"position":1,"speed":2}],"mute":true,"transition_in":{"id":"flash"}},
       {"media_id":"v2","start":1,"end":2,"effects":[{"id":"nope"}]}
     ],
     "music":{"media_id":"m","start":2,"volume":0.8,"fade_out":1},
     "texts":[{"text":"NO LIMITS","start":0,"end":2,"position":"center","font":"bebas neue","size":90,"anim_in":"zoom"}],
     "effect_layers":[{"effect_id":"grain","start":0,"end":5},{"effect_id":"teal_orange"}]}
    """.trimIndent()

    @Test fun buildsEditableProject() {
        val (p, warnings) = ProjectSpecs.build(JSONObject(recipe), lib)
        assertEquals(1080, p.format.width); assertEquals(1920, p.format.height)
        val main = p.tracks.first { it.kind == TrackKind.VIDEO }.sortedClips()
        assertEquals(4, main.size)
        // la transition fait chevaucher les clips 1 et 2
        assertEquals("whip", main[1].transitionIn?.transitionId)
        assertTrue(main[1].startUs < main[0].endUs)
        // ralenti : 4,2 s source à 0,5x = 8,4 s sur la timeline
        assertEquals(8_400_000.0, main[1].durationUs.toDouble(), 50_000.0)
        assertEquals(2, main[2].speedCurve.size)
        assertTrue(main[2].audio.muted)
        assertTrue(main[0].keyframes.containsKey(Prop.SCALE))
        assertEquals("zoom_blur", main[0].effects.single().effectId)
        assertTrue(main[3].effects.isEmpty())
        assertTrue(warnings.any { "nope" in it })
        // musique de 10 s démarrant à 2 s : elle boucle pour couvrir toute la vidéo
        val music = p.tracks.first { it.kind == TrackKind.AUDIO }.sortedClips()
        assertTrue(music.size >= 2)
        assertEquals(2_000_000, music[0].sourceInUs)
        assertEquals(p.tracks.first { it.kind == TrackKind.VIDEO }.endUs.toDouble(), music.last().endUs.toDouble(), 60_000.0)
        assertEquals("NO LIMITS", p.tracks.first { it.kind == TrackKind.TEXT }.clips.single().text?.text)
        assertEquals(2, p.tracks.first { it.kind == TrackKind.EFFECT }.clips.size)
        // sérialisable par le ProjectStore
        val back = ProjectStore.decode(ProjectStore.encode(p))
        assertEquals(p.durationUs, back.durationUs)
        // et rendable par le moteur (même pipeline que l'aperçu et l'export)
        val built = CompositionFactory(ctx, MediaResolver { Uri.parse(it.uri) }).build(LiveProject(p), 540, 960, 30, preview = false)
        assertEquals(p.durationUs, built.durationUs)
        // relecture par Claude
        val d = ProjectSpecs.describe(p)
        assertEquals(p.id, d.getString("project_id"))
    }

    @Test fun replacesExistingProjectKeepingId() {
        val (p1, _) = ProjectSpecs.build(JSONObject(recipe), lib)
        val (p2, _) = ProjectSpecs.build(JSONObject(recipe).put("name", "v2"), lib, existing = p1)
        assertEquals(p1.id, p2.id); assertEquals("v2", p2.name)
    }

    @Test fun rejectsInvalidRange() {
        val bad = JSONObject("""{"name":"x","clips":[{"media_id":"v2","start":59.99,"end":70}]}""")
        try { ProjectSpecs.build(bad, lib); fail() } catch (e: IllegalArgumentException) { assertTrue("intervalle" in e.message!!) }
    }
}
