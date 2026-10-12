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


internal fun MainActivity.accentTeal(): Int = Color.rgb(0, 137, 123)

internal fun MainActivity.modernPageTitle(titleText:String, subtitleText:String, icon:String){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(2),dp(4),dp(2),dp(14))}
        val backBtn=TextView(this).apply{text="‹";textSize=38f;gravity=Gravity.CENTER;colorize(textMain);setOnClickListener{onBackPressed()}}
        row.addView(backBtn,LinearLayout.LayoutParams(dp(42),dp(48)))
        val texts=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};texts.addView(label(titleText,22f,true));texts.addView(subLabel(subtitleText,12f));row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
        val iv=MdiIconView(this).apply{setIconName(icon);setIconSize(22f);setTextColor(textMain);background=bg(panel2,18,line)};row.addView(iv,LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(row)
    }
internal fun TextView.colorize(c:Int){setTextColor(c)}
internal fun MainActivity.modernToolListRow(titleText:String,sub:String,icon:String,onClick:()->Unit){val card=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(12),dp(12),dp(12));background=rippleBg(panel,Ds.RADIUS_XL,line);setOnClickListener{onClick()}};Motion.press(card,0.98f);val ib=MdiIconView(this).apply{setIconName(icon);setIconSize(22f);setTextColor(textMain);background=bg(panel2,Ds.RADIUS_MD,line)};card.addView(ib,LinearLayout.LayoutParams(dp(56),dp(56)).apply{rightMargin=dp(14)});val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};tx.addView(label(titleText,16f,true));tx.addView(subLabel(sub,12f));card.addView(tx,LinearLayout.LayoutParams(0,-2,1f));card.addView(label("›",28f).apply{setTextColor(textMuted)},LinearLayout.LayoutParams(dp(30),dp(50)));content.addView(card,LinearLayout.LayoutParams(-1,dp(96)).apply{bottomMargin=dp(10);leftMargin=dp(2);rightMargin=dp(2)})}
internal fun MainActivity.modernMetricGrid(items:List<Pair<String,String>>){val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};items.chunked(2).forEach{pair->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};pair.forEachIndexed{idx,(a,b)->val c=modernSmallMetric(a,b,"information-outline");row.addView(c,LinearLayout.LayoutParams(0,-2,1f).apply{bottomMargin=dp(6);if(idx==1)leftMargin=dp(6)})};if(pair.size==1)row.addView(View(this),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(6)});grid.addView(row)};content.addView(grid,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(2)})}
internal fun MainActivity.modernSmallMetric(name:String,value:String,icon:String):LinearLayout{val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=bg(panel,Ds.RADIUS_MD,line)};val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};val iv=MdiIconView(this).apply{setIconName(icon);setIconSize(17f);setTextColor(textMain)};top.addView(iv,LinearLayout.LayoutParams(dp(26),dp(26)));top.addView(subLabel(name,11f),LinearLayout.LayoutParams(0,-2,1f));box.addView(top);box.addView(label(value,15f,true));return box}
internal fun MainActivity.setMetricBox(box:LinearLayout,name:String,value:String,sub:String){if(box.childCount>1)(box.getChildAt(1) as? TextView)?.text=value;if(box.childCount>2)(box.getChildAt(2) as? TextView)?.text=sub else box.addView(subLabel(sub,10f))}
internal fun MainActivity.modernInfoCard(titleText:String,valueText:String){val box=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(11),dp(14),dp(11));background=bg(panel,16,line)};val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};tx.addView(label(titleText,12f,true));tx.addView(subLabel(valueText,11f));box.addView(tx,LinearLayout.LayoutParams(0,-2,1f));box.addView(label("›",28f).apply{setTextColor(Color.rgb(80,90,100))},LinearLayout.LayoutParams(dp(26),dp(40)));content.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})}
internal fun MainActivity.modernActionRow(titleText:String,icon:String,onClick:()->Unit){content.addView(modernActionButton(titleText,icon,onClick),LinearLayout.LayoutParams(-1,dp(58)).apply{bottomMargin=dp(8)})}
internal fun MainActivity.modernActionButton(titleText:String,icon:String,onClick:()->Unit):TextView{return TextView(this).apply{text=titleText;textSize=13f;gravity=Gravity.CENTER;setTypeface(typeface,android.graphics.Typeface.BOLD);setTextColor(textMain);background=rippleBg(panel,Ds.RADIUS_MD,line);setOnClickListener{onClick()};isClickable=true;minHeight=dp(52)}.also{Motion.press(it,0.98f)}}
internal fun MainActivity.weightLp(weight:Float,gap:Int)=LinearLayout.LayoutParams(0,-2,weight).apply{bottomMargin=dp(6);if(gap>0)leftMargin=dp(gap)}

