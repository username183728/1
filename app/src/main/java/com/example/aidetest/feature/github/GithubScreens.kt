package com.example.aidetest

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.example.aidetest.GhActionJob
import com.example.aidetest.GhCancelException
import com.example.aidetest.GhField
import com.example.aidetest.GhProgress
import com.example.aidetest.GhResult
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.*
import java.util.*
import java.util.zip.*
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Layar & dialog GitHub (pengaturan, browser repo, progress, hasil, Actions, hapus).
 * Dipindah dari MainActivity (modularisasi bertahap, Step 16). State `gh*` tetap
 * menjadi field MainActivity karena extension tidak dapat memiliki backing field.
 */

// ----- warna & helper kecil (mengikuti tema terang/gelap aplikasi) -----
internal fun MainActivity.ghc(dark: Long, light: Long): Int = (if (isDarkTheme) dark else light).toInt()

internal fun MainActivity.ghRound(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

/** Terapkan sudut tumpul konsisten pada dialog GitHub, termasuk panel dan inset konten. */
internal fun MainActivity.ghStyleRoundedDialog(dialog: AlertDialog, radiusDp: Int = 24): AlertDialog {
    dialog.window?.setBackgroundDrawable(ghRound(ghCard, radiusDp, ghStroke))
    dialog.window?.decorView?.setPadding(dp(2), dp(2), dp(2), dp(2))
    return dialog
}

internal fun MainActivity.ghText(text: String, sp: Float, color: Int = ghInk, bold: Boolean = false): TextView = TextView(this).apply {
    this.text = text
    textSize = sp
    setTextColor(color)
    includeFontPadding = false
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
}

internal fun MainActivity.ghIcon(name: String, sp: Float, color: Int = ghInk): MdiIconView =
    MdiIconView(this).apply { setIconName(name); setIconSize(sp); setTextColor(color) }

internal fun MainActivity.ghLogo(sizeDp: Int, color: Int): PublishLogoView =
    PublishLogoView(this).apply { this.color = color; layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) }

internal fun MainActivity.ghHideKeyboard(v: View) {
    runCatching {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
    }
}

internal fun MainActivity.ghWatch(edit: EditText, onChange: () -> Unit) {
    edit.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) { onChange() }
    })
}

internal fun MainActivity.ghFieldBg(focused: Boolean, error: Boolean): GradientDrawable =
    ghRound(ghCard, 16, if (error) ghDanger else if (focused) ghInk else ghStroke, if (focused || error) 2 else 1)

internal fun MainActivity.ghField(labelText: String, iconName: String, hint: String, initial: String, password: Boolean = false): GhField {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ghText(labelText, 13f, ghInk, true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(7); leftMargin = dp(2) })
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(10), 0)
        background = ghFieldBg(false, false)
    }
    row.addView(ghIcon(iconName, 20f, ghMuted), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) })
    val edit = EditText(this).apply {
        this.hint = hint
        setText(initial)
        textSize = 15f
        setTextColor(ghInk)
        setHintTextColor(ghMuted)
        background = null
        setPadding(0, 0, 0, 0)
        maxLines = 1
        setSingleLine(true)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    row.addView(edit, LinearLayout.LayoutParams(0, dp(50), 1f))
    if (password) {
        val eye = ghIcon("eye-outline", 20f, ghMuted).apply {
            isClickable = true
            setOnClickListener {
                val visible = edit.transformationMethod == null
                val cursor = edit.selectionStart
                if (visible) {
                    edit.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                    setIconName("eye-outline")
                } else {
                    edit.transformationMethod = null
                    setIconName("eye-off-outline")
                }
                edit.setSelection(cursor.coerceAtLeast(0).coerceAtMost(edit.text.length))
            }
        }
        row.addView(eye, LinearLayout.LayoutParams(dp(40), dp(40)))
    }
    root.addView(row, LinearLayout.LayoutParams(-1, -2))
    val error = ghText("", 12f, ghDanger).apply { visibility = View.GONE; setPadding(dp(4), dp(6), 0, 0) }
    root.addView(error, LinearLayout.LayoutParams(-1, -2))
    root.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    val field = GhField(root, edit, error, row)
    edit.setOnFocusChangeListener { _, hasFocus -> row.background = ghFieldBg(hasFocus, error.visibility == View.VISIBLE) }
    ghWatch(edit) { ghClearError(field) }
    return field
}

internal fun MainActivity.ghSetError(field: GhField, message: String) {
    field.error.text = message
    field.error.visibility = View.VISIBLE
    field.row.background = ghFieldBg(field.edit.hasFocus(), true)
    Motion.error(field.row)
}

internal fun MainActivity.ghClearError(field: GhField) {
    if (field.error.visibility == View.VISIBLE) {
        field.error.visibility = View.GONE
        field.row.background = ghFieldBg(field.edit.hasFocus(), false)
    }
}

internal fun MainActivity.ghPressable(view: View, onClick: () -> Unit) {
    view.isClickable = true
    view.isFocusable = true
    view.setOnClickListener {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        Motion.tap(view, 0.98f)
        onClick()
    }
}

internal fun MainActivity.ghPrimaryButton(text: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    background = ghRound(ghInk, 18)
    addView(ghLogo(22, ghOnInk), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
    addView(ghText(text, 15f, ghOnInk, true))
    addView(ghIcon("arrow-right", 18f, ghOnInk), LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(10) })
    layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(4); bottomMargin = dp(8) }
    ghPressable(this, onClick)
}

internal fun MainActivity.ghOutlineButton(text: String, iconName: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    background = ghRound(ghCard, 18, ghStroke)
    addView(ghIcon(iconName, 19f, ghInk), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
    addView(ghText(text, 15f, ghInk, true))
    layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) }
    ghPressable(this, onClick)
}

internal fun MainActivity.ghSmallButton(text: String, iconName: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    background = ghRound(ghSoft, 12)
    setPadding(dp(12), 0, dp(12), 0)
    addView(ghIcon(iconName, 17f, ghInk), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(6) })
    addView(ghText(text, 13f, ghInk, true))
    ghPressable(this, onClick)
}

internal fun MainActivity.ghSwitchRow(iconName: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), 0, dp(2))
    }
    row.addView(ghIcon(iconName, 18f, ghMuted), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
    row.addView(ghText(text, 13.5f, ghMuted), LinearLayout.LayoutParams(0, -2, 1f))
    val sw = Switch(this).apply {
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        trackTintList = android.content.res.ColorStateList(states, intArrayOf(ghInk, ghStroke))
        thumbTintList = android.content.res.ColorStateList(states, intArrayOf(ghOnInk, ghCard))
        setOnCheckedChangeListener { v, value -> v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onChange(value) }
    }
    row.addView(sw, LinearLayout.LayoutParams(-2, dp(40)))
    return row
}

internal fun MainActivity.ghSectionTitle(text: String): TextView =
    ghText(text, 12.5f, ghMuted, true).apply { setPadding(dp(2), dp(6), 0, dp(10)) }

// ----- pergantian layar -----
internal fun MainActivity.ghShow(screen: View, titleText: String) {
    val stage = ghStage ?: return
    title.text = titleText

    // Layar proses memakai Bit animated di kanan atas, seperti Proses Upload.
    // Tombol ⋮ disembunyikan agar tidak muncul bersamaan dengan wajah Bit.
    val processScreen = titleText == "Proses Upload" || titleText == "GitHub Actions" || titleText == "Cek Actions"
    action.visibility = if (processScreen) View.GONE else View.VISIBLE
    if (processScreen) editorMore.visibility = View.GONE

    stage.removeAllViews()
    stage.addView(screen, FrameLayout.LayoutParams(-1, -2))
    screen.alpha = 0f
    screen.translationY = dp(14).toFloat()
    screen.animate().alpha(1f).translationY(0f).setDuration(260).start()
    scroll.post { scroll.smoothScrollTo(0, 0) }
}

// =====================================================================
// Layar 1: Pengaturan GitHub
// =====================================================================
internal fun MainActivity.githubZipTool() {
    clearPage("GitHub Publisher")
    ghUserValue = prefs.getString("gh_user", null) ?: "username183728"
    ghRepoValue = prefs.getString("gh_repo", null) ?: "B1"
    // Pulihkan ZIP terakhir supaya nama file yang sudah dipilih tetap tampil.
    githubZipUri = prefs.getString("gh_selected_zip_uri", null)?.let {
        runCatching { Uri.parse(it) }.getOrNull()
    }
    ghBranch = prefs.getString("gh_branch", null) ?: "main"
    ghSaveToken = prefs.getBoolean("gh_save_token", true)
    ghPrivateRepo = prefs.getBoolean("gh_private", true)
    ghTokenValue = if (ghSaveToken) prefs.getString("gh_token_enc", null)?.let { GithubTokenVault.decrypt(it) }.orEmpty() else ""
    ghCommitValue = prefs.getString("gh_commit", null) ?: "Upload project via GITLS"
    ghStage = FrameLayout(this)
    content.addView(ghStage, LinearLayout.LayoutParams(-1, -2))
    val pendingResult = ghPendingResult
    val pendingError = ghPendingError
    when {
        ghRunning -> {
            // Upload masih berjalan: kembali ke layar proses, bukan halaman pengaturan.
            ghShow(ghProgressScreen("", ghUserValue, ghRepoValue, ghBranch), "Proses Upload")
            ghReplayProgress()
            ghHandler.removeCallbacks(ghTicker)
            ghHandler.post(ghTicker)
        }
        pendingResult != null -> { ghPendingResult = null; ghShowResult(pendingResult) }
        pendingError != null -> {
            ghPendingError = null
            ghShow(ghProgressScreen("", ghUserValue, ghRepoValue, ghBranch), "Proses Upload")
            ghReplayProgress()
            ghShowFailure(pendingError)
        }
        else -> {
            ghShow(ghSettingsScreen(), "Pengaturan GitHub")
            // Pulihkan preview ZIP terakhir bila URI masih bisa dibaca.
            githubZipUri?.let { uri ->
                if (prefs.getString("gh_selected_zip_name", null).orEmpty().isNotBlank()) {
                    githubUploadStatus?.text = "Memulihkan ZIP terakhir…"
                    prepareGithubZipPreview(uri)
                }
            }
        }
    }
}

