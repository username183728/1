package com.example.aidetest

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.concurrent.RejectedExecutionException
import android.Manifest
import android.app.*
import android.app.usage.StorageStatsManager
import android.os.StatFs
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
import android.provider.Settings
import android.provider.MediaStore
import android.media.ImageReader
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.text.InputType
import android.view.*
import android.widget.*
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.MultiFormatReader
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.common.HybridBinarizer
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.airbnb.lottie.LottieListener


internal fun MainActivity.toolHelp(id: String, name: String = homeToolMap[id] ?: id): ToolHelp {
        // Id lama (photoeditor / designstudio / bgremover) → Studio Kreatif.
        return when (CsToolIds.canonical(id)) {
            CsToolIds.ID -> ToolHelp(
                "Satu studio berlapis untuk foto, desain, dan hapus latar: Atur cahaya/warna, kurva & HSL, filter, detail, potong/putar/luruskan/perspektif, heal, hapus latar AI + kuas + tepi + latar pengganti + bayangan; teks, bentuk, stiker, layer, panduan tengah & snap, rata & distribusi, undo/redo, proyek, ekspor PNG/JPG/WebP/PDF/SVG.",
                "Saat mengedit foto, membuat thumbnail/poster/banner, atau butuh foto produk berlatar transparan.",
                "Foto › Edit foto (atau Hapus latar) → pilih layer → Atur/Hapus latar → tambah teks → Ekspor."
            )
            "filemanager" -> ToolHelp("Kelola file dan folder di perangkat.", "Saat ingin menyalin, memindahkan, menghapus, atau membuka file.", "Pilih folder Download lalu pindahkan file ke folder lain.", "Pilih file")
            "recentfiles" -> ToolHelp("Menampilkan file yang baru saja digunakan.", "Saat ingin cepat kembali ke file terakhir.", "Buka file yang tadi kamu edit tanpa mencarinya lagi.")
            "backuprestore" -> ToolHelp("Membuat atau memulihkan backup data aplikasi.", "Sebelum reset, pindah perangkat, atau mengembalikan data.", "Export backup → simpan → Restore saat dibutuhkan.")
            "zip" -> ToolHelp("Membuat dan mengekstrak arsip ZIP.", "Saat ingin menggabungkan file atau membuka ZIP.", "Pilih beberapa file → buat ZIP → bagikan satu file.")
            "githubzip" -> ToolHelp("Mengirim project atau ZIP ke GitHub.", "Saat ingin publish/update project dari HP.", "Pilih ZIP project → pilih repository → upload.")
            "fileconvert" -> ToolHelp("Mengonversi file antar format yang didukung.", "Saat format file perlu disesuaikan dengan aplikasi lain.", "Konversi gambar (PNG/JPG/WEBP/BMP) atau kompres satu file ke GZ. Audio, video→audio, dan ZIP dibuka di tool masing-masing.")
            "videotoaudio" -> ToolHelp("Ekstrak audio dari video (MP4/MKV/WebM) menjadi file M4A.", "Saat hanya butuh suara dari video, tanpa gambar.", "Pilih video → Ekstrak Audio → dapatkan file M4A yang bisa diputar seperti MP3.")
            "photoeditor" -> ToolHelp("Editor foto non-destruktif: exposure, kontras, highlight/shadow, suhu/tint, saturasi/vibrance, kurva & HSL, filter dengan intensitas, sharpen/denoise/vignette/grain/blur, blur latar AI, crop/rasio/putar/flip/luruskan/perspektif, heal, layer teks/stiker/gambar, bandingkan sebelum-sesudah, ekspor JPG/PNG/WebP + ukuran kustom.", "Saat ingin mengedit foto secara profesional sebelum dibagikan atau diunggah.", "Pilih foto → atur di tab bawah (Atur, Kurva, Filter, Detail, Potong, Heal, Layer) → Ekspor.")
            "animationstudio" -> ToolHelp("Animasikan desain Studio Kreatif atau foto: keyframe per layer untuk posisi, putar, skala, dan opasitas dengan easing (linear, ease-in, ease-out, ease-in-out); timeline dengan playhead, putar/jeda 30 fps, durasi 1–60 detik, preset (fade, geser masuk, pop, zoom, putar), undo/redo, simpan otomatis, ekspor MP4 (H.264) atau GIF, dan kirim ke Video Editor.", "Saat ingin membuat konten gerak pendek dari desain: intro, story, banner beranimasi, atau GIF.", "Studio Kreatif › Ekspor › Ke Animation Studio → pilih layer → Preset atau seret objek di playhead → Ekspor MP4/GIF.")
            "presentationstudio" -> ToolHelp("Buat presentasi: tiap slide adalah kanvas Studio Kreatif (teks, bentuk, foto, stiker). Tambah/duplikat/hapus/urutkan slide, ukuran 16:9, 4:3, 9:16, transisi per slide (fade/geser), animasi masuk per objek, mode presentasi layar penuh, simpan/buka proyek, ekspor PDF dan MP4 slideshow.", "Saat menyiapkan materi presentasi, pitch, atau slideshow video dari desain.", "Tambah slide (kosong atau dari Studio Kreatif) → edit objek → atur transisi & animasi → Tampilkan atau Ekspor PDF/MP4.")
            "videoeditor" -> ToolHelp("Editor video mode potret: timeline multi-track dengan playhead di tengah, trim lewat gagang, teks/keterangan/stiker yang bisa diseret di pratinjau, musik & rekam suara, efek, filter, transisi, auto-cut (hapus hening/bagi adegan), sampul, dan ekspor MP4 ke Galeri.", "Saat ingin memotong, menyusun, dan mempercantik video pendek seperti di editor video seluler.", "Tambah media → potong & susun di timeline → tambah teks, audio, efek → Ekspor (Movies/GITLS).")
            "audioeditor" -> ToolHelp("Editor audio: waveform + zoom, playhead, pilih rentang, trim, split, fade, gain, gabung, mix, rekam, undo/redo, ekspor WAV/M4A (AAC).", "Saat ingin memotong, merapikan, atau menggabungkan audio.", "Impor audio/video → tekan lama untuk memilih rentang → edit → Ekspor.")
            "designstudio" -> ToolHelp("Kanvas desain presisi: panduan tengah otomatis + magnet, Pusatkan sekali ketuk, rata & distribusi, rotasi, layer, grup, tipografi, proyek tersimpan, ekspor PNG/JPEG/PDF/SVG.", "Saat membuat thumbnail, poster, logo, atau banner.", "Pilih template → tambah teks/bentuk → Pusat → Ekspor.")
            "batchconvert" -> ToolHelp("Konversi batch gambar/audio/video dengan antrean, progress, dan validasi codec.", "Saat banyak file perlu diubah format atau dikompres.", "Tambah file → pilih target → Jalankan antrean.")
            "bgremover" -> ToolHelp("Hapus latar belakang dengan AI di perangkat (ML Kit): orang, hewan, produk. Rapikan dengan kuas hapus/kembalikan, haluskan tepi, ganti latar warna/blur/gambar.", "Saat butuh foto produk, profil, atau stiker berlatar transparan.", "Pilih foto → AI berjalan otomatis → rapikan dengan Kuas/Tepi → Ekspor PNG transparan.")
            "imagecompress" -> ToolHelp("Kompres gambar dengan kualitas, resolusi, atau target ukuran (KB); perkiraan ukuran & pratinjau hasil tampil sebelum disimpan.", "Saat file foto terlalu besar untuk dikirim.", "Pilih gambar → atur kualitas/target → cek perkiraan → Kompres.")
            "imageconvert" -> ToolHelp("Konversi format gambar (JPG, PNG, WebP, BMP) dengan perkiraan ukuran hasil.", "Saat aplikasi lain membutuhkan format tertentu.", "Pilih gambar → pilih format → Konversi.")
            "autotrace" -> ToolHelp(
                "Ubah gambar raster menjadi SVG vector (mode B&W atau Color). Cocok logo, ikon, ilustrasi sederhana untuk Alight Motion.",
                "Saat butuh aset vector dari foto/screenshot tanpa tracing manual.",
                "Pilih gambar → pilih mode B&W/Color → atur threshold/detail → Trace → Simpan SVG."
            )
            "editingstudio" -> ToolHelp("Studio editing: editor teks/kode dan alat media.", "Saat ingin mengedit teks, kode, atau media di satu tempat.", "Buka Text Editor, Video→Audio, atau File Converter.")
            "filesearch" -> ToolHelp("Mencari file berdasarkan NAMA (bukan isi) di folder aplikasi, Download, atau folder yang kamu pilih, dengan filter ekstensi, ukuran, dan tanggal.", "Saat file sulit ditemukan di folder tertentu.", "Cari ekstensi .apk berukuran di atas 5 MB yang diubah 7 hari terakhir.")
            "storage" -> ToolHelp("Menganalisis penggunaan penyimpanan.", "Saat ingin mengetahui apa yang paling banyak memakai storage.", "Lihat kategori/file terbesar sebelum membersihkan storage.")
            "apps" -> ToolHelp("Melihat dan mengelola aplikasi yang terpasang.", "Saat ingin memeriksa aplikasi di perangkat.", "Cari aplikasi tertentu lalu lihat informasi paketnya.")
            "apk" -> ToolHelp("Memeriksa informasi dan metadata APK.", "Saat ingin mengetahui package, versi, atau detail APK.", "Pilih APK → periksa package name dan version.")
            "apkcompare" -> ToolHelp("Membandingkan dua APK: nama paket, versi, izin, tanda tangan, dan isi file.", "Saat ingin tahu apa yang berubah antara dua APK, atau apakah hash berbeda berarti fitur berbeda.", "Pilih APK A dan APK B → Bandingkan → baca penjelasan tiap perbedaan.")
            "duplicatefinder" -> ToolHelp("Mencari file yang isinya sama (ukuran sama, lalu dikonfirmasi SHA-256).", "Saat ingin menghemat storage tanpa menghapus file secara sembarang.", "Scan → lihat grup duplikat → pilih file yang dipertahankan → hapus sisanya dengan konfirmasi.")
            "largefilefinder" -> ToolHelp("Mencari file berukuran minimum tertentu (dalam MB).", "Saat storage hampir penuh.", "Isi ukuran minimum atau pilih preset → pilih folder → Scan → review file terbesar.")
            "filehashcompare" -> ToolHelp("Membandingkan DUA file di perangkat dengan hash (SHA-256 dll.) untuk melihat apakah isinya identik.", "Saat ingin memastikan dua salinan file sama persis.", "Bandingkan APK hasil unduhan dengan salinan lain. Untuk hash resmi dari situs, pakai Checksum File.")
            "devicecenter" -> ToolHelp("Menampilkan informasi perangkat dan sistem.", "Saat ingin memeriksa hardware, Android, atau konfigurasi perangkat.", "Cek versi Android dan informasi perangkat.")
            "json" -> ToolHelp("Membuka workspace untuk bekerja dengan JSON.", "Saat mengedit atau memeriksa data JSON.", "Tempel response API lalu rapikan dan edit datanya.")
            "jsonformat" -> ToolHelp("Merapikan JSON menjadi format yang mudah dibaca.", "Saat JSON dari API terlihat satu baris atau berantakan.", "Tempel JSON minified → format → baca struktur object/array.")
            "base64" -> ToolHelp("Encode atau decode teks menggunakan Base64.", "Saat API atau format data membutuhkan Base64.", "Encode teks menjadi Base64 lalu gunakan hasilnya di request.")
            "base64file" -> ToolHelp("Encode atau decode file dengan Base64.", "Saat sebuah API menerima file sebagai Base64.", "Pilih gambar → encode → salin string Base64.")
            "url" -> ToolHelp("Encode atau decode bagian URL.", "Saat parameter URL memiliki karakter khusus.", "Encode 'hello world' agar aman dimasukkan ke query URL.")
            "regex" -> ToolHelp("Menguji pola regular expression.", "Saat ingin memvalidasi atau mengekstrak pola dari teks.", "Uji regex email terhadap daftar alamat.")
            "uuid" -> ToolHelp("Membuat UUID unik.", "Saat API, database, atau resource membutuhkan ID unik.", "Generate UUID lalu gunakan sebagai ID record.")
            "uuidbatch" -> ToolHelp("Membuat banyak UUID sekaligus.", "Saat membutuhkan ID unik dalam jumlah besar.", "Generate 100 UUID untuk seed data.")
            "textstat" -> ToolHelp("Menganalisis statistik teks.", "Saat ingin mengetahui jumlah karakter, kata, atau baris.", "Tempel artikel → lihat jumlah kata dan karakter.")
            "case" -> ToolHelp("Mengubah format huruf teks.", "Saat perlu uppercase, lowercase, atau variasi case.", "Ubah nama field menjadi UPPERCASE atau lowercase.")
            "dedupe" -> ToolHelp("Menghapus item duplikat per baris, per kata, atau dengan pemisah khusus.", "Saat daftar atau kumpulan kata memiliki item yang berulang.", "Pilih pemisah koma agar 'APK Builder' tetap satu item.")
            "compare" -> ToolHelp("Membandingkan dua teks.", "Saat ingin menemukan perbedaan antara dua versi.", "Bandingkan config lama dan config baru.")
            "slug" -> ToolHelp("Membuat slug ramah URL dari teks.", "Saat membuat URL dari judul.", "'Hello World 2026' → slug untuk URL.")
            "password" -> ToolHelp("Membuat password acak.", "Saat membutuhkan credential baru yang tidak mudah ditebak.", "Generate password lalu simpan di password manager.")
            "passwordstrength" -> ToolHelp("Menilai kekuatan password.", "Saat ingin mengevaluasi password sebelum digunakan.", "Masukkan password uji → lihat indikator kekuatan.")
            "passwordanalyzer" -> ToolHelp("Menganalisis karakteristik password.", "Saat melakukan review keamanan credential.", "Periksa panjang, variasi karakter, dan pola lemah.")
            "token" -> ToolHelp("Membuat token acak.", "Saat membutuhkan identifier atau secret sementara.", "Generate token untuk pengujian API.")
            "jwt" -> ToolHelp("Membaca isi JWT tanpa mengubah signature.", "Saat debugging token autentikasi.", "Tempel JWT → lihat header dan payload.")
            "hmac" -> ToolHelp("Membuat HMAC dengan secret key.", "Saat API menggunakan signature berbasis HMAC.", "Hitung HMAC request menggunakan secret yang kamu miliki.")
            "totp" -> ToolHelp("Membuat kode TOTP untuk autentikasi dua faktor.", "Saat mengelola secret TOTP milik akun sendiri.", "Masukkan secret TOTP → lihat kode yang sedang berlaku.")
            "totpvault" -> ToolHelp("Menyimpan dan mengelola secret TOTP.", "Saat membutuhkan penyimpanan lokal untuk akun 2FA.", "Tambahkan akun → gunakan kode TOTP saat login.")
            "aes" -> ToolHelp("Enkripsi atau dekripsi data dengan AES.", "Saat ingin melindungi data yang kamu simpan atau kirim.", "Enkripsi teks dengan key lalu simpan ciphertext.")
            "random" -> ToolHelp("Menghasilkan random bytes.", "Saat membutuhkan data acak untuk testing atau crypto workflow.", "Generate 32 random bytes untuk kebutuhan pengujian.")
            "hash" -> ToolHelp("Menghasilkan hash dari input.", "Saat ingin membuat fingerprint data.", "Masukkan teks → hitung SHA-256.")
            "checksum" -> ToolHelp("Menghitung hash SATU file dan mencocokkannya dengan hash dari sumber resmi.", "Saat memverifikasi unduhan.", "Pilih APK → tempel SHA-256 dari situs pengembang → lihat cocok atau tidak.")
            "hex" -> ToolHelp("Mengubah data antara teks dan representasi hexadecimal.", "Saat bekerja dengan byte atau protokol.", "Convert byte/string ke HEX untuk debugging.")
            "base32" -> ToolHelp("Encode atau decode Base32.", "Saat bekerja dengan format Base32 seperti secret tertentu.", "Decode string Base32 menjadi data asli.")
            "securitycenter" -> ToolHelp("Menyatukan beberapa pemeriksaan keamanan.", "Saat ingin melakukan pemeriksaan keamanan dasar.", "Buka Security Center lalu review status keamanan yang tersedia.")
            "fileencryption" -> ToolHelp("Melindungi file dengan enkripsi.", "Saat file lokal berisi data yang tidak ingin dibaca sembarang orang.", "Pilih file → enkripsi → simpan hasilnya.")
            "steganography" -> ToolHelp("Menyisipkan atau membaca data tersembunyi pada media yang didukung.", "Untuk eksperimen dan workflow data yang memang kamu miliki.", "Gunakan gambar milik sendiri untuk menguji proses encode/decode.")
            "breachchecker" -> ToolHelp("Memeriksa indikator kebocoran data dengan layanan yang didukung.", "Saat ingin mengecek apakah credential perlu diganti.", "Masukkan identifier yang memang boleh kamu periksa lalu review hasilnya.")
            "securenotes" -> ToolHelp("Menyimpan catatan sensitif secara lokal dengan perlindungan yang tersedia.", "Saat membutuhkan catatan privat di perangkat.", "Buat catatan → kunci → buka kembali saat diperlukan.")
            "pgp" -> ToolHelp("Mengenkripsi atau mendekripsi data menggunakan PGP.", "Saat workflow membutuhkan kriptografi PGP.", "Enkripsi pesan untuk penerima yang memiliki public key yang sesuai.")
            "sshkeygen" -> ToolHelp("Membuat pasangan kunci SSH.", "Saat menyiapkan akses SSH ke server atau Git.", "Generate key pair → pasang public key pada server.")
            "certviewer" -> ToolHelp("Melihat informasi sertifikat digital.", "Saat memeriksa issuer, validity, atau detail sertifikat.", "Buka sertifikat → cek issuer dan masa berlaku.")
            "virusscanner" -> ToolHelp("Memeriksa file dengan mekanisme scanner yang tersedia.", "Saat ingin melakukan pemeriksaan awal terhadap file.", "Pilih file hasil download → jalankan scan → review hasil.")
            "urlsafety" -> ToolHelp("Menganalisis URL untuk indikator risiko yang tersedia.", "Sebelum membuka URL yang tidak kamu kenal.", "Tempel URL → review domain, scheme, dan indikator yang tersedia.")
            "dns" -> ToolHelp("Melakukan DNS lookup.", "Saat ingin mengetahui record DNS sebuah domain.", "Masukkan example.com → lihat record yang tersedia.")
            "rdns" -> ToolHelp("Melakukan reverse DNS lookup.", "Saat ingin mencari hostname dari IP.", "Masukkan IP → lihat hostname yang terdeteksi.")
            "port" -> ToolHelp("Memeriksa konektivitas port pada host yang kamu kelola.", "Saat debugging service atau server milik sendiri.", "Cek host lokal dan port 8080 sebelum debugging API.")
            "publicip" -> ToolHelp("Menampilkan IP publik koneksi.", "Saat perlu mengetahui alamat publik koneksi saat ini.", "Buka tool → lihat IP publik yang terdeteksi.")
            "ping" -> ToolHelp("Menguji keterjangkauan host.", "Saat koneksi ke server terasa bermasalah.", "Ping server milikmu untuk melihat latency dan reachability.")
            "ipinfo" -> ToolHelp("Menampilkan informasi alamat IP.", "Saat debugging konfigurasi jaringan.", "Masukkan IP → lihat informasi yang tersedia.")
            "ssl" -> ToolHelp("Memeriksa sertifikat TLS/SSL sebuah endpoint.", "Saat mengecek konfigurasi HTTPS.", "Periksa domain → lihat issuer dan masa berlaku sertifikat.")
            "http" -> ToolHelp("Menjalankan HTTP server lokal.", "Saat ingin melayani file atau endpoint sederhana dari perangkat.", "Pilih project web → jalankan server → buka http://127.0.0.1:<port> di HP ini (untuk Wi-Fi pakai Web Hosting Wi-Fi).")
            "webhostwifi" -> ToolHelp("Menyajikan halaman HTML melalui jaringan Wi-Fi lokal.", "Saat ingin preview halaman dari perangkat lain di jaringan yang sama.", "Pilih folder web → start hosting → buka alamat lokal.")
            "httpheaders" -> ToolHelp("Melihat atau menguji HTTP headers.", "Saat debugging request/response HTTP.", "Periksa Content-Type, Cache-Control, atau Authorization.")
            "restclient" -> ToolHelp("Mengirim request HTTP ke API dan melihat response.", "Saat menguji API tanpa membuat aplikasi khusus.", "GET /users → kirim request → lihat status, headers, dan body.", "Buat request")
            "websocket" -> ToolHelp("Menguji koneksi WebSocket.", "Saat debugging service realtime.", "Hubungkan endpoint WebSocket → kirim pesan → lihat event response.")
            "network" -> ToolHelp("Melihat informasi jaringan perangkat.", "Saat debugging Wi-Fi atau koneksi data.", "Periksa interface, alamat IP, dan status koneksi.")
            "networkcenter" -> ToolHelp("Menggabungkan beberapa pemeriksaan jaringan.", "Saat ingin melakukan pemeriksaan jaringan dari satu tempat.", "Buka Network Center → pilih pemeriksaan yang dibutuhkan.")
            "wifi" -> ToolHelp("Melihat informasi Wi-Fi yang tersedia untuk aplikasi.", "Saat memeriksa koneksi Wi-Fi perangkat.", "Buka tool saat terhubung ke Wi-Fi untuk melihat detailnya.")
            "qr" -> ToolHelp("Memindai QR code.", "Saat menerima QR berisi URL, teks, atau data lain.", "Arahkan kamera ke QR → baca hasilnya.")
            "color" -> ToolHelp("Memilih, menganalisis, dan mengonversi warna.", "Saat bekerja dengan UI, desain, atau asset.", "Ambil warna dari gambar → salin HEX/RGB.")
            "imagestudio" -> ToolHelp("Mengolah dan memeriksa gambar dari perangkat.", "Saat perlu melakukan pekerjaan gambar sederhana.", "Pilih gambar → gunakan tool yang tersedia → simpan hasil.")
            "number" -> ToolHelp("Membuka pusat kalkulator dengan berbagai mode.", "Saat membutuhkan perhitungan umum atau teknis.", "Pilih mode dasar, ilmiah, rasio, atau kalkulator teknis.")
            "financereader" -> ToolHelp("Mencatat dan mengelola transaksi keuangan lokal.", "Saat ingin mencatat pemasukan dan pengeluaran.", "Tambah transaksi → pilih kategori → lihat saldo.")
            "financedashboard" -> ToolHelp("Menampilkan ringkasan dan insight keuangan.", "Saat ingin melihat pola pemasukan dan pengeluaran.", "Buka dashboard → review ringkasan periode berjalan.")
            "reminder" -> ToolHelp("Membuat pengingat lokal.", "Saat ada tugas atau jadwal yang tidak boleh terlewat.", "Buat reminder untuk waktu tertentu lalu aktifkan notifikasi.")
            "stopwatch" -> ToolHelp("Mengukur durasi dengan stopwatch.", "Saat membutuhkan pengukuran waktu yang berjalan.", "Mulai → ukur durasi → pause/reset.")
            "timer" -> ToolHelp("Menjalankan hitung mundur.", "Saat membutuhkan pengingat berbasis durasi.", "Atur 10 menit → mulai → tunggu notifikasi.")
            "markdown" -> ToolHelp("Melihat Markdown dalam tampilan terformat.", "Saat membaca README atau dokumentasi Markdown.", "Tempel Markdown → lihat hasil render.")
            "sql" -> ToolHelp("Membantu membaca atau bekerja dengan query SQL.", "Saat debugging atau menyusun query database.", "Masukkan query SELECT → periksa struktur dan hasil yang didukung.")
            "yaml" -> ToolHelp("Memformat dan memeriksa YAML.", "Saat mengedit konfigurasi YAML.", "Tempel config YAML → rapikan dan periksa struktur.")
            "toml" -> ToolHelp("Memeriksa struktur TOML.", "Saat debugging file konfigurasi TOML.", "Tempel TOML → periksa section dan value.")
            "cron" -> ToolHelp("Membantu menyusun ekspresi cron.", "Saat membuat jadwal otomatis berbasis cron.", "Atur menit/jam/hari → gunakan ekspresi yang dihasilkan.")
            "helpbot" -> ToolHelp("Memberi bantuan lokal untuk penggunaan tool.", "Saat tidak yakin tool mana yang harus digunakan.", "Cari kebutuhanmu di HelpBot lalu ikuti rekomendasi.")
            "workspace" -> ToolHelp("Pusat workspace untuk workflow project.", "Saat ingin mengatur pekerjaan project dari satu tempat.", "Buka workspace → pilih project atau workflow.")
            "plugincenter" -> ToolHelp("Mengelola integrasi/plugin yang tersedia.", "Saat ingin menambah atau mengatur kemampuan terhubung.", "Buka Plugin Center → review plugin yang tersedia.")
            "customtools" -> ToolHelp("Mengatur tampilan dan perilaku tool yang bisa dikustomisasi.", "Saat ingin menyesuaikan workspace dengan kebiasaanmu.", "Pilih tool → ubah opsi yang tersedia.")
            "studiocenter" -> ToolHelp("Pusat untuk workflow Studio.", "Saat ingin masuk ke salah satu Studio khusus.", "Pilih Studio sesuai pekerjaan yang ingin dilakukan.")
            else -> {
                val lower = name.lowercase(Locale.ROOT)
                when {
                    id.endsWith("calc") -> ToolHelp("Kalkulator khusus untuk $lower.", "Saat membutuhkan perhitungan yang sesuai bidang ini.", "Masukkan nilai yang dibutuhkan → hitung → review hasil.")
                    id.contains("file") -> ToolHelp("Utility untuk bekerja dengan file terkait $lower.", "Saat sedang mengelola atau memeriksa file.", "Pilih file → jalankan operasi → review hasil.")
                    id.contains("network") || id in setOf("dns", "rdns", "port", "ping") -> ToolHelp("Utility jaringan untuk $lower.", "Saat debugging atau memeriksa koneksi yang kamu kelola.", "Masukkan target yang kamu miliki izin untuk uji → jalankan pemeriksaan.")
                    else -> ToolHelp("Utility untuk $lower.", "Saat pekerjaanmu membutuhkan fungsi ini.", "Buka tool → masukkan data yang diperlukan → jalankan aksi utama.")
                }
            }
        }
    }
