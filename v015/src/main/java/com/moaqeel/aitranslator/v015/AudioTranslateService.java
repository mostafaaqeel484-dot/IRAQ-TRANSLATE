package com.moaqeel.aitranslator.v015;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.app.PendingIntent;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/** Local continuous PCM16 capture -> streaming Vosk ASR -> Arabic translation -> TV/mobile subtitle overlay. */
public final class AudioTranslateService extends Service {
    public static final String ACTION_START = "com.moaqeel.v015.AUDIO_START";
    public static final String ACTION_STOP = "com.moaqeel.v015.AUDIO_STOP";
    public static final String MODE_PLAYBACK = "playback";
    public static final String MODE_MIC = "microphone";
    private static final int NOTIF_ID = 151;
    private static final String CHANNEL = "ai_translate_audio_v015";
    private static final int SAMPLE_RATE = 16000;
    private static final int BUFFER_BYTES = 6400;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong translationRequest = new AtomicLong();
    private volatile boolean active = false;
    private Thread thread;
    private AudioRecord recording;
    private MediaProjection projection;
    private MediaProjection.Callback callback;
    private WindowManager windows;
    private TextView overlay;
    private TranslationEngine translator;
    private String language = "en";
    private String mode = MODE_MIC;
    private volatile String statusText = "";
    // Xiaomi-inspired UX only: original independent caption renderer, no vendor SDK or copied classes.
    private boolean bilingual = true;
    private boolean captionTop = false;
    private int captionSize = 28;
    private String lastOriginal = "";

