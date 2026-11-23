package ru.llazyrin.goblinvpn.net;

import ru.llazyrin.goblinvpn.MainActivity;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.llazyrin.goblinvpn.security.MobileProvenConfigStorage;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.service.AppLogger;
import ru.llazyrin.goblinvpn.service.GoblinVpnService;

/**
 * WhitelistPoolManager — Менеджер пула разрешенных IP-диапазонов и доменов для сотовых сетей.
 *
 * Загружает и кэширует whitelist_url.txt с GitHub:
 * https://raw.githubusercontent.com/d010b/custom_geoip/refs/heads/main/whitelist_url.txt
 *
 * Обеспечивает:
 * 1. Проверку принадлежности IP/домена белому списку: isWhitelistedForMobile(hostOrIp).
 * 2. Определение работы на мобильной сети (Cellular/LTE/3G).
 * 3. Приоритезацию VPN-узлов для мобильного интернета.
 */
public class WhitelistPoolManager {
    private static final String TAG = "WhitelistPoolManager";
    public static final String WHITELIST_URL = "https://raw.githubusercontent.com/d010b/custom_geoip/refs/heads/main/whitelist_url.txt";
    private static final String CACHE_FILE_NAME = "cellular_whitelist.txt";

    public static final String MOBILE_CONFIGS_API_URL = "https://llazyrin.online/api/app/mobile-configs";
    public static final String MOBILE_CONFIGS_FALLBACK_URL = "https://llazyrin.online/mobile_whitelist_configs.txt";
    public static final String MOBILE_CONFIGS_CACHE_FILE = "mobile_whitelist_configs.txt";
    public static final String PREFS_NAME = "goblin_whitelist_prefs";
    public static final String KEY_LAST_MOBILE_SYNC = "last_mobile_configs_sync_ms";

    // 6 часов — оптимальный расчетный интервал ротации резервного пула обхода глушилок
    public static final long PERIODIC_MOBILE_SYNC_MS = 6L * 3600L * 1000L;
    // 2 часа — оппортунистическое обновление при наличии активного Wi-Fi
    public static final long OPPORTUNISTIC_WIFI_SYNC_MS = 2L * 3600L * 1000L;

    private static volatile WhitelistPoolManager instance = null;

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Fast bitwise IPv4 CIDR matching
    public static class CidrRange {
        public final int network;
        public final int mask;
        public final String original;

        public CidrRange(int network, int mask, String original) {
            this.network = network;
            this.mask = mask;
            this.original = original;
        }

        public boolean matches(int ip) {
            return (ip & mask) == (network & mask);
        }
    }

    private final List<CidrRange> cidrRanges = new CopyOnWriteArrayList<>();
    private final Set<String> domainSet = Collections.synchronizedSet(new HashSet<String>());
    private volatile boolean isLoaded = false;

