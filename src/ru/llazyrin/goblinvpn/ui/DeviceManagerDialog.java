package ru.llazyrin.goblinvpn.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.net.SubscriptionManager;
import ru.llazyrin.goblinvpn.security.DeviceFingerprintHelper;

/**
 * DeviceManagerDialog — Управление 3-мя слотами устройств учетной записи Goblin VPN.
 * Оформлен в строгом фирменном стиле приложения (Obsidian / Emerald, 8dp сетка, Bottom Sheet).
 */
public class DeviceManagerDialog {
    private static Dialog activeDialog = null;

    public static void show(final Activity activity) {
        show(activity, null);
    }

    public static void show(final Activity activity, final Runnable onDismissCallback) {
        if (activity == null || activity.isFinishing()) return;

        if (activeDialog != null && activeDialog.isShowing()) {
            try {
                activeDialog.dismiss();
            } catch (Throwable ignored) {}
        }

        final Dialog dialog = new Dialog(activity, R.style.BottomSheetDialogTheme);
        activeDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_device_manager);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.65f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                window.getAttributes().setBlurBehindRadius(35);
            }
        }

        FrameLayout btnClose = dialog.findViewById(R.id.btnCloseDevices);
        final TextView tvHeader = dialog.findViewById(R.id.tvDeviceSlotsHeader);
        final TextView btnBind = dialog.findViewById(R.id.btnBindCurrentDevice);

        final TextView tvSlot1Title = dialog.findViewById(R.id.tvSlot1Title);
        final TextView tvSlot1Sub = dialog.findViewById(R.id.tvSlot1Subtitle);
        final TextView badgeSlot1 = dialog.findViewById(R.id.badgeSlot1);

        final TextView tvSlot2Title = dialog.findViewById(R.id.tvSlot2Title);
        final TextView tvSlot2Sub = dialog.findViewById(R.id.tvSlot2Subtitle);
        final TextView badgeSlot2 = dialog.findViewById(R.id.badgeSlot2);

        final TextView tvSlot3Title = dialog.findViewById(R.id.tvSlot3Title);
        final TextView tvSlot3Sub = dialog.findViewById(R.id.tvSlot3Subtitle);
        final TextView badgeSlot3 = dialog.findViewById(R.id.badgeSlot3);

        View.OnClickListener dismissAction = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                dialog.dismiss();
            }
        };

        if (btnClose != null) {
            btnClose.setOnClickListener(dismissAction);
        }

        final String myDeviceId = DeviceFingerprintHelper.getDeviceFingerprint(activity);
        final String myDeviceModel = DeviceFingerprintHelper.getDeviceModel();

        // Загрузка актуального списка устройств
        final Runnable reloadDevices = new Runnable() {
            @Override
            public void run() {
                SubscriptionManager.fetchUserDevices(activity, new SubscriptionManager.DevicesCallback() {
                    @Override
                    public void onSuccess(int maxDevices, int usedSlots, List<SubscriptionManager.DeviceEntry> devices) {
                        if (!dialog.isShowing()) return;

                        if (tvHeader != null) {
                            tvHeader.setText("Занято " + usedSlots + " из " + maxDevices + " доступных слотов");
                        }

                        // Сброс слотов в состояние "Свободен"
                        resetSlot(tvSlot1Title, tvSlot1Sub, badgeSlot1, 1);
                        resetSlot(tvSlot2Title, tvSlot2Sub, badgeSlot2, 2);
                        resetSlot(tvSlot3Title, tvSlot3Sub, badgeSlot3, 3);

                        boolean currentDeviceBound = false;

                        if (devices != null) {
                            for (final SubscriptionManager.DeviceEntry dev : devices) {
                                boolean isThisDevice = myDeviceId.equalsIgnoreCase(dev.deviceId);
                                if (isThisDevice) currentDeviceBound = true;

                                if (dev.slotIndex == 1) {
                                    populateSlot(tvSlot1Title, tvSlot1Sub, badgeSlot1, dev, isThisDevice);
                                } else if (dev.slotIndex == 2) {
                                    populateSlot(tvSlot2Title, tvSlot2Sub, badgeSlot2, dev, isThisDevice);
                                } else if (dev.slotIndex == 3) {
                                    populateSlot(tvSlot3Title, tvSlot3Sub, badgeSlot3, dev, isThisDevice);
                                }
                            }
                        }

                        if (btnBind != null) {
                            if (currentDeviceBound) {
                                btnBind.setText("✓ Это устройство привязано");
                                btnBind.setEnabled(false);
                                btnBind.setAlpha(0.65f);
                            } else {
                                btnBind.setText("📱 Привязать это устройство (" + myDeviceModel + ")");
                                btnBind.setEnabled(true);
                                btnBind.setAlpha(1.0f);
                            }
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        if (dialog.isShowing() && tvHeader != null) {
                            tvHeader.setText("Ошибка загрузки: " + errorMessage);
                        }
                    }
                });
            }
        };

        // Запустить первичную загрузку
        reloadDevices.run();

        if (btnBind != null) {
            btnBind.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    btnBind.setEnabled(false);
                    btnBind.setText("Привязка к слоту…");

                    SubscriptionManager.bindCurrentDevice(activity, new SubscriptionManager.ActionCallback() {
                        @Override
                        public void onSuccess(String message) {
                            Toast.makeText(activity, "Устройство успешно привязано!", Toast.LENGTH_SHORT).show();
                            reloadDevices.run();
                        }

                        @Override
                        public void onError(String errorMessage) {
                            Toast.makeText(activity, "Лимит устройств: " + errorMessage, Toast.LENGTH_LONG).show();
                            btnBind.setEnabled(true);
                            btnBind.setText("📱 Привязать это устройство");
                        }
                    });
                }
            });
        }

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (onDismissCallback != null) {
                    onDismissCallback.run();
                }
            }
        });

        dialog.show();

        // Применяем адаптивную ширину окна ПОСЛЕ show() во избежание схлопывания на OneUI / HyperOS
        if (dialog.getWindow() != null) {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            float widthDp = dm.widthPixels / dm.density;
            int targetWidth = (widthDp >= 600f) ? (int) (560 * dm.density + 0.5f) : ViewGroup.LayoutParams.MATCH_PARENT;
            dialog.getWindow().setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void resetSlot(TextView title, TextView subtitle, TextView badge, int slotNum) {
        if (title != null) title.setText("Слот #" + slotNum + ": Свободен");
        if (subtitle != null) subtitle.setText("Доступен для подключения второго смартфона или ПК");
        if (badge != null) {
            badge.setText("СВОБОДЕН");
            badge.setTextColor(Color.parseColor("#94A3B8"));
            badge.setBackgroundResource(R.drawable.bg_m3_badge_online);
        }
    }

    private static void populateSlot(TextView title, TextView subtitle, TextView badge,
                                     SubscriptionManager.DeviceEntry dev, boolean isThisDevice) {
        if (title != null) {
            String name = dev.deviceModel != null && !dev.deviceModel.isEmpty() ? dev.deviceModel : "Устройство";
            if (isThisDevice) name += " (Это устройство)";
            title.setText("Слот #" + dev.slotIndex + ": " + name);
        }
        if (subtitle != null) {
            String date = dev.lastSeen != null && dev.lastSeen.length() >= 10 ? dev.lastSeen.substring(0, 10) : "Недавно";
            subtitle.setText("Активность: " + date + " • DRM ID: " + (dev.deviceId.length() > 8 ? dev.deviceId.substring(0, 8) + "…" : dev.deviceId));
        }
        if (badge != null) {
            badge.setText("АКТИВЕН");
            badge.setTextColor(Color.parseColor("#34D399"));
            badge.setBackgroundResource(R.drawable.bg_m3_badge_emerald);
        }
    }
}
