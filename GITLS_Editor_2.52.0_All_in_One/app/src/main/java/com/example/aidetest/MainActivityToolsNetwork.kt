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


internal fun MainActivity.normalizeToolContentHeader() {
        // The toolbar is the single source of truth for the current page title.
        // A number of older/newer screens still add their own title at the top of
        // the scroll content, producing the exact duplicated-heading effect seen
        // on pages such as History Center. Remove only a leading duplicate so
        // real content/section headings further down are never touched.
        if (content.childCount == 0) return

        val toolbarTitle = title.text?.toString()?.trim().orEmpty()
        if (toolbarTitle.isEmpty()) return

        // Header berbentuk container (mis. Color Tools): hapus hanya judul di dalamnya,
        // deskripsi di bawahnya tetap dipertahankan.
        val firstGroup = content.getChildAt(0) as? android.view.ViewGroup
        if (firstGroup != null && firstGroup !is android.widget.ScrollView && firstGroup.childCount in 1..3) {
            val inner = firstGroup.getChildAt(0) as? TextView
            if (inner != null && inner.text?.toString()?.trim().equals(toolbarTitle, ignoreCase = true)) {
                firstGroup.removeViewAt(0)
                if (firstGroup.childCount == 0) content.removeViewAt(0)
                return
            }
        }

        val first = content.getChildAt(0) as? TextView
        if (first != null) {
            val firstText = first.text?.toString()?.trim().orEmpty()
            if (firstText.equals(toolbarTitle, ignoreCase = true)) {
                content.removeViewAt(0)
                val next = content.getChildAt(0) as? TextView
                if (next != null && next.text?.isNotBlank() == true && next.currentTextColor == textMuted) {
                    content.removeViewAt(0)
                }
            }
        }
    }
internal fun MainActivity.webSocketClientTool() {
        clearPage("WebSocket Client")
        toolWorkspace("WebSocket Client", "Hubungkan endpoint WebSocket, kirim pesan, terima frame, dan pantau log.", "connection")
        toolWorkspaceSection("CONNECTION", "Gunakan ws:// atau wss:// lalu kontrol koneksi dari bawah.")
        val url = edit("ws://echo.websocket.events")
        val message = edit("Pesan")
        val log = edit("Log")
        log.isEnabled = false; log.minLines = 8; log.setTextIsSelectable(true)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val connect = button("Connect") {}; val send = button("Send") {}; val receive = button("Receive") {}; val close = button("Close") {}
        row.addView(connect, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin=dp(3) })
        row.addView(send, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin=dp(2); rightMargin=dp(2) })
        row.addView(receive, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin=dp(2); rightMargin=dp(2) })
        row.addView(close, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin=dp(2) })
        content.addView(url); content.addView(message); content.addView(row); content.addView(log)
        fun appendLog(t:String) { log.append((if(log.text.isNotEmpty()) "\n" else "") + t) }
        connect.setOnClickListener {
            val raw=url.text.toString().trim(); if(!raw.startsWith("ws://") && !raw.startsWith("wss://")){toast("Gunakan ws:// atau wss://");return@setOnClickListener}
            connect.isEnabled=false
            toolThread {
                val r=runCatching{ openWebSocket(raw) }.getOrElse{"ERROR: ${it.message}"}
                runOnToolUiThread{appendLog(r); connect.isEnabled=true}
            }
        }
        send.setOnClickListener {
            val msg=message.text.toString(); if(msg.isBlank()){toast("Pesan kosong");return@setOnClickListener}
            toolThread {val r=runCatching{writeWsText(msg); "TX: $msg"}.getOrElse{"ERROR: ${it.message}"};runOnToolUiThread{appendLog(r)}}
        }
        receive.setOnClickListener { toolThread { val r=runCatching{readWsText()}.getOrElse{"ERROR: ${it.message}"}; runOnToolUiThread{appendLog(if(r.startsWith("ERROR")) r else "RX: $r")} } }
        close.setOnClickListener { toolThread { runCatching{closeWebSocket()}; runOnToolUiThread{appendLog("Closed")} } }
    }
