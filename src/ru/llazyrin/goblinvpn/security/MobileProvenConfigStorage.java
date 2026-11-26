package ru.llazyrin.goblinvpn.security;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ru.llazyrin.goblinvpn.MainActivity;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.net.VpnSubscriptionClient;
import ru.llazyrin.goblinvpn.service.AppLogger;

/**
 * MobileProvenConfigStorage — Хранилище доказанно работающих конфигов на мобильных сетях.
 *
 * При успешном рукопожатии и передаче трафика на сотовой сети (LTE/5G/3G)
 * узел автоматически сохраняется в зашифрованный тайник устройства.
 *
 * При последующих запусках вне дома/офиса (на улице, при глушилках БС)
 * эти узлы получают наивысший приоритет (Tier 1), гарантируя моментальное
 * подключение без необходимости ручного перебора.
 *
 * Срок актуальности: 7 суток.
 * Ротация: автоматическое отбраковывание при 3 подряд сбоях рукопожатия.
 */
public class MobileProvenConfigStorage {
    private static final String TAG = "MobileProvenConfigStorage";
    private static final String STASH_FILE_NAME = ".mobile_proven.stash";
    private static final String STICKY_PREFS_NAME = "goblin_mobile_sticky";
    private static final String KEY_STICKY_CELLULAR_KEY = "sticky_cellular_server_key";
    private static final long MAX_AGE_MS = 7L * 24L * 3600L * 1000L; // 7 суток

    private static volatile MobileProvenConfigStorage instance = null;

    private final Context appContext;
    private final List<ProvenEntry> entries = new CopyOnWriteArrayList<>();
    private boolean isLoaded = false;

    public static class ProvenEntry {
        public String serverKey;
        public String rawUri;
        public String host;
        public int port;
        public String flag;
        public String country;
        public String remark;
        public String sni;
        public String security;
        public long lastSuccessMs;
        public long lastFailureMs;
        public int successCount;
        public int consecutiveFailures;
        public int lastLatencyMs;

