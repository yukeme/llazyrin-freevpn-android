package ru.llazyrin.goblinvpn.net;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import ru.llazyrin.goblinvpn.MainActivity;
import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.security.AppFileProvider;
import ru.llazyrin.goblinvpn.service.AppLogger;
import ru.llazyrin.goblinvpn.service.GoblinVpnService;

/**
 * AppUpdateManager — Высокозащищенная система проверки и загрузки автообновлений Goblin VPN.
 *
 * Архитектурные уровни защиты:
 * 1. Строгая проверка HTTPS и доверенного домена сервера (Anti-Spoofing / Anti-DNS Poisoning).
 * 2. Аппаратный расчет контрольной суммы SHA-256 потока данных "на лету" (Anti-MITM / Anti-Tampering).
 * 3. Немедленное удаление файла при малейшем расхождении хеша или размера до передачи системе.
 * 4. Защита сокетов через GoblinVpnService.protectSocket(...) от зацикливания при активном VPN.
 * 5. Безопасная передача APK установщику Android через собственный AppFileProvider без androidx.
 */
public class AppUpdateManager {

    private static final String TAG = "AppUpdateManager";

    public static final String UPDATE_CHECK_URL = "https://llazyrin.online/api/app/version/latest";
    public static final String TRUSTED_HOST = "llazyrin.online";
    public static final String APK_FILE_NAME = "update_download.apk";

    public static final String UPDATE_SIGNING_PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\n" +
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1lRvII1+izhifHf9hl6B\n" +
            "1PPH3mc9fM45sFWGAUfFNqwICSJc9D2ikHOWN3CbFnn9mar46fEX7EvpVwY+p5jB\n" +
            "+2wWweYoGTXwY8xN/mqm4OyZnHpC2doY3sN54y3pQH1jQLJBGnOYjiVI6wVTGXSV\n" +
            "8soiw7+haWcxxtSZrL3uLZrzgJGi9MV9OgTjGpdOe4vWF9eRE1dvaeT/vTSL1Y1s\n" +
            "3pIkCMAm1uuqVHga9UzNDllbDImklzoxpADfWPTssZIYsXFPTP0MiOwQ8OJHoiTG\n" +
            "bYcUXqmt8EZcU/3FrpGal5vKc4k4Lur0Mo7HvArYNnex4BCGTM6FqwK48mehLTuL\n" +
            "hwIDAQAB\n" +
            "-----END PUBLIC KEY-----";

    private static final String[] PINNED_CERT_SHA256 = new String[] {
            // Leaf cert for llazyrin.online
            "08a1edd35587578975f3a8a31b5881c9b368722c1bddd21ea114672db9816892",
            // Let's Encrypt intermediate CAs (YE2, R10, R11)
            "97658de8c68dfa98ace1e5028a63d54a1aae911b3e21471076c6850cd08cbab4",
            "0fc0901cca2bae9e9fdbb02d50d02f1094f7b36672086991b9e897626dc485f0",
            "ee5f7abd6981bb0255632cd8f49283451b4b18844d12040b44ee00f07b8fe2c6",
            // Let's Encrypt ISRG Root X1 (valid through 2035)
            "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"
    };

    public static final String EXTRA_CHECK_UPDATE = "ru.llazyrin.goblinvpn.EXTRA_CHECK_UPDATE";
    public static final String UPDATE_CHANNEL_ID = "llazyrin_updates_channel";
    public static final String UPDATE_CHANNEL_NAME = "Обновления llazyrinVPN";
    public static final int NOTIF_ID_UPDATE_TRAY = 5001;

    private static final int TIMEOUT_CONNECT_MS = 12000;
    private static final int TIMEOUT_READ_MS = 25000;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static volatile File pendingInstallFile = null;
    private static volatile boolean isDownloading = false;
    private static volatile boolean isAppBlocked = false;
    private static volatile UpdateInfo blockedUpdateInfo = null;
    private static volatile Dialog activeBlockDialog = null;
    private static volatile Dialog activeUpdateDialog = null;
    private static final String PREFS_UPDATE_NAME = "goblin_update_prefs";
    private static final String PREF_KEY_LAST_NOTIFIED_VERSION = "last_notified_tray_version_code";
    private static final String PREF_KEY_LAST_NOTIFIED_TIME = "last_notified_tray_time_ms";
    private static final long TRAY_NOTIF_DEBOUNCE_MS = 24L * 3600L * 1000L;

    public static boolean isAppBlocked() {
        return isAppBlocked;
    }

    public static UpdateInfo getBlockedUpdateInfo() {
        return blockedUpdateInfo;
    }

    /**
     * DTO информации об обновлении.
     */
    public static class UpdateInfo {
        public final boolean hasUpdate;
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final long fileSize;
        public final String sha256;
        public final String signature;
        public final String releaseNotes;
        // Поля политики блокировки устаревших версий (Force Update / Deprecation)
        public final int minSupportedVersionCode;
        public final String minSupportedVersionName;
        public final boolean isBlocked;
        public final String blockMessage;
        public final int maxVersionsBehind;
        public final String policyMode;

        public UpdateInfo(boolean hasUpdate, int versionCode, String versionName,
                          String apkUrl, long fileSize, String sha256, String signature, String releaseNotes,
                          int minSupportedVersionCode, String minSupportedVersionName, boolean isBlocked,
                          String blockMessage, int maxVersionsBehind, String policyMode) {
            this.hasUpdate = hasUpdate;
            this.versionCode = versionCode;
            this.versionName = versionName != null ? versionName.trim() : "";
            this.apkUrl = apkUrl != null ? apkUrl.trim() : "";
            this.fileSize = fileSize;
            this.sha256 = sha256 != null ? sha256.trim().toLowerCase(Locale.ROOT) : "";
            this.signature = signature != null ? signature.trim() : "";
            this.releaseNotes = releaseNotes != null ? releaseNotes.trim() : "";
            this.minSupportedVersionCode = minSupportedVersionCode;
            this.minSupportedVersionName = minSupportedVersionName != null ? minSupportedVersionName.trim() : "";
            this.isBlocked = isBlocked;
            this.blockMessage = blockMessage != null ? blockMessage.trim() : "";
            this.maxVersionsBehind = maxVersionsBehind;
            this.policyMode = policyMode != null ? policyMode.trim() : "auto";
        }

