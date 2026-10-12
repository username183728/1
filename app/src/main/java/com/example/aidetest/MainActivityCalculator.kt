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


internal fun MainActivity.calcDisplay(hint: String = "0"): EditText = EditText(this).apply {
        // Area display ala kalkulator sistem: angka besar, kanan-bawah, tanpa kotak tebal.
        setTextColor(textMain)
        setHintTextColor(textMuted)
        textSize = 44f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.BOTTOM or Gravity.END
        setSingleLine(true)
        setPadding(dp(20), dp(16), dp(20), dp(12))
        background = null
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply {
            bottomMargin = dp(8)
        }
    }

internal fun MainActivity.calcButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = when {
            text.length <= 1 -> 22f
            text.length <= 2 -> 18f
            else -> 15f
        }
        setTextColor(textMain)
        // Tombol lebih “padat” seperti keypad sistem: radius sedang, tinggi seragam.
        background = bg(panel2, 18)
        setOnClickListener { onClick() }
        setStateListAnimator(null)
        isAllCaps = false
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = dp(64)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }
    }

internal fun MainActivity.calculatorTool(scientific: Boolean) {
        // Saat ditampilkan di dalam hub, toolbar + selector sudah menjadi konteks
        // kalkulator. Jangan ulangi judul/deskripsi di area isi.
        if (!embeddedCalculatorRender) {
            clearPage(if (scientific) "Kalkulator Ilmiah" else "Kalkulator Dasar")
            val calcTitle = if (scientific) "Kalkulator Ilmiah" else "Kalkulator Dasar"
            content.setPadding(dp(8), dp(4), dp(8), dp(10))
            addToolHeader(calcTitle, if (scientific) "Perhitungan ilmiah dengan keypad responsif." else "Perhitungan cepat dengan keypad yang nyaman di layar sentuh.", "calculator")
            content.setPadding(0, 0, 0, dp(10))
        } else {
            content.removeAllViews()
            content.setPadding(0, dp(2), 0, dp(14))
        }

        // Spacer fleksibel + display di zona atas (angka menempel ke keypad),
        // keypad menempel ke bawah — pola layout kalkulator sistem.
        val display = calcDisplay()
        display.textSize = if (scientific) 32f else 46f
        display.setPadding(dp(18), dp(8), dp(18), dp(14))
        content.addView(display, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })

        val grid = GridLayout(this).apply {
            columnCount = if (scientific) 5 else 4
            useDefaultMargins = false
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }

        // Basic mode follows the reference calculator layout:
        // AC, +/-, %, ÷
        // 7, 8, 9, ×
        // 4, 5, 6, −
        // 1, 2, 3, =
        // 0, ., DEL, C
        //
        // Most importantly, "=" is a real button and is wired to evaluateExpression().
        val keys = if (scientific) {
            listOf(
                "sin","cos","tan","log","ln",
                "√","x²","xʸ","(",")",
                "7","8","9","÷","DEL",
                "4","5","6","×","C",
                "1","2","3","−","=",
                "0",".","%","+","π"
            )
        } else {
            // Layout mirip kalkulator sistem: operator kanan, = menonjol.
            listOf(
                "AC","DEL","%","÷",
                "7","8","9","×",
                "4","5","6","−",
                "1","2","3","+",
                "±","0",".","="
            )
        }

        keys.forEach { key ->
            val keyButton = calcButton(key) {
                when (key) {
                    "C", "AC" -> display.setText("")

                    "DEL" -> {
                        if (display.text.isNotEmpty()) {
                            display.setText(display.text.dropLast(1))
                            display.setSelection(display.text.length)
                        }
                    }

                    "±" -> {
                        val current = display.text.toString()
                        if (current.isBlank()) {
                            display.setText("-")
                        } else if (current.startsWith("-")) {
                            display.setText(current.substring(1))
                        } else {
                            display.setText("-$current")
                        }
                        display.setSelection(display.text.length)
                    }

                    "=" -> {
                        val result = runCatching {
                            evaluateExpression(display.text.toString(), scientific)
                        }.getOrElse {
                            "Error: ${it.message ?: "input"}"
                        }
                        display.setText(result)
                        display.setSelection(display.text.length)
                    }

                    "sin","cos","tan","log","ln","x²" -> display.append(key + "(")
                    "√" -> display.append("sqrt(")
                    "xʸ" -> display.append("^")
                    "×" -> display.append("*")
                    "÷" -> display.append("/")
                    "−" -> display.append("-")
                    "π" -> display.append("pi")
                    else -> display.append(key)
                }
            }

            // Hierarki visual B/W: angka netral, operator gelap, = primer, AC/DEL soft.
            when {
                !scientific && key == "=" -> {
                    keyButton.setTextColor(if (isDarkTheme) Color.BLACK else Color.WHITE)
                    keyButton.background = bg(if (isDarkTheme) Color.WHITE else Color.rgb(20, 20, 22), 18)
                    keyButton.textSize = 24f
                }
                !scientific && key in setOf("÷", "×", "−", "+", "%") -> {
                    keyButton.setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(28, 28, 30))
                    keyButton.background = bg(
                        if (isDarkTheme) Color.rgb(55, 55, 60) else Color.rgb(228, 228, 232),
                        18
                    )
                    keyButton.textSize = 22f
                }
                !scientific && key in setOf("AC", "DEL", "±", "C") -> {
                    keyButton.setTextColor(textMuted)
                    keyButton.background = bg(
                        if (isDarkTheme) Color.rgb(40, 40, 44) else Color.rgb(240, 240, 243),
                        18
                    )
                }
                scientific && key in setOf("÷", "×", "−", "+", "=", "%") -> {
                    keyButton.setTextColor(if (isDarkTheme) Color.WHITE else textMain)
                    keyButton.background = bg(
                        if (isDarkTheme) Color.rgb(55, 55, 60) else Color.rgb(230, 230, 234),
                        16
                    )
                }
                else -> {
                    keyButton.setTextColor(textMain)
                    keyButton.background = bg(panel2, 18)
                }
            }
            // Map + for expression builder
            if (key == "+") {
                // already handled in else branch as display.append — need explicit?
            }
            grid.addView(keyButton)
        }

        content.addView(grid, LinearLayout.LayoutParams(-1, -2).apply {
            // Keypad fixed di bawah; display mengisi sisa ruang di atas.
        })

        if (scientific) {
            content.addView(
                subLabel(
                    "Mendukung + − × ÷ %, kurung, pangkat, √, sin, cos, tan, log, ln, π.",
                    11f
                ).apply {
                    setPadding(dp(14), dp(6), dp(14), 0)
                }
            )
        }
    }
