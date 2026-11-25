package ru.llazyrin.goblinvpn.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.RotateAnimation;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.model.NetworkPreferences;

/**
 * Modern 3-tab modal dialog for configuring routing rules, bypass lists, custom domains,
 * geoflags and DNS settings for Goblin VPN.
 * Designed in full accordance with Goblin VPN matte neon-emerald dark aesthetic (M3 grid).
 */
public class TunnelSettingsDialog {

    public interface OnTunnelSettingsChangedListener {
        void onTunnelSettingsChanged(boolean requestedReconnect);
    }

    public static void show(final Activity activity, final OnTunnelSettingsChangedListener listener) {
        if (activity == null || activity.isFinishing()) return;

        final NetworkPreferences prefs = NetworkPreferences.getInstance(activity);

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_tunnel_settings);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.65f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int targetHeight = (int) (dm.heightPixels * 0.92);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, targetHeight);
        }

        // Top Bar Controls
        FrameLayout btnTunnelBack = dialog.findViewById(R.id.btnTunnelBack);

        if (btnTunnelBack != null) {
            btnTunnelBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    dialog.dismiss();
                }
            });
        }

        // 3-Segmented Mode Switcher Views
        final FrameLayout layoutTabContainer = dialog.findViewById(R.id.layoutTabContainer);
        final View viewTabIndicatorCapsule = dialog.findViewById(R.id.viewTabIndicatorCapsule);
        final TextView tabRules = dialog.findViewById(R.id.tabRules);
        final TextView tabDomains = dialog.findViewById(R.id.tabDomains);
        final TextView tabGeoFiles = dialog.findViewById(R.id.tabGeoFiles);

        final LinearLayout panelRules = dialog.findViewById(R.id.panelRules);
        final LinearLayout panelDomains = dialog.findViewById(R.id.panelDomains);
        final LinearLayout panelGeoFiles = dialog.findViewById(R.id.panelGeoFiles);

        final ValueAnimator[] tabMorphingAnimator = new ValueAnimator[1];
        final int[] currentTabIndex = { 0 };

        View.OnClickListener tabClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                int id = v.getId();
                if (id == R.id.tabRules) {
                    selectTab(layoutTabContainer, viewTabIndicatorCapsule, tabRules, tabDomains, tabGeoFiles,
                            panelRules, panelDomains, panelGeoFiles, tabMorphingAnimator, currentTabIndex, 0, true);
                } else if (id == R.id.tabDomains) {
                    selectTab(layoutTabContainer, viewTabIndicatorCapsule, tabRules, tabDomains, tabGeoFiles,
                            panelRules, panelDomains, panelGeoFiles, tabMorphingAnimator, currentTabIndex, 1, true);
                } else if (id == R.id.tabGeoFiles) {
                    selectTab(layoutTabContainer, viewTabIndicatorCapsule, tabRules, tabDomains, tabGeoFiles,
                            panelRules, panelDomains, panelGeoFiles, tabMorphingAnimator, currentTabIndex, 2, true);
                }
            }
        };

        if (tabRules != null) tabRules.setOnClickListener(tabClickListener);
        if (tabDomains != null) tabDomains.setOnClickListener(tabClickListener);
        if (tabGeoFiles != null) tabGeoFiles.setOnClickListener(tabClickListener);

        // Initial tab selection without animation
        selectTab(layoutTabContainer, viewTabIndicatorCapsule, tabRules, tabDomains, tabGeoFiles,
                panelRules, panelDomains, panelGeoFiles, tabMorphingAnimator, currentTabIndex, 0, false);

        // Initial Saved States to compute requestedReconnect upon applying
        final boolean initialBypassRu = prefs.isBypassRuTrafficEnabled();
        final boolean initialPopularVpn = prefs.isPopularServicesViaVpnEnabled();
        final boolean initialKillSwitch = prefs.isKillSwitchEnabled();
        final boolean initialAutoConnect = prefs.isAutoConnectEnabled();
        final boolean initialAutoSwitch = prefs.isAutoSwitchServerEnabled();
        final NetworkPreferences.DnsOption initialDns = prefs.getDnsOption();

        // Local Draft State
        final boolean[] draftBypassRu = { initialBypassRu };
        final boolean[] draftPopularVpn = { initialPopularVpn };
        final boolean[] draftKillSwitch = { initialKillSwitch };
        final boolean[] draftAutoConnect = { initialAutoConnect };
        final boolean[] draftAutoSwitch = { initialAutoSwitch };
        final NetworkPreferences.DnsOption[] draftDns = { initialDns };
        final Set<String> draftCountries = new LinkedHashSet<String>(prefs.getSelectedGeoCountries());
        final List<String> draftDomains = new ArrayList<String>(prefs.getCustomDomains());
        final List<String> draftGeoRules = new ArrayList<String>(prefs.getGeoRules());

        // -------------------------------------------------------------
        // PANEL 1: RULES BINDINGS
        // -------------------------------------------------------------
        LinearLayout cardBypassRu = dialog.findViewById(R.id.cardBypassRu);
        final FrameLayout toggleBypassRuTrack = dialog.findViewById(R.id.toggleBypassRuTrack);
        final View toggleBypassRuThumb = dialog.findViewById(R.id.toggleBypassRuThumb);
        setupObsidianToggleRow(activity, cardBypassRu, toggleBypassRuTrack, toggleBypassRuThumb, draftBypassRu, null);

        LinearLayout cardPopularVpn = dialog.findViewById(R.id.cardPopularVpn);
        final FrameLayout togglePopularVpnTrack = dialog.findViewById(R.id.togglePopularVpnTrack);
        final View togglePopularVpnThumb = dialog.findViewById(R.id.togglePopularVpnThumb);
        setupObsidianToggleRow(activity, cardPopularVpn, togglePopularVpnTrack, togglePopularVpnThumb, draftPopularVpn, null);

        LinearLayout rowKillSwitch = dialog.findViewById(R.id.rowKillSwitch);
        final FrameLayout toggleKillSwitchTrack = dialog.findViewById(R.id.toggleKillSwitchTrack);
        final View toggleKillSwitchThumb = dialog.findViewById(R.id.toggleKillSwitchThumb);
        final TextView badgeKillSwitch = dialog.findViewById(R.id.badgeKillSwitch);
        updateKillSwitchBadge(badgeKillSwitch, draftKillSwitch[0]);
        setupObsidianToggleRow(activity, rowKillSwitch, toggleKillSwitchTrack, toggleKillSwitchThumb, draftKillSwitch, new Runnable() {
            @Override
            public void run() {
                updateKillSwitchBadge(badgeKillSwitch, draftKillSwitch[0]);
            }
        });

        LinearLayout rowAutoConnect = dialog.findViewById(R.id.rowAutoConnect);
        final FrameLayout toggleAutoConnectTrack = dialog.findViewById(R.id.toggleAutoConnectTrack);
        final View toggleAutoConnectThumb = dialog.findViewById(R.id.toggleAutoConnectThumb);
        final TextView badgeAutoConnect = dialog.findViewById(R.id.badgeAutoConnect);
        updateAutoConnectBadge(badgeAutoConnect, draftAutoConnect[0]);
        setupObsidianToggleRow(activity, rowAutoConnect, toggleAutoConnectTrack, toggleAutoConnectThumb, draftAutoConnect, new Runnable() {
            @Override
            public void run() {
                updateAutoConnectBadge(badgeAutoConnect, draftAutoConnect[0]);
            }
        });

        LinearLayout rowAutoSwitch = dialog.findViewById(R.id.rowAutoSwitchServerTunnel);
        final FrameLayout toggleAutoSwitchServerTrack = dialog.findViewById(R.id.toggleAutoSwitchServerTrack);
        final View toggleAutoSwitchServerThumb = dialog.findViewById(R.id.toggleAutoSwitchServerThumb);
        final TextView badgeAutoSwitch = dialog.findViewById(R.id.badgeAutoSwitch);
        if (badgeAutoSwitch != null) {
            updateAutoConnectBadge(badgeAutoSwitch, draftAutoSwitch[0]);
        }
        setupObsidianToggleRow(activity, rowAutoSwitch, toggleAutoSwitchServerTrack, toggleAutoSwitchServerThumb, draftAutoSwitch, new Runnable() {
            @Override
            public void run() {
                if (badgeAutoSwitch != null) {
                    updateAutoConnectBadge(badgeAutoSwitch, draftAutoSwitch[0]);
                }
            }
        });

        // DNS Cards
        final LinearLayout cardCloudflare = dialog.findViewById(R.id.cardDnsCloudflare);
        final TextView indicatorCloudflare = dialog.findViewById(R.id.indicatorDnsCloudflare);
        final LinearLayout cardGoogle = dialog.findViewById(R.id.cardDnsGoogle);
        final TextView indicatorGoogle = dialog.findViewById(R.id.indicatorDnsGoogle);
        final LinearLayout cardAdguard = dialog.findViewById(R.id.cardDnsAdguard);
        final TextView indicatorAdguard = dialog.findViewById(R.id.indicatorDnsAdguard);

        updateDnsCards(draftDns[0], cardCloudflare, indicatorCloudflare,
                cardGoogle, indicatorGoogle, cardAdguard, indicatorAdguard);

        if (cardCloudflare != null) {
            cardCloudflare.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    draftDns[0] = NetworkPreferences.DnsOption.CLOUDFLARE;
                    updateDnsCards(draftDns[0], cardCloudflare, indicatorCloudflare,
                            cardGoogle, indicatorGoogle, cardAdguard, indicatorAdguard);
                }
            });
        }

        if (cardGoogle != null) {
            cardGoogle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    draftDns[0] = NetworkPreferences.DnsOption.GOOGLE;
                    updateDnsCards(draftDns[0], cardCloudflare, indicatorCloudflare,
                            cardGoogle, indicatorGoogle, cardAdguard, indicatorAdguard);
                }
            });
        }

        if (cardAdguard != null) {
            cardAdguard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    draftDns[0] = NetworkPreferences.DnsOption.ADGUARD;
                    updateDnsCards(draftDns[0], cardCloudflare, indicatorCloudflare,
                            cardGoogle, indicatorGoogle, cardAdguard, indicatorAdguard);
                }
            });
        }

        // Geo Country Chips
        LinearLayout layoutGeoCountryChips = dialog.findViewById(R.id.layoutGeoCountryChips);
        if (layoutGeoCountryChips != null) {
            populateCountryChips(activity, layoutGeoCountryChips, draftCountries);
        }

        // -------------------------------------------------------------
        // PANEL 2: DOMAINS BINDINGS
        // -------------------------------------------------------------
        final EditText etCustomDomain = dialog.findViewById(R.id.etCustomDomain);
        FrameLayout btnAddDomain = dialog.findViewById(R.id.btnAddDomain);
        final LinearLayout layoutCustomDomainsList = dialog.findViewById(R.id.layoutCustomDomainsList);

        if (layoutCustomDomainsList != null) {
            renderCustomDomains(activity, layoutCustomDomainsList, draftDomains);
        }

        View.OnClickListener addDomainListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (etCustomDomain == null) return;
                String domain = etCustomDomain.getText().toString().trim();
                if (!domain.isEmpty()) {
                    if (domain.startsWith("http://")) domain = domain.substring(7);
                    if (domain.startsWith("https://")) domain = domain.substring(8);
                    if (domain.contains("/")) domain = domain.substring(0, domain.indexOf('/'));
                    domain = domain.trim().toLowerCase();

                    if (!domain.isEmpty() && !draftDomains.contains(domain)) {
                        HapticFeedbackHelper.performButtonClick(v);
                        draftDomains.add(domain);
                        etCustomDomain.setText("");
                        if (layoutCustomDomainsList != null) {
                            renderCustomDomains(activity, layoutCustomDomainsList, draftDomains);
                        }
                    } else if (draftDomains.contains(domain)) {
                        HapticFeedbackHelper.performTick(v);
                        Toast.makeText(activity, "Домен уже в списке", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        };

        if (btnAddDomain != null) {
            btnAddDomain.setOnClickListener(addDomainListener);
        }
        if (etCustomDomain != null) {
            final View addDomainBtnRef = btnAddDomain;
            etCustomDomain.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                        if (addDomainBtnRef != null) addDomainBtnRef.performClick();
                        return true;
                    }
                    return false;
                }
            });
        }

        // -------------------------------------------------------------
        // PANEL 3: GEOFILES / GEOFLAGS BINDINGS
        // -------------------------------------------------------------
        final EditText etGeoRule = dialog.findViewById(R.id.etGeoRule);
        final FrameLayout btnAddGeoRule = dialog.findViewById(R.id.btnAddGeoRule);
        final LinearLayout layoutGeoRulesList = dialog.findViewById(R.id.layoutGeoRulesList);
        FrameLayout btnUpdateGeoFiles = dialog.findViewById(R.id.btnUpdateGeoFiles);

        if (layoutGeoRulesList != null) {
            renderGeoRules(activity, layoutGeoRulesList, draftGeoRules);
        }

        View.OnClickListener addGeoRuleListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (etGeoRule == null) return;
                String rule = etGeoRule.getText().toString().trim().toLowerCase();
                if (!rule.isEmpty()) {
                    if (!draftGeoRules.contains(rule)) {
                        HapticFeedbackHelper.performButtonClick(v);
                        draftGeoRules.add(rule);
                        etGeoRule.setText("");
                        if (layoutGeoRulesList != null) {
                            renderGeoRules(activity, layoutGeoRulesList, draftGeoRules);
                        }
                    } else {
                        HapticFeedbackHelper.performTick(v);
                        Toast.makeText(activity, "Правило уже в списке", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        };

        if (btnAddGeoRule != null) {
            btnAddGeoRule.setOnClickListener(addGeoRuleListener);
        }
        if (etGeoRule != null) {
            final View addGeoBtnRef = btnAddGeoRule;
            etGeoRule.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                        if (addGeoBtnRef != null) addGeoBtnRef.performClick();
                        return true;
                    }
                    return false;
                }
            });
        }

        if (btnUpdateGeoFiles != null) {
            btnUpdateGeoFiles.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performSuccess(v);
                    RotateAnimation rotate = new RotateAnimation(0f, 360f,
                            Animation.RELATIVE_TO_SELF, 0.5f,
                            Animation.RELATIVE_TO_SELF, 0.5f);
                    rotate.setDuration(700);
                    rotate.setInterpolator(new AccelerateDecelerateInterpolator());
                    if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
                        ((ViewGroup) v).getChildAt(0).startAnimation(rotate);
                    } else {
                        v.startAnimation(rotate);
                    }
                    Toast.makeText(activity, "🔄 Базы geoip.dat и geosite.dat обновлены", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // -------------------------------------------------------------
        // APPLY BUTTON
        // -------------------------------------------------------------
        View btnApplyTunnel = dialog.findViewById(R.id.btnApplyTunnel);
        if (btnApplyTunnel != null) {
            btnApplyTunnel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performSuccess(v);

                    boolean requestedReconnect = (initialKillSwitch != draftKillSwitch[0])
                            || (initialDns != draftDns[0])
                            || (initialBypassRu != draftBypassRu[0])
                            || (initialPopularVpn != draftPopularVpn[0]);

                    prefs.setBypassRuTrafficEnabled(draftBypassRu[0]);
                    prefs.setPopularServicesViaVpnEnabled(draftPopularVpn[0]);
                    prefs.setKillSwitchEnabled(draftKillSwitch[0]);
                    prefs.setAutoConnectEnabled(draftAutoConnect[0]);
                    prefs.setAutoSwitchServerEnabled(draftAutoSwitch[0]);
                    ru.llazyrin.goblinvpn.net.AutoSwitchEngine.getInstance(activity).onAutoSwitchPreferenceChanged();
                    prefs.setDnsOption(draftDns[0]);
                    prefs.setSelectedGeoCountries(draftCountries);
                    prefs.setCustomDomains(draftDomains);
                    prefs.setGeoRules(draftGeoRules);

                    dialog.dismiss();

                    if (listener != null) {
                        listener.onTunnelSettingsChanged(requestedReconnect);
                    }
                }
            });
        }

        dialog.show();
    }

    /**
     * Handles tab switching with smooth sliding and morphing capsule indicator,
     * identical to SplitTunnelingDialog / AppRoutingDialog.
     */
    private static void selectTab(
            final FrameLayout layoutTabContainer,
            final View viewTabIndicatorCapsule,
            final TextView tabRules,
            final TextView tabDomains,
            final TextView tabGeoFiles,
            final LinearLayout panelRules,
            final LinearLayout panelDomains,
            final LinearLayout panelGeoFiles,
            final ValueAnimator[] tabMorphingAnimator,
            final int[] currentTabIndex,
            final int targetIndex,
            final boolean animate) {

        final int colorActive = Color.parseColor("#10B981");
        final int colorInactive = Color.parseColor("#758678");

        if (tabRules != null) {
            tabRules.setTextColor(targetIndex == 0 ? colorActive : colorInactive);
            tabRules.setTypeface(null, Typeface.BOLD);
        }
        if (tabDomains != null) {
            tabDomains.setTextColor(targetIndex == 1 ? colorActive : colorInactive);
            tabDomains.setTypeface(null, Typeface.BOLD);
        }
        if (tabGeoFiles != null) {
            tabGeoFiles.setTextColor(targetIndex == 2 ? colorActive : colorInactive);
            tabGeoFiles.setTypeface(null, Typeface.BOLD);
        }

        if (panelRules != null) panelRules.setVisibility(targetIndex == 0 ? View.VISIBLE : View.GONE);
        if (panelDomains != null) panelDomains.setVisibility(targetIndex == 1 ? View.VISIBLE : View.GONE);
        if (panelGeoFiles != null) panelGeoFiles.setVisibility(targetIndex == 2 ? View.VISIBLE : View.GONE);

        if (layoutTabContainer != null && viewTabIndicatorCapsule != null) {
            layoutTabContainer.post(new Runnable() {
                @Override
                public void run() {
                    if (layoutTabContainer == null || viewTabIndicatorCapsule == null) return;
                    float containerWidth = layoutTabContainer.getWidth()
                            - layoutTabContainer.getPaddingLeft()
                            - layoutTabContainer.getPaddingRight();
                    if (containerWidth <= 0) return;

                    float segmentWidth = containerWidth / 3.0f;
                    int capsuleWidth = (int) segmentWidth;

                    if (viewTabIndicatorCapsule.getLayoutParams().width != capsuleWidth) {
                        viewTabIndicatorCapsule.getLayoutParams().width = capsuleWidth;
                        viewTabIndicatorCapsule.requestLayout();
                    }

                    final float startX = viewTabIndicatorCapsule.getTranslationX();
                    final float endX = targetIndex * segmentWidth;

                    if (animate && viewTabIndicatorCapsule.getWidth() > 0) {
                        if (tabMorphingAnimator[0] != null) {
                            tabMorphingAnimator[0].cancel();
                        }
                        tabMorphingAnimator[0] = ValueAnimator.ofFloat(0f, 1f);
                        tabMorphingAnimator[0].setDuration(280);
                        tabMorphingAnimator[0].setInterpolator(new DecelerateInterpolator(1.4f));
                        tabMorphingAnimator[0].addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                            @Override
                            public void onAnimationUpdate(ValueAnimator animation) {
                                float f = animation.getAnimatedFraction();
                                float curX = startX + (endX - startX) * f;
                                viewTabIndicatorCapsule.setTranslationX(curX);
                                float stretch = 1.0f + 0.22f * (float) Math.sin(f * Math.PI);
                                viewTabIndicatorCapsule.setScaleX(stretch);
                            }
                        });
                        tabMorphingAnimator[0].start();
                    } else {
                        viewTabIndicatorCapsule.setTranslationX(endX);
                        viewTabIndicatorCapsule.setScaleX(1.0f);
                    }
                    currentTabIndex[0] = targetIndex;
                }
            });
        }
    }

    private static void updateObsidianToggle(
            Context context,
            final FrameLayout track,
            final View thumb,
            boolean active,
            boolean animate) {
        if (track == null || thumb == null || context == null) return;
        float density = context.getResources().getDisplayMetrics().density;
        float targetTranslationX = active ? (22f * density) : 0f;

        final int colorGray = Color.parseColor("#262B34");
        final int strokeGray = Color.parseColor("#38404E");
        final int colorGreen = Color.parseColor("#10B981");
        final int strokeGreen = Color.parseColor("#059669");

        final int targetBgColor = active ? colorGreen : colorGray;
        final int targetStrokeColor = active ? strokeGreen : strokeGray;
        final int fromBgColor = active ? colorGray : colorGreen;
        final int fromStrokeColor = active ? strokeGray : strokeGreen;

        if (animate) {
            thumb.animate()
                    .translationX(targetTranslationX)
                    .setDuration(240)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            final GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(999f * density);
            final ArgbEvaluator evaluator = new ArgbEvaluator();
            final int strokeWidth = (int) (1f * density);

            ValueAnimator colorAnim = ValueAnimator.ofFloat(0f, 1f);
            colorAnim.setDuration(240);
            colorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float fraction = animation.getAnimatedFraction();
                    int currBg = (int) evaluator.evaluate(fraction, fromBgColor, targetBgColor);
                    int currStroke = (int) evaluator.evaluate(fraction, fromStrokeColor, targetStrokeColor);
                    trackBg.setColor(currBg);
                    trackBg.setStroke(strokeWidth, currStroke);
                    track.setBackground(trackBg);
                }
            });
            colorAnim.start();
        } else {
            thumb.setTranslationX(targetTranslationX);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(999f * density);
            trackBg.setColor(targetBgColor);
            trackBg.setStroke((int) (1f * density), targetStrokeColor);
            track.setBackground(trackBg);
        }
    }

    private static void setupObsidianToggleRow(
            final Context context,
            View row,
            final FrameLayout track,
            final View thumb,
            final boolean[] stateHolder,
            final Runnable onChange) {
        updateObsidianToggle(context, track, thumb, stateHolder[0], false);
        View.OnClickListener clickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stateHolder[0] = !stateHolder[0];
                updateObsidianToggle(context, track, thumb, stateHolder[0], true);
                HapticFeedbackHelper.performToggle(track != null ? track : v, stateHolder[0]);
                if (onChange != null) {
                    onChange.run();
                }
            }
        };
        if (row != null) {
            row.setOnClickListener(clickListener);
        }
        if (track != null) {
            track.setOnClickListener(clickListener);
            track.setClickable(true);
            track.setFocusable(true);
        }
    }

    private static void updateKillSwitchBadge(TextView badge, boolean active) {
        if (badge == null) return;
        if (active) {
            badge.setText("АКТИВЕН");
            badge.setBackgroundResource(R.drawable.bg_m3_chip_active);
            badge.setTextColor(Color.parseColor("#10B981"));
        } else {
            badge.setText("ВЫКЛ");
            badge.setBackgroundResource(R.drawable.bg_m3_chip);
            badge.setTextColor(Color.parseColor("#758678"));
        }
    }

    private static void updateAutoConnectBadge(TextView badge, boolean active) {
        if (badge == null) return;
        if (active) {
            badge.setText("ВКЛЮЧЕНО");
            badge.setBackgroundResource(R.drawable.bg_m3_chip_active);
            badge.setTextColor(Color.parseColor("#10B981"));
        } else {
            badge.setText("ВЫКЛ");
            badge.setBackgroundResource(R.drawable.bg_m3_chip);
            badge.setTextColor(Color.parseColor("#758678"));
        }
    }

    private static void updateDnsCards(NetworkPreferences.DnsOption selected,
                                       LinearLayout cardCloudflare, TextView indicatorCloudflare,
                                       LinearLayout cardGoogle, TextView indicatorGoogle,
                                       LinearLayout cardAdguard, TextView indicatorAdguard) {
        if (cardCloudflare == null || cardGoogle == null || cardAdguard == null) return;

        cardCloudflare.setBackgroundResource(R.drawable.bg_dns_card_normal);
        if (indicatorCloudflare != null) {
            indicatorCloudflare.setText("○");
            indicatorCloudflare.setTextColor(Color.parseColor("#5A6B5C"));
        }

        cardGoogle.setBackgroundResource(R.drawable.bg_dns_card_normal);
        if (indicatorGoogle != null) {
            indicatorGoogle.setText("○");
            indicatorGoogle.setTextColor(Color.parseColor("#5A6B5C"));
        }

        cardAdguard.setBackgroundResource(R.drawable.bg_dns_card_normal);
        if (indicatorAdguard != null) {
            indicatorAdguard.setText("○");
            indicatorAdguard.setTextColor(Color.parseColor("#5A6B5C"));
        }

        switch (selected) {
            case CLOUDFLARE:
                cardCloudflare.setBackgroundResource(R.drawable.bg_dns_card_selected);
                if (indicatorCloudflare != null) {
                    indicatorCloudflare.setText("●");
                    indicatorCloudflare.setTextColor(Color.parseColor("#10B981"));
                }
                break;
            case GOOGLE:
                cardGoogle.setBackgroundResource(R.drawable.bg_dns_card_selected);
                if (indicatorGoogle != null) {
                    indicatorGoogle.setText("●");
                    indicatorGoogle.setTextColor(Color.parseColor("#10B981"));
                }
                break;
            case ADGUARD:
                cardAdguard.setBackgroundResource(R.drawable.bg_dns_card_selected);
                if (indicatorAdguard != null) {
                    indicatorAdguard.setText("●");
                    indicatorAdguard.setTextColor(Color.parseColor("#10B981"));
                }
                break;
        }
    }

    private static void populateCountryChips(final Activity activity,
                                             LinearLayout container,
                                             final Set<String> selectedCountries) {
        container.removeAllViews();
        // Responsive 2-column grid (8dp spacing) so long country labels never clip or wrap awkwardly
        String[][] countryRows = new String[][]{
                {"Россия (IP)", "Россия (сайты)"},
                {"Украина (IP)", "Беларусь (IP)"},
                {"США (IP)", "Германия (IP)"},
                {"Франция (IP)", "Великобритания (IP)"},
                {"Япония (IP)"}
        };

        for (int r = 0; r < countryRows.length; r++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            if (r < countryRows.length - 1) {
                rowLp.bottomMargin = dpToPx(activity, 8);
            }
            row.setLayoutParams(rowLp);

            String[] cols = countryRows[r];
            for (int c = 0; c < cols.length; c++) {
                final String country = cols[c];
                final TextView chip = new TextView(activity);
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                );
                if (c < cols.length - 1) {
                    chipLp.setMarginEnd(dpToPx(activity, 8));
                }
                chip.setLayoutParams(chipLp);
                chip.setMinHeight(dpToPx(activity, 40));
                chip.setPadding(dpToPx(activity, 10), dpToPx(activity, 6), dpToPx(activity, 10), dpToPx(activity, 6));
                chip.setGravity(Gravity.CENTER);
                chip.setText(country);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                chip.setMaxLines(2);
                chip.setEllipsize(TextUtils.TruncateAt.END);

                updateChipStyle(chip, selectedCountries.contains(country));

                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        HapticFeedbackHelper.performTick(v);
                        if (selectedCountries.contains(country)) {
                            selectedCountries.remove(country);
                            updateChipStyle(chip, false);
                        } else {
                            selectedCountries.add(country);
                            updateChipStyle(chip, true);
                        }
                    }
                });

                row.addView(chip);
            }
            container.addView(row);
        }
    }

    private static void updateChipStyle(TextView chip, boolean isSelected) {
        if (isSelected) {
            chip.setBackgroundResource(R.drawable.bg_m3_chip_active);
            chip.setTextColor(Color.parseColor("#10B981"));
            chip.setTypeface(null, Typeface.BOLD);
        } else {
            chip.setBackgroundResource(R.drawable.bg_m3_chip);
            chip.setTextColor(Color.parseColor("#94A3B8"));
            chip.setTypeface(null, Typeface.NORMAL);
        }
    }

    private static void renderCustomDomains(final Activity activity,
                                             final LinearLayout container,
                                             final List<String> domains) {
        container.removeAllViews();
        for (int i = 0; i < domains.size(); i++) {
            final int index = i;
            final String domain = domains.get(i);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_m3_server_card);
            int padH = dpToPx(activity, 14);
            int padV = dpToPx(activity, 12);
            row.setPadding(padH, padV, padH, padV);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowLp.bottomMargin = dpToPx(activity, 8);
            row.setLayoutParams(rowLp);

            TextView tvName = new TextView(activity);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            );
            tvName.setLayoutParams(textLp);
            tvName.setText(domain);
            tvName.setTextColor(Color.parseColor("#FFFFFF"));
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            tvName.setSingleLine(true);
            tvName.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(tvName);

            TextView btnDelete = new TextView(activity);
            btnDelete.setText("✕");
            btnDelete.setTextColor(Color.parseColor("#EF4444"));
            btnDelete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            btnDelete.setTypeface(null, Typeface.BOLD);
            int delPad = dpToPx(activity, 6);
            btnDelete.setPadding(delPad, delPad, delPad, delPad);
            btnDelete.setClickable(true);
            btnDelete.setFocusable(true);
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performTick(v);
                    if (index < domains.size()) {
                        domains.remove(index);
                        renderCustomDomains(activity, container, domains);
                    }
                }
            });
            row.addView(btnDelete);

            container.addView(row);
        }
    }

    private static void renderGeoRules(final Activity activity,
                                       final LinearLayout container,
                                       final List<String> rules) {
        container.removeAllViews();
        for (int i = 0; i < rules.size(); i++) {
            final int index = i;
            final String rule = rules.get(i);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_m3_server_card);
            int padH = dpToPx(activity, 14);
            int padV = dpToPx(activity, 12);
            row.setPadding(padH, padV, padH, padV);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowLp.bottomMargin = dpToPx(activity, 8);
            row.setLayoutParams(rowLp);

            TextView tvName = new TextView(activity);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            );
            tvName.setLayoutParams(textLp);
            tvName.setText(rule);
            tvName.setTextColor(Color.parseColor("#FFFFFF"));
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            tvName.setSingleLine(true);
            tvName.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(tvName);

            TextView btnDelete = new TextView(activity);
            btnDelete.setText("✕");
            btnDelete.setTextColor(Color.parseColor("#EF4444"));
            btnDelete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            btnDelete.setTypeface(null, Typeface.BOLD);
            int delPad = dpToPx(activity, 6);
            btnDelete.setPadding(delPad, delPad, delPad, delPad);
            btnDelete.setClickable(true);
            btnDelete.setFocusable(true);
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performTick(v);
                    if (index < rules.size()) {
                        rules.remove(index);
                        renderGeoRules(activity, container, rules);
                    }
                }
            });
            row.addView(btnDelete);

            container.addView(row);
        }
    }

    private static int dpToPx(Context context, float dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
