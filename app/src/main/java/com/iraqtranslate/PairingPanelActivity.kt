package com.iraqtranslate

import android.app.Activity
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.Executors

/** Real mDNS pairing/connect discovery; uses the existing TLS ADB bridge, not guessed shell commands. */
class PairingPanelActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var address: EditText
    private lateinit var pairingPort: EditText
    private lateinit var connectPort: EditText
    private lateinit var pairingCode: EditText
    private lateinit var status: TextView
    private lateinit var nsd: NsdManager
    private var wifiLock: WifiManager.MulticastLock? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var discoveryActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val savedHost = getSharedPreferences("iraq_hub", MODE_PRIVATE).getString("adb_host", "") ?: ""
        val savedConnectPort = getSharedPreferences("iraq_hub", MODE_PRIVATE).getInt("adb_port", 0)
        val root = HubUi.root(this, "إقران التلفاز", "فعّل Wireless debugging من خيارات المطوّر في التلفاز. منفذ الإقران مختلف عن منفذ الاتصال")
        val panel = HubUi.panel(this, "PairingPanel • اتصال محلي آمن")
        address = field("عنوان IP للتلفاز", savedHost)
        pairingPort = field("منفذ الاقتران", "", true)
        connectPort = field("منفذ الاتصال", if (savedConnectPort == 0) "" else savedConnectPort.toString(), true)
        pairingCode = field("رمز الاقتران المكوّن من 6 أرقام", "", true)
        status = HubUi.label(this, "لم يبدأ اكتشاف الأجهزة بعد", 14f, HubUi.MUTED)
        panel.addView(status)
        listOf(address, pairingPort, connectPort, pairingCode).forEach { panel.addView(it) }
        panel.addView(HubUi.action(this, "اكتشاف منافذ ADB عبر mDNS") { discover() })
        panel.addView(HubUi.action(this, "إقران التلفاز") { pair() })
        panel.addView(HubUi.action(this, "الاتصال بالتلفاز") { connect() })
        panel.addView(HubUi.action(this, "فصل الاتصال") {
            io.execute {
                runCatching { AdbBridge.get(this).disconnect() }
                runOnUiThread { status.text = "تم فصل اتصال ADB" }
            }
        })
        root.addView(panel)
    }

    private fun field(hint: String, initial: String, number: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setText(initial)
        setSingleLine(true)
        setTextColor(HubUi.WHITE)
        setHintTextColor(HubUi.MUTED)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        textDirection = android.view.View.TEXT_DIRECTION_LTR
        layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
    }

    private fun discover() {
        if (discoveryActive) {
            status.text = "يجري البحث بالفعل…"
            return
        }
        discoveryActive = true
        status.text = "يبحث عن _adb-tls-pairing و _adb-tls-connect على شبكة التلفاز…"
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        runCatching {
            wifiLock = wifi?.createMulticastLock("iraqtranslate-nsd")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        discoverType("_adb-tls-pairing._tcp.", isPairing = true)
        discoverType("_adb-tls-connect._tcp.", isPairing = false)
    }

    private fun discoverType(type: String, isPairing: Boolean) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = update("تعذر اكتشاف $serviceType ($errorCode)")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip = info.host?.hostAddress ?: return
                        runOnUiThread {
                            address.setText(ip.substringBefore('%'))
                            if (isPairing) pairingPort.setText(info.port.toString())
                            else connectPort.setText(info.port.toString())
                            status.text = "تم العثور على ${info.serviceName}: $ip:${info.port}"
                        }
                    }
                })
            }
        }
        listeners += listener
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { update("فشل اكتشاف $type: ${it.message}") }
    }

    private fun pair() {
        val host = address.text.toString().trim()
        val port = pairingPort.text.toString().toIntOrNull()
        val code = pairingCode.text.toString().trim()
        if (host.isBlank() || port !in 1..65535 || !code.matches(Regex("\\d{6}"))) {
            status.text = "أدخل IP صحيحاً ومنفذ الاقتران ورمزاً من 6 أرقام"
            return
        }
        status.text = "جارٍ الاقتران…"
        io.execute {
            val output = runCatching { AdbBridge.get(this).pair(host, port!!, code) }.fold(
                onSuccess = { if (it) "نجح الاقتران. استخدم منفذ الاتصال الآن" else "رفض التلفاز الاقتران" },
                onFailure = { "فشل الاقتران: ${it.message}" }
            )
            runOnUiThread { pairingCode.text.clear(); status.text = output }
        }
    }
    private fun connect() {
        val host = address.text.toString().trim()
        val port = connectPort.text.toString().toIntOrNull()
        if (host.isBlank() || port !in 1..65535) {
            status.text = "أدخل IP ومنفذ اتصال ADB الصحيح"
            return
        }
        status.text = "جارٍ الاتصال…"
        io.execute {
            val output = runCatching {
                val adb = AdbBridge.get(this)
                if (adb.connect(host, port!!)) {
                    getSharedPreferences("iraq_hub", MODE_PRIVATE).edit()
                        .putString("adb_host", host).putInt("adb_port", port).apply()
                    "تم الاتصال بنجاح • ${adb.deviceSummary()}"
                } else "تعذر الاتصال بمنفذ ADB"
            }.getOrElse { "خطأ الاتصال: ${it.message}" }
            runOnUiThread { status.text = output }
        }
    }
    private fun update(message: String) { runOnUiThread { status.text = message } }
    override fun onStop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        runCatching { wifiLock?.release() }
        wifiLock = null
        discoveryActive = false
        super.onStop()
    }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
