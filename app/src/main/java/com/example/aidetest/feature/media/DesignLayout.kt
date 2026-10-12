package com.example.aidetest

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometri murni Design Studio (tanpa dependensi Android, diuji di DesignLayoutTest):
 *  - kotak pembatas visual untuk elemen berotasi
 *  - snap pintar ke tengah/tepi kanvas, objek lain, dan grid (dengan garis panduan)
 *  - perataan (kiri/tengah/kanan/atas/tengah/bawah) dan distribusi jarak rata
 *  - ubah ukuran dari sudut dengan sudut berlawanan tetap (mendukung rotasi)
 *
 * Semua koordinat dalam piksel kanvas (bukan layar).
 */
internal data class DsBox(val x: Float, val y: Float, val w: Float, val h: Float, val rotation: Float = 0f) {
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f

    /** Kotak pembatas sejajar sumbu (AABB) dari kotak setelah dirotasi pada pusatnya. */
    fun bounds(): DsRect {
        if (abs(rotation % 360f) < 0.01f) return DsRect(x, y, x + w, y + h)
        val r = Math.toRadians(rotation.toDouble())
        val c = abs(cos(r)).toFloat()
        val s = abs(sin(r)).toFloat()
        val bw = w * c + h * s
        val bh = w * s + h * c
        return DsRect(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
    }
}

internal data class DsRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f

    fun union(o: DsRect) = DsRect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
}

/** Jenis garis panduan: tengah kanvas ditandai khusus agar mudah dikenali. */
internal enum class DsGuideKind { CANVAS_CENTER, CANVAS_EDGE, OBJECT, GRID }

internal data class DsGuide(val vertical: Boolean, val pos: Float, val kind: DsGuideKind)

/** Hasil snap: geser tambahan (dx, dy) dan garis panduan yang aktif. */
internal data class DsSnap(val dx: Float, val dy: Float, val guides: List<DsGuide>) {
    val snappedX: Boolean get() = guides.any { it.vertical }
    val snappedY: Boolean get() = guides.any { !it.vertical }
}

internal enum class DsAlign { LEFT, H_CENTER, RIGHT, TOP, V_CENTER, BOTTOM }

internal object DesignLayout {

    /**
     * Snap kotak [moving] (AABB visual) terhadap kanvas, objek lain, dan grid.
     * @param threshold jarak maksimum (px kanvas) agar snap aktif — biasanya 8dp dibagi skala zoom.
     * @param gridStep  ukuran grid (px) atau 0 bila snap grid mati.
     */
    fun snapMove(
        moving: DsRect,
        others: List<DsRect>,
        canvasW: Float,
        canvasH: Float,
        threshold: Float,
        smart: Boolean,
        gridStep: Float
    ): DsSnap {
        val guides = ArrayList<DsGuide>(4)
        val sx = if (smart) bestAxis(
            floatArrayOf(moving.left, moving.cx, moving.right),
            axisTargets(canvasW, others, vertical = true),
            threshold
        ) else null
        val sy = if (smart) bestAxis(
            floatArrayOf(moving.top, moving.cy, moving.bottom),
            axisTargets(canvasH, others, vertical = false),
            threshold
        ) else null

        var dx = 0f
        var dy = 0f
        if (sx != null) {
            dx = sx.first
            // Tampilkan semua garis yang segaris setelah snap (mis. tengah kanvas + tepi objek lain).
            addMatchingGuides(guides, true, floatArrayOf(moving.left + dx, moving.cx + dx, moving.right + dx), canvasW, others)
        } else if (gridStep > 0f) {
            dx = Math.round(moving.left / gridStep) * gridStep - moving.left
        }
        if (sy != null) {
            dy = sy.first
            addMatchingGuides(guides, false, floatArrayOf(moving.top + dy, moving.cy + dy, moving.bottom + dy), canvasH, others)
        } else if (gridStep > 0f) {
            dy = Math.round(moving.top / gridStep) * gridStep - moving.top
        }
        return DsSnap(dx, dy, guides)
    }

