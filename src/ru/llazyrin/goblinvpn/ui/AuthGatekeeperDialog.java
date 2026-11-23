package ru.llazyrin.goblinvpn.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.CycleInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import ru.llazyrin.goblinvpn.MainActivity;
import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.model.NetworkPreferences;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.net.AppTelemetryClient;
import ru.llazyrin.goblinvpn.net.VpnSubscriptionClient;
import ru.llazyrin.goblinvpn.security.EmergencyConfigStorage;
import ru.llazyrin.goblinvpn.service.AppLogger;
import ru.llazyrin.goblinvpn.service.GoblinVpnService;
import ru.llazyrin.goblinvpn.service.VpnState;

/**
 * AuthGatekeeperDialog — Обязательное модальное окно авторизации/подписки на канал @llazyrin.
 * Включает строгий неблокируемый экран, проверку подписки через API, соглашение о конфиденциальности,
 * а также экстренный 5-минутный доступ в Telegram со счетчиком и выбором серверов сообщества.
 */
public class AuthGatekeeperDialog extends Dialog {
    private static final String TAG = "AuthGatekeeperDialog";
    private static final long EMERGENCY_DURATION_MS = 300000L; // 5 минут
    private static final long TWO_DAYS_MS = 2 * 24 * 3600 * 1000L; // 48 часов

    private static volatile AuthGatekeeperDialog currentDialog = null;

    private final MainActivity activity;
    private final NetworkPreferences prefs;

    private CheckBox cbPrivacy;
    private TextView tvPrivacyLink;
    private FrameLayout btnSubscribe;
    private FrameLayout btnCheckSub;
    private TextView tvStatus;

    private TextView tvTimer;
    private FrameLayout btnEmergencyVpn;
    private TextView tvEmergencyBtnText;
    private LinearLayout layoutServerChips;

    private CountDownTimer emergencyTimer = null;
    private boolean isEmergencyActive = false;
    private VpnServer selectedEmergencyServer = null;
    private final List<VpnServer> communityServers = new ArrayList<>();
    private final List<View> chipViews = new ArrayList<>();

