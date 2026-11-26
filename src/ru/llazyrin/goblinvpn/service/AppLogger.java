package ru.llazyrin.goblinvpn.service;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ru.llazyrin.goblinvpn.model.NetworkPreferences;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.net.AppTelemetryClient;

/**
 * AppLogger — Централизованный потокобезопасный диспетчер логов Goblin VPN.
 *
 * Особенности:
 * 1. Сохраняет события в потокобезопасную кольцевую очередь памяти (ConcurrentLinkedQueue<LogItem>).
 * 2. Ограничивает размер очереди 200 элементами: при переполнении старые записи автоматически вытесняются (drop oldest).
 * 3. Фоновый планировщик каждые 30 секунд (или по накоплению 25+ записей) при активном флаге
 *    AppTelemetryClient.isRemoteLoggingEnabled() извлекает пачку до 50 записей и отправляет
 *    на сервер телеметрии https://llazyrin.online/api/app/logs через защищённый сокет (VPN protectSocket).
 * 4. При успешной отправке отправленные записи удаляются из очереди; при сбое сети — сохраняются для повтора.
 * 5. Локальный просмотр логов в интерфейсе полностью отключен.
 */
public class AppLogger {
    private static final String DEFAULT_TAG = "GoblinVPN";
    private static final int MAX_QUEUE_SIZE = 1000;
    private static final int BATCH_SIZE = 100;
    private static final int BATCH_TRIGGER_THRESHOLD = 50;
    private static final int FLUSH_INTERVAL_SECONDS = 120;

    private static final ConcurrentLinkedQueue<LogItem> logQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger queueSize = new AtomicInteger(0);
    private static final Object flushLock = new Object();
    private static final Object schedulerLock = new Object();
    private static volatile Context appContext = null;

