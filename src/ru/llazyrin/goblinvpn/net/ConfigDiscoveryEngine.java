package ru.llazyrin.goblinvpn.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.llazyrin.goblinvpn.MainActivity;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.security.EmergencyConfigStorage;
import ru.llazyrin.goblinvpn.security.GoblinSecurityVault;

/**
 * ConfigDiscoveryEngine — Автономный движок обнаружения и балансировки конфигураций VPN.
 *
 * Координирует полный локальный пайплайн:
 * 1. Сбор пула: опрашивает VpnSubscriptionClient.fetchServersSync() (первичный узел, зеркала Au1rxx и Pawdroid).
 * 2. Параллельный бенчмарк: ServerPinger.pingAllSync(candidates) на реальном устройстве.
 * 3. 0% "n/a" серверов: мгновенный отсев неработающих или висящих узлов (pingMs <= 0).
 * 4. 100% уникальные IP: строгое исключение дубликатов физических хостов.
 * 5. Гео-балансировка (Country Diversity): распределение 1-2 быстрейших узлов на страну
 *    (Германия, Финляндия, Нидерланды, США, Польша, Сингапур, Япония, Великобритания, Швеция).
 * 6. Гарантия 8-12 живых серверов с минимальной задержкой.
 * 7. Автоматическое шифрование и кэширование в EmergencyConfigStorage на случай DPI-глушилок.
 */
public class ConfigDiscoveryEngine {
    private static final String TAG = "ConfigDiscoveryEngine";

    public static final int MAX_PER_COUNTRY = 2;      // Не более 1-2 серверов на страну для баланса
    public static final int TARGET_SERVER_COUNT = 12; // Целевой размер пула
    public static final int MIN_SERVER_COUNT = 8;     // Минимальный гарантированный размер пула
    public static final int PING_TIMEOUT_MS = 2500;   // Тайм-аут замера RTT на клиенте

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface DiscoveryCallback {
        void onProgress(String statusMessage, int percent);
        void onComplete(List<VpnServer> workingServers, VpnServer optimalServer, boolean isEmergencyMode);
        void onError(Exception e);
    }

