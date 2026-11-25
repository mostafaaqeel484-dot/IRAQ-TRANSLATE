package com.moaqeel.aitranslator.v015;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.TranslatorOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** OCR text alone is sent to the explicitly selected provider; screenshots never leave the device. */
public final class TranslationEngine {
    public interface Callback { void onResult(String text, String provider); }
    private final SecureSettings prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String,String> cache = new LinkedHashMap<String,String>(160, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> eldest) { return size() > 160; }
    };
    private Translator translatorEn;
    private Translator translatorJa;

    public TranslationEngine(Context context) { prefs = new SecureSettings(context.getApplicationContext()); }

    public void translate(String original, String source, Callback callback) {
        String text = original == null ? "" : original.trim();
        if (text.isEmpty()) return;
        String lang = "ja".equals(source) ? "ja" : "en";
        String provider = prefs.get("provider", "local");
        String key = provider + "|" + lang + "|" + text;
        String cached;
        synchronized (cache) { cached = cache.get(key); }
        if (cached != null) { send(callback, cached, "cache"); return; }
        if ("local".equals(provider)) {
            local(text, lang, key, callback);
        } else {
            executor.execute(() -> {
                try {
                    String output = cloud(provider, lang, text);
                    if (output == null || output.trim().isEmpty()) throw new IllegalStateException("Empty translation");
                    success(key, output.trim(), provider, callback);
                } catch (Exception exception) {
                    // Fail closed with respect to cloud: one selected provider only, then on-device fallback.
                    local(text, lang, key, callback);
                }
            });
        }
    }
    private synchronized Translator client(String source) {
        if ("ja".equals(source)) {
            if (translatorJa == null) translatorJa = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE).setTargetLanguage(TranslateLanguage.ARABIC).build());
            return translatorJa;
        }
        if (translatorEn == null) translatorEn = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.ARABIC).build());
        return translatorEn;
    }
    private void local(String text, String lang, String cacheKey, Callback callback) {
        Translator t = client(lang);
        t.downloadModelIfNeeded()
            .addOnSuccessListener(unused -> t.translate(text)
                .addOnSuccessListener(value -> success(cacheKey, value, "offline", callback))
                .addOnFailureListener(e -> send(callback, "تعذر الترجمة المحلية: " + e.getMessage(), "error")))
            .addOnFailureListener(e -> send(callback, "يتطلب النموذج المحلي تنزيلًا أوليًا: " + e.getMessage(), "error"));
    }
    private void success(String key, String text, String provider, Callback callback) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (cache) { cache.put(key, text); }
        send(callback, text, provider);
    }
    private void send(Callback callback, String text, String engine) {
        ui.post(() -> callback.onResult(text, engine));
    }

    private String cloud(String provider, String source, String text) throws Exception {
        String key = prefs.apiKey();
        String from = "ja".equals(source) ? "JA" : "EN";
        String endpoint;
        JSONObject request;
        HttpURLConnection conn;
        switch (provider) {
            case "libre":
                endpoint = prefs.get("libre_url", "").trim();
                if (!endpoint.startsWith("https://")) throw new IllegalStateException("LibreTranslate requires HTTPS");
                endpoint = endpoint.replaceAll("/+$", "");
                if (!endpoint.endsWith("/translate")) endpoint += "/translate";
                conn = connection(endpoint, "application/json");
                request = new JSONObject().put("q", text).put("source", source)
                    .put("target", "ar").put("format", "text");
                if (!key.isEmpty()) request.put("api_key", key);
                return post(conn, request.toString()).getString("translatedText");
            case "gemini":
                if (key.isEmpty()) throw new IllegalStateException("Missing Gemini API key");
                endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";
                conn = connection(endpoint, "application/json");
                conn.setRequestProperty("x-goog-api-key", key);
                request = new JSONObject().put("contents", new JSONArray().put(new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", prompt(source, text))))));
                return post(conn, request.toString()).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            case "groq":
                if (key.isEmpty()) throw new IllegalStateException("Missing Groq API key");
                conn = connection("https://api.groq.com/openai/v1/chat/completions", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + key);
                request = new JSONObject().put("model", "llama-3.3-70b-versatile")
                    .put("temperature", 0)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", prompt(source, text))));
                return post(conn, request.toString()).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            case "azure":
                if (key.isEmpty()) throw new IllegalStateException("Missing Azure key");
                endpoint = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from="
                    + source + "&to=ar";
                conn = connection(endpoint, "application/json");
                conn.setRequestProperty("Ocp-Apim-Subscription-Key", key);
                String region = prefs.get("azure_region", "").trim();
                if (!region.isEmpty()) conn.setRequestProperty("Ocp-Apim-Subscription-Region", region);
                JSONArray a = new JSONArray().put(new JSONObject().put("Text", text));
                return postArray(conn, a.toString()).getJSONObject(0)
                    .getJSONArray("translations").getJSONObject(0).getString("text");
            case "deepl":
                if (key.isEmpty()) throw new IllegalStateException("Missing DeepL key");
                conn = connection("https://api-free.deepl.com/v2/translate", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Authorization", "DeepL-Auth-Key " + key);
                String data = "text=" + URLEncoder.encode(text, "UTF-8") + "&source_lang=" + from + "&target_lang=AR";
                return post(conn, data).getJSONArray("translations").getJSONObject(0).getString("text");
            default: throw new IllegalArgumentException("Unknown provider");
        }
    }
    private String prompt(String source, String text) {
        return "Translate from " + ("ja".equals(source) ? "Japanese" : "English")
            + " into natural Modern Standard Arabic. Preserve proper names. Output translation only:\n" + text;
    }
    private HttpURLConnection connection(String url, String contentType) throws Exception {
        if (!url.startsWith("https://")) throw new IllegalArgumentException("HTTPS required");
        HttpURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(6000);
        c.setReadTimeout(13000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", contentType + "; charset=utf-8");
        return c;
    }
    private JSONObject post(HttpURLConnection c, String data) throws Exception {
        return new JSONObject(request(c, data));
    }
    private JSONArray postArray(HttpURLConnection c, String data) throws Exception {
        return new JSONArray(request(c, data));
    }
    private String request(HttpURLConnection c, String data) throws Exception {
        try {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            try (InputStream in = c.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (result.length() > 100000) throw new IllegalStateException("Large response");
                    result.append(line);
                }
                return result.toString();
            }
        } finally { c.disconnect(); }
    }
    public void close() {
        executor.shutdownNow();
        if (translatorEn != null) translatorEn.close();
        if (translatorJa != null) translatorJa.close();
    }
}