internal fun MainActivity.evaluateExpression(expr: String, scientific: Boolean): String {
        if (expr.isBlank()) return "0"
        val v = ExprParser(expr, scientific).parse()
        if (!v.isFinite()) error("Hasil tidak valid")
        return if (kotlin.math.abs(v - v.toLong()) < 1e-10) v.toLong().toString() else String.format(Locale.US, "%.10f", v).trimEnd('0').trimEnd('.')
    }
internal fun MainActivity.twoFields(titleText: String, aHint: String, bHint: String, actionText: String, calc: (Double,Double)->String) {
        clearPage(titleText)
        content.addView(label(titleText,22f,true))
        val a=edit(aHint); val b=edit(bHint); content.addView(a); content.addView(b)
        pinToolAction(button(actionText) {
            val x=runCatching{a.text.toString().replace(",",".").toDouble()}.getOrNull()
            val y=runCatching{b.text.toString().replace(",",".").toDouble()}.getOrNull()
            output(if(x==null||y==null) "Masukkan angka yang valid." else calc(x,y))
        })
    }
internal fun MainActivity.percentCalculator() {
        clearPage("Persentase")
        content.addView(label("Kalkulator Persentase",22f,true))
        val a=edit("Nilai"); val p=edit("Persen (%)"); content.addView(a); content.addView(p)
        content.addView(button("Hitung X% dari nilai") { val x=a.num(); val y=p.num(); output(if(x==null||y==null) "Input tidak valid" else "${fmt(x*y/100)}") })
        content.addView(button("Berapa % X dari Y") { val x=a.num(); val y=p.num(); output(if(x==null||y==null||y==0.0) "Input tidak valid" else "${fmt(x/y*100)}%") })
        content.addView(button("Tambah X% ke nilai") { val x=a.num(); val y=p.num(); output(if(x==null||y==null) "Input tidak valid" else "${fmt(x*(1+y/100))}") })
        content.addView(button("Kurangi X% dari nilai") { val x=a.num(); val y=p.num(); output(if(x==null||y==null) "Input tidak valid" else "${fmt(x*(1-y/100))}") })
    }
internal fun MainActivity.fractionCalculator() {
        clearPage("Pecahan")
        content.addView(label("Operasi Pecahan",22f,true))
        val a=edit("Pecahan A, contoh 3/4"); val b=edit("Pecahan B, contoh 1/2"); content.addView(a); content.addView(b)
        listOf("+","−","×","÷").forEach { op -> content.addView(button("A $op B") { output(fractionOp(a.text.toString(), b.text.toString(), op)) }) }
    }
