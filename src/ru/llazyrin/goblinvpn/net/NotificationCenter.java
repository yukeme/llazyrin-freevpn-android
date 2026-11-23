package ru.llazyrin.goblinvpn.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Locale;

import ru.llazyrin.goblinvpn.MainActivity;
import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.service.AppLogger;

/**
 * NotificationCenter — Централизованная система управления журналом уведомлений и системным треем.
 *
 * Функции:
 * 1. Получение серверных объявлений (heartbeat / telemetry) и системных обновлений.
 * 2. Дедупликация через SharedPreferences (last_notified_hash, last_notified_version).
 * 3. Отправка push/tray уведомлений в канал "llazyrin_announcements" ("llazyrinVPN Новости").
 * 4. Уведомление UI (MainActivity) для запуска пульсирующего зеленого индикатора badgeNotificationPulse.
 * 5. Хранение истории и статуса прочтения для диалога "Журнал уведомлений".
 */
public class NotificationCenter {

    private static final String TAG = "NotificationCenter";
    public static final String PREFS_NAME = "llazyrin_notification_box";

    public static final String CHANNEL_ID = "llazyrin_announcements";
    public static final String CHANNEL_NAME = "llazyrinVPN Новости";

    public static final int NOTIF_ID_ANNOUNCEMENT = 2001;
    public static final int NOTIF_ID_UPDATE = 2002;

    public static final String KEY_LAST_NOTIFIED_HASH = "last_notified_hash";
    public static final String KEY_LAST_READ_HASH = "last_read_hash";
    public static final String KEY_LATEST_ANNOUNCEMENT = "latest_announcement_text";
    public static final String KEY_LATEST_ANNOUNCEMENT_TIME = "latest_announcement_time";

    public static final String KEY_LAST_NOTIFIED_VERSION = "last_notified_version";
    public static final String KEY_LAST_READ_VERSION = "last_read_version";
    public static final String KEY_CACHED_VERSION_NAME = "cached_version_name";
    public static final String KEY_CACHED_RELEASE_NOTES = "cached_release_notes";
    public static final String KEY_CACHED_APK_URL = "cached_apk_url";
    public static final String KEY_CACHED_FILE_SIZE = "cached_file_size";
    public static final String KEY_CACHED_HAS_UPDATE = "cached_has_update";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile NotificationListener activeListener = null;

    public interface NotificationListener {
        void onNewNotificationArrived();
    }

    public static void setListener(NotificationListener listener) {
        activeListener = listener;
    }

    public static void removeListener() {
        activeListener = null;
    }

