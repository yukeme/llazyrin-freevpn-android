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
import android.os.SystemClock;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    public static final String WHITELIST_MIRROR_URL = "https://llazyrin.online/cellular_whitelist.txt";
    public static final String ZIENG2_VLESS_URL = "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt";
    public static final String CACHE_FILE_NAME = "cellular_whitelist.txt";
    public static final String ASSET_WHITELIST_FILE = "cellular_whitelist.txt";

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

    // In-memory latency cache for mobile bypass candidate nodes
    private static final Map<String, Integer> serverPingCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> serverPingTimestampCache = new ConcurrentHashMap<>();
    public static final long PING_CACHE_FRESHNESS_MS = 10L * 60L * 1000L; // 10 minutes
    private static volatile boolean lastKnownCellular = false;

    public static void setLastKnownCellular(boolean cellular) {
        lastKnownCellular = cellular;
    }

    public static boolean isLastKnownCellular() {
        return lastKnownCellular;
    }

    public static void recordServerPing(String serverKey, int pingMs) {
        if (serverKey != null && pingMs > 0) {
            String k = serverKey.toLowerCase(Locale.ROOT);
            serverPingCache.put(k, pingMs);
            serverPingTimestampCache.put(k, SystemClock.elapsedRealtime());
        }
    }

    public static int getCachedPing(String serverKey) {
        if (serverKey == null) return -1;
        Integer p = serverPingCache.get(serverKey.toLowerCase(Locale.ROOT));
        return p != null ? p : -1;
    }

    public static int getFreshCachedPing(String serverKey, long maxAgeMs) {
        if (serverKey == null) return -1;
        String k = serverKey.toLowerCase(Locale.ROOT);
        Integer p = serverPingCache.get(k);
        Long ts = serverPingTimestampCache.get(k);
        if (p == null || p <= 0 || ts == null) return -1;
        if ((SystemClock.elapsedRealtime() - ts) > maxAgeMs) return -1;
        return p;
    }

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

        // Load bundled full cellular whitelist from assets (869 CIDR subnets from d010b)
        try (InputStream is = appContext.getAssets().open(ASSET_WHITELIST_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            parseLines(reader);
            isLoaded = true;
            AppLogger.i(TAG, "Loaded " + cidrRanges.size() + " CIDR ranges and " + domainSet.size() + " domains from bundled assets.");
            return;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load assets cellular_whitelist.txt: " + e.getMessage());
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
                    URL url;
                    try {
                        url = new URL(WHITELIST_URL);
                    } catch (Exception e) {
                        url = new URL(WHITELIST_MIRROR_URL);
                    }
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
     * Checks if a given SNI is known to be blocked by Russian mobile operators / TSPU.
     */
    public static boolean isBlockedMobileSni(String sni) {
        if (sni == null) return false;
        String s = sni.trim().toLowerCase(Locale.ROOT);
        return s.contains("cloudflare.com") || s.contains("yt3.ggpht.com")
                || s.contains("discord.com") || s.contains("instagram.com")
                || s.contains("icloud.com") || s.contains("apple.com");
    }

    public static boolean isBlockedMobileHost(String host) {
        if (host == null) return false;
        String h = host.trim();
        return "193.233.199.233".equals(h);
    }

    /**
     * Evaluates SNI resilience and compatibility with Russian mobile TSPU whitelists.
     * Russian telecom operators prioritize ya.ru, vk.com, ok.ru, gosuslugi.ru, sberbank.ru.
     */
    public static int getSniMobileScore(String sni, int port) {
        if (sni == null || sni.trim().isEmpty()) {
            return 10;
        }
        String s = sni.trim().toLowerCase(Locale.ROOT);
        if (isBlockedMobileSni(s)) {
            return -100;
        }

        // =========================================================================
        // TOP PRIORITY: Verified fast cellular egress nodes from device telemetry
        // =========================================================================
        if (s.contains("smartcdn.top") || s.contains("polarladder.com")) {
            return 100; // Top verified 67-78ms cellular TLS+HTTP nodes
        }
        if (s.contains("max.ru") || s.contains("api.vk.me") || s.contains("hexdey.com") || s.contains("144.hexdey")) {
            return 99; // Verified 80-97ms cellular egress nodes
        }
        if (s.contains("cdnwh.icu") || s.contains("starsov.pro")
                || s.contains("telemetryapi.org") || s.contains("opendns.stream")) {
            return 98; // Verified 88-143ms cellular egress nodes
        }
        if (s.contains("1.4.8.8")) {
            return 96; // Verified 113ms cellular egress node
        }
        if (s.contains("maxleshax") || s.contains("fosonmine")) {
            return 93; // Secondary European egress
        }

        // Russian domestic services: excellent handshake on mobile towers, but domestic-only egress / captcha
        if (s.contains("ya.ru") || s.contains("yandex") || s.contains("yastatic.net")) {
            return 90;
        }
        // Demoted SNIs: repeated handshake failures or 0 hits in cellular telemetry
        if (s.contains("plaindune.com")) {
            return 85;
        }
        if (s.contains("olvento.space") || s.contains("cadrin.wtf") || s.contains("flintstack.dev") || s.contains("emberworks.cloud")) {
            return 84;
        }
        if (s.contains("paruscamp") || s.contains("quick-frost") || s.contains("gearmonster")) {
            return 82;
        }
        if (s.contains("vk.com") || s.contains("vk.me") || s.contains("userapi.com")) {
            return 80;
        }
        if (s.contains("wholebeam.com")) {
            return 75; // VK cluster in 185.86.144.0/22 (domestic intranet, captcha on foreign web)
        }
        if (s.contains("ok.ru") || s.contains("okcdn.ru")) {
            return 75;
        }
        if (s.contains("gosuslugi") || s.contains("sberbank") || s.contains("tinkoff")) {
            return 70;
        }
        if (s.contains("gateway.icloud.com") || s.contains("icloud.com")
                || s.contains("neverspy") || s.contains("bachidze")
                || s.contains("tunnely") || s.contains("sslip.io")) {
            return 60;
        }
        return 50;
    }

    /**
     * Checks whether server is tagged for mobile or matches whitelist pool.
     */
    public boolean isServerWhitelistedOrTagged(VpnServer server) {
        if (server == null) return false;
        if (isBlockedMobileSni(server.getSni())) {
            return false;
        }
        if (server.getPort() == 8445 || server.getPort() == 8446) {
            return true; // Dedicated Yandex & VK Reality bypass ports
        }
        if (isWhitelistedForMobile(server.getHost())) {
            return true;
        }
        String sni = server.getSni();
        if (sni != null) {
            if (getSniMobileScore(sni, server.getPort()) >= 90) {
                return true;
            }
        }
        String remark = server.getRemark();
        if (remark != null) {
            String r = remark.toLowerCase(Locale.ROOT);
            if (r.contains("mobile") || r.contains("cellular") || r.contains("lte")
                    || r.contains("4g") || r.contains("bypass") || r.contains("whitelist")
                    || r.contains("глушилок") || r.contains("яндекс") || r.contains("yandex") || r.contains("vk")) {
                return true;
            }
        }
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

            boolean wifiValidated = false;
            boolean wifiUnvalidated = false;
            boolean cellularConnected = false;

            // 1. Direct check of active default physical network (Android 6.0+) when not masked by VPN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNet = cm.getActiveNetwork();
                if (activeNet != null) {
                    NetworkCapabilities activeCaps = cm.getNetworkCapabilities(activeNet);
                    if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        boolean hasNet = activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        boolean notSusp = activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
                        boolean validated = activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                        if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                                && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                                && (hasNet || notSusp)) {
                            lastKnownCellular = true;
                            return true;
                        }
                        if ((activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                                && hasNet && notSusp && validated) {
                            lastKnownCellular = false;
                            return false;
                        }
                    }
                }
            }

            // 2. Inspect underlying physical network interfaces (skipping VPN overlay)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Network[] allNetworks = cm.getAllNetworks();
                if (allNetworks != null) {
                    for (Network net : allNetworks) {
                        if (net == null) continue;
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps == null) continue;
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            continue;
                        }

                        boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        boolean notSuspended = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
                        boolean isValidated = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                                ? caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                                : true;

                        NetworkInfo info = cm.getNetworkInfo(net);
                        boolean isConn = (info != null && info.isConnected());

                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            if (isConn && hasInternet && notSuspended) {
                                if (isValidated) {
                                    wifiValidated = true;
                                } else {
                                    wifiUnvalidated = true;
                                }
                            }
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            if (isConn || (hasInternet && notSuspended)) {
                                cellularConnected = true;
                            }
                        }
                    }
                }
            }

            // Genuinely validated Wi-Fi takes priority; unvalidated Wi-Fi yields to active cellular
            if (wifiValidated) {
                lastKnownCellular = false;
                return false;
            }
            if (cellularConnected) {
                lastKnownCellular = true;
                return true;
            }
            if (wifiUnvalidated) {
                lastKnownCellular = false;
                return false;
            }

            // 3. Fallback for active default network (Android 6.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNet = cm.getActiveNetwork();
                if (activeNet != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            lastKnownCellular = true;
                            return true;
                        }
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            lastKnownCellular = false;
                            return false;
                        }
                    }
                }
            }

            // 3. TelephonyManager check: DATA_CONNECTED indicates active mobile data subscription
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null && tm.getDataState() == TelephonyManager.DATA_CONNECTED) {
                    lastKnownCellular = true;
                    return true;
                }
            } catch (Throwable ignored) {}

            // 4. Fallback for legacy Android versions
            NetworkInfo activeInfo = cm.getActiveNetworkInfo();
            if (activeInfo != null && activeInfo.isConnected()) {
                boolean res = (activeInfo.getType() == ConnectivityManager.TYPE_MOBILE);
                lastKnownCellular = res;
                return res;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking cellular network state: " + e.getMessage());
        }
        lastKnownCellular = false;
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
    private volatile long cachedBypassFileLength = -1L;
    private volatile long cachedBypassFileMtime = -1L;
    private final List<VpnServer> cachedAssetBypassConfigs = new CopyOnWriteArrayList<>();
    private volatile boolean assetBypassLoaded = false;
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
                            conn.setRequestProperty("User-Agent", "llazyrinVPN/2.8.2 (Android Anti-Jamming)");

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

                    // 3. Прямой источник с GitHub (zieng2 vless_universal.txt) с фильтрацией по whitelist CIDR
                    if (rawConfigs.size() < 30) {
                        try {
                            URL url = new URL(ZIENG2_VLESS_URL);
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.setConnectTimeout(8000);
                            conn.setReadTimeout(8000);
                            conn.setRequestProperty("User-Agent", "llazyrinVPN/2.8.2 (Android Anti-Jamming)");
                            int code = conn.getResponseCode();
                            if (code == 200) {
                                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                    String line;
                                    while ((line = r.readLine()) != null) {
                                        line = line.trim();
                                        if (!line.startsWith("vless://")) continue;
                                        VpnServer cand = VpnSubscriptionClient.parseVlessLine(line);
                                        if (cand != null && cand.getHost() != null) {
                                            if (isWhitelistedForMobile(cand.getHost()) && !isBlockedMobileSni(cand.getSni())) {
                                                if (!rawConfigs.contains(line)) {
                                                    rawConfigs.add(line);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            conn.disconnect();
                        } catch (Throwable t) {
                            Log.w(TAG, "Zieng2 direct vless fetch failed: " + t.getMessage());
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
                            cachedBypassFileLength = -1L;
                            cachedBypassFileMtime = -1L;
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
     * Возвращает 3-уровневый интеллектуальный резервный пул серверов (VLESS Reality)
     * для сотовых сетей РФ (обход ТСПУ и белые списки базовых станций):
     *
     * Пул 1 (Высокая вероятность, ~15 конфигов):
     *   - Доказанно работавшие на этом телефоне (MobileProvenConfigStorage)
     *   - Выделенные порты VPS 8445 (Yandex Reality) и 8446 (VK Reality)
     *   - Проверенные российские белые SNI (ya.ru, vk.com, 144.hexdey.com, okcdn.ru, ok.ru)
     *   - Белые IP/CIDR из базы Роскомнадзора / операторов РФ
     *
     * Пул 2 (Средняя вероятность, ~10 конфигов):
     *   - Чистые Reality-ноды зарубежных партнеров (gateway.icloud.com, neverspy, bachidze, tunnely, sslip.io, cdn)
     *   - Топ сообщества с положительным рейтингом
     *
     * Пул 3 (Резервная вероятность, ~5-8 конфигов):
     *   - Аварийные семена безопасности (GoblinSecurityVault)
     *   - Локальный зашифрованный тайник (EmergencyConfigStorage)
     */
    public List<VpnServer> getMobileBypassServerPool(Context context) {
        Context ctx = (context != null) ? context.getApplicationContext() : appContext;
        List<VpnServer> combined = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        // 1. Доказанно работающие на сотовой связи узлы данного устройства (строго в пределах белых списков РФ)
        try {
            List<VpnServer> proven = MobileProvenConfigStorage.getInstance(ctx).getProvenMobileServers();
            if (proven != null && !proven.isEmpty()) {
                for (VpnServer s : proven) {
                    if (s != null && s.getHost() != null && !s.getHost().equals("193.233.199.233")
                            && !MainActivity.isKnownDeadHost(s.getHost()) && !isBlockedMobileSni(s.getSni())) {
                        if (isWhitelistedForMobile(s.getHost())) {
                            String key = s.getServerKey().toLowerCase(Locale.ROOT);
                            if (!seenKeys.contains(key)) {
                                seenKeys.add(key);
                                combined.add(s);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error querying MobileProvenConfigStorage: " + t.getMessage());
        }

        // 2. Высокоустойчивые серверы сообщества (строго в пределах белых CIDR подсетей, зарубежные серверы исключены)
        try {
            List<VpnServer> comm = CommunityPoolManager.getInstance(ctx).getTopCommunityServers();
            if (comm != null && !comm.isEmpty()) {
                for (VpnServer s : comm) {
                    if (s != null && s.getHost() != null && !s.getHost().equals("193.233.199.233")
                            && !MainActivity.isKnownDeadHost(s.getHost()) && !isBlockedMobileSni(s.getSni())) {
                        if (isWhitelistedForMobile(s.getHost())) {
                            String key = s.getServerKey().toLowerCase(Locale.ROOT);
                            if (!seenKeys.contains(key)) {
                                seenKeys.add(key);
                                combined.add(s);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error querying CommunityPoolManager: " + t.getMessage());
        }

        // 3. Динамически обновленные ноды из локального кэша (zieng2) — с кэшированием в памяти
        File cacheFile = new File(ctx.getFilesDir(), MOBILE_CONFIGS_CACHE_FILE);
        if (cacheFile.exists() && cacheFile.length() > 0) {
            long fileLen = cacheFile.length();
            long fileMtime = cacheFile.lastModified();
            List<VpnServer> cachedNodes;
            synchronized (cachedBypassConfigs) {
                if (cachedBypassConfigs.isEmpty() || cachedBypassFileLength != fileLen || cachedBypassFileMtime != fileMtime) {
                    cachedBypassConfigs.clear();
                    try (FileInputStream fis = new FileInputStream(cacheFile);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                        List<VpnServer> parsed = new ArrayList<>();
                        parseBypassConfigLines(reader, parsed);
                        cachedBypassConfigs.addAll(parsed);
                        cachedBypassFileLength = fileLen;
                        cachedBypassFileMtime = fileMtime;
                    } catch (Throwable ignored) {}
                }
                cachedNodes = new ArrayList<>(cachedBypassConfigs);
            }
            for (VpnServer cn : cachedNodes) {
                if (cn != null && cn.getHost() != null && !cn.getHost().equals("193.233.199.233")
                        && !MainActivity.isKnownDeadHost(cn.getHost()) && !isBlockedMobileSni(cn.getSni())) {
                    if (isWhitelistedForMobile(cn.getHost())) {
                        int cachedPing = getCachedPing(cn.getServerKey());
                        if (cachedPing > 0) {
                            cn.setPingMs(cachedPing);
                            cn.setAlive(true);
                        }
                        String key = cn.getServerKey().toLowerCase(Locale.ROOT);
                        if (!seenKeys.contains(key)) {
                            seenKeys.add(key);
                            combined.add(cn);
                        }
                    }
                }
            }
        }

        // 4. Эталонные встроенные ноды из assets (все 65 проверенных конфигов zieng2) — парсится один раз
        List<VpnServer> assetNodes;
        synchronized (cachedAssetBypassConfigs) {
            if (!assetBypassLoaded) {
                try (InputStream is = ctx.getAssets().open("mobile_whitelist_configs.txt");
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    List<VpnServer> parsedAssets = new ArrayList<>();
                    parseBypassConfigLines(reader, parsedAssets);
                    cachedAssetBypassConfigs.clear();
                    cachedAssetBypassConfigs.addAll(parsedAssets);
                    assetBypassLoaded = true;
                } catch (Throwable ignored) {}
            }
            assetNodes = new ArrayList<>(cachedAssetBypassConfigs);
        }
        for (VpnServer an : assetNodes) {
            if (an != null && an.getHost() != null && !an.getHost().equals("193.233.199.233")
                    && !MainActivity.isKnownDeadHost(an.getHost()) && !isBlockedMobileSni(an.getSni())) {
                if (isWhitelistedForMobile(an.getHost())) {
                    int cachedPing = getCachedPing(an.getServerKey());
                    if (cachedPing > 0) {
                        an.setPingMs(cachedPing);
                        an.setAlive(true);
                    }
                    String key = an.getServerKey().toLowerCase(Locale.ROOT);
                    if (!seenKeys.contains(key)) {
                        seenKeys.add(key);
                        combined.add(an);
                    }
                }
            }
        }

        // 5. Резервные семена безопасности (строго с проверкой белого списка, без нашего зарубежного VPS)
        List<VpnServer> emergencyReserve = ru.llazyrin.goblinvpn.security.EmergencyConfigStorage.loadEmergencyReserve(ctx);
        if (emergencyReserve != null) {
            for (VpnServer rn : emergencyReserve) {
                if (rn != null && rn.getHost() != null && !rn.getHost().equals("193.233.199.233")
                        && !MainActivity.isKnownDeadHost(rn.getHost()) && !isBlockedMobileSni(rn.getSni())) {
                    if (isWhitelistedForMobile(rn.getHost())) {
                        String key = rn.getServerKey().toLowerCase(Locale.ROOT);
                        if (!seenKeys.contains(key)) {
                            seenKeys.add(key);
                            combined.add(rn);
                        }
                    }
                }
            }
        }

        // =========================================================================
        // РАСПРЕДЕЛЕНИЕ ПО 3 ПУЛАМ ВЕРОЯТНОСТИ ПОДКЛЮЧЕНИЯ
        // =========================================================================
        List<VpnServer> tier1 = new ArrayList<>(); // До 15 серверов (Высшая вероятность: белый SNI РФ, wholebeam, proven)
        List<VpnServer> tier2 = new ArrayList<>(); // До 10 серверов (Средняя вероятность: белые CIDR ТСПУ, score >= 70)
        List<VpnServer> tier3 = new ArrayList<>(); // До 5-8 серверов (Резерв)

        for (VpnServer s : combined) {
            if (s == null) continue;
            int score = getSniMobileScore(s.getSni(), s.getPort());
            boolean isProven = MobileProvenConfigStorage.getInstance(ctx).isProven(s.getServerKey());
            boolean isInWhitelist = isWhitelistedForMobile(s.getHost());

            // Collect ALL Tier 1 candidates first before sorting (do NOT truncate before sort!)
            if (isProven || score >= 95) {
                s.setMobileTier(1);
                tier1.add(s);
            } else if (score >= 70 || isInWhitelist) {
                s.setMobileTier(2);
                tier2.add(s);
            } else {
                s.setMobileTier(3);
                tier3.add(s);
            }
        }

        final MobileProvenConfigStorage provenStorage = MobileProvenConfigStorage.getInstance(ctx);
        final ServerPenaltyManager penaltyMgr = ServerPenaltyManager.getInstance(ctx);

        // Сортировка внутри пулов: не-штрафные -> свежий быстрый пинг (<400мс) -> проверенные на устройстве -> SNI-рейтинг
        Collections.sort(tier1, new Comparator<VpnServer>() {
            @Override
            public int compare(VpnServer a, VpnServer b) {
                boolean penA = penaltyMgr.isPenalized(a.getServerKey());
                boolean penB = penaltyMgr.isPenalized(b.getServerKey());
                if (penA != penB) return penA ? 1 : -1;

                int freshA = getFreshCachedPing(a.getServerKey(), PING_CACHE_FRESHNESS_MS);
                int freshB = getFreshCachedPing(b.getServerKey(), PING_CACHE_FRESHNESS_MS);
                boolean fastA = (freshA > 0 && freshA < 400);
                boolean fastB = (freshB > 0 && freshB < 400);
                if (fastA != fastB) return fastA ? -1 : 1;
                if (fastA && fastB && freshA != freshB) {
                    return Integer.compare(freshA, freshB);
                }

                boolean provA = provenStorage.isProven(a.getServerKey());
                boolean provB = provenStorage.isProven(b.getServerKey());
                if (provA != provB) return provB ? 1 : -1;

                int sA = getSniMobileScore(a.getSni(), a.getPort());
                int sB = getSniMobileScore(b.getSni(), b.getPort());
                if (sA != sB) return Integer.compare(sB, sA);

                int pA = (freshA > 0) ? freshA : ((a.getPingMs() > 0 && a.getPingMs() != 45) ? a.getPingMs() : 9999);
                int pB = (freshB > 0) ? freshB : ((b.getPingMs() > 0 && b.getPingMs() != 45) ? b.getPingMs() : 9999);
                return Integer.compare(pA, pB);
            }
        });

        Collections.sort(tier2, new Comparator<VpnServer>() {
            @Override
            public int compare(VpnServer a, VpnServer b) {
                boolean penA = penaltyMgr.isPenalized(a.getServerKey());
                boolean penB = penaltyMgr.isPenalized(b.getServerKey());
                if (penA != penB) return penA ? 1 : -1;

                int sA = getSniMobileScore(a.getSni(), a.getPort());
                int sB = getSniMobileScore(b.getSni(), b.getPort());
                if (sA != sB) return Integer.compare(sB, sA);

                int pA = (a.getPingMs() > 0 && a.getPingMs() != 45) ? a.getPingMs() : 9999;
                int pB = (b.getPingMs() > 0 && b.getPingMs() != 45) ? b.getPingMs() : 9999;
                return Integer.compare(pA, pB);
            }
        });

        // Keep top 20 sorted nodes in Tier 1 (4 waves of 5), spill excess into Tier 2
        while (tier1.size() > 20) {
            VpnServer extra = tier1.remove(tier1.size() - 1);
            extra.setMobileTier(2);
            tier2.add(extra);
        }

        List<VpnServer> sortedPool = new ArrayList<>(tier1.size() + tier2.size() + tier3.size());
        sortedPool.addAll(tier1);
        sortedPool.addAll(tier2);
        sortedPool.addAll(tier3);

        StringBuilder roster = new StringBuilder();
        for (int i = 0; i < Math.min(tier1.size(), 6); i++) {
            VpnServer t = tier1.get(i);
            roster.append(t.getHost()).append(":").append(t.getPort()).append("(").append(t.getSni()).append(") ");
        }

        AppLogger.i(TAG, "📊 Assembled 3-Tier Mobile Bypass Pools: Tier 1 (High)=" + tier1.size()
                + ", Tier 2 (Medium)=" + tier2.size()
                + ", Tier 3 (Reserve)=" + tier3.size()
                + " [Total=" + sortedPool.size() + " nodes]. Top Tier 1: " + roster.toString());

        return sortedPool;
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
                int cachedPing = getCachedPing(s.getServerKey());
                if (cachedPing > 0) {
                    s.setPingMs(cachedPing);
                    s.setAlive(true);
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
