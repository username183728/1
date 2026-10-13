package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AnimEaseTest {
    private val eps = 1e-3f

    @Test fun legacyValuesUnchanged() {
        for (e in AnEase.ALL) assertEquals(e, AnEase.valid(e))
        assertEquals(AnEase.IN_OUT, AnEase.valid("bogus"))
        assertEquals(AnEase.IN_OUT, AnEase.valid(null))
        assertEquals(AnEase.IN_OUT, AnEase.valid("bezier:1,2"))
        assertEquals(AnEase.IN_OUT, AnEase.valid("spring:1"))
        assertEquals(AnEase.IN_OUT, AnEase.valid("bezier:a,b,c,d"))
    }

    @Test fun newFormsValidAndCanonical() {
        assertEquals("bezier:0.25,0.1,0.25,1", AnEase.valid("bezier:0.25,0.1,0.25,1.0"))
        assertEquals("spring:170,26", AnEase.valid("spring:170,26"))
        assertEquals("bounce", AnEase.valid("bounce"))
        assertTrue(AnEase.valid("back").startsWith("back:"))
        assertTrue(AnEase.valid("elastic").startsWith("elastic:"))
        // Dibatasi: x bezier 0..1.
        assertEquals("bezier:1,0,0,1", AnEase.valid("bezier:3,0,-2,1"))
        // Round-trip parse/format stabil.
        for ((k, _) in AnEasing.PRESETS) {
            val c = AnEase.valid(k)
            assertEquals(c, AnEase.valid(c))
        }
    }

    @Test fun endpointsExact() {
        for ((k, _) in AnEasing.PRESETS) {
            val e = AnEase.valid(k)
            assertEquals(e, 0f, AnEase.apply(e, 0f), 1e-6f)
            assertEquals(e, 1f, AnEase.apply(e, 1f), 1e-6f)
            assertEquals(e, 0f, AnEase.apply(e, -1f), 1e-6f)
            assertEquals(e, 1f, AnEase.apply(e, 2f), 1e-6f)
        }
    }

    @Test fun bezierMatchesKnownCurves() {
        // linear bezier = identitas
        for (i in 0..10) assertEquals(i / 10f, AnEasing.bezier(i / 10f, 0f, 0f, 1f, 1f), eps)
        // CSS "ease" di 0.5 ≈ 0.8024
        assertEquals(0.8024f, AnEasing.bezier(0.5f, 0.25f, 0.1f, 0.25f, 1f), 2e-3f)
        // simetris ease-in-out: f(0.5) = 0.5
        assertEquals(0.5f, AnEasing.bezier(0.5f, 0.42f, 0f, 0.58f, 1f), eps)
        // x monoton → solusi u valid untuk x ekstrem
        val u = AnEasing.solveU(0.3, 1.0, 0.0)
        assertTrue(u in 0.0..1.0)
    }

    @Test fun overshootCurves() {
        val back = AnEasing.parse("back")!!
        assertTrue(AnEasing.range(back).second > 1.05f)
        val bigger = AnEasing.withOvershoot(back, 1f)
        assertTrue(AnEasing.range(bigger).second > AnEasing.range(back).second)
        val el = AnEasing.parse("elastic")!!
        assertTrue(AnEasing.range(el).second > 1.1f)
        val sp = AnEasing.parse("spring:300,8")!!
        assertTrue(AnEasing.range(sp).second > 1.05f)
        // pegas teredam kritis/berlebih tidak overshoot
        val over = AnEasing.parse("spring:100,40")!!
        assertTrue(AnEasing.range(over).second <= 1.0001f)
        assertEquals(1f, AnEasing.apply(over, 1f), 1e-6f)
        assertTrue(AnEasing.apply(over, 0.5f) in 0f..1f)
    }

    @Test fun bounceStaysInRange() {
        for (i in 0..100) {
            val v = AnEasing.bounce(i / 100f)
            assertTrue(v in -1e-4f..1.0001f)
        }
        assertEquals(1f, AnEasing.bounce(1f), eps)
    }

    @Test fun accelRoundTrip() {
        val b = AnEasing.accel(0.5f, 0.25f)
        val (i, o) = AnEasing.accelOf(b)
        assertEquals(0.5f, i, eps)
        assertEquals(0.25f, o, eps)
        val lin = AnEasing.accel(0f, 0f)
        assertEquals(0.3f, AnEasing.apply(lin, 0.3f), eps)
    }

    @Test fun sampleUsesNewEase() {
        val keys = listOf(AnKey(0, dx = 0f, ease = "back:2"), AnKey(1000, dx = 100f))
        var maxDx = 0f
        for (t in 0..1000 step 10) maxDx = maxOf(maxDx, AnMath.sample(keys, t.toLong()).dx)
        assertTrue(maxDx > 100f)
        assertEquals(100f, AnMath.sample(keys, 1000).dx, eps)
        // codec keyframe lama mempertahankan ease baru
        val enc = AnCodec.encodeTracks(mapOf("a" to keys))
        val dec = AnCodec.decodeTracks(enc)
        assertEquals("back:2", dec["a"]!![0].ease)
    }

    @Test fun speedPositiveForMonotone() {
        val c = AnEasing.parse("inout")!!
        for (i in 1..9) assertTrue(AnEasing.speed(c, i / 10f) > 0f)
    }
}