internal fun MainActivity.ghSettingsScreen(): LinearLayout {
    val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(4), dp(2), dp(24)) }

    // Header publisher sengaja tidak dibuat sebagai kartu besar. Judul ditampilkan
    // compact di top bar, sejajar dengan tombol kembali dan menu.
    val user = ghField("Username", "account-outline", "username GitHub", ghUserValue)
    val repo = ghField("Repository", "source-repository", "nama repository", ghRepoValue)
    screen.addView(user.root)
    screen.addView(repo.root)

    // branch (dropdown)
    screen.addView(ghText("Branch", 13f, ghInk, true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(7); leftMargin = dp(2) })
    val branchRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        background = ghFieldBg(false, false)
    }
    branchRow.addView(ghIcon("source-branch", 20f, ghMuted), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) })
    val branchText = ghText(ghBranch, 15f, ghInk)
    branchRow.addView(branchText, LinearLayout.LayoutParams(0, -2, 1f))
    branchRow.addView(ghIcon("chevron-down", 20f, ghMuted), LinearLayout.LayoutParams(dp(24), dp(24)))
    ghPressable(branchRow) {
        ghHideKeyboard(branchRow)
        val options = listOf("main", "master", "develop", "Lainnya…")
        AlertDialog.Builder(this)
            .setTitle("Pilih branch")
            .setItems(options.toTypedArray()) { _, which ->
                if (which < options.size - 1) {
                    ghBranch = options[which]
                    branchText.text = ghBranch
                } else {
                    val input = EditText(this).apply { setText(ghBranch); setSingleLine(true); setPadding(dp(20), dp(14), dp(20), dp(14)) }
                    AlertDialog.Builder(this)
                        .setTitle("Nama branch")
                        .setView(input)
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Pakai") { _, _ ->
                            val value = input.text.toString().trim()
                            if (value.matches(Regex("[A-Za-z0-9._/-]+"))) { ghBranch = value; branchText.text = value }
                            else toast("Nama branch tidak valid")
                        }.show()
                }
            }.show()
    }
    screen.addView(branchRow, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })

    val token = ghField("Token (Personal Access Token)", "key-variant", "ghp_••••••••••••", ghTokenValue, password = true)
    token.root.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
    screen.addView(token.root)
    screen.addView(ghSwitchRow("information-outline", "Simpan token (terenkripsi)", ghSaveToken) { ghSaveToken = it })
    screen.addView(ghSwitchRow("lock-outline", "Repository baru dibuat private", ghPrivateRepo) { ghPrivateRepo = it })

    // Mode upload: penuh vs update repository (hanya file yang berbeda isinya).
    screen.addView(ghSectionTitle("Mode upload"))
    var onlyChangedState = prefs.getBoolean("gh_only_changed", true)
    val modeHint = ghText(
        if (onlyChangedState)
            "Update repository: file yang sudah ada dan isinya sama dilewati. Hanya file baru/berubah yang di-upload."
        else
            "Upload penuh: semua file project dikirim ulang ke GitHub (lebih lambat, lebih banyak request).",
        12f, ghMuted
    ).apply {
        setPadding(dp(4), dp(2), dp(4), dp(10))
        setLineSpacing(0f, 1.15f)
    }

    val modeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        weightSum = 2f
    }
    fun modeChip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(if (selected) Color.WHITE else ghInk)
            background = if (selected) {
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.rgb(20, 20, 24))
                }
            } else {
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (isDarkTheme) Color.rgb(40, 40, 44) else Color.rgb(240, 242, 245))
                    setStroke(dp(1), if (isDarkTheme) Color.rgb(60, 60, 66) else Color.rgb(220, 224, 228))
                }
            }
            setPadding(dp(10), dp(12), dp(10), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }
    lateinit var updateChip: TextView
    lateinit var fullChip: TextView
    fun refreshModeChips() {
        modeRow.removeAllViews()
        updateChip = modeChip("↻ Update repo", onlyChangedState) {
            onlyChangedState = true
            prefs.edit().putBoolean("gh_only_changed", true).apply()
            modeHint.text = "Update repository: file yang sudah ada dan isinya sama dilewati. Hanya file baru/berubah yang di-upload."
            refreshModeChips()
        }
        fullChip = modeChip("Upload penuh", !onlyChangedState) {
            onlyChangedState = false
            prefs.edit().putBoolean("gh_only_changed", false).apply()
            modeHint.text = "Upload penuh: semua file project dikirim ulang ke GitHub (lebih lambat, lebih banyak request)."
            refreshModeChips()
        }
        modeRow.addView(updateChip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        modeRow.addView(fullChip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
    }
    refreshModeChips()
    screen.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
    screen.addView(modeHint)

    val deleteMissing = ghSwitchRow("delete-sweep-outline", "Hapus file remote yang sudah tidak ada", prefs.getBoolean("gh_delete_missing", false)) {
        prefs.edit().putBoolean("gh_delete_missing", it).apply()
    }
    screen.addView(deleteMissing)
    val dryRun = ghSwitchRow("eye-outline", "Dry Run — cek perubahan tanpa commit", prefs.getBoolean("gh_dry_run", false)) {
        prefs.edit().putBoolean("gh_dry_run", it).apply()
    }
    screen.addView(dryRun)

    val commit = ghField("Pesan commit", "text-box-outline", "Update via GITLS", ghCommitValue)
    commit.root.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(8) }
    screen.addView(commit.root)

    // sumber project
    screen.addView(ghSectionTitle("Sumber project"))
    screen.addView(ghSourcePicker())

    // status
    val statusRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(10), dp(4), dp(14))
    }
    statusRow.addView(ghIcon("information-outline", 16f, ghMuted), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
    val statusText = ghText("Pilih ZIP atau folder untuk memulai.", 12f, ghMuted)
    githubUploadStatus = statusText
    statusRow.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
    screen.addView(statusRow)

    screen.addView(ghPrimaryButton("Simpan & Jalankan") {
        ghHideKeyboard(screen)
        val owner = user.edit.text.toString().trim()
        val repository = repo.edit.text.toString().trim()
        val pat = token.edit.text.toString().trim()
        val message = commit.edit.text.toString().trim()
        var valid = true
        if (!owner.matches(Regex("[A-Za-z0-9_.-]+"))) { ghSetError(user, "Username hanya boleh huruf, angka, titik, garis"); valid = false }
        if (!repository.matches(Regex("[A-Za-z0-9_.-]+"))) { ghSetError(repo, "Nama repository tidak valid"); valid = false }
        if (pat.isBlank()) { ghSetError(token, "Token wajib diisi"); valid = false }
        if (!valid) return@ghPrimaryButton
        if (!validateGithubInputs(owner, repository, pat, ghBranch)) return@ghPrimaryButton
        val onlyChangedNow = prefs.getBoolean("gh_only_changed", true)
        val commitMessage = message.ifBlank {
            if (onlyChangedNow) "Update via GITLS" else "Upload project via GITLS"
        }
        val zipUri = githubZipUri
        val folderUri = githubFolderUri
        if (ghSource == 0 && zipUri == null) { toast("Pilih file ZIP terlebih dahulu"); return@ghPrimaryButton }
        if (ghSource == 0 && githubZipPreviewFiles.isEmpty()) { toast("Tunggu analisis ZIP selesai atau pilih ZIP lagi"); return@ghPrimaryButton }
        if (ghSource == 1 && folderUri == null) { toast("Pilih folder project terlebih dahulu"); return@ghPrimaryButton }

        ghUserValue = owner; ghRepoValue = repository; ghTokenValue = pat; ghCommitValue = commitMessage
        ghSaveSettings(owner, repository, pat, commitMessage)

        val branch = ghBranch
        val makePrivate = ghPrivateRepo
        val onlyChangedMode = prefs.getBoolean("gh_only_changed", true)
        val deleteMissingMode = prefs.getBoolean("gh_delete_missing", false)
        val dryRunMode = prefs.getBoolean("gh_dry_run", false)
        val sourceLabel: String
        val fileCountText: String
        if (ghSource == 0 && zipUri != null) {
            val rootNow = githubZipRoot.trim('/')
            val excludedNow = githubZipExcluded.map { it.trim('/') }
            val count = githubZipPreviewFiles.count { rel ->
                val inRoot = rootNow.isBlank() || rel == rootNow || rel.startsWith("$rootNow/")
                inRoot && excludedNow.none { ex -> ex.isNotBlank() && (rel == ex || rel.startsWith("$ex/")) }
            }
            sourceLabel = "ZIP: " + prefs.getString("gh_selected_zip_name", null).orEmpty().ifBlank { "file terpilih" }
            fileCountText = "$count file (perkiraan, sebelum filter file sensitif)"
        } else {
            sourceLabel = "Folder project"
            fileCountText = "dihitung saat upload dimulai"
        }
        val summary = ghBuildUploadSummary(
            owner, repository, branch, pat, sourceLabel, fileCountText, commitMessage,
            makePrivate, onlyChangedMode, deleteMissingMode, dryRunMode
        )
        ghConfirmUploadDialog(summary, dryRunMode, deleteMissingMode) {
            if (ghSource == 0 && zipUri != null) {
                val root = githubZipRoot
                val excluded = githubZipExcluded.toSet()
                ghStartUpload("ZIP", owner, repository, branch) { p -> uploadZipToGitHub(zipUri, owner, repository, pat, branch, commitMessage, root, excluded, makePrivate, p,
                    onlyChangedMode, deleteMissingMode, dryRunMode) }
            } else if (folderUri != null) {
                ghStartUpload("Folder", owner, repository, branch) { p -> uploadFolderToGitHub(folderUri, owner, repository, pat, branch, commitMessage, makePrivate, p,
                    onlyChangedMode, deleteMissingMode, dryRunMode) }
            }
        }
    })

    screen.addView(
        ghText("Token dipakai langsung untuk request ke GitHub. Bila \"Simpan token\" mati, token tidak disimpan sama sekali. Butuh izin Contents read/write (classic: scope repo).", 11f, ghMuted)
            .apply { setPadding(dp(4), dp(4), dp(4), 0); setLineSpacing(0f, 1.15f) }
    )

    screen.addView(ghOutlineButton("Lihat Repository di GitHub", "folder-open-outline") {
        val owner = user.edit.text.toString().trim()
        val repository = repo.edit.text.toString().trim()
        val pat = token.edit.text.toString().trim()
        if (!owner.matches(Regex("[A-Za-z0-9_.-]+")) || !repository.matches(Regex("[A-Za-z0-9_.-]+")) || pat.isBlank()) {
            toast("Username, repository, dan token wajib diisi")
            return@ghOutlineButton
        }
        ghUserValue = owner; ghRepoValue = repository; ghTokenValue = pat
        ghSaveSettings(owner, repository, pat, commit.edit.text.toString().trim().ifBlank { "Upload project via GITLS" })
        ghShow(ghRepositoryBrowserScreen(""), "Isi Repository")
    })
    return screen
}

