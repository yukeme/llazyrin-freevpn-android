package ru.llazyrin.goblinvpn.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.net.Network;
import android.net.TrafficStats;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import go.Seq;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;
import ru.llazyrin.goblinvpn.MainActivity;
import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.model.NetworkPreferences;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.net.AppTelemetryClient;
import ru.llazyrin.goblinvpn.net.AutoSwitchEngine;
import ru.llazyrin.goblinvpn.net.ServerPinger;
import ru.llazyrin.goblinvpn.net.NotificationCenter;
import ru.llazyrin.goblinvpn.net.WhitelistPoolManager;
import ru.llazyrin.goblinvpn.net.XrayConfigGenerator;

/**
 * Hardened Android VpnService implementation for llazyrinVPN powered by Xray-core engine.
 * Routes all system network traffic through VLESS Reality tunnel.
 */
public class GoblinVpnService extends VpnService {
    private static final String TAG = "GoblinVpnService";

    public static final String ACTION_CONNECT = "ru.llazyrin.goblinvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "ru.llazyrin.goblinvpn.DISCONNECT";
    public static final String ACTION_STATE_CHANGED = "ru.llazyrin.goblinvpn.STATE_CHANGED";
    public static final String ACTION_RESTORE_NOTIFICATION = "ru.llazyrin.goblinvpn.action.RESTORE_NOTIFICATION";

    public static final String EXTRA_SERVER = "extra_server";
    public static final String EXTRA_STATE = "extra_state";
    public static final String EXTRA_ERROR_MESSAGE = "extra_error_message";
    public static final String EXTRA_RESTRICTED_TELEGRAM_ONLY = "extra_restricted_telegram_only";
    public static final String EXTRA_AUTO_WAVE_SELECT = "extra_auto_wave_select";

    private static final String CHANNEL_ID = "goblin_vpn_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long NOTIF_TRAFFIC_INTERVAL_MS = 3000L;
    private static final long WAKELOCK_SAFETY_TIMEOUT_MS = 20_000L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface StateListener {
        void onStateChanged(VpnState state, VpnServer server);
    }
    private static volatile StateListener stateListener;

    public static void setStateListener(StateListener listener) {
        stateListener = listener;
    }

    private static volatile VpnState currentState = VpnState.DISCONNECTED;
    private static volatile VpnServer currentServer = null;
    private static volatile long connectedTimestamp = 0;
    private static volatile int lastKnownPing = 0;
    private static volatile GoblinVpnService activeInstance = null;
    private static volatile long initialUidRxBytes = 0;
    private static volatile long initialUidTxBytes = 0;
    private static volatile long lastNotifRx = 0;
    private static volatile long lastNotifTx = 0;
    public static volatile boolean isExplicitUserDisconnect = false;
    private static volatile long lastNotifTimeMs = 0;
    private static volatile long lastSpeedRx = 0;
    private static volatile long lastSpeedTx = 0;
    private static volatile String connectionStageHint = null;
    private static volatile boolean waveSelecting = false;
    private volatile AtomicBoolean waveCancelToken = new AtomicBoolean(false);

    public static boolean isWaveSelecting() {
        return waveSelecting;
    }

    public static String getConnectionStageHint() {
        return connectionStageHint;
    }

    public static void setConnectionStageHint(String hint) {
        connectionStageHint = hint;
        GoblinVpnService instance = activeInstance;
        if (instance != null && currentState == VpnState.CONNECTING) {
            instance.updateNotification(hint);
            instance.broadcastStateOnly();
        }
    }

    public static long getInitialRxBytes() {
        return initialUidRxBytes;
    }

    public static long getInitialTxBytes() {
        return initialUidTxBytes;
    }

    public static int getLastKnownPing() {
        return lastKnownPing;
    }