        public ProvenEntry() {}

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("serverKey", serverKey != null ? serverKey : "");
                obj.put("rawUri", rawUri != null ? rawUri : "");
                obj.put("host", host != null ? host : "");
                obj.put("port", port);
                obj.put("flag", flag != null ? flag : "🌐");
                obj.put("country", country != null ? country : "Сервер");
                obj.put("remark", remark != null ? remark : "");
                obj.put("sni", sni != null ? sni : "");
                obj.put("security", security != null ? security : "reality");
                obj.put("lastSuccessMs", lastSuccessMs);
                obj.put("lastFailureMs", lastFailureMs);
                obj.put("successCount", successCount);
                obj.put("consecutiveFailures", consecutiveFailures);
                obj.put("lastLatencyMs", lastLatencyMs);
                return obj;
            } catch (Exception e) {
                return null;
            }
        }

        public static ProvenEntry fromJson(JSONObject obj) {
            if (obj == null) return null;
            ProvenEntry e = new ProvenEntry();
            e.serverKey = obj.optString("serverKey", "");
            e.rawUri = obj.optString("rawUri", "");
            e.host = obj.optString("host", "");
            e.port = obj.optInt("port", 443);
            e.flag = obj.optString("flag", "🌐");
            e.country = obj.optString("country", "Сервер");
            e.remark = obj.optString("remark", e.country);
            e.sni = obj.optString("sni", e.host);
            e.security = obj.optString("security", "reality");
            e.lastSuccessMs = obj.optLong("lastSuccessMs", 0L);
            e.lastFailureMs = obj.optLong("lastFailureMs", 0L);
            e.successCount = obj.optInt("successCount", 1);
            e.consecutiveFailures = obj.optInt("consecutiveFailures", 0);
            e.lastLatencyMs = obj.optInt("lastLatencyMs", 50);
            return e;
        }

        public int getMaxConsecutiveFailures() {
            if (successCount >= 10) return 8;
            if (successCount >= 5) return 6;
            if (successCount >= 2) return 4;
            return 3;
        }

        public void decayFailuresIfNeeded(long now) {
            if (consecutiveFailures > 0 && lastFailureMs > 0) {
                long elapsed = now - lastFailureMs;
                if (elapsed >= 150_000L) { // 2.5 minutes without failures
                    int units = (int) (elapsed / 90_000L); // decay 1 failure per 1.5 min
                    consecutiveFailures = Math.max(0, consecutiveFailures - units);
                    if (consecutiveFailures == 0) {
                        lastFailureMs = 0L;
                    }
                }
            }
        }

        public boolean isCoolingDown(long now) {
            if (lastFailureMs <= 0) return false;
            long cooldownMs = (consecutiveFailures <= 1) ? 20_000L : 60_000L;
            return (now - lastFailureMs) < cooldownMs;
        }

        public VpnServer toVpnServer() {
            VpnServer s;
            if (rawUri != null && !rawUri.isEmpty()) {
                s = VpnSubscriptionClient.parseVlessLine(rawUri);
                if (s == null) {
                    s = new VpnServer(rawUri, host, port, flag, country, remark, lastLatencyMs, sni, security);
                }
            } else {
                s = new VpnServer("", host, port, flag, country, remark, lastLatencyMs, sni, security);
            }
            s.setAlive(true);
            s.setPingMs(lastLatencyMs > 0 ? lastLatencyMs : 45);
            s.setRemark("⭐ Проверен на сотовой связи: " + remark);
            return s;
        }

        public double calculateScore() {
            long ageHours = (System.currentTimeMillis() - lastSuccessMs) / (3600L * 1000L);
            return (successCount * 300.0) - (consecutiveFailures * 120.0) - (lastLatencyMs * 0.3) - (ageHours * 5.0);
        }
    }

    public static MobileProvenConfigStorage getInstance(Context context) {
        if (instance == null) {
            synchronized (MobileProvenConfigStorage.class) {
                if (instance == null) {
                    instance = new MobileProvenConfigStorage(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private MobileProvenConfigStorage(Context context) {
        this.appContext = context;
        loadFromStash();
    }

    /**
     * Загружает сохраненные доказанные узлы из зашифрованного тайника.
     */
    private synchronized void loadFromStash() {
        if (isLoaded) return;
        isLoaded = true;
        entries.clear();

        File file = new File(appContext.getFilesDir(), STASH_FILE_NAME);
        if (!file.exists() || file.length() == 0) {
            return;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            if (read > 0) {
                String decrypted = decryptWithDeviceSalt(appContext, data);
                if (decrypted != null && decrypted.startsWith("[")) {
                    JSONArray arr = new JSONArray(decrypted);
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        ProvenEntry entry = ProvenEntry.fromJson(obj);
                        if (entry != null && entry.serverKey != null && !entry.serverKey.isEmpty()) {
                            // Sanitization: Evict blocked SNIs and blocked Sweden host from proven stash
                            if (ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileSni(entry.sni)
                                    || ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileHost(entry.host)) {
                                AppLogger.w(TAG, "Evicting blocked mobile node from proven stash: " + entry.serverKey + " (sni=" + entry.sni + ")");
                                continue;
                            }
                            // Проверка устаревания (7 суток)
                            if (now - entry.lastSuccessMs <= MAX_AGE_MS) {
                                entries.add(entry);
                            }
                        }
                    }
                    AppLogger.i(TAG, "Loaded " + entries.size() + " mobile-proven nodes from secure stash");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read mobile-proven stash: " + e.getMessage());
        }
    }

    /**
     * Фиксирует успешное подключение и подтвержденное рукопожатие на сотовой сети.
     */
    public synchronized boolean isProven(String serverKey) {
        if (serverKey == null || serverKey.isEmpty()) return false;
        loadFromStash();
        long now = System.currentTimeMillis();
        for (ProvenEntry e : entries) {
            if (e.serverKey.equalsIgnoreCase(serverKey)) {
                e.decayFailuresIfNeeded(now);
                if (e.consecutiveFailures < e.getMaxConsecutiveFailures()) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized void recordSuccess(VpnServer server, int latencyMs) {
        if (server == null || server.getHost() == null || server.getHost().isEmpty()) {
            return;
        }
        if (ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileSni(server.getSni())
                || ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileHost(server.getHost())) {
            AppLogger.w(TAG, "Refusing to record blocked mobile node to proven stash: " + server.getServerKey());
            return;
        }

        loadFromStash();
        String key = server.getServerKey();
        ProvenEntry found = null;
        for (ProvenEntry e : entries) {
            if (e.serverKey.equalsIgnoreCase(key)) {
                found = e;
                break;
            }
        }

        long now = System.currentTimeMillis();
        if (found != null) {
            found.successCount++;
            found.consecutiveFailures = 0;
            found.lastFailureMs = 0L;
            found.lastSuccessMs = now;
            if (latencyMs > 0) {
                found.lastLatencyMs = latencyMs;
            }
            if (server.getRawUri() != null && !server.getRawUri().isEmpty()) {
                found.rawUri = server.getRawUri();
            }
        } else {
            found = new ProvenEntry();
            found.serverKey = key;
            found.rawUri = server.getRawUri();
            found.host = server.getHost();
            found.port = server.getPort();
            found.flag = server.getFlag();
            found.country = server.getCountry();
            found.remark = server.getRemark();
            found.sni = server.getSni();
            found.security = server.getSecurity();
            found.lastSuccessMs = now;
            found.lastFailureMs = 0L;
            found.successCount = 1;
            found.consecutiveFailures = 0;
            found.lastLatencyMs = latencyMs > 0 ? latencyMs : 50;
            entries.add(found);
        }

        AppLogger.i(TAG, "✅ [MobileProven] Stored sticky working cellular node: " + found.remark
                + " [" + found.serverKey + "] (successCount=" + found.successCount
                + ", RTT=" + found.lastLatencyMs + "ms)");

        setStickyServerKey(key);
        persistToStash();
    }

    /**
     * Фиксирует неудачу рукопожатия на сотовой сети для пенализации/отбраковки узла.
     */
    public synchronized void recordFailure(String serverKey) {
        if (serverKey == null || serverKey.isEmpty()) return;

        loadFromStash();
        long now = System.currentTimeMillis();
        boolean foundEntry = false;
        for (ProvenEntry e : entries) {
            if (e.serverKey.equalsIgnoreCase(serverKey)) {
                foundEntry = true;
                e.decayFailuresIfNeeded(now);
                e.consecutiveFailures++;
                e.lastFailureMs = now;
                int maxFails = e.getMaxConsecutiveFailures();
                AppLogger.w(TAG, "⚠️ [MobileProven] Node failed on cellular: " + e.serverKey
                        + " (fail #" + e.consecutiveFailures + "/" + maxFails + ", successCount=" + e.successCount + ")");
                // Preserve sticky across a single transient hiccup; clear only after 2+ consecutive failures
                if (e.consecutiveFailures >= 2 && serverKey.equalsIgnoreCase(getStickyServerKey())) {
                    clearStickyServerKey();
                }
                if (e.consecutiveFailures >= maxFails) {
                    AppLogger.w(TAG, "🛑 [MobileProven] Node " + e.serverKey
                            + " temporarily demoted due to " + e.consecutiveFailures + " consecutive cellular failures.");
                }
                persistToStash();
                break;
            }
        }
        if (!foundEntry && serverKey.equalsIgnoreCase(getStickyServerKey())) {
            clearStickyServerKey();
        }
    }

    /**
     * Возвращает отсортированный список доказанно работавших на сотовой связи серверов.
     * Не допускает перманентного исключения при временных сбоях радиоканала.
     */
    public synchronized List<VpnServer> getProvenMobileServers() {
        loadFromStash();
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        List<ProvenEntry> valid = new ArrayList<>();
        List<ProvenEntry> coolingDown = new ArrayList<>();
        List<ProvenEntry> highSuccessFallback = new ArrayList<>();

        for (ProvenEntry e : entries) {
            if (e == null || e.host == null || e.host.isEmpty()) continue;
            if (MainActivity.isKnownDeadHost(e.host)) continue;
            if (ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileHost(e.host)) continue;
            if (ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileSni(e.sni)) continue;
            if (now - e.lastSuccessMs > MAX_AGE_MS) continue;

            e.decayFailuresIfNeeded(now);

            if (e.consecutiveFailures < e.getMaxConsecutiveFailures()) {
                if (!e.isCoolingDown(now)) {
                    valid.add(e);
                } else {
                    coolingDown.add(e);
                }
            } else if (e.successCount >= 3) {
                // Сервер многократно доказывал свою надежность (например 15 успехов) — не теряем его
                highSuccessFallback.add(e);
            }
        }

        List<ProvenEntry> target = !valid.isEmpty() ? valid : (!coolingDown.isEmpty() ? coolingDown : highSuccessFallback);
        if (target.isEmpty()) {
            return Collections.emptyList();
        }

        Collections.sort(target, new Comparator<ProvenEntry>() {
            @Override
            public int compare(ProvenEntry a, ProvenEntry b) {
                return Double.compare(b.calculateScore(), a.calculateScore());
            }
        });

        List<VpnServer> result = new ArrayList<>();
        for (ProvenEntry pe : target) {
            result.add(pe.toVpnServer());
        }

        AppLogger.i(TAG, "Returned " + result.size() + " proven mobile servers (Top: "
                + result.get(0).getDisplayName() + ", mode=" + (!valid.isEmpty() ? "READY" : (!coolingDown.isEmpty() ? "COOLDOWN_FALLBACK" : "HISTORICAL_FALLBACK")) + ")");
        return result;
    }

    /**
     * Возвращает топовый доказанно работавший сервер для моментального подключения на сотовой связи.
     */
    public synchronized VpnServer getBestProvenServer() {
        List<VpnServer> list = getProvenMobileServers();
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    /**
     * Returns the last working sticky cellular server so mobile connections reuse
     * the same working config until it actually fails (avoiding redundant wave probes).
     */
    public synchronized VpnServer getStickyMobileServer() {
        loadFromStash();
        String stickyKey = getStickyServerKey();
        long now = System.currentTimeMillis();
        if (stickyKey != null && !stickyKey.isEmpty()) {
            for (ProvenEntry e : entries) {
                if (e != null && stickyKey.equalsIgnoreCase(e.serverKey)) {
                    if (e.host != null && !e.host.isEmpty()
                            && !MainActivity.isKnownDeadHost(e.host)
                            && !ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileHost(e.host)
                            && !ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileSni(e.sni)
                            && (now - e.lastSuccessMs <= MAX_AGE_MS)) {
                        e.decayFailuresIfNeeded(now);
                        if (e.consecutiveFailures < 2 && !e.isCoolingDown(now)) {
                            AppLogger.d(TAG, "⚡ [StickyMobile] Reusing remembered working node: " + e.serverKey);
                            return e.toVpnServer();
                        }
                    }
                    break;
                }
            }
        }
        return getBestProvenServer();
    }

    /**
     * For explicit user-initiated connect on cellular: returns the last used working config
     * even if a transient cooldown was recorded earlier, falling back to the best proven historical config.
     */
    public synchronized VpnServer getStickyOrBestForUserConnect() {
        loadFromStash();
        String stickyKey = getStickyServerKey();
        long now = System.currentTimeMillis();
        if (stickyKey != null && !stickyKey.isEmpty()) {
            for (ProvenEntry e : entries) {
                if (e != null && stickyKey.equalsIgnoreCase(e.serverKey)
                        && e.host != null && !e.host.isEmpty()
                        && !MainActivity.isKnownDeadHost(e.host)
                        && !ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileHost(e.host)
                        && !ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileSni(e.sni)
                        && (now - e.lastSuccessMs <= MAX_AGE_MS)) {
                    e.decayFailuresIfNeeded(now);
                    boolean failedJustNow = (e.consecutiveFailures >= 1 && e.lastFailureMs > 0 && (now - e.lastFailureMs) < 120_000L);
                    if (e.consecutiveFailures < 3 && !failedJustNow) {
                        AppLogger.i(TAG, "⚡ [StickyMobile] Restoring previously used working node: " + e.serverKey);
                        return e.toVpnServer();
                    }
                }
            }
        }
        return getBestProvenServer();
    }

    public String getStickyServerKey() {
        try {
            return appContext.getSharedPreferences(STICKY_PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_STICKY_CELLULAR_KEY, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setStickyServerKey(String serverKey) {
        if (serverKey == null || serverKey.isEmpty()) return;
        try {
            appContext.getSharedPreferences(STICKY_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_STICKY_CELLULAR_KEY, serverKey).apply();
        } catch (Throwable ignored) {}
    }

    public void clearStickyServerKey() {
        try {
            appContext.getSharedPreferences(STICKY_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(KEY_STICKY_CELLULAR_KEY).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * Записывает текущие записи в зашифрованный тайник.
     */
    private synchronized void persistToStash() {
        try {
            JSONArray arr = new JSONArray();
            int count = 0;
            for (ProvenEntry e : entries) {
                if (e != null) {
                    JSONObject obj = e.toJson();
                    if (obj != null) {
                        arr.put(obj);
                        count++;
                        if (count >= 20) break; // Лимит до 20 лучших проверенных мобильных нод
                    }
                }
            }

            String jsonPayload = arr.toString();
            byte[] encrypted = encryptWithDeviceSalt(appContext, jsonPayload);

            File target = new File(appContext.getFilesDir(), STASH_FILE_NAME);
            File temp = new File(appContext.getFilesDir(), STASH_FILE_NAME + ".tmp");

            try (FileOutputStream fos = new FileOutputStream(temp)) {
                fos.write(encrypted);
                fos.flush();
            }

            if (!temp.renameTo(target)) {
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(encrypted);
                    fos.flush();
                }
                temp.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to persist mobile proven stash: " + e.getMessage(), e);
        }
    }

    public synchronized int getCount() {
        loadFromStash();
        return entries.size();
    }

    private static byte[] encryptWithDeviceSalt(Context context, String text) {
        byte[] raw = GoblinSecurityVault.encrypt(text);
        byte[] salt = getDeviceSalt(context);
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (raw[i] ^ salt[i % salt.length]);
        }
        return raw;
    }

    private static String decryptWithDeviceSalt(Context context, byte[] data) {
        byte[] salt = getDeviceSalt(context);
        byte[] unSalted = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            unSalted[i] = (byte) (data[i] ^ salt[i % salt.length]);
        }
        return GoblinSecurityVault.decrypt(unSalted);
    }

    private static byte[] getDeviceSalt(Context context) {
        String base = Build.MODEL + ":" + Build.BRAND + ":" + context.getPackageName();
        return base.getBytes(StandardCharsets.UTF_8);
    }
}