// ----- browser repository GitHub: lihat folder/file + hapus dengan verifikasi ganda -----
internal fun MainActivity.ghRepositoryBrowserScreen(path: String): LinearLayout {
    val owner = ghUserValue.trim()
    val repo = ghRepoValue.trim()
    val token = ghTokenValue.trim().ifBlank {
        prefs.getString("gh_token_enc", null)?.let { GithubTokenVault.decrypt(it) }.orEmpty()
    }
    val branch = ghBranch.ifBlank { "main" }
    val cleanPath = path.trim('/').replace("//", "/")
    val screen = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(2), dp(24))
    }

    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = ghRound(ghSoft, 16)
    }
    val back = ghIcon("arrow-left", 20f, ghInk).apply {
        visibility = if (cleanPath.isBlank()) View.GONE else View.VISIBLE
        setPadding(dp(8), dp(8), dp(8), dp(8))
        isClickable = true
        setOnClickListener {
            val parent = cleanPath.substringBeforeLast('/', "")
            ghShow(ghRepositoryBrowserScreen(parent), "Isi Repository")
        }
    }
    top.addView(back, LinearLayout.LayoutParams(dp(42), dp(42)))
    val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    titleBox.addView(ghText(if (cleanPath.isBlank()) "$owner/$repo" else cleanPath, 15f, ghInk, true))
    titleBox.addView(ghText("Branch: $branch", 11f, ghMuted).apply { setPadding(0, dp(3), 0, 0) })
    top.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
    val refresh = ghIcon("refresh", 20f, ghInk).apply {
        setPadding(dp(8), dp(8), dp(8), dp(8))
        isClickable = true
        setOnClickListener { ghShow(ghRepositoryBrowserScreen(cleanPath), "Isi Repository") }
    }
    top.addView(refresh, LinearLayout.LayoutParams(dp(42), dp(42)))
    screen.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

    val info = ghText("Memuat isi repository…", 12f, ghMuted).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
    screen.addView(info)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply { addView(list) }
    screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

    if (token.isBlank()) {
        info.text = "Token GitHub belum tersedia."
        return screen
    }

    toolThread {
        val result = runCatching {
            val endpoint = "https://api.github.com/repos/${Uri.encode(owner)}/${Uri.encode(repo)}/contents/${if (cleanPath.isBlank()) "" else encodePath(cleanPath)}?ref=${Uri.encode(branch)}"
            val json = githubRequestRaw(endpoint, token)
            if (json.trimStart().startsWith("[")) {
                val arr = JSONArray(json)
                val items = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .sortedWith(compareBy<JSONObject>({ it.optString("type") != "dir" }, { it.optString("name").lowercase(Locale.getDefault()) }))
                items
            } else {
                listOf(JSONObject(json))
            }
        }
        runOnToolUiThread {
            if (isFinishing) return@runOnToolUiThread
            list.removeAllViews()
            result.onSuccess { items ->
                info.text = if (items.isEmpty()) "Folder kosong." else "${items.size} item"
                if (items.isEmpty()) return@onSuccess
                items.forEach { item ->
                    val itemPath = item.optString("path")
                    val isDir = item.optString("type") == "dir"
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(8), dp(4), dp(8))
                        background = ghRound(ghCard, 18, ghStroke)
                    }
                    val icon = ghIcon(if (isDir) "folder-outline" else "file-outline", 21f, ghInk)
                    row.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(8) })
                    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    texts.addView(ghText(item.optString("name"), 14f, ghInk, true))
                    texts.addView(ghText(if (isDir) "Folder" else ghFormatBytes(item.optLong("size", 0L)), 11f, ghMuted).apply { setPadding(0, dp(3), 0, 0) })
                    row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                    val trash = ghIcon("delete-outline", 20f, ghDanger).apply {
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        isClickable = true
                        contentDescription = "Hapus ${item.optString("name")}"
                        setOnClickListener {
                            val target = item.optString("name")
                            ghConfirmGithubDelete(itemPath, isDir, target, token) {
                                ghShow(ghRepositoryBrowserScreen(cleanPath), "Isi Repository")
                            }
                        }
                    }
                    row.addView(trash, LinearLayout.LayoutParams(dp(44), dp(44)))
                    row.setOnClickListener {
                        if (isDir) ghShow(ghRepositoryBrowserScreen(itemPath), "Isi Repository")
                        else ghOpenUrl(item.optString("html_url").ifBlank { "https://github.com/$owner/$repo/blob/$branch/${encodePath(itemPath)}" })
                    }
                    list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
                }
                val deleteAllLabel = if (cleanPath.isBlank()) "Hapus Semua File Repository" else "Hapus Semua File di Folder Ini"
                val deleteAll = ghOutlineButton(deleteAllLabel, "delete-sweep-outline") {
                    ghConfirmGithubDeleteAll(token, cleanPath) {
                        ghShow(ghRepositoryBrowserScreen(cleanPath), "Isi Repository")
                    }
                }
                // Samakan tinggi dan sudut tombol dengan kartu/file lain agar benar-benar tumpul.
                deleteAll.layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(2)
                }
                list.addView(deleteAll)
            }.onFailure { e ->
                info.text = "Gagal memuat repository."
                toast(e.message ?: "Gagal membaca GitHub")
            }
        }
    }
    return screen
}

internal fun MainActivity.ghConfirmGithubDelete(path: String, isDir: Boolean, name: String, token: String, onDone: () -> Unit) {
    val kind = if (isDir) "folder" else "file"
    val deleteLabel = "Hapus $kind $name"

    // Verifikasi pertama: cukup pilih Batal atau tombol hapus dengan nama item.
    AlertDialog.Builder(this)
        .setTitle("Hapus $kind?")
        .setMessage("$name\n\nItem ini akan dihapus dari branch $ghBranch dan perubahan akan disimpan ke GitHub.")
        .setNegativeButton("Batal", null)
        .setPositiveButton(deleteLabel) { _, _ ->
            // Verifikasi kedua: tidak perlu mengetik HAPUS.
            AlertDialog.Builder(this)
                .setTitle("Konfirmasi sekali lagi")
                .setMessage("Yakin ingin menghapus $kind ini?\n\n$name")
                .setNegativeButton("Batal", null)
                .setPositiveButton(deleteLabel) { _, _ ->
                    ghRunDelete(path, isDir, token, onDone)
                }
                .show().also { ghStyleRoundedDialog(it) }
        }
        .show().also { ghStyleRoundedDialog(it) }
}

internal fun MainActivity.ghConfirmGithubDeleteAll(token: String, prefix: String, onDone: () -> Unit) {
    val scope = prefix.trim('/').let { if (it.isBlank()) "seluruh repository" else "folder $it" }

    // Hapus semua memakai pola konfirmasi yang sama dengan hapus satu file/folder:
    // tidak ada input teks "HAPUS SEMUA" dan tetap ada konfirmasi kedua.
    AlertDialog.Builder(this)
        .setTitle("Hapus semua?")
        .setMessage("Semua file di $scope pada branch $ghBranch akan dihapus. Folder kosong juga akan hilang karena GitHub tidak menyimpan folder kosong.")
        .setNegativeButton("Batal", null)
        .setPositiveButton("Hapus Semua") { _, _ ->
            AlertDialog.Builder(this)
                .setTitle("Konfirmasi sekali lagi")
                .setMessage("Yakin ingin menghapus semua file?\n\n$scope")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus Semua") { _, _ ->
                    ghRunDeleteAll(token, prefix, onDone)
                }
                .show().also { ghStyleRoundedDialog(it) }
        }
        .show().also { ghStyleRoundedDialog(it) }
}

internal fun MainActivity.ghShowError(title: String, e: Throwable) {
    if (isFinishing) return
    AlertDialog.Builder(this).setTitle(title).setMessage(ghDeleteError(e)).setPositiveButton("OK", null)
        .show().also { ghStyleRoundedDialog(it) }
}

internal fun MainActivity.ghRunDelete(path: String, isDir: Boolean, token: String, onDone: () -> Unit) {
    AlertDialog.Builder(this).setTitle("Menghapus…").setMessage("Menyiapkan commit penghapusan.").setCancelable(false).create().also { dialog ->
        dialog.show()
        toolThread {
            val result = runCatching {
                if (isDir) ghDeleteTreePrefix(path, token) else ghDeleteSingleFile(path, token)
            }
            runOnToolUiThread {
                dialog.dismiss()
                result.onSuccess { toast("$path berhasil dihapus"); onDone() }
                    .onFailure { ghShowError("Gagal menghapus", it) }
            }
        }
    }
}

internal fun MainActivity.startGithubDeleteService(token: String, prefix: String) {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
    }
    val intent = Intent(this, GithubDeleteService::class.java).apply {
        putExtra(GithubDeleteService.EXTRA_TOKEN, token)
        putExtra(GithubDeleteService.EXTRA_OWNER, ghUserValue)
        putExtra(GithubDeleteService.EXTRA_REPO, ghRepoValue)
        putExtra(GithubDeleteService.EXTRA_BRANCH, ghBranch)
        putExtra(GithubDeleteService.EXTRA_PREFIX, prefix)
    }
    ContextCompat.startForegroundService(this, intent)
}

internal fun MainActivity.ghRunDeleteAll(token: String, prefix: String, onDone: () -> Unit) {
    // Penghapusan dijalankan oleh ForegroundService supaya tetap hidup ketika
    // dialog ditutup, Activity dipindah ke background, atau pengguna keluar
    // dari halaman. Progress juga tetap terlihat di notification shade.
    startGithubDeleteService(token, prefix)

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(12))
    }
    val status = TextView(this).apply {
        text = "Menyiapkan penghapusan…"
        textSize = 17f
        setTextColor(ghInk)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    val percent = TextView(this).apply {
        text = "0%"
        textSize = 14f
        setTextColor(ghMuted)
        gravity = Gravity.END
    }
    val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        isIndeterminate = false
    }
    val detail = TextView(this).apply {
        text = "Proses berjalan di latar belakang…"
        textSize = 12f
        setTextColor(ghMuted)
        setPadding(0, dp(8), 0, 0)
    }
    val tip = TextView(this).apply {
        text = "Sembunyikan aman. Proses tetap berjalan dan bisa dipantau dari notifikasi."
        textSize = 12f
        setTextColor(ghMuted)
        setPadding(0, dp(12), 0, 0)
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(percent, LinearLayout.LayoutParams(dp(52), -2))
    box.addView(row)
    box.addView(progress, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(12) })
    box.addView(detail)
    box.addView(tip)

    val dialog = AlertDialog.Builder(this)
        .setTitle("Menghapus semua…")
        .setView(box)
        .setCancelable(false)
        .setNegativeButton("Sembunyikan") { d, _ -> d.dismiss() }
        .setPositiveButton("Tutup") { d, _ -> d.dismiss() }
        .create()
    dialog.show()
    ghStyleRoundedDialog(dialog)

    val handler = Handler(Looper.getMainLooper())
    val poll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val prefsState = getSharedPreferences("gh_delete_state", Context.MODE_PRIVATE)
            val activeId = prefsState.getString("job_id", "")
            val myId = intentJobId(token, prefix)
            if (activeId == myId) {
                val pct = prefsState.getInt("progress", 0).coerceIn(0, 100)
                progress.progress = pct
                percent.text = "$pct%"
                status.text = prefsState.getString("title", "Menghapus…")
                detail.text = prefsState.getString("detail", "Proses berjalan di latar belakang…")
                if (prefsState.getBoolean("finished", false)) {
                    dialog.dismiss()
                    val ok = prefsState.getBoolean("success", false)
                    toast(prefsState.getString("result", if (ok) "Semua file berhasil dihapus" else "Penghapusan gagal") ?: "Selesai")
                    onDone()
                    return
                }
            }
            handler.postDelayed(this, 350L)
        }
    }
    handler.post(poll)
}

internal fun MainActivity.ghSaveSettings(owner: String, repository: String, pat: String, commitMessage: String) {
    val editor = prefs.edit()
        .putString("gh_user", owner).putString("gh_repo", repository).putString("gh_branch", ghBranch)
        .putString("gh_commit", commitMessage)
        .putBoolean("gh_save_token", ghSaveToken).putBoolean("gh_private", ghPrivateRepo)
    if (ghSaveToken) {
        val enc = GithubTokenVault.encrypt(pat)
        if (enc != null) editor.putString("gh_token_enc", enc) else editor.remove("gh_token_enc")
    } else {
        editor.remove("gh_token_enc")
    }
    editor.apply()
    // Jika Publisher dibuka oleh Bot karena credential belum lengkap, lanjutkan
    // upload lampiran yang tertunda setelah data berhasil disimpan. Jika pengguna
    // memilih "Rubah" pada konfirmasi Bot, Publisher harus menunggu aksi upload
    // pengguna dan tidak boleh mengirim file secara otomatis.
    if (!bitGithubUploadNeedsManualPublisher) {
        runOnToolUiThread { window.decorView.postDelayed({ bitRetryPendingGithubUploadIfReady(pat) }, 180L) }
    }
}

// ----- pemilih sumber: ZIP / Folder -----
internal fun MainActivity.ghSourcePicker(): LinearLayout {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val tabs = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = ghRound(ghSoft, 16)
    }
    val zipPanel = ghZipPanel()
    val folderPanel = ghFolderPanel()
    val tabViews = ArrayList<LinearLayout>()
    fun paintTabs() {
        tabViews.forEachIndexed { index, tab ->
            val selected = index == ghSource
            tab.background = if (selected) ghRound(ghCard, 12, ghStroke) else null
            for (i in 0 until tab.childCount) {
                val child = tab.getChildAt(i)
                if (child is MdiIconView) child.setTextColor(if (selected) ghInk else ghMuted)
                if (child is TextView && child !is MdiIconView) child.setTextColor(if (selected) ghInk else ghMuted)
            }
        }
        zipPanel.visibility = if (ghSource == 0) View.VISIBLE else View.GONE
        folderPanel.visibility = if (ghSource == 1) View.VISIBLE else View.GONE
    }
    fun tab(label: String, iconName: String, index: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(ghIcon(iconName, 18f, ghMuted), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) })
        addView(ghText(label, 14f, ghMuted, true))
        isClickable = true
        setOnClickListener {
            if (ghSource != index) {
                it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                ghSource = index
                paintTabs()
                val panel = if (index == 0) zipPanel else folderPanel
                panel.alpha = 0f
                panel.animate().alpha(1f).setDuration(200).start()
            }
        }
    }
    tabViews.add(tab("File ZIP", "folder-zip-outline", 0))
    tabViews.add(tab("Folder", "folder-open-outline", 1))
    tabViews.forEach { tabs.addView(it, LinearLayout.LayoutParams(0, dp(42), 1f)) }
    box.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    box.addView(zipPanel)
    box.addView(folderPanel)
    paintTabs()
    return box
}

