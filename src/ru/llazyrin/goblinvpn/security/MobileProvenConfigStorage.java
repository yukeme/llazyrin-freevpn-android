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
            e.successCount = obj.optInt("successCount", 1);
            e.consecutiveFailures = obj.optInt("consecutiveFailures", 0);
            e.lastLatencyMs = obj.optInt("lastLatencyMs", 50);
            return e;
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
            return (successCount * 500.0) - (consecutiveFailures * 400.0) - (lastLatencyMs * 0.5) - (ageHours * 10.0);
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
    public synchronized void recordSuccess(VpnServer server, int latencyMs) {
        if (server == null || server.getHost() == null || server.getHost().isEmpty()) {
            return;
        }
        if (!ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isSniPermittedOnCellular(server.getSni())) {
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
            found.successCount = 1;
            found.consecutiveFailures = 0;
            found.lastLatencyMs = latencyMs > 0 ? latencyMs : 50;
            entries.add(found);
        }

        AppLogger.i(TAG, "✅ [MobileProven] Stored working cellular node: " + found.remark
                + " [" + found.serverKey + "] (successCount=" + found.successCount
                + ", RTT=" + found.lastLatencyMs + "ms)");

        persistToStash();
    }

    /**
     * Фиксирует неудачу рукопожатия на сотовой сети для пенализации/отбраковки узла.
     */
    public synchronized void recordFailure(String serverKey) {
        if (serverKey == null || serverKey.isEmpty()) return;

        loadFromStash();
        for (ProvenEntry e : entries) {
            if (e.serverKey.equalsIgnoreCase(serverKey)) {
                e.consecutiveFailures++;
                AppLogger.w(TAG, "⚠️ [MobileProven] Node failed on cellular: " + e.serverKey
                        + " (fail #" + e.consecutiveFailures + ")");
                if (e.consecutiveFailures >= 3) {
                    AppLogger.w(TAG, "🛑 [MobileProven] Node " + e.serverKey
                            + " demoted due to 3 consecutive cellular failures.");
                }
                persistToStash();
                break;
            }
        }
    }

    /**
     * Проверяет, является ли сервер доказанно работающим на сотовой сети.
     */
    public synchronized boolean isProven(String serverKey) {
        if (serverKey == null || serverKey.isEmpty()) return false;
        loadFromStash();
        long now = System.currentTimeMillis();
        for (ProvenEntry e : entries) {
            if (e != null && serverKey.equalsIgnoreCase(e.serverKey)) {
                return (e.consecutiveFailures < 3 && (now - e.lastSuccessMs <= MAX_AGE_MS));
            }
        }
        return false;
    }

    /**
     * Возвращает отсортированный список доказанно работавших на сотовой связи серверов.
     * Отбраковывает узлы с 3+ сбоями подряд или старше 7 суток.
     */
    public synchronized List<VpnServer> getProvenMobileServers() {
        loadFromStash();
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        List<ProvenEntry> valid = new ArrayList<>();
        for (ProvenEntry e : entries) {
            // Tier 1 retains proven nodes with < 3 consecutive cellular failures and valid cellular SNI
            if (e.consecutiveFailures < 3
                    && (now - e.lastSuccessMs <= MAX_AGE_MS)
                    && !MainActivity.isKnownDeadHost(e.host)
                    && ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isSniPermittedOnCellular(e.sni)) {
                valid.add(e);
            }
        }

        if (valid.isEmpty()) {
            return Collections.emptyList();
        }

        Collections.sort(valid, new Comparator<ProvenEntry>() {
            @Override
            public int compare(ProvenEntry a, ProvenEntry b) {
                return Double.compare(b.calculateScore(), a.calculateScore());
            }
        });

        List<VpnServer> result = new ArrayList<>();
        for (ProvenEntry pe : valid) {
            result.add(pe.toVpnServer());
        }

        AppLogger.i(TAG, "Returned " + result.size() + " proven mobile servers (Top: "
                + result.get(0).getDisplayName() + ")");
        return result;
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
