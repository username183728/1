package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

class HashToolsTest {
    private val sha256Abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val md5Abc = "900150983cd24fb0d6963f7d28e17f72"

    @Test fun hashesKnownVector() {
        val r = hashStream(ByteArrayInputStream("abc".toByteArray()), listOf("SHA-256", "MD5"), 3L, AtomicBoolean(false)) { _, _ -> }
        assertEquals(sha256Abc, r.hex["SHA-256"])
        assertEquals(md5Abc, r.hex["MD5"])
        assertEquals(3L, r.bytes)
    }

    @Test(expected = HashCancelled::class) fun cancelStopsHashing() {
        hashStream(ByteArrayInputStream(ByteArray(10)), listOf("SHA-256"), 10L, AtomicBoolean(true)) { _, _ -> }
    }

    @Test fun normalizeAcceptsCommonFormats() {
        assertEquals(sha256Abc, hashNormalize("SHA256:" + sha256Abc.uppercase()))
        assertEquals(sha256Abc, hashNormalize("  $sha256Abc  app-release.apk"))
        assertEquals(md5Abc, hashNormalize(md5Abc.chunked(2).joinToString(":")))
        assertNull(hashNormalize("bukan hash"))
        assertNull(hashNormalize("abcdef"))
    }

    @Test fun verdictMatchAndMismatch() {
        val computed = mapOf("SHA-256" to sha256Abc, "MD5" to md5Abc)
        assertEquals(HashMatch.MATCH, hashVerdict(sha256Abc.uppercase(), computed).match)
        assertEquals(HashMatch.MISMATCH, hashVerdict("0".repeat(64), computed).match)
        assertEquals(HashMatch.UNKNOWN_FORMAT, hashVerdict("xyz", computed).match)
        assertEquals(HashMatch.NOT_COMPUTED, hashVerdict("0".repeat(128), computed).match)
    }
}