internal fun MainActivity.ghSourceCard(iconName: String, titleView: TextView, hintText: String): LinearLayout {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = ghRound(ghCard, 18, ghStroke)
    }
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val badge = ghIcon(iconName, 22f, ghInk).apply { background = ghRound(ghSoft, 14) }
    top.addView(badge, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(14) })
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(titleView.apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
    texts.addView(ghText(hintText, 12f, ghMuted).apply { setPadding(0, dp(4), 0, 0) })
    top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
    card.addView(top)
    return card
}

internal fun MainActivity.ghZipPanel(): LinearLayout {
    val restoredName = prefs.getString("gh_selected_zip_name", null)
    val zipTitleText = restoredName?.takeIf { it.isNotBlank() }
        ?: githubZipUri?.let { queryName(it) }
        ?: "Belum ada ZIP dipilih"
    val zipTitle = ghText(zipTitleText, 14.5f, ghInk, true)
    githubZipLabel = zipTitle
    // Jika URI masih valid, tampilkan ukuran tanpa menunggu pengguna memilih ulang.
    githubZipUri?.let { uri ->
        val size = runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        if (size > 0 && zipTitleText != "Belum ada ZIP dipilih") {
            zipTitle.text = "$zipTitleText • ${ghFormatBytes(size)}"
        }
    }
    val card = ghSourceCard("folder-zip-outline", zipTitle, "Preview isi, atur root, dan kecualikan file.")
    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
    actions.addView(ghSmallButton("Pilih ZIP", "folder-open-outline") {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }, GITHUB_ZIP_PICK_REQUEST)
    }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
    actions.addView(ghSmallButton("Kelola isi", "file-tree-outline") {
        val uri = githubZipUri
        if (uri == null) toast("Pilih ZIP terlebih dahulu") else showGithubZipPreview(uri)
    }, LinearLayout.LayoutParams(0, dp(40), 1f))
    card.addView(actions)
    return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(card) }
}

internal fun MainActivity.ghFolderPanel(): LinearLayout {
    val folderTitle = ghText("Belum ada folder dipilih", 14.5f, ghInk, true)
    githubFolderLabel = folderTitle
    val card = ghSourceCard("folder-outline", folderTitle, "Semua file dan subfolder ikut terkirim.")
    val previewText = ghText("Isi folder akan tampil di sini.", 11.5f, ghMuted).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = ghRound(ghSoft, 12)
        setLineSpacing(0f, 1.2f)
        maxLines = 9
    }
    githubFolderPreview = previewText
    card.addView(previewText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
    actions.addView(ghSmallButton("Pilih folder", "folder-open-outline") {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, GITHUB_FOLDER_PICK_REQUEST)
    }, LinearLayout.LayoutParams(-1, dp(40)))
    actions.addView(ghSmallButton("Kecualikan jenis file (md, txt, dll.)", "eye-off-outline") {
        showGithubExcludeDialog {
            val ps = githubExcludePatterns()
            githubUploadStatus?.text = if (ps.isEmpty()) "Pola pengecualian dihapus" else "Pola pengecualian: ${ps.joinToString(", ")}"
        }
    }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
    card.addView(actions)
    return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(card) }
}

internal fun MainActivity.ghBuildUploadSummary(
    owner: String, repository: String, branch: String, token: String,
    sourceLabel: String, fileCountText: String, commitMessage: String,
    makePrivate: Boolean, onlyChanged: Boolean, deleteMissing: Boolean, dryRun: Boolean
): String = buildString {
    appendLine("Repository : $owner/$repository")
    appendLine("Branch     : $branch")
    appendLine("Sumber     : $sourceLabel")
    appendLine("Jumlah file: $fileCountText")
    appendLine("Token      : ${ghMaskToken(token)}")
    appendLine("Pesan commit: $commitMessage")
    appendLine()
    appendLine(if (dryRun) "Mode: DRY RUN (tidak ada yang dikirim)" else "Mode: upload sungguhan")
    appendLine(if (onlyChanged) "• Hanya file yang berubah yang dikirim" else "• Semua file dikirim ulang")
    if (deleteMissing) appendLine("• File di repository yang tidak ada di sumber akan DIHAPUS")
    append("• Jika repository belum ada, dibuat sebagai ${if (makePrivate) "private" else "public"}")
}

internal fun MainActivity.ghConfirmUploadDialog(summary: String, dryRun: Boolean, deleteMissing: Boolean, onConfirm: () -> Unit) {
    val warning = when {
        dryRun -> ""
        deleteMissing -> "\n\nPeringatan: file dengan path yang sama akan ditimpa dan file yang tidak ada di sumber akan dihapus dari branch ini."
        else -> "\n\nCatatan: file dengan path yang sama di branch ini akan ditimpa. Rincian file baru vs diperbarui tampil setelah proses selesai."
    }
    AlertDialog.Builder(this)
        .setTitle(if (dryRun) "Jalankan Dry Run?" else "Kirim ke GitHub?")
        .setMessage(summary + warning)
        .setNegativeButton("Periksa lagi", null)
        .setPositiveButton(if (dryRun) "Jalankan Dry Run" else "Ya, upload") { _, _ -> onConfirm() }
        .show().also { ghStyleRoundedDialog(it) }
}

internal fun MainActivity.validateGithubInputs(owner: String, repository: String, pat: String, branch: String): Boolean {
    if (!owner.matches(Regex("[A-Za-z0-9_.-]+"))) { toast("Username GitHub tidak valid"); return false }
    if (!repository.matches(Regex("[A-Za-z0-9_.-]+"))) { toast("Nama repository tidak valid"); return false }
    if (pat.isBlank()) { toast("Masukkan GitHub token"); return false }
    if (!branch.matches(Regex("[A-Za-z0-9._/-]+"))) { toast("Nama branch tidak valid"); return false }
    return true
}

// =====================================================================
// Layar 2: Proses Upload (animasi)
// =====================================================================
internal fun MainActivity.ghStepTitle(index: Int, branch: String): String = when (index) {
    0 -> "Menghubungkan ke GitHub"
    1 -> "Membuat repository (jika belum ada)"
    2 -> "Mengunggah file"
    3 -> "Push ke branch $branch"
    else -> "Verifikasi hasil upload"
}

/**
 * Small Bit assistant shown on the GitHub process screen.
 * Normal state uses the same animation as the bottom navigation.
 * On error it switches to an open-eye frame, moves slightly up/left and exposes
 * a tappable "Error" label that opens Bit chat with the failure context.
 */
internal fun MainActivity.ghBitProcessAssistant(): FrameLayout {
    val host = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        contentDescription = "Bit — status proses"
    }

    val face = LottieAnimationView(this).apply {
        setBitAsset("bit/bit_idle.json")
        repeatMode = LottieDrawable.RESTART
        repeatCount = LottieDrawable.INFINITE
        setMinAndMaxFrame(0, 108)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        isClickable = false
        isFocusable = false
        contentDescription = "Bit"
    }
    bitProcessFace = face
    host.addView(face, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
    applyBitFaceTheme(face)
    bitAnim?.attach(face)

    host.setOnClickListener {
        // Bit is a companion, not a chat entry point.
        bitAnim?.onTap(face)
    }
    addPressFeedback(host)
    return host
}

internal fun MainActivity.ghProgressScreen(kind: String, owner: String, repo: String, branch: String): LinearLayout {
    ghStepViews.clear(); ghStepTitles.clear(); ghStepDetails.clear()
    val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(2), dp(2), dp(24)) }

    // Bit sits at the top-right of the process screen, using the same animated face as the nav.
    val processHeader = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
    }
    val processBit = createGhBitProcessAssistant()
    processHeader.addView(processBit, FrameLayout.LayoutParams(dp(104), dp(58), Gravity.TOP or Gravity.END))
    screen.addView(processHeader, LinearLayout.LayoutParams(-1, dp(58)))

    val ringBox = FrameLayout(this)
    val ring = ProgressRingView(this).apply {
        ringColor = ghInk
        trackColor = ghSoft
        indeterminate = true
    }
    ghRing = ring
    ringBox.addView(ring, FrameLayout.LayoutParams(-1, -1))
    val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    center.addView(ghLogo(54, ghInk), LinearLayout.LayoutParams(dp(54), dp(54)).apply { bottomMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
    ghPercentText = ghText("$ghPct%", 28f, ghInk, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
    center.addView(ghPercentText, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
    ringBox.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    screen.addView(ringBox, LinearLayout.LayoutParams(dp(210), dp(210)).apply { topMargin = dp(6); bottomMargin = dp(14) })

    val target = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = ghRound(ghSoft, 14)
    }
    target.addView(ghIcon("source-repository", 15f, ghMuted), LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(6) })
    target.addView(ghText("$owner/$repo", 12.5f, ghInk, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
    target.addView(ghIcon("source-branch", 15f, ghMuted), LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(12); rightMargin = dp(4) })
    target.addView(ghText(branch, 12.5f, ghMuted))
    screen.addView(target, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(22) })

    val steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
    for (i in 0..4) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(9)) }
        val state = StepStateView(this).apply {
            inkColor = ghInk; onInkColor = ghOnInk; mutedColor = ghStroke; dangerColor = ghDanger
            setState(StepStateView.PENDING, false)
        }
        ghStepViews.add(state)
        row.addView(state, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14); topMargin = dp(1) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stepTitle = ghText(ghStepTitle(i, branch), 14.5f, ghMuted)
        val stepDetail = ghText("", 11.5f, ghMuted).apply { visibility = View.GONE; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(4), 0, 0) }
        ghStepTitles.add(stepTitle); ghStepDetails.add(stepDetail)
        col.addView(stepTitle); col.addView(stepDetail)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        steps.addView(row)
        if (i < 4) {
            // Garis timeline tipis di bawah status agar alur proses lebih mudah dibaca.
            steps.addView(View(this).apply {
                setBackgroundColor(ghStroke)
                alpha = 0.75f
            }, LinearLayout.LayoutParams(dp(2), dp(10)).apply {
                leftMargin = dp(11)
                topMargin = dp(-3)
                bottomMargin = dp(-3)
            })
        }
    }
    screen.addView(steps, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

    val note = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = ghRound(ghSoft, 16)
    }
    val spinner = StepStateView(this).apply {
        inkColor = ghInk; onInkColor = ghOnInk; mutedColor = ghStroke; dangerColor = ghDanger
        setState(StepStateView.ACTIVE, false)
    }
    note.addView(spinner, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(12) })
    val noteTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    noteTexts.addView(ghText("Mohon tunggu, proses ini mungkin memakan waktu…", 12.5f, ghMuted).apply { setLineSpacing(0f, 1.1f) })
    ghElapsedText = ghText("Berjalan 00:00", 11.5f, ghMuted).apply { setPadding(0, dp(4), 0, 0) }
    noteTexts.addView(ghElapsedText)
    note.addView(noteTexts, LinearLayout.LayoutParams(0, -2, 1f))
    ghNoteBox = note
    screen.addView(note, LinearLayout.LayoutParams(-1, -2))

    ghErrorHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    screen.addView(ghErrorHost, LinearLayout.LayoutParams(-1, -2))

    val cancel = ghOutlineButton("Batal", "close-circle") { ghConfirmCancel() }
    (cancel.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
    ghCancelBtn = cancel
    ghCancelLabel = cancel.getChildAt(1) as? TextView
    screen.addView(cancel)
    return screen
}

