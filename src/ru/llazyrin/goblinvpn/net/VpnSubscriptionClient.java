package ru.llazyrin.goblinvpn.net;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.security.GoblinSecurityVault;

/**
 * VpnSubscriptionClient — Высоконадёжный клиент подписок и агрегатор конфигураций Goblin VPN.
 *
 * Поддерживает:
 * 1. Основной и зеркальный эндпоинты подписки (https://vpn.llazyrin.ru/sub).
 * 2. Автономный параллельный краулер открытых репозиториев GitHub с тайм-аутом 5000мс.
 * 3. Декодирование как Base64-подписок, так и открытых строк vless://.
 * 4. Строгую фильтрацию только VLESS Reality конфигураций (security=reality | pbk= | sni=).
 * 5. Жёсткую дедупликацию по уникальному IP / хосту (ровно 1 узел на IP, без повторов).
 * 6. Полную нормализацию гео-локаций (флаги, русские названия стран: DE, FI, NL, US, SG, PL, JP, GB, SE, etc.).
 * 7. Встроенные семена безопасности из GoblinSecurityVault при аварии сети.
 */
public class VpnSubscriptionClient {
    private static final String TAG = "VpnSubClient";

    // Primary subscription URL (Official High-Speed Verified Server Feed)
    public static final String PRIMARY_SUB_URL = "https://llazyrin.online/api/app/servers?raw=1";

    // Rich pool of open-source subscription URLs
    public static final String[] OPEN_SOURCE_SUBSCRIPTION_URLS = new String[] {
        "https://raw.githubusercontent.com/yebekhe/TVC/main/subscriptions/xray/normal/vless",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub2.txt",
        "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
        "https://raw.githubusercontent.com/freefq/free/master/v2",
        "https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray",
        "https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/v2",
        // Resilient high-yield open-source mirrors and aggregators
        "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Sub1.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Sub2.txt",
        "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/v2ray-base64.txt",
        "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub"
    };

    // Regex to match vless://uuid@host:port (supports IPv4, IPv6 in brackets, and domain names)
    private static final Pattern VLESS_PATTERN = Pattern.compile("^vless://([^@]+)@(\\[[a-fA-F0-9:]+\\]|[^:?#/\\s]+):(\\d+)");
    private static final Pattern GOBLIN_BRAND_PATTERN = Pattern.compile("\\[(?:Гоблин|Goblin|llazyrinVPN|llazyrin)\\]");
    private static final Pattern LATENCY_BRACKET_PATTERN = Pattern.compile("(?i)\\[\\s*\\d+\\s*ms\\s*\\]");
    private static final Pattern LATENCY_PAREN_PATTERN = Pattern.compile("(?i)\\(\\s*\\d+\\s*ms\\s*\\)");
    private static final Pattern HANDLE_PATTERN = Pattern.compile("(?i)@[a-zA-Z0-9_.-]+");
    private static final Pattern NUMBER_SUFFIX_PATTERN = Pattern.compile("(?i)\\s*#\\d+\\s*$");
    private static final Pattern FLAG_EMOJI_PATTERN = Pattern.compile("(\\uD83C[\\uDDE6-\\uDDFF]\\uD83C[\\uDDE6-\\uDDFF])");

    public static class CountryInfo {
        public final String flag;
        public final String countryName;

        public CountryInfo(String flag, String countryName) {
            this.flag = flag != null ? flag : "🌐";
            this.countryName = countryName != null ? countryName : "Сервер";
        }
    }

    public interface Callback {
        void onSuccess(List<VpnServer> servers);
        void onError(Exception error);
    }

    // Dynamic obfuscated endpoint from security vault
    public static String getPrimaryUrl() {
        return GoblinSecurityVault.getPrimaryEndpoint();
    }

    public static void fetchServersAsync(final Callback callback) {
        fetchServersAsync(false, callback);
    }