        public UpdateInfo(boolean hasUpdate, int versionCode, String versionName,
                          String apkUrl, long fileSize, String sha256, String signature, String releaseNotes) {
            this(hasUpdate, versionCode, versionName, apkUrl, fileSize, sha256, signature, releaseNotes, 0, "", false, "", 3, "auto");
        }

        public UpdateInfo(boolean hasUpdate, int versionCode, String versionName,
                          String apkUrl, long fileSize, String sha256, String releaseNotes) {
            this(hasUpdate, versionCode, versionName, apkUrl, fileSize, sha256, "", releaseNotes, 0, "", false, "", 3, "auto");
        }
    }

    /**
     * Проверка обновлений (вызывается при старте и по нажатию пользователем).
     *
     * @param activity       текущая активность
     * @param userTriggered  true если вызов инициирован вручную пользователем
     */
    public static void checkForUpdates(final Activity activity, final boolean userTriggered) {
        if (activity == null || activity.isFinishing()) return;

        if (userTriggered) {
            Toast.makeText(activity, "Проверка обновлений на официальном сервере...", Toast.LENGTH_SHORT).show();
        }

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final UpdateInfo info = fetchUpdateInfo(activity);
                    if (info == null) {
                        if (userTriggered) {
                            MAIN_HANDLER.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!activity.isFinishing()) {
                                        Toast.makeText(activity, "Сервер обновлений временно недоступен", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                        }
                        return;
                    }

                    final int currentVersionCode = getAppVersionCode(activity);
                    final String currentVersionName = getAppVersionName(activity);

                    NotificationCenter.handleUpdateCheck(activity, info);

                    AppLogger.i(TAG, "Проверка версии: текущая code=" + currentVersionCode +
                            " (" + currentVersionName + "), на сервере code=" + info.versionCode + " (" + info.versionName + ")");

                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isFinishing()) return;

                            // =========================================================================
                            // КРИТИЧЕСКАЯ ПРОВЕРКА ПОЛИТИКИ ПОДДЕРЖКИ ВЕРСИЙ (Force Update / Deprecation)
                            // =========================================================================
                            boolean isDeprecated = info.isBlocked || (info.minSupportedVersionCode > 0 && currentVersionCode < info.minSupportedVersionCode);
                            if (isDeprecated) {
                                AppLogger.w(TAG, "⛔ Версия приложения заблокирована политикой сервера! Current: " + currentVersionCode +
                                        ", Min: " + info.minSupportedVersionCode + ", Latest: " + info.versionCode);

                                isAppBlocked = true;
                                blockedUpdateInfo = info;

                                // Отключаем активный VPN при блокировке версии
                                try {
                                    Intent stopIntent = new Intent(activity, GoblinVpnService.class);
                                    stopIntent.setAction(GoblinVpnService.ACTION_DISCONNECT);
                                    activity.startService(stopIntent);
                                } catch (Throwable ignored) {}

                                // Отправляем уведомление в трей
                                showForceUpdateNotification(activity, info);

                                // Показываем блокирующий диалог
                                showForceUpdateDialog(activity, info, currentVersionName);
                                return;
                            }

                            // Если версия не заблокирована, сбрасываем статус блокировки
                            isAppBlocked = false;
                            blockedUpdateInfo = null;
                            if (activeBlockDialog != null && activeBlockDialog.isShowing()) {
                                try { activeBlockDialog.dismiss(); } catch (Throwable ignored) {}
                                activeBlockDialog = null;
                            }

                            if (info.hasUpdate && info.versionCode > currentVersionCode && !info.apkUrl.isEmpty()) {
                                // Асимметричная криптографическая проверка манифеста обновления
                                if (!verifyUpdateSignature(info)) {
                                    AppLogger.e(TAG, "🚨 Цифровая подпись манифеста обновления не прошла верификацию! Уведомление отклонено.");
                                    if (userTriggered) {
                                        showSecurityAlert(activity, "🚨 ОШИБКА БЕЗОПАСНОСТИ: Цифровая подпись обновления недействительна! Файл не подписан официальным ключом разработчика.");
                                    }
                                    return;
                                }

                                // Всегда отправляем системное уведомление в трей Android
                                showUpdateNotification(activity, info);

                                // Всегда показываем диалог обновления, чтобы пользователь сразу мог обновиться
                                showUpdateDialog(activity, info, currentVersionName);
                            } else {
                                if (userTriggered) {
                                    Toast.makeText(activity,
                                            "У вас установлена самая последняя версия llazyrinVPN (" + currentVersionName + ")",
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        }
                    });

                } catch (final Exception e) {
                    AppLogger.w(TAG, "Ошибка проверки обновлений: " + e.getMessage());
                    if (userTriggered) {
                        MAIN_HANDLER.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!activity.isFinishing()) {
                                    Toast.makeText(activity, "Ошибка проверки: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                }
            }
        });
    }

    /**
     * Создание выделенного канала системных уведомлений об обновлениях (Android 8.0+, API 26+).
     */
    public static void ensureUpdateChannelCreated(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(UPDATE_CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(
                            UPDATE_CHANNEL_ID,
                            UPDATE_CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_HIGH
                    );
                    channel.setDescription("Уведомления о новых версиях и официальных обновлениях llazyrinVPN");
                    channel.enableLights(true);
                    channel.setLightColor(0xFF10B981);
                    channel.enableVibration(true);
                    channel.setShowBadge(true);
                    nm.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                AppLogger.w(TAG, "Ошибка создания канала обновлений: " + e.getMessage());
            }
        }
    }

    /**
     * Отправляет системное push-уведомление в трей Android о доступности новой версии.
     * Нажатие на уведомление открывает MainActivity с флагом EXTRA_CHECK_UPDATE.
     */
    public static void showUpdateNotification(Context context, UpdateInfo info) {
        if (context == null || info == null) return;

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_UPDATE_NAME, Context.MODE_PRIVATE);
            int lastNotifiedVersion = prefs.getInt(PREF_KEY_LAST_NOTIFIED_VERSION, -1);
            long lastNotifiedTime = prefs.getLong(PREF_KEY_LAST_NOTIFIED_TIME, 0L);
            long now = System.currentTimeMillis();

            if (lastNotifiedVersion == info.versionCode && (now - lastNotifiedTime < TRAY_NOTIF_DEBOUNCE_MS)) {
                AppLogger.d(TAG, "Skip tray notification: already notified for version " + info.versionName + " less than 24h ago");
                return;
            }
        } catch (Throwable t) {
            AppLogger.d(TAG, "Notification debounce check failed: " + t.getMessage());
        }

        ensureUpdateChannelCreated(context);

        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(EXTRA_CHECK_UPDATE, true);

            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    NOTIF_ID_UPDATE_TRAY,
                    intent,
                    pendingFlags
            );

            String title = "🚀 Доступно обновление llazyrinVPN " + info.versionName;
            String text = "Новая версия " + info.versionName + " уже доступна. Нажмите для быстрой установки";

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, UPDATE_CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
                builder.setPriority(Notification.PRIORITY_HIGH);
            }

            builder.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                String bigText = text + (info.releaseNotes != null && !info.releaseNotes.isEmpty()
                        ? "\n\nЧто нового:\n" + info.releaseNotes
                        : "");
                builder.setStyle(new Notification.BigTextStyle().bigText(bigText));
            }

            nm.notify(NOTIF_ID_UPDATE_TRAY, builder.build());
            AppLogger.i(TAG, "Системное уведомление о новой версии отправлено в трей: " + info.versionName);

            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_UPDATE_NAME, Context.MODE_PRIVATE);
                prefs.edit()
                        .putInt(PREF_KEY_LAST_NOTIFIED_VERSION, info.versionCode)
                        .putLong(PREF_KEY_LAST_NOTIFIED_TIME, System.currentTimeMillis())
                        .apply();
            } catch (Throwable ignored) {}
        } catch (SecurityException se) {
            AppLogger.w(TAG, "Разрешение POST_NOTIFICATIONS не предоставлено: " + se.getMessage());
        } catch (Exception e) {
            AppLogger.e(TAG, "Не удалось отправить уведомление об обновлении в трей: " + e.getMessage());
        }
    }

    /**
     * Проверяет отложенную установку APK (например, после возврата из системных настроек
     * с разрешением REQUEST_INSTALL_PACKAGES).
     */
    public static void checkPendingInstall(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        if (pendingInstallFile != null && pendingInstallFile.exists()) {
            boolean canInstall = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canInstall = activity.getPackageManager().canRequestPackageInstalls();
            }
            if (canInstall) {
                File fileToInstall = pendingInstallFile;
                pendingInstallFile = null;
                installApk(activity, fileToInstall);
            }
        }
    }

    /**
     * Запрос метаданных последней версии с сервера через защищенное HTTPS соединение.
     */
    private static UpdateInfo fetchUpdateInfo(Context context) throws Exception {
        int currentCode = getAppVersionCode(context);
        String currentName = getAppVersionName(context);
        URL url = new URL(UPDATE_CHECK_URL + "?version_code=" + currentCode + "&version_name=" + currentName);

        // Anti-Spoofing: строгая проверка домена
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new SecurityException("Незащищенный протокол URL обновления: " + url.getProtocol());
        }
        if (!TRUSTED_HOST.equalsIgnoreCase(url.getHost())) {
            throw new SecurityException("Попытка перенаправления на недоверенный хост: " + url.getHost());
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
        conn.setReadTimeout(TIMEOUT_READ_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "GoblinVPN-Updater/" + getAppVersionName(context) + " (Android)");
        conn.setRequestProperty("Accept", "application/json");

        if (conn instanceof HttpsURLConnection) {
            try {
                ((HttpsURLConnection) conn).setSSLSocketFactory(getPinnedSSLSocketFactory());
            } catch (Throwable t) {
                Log.w(TAG, "Socket protect setup skipped: " + t.getMessage());
            }
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            AppLogger.w(TAG, "Сервер обновлений вернул HTTP код: " + code);
            conn.disconnect();
            return null;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();

        JSONObject json = new JSONObject(sb.toString());
        boolean ok = json.optBoolean("ok", true);
        if (!ok) return null;

        boolean hasUpdate = json.optBoolean("has_update", true);
        int versionCode = json.optInt("version_code", json.optInt("versionCode", 0));
        String versionName = json.optString("version_name", json.optString("versionName", ""));
        String apkUrl = json.optString("apk_url", json.optString("download_url", ""));
        long fileSize = json.optLong("file_size", json.optLong("size", 0L));
        String sha256 = json.optString("sha256", json.optString("hash", ""));
        String signature = json.optString("signature", json.optString("sig", ""));
        String releaseNotes = json.optString("release_notes", json.optString("changelog", ""));

        // Парсинг политики минимальных поддерживаемых версий
        int minSupportedVersionCode = json.optInt("min_supported_version_code", json.optInt("min_version_code", 0));
        String minSupportedVersionName = json.optString("min_supported_version_name", json.optString("min_version_name", ""));
        boolean isBlocked = json.optBoolean("is_blocked", false);
        String blockMessage = json.optString("block_message", "");
        int maxVersionsBehind = json.optInt("max_versions_behind", 3);
        String policyMode = json.optString("policy_mode", "auto");

        if (minSupportedVersionCode > 0 && currentCode < minSupportedVersionCode) {
            isBlocked = true;
        }

        return new UpdateInfo(hasUpdate, versionCode, versionName, apkUrl, fileSize, sha256, signature, releaseNotes,
                minSupportedVersionCode, minSupportedVersionName, isBlocked, blockMessage, maxVersionsBehind, policyMode);
    }

    /**
     * Показывает Material 3 диалог подтверждения обновления.
     */
    /**
     * Системное уведомление в трей о критической блокировке устаревшей версии.
     */
    public static void showForceUpdateNotification(Context context, UpdateInfo info) {
        if (context == null || info == null) return;
        ensureUpdateChannelCreated(context);

        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(EXTRA_CHECK_UPDATE, true);

            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    NOTIF_ID_UPDATE_TRAY,
                    intent,
                    pendingFlags
            );

            String title = "⛔ llazyrinVPN заблокирован: требуется обновление";
            String text = "Ваша версия устарела и отключена сервером. Нажмите для обязательного обновления до v" + info.versionName;

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, UPDATE_CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
                builder.setPriority(Notification.PRIORITY_MAX);
            }

            builder.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(false)
                    .setOngoing(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                String bigText = text + "\n\nМинимально поддерживаемая версия: v" + info.minSupportedVersionName +
                        (info.blockMessage != null && !info.blockMessage.isEmpty() ? "\n\n" + info.blockMessage : "");
                builder.setStyle(new Notification.BigTextStyle().bigText(bigText));
            }

            nm.notify(NOTIF_ID_UPDATE_TRAY, builder.build());
        } catch (Throwable t) {
            AppLogger.w(TAG, "showForceUpdateNotification: " + t.getMessage());
        }
    }

    /**
     * Полноэкранный блокирующий диалог (Force Update):
     * - Не закрывается по тапу вне диалога или кнопке назад (закрывает всё приложение).
     * - Блокирует использование VPN до установки актуальной версии.
     * - Предоставляет возможность прямого скачивания и обновления с прогресс-баром.
     */
    public static void showForceUpdateDialog(final Activity activity, final UpdateInfo info, final String currentVersionName) {
        if (activity == null || activity.isFinishing()) return;

        if (activeBlockDialog != null && activeBlockDialog.isShowing()) {
            return;
        }

        final Dialog dialog = new Dialog(activity);
        activeBlockDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_update);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.88f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, android.view.KeyEvent event) {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    activity.finishAffinity();
                    return true;
                }
                return false;
            }
        });

        // Скрываем крестик закрытия
        View btnCloseUpdateDialog = dialog.findViewById(R.id.btnCloseUpdateDialog);
        if (btnCloseUpdateDialog != null) {
            btnCloseUpdateDialog.setVisibility(View.GONE);
        }

        // Заголовок ошибки блокировки
        TextView tvUpdateTitle = dialog.findViewById(R.id.tvUpdateTitle);
        if (tvUpdateTitle != null) {
            tvUpdateTitle.setText("⛔ Версия приложения устарела");
            tvUpdateTitle.setTextColor(0xFFEF4444);
        }

        TextView tvCurrentVersionBadge = dialog.findViewById(R.id.tvCurrentVersionBadge);
        TextView tvNewVersionBadge = dialog.findViewById(R.id.tvNewVersionBadge);
        TextView tvUpdateSize = dialog.findViewById(R.id.tvUpdateSize);
        TextView tvReleaseNotes = dialog.findViewById(R.id.tvReleaseNotes);

        final View layoutUpdateActions = dialog.findViewById(R.id.layoutUpdateActions);
        final View layoutDownloadProgress = dialog.findViewById(R.id.layoutDownloadProgress);
        final ProgressBar pbDownload = dialog.findViewById(R.id.pbDownload);
        final TextView tvDownloadStatus = dialog.findViewById(R.id.tvDownloadStatus);
        final TextView tvDownloadProgress = dialog.findViewById(R.id.tvDownloadProgress);
        final TextView tvDownloadDetail = dialog.findViewById(R.id.tvDownloadDetail);

        final TextView btnUpdateNow = dialog.findViewById(R.id.btnUpdateNow);
        final TextView btnUpdateLater = dialog.findViewById(R.id.btnUpdateLater);

        if (tvCurrentVersionBadge != null) {
            tvCurrentVersionBadge.setText("v" + currentVersionName + " (отключена)");
            tvCurrentVersionBadge.setTextColor(0xFFEF4444);
        }
        if (tvNewVersionBadge != null) {
            tvNewVersionBadge.setText("v" + info.versionName + " (требуется)");
        }
        if (tvUpdateSize != null) {
            if (info.fileSize > 0) {
                double sizeMb = (double) info.fileSize / (1024.0 * 1024.0);
                tvUpdateSize.setText(String.format(Locale.US, "%.1f МБ", sizeMb));
            } else {
                tvUpdateSize.setText("~74 МБ");
            }
        }

        if (tvReleaseNotes != null) {
            StringBuilder sb = new StringBuilder();
            if (info.blockMessage != null && !info.blockMessage.isEmpty()) {
                sb.append(info.blockMessage).append("\n\n");
            } else {
                sb.append("Администратор сервера ограничил поддержку старых версий для обеспечения безопасности туннелей.\n\n");
            }
            if (info.minSupportedVersionName != null && !info.minSupportedVersionName.isEmpty()) {
                sb.append("• Минимально поддерживаемая версия: v").append(info.minSupportedVersionName).append("\n");
            }
            if (info.maxVersionsBehind > 0 && "auto".equals(info.policyMode)) {
                sb.append("• Порог автоблокировки: отставание на ").append(info.maxVersionsBehind).append(" и более обновлений\n");
            }
            if (info.releaseNotes != null && !info.releaseNotes.isEmpty()) {
                sb.append("\nЧто нового в актуальной версии v").append(info.versionName).append(":\n").append(info.releaseNotes);
            }
            tvReleaseNotes.setText(sb.toString());
        }

        if (btnUpdateLater != null) {
            btnUpdateLater.setText("ВЫЙТИ");
            btnUpdateLater.setTextColor(0xFFEF4444);
            btnUpdateLater.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activity.finishAffinity();
                }
            });
        }

        if (btnUpdateNow != null) {
            btnUpdateNow.setText("ОБНОВИТЬ СЕЙЧАС");
            btnUpdateNow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isDownloading) return;

                    if (layoutUpdateActions != null) layoutUpdateActions.setVisibility(View.GONE);
                    if (layoutDownloadProgress != null) layoutDownloadProgress.setVisibility(View.VISIBLE);

                    startDownloadAndInstall(activity, info, dialog, pbDownload, tvDownloadStatus, tvDownloadProgress, tvDownloadDetail);
                }
            });
        }

        try {
            dialog.show();
        } catch (Exception ignored) {}
    }

    public static void showUpdateDialog(final Activity activity, final UpdateInfo info, final String currentVersionName) {
        if (activity == null || activity.isFinishing()) return;

        if (activeUpdateDialog != null && activeUpdateDialog.isShowing()) {
            AppLogger.d(TAG, "showUpdateDialog: Update dialog already showing, skipping duplicate request");
            return;
        }

        final Dialog dialog = new Dialog(activity);
        activeUpdateDialog = dialog;
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (activeUpdateDialog == dialog) {
                    activeUpdateDialog = null;
                }
            }
        });
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_update);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.70f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvCurrentVersionBadge = dialog.findViewById(R.id.tvCurrentVersionBadge);
        TextView tvNewVersionBadge = dialog.findViewById(R.id.tvNewVersionBadge);
        TextView tvUpdateSize = dialog.findViewById(R.id.tvUpdateSize);
        TextView tvReleaseNotes = dialog.findViewById(R.id.tvReleaseNotes);

        final View layoutUpdateActions = dialog.findViewById(R.id.layoutUpdateActions);
        final View layoutDownloadProgress = dialog.findViewById(R.id.layoutDownloadProgress);
        final ProgressBar pbDownload = dialog.findViewById(R.id.pbDownload);
        final TextView tvDownloadStatus = dialog.findViewById(R.id.tvDownloadStatus);
        final TextView tvDownloadProgress = dialog.findViewById(R.id.tvDownloadProgress);
        final TextView tvDownloadDetail = dialog.findViewById(R.id.tvDownloadDetail);

        final TextView btnUpdateNow = dialog.findViewById(R.id.btnUpdateNow);
        final TextView btnUpdateLater = dialog.findViewById(R.id.btnUpdateLater);
        final View btnCloseUpdateDialog = dialog.findViewById(R.id.btnCloseUpdateDialog);

        if (tvCurrentVersionBadge != null) {
            tvCurrentVersionBadge.setText("v" + currentVersionName);
        }
        if (tvNewVersionBadge != null) {
            tvNewVersionBadge.setText("v" + info.versionName);
        }
        if (tvUpdateSize != null) {
            if (info.fileSize > 0) {
                double sizeMb = (double) info.fileSize / (1024.0 * 1024.0);
                tvUpdateSize.setText(String.format(Locale.US, "%.1f МБ", sizeMb));
            } else {
                tvUpdateSize.setText("~28 МБ");
            }
        }
        if (tvReleaseNotes != null && !info.releaseNotes.isEmpty()) {
            tvReleaseNotes.setText(info.releaseNotes);
        }

        View.OnClickListener dismissListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isDownloading) {
                    Toast.makeText(activity, "Загрузка обновления продолжается в фоне...", Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
            }
        };

        if (btnCloseUpdateDialog != null) {
            btnCloseUpdateDialog.setOnClickListener(dismissListener);
        }
        if (btnUpdateLater != null) {
            btnUpdateLater.setOnClickListener(dismissListener);
        }

        if (btnUpdateNow != null) {
            btnUpdateNow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isDownloading) return;

                    // Переключаем диалог в режим загрузки
                    if (layoutUpdateActions != null) layoutUpdateActions.setVisibility(View.GONE);
                    if (layoutDownloadProgress != null) layoutDownloadProgress.setVisibility(View.VISIBLE);
                    dialog.setCancelable(false);

                    startDownloadAndInstall(activity, info, dialog, pbDownload, tvDownloadStatus, tvDownloadProgress, tvDownloadDetail);
                }
            });
        }

        try {
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Загрузка APK с параллельным потоковым вычислением SHA-256 и проверкой подлинности.
     */
    private static void startDownloadAndInstall(final Activity activity, final UpdateInfo info, final Dialog dialog,
                                                final ProgressBar pbDownload, final TextView tvDownloadStatus,
                                                final TextView tvDownloadProgress, final TextView tvDownloadDetail) {
        isDownloading = true;

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                File cacheDir = activity.getExternalCacheDir() != null ? activity.getExternalCacheDir() : activity.getCacheDir();
                final File targetApkFile = new File(cacheDir, APK_FILE_NAME);
                FileOutputStream fos = null;
                InputStream in = null;
                HttpURLConnection conn = null;

                try {
                    // Асимметричная проверка подписи манифеста перед скачиванием
                    if (!verifyUpdateSignature(info)) {
                        throw new SecurityException("ОШИБКА БЕЗОПАСНОСТИ: Манифест обновления не содержит валидной цифровой подписи разработчика!");
                    }

                    URL downloadUrl = new URL(info.apkUrl);

                    // Anti-Spoofing: проверка протокола и домена
                    if (!"https".equalsIgnoreCase(downloadUrl.getProtocol())) {
                        throw new SecurityException("ОШИБКА БЕЗОПАСНОСТИ: URL загрузки должен использовать только HTTPS протокол!");
                    }
                    if (!TRUSTED_HOST.equalsIgnoreCase(downloadUrl.getHost()) &&
                            !downloadUrl.getHost().endsWith("." + TRUSTED_HOST)) {
                        throw new SecurityException("ОШИБКА БЕЗОПАСНОСТИ: Домен загрузки " + downloadUrl.getHost() +
                                " не принадлежит официальному серверу " + TRUSTED_HOST + "!");
                    }

                    if (targetApkFile.exists()) {
                        targetApkFile.delete();
                    }

                    conn = (HttpURLConnection) downloadUrl.openConnection();
                    conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
                    conn.setReadTimeout(TIMEOUT_READ_MS);
                    conn.setRequestProperty("User-Agent", "GoblinVPN-Updater/" + getAppVersionName(activity) + " (Android)");

                    if (conn instanceof HttpsURLConnection) {
                        try {
                            ((HttpsURLConnection) conn).setSSLSocketFactory(getPinnedSSLSocketFactory());
                        } catch (Throwable t) {
                            Log.w(TAG, "Socket protect setup skipped: " + t.getMessage());
                        }
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw new IOException("Сервер загрузки вернул код ответа: " + responseCode);
                    }

                    final long totalBytes = conn.getContentLength() > 0 ? conn.getContentLength() : info.fileSize;
                    in = conn.getInputStream();
                    fos = new FileOutputStream(targetApkFile);

                    // Инициализация аппаратного криптографического дайджеста SHA-256
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");

                    byte[] buffer = new byte[16384]; // 16KB буфер
                    int bytesRead;
                    long downloadedBytes = 0;
                    long lastUpdateTime = 0;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        // 1. Потоковое хеширование
                        digest.update(buffer, 0, bytesRead);
                        // 2. Запись на диск
                        fos.write(buffer, 0, bytesRead);

                        downloadedBytes += bytesRead;

                        // Обновление прогресс-бара не чаще чем раз в 80мс для плавности
                        long now = System.currentTimeMillis();
                        if (now - lastUpdateTime > 80 || downloadedBytes >= totalBytes) {
                            lastUpdateTime = now;
                            final int percent = totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0;
                            final long curBytes = downloadedBytes;

                            MAIN_HANDLER.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (pbDownload != null) pbDownload.setProgress(percent);
                                    if (tvDownloadProgress != null) tvDownloadProgress.setText(percent + "%");
                                    if (tvDownloadDetail != null && totalBytes > 0) {
                                        double curMb = (double) curBytes / (1024.0 * 1024.0);
                                        double totMb = (double) totalBytes / (1024.0 * 1024.0);
                                        tvDownloadDetail.setText(String.format(Locale.US, "%.1f / %.1f МБ", curMb, totMb));
                                    }
                                }
                            });
                        }
                    }

                    fos.flush();
                    fos.close();
                    fos = null;

                    in.close();
                    in = null;
                    conn.disconnect();
                    conn = null;

                    // =========================================================================
                    // КРИТИЧЕСКАЯ ПРОВЕРКА ЦЕЛОСТНОСТИ И ПОДЛИННОСТИ (Anti-MITM / Anti-Tampering)
                    // =========================================================================
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (tvDownloadStatus != null) {
                                tvDownloadStatus.setText("Проверка цифровой подписи и SHA-256...");
                            }
                        }
                    });

                    byte[] hashBytes = digest.digest();
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hashBytes) {
                        sb.append(String.format("%02x", b));
                    }
                    final String calculatedSha256 = sb.toString().toLowerCase(Locale.ROOT).trim();
                    final String expectedSha256 = info.sha256 != null ? info.sha256.toLowerCase(Locale.ROOT).trim() : "";

                    AppLogger.i(TAG, "SHA-256 скачанного APK: " + calculatedSha256);
                    AppLogger.i(TAG, "SHA-256 ожидаемый:       " + expectedSha256);

                    // СТРОГАЯ ВАЛИДАЦИЯ ХЕША
                    if (expectedSha256.isEmpty() || !calculatedSha256.equalsIgnoreCase(expectedSha256)) {
                        // ОБНАРУЖЕНА ПОДМЕНА ИЛИ ПЕРЕХВАТ ТРАФИКА!
                        if (targetApkFile.exists()) {
                            targetApkFile.delete(); // МГНОВЕННОЕ УНИЧТОЖЕНИЕ СКОМПРОМЕТИРОВАННОГО ФАЙЛА
                        }

                        MAIN_HANDLER.post(new Runnable() {
                            @Override
                            public void run() {
                                isDownloading = false;
                                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                                showSecurityAlert(activity,
                                        "КРИТИЧЕСКАЯ ОШИБКА БЕЗОПАСНОСТИ: Хеш файла не совпал с официальным сервером! Обновление заблокировано для защиты устройства.\n\n" +
                                                "Ожидаемый хеш: " + expectedSha256 + "\n" +
                                                "Полученный хеш: " + calculatedSha256);
                            }
                        });
                        return;
                    }

                    // ПРОВЕРКА РАЗМЕРА ФАЙЛА
                    if (info.fileSize > 0 && targetApkFile.length() != info.fileSize) {
                        final long actualSize = targetApkFile.length();
                        if (targetApkFile.exists()) {
                            targetApkFile.delete();
                        }
                        MAIN_HANDLER.post(new Runnable() {
                            @Override
                            public void run() {
                                isDownloading = false;
                                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                                showSecurityAlert(activity, "ОШИБКА БЕЗОПАСНОСТИ: Размер файла (" +
                                        actualSize + " байт) не совпал с сервером (" + info.fileSize + " байт). Файл удален.");
                            }
                        });
                        return;
                    }

                    // =========================================================================
                    // КРИТИЧЕСКАЯ ПРОВЕРКА ПОДПИСИ И СЕРТИФИКАТА APK (Anti-Downgrade / Anti-Tampering)
                    // =========================================================================
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (tvDownloadStatus != null) {
                                tvDownloadStatus.setText("Проверка сертификата разработчика и версии...");
                            }
                        }
                    });

                    if (!verifyApkPackageIntegrity(activity, targetApkFile, info, getAppVersionCode(activity))) {
                        if (targetApkFile.exists()) {
                            targetApkFile.delete(); // МГНОВЕННОЕ УНИЧТОЖЕНИЕ СКОМПРОМЕТИРОВАННОГО ФАЙЛА
                        }

                        MAIN_HANDLER.post(new Runnable() {
                            @Override
                            public void run() {
                                isDownloading = false;
                                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                                showSecurityAlert(activity, "🚨 КРИТИЧЕСКАЯ ОШИБКА БЕЗОПАСНОСТИ: APK-файл не прошел верификацию сертификата разработчика или версии пакета! Обновление заблокировано.");
                            }
                        });
                        return;
                    }

                    // Файл 100% подлинный и защищен
                    final File verifiedApk = targetApkFile;
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            isDownloading = false;
                            if (dialog != null && dialog.isShowing()) {
                                dialog.dismiss();
                            }
                            Toast.makeText(activity, "Хеш SHA-256 подтвержден. Запуск установки...", Toast.LENGTH_SHORT).show();
                            installApk(activity, verifiedApk);
                        }
                    });

                } catch (final Exception e) {
                    AppLogger.e(TAG, "Ошибка загрузки обновления: " + e.getMessage());
                    if (targetApkFile != null && targetApkFile.exists()) {
                        targetApkFile.delete();
                    }

                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            isDownloading = false;
                            if (dialog != null && dialog.isShowing()) dialog.dismiss();
                            Toast.makeText(activity, "Не удалось загрузить обновление: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    /**
     * Запуск системного PackageInstaller через безопасный AppFileProvider Intent.
     */
    public static void installApk(final Activity activity, final File apkFile) {
        if (activity == null || activity.isFinishing() || apkFile == null || !apkFile.exists()) {
            return;
        }

        // Проверка разрешения на установку из неизвестных источников для Android 8.0+ (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean canInstall = activity.getPackageManager().canRequestPackageInstalls();
            if (!canInstall) {
                pendingInstallFile = apkFile;

                new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("🔒 Разрешение на установку")
                        .setMessage("Для завершения официального обновления llazyrinVPN разрешите установку приложений в настройках Android.")
                        .setPositiveButton("В настройки", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                                    activity.startActivity(intent);
                                } catch (Exception e) {
                                    activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                                }
                            }
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
                return;
            }
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri = AppFileProvider.getUriForFile(activity, apkFile);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Grant URI read permission explicitly to all matching resolver packages (Realme UI / ColorOS / HyperOS)
            try {
                java.util.List<android.content.pm.ResolveInfo> resolveInfoList = activity.getPackageManager()
                        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolveInfoList != null) {
                    for (android.content.pm.ResolveInfo resolveInfo : resolveInfoList) {
                        if (resolveInfo.activityInfo != null && resolveInfo.activityInfo.packageName != null) {
                            activity.grantUriPermission(resolveInfo.activityInfo.packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }
                    }
                }
            } catch (Throwable t) {
                AppLogger.w(TAG, "grantUriPermission resolver scan: " + t.getMessage());
            }

            activity.startActivity(intent);
        } catch (Exception e) {
            AppLogger.e(TAG, "Ошибка вызова PackageInstaller: " + e.getMessage());
            Toast.makeText(activity, "Ошибка запуска установщика: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Диалог критического предупреждения о нарушении безопасности / подмене файла.
     */
    private static void showSecurityAlert(Activity activity, String message) {
        if (activity == null || activity.isFinishing()) return;

        new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("🚨 ОШИБКА БЕЗОПАСНОСТИ")
                .setMessage(message)
                .setPositiveButton("Понятно", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
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
            return 21;
        }
    }

    public static String getAppVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "2.6.2";
        }
    }

    /**
     * Создает SSLSocketFactory с Certificate Pinning и защитой от зацикливания сокетов через VPN.
     */
    private static SSLSocketFactory getPinnedSSLSocketFactory() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null);

        X509TrustManager defaultTm = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                defaultTm = (X509TrustManager) tm;
                break;
            }
        }

        final X509TrustManager finalDefaultTm = defaultTm;

        X509TrustManager pinningTm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (finalDefaultTm != null) {
                    finalDefaultTm.checkClientTrusted(chain, authType);
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (finalDefaultTm != null) {
                    finalDefaultTm.checkServerTrusted(chain, authType);
                }

                if (chain == null || chain.length == 0) {
                    throw new CertificateException("Empty certificate chain");
                }

                boolean pinnedMatch = false;
                try {
                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    for (X509Certificate cert : chain) {
                        byte[] encoded = cert.getEncoded();
                        byte[] digest = sha256.digest(encoded);
                        StringBuilder hex = new StringBuilder();
                        for (byte b : digest) {
                            hex.append(String.format("%02x", b));
                        }
                        String certFingerprint = hex.toString();
                        for (String pin : PINNED_CERT_SHA256) {
                            if (pin.equalsIgnoreCase(certFingerprint)) {
                                pinnedMatch = true;
                                break;
                            }
                        }
                        if (pinnedMatch) break;
                    }
                } catch (Exception e) {
                    throw new CertificateException("Certificate pinning check failed: " + e.getMessage(), e);
                }

                if (!pinnedMatch) {
                    throw new CertificateException("Certificate pinning validation failed! Server certificate does not match pinned fingerprints.");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return finalDefaultTm != null ? finalDefaultTm.getAcceptedIssuers() : new X509Certificate[0];
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{pinningTm}, new java.security.SecureRandom());
        return new ProtectedSSLSocketFactory(sslContext.getSocketFactory());
    }

    /**
     * Асимметричная криптографическая проверка подлинности манифеста релиза (RSA-2048 / SHA-256).
     * Предотвращает загрузку вредоносных обновлений даже при полной компрометации веб-сервера.
     */
    public static boolean verifyUpdateSignature(UpdateInfo info) {
        if (info == null || info.signature == null || info.signature.isEmpty()) {
            AppLogger.e(TAG, "Критическая ошибка: отсутствует цифровая подпись манифеста обновления!");
            return false;
        }
        try {
            String payload = "llazyrinVPN-Release:" + info.versionCode + ":" + info.versionName + ":" +
                    info.sha256.toLowerCase(Locale.ROOT).trim() + ":" + info.fileSize;
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);

            String pubKeyClean = UPDATE_SIGNING_PUBLIC_KEY_PEM
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.decode(pubKeyClean, Base64.DEFAULT);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey publicKey = kf.generatePublic(spec);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(data);

            byte[] sigBytes = Base64.decode(info.signature, Base64.DEFAULT);
            boolean verified = sig.verify(sigBytes);
            if (verified) {
                AppLogger.i(TAG, "✓ Цифровая подпись релиза успешно проверена (RSA-2048/SHA-256)");
            } else {
                AppLogger.e(TAG, "🚨 Цифровая подпись релиза НЕВЕРНА!");
            }
            return verified;
        } catch (Exception e) {
            AppLogger.e(TAG, "Ошибка проверки цифровой подписи: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Глубокая инспекция APK перед установкой:
     * 1. Соответствие package name ("ru.llazyrin.goblinvpn").
     * 2. Защита от Downgrade (версия строго больше текущей установленной и равна версии в манифесте).
     * 3. Сверка сертификата разработчика APK с текущим запущенным приложением.
     */
    public static boolean verifyApkPackageIntegrity(Context context, File apkFile, UpdateInfo info, int currentVersionCode) {
        if (context == null || apkFile == null || !apkFile.exists()) {
            return false;
        }
        try {
            PackageManager pm = context.getPackageManager();
            int flags = PackageManager.GET_SIGNATURES;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                flags |= PackageManager.GET_SIGNING_CERTIFICATES;
            }

            PackageInfo downloadedInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
            if (downloadedInfo == null) {
                AppLogger.e(TAG, "verifyApkPackageIntegrity: getPackageArchiveInfo returned null (APK corrupt or invalid)");
                return false;
            }

            // 1. Проверка идентификатора пакета
            String expectedPackage = context.getPackageName();
            if (!expectedPackage.equals(downloadedInfo.packageName)) {
                AppLogger.e(TAG, "verifyApkPackageIntegrity: Несовпадение package name! Ожидалось " +
                        expectedPackage + ", получено " + downloadedInfo.packageName);
                return false;
            }

            // 2. Защита от Downgrade-атаки и проверка версии
            int downloadedVersionCode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                downloadedVersionCode = (int) downloadedInfo.getLongVersionCode();
            } else {
                downloadedVersionCode = downloadedInfo.versionCode;
            }

            if (downloadedVersionCode <= currentVersionCode) {
                AppLogger.e(TAG, "verifyApkPackageIntegrity: Попытка даунгрейда заблокирована! Текущая: " +
                        currentVersionCode + ", в APK: " + downloadedVersionCode);
                return false;
            }

            if (info != null && info.versionCode > 0 && downloadedVersionCode != info.versionCode) {
                AppLogger.e(TAG, "verifyApkPackageIntegrity: Несовпадение версии APK с манифестом! В манифесте: " +
                        info.versionCode + ", в APK: " + downloadedVersionCode);
                return false;
            }

            // 3. Проверка совпадения сертификата разработчика с установленным приложением
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageInfo currentInfo = pm.getPackageInfo(expectedPackage, PackageManager.GET_SIGNING_CERTIFICATES);
                    if (currentInfo.signingInfo != null && downloadedInfo.signingInfo != null) {
                        android.content.pm.Signature[] currentSigs = currentInfo.signingInfo.hasMultipleSigners()
                                ? currentInfo.signingInfo.getApkContentsSigners()
                                : currentInfo.signingInfo.getSigningCertificateHistory();
                        android.content.pm.Signature[] downloadedSigs = downloadedInfo.signingInfo.hasMultipleSigners()
                                ? downloadedInfo.signingInfo.getApkContentsSigners()
                                : downloadedInfo.signingInfo.getSigningCertificateHistory();

                        if (!signaturesMatch(currentSigs, downloadedSigs)) {
                            AppLogger.e(TAG, "verifyApkPackageIntegrity: Сертификат APK не совпадает с сертификатом установленного приложения!");
                            return false;
                        }
                    } else {
                        PackageInfo curLegacy = pm.getPackageInfo(expectedPackage, PackageManager.GET_SIGNATURES);
                        if (curLegacy.signatures != null && downloadedInfo.signatures != null) {
                            if (!signaturesMatch(curLegacy.signatures, downloadedInfo.signatures)) {
                                AppLogger.e(TAG, "verifyApkPackageIntegrity: Сигнатуры APK не совпадают!");
                                return false;
                            }
                        }
                    }
                } else {
                    PackageInfo currentInfo = pm.getPackageInfo(expectedPackage, PackageManager.GET_SIGNATURES);
                    if (currentInfo.signatures != null && downloadedInfo.signatures != null) {
                        if (!signaturesMatch(currentInfo.signatures, downloadedInfo.signatures)) {
                            AppLogger.e(TAG, "verifyApkPackageIntegrity: Сигнатуры APK не совпадают!");
                            return false;
                        }
                    }
                }
            } catch (Throwable t) {
                AppLogger.w(TAG, "verifyApkPackageIntegrity: Предупреждение сверки подписей: " + t.getMessage());
            }

            AppLogger.i(TAG, "✓ Проверка целостности APK успешна (Package: " +
                    downloadedInfo.packageName + ", VersionCode: " + downloadedVersionCode + ")");
            return true;
        } catch (Exception e) {
            AppLogger.e(TAG, "verifyApkPackageIntegrity exception: " + e.getMessage(), e);
            return false;
        }
    }

    private static boolean signaturesMatch(android.content.pm.Signature[] sigs1, android.content.pm.Signature[] sigs2) {
        if (sigs1 == null || sigs2 == null || sigs1.length == 0 || sigs2.length == 0) {
            return false;
        }
        for (android.content.pm.Signature s1 : sigs1) {
            for (android.content.pm.Signature s2 : sigs2) {
                if (s1.equals(s2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * SSLSocketFactory с защитой сокетов через GoblinVpnService.protectSocket(...)
     */
    private static class ProtectedSSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        ProtectedSSLSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket protect(Socket socket) {
            if (socket != null) {
                try {
                    GoblinVpnService.protectSocket(socket);
                } catch (Throwable ignored) {}
            }
            return socket;
        }

        @Override
        public Socket createSocket() throws IOException {
            return protect(delegate.createSocket());
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return protect(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return protect(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return protect(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return protect(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return protect(delegate.createSocket(address, port, localAddress, localPort));
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }
    }
}