    public AuthGatekeeperDialog(MainActivity activity) {
        super(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.activity = activity;
        this.prefs = NetworkPreferences.getInstance(activity);
    }

    /**
     * Основная точка входа: проверка статуса подписки и показ диалога при необходимости.
     */
    public static synchronized void checkAndShowIfNeeded(final MainActivity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final NetworkPreferences prefs = NetworkPreferences.getInstance(activity);
        boolean isSubscribed = prefs.isTelegramSubscribed();
        long lastCheck = prefs.getLastSubscriptionCheckTimestamp();
        long now = System.currentTimeMillis();

        if (!isSubscribed) {
            AppLogger.i(TAG, "Пользователь не подписан на канал @llazyrin. Показ диалога авторизации.");
            showDialog(activity);
        } else if (now - lastCheck > TWO_DAYS_MS) {
            AppLogger.i(TAG, "Прошло более 2 дней с последней проверки подписки. Фоновая верификация...");
            AppTelemetryClient.checkSubscriptionAsync(activity, null, new AppTelemetryClient.SubscriptionCheckCallback() {
                @Override
                public void onResult(boolean subscribed, String message) {
                    if (activity.isFinishing()) return;
                    if (!subscribed) {
                        AppLogger.w(TAG, "Подписка не подтверждена сервером (" + message + "). Сброс флага подписки.");
                        prefs.setTelegramSubscribed(false);
                        showDialog(activity);
                    } else {
                        AppLogger.i(TAG, "Фоновая верификация успешна: подписка активна.");
                        prefs.setLastSubscriptionCheckTimestamp(System.currentTimeMillis());
                    }
                }

                @Override
                public void onError(Exception error) {
                    AppLogger.w(TAG, "Ошибка фоновой верификации подписки: " + error.getMessage() + ". Доступ сохранен.");
                }
            });
        }
    }

    public static synchronized void showDialog(final MainActivity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) return;

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity == null || activity.isFinishing()) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) return;
                try {
                    if (currentDialog != null && currentDialog.isShowing()) {
                        return;
                    }
                    currentDialog = new AuthGatekeeperDialog(activity);
                    currentDialog.show();
                } catch (Throwable t) {
                    Log.e(TAG, "Error showing AuthGatekeeperDialog: " + t.getMessage(), t);
                }
            }
        }, 500);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.dialog_auth_gatekeeper);

            setCancelable(false);
            setCanceledOnTouchOutside(false);

            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setGravity(Gravity.CENTER);
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setDimAmount(0.75f);

                // Android 12+ hardware blur
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                        WindowManager.LayoutParams lp = window.getAttributes();
                        lp.setBlurBehindRadius(35);
                        window.setAttributes(lp);
                    } catch (Throwable ignored) {}
                }
            }

            initViews();
            loadCommunityServers();
            setupListeners();
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error in AuthGatekeeperDialog onCreate: " + t.getMessage(), t);
        }
    }

    private void initViews() {
        cbPrivacy = findViewById(R.id.cbGatekeeperPrivacy);
        tvPrivacyLink = findViewById(R.id.tvGatekeeperPrivacyLink);
        btnSubscribe = findViewById(R.id.btnGatekeeperSubscribe);
        btnCheckSub = findViewById(R.id.btnGatekeeperCheckSub);
        tvStatus = findViewById(R.id.tvGatekeeperStatus);

        tvTimer = findViewById(R.id.tvGatekeeperTimer);
        btnEmergencyVpn = findViewById(R.id.btnGatekeeperEmergencyVpn);
        tvEmergencyBtnText = findViewById(R.id.tvGatekeeperEmergencyBtnText);
        layoutServerChips = findViewById(R.id.layoutGatekeeperServerChips);

        // Pre-check if already consent was given
        if (prefs.isUserLoggingConsentGranted()) {
            cbPrivacy.setChecked(true);
        }
    }

    private void setupListeners() {
        // Privacy link click
        tvPrivacyLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                new PrivacyPolicyDialog(activity).show();
            }
        });

        cbPrivacy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                prefs.setUserLoggingConsentGranted(cbPrivacy.isChecked());
            }
        });

        // "Подписаться на канал" button -> open Telegram
        btnSubscribe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                openTelegramChannel();
            }
        });

        // "Я подписался" button -> check subscription
        btnCheckSub.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                handleCheckSubscription();
            }
        });

        // "Запустить 5 мин" / "Продлить время" button
        btnEmergencyVpn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                handleEmergencyVpnClick();
            }
        });
    }

    private void openTelegramChannel() {
        try {
            Intent tgIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=llazyrin"));
            tgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(tgIntent);
        } catch (Exception e) {
            try {
                Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/llazyrin"));
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(webIntent);
            } catch (Exception ex) {
                Toast.makeText(activity, "Не удалось открыть Telegram: https://t.me/llazyrin", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void handleCheckSubscription() {
        if (!cbPrivacy.isChecked()) {
            shakeView(cbPrivacy);
            Toast.makeText(activity, "Необходимо принять правила конфиденциальности", Toast.LENGTH_SHORT).show();
            return;
        }

        btnCheckSub.setEnabled(false);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setTextColor(0xFF38BDF8);
        tvStatus.setText("⏳ Проверка подписки на канал @llazyrin...");

        AppTelemetryClient.checkSubscriptionAsync(activity, null, new AppTelemetryClient.SubscriptionCheckCallback() {
            @Override
            public void onResult(boolean subscribed, String message) {
                if (activity.isFinishing()) return;
                btnCheckSub.setEnabled(true);

                if (subscribed) {
                    prefs.setTelegramSubscribed(true);
                    prefs.setLastSubscriptionCheckTimestamp(System.currentTimeMillis());

                    // Cancel emergency timer & lift restricted routing
                    cancelEmergencyTimer();
                    prefs.setRestrictedTelegramOnly(false);

                    Toast.makeText(activity, "✅ Подписка подтверждена! Полный доступ разблокирован.", Toast.LENGTH_LONG).show();
                    dismiss();
                } else {
                    HapticFeedbackHelper.performErrorFeedback(btnCheckSub);
                    tvStatus.setVisibility(View.VISIBLE);
                    tvStatus.setTextColor(0xFFEF4444);
                    tvStatus.setText("❌ Подписка на @llazyrin не обнаружена. Пожалуйста, подпишитесь на канал и нажмите 'Я подписался'.");
                }
            }

            @Override
            public void onError(Exception error) {
                if (activity.isFinishing()) return;
                btnCheckSub.setEnabled(true);
                HapticFeedbackHelper.performErrorFeedback(btnCheckSub);
                tvStatus.setVisibility(View.VISIBLE);
                tvStatus.setTextColor(0xFFFBBF24);
                tvStatus.setText("⚠️ Не удалось связаться с сервером проверки. Проверьте интернет или запустите 5 мин VPN.");
            }
        });
    }

    private void handleEmergencyVpnClick() {
        if (!cbPrivacy.isChecked()) {
            shakeView(cbPrivacy);
            Toast.makeText(activity, "Необходимо принять правила конфиденциальности", Toast.LENGTH_SHORT).show();
            return;
        }

        // Start or Extend 5-Minute Emergency VPN
        startOrExtendEmergencyVpn();
    }

    private void startOrExtendEmergencyVpn() {
        // Set restricted mode: only Telegram
        prefs.setRestrictedTelegramOnly(true);

        // Check VPN preparation permission
        Intent prepareIntent = VpnService.prepare(activity);
        if (prepareIntent != null) {
            Toast.makeText(activity, "Требуется подтвердить VPN-подключение Android", Toast.LENGTH_LONG).show();
            activity.startActivityForResult(prepareIntent, 42);
            return;
        }

        // Connect VPN in restricted mode
        VpnServer targetServer = (selectedEmergencyServer != null) ? selectedEmergencyServer : getDefaultServer();
        Intent vpnIntent = new Intent(activity, GoblinVpnService.class);
        vpnIntent.setAction(GoblinVpnService.ACTION_CONNECT);
        vpnIntent.putExtra(GoblinVpnService.EXTRA_SERVER, targetServer);
        vpnIntent.putExtra(GoblinVpnService.EXTRA_RESTRICTED_TELEGRAM_ONLY, true);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(vpnIntent);
            } else {
                activity.startService(vpnIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start emergency VPN: " + e.getMessage(), e);
        }

        // Setup 5-Minute Timer
        if (emergencyTimer != null) {
            emergencyTimer.cancel();
        }

        isEmergencyActive = true;
        tvEmergencyBtnText.setText("Продлить время");
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setTextColor(0xFF10B981);
        tvStatus.setText("⚡ Экстренный доступ в Telegram активен (" + (targetServer != null ? targetServer.getCountry() : "VPN") + ")");

        emergencyTimer = new CountDownTimer(EMERGENCY_DURATION_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long minutes = (millisUntilFinished / 1000) / 60;
                long seconds = (millisUntilFinished / 1000) % 60;
                tvTimer.setText(String.format(Locale.US, "%02d:%02d", minutes, seconds));
            }

            @Override
            public void onFinish() {
                isEmergencyActive = false;
                tvTimer.setText("00:00");
                tvEmergencyBtnText.setText("Запустить 5 мин");

                // Stop emergency VPN
                prefs.setRestrictedTelegramOnly(false);
                Intent stopIntent = new Intent(activity, GoblinVpnService.class);
                stopIntent.setAction(GoblinVpnService.ACTION_DISCONNECT);
                try {
                    activity.startService(stopIntent);
                } catch (Exception ignored) {}

                tvStatus.setVisibility(View.VISIBLE);
                tvStatus.setTextColor(0xFFFBBF24);
                tvStatus.setText("Время истекло. Нажмите 'Продлить', если нужно еще 5 минут.");
                HapticFeedbackHelper.performErrorFeedback(btnEmergencyVpn);
            }
        };
        emergencyTimer.start();
    }

    private void cancelEmergencyTimer() {
        if (emergencyTimer != null) {
            emergencyTimer.cancel();
            emergencyTimer = null;
        }
        isEmergencyActive = false;
    }

    private void loadCommunityServers() {
        communityServers.clear();

        // 1. Try servers loaded by MainActivity
        if (activity.getServerList() != null && !activity.getServerList().isEmpty()) {
            communityServers.addAll(activity.getServerList());
        }

        // 2. Fallback to EmergencyConfigStorage
        if (communityServers.isEmpty()) {
            communityServers.addAll(EmergencyConfigStorage.loadEmergencyReserve(activity));
        }

        // 3. Fallback to built-in fallback servers
        if (communityServers.isEmpty()) {
            communityServers.addAll(VpnSubscriptionClient.getBuiltinFallbackServers());
        }

        // Sort by ping / latency to prioritize best community servers
        Collections.sort(communityServers, new Comparator<VpnServer>() {
            @Override
            public int compare(VpnServer a, VpnServer b) {
                int pA = a.getPingMs() > 0 ? a.getPingMs() : 999;
                int pB = b.getPingMs() > 0 ? b.getPingMs() : 999;
                return Integer.compare(pA, pB);
            }
        });

        if (!communityServers.isEmpty()) {
            selectedEmergencyServer = communityServers.get(0);
        }

        populateServerChips();
    }

    private void populateServerChips() {
        if (layoutServerChips == null) return;
        layoutServerChips.removeAllViews();
        chipViews.clear();

        LayoutInflater inflater = LayoutInflater.from(activity);
        int maxChips = Math.min(communityServers.size(), 8);

        for (int i = 0; i < maxChips; i++) {
            final VpnServer server = communityServers.get(i);
            View chipView = inflater.inflate(R.layout.item_gatekeeper_server_chip, layoutServerChips, false);

            TextView tvFlag = chipView.findViewById(R.id.tvChipFlag);
            TextView tvTitle = chipView.findViewById(R.id.tvChipTitle);
            TextView tvPing = chipView.findViewById(R.id.tvChipPing);

            if (tvFlag != null) {
                tvFlag.setText(server.getFlag() != null ? server.getFlag() : "🌐");
            }
            if (tvTitle != null) {
                tvTitle.setText(server.getCountry() != null ? server.getCountry() : "Сервер");
            }
            if (tvPing != null) {
                int ping = server.getPingMs();
                if (ping > 0) {
                    tvPing.setText(ping + "ms");
                    tvPing.setTextColor(ping < 120 ? 0xFF10B981 : 0xFFFBBF24);
                } else {
                    tvPing.setText("⚡ Top");
                    tvPing.setTextColor(0xFF10B981);
                }
            }

            chipView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    selectedEmergencyServer = server;
                    updateChipSelection();
                }
            });

            chipViews.add(chipView);
            layoutServerChips.addView(chipView);
        }

        updateChipSelection();
    }

    private void updateChipSelection() {
        for (int i = 0; i < chipViews.size(); i++) {
            View view = chipViews.get(i);
            if (i >= communityServers.size()) break;
            VpnServer server = communityServers.get(i);

            boolean isSelected = (selectedEmergencyServer != null
                    && selectedEmergencyServer.getHost() != null
                    && selectedEmergencyServer.getHost().equals(server.getHost()));

            view.setBackgroundResource(isSelected ? R.drawable.bg_m3_chip_active : R.drawable.bg_m3_chip);
            TextView tvTitle = view.findViewById(R.id.tvChipTitle);
            if (tvTitle != null) {
                tvTitle.setTextColor(isSelected ? 0xFFFFFFFF : 0xFF94A3B8);
            }
        }
    }

    private VpnServer getDefaultServer() {
        if (!communityServers.isEmpty()) {
            return communityServers.get(0);
        }
        return new VpnServer(null, "127.0.0.1", 443, "🇩🇪", "Германия", "Community Top", 45);
    }

    private void shakeView(View view) {
        if (view == null) return;
        HapticFeedbackHelper.performErrorFeedback(view);
        Animation shake = new TranslateAnimation(0, 15, 0, 0);
        shake.setDuration(300);
        shake.setInterpolator(new CycleInterpolator(3));
        view.startAnimation(shake);
    }

    @Override
    public void onBackPressed() {
        // Intercept back button: user cannot dismiss without authorization
        HapticFeedbackHelper.performErrorFeedback(btnSubscribe);
        Toast.makeText(activity, "Для продолжения необходимо подтвердить подписку на @llazyrin", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void dismiss() {
        cancelEmergencyTimer();
        if (currentDialog == this) {
            currentDialog = null;
        }
        super.dismiss();
    }
}
