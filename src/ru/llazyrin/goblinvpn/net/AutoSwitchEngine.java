package ru.llazyrin.goblinvpn.net;

import java.util.Set;
import java.util.HashSet;
import java.util.Locale;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.llazyrin.goblinvpn.MainActivity;
import ru.llazyrin.goblinvpn.model.NetworkPreferences;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.security.EmergencyConfigStorage;
import ru.llazyrin.goblinvpn.security.MobileProvenConfigStorage;
import ru.llazyrin.goblinvpn.service.AppLogger;
import ru.llazyrin.goblinvpn.service.GoblinVpnService;
import ru.llazyrin.goblinvpn.service.VpnState;

/**
 * AutoSwitchEngine — Интеллектуальный движок фонового мониторинга, бесшовного автопереключения
 * и обхода сотовых блокировок (БС / ТСПУ / White IP).
 *
 * Архитектура v2.6.4:
 * 1. Мгновенная реакция на смену сети (Wi-Fi -> Сотовая связь):
 *    Слушает события ConnectivityManager.NetworkCallback. При переходе на сотовую сеть
 *    моментально сбрасывает европейский пул и переключает туннель на мобильный пул обхода глушилок.
 * 2. Ротационный перебор ВСЕХ 54+ конфигов обхода (Full-Pool Sliding Cursor):
 *    Перебирает конфиги скользящими батчами по 8 серверов с таймаутом 1500 мс.
 *    Не ограничивается первыми 16 серверами, а проходит весь список до нахождения рабочего.
 * 3. Кэш штрафов/cooldown для упавших узлов (Failed Server Cooldown, 4 минуты):
 *    Узлы, на которых вышка сбросила сессию, временно исключаются из подбора,
 *    давая шанс другим альтернативным серверам.
 * 4. Защита от 3-минутного сброса (Cellular NAT Keep-Alive):
 *    Каждые 20 секунд отправляет легковесный проверочный пинг через туннель для
 *    поддержания состояния NAT-таблицы у сотового оператора (GGSN/UPF).
 * 5. Непрерывное автоисцеление (Continuous Auto-Healing):
 *    При неожиданном падении туннеля или выходе Xray-core моментально подставляет
 *    предпроверенный сервер из горячего пула (Hot Standby) или запускает экстренный перебор.
 */
public class AutoSwitchEngine {
    private static final String TAG = "AutoSwitchEngine";

    public static final long BENCHMARK_INTERVAL_SECONDS = 120L; // 2 minutes
    private static final long HEALTH_CHECK_INTERVAL_SECONDS = 6L; // Check every 6 seconds
    private static final long KEEP_ALIVE_INTERVAL_SECONDS = 20L; // 20s NAT keepalive
    private static final long BASE_SWITCH_COOLDOWN_MS = 30000L; // 30s base cooldown after switch
    private static final long MAX_SWITCH_COOLDOWN_MS = 300000L; // 5 min max backoff cap
    private static final long HEALTHY_STABILITY_WINDOW_MS = 120000L; // 2 min stable resets backoff
    private static final long DATA_FLOW_IMMUNITY_BYTES = 8192L; // 8 KB rx delta protects active connection
    private static final long FAILED_SERVER_COOLDOWN_MS = 240000L; // 4 minutes
    private static final int STANDBY_POOL_SIZE = 3;
    private static final int BATCH_TEST_SIZE = 5;
    public static final int CELLULAR_WAVE_BATCH_SIZE = 5;
    public static final int CELLULAR_WAVE_TIMEOUT_MS = 2500;
    public static final int CELLULAR_PRIMARY_WAVE_LIMIT = 20; // 4 waves of 5 servers = 20 candidates

    public enum NetworkProfile {
        WIFI("Wi-Fi", 250, 300),
        LTE_4G("4G/LTE", 280, 150),
        CELLULAR_3G("3G/2G", 450, 50),
        UNKNOWN("Default", 280, 150);

        public final String name;
        public final int maxPingMs;
        public final int minSpeedKbps;

        NetworkProfile(String name, int maxPingMs, int minSpeedKbps) {
            this.name = name;
            this.maxPingMs = maxPingMs;
            this.minSpeedKbps = minSpeedKbps;
        }
    }