internal fun MainActivity.modernBatteryHero():BatteryHero{val card=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(14));background=bg(panel,18,line)};val iv=MdiIconView(this).apply{setIconName("battery-high");setIconSize(46f);setTextColor(Color.rgb(244,67,54));background=bg(Color.rgb(255,245,245),18)};card.addView(iv,LinearLayout.LayoutParams(dp(76),dp(76)).apply{rightMargin=dp(14)});val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val pct=label("--%",28f,true);val st=label("Baterai lemah",12f,true);tx.addView(pct);tx.addView(st);card.addView(tx,LinearLayout.LayoutParams(0,-2,1f));content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)});return BatteryHero(pct,st,iv)}
internal fun MainActivity.modernWifiHero():WifiHero{val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(12));background=bg(panel,18,line)};val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};val iv=MdiIconView(this).apply{setIconName("wifi");setIconSize(34f);setTextColor(accentTeal());background=bg(if(isDarkTheme) Color.rgb(20,48,42) else Color.rgb(238,249,246),22,line)};top.addView(iv,LinearLayout.LayoutParams(dp(66),dp(66)).apply{rightMargin=dp(12)});val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val ssid=label("<unknown ssid>",18f,true);val con=label("●  Terhubung",12f,true);con.setTextColor(accentTeal());tx.addView(ssid);tx.addView(con);top.addView(tx,LinearLayout.LayoutParams(0,-2,1f));val rssi=label("-81 dBm",17f,true);top.addView(rssi);card.addView(top);val metrics=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val a=modernSmallMetric("Link speed","-","speedometer");val b=modernSmallMetric("Frekuensi","-","sine-wave");val c=modernSmallMetric("Channel","-","access-point");metrics.addView(a,weightLp(1f,0));metrics.addView(b,weightLp(1f,6));metrics.addView(c,weightLp(1f,6));card.addView(metrics);content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)});return WifiHero(ssid,con,rssi,iv,a,b,c)}
internal fun MainActivity.setWifiMetric(box:LinearLayout,name:String,value:String,sub:String){if(box.childCount>=2)(box.getChildAt(1) as? TextView)?.text=value;if(box.childCount>=3)(box.getChildAt(2) as? TextView)?.text=sub else box.addView(subLabel(sub,9f))}
internal fun MainActivity.modernChartCard(titleText:String,chart:MiniLineChart){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(8));background=bg(panel,18,line)};box.addView(label(titleText,13f,true));box.addView(chart,LinearLayout.LayoutParams(-1,dp(145)));content.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})}
internal fun MainActivity.modernDeviceHero(){val card=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=bg(panel,18,line)};val iv=MdiIconView(this).apply{setIconName("cellphone-information");setIconSize(30f);setTextColor(textMain);background=bg(panel2,18,line)};card.addView(iv,LinearLayout.LayoutParams(dp(66),dp(66)).apply{rightMargin=dp(12)});val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};tx.addView(label(Build.MODEL,18f,true));tx.addView(subLabel("Android ${Build.VERSION.RELEASE}",11f));tx.addView(subLabel(Build.MANUFACTURER,11f));card.addView(tx);content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})}
internal fun MainActivity.modernProgressCard(name:String,pct:Int,sub:String,value:String){val box=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=bg(panel,16,line)};val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};tx.addView(label(name,14f,true));tx.addView(subLabel(sub,10f));box.addView(tx,LinearLayout.LayoutParams(0,-2,1f));val right=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.RIGHT};right.addView(label("$pct%",12f,true).apply{gravity=Gravity.RIGHT});val track=FrameLayout(this);track.background=bg(Color.rgb(232,237,241),6);val fill=View(this);fill.background=bg(if(pct>=80)Color.rgb(244,67,54) else accentTeal(),6);track.addView(fill,FrameLayout.LayoutParams((dp(105)*pct/100).coerceAtLeast(dp(4)),dp(6)));right.addView(track,LinearLayout.LayoutParams(dp(105),dp(6)));right.addView(subLabel(value,9f));box.addView(right);content.addView(box,LinearLayout.LayoutParams(-1,dp(72)).apply{bottomMargin=dp(7)})}
internal fun MainActivity.cpuLoadPercent():Int{val cores=Runtime.getRuntime().availableProcessors().coerceAtLeast(1);return runCatching{val s=File("/proc/loadavg").readText().trim().split(" ")[0].toFloat();(s/cores*100f).roundToInt().coerceIn(0,100)}.getOrDefault(0)}
internal fun MainActivity.infoLine(k:String,v:String):LinearLayout{val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(5),0,dp(5))};row.addView(subLabel(k,10f),LinearLayout.LayoutParams(0,-2,1f));row.addView(label(v,10f,false));return row}
internal fun MainActivity.wifiChannel(freq:Int):Int=when{freq==2484->14;freq in 2412..2472->((freq-2407)/5);freq in 5170..5895->((freq-5000)/5);freq in 5955..7115->((freq-5950)/5);else->0}

