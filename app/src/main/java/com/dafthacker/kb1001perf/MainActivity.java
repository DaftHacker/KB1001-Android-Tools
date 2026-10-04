package com.dafthacker.kb1001perf;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int ACCENT = Color.rgb(158,218,226);
    private static final int MUTED = Color.rgb(155,174,171);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout page;
    private TextView headerState;
    private Button updateBanner;

    private Switch hudSwitch;
    private Switch autoBoostSwitch;
    private Switch autoHudSwitch;
    private Switch loggingSwitch;

    private MetricUi ramMetric;
    private MetricUi cpuMetric;
    private MetricUi gpuMetric;
    private MetricUi thermalMetric;
    private MetricUi batteryMetric;
    private TextView loggerPath;

    private TextView heroMode;
    private TextView heroProfile;
    private TextView heroGpu;
    private TextView heroTemp;

    private LinearLayout gamesContainer;
    private final Set<String> selectedGames = new LinkedHashSet<>();
    private final List<LauncherApp> launcherApps = new ArrayList<>();

    private final Map<String,RadioButton> profileButtons = new LinkedHashMap<>();
    private UpdateManager.ReleaseInfo releaseInfo;

    private int tab;
    private boolean active;
    private boolean suppressSwitchCallbacks;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TelemetryStore.ensureSnapshot(this);

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }

        setContentView(buildUi());
        showTab(0);

        io.execute(() -> {
            RootBridge.get().ctl("status");
            autoDetectGames();
        });

        quietUpdateCheck();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(16),dp(16),dp(10));
        root.setBackgroundResource(R.drawable.bg_app);

        TextView eyebrow = text("KB1001",11,ACCENT,true);
        eyebrow.setLetterSpacing(.18f);
        root.addView(eyebrow);

        root.addView(text("Performance Manager",29,Color.rgb(231,240,238),true));

        headerState = text("Connecting to performance backend…",12,MUTED,false);
        headerState.setPadding(0,dp(3),0,dp(9));
        root.addView(headerState);

        updateBanner = button("Update available",true,v -> {
            if (releaseInfo != null) showUpdateConfirmation(releaseInfo);
            else checkForUpdate(false);
        });
        updateBanner.setVisibility(View.GONE);
        root.addView(updateBanner,new LinearLayout.LayoutParams(-1,-2));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        tabs.addView(tabButton("DASHBOARD",0),tabWeight());
        tabs.addView(tabButton("GAMES",1),tabWeight());
        tabs.addView(tabButton("LOGGER",2),tabWeight());
        tabs.addView(tabButton("SETTINGS",3),tabWeight());
        tabScroll.addView(tabs);
        root.addView(tabScroll,new LinearLayout.LayoutParams(-1,-2));

        ScrollView scroller = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0,dp(8),0,dp(28));
        scroller.addView(page);
        root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));

        return root;
    }

    private void showTab(int index) {
        tab = index;
        page.removeAllViews();

        hudSwitch = null;
        autoBoostSwitch = null;
        autoHudSwitch = null;
        loggingSwitch = null;
        ramMetric = cpuMetric = gpuMetric = thermalMetric = batteryMetric = null;
        loggerPath = null;
        heroMode = heroProfile = heroGpu = heroTemp = null;
        gamesContainer = null;
        profileButtons.clear();

        if (index == 0) dashboardPage();
        else if (index == 1) gamesPage();
        else if (index == 2) loggerPage();
        else settingsPage();

        refreshTelemetry();
        refreshBackendState();
    }

    private void dashboardPage() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(16),dp(15),dp(16),dp(15));
        hero.setBackgroundResource(R.drawable.bg_hero);

        LinearLayout titleRow = row();
        LinearLayout titleText = new LinearLayout(this);
        titleText.setOrientation(LinearLayout.VERTICAL);
        titleText.addView(text("ALLWINNER A333",18,Color.rgb(231,240,238),true));
        titleText.addView(text("KB1001 • Mali-G57",12,Color.rgb(190,215,214),false));
        titleRow.addView(titleText,new LinearLayout.LayoutParams(0,-2,1));
        heroMode = text("STANDBY",12,ACCENT,true);
        titleRow.addView(heroMode);
        hero.addView(titleRow);

        LinearLayout stats = row();
        heroProfile = heroStat("PROFILE","—");
        heroGpu = heroStat("GPU","—");
        heroTemp = heroStat("THERMAL","—");
        stats.addView((View)heroProfile.getParent(),weight());
        stats.addView((View)heroGpu.getParent(),weight());
        stats.addView((View)heroTemp.getParent(),weight());
        hero.addView(stats);
        page.addView(hero,full());

        section("AUTOMATION","Persistent game detection and HUD behavior.");

        autoBoostSwitch = toggleCard(
                "AutoBoost",
                "Switch to the configured game profile when a selected title is foreground.",
                checked -> ctl("auto " + (checked ? "enable" : "disable")));
        page.addView((View)autoBoostSwitch.getParent());

        autoHudSwitch = toggleCard(
                "Auto-show HUD in games",
                "Show the performance HUD when a selected game becomes active.",
                checked -> ctl("overlay auto " + (checked ? "enable" : "disable")));
        page.addView((View)autoHudSwitch.getParent());

        hudSwitch = toggleCard(
                "Performance HUD",
                "The HUD remains active when this window is closed.",
                checked -> {
                    if (checked) showHud();
                    else stopService(new Intent(this,OverlayService.class));
                });
        page.addView((View)hudSwitch.getParent());

        section("GPU PROFILE","Persistent default. AutoBoost can temporarily override it.");

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setBackgroundResource(R.drawable.bg_card);
        group.setPadding(dp(12),dp(8),dp(12),dp(8));

        addProfile(group,"Stock 696 MHz","stock","Factory DVFS range");
        addProfile(group,"Dynamic 744 MHz","dynamic744","744 MHz ceiling with DVFS");
        addProfile(group,"Performance 744 MHz","performance744","Pinned 744 MHz");
        addProfile(group,"Experimental 792 MHz","experimental792","Session only • never used by AutoBoost");

        page.addView(group,full());
    }

    private void addProfile(RadioGroup group,String title,String key,String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        RadioButton rb = new RadioButton(this);
        rb.setText(title);
        rb.setTextColor(Color.rgb(231,240,238));
        rb.setTextSize(14);
        rb.setTag(key);
        rb.setPadding(0,dp(6),0,dp(6));
        profileButtons.put(key,rb);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(rb);
        labels.addView(text(subtitle,10,MUTED,false));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        row.setOnClickListener(v -> rb.performClick());
        rb.setOnClickListener(v -> {
            if (suppressSwitchCallbacks) return;
            if ("experimental792".equals(key)) {
                experimental();
            } else {
                for (RadioButton b : profileButtons.values()) b.setChecked(false);
                rb.setChecked(true);
                ctl("persist " + key);
            }
        });
        group.addView(row);
    }

    private TextView heroStat(String label,String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4),dp(14),dp(4),0);
        TextView l = text(label,9,Color.rgb(175,202,200),true);
        TextView v = text(value,16,Color.WHITE,true);
        box.addView(l);
        box.addView(v);
        return v;
    }

    private void gamesPage() {
        LinearLayout heading = row();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("Game Library",22,Color.rgb(231,240,238),true));
        labels.addView(text("Auto-detected and manually selected AutoBoost targets.",11,MUTED,false));
        heading.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        Button add = button("+",true,v -> showAddGameDialog());
        add.setTextSize(20);
        heading.addView(add,new LinearLayout.LayoutParams(dp(52),dp(48)));
        page.addView(heading);

        gamesContainer = new LinearLayout(this);
        gamesContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(gamesContainer,new LinearLayout.LayoutParams(-1,-2));

        TextView tip = text("Long-press a game to remove it from AutoBoost.",11,MUTED,false);
        tip.setPadding(dp(2),dp(6),0,dp(8));
        page.addView(tip);

        section("DETECTION INTERVAL","Foreground polling stays in the persistent backend.");
        LinearLayout rates = row();
        rates.addView(button("1 sec",false,v -> ctl("auto poll 1")),weight());
        rates.addView(button("2 sec",true,v -> ctl("auto poll 2")),weight());
        rates.addView(button("5 sec",false,v -> ctl("auto poll 5")),weight());
        page.addView(card(rates),full());

        loadGamesInline();
    }

    private void loadGamesInline() {
        if (gamesContainer == null) return;
        gamesContainer.removeAllViews();
        TextView loading = text("Loading game library…",12,MUTED,false);
        loading.setPadding(dp(4),dp(18),0,dp(18));
        gamesContainer.addView(loading);

        io.execute(() -> {
            RootBridge.Result r = RootBridge.get().ctl("game list");
            selectedGames.clear();
            if (r.ok()) {
                for (String line : r.output.split("\\R")) {
                    String pkg = line.trim();
                    if (!pkg.isEmpty()) selectedGames.add(pkg);
                }
            }
            scanLauncherApps();
            runOnUiThread(this::renderGamesInline);
        });
    }

    private void renderGamesInline() {
        if (gamesContainer == null) return;
        gamesContainer.removeAllViews();

        if (selectedGames.isEmpty()) {
            TextView empty = text("No games selected. Tap + to add an app.",13,MUTED,false);
            empty.setPadding(dp(6),dp(22),dp(6),dp(22));
            gamesContainer.addView(card(empty),full());
            return;
        }

        for (String pkg : selectedGames) {
            LauncherApp app = findLauncherApp(pkg);
            gamesContainer.addView(gameRow(
                    app == null ? pkg : app.name,
                    pkg,
                    app == null ? null : app.icon,
                    app != null && app.androidGame
            ),full());
        }
    }

    private View gameRow(String name,String pkg,Drawable icon,boolean detected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),dp(10),dp(12),dp(10));
        row.setBackgroundResource(R.drawable.bg_card);

        ImageView image = new ImageView(this);
        if (icon != null) image.setImageDrawable(icon);
        row.addView(image,new LinearLayout.LayoutParams(dp(42),dp(42)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12),0,0,0);
        labels.addView(text(name,15,Color.rgb(231,240,238),true));
        labels.addView(text((detected ? "Auto-detected • " : "Manual • ") + pkg,10,MUTED,false));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        TextView active = text("●",14,ACCENT,true);
        row.addView(active);

        row.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage("Remove this app from AutoBoost?")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Remove",(d,w) -> removeGame(pkg,detected))
                    .show();
            return true;
        });
        return row;
    }

    private void removeGame(String pkg,boolean autoDetected) {
        io.execute(() -> {
            RootBridge.get().ctl("game remove " + pkg);
            if (autoDetected) {
                SharedPreferences prefs = getSharedPreferences("game_library",MODE_PRIVATE);
                Set<String> ignored = new LinkedHashSet<>(prefs.getStringSet("ignored_games",Collections.emptySet()));
                ignored.add(pkg);
                prefs.edit().putStringSet("ignored_games",ignored).apply();
            }
            runOnUiThread(this::loadGamesInline);
        });
    }

    private void showAddGameDialog() {
        io.execute(() -> {
            scanLauncherApps();
            List<LauncherApp> available = new ArrayList<>();
            for (LauncherApp a : launcherApps) if (!selectedGames.contains(a.pkg)) available.add(a);

            String[] labels = new String[available.size()];
            for (int i=0;i<available.size();i++) labels[i] = available.get(i).name + "\n" + available.get(i).pkg;

            runOnUiThread(() -> {
                if (available.isEmpty()) {
                    Toast.makeText(this,"All launcher apps are already selected.",Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Add app to AutoBoost")
                        .setItems(labels,(d,which) -> addGame(available.get(which)))
                        .setNegativeButton("Cancel",null)
                        .show();
            });
        });
    }

    private void addGame(LauncherApp app) {
        io.execute(() -> {
            RootBridge.Result r = RootBridge.get().ctl("game add " + app.pkg);
            if (r.ok()) {
                SharedPreferences prefs = getSharedPreferences("game_library",MODE_PRIVATE);
                Set<String> ignored = new LinkedHashSet<>(prefs.getStringSet("ignored_games",Collections.emptySet()));
                ignored.remove(app.pkg);
                prefs.edit().putStringSet("ignored_games",ignored).apply();
                runOnUiThread(this::loadGamesInline);
            }
        });
    }

    private void scanLauncherApps() {
        launcherApps.clear();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();

        List<ResolveInfo> list = Build.VERSION.SDK_INT >= 33
                ? pm.queryIntentActivities(launcher,PackageManager.ResolveInfoFlags.of(0))
                : pm.queryIntentActivities(launcher,0);

        Map<String,LauncherApp> unique = new LinkedHashMap<>();
        for (ResolveInfo r : list) {
            if (r.activityInfo == null || r.activityInfo.packageName == null) continue;
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;

            CharSequence cs = r.loadLabel(pm);
            String name = cs == null ? pkg : cs.toString();
            boolean game = false;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg,0);
                if (Build.VERSION.SDK_INT >= 26) game = ai.category == ApplicationInfo.CATEGORY_GAME;
            } catch (Exception ignored) {}

            unique.put(pkg,new LauncherApp(name,pkg,r.loadIcon(pm),game));
        }

        launcherApps.addAll(unique.values());
        launcherApps.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.US)));
    }

    private LauncherApp findLauncherApp(String pkg) {
        for (LauncherApp a : launcherApps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void autoDetectGames() {
        scanLauncherApps();
        SharedPreferences prefs = getSharedPreferences("game_library",MODE_PRIVATE);
        Set<String> ignored = prefs.getStringSet("ignored_games",Collections.emptySet());

        for (LauncherApp app : launcherApps) {
            if (!app.androidGame || ignored.contains(app.pkg)) continue;
            RootBridge.get().ctl("game add " + app.pkg);
        }
    }

    private void loggerPage() {
        section("LIVE PERFORMANCE","A clean device view rather than a raw log dump.");

        ramMetric = metricCard("RAM");
        cpuMetric = metricCard("CPU Policies");
        gpuMetric = metricCard("GPU Clock");
        thermalMetric = metricCard("Thermal");
        batteryMetric = metricCard("Battery");

        page.addView(ramMetric.root,full());
        page.addView(cpuMetric.root,full());
        page.addView(gpuMetric.root,full());
        page.addView(thermalMetric.root,full());
        page.addView(batteryMetric.root,full());

        section("SESSION RECORDING","Record the same telemetry to CSV for profile comparisons.");

        loggingSwitch = toggleCard(
                "Save session to file",
                "Documents/KB1001Performance/logs",
                checked -> ctl("logger file " + (checked ? "on" : "off")));
        page.addView((View)loggingSwitch.getParent());

        LinearLayout rate = row();
        rate.addView(button("1 sec",true,v -> ctl("logger interval 1")),weight());
        rate.addView(button("2 sec",false,v -> ctl("logger interval 2")),weight());
        rate.addView(button("5 sec",false,v -> ctl("logger interval 5")),weight());
        page.addView(card(rate),full());

        loggerPath = text("No active file.",10,MUTED,false);
        loggerPath.setTextIsSelectable(true);
        page.addView(card(loggerPath),full());
    }

    private void settingsPage() {
        section("SOFTWARE","One update operation handles the app and persistent backend together.");

        Button check = button("Check for Update",true,v -> checkForUpdate(false));
        check.setTextSize(14);
        page.addView(card(check),full());

        TextView versions = text(
                "App " + BuildConfig.VERSION_NAME + "\nModule version is checked automatically.",
                11,MUTED,false);
        page.addView(card(versions),full());

        section("ARCHITECTURE","App-first with an optional persistence backend.");
        TextView info = text(
                "The HUD is an Android foreground service and can remain active after this window closes. " +
                "The Magisk module currently provides boot persistence, game detection and privileged telemetry. " +
                "We can migrate those pieces into the app and make the module optional after the foreground-service path proves reliable on this tablet.",
                12,Color.rgb(190,205,202),false);
        page.addView(card(info),full());
    }

    private Switch toggleCard(String title,String subtitle,ToggleAction action) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER_VERTICAL);
        wrapper.setPadding(dp(13),dp(11),dp(13),dp(11));
        wrapper.setBackgroundResource(R.drawable.bg_card);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,15,Color.rgb(231,240,238),true));
        labels.addView(text(subtitle,10,MUTED,false));
        wrapper.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        Switch sw = new Switch(this);
        sw.setOnCheckedChangeListener((b,checked) -> {
            if (!suppressSwitchCallbacks) action.changed(checked);
        });
        wrapper.addView(sw);

        wrapper.setLayoutParams(full());
        return sw;
    }

    private void showHud() {
        if (Settings.canDrawOverlays(this)) {
            Intent i = new Intent(this,OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            return;
        }

        suppressSwitchCallbacks = true;
        if (hudSwitch != null) hudSwitch.setChecked(false);
        suppressSwitchCallbacks = false;

        new AlertDialog.Builder(this)
                .setTitle("Overlay permission required")
                .setMessage(
                        "If Android says this setting is restricted: open App info, tap the top-right menu, choose “Allow restricted settings”, then return and enable Display over other apps.")
                .setPositiveButton("Overlay settings",(d,w) ->
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()))))
                .setNeutralButton("App info",(d,w) ->
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void refreshBackendState() {
        io.execute(() -> {
            RootBridge.Result r = RootBridge.get().ctl("status");
            if (!r.ok()) return;
            Map<String,String> status = parseStatus(r.output);

            runOnUiThread(() -> {
                suppressSwitchCallbacks = true;

                if (autoBoostSwitch != null) autoBoostSwitch.setChecked("1".equals(status.get("Auto boost")));
                if (autoHudSwitch != null) autoHudSwitch.setChecked("1".equals(status.get("Overlay auto")));
                if (loggingSwitch != null) loggingSwitch.setChecked("1".equals(status.get("File logging")));
                if (hudSwitch != null) hudSwitch.setChecked(OverlayService.isRunning());

                String persistent = status.get("Persistent profile");
                if (persistent != null) {
                    RadioButton rb = profileButtons.get(persistent);
                    if (rb != null) {
                        for (RadioButton b : profileButtons.values()) b.setChecked(false);
                        rb.setChecked(true);
                    }
                }

                suppressSwitchCallbacks = false;
            });
        });
    }

    private Map<String,String> parseStatus(String out) {
        Map<String,String> map = new HashMap<>();
        for (String line : out.split("\\R")) {
            int i = line.indexOf(':');
            if (i > 0) map.put(line.substring(0,i).trim(),line.substring(i+1).trim());
        }
        return map;
    }

    private void refreshTelemetry() {
        Map<String,String> m = TelemetryStore.read(this);

        String mode = TelemetryStore.get(m,"mode","waiting");
        String profile = TelemetryStore.get(m,"profile","—");
        String gpu = TelemetryStore.get(m,"gpu_clock_mhz","—");
        String temp = TelemetryStore.get(m,"thermal_max_c","—");

        headerState.setText(mode.toUpperCase(Locale.US) + "  •  " + profile + "  •  GPU " + gpu + " MHz");

        if (heroMode != null) heroMode.setText(mode.toUpperCase(Locale.US));
        if (heroProfile != null) heroProfile.setText(displayProfile(profile));
        if (heroGpu != null) heroGpu.setText(gpu + " MHz");
        if (heroTemp != null) heroTemp.setText(temp + "°C");

        if (tab != 2 || ramMetric == null) return;

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        long total = mi.totalMem/1024/1024;
        long available = mi.availMem/1024/1024;
        long used = Math.max(0,total-available);
        int ramPct = total > 0 ? (int)Math.min(100,used*100/total) : 0;
        ramMetric.set(used + " / " + total + " MB",ramPct + "% used",ramPct);

        CpuPolicies cpu = parseCpuPolicies(TelemetryStore.get(m,"cpu_policies",""));
        cpuMetric.set(
                cpu.peakCurrent + " MHz",
                cpu.summary.isEmpty() ? "CPU policy data unavailable" : cpu.summary,
                cpu.percent);

        int gpuMhz = parseInt(TelemetryStore.get(m,"gpu_clock_mhz","0"));
        int gpuPct = Math.max(0,Math.min(100,Math.round(gpuMhz*100f/792f)));
        gpuMetric.set(
                gpuMhz > 0 ? gpuMhz + " MHz" : "Waiting…",
                "Current devfreq clock • experimental ceiling 792 MHz",
                gpuPct);

        float thermal = parseFloat(TelemetryStore.get(m,"thermal_max_c","0"));
        int thermalPct = Math.max(0,Math.min(100,Math.round(thermal/85f*100)));
        thermalMetric.set(
                String.format(Locale.US,"%.1f °C",thermal),
                "Highest reported thermal zone",
                thermalPct);

        BatteryManager bm = (BatteryManager)getSystemService(BATTERY_SERVICE);
        int batt = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        float battTemp = parseFloat(TelemetryStore.get(m,"battery_temp_c","0"));
        batteryMetric.set(
                batt < 0 ? "—" : batt + "%",
                String.format(Locale.US,"%.1f °C",battTemp),
                Math.max(0,batt));

        if (loggingSwitch != null) {
            suppressSwitchCallbacks = true;
            loggingSwitch.setChecked("1".equals(TelemetryStore.get(m,"file_logging","0")));
            suppressSwitchCallbacks = false;
        }

        if (loggerPath != null) {
            String path = TelemetryStore.get(m,"file_path","");
            loggerPath.setText(path.isEmpty() ? "No active file." : path);
        }
    }

    private String displayProfile(String p) {
        if ("stock".equals(p)) return "Stock 696";
        if ("dynamic744".equals(p)) return "Dynamic 744";
        if ("performance744".equals(p)) return "Performance 744";
        if ("extreme792".equals(p) || "experimental792".equals(p)) return "Experimental 792";
        return p;
    }

    private CpuPolicies parseCpuPolicies(String policies) {
        int peakCurrent = 0;
        int peakMax = 0;
        ArrayList<String> parts = new ArrayList<>();

        for (String item : policies.split(";")) {
            int eq = item.indexOf('=');
            int slash = item.indexOf('/',eq+1);
            int dash = item.indexOf('-',slash+1);
            int at = item.indexOf('@',dash+1);
            if (eq < 0 || slash < 0 || dash < 0) continue;

            try {
                String name = item.substring(0,eq);
                int bracket = name.indexOf('[');
                if (bracket > 0) name = name.substring(0,bracket);

                int cur = Integer.parseInt(item.substring(eq+1,slash).replaceAll("[^0-9]",""));
                int max = Integer.parseInt(item.substring(dash+1,at > dash ? at : item.length()).replaceAll("[^0-9]",""));

                peakCurrent = Math.max(peakCurrent,cur);
                peakMax = Math.max(peakMax,max);
                parts.add(name.replace("policy","P") + " " + cur + "/" + max);
            } catch (Exception ignored) {}
        }

        int percent = peakMax > 0 ? Math.max(0,Math.min(100,peakCurrent*100/peakMax)) : 0;
        return new CpuPolicies(peakCurrent,peakMax,percent,android.text.TextUtils.join(" • ",parts));
    }

    private void quietUpdateCheck() {
        checkForUpdate(true);
    }

    private void checkForUpdate(boolean quiet) {
        io.execute(() -> {
            try {
                UpdateManager.ReleaseInfo info = UpdateManager.check(this);
                releaseInfo = info;

                int appInstalled = UpdateManager.installedAppVersion(this);
                int moduleInstalled = UpdateManager.installedModuleVersion();

                boolean appNew = info.appVersionCode > appInstalled;
                boolean moduleNew = info.moduleVersionCode > moduleInstalled;

                if (!appNew && !moduleNew) {
                    if (!quiet) runOnUiThread(() ->
                            new AlertDialog.Builder(this)
                                    .setTitle("You're up to date")
                                    .setMessage("App " + BuildConfig.VERSION_NAME + " and the installed backend are current.")
                                    .setPositiveButton("OK",null)
                                    .show());
                    return;
                }

                runOnUiThread(() -> {
                    updateBanner.setText("UPDATE AVAILABLE");
                    updateBanner.setVisibility(View.VISIBLE);
                    if (!quiet) showUpdateConfirmation(info);
                });
            } catch (Exception e) {
                if (!quiet) runOnUiThread(() ->
                        new AlertDialog.Builder(this)
                                .setTitle("Update check failed")
                                .setMessage(e.getMessage())
                                .setPositiveButton("OK",null)
                                .show());
            }
        });
    }

    private void showUpdateConfirmation(UpdateManager.ReleaseInfo info) {
        int appInstalled = UpdateManager.installedAppVersion(this);
        int moduleInstalled = UpdateManager.installedModuleVersion();
        boolean appNew = info.appVersionCode > appInstalled;
        boolean moduleNew = info.moduleVersionCode > moduleInstalled;

        StringBuilder message = new StringBuilder("The following updates are ready:\n\n");
        if (appNew) message.append("• App → ").append(info.appVersionName).append("\n");
        if (moduleNew) message.append("• Backend → ").append(info.moduleVersionName).append("\n");
        message.append("\nBoth downloads are SHA-256 verified before installation.");

        new AlertDialog.Builder(this)
                .setTitle("Install update?")
                .setMessage(message.toString())
                .setNegativeButton("Later",null)
                .setPositiveButton("Install",(d,w) -> performCombinedUpdate(info,appNew,moduleNew))
                .show();
    }

    private void performCombinedUpdate(UpdateManager.ReleaseInfo info,boolean appNew,boolean moduleNew) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Updating KB1001 Performance Manager");
        progress.setMessage("Preparing downloads…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        io.execute(() -> {
            try {
                if (moduleNew) {
                    runOnUiThread(() -> progress.setMessage("Downloading backend…"));
                    File module = UpdateManager.download(this,info.module,null);

                    runOnUiThread(() -> progress.setMessage("Installing backend…"));
                    RootBridge.Result result = UpdateManager.installModule(module);
                    if (!result.ok()) throw new IllegalStateException("Backend install failed:\n" + result.output);
                }

                if (appNew) {
                    runOnUiThread(() -> progress.setMessage("Downloading app…"));
                    File apk = UpdateManager.download(this,info.app,null);

                    getSharedPreferences("updates",MODE_PRIVATE)
                            .edit()
                            .putBoolean("pending_reboot",moduleNew)
                            .apply();

                    runOnUiThread(() -> {
                        progress.dismiss();
                        try {
                            UpdateManager.installApk(this,apk);
                        } catch (Exception e) {
                            showError("App install",e.getMessage());
                        }
                    });
                } else {
                    runOnUiThread(() -> {
                        progress.dismiss();
                        askReboot();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    showError("Update failed",e.getMessage());
                });
            }
        });
    }

    private void askReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Restart required")
                .setMessage("The backend update is installed. Reboot now to load it?")
                .setNegativeButton("Later",null)
                .setPositiveButton("Reboot",(d,w) -> io.execute(() -> RootBridge.get().exec("reboot")))
                .show();
    }

    private void checkPendingReboot() {
        SharedPreferences p = getSharedPreferences("updates",MODE_PRIVATE);
        if (!p.getBoolean("pending_reboot",false)) return;

        p.edit().putBoolean("pending_reboot",false).apply();
        new AlertDialog.Builder(this)
                .setTitle("Update installed")
                .setMessage("The app and backend update are installed. Reboot now to finish loading the backend?")
                .setNegativeButton("Later",null)
                .setPositiveButton("Reboot",(d,w) -> io.execute(() -> RootBridge.get().exec("reboot")))
                .show();
    }

    private void showError(String title,String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message == null ? "Unknown error" : message)
                .setPositiveButton("OK",null)
                .show();
    }

    private void experimental() {
        new AlertDialog.Builder(this)
                .setTitle("Experimental 792 MHz")
                .setMessage("Apply 792 MHz for this session? It is not persisted and AutoBoost never selects it.")
                .setNegativeButton("Cancel",(d,w) -> refreshBackendState())
                .setPositiveButton("Apply",(d,w) -> ctl("apply experimental792"))
                .show();
    }

    private void ctl(String command) {
        io.execute(() -> {
            RootBridge.Result r = RootBridge.get().ctl(command);
            runOnUiThread(() -> {
                if (!r.ok()) Toast.makeText(this,"Backend command failed",Toast.LENGTH_LONG).show();
                refreshTelemetry();
                refreshBackendState();
            });
        });
    }

    private MetricUi metricCard(String name) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(13),dp(11),dp(13),dp(11));
        root.setBackgroundResource(R.drawable.bg_card);

        LinearLayout head = row();
        TextView title = text(name,12,MUTED,true);
        TextView value = text("—",18,Color.rgb(231,240,238),true);
        head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        head.addView(value);
        root.addView(head);

        ProgressBar bar = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1,dp(6));
        bp.setMargins(0,dp(8),0,dp(6));
        root.addView(bar,bp);

        TextView detail = text("Waiting for telemetry…",10,MUTED,false);
        root.addView(detail);

        return new MetricUi(root,value,detail,bar);
    }

    private void section(String title,String subtitle) {
        TextView t = text(title,15,Color.rgb(231,240,238),true);
        t.setPadding(0,dp(16),0,dp(2));
        page.addView(t);

        TextView s = text(subtitle,11,MUTED,false);
        s.setPadding(0,0,0,dp(7));
        page.addView(s);
    }

    private LinearLayout card(View child) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(dp(12),dp(10),dp(12),dp(10));
        c.addView(child);
        return c;
    }

    private Button tabButton(String label,int index) {
        return button(label,false,v -> showTab(index));
    }

    private Button button(String label,boolean primary,View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(231,240,238));
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackgroundResource(primary ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        b.setOnClickListener(listener);
        return b;
    }

    private TextView text(String value,int sp,int color,boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1);
        p.setMargins(dp(3),dp(3),dp(3),dp(3));
        return p;
    }

    private LinearLayout.LayoutParams tabWeight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(112),-2);
        p.setMargins(dp(3),dp(3),dp(3),dp(3));
        return p;
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(4),0,dp(4));
        return p;
    }

    private int parseInt(String s) { try { return Integer.parseInt(s); } catch(Exception e) { return 0; } }
    private float parseFloat(String s) { try { return Float.parseFloat(s); } catch(Exception e) { return 0f; } }
    private int dp(int x) { return Math.round(x*getResources().getDisplayMetrics().density); }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refreshTelemetry();
            handler.postDelayed(this,1000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        active = true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        refreshBackendState();
        checkPendingReboot();
        if (tab == 1) loadGamesInline();
    }

    @Override protected void onPause() {
        active = false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private interface ToggleAction { void changed(boolean checked); }

    private static final class MetricUi {
        final LinearLayout root;
        final TextView value;
        final TextView detail;
        final ProgressBar bar;

        MetricUi(LinearLayout root,TextView value,TextView detail,ProgressBar bar) {
            this.root=root;
            this.value=value;
            this.detail=detail;
            this.bar=bar;
        }

        void set(String main,String sub,int percent) {
            value.setText(main);
            detail.setText(sub);
            bar.setProgress(Math.max(0,Math.min(100,percent)));
        }
    }

    private static final class CpuPolicies {
        final int peakCurrent;
        final int peakMax;
        final int percent;
        final String summary;

        CpuPolicies(int peakCurrent,int peakMax,int percent,String summary) {
            this.peakCurrent=peakCurrent;
            this.peakMax=peakMax;
            this.percent=percent;
            this.summary=summary;
        }
    }

    private static final class LauncherApp {
        final String name;
        final String pkg;
        final Drawable icon;
        final boolean androidGame;

        LauncherApp(String name,String pkg,Drawable icon,boolean androidGame) {
            this.name=name;
            this.pkg=pkg;
            this.icon=icon;
            this.androidGame=androidGame;
        }
    }
}