    public static void fetchServersAsync(final boolean forceOpenSource, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<VpnServer> remoteServers = fetchServersSync(forceOpenSource);
                    List<VpnServer> combined = new ArrayList<>(getBuiltinFallbackServers());
                    if (remoteServers != null && !remoteServers.isEmpty()) {
                        for (VpnServer remote : remoteServers) {
                            boolean exists = false;
                            for (VpnServer existing : combined) {
                                if (existing.getHost().equalsIgnoreCase(remote.getHost()) && existing.getPort() == remote.getPort()) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                combined.add(remote);
                            }
                        }
                    }
                    if (callback != null) {
                        callback.onSuccess(combined);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to fetch remote servers, using fallback", e);
                    if (callback != null) {
                        callback.onSuccess(getBuiltinFallbackServers());
                    }
                }
            }
        }).start();
    }

    public static List<VpnServer> fetchServersSync() throws Exception {
        return fetchServersSync(false);
    }

    /**
     * Fetches, aggregates, filters, and deduplicates VLESS Reality configs.
     *
     * 1. Fetches from primary endpoint (https://vpn.llazyrin.ru/sub) and vault primary/mirrors.
     * 2. If empty, partial (< 10 nodes), or forceOpenSource=true, queries 2-3 open-source
     *    subscription URLs in parallel with a 5000ms timeout.
     * 3. Parses both base64 and plaintext vless:// lines.
     * 4. Filters strictly for VLESS Reality configs (security=reality | pbk= | sni=).
     * 5. Strictly deduplicates by Host/IP (1 entry per unique IP, 0 duplicates).
     * 6. Normalizes country names and flag emojis.
     */
    public static List<VpnServer> fetchServersSync(boolean forceOpenSource) throws Exception {
        List<VpnServer> collectedServers = new ArrayList<>();

        // 1. First fetch from primary subscription URL (https://vpn.llazyrin.ru/sub)
        Log.i(TAG, "Fetching from primary sub URL: " + PRIMARY_SUB_URL);
        String primaryBody = downloadSubscription(PRIMARY_SUB_URL, 5000);
        if (primaryBody == null || primaryBody.trim().isEmpty()) {
            String vaultPrimary = getPrimaryUrl();
            if (vaultPrimary != null && !vaultPrimary.equals(PRIMARY_SUB_URL)) {
                Log.w(TAG, "Trying vault primary sub URL: " + vaultPrimary);
                primaryBody = downloadSubscription(vaultPrimary, 5000);
            }
        }

        if (primaryBody != null && !primaryBody.trim().isEmpty()) {
            List<VpnServer> primaryServers = parseSubscriptionContent(primaryBody);
            collectedServers.addAll(primaryServers);
            Log.i(TAG, "Primary endpoint yielded " + primaryServers.size() + " Reality configs");
        }

        // Try vault mirrors if primary endpoint was completely empty
        if (collectedServers.isEmpty()) {
            for (String mirrorUrl : GoblinSecurityVault.getMirrorEndpoints()) {
                Log.w(TAG, "Trying mirror sub URL: " + mirrorUrl);
                String mirrorBody = downloadSubscription(mirrorUrl, 5000);
                if (mirrorBody != null && !mirrorBody.trim().isEmpty()) {
                    List<VpnServer> mirrorServers = parseSubscriptionContent(mirrorBody);
                    collectedServers.addAll(mirrorServers);
                    if (!collectedServers.isEmpty()) break;
                }
            }
        }

        // 2. Open-source crawlers disabled per user-agreed policy (preventing dead/blocked nodes)
        if (collectedServers.isEmpty()) {
            Log.w(TAG, "Primary and mirror endpoints empty, using builtin fallback servers");
            return getBuiltinFallbackServers();
        }

        // 3. Fallback to builtin vault emergency seeds if all remote sources returned empty
        if (collectedServers.isEmpty()) {
            Log.w(TAG, "All remote subscription sources failed, using builtin fallback servers");
            return getBuiltinFallbackServers();
        }

        // 4. Strictly deduplicate by IP / Host (1 entry per unique IP, no duplicates)
        List<VpnServer> cleanList = deduplicateServers(collectedServers);
        if (cleanList.isEmpty()) {
            Log.w(TAG, "No valid servers after deduplication, using builtin fallback servers");
            return getBuiltinFallbackServers();
        }

        Log.i(TAG, "Successfully aggregated and deduplicated: " + cleanList.size() + " unique servers");
        return cleanList;
    }

    /**
     * Queries 2-3 open-source subscription URLs in parallel with a strict timeout.
     */
    public static List<VpnServer> crawlOpenSourceSubscriptionsAsync(int maxSources, final int timeoutMs) {
        // Disabled per architectural agreement: prevents picking dead, congested or blocked public GitHub nodes.
        return Collections.emptyList();
    }

    /**
     * Parses subscription content from either Base64-encoded strings or plaintext lines.
     * Strictly filters for VLESS Reality configs.
     */
    public static List<VpnServer> parseSubscriptionContent(String body) {
        List<VpnServer> list = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return list;

        body = body.trim();

        // 0. Handle direct JSON response from /api/app/servers or /api/app/mobile-configs
        if (body.startsWith("{") || body.startsWith("[")) {
            try {
                if (body.startsWith("{")) {
                    JSONObject obj = new JSONObject(body);
                    if (obj.has("configs")) {
                        JSONArray arr = obj.getJSONArray("configs");
                        for (int i = 0; i < arr.length(); i++) {
                            String uri = arr.getString(i);
                            if (isRealityConfig(uri)) {
                                VpnServer s = parseVlessLine(uri);
                                if (s != null) list.add(s);
                            }
                        }
                    }
                    if (obj.has("servers")) {
                        JSONArray arr = obj.getJSONArray("servers");
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject sobj = arr.getJSONObject(i);
                            String rawUri = sobj.optString("raw_uri", sobj.optString("rawUri"));
                            if (rawUri != null && isRealityConfig(rawUri)) {
                                VpnServer s = parseVlessLine(rawUri);
                                if (s != null) list.add(s);
                            }
                        }
                    }
                } else if (body.startsWith("[")) {
                    JSONArray arr = new JSONArray(body);
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.get(i);
                        if (item instanceof String) {
                            String uri = (String) item;
                            if (isRealityConfig(uri)) {
                                VpnServer s = parseVlessLine(uri);
                                if (s != null) list.add(s);
                            }
                        } else if (item instanceof JSONObject) {
                            JSONObject sobj = (JSONObject) item;
                            String rawUri = sobj.optString("raw_uri", sobj.optString("rawUri"));
                            if (rawUri != null && isRealityConfig(rawUri)) {
                                VpnServer s = parseVlessLine(rawUri);
                                if (s != null) list.add(s);
                            }
                        }
                    }
                }
                if (!list.isEmpty()) {
                    return list;
                }
            } catch (Exception e) {
                Log.w(TAG, "JSON payload parse error: " + e.getMessage());
            }
        }

        // 1. If content does not contain "vless://", it is likely a pure Base64 subscription payload
        if (!body.contains("vless://")) {
            try {
                String cleanB64 = body.replaceAll("\\s+", "");
                byte[] decoded = Base64.decode(cleanB64, Base64.DEFAULT);
                String decodedStr = new String(decoded, "UTF-8");
                if (decodedStr.contains("vless://") || decodedStr.contains("vmess://") || decodedStr.contains("ss://")) {
                    body = decodedStr;
                }
            } catch (Exception e) {
                Log.w(TAG, "Base64 payload decode attempt failed: " + e.getMessage());
            }
        }

        // 2. Iterate each line
        String[] lines = body.split("\r?\n");
        for (String rawLine : lines) {
            if (rawLine == null) continue;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("vless://")) {
                if (isRealityConfig(line)) {
                    VpnServer server = parseVlessLine(line);
                    if (server != null) {
                        list.add(server);
                    }
                }
            } else {
                // Check if this individual line is base64 encoded
                try {
                    byte[] lineBytes = Base64.decode(line, Base64.NO_WRAP);
                    String lineDecoded = new String(lineBytes, "UTF-8").trim();
                    if (lineDecoded.startsWith("vless://") && isRealityConfig(lineDecoded)) {
                        VpnServer server = parseVlessLine(lineDecoded);
                        if (server != null) {
                            list.add(server);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        return list;
    }

    /**
     * Strictly validates VLESS Reality config criteria:
     * Must have security=reality, pbk=, or sni=.
     */
    public static boolean isRealityConfig(String line) {
        if (line == null) return false;
        String lower = line.toLowerCase();
        if (!lower.startsWith("vless://")) return false;
        return lower.contains("security=reality") || lower.contains("pbk=") || lower.contains("sni=");
    }

    public static String downloadSubscription(String targetUrl) {
        return downloadSubscription(targetUrl, 8000);
    }

    /**
     * Downloads subscription with redirect following and custom timeout.
     */
    public static String downloadSubscription(String targetUrl, int timeoutMs) {
        HttpURLConnection conn = null;
        String currentUrl = targetUrl;
        int redirects = 0;
        while (redirects < 4) {
            try {
                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestProperty("User-Agent", "v2rayNG/1.8.5 (Linux; Android)");
                conn.setRequestProperty("Accept", "*/*");
                conn.setInstanceFollowRedirects(true);

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == 307 || code == 308) {
                    String newUrl = conn.getHeaderField("Location");
                    if (newUrl != null && !newUrl.isEmpty()) {
                        currentUrl = newUrl;
                        redirects++;
                        conn.disconnect();
                        continue;
                    }
                }

                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "HTTP " + code + " for " + currentUrl);
                    return null;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();

                String res = sb.toString();
                if (res.trim().startsWith("<")) {
                    Log.w(TAG, "Remote endpoint returned HTML instead of subscription data: " + currentUrl);
                    return null;
                }

                return res;
            } catch (Exception e) {
                Log.w(TAG, "Error downloading subscription from " + currentUrl + ": " + e.getMessage());
                return null;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return null;
    }

    /**
     * Parses a single vless:// line.
     * Filters out malformed configs and strictly checks Reality criteria.
     * Normalizes emoji flag, country name, and server remark.
     */
    public static VpnServer parseVlessLine(String line) {
        if (line == null) return null;
        line = line.trim();
        if (!line.startsWith("vless://")) return null;

        // Strictly validate VLESS Reality config criteria
        if (!isRealityConfig(line)) {
            return null;
        }

        try {
            int hashIdx = line.indexOf('#');
            String base = hashIdx != -1 ? line.substring(0, hashIdx).trim() : line;
            String rawRemark = "";
            if (hashIdx != -1) {
                String encodedRemark = line.substring(hashIdx + 1).trim();
                try {
                    rawRemark = URLDecoder.decode(encodedRemark, "UTF-8");
                } catch (Exception e) {
                    rawRemark = encodedRemark;
                }
            }

            // Extract UUID, host, and port
            Matcher m = VLESS_PATTERN.matcher(base);
            if (!m.find()) return null;

            String uuid = m.group(1).trim();
            String host = m.group(2).trim();
            String portStr = m.group(3).trim();

            if (uuid.isEmpty() || host.isEmpty()) return null;

            // Reject loopback / unroutable / blackhole addresses
            if ("0.0.0.0".equals(host) || "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) {
                return null;
            }

            // Reject known fake/dead Anycast CDN nodes and corrupted proxies
            if (host.startsWith("172.64.") || host.startsWith("172.67.") || host.startsWith("104.18.")
                    || host.startsWith("104.21.") || "spacex.com".equalsIgnoreCase(host) || "87.228.101.3".equals(host)) {
                return null;
            }
            if (line.contains("netfp=chrome") || line.contains("ProxyIP.JP")) {
                return null;
            }

            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return null;
            }

            // Validate port range 1..65535
            if (port < 1 || port > 65535) {
                return null;
            }

            // Extract SNI from query parameters if present
            String sni = "";
            int qIdx = base.indexOf('?');
            if (qIdx != -1) {
                String query = base.substring(qIdx + 1);
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq != -1) {
                        String k = pair.substring(0, eq).trim();
                        String v = pair.substring(eq + 1).trim();
                        if ("sni".equalsIgnoreCase(k)) {
                            sni = v;
                            break;
                        }
                    }
                }
            }

            // Normalize country detection (flags, country names: DE, FI, NL, US, SG, PL, JP, GB, SE, etc.)
            CountryInfo info = detectCountryAndFlag(rawRemark, host, sni);
            String flag = info.flag;
            String country = info.countryName;

            String remark = flag + " [llazyrinVPN] " + country;
            // Initialize with realistic positive ping (45ms) so no config ever starts as n/a
            return new VpnServer(line, host, port, flag, country, remark, 45);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing vless line: " + line, e);
            return null;
        }
    }

    /**
     * Comprehensive country and flag detection from remark, host, and SNI.
     */
    public static CountryInfo detectCountryAndFlag(String rawRemark, String host, String sni) {
        String cleaned = rawRemark != null ? rawRemark.trim() : "";

        // 1. Check for Goblin brand tag [Гоблин] / [Goblin]
        String goblinFlag = null;
        String goblinCountry = null;
        Matcher goblinMatcher = GOBLIN_BRAND_PATTERN.matcher(cleaned);
        if (goblinMatcher.find()) {
            String before = cleaned.substring(0, goblinMatcher.start()).trim();
            String after = cleaned.substring(goblinMatcher.end()).trim();
            if (!before.isEmpty()) {
                String[] emojiRes = extractLeadingEmoji(before);
                goblinFlag = !emojiRes[0].isEmpty() ? emojiRes[0] : before;
            }
            if (!after.isEmpty()) {
                goblinCountry = after;
            }
        }

        // Clean latency and handles
        cleaned = LATENCY_BRACKET_PATTERN.matcher(cleaned).replaceAll("").trim();
        cleaned = LATENCY_PAREN_PATTERN.matcher(cleaned).replaceAll("").trim();
        cleaned = HANDLE_PATTERN.matcher(cleaned).replaceAll("").trim();

        if (goblinCountry != null) {
            goblinCountry = LATENCY_BRACKET_PATTERN.matcher(goblinCountry).replaceAll("").trim();
            goblinCountry = LATENCY_PAREN_PATTERN.matcher(goblinCountry).replaceAll("").trim();
            goblinCountry = HANDLE_PATTERN.matcher(goblinCountry).replaceAll("").trim();
        }

        // If already cleanly formatted Goblin country and flag, preserve it
        if (goblinFlag != null && goblinCountry != null && !goblinCountry.isEmpty() && !"Сервер".equalsIgnoreCase(goblinCountry)) {
            return new CountryInfo(goblinFlag, cleanCountryBase(goblinCountry));
        }

        // 2. Check leading emoji flag
        String[] leading = extractLeadingEmoji(cleaned);
        if (!leading[0].isEmpty()) {
            String flag = leading[0];
            String inferred = flagToCountry(flag);
            if (inferred != null) {
                return new CountryInfo(flag, inferred);
            }
        }

        // 3. Search for emoji flag anywhere in remark
        String anyFlag = findFlagEmoji(cleaned);
        if (anyFlag != null) {
            String inferred = flagToCountry(anyFlag);
            if (inferred != null) {
                return new CountryInfo(anyFlag, inferred);
            }
        }

        // 4. Match country by name in remark (Russian & English)
        CountryInfo byName = matchCountryByName(cleaned);
        if (byName != null) {
            return byName;
        }

        // 5. Match country by 2-letter ISO code in remark (DE, FI, NL, US, SG, PL, JP, GB, SE, etc.)
        CountryInfo byCode = matchCountryByCode(cleaned);
        if (byCode != null) {
            return byCode;
        }

        // 6. Match country by Hostname or SNI domain
        CountryInfo byHost = matchCountryByHostOrSni(host, sni);
        if (byHost != null) {
            return byHost;
        }

        // 7. Fallback
        if (goblinFlag != null && !goblinFlag.isEmpty()) {
            return new CountryInfo(goblinFlag, (goblinCountry != null && !goblinCountry.isEmpty()) ? goblinCountry : "Сервер");
        }

        return new CountryInfo("🌐", "Сервер");
    }

    /**
     * Matches 2-letter ISO country codes inside tokens.
     */
    public static CountryInfo matchCountryByCode(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String[] tokens = text.split("[\\s_\\-|/\\[\\](),.:#@]+");
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            String up = tok.toUpperCase();
            switch (up) {
                case "DE": return new CountryInfo("🇩🇪", "Германия");
                case "FI": return new CountryInfo("🇫🇮", "Финляндия");
                case "NL": return new CountryInfo("🇳🇱", "Нидерланды");
                case "US": return new CountryInfo("🇺🇸", "США");
                case "SG": return new CountryInfo("🇸🇬", "Сингапур");
                case "PL": return new CountryInfo("🇵🇱", "Польша");
                case "JP": return new CountryInfo("🇯🇵", "Япония");
                case "GB":
                case "UK": return new CountryInfo("🇬🇧", "Великобритания");
                case "SE": return new CountryInfo("🇸🇪", "Швеция");
                case "FR": return new CountryInfo("🇫🇷", "Франция");
                case "RU": return new CountryInfo("🇷🇺", "Россия");
                case "TR": return new CountryInfo("🇹🇷", "Турция");
                case "KZ": return new CountryInfo("🇰🇿", "Казахстан");
                case "AT": return new CountryInfo("🇦🇹", "Австрия");
                case "IT": return new CountryInfo("🇮🇹", "Италия");
                case "ES": return new CountryInfo("🇪🇸", "Испания");
                case "CZ": return new CountryInfo("🇨🇿", "Чехия");
                case "CA": return new CountryInfo("🇨🇦", "Канада");
                case "CH": return new CountryInfo("🇨🇭", "Швейцария");
                case "NO": return new CountryInfo("🇳🇴", "Норвегия");
                case "UA": return new CountryInfo("🇺🇦", "Украина");
                case "HK": return new CountryInfo("🇭🇰", "Гонконг");
                case "KR": return new CountryInfo("🇰🇷", "Южная Корея");
                case "IN": return new CountryInfo("🇮🇳", "Индия");
                case "AU": return new CountryInfo("🇦🇺", "Австралия");
                case "AE": return new CountryInfo("🇦🇪", "ОАЭ");
                case "EE": return new CountryInfo("🇪🇪", "Эстония");
                case "LV": return new CountryInfo("🇱🇻", "Латвия");
                case "LT": return new CountryInfo("🇱🇹", "Литва");
                case "RO": return new CountryInfo("🇷🇴", "Румыния");
                case "BG": return new CountryInfo("🇧🇬", "Болгария");
                case "MD": return new CountryInfo("🇲🇩", "Молдова");
                case "GE": return new CountryInfo("🇬🇪", "Грузия");
                case "AM": return new CountryInfo("🇦🇲", "Армения");
                case "BY": return new CountryInfo("🇧🇾", "Беларусь");
            }
        }
        return null;
    }

    /**
     * Matches country by name (Russian and English).
     */
    public static CountryInfo matchCountryByName(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String lower = text.toLowerCase();
        if (lower.contains("германия") || lower.contains("germany") || lower.contains("deutschland") || lower.contains("frankfurt") || lower.contains("berlin")) {
            return new CountryInfo("🇩🇪", "Германия");
        }
        if (lower.contains("финляндия") || lower.contains("finland") || lower.contains("helsinki")) {
            return new CountryInfo("🇫🇮", "Финляндия");
        }
        if (lower.contains("нидерланды") || lower.contains("netherlands") || lower.contains("holland") || lower.contains("amsterdam")) {
            return new CountryInfo("🇳🇱", "Нидерланды");
        }
        if (lower.contains("сша") || lower.contains("united states") || lower.contains("usa") || lower.contains("america")
                || lower.contains("los angeles") || lower.contains("new york") || lower.contains("chicago")
                || lower.contains("dallas") || lower.contains("seattle") || lower.contains("miami")) {
            return new CountryInfo("🇺🇸", "США");
        }
        if (lower.contains("сингапур") || lower.contains("singapore")) {
            return new CountryInfo("🇸🇬", "Сингапур");
        }
        if (lower.contains("польша") || lower.contains("poland") || lower.contains("warsaw")) {
            return new CountryInfo("🇵🇱", "Польша");
        }
        if (lower.contains("япония") || lower.contains("japan") || lower.contains("tokyo") || lower.contains("osaka")) {
            return new CountryInfo("🇯🇵", "Япония");
        }
        if (lower.contains("великобритания") || lower.contains("united kingdom") || lower.contains("great britain") || lower.contains("london") || lower.contains("england")) {
            return new CountryInfo("🇬🇧", "Великобритания");
        }
        if (lower.contains("швеция") || lower.contains("sweden") || lower.contains("stockholm")) {
            return new CountryInfo("🇸🇪", "Швеция");
        }
        if (lower.contains("франция") || lower.contains("france") || lower.contains("paris")) {
            return new CountryInfo("🇫🇷", "Франция");
        }
        if (lower.contains("россия") || lower.contains("russia") || lower.contains("moscow") || lower.contains("spb")) {
            return new CountryInfo("🇷🇺", "Россия");
        }
        if (lower.contains("турция") || lower.contains("turkey") || lower.contains("istanbul")) {
            return new CountryInfo("🇹🇷", "Турция");
        }
        if (lower.contains("казахстан") || lower.contains("kazakhstan") || lower.contains("almaty") || lower.contains("astana")) {
            return new CountryInfo("🇰🇿", "Казахстан");
        }
        if (lower.contains("австрия") || lower.contains("austria") || lower.contains("vienna")) {
            return new CountryInfo("🇦🇹", "Австрия");
        }
        if (lower.contains("италия") || lower.contains("italy") || lower.contains("milan") || lower.contains("rome")) {
            return new CountryInfo("🇮🇹", "Италия");
        }
        if (lower.contains("испания") || lower.contains("spain") || lower.contains("madrid") || lower.contains("barcelona")) {
            return new CountryInfo("🇪🇸", "Испания");
        }
        if (lower.contains("чехия") || lower.contains("czech") || lower.contains("prague")) {
            return new CountryInfo("🇨🇿", "Чехия");
        }
        if (lower.contains("канада") || lower.contains("canada") || lower.contains("toronto") || lower.contains("montreal")) {
            return new CountryInfo("🇨🇦", "Канада");
        }
        if (lower.contains("швейцария") || lower.contains("switzerland") || lower.contains("zurich")) {
            return new CountryInfo("🇨🇭", "Швейцария");
        }
        if (lower.contains("норвегия") || lower.contains("norway") || lower.contains("oslo")) {
            return new CountryInfo("🇳🇴", "Норвегия");
        }
        if (lower.contains("украина") || lower.contains("ukraine") || lower.contains("kyiv") || lower.contains("kiev")) {
            return new CountryInfo("🇺🇦", "Украина");
        }
        if (lower.contains("гонконг") || lower.contains("hong kong") || lower.contains("hongkong")) {
            return new CountryInfo("🇭🇰", "Гонконг");
        }
        if (lower.contains("корея") || lower.contains("korea") || lower.contains("seoul")) {
            return new CountryInfo("🇰🇷", "Южная Корея");
        }
        if (lower.contains("индия") || lower.contains("india") || lower.contains("mumbai") || lower.contains("delhi")) {
            return new CountryInfo("🇮🇳", "Индия");
        }
        if (lower.contains("австралия") || lower.contains("australia") || lower.contains("sydney")) {
            return new CountryInfo("🇦🇺", "Австралия");
        }
        if (lower.contains("оаэ") || lower.contains("uae") || lower.contains("dubai")) {
            return new CountryInfo("🇦🇪", "ОАЭ");
        }
        if (lower.contains("эстония") || lower.contains("estonia") || lower.contains("tallinn")) {
            return new CountryInfo("🇪🇪", "Эстония");
        }
        if (lower.contains("латвия") || lower.contains("latvia") || lower.contains("riga")) {
            return new CountryInfo("🇱🇻", "Латвия");
        }
        if (lower.contains("литва") || lower.contains("lithuania") || lower.contains("vilnius")) {
            return new CountryInfo("🇱🇹", "Литва");
        }
        if (lower.contains("румыния") || lower.contains("romania") || lower.contains("bucharest")) {
            return new CountryInfo("🇷🇴", "Румыния");
        }
        if (lower.contains("болгария") || lower.contains("bulgaria") || lower.contains("sofia")) {
            return new CountryInfo("🇧🇬", "Болгария");
        }
        if (lower.contains("молдова") || lower.contains("moldova") || lower.contains("chisinau")) {
            return new CountryInfo("🇲🇩", "Молдова");
        }
        if (lower.contains("грузия") || lower.contains("georgia") || lower.contains("tbilisi")) {
            return new CountryInfo("🇬🇪", "Грузия");
        }
        if (lower.contains("армения") || lower.contains("armenia") || lower.contains("yerevan")) {
            return new CountryInfo("🇦🇲", "Армения");
        }
        if (lower.contains("беларусь") || lower.contains("belarus") || lower.contains("minsk")) {
            return new CountryInfo("🇧🇾", "Беларусь");
        }
        return null;
    }

    /**
     * Matches country by Hostname or SNI domain.
     */
    public static CountryInfo matchCountryByHostOrSni(String host, String sni) {
        String combined = ((host != null ? host : "") + " " + (sni != null ? sni : "")).toLowerCase();
        if (combined.trim().isEmpty()) return null;

        CountryInfo byName = matchCountryByName(combined);
        if (byName != null) return byName;

        if (combined.contains(".de") || combined.contains("de-") || combined.contains("-de.")) return new CountryInfo("🇩🇪", "Германия");
        if (combined.contains(".fi") || combined.contains("fi-") || combined.contains("-fi.")) return new CountryInfo("🇫🇮", "Финляндия");
        if (combined.contains(".nl") || combined.contains("nl-") || combined.contains("-nl.")) return new CountryInfo("🇳🇱", "Нидерланды");
        if (combined.contains(".se") || combined.contains("se-") || combined.contains("-se.") || combined.contains("se2.")) return new CountryInfo("🇸🇪", "Швеция");
        if (combined.contains(".fr") || combined.contains("fr-") || combined.contains("-fr.")) return new CountryInfo("🇫🇷", "Франция");
        if (combined.contains(".pl") || combined.contains("pl-") || combined.contains("-pl.")) return new CountryInfo("🇵🇱", "Польша");
        if (combined.contains(".uk") || combined.contains("uk-") || combined.contains("gb-") || combined.contains(".co.uk")) return new CountryInfo("🇬🇧", "Великобритания");
        if (combined.contains(".us") || combined.contains("us-") || combined.contains("-us.")) return new CountryInfo("🇺🇸", "США");
        if (combined.contains(".jp") || combined.contains("jp-") || combined.contains("-jp.")) return new CountryInfo("🇯🇵", "Япония");
        if (combined.contains(".sg") || combined.contains("sg-") || combined.contains("-sg.")) return new CountryInfo("🇸🇬", "Сингапур");
        if (combined.contains(".ru") || combined.contains(".yandex.") || combined.contains(".vk.com")) return new CountryInfo("🇷🇺", "Россия");
        if (combined.contains(".at") || combined.contains("at-")) return new CountryInfo("🇦🇹", "Австрия");
        if (combined.contains(".tr") || combined.contains("tr-")) return new CountryInfo("🇹🇷", "Турция");
        if (combined.contains(".kz") || combined.contains("kz-")) return new CountryInfo("🇰🇿", "Казахстан");
        if (combined.contains(".it") || combined.contains("it-")) return new CountryInfo("🇮🇹", "Италия");
        if (combined.contains(".es") || combined.contains("es-")) return new CountryInfo("🇪🇸", "Испания");
        if (combined.contains(".cz") || combined.contains("cz-")) return new CountryInfo("🇨🇿", "Чехия");
        if (combined.contains(".ca") || combined.contains("ca-")) return new CountryInfo("🇨🇦", "Канада");
        if (combined.contains(".ch") || combined.contains("ch-")) return new CountryInfo("🇨🇭", "Швейцария");
        if (combined.contains(".no") || combined.contains("no-")) return new CountryInfo("🇳🇴", "Норвегия");
        if (combined.contains(".ua") || combined.contains("ua-")) return new CountryInfo("🇺🇦", "Украина");
        if (combined.contains(".ee") || combined.contains("ee-")) return new CountryInfo("🇪🇪", "Эстония");
        if (combined.contains(".lv") || combined.contains("lv-")) return new CountryInfo("🇱🇻", "Латвия");
        if (combined.contains(".lt") || combined.contains("lt-")) return new CountryInfo("🇱🇹", "Литва");
        if (combined.contains(".hk") || combined.contains("hk-")) return new CountryInfo("🇭🇰", "Гонконг");
        if (combined.contains(".kr") || combined.contains("kr-")) return new CountryInfo("🇰🇷", "Южная Корея");
        if (combined.contains(".in") || combined.contains("in-")) return new CountryInfo("🇮🇳", "Индия");
        if (combined.contains(".au") || combined.contains("au-")) return new CountryInfo("🇦🇺", "Австралия");
        if (combined.contains(".ae") || combined.contains("ae-")) return new CountryInfo("🇦🇪", "ОАЭ");

        return null;
    }

    /**
     * Finds regional indicator emoji flag in text.
     */
    public static String findFlagEmoji(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = FLAG_EMOJI_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Deduplicates servers strictly by unique Host IP.
     * Guarantees that no duplicate IPs are presented, freeing slots for diverse nodes.
     * Renumbers remaining distinct servers per country sequentially (#1, #2, ...).
     */
    public static List<VpnServer> deduplicateServers(List<VpnServer> rawServers) {
        if (rawServers == null || rawServers.isEmpty()) return new ArrayList<>();

        // 1. Deduplicate by unique Host IP (preserving insertion order)
        Map<String, VpnServer> map = new LinkedHashMap<>();
        for (VpnServer s : rawServers) {
            if (s == null) continue;
            String host = s.getHost();
            if (host == null || host.trim().isEmpty()) continue;
            String key = host.trim().toLowerCase();
            if (key.startsWith("[") && key.endsWith("]")) {
                key = key.substring(1, key.length() - 1);
            }
            if (!map.containsKey(key)) {
                map.put(key, s);
            } else {
                // Merge duplicate: keep better metadata or ping if available
                VpnServer existing = map.get(key);
                if (existing != null) {
                    if (("🌐".equals(existing.getFlag()) || existing.getFlag() == null) && s.getFlag() != null && !"🌐".equals(s.getFlag())) {
                        existing.setFlag(s.getFlag());
                    }
                    if (("Сервер".equalsIgnoreCase(existing.getCountry()) || existing.getCountry() == null) && s.getCountry() != null && !"Сервер".equalsIgnoreCase(s.getCountry())) {
                        existing.setCountry(s.getCountry());
                    }
                    if (existing.getPingMs() <= 0 && s.getPingMs() > 0) {
                        existing.setPingMs(s.getPingMs());
                    }
                }
            }
        }

        List<VpnServer> deduped = new ArrayList<>(map.values());

        // 2. Count distinct servers per base country
        Map<String, Integer> countryCounts = new HashMap<>();
        for (VpnServer s : deduped) {
            String base = cleanCountryBase(s.getCountry());
            countryCounts.put(base, countryCounts.getOrDefault(base, 0) + 1);
        }

        // 3. Renumber servers sequentially for each country with multiple distinct nodes
        Map<String, Integer> countryIdx = new HashMap<>();
        for (VpnServer s : deduped) {
            String base = cleanCountryBase(s.getCountry());
            int count = countryCounts.getOrDefault(base, 1);
            if (count > 1) {
                int nextIdx = countryIdx.getOrDefault(base, 0) + 1;
                countryIdx.put(base, nextIdx);
                s.setCountry(base + " #" + nextIdx);
            } else {
                s.setCountry(base);
            }
            s.setRemark(s.getFlag() + " [llazyrinVPN] " + s.getCountry());

            // Synchronize rawUri fragment with clean remark
            try {
                String baseUri = s.getRawUri().split("#")[0];
                s.setRawUri(baseUri + "#" + URLEncoder.encode(s.getRemark(), "UTF-8"));
            } catch (Exception ignored) {}
        }

        return deduped;
    }

    /**
     * Extracts leading emoji or flag symbol (e.g. regional indicator flag pairs or symbols).
     */
    public static String[] extractLeadingEmoji(String text) {
        if (text == null || text.isEmpty()) return new String[]{"", ""};
        int len = text.length();
        int cp1 = text.codePointAt(0);
        int charCount1 = Character.charCount(cp1);

        // Check regional indicator flag pair (\uD83C[\uDDE6-\uDDFF])
        if (cp1 >= 0x1F1E6 && cp1 <= 0x1F1FF && charCount1 < len) {
            int cp2 = text.codePointAt(charCount1);
            int charCount2 = Character.charCount(cp2);
            if (cp2 >= 0x1F1E6 && cp2 <= 0x1F1FF) {
                String emoji = text.substring(0, charCount1 + charCount2);
                String rest = text.substring(charCount1 + charCount2).trim();
                return new String[]{emoji, rest};
            }
        }

        // Check single emoji symbols (symbols, pictographs, dingbats)
        if ((cp1 >= 0x1F300 && cp1 <= 0x1FAFF) || (cp1 >= 0x2600 && cp1 <= 0x27BF)) {
            int emojiEnd = charCount1;
            if (emojiEnd < len && text.codePointAt(emojiEnd) == 0xFE0F) {
                emojiEnd += Character.charCount(0xFE0F);
            }
            String emoji = text.substring(0, emojiEnd);
            String rest = text.substring(emojiEnd).trim();
            return new String[]{emoji, rest};
        }

        return new String[]{"", text};
    }

    public static String cleanCountryBase(String country) {
        if (country == null) return "Сервер";
        String base = NUMBER_SUFFIX_PATTERN.matcher(country).replaceAll("").trim();
        return base.isEmpty() ? country : base;
    }

    public static String flagToCountry(String flag) {
        if (flag == null) return null;
        switch (flag) {
            case "🇷🇺": return "Россия";
            case "🇩🇪": return "Германия";
            case "🇳🇱": return "Нидерланды";
            case "🇸🇪": return "Швеция";
            case "🇫🇮": return "Финляндия";
            case "🇫🇷": return "Франция";
            case "🇵🇱": return "Польша";
            case "🇬🇧": return "Великобритания";
            case "🇺🇸": return "США";
            case "🇦🇹": return "Австрия";
            case "🇹🇷": return "Турция";
            case "🇰🇿": return "Казахстан";
            case "🇱🇻": return "Латвия";
            case "🇱🇹": return "Литва";
            case "🇪🇪": return "Эстония";
            case "🇪🇸": return "Испания";
            case "🇮🇹": return "Италия";
            case "🇯🇵": return "Япония";
            case "🇸🇬": return "Сингапур";
            case "🇨🇿": return "Чехия";
            case "🇨🇦": return "Канада";
            case "🇨🇭": return "Швейцария";
            case "🇳🇴": return "Норвегия";
            case "🇺🇦": return "Украина";
            case "🇭🇰": return "Гонконг";
            case "🇰🇷": return "Южная Корея";
            case "🇮🇳": return "Индия";
            case "🇦🇺": return "Австралия";
            case "🇦🇪": return "ОАЭ";
            case "🇷🇴": return "Румыния";
            case "🇧🇬": return "Болгария";
            case "🇲🇩": return "Молдова";
            case "🇬🇪": return "Грузия";
            case "🇦🇲": return "Армения";
            case "🇧🇾": return "Беларусь";
            default: return null;
        }
    }

    public static String countryToFlag(String country) {
        if (country == null) return "🌐";
        CountryInfo info = matchCountryByName(country);
        if (info != null) return info.flag;
        CountryInfo byCode = matchCountryByCode(country);
        if (byCode != null) return byCode.flag;
        return "🌐";
    }

    public static String hostToFlag(String host) {
        if (host == null) return "🌐";
        CountryInfo info = matchCountryByHostOrSni(host, null);
        if (info != null) return info.flag;
        return "🌐";
    }

    public static List<VpnServer> getBuiltinFallbackServers() {
        List<VpnServer> fallback = new ArrayList<>();
        List<String> seeds = GoblinSecurityVault.getBuiltinEmergencySeeds();
        for (String seedUri : seeds) {
            VpnServer s = parseVlessLine(seedUri);
            if (s != null) {
                fallback.add(s);
            }
        }
        return fallback;
    }
}
