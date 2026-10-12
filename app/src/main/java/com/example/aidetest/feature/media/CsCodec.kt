package com.example.aidetest

import java.util.UUID

/**
 * Codec proyek Foto Studio (format v3) di atas pohon Map/List murni. org.json hanya dipakai
 * di lapisan Android (CsStore) untuk mengubah teks JSON ↔ pohon, sehingga seluruh aturan
 * format + migrasi bisa diuji di JVM (CsCodecTest).
 *
 * Kompatibilitas:
 *  - v2 (Design Studio): semua kunci lama tetap dibaca dengan arti yang sama. Bayangan v2 tidak
 *    punya offset dan dulu digambar blur/3 ke kanan-bawah → dimigrasi ke shadowDx/shadowDy.
 *  - Versi tanpa "v" dianggap v2; versi lebih baru dibaca sebisanya (kunci tak dikenal diabaikan).
 */
internal object CsCodec {
    const val VERSION = 3
    const val MAX_CANVAS = 4096

    val BLENDS = setOf("normal", "multiply", "screen", "overlay", "darken", "lighten", "add")
    val BG_MODES = setOf("solid", "gradient", "transparent")
    private val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")

    // ------------------------------------------------------------------ pembaca nilai pohon

    /** Angka dari org.json bisa Int/Long/Double (atau String bila disunting manual). */
    fun f(m: Map<String, Any?>?, k: String, def: Float): Float {
        val d = when (val v = m?.get(k)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: return def
            else -> return def
        }
        return if (d.isNaN() || d.isInfinite()) def else d.toFloat()
    }

    /** Integer; Long dipotong ke Int sehingga warna ARGB yang tersimpan sebagai Long tetap benar. */
    fun i(m: Map<String, Any?>?, k: String, def: Int): Int = when (val v = m?.get(k)) {
        is Number -> v.toLong().toInt()
        is String -> v.toLongOrNull()?.toInt() ?: def
        else -> def
    }

    fun b(m: Map<String, Any?>?, k: String, def: Boolean): Boolean = when (val v = m?.get(k)) {
        is Boolean -> v
        is String -> when {
            v.equals("true", ignoreCase = true) -> true
            v.equals("false", ignoreCase = true) -> false
            else -> def
        }
        else -> def
    }

    fun s(m: Map<String, Any?>?, k: String, def: String): String = (m?.get(k) as? String) ?: def

