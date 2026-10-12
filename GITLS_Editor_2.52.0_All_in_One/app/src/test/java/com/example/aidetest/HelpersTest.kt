package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpersTest {
    @Test fun sensitivePathsAreSkipped() {
        assertTrue(ghIsSensitivePath(".env"))
        assertTrue(ghIsSensitivePath("app/.env.production"))
        assertTrue(ghIsSensitivePath("local.properties"))
        assertTrue(ghIsSensitivePath("keys/release.keystore"))
        assertTrue(ghIsSensitivePath("web/node_modules/x/index.js"))
        assertFalse(ghIsSensitivePath("app/src/main/Main.kt"))
        assertFalse(ghIsSensitivePath("README.md"))
    }

    @Test fun executableModeForScripts() {
        assertEquals("100755", ghFileMode("gradlew"))
        assertEquals("100755", ghFileMode("scripts/ci/preflight.sh"))
        assertEquals("100644", ghFileMode("app/build.gradle"))
    }

    @Test fun base32RoundTrip() {
        val data = "GITLS-2026".toByteArray()
        assertEquals(String(data), String(Base32.decode(Base32.encode(data))))
    }
}
