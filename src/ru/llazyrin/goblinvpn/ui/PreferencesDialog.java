package ru.llazyrin.goblinvpn.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.model.NetworkPreferences;

public class PreferencesDialog {

    public interface OnPreferencesChangedListener {
        void onPreferencesChanged(boolean killSwitch, boolean autoConnect, NetworkPreferences.DnsOption dnsOption, boolean requestedReconnect);
    }

    public static void show(final Activity activity, final boolean isVpnActive, final OnPreferencesChangedListener listener) {
        if (activity == null || activity.isFinishing()) return;

        final NetworkPreferences prefs = NetworkPreferences.getInstance(activity);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        LayoutInflater inflater = LayoutInflater.from(activity);
        View dialogView = inflater.inflate(R.layout.dialog_preferences, null);
        builder.setView(dialogView);

        final AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }

        // Views
        View btnClose = dialogView.findViewById(R.id.btnCloseSettings);
        LinearLayout rowKillSwitch = dialogView.findViewById(R.id.rowKillSwitch);
        final Switch switchKillSwitch = dialogView.findViewById(R.id.switchKillSwitch);
        final TextView badgeKillSwitch = dialogView.findViewById(R.id.badgeKillSwitch);

        LinearLayout rowAutoConnect = dialogView.findViewById(R.id.rowAutoConnect);
        final Switch switchAutoConnect = dialogView.findViewById(R.id.switchAutoConnect);
        final TextView badgeAutoConnect = dialogView.findViewById(R.id.badgeAutoConnect);

        final LinearLayout cardCloudflare = dialogView.findViewById(R.id.cardDnsCloudflare);
        final TextView indicatorCloudflare = dialogView.findViewById(R.id.indicatorDnsCloudflare);

        final LinearLayout cardGoogle = dialogView.findViewById(R.id.cardDnsGoogle);
        final TextView indicatorGoogle = dialogView.findViewById(R.id.indicatorDnsGoogle);

        final LinearLayout cardAdguard = dialogView.findViewById(R.id.cardDnsAdguard);
        final TextView indicatorAdguard = dialogView.findViewById(R.id.indicatorDnsAdguard);

        TextView tvReconnectHint = dialogView.findViewById(R.id.tvReconnectHint);
        Button btnApplyReconnect = dialogView.findViewById(R.id.btnApplyReconnect);
        Button btnSaveAndClose = dialogView.findViewById(R.id.btnSaveAndClose);

        // 1. Initial State: Kill Switch
        boolean currentKillSwitch = prefs.isKillSwitchEnabled();
        switchKillSwitch.setChecked(currentKillSwitch);
        updateKillSwitchBadge(badgeKillSwitch, currentKillSwitch);

        switchKillSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setKillSwitchEnabled(isChecked);
                updateKillSwitchBadge(badgeKillSwitch, isChecked);
            }
        });

        rowKillSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchKillSwitch.toggle();
            }
        });

        // 2. Initial State: Auto-Connect
        boolean currentAutoConnect = prefs.isAutoConnectEnabled();
        switchAutoConnect.setChecked(currentAutoConnect);
        updateAutoConnectBadge(badgeAutoConnect, currentAutoConnect);

        switchAutoConnect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setAutoConnectEnabled(isChecked);
                updateAutoConnectBadge(badgeAutoConnect, isChecked);
            }
        });

        rowAutoConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchAutoConnect.toggle();
            }
        });

        // 3. Initial State: DNS Selector
        final NetworkPreferences.DnsOption[] selectedDns = { prefs.getDnsOption() };
        updateDnsCards(selectedDns[0], cardCloudflare, indicatorCloudflare,
                cardGoogle, indicatorGoogle, cardAdguard, indicatorAdguard);

        cardCloudflare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedDns[0] = NetworkPreferences.DnsOption.CLOUDFLARE;
                prefs.setDnsOption(selectedDns[0]);
                updateDnsCards(selectedDns[0], cardCloudflare, indicatorCloudflare,
                        cardGoogle, indicatorGoogle, cardAdguard, indicatorAdguard);
            }
        });

        cardGoogle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedDns[0] = NetworkPreferences.DnsOption.GOOGLE;
                prefs.setDnsOption(selectedDns[0]);
                updateDnsCards(selectedDns[0], cardCloudflare, indicatorCloudflare,
                        cardGoogle, indicatorGoogle, cardAdguard, indicatorAdguard);
            }
        });

        cardAdguard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedDns[0] = NetworkPreferences.DnsOption.ADGUARD;
                prefs.setDnsOption(selectedDns[0]);
                updateDnsCards(selectedDns[0], cardCloudflare, indicatorCloudflare,
                        cardGoogle, indicatorGoogle, cardAdguard, indicatorAdguard);
            }
        });

        // Reconnect Banner & Actions
        if (isVpnActive) {
            tvReconnectHint.setVisibility(View.VISIBLE);
            btnApplyReconnect.setVisibility(View.VISIBLE);
            btnSaveAndClose.setText(R.string.btn_close);
        } else {
            tvReconnectHint.setVisibility(View.GONE);
            btnApplyReconnect.setVisibility(View.GONE);
            btnSaveAndClose.setText("СОХРАНИТЬ И ЗАКРЫТЬ");
        }

        View.OnClickListener closeListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (listener != null) {
                    listener.onPreferencesChanged(
                            switchKillSwitch.isChecked(),
                            switchAutoConnect.isChecked(),
                            selectedDns[0],
                            false
                    );
                }
            }
        };

        btnClose.setOnClickListener(closeListener);
        btnSaveAndClose.setOnClickListener(closeListener);

        btnApplyReconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (listener != null) {
                    listener.onPreferencesChanged(
                            switchKillSwitch.isChecked(),
                            switchAutoConnect.isChecked(),
                            selectedDns[0],
                            true
                    );
                }
            }
        });

        dialog.show();

        // Adjust dialog width to match margins
        if (dialog.getWindow() != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(dialog.getWindow().getAttributes());
            lp.width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.94);
            dialog.getWindow().setAttributes(lp);
        }
    }

    private static void updateKillSwitchBadge(TextView badge, boolean active) {
        if (badge == null) return;
        if (active) {
            badge.setText("АКТИВЕН");
            badge.setBackgroundResource(R.drawable.bg_ping_badge);
            badge.setTextColor(Color.parseColor("#052A15"));
        } else {
            badge.setText("ВЫКЛ");
            badge.setBackgroundResource(R.drawable.bg_button_secondary);
            badge.setTextColor(Color.parseColor("#8B949E"));
        }
    }

    private static void updateAutoConnectBadge(TextView badge, boolean active) {
        if (badge == null) return;
        if (active) {
            badge.setText("ВКЛЮЧЕНО");
            badge.setBackgroundResource(R.drawable.bg_ping_badge);
            badge.setTextColor(Color.parseColor("#052A15"));
        } else {
            badge.setText("ВЫКЛ");
            badge.setBackgroundResource(R.drawable.bg_button_secondary);
            badge.setTextColor(Color.parseColor("#8B949E"));
        }
    }

    private static void updateDnsCards(NetworkPreferences.DnsOption selected,
                                       LinearLayout cardCloudflare, TextView indicatorCloudflare,
                                       LinearLayout cardGoogle, TextView indicatorGoogle,
                                       LinearLayout cardAdguard, TextView indicatorAdguard) {
        // Reset all
        cardCloudflare.setBackgroundResource(R.drawable.bg_dns_card_normal);
        indicatorCloudflare.setText("○");
        indicatorCloudflare.setTextColor(Color.parseColor("#586069"));

        cardGoogle.setBackgroundResource(R.drawable.bg_dns_card_normal);
        indicatorGoogle.setText("○");
        indicatorGoogle.setTextColor(Color.parseColor("#586069"));

        cardAdguard.setBackgroundResource(R.drawable.bg_dns_card_normal);
        indicatorAdguard.setText("○");
        indicatorAdguard.setTextColor(Color.parseColor("#586069"));

        // Highlight selected
        switch (selected) {
            case CLOUDFLARE:
                cardCloudflare.setBackgroundResource(R.drawable.bg_dns_card_selected);
                indicatorCloudflare.setText("●");
                indicatorCloudflare.setTextColor(Color.parseColor("#32D786"));
                break;
            case GOOGLE:
                cardGoogle.setBackgroundResource(R.drawable.bg_dns_card_selected);
                indicatorGoogle.setText("●");
                indicatorGoogle.setTextColor(Color.parseColor("#32D786"));
                break;
            case ADGUARD:
                cardAdguard.setBackgroundResource(R.drawable.bg_dns_card_selected);
                indicatorAdguard.setText("●");
                indicatorAdguard.setTextColor(Color.parseColor("#32D786"));
                break;
        }
    }
}
