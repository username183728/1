package com.example.aidetest

import android.content.Context
import com.airbnb.lottie.LottieAnimationView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bentuk badan Bit. Animasi Lottie asli memakai lingkaran; di sini elemen
 * ellipse pada layer BODY diganti sesuai bentuk pilihan pengguna, sehingga mata
 * dan semua animasi tetap sama.
 */
internal object BitShape {
    const val PREF_KEY = "bit_shape"

    /** id to label */
    val options = listOf(
        "circle" to "Lingkaran",
        "square" to "Kotak",
        "triangle" to "Segitiga",
        "diamond" to "Belah ketupat",
        "hexagon" to "Segi enam",
        "star" to "Bintang"
    )

    @Volatile var current: String = "circle"

    private val cache = HashMap<String, String>()

    fun load(prefs: android.content.SharedPreferences) {
        val saved = prefs.getString(PREF_KEY, "circle") ?: "circle"
        current = if (options.any { it.first == saved }) saved else "circle"
    }

    // Frame arah pandang disisipkan setelah semua keyframe asli (terakhir di frame 940).
    // Grid rapat 41x33 (langkah ~1,2 unit ≈ 0,6dp) agar gerak mata terlihat mulus.
    const val GAZE_BASE = 1000
    const val GAZE_COLS = 41
    const val GAZE_ROWS = 33
    const val GAZE_MAX_X = 24.0     // unit komposisi Lottie (kanvas 512)
    const val GAZE_MAX_Y = 20.0
    private const val EYE_X = 256.0
    private const val EYE_Y = 260.0

    fun gazeFrame(col: Int, row: Int) = GAZE_BASE + row * GAZE_COLS + col
    val gazeEndFrame get() = GAZE_BASE + GAZE_COLS * GAZE_ROWS

    fun jsonFor(ctx: Context, asset: String, withGaze: Boolean = false): String {
        val key = "$asset|$current|$withGaze"
        synchronized(cache) {
            cache[key]?.let { return it }
            var out = ctx.assets.open(asset).bufferedReader().use { it.readText() }
            if (current != "circle") out = patch(out, current)
            if (withGaze) out = addGazeFrames(out)
            cache[key] = out
            return out
        }
    }

    fun cacheKey(asset: String, withGaze: Boolean = false) = "bit-$asset-$current-$withGaze"

    /** Tambah keyframe 'hold' posisi mata: satu frame per sel grid arah pandang. */
    private fun addGazeFrames(raw: String): String {
        val root = JSONObject(raw)
        val layers = root.optJSONArray("layers") ?: return raw
        val end = gazeEndFrame
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val name = layer.optString("nm")
            if (name.startsWith("EYES")) {
                val pos = layer.getJSONObject("ks").getJSONObject("p")
                if (pos.optInt("a") != 1) continue
                val kf = pos.getJSONArray("k")
                for (row in 0 until GAZE_ROWS) for (col in 0 until GAZE_COLS) {
                    val dx = (col / (GAZE_COLS - 1.0) * 2.0 - 1.0) * GAZE_MAX_X
                    val dy = (row / (GAZE_ROWS - 1.0) * 2.0 - 1.0) * GAZE_MAX_Y
                    kf.put(JSONObject().put("t", gazeFrame(col, row)).put("h", 1)
                        .put("s", JSONArray().put(EYE_X + dx).put(EYE_Y + dy)))
                }
                layer.put("op", end)
            } else if (name.startsWith("BODY") || name.startsWith("CTRL")) {
                layer.put("op", end)
            }
        }
        root.put("op", end)
        return root.toString()
    }

    private fun num(v: Double) = JSONObject().put("a", 0).put("k", v)
    private fun pos() = JSONObject().put("a", 0).put("k", JSONArray().put(256).put(256))

    private fun shapeItem(shape: String): JSONObject = when (shape) {
        "square" -> JSONObject().put("ty", "rc").put("p", pos())
            .put("s", JSONObject().put("a", 0).put("k", JSONArray().put(330).put(330)))
            .put("r", num(70.0))
        "triangle" -> poly(3, 0.0, 235.0, 14.0, 276.0)
        "diamond" -> poly(4, 0.0, 215.0, 10.0, 256.0)
        "hexagon" -> poly(6, 0.0, 190.0, 8.0, 256.0)
        else -> JSONObject().put("ty", "sr").put("sy", 1).put("pt", num(5.0)).put("p", pos())
            .put("r", num(0.0)).put("or", num(215.0)).put("ir", num(130.0))
            .put("os", num(18.0)).put("is", num(18.0))
    }

    private fun poly(points: Int, rot: Double, radius: Double, round: Double, cy: Double) =
        JSONObject().put("ty", "sr").put("sy", 2).put("pt", num(points.toDouble()))
            .put("p", JSONObject().put("a", 0).put("k", JSONArray().put(256).put(cy)))
            .put("r", num(rot)).put("or", num(radius)).put("os", num(round))

    private fun patch(raw: String, shape: String): String {
        val root = JSONObject(raw)
        val layers = root.optJSONArray("layers") ?: return raw
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (!layer.optString("nm").startsWith("BODY")) continue
            patchItems(layer.optJSONArray("shapes"), shape)
        }
        return root.toString()
    }

    private fun patchItems(items: JSONArray?, shape: String) {
        if (items == null) return
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            when (it.optString("ty")) {
                "el" -> items.put(i, shapeItem(shape))
                "gr" -> patchItems(it.optJSONArray("it"), shape)
            }
        }
    }
}

/** Pengganti setAnimation("bit/xxx.json") yang menghormati bentuk Bit pilihan. */
internal fun LottieAnimationView.setBitAsset(assetPath: String, withGaze: Boolean = false) {
    if (BitShape.current == "circle" && !withGaze) {
        setAnimation(assetPath)
    } else {
        setAnimationFromJson(
            BitShape.jsonFor(context, assetPath, withGaze),
            BitShape.cacheKey(assetPath, withGaze)
        )
    }
}
