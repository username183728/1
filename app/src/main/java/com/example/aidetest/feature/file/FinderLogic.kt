package com.example.aidetest

import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.floor

/*
 * Logika murni (tanpa Android) untuk APK Compare, Duplicate Finder, dan Large File Finder.
 * Dipisah dari tampilan supaya bisa diuji dengan unit test biasa.
 */

internal const val FINDER_MAX_MB = 102_400.0
internal const val FINDER_MAX_KB = 10_485_760.0
internal const val FINDER_MAX_FILES = 200_000

/** Hasil validasi input ukuran. [error] != null berarti input tidak valid. */
internal class FinderInput(val bytes: Long, val label: String, val error: String?)

private fun formatFinderNumber(v: Double): String =
    if (v == floor(v)) v.toLong().toString()
    else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

private fun parseFinderNumber(raw: String): Double? {
    val v = raw.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return if (v.isNaN() || v.isInfinite()) null else v
}

/** Ukuran minimum dalam MB: wajib diisi, angka, lebih dari 0, dan tidak lebih dari 100 GB. */
internal fun parseMinSizeMb(raw: String): FinderInput {
    if (raw.isBlank()) return FinderInput(0L, "", "Isi ukuran minimum dalam MB, contoh: 50.")
    val v = parseFinderNumber(raw)
        ?: return FinderInput(0L, "", "\"${raw.trim()}\" bukan angka. Isi angka dalam MB, contoh: 50.")
    if (v <= 0.0) return FinderInput(0L, "", "Ukuran minimum harus lebih dari 0 MB.")
    if (v > FINDER_MAX_MB) return FinderInput(0L, "", "Terlalu besar. Batas maksimum 102.400 MB (100 GB).")
    return FinderInput((v * 1024.0 * 1024.0).toLong().coerceAtLeast(1L), formatFinderNumber(v), null)
}

/** Ukuran minimum dalam KB: boleh kosong (dipakai 1 KB), selain itu angka lebih dari 0. */
internal fun parseMinSizeKb(raw: String): FinderInput {
    if (raw.isBlank()) return FinderInput(1024L, "1", null)
    val v = parseFinderNumber(raw)
        ?: return FinderInput(0L, "", "\"${raw.trim()}\" bukan angka. Isi angka dalam KB, contoh: 100.")
    if (v <= 0.0) return FinderInput(0L, "", "Ukuran minimum harus lebih dari 0 KB. Kosongkan untuk memakai 1 KB.")
    if (v > FINDER_MAX_KB) return FinderInput(0L, "", "Terlalu besar. Batas maksimum 10.485.760 KB (10 GB).")
    return FinderInput((v * 1024.0).toLong().coerceAtLeast(1L), formatFinderNumber(v), null)
}

/** Statistik penelusuran folder, termasuk folder yang tidak bisa dibaca. */
internal class WalkStats {
    var dirs = 0
    var files = 0
    var truncated = false
    val unreadable = ArrayList<String>()
}

/**
 * Menelusuri [root] beserta subfolder. Folder yang tidak bisa dibuka dicatat di [WalkStats.unreadable]
 * (bukan dianggap kosong), dan symlink folder dilewati agar tidak berputar.
 */
internal fun walkFiles(root: File, stats: WalkStats, maxFiles: Int = FINDER_MAX_FILES, onFile: (File) -> Unit) {
    val stack = ArrayList<File>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val dir = stack.removeAt(stack.size - 1)
        val children: Array<File>? = try { dir.listFiles() } catch (_: SecurityException) { null }
        if (children == null) {
            stats.unreadable.add(dir.absolutePath)
            continue
        }
        stats.dirs++
        val canon = try { dir.canonicalFile } catch (_: IOException) { dir }
        for (c in children) {
            if (stats.files >= maxFiles) {
                stats.truncated = true
                return
            }
            if (c.isDirectory) {
                val realParent = try { c.canonicalFile.parentFile } catch (_: IOException) { null }
                if (realParent == canon) stack.add(c)
            } else if (c.isFile) {
                stats.files++
                onFile(c)
            }
        }
    }
}

