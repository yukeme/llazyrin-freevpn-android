package ru.llazyrin.goblinvpn.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.net.AppUpdateManager;
import ru.llazyrin.goblinvpn.net.NotificationCenter;
import ru.llazyrin.goblinvpn.service.GoblinVpnService;

/**
 * NotificationInboxDialog — Модальное окно "Журнал уведомлений" в стиле Material 3.
 *
 * Содержит:
 * 1. Блок "🚀 Обновление системы":
 *    - текущая версия приложения (например, v2.5.0)
 *    - статус ("Установлена последняя версия" / "Доступно обновление")
 *    - список последних изменений (Changelog)
 *    - кнопка проверки или немедленной загрузки обновления
 * 2. Блок "📢 Новости, акции и промокоды":
 *    - серверный текст объявления, скидок и промокодов
 *    - кнопка копирования промокода в буфер обмена
 *    - кнопка перехода в официальный Telegram-канал
 * 3. Контакт службы поддержки @llazyrin
 */
public class NotificationInboxDialog {

    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        // Помечаем все уведомления как прочитанные
        NotificationCenter.markAllAsRead(activity);

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_notification_inbox);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.65f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        // Header Views
        final View dotInboxPulse = dialog.findViewById(R.id.dotInboxPulse);
        final FrameLayout btnClose = dialog.findViewById(R.id.btnCloseInboxDialog);

        if (dotInboxPulse != null) {
            AlphaAnimation pulse = new AlphaAnimation(0.35f, 1.0f);
            pulse.setDuration(900);
            pulse.setRepeatMode(Animation.REVERSE);
            pulse.setRepeatCount(Animation.INFINITE);
            dotInboxPulse.startAnimation(pulse);
        }

        // Блок 1: Обновление системы
        final TextView tvInboxCurrentVersion = dialog.findViewById(R.id.tvInboxCurrentVersion);
        final View pillUpdateStatus = dialog.findViewById(R.id.pillUpdateStatus);
        final TextView tvUpdateStatusIcon = dialog.findViewById(R.id.tvUpdateStatusIcon);
        final TextView tvUpdateStatusText = dialog.findViewById(R.id.tvUpdateStatusText);
        final TextView tvInboxChangelog = dialog.findViewById(R.id.tvInboxChangelog);
        final TextView btnInboxUpdateAction = dialog.findViewById(R.id.btnInboxUpdateAction);

        final String currentVersionName = NotificationCenter.getAppVersionName(activity);
        if (tvInboxCurrentVersion != null) {
            tvInboxCurrentVersion.setText("v" + currentVersionName);
        }
        if (tvInboxChangelog != null) {
            tvInboxChangelog.setText(NotificationCenter.getSystemChangelog(activity));
        }

        final boolean hasUpdate = NotificationCenter.isUpdateAvailable(activity);
        final AppUpdateManager.UpdateInfo updateInfo = NotificationCenter.getCachedUpdateInfo(activity);

        if (hasUpdate && updateInfo != null && updateInfo.hasUpdate && !updateInfo.apkUrl.isEmpty()) {
            if (pillUpdateStatus != null) {
                pillUpdateStatus.setBackgroundResource(R.drawable.bg_m3_badge_amber);
            }
            if (tvUpdateStatusIcon != null) {
                tvUpdateStatusIcon.setText("⚡");
                tvUpdateStatusIcon.setTextColor(0xFFF59E0B);
            }
            if (tvUpdateStatusText != null) {
                tvUpdateStatusText.setText("Доступно обновление: v" + updateInfo.versionName);
                tvUpdateStatusText.setTextColor(0xFFF59E0B);
            }
            if (btnInboxUpdateAction != null) {
                btnInboxUpdateAction.setText("⚡ Загрузить обновление (v" + updateInfo.versionName + ")");
                btnInboxUpdateAction.setBackgroundResource(R.drawable.bg_m3_apply_btn);
                btnInboxUpdateAction.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        HapticFeedbackHelper.performButtonClick(v);
                        dialog.dismiss();
                        AppUpdateManager.showUpdateDialog(activity, updateInfo, currentVersionName);
                    }
                });
            }
        } else {
            if (pillUpdateStatus != null) {
                pillUpdateStatus.setBackgroundResource(R.drawable.bg_m3_badge_emerald);
            }
            if (tvUpdateStatusIcon != null) {
                tvUpdateStatusIcon.setText("✓");
                tvUpdateStatusIcon.setTextColor(0xFF34D399);
            }
            if (tvUpdateStatusText != null) {
                tvUpdateStatusText.setText("Установлена последняя версия");
                tvUpdateStatusText.setTextColor(0xFF34D399);
            }
            if (btnInboxUpdateAction != null) {
                btnInboxUpdateAction.setText("Проверить обновления на сервере");
                btnInboxUpdateAction.setBackgroundResource(R.drawable.bg_button_secondary);
                btnInboxUpdateAction.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        HapticFeedbackHelper.performButtonClick(v);
                        dialog.dismiss();
                        AppUpdateManager.checkForUpdates(activity, true);
                    }
                });
            }
        }

        // Блок 2: Новости, акции и промокоды
        final TextView tvInboxAnnouncementText = dialog.findViewById(R.id.tvInboxAnnouncementText);
        final TextView btnInboxCopyPromo = dialog.findViewById(R.id.btnInboxCopyPromo);
        final TextView btnInboxOpenChannel = dialog.findViewById(R.id.btnInboxOpenChannel);
        final LinearLayout rowInboxSupport = dialog.findViewById(R.id.rowInboxSupport);
        final TextView btnDismissInbox = dialog.findViewById(R.id.btnDismissInbox);

        final String announcementText = NotificationCenter.getLatestAnnouncement(activity);
        if (tvInboxAnnouncementText != null) {
            tvInboxAnnouncementText.setText(announcementText);
        }

        if (btnInboxCopyPromo != null) {
            btnInboxCopyPromo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    copyToClipboard(activity, announcementText);
                }
            });
        }

        if (btnInboxOpenChannel != null) {
            btnInboxOpenChannel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    openUrl(activity, "https://t.me/llazyrin");
                }
            });
        }

        if (rowInboxSupport != null) {
            rowInboxSupport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    openUrl(activity, "https://t.me/llazyrin");
                }
            });
        }

        View.OnClickListener dismissListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                dialog.dismiss();
            }
        };

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                Intent restore = new Intent(activity, GoblinVpnService.class);
                restore.setAction(GoblinVpnService.ACTION_RESTORE_NOTIFICATION);
                try {
                    activity.startService(restore);
                } catch (Exception ignored) {}
            }
        });

        if (btnClose != null) {
            btnClose.setOnClickListener(dismissListener);
        }
        if (btnDismissInbox != null) {
            btnDismissInbox.setOnClickListener(dismissListener);
        }

        try {
            dialog.show();
        } catch (Exception ignored) {}
    }

    private static void copyToClipboard(Context context, String fullText) {
        if (context == null || fullText == null) return;
        try {
            // Пытаемся извлечь промокод, если есть ключевое слово "промокод <CODE>"
            String copyTarget = fullText;
            Pattern p = Pattern.compile("(?i)(?:промокод[\\s:]+)([A-Z0-9_-]+)");
            Matcher m = p.matcher(fullText);
            if (m.find()) {
                String promo = m.group(1);
                if (promo != null && !promo.isEmpty()) {
                    copyTarget = promo;
                }
            }

            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("llazyrinVPN Promo", copyTarget);
                cm.setPrimaryClip(clip);
                Toast.makeText(context, "Скопировано: " + copyTarget, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "Не удалось скопировать в буфер", Toast.LENGTH_SHORT).show();
        }
    }

    private static void openUrl(Context context, String url) {
        if (context == null || url == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "Не удалось открыть ссылку: " + url, Toast.LENGTH_SHORT).show();
        }
    }
}
