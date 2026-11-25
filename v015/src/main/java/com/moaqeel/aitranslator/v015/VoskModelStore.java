package com.moaqeel.aitranslator.v015;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads official small offline Vosk speech models once. No audio leaves the device. */
public final class VoskModelStore {
    public interface Progress { void update(String message); }
    private static final String BASE = "https://alphacephei.com/vosk/models/";
    private final File root;

    public VoskModelStore(Context context) {
        root = new File(context.getFilesDir(), "vosk-v015");
    }
    private String modelName(String lang) {
        return "ja".equals(lang) ? "vosk-model-small-ja-0.22" : "vosk-model-small-en-us-0.15";
    }
    public File ensure(String language, Progress progress) throws Exception {
        String name = modelName(language);
        File target = new File(root, name);
        if (valid(target)) { progress.update("نموذج الصوت المحلي جاهز"); return target; }
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create models directory");
        File download = new File(root, name + ".partial.zip");
        File extracting = new File(root, name + ".extracting");
        erase(extracting); download.delete();
        if (!extracting.mkdirs()) throw new IOException("Cannot prepare model storage");
        try {
            progress.update("تحميل نموذج الصوت " + ("ja".equals(language) ? "الياباني (~48MB)" : "الإنجليزي (~40MB)"));
            URL url = new URL(BASE + name + ".zip");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            try {
                if (c.getResponseCode() != 200) throw new IOException("Model download HTTP " + c.getResponseCode());
                long count = 0;
                try (BufferedInputStream in = new BufferedInputStream(c.getInputStream());
                     FileOutputStream output = new FileOutputStream(download)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                        count += n;
                        if (count > 130_000_000L) throw new IOException("Audio model download too large");
                        output.write(buf, 0, n);
                        if (count % (2 * 1024 * 1024) < n)
                            progress.update("تحميل نموذج الصوت: " + (count / 1024 / 1024) + " MB");
                    }
                }
            } finally { c.disconnect(); }
            progress.update("تجهيز النموذج الصوتي...");
            long expanded = 0;
            int entries = 0;
            String baseCanonical = extracting.getCanonicalPath() + File.separator;
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                    new java.io.FileInputStream(download)))) {
                ZipEntry entry;
                byte[] buf = new byte[32768];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > 18000) throw new IOException("Model too many entries");
                    File out = new File(extracting, entry.getName());
                    if (!out.getCanonicalPath().startsWith(baseCanonical)) throw new IOException("Unsafe model archive");
                    if (entry.isDirectory()) {
                        if (!out.exists() && !out.mkdirs()) throw new IOException("Cannot create model subdirectory");
                    } else {
                        File parent = out.getParentFile();
                        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create subdirectory");
                        try (FileOutputStream dest = new FileOutputStream(out)) {
                            int n;
                            while ((n = zip.read(buf)) >= 0) {
                                expanded += n;
                                if (expanded > 650_000_000L) throw new IOException("Model expanded too large");
                                dest.write(buf, 0, n);
                            }
                        }
                    }
                    zip.closeEntry();
                }
            }
            File extracted = new File(extracting, name);
            if (!valid(extracted)) throw new IOException("Audio model incomplete; retry download");
            erase(target);
            if (!extracted.renameTo(target)) throw new IOException("Unable to save model");
            progress.update("نموذج الصوت جاهز للتعرف الفوري");
            return target;
        } finally {
            download.delete();
            erase(extracting);
        }
    }
    private boolean valid(File file) {
        return new File(file, "am/final.mdl").isFile()
            && (new File(file, "graph/HCLr.fst").isFile()
                || new File(file, "graph/HCLG.fst").isFile()
                || new File(file, "graph/Gr.fst").isFile());
    }
    private void erase(File path) {
        if (!path.exists()) return;
        if (path.isDirectory()) {
            File[] files = path.listFiles();
            if (files != null) for (File file : files) erase(file);
        }
        path.delete();
    }
}
