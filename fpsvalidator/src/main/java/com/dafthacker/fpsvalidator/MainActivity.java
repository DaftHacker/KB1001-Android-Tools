package com.dafthacker.fpsvalidator;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    static {
        System.loadLibrary("fpsvalidator");
    }

    private static final int MODE_FIXED = 0;
    private static final int MODE_SWEEP = 1;
    private static final int MODE_STEP = 2;
    private static final int MODE_JITTER = 3;
    private static final int MODE_STALL = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SurfaceView surfaceView;
    private Surface surface;
    private TextView status;
    private TextView logPath;
    private boolean rendering;

    private native boolean nativeStart(Surface surface, String csvPath, int mode, float targetFps);
    private native void nativeStop();
    private native String nativeStatus();

    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            if (rendering) {
                String s = nativeStatus();
                if (s != null && !s.isEmpty()) status.setText(s);
            }
            handler.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(Color.rgb(8, 13, 18));

        TextView title = text("KB1001 GPU / FPS Validation Harness", 20, Color.WHITE, true);
        root.addView(title);

        TextView help = text(
                "Native OpenGL ES workload with independent per-frame timing. " +
                        "Use fixed rates or controlled patterns, then compare this CSV against Performance Manager.",
                11, Color.rgb(170, 185, 200), false);
        help.setPadding(0, dp(3), 0, dp(8));
        root.addView(help);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        status = text("Surface initializing…", 15, Color.rgb(105, 235, 170), true);
        status.setPadding(0, dp(7), 0, dp(4));
        root.addView(status);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);

        int[] fixed = {10, 15, 20, 24, 30, 40, 45, 50, 60};
        for (int fps : fixed) {
            buttons.addView(modeButton(fps + " FPS", () -> startSession(MODE_FIXED, fps)));
        }
        buttons.addView(modeButton("Sweep", () -> startSession(MODE_SWEEP, 0)));
        buttons.addView(modeButton("Step", () -> startSession(MODE_STEP, 0)));
        buttons.addView(modeButton("Jitter", () -> startSession(MODE_JITTER, 60)));
        buttons.addView(modeButton("Stalls", () -> startSession(MODE_STALL, 60)));
        buttons.addView(modeButton("Stop", this::stopSession));

        scroll.addView(buttons);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        logPath = text("No session log yet.", 10, Color.rgb(160, 175, 190), false);
        logPath.setPadding(0, dp(4), 0, 0);
        root.addView(logPath);

        setContentView(root);
        handler.post(statusTicker);
    }

    private Button modeButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(46));
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private void startSession(int mode, float targetFps) {
        if (surface == null || !surface.isValid()) {
            status.setText("Surface is not ready.");
            return;
        }

        stopSession();

        File dir = new File(getExternalFilesDir(null), "fps-validation");
        if (!dir.exists() && !dir.mkdirs()) {
            status.setText("Could not create validation log directory.");
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String modeName = modeName(mode, targetFps);
        File csv = new File(dir, "validator-" + stamp + "-" + modeName + ".csv");

        rendering = nativeStart(surface, csv.getAbsolutePath(), mode, targetFps);
        if (rendering) {
            logPath.setText("Truth log: " + csv.getAbsolutePath());
        } else {
            status.setText("Native renderer failed to start.");
        }
    }

    private void stopSession() {
        if (rendering) nativeStop();
        rendering = false;
    }

    private String modeName(int mode, float targetFps) {
        switch (mode) {
            case MODE_SWEEP: return "sweep";
            case MODE_STEP: return "step";
            case MODE_JITTER: return "jitter";
            case MODE_STALL: return "stalls";
            default: return "fixed-" + Math.round(targetFps);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        status.setText("Ready. Select a validation pattern.");
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surface = holder.getSurface();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        stopSession();
        surface = null;
        status.setText("Surface destroyed.");
    }

    @Override protected void onDestroy() {
        stopSession();
        handler.removeCallbacks(statusTicker);
        super.onDestroy();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