internal fun MainActivity.fractionOp(a:String,b:String,op:String):String { return runCatching { val x=frac(a); val y=frac(b); val n=when(op){"+"->x.first*y.second+y.first*x.second;"−"->x.first*y.second-y.first*x.second;"×"->x.first*y.first;else->x.first*y.second}; val d=when(op){"+","−"->x.second*y.second;"×"->x.second*y.second;else->{if(y.first==0L) error("Pembagi 0");x.second*y.first}}; val g=gcd(kotlin.math.abs(n),kotlin.math.abs(d)); "${n/g}/${d/g} = ${fmt(n.toDouble()/d)}" }.getOrElse{"Format harus seperti 3/4"} }
internal fun MainActivity.frac(s:String):Pair<Long,Long>{ val p=s.trim().split("/"); if(p.size!=2) error("pecahan"); val n=p[0].trim().toLong(); val d=p[1].trim().toLong(); if(d==0L) error("0"); return if(d<0) -n to -d else n to d }
internal fun MainActivity.gcd(a0:Long,b0:Long):Long { var a=a0; var b=b0; while(b!=0L){val t=a%b;a=b;b=t};return if(a==0L)1L else a }
internal fun MainActivity.ratioCalculator() { twoFields("Rasio & Proporsi","A","B","Sederhanakan rasio") { a,b -> val scale=1000000.0; val ai=kotlin.math.round(a*scale).toLong(); val bi=kotlin.math.round(b*scale).toLong(); val g=gcd(kotlin.math.abs(ai),kotlin.math.abs(bi)); "${ai/g} : ${bi/g}" } }
internal fun MainActivity.unitCalculator() {
        if (!embeddedCalculatorRender) {
            clearPage("Konverter Satuan")
            content.addView(label("Konverter Satuan", 18f, true))
        }
        // Grid kategori mirip konverter sistem (ikon + label), tetap B/W.
        val cats = listOf(
            Triple("Panjang", "ruler", "unitcalc"),
            Triple("Massa", "weight", "unitcalc"),
            Triple("Suhu", "thermometer", "unitcalc"),
            Triple("Area", "vector-square", "areacalc"),
            Triple("Waktu", "clock-outline", "timecalc"),
            Triple("Data", "database", "datacalc"),
            Triple("Diskon", "tag-outline", "discountcalc"),
            Triple("Volume", "cube-outline", "volumecalc"),
            Triple("Sistem angka", "numeric", "basecalc"),
            Triple("Kecepatan", "speedometer", "speedcalc"),
            Triple("BMI", "human", "aspectcalc"),
            Triple("Tekanan", "gauge", "pressurecalc")
        )
        val grid = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        cats.forEach { (name, icon, modeId) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(16), dp(8), dp(16))
                background = bg(panel2, 16)
                isClickable = true
                setOnClickListener {
                    if (modeId != "unitcalc") calculatorHub(modeId)
                    else {
                        // Fokus form konversi umum di bawah grid
                        toast("Isi nilai di form konversi")
                    }
                }
            }
            cell.addView(MdiIconView(this).apply {
                setIconName(icon)
                setIconSize(26f)
                setTextColor(textMain)
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { bottomMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
            cell.addView(TextView(this).apply {
                text = name
                textSize = 12f
                setTextColor(textMuted)
                gravity = Gravity.CENTER
            })
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                height = -2
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            })
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        content.addView(label("Konversi cepat", 14f, true).apply {
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })
        val input = edit("Nilai")
        content.addView(input)
        val from = Spinner(this); val to = Spinner(this)
        val units = arrayOf(
            "meter","kilometer","centimeter","milimeter","inch","feet","yard","mile",
            "gram","kilogram","pound",
            "celsius","fahrenheit","kelvin","reamur",
            "mps","kmh","mph",
            "pascal","kpa","bar","psi"
        )
        listOf(from, to).forEach {
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
            content.addView(it, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        }
        val go = button("Konversi") {
            val v = input.num()
            output(
                if (v == null) "Input tidak valid"
                else "${fmt(convertUnit(v, from.selectedItem.toString(), to.selectedItem.toString()))} ${to.selectedItem}"
            )
        }
        content.addView(go)
    }
internal fun MainActivity.convertUnit(v:Double,from:String,to:String):Double {
        val temps=setOf("celsius","fahrenheit","kelvin","reamur")
        if(from in temps || to in temps){
            val c=when(from){"celsius"->v;"fahrenheit"->(v-32)*5/9;"kelvin"->v-273.15;"reamur"->v*5/4;else->v}
            return when(to){"celsius"->c;"fahrenheit"->c*9/5+32;"kelvin"->c+273.15;"reamur"->c*4/5;else->error("Temperatur") }
        }
        val speedBase=mapOf("mps" to 1.0,"kmh" to 1.0/3.6,"mph" to 0.44704)
        if(from in speedBase || to in speedBase){ return v*speedBase.getValue(from)/speedBase.getValue(to) }
        val pressureBase=mapOf("pascal" to 1.0,"kpa" to 1000.0,"bar" to 100000.0,"psi" to 6894.757293)
        if(from in pressureBase || to in pressureBase){ return v*pressureBase.getValue(from)/pressureBase.getValue(to) }
        val factors=mapOf("meter" to 1.0,"kilometer" to 1000.0,"centimeter" to .01,"milimeter" to .001,"inch" to .0254,"feet" to .3048,"yard" to .9144,"mile" to 1609.344,"gram" to .001,"kilogram" to 1.0,"pound" to .45359237)
        if(from !in factors || to !in factors) error("Satuan tidak sejenis")
        return v*factors.getValue(from)/factors.getValue(to)
    }
internal fun MainActivity.riskRewardCalculator() {
        clearPage("Risk-Reward & Position Sizing")
        content.addView(label("Risk-Reward & Position Sizing",22f,true))
        val capital=edit("Total modal"); val risk=edit("Risiko (%) contoh 1-2"); val entry=edit("Harga entry"); val stop=edit("Stop Loss"); val target=edit("Target harga (opsional)"); val lot=edit("Ukuran 1 lot (opsional, default 1)")
        listOf(capital,risk,entry,stop,target,lot).forEach{content.addView(it)}
        pinToolAction(button("Hitung posisi") {
            val c=capital.num();val r=risk.num();val e=entry.num();val sl=stop.num();val t=target.num();val ls=lot.num()?:1.0
            if(c==null||r==null||e==null||sl==null||r<=0||e==sl||ls<=0) output("Input tidak valid.") else {
                val riskMoney=c*r/100; val riskUnit=kotlin.math.abs(e-sl); val qty=riskMoney/riskUnit; val lots=qty/ls
                val rr=if(t==null) null else kotlin.math.abs(t-e)/riskUnit
                output("Modal risiko: ${fmt(riskMoney)}\nRisiko/unit: ${fmt(riskUnit)}\nUkuran posisi: ${fmt(qty)} unit\nLot: ${fmt(lots)}${if(rr!=null) "\nRisk-Reward: 1 : ${fmt(rr)}" else ""}")
            }
        })
    }
internal fun MainActivity.compoundCalculator() {
        clearPage("Compound & Target Tabungan")
        content.addView(label("Compound Interest & Target Tabungan",22f,true))
        val initial=edit("Modal awal");val contribution=edit("Setoran berkala");val rate=edit("Bunga/return tahunan (%)");val periods=edit("Jumlah periode (bulan)")
        listOf(initial,contribution,rate,periods).forEach{content.addView(it)}
        val freq=segmentedChoice(listOf("Bulanan","Mingguan"));content.addView(freq)
        content.addView(button("Proyeksikan") {
            val p=initial.num();val add=contribution.num();val annual=rate.num();val months=periods.num()
            if(p==null||add==null||annual==null||months==null||months<0) output("Input tidak valid.") else {
                val n=if(freq.selectedItemPosition==0) months.toInt() else kotlin.math.round(months*52.0/12.0).toInt(); val ratePer=if(freq.selectedItemPosition==0) annual/100/12 else annual/100/52
                val fv=if(ratePer==0.0) p+add*n else p*Math.pow(1.0+ratePer,n.toDouble())+add*((Math.pow(1.0+ratePer,n.toDouble())-1.0)/ratePer)
                output("Periode: $n\nProyeksi akhir: ${fmt(fv)}\nTotal setoran: ${fmt(p+add*n)}\nPertumbuhan: ${fmt(fv-(p+add*n))}")
            }
        })
        val target=edit("Target nominal (opsional)"); content.addView(target)
        content.addView(button("Hitung setoran bulanan ke target") {
            val tar=target.num();val p=initial.num();val annual=rate.num();val m=periods.num()
            if(tar==null||p==null||annual==null||m==null||m<=0) output("Isi target, modal awal, return tahunan, dan periode.") else {
                val rr=annual/100/12; val n=m.toInt(); val need=if(rr==0.0)(tar-p)/n else (tar-p*Math.pow(1.0+rr,n.toDouble()))*rr/(Math.pow(1.0+rr,n.toDouble())-1.0); output("Setoran bulanan yang diperlukan: ${fmt(kotlin.math.max(0.0,need))}")
            }
        })
    }
internal fun MainActivity.marginTaxCalculator() {
        clearPage("Margin & PPN/Pajak")
        content.addView(label("Harga Jual • Margin • Pajak",22f,true))
        val cogs=edit("COGS / modal barang");val margin=edit("Target margin (%)");val tax=edit("PPN / pajak (%)")
        listOf(cogs,margin,tax).forEach{content.addView(it)}
        pinToolAction(button("Hitung harga jual") {
            val c=cogs.num();val m=margin.num();val t=tax.num()?:0.0
            if(c==null||m==null||m<0||m>=100||t<0) output("Input tidak valid. Margin harus 0-99.99%.") else {
                val before=c/(1-m/100); val taxMoney=before*t/100; output("Harga sebelum pajak: ${fmt(before)}\nPajak: ${fmt(taxMoney)}\nHarga akhir: ${fmt(before+taxMoney)}\nLaba kotor: ${fmt(before-c)}")
            }
        })
    }
internal fun MainActivity.tieredDiscountCalculator() {
        clearPage("Diskon Bertingkat")
        content.addView(label("Diskon Bertingkat",22f,true))
        val price=edit("Harga awal");val d1=edit("Diskon 1 (%)");val d2=edit("Diskon 2 (%)");val d3=edit("Diskon 3 (%) opsional");listOf(price,d1,d2,d3).forEach{content.addView(it)}
        pinToolAction(button("Hitung harga akhir") {
            val p=price.num();val a=d1.num();val b=d2.num();val c=d3.num()?:0.0
            if(p==null||a==null||b==null||a<0||b<0||c<0||a>100||b>100||c>100) output("Input diskon tidak valid.") else { val end=p*(1-a/100)*(1-b/100)*(1-c/100); output("Harga akhir: ${fmt(end)}\nTotal diskon efektif: ${fmt((1-end/p)*100)}%\nHemat: ${fmt(p-end)}") }
        })
    }
internal fun MainActivity.dataUnitCalculator() {
        clearPage("Ukuran Data Digital")
        content.addView(label("Byte • KB • MB • GB • TB",22f,true))
        val input=edit("Nilai");content.addView(input);val units=listOf("Byte","KB","MB","GB","TB");content.addView(subLabel("Dari",11f));val from=segmentedChoice(units,2);content.addView(from);content.addView(subLabel("Ke",11f));val to=segmentedChoice(units,1);content.addView(to)
        pinToolAction(button("Konversi") { val v=input.num(); output(if(v==null)"Input tidak valid" else "${fmt(v*Math.pow(1024.0,from.selectedItemPosition-to.selectedItemPosition.toDouble()))} ${to.selectedItem}") })
    }
internal fun MainActivity.pressureCalculator() {
        clearPage("Konverter Tekanan")
        content.addView(label("Konverter Tekanan",22f,true))
        val input=edit("Nilai"); content.addView(input)
        val units=arrayOf("Pa","kPa","bar","psi")
        content.addView(subLabel("Dari", 11f)); val from=segmentedChoice(units.toList(), 2); content.addView(from)
        content.addView(subLabel("Ke", 11f)); val to=segmentedChoice(units.toList(), 3); content.addView(to)
        pinToolAction(button("Konversi") {
            val v=input.num(); output(if(v==null) "Input tidak valid" else {
                val base=v*when(from.selectedItemPosition){0->1.0;1->1000.0;2->100000.0;else->6894.757293}
                val result=base/when(to.selectedItemPosition){0->1.0;1->1000.0;2->100000.0;else->6894.757293}
                "${fmt(result)} ${to.selectedItem}"
            })
        })
    }
internal fun MainActivity.workTimeCalculator() {
        clearPage("Jam Kerja")
        content.addView(label("Durasi Jam Kerja",22f,true))
        val start=edit("Mulai HH:mm");val end=edit("Selesai HH:mm");val breakMin=edit("Istirahat (menit)",false);listOf(start,end,breakMin).forEach{content.addView(it)}
        pinToolAction(button("Hitung durasi") {
            output(runCatching { val f=SimpleDateFormat("HH:mm",Locale.US).apply{isLenient=false}; val s=f.parse(start.text.toString())?.time ?: error("mulai"); var e=f.parse(end.text.toString())?.time ?: error("selesai"); if(e<s)e+=86400000; val br=breakMin.num()?:0.0; val mins=((e-s)/60000.0-br).coerceAtLeast(0.0); "Durasi kerja: ${fmt(mins/60)} jam\n${mins.toLong()} menit" }.getOrElse{"Format waktu harus HH:mm"})
        })
    }
internal fun MainActivity.areaCalculator() {
        clearPage("Luas & Keliling")
        content.addView(label("Luas & Keliling",22f,true))
        val shape=segmentedChoice(listOf("Persegi","P. panjang","Segitiga","Lingkaran")); content.addView(shape)
        val a=edit("Sisi / panjang"); val b=edit("Lebar / tinggi (jika perlu)"); content.addView(a); content.addView(b)
        pinToolAction(button("Hitung") { val x=a.num(); val y=b.num(); if(x==null) output("Input tidak valid") else when(shape.selectedItemPosition){0->output("Luas=${fmt(x*x)} • Keliling=${fmt(4*x)}");1->if(y==null)output("Masukkan lebar")else output("Luas=${fmt(x*y)} • Keliling=${fmt(2*(x+y))}");2->if(y==null)output("Masukkan tinggi")else output("Luas=${fmt(.5*x*y)}");3->output("Luas=${fmt(Math.PI*x*x)} • Keliling=${fmt(2*Math.PI*x)}") } })
    }
internal fun MainActivity.volumeCalculator() {
        clearPage("Volume")
        content.addView(label("Kalkulator Volume",22f,true))
        val shape = segmentedChoice(listOf("Kubus","Balok","Tabung","Bola"))
        content.addView(shape)
        val a = edit("Ukuran / radius")
        val b = edit("Lebar / tinggi")
        val c = edit("Panjang / tinggi")
        content.addView(a)
        content.addView(b)
        content.addView(c)
        pinToolAction(button("Hitung Volume") {
            val x = a.num()
            val y = b.num()
            val z = c.num()
            val result = when (shape.selectedItemPosition) {
                0 -> if (x == null) "Input tidak valid" else fmt(x * x * x)
                1 -> if (x == null || y == null || z == null) "Butuh 3 ukuran" else fmt(x * y * z)
                2 -> if (x == null || y == null) "Butuh radius + tinggi" else fmt(Math.PI * x * x * y)
                else -> if (x == null) "Input tidak valid" else fmt(4.0 / 3.0 * Math.PI * x * x * x)
            }
            output(result)
        })
    }
internal fun MainActivity.speedCalculator() {
        clearPage("Kecepatan")
        content.addView(label("Jarak • Waktu • Kecepatan",22f,true))
        val d = edit("Jarak")
        val t = edit("Waktu (jam)")
        content.addView(d)
        content.addView(t)
        content.addView(button("Hitung kecepatan") {
            val x = d.num()
            val y = t.num()
            output(if (x == null || y == null || y == 0.0) "Input tidak valid" else "Kecepatan = ${fmt(x / y)} unit/jam")
        })
        content.addView(button("Hitung jarak dari kecepatan × waktu") {
            val x = d.num()
            val y = t.num()
            output(if (x == null || y == null) "Input tidak valid" else "Jarak = ${fmt(x * y)} unit")
        })
    }
internal fun MainActivity.timeCalculator() {
        clearPage("Waktu & Durasi")
        content.addView(label("Konversi Durasi",22f,true))
        val v = edit("Detik")
        content.addView(v)
        pinToolAction(button("Konversi") {
            val x = v.num()
            if (x == null || x < 0) {
                output("Input tidak valid")
            } else {
                val sec = x.toLong()
                val h = sec / 3600
                val m = (sec % 3600) / 60
                val ss = sec % 60
                output("$h jam $m menit $ss detik")
            }
        })
    }
internal fun MainActivity.dateCalculator() {
        clearPage("Selisih Tanggal")
        content.addView(label("Selisih dua tanggal",22f,true))
        val a=edit("Tanggal 1: YYYY-MM-DD"); val b=edit("Tanggal 2: YYYY-MM-DD")
        content.addView(a); content.addView(b)
        content.addView(button("Hitung hari") {
            output(runCatching {
                val f=SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient=false }
                val d1=f.parse(a.text.toString().trim()) ?: error("tanggal")
                val d2=f.parse(b.text.toString().trim()) ?: error("tanggal")
                "${kotlin.math.abs((d2.time-d1.time)/86400000L)} hari"
            }.getOrElse{"Format tanggal: YYYY-MM-DD"})
        })
        content.addView(button("Hitung umur dari Tanggal 1") {
            output(runCatching {
                val f=SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient=false }; val birth=f.parse(a.text.toString().trim()) ?: error("tanggal"); val now=Calendar.getInstance(); val dob=Calendar.getInstance().apply{time=birth}; var years=now.get(Calendar.YEAR)-dob.get(Calendar.YEAR); if(now.get(Calendar.DAY_OF_YEAR)<dob.get(Calendar.DAY_OF_YEAR)) years--; val days=((now.timeInMillis-birth.time)/86400000L).coerceAtLeast(0); "Umur sekitar $years tahun\nTotal hari hidup: $days"
            }.getOrElse{"Format tanggal: YYYY-MM-DD"})
        })
    }