    private static ScheduledExecutorService scheduler = null;
    private static ScheduledFuture<?> scheduledFlushFuture = null;
    private static final AtomicBoolean isSchedulerStarted = new AtomicBoolean(false);

    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        }
    };

    /**
     * DTO записи лога для дистанционной телеметрии.
     */
    public static class LogItem {
        public final String timestamp;
        public final String level;
        public final String tag;
        public final String message;
        public final String stacktrace;

        public LogItem(String timestamp, String level, String tag, String message, String stacktrace) {
            this.timestamp = timestamp != null ? timestamp : "";
            this.level = level != null ? level : "INFO";
            this.tag = tag != null ? tag : DEFAULT_TAG;
            this.message = message != null ? message : "";
            this.stacktrace = stacktrace != null ? stacktrace : "";
        }

        public JSONObject toJsonObject() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("timestamp", timestamp);
                obj.put("level", level);
                obj.put("tag", tag);
                obj.put("message", message);
                obj.put("stacktrace", stacktrace);
            } catch (Exception ignored) {}
            return obj;
        }

        @Override
        public String toString() {
            return "[" + timestamp + "] [" + level + "/" + tag + "] " + message +
                    (stacktrace.isEmpty() ? "" : "\n" + stacktrace);
        }
    }

    @Deprecated
    public interface LogListener {
        void onLogAdded(String formattedLine);
    }

    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        AppTelemetryClient.init(appContext);

        // Очищаем старый локальный файл логов, если он остался от предыдущих версий
        try {
            File legacyFile = new File(appContext.getFilesDir(), "goblin_beta.log");
            if (legacyFile.exists()) {
                legacyFile.delete();
            }
        } catch (Exception ignored) {}

        if (AppTelemetryClient.isRemoteLoggingEnabled()) {
            startPeriodicFlushIfNeeded();
        } else {
            stopPeriodicFlush();
        }

        i(DEFAULT_TAG, "AppLogger initialized. OS: Android " + Build.VERSION.RELEASE +
                " (API " + Build.VERSION.SDK_INT + "), Device: " + Build.MANUFACTURER + " " + Build.MODEL);
    }

    private static ScheduledExecutorService getOrCreateSchedulerLocked() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "GoblinLogDispatcher");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return scheduler;
    }

    private static void startPeriodicFlushIfNeeded() {
        if (!AppTelemetryClient.isRemoteLoggingEnabled()) {
            return;
        }
        synchronized (schedulerLock) {
            if (!AppTelemetryClient.isRemoteLoggingEnabled()) {
                return;
            }
            if (scheduledFlushFuture == null || scheduledFlushFuture.isCancelled() || scheduledFlushFuture.isDone()) {
                ScheduledExecutorService exec = getOrCreateSchedulerLocked();
                scheduledFlushFuture = exec.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            flushLogsInternal();
                        } catch (Throwable t) {
                            Log.w(DEFAULT_TAG, "Periodic log flush error: " + t.getMessage());
                        }
                    }
                }, 10, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
                isSchedulerStarted.set(true);
            }
        }
    }

    private static void stopPeriodicFlush() {
        synchronized (schedulerLock) {
            if (scheduledFlushFuture != null) {
                scheduledFlushFuture.cancel(false);
                scheduledFlushFuture = null;
            }
            if (scheduler != null) {
                scheduler.shutdown();
                scheduler = null;
            }
            isSchedulerStarted.set(false);
        }
    }

    public static void i(String tag, String message) {
        String effectiveTag = tag != null ? tag : DEFAULT_TAG;
        String effectiveMsg = message != null ? message : "";
        Log.i(effectiveTag, effectiveMsg);
        enqueue("INFO", effectiveTag, effectiveMsg, "");
    }

    public static void d(String tag, String message) {
        String effectiveTag = tag != null ? tag : DEFAULT_TAG;
        String effectiveMsg = message != null ? message : "";
        Log.d(effectiveTag, effectiveMsg);
        enqueue("DEBUG", effectiveTag, effectiveMsg, "");
    }

    public static void w(String tag, String message) {
        String effectiveTag = tag != null ? tag : DEFAULT_TAG;
        String effectiveMsg = message != null ? message : "";
        Log.w(effectiveTag, effectiveMsg);
        enqueue("WARN", effectiveTag, effectiveMsg, "");
    }

    public static void e(String tag, String message) {
        String effectiveTag = tag != null ? tag : DEFAULT_TAG;
        String effectiveMsg = message != null ? message : "";
        Log.e(effectiveTag, effectiveMsg);
        enqueue("ERROR", effectiveTag, effectiveMsg, "");
    }

    public static void e(String tag, String message, Throwable tr) {
        String effectiveTag = tag != null ? tag : DEFAULT_TAG;
        String effectiveMsg = message != null ? message : "";
        String stacktrace = "";
        if (tr != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            tr.printStackTrace(pw);
            stacktrace = sw.toString();
        }
        Log.e(effectiveTag, effectiveMsg + (stacktrace.isEmpty() ? "" : "\n" + stacktrace), tr);
        enqueue("ERROR", effectiveTag, effectiveMsg, stacktrace);
    }

    private static void enqueue(String level, String tag, String message, String stacktrace) {
        String timestamp = TIME_FORMAT.get().format(new Date());
        String effectiveTag = tag != null ? tag : DEFAULT_TAG;
        String effectiveMsg = message != null ? message : "";
        String effectiveTrace = stacktrace != null ? stacktrace : "";

        LogItem item = new LogItem(timestamp, level, effectiveTag, effectiveMsg, effectiveTrace);
        if (logQueue.offer(item)) {
            int currentSize = queueSize.incrementAndGet();

            // При превышении лимита записей вытесняем самые старые записи из очереди
            while (currentSize > MAX_QUEUE_SIZE) {
                if (logQueue.poll() != null) {
                    currentSize = queueSize.decrementAndGet();
                } else {
                    break;
                }
            }

            // Если накопилось BATCH_TRIGGER_THRESHOLD+ записей и логирование включено сервером — запускаем досрочную отправку
            if (currentSize >= BATCH_TRIGGER_THRESHOLD && AppTelemetryClient.isRemoteLoggingEnabled()) {
                triggerFlushAsync();
            }
        }
    }

    public static void triggerFlushAsync() {
        if (!AppTelemetryClient.isRemoteLoggingEnabled()) {
            return;
        }
        synchronized (schedulerLock) {
            if (!AppTelemetryClient.isRemoteLoggingEnabled()) {
                return;
            }
            getOrCreateSchedulerLocked().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        flushLogsInternal();
                    } catch (Throwable t) {
                        Log.w(DEFAULT_TAG, "Async log flush error: " + t.getMessage());
                    }
                }
            });
        }
    }

    /**
     * Вызывается клиентом телеметрии при смене серверного статуса logging_enabled.
     */
    public static void onRemoteLoggingStateChanged(boolean enabled) {
        if (enabled) {
            startPeriodicFlushIfNeeded();
            if (!logQueue.isEmpty()) {
                triggerFlushAsync();
            }
        } else {
            stopPeriodicFlush();
        }
    }

    private static void flushLogsInternal() {
        if (appContext == null) {
            return;
        }
        // Strict privacy requirement: user consent must be explicitly granted
        if (!NetworkPreferences.getInstance(appContext).isUserLoggingConsentGranted()) {
            return;
        }
        if (!AppTelemetryClient.isRemoteLoggingEnabled()) {
            return;
        }

        synchronized (flushLock) {
            while (AppTelemetryClient.isRemoteLoggingEnabled() && !logQueue.isEmpty()) {
                List<LogItem> batch = new ArrayList<>(BATCH_SIZE);
                Iterator<LogItem> it = logQueue.iterator();
                while (it.hasNext() && batch.size() < BATCH_SIZE) {
                    batch.add(it.next());
                }

                if (batch.isEmpty()) {
                    break;
                }

                boolean success = AppTelemetryClient.sendBatchLogs(appContext, batch);
                if (success) {
                    for (LogItem sentItem : batch) {
                        if (logQueue.peek() == sentItem) {
                            if (logQueue.poll() != null) {
                                queueSize.decrementAndGet();
                            }
                        } else if (logQueue.remove(sentItem)) {
                            queueSize.decrementAndGet();
                        }
                    }
                    if (batch.size() < BATCH_SIZE) {
                        break;
                    }
                } else {
                    // Ошибка сети — оставляем записи в очереди для следующей попытки
                    Log.d(DEFAULT_TAG, "sendBatchLogs network error, retained " + queueSize.get() + " items in queue");
                    break;
                }
            }
        }
    }

    public static String getLogs() {
        StringBuilder sb = new StringBuilder();
        for (LogItem item : logQueue) {
            sb.append(item.toString()).append("\n");
        }
        return sb.toString();
    }

    public static void clearLogs() {
        while (logQueue.poll() != null) {
            queueSize.decrementAndGet();
        }
        i(DEFAULT_TAG, "Журнал логов очищен");
    }

    @Deprecated
    public static void addListener(LogListener listener) {
        // Локальное прослушивание логов отключено
    }

    @Deprecated
    public static void removeListener(LogListener listener) {
        // Локальное прослушивание логов отключено
    }

    /**
     * Создает полноценный отчёт для бета-тестеров, содержащий диагностику устройства,
     * настройки сети и последние системные события.
     */
    public static String getFullReport(Context context, VpnServer currentServer, VpnState currentState) {
        StringBuilder report = new StringBuilder();
        report.append("=========================================\n");
        report.append("📱 LLAZYRINVPN — ОТЧЁТ БЕТА-ТЕСТИРОВАНИЯ\n");
        report.append("=========================================\n");

        String versionName = "2.4.0";
        int versionCode = 12;
        if (context != null) {
            try {
                PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                versionName = pInfo.versionName;
                versionCode = pInfo.versionCode;
            } catch (Exception ignored) {}
        }

        report.append("Версия приложения: v").append(versionName).append(" (код ").append(versionCode).append(")\n");
        report.append("Устройство: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        report.append("ОС Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");

        if (context != null) {
            NetworkPreferences prefs = NetworkPreferences.getInstance(context);
            report.append("ID клиента: ").append(prefs.getClientId()).append("\n");
            report.append("DNS провайдер: ").append(prefs.getDnsOption().getTitle()).append("\n");
            report.append("Обход зоны РФ: ").append(prefs.isBypassRuTrafficEnabled() ? "ВКЛ" : "ВЫКЛ").append("\n");
            report.append("Kill Switch: ").append(prefs.isKillSwitchEnabled() ? "ВКЛ" : "ВЫКЛ").append("\n");
            report.append("Режим прокси приложений: ").append(prefs.getAppRoutingMode().getTitle()).append("\n");
            report.append("Выбрано приложений для маршрутизации: ").append(prefs.getRoutedPackages().size()).append("\n");
        }

        report.append("Статус VPN: ").append(currentState != null ? currentState.name() : "UNKNOWN").append("\n");
        if (currentServer != null) {
            report.append("Сервер: ").append(currentServer.getCountry())
                    .append(" (").append(currentServer.getHost()).append(":").append(currentServer.getPort()).append(")\n");
            report.append("Пинг: ").append(currentServer.getPingMs()).append(" мс\n");
            report.append("Протокол: ").append(currentServer.isReality() ? "VLESS Reality (XTLS-Vision)" : "VLESS").append("\n");
        } else {
            report.append("Сервер: Автовыбор\n");
        }
        report.append("Ядро: Xray-core Native (Go-mobile)\n");
        report.append("Время формирования: ").append(TIME_FORMAT.get().format(new Date())).append("\n");
        report.append("=========================================\n\n");
        report.append("--- ЖУРНАЛ СОБЫТИЙ ---\n");
        report.append(getLogs());
        return report.toString();
    }
}