internal fun MainActivity.ipv4MaskFromPrefix(prefix:Int):String {
        if(prefix !in 0..32) return "-"
        val mask = if(prefix == 0) 0L else (0xffffffffL shl (32-prefix)) and 0xffffffffL
        return listOf((mask shr 24) and 255,(mask shr 16) and 255,(mask shr 8) and 255,mask and 255).joinToString(".")
    }
internal fun MainActivity.wifiLinkProperties():android.net.LinkProperties? {
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val n=cm.activeNetwork ?: return null
        val caps=cm.getNetworkCapabilities(n) ?: return null
        return if(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) cm.getLinkProperties(n) else null
    }
internal fun MainActivity.wifiIpText():String {
        val lp=wifiLinkProperties() ?: return "Tidak terhubung"
        return lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: "-"
    }
internal fun MainActivity.requestWifiPermissions(){
        val req=mutableListOf<String>()
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if(req.isNotEmpty()) ActivityCompat.requestPermissions(this,req.toTypedArray(),2001)
    }
internal fun MainActivity.testWifiConnection(){toolThread {val r=runCatching{val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager;val n=cm.activeNetwork;val caps=if(n!=null)cm.getNetworkCapabilities(n)else null;val validated=caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true;val host=InetAddress.getByName("1.1.1.1");val t=System.currentTimeMillis();val ok=host.isReachable(1800);val ms=System.currentTimeMillis()-t;"Latency: ${ms} ms\nInternet: ${if(ok||validated)"Terhubung" else "Tidak terhubung"}"}.getOrElse{"Test gagal: ${it.message}"};runOnToolUiThread{if(currentPage=="Wi-Fi Info")modernInfoCard("Hasil Test Koneksi",r)}}}
@android.annotation.SuppressLint("MissingPermission")
internal fun MainActivity.scanNearbyWifi(){
        if(Build.VERSION.SDK_INT>=23&&ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){toast("Izinkan lokasi untuk scan Wi-Fi");return}
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED){toast("Izinkan Nearby Wi-Fi untuk scan jaringan");return}
        clearPage("Wi-Fi Scan")
        toolWorkspace("Wi-Fi Scan", "Pindai jaringan Wi-Fi di sekitar (izin lokasi diperlukan).", "wifi")
        val status=label("Memindai Wi-Fi…",15f,true)
        content.addView(status,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val wm=applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val started=runCatching{@Suppress("DEPRECATION") wm.startScan()}.getOrDefault(false)
        modernUiHandler.postDelayed({
            if(currentPage!="Wi-Fi Scan") return@postDelayed
            val results=runCatching{@Suppress("DEPRECATION") wm.scanResults}.getOrDefault(emptyList())
            content.removeAllViews()
            if(!started){
                modernInfoCard("Scan tidak dimulai","Android menolak atau membatasi pemindaian. Coba lagi beberapa saat.")
            } else if(results.isEmpty()){
                modernInfoCard("Tidak ada hasil","Scan bisa dibatasi Android/perangkat atau belum mendapat hasil baru.")
            } else {
                results.sortedByDescending{it.level}.distinctBy{it.BSSID}.take(20).forEach{
                    val ssid=it.SSID.ifBlank{"<hidden>"}
                    modernInfoCard(ssid,"${it.level} dBm • ${it.frequency} MHz • channel ${wifiChannel(it.frequency)}")
                }
            }
        },1200L)
    }
