package ru.llazyrin.goblinvpn.net;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import ru.llazyrin.goblinvpn.model.NetworkPreferences;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.service.AppLogger;
import ru.llazyrin.goblinvpn.service.GoblinVpnService;
import ru.llazyrin.goblinvpn.service.VpnState;

/**
 * AppTelemetryClient — Высоконадёжный клиент телеметрии и аналитики Goblin VPN.
 *
 * Функции:
 * 1. Отправка heartbeat статуса на https://llazyrin.online/api/app/heartbeat
 *    Обработка флага logging_enabled из ответа сервера.
 * 2. Дистанционная отправка пакетов логов (sendBatchLogs) на https://llazyrin.online/api/app/logs
 * 3. Получение актуальной публичной статистики (онлайн пользователей, VPN соединений, серверов, объявлений)
 *    с https://llazyrin.online/api/app/stats/public
 * 4. Автоматическая защита сокетов через GoblinVpnService.protectSocket(...) при активном VPN
 * 5. Полная отказоустойчивость: таймауты 5000мс, фоновый поток без блокировки UI, graceful fallback без крашей.
 */
public class AppTelemetryClient {
    private static final String TAG = "AppTelemetryClient";

    public static final String HEARTBEAT_URL = "https://llazyrin.online/api/app/heartbeat";
    public static final String STATS_URL = "https://llazyrin.online/api/app/stats/public";
    public static final String LOGS_URL = "https://llazyrin.online/api/app/logs";
    public static final String COMMUNITY_SERVERS_SHARE_URL = "https://llazyrin.online/api/app/community/servers/share";
    public static final String AUTH_CHECK_SUBSCRIPTION_URL = "https://llazyrin.online/api/app/auth/check-subscription";