internal fun MainActivity.loanCalculator() { clearPage("Cicilan Pinjaman"); content.addView(label("Kalkulator cicilan",22f,true)); val principal=edit("Pokok pinjaman"); val rate=edit("Bunga tahunan (%)"); val months=edit("Tenor (bulan)"); content.addView(principal);content.addView(rate);content.addView(months); pinToolAction(button("Hitung cicilan") {val p=principal.num();val r=rate.num();val n=months.num(); if(p==null||r==null||n==null||n<=0)output("Input tidak valid")else{val m=r/100/12; val pay=if(m==0.0)p/n else p*m*Math.pow(1+m,n)/(Math.pow(1+m,n)-1); output("Cicilan ≈ ${fmt(pay)} per bulan\nTotal ≈ ${fmt(pay*n)}")}}) }
internal fun MainActivity.fuelCalculator() {
        clearPage("Konsumsi BBM")
        content.addView(label("Konsumsi BBM", 22f, true))
        val distance = edit("Jarak (km)")
        val fuel = edit("BBM (liter)")
        val price = edit("Harga per liter (opsional)")
        content.addView(distance)
        content.addView(fuel)
        content.addView(price)
        pinToolAction(button("Hitung") {
            val d = distance.num()
            val f = fuel.num()
            val p = price.num()
            if (d == null || f == null || f <= 0) {
                output("Input tidak valid")
            } else {
                val kmpl = d / f
                val l100 = f / d * 100
                val cost = if (p == null) "" else "\nBiaya ≈ ${fmt(f * p)}"
                output("${fmt(kmpl)} km/l\n${fmt(l100)} L/100 km$cost")
            }
        })
    }
