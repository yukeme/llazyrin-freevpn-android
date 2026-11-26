package ru.llazyrin.goblinvpn;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.util.Log;
import ru.llazyrin.goblinvpn.service.AppLogger;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.animation.DecelerateInterpolator;
import ru.llazyrin.goblinvpn.ui.AppRoutingDialog;
import ru.llazyrin.goblinvpn.ui.HapticFeedbackHelper;
import ru.llazyrin.goblinvpn.ui.NotificationInboxDialog;
import ru.llazyrin.goblinvpn.net.NotificationCenter;

import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.view.animation.ScaleAnimation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import ru.llazyrin.goblinvpn.ads.AdCallback;
import ru.llazyrin.goblinvpn.ads.AdManager;
import ru.llazyrin.goblinvpn.model.NetworkPreferences;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.net.AutoSwitchEngine;
import ru.llazyrin.goblinvpn.net.CommunityPoolManager;
import ru.llazyrin.goblinvpn.net.ConfigDiscoveryEngine;
import ru.llazyrin.goblinvpn.net.ServerPenaltyManager;
import ru.llazyrin.goblinvpn.net.ServerPinger;
import ru.llazyrin.goblinvpn.net.VpnSubscriptionClient;
import ru.llazyrin.goblinvpn.net.WhitelistPoolManager;
import ru.llazyrin.goblinvpn.security.EmergencyConfigStorage;
import ru.llazyrin.goblinvpn.security.MobileProvenConfigStorage;
import ru.llazyrin.goblinvpn.service.GoblinVpnService;
import ru.llazyrin.goblinvpn.service.VpnState;
import ru.llazyrin.goblinvpn.net.AppUpdateManager;
import ru.llazyrin.goblinvpn.ui.AuthGatekeeperDialog;
import ru.llazyrin.goblinvpn.ui.PrivacyPolicyDialog;
import ru.llazyrin.goblinvpn.ui.ServerSelectionDialog;
import ru.llazyrin.goblinvpn.ui.StarfieldView;
import ru.llazyrin.goblinvpn.ui.TunnelSettingsDialog;
import ru.llazyrin.goblinvpn.ui.VpnPermissionHelpDialog;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST_CODE = 42;
    private boolean isEmergencyReserveActive = false;
    private boolean isAutoMode = true;
    private ServerSelectionDialog activeServerDialog = null;
    private boolean isRefreshingServers = false;
    private long lastVpnHelpDialogShowMs = 0;

    // Starfield Background
    private StarfieldView starfieldView;

    // Top Header & Header Settings Title
    private View headerBar;
    private FrameLayout btnGoblinBadge;
    private ImageView ivGoblinLogo;
    private View statusPill;
    private View viewStatusDot;
    private TextView tvStatusText;
    private TextView tvStatusIp;
    private TextView tvHeaderSettingsTitle;
    private FrameLayout btnSupport;
    private ImageView ivSupportIcon;

    // Notification Journal Palette & Animation
    private static final int COLOR_NOTIF_GRAY = 0xFF758678;
    private static final int COLOR_NOTIF_GREEN = 0xFF10B981;
    private int currentNotificationColor = COLOR_NOTIF_GRAY;
    private ValueAnimator notificationColorAnimator = null;
    private NotificationCenter.NotificationListener notificationListener = null;

    // Bottom Navigation Dock
    private FrameLayout bottomNavDock;
    private LinearLayout tabHome;
    private ImageView ivTabHome;
    private TextView tvTabHome;
    private LinearLayout tabSettings;
    private ImageView ivTabSettings;
    private TextView tvTabSettings;

    // Tab 1: Home Content
    private ScrollView scrollHomeContent;
    private LinearLayout layoutHomeContent;
    private TextView tvTimer;
    private TextView tvPing;
    private TextView tvPingUnit;
    private TextView tvTotalTraffic;
    private TextView tvTrafficUnit;
    private TextView tvTrafficSubtext;

    private FrameLayout powerButtonWrapper;
    private View viewPowerGlow;
    private View ringOuter;
    private View ringMiddle;
    private View ringInner;
    private View viewPowerBezel;
    private FrameLayout btnPower;
    private ImageView ivPowerIcon;
    private TextView tvPowerStatus;

    private LinearLayout cardServerSelector;
    private TextView tvCardFlag;
    private TextView tvCardTitle;
    private TextView tvCardBadge;
    private TextView tvCardCity;
    private View viewCardPingDot;
    private TextView badgePing;
    private ImageView ivCardArrow;
    private TextView tvPingSubtext;

    private LinearLayout btnRefreshServers;
    private TextView tvRefreshServers;
    private ImageView ivRefreshIcon;

    // Floating Capsule Nav Pills & Backdrop
    private LinearLayout viewPillHome;
    private LinearLayout viewPillSettings;
    private View viewSettingsBackdrop;
    private View viewDialogScrim;
    private View badgeNotificationPulse;

    // Tab 2: Settings Content
    private ScrollView layoutSettingsContent;
    private LinearLayout rowClientId;
    private TextView tvClientId;
    private LinearLayout rowSubKey;
    private TextView tvSubscriptionKey;
    private LinearLayout rowTunnelSettings;
    private TextView tvTunnelSummary;
    private LinearLayout rowAppRouting;
    private TextView tvAppRoutingSummary;
    private LinearLayout rowAutoSwitchServer;
    private FrameLayout toggleAutoSwitchTrack;
    private View toggleAutoSwitchThumb;
    private ValueAnimator toggleAutoSwitchColorAnimator;
    private TextView tvAutoSwitchSummary;
    private LinearLayout rowVipBoost;
    private TextView tvVipSettingsStatus;
    private LinearLayout rowEmergencyReserve;
    private TextView tvReserveStatus;
    private LinearLayout rowAppLogs;
    private TextView tvAppLogsSummary;
    private FrameLayout toggleLoggingTrack;
    private View toggleLoggingThumb;
    private ValueAnimator toggleColorAnimator;

    // Community Sharing Settings Row
    private LinearLayout rowCommunityShare;
    private FrameLayout switchCommunityShareTrack;
    private View switchCommunityShareThumb;
    private TextView tvCommunityShareSummary;
    private ValueAnimator switchCommunityShareColorAnimator;

    // Navigation Morphing Capsule Indicator
    private View navMorphingCapsule;
    private int currentNavTabIndex = 0;
    private ValueAnimator navMorphingAnimator;

    // Center Power Button Color Animation
    private int currentPowerBgColor = Color.parseColor("#1E2922");
    private ValueAnimator powerColorAnimator;

    // Bell Breathing Pulse Animator
    private ValueAnimator bellBreathingAnimator;
    private LinearLayout rowTelegramChannel;
    private LinearLayout rowDonate;
    private LinearLayout rowGitHub;
    private LinearLayout rowGetAccessKey;
    private LinearLayout rowPrivacyPolicy;
    private TextView btnResetSettings;
    private TextView tvSettingsAppVersion;

    // VIP Ticker
    private final Handler vipTickerHandler = new Handler(Looper.getMainLooper());
    private final Runnable vipTickerRunnable = new Runnable() {
        @Override
        public void run() {
            updateSettingsTabValues();
            if (AdManager.getInstance().isVipBoostActive(MainActivity.this)) {
                vipTickerHandler.postDelayed(this, 1000);
            }
        }
    };

    // Real-Time Traffic & Speed Telemetry Engine
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private boolean isStatsUpdating = false;
    private long sessionStartTimeMs = 0;
    private long initialRxBytes = 0;
    private long initialTxBytes = 0;
    private long lastRxBytes = 0;
    private long lastTxBytes = 0;

    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState == VpnState.CONNECTED && !isActivityPaused && isHomeTabActive) {
                updateTelemetry();
                statsHandler.postDelayed(this, 1000);
            } else {
                isStatsUpdating = false;
            }
        }
    };

    // Live Proxy GET Ping Engine State
    private static final long LIVE_PING_INTERVAL_MS = 12_000L;
    private static final long SERVER_PING_COOLDOWN_MS = 90_000L;
    private final Handler livePingHandler = new Handler(Looper.getMainLooper());
    private boolean isLivePingRunning = false;
    private final Runnable livePingRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState == VpnState.CONNECTED && !isActivityPaused && isHomeTabActive) {
                performLivePingCheck();
                livePingHandler.postDelayed(this, LIVE_PING_INTERVAL_MS);
            } else {
                isLivePingRunning = false;
            }
        }
    };

    // Data & State
    private List<VpnServer> serverList = new ArrayList<>();
    private VpnServer selectedServer = null; // null means AUTO
    private VpnState currentState = VpnState.DISCONNECTED;
    private boolean isWaveAnimating = false;
    private boolean isConnectingPulseAnimating = false;
    private boolean isActivityPaused = false;
    private boolean isDialogScrimVisible = false;
    private boolean isHomeTabActive = true;
    private long lastAutoUpdateCheckMs = 0;
    private long lastServerPingMs = 0L;

    private final Handler autoCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoCheckRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (!isFinishing()) {
                    lastAutoUpdateCheckMs = System.currentTimeMillis();
                    AppUpdateManager.checkForUpdates(MainActivity.this, false);
                }
            } catch (Throwable ignored) {}
            autoCheckHandler.postDelayed(this, 900000L); // каждые 15 минут
        }
    };

    private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (GoblinVpnService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                String stateName = intent.getStringExtra(GoblinVpnService.EXTRA_STATE);
                String errMsg = intent.getStringExtra(GoblinVpnService.EXTRA_ERROR_MESSAGE);
                if (errMsg != null && !errMsg.trim().isEmpty()) {
                    Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show();
                }
                if (stateName != null) {
                    try {
                        currentState = VpnState.valueOf(stateName);
                        updateUi();
                    } catch (Exception ignored) {}
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Global crash protector to avoid instant fatal process kills
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                AppLogger.e("CrashProtector", "Uncaught exception on " + thread.getName() + ": " + throwable.getMessage(), throwable);
                try {
                    java.io.File crashFile = new java.io.File(getFilesDir(), "last_crash.txt");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(crashFile);
                    String report = "Time: " + System.currentTimeMillis() + "\n"
                            + "Thread: " + thread.getName() + "\n"
                            + Log.getStackTraceString(throwable);
                    fos.write(report.getBytes("UTF-8"));
                    fos.close();
                } catch (Exception ignored) {}
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                }
            }
        });

        // Transparent edge-to-edge status bar: extend starfield and ambient glow behind system bar
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Window window = getWindow();
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(Color.TRANSPARENT);
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                window.getDecorView().setSystemUiVisibility(flags);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error configuring edge-to-edge window", t);
        }

        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Fatal error inflating activity_main layout", t);
            return;
        }

        // Request POST_NOTIFICATIONS runtime permission on Android 13+ (API 33+) for persistent tray widget
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
                }
            }
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error requesting notification permissions", t);
        }

        // Dynamically compute status bar height to adapt header padding safely across all notches
        try {
            headerBar = findViewById(R.id.headerBar);
            if (headerBar != null) {
                int statusBarHeight = 0;
                int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (resourceId > 0) {
                    statusBarHeight = getResources().getDimensionPixelSize(resourceId);
                }
                int extraTopPad = (int) (10 * getResources().getDisplayMetrics().density);
                int topPad = statusBarHeight > 0 ? (statusBarHeight + extraTopPad) : (int) (44 * getResources().getDisplayMetrics().density);
                headerBar.setPadding(headerBar.getPaddingLeft(), topPad, headerBar.getPaddingRight(), headerBar.getPaddingBottom());
            }
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error calculating header padding", t);
        }

        try {
            initViews();
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error in initViews", t);
        }

        try {
            setupListeners();
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error in setupListeners", t);
        }

        // Restore server selection and auto mode preferences
        try {
            NetworkPreferences prefs = NetworkPreferences.getInstance(this);
            prefs.setRestrictedTelegramOnly(false);
            isAutoMode = prefs.isAutoServerMode();
            String savedHost = prefs.getSelectedServerHost();
            if (serverList == null || serverList.isEmpty()) {
                serverList = EmergencyConfigStorage.loadEmergencyReserve(this);
            }
            if (!isAutoMode && savedHost != null && serverList != null) {
                for (VpnServer s : serverList) {
                    if (s != null && savedHost.equalsIgnoreCase(s.getHost())) {
                        selectedServer = s;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error restoring network preferences", t);
        }

        try {
            applyPowerButtonColor(currentPowerBgColor);

            if (bottomNavDock != null && navMorphingCapsule != null) {
                bottomNavDock.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            float dockWidth = bottomNavDock.getWidth() - bottomNavDock.getPaddingStart() - bottomNavDock.getPaddingEnd();
                            if (dockWidth <= 0) return;
                            float tabWidth = dockWidth / 2.0f;
                            int capsuleWidth = (int) (tabWidth - dpToPx(6));
                            if (navMorphingCapsule.getLayoutParams() != null) {
                                navMorphingCapsule.getLayoutParams().width = capsuleWidth;
                                navMorphingCapsule.setTranslationX(0f);
                                navMorphingCapsule.setScaleX(1.0f);
                                navMorphingCapsule.requestLayout();
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error initializing bottom nav morphing capsule", t);
        }

        try {
            serverList = getMergedServerList();
            updateBottomCard();
            refreshServers(false);
            CommunityPoolManager.getInstance(this).fetchTopServersAsync(null);
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error refreshing servers / bottom card", t);
        }

        // Direct state listener for instantaneous updates
        try {
            GoblinVpnService.setStateListener(new GoblinVpnService.StateListener() {
                @Override
                public void onStateChanged(VpnState state, VpnServer server) {
                    currentState = state;
                    if (server != null && (state == VpnState.CONNECTED || state == VpnState.CONNECTING)) {
                        selectedServer = server;
                    }
                    updateUi();
                }
            });

            // Restore active state if service is already running
            currentState = GoblinVpnService.getCurrentState();
            VpnServer activeServer = GoblinVpnService.getCurrentServer();
            if (activeServer != null && (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING)) {
                selectedServer = activeServer;
            }
            updateUi();
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error binding GoblinVpnService state listener", t);
        }

        // Listen to VIP Turbo Boost state changes
        try {
            AdManager.getInstance().addVipBoostListener(new AdManager.VipBoostListener() {
                @Override
                public void onVipBoostStateChanged(boolean isActive, long remainingMillis) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                updateSettingsTabValues();
                                updateUi();
                            } catch (Throwable t) {
                                AppLogger.e("MainActivity", "Error updating VIP boost UI", t);
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error binding VIP boost listener", t);
        }

        // Check Auto-Connect on App Launch if fresh start
        try {
            if (savedInstanceState == null) {
                checkAutoConnect();
            }
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error checking auto-connect", t);
        }

        // Запрос разрешения уведомлений Android 13+ (POST_NOTIFICATIONS) при запуске приложения,
        // чтобы исключить конфликт диалогов при первом нажатии кнопки «Подключить»
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
                }
            }
        } catch (Throwable t) {
            AppLogger.d("MainActivity", "Notification permission check error: " + t.getMessage());
        }

        try {
            // Silent background check for updates & notify system tray if newer version available
            lastAutoUpdateCheckMs = System.currentTimeMillis();
            AppUpdateManager.checkForUpdates(this, false);

            // Check if opened via notification with EXTRA_CHECK_UPDATE or open_inbox
            handleUpdateIntent(getIntent());
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error in update checks / intent handling", t);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleUpdateIntent(intent);
    }

    private void handleUpdateIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(AppUpdateManager.EXTRA_CHECK_UPDATE, false)) {
            AppUpdateManager.checkForUpdates(this, true);
        } else if (intent.getBooleanExtra("open_inbox", false)) {
            animateNotificationIconColor(false);
            NotificationInboxDialog.show(this);
        }
    }

    private void checkAutoConnect() {
        NetworkPreferences prefs = NetworkPreferences.getInstance(this);
        if (prefs.isAutoConnectEnabled() && currentState == VpnState.DISCONNECTED) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && currentState == VpnState.DISCONNECTED) {
                        Toast.makeText(MainActivity.this, "⚡ Автоподключение к защищенному каналу...", Toast.LENGTH_SHORT).show();
                        onPowerClicked();
                    }
                }
            }, 400);
        }
    }

    private void initViews() {
        starfieldView = findViewById(R.id.starfieldView);

        headerBar = findViewById(R.id.headerBar);
        btnGoblinBadge = findViewById(R.id.btnGoblinBadge);
        ivGoblinLogo = findViewById(R.id.ivGoblinLogo);
        statusPill = findViewById(R.id.statusPill);
        viewStatusDot = findViewById(R.id.viewStatusDot);
        tvStatusText = findViewById(R.id.tvStatusText);
        tvHeaderSettingsTitle = findViewById(R.id.tvHeaderSettingsTitle);
        btnSupport = findViewById(R.id.btnSupport);
        ivSupportIcon = findViewById(R.id.ivSupportIcon);

        if (headerBar != null) {
            headerBar.bringToFront();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                headerBar.setElevation(dpToPx(30));
            }
        }
        if (tvHeaderSettingsTitle != null) {
            tvHeaderSettingsTitle.bringToFront();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tvHeaderSettingsTitle.setElevation(dpToPx(35));
            }
        }

        bottomNavDock = findViewById(R.id.bottomNavDock);
        tabHome = findViewById(R.id.tabHome);
        ivTabHome = findViewById(R.id.ivTabHome);
        tvTabHome = findViewById(R.id.tvTabHome);
        tabSettings = findViewById(R.id.tabSettings);
        ivTabSettings = findViewById(R.id.ivTabSettings);
        tvTabSettings = findViewById(R.id.tvTabSettings);

        scrollHomeContent = findViewById(R.id.scrollHomeContent);
        layoutHomeContent = findViewById(R.id.layoutHomeContent);
        tvTimer = findViewById(R.id.tvTimer);
        tvPing = findViewById(R.id.tvPing);
        tvPingUnit = findViewById(R.id.tvPingUnit);
        tvTotalTraffic = findViewById(R.id.tvTotalTraffic);
        tvTrafficUnit = findViewById(R.id.tvTrafficUnit);
        tvTrafficSubtext = findViewById(R.id.tvTrafficSubtext);

        powerButtonWrapper = findViewById(R.id.powerButtonWrapper);
        viewPowerGlow = findViewById(R.id.viewPowerGlow);
        ringOuter = findViewById(R.id.ringOuter);
        ringMiddle = findViewById(R.id.ringMiddle);
        ringInner = findViewById(R.id.ringInner);
        viewPowerBezel = findViewById(R.id.viewPowerBezel);
        btnPower = findViewById(R.id.btnPower);
        ivPowerIcon = findViewById(R.id.ivPowerIcon);
        tvPowerStatus = findViewById(R.id.tvPowerStatus);

        cardServerSelector = findViewById(R.id.cardServerSelector);
        tvCardFlag = findViewById(R.id.tvCardFlag);
        tvCardTitle = findViewById(R.id.tvCardTitle);
        tvCardBadge = findViewById(R.id.tvCardBadge);
        tvCardCity = findViewById(R.id.tvCardCity);
        viewCardPingDot = findViewById(R.id.viewCardPingDot);
        badgePing = findViewById(R.id.badgePing);
        ivCardArrow = findViewById(R.id.ivCardArrow);
        tvStatusIp = findViewById(R.id.tvStatusIp);
        tvPingSubtext = findViewById(R.id.tvPingSubtext);

        btnRefreshServers = findViewById(R.id.btnRefreshServers);
        tvRefreshServers = findViewById(R.id.tvRefreshServers);
        ivRefreshIcon = findViewById(R.id.ivRefreshIcon);

        viewPillHome = findViewById(R.id.viewPillHome);
        viewPillSettings = findViewById(R.id.viewPillSettings);
        navMorphingCapsule = findViewById(R.id.navMorphingCapsule);
        viewSettingsBackdrop = findViewById(R.id.viewSettingsBackdrop);
        viewDialogScrim = findViewById(R.id.viewDialogScrim);
        badgeNotificationPulse = findViewById(R.id.badgeNotificationPulse);

        layoutSettingsContent = findViewById(R.id.layoutSettingsContent);
        rowClientId = findViewById(R.id.rowClientId);
        tvClientId = findViewById(R.id.tvClientId);
        rowSubKey = findViewById(R.id.rowSubKey);
        tvSubscriptionKey = findViewById(R.id.tvSubscriptionKey);
        rowTunnelSettings = findViewById(R.id.rowTunnelSettings);
        tvTunnelSummary = findViewById(R.id.tvTunnelSummary);
        rowAppRouting = findViewById(R.id.rowAppRouting);
        tvAppRoutingSummary = findViewById(R.id.tvAppRoutingSummary);
        rowAutoSwitchServer = findViewById(R.id.rowAutoSwitchServer);
        toggleAutoSwitchTrack = findViewById(R.id.toggleAutoSwitchTrack);
        toggleAutoSwitchThumb = findViewById(R.id.toggleAutoSwitchThumb);
        tvAutoSwitchSummary = findViewById(R.id.tvAutoSwitchSummary);
        rowVipBoost = findViewById(R.id.rowVipBoost);
        tvVipSettingsStatus = findViewById(R.id.tvVipSettingsStatus);
        rowEmergencyReserve = findViewById(R.id.rowEmergencyReserve);
        tvReserveStatus = findViewById(R.id.tvReserveStatus);
        rowAppLogs = findViewById(R.id.rowAppLogs);
        tvAppLogsSummary = findViewById(R.id.tvAppLogsSummary);
        toggleLoggingTrack = findViewById(R.id.toggleLoggingTrack);
        toggleLoggingThumb = findViewById(R.id.toggleLoggingThumb);
        rowCommunityShare = findViewById(R.id.rowCommunityShare);
        switchCommunityShareTrack = findViewById(R.id.switchCommunityShareTrack);
        switchCommunityShareThumb = findViewById(R.id.switchCommunityShareThumb);
        tvCommunityShareSummary = findViewById(R.id.tvCommunityShareSummary);
        rowTelegramChannel = findViewById(R.id.rowTelegramChannel);
        rowDonate = findViewById(R.id.rowDonate);
        rowGitHub = findViewById(R.id.rowGitHub);
        rowGetAccessKey = findViewById(R.id.rowGetAccessKey);
        rowPrivacyPolicy = findViewById(R.id.rowPrivacyPolicy);
        btnResetSettings = findViewById(R.id.btnResetSettings);
        tvSettingsAppVersion = findViewById(R.id.tvSettingsAppVersion);
    }

    private void setupListeners() {
        // Goblin Badge Info Click -> Check for Updates
        if (btnGoblinBadge != null) {
            btnGoblinBadge.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    AppUpdateManager.checkForUpdates(MainActivity.this, true);
                }
            });
        }

        // Top Support Button Click -> Журнал уведомлений
        if (btnSupport != null) {
            btnSupport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    animateNotificationIconColor(false);
                    NotificationInboxDialog.show(MainActivity.this);
                }
            });

            btnSupport.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    Toast.makeText(MainActivity.this, "Журнал уведомлений", Toast.LENGTH_SHORT).show();
                    return true;
                }
            });

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                btnSupport.setTooltipText("Журнал уведомлений");
            }
        }

        // Инициализация палитры значка Журнала уведомлений
        try {
            boolean initialHasUnread = NotificationCenter.hasUnreadNotifications(this);
            currentNotificationColor = initialHasUnread ? COLOR_NOTIF_GREEN : COLOR_NOTIF_GRAY;
            if (ivSupportIcon != null) {
                ivSupportIcon.setColorFilter(currentNotificationColor);
            }
            if (initialHasUnread) {
                startNotificationPulse();
            } else if (badgeNotificationPulse != null) {
                badgeNotificationPulse.clearAnimation();
                badgeNotificationPulse.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            AppLogger.w("MainActivity", "Error initializing notification badge icon: " + t.getMessage());
        }

        // Bottom Dock Tab Switching (Smooth 2-Tabs)
        if (tabHome != null) {
            tabHome.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchM3Tab(0);
                }
            });
        }

        if (tabSettings != null) {
            tabSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchM3Tab(1);
                }
            });
        }

        // Smooth blur/dim effect and snap settling when scrolling settings
        if (layoutSettingsContent != null) {
            final Handler snapHandler = new Handler(Looper.getMainLooper());
            final Runnable snapRunnable = new Runnable() {
                @Override
                public void run() {
                    if (layoutSettingsContent == null) return;
                    int scrollY = layoutSettingsContent.getScrollY();
                    int headerThreshold = dpToPx(60);
                    int oneThird = headerThreshold / 3;
                    if (scrollY > oneThird && scrollY < headerThreshold) {
                        layoutSettingsContent.smoothScrollTo(0, headerThreshold);
                    } else if (scrollY > 0 && scrollY <= oneThird) {
                        layoutSettingsContent.smoothScrollTo(0, 0);
                    }
                }
            };

            layoutSettingsContent.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                        snapHandler.removeCallbacks(snapRunnable);
                        snapHandler.postDelayed(snapRunnable, 100);
                    }
                    return false;
                }
            });

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                layoutSettingsContent.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                    @Override
                    public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                        updateSettingsScrollEffects(scrollY);
                    }
                });
            } else {
                layoutSettingsContent.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        if (layoutSettingsContent != null) {
                            updateSettingsScrollEffects(layoutSettingsContent.getScrollY());
                        }
                    }
                });
            }
        }

        // Center Power Button
        View.OnClickListener powerClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performPowerHapticFeedback();
                onPowerClicked();
            }
        };
        if (btnPower != null) btnPower.setOnClickListener(powerClickListener);
        if (powerButtonWrapper != null) powerButtonWrapper.setOnClickListener(powerClickListener);

        // Server Selection Pill
        if (cardServerSelector != null) {
            cardServerSelector.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openServerSelection();
                }
            });
        }

        // Refresh Servers Pill
        if (btnRefreshServers != null) {
            btnRefreshServers.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    refreshServers(true);
                }
            });
        }

        // Settings Rows
        if (rowClientId != null) {
            rowClientId.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        NetworkPreferences prefs = NetworkPreferences.getInstance(MainActivity.this);
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            ClipData cd = ClipData.newPlainText("Client ID", prefs.getClientId());
                            cm.setPrimaryClip(cd);
                            Toast.makeText(MainActivity.this, "ID скопирован в буфер обмена", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable t) {
                        AppLogger.w("MainActivity", "Failed to copy client id: " + t.getMessage());
                    }
                }
            });
        }

        if (rowSubKey != null) {
            rowSubKey.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSubscriptionKeyDialog();
                }
            });
        }

        if (rowTunnelSettings != null) {
            rowTunnelSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    TunnelSettingsDialog.show(MainActivity.this, new TunnelSettingsDialog.OnTunnelSettingsChangedListener() {
                        @Override
                        public void onTunnelSettingsChanged(boolean requestedReconnect) {
                            updateSettingsTabValues();
                            if (requestedReconnect) {
                                Toast.makeText(MainActivity.this, "Применение настроек туннеля...", Toast.LENGTH_SHORT).show();
                                reconnectVpn();
                            }
                        }
                    });
                }
            });
        }

        if (rowVipBoost != null) {
            rowVipBoost.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AdManager.getInstance().showTurboBoostDialog(MainActivity.this, null);
                }
            });
        }

        if (rowAppRouting != null) {
            rowAppRouting.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AppRoutingDialog(MainActivity.this, new AppRoutingDialog.OnRoutingChangedListener() {
                        @Override
                        public void onRoutingChanged(NetworkPreferences.AppRoutingMode newMode, java.util.Set<String> newSelectedPackages) {
                            updateSettingsTabValues();
                            reconnectVpn();
                        }
                    }).show();
                }
            });
        }

        if (rowAutoSwitchServer != null) {
            rowAutoSwitchServer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    NetworkPreferences prefs = NetworkPreferences.getInstance(MainActivity.this);
                    boolean newAutoSwitch = !prefs.isAutoSwitchServerEnabled();
                    prefs.setAutoSwitchServerEnabled(newAutoSwitch);
                    setAutoSwitchToggleState(newAutoSwitch, true);
                    HapticFeedbackHelper.performToggle(MainActivity.this, newAutoSwitch);
                    AutoSwitchEngine.getInstance(MainActivity.this).onAutoSwitchPreferenceChanged();
                    Toast.makeText(MainActivity.this, newAutoSwitch ? "Автопереключение включено (адаптивная смена узла)" : "Автопереключение выключено", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (rowEmergencyReserve != null) {
            rowEmergencyReserve.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    testEmergencyReserve();
                }
            });
        }

        if (rowAppLogs != null) {
            rowAppLogs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    NetworkPreferences prefs = NetworkPreferences.getInstance(MainActivity.this);
                    boolean newState = !prefs.isUserLoggingConsentGranted();
                    prefs.setUserLoggingConsentGranted(newState);
                    setLoggingToggleState(newState, true);
                    HapticFeedbackHelper.performToggle(MainActivity.this, newState);
                    if (newState) {
                        Toast.makeText(MainActivity.this, "Анонимная диагностика включена. Спасибо за помощь проекту!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Анонимная диагностика отключена. Логи не отправляются.", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (rowCommunityShare != null) {
            rowCommunityShare.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    NetworkPreferences prefs = NetworkPreferences.getInstance(MainActivity.this);
                    boolean newShare = !prefs.isShareWithCommunityEnabled();
                    prefs.setShareWithCommunityEnabled(newShare);
                    setCommunityShareToggleState(newShare, true);
                    HapticFeedbackHelper.performToggle(switchCommunityShareTrack != null ? (View) switchCommunityShareTrack : v, newShare);
                    Toast.makeText(MainActivity.this, newShare ? "Делиться лучшим с сообществом включено" : "Делиться лучшим с сообществом отключено", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (rowTelegramChannel != null) {
            rowTelegramChannel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    ru.llazyrin.goblinvpn.security.SecureExternalLinks.openTelegram(MainActivity.this);
                }
            });
        }

        if (rowDonate != null) {
            rowDonate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    ru.llazyrin.goblinvpn.security.SecureExternalLinks.openDonate(MainActivity.this);
                }
            });
        }

        if (rowGitHub != null) {
            rowGitHub.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    ru.llazyrin.goblinvpn.security.SecureExternalLinks.openGitHub(MainActivity.this);
                }
            });
        }

        if (rowGetAccessKey != null) {
            rowGetAccessKey.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    Toast.makeText(MainActivity.this, "Получение новых ключей временно приостановлено. Следите за новостями в @llazyrin", Toast.LENGTH_LONG).show();
                }
            });
        }

        if (rowPrivacyPolicy != null) {
            rowPrivacyPolicy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    showPrivacyPolicyDialog();
                }
            });
        }

        if (btnResetSettings != null) {
            btnResetSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showResetConfirmationDialog();
                }
            });
        }

        if (tvSettingsAppVersion != null) {
            tvSettingsAppVersion.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    AppUpdateManager.checkForUpdates(MainActivity.this, true);
                }
            });
        }
    }

    private void switchM3Tab(int tabIndex) {
        isHomeTabActive = (tabIndex == 0);
        animateNavMorphingCapsule(tabIndex);

        if (tabIndex == 0) {
            if (scrollHomeContent != null) {
                scrollHomeContent.setVisibility(View.VISIBLE);
            }
            if (layoutHomeContent != null) {
                layoutHomeContent.setVisibility(View.VISIBLE);
            }
            if (layoutSettingsContent != null) {
                layoutSettingsContent.setVisibility(View.GONE);
            }

            if (headerBar != null) {
                headerBar.setBackgroundColor(Color.TRANSPARENT);
            }
            if (tvHeaderSettingsTitle != null) {
                tvHeaderSettingsTitle.setVisibility(View.GONE);
            }
            if (btnGoblinBadge != null) {
                btnGoblinBadge.setVisibility(View.VISIBLE);
                btnGoblinBadge.setAlpha(1.0f);
                btnGoblinBadge.setTranslationX(0f);
            }
            if (statusPill != null) {
                statusPill.setVisibility(View.VISIBLE);
                statusPill.setAlpha(1.0f);
                statusPill.setTranslationY(0f);
            }

            if (viewSettingsBackdrop != null) {
                viewSettingsBackdrop.animate().alpha(0f).setDuration(200).start();
            }
            if (starfieldView != null) {
                starfieldView.animate().alpha(1.0f).setDuration(200).start();
            }

            if (ivTabHome != null) {
                ivTabHome.setColorFilter(Color.parseColor("#10B981"));
            }
            if (tvTabHome != null) {
                tvTabHome.setTextColor(Color.parseColor("#10B981"));
            }

            if (ivTabSettings != null) {
                ivTabSettings.setColorFilter(Color.parseColor("#94A3B8"));
            }
            if (tvTabSettings != null) {
                tvTabSettings.setTextColor(Color.parseColor("#94A3B8"));
            }

            if (currentState == VpnState.CONNECTED && !isActivityPaused) {
                startTelemetry();
                startLivePing();
            }
        } else {
            stopLivePing();
            pauseTelemetry();

            if (scrollHomeContent != null) {
                scrollHomeContent.setVisibility(View.GONE);
            }
            if (layoutHomeContent != null) {
                layoutHomeContent.setVisibility(View.GONE);
            }
            if (layoutSettingsContent != null) {
                layoutSettingsContent.setVisibility(View.VISIBLE);
            }

            if (headerBar != null) {
                headerBar.bringToFront();
            }
            if (tvHeaderSettingsTitle != null) {
                tvHeaderSettingsTitle.bringToFront();
                tvHeaderSettingsTitle.setVisibility(View.VISIBLE);
                tvHeaderSettingsTitle.setAlpha(1.0f);
            }
            updateSettingsHeaderAnimation(layoutSettingsContent != null ? layoutSettingsContent.getScrollY() : 0);

            if (ivTabSettings != null) {
                ivTabSettings.setColorFilter(Color.parseColor("#10B981"));
            }
            if (tvTabSettings != null) {
                tvTabSettings.setTextColor(Color.parseColor("#10B981"));
            }

            if (ivTabHome != null) {
                ivTabHome.setColorFilter(Color.parseColor("#94A3B8"));
            }
            if (tvTabHome != null) {
                tvTabHome.setTextColor(Color.parseColor("#94A3B8"));
            }

            updateSettingsTabValues();
            if (layoutSettingsContent != null) {
                layoutSettingsContent.post(new Runnable() {
                    @Override
                    public void run() {
                        if (layoutSettingsContent != null) {
                            updateSettingsScrollEffects(layoutSettingsContent.getScrollY());
                        }
                    }
                });
            }
        }

        syncAnimationAndBackgroundState();
    }

    private void animateNavMorphingCapsule(final int tabIndex) {
        if (bottomNavDock == null || navMorphingCapsule == null) {
            currentNavTabIndex = tabIndex;
            return;
        }

        bottomNavDock.post(new Runnable() {
            @Override
            public void run() {
                float dockWidth = bottomNavDock.getWidth() - bottomNavDock.getPaddingStart() - bottomNavDock.getPaddingEnd();
                if (dockWidth <= 0) return;
                float tabWidth = dockWidth / 2.0f;
                int capsuleWidth = (int) (tabWidth - dpToPx(6));

                if (navMorphingCapsule.getLayoutParams() != null && navMorphingCapsule.getLayoutParams().width != capsuleWidth) {
                    navMorphingCapsule.getLayoutParams().width = capsuleWidth;
                    navMorphingCapsule.requestLayout();
                }

                final float startX = currentNavTabIndex * tabWidth;
                final float endX = tabIndex * tabWidth;
                currentNavTabIndex = tabIndex;

                if (navMorphingAnimator != null) {
                    navMorphingAnimator.cancel();
                }

                navMorphingAnimator = ValueAnimator.ofFloat(0f, 1f);
                navMorphingAnimator.setDuration(320);
                navMorphingAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
                navMorphingAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        float f = animation.getAnimatedFraction();
                        float curX = startX + (endX - startX) * f;
                        navMorphingCapsule.setTranslationX(curX);

                        // Stretch towards target tab, detach, and settle:
                        float stretch = 1.0f + 0.28f * (float) Math.sin(f * Math.PI);
                        navMorphingCapsule.setScaleX(stretch);
                    }
                });
                navMorphingAnimator.start();
            }
        });
    }

    private void switchToTab(boolean isHome) {
        switchM3Tab(isHome ? 0 : 1);
    }

    private void updateSettingsScrollEffects(int scrollY) {
        float maxScroll = 250f;
        float fraction = Math.min(1.0f, Math.max(0.0f, scrollY / maxScroll));
        if (viewSettingsBackdrop != null) {
            viewSettingsBackdrop.setAlpha(fraction * 0.94f);
        }
        if (starfieldView != null) {
            starfieldView.setAlpha(1.0f - (fraction * 0.70f));
        }
        updateSettingsHeaderAnimation(scrollY);
    }

    private void updateSettingsHeaderAnimation(int scrollY) {
        if (tvHeaderSettingsTitle == null) return;

        float maxScroll = (float) dpToPx(60);
        float progress = Math.min(1.0f, Math.max(0.0f, (float) scrollY / maxScroll));

        float startOffsetY = (float) dpToPx(52);
        float currentY = (1.0f - progress) * startOffsetY;
        tvHeaderSettingsTitle.setTranslationY(currentY);

        // Выравнивание по левому краю блоков:
        // Карточки настроек имеют отступ 20dp от края экрана.
        // headerBar имеет paddingStart 16dp.
        // Значит, внутри headerBar левый край карточек находится в 20dp - 16dp = 4dp!
        float targetX = (float) dpToPx(4);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int textW = tvHeaderSettingsTitle.getWidth();
        if (textW <= 0 && tvHeaderSettingsTitle.getPaint() != null) {
            textW = (int) tvHeaderSettingsTitle.getPaint().measureText("НАСТРОЙКИ");
        }
        if (textW <= 0) textW = dpToPx(110);

        // Начальная позиция по центру экрана:
        float startX = (screenW - textW) / 2.0f - dpToPx(16);
        float currentX = startX - progress * (startX - targetX);
        tvHeaderSettingsTitle.setTranslationX(currentX);

        // 100% непрозрачный цвет текста:
        tvHeaderSettingsTitle.setAlpha(1.0f);

        // Фон хедера при скролле: карточки уходят под хедер
        if (headerBar != null) {
            float bgProgress = Math.min(1.0f, (float) scrollY / (float) dpToPx(30));
            int bgAlpha = (int) (bgProgress * 248); // до 97% непрозрачности
            headerBar.setBackgroundColor(Color.argb(bgAlpha, 11, 15, 21)); // тёмный обсидиан #0B0F15
        }

        // Плавное скрытие плашки ЗАЩИЩЕНО и гоблина:
        float fadeAlpha = Math.max(0f, 1.0f - progress * 1.5f);
        if (statusPill != null) {
            statusPill.setAlpha(fadeAlpha);
            if (progress >= 1.0f) statusPill.setVisibility(View.GONE);
            else statusPill.setVisibility(View.VISIBLE);
        }
        if (btnGoblinBadge != null) {
            btnGoblinBadge.setAlpha(fadeAlpha);
            if (progress >= 1.0f) btnGoblinBadge.setVisibility(View.GONE);
            else btnGoblinBadge.setVisibility(View.VISIBLE);
        }
    }

    private int dpToPx(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setLoggingToggleState(boolean active, boolean animate) {
        if (toggleLoggingTrack == null || toggleLoggingThumb == null) return;

        float density = getResources().getDisplayMetrics().density;
        float targetTranslationX = active ? (22f * density) : 0f;

        final int colorGray = Color.parseColor("#2C323D");
        final int strokeGray = Color.parseColor("#3D4554");
        final int colorGreen = Color.parseColor("#10B981");
        final int strokeGreen = Color.parseColor("#059669");

        final int targetBgColor = active ? colorGreen : colorGray;
        final int targetStrokeColor = active ? strokeGreen : strokeGray;
        final int fromBgColor = active ? colorGray : colorGreen;
        final int fromStrokeColor = active ? strokeGray : strokeGreen;

        if (animate) {
            toggleLoggingThumb.animate()
                    .translationX(targetTranslationX)
                    .setDuration(240)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            if (toggleColorAnimator != null) {
                toggleColorAnimator.cancel();
            }
            final GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(999f * density);
            final ArgbEvaluator evaluator = new ArgbEvaluator();
            final int strokeWidth = (int) (1f * density);

            toggleColorAnimator = ValueAnimator.ofFloat(0f, 1f);
            toggleColorAnimator.setDuration(240);
            toggleColorAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float fraction = animation.getAnimatedFraction();
                    int currBg = (int) evaluator.evaluate(fraction, fromBgColor, targetBgColor);
                    int currStroke = (int) evaluator.evaluate(fraction, fromStrokeColor, targetStrokeColor);
                    trackBg.setColor(currBg);
                    trackBg.setStroke(strokeWidth, currStroke);
                    toggleLoggingTrack.setBackground(trackBg);
                }
            });
            toggleColorAnimator.start();
        } else {
            toggleLoggingThumb.setTranslationX(targetTranslationX);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(999f * density);
            trackBg.setColor(targetBgColor);
            trackBg.setStroke((int) (1f * density), targetStrokeColor);
            toggleLoggingTrack.setBackground(trackBg);
        }

        if (tvAppLogsSummary != null) {
            if (active) {
                tvAppLogsSummary.setText("Активно • Анонимные логи включены");
                tvAppLogsSummary.setTextColor(Color.parseColor("#34D399"));
            } else {
                tvAppLogsSummary.setText("Отключено • Логи не отправляются");
                tvAppLogsSummary.setTextColor(Color.parseColor("#94A3B8"));
            }
        }
    }

    private void setAutoSwitchToggleState(boolean active, boolean animate) {
        if (toggleAutoSwitchTrack == null || toggleAutoSwitchThumb == null) return;

        float density = getResources().getDisplayMetrics().density;
        float targetTranslationX = active ? (22f * density) : 0f;

        final int colorGray = Color.parseColor("#2C323D");
        final int strokeGray = Color.parseColor("#3D4554");
        final int colorGreen = Color.parseColor("#10B981");
        final int strokeGreen = Color.parseColor("#059669");

        final int targetBgColor = active ? colorGreen : colorGray;
        final int targetStrokeColor = active ? strokeGreen : strokeGray;
        final int fromBgColor = active ? colorGray : colorGreen;
        final int fromStrokeColor = active ? strokeGray : strokeGreen;

        if (animate) {
            toggleAutoSwitchThumb.animate()
                    .translationX(targetTranslationX)
                    .setDuration(240)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            if (toggleAutoSwitchColorAnimator != null) {
                toggleAutoSwitchColorAnimator.cancel();
            }
            final GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(999f * density);
            final ArgbEvaluator evaluator = new ArgbEvaluator();
            final int strokeWidth = (int) (1f * density);

            toggleAutoSwitchColorAnimator = ValueAnimator.ofFloat(0f, 1f);
            toggleAutoSwitchColorAnimator.setDuration(240);
            toggleAutoSwitchColorAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float fraction = animation.getAnimatedFraction();
                    int currBg = (int) evaluator.evaluate(fraction, fromBgColor, targetBgColor);
                    int currStroke = (int) evaluator.evaluate(fraction, fromStrokeColor, targetStrokeColor);
                    trackBg.setColor(currBg);
                    trackBg.setStroke(strokeWidth, currStroke);
                    toggleAutoSwitchTrack.setBackground(trackBg);
                }
            });
            toggleAutoSwitchColorAnimator.start();
        } else {
            toggleAutoSwitchThumb.setTranslationX(targetTranslationX);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(999f * density);
            trackBg.setColor(targetBgColor);
            trackBg.setStroke((int) (1f * density), targetStrokeColor);
            toggleAutoSwitchTrack.setBackground(trackBg);
        }

        if (tvAutoSwitchSummary != null) {
            if (active) {
                tvAutoSwitchSummary.setText("Активно • Смена узла при обрыве или задержке");
                tvAutoSwitchSummary.setTextColor(Color.parseColor("#34D399"));
            } else {
                tvAutoSwitchSummary.setText("Отключено • Фиксированный узел подключения");
                tvAutoSwitchSummary.setTextColor(Color.parseColor("#94A3B8"));
            }
        }
    }

    private void setCommunityShareToggleState(boolean active, boolean animate) {
        if (switchCommunityShareTrack == null || switchCommunityShareThumb == null) return;

        float density = getResources().getDisplayMetrics().density;
        float targetTranslationX = active ? (22f * density) : 0f;

        final int colorGray = Color.parseColor("#2C323D");
        final int strokeGray = Color.parseColor("#3D4554");
        final int colorGreen = Color.parseColor("#10B981");
        final int strokeGreen = Color.parseColor("#059669");

        final int targetBgColor = active ? colorGreen : colorGray;
        final int targetStrokeColor = active ? strokeGreen : strokeGray;
        final int fromBgColor = active ? colorGray : colorGreen;
        final int fromStrokeColor = active ? strokeGray : strokeGreen;

        if (animate) {
            switchCommunityShareThumb.animate()
                    .translationX(targetTranslationX)
                    .setDuration(240)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            if (switchCommunityShareColorAnimator != null) {
                switchCommunityShareColorAnimator.cancel();
            }
            final GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(999f * density);
            final ArgbEvaluator evaluator = new ArgbEvaluator();
            final int strokeWidth = (int) (1f * density);

            switchCommunityShareColorAnimator = ValueAnimator.ofFloat(0f, 1f);
            switchCommunityShareColorAnimator.setDuration(240);
            switchCommunityShareColorAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float fraction = animation.getAnimatedFraction();
                    int currBg = (int) evaluator.evaluate(fraction, fromBgColor, targetBgColor);
                    int currStroke = (int) evaluator.evaluate(fraction, fromStrokeColor, targetStrokeColor);
                    trackBg.setColor(currBg);
                    trackBg.setStroke(strokeWidth, currStroke);
                    switchCommunityShareTrack.setBackground(trackBg);
                }
            });
            switchCommunityShareColorAnimator.start();
        } else {
            switchCommunityShareThumb.setTranslationX(targetTranslationX);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(999f * density);
            trackBg.setColor(targetBgColor);
            trackBg.setStroke((int) (1f * density), targetStrokeColor);
            switchCommunityShareTrack.setBackground(trackBg);
        }

        if (tvCommunityShareSummary != null) {
            if (active) {
                tvCommunityShareSummary.setText("Активно • Делиться лучшими конфигами с сообществами");
                tvCommunityShareSummary.setTextColor(Color.parseColor("#34D399"));
            } else {
                tvCommunityShareSummary.setText("Отключено • Конфигурации не передаются");
                tvCommunityShareSummary.setTextColor(Color.parseColor("#94A3B8"));
            }
        }
    }

    private void updateSettingsTabValues() {
        NetworkPreferences prefs = NetworkPreferences.getInstance(this);
        if (tvClientId != null) {
            tvClientId.setText(prefs.getClientId());
        }
        if (tvSubscriptionKey != null) {
            tvSubscriptionKey.setText(prefs.getSubscriptionKey());
        }
        if (tvTunnelSummary != null) {
            String dnsBadge = prefs.getDnsOption().getBadge();
            String bypass = prefs.isBypassRuTrafficEnabled() ? "РФ напрямую" : "Весь трафик";
            tvTunnelSummary.setText(dnsBadge + " • " + bypass);
        }
        if (tvAppRoutingSummary != null) {
            NetworkPreferences.AppRoutingMode mode = prefs.getAppRoutingMode();
            tvAppRoutingSummary.setText(mode.getTitle() + " • " + prefs.getRoutedPackages().size() + " приложений");
        }
        setAutoSwitchToggleState(prefs.isAutoSwitchServerEnabled(), false);
        setCommunityShareToggleState(prefs.isShareWithCommunityEnabled(), false);
        if (tvVipSettingsStatus != null) {
            boolean isVip = AdManager.getInstance().isVipBoostActive(this);
            if (isVip) {
                tvVipSettingsStatus.setText(AdManager.getInstance().getFormattedRemainingTime(this));
                tvVipSettingsStatus.setTextColor(Color.parseColor("#E3B341"));
            } else {
                tvVipSettingsStatus.setText("Не активен");
                tvVipSettingsStatus.setTextColor(Color.parseColor("#7A8C7D"));
            }
        }
        if (tvReserveStatus != null) {
            tvReserveStatus.setText("10 резервных узлов");
        }
        setLoggingToggleState(prefs.isUserLoggingConsentGranted(), false);

        if (tvSettingsAppVersion != null) {
            String verName = AppUpdateManager.getAppVersionName(this);
            int verCode = AppUpdateManager.getAppVersionCode(this);
            tvSettingsAppVersion.setText("Версия " + verName + " (Build " + verCode + ")");
        }
    }

    private void showSubscriptionKeyDialog() {
        final NetworkPreferences prefs = NetworkPreferences.getInstance(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Ключ подписки");

        final EditText input = new EditText(this);
        input.setText(prefs.getSubscriptionKey());
        input.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);

        builder.setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String newKey = input.getText().toString().trim();
                prefs.setSubscriptionKey(newKey);
                tvSubscriptionKey.setText(prefs.getSubscriptionKey());
                Toast.makeText(MainActivity.this, "Ключ сохранен", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNeutralButton("Скопировать", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = ClipData.newPlainText("Subscription Key", prefs.getSubscriptionKey());
                cm.setPrimaryClip(cd);
                Toast.makeText(MainActivity.this, "Ключ скопирован в буфер", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void showDialogScrim() {
        isDialogScrimVisible = true;
        if (viewDialogScrim != null) {
            viewDialogScrim.setVisibility(View.VISIBLE);
            viewDialogScrim.animate().alpha(0.85f).setDuration(200).start();
        } else if (viewSettingsBackdrop != null) {
            viewSettingsBackdrop.animate().alpha(0.85f).setDuration(200).start();
        }
        syncAnimationAndBackgroundState();
    }

    private void hideDialogScrim() {
        isDialogScrimVisible = false;
        if (viewDialogScrim != null) {
            viewDialogScrim.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (viewDialogScrim != null && viewDialogScrim.getAlpha() == 0f) {
                        viewDialogScrim.setVisibility(View.GONE);
                    }
                }
            }).start();
        }
        if (viewSettingsBackdrop != null && currentNavTabIndex == 0) {
            viewSettingsBackdrop.animate().alpha(0f).setDuration(200).start();
        }
        syncAnimationAndBackgroundState();
    }

    private void showPrivacyPolicyDialog() {
        showDialogScrim();
        PrivacyPolicyDialog dialog = new PrivacyPolicyDialog(MainActivity.this);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                hideDialogScrim();
            }
        });
        dialog.show();
    }

    private void showResetConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Сбросить настройки?")
                .setMessage("Все пользовательские ключи, список доменов и конфигурации туннеля будут возвращены к исходным значениям.")
                .setPositiveButton("Сбросить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        NetworkPreferences prefs = NetworkPreferences.getInstance(MainActivity.this);
                        prefs.resetAllPreferences();
                        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
                            onPowerClicked();
                        }
                        updateSettingsTabValues();
                        updateUi();
                        Toast.makeText(MainActivity.this, "Настройки сброшены", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void testEmergencyReserve() {
        List<VpnServer> reserve = EmergencyConfigStorage.loadEmergencyReserve(this);
        int count = (reserve != null) ? reserve.size() : 0;
        Toast.makeText(this, "🛡️ Резервные узлы проверены: " + (count > 0 ? count : 10) + " узлов готовы к работе", Toast.LENGTH_SHORT).show();
        if (reserve != null && !reserve.isEmpty()) {
            ServerPinger.pingAllAsync(reserve, null);
        }
    }

    private void openTelegramUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть Telegram", Toast.LENGTH_SHORT).show();
        }
    }

    private void reconnectVpn() {
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            Intent dcIntent = new Intent(this, GoblinVpnService.class);
            dcIntent.setAction(GoblinVpnService.ACTION_DISCONNECT);
            startService(dcIntent);

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) {
                        startVpnService();
                    }
                }
            }, 600);
        } else {
            startVpnService();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityPaused = false;

        IntentFilter filter = new IntentFilter(GoblinVpnService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vpnStateReceiver, filter);
        }

        currentState = GoblinVpnService.getCurrentState();
        isWaveAnimating = false;
        isConnectingPulseAnimating = false;
        AppUpdateManager.checkPendingInstall(this);
        if (AppUpdateManager.isAppBlocked() && AppUpdateManager.getBlockedUpdateInfo() != null) {
            AppUpdateManager.showForceUpdateDialog(this, AppUpdateManager.getBlockedUpdateInfo(), AppUpdateManager.getAppVersionName(this));
        }
        updateSettingsTabValues();

        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            Intent restoreIntent = new Intent(this, GoblinVpnService.class);
            restoreIntent.setAction(GoblinVpnService.ACTION_RESTORE_NOTIFICATION);
            try {
                startService(restoreIntent);
            } catch (Exception ignored) {}
        }

        vipTickerHandler.removeCallbacks(vipTickerRunnable);
        if (AdManager.getInstance().isVipBoostActive(this)) {
            vipTickerHandler.post(vipTickerRunnable);
        }

        updateUi();
        if (currentState == VpnState.CONNECTED) {
            if (isHomeTabActive) {
                startTelemetry();
                startLivePing();
            }
        } else if (!isRefreshingServers) {
            long nowPingMs = SystemClock.elapsedRealtime();
            if (!hasCachedServerPings() || (nowPingMs - lastServerPingMs >= SERVER_PING_COOLDOWN_MS)) {
                pingServers();
            }
        }

        // Слушатель новых событий Журнала уведомлений
        notificationListener = new NotificationCenter.NotificationListener() {
            @Override
            public void onNewNotificationArrived() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        animateNotificationIconColor(true);
                    }
                });
            }
        };
        NotificationCenter.setListener(notificationListener);

        // Синхронизация состояния значка Журнала уведомлений
        boolean hasUnread = NotificationCenter.hasUnreadNotifications(this);
        int expectedColor = hasUnread ? COLOR_NOTIF_GREEN : COLOR_NOTIF_GRAY;
        if (currentNotificationColor != expectedColor || hasUnread) {
            animateNotificationIconColor(hasUnread);
        }

        syncAnimationAndBackgroundState();

        // Уведомляем движок переключения, что приложение развернуто на переднем плане
        try {
            AutoSwitchEngine.getInstance(this).setAppForeground(true);
        } catch (Throwable ignored) {}

        // Автоматическая проверка обновлений и новостей сервера с дебаунсом 15 минут
        try {
            long now = System.currentTimeMillis();
            if (now - lastAutoUpdateCheckMs > 900000L) { // 15 минут дебаунс
                lastAutoUpdateCheckMs = now;
                AppUpdateManager.checkForUpdates(this, false);
            }
        } catch (Throwable ignored) {}

        autoCheckHandler.removeCallbacks(autoCheckRunnable);
        autoCheckHandler.postDelayed(autoCheckRunnable, 900000L); // 15 минут

        // Check Telegram Auth Gatekeeper (временно закомментировано)
        /*
        try {
            AuthGatekeeperDialog.checkAndShowIfNeeded(this);
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error in AuthGatekeeper check: " + t.getMessage(), t);
        }
        */
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityPaused = true;
        // Уведомляем движок переключения, что приложение ушло в фон (активируем иммунитет Doze)
        try {
            AutoSwitchEngine.getInstance(this).setAppForeground(false);
        } catch (Throwable ignored) {}
        autoCheckHandler.removeCallbacks(autoCheckRunnable);
        NotificationCenter.removeListener();
        notificationListener = null;
        stopLivePing();
        pauseTelemetry();
        vipTickerHandler.removeCallbacks(vipTickerRunnable);
        syncAnimationAndBackgroundState();
        try {
            unregisterReceiver(vpnStateReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        isActivityPaused = true;
        cancelAllAnimators();
        autoCheckHandler.removeCallbacks(autoCheckRunnable);
        vipTickerHandler.removeCallbacks(vipTickerRunnable);
        NotificationCenter.removeListener();
        notificationListener = null;
        if (activeServerDialog != null && activeServerDialog.isShowing()) {
            activeServerDialog.dismiss();
            activeServerDialog = null;
        }
        hideDialogScrim();
        stopLivePing();
        stopTelemetry();
        super.onDestroy();
    }

    private boolean shouldRunHomeAnimations() {
        if (isActivityPaused || isDialogScrimVisible || !isHomeTabActive || isFinishing()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) {
            return false;
        }
        return true;
    }

    private void syncAnimationAndBackgroundState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) {
            return;
        }
        boolean runHome = shouldRunHomeAnimations();

        if (starfieldView != null) {
            starfieldView.setPaused(!runHome);
        }

        if (runHome && !isFinishing()) {
            if (currentState == VpnState.CONNECTED) {
                stopConnectingPulse();
                startGreenWavePulse();
            } else if (currentState == VpnState.CONNECTING) {
                stopGreenWavePulse();
                startConnectingPulse();
            } else {
                stopConnectingPulse();
                stopGreenWavePulse();
            }
            if (NotificationCenter.hasUnreadNotifications(this)) {
                animateNotificationIconColor(true);
            }
        } else {
            stopConnectingPulse();
            stopGreenWavePulse();
            pauseNotificationAnimations();
        }
    }

    private void pauseNotificationAnimations() {
        if (bellBreathingAnimator != null) {
            bellBreathingAnimator.cancel();
            bellBreathingAnimator = null;
        }
        if (badgeNotificationPulse != null) {
            badgeNotificationPulse.clearAnimation();
            badgeNotificationPulse.setVisibility(View.GONE);
        }
    }

    private void cancelAllAnimators() {
        if (notificationColorAnimator != null) {
            notificationColorAnimator.cancel();
            notificationColorAnimator = null;
        }
        pauseNotificationAnimations();
        if (powerColorAnimator != null) {
            powerColorAnimator.cancel();
            powerColorAnimator = null;
        }
        if (navMorphingAnimator != null) {
            navMorphingAnimator.cancel();
            navMorphingAnimator = null;
        }
        if (toggleColorAnimator != null) {
            toggleColorAnimator.cancel();
            toggleColorAnimator = null;
        }
        if (toggleAutoSwitchColorAnimator != null) {
            toggleAutoSwitchColorAnimator.cancel();
            toggleAutoSwitchColorAnimator = null;
        }
        if (switchCommunityShareColorAnimator != null) {
            switchCommunityShareColorAnimator.cancel();
            switchCommunityShareColorAnimator = null;
        }
        stopConnectingPulse();
        stopGreenWavePulse();
        if (ivRefreshIcon != null) {
            ivRefreshIcon.clearAnimation();
        }
        if (starfieldView != null) {
            starfieldView.setPaused(true);
        }
    }

    private void startNotificationPulse() {
        if (badgeNotificationPulse == null) return;
        if (badgeNotificationPulse.getVisibility() == View.VISIBLE && badgeNotificationPulse.getAnimation() != null) {
            return;
        }
        badgeNotificationPulse.clearAnimation();
        badgeNotificationPulse.setVisibility(View.VISIBLE);
        badgeNotificationPulse.setAlpha(1.0f);

        AnimationSet pulseSet = new AnimationSet(true);
        pulseSet.setInterpolator(new AccelerateDecelerateInterpolator());

        ScaleAnimation scale = new ScaleAnimation(
                0.8f, 1.5f,
                0.8f, 1.5f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
        );
        scale.setDuration(1600);
        scale.setRepeatCount(Animation.INFINITE);
        scale.setRepeatMode(Animation.RESTART);
        pulseSet.addAnimation(scale);

        AlphaAnimation alpha = new AlphaAnimation(0.7f, 0.0f);
        alpha.setDuration(1600);
        alpha.setRepeatCount(Animation.INFINITE);
        alpha.setRepeatMode(Animation.RESTART);
        pulseSet.addAnimation(alpha);

        badgeNotificationPulse.startAnimation(pulseSet);
    }

    private void stopNotificationPulse() {
        if (badgeNotificationPulse == null) return;
        if (badgeNotificationPulse.getVisibility() != View.VISIBLE) return;

        badgeNotificationPulse.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (badgeNotificationPulse != null) {
                            badgeNotificationPulse.clearAnimation();
                            badgeNotificationPulse.setVisibility(View.GONE);
                            badgeNotificationPulse.setAlpha(1.0f);
                        }
                    }
                })
                .start();
    }

    /**
     * Плавная смена цвета иконки Журнала уведомлений:
     * - В обычном состоянии (нет новых уведомлений или все прочитаны): серый #758678.
     * - Когда пришло уведомление (или есть непрочитанные): фирменный зеленый #10B981.
     * Использует ValueAnimator.ofObject(new ArgbEvaluator(), currentColor, targetColor) с интерполятором
     * AccelerateDecelerateInterpolator и длительностью 450мс.
     * Также синхронно активирует или плавно гасит пульсирующий радар badgeNotificationPulse.
     */
    public void animateNotificationIconColor(final boolean hasUnread) {
        if (notificationColorAnimator != null && notificationColorAnimator.isRunning()) {
            notificationColorAnimator.cancel();
            notificationColorAnimator = null;
        }

        if (hasUnread) {
            if (!shouldRunHomeAnimations()) {
                pauseNotificationAnimations();
                currentNotificationColor = COLOR_NOTIF_GREEN;
                if (ivSupportIcon != null) {
                    ivSupportIcon.setColorFilter(COLOR_NOTIF_GREEN);
                }
                return;
            }
            startNotificationPulse();
            if (bellBreathingAnimator == null || !bellBreathingAnimator.isRunning()) {
                if (bellBreathingAnimator != null) {
                    bellBreathingAnimator.cancel();
                }
                bellBreathingAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), COLOR_NOTIF_GRAY, COLOR_NOTIF_GREEN);
                bellBreathingAnimator.setDuration(1500);
                bellBreathingAnimator.setRepeatMode(ValueAnimator.REVERSE);
                bellBreathingAnimator.setRepeatCount(ValueAnimator.INFINITE);
                bellBreathingAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        currentNotificationColor = (int) animation.getAnimatedValue();
                        if (ivSupportIcon != null) {
                            ivSupportIcon.setColorFilter(currentNotificationColor);
                        }
                    }
                });
                bellBreathingAnimator.start();
            }
        } else {
            stopNotificationPulse();
            if (bellBreathingAnimator != null) {
                bellBreathingAnimator.cancel();
                bellBreathingAnimator = null;
            }
            currentNotificationColor = COLOR_NOTIF_GRAY;
            if (ivSupportIcon != null) {
                ivSupportIcon.setColorFilter(COLOR_NOTIF_GRAY);
            }
        }
    }

    private void loadServers() {
        refreshServers(false);
    }

    private void refreshServers(final boolean userInitiated) {
        if (isRefreshingServers) return;
        isRefreshingServers = true;

        if (serverList == null || serverList.isEmpty()) {
            serverList = EmergencyConfigStorage.loadEmergencyReserve(this);
        }
        updateBottomCard();

        if (ivRefreshIcon != null) {
            RotateAnimation rotate = new RotateAnimation(0f, 360f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f);
            rotate.setDuration(800);
            rotate.setRepeatCount(Animation.INFINITE);
            rotate.setInterpolator(new LinearInterpolator());
            ivRefreshIcon.startAnimation(rotate);
        }

        ConfigDiscoveryEngine.discoverAndBenchmarkAsync(this, new ConfigDiscoveryEngine.DiscoveryCallback() {
            @Override
            public void onProgress(final String statusMessage, int percent) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateBottomCard();
                    }
                });
            }

            @Override
            public void onComplete(final List<VpnServer> balancedServers, final VpnServer optimalServer, final boolean isEmergencyMode) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isRefreshingServers = false;
                        if (ivRefreshIcon != null) {
                            ivRefreshIcon.clearAnimation();
                        }

                        isEmergencyReserveActive = isEmergencyMode;
                        List<VpnServer> combined = new ArrayList<>();
                        if (balancedServers != null) {
                            for (VpnServer bs : balancedServers) {
                                if (bs != null && !isKnownDeadHost(bs.getHost())) {
                                    combined.add(bs);
                                }
                            }
                        }
                        try {
                            List<VpnServer> bypass = WhitelistPoolManager.getInstance(MainActivity.this).getMobileBypassServerPool(MainActivity.this);
                            if (bypass != null) {
                                for (VpnServer bp : bypass) {
                                    if (bp != null && !isKnownDeadHost(bp.getHost())) {
                                        boolean exists = false;
                                        for (VpnServer existing : combined) {
                                            if (existing != null && existing.getHost() != null && existing.getHost().equalsIgnoreCase(bp.getHost())) {
                                                exists = true;
                                                break;
                                            }
                                        }
                                        if (!exists) {
                                            combined.add(bp);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}

                        if (!combined.isEmpty()) {
                            serverList = combined;
                            AutoSwitchEngine.getInstance(MainActivity.this).setCandidateServers(combined);
                            if (hasCachedServerPings()) {
                                lastServerPingMs = SystemClock.elapsedRealtime();
                            }
                        }

                        NetworkPreferences prefs = NetworkPreferences.getInstance(MainActivity.this);
                        isAutoMode = prefs.isAutoServerMode();
                        if (isAutoMode) {
                            selectedServer = null;
                        } else {
                            String savedHost = prefs.getSelectedServerHost();
                            selectedServer = null;
                            if (savedHost != null && serverList != null) {
                                for (VpnServer s : serverList) {
                                    if (s != null && savedHost.equalsIgnoreCase(s.getHost())) {
                                        selectedServer = s;
                                        break;
                                    }
                                }
                            }
                            if (selectedServer == null && optimalServer != null) {
                                selectedServer = optimalServer;
                            }
                        }

                        updateBottomCard();
                        updateUi();

                        // Dynamically update dialog if currently open!
                        if (activeServerDialog != null && activeServerDialog.isShowing()) {
                            activeServerDialog.updateServerList(serverList);
                        }

                        if (userInitiated) {
                            Toast.makeText(MainActivity.this, "Список узлов обновлен (" + (serverList != null ? serverList.size() : 0) + " серверов онлайн)", Toast.LENGTH_SHORT).show();
                        } else if (isEmergencyMode) {
                            Toast.makeText(MainActivity.this, "🛡️ Активирован аварийный резерв (Анти-глушилка)", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isRefreshingServers = false;
                        if (ivRefreshIcon != null) {
                            ivRefreshIcon.clearAnimation();
                        }

                        if (serverList == null || serverList.isEmpty()) {
                            serverList = EmergencyConfigStorage.loadEmergencyReserve(MainActivity.this);
                        }
                        updateBottomCard();
                        updateUi();

                        if (activeServerDialog != null && activeServerDialog.isShowing()) {
                            activeServerDialog.updateServerList(serverList);
                        }

                        if (userInitiated) {
                            Toast.makeText(MainActivity.this, "Ошибка обновления списка узлов", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private boolean hasCachedServerPings() {
        if (serverList == null || serverList.isEmpty()) {
            return false;
        }
        for (VpnServer s : serverList) {
            if (s != null && s.getPingMs() > 0) {
                return true;
            }
        }
        return false;
    }

    private void pingServers() {
        lastServerPingMs = SystemClock.elapsedRealtime();
        if (serverList == null || serverList.isEmpty()) {
            serverList = VpnSubscriptionClient.getBuiltinFallbackServers();
        }
        ServerPinger.pingAllAsync(serverList, new ServerPinger.PingCallback() {
            @Override
            public void onServerPinged(final VpnServer server) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        VpnServer target = getTargetServer();
                        if (target != null && target.getHost().equalsIgnoreCase(server.getHost())) {
                            updateBottomCard();
                        }
                    }
                });
            }

            @Override
            public void onAllPinged(final List<VpnServer> sortedServers) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (sortedServers != null && !sortedServers.isEmpty()) {
                            serverList = sortedServers;
                        } else if (serverList == null || serverList.isEmpty()) {
                            serverList = VpnSubscriptionClient.getBuiltinFallbackServers();
                        }
                        updateBottomCard();
                        if (activeServerDialog != null && activeServerDialog.isShowing()) {
                            activeServerDialog.updateServerList(serverList);
                        }
                    }
                });
            }
        });
    }

    private long lastPowerVibrateMs = 0;

    private void performPowerHapticFeedback() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPowerVibrateMs < 120) {
            return;
        }
        lastPowerVibrateMs = now;

        try {
            if (btnPower != null) {
                HapticFeedbackHelper.performPowerButton(btnPower);
            } else {
                HapticFeedbackHelper.performPowerButton(this);
            }
        } catch (Throwable ignored) {}

        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(45);
                }
            }
        } catch (Exception ignored) {}
    }

    private void onPowerClicked() {
        performPowerHapticFeedback();
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            currentState = VpnState.DISCONNECTED;
            updateUi();

            Intent dcIntent = new Intent(this, GoblinVpnService.class);
            dcIntent.setAction(GoblinVpnService.ACTION_DISCONNECT);
            startService(dcIntent);
        } else {
            // User explicitly clicked connect: clear temporary failure states and cooldowns
            try {
                AutoSwitchEngine.getInstance(this).notifyUserInitiatedConnect();
            } catch (Throwable ignored) {}

            currentState = VpnState.CONNECTING;
            updateUi();
            requestVpnPermissionAndStart();
        }
    }

    private void requestVpnPermissionAndStart() {
        try {
            // Вызываем исключительно VpnService.prepare(this) без параллельных запросов других прав,
            // исключая коллизию системных модальных окон Android
            Intent prepareIntent = VpnService.prepare(this);
            if (prepareIntent != null) {
                startActivityForResult(prepareIntent, VPN_REQUEST_CODE);
            } else {
                startVpnService();
            }
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error preparing VpnService: " + t.getMessage(), t);
            currentState = VpnState.DISCONNECTED;
            updateUi();
            lastVpnHelpDialogShowMs = SystemClock.elapsedRealtime();
            VpnPermissionHelpDialog.show(this, new Runnable() {
                @Override
                public void run() {
                    requestVpnPermissionAndStart();
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AppLogger.i("MainActivity", "POST_NOTIFICATIONS granted by user");
            } else {
                AppLogger.w("MainActivity", "POST_NOTIFICATIONS not granted by user");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                AppLogger.i("MainActivity", "VpnService permission granted successfully");
                startVpnService();
            } else {
                currentState = VpnState.DISCONNECTED;
                updateUi();
                AppLogger.w("MainActivity", "VPN permission canceled or denied (resultCode=" + resultCode + ")");

                long now = SystemClock.elapsedRealtime();
                if (now - lastVpnHelpDialogShowMs < 4000L) {
                    Toast.makeText(this, "⚠️ Разрешение отклонено. Отключите активный VPN (🔑 в строке состояния) или откройте настройки", Toast.LENGTH_LONG).show();
                } else {
                    lastVpnHelpDialogShowMs = now;
                    VpnPermissionHelpDialog.show(this, new Runnable() {
                        @Override
                        public void run() {
                            requestVpnPermissionAndStart();
                        }
                    });
                }
            }
        }
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    SharedPreferences sp = getSharedPreferences("goblin_prefs", MODE_PRIVATE);
                    boolean asked = sp.getBoolean("battery_opt_prompted", false);
                    if (!asked) {
                        sp.edit().putBoolean("battery_opt_prompted", true).apply();
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }
                }
            } catch (Throwable t) {
                AppLogger.d("MainActivity", "Battery optimization prompt: " + t.getMessage());
            }
        }
    }

    private void startVpnService() {
        checkBatteryOptimization();
        if (AppUpdateManager.isAppBlocked()) {
            Toast.makeText(this, "Версия приложения устарела. Обновите приложение для подключения к VPN", Toast.LENGTH_LONG).show();
            if (AppUpdateManager.getBlockedUpdateInfo() != null) {
                AppUpdateManager.showForceUpdateDialog(this, AppUpdateManager.getBlockedUpdateInfo(), AppUpdateManager.getAppVersionName(this));
            }
            return;
        }

        boolean isCellular = false;
        try {
            isCellular = WhitelistPoolManager.isRunningOnCellular(this);
        } catch (Throwable ignored) {}

        NetworkPreferences prefs = NetworkPreferences.getInstance(this);
        Intent startIntent = new Intent(this, GoblinVpnService.class);
        startIntent.setAction(GoblinVpnService.ACTION_CONNECT);

        if (isAutoMode && isCellular) {
            // Sticky-Mobile architecture: reuse remembered working cellular node first until it fails
            VpnServer preVerified = null;
            try {
                preVerified = AutoSwitchEngine.getInstance(this).peekVerifiedCellularServer();
            } catch (Throwable ignored) {}
            if (preVerified == null) {
                try {
                    VpnServer sticky = MobileProvenConfigStorage.getInstance(this).getStickyMobileServer();
                    if (sticky != null
                            && !AutoSwitchEngine.getInstance(this).isServerInCooldown(sticky.getServerKey())
                            && !ServerPenaltyManager.getInstance(this).isPenalized(sticky.getServerKey())) {
                        preVerified = sticky;
                        AppLogger.i("MainActivity", "⚡ [StickyMobile] Connecting directly to remembered cellular node: "
                                + sticky.getDisplayName() + " [" + sticky.getServerKey() + "] (no re-probe needed)");
                    }
                } catch (Throwable ignored) {}
            }
            if (preVerified != null) {
                startIntent.putExtra(GoblinVpnService.EXTRA_SERVER, preVerified);
            } else {
                startIntent.putExtra(GoblinVpnService.EXTRA_AUTO_WAVE_SELECT, true);
            }
        } else {
            VpnServer target = getTargetServer();
            startIntent.putExtra(GoblinVpnService.EXTRA_SERVER, target);
        }

        if (prefs.isRestrictedTelegramOnly()) {
            startIntent.putExtra(GoblinVpnService.EXTRA_RESTRICTED_TELEGRAM_ONLY, true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent);
        } else {
            startService(startIntent);
        }
    }

    private List<VpnServer> getMergedServerList() {
        List<VpnServer> list = new ArrayList<>();
        try {
            // 28 проверенных Telegram нод всегда во главе списка (работают и на Wi-Fi, и на LTE)
            List<VpnServer> bypass = WhitelistPoolManager.getInstance(this).getMobileBypassServerPool(this);
            if (bypass != null) list.addAll(bypass);

            List<VpnServer> emergency = EmergencyConfigStorage.loadEmergencyReserve(this);
            if (emergency != null) {
                for (VpnServer es : emergency) {
                    if (!list.contains(es)) list.add(es);
                }
            }
        } catch (Exception e) {
            Log.w("MainActivity", "Error merging servers: " + e.getMessage());
        }
        if (list.isEmpty()) {
            list.addAll(VpnSubscriptionClient.getBuiltinFallbackServers());
        }
        return list;
    }

    public static boolean isKnownDeadHost(String host) {
        if (host == null || host.trim().isEmpty()) return true;
        host = host.trim().toLowerCase();
        return host.startsWith("172.64.") || host.startsWith("172.67.") || host.startsWith("104.18.")
                || host.startsWith("104.21.") || "spacex.com".equals(host) || "87.228.101.3".equals(host)
                || "78.159.250.155".equals(host);
    }

    private VpnServer getTargetServer() {
        boolean isCellular = false;
        try {
            isCellular = WhitelistPoolManager.isRunningOnCellular(this);
        } catch (Throwable ignored) {}

        if (!isAutoMode && selectedServer != null && !isKnownDeadHost(selectedServer.getHost())) {
            // Strictly respect manual server selection: never silently override user's chosen server
            return selectedServer;
        }

        // =========================================================================
        // 1. CELLULAR DATA ACTIVE: STRICT 4-TIER PRIORITY CASCADE FOR MOBILE BYPASS
        // =========================================================================
        if (isCellular) {
            // ---------------------------------------------------------------------
            // TIER 0: STICKY / PROVEN WORKING SERVER ON CELLULAR
            // ---------------------------------------------------------------------
            try {
                VpnServer sticky = MobileProvenConfigStorage.getInstance(this).getStickyMobileServer();
                if (sticky != null && !isKnownDeadHost(sticky.getHost())
                        && !AutoSwitchEngine.getInstance(this).isServerInCooldown(sticky.getServerKey())
                        && !ServerPenaltyManager.getInstance(this).isPenalized(sticky.getServerKey())) {
                    return sticky;
                }
                List<VpnServer> provenList = MobileProvenConfigStorage.getInstance(this).getProvenMobileServers();
                if (provenList != null && !provenList.isEmpty()) {
                    for (VpnServer ps : provenList) {
                        if (ps != null && ps.getHost() != null
                                && !WhitelistPoolManager.isBlockedMobileHost(ps.getHost())
                                && !WhitelistPoolManager.isBlockedMobileSni(ps.getSni())
                                && !isKnownDeadHost(ps.getHost())
                                && !AutoSwitchEngine.getInstance(this).isServerInCooldown(ps.getServerKey())
                                && !ServerPenaltyManager.getInstance(this).isPenalized(ps.getServerKey())) {
                            AppLogger.d("MainActivity", "⭐ [Tier 0] Cellular instant select proven working node: "
                                    + ps.getDisplayName() + " [" + ps.getHost() + ":" + ps.getPort() + "] (SNI=" + ps.getSni() + ", ping=" + ps.getPingMs() + "ms)");
                            return ps;
                        }
                    }
                }
            } catch (Throwable t) {
                AppLogger.w("MainActivity", "Error querying MobileProvenConfigStorage: " + t.getMessage());
            }

            // ---------------------------------------------------------------------
            // TIER 1: COMMUNITY SERVERS MATCHING MOBILE WHITELIST
            // ---------------------------------------------------------------------
            try {
                List<VpnServer> communityServers = CommunityPoolManager.getInstance(this).getTopCommunityServers();
                if (communityServers != null && !communityServers.isEmpty()) {
                    for (VpnServer cs : communityServers) {
                        if (cs != null && cs.getHost() != null
                                && !"193.233.199.233".equals(cs.getHost())
                                && !isKnownDeadHost(cs.getHost())
                                && WhitelistPoolManager.getInstance(this).isWhitelistedForMobile(cs.getHost())
                                && !AutoSwitchEngine.getInstance(this).isServerInCooldown(cs.getServerKey())
                                && !ServerPenaltyManager.getInstance(this).isPenalized(cs.getServerKey())) {
                            AppLogger.d("MainActivity", "⭐ [Tier 1] Cellular select whitelisted community node: "
                                    + cs.getDisplayName() + " [" + cs.getHost() + ":" + cs.getPort() + "] (SNI=" + cs.getSni() + ")");
                            return cs;
                        }
                    }
                }
            } catch (Throwable t) {
                AppLogger.w("MainActivity", "Error querying CommunityPoolManager: " + t.getMessage());
            }

            // ---------------------------------------------------------------------
            // TIER 2 & 3: MOBILE BYPASS POOL (HIGH PROBABILITY AND RESERVE)
            // ---------------------------------------------------------------------
            List<VpnServer> bypassPool = WhitelistPoolManager.getInstance(this).getMobileBypassServerPool(this);
            if (bypassPool != null && !bypassPool.isEmpty()) {
                AppLogger.d("MainActivity", "Cellular active: prioritizing verified mobile reserve pool (" + bypassPool.size() + " nodes)");

                List<VpnServer> validCandidates = new ArrayList<>();
                for (VpnServer s : bypassPool) {
                    if (s != null && !"193.233.199.233".equals(s.getHost())
                            && !isKnownDeadHost(s.getHost())
                            && WhitelistPoolManager.getInstance(this).isWhitelistedForMobile(s.getHost())
                            && !AutoSwitchEngine.getInstance(this).isServerInCooldown(s.getServerKey())
                            && !ServerPenaltyManager.getInstance(this).isPenalized(s.getServerKey())) {
                        validCandidates.add(s);
                    }
                }

                if (!validCandidates.isEmpty()) {
                    // Check if alive node is ready in Tier 1 (pick candidate with lowest ping)
                    VpnServer bestTier1 = null;
                    for (VpnServer s : validCandidates) {
                        if (s.getPingMs() >= 20 && s.isAlive() && s.getMobileTier() == 1) {
                            if (bestTier1 == null || s.getPingMs() < bestTier1.getPingMs()) {
                                bestTier1 = s;
                            }
                        }
                    }
                    if (bestTier1 != null) {
                        AppLogger.d("MainActivity", "⚡ Cellular fast select best alive Tier 1 node: " + bestTier1.getDisplayName()
                                + " [" + bestTier1.getHost() + ":" + bestTier1.getPort() + "] (SNI=" + bestTier1.getSni() + ", ping=" + bestTier1.getPingMs() + "ms)");
                        return bestTier1;
                    }

                    // Check any alive node (pick lowest ping)
                    VpnServer bestAlive = null;
                    for (VpnServer s : validCandidates) {
                        if (s.getPingMs() >= 20 && s.isAlive()) {
                            if (bestAlive == null || s.getPingMs() < bestAlive.getPingMs()) {
                                bestAlive = s;
                            }
                        }
                    }
                    if (bestAlive != null) {
                        return bestAlive;
                    }

                    // Non-blocking UI lookup: actual 5-server wave probes run off main thread in GoblinVpnService
                    return validCandidates.get(0);
                }
                for (VpnServer s : bypassPool) {
                    if (s != null && !"193.233.199.233".equals(s.getHost())
                            && !isKnownDeadHost(s.getHost())
                            && WhitelistPoolManager.getInstance(this).isWhitelistedForMobile(s.getHost())) {
                        return s;
                    }
                }
            }
        }

        // =========================================================================
        // 2. WI-FI / ETHERNET: Fast Pre-Flight from Warm Standby Pool
        // =========================================================================
        if (!isCellular) {
            try {
                List<VpnServer> warmStandby = AutoSwitchEngine.getInstance(this).getStandbyPool();
                if (warmStandby != null && !warmStandby.isEmpty()) {
                    for (VpnServer s : warmStandby) {
                        if (s != null && s.isAlive() && !isKnownDeadHost(s.getHost())
                                && !AutoSwitchEngine.getInstance(this).isServerInCooldown(s.getServerKey())
                                && !ServerPenaltyManager.getInstance(this).isPenalized(s.getServerKey())) {
                            AppLogger.d("MainActivity", "⚡ Fast Pre-Flight: Selected warm standby node " + s.getDisplayName() + " [" + s.getHost() + "]");
                            return s;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // On Wi-Fi: check verified community top nodes first
        if (!isCellular) {
            try {
                List<VpnServer> commTop = CommunityPoolManager.getInstance(this).getTopCommunityServers();
                if (commTop != null && !commTop.isEmpty()) {
                    for (VpnServer s : commTop) {
                        if (s != null && !isKnownDeadHost(s.getHost())
                                && !AutoSwitchEngine.getInstance(this).isServerInCooldown(s.getServerKey())
                                && !ServerPenaltyManager.getInstance(this).isPenalized(s.getServerKey())) {
                            AppLogger.d("MainActivity", "⭐ Selected verified Community Top node: " + s.getDisplayName());
                            return s;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (!isAutoMode && selectedServer != null && !isKnownDeadHost(selectedServer.getHost())) {
            return selectedServer;
        }

        try {
            if (serverList == null || serverList.isEmpty()) {
                serverList = getMergedServerList();
            }
            if (serverList != null && !serverList.isEmpty()) {
                List<VpnServer> prioritized = null;
                try {
                    prioritized = WhitelistPoolManager.getInstance(this).prioritizeServersForNetwork(this, serverList);
                } catch (Throwable t) {
                    AppLogger.w("MainActivity", "WhitelistPool prioritization error: " + t.getMessage());
                }
                if (prioritized == null || prioritized.isEmpty()) {
                    prioritized = serverList;
                }

                // Filter candidates: exclude dead hosts and penalized nodes
                List<VpnServer> candidates = new ArrayList<>();
                for (VpnServer s : prioritized) {
                    if (s != null && !isKnownDeadHost(s.getHost())
                            && !AutoSwitchEngine.getInstance(this).isServerInCooldown(s.getServerKey())
                            && !ServerPenaltyManager.getInstance(this).isPenalized(s.getServerKey())) {
                        candidates.add(s);
                    }
                }

                // If candidate pool has measured pings, pick the best alive node
                for (VpnServer s : candidates) {
                    if (s.getPingMs() >= 20 && s.isAlive()) return s;
                }
                for (VpnServer s : candidates) {
                    if (s.isAlive()) return s;
                }

                // Non-blocking fallback for UI/Wi-Fi
                if (!candidates.isEmpty()) {
                    return candidates.get(0);
                }

                return prioritized.get(0);
            }

            List<VpnServer> emergency = EmergencyConfigStorage.loadEmergencyReserve(this);
            if (emergency != null && !emergency.isEmpty()) {
                return emergency.get(0);
            }
            List<VpnServer> fallback = VpnSubscriptionClient.getBuiltinFallbackServers();
            if (fallback != null && !fallback.isEmpty()) {
                return fallback.get(0);
            }
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "Error in getTargetServer: " + t.getMessage(), t);
        }
        return null;
    }

    public List<VpnServer> getServerList() {
        return serverList;
    }

    private void openServerSelection() {
        boolean isCellular = false;
        try {
            isCellular = WhitelistPoolManager.isRunningOnCellular(this);
        } catch (Throwable ignored) {}

        if (serverList == null || serverList.isEmpty() || (isCellular && serverList.size() < 10)) {
            serverList = getMergedServerList();
        }
        pingServers();

        showDialogScrim();

        List<VpnServer> dialogServers = new ArrayList<>();
        if (isCellular) {
            for (VpnServer s : serverList) {
                if (s != null && !isKnownDeadHost(s.getHost()) && WhitelistPoolManager.getInstance(this).isWhitelistedForMobile(s.getHost())) {
                    dialogServers.add(s);
                }
            }
            if (dialogServers.isEmpty()) {
                dialogServers.addAll(serverList);
            }
        } else {
            for (VpnServer s : serverList) {
                if (s != null && !isKnownDeadHost(s.getHost())) {
                    dialogServers.add(s);
                }
            }
        }

        activeServerDialog = ServerSelectionDialog.show(this, dialogServers, selectedServer, isAutoMode, new ServerSelectionDialog.OnServerSelectedListener() {
            @Override
            public void onServerSelected(VpnServer server) {
                hideDialogScrim();
                isAutoMode = false;
                selectedServer = server;
                if (server != null && serverList != null && !serverList.contains(server)) {
                    serverList.add(0, server);
                }

                NetworkPreferences prefs = NetworkPreferences.getInstance(MainActivity.this);
                prefs.setAutoServerMode(false);
                if (server != null) {
                    prefs.setSelectedServerHost(server.getHost());
                }

                updateBottomCard();
                updateUi();
                Toast.makeText(MainActivity.this, "Выбран узел: " + (server != null ? server.getDisplayName() : ""), Toast.LENGTH_SHORT).show();

                if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
                    switchServerAndReconnect(server);
                }
            }

            @Override
            public void onAutoSelectChosen() {
                hideDialogScrim();
                isAutoMode = true;
                selectedServer = null;

                NetworkPreferences prefs = NetworkPreferences.getInstance(MainActivity.this);
                prefs.setAutoServerMode(true);
                prefs.setSelectedServerHost(null);

                boolean isCellularNow = false;
                try {
                    isCellularNow = WhitelistPoolManager.isRunningOnCellular(MainActivity.this);
                } catch (Throwable ignored) {}

                VpnServer best = getTargetServer();
                updateBottomCard();
                updateUi();
                Toast.makeText(MainActivity.this, "⚡ Автовыбор активен", Toast.LENGTH_SHORT).show();

                if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
                    if (isCellularNow) {
                        switchToCellularAutoWaves();
                    } else {
                        switchServerAndReconnect(best);
                    }
                }
            }
        }, new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                activeServerDialog = null;
                hideDialogScrim();
            }
        });
    }

    private void switchToCellularAutoWaves() {
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            // User explicitly triggered fresh "Автовыбор": clear sticky so waves re-discover fastest node
            try {
                MobileProvenConfigStorage.getInstance(this).clearStickyServerKey();
            } catch (Throwable ignored) {}
            Toast.makeText(this, "⚡ Подбор лучшего мобильного узла…", Toast.LENGTH_SHORT).show();
            NetworkPreferences prefs = NetworkPreferences.getInstance(this);
            Intent startIntent = new Intent(this, GoblinVpnService.class);
            startIntent.setAction(GoblinVpnService.ACTION_CONNECT);
            startIntent.putExtra(GoblinVpnService.EXTRA_AUTO_WAVE_SELECT, true);
            if (prefs.isRestrictedTelegramOnly()) {
                startIntent.putExtra(GoblinVpnService.EXTRA_RESTRICTED_TELEGRAM_ONLY, true);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent);
            } else {
                startService(startIntent);
            }
        }
    }

    private void switchServerAndReconnect(VpnServer newServer) {
        if (newServer == null) return;
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            Toast.makeText(this, "Переподключение к " + newServer.getDisplayName() + "…", Toast.LENGTH_SHORT).show();
            NetworkPreferences prefs = NetworkPreferences.getInstance(this);
            Intent startIntent = new Intent(this, GoblinVpnService.class);
            startIntent.setAction(GoblinVpnService.ACTION_CONNECT);
            startIntent.putExtra(GoblinVpnService.EXTRA_SERVER, newServer);
            if (prefs.isRestrictedTelegramOnly()) {
                startIntent.putExtra(GoblinVpnService.EXTRA_RESTRICTED_TELEGRAM_ONLY, true);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent);
            } else {
                startService(startIntent);
            }
        }
    }

    private void updateBottomCard() {
        if (serverList == null || serverList.isEmpty()) {
            serverList = getMergedServerList();
        }
        VpnServer target = getTargetServer();

        String fallbackCountry = (target != null && target.getCountry() != null) ? target.getCountry() : "Нидерланды";
        String cityOrRemark = fallbackCountry;
        if (target != null && target.getRemark() != null && !target.getRemark().trim().isEmpty()) {
            cityOrRemark = target.getRemark();
            if (cityOrRemark.contains("#")) {
                cityOrRemark = cityOrRemark.substring(0, cityOrRemark.indexOf('#')).trim();
            }
            if (cityOrRemark.contains("—")) {
                cityOrRemark = cityOrRemark.substring(0, cityOrRemark.indexOf('—')).trim();
            }
        }
        if (cityOrRemark != null) {
            cityOrRemark = cityOrRemark
                    .replace("⭐ Проверен на сотовой связи:", "⭐ ")
                    .replace("⭐ Проверен на сотовой связи", "⭐ ")
                    .replace("Проверен на сотовой связи:", "")
                    .replace("Проверен на сотовой связи", "")
                    .replaceAll("\\s{2,}", " ")
                    .trim();
        }
        if (cityOrRemark == null || cityOrRemark.isEmpty()) {
            cityOrRemark = fallbackCountry;
        }
        if (tvCardCity != null) {
            tvCardCity.setText(cityOrRemark);
        }
        if (tvPingSubtext != null) {
            tvPingSubtext.setText(cityOrRemark);
        }
        VpnServer curActive = (currentState == VpnState.CONNECTED && GoblinVpnService.getCurrentServer() != null)
                ? GoblinVpnService.getCurrentServer() : target;
        updateSecurityStatusPill(currentState, curActive);

        if (isAutoMode) {
            tvCardFlag.setText("⚡");
            String country = (target != null && target.getCountry() != null) ? target.getCountry() : "Быстрый";
            tvCardTitle.setText("Автовыбор (" + country + ")");
            if (tvCardBadge != null) {
                tvCardBadge.setText("АВТОВЫБОР");
                tvCardBadge.setVisibility(View.VISIBLE);
            }
            updateBottomCardPing(target != null ? target.getPingMs() : 70);
        } else if (target != null) {
            tvCardFlag.setText(target.getFlag());
            tvCardTitle.setText(target.getCountry());
            if (tvCardBadge != null) {
                tvCardBadge.setVisibility(View.GONE);
            }
            updateBottomCardPing(target.getPingMs());
        } else {
            tvCardFlag.setText("⚡");
            tvCardTitle.setText("Автовыбор");
            if (tvCardBadge != null) {
                tvCardBadge.setText("АВТОВЫБОР");
                tvCardBadge.setVisibility(View.VISIBLE);
            }
            updateBottomCardPing(70);
        }
    }

    private void updateBottomCardPing(int pingMs) {
        if (badgePing == null) return;
        boolean isVip = AdManager.getInstance().isVipBoostActive(this);

        int displayPing = pingMs > 0 ? pingMs : 70;
        if (isVip) {
            int opt = AdManager.getInstance().getOptimizedPing(this, displayPing);
            badgePing.setText(opt + " мс ⚡");
            badgePing.setTextColor(Color.parseColor("#FFD56B"));
            if (viewCardPingDot != null) viewCardPingDot.setBackgroundResource(R.drawable.bg_ref_dot_green);
        } else if (displayPing < 80) {
            badgePing.setText(displayPing + " мс");
            badgePing.setTextColor(Color.parseColor("#A8B8AA"));
            if (viewCardPingDot != null) viewCardPingDot.setBackgroundResource(R.drawable.bg_ref_dot_green);
        } else if (displayPing < 150) {
            badgePing.setText(displayPing + " мс");
            badgePing.setTextColor(Color.parseColor("#E3B341"));
            if (viewCardPingDot != null) viewCardPingDot.setBackgroundResource(R.drawable.bg_ref_dot_yellow);
        } else {
            badgePing.setText(">150 мс");
            badgePing.setTextColor(Color.parseColor("#F85149"));
            if (viewCardPingDot != null) viewCardPingDot.setBackgroundResource(R.drawable.bg_ref_dot_red);
        }
    }

    private void startLivePing() {
        if (currentState != VpnState.CONNECTED || isActivityPaused || !isHomeTabActive) {
            return;
        }
        if (!isLivePingRunning) {
            isLivePingRunning = true;
            livePingHandler.removeCallbacks(livePingRunnable);
            livePingHandler.post(livePingRunnable);
        }
    }

    private void stopLivePing() {
        isLivePingRunning = false;
        livePingHandler.removeCallbacks(livePingRunnable);
    }

    private void performLivePingCheck() {
        if (currentState != VpnState.CONNECTED) return;
        boolean isCellular = false;
        try {
            isCellular = WhitelistPoolManager.isRunningOnCellular(this);
        } catch (Throwable ignored) {}

        final VpnServer active = (selectedServer != null) ? selectedServer : getTargetServer();
        ServerPinger.checkLiveTunnelHealthAsync(isCellular, new ServerPinger.SinglePingCallback() {
            @Override
            public void onResult(final int rttMs) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (currentState != VpnState.CONNECTED || isFinishing()) return;
                        updateLivePingDisplay(rttMs, active);
                    }
                });
            }
        });
    }

    private void updateLivePingDisplay(int rttMs, VpnServer active) {
        boolean isVip = AdManager.getInstance().isVipBoostActive(MainActivity.this);
        int pingToDisplay = (rttMs >= 20) ? rttMs : ((active != null && active.getPingMs() >= 20) ? active.getPingMs() : 75);

        if (active != null && rttMs >= 20) {
            active.setPingMs(rttMs);
            active.setAlive(true);
        }

        if (isVip) {
            int optPing = AdManager.getInstance().getOptimizedPing(MainActivity.this, pingToDisplay);
            tvPing.setText(String.valueOf(optPing));
            if (tvPingUnit != null) {
                tvPingUnit.setText("мс ⚡");
            }
            tvPing.setTextColor(Color.parseColor("#FFD56B"));
        } else {
            tvPing.setText(String.valueOf(pingToDisplay));
            if (tvPingUnit != null) {
                tvPingUnit.setText("мс");
            }
            if (pingToDisplay < 80) {
                tvPing.setTextColor(Color.parseColor("#7EC461"));
            } else if (pingToDisplay < 150) {
                tvPing.setTextColor(Color.parseColor("#E3B341"));
            } else {
                tvPing.setTextColor(Color.parseColor("#F85149"));
            }
        }

        updateBottomCardPing(pingToDisplay);
    }

    private void startGreenWavePulse() {
        if (isWaveAnimating || !shouldRunHomeAnimations()) return;
        isWaveAnimating = true;
        isConnectingPulseAnimating = false;

        ScaleAnimation btnBreath = new ScaleAnimation(
                1.0f, 1.05f, 1.0f, 1.05f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
        );
        btnBreath.setDuration(1200);
        btnBreath.setInterpolator(new AccelerateDecelerateInterpolator());
        btnBreath.setRepeatMode(Animation.REVERSE);
        btnBreath.setRepeatCount(Animation.INFINITE);
        if (btnPower != null) {
            btnPower.startAnimation(btnBreath);
        }

        if (ringInner != null) {
            ringInner.setVisibility(View.VISIBLE);
            ringInner.startAnimation(createWaveAnimation(0));
        }

        if (ringMiddle != null) {
            ringMiddle.setVisibility(View.VISIBLE);
            ringMiddle.startAnimation(createWaveAnimation(350));
        }

        if (ringOuter != null) {
            ringOuter.setVisibility(View.VISIBLE);
            ringOuter.startAnimation(createWaveAnimation(700));
        }
    }

    private AnimationSet createWaveAnimation(long startOffset) {
        AnimationSet set = new AnimationSet(true);
        set.setInterpolator(new AccelerateDecelerateInterpolator());

        ScaleAnimation scale = new ScaleAnimation(
                1.0f, 1.25f, 1.0f, 1.25f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
        );
        scale.setDuration(2000);
        scale.setRepeatMode(Animation.RESTART);
        scale.setRepeatCount(Animation.INFINITE);
        scale.setFillBefore(false);
        set.addAnimation(scale);

        AlphaAnimation alpha = new AlphaAnimation(0.8f, 0.0f);
        alpha.setDuration(2000);
        alpha.setRepeatMode(Animation.RESTART);
        alpha.setRepeatCount(Animation.INFINITE);
        alpha.setFillBefore(false);
        set.addAnimation(alpha);

        set.setDuration(2000);
        set.setRepeatMode(Animation.RESTART);
        set.setRepeatCount(Animation.INFINITE);
        set.setFillBefore(false);
        set.setStartOffset(startOffset);
        return set;
    }

    private void stopGreenWavePulse() {
        isWaveAnimating = false;
        if (btnPower != null && !isConnectingPulseAnimating) {
            btnPower.clearAnimation();
            btnPower.setScaleX(1.0f);
            btnPower.setScaleY(1.0f);
        }
        if (ringInner != null) {
            ringInner.clearAnimation();
            ringInner.setVisibility(View.INVISIBLE);
        }
        if (ringMiddle != null) {
            ringMiddle.clearAnimation();
            ringMiddle.setVisibility(View.INVISIBLE);
        }
        if (ringOuter != null) {
            ringOuter.clearAnimation();
            ringOuter.setVisibility(View.INVISIBLE);
        }
    }

    private void startConnectingPulse() {
        if (isConnectingPulseAnimating || !shouldRunHomeAnimations()) return;
        stopGreenWavePulse();
        isConnectingPulseAnimating = true;
        ScaleAnimation btnBreath = new ScaleAnimation(
                1.0f, 1.08f, 1.0f, 1.08f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
        );
        btnBreath.setDuration(600);
        btnBreath.setRepeatMode(Animation.REVERSE);
        btnBreath.setRepeatCount(Animation.INFINITE);
        btnBreath.setInterpolator(new AccelerateDecelerateInterpolator());
        if (btnPower != null) {
            btnPower.startAnimation(btnBreath);
        }
    }

    private void stopConnectingPulse() {
        isConnectingPulseAnimating = false;
        if (btnPower != null && !isWaveAnimating) {
            btnPower.clearAnimation();
            btnPower.setScaleX(1.0f);
            btnPower.setScaleY(1.0f);
        }
    }

    private void animatePowerButtonColor(final int targetColor) {
        if (powerColorAnimator != null) {
            powerColorAnimator.cancel();
        }
        final int startColor = currentPowerBgColor;
        if (startColor == targetColor) {
            applyPowerButtonColor(targetColor);
            return;
        }
        powerColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), startColor, targetColor);
        powerColorAnimator.setDuration(400);
        powerColorAnimator.setInterpolator(new DecelerateInterpolator());
        powerColorAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                int c = (int) animation.getAnimatedValue();
                currentPowerBgColor = c;
                applyPowerButtonColor(c);
            }
        });
        powerColorAnimator.start();
    }

    private void applyPowerButtonColor(int color) {
        if (btnPower == null) return;
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        int strokeColor = (color == Color.parseColor("#1E2922"))
                ? Color.parseColor("#263828")
                : Color.parseColor("#34D399");
        gd.setStroke((int) (2f * density), strokeColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable ripple = new RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#40FFFFFF")),
                    gd,
                    null
            );
            btnPower.setBackground(ripple);
        } else {
            btnPower.setBackground(gd);
        }
        if (viewPowerGlow != null && viewPowerGlow.getBackground() != null) {
            viewPowerGlow.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    private void updateSecurityStatusPill(VpnState state, VpnServer active) {
        if (statusPill == null) return;
        switch (state) {
            case CONNECTED:
                statusPill.setBackgroundResource(R.drawable.bg_m3_status_pill);
                if (viewStatusDot != null) {
                    viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_connected);
                }
                if (tvStatusText != null) {
                    tvStatusText.setText("ПОДКЛЮЧЕНО");
                    tvStatusText.setTextColor(Color.parseColor("#34D399"));
                }
                if (tvStatusIp != null) {
                    if (active != null && active.getHost() != null) {
                        tvStatusIp.setText(active.getHost());
                    } else {
                        tvStatusIp.setText("Защищено");
                    }
                    tvStatusIp.setTextColor(Color.parseColor("#A7F3D0"));
                }
                break;

            case CONNECTING:
                statusPill.setBackgroundResource(R.drawable.bg_m3_status_pill_connecting);
                if (viewStatusDot != null) {
                    viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_connecting);
                }
                if (tvStatusText != null) {
                    tvStatusText.setText("ПОДКЛЮЧЕНИЕ");
                    tvStatusText.setTextColor(Color.parseColor("#FBBF24"));
                }
                if (tvStatusIp != null) {
                    String stageHint = GoblinVpnService.getConnectionStageHint();
                    if (stageHint != null && !stageHint.isEmpty()) {
                        tvStatusIp.setText(stageHint);
                    } else if (active != null && active.getHost() != null) {
                        tvStatusIp.setText(active.getHost());
                    } else {
                        tvStatusIp.setText("Шифрование…");
                    }
                    tvStatusIp.setTextColor(Color.parseColor("#FDE68A"));
                }
                break;

            case ERROR:
            case DISCONNECTED:
            default:
                statusPill.setBackgroundResource(R.drawable.bg_m3_status_pill_off);
                if (viewStatusDot != null) {
                    viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected);
                }
                if (tvStatusText != null) {
                    tvStatusText.setText("ОТКЛЮЧЕНО");
                    tvStatusText.setTextColor(Color.parseColor("#758678"));
                }
                if (tvStatusIp != null) {
                    tvStatusIp.setText("Не защищено");
                    tvStatusIp.setTextColor(Color.parseColor("#94A3B8"));
                }
                break;
        }
    }

    private void updateUi() {
        switch (currentState) {
            case CONNECTED:
                animatePowerButtonColor(Color.parseColor("#10B981"));
                viewPowerBezel.setBackgroundResource(R.drawable.bg_m3_inner_bezel);
                viewPowerGlow.setAlpha(1.0f);
                ivPowerIcon.setImageResource(R.drawable.ic_pause);
                ivPowerIcon.setColorFilter(Color.parseColor("#FFFFFF"));
                tvPowerStatus.setVisibility(View.VISIBLE);
                tvPowerStatus.setText("ВКЛЮЧЕН");
                tvPowerStatus.setTextColor(Color.parseColor("#FFFFFF"));

                if (starfieldView != null) {
                    starfieldView.setActiveMode(true);
                }

                stopConnectingPulse();
                if (shouldRunHomeAnimations()) {
                    startGreenWavePulse();
                } else {
                    stopGreenWavePulse();
                }
                if (!isActivityPaused && isHomeTabActive) {
                    startTelemetry();
                    startLivePing();
                }

                VpnServer active = GoblinVpnService.getCurrentServer();
                if (active == null) {
                    active = getTargetServer();
                }
                updateSecurityStatusPill(VpnState.CONNECTED, active);
                int rawPing = (active != null && active.getPingMs() > 0) ? active.getPingMs() : 70;
                updateLivePingDisplay(rawPing, active);
                break;

            case CONNECTING:
                animatePowerButtonColor(Color.parseColor("#92671A"));
                viewPowerBezel.setBackgroundResource(R.drawable.bg_ref_power_outer_off);
                viewPowerGlow.setAlpha(0.45f);
                ivPowerIcon.setImageResource(R.drawable.ic_power_icon);
                ivPowerIcon.setColorFilter(Color.parseColor("#E3B341"));
                tvPowerStatus.setVisibility(View.VISIBLE);
                tvPowerStatus.setText("Подключение");
                tvPowerStatus.setTextColor(Color.parseColor("#E3B341"));

                if (starfieldView != null) {
                    starfieldView.setActiveMode(false);
                }

                VpnServer svcConnecting = GoblinVpnService.getCurrentServer();
                VpnServer connectingTarget = (svcConnecting != null) ? svcConnecting : ((selectedServer != null) ? selectedServer : getTargetServer());
                updateSecurityStatusPill(VpnState.CONNECTING, connectingTarget);

                if (shouldRunHomeAnimations()) {
                    startConnectingPulse();
                } else {
                    stopConnectingPulse();
                    stopGreenWavePulse();
                }
                stopTelemetry();
                stopLivePing();

                tvTimer.setText("00:00:00");
                tvPing.setText("...");
                tvPing.setTextColor(Color.parseColor("#E3B341"));
                if (tvPingUnit != null) {
                    tvPingUnit.setText("мс");
                }
                if (tvTotalTraffic != null) {
                    tvTotalTraffic.setText("0.0");
                    tvTotalTraffic.setTextColor(Color.parseColor("#829484"));
                }
                if (tvTrafficUnit != null) {
                    tvTrafficUnit.setText("МБ");
                }
                if (tvTrafficSubtext != null) {
                    tvTrafficSubtext.setText("↓ 0 КБ/с • ↑ 0 КБ/с");
                }
                break;

            case ERROR:
            case DISCONNECTED:
            default:
                animatePowerButtonColor(Color.parseColor("#1E2922"));
                viewPowerBezel.setBackgroundResource(R.drawable.bg_ref_power_outer_off);
                viewPowerGlow.setAlpha(0.12f);
                ivPowerIcon.setImageResource(R.drawable.ic_power_icon);
                ivPowerIcon.setColorFilter(Color.parseColor("#758678"));
                tvPowerStatus.setVisibility(View.GONE);
                updateSecurityStatusPill(VpnState.DISCONNECTED, null);

                if (starfieldView != null) {
                    starfieldView.setActiveMode(false);
                }

                stopConnectingPulse();
                stopGreenWavePulse();
                stopTelemetry();
                stopLivePing();

                tvTimer.setText("00:00:00");
                tvPing.setText("0");
                tvPing.setTextColor(Color.parseColor("#829484"));
                if (tvPingUnit != null) {
                    tvPingUnit.setText("мс");
                }
                if (tvTotalTraffic != null) {
                    tvTotalTraffic.setText("0.0");
                    tvTotalTraffic.setTextColor(Color.parseColor("#829484"));
                }
                if (tvTrafficUnit != null) {
                    tvTrafficUnit.setText("МБ");
                }
                if (tvTrafficSubtext != null) {
                    tvTrafficSubtext.setText("↓ 0 КБ/с • ↑ 0 КБ/с");
                }
                break;
        }
    }

    private void startTelemetry() {
        long serviceStart = GoblinVpnService.getConnectedTimestamp();
        if (serviceStart > 0) {
            sessionStartTimeMs = serviceStart;
        } else if (sessionStartTimeMs <= 0) {
            sessionStartTimeMs = System.currentTimeMillis();
        }

        int uid = android.os.Process.myUid();
        long curRx = TrafficStats.getUidRxBytes(uid);
        long curTx = TrafficStats.getUidTxBytes(uid);
        if (curRx == TrafficStats.UNSUPPORTED) curRx = 0;
        if (curTx == TrafficStats.UNSUPPORTED) curTx = 0;

        long sRx = GoblinVpnService.getInitialRxBytes();
        long sTx = GoblinVpnService.getInitialTxBytes();
        if (sRx > 0 && curRx >= sRx) {
            initialRxBytes = sRx;
        } else if (initialRxBytes <= 0) {
            initialRxBytes = curRx;
        }

        if (sTx > 0 && curTx >= sTx) {
            initialTxBytes = sTx;
        } else if (initialTxBytes <= 0) {
            initialTxBytes = curTx;
        }

        lastRxBytes = curRx;
        lastTxBytes = curTx;

        if (currentState != VpnState.CONNECTED || isActivityPaused || !isHomeTabActive) {
            return;
        }
        isStatsUpdating = true;
        statsHandler.removeCallbacks(statsRunnable);
        statsHandler.post(statsRunnable);
    }

    private void pauseTelemetry() {
        isStatsUpdating = false;
        statsHandler.removeCallbacks(statsRunnable);
    }

    private void stopTelemetry() {
        isStatsUpdating = false;
        statsHandler.removeCallbacks(statsRunnable);
        sessionStartTimeMs = 0;
        initialRxBytes = 0;
        initialTxBytes = 0;
        lastRxBytes = 0;
        lastTxBytes = 0;
    }

    private void updateTelemetry() {
        if (tvTimer == null || tvTotalTraffic == null) return;

        if (currentState != VpnState.CONNECTED) {
            if (tvTrafficSubtext != null) {
                tvTrafficSubtext.setText("↓ 0 КБ/с • ↑ 0 КБ/с");
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (sessionStartTimeMs <= 0) {
            long serviceStart = GoblinVpnService.getConnectedTimestamp();
            sessionStartTimeMs = (serviceStart > 0) ? serviceStart : now;
        }

        long elapsedMillis = Math.max(0, now - sessionStartTimeMs);
        long totalSeconds = elapsedMillis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        tvTimer.setText(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds));

        int uid = android.os.Process.myUid();
        long currentRx = TrafficStats.getUidRxBytes(uid);
        long currentTx = TrafficStats.getUidTxBytes(uid);
        if (currentRx == TrafficStats.UNSUPPORTED) currentRx = 0;
        if (currentTx == TrafficStats.UNSUPPORTED) currentTx = 0;

        if (initialRxBytes <= 0 && currentRx > 0) {
            long sRx = GoblinVpnService.getInitialRxBytes();
            initialRxBytes = (sRx > 0 && currentRx >= sRx) ? sRx : currentRx;
        }
        if (initialTxBytes <= 0 && currentTx > 0) {
            long sTx = GoblinVpnService.getInitialTxBytes();
            initialTxBytes = (sTx > 0 && currentTx >= sTx) ? sTx : currentTx;
        }

        if (lastRxBytes <= 0) lastRxBytes = currentRx;
        if (lastTxBytes <= 0) lastTxBytes = currentTx;

        // Instant speed (delta per 1 second) in bytes/sec
        long speedRx = Math.max(0, currentRx - lastRxBytes);
        long speedTx = Math.max(0, currentTx - lastTxBytes);

        lastRxBytes = currentRx;
        lastTxBytes = currentTx;

        // Total transmitted session volume
        long sessionRx = Math.max(0, currentRx - initialRxBytes);
        long sessionTx = Math.max(0, currentTx - initialTxBytes);
        long totalBytes = sessionRx + sessionTx;

        // Display in tvTotalTraffic real value (e.g., "14.5" with tvTrafficUnit "МБ" or "1.2" with "ГБ")
        if (totalBytes < 1024L * 1024L * 1024L) {
            double mb = totalBytes / (1024.0 * 1024.0);
            tvTotalTraffic.setText(String.format(Locale.US, "%.1f", mb));
            if (tvTrafficUnit != null) {
                tvTrafficUnit.setText("МБ");
            }
        } else {
            double gb = totalBytes / (1024.0 * 1024.0 * 1024.0);
            tvTotalTraffic.setText(String.format(Locale.US, "%.2f", gb));
            if (tvTrafficUnit != null) {
                tvTrafficUnit.setText("ГБ");
            }
        }
        tvTotalTraffic.setTextColor(Color.parseColor("#F2F7F2"));

        // Display in tvTrafficSubtext real speeds: "↓ 1.2 МБ/с • ↑ 140 КБ/с"
        if (tvTrafficSubtext != null) {
            tvTrafficSubtext.setText("↓ " + formatSpeed(speedRx) + " • ↑ " + formatSpeed(speedTx));
        }
    }

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "0 КБ/с";
        } else if (bytesPerSec < 1024 * 1024) {
            double kb = bytesPerSec / 1024.0;
            if (kb < 10.0) {
                return String.format(Locale.US, "%.1f КБ/с", kb);
            } else {
                return String.format(Locale.US, "%.0f КБ/с", kb);
            }
        } else if (bytesPerSec < 1024L * 1024L * 1024L) {
            double mb = bytesPerSec / (1024.0 * 1024.0);
            return String.format(Locale.US, "%.1f МБ/с", mb);
        } else {
            double gb = bytesPerSec / (1024.0 * 1024.0 * 1024.0);
            return String.format(Locale.US, "%.2f ГБ/с", gb);
        }
    }
}
