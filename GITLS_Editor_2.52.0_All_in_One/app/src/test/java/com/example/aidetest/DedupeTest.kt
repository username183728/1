package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Test

class DedupeTest {
    private val sample = "GitHub GitLis Android GitHub APK Builder GitLis"

    @Test fun lineModeKeepsLegacyBehaviour() {
        val r = dedupeItems("a\nb\na\n\n b ", DedupeMode.LINE)
        assertEquals(listOf("a", "b"), r.after)
        assertEquals("a\nb", r.text())
    }

    @Test fun lineModeTreatsOneLineAsOneItem() {
        val r = dedupeItems(sample, DedupeMode.LINE)
        assertEquals(1, r.after.size)
    }

    @Test fun wordModeRemovesDuplicateWords() {
        val r = dedupeItems(sample, DedupeMode.WORD)
        assertEquals("GitHub GitLis Android APK Builder", r.text())
        assertEquals(7, r.before.size)
        assertEquals(2, r.removed)
    }

    @Test fun wordModePunctuationCountsUnlessIgnored() {
        assertEquals(2, dedupeItems("GitLis, GitLis", DedupeMode.WORD).after.size)
        assertEquals(1, dedupeItems("GitLis, GitLis", DedupeMode.WORD, ignorePunct = true).after.size)
    }

    @Test fun ignoreCaseWorks() {
        assertEquals(1, dedupeItems("Git git GIT", DedupeMode.WORD, ignoreCase = true).after.size)
        assertEquals(3, dedupeItems("Git git GIT", DedupeMode.WORD).after.size)
    }

    @Test fun customDelimiterKeepsApkBuilderAsOneItem() {
        val r = dedupeItems("GitHub, GitLis, GitHub, APK Builder, GitLis", DedupeMode.CUSTOM, ",")
        assertEquals(listOf("GitHub", "GitLis", "APK Builder"), r.after)
        assertEquals("GitHub, GitLis, APK Builder", r.text())
    }

    @Test fun unescapeNewlineDelimiter() {
        assertEquals("\n", dedupeUnescape("\\n"))
    }
}
