package com.dafthacker.kb1001perf;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;

public class GamePickerActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<AppEntry> launcherApps = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private LinearLayout list;
    private ProgressBar progress;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("game_library", MODE_PRIVATE);
        setContentView(buildUi());
        load();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(18),dp(18),dp(18));
        root.setBackgroundResource(R.drawable.bg_app);

        TextView eyebrow = label("AUTOBOOST", 12, Color.rgb(92,232,255), true);
        eyebrow.setLetterSpacing(.14f);
        root.addView(eyebrow);
        root.addView(label("Game Library", 28, Color.WHITE, true));

        TextView help = label(
                "Games marked by Android are added automatically. Tap + to add any app. Long-press a game to remove it.",
                13, Color.rgb(156,176,201), false);
        help.setPadding(0,dp(5),0,dp(12));
        root.addView(help);

        progress = new ProgressBar(this);
        root.addView(progress);

        ScrollView scroller = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(list);
        root.addView(scroller, new LinearLayout.LayoutParams(-1,0,1));

        Button add = new Button(this);
        add.setText("+  Add app");
        add.setAllCaps(false);
        add.setTextColor(Color.WHITE);
        add.setBackgroundResource(R.drawable.bg_button_primary);
        add.setOnClickListener(v -> showAddMenu());
        root.addView(add);
        return root;
    }

    private void load() {
        io.execute(() -> {
            RootBridge.Result current = RootBridge.get().ctl("game list");
            selected.clear();
            if (current.ok()) {
                for (String line : current.output.split("\\R")) {
                    String p = line.trim();
                    if (!p.isEmpty()) selected.add(p);
                }
            }

            scanLauncherApps();
            autoDetectGames();

            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                render();
            });
        });
    }

    private void scanLauncherApps() {
        launcherApps.clear();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);

        PackageManager pm = getPackageManager();
        List<ResolveInfo> resolved = Build.VERSION.SDK_INT >= 33
                ? pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
                : pm.queryIntentActivities(launcher, 0);

        Map<String,AppEntry> unique = new LinkedHashMap<>();
        for (ResolveInfo r : resolved) {
            if (r.activityInfo == null || r.activityInfo.packageName == null) continue;
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            CharSequence cs = r.loadLabel(pm);
            String name = cs == null ? pkg : cs.toString();
            Drawable icon = r.loadIcon(pm);

            boolean androidGame = false;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (Build.VERSION.SDK_INT >= 26) {
                    androidGame = ai.category == ApplicationInfo.CATEGORY_GAME;
                }
            } catch (Exception ignored) {}

            unique.put(pkg, new AppEntry(name,pkg,icon,androidGame));
        }

        launcherApps.addAll(unique.values());
        launcherApps.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.US)));
    }

    private void autoDetectGames() {
        Set<String> ignored = prefs.getStringSet("ignored_games", Collections.emptySet());
        for (AppEntry app : launcherApps) {
            if (!app.androidGame || ignored.contains(app.pkg) || selected.contains(app.pkg)) continue;
            RootBridge.Result result = RootBridge.get().ctl("game add " + app.pkg);
            if (result.ok()) selected.add(app.pkg);
        }
    }

    private void render() {
        list.removeAllViews();
        if (selected.isEmpty()) {
            TextView empty = label("No AutoBoost games yet. Tap + to add one.",14,Color.rgb(156,176,201),false);
            empty.setPadding(dp(10),dp(28),dp(10),dp(28));
            list.addView(empty);
            return;
        }

        for (String pkg : selected) {
            AppEntry app = find(pkg);
            String name = app == null ? pkg : app.name;
            Drawable icon = app == null ? null : app.icon;
            boolean detected = app != null && app.androidGame;
            list.addView(gameCard(name,pkg,icon,detected));
        }
    }

    private View gameCard(String name, String pkg, Drawable icon, boolean detected) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12),dp(11),dp(12),dp(11));
        card.setBackgroundResource(R.drawable.bg_card);

        ImageView image = new ImageView(this);
        if (icon != null) image.setImageDrawable(icon);
        card.addView(image, new LinearLayout.LayoutParams(dp(42),dp(42)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12),0,0,0);
        texts.addView(label(name,16,Color.WHITE,true));
        TextView sub = label((detected ? "Auto-detected • " : "Manual • ") + pkg,
                11,Color.rgb(156,176,201),false);
        texts.addView(sub);
        card.addView(texts,new LinearLayout.LayoutParams(0,-2,1));

        TextView dot = label("●",15,Color.rgb(92,232,255),true);
        card.addView(dot);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(4),0,dp(4));
        card.setLayoutParams(lp);

        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setItems(new String[]{"Delete from AutoBoost"}, (d, which) -> deleteGame(pkg))
                    .show();
            return true;
        });
        return card;
    }

    private void deleteGame(String pkg) {
        io.execute(() -> {
            RootBridge.get().ctl("game remove " + pkg);
            selected.remove(pkg);

            AppEntry app = find(pkg);
            if (app != null && app.androidGame) {
                Set<String> ignored = new LinkedHashSet<>(
                        prefs.getStringSet("ignored_games", Collections.emptySet()));
                ignored.add(pkg);
                prefs.edit().putStringSet("ignored_games", ignored).apply();
            }

            runOnUiThread(this::render);
        });
    }

    private void showAddMenu() {
        List<AppEntry> choices = new ArrayList<>();
        for (AppEntry app : launcherApps) if (!selected.contains(app.pkg)) choices.add(app);
        if (choices.isEmpty()) {
            Toast.makeText(this,"All launcher apps are already in the library.",Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = new String[choices.size()];
        for (int i=0;i<choices.size();i++) labels[i] = choices.get(i).name + "\n" + choices.get(i).pkg;

        new AlertDialog.Builder(this)
                .setTitle("Add app to AutoBoost")
                .setItems(labels,(d,which) -> addManual(choices.get(which)))
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void addManual(AppEntry app) {
        io.execute(() -> {
            RootBridge.Result r = RootBridge.get().ctl("game add " + app.pkg);
            if (r.ok()) {
                selected.add(app.pkg);
                Set<String> ignored = new LinkedHashSet<>(
                        prefs.getStringSet("ignored_games", Collections.emptySet()));
                ignored.remove(app.pkg);
                prefs.edit().putStringSet("ignored_games", ignored).apply();
                runOnUiThread(this::render);
            } else {
                runOnUiThread(() -> Toast.makeText(this,"Could not add app.",Toast.LENGTH_LONG).show());
            }
        });
    }

    private AppEntry find(String pkg) {
        for (AppEntry a : launcherApps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private TextView label(String s,int sp,int color,boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return v;
    }

    private int dp(int x) { return Math.round(x*getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private static final class AppEntry {
        final String name,pkg;
        final Drawable icon;
        final boolean androidGame;
        AppEntry(String name,String pkg,Drawable icon,boolean androidGame) {
            this.name=name; this.pkg=pkg; this.icon=icon; this.androidGame=androidGame;
        }
    }
}
