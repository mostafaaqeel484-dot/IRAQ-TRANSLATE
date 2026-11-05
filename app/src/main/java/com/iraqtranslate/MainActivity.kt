package com.iraqtranslate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    companion object {
        private const val PREFS = "iraq_translate"
        private const val PREF_AUTO_LANGUAGE = "auto_language"
    }

    private lateinit var status: TextView
    private lateinit var installer: SpeechModelInstaller
    private lateinit var sourceSpinner: Spinner
    private lateinit var autoLanguage: CheckBox
    @Volatile private var adbConnected = false
    private val io = Executors.newSingleThreadExecutor()

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestCapture()
        else status.text = "Playback-audio permission denied. The microphone is not used by this app."
    }

    private val fingerprintCatalogueLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            status.text = "Reference fingerprint import cancelled."
        } else {
            status.text = "Importing reference fingerprint catalogue..."
            io.execute {
                try {
                    val target = java.io.File(filesDir, "reference_fingerprints.csv")
                    val input = requireNotNull(contentResolver.openInputStream(uri)) { "Could not open selected CSV file" }
                    input.use { stream -> target.outputStream().use { output -> stream.copyTo(output) } }
                    require(target.length() in 1..100_000_000L) { "Catalogue file is empty or exceeds 100 MB" }
                    val rows = target.useLines { lines -> lines.count { it.isNotBlank() && !it.trim().startsWith("#") && !it.trim().startsWith("mediaId", true) } }
                    runOnUiThread { status.text = "Imported fingerprint catalogue: $rows rows. It will load on the next capture start." }
                } catch (t: Throwable) {
                    runOnUiThread { status.text = "Fingerprint catalogue import failed: ${t.message}" }
                }
            }
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            status.text = "TV playback capture started. No microphone or screen frames are captured."
        } else status.text = "Playback capture consent denied."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installer = SpeechModelInstaller(this)
        status = TextView(this).apply {
            textSize = 17f
            text = "IRAQ TRANSLATE 1.0.9\nIPTV playback audio → speech recognition → Arabic captions"
            setPadding(0, 0, 0, 16)
        }

        autoLanguage = CheckBox(this).apply {
            text = "Detect spoken language automatically (Sherpa-ONNX)"
            isChecked = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_AUTO_LANGUAGE, true)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_AUTO_LANGUAGE, checked).apply()
                status.text = if (checked) "Automatic detection enabled. Select a Vosk model below to pre-download the language likely to be detected."
                else "Manual source-language selection enabled."
            }
        }

        sourceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                SpeechModelInstaller.MODELS.map { "${it.label} (${it.id})" }
            )
            setSelection(SpeechModelInstaller.MODELS.indexOfFirst {
                it.id == installer.selectedModel().id
            }.coerceAtLeast(0))
            isEnabled = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val spec = SpeechModelInstaller.MODELS[position]
                    installer.selectModel(spec.id)
                    status.text = if (installer.isInstalled(spec)) "${spec.label} Vosk model installed."
                    else "${spec.label} Vosk model must be downloaded."
                }
            }
        }

        val installModel = Button(this).apply {
            text = "Download selected Vosk speech model"
            setOnClickListener {
                val spec = installer.selectedModel()
                if (installer.isInstalled(spec)) {
                    status.text = "${spec.label} model is already installed."
                    return@setOnClickListener
                }
                isEnabled = false
                status.text = "Downloading ${spec.label} model..."
                io.execute {
                    try {
                        val dir = installer.install(spec) { percent ->
                            runOnUiThread { status.text = "Installing ${spec.label}: $percent%" }
                        }
                        runOnUiThread {
                            isEnabled = true
                            status.text = "Vosk model installed: ${dir.name}"
                            Toast.makeText(this@MainActivity, "Speech model ready", Toast.LENGTH_LONG).show()
                        }
                    } catch (t: Throwable) {
                        runOnUiThread {
                            isEnabled = true
                            status.text = "Model installation failed: ${t.message}"
                        }
                    }
                }
            }
        }

        val installLanguageId = Button(this).apply {
            text = "Download Sherpa-ONNX language detection model"
            setOnClickListener {
                val modelInstaller = LanguageIdModelInstaller(this@MainActivity)
                if (modelInstaller.isInstalled()) {
                    status.text = "Sherpa Whisper language model is already installed."
                    return@setOnClickListener
                }
                isEnabled = false
                status.text = "Downloading Whisper tiny encoder and decoder..."
                io.execute {
                    try {
                        modelInstaller.install { name, percent ->
                            runOnUiThread { status.text = "Downloading $name: $percent%" }
                        }
                        runOnUiThread {
                            isEnabled = true
                            status.text = "Sherpa-ONNX language detection model installed."
                        }
                    } catch (t: Throwable) {
                        runOnUiThread {
                            isEnabled = true
                            status.text = "Language model download failed: ${t.message}"
                        }
                    }
                }
            }
        }

        val start = Button(this).apply {
            text = "Start ADB Echo Reference translation"
            setOnClickListener {
                val auto = autoLanguage.isChecked
                if (auto && !LanguageIdModelInstaller(this@MainActivity).isInstalled()) {
                    status.text = "Download the Sherpa-ONNX language-identification model first."
                } else if (!auto && !installer.isInstalled()) {
                    status.text = "Download the selected Vosk model first."
                } else if (Build.VERSION.SDK_INT < 34) {
                    status.text = "ADB Echo Reference mode requires Android 14+. Use the playback-capture fallback on older Android versions."
                } else if (!adbConnected) {
                    status.text = "Pair and connect ADB to the TV first."
                } else {
                    val adbIntent = Intent(this@MainActivity, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_START_ADB)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(adbIntent) else startService(adbIntent)
                    status.text = "Starting ADB Echo Reference. No microphone or screen capture is used."
                }
            }
        }
        val startPlaybackFallback = Button(this).apply {
            text = "Start Android playback-capture fallback"
            setOnClickListener {
                val auto = autoLanguage.isChecked
                if (auto && !LanguageIdModelInstaller(this@MainActivity).isInstalled()) {
                    status.text = "Download the Sherpa-ONNX language-identification model first."
                } else if (!auto && !installer.isInstalled()) {
                    status.text = "Download the selected Vosk model first."
                } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Playback-audio permission")
                        .setMessage(
                            "Android requires RECORD_AUDIO permission to authorize AudioPlaybackCapture. " +
                                "The app captures only media/IPTV playback via MediaProjection. " +
                                "It does not select MIC, record user speech, or capture screen frames."
                        )
                        .setPositiveButton("Continue") { _, _ ->
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else requestCapture()
            }
        }

        val stop = Button(this).apply {
            text = "Stop playback translation"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                status.text = "Playback translation stopped."
            }
        }
        val overlayPermission = Button(this).apply {
            text = "Grant translated-caption overlay permission"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else status.text = "Overlay permission granted. F1 then 3 toggles captions."
            }
        }
        val showOverlay = Button(this).apply {
            text = "Show / hide translated captions"
            setOnClickListener { OverlayController.toggle(this@MainActivity) }
        }
        val accessibility = Button(this).apply {
            text = "Enable F1 then 3 remote shortcut"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val adbTitle = TextView(this).apply {
            text = "ADB / Wireless debugging"
            textSize = 20f
            setPadding(0, 24, 0, 8)
        }
        val adbHelp = TextView(this).apply {
            text = "On the TV: enable Developer options → Wireless debugging. Pair using its pairing port/code, then connect using the separate ADB port. For legacy TCP debugging, connect to port 5555 after enabling adb tcpip from a trusted host."
            setPadding(0, 0, 0, 8)
        }
        val host = edit("TV IP address", "127.0.0.1", InputType.TYPE_CLASS_TEXT)
        val pairingPort = edit("Wireless debugging pairing port", "", InputType.TYPE_CLASS_NUMBER)
        val pairingCode = edit("Six-digit pairing code", "", InputType.TYPE_CLASS_NUMBER)
        val connectPort = edit("Wireless debugging connection port", "", InputType.TYPE_CLASS_NUMBER)

        val pair = Button(this).apply {
            text = "Pair ADB using pairing code"
            setOnClickListener {
                val ip = host.text.toString().trim()
                val port = pairingPort.text.toString().toIntOrNull()
                val code = pairingCode.text.toString().trim()
                if (port == null) {
                    status.text = "Enter the pairing port shown in Wireless debugging."
                    return@setOnClickListener
                }
                isEnabled = false
                io.execute {
                    try {
                        val paired = AdbBridge.get(this@MainActivity).pair(ip, port, code)
                        runOnUiThread {
                            isEnabled = true
                            status.text = if (paired) "ADB pairing successful. Connect using the separate connection port."
                            else "ADB pairing failed."
                        }
                    } catch (t: Throwable) {
                        runOnUiThread { isEnabled = true; status.text = "ADB pairing error: ${t.message}" }
                    }
                }
            }
        }
        val connect = Button(this).apply {
            text = "Connect ADB"
            setOnClickListener {
                val ip = host.text.toString().trim()
                val port = connectPort.text.toString().toIntOrNull()
                if (port == null) {
                    status.text = "Enter the ADB connection port shown in Wireless debugging."
                    return@setOnClickListener
                }
                isEnabled = false
                io.execute {
                    try {
                        val bridge = AdbBridge.get(this@MainActivity)
                        val connected = bridge.connect(ip, port)
                        adbConnected = connected || bridge.isConnected()
                        val summary = if (adbConnected) bridge.deviceSummary() else "ADB connection was not established."
                        runOnUiThread { isEnabled = true; status.text = summary }
                    } catch (t: Throwable) {
                        runOnUiThread { isEnabled = true; status.text = "ADB connection error: ${t.message}" }
                    }
                }
            }
        }
        val inspectAudio = Button(this).apply {
            text = "Inspect Echo Reference / Tuner audio route"
            setOnClickListener {
                isEnabled = false
                io.execute {
                    try {
                        val output = AdbBridge.get(this@MainActivity).inspectAudioRoute()
                        runOnUiThread { isEnabled = true; status.text = output }
                    } catch (t: Throwable) {
                        runOnUiThread { isEnabled = true; status.text = "Audio-route inspection failed: ${t.message}" }
                    }
                }
            }
        }
        val disconnectAdb = Button(this).apply {
            text = "Disconnect ADB"
            setOnClickListener {
                io.execute {
                    runCatching { AdbBridge.get(this@MainActivity).disconnect() }
                    adbConnected = false
                    runOnUiThread { status.text = "ADB disconnected." }
                }
            }
        }

        val subtitleTitle = TextView(this).apply {
            text = "Automatic movie / episode subtitles"
            textSize = 20f
            setPadding(0, 24, 0, 8)
        }
        val subtitleHelp = TextView(this).apply {
            text = "The app identifies the title/episode from playback dialogue, then searches Arabic subtitles in Wyzie, OpenSubtitles.com, SubDL and SubtitleCat. Playback VAD feeds Wyzie Synced for audio alignment and FPS correction (Wyzie Pro key required); quote timestamps provide local drift correction when Pro sync is unavailable. SubtitleCat is accessed through its public HTML search pages, so markup changes or anti-bot protection can interrupt it. Add your own API keys; do not publish them."
            setPadding(0, 0, 0, 8)
        }
        val wyzieKey = edit("Wyzie API key (Pro enables audio/FPS sync)", SubtitleKeyStore.get(this, AutomaticSubtitleManager.PREF_WYZIE_KEY), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val openSubtitlesKey = edit("OpenSubtitles.com API key", SubtitleKeyStore.get(this, AutomaticSubtitleManager.PREF_OPENSUBTITLES_KEY), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val subdlKey = edit("SubDL API key (fallback)", SubtitleKeyStore.get(this, AutomaticSubtitleManager.PREF_SUBDL_KEY), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val importFingerprintCatalogue = Button(this).apply {
            text = "Import reference audio fingerprint CSV"
            setOnClickListener {
                fingerprintCatalogueLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
            }
        }

        val saveSubtitleKeys = Button(this).apply {
            text = "Save subtitle provider keys"
            setOnClickListener {
                SubtitleKeyStore.put(this@MainActivity, AutomaticSubtitleManager.PREF_WYZIE_KEY, wyzieKey.text.toString().trim())
                SubtitleKeyStore.put(this@MainActivity, AutomaticSubtitleManager.PREF_OPENSUBTITLES_KEY, openSubtitlesKey.text.toString().trim())
                SubtitleKeyStore.put(this@MainActivity, AutomaticSubtitleManager.PREF_SUBDL_KEY, subdlKey.text.toString().trim())
                status.text = "Subtitle provider keys saved on this device. Automatic title detection is enabled during playback."
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            addView(status)
            addView(autoLanguage)
            addView(subtitleTitle)
            addView(subtitleHelp)
            addView(wyzieKey)
            addView(openSubtitlesKey)
            addView(subdlKey)
            addView(saveSubtitleKeys)
            addView(importFingerprintCatalogue)
            addView(TextView(this@MainActivity).apply {
                text = "Vosk model to download (automatic mode selects by detected language)"
                setPadding(0, 8, 0, 4)
            })
            addView(sourceSpinner)
            addView(installModel)
            addView(installLanguageId)
            addView(start)
            addView(startPlaybackFallback)
            addView(stop)
            addView(overlayPermission)
            addView(showOverlay)
            addView(accessibility)
            addView(adbTitle)
            addView(adbHelp)
            addView(host)
            addView(pairingPort)
            addView(pairingCode)
            addView(connectPort)
            addView(pair)
            addView(connect)
            addView(inspectAudio)
            addView(disconnectAdb)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun edit(hintText: String, value: String, type: Int): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = type
        setSingleLine(true)
    }

    private fun requestCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && HotkeyState.consumeKeyDown(event.keyCode)) {
            OverlayController.toggle(this)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
