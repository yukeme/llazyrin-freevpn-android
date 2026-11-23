package ru.llazyrin.goblinvpn.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.model.NetworkPreferences;
import ru.llazyrin.goblinvpn.model.NetworkPreferences.AppRoutingMode;
import ru.llazyrin.goblinvpn.service.AppLogger;
import ru.llazyrin.goblinvpn.service.GoblinVpnService;
import ru.llazyrin.goblinvpn.service.VpnState;

/**
 * AppRoutingDialog — Диалог управления раздельным туннелированием (Per-App Proxy).
 * Поддерживает 3 режима: ВЫКЛ (для всех), ВКЛ (только для выбранных), ОБХОД (выбранные мимо VPN).
 * Включает полное отображение всех программ устройства и фильтры по категориям (Google, Шпионские, Системные).
 */
public class AppRoutingDialog {

    private static final String TAG = "AppRouting";

    public interface OnRoutingChangedListener {
        void onRoutingChanged(AppRoutingMode newMode, Set<String> newSelectedPackages);
    }

    public enum CategoryFilter {
        ALL,
        SPYWARE,
        GOOGLE,
        SELECTED
    }

    public static class AppEntry {
        public final String name;
        public final String packageName;
        public final Drawable icon;
        public final boolean isSystem;
        public final boolean isGoogle;
        public final boolean isSpyware;
        public boolean isSelected;

