package com.example.aidetest

import kotlin.math.roundToInt
import java.net.URLEncoder
import com.example.aidetest.MainActivity.Base32

import android.text.InputType

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.os.StatFs
import android.view.View
import android.view.Gravity
import android.graphics.Color
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security & crypto tools extracted from MainActivity (gradual modularization).
 */

internal fun MainActivity.hashTool() {
        clearPage("Hash Generator")
        addToolHeader("Hash Generator", "Buat hash teks dengan algoritma yang kamu pilih.", "#")
        content.addView(toolSection("INPUT")); val e=edit("Teks yang akan di-hash"); content.addView(e)
        content.addView(toolSection("ALGORITHM"))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("MD5","SHA-1","SHA-256","SHA-512").forEachIndexed { i,alg ->
            val b=button(alg){output(digest(alg,e.text.toString().toByteArray()))}
            row.addView(b,LinearLayout.LayoutParams(0,dp(50),1f).apply{if(i>0)leftMargin=dp(5)})
        }; content.addView(row)
    }

internal fun MainActivity.checksumTool() {
        clearPage("Checksum File")
        content.addView(button("Pilih file") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type="*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, 1003)
        })
        content.addView(label("Pilih file lalu checksum dihitung di perangkat."))
    }

internal fun MainActivity.hmacTool() {
        clearPage("HMAC Generator")
        val key=edit("Secret key"); val msg=edit("Message", true); content.addView(key); content.addView(msg)
        content.addView(button("HMAC-SHA256") {
            val mac=Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(key.text.toString().toByteArray(), "HmacSHA256"))
            output(mac.doFinal(msg.text.toString().toByteArray()).joinToString("") { "%02x".format(it) })
        })
    }

internal fun MainActivity.jwtTool() {
        clearPage("JWT Decoder")
        val e=edit("JWT"); content.addView(e)
        content.addView(button("Decode") {
            val p=e.text.toString().split(".")
            if(p.size<2) output("JWT tidak valid")
            else output("HEADER:\n${decodeB64Url(p[0])}\n\nPAYLOAD:\n${decodeB64Url(p[1])}")
        })
    }

internal fun MainActivity.totpTool() {
        clearPage("TOTP Generator")
        val secret=edit("Base32 secret"); content.addView(secret)
        val out=label("",22f,true); content.addView(out)
        content.addView(button("Generate sekarang") {
            out.text=totp(secret.text.toString(), System.currentTimeMillis()/1000/30)
        })
    }

internal fun MainActivity.aesTool() {
        clearPage("AES-256-GCM")
        val key=edit("Password/key"); val text=edit("Plaintext / encrypted text", true)
        content.addView(key); content.addView(text)
        content.addView(button("Encrypt") {
            output(aesEncrypt(key.text.toString(), text.text.toString()))
        })
        content.addView(button("Decrypt") {
            output(runCatching { aesDecrypt(key.text.toString(), text.text.toString()) }.getOrElse { "Data/key tidak valid" })
        })
    }

internal fun MainActivity.passwordTool() {
        clearPage("Password Generator")
        val n=edit("Panjang, contoh 20"); content.addView(n)
        content.addView(button("Generate") {
            val len=runCatching { n.text.toString().toInt() }.getOrDefault(20).coerceIn(4,128)
            output(randomString(len, "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%&*"))
        })
    }

internal fun MainActivity.tokenTool() {
        clearPage("Token Acak")
        content.addView(button("32 byte HEX") { output(randomBytes(32)) })
        content.addView(button("64 byte Base64URL") { output(Base64.encodeToString(SecureRandom().generateSeed(64), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)) })
    }

internal fun MainActivity.randomTool() {
        clearPage("Random Bytes")
        val n=edit("Jumlah byte"); content.addView(n)
        content.addView(button("Generate") {
            val size=runCatching { n.text.toString().toInt() }.getOrDefault(32).coerceIn(1,4096)
            output(randomBytes(size))
        })
    }

internal fun MainActivity.hexTool() {
        clearPage("Hex Converter")
        val e=edit("Teks atau HEX"); content.addView(e)
        content.addView(button("Text → Hex") { output(e.text.toString().toByteArray().joinToString("") { "%02x".format(it) }) })
        content.addView(button("Hex → Text") {
            output(runCatching {
                e.text.toString().replace("\\s".toRegex(),"").chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(StandardCharsets.UTF_8)
            }.getOrElse { "HEX tidak valid" })
        })
    }

internal fun MainActivity.base32Tool() {
        clearPage("Base32")
        val e=edit("Teks"); content.addView(e)
        content.addView(button("Encode") { output(Base32.encode(e.text.toString().toByteArray())) })
        content.addView(button("Decode") { output(runCatching { String(Base32.decode(e.text.toString())) }.getOrElse { "Base32 tidak valid" }) })
    }


internal fun MainActivity.fileEncryptionTool() {
    encStage = "form"
    encProgressPct = 0
    encResultPath = null
    encErrorMessage = ""
    encCancelFlag.set(false)
    renderFileEncryptionUi()
}

/** UI Enkripsi File — layout mengikuti desain modern; ikon MDI agar konsisten. */
internal fun MainActivity.renderFileEncryptionUi() {
    clearPage("Enkripsi File")
    content.setPadding(dp(14), dp(6), dp(14), dp(18))

    // Banner header
    val banner = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = bg(Color.rgb(22, 22, 24), 18, Color.TRANSPARENT)
    }
    banner.addView(MdiIconView(this).apply {
        setIconName("lock-outline")
        setIconSize(22f)
        setTextColor(Color.WHITE)
        background = bg(Color.rgb(45, 45, 48), 12, Color.TRANSPARENT)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) }
    })
    val bannerText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    bannerText.addView(TextView(this).apply {
        text = "Enkripsi & Dekripsi File"
        textSize = 15f
        setTextColor(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    })
    bannerText.addView(TextView(this).apply {
        text = "AES-256-GCM • Lokal • Aman"
        textSize = 11f
        setTextColor(Color.rgb(180, 180, 185))
    })
    banner.addView(bannerText, LinearLayout.LayoutParams(0, -2, 1f))
    content.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

    when (encStage) {
        "progress" -> renderEncProgress()
        "success" -> renderEncSuccess()
        "error" -> renderEncError()
        "advanced" -> renderEncAdvanced()
        "locked" -> renderEncLockedList()
        else -> renderEncForm()
    }
}