    private class Target(val pos: Float, val kind: DsGuideKind)

    private fun axisTargets(size: Float, others: List<DsRect>, vertical: Boolean): List<Target> {
        val t = ArrayList<Target>(3 + others.size * 3)
        // Tengah kanvas diprioritaskan (urutan pertama menang saat jarak sama).
        t.add(Target(size / 2f, DsGuideKind.CANVAS_CENTER))
        t.add(Target(0f, DsGuideKind.CANVAS_EDGE))
        t.add(Target(size, DsGuideKind.CANVAS_EDGE))
        for (o in others) {
            if (vertical) {
                t.add(Target(o.left, DsGuideKind.OBJECT)); t.add(Target(o.cx, DsGuideKind.OBJECT)); t.add(Target(o.right, DsGuideKind.OBJECT))
            } else {
                t.add(Target(o.top, DsGuideKind.OBJECT)); t.add(Target(o.cy, DsGuideKind.OBJECT)); t.add(Target(o.bottom, DsGuideKind.OBJECT))
            }
        }
        return t
    }

    /** @return (offset, target) terbaik dengan |offset| <= threshold, atau null. */
    private fun bestAxis(edges: FloatArray, targets: List<Target>, threshold: Float): Pair<Float, Target>? {
        var best: Pair<Float, Target>? = null
        var bestAbs = threshold + 1e-4f
        for (t in targets) for (e in edges) {
            val d = t.pos - e
            val ad = abs(d)
            if (ad < bestAbs - 1e-4f) {
                bestAbs = ad
                best = d to t
            }
        }
        return best
    }

    private fun addMatchingGuides(out: MutableList<DsGuide>, vertical: Boolean, edges: FloatArray, size: Float, others: List<DsRect>) {
        val eps = 0.5f
        val seen = HashSet<Int>()
        for (t in axisTargets(size, others, vertical)) {
            if (edges.any { abs(it - t.pos) <= eps } && seen.add(Math.round(t.pos * 2f))) {
                out.add(DsGuide(vertical, t.pos, t.kind))
            }
        }
    }

    /**
     * Geser (dx, dy) per kotak agar rata. Satu kotak → rata terhadap kanvas;
     * beberapa kotak → rata terhadap kotak gabungan seleksi.
     */
    fun align(boxes: List<DsRect>, mode: DsAlign, canvasW: Float, canvasH: Float): List<Pair<Float, Float>> {
        if (boxes.isEmpty()) return emptyList()
        val ref = if (boxes.size == 1) DsRect(0f, 0f, canvasW, canvasH) else boxes.reduce { acc, r -> acc.union(r) }
        return boxes.map { b ->
            when (mode) {
                DsAlign.LEFT -> (ref.left - b.left) to 0f
                DsAlign.H_CENTER -> (ref.cx - b.cx) to 0f
                DsAlign.RIGHT -> (ref.right - b.right) to 0f
                DsAlign.TOP -> 0f to (ref.top - b.top)
                DsAlign.V_CENTER -> 0f to (ref.cy - b.cy)
                DsAlign.BOTTOM -> 0f to (ref.bottom - b.bottom)
            }
        }
    }

    /** Geser (dx, dy) agar kotak gabungan seluruh seleksi tepat di tengah kanvas. */
    fun centerGroup(boxes: List<DsRect>, canvasW: Float, canvasH: Float, horizontal: Boolean, vertical: Boolean): Pair<Float, Float> {
        if (boxes.isEmpty()) return 0f to 0f
        val u = boxes.reduce { acc, r -> acc.union(r) }
        return (if (horizontal) canvasW / 2f - u.cx else 0f) to (if (vertical) canvasH / 2f - u.cy else 0f)
    }

