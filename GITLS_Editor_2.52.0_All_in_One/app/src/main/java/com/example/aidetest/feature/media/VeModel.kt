package com.example.aidetest

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/*
 * Model proyek Video Editor (potret, multi-track):
 *  - Track utama: klip video/foto berurutan (VeClip)
 *  - Audio: item bebas di timeline (musik, suara rekaman, audio diekstrak) — VeAudioItem
 *  - Overlay: teks, keterangan (subtitle), stiker, gambar — VeOverlay, tiap item punya waktu & transformasi
 *  - Efek berwaktu — VeEffect
 * Format JSON proyek (prefs "ve_project_v2") tetap kompatibel: kunci lama dibaca, kunci baru opsional.
 */

data class VeClip(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val isImage: Boolean,
    /** Durasi sumber (ms); untuk gambar = durasi tampil. */
    var sourceDurationMs: Long,
    /** Trim mulai (ms dari awal sumber). */
    var trimStartMs: Long = 0L,
    /** Trim akhir (ms); 0 = sampai habis. */
    var trimEndMs: Long = 0L,
    var rotation: Int = 0,
    var videoWidth: Int = 0,
    var videoHeight: Int = 0,
    var sourceRotation: Int = 0,
    /** Kecepatan putar 0.25×–4× (render penuh: encoding ulang video + audio). */
    var speed: Float = 1f,
    /** Preset filter: normal, bw, warm, cool, vivid, fade, invert. */
    var filter: String = "normal",
    /** -0.5..0.5 */
    var brightness: Float = 0f,
    /** -0.5..0.5 */
    var contrast: Float = 0f,
    /** -100..100 */
    var saturation: Float = 0f,
    /** Transisi MASUK ke klip ini (lihat VeTransMath.TYPES). */
    var transition: String = "none",
    /** Teks lama per klip (dimigrasikan ke overlay keterangan saat proyek dimuat; tidak dipakai lagi). */
    var caption: String = "",
    /** Volume audio klip 0..2. */
    var volume: Float = 1f,
    /** Cermin horizontal. */
    var flipH: Boolean = false,
    /** Zoom 1..4 di sekitar pusat + geser (-1..1 dari rentang geser yang diizinkan). */
    var zoom: Float = 1f,
    var panX: Float = 0f,
    var panY: Float = 0f,
    /** Suhu warna -1 (dingin) .. 1 (hangat). */
    var temperature: Float = 0f
) {
    fun effectiveEnd(): Long = if (trimEndMs > 0L) trimEndMs else sourceDurationMs

    /** Panjang potongan sumber (ms), sebelum kecepatan. */
    fun durationMs(): Long = (effectiveEnd() - trimStartMs).coerceAtLeast(100L)

    /** Panjang di timeline / hasil render (ms), setelah kecepatan. */
    fun timelineMs(): Long =
        if (isImage) durationMs() else (durationMs() / speed.coerceIn(0.25f, 4f)).toLong().coerceAtLeast(100L)

    fun effectiveSpeed(): Float = if (isImage) 1f else speed.coerceIn(0.25f, 4f)

    fun hasTransform(): Boolean = flipH || zoom > 1.001f
    fun hasColorEdits(): Boolean = filter != "normal" || brightness != 0f || contrast != 0f || saturation != 0f || temperature != 0f
}

/** Item pustaka media (hasil impor). */
data class VeMedia(
    val uri: Uri,
    val name: String,
    val isImage: Boolean,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int
)

/** Item audio di timeline: musik, suara rekaman, atau audio yang diekstrak dari video. */
data class VeAudioItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    var name: String,
    /** musik | suara | ekstrak */
    var kind: String = "musik",
    /** Posisi awal di timeline (ms). */
    var startMs: Long = 0L,
    val sourceDurationMs: Long,
    var srcStartMs: Long = 0L,
    /** 0 = sampai habis. */
    var srcEndMs: Long = 0L,
    var volume: Float = 1f,
    var fadeInMs: Long = 0L,
    var fadeOutMs: Long = 0L
) {
    fun effectiveEnd(): Long = if (srcEndMs > 0L) srcEndMs else sourceDurationMs
    fun durationMs(): Long = (effectiveEnd() - srcStartMs).coerceAtLeast(100L)
    fun endMs(): Long = startMs + durationMs()
}