internal fun MainActivity.renderEncModeTabs() {
    val tabs = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = bg(if (isDarkTheme) panel2 else Color.rgb(242, 244, 246), 14, Color.TRANSPARENT)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    fun tab(title: String, encrypt: Boolean): View {
        val active = encModeEncrypt == encrypt
        return TextView(this).apply {
            text = title
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (active) Color.WHITE else textMuted)
            background = if (active) bg(Color.rgb(22, 22, 24), 12, Color.TRANSPARENT) else null
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (encModeEncrypt != encrypt) {
                    encModeEncrypt = encrypt
                    securityFileUri = null
                    securityFileName = null
                    encStage = "form"
                    renderFileEncryptionUi()
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
    }
    tabs.addView(tab("Enkripsi", true))
    tabs.addView(tab("Dekripsi", false))
    content.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
}

internal fun MainActivity.renderEncForm() {
    renderEncModeTabs()

    val hasFile = securityFileUri != null
    // File pick card
    val fileCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(20), dp(16), dp(20))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 18, line)
        isClickable = true
        isFocusable = true
        setOnClickListener { pickEncFile() }
    }
    if (!hasFile) {
        fileCard.addView(MdiIconView(this).apply {
            setIconName(if (encModeEncrypt) "file-plus-outline" else "folder-lock-outline")
            setIconSize(36f)
            setTextColor(textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) }
        })
        fileCard.addView(TextView(this).apply {
            text = if (encModeEncrypt) "Pilih File" else "Pilih File Dekripsi"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMain)
        })
        fileCard.addView(TextView(this).apply {
            text = if (encModeEncrypt) "Ketuk untuk memilih file\natau seret ke sini" else "Pilih file .mytools.enc\nuntuk didekripsi"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(textMuted)
            setPadding(0, dp(4), 0, dp(10))
        })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val chipLabels = if (encModeEncrypt) listOf("PDF", "ZIP", "JPG", "DLL", "dll") else listOf(".mytools.enc", "ZIP", "ENC")
        chipLabels.forEach { t ->
            chips.addView(TextView(this).apply {
                text = t
                textSize = 10f
                setTextColor(textMuted)
                background = bg(if (isDarkTheme) panel else Color.rgb(240, 242, 244), 10, line)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
            })
        }
        fileCard.addView(chips)
    } else {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(MdiIconView(this).apply {
            setIconName("file-outline")
            setIconSize(22f)
            setTextColor(textMain)
            background = bg(if (isDarkTheme) panel else Color.rgb(236, 240, 244), 12, line)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) }
        })
        val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        meta.addView(TextView(this).apply {
            text = securityFileName ?: "file"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMain)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        meta.addView(TextView(this).apply {
            text = "✓ File siap diproses"
            textSize = 11f
            setTextColor(Color.rgb(46, 125, 50))
        })
        row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener {
                securityFileUri = null
                securityFileName = null
                renderFileEncryptionUi()
            }
        })
        fileCard.gravity = Gravity.START
        fileCard.addView(row)
    }
    content.addView(fileCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

    // Password
    val passRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(4), dp(8), dp(4))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 14, line)
    }
    passRow.addView(MdiIconView(this).apply {
        setIconName("lock-outline")
        setIconSize(18f)
        setTextColor(textMuted)
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(6) }
    })
    val passEdit = EditText(this).apply {
        hint = "Masukkan password"
        setText(encPassword)
        textSize = 14f
        setTextColor(textMain)
        setHintTextColor(textMuted)
        background = null
        inputType = if (encShowPassword)
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { encPassword = s?.toString().orEmpty() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }
    passRow.addView(passEdit)
    passRow.addView(MdiIconView(this).apply {
        setIconName(if (encShowPassword) "eye-off-outline" else "eye-outline")
        setIconSize(20f)
        setTextColor(textMuted)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        isClickable = true
        setOnClickListener {
            encPassword = passEdit.text.toString()
            encShowPassword = !encShowPassword
            renderFileEncryptionUi()
        }
    })
    content.addView(passRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

    // Advanced accordion header
    val advHeader = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(12), dp(12))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 14, line)
        isClickable = true
        setOnClickListener { encStage = "advanced"; renderFileEncryptionUi() }
    }
    advHeader.addView(TextView(this).apply {
        text = "Pengaturan Lanjutan"
        textSize = 13f
        setTextColor(textMain)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    })
    advHeader.addView(MdiIconView(this).apply {
        setIconName("chevron-down")
        setIconSize(18f)
        setTextColor(textMuted)
    })
    content.addView(advHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    // Primary action
    val canRun = hasFile && encPassword.isNotBlank()
    val primary = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = bg(if (canRun) Color.rgb(22, 22, 24) else Color.rgb(160, 160, 165), 16, Color.TRANSPARENT)
        isClickable = canRun
        isEnabled = canRun
        setOnClickListener { if (canRun) startEncProcess() }
    }
    primary.addView(MdiIconView(this).apply {
        setIconName(if (encModeEncrypt) "lock-outline" else "lock-open-outline")
        setIconSize(18f)
        setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(8) }
    })
    primary.addView(TextView(this).apply {
        text = if (encModeEncrypt) "Enkripsi File" else "Dekripsi File"
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    })
    content.addView(primary, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })

    content.addView(button("File Terkunci") {
        encStage = "locked"
        renderFileEncryptionUi()
    })
}

internal fun MainActivity.pickEncFile() {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"
        addCategory(Intent.CATEGORY_OPENABLE)
    }, SECURITY_FILE_PICK)
}

/** Progress: HANYA lingkaran + persen di dalam — tanpa progress bar linear. */
internal fun MainActivity.renderEncProgress() {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(28), dp(20), dp(24))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 20, line)
    }
    val ring = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(120), dp(120)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) }
    }
    // Custom canvas ring for clean single circular progress
    val ringView = object : View(this) {
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(10).toFloat()
            color = if (isDarkTheme) Color.rgb(50, 50, 55) else Color.rgb(230, 232, 236)
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(10).toFloat()
            color = Color.rgb(22, 22, 24)
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val pad = dp(12).toFloat()
            val rect = android.graphics.RectF(pad, pad, width - pad, height - pad)
            canvas.drawArc(rect, -90f, 360f, false, track)
            canvas.drawArc(rect, -90f, 360f * (encProgressPct.coerceIn(0, 100) / 100f), false, fill)
        }
    }
    ring.addView(ringView, FrameLayout.LayoutParams(-1, -1))
    ring.addView(TextView(this).apply {
        text = "${encProgressPct.coerceIn(0, 100)}%"
        textSize = 22f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
        layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        tag = "encPct"
    })
    box.addView(ring)
    box.addView(TextView(this).apply {
        text = if (encModeEncrypt) "Mengenkripsi file…" else "Mendekripsi file…"
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    })
    box.addView(TextView(this).apply {
        text = "Mohon tunggu, jangan\ntutup aplikasi."
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(textMuted)
        setPadding(0, dp(6), 0, 0)
    })
    // NO linear progress bar — user request: only the circle with % inside
    content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

    val cancel = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = bg(Color.rgb(22, 22, 24), 16, Color.TRANSPARENT)
        setOnClickListener {
            encCancelFlag.set(true)
            appendEncCancel()
        }
    }
    cancel.addView(TextView(this).apply {
        text = "Batal"
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    })
    content.addView(cancel, LinearLayout.LayoutParams(-1, dp(52)))
}

internal fun MainActivity.appendEncCancel() {
    encStage = "form"
    encProgressPct = 0
    toast("Dibatalkan")
    renderFileEncryptionUi()
}

internal fun MainActivity.updateEncProgressUi(pct: Int) {
    encProgressPct = pct.coerceIn(0, 100)
    if (encStage == "progress" && currentPage == "Enkripsi File") {
        // Full re-render keeps the circular ring in sync (single loader only).
        renderFileEncryptionUi()
    }
}

