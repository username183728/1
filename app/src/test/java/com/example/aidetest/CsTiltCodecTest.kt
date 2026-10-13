package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Test

class CsTiltCodecTest {
    @Test
    fun tiltAndDepthRoundTrip() {
        val el = CsElement(type = CsType.RECT, x = 10f, y = 20f, w = 300f, h = 200f, tiltX = 25f, tiltY = -12.5f, depth = 0.6f)
        val back = CsCodec.decodeElement(CsCodec.encodeElement(el), CsCodec.VERSION, { it })
        assertEquals(25f, back.tiltX, 0.001f)
        assertEquals(-12.5f, back.tiltY, 0.001f)
        assertEquals(0.6f, back.depth, 0.001f)
    }

    @Test
    fun oldFilesLoadWithoutTilt() {
        val el = CsElement(type = CsType.RECT, x = 0f, y = 0f, w = 50f, h = 50f)
        val map = CsCodec.encodeElement(el).toMutableMap().apply { remove("tiltX"); remove("tiltY"); remove("depth") }
        val back = CsCodec.decodeElement(map, 2, { it })
        assertEquals(0f, back.tiltX, 0f)
        assertEquals(0f, back.depth, 0f)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val el = CsElement(type = CsType.RECT, x = 0f, y = 0f, w = 50f, h = 50f, tiltX = 200f, depth = 3f)
        val back = CsCodec.decodeElement(CsCodec.encodeElement(el), CsCodec.VERSION, { it })
        assertEquals(Pe3D.MAX_TILT, back.tiltX, 0.001f)
        assertEquals(1f, back.depth, 0.001f)
    }
}
