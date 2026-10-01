package com.example.aidetest

import java.nio.charset.StandardCharsets
import java.net.NetworkInterface

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

internal fun MainActivity.whoisTool(){ clearPage("Whois"); val e=edit("Domain",false);e.hint="example.com";content.addView(e);content.addView(button("Lookup"){val d=e.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore('/');if(d.isBlank()){toast("Masukkan domain");return@button};toolThread {runCatching{val s=Socket("whois.iana.org",43);s.soTimeout=6000;s.getOutputStream().write((d+"\r\n").toByteArray());val out=s.getInputStream().bufferedReader().readText().take(12000);s.close();runOnUiThread{output(out)}}.onFailure{runOnUiThread{toast("Whois gagal: ${it.message}")}}}})}

internal fun MainActivity.tracerouteTool(){ clearPage("Traceroute");val e=edit("Host",false);e.setText("8.8.8.8");content.addView(e);content.addView(button("Start"){val h=e.text.toString().trim();toolThread {val cmds=listOf(arrayOf("traceroute","-m","12","-w","1",h),arrayOf("/system/bin/traceroute","-m","12","-w","1",h));var done=false;for(c in cmds){runCatching{val p=ProcessBuilder(*c).redirectErrorStream(true).start();val o=p.inputStream.bufferedReader().readText().take(16000);p.waitFor();runOnUiThread{output(o)};done=true}.onFailure{}};if(!done)runOnUiThread{toast("Traceroute tidak tersedia di perangkat")}}})}

internal fun MainActivity.subnetCalculatorTool(){clearPage("Subnet Calculator");val ip=edit("IPv4",false);ip.setText("192.168.1.10");val pre=edit("Prefix",false);pre.setText("24");content.addView(ip);content.addView(pre);content.addView(button("Hitung"){val parts=ip.text.toString().split('.').mapNotNull{it.toIntOrNull()};val p=pre.text.toString().toIntOrNull();if(parts.size!=4||p==null||p !in 0..32){toast("IPv4/prefix tidak valid");return@button};val mask=if(p==0)0L else (0xffffffffL shl (32-p)) and 0xffffffffL;val addr=((parts[0].toLong() shl 24) or (parts[1].toLong() shl 16) or (parts[2].toLong() shl 8) or parts[3].toLong());val net=addr and mask;val broad=net or (0xffffffffL xor mask);output("Network: ${ipv4(net)}\nBroadcast: ${ipv4(broad)}\nSubnet Mask: ${ipv4(mask)}\nPrefix: /$p\nTotal alamat: ${if(p==32)1L else 1L shl (32-p)}")})}

internal fun MainActivity.ipv4(v:Long)="${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

internal fun MainActivity.publicIpTool() {
        clearPage("IP Publik")
        content.addView(button("Get IP") {
            toolThread {
                val r=runCatching { URL("https://api.ipify.org").readText() }.getOrElse { it.message ?: "error" }
                runOnUiThread { output(r) }
            }
        })
    }

internal fun MainActivity.pingTool() {
        clearPage("Ping")
        val e=edit("Host"); content.addView(e)
        content.addView(button("Ping") {
            toolThread {
                val r=runCatching {
                    val p=Runtime.getRuntime().exec(arrayOf("ping","-c","1","-W","2",e.text.toString()))
                    p.inputStream.bufferedReader().readText()
                }.getOrElse { "Ping error: ${it.message}" }
                runOnUiThread { output(r) }
            }
        })
    }

internal fun MainActivity.portTool() {
        clearPage("Port Checker")
        val host=edit("Host"); val port=edit("Port"); content.addView(host); content.addView(port)
        content.addView(button("Check") {
            toolThread {
                val r=runCatching { Socket().use { it.connect(InetSocketAddress(host.text.toString(), port.text.toString().toInt()), 2500); "OPEN" } }.getOrElse { "CLOSED / ERROR: ${it.message}" }
                runOnUiThread { output(r) }
            }
        })
    }

internal fun MainActivity.dnsTool() {
        clearPage("DNS Lookup")
        val e=edit("domain"); content.addView(e)
        content.addView(button("Lookup") {
            toolThread { val r=runCatching { InetAddress.getAllByName(e.text.toString()).joinToString("\n") { it.hostAddress ?: "" } }.getOrElse { it.message ?: "error" }; runOnUiThread { output(r) } }
        })
    }

internal fun MainActivity.reverseDnsTool() {
        clearPage("Reverse DNS")
        val e=edit("IP"); content.addView(e)
        content.addView(button("Lookup") {
            toolThread { val r=runCatching { InetAddress.getByName(e.text.toString()).canonicalHostName }.getOrElse { it.message ?: "error" }; runOnUiThread { output(r) } }
        })
    }

internal fun MainActivity.ipInfoTool() {
        clearPage("IP Address Info")
        val e=edit("IP"); content.addView(e)
        content.addView(button("Analyze") {
            output(runCatching {
                val ip=InetAddress.getByName(e.text.toString())
                val raw=ip.address
                "Host: ${ip.hostAddress}\nLoopback: ${ip.isLoopbackAddress}\nLink-local: ${ip.isLinkLocalAddress}\nSite-local: ${ip.isSiteLocalAddress}\nBytes: ${raw.joinToString(".") { (it.toInt() and 255).toString() }}"
            }.getOrElse { "IP tidak valid" })
        })
    }

