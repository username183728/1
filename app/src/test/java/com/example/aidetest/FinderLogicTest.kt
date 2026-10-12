package com.example.aidetest

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FinderLogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ── Validasi input ──

    @Test fun mbValidInput() {
        val r = parseMinSizeMb("50")
        assertNull(r.error)
        assertEquals(50L * 1024 * 1024, r.bytes)
        assertEquals("50", r.label)
    }

    @Test fun mbAcceptsDecimalWithComma() {
        val r = parseMinSizeMb("1,5")
        assertNull(r.error)
        assertEquals((1.5 * 1024 * 1024).toLong(), r.bytes)
        assertEquals("1.5", r.label)
    }

    @Test fun mbRejectsEmptyTextZeroNegativeAndHuge() {
        assertNotNull(parseMinSizeMb("").error)
        assertNotNull(parseMinSizeMb("abc").error)
        assertNotNull(parseMinSizeMb("0").error)
        assertNotNull(parseMinSizeMb("-5").error)
        assertNotNull(parseMinSizeMb("200000").error)
    }

    @Test fun kbEmptyUsesDefaultAndInvalidIsRejected() {
        val d = parseMinSizeKb("")
        assertNull(d.error)
        assertEquals(1024L, d.bytes)
        assertNotNull(parseMinSizeKb("0").error)
        assertNotNull(parseMinSizeKb("x").error)
        assertEquals(100L * 1024, parseMinSizeKb("100").bytes)
    }

    // ── Penelusuran & status ──

    @Test fun walkCountsFilesAndFolders() {
        val sub = tmp.newFolder("sub")
        tmp.newFile("a.bin").writeText("1")
        File(sub, "b.bin").writeText("22")
        val stats = WalkStats()
        val seen = ArrayList<String>()
        walkFiles(tmp.root, stats) { seen.add(it.name) }
        assertEquals(2, stats.files)
        assertEquals(2, stats.dirs)
        assertTrue(stats.unreadable.isEmpty())
        assertEquals(listOf("a.bin", "b.bin"), seen.sorted())
    }

    @Test fun unreadableFolderIsReportedNotSilentlyEmpty() {
        val stats = WalkStats()
        walkFiles(File(tmp.root, "tidak-ada"), stats) { }
        assertEquals(1, stats.unreadable.size)
        assertEquals("Izin ditolak", finderStatus(null, stats, false))
    }

    @Test fun statusDistinguishesFourOutcomes() {
        val empty = WalkStats().apply { dirs = 1; files = 5 }
        assertEquals("Selesai", finderStatus(null, empty, true))
        assertEquals("Tidak ada hasil", finderStatus(null, empty, false))
        assertEquals("Tidak ada duplikat", finderStatus(null, empty, false, "Tidak ada duplikat"))
        assertEquals("Izin ditolak", finderStatus(SecurityException("x"), empty, false))
        assertEquals("Gagal memindai", finderStatus(IOException("x"), empty, false))
    }

    // ── Duplicate Finder ──

    @Test fun duplicatesNeedSameSizeAndSameContent() {
        val a = tmp.newFile("a.txt").apply { writeText("hello") }
        val b = tmp.newFile("b.txt").apply { writeText("hello") }
        val c = tmp.newFile("c.txt").apply { writeText("world") } // ukuran sama, isi beda
        val d = tmp.newFile("d.txt").apply { writeText("hi") }
        val entries = listOf(a, b, c, d).map { it to it.length() }
        val res = findDuplicateGroups(entries, hash = { it.readText() })
        assertEquals(1, res.groups.size)
        val g = res.groups[0]
        assertEquals(setOf(a, b), g.files.toSet())
        assertEquals(5L, g.size)
        assertEquals(5L, g.savings)
        assertEquals(3, res.candidateFiles)
        assertEquals(0, res.hashFailed)
    }

    @Test fun hashFailureIsCountedAndSkipped() {
        val a = tmp.newFile("a.txt").apply { writeText("hello") }
        val b = tmp.newFile("b.txt").apply { writeText("hello") }
        val entries = listOf(a, b).map { it to it.length() }
        val res = findDuplicateGroups(entries, hash = { f -> if (f.name == "b.txt") null else f.readText() })
        assertTrue(res.groups.isEmpty())
        assertEquals(1, res.hashFailed)
    }

    @Test fun defaultKeepIsTheOldestFile() {
        val a = tmp.newFile("a.txt").apply { setLastModified(2_000_000L) }
        val b = tmp.newFile("b.txt").apply { setLastModified(1_000_000L) }
        assertEquals(1, defaultKeepIndex(listOf(a, b)))
    }

    // ── APK Compare ──

    @Test fun zipDiffIgnoresSignatureEntries() {
        val a = mapOf("classes.dex" to (1L to 10L), "META-INF/CERT.RSA" to (5L to 3L), "res/a.png" to (2L to 4L))
        val b = mapOf("classes.dex" to (1L to 10L), "META-INF/OTHER.RSA" to (9L to 3L), "res/a.png" to (2L to 4L))
        val d = diffZipEntries(a, b)
        assertTrue(d.identical)
        assertEquals(2, d.same)
        assertTrue(signatureEntriesDiffer(a, b))
    }

    @Test fun zipDiffFindsChangedAddedRemoved() {
        val a = mapOf("classes.dex" to (1L to 10L), "res/a.png" to (2L to 4L))
        val c = mapOf("classes.dex" to (99L to 10L), "new.txt" to (1L to 1L))
        val d = diffZipEntries(a, c)
        assertEquals(listOf("classes.dex"), d.changed)
        assertEquals(listOf("new.txt"), d.added)
        assertEquals(listOf("res/a.png"), d.removed)
        assertEquals(3, d.total)
        assertEquals(0, d.same)
    }

    private fun snap(
        sha: String,
        pkg: String? = "com.x",
        signers: List<String>? = listOf("AA"),
        entries: Map<String, Pair<Long, Long>>? = mapOf("classes.dex" to (1L to 1L))
    ) = ApkSnapshot(
        fileName = "x.apk",
        size = 10L,
        sha256 = sha,
        packageName = pkg,
        versionName = "1.0",
        versionCode = 1L,
        minSdk = 23,
        targetSdk = 34,
        permissions = listOf("android.permission.INTERNET"),
        signers = signers,
        entries = entries
    )

    @Test fun sameHashIsIdentical() {
        val r = buildApkCompareReport(snap("h"), snap("h"))
        assertTrue(r.identical)
        assertTrue(r.headline.startsWith("Identik"))
    }

    @Test fun differentHashSameContentIsNotAFeatureChange() {
        val r = buildApkCompareReport(snap("h1", signers = listOf("AA")), snap("h2", signers = listOf("BB")))
        assertFalse(r.identical)
        assertTrue(r.headline.contains("Bukan perubahan fitur"))
        assertTrue(r.headline.contains("tanda tangan"))
    }

    @Test fun differentPackageIsExplained() {
        val r = buildApkCompareReport(snap("h1"), snap("h2", pkg = "com.y"))
        assertTrue(r.headline.startsWith("Dua aplikasi berbeda"))
        assertTrue(r.sections.any { it.title == "Nama paket" && it.differs })
    }

    @Test fun unreadableMetadataIsListedAsNotComparable() {
        val r = buildApkCompareReport(snap("h1"), snap("h2", pkg = null, signers = null))
        assertTrue(r.notComparable.any { it.startsWith("Nama paket") })
        assertTrue(r.notComparable.any { it.startsWith("Tanda tangan") })
    }
}