internal fun MainActivity.baseCalculator() {
        clearPage("Basis Angka")
        val e = edit("Masukkan angka, mis. 101101 atau FF")
        content.addView(e)
        content.addView(subLabel("Basis asal", 11f))
        val from = segmentedChoice(listOf("2", "8", "10", "16"), 2)
        content.addView(from)
        pinToolAction(button("Konversi ke semua basis") {
            try {
                val radix = from.selectedItem.toString().toInt()
                val raw = e.text.toString().trim()
                val n = raw.toLong(radix)
                val result = "BIN  " + n.toString(2) + "\n" +
                        "OCT  " + n.toString(8) + "\n" +
                        "DEC  " + n.toString(10) + "\n" +
                        "HEX  " + n.toString(16).uppercase(Locale.getDefault())
                output(result)
            } catch (ex: Exception) {
                output("Angka tidak valid untuk basis yang dipilih.")
            }
        })
    }
internal fun MainActivity.equationCalculator() { clearPage("Persamaan Linear"); content.addView(label("ax + b = c",22f,true)); val a=edit("a"); val b=edit("b"); val c=edit("c"); content.addView(a);content.addView(b);content.addView(c); pinToolAction(button("Cari x") {val aa=a.num();val bb=b.num();val cc=c.num();output(if(aa==null||bb==null||cc==null||aa==0.0)"Input tidak valid / a tidak boleh 0" else "x = ${fmt((cc-bb)/aa)}")}) }
internal fun EditText.num(): Double? = text.toString().trim().replace(",",".").toDoubleOrNull()