internal fun MainActivity.infoRow(name: String, value: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = bg(panel2, 14)
        }
        card.addView(label(name, 12f, true))
        card.addView(label(value.ifBlank { "Tidak tersedia" }, 14f))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }
internal fun MainActivity.bytesText(v: Long): String {
        if (v < 1024) return "$v B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var n = v.toDouble()
        var i = -1
        while (n >= 1024 && i < units.lastIndex) { n /= 1024.0; i++ }
        return String.format(Locale.getDefault(), "%.2f %s", n, units[i])
    }
internal fun MainActivity.deviceSystemCenterTool() {
        clearPage("Device & System")
        val dm = resources.displayMetrics
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        val ramPct = ((mem.totalMem - mem.availMem).toDouble() / mem.totalMem * 100).roundToInt().coerceIn(0, 100)
        val storagePct = ((stat.totalBytes - stat.availableBytes).toDouble() / stat.totalBytes * 100).roundToInt().coerceIn(0, 100)

        content.addView(label("Device & System", 22f, true))
        content.addView(subLabel("Dashboard ringkas — ketuk tautan di bawah untuk detail.", 12f))
        modernDeviceHero()
        modernMetricGrid(listOf(
            "Model" to Build.MODEL.ifBlank { "Tidak tersedia" },
            "RAM" to String.format(Locale.getDefault(), "%.1f GB", mem.totalMem / 1024.0 / 1024.0 / 1024.0),
            "Hardware" to Build.HARDWARE.ifBlank { Build.BOARD.ifBlank { "Tidak tersedia" } },
            "Layar" to "${dm.widthPixels} × ${dm.heightPixels}\n${String.format(Locale.getDefault(), "%.2f", dm.density)}x",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "ABI" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "Tidak tersedia")
        ))

        // Tautan ke halaman detail
        modernToolListRow("Device Info", "Spesifikasi lengkap perangkat & OS", "cellphone-information") { deviceInfoTool() }
        modernToolListRow("Battery Info", "Detail level, suhu, dan status pengisian", "battery") { batteryInfoTool() }

        toolWorkspaceSection("SYSTEM STATUS", "Ringkasan penggunaan perangkat saat ini.")
        modernProgressCard("CPU", cpuLoadPercent(), "Perkiraan penggunaan CPU saat ini (loadavg)", "${Runtime.getRuntime().availableProcessors()} core")
        modernProgressCard("RAM", ramPct, "Penggunaan memori", "${bytesText(mem.totalMem - mem.availMem)} / ${bytesText(mem.totalMem)}")
        modernProgressCard("Storage", storagePct, "Penyimpanan internal", "${bytesText(stat.totalBytes - stat.availableBytes)} / ${bytesText(stat.totalBytes)}")
        val processes = am.runningAppProcesses?.size ?: 0
        modernProgressCard("Proses aplikasi yang terdeteksi", processes.coerceAtMost(100), "Jumlah proses aplikasi yang terlihat", "$processes aktif")
        modernInfoCard("Build", Build.DISPLAY.ifBlank { "Tidak tersedia" })
        modernInfoCard("Kernel", System.getProperty("os.version") ?: "Tidak tersedia")

        toolWorkspaceSection("BATTERY", "Status baterai — riwayat hanya selama halaman dibuka.")
        modernUiRunnable?.let { modernUiHandler.removeCallbacks(it) }
        batteryHistory.clear()
        val hero = modernBatteryHero()
        val chart = MiniLineChart(this).apply {
            lineColor = Color.rgb(244, 67, 54)
            fillColor = Color.argb(48, 244, 67, 54)
            minValue = 0f
            maxValue = 100f
        }
        modernChartCard("Riwayat selama halaman dibuka", chart)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val statusBox = modernSmallMetric("Status", "-", "battery-charging")
        val levelBox = modernSmallMetric("Level baterai", "-", "battery")
        val tempBox = modernSmallMetric("Suhu baterai", "-", "thermometer")
        val voltBox = modernSmallMetric("Tegangan", "-", "flash")
        r1.addView(statusBox, weightLp(1f, 0)); r1.addView(levelBox, weightLp(1f, 6))
        r2.addView(tempBox, weightLp(1f, 0)); r2.addView(voltBox, weightLp(1f, 6))
        grid.addView(r1); grid.addView(r2)
        content.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        fun updateBattery() {
            if (currentPage != "Device & System") return
            val i = batteryStatusIntent() ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = (level * 100 / scale).coerceIn(0, 100)
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            val stText = when (st) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Mengisi"
                BatteryManager.BATTERY_STATUS_FULL -> "Penuh"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Menggunakan baterai"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Tidak mengisi"
                else -> "Status tidak diketahui"
            }
            batteryHistory.add(pct); if (batteryHistory.size > 40) batteryHistory.removeAt(0)
            chart.values = batteryHistory.map { it.toFloat() }; chart.invalidate()
            hero.percent.text = "$pct%"
            hero.status.text = if (pct <= 20) "Baterai lemah" else if (charging) "Sedang mengisi" else "Normal"
            hero.icon.setIconName(if (pct <= 20) "battery-high" else if (charging) "battery-charging" else "battery-high")
            hero.icon.setTextColor(if (pct <= 20) Color.rgb(244, 67, 54) else accentTeal())
            hero.status.setTextColor(if (pct <= 20) Color.rgb(211, 47, 47) else accentTeal())
            setMetricBox(statusBox, "Status", stText, if (charging) "Mengisi daya" else "Menggunakan baterai")
            setMetricBox(levelBox, "Level baterai", "$pct%", if (pct <= 20) "Rendah" else "Normal")
            setMetricBox(tempBox, "Suhu baterai", String.format(Locale.getDefault(), "%.1f°C", temp), if (temp > 40) "Tinggi" else "Normal")
            setMetricBox(voltBox, "Tegangan", if (voltage > 0) "${voltage} mV" else "Tidak tersedia", if (voltage > 0) "Normal" else "—")
        }

        modernActionRow("Perbarui sekarang", "refresh") {
            updateBattery()
            toast("Diperbarui")
        }

        val r = object : Runnable {
            override fun run() {
                if (currentPage != "Device & System") return
                updateBattery()
                modernUiHandler.postDelayed(this, 5000)
            }
        }
        modernUiRunnable = r
        r.run()
    }