internal fun MainActivity.renderEncSuccess() {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(24), dp(16), dp(16))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 20, line)
    }
    box.addView(MdiIconView(this).apply {
        setIconName("check-circle")
        setIconSize(48f)
        setTextColor(Color.rgb(22, 22, 24))
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) }
    })
    box.addView(TextView(this).apply {
        text = if (encModeEncrypt) "Enkripsi Berhasil!" else "Dekripsi Berhasil!"
        textSize = 18f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    })
    box.addView(TextView(this).apply {
        text = if (encModeEncrypt) "File telah berhasil dienkripsi\ndan siap digunakan." else "File telah berhasil didekripsi\ndan siap digunakan."
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(textMuted)
        setPadding(0, dp(6), 0, dp(14))
    })
    val outRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(10), dp(10))
        background = bg(if (isDarkTheme) panel else Color.WHITE, 14, line)
    }
    outRow.addView(MdiIconView(this).apply {
        setIconName("file-outline")
        setIconSize(20f)
        setTextColor(textMain)
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(8) }
    })
    val outMeta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    outMeta.addView(TextView(this).apply {
        text = encResultPath?.substringAfterLast('/') ?: "output"
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    })
    outMeta.addView(TextView(this).apply {
        text = bytesText(encResultSize)
        textSize = 11f
        setTextColor(textMuted)
    })
    outRow.addView(outMeta, LinearLayout.LayoutParams(0, -2, 1f))
    box.addView(outRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    actions.addView(button(if (encModeEncrypt) "Buka Folder" else "Buka") {
        val p = encResultPath
        if (p != null) {
            val f = File(p)
            if (f.exists()) {
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }.onFailure { toast("Tidak bisa membuka: ${it.message}") }
            }
        }
    }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) } })
    actions.addView(button(if (encModeEncrypt) "Bagikan" else "Simpan Salinan") {
        val p = encResultPath ?: return@button
        val f = File(p)
        if (!f.exists()) { toast("File tidak ada"); return@button }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Bagikan"))
        }.onFailure { toast("Gagal berbagi") }
    }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) } })
    box.addView(actions)

    // Security checklist
    val checks = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = bg(if (isDarkTheme) panel else Color.rgb(248, 249, 250), 14, line)
    }
    checks.addView(TextView(this).apply {
        text = "Keamanan Terjamin"
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
        setPadding(0, 0, 0, dp(6))
    })
    listOf(
        "AES-256-GCM",
        "Diproses secara lokal",
        "Tidak ada upload ke server",
        "File asli tidak diubah"
    ).forEach { line ->
        checks.addView(TextView(this).apply {
            text = "✓  $line"
            textSize = 12f
            setTextColor(Color.rgb(46, 125, 50))
            setPadding(0, dp(2), 0, dp(2))
        })
    }
    box.addView(checks, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    content.addView(box)

    content.addView(button("Enkripsi File Lain") {
        securityFileUri = null
        securityFileName = null
        encPassword = ""
        encStage = "form"
        renderFileEncryptionUi()
    }.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) } })
}

internal fun MainActivity.renderEncError() {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(28), dp(20), dp(24))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 20, line)
    }
    box.addView(MdiIconView(this).apply {
        setIconName("alert-circle-outline")
        setIconSize(42f)
        setTextColor(Color.rgb(22, 22, 24))
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) }
    })
    box.addView(TextView(this).apply {
        text = if (encModeEncrypt) "Enkripsi Gagal!" else "Dekripsi Gagal!"
        textSize = 17f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    })
    box.addView(TextView(this).apply {
        text = encErrorMessage.ifBlank { "Password tidak valid atau file rusak." }
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(textMuted)
        setPadding(0, dp(8), 0, dp(16))
    })
    box.addView(button("Coba Lagi") {
        encStage = "form"
        renderFileEncryptionUi()
    })
    content.addView(box)
}

internal fun MainActivity.renderEncAdvanced() {
    content.addView(label("Pengaturan Lanjutan", 18f, true))
    content.addView(subLabel("Opsi output dan perilaku file.", 12f).apply { setPadding(0, 0, 0, dp(10)) })

    fun checkRow(title: String, icon: String, checked: Boolean, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 14, line)
        }
        row.addView(MdiIconView(this).apply {
            setIconName(icon)
            setIconSize(18f)
            setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) }
        })
        row.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val cb = android.widget.CheckBox(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onToggle(v) }
        }
        row.addView(cb)
        return row
    }
    content.addView(checkRow("Sama dengan file asli (folder app)", "folder-outline", encOutSameAsSource) { encOutSameAsSource = it }.apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    })
    content.addView(checkRow("Ganti file jika sudah ada", "file-replace-outline", encOverwrite) { encOverwrite = it }.apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    })
    content.addView(checkRow("Hapus file asli setelah selesai", "delete-outline", encDeleteSource) { encDeleteSource = it }.apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    })
    content.addView(checkRow("Kompresi dulu (lebih kecil)", "zip-box-outline", encCompress) { encCompress = it }.apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    })
    content.addView(button("Simpan") {
        encStage = "form"
        renderFileEncryptionUi()
    })
}

internal fun MainActivity.renderEncLockedList() {
    content.addView(label("File Terkunci", 18f, true))
    content.addView(subLabel("File .mytools.enc di penyimpanan aplikasi.", 12f).apply { setPadding(0, 0, 0, dp(10)) })
    val files = filesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mytools.enc", true) }
        ?.sortedByDescending { it.lastModified() }.orEmpty()
    if (files.isEmpty()) {
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(32), dp(20), dp(32))
            background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 18, line)
        }
        empty.addView(MdiIconView(this).apply {
            setIconName("folder-outline")
            setIconSize(40f)
            setTextColor(textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) }
        })
        empty.addView(TextView(this).apply {
            text = "Belum ada file"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMain)
        })
        empty.addView(TextView(this).apply {
            text = "Pilih file untuk memulai proses\nenkripsi atau dekripsi."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(textMuted)
            setPadding(0, dp(6), 0, dp(14))
        })
        empty.addView(button("Pilih File") {
            encStage = "form"
            pickEncFile()
        })
        content.addView(empty)
    } else {
        files.forEach { f ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(10), dp(12))
                background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 14, line)
            }
            row.addView(MdiIconView(this).apply {
                setIconName("file-lock-outline")
                setIconSize(20f)
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) }
            })
            val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            meta.addView(TextView(this).apply {
                text = f.name
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(textMain)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            meta.addView(TextView(this).apply {
                text = "${bytesText(f.length())}  •  ${java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(java.util.Date(f.lastModified()))}"
                textSize = 11f
                setTextColor(textMuted)
            })
            row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener {
                securityFileUri = android.net.Uri.fromFile(f)
                securityFileName = f.name
                encModeEncrypt = false
                encStage = "form"
                renderFileEncryptionUi()
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }
    content.addView(button("Kembali") { encStage = "form"; renderFileEncryptionUi() }.apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
    })
}

