package com.dafthacker.kb1001perf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private boolean active;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("KB1001 Performance Manager");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Allwinner A333 • rooted KB1001 • GPU-first alpha\nAutoBoost never uses Extreme 792 and does not disable thermal protection.");
        sub.setTextSize(14);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        addHeader(root, "Manual GPU profile");
        LinearLayout row1 = row();
        row1.addView(button("Dynamic 744", v -> ctl("persist dynamic744")), weight());
        row1.addView(button("Performance 744", v -> ctl("persist performance744")), weight());
        root.addView(row1);

        LinearLayout row2 = row();
        row2.addView(button("Stock 696", v -> ctl("persist stock")), weight());
        row2.addView(button("Extreme 792 TEST", v -> confirmExtreme()), weight());
        root.addView(row2);

        addHeader(root, "Automatic game boost");
        LinearLayout row3 = row();
        row3.addView(button("Enable AutoBoost", v -> ctl("auto enable")), weight());
        row3.addView(button("Disable AutoBoost", v -> ctl("auto disable")), weight());
        root.addView(row3);

        LinearLayout row4 = row();
        row4.addView(button("Games → Perf 744", v -> ctl("auto profile performance744")), weight());
        row4.addView(button("Games → Dynamic 744", v -> ctl("auto profile dynamic744")), weight());
        root.addView(row4);

        TextView idleLabel = new TextView(this);
        idleLabel.setText("Idle profile");
        idleLabel.setTextSize(14);
        idleLabel.setPadding(0, dp(8), 0, dp(2));
        root.addView(idleLabel);

        LinearLayout row5 = row();
        row5.addView(button("Idle → Dynamic 744", v -> ctl("auto idle dynamic744")), weight());
        row5.addView(button("Idle → Stock 696", v -> ctl("auto idle stock")), weight());
        root.addView(row5);

        TextView pollLabel = new TextView(this);
        pollLabel.setText("Detection interval");
        pollLabel.setTextSize(14);
        pollLabel.setPadding(0, dp(8), 0, dp(2));
        root.addView(pollLabel);

        LinearLayout row6 = row();
        row6.addView(button("1 sec", v -> ctl("auto poll 1")), weight());
        row6.addView(button("2 sec", v -> ctl("auto poll 2")), weight());
        row6.addView(button("5 sec", v -> ctl("auto poll 5")), weight());
        root.addView(row6);

        Button games = button("Choose AutoBoost games", v -> startActivity(new Intent(this, GamePickerActivity.class)));
        root.addView(games, full());

        addHeader(root, "Live status");
        status = new TextView(this);
        status.setText("Requesting root / reading module…");
        status.setTextSize(13);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        status.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(status, full());

        LinearLayout backendRow = row();
        backendRow.addView(button("Refresh", v -> refresh()), weight());
        backendRow.addView(button("Restart backend", v -> restartBackend()), weight());
        root.addView(backendRow);

        TextView note = new TextView(this);
        note.setText("v0.1: the Magisk layer detects the foreground app and owns AutoBoost, so it keeps working if this app is closed. CPU/DDR controls will be added only after the tablet's real interfaces are mapped.");
        note.setTextSize(12);
        note.setPadding(0, dp(12), 0, dp(24));
        root.addView(note);

        return scroll;
    }

    private void addHeader(LinearLayout root, String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(18);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        h.setPadding(0, dp(14), 0, dp(6));
        root.addView(h);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        return p;
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(3), 0, dp(3));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void confirmExtreme() {
        new AlertDialog.Builder(this)
                .setTitle("Extreme 792 MHz")
                .setMessage("This is the existing session-only test profile. It is not stability-qualified and will never be used by AutoBoost. Reboot fallback remains Dynamic 744. Apply it now?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, w) -> ctl("apply extreme792"))
                .show();
    }

    private void ctl(String args) {
        status.setText("Running: kb1001ctl " + args + " …");
        executor.execute(() -> {
            RootShell.Result result = RootShell.ctl(args);
            runOnUiThread(() -> {
                if (!result.ok()) {
                    Toast.makeText(this, "Command failed (root/module?)", Toast.LENGTH_LONG).show();
                    status.setText("Command failed, exit=" + result.exitCode + "\n" + result.output);
                } else {
                    refresh();
                }
            });
        });
    }

    private void restartBackend() {
        status.setText("Restarting AutoBoost backend…");
        executor.execute(() -> {
            RootShell.Result stop = RootShell.ctl("auto stop");
            RootShell.Result start = RootShell.ctl("auto start");
            runOnUiThread(() -> {
                if (!start.ok()) {
                    status.setText("Backend restart failed.\n" + stop.output + "\n" + start.output);
                } else {
                    refresh();
                }
            });
        });
    }

    private void refresh() {
        executor.execute(() -> {
            RootShell.Result result = RootShell.ctl("status");
            String text;
            if (result.ok()) {
                text = result.output;
            } else {
                RootShell.Result id = RootShell.exec("id; test -x " + RootShell.CONTROLLER + "; echo module_controller=$?");
                text = "Unable to read KB1001 module.\n\n" + id.output +
                        "\n\nInstall/enable KB1001 GPU Profiles v1.2-AutoBoost, reboot, and grant this app root.";
            }
            runOnUiThread(() -> status.setText(text));
        });
    }

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refresh();
            handler.postDelayed(this, 3500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        active = false;
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresher);
        executor.shutdownNow();
        super.onDestroy();
    }
}