internal fun MainActivity.fmt(v:Double):String = if(v.isFinite() && kotlin.math.abs(v-v.toLong())<1e-10) v.toLong().toString() else String.format(Locale.US,"%.8f",v).trimEnd('0').trimEnd('.')


// ===================== Kalkulator tambahan: Trading / Finansial / Teknik / Developer =====================

/** Format uang gaya Indonesia: 1.234.567,89 */
internal fun MainActivity.fmtMoney(v: Double): String =
    String.format(Locale.forLanguageTag("id-ID"), "%,.2f", v)

internal fun MainActivity.pivotPointCalculator() {
    clearPage("Pivot Point")
    content.addView(label("Pivot Point", 22f, true))
    val high = edit("High"); val low = edit("Low"); val close = edit("Close")
    listOf(high, low, close).forEach { content.addView(it) }
    val method = segmentedChoice(listOf("Classic", "Fibonacci", "Woodie"))
    content.addView(method)
    pinToolAction(button("Hitung level") {
        val h = high.num(); val l = low.num(); val c = close.num()
        if (h == null || l == null || c == null || h < l) {
            output("Input tidak valid. Isi High, Low, Close (High harus ≥ Low).")
        } else {
            val range = h - l
            val text = when (method.selectedItemPosition) {
                1 -> {
                    val p = (h + l + c) / 3.0
                    "Pivot (P): ${fmt(p)}\nR1: ${fmt(p + 0.382 * range)}\nR2: ${fmt(p + 0.618 * range)}\nR3: ${fmt(p + range)}\nS1: ${fmt(p - 0.382 * range)}\nS2: ${fmt(p - 0.618 * range)}\nS3: ${fmt(p - range)}"
                }
                2 -> {
                    val p = (h + l + 2.0 * c) / 4.0
                    "Pivot (P): ${fmt(p)}\nR1: ${fmt(2 * p - l)}\nR2: ${fmt(p + range)}\nS1: ${fmt(2 * p - h)}\nS2: ${fmt(p - range)}"
                }
                else -> {
                    val p = (h + l + c) / 3.0
                    "Pivot (P): ${fmt(p)}\nR1: ${fmt(2 * p - l)}\nR2: ${fmt(p + range)}\nR3: ${fmt(h + 2 * (p - l))}\nS1: ${fmt(2 * p - h)}\nS2: ${fmt(p - range)}\nS3: ${fmt(l - 2 * (h - p))}"
                }
            }
            output(text)
        }
    })
}

internal fun MainActivity.voltageDividerCalculator() {
    clearPage("Voltage Divider")
    content.addView(label("Voltage Divider", 22f, true))
    val vin = edit("Vin (Volt)"); val r1 = edit("R1 (Ω)"); val r2 = edit("R2 (Ω)")
    listOf(vin, r1, r2).forEach { content.addView(it) }
    content.addView(button("Hitung Vout") {
        val v = vin.num(); val a = r1.num(); val b = r2.num()
        if (v == null || a == null || b == null || a < 0 || b < 0 || a + b <= 0.0) {
            output("Input tidak valid. R1 dan R2 tidak boleh negatif dan totalnya harus lebih dari 0.")
        } else {
            val vout = v * b / (a + b); val i = v / (a + b)
            output("Vout: ${fmt(vout)} V\nArus: ${fmt(i * 1000)} mA\nDaya total: ${fmt(v * i * 1000)} mW\nRasio Vout/Vin: ${fmt(b / (a + b) * 100)}%")
        }
    })
    val target = edit("Vout target (Volt) untuk mencari R2"); content.addView(target)
    content.addView(button("Cari R2 dari Vout target") {
        val v = vin.num(); val a = r1.num(); val t = target.num()
        if (v == null || a == null || t == null || a <= 0 || t <= 0 || t >= v) {
            output("Isi Vin, R1 (>0), dan Vout target (0 < Vout < Vin).")
        } else {
            val b = a * t / (v - t)
            output("R2 yang dibutuhkan: ${fmt(b)} Ω\nArus: ${fmt(v / (a + b) * 1000)} mA")
        }
    })
}

internal fun MainActivity.dcaCalculator() {
    clearPage("Averaging / DCA")
    content.addView(label("Averaging / DCA", 22f, true))
    val qty0 = edit("Jumlah awal (unit/lot)"); val avg0 = edit("Harga rata-rata awal")
    val qty1 = edit("Jumlah tambahan"); val price1 = edit("Harga beli tambahan"); val now = edit("Harga saat ini (opsional)")
    listOf(qty0, avg0, qty1, price1, now).forEach { content.addView(it) }
    pinToolAction(button("Hitung rata-rata baru") {
        val q0 = qty0.num(); val a0 = avg0.num(); val q1 = qty1.num(); val p1 = price1.num(); val cur = now.num()
        if (q0 == null || a0 == null || q1 == null || p1 == null || q0 < 0 || a0 < 0 || q1 <= 0 || p1 <= 0) {
            output("Input tidak valid. Jumlah tambahan dan harga beli harus lebih dari 0.")
        } else {
            val totalQty = q0 + q1
            val cost = q0 * a0 + q1 * p1
            val avg = cost / totalQty
            val sb = StringBuilder()
            sb.append("Total jumlah: ${fmt(totalQty)}\nTotal modal: ${fmtMoney(cost)}\nRata-rata baru: ${fmt(avg)}\nPerubahan rata-rata: ${fmt(avg - a0)}")
            if (cur != null && cur > 0) {
                val pnl = (cur - avg) * totalQty
                val pct = (cur - avg) / avg * 100.0
                sb.append("\nFloating P/L: ${fmtMoney(pnl)} (${String.format(Locale.US, "%.2f", pct)}%)")
            }
            output(sb.toString())
        }
    })
}