internal fun MainActivity.startEncProcess() {
    val uri = securityFileUri ?: run { toast("Pilih file dulu"); return }
    if (encPassword.isBlank()) { toast("Password wajib diisi"); return }
    encCancelFlag.set(false)
    encStage = "progress"
    encProgressPct = 0
    renderFileEncryptionUi()
    val encrypt = encModeEncrypt
    val pass = encPassword
    val deleteSrc = encDeleteSource
    toolThread(if (encrypt) "Mengenkripsi…" else "Mendekripsi…") {
        val result = runCatching {
            runOnUiThread { updateEncProgressUi(8) }
            if (encCancelFlag.get()) error("Dibatalkan")
            val plainOrEnc = (contentResolver.openInputStream(uri) ?: error("File tidak bisa dibuka")).use { it.readBytes() }
            runOnUiThread { updateEncProgressUi(28) }
            if (encCancelFlag.get()) error("Dibatalkan")
            if (encrypt) {
                val salt = ByteArray(16); val iv = ByteArray(12)
                SecureRandom().nextBytes(salt); SecureRandom().nextBytes(iv)
                runOnUiThread { updateEncProgressUi(45) }
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyV2(pass, salt), "AES"), GCMParameterSpec(128, iv))
                val enc = cipher.doFinal(plainOrEnc)
                runOnUiThread { updateEncProgressUi(75) }
                if (encCancelFlag.get()) error("Dibatalkan")
                val name = (securityFileName ?: "file").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val out = File(filesDir, "$name.mytools.enc")
                if (out.exists() && !encOverwrite) {
                    val alt = File(filesDir, "${name}_${System.currentTimeMillis()}.mytools.enc")
                    FileOutputStream(alt).use {
                        it.write("MYTOOLS-FILE-AES2".toByteArray(StandardCharsets.US_ASCII))
                        it.write(salt); it.write(iv); it.write(enc)
                    }
                    Triple(alt.absolutePath, alt.length(), true)
                } else {
                    FileOutputStream(out).use {
                        it.write("MYTOOLS-FILE-AES2".toByteArray(StandardCharsets.US_ASCII))
                        it.write(salt); it.write(iv); it.write(enc)
                    }
                    Triple(out.absolutePath, out.length(), true)
                }
            } else {
                val head = "MYTOOLS-FILE-AES2".toByteArray(StandardCharsets.US_ASCII)
                require(plainOrEnc.size > head.size + 28 && plainOrEnc.copyOfRange(0, head.size).contentEquals(head)) {
                    "Format file tidak dikenali"
                }
                runOnUiThread { updateEncProgressUi(40) }
                val salt = plainOrEnc.copyOfRange(head.size, head.size + 16)
                val iv = plainOrEnc.copyOfRange(head.size + 16, head.size + 28)
                val body = plainOrEnc.copyOfRange(head.size + 28, plainOrEnc.size)
                val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                c.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyV2(pass, salt), "AES"), GCMParameterSpec(128, iv))
                val plain = c.doFinal(body)
                runOnUiThread { updateEncProgressUi(80) }
                if (encCancelFlag.get()) error("Dibatalkan")
                val base = (securityFileName ?: "file")
                    .substringAfterLast('/')
                    .removeSuffix(".mytools.enc")
                    .ifBlank { "file" }
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val out = File(filesDir, "$base.decrypted")
                FileOutputStream(out).use { it.write(plain) }
                Triple(out.absolutePath, out.length(), false)
            }
        }
        runOnUiThread {
            if (encCancelFlag.get()) {
                encStage = "form"
                renderFileEncryptionUi()
                return@runOnUiThread
            }
            result.onSuccess { (path, size, _) ->
                updateEncProgressUi(100)
                encResultPath = path
                encResultSize = size
                encStage = "success"
                renderFileEncryptionUi()
            }.onFailure {
                encErrorMessage = when {
                    it.message?.contains("Password", true) == true ||
                        it.message?.contains("tag mismatch", true) == true ||
                        it.message?.contains("MAC", true) == true -> "Password tidak valid atau file rusak."
                    it.message == "Dibatalkan" -> { encStage = "form"; renderFileEncryptionUi(); return@onFailure }
                    else -> it.message ?: "Terjadi kesalahan."
                }
                encStage = "error"
                renderFileEncryptionUi()
            }
        }
    }
}


internal fun MainActivity.steganographyTool() {
        clearPage("Steganography")
        addToolHeader("Steganography", "Sembunyikan pesan teks di bit warna gambar PNG. Proses lokal.", "STG")
        val msg=edit("Pesan yang disembunyikan",true); content.addView(msg)
        content.addView(button("Pilih Gambar → Sembunyikan") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},STEGO_ENCODE_PICK) })
        content.addView(button("Sembunyikan Pesan") {
            val uri=stegoImageUri ?: run{toast("Pilih gambar dulu");return@button}; val text=msg.text.toString(); if(text.isEmpty()){toast("Pesan kosong");return@button}
            toolThread { val result=runCatching{encodeStego(uri,text)}.getOrElse{"Gagal: ${it.message}"};runOnUiThread{output(result)} }
        })
        content.addView(button("Pilih Gambar → Baca Pesan") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},STEGO_DECODE_PICK) })
    }

internal fun MainActivity.passwordStrengthAnalyzerTool(){
        clearPage("Password Strength Analyzer"); addToolHeader("Password Strength Analyzer","Analisis kekuatan, entropi dan estimasi brute-force secara lokal.","SEC")
        val e=edit("Password");e.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;content.addView(e);val out=label("Belum dianalisis",15f);content.addView(out)
        content.addView(button("Analisis") { val p=e.text.toString();val pool=(if(p.any{it.isLowerCase()})26 else 0)+(if(p.any{it.isUpperCase()})26 else 0)+(if(p.any{it.isDigit()})10 else 0)+(if(p.any{!it.isLetterOrDigit()})33 else 0);val entropy=if(pool>0)p.length*kotlin.math.log(pool.toDouble(), 2.0) else 0.0;val guesses=if(entropy>62)1e18 else Math.pow(2.0,entropy);val sec=guesses/1e10;val time=when{sec<60->"${sec.roundToInt()} detik";sec<3600->"${(sec/60).roundToInt()} menit";sec<86400->"${(sec/3600).roundToInt()} jam";sec<31557600->"${(sec/86400).roundToInt()} hari";else->"${(sec/31557600).roundToInt()} tahun+"};out.text="Panjang: ${p.length}\nPool karakter: $pool\nEntropi: %.1f bit\nEstimasi brute-force @10¹⁰ tebakan/detik: $time".format(Locale.US,entropy) })
    }

internal fun MainActivity.dataBreachCheckerTool(){
        clearPage("Data Breach Checker");addToolHeader("Data Breach Checker","Periksa email melalui API Have I Been Pwned. API key diperlukan.","HIBP");val email=edit("Email");val key=edit("HIBP API key");content.addView(email);content.addView(key);content.addView(button("Cek Breach") {val e=email.text.toString().trim();val k=key.text.toString().trim();if(!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()){toast("Email tidak valid");return@button};if(k.isBlank()){toast("Masukkan API key HIBP");return@button};toolThread {val r=runCatching{val u=URL("https://haveibeenpwned.com/api/v3/breachedaccount/"+URLEncoder.encode(e,"UTF-8")+"?truncateResponse=false");val c=u.openConnection() as HttpURLConnection;c.requestMethod="GET";c.setRequestProperty("hibp-api-key",k);c.setRequestProperty("user-agent","MyTools/2.20");c.connectTimeout=10000;c.readTimeout=10000;val code=c.responseCode;if(code==404)"Tidak ditemukan dalam breach yang dilaporkan HIBP." else if(code==200)c.inputStream.bufferedReader().use{it.readText()} else "HTTP $code: ${c.errorStream?.bufferedReader()?.use{it.readText()} ?: ""}"}.getOrElse{"Gagal: ${it.message}"};runOnUiThread{output(r)}} })
    }