    private static final String PREF_NAME = "goblin_vpn_telemetry";
    private static final String PREF_REMOTE_LOGGING = "remote_logging_enabled";
    private static final int TIMEOUT_MS = 5000;
    private static final long COMMUNITY_SHARE_COOLDOWN_MS = 1_800_000L; // 30 minutes per server
    private static final Map<String, Long> LAST_COMMUNITY_SHARE_MS = new ConcurrentHashMap<>();

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "GoblinTelemetryWorker");
            t.setDaemon(true);
            return t;
        }
    });

    private static volatile Context appContext = null;
    private static volatile boolean remoteLoggingEnabled = false;
    private static volatile LoggingStateListener loggingStateListener = null;
    private static volatile AppPublicStats lastStats = null;

    public interface LoggingStateListener {
        void onLoggingStateChanged(boolean enabled);
    }

    public static void setLoggingStateListener(LoggingStateListener listener) {
        loggingStateListener = listener;
    }

    public static void init(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
            try {
                remoteLoggingEnabled = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .getBoolean(PREF_REMOTE_LOGGING, false);
            } catch (Exception ignored) {}
        }
    }

    public static boolean isRemoteLoggingEnabled() {
        return remoteLoggingEnabled;
    }

    public static void setRemoteLoggingEnabled(final boolean enabled) {
        boolean changed = (remoteLoggingEnabled != enabled);
        remoteLoggingEnabled = enabled;
        if (changed) {
            Log.i(TAG, "Remote logging status updated by server: " + enabled);
            if (appContext != null) {
                try {
                    appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_REMOTE_LOGGING, enabled)
                            .apply();
                } catch (Exception ignored) {}
            }
            AppLogger.onRemoteLoggingStateChanged(enabled);

            final LoggingStateListener listener = loggingStateListener;
            if (listener != null) {
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onLoggingStateChanged(enabled);
                    }
                });
            }
        }
    }

    public static String getOrCreateClientId(Context context) {
        Context target = (context != null) ? context.getApplicationContext() : appContext;
        if (target != null) {
            try {
                return NetworkPreferences.getInstance(target).getClientId();
            } catch (Exception ignored) {}
        }
        return "0000000000000000";
    }

    /**
     * DTO для публичной статистики сети.
     */
    public static class AppPublicStats implements Serializable {
        public final int onlineUsers;
        public final int connectedVpnUsers;
        public final int totalUsers;
        public final int activeServers;
        public final String announcement;

        public AppPublicStats(int onlineUsers, int connectedVpnUsers, int totalUsers, int activeServers, String announcement) {
            this.onlineUsers = onlineUsers;
            this.connectedVpnUsers = connectedVpnUsers;
            this.totalUsers = totalUsers;
            this.activeServers = activeServers;
            this.announcement = announcement != null ? announcement.trim() : "";
        }

        public boolean hasAnnouncement() {
            return announcement != null && !announcement.isEmpty();
        }

        @Override
        public String toString() {
            return "AppPublicStats{" +
                    "onlineUsers=" + onlineUsers +
                    ", connectedVpnUsers=" + connectedVpnUsers +
                    ", totalUsers=" + totalUsers +
                    ", activeServers=" + activeServers +
                    ", announcement='" + announcement + '\'' +
                    '}';
        }
    }

    public interface StatsCallback {
        void onStatsLoaded(AppPublicStats stats);
        void onError(Exception error);
    }

    public static AppPublicStats getLastKnownStats() {
        return lastStats;
    }

    /**
     * Отправка Heartbeat на сервер с текущим состоянием VPN.
     */
    public static void sendHeartbeat(final Context context) {
        if (context == null) return;
        boolean isConnected = (GoblinVpnService.getCurrentState() == VpnState.CONNECTED);
        VpnServer server = GoblinVpnService.getCurrentServer();
        sendHeartbeat(context, isConnected, server);
    }

    /**
     * Отправка Heartbeat на сервер с явным статусом соединения и сервером.
     */
    public static void sendHeartbeat(final Context context, final boolean isConnected, final VpnServer server) {
        if (context == null) return;
        init(context);
        final Context currentContext = (appContext != null) ? appContext : context.getApplicationContext();

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String clientId = getOrCreateClientId(currentContext);
                    String deviceModel = getDeviceModel();
                    String appVersion = getAppVersion(currentContext);
                    String country = (server != null && server.getCountry() != null) ? server.getCountry() : "";
                    String host = (server != null && server.getHost() != null) ? server.getHost() : "";

                    JSONObject json = new JSONObject();
                    json.put("client_id", clientId);
                    json.put("device_model", deviceModel);
                    json.put("app_version", appVersion);
                    json.put("is_connected", isConnected);
                    json.put("server_country", country);
                    json.put("server_host", host);
                    json.put("logging_consent", NetworkPreferences.getInstance(currentContext).isUserLoggingConsentGranted());

                    byte[] postData = json.toString().getBytes(StandardCharsets.UTF_8);

                    conn = openHttpsConnection(HEARTBEAT_URL);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setFixedLengthStreamingMode(postData.length);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postData);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    Log.d(TAG, "Heartbeat sent [is_connected=" + isConnected + ", server=" + host + "], response: " + code);

                    if (code == HttpURLConnection.HTTP_OK) {
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = in.readLine()) != null) {
                                sb.append(line);
                            }
                            String respStr = sb.toString();
                            if (!respStr.isEmpty()) {
                                JSONObject respJson = new JSONObject(respStr);
                                if (respJson.has("logging_enabled")) {
                                    boolean enabled = respJson.optBoolean("logging_enabled", false);
                                    setRemoteLoggingEnabled(enabled);
                                }
                                if (respJson.has("announcement")) {
                                    String announcement = respJson.optString("announcement", "");
                                    if (announcement != null && !announcement.trim().isEmpty()) {
                                        NotificationCenter.handleNewAnnouncement(currentContext, announcement);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Heartbeat failed gracefully: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    /**
     * Отправка пачки логов на сервер телеметрии https://llazyrin.online/api/app/logs
     * Возвращает true при успешной доставке (HTTP 2xx).
     */
    public static boolean sendBatchLogs(Context context, List<AppLogger.LogItem> logs) {
        if (logs == null || logs.isEmpty()) {
            return true;
        }
        init(context);
        final Context targetCtx = (context != null) ? context.getApplicationContext() : appContext;
        if (targetCtx != null && !NetworkPreferences.getInstance(targetCtx).isUserLoggingConsentGranted()) {
            Log.d(TAG, "sendBatchLogs skipped: user consent not granted");
            return false;
        }

        HttpURLConnection conn = null;
        try {
            String clientId = getOrCreateClientId(targetCtx);
            String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
            String appVersion = getAppVersion(targetCtx);

            JSONObject payload = new JSONObject();
            payload.put("client_id", clientId);
            payload.put("device_model", deviceModel);
            payload.put("app_version", appVersion);

            JSONArray logsArray = new JSONArray();
            for (AppLogger.LogItem item : logs) {
                if (item != null) {
                    logsArray.put(item.toJsonObject());
                }
            }
            payload.put("logs", logsArray);

            byte[] postData = payload.toString().getBytes(StandardCharsets.UTF_8);

            conn = openHttpsConnection(LOGS_URL);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setFixedLengthStreamingMode(postData.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
                os.flush();
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "sendBatchLogs sent " + logs.size() + " items, HTTP code: " + code);

            if (code >= 200 && code < 300) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        sb.append(line);
                    }
                    String respStr = sb.toString();
                    if (!respStr.isEmpty()) {
                        JSONObject respJson = new JSONObject(respStr);
                        if (respJson.has("logging_enabled")) {
                            setRemoteLoggingEnabled(respJson.optBoolean("logging_enabled", false));
                        }
                    }
                } catch (Exception ignored) {}
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "sendBatchLogs failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Асинхронный запрос публичной статистики онлайна.
     * Результат или ошибка доставляются на Main UI thread.
     */
    public static void fetchStatsAsync(final StatsCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                BufferedReader reader = null;
                try {
                    conn = openHttpsConnection(STATS_URL);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "application/json");

                    int code = conn.getResponseCode();
                    if (code == HttpURLConnection.HTTP_OK) {
                        reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }

                        JSONObject json = new JSONObject(sb.toString());
                        final AppPublicStats stats = new AppPublicStats(
                                json.optInt("online_users", 0),
                                json.optInt("connected_vpn_users", 0),
                                json.optInt("total_users", 0),
                                json.optInt("active_servers", 0),
                                json.optString("announcement", "")
                        );
                        lastStats = stats;

                        if (stats.hasAnnouncement()) {
                            NotificationCenter.handleNewAnnouncement(appContext, stats.announcement);
                        }

                        if (callback != null) {
                            MAIN_HANDLER.post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onStatsLoaded(stats);
                                }
                            });
                        }
                    } else {
                        throw new IOException("Server returned HTTP " + code);
                    }
                } catch (final Exception e) {
                    Log.w(TAG, "fetchStatsAsync failed: " + e.getMessage());
                    if (callback != null) {
                        MAIN_HANDLER.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(e);
                            }
                        });
                    }
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (Exception ignored) {}
                    }
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    /**
     * Reports node latency and speed to Community Server Sharing endpoint
     * /api/app/community/servers/share if enabled in NetworkPreferences.
     */
    public static void reportCommunityServerShare(final Context context, final VpnServer server, final int latencyMs, final long speedKbps) {
        if (context == null || server == null) return;
        init(context);
        final Context currentContext = (appContext != null) ? appContext : context.getApplicationContext();

        NetworkPreferences prefs = NetworkPreferences.getInstance(currentContext);
        if (!prefs.isShareWithCommunityEnabled()) {
            return;
        }

        final int effPing = latencyMs > 0 ? latencyMs : server.getPingMs();
        if (effPing <= 0) {
            return;
        }

        String rawKey = server.getServerKey();
        if (rawKey == null || rawKey.trim().isEmpty()) {
            rawKey = (server.getHost() != null ? server.getHost() : "") + ":" + server.getPort();
        }
        String normKey = rawKey.trim().toLowerCase(Locale.ROOT);
        long now = SystemClock.elapsedRealtime();
        synchronized (LAST_COMMUNITY_SHARE_MS) {
            Long lastTs = LAST_COMMUNITY_SHARE_MS.get(normKey);
            if (lastTs != null && (now - lastTs) < COMMUNITY_SHARE_COOLDOWN_MS) {
                return;
            }
            LAST_COMMUNITY_SHARE_MS.put(normKey, now);
        }

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String clientId = getOrCreateClientId(currentContext);
                    JSONObject json = new JSONObject();
                    json.put("client_id", clientId);
                    json.put("server_id", server.getServerKey());
                    json.put("server_host", server.getHost() != null ? server.getHost() : "");
                    json.put("port", server.getPort());
                    json.put("country", server.getCountry() != null ? server.getCountry() : "");
                    json.put("flag", server.getFlag() != null ? server.getFlag() : "");
                    json.put("latency_ms", effPing);
                    json.put("speed_kbps", speedKbps);
                    json.put("timestamp", System.currentTimeMillis());

                    byte[] postData = json.toString().getBytes(StandardCharsets.UTF_8);

                    conn = openHttpsConnection(COMMUNITY_SERVERS_SHARE_URL);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setFixedLengthStreamingMode(postData.length);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postData);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    Log.d(TAG, "reportCommunityServerShare sent for " + server.getHost() + " (code=" + code + ")");
                } catch (Exception e) {
                    Log.w(TAG, "reportCommunityServerShare failed: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    public interface SubscriptionCheckCallback {
        void onResult(boolean subscribed, String message);
        void onError(Exception error);
    }

    /**
     * Checks user's Telegram subscription against backend API.
     */
    public static void checkSubscriptionAsync(final Context context, final String telegramHandle, final SubscriptionCheckCallback callback) {
        if (context == null) return;
        init(context);
        final Context currentContext = (appContext != null) ? appContext : context.getApplicationContext();

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String clientId = getOrCreateClientId(currentContext);
                    JSONObject json = new JSONObject();
                    json.put("client_id", clientId);
                    if (telegramHandle != null && !telegramHandle.trim().isEmpty()) {
                        json.put("telegram_handle", telegramHandle.trim());
                        json.put("telegram_id", telegramHandle.trim());
                    }

                    byte[] postData = json.toString().getBytes(StandardCharsets.UTF_8);

                    conn = openHttpsConnection(AUTH_CHECK_SUBSCRIPTION_URL);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setFixedLengthStreamingMode(postData.length);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postData);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    Log.d(TAG, "checkSubscription response code: " + code);

                    InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                    String responseBody = "";
                    if (is != null) {
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = in.readLine()) != null) {
                                sb.append(line);
                            }
                            responseBody = sb.toString();
                        }
                    }

                    boolean subscribed = false;
                    String message = "";
                    if (!responseBody.isEmpty()) {
                        try {
                            JSONObject respJson = new JSONObject(responseBody);
                            subscribed = respJson.optBoolean("subscribed", false);
                            message = respJson.optString("message", "");
                        } catch (Exception ignored) {}
                    }

                    final boolean finalSubscribed = subscribed;
                    final String finalMessage = message;
                    if (callback != null) {
                        MAIN_HANDLER.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onResult(finalSubscribed, finalMessage);
                            }
                        });
                    }
                } catch (final Exception e) {
                    Log.w(TAG, "checkSubscription failed: " + e.getMessage());
                    if (callback != null) {
                        MAIN_HANDLER.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(e);
                            }
                        });
                    }
                } finally {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    private static HttpURLConnection openHttpsConnection(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "GoblinVPN/2.4.0 (Android)");

        if (conn instanceof HttpsURLConnection) {
            try {
                SSLSocketFactory defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
                ((HttpsURLConnection) conn).setSSLSocketFactory(new TelemetrySSLSocketFactory(defaultFactory));
            } catch (Throwable t) {
                Log.w(TAG, "Custom SSLSocketFactory setup skipped: " + t.getMessage());
            }
        }
        return conn;
    }

    private static String getDeviceModel() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model != null && manufacturer != null && model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        }
        return capitalize(manufacturer) + " " + (model != null ? model : "Device");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String getAppVersion(Context context) {
        if (context != null) {
            try {
                return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception ignored) {}
        }
        return "2.4.0";
    }

    /**
     * Кастомная фабрика SSL-сокетов с защитой сокетов от заворачивания в туннель VPN
     * через GoblinVpnService.protectSocket(...)
     */
    private static class TelemetrySSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        TelemetrySSLSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket protect(Socket socket) {
            if (socket != null) {
                try {
                    GoblinVpnService.protectSocket(socket);
                } catch (Throwable ignored) {}
            }
            return socket;
        }

        @Override
        public Socket createSocket() throws IOException {
            Socket s = delegate.createSocket();
            return protect(s);
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            protect(s);
            Socket ssl = delegate.createSocket(s, host, port, autoClose);
            return protect(ssl);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket plain = new Socket();
            protect(plain);
            plain.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return delegate.createSocket(plain, host, port, true);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket plain = new Socket();
            protect(plain);
            plain.bind(new InetSocketAddress(localHost, localPort));
            plain.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return delegate.createSocket(plain, host, port, true);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return createSocket(host.getHostAddress(), port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return createSocket(address.getHostAddress(), port, localAddress, localPort);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }
    }
}