    private static volatile AutoSwitchEngine instance = null;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
        private int count = 0;
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "GoblinAutoSwitchWorker-" + (++count));
            t.setDaemon(true);
            return t;
        }
    });

    private final ScheduledExecutorService handoverExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "GoblinHandoverWorker");
            t.setDaemon(true);
            return t;
        }
    });

    private final java.util.concurrent.ExecutorService benchmarkWorkerPool = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private int count = 0;
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "GoblinBenchmarkWorker-" + (++count));
            t.setDaemon(true);
            return t;
        }
    });

    private final AtomicBoolean isWifiHandoverPending = new AtomicBoolean(false);

    private final List<VpnServer> candidateServers = new CopyOnWriteArrayList<>();
    private final List<VpnServer> standbyPool = new CopyOnWriteArrayList<>();
    private final List<VpnServer> verifiedCellularQueue = new CopyOnWriteArrayList<>();
    private final Map<String, Long> failedServerCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Integer> serverFailureCounts = new ConcurrentHashMap<>();
    private final List<String> recentSwitchedServerKeys = Collections.synchronizedList(new ArrayList<String>());
    private static final int MAX_RECENT_HISTORY = 4;
    private final java.util.concurrent.atomic.AtomicInteger initialFailoverAttempts = new java.util.concurrent.atomic.AtomicInteger(0);
    private final Set<String> sessionHandshakeFailedKeys = Collections.synchronizedSet(new HashSet<String>());
    private volatile boolean isAppInForeground = true;

    public void setAppForeground(boolean foreground) {
        this.isAppInForeground = foreground;
    }

    public boolean isAppForeground() {
        return this.isAppInForeground;
    }

    private ScheduledFuture<?> benchmarkSchedule = null;
    private ScheduledFuture<?> healthSchedule = null;
    private ScheduledFuture<?> keepAliveSchedule = null;

    private volatile VpnServer currentConnectedServer = null;
    private volatile boolean isRunning = false;
    private final AtomicBoolean isBenchmarking = new AtomicBoolean(false);
    private final AtomicBoolean isEmergencySweeping = new AtomicBoolean(false);

    private long lastSwitchTimestampMs = 0;
    private int consecutiveSwitchCount = 0;
    private long currentSwitchCooldownMs = BASE_SWITCH_COOLDOWN_MS;
    private int consecutiveDegradationCount = 0;
    private int consecutiveDropCount = 0;

    private long lastTrafficRxBytes = 0;
    private long lastTrafficTimestampMs = 0;

    private int mobilePoolCursor = 0;
    private volatile boolean lastWasCellular = false;
    private long lastNetworkHandoverMs = 0;
    private ConnectivityManager.NetworkCallback networkCallback = null;
    private ConnectivityManager.NetworkCallback defaultNetworkCallback = null;

    public static AutoSwitchEngine getInstance(Context context) {
        if (instance == null) {
            synchronized (AutoSwitchEngine.class) {
                if (instance == null) {
                    instance = new AutoSwitchEngine(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private AutoSwitchEngine(Context context) {
        this.appContext = context;
        loadInitialCandidates();
        registerNetworkListener();
    }

    public void recordServerFailure(String serverKey) {
        if (serverKey == null || serverKey.trim().isEmpty()) return;
        int count = 1;
        Integer prev = serverFailureCounts.get(serverKey);
        if (prev != null) count = prev + 1;
        serverFailureCounts.put(serverKey, count);

        boolean isProven = MobileProvenConfigStorage.getInstance(appContext).isProven(serverKey);
        long duration;
        if (isProven) {
            // Proven cellular nodes get significantly shorter cooldown (20s on fail #1)
            duration = (count == 1) ? 20_000L : ((count == 2) ? 60_000L : 180_000L);
        } else {
            duration = FAILED_SERVER_COOLDOWN_MS;
            if (count == 2) duration = 600000L; // 10 min
            else if (count >= 3) duration = 1800000L; // 30 min
        }

        failedServerCooldowns.put(serverKey, SystemClock.elapsedRealtime() + duration);
        ServerPenaltyManager.getInstance(appContext).recordFailure(serverKey);
        AppLogger.i(TAG, "Node penalty: " + serverKey + " failure #" + count + ", cooldown=" + (duration / 1000) + "s (isProven=" + isProven + ")");
    }

    public boolean isServerInCooldown(String serverKey) {
        if (serverKey == null || serverKey.trim().isEmpty()) return false;
        if (ServerPenaltyManager.getInstance(appContext).isPenalized(serverKey)) {
            return true;
        }
        Long expireAt = failedServerCooldowns.get(serverKey);
        if (expireAt == null) return false;
        if (SystemClock.elapsedRealtime() < expireAt) {
            return true;
        }
        failedServerCooldowns.remove(serverKey);
        return false;
    }

    private void recordSwitchedServer(String serverKey) {
        if (serverKey == null) return;
        synchronized (recentSwitchedServerKeys) {
            recentSwitchedServerKeys.remove(serverKey);
            recentSwitchedServerKeys.add(0, serverKey);
            while (recentSwitchedServerKeys.size() > MAX_RECENT_HISTORY) {
                recentSwitchedServerKeys.remove(recentSwitchedServerKeys.size() - 1);
            }
        }
    }

    private boolean isRecentlySwitched(String serverKey) {
        if (serverKey == null) return false;
        synchronized (recentSwitchedServerKeys) {
            return recentSwitchedServerKeys.contains(serverKey);
        }
    }

    private VpnServer pickNextEligibleCandidate(VpnServer failedServer) {
        boolean isCellular = WhitelistPoolManager.isRunningOnCellular(appContext);
        List<VpnServer> pool = null;
        if (isCellular) {
            pool = WhitelistPoolManager.getInstance(appContext).getMobileBypassServerPool(appContext);
        } else {
            try {
                List<VpnServer> commTop = CommunityPoolManager.getInstance(appContext).getTopCommunityServers();
                if (commTop != null && !commTop.isEmpty()) {
                    pool = new ArrayList<>(commTop);
                }
            } catch (Throwable ignored) {}
        }
        if (pool == null || pool.isEmpty()) {
            pool = candidateServers;
        }
        if (pool == null || pool.isEmpty()) {
            return null;
        }

        String failedKey = failedServer != null ? failedServer.getServerKey() : "";

        // First pass: not in cooldown, not dead, not recently switched, not in sessionHandshakeFailedKeys
        VpnServer bestPass1 = null;
        for (VpnServer s : pool) {
            if (s == null) continue;
            if (isCellular && ("193.233.199.233".equals(s.getHost()) || !WhitelistPoolManager.getInstance(appContext).isWhitelistedForMobile(s.getHost()))) continue;
            String key = s.getServerKey();
            if (key.equalsIgnoreCase(failedKey)) continue;
            if (MainActivity.isKnownDeadHost(s.getHost())) continue;
            if (sessionHandshakeFailedKeys.contains(key.toLowerCase(Locale.ROOT))) continue;
            if (isServerInCooldown(key)) continue;
            if (isRecentlySwitched(key)) continue;

            if (s.getPingMs() > 0 && s.isAlive()) {
                if (bestPass1 == null || bestPass1.getPingMs() <= 0 || s.getPingMs() < bestPass1.getPingMs()) {
                    bestPass1 = s;
                }
            } else if (bestPass1 == null) {
                bestPass1 = s;
            }
        }
        if (bestPass1 != null) {
            AppLogger.i(TAG, "pickNextEligibleCandidate (Pass 1) -> " + bestPass1.getDisplayName()
                    + " [" + bestPass1.getHost() + ":" + bestPass1.getPort() + "] (SNI=" + bestPass1.getSni()
                    + ", Tier=" + bestPass1.getMobileTier() + ", ping=" + bestPass1.getPingMs() + "ms)");
            return bestPass1;
        }

        // Second pass: not in cooldown, not in sessionHandshakeFailedKeys, ignoring recent switch history
        for (VpnServer s : pool) {
            if (s == null) continue;
            if (isCellular && ("193.233.199.233".equals(s.getHost()) || !WhitelistPoolManager.getInstance(appContext).isWhitelistedForMobile(s.getHost()))) continue;
            String key = s.getServerKey();
            if (key.equalsIgnoreCase(failedKey)) continue;
            if (MainActivity.isKnownDeadHost(s.getHost())) continue;
            if (sessionHandshakeFailedKeys.contains(key.toLowerCase(Locale.ROOT))) continue;
            if (isServerInCooldown(key)) continue;
            AppLogger.i(TAG, "pickNextEligibleCandidate (Pass 2) -> " + s.getDisplayName()
                    + " [" + s.getHost() + ":" + s.getPort() + "] (SNI=" + s.getSni() + ", Tier=" + s.getMobileTier() + ")");
            return s;
        }

        // Third pass: not matching failed server, not in sessionHandshakeFailedKeys
        for (VpnServer s : pool) {
            if (s == null) continue;
            if (isCellular && ("193.233.199.233".equals(s.getHost()) || !WhitelistPoolManager.getInstance(appContext).isWhitelistedForMobile(s.getHost()))) continue;
            String key = s.getServerKey();
            if (key.equalsIgnoreCase(failedKey)) continue;
            if (MainActivity.isKnownDeadHost(s.getHost())) continue;
            if (sessionHandshakeFailedKeys.contains(key.toLowerCase(Locale.ROOT))) continue;
            AppLogger.i(TAG, "pickNextEligibleCandidate (Pass 3) -> " + s.getDisplayName()
                    + " [" + s.getHost() + ":" + s.getPort() + "] (SNI=" + s.getSni() + ", Tier=" + s.getMobileTier() + ")");
            return s;
        }

        // Fourth pass: all candidates in pool have been tried this session. Reset session exclusions to restart fresh rotation!
        AppLogger.i(TAG, "⚡ All candidates in pool were tried this session. Resetting session history to restart fresh full-cycle rotation.");
        sessionHandshakeFailedKeys.clear();
        failedServerCooldowns.clear();
        for (VpnServer s : pool) {
            if (s == null) continue;
            if (isCellular && ("193.233.199.233".equals(s.getHost()) || !WhitelistPoolManager.getInstance(appContext).isWhitelistedForMobile(s.getHost()))) continue;
            String key = s.getServerKey();
            if (key.equalsIgnoreCase(failedKey)) continue;
            if (MainActivity.isKnownDeadHost(s.getHost())) continue;
            AppLogger.i(TAG, "pickNextEligibleCandidate (Restart Cycle) -> " + s.getDisplayName()
                    + " [" + s.getHost() + ":" + s.getPort() + "] (SNI=" + s.getSni() + ", Tier=" + s.getMobileTier() + ")");
            return s;
        }
        return null;
    }

    /**
     * Вызывается службой GoblinVpnService при неудаче первичного рукопожатия
     * (Initial Handshake Watchdog: 0 байт трафика или таймаут за 3.5 секунды).
     * Моментально выполняет авто-переход на следующий лучший узел без ожидания кулдауна.
     */
    /**
     * Вызывается службой GoblinVpnService при успешном подтверждении первичного рукопожатия.
     * Сбрасывает счетчики сбоев и историю неудавшихся попыток текущей сессии.
     */
    public synchronized void notifyHandshakeSucceeded(VpnServer server) {
        initialFailoverAttempts.set(0);
        sessionHandshakeFailedKeys.clear();
        currentConnectedServer = server;
        AppLogger.i(TAG, "✅ AutoSwitchEngine: Handshake verified on "
                + (server != null ? server.getDisplayName() : "node") + ". Failover attempts reset.");
    }

    /**
     * User-initiated connection: clears previous temporary failure states, session exclusions,
     * and server penalties so fresh connection attempt is never starved.
     */
    public interface WaveProgressListener {
        void onWaveProgress(int waveIndex, int totalWaves, int batchSize);
    }

    public synchronized void notifyUserInitiatedConnect() {
        initialFailoverAttempts.set(0);
        sessionHandshakeFailedKeys.clear();
        recentSwitchedServerKeys.clear();
        failedServerCooldowns.clear();
        serverFailureCounts.clear();
        verifiedCellularQueue.clear();
        try {
            ServerPenaltyManager.getInstance(appContext).resetAll();
        } catch (Throwable ignored) {}
        AppLogger.i(TAG, "⚡ User initiated connect: reset failover counters, queue, and penalties.");
    }

    /**
     * Checks whether a verified working server is already queued from an earlier 5-server wave.
     * Non-blocking; returns null if wave discovery should run.
     */
    /**
     * Non-blocking fallback selector for non-cellular routes when intent had null EXTRA_SERVER.
     */
    public VpnServer selectFallbackNonCellularServer() {
        while (!standbyPool.isEmpty()) {
            VpnServer cand = standbyPool.remove(0);
            if (cand != null && !MainActivity.isKnownDeadHost(cand.getHost())) {
                return cand;
            }
        }
        try {
            List<VpnServer> comm = CommunityPoolManager.getInstance(appContext).getTopCommunityServers();
            if (comm != null && !comm.isEmpty()) {
                for (VpnServer s : comm) {
                    if (s != null && !MainActivity.isKnownDeadHost(s.getHost())) return s;
                }
            }
        } catch (Throwable ignored) {}
        return pickNextEligibleCandidate(null);
    }

    public VpnServer peekVerifiedCellularServer() {
        for (VpnServer s : verifiedCellularQueue) {
            if (s != null && s.getHost() != null
                    && !sessionHandshakeFailedKeys.contains(s.getServerKey().toLowerCase(Locale.ROOT))
                    && !MainActivity.isKnownDeadHost(s.getHost())
                    && !WhitelistPoolManager.isBlockedMobileHost(s.getHost())
                    && !WhitelistPoolManager.isBlockedMobileSni(s.getSni())
                    && !isServerInCooldown(s.getServerKey())
                    && !ServerPenaltyManager.getInstance(appContext).isPenalized(s.getServerKey())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Pops and returns the next fastest pre-verified server (#2 -> #3 -> ...) from the queue.
     */
    public synchronized VpnServer popVerifiedCellularServer() {
        while (!verifiedCellularQueue.isEmpty()) {
            VpnServer s = verifiedCellularQueue.remove(0);
            if (s == null || s.getHost() == null) continue;
            String k = s.getServerKey().toLowerCase(Locale.ROOT);
            if (sessionHandshakeFailedKeys.contains(k)
                    || MainActivity.isKnownDeadHost(s.getHost())
                    || WhitelistPoolManager.isBlockedMobileHost(s.getHost())
                    || WhitelistPoolManager.isBlockedMobileSni(s.getSni())
                    || isServerInCooldown(s.getServerKey())
                    || ServerPenaltyManager.getInstance(appContext).isPenalized(s.getServerKey())) {
                continue;
            }
            AppLogger.i(TAG, "⚡ [Wave Queue] Instant #next switch (0ms ping): " + s.getDisplayName()
                    + " [" + s.getHost() + ":" + s.getPort() + "] (SNI=" + s.getSni() + ", ping=" + s.getPingMs() + "ms)");
            return s;
        }
        return null;
    }

    private void addUniqueCellularCandidate(VpnServer s, List<VpnServer> dest, Set<String> seenKeys, Set<String> excludedLowerKeys) {
        if (s == null || s.getHost() == null) return;
        String host = s.getHost();
        if ("193.233.199.233".equals(host)
                || MainActivity.isKnownDeadHost(host)
                || WhitelistPoolManager.isBlockedMobileHost(host)
                || WhitelistPoolManager.isBlockedMobileSni(s.getSni())) {
            return;
        }
        if (!WhitelistPoolManager.getInstance(appContext).isServerWhitelistedOrTagged(s)) {
            return;
        }
        String keyLower = s.getServerKey().toLowerCase(Locale.ROOT);
        if (excludedLowerKeys != null && excludedLowerKeys.contains(keyLower)) {
            return;
        }
        if (seenKeys.add(keyLower)) {
            dest.add(s);
        }
    }

    /**
     * Assembles an ordered, deduplicated list of cellular bypass candidates:
     * 1) Proven cellular servers on this device
     * 2) Whitelisted community top servers
     * 3) Verified 3-tier mobile bypass pool
     */
    public List<VpnServer> assembleCellularCandidateOrder(Set<String> extraExcluded) {
        List<VpnServer> preferred = new ArrayList<>();
        List<VpnServer> penalizedTail = new ArrayList<>();
        List<VpnServer> allRaw = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Set<String> excluded = new HashSet<>();
        synchronized (sessionHandshakeFailedKeys) {
            excluded.addAll(sessionHandshakeFailedKeys);
        }
        if (extraExcluded != null) {
            for (String ek : extraExcluded) {
                if (ek != null) excluded.add(ek.toLowerCase(Locale.ROOT));
            }
        }

        // 1. MobileProvenConfigStorage (previously working on this device)
        try {
            List<VpnServer> proven = MobileProvenConfigStorage.getInstance(appContext).getProvenMobileServers();
            if (proven != null) {
                for (VpnServer ps : proven) addUniqueCellularCandidate(ps, allRaw, seen, excluded);
            }
        } catch (Throwable ignored) {}

        // 2. Community top servers matching mobile whitelist
        try {
            List<VpnServer> comm = CommunityPoolManager.getInstance(appContext).getTopCommunityServers();
            if (comm != null) {
                for (VpnServer cs : comm) addUniqueCellularCandidate(cs, allRaw, seen, excluded);
            }
        } catch (Throwable ignored) {}

        // 3. 3-Tier Mobile Bypass Pool
        try {
            List<VpnServer> bypass = WhitelistPoolManager.getInstance(appContext).getMobileBypassServerPool(appContext);
            if (bypass != null) {
                for (VpnServer bs : bypass) addUniqueCellularCandidate(bs, allRaw, seen, excluded);
            }
        } catch (Throwable ignored) {}

        for (VpnServer s : allRaw) {
            if (!isServerInCooldown(s.getServerKey()) && !ServerPenaltyManager.getInstance(appContext).isPenalized(s.getServerKey())) {
                preferred.add(s);
            } else {
                penalizedTail.add(s);
            }
        }
        if (preferred.isEmpty() && !penalizedTail.isEmpty()) {
            preferred.addAll(penalizedTail);
        }
        return preferred;
    }

    /**
     * Selects the fastest working cellular bypass node using parallel 5-server waves via Telegram / In-Tunnel API:
     * - First returns any pre-probed #2/#3 server waiting in verifiedCellularQueue (0 ms delay).
     * - Otherwise probes candidates in waves of 5 concurrently (up to 4 primary waves = 20 servers, continuing if needed).
     * - Within a successful wave, picks the fastest #1 node AND queues #2, #3... in verifiedCellularQueue for instant failover!
     */
    public VpnServer selectCellularServerByWaves(Set<String> extraExcluded, WaveProgressListener listener, AtomicBoolean cancelFlag) {
        // 0. Check pre-verified queue from previous wave (#2 -> #3 -> ...)
        VpnServer queued = popVerifiedCellularServer();
        if (queued != null) {
            return queued;
        }

        List<VpnServer> candidates = assembleCellularCandidateOrder(extraExcluded);
        if (candidates.isEmpty()) {
            // Reset session exclusions if everything in pool has already been tried
            AppLogger.i(TAG, "⚡ All cellular candidates excluded this session; resetting exclusions for fresh wave sweep.");
            sessionHandshakeFailedKeys.clear();
            failedServerCooldowns.clear();
            candidates = assembleCellularCandidateOrder(extraExcluded);
        }
        if (candidates.isEmpty()) {
            return null;
        }

        int totalPrimary = Math.min(candidates.size(), CELLULAR_PRIMARY_WAVE_LIMIT);
        int totalWaves = Math.max(1, (totalPrimary + CELLULAR_WAVE_BATCH_SIZE - 1) / CELLULAR_WAVE_BATCH_SIZE);

        // Run up to totalPrimary in batches of 5 (then overflow if all primary failed)
        int maxScan = candidates.size();
        for (int offset = 0; offset < maxScan; offset += CELLULAR_WAVE_BATCH_SIZE) {
            if ((cancelFlag != null && cancelFlag.get()) || GoblinVpnService.isExplicitUserDisconnect) {
                AppLogger.i(TAG, "⚡ Wave selection aborted (user disconnected or superseded).");
                return null;
            }

            int end = Math.min(offset + CELLULAR_WAVE_BATCH_SIZE, maxScan);
            List<VpnServer> wave = new ArrayList<>(candidates.subList(offset, end));
            int waveIdx = (offset / CELLULAR_WAVE_BATCH_SIZE) + 1;
            int displayTotal = (offset < totalPrimary) ? totalWaves : Math.max(waveIdx, (maxScan + CELLULAR_WAVE_BATCH_SIZE - 1) / CELLULAR_WAVE_BATCH_SIZE);

            if (listener != null) {
                try {
                    listener.onWaveProgress(waveIdx, displayTotal, wave.size());
                } catch (Throwable ignored) {}
            }

            StringBuilder targetsDesc = new StringBuilder();
            for (int i = 0; i < wave.size(); i++) {
                VpnServer w = wave.get(i);
                if (i > 0) targetsDesc.append(", ");
                targetsDesc.append(w.getHost()).append(":").append(w.getPort()).append("(").append(w.getSni()).append(")");
            }
            AppLogger.i(TAG, "📡 [Wave " + waveIdx + "/" + displayTotal + "] Pinging batch of " + wave.size()
                    + " cellular nodes via Telegram API: [" + targetsDesc.toString() + "]");

            List<VpnServer> waveAlive = ServerPinger.benchmarkBatchSync(wave, CELLULAR_WAVE_TIMEOUT_MS);

            if ((cancelFlag != null && cancelFlag.get()) || GoblinVpnService.isExplicitUserDisconnect) {
                return null;
            }

            if (!waveAlive.isEmpty()) {
                VpnServer winner = waveAlive.get(0);
                // Enqueue #2, #3... in ascending speed order for instant 0ms failover
                verifiedCellularQueue.clear();
                standbyPool.clear();
                for (int i = 1; i < waveAlive.size(); i++) {
                    VpnServer backup = waveAlive.get(i);
                    verifiedCellularQueue.add(backup);
                    if (standbyPool.size() < STANDBY_POOL_SIZE) {
                        standbyPool.add(backup);
                    }
                }
                StringBuilder winDesc = new StringBuilder();
                for (int i = 0; i < waveAlive.size(); i++) {
                    if (i > 0) winDesc.append(", ");
                    VpnServer a = waveAlive.get(i);
                    winDesc.append("#").append(i + 1).append(" ").append(a.getHost()).append("(").append(a.getPingMs()).append("ms)");
                }
                AppLogger.i(TAG, "✅ [Wave " + waveIdx + "/" + displayTotal + "] Alive: " + waveAlive.size() + "/" + wave.size()
                        + " -> Selected Winner: " + winner.getDisplayName() + " [" + winDesc.toString() + "]");
                return winner;
            }

            // Record cooldown/session skip for the 5 failed nodes in this wave so failover advances
            for (VpnServer dead : wave) {
                if (dead != null) {
                    sessionHandshakeFailedKeys.add(dead.getServerKey().toLowerCase(Locale.ROOT));
                    failedServerCooldowns.put(dead.getServerKey(), SystemClock.elapsedRealtime() + 60_000L);
                }
            }
            AppLogger.w(TAG, "⚠️ [Wave " + waveIdx + "/" + displayTotal + "] 0/" + wave.size()
                    + " responded; advancing immediately to next batch of 5...");
        }

        // All waves failed probe — emergency fallback to top unprobed candidate
        VpnServer fallback = candidates.get(0);
        AppLogger.w(TAG, "⚠️ All waves returned 0 alive via preflight; falling back to top candidate: "
                + fallback.getDisplayName() + " [" + fallback.getHost() + "]");
        return fallback;
    }

    public synchronized void onInitialConnectFailed(VpnServer failedServer) {
        int attempt = initialFailoverAttempts.incrementAndGet();
        boolean isCellular = WhitelistPoolManager.isRunningOnCellular(appContext);

        if (failedServer != null) {
            recordServerFailure(failedServer.getServerKey());
            recordSwitchedServer(failedServer.getServerKey());
            failedServer.setAlive(false);
            sessionHandshakeFailedKeys.add(failedServer.getServerKey().toLowerCase(Locale.ROOT));
            AppLogger.w(TAG, "⚡ Initial handshake failed on [" + failedServer.getDisplayName()
                    + " (" + failedServer.getHost() + ")]. Failure attempt #" + attempt);
        }

        int maxAttempts = isCellular ? 24 : 8;
        if (attempt > maxAttempts) {
            AppLogger.i(TAG, "Failover cycle reached " + maxAttempts + " attempts. Resetting attempt counters and triggering emergency mobile refresh.");
            initialFailoverAttempts.set(0);
            sessionHandshakeFailedKeys.clear();
            failedServerCooldowns.clear();
            if (isCellular) {
                WhitelistPoolManager.getInstance(appContext).triggerEmergencyRefresh();
            }
        }

        // При 3-х сбоях на сотовой сети — экстренное фоновое обновление мобильных конфигов
        if (isCellular && attempt >= 3 && attempt % 3 == 0) {
            WhitelistPoolManager.getInstance(appContext).triggerEmergencyRefresh();
        }

        currentSwitchCooldownMs = BASE_SWITCH_COOLDOWN_MS;

        VpnServer next = null;

        if (isCellular) {
            // =========================================================================
            // НА СОТОВОЙ СВЯЗИ (Build 67):
            // 1. Сначала мгновенно (0 мс) берем #2/#3 из уже пропингованной 5-серверной волны
            // 2. Если очередь волны пуста — запускаем следующую волну по 5 серверов через Telegram API
            // =========================================================================
            next = popVerifiedCellularServer();
            if (next != null) {
                AppLogger.i(TAG, "⚡ [Wave Failover #" + attempt + "] Switching immediately (0ms) to queued #next server: "
                        + next.getDisplayName() + " [" + next.getHost() + "] (" + next.getPingMs() + "ms)");
                seamlessConnectToServer(next, "Резервный узел из волны (попытка " + attempt + ")");
                triggerImmediateBenchmark();
            } else {
                AppLogger.i(TAG, "⚡ [Wave Failover #" + attempt + "] Verified queue exhausted; launching next 5-server Telegram wave probe...");
                seamlessConnectToWaveSearch("Подбор следующей 5-ки серверов (попытка " + attempt + ")");
            }
            return;
        } else {
            // =========================================================================
            // НА WI-FI / ETHERNET: Выбор из горячего standbyPool и Community Top
            // =========================================================================
            while (!standbyPool.isEmpty()) {
                VpnServer cand = standbyPool.remove(0);
                if (cand != null && cand.getHost() != null) {
                    String k = cand.getServerKey().toLowerCase(Locale.ROOT);
                    if (!sessionHandshakeFailedKeys.contains(k)
                            && !isServerInCooldown(cand.getServerKey())
                            && !isRecentlySwitched(cand.getServerKey())
                            && !ServerPenaltyManager.getInstance(appContext).isPenalized(cand.getServerKey())
                            && !MainActivity.isKnownDeadHost(cand.getHost())) {
                        next = cand;
                        break;
                    }
                }
            }

            if (next == null) {
                try {
                    List<VpnServer> communityTop = CommunityPoolManager.getInstance(appContext).getTopCommunityServers();
                    if (communityTop != null && !communityTop.isEmpty()) {
                        for (VpnServer c : communityTop) {
                            if (c != null && c.getHost() != null) {
                                String k = c.getServerKey().toLowerCase(Locale.ROOT);
                                if (!sessionHandshakeFailedKeys.contains(k)
                                        && !isServerInCooldown(c.getServerKey())
                                        && !isRecentlySwitched(c.getServerKey())
                                        && !ServerPenaltyManager.getInstance(appContext).isPenalized(c.getServerKey())
                                        && !MainActivity.isKnownDeadHost(c.getHost())) {
                                    next = c;
                                    AppLogger.i(TAG, "⚡ Initial failover selected Community Top node: " + c.getDisplayName());
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            if (next == null) {
                next = pickNextEligibleCandidate(failedServer);
            }
        }

        if (next != null) {
            AppLogger.i(TAG, "⚡ Instant initial failover (#" + attempt + "): switching to "
                    + next.getDisplayName() + " [" + next.getHost() + "]");
            seamlessConnectToServer(next, "Первичное рукопожатие не удалось (попытка " + attempt + ")");
            triggerImmediateBenchmark();
        } else {
            AppLogger.w(TAG, "⚡ No eligible standby found, launching emergency full-pool sweep...");
            performEmergencyFullPoolSearchAndSwitch("Первичное рукопожатие не удалось (попытка " + attempt + ")");
        }
    }

    private void loadInitialCandidates() {
        try {
            List<VpnServer> initial = EmergencyConfigStorage.loadEmergencyReserve(appContext);
            if (initial != null && !initial.isEmpty()) {
                candidateServers.addAll(initial);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Регистрирует слушатель сетевых переходов (Wi-Fi <-> Сотовая связь).
     */
    private void registerNetworkListener() {
        try {
            final ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            lastWasCellular = WhitelistPoolManager.isRunningOnCellular(appContext);

            // 1. Android 7.0+ (API 24+) / Android 9.0+ System Default Network Callback
            // Официальный механизм Android: срабатывает именно тогда, когда система переключает маршрут по умолчанию
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    defaultNetworkCallback = new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            handleDefaultNetworkEvent(network);
                        }

                        @Override
                        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                            handleDefaultNetworkEvent(network);
                        }

                        @Override
                        public void onLost(Network network) {
                            GoblinVpnService.updateUnderlyingNetwork(null);
                            handoverExecutor.schedule(new Runnable() {
                                @Override
                                public void run() {
                                    checkUnderlyingTransportChange();
                                }
                            }, 150, TimeUnit.MILLISECONDS);
                        }
                    };
                    cm.registerDefaultNetworkCallback(defaultNetworkCallback);
                    AppLogger.i(TAG, "Registered registerDefaultNetworkCallback for Android default route monitoring.");
                } catch (Throwable t) {
                    Log.w(TAG, "registerDefaultNetworkCallback failed: " + t.getMessage());
                }
            }

            // 2. Generic NetworkRequest fallback listener for physical network monitoring
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        handoverExecutor.schedule(new Runnable() {
                            @Override
                            public void run() {
                                checkUnderlyingTransportChange();
                            }
                        }, 200, TimeUnit.MILLISECONDS);
                    }

                    @Override
                    public void onLost(Network network) {
                        GoblinVpnService.updateUnderlyingNetwork(null);
                        handoverExecutor.schedule(new Runnable() {
                            @Override
                            public void run() {
                                checkUnderlyingTransportChange();
                            }
                        }, 150, TimeUnit.MILLISECONDS);
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        handoverExecutor.schedule(new Runnable() {
                            @Override
                            public void run() {
                                checkUnderlyingTransportChange();
                            }
                        }, 200, TimeUnit.MILLISECONDS);
                    }
                };

                cm.registerNetworkCallback(request, networkCallback);
                AppLogger.i(TAG, "Registered NetworkRequest callback for underlying physical network monitoring.");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to register network callback: " + e.getMessage());
        }
    }

    private synchronized void handleDefaultNetworkEvent(Network network) {
        if (network == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return;

            // 1. ИГНОРИРУЕМ VPN-СЕТЬ! Android присылает события на созданную виртуальную сеть tun0
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return;

            boolean isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            boolean isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

            if (!hasInternet) return;

            long now = SystemClock.elapsedRealtime();

            if (isWifi && lastWasCellular) {
                if (now - lastNetworkHandoverMs < 800L) return;
                lastNetworkHandoverMs = now;
                lastWasCellular = false;
                GoblinVpnService.updateUnderlyingNetwork(null);
                AppLogger.i(TAG, "⚡ DEFAULT ROUTE HANDOVER: Cellular -> Wi-Fi (" + network + ")! Restoring VPN connection over Wi-Fi...");
                onNetworkSwitchedToWifi();
            } else if (isCellular && !lastWasCellular && !isWifi) {
                if (now - lastNetworkHandoverMs < 800L) return;
                lastNetworkHandoverMs = now;
                lastWasCellular = true;
                GoblinVpnService.updateUnderlyingNetwork(null);
                AppLogger.i(TAG, "⚡ DEFAULT ROUTE HANDOVER: Wi-Fi -> Cellular (" + network + ")! Activating mobile bypass...");
                onNetworkSwitchedToCellular();
            } else if (isWifi && !lastWasCellular) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    GoblinVpnService.updateUnderlyingNetwork(network);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "handleDefaultNetworkEvent error: " + t.getMessage());
        }
    }

    private synchronized void checkUnderlyingTransportChange() {
        long now = SystemClock.elapsedRealtime();
        boolean nowCellular = WhitelistPoolManager.isRunningOnCellular(appContext);
        if (nowCellular && !lastWasCellular) {
            if (now - lastNetworkHandoverMs < 800L) return;
            lastNetworkHandoverMs = now;
            lastWasCellular = true;
            AppLogger.i(TAG, "⚡ PHYSICAL NETWORK SWITCH DETECTED: Wi-Fi -> Cellular! Initiating instant Mobile Bypass Handover.");
            onNetworkSwitchedToCellular();
        } else if (!nowCellular && lastWasCellular) {
            if (now - lastNetworkHandoverMs < 800L) return;
            lastNetworkHandoverMs = now;
            lastWasCellular = false;
            AppLogger.i(TAG, "⚡ PHYSICAL NETWORK SWITCH DETECTED: Cellular -> Wi-Fi! Initiating instant Wi-Fi Handover.");
            onNetworkSwitchedToWifi();
        }
    }

    /**
     * Обработка мгновенного переключения с Wi-Fi на сотовую сеть.
     */
    public synchronized void onNetworkSwitchedToCellular() {
        AppLogger.i(TAG, "⚡ onNetworkSwitchedToCellular() invoked! Checking active VPN connection state...");

        // 1. Очищаем резервный пул и очередь предыдущей сети
        standbyPool.clear();
        verifiedCellularQueue.clear();

        // 2. Сбрасываем привязку к физическому интерфейсу на системный дефолт (сотовую сеть)
        GoblinVpnService.updateUnderlyingNetwork(null);

        // 3. Если VPN активен — переподключаем туннель на сотовую сеть
        VpnState state = GoblinVpnService.getCurrentState();
        if (state == VpnState.CONNECTED || state == VpnState.CONNECTING) {
            handoverExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Give cellular modem 600ms grace period to initialize IP routing & cellular bearer
                        try {
                            Thread.sleep(600);
                        } catch (InterruptedException ignored) {}

                        if (GoblinVpnService.isExplicitUserDisconnect) return;

                        boolean isAlreadyMobileBypass = false;
                        VpnServer current = currentConnectedServer;
                        if (current != null) {
                            if (!"193.233.199.233".equals(current.getHost())
                                    && WhitelistPoolManager.getInstance(appContext).isWhitelistedForMobile(current.getHost())) {
                                isAlreadyMobileBypass = WhitelistPoolManager.getInstance(appContext).isServerWhitelistedOrTagged(current);
                            }
                        }

                        if (!isAlreadyMobileBypass || isServerInCooldown(current.getServerKey()) || ServerPenaltyManager.getInstance(appContext).isPenalized(current.getServerKey())) {
                            AppLogger.i(TAG, "Active server [" + (current != null ? current.getDisplayName() : "Unknown")
                                    + "] is not an active mobile bypass node. Launching 5-server cellular wave handover...");
                            VpnServer queued = popVerifiedCellularServer();
                            if (queued != null) {
                                seamlessConnectToServer(queued, "Переключение Wi-Fi -> Сотовая связь (Queue)");
                                triggerImmediateBenchmark();
                            } else {
                                seamlessConnectToWaveSearch("Переключение Wi-Fi -> Сотовая связь (волновой подбор)");
                            }
                        } else {
                            AppLogger.i(TAG, "Active server is already a mobile bypass node. Seamlessly reconnecting over Cellular transport...");
                            seamlessConnectToServer(current, "Переключение Wi-Fi -> Сотовая связь (переподключение)");
                            triggerImmediateBenchmark();
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in Cellular handover: " + t.getMessage(), t);
                    }
                }
            });
        }
    }

    /**
     * Обработка мгновенного переключения с сотовой сети на Wi-Fi.
     * Автоматически восстанавливает VPN-туннель на новом Wi-Fi интерфейсе.
     */
    public synchronized void onNetworkSwitchedToWifi() {
        AppLogger.i(TAG, "⚡ onNetworkSwitchedToWifi() invoked! Checking active VPN connection state...");

        VpnState state = GoblinVpnService.getCurrentState();
        if (state != VpnState.CONNECTED && state != VpnState.CONNECTING) {
            return;
        }

        if (!isWifiHandoverPending.compareAndSet(false, true)) {
            AppLogger.d(TAG, "Wi-Fi handover already in progress, debouncing duplicate request");
            return;
        }

        standbyPool.clear();
        verifiedCellularQueue.clear();
        isEmergencySweeping.set(false);
        GoblinVpnService.updateUnderlyingNetwork(null);
        AppLogger.i(TAG, "⚡ Active VPN detected during Cellular -> Wi-Fi switch! Handover to Wi-Fi initiated...");
        WhitelistPoolManager.getInstance(appContext).refreshMobileBypassConfigsAsync(false);

        handoverExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    VpnState st = GoblinVpnService.getCurrentState();
                    if (st != VpnState.CONNECTED && st != VpnState.CONNECTING) {
                        return;
                    }

                    NetworkPreferences prefs = NetworkPreferences.getInstance(appContext);
                    String selectedHost = prefs.getSelectedServerHost();
                    boolean isCellular = WhitelistPoolManager.isRunningOnCellular(appContext);

                    VpnServer target = null;

                    // 1. If user selected a specific server and not in auto mode, use that (strictly unless cellular blocks it)
                    if (!prefs.isAutoServerMode() && selectedHost != null && candidateServers != null) {
                        if (!isCellular || (!"193.233.199.233".equals(selectedHost) && WhitelistPoolManager.getInstance(appContext).isWhitelistedForMobile(selectedHost))) {
                            for (VpnServer s : candidateServers) {
                                if (s != null && selectedHost.equalsIgnoreCase(s.getHost()) && !MainActivity.isKnownDeadHost(s.getHost())) {
                                    target = s;
                                    break;
                                }
                            }
                        }
                    }

                    // 2. On Cellular network: STRICTLY prioritize Mobile Bypass Pool
                    if (target == null && isCellular) {
                        List<VpnServer> bypass = WhitelistPoolManager.getInstance(appContext).getMobileBypassServerPool(appContext);
                        List<VpnServer> candidates = new ArrayList<>();
                        if (bypass != null && !bypass.isEmpty()) {
                            for (VpnServer s : bypass) {
                                if (s != null && !"193.233.199.233".equals(s.getHost())
                                        && !MainActivity.isKnownDeadHost(s.getHost())
                                        && WhitelistPoolManager.getInstance(appContext).isWhitelistedForMobile(s.getHost())
                                        && !isServerInCooldown(s.getServerKey())
                                        && !ServerPenaltyManager.getInstance(appContext).isPenalized(s.getServerKey())) {
                                    candidates.add(s);
                                }
                            }
                        }
                        if (!candidates.isEmpty()) {
                            target = ServerPinger.preflightTelegramPingSync(candidates, 2500);
                            if (target == null) target = candidates.get(0);
                        }
                        if (target == null && bypass != null && !bypass.isEmpty()) {
                            for (VpnServer bs : bypass) {
                                if (bs != null && !"193.233.199.233".equals(bs.getHost())
                                        && !MainActivity.isKnownDeadHost(bs.getHost())
                                        && WhitelistPoolManager.getInstance(appContext).isWhitelistedForMobile(bs.getHost())) {
                                    target = bs;
                                    break;
                                }
                            }
                        }
                    }

                    // 3. On Wi-Fi / Non-Cellular: assemble verified alive candidates with pre-flight ping
                    if (target == null) {
                        List<VpnServer> candidates = new ArrayList<>();

                        // Priority A: Verified Community Top Nodes (Sweden, Germany, etc.)
                        try {
                            List<VpnServer> commTop = CommunityPoolManager.getInstance(appContext).getTopCommunityServers();
                            if (commTop != null && !commTop.isEmpty()) {
                                for (VpnServer s : commTop) {
                                    if (s != null && !MainActivity.isKnownDeadHost(s.getHost())
                                            && !isServerInCooldown(s.getServerKey())
                                            && !ServerPenaltyManager.getInstance(appContext).isPenalized(s.getServerKey())) {
                                        candidates.add(s);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}

                        // Priority B: Primary candidate servers (subscription / bundled)
                        if (candidateServers != null && !candidateServers.isEmpty()) {
                            for (VpnServer s : candidateServers) {
                                if (s != null && !MainActivity.isKnownDeadHost(s.getHost())
                                        && !isServerInCooldown(s.getServerKey())
                                        && !ServerPenaltyManager.getInstance(appContext).isPenalized(s.getServerKey())
                                        && !candidates.contains(s)) {
                                    candidates.add(s);
                                }
                            }
                        }

                        // Priority C: Mobile bypass pool fallback (works great on Wi-Fi too)
                        if (candidates.isEmpty()) {
                            List<VpnServer> bypass = WhitelistPoolManager.getInstance(appContext).getMobileBypassServerPool(appContext);
                            if (bypass != null && !bypass.isEmpty()) {
                                for (VpnServer s : bypass) {
                                    if (s != null && !MainActivity.isKnownDeadHost(s.getHost())
                                            && !isServerInCooldown(s.getServerKey())
                                            && !ServerPenaltyManager.getInstance(appContext).isPenalized(s.getServerKey())) {
                                        candidates.add(s);
                                    }
                                }
                            }
                        }

                        // Fast-path: Rapid parallel preflight ping (750ms) to select the living responsive node
                        if (!candidates.isEmpty()) {
                            target = ServerPinger.preflightPingSync(candidates, 750);
                        }

                        if (target == null && !candidates.isEmpty()) {
                            target = candidates.get(0);
                        }
                    }

                    if (target != null) {
                        AppLogger.i(TAG, "⚡ Reconnecting to verified node " + target.getDisplayName() + " [" + target.getHost() + "] on Wi-Fi interface...");
                        seamlessConnectToServer(target, "Переключение Сотовая связь -> Wi-Fi (Fast-Path)");
                        triggerImmediateBenchmark();
                    } else {
                        AppLogger.i(TAG, "⚡ No cached server, launching sweep for Wi-Fi reconnection...");
                        performEmergencyFullPoolSearchAndSwitch("Переключение Сотовая связь -> Wi-Fi (автовосстановление)");
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Error in Wi-Fi handover: " + t.getMessage(), t);
                } finally {
                    isWifiHandoverPending.set(false);
                }
            }
        }, 300, TimeUnit.MILLISECONDS);
    }

    public void setCandidateServers(List<VpnServer> servers) {
        if (servers == null || servers.isEmpty()) return;
        candidateServers.clear();
        candidateServers.addAll(servers);
        if (standbyPool.isEmpty()) {
            triggerImmediateBenchmark();
        }
    }

    public List<VpnServer> getStandbyPool() {
        return Collections.unmodifiableList(new ArrayList<>(standbyPool));
    }

    public synchronized void onVpnConnected(VpnServer server) {
        this.currentConnectedServer = server;
        this.lastSwitchTimestampMs = SystemClock.elapsedRealtime();
        this.consecutiveDegradationCount = 0;
        this.consecutiveDropCount = 0;
        this.lastTrafficRxBytes = TrafficStats.getUidRxBytes(Process.myUid());
        this.lastTrafficTimestampMs = SystemClock.elapsedRealtime();
        this.initialFailoverAttempts.set(0);

        if (server != null) {
            ServerPenaltyManager.getInstance(appContext).recordSuccess(server.getServerKey());
        }

        startMonitoring();
    }

    public synchronized void onVpnDisconnected() {
        this.currentConnectedServer = null;
        this.consecutiveSwitchCount = 0;
        this.consecutiveDropCount = 0;
        this.consecutiveDegradationCount = 0;
        this.currentSwitchCooldownMs = BASE_SWITCH_COOLDOWN_MS;
        stopMonitoring();
    }

    /**
     * Вызывается службой GoblinVpnService при неожиданном обрыве туннеля сотовой вышкой
     * или непредвиденном завершении цикла Xray.
     */
    public synchronized void onTunnelDroppedUnexpectedly(VpnServer failedServer, String reason) {
        boolean isCellular = WhitelistPoolManager.isRunningOnCellular(appContext);
        if (failedServer != null) {
            recordServerFailure(failedServer.getServerKey());
            recordSwitchedServer(failedServer.getServerKey());
            ServerPenaltyManager.getInstance(appContext).recordFailure(failedServer.getServerKey());
            if (isCellular) {
                MobileProvenConfigStorage.getInstance(appContext).recordFailure(failedServer.getServerKey());
            }
        }
        AppLogger.w(TAG, "⚡ Unexpected tunnel drop detected: " + reason);

        VpnServer next = null;

        // On Cellular: Instant 0ms failover to #2/#3 from wave queue, else launch 5-server Telegram wave probe
        if (isCellular) {
            if (failedServer != null) {
                sessionHandshakeFailedKeys.add(failedServer.getServerKey().toLowerCase(Locale.ROOT));
            }
            next = popVerifiedCellularServer();
            if (next != null) {
                AppLogger.i(TAG, "⚡ [Drop Failover] Switching immediately (0ms) to queued #next server: "
                        + next.getDisplayName() + " [" + next.getHost() + "] (" + next.getPingMs() + "ms)");
                seamlessConnectToServer(next, reason);
                triggerImmediateBenchmark();
            } else {
                AppLogger.i(TAG, "⚡ [Drop Failover] Queue empty on cellular; launching 5-server wave recovery...");
                seamlessConnectToWaveSearch(reason);
            }
            return;
        } else {
            // Wi-Fi: check standbyPool first
            while (!standbyPool.isEmpty()) {
                VpnServer cand = standbyPool.remove(0);
                if (cand != null && !isServerInCooldown(cand.getServerKey()) && !isRecentlySwitched(cand.getServerKey())
                        && !ServerPenaltyManager.getInstance(appContext).isPenalized(cand.getServerKey())
                        && !MainActivity.isKnownDeadHost(cand.getHost())) {
                    next = cand;
                    break;
                }
            }
        }

        // On Wi-Fi: check Community Top nodes
        if (next == null && !isCellular) {
            try {
                List<VpnServer> communityTop = CommunityPoolManager.getInstance(appContext).getTopCommunityServers();
                if (communityTop != null && !communityTop.isEmpty()) {
                    for (VpnServer c : communityTop) {
                        if (c != null && !isServerInCooldown(c.getServerKey()) && !isRecentlySwitched(c.getServerKey())
                                && !ServerPenaltyManager.getInstance(appContext).isPenalized(c.getServerKey())
                                && !MainActivity.isKnownDeadHost(c.getHost())) {
                            next = c;
                            AppLogger.i(TAG, "⚡ Initial failover selected Community Top node: " + c.getDisplayName());
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (next == null) {
            next = pickNextEligibleCandidate(failedServer);
        }

        if (next != null) {
            AppLogger.i(TAG, "⚡ Auto-recovery: switching to standby server " + next.getDisplayName() + " [" + next.getHost() + "]");
            seamlessConnectToServer(next, reason);
            triggerImmediateBenchmark();
        } else {
            AppLogger.i(TAG, "⚡ Auto-recovery: standby pool empty, launching emergency full-pool sweep across all 54 bypass nodes...");
            performEmergencyFullPoolSearchAndSwitch(reason);
        }
    }

    public synchronized void onAutoSwitchPreferenceChanged() {
        NetworkPreferences prefs = NetworkPreferences.getInstance(appContext);
        if (prefs.isAutoSwitchServerEnabled()) {
            if (GoblinVpnService.getCurrentState() == VpnState.CONNECTED) {
                startMonitoring();
            }
        } else {
            cancelHealthCheck();
        }
    }

    private synchronized void startMonitoring() {
        if (isRunning) return;
        isRunning = true;
        AppLogger.i(TAG, "Starting AutoSwitchEngine monitoring...");

        triggerImmediateBenchmark();

        // 1. Двухминутный бенчмарк горячего пула
        benchmarkSchedule = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                runStandbyBenchmark();
            }
        }, BENCHMARK_INTERVAL_SECONDS, BENCHMARK_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // 2. Периодическая проверка качества соединения
        NetworkPreferences prefs = NetworkPreferences.getInstance(appContext);
        if (prefs.isAutoSwitchServerEnabled() || WhitelistPoolManager.isRunningOnCellular(appContext)) {
            startHealthCheckSchedule();
        }

        // 3. Keepalive пинг каждые 20 секунд для сотовых операторов
        startKeepAliveSchedule();
    }

    private synchronized void startHealthCheckSchedule() {
        cancelHealthCheck();
        healthSchedule = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                checkConnectionHealth();
            }
        }, 2, HEALTH_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void cancelHealthCheck() {
        if (healthSchedule != null) {
            healthSchedule.cancel(false);
            healthSchedule = null;
        }
    }

    private synchronized void startKeepAliveSchedule() {
        cancelKeepAliveSchedule();
        keepAliveSchedule = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    if (GoblinVpnService.getCurrentState() == VpnState.CONNECTED) {
                        GoblinVpnService.measureActiveTunnelDelay(ServerPinger.HTTP_204_CLOUDFLARE);
                    }
                } catch (Exception ignored) {}
            }
        }, 15, KEEP_ALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void cancelKeepAliveSchedule() {
        if (keepAliveSchedule != null) {
            keepAliveSchedule.cancel(false);
            keepAliveSchedule = null;
        }
    }

    private synchronized void stopMonitoring() {
        isRunning = false;
        if (benchmarkSchedule != null) {
            benchmarkSchedule.cancel(false);
            benchmarkSchedule = null;
        }
        cancelHealthCheck();
        cancelKeepAliveSchedule();
        AppLogger.i(TAG, "Stopped AutoSwitchEngine monitoring.");
    }

    public void triggerImmediateBenchmark() {
        scheduler.execute(new Runnable() {
            @Override
            public void run() {
                runStandbyBenchmark();
            }
        });
    }

    /**
     * Экстренный перебор ВСЕГО пула (все 54+ конфига) скользящими батчами.
     */
    public void performEmergencyFullPoolSearchAndSwitch(final String reason) {
        if (!isEmergencySweeping.compareAndSet(false, true)) {
            return;
        }

        scheduler.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    AppLogger.i(TAG, "🔍 Starting emergency mobile bypass sweep across full pool (Reason: " + reason + ")...");
                    WhitelistPoolManager whitelist = WhitelistPoolManager.getInstance(appContext);
                    List<VpnServer> allBypass = whitelist.getMobileBypassServerPool(appContext);
                    if (allBypass == null || allBypass.isEmpty()) {
                        allBypass = candidateServers;
                    }
                    if (allBypass == null || allBypass.isEmpty()) {
                        AppLogger.w(TAG, "No candidate bypass servers found in storage!");
                        return;
                    }

                    long now = SystemClock.elapsedRealtime();

                    // Очистка устаревших штрафов (старше 4 минут)
                    for (Map.Entry<String, Long> entry : failedServerCooldowns.entrySet()) {
                        if (now - entry.getValue() > FAILED_SERVER_COOLDOWN_MS) {
                            failedServerCooldowns.remove(entry.getKey());
                        }
                    }

                    final VpnServer active = currentConnectedServer;
                    List<VpnServer> eligible = new ArrayList<>();
                    for (VpnServer s : allBypass) {
                        if (s == null) continue;
                        if (active != null && s.getServerKey().equalsIgnoreCase(active.getServerKey())) {
                            continue;
                        }
                        Long cd = failedServerCooldowns.get(s.getServerKey());
                        if (cd != null && (now - cd < FAILED_SERVER_COOLDOWN_MS)) {
                            continue;
                        }
                        eligible.add(s);
                    }

                    // Если все узлы в штрафном боксе — сбрасываем штрафы для повторного круга
                    if (eligible.isEmpty()) {
                        AppLogger.i(TAG, "All bypass servers were on cooldown. Resetting cooldowns for second sweep...");
                        failedServerCooldowns.clear();
                        for (VpnServer s : allBypass) {
                            if (s == null) continue;
                            if (active != null && s.getServerKey().equalsIgnoreCase(active.getServerKey())) continue;
                            eligible.add(s);
                        }
                    }

                    int total = eligible.size();
                    if (total == 0) {
                        AppLogger.w(TAG, "No eligible servers to sweep!");
                        return;
                    }

                    int startCursor = mobilePoolCursor % total;
                    VpnServer workingNode = null;
                    List<VpnServer> standbyCandidates = new ArrayList<>();

                    for (int offset = 0; offset < total; offset += BATCH_TEST_SIZE) {
                        List<VpnServer> currentBatch = new ArrayList<>();
                        for (int i = 0; i < BATCH_TEST_SIZE && (offset + i) < total; i++) {
                            int idx = (startCursor + offset + i) % total;
                            currentBatch.add(eligible.get(idx));
                        }

                        final CountDownLatch latch = new CountDownLatch(currentBatch.size());
                        final List<VpnServer> batchAlive = Collections.synchronizedList(new ArrayList<VpnServer>());

                        for (final VpnServer candidate : currentBatch) {
                            benchmarkWorkerPool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        int ping = measureServerRtt(candidate);
                                        if (ping > 0 && ping < 3200) {
                                            candidate.setPingMs(ping);
                                            candidate.setAlive(true);
                                            batchAlive.add(candidate);
                                        } else {
                                            failedServerCooldowns.put(candidate.getServerKey(), SystemClock.elapsedRealtime());
                                        }
                                    } catch (Exception ignored) {
                                        failedServerCooldowns.put(candidate.getServerKey(), SystemClock.elapsedRealtime());
                                    } finally {
                                        latch.countDown();
                                    }
                                }
                            });
                        }

                        latch.await(3000, TimeUnit.MILLISECONDS);

                        if (!batchAlive.isEmpty()) {
                            Collections.sort(batchAlive, new Comparator<VpnServer>() {
                                @Override
                                public int compare(VpnServer a, VpnServer b) {
                                    return Integer.compare(a.getPingMs(), b.getPingMs());
                                }
                            });

                            workingNode = batchAlive.remove(0);
                            standbyCandidates.addAll(batchAlive);
                            mobilePoolCursor = (startCursor + offset + BATCH_TEST_SIZE) % total;
                            break;
                        }
                    }

                    if (workingNode != null) {
                        AppLogger.i(TAG, "✅ Found working bypass node: " + workingNode.getDisplayName()
                                + " [" + workingNode.getHost() + "] Ping=" + workingNode.getPingMs() + "ms");

                        standbyPool.clear();
                        for (int i = 0; i < Math.min(standbyCandidates.size(), STANDBY_POOL_SIZE); i++) {
                            standbyPool.add(standbyCandidates.get(i));
                        }

                        seamlessConnectToServer(workingNode, reason);
                    } else {
                        AppLogger.w(TAG, "Full pool sweep completed, no nodes responded. Retrying in 8s...");
                        scheduler.schedule(new Runnable() {
                            @Override
                            public void run() {
                                performEmergencyFullPoolSearchAndSwitch("Повторная попытка после неудачного перебора");
                            }
                        }, 8, TimeUnit.SECONDS);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error during emergency bypass sweep: " + e.getMessage(), e);
                } finally {
                    isEmergencySweeping.set(false);
                }
            }
        });
    }

    /**
     * Фоновый бенчмарк: тестирует доступные узлы и обновляет горячий резерв (Hot Standby).
     */
    private void runStandbyBenchmark() {
        if (!isBenchmarking.compareAndSet(false, true)) {
            return;
        }

        try {
            boolean isCellular = WhitelistPoolManager.isRunningOnCellular(appContext);
            List<VpnServer> poolCandidates = new ArrayList<>();

            // 1. Всегда первично включаем 28 проверенных нод обхода глушилок (работают и на Wi-Fi, и на LTE)
            List<VpnServer> mobilePool = WhitelistPoolManager.getInstance(appContext).getMobileBypassServerPool(appContext);
            if (mobilePool != null && !mobilePool.isEmpty()) {
                poolCandidates.addAll(mobilePool);
            }

            // 2. Если Wi-Fi — дополняем обычными серверами подписки
            if (!isCellular && candidateServers != null) {
                for (VpnServer cs : candidateServers) {
                    if (cs != null && !poolCandidates.contains(cs)) {
                        poolCandidates.add(cs);
                    }
                }
            }

            if (poolCandidates == null || poolCandidates.isEmpty()) {
                return;
            }

            long now = SystemClock.elapsedRealtime();
            final VpnServer active = currentConnectedServer;
            List<VpnServer> filtered = new ArrayList<>();
            for (VpnServer s : poolCandidates) {
                if (s == null) continue;
                if (active != null && s.getServerKey().equalsIgnoreCase(active.getServerKey())) {
                    continue;
                }
                Long cd = failedServerCooldowns.get(s.getServerKey());
                if (cd != null && (now - cd < FAILED_SERVER_COOLDOWN_MS)) {
                    continue;
                }
                filtered.add(s);
            }

            if (filtered.isEmpty()) {
                failedServerCooldowns.clear();
                filtered.addAll(poolCandidates);
            }

            int total = filtered.size();
            int startIdx = (isCellular ? mobilePoolCursor : 0) % total;
            int countToTest = Math.min(total, BATCH_TEST_SIZE); // 5 servers per wave

            final List<VpnServer> testBatch = new ArrayList<>();
            for (int i = 0; i < countToTest; i++) {
                testBatch.add(filtered.get((startIdx + i) % total));
            }

            if (isCellular) {
                mobilePoolCursor = (startIdx + countToTest) % total;
            }

            List<VpnServer> aliveServers = ServerPinger.benchmarkBatchSync(testBatch, CELLULAR_WAVE_TIMEOUT_MS);
            if (!aliveServers.isEmpty()) {
                for (VpnServer alive : aliveServers) {
                    reportCommunityStats(alive, alive.getPingMs(), 0);
                }
                standbyPool.clear();
                if (isCellular) {
                    // Add fresh responsive cellular backups into verifiedCellularQueue (avoiding duplicates)
                    for (VpnServer alive : aliveServers) {
                        boolean dup = false;
                        for (VpnServer q : verifiedCellularQueue) {
                            if (q != null && q.getServerKey().equalsIgnoreCase(alive.getServerKey())) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup && verifiedCellularQueue.size() < 6) {
                            verifiedCellularQueue.add(alive);
                        }
                    }
                }
                for (int i = 0; i < Math.min(aliveServers.size(), STANDBY_POOL_SIZE); i++) {
                    standbyPool.add(aliveServers.get(i));
                }

                AppLogger.i(TAG, "Standby Hot Pool refreshed (" + testBatch.size() + "-wide batch): standby=" + standbyPool.size()
                        + ", verifiedQueue=" + verifiedCellularQueue.size());
            } else {
                for (VpnServer dead : testBatch) {
                    failedServerCooldowns.put(dead.getServerKey(), SystemClock.elapsedRealtime() + 60_000L);
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "Standby benchmark error: " + e.getMessage());
        } finally {
            isBenchmarking.set(false);
        }
    }

    private int measureServerRtt(VpnServer server) {
        if (server == null) return -1;
        String host = server.getHost();
        if (host != null) {
            if (host.startsWith("172.64.") || host.startsWith("172.67.") || host.startsWith("104.") || "spacex.com".equalsIgnoreCase(host) || "87.228.101.3".equals(host)) {
                return -1;
            }
        }
        try {
            // 1. Полноценная проверка через Libv2ray с измерением сквозной задержки
            int realDelay = ServerPinger.measureRealProxyDelay(server, 2500);
            if (realDelay >= 20) return realDelay;

            // 2. Резервная прямая TLS проверка сокета ТОЛЬКО для Reality нод
            if (server.isReality()) {
                int directRtt = ServerPinger.pingServerDirect(server, 2500);
                if (directRtt >= 20) return directRtt;
            }

            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Проверка состояния туннеля и сете-адаптивное обнаружение деградации.
     */
    private void checkConnectionHealth() {
        if (GoblinVpnService.getCurrentState() != VpnState.CONNECTED) {
            return;
        }

        // ПЕРВЫМ ДЕЛОМ: Проверка физического переключения сети Wi-Fi <-> Сотовая сеть
        boolean isCellular = WhitelistPoolManager.isRunningOnCellular(appContext);
        long now = SystemClock.elapsedRealtime();
        if (isCellular && !lastWasCellular) {
            if (now - lastNetworkHandoverMs >= 800L) {
                lastNetworkHandoverMs = now;
                lastWasCellular = true;
                consecutiveSwitchCount = 0; // Сброс бэкоффа при смене типа физической сети
                currentSwitchCooldownMs = BASE_SWITCH_COOLDOWN_MS;
                AppLogger.i(TAG, "⚡ Polling detected Wi-Fi -> Cellular transition.");
                onNetworkSwitchedToCellular();
                return;
            }
        } else if (!isCellular && lastWasCellular) {
            if (now - lastNetworkHandoverMs >= 800L) {
                lastNetworkHandoverMs = now;
                lastWasCellular = false;
                consecutiveSwitchCount = 0; // Сброс бэкоффа при смене типа физической сети
                currentSwitchCooldownMs = BASE_SWITCH_COOLDOWN_MS;
                AppLogger.i(TAG, "⚡ Polling detected Cellular -> Wi-Fi transition.");
                onNetworkSwitchedToWifi();
                return;
            }
        }

        NetworkPreferences prefs = NetworkPreferences.getInstance(appContext);
        if (!prefs.isAutoSwitchServerEnabled() && !isCellular) {
            return;
        }

        // 1. Подавление флэппинга: динамический кулдаун с экспоненциальным бэкоффом
        // Если узел мертв при первичном подключении (livePing <= 0 и нет входящего трафика), не ждем 30-300 секунд!
        boolean isDeadOnArrival = (lastTrafficRxBytes == 0 && GoblinVpnService.getLastKnownPing() <= 0);
        long effectiveCooldown = (isCellular && isDeadOnArrival) ? 10000L : currentSwitchCooldownMs;
        if (now - lastSwitchTimestampMs < effectiveCooldown) {
            return;
        }

        // 2. Измерение пропускной способности и иммунитет активного потока (Data-Flow Immunity)
        long curRx = TrafficStats.getUidRxBytes(Process.myUid());
        if (curRx == TrafficStats.UNSUPPORTED) {
            curRx = TrafficStats.getTotalRxBytes();
        }
        long deltaBytes = (lastTrafficRxBytes > 0 && curRx >= lastTrafficRxBytes) ? (curRx - lastTrafficRxBytes) : 0;
        long deltaMs = (lastTrafficTimestampMs > 0 && now > lastTrafficTimestampMs) ? (now - lastTrafficTimestampMs) : 0;
        long speedKbps = (deltaMs > 0) ? ((deltaBytes * 1000L) / (deltaMs * 1024L)) : 0;
        lastTrafficRxBytes = curRx;
        lastTrafficTimestampMs = now;

        // DATA-FLOW IMMUNITY: Если пользовательские приложения активно принимают данные (> 8 КБ за интервал),
        // туннель физически функционирует и передает пакеты. Срабатывание ложного обрыва категорически блокируется!
        boolean hasDataFlowImmunity = (deltaBytes > DATA_FLOW_IMMUNITY_BYTES);

        NetworkProfile profile = detectNetworkProfile(appContext);

        // 3. Измерение доступности туннеля НАПРЯМУЮ ВНУТРИ Xray-core (через coreController.measureDelay)
        int livePing = ServerPinger.checkLiveTunnelHealth(isCellular);
        if (livePing > 0) {
            GoblinVpnService.updateLivePing(livePing);
        }

        // Сброс экспоненциального бэкоффа кулдауна при стабильной работе соединения (2 минуты)
        if (livePing > 0 || hasDataFlowImmunity) {
            if (now - lastSwitchTimestampMs >= HEALTHY_STABILITY_WINDOW_MS) {
                if (consecutiveSwitchCount > 0) {
                    AppLogger.d(TAG, "Tunnel stable for " + (HEALTHY_STABILITY_WINDOW_MS / 1000) + "s, resetting switch backoff.");
                    consecutiveSwitchCount = 0;
                    currentSwitchCooldownMs = BASE_SWITCH_COOLDOWN_MS;
                }
            }
        }

        // 4. Обнаружение обрыва / потери пакетов с защитой Data-Flow Immunity
        if (livePing <= 0) {
            consecutiveDropCount++;
            if (hasDataFlowImmunity && consecutiveDropCount <= 2) {
                // Активный поток трафика защищает от кратковременных просадок синтетического пинга (до 2 интервалов)
                AppLogger.d(TAG, "Data-Flow Immunity active: rx delta=" + (deltaBytes / 1024) + " KB ("
                        + speedKbps + " KB/s). Suppressed temporary ping drop (streak " + consecutiveDropCount + ").");
            } else {
                boolean justHandedOver = (now - lastNetworkHandoverMs < 15000L);
                // В фоновом режиме (приложение свернуто) модем может засыпать (Doze mode / Deep sleep),
                // поэтому порог дропа увеличиваем до 6 попыток (~40 сек), исключая ложный разрыв при сворачивании!
                int dropThreshold;
                if (!isAppInForeground) {
                    dropThreshold = isCellular ? 6 : 5;
                } else {
                    dropThreshold = isCellular ? 3 : 3;
                    if (justHandedOver) {
                        dropThreshold = 2;
                    }
                }

                AppLogger.w(TAG, "Tunnel drop / packet loss detected (attempt " + consecutiveDropCount
                        + " of " + dropThreshold + ", isCellular=" + isCellular + ", isForeground=" + isAppInForeground + ", deltaRx=" + deltaBytes + " B)");

                if (consecutiveDropCount >= dropThreshold) {
                    triggerAutoSwitch(isCellular ? "Обрыв связи сотовой сетью (БС) / потеря пакетов" : "Потеря пакетов в туннеле");
                    return;
                }
            }
        } else {
            consecutiveDropCount = 0;
        }

        // 5. Сете-адаптивная проверка деградации (активна ТОЛЬКО при развернутом приложении)
        if (isAppInForeground) {
            boolean pingDegraded = (livePing > profile.maxPingMs);
            boolean speedDegraded = (!hasDataFlowImmunity && speedKbps > 0 && speedKbps < profile.minSpeedKbps && deltaBytes > 15000);

            if (pingDegraded || speedDegraded) {
                consecutiveDegradationCount++;
                AppLogger.w(TAG, "Degradation on " + profile.name + " [ping=" + livePing + "ms > "
                        + profile.maxPingMs + "ms, speed=" + speedKbps + " KB/s < " + profile.minSpeedKbps
                        + " KB/s, hits=" + consecutiveDegradationCount + "]");

                if (consecutiveDegradationCount >= 3) {
                    String reason = pingDegraded ? ("Высокий пинг (" + livePing + " мс > " + profile.maxPingMs + " мс)")
                            : ("Низкая скорость (" + speedKbps + " КБ/с < " + profile.minSpeedKbps + " КБ/с)");
                    triggerAutoSwitch(reason);
                }
            } else {
                consecutiveDegradationCount = 0;
                if (currentConnectedServer != null && livePing > 0) {
                    reportCommunityStats(currentConnectedServer, livePing, speedKbps);
                }
            }
        } else {
            consecutiveDegradationCount = 0;
        }
    }

    /**
     * Бесшовное переключение на лучший узел горячего пула без сброса UI-состояния.
     */
    private synchronized void triggerAutoSwitch(String reason) {
        lastSwitchTimestampMs = SystemClock.elapsedRealtime();
        consecutiveDegradationCount = 0;
        consecutiveDropCount = 0;

        // Подавление флэппинга: экспоненциальный бэкофф кулдауна переключений (30с, 60с, 120с, 240с, до 300с)
        consecutiveSwitchCount++;
        currentSwitchCooldownMs = Math.min(MAX_SWITCH_COOLDOWN_MS,
                BASE_SWITCH_COOLDOWN_MS * (1L << Math.min(consecutiveSwitchCount - 1, 4)));

        AppLogger.w(TAG, "Flapping suppression: consecutive switch #" + consecutiveSwitchCount
                + ", next switch cooldown: " + (currentSwitchCooldownMs / 1000) + "s");

        // Штрафуем упавший сервер с экспоненциальным кулдауном и историей
        VpnServer failedServer = currentConnectedServer;
        if (failedServer != null) {
            recordServerFailure(failedServer.getServerKey());
            recordSwitchedServer(failedServer.getServerKey());
            sessionHandshakeFailedKeys.add(failedServer.getServerKey().toLowerCase(Locale.ROOT));
        }

        boolean isCellular = WhitelistPoolManager.isRunningOnCellular(appContext);
        VpnServer nextServer = null;
        if (isCellular) {
            nextServer = popVerifiedCellularServer();
            if (nextServer == null) {
                AppLogger.i(TAG, "⚡ [AutoSwitch] Cellular verified queue empty; launching 5-server Telegram wave probe...");
                seamlessConnectToWaveSearch(reason);
                return;
            }
        }

        while (nextServer == null && !standbyPool.isEmpty()) {
            VpnServer cand = standbyPool.remove(0);
            if (cand != null && !isServerInCooldown(cand.getServerKey()) && !isRecentlySwitched(cand.getServerKey()) && !MainActivity.isKnownDeadHost(cand.getHost())) {
                nextServer = cand;
                break;
            }
        }

        if (nextServer == null) {
            nextServer = pickNextEligibleCandidate(failedServer);
        }

        if (nextServer == null) {
            // Если резервный пул пуст — моментально запускаем экстренный перебор по всему списку
            AppLogger.i(TAG, "Standby pool empty on triggerAutoSwitch! Launching emergency sweep...");
            performEmergencyFullPoolSearchAndSwitch(reason);
            return;
        }

        seamlessConnectToServer(nextServer, reason);

        // Планируем фоновое пополнение горячего пула
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                runStandbyBenchmark();
            }
        }, 2, TimeUnit.SECONDS);
    }

    private void seamlessConnectToWaveSearch(String reason) {
        lastSwitchTimestampMs = SystemClock.elapsedRealtime();
        consecutiveDegradationCount = 0;
        consecutiveDropCount = 0;

        AppLogger.i(TAG, "⚡ WAVE AUTO-SWITCH ACTIVATED (" + reason + ")! Launching 5-server wave selection...");

        Intent intent = new Intent(appContext, GoblinVpnService.class);
        intent.setAction(GoblinVpnService.ACTION_CONNECT);
        intent.putExtra(GoblinVpnService.EXTRA_AUTO_WAVE_SELECT, true);

        NetworkPreferences prefs = NetworkPreferences.getInstance(appContext);
        if (prefs.isRestrictedTelegramOnly()) {
            intent.putExtra(GoblinVpnService.EXTRA_RESTRICTED_TELEGRAM_ONLY, true);
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send wave auto-switch intent: " + e.getMessage(), e);
        }
    }

    private void seamlessConnectToServer(VpnServer target, String reason) {
        if (target == null) return;
        lastSwitchTimestampMs = SystemClock.elapsedRealtime();
        consecutiveDegradationCount = 0;
        consecutiveDropCount = 0;

        AppLogger.i(TAG, "⚡ AUTO-SWITCH ACTIVATED (" + reason + ")! Switching to " + target.getDisplayName() + " [" + target.getHost() + "]");

        Intent intent = new Intent(appContext, GoblinVpnService.class);
        intent.setAction(GoblinVpnService.ACTION_CONNECT);
        intent.putExtra(GoblinVpnService.EXTRA_SERVER, target);

        NetworkPreferences prefs = NetworkPreferences.getInstance(appContext);
        if (prefs.isRestrictedTelegramOnly()) {
            intent.putExtra(GoblinVpnService.EXTRA_RESTRICTED_TELEGRAM_ONLY, true);
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send auto-switch intent: " + e.getMessage(), e);
        }

        reportCommunityStats(target, target.getPingMs(), 0);
    }

    private void reportCommunityStats(VpnServer server, int pingMs, long speedKbps) {
        if (server == null) return;
        try {
            AppTelemetryClient.reportCommunityServerShare(appContext, server, pingMs, speedKbps);
        } catch (Exception ignored) {}

        try {
            long connectedTs = GoblinVpnService.getConnectedTimestamp();
            long uptimeSec = (connectedTs > 0) ? (System.currentTimeMillis() - connectedTs) / 1000L : 0;
            long curRx = TrafficStats.getUidRxBytes(Process.myUid());
            long initRx = GoblinVpnService.getInitialRxBytes();
            long sessionRx = (curRx != TrafficStats.UNSUPPORTED && curRx >= initRx) ? (curRx - initRx) : 0;
            if (uptimeSec >= 60 && sessionRx > 100 * 1024L && pingMs > 0 && pingMs < 250) {
                CommunityPoolManager.getInstance(appContext).shareStableServerAsync(server, pingMs, uptimeSec, sessionRx);
                ServerPenaltyManager.getInstance(appContext).recordSuccess(server.getServerKey());
            }
        } catch (Exception ignored) {}
    }

    /**
     * Сете-адаптивное определение типа подключения.
     */
    public static NetworkProfile detectNetworkProfile(Context context) {
        if (context == null) return NetworkProfile.UNKNOWN;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return NetworkProfile.UNKNOWN;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNet = cm.getActiveNetwork();
                if (activeNet != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            return NetworkProfile.WIFI;
                        }
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            return getCellularSubtype(context);
                        }
                    }
                }
            } else {
                NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null && info.isConnected()) {
                    int type = info.getType();
                    if (type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_ETHERNET) {
                        return NetworkProfile.WIFI;
                    }
                    if (type == ConnectivityManager.TYPE_MOBILE) {
                        return getCellularSubtype(context);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error detecting network profile: " + e.getMessage());
        }
        return NetworkProfile.UNKNOWN;
    }

    private static NetworkProfile getCellularSubtype(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                int netType = tm.getNetworkType();
                switch (netType) {
                    case TelephonyManager.NETWORK_TYPE_LTE:
                    case 20: // TelephonyManager.NETWORK_TYPE_NR (5G)
                    case TelephonyManager.NETWORK_TYPE_HSPAP:
                        return NetworkProfile.LTE_4G;
                    case TelephonyManager.NETWORK_TYPE_UMTS:
                    case TelephonyManager.NETWORK_TYPE_HSDPA:
                    case TelephonyManager.NETWORK_TYPE_HSUPA:
                    case TelephonyManager.NETWORK_TYPE_HSPA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                        return NetworkProfile.CELLULAR_3G;
                    default:
                        return NetworkProfile.LTE_4G;
                }
            }
        } catch (Exception ignored) {}
        return NetworkProfile.LTE_4G;
    }
}
