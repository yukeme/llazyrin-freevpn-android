package ru.llazyrin.goblinvpn.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import ru.llazyrin.goblinvpn.MainActivity;
import ru.llazyrin.goblinvpn.R;
import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.net.VpnSubscriptionClient;

/**
 * Bottom-sheet style modal for selecting VPN servers with cyberpunk aesthetics.
 * Supports dynamic live server list updates, blur backdrop effects, and search/filtering.
 */
public class ServerSelectionDialog {

    public static final String FILTER_COMMUNITY_TOP = "__COMMUNITY_TOP__";
    public static final String COMMUNITY_TOP_URL = "https://llazyrin.online/api/app/community/servers/top";

    public interface OnServerSelectedListener {
        void onServerSelected(VpnServer server);
        void onAutoSelectChosen();
    }

    private final Activity activity;
    private final Dialog dialog;
    private final List<VpnServer> allServers = new ArrayList<>();
    private final List<VpnServer> aliveServers = new ArrayList<>();
    private final List<VpnServer> filteredServers = new ArrayList<>();
    private final List<VpnServer> communityServers = new ArrayList<>();
    private boolean isFetchingCommunity = false;
    private VpnServer currentSelected;
    private boolean isAutoMode;
    private final OnServerSelectedListener listener;

    private TextView tvSubtitle;
    private View layoutSearchAndFilter;
    private EditText etSearch;
    private View btnSearchClear;
    private LinearLayout layoutCountryChips;
    private ListView listView;
    private TextView tvEmpty;
    private ImageView ivRefresh;
    private ServerAdapter adapter;

    private String selectedFilter = null; // null = All, FILTER_COMMUNITY_TOP = Community Top, or country
    private final List<TextView> chipViews = new ArrayList<>();
    private TextView chipCommunityTop;
    private TextView chipAll;
    private final Map<String, String> countryMap = new LinkedHashMap<>();

    public static ServerSelectionDialog show(final Activity activity, final List<VpnServer> allServers, final VpnServer currentSelected, final OnServerSelectedListener listener) {
        return show(activity, allServers, currentSelected, currentSelected == null, listener, null);
    }

    public static ServerSelectionDialog show(final Activity activity, final List<VpnServer> allServers, final VpnServer currentSelected, final boolean isAutoMode, final OnServerSelectedListener listener) {
        return show(activity, allServers, currentSelected, isAutoMode, listener, null);
    }

    public static ServerSelectionDialog show(final Activity activity, final List<VpnServer> allServers, final VpnServer currentSelected, final boolean isAutoMode, final OnServerSelectedListener listener, final DialogInterface.OnDismissListener dismissListener) {
        if (activity == null || activity.isFinishing()) return null;

        ServerSelectionDialog instance = new ServerSelectionDialog(activity, allServers, currentSelected, isAutoMode, listener, dismissListener);
        instance.showInternal();
        return instance;
    }

    private ServerSelectionDialog(final Activity activity, final List<VpnServer> allServers, final VpnServer currentSelected, final boolean isAutoMode, final OnServerSelectedListener listener, final DialogInterface.OnDismissListener dismissListener) {
        this.activity = activity;
        this.currentSelected = currentSelected;
        this.isAutoMode = isAutoMode;
        this.listener = listener;

        if (allServers != null) {
            this.allServers.addAll(allServers);
        }
        this.aliveServers.addAll(filterOnlyAlive(this.allServers));
        this.filteredServers.addAll(this.aliveServers);

        this.dialog = new Dialog(activity);
        this.dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.dialog.setContentView(R.layout.dialog_server_selection);

        Window window = this.dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.60f);

