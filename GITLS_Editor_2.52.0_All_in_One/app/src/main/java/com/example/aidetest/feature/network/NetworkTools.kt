package com.example.aidetest

import android.content.Context
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.net.wifi.WifiManager
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Network utility tools extracted from MainActivity (gradual modularization).
 * Extension receivers keep call sites in openTool() unchanged.
 */

internal fun MainActivity.whoisTool() {
        clearPage("Whois")
        toolWorkspace("Whois", "Lookup registrasi domain lewat whois.iana.org (hasil mentah).", "web")
        toolWorkspaceSection("DOMAIN")
        val e = edit("Domain", false).apply { hint = "example.com" }
        content.addView(e)
        pinToolAction(button("Lookup") {
            val d = e.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
            if (d.isBlank()) { toast("Masukkan domain"); return@button }
            toolThread {
                runCatching {
                    val s = Socket()
                    s.connect(java.net.InetSocketAddress("whois.iana.org", 43), 6000)
                    s.soTimeout = 6000
                    s.getOutputStream().write((d + "\r\n").toByteArray())
                    val out = s.getInputStream().bufferedReader().readText().take(12000)
                    s.close()
                    runOnToolUiThread { output(out) }
                }.onFailure { runOnToolUiThread { toast("Whois gagal: ${it.message}") } }
            }
        })
    }

internal fun MainActivity.tracerouteTool() {
        clearPage("Traceroute")
        toolWorkspace("Traceroute", "Jejak hop menuju host (tergantung binary di perangkat).", "map-marker-path")
        toolWorkspaceSection("HOST")
        val e = edit("Host", false).apply { setText("8.8.8.8") }
        content.addView(e)
        pinToolAction(button("Start") {
            val h = e.text.toString().trim()
            toolThread {
                val cmds = listOf(
                    arrayOf("traceroute", "-m", "12", "-w", "1", h),
                    arrayOf("/system/bin/traceroute", "-m", "12", "-w", "1", h)
                )
                var done = false
                for (c in cmds) {
                    if (done) continue // jangan tampilkan hasil dua kali bila kedua path berhasil
                    runCatching {
                        val p = ProcessBuilder(*c).redirectErrorStream(true).start()
                        val o = p.inputStream.bufferedReader().readText().take(16000)
                        p.waitFor()
                        runOnToolUiThread { output(o) }
                        done = true
                    }.onFailure { }
                }
                if (!done) runOnToolUiThread { toast("Traceroute tidak tersedia di perangkat") }
            }
        })
    }

internal fun MainActivity.subnetCalculatorTool() {
    clearPage("Subnet Calculator")
    toolWorkspace("Subnet Calculator", "Hitung network, broadcast, dan range IPv4.", "ip-network")
    val ip = edit("IPv4").apply { setText("192.168.1.10") }
    val prefix = edit("Prefix").apply { setText("24") }
    content.addView(ip)
    content.addView(prefix)
    pinToolAction(button("Hitung") {
        val octets = ip.text.toString().trim().split('.').mapNotNull { it.toIntOrNull() }
        val p = prefix.text.toString().trim().toIntOrNull()
        if (octets.size != 4 || octets.any { it !in 0..255 } || p == null || p !in 0..32) {
            toast("IPv4/prefix tidak valid")
            return@button
        }
        val mask = if (p == 0) 0L else (0xffffffffL shl (32 - p)) and 0xffffffffL
        val addr = (octets[0].toLong() shl 24) or (octets[1].toLong() shl 16) or
            (octets[2].toLong() shl 8) or octets[3].toLong()
        val net = addr and mask
        val broadcast = net or (0xffffffffL xor mask)
        output("Network: ${ipv4(net)}\nBroadcast: ${ipv4(broadcast)}\nSubnet Mask: ${ipv4(mask)}\nPrefix: /$p\nTotal alamat: ${if (p == 32) 1L else 1L shl (32 - p)}")
    })
}

internal fun MainActivity.ipv4(v:Long)="${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