internal fun MainActivity.deviceInfoTool() {
        clearPage("Device Info")
        toolWorkspace("Device Info", "Spesifikasi lengkap perangkat, OS, hardware, dan layar.", "cellphone")
        val dm = resources.displayMetrics
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        modernDeviceHero()

        toolWorkspaceSection("PERANGKAT", "Identitas model dan manufaktur.")
        modernInfoCard("Model", Build.MODEL.ifBlank { "Tidak tersedia" })
        modernInfoCard("Manufaktur", Build.MANUFACTURER.ifBlank { "Tidak tersedia" })
        modernInfoCard("Perangkat (device)", Build.DEVICE.ifBlank { "Tidak tersedia" })
        modernInfoCard("Produk", Build.PRODUCT.ifBlank { "Tidak tersedia" })
        modernInfoCard("Brand", Build.BRAND.ifBlank { "Tidak tersedia" })

        toolWorkspaceSection("ANDROID", "Versi sistem operasi dan patch keamanan.")
        modernInfoCard("Versi Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        modernInfoCard("Codename", Build.VERSION.CODENAME.ifBlank { "Tidak tersedia" })
        val securityPatch = if (Build.VERSION.SDK_INT >= 23) {
            Build.VERSION.SECURITY_PATCH.ifBlank { "Tidak tersedia" }
        } else "Tidak tersedia"
        modernInfoCard("Patch keamanan", securityPatch)
        modernInfoCard("Build ID", Build.ID.ifBlank { "Tidak tersedia" })
        modernInfoCard("Build display", Build.DISPLAY.ifBlank { "Tidak tersedia" })
        modernInfoCard("Fingerprint", Build.FINGERPRINT.ifBlank { "Tidak tersedia" })

        toolWorkspaceSection("HARDWARE", "Identitas board/hardware vs model CPU yang terdeteksi.")
        modernInfoCard("Hardware (board)", Build.HARDWARE.ifBlank { "Tidak tersedia" })
        modernInfoCard("Board", Build.BOARD.ifBlank { "Tidak tersedia" })
        modernInfoCard("CPU ABI", Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "Tidak tersedia" })
        modernInfoCard("Jumlah core", "${Runtime.getRuntime().availableProcessors()}")
        modernInfoCard("RAM total", String.format(Locale.getDefault(), "%.2f GB", mem.totalMem / 1024.0 / 1024.0 / 1024.0))
        modernInfoCard("Kernel", System.getProperty("os.version") ?: "Tidak tersedia")
        val cpuModel = runCatching {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Hardware") || it.startsWith("model name") }
                ?.substringAfter(":")?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "Tidak tersedia"
        modernInfoCard("Model CPU terdeteksi", cpuModel)

        toolWorkspaceSection("LAYAR", "Resolusi, densitas, dan metrik tampilan.")
        modernInfoCard("Resolusi", "${dm.widthPixels} × ${dm.heightPixels} px")
        modernInfoCard("Densitas", String.format(Locale.getDefault(), "%.2f x  (%d dpi)", dm.density, dm.densityDpi))
        modernInfoCard("Lebar (dp)", String.format(Locale.getDefault(), "%.0f dp", dm.widthPixels / dm.density))
        modernInfoCard("Tinggi (dp)", String.format(Locale.getDefault(), "%.0f dp", dm.heightPixels / dm.density))
        modernInfoCard("Orientasi", if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "Landscape" else "Portrait")

        val report = buildString {
            appendLine("=== Laporan Spesifikasi Perangkat ===")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Manufaktur: ${Build.MANUFACTURER}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Patch keamanan: $securityPatch")
            appendLine("Build: ${Build.DISPLAY}")
            appendLine("Hardware: ${Build.HARDWARE}")
            appendLine("Board: ${Build.BOARD}")
            appendLine("CPU model: $cpuModel")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Core: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("RAM: ${String.format(Locale.getDefault(), "%.2f GB", mem.totalMem / 1024.0 / 1024.0 / 1024.0)}")
            appendLine("Layar: ${dm.widthPixels}×${dm.heightPixels} @ ${dm.densityDpi} dpi")
            appendLine("Kernel: ${System.getProperty("os.version") ?: "-"}")
        }
        modernActionRow("Salin informasi perangkat", "content-copy") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Device Info", report))
            toast("Disalin ke clipboard")
        }
        modernActionRow("Bagikan laporan spesifikasi", "share-variant") {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Spesifikasi ${Build.MODEL}")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            runCatching { startActivity(Intent.createChooser(intent, "Bagikan spesifikasi")) }
                .onFailure { toast("Tidak bisa membuka share") }
        }
    }
