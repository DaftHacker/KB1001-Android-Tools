package com.dafthacker.kb1001perf;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
    private static final int CPU_COLOR = Color.rgb(77,210,126);
    private static final int GPU_COLOR = Color.rgb(255,151,61);
    private static final int RAM_COLOR = Color.rgb(72,151,255);
    private static final int THERMAL_COOL = Color.rgb(91,205,223);
    private static final int THERMAL_WARM = Color.rgb(255,175,59);
    private static final int THERMAL_HOT = Color.rgb(255,83,79);
    private static final int BATTERY_GOOD = Color.rgb(83,205,109);
    private static final int BATTERY_WARN = Color.rgb(255,207,69);
    private static final int BATTERY_LOW = Color.rgb(255,92,82);
    private static final int GAMES_COLOR = Color.rgb(190,112,255);
    private static final int SETTINGS_COLOR = Color.rgb(102,163,255);
    private static final int SESSION_COLOR = Color.rgb(238,102,190);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout page;
    private final Button[] tabButtons = new Button[3];

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
    private TextView limitValue;
    private TextView limitDetail;

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
        root.setPadding(dp(12),dp(10),dp(12),dp(10));
        root.setBackgroundResource(R.drawable.bg_app);

        root.setOnApplyWindowInsetsListener((v,insets) -> {
            int top=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){
                Insets bars=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
                top=bars.top;
                bottom=bars.bottom;
            }else{
                top=insets.getSystemWindowInsetTop();
                bottom=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(12),top+dp(10),dp(12),bottom+dp(8));
            return insets;
        });
        root.requestApplyInsets();

        PerformanceHeaderView header = new PerformanceHeaderView(this);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1,dp(126));
        hp.setMargins(0,0,0,dp(10));
        root.addView(header,hp);

        LinearLayout tabShell = new LinearLayout(this);
        tabShell.setOrientation(LinearLayout.HORIZONTAL);
        tabShell.setGravity(Gravity.CENTER);
        tabShell.setPadding(dp(5),dp(5),dp(5),dp(5));
        tabShell.setBackground(tabShellBackground());

        tabShell.addView(tabButton("Dashboard",0),tabWeight());
        tabShell.addView(tabButton("Games",1),tabWeight());
        tabShell.addView(tabButton("Settings",2),tabWeight());

        root.addView(tabShell,new LinearLayout.LayoutParams(-1,dp(54)));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
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
        limitValue = null;
        limitDetail = null;
        gamesContainer = null;
        profileButtons.clear();

        updateTabStyles();

        if (index == 0) dashboardPage();
        else if (index == 1) gamesPage();
        else settingsPage();

        refreshTelemetry();
        refreshBackendState();
    }

    private void dashboardPage() {
        section("LIVE PERFORMANCE","Tap CPU or GPU for deeper controls and details.");

        cpuMetric = metricCard("CPU",CPU_COLOR);
        cpuMetric.root.setOnClickListener(v -> showCpuMenu());
        page.addView(cpuMetric.root,full());

        gpuMetric = metricCard("GPU",GPU_COLOR);
        gpuMetric.root.setOnClickListener(v -> showGpuMenu());
        page.addView(gpuMetric.root,full());

        ramMetric = metricCard("RAM",RAM_COLOR);
        page.addView(ramMetric.root,full());

        thermalMetric = metricCard("THERMAL",THERMAL_COOL);
        page.addView(thermalMetric.root,full());

        batteryMetric = metricCard("BATTERY",BATTERY_GOOD);
        page.addView(batteryMetric.root,full());

        section("PERFORMANCE LIMIT","Live estimate based on CPU/GPU utilization, thermal cooling state and power state.");
        page.addView(performanceLimitCard(),full());

        section("SESSION","Capture a full performance session for later comparison.");

        loggingSwitch = toggleCard(
                "Record performance session",
                "Save telemetry to Documents/KB1001Performance/logs.",
                checked -> ctl("logger file " + (checked ? "on" : "off")));
        page.addView((View)loggingSwitch.getParent());

        loggerPath = text("No active recording.",10,MUTED,false);
        loggerPath.setTextIsSelectable(true);
        page.addView(card(loggerPath),full());
    }

    private void showGpuMenu() {
        startActivity(new Intent(this,GpuActivity.class));
    }

    private void showCpuMenu() {
        startActivity(new Intent(this,CpuActivity.class));
    }

    private void gamesPage() {
        LinearLayout heading = row();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("Game Library",22,Color.rgb(231,240,238),true));
        labels.addView(text("Apps that trigger your game HUD and optional performance profile.",11,MUTED,false));
        heading.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        Button add = button("+",true,v -> showAddGameDialog());
        add.setTextSize(20);
        heading.addView(add,new LinearLayout.LayoutParams(dp(52),dp(48)));
        page.addView(heading);

        gamesContainer = new LinearLayout(this);
        gamesContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(gamesContainer,new LinearLayout.LayoutParams(-1,-2));

        TextView tip = text("Long-press a game to remove it from the game list.",11,MUTED,false);
        tip.setPadding(dp(2),dp(6),0,dp(8));
        page.addView(tip);

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
        row.setBackground(metricBackground(GAMES_COLOR));

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
                    .setMessage("Remove this app from the game list?")
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

    private void settingsPage() {
        section("GAME AUTOMATION","Game detection is independent from GPU profile switching.");

        autoHudSwitch = toggleCard(
                "Auto-show HUD in listed games",
                "Show the overlay whenever a listed game is foreground, even if profile boosting is off.",
                checked -> ctl("overlay auto " + (checked ? "enable" : "disable")));
        page.addView((View)autoHudSwitch.getParent());

        autoBoostSwitch = toggleCard(
                "Boost profile in listed games",
                "Temporarily apply the selected game GPU profile, then restore the outside-game profile.",
                checked -> ctl("auto " + (checked ? "enable" : "disable")));
        page.addView((View)autoBoostSwitch.getParent());

        section("HUD","Manual overlay and game-detection responsiveness.");

        hudSwitch = toggleCard(
                "Performance HUD",
                "Start or stop the same colored live-performance view as the dashboard.",
                checked -> {
                    if (checked) showHud();
                    else stopService(new Intent(this,OverlayService.class));
                });
        page.addView((View)hudSwitch.getParent());

        page.addView(overlayScaleCard(),full());

        section("DIAGNOSTICS","Bounded CPU, GPU and combined stress testing with live graphs and thermal safety.");

        Button stress = stressButton("Stress Test",SESSION_COLOR);
        page.addView(stress,full());

        section("SOFTWARE","One update operation handles the app and persistent backend together.");

        Button check = button("Check for Update",true,v -> checkForUpdate(false));
        check.setTextSize(14);
        page.addView(card(check),full());

        TextView versions = text(
                "App " + BuildConfig.VERSION_NAME + "\nBackend version is checked automatically.",
                11,MUTED,false);
        page.addView(card(versions),full());

        section("BACKEND","The Android app owns the experience; the module currently provides boot-persistent privileged execution.");
        TextView info = text(
                "The HUD can stay alive after this window closes. The service/module abstraction is being kept so the persistent module backend can eventually become optional without changing the UI API.",
                11,Color.rgb(190,205,202),false);
        page.addView(card(info),full());
    }

    private View overlayScaleCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13),dp(11),dp(13),dp(11));
        card.setBackground(metricBackground(GPU_COLOR));

        LinearLayout head = row();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("Overlay size",15,Color.rgb(231,240,238),true));
        labels.addView(text("Scales the entire HUD: text, graphs, spacing and controls.",10,MUTED,false));
        head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        float saved = getSharedPreferences("hud",MODE_PRIVATE).getFloat("scale",1f);
        TextView value = text(Math.round(saved*100f)+"%",12,GPU_COLOR,true);
        value.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        head.addView(value,new LinearLayout.LayoutParams(dp(64),-2));
        card.addView(head);

        SeekBar seek = new SeekBar(this);
        seek.setMax(125);
        seek.setProgress(Math.round(saved*100f)-50);
        seek.setPadding(0,dp(8),0,0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                int pct=50+progress;
                value.setText(pct+"%");
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}

            @Override public void onStopTrackingTouch(SeekBar bar) {
                int pct=50+bar.getProgress();
                float scale=pct/100f;
                getSharedPreferences("hud",MODE_PRIVATE).edit().putFloat("scale",scale).apply();

                if(OverlayService.isRunning()) {
                    stopService(new Intent(MainActivity.this,OverlayService.class));
                    handler.postDelayed(() -> {
                        Intent restart=new Intent(MainActivity.this,OverlayService.class);
                        if(Build.VERSION.SDK_INT>=26) startForegroundService(restart);
                        else startService(restart);
                    },120);
                }
            }
        });
        card.addView(seek,new LinearLayout.LayoutParams(-1,-2));
        return card;
    }

    private Button stressButton(String label,int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(Color.rgb(236,244,242));
        b.setBackground(metricBackground(color));
        b.setOnClickListener(v -> startActivity(new Intent(this,StressTestActivity.class)));
        return b;
    }

    private Switch toggleCard(String title,String subtitle,ToggleAction action) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER_VERTICAL);
        wrapper.setPadding(dp(13),dp(11),dp(13),dp(11));
        wrapper.setBackground(metricBackground(SETTINGS_COLOR));

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

        String profile = TelemetryStore.get(m,"profile","—");
        if (tab != 0 || ramMetric == null) return;

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        long total = mi.totalMem/1024/1024;
        long available = mi.availMem/1024/1024;
        long used = Math.max(0,total-available);
        int ramPct = total > 0 ? (int)Math.min(100,used*100/total) : 0;
        ramMetric.set(used + " / " + total + " MB",ramPct + "% used",ramPct);

        CpuPolicies cpu = parseCpuPolicies(TelemetryStore.get(m,"cpu_policies",""));
        int cpuUtil = Math.max(0,Math.min(100,parseInt(TelemetryStore.get(m,"cpu_util_pct","0"))));
        String coreUtil = TelemetryStore.get(m,"cpu_core_util","");
        cpuMetric.set(
                cpuUtil + "% • " + cpu.peakCurrent + " MHz",
                (cpu.summary.isEmpty() ? "CPU policy data unavailable" : cpu.summary) +
                        (coreUtil.isEmpty() ? "" : " • cores " + coreUtil.replace(';',' ')),
                cpuUtil);

        int gpuMhz = parseInt(TelemetryStore.get(m,"gpu_clock_mhz","0"));
        int gpuUtil = Math.max(0,Math.min(100,parseInt(TelemetryStore.get(m,"gpu_util_pct","0"))));
        gpuMetric.set(
                gpuUtil + "% • " + (gpuMhz > 0 ? gpuMhz + " MHz" : "Waiting…"),
                "Real Mali utilization • current devfreq clock",
                gpuUtil);

        float thermal = parseFloat(TelemetryStore.get(m,"thermal_max_c","0"));
        int thermalPct = Math.max(0,Math.min(100,Math.round(thermal/85f*100)));
        int thermalColor = thermal >= 70f ? THERMAL_HOT : (thermal >= 55f ? THERMAL_WARM : THERMAL_COOL);
        thermalMetric.setAccent(thermalColor);
        thermalMetric.set(
                String.format(Locale.US,"%.1f °C",thermal),
                thermal < 55f ? "Cool • highest reported thermal zone" :
                        (thermal < 70f ? "Warm • highest reported thermal zone" : "Hot • highest reported thermal zone"),
                thermalPct);

        if(limitValue != null && limitDetail != null){
            boolean throttling = "1".equals(TelemetryStore.get(m,"thermal_throttling","0"));
            boolean plugged = "1".equals(TelemetryStore.get(m,"power_online","0"));
            int fps = parseInt(TelemetryStore.get(m,"fps","0"));
            String label;
            String detail;
            int color;
            if(throttling){
                label="THERMAL LIMITED";
                detail="A kernel cooling device is actively limiting performance.";
                color=THERMAL_HOT;
            }else if(cpuUtil>=88 && gpuUtil<85){
                label="CPU LIMITED";
                detail="CPU "+cpuUtil+"% • GPU "+gpuUtil+"% • GPU still has headroom" + (fps>0?" • "+fps+" FPS":"");
                color=CPU_COLOR;
            }else if(gpuUtil>=90 && cpuUtil<90){
                label="GPU LIMITED";
                detail="GPU "+gpuUtil+"% • CPU "+cpuUtil+"%" + (fps>0?" • "+fps+" FPS":"");
                color=GPU_COLOR;
            }else if(cpuUtil>=88 && gpuUtil>=88){
                label="SYSTEM SATURATED";
                detail="CPU and GPU are both heavily loaded" + (fps>0?" • "+fps+" FPS":"");
                color=SESSION_COLOR;
            }else{
                label="HEADROOM";
                detail="CPU "+cpuUtil+"% • GPU "+gpuUtil+"% • "+(plugged?"USB power":"battery power");
                color=ACCENT;
            }
            limitValue.setText(label);
            limitValue.setTextColor(color);
            limitDetail.setText(detail);
        }

        BatteryManager bm = (BatteryManager)getSystemService(BATTERY_SERVICE);
        int batt = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        float battTemp = parseFloat(TelemetryStore.get(m,"battery_temp_c","0"));
        int batteryColor = batt >= 50 ? BATTERY_GOOD : (batt >= 20 ? BATTERY_WARN : BATTERY_LOW);
        batteryMetric.setAccent(batteryColor);
        batteryMetric.set(
                batt < 0 ? "—" : batt + "%",
                String.format(Locale.US,"%.1f °C battery",battTemp),
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

                runOnUiThread(() -> showUpdateConfirmation(info));
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
                            .putInt("pending_reboot_app_version",info.appVersionCode)
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
        int targetVersion = p.getInt("pending_reboot_app_version",0);
        if (targetVersion > 0 && UpdateManager.installedAppVersion(this) < targetVersion) return;

        p.edit()
                .putBoolean("pending_reboot",false)
                .remove("pending_reboot_app_version")
                .apply();
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

    private View performanceLimitCard() {
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(12),dp(14),dp(12));
        root.setBackground(metricBackground(ACCENT));
        limitValue=text("COLLECTING…",18,ACCENT,true);
        limitDetail=text("Waiting for utilization telemetry.",10,MUTED,false);
        limitDetail.setPadding(0,dp(3),0,0);
        root.addView(limitValue);
        root.addView(limitDetail);
        return root;
    }

    private MetricUi metricCard(String name,int accent) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(13),dp(12),dp(13),dp(12));
        root.setBackground(metricBackground(accent));
        root.setClickable(true);
        root.setFocusable(true);

        LinearLayout head = row();
        TextView title = text(name,12,accent,true);
        TextView value = text("—",20,Color.rgb(231,240,238),true);
        head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        head.addView(value);
        root.addView(head);

        SparklineView graph = new SparklineView(this);
        graph.setAccentColor(accent);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1,dp(72));
        gp.setMargins(0,dp(8),0,dp(5));
        root.addView(graph,gp);

        TextView detail = text("Collecting telemetry…",10,MUTED,false);
        root.addView(detail);

        return new MetricUi(root,value,detail,graph,title);
    }

    private GradientDrawable metricBackground(int accent) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(13,23,22));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1),Color.argb(95,Color.red(accent),Color.green(accent),Color.blue(accent)));
        return bg;
    }

    private void section(String title,String subtitle) {
        int color=ACCENT;
        String key=title.toUpperCase(Locale.US);
        if(key.contains("SESSION")) color=SESSION_COLOR;
        else if(key.contains("GAME")) color=GAMES_COLOR;
        else if(key.contains("HUD")) color=GPU_COLOR;
        else if(key.contains("SOFTWARE")) color=CPU_COLOR;
        else if(key.contains("BACKEND")) color=SETTINGS_COLOR;

        TextView t = text(title,15,color,true);
        t.setPadding(dp(2),dp(17),0,dp(2));
        page.addView(t);

        TextView s = text(subtitle,11,MUTED,false);
        s.setPadding(dp(2),0,0,dp(7));
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
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(MUTED);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(6),0,dp(6),0);
        b.setOnClickListener(v -> showTab(index));
        tabButtons[index]=b;
        return b;
    }

    private void updateTabStyles() {
        int[] colors={ACCENT,GAMES_COLOR,SETTINGS_COLOR};
        for(int i=0;i<tabButtons.length;i++){
            Button b=tabButtons[i];
            if(b==null)continue;
            boolean selected=i==tab;
            b.setTextColor(selected?Color.rgb(6,15,16):MUTED);
            b.setBackground(tabBackground(colors[i],selected));
        }
    }

    private GradientDrawable tabShellBackground() {
        GradientDrawable g=new GradientDrawable();
        g.setColor(Color.rgb(10,18,18));
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1),Color.rgb(31,51,50));
        return g;
    }

    private GradientDrawable tabBackground(int color,boolean selected) {
        GradientDrawable g=new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                selected
                        ? new int[]{color,blend(color,Color.WHITE,.12f)}
                        : new int[]{Color.rgb(17,28,27),Color.rgb(12,21,21)});
        g.setCornerRadius(dp(14));
        if(!selected) g.setStroke(dp(1),Color.argb(70,Color.red(color),Color.green(color),Color.blue(color)));
        return g;
    }

    private int blend(int a,int b,float amount){
        float x=Math.max(0f,Math.min(1f,amount));
        int r=Math.round(Color.red(a)*(1f-x)+Color.red(b)*x);
        int g=Math.round(Color.green(a)*(1f-x)+Color.green(b)*x);
        int bl=Math.round(Color.blue(a)*(1f-x)+Color.blue(b)*x);
        return Color.rgb(r,g,bl);
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-1,1);
        p.setMargins(dp(2),0,dp(2),0);
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
        final SparklineView graph;
        final TextView title;

        MetricUi(LinearLayout root,TextView value,TextView detail,SparklineView graph,TextView title) {
            this.root=root;
            this.value=value;
            this.detail=detail;
            this.graph=graph;
            this.title=title;
        }

        void set(String main,String sub,int valueForGraph) {
            value.setText(main);
            detail.setText(sub);
            graph.addValue(Math.max(0,Math.min(100,valueForGraph)));
        }

        void setAccent(int color) {
            graph.setAccentColor(color);
            title.setTextColor(color);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(13,23,22));
            bg.setCornerRadius(16f * root.getResources().getDisplayMetrics().density);
            bg.setStroke(
                    Math.max(1,(int)root.getResources().getDisplayMetrics().density),
                    Color.argb(95,Color.red(color),Color.green(color),Color.blue(color)));
            root.setBackground(bg);
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
