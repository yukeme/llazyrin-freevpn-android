package ru.llazyrin.goblinvpn.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

import ru.llazyrin.goblinvpn.model.NetworkPreferences;
import ru.llazyrin.goblinvpn.security.DeviceFingerprintHelper;
import ru.llazyrin.goblinvpn.service.GoblinVpnService;

/**
 * SubscriptionManager — Менеджер промокодов, мультиязычных подписок и реестра 3-х устройств Goblin VPN.
 *
 * Эндпоинты:
 * 1. POST /api/app/promo/redeem (Атомарная активация промокода из Telegram-каналов)
 * 2. GET  /api/app/devices/list (Список активных устройств подписки)
 * 3. POST /api/app/devices/bind (Привязка текущего смартфона к слоту 1..3)
 * 4. POST /api/app/devices/swap (Отвязка старого устройства и замена на новое)
 * 5. GET  /api/app/subscription/status (Статус подписки)
 *
 * Примечание: Платный доступ и показ рекламы временно отключены/закомментированы по требованию
 * пользователя на время бета-тестирования. Все пользователи имеют полноценный бесплатный доступ.
 */
public class SubscriptionManager {
    private static final String TAG = "SubscriptionManager";

    public static final String PROMO_REDEEM_URL = "https://llazyrin.online/api/app/promo/redeem";
    public static final String DEVICES_LIST_URL = "https://llazyrin.online/api/app/devices/list";
    public static final String DEVICES_BIND_URL = "https://llazyrin.online/api/app/devices/bind";
    public static final String DEVICES_SWAP_URL = "https://llazyrin.online/api/app/devices/swap";
    public static final String SUB_STATUS_URL   = "https://llazyrin.online/api/app/subscription/status";

    private static final int TIMEOUT_MS = 6000;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static class DeviceEntry {
        public final int id;
        public final String deviceId;
        public final String deviceModel;
        public final int slotIndex;
        public final boolean isActive;
        public final String createdAt;
        public final String lastSeen;

        public DeviceEntry(int id, String deviceId, String deviceModel, int slotIndex,
                           boolean isActive, String createdAt, String lastSeen) {
            this.id = id;
            this.deviceId = deviceId;
            this.deviceModel = deviceModel;
            this.slotIndex = slotIndex;
            this.isActive = isActive;
            this.createdAt = createdAt;
            this.lastSeen = lastSeen;
        }
    }

    public interface PromoCallback {
        void onSuccess(String message, int bonusDays, String expiresAt);
        void onError(String errorMessage);
    }

    public interface DevicesCallback {
        void onSuccess(int maxDevices, int usedSlots, List<DeviceEntry> devices);
        void onError(String errorMessage);
    }

    public interface ActionCallback {
        void onSuccess(String message);
        void onError(String errorMessage);
    }

