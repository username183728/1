package com.example.aidetest

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dekoder audio streaming: membaca [startMs, endMs) dari sebuah file audio/video dan mengeluarkan
 * PCM 16-bit STEREO 44,1 kHz secara bertahap (read(n)). Memori kecil dan tetap stereo —
 * kualitas musik tidak diturunkan menjadi mono.
 */
internal class VeAudioStream(
    private val ctx: Context,
    private val uri: Uri,
    startMs: Long,
    endMs: Long
) {
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var pfd: ParcelFileDescriptor? = null
    private var inRate = VeMixer.RATE
    private var channels = 2
    private var pcmFloat = false
    private var resampler: VeResampler? = null
    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()
    private val queue = ArrayDeque<ShortArray>()
    private var queuedFrames = 0
    private var headOffset = 0
    private val startUs = startMs.coerceAtLeast(0L) * 1000L
    private val endUs = if (endMs > 0L) endMs * 1000L else Long.MAX_VALUE

    fun open(): Boolean {
        val ex = MediaExtractor()
        try {
            val p = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            if (p != null) { pfd = p; ex.setDataSource(p.fileDescriptor) } else ex.setDataSource(ctx, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) { ex.release(); close(); return false }
            ex.selectTrack(track)
            inRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(VeMixer.RATE)
            channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2).coerceAtLeast(1)
            resampler = VeResampler(inRate, VeMixer.RATE)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val dec = MediaCodec.createDecoderByType(mime)
            dec.configure(format, null, null, 0)
            dec.start()
            codec = dec
            extractor = ex
            if (startUs > 0L) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            return true
        } catch (e: Throwable) {
            runCatching { ex.release() }
            close()
            return false
        }
    }

    private fun push(stereo: ShortArray) {
        if (stereo.isEmpty()) return
        val out = resampler?.process(stereo) ?: stereo
        if (out.isEmpty()) return
        queue.addLast(out)
        queuedFrames += out.size / 2
    }

    private fun handleOutput(buf: ByteBuffer, offset: Int, size: Int, ptsUs: Long) {
        buf.position(offset)
        buf.limit(offset + size)
        val bo = buf.order(ByteOrder.nativeOrder())
        val shorts: ShortArray = if (pcmFloat) {
            val fb = bo.asFloatBuffer()
            val arr = FloatArray(fb.remaining())
            fb.get(arr)
            ShortArray(arr.size) { (arr[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        } else {
            val sb = bo.asShortBuffer()
            ShortArray(sb.remaining()).also { sb.get(it) }
        }
        val ch = max(1, channels)
        val inFrames = shorts.size / ch
        if (inFrames <= 0) return
        // Jadikan stereo: mono digandakan, >2 kanal memakai dua pertama.
        val stereo = ShortArray(inFrames * 2)
        for (i in 0 until inFrames) {
            val l = shorts[i * ch]
            val r = if (ch >= 2) shorts[i * ch + 1] else l
            stereo[i * 2] = l
            stereo[i * 2 + 1] = r
        }
        var from = 0
        var to = inFrames
        if (ptsUs < startUs) from = ((startUs - ptsUs) * inRate / 1_000_000L).toInt().coerceAtMost(inFrames)
        val bufEndUs = ptsUs + inFrames * 1_000_000L / inRate
        if (bufEndUs > endUs) {
            to = ((endUs - ptsUs) * inRate / 1_000_000L).toInt().coerceIn(from, inFrames)
            outputDone = true
        }
        if (to > from) push(stereo.copyOfRange(from * 2, to * 2))
    }

    private fun fill(minFrames: Int) {
        val dec = codec ?: return
        val ex = extractor ?: return
        var idle = 0
        while (queuedFrames < minFrames && !outputDone) {
            if (!inputDone) {
                val ii = dec.dequeueInputBuffer(2_000)
                if (ii >= 0) {
                    val ib = dec.getInputBuffer(ii)
                    val size = if (ib != null) ex.readSampleData(ib, 0) else -1
                    if (size < 0 || ex.sampleTime > endUs) {
                        dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        dec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val oi = dec.dequeueOutputBuffer(info, 5_000)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = dec.outputFormat
                    val newRate = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(inRate)
                    channels = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channels).coerceAtLeast(1)
                    pcmFloat = runCatching { f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT }.getOrDefault(false)
                    if (newRate != inRate) { inRate = newRate; resampler = VeResampler(inRate, VeMixer.RATE) }
                    idle = 0
                }
                oi >= 0 -> {
                    idle = 0
                    val ob = dec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) handleOutput(ob, info.offset, info.size, info.presentationTimeUs)
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                else -> { idle++; if (idle > 1500) outputDone = true }
            }
        }
    }

    /** Hingga [frames] frame stereo berikutnya; lebih pendek (atau kosong) di akhir sumber. */
    fun read(frames: Int): ShortArray {
        fill(frames)
        val take = min(frames, queuedFrames)
        val out = ShortArray(take * 2)
        var written = 0
        while (written < take) {
            val head = queue.firstOrNull() ?: break
            val availFrames = head.size / 2 - headOffset
            val n = min(availFrames, take - written)
            System.arraycopy(head, headOffset * 2, out, written * 2, n * 2)
            written += n
            headOffset += n
            if (headOffset >= head.size / 2) { queue.removeFirst(); headOffset = 0 }
        }
        queuedFrames -= written
        return out
    }

    fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        runCatching { pfd?.close() }
        codec = null; extractor = null; pfd = null
    }
}

internal object VeAudioMix {
    private const val BLOCK_SEC = 4

    /**
     * Campur semua item audio menjadi satu WAV stereo 44,1 kHz sepanjang [totalMs] (diproses per blok 4 dtk,
     * tiap sumber didekode berurutan sehingga memori tetap kecil).
     * @return true bila WAV tertulis; false bila dibatalkan atau tidak ada audio yang bisa dibaca.
     */
    fun mixToWav(ctx: Context, items: List<VeAudioItem>, totalMs: Long, out: File, cancelled: () -> Boolean, onProgress: (Int) -> Unit): Boolean {
        if (items.isEmpty() || totalMs <= 0L) return false
        val rate = VeMixer.RATE
        val totalFrames = totalMs * rate / 1000L
        class Src(val item: VeAudioItem) {
            val startFrame = item.startMs * rate / 1000L
            val frames = item.durationMs() * rate / 1000L
            var stream: VeAudioStream? = null
            var failed = false
            var consumed = 0L
        }
        val srcs = items.map { Src(it) }
        var anyOk = false
        var finished = false
        val tmp = File(out.parentFile, out.name + ".tmp")
        try {
            BufferedOutputStream(FileOutputStream(tmp), 256 * 1024).use { os ->
                os.write(VeMixer.wavHeader(totalFrames, rate, 2))
                var frame = 0L
                val block = (rate * BLOCK_SEC).toLong()
                while (frame < totalFrames) {
                    if (cancelled()) return false
                    val n = min(block, totalFrames - frame).toInt()
                    val buf = ShortArray(n * 2)
                    for (s in srcs) {
                        if (s.failed) continue
                        val oStart = max(frame, s.startFrame)
                        val oEnd = min(frame + n, s.startFrame + s.frames)
                        if (oEnd <= oStart) continue
                        val st = s.stream ?: VeAudioStream(ctx, s.item.uri, s.item.srcStartMs, s.item.effectiveEnd()).also {
                            if (!it.open()) { s.failed = true; return@also }
                            s.stream = it
                        }
                        if (s.failed) continue
                        // Sumber dibaca berurutan: lewati celah jika blok melompat (tidak terjadi pada alur normal).
                        val want = (oEnd - oStart).toInt()
                        val chunk = st.read(want)
                        if (chunk.isEmpty()) continue
                        val itemFrames = s.frames.toInt()
                        val fadeIn = (s.item.fadeInMs * rate / 1000L).toInt()
                        val fadeOut = (s.item.fadeOutMs * rate / 1000L).toInt()
                        VeMixer.mixInto(buf, chunk, (oStart - frame).toInt(), s.item.volume.coerceIn(0f, 2f), fadeIn, fadeOut,
                            srcFrameOffset = (oStart - s.startFrame).toInt(), itemFrames = itemFrames)
                        s.consumed += chunk.size / 2
                        anyOk = true
                    }
                    val bytes = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(buf)
                    os.write(bytes.array())
                    frame += n
                    onProgress((frame * 100 / totalFrames).toInt())
                }
            }
            if (!anyOk) return false
            if (out.exists()) out.delete()
            finished = tmp.renameTo(out)
            return finished
        } finally {
            srcs.forEach { it.stream?.close() }
            // Dibatalkan / gagal / tidak ada audio terbaca: jangan tinggalkan berkas sementara.
            if (!finished) tmp.delete()
        }
    }

    /** Puncak amplitudo per 50 ms (0..32767) untuk menggambar waveform di timeline. */
    fun peaks(ctx: Context, uri: Uri, startMs: Long = 0L, endMs: Long = 0L, maxSeconds: Int = 900): ShortArray {
        val stream = VeAudioStream(ctx, uri, startMs, endMs)
        if (!stream.open()) return ShortArray(0)
        val out = ArrayList<Short>()
        val frames = VeMixer.RATE / 20
        try {
            var guard = maxSeconds * 20
            while (guard-- > 0) {
                val chunk = stream.read(frames)
                if (chunk.isEmpty()) break
                var p = 0
                for (v in chunk) p = max(p, abs(v.toInt()))
                out.add(min(p, 32767).toShort())
                if (chunk.size / 2 < frames) break
            }
        } finally {
            stream.close()
        }
        return out.toShortArray()
    }
}