internal fun MainActivity.sslTool() {
        clearPage("SSL Certificate")
        val e=edit("example.com:443"); content.addView(e)
        content.addView(button("Check") {
            toolThread {
                val r=runCatching {
                    val p=e.text.toString().split(":")
                    val host=p[0]; val port=p.getOrNull(1)?.toIntOrNull() ?: 443
                    val ctx=javax.net.ssl.SSLContext.getDefault()
                    val sock=ctx.socketFactory.createSocket() as javax.net.ssl.SSLSocket
                    sock.connect(InetSocketAddress(host,port),5000); sock.startHandshake()
                    val cert=sock.session.peerCertificates.firstOrNull()
                    sock.close()
                    cert?.toString() ?: "No certificate"
                }.getOrElse { "SSL error: ${it.message}" }
                runOnUiThread { output(r) }
            }
        })
    }

internal fun MainActivity.restApiClientTool() {
        clearPage("REST / API Client")
        toolWorkspace("REST / API Client", "Kirim request HTTP dan periksa status, header, serta body respons.", "api")
        toolWorkspaceSection("REQUEST", "Tentukan method dan endpoint terlebih dahulu.")
        val method = Spinner(this).apply { adapter = ArrayAdapter(this@restApiClientTool, android.R.layout.simple_spinner_dropdown_item, arrayOf("GET","POST","PUT","PATCH","DELETE","HEAD")) }
        val url = edit("https://example.com/api")
        val headers = edit("Headers (satu per baris: Name: Value)")
        headers.minLines = 3
        val body = edit("Request body (JSON/text)")
        body.minLines = 5
        content.addView(method); content.addView(url); content.addView(headers); content.addView(body)
        val status = toolStatus("Siap", false); content.addView(status)
        val send = button("Kirim Request") {}
        content.addView(send)
        send.setOnClickListener {
            val target = url.text.toString().trim()
            if (target.isBlank()) { toast("URL wajib diisi"); return@setOnClickListener }
            send.isEnabled = false; status.text = "Mengirim…"
            toolThread {
                val result = runCatching {
                    val conn = URL(target).openConnection() as HttpURLConnection
                    conn.requestMethod = method.selectedItem.toString()
                    conn.connectTimeout = 12000; conn.readTimeout = 15000
                    conn.instanceFollowRedirects = true
                    headers.text.toString().lines().forEach { line ->
                        val i = line.indexOf(':')
                        if (i > 0) conn.setRequestProperty(line.substring(0,i).trim(), line.substring(i+1).trim())
                    }
                    val m = conn.requestMethod
                    if (m in setOf("POST","PUT","PATCH","DELETE")) {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body.text.toString().toByteArray(StandardCharsets.UTF_8)) }
                    }
                    val code = conn.responseCode
                    val stream = if (code >= 400) conn.errorStream else conn.inputStream
                    val responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                    val hs = conn.headerFields.entries.filter { it.key != null }.joinToString("\n") { (k,v) -> "$k: ${v?.joinToString("; ") ?: ""}" }
                    conn.disconnect()
                    "HTTP $code\n\nHeaders:\n$hs\n\nBody:\n${responseBody.take(50000)}"
                }.getOrElse { "Request gagal: ${it.javaClass.simpleName}: ${it.message ?: "unknown error"}" }
                runOnUiThread { send.isEnabled = true; status.text = if (result.startsWith("Request gagal")) "Gagal" else "Selesai"; output(result) }
            }
        }
    }
internal fun MainActivity.networkScannerTool() {
        clearPage("Network Scanner")
        toolWorkspace("Network Scanner", "Cari host dan port TCP terbuka pada subnet lokal.", "magnify-scan")
        toolWorkspaceSection("SCAN CONFIG", "Tentukan subnet dan daftar port sebelum memulai scan.")
        val subnet = edit("Contoh 192.168.1.0/24")
        val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ip = wm.connectionInfo.ipAddress
        val defaultSubnet = if (ip != 0) {
            val a = ip and 255; val b = ip shr 8 and 255; val c = ip shr 16 and 255
            "$a.$b.$c.0/24"
        } else "192.168.1.0/24"
        subnet.setText(defaultSubnet)
        content.addView(subnet)
        val ports = edit("Port: 80,443,8080,22,21,53,139,445")
        ports.setText("80,443,8080,22,21,53,139,445")
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
                runOnUiThread {
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
        val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ip = wm.connectionInfo.ipAddress
        val ipText = if (ip == 0) "Tidak terhubung" else listOf(ip and 255, ip shr 8 and 255, ip shr 16 and 255, ip shr 24 and 255).joinToString(".")
        infoRow("Wi-Fi IP", ipText)
    }
internal fun MainActivity.networkCenterTool() {
        clearPage("Network Center")
        toolWorkspace("Network Center", "Ringkasan koneksi, interface, internet, dan alamat jaringan perangkat.", "lan-connect")
        toolWorkspaceSection("NETWORK STATUS", "Informasi dibaca langsung dari sistem Android.")
        val cm=getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
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
