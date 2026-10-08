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
    private final ExecutorService backendIo = Executors.newSingleThreadExecutor();
    private final ExecutorService scanIo = Executors.newSingleThreadExecutor();
    private final ExecutorService updateIo = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout page;
    private FrameLayout pageHost;
    private final LinearLayout[] tabPages=new LinearLayout[3];
    private final ScrollView[] tabScrolls=new ScrollView[3];
    private final boolean[] tabBuilt=new boolean[3];
    private final Button[] tabButtons = new Button[3];

    private Switch hudSwitch;
    private Switch fpsHudSwitch;
    private Switch autoBoostSwitch;
    private Switch loggingSwitch;

    private MetricUi ramMetric;
    private MetricUi cpuMetric;
    private MetricUi gpuMetric;
    private MetricUi thermalMetric;
    private MetricUi batteryMetric;
    private TextView loggerPath;
    private TextView limitValue;
    private TextView limitDetail;
    private TextView backendHealthValue;
    private TextView cpuOcSupportValue;

    private LinearLayout gamesContainer;
    private final Set<String> selectedGames = new LinkedHashSet<>();
    private final Set<String> metricsEnabledGames = new LinkedHashSet<>();
    private final Set<String> fpsEnabledGames = new LinkedHashSet<>();
    private final List<LauncherApp> launcherApps = new ArrayList<>();

    private final Map<String,RadioButton> profileButtons = new LinkedHashMap<>();
    private UpdateManager.ReleaseInfo releaseInfo;

    private int tab;
    private boolean active;
    private boolean suppressSwitchCallbacks;
    private boolean overlayStateReceiverRegistered;
    private boolean metricsTogglePending;
    private boolean fpsTogglePending;
    private String pendingOverlayPermissionType;

    // Fresh-install root startup is deliberately serialized. Android runtime
    // permission dialogs (notably POST_NOTIFICATIONS) can temporarily own the
    // foreground window; launching MagiskSU underneath them can cause the
    // Superuser request to fail without ever presenting its dialog.
    private boolean rootStartupComplete;
    private boolean rootStartupInFlight;
    private boolean rootStartupRequested;
    private boolean hasWindowFocus;
    private boolean startupUpdateCheckStarted;

    private final BroadcastReceiver overlayStateReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(intent==null)return;
            String type=intent.getStringExtra("type");
            boolean enabled=intent.getBooleanExtra("enabled",false);
            if("metrics".equals(type) && hudSwitch!=null){
                metricsTogglePending=false;
                setSwitchStateSilently(hudSwitch,enabled);
                hudSwitch.setEnabled(true);
            }
            if("fps".equals(type) && fpsHudSwitch!=null){
                fpsTogglePending=false;
                setSwitchStateSilently(fpsHudSwitch,enabled);
                fpsHudSwitch.setEnabled(true);
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TelemetryStore.ensureSnapshot(this);
        AppStateCache.initialize(this);

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }

        setContentView(buildUi());
        applyMainKeepAwake();
        showTab(0);

        // Do not touch the privileged backend here. Root acquisition is
        // deferred until this Activity owns the foreground window and any
        // Android runtime-permission dialog has completed.
        rootStartupRequested=true;

        startupUpdateCheck();
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
        tabShell.addView(tabButton("Overlay",1),tabWeight());
        tabShell.addView(tabButton("Settings",2),tabWeight());

        root.addView(tabShell,new LinearLayout.LayoutParams(-1,dp(54)));

        pageHost=new FrameLayout(this);
        for(int i=0;i<tabPages.length;i++){
            ScrollView scroller=new ScrollView(this);
            scroller.setFillViewport(true);
            LinearLayout content=new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(0,dp(8),0,dp(28));
            scroller.addView(content);
            scroller.setVisibility(View.GONE);
            tabPages[i]=content;
            tabScrolls[i]=scroller;
            pageHost.addView(scroller,new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        root.addView(pageHost,new LinearLayout.LayoutParams(-1,0,1));

        return root;
    }

    private void showTab(int index) {
        tab=index;

        for(int i=0;i<tabScrolls.length;i++){
            if(tabScrolls[i]!=null){
                tabScrolls[i].setVisibility(i==index?View.VISIBLE:View.GONE);
            }
        }

        page=tabPages[index];
        updateTabStyles();

        if(!tabBuilt[index]){
            if(index==0)dashboardPage();
            else if(index==1)overlayPage();
            else settingsPage();
            tabBuilt[index]=true;
        }

        refreshTelemetry();
        if(rootStartupComplete && !AppStateCache.statusFresh(this,5000)){
            refreshBackendState();
        }
    }

    private void dashboardPage() {
        section("LIVE PERFORMANCE","Tap CPU, GPU, Thermal, or Battery for deeper controls and details.");

        cpuMetric = metricCard("CPU",CPU_COLOR);
        cpuMetric.root.setOnClickListener(v -> showCpuMenu());
        page.addView(cpuMetric.root,full());

        gpuMetric = metricCard("GPU",GPU_COLOR);
        gpuMetric.root.setOnClickListener(v -> showGpuMenu());
        page.addView(gpuMetric.root,full());

        ramMetric = metricCard("RAM",RAM_COLOR);
        page.addView(ramMetric.root,full());

        thermalMetric = metricCard("THERMAL",THERMAL_COOL);
        thermalMetric.root.setOnClickListener(v -> showThermalMenu());
        page.addView(thermalMetric.root,full());

        batteryMetric = metricCard("BATTERY",BATTERY_GOOD);
        batteryMetric.root.setOnClickListener(v -> showBatteryMenu());
        page.addView(batteryMetric.root,full());

        section("PERFORMANCE LIMIT","Live estimate based on CPU/GPU utilization, thermal cooling state and power state.");
        page.addView(performanceLimitCard(),full());

        section("SESSION","Capture a full performance session for later comparison.");

        loggingSwitch = toggleCard(
                "Record performance session",
                "Save telemetry to Documents/KB1001Performance/logs.",
                checked -> {
                    AppStateCache.setFileLogging(this,checked);
                    ctl("logger file " + (checked ? "on" : "off"));
                });
        setSwitchImmediately(loggingSwitch,AppStateCache.fileLogging(this));
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

    private void showThermalMenu() {
        startActivity(new Intent(this,ThermalActivity.class));
    }

    private void showBatteryMenu() {
        startActivity(new Intent(this,BatteryActivity.class));
    }

    private void overlayPage() {
        LinearLayout heading=row();
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("Overlay Manager",22,Color.rgb(231,240,238),true));
        labels.addView(text("Manual overlay controls, appearance, and per-game automation.",11,MUTED,false));
        heading.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        page.addView(heading);

        section("OVERLAYS","Manual controls. Per-game automatic behavior is configured below.");

        hudSwitch = toggleCard(
                "Metrics overlay",
                "Manual hold. ON stays visible through game launches/exits; OFF still allows per-game rules to show it.",
                checked -> setManualOverlay("metrics",checked,hudSwitch));
        setSwitchImmediately(hudSwitch,AppStateCache.manualMetrics(this));
        page.addView((View)hudSwitch.getParent());

        fpsHudSwitch = toggleCard(
                "FPS counter",
                "Manual hold. ON stays visible through game launches/exits; OFF still allows per-game FPS rules.",
                checked -> setManualOverlay("fps",checked,fpsHudSwitch));
        setSwitchImmediately(fpsHudSwitch,AppStateCache.manualFps(this));
        page.addView((View)fpsHudSwitch.getParent());

        page.addView(overlayScaleCard(),full());
        page.addView(fpsOverlayAppearanceCard(),full());

        section("PER-GAME OVERLAYS",
                "Newly detected games start with both overlays off. Saved choices are preserved when you refresh.");

        LinearLayout libraryHeading=row();
        LinearLayout libraryLabels=new LinearLayout(this);
        libraryLabels.setOrientation(LinearLayout.VERTICAL);
        libraryLabels.addView(text("Game Library",18,Color.rgb(231,240,238),true));
        libraryLabels.addView(text("Enable Metrics and FPS independently for each game.",10,MUTED,false));
        libraryHeading.addView(libraryLabels,new LinearLayout.LayoutParams(0,-2,1));

        Button refresh=button("↻",true,v -> refreshDetectedGames());
        refresh.setTextSize(19);
        refresh.setContentDescription("Refresh detected games");
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(52),dp(48));
        rp.setMargins(0,0,dp(6),0);
        libraryHeading.addView(refresh,rp);

        Button add=button("+",true,v -> showAddGamePicker());
        add.setTextSize(20);
        add.setContentDescription("Add app");
        libraryHeading.addView(add,new LinearLayout.LayoutParams(dp(52),dp(48)));
        page.addView(libraryHeading,full());

        gamesContainer=new LinearLayout(this);
        gamesContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(gamesContainer,new LinearLayout.LayoutParams(-1,-2));

        TextView tip=text("Long-press a game to remove it from the library.",11,MUTED,false);
        tip.setPadding(dp(2),dp(6),0,dp(8));
        page.addView(tip);

        loadGamesInline();
    }

    private void loadGamesInline() {
        if (gamesContainer == null) return;

        AppStateCache.GameSnapshot cached=AppStateCache.gameSnapshot(this);
        if(cached.loaded){
            selectedGames.clear();
            selectedGames.addAll(cached.games);
            metricsEnabledGames.clear();
            metricsEnabledGames.addAll(cached.metricsGames);
            fpsEnabledGames.clear();
            fpsEnabledGames.addAll(cached.fpsGames);
            renderGamesInline();
        }else{
            gamesContainer.removeAllViews();
            TextView loading = text("Loading game library…",12,MUTED,false);
            loading.setPadding(dp(4),dp(18),0,dp(18));
            gamesContainer.addView(loading);
        }

        if(AppStateCache.gamesFresh(this,5000)){
            ensureLauncherMetadataAsync();
            return;
        }

        io.execute(() -> {
            RootBridge.Result snapshot=RootBridge.get().ctl("game snapshot");

            selectedGames.clear();
            metricsEnabledGames.clear();
            fpsEnabledGames.clear();

            if(snapshot.ok()){
                for(String line:snapshot.output.split("\\R")){
                    if(line.startsWith("game=")) selectedGames.add(line.substring(5));
                    else if(line.startsWith("metrics=")) metricsEnabledGames.add(line.substring(8));
                    else if(line.startsWith("fps=")) fpsEnabledGames.add(line.substring(4));
                }
                AppStateCache.updateGameSnapshot(
                        this,selectedGames,metricsEnabledGames,fpsEnabledGames);
            }

            runOnUiThread(this::renderGamesInline);
            ensureLauncherMetadataAsync();
        });
    }

    private void ensureLauncherMetadataAsync(){
        if(!launcherApps.isEmpty())return;
        scanIo.execute(() -> {
            scanSelectedGameMetadata();
            runOnUiThread(() -> {
                if(gamesContainer!=null)renderGamesInline();
            });
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
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),dp(10),dp(10),dp(10));
        row.setBackground(metricBackground(GAMES_COLOR));

        ImageView image=new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if(icon!=null)image.setImageDrawable(icon);
        row.addView(image,new LinearLayout.LayoutParams(dp(46),dp(46)));

        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12),0,dp(6),0);
        labels.addView(text(name,15,Color.rgb(231,240,238),true));
        labels.addView(text((detected?"Auto-detected • ":"Manual • ")+pkg,9,MUTED,false));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        controls.addView(gameOverlayControl(
                "METRICS",pkg,"metrics",metricsEnabledGames.contains(pkg)),
                new LinearLayout.LayoutParams(dp(78),dp(58)));

        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(68),dp(58));
        fp.setMargins(dp(5),0,0,0);
        controls.addView(gameOverlayControl(
                "FPS",pkg,"fps",fpsEnabledGames.contains(pkg)),fp);

        row.addView(controls);

        row.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage("Remove this app from the game library?")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Remove",(d,w)->removeGame(pkg,detected))
                    .show();
            return true;
        });
        return row;
    }

    private View gameOverlayControl(String label,String pkg,String type,boolean enabled){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title=text(label,8,MUTED,true);
        title.setGravity(Gravity.CENTER);
        box.addView(title,new LinearLayout.LayoutParams(-1,dp(20)));

        Button button=new Button(this);
        button.setAllCaps(false);
        button.setTextSize(9);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(5),0,dp(5),0);
        styleGameOverlayButton(button,enabled);
        button.setContentDescription(label+" overlay for "+pkg);

        button.setOnClickListener(v -> {
            boolean current=Boolean.TRUE.equals(button.getTag());
            boolean next=!current;
            styleGameOverlayButton(button,next);
            button.setEnabled(false);
            AppStateCache.setGameOverlay(this,pkg,type,next);
            setGameOverlayEnabled(pkg,type,next,current,button);
        });

        box.addView(button,new LinearLayout.LayoutParams(-1,dp(32)));
        return box;
    }

    private void styleGameOverlayButton(Button button,boolean enabled){
        button.setTag(enabled);
        button.setText(enabled?"ON":"OFF");
        button.setTextColor(enabled?Color.rgb(7,28,13):Color.rgb(220,225,224));

        GradientDrawable bg=new GradientDrawable();
        bg.setCornerRadius(dp(999));
        bg.setColor(enabled?CPU_COLOR:Color.rgb(48,58,58));
        bg.setStroke(dp(1),enabled?Color.rgb(120,235,151):Color.rgb(92,108,106));
        button.setBackground(bg);
    }

    private void setGameOverlayEnabled(
            String pkg,String type,boolean enabled,boolean previous,Button button){
        io.execute(() -> {
            String writeCommand="game "+type+"-"+(enabled?"enable ":"disable ")+pkg;
            RootBridge.Result write=RootBridge.get().ctl(writeCommand);
            boolean verified=write.ok()&&
                    (enabled?"enabled".equals(write.output.trim())
                            :"disabled".equals(write.output.trim()));

            if(verified){
                Set<String> set="metrics".equals(type)?metricsEnabledGames:fpsEnabledGames;
                if(enabled)set.add(pkg); else set.remove(pkg);
                AppStateCache.setGameOverlay(this,pkg,type,enabled);
            }

            runOnUiThread(() -> {
                if(verified){
                    styleGameOverlayButton(button,enabled);
                }else{
                    AppStateCache.setGameOverlay(this,pkg,type,previous);
                    styleGameOverlayButton(button,previous);
                    Toast.makeText(this,
                            "Could not verify saved "+type.toUpperCase(Locale.US)+" overlay setting.",
                            Toast.LENGTH_SHORT).show();
                }
                button.setEnabled(true);
            });
        });
    }

    private void removeGame(String pkg,boolean autoDetected) {
        io.execute(() -> {
            RootBridge.get().ctl("game remove " + pkg);
            AppStateCache.removeGame(this,pkg);
            if (autoDetected) {
                SharedPreferences prefs = getSharedPreferences("game_library",MODE_PRIVATE);
                Set<String> ignored = new LinkedHashSet<>(prefs.getStringSet("ignored_games",Collections.emptySet()));
                ignored.add(pkg);
                prefs.edit().putStringSet("ignored_games",ignored).apply();
            }
            runOnUiThread(this::loadGamesInline);
        });
    }

    private void showAddGamePicker() {
        startActivity(new Intent(this,GamePickerActivity.class));
    }

    private void refreshDetectedGames() {
        if(gamesContainer!=null){
            gamesContainer.removeAllViews();
            TextView scanning=text("Scanning installed apps for games…",12,MUTED,false);
            scanning.setPadding(dp(4),dp(18),0,dp(18));
            gamesContainer.addView(scanning);
        }

        Set<String> before=new LinkedHashSet<>(selectedGames);
        scanIo.execute(() -> {
            launcherApps.clear();
            scanLauncherApps();

            io.execute(() -> {
                autoDetectGames();

                RootBridge.Result snapshot=RootBridge.get().ctl("game snapshot");
                Set<String> after=new LinkedHashSet<>();
                Set<String> metrics=new LinkedHashSet<>();
                Set<String> fps=new LinkedHashSet<>();
                if(snapshot.ok()){
                    for(String line:snapshot.output.split("\\R")){
                        if(line.startsWith("game=")) after.add(line.substring(5));
                        else if(line.startsWith("metrics=")) metrics.add(line.substring(8));
                        else if(line.startsWith("fps=")) fps.add(line.substring(4));
                    }
                    AppStateCache.updateGameSnapshot(this,after,metrics,fps);
                }

                Set<String> addedGames=new LinkedHashSet<>(after);
                addedGames.removeAll(before);
                int added=addedGames.size();

                runOnUiThread(() -> {
                    Toast.makeText(this,
                            added>0 ? "Detected "+added+" new game"+(added==1?"":"s") : "Game scan complete",
                            Toast.LENGTH_SHORT).show();
                    loadGamesInline();
                });
            });
        });
    }

    private void scanSelectedGameMetadata() {
        launcherApps.clear();
        PackageManager pm=getPackageManager();
        for(String pkg:new LinkedHashSet<>(selectedGames)){
            try{
                ApplicationInfo ai=pm.getApplicationInfo(pkg,0);
                CharSequence label=pm.getApplicationLabel(ai);
                String name=label==null?pkg:label.toString();
                boolean game=Build.VERSION.SDK_INT>=26 &&
                        ai.category==ApplicationInfo.CATEGORY_GAME;
                Drawable icon=pm.getApplicationIcon(ai);
                launcherApps.add(new LauncherApp(name,pkg,icon,game));
            }catch(Exception ignored){
                launcherApps.add(new LauncherApp(pkg,pkg,null,false));
            }
        }
        launcherApps.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.US)));
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
        if(launcherApps.isEmpty()) scanLauncherApps();
        SharedPreferences prefs = getSharedPreferences("game_library",MODE_PRIVATE);
        Set<String> ignored = prefs.getStringSet("ignored_games",Collections.emptySet());

        StringBuilder batch=new StringBuilder("game add-batch");
        int count=0;
        for (LauncherApp app : launcherApps) {
            if (!app.androidGame || ignored.contains(app.pkg)) continue;
            batch.append(' ').append(app.pkg);
            count++;
        }
        if(count>0) RootBridge.get().ctl(batch.toString());
    }

    private void settingsPage() {
        section("GAME PERFORMANCE","Automatic GPU profile behavior for apps in the Overlay game library.");

        autoBoostSwitch=toggleCard(
                "Boost profile in listed games",
                "Temporarily apply the selected game GPU profile, then restore the outside-game profile.",
                checked -> {
                    AppStateCache.setAutoBoost(this,checked);
                    ctl("auto "+(checked?"enable":"disable"));
                });
        setSwitchImmediately(autoBoostSwitch,AppStateCache.autoBoost(this));
        page.addView((View)autoBoostSwitch.getParent());

        section("DIAGNOSTICS","Repeatable CPU, GPU and system benchmark scores with live utilization, clocks and thermal safety.");

        Button stress=stressButton("System Stress & Test",SESSION_COLOR);
        page.addView(stress,full());

        section("DISPLAY & SLEEP","Android timeout presets and KB1001 screen-awake behavior.");

        Button displaySleep=button("Display & Sleep Settings",true,
                v->startActivity(new Intent(this,DisplaySleepActivity.class)));
        displaySleep.setTextSize(13);
        page.addView(card(displaySleep),full());

        section("SOFTWARE","");

        LinearLayout softwareStatus=new LinearLayout(this);
        softwareStatus.setOrientation(LinearLayout.VERTICAL);

        TextView currentVersion=text(
                "Current version: "+BuildConfig.VERSION_NAME,
                10,MUTED,false);
        softwareStatus.addView(currentVersion);

        backendHealthValue=text("Backend: Checking…",10,MUTED,false);
        backendHealthValue.setPadding(0,dp(2),0,0);
        softwareStatus.addView(backendHealthValue);
        cpuOcSupportValue=text("CPU OC support: Checking…",10,MUTED,false);
        cpuOcSupportValue.setPadding(0,dp(2),0,0);
        softwareStatus.addView(cpuOcSupportValue);
        page.addView(softwareStatus,full());

        Button ocSupport=button("CPU OC Support",true,v -> showCpuOcSupportStatus());
        ocSupport.setTextSize(13);
        page.addView(card(ocSupport),full());

        Button check=button("Check for Update",true,v -> checkForUpdate(false));
        check.setTextSize(14);
        check.setTextColor(Color.rgb(6,28,12));
        check.setBackground(tabBackground(CPU_COLOR,true));
        page.addView(card(check),full());

        refreshBackendHealth();
        refreshCpuOcSupport();
    }

    private void refreshCpuOcSupport(){
        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("cpu oc-status");
            String out=r.output==null?"":r.output;

            if(!r.ok()){
                final boolean rootRequired=r.superuserRequired();
                final String detail=rootRequired?"Superuser access required":compactBackendError(out);
                runOnUiThread(()->{
                    if(cpuOcSupportValue==null)return;
                    cpuOcSupportValue.setText(rootRequired
                            ?"CPU OC support: Superuser access required"
                            :"CPU OC support: Backend unavailable • "+detail);
                    cpuOcSupportValue.setTextColor(BATTERY_WARN);
                });
                return;
            }

            boolean installed1608=out.contains("oc_1608_apply_supported=1") &&
                    out.contains("vendor_boot_state=verified_cpu4_1608_patch");
            boolean installed1560=out.contains("oc_apply_supported=1") &&
                    (out.contains("vendor_boot_state=verified_cpu4_1560_patch") ||
                     out.contains("vendor_boot_state=verified_cpu4_1608_patch"));
            boolean installed=installed1608||installed1560;
            boolean stock=out.contains("vendor_boot_state=verified_stock");
            runOnUiThread(()->{
                if(cpuOcSupportValue==null)return;
                if(installed1608){
                    cpuOcSupportValue.setText("CPU OC support: Installed • CPU4 1608 MHz validated");
                    cpuOcSupportValue.setTextColor(CPU_COLOR);
                }else if(installed1560){
                    cpuOcSupportValue.setText("CPU OC support: Installed • CPU4 1560 MHz validated");
                    cpuOcSupportValue.setTextColor(CPU_COLOR);
                }else if(stock){
                    cpuOcSupportValue.setText("CPU OC support: One-time vendor_boot enablement required");
                    cpuOcSupportValue.setTextColor(BATTERY_WARN);
                }else{
                    cpuOcSupportValue.setText("CPU OC support: Unverified / unavailable");
                    cpuOcSupportValue.setTextColor(MUTED);
                }
            });
        });
    }


    private void showCpuOcSupportStatus(){
        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("cpu oc-status");
            String out=r.output==null?"":r.output;

            if(!r.ok()){
                if(r.superuserRequired()){
                    runOnUiThread(()->new AlertDialog.Builder(this)
                            .setTitle("CPU OC Support")
                            .setMessage("Superuser access required")
                            .setPositiveButton("Close",null)
                            .show());
                    return;
                }
                String detail=out.trim();
                if(detail.isEmpty())detail=BackendManager.lastInstallError(this).trim();
                if(detail.isEmpty())detail="No diagnostic output was returned.";
                final String failure=detail;
                runOnUiThread(()->new AlertDialog.Builder(this)
                        .setTitle("CPU OC Support")
                        .setMessage("Backend error. Details were saved to the app data diagnostics folder.\n\n"+
                                failure)
                        .setPositiveButton("Close",null)
                        .show());
                return;
            }

            boolean stage14=out.contains("vendor_boot_state=verified_cpu0_1512_patch");
            boolean stage13=out.contains("vendor_boot_state=verified_cpu0_1464_patch") || stage14;
            boolean stage12=out.contains("vendor_boot_state=verified_cpu0_1416_patch") || stage13;
            boolean stage11=out.contains("vendor_boot_state=verified_cpu0_1368_patch") || stage12;
            boolean stage10=out.contains("vendor_boot_state=verified_cpu0_1344_patch") || stage11;
            boolean stage9=out.contains("vendor_boot_state=verified_cpu0_1296_patch") || stage10;
            boolean stage8=out.contains("vendor_boot_state=verified_cpu2_1776_patch") || stage9;
            boolean installed1512=out.contains("oc_1512_apply_supported=1") && stage14;
            boolean installed1464=out.contains("oc_1464_apply_supported=1") && stage13;
            boolean installed1416=out.contains("oc_1416_apply_supported=1") && stage12;
            boolean installed1368=out.contains("oc_1368_apply_supported=1") && stage11;
            boolean installed1344=out.contains("oc_1344_apply_supported=1") && stage10;
            boolean installed1296=out.contains("oc_1296_apply_supported=1") && stage9;
            boolean installed1776=out.contains("oc_1776_apply_supported=1") && stage8;
            boolean installed1608=out.contains("oc_1608_apply_supported=1") &&
                    (out.contains("vendor_boot_state=verified_cpu4_1608_patch") || stage8);
            boolean installed1560=out.contains("oc_apply_supported=1") &&
                    (out.contains("vendor_boot_state=verified_cpu4_1560_patch") ||
                     out.contains("vendor_boot_state=verified_cpu4_1608_patch") || stage8);
            boolean installed=installed1512||installed1464||installed1416||installed1368||installed1344||installed1296||installed1776||installed1608||installed1560;
            boolean stock=out.contains("vendor_boot_state=verified_stock");
            String message;
            if(installed1512){
                message="Validated CPU OC support is installed.\n\n"+
                        "CPU0-1: 1296 MHz @ 1.10 V + 1344/1368/1416/1464/1512 MHz @ 1.15 V turbo OPPs\n"+
                        "CPU2-3: 1776 MHz turbo OPP @ 1.15 V\n"+
                        "CPU4: 1560 + 1608 MHz turbo OPPs @ 1.15 V\n"+
                        "Validation: Stage 6C + Stage 7C + Stage 8C + Stage 9C + Stage 10C + Stage 11C + Stage 12C + Stage 13C + Stage 14C short pinned-load PASS\n\n"+
                        "Use the CPU Manager to select Dynamic/Performance 1296, 1344, 1368, 1416, 1464, 1512, 1560, 1608, or 1776.";
            }else if(installed1464){
                message="Validated CPU OC support is installed.\n\n"+
                        "CPU0-1: 1296 MHz @ 1.10 V + 1344/1368/1416/1464 MHz @ 1.15 V turbo OPPs\n"+
                        "CPU2-3: 1776 MHz turbo OPP @ 1.15 V\n"+
                        "CPU4: 1560 + 1608 MHz turbo OPPs @ 1.15 V\n"+
                        "Validation: Stage 6C + Stage 7C + Stage 8C + Stage 9C + Stage 10C + Stage 11C + Stage 12C + Stage 13C short pinned-load PASS\n\n"+
                        "Use the CPU Manager to select Dynamic/Performance 1296, 1344, 1368, 1416, 1464, 1560, 1608, or 1776.";
            }else if(installed1416){
                message="Validated CPU OC support is installed.\n\n"+
                        "CPU0-1: 1296 MHz @ 1.10 V + 1344/1368/1416 MHz @ 1.15 V turbo OPPs\n"+
                        "CPU2-3: 1776 MHz turbo OPP @ 1.15 V\n"+
                        "CPU4: 1560 + 1608 MHz turbo OPPs @ 1.15 V\n"+
                        "Validation: Stage 6C + Stage 7C + Stage 8C + Stage 9C + Stage 10C + Stage 11C + Stage 12C short pinned-load PASS\n\n"+
                        "Use the CPU Manager to select Dynamic/Performance 1296, 1344, 1368, 1416, 1560, 1608, or 1776.";
            }else if(installed1368){
                message="Validated CPU OC support is installed.\n\n"+
                        "CPU0-1: 1296 MHz @ 1.10 V + 1344/1368 MHz @ 1.15 V turbo OPPs\n"+
                        "CPU2-3: 1776 MHz turbo OPP @ 1.15 V\n"+
                        "CPU4: 1560 + 1608 MHz turbo OPPs @ 1.15 V\n"+
                        "Validation: Stage 6C + Stage 7C + Stage 8C + Stage 9C + Stage 10C + Stage 11C short pinned-load PASS\n\n"+
                        "Use the CPU Manager to select Dynamic/Performance 1296, 1344, 1368, 1560, 1608, or 1776.";
            }else if(installed1344){
                message="Validated CPU OC support is installed.\n\n"+
                        "CPU0-1: 1296 MHz turbo OPP @ 1.10 V + 1344 MHz turbo OPP @ 1.15 V\n"+
                        "CPU2-3: 1776 MHz turbo OPP @ 1.15 V\n"+
                        "CPU4: 1560 + 1608 MHz turbo OPPs @ 1.15 V\n"+
                        "Validation: Stage 6C + Stage 7C + Stage 8C + Stage 9C + Stage 10C short pinned-load PASS\n\n"+
                        "Use the CPU Manager to select Dynamic/Performance 1296, 1344, 1560, 1608, or 1776.";
            }else if(installed1296){
                message="Validated CPU OC support is installed.\n\n"+
                        "CPU0-1: 1296 MHz turbo OPP @ 1.10 V\n"+
                        "CPU2-3: 1776 MHz turbo OPP @ 1.15 V\n"+
                        "CPU4: 1560 + 1608 MHz turbo OPPs @ 1.15 V\n"+
                        "Validation: Stage 6C + Stage 7C + Stage 8C + Stage 9C short pinned-load PASS\n\n"+
                        "Use the CPU Manager to select Dynamic/Performance 1296, 1560, 1608, or 1776.";
            }else if(installed1776){
                message="Validated CPU OC support is installed.\n\n"+
                        "CPU4: 1560 + 1608 MHz turbo OPPs\n"+
                        "CPU2-3: 1776 MHz turbo OPP\nVoltage: 1.15 V\n"+
                        "Validation: Stage 6C + Stage 7C + Stage 8C short pinned-load PASS\n\n"+
                        "Use the CPU Manager to select Dynamic/Performance 1560, 1608, or 1776.";
            }else if(installed1608){
                message="Validated CPU OC support is installed.\n\n"+
                        "CPU4: 1560 + 1608 MHz turbo OPPs\nVoltage: 1.15 V\n"+
                        "Validation: Stage 6C (1560) + Stage 7C (1608) short CPU4 load PASS\n\n"+
                        "Use the CPU Manager to select Dynamic/Performance 1560 or 1608.";
            }else if(installed1560){
                message="Validated CPU OC support is installed.\n\n"+
                        "CPU4: 1560 MHz turbo OPP\nVoltage: 1.15 V\n"+
                        "Validation: Stage 6C short CPU4 load PASS\n\n"+
                        "Use the CPU Manager to select Dynamic 1560 or Performance 1560.";
            }else if(stock){
                message="This tablet is on the exact verified stock vendor_boot. "+
                        "The one-time validated CPU OPP enablement has not been installed.\n\n"+
                        "The boot-partition write remains an explicit one-time operation.";
            }else{
                message="The current vendor_boot/OPP state does not match a verified stock or validated Stage 6/7/8/9/10/11/12/13/14 CPU OC configuration. "+
                        "CPU OC controls remain disabled.";
            }
            runOnUiThread(()->{
                AlertDialog.Builder dialog=new AlertDialog.Builder(this)
                        .setTitle("CPU OC Support")
                        .setMessage(message)
                        .setNegativeButton("Close",null);
                if(installed)dialog.setPositiveButton("Open CPU Manager",(d,w)->showCpuMenu());
                dialog.show();
            });
        });
    }


    private View overlayScaleCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13),dp(11),dp(13),dp(11));
        card.setBackground(metricBackground(GPU_COLOR));

        LinearLayout head = row();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("Metrics overlay size",15,Color.rgb(231,240,238),true));
        labels.addView(text("Scales the low-overhead HUD text, spacing and controls.",10,MUTED,false));
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
                    Intent refresh=new Intent(MainActivity.this,OverlayService.class);
                    refresh.setAction("kb1001.refresh_metrics_appearance");
                    if(Build.VERSION.SDK_INT>=26) startForegroundService(refresh);
                    else startService(refresh);
                }
            }
        });
        card.addView(seek,new LinearLayout.LayoutParams(-1,-2));
        return card;
    }

    private View fpsOverlayAppearanceCard() {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13),dp(11),dp(13),dp(11));
        card.setBackground(metricBackground(CPU_COLOR));

        LinearLayout head=row();
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("FPS counter appearance",15,Color.rgb(231,240,238),true));
        labels.addView(text("Drag the FPS text anywhere in-game. A subtle black backing keeps it readable.",10,MUTED,false));
        head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        SharedPreferences prefs=getSharedPreferences("fps_hud",MODE_PRIVATE);
        float saved=prefs.getFloat("scale",1f);
        TextView value=text(Math.round(saved*100f)+"%",12,CPU_COLOR,true);
        value.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        head.addView(value,new LinearLayout.LayoutParams(dp(64),-2));
        card.addView(head);

        SeekBar seek=new SeekBar(this);
        seek.setMax(150);
        seek.setProgress(Math.max(0,Math.min(150,Math.round(saved*100f)-50)));
        seek.setPadding(0,dp(7),0,dp(4));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
                value.setText((50+progress)+"%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){
                float scale=(50+bar.getProgress())/100f;
                prefs.edit().putFloat("scale",scale).apply();
                restartFpsOverlayIfRunning();
            }
        });
        card.addView(seek,new LinearLayout.LayoutParams(-1,-2));

        TextView samplingTitle=text("FPS source",10,MUTED,true);
        samplingTitle.setPadding(0,dp(9),0,dp(2));
        card.addView(samplingTitle);

        TextView samplingHint=text(
                "Live FPS uses a persistent SurfaceFlinger FrameTimeline stream. " +
                "The overlay updates automatically without repeated dumpsys polling.",
                9,MUTED,false);
        samplingHint.setPadding(0,0,0,dp(3));
        card.addView(samplingHint);

        TextView avgTitle=text("Average FPS window",10,MUTED,true);
        avgTitle.setPadding(0,dp(7),0,dp(2));
        card.addView(avgTitle);

        int savedWindow=Math.max(500,Math.min(5000,prefs.getInt("average_window_ms",2000)));
        LinearLayout avgHead=row();
        TextView avgHint=text("Time represented by Average FPS. Shorter reacts faster.",9,MUTED,false);
        TextView avgValue=text(formatFpsWindow(savedWindow),11,CPU_COLOR,true);
        avgValue.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        avgHead.addView(avgHint,new LinearLayout.LayoutParams(0,-2,1));
        avgHead.addView(avgValue,new LinearLayout.LayoutParams(dp(70),-2));
        card.addView(avgHead);

        SeekBar avgSeek=new SeekBar(this);
        avgSeek.setMax(18); // 0.5..5.0 s in 0.25 s steps.
        avgSeek.setProgress((savedWindow-500)/250);
        avgSeek.setPadding(0,dp(3),0,dp(3));
        avgSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
                int ms=500+progress*250;
                avgValue.setText(formatFpsWindow(ms));
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){
                int ms=500+bar.getProgress()*250;
                prefs.edit().putInt("average_window_ms",ms).apply();
                refreshFpsSamplingIfRunning();
            }
        });
        card.addView(avgSeek,new LinearLayout.LayoutParams(-1,-2));

        TextView validationInfo=text(
                "FPS validation logging is automatic when KB1001 FPS Validator is foreground. "+
                        "Logging uses a buffered CSV writer so validation does not add per-sample disk flushes.",
                9,MUTED,false);
        validationInfo.setPadding(0,dp(8),0,dp(3));
        card.addView(validationInfo);

        String lastValidationPath=prefs.getString("validation_log_path","");
        TextView validationPath=text(
                lastValidationPath.isEmpty()
                        ? "Latest validation log: none yet"
                        : "Latest validation log: "+lastValidationPath,
                8,Color.rgb(130,150,165),false);
        validationPath.setPadding(0,dp(2),0,dp(3));
        validationPath.setTextIsSelectable(true);
        card.addView(validationPath);

        TextView colorTitle=text("Text color",10,MUTED,true);
        colorTitle.setPadding(0,dp(7),0,dp(5));
        card.addView(colorTitle);

        LinearLayout colors=row();
        colors.addView(fpsColorButton("White",Color.WHITE),new LinearLayout.LayoutParams(0,dp(38),1));
        colors.addView(fpsColorButton("Green",Color.rgb(96,235,132)),new LinearLayout.LayoutParams(0,dp(38),1));
        colors.addView(fpsColorButton("Yellow",Color.rgb(255,220,72)),new LinearLayout.LayoutParams(0,dp(38),1));
        colors.addView(fpsColorButton("Cyan",Color.rgb(92,225,238)),new LinearLayout.LayoutParams(0,dp(38),1));
        colors.addView(fpsColorButton("Orange",Color.rgb(255,166,68)),new LinearLayout.LayoutParams(0,dp(38),1));
        card.addView(colors);

        return card;
    }

    private Button fpsColorButton(String label,int color){
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(8);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(2),0,dp(2),0);
        b.setTextColor(Color.rgb(8,16,16));

        GradientDrawable bg=new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(999));
        bg.setStroke(dp(1),Color.argb(180,0,0,0));
        b.setBackground(bg);

        b.setOnClickListener(v->{
            getSharedPreferences("fps_hud",MODE_PRIVATE)
                    .edit()
                    .putInt("color",color)
                    .apply();
            restartFpsOverlayIfRunning();
        });
        return b;
    }

    private void restartFpsOverlayIfRunning(){
        if(!FpsOverlayService.isRunning())return;
        Intent refresh=new Intent(this,FpsOverlayService.class);
        refresh.setAction("kb1001.refresh_fps_appearance");
        if(Build.VERSION.SDK_INT>=26)startForegroundService(refresh);
        else startService(refresh);
    }

    private void refreshFpsSamplingIfRunning(){
        if(!FpsOverlayService.isRunning())return;
        Intent refresh=new Intent(this,FpsOverlayService.class);
        refresh.setAction("kb1001.refresh_fps_sampling");
        if(Build.VERSION.SDK_INT>=26)startForegroundService(refresh);
        else startService(refresh);
    }

    private String formatFpsWindow(int ms){
        if(ms%1000==0)return (ms/1000)+" s";
        return String.format(Locale.US,"%.2f s",ms/1000f)
                .replaceAll("0+$","")
                .replaceAll("\\.$","");
    }

    private void setManualOverlay(String type,boolean enabled,Switch control){
        if(control==null)return;

        if(enabled && !Settings.canDrawOverlays(this)){
            pendingOverlayPermissionType=type;
            setSwitchStateSilently(control,false);
            showOverlayPermissionDialog();
            return;
        }

        if("metrics".equals(type)){
            metricsTogglePending=true;
            AppStateCache.setManualMetrics(this,enabled);
        }else{
            fpsTogglePending=true;
            AppStateCache.setManualFps(this,enabled);
        }

        // Manual overlays are Android UI services and must not be held hostage
        // by an unavailable root backend. Apply the local UI state immediately.
        if(enabled){
            if("metrics".equals(type))showHud();
            else showFpsHud();
        }else{
            Intent service=new Intent(this,
                    "metrics".equals(type)?OverlayService.class:FpsOverlayService.class);
            try{stopService(service);}catch(Exception ignored){}
        }

        setSwitchStateSilently(control,enabled);
        control.setEnabled(false);

        // Root-backed persistence is best-effort. If Magisk is unavailable to
        // the app process, keep the overlay running/stopped according to the
        // user's local choice instead of rolling the switch back.
        io.execute(()->{
            String command="overlay "+type+"-manual-"+(enabled?"on":"off");
            RootBridge.Result r=RootBridge.get().ctl(command);
            boolean saved=r.ok() &&
                    (enabled?"enabled":"disabled").equals(r.output.trim());

            runOnUiThread(()->{
                if("metrics".equals(type))metricsTogglePending=false;
                else fpsTogglePending=false;

                if(!saved){
                    String detail=compactBackendError(r.output);
                    Toast.makeText(
                            this,
                            (enabled?"Overlay enabled locally. ":"Overlay disabled locally. ")+
                                    "Privileged backend sync unavailable: "+detail,
                            Toast.LENGTH_LONG).show();
                }

                setSwitchStateSilently(control,enabled);
                control.setEnabled(true);
            });
        });
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

    private void setSwitchImmediately(Switch sw,boolean checked){
        setSwitchStateSilently(sw,checked);
    }

    private void setSwitchStateSilently(Switch sw,boolean checked){
        if(sw==null || sw.isChecked()==checked)return;

        boolean oldSuppress=suppressSwitchCallbacks;
        boolean oldSound=sw.isSoundEffectsEnabled();
        suppressSwitchCallbacks=true;
        sw.setSoundEffectsEnabled(false);
        sw.setChecked(checked);
        sw.setSoundEffectsEnabled(oldSound);
        suppressSwitchCallbacks=oldSuppress;
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

        setSwitchStateSilently(hudSwitch,false);

        showOverlayPermissionDialog();
    }

    private void showFpsHud() {
        if (Settings.canDrawOverlays(this)) {
            Intent i=new Intent(this,FpsOverlayService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);
            else startService(i);
            return;
        }

        setSwitchStateSilently(fpsHudSwitch,false);

        showOverlayPermissionDialog();
    }

    private void showOverlayPermissionDialog() {
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

    private void restoreManualOverlaysIfNeeded(){
        if(!Settings.canDrawOverlays(this))return;

        if(AppStateCache.manualMetrics(this) && !OverlayService.isRunning()){
            showHud();
        }
        if(AppStateCache.manualFps(this) && !FpsOverlayService.isRunning()){
            showFpsHud();
        }
    }

    private void refreshBackendState() {
        if(!rootStartupComplete)return;
        backendIo.execute(() -> {
            RootBridge.Result r = RootBridge.get().ctl("status");

            if (!r.ok()) {
                runOnUiThread(() -> {
                    if (hudSwitch != null && !metricsTogglePending)
                        setSwitchStateSilently(hudSwitch,AppStateCache.manualMetrics(this));
                    if (fpsHudSwitch != null && !fpsTogglePending)
                        setSwitchStateSilently(fpsHudSwitch,AppStateCache.manualFps(this));
                    restoreManualOverlaysIfNeeded();
                });
                return;
            }

            Map<String,String> status = parseStatus(r.output);
            AppStateCache.updateStatus(this,status);

            runOnUiThread(() -> {
                suppressSwitchCallbacks = true;

                if (autoBoostSwitch != null)
                    setSwitchStateSilently(autoBoostSwitch,AppStateCache.autoBoost(this));
                if (loggingSwitch != null)
                    setSwitchStateSilently(loggingSwitch,AppStateCache.fileLogging(this));
                boolean metricsRunning=OverlayService.isRunning();
                boolean fpsRunning=FpsOverlayService.isRunning();

                getSharedPreferences("hud",MODE_PRIVATE).edit()
                        .putBoolean("runtime_running",metricsRunning).apply();
                getSharedPreferences("fps_hud",MODE_PRIVATE).edit()
                        .putBoolean("runtime_running",fpsRunning).apply();

                if (hudSwitch != null && !metricsTogglePending)
                    setSwitchStateSilently(hudSwitch,AppStateCache.manualMetrics(this));
                if (fpsHudSwitch != null && !fpsTogglePending)
                    setSwitchStateSilently(fpsHudSwitch,AppStateCache.manualFps(this));

                restoreManualOverlaysIfNeeded();

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
        String cpuPoliciesRaw = TelemetryStore.get(m,"cpu_policies","");
        int primeUtil = coreUtilValue(coreUtil,4);
        int primeClock = cpuPolicyCurrent(cpuPoliciesRaw,"policy4");
        cpuMetric.set(
                cpuUtil + "% • " + cpu.peakCurrent + " MHz",
                "A73 CPU4 " + primeUtil + "% @ " + primeClock + " MHz" +
                        (cpu.summary.isEmpty() ? "" : " • " + cpu.summary),
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
            }else if(primeUtil>=90 && gpuUtil<85){
                label="PRIME CPU LIMITED";
                detail="A73 CPU4 "+primeUtil+"% • total CPU "+cpuUtil+"% • GPU "+gpuUtil+"%"+
                        (fps>0?" • "+fps+" FPS":"");
                color=CPU_COLOR;
            }else if(cpuUtil>=88 && gpuUtil<85){
                label="CPU LIMITED";
                detail="Total CPU "+cpuUtil+"% • GPU "+gpuUtil+"% • GPU still has headroom"+
                        (fps>0?" • "+fps+" FPS":"");
                color=CPU_COLOR;
            }else if(gpuUtil>=90 && cpuUtil<90){
                label="GPU LIMITED";
                detail="GPU "+gpuUtil+"% • total CPU "+cpuUtil+"% • A73 "+primeUtil+"%"+
                        (fps>0?" • "+fps+" FPS":"");
                color=GPU_COLOR;
            }else if(cpuUtil>=88 && gpuUtil>=88){
                label="SYSTEM SATURATED";
                detail="CPU and GPU are both heavily loaded"+(fps>0?" • "+fps+" FPS":"");
                color=SESSION_COLOR;
            }else{
                label="HEADROOM";
                detail="CPU "+cpuUtil+"% • A73 "+primeUtil+"% • GPU "+gpuUtil+"% • "+
                        (plugged?"USB power":"battery power");
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
        String batteryStatus=TelemetryStore.get(m,"battery_status","—");
        boolean plugged="1".equals(TelemetryStore.get(m,"power_online","0"));
        String usbType=TelemetryStore.get(m,"power_usb_type","");
        batteryMetric.set(
                batt < 0 ? "—" : batt + "%",
                batteryStatus+" • "+(plugged ? "plugged in"+(usbType.isEmpty()?"":" • "+usbType) : "battery")+
                        String.format(Locale.US," • %.1f °C",battTemp),
                Math.max(0,batt));

        if (loggingSwitch != null) {
            boolean recording="1".equals(TelemetryStore.get(m,"file_logging","0"));
            AppStateCache.setFileLogging(this,recording);
            setSwitchImmediately(loggingSwitch,recording);
        }

        if (loggerPath != null) {
            String path = TelemetryStore.get(m,"file_path","");
            loggerPath.setText(path.isEmpty() ? "No active file." : path);
        }
    }

    private int coreUtilValue(String raw,int core) {
        if(raw==null)return 0;
        String key="cpu"+core+":";
        for(String item:raw.split(";")){
            if(!item.startsWith(key))continue;
            try{return Math.max(0,Math.min(100,Integer.parseInt(item.substring(key.length()).trim())));}
            catch(Exception ignored){return 0;}
        }
        return 0;
    }

    private int cpuPolicyCurrent(String raw,String policy) {
        if(raw==null)return 0;
        for(String item:raw.split(";")){
            if(!item.startsWith(policy))continue;
            int eq=item.indexOf('=');
            int slash=item.indexOf('/',eq+1);
            if(eq<0||slash<0)continue;
            try{return Integer.parseInt(item.substring(eq+1,slash).replaceAll("[^0-9]",""));}
            catch(Exception ignored){return 0;}
        }
        return 0;
    }

    private String displayProfile(String p) {
        if ("stock".equals(p)) return "Stock 696";
        if ("performance696".equals(p)) return "Performance 696";
        if ("dynamic744".equals(p)) return "Experimental Dynamic 744";
        if ("performance744".equals(p)) return "Experimental Performance 744";
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

    // Update policy: one quiet check when MainActivity is created, plus the
    // explicit Check for Update button. No resume timer, retry timer, or
    // background polling.
    private void startupUpdateCheck() {
        // Exactly one automatic version check per MainActivity creation.
        // The only other version-check entry point is the explicit
        // "Check for Update" button in Settings.
        if(startupUpdateCheckStarted)return;
        startupUpdateCheckStarted=true;
        checkForUpdate(true);
    }

    private String compactBackendError(String raw){
        String value=raw==null?"":raw.trim();
        if(value.isEmpty())value=BackendManager.lastInstallError(this).trim();
        if(value.isEmpty())return "No diagnostic output";

        String stage="";
        String detail="";
        for(String line:value.split("\\R")){
            String s=line.trim();
            if(s.isEmpty())continue;
            if(s.startsWith("stage=") && stage.isEmpty()){
                stage=s.substring(6).trim();
            }else if(s.startsWith("exit=")){
                // Stored diagnostic metadata; prefer the actual error line.
            }else if(detail.isEmpty()){
                detail=s;
            }
        }

        if(detail.isEmpty())detail=value.replace('\n',' ').replace('\r',' ').trim();
        String out=stage.isEmpty()?detail:(stage+": "+detail);
        return out.length()>120?out.substring(0,117)+"...":out;
    }

    private void refreshBackendHealth(){
        backendIo.execute(()->{
            RootBridge.Result r=BackendManager.backendHealth(this);
            runOnUiThread(()->{
                if(backendHealthValue==null)return;

                String output=r.output==null?"":r.output.trim();
                if(r.ok() && output.startsWith("ready|boot-hook")){
                    String[] lines=output.split("\\R");
                    String deployed=lines.length>1?lines[1].trim():"";
                    String label="Backend: Ready • Boot hook installed";
                    if(!deployed.isEmpty()){
                        String[] parts=deployed.split("\\|",-1);
                        if(parts.length>=2)label+=" • "+parts[1];
                    }
                    backendHealthValue.setText(label);
                    backendHealthValue.setTextColor(Color.rgb(77,210,126));
                }else{
                    backendHealthValue.setText(r.superuserRequired()
                            ?"Backend: Superuser access required"
                            :"Backend: Needs attention • "+compactBackendError(output));
                    backendHealthValue.setTextColor(Color.rgb(255,170,92));
                }
            });
        });
    }


    private void checkForUpdate(boolean quiet) {
        updateIo.execute(() -> {
            try {
                UpdateManager.ReleaseInfo info=UpdateManager.check(this);
                releaseInfo=info;

                int installed=UpdateManager.installedAppVersion(this);
                boolean appNew=info.appVersionCode>installed;

                if(!appNew){
                    if(!quiet)runOnUiThread(() ->
                            new AlertDialog.Builder(this)
                                    .setTitle("You're already up to date!")
                                    .setMessage("Current version: "+BuildConfig.VERSION_NAME)
                                    .setPositiveButton("OK",null)
                                    .show());
                    return;
                }

                runOnUiThread(() -> showUpdateConfirmation(info));
            } catch(Exception e) {
                if(!quiet)runOnUiThread(() ->
                        new AlertDialog.Builder(this)
                                .setTitle("Update check failed")
                                .setMessage(e.getMessage())
                                .setPositiveButton("OK",null)
                                .show());
            }
        });
    }

    private void showUpdateConfirmation(UpdateManager.ReleaseInfo info) {
        new AlertDialog.Builder(this)
                .setTitle("Install update?")
                .setMessage(
                        "Current version: "+BuildConfig.VERSION_NAME+
                                "\nNew version: "+info.appVersionName)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Install",(d,w) -> performAppUpdate(info))
                .show();
    }

    private void performAppUpdate(UpdateManager.ReleaseInfo info) {
        ProgressDialog progress=new ProgressDialog(this);
        progress.setTitle("Updating KB1001 Performance Manager");
        progress.setMessage("Downloading app…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        updateIo.execute(() -> {
            try {
                File apk=UpdateManager.download(this,info.app,null);

                runOnUiThread(() -> {
                    progress.dismiss();
                    try {
                        UpdateManager.installApk(this,apk);
                    } catch(Exception e) {
                        showError("App install",e.getMessage());
                    }
                });
            } catch(Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    showError("Update failed",e.getMessage());
                });
            }
        });
    }

    private void confirmLegacyModuleRemoval() {
        new AlertDialog.Builder(this)
                .setTitle("Remove legacy Magisk module?")
                .setMessage(
                        "The app-owned root backend is installed before this removal is scheduled. " +
                                "Your profiles, game library, overlay rules and CPU state are migrated to the new backend. " +
                                "Magisk will finish removing the old module on the next reboot.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Remove",(d,w) -> io.execute(() -> {
                    RootBridge.Result ready=BackendManager.ensureInstalled(this);
                    RootBridge.Result result=ready.ok()
                            ? BackendManager.scheduleLegacyModuleRemoval(this)
                            : ready;

                    runOnUiThread(() -> {
                        if(!result.ok()){
                            showError("Legacy module",result.output);
                            return;
                        }

                        if("absent".equals(result.output.trim())){
                            Toast.makeText(this,"Legacy Magisk module is already absent.",Toast.LENGTH_SHORT).show();
                            return;
                        }

                        new AlertDialog.Builder(this)
                                .setTitle("Removal scheduled")
                                .setMessage(
                                        "The old module is disabled and marked for removal. " +
                                                "The app-owned backend is already active. Reboot when convenient to let Magisk remove the legacy module.")
                                .setNegativeButton("Later",null)
                                .setPositiveButton("Reboot",(x,y) ->
                                        io.execute(() -> RootBridge.get().exec("reboot")))
                                .show();
                    });
                }))
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
                .setMessage("Apply the 792 MHz runtime OPP for this session? The factory ceiling is 696 MHz; this is not persisted and AutoBoost never selects it.")
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

    private void applyMainKeepAwake(){
        DisplaySleepPolicy.apply(this,false);
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

        if(subtitle!=null && !subtitle.isEmpty()){
            TextView s = text(subtitle,11,MUTED,false);
            s.setPadding(dp(2),0,0,dp(7));
            page.addView(s);
        }
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
        if(!overlayStateReceiverRegistered){
            IntentFilter filter=new IntentFilter(AppStateCache.ACTION_MANUAL_OVERLAY_CHANGED);
            if(Build.VERSION.SDK_INT>=33){
                registerReceiver(overlayStateReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
            }else{
                registerReceiver(overlayStateReceiver,filter);
            }
            overlayStateReceiverRegistered=true;
        }
        TelemetryDemand.activityResumed();
        applyMainKeepAwake();
        active = true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);

        if(pendingOverlayPermissionType!=null && Settings.canDrawOverlays(this)){
            String requested=pendingOverlayPermissionType;
            pendingOverlayPermissionType=null;
            if("metrics".equals(requested) && hudSwitch!=null){
                setSwitchStateSilently(hudSwitch,true);
                setManualOverlay("metrics",true,hudSwitch);
            }else if("fps".equals(requested) && fpsHudSwitch!=null){
                setSwitchStateSilently(fpsHudSwitch,true);
                setManualOverlay("fps",true,fpsHudSwitch);
            }
        }

        restoreManualOverlaysIfNeeded();
        if(rootStartupComplete && !AppStateCache.statusFresh(this,5000)){
            refreshBackendState();
        }
        if (tab == 1 && rootStartupComplete) loadGamesInline();

        maybeStartForegroundRoot();
    }

    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);
        hasWindowFocus=focused;
        if(focused)maybeStartForegroundRoot();
    }

    @Override public void onRequestPermissionsResult(
            int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==41){
            // The notification decision itself does not affect root. It only
            // tells us Android's permission dialog is gone; wait for focus and
            // then perform the single foreground Magisk request.
            rootStartupRequested=true;
            handler.post(this::maybeStartForegroundRoot);
        }
    }

    private void maybeStartForegroundRoot(){
        if(rootStartupComplete || rootStartupInFlight || !rootStartupRequested)return;
        if(!active || !hasWindowFocus)return;

        // On Android 13+ a fresh install may still be waiting for the
        // notification permission result. Do not launch MagiskSU underneath it.
        if(Build.VERSION.SDK_INT>=33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED){
            // A denial is also a completed decision, but onRequestPermissionsResult
            // will re-enter here after the dialog closes. Window focus is the
            // authoritative foreground gate.
        }

        rootStartupRequested=false;
        rootStartupInFlight=true;

        backendIo.execute(() -> {
            RootBridge.Result ready=BackendManager.ensureInstalled(this);

            runOnUiThread(() -> {
                rootStartupInFlight=false;

                if(ready.ok()){
                    rootStartupComplete=true;
                    TelemetryDemand.setPrivilegedReady(true);
                    refreshBackendState();
                    if(tab==1)loadGamesInline();
                    return;
                }

                TelemetryDemand.setPrivilegedReady(false);

                // Do not create a retry storm. A failed foreground request is
                // retried only by an explicit Retry action or a later return to
                // the foreground.
                rootStartupRequested=true;

                String detail=ready.output==null?"":ready.output.trim();
                String lower=detail.toLowerCase(Locale.US);
                boolean denied=
                        lower.contains("permission denied") ||
                        lower.contains("superuser rights") && lower.contains("denied") ||
                        lower.contains("request rejected");

                if(detail.contains("retry suppressed briefly")){
                    detail="Root authorization did not complete.";
                }

                if(backendHealthValue!=null){
                    backendHealthValue.setText(
                            denied
                                    ?"Backend: Superuser access required"
                                    :(detail.isEmpty()?"Root authorization required":detail));
                    backendHealthValue.setTextColor(BATTERY_WARN);
                }

                if(denied){
                    showRootAuthorizationRequired();
                }
            });
        });
    }

    private void showRootAuthorizationRequired(){
        if(isFinishing() || isDestroyed())return;

        new AlertDialog.Builder(this)
                .setTitle("Superuser access required")
                .setMessage(
                        "KB1001 Performance Manager needs Magisk Superuser access for the privileged backend.\n\n"+
                        "If this app has no Magisk policy yet, Retry will issue a fresh foreground root request and Magisk should show its Grant prompt.\n\n"+
                        "If the app is already listed as denied/off in Magisk, enable it in Magisk first; Android apps cannot override a stored Magisk denial.")
                .setNegativeButton("Later",null)
                .setPositiveButton("Retry",(d,w)->{
                    RootBridge.get().clearRetryBackoff();
                    rootStartupRequested=true;
                    handler.post(this::maybeStartForegroundRoot);
                })
                .show();
    }

    @Override protected void onPause() {
        if(overlayStateReceiverRegistered){
            try{unregisterReceiver(overlayStateReceiver);}catch(Exception ignored){}
            overlayStateReceiverRegistered=false;
        }
        active = false;
        handler.removeCallbacks(ticker);
        TelemetryDemand.activityPaused();
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        backendIo.shutdownNow();
        scanIo.shutdownNow();
        updateIo.shutdownNow();
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
