package ru.llazyrin.goblinvpn.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.service.AppLogger;

/**
 * ServerPenaltyManager
 *
 * Tracks failure history and applies escalating penalty cooldowns to dead or unreliable servers:
 * - 1st failure: 3 minutes cooldown (180,000 ms)
 * - 2nd failure: 8 minutes cooldown (480,000 ms)
 * - 3rd+ failure: 20 minutes cooldown (1,200,000 ms)
 *
 * During penalty cooldown, servers are demoted to the very tail of candidate pools.
 * Confirmed stable servers have their failure counts decayed/reset.
 */
public class ServerPenaltyManager {

    private static final String TAG = "ServerPenaltyManager";
    private static final String PREFS_NAME = "goblin_server_penalties";

    private static volatile ServerPenaltyManager sInstance;

    private final Context appContext;
    private final SharedPreferences prefs;

    // In-memory tracking with wall-clock uptime timestamps (SystemClock.elapsedRealtime)
    private final ConcurrentHashMap<String, Long> penaltyExpiryMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> failureCountMap = new ConcurrentHashMap<>();

    private static final long COOLDOWN_TIER_1_MS = 3 * 60 * 1000L;  // 3 minutes
    private static final long COOLDOWN_TIER_2_MS = 8 * 60 * 1000L;  // 8 minutes
    private static final long COOLDOWN_TIER_3_MS = 20 * 60 * 1000L; // 20 minutes

    private ServerPenaltyManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static ServerPenaltyManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ServerPenaltyManager.class) {
                if (sInstance == null) {
                    sInstance = new ServerPenaltyManager(context);
                }
            }
        }
        return sInstance;
    }

    private String getNormalizedKey(String serverKey) {
        if (serverKey == null) return "unknown";
        return serverKey.trim().toLowerCase();
    }

    /**
     * Records a connection failure, handshake timeout, or sudden tunnel drop.
     * Applies escalating cooldown and demotes node to penalty box.
     */
    public synchronized void recordFailure(String serverKey) {
        if (serverKey == null || serverKey.trim().isEmpty()) return;
        String key = getNormalizedKey(serverKey);

        int count = failureCountMap.containsKey(key) ? failureCountMap.get(key) + 1 : 1;
        failureCountMap.put(key, count);

        boolean isProven = ru.llazyrin.goblinvpn.security.MobileProvenConfigStorage.getInstance(appContext).isProven(serverKey);

        long cooldownMs;
        if (isProven) {
            // Proven cellular nodes get gentle, short cooldowns (20s / 60s / 180s) so transient handover drops don't lock them out
            if (count == 1) {
                cooldownMs = 20 * 1000L;
            } else if (count == 2) {
                cooldownMs = 60 * 1000L;
            } else {
                cooldownMs = 180 * 1000L;
            }
        } else {
            if (count == 1) {
                cooldownMs = COOLDOWN_TIER_1_MS;
            } else if (count == 2) {
                cooldownMs = COOLDOWN_TIER_2_MS;
            } else {
                cooldownMs = COOLDOWN_TIER_3_MS;
            }
        }

        long expiryTime = SystemClock.elapsedRealtime() + cooldownMs;
        penaltyExpiryMap.put(key, expiryTime);

        AppLogger.w(TAG, "⚠️ Node penalized [" + key + "]: failure #" + count
                + ", cooldown=" + (cooldownMs / 1000) + "s (isProven=" + isProven + ")");
    }

    /**
     * Checks if a server is currently in penalty cooldown.
     */
    public boolean isPenalized(String serverKey) {
        if (serverKey == null || serverKey.trim().isEmpty()) return false;
        String key = getNormalizedKey(serverKey);

        Long expiry = penaltyExpiryMap.get(key);
        if (expiry == null) return false;

        if (SystemClock.elapsedRealtime() < expiry) {
            return true;
        } else {
            // Cooldown expired
            penaltyExpiryMap.remove(key);
            return false;
        }
    }

    /**
     * Remaining penalty cooldown in milliseconds, or 0 if not penalized.
     */
    public long getRemainingPenaltyMs(String serverKey) {
        if (serverKey == null) return 0;
        String key = getNormalizedKey(serverKey);
        Long expiry = penaltyExpiryMap.get(key);
        if (expiry == null) return 0;
        long rem = expiry - SystemClock.elapsedRealtime();
        return rem > 0 ? rem : 0;
    }

    /**
     * Records successful stable session on a server, resetting or decaying penalties.
     */
    public synchronized void recordSuccess(String serverKey) {
        if (serverKey == null || serverKey.trim().isEmpty()) return;
        String key = getNormalizedKey(serverKey);

        Long prevExpiry = penaltyExpiryMap.remove(key);
        Integer count = failureCountMap.get(key);
        boolean hadFailures = (count != null && count > 0);
        if (count != null && count > 1) {
            failureCountMap.put(key, count - 1);
        } else {
            failureCountMap.remove(key);
        }
        if (hadFailures || prevExpiry != null) {
            AppLogger.i(TAG, "✅ Node rewarded [" + key + "]: penalties cleared / decayed");
        }
    }

    /**
     * Sorts candidate servers: non-penalized nodes first (preserving ping/priority order),
     * followed by penalized nodes demoted to the tail as last-resort fallback.
     */
    public List<VpnServer> filterAndSortCandidates(List<VpnServer> originalList) {
        if (originalList == null || originalList.isEmpty()) {
            return new ArrayList<>();
        }

        List<VpnServer> healthy = new ArrayList<>();
        List<VpnServer> penalized = new ArrayList<>();

        for (VpnServer s : originalList) {
            if (s == null) continue;
            if (isPenalized(s.getServerKey())) {
                penalized.add(s);
            } else {
                healthy.add(s);
            }
        }

        List<VpnServer> result = new ArrayList<>(healthy.size() + penalized.size());
        result.addAll(healthy);
        result.addAll(penalized);
        return result;
    }

    /**
     * Prunes expired penalties and halves active penalty cooldowns (minimum 15s)
     * so recently failing nodes remain demoted across user-initiated reconnects.
     */
    public synchronized void pruneExpiredAndDecay() {
        long now = SystemClock.elapsedRealtime();
        java.util.Iterator<Map.Entry<String, Long>> it = penaltyExpiryMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            long remaining = entry.getValue() - now;
            if (remaining <= 0L) {
                it.remove();
                String key = entry.getKey();
                Integer count = failureCountMap.get(key);
                if (count == null || count <= 1) {
                    failureCountMap.remove(key);
                } else {
                    failureCountMap.put(key, count - 1);
                }
            } else {
                long decayed = Math.max(15_000L, remaining / 2L);
                entry.setValue(now + decayed);
            }
        }
    }

    public synchronized void clearServerPenalty(String serverKey) {
        if (serverKey == null || serverKey.trim().isEmpty()) return;
        String key = getNormalizedKey(serverKey);
        penaltyExpiryMap.remove(key);
        failureCountMap.remove(key);
    }

    /**
     * Clears all in-memory penalties (e.g. on manual user pool refresh).
     */
    public synchronized void resetAll() {
        penaltyExpiryMap.clear();
        failureCountMap.clear();
        AppLogger.i(TAG, "All server penalties reset");
    }
}
