package com.example.aidetest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDspTest {
    private fun ramp(n: Int, v: Int = 1000) = ShortArray(n) { v.toShort() }

    @Test
    fun deleteRangeJoinsSides() {
        val pcm = ShortArray(10) { it.toShort() }
        val out = AudioDsp.deleteRange(pcm, 3, 6)
        assertArrayEquals(shortArrayOf(0, 1, 2, 6, 7, 8, 9), out)
        assertEquals(10, pcm.size) // masukan tidak berubah
    }

    @Test
    fun sliceAndSilenceDoNotMutateInput() {
        val pcm = ramp(8)
        val silent = AudioDsp.silenceRange(pcm, 2, 5)
        assertNotSame(pcm, silent)
        assertEquals(1000, pcm[3].toInt())
        assertEquals(0, silent[3].toInt())
        assertEquals(1000, silent[5].toInt())
        assertEquals(3, AudioDsp.slice(pcm, 5, 100).size)
    }

    @Test
    fun fadeInStartsSilentAndEndsNearFull() {
        val out = AudioDsp.fadeRange(ramp(1000, 10000), 0, 1000, fadeIn = true)
        assertEquals(0, out[0].toInt())
        assertTrue(out[999] > 9900)
        assertTrue(out[250] < out[500] && out[500] < out[750])
        val fo = AudioDsp.fadeRange(ramp(1000, 10000), 0, 1000, fadeIn = false)
        assertEquals(10000, fo[0].toInt())
        assertTrue(fo[999] < 100)
    }

    @Test
    fun gainClipsToRange() {
        val out = AudioDsp.gainRange(shortArrayOf(20000, -20000, 100), 0, 2, 12f)
        assertEquals(Short.MAX_VALUE, out[0])
        assertEquals(Short.MIN_VALUE, out[1])
        assertEquals(100, out[2].toInt())
    }

    @Test
    fun resampleChangesLength() {
        val pcm = ramp(44100)
        assertEquals(48000, AudioDsp.resample(pcm, 44100, 48000).size)
        assertEquals(22050, AudioDsp.resample(pcm, 44100, 22050).size)
        assertTrue(AudioDsp.resample(pcm, 44100, 44100) === pcm)
    }

    @Test
    fun mixAtSumsAndExtends() {
        val out = AudioDsp.mixAt(ramp(4, 100), ramp(4, 50), 2)
        assertArrayEquals(shortArrayOf(100, 100, 150, 150, 50, 50), out)
    }

    @Test
    fun concatAndPeaks() {
        val joined = AudioDsp.concat(listOf(shortArrayOf(1, -5), shortArrayOf(3)))
        assertArrayEquals(shortArrayOf(1, -5, 3), joined)
        assertArrayEquals(shortArrayOf(5, 3), AudioDsp.peaks(joined, 2))
    }

    @Test
    fun levelsAndFormatting() {
        assertEquals(-96f, AudioDsp.peakDb(ShortArray(10)), 0.01f)
        assertEquals(0f, AudioDsp.peakDb(shortArrayOf(Short.MIN_VALUE)), 0.01f)
        assertEquals("1:05.2", AudioDsp.fmtTime(65_250))
        assertEquals("1:01:01", AudioDsp.fmtTime(3_661_000))
        assertEquals(44100, AudioDsp.aacSampleRate(44100))
        assertEquals(48000, AudioDsp.aacSampleRate(96000))
    }
}