    /**
     * Активация промокода (бонусные дни / VIP-канал).
     */
    public static void redeemPromoCode(final Context context, final String rawCode, final PromoCallback callback) {
        if (context == null || rawCode == null || rawCode.trim().isEmpty()) {
            if (callback != null) callback.onError("Введите корректный промокод");
            return;
        }

        final String code = rawCode.trim().toUpperCase();
        final String userId = NetworkPreferences.getInstance(context).getClientId();
        final String deviceId = DeviceFingerprintHelper.getDeviceFingerprint(context);

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    JSONObject body = new JSONObject();
                    body.put("code", code);
                    body.put("user_id", userId);
                    body.put("device_id", deviceId);

                    URL url = new URL(PROMO_REDEEM_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "application/json");

                    byte[] postBytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postBytes);
                    }

                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    String responseStr = readStream(is);

                    final JSONObject respJson = (responseStr != null && !responseStr.isEmpty())
                            ? new JSONObject(responseStr) : new JSONObject();

                    final boolean ok = respJson.optBoolean("ok", false);
                    final String msg = respJson.optString("message", respJson.optString("error", "Неизвестный ответ сервера"));
                    final int bonusDays = respJson.optInt("bonus_days", 0);
                    final String expiresAt = respJson.optString("expires_at", "");

                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                if (ok) {
                                    callback.onSuccess(msg, bonusDays, expiresAt);
                                } else {
                                    callback.onError(msg);
                                }
                            }
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "redeemPromoCode failed", e);
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onError("Ошибка сети при проверке промокода: " + e.getLocalizedMessage());
                            }
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    /**
     * Получить список зарегистрированных устройств пользователя (до 3-х слотов).
     */
    public static void fetchUserDevices(final Context context, final DevicesCallback callback) {
        if (context == null) return;
        final String userId = NetworkPreferences.getInstance(context).getClientId();

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String fullUrl = DEVICES_LIST_URL + "?user_id=" + userId;
                    URL url = new URL(fullUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setRequestProperty("Accept", "application/json");

                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    String responseStr = readStream(is);

                    final JSONObject respJson = (responseStr != null && !responseStr.isEmpty())
                            ? new JSONObject(responseStr) : new JSONObject();

                    final boolean ok = respJson.optBoolean("ok", false);
                    final int maxDev = respJson.optInt("max_devices", 3);
                    final int usedSlots = respJson.optInt("used_slots", 0);
                    final List<DeviceEntry> list = new ArrayList<>();

                    if (ok) {
                        JSONArray arr = respJson.optJSONArray("devices");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject item = arr.getJSONObject(i);
                                list.add(new DeviceEntry(
                                        item.optInt("id", 0),
                                        item.optString("device_id", ""),
                                        item.optString("device_model", "Android"),
                                        item.optInt("slot_index", 1),
                                        item.optInt("is_active", 1) == 1,
                                        item.optString("created_at", ""),
                                        item.optString("last_seen", "")
                                ));
                            }
                        }
                    }

                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                if (ok) {
                                    callback.onSuccess(maxDev, usedSlots, list);
                                } else {
                                    callback.onError(respJson.optString("error", "Ошибка загрузки устройств"));
                                }
                            }
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "fetchUserDevices failed", e);
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onError("Ошибка подключения: " + e.getLocalizedMessage());
                            }
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    /**
     * Автоматическая привязка текущего устройства к свободному слоту.
     */
    public static void bindCurrentDevice(final Context context, final ActionCallback callback) {
        if (context == null) return;
        final String userId = NetworkPreferences.getInstance(context).getClientId();
        final String deviceId = DeviceFingerprintHelper.getDeviceFingerprint(context);
        final String deviceModel = DeviceFingerprintHelper.getDeviceModel();

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    JSONObject body = new JSONObject();
                    body.put("user_id", userId);
                    body.put("device_id", deviceId);
                    body.put("device_model", deviceModel);

                    URL url = new URL(DEVICES_BIND_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "application/json");

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }

                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    String responseStr = readStream(is);
                    final JSONObject respJson = (responseStr != null && !responseStr.isEmpty())
                            ? new JSONObject(responseStr) : new JSONObject();

                    final boolean ok = respJson.optBoolean("ok", false);
                    final String msg = respJson.optString("message", respJson.optString("error", "Устройство зарегистрировано"));

                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                if (ok) {
                                    callback.onSuccess(msg);
                                } else {
                                    callback.onError(msg);
                                }
                            }
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "bindCurrentDevice failed", e);
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) callback.onError(e.getLocalizedMessage());
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    /**
     * Замена устройства в указанном слоте (1..3) на текущее.
     */
    public static void swapDeviceSlot(final Context context, final int slotIndex, final ActionCallback callback) {
        if (context == null || slotIndex < 1 || slotIndex > 3) {
            if (callback != null) callback.onError("Некорректный номер слота");
            return;
        }

        final String userId = NetworkPreferences.getInstance(context).getClientId();
        final String deviceId = DeviceFingerprintHelper.getDeviceFingerprint(context);
        final String deviceModel = DeviceFingerprintHelper.getDeviceModel();

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    JSONObject body = new JSONObject();
                    body.put("user_id", userId);
                    body.put("slot_index", slotIndex);
                    body.put("new_device_id", deviceId);
                    body.put("new_device_model", deviceModel);

                    URL url = new URL(DEVICES_SWAP_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "application/json");

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }

                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    String responseStr = readStream(is);
                    final JSONObject respJson = (responseStr != null && !responseStr.isEmpty())
                            ? new JSONObject(responseStr) : new JSONObject();

                    final boolean ok = respJson.optBoolean("ok", false);
                    final String msg = respJson.optString("message", respJson.optString("error", "Слот обновлен"));

                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                if (ok) {
                                    callback.onSuccess(msg);
                                } else {
                                    callback.onError(msg);
                                }
                            }
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "swapDeviceSlot failed", e);
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) callback.onError(e.getLocalizedMessage());
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    private static String readStream(InputStream is) {
        if (is == null) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception ignored) {
            return null;
        }
    }
}