/** Status akhir pemindaian: membedakan selesai, tidak ada hasil, izin ditolak, dan gagal. */
internal fun finderStatus(error: Throwable?, stats: WalkStats, foundAny: Boolean, emptyLabel: String = "Tidak ada hasil"): String = when {
    error is SecurityException || (error == null && stats.files == 0 && stats.unreadable.isNotEmpty()) -> "Izin ditolak"
    error != null -> "Gagal memindai"
    !foundAny -> emptyLabel
    else -> "Selesai"
}

internal fun unreadableNote(stats: WalkStats): String? =
    if (stats.unreadable.isEmpty()) null
    else "${stats.unreadable.size} folder tidak bisa dibaca (ditolak oleh Android), misalnya:\n" +
        stats.unreadable.take(3).joinToString("\n") { "• $it" }

// ─────────────────────────── Duplicate Finder ───────────────────────────

internal class DupGroup(val size: Long, val sha256: String, val files: List<File>) {
    val savings: Long get() = size * (files.size - 1).toLong()
}

internal class DupScanResult(val groups: List<DupGroup>, val candidateFiles: Int, val hashFailed: Int)

/**
 * Langkah 1: kelompokkan menurut ukuran. Langkah 2: hash hanya kandidat yang ukurannya sama.
 * Hanya file dengan ukuran DAN hash sama yang dianggap duplikat.
 */
internal fun findDuplicateGroups(
    entries: List<Pair<File, Long>>,
    hash: (File) -> String?,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): DupScanResult {
    val sizeGroups = entries.groupBy({ it.second }, { it.first }).filterValues { it.size > 1 }
    var total = 0
    for (g in sizeGroups.values) total += g.size
    var done = 0
    var failed = 0
    val result = ArrayList<DupGroup>()
    for ((size, files) in sizeGroups) {
        val byHash = LinkedHashMap<String, MutableList<File>>()
        for (f in files) {
            val h = hash(f)
            done++
            if (h == null) failed++ else byHash.getOrPut(h) { ArrayList() }.add(f)
            onProgress(done, total)
        }
        for ((h, same) in byHash) {
            if (same.size > 1) result.add(DupGroup(size, h, same))
        }
    }
    return DupScanResult(result.sortedByDescending { it.savings }, total, failed)
}

/** File yang dipertahankan secara bawaan: yang paling lama (biasanya aslinya). Pengguna bisa menggantinya. */
internal fun defaultKeepIndex(files: List<File>): Int {
    var best = 0
    for (i in 1 until files.size) {
        val a = files[best]
        val b = files[i]
        if (b.lastModified() < a.lastModified() ||
            (b.lastModified() == a.lastModified() && b.absolutePath.length < a.absolutePath.length)
        ) best = i
    }
    return best
}

// ─────────────────────────── APK Compare ───────────────────────────

internal class ApkSnapshot(
    val fileName: String,
    val size: Long,
    val sha256: String,
    val appLabel: String? = null,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    /** null = tidak terbaca. */
    val permissions: List<String>? = null,
    /** SHA-256 sertifikat penandatangan; null = tidak terbaca. */
    val signers: List<String>? = null,
    /** nama entri -> (CRC, ukuran asli); null = ZIP tidak terbaca. */
    val entries: Map<String, Pair<Long, Long>>? = null,
    val problems: List<String> = emptyList()
)

internal class ApkCompareSection(val title: String, val body: String, val differs: Boolean)

internal class ApkCompareReport(
    val headline: String,
    val identical: Boolean,
    val sections: List<ApkCompareSection>,
    val notComparable: List<String>
)