internal fun MainActivity.showToolHelpDialog(id: String) {
        val name = homeToolMap[id] ?: id
        val help = toolHelp(id, name)
        val message = buildString {
            append(help.purpose)
            append("\n\nKapan digunakan?\n")
            append(help.whenUse)
            append("\n\nContoh\n")
            append(help.example)
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(message)
            .setNegativeButton("Tutup", null)
            .setPositiveButton(help.action) { _, _ -> openTool(id) }
            .show()
    }
internal fun MainActivity.iconFor(id: String): String = when (CsToolIds.canonical(id)) {
        "filemanager" -> "folder-outline"
        "recentfiles" -> "history"
        "backuprestore" -> "backup-restore"
        "workspace" -> "view-dashboard-outline"
        "plugincenter" -> "puzzle-outline"
        "customtools" -> "tune-variant"
        "studiocenter" -> "palette-swatch-outline"
        "editor" -> "file-document-edit-outline"
        "reminder" -> "bell-outline"
        "zip" -> "folder-zip-outline"
        "githubzip" -> "rocket-launch-outline"
        "wifi" -> "wifi"
        "devicecenter" -> "cellphone-cog"
        "json" -> "code-json"
        "hash" -> "pound"
        "base64" -> "numeric-4-box-outline"
        "url" -> "link-variant"
        "regex" -> "regex"
        "uuid" -> "identifier"
        "color" -> "palette-outline"
        "number" -> "calculator-variant-outline"
        "history" -> "history"
        "uicolorcalc" -> "palette-swatch-variant"
        "basiccalc" -> "calculator"
        "scicalc" -> "function-variant"
        "percentcalc" -> "percent-outline"
        "fractioncalc" -> "division"
        "ratiocalc" -> "scale-balance"
        "unitcalc" -> "ruler"
        "areacalc" -> "vector-square"
        "volumecalc" -> "cube-outline"
        "speedcalc" -> "speedometer"
        "timecalc" -> "clock-outline"
        "datecalc" -> "calendar-range-outline"
        "loancalc" -> "bank-outline"
        "fuelcalc" -> "gas-station-outline"
        "pivotcalc" -> "chart-areaspline"
        "dividercalc" -> "sine-wave"
        "dcacalc" -> "finance"
        "pwmcalc" -> "pulse"
        "spritecalc" -> "grid"
        "installcalc" -> "cash-multiple"
        "powercalc" -> "flash-outline"
        "aspectcalc" -> "aspect-ratio"
        "pphcalc" -> "percent"
        "financereader" -> "wallet-outline"
        "financedashboard" -> "chart-line"
        "securitycenter" -> "shield-check-outline"
        "helpbot" -> "robot-outline"
        "riskcalc" -> "scale-balance"
        "compoundcalc" -> "chart-timeline-variant"
        "margincalc" -> "cash-register"
        "discountcalc" -> "tag-outline"
        "datacalc" -> "database-outline"
        "pressurecalc" -> "gauge"
        "worktimecalc" -> "briefcase-clock-outline"
        "basecalc" -> "numeric"
        "equationcalc" -> "sigma"
        "textstat" -> "format-list-numbered"
        "case" -> "format-letter-case"
        "dedupe" -> "content-duplicate"
        "compare" -> "compare"
        "slug" -> "link-box-variant-outline"
        "password" -> "form-textbox-password"
        "token" -> "key-variant"
        "jwt" -> "badge-account-outline"
        "hmac" -> "shield-key-outline"
        "totp" -> "clock-check-outline"
        "aes" -> "lock-outline"
        "random" -> "dice-multiple"
        "checksum" -> "file-check-outline"
        "hex" -> "hexadecimal"
        "base32" -> "numeric"
        "dns" -> "dns"
        "rdns" -> "lan"
        "port" -> "lan-connect"
        "publicip" -> "ip-outline"
        "ping" -> "access-point-network"
        "ipinfo" -> "ip-network"
        "ipintelligence" -> "earth"
        "dohdns" -> "dns-outline"
        "packageinspector" -> "package-variant"
        "apiconnections" -> "api"
        "ssl" -> "certificate-outline"
        "apk" -> "android"
        "qr" -> "qrcode"
        "fileconvert" -> "swap-horizontal"
        "videotoaudio" -> "movie-open-outline"
        "videoeditor" -> "movie-edit-outline"
        "audioeditor" -> "microphone"
        "creativestudio" -> "palette-outline"
        "animationstudio" -> "motion-play-outline"
        "presentationstudio" -> "presentation-play"
        "batchconvert" -> "swap-horizontal"
        "editingstudio" -> "movie-edit-outline"
        "system" -> "cog-outline"
        "http" -> "web"
        "webhostwifi" -> "wifi-star"
        "filehashcompare" -> "file-compare"
        "markdown" -> "language-markdown-outline"
        "sql" -> "database-search-outline"
        "yaml" -> "file-code-outline"
        "toml" -> "file-cog-outline"
        "cron" -> "calendar-clock-outline"
        "passwordstrength" -> "shield-lock-outline"
        "fileencryption" -> "file-lock-outline"
        "steganography" -> "image-lock-outline"
        "passwordanalyzer" -> "shield-search"
        "breachchecker" -> "shield-alert-outline"
        "securenotes" -> "note-edit-outline"
        "totpvault" -> "shield-key-outline"
        "pgp" -> "key-chain-variant"
        "sshkeygen" -> "key-plus"
        "certviewer" -> "certificate-outline"
        "virusscanner" -> "bug-outline"
        "urlsafety" -> "link-lock"
        "stopwatch" -> "timer-outline"
        "timer" -> "timer-sand"
        "imagestudio" -> "image-multiple-outline"
        "imagecompress" -> "arrow-collapse-all"
        "imageconvert" -> "image-auto-adjust"
        "autotrace" -> "vector-polyline"
        "timestamp" -> "clock-time-four-outline"
        "unicode" -> "format-letter-case-upper"
        "urlparser" -> "link-variant"
        "mime" -> "file-document-outline"
        "jsonformat" -> "code-braces"
        "xmlformat" -> "xml"
        "uuidbatch" -> "identifier"
        "base64file" -> "file-code-outline"
        "httpheaders" -> "format-header-1"
        "textreplace" -> "find-replace"
        "wordfreq" -> "counter"
        "deviceinfo" -> "cellphone-information"
        "storage" -> "database"
        "apps" -> "apps"
        "network" -> "network"
        "battery" -> "battery-high"
        "filesearch" -> "file-search"
        "clipboard" -> "clipboard-text-outline"
        "ocr" -> "ocr"
        "unitconverter" -> "swap-horizontal-bold"
        "apkanalyzer" -> "android-studio"
        "netscanner" -> "magnify-scan"
        "webproject" -> "web-plus"
        "webeditor" -> "language-html5"
        "networkstudio" -> "lan"
        "developerstudio" -> "code-tags"
        "filestudio" -> "folder-multiple-outline"
        "imagestudio" -> "image-multiple-outline"
        "colorstudio" -> "palette"
        "systemstudio" -> "cellphone-cog"
        "financestudio" -> "cash-multiple"
        "utilitystudio" -> "toolbox-outline"
        "whois" -> "account-search-outline"
        "traceroute" -> "routes"
        "subnetcalc" -> "ip-network-outline"
        "restclient" -> "api"
        "websocket" -> "connection"
        "networkcenter" -> "lan-connect"
        "systemcenter" -> "view-dashboard-outline"
        "apkcompare" -> "compare-horizontal"
        "duplicatefinder" -> "file-multiple-outline"
        "largefilefinder" -> "file-search-outline"
        else -> "tools"
    }

internal fun MainActivity.edit(hint: String = "", multiline: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        textSize = Ds.TEXT_BODY
        setTextColor(textMain)
        setHintTextColor(textMuted)
        setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD + 2), dp(Ds.SPACE_LG), dp(Ds.SPACE_MD + 2))
        // Soft surface — subtle focus ring, no heavy chrome
        val surface = if (isDarkTheme) panel2 else Color.rgb(246, 247, 250)
        val borderIdle = line
        val borderFocus = if (isDarkTheme) Color.rgb(110, 114, 124) else Color.rgb(160, 166, 176)
        background = bg(surface, Ds.RADIUS_MD, borderIdle)
        isSingleLine = !multiline
        isFocusable = true
        isFocusableInTouchMode = true
        if (hint.isNotBlank()) contentDescription = hint
        setOnFocusChangeListener { view, focused ->
            val stroke = if (focused) dp(1.5f.toInt().coerceAtLeast(1)) else dp(1)
            view.background = bg(surface, Ds.RADIUS_MD, if (focused) borderFocus else borderIdle)
                .also { d -> d.setStroke(stroke, if (focused) borderFocus else borderIdle) }
        }
        if (multiline) {
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            minLines = 1
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            // Auto number pad for common numeric hints
            val h = hint.lowercase(java.util.Locale.ROOT)
            inputType = when {
                h.contains("port") || h.contains("detik") || h.contains("angka") ||
                    h.contains("tahun") || h.contains("bulan") || h.contains("hari") ||
                    h.contains("persen") || h.contains("%") || h.contains("qty") ||
                    h.contains("jumlah") || h.contains("harga") || h.contains("nilai") ->
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        val multilineHeight = (resources.displayMetrics.heightPixels * 0.26f).toInt()
            .coerceIn(dp(140), dp(260))
        layoutParams = LinearLayout.LayoutParams(
            -1,
            if (multiline) multilineHeight else -2
        ).apply { bottomMargin = dp(Ds.SPACE_MD) }
        if (!multiline) minHeight = dp(Ds.INPUT_MIN)

        // --- Auto-restore + auto-save session (semua tools) ---
        // Key stabil: halaman + urutan field + hint → data terbaru selalu balik saat buka tool lagi.
        val fieldIndex = toolFieldSeq++
        val page = currentPage
        val fieldKey = "${hint.ifBlank { "field" }}|$fieldIndex"
        // Tag string: "GITLS_FIELD::page::hint|index"
        tag = "GITLS_FIELD::$page::$fieldKey"
        // Rahasia (password, kunci, token, catatan rahasia) TIDAK pernah disimpan/dipulihkan otomatis.
        if (!isSensitivePage(page) && !isSecretHint(hint)) {
            toolSessionFields[page]?.get(fieldKey)?.let { saved ->
                if (saved.isNotEmpty()) setText(saved)
            }
        }
        val self = this
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (isSensitivePage(page) || isSecretInput(self)) return
                val map = toolSessionFields.getOrPut(page) { LinkedHashMap() }
                map[fieldKey] = e?.toString().orEmpty()
            }
        })
    }