/** Teks, keterangan, stiker, atau gambar di atas video — dengan waktu tampil & transformasi. */
data class VeOverlay(
    val id: String = UUID.randomUUID().toString(),
    /** text | caption | sticker | image */
    var kind: String,
    var startMs: Long,
    var durationMs: Long = 3000L,
    var text: String = "",
    var font: String = "bold",
    var bold: Boolean = false,
    var italic: Boolean = false,
    var color: Int = Color.WHITE,
    /** Tinggi huruf / lebar stiker & gambar, relatif sisi pendek bingkai. */
    var size: Float = 0.075f,
    /** left | center | right */
    var align: String = "center",
    var bgColor: Int = Color.BLACK,
    var bgOpacity: Float = 0f,
    var outlineColor: Int = Color.BLACK,
    /** 0..0.25 × ukuran huruf. */
    var outlineWidth: Float = 0f,
    /** 0..1 */
    var shadow: Float = 0f,
    /** Pusat, relatif bingkai (0..1). */
    var cx: Float = 0.5f,
    var cy: Float = 0.5f,
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var opacity: Float = 1f,
    var animIn: String = "none",
    var animOut: String = "none",
    var animMs: Long = 400L,
    /** Nama ikon MDI (stiker) — kosong untuk emoji/teks. */
    var icon: String = "",
    var imageUri: Uri? = null,
    /** Lebar/tinggi gambar overlay. */
    var imageAspect: Float = 1f
) {
    fun endMs(): Long = startMs + durationMs

    companion object {
        fun text(startMs: Long, text: String): VeOverlay = VeOverlay(
            kind = "text", startMs = startMs, text = text, font = "bold", size = 0.075f,
            outlineWidth = 0.08f, shadow = 0.3f
        )

        fun caption(startMs: Long, durationMs: Long, text: String): VeOverlay = VeOverlay(
            kind = "caption", startMs = startMs, durationMs = durationMs, text = text, font = "sans", size = 0.045f,
            bgOpacity = 0.55f, cy = 0.86f
        )

        fun sticker(startMs: Long, icon: String, emoji: String): VeOverlay = VeOverlay(
            kind = "sticker", startMs = startMs, text = emoji, icon = icon, size = 0.2f, cy = 0.4f,
            shadow = if (icon.isNotEmpty()) 0.3f else 0f
        )

        fun image(startMs: Long, uri: Uri, aspect: Float): VeOverlay = VeOverlay(
            kind = "image", startMs = startMs, imageUri = uri, imageAspect = aspect, size = 0.4f
        )
    }
}

/** Efek visual berwaktu (lihat VeFxMath). */
data class VeEffect(
    val id: String = UUID.randomUUID().toString(),
    var type: String,
    var startMs: Long,
    var durationMs: Long = 2000L,
    var intensity: Float = 0.6f
) {
    fun endMs(): Long = startMs + durationMs
}

/** Konversi proyek lama (subtitle/watermark/teks per klip) menjadi spesifikasi overlay — murni & teruji. */
internal class VeLegacySpec(val kind: String, val text: String, val startMs: Long, val durationMs: Long, val topRight: Boolean)

internal object VeLegacy {
    /**
     * Subtitle global dan teks per klip: tiap klip memperoleh satu keterangan (teks klip menimpa subtitle global).
     * Watermark menjadi teks kecil di kanan atas sepanjang timeline.
     */
    fun migrate(subtitle: String, watermark: String, clipCaptions: List<String>, clipDurations: List<Long>): List<VeLegacySpec> {
        val out = ArrayList<VeLegacySpec>()
        var t = 0L
        for (i in clipDurations.indices) {
            val d = clipDurations[i]
            val text = clipCaptions.getOrNull(i).orEmpty().ifBlank { subtitle }.trim()
            if (text.isNotEmpty() && d > 0L) out.add(VeLegacySpec("caption", text, t, d, false))
            t += d
        }
        if (watermark.isNotBlank() && t > 0L) out.add(VeLegacySpec("text", watermark.trim(), 0L, t, true))
        return out
    }
}

