package com.example.aidetest

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.util.Locale

/** Satu baris aplikasi. Semua field diambil dari PackageManager; yang tidak tersedia diberi nilai bawaan. */
internal class AppRow(
    val name: String,
    val pkg: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val apkBytes: Long,
    val splitCount: Int
)

private val appManagerFilterLabels = arrayOf("Semua aplikasi", "Aplikasi pengguna", "Aplikasi sistem")

private fun MainActivity.loadAppRows(): List<AppRow> {
    val pm = packageManager
    @Suppress("DEPRECATION")
    val infos = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
    val rows = ArrayList<AppRow>(infos.size)
    for (ai in infos) {
        val name = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
        val pi = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ai.packageName, 0)
        }.getOrNull()
        val splits = ai.splitSourceDirs ?: emptyArray()
        val apkBytes = runCatching {
            File(ai.sourceDir).length() + splits.sumOf { File(it).length() }
        }.getOrDefault(0L)
        rows.add(
            AppRow(
                name = name,
                pkg = ai.packageName,
                versionName = pi?.versionName ?: "",
                versionCode = pi?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                apkBytes = apkBytes,
                splitCount = splits.size
            )
        )
    }
    return rows.sortedBy { it.name.lowercase(Locale.getDefault()) }
}

private fun AppRow.typeLabel(): String = when {
    isSystem && isUpdatedSystem -> "Sistem (sudah diperbarui)"
    isSystem -> "Sistem"
    else -> "Pengguna"
}

private fun AppRow.versionLabel(): String =
    if (versionName.isBlank() && versionCode == 0L) "Versi tidak tersedia"
    else "Versi ${versionName.ifBlank { "?" }} (kode $versionCode)"

internal fun MainActivity.appManagerTool() {
    clearPage("App Manager", true)
    toolWorkspace(
        "App Manager",
        "Lihat aplikasi yang bisa dibaca GITLS dan buka halaman detailnya di pengaturan Android.",
        "tools"
    )
    content.addView(
        infoCard(
            "Batas yang perlu diketahui",
            "• Android hanya menampilkan sebagian aplikasi: aplikasi yang punya ikon di layar utama, plus GITLS sendiri. " +
                "Aplikasi sistem tanpa ikon atau yang disembunyikan tidak terlihat.\n" +
                "• Ukuran yang tampil adalah ukuran file APK saja. Data, cache, dan media aplikasi belum termasuk, jadi ukuran sebenarnya di HP bisa jauh lebih besar.\n" +
                "• Di sini GITLS tidak mencopot atau menghapus data aplikasi. Untuk itu, gunakan halaman detail di pengaturan Android."
        )
    )

    val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val countLabel = subLabel("Memuat daftar aplikasi…", 12f).apply { setPadding(dp(2), dp(10), 0, dp(6)) }
    var rows: List<AppRow> = emptyList()

    fun render() {
        listBox.removeAllViews()
        val q = appManagerQuery.trim().lowercase(Locale.getDefault())
        val shown = rows.filter { r ->
            val typeOk = when (appManagerFilter) {
                1 -> !r.isSystem
                2 -> r.isSystem
                else -> true
            }
            typeOk && (q.isEmpty() || r.name.lowercase(Locale.getDefault()).contains(q) || r.pkg.lowercase(Locale.ROOT).contains(q))
        }
        countLabel.text = "${shown.size} dari ${rows.size} aplikasi yang terbaca"
        if (shown.isEmpty()) {
            listBox.addView(infoCard("Tidak ada aplikasi yang cocok", "Ubah filter jenis atau kata pencarian."))
            return
        }
        shown.forEach { r ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rippleBg(panel2, Ds.RADIUS_MD, line)
                isClickable = true
                isFocusable = true
                setOnClickListener { showAppDetail(r) }
            }
            card.addView(label(r.name, 14f, true))
            card.addView(subLabel(r.pkg, 11f))
            card.addView(subLabel("${r.versionLabel()} • ${r.typeLabel()} • APK ${bytesText(r.apkBytes)}", 11f))
            listBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    val search = edit("Cari nama atau nama paket").apply {
        setText(appManagerQuery)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                appManagerQuery = s?.toString().orEmpty()
                render()
            }
        })
    }
    content.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    val filterBtn = button("Jenis: ${appManagerFilterLabels[appManagerFilter]}") {}
    styleAsSecondary(filterBtn)
    filterBtn.setOnClickListener {
        AlertDialog.Builder(this)
            .setTitle("Tampilkan")
            .setItems(appManagerFilterLabels) { _, which ->
                appManagerFilter = which
                filterBtn.text = "Jenis: ${appManagerFilterLabels[which]}"
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
    content.addView(filterBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    content.addView(countLabel)
    content.addView(listBox)

    toolThread("Memuat aplikasi…") {
        val loaded = runCatching { loadAppRows() }.getOrDefault(emptyList())
        runOnToolUiThread {
            rows = loaded
            render()
        }
    }
}

private fun MainActivity.showAppDetail(r: AppRow) {
    val msg = buildString {
        appendLine("Paket: ${r.pkg}")
        appendLine(r.versionLabel())
        appendLine("Jenis: ${r.typeLabel()}")
        append("Ukuran APK: ${bytesText(r.apkBytes)}")
        if (r.splitCount > 0) append(" (termasuk ${r.splitCount} split APK)")
        appendLine()
        appendLine()
        append("Ukuran ini belum mencakup data, cache, dan media aplikasi.")
        if (r.isSystem) {
            appendLine()
            append("Ini aplikasi sistem. Mengubahnya bisa mengganggu fungsi HP, jadi hanya detail dan pengaturan resmi Android yang tersedia.")
        }
    }
    val builder = AlertDialog.Builder(this)
        .setTitle(r.name)
        .setMessage(msg)
        .setPositiveButton("Buka detail di pengaturan Android") { _, _ ->
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.pkg}")))
            }.onFailure { toast("Halaman detail aplikasi tidak bisa dibuka") }
        }
        .setNegativeButton("Tutup", null)
    val launch = if (r.pkg != packageName) packageManager.getLaunchIntentForPackage(r.pkg) else null
    if (launch != null) {
        builder.setNeutralButton("Buka aplikasi") { _, _ ->
            runCatching { startActivity(launch) }.onFailure { toast("Aplikasi tidak bisa dibuka") }
        }
    }
    builder.show()
}