class AnimFxTest {
    private val eps = 1e-4f

    @Test fun envelope() {
        assertEquals(0f, AnFxMath.envelope(-1, 0, 1000), eps)
        assertEquals(0f, AnFxMath.envelope(1001, 0, 1000), eps)
        assertEquals(1f, AnFxMath.envelope(500, 0, 1000), eps)
        assertEquals(0.5f, AnFxMath.envelope(100, 0, 1000, 200), eps)
        assertEquals(0f, AnFxMath.envelope(10, 0, 0), eps)
    }

    @Test fun deterministicNoise() {
        for (i in 0..50) {
            val a = AnFxMath.hash(7, i, 3)
            assertEquals(a, AnFxMath.hash(7, i, 3), 0f)
            assertTrue(a in 0f..1f)
        }
        assertTrue(abs(AnFxMath.hash(1, 1) - AnFxMath.hash(2, 1)) > 1e-6f)
        for (i in 0..100) assertTrue(AnFxMath.noise(3, i / 7f) in -1f..1f)
    }

    @Test fun shakeDeterministicAndBounded() {
        val e = AnEffect(AnFxMath.SHAKE, 0, 1000, 1f, seed = 42)
        val a = AnFxMath.shake(e, 400, 1000f, 800f)
        val b = AnFxMath.shake(e, 400, 1000f, 800f)
        assertTrue(a.contentEquals(b))
        assertTrue(abs(a[0]) <= 800f * 0.025f + eps)
        assertTrue(AnFxMath.shake(e, 2000, 1000f, 800f).all { it == 0f })
    }