internal const val VE_PICK_VIDEO = 1230
internal const val VE_PICK_IMAGE = 1231
internal const val VE_PREFS_KEY = "ve_project_v2"

@Volatile
internal var videoEditorPickPending: ((List<Uri>) -> Unit)? = null

/** State proyek bertahan saat halaman dibangun ulang (Back, buka tool lain, ganti tema). */
internal object VeProject {
    val clips = mutableListOf<VeClip>()
    val library = mutableListOf<VeMedia>()
    val audios = mutableListOf<VeAudioItem>()
    val overlays = mutableListOf<VeOverlay>()
    val effects = mutableListOf<VeEffect>()

    /** Seleksi timeline: kind = clip | audio | overlay | effect | "" (tidak ada). */
    var selKind = ""
    var selId: String? = null

    var name = "Proyek 1"
    var includeAudio = true
    var showText = true
    var previewMuted = false
    var previewVolume = 1f
    /** Piksel per detik = dp(40) × zoom. */
    var zoom = 1f
    var playheadMs = 0L
    var loaded = false
    var lastExport: File? = null
    var lastExportLabel = ""
    var lastExportUri: Uri? = null
    var exporting = false
    var exportProgress = -1
    /** render | cepat */
    var exportMode = "render"
    /** asli | 16:9 | 9:16 | 1:1 | 4:5 | 4:3 | 3:4 */
    var aspect = "asli"
    /** isi (crop) | pas (bar hitam) | blur (latar blur) */
    var fitMode = "pas"
    /** Sisi pendek output: 480 | 720 | 1080 | 1440 */
    var resolution = 720
    /** Frame rate output: 0 = asli | 30 | 24. Hanya menurunkan. */
    var frameRate = 0
    /** auto | hemat | tinggi */
    var quality = "auto"
    /** Sampul: waktu (ms global) dan apakah disimpan sebagai JPG saat ekspor. */
    var coverMs = 0L
    var coverOn = false
    /** Magnet: snap ke playhead/tepi klip/item. */
    var snapOn = true
    /** Render yang sedang berjalan (bertahan walau halaman dibangun ulang). */
    var activeRender: VeRenderExporter? = null
    val undo = ArrayList<String>()
    val redo = ArrayList<String>()
    val thumbs = HashMap<String, Bitmap>()

    // ---- legacy (dibaca dari proyek lama lalu dimigrasikan)
    private var legacySubtitle = ""
    private var legacyWatermark = ""

    fun totalMs(): Long = clips.sumOf { it.timelineMs() }

    fun clipDurations(): LongArray = LongArray(clips.size) { clips[it].timelineMs() }

    fun clipStart(clip: VeClip): Long {
        var t = 0L
        for (c in clips) {
            if (c.id == clip.id) return t
            t += c.timelineMs()
        }
        return t
    }

    fun clearSelection() { selKind = ""; selId = null }

    // ------------------------------------------------------------------ JSON

    fun clipJson(c: VeClip): JSONObject = JSONObject()
        .put("id", c.id).put("uri", c.uri.toString()).put("name", c.name).put("img", c.isImage)
        .put("src", c.sourceDurationMs).put("ts", c.trimStartMs).put("te", c.trimEndMs)
        .put("rot", c.rotation).put("w", c.videoWidth).put("h", c.videoHeight).put("srot", c.sourceRotation)
        .put("spd", c.speed.toDouble()).put("flt", c.filter).put("bri", c.brightness.toDouble())
        .put("con", c.contrast.toDouble()).put("sat", c.saturation.toDouble()).put("trn", c.transition)
        .put("cap", c.caption).put("vol", c.volume.toDouble())
        .put("flip", c.flipH).put("zm", c.zoom.toDouble()).put("px", c.panX.toDouble()).put("py", c.panY.toDouble())
        .put("tmp", c.temperature.toDouble())

