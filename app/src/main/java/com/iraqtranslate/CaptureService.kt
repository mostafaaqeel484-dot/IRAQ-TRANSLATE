package com.iraqtranslate

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.iraqtranslate.START"
        const val ACTION_STOP = "com.iraqtranslate.STOP"
        const val ACTION_START_ADB = "com.iraqtranslate.START_ADB"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "iraq_translate_capture"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "IRAQ_TRANSLATE"
        private const val PREFS = "iraq_translate"
        private const val PREF_AUTO_LANGUAGE = "auto_language"
        private const val LID_SECONDS = 5
    }

    private data class CaptureInput(val audioRecord: AudioRecord, val sampleRate: Int, val channels: Int)
    private data class CapturedPcmChunk(
        val pcm: ShortArray?,
        val captureStartElapsedMs: Long,
        val terminal: Boolean = false
    )
    private fun newPcmQueue() = ArrayBlockingQueue<CapturedPcmChunk>(50) // up to ~10 seconds at 200 ms/chunk
    inner class LocalBinder : Binder() { fun service(): CaptureService = this@CaptureService }
    private val localBinder = LocalBinder()

    @Volatile private var running = false
    @Volatile private var usingMediaProjection = false
    @Volatile private var captureGeneration = 0L
    private var projection: MediaProjection? = null
    private var captureInput: CaptureInput? = null
    private var adbSession: AdbBridge.EchoReferenceSession? = null
    private var worker: Thread? = null // consumer / DSP worker
    @Volatile private var captureProducer: Thread? = null
    @Volatile private var pcmQueue: ArrayBlockingQueue<CapturedPcmChunk>? = null
    @Volatile private var captureQueueFailure: String? = null
    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    private var languageIdentifier: SherpaLanguageIdentifier? = null
    private var resampler: Fir63Resampler? = null
    private val translator = TranslationEngine()
    private val subtitleManager by lazy { AutomaticSubtitleManager(this) }
    private val subtitleLookupExecutor = Executors.newSingleThreadExecutor()
    private val mediaSessionPoller = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var mediaSessionPollFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val subtitleTickerHandler = Handler(Looper.getMainLooper())
    private val subtitleTicker = object : Runnable {
        override fun run() {
            if (running) {
                if (subtitleManager.activeSubtitle() != null) {
                    val cue = subtitleManager.cueAt(SystemClock.elapsedRealtime())
                    if (cue.isNullOrBlank()) OverlayController.clear()
                    else OverlayController.update(this@CaptureService, cue)
                }
                subtitleTickerHandler.postDelayed(this, 250L)
            }
        }
    }
    private val translationExecutor = Executors.newSingleThreadExecutor()
    private val lastPartialTranslationAt = AtomicLong(0L)
    private val subtitleSynchronizer = SubtitleSynchronizer()
    private val audioFingerprintEngine = AudioFingerprintEngine(sampleRate = 16_000)
    @Volatile private var playbackClock: PlaybackClock? = null
    @Volatile private var activeSourceLanguage: String = "en"
    @Volatile private var recognitionStartElapsedMs: Long = 0L
    @Volatile private var lastPcmCaptureElapsedMs: Long = 0L
    @Volatile private var recognizerClockStarted: Boolean = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system/user")
            stopCapture()
        }
    }

    /**
     * Player integration hook. Call with the presentation timestamp of a decoded/rendered video frame,
     * in microseconds, and the elapsed-realtime observation time. This is the only path that supplies
     * genuine decoder PTS; MediaSession and AudioRecord timestamps must not be passed here.
     */
    fun onDecoderVideoFramePts(ptsUs: Long, playbackSpeed: Double, playing: Boolean) {
        subtitleManager.updateDecoderVideoPts(
            ptsUs = ptsUs,
            observedElapsedMs = SystemClock.elapsedRealtime(),
            speed = playbackSpeed,
            playing = playing
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START_ADB -> {
                if (Build.VERSION.SDK_INT < 34) {
                    Log.w(TAG, "ADB Echo Reference foreground mode requires Android 14+ specialUse service type")
                    stopSelf()
                } else {
                    startAsForeground(notification("Starting ADB Echo Reference capture"), mediaProjection = false)
                    startAdbCapture()
                }
            }
            ACTION_START -> {
                startAsForeground(notification("Preparing IPTV playback audio capture"))
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultCode == Activity.RESULT_OK && data != null) startCapture(resultCode, data)
                else stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(notification: Notification, mediaProjection: Boolean = true) {
        if (mediaProjection && Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else if (!mediaProjection && Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (running) stopCapture()
        val generation = ++captureGeneration
        try {
            check(Build.VERSION.SDK_INT >= 29) { "Android 10+ playback capture is required" }
            val installer = SpeechModelInstaller(this)
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val autoLanguage = prefs.getBoolean(PREF_AUTO_LANGUAGE, true)
            if (autoLanguage) {
                val lidInstaller = LanguageIdModelInstaller(this)
                check(lidInstaller.isInstalled()) {
                    "Download the Sherpa-ONNX Whisper language-identification model first"
                }
                languageIdentifier = SherpaLanguageIdentifier(
                    lidInstaller.encoderPath(), lidInstaller.decoderPath()
                )
            } else {
                val selected = installer.selectedModel()
                check(installer.isInstalled(selected)) {
                    "Selected ${selected.label} Vosk model is not installed"
                }
                loadVoskModel(selected)
            }

            usingMediaProjection = true
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val p = projection ?: error("MediaProjection unavailable")
            p.registerCallback(projectionCallback, null)

            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            captureInput = createPlaybackRecorder(config)
            playbackClock = PlaybackClock(captureInput!!.sampleRate)
            resampler = if (captureInput?.channels == 2) Fir63Resampler() else null

            running = true
        subtitleTickerHandler.removeCallbacks(subtitleTicker)
        subtitleTickerHandler.post(subtitleTicker)
            captureInput!!.audioRecord.startRecording()
            if (!autoLanguage) recognitionStartElapsedMs = SystemClock.elapsedRealtime()
            check(captureInput!!.audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Playback AudioRecord did not enter recording state"
            }
            val captureDescription = if (captureInput!!.channels == 2)
                "48 kHz stereo playback capture → 63-tap FIR → 16 kHz Vosk"
            else "16 kHz mono playback capture fallback"
            startAsForeground(notification(if (autoLanguage) {
                "Detecting TV speech language; playback audio only"
            } else {
                "${installer.selectedModel().label} recognition; playback audio only"
            }))
            if (Settings.canDrawOverlays(this)) OverlayController.show(this)
            OverlayController.update(this, if (autoLanguage) "جارٍ تحديد لغة صوت التلفزيون…" else captureDescription)
            worker = Thread({ captureLoop(autoLanguage, generation) }, "iraq-translate-playback-audio").apply { start() }
        } catch (t: Throwable) {
            Log.e(TAG, "Playback capture start failed", t)
            if (Settings.canDrawOverlays(this)) {
                OverlayController.update(this, "تعذر بدء التقاط صوت تشغيل التلفزيون: ${t.message ?: "خطأ غير معروف"}")
            }
            stopCapture()
        }
    }

    /** Prefer the reference's shell Echo Reference path; it never opens a microphone input. */
    private fun startAdbCapture() {
        if (Build.VERSION.SDK_INT < 34) {
            if (Settings.canDrawOverlays(this)) {
                OverlayController.update(this, "مسار ADB Echo Reference يتطلب Android 14 أو أحدث؛ استخدم مسار الالتقاط الاحتياطي")
            }
            stopSelf()
            return
        }
        if (running) stopCapture()
        val generation = ++captureGeneration
        running = true
        subtitleTickerHandler.removeCallbacks(subtitleTicker)
        subtitleTickerHandler.post(subtitleTicker)
        worker = Thread({
            try {
                val installer = SpeechModelInstaller(this)
                val autoLanguage = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PREF_AUTO_LANGUAGE, true)
                if (autoLanguage) {
                    val lidInstaller = LanguageIdModelInstaller(this)
                    check(lidInstaller.isInstalled()) {
                        "Download the Sherpa-ONNX Whisper language-identification model first"
                    }
                    languageIdentifier = SherpaLanguageIdentifier(
                        lidInstaller.encoderPath(), lidInstaller.decoderPath()
                    )
                } else {
                    val selected = installer.selectedModel()
                    check(installer.isInstalled(selected)) {
                        "Selected ${selected.label} Vosk model is not installed"
                    }
                    loadVoskModel(selected)
                }
                check(AdbBridge.get(this).isConnected()) { "Connect ADB to the TV first" }
                adbSession = AdbBridge.get(this).openEchoReferenceSession()
                startMediaSessionPolling(generation)
                if (!running || generation != captureGeneration) {
                    adbSession?.close()
                    adbSession = null
                    return@Thread
                }
                usingMediaProjection = false
                if (!autoLanguage) recognitionStartElapsedMs = SystemClock.elapsedRealtime()
                resampler = Fir63Resampler()
                startAsForeground(
                    notification("ADB Echo Reference 48 kHz PCM32 stereo → FIR-63 → 16 kHz speech"),
                    mediaProjection = false
                )
                if (Settings.canDrawOverlays(this)) OverlayController.show(this)
                OverlayController.update(this, "تم تشغيل مسار ADB Echo Reference — التقاط صوت الخرج فقط")
                captureAdbLoop(autoLanguage, generation)
            } catch (t: Throwable) {
                Log.e(TAG, "ADB Echo Reference start failed", t)
                if (Settings.canDrawOverlays(this)) {
                    OverlayController.update(this, "تعذر تشغيل Echo Reference عبر ADB: ${t.message ?: "صلاحية النظام غير متاحة"}")
                }
                if (generation == captureGeneration) stopCapture()
            }
        }, "iraq-translate-adb-startup").apply { start() }
    }

    private fun startMediaSessionPolling(generation: Long) {
        if (mediaSessionPollFuture != null) return
        mediaSessionPollFuture = mediaSessionPoller.scheduleWithFixedDelay({
            if (!running || generation != captureGeneration || !AdbBridge.get(this).isConnected()) return@scheduleWithFixedDelay
            val now = SystemClock.elapsedRealtime()
            val position = runCatching { AdbBridge.get(this).readMediaSessionPosition() }.getOrNull()
            if (position != null) {
                subtitleManager.updatePlayerPosition(position.positionMs, position.speed, position.state, now)
                Log.d(TAG, "MediaSession clock: ${position.packageName} state=${position.state} pos=${position.positionMs} speed=${position.speed}")
            } else subtitleManager.expirePlayerPosition(now)
        }, 0, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Prefer 48 kHz stereo playback capture with a bounded 200 ms read block; fall back to 16 kHz mono. */
    private fun createPlaybackRecorder(config: AudioPlaybackCaptureConfiguration): CaptureInput {
        var highRate: AudioRecord? = null
        try {
            val min = AudioRecord.getMinBufferSize(
                48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(min > 0) { "48 kHz stereo PCM16 is not supported ($min)" }
            highRate = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(38_400, min * 2))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            check(highRate.state == AudioRecord.STATE_INITIALIZED) { "48 kHz stereo AudioRecord initialization failed" }
            return CaptureInput(highRate, 48000, 2)
        } catch (t: Throwable) {
            runCatching { highRate?.release() }
            Log.w(TAG, "48 kHz stereo path unavailable; using 16 kHz mono playback capture", t)
        }

        val min = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(min > 0) { "16 kHz mono PCM16 is not supported ($min)" }
        val fallback = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, 32000))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(fallback.state == AudioRecord.STATE_INITIALIZED) { "16 kHz mono playback AudioRecord initialization failed" }
        return CaptureInput(fallback, 16000, 1)
    }

    private fun captureLoop(autoLanguage: Boolean, generation: Long) {
        val input = captureInput ?: return
        val queue = newPcmQueue()
        pcmQueue = queue
        captureQueueFailure = null
        captureProducer = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                val raw = ByteArray(if (input.channels == 2) 38_400 else 6_400)
                while (running && generation == captureGeneration) {
                    val count = try {
                        input.audioRecord.read(raw, 0, raw.size, AudioRecord.READ_BLOCKING)
                    } catch (t: Throwable) {
                        if (running) Log.e(TAG, "Playback audio read failed", t)
                        -1
                    }
                    if (count < 0) break
                    if (count == 0) continue
                    val stamp = runCatching { playbackClock?.observe(input.audioRecord) }.getOrNull()
                    val pcm = if (input.channels == 2) resampler?.processStereoPcm16(raw, count) ?: ShortArray(0)
                        else bytesToShorts(raw, count)
                    if (pcm.isEmpty()) continue
                    val durationMs = pcm.size * 1000L / 16_000L
                    val captureStart = if (stamp != null) {
                        if (stamp.discontinuity) Log.w(TAG, "AudioRecord capture-clock discontinuity; timestamp origin recalibrated")
                        val inputFrames = count / (2 * input.channels)
                        val inputDurationMs = inputFrames * 1000L / input.sampleRate
                        ((playbackClock?.elapsedMsForFrame(stamp.framePosition) ?: SystemClock.elapsedRealtime()) - inputDurationMs).coerceAtLeast(0L)
                    } else (SystemClock.elapsedRealtime() - durationMs).coerceAtLeast(0L)
                    if (!queue.offer(CapturedPcmChunk(pcm, captureStart))) {
                        captureQueueFailure = "PCM queue overrun: processing could not keep up with live playback; session stopped to prevent timestamp corruption"
                        Log.e(TAG, captureQueueFailure!!)
                        running = false
                        break
                    }
                }
            } catch (t: Throwable) {
                captureQueueFailure = "Audio capture producer failed: ${t.message ?: t.javaClass.simpleName}"
                Log.e(TAG, captureQueueFailure!!, t)
            } finally {
                val terminal = CapturedPcmChunk(null, SystemClock.elapsedRealtime(), terminal = true)
                while (running && generation == captureGeneration && !queue.offer(terminal)) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }
        }, "iraq-translate-pcm-capture").apply { start() }
        processAudio(autoLanguage, generation, queue)
    }

    private fun captureAdbLoop(autoLanguage: Boolean, generation: Long) {
        val session = adbSession ?: return
        val reader = Pcm32StereoTo16kReader(session.input, resampler ?: Fir63Resampler())
        val queue = newPcmQueue()
        pcmQueue = queue
        captureQueueFailure = null
        captureProducer = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                while (running && generation == captureGeneration) {
                    val pcm = try { reader.read() } catch (t: Throwable) {
                        if (running) Log.e(TAG, "ADB Echo Reference stream read failed", t)
                        null
                    } ?: break
                    if (pcm.isEmpty()) continue
                    val durationMs = pcm.size * 1000L / 16_000L
                    val captureStart = (SystemClock.elapsedRealtime() - durationMs).coerceAtLeast(0L)
                    if (!queue.offer(CapturedPcmChunk(pcm, captureStart))) {
                        captureQueueFailure = "PCM queue overrun: ADB Echo Reference capture stopped to prevent silent audio loss"
                        Log.e(TAG, captureQueueFailure!!)
                        running = false
                        break
                    }
                }
            } catch (t: Throwable) {
                captureQueueFailure = "ADB capture producer failed: ${t.message ?: t.javaClass.simpleName}"
                Log.e(TAG, captureQueueFailure!!, t)
            } finally {
                val terminal = CapturedPcmChunk(null, SystemClock.elapsedRealtime(), terminal = true)
                while (running && generation == captureGeneration && !queue.offer(terminal)) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }
        }, "iraq-translate-adb-pcm-capture").apply { start() }
        processAudio(autoLanguage, generation, queue)
    }

    private fun processAudio(
        autoLanguage: Boolean,
        generation: Long,
        queue: ArrayBlockingQueue<CapturedPcmChunk>
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        val languageSamples = if (autoLanguage) FloatArray(16000 * LID_SECONDS) else null
        var languageSampleCount = 0
        var languageDetected = !autoLanguage
        val vad = VoiceActivityDetector(sampleRate = 16_000)
        audioFingerprintEngine.reset()
        var sessionStartElapsedMs = 0L
        subtitleManager.clear()
        val catalogueEntries = runCatching { subtitleManager.loadReferenceFingerprintCatalogue() }.getOrDefault(0)
        Log.i(TAG, "Reference fingerprint catalogue entries=$catalogueEntries")

        while (running && generation == captureGeneration) {
            val packet = try { queue.poll(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { null }
            if (packet == null) continue
            if (packet.terminal) break
            val pcm = packet.pcm ?: continue
            if (pcm.isEmpty()) continue
            lastPcmCaptureElapsedMs = packet.captureStartElapsedMs
            if (sessionStartElapsedMs == 0L) {
                sessionStartElapsedMs = packet.captureStartElapsedMs
                subtitleManager.setSessionStart(sessionStartElapsedMs)
            }

            audioFingerprintEngine.accept(pcm).forEach { observation ->
                if (observation.repeated) Log.d(TAG, "Repeated acoustic fingerprint detected (${"%.2f".format(observation.similarity)})")
                subtitleManager.observeReferenceFingerprint(observation.fingerprint)?.let { match ->
                    val applied = subtitleManager.applyReferenceFingerprintMatch(match)
                    Log.i(TAG, "Reference audio match: media=${match.mediaId}, offset=${match.offsetMs}ms, hits=${match.hits}, confidence=${"%.2f".format(match.confidence)}, coverage=${"%.2f".format(match.coverage)}, applied=$applied")
                }
            }
            vad.accept(pcm).forEach { segment ->
                val startMs = sessionStartElapsedMs + segment.startSample * 1000L / 16_000L
                val endMs = sessionStartElapsedMs + segment.endSample * 1000L / 16_000L
                subtitleManager.addSpeechSegment(startMs, endMs)
            }

            if (!languageDetected) {
                val lidBuffer = languageSamples ?: break
                val floatSamples = pcm.toFloatSamples()
                val meanSquare = floatSamples.fold(0.0) { total, sample -> total + sample * sample } /
                    floatSamples.size.coerceAtLeast(1)
                if (meanSquare < 0.000012) continue
                val copy = minOf(floatSamples.size, lidBuffer.size - languageSampleCount)
                floatSamples.copyInto(lidBuffer, languageSampleCount, 0, copy)
                languageSampleCount += copy
                if (languageSampleCount >= lidBuffer.size) {
                    try {
                        val detected = languageIdentifier?.detect(lidBuffer, 16000).orEmpty()
                        val spec = SpeechModelInstaller(this).modelForLanguage(detected)
                        if (spec == null) {
                            OverlayController.update(this, "تم كشف اللغة ($detected) لكن لا يوجد نموذج Vosk مطابق لها")
                            Log.w(TAG, "No Vosk model mapped for detected language '$detected'")
                            break
                        }
                        if (!SpeechModelInstaller(this).isInstalled(spec)) {
                            OverlayController.update(this, "تم كشف ${spec.label}. نزّل نموذج هذه اللغة ثم أعد التشغيل")
                            Log.w(TAG, "Detected model is not installed: ${spec.directory}")
                            break
                        }
                        loadVoskModel(spec)
                        languageDetected = true
                        startAsForeground(notification("${spec.label} detected; translating playback to Arabic"), mediaProjection = usingMediaProjection)
                        OverlayController.update(this, "تم تحديد اللغة: ${spec.label} — بدء الترجمة")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Sherpa language identification failed", t)
                        OverlayController.update(this, "فشل تحديد لغة الصوت: ${t.message ?: "خطأ"}")
                        break
                    }
                }
                continue
            }
            runRecognition(pcm)
        }

        vad.flush().forEach { segment ->
            if (sessionStartElapsedMs > 0L) {
                val startMs = sessionStartElapsedMs + segment.startSample * 1000L / 16_000L
                val endMs = sessionStartElapsedMs + segment.endSample * 1000L / 16_000L
                subtitleManager.addSpeechSegment(startMs, endMs)
            }
        }
        if (generation == captureGeneration) {
            captureQueueFailure?.let { OverlayController.update(this, it) }
            if (running) {
                runCatching {
                    val finalJson = recognizer?.finalResult.orEmpty()
                    val finalSpeech = parseSpeechResult(finalJson)
                    if (finalSpeech.text.isNotBlank()) {
                        translateAndDisplay(finalSpeech.text, finalSpeech.captureElapsedMs, hasReliableTimestamp = finalSpeech.hasWordTiming)
                    }
                }
            }
            stopCapture()
        }
    }

    private fun loadVoskModel(spec: SpeechModelSpec) {
        val loaded = Model(SpeechModelInstaller(this).modelDirectory(spec).absolutePath)
        val newRecognizer = Recognizer(loaded, 16000.0f)
        newRecognizer.setWords(true)
        val oldRecognizer = recognizer
        val oldModel = model
        recognizer = newRecognizer
        model = loaded
        activeSourceLanguage = spec.sourceLanguage
        recognizerClockStarted = false
        recognitionStartElapsedMs = 0L
        runCatching { oldRecognizer?.close() }
        runCatching { oldModel?.close() }
    }

    private fun runRecognition(pcm: ShortArray) {
        try {
            val r = recognizer ?: return
            if (!recognizerClockStarted) {
                recognitionStartElapsedMs = lastPcmCaptureElapsedMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
                recognizerClockStarted = true
            }
            val bytes = pcm.toPcm16Le()
            if (r.acceptWaveForm(bytes, bytes.size)) {
                val spoken = parseSpeechResult(r.result)
                if (spoken.text.isNotBlank()) translateAndDisplay(
                    spoken.text, spoken.captureElapsedMs, hasReliableTimestamp = spoken.hasWordTiming
                )
            } else {
                val partial = parseSpeechResult(r.partialResult).text
                val now = System.currentTimeMillis()
                val previous = lastPartialTranslationAt.get()
                if (partial.isNotBlank() && now - previous >= 2500 &&
                    lastPartialTranslationAt.compareAndSet(previous, now)) {
                    translateAndDisplay(partial, lastPcmCaptureElapsedMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime(), isFinalResult = false)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Vosk recognition failed", t)
        }
    }

    private fun translateAndDisplay(
        spoken: String,
        audioElapsedMs: Long = SystemClock.elapsedRealtime(),
        isFinalResult: Boolean = true,
        hasReliableTimestamp: Boolean = true
    ) {
        val input = ArabicTextNormalizer.displayText(spoken)
        if (input.isEmpty()) return
        translationExecutor.execute {
            try {
                val sourceLanguage = activeSourceLanguage
                val arabic = if (sourceLanguage == "ar") input
                else translator.toArabic(input, sourceLanguage)
                if (arabic.isBlank() || !running) return@execute

                // Feed translated Arabic speech to the candidate validator before subtitle discovery.
                if (isFinalResult && hasReliableTimestamp) {
                    subtitleManager.observeDialogueForCandidate(arabic)
                    subtitleLookupExecutor.execute {
                        runCatching {
                            val message = subtitleManager.identifyAndLoad(input, sourceLanguage, audioElapsedMs)
                            if (!message.isNullOrBlank()) {
                                Log.i(TAG, "Automatic subtitle: $message")
                                if (running && subtitleManager.activeSubtitle() != null) {
                                    OverlayController.update(this, message)
                                }
                            }
                        }.onFailure { Log.w(TAG, "Automatic subtitle discovery failed", it) }
                    }
                }

                val external = subtitleManager.activeSubtitle()
                if (external != null) {
                    if (!isFinalResult || !hasReliableTimestamp) return@execute
                    val confidence = subtitleManager.observeTranslatedDialogue(arabic, audioElapsedMs)
                    Log.d(TAG, "Subtitle word alignment confidence=${"%.3f".format(confidence)}; ${subtitleManager.alignmentStatus()}")
                    subtitleManager.cueAt(SystemClock.elapsedRealtime())?.let { OverlayController.update(this, it) }
                    return@execute
                }
                val caption = subtitleSynchronizer.append(arabic, audioElapsedMs)
                Log.i(TAG, "Arabic caption: $caption")
                OverlayController.update(this, caption)
            } catch (t: Throwable) {
                Log.e(TAG, "Translation/alignment failed", t)
                if (running && subtitleManager.activeSubtitle() == null) OverlayController.update(this, "تعذر الاتصال بخدمة الترجمة")
            }
        }
    }

    private data class ParsedSpeech(val text: String, val captureElapsedMs: Long, val hasWordTiming: Boolean)

    private fun parseSpeechResult(json: String): ParsedSpeech = try {
        val objectValue = JSONObject(json)
        val text = ArabicTextNormalizer.displayText(
            objectValue.optString("text", objectValue.optString("partial", ""))
        )
        val words = objectValue.optJSONArray("result")
        val lastWord = if (words != null && words.length() > 0) words.optJSONObject(words.length() - 1) else null
        val endSeconds = lastWord?.optDouble("end", Double.NaN) ?: Double.NaN
        val hasWordTiming = endSeconds.isFinite() && endSeconds >= 0.0 && recognitionStartElapsedMs > 0L
        val timestamp = if (hasWordTiming) {
            recognitionStartElapsedMs + (endSeconds * 1000.0).toLong()
        } else lastPcmCaptureElapsedMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        ParsedSpeech(text, timestamp, hasWordTiming)
    } catch (t: Throwable) {
        Log.w(TAG, "Could not parse Vosk result JSON", t)
        ParsedSpeech("", SystemClock.elapsedRealtime(), false)
    }

    private fun bytesToShorts(bytes: ByteArray, count: Int): ShortArray {
        val size = count.coerceAtMost(bytes.size) / 2
        return ShortArray(size) { i ->
            ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }

    @Synchronized
    private fun stopCapture() {
        running = false
        mediaSessionPollFuture?.cancel(true)
        mediaSessionPollFuture = null
        subtitleTickerHandler.removeCallbacks(subtitleTicker)
        captureGeneration++
        usingMediaProjection = false
        runCatching { captureInput?.audioRecord?.stop() }
        runCatching { adbSession?.close() }
        adbSession = null
        pcmQueue?.clear()
        runCatching { captureProducer?.interrupt() }
        if (Thread.currentThread() !== captureProducer) runCatching { captureProducer?.join(1_500) }
        captureProducer = null
        pcmQueue = null
        if (Thread.currentThread() !== worker) runCatching { worker?.join(700) }
        worker = null
        captureQueueFailure = null
        runCatching { captureInput?.audioRecord?.release() }
        captureInput = null
        playbackClock?.reset()
        playbackClock = null
        lastPcmCaptureElapsedMs = 0L
        recognizerClockStarted = false
        audioFingerprintEngine.reset()
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
        runCatching { languageIdentifier?.close() }
        languageIdentifier = null
        resampler?.reset()
        resampler = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        subtitleSynchronizer.reset()
        subtitleManager.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        translationExecutor.shutdownNow()
        subtitleLookupExecutor.shutdownNow()
        mediaSessionPoller.shutdownNow()
        subtitleTickerHandler.removeCallbacks(subtitleTicker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "IRAQ TRANSLATE playback audio", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("IRAQ TRANSLATE")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }
}
