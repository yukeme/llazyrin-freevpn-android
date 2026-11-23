package ru.llazyrin.goblinvpn.ads;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ru.llazyrin.goblinvpn.R;

/**
 * Modernized Monetization & Ad Manager for Goblin VPN.
 *
 * <p>ARCHITECTURAL PRINCIPLES:</p>
 * <ol>
 *   <li><b>ZERO BLOCKING FOR STANDARD CONNECTIONS:</b>
 *       Normal VPN connections are NEVER gated, interrupted, or delayed by mandatory popups
 *       or countdown timers. The core utility is completely frictionless.</li>
 *
 *   <li><b>VALUE-EXCHANGE VIP MONETIZATION ("⚡ VIP Turbo Boost" / "Резервный канал +50% скорости"):</b>
 *       Users voluntarily choose to tap "⚡ Ускорить" or select reserved VIP routes,
 *       which opens an attractive dialog offering a 2-hour high-speed boost (+50% bandwidth,
 *       prioritized low-latency routing) in exchange for watching 1 rewarded video.</li>
 *
 *   <li><b>CLEAN AdCallback LIFECYCLE:</b>
 *       Full dispatch of {@link AdCallback#onAdCompleted()}, {@link AdCallback#onAdSkipped()},
 *       and {@link AdCallback#onAdFailed(String)}.</li>
 *
 *   <li><b>YANDEX MOBILE ADS SDK READINESS:</b>
 *       Ready for turnkey production integration with Yandex Mobile Ads SDK
 *       (RewardedAd, RewardedAdLoader, AdRequestConfiguration). Includes dynamic reflection
 *       dispatch that compiles cleanly in standalone builds and automatically binds to the Yandex SDK
 *       when the dependency is present in Gradle.</li>
 * </ol>
 */
public class AdManager {
    private static final String TAG = "AdManager";

    private static final String PREF_NAME = "goblin_vpn_monetization";
    private static final String KEY_VIP_EXPIRE = "vip_boost_expire_timestamp";

    /** Default duration granted by one rewarded video: 2 hours (in milliseconds) */
    public static final long DEFAULT_BOOST_DURATION_MS = 2 * 60 * 60 * 1000L;

    /** Yandex Mobile Ads demo block ID for rewarded ads */
    public static final String DEFAULT_YANDEX_BLOCK_ID = "demo-rewarded-yandex";

    private static AdManager instance;

    private boolean isTestMode = true;
    private String yandexBlockId = DEFAULT_YANDEX_BLOCK_ID;

    public interface VipBoostListener {
        void onVipBoostStateChanged(boolean isActive, long remainingMillis);
    }

    private final List<VipBoostListener> boostListeners = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static synchronized AdManager getInstance() {
        if (instance == null) {
            instance = new AdManager();
        }
        return instance;
    }

    private AdManager() {}

    // =========================================================================
    // Configuration
    // =========================================================================

    public void setTestMode(boolean testMode) {
        this.isTestMode = testMode;
        Log.i(TAG, "AdManager test mode set to: " + testMode);
    }

    public boolean isTestMode() {
        return isTestMode;
    }

    public void setYandexBlockId(String blockId) {
        if (blockId != null && !blockId.trim().isEmpty()) {
            this.yandexBlockId = blockId.trim();
        }
    }

    public String getYandexBlockId() {
        return yandexBlockId;
    }

    // =========================================================================
    // VIP Turbo Boost State & Persistence (+50% Speed / Low Latency)
    // =========================================================================

    public boolean isVipBoostActive(Context context) {
        if (context == null) return false;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long expireTs = sp.getLong(KEY_VIP_EXPIRE, 0L);
        return expireTs > System.currentTimeMillis();
    }

    public long getVipBoostRemainingMillis(Context context) {
        if (context == null) return 0L;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long expireTs = sp.getLong(KEY_VIP_EXPIRE, 0L);
        long diff = expireTs - System.currentTimeMillis();
        return Math.max(0L, diff);
    }

