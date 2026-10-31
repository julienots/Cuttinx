package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.project.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test

class VersionsTest {
    private fun tmp() = kotlin.io.path.createTempDirectory("ver").toFile()
    private fun base(): Project {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        return p
    }

    @Test fun snapshotsAreListedRestoredAndDeduplicated() {
        val st = VersionStore(tmp())
        val p = base()
        val v1 = st.snapshot(p, "Départ", auto = true, nowMs = 1_000)!!
        assertNull(st.snapshot(p, "Départ", auto = true, nowMs = 2_000))           // nothing changed
        val p2 = TimelineEditor.appendAsset(p, T.video("c", 4.0)).first
        val v2 = st.snapshot(p2, "Avant le teaser", auto = false, nowMs = 3_000)!!
        val list = st.list(p.id)
        assertEquals(listOf(v2.id, v1.id), list.map { it.id })
        assertEquals(3, list.first().clipCount)
        assertEquals(p, st.load(v1)!!.copy(modifiedAtMs = p.modifiedAtMs))
        assertFalse(list.first().auto)
    }

    @Test fun oldAutomaticVersionsAreThinnedButNamedOnesStay() {
        val st = VersionStore(tmp(), keepAuto = 6)
        var p = base()
        val named = st.snapshot(p, "Version finale", auto = false, nowMs = 1)!!
        for (i in 1..40) {
            p = TimelineEditor.appendAsset(p, T.image("i$i")).first
            st.snapshot(p, "Auto", auto = true, nowMs = i * 60_000L + 10)   // one per minute: all in the same hour
        }
        val autos = st.list(p.id).filter { it.auto }
        assertTrue("${autos.size}", autos.size <= 6)
        assertTrue(st.list(p.id).any { it.id == named.id })
        // the newest automatic ones are the ones kept
        assertEquals(40 * 60_000L + 10, autos.first().atMs)
    }

    @Test fun diffExplainsWhatChanged() {
        val old = base()
        var now = TimelineEditor.appendAsset(old, T.video("c", 4.0)).first
        val first = now.tracks.first { it.kind == TrackKind.VIDEO }.sortedClips().first()
        now = TimelineEditor.updateClip(now, first.id) { it.copy(effects = listOf(EffectInstance("fx1", "bw", emptyMap()))) }
        now = TimelineEditor.addMarker(now, 1_000_000)
        val lines = ProjectDiff.summary(old, now)
        val text = lines.joinToString("\n")
        assertTrue(text, "Durée : 16.0 s → 20.0 s (+4.0 s)" in text)
        assertTrue(text, "Ajouté depuis : 1 plan(s)" in text)
        assertTrue(text, "Effets modifiés sur 1" in text)
        assertTrue(text, "Marqueurs : 0 → 1" in text)
        assertTrue(ProjectDiff.summary(old, old).isEmpty())
    }

    @Test fun crashLogKeepsReportsAndDetectsALoop() {
        val log = CrashLog(tmp(), maxFiles = 4)
        val e = IllegalStateException("boom")
        for (i in 1..6) log.write("main", e, "Projet p1", nowMs = 1_000_000L + i * 1000)
        assertEquals(4, log.files().size)                       // oldest reports removed
        assertTrue(log.looping(nowMs = 1_010_000))              // 3+ in the last 10 minutes
        assertFalse(log.looping(nowMs = 1_000_000L + 3_600_000)) // an hour later: fine
        val f = log.unseen()!!
        assertTrue("IllegalStateException" in f.readText() && "Projet p1" in f.readText())
        log.markSeen(f)
        assertNotEquals(f, log.unseen())
    }
}
