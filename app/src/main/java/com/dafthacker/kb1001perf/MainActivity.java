package com.dafthacker.kb1001perf;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout page;
    private TextView headerState;
    private Button updateBanner;
    private TextView updateStatus;
    private Button appUpdateButton;
    private Button moduleUpdateButton;
    private UpdateManager.ReleaseInfo releaseInfo;

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

    private int tab;
    private boolean active;
    private boolean suppressSwitchCallbacks;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        root.setPadding(dp(18),dp(18),dp(18),dp(12));
        root.setBackgroundResource(R.drawable.bg_app);

        TextView eyebrow = text("KB1001",12,Color.rgb(92,232,255),true);
        eyebrow.setLetterSpacing(.18f);
        root.addView(eyebrow);
        root.addView(text("Performance Manager",30,Color.WHITE,true));

        headerState = text("Backend connecting…",13,Color.rgb(156,176,201),false);
        headerState.setPadding(0,dp(4),0,dp(8));
        root.addView(headerState);

        updateBanner = button("Update available",true,v -> showTab(3));
        updateBanner.setVisibility(View.GONE);
        root.addView(updateBanner,new LinearLayout.LayoutParams(-1,-2));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        tabs.addView(tabButton("CONTROL",0),tabWeight());
        tabs.addView(tabButton("GAMES",1),tabWeight());
        tabs.addView(tabButton("LOGGER",2),tabWeight());
        tabs.addView(tabButton("UPDATES",3),tabWeight());
        tabScroll.addView(tabs);
        root.addView(tabScroll,new LinearLayout.LayoutParams(-1,-2));

        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0,dp(10),0,dp(28));
        scroll.addView(page);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        return root;
    }

    private void showTab(int index) {
        tab=index;
        page.removeAllViews();
        hudSwitch=null; autoBoostSwitch=null; autoHudSwitch=null; loggingSwitch=null;
        ramMetric=cpuMetric=gpuMetric=thermalMetric=batteryMetric=null;
        updateStatus=null; appUpdateButton=null; moduleUpdateButton=null;

        if(index==0) controlPage();
        else if(index==1) gamesPage();
        else if(index==2) loggerPage();
        else updatesPage();

        refreshTelemetry();
        refreshBackendState();
    }

    private void controlPage() {
        section("AUTOMATION","The root backend keeps running even if this screen is closed.");

        autoBoostSwitch = toggleCard(
                "AutoBoost",
                "Boost selected games automatically and restore the idle profile afterwards.",
                checked -> ctl("auto " + (checked ? "enable" : "disable")));
        page.addView(autoBoostSwitch.getParent() instanceof View
                ? (View)autoBoostSwitch.getParent() : autoBoostSwitch);

        autoHudSwitch = toggleCard(
                "Show HUD automatically in games",
                "Starts the overlay only when a selected game is active.",
                checked -> ctl("overlay auto " + (checked ? "enable" : "disable")));
        page.addView((View)autoHudSwitch.getParent());

        hudSwitch = toggleCard(
                "In-game HUD",
                "Manually show or hide the draggable performance overlay.",
                checked -> {
                    if(checked) showHud();
                    else stopService(new Intent(this,OverlayService.class));
                });
        page.addView((View)hudSwitch.getParent());

        section("GPU PROFILE","Choose the default persistent GPU behavior.");

        RadioGroup profiles = new RadioGroup(this);
        profiles.setOrientation(RadioGroup.VERTICAL);
        profiles.setBackgroundResource(R.drawable.bg_card);
        profiles.setPadding(dp(12),dp(8),dp(12),dp(8));

        String[][] opts = {
                {"Stock 696","stock"},
                {"Dynamic 744","dynamic744"},
                {"Performance 744","performance744"},
                {"Experimental 792 (session only)","experimental792"}
        };

        for(String[] o:opts) {
            RadioButton rb = new RadioButton(this);
            rb.setText(o[0]);
            rb.setTextColor(Color.WHITE);
            rb.setTextSize(14);
            rb.setPadding(dp(4),dp(5),dp(4),dp(5));
            rb.setOnClickListener(v -> {
                if("experimental792".equals(o[1])) experimental();
                else ctl("persist " + o[1]);
            });
            profiles.addView(rb);
        }
        page.addView(profiles,full());

        TextView note = text(
                "792 MHz is intentionally labeled experimental. AutoBoost never selects it.",
                12,Color.rgb(156,176,201),false);
        page.addView(note);
    }

    private void gamesPage() {
        section("GAME LIBRARY","Android-marked games are added automatically. You can add any launcher app manually.");

        Button open = button("+  Manage AutoBoost games",true,
                v -> startActivity(new Intent(this,GamePickerActivity.class)));
        page.addView(card(open),full());

        TextView tip = text(
                "Long-press a game in the library to delete it. Deleted auto-detected games stay ignored until you add them manually again.",
                12,Color.rgb(156,176,201),false);
        page.addView(card(tip),full());

        section("DETECTION INTERVAL","2 seconds is the default balance between responsiveness and overhead.");
        LinearLayout rates=row();
        rates.addView(button("1 sec",false,v -> ctl("auto poll 1")),weight());
        rates.addView(button("2 sec",true,v -> ctl("auto poll 2")),weight());
        rates.addView(button("5 sec",false,v -> ctl("auto poll 5")),weight());
        page.addView(card(rates),full());
    }

    private void loggerPage() {
        section("LIVE PERFORMANCE","Visual telemetry updates without repeated Superuser prompts.");

        ramMetric = metricCard("RAM");
        cpuMetric = metricCard("CPU Clock");
        gpuMetric = metricCard("GPU Clock");
        thermalMetric = metricCard("Thermal");
        batteryMetric = metricCard("Battery");

        page.addView(ramMetric.root,full());
        page.addView(cpuMetric.root,full());
        page.addView(gpuMetric.root,full());
        page.addView(thermalMetric.root,full());
        page.addView(batteryMetric.root,full());

        section("SESSION RECORDING","Optionally save a CSV session for later comparison.");

        loggingSwitch = toggleCard(
                "Save performance log to file",
                "CSV files are written under Documents/KB1001Performance/logs.",
                checked -> ctl("logger file " + (checked ? "on" : "off")));
        page.addView((View)loggingSwitch.getParent());

        LinearLayout rate=row();
        rate.addView(button("1 sec",true,v -> ctl("logger interval 1")),weight());
        rate.addView(button("2 sec",false,v -> ctl("logger interval 2")),weight());
        rate.addView(button("5 sec",false,v -> ctl("logger interval 5")),weight());
        page.addView(card(rate),full());

        loggerPath = text("No active file.",11,Color.rgb(156,176,201),false);
        loggerPath.setTextIsSelectable(true);
        page.addView(card(loggerPath),full());
    }

    private void updatesPage() {
        section("SOFTWARE UPDATES","The APK and Magisk backend share one signed release feed.");

        updateStatus = mono(
                "Installed app: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\nChecking…");
        page.addView(card(updateStatus),full());

        Button check=button("CHECK NOW",true,v -> checkUpdates(false));
        page.addView(card(check),full());

        appUpdateButton=button("DOWNLOAD APP UPDATE",false,v -> downloadAppUpdate());
        appUpdateButton.setEnabled(false);
        page.addView(card(appUpdateButton),full());

        moduleUpdateButton=button("DOWNLOAD + INSTALL MODULE",false,v -> downloadModuleUpdate());
        moduleUpdateButton.setEnabled(false);
        page.addView(card(moduleUpdateButton),full());

        checkUpdates(false);
    }

    private void autoDetectGames() {
        try {
            SharedPreferences prefs=getSharedPreferences("game_library",MODE_PRIVATE);
            Set<String> ignored=prefs.getStringSet("ignored_games",Collections.emptySet());

            Intent launcher=new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            PackageManager pm=getPackageManager();
            List<ResolveInfo> list=Build.VERSION.SDK_INT>=33
                    ? pm.queryIntentActivities(launcher,PackageManager.ResolveInfoFlags.of(0))
                    : pm.queryIntentActivities(launcher,0);

            for(ResolveInfo r:list) {
                if(r.activityInfo==null) continue;
                String pkg=r.activityInfo.packageName;
                if(pkg==null || pkg.equals(getPackageName()) || ignored.contains(pkg)) continue;

                try {
                    ApplicationInfo ai=pm.getApplicationInfo(pkg,0);
                    if(Build.VERSION.SDK_INT>=26 && ai.category==ApplicationInfo.CATEGORY_GAME) {
                        RootBridge.get().ctl("game add " + pkg);
                    }
                } catch(Exception ignoredError) {}
            }
        } catch(Exception ignored) {}
    }

    private Switch toggleCard(String title,String subtitle,ToggleAction action) {
        LinearLayout wrapper=new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER_VERTICAL);
        wrapper.setPadding(dp(12),dp(10),dp(12),dp(10));
        wrapper.setBackgroundResource(R.drawable.bg_card);

        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,15,Color.WHITE,true));
        labels.addView(text(subtitle,11,Color.rgb(156,176,201),false));
        wrapper.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        Switch sw=new Switch(this);
        sw.setOnCheckedChangeListener((b,checked) -> {
            if(!suppressSwitchCallbacks) action.changed(checked);
        });
        wrapper.addView(sw);

        LinearLayout.LayoutParams lp=full();
        wrapper.setLayoutParams(lp);
        return sw;
    }

    private void showHud() {
        if(Settings.canDrawOverlays(this)) {
            Intent i=new Intent(this,OverlayService.class);
            if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
            return;
        }

        suppressSwitchCallbacks=true;
        if(hudSwitch!=null) hudSwitch.setChecked(false);
        suppressSwitchCallbacks=false;

        new AlertDialog.Builder(this)
                .setTitle("Overlay permission required")
                .setMessage(
                        "Android may block this permission for sideloaded apps. If the next screen says the setting is restricted, open App info, use the top-right menu and choose “Allow restricted settings”, then return here and enable the HUD again.")
                .setPositiveButton("Open overlay settings",(d,w) ->
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()))))
                .setNeutralButton("Open app info",(d,w) ->
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void refreshBackendState() {
        if(autoBoostSwitch==null && autoHudSwitch==null && loggingSwitch==null) return;

        io.execute(() -> {
            RootBridge.Result r=RootBridge.get().ctl("status");
            if(!r.ok()) return;
            Map<String,String> s=parseStatus(r.output);

            runOnUiThread(() -> {
                suppressSwitchCallbacks=true;
                if(autoBoostSwitch!=null) autoBoostSwitch.setChecked("1".equals(s.get("Auto boost")));
                if(autoHudSwitch!=null) autoHudSwitch.setChecked("1".equals(s.get("Overlay auto")));
                if(loggingSwitch!=null) loggingSwitch.setChecked("1".equals(s.get("File logging")));
                if(hudSwitch!=null) hudSwitch.setChecked(OverlayService.isRunning());
                suppressSwitchCallbacks=false;
            });
        });
    }

    private Map<String,String> parseStatus(String out) {
        Map<String,String> map=new HashMap<>();
        for(String line:out.split("\\R")) {
            int i=line.indexOf(':');
            if(i>0) map.put(line.substring(0,i).trim(),line.substring(i+1).trim());
        }
        return map;
    }

    private void refreshTelemetry() {
        Map<String,String> m=TelemetryStore.read(this);
        String mode=TelemetryStore.get(m,"mode","waiting");
        String profile=TelemetryStore.get(m,"profile","—");
        String gpu=TelemetryStore.get(m,"gpu_clock_mhz","—");
        String temp=TelemetryStore.get(m,"thermal_max_c","—");
        headerState.setText(mode.toUpperCase(Locale.US)+"  •  "+profile+"  •  GPU "+gpu+" MHz  •  "+temp+"°C");

        if(tab!=2 || ramMetric==null) return;

        ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        long total=mi.totalMem/1024/1024;
        long available=mi.availMem/1024/1024;
        long used=Math.max(0,total-available);
        int ramPct=total>0?(int)Math.min(100,used*100/total):0;
        ramMetric.set(used+" / "+total+" MB",ramPct+"% used",ramPct);

        CpuClock cpu=parseCpuClock(TelemetryStore.get(m,"cpu_policies",""));
        cpuMetric.set(cpu.currentMhz+" MHz",cpu.maxMhz>0?"Max "+cpu.maxMhz+" MHz":"Clock data unavailable",cpu.percent);

        int gpuMhz=parseInt(TelemetryStore.get(m,"gpu_clock_mhz","0"));
        int gpuPct=Math.max(0,Math.min(100,Math.round(gpuMhz*100f/792f)));
        gpuMetric.set(gpuMhz+" MHz","Relative to experimental 792 MHz ceiling",gpuPct);

        float thermal=parseFloat(TelemetryStore.get(m,"thermal_max_c","0"));
        int thermalPct=Math.max(0,Math.min(100,Math.round(thermal/85f*100)));
        thermalMetric.set(String.format(Locale.US,"%.1f °C",thermal),"Highest reported thermal zone",thermalPct);

        BatteryManager bm=(BatteryManager)getSystemService(BATTERY_SERVICE);
        int batt=bm==null?-1:bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        float battTemp=parseFloat(TelemetryStore.get(m,"battery_temp_c","0"));
        batteryMetric.set((batt<0?"—":batt+"%"),String.format(Locale.US,"%.1f °C",battTemp),Math.max(0,batt));

        if(loggingSwitch!=null) {
            boolean rec="1".equals(TelemetryStore.get(m,"file_logging","0"));
            suppressSwitchCallbacks=true;
            loggingSwitch.setChecked(rec);
            suppressSwitchCallbacks=false;
        }

        if(loggerPath!=null) {
            String path=TelemetryStore.get(m,"file_path","");
            loggerPath.setText(path.isEmpty()?"No active file.":path);
        }
    }

    private CpuClock parseCpuClock(String policies) {
        int cur=0,max=0,count=0;
        for(String item:policies.split(";")) {
            int eq=item.indexOf('=');
            int slash=item.indexOf('/',eq+1);
            int dash=item.indexOf('-',slash+1);
            int at=item.indexOf('@',dash+1);
            if(eq<0||slash<0||dash<0) continue;
            try {
                int c=Integer.parseInt(item.substring(eq+1,slash));
                int mx=Integer.parseInt(item.substring(dash+1,at>dash?at:item.length()).replaceAll("[^0-9]",""));
                cur+=c; max+=mx; count++;
            } catch(Exception ignored) {}
        }
        if(count==0) return new CpuClock(0,0,0);
        int avgCur=cur/count, avgMax=max/count;
        int pct=avgMax>0?Math.max(0,Math.min(100,avgCur*100/avgMax)):0;
        return new CpuClock(avgCur,avgMax,pct);
    }

    private void quietUpdateCheck() {
        io.execute(() -> {
            try {
                UpdateManager.ReleaseInfo info=UpdateManager.check(this);
                int appInstalled=UpdateManager.installedAppVersion(this);
                int moduleInstalled=UpdateManager.installedModuleVersion();
                releaseInfo=info;
                boolean newer=info.appVersionCode>appInstalled || info.moduleVersionCode>moduleInstalled;
                if(newer) runOnUiThread(() -> {
                    updateBanner.setText("UPDATE AVAILABLE  •  "+info.appVersionName+" / "+info.moduleVersionName);
                    updateBanner.setVisibility(View.VISIBLE);
                });
            } catch(Exception ignored) {}
        });
    }

    private void checkUpdates(boolean quiet) {
        if(updateStatus!=null) updateStatus.setText("Checking dev-latest…");
        io.execute(() -> {
            try {
                UpdateManager.ReleaseInfo info=UpdateManager.check(this);
                int appInstalled=UpdateManager.installedAppVersion(this);
                int moduleInstalled=UpdateManager.installedModuleVersion();
                releaseInfo=info;
                boolean appNew=info.appVersionCode>appInstalled;
                boolean moduleNew=info.moduleVersionCode>moduleInstalled;

                runOnUiThread(() -> {
                    if(updateStatus!=null) updateStatus.setText(
                            "APP\n  installed  "+BuildConfig.VERSION_NAME+" ("+appInstalled+")\n"+
                            "  available  "+info.appVersionName+" ("+info.appVersionCode+")\n"+
                            "  "+(appNew?"UPDATE AVAILABLE":"current")+"\n\n"+
                            "MODULE\n  installed  "+moduleInstalled+"\n"+
                            "  available  "+info.moduleVersionName+" ("+info.moduleVersionCode+")\n"+
                            "  "+(moduleNew?"UPDATE AVAILABLE":"current"));
                    if(appUpdateButton!=null) appUpdateButton.setEnabled(appNew);
                    if(moduleUpdateButton!=null) moduleUpdateButton.setEnabled(moduleNew);
                });
            } catch(Exception e) {
                if(!quiet) runOnUiThread(() -> {
                    if(updateStatus!=null) updateStatus.setText("Update check failed:\n"+e.getMessage());
                });
            }
        });
    }

    private void downloadAppUpdate() {
        UpdateManager.ReleaseInfo info=releaseInfo;
        if(info==null){checkUpdates(false);return;}
        setUpdateBusy("Downloading app update…");
        io.execute(() -> {
            try {
                File apk=UpdateManager.download(this,info.app,(done,total) ->
                        runOnUiThread(() -> setUpdateProgress("Downloading APK",done,total)));
                runOnUiThread(() -> {
                    if(updateStatus!=null) updateStatus.setText("APK verified. Opening Android installer…");
                    try{UpdateManager.installApk(this,apk);}
                    catch(Exception e){if(updateStatus!=null) updateStatus.setText(e.getMessage());}
                });
            } catch(Exception e){runOnUiThread(() -> updateError(e));}
        });
    }

    private void downloadModuleUpdate() {
        UpdateManager.ReleaseInfo info=releaseInfo;
        if(info==null){checkUpdates(false);return;}
        setUpdateBusy("Downloading module…");
        io.execute(() -> {
            try {
                File zip=UpdateManager.download(this,info.module,(done,total) ->
                        runOnUiThread(() -> setUpdateProgress("Downloading module",done,total)));
                RootBridge.Result r=UpdateManager.installModule(zip);
                runOnUiThread(() -> {
                    if(!r.ok()) {
                        if(updateStatus!=null) updateStatus.setText("Module install failed:\n"+r.output);
                        return;
                    }
                    if(updateStatus!=null) updateStatus.setText("Module staged. Reboot required.");
                    new AlertDialog.Builder(this)
                            .setTitle("Module update installed")
                            .setMessage("Reboot now to load the updated backend?")
                            .setNegativeButton("Later",null)
                            .setPositiveButton("Reboot",(d,w) -> io.execute(() -> RootBridge.get().exec("reboot")))
                            .show();
                });
            } catch(Exception e){runOnUiThread(() -> updateError(e));}
        });
    }

    private void setUpdateBusy(String s) {
        if(updateStatus!=null) updateStatus.setText(s);
        if(appUpdateButton!=null) appUpdateButton.setEnabled(false);
        if(moduleUpdateButton!=null) moduleUpdateButton.setEnabled(false);
    }

    private void setUpdateProgress(String label,long done,long total) {
        if(updateStatus==null) return;
        if(total>0) updateStatus.setText(label+"… "+Math.min(100,done*100/total)+"%");
        else updateStatus.setText(label+"…");
    }

    private void updateError(Exception e) {
        if(updateStatus!=null) updateStatus.setText("Update failed:\n"+e.getMessage());
    }

    private void experimental() {
        new AlertDialog.Builder(this)
                .setTitle("Experimental 792 MHz")
                .setMessage("Apply the session-only 792 MHz profile? AutoBoost never selects this automatically.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Apply",(d,w) -> ctl("apply experimental792"))
                .show();
    }

    private void ctl(String command) {
        io.execute(() -> {
            RootBridge.Result r=RootBridge.get().ctl(command);
            runOnUiThread(() -> {
                if(!r.ok()) Toast.makeText(this,"Backend command failed",Toast.LENGTH_LONG).show();
                refreshTelemetry();
                refreshBackendState();
            });
        });
    }

    private MetricUi metricCard(String name) {
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(10),dp(12),dp(10));
        root.setBackgroundResource(R.drawable.bg_card);

        LinearLayout head=row();
        TextView title=text(name,13,Color.rgb(156,176,201),true);
        TextView value=text("—",19,Color.WHITE,true);
        head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        head.addView(value);
        root.addView(head);

        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(7));
        bp.setMargins(0,dp(8),0,dp(6));
        root.addView(bar,bp);

        TextView detail=text("Waiting for telemetry…",11,Color.rgb(156,176,201),false);
        root.addView(detail);
        return new MetricUi(root,value,detail,bar);
    }

    private void section(String title,String subtitle) {
        TextView t=text(title,16,Color.WHITE,true);
        t.setPadding(0,dp(16),0,dp(2));
        page.addView(t);
        TextView s=text(subtitle,12,Color.rgb(156,176,201),false);
        s.setPadding(0,0,0,dp(7));
        page.addView(s);
    }

    private LinearLayout card(View child) {
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(dp(12),dp(10),dp(12),dp(10));
        c.addView(child);
        return c;
    }

    private Button tabButton(String s,int i){return button(s,false,v -> showTab(i));}
    private Button button(String s,boolean primary,View.OnClickListener l){
        Button b=new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(12); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackgroundResource(primary?R.drawable.bg_button_primary:R.drawable.bg_button_secondary);
        b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s,int sp,int color,boolean bold){
        TextView v=new TextView(this);
        v.setText(s);v.setTextSize(sp);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }

    private TextView mono(String s){
        TextView v=text(s,12,Color.WHITE,false);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextIsSelectable(true);
        return v;
    }

    private LinearLayout row(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weight(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
        p.setMargins(dp(3),dp(3),dp(3),dp(3));
        return p;
    }

    private LinearLayout.LayoutParams tabWeight(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(118),-2);
        p.setMargins(dp(3),dp(3),dp(3),dp(3));
        return p;
    }

    private LinearLayout.LayoutParams full(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(4),0,dp(4));
        return p;
    }

    private int parseInt(String s){try{return Integer.parseInt(s);}catch(Exception e){return 0;}}
    private float parseFloat(String s){try{return Float.parseFloat(s);}catch(Exception e){return 0f;}}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}

    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(!active)return;
            refreshTelemetry();
            handler.postDelayed(this,1000);
        }
    };

    @Override protected void onResume(){
        super.onResume();
        active=true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        refreshBackendState();
    }

    @Override protected void onPause(){
        active=false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy(){
        io.shutdownNow();
        super.onDestroy();
    }

    private interface ToggleAction { void changed(boolean checked); }

    private static final class MetricUi {
        final LinearLayout root;
        final TextView value,detail;
        final ProgressBar bar;
        MetricUi(LinearLayout root,TextView value,TextView detail,ProgressBar bar){
            this.root=root;this.value=value;this.detail=detail;this.bar=bar;
        }
        void set(String main,String sub,int percent){
            value.setText(main);detail.setText(sub);bar.setProgress(Math.max(0,Math.min(100,percent)));
        }
    }

    private static final class CpuClock {
        final int currentMhz,maxMhz,percent;
        CpuClock(int currentMhz,int maxMhz,int percent){
            this.currentMhz=currentMhz;this.maxMhz=maxMhz;this.percent=percent;
        }
    }
}
