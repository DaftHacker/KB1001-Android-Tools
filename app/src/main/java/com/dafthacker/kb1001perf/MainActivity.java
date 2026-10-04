package com.dafthacker.kb1001perf;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private TextView headerState;
    private TextView liveText;
    private TextView logText;
    private TextView updateStatus;
    private Button appUpdateButton;
    private Button moduleUpdateButton;
    private EditText tokenField;
    private UpdateManager.ReleaseInfo releaseInfo;
    private int tab;
    private boolean active;
    private boolean showHudAfterPermission;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TelemetryStore.ensureSnapshot(this);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
        setContentView(buildUi());
        showTab(0);
        io.execute(() -> RootBridge.get().ctl("status"));
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(12));
        root.setBackgroundResource(R.drawable.bg_app);

        TextView brand = text("KB1001", 13, Color.rgb(92,232,255), true);
        brand.setLetterSpacing(.18f);
        root.addView(brand);
        root.addView(text("Performance Manager", 30, Color.WHITE, true));

        headerState = text("Root backend • connecting…", 13, Color.rgb(156,176,201), false);
        headerState.setPadding(0, dp(4), 0, dp(14));
        root.addView(headerState);

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        tabs.addView(tabButton("CONTROL",0), tabWeight());
        tabs.addView(tabButton("GAMES",1), tabWeight());
        tabs.addView(tabButton("LOGS",2), tabWeight());
        tabs.addView(tabButton("UPDATES",3), tabWeight());
        tabScroll.addView(tabs);
        root.addView(tabScroll, new LinearLayout.LayoutParams(-1,-2));

        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(10), 0, dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        return root;
    }

    private void showTab(int index) {
        tab = index;
        page.removeAllViews();
        liveText = null;
        logText = null;
        updateStatus = null;
        appUpdateButton = null;
        moduleUpdateButton = null;

        if (index == 0) controlPage();
        else if (index == 1) gamesPage();
        else if (index == 2) logsPage();
        else updatesPage();

        refreshTelemetry();
    }

    private void controlPage() {
        section("IN-GAME HUD", "Draggable overlay with live profile, GPU, CPU, thermals, RAM and logger state.");
        LinearLayout r = row();
        r.addView(button("SHOW HUD", true, v -> showHud()), weight());
        r.addView(button("HIDE HUD", false, v -> stopService(new Intent(this, OverlayService.class))), weight());
        page.addView(card(r), full());

        LinearLayout ar = row();
        ar.addView(button("AUTO-SHOW IN GAMES", false, v -> ctl("overlay auto enable")), weight());
        ar.addView(button("AUTO-SHOW OFF", false, v -> ctl("overlay auto disable")), weight());
        page.addView(card(ar), full());

        section("GPU PROFILES", "792 MHz is experimental—not proven to be the A333 ceiling. AutoBoost never selects it.");
        LinearLayout p1 = row();
        p1.addView(button("DYNAMIC 744", true, v -> ctl("persist dynamic744")), weight());
        p1.addView(button("PERFORMANCE 744", false, v -> ctl("persist performance744")), weight());
        page.addView(card(p1), full());

        LinearLayout p2 = row();
        p2.addView(button("STOCK 696", false, v -> ctl("persist stock")), weight());
        p2.addView(button("EXPERIMENTAL 792", false, v -> experimental()), weight());
        page.addView(card(p2), full());

        section("AUTOBOOST", "The Magisk daemon remains alive with this app closed.");
        LinearLayout a = row();
        a.addView(button("ENABLE", true, v -> ctl("auto enable")), weight());
        a.addView(button("DISABLE", false, v -> ctl("auto disable")), weight());
        page.addView(card(a), full());

        liveText = mono("Waiting for telemetry…");
        page.addView(card(liveText), full());
    }

    private void gamesPage() {
        section("GAME LIBRARY", "Selected launcher packages trigger the game profile and can auto-open the HUD.");
        page.addView(card(button("CHOOSE AUTOBOOST GAMES", true,
                v -> startActivity(new Intent(this, GamePickerActivity.class)))), full());

        section("DETECTION", "Foreground detection belongs to the root backend, not the overlay.");
        LinearLayout r = row();
        r.addView(button("1 SEC", false, v -> ctl("auto poll 1")), weight());
        r.addView(button("2 SEC", false, v -> ctl("auto poll 2")), weight());
        r.addView(button("5 SEC", false, v -> ctl("auto poll 5")), weight());
        page.addView(card(r), full());

        TextView info = mono("AutoBoost: root daemon\nHUD: visual client\nApp closed: detection still active\nHUD closed: detection still active");
        page.addView(card(info), full());
    }

    private void logsPage() {
        section("PERFORMANCE LOGGER", "The root daemon samples hardware once; the app/HUD reads its mirrored snapshot without repeated su calls.");
        LinearLayout r = row();
        r.addView(button("START FILE LOG", true, v -> ctl("logger file on")), weight());
        r.addView(button("STOP FILE LOG", false, v -> ctl("logger file off")), weight());
        page.addView(card(r), full());

        LinearLayout rate = row();
        rate.addView(button("1 SEC", false, v -> ctl("logger interval 1")), weight());
        rate.addView(button("2 SEC", false, v -> ctl("logger interval 2")), weight());
        rate.addView(button("5 SEC", false, v -> ctl("logger interval 5")), weight());
        page.addView(card(rate), full());

        Button refresh = button("REFRESH RECENT SAMPLES", false, v -> refreshLogTail());
        page.addView(card(refresh), full());
        logText = mono("Open this tab and refresh to load the root history.\nCSV logging is optional and saved under Documents/KB1001Performance/logs.");
        page.addView(card(logText), full());
        refreshLogTail();
    }

    private void updatesPage() {
        section("SOFTWARE UPDATES", "One release feed updates both the Android front end and the Magisk backend.");

        updateStatus = mono(
                "Installed app: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n" +
                "Checking module version…");
        page.addView(card(updateStatus), full());

        Button check = button("CHECK FOR UPDATES", true, v -> checkUpdates());
        page.addView(card(check), full());

        appUpdateButton = button("DOWNLOAD APP UPDATE", false, v -> downloadAppUpdate());
        appUpdateButton.setEnabled(false);
        page.addView(card(appUpdateButton), full());

        moduleUpdateButton = button("DOWNLOAD + INSTALL MODULE", false, v -> downloadModuleUpdate());
        moduleUpdateButton.setEnabled(false);
        page.addView(card(moduleUpdateButton), full());

        section("PRIVATE GITHUB REPO", "This repo is private. A fine-grained token with read-only access to this repository lets the app query/download releases. Leave blank if releases are moved to a public repo.");
        tokenField = new EditText(this);
        tokenField.setText(UpdateManager.getToken(this));
        tokenField.setHint("github_pat_…");
        tokenField.setTextColor(Color.WHITE);
        tokenField.setHintTextColor(Color.rgb(110,125,145));
        tokenField.setSingleLine(true);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(card(tokenField), full());

        LinearLayout tokenRow = row();
        tokenRow.addView(button("SAVE TOKEN", false, v -> {
            UpdateManager.saveToken(this, tokenField.getText().toString());
            Toast.makeText(this, "Update token saved", Toast.LENGTH_SHORT).show();
        }), weight());
        tokenRow.addView(button("CLEAR TOKEN", false, v -> {
            tokenField.setText("");
            UpdateManager.saveToken(this, "");
            Toast.makeText(this, "Update token cleared", Toast.LENGTH_SHORT).show();
        }), weight());
        page.addView(card(tokenRow), full());

        TextView note = mono(
                "APK updates: SHA-256 verify → Android installer\n" +
                "Module updates: SHA-256 verify → magisk --install-module\n" +
                "A stable APK signing certificate is required for Android to accept in-place app updates.");
        page.addView(card(note), full());

        checkUpdates();
    }

    private void checkUpdates() {
        if (updateStatus == null) return;
        updateStatus.setText("Checking dev-latest…");
        appUpdateButton.setEnabled(false);
        moduleUpdateButton.setEnabled(false);

        io.execute(() -> {
            try {
                UpdateManager.ReleaseInfo info = UpdateManager.check(this);
                int appInstalled = UpdateManager.installedAppVersion(this);
                int moduleInstalled = UpdateManager.installedModuleVersion();
                releaseInfo = info;

                boolean appNew = info.appVersionCode > appInstalled;
                boolean moduleNew = info.moduleVersionCode > moduleInstalled;

                String s =
                        "APP\n" +
                        "  installed  " + BuildConfig.VERSION_NAME + " (" + appInstalled + ")\n" +
                        "  available  " + info.appVersionName + " (" + info.appVersionCode + ")\n" +
                        "  status     " + (appNew ? "UPDATE AVAILABLE" : "current") + "\n\n" +
                        "MODULE\n" +
                        "  installed  code " + moduleInstalled + "\n" +
                        "  available  " + info.moduleVersionName + " (" + info.moduleVersionCode + ")\n" +
                        "  status     " + (moduleNew ? "UPDATE AVAILABLE" : "current") + "\n\n" +
                        "commit " + info.commit;

                runOnUiThread(() -> {
                    if (updateStatus != null) updateStatus.setText(s);
                    if (appUpdateButton != null) appUpdateButton.setEnabled(appNew);
                    if (moduleUpdateButton != null) moduleUpdateButton.setEnabled(moduleNew);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (updateStatus != null) {
                        updateStatus.setText("Update check failed:\n" + e.getMessage() +
                                "\n\nIf the repository remains private, save a read-only GitHub token below.");
                    }
                });
            }
        });
    }

    private void downloadAppUpdate() {
        UpdateManager.ReleaseInfo info = releaseInfo;
        if (info == null) { checkUpdates(); return; }
        setUpdateBusy("Downloading app update…");

        io.execute(() -> {
            try {
                File apk = UpdateManager.download(this, info.app, (done,total) ->
                        runOnUiThread(() -> setUpdateProgress("Downloading APK", done, total)));
                runOnUiThread(() -> {
                    if (updateStatus != null) updateStatus.setText("APK downloaded and SHA-256 verified. Opening Android installer…");
                    try {
                        UpdateManager.installApk(this, apk);
                    } catch (Exception e) {
                        if (updateStatus != null) updateStatus.setText(e.getMessage());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> updateError(e));
            }
        });
    }

    private void downloadModuleUpdate() {
        UpdateManager.ReleaseInfo info = releaseInfo;
        if (info == null) { checkUpdates(); return; }
        setUpdateBusy("Downloading Magisk module…");

        io.execute(() -> {
            try {
                File zip = UpdateManager.download(this, info.module, (done,total) ->
                        runOnUiThread(() -> setUpdateProgress("Downloading module", done, total)));

                runOnUiThread(() -> {
                    if (updateStatus != null) updateStatus.setText("Module ZIP downloaded and verified. Installing through Magisk…");
                });

                RootBridge.Result r = UpdateManager.installModule(zip);
                runOnUiThread(() -> {
                    if (!r.ok()) {
                        if (updateStatus != null) updateStatus.setText("Magisk module install failed:\n" + r.output);
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Module update installed")
                            .setMessage("Magisk accepted the new module. Reboot now to load the updated backend?")
                            .setNegativeButton("Later", null)
                            .setPositiveButton("Reboot", (d,w) -> io.execute(() -> RootBridge.get().exec("reboot")))
                            .show();
                    if (updateStatus != null) updateStatus.setText("Module update staged successfully. Reboot required.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> updateError(e));
            }
        });
    }

    private void setUpdateBusy(String text) {
        if (updateStatus != null) updateStatus.setText(text);
        if (appUpdateButton != null) appUpdateButton.setEnabled(false);
        if (moduleUpdateButton != null) moduleUpdateButton.setEnabled(false);
    }

    private void setUpdateProgress(String label, long done, long total) {
        if (updateStatus == null) return;
        if (total > 0) {
            long pct = Math.min(100, (done * 100) / total);
            updateStatus.setText(label + "… " + pct + "%\n" + human(done) + " / " + human(total));
        } else {
            updateStatus.setText(label + "… " + human(done));
        }
    }

    private void updateError(Exception e) {
        if (updateStatus != null) updateStatus.setText("Update failed:\n" + e.getMessage());
        if (appUpdateButton != null) appUpdateButton.setEnabled(releaseInfo != null);
        if (moduleUpdateButton != null) moduleUpdateButton.setEnabled(releaseInfo != null);
    }

    private String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024*1024) return String.format("%.1f KB", b/1024.0);
        return String.format("%.1f MB", b/(1024.0*1024.0));
    }

    private void refreshLogTail() {
        if (logText == null) return;
        io.execute(() -> {
            RootBridge.Result r = RootBridge.get().ctl("logger tail 60");
            runOnUiThread(() -> {
                if (logText != null)
                    logText.setText(r.ok() ? r.output : "Logger history unavailable:\n" + r.output);
            });
        });
    }

    private void showHud() {
        if (!Settings.canDrawOverlays(this)) {
            showHudAfterPermission = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Intent i = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void experimental() {
        new AlertDialog.Builder(this)
                .setTitle("Experimental 792 MHz")
                .setMessage("792 MHz works as a session-only OPP on this tablet, but we have not yet established its thermal or stability margin. It is not automatically selected. Apply it for a logged test session?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d,w) -> ctl("apply experimental792"))
                .show();
    }

    private void ctl(String command) {
        io.execute(() -> {
            RootBridge.Result r = RootBridge.get().ctl(command);
            runOnUiThread(() -> {
                if (!r.ok()) Toast.makeText(this, "Backend command failed: " + r.output, Toast.LENGTH_LONG).show();
                refreshTelemetry();
                if (tab == 2) refreshLogTail();
            });
        });
    }

    private void refreshTelemetry() {
        Map<String,String> m = TelemetryStore.read(this);
        String mode = TelemetryStore.get(m,"mode","waiting");
        String profile = TelemetryStore.get(m,"profile","—");
        String gpu = TelemetryStore.get(m,"gpu_clock_mhz","—");
        String temp = TelemetryStore.get(m,"thermal_max_c","—");
        headerState.setText(mode.toUpperCase() + "  •  " + profile + "  •  GPU " + gpu + " MHz  •  " + temp + "°C");
        if (liveText != null) liveText.setText(TelemetryStore.pretty(m));
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refreshTelemetry();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        active = true;
        if (showHudAfterPermission && Settings.canDrawOverlays(this)) {
            showHudAfterPermission = false;
            showHud();
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
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

    private void section(String title, String subtitle) {
        TextView t = text(title, 16, Color.WHITE, true);
        t.setPadding(0, dp(16), 0, dp(2));
        page.addView(t);
        TextView s = text(subtitle, 12, Color.rgb(156,176,201), false);
        s.setPadding(0, 0, 0, dp(7));
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

    private Button tabButton(String s, int i) { return button(s, false, v -> showTab(i)); }

    private Button button(String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackgroundResource(primary ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s,int sp,int color,boolean bold) {
        TextView v=new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if(bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }

    private TextView mono(String s) {
        TextView v=text(s,12,Color.WHITE,false);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextIsSelectable(true);
        return v;
    }

    private LinearLayout row() {
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
        p.setMargins(dp(3),dp(3),dp(3),dp(3));
        return p;
    }

    private LinearLayout.LayoutParams tabWeight() {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(118),-2);
        p.setMargins(dp(3),dp(3),dp(3),dp(3));
        return p;
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(4),0,dp(4));
        return p;
    }

    private int dp(int x) {
        return Math.round(x*getResources().getDisplayMetrics().density);
    }
}
