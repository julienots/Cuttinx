package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PlusTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private var now = 1_700_000_000_000L

    /** Same format as the site (website/lib/plus.js). */
    private fun sign(json: String, key: PrivateKey = pair.private): String {
        val payload = json.toByteArray()
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(payload) }.sign()
        val enc = Base64.getUrlEncoder().withoutPadding()
        return Plus.PREFIX + enc.encodeToString(payload) + "." + enc.encodeToString(s)
    }
    private fun code(exp: Long, sub: String = "sub_123") = sign("""{"v":1,"sub":"$sub","email":"a@b.fr","plan":"plus","exp":$exp,"iss":"https://exemple.fr"}""")

    @Before fun setUp() { Plus.keyOverride = pair.public; TechVault.clock = { now }; Plus.deactivate() }
    @After fun tearDown() { Plus.deactivate(); Plus.keyOverride = null; TechVault.clock = { System.currentTimeMillis() } }

    @Test fun licenceIsCheckedOffline() {
        val c = code(now + 86_400_000L)
        val l = Plus.parse(c)!!
        assertEquals("sub_123", l.sub); assertEquals("https://exemple.fr", l.iss)
        assertTrue(Plus.isValid(l))
        assertNull(Plus.parse(c.dropLast(3) + "AAA"))                                   // tampered signature
        assertNull(Plus.parse(c.replaceFirst("CXP1.", "CXP1.e")))                        // tampered payload
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertNull(Plus.parse(sign("""{"exp":${now + 1000000}}""", other.private)))       // not signed by CUTTINX
        assertNull(Plus.parse("n'importe quoi"))
        assertNotNull(Plus.parse(" " + c.chunked(20).joinToString("\n") + " "))          // spaces / line breaks from a copy-paste
        assertFalse(Plus.isValid(Plus.parse(code(now - 1))))
    }

    @Test fun activationUnlocksEverythingUntilItEnds() {
        assertFalse(Plus.active)
        assertTrue(QuickActions["plus_frame_lock"]!!.locked)
        TechVault.unlock(Tech.FRAME_LOCK)                                                 // an advert cannot unlock a Plus technology
        assertFalse(TechVault.isUnlocked(Tech.FRAME_LOCK))
        assertNotNull(Plus.activate(code(now - 1)))                                      // expired code refused
        assertNotNull(Plus.activate("CXP1.faux.code"))
        assertNull(Plus.activate(code(now + 30L * 86_400_000L)))
        assertTrue(Plus.active)
        assertTrue(Tech.entries.all { TechVault.isUnlocked(it) })
        assertTrue(QuickActions.all.none { it.locked })
        assertEquals("Plus", TechVault.remaining(Tech.ECHO))
        now += 31L * 86_400_000L
        assertFalse(Plus.active)
        assertFalse(TechVault.isUnlocked(Tech.CINE_GRADE))
        assertTrue(Tech.entries.filter { it.plus }.all { QuickActions.byTech(it).isNotEmpty() })
    }

    // ------------------------------------------------------------------ technologies

    private fun three(): Project {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        p = TimelineEditor.appendAsset(p, T.image("i")).first
        return p
    }

    @Test fun frameLockPutsEverythingOnTheFrameGrid() {
        var p = three()
        val f = 1_000_000.0 / p.format.frameRate
        val m = SmartTools.mainTrack(p)!!.sortedClips()
        // off-grid edits: a clip that starts 12 ms late, a 9 ms overlap, an odd marker and keyframe
        p = TimelineEditor.updateClip(p, m[1].id) { it.copy(startUs = it.startUs - 9_000) }
        p = TimelineEditor.updateClip(p, m[2].id) { it.copy(startUs = it.startUs + 12_345, keyframes = mapOf(Prop.SCALE to KeyframeTrack(listOf(Keyframe(101_000, 1.0), Keyframe(1_234_567, 1.2))))) }
        p = p.copy(markers = listOf(Marker("m", 5_017_000)))
        val q = PlusLab.frameLock(p)
        fun onGrid(t: Long) = kotlin.math.abs(t / f - Math.round(t / f)) * f < 1.0
        val clips = q.allClips
        assertTrue(clips.all { onGrid(it.startUs) && onGrid(it.endUs) })
        assertTrue(q.markers.all { onGrid(it.timeUs) })
        assertTrue(clips.flatMap { it.keyframes.values }.flatMap { it.keyframes }.all { onGrid(it.timeUs) })
        val mq = SmartTools.mainTrack(q)!!.sortedClips()
        assertTrue(mq.zipWithNext().all { (a, b) -> b.startUs == a.endUs })               // gaps and overlaps closed
    }

    @Test fun beatLockCutsOnFrameAccurateBeats() {
        val p = three()
        val q = PlusLab.beatLock(p, 120.0)
        val beats = PlusLab.frameBeats(p, 120.0).toSet()
        val cuts = SmartTools.mainTrack(q)!!.sortedClips().drop(1).map { it.startUs }
        assertTrue(cuts.size > 4)
        val original = setOf(10_000_000L, 16_000_000L)
        assertTrue(cuts.all { it in beats || it in original })
        assertTrue(q.tracks.any { it.kind == TrackKind.EFFECT && it.name.startsWith("Temps forts") })
    }

    @Test fun gradeMasteringAndCamera() {
        val p = three()
        for (g in Grade.entries) {
            val q = PlusLab.cineGrade(p, g)
            val layer = q.tracks.first { it.kind == TrackKind.EFFECT }.clips.single()
            assertEquals(0L, layer.startUs); assertEquals(p.durationUs, layer.endUs)
            assertTrue(layer.effects.size >= 4)
        }
        val m = PlusLab.mastering(p, emptyList())
        val voice = SmartTools.mainTrack(m)!!.clips.first { Kit.hasSound(m, it) }
        assertTrue(voice.audio.effects.map { it.effectId }.containsAll(listOf("a_highpass", "a_deesser", "a_compressor", "a_limiter")))
        assertTrue(PlusLab.mastering(m, emptyList()).allClips.all { c -> c.audio.effects.count { it.effectId == "a_limiter" } <= 1 })   // re-run replaces
        val cam = PlusLab.parallax(p)
        val photo = cam.allClips.first { it.assetId == "i" }
        assertTrue(Prop.SCALE in photo.keyframes)
        assertTrue(cam.allClips.filter { it.assetId != "i" }.all { Prop.SCALE !in it.keyframes })
        val a = SmartTools.mainTrack(p)!!.sortedClips()[0]
        val focus = PlusLab.rackFocus(p, listOf(a.id)).findClip(a.id)!!.second
        assertTrue(focus.effects.any { it.effectId == "tilt_shift" } && focus.keyframes.keys.any { it.endsWith(":center") })
        val slow = PlusLab.smoothSlowmo(p, listOf(a.id)).findClip(a.id)!!.second
        assertTrue(slow.speedCurve.any { it.speed == 0.25 } && slow.stabilization > 0)
        assertTrue(PlusLab.volumetric(p).tracks.any { t -> t.clips.any { c -> c.effects.any { it.effectId == "god_rays" } } })
    }
}