internal fun MainActivity.ghConfirmCancel() {
    if (!ghRunning || ghCancelled) return
    AlertDialog.Builder(this)
        .setTitle("Batalkan upload?")
        .setMessage("Proses upload ke GitHub akan dihentikan. File yang belum ter-push tidak akan masuk ke repository.")
        .setNegativeButton("Lanjutkan", null)
        .setPositiveButton("Batalkan") { _, _ ->
            if (!ghRunning) return@setPositiveButton
            ghCancelled = true
            ghCancelLabel?.text = "Membatalkan…"
            ghCancelBtn?.alpha = 0.5f
            ghCancelBtn?.isEnabled = false
            ghCancelBtn?.isClickable = false
        }
        .show()
}

/** Menggambar ulang layar proses dari status terakhir (dipakai saat masuk kembali ke halaman). */
internal fun MainActivity.ghReplayProgress() {
    ghCurrentStep = 0
    for (i in 0..4) ghStepLast[i]?.let { ghOnProgress(it, store = false) }
    if (ghCancelled) {
        ghCancelLabel?.text = "Membatalkan…"
        ghCancelBtn?.alpha = 0.5f
        ghCancelBtn?.isEnabled = false
        ghCancelBtn?.isClickable = false
    }
}

internal fun MainActivity.ghNotifyDone(success: Boolean, text: String) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Minta izin sekali; notifikasi berikutnya akan tampil setelah user izinkan
            runCatching {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
            }
            toast(text)
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = if (success) "github_upload_ok" else "github_upload_fail"
        if (Build.VERSION.SDK_INT >= 26) {
            val importance = if (success) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    if (success) "GitHub — Berhasil" else "GitHub — Gagal",
                    importance
                ).apply {
                    description = "Notifikasi hasil aksi GitHub (upload / hapus / publish)"
                    enableVibration(!success)
                }
            )
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_github_upload", true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val open = PendingIntent.getActivity(
            this, if (success) 99002 else 99003, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val title = if (success) "GitHub berhasil" else "GitHub gagal"
        val builder = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(this, channelId)
        else
            @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(text.take(180))
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            builder.setPriority(if (success) android.app.Notification.PRIORITY_DEFAULT else android.app.Notification.PRIORITY_HIGH)
        }
        manager.notify(if (success) 99002 else 99003, builder.build())
        // Toast ringkas juga, agar feedback langsung terasa di dalam app
        toast(if (success) text else text.take(100))
    }.onFailure {
        toast(if (success) text else text.take(100))
    }
}

internal fun MainActivity.ghStartUpload(kind: String, owner: String, repo: String, branch: String, task: ((GhProgress) -> Unit) -> GhResult) {
    ghRetry = { ghStartUpload(kind, owner, repo, branch, task) }
    if (!ghInternetAvailable()) {
        AlertDialog.Builder(this)
            .setTitle("Tidak ada jaringan internet")
            .setMessage("GITLS membutuhkan koneksi internet untuk meng-upload file ke GitHub. Silakan cek koneksi internet atau Wi-Fi Anda terlebih dahulu.")
            .setNegativeButton("Batal", null)
            .setNeutralButton("Cek koneksi") { _, _ -> ghOpenConnectionSettings() }
            .setPositiveButton("Coba lagi") { _, _ -> ghStartUpload(kind, owner, repo, branch, task) }
            .show().also { ghStyleRoundedDialog(it) }
        return
    }
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        runCatching { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001) }.logFailure("gh.notifPermission")
    }
    // Claim only after cheap preflight checks. This avoids leaving the gate locked
    // when the device is offline or another preflight condition returns early.
    synchronized(ghRunLock) {
        if (ghRunning) { toast("Upload sedang berjalan"); return }
        ghRunning = true
    }
    ghCancelled = false
    ghPendingResult = null
    ghPendingError = null
    ghFinishedSec = -1
    ghPct = 0
    for (i in 0..4) ghStepLast[i] = null
    bitPendingChatMessage = null
    bitProcessErrorView = null
    bitAnim?.detach(bitProcessFace)
    bitProcessFace = null
    ghShow(ghProgressScreen(kind, owner, repo, branch), "Proses Upload")
    BitRuntimeContext.onProcessStart("Proses Upload", "Upload $kind ke GitHub")
    ghCurrentStep = 0
    ghFileTotal = 0
    ghStartedAt = SystemClock.elapsedRealtime()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    ghHandler.removeCallbacks(ghTicker)
    ghHandler.postDelayed(ghTicker, 1000L)
    ghOnProgress(GhProgress(0, "Menyiapkan $kind…"))
    toolThread(showLoading = false) {
        val result = runCatching {
            task { p ->
                if (ghCancelled) throw GhCancelException()
                runOnToolUiThread { ghOnProgress(p) }
            }
        }
        // Clear the operation state before the UI-gated callback. If the user
        // navigated away while the upload was running, runOnToolUiThread may
        // intentionally drop the callback; the upload must still become
        // available for a future run.
        synchronized(ghRunLock) { ghRunning = false }
        runOnToolUiThread {
            ghHandler.removeCallbacks(ghTicker)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ghFinishedSec = ((SystemClock.elapsedRealtime() - ghStartedAt) / 1000L).toInt()
            val visible = ghStage?.isAttachedToWindow == true
            val cancelled = ghCancelled || result.exceptionOrNull() is GhCancelException
            ghCancelled = false
            when {
                cancelled -> {
                    BitRuntimeContext.onProcessCancelled()
                    ghPendingResult = null; ghPendingError = null
                    if (visible) { toast("Upload dibatalkan"); ghShow(ghSettingsScreen(), "Pengaturan GitHub") }
                }
                result.isSuccess -> {
                    val r = result.getOrThrow()
                    val msg = "${r.owner}/${r.repo} • ${r.files} file berhasil diunggah ke branch ${r.branch}"
                    BitRuntimeContext.onProcessSuccess(msg)
                    // Selalu kirim notifikasi (baik app di foreground maupun background)
                    ghNotifyDone(true, msg)
                    if (visible) ghShowResult(r) else ghPendingResult = r
                }
                else -> {
                    val e = result.exceptionOrNull() ?: IOException("Upload gagal")
                    val friendly = ghFriendlyError(e)
                    val errorTitle = when {
                        ghIsTokenAuthError(e) -> "Token GitHub perlu diperbarui"
                        ghIsNetworkError(e) -> "Tidak ada koneksi internet"
                        else -> "Upload gagal"
                    }
                    BitRuntimeContext.onProcessError(errorTitle, ghMaskSecrets(friendly))
                    // Selalu kirim notifikasi gagal
                    ghNotifyDone(false, "$errorTitle — ${friendly.take(120)}")
                    if (visible) ghShowFailure(e) else ghPendingError = e
                }
            }
        }
    }
}

internal fun MainActivity.ghOnProgress(p: GhProgress, store: Boolean = true) {
    if (store && p.step >= ghCurrentStep) {
        ghStepLast[p.step.coerceIn(0, 4)] = p
        ghPct = when (p.step) {
            0 -> 4
            1 -> 12
            2 -> if (p.total > 0) 15 + (70 * (p.current - 1).coerceAtLeast(0)) / p.total else 15
            3 -> 88
            else -> 96
        }
        ghCurrentStep = maxOf(ghCurrentStep, p.step)
    }
    val percentForContext = when (p.step) {
        0 -> 4
        1 -> 12
        2 -> if (p.total > 0) 15 + (70 * (p.current - 1).coerceAtLeast(0)) / p.total else 15
        3 -> 88
        else -> 96
    }
    BitRuntimeContext.onProcessProgress(percentForContext, p.current.takeIf { p.total > 0 }, p.total.takeIf { it > 0 }, p.detail)
    if (ghStage?.isAttachedToWindow != true || ghStepViews.size < 5) return
    if (p.step < ghCurrentStep && store) return
    ghCurrentStep = p.step
    // Setelah tahap "Push", pembatalan tidak lagi aman/berguna.
    if (p.step >= 3 && !ghCancelled) {
        ghCancelBtn?.alpha = 0.4f
        ghCancelBtn?.isEnabled = false
        ghCancelBtn?.isClickable = false
    }
    for (i in 0..4) {
        val state = when {
            i < p.step -> StepStateView.DONE
            i == p.step -> StepStateView.ACTIVE
            else -> StepStateView.PENDING
        }
        ghStepViews[i].setState(state)
        ghStepTitles[i].setTextColor(if (i <= p.step) ghInk else ghMuted)
        if (i == p.step) ghStepTitles[i].setTypeface(null, android.graphics.Typeface.BOLD)
        else ghStepTitles[i].setTypeface(null, android.graphics.Typeface.NORMAL)
    }
    if (p.step == 2 && p.total > 0) {
        ghFileTotal = p.total
        ghStepTitles[2].text = "Mengunggah file (${p.current}/${p.total})"
    }
    if (p.step > 2 && ghFileTotal > 0) {
        ghStepTitles[2].text = "Mengunggah file ($ghFileTotal/$ghFileTotal)"
        ghStepDetails[2].text = "$ghFileTotal file terunggah"
        ghStepDetails[2].visibility = View.VISIBLE
    }
    if (p.detail.isNotBlank() && !(p.step == 2 && p.total == 0)) {
        ghStepDetails[p.step].text = p.detail
        ghStepDetails[p.step].visibility = View.VISIBLE
    }
    val pct = when (p.step) {
        0 -> 4
        1 -> 12
        2 -> if (p.total > 0) 15 + (70 * (p.current - 1).coerceAtLeast(0)) / p.total else 15
        3 -> 88
        else -> 96
    }
    ghRing?.let { it.indeterminate = false; it.setProgress(pct.toFloat()) }
    ghPercentText?.text = "$pct%"
}

internal fun MainActivity.ghOpenConnectionSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }.onFailure {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }.logFailure("gh.openSettings")
    }
}

internal fun MainActivity.ghShowFailure(error: Throwable) {
    if (ghStage?.isAttachedToWindow != true) return
    val failedStep = ghCurrentStep.coerceIn(0, 4)
    if (ghStepViews.size == 5) {
        ghStepViews[failedStep].setState(StepStateView.FAILED)
        ghStepTitles[failedStep].setTextColor(ghDanger)
    }
    ghRing?.let { it.indeterminate = false; it.ringColor = ghDanger }
    ghNoteBox?.visibility = View.GONE
    val host = ghErrorHost ?: return
    host.removeAllViews()

    val tokenError = ghIsTokenAuthError(error)
    val networkError = ghIsNetworkError(error)
    val title = when {
        tokenError -> "Token GitHub perlu diperbarui"
        networkError -> "Tidak ada koneksi internet"
        else -> "Upload gagal"
    }
    val message = when {
        tokenError -> "GitHub menolak token yang digunakan (HTTP 401). Token bisa kedaluwarsa, dicabut, atau tidak valid. Perbarui token GitHub lalu coba upload lagi."
        networkError -> "GITLS tidak dapat terhubung ke GitHub. Periksa koneksi internet/Wi-Fi Anda, lalu coba lagi.\n\nJika jaringan sudah aktif tetapi tetap gagal, coba buka pengaturan koneksi dan pastikan internet dapat digunakan."
        else -> ghFriendlyError(error)
    }
    showGhBitProcessError(title, message)

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = ghRound(ghCard, 16, ghDanger)
    }
    card.addView(ghText(title, 15f, ghDanger, true))
    card.addView(ghText(message, 12.5f, ghMuted).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.15f) })
    host.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

    if (tokenError) {
        host.addView(ghPrimaryButton("Perbarui token GitHub") {
            ghShow(ghSettingsScreen(), "Pengaturan GitHub")
        })
        host.addView(ghOutlineButton("Coba lagi", "refresh") { ghRetry?.invoke() })
    } else if (networkError) {
        host.addView(ghPrimaryButton("Cek koneksi internet") { ghOpenConnectionSettings() })
        host.addView(ghOutlineButton("Coba lagi", "refresh") { ghRetry?.invoke() })
    } else {
        host.addView(ghPrimaryButton("Coba lagi") { ghRetry?.invoke() })
        host.addView(ghOutlineButton("Ubah pengaturan", "cog-outline") { ghShow(ghSettingsScreen(), "Pengaturan GitHub") })
    }
    host.alpha = 0f
    host.animate().alpha(1f).setDuration(240).start()
    toast(title)
}

