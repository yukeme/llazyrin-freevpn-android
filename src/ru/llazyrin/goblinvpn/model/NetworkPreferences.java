package ru.llazyrin.goblinvpn.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class NetworkPreferences {
    private static final String PREF_NAME = "goblin_vpn_network_prefs";

    public static final String KEY_KILL_SWITCH = "kill_switch_enabled";
    public static final String KEY_AUTO_CONNECT = "auto_connect_enabled";
    public static final String KEY_DNS_PROVIDER = "dns_provider";

    public static final String KEY_BYPASS_RU_TRAFFIC = "bypass_ru_traffic_enabled";
    public static final String KEY_POPULAR_SERVICES_VIA_VPN = "popular_services_via_vpn_enabled";
    public static final String KEY_SELECTED_GEO_COUNTRIES = "selected_geo_countries";
    public static final String KEY_CUSTOM_DOMAINS = "custom_domains";
    public static final String KEY_GEO_RULES = "geo_rules";
    public static final String KEY_CLIENT_ID = "client_id";
    public static final String KEY_SUBSCRIPTION_KEY = "subscription_key";
    public static final String KEY_APP_ROUTING_MODE = "app_routing_mode";
    public static final String KEY_ROUTED_PACKAGES = "routed_packages";
    public static final String KEY_HIDE_SYSTEM_APPS = "hide_system_apps";
    public static final String KEY_AUTO_SWITCH_SERVER = "auto_switch_server";
    public static final String KEY_USER_LOGGING_CONSENT = "user_logging_consent_granted";
    public static final String KEY_SELECTED_SERVER_HOST = "selected_server_host";
    public static final String KEY_IS_AUTO_SERVER_MODE = "is_auto_server_mode";
    public static final String KEY_SHARE_WITH_COMMUNITY = "share_with_community_enabled";
    public static final String KEY_RESTRICTED_TELEGRAM_ONLY = "restricted_telegram_only";
    public static final String KEY_VPN_DESIRED_ACTIVE = "vpn_desired_active";
    public static final String KEY_LAST_SUBSCRIPTION_CHECK = "last_subscription_check_timestamp";
    public static final String KEY_TELEGRAM_SUBSCRIBED = "telegram_subscribed";
    public static final String KEY_PRIVACY_POLICY_ACCEPTED = "privacy_policy_accepted";

    public enum AppRoutingMode {
        ALL("ВЫКЛ", "Для всех приложений", "Туннель применяется ко всем приложениям устройства без исключений"),
        ONLY_SELECTED("ВКЛ", "Только для выбранных", "Только выбранные приложения выходят через прокси. Все остальные работают напрямую"),
        BYPASS_SELECTED("ОБХОД", "Обход для выбранных", "Выбранные приложения обходят прокси и работают напрямую. Все остальные используют туннель");

        private final String code;
        private final String title;
        private final String description;

        AppRoutingMode(String code, String title, String description) {
            this.code = code;
            this.title = title;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public static AppRoutingMode fromName(String name) {
            if (name == null) return ALL;
            try {
                return AppRoutingMode.valueOf(name);
            } catch (Exception e) {
                return ALL;
            }
        }
    }

    private static final String[] DEFAULT_GEO_COUNTRIES = new String[]{
            "Россия (IP)",
            "Россия (сайты)"
    };

    private static final String[] DEFAULT_CUSTOM_DOMAINS = new String[]{
            "gosuslugi.ru",
            "sberbank.ru"
    };

    private static final String[] DEFAULT_GEO_RULES = new String[]{
            "geoip:ru",
            "geosite:category-ru",
            "geosite:private"
    };

    public enum DnsOption {
        CLOUDFLARE(
                "Cloudflare (1.1.1.1)",
                "1.1.1.1",
                new String[]{"1.1.1.1", "1.0.0.1"},
                "1.1.1.1 • 1.0.0.1  |  Скорость и приватность"
        ),
        GOOGLE(
                "Google (8.8.8.8)",
                "8.8.8.8",
                new String[]{"8.8.8.8", "8.8.4.4"},
                "8.8.8.8 • 8.8.4.4  |  Глобальная стабильность"
        ),
        ADGUARD(
                "AdGuard (anti-ad DNS)",
                "Anti-Ad",
                new String[]{"94.140.14.14", "94.140.15.15"},
                "94.140.14.14 • 94.140.15.15  |  Блокировка рекламы"
        );

        private final String title;
        private final String badge;
        private final String[] servers;
        private final String summary;

        DnsOption(String title, String badge, String[] servers, String summary) {
            this.title = title;
            this.badge = badge;
            this.servers = servers;
            this.summary = summary;
        }

        public String getTitle() {
            return title;
        }

        public String getBadge() {
            return badge;
        }

        public String[] getServers() {
            return servers;
        }

        public String getSummary() {
            return summary;
        }

        public String getPrimaryServer() {
            return servers.length > 0 ? servers[0] : "1.1.1.1";
        }

        public static DnsOption fromName(String name) {
            if (name == null) return CLOUDFLARE;
            try {
                return DnsOption.valueOf(name);
            } catch (Exception e) {
                return CLOUDFLARE;
            }
        }
    }

    private static NetworkPreferences instance;
    private final Context appContext;
    private final SharedPreferences prefs;

    private NetworkPreferences(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = this.appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized NetworkPreferences getInstance(Context context) {
        if (instance == null) {
            instance = new NetworkPreferences(context);
        }
        return instance;
    }

    public boolean isKillSwitchEnabled() {
        return prefs.getBoolean(KEY_KILL_SWITCH, false);
    }

    public void setKillSwitchEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_KILL_SWITCH, enabled).apply();
    }

    public boolean isAutoConnectEnabled() {
        return prefs.getBoolean(KEY_AUTO_CONNECT, false);
    }

    public void setAutoConnectEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply();
    }

    public boolean isVpnDesiredActive() {
        return prefs.getBoolean(KEY_VPN_DESIRED_ACTIVE, false);
    }

    public void setVpnDesiredActive(boolean active) {
        prefs.edit().putBoolean(KEY_VPN_DESIRED_ACTIVE, active).apply();
    }

    public DnsOption getDnsOption() {
        String saved = prefs.getString(KEY_DNS_PROVIDER, DnsOption.CLOUDFLARE.name());
        return DnsOption.fromName(saved);
    }

    public void setDnsOption(DnsOption dnsOption) {
        if (dnsOption == null) dnsOption = DnsOption.CLOUDFLARE;
        prefs.edit().putString(KEY_DNS_PROVIDER, dnsOption.name()).apply();
    }

    public boolean isBypassRuTrafficEnabled() {
        return prefs.getBoolean(KEY_BYPASS_RU_TRAFFIC, true);
    }

    public void setBypassRuTrafficEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BYPASS_RU_TRAFFIC, enabled).apply();
    }

    public boolean isPopularServicesViaVpnEnabled() {
        return prefs.getBoolean(KEY_POPULAR_SERVICES_VIA_VPN, true);
    }

    public void setPopularServicesViaVpnEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_POPULAR_SERVICES_VIA_VPN, enabled).apply();
    }

    public Set<String> getSelectedGeoCountries() {
        if (!prefs.contains(KEY_SELECTED_GEO_COUNTRIES)) {
            return new LinkedHashSet<String>(Arrays.asList(DEFAULT_GEO_COUNTRIES));
        }
        Set<String> stored = prefs.getStringSet(KEY_SELECTED_GEO_COUNTRIES, null);
        if (stored == null) {
            return new LinkedHashSet<String>();
        }
        return new LinkedHashSet<String>(stored);
    }

    public void setSelectedGeoCountries(Set<String> countries) {
        Set<String> copy = new HashSet<String>();
        if (countries != null) {
            for (String item : countries) {
                if (item != null) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) {
                        copy.add(trimmed);
                    }
                }
            }
        }
        prefs.edit().putStringSet(KEY_SELECTED_GEO_COUNTRIES, copy).apply();
    }

    public List<String> getCustomDomains() {
        if (!prefs.contains(KEY_CUSTOM_DOMAINS)) {
            return new ArrayList<String>(Arrays.asList(DEFAULT_CUSTOM_DOMAINS));
        }
        return parseLineList(prefs.getString(KEY_CUSTOM_DOMAINS, ""));
    }

    public void setCustomDomains(List<String> domains) {
        prefs.edit().putString(KEY_CUSTOM_DOMAINS, joinLineList(domains)).apply();
    }

    public List<String> getGeoRules() {
        if (!prefs.contains(KEY_GEO_RULES)) {
            return new ArrayList<String>(Arrays.asList(DEFAULT_GEO_RULES));
        }
        return parseLineList(prefs.getString(KEY_GEO_RULES, ""));
    }

    public void setGeoRules(List<String> rules) {
        prefs.edit().putString(KEY_GEO_RULES, joinLineList(rules)).apply();
    }

    public synchronized String getClientId() {
        String saved = prefs.getString(KEY_CLIENT_ID, null);
        if (saved != null && saved.length() == 16 && isAllDigits(saved)) {
            return saved;
        }
        String generated = generateDeterministicClientId();
        prefs.edit().putString(KEY_CLIENT_ID, generated).apply();
        return generated;
    }

    public String getSubscriptionKey() {
        String saved = prefs.getString(KEY_SUBSCRIPTION_KEY, null);
        if (saved != null) {
            String trimmed = saved.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "llazyrinReality-" + computeShortHash(getClientId());
    }

    public void setSubscriptionKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            prefs.edit().remove(KEY_SUBSCRIPTION_KEY).apply();
        } else {
            prefs.edit().putString(KEY_SUBSCRIPTION_KEY, key.trim()).apply();
        }
    }

    public AppRoutingMode getAppRoutingMode() {
        String saved = prefs.getString(KEY_APP_ROUTING_MODE, AppRoutingMode.ALL.name());
        return AppRoutingMode.fromName(saved);
    }

    public void setAppRoutingMode(AppRoutingMode mode) {
        if (mode == null) mode = AppRoutingMode.ALL;
        prefs.edit().putString(KEY_APP_ROUTING_MODE, mode.name()).apply();
    }

    public Set<String> getRoutedPackages() {
        Set<String> set = prefs.getStringSet(KEY_ROUTED_PACKAGES, null);
        if (set == null) {
            return new HashSet<>();
        }
        return new HashSet<>(set);
    }

    public void setRoutedPackages(Set<String> packages) {
        Set<String> copy = new HashSet<>();
        if (packages != null) {
            for (String p : packages) {
                if (p != null && !p.trim().isEmpty()) {
                    copy.add(p.trim());
                }
            }
        }
        prefs.edit().putStringSet(KEY_ROUTED_PACKAGES, copy).apply();
    }

    public boolean isHideSystemApps() {
        return prefs.getBoolean(KEY_HIDE_SYSTEM_APPS, false);
    }

    public void setHideSystemApps(boolean hide) {
        prefs.edit().putBoolean(KEY_HIDE_SYSTEM_APPS, hide).apply();
    }

    public boolean isUserLoggingConsentGranted() {
        return prefs.getBoolean(KEY_USER_LOGGING_CONSENT, false);
    }

    public void setUserLoggingConsentGranted(boolean granted) {
        prefs.edit().putBoolean(KEY_USER_LOGGING_CONSENT, granted).apply();
    }

    public boolean isAutoSwitchServerEnabled() {
        return prefs.getBoolean(KEY_AUTO_SWITCH_SERVER, false);
    }

    public void setAutoSwitchServerEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_SWITCH_SERVER, enabled).apply();
    }

    public String getSelectedServerHost() {
        return prefs.getString(KEY_SELECTED_SERVER_HOST, null);
    }

    public void setSelectedServerHost(String host) {
        if (host == null) {
            prefs.edit().remove(KEY_SELECTED_SERVER_HOST).apply();
        } else {
            prefs.edit().putString(KEY_SELECTED_SERVER_HOST, host).apply();
        }
    }

    public boolean isAutoServerMode() {
        return prefs.getBoolean(KEY_IS_AUTO_SERVER_MODE, true);
    }

    public void setAutoServerMode(boolean autoMode) {
        prefs.edit().putBoolean(KEY_IS_AUTO_SERVER_MODE, autoMode).apply();
    }

    public boolean isShareWithCommunityEnabled() {
        return prefs.getBoolean(KEY_SHARE_WITH_COMMUNITY, true);
    }

    public void setShareWithCommunityEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SHARE_WITH_COMMUNITY, enabled).apply();
    }

    public boolean isRestrictedTelegramOnly() {
        return prefs.getBoolean(KEY_RESTRICTED_TELEGRAM_ONLY, false);
    }

    public void setRestrictedTelegramOnly(boolean enabled) {
        prefs.edit().putBoolean(KEY_RESTRICTED_TELEGRAM_ONLY, enabled).apply();
    }

    public long getLastSubscriptionCheckTimestamp() {
        return prefs.getLong(KEY_LAST_SUBSCRIPTION_CHECK, 0L);
    }

    public void setLastSubscriptionCheckTimestamp(long timestamp) {
        prefs.edit().putLong(KEY_LAST_SUBSCRIPTION_CHECK, timestamp).apply();
    }

    public boolean isTelegramSubscribed() {
        return prefs.getBoolean(KEY_TELEGRAM_SUBSCRIBED, false);
    }

    public void setTelegramSubscribed(boolean subscribed) {
        prefs.edit().putBoolean(KEY_TELEGRAM_SUBSCRIBED, subscribed).apply();
    }

    public boolean isPrivacyPolicyAccepted() {
        return prefs.getBoolean(KEY_PRIVACY_POLICY_ACCEPTED, false);
    }

    public void setPrivacyPolicyAccepted(boolean accepted) {
        prefs.edit().putBoolean(KEY_PRIVACY_POLICY_ACCEPTED, accepted).apply();
    }

    public static boolean isGooglePackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.startsWith("com.google.android")
                || p.startsWith("com.android.vending")
                || p.startsWith("com.google.android.apps.")
                || p.equals("com.google.android.youtube")
                || p.equals("com.google.android.gm")
                || p.equals("com.google.android.maps")
                || p.equals("com.google.android.play.games")
                || p.equals("com.google.android.keep")
                || p.equals("com.google.android.calendar")
                || p.equals("com.google.android.contacts")
                || p.equals("com.google.android.deskclock")
                || p.equals("com.google.android.apps.photos")
                || p.equals("com.google.android.apps.docs");
    }

    public static boolean isSpywarePackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.startsWith("ru.yandex")
                || p.startsWith("com.yandex")
                || p.startsWith("com.vkontakte")
                || p.startsWith("com.vk.im")
                || p.startsWith("com.vk.calls")
                || p.startsWith("ru.mail")
                || p.startsWith("com.my.mail")
                || p.startsWith("com.mail.my")
                || p.startsWith("ru.ok.android")
                || p.startsWith("ru.sberbank")
                || p.startsWith("com.sberbank")
                || p.startsWith("ru.tinkoff")
                || p.startsWith("ru.tcsbank")
                || p.startsWith("com.idamob.tinkoff")
                || p.startsWith("com.vtb")
                || p.startsWith("ru.vtb24")
                || p.startsWith("ru.alfabank")
                || p.startsWith("com.gazprombank")
                || p.startsWith("ru.raiffeisen")
                || p.startsWith("ru.sovcombank")
                || p.startsWith("ru.nspk")
                || p.startsWith("ru.gosuslugi")
                || p.startsWith("ru.mos")
                || p.startsWith("ru.ozon")
                || p.startsWith("com.wildberries")
                || p.startsWith("ru.avito")
                || p.startsWith("ru.rutube")
                || p.startsWith("ru.kinopoisk")
                || p.startsWith("ru.beeline")
                || p.startsWith("ru.mts")
                || p.startsWith("ru.megafon")
                || p.startsWith("ru.tele2")
                || p.startsWith("com.facebook")
                || p.startsWith("com.instagram")
                || p.startsWith("com.zhiliaoapp.musically")
                || p.startsWith("com.ss.android.ugc.trill")
                || p.startsWith("com.snapchat.android")
                || p.contains("analytics")
                || p.contains("tracker")
                || p.contains("telemetry");
    }

    public synchronized void resetAllPreferences() {
        prefs.edit().clear().apply();
        // Re-initialize deterministic client ID so account identity remains fresh & valid
        getClientId();
    }

    private String generateDeterministicClientId() {
        String androidId = null;
        try {
            androidId = Settings.Secure.getString(
                    appContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
        } catch (Exception ignored) {
            // Fallback handled below
        }

        StringBuilder seed = new StringBuilder("GoblinVPN:ClientId:v1:");
        if (androidId != null && !androidId.trim().isEmpty()) {
            seed.append(androidId.trim());
        } else {
            seed.append(appContext.getPackageName())
                    .append(':')
                    .append(Build.MANUFACTURER)
                    .append(':')
                    .append(Build.MODEL)
                    .append(':')
                    .append(Build.FINGERPRINT);
        }

        byte[] digest = sha256(seed.toString());
        StringBuilder digits = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            int val = digest[i % digest.length] & 0xFF;
            if (i == 0) {
                // Ensure leading digit is 1-9 for a clean 16-digit numeric ID
                digits.append((val % 9) + 1);
            } else {
                digits.append(val % 10);
            }
        }
        return digits.toString();
    }

    private static String computeShortHash(String input) {
        byte[] digest = sha256("GoblinVPN:SubKey:v1:" + input);
        StringBuilder hex = new StringBuilder(8);
        for (int i = 0; i < 4; i++) {
            hex.append(String.format(Locale.US, "%02X", digest[i] & 0xFF));
        }
        return hex.toString();
    }

    private static byte[] sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Deterministic fallback if SHA-256 were ever unavailable
            byte[] fallback = new byte[16];
            int h = text.hashCode();
            for (int i = 0; i < fallback.length; i++) {
                h = h * 31 + i + 17;
                fallback[i] = (byte) (h ^ (h >>> 8));
            }
            return fallback;
        }
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static List<String> parseLineList(String raw) {
        List<String> result = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            if (line != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static String joinLineList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (item != null) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(trimmed);
                }
            }
        }
        return sb.toString();
    }
}