    fun clipFrom(o: JSONObject): VeClip = VeClip(
        id = o.optString("id", UUID.randomUUID().toString()),
        uri = Uri.parse(o.getString("uri")),
        name = o.optString("name", "klip"),
        isImage = o.optBoolean("img", false),
        sourceDurationMs = o.optLong("src", 3000L),
        trimStartMs = o.optLong("ts", 0L),
        trimEndMs = o.optLong("te", 0L),
        rotation = o.optInt("rot", 0),
        videoWidth = o.optInt("w", 0),
        videoHeight = o.optInt("h", 0),
        sourceRotation = o.optInt("srot", 0),
        speed = o.optDouble("spd", 1.0).toFloat().coerceIn(0.25f, 4f),
        filter = o.optString("flt", "normal"),
        brightness = o.optDouble("bri", 0.0).toFloat(),
        contrast = o.optDouble("con", 0.0).toFloat(),
        saturation = o.optDouble("sat", 0.0).toFloat(),
        transition = o.optString("trn", "none"),
        caption = o.optString("cap", ""),
        volume = o.optDouble("vol", 1.0).toFloat(),
        flipH = o.optBoolean("flip", false),
        zoom = o.optDouble("zm", 1.0).toFloat().coerceIn(1f, 4f),
        panX = o.optDouble("px", 0.0).toFloat().coerceIn(-1f, 1f),
        panY = o.optDouble("py", 0.0).toFloat().coerceIn(-1f, 1f),
        temperature = o.optDouble("tmp", 0.0).toFloat().coerceIn(-1f, 1f)
    )

    private fun audioJson(a: VeAudioItem): JSONObject = JSONObject()
        .put("id", a.id).put("uri", a.uri.toString()).put("name", a.name).put("kind", a.kind)
        .put("st", a.startMs).put("sd", a.sourceDurationMs).put("ss", a.srcStartMs).put("se", a.srcEndMs)
        .put("vol", a.volume.toDouble()).put("fi", a.fadeInMs).put("fo", a.fadeOutMs)

    private fun audioFrom(o: JSONObject): VeAudioItem = VeAudioItem(
        id = o.optString("id", UUID.randomUUID().toString()),
        uri = Uri.parse(o.getString("uri")),
        name = o.optString("name", "Audio"),
        kind = o.optString("kind", "musik"),
        startMs = o.optLong("st", 0L).coerceAtLeast(0L),
        sourceDurationMs = o.optLong("sd", 1000L).coerceAtLeast(100L),
        srcStartMs = o.optLong("ss", 0L).coerceAtLeast(0L),
        srcEndMs = o.optLong("se", 0L).coerceAtLeast(0L),
        volume = o.optDouble("vol", 1.0).toFloat().coerceIn(0f, 2f),
        fadeInMs = o.optLong("fi", 0L).coerceAtLeast(0L),
        fadeOutMs = o.optLong("fo", 0L).coerceAtLeast(0L)
    )

    private fun overlayJson(v: VeOverlay): JSONObject = JSONObject()
        .put("id", v.id).put("k", v.kind).put("st", v.startMs).put("d", v.durationMs).put("t", v.text)
        .put("f", v.font).put("b", v.bold).put("i", v.italic).put("c", v.color).put("sz", v.size.toDouble())
        .put("al", v.align).put("bc", v.bgColor).put("bo", v.bgOpacity.toDouble())
        .put("oc", v.outlineColor).put("ow", v.outlineWidth.toDouble()).put("sh", v.shadow.toDouble())
        .put("cx", v.cx.toDouble()).put("cy", v.cy.toDouble()).put("sc", v.scale.toDouble())
        .put("rt", v.rotation.toDouble()).put("op", v.opacity.toDouble())
        .put("ai", v.animIn).put("ao", v.animOut).put("am", v.animMs)
        .put("ic", v.icon).put("iu", v.imageUri?.toString() ?: "").put("ia", v.imageAspect.toDouble())

