package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfMathTest {
    private fun plan(mode: String, start: Long = 0, span: Long = 5000, dur: Long = 5000, fps: Double = 30.0,
                     count: Int = 10, f: Double = 5.0, interval: Long = 1000, custom: List<Long> = emptyList()) =
        VfMath.plan(mode, start, span, dur, fps, count, f, interval, custom)

    @Test
    fun fiveSecondsAt30FpsIs150Frames() {
        assertEquals(150, VfMath.totalFrames(5000, 30.0))
        assertEquals(150, plan(VfMath.MODE_ALL).count)
        assertEquals(150, plan(VfMath.MODE_FPS, f = 30.0).count)
    }

    @Test
    fun countModeSpreadsEvenlyAndClampsToSourceFrames() {
        val p = plan(VfMath.MODE_COUNT, count = 3)
        assertEquals(3, p.count)
        assertEquals(0L, p.timeMs(0))
        assertEquals(1666L, p.timeMs(1))
        assertEquals(3333L, p.timeMs(2))
        assertNull(p.note)
        val big = plan(VfMath.MODE_COUNT, count = 999)
        assertEquals(150, big.count)
        assertNotNull(big.note)
        assertEquals(25, plan(VfMath.MODE_COUNT, count = 25).count)
    }

    @Test
    fun fpsAndIntervalModes() {
        assertEquals(25, plan(VfMath.MODE_FPS, f = 5.0).count)
        assertEquals(150, plan(VfMath.MODE_FPS, f = 120.0).count) // dibatasi ke FPS video
        assertEquals(5, plan(VfMath.MODE_INTERVAL, interval = 1000).count)
        assertEquals(10, plan(VfMath.MODE_INTERVAL, interval = 500).count)
        assertEquals(1000L, plan(VfMath.MODE_INTERVAL, interval = 1000).timeMs(1))
    }

    @Test
    fun rangeIsClampedToVideoAndTimesStayInside() {
        val p = plan(VfMath.MODE_ALL, start = 4000, span = 99_000)
        assertEquals(30, p.count)
        assertTrue((0 until p.count).all { p.timeMs(it) in 4000..4999 })
        val q = plan(VfMath.MODE_COUNT, start = 99_000, span = 1000, count = 3)
        assertTrue(q.count >= 1)
        assertTrue((0 until q.count).all { q.timeMs(it) in 0..4999 })
    }

    @Test
    fun framesAreCappedAtMax() {
        val p = VfMath.plan(VfMath.MODE_ALL, 0, 3_600_000, 3_600_000, 60.0, 1, 1.0, 1000, emptyList())
        assertEquals(VfMath.MAX_FRAMES, p.count)
        assertTrue(p.capped)
        assertEquals(216_000, p.rawCount)
    }

    @Test
    fun customTimesParseSortAndFilter() {
        val (times, bad) = VfMath.parseCustom("0, 1.5; 1:05 x 99999 3.2", 10_000)
        assertEquals(listOf(0L, 1500L, 65000L, 3200L).filter { it < 10_000 }, times)
        assertEquals(3, bad) // 1:05 (di luar durasi), x, 99999
        val p = plan(VfMath.MODE_CUSTOM, custom = listOf(3200L, 0L, 1500L, 1500L, 9_999_999L))
        assertEquals(3, p.count)
        assertEquals(listOf(0L, 1500L, 3200L), (0 until 3).map { p.timeMs(it) })
        assertEquals(65.0, VfMath.parseTime("1:05")!!, 1e-9)
        assertEquals(3723.5, VfMath.parseTime("1:02:03.5")!!, 1e-9)
        assertNull(VfMath.parseTime("abc"))
    }

    @Test
    fun outputSizeNeverUpscalesAndHonorsRotation() {
        assertEquals(1920 to 1080, VfMath.outputSize(1920, 1080, "asli", 0))
        assertEquals(1280 to 720, VfMath.outputSize(1920, 1080, "720", 0))
        assertEquals(720 to 1280, VfMath.outputSize(1080, 1920, "720", 0)) // potret: sisi pendek
        assertEquals(1920 to 1080, VfMath.outputSize(1920, 1080, "1080", 0))
        assertEquals(640 to 360, VfMath.outputSize(1920, 1080, "custom", 640))
        assertEquals(1920 to 1080, VfMath.outputSize(1920, 1080, "custom", 4000))
        assertEquals(1080 to 1920, VfMath.displaySize(1920, 1080, 90))
        assertEquals(1920 to 1080, VfMath.displaySize(1920, 1080, 180))
    }

    @Test
    fun byteEstimateScalesWithQualityAndFormat() {
        val png = VfMath.frameBytes(1920, 1080, "png", 90)
        val jpgHi = VfMath.frameBytes(1920, 1080, "jpg", 100)
        val jpgLo = VfMath.frameBytes(1920, 1080, "jpg", 60)
        assertTrue(png > jpgHi && jpgHi > jpgLo)
        assertEquals(4, VfMath.digits(150))
        assertEquals(5, VfMath.digits(12345))
    }
}