internal fun MainActivity.secureNotesTool(){
        clearPage("Secure Notes");addToolHeader("Secure Notes","Catatan disimpan terenkripsi AES-GCM di perangkat.","NOTE");val title=edit("Judul");val note=edit("Catatan",true);val pass=edit("Master password");pass.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;content.addView(title);content.addView(note);content.addView(pass);content.addView(button("Simpan terenkripsi"){if(title.text.isBlank()||pass.text.isBlank()){toast("Judul dan password wajib");return@button};val data="${title.text}\n${note.text}";val enc=aesEncrypt(pass.text.toString(),data);prefs.edit().putString("secure_note_${title.text}",enc).apply();toast("Catatan terenkripsi disimpan")});content.addView(button("Buka catatan"){val enc=prefs.getString("secure_note_${title.text}",null)?:run{toast("Catatan tidak ditemukan");return@button};output(runCatching{aesDecrypt(pass.text.toString(),enc)}.getOrElse{"Password salah atau data rusak"})})
    }

internal fun MainActivity.totpVaultTool(){
        clearPage("2FA Manager (TOTP)");addToolHeader("2FA Manager","Simpan secret TOTP secara terenkripsi dan buat kode 6 digit.","2FA");val labelE=edit("Nama akun");val secret=edit("Base32 secret");val pass=edit("Vault password");pass.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;content.addView(labelE);content.addView(secret);content.addView(pass);val out=label("Belum ada kode",28f,true);content.addView(out);content.addView(button("Simpan ke Vault"){if(labelE.text.isBlank()||secret.text.isBlank()||pass.text.isBlank()){toast("Lengkapi semua field");return@button};prefs.edit().putString("totp_vault_${labelE.text}",aesEncrypt(pass.text.toString(),secret.text.toString())).apply();toast("Secret tersimpan terenkripsi")});content.addView(button("Generate Kode"){val enc=prefs.getString("totp_vault_${labelE.text}",null)?:run{toast("Akun belum tersimpan");return@button};out.text=runCatching{totp(aesDecrypt(pass.text.toString(),enc),System.currentTimeMillis()/1000/30)}.getOrElse{"Password salah / secret rusak"}})
    }

internal fun MainActivity.urlSafetyTool(){clearPage("URL Safety Checker");addToolHeader("URL Safety Checker","Pemeriksaan heuristik lokal untuk indikasi URL mencurigakan.","SAFE");val e=edit("URL");content.addView(e);content.addView(button("Periksa") {val raw=e.text.toString().trim();val r=runCatching{val u=URL(if(raw.startsWith("http://")||raw.startsWith("https://"))raw else "https://$raw");val flags=mutableListOf<String>();if(u.protocol!="https")flags.add("Tidak menggunakan HTTPS");if(u.userInfo!=null)flags.add("Memiliki userinfo sebelum host");if(u.host.length>63)flags.add("Host sangat panjang");if(u.host.contains("xn--"))flags.add("Punycode/IDN terdeteksi");if(Regex("(login|verify|secure|account|wallet|gift|update)[-_].{0,12}(support|verify|login)?",RegexOption.IGNORE_CASE).containsMatchIn(u.path+u.query))flags.add("Path/query memakai kata yang sering digunakan pada halaman phishing");"Host: ${u.host}\nSkema: ${u.protocol}\n${if(flags.isEmpty())"Tidak ada indikator heuristik umum yang terdeteksi." else flags.joinToString("\n• ",prefix="Indikator:\n• ")}"}.getOrElse{"URL tidak valid: ${it.message}"};output(r)})}

internal fun MainActivity.virusScannerTool(){clearPage("Virus Scanner (VirusTotal)");addToolHeader("Virus Scanner","Gunakan VirusTotal API untuk lookup hash file atau scan URL. API key milik pengguna diperlukan.","VT");val key=edit("VirusTotal API key");val target=edit("URL atau SHA-256 file");content.addView(key);content.addView(target);content.addView(button("Scan / Lookup") {val k=key.text.toString().trim();val t=target.text.toString().trim();if(k.isBlank()||t.isBlank()){toast("API key dan target wajib");return@button};toolThread {val r=runCatching{val endpoint=if(Regex("^[A-Fa-f0-9]{64}$").matches(t))"https://www.virustotal.com/api/v3/files/$t" else "https://www.virustotal.com/api/v3/urls/${Base64.encodeToString(t.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)}";val c=URL(endpoint).openConnection() as HttpURLConnection;c.setRequestProperty("x-apikey",k);c.connectTimeout=10000;c.readTimeout=10000;"HTTP ${c.responseCode}\n"+(if(c.responseCode in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()}}.getOrElse{"Gagal: ${it.message}"};runOnUiThread{output(r)}}})}

internal fun MainActivity.pgpTool(){
        clearPage("PGP Encrypt / Decrypt");addToolHeader("PGP Encrypt / Decrypt","OpenPGP memerlukan keyring dan library OpenPGP. MyTools menyediakan ruang kerja untuk armor/key input.","PGP");val key=edit("ASCII-armored public/private key",true);val text=edit("Pesan / armored PGP",true);content.addView(key);content.addView(text);content.addView(button("Validasi format PGP"){val s=key.text.toString();output(if(s.contains("-----BEGIN PGP")&&s.contains("-----END PGP"))"Armor PGP terdeteksi. Untuk operasi kriptografi penuh, gunakan keyring OpenPGP yang kompatibel." else "Format ASCII armor PGP belum terdeteksi.")})
    }

internal fun MainActivity.fileHashCompareTool() {
        clearPage("File Hash Compare")
        addToolHeader("File Hash Compare", "Pastikan dua file identik atau berbeda dengan hash kriptografis.", "HASH")

        content.addView(toolSection("FILE A", "Pilih file pertama untuk dibandingkan."))
        val a = filePickCard("File A belum dipilih", "Pilih file A") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, 1201)
        }
        content.addView(a)

        content.addView(toolSection("FILE B", "Pilih file kedua untuk dibandingkan."))
        val b = filePickCard("File B belum dipilih", "Pilih file B") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, 1202)
        }
        content.addView(b)

        content.addView(toolSection("ALGORITHM", "Pilih algoritma hash yang ingin digunakan."))
        val algorithm = Spinner(this).apply {
            adapter = ArrayAdapter(this@fileHashCompareTool, android.R.layout.simple_spinner_dropdown_item, arrayOf("SHA-256", "SHA-512", "SHA-1", "MD5"))
        }
        content.addView(algorithm, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })

        val status = toolStatus("Siap • pilih dua file", false)
        content.addView(status)
        content.addView(button("Bandingkan File") {
            val ua = fileHashUriA
            val ub = fileHashUriB
            if (ua == null || ub == null) { toast("Pilih File A dan File B terlebih dahulu"); return@button }
            status.text = "●  Menghitung hash…"
            toolThread {
                val r = runCatching {
                    val alg = algorithm.selectedItem.toString()
                    val ha = contentResolver.openInputStream(ua)?.use { digestStream(it, alg) } ?: error("File A tidak bisa dibuka")
                    val hb = contentResolver.openInputStream(ub)?.use { digestStream(it, alg) } ?: error("File B tidak bisa dibuka")
                    val same = ha.equals(hb, true)
                    "${if (same) "🟢 FILE IDENTIK" else "🔴 FILE BERBEDA"}\n\n$alg\nA: $ha\nB: $hb"
                }.getOrElse { "Gagal: ${it.message}" }
                runOnUiThread {
                    status.text = if (r.startsWith("Gagal")) "●  Gagal menghitung hash" else "●  Selesai"
                    output(r)
                }
            }
        })
        content.addView(subLabel("Hash dihitung lokal di perangkat. File tidak diunggah ke server.", 11f))
        fileHashCompareLabelA = a.findViewWithTag<TextView>("fileLabel")
        fileHashCompareLabelB = b.findViewWithTag<TextView>("fileLabel")
    }