    private fun overlayFrom(o: JSONObject): VeOverlay = VeOverlay(
        id = o.optString("id", UUID.randomUUID().toString()),
        kind = o.optString("k", "text"),
        startMs = o.optLong("st", 0L).coerceAtLeast(0L),
        durationMs = o.optLong("d", 3000L).coerceAtLeast(200L),
        text = o.optString("t", ""),
        font = o.optString("f", "bold"),
        bold = o.optBoolean("b", false),
        italic = o.optBoolean("i", false),
        color = o.optInt("c", Color.WHITE),
        size = o.optDouble("sz", 0.075).toFloat().coerceIn(0.01f, 1.5f),
        align = o.optString("al", "center"),
        bgColor = o.optInt("bc", Color.BLACK),
        bgOpacity = o.optDouble("bo", 0.0).toFloat().coerceIn(0f, 1f),
        outlineColor = o.optInt("oc", Color.BLACK),
        outlineWidth = o.optDouble("ow", 0.0).toFloat().coerceIn(0f, 0.3f),
        shadow = o.optDouble("sh", 0.0).toFloat().coerceIn(0f, 1f),
        cx = o.optDouble("cx", 0.5).toFloat().coerceIn(0f, 1f),
        cy = o.optDouble("cy", 0.5).toFloat().coerceIn(0f, 1f),
        scale = o.optDouble("sc", 1.0).toFloat().coerceIn(0.1f, 6f),
        rotation = o.optDouble("rt", 0.0).toFloat(),
        opacity = o.optDouble("op", 1.0).toFloat().coerceIn(0f, 1f),
        animIn = o.optString("ai", "none"),
        animOut = o.optString("ao", "none"),
        animMs = o.optLong("am", 400L).coerceIn(100L, 2000L),
        icon = o.optString("ic", ""),
        imageUri = o.optString("iu", "").takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
        imageAspect = o.optDouble("ia", 1.0).toFloat().coerceIn(0.05f, 20f)
    )

    private fun effectJson(e: VeEffect): JSONObject = JSONObject()
        .put("id", e.id).put("ty", e.type).put("st", e.startMs).put("d", e.durationMs).put("in", e.intensity.toDouble())

    private fun effectFrom(o: JSONObject): VeEffect = VeEffect(
        id = o.optString("id", UUID.randomUUID().toString()),
        type = o.optString("ty", "getar"),
        startMs = o.optLong("st", 0L).coerceAtLeast(0L),
        durationMs = o.optLong("d", 2000L).coerceAtLeast(200L),
        intensity = o.optDouble("in", 0.6).toFloat().coerceIn(0f, 1f)
    )

    private fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> {
        val out = ArrayList<T>()
        for (i in 0 until length()) runCatching { out.add(f(getJSONObject(i))) }
        return out
    }

    /** Snapshot proyek (tanpa pustaka) untuk undo/redo. */
    fun snapshot(): String {
        val clipArr = JSONArray(); clips.forEach { clipArr.put(clipJson(it)) }
        val audioArr = JSONArray(); audios.forEach { audioArr.put(audioJson(it)) }
        val overlayArr = JSONArray(); overlays.forEach { overlayArr.put(overlayJson(it)) }
        val effectArr = JSONArray(); effects.forEach { effectArr.put(effectJson(it)) }
        return JSONObject().put("clips", clipArr).put("audios", audioArr).put("overlays", overlayArr).put("effects", effectArr)
            .put("selK", selKind).put("selI", selId ?: "")
            .put("audio", includeAudio).put("cover", coverMs).toString()
    }

    fun restore(json: String) {
        val o = JSONObject(json)
        clips.clear()
        val arr = o.optJSONArray("clips") ?: JSONArray()
        for (i in 0 until arr.length()) runCatching { clips.add(clipFrom(arr.getJSONObject(i))) }
        includeAudio = o.optBoolean("audio", true)
        coverMs = o.optLong("cover", 0L).coerceAtLeast(0L)
        audios.clear(); overlays.clear(); effects.clear()
        val hasNew = o.has("overlays") || o.has("audios") || o.has("effects")
        if (hasNew) {
            audios.addAll((o.optJSONArray("audios") ?: JSONArray()).mapObjects { audioFrom(it) })
            overlays.addAll((o.optJSONArray("overlays") ?: JSONArray()).mapObjects { overlayFrom(it) })
            effects.addAll((o.optJSONArray("effects") ?: JSONArray()).mapObjects { effectFrom(it) })
        } else {
            migrateLegacy(o)
        }
        val selK = o.optString("selK", "")
        val selI = o.optString("selI", "").ifBlank { null }
        // Proyek lama menyimpan "sel" = id klip.
        val legacySel = o.optString("sel", "").ifBlank { null }
        if (selK.isNotEmpty() && selI != null) { selKind = selK; selId = selI }
        else if (legacySel != null) { selKind = "clip"; selId = legacySel }
        else clearSelection()
        validateSelection()
    }