// =====================================================================
// Layar 3: Upload Selesai
// =====================================================================
internal fun MainActivity.ghShowResult(result: GhResult) {
    if (ghStage?.isAttachedToWindow != true) return
    bitProcessErrorView?.let { it.animate().cancel(); it.visibility = View.GONE }
    bitAnim?.playRecovered()
    ghLastResult = result
    val elapsed = if (ghFinishedSec >= 0) ghFinishedSec else ((SystemClock.elapsedRealtime() - ghStartedAt) / 1000L).toInt()
    val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(2), dp(2), dp(10)) }

    val isDryRun = result.commitSha == "DRY-RUN"
    val badge = SuccessBadgeView(this).apply { inkColor = ghInk; onInkColor = ghOnInk }
    screen.addView(badge, LinearLayout.LayoutParams(dp(82), dp(82)).apply { bottomMargin = dp(8) })

    // Dry Run tidak melakukan commit; tampilkan hasil sebagai pemeriksaan, bukan upload sukses.
    screen.addView(ghText(if (isDryRun) "Pemeriksaan Selesai" else "Upload Berhasil!", 24f, ghInk, true).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
    }, LinearLayout.LayoutParams(-1, -2).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    })

    val subtitle = when {
        isDryRun -> "Dry Run: tidak ada commit atau perubahan yang dikirim ke GitHub."
        result.createdRepo -> "Repository baru dibuat (${if (result.createdPrivate) "private" else "public"}) dan project berhasil diunggah."
        else -> "Project berhasil diunggah ke GitHub"
    }
    screen.addView(ghText(subtitle, 13f, ghMuted).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        setPadding(dp(20), dp(3), dp(20), 0)
        setLineSpacing(0f, 1.15f)
    }, LinearLayout.LayoutParams(-1, -2).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    })

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(6), dp(16), dp(6))
        background = ghRound(ghCard, 20, ghStroke)
    }
    fun infoRow(iconName: String, labelText: String, valueText: String, trailing: String? = null, onClick: (() -> Unit)? = null, last: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(ghIcon(iconName, 22f, ghInk), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ghText(labelText, 11.5f, ghMuted))
        col.addView(ghText(valueText, 15f, ghInk, true).apply { setPadding(0, dp(3), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) row.addView(ghIcon(trailing, 19f, ghMuted), LinearLayout.LayoutParams(dp(26), dp(26)))
        if (onClick != null) ghPressable(row) { onClick.invoke() }
        card.addView(row)
        if (!last) card.addView(View(this).apply { setBackgroundColor(ghStroke) }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(44) })
    }
    infoRow("source-repository", "Repository", "${result.owner}/${result.repo}", "open-in-new", { ghOpenUrl(result.url) })
    infoRow("source-branch", "Branch", result.branch)
    infoRow("file-tree-outline", if (isDryRun) "File yang akan dikirim" else "Total File", "${result.files} file")
    if (result.addedFiles > 0 || result.updatedFiles > 0 || result.deletedFiles > 0 || result.unchangedFiles > 0) {
        val parts = ArrayList<String>()
        parts += "${result.addedFiles} baru"
        parts += "${result.updatedFiles} diperbarui"
        if (result.deletedFiles > 0) parts += "${result.deletedFiles} dihapus"
        if (result.unchangedFiles > 0) parts += "${result.unchangedFiles} tidak berubah"
        infoRow("file-compare", "Rincian", parts.joinToString(" • "))
    }
    infoRow("harddisk", "Ukuran", ghFormatBytes(result.bytes))
    infoRow("source-commit", if (isDryRun) "Mode" else "Commit",
        if (isDryRun) "DRY RUN" else result.commitSha.take(7),
        if (isDryRun) null else "content-copy",
        if (isDryRun) null else ({ ghCopy("Commit SHA", result.commitSha); toast("SHA commit disalin") }))
    infoRow("timer-outline", "Durasi", if (elapsed >= 60) "${elapsed / 60} mnt ${elapsed % 60} dtk" else "$elapsed dtk", last = true)
    screen.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(12) })

    if (!isDryRun) screen.addView(ghPrimaryButton("Lihat di GitHub") { ghOpenUrl(result.url + "/tree/" + result.branch) })
    if (!isDryRun && result.workflowFiles.isNotEmpty()) {
        screen.addView(ghOutlineButton("Cek GitHub Actions", "play-circle-outline") { ghShowActionsScreen(result) })
        screen.addView(ghText("Workflow terdeteksi: ${result.workflowFiles.joinToString(", ")}", 11f, ghMuted).apply {
            setPadding(dp(4), dp(5), dp(4), dp(8)); setLineSpacing(0f, 1.1f)
        })
    }
    screen.addView(ghOutlineButton("Upload Lagi", "upload-network") { ghShow(ghSettingsScreen(), "Pengaturan GitHub") })
    screen.addView(ghOutlineButton("Salin tautan repository", "link-variant") { ghCopy("Repository", result.url); toast("Tautan disalin") })

    ghShow(screen, "Upload Selesai")
    badge.postDelayed({ badge.play() }, 120L)
    screen.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    toast(if (isDryRun) "Dry Run selesai — tidak ada perubahan dikirim" else "Upload GitHub selesai")
}