    @Test fun particlesCappedDeterministic() {
        val e = AnEffect(AnFxMath.PARTICLES, 0, 3000, 2f, variant = 1, global = true, seed = 5)
        val a = AnFxMath.particles(e, 1234, 500f)
        val b = AnFxMath.particles(e, 1234, 500f)
        assertTrue(a.size <= AnFxMath.MAX_PARTICLES)
        assertEquals(a.size, b.size)
        for (i in a.indices) { assertEquals(a[i].x, b[i].x, 0f); assertEquals(a[i].y, b[i].y, 0f) }
        assertTrue(a.all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(AnFxMath.particles(e, 5000, 500f).isEmpty())
        for (v in 0..2) assertTrue(AnFxMath.particles(e.copy(variant = v), 1500, 500f).isNotEmpty())
    }

    @Test fun flashAndBlur() {
        val f = AnEffect(AnFxMath.FLASH, 1000, 500, 1f)
        assertEquals(0f, AnFxMath.flash(f, 900), eps)
        assertEquals(1f, AnFxMath.flash(f, 1100), eps)
        assertTrue(AnFxMath.flash(f, 1400) < 0.5f)
        val b = AnEffect(AnFxMath.BLUR, 0, 1000, 2f)
        assertEquals(1f, AnFxMath.blurAmount(b, 500), eps)
        assertEquals(0f, AnFxMath.blurAmount(b, 0), eps)
        assertEquals(1f, AnFxMath.blurFactor(0f), eps)
        assertEquals(1f / 24f, AnFxMath.blurFactor(1f), eps)
    }

    @Test fun motionSamplesAndGlitch() {
        val m = AnEffect(AnFxMath.MOTION_BLUR, 0, 1000, 1f)
        val s = AnFxMath.motionSamples(m, 33)
        assertEquals(AnFxMath.MOTION_SAMPLES, s.size)
        assertTrue(s.all { it.first < 0 && it.second in 0f..1f })
        assertTrue(AnFxMath.motionSamples(m.copy(amount = 0f), 33).isEmpty())
        assertFalse(AnFxMath.moving(AnState.IDENTITY, AnState.IDENTITY))
        val g = AnEffect(AnFxMath.GLITCH, 0, 1000, 1f, seed = 9)
        val a = AnFxMath.glitchSlices(g, 500)
        val b = AnFxMath.glitchSlices(g, 510)
        assertEquals(a.size, b.size) // langkah 66 ms yang sama
        assertTrue(a.all { it[0] in 0f..1f && it[1] in it[0]..1f })
        assertTrue(AnFxMath.glitchSlices(g, 1500).isEmpty())
    }

    @Test fun transitionPairsAndLook() {
        val opts = mapOf(
            "a" to AnLayerOpt(outMs = 2000),
            "b" to AnLayerOpt(inMs = 2050, trans = AnTransition(AnTransMath.CROSSFADE, 600)),
            "c" to AnLayerOpt(inMs = 4000, trans = AnTransition(AnTransMath.WIPE, 400))
        )
        val pairs = AnTransMath.pairs(listOf("a", "b", "c"), opts, 6000)
        assertEquals(2, pairs.size)
        assertEquals("a", pairs[0].fromId)
        assertNull(pairs[1].fromId)
        val rb = AnTransMath.roleOf(pairs, "b", 2050)!!
        assertTrue(rb.incoming)
        assertEquals(0.5f, rb.p, 0.01f)
        val ra = AnTransMath.roleOf(pairs, "a", 2100)
        assertNotNull(ra)
        assertFalse(ra!!.incoming)
        assertNull(AnTransMath.roleOf(pairs, "a", 3000))
        // crossfade: alfa saling melengkapi
        val la = AnTransMath.look(AnTransMath.Role(AnTransMath.CROSSFADE, false, 0.3f))
        val lb = AnTransMath.look(AnTransMath.Role(AnTransMath.CROSSFADE, true, 0.3f))
        assertEquals(1f, la[0] + lb[0], eps)
        // wipe masuk di awal tak terlihat (klip 0..0)
        val w = AnTransMath.look(AnTransMath.Role(AnTransMath.WIPE, true, 0f))
        assertEquals(0f, w[4], eps)
        val sl = AnTransMath.look(AnTransMath.Role(AnTransMath.SLIDE, true, 1f))
        assertEquals(0f, sl[1], eps)
    }

    @Test fun codecRoundTripAndBackwardCompat() {
        val o = AnLayerOpt(
            inMs = 100,
            effects = listOf(AnEffect(AnFxMath.PARTICLES, 200, 900, 1.5f, 2, true, 77), AnEffect(AnFxMath.GLITCH, 0, 500)),
            trans = AnTransition(AnTransMath.ZOOM, 800)
        )
        assertFalse(o.isDefault())
        assertFalse(AnLayerOpt(effects = listOf(AnEffect(AnFxMath.FLASH))).isDefault())
        val enc = AnLayerCodec.encode(mapOf("x" to o))
        val dec = AnLayerCodec.decode(enc)
        assertEquals(o, dec["x"])
        // proyek lama tanpa fx/tr
        val old = AnLayerCodec.decode(mapOf("y" to mapOf("in" to 300L, "out" to -1L)))
        assertTrue(old["y"]!!.effects.isEmpty())
        assertNull(old["y"]!!.trans)
        // jenis tak dikenal dibuang, batas dijaga
        val bad = AnLayerCodec.decode(mapOf("z" to mapOf("in" to 0L, "out" to -1L, "fx" to listOf(mapOf("type" to "nope"), mapOf("type" to "flash", "amt" to 9.0)), "tr" to mapOf("type" to "?"))))
        assertEquals(1, bad["z"]!!.effects.size)
        assertEquals(2f, bad["z"]!!.effects[0].amount, eps)
        assertNull(bad["z"]!!.trans)
    }
}