internal class ZipDiff(val added: List<String>, val removed: List<String>, val changed: List<String>, val same: Int) {
    val total: Int get() = added.size + removed.size + changed.size
    val identical: Boolean get() = total == 0
}

/** Berkas tanda tangan klasik di META-INF. Tanda tangan v2+ ada di luar daftar entri ZIP. */
internal fun isSignatureEntry(name: String): Boolean {
    val n = name.uppercase(Locale.ROOT)
    if (!n.startsWith("META-INF/")) return false
    val rest = n.removePrefix("META-INF/")
    if (rest.contains('/')) return false
    return rest == "MANIFEST.MF" || rest.endsWith(".SF") || rest.endsWith(".RSA") || rest.endsWith(".DSA") || rest.endsWith(".EC")
}

/** Membandingkan isi dua APK per entri (CRC + ukuran), tanpa berkas tanda tangan. */
internal fun diffZipEntries(a: Map<String, Pair<Long, Long>>, b: Map<String, Pair<Long, Long>>): ZipDiff {
    val added = ArrayList<String>()
    val removed = ArrayList<String>()
    val changed = ArrayList<String>()
    var same = 0
    for ((name, va) in a) {
        if (isSignatureEntry(name)) continue
        val vb = b[name]
        if (vb == null) removed.add(name) else if (vb != va) changed.add(name) else same++
    }
    for (name in b.keys) {
        if (isSignatureEntry(name)) continue
        if (!a.containsKey(name)) added.add(name)
    }
    return ZipDiff(added.sorted(), removed.sorted(), changed.sorted(), same)
}

internal fun signatureEntriesDiffer(a: Map<String, Pair<Long, Long>>, b: Map<String, Pair<Long, Long>>): Boolean =
    a.filterKeys { isSignatureEntry(it) } != b.filterKeys { isSignatureEntry(it) }

internal fun apkEntryCategory(name: String): String = when {
    name.startsWith("classes") && name.endsWith(".dex") && !name.contains('/') -> "Kode (DEX)"
    name == "AndroidManifest.xml" || name == "resources.arsc" || name.startsWith("res/") -> "Manifest & resource"
    name.startsWith("lib/") -> "Library native"
    name.startsWith("assets/") -> "Aset"
    name.startsWith("META-INF/") -> "META-INF"
    else -> "Lainnya"
}

private fun apkCategoryCounts(names: List<String>): String =
    names.groupingBy { apkEntryCategory(it) }.eachCount().entries
        .sortedByDescending { it.value }
        .joinToString(", ") { "${it.key} ${it.value}" }

private fun whoMissing(aMissing: Boolean, bMissing: Boolean): String = when {
    aMissing && bMissing -> "tidak terbaca di APK A dan APK B"
    aMissing -> "tidak terbaca di APK A"
    else -> "tidak terbaca di APK B"
}

private fun shortPerm(p: String) = p.removePrefix("android.permission.")

private fun shortFp(s: String) = if (s.length > 32) s.take(32) + "…" else s

