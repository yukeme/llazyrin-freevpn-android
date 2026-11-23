package ru.llazyrin.goblinvpn.security;

import android.content.Context;
import android.media.MediaDrm;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * DeviceFingerprintHelper — Защищенная аппаратная идентификация устройства для Goblin VPN.
 *
 * Принципы:
 * 1. Несбрасываемый аппаратный отпечаток Widevine DRM Unique ID (MediaDrm.PROPERTY_DEVICE_UNIQUE_ID).
 *    Хранится в защищенной зоне процессора (TEE / ARM TrustZone) и не удаляется при очистке
 *    кэша, сбросе данных приложения или переустановке APK.
 * 2. Грациозный fallback на Settings.Secure.ANDROID_ID при недоступности DRM-подсистемы.
 * 3. Хеширование SHA-256 для защиты приватности (на сервер передается только 64-символьный хэш).
 * 4. Предотвращает мульти-аккаунтинг и абуз лимита 3 устройств на учетную запись.
 */
public class DeviceFingerprintHelper {
    private static final String TAG = "DeviceFingerprint";

    // Стандартный UUID Widevine Modular DRM Scheme (RFC 4122)
    private static final UUID WIDEVINE_UUID = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);

    private static volatile String cachedFingerprint = null;

    /**
     * Получить стабильный аппаратный отпечаток устройства (64-символьный hex SHA-256).
     */
    public static synchronized String getDeviceFingerprint(Context context) {
        if (cachedFingerprint != null && !cachedFingerprint.isEmpty()) {
            return cachedFingerprint;
        }

        byte[] rawId = null;

        // 1. Попытка получить Widevine DRM Unique ID
        try {
            MediaDrm mediaDrm = new MediaDrm(WIDEVINE_UUID);
            try {
                rawId = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mediaDrm.close();
                } else {
                    mediaDrm.release();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Widevine DRM unique ID unavailable, falling back: " + t.getMessage());
        }

        // 2. Если DRM ID успешно получен и не пустой
        if (rawId != null && rawId.length > 0) {
            cachedFingerprint = sha256Hex(rawId);
            return cachedFingerprint;
        }

        // 3. Fallback: Android ID
        String androidId = null;
        if (context != null) {
            try {
                androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            } catch (Throwable ignored) {}
        }

        if (androidId != null && !androidId.trim().isEmpty() && !"9774d56d682e549c".equals(androidId)) {
            String seed = "DRM_FALLBACK:ANDROID_ID:" + androidId.trim();
            cachedFingerprint = sha256Hex(seed.getBytes(StandardCharsets.UTF_8));
            return cachedFingerprint;
        }

        // 4. Крайний fallback: Аппаратные свойства устройства
        String hwSeed = "DRM_FALLBACK:HW:" + Build.MANUFACTURER + ":" + Build.MODEL + ":" + Build.BOARD + ":" + Build.FINGERPRINT;
        cachedFingerprint = sha256Hex(hwSeed.getBytes(StandardCharsets.UTF_8));
        return cachedFingerprint;
    }

    /**
     * Возвращает человекочитаемое название модели (например, "Samsung SM-S936B" или "Xiaomi 23117RK66C").
     */
    public static String getDeviceModel() {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
        String model = Build.MODEL != null ? Build.MODEL : "Device";

        if (model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            return capitalize(model);
        } else {
            return capitalize(manufacturer) + " " + model;
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.length() == 0) return "";
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) return s;
        return Character.toUpperCase(first) + s.substring(1);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            // Фолбэк на простой строковый хэшкод
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }
}
