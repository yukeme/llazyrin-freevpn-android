package ru.llazyrin.goblinvpn.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.net.AppTelemetryClient;
import ru.llazyrin.goblinvpn.net.AppTelemetryClient.AppPublicStats;

/**
 * CommunityStatsDialog — Модальное окно статистики сообщества Goblin VPN.
 *
 * Отображает:
 * 1. Количество активных пользователей в сети
 * 2. Количество активных VPN-соединений
 * 3. Общее количество зарегистрированных клиентов
 * 4. Количество активных серверов и статус кластера («Все узлы работают штатно»)
 * 5. Важное объявление сети (announcement) при наличии текста с сервера
 * 6. Возможность ручного обновления данных в реальном времени
 */
public class CommunityStatsDialog {

    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_community_stats);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.65f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        final View dotDialogLive = dialog.findViewById(R.id.dotDialogLive);
        final FrameLayout btnClose = dialog.findViewById(R.id.btnCloseCommunityStats);
        final LinearLayout containerAnnouncement = dialog.findViewById(R.id.containerAnnouncement);
        final TextView tvAnnouncementText = dialog.findViewById(R.id.tvAnnouncementText);

        final TextView tvServerStatusIndicator = dialog.findViewById(R.id.tvServerStatusIndicator);
        final TextView tvServerStatusDetails = dialog.findViewById(R.id.tvServerStatusDetails);

        final TextView tvStatOnlineUsers = dialog.findViewById(R.id.tvStatOnlineUsers);
        final TextView tvStatConnectedVpn = dialog.findViewById(R.id.tvStatConnectedVpn);
        final TextView tvStatTotalUsers = dialog.findViewById(R.id.tvStatTotalUsers);
        final TextView tvStatActiveServers = dialog.findViewById(R.id.tvStatActiveServers);

        final Button btnRefresh = dialog.findViewById(R.id.btnRefreshCommunityStats);
        final Button btnDismiss = dialog.findViewById(R.id.btnDismissCommunityStats);

        // Start subtle live pulsing on dot
        if (dotDialogLive != null) {
            AlphaAnimation pulse = new AlphaAnimation(0.4f, 1.0f);
            pulse.setDuration(900);
            pulse.setRepeatMode(Animation.REVERSE);
            pulse.setRepeatCount(Animation.INFINITE);
            dotDialogLive.startAnimation(pulse);
        }

        // Apply cached or default stats
        AppPublicStats initial = AppTelemetryClient.getLastKnownStats();
        if (initial != null) {
            populateStats(initial, containerAnnouncement, tvAnnouncementText,
                    tvServerStatusIndicator, tvServerStatusDetails,
                    tvStatOnlineUsers, tvStatConnectedVpn, tvStatTotalUsers, tvStatActiveServers);
        } else {
            tvStatOnlineUsers.setText("...");
            tvStatConnectedVpn.setText("...");
            tvStatTotalUsers.setText("...");
            tvStatActiveServers.setText("...");
        }

        // Helper to load fresh stats
        final Runnable fetchTask = new Runnable() {
            @Override
            public void run() {
                if (btnRefresh != null) {
                    btnRefresh.setEnabled(false);
                    btnRefresh.setText("Обновление...");
                }
                AppTelemetryClient.fetchStatsAsync(new AppTelemetryClient.StatsCallback() {
                    @Override
                    public void onStatsLoaded(AppPublicStats stats) {
                        if (activity.isFinishing() || !dialog.isShowing()) return;
                        if (btnRefresh != null) {
                            btnRefresh.setEnabled(true);
                            btnRefresh.setText("🔄 Обновить");
                        }
                        populateStats(stats, containerAnnouncement, tvAnnouncementText,
                                tvServerStatusIndicator, tvServerStatusDetails,
                                tvStatOnlineUsers, tvStatConnectedVpn, tvStatTotalUsers, tvStatActiveServers);
                    }

                    @Override
                    public void onError(Exception error) {
                        if (activity.isFinishing() || !dialog.isShowing()) return;
                        if (btnRefresh != null) {
                            btnRefresh.setEnabled(true);
                            btnRefresh.setText("🔄 Повторить");
                        }
                        Toast.makeText(activity, "Не удалось обновить статистику сети", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };

        // Automatic fetch on open
        fetchTask.run();

        // Listeners
        if (btnClose != null) {
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    dialog.dismiss();
                }
            });
        }

        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    dialog.dismiss();
                }
            });
        }

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    fetchTask.run();
                }
            });
        }

        dialog.show();
    }

    private static void populateStats(AppPublicStats stats,
                                      LinearLayout containerAnnouncement,
                                      TextView tvAnnouncementText,
                                      TextView tvServerStatusIndicator,
                                      TextView tvServerStatusDetails,
                                      TextView tvStatOnlineUsers,
                                      TextView tvStatConnectedVpn,
                                      TextView tvStatTotalUsers,
                                      TextView tvStatActiveServers) {
        if (stats == null) return;

        tvStatOnlineUsers.setText(String.format(Locale.getDefault(), "%d", stats.onlineUsers));
        tvStatConnectedVpn.setText(String.format(Locale.getDefault(), "%d", stats.connectedVpnUsers));
        tvStatTotalUsers.setText(String.format(Locale.getDefault(), "%d", stats.totalUsers));
        tvStatActiveServers.setText(String.format(Locale.getDefault(), "%d", stats.activeServers));

        if (tvServerStatusIndicator != null) {
            tvServerStatusIndicator.setText("🟢 Все узлы работают штатно");
        }
        if (tvServerStatusDetails != null) {
            tvServerStatusDetails.setText(String.format(Locale.getDefault(),
                    "%d активных серверов VLESS Reality в строю", stats.activeServers));
        }

        if (containerAnnouncement != null && tvAnnouncementText != null) {
            if (stats.hasAnnouncement()) {
                containerAnnouncement.setVisibility(View.VISIBLE);
                tvAnnouncementText.setText(stats.announcement);
            } else {
                containerAnnouncement.setVisibility(View.GONE);
            }
        }
    }
}