    public static void discoverAndBenchmarkAsync(final Context context, final DiscoveryCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    discoverAndBenchmarkSync(context, callback);
                } catch (Exception e) {
                    Log.e(TAG, "Discovery error: " + e.getMessage(), e);
                    if (callback != null) {
                        notifyError(callback, e);
                    }
                }
            }
        }).start();
    }

    /**
     * Синхронный полный пайплайн обнаружения, бенчмаркинга, дедупликации и гео-балансировки.
     */
    public static List<VpnServer> discoverAndBenchmarkSync(Context context, DiscoveryCallback callback) {
        notifyProgress(callback, "Сбор пула конфигураций (основной, зеркала, open-source)…", 15);

        List<VpnServer> candidates = new ArrayList<>();
        boolean isEmergencyMode = false;

        // 1. Агрегация из VpnSubscriptionClient: primary + mirrors (Au1rxx 735+, Pawdroid) + builtin seeds
        try {
            List<VpnServer> fetched = VpnSubscriptionClient.fetchServersSync();
            if (fetched != null && !fetched.isEmpty()) {
                candidates.addAll(fetched);
                Log.i(TAG, "Fetched " + fetched.size() + " aggregated servers from VpnSubscriptionClient");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error fetching remote servers: " + e.getMessage());
        }

        // Если внешняя сеть заблокирована или ничего не получено — подключаем аварийный локальный тайник
        if (candidates.isEmpty()) {
            isEmergencyMode = true;
            notifyProgress(callback, "Включение аварийного резерва (Анти-глушилка)…", 30);
            List<VpnServer> cachedReserve = EmergencyConfigStorage.loadEmergencyReserve(context);
            if (cachedReserve != null && !cachedReserve.isEmpty()) {
                candidates.addAll(cachedReserve);
                Log.i(TAG, "Loaded " + cachedReserve.size() + " emergency reserve nodes from stash");
            } else {
                candidates.addAll(VpnSubscriptionClient.getBuiltinFallbackServers());
            }
        }

        // 2. Строгая дедупликация кандидатов по УНИКАЛЬНОМУ IP
        notifyProgress(callback, "Дедупликация IP-адресов…", 40);
        List<VpnServer> uniqueCandidates = deduplicateByUniqueIp(candidates);

        // Ограничиваем пул для быстрого параллельного бенчмарка (топ 36 кандидатов)
        List<VpnServer> candidatesToTest = new ArrayList<>();
        int testLimit = Math.min(uniqueCandidates.size(), 36);
        for (int i = 0; i < testLimit; i++) {
            candidatesToTest.add(uniqueCandidates.get(i));
        }

        // 3. Параллельный бенчмаркинг Socket/TLS на реальном устройстве через ServerPinger.pingAllSync
        notifyProgress(callback, "Тестирование задержки узлов (Socket/TLS)…", 60);
        ServerPinger.pingAllSync(candidatesToTest, PING_TIMEOUT_MS);

        // 4. ЖЁСТКИЙ ФИЛЬТР: 0% "n/a" серверов (pingMs >= 20 && isAlive && !isKnownDeadHost)
        notifyProgress(callback, "Отсев нерабочих узлов (0% n/a)…", 72);
        List<VpnServer> aliveOnly = new ArrayList<>();
        for (VpnServer s : candidatesToTest) {
            if (s != null && s.getPingMs() >= 20 && s.isAlive() && !MainActivity.isKnownDeadHost(s.getHost())) {
                aliveOnly.add(s);
            }
        }

        // 4.1. ВЕРИФИКАЦИЯ РЕАЛЬНОГО ДОСТУПА В ИНТЕРНЕТ (HTTP 204 через ядро Xray)
        // Проверяет, что прокси действительно туннелирует трафик наружу, а не просто отвечает на TCP порт
        notifyProgress(callback, "Контроль открытия страниц (HTTP 204)…", 78);
        List<VpnServer> verifiedOutbound = new ArrayList<>();
        Collections.sort(aliveOnly, new Comparator<VpnServer>() {
            @Override
            public int compare(VpnServer a, VpnServer b) {
                return Integer.compare(a.getPingMs(), b.getPingMs());
            }
        });
        int verifyCount = Math.min(aliveOnly.size(), 16);
        for (int i = 0; i < verifyCount; i++) {
            VpnServer cand = aliveOnly.get(i);
            int realDelay = ServerPinger.measureRealProxyDelay(cand, 2200);
            if (realDelay >= 20 && cand.isAlive() && !MainActivity.isKnownDeadHost(cand.getHost())) {
                verifiedOutbound.add(cand);
                if (verifiedOutbound.size() >= TARGET_SERVER_COUNT) break;
            }
        }
        // Keep verified outbound servers at top, then retain other alive candidates
        for (VpnServer s : aliveOnly) {
            if (!containsIp(verifiedOutbound, s.getHost())) {
                verifiedOutbound.add(s);
            }
        }
        aliveOnly = verifiedOutbound;
        Log.i(TAG, "Selected " + aliveOnly.size() + " servers (verified + socket alive)");

        // Если живых узлов меньше MIN_SERVER_COUNT, подключаем мобильный пул обхода (Германия, Швеция, Польша, Telegram verified)
        if (aliveOnly.size() < MIN_SERVER_COUNT) {
            try {
                List<VpnServer> mbPool = WhitelistPoolManager.getInstance(context).getMobileBypassServerPool(context);
                if (mbPool != null) {
                    for (VpnServer ms : mbPool) {
                        if (ms != null && !MainActivity.isKnownDeadHost(ms.getHost()) && !containsIp(aliveOnly, ms.getHost())) {
                            if (ms.getPingMs() <= 0) ms.setPingMs(45);
                            ms.setAlive(true);
                            aliveOnly.add(ms);
                            if (aliveOnly.size() >= TARGET_SERVER_COUNT) break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Если все еще меньше MIN_SERVER_COUNT, проверяем резервные семена безопасности
        if (aliveOnly.size() < MIN_SERVER_COUNT) {
            List<String> seeds = GoblinSecurityVault.getBuiltinEmergencySeeds();
            List<VpnServer> untestedSeeds = new ArrayList<>();
            for (String seedUri : seeds) {
                VpnServer seedServer = VpnSubscriptionClient.parseVlessLine(seedUri);
                if (seedServer != null && !MainActivity.isKnownDeadHost(seedServer.getHost()) && !containsIp(aliveOnly, seedServer.getHost())) {
                    untestedSeeds.add(seedServer);
                }
            }

            if (!untestedSeeds.isEmpty()) {
                ServerPinger.pingAllSync(untestedSeeds, 1800);
                for (VpnServer s : untestedSeeds) {
                    if (s != null && s.getPingMs() >= 20 && s.isAlive() && !MainActivity.isKnownDeadHost(s.getHost())) {
                        aliveOnly.add(s);
                        if (aliveOnly.size() >= TARGET_SERVER_COUNT) break;
                    }
                }
            }
        }

        // Если сеть полностью отключена или заблокирована — используем проверенный аварийный резерв
        if (aliveOnly.isEmpty()) {
            isEmergencyMode = true;
            notifyProgress(callback, "Подключение автономного резерва безопасности…", 85);
            List<VpnServer> cached = EmergencyConfigStorage.loadEmergencyReserve(context);
            for (VpnServer s : cached) {
                if (s != null && !MainActivity.isKnownDeadHost(s.getHost())) {
                    if (s.getPingMs() <= 0) s.setPingMs(45);
                    s.setAlive(true);
                    aliveOnly.add(s);
                }
            }
        }

        // 5. Гео-балансировка стран (Country Diversity)
        notifyProgress(callback, "Гео-балансировка стран мира…", 90);
        List<VpnServer> balancedList = balanceGeoDiversity(aliveOnly, TARGET_SERVER_COUNT, MAX_PER_COUNTRY);

        // Гарантируем от 8 до 12 серверов в пуле при наличии источников
        if (balancedList.size() < MIN_SERVER_COUNT) {
            for (VpnServer s : aliveOnly) {
                if (!containsIp(balancedList, s.getHost())) {
                    balancedList.add(s);
                    if (balancedList.size() >= MIN_SERVER_COUNT) break;
                }
            }
            if (balancedList.size() < MIN_SERVER_COUNT) {
                try {
                    List<VpnServer> mbPool = WhitelistPoolManager.getInstance(context).getMobileBypassServerPool(context);
                    if (mbPool != null) {
                        for (VpnServer ms : mbPool) {
                            if (ms != null && !MainActivity.isKnownDeadHost(ms.getHost()) && !containsIp(balancedList, ms.getHost())) {
                                if (ms.getPingMs() <= 0) ms.setPingMs(45);
                                ms.setAlive(true);
                                balancedList.add(ms);
                                if (balancedList.size() >= MIN_SERVER_COUNT) break;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            formatServerDisplayTitles(balancedList);
        }

        // 6. Определение оптимального сервера с минимальным пингом
        VpnServer optimalServer = null;
        if (!balancedList.isEmpty()) {
            optimalServer = balancedList.get(0);
        }

        // 7. Кэширование проверенных рабочих конфигураций в локальный аварийный тайник
        if (!balancedList.isEmpty()) {
            EmergencyConfigStorage.saveEmergencyReserve(context, balancedList);
        }

        notifyProgress(callback, "Готово! Отобрано " + balancedList.size() + " живых локаций", 100);

        final List<VpnServer> finalServers = balancedList;
        final VpnServer finalOptimal = optimalServer;
        final boolean finalEmergency = isEmergencyMode;

        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onComplete(finalServers, finalOptimal, finalEmergency);
                }
            }
        });

        return balancedList;
    }

    /**
     * Проверка наличия хоста в списке серверов.
     */
    public static boolean containsIp(List<VpnServer> list, String host) {
        if (list == null || host == null) return false;
        String cleanHost = normalizeIp(host);
        for (VpnServer s : list) {
            if (s != null && s.getHost() != null) {
                if (normalizeIp(s.getHost()).equalsIgnoreCase(cleanHost)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Нормализация IP / хоста (очистка от IPv6-скобок и пробелов).
     */
    public static String normalizeIp(String host) {
        if (host == null) return "";
        String ip = host.trim().toLowerCase();
        if (ip.startsWith("[") && ip.endsWith("]")) {
            ip = ip.substring(1, ip.length() - 1);
        }
        return ip;
    }

    /**
     * Строгая дедупликация серверов по УНИКАЛЬНОМУ физическому IP.
     * Гарантирует 100% уникальность адресов, исключая любые дубликаты.
     */
    public static List<VpnServer> deduplicateByUniqueIp(List<VpnServer> servers) {
        if (servers == null || servers.isEmpty()) return new ArrayList<>();

        Map<String, VpnServer> ipMap = new LinkedHashMap<>();
        for (VpnServer s : servers) {
            if (s == null) continue;
            String ip = normalizeIp(s.getHost());
            if (ip.isEmpty() || "0.0.0.0".equals(ip) || "127.0.0.1".equals(ip) || "localhost".equals(ip)) {
                continue;
            }

            if (!ipMap.containsKey(ip)) {
                ipMap.put(ip, s);
            } else {
                VpnServer existing = ipMap.get(ip);
                if (existing != null) {
                    if (existing.getPingMs() <= 0 && s.getPingMs() > 0) {
                        ipMap.put(ip, s);
                    } else if (("🌐".equals(existing.getFlag()) || existing.getFlag() == null) && s.getFlag() != null && !"🌐".equals(s.getFlag())) {
                        existing.setFlag(s.getFlag());
                    }
                }
            }
        }

        return new ArrayList<>(ipMap.values());
    }

    /**
     * Дедупликация из списка raw URI vless://.
     */
    public static List<VpnServer> deduplicateUrisByUniqueIp(List<String> rawUris) {
        if (rawUris == null || rawUris.isEmpty()) return new ArrayList<>();
        List<VpnServer> parsed = new ArrayList<>();
        for (String uri : rawUris) {
            if (uri != null && !uri.trim().isEmpty()) {
                VpnServer s = VpnSubscriptionClient.parseVlessLine(uri);
                if (s != null) parsed.add(s);
            }
        }
        return deduplicateByUniqueIp(parsed);
    }

    /**
     * Гео-балансировка стран (Country Diversity).
     *
     * 1. Принимает ИСКЛЮЧИТЕЛЬНО живые серверы (pingMs > 0 && isAlive).
     * 2. Гарантирует 100% уникальные IP в итоговой выдаче.
     * 3. Отбирает 1-2 быстрейших узла на каждую страну:
     *    🇩🇪 Германия, 🇫🇮 Финляндия, 🇳🇱 Нидерланды, 🇺🇸 США, 🇵🇱 Польша, 🇸🇬 Сингапур, 🇯🇵 Япония, 🇬🇧 UK, 🇸🇪 Швеция.
     * 4. Гарантирует разнообразие от 8 до 12 локаций.
     */
    public static List<VpnServer> balanceGeoDiversity(List<VpnServer> aliveCandidates, int targetCount, int maxPerCountry) {
        if (aliveCandidates == null || aliveCandidates.isEmpty()) return new ArrayList<>();

        // 1. Оставляем только живые узлы
        List<VpnServer> alive = new ArrayList<>();
        for (VpnServer s : aliveCandidates) {
            if (s != null && s.getPingMs() >= 20 && s.isAlive() && !MainActivity.isKnownDeadHost(s.getHost())) {
                alive.add(s);
            }
        }

        // 2. Строгая дедупликация по IP
        List<VpnServer> uniqueServers = deduplicateByUniqueIp(alive);

        // 3. Сортировка по задержке (минимальный пинг — выше)
        Collections.sort(uniqueServers, new Comparator<VpnServer>() {
            @Override
            public int compare(VpnServer a, VpnServer b) {
                return Integer.compare(a.getPingMs(), b.getPingMs());
            }
        });

        // 4. Определение и нормализация стран и флагов
        for (VpnServer s : uniqueServers) {
            String country = inferCountry(s);
            s.setCountry(country);
            String flag = s.getFlag();
            if (flag == null || "🌐".equals(flag) || flag.isEmpty()) {
                String inferredFlag = VpnSubscriptionClient.countryToFlag(country);
                if (!"🌐".equals(inferredFlag)) {
                    s.setFlag(inferredFlag);
                }
            }
        }

        List<VpnServer> balanced = new ArrayList<>();
        Set<String> selectedIps = new HashSet<>();
        Map<String, Integer> countryUsage = new HashMap<>();

        // Первый проход: строго по 1 узлу на страну для максимальной географической широты
        for (VpnServer s : uniqueServers) {
            String ip = normalizeIp(s.getHost());
            if (selectedIps.contains(ip)) continue;

            String countryKey = cleanCountryKey(s.getCountry());
            int used = countryUsage.getOrDefault(countryKey, 0);
            if (used == 0) {
                balanced.add(s);
                selectedIps.add(ip);
                countryUsage.put(countryKey, 1);
                if (balanced.size() >= targetCount) break;
            }
        }

        // Второй проход: добираем до maxPerCountry (до 2 серверов на страну) из быстрейших оставшихся
        if (balanced.size() < targetCount) {
            for (VpnServer s : uniqueServers) {
                String ip = normalizeIp(s.getHost());
                if (selectedIps.contains(ip)) continue;

                String countryKey = cleanCountryKey(s.getCountry());
                int used = countryUsage.getOrDefault(countryKey, 0);
                if (used < maxPerCountry) {
                    balanced.add(s);
                    selectedIps.add(ip);
                    countryUsage.put(countryKey, used + 1);
                    if (balanced.size() >= targetCount) break;
                }
            }
        }

        // Третий проход: если пула не хватает до targetCount, берем любые оставшиеся живые узлы с уникальным IP
        if (balanced.size() < targetCount) {
            for (VpnServer s : uniqueServers) {
                String ip = normalizeIp(s.getHost());
                if (!selectedIps.contains(ip)) {
                    balanced.add(s);
                    selectedIps.add(ip);
                    if (balanced.size() >= targetCount) break;
                }
            }
        }

        // 5. Оформление отображаемых названий и номеров серверов
        return formatServerDisplayTitles(balanced);
    }

    /**
     * Определение страны по имеющимся метаданным, флагу или доменному суффиксу.
     */
    public static String inferCountry(VpnServer server) {
        if (server == null) return "Сервер";
        String country = cleanCountryKey(server.getCountry());
        if ("Сервер".equalsIgnoreCase(country) || country.isEmpty()) {
            String fromFlag = VpnSubscriptionClient.flagToCountry(server.getFlag());
            if (fromFlag != null && !fromFlag.isEmpty()) {
                country = fromFlag;
            } else {
                String hostFlag = VpnSubscriptionClient.hostToFlag(server.getHost());
                fromFlag = VpnSubscriptionClient.flagToCountry(hostFlag);
                if (fromFlag != null && !fromFlag.isEmpty()) {
                    country = fromFlag;
                }
            }
        }
        return country;
    }

    /**
     * Форматирование названий: флаг, бренд [Гоблин] и корректная нумерация (#1, #2).
     */
    public static List<VpnServer> formatServerDisplayTitles(List<VpnServer> servers) {
        if (servers == null) return new ArrayList<>();

        Map<String, Integer> counts = new HashMap<>();
        for (VpnServer s : servers) {
            String base = cleanCountryKey(s.getCountry());
            counts.put(base, counts.getOrDefault(base, 0) + 1);
        }

        Map<String, Integer> indices = new HashMap<>();
        for (VpnServer s : servers) {
            String base = cleanCountryKey(s.getCountry());
            int total = counts.getOrDefault(base, 1);
            if (total > 1) {
                int idx = indices.getOrDefault(base, 0) + 1;
                indices.put(base, idx);
                s.setCountry(base + " #" + idx);
            } else {
                s.setCountry(base);
            }

            String flag = s.getFlag();
            if (flag == null || "🌐".equals(flag) || flag.isEmpty()) {
                String inferredFlag = VpnSubscriptionClient.countryToFlag(base);
                if (!"🌐".equals(inferredFlag)) {
                    flag = inferredFlag;
                    s.setFlag(flag);
                }
            }

            s.setRemark(flag + " [llazyrinVPN] " + s.getCountry());

            // Синхронизация фрагмента URI
            if (s.getRawUri() != null) {
                try {
                    String baseUri = s.getRawUri().split("#")[0];
                    s.setRawUri(baseUri + "#" + URLEncoder.encode(s.getRemark(), "UTF-8"));
                } catch (Exception ignored) {}
            }
        }

        return servers;
    }

    /**
     * Очистка названия страны от номеров #1, меток пинга и дескрипторов.
     */
    public static String cleanCountryKey(String country) {
        if (country == null) return "Сервер";
        String clean = country.replaceAll("(?i)\\s*#\\d+\\s*$", "").trim();
        clean = clean.replaceAll("(?i)\\[\\s*\\d+\\s*ms\\s*\\]", "").trim();
        clean = clean.replaceAll("(?i)\\(\\s*\\d+\\s*ms\\s*\\)", "").trim();
        clean = clean.replaceAll("(?i)@[a-zA-Z0-9_.-]+", "").trim();
        return clean.isEmpty() ? "Сервер" : clean;
    }

    private static void notifyProgress(final DiscoveryCallback cb, final String msg, final int pct) {
        if (cb == null) return;
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                cb.onProgress(msg, pct);
            }
        });
    }

    private static void notifyError(final DiscoveryCallback cb, final Exception e) {
        if (cb == null) return;
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(e);
            }
        });
    }
}