    /**
     * Создание канала уведомлений для Android 8.0+ (Oreo).
     */
    public static void ensureChannelCreated(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(
                            CHANNEL_ID,
                            CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_DEFAULT
                    );
                    channel.setDescription("Уведомления о новостях, акциях, промокодах и обновлениях llazyrinVPN");
                    channel.enableLights(true);
                    channel.setLightColor(0xFF10B981); // Emerald pulse color
                    channel.enableVibration(true);
                    channel.setShowBadge(true);
                    nm.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to create announcement notification channel: " + e.getMessage());
            }
        }
    }

    /**
     * Обработка поступления нового объявления (из heartbeat или fetchStats).
     */
    public static void handleNewAnnouncement(Context context, String rawAnnouncement) {
        if (context == null || rawAnnouncement == null) return;
        final String announcement = rawAnnouncement.trim();
        if (announcement.isEmpty()) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String currentHash = Integer.toHexString(announcement.hashCode());
        String lastNotifiedHash = prefs.getString(KEY_LAST_NOTIFIED_HASH, "");

        // Проверяем, не показывалось ли оно уже
        boolean isNew = !currentHash.equals(lastNotifiedHash);

        // Всегда сохраняем последний текст для офлайн отображения в журнале уведомлений
        prefs.edit()
                .putString(KEY_LATEST_ANNOUNCEMENT, announcement)
                .putLong(KEY_LATEST_ANNOUNCEMENT_TIME, System.currentTimeMillis())
                .apply();

        if (isNew) {
            AppLogger.i(TAG, "Получено новое серверное объявление: " + announcement);
            prefs.edit().putString(KEY_LAST_NOTIFIED_HASH, currentHash).apply();

            // Отправка в системный трей
            sendTrayNotification(
                    context,
                    NOTIF_ID_ANNOUNCEMENT,
                    "llazyrinVPN",
                    announcement,
                    announcement
            );

            // Уведомление UI о новом событии
            notifyUiListener();
        }
    }

    /**
     * Обработка проверки обновлений системы.
     */
    public static void handleUpdateCheck(Context context, AppUpdateManager.UpdateInfo info) {
        if (context == null || info == null) return;

        final int currentVersionCode = getAppVersionCode(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (info.hasUpdate && info.versionCode > currentVersionCode && !info.apkUrl.isEmpty()) {
            // Кэшируем информацию об обновлении
            prefs.edit()
                    .putBoolean(KEY_CACHED_HAS_UPDATE, true)
                    .putInt(KEY_LAST_NOTIFIED_VERSION, info.versionCode)
                    .putString(KEY_CACHED_VERSION_NAME, info.versionName)
                    .putString(KEY_CACHED_RELEASE_NOTES, info.releaseNotes)
                    .putString(KEY_CACHED_APK_URL, info.apkUrl)
                    .putLong(KEY_CACHED_FILE_SIZE, info.fileSize)
                    .apply();

            int lastNotifiedVersion = prefs.getInt("tray_notified_version", 0);
            if (info.versionCode > lastNotifiedVersion) {
                prefs.edit().putInt("tray_notified_version", info.versionCode).apply();

                String summary = "Доступно обновление v" + info.versionName + "!";
                String detail = summary + (info.releaseNotes.isEmpty() ? "" : "\n" + info.releaseNotes);

                AppLogger.i(TAG, "Новая версия в трей: v" + info.versionName);

                sendTrayNotification(
                        context,
                        NOTIF_ID_UPDATE,
                        "llazyrinVPN",
                        summary,
                        detail
                );

                notifyUiListener();
            }
        } else {
            // Нет обновлений или установлена самая свежая версия
            prefs.edit()
                    .putBoolean(KEY_CACHED_HAS_UPDATE, false)
                    .apply();
        }
    }

    /**
     * Отправка системного push/tray уведомления через NotificationManager.
     */
    public static void sendTrayNotification(Context context, int notificationId, String title, String summary, String fullBody) {
        if (context == null) return;
        ensureChannelCreated(context);

        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent clickIntent = new Intent(context, MainActivity.class);
            clickIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            clickIntent.putExtra("open_inbox", true);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(context, notificationId, clickIntent, flags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }

            builder.setContentTitle(title)
                    .setContentText(summary)
                    .setSmallIcon(R.drawable.ic_notification_journal)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                builder.setStyle(new Notification.BigTextStyle().bigText(fullBody));
            }

            nm.notify(notificationId, builder.build());
            AppLogger.d(TAG, "Tray notification posted successfully [id=" + notificationId + "]");
        } catch (SecurityException se) {
            Log.w(TAG, "Notification permission not granted: " + se.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Error posting tray notification: " + e.getMessage());
        }
    }

    /**
     * Отправка пользовательского промо-объявления или сообщения.
     */
    public static void postAnnouncement(Context context, String announcementText) {
        handleNewAnnouncement(context, announcementText);
    }

    /**
     * Проверка наличия непрочитанных уведомлений (новое объявление или обновление).
     */
    public static boolean hasUnreadNotifications(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String lastNotifiedHash = prefs.getString(KEY_LAST_NOTIFIED_HASH, "");
        String lastReadHash = prefs.getString(KEY_LAST_READ_HASH, "");
        boolean hasUnreadAnnouncement = !lastNotifiedHash.isEmpty() && !lastNotifiedHash.equals(lastReadHash);

        int lastNotifiedVersion = prefs.getInt(KEY_LAST_NOTIFIED_VERSION, 0);
        int lastReadVersion = prefs.getInt(KEY_LAST_READ_VERSION, 0);
        boolean hasUnreadUpdate = lastNotifiedVersion > 0 && lastNotifiedVersion > lastReadVersion;

        return hasUnreadAnnouncement || hasUnreadUpdate;
    }

    /**
     * Отметка всех уведомлений как прочитанных (пользователь открыл диалог журнала уведомлений).
     */
    public static void markAllAsRead(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String lastNotifiedHash = prefs.getString(KEY_LAST_NOTIFIED_HASH, "");
        int lastNotifiedVersion = prefs.getInt(KEY_LAST_NOTIFIED_VERSION, 0);

        prefs.edit()
                .putString(KEY_LAST_READ_HASH, lastNotifiedHash)
                .putInt(KEY_LAST_READ_VERSION, lastNotifiedVersion)
                .apply();

        AppLogger.d(TAG, "Все уведомления помечены как прочитанные.");
    }

    /**
     * Возвращает текст последнего объявления или приветственное сообщение по умолчанию.
     */
    public static String getLatestAnnouncement(Context context) {
        if (context == null) return "";
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String text = prefs.getString(KEY_LATEST_ANNOUNCEMENT, "");
        if (text.isEmpty()) {
            return "🎉 Добро пожаловать в llazyrinVPN!\nИспользуйте промокод LLAZYRIN для получения бонусов и следите за обновлениями в нашем канале.";
        }
        return text;
    }

    /**
     * Проверяет, доступно ли новое обновление системы.
     */
    public static boolean isUpdateAvailable(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_CACHED_HAS_UPDATE, false);
    }

    /**
     * Возвращает закэшированную информацию о последнем доступном обновлении.
     */
    public static AppUpdateManager.UpdateInfo getCachedUpdateInfo(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean hasUpdate = prefs.getBoolean(KEY_CACHED_HAS_UPDATE, false);
        int versionCode = prefs.getInt(KEY_LAST_NOTIFIED_VERSION, 0);
        String versionName = prefs.getString(KEY_CACHED_VERSION_NAME, "");
        String releaseNotes = prefs.getString(KEY_CACHED_RELEASE_NOTES, "");
        String apkUrl = prefs.getString(KEY_CACHED_APK_URL, "");
        long fileSize = prefs.getLong(KEY_CACHED_FILE_SIZE, 0L);

        return new AppUpdateManager.UpdateInfo(hasUpdate, versionCode, versionName, apkUrl, fileSize, "", releaseNotes);
    }

    /**
     * Описание изменений последней актуальной версии (Changelog).
     */
    public static String getSystemChangelog(Context context) {
        return "• Конфиденциальность: тумблер согласия на сбор анонимных логов\n" +
               "• Новый матовый дизайн: монотонные кнопки и капсульный хотбар\n" +
               "• Совместимость: устранена ошибка установщика Realme UI / ColorOS\n" +
               "• Высокоскоростной нативный VLESS Reality движок";
    }

    public static int getAppVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (Exception e) {
            return 13;
        }
    }

    public static String getAppVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "2.5.0";
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " Б";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f КБ", bytes / 1024.0);
        } else if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f МБ", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.getDefault(), "%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "0 КБ/с";
        } else if (bytesPerSec < 1024 * 1024) {
            double kb = bytesPerSec / 1024.0;
            if (kb < 10.0) {
                return String.format(Locale.getDefault(), "%.1f КБ/с", kb);
            } else {
                return String.format(Locale.getDefault(), "%.0f КБ/с", kb);
            }
        } else if (bytesPerSec < 1024L * 1024L * 1024L) {
            double mb = bytesPerSec / (1024.0 * 1024.0);
            return String.format(Locale.getDefault(), "%.1f МБ/с", mb);
        } else {
            double gb = bytesPerSec / (1024.0 * 1024.0 * 1024.0);
            return String.format(Locale.getDefault(), "%.2f ГБ/с", gb);
        }
    }

    public static String formatTrafficRx(long bytes) {
        return "Получено: " + formatBytes(bytes);
    }

    public static String formatTrafficTx(long bytes) {
        return "Передано: " + formatBytes(bytes);
    }

    public static String formatTrafficRx(double mb) {
        if (mb >= 1024.0) {
            return String.format(Locale.getDefault(), "Получено: %.2f ГБ", mb / 1024.0);
        } else {
            return String.format(Locale.getDefault(), "Получено: %.2f МБ", mb);
        }
    }

    public static String formatTrafficTx(double mb) {
        if (mb >= 1024.0) {
            return String.format(Locale.getDefault(), "Передано: %.2f ГБ", mb / 1024.0);
        } else {
            return String.format(Locale.getDefault(), "Передано: %.2f МБ", mb);
        }
    }

    private static void notifyUiListener() {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                NotificationListener l = activeListener;
                if (l != null) {
                    l.onNewNotificationArrived();
                }
            }
        });
    }
}