/**
 * Ambil semua EditText di halaman aktif → simpan ke toolSessionFields (data TERBARU).
 * Dipanggil saat keluar tool, pindah Home, atau onPause.
 */
/** Halaman yang isinya rahasia: input tidak disimpan otomatis & hasil tidak masuk Riwayat. */
internal val SENSITIVE_PAGES: Set<String> = setOf(
    "Password Generator", "AES Encrypt / Decrypt", "AES-256-GCM", "HMAC Generator", "TOTP Generator",
    "Token Acak", "Random Bytes", "JWT Decoder", "Hash Generator", "Enkripsi File", "Steganography",
    "Password Strength Analyzer", "Data Breach Checker", "Secure Notes", "2FA Manager (TOTP)",
    "Virus Scanner (VirusTotal)", "PGP Encrypt / Decrypt", "SSH Key Generator", "API Connections",
    "IP Intelligence", "GitHub Publisher"
)

internal fun isSensitivePage(page: String): Boolean = page in SENSITIVE_PAGES

private val SECRET_HINT = Regex("pass|sandi|secret|rahasia|token|api ?key|kunci|\\bkey\\b|\\bpin\\b|private", RegexOption.IGNORE_CASE)

internal fun isSecretHint(hint: CharSequence?): Boolean = hint != null && SECRET_HINT.containsMatchIn(hint)

