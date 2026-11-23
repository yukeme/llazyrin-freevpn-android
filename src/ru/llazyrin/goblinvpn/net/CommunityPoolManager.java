package ru.llazyrin.goblinvpn.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

import ru.llazyrin.goblinvpn.MainActivity;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.security.GoblinSecurityVault;
import ru.llazyrin.goblinvpn.service.AppLogger;
import ru.llazyrin.goblinvpn.service.GoblinVpnService;

/**
 * CommunityPoolManager
 *
 * Manages verified high-reliability community servers:
 * - Fetches community-top configs from backend (https://llazyrin.online/api/app/community/servers/top)
 * - Caches validated servers locally on disk (community_top_cache.json)
 * - Provides builtin resilient fallback seeds (Stockholm Ultra, Yandex Reality, VK Whitelist)
 * - Automatically reports stable nodes (uptime > 60s, traffic > 100 KB, RTT < 250ms, 0 drops)
 *   to the community rating API.
 */
public class CommunityPoolManager {

    private static final String TAG = "CommunityPoolManager";
    private static final String TOP_SERVERS_URL = "https://llazyrin.online/api/app/community/servers/top";
    private static final String SHARE_SERVER_URL = "https://llazyrin.online/api/app/community/servers/share";
    private static final String CACHE_FILE_NAME = "community_top_cache.json";
    private static final String PREFS_NAME = "goblin_community_pool";
    private static final String KEY_LAST_SHARE_PREFIX = "last_share_";

    private static volatile CommunityPoolManager sInstance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<VpnServer> cachedTopServers = new ArrayList<>();
    private long lastFetchTimestamp = 0;

    public interface CommunityCallback {
        void onLoaded(List<VpnServer> topServers);
    }