// =====================================================================
// GitHub Actions Monitor: tampilkan urutan langkah, bukan log/code mentah.
// =====================================================================
internal fun MainActivity.ghShowActionsScreen(result: GhResult) {
    ghActionPoll?.removeCallbacksAndMessages(null)

    // ================================================================
    // Tampilan mengikuti "Proses Upload": satu Bit besar di atas, target,
    // lalu timeline dengan StepStateView yang sama (loading/check/error).
    // ================================================================
    val screen = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(2), dp(2), dp(24))
    }

    // Hanya tampilkan satu Bit besar pada layar GitHub Actions.
    // Header Bit kecil di kanan atas sengaja tidak dibuat agar tidak muncul dua bot.
    // Hero ditempatkan dekat bagian atas, mengikuti tampilan Actions yang diinginkan.
    val hero = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    val heroBitHost = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        contentDescription = "Bit — status Actions"
    }
    val heroFace = LottieAnimationView(this).apply {
        setBitAsset("bit/bit_idle.json")
        repeatMode = LottieDrawable.RESTART
        repeatCount = LottieDrawable.INFINITE
        setMinAndMaxFrame(0, 108)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        isClickable = false
        contentDescription = "Bit"
    }
    applyBitFaceTheme(heroFace)
    bitAnim?.attach(heroFace)
    heroBitHost.addView(
        heroFace,
        FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER)
    )
    heroBitHost.setOnClickListener { bitAnim?.onTap(heroFace) }
    addPressFeedback(heroBitHost)
    hero.addView(
        heroBitHost,
        LinearLayout.LayoutParams(dp(140), dp(140)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(4)
        }
    )
    // Repo + branch di bawah Bit (tanpa angka persen).
    val target = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = ghRound(ghSoft, 14)
    }
    target.addView(
        ghIcon("source-repository", 15f, ghMuted),
        LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(6) }
    )
    target.addView(
        ghText("${result.owner}/${result.repo}", 12.5f, ghInk, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        },
        LinearLayout.LayoutParams(0, -2, 1f)
    )
    target.addView(
        ghIcon("source-branch", 15f, ghMuted),
        LinearLayout.LayoutParams(dp(18), dp(18)).apply {
            leftMargin = dp(10)
            rightMargin = dp(4)
        }
    )
    target.addView(ghText(result.branch, 12.5f, ghMuted))
    hero.addView(target, LinearLayout.LayoutParams(-2, -2).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(6)
        bottomMargin = dp(4)
    })
    // Dummy ring + percent agar kode polling lama tidak putus (disembunyikan).
    val percent = ghText("", 1f, ghInk, true).apply { visibility = View.GONE }
    val ring = ProgressRingView(this).apply {
        visibility = View.GONE
        ringColor = ghInk
        trackColor = ghSoft
        indeterminate = true
    }
    screen.addView(
        hero,
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(0)
            bottomMargin = dp(12)
        }
    )

    val host = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
    }
    screen.addView(host, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    val note = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = ghRound(ghSoft, 16)
    }
    val noteSpinner = StepStateView(this).apply {
        inkColor = ghInk
        onInkColor = ghOnInk
        mutedColor = ghStroke
        dangerColor = ghDanger
        setState(StepStateView.ACTIVE, false)
    }
    note.addView(noteSpinner, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(12) })
    val noteTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val noteTitle = ghText("Actions sedang berjalan…", 12.5f, ghMuted).apply {
        setLineSpacing(0f, 1.1f)
    }
    val noteDetail = ghText("Memperbarui status otomatis…", 11.5f, ghMuted).apply {
        setPadding(0, dp(4), 0, 0)
    }
    noteTexts.addView(noteTitle)
    noteTexts.addView(noteDetail)
    note.addView(noteTexts, LinearLayout.LayoutParams(0, -2, 1f))
    screen.addView(note, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

    screen.addView(
        ghOutlineButton("Buka Actions di GitHub", "open-in-new") {
            ghOpenUrl("${result.url}/actions")
        }
    )
    screen.addView(
        ghOutlineButton("Kembali", "arrow-left") {
            ghShowResult(result)
        }
    )

    ghShow(screen, "GitHub Actions")

    // Status view dipakai ulang antar polling agar spinner tetap hidup dan
    // centang hanya melakukan pop/garis saat state benar-benar berubah.
    val stateViews = HashMap<String, StepStateView>()

    fun stateOf(statusOrConclusion: String?): Int = when (statusOrConclusion) {
        "success", "completed", "neutral" -> StepStateView.DONE
        "failure", "cancelled", "timed_out", "startup_failure" -> StepStateView.FAILED
        // GitHub may report a live job/step as queued or waiting before it
        // switches to in_progress. Show the same animated activity state so
        // the timeline never looks frozen while the workflow is actually running.
        "in_progress", "queued", "waiting", "requested", "pending" -> StepStateView.ACTIVE
        else -> StepStateView.PENDING
    }

    fun stepDisplayState(status: String?, firstLiveIndex: Int, index: Int, running: Boolean): Int {
        val terminalDone = status in setOf("success", "completed", "neutral")
        val terminalFailed = status in setOf("failure", "cancelled", "timed_out", "startup_failure")
        return when {
            terminalFailed -> StepStateView.FAILED
            terminalDone -> StepStateView.DONE
            !running -> StepStateView.PENDING
            status == "in_progress" -> StepStateView.ACTIVE
            // A queued/waiting step is often the actual current step before
            // GitHub reports in_progress. Animate only the first unfinished
            // step; future steps remain clean/pending rather than all spinning.
            index == firstLiveIndex -> StepStateView.ACTIVE
            else -> StepStateView.PENDING
        }
    }

    fun stateView(key: String, state: Int): StepStateView {
        val existing = stateViews[key]
        val view = existing ?: StepStateView(this).apply {
            inkColor = ghInk
            onInkColor = ghOnInk
            mutedColor = ghStroke
            dangerColor = ghDanger
            stateViews[key] = this
        }
        (view.parent as? ViewGroup)?.removeView(view)
        view.setState(state, existing != null)
        return view
    }

    fun doneState(value: String?): Boolean = when (value) {
        "success", "completed", "neutral" -> true
        else -> false
    }

    fun render(
        message: String,
        jobs: List<GhActionJob> = emptyList(),
        runUrl: String = "",
        running: Boolean = true,
        runFound: Boolean = false
    ) {
        host.removeAllViews()

        val allSteps = jobs.flatMap { job -> job.steps.map { it.second } }
            .filter { it != "skipped" }
        val completedSteps = allSteps.count(::doneState)
        val hasActiveStep = allSteps.any { it == "in_progress" }
        val failed = jobs.any { job ->
            job.conclusion in listOf("failure", "cancelled", "timed_out", "startup_failure") ||
                job.steps.any { it.second in listOf("failure", "cancelled", "timed_out", "startup_failure") }
        }
        val calculatedPercent = when {
            !runFound -> 0
            !running -> 100
            allSteps.isNotEmpty() -> ((completedSteps * 100f) / allSteps.size)
                .roundToInt().coerceIn(0, 98)
            hasActiveStep -> 8
            jobs.isNotEmpty() -> 6
            else -> 0
        }

        if (!runFound) {
            ring.indeterminate = true
        } else {
            ring.indeterminate = false
            ring.ringColor = ghInk
            ring.setProgress(calculatedPercent.toFloat())
        }
        // Angka persen tidak ditampilkan; status lewat note + ekspresi Bit.
        percent.visibility = View.GONE
        noteTitle.text = when {
            failed && !running -> "Actions gagal."
            !running -> "Actions selesai."
            else -> message.ifBlank { "Actions sedang berjalan…" }
        }
        noteDetail.text = when {
            failed && !running -> {
                val failNames = jobs.filter { j ->
                    j.conclusion in listOf("failure", "cancelled", "timed_out", "startup_failure") ||
                        j.steps.any { it.second in listOf("failure", "cancelled", "timed_out", "startup_failure") }
                }.map { it.name }.distinct()
                if (failNames.isNotEmpty())
                    "Bit: ada kesalahan di ${failNames.take(2).joinToString(", ")}. Cek langkah bertanda silang atau unduh log."
                else
                    "Bit: workflow gagal. Cek langkah bertanda silang atau unduh log."
            }
            !runFound -> "Menunggu GitHub membuat workflow run…"
            runUrl.isNotBlank() && running -> "Run ditemukan • status diperbarui otomatis"
            !running -> "Pemeriksaan workflow selesai"
            else -> "Menunggu langkah berikutnya…"
        }
        if (failed && !running) {
            bitAnim?.playError()
            bitAnim?.playSad(heroFace)
        } else if (!running) {
            bitAnim?.playRecovered()
        }
        noteSpinner.setState(
            when {
                failed && !running -> StepStateView.FAILED
                !running -> StepStateView.DONE
                else -> StepStateView.ACTIVE
            },
            true
        )

        if (runUrl.isNotBlank()) {
            host.addView(
                ghText("Run ditemukan", 13f, ghInk, true).apply {
                    setPadding(dp(4), 0, dp(4), dp(8))
                }
            )
        }

        jobs.forEach { job ->
            val jobState = job.conclusion?.let(::stateOf) ?: when {
                job.status in setOf("in_progress", "queued", "waiting", "requested", "pending") && running -> StepStateView.ACTIVE
                else -> stateOf(job.status)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = ghRound(ghCard, 15, ghStroke)
            }

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(
                stateView("job-${job.id}", jobState),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) }
            )
            titleRow.addView(ghText(job.name, 14f, ghInk, true))
            card.addView(titleRow)

            if (job.steps.isEmpty()) {
                card.addView(
                    ghText(job.status.ifBlank { "Menunggu…" }, 11.5f, ghMuted).apply {
                        setPadding(0, dp(7), 0, 0)
                    }
                )
            } else {
                val timeline = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), 0, 0)
                }
                val firstLiveIndex = job.steps.indexOfFirst { pair ->
                    val status = pair.second
                    status !in setOf("success", "completed", "neutral", "failure", "cancelled", "timed_out", "startup_failure", "skipped")
                }
                job.steps.forEachIndexed { index, pair ->
                    val name = pair.first
                    val status = pair.second
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(5), 0, dp(5))
                    }
                    if (status == "skipped") {
                        row.addView(
                            ghText("–", 12f, ghMuted, true).apply { gravity = Gravity.CENTER },
                            LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) }
                        )
                        row.addView(ghText(name, 11.5f, ghMuted))
                    } else {
                        val stepState = stepDisplayState(status, firstLiveIndex, index, running)
                        row.addView(
                            stateView("${job.id}#$index", stepState),
                            LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) }
                        )
                        row.addView(
                            ghText(name, 11.5f, if (stepState == StepStateView.PENDING) ghMuted else ghInk).apply {
                                if (stepState == StepStateView.ACTIVE) setTypeface(null, android.graphics.Typeface.BOLD)
                                maxLines = 2
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            }
                        )
                    }
                    timeline.addView(row)
                    if (index < job.steps.lastIndex) {
                        timeline.addView(
                            View(this).apply {
                                setBackgroundColor(ghStroke)
                                alpha = 0.72f
                            },
                            LinearLayout.LayoutParams(dp(2), dp(8)).apply {
                                leftMargin = dp(9)
                                topMargin = dp(-3)
                                bottomMargin = dp(-3)
                            }
                        )
                    }
                }
                card.addView(timeline)
            }

            if (job.conclusion == "failure" || job.conclusion == "cancelled") {
                ghActionLastFailedJob = job
                card.addView(
                    ghOutlineButton("Salin kesalahan", "content-copy") {
                        ghCopy(
                            "GitHub Actions",
                            "${job.name}\nStatus: ${job.conclusion}\n" +
                                job.steps.joinToString("\n") { "${it.first}: ${it.second ?: ""}" }
                        )
                        toast("Ringkasan disalin")
                    }.apply { setPadding(0, dp(8), 0, 0) }
                )
                card.addView(
                    ghOutlineButton("Download log gagal", "download") {
                        ghDownloadActionLog(result, job)
                    }.apply { setPadding(0, dp(4), 0, 0) }
                )
            }

            host.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        if (!running && failed) {
            host.addView(
                ghText(
                    "Actions gagal. Periksa langkah bertanda silang. Kamu bisa menyalin ringkasan atau mengunduh log untuk pemeriksaan lebih lanjut.",
                    12f,
                    ghMuted
                ).apply {
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    setLineSpacing(0f, 1.1f)
                }
            )
        }
    }

    fun poll() {
        thread {
            try {
                val headers = ghHeaders(ghTokenValue)
                val base = "https://api.github.com/repos/${Uri.encode(result.owner)}/${Uri.encode(result.repo)}"
                val runs = githubRequestRetry(
                    "GET",
                    "$base/actions/runs?branch=${Uri.encode(result.branch)}&per_page=10",
                    null,
                    headers
                )
                val arr = runs.optJSONArray("workflow_runs") ?: JSONArray()
                var run: JSONObject? = null
                for (i in 0 until arr.length()) {
                    val candidate = arr.optJSONObject(i) ?: continue
                    if (candidate.optString("head_sha") == result.commitSha) {
                        run = candidate
                        break
                    }
                }
                if (run == null) {
                    runOnToolUiThread { render("Actions sedang berjalan…", runFound = false) }
                    ghActionPoll?.postDelayed(ghActionRunnable!!, 3000L)
                    return@thread
                }

                val currentRun = run
                val runId = currentRun.optLong("id")
                val runUrl = currentRun.optString("html_url")
                val jobsJson = githubRequestRetry(
                    "GET",
                    "$base/actions/runs/$runId/jobs?per_page=100",
                    null,
                    headers
                )
                val jobsArr = jobsJson.optJSONArray("jobs") ?: JSONArray()
                val jobs = mutableListOf<GhActionJob>()
                for (i in 0 until jobsArr.length()) {
                    val j = jobsArr.optJSONObject(i) ?: continue
                    val stepsArr = j.optJSONArray("steps") ?: JSONArray()
                    val steps = mutableListOf<Pair<String, String?>>()
                    for (k in 0 until stepsArr.length()) {
                        val st = stepsArr.optJSONObject(k) ?: continue
                        steps += st.optString("name") to st.optString("conclusion")
                            .ifBlank { st.optString("status").ifBlank { null } }
                    }
                    jobs += GhActionJob(
                        j.optLong("id"),
                        j.optString("name", "Job"),
                        j.optString("status"),
                        j.optString("conclusion").ifBlank { null },
                        steps,
                        j.optString("html_url")
                    )
                }

                val completed = currentRun.optString("status") == "completed"
                runOnToolUiThread {
                    render(
                        if (completed) "Actions selesai." else "Actions sedang berjalan…",
                        jobs,
                        runUrl,
                        !completed,
                        runFound = true
                    )
                }
                if (!completed) {
                    ghActionPoll?.postDelayed(ghActionRunnable!!, 3000L)
                } else {
                    ghActionPollWanted = false
                }
            } catch (e: Throwable) {
                val raw = e.message.orEmpty()
                val message = when {
                    raw.contains("HTTP 401", true) -> "Token GitHub tidak valid atau kedaluwarsa. Perbarui token lalu cek Actions lagi."
                    raw.contains("HTTP 403", true) -> "Token berhasil dipakai untuk repository, tetapi akses GitHub Actions ditolak. Pastikan token memiliki izin Actions: Read."
                    ghIsNetworkError(e) -> "Tidak ada koneksi internet. Periksa Wi-Fi/data lalu coba lagi."
                    else -> ghFriendlyError(e)
                }
                runOnToolUiThread {
                    bitAnim?.playError()
                    bitAnim?.playSad(heroFace)
                    noteTitle.text = "Actions error"
                    noteDetail.text = "Bit: $message"
                    noteSpinner.setState(StepStateView.FAILED, true)
                    render("Belum bisa membaca Actions: $message", runFound = true)
                }
                ghActionPoll?.postDelayed(ghActionRunnable!!, 5000L)
            }
        }
    }

    ghActionPoll = Handler(Looper.getMainLooper())
    ghActionRunnable = Runnable { poll() }
    ghActionPollWanted = true
    poll()
}

internal fun MainActivity.ghDownloadActionLog(result: GhResult, job: GhActionJob) {
    thread {
        try {
            val url = "https://api.github.com/repos/${Uri.encode(result.owner)}/${Uri.encode(result.repo)}/actions/jobs/${job.id}/logs"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 20000; readTimeout = 60000
                ghHeaders(ghTokenValue).forEach { (k,v) -> setRequestProperty(k,v) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IOException("GitHub HTTP $code: ${err.take(240)}")
            }
            val contentType = conn.contentType.orEmpty().lowercase()
            val out = File(getExternalFilesDir(null), "GITLS_Actions_Failed_${job.id}.zip")
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, out.name + ".part")
            var head = ByteArray(0)
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { os ->
                        val buf = ByteArray(16 * 1024)
                        var first = true
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (first && n > 0) { head = buf.copyOf(minOf(n, 4)); first = false }
                            os.write(buf, 0, n)
                        }
                    }
                }
            } finally { conn.disconnect() }
            // Endpoint job logs mengembalikan arsip; jangan simpan error JSON/HTML sebagai .zip.
            val looksLikeZip = head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte() &&
                (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte()) &&
                (head[3] == 0x04.toByte() || head[3] == 0x06.toByte() || head[3] == 0x08.toByte())
            if (!(looksLikeZip || contentType.contains("zip") || contentType.contains("octet-stream"))) {
                tmp.delete()
                throw IllegalArgumentException("GitHub tidak mengembalikan file log ZIP yang valid")
            }
            out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            runOnToolUiThread { toast("Log gagal disimpan: ${out.name}"); shareFile(out) }
        } catch (e: Throwable) {
            runOnToolUiThread { toast("Gagal download log: ${ghFriendlyError(e)}") }
        }
    }
}

