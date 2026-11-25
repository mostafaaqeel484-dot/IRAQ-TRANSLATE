package com.moaqeel.aitranslator.v015;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 400;
    private static final int REQUEST_SPEECH = 401;
    private SecureSettings settings;
    private TranslationEngine translations;
    private Spinner providers;
    private Spinner sources;
    private EditText apiKey;
    private EditText libreUrl;
    private EditText region;
    private EditText manualText;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new SecureSettings(this);
        translations = new TranslationEngine(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(18), dp(20), dp(24));
        body.setBackgroundColor(Color.rgb(10,11,14));

        addText(body, "AI Translate", BuildConfig.TV_MODE ? 34 : 28, 0xFFF3D16B, true);
        addText(body, "V0.15  •  " + (BuildConfig.TV_MODE ? "MT5889 TV / Remote" : "Mobile / Android"),
            16, 0xFFBBBBBB, false);
        addText(body, "ترجمة النصوص الظاهرة على الشاشة إلى العربية — الإنجليزية واليابانية",
            BuildConfig.TV_MODE ? 20 : 16, Color.WHITE, false);

        addText(body, "مصدر النص", 18, 0xFFF3D16B, true);
        sources = spinner(body, new String[]{"English (en)", "Japanese (ja)"});
        sources.setSelection("ja".equals(settings.get("source","en")) ? 1 : 0);

        addText(body, "محرك الترجمة", 18, 0xFFF3D16B, true);
        String[] engines = {"Local / Offline ML Kit", "LibreTranslate", "Gemini", "Groq", "Azure", "DeepL"};
        String[] ids = {"local","libre","gemini","groq","azure","deepl"};
        providers = spinner(body, engines);
        String chosen = settings.get("provider","local");
        for (int i=0; i<ids.length; i++) if(ids[i].equals(chosen)) providers.setSelection(i);

        apiKey = edit(body, "مفتاح API (اختياري للمحرك المحلي) — يخزن مشفراً", false);
        libreUrl = edit(body, "LibreTranslate HTTPS URL (مثلاً https://example.org)", false);
        libreUrl.setText(settings.get("libre_url",""));
        region = edit(body, "Azure region (اختياري حسب الخدمة)", false);
        region.setText(settings.get("azure_region",""));
        addText(body, "لا تُرسل أي نصوص لخدمة سحابية إلا عند اختيارها. الصور نفسها لا تُرسل.", 13,
            0xFFBDBDBD, false);

        button(body, "حفظ الإعدادات", () -> {
            settings.put("provider", ids[providers.getSelectedItemPosition()]);
            settings.put("source", sources.getSelectedItemPosition() == 1 ? "ja" : "en");
            settings.put("libre_url", libreUrl.getText().toString().trim());
            settings.put("azure_region", region.getText().toString().trim());
            if (apiKey.getText().length() > 0) {
                try { settings.storeApiKey(apiKey.getText().toString().trim()); apiKey.setText(""); }
                catch (Exception e) { show("تعذر تشفير المفتاح: " + e.getMessage()); return; }
            }
            show("تم حفظ الإعدادات. المحرك الحالي: " + providers.getSelectedItem());
        });
        button(body, "مسح مفتاح API المحفوظ", () -> {
            try { settings.storeApiKey(""); show("تم مسح المفتاح المحفوظ"); }
            catch (Exception e) { show("خطأ أثناء مسح المفتاح"); }
        });

        addText(body, "ترجمة الشاشة", 20, 0xFFF3D16B, true);
        button(body, "السماح بظهور الترجمة فوق التطبيقات", () -> {
            if (Settings.canDrawOverlays(this)) { show("صلاحية النافذة العائمة مفعلة"); return; }
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        button(body, "▶ بدء OCR وترجمة الشاشة", () -> {
            saveQuick(ids);
            if (!Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this).setMessage("فعّل ظهور النافذة العائمة ثم ابدأ الالتقاط")
                    .setPositiveButton("الإعدادات", (d,w) -> startActivity(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("لاحقاً", null).show();
                return;
            }
            MediaProjectionManager projection = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(projection.createScreenCaptureIntent(), REQUEST_CAPTURE);
        });
        button(body, "■ إيقاف الترجمة", () -> {
            Intent stop = new Intent(this, ScreenTranslateService.class);
            stop.setAction(ScreenTranslateService.ACTION_STOP);
            startService(stop);
            show("تم طلب إيقاف ترجمة الشاشة");
        });

        addText(body, "اختبار الترجمة اليدوية", 20, 0xFFF3D16B, true);
        manualText = edit(body, "اكتب جملة إنجليزية أو يابانية", true);
        button(body, "ترجم النص", () -> translatePreview(manualText.getText().toString(), ids));
        button(body, "🎙 الترجمة من ميكروفون الجهاز", () -> {
            try {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                    sources.getSelectedItemPosition() == 1 ? "ja-JP" : "en-US");
                startActivityForResult(intent, REQUEST_SPEECH);
            } catch (Exception e) { show("خدمة التعرف الصوتي غير متوفرة على الجهاز"); }
        });
        status = addText(body, "جاهز. اختر المحرك واحفظ الإعدادات ثم ابدأ.", 16, Color.WHITE, false);
        addText(body, BuildConfig.TV_MODE
            ? "TV Lite: أزرار تدعم الريموت. تطبيقات البث وHDMI وDVB قد تحجب الالتقاط."
            : "Mobile: يدعم الوضع الأفقي والعمودي. قد تحجب التطبيقات المحمية تصوير الشاشة.",
            14, 0xFFBBBBBB, false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        setContentView(scroll);
    }
    private void saveQuick(String[] ids) {
        settings.put("provider", ids[providers.getSelectedItemPosition()]);
        settings.put("source", sources.getSelectedItemPosition() == 1 ? "ja" : "en");
        settings.put("libre_url", libreUrl.getText().toString().trim());
        settings.put("azure_region", region.getText().toString().trim());
    }
    private void translatePreview(String text, String[] ids) {
        if (text.trim().isEmpty()) { show("اكتب النص أولاً"); return; }
        saveQuick(ids);
        show("تجهيز الترجمة...");
        translations.translate(text, settings.get("source","en"),
            (value, provider) -> show(provider + ": " + value));
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_CAPTURE && result == RESULT_OK && data != null) {
            Intent launch = new Intent(this, ScreenTranslateService.class);
            launch.setAction(ScreenTranslateService.ACTION_START);
            launch.putExtra("result_code", result);
            launch.putExtra("projection_data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(launch); else startService(launch);
            show("جاري تشغيل OCR في الخلفية. افتح اللعبة أو التطبيق.");
        }
        if (request == REQUEST_SPEECH && result == RESULT_OK && data != null) {
            ArrayList<String> text = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (text != null && !text.isEmpty()) {
                manualText.setText(text.get(0));
                String[] ids = {"local","libre","gemini","groq","azure","deepl"};
                translatePreview(text.get(0), ids);
            }
        }
    }
    private TextView addText(LinearLayout body,String text,int sp,int color,boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0,dp(8),0,dp(6));
        body.addView(view, new LinearLayout.LayoutParams(-1,-2));
        return view;
    }
    private Spinner spinner(LinearLayout body,String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values);
        spinner.setAdapter(adapter);
        spinner.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF3D16B));
        body.addView(spinner,new LinearLayout.LayoutParams(-1,dp(BuildConfig.TV_MODE?62:52)));
        return spinner;
    }
    private EditText edit(LinearLayout body,String hint,boolean multiline) {
        EditText value = new EditText(this);
        value.setHint(hint);
        value.setHintTextColor(0xFF777777);
        value.setTextColor(Color.WHITE);
        value.setSingleLine(!multiline);
        value.setTextSize(BuildConfig.TV_MODE?20:16);
        value.setInputType(multiline ? 0x00020001 : 0x00000001);
        body.addView(value,new LinearLayout.LayoutParams(-1,-2));
        return value;
    }
    private void button(LinearLayout body,String label,Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF363019));
        button.setTextSize(BuildConfig.TV_MODE?20:16);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,dp(BuildConfig.TV_MODE?64:52));
        p.topMargin = dp(6);
        body.addView(button,p);
    }
    private void show(String msg) {
        if (status != null) status.setText(msg);
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }
    private int dp(float dp) { return (int)(dp*getResources().getDisplayMetrics().density+.5f); }
    @Override protected void onDestroy() { if(translations!=null)translations.close(); super.onDestroy(); }
}