internal fun MainActivity.batteryInfoTool() {
        clearPage("Battery Info")
        toolWorkspace("Battery Info", "Status baterai, suhu, dan pengisian (bukan kesehatan jangka panjang).", "battery")
        modernUiRunnable?.let { modernUiHandler.removeCallbacks(it) }
        batteryHistory.clear()
        val hero = modernBatteryHero()
        val chart = MiniLineChart(this).apply {
            lineColor = Color.rgb(244, 67, 54)
            fillColor = Color.argb(48, 244, 67, 54)
            minValue = 0f
            maxValue = 100f
        }
        modernChartCard("Riwayat sesi ini (bukan 24 jam)", chart)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val statusBox = modernSmallMetric("Status", "-", "battery-charging")
        val levelBox = modernSmallMetric("Level baterai", "-", "battery")
        val tempBox = modernSmallMetric("Suhu baterai", "-", "thermometer")
        val voltBox = modernSmallMetric("Tegangan", "-", "flash")
        r1.addView(statusBox, weightLp(1f, 0)); r1.addView(levelBox, weightLp(1f, 6))
        r2.addView(tempBox, weightLp(1f, 0)); r2.addView(voltBox, weightLp(1f, 6))
        grid.addView(r1); grid.addView(r2)
        content.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val healthBox = modernSmallMetric("Kesehatan baterai", "Tidak tersedia", "heart-pulse")
        content.addView(healthBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        modernInfoCard("Catatan suhu", "Nilai suhu di atas adalah suhu baterai (bukan suhu CPU).")
        modernInfoCard("Tips hemat baterai", "Kurangi kecerahan layar dan tutup aplikasi yang tidak digunakan.")

        fun update() {
            if (currentPage != "Battery Info") return
            val i = batteryStatusIntent() ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = (level * 100 / scale).coerceIn(0, 100)
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            val stText = when (st) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Mengisi"
                BatteryManager.BATTERY_STATUS_FULL -> "Penuh"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Menggunakan baterai"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Tidak mengisi"
                else -> "Status tidak diketahui"
            }
            val healthText = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Baik"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Rusak"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Dingin"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Gagal"
                else -> "Tidak tersedia"
            }
            batteryHistory.add(pct)
            if (batteryHistory.size > 40) batteryHistory.removeAt(0)
            chart.values = batteryHistory.map { it.toFloat() }
            chart.invalidate()
            hero.percent.text = "$pct%"
            hero.status.text = if (pct <= 20) "Baterai lemah" else if (charging) "Sedang mengisi" else "Normal"
            hero.icon.setIconName(if (pct <= 20) "battery-high" else if (charging) "battery-charging" else "battery-high")
            hero.icon.setTextColor(if (pct <= 20) Color.rgb(244, 67, 54) else accentTeal())
            hero.status.setTextColor(if (pct <= 20) Color.rgb(211, 47, 47) else accentTeal())
            setMetricBox(statusBox, "Status", stText, if (charging) "Mengisi daya" else "Menggunakan baterai")
            setMetricBox(levelBox, "Level baterai", "$pct%", if (pct <= 20) "Rendah" else "Normal")
            setMetricBox(tempBox, "Suhu baterai", String.format(Locale.getDefault(), "%.1f°C", temp), if (temp > 40) "Tinggi" else "Normal")
            setMetricBox(voltBox, "Tegangan", if (voltage > 0) "${voltage} mV" else "Tidak tersedia", if (voltage > 0) "Normal" else "—")
            setMetricBox(healthBox, "Kesehatan baterai", healthText, if (healthText == "Tidak tersedia") "Tidak diukur andal" else "Dari sistem")
        }

        modernActionRow("Perbarui sekarang", "refresh") {
            update()
            toast("Diperbarui")
        }

        val r = object : Runnable {
            override fun run() {
                if (currentPage != "Battery Info") return
                update()
                modernUiHandler.postDelayed(this, 5000)
            }
        }
        modernUiRunnable = r
        r.run()
    }