    private CommunityPoolManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadDiskCache();
    }

    public static CommunityPoolManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (CommunityPoolManager.class) {
                if (sInstance == null) {
                    sInstance = new CommunityPoolManager(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * Returns top community servers from in-memory cache, disk cache, or builtin seeds.
     * Never returns an empty list.
     */
    public synchronized List<VpnServer> getTopCommunityServers() {
        if (!cachedTopServers.isEmpty()) {
            return new ArrayList<>(cachedTopServers);
        }
        loadDiskCache();
        if (!cachedTopServers.isEmpty()) {
            return new ArrayList<>(cachedTopServers);
        }
        return getBuiltinSeeds();
    }

    /**
     * Asynchronously updates top community nodes from backend.
     */
    public void fetchTopServersAsync(final CommunityCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final List<VpnServer> fetched = fetchTopServersSync();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onLoaded(fetched);
                        }
                    }
                });
            }
        });
    }

    public synchronized List<VpnServer> fetchTopServersSync() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(TOP_SERVERS_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "llazyrinVPN-Android/" + Build.VERSION.RELEASE);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                List<VpnServer> parsed = parseCommunityJson(sb.toString());
                if (!parsed.isEmpty()) {
                    cachedTopServers.clear();
                    cachedTopServers.addAll(parsed);
                    lastFetchTimestamp = System.currentTimeMillis();
                    saveDiskCache(sb.toString());
                    AppLogger.i(TAG, "Successfully fetched and cached " + parsed.size() + " top community servers");
                    return new ArrayList<>(parsed);
                }
            } else {
                AppLogger.w(TAG, "Backend returned HTTP " + code + " for community top servers");
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "Failed to fetch community top servers: " + t.getMessage());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }

        return getTopCommunityServers();
    }

    private List<VpnServer> parseCommunityJson(String jsonStr) {
        List<VpnServer> list = new ArrayList<>();
        if (jsonStr == null || jsonStr.trim().isEmpty()) return list;

        try {
            JSONArray arr = null;
            if (jsonStr.trim().startsWith("[")) {
                arr = new JSONArray(jsonStr);
            } else if (jsonStr.trim().startsWith("{")) {
                JSONObject root = new JSONObject(jsonStr);
                if (root.has("servers")) {
                    arr = root.getJSONArray("servers");
                }
            }

            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String rawUri = obj.optString("rawUri", obj.optString("uri", ""));
                    if (rawUri.isEmpty()) continue;

                    VpnServer s = VpnSubscriptionClient.parseVlessLine(rawUri);
                    if (s == null) {
                        String host = obj.optString("host");
                        int port = obj.optInt("port", 443);
                        String flag = obj.optString("flag", "⭐");
                        String country = obj.optString("country", "Топ узел");
                        String remark = obj.optString("remark", "⭐ Топ сообщества");
                        int ping = obj.optInt("pingMs", 45);
                        String sni = obj.optString("sni", host);
                        String sec = obj.optString("security", "reality");
                        s = new VpnServer(rawUri, host, port, flag, country, remark, ping, sni, sec);
                    }

                    if (s != null && !MainActivity.isKnownDeadHost(s.getHost())) {
                        s.setCommunityTop(true);
                        s.setCommunityScore(obj.optDouble("score", 9.8));
                        s.setCommunityBadge(obj.optString("badge", "⭐ ТОП"));
                        list.add(s);
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "Error parsing community top JSON", t);
        }
        return list;
    }

    private synchronized void saveDiskCache(String jsonStr) {
        try {
            File f = new File(appContext.getFilesDir(), CACHE_FILE_NAME);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
        } catch (Throwable t) {
            AppLogger.w(TAG, "Failed to save community cache: " + t.getMessage());
        }
    }

    private synchronized void loadDiskCache() {
        try {
            File f = new File(appContext.getFilesDir(), CACHE_FILE_NAME);
            if (f.exists() && f.length() > 0) {
                FileInputStream fis = new FileInputStream(f);
                byte[] data = new byte[(int) f.length()];
                int read = fis.read(data);
                fis.close();
                if (read > 0) {
                    String json = new String(data, StandardCharsets.UTF_8);
                    List<VpnServer> parsed = parseCommunityJson(json);
                    if (!parsed.isEmpty()) {
                        cachedTopServers.clear();
                        cachedTopServers.addAll(parsed);
                        AppLogger.i(TAG, "Loaded " + parsed.size() + " community servers from disk cache");
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "Failed to load community disk cache: " + t.getMessage());
        }
    }

    /**
     * Reports a confirmed stable server to the community backend.
     * Deduplicates submissions: max once per server per 12 hours.
     */
    public void shareStableServerAsync(final VpnServer server, final int pingMs, final long uptimeSec, final long rxBytes) {
        if (server == null || server.getRawUri() == null || server.getHost() == null) return;

        final String host = server.getHost();
        long lastShare = prefs.getLong(KEY_LAST_SHARE_PREFIX + host, 0);
        long now = System.currentTimeMillis();
        if (now - lastShare < 12 * 60 * 60 * 1000L) {
            return; // Already shared within last 12 hours
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("rawUri", server.getRawUri());
                    payload.put("host", server.getHost());
                    payload.put("port", server.getPort());
                    payload.put("country", server.getCountry());
                    payload.put("pingMs", pingMs);
                    payload.put("uptimeSec", uptimeSec);
                    payload.put("rxBytes", rxBytes);
                    payload.put("appVersion", "2.7.5");

                    URL url = new URL(SHARE_SERVER_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("User-Agent", "llazyrinVPN-Android/" + Build.VERSION.RELEASE);

                    byte[] postData = payload.toString().getBytes(StandardCharsets.UTF_8);
                    OutputStream os = conn.getOutputStream();
                    os.write(postData);
                    os.flush();
                    os.close();

                    int resCode = conn.getResponseCode();
                    if (resCode == 200 || resCode == 201) {
                        prefs.edit().putLong(KEY_LAST_SHARE_PREFIX + host, System.currentTimeMillis()).apply();
                        AppLogger.i(TAG, "🌟 Successfully shared stable node [" + server.getDisplayName()
                                + "] with community! (res=" + resCode + ")");
                    }
                } catch (Throwable t) {
                    AppLogger.w(TAG, "Failed to share stable server with community: " + t.getMessage());
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Throwable ignored) {}
                    }
                }
            }
        });
    }

    /**
     * Builtin ultra-reliable fallback seeds with community top badges.
     */
    private List<VpnServer> getBuiltinSeeds() {
        List<VpnServer> seeds = new ArrayList<>();
        List<String> rawUris = GoblinSecurityVault.getBuiltinEmergencySeeds();
        for (String uri : rawUris) {
            VpnServer s = VpnSubscriptionClient.parseVlessLine(uri);
            if (s != null && !MainActivity.isKnownDeadHost(s.getHost())) {
                s.setCommunityTop(true);
                s.setCommunityBadge("⭐ ТОП");
                s.setCommunityScore(9.9);
                seeds.add(s);
            }
        }
        return seeds;
    }
}