internal fun buildApkCompareReport(a: ApkSnapshot, b: ApkSnapshot): ApkCompareReport {
    val sections = ArrayList<ApkCompareSection>()
    val missing = ArrayList<String>()
    val sameHash = a.sha256.equals(b.sha256, ignoreCase = true)

    sections.add(
        ApkCompareSection(
            "File",
            "Ukuran A: ${a.size} byte\nUkuran B: ${b.size} byte\nSHA-256: ${if (sameHash) "sama" else "berbeda"}\n\n" +
                (if (sameHash) "Kedua file identik byte per byte."
                else "Hash berbeda hanya berarti byte-nya tidak sama. Itu belum tentu berarti fitur aplikasi berubah — lihat bagian \"Isi aplikasi\"."),
            !sameHash
        )
    )

    // Nama paket
    val pa = a.packageName
    val pb = b.packageName
    val samePkg = pa != null && pb != null && pa == pb
    val diffPkg = pa != null && pb != null && pa != pb
    if (pa != null && pb != null) {
        sections.add(
            if (samePkg) ApkCompareSection("Nama paket", "Sama: $pa", false)
            else ApkCompareSection(
                "Nama paket",
                "BERBEDA\nA: $pa\nB: $pb\n\nAndroid menganggap keduanya aplikasi yang berbeda (atau varian dengan akhiran berbeda, misalnya debug dan rilis). " +
                    "Keduanya bisa terpasang bersamaan dan bukan pembaruan satu sama lain. Perbandingan lain di bawah adalah antar dua aplikasi, bukan perubahan pada satu aplikasi.",
                true
            )
        )
    } else missing.add("Nama paket — ${whoMissing(pa == null, pb == null)}")

    // Versi
    val va = a.versionCode
    val vb = b.versionCode
    var versionDiffers: Boolean? = null
    if (va != null && vb != null) {
        val sameVer = va == vb && a.versionName == b.versionName
        versionDiffers = !sameVer
        val body = StringBuilder()
        if (sameVer) {
            body.append("Sama: ${a.versionName ?: "-"} (kode $va)")
        } else {
            body.append("BERBEDA\nA: ${a.versionName ?: "-"} (kode $va)\nB: ${b.versionName ?: "-"} (kode $vb)")
            if (samePkg) {
                body.append(
                    when {
                        va < vb -> "\n\nB lebih baru daripada A."
                        va > vb -> "\n\nB lebih lama daripada A (downgrade)."
                        else -> "\n\nKode versi sama; hanya nama versi yang berbeda."
                    }
                )
            } else if (diffPkg) {
                body.append("\n\nKarena nama paket berbeda, urutan versi tidak bisa dibandingkan.")
            }
        }
        sections.add(ApkCompareSection("Versi", body.toString(), !sameVer))
    } else missing.add("Versi — ${whoMissing(va == null, vb == null)}")

    // SDK
    val minA = a.minSdk
    val minB = b.minSdk
    val tgtA = a.targetSdk
    val tgtB = b.targetSdk
    if (minA != null && minB != null && tgtA != null && tgtB != null) {
        val same = minA == minB && tgtA == tgtB
        sections.add(
            ApkCompareSection(
                "Versi Android (SDK)",
                if (same) "Sama: minimum API $minA, target API $tgtA"
                else "BERBEDA\nA: minimum API $minA, target API $tgtA\nB: minimum API $minB, target API $tgtB",
                !same
            )
        )
    } else missing.add("Versi Android minimum/target — ${whoMissing(minA == null || tgtA == null, minB == null || tgtB == null)}")

    // Izin
    val permA = a.permissions
    val permB = b.permissions
    if (permA != null && permB != null) {
        val setA = permA.toSet()
        val setB = permB.toSet()
        val onlyA = (setA - setB).map { shortPerm(it) }.sorted()
        val onlyB = (setB - setA).map { shortPerm(it) }.sorted()
        if (onlyA.isEmpty() && onlyB.isEmpty()) {
            sections.add(ApkCompareSection("Izin", "Sama (${setA.size} izin).", false))
        } else {
            val body = StringBuilder("BERBEDA\nJumlah izin: A ${setA.size}, B ${setB.size}")
            if (onlyB.isNotEmpty()) body.append("\n\nHanya di B (${onlyB.size}):\n").append(onlyB.joinToString("\n") { "• $it" })
            if (onlyA.isNotEmpty()) body.append("\n\nHanya di A (${onlyA.size}):\n").append(onlyA.joinToString("\n") { "• $it" })
            sections.add(ApkCompareSection("Izin", body.toString(), true))
        }
    } else missing.add("Izin — ${whoMissing(permA == null, permB == null)}")

    // Tanda tangan / sertifikat
    val sigA = a.signers
    val sigB = b.signers
    var signersDiffer: Boolean? = null
    if (sigA != null && sigB != null) {
        val same = sigA.toSet() == sigB.toSet()
        signersDiffer = !same
        sections.add(
            if (same) ApkCompareSection("Tanda tangan", "Sama: kedua APK ditandatangani sertifikat yang sama.\nSHA-256: ${sigA.joinToString(", ") { shortFp(it) }}", false)
            else ApkCompareSection(
                "Tanda tangan",
                "BERBEDA\nA: ${sigA.joinToString(", ") { shortFp(it) }}\nB: ${sigB.joinToString(", ") { shortFp(it) }}\n\n" +
                    (if (samePkg) "Dengan nama paket yang sama, Android menolak memperbarui satu APK dengan yang lain bila tanda tangannya berbeda. Aplikasi lama harus dicopot dulu (data aplikasi ikut hilang)."
                    else "Tanda tangan berbeda berarti kunci yang dipakai berbeda, misalnya kunci debug dan kunci rilis."),
                true
            )
        )
    } else missing.add("Tanda tangan — ${whoMissing(sigA == null, sigB == null)}")

    // Isi aplikasi
    val entA = a.entries
    val entB = b.entries
    var contentDiff: ZipDiff? = null
    if (entA != null && entB != null) {
        val d = diffZipEntries(entA, entB)
        contentDiff = d
        if (d.identical) {
            val body = StringBuilder("IDENTIK: ${d.same} file (kode, resource, aset, library) sama persis.")
            if (!sameHash) {
                body.append("\n\nHash tetap berbeda karena ")
                body.append(
                    when {
                        signersDiffer == true -> "tanda tangan berbeda (kemungkinan APK ditandatangani ulang)."
                        signatureEntriesDiffer(entA, entB) -> "berkas tanda tangan di META-INF berbeda."
                        else -> "pengemasan ZIP (urutan atau kompresi file, zip-align) atau blok tanda tangan APK berbeda."
                    }
                )
                body.append("\nIni bukan perubahan fitur aplikasi.")
            }
            sections.add(ApkCompareSection("Isi aplikasi", body.toString(), false))
        } else {
            val all = d.changed + d.added + d.removed
            val body = StringBuilder("BERBEDA\nSama persis: ${d.same} file\nDiubah: ${d.changed.size}\nHanya di A: ${d.removed.size}\nHanya di B: ${d.added.size}")
            body.append("\n\nPer kategori: ${apkCategoryCounts(all)}")
            body.append("\n\nContoh:\n").append(all.take(8).joinToString("\n") { "• $it" })
            if (all.size > 8) body.append("\n… dan ${all.size - 8} lainnya")
            sections.add(ApkCompareSection("Isi aplikasi", body.toString(), true))
        }
    } else missing.add("Isi file di dalam APK — ${whoMissing(entA == null, entB == null)}")

    a.problems.forEach { missing.add("APK A: $it") }
    b.problems.forEach { missing.add("APK B: $it") }

    val d = contentDiff
    val headline = when {
        sameHash -> "Identik — kedua file sama persis (SHA-256 sama)."
        diffPkg -> "Dua aplikasi berbeda — nama paketnya tidak sama."
        d == null -> "Berbeda (hash tidak sama), tetapi penyebabnya tidak bisa dipastikan karena isi salah satu APK tidak terbaca."
        d.identical ->
            if (signersDiffer == true) "Hash berbeda, isi aplikasi sama — hanya tanda tangan yang berbeda. Bukan perubahan fitur."
            else "Hash berbeda, tetapi isi aplikasi sama — kemungkinan hanya beda pengemasan atau tanda tangan. Bukan perubahan fitur."
        versionDiffers == true -> "Berbeda — versi aplikasi berbeda dan ${d.total} file berubah."
        else -> "Berbeda — versi sama, tetapi ${d.total} file di dalam APK berubah (kemungkinan build ulang atau perubahan kode)."
    }
    return ApkCompareReport(headline, sameHash, sections, missing)
}
