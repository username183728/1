package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimTextTest {
    private val eps = 0.001f

    @Test
    fun glyphsKeepSurrogatePairs() {
        val g = AnTextMath.glyphs("a😀b")
        assertEquals(3, g.size)
        assertEquals(1 until 3, g[1])
    }

    @Test
    fun typewriterVisibleCount() {
        assertEquals(0, AnTextMath.visibleCount(10, 99, 100, 1000))
        assertEquals(1, AnTextMath.visibleCount(10, 100, 100, 1000))
        assertEquals(6, AnTextMath.visibleCount(10, 600, 100, 1000))
        assertEquals(10, AnTextMath.visibleCount(10, 1100, 100, 1000))
        assertEquals(10, AnTextMath.visibleCount(10, 5000, 100, 1000))
        assertEquals(10, AnTextMath.visibleCount(10, 100, 100, 0))
    }

    @Test
    fun charProgressStaggersAndFinishesAtEnd() {
        val n = 5
        assertEquals(0f, AnTextMath.charProgress(0, n, 0, 0, 1000), eps)
        assertEquals(1f, AnTextMath.charProgress(n - 1, n, 1000, 0, 1000), eps)
        // Huruf pertama lebih dulu dari huruf terakhir.
        assertTrue(AnTextMath.charProgress(0, n, 300, 0, 1000) > AnTextMath.charProgress(n - 1, n, 300, 0, 1000))
        assertEquals(0f, AnTextMath.charProgress(n - 1, n, 599, 0, 1000), eps)
        assertEquals(1f, AnTextMath.charProgress(0, 1, 1000, 0, 1000), eps)
    }

    @Test
    fun easingEndpoints() {
        for (f in listOf(AnTextMath::easeOutCubic, AnTextMath::easeOutBack, AnTextMath::easeOutBounce)) {
            assertEquals(0f, f(0f), eps)
            assertEquals(1f, f(1f), eps)
        }
        assertTrue(AnTextMath.easeOutBack(0.6f) > 1f)
    }

    @Test
    fun effectsRestAtEnd() {
        for ((type, _) in AnTextMath.ALL) {
            val a = AnTextAnim(type, 200, 1000)
            for (i in 0 until 4) {
                val f = AnTextMath.fx(a, i, 4, 1300, 40f)
                assertEquals(type, 0f, f.dy, 0.01f)
                assertEquals(type, 1f, f.scale, 0.01f)
                assertEquals(type, 1f, f.alpha, 0.01f)
            }
            assertEquals(type, 0f, AnTextMath.extraSpacing(a, 1300), eps)
        }
    }

    @Test
    fun entranceEffectsHiddenAtStart() {
        for (type in listOf(AnTextMath.FADE, AnTextMath.BOUNCE, AnTextMath.TITLE, AnTextMath.SUBTITLE, AnTextMath.TYPEWRITER)) {
            assertEquals(type, 0f, AnTextMath.fx(AnTextAnim(type, 500, 1000), 3, 4, 100, 40f).alpha, eps)
        }
        assertTrue(AnTextMath.fx(AnTextAnim(AnTextMath.BOUNCE, 0, 1000), 0, 1, 0, 40f).dy < 0f)
        assertTrue(AnTextMath.extraSpacing(AnTextAnim(AnTextMath.TRACKING, 0, 1000), 0) > 0.4f)
    }

    @Test
    fun waveIsBoundedAndPhaseShifted() {
        val amp = 10f
        for (t in 0L..2000L step 37) {
            assertTrue(kotlin.math.abs(AnTextMath.waveOffset(0, t, 0, 2000, amp)) <= amp + eps)
        }
        assertEquals(0f, AnTextMath.waveOffset(0, 0, 0, 2000, amp), eps)
        assertEquals(0f, AnTextMath.waveOffset(0, 2500, 0, 2000, amp), eps)
        assertTrue(AnTextMath.waveOffset(0, 800, 0, 2000, amp) != AnTextMath.waveOffset(3, 800, 0, 2000, amp))
    }

    @Test
    fun alignmentHelpers() {
        assertEquals(10f, AnTextMath.lineX(10f, 100f, 40f, 0), eps)
        assertEquals(40f, AnTextMath.lineX(10f, 100f, 40f, 1), eps)
        assertEquals(70f, AnTextMath.lineX(10f, 100f, 40f, 2), eps)
        assertEquals(30f, AnTextMath.pathOffset(100f, 40f, 1), eps)
        assertEquals(0f, AnTextMath.pathOffset(30f, 40f, 2), eps)
    }

    // ------------------------------------------------------------------ timeline

    @Test
    fun snapTo50() {
        assertEquals(0L, AnTimeline.snap(24))
        assertEquals(50L, AnTimeline.snap(25))
        assertEquals(1250L, AnTimeline.snap(1262))
    }

    @Test
    fun moveKeySnapsSortsAndRejectsCollision() {
        val keys = listOf(AnKey(0, dx = 1f), AnKey(500, dx = 2f), AnKey(1000, dx = 3f))
        val moved = AnTimeline.moveKey(keys, 0, 1212, 2000)
        assertNotNull(moved)
        assertEquals(listOf(500L, 1000L, 1200L), moved!!.map { it.t })
        assertEquals(1f, moved[2].dx, eps)
        assertNull(AnTimeline.moveKey(keys, 0, 510, 2000))
        assertEquals(2000L, AnTimeline.moveKey(keys, 2, 9000, 2000)!!.last().t)
    }

    @Test
    fun rangeEditing() {
        val dur = 5000L
        var o = AnLayerOpt.DEFAULT
        assertTrue(o.activeAt(0, dur) && o.activeAt(dur, dur))
        o = AnTimeline.withIn(o, 1013, dur)
        assertEquals(1000L, o.inMs)
        o = AnTimeline.withOut(o, 900, dur)
        assertEquals(1100L, o.outMs)
        assertTrue(!o.activeAt(999, dur) && o.activeAt(1050, dur) && !o.activeAt(1200, dur))
        o = AnTimeline.withOut(o, 99999, dur)
        assertEquals(-1L, o.outMs)
        assertTrue(!o.copy(hidden = true).activeAt(2000, dur))
    }

    // ------------------------------------------------------------------ codec

    @Test
    fun codecRoundTripAndBackwardCompat() {
        val opts = mapOf(
            "a" to AnLayerOpt(inMs = 300, outMs = 2000, locked = true, text = AnTextAnim(AnTextMath.PATH, 100, 900, 1.5f, AnTextMath.PATH_CIRCLE)),
            "b" to AnLayerOpt.DEFAULT,
            "c" to AnLayerOpt(hidden = true)
        )
        val enc = AnLayerCodec.encode(opts)
        assertTrue("b" !in enc)
        val dec = AnLayerCodec.decode(enc)
        assertEquals(opts["a"], dec["a"])
        assertEquals(opts["c"], dec["c"])
        // Proyek lama: tanpa field "layers".
        assertTrue(AnLayerCodec.decode(null).isEmpty())
        // Tipe tak dikenal / out ≤ in dibersihkan.
        val bad = AnLayerCodec.decode(mapOf("x" to mapOf("in" to 500L, "out" to 100L, "text" to mapOf("type" to "zzz"))))
        assertEquals(AnLayerOpt(inMs = 500L), bad["x"])
    }
}