    /**
     * Distribusi jarak rata: objek pertama & terakhir (urut posisi) tetap, celah di antaranya disamakan.
     * @return geser per kotak dalam urutan input; kosong bila kurang dari 3 kotak.
     */
    fun distribute(boxes: List<DsRect>, horizontal: Boolean): List<Float> {
        if (boxes.size < 3) return emptyList()
        val order = boxes.indices.sortedBy { if (horizontal) boxes[it].left else boxes[it].top }
        val first = boxes[order.first()]
        val last = boxes[order.last()]
        val span = if (horizontal) last.right - first.left else last.bottom - first.top
        val total = boxes.sumOf { (if (horizontal) it.width else it.height).toDouble() }.toFloat()
        val gap = (span - total) / (boxes.size - 1)
        val out = FloatArray(boxes.size)
        var cursor = if (horizontal) first.left else first.top
        for (i in order) {
            val b = boxes[i]
            val start = if (horizontal) b.left else b.top
            out[i] = cursor - start
            cursor += (if (horizontal) b.width else b.height) + gap
        }
        return out.toList()
    }

    /** Titik dunia → koordinat lokal kotak (pusat = 0,0, tanpa rotasi). */
    fun toLocal(box: DsBox, px: Float, py: Float): Pair<Float, Float> {
        val r = Math.toRadians(-box.rotation.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val dx = px - box.cx
        val dy = py - box.cy
        return (dx * c - dy * s) to (dx * s + dy * c)
    }

    /** Koordinat lokal kotak → titik dunia. */
    fun toWorld(box: DsBox, lx: Float, ly: Float): Pair<Float, Float> {
        val r = Math.toRadians(box.rotation.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return (box.cx + lx * c - ly * s) to (box.cy + lx * s + ly * c)
    }

    fun hit(box: DsBox, px: Float, py: Float, tolerance: Float): Boolean {
        val (lx, ly) = toLocal(box, px, py)
        return abs(lx) <= box.w / 2f + tolerance && abs(ly) <= box.h / 2f + tolerance
    }

    /**
     * Ubah ukuran dari sudut ([sx], [sy] = ±1 menunjuk sudut yang ditarik) ke titik dunia (px, py);
     * sudut berlawanan tetap di tempat. [uniform] menjaga rasio aspek (teks/gambar).
     */
    fun resizeFromCorner(start: DsBox, sx: Int, sy: Int, px: Float, py: Float, uniform: Boolean, minSize: Float): DsBox {
        val fixed = toWorld(start, -sx * start.w / 2f, -sy * start.h / 2f)
        val r = Math.toRadians(-start.rotation.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val vx = px - fixed.first
        val vy = py - fixed.second
        val lx = (vx * c - vy * s) * sx
        val ly = (vx * s + vy * c) * sy
        var w: Float
        var h: Float
        if (uniform && start.w > 0f && start.h > 0f) {
            val k = ((lx * start.w + ly * start.h) / (start.w * start.w + start.h * start.h))
                .coerceAtLeast(minSize / minOf(start.w, start.h))
            w = start.w * k
            h = start.h * k
        } else {
            w = lx.coerceAtLeast(minSize)
            h = ly.coerceAtLeast(minSize)
        }
        val rr = Math.toRadians(start.rotation.toDouble())
        val cc = cos(rr).toFloat()
        val ss = sin(rr).toFloat()
        val hx = sx * w / 2f
        val hy = sy * h / 2f
        val ncx = fixed.first + hx * cc - hy * ss
        val ncy = fixed.second + hx * ss + hy * cc
        return DsBox(ncx - w / 2f, ncy - h / 2f, w, h, start.rotation)
    }

    /** Sudut (derajat) dari pusat kotak ke titik; 0° = arah atas. Snap ke kelipatan 45° dalam [snapDeg]. */
    fun rotationTo(box: DsBox, px: Float, py: Float, snapDeg: Float): Float {
        val deg = Math.toDegrees(kotlin.math.atan2((px - box.cx).toDouble(), -(py - box.cy).toDouble())).toFloat()
        var norm = ((deg % 360f) + 360f) % 360f
        val nearest = Math.round(norm / 45f) * 45f
        if (abs(norm - nearest) <= snapDeg) norm = nearest % 360f
        return if (norm > 180f) norm - 360f else norm
    }
}