        public AppEntry(String name, String packageName, Drawable icon, boolean isSystem,
                        boolean isGoogle, boolean isSpyware, boolean isSelected) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.isSystem = isSystem;
            this.isGoogle = isGoogle;
            this.isSpyware = isSpyware;
            this.isSelected = isSelected;
        }
    }

    private final Context context;
    private final NetworkPreferences prefs;
    private final OnRoutingChangedListener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Dialog dialog;
    private AppRoutingMode currentMode;
    private final Set<String> selectedPackages = new HashSet<>();
    private boolean hideSystemApps;
    private CategoryFilter activeCategoryFilter = CategoryFilter.ALL;

    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> displayedApps = new ArrayList<>();
    private AppsAdapter adapter;

    // Views
    private FrameLayout layoutModeContainer;
    private View viewModeIndicatorCapsule;
    private ValueAnimator modeMorphingAnimator;
    private int currentModeIndex = 0;
    private TextView btnModeAll;
    private TextView btnModeOnlySelected;
    private TextView btnModeBypassSelected;
    private TextView tvModeTitle;
    private TextView tvModeDescription;
    private TextView tvSelectedCount;

    // Filter Chips
    private TextView chipFilterAll;
    private TextView chipFilterSpyware;
    private TextView chipFilterGoogle;
    private TextView chipFilterSelected;

    // Tool Action Chips
    private TextView chipSelectVisible;
    private TextView chipClearAll;
    private TextView chipInvert;
    private TextView chipToggleSystem;

    // Search and List
    private EditText etSearchApp;
    private TextView btnClearSearch;
    private ProgressBar pbLoadingApps;
    private TextView tvEmptyList;
    private ListView lvInstalledApps;
    private TextView btnSaveAppRouting;

    public AppRoutingDialog(Context context, OnRoutingChangedListener listener) {
        this.context = context;
        this.prefs = NetworkPreferences.getInstance(context);
        this.listener = listener;
        this.currentMode = prefs.getAppRoutingMode();
        this.selectedPackages.addAll(prefs.getRoutedPackages());
        this.hideSystemApps = prefs.isHideSystemApps();
    }

    public void show() {
        dialog = new Dialog(context, R.style.AppTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_routing);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        bindViews(dialog);
        setupListeners();
        updateModeUI();
        updateSystemChipUI();
        updateCountBadge();
        updateFilterChipsUI();

        loadInstalledAppsAsync();

        dialog.show();
    }

    private void bindViews(Dialog d) {
        layoutModeContainer = d.findViewById(R.id.layoutModeContainer);
        viewModeIndicatorCapsule = d.findViewById(R.id.viewModeIndicatorCapsule);
        btnModeAll = d.findViewById(R.id.btnModeAll);
        btnModeOnlySelected = d.findViewById(R.id.btnModeOnlySelected);
        btnModeBypassSelected = d.findViewById(R.id.btnModeBypassSelected);
        tvModeTitle = d.findViewById(R.id.tvModeTitle);
        tvModeDescription = d.findViewById(R.id.tvModeDescription);
        tvSelectedCount = d.findViewById(R.id.tvSelectedCount);

        chipFilterAll = d.findViewById(R.id.chipFilterAll);
        chipFilterSpyware = d.findViewById(R.id.chipFilterSpyware);
        chipFilterGoogle = d.findViewById(R.id.chipFilterGoogle);
        chipFilterSelected = d.findViewById(R.id.chipFilterSelected);

        chipSelectVisible = d.findViewById(R.id.chipSelectVisible);
        chipClearAll = d.findViewById(R.id.chipClearAll);
        chipInvert = d.findViewById(R.id.chipInvert);
        chipToggleSystem = d.findViewById(R.id.chipToggleSystem);

        etSearchApp = d.findViewById(R.id.etSearchApp);
        btnClearSearch = d.findViewById(R.id.btnClearSearch);
        pbLoadingApps = d.findViewById(R.id.pbLoadingApps);
        tvEmptyList = d.findViewById(R.id.tvEmptyList);
        lvInstalledApps = d.findViewById(R.id.lvInstalledApps);
        btnSaveAppRouting = d.findViewById(R.id.btnSaveAppRouting);

        adapter = new AppsAdapter();
        lvInstalledApps.setAdapter(adapter);
    }

    private void setupListeners() {
        FrameLayout btnClose = dialog.findViewById(R.id.btnCloseAppRouting);
        if (btnClose != null) {
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }

        // Mode segments
        btnModeAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                setMode(AppRoutingMode.ALL);
            }
        });

        btnModeOnlySelected.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                setMode(AppRoutingMode.ONLY_SELECTED);
            }
        });

        btnModeBypassSelected.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performButtonClick(v);
                setMode(AppRoutingMode.BYPASS_SELECTED);
            }
        });

        // Category Filter Chips
        chipFilterAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeCategoryFilter = CategoryFilter.ALL;
                updateFilterChipsUI();
                filterApps();
            }
        });

        chipFilterSpyware.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeCategoryFilter = CategoryFilter.SPYWARE;
                updateFilterChipsUI();
                filterApps();
            }
        });

        chipFilterGoogle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeCategoryFilter = CategoryFilter.GOOGLE;
                updateFilterChipsUI();
                filterApps();
            }
        });

        chipFilterSelected.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeCategoryFilter = CategoryFilter.SELECTED;
                updateFilterChipsUI();
                filterApps();
            }
        });

        // Quick Tool Buttons
        chipSelectVisible.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAllVisible();
            }
        });

        chipClearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearAllSelections();
            }
        });

        chipInvert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                invertSelections();
            }
        });

        chipToggleSystem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSystemApps = !hideSystemApps;
                prefs.setHideSystemApps(hideSystemApps);
                updateSystemChipUI();
                filterApps();
            }
        });

        // Search text watcher
        etSearchApp.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (btnClearSearch != null) {
                    btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
                filterApps();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etSearchApp.setText("");
                }
            });
        }

        // Item click toggles selection
        lvInstalledApps.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < displayedApps.size()) {
                    AppEntry entry = displayedApps.get(position);
                    entry.isSelected = !entry.isSelected;
                    if (entry.isSelected) {
                        selectedPackages.add(entry.packageName);
                    } else {
                        selectedPackages.remove(entry.packageName);
                    }
                    adapter.notifyDataSetChanged();
                    updateCountBadge();
                    updateFilterChipsUI();
                }
            }
        });

        // Save & Apply button
        if (btnSaveAppRouting != null) {
            btnSaveAppRouting.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveAndApply();
                }
            });
        }
    }

    private void setMode(AppRoutingMode mode) {
        this.currentMode = mode;
        updateModeUI(true);
    }

    private void updateModeUI() {
        updateModeUI(false);
    }

    private void updateModeUI(final boolean animate) {
        final int colorActive = Color.parseColor("#10B981");
        final int colorInactive = Color.parseColor("#758678");

        btnModeAll.setTextColor(currentMode == AppRoutingMode.ALL ? colorActive : colorInactive);
        btnModeOnlySelected.setTextColor(currentMode == AppRoutingMode.ONLY_SELECTED ? colorActive : colorInactive);
        btnModeBypassSelected.setTextColor(currentMode == AppRoutingMode.BYPASS_SELECTED ? colorActive : colorInactive);

        final int targetIndex;
        switch (currentMode) {
            case ONLY_SELECTED:
                targetIndex = 1;
                break;
            case BYPASS_SELECTED:
                targetIndex = 2;
                break;
            case ALL:
            default:
                targetIndex = 0;
                break;
        }

        if (layoutModeContainer != null && viewModeIndicatorCapsule != null) {
            layoutModeContainer.post(new Runnable() {
                @Override
                public void run() {
                    if (layoutModeContainer == null || viewModeIndicatorCapsule == null) return;
                    float containerWidth = layoutModeContainer.getWidth()
                            - layoutModeContainer.getPaddingLeft()
                            - layoutModeContainer.getPaddingRight();
                    if (containerWidth <= 0) return;

                    float segmentWidth = containerWidth / 3.0f;
                    int capsuleWidth = (int) segmentWidth;

                    if (viewModeIndicatorCapsule.getLayoutParams().width != capsuleWidth) {
                        viewModeIndicatorCapsule.getLayoutParams().width = capsuleWidth;
                        viewModeIndicatorCapsule.requestLayout();
                    }

                    final float startX = viewModeIndicatorCapsule.getTranslationX();
                    final float endX = targetIndex * segmentWidth;

                    if (animate && viewModeIndicatorCapsule.getWidth() > 0) {
                        if (modeMorphingAnimator != null) {
                            modeMorphingAnimator.cancel();
                        }
                        modeMorphingAnimator = ValueAnimator.ofFloat(0f, 1f);
                        modeMorphingAnimator.setDuration(280);
                        modeMorphingAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
                        modeMorphingAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                            @Override
                            public void onAnimationUpdate(ValueAnimator animation) {
                                float f = animation.getAnimatedFraction();
                                float curX = startX + (endX - startX) * f;
                                viewModeIndicatorCapsule.setTranslationX(curX);
                                float stretch = 1.0f + 0.22f * (float) Math.sin(f * Math.PI);
                                viewModeIndicatorCapsule.setScaleX(stretch);
                            }
                        });
                        modeMorphingAnimator.start();
                    } else {
                        viewModeIndicatorCapsule.setTranslationX(endX);
                        viewModeIndicatorCapsule.setScaleX(1.0f);
                    }
                    currentModeIndex = targetIndex;
                }
            });
        }

        switch (currentMode) {
            case ALL:
                tvModeTitle.setText("Режим: Туннель для всех (ВЫКЛ)");
                tvModeDescription.setText("Туннель VPN применяется ко ВСЕМ программам без исключений.");
                break;
            case ONLY_SELECTED:
                tvModeTitle.setText("Режим: Только для выбранных (ВКЛ)");
                tvModeDescription.setText("Только отмеченные программы идут через VPN. Все остальные выходят напрямую.");
                break;
            case BYPASS_SELECTED:
                tvModeTitle.setText("Режим: Обход прокси (ОБХОД)");
                tvModeDescription.setText("Отмеченные программы обходят VPN напрямую (РФ банки, слежка, госуслуги). Все остальные под защитой.");
                break;
        }
    }

    private void updateSystemChipUI() {
        if (hideSystemApps) {
            chipToggleSystem.setBackgroundResource(R.drawable.bg_m3_chip_active);
            chipToggleSystem.setText("⚙️ Без системных");
            chipToggleSystem.setTextColor(Color.parseColor("#10B981"));
        } else {
            chipToggleSystem.setBackgroundResource(R.drawable.bg_m3_chip);
            chipToggleSystem.setText("⚙️ С системными");
            chipToggleSystem.setTextColor(Color.parseColor("#CBD5E1"));
        }
    }

    private void updateFilterChipsUI() {
        int activeBg = R.drawable.bg_m3_chip_active;
        int inactiveBg = R.drawable.bg_m3_chip;
        int activeColor = Color.parseColor("#10B981");
        int inactiveColor = Color.parseColor("#CBD5E1");

        // Chip All
        chipFilterAll.setBackgroundResource(activeCategoryFilter == CategoryFilter.ALL ? activeBg : inactiveBg);
        chipFilterAll.setTextColor(activeCategoryFilter == CategoryFilter.ALL ? activeColor : inactiveColor);
        chipFilterAll.setTypeface(null, activeCategoryFilter == CategoryFilter.ALL ? Typeface.BOLD : Typeface.NORMAL);

        // Chip Spyware
        chipFilterSpyware.setBackgroundResource(activeCategoryFilter == CategoryFilter.SPYWARE ? activeBg : inactiveBg);
        chipFilterSpyware.setTextColor(activeCategoryFilter == CategoryFilter.SPYWARE ? activeColor : inactiveColor);
        chipFilterSpyware.setTypeface(null, activeCategoryFilter == CategoryFilter.SPYWARE ? Typeface.BOLD : Typeface.NORMAL);

        // Chip Google
        chipFilterGoogle.setBackgroundResource(activeCategoryFilter == CategoryFilter.GOOGLE ? activeBg : inactiveBg);
        chipFilterGoogle.setTextColor(activeCategoryFilter == CategoryFilter.GOOGLE ? activeColor : inactiveColor);
        chipFilterGoogle.setTypeface(null, activeCategoryFilter == CategoryFilter.GOOGLE ? Typeface.BOLD : Typeface.NORMAL);

        // Chip Selected
        chipFilterSelected.setBackgroundResource(activeCategoryFilter == CategoryFilter.SELECTED ? activeBg : inactiveBg);
        chipFilterSelected.setTextColor(activeCategoryFilter == CategoryFilter.SELECTED ? activeColor : inactiveColor);
        chipFilterSelected.setTypeface(null, activeCategoryFilter == CategoryFilter.SELECTED ? Typeface.BOLD : Typeface.NORMAL);
        chipFilterSelected.setText("★ Выбранные (" + selectedPackages.size() + ")");
    }

    private void updateCountBadge() {
        tvSelectedCount.setText("Выбрано: " + selectedPackages.size());
        if (btnSaveAppRouting != null) {
            btnSaveAppRouting.setText("СОХРАНИТЬ И ПРИМЕНИТЬ (" + selectedPackages.size() + ")");
        }
    }

    private void selectAllVisible() {
        int added = 0;
        for (AppEntry entry : displayedApps) {
            if (!entry.isSelected) {
                entry.isSelected = true;
                selectedPackages.add(entry.packageName);
                added++;
            }
        }
        adapter.notifyDataSetChanged();
        updateCountBadge();
        updateFilterChipsUI();
        Toast.makeText(context, "Отмечено в текущем списке: " + added, Toast.LENGTH_SHORT).show();
    }

    private void clearAllSelections() {
        selectedPackages.clear();
        for (AppEntry entry : allApps) {
            entry.isSelected = false;
        }
        adapter.notifyDataSetChanged();
        updateCountBadge();
        updateFilterChipsUI();
        Toast.makeText(context, "Выбор очищен", Toast.LENGTH_SHORT).show();
    }

    private void invertSelections() {
        for (AppEntry entry : displayedApps) {
            entry.isSelected = !entry.isSelected;
            if (entry.isSelected) {
                selectedPackages.add(entry.packageName);
            } else {
                selectedPackages.remove(entry.packageName);
            }
        }
        adapter.notifyDataSetChanged();
        updateCountBadge();
        updateFilterChipsUI();
    }

    private void filterApps() {
        String query = etSearchApp.getText() != null ? etSearchApp.getText().toString().trim().toLowerCase(Locale.ROOT) : "";
        displayedApps.clear();

        for (AppEntry app : allApps) {
            if (hideSystemApps && app.isSystem) {
                continue;
            }

            // Category filter
            switch (activeCategoryFilter) {
                case SPYWARE:
                    if (!app.isSpyware) continue;
                    break;
                case GOOGLE:
                    if (!app.isGoogle) continue;
                    break;
                case SELECTED:
                    if (!app.isSelected) continue;
                    break;
                case ALL:
                default:
                    break;
            }

            // Search query filter
            if (!query.isEmpty()) {
                boolean matchName = app.name.toLowerCase(Locale.ROOT).contains(query);
                boolean matchPkg = app.packageName.toLowerCase(Locale.ROOT).contains(query);
                if (!matchName && !matchPkg) {
                    continue;
                }
            }
            displayedApps.add(app);
        }

        adapter.notifyDataSetChanged();
        tvEmptyList.setVisibility(displayedApps.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void loadInstalledAppsAsync() {
        pbLoadingApps.setVisibility(View.VISIBLE);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = context.getPackageManager();
                Map<String, ApplicationInfo> appMap = new LinkedHashMap<>();
                Set<String> launcherPackages = new HashSet<>();

                // 1. Поиск запускаемых пользователем приложений (Launcher Activities)
                try {
                    Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
                    launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> resolveInfos = pm.queryIntentActivities(launcherIntent, 0);
                    if (resolveInfos != null) {
                        for (ResolveInfo ri : resolveInfos) {
                            if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                                launcherPackages.add(ri.activityInfo.packageName);
                                if (ri.activityInfo.applicationInfo != null) {
                                    appMap.put(ri.activityInfo.packageName, ri.activityInfo.applicationInfo);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // 2. Получение установленных приложений через getInstalledApplications
                try {
                    List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                    if (apps != null) {
                        for (ApplicationInfo ai : apps) {
                            if (ai != null && ai.packageName != null) {
                                if (!appMap.containsKey(ai.packageName)) {
                                    appMap.put(ai.packageName, ai);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // 3. Получение через getInstalledPackages(PackageManager.GET_META_DATA)
                try {
                    List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_META_DATA);
                    if (pkgs != null) {
                        for (PackageInfo pi : pkgs) {
                            if (pi != null && pi.packageName != null && pi.applicationInfo != null) {
                                if (!appMap.containsKey(pi.packageName)) {
                                    appMap.put(pi.packageName, pi.applicationInfo);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // 4. Дополнительный fallback через getInstalledPackages(0) на случай TransactionTooLargeException
                try {
                    List<PackageInfo> basicPkgs = pm.getInstalledPackages(0);
                    if (basicPkgs != null) {
                        for (PackageInfo pi : basicPkgs) {
                            if (pi != null && pi.packageName != null) {
                                if (!appMap.containsKey(pi.packageName)) {
                                    try {
                                        ApplicationInfo ai = pm.getApplicationInfo(pi.packageName, 0);
                                        if (ai != null) {
                                            appMap.put(pi.packageName, ai);
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}

                final List<AppEntry> loaded = new ArrayList<>();
                String myPackage = context.getPackageName();

                for (ApplicationInfo info : appMap.values()) {
                    if (info == null || info.packageName == null || info.packageName.equals(myPackage)) {
                        continue; // Не отображаем Goblin VPN сам в себе
                    }

                    try {
                        String label = pm.getApplicationLabel(info).toString();
                        Drawable icon = pm.getApplicationIcon(info);

                        boolean hasSystemFlag = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        boolean isUpdatedSystem = (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                        boolean isLauncherApp = launcherPackages.contains(info.packageName);

                        // Программа считается системным фоновым демоном ТОЛЬКО если у нее нет лаунчера,
                        // она не обновлялась пользователем и помечена системным флагом
                        boolean isSys = hasSystemFlag && !isUpdatedSystem && !isLauncherApp;

                        boolean isGgl = NetworkPreferences.isGooglePackage(info.packageName);
                        boolean isSpy = NetworkPreferences.isSpywarePackage(info.packageName);
                        boolean isSel = selectedPackages.contains(info.packageName);

                        loaded.add(new AppEntry(label, info.packageName, icon, isSys, isGgl, isSpy, isSel));
                    } catch (Exception ignored) {}
                }

                // Сортировка: Сначала выбранные, затем по алфавиту
                Collections.sort(loaded, new Comparator<AppEntry>() {
                    @Override
                    public int compare(AppEntry o1, AppEntry o2) {
                        if (o1.isSelected != o2.isSelected) {
                            return o1.isSelected ? -1 : 1;
                        }
                        return o1.name.compareToIgnoreCase(o2.name);
                    }
                });

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        allApps.clear();
                        allApps.addAll(loaded);
                        filterApps();
                        pbLoadingApps.setVisibility(View.GONE);
                        updateCountBadge();
                        updateFilterChipsUI();
                        updateSystemChipUI();
                    }
                });
            }
        });
    }

    private void saveAndApply() {
        prefs.setAppRoutingMode(currentMode);
        prefs.setRoutedPackages(selectedPackages);

        AppLogger.i(TAG, "Пользователь сохранил правила раздельного туннелирования: "
                + currentMode.getTitle() + " (пакетов: " + selectedPackages.size() + ")");

        if (listener != null) {
            listener.onRoutingChanged(currentMode, selectedPackages);
        } else {
            VpnState vpnState = GoblinVpnService.getCurrentState();
            if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING) {
                AppLogger.i(TAG, "VPN активен, применение правил маршрутизации через GoblinVpnService...");
                Intent restartIntent = new Intent(context, GoblinVpnService.class);
                restartIntent.setAction(GoblinVpnService.ACTION_CONNECT);
                restartIntent.putExtra(GoblinVpnService.EXTRA_SERVER, GoblinVpnService.getCurrentServer());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(restartIntent);
                } else {
                    context.startService(restartIntent);
                }
            }
        }

        Toast.makeText(context, "Настройки маршрутизации сохранены", Toast.LENGTH_SHORT).show();
        dialog.dismiss();
    }

    private class AppsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return displayedApps.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return displayedApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_app_routing, parent, false);
                holder = new ViewHolder();
                holder.ivIcon = convertView.findViewById(R.id.ivAppIcon);
                holder.tvName = convertView.findViewById(R.id.tvAppName);
                holder.tvCategoryBadge = convertView.findViewById(R.id.tvAppCategoryBadge);
                holder.tvPackage = convertView.findViewById(R.id.tvAppPackage);
                holder.cbSelected = convertView.findViewById(R.id.cbAppSelected);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            final AppEntry entry = getItem(position);
            holder.ivIcon.setImageDrawable(entry.icon);
            holder.tvName.setText(entry.name);
            holder.tvPackage.setText(entry.packageName);
            holder.cbSelected.setChecked(entry.isSelected);

            // Category badge indicator
            if (entry.isSpyware) {
                holder.tvCategoryBadge.setVisibility(View.VISIBLE);
                holder.tvCategoryBadge.setText("Слежка / РФ");
                holder.tvCategoryBadge.setTextColor(Color.parseColor("#F59E0B"));
                holder.tvCategoryBadge.setBackgroundResource(R.drawable.bg_m3_badge_amber);
            } else if (entry.isGoogle) {
                holder.tvCategoryBadge.setVisibility(View.VISIBLE);
                holder.tvCategoryBadge.setText("Google");
                holder.tvCategoryBadge.setTextColor(Color.parseColor("#60A5FA"));
                holder.tvCategoryBadge.setBackgroundResource(R.drawable.bg_m3_chip);
            } else if (entry.isSystem) {
                holder.tvCategoryBadge.setVisibility(View.VISIBLE);
                holder.tvCategoryBadge.setText("Система");
                holder.tvCategoryBadge.setTextColor(Color.parseColor("#94A3B8"));
                holder.tvCategoryBadge.setBackgroundResource(R.drawable.bg_m3_chip);
            } else {
                holder.tvCategoryBadge.setVisibility(View.GONE);
            }

            final AppEntry currentEntry = entry;
            View.OnClickListener toggleClick = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentEntry.isSelected = !currentEntry.isSelected;
                    if (currentEntry.isSelected) {
                        selectedPackages.add(currentEntry.packageName);
                    } else {
                        selectedPackages.remove(currentEntry.packageName);
                    }
                    holder.cbSelected.setChecked(currentEntry.isSelected);
                    updateCountBadge();
                    updateFilterChipsUI();
                }
            };
            convertView.setOnClickListener(toggleClick);
            holder.cbSelected.setOnClickListener(toggleClick);

            return convertView;
        }

        class ViewHolder {
            ImageView ivIcon;
            TextView tvName;
            TextView tvCategoryBadge;
            TextView tvPackage;
            CheckBox cbSelected;
        }
    }
}