internal fun MainActivity.openWebSocket(raw:String):String {
        val u=URI(raw)
        val secure=u.scheme.equals("wss",true)
        if (!secure && !u.scheme.equals("ws", true)) throw IOException("Skema WebSocket tidak didukung")
        if (u.host.isNullOrBlank()) throw IOException("Host WebSocket kosong")
        val port=if(u.port>0)u.port else if(secure)443 else 80
        val generation: Long
        synchronized(wsLock) {
            wsGeneration += 1L
            generation = wsGeneration
            runCatching { wsSocket?.close() }
            wsSocket=null; wsInput=null; wsOutput=null
        }
        val raw = Socket()
        var s: Socket = raw
        try {
            raw.connect(InetSocketAddress(u.host,port),8000)
            if (secure) {
                // TLS di atas socket yang sudah terhubung: SNI terkirim dan nama host sertifikat diverifikasi (anti-MITM).
                val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                    .createSocket(raw, u.host, port, true) as javax.net.ssl.SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.startHandshake()
                s = ssl
            }
            s.soTimeout=12000
            val out=s.getOutputStream(); val input=s.getInputStream()
            val key=android.util.Base64.encodeToString(ByteArray(16).also{SecureRandom().nextBytes(it)}, android.util.Base64.NO_WRAP)
            val path=(if(u.rawPath.isNullOrBlank()) "/" else u.rawPath)+(u.rawQuery?.let{"?$it"} ?: "")
            out.write(("GET $path HTTP/1.1\r\nHost: ${u.host}:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)); out.flush()
            val header=readHttpHeader(input)
            if(!header.startsWith("HTTP/1.1 101") && !header.startsWith("HTTP/1.0 101")) throw IOException("Handshake gagal: ${header.lines().firstOrNull()}")
            val expectedAccept = android.util.Base64.encodeToString(
                java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII)),
                android.util.Base64.NO_WRAP
            )
            val actualAccept = header.lineSequence().firstOrNull { it.startsWith("Sec-WebSocket-Accept:", true) }
                ?.substringAfter(":", "")?.trim()
            if (actualAccept != expectedAccept) throw IOException("Handshake WebSocket tidak valid")
            synchronized(wsLock) {
                if (generation != wsGeneration) throw IOException("Koneksi dibatalkan oleh aksi lain")
                wsSocket=s; wsInput=input; wsOutput=out
            }
            return "Connected: $raw"
        } catch (t: Throwable) {
            runCatching { s.close() }
            throw t
        }
    }
internal fun MainActivity.readHttpHeader(input:InputStream):String { val b=ByteArrayOutputStream(); var state=0; while(true){val c=input.read();if(c<0)break;b.write(c);state=if(state==0&&c==13)1 else if(state==1&&c==10)2 else if(state==2&&c==13)3 else if(state==3&&c==10)4 else 0;if(state==4)break;if(b.size()>16000)throw IOException("Header terlalu besar")};return b.toString("ISO-8859-1") }
internal fun MainActivity.writeWsText(text:String){
        val out=synchronized(wsLock){ wsOutput } ?: throw IOException("Belum terhubung")
        val data=text.toByteArray(StandardCharsets.UTF_8); if(data.size>65535) throw IOException("Pesan terlalu besar")
        val mask=ByteArray(4).also{SecureRandom().nextBytes(it)}; val first=0x81
        synchronized(wsLock) {
            if (out !== wsOutput) throw IOException("Koneksi WebSocket berubah")
            out.write(first)
            when { data.size<126 -> out.write(0x80 or data.size); else -> {out.write(0x80 or 126);out.write(data.size shr 8);out.write(data.size and 255)} }
            out.write(mask); for(i in data.indices) out.write(data[i].toInt() xor mask[i%4].toInt()); out.flush()
        }
    }
internal fun MainActivity.readWsText():String{
        val input=synchronized(wsLock){ wsInput } ?: throw IOException("Belum terhubung")
        val message=ByteArrayOutputStream()
        var fragmented=false
        while(true){
            val h1=input.read(); val h2=input.read(); if(h1<0||h2<0)throw IOException("Koneksi ditutup")
            val fin=(h1 and 0x80)!=0; val opcode=h1 and 0x0f; var len=(h2 and 0x7f).toLong(); val masked=(h2 and 0x80)!=0
            if(len==126L){val a=input.read();val b=input.read();if(a<0||b<0)throw EOFException();len=((a shl 8) or b).toLong()} else if(len==127L){len=0;repeat(8){val x=input.read();if(x<0)throw EOFException();len=(len shl 8) or x.toLong()}}
            if(len>1024*1024 || message.size()+len>1024*1024)throw IOException("Frame/pesan terlalu besar")
            val mask=if(masked)ByteArray(4).also{readFullyWs(input,it)} else null
            val data=ByteArray(len.toInt());readFullyWs(input,data);if(mask!=null)for(i in data.indices)data[i]=(data[i].toInt() xor mask[i%4].toInt()).toByte()
            when(opcode){
                9 -> { writeWsControl(10, data); continue } // PING -> PONG
                10 -> continue // PONG is transport-level noise for the text reader
                8 -> { runCatching{closeWebSocket()}; return "[CLOSE]" }
                1 -> { if(fragmented) throw IOException("WebSocket message fragment tidak valid"); fragmented=true; message.write(data) }
                0 -> { if(!fragmented) throw IOException("WebSocket continuation tanpa message awal"); message.write(data) }
                else -> { if(fin) return "[opcode=$opcode, ${data.size} bytes]"; throw IOException("Opcode WebSocket tidak didukung: $opcode") }
            }
            if(fin){
                if(!fragmented) return String(data,StandardCharsets.UTF_8)
                return String(message.toByteArray(),StandardCharsets.UTF_8)
            }
        }
    }