    public static WhitelistPoolManager getInstance(Context context) {
        if (instance == null) {
            synchronized (WhitelistPoolManager.class) {
                if (instance == null) {
                    instance = new WhitelistPoolManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private WhitelistPoolManager(Context context) {
        this.appContext = context;
        loadFromCacheOrDefaults();
        refreshWhitelistAsync(null);
        refreshMobileBypassConfigsAsync(false);
    }

    /**
     * Загрузка из локального кэш-файла во внутренней памяти приложения.
     */
    private void loadFromCacheOrDefaults() {
        File cacheFile = new File(appContext.getFilesDir(), CACHE_FILE_NAME);
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try (FileInputStream fis = new FileInputStream(cacheFile);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                parseLines(reader);
                isLoaded = true;
                AppLogger.i(TAG, "Loaded " + cidrRanges.size() + " CIDR ranges and " + domainSet.size() + " domains from cache.");
                return;
            } catch (Exception e) {
                Log.w(TAG, "Failed to load cached whitelist: " + e.getMessage());
            }
        }

        // Built-in initial seeds in case of first launch without internet
        loadInitialSeeds();
    }

    private void loadInitialSeeds() {
        String[] seeds = new String[]{
                "2.60.247.0/24", "2.62.118.0/24", "2.62.127.128/26", "2.63.239.0/24",
                "5.101.40.0/21", "5.101.85.0/24", "5.129.202.0/24", "5.141.28.0/24",
                "5.143.229.0/23", "5.178.85.0/24", "5.181.62.0/24", "5.188.112.0/22",
                "5.255.192.0/18", "77.88.0.0/18", "87.250.250.0/24", "93.158.134.0/24",
                "telegram.org", "t.me", "telegram.me", "telegra.ph"
        };
        for (String seed : seeds) {
            parseAndAddLine(seed);
        }
        isLoaded = true;
    }

    /**
     * Асинхронное обновление списка с удаленного репозитория.
     */
    public void refreshWhitelistAsync(final Runnable onComplete) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(WHITELIST_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("User-Agent", "llazyrinVPN/2.6.1 (Android Mobile)");

                    int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        File cacheFile = new File(appContext.getFilesDir(), CACHE_FILE_NAME);
                        File tempFile = new File(appContext.getFilesDir(), CACHE_FILE_NAME + ".tmp");

                        try (InputStream in = conn.getInputStream();
                             FileOutputStream out = new FileOutputStream(tempFile)) {
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = in.read(buf)) != -1) {
                                out.write(buf, 0, r);
                            }
                            out.flush();
                        }

                        if (tempFile.length() > 0) {
                            if (cacheFile.exists()) {
                                cacheFile.delete();
                            }
                            tempFile.renameTo(cacheFile);

                            try (FileInputStream fis = new FileInputStream(cacheFile);
                                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                                cidrRanges.clear();
                                domainSet.clear();
                                parseLines(reader);
                                isLoaded = true;
                                AppLogger.i(TAG, "Successfully updated whitelist pool: "
                                        + cidrRanges.size() + " CIDRs, " + domainSet.size() + " domains.");
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to refresh whitelist from URL: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Exception ignored) {}
                    }
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            }
        });
    }

    private void parseLines(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            parseAndAddLine(line.trim());
        }
    }

    private void parseAndAddLine(String line) {
        if (line == null || line.isEmpty() || line.startsWith("#")) {
            return;
        }

        if (line.contains("/") || isLikelyIpv4(line)) {
            CidrRange cidr = parseCidr(line);
            if (cidr != null) {
                cidrRanges.add(cidr);
                return;
            }
        }

        // Otherwise domain rule
        domainSet.add(line.toLowerCase(Locale.ROOT));
    }

    private static boolean isLikelyIpv4(String s) {
        if (s == null || s.isEmpty()) return false;
        char c0 = s.charAt(0);
        return (c0 >= '0' && c0 <= '9') && s.contains(".");
    }

    public static CidrRange parseCidr(String cidrStr) {
        if (cidrStr == null || cidrStr.isEmpty()) return null;
        int slash = cidrStr.indexOf('/');
        if (slash != -1) {
            String ipPart = cidrStr.substring(0, slash).trim();
            String prefixPart = cidrStr.substring(slash + 1).trim();
            Integer ip = parseIpv4(ipPart);
            if (ip == null) return null;
            try {
                int prefix = Integer.parseInt(prefixPart);
                if (prefix < 0 || prefix > 32) return null;
                int mask = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));
                return new CidrRange(ip & mask, mask, cidrStr);
            } catch (Exception e) {
                return null;
            }
        } else {
            Integer ip = parseIpv4(cidrStr.trim());
            if (ip != null) {
                return new CidrRange(ip, 0xFFFFFFFF, cidrStr);
            }
        }
        return null;
    }

    public static Integer parseIpv4(String ipStr) {
        if (ipStr == null) return null;
        String[] parts = ipStr.trim().split("\\.");
        if (parts.length != 4) return null;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);
            if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255) {
                return null;
            }
            return (a << 24) | (b << 16) | (c << 8) | d;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks if given host or IP address is whitelisted for mobile network bypass/priority.
     */
    public boolean isWhitelistedForMobile(String hostOrIp) {
        if (hostOrIp == null || hostOrIp.trim().isEmpty()) {
            return false;
        }
        String clean = hostOrIp.trim().toLowerCase(Locale.ROOT);

        // Strip port if present
        int colon = clean.indexOf(':');
        if (colon != -1 && !clean.contains("]")) {
            clean = clean.substring(0, colon);
        }

        // Check if IPv4
        Integer ip = parseIpv4(clean);
        if (ip != null) {
            int ipVal = ip.intValue();
            for (CidrRange range : cidrRanges) {
                if (range.matches(ipVal)) {
                    return true;
                }
            }
            return false;
        }

        // Domain matching
        if (domainSet.contains(clean)) {
            return true;
        }
        for (String d : domainSet) {
            if (clean.endsWith("." + d)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether server is tagged for mobile or matches whitelist pool.
     */
    public boolean isServerWhitelistedOrTagged(VpnServer server) {
        if (server == null) return false;
        String host = server.getHost();
        if (host == null || host.trim().isEmpty()) return false;
        host = host.trim();

        // 1. Dedicated VPS nodes (Sweden Stockholm, Yandex Reality, VK Reality, etc.)
        if ("193.233.199.233".equals(host) || "185.86.145.173".equals(host)
                || "193.233.198.117".equals(host) || "178.248.236.229".equals(host)) {
            return true;
        }

        // 2. Telecom CIDR / domain whitelist
        if (isWhitelistedForMobile(host)) {
            return true;
        }

        // 3. User's proven mobile history in local stash
        try {
            if (ru.llazyrin.goblinvpn.security.MobileProvenConfigStorage.getInstance(appContext).isProven(server.getServerKey())) {
                return true;
            }
        } catch (Throwable ignored) {}

        // 4. Tags in remark
        String remark = server.getRemark();
        if (remark != null) {
            String r = remark.toLowerCase(Locale.ROOT);
            if (r.contains("mobile") || r.contains("cellular") || r.contains("lte")
                    || r.contains("4g") || r.contains("bypass") || r.contains("whitelist")
                    || r.contains("швеция") || r.contains("обход") || r.contains("глушил")
                    || r.contains("ultra") || r.contains("yandex") || r.contains("shield")) {
                return true;
            }
        }

        // 5. Present in Mobile Bypass Pool
        try {
            List<VpnServer> pool = getMobileBypassServerPool(appContext);
            if (pool != null) {
                String targetKey = server.getServerKey().toLowerCase(Locale.ROOT);
                for (VpnServer s : pool) {
                    if (s != null && s.getServerKey().toLowerCase(Locale.ROOT).equals(targetKey)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    /**
     * Checks if device is currently running on cellular network.
     * Accurately inspects underlying physical network interfaces even when a VPN is connected.
     * Ignores the VPN overlay itself and requires Wi-Fi to have active internet and not be suspended.
     */
    public static boolean isRunningOnCellular(Context context) {
        if (context == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            // Direct check via NetworkInfo (immediately reflects Wi-Fi toggle off/on)
            NetworkInfo wifiInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            NetworkInfo ethernetInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_ETHERNET);
            boolean wifiConnected = (wifiInfo != null && wifiInfo.isConnected());
            boolean ethernetConnected = (ethernetInfo != null && ethernetInfo.isConnected());

            if (wifiConnected || ethernetConnected) {
                return false;
            }

            NetworkInfo mobileInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            boolean mobileConnected = (mobileInfo != null && mobileInfo.isConnected());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Network[] allNetworks = cm.getAllNetworks();
                boolean hasPhysicalCellular = false;
                boolean hasPhysicalWifi = false;

                if (allNetworks != null) {
                    for (Network net : allNetworks) {
                        if (net == null) continue;
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps == null) continue;

                        // 1. Игнорируем сам VPN-интерфейс, так как он наследует флаги предыдущей сети
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            continue;
                        }

                        // 2. Wi-Fi или Ethernet считается активным только при наличии NET_CAPABILITY_INTERNET и отсутствии приостановки
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
                                hasPhysicalWifi = true;
                            }
                        }

                        // 3. Сотовая сеть
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                    || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
                                hasPhysicalCellular = true;
                            }
                        }
                    }
                }

                if (hasPhysicalWifi) {
                    return false;
                }
                if (hasPhysicalCellular) {
                    return true;
                }
            }

            return mobileConnected && !wifiConnected;
        } catch (Exception e) {
            Log.w(TAG, "Error checking cellular network state: " + e.getMessage());
        }
        return false;
    }

    /**
     * When running on cellular network, gives priority to servers matching whitelisted ranges
     * or tagged for mobile bypass.
     */
    public List<VpnServer> prioritizeServersForNetwork(Context context, List<VpnServer> servers) {
        if (servers == null || servers.isEmpty()) {
            return servers;
        }
        if (!isRunningOnCellular(context)) {
            return servers;
        }

        List<VpnServer> mobilePriority = new ArrayList<>();
        List<VpnServer> standardList = new ArrayList<>();

        for (VpnServer server : servers) {
            if (server == null) continue;
            if (isServerWhitelistedOrTagged(server)) {
                mobilePriority.add(server);
            } else {
                standardList.add(server);
            }
        }

        if (mobilePriority.isEmpty()) {
            return servers;
        }

        List<VpnServer> result = new ArrayList<>(servers.size());
        result.addAll(mobilePriority);
        result.addAll(standardList);
        AppLogger.i(TAG, "Prioritized " + mobilePriority.size() + " mobile-whitelisted servers on Cellular network.");
        return result;
    }

    private final List<VpnServer> cachedBypassConfigs = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean isMobileRefreshing = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Экстренный принудительный запуск обновления резервного пула обхода глушилок.
     */
    public void triggerEmergencyRefresh() {
        refreshMobileBypassConfigsAsync(true);
    }

    /**
     * Фоновое обновление специализированного пула серверов для обхода глушилок сотовых сетей.
     * Расчетный интервал:
     * - 6 часов при нахождении на мобильной сети (защита от устаревания и разряда аккумулятора)
     * - 2 часа при подключении к Wi-Fi (оппортунистическая предзагрузка актуальных конфигов)
     * - 0 сек при экстренной отбраковке (force=true)
     */
    public void refreshMobileBypassConfigsAsync(final boolean force) {
        if (!isMobileRefreshing.compareAndSet(false, true)) {
            return;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    long lastSync = prefs.getLong(KEY_LAST_MOBILE_SYNC, 0L);
                    long now = System.currentTimeMillis();
                    boolean isWifi = !isRunningOnCellular(appContext);
                    long requiredInterval = isWifi ? OPPORTUNISTIC_WIFI_SYNC_MS : PERIODIC_MOBILE_SYNC_MS;

                    if (!force && (now - lastSync < requiredInterval)) {
                        return;
                    }

                    AppLogger.i(TAG, "Starting background refresh of mobile anti-jamming configs (force=" + force + ")...");
                    List<String> rawConfigs = new ArrayList<>();

                    // 1. Попытка загрузки через REST API backend
                    try {
                        URL url = new URL(MOBILE_CONFIGS_API_URL);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(7000);
                        conn.setReadTimeout(7000);
                        conn.setRequestProperty("User-Agent", "llazyrinVPN/2.8.0 (Android Anti-Jamming)");
                        conn.setRequestProperty("Accept", "application/json");

                        int code = conn.getResponseCode();
                        if (code == 200) {
                            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = r.readLine()) != null) {
                                    sb.append(line);
                                }
                                JSONObject obj = new JSONObject(sb.toString());
                                JSONArray arr = obj.optJSONArray("configs");
                                if (arr != null) {
                                    for (int i = 0; i < arr.length(); i++) {
                                        String c = arr.optString(i, "").trim();
                                        if (c.startsWith("vless://")) {
                                            rawConfigs.add(c);
                                        }
                                    }
                                }
                            }
                        }
                        conn.disconnect();
                    } catch (Throwable t) {
                        Log.w(TAG, "Mobile configs API fetch failed: " + t.getMessage());
                    }

                    // 2. Резервный источник: прямой текстовый файл на CDN
                    if (rawConfigs.isEmpty()) {
                        try {
                            URL url = new URL(MOBILE_CONFIGS_FALLBACK_URL);
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.setConnectTimeout(7000);
                            conn.setReadTimeout(7000);
                            conn.setRequestProperty("User-Agent", "llazyrinVPN/2.8.0 (Android Anti-Jamming)");

                            int code = conn.getResponseCode();
                            if (code == 200) {
                                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                    String line;
                                    while ((line = r.readLine()) != null) {
                                        line = line.trim();
                                        if (line.startsWith("vless://")) {
                                            rawConfigs.add(line);
                                        }
                                    }
                                }
                            }
                            conn.disconnect();
                        } catch (Throwable t) {
                            Log.w(TAG, "Mobile configs fallback fetch failed: " + t.getMessage());
                        }
                    }

                    // Сохранение во внутренний кэш
                    if (!rawConfigs.isEmpty()) {
                        File cacheFile = new File(appContext.getFilesDir(), MOBILE_CONFIGS_CACHE_FILE);
                        File tempFile = new File(appContext.getFilesDir(), MOBILE_CONFIGS_CACHE_FILE + ".tmp");
                        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                            for (String c : rawConfigs) {
                                fos.write((c + "\n").getBytes(StandardCharsets.UTF_8));
                            }
                            fos.flush();
                        }
                        if (cacheFile.exists()) {
                            cacheFile.delete();
                        }
                        tempFile.renameTo(cacheFile);

                        prefs.edit().putLong(KEY_LAST_MOBILE_SYNC, now).apply();
                        synchronized (cachedBypassConfigs) {
                            cachedBypassConfigs.clear();
                        }
                        AppLogger.i(TAG, "✅ Mobile anti-jamming bypass configs successfully refreshed ("
                                + rawConfigs.size() + " nodes). Cache timestamp updated.");
                    }
                } catch (Throwable t) {
                    AppLogger.w(TAG, "Failed to refresh mobile bypass configs: " + t.getMessage());
                } finally {
                    isMobileRefreshing.set(false);
                }
            }
        });
    }

    /**
     * Checks if a given server belongs to the mobile bypass pool.
     */
    public static boolean isMobileBypassServer(Context context, VpnServer server) {
        if (server == null || server.getHost() == null) return false;
        try {
            List<VpnServer> pool = getInstance(context).getMobileBypassServerPool(context);
            if (pool != null) {
                String targetKey = server.getServerKey().toLowerCase(Locale.ROOT);
                for (VpnServer s : pool) {
                    if (s != null && s.getServerKey().toLowerCase(Locale.ROOT).equals(targetKey)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Checks if the given SNI is permitted/unthrottled on Russian mobile networks.
     * Prevents routing cellular traffic through heavily throttled/blocked domains.
     */
    public static boolean isSniPermittedOnCellular(String sni) {
        if (sni == null || sni.trim().isEmpty()) return true;
        String s = sni.trim().toLowerCase(Locale.ROOT);
        if (s.contains("instagram") || s.contains("facebook") || s.contains("twitter") || s.contains("x.com")) {
            return false;
        }
        return true;
    }

    /**
     * Returns Tier 1 mobile pool: proven working nodes from device storage + verified Telegram nodes.
     */
    public List<VpnServer> getTier1MobilePool(Context context) {
        Context ctx = (context != null) ? context.getApplicationContext() : appContext;
        List<VpnServer> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Proven mobile configs from storage
        try {
            List<VpnServer> proven = MobileProvenConfigStorage.getInstance(ctx).getProvenMobileServers();
            if (proven != null && !proven.isEmpty()) {
                for (VpnServer s : proven) {
                    if (s != null && s.getHost() != null && !MainActivity.isKnownDeadHost(s.getHost())) {
                        String k = s.getServerKey().toLowerCase(Locale.ROOT);
                        if (!seen.contains(k)) {
                            seen.add(k);
                            list.add(s);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2. Verified Telegram configs (Hexdey, Neverspy, Poland, Russia Yandex)
        try {
            loadVerifiedTelegramConfigs(ctx, list, seen);
        } catch (Throwable ignored) {}

        return list;
    }

    /**
     * Возвращает многоуровневый резервный пул специализированных узлов (VLESS Reality)
     * для обхода глушилок и фильтрации мобильных операторов:
     *
     * Tier 1 (Высший приоритет): Доказанно работавшие на сотовой сети узлы этого пользователя
     * Tier 2: Проверенные высокоустойчивые ноды сообщества и VPS (ya.ru:8445, icloud:8443, vk:443, cdn:2053)
     * Tier 3: Динамически обновленные серверы из кэша / API
     * Tier 4: Встроенные эталонные активы и семена безопасности
     */
    public List<VpnServer> getMobileBypassServerPool(Context context) {
        Context ctx = (context != null) ? context.getApplicationContext() : appContext;
        List<VpnServer> combined = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        // Tier 0: Проверенные Telegram конфиги обхода блокировок (Германия Neverspy, Германия Hexdey, Польша, Швейцария)
        try {
            loadVerifiedTelegramConfigs(ctx, combined, seenKeys);
        } catch (Throwable t) {
            Log.w(TAG, "Error loading verified telegram configs: " + t.getMessage());
        }

        // Tier 1: Доказанно работающие на сотовой связи узлы данного устройства
        try {
            List<VpnServer> proven = MobileProvenConfigStorage.getInstance(ctx).getProvenMobileServers();
            if (proven != null && !proven.isEmpty()) {
                for (VpnServer s : proven) {
                    if (s != null && s.getHost() != null && !MainActivity.isKnownDeadHost(s.getHost())) {
                        String key = s.getServerKey().toLowerCase(Locale.ROOT);
                        if (!seenKeys.contains(key)) {
                            seenKeys.add(key);
                            combined.add(s);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error querying MobileProvenConfigStorage: " + t.getMessage());
        }

        // Tier 2: Высокоустойчивые серверы сообщества и нашего VPS (с незаблокированными SNI и портами 8443, 8445, 2053)
        try {
            List<VpnServer> comm = CommunityPoolManager.getInstance(ctx).getTopCommunityServers();
            if (comm != null && !comm.isEmpty()) {
                for (VpnServer s : comm) {
                    if (s != null && s.getHost() != null && !MainActivity.isKnownDeadHost(s.getHost())) {
                        String key = s.getServerKey().toLowerCase(Locale.ROOT);
                        if (!seenKeys.contains(key)) {
                            seenKeys.add(key);
                            combined.add(s);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error querying CommunityPoolManager: " + t.getMessage());
        }

        // Tier 3: Динамически обновленные ноды из локального кэша
        File cacheFile = new File(ctx.getFilesDir(), MOBILE_CONFIGS_CACHE_FILE);
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try (FileInputStream fis = new FileInputStream(cacheFile);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                List<VpnServer> cachedNodes = new ArrayList<>();
                parseBypassConfigLines(reader, cachedNodes);
                for (VpnServer cn : cachedNodes) {
                    if (cn != null && cn.getHost() != null && !MainActivity.isKnownDeadHost(cn.getHost())) {
                        String key = cn.getServerKey().toLowerCase(Locale.ROOT);
                        if (!seenKeys.contains(key)) {
                            seenKeys.add(key);
                            combined.add(cn);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Tier 4: Эталонные встроенные ноды из assets
        try (InputStream is = ctx.getAssets().open("mobile_whitelist_configs.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            List<VpnServer> assetNodes = new ArrayList<>();
            parseBypassConfigLines(reader, assetNodes);
            for (VpnServer an : assetNodes) {
                if (an != null && an.getHost() != null && !MainActivity.isKnownDeadHost(an.getHost())) {
                    String key = an.getServerKey().toLowerCase(Locale.ROOT);
                    if (!seenKeys.contains(key)) {
                        seenKeys.add(key);
                        combined.add(an);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Tier 4 (Fallback): Резервные семена безопасности
        if (combined.isEmpty()) {
            List<VpnServer> reserve = ru.llazyrin.goblinvpn.security.EmergencyConfigStorage.loadEmergencyReserve(ctx);
            if (reserve != null) {
                for (VpnServer rn : reserve) {
                    if (rn != null && rn.getHost() != null && !MainActivity.isKnownDeadHost(rn.getHost())) {
                        String key = rn.getServerKey().toLowerCase(Locale.ROOT);
                        if (!seenKeys.contains(key)) {
                            seenKeys.add(key);
                            combined.add(rn);
                        }
                    }
                }
            }
        }

        AppLogger.i(TAG, "Assembled Mobile Anti-Jamming Bypass Pool: " + combined.size()
                + " nodes (Tier 1 proven count=" + MobileProvenConfigStorage.getInstance(ctx).getCount() + ")");
        return combined;
    }

    private static void loadVerifiedTelegramConfigs(Context ctx, List<VpnServer> combined, Set<String> seenKeys) {
        try (InputStream is = ctx.getAssets().open("verified_telegram_configs.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String uri = obj.optString("uri");
                if (uri == null || uri.isEmpty()) continue;
                VpnServer s = VpnSubscriptionClient.parseVlessLine(uri);
                if (s != null && s.getHost() != null && !MainActivity.isKnownDeadHost(s.getHost())) {
                    String tag = obj.optString("tag");
                    if (tag != null && !tag.isEmpty()) {
                        s.setRemark(tag);
                    }
                    int delay = obj.optInt("delay", 80);
                    s.setPingMs(delay);
                    String key = s.getServerKey().toLowerCase(Locale.ROOT);
                    if (!seenKeys.contains(key)) {
                        seenKeys.add(key);
                        combined.add(s);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void parseBypassConfigLines(BufferedReader reader, List<VpnServer> out) throws Exception {
        String line;
        int idx = 1;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("vless://")) continue;
            VpnServer s = VpnSubscriptionClient.parseVlessLine(line);
            if (s != null && s.getHost() != null && !s.getHost().equals("0.0.0.0") && !s.getHost().equals("127.0.0.1") && !ru.llazyrin.goblinvpn.MainActivity.isKnownDeadHost(s.getHost())) {
                if (s.getRemark() == null || s.getRemark().isEmpty() || s.getRemark().equalsIgnoreCase(s.getHost())) {
                    s.setRemark("🇷🇺 Обход глушилок #" + idx);
                }
                out.add(s);
                idx++;
            }
        }
    }

    public int getCachedCidrCount() {
        return cidrRanges.size();
    }

    public int getCachedDomainCount() {
        return domainSet.size();
    }
}