    /** Proyek lama: subtitle/watermark/teks klip → overlay; musik tunggal → item audio (diulang menutupi video). */
    private fun migrateLegacy(o: JSONObject) {
        legacySubtitle = o.optString("sub", "")
        legacyWatermark = o.optString("wm", "")
        val specs = VeLegacy.migrate(legacySubtitle, legacyWatermark, clips.map { it.caption }, clipDurations().toList())
        for (s in specs) {
            val v = if (s.kind == "caption") VeOverlay.caption(s.startMs, s.durationMs, s.text)
            else VeOverlay.text(s.startMs, s.text).also { it.durationMs = s.durationMs; it.size = 0.03f; it.cx = 0.84f; it.cy = 0.05f; it.opacity = 0.85f; it.outlineWidth = 0f; it.shadow = 0.5f }
            overlays.add(v)
        }
        clips.forEach { it.caption = "" }
        val music = o.optString("music", "")
        if (music.isNotBlank()) {
            val dur = o.optLong("musicDur", 0L).coerceAtLeast(1000L)
            val vol = o.optDouble("musicVol", 0.6).toFloat().coerceIn(0f, 1.5f)
            val total = totalMs()
            var t = 0L
            var n = 0
            while (t < total && n < 40) {
                audios.add(VeAudioItem(uri = Uri.parse(music), name = o.optString("musicName", "Musik").ifBlank { "Musik" }, startMs = t,
                    sourceDurationMs = dur, volume = vol))
                t += dur
                n++
            }
        }
    }

    fun validateSelection() {
        val ok = when (selKind) {
            "clip" -> clips.any { it.id == selId }
            "audio" -> audios.any { it.id == selId }
            "overlay" -> overlays.any { it.id == selId }
            "effect" -> effects.any { it.id == selId }
            else -> false
        }
        if (!ok) clearSelection()
    }

    fun toPrefs(): String {
        val lib = JSONArray()
        library.forEach {
            lib.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("img", it.isImage)
                .put("d", it.durationMs).put("w", it.width).put("h", it.height).put("r", it.rotation))
        }
        return JSONObject().put("project", snapshot()).put("lib", lib).put("name", name)
            .put("show", showText).put("zoom", zoom.toDouble())
            .put("mode", exportMode).put("aspect", aspect).put("fit", fitMode).put("res", resolution)
            .put("fps", frameRate).put("quality", quality).put("coverOn", coverOn).put("snap", snapOn).toString()
    }

    fun fromPrefs(json: String) {
        val o = JSONObject(json)
        restore(o.optString("project", "{}"))
        name = o.optString("name", "Proyek 1")
        showText = o.optBoolean("show", true)
        zoom = o.optDouble("zoom", 1.0).toFloat().coerceIn(0.1f, 8f)
        exportMode = o.optString("mode", "render")
        aspect = o.optString("aspect", "asli").let { if (it in ASPECTS) it else "asli" }
        fitMode = o.optString("fit", "pas").let { if (it in listOf("pas", "isi", "blur")) it else "pas" }
        resolution = o.optInt("res", 720).let { if (it in listOf(480, 720, 1080, 1440)) it else 720 }
        frameRate = o.optInt("fps", 0).let { if (it in listOf(0, 24, 30)) it else 0 }
        quality = o.optString("quality", "auto").let { if (it in listOf("auto", "hemat", "tinggi")) it else "auto" }
        coverOn = o.optBoolean("coverOn", false)
        snapOn = o.optBoolean("snap", true)
        library.clear()
        val lib = o.optJSONArray("lib") ?: JSONArray()
        for (i in 0 until lib.length()) runCatching {
            val m = lib.getJSONObject(i)
            library.add(VeMedia(Uri.parse(m.getString("uri")), m.optString("name", "media"), m.optBoolean("img"),
                m.optLong("d", 3000L), m.optInt("w"), m.optInt("h"), m.optInt("r")))
        }
    }

    val ASPECTS = listOf("asli", "16:9", "9:16", "1:1", "4:5", "4:3", "3:4")
}