/** Field kata sandi (inputType) atau berlabel rahasia. */
internal fun isSecretInput(et: EditText): Boolean {
    val t = et.inputType
    val cls = t and android.text.InputType.TYPE_MASK_CLASS
    val v = t and android.text.InputType.TYPE_MASK_VARIATION
    val pwd = (cls == android.text.InputType.TYPE_CLASS_TEXT && (v == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
        v == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
        (cls == android.text.InputType.TYPE_CLASS_NUMBER && v == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    return pwd || isSecretHint(et.hint)
}

internal fun MainActivity.harvestToolSessionFromContent() {
    val page = currentPage
    if (page.isBlank() || page == "home" || page == "all" || page == "favorites" || page == "settings") return
    if (isSensitivePage(page)) { toolSessionFields.remove(page); return }
    if (content.childCount == 0) return
    val map = toolSessionFields.getOrPut(page) { LinkedHashMap() }
    val edits = ArrayList<EditText>()
    fun walk(v: View) {
        when (v) {
            is EditText -> edits.add(v)
            is ViewGroup -> {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
    }
    walk(content)
    edits.forEachIndexed { index, et ->
        if (isSecretInput(et)) return@forEachIndexed
        val tagStr = et.tag as? String
        val key = if (tagStr != null && tagStr.startsWith("GITLS_FIELD::")) {
            // format: GITLS_FIELD::page::hint|index
            tagStr.substringAfter("GITLS_FIELD::").substringAfter("::")
        } else {
            "${et.hint?.toString()?.ifBlank { "field" } ?: "field"}|$index"
        }
        map[key] = et.text?.toString().orEmpty()
    }
    // Juga simpan buffer editor aktif jika sedang di Editor.
    if (page == "Editor" || page.startsWith("Editor")) {
        runCatching { flushEditorCurrentToBuffer(writeDisk = true) }
    }
    // Draft khusus tool lain yang disimpan di variabel (bukan EditText session).
    runCatching {
        prefs.edit()
            .putString("hosting_draft_name", hostingDraftName)
            .putString("hosting_draft_html", hostingDraftHtml)
            .putString("hosting_draft_css", hostingDraftCss)
            .putString("hosting_draft_js", hostingDraftJs)
            .putString("calculator_selected_mode", calculatorSelectedMode)
            .putString("reminder_draft_message", reminderDraftMessage)
            .putString("reminder_draft_body", reminderDraftBody)
            .putString("reminder_draft_note", reminderDraftNote)
            .putInt("reminder_draft_hour", reminderDraftHour)
            .putInt("reminder_draft_minute", reminderDraftMinute)
            .putString("reminder_draft_category", reminderDraftCategory)
            .putString("reminder_draft_repeat", reminderDraftRepeat)
            .putBoolean("reminder_draft_enabled", reminderDraftEnabled)
            .apply()
    }
    toolSessionLastHarvestPage = page
}

/** Persist session semua tools ke SharedPreferences (tahan keluar app / kill process). */
internal fun MainActivity.persistToolSessionFields() {
    runCatching {
        val root = JSONObject()
        toolSessionFields.forEach { (page, fields) ->
            val obj = JSONObject()
            fields.forEach { (k, v) -> obj.put(k, v) }
            root.put(page, obj)
        }
        prefs.edit()
            .putString("tool_session_fields_v1", root.toString())
            .putString("hosting_draft_name", hostingDraftName)
            .putString("hosting_draft_html", hostingDraftHtml)
            .putString("hosting_draft_css", hostingDraftCss)
            .putString("hosting_draft_js", hostingDraftJs)
            .putString("calculator_selected_mode", calculatorSelectedMode)
            .apply()
    }
}

/** Muat session tools dari disk saat app start. */
internal fun MainActivity.loadToolSessionFields() {
    val raw = prefs.getString("tool_session_fields_v1", null)
    var purged = false
    if (raw != null) {
        runCatching {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val page = keys.next()
                // Migrasi: buang isi rahasia yang tersimpan versi lama.
                if (isSensitivePage(page)) { purged = true; continue }
                val obj = root.optJSONObject(page) ?: continue
                val map = LinkedHashMap<String, String>()
                val fk = obj.keys()
                while (fk.hasNext()) {
                    val k = fk.next()
                    if (isSecretHint(k.substringBefore('|'))) { purged = true; continue }
                    map[k] = obj.optString(k, "")
                }
                toolSessionFields[page] = map
            }
        }
        if (purged) persistToolSessionFields()
    }
    // Pulihkan draft tool khusus.
    runCatching {
        hostingDraftName = prefs.getString("hosting_draft_name", hostingDraftName) ?: hostingDraftName
        hostingDraftHtml = prefs.getString("hosting_draft_html", hostingDraftHtml) ?: hostingDraftHtml
        hostingDraftCss = prefs.getString("hosting_draft_css", hostingDraftCss) ?: hostingDraftCss
        hostingDraftJs = prefs.getString("hosting_draft_js", hostingDraftJs) ?: hostingDraftJs
        calculatorSelectedMode = prefs.getString("calculator_selected_mode", calculatorSelectedMode) ?: calculatorSelectedMode
        reminderDraftMessage = prefs.getString("reminder_draft_message", reminderDraftMessage) ?: reminderDraftMessage
        reminderDraftBody = prefs.getString("reminder_draft_body", reminderDraftBody) ?: reminderDraftBody
        reminderDraftNote = prefs.getString("reminder_draft_note", reminderDraftNote) ?: reminderDraftNote
        reminderDraftHour = prefs.getInt("reminder_draft_hour", reminderDraftHour)
        reminderDraftMinute = prefs.getInt("reminder_draft_minute", reminderDraftMinute)
        reminderDraftCategory = prefs.getString("reminder_draft_category", reminderDraftCategory) ?: reminderDraftCategory
        reminderDraftRepeat = prefs.getString("reminder_draft_repeat", reminderDraftRepeat) ?: reminderDraftRepeat
        reminderDraftEnabled = prefs.getBoolean("reminder_draft_enabled", reminderDraftEnabled)
    }
}

internal fun MainActivity.button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = Ds.TEXT_BODY
        minHeight = dp(Ds.BUTTON_MIN)
        setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_MD))
        setStateListAnimator(null)
        elevation = 0f
        isAllCaps = false
        letterSpacing = 0.015f
        contentDescription = text
        isFocusable = true
        // Hierarki visual: hanya aksi utama (proses/simpan/ekspor, atau tombol di dock bawah)
        // yang solid. Tombol pilihan & pendukung memakai gaya tonal bergaris agar halaman
        // tidak lagi berupa deretan pil hitam yang sama rata.
        if (isPrimaryAction(text)) styleAsPrimary(this) else styleAsSecondary(this)
        // Debounce rapid taps to avoid duplicate actions / double network calls.
        var lastClickAt = 0L
        setOnClickListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastClickAt < 400L) return@setOnClickListener
            lastClickAt = now
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (animationsEnabled()) {
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(Ds.ANIM_FAST / 2)
                    .withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(Ds.ANIM_NORMAL)
                            .setInterpolator(Motion.EASE_OUT)
                            .start()
                    }.start()
            }
            runCatching { onClick() }.onFailure {
                android.util.Log.w("GITLS", "button action failed: ${it.message}")
                toast("Aksi gagal: ${it.message?.take(80) ?: "error"}")
            }
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ds.SPACE_SM) }
    }

internal fun MainActivity.toolHeader(titleText: String, description: String, icon: String = "•"): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = bg(panel2, Ds.RADIUS_LG, line)
        }
        val iconView = MdiIconView(this).apply {
            setIconName(resolveToolIcon(titleText, icon)); setIconSize(22f); setTextColor(textMain)
            background = bg(panel, Ds.RADIUS_MD, line)
        }
        box.addView(iconView, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(titleText, 18f, true))
        texts.addView(subLabel(description, 11f))
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return box
    }
internal fun MainActivity.resolveToolIcon(titleText: String, icon: String): String {
        if (MdiGlyphs.has(icon)) return icon
        val exact = homeTools.firstOrNull { it.second.equals(titleText, true) }?.first
        if (exact != null) return iconFor(exact)
        val fuzzy = homeTools.firstOrNull { titleText.contains(it.second, true) || it.second.contains(titleText, true) }?.first
        return if (fuzzy != null) iconFor(fuzzy) else "tools"
    }
