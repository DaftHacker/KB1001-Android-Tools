package com.dafthacker.kb1001perf;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GamePickerActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ListView listView;
    private ProgressBar progress;
    private final List<AppEntry> apps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        load();
    }

    private View buildUi() {
        int pad = Math.round(14 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Choose AutoBoost games");
        title.setTextSize(22);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Checked launcher apps are treated as games. When one becomes the foreground app, the Magisk daemon applies the configured game profile and restores the idle profile when you leave it.");
        help.setPadding(0, 4, 0, 10);
        root.addView(help);

        progress = new ProgressBar(this);
        root.addView(progress);

        listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button save = new Button(this);
        save.setText("Save game list");
        save.setAllCaps(false);
        save.setOnClickListener(v -> save());
        root.addView(save);

        return root;
    }

    private void load() {
        executor.execute(() -> {
            RootShell.Result current = RootShell.ctl("game list");
            Set<String> selected = new HashSet<>();
            if (current.ok() && !current.output.isEmpty()) {
                for (String line : current.output.split("\\R")) {
                    String p = line.trim();
                    if (!p.isEmpty()) selected.add(p);
                }
            }

            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved;
            if (Build.VERSION.SDK_INT >= 33) {
                resolved = pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0));
            } else {
                resolved = pm.queryIntentActivities(launcher, 0);
            }

            Map<String, AppEntry> unique = new LinkedHashMap<>();
            for (ResolveInfo r : resolved) {
                if (r.activityInfo == null || r.activityInfo.packageName == null) continue;
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                CharSequence labelCs = r.loadLabel(pm);
                String label = labelCs == null ? pkg : labelCs.toString();
                unique.put(pkg, new AppEntry(label, pkg));
            }

            apps.clear();
            apps.addAll(unique.values());
            Collections.sort(apps, Comparator.comparing(a -> a.label.toLowerCase()));

            ArrayList<String> rows = new ArrayList<>();
            for (AppEntry a : apps) rows.add(a.label + "\n" + a.pkg);

            runOnUiThread(() -> {
                listView.setAdapter(new ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_multiple_choice, rows));
                for (int i = 0; i < apps.size(); i++) {
                    if (selected.contains(apps.get(i).pkg)) listView.setItemChecked(i, true);
                }
                progress.setVisibility(View.GONE);
            });
        });
    }

    private void save() {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < apps.size(); i++) {
            if (listView.isItemChecked(i)) selected.add(apps.get(i).pkg);
        }

        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            RootShell.Result clear = RootShell.ctl("game clear");
            boolean ok = clear.ok();
            String lastError = clear.output;
            if (ok) {
                for (String pkg : selected) {
                    RootShell.Result r = RootShell.ctl("game add " + pkg);
                    if (!r.ok()) {
                        ok = false;
                        lastError = r.output;
                        break;
                    }
                }
            }
            boolean success = ok;
            String error = lastError;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (success) {
                    Toast.makeText(this, "Saved " + selected.size() + " AutoBoost games", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Could not save")
                            .setMessage(error.isEmpty() ? "Check root access and module installation." : error)
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class AppEntry {
        final String label;
        final String pkg;
        AppEntry(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
        }
    }
}