internal fun MainActivity.publicIpTool() {
        clearPage("IP Publik")
        pinToolAction(button("Get IP") {
            toolThread {
                val r=runCatching {
                    (URL("https://api.ipify.org").openConnection() as java.net.HttpURLConnection).run {
                        connectTimeout = 8000; readTimeout = 8000
                        try { inputStream.bufferedReader().readText() } finally { disconnect() }
                    }
                }.getOrElse { it.message ?: "error" }
                runOnToolUiThread { output(r) }
            }
        })
    }

internal fun MainActivity.pingTool() {
    clearPage("Ping")
    toolWorkspace("Ping", "Cek latensi host atau alamat IP.", "lan")
    toolWorkspaceSection("TARGET")
    val field = edit("Host atau IP").apply { setText("8.8.8.8") }
    content.addView(field)
    lateinit var action: android.widget.Button
    action = button("Ping") {
        val target = field.text.toString().trim()
        if (target.isBlank()) { toast("Masukkan host atau IP"); return@button }
        action.isEnabled = false
        toolThread("Menjalankan ping…") {
            val result = runCatching {
                val process = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "2", target))
                val text = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                text.ifBlank { "Tidak ada respons ping." }
            }.getOrElse { "Ping gagal: ${it.message ?: it.javaClass.simpleName}" }
            runOnToolUiThread { action.isEnabled = true; output(result) }
        }
    }
    pinToolAction(action)
}

internal fun MainActivity.portTool() {
    clearPage("Port Checker")
    toolWorkspace("Port Checker", "Cek port TCP pada host yang kamu miliki atau diizinkan.", "lan-check")
    toolWorkspaceSection("TARGET")
    val hostField = edit("Host")
    val portField = edit("Port").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
    content.addView(hostField)
    content.addView(portField)
    lateinit var action: android.widget.Button
    action = button("Check") {
        val host = hostField.text.toString().trim()
        val port = portField.text.toString().trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            toast("Host atau port tidak valid")
            return@button
        }
        action.isEnabled = false
        toolThread("Memeriksa port…") {
            val result = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 2500)
                    "OPEN • $host:$port"
                }
            }.getOrElse { "CLOSED / ERROR • ${it.message ?: it.javaClass.simpleName}" }
            runOnToolUiThread { action.isEnabled = true; output(result) }
        }
    }
    pinToolAction(action)
}

internal fun MainActivity.dnsTool() {
    clearPage("DNS Lookup")
    toolWorkspace("DNS Lookup", "Cari alamat IP dari domain.", "dns")
    toolWorkspaceSection("DOMAIN")
    val field = edit("Domain").apply { setText("example.com") }
    content.addView(field)
    lateinit var action: android.widget.Button
    action = button("Lookup") {
        val target = field.text.toString().trim()
        if (target.isBlank()) { toast("Masukkan domain"); return@button }
        action.isEnabled = false
        toolThread("Mencari DNS…") {
            val result = runCatching {
                InetAddress.getAllByName(target).mapNotNull { it.hostAddress }.distinct().joinToString("\n")
                    .ifBlank { "Tidak ada alamat yang ditemukan." }
            }.getOrElse { "DNS lookup gagal: ${it.message ?: it.javaClass.simpleName}" }
            runOnToolUiThread { action.isEnabled = true; output(result) }
        }
    }
    pinToolAction(action)
}

internal fun MainActivity.reverseDnsTool() {
    clearPage("Reverse DNS")
    toolWorkspace("Reverse DNS", "Cari hostname dari alamat IP.", "dns")
    toolWorkspaceSection("IP")
    val field = edit("IP").apply { setText("8.8.8.8") }
    content.addView(field)
    lateinit var action: android.widget.Button
    action = button("Lookup") {
        val target = field.text.toString().trim()
        if (target.isBlank()) { toast("Masukkan IP"); return@button }
        action.isEnabled = false
        toolThread("Mencari reverse DNS…") {
            val result = runCatching { InetAddress.getByName(target).canonicalHostName }
                .getOrElse { "Reverse DNS gagal: ${it.message ?: it.javaClass.simpleName}" }
            runOnToolUiThread { action.isEnabled = true; output(result) }
        }
    }
    pinToolAction(action)
}

