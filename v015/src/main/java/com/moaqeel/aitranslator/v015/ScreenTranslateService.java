package com.moaqeel.aitranslator.v015;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ScreenTranslateService extends Service {
    public static final String ACTION_START = "com.moaqeel.v015.START";
    public static final String ACTION_STOP = "com.moaqeel.v015.STOP";
    private static final String CHANNEL_ID = "ai_translate_v015";
    private static final int NOTIFICATION_ID = 150;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler captureHandler;
    private TranslationEngine translator;
    private TextRecognizer latin;
    private TextRecognizer japanese;
    private WindowManager windowManager;
    private TextView subtitle;
    private boolean running;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicLong requestId = new AtomicLong();
    private String lastDetected = "";
    private int emptyCount = 0;
    private int captureWidth;
    private int captureHeight;
    private String sourceLanguage = "en";
    private final Handler main = new Handler(android.os.Looper.getMainLooper());

    @Override public void onCreate() {
        super.onCreate();
        translator = new TranslationEngine(this);
        latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        japanese = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        thread = new HandlerThread("ocr-v015");
        thread.start();
        captureHandler = new Handler(thread.getLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
            "AI Translate OCR", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running) return START_NOT_STICKY;
        int code = intent.getIntExtra("result_code", 0);
        Intent data = Build.VERSION.SDK_INT >= 33
            ? intent.getParcelableExtra("projection_data", Intent.class)
            : intent.getParcelableExtra("projection_data");
        if (code != android.app.Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            startForeground(NOTIFICATION_ID, notification("تجهيز OCR"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("MediaProjection unavailable");
            projectionCallback = new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            };
            projection.registerCallback(projectionCallback, main);

            DisplayMetrics m = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(m);
            float ratio = Math.min(1f, 1280f / Math.max(1, m.widthPixels));
            captureWidth = Math.max(320, Math.round(m.widthPixels * ratio));
            captureHeight = Math.max(320, Math.round(m.heightPixels * ratio));
            reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay("AI-Translate-OCR", captureWidth, captureHeight,
                m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, captureHandler);
            sourceLanguage = new SecureSettings(this).get("source", "en");
            running = true;
            ensureOverlay();
            notifyStatus("الترجمة تعمل. اللغة: " + sourceLanguage);
            captureHandler.postDelayed(scan, 800);
        } catch (Exception e) {
            notifyStatus("تعذر تشغيل الالتقاط: " + e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY; // consent must be requested again for each new session
    }

    private final Runnable scan = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (processing.compareAndSet(false, true)) captureOne();
            if (captureHandler != null) captureHandler.postDelayed(this, BuildConfig.TV_MODE ? 1500 : 1150);
        }
    };
    private void captureOne() {
        Image image = null;
        Bitmap bitmap = null;
        try {
            if (reader == null) { processing.set(false); return; }
            image = reader.acquireLatestImage();
            if (image == null) { processing.set(false); return; }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int paddedWidth = captureWidth + (rowStride - pixelStride * captureWidth) / pixelStride;
            bitmap = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            if (paddedWidth != captureWidth) {
                Bitmap crop = Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight);
                bitmap.recycle();
                bitmap = crop;
            }
        } catch (Exception e) {
            if (bitmap != null) bitmap.recycle();
            processing.set(false);
            return;
        } finally {
            if (image != null) image.close();
        }
        final Bitmap captured = bitmap;
        TextRecognizer ocr = "ja".equals(sourceLanguage) ? japanese : latin;
        Task<Text> task = ocr.process(InputImage.fromBitmap(captured, 0));
        task.addOnSuccessListener(result -> consumeText(result));
        task.addOnCompleteListener(done -> {
            captured.recycle();
            processing.set(false);
        });
    }

    private void consumeText(Text result) {
        if (!running) return;
        List<String> parts = new ArrayList<>();
        int max = 4;
        for (Text.TextBlock block : result.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            if (box == null || box.centerY() < captureHeight * .27f) continue;
            String line = block.getText().trim();
            if (line.length() < 3 || line.length() > 180) continue;
            // Excludes our own Arabic overlay and prevents OCR -> translate -> OCR loops.
            if (hasArabic(line)) continue;
            boolean letters = false;
            for (int i=0; i<line.length(); i++) if(Character.isLetter(line.charAt(i))) { letters=true; break; }
            if (letters) parts.add(line);
            if (parts.size() >= max) break;
        }
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (joined.length() + part.length() > 300) break;
            if (joined.length() > 0) joined.append('\n');
            joined.append(part);
        }
        String text = joined.toString().trim();
        if (text.isEmpty()) {
            if (++emptyCount > 4 && !lastDetected.isEmpty()) {
                lastDetected = "";
                requestId.incrementAndGet();
                main.post(() -> setSubtitle(""));
            }
            return;
        }
        emptyCount = 0;
        if (text.equals(lastDetected)) return;
        lastDetected = text;
        long seq = requestId.incrementAndGet();
        translator.translate(text, sourceLanguage, (value, engine) -> {
            if (!running || seq != requestId.get()) return;
            if ("error".equals(engine)) {
                notifyStatus(value);
                return;
            }
            setSubtitle(value);
        });
    }
    private boolean hasArabic(String value) {
        for (int i=0; i<value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u0600' && c <= '\u06FF') return true;
        }
        return false;
    }
    private void ensureOverlay() {
        if (!Settings.canDrawOverlays(this) || subtitle != null) return;
        try {
            subtitle = new TextView(this);
            subtitle.setTextColor(Color.WHITE);
            subtitle.setTextSize(BuildConfig.TV_MODE ? 29 : 22);
            subtitle.setGravity(Gravity.CENTER);
            subtitle.setTextDirection(View.TEXT_DIRECTION_RTL);
            subtitle.setMaxLines(BuildConfig.TV_MODE ? 3 : 4);
            subtitle.setPadding(16, 8, 16, 8);
            subtitle.setShadowLayer(3f, 1f, 2f, Color.BLACK);
            subtitle.setBackgroundColor(0xBB0A0A0A);
            subtitle.setVisibility(View.GONE);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.y = BuildConfig.TV_MODE ? 48 : 82;
            windowManager.addView(subtitle, params);
        } catch (Exception e) { subtitle = null; }
    }
    private void setSubtitle(String text) {
        if (subtitle == null) ensureOverlay();
        if (subtitle != null) {
            subtitle.setText(text);
            subtitle.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }
    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("AI Translate V0.15")
            .setContentText(text)
            .setOngoing(true)
            .build();
    }
    private void notifyStatus(String text) {
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(text));
    }
    @Override public void onDestroy() {
        running = false;
        requestId.incrementAndGet();
        if (captureHandler != null) captureHandler.removeCallbacksAndMessages(null);
        if (subtitle != null) {
            try { windowManager.removeView(subtitle); } catch (Exception ignored) { }
            subtitle = null;
        }
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) {
            if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
        if (latin != null) latin.close();
        if (japanese != null) japanese.close();
        if (translator != null) translator.close();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