internal fun MainActivity.toolSection(titleText: String, subtitle: String = ""): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ds.SPACE_XS), dp(Ds.SPACE_MD), dp(Ds.SPACE_XS), dp(Ds.SPACE_SM))
        }
        box.addView(label(titleText.uppercase(java.util.Locale.ROOT), Ds.TEXT_CAPTION, true).apply {
            setTextColor(textMuted)
            letterSpacing = 0.08f
        })
        // Penanda bagian: dipakai Tool Editor Shell untuk laci bagian yang bisa buka-tutup.
        box.tag = TOOL_SECTION_TAG_PREFIX + titleText
        if (subtitle.isNotBlank()) box.addView(subLabel(subtitle, Ds.TEXT_CAPTION_MIN))
        return box
    }
internal fun MainActivity.toolStatus(textValue: String, positive: Boolean = false): TextView = TextView(this).apply {
        // Status dots follow the app's monochrome theme; state remains explicit in its label.
        val dotColor = textMain
        val sb = android.text.SpannableStringBuilder("●  $textValue")
        sb.setSpan(android.text.style.ForegroundColorSpan(dotColor), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = sb
        textSize = 13f
        setTextColor(textMain)
        minHeight = dp(Ds.TOUCH_MIN)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_MD))
        background = bg(if (positive) panel else panel2, Ds.RADIUS_MD, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ds.SPACE_SM) }
    }

internal fun MainActivity.addToolHeader(titleText: String, description: String, icon: String = "•") {
        // Top bar menampilkan nama tool. Konten: deskripsi ringkas (1–2 baris) + hint alur.
        val desc = description.ifBlank { toolDescription(titleText) }
        if (desc.isNotBlank()) {
            content.addView(TextView(this).apply {
                text = desc
                textSize = Ds.TEXT_SUBTITLE
                setTextColor(textMuted)
                setLineSpacing(0f, 1.25f)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(Ds.SPACE_XS), dp(Ds.SPACE_XS), dp(Ds.SPACE_XS), dp(Ds.SPACE_MD))
                contentDescription = desc
            })
        }
        // Baris chip "Alur" dihapus agar header tool ringkas; tool terkait tetap ada di
        // ikon bantuan (?) → "Tool terkait" (showToolHelpSheet).
    }

    /** Saran alur kerja antar-tool (File→Editor, JSON→Format, dll). */
    internal fun MainActivity.addToolWorkflowHints(id: String) {
        val flows = when {
            id in setOf("json", "jsonformat") -> listOf(
                "Format" to "jsonformat", "Validasi" to "json", "Editor" to "editor", "YAML" to "yaml"
            )
            id == "editor" -> listOf(
                "JSON" to "jsonformat", "Regex" to "regex", "Markdown" to "markdown", "Base64" to "base64"
            )
            id in setOf("filemanager", "zip", "recentfiles") -> listOf(
                "Editor" to "editor", "ZIP" to "zip", "Hash" to "hash", "Backup" to "backuprestore"
            )
            id in setOf("ping", "dns", "traceroute", "port", "ipinfo", "networkcenter") -> listOf(
                "Ping" to "ping", "DNS" to "dns", "Traceroute" to "traceroute", "Port" to "port"
            )
            id in setOf("hash", "checksum", "aes", "password", "securitycenter") -> listOf(
                "Hash" to "hash", "AES" to "aes", "Password" to "password", "Compare" to "filehashcompare"
            )
            id.endsWith("calc") || id == "number" -> listOf(
                "Kalkulator" to "number", "Unit" to "unitconverter", "Riwayat" to "history"
            )
            else -> emptyList()
        }.filter { it.second != id && homeToolMap.containsKey(it.second) }.take(4)
        if (flows.isEmpty()) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = "Alur"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, 0, dp(8), 0)
        })
        flows.forEach { (label, toolId) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(textMain)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rippleBg(
                    if (isDarkTheme) Color.rgb(32, 34, 40) else Color.rgb(240, 242, 246),
                    Ds.RADIUS_PILL, line
                )
                isClickable = true
                contentDescription = "Buka $label"
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    openTool(toolId)
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        content.addView(row)
    }
internal fun MainActivity.toolWorkspace(titleText: String, description: String, icon: String = "tools") {
        // Workspace dipakai oleh tool yang sebelumnya belum punya header standar.
        // Sekarang workspace selalu mendapat header + binding ke tool terkait.
        addToolHeader(titleText, description, icon)
    }
internal fun MainActivity.toolWorkspaceSection(titleText: String, subtitle: String = "") {
        content.addView(toolSection(titleText, subtitle))
    }
internal fun MainActivity.compactButtonRow(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        items.forEachIndexed { index, item ->
            val b = button(item.first, item.second)
            if (index == 0) styleAsPrimary(b) else styleAsSecondary(b)
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) leftMargin = dp(Ds.SPACE_SM)
            })
        }
        return row
    }
internal fun MainActivity.toolControlBar(name: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = bg(panel2, 14, line)
        }
        val status = TextView(this).apply {
            text = "●  Siap digunakan"
            textSize = 11f
            setTextColor(textMain)
            gravity = Gravity.CENTER_VERTICAL
        }
        box.addView(status, LinearLayout.LayoutParams(0, dp(40), 1f))
        fun actionText(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
            this.text = text
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(textMain)
            background = bg(panel, 10, line)
            setPadding(dp(9), 0, dp(9), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        box.addView(actionText("Riwayat") { historyTool() }, LinearLayout.LayoutParams(dp(76), dp(36)).apply { rightMargin = dp(5) })
        box.addView(actionText("Info") {
            AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage(toolDescription(name))
                .setPositiveButton("OK", null)
                .show()
        }, LinearLayout.LayoutParams(dp(54), dp(36)))
        return box
    }
internal fun MainActivity.toolDescription(name: String): String = when {
        name.contains("JSON", true) -> "Validasi, rapikan, kecilkan, dan proses JSON. Hasil dapat disalin atau dibagikan."
        name.contains("Converter", true) -> "Pilih input, tentukan format tujuan, proses file, lalu buka atau bagikan hasil."
        name.contains("Network", true) || name in setOf("Ping", "DNS Lookup", "Reverse DNS", "Port Checker", "SSL Certificate", "REST / API Client", "WebSocket Client", "Network Center") -> "Tool jaringan untuk diagnosis, validasi koneksi, API, WebSocket, dan pemeriksaan host."
        name.contains("Finance", true) || name.contains("Keuangan", true) -> "Pencatatan lokal, transaksi, ringkasan, anggaran, insight, dan ekspor."
        name.contains("Calculator", true) || name.contains("Kalkulator", true) || name in setOf("Voltage Divider", "Pivot Point", "PWM & Duty Cycle") -> "Masukkan parameter, hitung, lalu salin atau bagikan hasil. Input divalidasi sebelum perhitungan."
        name.contains("File", true) || name.contains("ZIP", true) -> "Kelola, baca, konversi, kompres, atau ekstrak file dengan hasil yang dapat diproses kembali."
        else -> "Tool GITLS dengan fungsi utama, validasi input, hasil, salin, bagikan, dan riwayat lokal bila relevan."
    }

internal fun MainActivity.sanitizeSensitiveHistory() {
        val arr = runCatching { JSONArray(prefs.getString("history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val cleaned = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (!isSensitiveTool(item.optString("tool"))) cleaned.put(item)
        }
        if (cleaned.length() != arr.length()) prefs.edit().putString("history", cleaned.toString()).apply()
    }
internal fun MainActivity.isSensitiveTool(name: String = currentPage): Boolean = isSensitivePage(name)

internal fun MainActivity.output(text: String) {
        val safe = text.ifBlank { "(kosong)" }
        if (!isSensitiveTool()) saveHistory(currentPage, safe)
        // Satu kartu hasil saja: hapus yang lama agar tidak menumpuk ke bawah.
        lastOutputCard?.let { old ->
            runCatching { content.removeView(old) }
        }
        lastOutputCard = null
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_SM))
            background = bg(panel2, Ds.RADIUS_LG, line)
            contentDescription = "Hasil $currentPage"
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = MdiIconView(this).apply {
            setIconName(Ds.stateIcon(Ds.State.SUCCESS))
            setIconSize(18f)
            setTextColor(statusColor(Ds.State.SUCCESS))
            background = bg(panel, Ds.RADIUS_SM, line)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        heading.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(Ds.SPACE_SM) })
        val headingText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headingText.addView(label("Hasil", 14f, true))
        headingText.addView(subLabel("${currentPage} • selesai", 12f))
        heading.addView(headingText, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)

        // Output panjang/berbaris banyak (kode, log, JSON) memakai monospace agar rapi.
        val looksLikeCode = safe.contains('\n') || safe.startsWith("{") || safe.startsWith("[")
        val result = TextView(this).apply {
            this.text = safe
            textSize = if (looksLikeCode) 13f else 14f
            if (looksLikeCode) typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(textMain)
            setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD))
            background = bg(panel, Ds.RADIUS_MD, line)
            setTextIsSelectable(true)
            gravity = Gravity.TOP or Gravity.START
        }
        card.addView(result, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Ds.SPACE_SM); bottomMargin = dp(Ds.SPACE_SM) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun resultAction(textValue: String, iconName: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
            this.text = textValue
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minHeight = dp(Ds.BUTTON_MIN)
            isClickable = true
            isFocusable = true
            contentDescription = textValue
            if (primary) styleAsPrimary(this) else styleAsSecondary(this)
            setOnClickListener { onClick() }
        }
        actions.addView(resultAction("Salin", "content-copy", true) { copyText(safe) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(Ds.SPACE_XS) })
        actions.addView(resultAction("Bagikan", "share-variant", false) { shareText(safe) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(Ds.SPACE_XS); rightMargin = dp(Ds.SPACE_XS) })
        actions.addView(resultAction("Editor", "file-document-edit-outline", false) {
            val mode = when {
                safe.trimStart().startsWith("{") || safe.trimStart().startsWith("[") -> "json"
                safe.contains("</") || safe.contains("<!DOCTYPE", true) -> "html"
                safe.contains("{") && (safe.contains("function") || safe.contains("=>")) -> "js"
                else -> "text"
            }
            // Simpan payload lalu buka editor; isi setelah view siap.
            prefs.edit().putString("editor_pending_payload", safe).apply()
            editor(null, mode)
            content.post {
                val payload = prefs.getString("editor_pending_payload", null)
                if (!payload.isNullOrEmpty()) {
                    editorBox?.setText(payload)
                    editorBox?.setSelection(minOf(payload.length, editorBox?.length() ?: 0))
                    prefs.edit().remove("editor_pending_payload").apply()
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(Ds.SPACE_XS); rightMargin = dp(Ds.SPACE_XS) })
        actions.addView(resultAction("Hapus", "delete-outline", false) {
            content.removeView(card)
            if (lastOutputCard === card) lastOutputCard = null
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(Ds.SPACE_XS) })
        card.addView(actions)
        // Animate result in
        if (Motion.enabled) {
            card.alpha = 0f
            card.translationY = dp(8).toFloat()
            card.animate().alpha(1f).translationY(0f).setDuration(Ds.ANIM_NORMAL)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Ds.SPACE_SM); bottomMargin = dp(Ds.SPACE_SM) })
        lastOutputCard = card
        // Scroll ke hasil (aman jika scroll belum siap).
        runCatching { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
    }
internal fun MainActivity.copyText(value:String) {
        val cm=getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("MyTools",value)); toast("Hasil disalin")
    }
internal fun MainActivity.shareText(value:String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,value) },"Bagikan hasil"))
    }
