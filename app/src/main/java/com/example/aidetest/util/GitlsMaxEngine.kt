package com.example.aidetest

import android.content.Context
import android.net.Uri
import java.io.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared engine for GITLS advanced tools. UI layers can reuse this without duplicating logic. */
object GitlsMaxEngine {
    /** Hard safety limits for file operations exposed by the advanced tools.
     * Prevents accidental multi-GB reads/writes from freezing the app or exhausting storage.
     */
    const val MAX_SINGLE_FILE_BYTES: Long = 256L * 1024L * 1024L
    const val MAX_ARCHIVE_BYTES: Long = 512L * 1024L * 1024L
    data class Progress(val done: Long, val total: Long, val current: String = "") {
        val percent: Int get() = if (total <= 0L) 0 else ((done * 100L) / total).toInt().coerceIn(0, 100)
    }
    data class Failure(val path: String, val message: String)
    data class BatchResult(val completed: Int, val failed: List<Failure>, val cancelled: Boolean)
    data class ArchiveEntryInfo(val name: String, val directory: Boolean, val size: Long, val compressed: Long)
    data class FileStat(val file: File, val size: Long, val modified: Long, val sha256: String? = null)

    fun copyOrMoveBatch(
        sources: List<File>, destination: File, move: Boolean, cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Progress) -> Unit = { }
    ): BatchResult {
        destination.mkdirs()
        val work = sources.flatMap { if (it.isDirectory) it.walkTopDown().toList() else listOf(it) }
        val total = work.count { it.isFile }.coerceAtLeast(1).toLong()
        var done = 0L
        var completed = 0
        val failures = mutableListOf<Failure>()
        for (source in sources) {
            if (cancel.get()) break
            val target = File(destination, source.name)
            try {
                val sourceCanonical = source.canonicalFile
                val targetCanonical = target.canonicalFile
                if (sourceCanonical == targetCanonical || (source.isDirectory && destination.canonicalFile.path.startsWith(sourceCanonical.path + File.separator))) {
                    throw IOException("Source dan destination tidak boleh sama/berada di dalam source")
                }
                val backup = if (target.exists()) File(target.parentFile, ".${target.name}.gitls-backup-${UUID.randomUUID()}") else null
                if (backup != null && !target.renameTo(backup)) throw IOException("Tidak bisa mengamankan target lama")
                try {
                    if (source.isDirectory) {
                        copyTree(source, target, cancel) { name -> done++; onProgress(Progress(done, total, name)) }
                    } else {
                        copyFile(source, target, cancel) { _ -> onProgress(Progress(done, total, source.name)) }
                        done++; onProgress(Progress(done, total, source.name))
                    }
                    if (move) {
                        if (!source.deleteRecursively()) throw IOException("Copied but could not delete source ${source.absolutePath}")
                    }
                    if (backup != null) backup.deleteRecursively()
                    completed++
                } catch (e: Exception) {
                    runCatching { if (target.exists()) target.deleteRecursively() }
                    if (backup != null && backup.exists()) runCatching { backup.renameTo(target) }
                    throw e
                }
            } catch (e: Exception) {
                failures += Failure(source.absolutePath, e.message ?: "Unknown error")
            }
        }
        return BatchResult(completed, failures, cancel.get())
    }

    private fun copyTree(src: File, dst: File, cancel: AtomicBoolean, onFile: (String) -> Unit) {
        if (cancel.get()) throw InterruptedIOException("Cancelled")
        if (!dst.exists() && !dst.mkdirs()) throw IOException("Cannot create ${dst.absolutePath}")
        src.listFiles()?.forEach { child ->
            if (cancel.get()) throw InterruptedIOException("Cancelled")
            val out = File(dst, child.name)
            if (child.isDirectory) copyTree(child, out, cancel, onFile)
            else { copyFile(child, out, cancel) { }; onFile(child.name) }
        }
    }

    private fun copyFile(src: File, dst: File, cancel: AtomicBoolean, onBytes: (Long) -> Unit) {
        require(src.length() <= MAX_SINGLE_FILE_BYTES) { "File terlalu besar (maks ${MAX_SINGLE_FILE_BYTES / (1024 * 1024)} MB): ${src.name}" }
        dst.parentFile?.mkdirs()
        val temp = File(dst.parentFile, ".${dst.name}.gitls-copy-${UUID.randomUUID()}")
        try {
            FileInputStream(src).use { input -> FileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancel.get()) throw InterruptedIOException("Cancelled")
                    val n = input.read(buffer); if (n < 0) break
                    output.write(buffer, 0, n); onBytes(n.toLong())
                }
                output.fd.sync()
            }}
            if (dst.exists() && !dst.delete()) throw IOException("Cannot replace ${dst.absolutePath}")
            if (!temp.renameTo(dst)) throw IOException("Cannot install ${dst.absolutePath}")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun deleteBatch(sources: List<File>, cancel: AtomicBoolean = AtomicBoolean(false), onProgress: (Progress) -> Unit = { }): BatchResult {
        val total = sources.size.coerceAtLeast(1).toLong(); var done = 0L; var completed = 0
        val failures = mutableListOf<Failure>()
        for (file in sources) {
            if (cancel.get()) break
            try { deleteTree(file, cancel); completed++ }
            catch (e: Exception) { failures += Failure(file.absolutePath, e.message ?: "Delete failed") }
            done++; onProgress(Progress(done, total, file.name))
        }
        return BatchResult(completed, failures, cancel.get())
    }

    private fun deleteTree(file: File, cancel: AtomicBoolean) {
        if (cancel.get()) throw InterruptedIOException("Cancelled")
        if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it, cancel) }
        if (file.exists() && !file.delete()) throw IOException("Cannot delete ${file.absolutePath}")
    }

    private fun deleteTreeAfterSuccessfulCopy(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteTreeAfterSuccessfulCopy(it) }
        if (file.exists() && !file.delete()) throw IOException("Copied but could not delete source ${file.absolutePath}")
    }

    fun listArchive(file: File): List<ArchiveEntryInfo> = ZipFile(file).use { zip ->
        val entries = zip.entries().toList()
        val total = entries.sumOf { it.size.coerceAtLeast(0L) }
        require(total <= MAX_ARCHIVE_BYTES) { "Arsip terlalu besar (maks ${MAX_ARCHIVE_BYTES / (1024 * 1024)} MB)" }
        entries.map { ArchiveEntryInfo(it.name, it.isDirectory, it.size.coerceAtLeast(0), it.compressedSize.coerceAtLeast(0)) }
    }

    fun createZip(source: File, output: File, cancel: AtomicBoolean = AtomicBoolean(false), onProgress: (Progress) -> Unit = { }) {
        require(source.canonicalFile != output.canonicalFile) { "Source and output cannot be the same" }
        val files = if (source.isFile) listOf(source) else source.walkTopDown().filter { it.isFile }.toList()
        val total = files.sumOf { it.length() }.coerceAtLeast(1L); var done = 0L
        output.parentFile?.mkdirs()
        val tempOutput = File(output.parentFile, ".${output.name}.gitls-zip-${UUID.randomUUID()}")
        try {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempOutput))).use { zos ->
            val base = source.parentFile ?: source
            files.forEach { f ->
                if (cancel.get()) throw InterruptedIOException("Cancelled")
                val name = if (source.isFile) source.name else f.relativeTo(base).path.replace(File.separatorChar, '/')
                zos.putNextEntry(ZipEntry(name))
                FileInputStream(f).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel.get()) throw InterruptedIOException("Cancelled")
                        val n = input.read(buffer); if (n < 0) break
                        zos.write(buffer, 0, n); done += n
                        onProgress(Progress(done, total, f.name))
                    }
                }
                zos.closeEntry()
            }
        }
        if (output.exists() && !output.delete()) throw IOException("Cannot replace ZIP output")
        if (!tempOutput.renameTo(output)) throw IOException("Cannot install ZIP output")
        } finally {
            if (tempOutput.exists()) tempOutput.delete()
        }
    }

    fun extractZip(zip: File, destination: File, cancel: AtomicBoolean = AtomicBoolean(false), onProgress: (Progress) -> Unit = { }) {
        require(zip.length() <= MAX_ARCHIVE_BYTES) { "ZIP terlalu besar" }
        val entries = listArchive(zip)
        val total = entries.sumOf { it.size }.coerceAtLeast(1L)
        var done = 0L
        val stage = File(destination.parentFile ?: destination, ".${destination.name}.gitls-extract-${UUID.randomUUID()}")
        stage.mkdirs()
        try {
            val canonicalStage = stage.canonicalFile
            ZipFile(zip).use { z ->
                z.entries().asSequence().forEach { e ->
                    if (cancel.get()) throw InterruptedIOException("Cancelled")
                    val target = File(canonicalStage, e.name).canonicalFile
                    if (!target.path.startsWith(canonicalStage.path + File.separator)) throw SecurityException("Unsafe ZIP path: ${e.name}")
                    if (!e.isDirectory && e.size > MAX_SINGLE_FILE_BYTES) throw IOException("ZIP entry terlalu besar: ${e.name}")
                    if (e.isDirectory) { target.mkdirs(); return@forEach }
                    target.parentFile?.mkdirs()
                    z.getInputStream(e).use { input -> FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancel.get()) throw InterruptedIOException("Cancelled")
                            val n = input.read(buffer); if (n < 0) break
                            output.write(buffer, 0, n); done += n; onProgress(Progress(done, total, e.name))
                        }
                        output.fd.sync()
                    }}
                }
            }
            if (cancel.get()) throw InterruptedIOException("Cancelled")
            // Install only after the complete archive has been extracted successfully.
            destination.mkdirs()
            stage.walkTopDown().forEach { src ->
                if (src == stage) return@forEach
                val rel = src.relativeTo(stage).path
                val dst = File(destination, rel)
                if (src.isDirectory) dst.mkdirs() else {
                    val tmp = File(dst.parentFile, ".${dst.name}.gitls-install-${UUID.randomUUID()}")
                    dst.parentFile?.mkdirs()
                    src.copyTo(tmp, overwrite = true)
                    if (dst.exists() && !dst.delete()) throw IOException("Tidak bisa mengganti ${dst.path}")
                    if (!tmp.renameTo(dst)) throw IOException("Tidak bisa memasang ${dst.path}")
                }
            }
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    fun readUtf8Limited(file: File, maxBytes: Long = 16L * 1024L * 1024L): String {
        require(file.length() <= maxBytes) { "File teks terlalu besar (maks ${maxBytes / (1024 * 1024)} MB)" }
        return FileInputStream(file).use { input ->
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n=input.read(buffer); if(n<0) break; md.update(buffer,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun findDuplicates(root: File, minSize: Long = 1L, cancel: AtomicBoolean = AtomicBoolean(false)): List<List<FileStat>> {
        val bySize = root.walkTopDown().filter { it.isFile && it.length() >= minSize }.groupBy { it.length() }
        val result = mutableListOf<List<FileStat>>()
        bySize.values.filter { it.size > 1 }.forEach { group ->
            if (cancel.get()) return@forEach
            // Hash each candidate exactly once. The old implementation calculated
            // the SHA-256 again while constructing FileStat, doubling I/O on large files.
            val byHash = group.mapNotNull { file ->
                if (cancel.get()) return@mapNotNull null
                runCatching { file to sha256(file) }.getOrNull()
            }.groupBy({ it.second }, { it.first })
            byHash.filterValues { it.size > 1 }.forEach { (hash, same) ->
                result += same.map { file -> FileStat(file, file.length(), file.lastModified(), hash) }
            }
        }
        return result
    }

    fun largestFiles(root: File, limit: Int = 100): List<FileStat> = root.walkTopDown().filter { it.isFile }.map { FileStat(it, it.length(), it.lastModified()) }.sortedByDescending { it.size }.take(limit).toList()

    fun folderSize(root: File): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun formatReport(result: BatchResult): String = buildString {
        appendLine("GITLS Operation Report")
        appendLine("Completed: ${result.completed}")
        appendLine("Failed: ${result.failed.size}")
        appendLine("Cancelled: ${result.cancelled}")
        result.failed.forEach { appendLine("FAILED | ${it.path} | ${it.message}") }
    }

    fun recent(context: Context): List<File> = context.getSharedPreferences("gitls", Context.MODE_PRIVATE)
        .getString("recent_files", "")
        .orEmpty()
        .lineSequence()
        .filter { it.isNotBlank() }
        .map(::File)
        .filter { it.exists() }
        .distinctBy { it.absolutePath }
        .toList()

    fun addRecent(context: Context, file: File, max: Int = 50) {
        val prefs = context.getSharedPreferences("gitls", Context.MODE_PRIVATE)
        val list = (listOf(file.absolutePath) + recent(context).map { it.absolutePath }.filter { it != file.absolutePath }).take(max)
        prefs.edit().putString("recent_files", list.joinToString("\n")).apply()
    }

    fun exportText(context: Context, text: String, uri: Uri): Boolean = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }; true }.getOrDefault(false)

    fun nowLabel(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