internal fun MainActivity.pwmCalculator() {
    clearPage("PWM & Duty Cycle")
    content.addView(label("PWM & Duty Cycle", 22f, true))
    val freq = edit("Frekuensi (Hz)"); val duty = edit("Duty cycle (%)")
    val vcc = edit("Tegangan HIGH (V, opsional)"); val bits = edit("Resolusi (bit, opsional, mis. 8)")
    listOf(freq, duty, vcc, bits).forEach { content.addView(it) }
    fun timeText(sec: Double): String = when {
        sec >= 1.0 -> "${fmt(sec)} s"
        sec >= 1e-3 -> "${fmt(sec * 1e3)} ms"
        else -> "${fmt(sec * 1e6)} µs"
    }
    pinToolAction(button("Hitung PWM") {
        val f = freq.num(); val d = duty.num(); val v = vcc.num(); val bt = bits.num()?.toInt()
        if (f == null || f <= 0 || d == null || d < 0 || d > 100 || (bt != null && bt !in 1..16)) {
            output("Input tidak valid. Frekuensi > 0, duty 0–100%, resolusi 1–16 bit.")
        } else {
            val period = 1.0 / f
            val high = period * d / 100.0
            val sb = StringBuilder()
            sb.append("Periode: ${timeText(period)}\nWaktu HIGH: ${timeText(high)}\nWaktu LOW: ${timeText(period - high)}")
            if (v != null && v >= 0) sb.append("\nTegangan rata-rata: ${fmt(v * d / 100.0)} V")
            if (bt != null) {
                val maxValue = (1 shl bt) - 1
                sb.append("\nNilai PWM (${bt}-bit): ${kotlin.math.round(d / 100.0 * maxValue).toLong()} dari $maxValue")
            }
            output(sb.toString())
        }
    })
}

internal fun MainActivity.spriteSheetCalculator() {
    clearPage("Sprite Sheet Grid")
    content.addView(label("Sprite Sheet Grid", 22f, true))
    val sw = edit("Lebar sheet (px)"); val sh = edit("Tinggi sheet (px)")
    val fw = edit("Lebar frame (px)"); val fh = edit("Tinggi frame (px)")
    val idx = edit("Indeks frame (opsional, mulai dari 0)"); val fps = edit("FPS animasi (opsional)")
    listOf(sw, sh, fw, fh, idx, fps).forEach { content.addView(it) }
    pinToolAction(button("Hitung grid") {
        val w = sw.num()?.toInt(); val h = sh.num()?.toInt(); val frameW = fw.num()?.toInt(); val frameH = fh.num()?.toInt()
        val index = idx.num()?.toInt(); val rate = fps.num()
        if (w == null || h == null || frameW == null || frameH == null || w <= 0 || h <= 0 || frameW <= 0 || frameH <= 0 || frameW > w || frameH > h) {
            output("Input tidak valid. Ukuran frame harus > 0 dan tidak lebih besar dari sheet.")
        } else {
            val cols = w / frameW; val rows = h / frameH; val total = cols * rows
            val sb = StringBuilder()
            sb.append("Kolom: $cols\nBaris: $rows\nTotal frame: $total\nSisa piksel: ${w - cols * frameW} × ${h - rows * frameH}")
            if (index != null) {
                if (index in 0 until total) {
                    val x = (index % cols) * frameW; val y = (index / cols) * frameH
                    sb.append("\n\nFrame #$index\nPosisi: x=$x, y=$y\nUV: (${fmt(x.toDouble() / w)}, ${fmt(y.toDouble() / h)}) → (${fmt((x + frameW).toDouble() / w)}, ${fmt((y + frameH).toDouble() / h)})")
                } else {
                    sb.append("\n\nIndeks di luar rentang 0–${total - 1}")
                }
            }
            if (rate != null && rate > 0) sb.append("\n\nDurasi animasi: ${fmt(total / rate)} detik pada ${fmt(rate)} FPS")
            output(sb.toString())
        }
    })
}

internal fun MainActivity.installmentComparisonCalculator() {
    clearPage("Flat vs Anuitas")
    content.addView(label("Cicilan Flat vs Anuitas", 22f, true))
    val principal = edit("Pokok pinjaman"); val rate = edit("Bunga per tahun (%)"); val tenor = edit("Tenor (bulan)")
    listOf(principal, rate, tenor).forEach { content.addView(it) }
    pinToolAction(button("Bandingkan") {
        val p = principal.num(); val r = rate.num(); val n = tenor.num()?.toInt()
        if (p == null || r == null || n == null || p <= 0 || r < 0 || n <= 0 || n > 600) {
            output("Input tidak valid. Tenor 1–600 bulan.")
        } else {
            val flatInterest = p * r / 100.0 * (n / 12.0)
            val flatMonthly = (p + flatInterest) / n
            val mr = r / 100.0 / 12.0
            val annMonthly = if (mr == 0.0) p / n else p * mr / (1.0 - Math.pow(1.0 + mr, -n.toDouble()))
            val annTotal = annMonthly * n
            // Bunga efektif skema flat: cari bunga bulanan yang membuat anuitas = cicilan flat.
            var effAnnual = 0.0
            if (r > 0.0) {
                var lo = 0.0; var hi = 1.0
                repeat(100) {
                    val mid = (lo + hi) / 2.0
                    val pay = p * mid / (1.0 - Math.pow(1.0 + mid, -n.toDouble()))
                    if (pay.isNaN() || pay >= flatMonthly) hi = mid else lo = mid
                }
                effAnnual = (lo + hi) / 2.0 * 12.0 * 100.0
            }
            output(
                "FLAT\nCicilan/bulan: ${fmtMoney(flatMonthly)}\nTotal bunga: ${fmtMoney(flatInterest)}\nTotal bayar: ${fmtMoney(p + flatInterest)}" +
                "\nBunga efektif setara: ${String.format(Locale.US, "%.2f", effAnnual)}% per tahun" +
                "\n\nANUITAS\nCicilan/bulan: ${fmtMoney(annMonthly)}\nTotal bunga: ${fmtMoney(annTotal - p)}\nTotal bayar: ${fmtMoney(annTotal)}" +
                "\n\nSelisih total bayar (flat − anuitas): ${fmtMoney(p + flatInterest - annTotal)}"
            )
        }
    })
}