internal fun MainActivity.saveHistory(tool:String, result:String) {
        val arr=runCatching { JSONArray(prefs.getString("history","[]") ?: "[]") }.getOrElse { JSONArray() }
        val item=JSONObject().apply { put("time",System.currentTimeMillis()); put("tool",tool); put("result",result.take(2000)) }
        val next=JSONArray(); next.put(item)
        for(i in 0 until minOf(arr.length(),49)) next.put(arr.getJSONObject(i))
        prefs.edit().putString("history",next.toString()).apply()
    }
internal fun MainActivity.historyTool() {
        clearPage("History Center")
        addToolHeader("History Center", "Riwayat hasil tool (lokal). Data sensitif tidak disimpan.", "history")
        val arr = runCatching { JSONArray(prefs.getString("history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        if (arr.length() == 0) {
            content.addView(TextView(this).apply {
                text = "Belum ada riwayat.\n\nJalankan tool apa saja, lalu hasilnya akan muncul di sini."
                textSize = 14f
                setTextColor(textMuted)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(48), dp(24), dp(24))
            })
            content.addView(button("Buka Semua Tools") { showAllTools() })
            content.addView(button("Command Palette") { showCommandPalette() })
            return
        }
        content.addView(toolSection("ENTRIES", "${arr.length()} hasil tersimpan"))
        content.addView(button("Hapus semua riwayat") {
            AlertDialog.Builder(this)
                .setTitle("Hapus semua riwayat?")
                .setMessage("Tindakan ini tidak bisa dibatalkan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ ->
                    prefs.edit().putString("history", "[]").apply()
                    toast("Riwayat dihapus")
                    historyTool()
                }.show()
        })
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val whenText = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(o.optLong("time")))
            val toolName = o.optString("tool")
            val resultText = o.optString("result")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = bg(panel2, 14, line)
            }
            card.addView(label("$toolName • $whenText", 12f, true))
            card.addView(subLabel(resultText.take(400), 13f))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            fun mini(label: String, act: () -> Unit) = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(textMain)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rippleBg(panel, Ds.RADIUS_PILL, line)
                isClickable = true
                contentDescription = label
                setOnClickListener { act() }
            }
            row.addView(mini("Salin") { copyText(resultText) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            row.addView(mini("Editor") {
                prefs.edit().putString("editor_pending_payload", resultText).apply()
                editor(null, "text")
                content.post {
                    val p = prefs.getString("editor_pending_payload", null)
                    if (!p.isNullOrEmpty()) {
                        editorBox?.setText(p)
                        prefs.edit().remove("editor_pending_payload").apply()
                    }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
    }
internal fun MainActivity.profilePrefs() = getSharedPreferences("gitls_profile", Context.MODE_PRIVATE)

internal fun MainActivity.showLocalRegistration() {
        val prefs = profilePrefs()
        val alreadyRegistered = prefs.getBoolean("registered", false)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = EditText(this).apply {
            hint = "Nama"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            if (alreadyRegistered) setText(prefs.getString("name", "") ?: "")
        }
        val username = EditText(this).apply {
            hint = "Username (opsional, huruf/angka/_ .)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.LengthFilter(24))
            if (alreadyRegistered) setText(prefs.getString("username", "") ?: "")
        }
        name.minHeight = dp(48)
        username.minHeight = dp(48)
        box.addView(name, LinearLayout.LayoutParams(-1, -2))
        box.addView(username, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (alreadyRegistered) "Edit profil lokal" else "Buat profil lokal")
            .setMessage("Profil ini disimpan di perangkat. Tidak membuat akun online.")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()
        dialog.setOnShowListener {
            // Tombol Simpan ditangani manual agar dialog TIDAK tertutup (dan input tidak hilang) saat validasi gagal.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val u = username.text.toString().trim().removePrefix("@")
                when {
                    n.isEmpty() -> { name.error = "Nama belum diisi"; name.requestFocus() }
                    u.isNotEmpty() && !Regex("^[A-Za-z0-9_.]{3,24}$").matches(u) ->
                        { username.error = "3-24 karakter: huruf, angka, _ atau ."; username.requestFocus() }
                    else -> {
                        prefs.edit()
                            .putString("name", n)
                            .putString("username", u)
                            .putBoolean("registered", true)
                            .apply()
                        dialog.dismiss()
                        if (alreadyRegistered) {
                            toast("Profil diperbarui")
                            showProfile() // tetap di halaman profil, tidak dilempar ke Beranda
                        } else {
                            toast("Profil lokal dibuat")
                            enterApp()
                        }
                    }
                }
            }
        }
        dialog.show()
    }
internal fun MainActivity.showProfile() {
        closeDrawer()
        clearPage("profile", true)
        content.setPadding(dp(16), dp(8), dp(16), dp(28))
        val prefs = profilePrefs()
        val name = prefs.getString("name", "Pengguna GITLS") ?: "Pengguna GITLS"
        val username = prefs.getString("username", "") ?: ""
        val registered = prefs.getBoolean("registered", false)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(18))
        }
        header.addView(TextView(this).apply {
            text = if (registered && name.isNotBlank()) String(Character.toChars(name.codePointAt(0))).uppercase(Locale.getDefault()) else "G"
            gravity = Gravity.CENTER
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(25, 25, 28))
            }
        }, LinearLayout.LayoutParams(dp(76), dp(76)))
        header.addView(label(name, 21f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        header.addView(subLabel(if (registered && username.isNotEmpty()) "@$username" else "Guest Account", 12f).apply { gravity = Gravity.CENTER })
        content.addView(header)

        val edit = button(if (registered) "Edit Profil" else "Buat Profil Lokal") {
            showLocalRegistration()
        }
        content.addView(edit)

        sectionTitle("Akun")
        content.addView(settingRow("Status", if (registered) "Profil lokal aktif" else "Guest", "Data profil tersimpan hanya di perangkat."))
        content.addView(settingRow("Username", if (username.isEmpty()) "Belum diatur" else "@$username", "Nama pengguna lokal."))

        sectionTitle("Aktivitas")
        content.addView(settingRow("Tools", "Tersedia", "Semua tool aplikasi tetap dapat digunakan."))
        content.addView(settingRow("Favorit", "Tersimpan lokal", "Daftar favorit mengikuti penyimpanan aplikasi."))
        content.addView(settingRow("Riwayat", "Tersedia", "Riwayat mengikuti tool yang menyediakannya."))

        sectionTitle("Preferensi")
        content.addView(settingRowClickable("Pengaturan", "Tema, animasi, dan opsi aplikasi", "", "cog-outline") { showSettings() })
        content.addView(settingRowClickable("Backup & Restore", "Kelola data aplikasi yang mendukung backup", "", "backup-restore") { showSettings() })

        sectionTitle("Data Profil")
        content.addView(button("Hapus Profil Lokal") {
            AlertDialog.Builder(this).setTitle("Hapus profil lokal?")
                .setMessage("Profil lokal akan dihapus dari perangkat. Tool dan aplikasi tidak ikut dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ ->
                    // Hapus data profil saja; pilihan 'Lanjut tanpa akun' dipertahankan agar layar login tidak muncul lagi.
                    profilePrefs().edit().remove("name").remove("username").remove("registered").putBoolean("guest_chosen", true).apply()
                    toast("Profil lokal dihapus")
                    showProfile()
                }.show()
        })
    }
internal fun MainActivity.toggleDrawer() {
        if (drawerOpen) closeDrawer() else openDrawer()
    }
internal fun MainActivity.closeDrawer() {
        drawerOverlay?.let { rootFrame.removeView(it) }
        drawerOverlay = null
        drawerOpen = false
    }
internal fun MainActivity.openDrawer() {
        if (drawerOpen || mainContainer.visibility != View.VISIBLE) return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { closeDrawer() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28) + sysTopInset, dp(14), dp(18) + sysBottomInset)
            background = GradientDrawable().apply {
                setColor(if (isDarkTheme) Color.rgb(20, 21, 24) else Color.WHITE)
                cornerRadii = floatArrayOf(0f,0f,dp(22).toFloat(),dp(22).toFloat(),dp(22).toFloat(),dp(22).toFloat(),0f,0f)
            }
            elevation = dp(12).toFloat()
            isClickable = true
        }
        overlay.addView(panel, FrameLayout.LayoutParams(dp(310), -1).apply { gravity = Gravity.START })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,0,0,dp(20)) }
        head.addView(TextView(this).apply { text = "G"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE); background = GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(Color.rgb(30,30,34)) } }, LinearLayout.LayoutParams(dp(48),dp(48)))
        head.addView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,0,0); addView(label("GITLS",18f,true)); addView(subLabel("Semua alat dalam satu aplikasi",11f)) }, LinearLayout.LayoutParams(0,-2,1f))
        head.addView(TextView(this).apply { text="×"; textSize=28f; gravity=Gravity.CENTER; setTextColor(textMain); setOnClickListener{closeDrawer()} }, LinearLayout.LayoutParams(dp(42),dp(42)))
        panel.addView(head)

        val items = listOf(
            Triple("Beranda", "home", "home-outline"),
            Triple("Semua Tools", "all", "view-grid"),
            Triple("Favorit", "favorites", "star"),
            Triple("Riwayat", "history", "history"),
            Triple("Profil", "profile", "account-outline"),
            Triple("Pengaturan", "settings", "cog-outline")
        )
        items.forEachIndexed { index, (name, page, icon) ->
            val row = drawerRow(name, icon) {
                closeDrawer()
                when(page) {
                    "home" -> navigateRoot("home") { showHome() }
                    "all" -> navigateRoot("all") { showAllTools() }
                    "favorites" -> navigateRoot("favorites") { showFavorites() }
                    "history" -> showHistoryPage()
                    "profile" -> showProfile()
                    "settings" -> navigateRoot("settings") { showSettings() }
                }
            }
            // Beri ruang antar-item agar drawer tidak terasa padat.
            panel.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply {
                bottomMargin = if (index == items.lastIndex) dp(14) else dp(10)
            })
        }

        // Spacer fleksibel menjaga kelompok bantuan tetap berada di bagian bawah drawer.
        panel.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Kelompok sekunder dipisahkan dari navigasi utama.
        panel.addView(drawerRow("Tentang GITLS", "information-outline") { closeDrawer(); showAbout() },
            LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(10) })
        panel.addView(drawerRow("Bantuan", "help-circle-outline") { closeDrawer(); showHelpPage() },
            LinearLayout.LayoutParams(-1, dp(58)))
        rootFrame.addView(overlay, FrameLayout.LayoutParams(-1,-1))
        drawerOverlay = overlay
        drawerOpen = true
    }
internal fun MainActivity.drawerRow(textValue: String, icon: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            minimumHeight = dp(58)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            background = rippleBg(if (isDarkTheme) panel2 else Color.WHITE, 16, line)
        }
        row.addView(
            MdiIconView(this).apply {
                setIconName(icon)
                setIconSize(22f)
                setTextColor(textMain)
            },
            LinearLayout.LayoutParams(dp(40), dp(58)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        row.addView(
            label(textValue, 15f, false),
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(6)
            }
        )
        return row
    }
internal fun MainActivity.showHistoryPage() {
        clearPage("Riwayat", true)
        content.addView(label("Riwayat", 22f, true))
        content.addView(subLabel("Aktivitas terbaru dari tool yang menyimpan riwayat.", 12f))
        content.addView(settingRow("Status", "Lokal", "Riwayat hanya ditampilkan untuk fitur yang memang menyimpannya."))
        content.addView(button("Kembali ke Beranda") { showHome() })
    }