    @Override public void onCreate() {
        super.onCreate();
        translator = new TranslationEngine(this);
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
            "AI Translate — live audio", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || active) return START_NOT_STICKY;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY;
        }
        mode = MODE_PLAYBACK.equals(intent.getStringExtra("mode")) ? MODE_PLAYBACK : MODE_MIC;
        SecureSettings captionPrefs = new SecureSettings(this);
        language = "ja".equals(captionPrefs.get("source", "en")) ? "ja" : "en";
        bilingual = "on".equals(captionPrefs.get("caption_bilingual", "on"));
        captionTop = "top".equals(captionPrefs.get("caption_position", "bottom"));
        try { captionSize = Math.max(18, Math.min(38, Integer.parseInt(
                captionPrefs.get("caption_size", BuildConfig.TV_MODE ? "32" : "24")))); }
        catch (NumberFormatException ignored) { captionSize = BuildConfig.TV_MODE ? 32 : 24; }
        try {
            int type = MODE_PLAYBACK.equals(mode)
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIF_ID, notification("تهيئة الترجمة الصوتية الفورية"), type);
            if (MODE_PLAYBACK.equals(mode)) {
                int code = intent.getIntExtra("result_code", 0);
                Intent data = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra("projection_data", Intent.class)
                    : intent.getParcelableExtra("projection_data");
                if (code != android.app.Activity.RESULT_OK || data == null)
                    throw new IllegalStateException("User did not authorize playback capture");
                MediaProjectionManager mgr = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
                projection = mgr.getMediaProjection(code, data);
                if (projection == null) throw new IllegalStateException("Audio projection unavailable");
                callback = new MediaProjection.Callback() {
                    @Override public void onStop() { stopSelf(); }
                };
                projection.registerCallback(callback, main);
            }
            active = true;
            showOverlay();
            thread = new Thread(this::runSpeechLoop, "live-vosk-audio-v015");
            thread.start();
            notifyStatus("الترجمة الصوتية شغالة — تجهيز النموذج...");
        } catch (Exception e) {
            notifyStatus("خطأ تشغيل الصوت: " + e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY; // Android 14 consent must not be reused after process death.
    }
    private void runSpeechLoop() {
        Model model = null;
        Recognizer recognizer = null;
        try {
            File path = new VoskModelStore(this).ensure(language, this::notifyStatus);
            if (!active) return;
            model = new Model(path.getAbsolutePath());
            recognizer = new Recognizer(model, (float) SAMPLE_RATE);
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("PCM16 16kHz not supported");
            int size = Math.max(minimum * 2, BUFFER_BYTES);
            AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(size);
            if (MODE_PLAYBACK.equals(mode)) {
                if (projection == null) throw new IllegalStateException("Missing playback projection");
                AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
                builder.setAudioPlaybackCaptureConfig(config);
            } else {
                builder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            recording = builder.build();
            if (recording.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord initialization failed");
            recording.startRecording();
            if (recording.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Audio capture was not permitted");
            notifyStatus(MODE_PLAYBACK.equals(mode)
                ? "المصدر: صوت النظام (الألعاب/الفيديو المسموح فقط)"
                : "المصدر: الميكروفون (ترجمة الصوت الخارجي)");
            byte[] chunk = new byte[3200]; // 100ms mono @ 16kHz 16-bit
            String lastPartial = "";
            long previousPartialTime = 0;
            long lastSound = android.os.SystemClock.elapsedRealtime();
            while (active && !Thread.currentThread().isInterrupted()) {
                int n = recording.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
                if (n < 0) throw new IllegalStateException("AudioRecord read error " + n);
                if (n == 0) continue;
                // Audio input may be silent if target app/TV pipeline disallows playback capture.
                long energy = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    int sample = (short)((chunk[i] & 255) | ((chunk[i+1] & 255) << 8));
                    energy += Math.abs(sample);
                }
                if ((energy / Math.max(1,n/2)) > 65) lastSound = android.os.SystemClock.elapsedRealtime();
                long now = android.os.SystemClock.elapsedRealtime();
                if (MODE_PLAYBACK.equals(mode) && now - lastSound > 12000) {
                    notifyStatus("لا يصل صوت من التطبيقات. جرّب وضع الميكروفون؛ قد يمنع TV/DVB/HDMI الالتقاط");
                    lastSound = now; // throttle warning
                }
                if (recognizer.acceptWaveForm(chunk, n)) {
                    String sentence = new JSONObject(recognizer.getResult()).optString("text", "").trim();
                    if (!sentence.isEmpty()) {
                        translatePhrase(sentence, false);
                        lastPartial = "";
                        previousPartialTime = now;
                    }
                } else if (now - previousPartialTime >= 1600) {
                    String partial = new JSONObject(recognizer.getPartialResult()).optString("partial", "").trim();
                    if (partial.length() >= 13 && !partial.equals(lastPartial)) {
                        lastPartial = partial;
                        previousPartialTime = now;
                        translatePhrase(partial, true);
                    }
                }
            }
            if (recognizer != null && active) {
                String sentence = new JSONObject(recognizer.getFinalResult()).optString("text", "").trim();
                if (!sentence.isEmpty()) translatePhrase(sentence, false);
            }
        } catch (Exception | LinkageError error) {
            notifyStatus("تعذرت الترجمة الصوتية: " + error.getMessage());
            main.post(() -> showText("خطأ الصوت: " + error.getMessage()));
        } finally {
            if (recording != null) {
                try { recording.stop(); } catch (Exception ignored) { }
                recording.release(); recording = null;
            }
            if (recognizer != null) recognizer.close();
            if (model != null) model.close();
            if (active) main.post(this::stopSelf);
        }
    }
    private void translatePhrase(String original, boolean partial) {
        long seq = translationRequest.incrementAndGet();
        lastOriginal = original;
        // Keep prior subtitle visible while the next phrase is recognized and translated.
        translator.translate(original, language, (arabic, engine) -> {
            if (!active || seq != translationRequest.get()) return;
            if ("error".equals(engine)) {
                notifyStatus("خطأ الترجمة: " + arabic);
                return;
            }
            if (!arabic.trim().isEmpty()) showCaption(original, arabic);
        });
    }
    private void showOverlay() {
        if (!Settings.canDrawOverlays(this)) return;
        main.post(() -> {
            if (!active || overlay != null || !Settings.canDrawOverlays(this)) return;
            try {
                TextView label = new TextView(this);
                label.setTextSize(captionSize);
                label.setTextColor(Color.WHITE);
                label.setGravity(Gravity.CENTER);
                label.setTextDirection(View.TEXT_DIRECTION_RTL);
                label.setMaxLines(BuildConfig.TV_MODE ? 3 : 4);
                label.setBackgroundColor(0xC6000000);
                label.setPadding(20,12,20,12);
                label.setShadowLayer(3f,1f,1f,Color.BLACK);
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
                p.gravity = (captionTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
                p.y = BuildConfig.TV_MODE ? 48 : 78;
                windows.addView(label, p);
                label.setVisibility(View.GONE);
                overlay = label;
            } catch (Exception e) { notifyStatus("صلاحية الترجمة العائمة غير متاحة"); }
        });
    }
    /** Draw original speech in smaller gold type and Arabic translation in white; always user controlled. */
    private void showCaption(String original, String arabic) {
        if (!active) return;
        if (!bilingual || original == null || original.isEmpty()) {
            showText(arabic);
            return;
        }
        String source = ("ja".equals(language) ? "日本語" : "English");
        String heading = source + ":  " + original.replace('\n', ' ');
        SpannableStringBuilder content = new SpannableStringBuilder(heading + "\n" + arabic);
        content.setSpan(new RelativeSizeSpan(0.66f), 0, heading.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        content.setSpan(new ForegroundColorSpan(0xFFFFD77A), 0, heading.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (overlay == null) showOverlay();
        if (overlay != null) {
            overlay.setMaxLines(BuildConfig.TV_MODE ? 5 : 6);
            overlay.setText(content);
            overlay.setVisibility(View.VISIBLE);
        }
    }
    private void showText(String text) {
        if (!active) return;
        if (overlay == null) showOverlay();
        if (overlay != null) {
            overlay.setText(text);
            overlay.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }
    private Notification notification(String message) {
        return new Notification.Builder(this, CHANNEL)
            .setContentTitle("AI Translate — Live Speech")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "إيقاف الترجمة",
                PendingIntent.getService(this, 152, new Intent(this, AudioTranslateService.class)
                    .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build())
            .setOngoing(true).build();
    }
    private void notifyStatus(String message) {
        if (message.equals(statusText)) return;
        statusText = message;
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
            .notify(NOTIF_ID, notification(message));
    }
    @Override public void onDestroy() {
        active = false;
        translationRequest.incrementAndGet();
        if (recording != null) {
            try { recording.stop(); } catch (Exception ignored) { }
        }
        if (thread != null) thread.interrupt();
        if (projection != null) {
            if (callback != null) projection.unregisterCallback(callback);
            projection.stop(); projection = null;
        }
        if (overlay != null) {
            try { windows.removeView(overlay); } catch (Exception ignored) { }
            overlay = null;
        }
        if (translator != null) translator.close();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