internal fun MainActivity.ipInfoTool() {
    clearPage("IP Address Info")
    toolWorkspace("IP Address Info", "Analisis properti alamat IP.", "ip-network")
    toolWorkspaceSection("IP")
    val field = edit("IP").apply { setText("8.8.8.8") }
    content.addView(field)
    lateinit var action: android.widget.Button
    action = button("Analyze") {
        val target = field.text.toString().trim()
        if (target.isBlank()) { toast("Masukkan alamat IP atau hostname"); return@button }
        action.isEnabled = false
        toolThread("Menganalisis alamat…") {
            val result = runCatching {
                val ip = InetAddress.getByName(target)
                val raw = ip.address
                "Host: ${ip.hostAddress}\nLoopback: ${ip.isLoopbackAddress}\nLink-local: ${ip.isLinkLocalAddress}\nSite-local: ${ip.isSiteLocalAddress}\nBytes: ${raw.joinToString(".") { (it.toInt() and 255).toString() }}"
            }.getOrElse { "IP/hostname tidak valid: ${it.message ?: "lookup gagal"}" }
            runOnToolUiThread { action.isEnabled = true; output(result) }
        }
    }
    pinToolAction(action)
}

internal fun MainActivity.sslTool() {
    clearPage("SSL Certificate")
    toolWorkspace("SSL Certificate", "Periksa sertifikat TLS dari host HTTPS.", "certificate")
    val field = edit("example.com:443")
    content.addView(field)
    lateinit var action: android.widget.Button
    action = button("Check") {
        val endpoint = field.text.toString().trim()
        val parts = endpoint.split(":")
        val host = parts.firstOrNull().orEmpty().trim()
        val port = if (parts.size < 2) 443 else parts.getOrNull(1)?.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            toast("Host atau port tidak valid")
            return@button
        }
        action.isEnabled = false
        toolThread("Memeriksa sertifikat…") {
            val result = runCatching {
                val ctx = javax.net.ssl.SSLContext.getDefault()
                (ctx.socketFactory.createSocket() as javax.net.ssl.SSLSocket).use { sock ->
                    sock.connect(InetSocketAddress(host, port), 5000)
                    sock.soTimeout = 5000
                    sock.startHandshake()
                    sock.session.peerCertificates.firstOrNull()?.toString() ?: "No certificate"
                }
            }.getOrElse { "SSL error: ${it.message ?: it.javaClass.simpleName}" }
            runOnToolUiThread { action.isEnabled = true; output(result) }
        }
    }
    pinToolAction(action)
}

