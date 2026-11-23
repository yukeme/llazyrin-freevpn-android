package ru.llazyrin.goblinvpn.security;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * AppFileProvider — Легковесный защищенный ContentProvider для безопасной передачи APK
 * установщику Android (PackageInstaller) без внешних зависимостей (чистый android.jar).
 *
 * Полная совместимость со Scoped Storage (Android 7.0 - 15+) и защита от Path Traversal.
 */
public class AppFileProvider extends ContentProvider {

    private static final String TAG = "AppFileProvider";
    public static final String AUTHORITY = "ru.llazyrin.goblinvpn.fileprovider";
    public static final String CONTENT_URI_PREFIX = "content://" + AUTHORITY + "/";

    private static final String[] COLUMNS = {
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * Генерирует защищенный content:// URI для передачи системному PackageInstaller Android.
     */
    public static Uri getUriForFile(Context context, File file) {
        if (file == null) {
            throw new IllegalArgumentException("Файл не может быть null");
        }
        return Uri.parse(CONTENT_URI_PREFIX + "cache/" + file.getName());
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && !"rwt".equals(mode) && !mode.contains("r")) {
            throw new SecurityException("Режим записи запрещен для AppFileProvider: " + mode);
        }

        File file = getFileForUri(uri);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("Файл не найден для URI: " + uri);
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = getFileForUri(uri);
        if (file == null || !file.exists()) {
            return null;
        }

        if (projection == null) {
            projection = COLUMNS;
        }

        String[] cols = new String[projection.length];
        Object[] values = new Object[projection.length];
        int i = 0;
        for (String col : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                cols[i] = OpenableColumns.DISPLAY_NAME;
                values[i++] = file.getName();
            } else if (OpenableColumns.SIZE.equals(col)) {
                cols[i] = OpenableColumns.SIZE;
                values[i++] = file.length();
            }
        }

        cols = copyOf(cols, i);
        values = copyOf(values, i);

        MatrixCursor cursor = new MatrixCursor(cols, 1);
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String path = uri != null ? uri.getPath() : null;
        if (path != null && path.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Операция insert не поддерживается в AppFileProvider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File file = getFileForUri(uri);
        if (file != null && file.exists()) {
            return file.delete() ? 1 : 0;
        }
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Операция update не поддерживается в AppFileProvider");
    }

    private File getFileForUri(Uri uri) {
        Context context = getContext();
        if (context == null || uri == null) return null;

        String path = uri.getPath();
        if (path == null) return null;

        // Формат пути: /cache/update_download.apk
        String fileName;
        if (path.startsWith("/cache/")) {
            fileName = path.substring("/cache/".length());
        } else if (path.startsWith("/")) {
            fileName = path.substring(1);
        } else {
            fileName = path;
        }

        // Защита от Path Traversal
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            Log.e(TAG, "КРИТИЧЕСКАЯ УГРОЗА: Попытка Path Traversal в URI: " + uri);
            return null;
        }

        // 1. Поиск во внешнем кэше приложения (getExternalCacheDir)
        File extCache = context.getExternalCacheDir();
        if (extCache != null) {
            File f = new File(extCache, fileName);
            if (f.exists()) return f;
        }

        // 2. Поиск во внутреннем кэше приложения (getCacheDir)
        File intCache = context.getCacheDir();
        if (intCache != null) {
            File f = new File(intCache, fileName);
            if (f.exists()) return f;
        }

        // Если файл еще не создан или проверяется путь
        if (extCache != null) {
            return new File(extCache, fileName);
        }
        return new File(intCache, fileName);
    }

    private static String[] copyOf(String[] original, int newLength) {
        String[] result = new String[newLength];
        System.arraycopy(original, 0, result, 0, Math.min(original.length, newLength));
        return result;
    }

    private static Object[] copyOf(Object[] original, int newLength) {
        Object[] result = new Object[newLength];
        System.arraycopy(original, 0, result, 0, Math.min(original.length, newLength));
        return result;
    }
}