internal fun MainActivity.powerConsumptionCalculator() {
    clearPage("Konsumsi Listrik")
    content.addView(label("Konsumsi Listrik", 22f, true))
    val watt = edit("Daya perangkat (Watt)"); val qty = edit("Jumlah perangkat (default 1)")
    val hours = edit("Pemakaian per hari (jam)"); val days = edit("Hari per bulan (default 30)")
    val price = edit("Tarif per kWh (opsional)")
    listOf(watt, qty, hours, days, price).forEach { content.addView(it) }
    pinToolAction(button("Hitung konsumsi") {
        val w = watt.num(); val q = qty.num() ?: 1.0; val h = hours.num(); val d = days.num() ?: 30.0; val tarif = price.num()
        if (w == null || h == null || w <= 0 || q <= 0 || h <= 0 || h > 24 || d <= 0 || d > 31) {
            output("Input tidak valid. Daya > 0, jam 0–24 per hari, hari 1–31.")
        } else {
            val kwhDay = w * q * h / 1000.0
            val kwhMonth = kwhDay * d
            val sb = StringBuilder("Energi per hari: ${fmt(kwhDay)} kWh\nEnergi per bulan: ${fmt(kwhMonth)} kWh\nEnergi per tahun: ${fmt(kwhDay * 365.0)} kWh")
            if (tarif != null && tarif > 0) {
                sb.append("\n\nBiaya per hari: ${fmtMoney(kwhDay * tarif)}\nBiaya per bulan: ${fmtMoney(kwhMonth * tarif)}\nBiaya per tahun: ${fmtMoney(kwhDay * 365.0 * tarif)}")
            }
            output(sb.toString())
        }
    })
}

internal fun MainActivity.aspectRatioCalculator() {
    clearPage("Aspect Ratio")
    content.addView(label("Aspect Ratio", 22f, true))
    val width = edit("Lebar"); val height = edit("Tinggi")
    val newW = edit("Lebar baru (opsional)"); val newH = edit("Tinggi baru (opsional)")
    listOf(width, height, newW, newH).forEach { content.addView(it) }
    pinToolAction(button("Hitung rasio") {
        val w = width.num(); val h = height.num(); val nw = newW.num(); val nh = newH.num()
        if (w == null || h == null || w <= 0 || h <= 0) {
            output("Input tidak valid. Lebar dan tinggi harus lebih dari 0.")
        } else {
            val sb = StringBuilder()
            val wl = w.toLong(); val hl = h.toLong()
            if (w == wl.toDouble() && h == hl.toDouble() && wl > 0 && hl > 0) {
                val g = gcd(wl, hl)
                sb.append("Rasio: ${wl / g}:${hl / g}\n")
            }
            sb.append("Desimal (W/H): ${String.format(Locale.US, "%.4f", w / h)}\nOrientasi: ${if (w > h) "Landscape" else if (w < h) "Portrait" else "Persegi"}\nMegapiksel: ${String.format(Locale.US, "%.2f", w * h / 1_000_000.0)} MP")
            if (nw != null && nw > 0) sb.append("\n\nUntuk lebar ${fmt(nw)} → tinggi ${fmt(nw * h / w)}")
            if (nh != null && nh > 0) sb.append("\nUntuk tinggi ${fmt(nh)} → lebar ${fmt(nh * w / h)}")
            output(sb.toString())
        }
    })
}

internal fun MainActivity.ppnPphCalculator() {
    clearPage("PPN & PPh")
    content.addView(label("PPN & PPh", 22f, true))
    val amount = edit("Nominal"); val ppnRate = edit("Tarif PPN % (default 11)"); val pphRate = edit("Tarif PPh % (opsional, mis. 2)")
    listOf(amount, ppnRate, pphRate).forEach { content.addView(it) }
    val mode = segmentedChoice(listOf("Belum termasuk PPN", "Sudah termasuk PPN"))
    content.addView(mode)
    pinToolAction(button("Hitung") {
        val a = amount.num(); val ppn = ppnRate.num() ?: 11.0; val pph = pphRate.num() ?: 0.0
        if (a == null || a <= 0 || ppn < 0 || ppn > 100 || pph < 0 || pph > 100) {
            output("Input tidak valid. Nominal > 0, tarif 0–100%.")
        } else {
            val dpp: Double; val ppnValue: Double; val total: Double
            if (mode.selectedItemPosition == 0) {
                dpp = a; ppnValue = dpp * ppn / 100.0; total = dpp + ppnValue
            } else {
                total = a; dpp = total / (1.0 + ppn / 100.0); ppnValue = total - dpp
            }
            val sb = StringBuilder("DPP (sebelum PPN): ${fmtMoney(dpp)}\nPPN ${fmt(ppn)}%: ${fmtMoney(ppnValue)}\nTotal dengan PPN: ${fmtMoney(total)}")
            if (pph > 0) {
                val pphValue = dpp * pph / 100.0
                sb.append("\n\nPPh ${fmt(pph)}% (atas DPP): ${fmtMoney(pphValue)}\nNetto setelah PPh: ${fmtMoney(total - pphValue)}")
            }
            output(sb.toString())
        }
    })
}