internal fun MainActivity.restApiClientTool() {
    clearPage("REST / API Client")
    toolWorkspace("REST / API Client", "Kirim request HTTP dan periksa respons.", "api")
    toolWorkspaceSection("REQUEST", "Atur method dan URL.")
    // Segmented control: semua method terlihat, satu ketukan (tanpa dropdown).
    val method = segmentedChoice(listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"))
    val url = edit("https://example.com/api")
    val headers = edit("Headers (Name: Value per baris)").apply { minLines = 2; maxLines = 4 }
    val body = edit("Body (JSON/text)").apply { minLines = 3; maxLines = 6 }
    content.addView(method)
    content.addView(url)
    content.addView(headers)
    content.addView(body)
    val status = toolStatus("Siap", false)
    content.addView(status)
    lateinit var send: android.widget.Button
    send = button("Kirim Request") {
        val target = url.text.toString().trim()
        val requestMethod = method.selectedItem.ifBlank { "GET" }
        val headerText = headers.text.toString()
        val requestBody = body.text.toString()
        if (target.isBlank()) { toast("URL wajib diisi"); return@button }
        httpUrlProblem(target)?.let { problem ->
            setToolState(status, Ds.State.ERROR, problem)
            toast(problem)
            return@button
        }
        send.isEnabled = false
        setToolState(status, Ds.State.LOADING, "Mengirim $requestMethod…")
        toolThread("Mengirim request…") {
            val result = runCatching {
                val conn = URL(target).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = requestMethod
                    conn.connectTimeout = 12000
                    conn.readTimeout = 15000
                    conn.instanceFollowRedirects = true
                    headerText.lineSequence().forEach { line ->
                        val i = line.indexOf(':')
                        if (i > 0) {
                            val key = line.substring(0, i).trim()
                            val value = line.substring(i + 1).trim()
                            if (key.isNotEmpty()) conn.setRequestProperty(key, value)
                        }
                    }
                    if (requestMethod in setOf("POST", "PUT", "PATCH", "DELETE")) {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(requestBody.toByteArray(StandardCharsets.UTF_8)) }
                    }
                    val code = conn.responseCode
                    val stream = if (code >= 400) conn.errorStream else conn.inputStream
                    val responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                    val responseHeaders = conn.headerFields.entries.filter { it.key != null }
                        .joinToString("\n") { (key, values) -> "$key: ${values?.joinToString("; ") ?: ""}" }
                    "HTTP $code\n\nHeaders:\n$responseHeaders\n\nBody:\n${responseBody.take(50000)}"
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { "Request gagal: ${it.javaClass.simpleName}: ${it.message ?: "unknown error"}" }
            runOnToolUiThread {
                send.isEnabled = true
                if (result.startsWith("Request gagal")) setToolState(status, Ds.State.ERROR, "Gagal — lihat detail di hasil")
                else setToolState(status, Ds.State.SUCCESS, result.substringBefore('\n'))
                output(result)
            }
        }
    }
    pinToolAction(send)
}

internal fun MainActivity.networkScannerTool() {
        clearPage("Network Scanner")
        toolWorkspace("Network Scanner", "Diagnostik subnet lokal: cari host/port TCP terbuka pada jaringan yang Anda miliki atau diizinkan.", "magnify-scan")
        content.addView(subLabel("Hanya untuk jaringan yang Anda miliki atau diizinkan (rumah/lab). Jangan memindai sistem pihak lain tanpa izin.", 12f).apply { setPadding(dp(2), dp(4), dp(2), dp(8)) })
        toolWorkspaceSection("SCAN CONFIG", "Tentukan subnet dan daftar port sebelum memulai scan.")
        val subnet = edit("Contoh 192.168.1.0/24")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ip = wm.connectionInfo.ipAddress
        val defaultSubnet = if (ip != 0) {
            val a = ip and 255; val b = ip shr 8 and 255; val c = ip shr 16 and 255
            "$a.$b.$c.0/24"
        } else "192.168.1.0/24"
        subnet.setText(defaultSubnet)
        content.addView(subnet)
        val ports = edit("Port: 80,443,8080,53,3000,5173")
        ports.setText("80,443,8080,53,3000,5173")
        content.addView(ports)
        val status = label("Siap", 13f, true); content.addView(status)
        content.addView(button("Mulai Scan") {
            val range = parseCidr24(subnet.text.toString().trim())
            if (range == null) { toast("Gunakan format x.x.x.0/24"); return@button }
            val portList = ports.text.toString().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 }.distinct().take(12)
            if (portList.isEmpty()) { toast("Port tidak valid"); return@button }
            networkScanStop.set(false)
            status.text = "Scanning..."
            val resultBox = label("", 12f)
            content.addView(resultBox)
            toolThread {
                val found = Collections.synchronizedList(mutableListOf<String>())
                val pool = Executors.newFixedThreadPool(ToolPerformance.NET_SCAN_POOL)
                val jobs = (1..254).map { host ->
                    pool.submit {
                        if (networkScanStop.get()) return@submit
                        val hostIp = "${range.first}.$host"
                        for (port in portList) {
                            if (networkScanStop.get()) break
                            try {
                                Socket().use { s ->
                                    s.connect(InetSocketAddress(hostIp, port), ToolPerformance.NET_PROBE_TIMEOUT_MS)
                                    found.add("$hostIp:$port OPEN")
                                }
                            } catch (_: Exception) {
                                // Port closed/filtered/timeout — expected during LAN scan
                            }
                        }
                    }
                }
                jobs.forEach { runCatching { it.get() } }
                pool.shutdownNow()
                runOnToolUiThread {
                    status.text = if (networkScanStop.get()) "Dihentikan" else "Selesai"
                    val list = found.distinct().sorted(); resultBox.text = if (list.isEmpty()) "Tidak ditemukan port terbuka pada port yang dipilih." else list.take(500).joinToString("\n") + if (list.size > 500) "\n… (${list.size} total)" else ""
                }
            }
        })
        content.addView(button("Hentikan Scan") { networkScanStop.set(true) })
    }