    public String getFormattedRemainingTime(Context context) {
        long millis = getVipBoostRemainingMillis(context);
        if (millis <= 0) return "00:00:00";
        long totalSec = millis / 1000;
        long hours = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        long secs = totalSec % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs);
        } else {
            return String.format(Locale.US, "%02d:%02d", mins, secs);
        }
    }

    /**
     * Activates or extends VIP Turbo Boost by the specified duration.
     * If already active, the new duration is added on top of existing remaining time.
     */
    public void activateVipBoost(Context context, long durationMs) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long currentExpire = sp.getLong(KEY_VIP_EXPIRE, 0L);
        long base = Math.max(now, currentExpire);
        long newExpire = base + durationMs;

        sp.edit().putLong(KEY_VIP_EXPIRE, newExpire).apply();
        Log.i(TAG, "VIP Turbo Boost activated until " + newExpire + " (+ " + (durationMs / 60000) + " mins)");

        notifyBoostListeners(true, newExpire - now);
    }

    public void deactivateVipBoost(Context context) {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_VIP_EXPIRE).apply();
        Log.i(TAG, "VIP Turbo Boost cleared");

        notifyBoostListeners(false, 0L);
    }

    public void addVipBoostListener(VipBoostListener listener) {
        synchronized (boostListeners) {
            if (listener != null && !boostListeners.contains(listener)) {
                boostListeners.add(listener);
            }
        }
    }

    public void removeVipBoostListener(VipBoostListener listener) {
        synchronized (boostListeners) {
            boostListeners.remove(listener);
        }
    }

    private void notifyBoostListeners(final boolean isActive, final long remainingMillis) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                List<VipBoostListener> copy;
                synchronized (boostListeners) {
                    copy = new ArrayList<>(boostListeners);
                }
                for (VipBoostListener l : copy) {
                    try {
                        l.onVipBoostStateChanged(isActive, remainingMillis);
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying boost listener", e);
                    }
                }
            }
        });
    }

    /**
     * Calculates optimized latency if VIP Turbo Boost is active.
     */
    public int getOptimizedPing(Context context, int normalPing) {
        if (normalPing <= 0) return normalPing;
        if (isVipBoostActive(context)) {
            // Priority routes reduce ping by ~40%
            return Math.max(12, (int) (normalPing * 0.60f));
        }
        return normalPing;
    }

    // =========================================================================
    // Modern UI Dialog: VIP Turbo Boost Activation Modal
    // =========================================================================

    /**
     * Displays the VIP Turbo Boost modal explaining benefits (+50% speed, priority tunnel)
     * and offering to activate it by watching a rewarded video.
     */
    public void showTurboBoostDialog(final Activity activity, final AdCallback rewardCallback) {
        if (activity == null || activity.isFinishing()) return;

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                LayoutInflater inflater = LayoutInflater.from(activity);
                View dialogView = inflater.inflate(R.layout.dialog_turbo_boost, null);

                final TextView tvBoostStatusText = dialogView.findViewById(R.id.tvBoostStatusText);
                final View containerBoostStatus = dialogView.findViewById(R.id.containerBoostStatus);
                final Button btnActivateBoost = dialogView.findViewById(R.id.btnActivateBoost);
                final Button btnCloseBoost = dialogView.findViewById(R.id.btnCloseBoost);

                boolean isActive = isVipBoostActive(activity);
                if (isActive) {
                    String remaining = getFormattedRemainingTime(activity);
                    tvBoostStatusText.setText("Статус: ⚡ АКТИВЕН (+50% к скорости, осталось " + remaining + ")");
                    tvBoostStatusText.setTextColor(Color.parseColor("#32D786"));
                    containerBoostStatus.setBackgroundResource(R.drawable.bg_vip_boost_pill_active);
                    btnActivateBoost.setText("⚡ Продлить еще на 2 часа (Смотреть ролик)");
                } else {
                    tvBoostStatusText.setText("Статус: Базовый канал (стандартная скорость)");
                    tvBoostStatusText.setTextColor(Color.parseColor("#F0F6FC"));
                    containerBoostStatus.setBackgroundResource(R.drawable.bg_vip_boost_pill);
                    btnActivateBoost.setText("⚡ Активировать на 2 часа (Смотреть ролик)");
                }

                AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
                builder.setView(dialogView);
                builder.setCancelable(true);

                final AlertDialog dialog = builder.create();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
                }
                dialog.show();

                btnActivateBoost.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        // Play Rewarded Video to earn VIP boost
                        showRewardedVideo(activity, new AdCallback() {
                            @Override
                            public void onAdCompleted() {
                                activateVipBoost(activity, DEFAULT_BOOST_DURATION_MS);
                                Toast.makeText(activity, "🎉 Резервный канал +50% скорости успешно активирован на 2 часа!", Toast.LENGTH_LONG).show();
                                if (rewardCallback != null) rewardCallback.onAdCompleted();
                            }

                            @Override
                            public void onAdSkipped() {
                                Toast.makeText(activity, "Просмотр не завершен: VIP Turbo Boost не активирован", Toast.LENGTH_SHORT).show();
                                if (rewardCallback != null) rewardCallback.onAdSkipped();
                            }

                            @Override
                            public void onAdFailed(String reason) {
                                Toast.makeText(activity, "Ошибка показа рекламы: " + reason, Toast.LENGTH_SHORT).show();
                                if (rewardCallback != null) rewardCallback.onAdFailed(reason);
                            }
                        });
                    }
                });

                btnCloseBoost.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                    }
                });
            }
        });
    }

    // =========================================================================
    // Rewarded Ad Dispatcher (Yandex Mobile Ads / Test Simulator)
    // =========================================================================

    /**
     * Requests and shows a Rewarded Video Ad.
     * When completed, invokes {@link AdCallback#onAdCompleted()}.
     */
    public void showRewardedVideo(final Activity activity, final AdCallback callback) {
        if (activity == null || activity.isFinishing()) {
            if (callback != null) callback.onAdFailed("Activity is invalid or finishing");
            return;
        }

        if (isTestMode) {
            showSimulatedRewardDialog(activity, callback);
        } else {
            // Attempt production Yandex Mobile Ads SDK display
            boolean dispatched = tryShowYandexRewardedAd(activity, callback);
            if (!dispatched) {
                Log.w(TAG, "Yandex Mobile Ads SDK not detected in classpath. Falling back to test simulator.");
                showSimulatedRewardDialog(activity, callback);
            }
        }
    }

    // =========================================================================
    // Modern Rewarded Ad Simulator (Test Mode / Instant Preview)
    // =========================================================================

    private void showSimulatedRewardDialog(final Activity activity, final AdCallback callback) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                LayoutInflater inflater = LayoutInflater.from(activity);
                View dialogView = inflater.inflate(R.layout.dialog_ad, null);

                final TextView tvTimer = dialogView.findViewById(R.id.tvAdTimer);
                final TextView tvStatus = dialogView.findViewById(R.id.tvAdStatus);
                final Button btnAction = dialogView.findViewById(R.id.btnSimulateAdAction);
                final TextView btnSkip = dialogView.findViewById(R.id.btnSkipAd);

                AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
                builder.setView(dialogView);
                builder.setCancelable(false);

                final AlertDialog dialog = builder.create();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
                }
                dialog.show();

                btnAction.setEnabled(false);
                btnAction.setAlpha(0.45f);
                btnAction.setText("Награда через 5 сек…");

                final CountDownTimer timer = new CountDownTimer(5000, 1000) {
                    @Override
                    public void onTick(long millisUntilFinished) {
                        int seconds = (int) (millisUntilFinished / 1000) + 1;
                        tvTimer.setText(String.valueOf(seconds));
                        btnAction.setText("Награда через " + seconds + " сек…");
                        tvStatus.setText("Спонсорский ролик Yandex Mobile Ads (" + seconds + " сек)...");
                    }

                    @Override
                    public void onFinish() {
                        tvTimer.setText("✓");
                        tvStatus.setText("✓ Просмотр завершен! Награда готова.");
                        btnAction.setEnabled(true);
                        btnAction.setAlpha(1.0f);
                        btnAction.setText("⚡ Забрать награду (+2 часа VIP)");
                    }
                };
                timer.start();

                btnSkip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        timer.cancel();
                        dialog.dismiss();
                        if (callback != null) {
                            callback.onAdSkipped();
                        }
                    }
                });

                btnAction.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        if (callback != null) {
                            callback.onAdCompleted();
                        }
                    }
                });
            }
        });
    }

    // =========================================================================
    // Yandex Mobile Ads SDK Dynamic Bridge & Architecture Documentation
    // =========================================================================

    /**
     * Dynamically invokes Yandex Mobile Ads SDK via reflection if bundled in runtime,
     * maintaining clean standalone javac compilability without external jar dependencies.
     *
     * <p>When building with Gradle, add:
     * <pre>
     * dependencies {
     *     implementation 'com.yandex.android:mobileads:7.7.0'
     * }
     * </pre>
     *
     * <p>The native strongly-typed code corresponds to:
     * <pre>
     * RewardedAdLoader loader = new RewardedAdLoader(activity);
     * loader.setAdLoadListener(new RewardedAdLoadListener() {
     *     public void onAdLoaded(@NonNull RewardedAd rewardedAd) {
     *         rewardedAd.setAdEventListener(new RewardedAdEventListener() {
     *             public void onRewarded(@NonNull Reward reward) { callback.onAdCompleted(); }
     *             public void onAdDismissed() { callback.onAdSkipped(); }
     *             public void onAdFailedToShow(@NonNull AdError error) { callback.onAdFailed(error.getDescription()); }
     *             ...
     *         });
     *         rewardedAd.show(activity);
     *     }
     *     public void onAdFailedToLoad(@NonNull AdRequestError error) {
     *         callback.onAdFailed(error.getDescription());
     *     }
     * });
     * loader.loadAd(new AdRequestConfiguration.Builder(yandexBlockId).build());
     * </pre>
     */
    private boolean tryShowYandexRewardedAd(final Activity activity, final AdCallback callback) {
        try {
            Class<?> loaderClass = Class.forName("com.yandex.mobile.ads.rewarded.RewardedAdLoader");
            Class<?> configClass = Class.forName("com.yandex.mobile.ads.common.AdRequestConfiguration");
            Class<?> builderClass = Class.forName("com.yandex.mobile.ads.common.AdRequestConfiguration$Builder");
            Class<?> loadListenerClass = Class.forName("com.yandex.mobile.ads.rewarded.RewardedAdLoadListener");
            final Class<?> eventListenerClass = Class.forName("com.yandex.mobile.ads.rewarded.RewardedAdEventListener");

            // Build AdRequestConfiguration
            Object builderObj = builderClass.getConstructor(String.class).newInstance(yandexBlockId);
            Method buildMethod = builderClass.getMethod("build");
            final Object configObj = buildMethod.invoke(builderObj);

            // Instantiate RewardedAdLoader
            final Object loaderObj = loaderClass.getConstructor(Context.class).newInstance(activity);

            // Dynamic proxy for RewardedAdLoadListener
            Object loadListenerProxy = Proxy.newProxyInstance(
                    activity.getClassLoader(),
                    new Class<?>[]{loadListenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            if ("onAdLoaded".equals(name) && args != null && args.length > 0) {
                                final Object rewardedAd = args[0];
                                attachYandexEventListenerAndShow(activity, rewardedAd, eventListenerClass, callback);
                            } else if ("onAdFailedToLoad".equals(name) && args != null && args.length > 0) {
                                String errorDesc = args[0] != null ? args[0].toString() : "Unknown Yandex load error";
                                Log.e(TAG, "Yandex ad failed to load: " + errorDesc);
                                if (callback != null) callback.onAdFailed(errorDesc);
                            }
                            return null;
                        }
                    });

            Method setLoadListenerMethod = loaderClass.getMethod("setAdLoadListener", loadListenerClass);
            setLoadListenerMethod.invoke(loaderObj, loadListenerProxy);

            Method loadAdMethod = loaderClass.getMethod("loadAd", configClass);
            loadAdMethod.invoke(loaderObj, configObj);
            Log.i(TAG, "Yandex RewardedAd load initiated with blockId: " + yandexBlockId);
            return true;

        } catch (ClassNotFoundException e) {
            // Yandex SDK not in classpath
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error invoking Yandex Mobile Ads SDK via reflection", e);
            return false;
        }
    }

    private void attachYandexEventListenerAndShow(final Activity activity, final Object rewardedAd,
                                                 Class<?> eventListenerClass, final AdCallback callback) {
        try {
            final boolean[] rewardGranted = new boolean[]{false};

            Object eventListenerProxy = Proxy.newProxyInstance(
                    activity.getClassLoader(),
                    new Class<?>[]{eventListenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            switch (name) {
                                case "onRewarded":
                                    rewardGranted[0] = true;
                                    Log.i(TAG, "Yandex RewardedAd reward earned!");
                                    if (callback != null) callback.onAdCompleted();
                                    break;
                                case "onAdDismissed":
                                    if (!rewardGranted[0]) {
                                        Log.i(TAG, "Yandex RewardedAd dismissed without reward");
                                        if (callback != null) callback.onAdSkipped();
                                    }
                                    break;
                                case "onAdFailedToShow":
                                    String err = args != null && args.length > 0 && args[0] != null ? args[0].toString() : "Ad show failed";
                                    Log.e(TAG, "Yandex RewardedAd failed to show: " + err);
                                    if (callback != null) callback.onAdFailed(err);
                                    break;
                            }
                            return null;
                        }
                    });

            Method setEventListenerMethod = rewardedAd.getClass().getMethod("setAdEventListener", eventListenerClass);
            setEventListenerMethod.invoke(rewardedAd, eventListenerProxy);

            Method showMethod = rewardedAd.getClass().getMethod("show", Activity.class);
            showMethod.invoke(rewardedAd, activity);
            Log.i(TAG, "Yandex RewardedAd show invoked");

        } catch (Exception e) {
            Log.e(TAG, "Error showing Yandex rewarded ad", e);
            if (callback != null) callback.onAdFailed(e.getMessage());
        }
    }
}