internal fun MainActivity.showHelpPage() {
        clearPage("Bantuan", true)
        content.addView(label("Bantuan", 22f, true))
        content.addView(subLabel("Panduan singkat penggunaan GITLS.", 12f))
        sectionTitle("Navigasi")
        content.addView(settingRow("Menu", "Navigasi utama", "Gunakan menu kiri atas untuk membuka Profil, Pengaturan, Favorit, dan halaman lainnya."))
        content.addView(settingRow("Profil", "Akun lokal", "Kelola profil lokal perangkat tanpa membuat akun online."))
        content.addView(settingRow("Search", "Cari tools", "Gunakan pencarian dan geser sedikit untuk Snap Search."))
        content.addView(settingRow("+", "Aksi cepat", "Buka daftar tools yang dapat digunakan."))
        sectionTitle("Masalah umum")
        content.addView(settingRow("Icon kosong", "Perbarui aplikasi", "Versi ini memperbaiki beberapa nama glyph yang tidak tersedia."))
        content.addView(settingRow("Keyboard", "Otomatis", "Bottom navigation disembunyikan ketika keyboard terbuka."))
    }
internal fun MainActivity.showAbout() {
        val msg = buildString {
            append("GITLS — suite utilitas native Android.\n\n")
            append("Lebih dari 100 tool offline-first: File, Network, Security, Text & Dev, Calculator, Finance, QR, dan lainnya.\n\n")
            append("• Data keuangan, catatan, dan preferensi disimpan lokal di perangkat.\n")
            append("• Token GitHub dienkripsi dengan Android Keystore.\n")
            append("• Tidak ada akun wajib dan tidak ada pelacakan iklan.\n")
            append("• Beberapa tool keamanan/jaringan bersifat edukatif/sederhana — gunakan dengan bijak.\n\n")
            append("Versi ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        }
        AlertDialog.Builder(this)
            .setTitle("Tentang GITLS")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Privasi") { _, _ -> showPrivacyInfo() }
            .show()
    }
internal fun MainActivity.showPrivacyInfo() {
        val msg = buildString {
            append("Ringkasan Privasi GITLS\n\n")
            append("• Hampir semua tool berjalan sepenuhnya offline.\n")
            append("• Data Finance, Secure Notes, riwayat, dan pengaturan hanya disimpan di perangkat Anda.\n")
            append("• Token GitHub (jika dipakai) dienkripsi dengan kunci di Android Keystore dan tidak dikirim ke server GITLS.\n")
            append("• Izin lokasi & Wi-Fi hanya digunakan untuk fitur Network / Wi-Fi Info yang Anda buka sendiri.\n")
            append("• Notifikasi & alarm hanya untuk pengingat yang Anda buat.\n")
            append("• Tidak ada analytics pihak ketiga atau iklan di dalam aplikasi.\n\n")
            append("Anda dapat mencabut izin kapan saja lewat Pengaturan sistem perangkat.")
        }
        AlertDialog.Builder(this)
            .setTitle("Privasi & Data")
            .setMessage(msg)
            .setPositiveButton("Mengerti", null)
            .setNeutralButton("Kelola Izin") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    })
                }
            }
            .show()
    }
internal fun MainActivity.sectionTitle(textValue: String): View {
        val v = TextView(this).apply {
            text = textValue
            textSize = 13f
            setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(14), dp(4), dp(8))
            tag = TOOL_SECTION_TAG_PREFIX + textValue
        }
        content.addView(v)
        return v
    }
internal fun MainActivity.sectionTitle(textValue: String, actionText: String, onAction: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(8))
            tag = TOOL_SECTION_TAG_PREFIX + textValue
        }
        row.addView(TextView(this).apply {
            text = textValue
            textSize = 13f
            setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = actionText
            textSize = 12f
            setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = bg(panel2, 10, line)
            setOnClickListener { onAction() }
        })
        content.addView(row)
        return row
    }
internal fun MainActivity.openToolWithPress(id: String, view: View) {
        // Debounce rapid repeated taps so a single card cannot queue the same tool
        // multiple times while the previous transition is still starting.
        addPressFeedback(view)
        val now = android.os.SystemClock.uptimeMillis()
        if (id == lastToolOpenId && now - lastToolOpenAt < 350L) return
        lastToolOpenId = id
        lastToolOpenAt = now
        view.postDelayed({
            if (id == lastToolOpenId) openTool(id)
        }, 70L)
    }
/** P2: AlertDialog mengikuti tema gelap/terang perangkat. */
internal fun MainActivity.themedDialog(): AlertDialog.Builder {
    val style = if (isDarkTheme) {
        android.R.style.Theme_DeviceDefault_Dialog_Alert
    } else {
        android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    }
    return AlertDialog.Builder(this, style)
}

internal fun MainActivity.openTool(requestedId: String) {
        // Id lama Photo Editor / Design Studio / Background Remover → Studio Kreatif + aksi awalnya.
        val studioEntry = CsToolIds.entryFor(requestedId)
        val id = CsToolIds.canonical(requestedId)
        // Catat tool terakhir yang dibuka (setelah halaman selesai dibangun) untuk pemulihan sesi.
        window.decorView.post { lastOpenedToolId = id; lastOpenedToolPage = currentPage }
        BitRuntimeContext.onToolOpened(id, homeToolMap[id] ?: id)
        recordRecentTool(id)
        when (id) {
            "filemanager" -> fileManager(filesDir)
            "recentfiles" -> recentFilesTool()
            "backuprestore" -> backupRestoreTool()
            "workspace" -> workspaceCenterTool()
            "plugincenter" -> pluginCenterTool()
            "customtools" -> toolCustomizationTool()
            "studiocenter" -> studioCenterTool()
            "editor" -> editor(null)
            "reminder" -> reminderTool()
            "zip" -> zipTool()
            "githubzip" -> githubZipTool()
            "wifi" -> wifiInfo()
            "devicecenter" -> deviceSystemCenterTool()
            "json" -> jsonTool()
            "hash" -> hashTool()
            "base64" -> simpleTransform("Base64", "Encode", "Decode")
            "url" -> urlTool()
            "regex" -> regexTool()
            "uuid" -> simpleResultTool("UUID Generator") { UUID.randomUUID().toString() }
            "color" -> colorTool()
            "number" -> calculatorHub()
            "history" -> historyTool()
            // UI Color adalah pipet layar global, bukan mode kalkulator.
            "uicolorcalc" -> uiColorPickerTool()
            // Semua kalkulator tetap berada di satu layar. Mode hanya mengganti isi workspace.
            "basiccalc", "scicalc", "percentcalc", "fractioncalc", "ratiocalc",
            "unitcalc", "areacalc", "volumecalc", "speedcalc", "timecalc", "datecalc",
            "loancalc", "fuelcalc", "pivotcalc", "dividercalc", "dcacalc", "pwmcalc",
            "spritecalc", "installcalc", "powercalc", "aspectcalc", "pphcalc" -> calculatorHub(id)
            "financereader" -> financeReaderTool(0)
            "financedashboard" -> financeDashboardTool()
            "securitycenter" -> securityCenterTool()
            "helpbot" -> helpBotTool()
            "riskcalc" -> riskRewardCalculator()
            "compoundcalc" -> compoundCalculator()
            "margincalc" -> marginTaxCalculator()
            "discountcalc" -> tieredDiscountCalculator()
            "datacalc" -> dataUnitCalculator()
            "pressurecalc" -> pressureCalculator()
            "worktimecalc" -> workTimeCalculator()
            "basecalc" -> baseCalculator()
            "equationcalc" -> equationCalculator()
            "textstat" -> textStatTool()
            "case" -> caseTool()
            "dedupe" -> dedupeTool()
            "compare" -> compareTool()
            "slug" -> slugTool()
            "password" -> passwordTool()
            "token" -> tokenTool()
            "jwt" -> jwtTool()
            "hmac" -> hmacTool()
            "totp" -> totpTool()
            "aes" -> aesTool()
            "random" -> randomTool()
            "checksum" -> checksumTool()
            "hex" -> hexTool()
            "base32" -> base32Tool()
            "dns" -> dnsTool()
            "rdns" -> reverseDnsTool()
            "port" -> portTool()
            "publicip" -> publicIpTool()
            "ping" -> pingTool()
            "ipinfo" -> ipInfoTool()
            "ipintelligence" -> ipIntelligenceTool()
            "dohdns" -> dohDnsTool()
            "packageinspector" -> packageInspectorTool()
            "apiconnections" -> apiConnectionsTool()
            "ssl" -> sslTool()
            "apk" -> apkInspector()
            "qr" -> qrTool()
            "fileconvert" -> fileConvertTool()
            "system" -> systemInfo()
            "http" -> httpServer()
            "webhostwifi" -> wifiHtmlHostingTool()
            "filehashcompare" -> fileHashCompareTool()
            "markdown" -> markdownViewerTool()
            "sql" -> sqlToolsTool()
            "yaml" -> yamlFormatterTool()
            "toml" -> tomlInspectorTool()
            "cron" -> cronHelperTool()
            "passwordstrength" -> passwordStrengthTool()
            "fileencryption" -> fileEncryptionTool()
            "steganography" -> steganographyTool()
            "passwordanalyzer" -> passwordStrengthAnalyzerTool()
            "breachchecker" -> dataBreachCheckerTool()
            "securenotes" -> secureNotesTool()
            "totpvault" -> totpVaultTool()
            "pgp" -> pgpTool()
            "sshkeygen" -> sshKeyGeneratorTool()
            "certviewer" -> certificateViewerTool()
            "virusscanner" -> virusScannerTool()
            "urlsafety" -> urlSafetyTool()
            "stopwatch" -> stopwatchTool()
            "timer" -> timerTool()
            "imagestudio" -> imageStudioTool()
            "timestamp" -> timestampTool()
            "unicode" -> unicodeTool()
            "urlparser" -> urlParserTool()
            "mime" -> mimeTool()
            "jsonformat" -> jsonTool()
            "xmlformat" -> xmlFormatTool()
            "uuidbatch" -> uuidBatchTool()
            "base64file" -> base64FileTool()
            "httpheaders" -> httpHeadersTool()
            "textreplace" -> textReplaceTool()
            "wordfreq" -> wordFrequencyTool()
            "deviceinfo" -> deviceInfoTool()
            "storage" -> storageAnalyzerTool()
            "apps" -> appManagerTool()
            "network" -> networkInfoTool()
            "battery" -> batteryInfoTool()
            "filesearch" -> fileSearchTool()
            "clipboard" -> clipboardManagerTool()
            "ocr" -> ocrTool()
            "unitconverter" -> unitConverterProTool()
            "apkanalyzer" -> apkAnalyzerTool()
            "netscanner" -> networkScannerTool()
            "webproject" -> webProjectBuilder()
            "webeditor" -> editor(null)
            "networkstudio" -> networkStudioTool()
            "developerstudio" -> developerStudioTool()
            "filestudio" -> fileStudioTool()
            "colorstudio" -> colorTool()
            "systemstudio" -> systemStudioTool()
            "financestudio" -> financeStudioTool()
            "utilitystudio" -> utilityStudioTool()
            "editingstudio" -> editingStudioTool()
            "videotoaudio" -> videoToAudioTool()
            "videoeditor" -> videoEditorTool()
            "audioeditor" -> audioEditorTool()
            CsToolIds.ID -> creativeStudioTool(studioEntry)
            ANIM_STUDIO_ID -> animationStudioTool()
            PRESENT_STUDIO_ID -> presentationStudioTool()
            "batchconvert" -> batchConverterTool()
            "imagecompress" -> imageCompressorTool()
            "imageconvert" -> imageConverterTool()
            "autotrace" -> autoTraceTool()
            "whois" -> whoisTool()
            "traceroute" -> tracerouteTool()
            "subnetcalc" -> subnetCalculatorTool()
            "restclient" -> restApiClientTool()
            "websocket" -> webSocketClientTool()
            "networkcenter" -> networkCenterTool()
            "systemcenter" -> systemCenterTool()
            "apkcompare" -> apkCompareTool()
            "duplicatefinder" -> duplicateFinderTool()
            "largefilefinder" -> largeFileFinderTool()
            else -> {
                toast("Tool tidak dikenali: $id")
                android.util.Log.w("GITLS", "openTool unknown id=$id")
            }
        }
        // Global UI normalization: the toolbar is the single page title.
        // Older tools sometimes still add the same title (and subtitle) into the
        // scroll content, which makes the page look duplicated/overlapped.
        content.post { normalizeToolContentHeader() }
        if (currentPage != "Editor" && !currentPage.startsWith("Editor - ")) {
            content.post { Motion.openWorkspace(content, Motion.characterFor(id)) }
        }
    }