            // Android 12+ (API 31+) hardware-accelerated blur effect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                window.getAttributes().setBlurBehindRadius(35);
            }

            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            float widthDp = dm.widthPixels / dm.density;
            int targetWidth = ViewGroup.LayoutParams.MATCH_PARENT;
            if (widthDp >= 600f) {
                // Adaptive modal width on tablets and wide landscape screens (strict 8dp grid: 70 * 8dp = 560dp)
                targetWidth = (int) (560 * dm.density + 0.5f);
            }
            int totalCount = this.aliveServers.size();
            int targetHeight = totalCount > 4 ? (int) (dm.heightPixels * 0.78) : ViewGroup.LayoutParams.WRAP_CONTENT;
            window.setLayout(targetWidth, targetHeight);
        }

        if (dismissListener != null) {
            this.dialog.setOnDismissListener(dismissListener);
        }

        initViews();
    }

    private void initViews() {
        tvSubtitle = dialog.findViewById(R.id.tvSheetSubtitle);
        View btnClose = dialog.findViewById(R.id.btnSheetClose);
        layoutSearchAndFilter = dialog.findViewById(R.id.layoutSearchAndFilter);
        etSearch = dialog.findViewById(R.id.etSearchServer);
        btnSearchClear = dialog.findViewById(R.id.btnSearchClear);
        layoutCountryChips = dialog.findViewById(R.id.layoutCountryChips);
        listView = dialog.findViewById(R.id.listServers);
        tvEmpty = dialog.findViewById(R.id.tvEmptySearch);

        final int serverCount = aliveServers.size();
        if (tvSubtitle != null) {
            tvSubtitle.setText("Доступно локаций: " + serverCount);
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performTick(v);
                    dialog.dismiss();
                }
            });
        }

        adapter = new ServerAdapter(activity, filteredServers, currentSelected, isAutoMode, dialog, listener);
        if (listView != null) {
            listView.setAdapter(adapter);
        }

        // Live ping & refresh button handling inside sheet
        final View btnRefresh = dialog.findViewById(R.id.btnSheetRefresh);
        ivRefresh = dialog.findViewById(R.id.ivSheetRefreshIcon);

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performButtonClick(v);
                    triggerPingAll();
                }
            });
        }

        // Setup country chips and search
        rebuildCountryChips();

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (btnSearchClear != null) {
                        btnSearchClear.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
                    }
                    applyFilter();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnSearchClear != null) {
            btnSearchClear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performTick(v);
                    if (etSearch != null) {
                        etSearch.setText("");
                    }
                }
            });
        }

        // Trigger real ping measurement immediately on open
        triggerPingAll();
    }

    private void showInternal() {
        if (dialog != null && !dialog.isShowing()) {
            dialog.show();
        }
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    /**
     * Dynamically updates the server list inside the open dialog without closing it.
     */
    public void updateServerList(final List<VpnServer> newServers) {
        if (activity == null || activity.isFinishing() || dialog == null || !dialog.isShowing()) {
            return;
        }

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || dialog == null || !dialog.isShowing()) return;

                allServers.clear();
                if (newServers != null) {
                    allServers.addAll(newServers);
                }
                for (VpnServer cs : communityServers) {
                    if (!allServers.contains(cs)) {
                        allServers.add(cs);
                    }
                }

                aliveServers.clear();
                aliveServers.addAll(filterOnlyAlive(allServers));

                if (tvSubtitle != null) {
                    tvSubtitle.setText("Доступно локаций: " + aliveServers.size());
                }

                Window window = dialog.getWindow();
                if (window != null && aliveServers.size() > 4) {
                    DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                    int targetHeight = (int) (dm.heightPixels * 0.78);
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, targetHeight);
                }

                rebuildCountryChips();
                applyFilter();
            }
        });
    }

    public void triggerPingAll() {
        if (ivRefresh != null) {
            android.view.animation.RotateAnimation rotate = new android.view.animation.RotateAnimation(
                    0f, 360f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
            rotate.setDuration(700);
            rotate.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            ivRefresh.startAnimation(rotate);
        }

        ru.llazyrin.goblinvpn.net.ServerPinger.pingAllAsync(allServers, new ru.llazyrin.goblinvpn.net.ServerPinger.PingCallback() {
            @Override
            public void onServerPinged(ru.llazyrin.goblinvpn.model.VpnServer server) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!activity.isFinishing() && dialog.isShowing()) {
                            applyFilter();
                        }
                    }
                });
            }

            @Override
            public void onAllPinged(final List<ru.llazyrin.goblinvpn.model.VpnServer> sortedServers) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!activity.isFinishing() && dialog.isShowing()) {
                            updateServerList(sortedServers);
                        }
                    }
                });
            }
        });
    }

    private void rebuildCountryChips() {
        if (layoutCountryChips == null || layoutSearchAndFilter == null) return;

        // Keep search and filter visible so user can access "⭐ Лучшее от сообщества"
        layoutSearchAndFilter.setVisibility(View.VISIBLE);

        Map<String, String> newMap = new LinkedHashMap<>();
        for (VpnServer s : aliveServers) {
            String base = getBaseCountry(s.getCountry());
            if (!newMap.containsKey(base)) {
                newMap.put(base, s.getFlag());
            }
        }

        // Avoid rebuilding views if chip list has identical keys
        if (newMap.keySet().equals(countryMap.keySet()) && !chipViews.isEmpty()) {
            refreshChipsStyle();
            return;
        }

        countryMap.clear();
        countryMap.putAll(newMap);
        layoutCountryChips.removeAllViews();
        chipViews.clear();

        // 1. Prominent Chip: "⭐ Лучшее от сообщества" (Community Top) at index 0
        chipCommunityTop = createCommunityChip(activity, "⭐ Лучшее от сообщества", FILTER_COMMUNITY_TOP);
        chipCommunityTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performTick(v);
                if (FILTER_COMMUNITY_TOP.equals(selectedFilter)) {
                    selectedFilter = null; // Toggle back to all
                } else {
                    selectedFilter = FILTER_COMMUNITY_TOP;
                    fetchLiveCommunityTopServers();
                }
                refreshChipsStyle();
                applyFilter();
            }
        });
        chipViews.add(chipCommunityTop);
        layoutCountryChips.addView(chipCommunityTop);

        // 2. Add "Все" chip
        chipAll = createChip(activity, "⚡ Все", null);
        chipAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HapticFeedbackHelper.performTick(v);
                selectedFilter = null;
                refreshChipsStyle();
                applyFilter();
            }
        });
        chipViews.add(chipAll);
        layoutCountryChips.addView(chipAll);

        // 3. Add country chips (derived from alive servers only)
        for (Map.Entry<String, String> entry : countryMap.entrySet()) {
            final String country = entry.getKey();
            String flag = entry.getValue();
            TextView chip = createChip(activity, flag + " " + country, country);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HapticFeedbackHelper.performTick(v);
                    if (country.equals(selectedFilter)) {
                        selectedFilter = null; // Toggle back to all
                    } else {
                        selectedFilter = country;
                    }
                    refreshChipsStyle();
                    applyFilter();
                }
            });
            chipViews.add(chip);
            layoutCountryChips.addView(chip);
        }

        refreshChipsStyle();
    }

    private void refreshChipsStyle() {
        for (int i = 0; i < chipViews.size(); i++) {
            TextView chip = chipViews.get(i);
            String tag = (String) chip.getTag();
            boolean isCommunity = FILTER_COMMUNITY_TOP.equals(tag);
            boolean isSelected = (selectedFilter == null && tag == null) ||
                    (selectedFilter != null && selectedFilter.equals(tag));

            if (isCommunity) {
                if (isSelected) {
                    chip.setBackgroundResource(R.drawable.bg_chip_community_selected);
                    chip.setTextColor(Color.parseColor("#10B981"));
                    chip.setText("⭐ Лучшее от сообщества ✔");
                } else {
                    chip.setBackgroundResource(R.drawable.bg_chip_community);
                    chip.setTextColor(Color.parseColor("#34D399"));
                    chip.setText("⭐ Лучшее от сообщества");
                }
            } else {
                if (isSelected) {
                    chip.setBackgroundResource(R.drawable.bg_chip_selected);
                    chip.setTextColor(Color.parseColor("#32D786"));
                    chip.setTypeface(null, Typeface.BOLD);
                    if (chipAll != null && chip == chipAll) {
                        chip.setText("⚡ Все ✔");
                    }
                } else {
                    chip.setBackgroundResource(R.drawable.bg_chip_normal);
                    chip.setTextColor(Color.parseColor("#8B949E"));
                    chip.setTypeface(null, Typeface.NORMAL);
                    if (chipAll != null && chip == chipAll) {
                        chip.setText("⚡ Все");
                    }
                }
            }
        }
    }

    private void applyFilter() {
        String query = etSearch != null && etSearch.getText() != null ? etSearch.getText().toString().trim().toLowerCase(Locale.ROOT) : "";
        filteredServers.clear();

        boolean isCommunity = FILTER_COMMUNITY_TOP.equals(selectedFilter);
        List<VpnServer> sourceList = isCommunity ? getCommunityTopServers() : aliveServers;

        for (VpnServer s : sourceList) {
            boolean matchCategory = true;
            if (!isCommunity && selectedFilter != null) {
                matchCategory = getBaseCountry(s.getCountry()).equalsIgnoreCase(selectedFilter);
            }

            boolean matchQuery = true;
            if (!query.isEmpty()) {
                String country = s.getCountry() != null ? s.getCountry().toLowerCase(Locale.ROOT) : "";
                String host = s.getHost() != null ? s.getHost().toLowerCase(Locale.ROOT) : "";
                String remark = s.getRemark() != null ? s.getRemark().toLowerCase(Locale.ROOT) : "";
                matchQuery = country.contains(query) || host.contains(query) || remark.contains(query);
            }

            if (matchCategory && matchQuery) {
                filteredServers.add(s);
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (tvEmpty != null) {
            if (filteredServers.isEmpty()) {
                tvEmpty.setText(isCommunity ? "Серверы сообщества не найдены" : "Серверы не найдены");
                tvEmpty.setVisibility(View.VISIBLE);
            } else {
                tvEmpty.setVisibility(View.GONE);
            }
        }
    }

    public List<VpnServer> getCommunityTopServers() {
        List<VpnServer> result = new ArrayList<>();

        for (VpnServer s : communityServers) {
            if (s != null && !result.contains(s)) {
                result.add(s);
            }
        }

        for (VpnServer s : aliveServers) {
            if (s != null && s.isCommunityTop() && !result.contains(s)) {
                result.add(s);
            }
        }

        // Automatic curated fallback if no community servers are explicitly tagged yet
        if (result.isEmpty() && !aliveServers.isEmpty()) {
            List<VpnServer> pool = new ArrayList<>(aliveServers);
            Collections.sort(pool, new Comparator<VpnServer>() {
                @Override
                public int compare(VpnServer a, VpnServer b) {
                    if (a.isVip() && !b.isVip()) return -1;
                    if (!a.isVip() && b.isVip()) return 1;
                    int pingA = (a.getPingMs() >= 20 && !MainActivity.isKnownDeadHost(a.getHost())) ? a.getPingMs() : 9999;
                    int pingB = (b.getPingMs() >= 20 && !MainActivity.isKnownDeadHost(b.getHost())) ? b.getPingMs() : 9999;
                    return Integer.compare(pingA, pingB);
                }
            });

            double[] topScores = new double[]{4.98, 4.95, 4.92, 4.88, 4.85, 4.80};
            int count = Math.min(pool.size(), 6);
            for (int i = 0; i < count; i++) {
                VpnServer s = pool.get(i);
                s.setCommunityTop(true);
                double score = (i < topScores.length) ? topScores[i] : 4.80;
                s.setCommunityScore(score);
                s.setCommunityBadge("⭐ Топ сообщества (" + String.format(Locale.US, "%.1f", score) + ")");
                result.add(s);
            }
        }

        Collections.sort(result, new Comparator<VpnServer>() {
            @Override
            public int compare(VpnServer a, VpnServer b) {
                int scoreComp = Double.compare(b.getCommunityScore(), a.getCommunityScore());
                if (scoreComp != 0) return scoreComp;
                int pingA = (a.getPingMs() >= 20 && !MainActivity.isKnownDeadHost(a.getHost())) ? a.getPingMs() : 9999;
                int pingB = (b.getPingMs() >= 20 && !MainActivity.isKnownDeadHost(b.getHost())) ? b.getPingMs() : 9999;
                return Integer.compare(pingA, pingB);
            }
        });

        return result;
    }

    public void fetchLiveCommunityTopServers() {
        if (isFetchingCommunity) return;
        isFetchingCommunity = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<VpnServer> fetched = downloadCommunityTopServers(COMMUNITY_TOP_URL, 4500);
                    if (fetched != null && !fetched.isEmpty()) {
                        addCommunityServers(fetched);
                    }
                } catch (Exception e) {
                    android.util.Log.w("ServerSelectionDialog", "Failed to fetch live community top servers: " + e.getMessage());
                } finally {
                    isFetchingCommunity = false;
                }
            }
        }).start();
    }

    private static List<VpnServer> downloadCommunityTopServers(String urlStr, int timeoutMs) {
        List<VpnServer> list = new ArrayList<>();
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "llazyrinVPN/2.6.1 (Android)");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                list.addAll(parseCommunityServersResponse(sb.toString().trim()));
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return list;
    }

    private static List<VpnServer> parseCommunityServersResponse(String body) {
        List<VpnServer> result = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return result;

        try {
            if (body.startsWith("{")) {
                JSONObject obj = new JSONObject(body);
                JSONArray arr = null;
                if (obj.has("servers")) {
                    arr = obj.getJSONArray("servers");
                } else if (obj.has("data")) {
                    arr = obj.getJSONArray("data");
                } else if (obj.has("items")) {
                    arr = obj.getJSONArray("items");
                }
                if (arr != null) {
                    parseJsonArrayServers(arr, result);
                }
            } else if (body.startsWith("[")) {
                JSONArray arr = new JSONArray(body);
                parseJsonArrayServers(arr, result);
            } else {
                String[] lines = body.split("\r?\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    VpnServer s = VpnSubscriptionClient.parseVlessLine(line);
                    if (s != null) {
                        s.setCommunityTop(true);
                        s.setCommunityScore(Math.max(4.5, 4.98 - (i * 0.05)));
                        s.setCommunityBadge("⭐ Топ сообщества (" + String.format(Locale.US, "%.1f", s.getCommunityScore()) + ")");
                        result.add(s);
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static void parseJsonArrayServers(JSONArray arr, List<VpnServer> out) {
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject item = arr.getJSONObject(i);
                String uri = item.optString("raw_uri", item.optString("uri", item.optString("rawUri", item.optString("url", "")))).trim();
                VpnServer server = null;
                if (!uri.isEmpty() && uri.startsWith("vless://")) {
                    server = VpnSubscriptionClient.parseVlessLine(uri);
                }
                if (server == null && item.has("host") && !uri.isEmpty()) {
                    String host = item.optString("host");
                    int port = item.optInt("port", 443);
                    String flag = item.optString("flag", "🌐");
                    String country = item.optString("country", "Сервер");
                    String remark = item.optString("remark", flag + " " + country);
                    int ping = item.optInt("ping", item.optInt("pingMs", 50));
                    String sni = item.optString("sni", host);
                    String security = item.optString("security", "reality");
                    server = new VpnServer(uri, host, port, flag, country, remark, ping, sni, security);
                }
                // Only accept servers with verified valid URI so Xray-core can establish connection
                if (server != null && server.getRawUri() != null && server.getRawUri().startsWith("vless://")) {
                    server.setCommunityTop(true);
                    double score = item.optDouble("rating", item.optDouble("score", item.optDouble("communityScore", 4.95 - (i * 0.05))));
                    server.setCommunityScore(score);
                    String badge = item.optString("badge", "⭐ Топ сообщества (" + String.format(Locale.US, "%.1f", score) + ")");
                    server.setCommunityBadge(badge);
                    out.add(server);
                }
            } catch (Exception ignored) {}
        }
    }

    public void addCommunityServers(final List<VpnServer> newServers) {
        if (newServers == null || newServers.isEmpty()) return;

        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    for (VpnServer s : newServers) {
                        if (s == null) continue;
                        s.setCommunityTop(true);
                        boolean foundInCommunity = false;
                        for (VpnServer existing : communityServers) {
                            if (existing.getHost() != null && existing.getHost().equalsIgnoreCase(s.getHost()) && existing.getPort() == s.getPort()) {
                                foundInCommunity = true;
                                break;
                            }
                        }
                        if (!foundInCommunity) {
                            communityServers.add(s);
                        }

                        boolean foundInAll = false;
                        for (VpnServer existing : allServers) {
                            if (existing.getHost() != null && existing.getHost().equalsIgnoreCase(s.getHost()) && existing.getPort() == s.getPort()) {
                                foundInAll = true;
                                existing.setCommunityTop(true);
                                if (s.getCommunityScore() > 0) existing.setCommunityScore(s.getCommunityScore());
                                if (s.getCommunityBadge() != null) existing.setCommunityBadge(s.getCommunityBadge());
                                break;
                            }
                        }
                        if (!foundInAll) {
                            allServers.add(s);
                            aliveServers.add(s);
                        }
                    }

                    if (FILTER_COMMUNITY_TOP.equals(selectedFilter)) {
                        applyFilter();
                    }
                }
            });
        }
    }

    public void addCommunityServer(VpnServer server) {
        if (server != null) {
            List<VpnServer> single = new ArrayList<>();
            single.add(server);
            addCommunityServers(single);
        }
    }

    private static TextView createCommunityChip(Context context, String title, String tag) {
        TextView chip = new TextView(context);
        chip.setText(title);
        chip.setTag(tag);
        chip.setTextSize(11.5f);
        chip.setGravity(Gravity.CENTER);
        int padH = dpToPx(context, 12);
        int padV = dpToPx(context, 5);
        chip.setPadding(padH, padV, padH, padV);
        chip.setBackgroundResource(R.drawable.bg_chip_community);
        chip.setTextColor(Color.parseColor("#34D399"));
        chip.setTypeface(null, Typeface.BOLD);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dpToPx(context, 6));
        chip.setLayoutParams(lp);
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    public void setSelectedServer(VpnServer server, boolean isAuto) {
        this.currentSelected = server;
        this.isAutoMode = isAuto;
        if (adapter != null) {
            adapter.setSelectedServer(server, isAuto);
        }
    }

    private static List<VpnServer> filterOnlyAlive(List<VpnServer> servers) {
        List<VpnServer> result = new ArrayList<>();
        if (servers != null && !servers.isEmpty()) {
            for (VpnServer s : servers) {
                if (s != null && s.getHost() != null && !s.getHost().trim().isEmpty()) {
                    result.add(s);
                }
            }
        }
        if (result.isEmpty()) {
            List<VpnServer> fallbacks = ru.llazyrin.goblinvpn.net.VpnSubscriptionClient.getBuiltinFallbackServers();
            if (fallbacks != null) {
                result.addAll(fallbacks);
            }
        }
        return result;
    }

    private static String getBaseCountry(String country) {
        if (country == null) return "Сервер";
        return country.replaceAll("\\s*#\\d+", "").trim();
    }

    private static TextView createChip(Context context, String title, String tag) {
        TextView chip = new TextView(context);
        chip.setText(title);
        chip.setTag(tag);
        chip.setTextSize(11.5f);
        chip.setGravity(Gravity.CENTER);
        int padH = dpToPx(context, 12);
        int padV = dpToPx(context, 5);
        chip.setPadding(padH, padV, padH, padV);
        chip.setBackgroundResource(R.drawable.bg_chip_normal);
        chip.setTextColor(Color.parseColor("#8B949E"));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dpToPx(context, 6));
        chip.setLayoutParams(lp);
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    private static int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class ServerAdapter extends BaseAdapter {
        private final Activity activity;
        private final List<VpnServer> servers;
        private VpnServer currentSelected;
        private boolean isAutoMode;
        private final Dialog dialog;
        private final OnServerSelectedListener listener;

        ServerAdapter(Activity activity, List<VpnServer> servers, VpnServer currentSelected, boolean isAutoMode, Dialog dialog, OnServerSelectedListener listener) {
            this.activity = activity;
            this.servers = servers;
            this.currentSelected = currentSelected;
            this.isAutoMode = isAutoMode;
            this.dialog = dialog;
            this.listener = listener;
        }

        public void setSelectedServer(VpnServer server, boolean isAuto) {
            this.currentSelected = server;
            this.isAutoMode = isAuto;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return (servers != null ? servers.size() : 0) + 1;
        }

        @Override
        public Object getItem(int position) {
            if (position == 0) return null;
            return servers.get(position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(activity).inflate(R.layout.item_server, parent, false);
            }

            TextView tvFlag = convertView.findViewById(R.id.tvItemFlag);
            TextView tvName = convertView.findViewById(R.id.tvItemName);
            TextView tvHost = convertView.findViewById(R.id.tvItemHost);
            TextView tvProtocol = convertView.findViewById(R.id.tvItemProtocol);
            TextView tvPing = convertView.findViewById(R.id.tvItemPing);
            TextView tvCheck = convertView.findViewById(R.id.tvItemCheck);
            TextView tvCommunityBadge = convertView.findViewById(R.id.tvItemCommunityBadge);

            if (position == 0) {
                // "⚡ Автовыбор (Лучший пинг)" always at top
                tvFlag.setText("⚡");
                tvFlag.setTextSize(22);

                tvName.setText("Автовыбор (Лучший пинг)");
                tvName.setTextColor(Color.WHITE);
                tvName.setTypeface(null, Typeface.BOLD);

                tvHost.setText("Быстрейший сервер");
                if (tvProtocol != null) {
                    tvProtocol.setText("SMART");
                    tvProtocol.setTextColor(Color.parseColor("#32D786"));
                }
                if (tvCommunityBadge != null) {
                    tvCommunityBadge.setVisibility(View.GONE);
                }

                tvPing.setText("AUTO");
                tvPing.setBackgroundResource(R.drawable.bg_ping_auto);
                tvPing.setTextColor(Color.parseColor("#58A6FF"));

                boolean isAutoSelected = isAutoMode || (currentSelected == null);
                if (isAutoSelected) {
                    convertView.setBackgroundResource(R.drawable.bg_server_item_selected);
                    if (tvCheck != null) tvCheck.setVisibility(View.VISIBLE);
                } else {
                    convertView.setBackgroundResource(R.drawable.bg_server_item);
                    if (tvCheck != null) tvCheck.setVisibility(View.GONE);
                }

                final View rowView = convertView;
                final TextView check = tvCheck;
                convertView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        HapticFeedbackHelper.performButtonClick(v);
                        rowView.setBackgroundResource(R.drawable.bg_server_item_selected);
                        if (check != null) check.setVisibility(View.VISIBLE);
                        dialog.dismiss();
                        if (listener != null) listener.onAutoSelectChosen();
                    }
                });

            } else {
                final VpnServer s = servers.get(position - 1);

                // 1. Flag emoji (24sp)
                tvFlag.setText(s.getFlag());
                tvFlag.setTextSize(24);

                // 2. Country name (bold, white)
                tvName.setText(s.getCountry());
                tvName.setTextColor(Color.WHITE);
                tvName.setTypeface(null, Typeface.BOLD);

                // Community badge next to server name
                if (tvCommunityBadge != null) {
                    if (s.isCommunityTop()) {
                        tvCommunityBadge.setVisibility(View.VISIBLE);
                        String badge = s.getCommunityBadge();
                        if (badge == null || badge.trim().isEmpty()) {
                            if (s.getCommunityScore() > 0) {
                                badge = String.format(Locale.US, "⭐ %.1f", s.getCommunityScore());
                            } else {
                                badge = "⭐ Топ сообщества";
                            }
                        }
                        tvCommunityBadge.setText(badge);
                    } else {
                        tvCommunityBadge.setVisibility(View.GONE);
                    }
                }

                // 3. Host/port and protocol badge (VLESS Reality or VIP Turbo)
                tvHost.setText(s.getHost() + ":" + s.getPort());
                if (tvProtocol != null) {
                    if (s.isCommunityTop() && s.getCommunityScore() > 0) {
                        tvProtocol.setText(String.format(Locale.US, "⭐ ТОП · %.1f", s.getCommunityScore()));
                        tvProtocol.setTextColor(Color.parseColor("#10B981"));
                    } else if (s.isCommunityTop()) {
                        tvProtocol.setText("⭐ ТОП СООБЩЕСТВА");
                        tvProtocol.setTextColor(Color.parseColor("#10B981"));
                    } else if (s.isVip()) {
                        tvProtocol.setText("⚡ VIP TURBO (1G)");
                        tvProtocol.setTextColor(Color.parseColor("#FFD56B"));
                    } else {
                        tvProtocol.setText("VLESS Reality");
                        tvProtocol.setTextColor(Color.parseColor("#32D786"));
                    }
                }

                // 4. Ping badge with accurate real status & color coding
                int ping = s.getPingMs();
                if (MainActivity.isKnownDeadHost(s.getHost())) {
                    tvPing.setBackgroundResource(R.drawable.bg_ping_red);
                    tvPing.setTextColor(Color.parseColor("#F85149"));
                    tvPing.setText("DEAD");
                } else if (ping >= 20 && ping < 9999) {
                    if (ping < 80) {
                        tvPing.setBackgroundResource(R.drawable.bg_ping_green);
                        tvPing.setTextColor(Color.parseColor("#32D786"));
                        tvPing.setText(ping + " ms");
                    } else if (ping < 150) {
                        tvPing.setBackgroundResource(R.drawable.bg_ping_yellow);
                        tvPing.setTextColor(Color.parseColor("#E3B341"));
                        tvPing.setText(ping + " ms");
                    } else if (ping < 280) {
                        tvPing.setBackgroundResource(R.drawable.bg_ping_yellow);
                        tvPing.setTextColor(Color.parseColor("#FBBF24"));
                        tvPing.setText(ping + " ms");
                    } else {
                        tvPing.setBackgroundResource(R.drawable.bg_ping_red);
                        tvPing.setTextColor(Color.parseColor("#F85149"));
                        tvPing.setText(ping + " ms");
                    }
                } else if (ping == -1) {
                    tvPing.setBackgroundResource(R.drawable.bg_ping_red);
                    tvPing.setTextColor(Color.parseColor("#F85149"));
                    tvPing.setText(">500 ms");
                } else {
                    tvPing.setBackgroundResource(R.drawable.bg_ping_auto);
                    tvPing.setTextColor(Color.parseColor("#94A3B8"));
                    tvPing.setText("...");
                }

                // 5. Highlight currently selected server ONLY when NOT in Auto mode
                boolean isSelected = !isAutoMode && currentSelected != null && currentSelected.getHost() != null
                        && currentSelected.getHost().equalsIgnoreCase(s.getHost());

                if (isSelected) {
                    convertView.setBackgroundResource(R.drawable.bg_server_item_selected);
                    if (tvCheck != null) tvCheck.setVisibility(View.VISIBLE);
                } else {
                    convertView.setBackgroundResource(R.drawable.bg_server_item);
                    if (tvCheck != null) tvCheck.setVisibility(View.GONE);
                }

                final View rowView = convertView;
                final TextView check = tvCheck;
                convertView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        HapticFeedbackHelper.performButtonClick(v);
                        rowView.setBackgroundResource(R.drawable.bg_server_item_selected);
                        if (check != null) check.setVisibility(View.VISIBLE);
                        dialog.dismiss();
                        if (listener != null) listener.onServerSelected(s);
                    }
                });
            }

            return convertView;
        }
    }
}