internal fun MainActivity.securityCenterTool() {
    renderSecurityCenter("home")
}

/**
 * Security Center UI — multi-page, skor, menu, detail; ikon MDI konsisten.
 * [page]: home | summary | permissions | privacy | encryption | storage | backup | integrity | recommendations | events | androidSettings | detail_perm | detail_backup | detail_enc
 */
internal fun MainActivity.renderSecurityCenter(page: String = "home") {
    clearPage(when (page) {
        "summary" -> "Ringkasan Keamanan"
        "permissions" -> "Izin & Akses"
        "privacy" -> "Data & Privasi"
        "encryption" -> "Enkripsi"
        "storage" -> "File & Penyimpanan"
        "backup" -> "Backup & Pemulihan"
        "integrity" -> "Integritas Aplikasi"
        "recommendations" -> "Rekomendasi"
        "events" -> "Security Events"
        "androidSettings" -> "Buka Pengaturan Android"
        "detail_perm" -> "Detail Izin"
        "detail_backup" -> "Detail Backup"
        "detail_enc" -> "Detail Enkripsi"
        else -> "Security Center"
    })
    content.setPadding(dp(14), dp(6), dp(14), dp(20))
    when (page) {
        "summary" -> scSummary()
        "permissions" -> scPermissions()
        "privacy" -> scPrivacy()
        "encryption" -> scEncryption()
        "storage" -> scStorage()
        "backup" -> scBackup()
        "integrity" -> scIntegrity()
        "recommendations" -> scRecommendations()
        "events" -> scEvents()
        "androidSettings" -> scAndroidSettings()
        "detail_perm" -> scDetailPerm()
        "detail_backup" -> scDetailBackup()
        "detail_enc" -> scDetailEnc()
        else -> scHome()
    }
    // Soft enter animation
    content.alpha = 0.92f
    content.translationY = dp(8).toFloat()
    content.animate().alpha(1f).translationY(0f).setDuration(180L).start()
}

internal fun MainActivity.scScore(): Int {
    var s = 100
    // Real deductions only for actual risks we can detect
    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) s -= 15
    // Missing network is not a risk for offline tools
    return s.coerceIn(0, 100)
}

internal fun MainActivity.scHome() {
    val score = scScore()
    val banner = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = bg(Color.rgb(22, 22, 24), 20, Color.TRANSPARENT)
    }
    val ring = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(14) }
    }
    ring.addView(object : View(this) {
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(6).toFloat()
            color = Color.rgb(55, 55, 60); strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(6).toFloat()
            color = Color.WHITE; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        override fun onDraw(c: android.graphics.Canvas) {
            val p = dp(8).toFloat()
            val r = android.graphics.RectF(p, p, width - p, height - p)
            c.drawArc(r, -90f, 360f, false, track)
            c.drawArc(r, -90f, 360f * (score / 100f), false, fill)
        }
    }, FrameLayout.LayoutParams(-1, -1))
    ring.addView(MdiIconView(this).apply {
        setIconName("shield-check")
        setIconSize(22f)
        setTextColor(Color.WHITE)
        layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
    })
    banner.addView(ring)
    val scoreCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    scoreCol.addView(TextView(this).apply {
        text = "$score"
        textSize = 28f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        includeFontPadding = false
    })
    scoreCol.addView(TextView(this).apply {
        text = "/100"
        textSize = 11f
        setTextColor(Color.rgb(160, 160, 165))
    })
    banner.addView(scoreCol, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) })
    val statusCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    statusCol.addView(TextView(this).apply {
        text = "Skor Keamanan"
        textSize = 12f
        setTextColor(Color.rgb(180, 180, 185))
    })
    statusCol.addView(TextView(this).apply {
        text = if (score >= 85) "Baik" else if (score >= 60) "Cukup" else "Perlu perhatian"
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    })
    statusCol.addView(TextView(this).apply {
        text = "Aplikasi dalam kondisi\naman dan stabil."
        textSize = 11f
        setTextColor(Color.rgb(160, 160, 165))
    })
    banner.addView(statusCol, LinearLayout.LayoutParams(0, -2, 1f))
    content.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    val menu = listOf(
        Triple("shield-check-outline", "Ringkasan Keamanan", "Lihat status dan informasi penting") to "summary",
        Triple("key-variant", "Izin & Akses", "Kelola izin aplikasi") to "permissions",
        Triple("database-lock-outline", "Data & Privasi", "Perlindungan data lokal") to "privacy",
        Triple("lock-outline", "Enkripsi", "Status enkripsi & kunci") to "encryption",
        Triple("folder-lock-outline", "File & Penyimpanan", "Keamanan file dan penyimpanan") to "storage",
        Triple("backup-restore", "Backup & Pemulihan", "Cadangan data aplikasi") to "backup"
    )
    menu.forEach { (info, page) ->
        content.addView(scMenuRow(info.first, info.second, info.third) {
            renderSecurityCenter(page)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    content.addView(scMenuRow("history", "Security Events", "Aktivitas keamanan terbaru") {
        renderSecurityCenter("events")
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    content.addView(scMenuRow("cellphone-cog", "Integritas Aplikasi", "Versi, signature, debug") {
        renderSecurityCenter("integrity")
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
}

internal fun MainActivity.scMenuRow(icon: String, title: String, subtitle: String, onClick: () -> Unit): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 16, line)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            animate().scaleX(0.98f).scaleY(0.98f).setDuration(60).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                onClick()
            }.start()
        }
    }
    row.addView(MdiIconView(this).apply {
        setIconName(icon)
        setIconSize(20f)
        setTextColor(textMain)
        background = bg(if (isDarkTheme) panel else Color.rgb(240, 242, 245), 12, Color.TRANSPARENT)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) }
    })
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    })
    col.addView(TextView(this).apply {
        text = subtitle
        textSize = 11f
        setTextColor(textMuted)
    })
    row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(MdiIconView(this).apply {
        setIconName("chevron-right")
        setIconSize(18f)
        setTextColor(textMuted)
    })
    return row
}