private val TOOL_RELATED_BINDINGS: Map<String, List<String>> = mapOf(
    "filemanager" to listOf("recentfiles", "filesearch", "zip", "backuprestore"),
    "recentfiles" to listOf("filemanager", "filesearch", "backuprestore"),
    "filesearch" to listOf("filemanager", "largefilefinder", "duplicatefinder", "dedupe"),
    "zip" to listOf("filemanager", "fileconvert", "githubzip", "backuprestore"),
    "videotoaudio" to listOf("fileconvert", "filestudio", "editingstudio", "videoeditor"),
    "videoeditor" to listOf("videotoaudio", "editingstudio", "creativestudio", "imagestudio", "audioeditor"),
    "audioeditor" to listOf("videotoaudio", "videoeditor", "editingstudio"),
    "creativestudio" to listOf("animationstudio", "presentationstudio", "imagecompress", "imageconvert", "autotrace", "imagestudio", "color"),
    "animationstudio" to listOf("creativestudio", "presentationstudio", "videoeditor"),
    "presentationstudio" to listOf("creativestudio", "animationstudio", "videoeditor"),
    "batchconvert" to listOf("fileconvert", "imageconvert", "imagecompress", "videotoaudio", "audioeditor"),
    "imagecompress" to listOf("creativestudio", "imageconvert", "imagestudio"),
    "imageconvert" to listOf("creativestudio", "imagecompress", "fileconvert", "autotrace"),
    "autotrace" to listOf("creativestudio", "imageconvert", "imagestudio", "batchconvert"),
    "editingstudio" to listOf("editor", "videotoaudio", "fileconvert", "webeditor"),
    "githubzip" to listOf("filemanager", "zip", "backuprestore", "securitycenter"),
    "backuprestore" to listOf("filemanager", "zip", "securenotes", "financedashboard"),
    "editor" to listOf("json", "jsonformat", "regex", "textreplace", "markdown"),
    "json" to listOf("jsonformat", "editor", "base64", "textreplace"),
    "jsonformat" to listOf("json", "editor", "yaml", "toml"),
    "ocr" to listOf("qr", "imagestudio", "editor", "jsonformat"),
    "qr" to listOf("ocr", "url", "clipboard", "imagestudio"),
    "clipboard" to listOf("editor", "qr", "urlparser", "hash"),
    "hash" to listOf("checksum", "filehashcompare", "password", "securitycenter"),
    "checksum" to listOf("hash", "filehashcompare", "filemanager"),
    "filehashcompare" to listOf("hash", "checksum", "filemanager"),
    "password" to listOf("passwordstrength", "passwordanalyzer", "securenotes", "totpvault"),
    "passwordstrength" to listOf("password", "passwordanalyzer", "securenotes"),
    "jwt" to listOf("base64", "json", "hmac", "hash"),
    "hmac" to listOf("hash", "jwt", "aes", "password"),
    "totp" to listOf("totpvault", "password", "securenotes"),
    "aes" to listOf("fileencryption", "securenotes", "password", "pgp"),
    "fileencryption" to listOf("aes", "filemanager", "hash", "backuprestore"),
    "dns" to listOf("rdns", "ipinfo", "ping", "networkcenter"),
    "rdns" to listOf("dns", "ipinfo", "whois", "networkcenter"),
    "ping" to listOf("dns", "traceroute", "port", "networkcenter"),
    "port" to listOf("ping", "netscanner", "networkcenter", "restclient"),
    "traceroute" to listOf("ping", "dns", "networkcenter", "ipinfo"),
    "whois" to listOf("dns", "rdns", "ssl", "ipinfo"),
    "ipinfo" to listOf("publicip", "dns", "whois", "networkcenter"),
    "ssl" to listOf("httpheaders", "urlsafety", "whois", "networkcenter"),
    "restclient" to listOf("httpheaders", "websocket", "ssl", "json"),
    "websocket" to listOf("restclient", "networkcenter", "json"),
    "networkcenter" to listOf("wifi", "network", "dns", "ping", "netscanner"),
    "wifi" to listOf("networkcenter", "network", "ipinfo", "webhostwifi"),
    "apk" to listOf("apkanalyzer", "apkcompare", "hash", "storage"),
    "apkanalyzer" to listOf("apk", "apkcompare", "storage", "filemanager"),
    "apkcompare" to listOf("apk", "apkanalyzer", "hash", "filemanager"),
    "number" to listOf("unitconverter", "history", "pivotcalc", "powercalc"),
    "pivotcalc" to listOf("number", "riskcalc", "dcacalc"),
    "dcacalc" to listOf("number", "compoundcalc", "riskcalc", "financedashboard"),
    "financedashboard" to listOf("financereader", "dcacalc", "compoundcalc", "powercalc"),
    "financereader" to listOf("financedashboard", "number", "backuprestore"),
    "imagestudio" to listOf("ocr", "qr", "color", "steganography"),
    "color" to listOf("imagestudio", "uicolorcalc", "spritecalc")
)

internal fun MainActivity.toolIdForUx(title: String): String? {
    val last = BitRuntimeContext.lastToolId
    if (!last.isNullOrBlank() && (currentPage.equals(title, true) || title.contains(homeToolMap[last] ?: "\u0000", true))) return last
    return homeTools.firstOrNull { it.second.equals(title, true) }?.first
        ?: homeTools.firstOrNull { title.contains(it.second, true) || it.second.contains(title, true) }?.first
}

internal fun MainActivity.relatedIdsForUx(id: String): List<String> {
    val explicit = TOOL_RELATED_BINDINGS[id].orEmpty()
    if (explicit.isNotEmpty()) return explicit.filter { homeToolMap.containsKey(it) }.distinct().take(5)
    val category = toolCategory(id)
    return homeTools.asSequence().map { it.first }
        .filter { it != id && homeToolMap.containsKey(it) }
        .filter { toolCategory(it) == category }
        .distinct().take(5).toList()
}

internal fun MainActivity.showRelatedTools(id: String) {
    val ids = relatedIdsForUx(id)
    if (ids.isEmpty()) {
        toast("Belum ada tool terkait")
        return
    }
    val names = ids.map { homeToolMap[it] ?: it }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Tool terkait")
        .setItems(names) { _, which -> openTool(ids[which]) }
        .setNegativeButton("Tutup", null)
        .show()
}

internal fun MainActivity.addToolUxActionRow(id: String, title: String) {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(10))
    }

    fun action(label: String, icon: String? = null, click: () -> Unit): TextView = TextView(this).apply {
        text = if (icon.isNullOrBlank()) label else "$icon  $label"
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(textMuted)
        background = rippleBg(
            if (isDarkTheme) Color.rgb(28, 28, 32) else Color.rgb(245, 246, 248),
            Ds.RADIUS_PILL,
            Color.TRANSPARENT
        )
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(40)
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            click()
        }
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginEnd = dp(6)
        }
    }

    val favorite = action("Favorit", if (isFavorite(id)) "★" else "☆") {
        toggleFavorite(id)
        if (currentPage == "favorites") showFavorites(force = true) else Unit
    }
    val related = action("Terkait", "↗") { showRelatedTools(id) }
    val history = action("Riwayat", "◷") { historyTool() }
    val guide = action("Panduan", "?") {
        if (homeToolMap.containsKey(id)) showToolHelpDialog(id)
        else AlertDialog.Builder(this).setTitle(title).setMessage(toolDescription(title)).setPositiveButton("OK", null).show()
    }
    (guide.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
    row.addView(favorite)
    row.addView(related)
    row.addView(history)
    row.addView(guide)
    content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
}

internal fun MainActivity.showBitToolPicker() {
    val dialog = android.app.Dialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(12))
        setBackgroundColor(if (isDarkTheme) Color.rgb(22,22,24) else Color.WHITE)
    }
    root.addView(label("Cari Tool", 18f, true), LinearLayout.LayoutParams(-1, -2))
    root.addView(subLabel("Pilih tool dan Bit akan membukanya.", 11f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    val search = EditText(this).apply {
        hint = "contoh: JSON, WiFi, hash, OCR"
        textSize = 14f
        setTextColor(textMain)
        setHintTextColor(textMuted)
        background = bg(if (isDarkTheme) panel2 else Color.rgb(247,248,250), 14, line)
        setPadding(dp(12), 0, dp(12), 0)
        minHeight = dp(48)
    }
    root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
    val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    scroll.addView(list)
    root.addView(scroll, LinearLayout.LayoutParams(-1, dp(420)))

    fun render(query: String) {
        list.removeAllViews()
        val q = query.trim().lowercase(Locale.ROOT)
        val items = homeTools.asSequence().filter { pair ->
            val id = pair.first
            val name = pair.second
            q.isBlank() || name.lowercase(Locale.ROOT).contains(q) || id.lowercase(Locale.ROOT).contains(q)
        }
            .distinctBy { it.first }
            .take(60).toList()
        if (items.isEmpty()) {
            list.addView(subLabel("Tidak ada tool yang cocok.", 13f).apply { setPadding(dp(4), dp(16), dp(4), dp(16)) })
            return
        }
        items.forEach { (id, name) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(8), dp(7))
                background = rippleBg(if (isDarkTheme) panel2 else Color.WHITE, 14, line)
                isClickable = true
                setOnClickListener { dialog.dismiss(); openTool(id) }
            }
            row.addView(MdiIconView(this).apply { setIconName(iconFor(id)); setIconSize(20f); setTextColor(textMain) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
            box.addView(label(name, 13f, true))
            box.addView(subLabel(toolCategory(id), 10f))
            row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(textMuted) }, LinearLayout.LayoutParams(dp(26), dp(40)))
            list.addView(row, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(6) })
        }
    }
    search.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    })
    render("")
    dialog.setContentView(root)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.setOnShowListener { dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(540)) }
    dialog.show()
    dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(540))
}

internal fun MainActivity.addBitFeaturePanel() {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = bg(if (isDarkTheme) panel2 else Color.rgb(248,249,250), 18, line)
    }
    val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    head.addView(MdiIconView(this).apply { setIconName("robot-outline"); setIconSize(20f); setTextColor(textMain) }, LinearLayout.LayoutParams(dp(30), dp(30)))
    head.addView(label("Pusat Bit", 14f, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
    head.addView(subLabel("offline", 9.5f))
    card.addView(head)
    card.addView(subLabel("Bit terhubung ke registry tool, konteks runtime, dan aksi aman.", 10.5f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })

    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun chip(text: String, icon: String, click: () -> Unit): TextView = TextView(this).apply {
        this.text = "$icon  $text"
        textSize = 10.5f
        gravity = Gravity.CENTER
        setTextColor(textMain)
        background = rippleBg(if (isDarkTheme) Color.rgb(34,34,38) else Color.WHITE, 14, line)
        minHeight = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) }
    }
    actions.addView(chip("Cari Tool", "⌕") { showBitToolPicker() })
    actions.addView(chip("Diagnose", "✓") { showEditorDiagnostics() })
    actions.addView(chip("OCR", "□") { openTool("ocr") })
    actions.addView(chip("GitHub", "↑") { openTool("githubzip") })
    card.addView(actions)
    content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
}
