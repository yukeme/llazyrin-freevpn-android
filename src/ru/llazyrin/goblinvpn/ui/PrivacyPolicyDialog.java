package ru.llazyrin.goblinvpn.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import ru.llazyrin.goblinvpn.R;

/**
 * PrivacyPolicyDialog — Модальное окно "Конфиденциальность и правила" в стиле llazyrinVPN.
 * Включает матовое темное стекло, аппаратный блюр (Android 12+), карточки с No-Logs политикой,
 * криптографией VLESS Reality, правилами сервиса и ссылкой на поддержку @llazyrin.
 */
public class PrivacyPolicyDialog extends Dialog {

    private final Context context;

    public PrivacyPolicyDialog(Context context) {
        super(context);
        this.context = context;
        init();
    }

    public PrivacyPolicyDialog(Context context, int themeResId) {
        super(context, themeResId);
        this.context = context;
        init();
    }

    private void init() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_privacy_policy);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.60f);

            // Android 12+ (API 31+) hardware-accelerated blur effect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                window.getAttributes().setBlurBehindRadius(35);
            }

            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int targetHeight = (int) (dm.heightPixels * 0.82);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, targetHeight);
        }

        // Close Button (cross in top right)
        FrameLayout btnClose = findViewById(R.id.btnClosePrivacyDialog);
        if (btnClose != null) {
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    dismiss();
                }
            });
        }

        // Primary Button: Accept & Close
        FrameLayout btnAccept = findViewById(R.id.btnPrivacyAccept);
        if (btnAccept != null) {
            btnAccept.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    try {
                        ru.llazyrin.goblinvpn.model.NetworkPreferences.getInstance(context).setPrivacyPolicyAccepted(true);
                    } catch (Exception ignored) {}
                    dismiss();
                }
            });
        }

        // Secondary Button: Telegram Channel link
        FrameLayout btnTelegram = findViewById(R.id.btnPrivacyTelegram);
        if (btnTelegram != null) {
            btnTelegram.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    openTelegramChannel();
                }
            });
        }
    }

    private void openTelegramChannel() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/llazyrin"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "Не удалось открыть Telegram", Toast.LENGTH_SHORT).show();
        }
    }

    public static PrivacyPolicyDialog show(Activity activity) {
        return show(activity, null);
    }

    public static PrivacyPolicyDialog show(Activity activity, DialogInterface.OnDismissListener dismissListener) {
        if (activity == null || activity.isFinishing()) return null;
        PrivacyPolicyDialog dialog = new PrivacyPolicyDialog(activity);
        if (dismissListener != null) {
            dialog.setOnDismissListener(dismissListener);
        }
        dialog.show();
        return dialog;
    }
}