    private volatile boolean isScreenOn = true;
    private volatile boolean lastWasIdleZero = false;
    private boolean isScreenReceiverRegistered = false;

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                isScreenOn = false;
                MAIN_HANDLER.removeCallbacks(notifTrafficRunnable);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                isScreenOn = true;
                if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
                    lastWasIdleZero = false;
                    updateNotification(null, false);
                    MAIN_HANDLER.removeCallbacks(notifTrafficRunnable);
                    MAIN_HANDLER.postDelayed(notifTrafficRunnable, NOTIF_TRAFFIC_INTERVAL_MS);
                }
            }
        }
    };

    private final Runnable notifTrafficRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isScreenOn) {
                return;
            }
            if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
                updateNotification(null, currentState == VpnState.CONNECTED);
                if (isScreenOn && (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING)) {
                    MAIN_HANDLER.postDelayed(this, NOTIF_TRAFFIC_INTERVAL_MS);
                }
            }
        }
    };

    public static void updateLivePing(int pingMs) {
        if (pingMs > 0) {
            lastKnownPing = pingMs;
        }
    }

    public static void updateUnderlyingNetwork(Network network) {
        GoblinVpnService instance = activeInstance;
        if (instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                if (network != null) {
                    android.net.ConnectivityManager cm = (android.net.ConnectivityManager) instance.getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm != null) {
                        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                        if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                            // Ни в коем случае не привязываем VPN к самому себе!
                            instance.setUnderlyingNetworks(null);
                            AppLogger.i(TAG, "Ignored virtual VPN network binding, kept underlying route as system default");
                            return;
                        }
                    }
                    instance.setUnderlyingNetworks(new Network[]{ network });
                    AppLogger.i(TAG, "Underlying network explicitly bound: " + network);
                } else {
                    instance.setUnderlyingNetworks(null);
                    AppLogger.i(TAG, "Underlying network reset to system default");
                }
            } catch (Throwable ignored) {}
        }
    }

    private final AtomicLong activeSessionId = new AtomicLong(0);
    private ParcelFileDescriptor vpnInterface = null;
    private volatile CoreController coreController = null;
    private volatile boolean coreShutdownSignaled = false;
    private Thread workerThread = null;
    private volatile boolean isRunning = false;
    private final Object tunnelLock = new Object();

    private PowerManager.WakeLock tunnelWakeLock = null;
    private WifiManager.WifiLock tunnelWifiLock = null;
    private volatile boolean isRestrictedTelegramOnly = false;

    private final Runnable wakeLockSafetyTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            AppLogger.d(TAG, "WakeLock/WifiLock 20s safety timeout reached; releasing transition locks");
            releaseWakeLocks();
        }
    };

    private synchronized void acquireWakeLocks() {
        MAIN_HANDLER.removeCallbacks(wakeLockSafetyTimeoutRunnable);
        MAIN_HANDLER.postDelayed(wakeLockSafetyTimeoutRunnable, WAKELOCK_SAFETY_TIMEOUT_MS);

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (tunnelWakeLock == null) {
                    tunnelWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "llazyrinVPN:TunnelWakeLock");
                    tunnelWakeLock.setReferenceCounted(false);
                }
                tunnelWakeLock.acquire(WAKELOCK_SAFETY_TIMEOUT_MS);
                AppLogger.i(TAG, "Acquired PowerManager.WakeLock (llazyrinVPN:TunnelWakeLock, timeout=20s)");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire WakeLock: " + e.getMessage());
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                if (tunnelWifiLock == null) {
                    tunnelWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "llazyrinVPN:WifiLock");
                    tunnelWifiLock.setReferenceCounted(false);
                }
                if (!tunnelWifiLock.isHeld()) {
                    tunnelWifiLock.acquire();
                    AppLogger.i(TAG, "Acquired WifiManager.WifiLock (llazyrinVPN:WifiLock)");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire WifiLock: " + e.getMessage());
        }
    }

    private synchronized void releaseWakeLocks() {
        MAIN_HANDLER.removeCallbacks(wakeLockSafetyTimeoutRunnable);

        try {
            if (tunnelWakeLock != null && tunnelWakeLock.isHeld()) {
                tunnelWakeLock.release();
                AppLogger.i(TAG, "Released PowerManager.WakeLock");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error releasing WakeLock: " + e.getMessage());
        } finally {
            tunnelWakeLock = null;
        }

        try {
            if (tunnelWifiLock != null && tunnelWifiLock.isHeld()) {
                tunnelWifiLock.release();
                AppLogger.i(TAG, "Released WifiManager.WifiLock");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error releasing WifiLock: " + e.getMessage());
        } finally {
            tunnelWifiLock = null;
        }
    }

    public static GoblinVpnService getActiveInstance() {
        return activeInstance;
    }

    /**
     * Measures round-trip delay through the currently active and running VPN tunnel.
     * Uses CoreController.measureDelay(url) so the probe originates directly INSIDE
     * the active Xray outbound handler (within the encrypted VPN session).
     *
     * This avoids false drop reports caused by builder.addDisallowedApplication(getPackageName())
     * where app process sockets bypass the VPN and hit physical ISP DPI filters.
     *
     * @param targetUrl Target HTTP/HTTPS endpoint (e.g. ServerPinger.TELEGRAM_API_URL or HTTP_204_GSTATIC).
     * @return delay in milliseconds if successful, or -1 if failed / tunnel not running.
     */
    public static int measureActiveTunnelDelay(String targetUrl) {
        GoblinVpnService service = activeInstance;
        if (service == null || !service.isRunning) {
            return -1;
        }
        CoreController controller;
        synchronized (service.tunnelLock) {
            controller = service.coreController;
        }
        if (controller == null || !controller.getIsRunning()) {
            return -1;
        }
        try {
            if (targetUrl == null || targetUrl.trim().isEmpty()) {
                targetUrl = "https://cp.cloudflare.com/generate_204";
            }
            long delay = controller.measureDelay(targetUrl);
            if (delay > 0) {
                return (int) delay;
            }
        } catch (Throwable t) {
            // Core delay measurement timed out or returned error
        }
        return -1;
    }

    /**
     * Protects a socket from being routed through the VPN tunnel, ensuring it
     * reaches the underlying network directly.
     */
    public static boolean protectSocket(java.net.Socket socket) {
        GoblinVpnService instance = activeInstance;
        if (instance != null && socket != null) {
            try {
                return instance.protect(socket);
            } catch (Exception e) {
                Log.w(TAG, "protect(Socket) failed: " + e.getMessage());
            }
        }
        return false;
    }

    public static boolean protectSocket(int socketFd) {
        GoblinVpnService instance = activeInstance;
        if (instance != null && socketFd > 0) {
            try {
                return instance.protect(socketFd);
            } catch (Exception e) {
                Log.w(TAG, "protect(socketFd) failed: " + e.getMessage());
            }
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        createNotificationChannel();
        AppLogger.init(getApplicationContext());
        AppLogger.i(TAG, "GoblinVpnService created, initializing Xray environment...");
        initXrayEnvironment();

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                    isScreenOn = pm.isInteractive();
                } else {
                    isScreenOn = pm.isScreenOn();
                }
            }
        } catch (Exception ignored) {}

        if (!isScreenReceiverRegistered) {
            try {
                IntentFilter screenFilter = new IntentFilter();
                screenFilter.addAction(Intent.ACTION_SCREEN_ON);
                screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(screenStateReceiver, screenFilter);
                }
                isScreenReceiverRegistered = true;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to register screen state receiver: " + t.getMessage());
            }
        }
    }

    private synchronized void initXrayEnvironment() {
        try {
            copyAssetFile("geoip.dat");
            copyAssetFile("geosite.dat");
            Seq.setContext(getApplicationContext());
            Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), "");
            Log.i(TAG, "Xray-core initialized. Version: " + Libv2ray.checkVersionX());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize Xray environment: " + t.getMessage(), t);
        }
    }

    private void copyAssetFile(String fileName) {
        File dest = new File(getFilesDir(), fileName);
        if (!dest.exists() || dest.length() == 0) {
            try {
                InputStream in = getAssets().open(fileName);
                FileOutputStream out = new FileOutputStream(dest);
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
                out.close();
                in.close();
                Log.i(TAG, "Copied asset " + fileName + " (" + dest.length() + " bytes)");
            } catch (Exception e) {
                Log.e(TAG, "Error copying asset " + fileName + ": " + e.getMessage(), e);
            }
        }
    }

    public static VpnState getCurrentState() {
        return currentState;
    }

    public static VpnServer getCurrentServer() {
        return currentServer;
    }

    public static long getConnectedTimestamp() {
        return connectedTimestamp;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        activeInstance = this;
        if (intent == null) {
            // Android восстанавливает процесс после нехватки памяти (START_STICKY)
            if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
                updateNotification(null);
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_CONNECT.equals(action)) {
            VpnServer server = null;
            try {
                server = (VpnServer) intent.getSerializableExtra(EXTRA_SERVER);
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse EXTRA_SERVER from intent", e);
            }
            boolean restrictedTelegram = intent.getBooleanExtra(EXTRA_RESTRICTED_TELEGRAM_ONLY, false);
            boolean autoWaveSelect = intent.getBooleanExtra(EXTRA_AUTO_WAVE_SELECT, false);
            connect(server, restrictedTelegram, autoWaveSelect);
        } else if (ACTION_DISCONNECT.equals(action)) {
            disconnect();
        } else if (ACTION_RESTORE_NOTIFICATION.equals(action)) {
            if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
                updateNotification(null);
            }
        }

        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "onTaskRemoved: Activity task removed/swiped, keeping VPN foreground service active.");
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            updateNotification(null);
        }
        super.onTaskRemoved(rootIntent);
    }

    public synchronized void connect(final VpnServer server) {
        connect(server, false, false);
    }

    public synchronized void connect(final VpnServer server, final boolean restrictedTelegramOnly) {
        connect(server, restrictedTelegramOnly, false);
    }

    public synchronized void connect(final VpnServer server, final boolean restrictedTelegramOnly, final boolean autoWaveSelect) {
        final long session = activeSessionId.incrementAndGet();
        stopTunnel();
        activeSessionId.set(session);
        isExplicitUserDisconnect = false;
        this.isRestrictedTelegramOnly = restrictedTelegramOnly;
        final AtomicBoolean currentWaveCancel = new AtomicBoolean(false);
        this.waveCancelToken = currentWaveCancel;
        acquireWakeLocks();

        currentServer = server;
        final boolean needWaveResolution = (autoWaveSelect || server == null);
        connectionStageHint = needWaveResolution ? "Волна 1/4…" : null;
        updateState(VpnState.CONNECTING);
        createNotificationChannel();

        String connectingText = needWaveResolution
                ? "Подбор быстрого узла…"
                : ("Подключение к " + server.getDisplayName() + "…");
        Notification notification = buildNotification(connectingText);

        boolean foregroundStarted = false;
        if (Build.VERSION.SDK_INT >= 34) { // Android 14+ (UPSIDE_DOWN_CAKE)
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                foregroundStarted = true;
            } catch (Exception se) {
                Log.e(TAG, "Error starting foreground service with SPECIAL_USE: " + se.getMessage(), se);
                try {
                    startForeground(NOTIFICATION_ID, notification);
                    foregroundStarted = true;
                } catch (Exception fallbackEx) {
                    Log.e(TAG, "Fallback startForeground failed: " + fallbackEx.getMessage(), fallbackEx);
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification);
                foregroundStarted = true;
            } catch (Exception e) {
                Log.e(TAG, "Error starting foreground service: " + e.getMessage(), e);
            }
        }

        if (!foregroundStarted) {
            Log.e(TAG, "Could not start foreground service. Aborting connection.");
            connectionStageHint = null;
            releaseWakeLocks();
            updateState(VpnState.ERROR);
            stopSelf();
            return;
        }

        isRunning = true;
        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean initialHandshakeFailed = false;
                try {
                    // Ensure Xray core is initialized before wave probing or TUN creation
                    initXrayEnvironment();

                    VpnServer targetNode = server;
                    if (needWaveResolution) {
                        boolean isCellNow = WhitelistPoolManager.isRunningOnCellular(GoblinVpnService.this);
                        if (isCellNow) {
                            waveSelecting = true;
                            try {
                                targetNode = AutoSwitchEngine.getInstance(GoblinVpnService.this).selectCellularServerByWaves(
                                        null,
                                        new AutoSwitchEngine.WaveProgressListener() {
                                            @Override
                                            public void onWaveProgress(int waveIndex, int totalWaves, int batchSize) {
                                                if (isRunning && activeSessionId.get() == session && !currentWaveCancel.get()) {
                                                    setConnectionStageHint("Волна " + waveIndex + "/" + totalWaves + "…");
                                                }
                                            }
                                        },
                                        currentWaveCancel
                                );
                            } finally {
                                waveSelecting = false;
                            }
                        } else {
                            targetNode = AutoSwitchEngine.getInstance(GoblinVpnService.this).selectFallbackNonCellularServer();
                        }
                        connectionStageHint = null;
                        if (currentWaveCancel.get() || activeSessionId.get() != session || !isRunning || isExplicitUserDisconnect) {
                            return;
                        }
                        if (targetNode == null) {
                            AppLogger.e(TAG, "Wave selection yielded no target server. Stopping.");
                            isRunning = false;
                            updateState(VpnState.ERROR);
                            stopForegroundNotification();
                            stopSelf();
                            return;
                        }
                        currentServer = targetNode;
                        updateNotification(null);
                        broadcastStateOnly();
                    }

                    final VpnServer activeTarget = targetNode;

                    synchronized (tunnelLock) {
                        vpnInterface = establishTunInterface(activeTarget);
                    }

                    if (vpnInterface == null) {
                        Log.e(TAG, "Failed to establish VPN interface (null returned from establish)");
                        if (activeSessionId.get() == session) {
                            isRunning = false;
                            updateState(VpnState.ERROR);
                            stopForegroundNotification();
                            stopSelf();
                        }
                        return;
                    }

                    int tunFd = vpnInterface.getFd();
                    Log.i(TAG, "Virtual TUN interface established. FD=" + tunFd);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        try {
                            setUnderlyingNetworks(null);
                        } catch (Throwable ignored) {}
                    }

                    NetworkPreferences prefs = NetworkPreferences.getInstance(GoblinVpnService.this);
                    boolean bypassRu = prefs.isBypassRuTrafficEnabled();
                    int mtu = 1500;
                    String configJson = XrayConfigGenerator.generateXrayConfig(activeTarget, bypassRu, mtu);
                    Log.i(TAG, "Starting Xray-core loop with generated VLESS Reality configuration...");

                    synchronized (tunnelLock) {
                        if (activeSessionId.get() != session) {
                            return;
                        }
                        coreShutdownSignaled = false;
                        coreController = Libv2ray.newCoreController(new CoreCallbackHandler() {
                            @Override
                            public long startup() {
                                Log.i(TAG, "Xray-core startup callback");
                                return 0;
                            }

                            @Override
                            public long shutdown() {
                                Log.i(TAG, "Xray-core shutdown callback");
                                synchronized (tunnelLock) {
                                    coreShutdownSignaled = true;
                                    tunnelLock.notifyAll();
                                }
                                return 0;
                            }

                            @Override
                            public long onEmitStatus(long code, String msg) {
                                Log.d(TAG, "Xray status [code=" + code + "]: " + msg);
                                return 0;
                            }
                        });

                        coreController.startLoop(configJson, tunFd);
                    }

                    // =========================================================================
                    // FAST INITIAL HANDSHAKE WATCHDOG (Adaptive deadline: 5.5s probed / 9.0s cold cellular / 3.5s Wi-Fi)
                    // Parallelized multi-target in-tunnel verification (Telegram API, GStatic, Cloudflare)
                    // =========================================================================
                    boolean handshakeOk = false;
                    final long watchdogStartMs = SystemClock.elapsedRealtime();
                    final boolean isCellular = WhitelistPoolManager.isRunningOnCellular(GoblinVpnService.this);
                    final boolean hasHealthyProbe = activeTarget != null
                            && activeTarget.isAlive()
                            && activeTarget.getPingMs() > 0
                            && activeTarget.getPingMs() < 2000;
                    final long watchdogDeadlineMs = isCellular ? (hasHealthyProbe ? 5500L : 9000L) : 3500L;

                    AppLogger.i(TAG, "Starting Initial Handshake Watchdog (" + watchdogDeadlineMs + "ms deadline, cellular="
                            + isCellular + ") on node " + (activeTarget != null ? activeTarget.getDisplayName() + " [" + activeTarget.getHost() + ":" + activeTarget.getPort()
                            + "] (SNI=" + activeTarget.getSni() + ", Tier=" + activeTarget.getMobileTier() + ")" : "unknown"));

                    final AtomicInteger probeDelay = new AtomicInteger(-1);
                    final AtomicBoolean probeConfirmed = new AtomicBoolean(false);
                    final AtomicBoolean globalEgressConfirmed = new AtomicBoolean(false);

                    final ExecutorService probeExecutor = Executors.newFixedThreadPool(3);
                    final String[] cellularTargets = new String[]{
                            ServerPinger.TELEGRAM_API_URL,
                            ServerPinger.HTTP_204_GSTATIC,
                            ServerPinger.HTTP_204_CLOUDFLARE
                    };
                    final String[] wifiTargets = new String[]{
                            ServerPinger.HTTP_204_GSTATIC,
                            ServerPinger.TELEGRAM_API_URL,
                            ServerPinger.HTTP_204_CLOUDFLARE
                    };
                    final String[] targets = isCellular ? cellularTargets : wifiTargets;

                    for (final String targetUrl : targets) {
                        probeExecutor.execute(new Runnable() {
                            @Override
                            public void run() {
                                if (!isRunning || activeSessionId.get() != session) {
                                    return;
                                }
                                int d = measureActiveTunnelDelay(targetUrl);
                                if (d > 0) {
                                    AppLogger.i(TAG, "🔍 In-tunnel probe OK (" + targetUrl + "): " + d + "ms");
                                    globalEgressConfirmed.set(true);
                                    if (probeConfirmed.compareAndSet(false, true)) {
                                        probeDelay.set(d);
                                    }
                                } else {
                                    AppLogger.d(TAG, "🔍 In-tunnel probe FAILED (" + targetUrl + ")");
                                }
                            }
                        });
                    }
                    probeExecutor.shutdown();

                    // Adaptive polling up to deadline (high frequency 80ms for instant handover)
                    while (isRunning && activeSessionId.get() == session && (SystemClock.elapsedRealtime() - watchdogStartMs < watchdogDeadlineMs)) {
                        try {
                            Thread.sleep(80);
                        } catch (InterruptedException ie) {
                            break;
                        }

                        if (coreController != null && !coreController.getIsRunning()) {
                            AppLogger.w(TAG, "Xray core loop exited during handshake watchdog");
                            break;
                        }

                        if ((probeConfirmed.get() || probeDelay.get() > 0) && (!isCellular || globalEgressConfirmed.get())) {
                            handshakeOk = true;
                            AppLogger.i(TAG, "✅ Initial Handshake VERIFIED in " + (SystemClock.elapsedRealtime() - watchdogStartMs)
                                    + "ms! (probeDelay=" + probeDelay.get() + "ms, globalEgress=" + globalEgressConfirmed.get() + ")");
                            break;
                        }
                    }

                    if (activeSessionId.get() != session || !isRunning) {
                        return;
                    }

                    if (!handshakeOk) {
                        int finalDelay = probeDelay.get();
                        if (finalDelay > 0 && (!isCellular || globalEgressConfirmed.get())) {
                            handshakeOk = true;
                        }
                    }

                    if (handshakeOk) {
                        if (isCellular && activeTarget != null
                                && !ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileSni(activeTarget.getSni())
                                && !ru.llazyrin.goblinvpn.net.WhitelistPoolManager.isBlockedMobileHost(activeTarget.getHost())
                                && (globalEgressConfirmed.get() || !isDomesticOnlySni(activeTarget.getSni()))) {
                            ru.llazyrin.goblinvpn.security.MobileProvenConfigStorage.getInstance(GoblinVpnService.this)
                                    .recordSuccess(activeTarget, probeDelay.get());
                            ru.llazyrin.goblinvpn.net.WhitelistPoolManager.recordServerPing(activeTarget.getServerKey(), probeDelay.get());
                        }
                        if (activeTarget != null) {
                            ru.llazyrin.goblinvpn.net.ServerPenaltyManager.getInstance(GoblinVpnService.this).recordSuccess(activeTarget.getServerKey());
                        }
                        AutoSwitchEngine.getInstance(GoblinVpnService.this).notifyHandshakeSucceeded(activeTarget);
                    } else {
                        initialHandshakeFailed = true;
                        AppLogger.w(TAG, "❌ Initial Handshake FAILED after " + watchdogDeadlineMs
                                + "ms (target=" + (activeTarget != null ? activeTarget.getHost() + ":" + activeTarget.getPort() + ", SNI=" + activeTarget.getSni() : "null")
                                + ", cellular=" + isCellular + "). Triggering instant auto-failover!");
                        if (isCellular && activeTarget != null) {
                            ru.llazyrin.goblinvpn.security.MobileProvenConfigStorage.getInstance(GoblinVpnService.this)
                                    .recordFailure(activeTarget.getServerKey());
                        }
                        AutoSwitchEngine.getInstance(GoblinVpnService.this).onInitialConnectFailed(activeTarget);
                        return;
                    }

                    if (activeSessionId.get() == session) {
                        connectionStageHint = null;
                        updateState(VpnState.CONNECTED);
                        String ksSuffix = prefs.isKillSwitchEnabled() ? " • Kill Switch" : "";
                        String dnsSuffix = " • DNS: " + prefs.getDnsOption().getBadge();
                        updateNotification("Защищенный туннель активен (" + (activeTarget != null ? activeTarget.getDisplayName() : "VLESS Reality") + ")" + dnsSuffix + ksSuffix);
                        Log.i(TAG, "VPN tunnel actively routing traffic through Xray-core.");
                    }

                    // Monitor loop keeping connection alive (event-driven wait on tunnelLock)
                    while (isRunning && activeSessionId.get() == session) {
                        CoreController controllerSnapshot;
                        synchronized (tunnelLock) {
                            if (!isRunning || activeSessionId.get() != session || vpnInterface == null || coreShutdownSignaled) {
                                break;
                            }
                            controllerSnapshot = coreController;
                        }
                        if (controllerSnapshot != null && !controllerSnapshot.getIsRunning()) {
                            Log.w(TAG, "Xray core loop exited unexpectedly");
                            break;
                        }
                        synchronized (tunnelLock) {
                            if (!isRunning || activeSessionId.get() != session || vpnInterface == null || coreShutdownSignaled) {
                                break;
                            }
                            try {
                                tunnelLock.wait(6000L);
                            } catch (InterruptedException ie) {
                                break;
                            }
                        }
                    }

                } catch (Throwable t) {
                    Log.e(TAG, "Error in VPN worker thread", t);
                } finally {
                    if (initialHandshakeFailed) {
                        closeVpnInterface();
                        Log.i(TAG, "Worker thread session #" + session + " closed cleanly after initial handshake failover.");
                    } else if (activeSessionId.get() == session) {
                        closeVpnInterface();
                        if (isRunning && !isExplicitUserDisconnect) {
                            AppLogger.w(TAG, "Tunnel dropped unexpectedly while active! Triggering auto-recovery...");
                            AutoSwitchEngine.getInstance(GoblinVpnService.this)
                                    .onTunnelDroppedUnexpectedly(currentServer, "Обрыв соединения сотовой сетью (БС)");
                        } else if (isExplicitUserDisconnect) {
                            updateState(VpnState.DISCONNECTED);
                        }
                    } else {
                        Log.i(TAG, "Worker thread session #" + session + " terminated gracefully (superseded by session #" + activeSessionId.get() + ")");
                    }
                }
            }
        }, "GoblinVpnWorkerThread");
        workerThread.start();
    }

    /**
     * Robust establishment of Android VPN Virtual Interface (TUN).
     * Routes entire IPv4 traffic (0.0.0.0/0) through TUN so 2ip.ru shows the proxy IP.
     * Exempts llazyrinVPN package from tunnel to prevent loopback.
     */
    private ParcelFileDescriptor establishTunInterface(VpnServer server) {
        NetworkPreferences prefs = NetworkPreferences.getInstance(this);
        boolean killSwitch = prefs.isKillSwitchEnabled();
        NetworkPreferences.DnsOption dnsOption = prefs.getDnsOption();

        boolean isCellular = WhitelistPoolManager.isRunningOnCellular(this);
        int[] mtuCandidates = isCellular ? new int[] { 1380, 1360, 0 } : new int[] { 1500, 1400, 0 };

        for (int mtu : mtuCandidates) {
            try {
                Builder builder = new Builder();
                String sessionTitle = "llazyrinVPN (" + (server != null ? server.getCountry() : "Авто") + ")";
                builder.setSession(sessionTitle);
                builder.addAddress("172.19.0.1", 30);

                // ROUTE ALL TRAFFIC INTO TUN INTERFACE
                builder.addRoute("0.0.0.0", 0);

                // IPv6 Routing: Do NOT capture ::/0 on cellular networks (prevents Happy Eyeballs blackhole)
                if (!isCellular) {
                    try {
                        builder.addAddress("fdfe:dcba:9876::1", 126);
                        builder.addRoute("::", 0);
                    } catch (Exception ignored) {}
                }

                // Per-App Proxy / Split Tunneling & Restricted Routing configuration
                boolean restrictedMode = this.isRestrictedTelegramOnly || prefs.isRestrictedTelegramOnly();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (restrictedMode) {
                        AppLogger.i(TAG, "Restricted Telegram-Only mode active. Only Telegram apps and llazyrinVPN routed.");
                        String[] telegramApps = new String[]{
                                "org.telegram.messenger",
                                "org.telegram.plus",
                                "org.thunderdog.challegram",
                                "org.telegram.messenger.web"
                        };
                        int allowedTelegramCount = 0;
                        for (String pkg : telegramApps) {
                            try {
                                builder.addAllowedApplication(pkg);
                                allowedTelegramCount++;
                            } catch (Exception e) {
                                AppLogger.w(TAG, "Restricted Telegram-Only: package " + pkg + " not installed: " + e.getMessage());
                            }
                        }
                        try {
                            builder.addDisallowedApplication(getPackageName());
                        } catch (Exception ignored) {}
                        AppLogger.i(TAG, "Restricted Telegram-Only mode configured for " + allowedTelegramCount + " packages.");
                    } else {
                        // Regular split tunneling configuration
                        NetworkPreferences.AppRoutingMode routingMode = prefs.getAppRoutingMode();
                        java.util.Set<String> routedPackages = prefs.getRoutedPackages();

                        if (routingMode == NetworkPreferences.AppRoutingMode.ONLY_SELECTED) {
                            // ВКЛ: Только выбранные приложения идут через прокси
                            int allowedCount = 0;
                            if (routedPackages != null && !routedPackages.isEmpty()) {
                                for (String pkg : routedPackages) {
                                    if (pkg != null && !pkg.trim().isEmpty() && !pkg.equals(getPackageName())) {
                                        try {
                                            builder.addAllowedApplication(pkg.trim());
                                            allowedCount++;
                                        } catch (Exception e) {
                                            AppLogger.w(TAG, "Failed to addAllowedApplication (" + pkg + "): " + e.getMessage());
                                        }
                                    }
                                }
                            }
                            try {
                                builder.addDisallowedApplication(getPackageName());
                            } catch (Exception ignored) {}
                            AppLogger.i(TAG, "Per-App Proxy [ВКЛ]: туннель активен ТОЛЬКО для " + allowedCount + " выбранных приложений");
                        } else if (routingMode == NetworkPreferences.AppRoutingMode.BYPASS_SELECTED) {
                            // ОБХОД: Выбранные приложения обходят прокси и идут напрямую
                            try {
                                builder.addDisallowedApplication(getPackageName());
                            } catch (Exception ignored) {}

                            int bypassCount = 0;
                            if (routedPackages != null && !routedPackages.isEmpty()) {
                                for (String pkg : routedPackages) {
                                    if (pkg != null && !pkg.trim().isEmpty() && !pkg.equals(getPackageName())) {
                                        try {
                                            builder.addDisallowedApplication(pkg.trim());
                                            bypassCount++;
                                        } catch (Exception e) {
                                            AppLogger.w(TAG, "Failed to addDisallowedApplication (" + pkg + "): " + e.getMessage());
                                        }
                                    }
                                }
                            }
                            AppLogger.i(TAG, "Per-App Proxy [ОБХОД]: " + bypassCount + " приложений пущены НАПРЯМУЮ в обход VPN");
                        } else {
                            // ВЫКЛ: Все приложения идут через прокси
                            try {
                                builder.addDisallowedApplication(getPackageName());
                            } catch (Exception ignored) {}
                            AppLogger.i(TAG, "Per-App Proxy [ВЫКЛ]: полный туннель для всех приложений устройства");
                        }
                    }
                }

                if (killSwitch) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            builder.setBlocking(true);
                            Log.i(TAG, "Kill Switch active: builder.setBlocking(true)");
                        } catch (Exception e) {
                            Log.w(TAG, "Kill Switch configuration failed: " + e.getMessage());
                        }
                    }
                }

                // DNS configuration
                List<String> dnsList = new ArrayList<>();
                String[] configuredDns = dnsOption.getServers();
                if (configuredDns != null) {
                    for (String dns : configuredDns) {
                        if (dns != null && !dnsList.contains(dns)) {
                            dnsList.add(dns);
                        }
                    }
                }
                if (!dnsList.contains("1.1.1.1")) dnsList.add("1.1.1.1");
                if (!dnsList.contains("8.8.8.8")) dnsList.add("8.8.8.8");

                for (String dns : dnsList) {
                    try {
                        builder.addDnsServer(dns);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to configure DNS " + dns + ": " + e.getMessage());
                    }
                }

                if (mtu > 0) {
                    builder.setMtu(mtu);
                }

                try {
                    Intent contentIntent = new Intent(this, MainActivity.class);
                    contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    PendingIntent piConfig = PendingIntent.getActivity(
                            this, 0, contentIntent,
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
                    );
                    builder.setConfigureIntent(piConfig);
                } catch (Exception ignored) {}

                ParcelFileDescriptor pfd = builder.establish();
                if (pfd != null) {
                    Log.i(TAG, "TUN interface established successfully with MTU " + (mtu > 0 ? mtu : "default"));
                    return pfd;
                }
            } catch (Exception e) {
                Log.w(TAG, "Exception with MTU " + mtu + ": " + e.getMessage() + ", trying fallback...");
            }
        }
        return null;
    }

    /**
     * Stop active worker thread and close tunnel without calling stopSelf().
     */
    private synchronized void stopTunnel() {
        activeSessionId.incrementAndGet();
        isRunning = false;
        waveSelecting = false;
        try {
            if (waveCancelToken != null) waveCancelToken.set(true);
        } catch (Throwable ignored) {}
        synchronized (tunnelLock) {
            tunnelLock.notifyAll();
        }
        if (workerThread != null) {
            try {
                workerThread.interrupt();
            } catch (Exception ignored) {}
            workerThread = null;
        }
        closeVpnInterface();
        releaseWakeLocks();
    }

    /**
     * Cleanly terminates the VPN connection attempt in ERROR state without switching servers
     * (used when auto-switch is disabled or manual server is locked).
     */
    public static void onPinnedConnectionFailed(final VpnServer failedServer, final String errorMessage) {
        final GoblinVpnService svc = activeInstance;
        if (svc == null) return;
        svc.isRunning = false;
        connectionStageHint = null;
        svc.activeSessionId.incrementAndGet();
        svc.stopForegroundNotification();
        svc.releaseWakeLocks();
        currentServer = failedServer;
        svc.updateState(VpnState.ERROR);
        if (errorMessage != null && !errorMessage.isEmpty()) {
            try {
                Intent broadcast = new Intent(ACTION_STATE_CHANGED);
                broadcast.setPackage(svc.getPackageName());
                broadcast.putExtra(EXTRA_STATE, VpnState.ERROR.name());
                broadcast.putExtra(EXTRA_ERROR_MESSAGE, errorMessage);
                if (failedServer != null) {
                    broadcast.putExtra(EXTRA_SERVER, failedServer);
                }
                svc.sendBroadcast(broadcast);
            } catch (Exception ignored) {}
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                svc.stopTunnel();
                svc.stopSelf();
            }
        }, "GoblinVpn-PinnedFail").start();
    }

    /**
     * Safely disconnects the VPN service, closes ParcelFileDescriptor,
     * removes foreground notifications, and stops the service.
     */
    public void disconnect() {
        Log.i(TAG, "Disconnecting llazyrinVPN service asynchronously...");
        isExplicitUserDisconnect = true;
        isRunning = false;
        connectionStageHint = null;
        try {
            if (waveCancelToken != null) waveCancelToken.set(true);
        } catch (Throwable ignored) {}
        activeSessionId.incrementAndGet();
        updateState(VpnState.DISCONNECTED);
        stopForegroundNotification();
        releaseWakeLocks();

        new Thread(new Runnable() {
            @Override
            public void run() {
                stopTunnel();
                stopSelf();
            }
        }, "GoblinVpn-Disconnect").start();
    }

    /**
     * Safely closes the ParcelFileDescriptor and terminates Xray core loop.
     */
    private void closeVpnInterface() {
        CoreController controllerToStop;
        ParcelFileDescriptor pfdToClose;
        synchronized (tunnelLock) {
            controllerToStop = coreController;
            coreController = null;
            pfdToClose = vpnInterface;
            vpnInterface = null;
            coreShutdownSignaled = true;
            tunnelLock.notifyAll();
        }

        if (controllerToStop != null) {
            try {
                Log.i(TAG, "Stopping Xray core loop...");
                controllerToStop.stopLoop();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping core loop: " + e.getMessage());
            }
        }

        if (pfdToClose != null) {
            try {
                Log.d(TAG, "Closing ParcelFileDescriptor");
                pfdToClose.close();
            } catch (IOException e) {
                Log.w(TAG, "IOException closing vpnInterface: " + e.getMessage(), e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error closing vpnInterface: " + e.getMessage(), e);
            }
        }
    }

    private void stopForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error stopping foreground: " + e.getMessage(), e);
        }

        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "VPN service revoked by system");
        disconnect();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy() called");
        MAIN_HANDLER.removeCallbacks(notifTrafficRunnable);
        MAIN_HANDLER.removeCallbacks(wakeLockSafetyTimeoutRunnable);
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver);
            } catch (Exception ignored) {
            } finally {
                isScreenReceiverRegistered = false;
            }
        }
        activeSessionId.incrementAndGet();
        stopTunnel();
        releaseWakeLocks();
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onDestroy();
    }

    private void updateState(final VpnState state) {
        currentState = state;
        if (state == VpnState.CONNECTED) {
            releaseWakeLocks();
            if (connectedTimestamp == 0) {
                connectedTimestamp = System.currentTimeMillis();
            }
            long curRx = TrafficStats.getUidRxBytes(Process.myUid());
            long curTx = TrafficStats.getUidTxBytes(Process.myUid());
            initialUidRxBytes = (curRx != TrafficStats.UNSUPPORTED) ? curRx : 0;
            initialUidTxBytes = (curTx != TrafficStats.UNSUPPORTED) ? curTx : 0;
            lastNotifRx = initialUidRxBytes;
            lastNotifTx = initialUidTxBytes;
            lastNotifTimeMs = SystemClock.elapsedRealtime();
            lastSpeedRx = 0;
            lastSpeedTx = 0;
            lastWasIdleZero = false;
            MAIN_HANDLER.removeCallbacks(notifTrafficRunnable);
            if (isScreenOn) {
                MAIN_HANDLER.postDelayed(notifTrafficRunnable, NOTIF_TRAFFIC_INTERVAL_MS);
            }

            AppLogger.i(TAG, "VPN CONNECTED! Node: " + (currentServer != null ? currentServer.getDisplayName() : "Auto")
                    + " (" + (currentServer != null ? currentServer.getHost() : "") + ")");
            AppTelemetryClient.sendHeartbeat(this, true, currentServer);
            AppLogger.flushIfPending();
            try {
                AutoSwitchEngine.getInstance(this).onVpnConnected(currentServer);
            } catch (Exception e) {
                Log.w(TAG, "Failed to notify AutoSwitchEngine on connected: " + e.getMessage());
            }
        } else if (state == VpnState.DISCONNECTED) {
            releaseWakeLocks();
            connectedTimestamp = 0;
            initialUidRxBytes = 0;
            initialUidTxBytes = 0;
            lastNotifRx = 0;
            lastNotifTx = 0;
            lastNotifTimeMs = 0;
            lastSpeedRx = 0;
            lastSpeedTx = 0;
            lastWasIdleZero = false;
            MAIN_HANDLER.removeCallbacks(notifTrafficRunnable);
            AppLogger.i(TAG, "VPN DISCONNECTED");
            AppTelemetryClient.sendHeartbeat(this, false, null);
            try {
                AutoSwitchEngine.getInstance(this).onVpnDisconnected();
            } catch (Exception e) {
                Log.w(TAG, "Failed to notify AutoSwitchEngine on disconnected: " + e.getMessage());
            }
        } else if (state == VpnState.ERROR) {
            releaseWakeLocks();
            connectedTimestamp = 0;
            initialUidRxBytes = 0;
            initialUidTxBytes = 0;
            lastNotifRx = 0;
            lastNotifTx = 0;
            lastNotifTimeMs = 0;
            lastSpeedRx = 0;
            lastSpeedTx = 0;
            lastWasIdleZero = false;
            MAIN_HANDLER.removeCallbacks(notifTrafficRunnable);
            AppLogger.e(TAG, "VPN ERROR encountered");
            AppTelemetryClient.sendHeartbeat(this, false, null);
            try {
                AutoSwitchEngine.getInstance(this).onVpnDisconnected();
            } catch (Exception e) {
                Log.w(TAG, "Failed to notify AutoSwitchEngine on error: " + e.getMessage());
            }
        } else if (state == VpnState.CONNECTING) {
            lastNotifRx = 0;
            lastNotifTx = 0;
            lastNotifTimeMs = 0;
            lastSpeedRx = 0;
            lastSpeedTx = 0;
            lastWasIdleZero = false;
            MAIN_HANDLER.removeCallbacks(notifTrafficRunnable);
            if (isScreenOn) {
                MAIN_HANDLER.post(notifTrafficRunnable);
            }
            AppLogger.i(TAG, "VPN CONNECTING to " + (currentServer != null ? currentServer.getDisplayName() : "Auto") + "...");
        }

        final StateListener listener = stateListener;
        if (listener != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                try {
                    listener.onStateChanged(state, currentServer);
                } catch (Exception e) {
                    Log.e(TAG, "Error in stateListener on main thread: " + e.getMessage(), e);
                }
            } else {
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        StateListener l = stateListener;
                        if (l != null) {
                            try {
                                l.onStateChanged(state, currentServer);
                            } catch (Exception e) {
                                Log.e(TAG, "Error in stateListener callback: " + e.getMessage(), e);
                            }
                        }
                    }
                });
            }
        }

        try {
            Intent broadcast = new Intent(ACTION_STATE_CHANGED);
            broadcast.setPackage(getPackageName());
            broadcast.putExtra(EXTRA_STATE, state.name());
            if (currentServer != null) {
                broadcast.putExtra(EXTRA_SERVER, currentServer);
            }
            sendBroadcast(broadcast);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send state broadcast: " + e.getMessage(), e);
        }
    }

    private void broadcastStateOnly() {
        final VpnState state = currentState;
        final VpnServer srv = currentServer;
        final StateListener listener = stateListener;
        if (listener != null) {
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    StateListener l = stateListener;
                    if (l != null) {
                        try {
                            l.onStateChanged(state, srv);
                        } catch (Exception ignored) {}
                    }
                }
            });
        }
        try {
            Intent broadcast = new Intent(ACTION_STATE_CHANGED);
            broadcast.setPackage(getPackageName());
            broadcast.putExtra(EXTRA_STATE, state.name());
            if (srv != null) {
                broadcast.putExtra(EXTRA_SERVER, srv);
            }
            sendBroadcast(broadcast);
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "llazyrinVPN Service",
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                channel.setDescription("Уведомления о состоянии защищенного VPN-канала");
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.setShowBadge(true);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to create NotificationChannel", e);
            }
        }
    }

    private Notification buildNotification(String text) {
        return buildNotification(text, true);
    }

    private long[] refreshTrafficStats() {
        long curRx = TrafficStats.getUidRxBytes(Process.myUid());
        long curTx = TrafficStats.getUidTxBytes(Process.myUid());
        if (curRx == TrafficStats.UNSUPPORTED) curRx = 0;
        if (curTx == TrafficStats.UNSUPPORTED) curTx = 0;

        if (currentState == VpnState.CONNECTED) {
            if (initialUidRxBytes <= 0 && curRx > 0) initialUidRxBytes = curRx;
            if (initialUidTxBytes <= 0 && curTx > 0) initialUidTxBytes = curTx;

            long now = SystemClock.elapsedRealtime();
            long deltaMs = (lastNotifTimeMs > 0) ? (now - lastNotifTimeMs) : 0;
            if (deltaMs >= 500 && lastNotifRx > 0 && curRx >= lastNotifRx) {
                lastSpeedRx = (curRx - lastNotifRx) * 1000 / deltaMs;
            } else if (lastNotifRx == 0 || deltaMs >= 500) {
                lastSpeedRx = 0;
            }
            if (deltaMs >= 500 && lastNotifTx > 0 && curTx >= lastNotifTx) {
                lastSpeedTx = (curTx - lastNotifTx) * 1000 / deltaMs;
            } else if (lastNotifTx == 0 || deltaMs >= 500) {
                lastSpeedTx = 0;
            }

            lastNotifRx = curRx;
            lastNotifTx = curTx;
            lastNotifTimeMs = now;
        } else {
            lastSpeedRx = 0;
            lastSpeedTx = 0;
        }

        return new long[]{curRx, curTx};
    }

    private Notification buildNotification(String text, boolean refreshStats) {
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piContent = PendingIntent.getActivity(
                this, 0, contentIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent dcIntent = new Intent(this, GoblinVpnService.class);
        dcIntent.setAction(ACTION_DISCONNECT);
        PendingIntent piDisconnect = PendingIntent.getService(
                this, 1, dcIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        String disconnectLabel = "Отключить";
        try {
            disconnectLabel = getString(R.string.vpn_action_disconnect);
        } catch (Exception ignored) {}

        VpnServer s = currentServer;
        String flag = (s != null && s.getFlag() != null && !s.getFlag().isEmpty()) ? s.getFlag() : "🌐";
        String country = (s != null && s.getCountry() != null && !s.getCountry().isEmpty()) ? s.getCountry() : "Автовыбор";
        String dispName = (s != null && s.getDisplayName() != null) ? s.getDisplayName() : "VLESS Reality";

        int ping = lastKnownPing;
        if (ping <= 0 && s != null && s.getPingMs() > 0) {
            ping = s.getPingMs();
        }

        String statusText;
        if (currentState == VpnState.CONNECTED) {
            statusText = "llazyrinVPN: Защита активна";
        } else if (currentState == VpnState.CONNECTING) {
            statusText = "llazyrinVPN: Подключение...";
        } else {
            statusText = "llazyrinVPN: Отключено";
        }

        String pingText = (currentState == VpnState.CONNECTED && ping > 0) ? (" • ⚡ " + ping + " мс") : "";
        String notifContent = statusText + " • " + flag + " " + country + " (" + dispName + ")" + pingText;

        builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(piContent)
                .setOngoing(currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_MAX)
                .setContentTitle("llazyrinVPN")
                .setContentText(statusText)
                .setSubText("llazyrinVPN")
                .setOnlyAlertOnce(true)
                .setTicker(statusText);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        Intent restoreIntent = new Intent(this, GoblinVpnService.class);
        restoreIntent.setAction(ACTION_RESTORE_NOTIFICATION);
        PendingIntent piDelete = PendingIntent.getService(
                this, 999, restoreIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );
        builder.setDeleteIntent(piDelete);

        if (connectedTimestamp > 0 && currentState == VpnState.CONNECTED) {
            builder.setWhen(connectedTimestamp);
            builder.setShowWhen(true);
        }

        // Calculate real traffic stats and instant speeds (TrafficStats)
        long curRx;
        long curTx;
        if (refreshStats) {
            long[] rxTx = refreshTrafficStats();
            curRx = rxTx[0];
            curTx = rxTx[1];
        } else {
            curRx = lastNotifRx;
            curTx = lastNotifTx;
        }

        long sessionRx = (currentState == VpnState.CONNECTED && curRx >= initialUidRxBytes && initialUidRxBytes > 0)
                ? (curRx - initialUidRxBytes) : 0;
        long sessionTx = (currentState == VpnState.CONNECTED && curTx >= initialUidTxBytes && initialUidTxBytes > 0)
                ? (curTx - initialUidTxBytes) : 0;

        // Custom Minimalistic Notification Widget RemoteViews (Reference style)
        try {
            // Collapsed RemoteViews (strictly 64dp height for Android notifications)
            RemoteViews rvCollapsed = new RemoteViews(getPackageName(), R.layout.notification_vpn_widget_collapsed);
            // Expanded RemoteViews (big view with complete stats)
            RemoteViews rvExpanded = new RemoteViews(getPackageName(), R.layout.notification_vpn_widget);

            // Left App Icon
            rvCollapsed.setImageViewResource(R.id.notifAppIcon, R.drawable.ic_launcher);
            rvExpanded.setImageViewResource(R.id.notifAppIcon, R.drawable.ic_launcher);

            // Title: llazyrinVPN (bold crisp white)
            rvCollapsed.setTextViewText(R.id.notifAppTitle, "llazyrinVPN");
            rvCollapsed.setTextColor(R.id.notifAppTitle, Color.parseColor("#FFFFFF"));
            rvExpanded.setTextViewText(R.id.notifAppTitle, "llazyrinVPN");
            rvExpanded.setTextColor(R.id.notifAppTitle, Color.parseColor("#FFFFFF"));

            // Subtitle / Status: "Подключено • Варшава" (or server city/country)
            String serverName = "Варшава";
            if (s != null) {
                if (s.getCity() != null && !s.getCity().isEmpty()) {
                    serverName = s.getCity();
                } else if (s.getCountry() != null && !s.getCountry().isEmpty()) {
                    serverName = s.getCountry();
                }
            }

            if (currentState == VpnState.CONNECTED) {
                String connectedStr = "Подключено • " + serverName;
                rvCollapsed.setTextViewText(R.id.notifStatusText, connectedStr);
                rvCollapsed.setTextColor(R.id.notifStatusText, Color.parseColor("#94A3B8"));
                rvExpanded.setTextViewText(R.id.notifStatusText, connectedStr);
                rvExpanded.setTextColor(R.id.notifStatusText, Color.parseColor("#94A3B8"));

                String speedStr = "↓ " + NotificationCenter.formatSpeed(lastSpeedRx) + " • ↑ " + NotificationCenter.formatSpeed(lastSpeedTx);
                rvCollapsed.setTextViewText(R.id.notifTrafficText, speedStr);

                String rxStr = "Получено: " + NotificationCenter.formatBytes(sessionRx) + " (↓ " + NotificationCenter.formatSpeed(lastSpeedRx) + ")";
                String txStr = "Передано: " + NotificationCenter.formatBytes(sessionTx) + " (↑ " + NotificationCenter.formatSpeed(lastSpeedTx) + ")";
                rvExpanded.setTextViewText(R.id.notifTrafficRx, rxStr);
                rvExpanded.setTextViewText(R.id.notifTrafficTx, txStr);

                // Quick Action Pill Button (Отключить)
                rvCollapsed.setTextViewText(R.id.notifBtnAction, "Отключить");
                rvCollapsed.setTextColor(R.id.notifBtnAction, Color.parseColor("#FFFFFF"));
                rvCollapsed.setOnClickPendingIntent(R.id.notifBtnAction, piDisconnect);

                rvExpanded.setTextViewText(R.id.notifBtnAction, "Отключить");
                rvExpanded.setTextColor(R.id.notifBtnAction, Color.parseColor("#FFFFFF"));
                rvExpanded.setOnClickPendingIntent(R.id.notifBtnAction, piDisconnect);
            } else if (currentState == VpnState.CONNECTING) {
                String hint = connectionStageHint;
                String connectingStr = (hint != null && !hint.isEmpty())
                        ? ("Подбор • " + hint)
                        : ("Подключение • " + serverName);
                rvCollapsed.setTextViewText(R.id.notifStatusText, connectingStr);
                rvCollapsed.setTextColor(R.id.notifStatusText, Color.parseColor("#FBBF24"));
                rvExpanded.setTextViewText(R.id.notifStatusText, connectingStr);
                rvExpanded.setTextColor(R.id.notifStatusText, Color.parseColor("#FBBF24"));

                String detail = (hint != null && !hint.isEmpty()) ? "Параллельный пинг (5 узлов)…" : "Настройка туннеля...";
                rvCollapsed.setTextViewText(R.id.notifTrafficText, detail);
                rvExpanded.setTextViewText(R.id.notifTrafficRx, "Получено: 0 Б");
                rvExpanded.setTextViewText(R.id.notifTrafficTx, "Передано: 0 Б");

                rvCollapsed.setTextViewText(R.id.notifBtnAction, "Отмена");
                rvCollapsed.setTextColor(R.id.notifBtnAction, Color.parseColor("#FFFFFF"));
                rvCollapsed.setOnClickPendingIntent(R.id.notifBtnAction, piDisconnect);

                rvExpanded.setTextViewText(R.id.notifBtnAction, "Отмена");
                rvExpanded.setTextColor(R.id.notifBtnAction, Color.parseColor("#FFFFFF"));
                rvExpanded.setOnClickPendingIntent(R.id.notifBtnAction, piDisconnect);
            } else {
                rvCollapsed.setTextViewText(R.id.notifStatusText, "Отключено");
                rvCollapsed.setTextColor(R.id.notifStatusText, Color.parseColor("#94A3B8"));
                rvExpanded.setTextViewText(R.id.notifStatusText, "Отключено");
                rvExpanded.setTextColor(R.id.notifStatusText, Color.parseColor("#94A3B8"));

                rvCollapsed.setTextViewText(R.id.notifTrafficText, "Туннель выключен");
                rvExpanded.setTextViewText(R.id.notifTrafficRx, "Получено: 0 Б");
                rvExpanded.setTextViewText(R.id.notifTrafficTx, "Передано: 0 Б");

                rvCollapsed.setTextViewText(R.id.notifBtnAction, "Подключить");
                rvCollapsed.setTextColor(R.id.notifBtnAction, Color.parseColor("#FFFFFF"));
                rvCollapsed.setOnClickPendingIntent(R.id.notifBtnAction, piContent);

                rvExpanded.setTextViewText(R.id.notifBtnAction, "Подключить");
                rvExpanded.setTextColor(R.id.notifBtnAction, Color.parseColor("#FFFFFF"));
                rvExpanded.setOnClickPendingIntent(R.id.notifBtnAction, piContent);
            }

            // Click entire notification body to open app
            rvCollapsed.setOnClickPendingIntent(R.id.notifRoot, piContent);
            rvCollapsed.setOnClickPendingIntent(R.id.notifAppTitle, piContent);
            rvCollapsed.setOnClickPendingIntent(R.id.notifAppIcon, piContent);
            rvCollapsed.setOnClickPendingIntent(R.id.notifStatusText, piContent);
            rvCollapsed.setOnClickPendingIntent(R.id.notifTrafficText, piContent);

            rvExpanded.setOnClickPendingIntent(R.id.notifRoot, piContent);
            rvExpanded.setOnClickPendingIntent(R.id.notifAppTitle, piContent);
            rvExpanded.setOnClickPendingIntent(R.id.notifAppIcon, piContent);
            rvExpanded.setOnClickPendingIntent(R.id.notifStatusText, piContent);
            rvExpanded.setOnClickPendingIntent(R.id.notifTrafficRx, piContent);
            rvExpanded.setOnClickPendingIntent(R.id.notifTrafficTx, piContent);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setCustomContentView(rvCollapsed);
                builder.setCustomBigContentView(rvExpanded);
                builder.setStyle(new Notification.DecoratedCustomViewStyle());
            } else {
                builder.setContent(rvCollapsed);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error building custom notification RemoteViews: " + t.getMessage(), t);
        }

        Notification notification = builder.build();
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR | Notification.FLAG_FOREGROUND_SERVICE;
        }
        return notification;
    }

    private void updateNotification(String text) {
        updateNotification(text, false);
    }

    private void updateNotification(String text, boolean skipIfConsecutiveIdleZero) {
        try {
            refreshTrafficStats();
            if (currentState == VpnState.CONNECTED) {
                boolean isIdleZero = (lastSpeedRx == 0 && lastSpeedTx == 0);
                if (skipIfConsecutiveIdleZero && isIdleZero && lastWasIdleZero) {
                    return;
                }
                lastWasIdleZero = isIdleZero;
            } else {
                lastWasIdleZero = false;
            }

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(text, false));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to update notification: " + e.getMessage(), e);
        }
    }

    private static boolean isDomesticOnlySni(String sni) {
        if (sni == null || sni.trim().isEmpty()) return false;
        String s = sni.trim().toLowerCase(Locale.ROOT);
        return s.contains("wholebeam.com") || s.contains("vk.com") || s.contains("ok.ru") || s.contains("mail.ru");
    }
}
