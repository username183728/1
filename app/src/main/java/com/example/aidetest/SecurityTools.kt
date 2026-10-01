package com.example.aidetest

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
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
import android.widget.ArrayAdapter
import java.io.FileOutputStream
import android.text.InputType
import android.widget.Spinner
import android.widget.TextView
import java.net.URLEncoder
import kotlin.math.roundToInt
import java.util.Base64

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
        content.addView(button("64 byte Base64URL") { output(Base64.getUrlEncoder().withoutPadding().encodeToString(SecureRandom().generateSeed(64))) })
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
        clearPage("File Encryption")
        addToolHeader("File Encryption", "Enkripsi/dekripsi file dengan AES-256-GCM. File diproses lokal.", "AES")
        val pass = edit("Password", false).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        content.addView(pass)
        val selected = label("Belum ada file", 13f); content.addView(selected)
        content.addView(button("Pilih File") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, SECURITY_FILE_PICK)
        })
        content.addView(button("Enkripsi AES-256-GCM") {
            val uri = securityFileUri ?: run { toast("Pilih file dulu"); return@button }
            if (pass.text.isNullOrBlank()) { toast("Password wajib diisi"); return@button }
            toolThread {
                val result = runCatching {
                    val src = contentResolver.openInputStream(uri) ?: error("File tidak bisa dibuka")
                    val plain = src.use { it.readBytes() }
                    val salt = ByteArray(16); val iv = ByteArray(12); SecureRandom().nextBytes(salt); SecureRandom().nextBytes(iv)
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyV2(pass.text.toString(), salt), "AES"), GCMParameterSpec(128, iv))
                    val enc = cipher.doFinal(plain)
                    val name = (uri.lastPathSegment ?: "file").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val out = File(filesDir, "${name}.mytools.enc")
                    FileOutputStream(out).use { it.write("MYTOOLS-FILE-AES2".toByteArray(StandardCharsets.US_ASCII)); it.write(salt); it.write(iv); it.write(enc) }
                    "Enkripsi berhasil\n${out.absolutePath}\nUkuran: ${out.length()} byte"
                }.getOrElse { "Gagal: ${it.message}" }
                runOnUiThread { output(result) }
            }
        })
        content.addView(button("Pilih .mytools.enc untuk Dekripsi") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE) }, SECURITY_FILE_PICK)
        })
        content.addView(button("Dekripsi") {
            val uri = securityFileUri ?: run { toast("Pilih file .enc dulu"); return@button }
            if (pass.text.isNullOrBlank()) { toast("Password wajib diisi"); return@button }
            toolThread {
                val result = runCatching {
                    val all = (contentResolver.openInputStream(uri) ?: error("File tidak bisa dibuka")).use { it.readBytes() }
                    val head = "MYTOOLS-FILE-AES2".toByteArray(StandardCharsets.US_ASCII); require(all.size > head.size + 28 && all.copyOfRange(0, head.size).contentEquals(head)) { "Format file tidak dikenali" }
                    val salt=all.copyOfRange(head.size,head.size+16); val iv=all.copyOfRange(head.size+16,head.size+28); val enc=all.copyOfRange(head.size+28,all.size)
                    val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.DECRYPT_MODE,SecretKeySpec(aesKeyV2(pass.text.toString(),salt),"AES"),GCMParameterSpec(128,iv)); val plain=c.doFinal(enc)
                    val out=File(filesDir,(uri.lastPathSegment ?: "decrypted").removeSuffix(".mytools.enc")+".decrypted")
                    FileOutputStream(out).use{it.write(plain)}; "Dekripsi berhasil\n${out.absolutePath}\nUkuran: ${out.length()} byte"
                }.getOrElse { "Gagal: password salah atau file rusak (${it.message})" }
                runOnUiThread{output(result)}
            }
        })
        content.addView(button("Buka File Terkunci") {
            if (pass.text.isNullOrBlank()) { toast("Password wajib diisi"); return@button }
            showLockedFilePicker(pass.text.toString())
        })
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

internal fun MainActivity.virusScannerTool(){clearPage("Virus Scanner (VirusTotal)");addToolHeader("Virus Scanner","Gunakan VirusTotal API untuk lookup hash file atau scan URL. API key milik pengguna diperlukan.","VT");val key=edit("VirusTotal API key");val target=edit("URL atau SHA-256 file");content.addView(key);content.addView(target);content.addView(button("Scan / Lookup") {val k=key.text.toString().trim();val t=target.text.toString().trim();if(k.isBlank()||t.isBlank()){toast("API key dan target wajib");return@button};toolThread {val r=runCatching{val endpoint=if(Regex("^[A-Fa-f0-9]{64}$").matches(t))"https://www.virustotal.com/api/v3/files/$t" else "https://www.virustotal.com/api/v3/urls/${Base64.getUrlEncoder().withoutPadding().encodeToString(t.toByteArray())}";val c=URL(endpoint).openConnection() as HttpURLConnection;c.setRequestProperty("x-apikey",k);c.connectTimeout=10000;c.readTimeout=10000;"HTTP ${c.responseCode}\n"+(if(c.responseCode in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()}}.getOrElse{"Gagal: ${it.message}"};runOnUiThread{output(r)}}})}

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
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("SHA-256", "SHA-512", "SHA-1", "MD5"))
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
        clearPage("Security Center")
        content.addView(label("Security Center", 24f, true))
        content.addView(subLabel("Ringkasan keamanan dan privasi aplikasi.", 12f))
        content.addView(settingRow("Notification Access", "Tidak digunakan", "MyTools tidak memakai NotificationListenerService dan tidak meminta BIND_NOTIFICATION_LISTENER_SERVICE."))
        content.addView(settingRow("Data keuangan", "Lokal", "Database FinanceDb berada di penyimpanan aplikasi; tidak ada pembacaan notifikasi untuk pencatatan."))
        content.addView(settingRow("Akses jaringan", "INTERNET + status Wi-Fi", "Diperlukan untuk tool jaringan/ESP. Jangan masukkan kredensial sensitif ke log atau payload."))
        content.addView(settingRow("Komponen internal", "FileProvider non-exported", "Berbagi file laporan menggunakan URI permission melalui FileProvider."))
        content.addView(settingRow("Backup", "Manual", "Backup/restore finance dilakukan saat pengguna memintanya."))
        content.addView(button("Hapus seluruh data keuangan") { confirmClearFinance(FinanceDb(this)) })
        content.addView(subLabel("Security Tools", 14f))
        listOf("HelpBot Offline" to "helpbot", "File Encryption" to "fileencryption", "Steganography" to "steganography", "Password Strength Analyzer" to "passwordanalyzer", "Data Breach Checker" to "breachchecker", "Secure Notes" to "securenotes", "2FA Manager (TOTP)" to "totpvault", "PGP Encrypt / Decrypt" to "pgp", "SSH Key Generator" to "sshkeygen", "Certificate Viewer" to "certviewer", "Virus Scanner" to "virusscanner", "URL Safety Checker" to "urlsafety").forEach { (n,id) -> content.addView(settingRowClickable(n, "Buka tool", "", "shield-key-outline") { openTool(id) }) }
        content.addView(button("Buka pengaturan aplikasi Android") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
        content.addView(subLabel("Catatan: halaman ini adalah pemeriksaan konfigurasi aplikasi, bukan audit keamanan perangkat secara menyeluruh.", 11f))
    }
