package ru.llazyrin.goblinvpn.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.service.AppLogger;

/**
 * VpnPermissionHelpDialog — Диалог помощи при ошибке или отклонении разрешения на VPN (VpnService.prepare).
 * 
 * Оформлен в строгом едином стиле приложения (8dp сетка, Obsidian/Emerald, Material Design 3 Bottom Sheet):
 * 1. Предупреждает пользователя об активном VPN (значок ключа 🔑 в строке состояния) или Always-on VPN.
 * 2. Предупреждает о плавающих пузырях чатов/фильтрах (защита Android от Tapjacking).
 * 3. Предоставляет кнопки для мгновенного перехода в системные настройки VPN и повтора запроса.
 */
public class VpnPermissionHelpDialog {
    private static final String TAG = "VpnPermissionHelpDialog";
    private static Dialog activeDialog = null;

    public static void show(final Activity activity, final Runnable onRetry) {
        if (activity == null || activity.isFinishing()) return;

        if (activeDialog != null && activeDialog.isShowing()) {
            try {
                activeDialog.dismiss();
            } catch (Throwable ignored) {}
        }

        final Dialog dialog = new Dialog(activity);
        activeDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_vpn_permission_help);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.70f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        // Bind views
        FrameLayout btnClose = dialog.findViewById(R.id.btnClosePermissionHelp);
        TextView btnOpenSettings = dialog.findViewById(R.id.btnOpenVpnSettings);
        TextView btnRetry = dialog.findViewById(R.id.btnRetryVpnPermission);
        TextView btnDismiss = dialog.findViewById(R.id.btnDismissPermissionHelp);

        View.OnClickListener dismissAction = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                dialog.dismiss();
            }
        };

        if (btnClose != null) btnClose.setOnClickListener(dismissAction);
        if (btnDismiss != null) btnDismiss.setOnClickListener(dismissAction);

        if (btnOpenSettings != null) {
            btnOpenSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    dialog.dismiss();
                    openSystemVpnSettings(activity);
                }
            });
        }

        if (btnRetry != null) {
            btnRetry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    dialog.dismiss();
                    // Задержка 350мс дает окну диалога полностью закрыться в WindowManager,
                    // исключая коллизию фокуса при вызове VpnService.prepare()
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!activity.isFinishing() && onRetry != null) {
                                onRetry.run();
                            }
                        }
                    }, 350L);
                }
            });
        }

        dialog.setCanceledOnTouchOutside(true);

        try {
            dialog.show();

            // Применяем адаптивную ширину окна ПОСЛЕ show(),
            // гарантируя, что OEM прошивки (MIUI/HyperOS/OneUI) не сожмут окно по горизонтали
            if (dialog.getWindow() != null) {
                DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                float widthDp = dm.widthPixels / dm.density;
                int targetWidth = ViewGroup.LayoutParams.MATCH_PARENT;
                if (widthDp >= 600f) {
                    targetWidth = (int) (560 * dm.density + 0.5f);
                }
                dialog.getWindow().setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            AppLogger.i(TAG, "VpnPermissionHelpDialog displayed successfully.");
        } catch (Throwable t) {
            AppLogger.e(TAG, "Failed to show VpnPermissionHelpDialog: " + t.getMessage(), t);
        }
    }

    public static void openSystemVpnSettings(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            Intent intent = new Intent("android.net.vpn.SETTINGS");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e1) {
            try {
                Intent fallback = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                fallback.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(fallback);
            } catch (Exception e2) {
                try {
                    Intent genericSettings = new Intent(Settings.ACTION_SETTINGS);
                    genericSettings.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(genericSettings);
                } catch (Exception e3) {
                    Toast.makeText(activity, "Не удалось открыть настройки VPN", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}