@android.annotation.SuppressLint("MissingPermission")
internal fun MainActivity.wifiInfo() {
    clearPage("Wi-Fi Info")
        toolWorkspace("Wi-Fi Info", "Detail koneksi Wi-Fi aktif.", "wifi")
    requestWifiPermissions()
    wifiHistory.clear()
    val hero = modernWifiHero()
    val chart = MiniLineChart(this).apply {
        lineColor = Color.rgb(0, 150, 136); fillColor = Color.argb(48, 0, 150, 136)
        minValue = -100f; maxValue = -30f
    }
    modernChartCard("Kekuatan sinyal (dBm)", chart)
    val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val ipBox = modernSmallMetric("IP lokal", "-", "information-outline")
    val maskBox = modernSmallMetric("Subnet mask", "-", "information-outline")
    val gwBox = modernSmallMetric("Gateway", "-", "information-outline")
    val dnsBox = modernSmallMetric("DNS", "-", "information-outline")
    row1.addView(ipBox, weightLp(1f, 0)); row1.addView(maskBox, weightLp(1f, 6))
    row2.addView(gwBox, weightLp(1f, 0)); row2.addView(dnsBox, weightLp(1f, 6))
    content.addView(row1, LinearLayout.LayoutParams(-1, -2))
    content.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    modernActionRow("Scan jaringan sekitar", "wifi") { scanNearbyWifi() }
    modernActionRow("Tes koneksi internet", "speedometer") { testWifiConnection() }
    modernActionRow("Buka pengaturan Wi-Fi", "cog-outline") {
        runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .onFailure { toast("Pengaturan Wi-Fi tidak bisa dibuka") }
    }

    fun showDisconnected() {
        hero.ssid.text = "Tidak terhubung ke Wi-Fi"
        hero.status.text = "●  Tidak terhubung"
        hero.status.setTextColor(Color.rgb(211, 47, 47))
        hero.rssi.text = "-"
        setWifiMetric(hero.linkBox, "Link speed", "-", "Mbps")
        setWifiMetric(hero.freqBox, "Frekuensi", "-", "MHz")
        setWifiMetric(hero.channelBox, "Channel", "-", "-")
        setWifiMetric(ipBox, "IP lokal", "-", "IPv4")
        setWifiMetric(maskBox, "Subnet mask", "-", "IPv4")
        setWifiMetric(gwBox, "Gateway", "-", "Default route")
        setWifiMetric(dnsBox, "DNS", "-", "Server DNS")
    }

    fun update() {
        if (currentPage != "Wi-Fi Info") return
        val lp = wifiLinkProperties()
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val info = runCatching { wm?.connectionInfo }.getOrNull()
        if (lp == null || info == null) { showDisconnected(); return }

        val rawSsid = (info.ssid ?: "").trim('"')
        val ssidKnown = rawSsid.isNotBlank() && rawSsid != "<unknown ssid>"
        hero.ssid.text = if (ssidKnown) rawSsid else "SSID tidak tersedia"
        hero.status.text = if (ssidKnown) "●  Terhubung" else "●  Terhubung (izinkan lokasi untuk melihat nama jaringan)"
        hero.status.setTextColor(accentTeal())

        val rssi = info.rssi
        if (rssi in -126..0) {
            hero.rssi.text = "$rssi dBm"
            wifiHistory.add(rssi)
            while (wifiHistory.size > 40) wifiHistory.removeAt(0)
            chart.values = wifiHistory.map { it.toFloat() }
            chart.invalidate()
        } else {
            hero.rssi.text = "-"
        }
        val speed = info.linkSpeed
        val freq = info.frequency
        val band = when {
            freq in 2400..2500 -> "Pita 2,4 GHz"
            freq in 4900..5900 -> "Pita 5 GHz"
            freq in 5925..7125 -> "Pita 6 GHz"
            else -> "MHz"
        }
        setWifiMetric(hero.linkBox, "Link speed", if (speed > 0) "$speed Mbps" else "-", "Kecepatan tautan")
        setWifiMetric(hero.freqBox, "Frekuensi", if (freq > 0) "$freq MHz" else "-", band)
        val ch = if (freq > 0) wifiChannel(freq) else 0
        setWifiMetric(hero.channelBox, "Channel", if (ch > 0) "$ch" else "-", "Saluran Wi-Fi")

        val addr = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress ?: "-"
        val dns = lp.dnsServers.mapNotNull { it.hostAddress }.joinToString(", ").ifBlank { "-" }
        setWifiMetric(ipBox, "IP lokal", addr?.address?.hostAddress ?: "-", "IPv4")
        setWifiMetric(maskBox, "Subnet mask", if (addr != null) ipv4MaskFromPrefix(addr.prefixLength) else "-", "IPv4")
        setWifiMetric(gwBox, "Gateway", gateway, "Default route")
        setWifiMetric(dnsBox, "DNS", dns, "Server DNS")
    }

    val r = object : Runnable {
        override fun run() {
            if (currentPage != "Wi-Fi Info") return
            update()
            modernUiHandler.postDelayed(this, 3000)
        }
    }
    modernUiRunnable = r
    r.run()
}