internal fun MainActivity.networkInfoTool() {
        clearPage("Network Info")
        toolWorkspace("Network Info", "Interface dan alamat jaringan yang tersedia di perangkat.", "network")
        toolWorkspaceSection("INTERFACES", "Daftar interface aktif dan alamatnya.")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            interfaces?.asSequence()?.filter { it.isUp && !it.isLoopback }?.forEach { ni ->
                val addresses = ni.inetAddresses.asSequence().map { it.hostAddress ?: "" }.filter { it.isNotBlank() }.toList()
                infoRow(ni.displayName ?: ni.name, addresses.joinToString(" • "))
            }
        } catch (e: Exception) {
            infoRow("Error", e.message ?: "Tidak dapat membaca interface")
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ip = wm.connectionInfo.ipAddress
        val ipText = if (ip == 0) "Tidak terhubung" else listOf(ip and 255, ip shr 8 and 255, ip shr 16 and 255, ip shr 24 and 255).joinToString(".")
        infoRow("Wi-Fi IP", ipText)
    }
internal fun MainActivity.networkCenterTool() {
        clearPage("Network Center")
        toolWorkspace("Network Center", "Ringkasan koneksi, interface, internet, dan alamat jaringan perangkat.", "lan-connect")
        toolWorkspaceSection("NETWORK STATUS", "Informasi dibaca langsung dari sistem Android.")
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val n=cm.activeNetwork; val caps=if(n!=null)cm.getNetworkCapabilities(n) else null
        infoRow("Status", if(n!=null) "Terhubung" else "Tidak terhubung")
        infoRow("Transport", when { caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true -> "Wi‑Fi"; caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)==true -> "Seluler"; caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)==true -> "Ethernet"; else -> "Lainnya / tidak diketahui" })
        infoRow("Internet", if(caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true) "Terverifikasi" else "Belum terverifikasi")
        runCatching{NetworkInterface.getNetworkInterfaces().asSequence().filter{it.isUp&&!it.isLoopback}.forEach{ni->val a=ni.inetAddresses.asSequence().mapNotNull{it.hostAddress}.distinct().joinToString(", ");infoRow(ni.displayName?:ni.name,a)}}
        content.addView(button("Refresh"){networkCenterTool()})
    }
internal fun MainActivity.parseCidr24(cidr: String): Pair<String, Int>? {
        val parts = cidr.split('/')
        if (parts.size != 2 || parts[1] != "24") return null
        val oct = parts[0].split('.').mapNotNull { it.toIntOrNull() }
        if (oct.size != 4 || oct.any { it !in 0..255 }) return null
        return "${oct[0]}.${oct[1]}.${oct[2]}" to 24
    }

/**
 * OPEN-10: Android memblokir lalu lintas cleartext (http://) kecuali ke localhost/127.0.0.1
 * (lihat res/xml/network_security_config.xml). Tolak lebih awal dengan pesan yang jelas
 * daripada menampilkan error "Cleartext HTTP traffic not permitted" mentah.
 * @return null bila URL boleh dipakai, atau pesan untuk pengguna.
 */
internal fun httpUrlProblem(target: String): String? {
    val uri = runCatching { java.net.URI(target.trim()) }.getOrNull() ?: return "URL tidak valid"
    val scheme = uri.scheme?.lowercase(Locale.US)
    val host = uri.host?.lowercase(Locale.US)
    if (scheme != "https" && scheme != "http") return "Gunakan URL yang diawali https://"
    if (host.isNullOrBlank()) return "Host pada URL tidak valid"
    if (scheme == "http" && host != "localhost" && host != "127.0.0.1") {
        return "http:// ke $host diblokir Android (tanpa enkripsi). Gunakan https://$host… — http:// hanya untuk localhost/127.0.0.1."
    }
    return null
}
