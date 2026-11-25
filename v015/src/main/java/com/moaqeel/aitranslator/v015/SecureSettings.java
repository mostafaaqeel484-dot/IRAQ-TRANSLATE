package com.moaqeel.aitranslator.v015;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureSettings {
    private static final String ALIAS = "ai_translate_v015_secret";
    private final SharedPreferences prefs;

    public SecureSettings(Context c) {
        prefs = c.getSharedPreferences("ai_translate_v015", Context.MODE_PRIVATE);
    }
    public String get(String key, String fallback) { return prefs.getString(key, fallback); }
    public void put(String key, String value) { prefs.edit().putString(key, value).apply(); }

    public void storeApiKey(String key) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        SecretKey secret = (SecretKey) store.getKey(ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret);
        byte[] encrypted = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        prefs.edit()
            .putString("key_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString("key_data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply();
    }

    public String apiKey() {
        try {
            String encoded = prefs.getString("key_data", "");
            if (encoded.isEmpty()) return "";
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey secret = (SecretKey) store.getKey(ALIAS, null);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret,
                new GCMParameterSpec(128, Base64.decode(prefs.getString("key_iv", ""), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
}
