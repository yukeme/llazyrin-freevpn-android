package ru.llazyrin.goblinvpn.security;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.net.VpnSubscriptionClient;

/**
 * EmergencyConfigStorage — Автономное зашифрованное хранилище резервных VPN-конфигов.
 *
 * Обеспечивает выживаемость приложения при:
 * 1. Блокировках источников подписок DPI / РКН.
 * 2. Работе мобильных сетей в условиях "глушилок" (белые списки, пропадание связи).
 * 3. Отсутствии доступа к внешнему интернету во время холодного старта.
 */
public class EmergencyConfigStorage {
    private static final String TAG = "EmergencyConfigStorage";
    private static final String STASH_FILE_NAME = ".goblin_reserve.stash";

    /**
     * Сохраняет проверенные живые конфиги в локальный зашифрованный тайник устройства.
     */
    public static synchronized boolean saveEmergencyReserve(Context context, List<VpnServer> servers) {
        if (context == null || servers == null || servers.isEmpty()) {
            return false;
        }

        try {
            JSONArray arr = new JSONArray();
            int count = 0;
            for (VpnServer s : servers) {
                if (s == null || s.getRawUri() == null || s.getRawUri().trim().isEmpty()) continue;
                JSONObject obj = new JSONObject();
                obj.put("rawUri", s.getRawUri());
                obj.put("host", s.getHost());
                obj.put("port", s.getPort());
                obj.put("flag", s.getFlag());
                obj.put("country", s.getCountry());
                obj.put("remark", s.getRemark());
                obj.put("pingMs", s.getPingMs());
                obj.put("sni", s.getSni());
                obj.put("security", s.getSecurity());
                obj.put("isCommunityTop", s.isCommunityTop());
                if (s.getCommunityScore() > 0) {
                    obj.put("communityScore", s.getCommunityScore());
                }
                if (s.getCommunityBadge() != null) {
                    obj.put("communityBadge", s.getCommunityBadge());
                }
                arr.put(obj);
                count++;
                if (count >= 15) break; // Храним до 15 лучших резервных нод
            }

            if (count == 0) return false;

            String jsonPayload = arr.toString();
            byte[] encrypted = encryptWithDeviceSalt(context, jsonPayload);

            File target = new File(context.getFilesDir(), STASH_FILE_NAME);
            File temp = new File(context.getFilesDir(), STASH_FILE_NAME + ".tmp");

            FileOutputStream fos = new FileOutputStream(temp);
            fos.write(encrypted);
            fos.flush();
            fos.close();

            if (temp.renameTo(target)) {
                Log.i(TAG, "Emergency stash successfully updated with " + count + " verified nodes");
                return true;
            } else {
                // Прямая запись при отказе атомарного rename
                FileOutputStream fosDirect = new FileOutputStream(target);
                fosDirect.write(encrypted);
                fosDirect.flush();
                fosDirect.close();
                temp.delete();
                Log.i(TAG, "Emergency stash directly written with " + count + " nodes");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save emergency reserve: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Загружает ранее отобранные резервные конфиги из локального хранилища.
     * При отсутствии или повреждении файла возвращает расшифрованные семена безопасности.
     */
    public static synchronized List<VpnServer> loadEmergencyReserve(Context context) {
        List<VpnServer> result = new ArrayList<>();
        if (context != null) {
            File file = new File(context.getFilesDir(), STASH_FILE_NAME);
            if (file.exists() && file.length() > 0) {
                try {
                    FileInputStream fis = new FileInputStream(file);
                    byte[] data = new byte[(int) file.length()];
                    int read = fis.read(data);
                    fis.close();

                    if (read > 0) {
                        String decrypted = decryptWithDeviceSalt(context, data);
                        if (decrypted != null && decrypted.startsWith("[")) {
                            JSONArray arr = new JSONArray(decrypted);
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject obj = arr.getJSONObject(i);
                                String rawUri = obj.optString("rawUri");
                                String host = obj.optString("host");
                                int port = obj.optInt("port", 443);
                                String flag = obj.optString("flag", "🌐");
                                String country = obj.optString("country", "Сервер");
                                String remark = obj.optString("remark", country);
                                int ping = obj.optInt("pingMs", 45);
                                String sni = obj.optString("sni", host);
                                String security = obj.optString("security", "reality");

                                VpnServer s = new VpnServer(rawUri, host, port, flag, country, remark, ping, sni, security);
                                if (obj.optBoolean("isCommunityTop", false)) {
                                    s.setCommunityTop(true);
                                }
                                if (obj.has("communityScore")) {
                                    s.setCommunityScore(obj.optDouble("communityScore", 0.0));
                                }
                                if (obj.has("communityBadge")) {
                                    s.setCommunityBadge(obj.optString("communityBadge"));
                                }
                                if (ru.llazyrin.goblinvpn.MainActivity.isKnownDeadHost(host)) {
                                    continue;
                                }
                                result.add(s);
                            }
                            Log.i(TAG, "Loaded " + result.size() + " emergency servers from local secure stash");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not read local stash, falling back to secure vault seeds: " + e.getMessage());
                }
            }
        }

        // Если локальный тайник пуст или повреждён, загружаем расшифрованные семена из Vault
        if (result.isEmpty()) {
            List<String> seeds = GoblinSecurityVault.getBuiltinEmergencySeeds();
            for (String seedUri : seeds) {
                VpnServer s = VpnSubscriptionClient.parseVlessLine(seedUri);
                if (s != null) {
                    result.add(s);
                }
            }
            Log.i(TAG, "Loaded " + result.size() + " default emergency seeds from GoblinSecurityVault");
        }

        return result;
    }

    public static boolean hasEmergencyReserve(Context context) {
        if (context == null) return false;
        File file = new File(context.getFilesDir(), STASH_FILE_NAME);
        return file.exists() && file.length() > 16;
    }

    public static long getLastSavedTimestamp(Context context) {
        if (context == null) return 0;
        File file = new File(context.getFilesDir(), STASH_FILE_NAME);
        return file.exists() ? file.lastModified() : 0;
    }

    private static byte[] encryptWithDeviceSalt(Context context, String text) {
        byte[] raw = GoblinSecurityVault.encrypt(text);
        byte[] salt = getDeviceSalt(context);
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (raw[i] ^ salt[i % salt.length]);
        }
        return raw;
    }

    private static String decryptWithDeviceSalt(Context context, byte[] data) {
        byte[] salt = getDeviceSalt(context);
        byte[] temp = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            temp[i] = (byte) (data[i] ^ salt[i % salt.length]);
        }
        return GoblinSecurityVault.decrypt(temp);
    }

    private static byte[] getDeviceSalt(Context context) {
        String base = (context != null ? context.getPackageName() : "goblin") + "_" + Build.BOARD + "_" + Build.MODEL;
        try {
            return base.getBytes("UTF-8");
        } catch (Exception e) {
            return new byte[]{0x1A, 0x2B, 0x3C, 0x4D};
        }
    }
}