internal fun MainActivity.writeWsControl(opcode:Int, data:ByteArray){
        if(opcode !in 8..10 || data.size>125) throw IOException("Control frame WebSocket tidak valid")
        val out=synchronized(wsLock){ wsOutput } ?: throw IOException("Belum terhubung")
        synchronized(wsLock){
            if(out!==wsOutput) throw IOException("Koneksi WebSocket berubah")
            out.write(0x80 or (opcode and 0x0f)); out.write(data.size); out.write(data); out.flush()
        }
    }
internal fun MainActivity.readFullyWs(input: InputStream, b: ByteArray) { var p = 0; while (p < b.size) { val n = input.read(b, p, b.size - p); if (n < 0) throw EOFException(); p += n } }
internal fun MainActivity.closeWebSocket(){
        synchronized(wsLock) {
            wsGeneration += 1L
            val old=wsSocket
            wsSocket=null; wsInput=null; wsOutput=null
            runCatching{old?.close()}
        }
    }
internal fun MainActivity.systemCenterTool() {
        clearPage("System Center")
        val am=getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem=ActivityManager.MemoryInfo().also{am.getMemoryInfo(it)}
        val stat=StatFs(Environment.getDataDirectory().path)
        val ramPct=((mem.totalMem-mem.availMem).toDouble()/mem.totalMem*100).roundToInt().coerceIn(0,100)
        val storagePct=((stat.totalBytes-stat.availableBytes).toDouble()/stat.totalBytes*100).roundToInt().coerceIn(0,100)
        modernProgressCard("CPU", cpuLoadPercent(), "Penggunaan & suhu prosesor", "${Runtime.getRuntime().availableProcessors()} core")
        modernProgressCard("RAM", ramPct, "Penggunaan memori", "${bytesText(mem.totalMem-mem.availMem)} / ${bytesText(mem.totalMem)}")
        modernProgressCard("Storage", storagePct, "Penyimpanan internal", "${bytesText(stat.totalBytes-stat.availableBytes)} / ${bytesText(stat.totalBytes)}")
        val processes=am.runningAppProcesses?.size ?: 0
        modernProgressCard("Layanan Sistem", processes.coerceAtMost(100), "Layanan & proses aktif", "$processes aktif")
        modernActionRow("Pengaturan Cepat", "flash") { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
internal fun MainActivity.formatDuration(ms:Long):String { var s=ms/1000; val d=s/86400;s%=86400;val h=s/3600;s%=3600;val m=s/60;s%=60;return "${d}d ${h}h ${m}m ${s}s" }
internal fun MainActivity.uriToCacheFile(uri:Uri,name:String):File{val f=File(cacheDir,name);contentResolver.openInputStream(uri)?.use{input->FileOutputStream(f).use{input.copyTo(it)}}?:throw IOException("File tidak dapat dibaca");return f}
internal fun MainActivity.packageArchiveInfo(f:File):Pair<String,String>{val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else 0;val p=packageManager.getPackageArchiveInfo(f.absolutePath,flags)?:throw IOException("APK tidak valid");return p.packageName to (if(Build.VERSION.SDK_INT>=28)p.longVersionCode.toString() else p.versionCode.toString())}
internal fun MainActivity.sha256(f:File):String{val md=MessageDigest.getInstance("SHA-256");FileInputStream(f).use{inp->val buf=ByteArray(8192);while(true){val n=inp.read(buf);if(n<0)break;md.update(buf,0,n)}};return md.digest().joinToString(""){String.format("%02x",it)} }
internal fun MainActivity.zipSummary(f:File):Pair<Int,Long>{var c=0;var total=0L;ZipInputStream(BufferedInputStream(FileInputStream(f))).use{z->while(true){val e=z.nextEntry?:break;c++;if(!e.isDirectory)total+=e.size.coerceAtLeast(0)}};return c to total}
internal fun MainActivity.scanRoots():List<File> = listOfNotNull(filesDir, getExternalFilesDir(null), File(Environment.getExternalStorageDirectory().path,"Download")).distinctBy{it.absolutePath}.filter{it.exists()}

internal fun MainActivity.scanFiles(dir:File,out:MutableList<File>,limit:Int){if(out.size>=limit)return;val list=runCatching{dir.listFiles()}.getOrNull()?:return;for(f in list){if(out.size>=limit)return;if(f.isFile)out.add(f) else if(f.isDirectory)scanFiles(f,out,limit)}}
internal fun MainActivity.infoCard(titleText:String,bodyText:String):View{val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=bg(panel2,Ds.RADIUS_MD,line)};c.addView(label(titleText,13f,true));c.addView(subLabel(bodyText,11f));c.layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)};return c}
