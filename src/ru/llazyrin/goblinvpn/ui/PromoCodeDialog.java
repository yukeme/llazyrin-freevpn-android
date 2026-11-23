package ru.llazyrin.goblinvpn.ui;

import android.app.Activity;
import android.app.Dialog;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.net.SubscriptionManager;

/**
 * PromoCodeDialog — Диалог ввода и активации промокодов на скидки и бесплатный доступ.
 * Оформлен в строгом фирменном стиле приложения (Obsidian / Emerald, 8dp сетка, Bottom Sheet).
 */
public class PromoCodeDialog {
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
        dialog.setContentView(R.layout.dialog_promo_code);

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

        FrameLayout btnClose = dialog.findViewById(R.id.btnClosePromo);
        final EditText etCode = dialog.findViewById(R.id.etPromoCodeInput);
        final TextView tvFeedback = dialog.findViewById(R.id.tvPromoFeedback);
        final TextView btnActivate = dialog.findViewById(R.id.btnActivatePromo);
        View cardTgHint = dialog.findViewById(R.id.cardTelegramPromoHint);

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

        if (cardTgHint != null) {
            cardTgHint.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/llazyrin"));
                        activity.startActivity(intent);
                    } catch (Throwable t) {
                        Toast.makeText(activity, "Канал в Telegram: @llazyrin", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (btnActivate != null && etCode != null) {
            btnActivate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    final String raw = etCode.getText().toString().trim();
                    if (raw.isEmpty()) {
                        tvFeedback.setVisibility(View.VISIBLE);
                        tvFeedback.setTextColor(Color.parseColor("#F87171"));
                        tvFeedback.setText("Пожалуйста, введите промокод");
                        return;
                    }

                    btnActivate.setEnabled(false);
                    btnActivate.setText("Проверка на сервере…");
                    tvFeedback.setVisibility(View.GONE);

                    SubscriptionManager.redeemPromoCode(activity, raw, new SubscriptionManager.PromoCallback() {
                        @Override
                        public void onSuccess(String message, int bonusDays, String expiresAt) {
                            if (dialog.isShowing()) {
                                btnActivate.setEnabled(true);
                                btnActivate.setText("✓ Активировано");
                                tvFeedback.setVisibility(View.VISIBLE);
                                tvFeedback.setTextColor(Color.parseColor("#34D399"));
                                tvFeedback.setText("🎉 " + message);
                                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                            }
                        }

                        @Override
                        public void onError(String errorMessage) {
                            if (dialog.isShowing()) {
                                btnActivate.setEnabled(true);
                                btnActivate.setText("⚡ Активировать промокод");
                                tvFeedback.setVisibility(View.VISIBLE);
                                tvFeedback.setTextColor(Color.parseColor("#F87171"));
                                tvFeedback.setText("⚠️ " + errorMessage);
                            }
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
}