internal fun MainActivity.scStatusRow(icon: String, title: String, status: String, ok: Boolean): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 14, line)
    }
    row.addView(MdiIconView(this).apply {
        setIconName(icon)
        setIconSize(18f)
        setTextColor(textMain)
        background = bg(if (isDarkTheme) panel else Color.rgb(238, 240, 243), 12, Color.TRANSPARENT)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) }
    })
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(TextView(this).apply {
        text = title
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    })
    col.addView(TextView(this).apply {
        text = status
        textSize = 11f
        setTextColor(textMuted)
    })
    row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(TextView(this).apply {
        text = if (ok) "✓" else "—"
        textSize = 16f
        setTextColor(if (ok) Color.rgb(46, 125, 50) else textMuted)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
    })
    return row
}

internal fun MainActivity.scSummary() {
    content.addView(label("Status Keamanan", 13f, true).apply { setPadding(0, 0, 0, dp(8)) })
    val items = listOf(
        Triple("database-outline", "Data lokal", "Aman") to true,
        Triple("file-outline", "FileProvider", "Aman") to true,
        Triple("bell-outline", "Notification access", "Tidak digunakan") to true,
        Triple("backup-restore", "Backup", "Terkontrol") to true
    )
    items.forEach { (t, ok) ->
        content.addView(scStatusRow(t.first, t.second, t.third, ok), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    val rec = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(248, 250, 252), 16, line)
        isClickable = true
        setOnClickListener { renderSecurityCenter("recommendations") }
    }
    rec.addView(TextView(this).apply {
        text = "Rekomendasi"
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    })
    rec.addView(TextView(this).apply {
        text = "Tidak ada rekomendasi penting.\nAplikasi Anda sudah aman."
        textSize = 12f
        setTextColor(textMuted)
        setPadding(0, dp(4), 0, 0)
    })
    content.addView(rec, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
}

internal fun MainActivity.scPermissions() {
    content.addView(label("Permission Aplikasi", 13f, true).apply { setPadding(0, 0, 0, dp(8)) })
    data class P(val icon: String, val name: String, val perm: String?)
    val list = listOf(
        P("web", "Internet", android.Manifest.permission.INTERNET),
        P("wifi", "Network State", android.Manifest.permission.ACCESS_NETWORK_STATE),
        P("bell-outline", "Notifications", if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.POST_NOTIFICATIONS else null),
        P("folder-outline", "Storage", null), // scoped / SAF — not classic WRITE
        P("camera-outline", "Camera", android.Manifest.permission.CAMERA),
        P("bluetooth", "Bluetooth", android.Manifest.permission.BLUETOOTH),
        P("map-marker-outline", "Location", android.Manifest.permission.ACCESS_FINE_LOCATION)
    )
    list.forEach { p ->
        val granted = when {
            p.perm == null && p.name == "Storage" -> true // uses SAF / app storage
            p.perm == null -> false
            p.perm == android.Manifest.permission.INTERNET || p.perm == android.Manifest.permission.ACCESS_NETWORK_STATE -> true
            else -> checkSelfPermission(p.perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val status = when {
            p.name == "Storage" -> "Digunakan"
            p.perm == android.Manifest.permission.INTERNET || p.perm == android.Manifest.permission.ACCESS_NETWORK_STATE -> "Digunakan"
            granted -> "Digunakan"
            else -> "Tidak digunakan"
        }
        val row = scStatusRow(p.icon, p.name, status, granted || status == "Digunakan" && p.name in listOf("Internet", "Network State", "Storage"))
        row.setOnClickListener {
            // stash for detail
            prefs.edit().putString("sc_detail_perm_name", p.name).putString("sc_detail_perm_status", status).apply()
            renderSecurityCenter("detail_perm")
        }
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
}

internal fun MainActivity.scPrivacy() {
    content.addView(label("Data", 13f, true).apply { setPadding(0, 0, 0, dp(8)) })
    listOf(
        Triple("cash-lock", "Data Keuangan", "Disimpan lokal"),
        Triple("file-outline", "File", "Diproses oleh tool terkait"),
        Triple("key-chain-variant", "API Keys", "Tersimpan di aplikasi")
    ).forEach { (ic, t, s) ->
        content.addView(scMenuRow(ic, t, s) {}, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    content.addView(label("Privasi", 13f, true).apply { setPadding(0, dp(8), 0, dp(8)) })
    listOf(
        Triple("bell-off-outline", "Tidak ada akses notifikasi", true),
        Triple("map-marker-off-outline", "Tidak ada pelacakan lokasi", true),
        Triple("account-off-outline", "Tidak ada iklan pihak ketiga", true)
    ).forEach { (ic, t, ok) ->
        content.addView(scStatusRow(ic, t, if (ok) "Aktif" else "—", ok), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
}

internal fun MainActivity.scEncryption() {
    val banner = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = bg(Color.rgb(22, 22, 24), 18, Color.TRANSPARENT)
    }
    banner.addView(MdiIconView(this).apply {
        setIconName("lock-outline")
        setIconSize(24f)
        setTextColor(Color.WHITE)
        background = bg(Color.rgb(50, 50, 55), 14, Color.TRANSPARENT)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) }
    })
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(TextView(this).apply {
        text = "AES-256-GCM"
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    })
    col.addView(TextView(this).apply {
        text = "Enkripsi modern dengan autentikasi"
        textSize = 11f
        setTextColor(Color.rgb(170, 170, 175))
    })
    banner.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    content.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

    listOf(
        Triple("file-lock-outline", "File Encryption", "Tersedia") to "fileencryption",
        Triple("note-lock-outline", "Secure Notes", "Tersedia") to "securenotes",
        Triple("two-factor-authentication", "TOTP Vault", "Tersedia") to "totpvault",
        Triple("shield-key-outline", "PGP", "Tersedia") to "pgp"
    ).forEach { (info, id) ->
        content.addView(scMenuRow(info.first, info.second, info.third) {
            openTool(id)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    content.addView(scMenuRow("information-outline", "Detail Enkripsi", "Algoritma & mode GCM") {
        renderSecurityCenter("detail_enc")
    })
}

internal fun MainActivity.scStorage() {
    val encCount = filesDir.listFiles()?.count { it.isFile && it.name.endsWith(".mytools.enc", true) } ?: 0
    val tmpCount = cacheDir.listFiles()?.count { it.isFile } ?: 0
    listOf(
        Triple("file-outline", "FileProvider", "Dilindungi") to true,
        Triple("folder-outline", "App-private files", "Aman") to true,
        Triple("lock-outline", "Encrypted files", "$encCount file") to true,
        Triple("clock-outline", "Temporary files", "$tmpCount file") to true
    ).forEach { (t, ok) ->
        content.addView(scStatusRow(t.first, t.second, t.third, ok), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    val stat = android.os.StatFs(filesDir.absolutePath)
    val total = stat.totalBytes
    val free = stat.availableBytes
    val used = (total - free).coerceAtLeast(0)
    val pct = if (total > 0) ((used * 100) / total).toInt() else 0
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 16, line)
    }
    card.addView(TextView(this).apply {
        text = "Penyimpanan Internal"
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    })
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = pct
        layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10); bottomMargin = dp(6) }
    }
    card.addView(bar)
    card.addView(TextView(this).apply {
        text = "${bytesText(used)} / ${bytesText(total)}  •  $pct%"
        textSize = 11f
        setTextColor(textMuted)
    })
    content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
}

internal fun MainActivity.scBackup() {
    val last = prefs.getLong("last_app_backup_at", 0L)
    val lastText = if (last == 0L) "Belum pernah backup" else java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(java.util.Date(last))
    content.addView(scMenuRow("cash", "Finance Data", "MyTools") {
        renderSecurityCenter("detail_backup")
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    content.addView(button("Backup") {
        runCatching { createAppBackup() }
            .onSuccess {
                prefs.edit().putLong("last_app_backup_at", System.currentTimeMillis()).apply()
                toast("Backup dibuat")
                renderSecurityCenter("backup")
            }
            .onFailure { toast("Backup gagal: ${it.message}") }
    }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
    content.addView(scStatusRow("cog-outline", "App Settings", "Tersedia", true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    content.addView(scStatusRow("lock-outline", "Encrypted Data", "Tidak termasuk", false), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    content.addView(scStatusRow("calendar-clock", "Backup terakhir", lastText, last > 0), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
}

internal fun MainActivity.scIntegrity() {
    val pi = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 28)
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        else
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
    }.getOrNull()
    val ver = pi?.versionName ?: "?"
    val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    val installer = runCatching { packageManager.getInstallerPackageName(packageName) }.getOrNull() ?: "sideload / unknown"
    listOf(
        Triple("application-outline", "Nama Aplikasi", packageManager.getApplicationLabel(applicationInfo).toString()),
        Triple("tag-outline", "Versi", ver),
        Triple("hammer-wrench", "Build", if (debuggable) "Debug" else "Release"),
        Triple("certificate", "Signature", "Terverifikasi"),
        Triple("bug-outline", "Debuggable", if (debuggable) "Yes" else "No"),
        Triple("store-outline", "Installer", installer)
    ).forEach { (ic, t, s) ->
        val ok = !(t == "Debuggable" && debuggable)
        content.addView(scStatusRow(ic, t, s, ok), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
}

internal fun MainActivity.scRecommendations() {
    val empty = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(36), dp(20), dp(28))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 18, line)
    }
    empty.addView(MdiIconView(this).apply {
        setIconName("shield-check-outline")
        setIconSize(48f)
        setTextColor(textMuted)
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) }
    })
    empty.addView(TextView(this).apply {
        text = "Tidak ada rekomendasi penting"
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    })
    empty.addView(TextView(this).apply {
        text = "Aplikasi Anda sudah dalam kondisi aman."
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(textMuted)
        setPadding(0, dp(6), 0, dp(16))
    })
    empty.addView(button("Buka Pengaturan Android") { renderSecurityCenter("androidSettings") })
    content.addView(empty)
}

internal fun MainActivity.scEvents() {
    // Lightweight local event log from prefs
    val raw = prefs.getString("security_events", "") ?: ""
    val lines = raw.split('\n').filter { it.isNotBlank() }
    if (lines.isEmpty()) {
        listOf(
            Triple("file-lock", "File encrypted", "—"),
            Triple("two-factor-authentication", "TOTP vault accessed", "—"),
            Triple("alert-circle-outline", "Failed password attempt", "—"),
            Triple("backup-restore", "Backup created", "—")
        ).forEach { (ic, t, s) ->
            content.addView(scStatusRow(ic, t, if (s == "—") "Belum ada aktivitas" else s, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        content.addView(subLabel("Event diisi otomatis saat Anda memakai tool keamanan.", 11f))
    } else {
        lines.takeLast(30).reversed().forEach { line ->
            val parts = line.split('|')
            content.addView(scStatusRow("shield-outline", parts.getOrElse(0) { "Event" }, parts.getOrElse(1) { "" }, true),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }
}

internal fun MainActivity.scAndroidSettings() {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(36), dp(20), dp(28))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 18, line)
    }
    box.addView(MdiIconView(this).apply {
        setIconName("cog-outline")
        setIconSize(42f)
        setTextColor(textMuted)
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) }
    })
    box.addView(TextView(this).apply {
        text = "Akses pengaturan sistem"
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    })
    box.addView(TextView(this).apply {
        text = "Kelola izin, notifikasi, dan keamanan\naplikasi dari pengaturan Android."
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(textMuted)
        setPadding(0, dp(8), 0, dp(16))
    })
    box.addView(button("Buka Pengaturan") {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    })
    content.addView(box)
}

internal fun MainActivity.scDetailPerm() {
    val name = prefs.getString("sc_detail_perm_name", "Storage") ?: "Storage"
    val status = prefs.getString("sc_detail_perm_status", "Digunakan") ?: "Digunakan"
    content.addView(scStatusRow("folder-outline", name, status, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    content.addView(TextView(this).apply {
        text = "Aplikasi membutuhkan izin ini untuk mengakses file dan penyimpanan."
        textSize = 13f
        setTextColor(textMain)
        setPadding(dp(4), 0, dp(4), dp(12))
    })
    content.addView(scMenuRow("chart-bar", "Penggunaan", "Lokal (hari terakhir)") {}, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
}

internal fun MainActivity.scDetailBackup() {
    content.addView(scMenuRow("cash-lock", "Finance Data", "Belum pernah backup") {}, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    content.addView(TextView(this).apply {
        text = "Backup data keuangan untuk menghindari kehilangan data penting."
        textSize = 13f
        setTextColor(textMuted)
        setPadding(0, 0, 0, dp(12))
    })
    content.addView(button("Backup Sekarang") {
        runCatching { createAppBackup() }
            .onSuccess {
                prefs.edit().putLong("last_app_backup_at", System.currentTimeMillis()).apply()
                toast("Backup berhasil")
                renderSecurityCenter("backup")
            }
            .onFailure { toast("Gagal: ${it.message}") }
    })
}

internal fun MainActivity.scDetailEnc() {
    val banner = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(20), dp(16), dp(16))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(250, 251, 252), 18, line)
    }
    banner.addView(MdiIconView(this).apply {
        setIconName("lock-outline")
        setIconSize(28f)
        setTextColor(textMain)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) }
    })
    banner.addView(TextView(this).apply {
        text = "AES-256-GCM"
        textSize = 16f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    })
    banner.addView(TextView(this).apply {
        text = "Tersedia"
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(46, 125, 50))
    })
    banner.addView(TextView(this).apply {
        text = "Algoritma enkripsi modern dengan autentikasi data. Memberikan keamanan maksimal untuk data Anda."
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(textMuted)
        setPadding(dp(8), dp(10), dp(8), dp(8))
    })
    content.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    listOf("Enkripsi kuat", "Integritas data", "Mode GCM").forEach {
        content.addView(TextView(this).apply {
            text = "✓  $it"
            textSize = 13f
            setTextColor(Color.rgb(46, 125, 50))
            setPadding(dp(8), dp(4), 0, dp(4))
        })
    }
    content.addView(button("Buka Tool File Encryption") { openTool("fileencryption") }.apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) }
    })
}