    @Suppress("UNCHECKED_CAST")
    fun asMap(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    fun obj(m: Map<String, Any?>?, k: String): Map<String, Any?>? = asMap(m?.get(k))

    fun list(m: Map<String, Any?>?, k: String): List<*>? = m?.get(k) as? List<*>

    private fun floats(l: List<*>?): List<Float> =
        l?.mapNotNull { v -> (v as? Number)?.toFloat()?.takeIf { !it.isNaN() && !it.isInfinite() } } ?: emptyList()

    /** org.json menolak NaN/Infinity — semua float lewat sini sebelum ditulis. */
    private fun fin(v: Float): Double = if (v.isNaN() || v.isInfinite()) 0.0 else v.toDouble()

    // ------------------------------------------------------------------ dokumen

    fun encodeDoc(d: CsDocData, savedAt: Long): Map<String, Any?> = linkedMapOf(
        "v" to VERSION,
        "app" to "creativestudio",
        "name" to d.name,
        "w" to d.w,
        "h" to d.h,
        "bg" to d.bg.color,
        "bgMode" to d.bg.mode,
        "bg2" to d.bg.color2,
        "bgAngle" to fin(d.bg.angle),
        "savedAt" to savedAt,
        "elements" to d.elements.map { encodeElement(it) }
    )

    /** [resolveImage] mengubah nama file gambar → path absolut di folder gambar aplikasi. */
    fun decodeDoc(m: Map<String, Any?>, resolveImage: (String) -> String): CsDocData {
        val version = i(m, "v", 2)
        val bg = CsBackground(
            mode = s(m, "bgMode", "solid").takeIf { it in BG_MODES } ?: "solid",
            color = i(m, "bg", -1),
            color2 = i(m, "bg2", 0xFF000000.toInt()),
            angle = f(m, "bgAngle", 90f)
        )
        val els = ArrayList<CsElement>()
        val seen = HashSet<String>()
        list(m, "elements")?.forEach { raw ->
            val em = asMap(raw) ?: return@forEach
            var el = decodeElement(em, version, resolveImage)
            // Id ganda (file disunting manual / rusak) → id baru agar seleksi & undo tetap benar.
            if (!seen.add(el.id)) el = el.copy(id = UUID.randomUUID().toString()).also { seen.add(it.id) }
            els.add(el)
        }
        return CsDocData(
            name = s(m, "name", "Desain").ifBlank { "Desain" },
            w = i(m, "w", 1080).coerceIn(16, MAX_CANVAS),
            h = i(m, "h", 1080).coerceIn(16, MAX_CANVAS),
            bg = bg,
            elements = els
        )
    }

    /** Nama file gambar dan id mask yang dirujuk sebuah dokumen tersimpan (untuk hapus file yatim). */
    fun fileRefs(m: Map<String, Any?>, images: MutableSet<String>, masks: MutableSet<String>) {
        list(m, "elements")?.forEach { raw ->
            val em = asMap(raw) ?: return@forEach
            s(em, "imagePath", "").takeIf { it.isNotBlank() }?.let { images.add(it.substringAfterLast('/')) }
            s(em, "maskId", "").takeIf { SAFE_ID.matches(it) }?.let { masks.add(it) }
        }
    }

    // ------------------------------------------------------------------ elemen

    fun encodeElement(e: CsElement): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "id" to e.id,
            "type" to e.type.name,
            "x" to fin(e.x), "y" to fin(e.y), "w" to fin(e.w), "h" to fin(e.h),
            "rotation" to fin(e.rotation),
            "opacity" to fin(e.opacity),
            "blend" to e.blend,
            "visible" to e.visible,
            "locked" to e.locked,
            "color" to e.color,
            "gradient" to e.gradient,
            "color2" to e.color2,
            "gradAngle" to fin(e.gradAngle),
            "strokeColor" to e.strokeColor,
            "strokeWidth" to fin(e.strokeWidth),
            "cornerRadius" to fin(e.cornerRadius),
            "shadow" to e.shadow,
            "shadowBlur" to fin(e.shadowBlur),
            "shadowColor" to e.shadowColor,
            "shadowDx" to fin(e.shadowDx),
            "shadowDy" to fin(e.shadowDy),
            "text" to e.text,
            "textSize" to fin(e.textSize),
            "font" to e.font,
            "bold" to e.bold,
            "italic" to e.italic,
            "underline" to e.underline,
            "align" to e.align.name,
            "letterSpacing" to fin(e.letterSpacing),
            "lineSpacing" to fin(e.lineSpacing),
            "textBg" to e.textBg,
            "textBgPad" to fin(e.textBgPad)
        )
        e.groupId?.let { m["groupId"] = it }
        if (e.name.isNotEmpty()) m["name"] = e.name
        if (e.icon.isNotEmpty()) m["icon"] = e.icon
        if (e.role.isNotEmpty()) m["role"] = e.role
        if (e.type == CsType.IMAGE) {
            // Hanya nama file: folder aplikasi bisa berubah (pindah perangkat / pulihkan backup).
            e.imagePath?.let { m["imagePath"] = it.substringAfterLast('/') }
            m["srcW"] = e.srcW
            m["srcH"] = e.srcH
            if (e.edit != PeEdit()) m["edit"] = encodeEdit(e.edit)
            e.maskId?.let { m["maskId"] = it }
            m["cut"] = encodeCut(e.cut)
            m["fitCanvas"] = e.fitCanvas
        }
        return m
    }

    fun decodeElement(m: Map<String, Any?>, version: Int, resolveImage: (String) -> String): CsElement {
        val type = runCatching { CsType.valueOf(s(m, "type", "RECT")) }.getOrDefault(CsType.RECT)
        val blur = f(m, "shadowBlur", 12f).coerceIn(0f, 400f)
        // v2 menggambar bayangan dengan offset blur/3; v3 menyimpan offset eksplisit.
        val defaultOffset = if (version < 3) blur / 3f else 4f
        return CsElement(
            id = s(m, "id", "").ifBlank { UUID.randomUUID().toString() },
            type = type,
            x = f(m, "x", 0f),
            y = f(m, "y", 0f),
            w = f(m, "w", 100f).coerceAtLeast(1f),
            h = f(m, "h", 100f).coerceAtLeast(1f),
            rotation = f(m, "rotation", 0f),
            opacity = f(m, "opacity", 1f).coerceIn(0f, 1f),
            blend = s(m, "blend", "normal").takeIf { it in BLENDS } ?: "normal",
            visible = b(m, "visible", true),
            locked = b(m, "locked", false),
            groupId = s(m, "groupId", "").ifBlank { null },
            name = s(m, "name", ""),
            color = i(m, "color", -1),
            gradient = b(m, "gradient", false),
            color2 = i(m, "color2", 0xFF808080.toInt()),
            gradAngle = f(m, "gradAngle", 90f),
            strokeColor = i(m, "strokeColor", 0),
            strokeWidth = f(m, "strokeWidth", 0f).coerceIn(0f, 400f),
            cornerRadius = f(m, "cornerRadius", 0f).coerceAtLeast(0f),
            shadow = b(m, "shadow", false),
            shadowBlur = blur,
            shadowColor = i(m, "shadowColor", 0x96000000.toInt()),
            shadowDx = f(m, "shadowDx", defaultOffset),
            shadowDy = f(m, "shadowDy", defaultOffset),
            text = s(m, "text", ""),
            textSize = f(m, "textSize", 48f).coerceIn(1f, 2000f),
            font = s(m, "font", "sans").ifBlank { "sans" },
            bold = b(m, "bold", false),
            italic = b(m, "italic", false),
            underline = b(m, "underline", false),
            align = runCatching { CsAlign.valueOf(s(m, "align", "LEFT")) }.getOrDefault(CsAlign.LEFT),
            letterSpacing = f(m, "letterSpacing", 0f).coerceIn(-1f, 2f),
            lineSpacing = f(m, "lineSpacing", 1.2f).coerceIn(0.5f, 4f),
            textBg = i(m, "textBg", 0),
            textBgPad = f(m, "textBgPad", 0.3f).coerceIn(0f, 2f),
            icon = s(m, "icon", ""),
            imagePath = s(m, "imagePath", "").ifBlank { null }?.let { resolveImage(it.substringAfterLast('/')) },
            srcW = i(m, "srcW", 0).coerceAtLeast(0),
            srcH = i(m, "srcH", 0).coerceAtLeast(0),
            edit = decodeEdit(obj(m, "edit")),
            maskId = s(m, "maskId", "").takeIf { SAFE_ID.matches(it) },
            cut = decodeCut(obj(m, "cut")),
            fitCanvas = b(m, "fitCanvas", false),
            role = s(m, "role", "")
        )
    }

    // ------------------------------------------------------------------ potong latar

    fun encodeCut(c: CsCut): Map<String, Any?> = linkedMapOf(
        "on" to c.on, "method" to c.method, "smooth" to c.smooth, "shift" to c.shift,
        "feather" to c.feather, "decontam" to c.decontam, "tolerance" to c.tolerance
    )

    fun decodeCut(m: Map<String, Any?>?): CsCut {
        if (m == null) return CsCut()
        return CsCut(
            on = b(m, "on", false),
            method = s(m, "method", ""),
            smooth = i(m, "smooth", 30).coerceIn(0, 100),
            shift = i(m, "shift", 0).coerceIn(-12, 12),
            feather = i(m, "feather", 1).coerceIn(0, 24),
            decontam = b(m, "decontam", true),
            tolerance = i(m, "tolerance", 34).coerceIn(5, 120)
        )
    }

    // ------------------------------------------------------------------ edit foto (PeEdit)

    private fun flat(c: CurvePts): List<Double> = c.flatMap { listOf(fin(it.first), fin(it.second)) }

    fun encodeEdit(e: PeEdit): Map<String, Any?> {
        val a = e.adjust
        val g = e.geom
        return linkedMapOf(
            "adjust" to linkedMapOf<String, Any?>(
                "exposure" to fin(a.exposure), "contrast" to fin(a.contrast),
                "highlights" to fin(a.highlights), "shadows" to fin(a.shadows),
                "temperature" to fin(a.temperature), "tint" to fin(a.tint),
                "saturation" to fin(a.saturation), "vibrance" to fin(a.vibrance),
                "curveM" to flat(a.curveM), "curveR" to flat(a.curveR), "curveG" to flat(a.curveG), "curveB" to flat(a.curveB),
                "hslHue" to a.hslHue.map { fin(it) }, "hslSat" to a.hslSat.map { fin(it) }, "hslLum" to a.hslLum.map { fin(it) },
                "preset" to a.preset, "presetAmount" to fin(a.presetAmount),
                "sharpen" to fin(a.sharpen), "denoise" to fin(a.denoise), "clarity" to fin(a.clarity),
                "vignette" to fin(a.vignette), "grain" to fin(a.grain), "blur" to fin(a.blur), "bgBlur" to fin(a.bgBlur)
            ),
            "geom" to linkedMapOf<String, Any?>(
                "rot90" to g.rot90, "flipH" to g.flipH, "flipV" to g.flipV,
                "straighten" to fin(g.straighten), "perspV" to fin(g.perspV), "perspH" to fin(g.perspH),
                "cl" to fin(g.cl), "ct" to fin(g.ct), "cr" to fin(g.cr), "cb" to fin(g.cb),
                "ratio" to g.ratio
            ),
            "heals" to e.heals.map { h ->
                linkedMapOf<String, Any?>("pts" to h.pts.map { fin(it) }, "r" to fin(h.radius), "ox" to fin(h.offX), "oy" to fin(h.offY))
            }
        )
    }

    private fun curve(l: List<*>?): CurvePts {
        val v = floats(l)
        if (v.size < 4 || v.size % 2 != 0) return CURVE_IDENTITY
        val pts = (0 until v.size / 2).map { v[it * 2].coerceIn(0f, 1f) to v[it * 2 + 1].coerceIn(0f, 1f) }.sortedBy { it.first }
        return if (PeCurves.isIdentity(pts)) CURVE_IDENTITY else pts
    }

    private fun eight(l: List<*>?): List<Float> {
        val v = floats(l)
        return if (v.size == 8) v.map { it.coerceIn(-1f, 1f) } else List(8) { 0f }
    }

    fun decodeEdit(m: Map<String, Any?>?): PeEdit {
        if (m == null) return PeEdit()
        val a = obj(m, "adjust")
        val g = obj(m, "geom")
        val adjust = if (a == null) PeAdjust() else PeAdjust(
            exposure = f(a, "exposure", 0f).coerceIn(-2f, 2f),
            contrast = f(a, "contrast", 0f).coerceIn(-1f, 1f),
            highlights = f(a, "highlights", 0f).coerceIn(-1f, 1f),
            shadows = f(a, "shadows", 0f).coerceIn(-1f, 1f),
            temperature = f(a, "temperature", 0f).coerceIn(-1f, 1f),
            tint = f(a, "tint", 0f).coerceIn(-1f, 1f),
            saturation = f(a, "saturation", 0f).coerceIn(-1f, 1f),
            vibrance = f(a, "vibrance", 0f).coerceIn(-1f, 1f),
            curveM = curve(list(a, "curveM")),
            curveR = curve(list(a, "curveR")),
            curveG = curve(list(a, "curveG")),
            curveB = curve(list(a, "curveB")),
            hslHue = eight(list(a, "hslHue")),
            hslSat = eight(list(a, "hslSat")),
            hslLum = eight(list(a, "hslLum")),
            preset = s(a, "preset", "asli").takeIf { k -> PePresets.list.any { it.first == k } } ?: "asli",
            presetAmount = f(a, "presetAmount", 1f).coerceIn(0f, 1f),
            sharpen = f(a, "sharpen", 0f).coerceIn(0f, 1f),
            denoise = f(a, "denoise", 0f).coerceIn(0f, 1f),
            clarity = f(a, "clarity", 0f).coerceIn(-1f, 1f),
            vignette = f(a, "vignette", 0f).coerceIn(-1f, 1f),
            grain = f(a, "grain", 0f).coerceIn(0f, 1f),
            blur = f(a, "blur", 0f).coerceIn(0f, 1f),
            bgBlur = f(a, "bgBlur", 0f).coerceIn(0f, 1f)
        )
        val geom = if (g == null) PeGeom() else {
            var cl = f(g, "cl", 0f).coerceIn(0f, 1f)
            var ct = f(g, "ct", 0f).coerceIn(0f, 1f)
            var cr = f(g, "cr", 1f).coerceIn(0f, 1f)
            var cb = f(g, "cb", 1f).coerceIn(0f, 1f)
            if (cr - cl < 0.01f) { cl = 0f; cr = 1f }
            if (cb - ct < 0.01f) { ct = 0f; cb = 1f }
            PeGeom(
                rot90 = ((i(g, "rot90", 0) % 4) + 4) % 4,
                flipH = b(g, "flipH", false),
                flipV = b(g, "flipV", false),
                straighten = f(g, "straighten", 0f).coerceIn(-45f, 45f),
                perspV = f(g, "perspV", 0f).coerceIn(-1f, 1f),
                perspH = f(g, "perspH", 0f).coerceIn(-1f, 1f),
                cl = cl, ct = ct, cr = cr, cb = cb,
                ratio = s(g, "ratio", "bebas")
            )
        }
        val heals = list(m, "heals")?.mapNotNull { raw ->
            val hm = asMap(raw) ?: return@mapNotNull null
            val pts = floats(list(hm, "pts"))
            if (pts.size < 2 || pts.size % 2 != 0) null
            else PeHeal(pts, f(hm, "r", 0.01f).coerceIn(0.0005f, 0.5f), f(hm, "ox", 0f).coerceIn(-2f, 2f), f(hm, "oy", 0f).coerceIn(-2f, 2f))
        } ?: emptyList()
        return PeEdit(adjust = adjust, geom = geom, heals = heals)
    }
}