internal fun MainActivity.ghOpenUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.logFailure("gh.openUrl")
        .onFailure { toast("Tidak ada aplikasi untuk membuka tautan") }
}

internal fun MainActivity.ghCopy(labelText: String, value: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(labelText, value))
}

internal fun MainActivity.prepareGithubZipPreview(uri: Uri) {
    toolThread {
        val result = runCatching {
            val workDir = File(cacheDir, "github_preview_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val zipFile = File(workDir, "preview.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(zipFile).use { output -> input.copyTo(output) }
                } ?: error("ZIP tidak dapat dibaca")
                val extracted = File(workDir, "src").apply { mkdirs() }
                unzipSafeForGithub(zipFile, extracted)
                analyzeGithubZip(extracted)
            } finally {
                workDir.deleteRecursively()
            }
        }
        runOnToolUiThread {
            result.onSuccess { analysis ->
                githubZipPreviewFiles = analysis.files
                githubZipPreviewDirs = analysis.dirs
                githubZipExcluded.clear()
                githubZipExcluded.addAll(analysis.excludedDefaults)
                githubZipRoot = analysis.suggestedRoot
                githubZipUploadSummary()
                githubUploadStatus?.text = "ZIP dianalisis: ${analysis.files.size} file. Root: ${if (analysis.suggestedRoot.isBlank()) "/" else analysis.suggestedRoot + "/"}"
            }.onFailure { e ->
                githubUploadStatus?.text = "Analisis ZIP gagal: ${e.message ?: "Unknown error"}"
            }
        }
    }
}

internal fun MainActivity.githubZipUploadSummary() {
    val root = githubZipRoot.trim('/').trim()
    val patterns = githubExcludePatterns()
    val count = githubZipPreviewFiles.count { path ->
        !githubZipExcluded.any { excluded -> path == excluded || path.startsWith("$excluded/") } &&
        (root.isBlank() || path == root || path.startsWith("$root/")) &&
        !githubPathExcludedByPatterns(patterns, if (root.isBlank()) path else path.removePrefix("$root/"))
    }
    val patternNote = if (patterns.isEmpty()) "" else " • Pola: ${patterns.joinToString(", ")}"
    githubUploadStatus?.text = "Siap: $count file akan di-upload • Root: ${if (root.isBlank()) "/" else root + "/"} • Exclude: ${githubZipExcluded.size}$patternNote"
}

internal fun MainActivity.showGithubZipPreview(uri: Uri) {
    if (githubZipPreviewFiles.isEmpty()) {
        githubUploadStatus?.text = "Menganalisis ZIP..."
        prepareGithubZipPreview(uri)
        return
    }

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(28, 8, 28, 8)
    }
    val rootLabel = TextView(this).apply {
        text = "Repository root"
        textSize = 13f
        setTextColor(textMuted)
    }
    box.addView(rootLabel)

    // Daftar fungsi penyegar baris; dipanggil saat pola/root berubah.
    val refreshers = mutableListOf<() -> Unit>()
    val rootSpinner = Spinner(this).apply {
        background = ghRound(ghSoft, 14, ghStroke)
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }
    val roots = listOf("/ (root ZIP)") + githubZipPreviewDirs.map { "$it/" }
    val currentRoot = githubZipRoot.trim('/').let { if (it.isBlank()) "/ (root ZIP)" else "$it/" }
    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roots)
    rootSpinner.adapter = adapter
    rootSpinner.setSelection(maxOf(0, roots.indexOf(currentRoot)))
    rootSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            githubZipRoot = if (position == 0) "" else roots[position].trimEnd('/')
            refreshers.forEach { it() }
            githubZipUploadSummary()
        }
    }
    box.addView(rootSpinner)
    box.addView(subLabel("Folder pembungkus otomatis dideteksi. Pilih folder yang akan menjadi root repository. Tombol × mengecualikan file/folder dari upload.", 11f))

    var patterns = githubExcludePatterns()
    val patternInfo = subLabel("", 11f)
    refreshers.add {
        patterns = githubExcludePatterns()
        patternInfo.text = if (patterns.isEmpty()) "Pola pengecualian: belum ada" else "Pola pengecualian: ${patterns.joinToString(", ")}"
    }
    refreshers[0]()
    box.addView(ghSmallButton("Kecualikan jenis file (md, txt, dll.)", "eye-off-outline") {
        showGithubExcludeDialog {
            refreshers.forEach { it() }
            githubZipUploadSummary()
        }
    }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
    box.addView(patternInfo)

    val scroll = ScrollView(this)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    githubZipPreviewFiles.forEach { path ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(8), dp(5))
            background = ghRound(ghCard, 12, ghStroke)
        }
        val label = TextView(this).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val remove = MdiIconView(this).apply {
            setIconSize(20f)
            gravity = Gravity.CENTER
            setTextColor(textMain)
            setPadding(18, 8, 12, 8)
        }
        fun refreshRow() {
            val byPath = githubZipExcluded.any { ex -> path == ex || path.startsWith("$ex/") }
            val rootNow = githubZipRoot.trim('/')
            val inner = if (rootNow.isBlank()) path else path.removePrefix("$rootNow/")
            val byPattern = !byPath && githubPathExcludedByPatterns(patterns, inner)
            label.text = when {
                byPath -> "⊘ $path"
                byPattern -> "⊘ $path  (pola)"
                else -> "• $path"
            }
            label.setTextColor(if (byPath || byPattern) textMuted else textMain)
            remove.setIconName(if (githubZipExcluded.contains(path)) "check" else "close")
            // File yang sudah dikecualikan oleh pola tidak bisa di-"batalkan" dari sini; ubah polanya.
            remove.alpha = if (byPattern) 0.35f else 1f
        }
        refreshRow()
        refreshers.add { refreshRow() }
        remove.setOnClickListener {
            if (githubZipExcluded.contains(path)) githubZipExcluded.remove(path) else githubZipExcluded.add(path)
            refreshRow()
            githubZipUploadSummary()
        }
        row.addView(label)
        row.addView(remove)
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }
    scroll.addView(list)
    val previewHeight = (340 * resources.displayMetrics.density).roundToInt()
    box.addView(scroll, LinearLayout.LayoutParams(-1, previewHeight))

    AlertDialog.Builder(this)
        .setTitle("Isi ZIP • ${githubZipPreviewFiles.size} file")
        .setView(box)
        .setNegativeButton("Tutup", null)
        .setPositiveButton("Simpan Pilihan", null)
        .show().also { ghStyleRoundedDialog(it, 24) }
}

/**
 * Dialog pola pengecualian upload. Centang jenis umum (md, txt, ...) atau tulis pola sendiri.
 * Tersimpan permanen dan berlaku untuk upload ZIP maupun folder.
 */
internal fun MainActivity.showGithubExcludeDialog(onChanged: () -> Unit) {
    val presets = listOf("md", "txt", "log", "bak", "tmp", "lock", "zip", "apk")
    val current = githubExcludePatterns()
    val active = linkedSetOf<String>()
    current.forEach { if (it.lowercase(Locale.ROOT) in presets) active.add(it.lowercase(Locale.ROOT)) }
    val customInitial = current.filter { it.lowercase(Locale.ROOT) !in presets }.joinToString(", ")

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    box.addView(ghText(
        "File dengan jenis yang dicentang atau ditulis di bawah tidak akan di-upload. Berlaku untuk ZIP dan folder. File yang sama yang sudah ada di repository tidak ikut dihapus.",
        12f, ghMuted
    ).apply { setLineSpacing(0f, 1.15f); setPadding(0, 0, 0, dp(6)) })

    val custom = EditText(this).apply {
        hint = "Pola lain, mis. *.test.js, docs/, README.md"
        setText(customInitial)
        textSize = 13f
        setTextColor(textMain)
        setHintTextColor(textMuted)
        background = ghRound(ghCard, 12, ghStroke)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    fun commit() {
        githubSaveExcludePatterns(active.toList() + githubParseExcludePatterns(custom.text.toString()))
        onChanged()
    }
    presets.chunked(4).forEach { rowItems ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowItems.forEach { ext ->
            row.addView(CheckBox(this).apply {
                text = ".$ext"
                textSize = 13f
                setTextColor(textMain)
                isChecked = ext in active
                setOnCheckedChangeListener { _, on ->
                    if (on) active.add(ext) else active.remove(ext)
                    commit()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        box.addView(row)
    }
    box.addView(custom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    ghWatch(custom) { commit() }
    box.addView(ghText("Contoh: md  •  *.test.js  •  docs/  •  README.md  •  src/*.txt", 11f, ghMuted)
        .apply { setPadding(dp(2), dp(6), 0, 0) })

    AlertDialog.Builder(this)
        .setTitle("Kecualikan jenis file")
        .setView(ScrollView(this).apply { addView(box) })
        .setNeutralButton("Hapus semua") { _, _ ->
            githubSaveExcludePatterns(emptyList())
            onChanged()
        }
        .setPositiveButton("Selesai", null)
        .show().also { ghStyleRoundedDialog(it, 24) }
}

internal fun MainActivity.uploadZipToGitHub(
    uri: Uri,
    owner: String,
    repo: String,
    token: String,
    branch: String,
    commitMessage: String,
    uploadRoot: String,
    excludedPaths: Set<String>,
    createPrivate: Boolean,
    progress: (GhProgress) -> Unit,
    onlyChanged: Boolean = false, deleteMissing: Boolean = false, dryRun: Boolean = false
): GhResult {
    val workDir = File(cacheDir, "github_zip_${System.currentTimeMillis()}").apply { mkdirs() }
    val zipFile = File(workDir, "upload.zip")
    try {
        progress(GhProgress(0, "Membaca file ZIP…"))
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(zipFile).use { output -> input.copyTo(output) }
        } ?: error("ZIP tidak dapat dibaca")
        progress(GhProgress(0, "Mengekstrak ZIP…"))
        val extracted = File(workDir, "src").apply { mkdirs() }
        unzipSafeForGithub(zipFile, extracted)

        val rootPath = uploadRoot.trim('/').trim()
        val files = extracted.walkTopDown()
            .filter { it.isFile }
            .map { f -> f.relativeTo(extracted).path.replace(File.separatorChar, '/') to f }
            .filter { (rel, _) ->
                val inRoot = rootPath.isBlank() || rel == rootPath || rel.startsWith("$rootPath/")
                val relativeForExclude = if (rootPath.isBlank()) rel else rel.removePrefix("$rootPath/")
                val excluded = excludedPaths.any { ex ->
                    val normalized = ex.trim('/').replace('\\', '/')
                    rel == normalized || rel.startsWith("$normalized/") ||
                        relativeForExclude == normalized || relativeForExclude.startsWith("$normalized/")
                }
                inRoot && !excluded &&
                    !rel.startsWith(".git/") && !rel.startsWith("__MACOSX/") &&
                    rel != ".git" && rel != "__MACOSX" &&
                    rel != ".DS_Store" && !rel.endsWith("/.DS_Store") &&
                    rel != "Thumbs.db" && !rel.endsWith("/Thumbs.db")
            }
            .map { (rel, f) -> (if (rootPath.isBlank()) rel else rel.removePrefix("$rootPath/")) to f }
            .toList()
        require(files.isNotEmpty()) { "Tidak ada file yang tersisa untuk di-upload dari root yang dipilih" }
        require(files.size <= 3000) { "ZIP terlalu banyak file (maksimal 3000)" }
        return pushFilesToGitHub(files, owner, repo, token, branch, commitMessage, createPrivate, progress,
            onlyChanged, deleteMissing, dryRun)
    } finally {
        workDir.deleteRecursively()
    }
}
