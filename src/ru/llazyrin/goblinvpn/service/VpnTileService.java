package ru.llazyrin.goblinvpn.service;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.List;

import ru.llazyrin.goblinvpn.MainActivity;
import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.model.NetworkPreferences;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.net.VpnSubscriptionClient;
import ru.llazyrin.goblinvpn.net.WhitelistPoolManager;
import ru.llazyrin.goblinvpn.security.EmergencyConfigStorage;

/**
 * Quick Settings Tile Service for llazyrinVPN.
 * Allows instant connect/disconnect toggle directly from the Android system notification shade.
 */
@TargetApi(Build.VERSION_CODES.N)
public class VpnTileService extends TileService {
    private static final String TAG = "VpnTileService";

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateTileUI();
        }
    };
    private boolean isReceiverRegistered = false;

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (!isReceiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter(GoblinVpnService.ACTION_STATE_CHANGED);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(stateReceiver, filter);
                }
                isReceiverRegistered = true;
            } catch (Throwable t) {
                AppLogger.w(TAG, "Failed to register state receiver: " + t.getMessage());
            }
        }
        updateTileUI();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Throwable ignored) {
            }
            isReceiverRegistered = false;
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        VpnState currentState = GoblinVpnService.getCurrentState();
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            // User requested disconnection
            Intent dcIntent = new Intent(this, GoblinVpnService.class);
            dcIntent.setAction(GoblinVpnService.ACTION_DISCONNECT);
            startService(dcIntent);
            updateTileState(Tile.STATE_INACTIVE, "Отключено");
        } else {
            // Check if VpnService permission has already been granted
            Intent prepIntent = VpnService.prepare(this);
            if (prepIntent != null) {
                // User permission required -> launch MainActivity
                launchMainActivity();
            } else {
                // Target server resolution (prioritizing mobile whitelist pool if on cellular)
                VpnServer target = resolveTargetServer();
                if (target == null) {
                    launchMainActivity();
                    return;
                }

                Intent startIntent = new Intent(this, GoblinVpnService.class);
                startIntent.setAction(GoblinVpnService.ACTION_CONNECT);
                startIntent.putExtra(GoblinVpnService.EXTRA_SERVER, target);
                if (NetworkPreferences.getInstance(this).isRestrictedTelegramOnly()) {
                    startIntent.putExtra(GoblinVpnService.EXTRA_RESTRICTED_TELEGRAM_ONLY, true);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent);
                } else {
                    startService(startIntent);
                }
                updateTileState(Tile.STATE_ACTIVE, "Подключение...");
            }
        }
    }

    private void launchMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34) { // Android 14+
                PendingIntent pi = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                );
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Throwable t) {
            try {
                startActivityAndCollapse(intent);
            } catch (Throwable t2) {
                AppLogger.e(TAG, "Cannot launch MainActivity: " + t2.getMessage(), t2);
            }
        }
    }

    private void updateTileUI() {
        VpnState state = GoblinVpnService.getCurrentState();
        if (state == VpnState.CONNECTED) {
            VpnServer server = GoblinVpnService.getCurrentServer();
            String subtitle;
            if (server != null && server.getCountry() != null) {
                subtitle = (server.getFlag() != null ? server.getFlag() + " " : "") + server.getCountry();
            } else {
                subtitle = "Подключено";
            }
            updateTileState(Tile.STATE_ACTIVE, subtitle);
        } else if (state == VpnState.CONNECTING) {
            updateTileState(Tile.STATE_ACTIVE, "Подключение...");
        } else {
            updateTileState(Tile.STATE_INACTIVE, "Отключено");
        }
    }

    private void updateTileState(int tileState, String subtitle) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(tileState);
        tile.setLabel("llazyrinVPN");
        try {
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_shield));
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(subtitle);
        }
        tile.updateTile();
    }

    private VpnServer resolveTargetServer() {
        try {
            boolean isCellular = WhitelistPoolManager.isRunningOnCellular(this);
            if (isCellular) {
                List<VpnServer> bypassPool = WhitelistPoolManager.getInstance(this).getMobileBypassServerPool(this);
                if (bypassPool != null && !bypassPool.isEmpty()) {
                    for (VpnServer s : bypassPool) {
                        if (s != null && s.getPingMs() >= 20 && s.isAlive() && !MainActivity.isKnownDeadHost(s.getHost())) return s;
                    }
                    for (VpnServer s : bypassPool) {
                        if (s != null && s.isAlive() && !MainActivity.isKnownDeadHost(s.getHost())) return s;
                    }
                    return bypassPool.get(0);
                }
            }

            List<VpnServer> emergency = EmergencyConfigStorage.loadEmergencyReserve(this);
            if (emergency != null && !emergency.isEmpty()) {
                List<VpnServer> prioritized = WhitelistPoolManager.getInstance(this).prioritizeServersForNetwork(this, emergency);
                if (prioritized != null && !prioritized.isEmpty()) {
                    return prioritized.get(0);
                }
                return emergency.get(0);
            }

            List<VpnServer> fallback = VpnSubscriptionClient.getBuiltinFallbackServers();
            if (fallback != null && !fallback.isEmpty()) {
                return fallback.get(0);
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "Error resolving target server: " + t.getMessage(), t);
        }
        return null;
    }
}
