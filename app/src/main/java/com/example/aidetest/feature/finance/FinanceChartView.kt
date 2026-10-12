package com.example.aidetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * Grafik keuangan monokrom (Canvas, tanpa library).
 *  - [setGroups]: batang vertikal berkelompok (mis. pemasukan vs pengeluaran 6 bulan, bulan ini vs lalu).
 *    Seri 0 = blok solid (tinta), seri 1 = abu-abu bergaris tepi; setiap batang diberi label nilai.
 *  - [setRows]: batang horizontal berlabel (kategori: nama · nilai · persen).
 */
class FinanceChartView(context: Context) : View(context) {
    data class Group(val label: String, val values: List<Long>)
    data class Row(val label: String, val value: Long, val caption: String)

    private enum class Mode { GROUPS, ROWS }

    private var mode = Mode.GROUPS
    private var groups: List<Group> = emptyList()
    private var series: List<String> = emptyList()
    private var rows: List<Row> = emptyList()
    private var highlight = -1

    var ink: Int = Color.BLACK
    var muted: Int = Color.GRAY
    var grid: Int = Color.LTGRAY
    var secondFill: Int = Color.rgb(170, 170, 176)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setGroups(series: List<String>, groups: List<Group>, highlightIndex: Int = -1) {
        mode = Mode.GROUPS
        this.series = series
        this.groups = groups
        this.highlight = highlightIndex
        requestLayout(); invalidate()
    }

    fun setRows(rows: List<Row>) {
        mode = Mode.ROWS
        this.rows = rows
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = when (mode) {
            Mode.GROUPS -> dp(210f)
            Mode.ROWS -> dp(8f) + rows.size.coerceAtLeast(1) * dp(40f)
        }
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.GROUPS -> drawGroups(canvas)
            Mode.ROWS -> drawRows(canvas)
        }
    }

    private fun seriesPaint(i: Int, c: Canvas, r: RectF) {
        val radius = dp(3f)
        if (i == 0) {
            fill.color = ink
            c.drawRoundRect(r, radius, radius, fill)
        } else {
            fill.color = secondFill
            c.drawRoundRect(r, radius, radius, fill)
            stroke.color = ink; stroke.strokeWidth = dp(1f)
            c.drawRoundRect(r, radius, radius, stroke)
        }
    }

    private fun niceMax(v: Long): Long {
        if (v <= 0L) return 1L
        var mag = 1L
        while (mag * 10 <= v && mag < Long.MAX_VALUE / 100) mag *= 10
        for (m in longArrayOf(1, 2, 5, 10)) if (mag * m >= v) return mag * m
        return mag * 10
    }

    private fun drawGroups(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Legenda
        text.textSize = sp(11f); text.color = muted; text.isFakeBoldText = false
        var lx = 0f
        val legendY = dp(12f)
        series.forEachIndexed { i, name ->
            rect.set(lx, legendY - dp(9f), lx + dp(10f), legendY + dp(1f))
            seriesPaint(i, c, rect)
            text.color = ink
            c.drawText(name, lx + dp(14f), legendY, text)
            lx += dp(14f) + text.measureText(name) + dp(16f)
        }
        val maxV = niceMax(groups.flatMap { it.values }.maxOrNull() ?: 0L)
        val axisW = dp(40f)
        val top = dp(36f)
        val bottom = h - dp(22f)
        val plotH = bottom - top
        // Garis bantu + label sumbu
        text.textSize = sp(9.5f); text.color = muted
        stroke.color = grid; stroke.strokeWidth = dp(1f)
        for (k in 0..2) {
            val v = maxV * k / 2
            val y = bottom - plotH * k / 2f
            c.drawLine(axisW, y, w, y, stroke)
            c.drawText(FinMoney.compact(v), 0f, y + dp(3f), text)
        }
        if (groups.isEmpty()) return
        val slot = (w - axisW) / groups.size
        val n = series.size.coerceAtLeast(1)
        val barW = ((slot - dp(10f)) / n).coerceAtMost(dp(26f))
        groups.forEachIndexed { gi, g ->
            val gx = axisW + slot * gi
            val start = gx + (slot - barW * n) / 2f
            g.values.forEachIndexed { si, v ->
                val bh = if (v <= 0L) 0f else (plotH * (v.toDouble() / maxV.toDouble())).toFloat().coerceAtLeast(dp(2f))
                rect.set(start + barW * si + dp(1f), bottom - bh, start + barW * (si + 1) - dp(1f), bottom)
                if (bh > 0f) seriesPaint(si, c, rect)
                // Nilai di atas batang. Grafik padat (>3 kelompok) hanya memberi label kelompok yang disorot
                // agar tidak bertumpuk; nilai lengkap ditampilkan sebagai tabel di bawah grafik.
                if (groups.size > 3 && gi != highlight) return@forEachIndexed
                text.textSize = sp(8.5f); text.color = ink; text.isFakeBoldText = false
                val label = if (v == 0L) "0" else FinMoney.compact(v)
                val tw = text.measureText(label)
                val cx = (rect.left + rect.right) / 2f
                c.drawText(label, (cx - tw / 2f).coerceIn(axisW, w - tw), bottom - bh - dp(3f), text)
            }
            text.textSize = sp(10.5f)
            text.isFakeBoldText = gi == highlight
            text.color = if (gi == highlight) ink else muted
            val tw = text.measureText(g.label)
            c.drawText(g.label, gx + (slot - tw) / 2f, h - dp(6f), text)
        }
        text.isFakeBoldText = false
    }

    private fun drawRows(c: Canvas) {
        val w = width.toFloat()
        if (rows.isEmpty()) return
        val maxV = rows.maxOf { it.value }.coerceAtLeast(1L)
        val rowH = dp(40f)
        rows.forEachIndexed { i, r ->
            val y0 = dp(4f) + rowH * i
            text.textSize = sp(12f); text.color = ink; text.isFakeBoldText = true
            val cap = r.caption
            text.isFakeBoldText = false
            val capW = text.measureText(cap)
            text.isFakeBoldText = true
            var label = r.label
            while (label.length > 3 && text.measureText(label) > w - capW - dp(12f)) label = label.dropLast(2) + "…"
            c.drawText(label, 0f, y0 + dp(14f), text)
            text.isFakeBoldText = false
            c.drawText(cap, w - capW, y0 + dp(14f), text)
            val trackTop = y0 + dp(21f)
            rect.set(0f, trackTop, w, trackTop + dp(8f))
            fill.color = grid
            c.drawRoundRect(rect, dp(4f), dp(4f), fill)
            val fw = (w * (r.value.toDouble() / maxV.toDouble())).toFloat().coerceAtLeast(if (r.value > 0) dp(4f) else 0f)
            rect.set(0f, trackTop, fw, trackTop + dp(8f))
            fill.color = ink
            if (fw > 0f) c.drawRoundRect(rect, dp(4f), dp(4f), fill)
        }
    }
}
