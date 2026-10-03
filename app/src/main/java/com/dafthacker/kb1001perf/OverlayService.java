package com.dafthacker.kb1001perf;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlayService extends Service {
    private static final String CHANNEL = "kb1001_hud";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private LinearLayout overlay;
    private TextView title;
    private TextView metrics;
    private LinearLayout details;
    private boolean compact;
    private float touchX, touchY;
    private int startX, startY;

    @Override public void onCreate() {
        super.onCreate();
        TelemetryStore.ensureSnapshot(this);
        createChannel();
        startForeground(1001, notification());
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showOverlay();
        handler.post(updateLoop);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void showOverlay() {
        if (overlay != null) return;
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(12), dp(10), dp(12), dp(10));
        overlay.setBackgroundResource(R.drawable.bg_card);
        overlay.setElevation(dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = text("KB1  •  HUD", 13, Color.rgb(92,232,255), true);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button fold = mini("▣");
        fold.setOnClickListener(v -> {
            compact = !compact;
            details.setVisibility(compact ? View.GONE : View.VISIBLE);
        });
        head.addView(fold);

        Button close = mini("×");
        close.setOnClickListener(v -> stopSelf());
        head.addView(close);
        overlay.addView(head);

        details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        metrics = text("Waiting for telemetry…", 12, Color.WHITE, false);
        metrics.setTypeface(android.graphics.Typeface.MONOSPACE);
        metrics.setPadding(0, dp(7), 0, dp(7));
        details.addView(metrics);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button dyn = mini("DYN 744");
        dyn.setOnClickListener(v -> ctl("persist dynamic744"));
        Button perf = mini("PERF 744");
        perf.setOnClickListener(v -> ctl("persist performance744"));
        Button log = mini("LOG");
        log.setOnClickListener(v -> ctl("logger file toggle"));
        buttons.addView(dyn);
        buttons.addView(perf);
        buttons.addView(log);
        details.addView(buttons);
        overlay.addView(details);

        params = new WindowManager.LayoutParams(
                dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(110);

        head.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchX = e.getRawX(); touchY = e.getRawY();
                    startX = params.x; startY = params.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = startX + Math.round(e.getRawX() - touchX);
                    params.y = startY + Math.round(e.getRawY() - touchY);
                    wm.updateViewLayout(overlay, params);
                    return true;
            }
            return false;
        });

        wm.addView(overlay, params);
    }

    private void ctl(String command) {
        io.execute(() -> RootBridge.get().ctl(command));
    }

    private final Runnable updateLoop = new Runnable() {
        @Override public void run() {
            if (overlay == null) return;
            io.execute(() -> {
                Map<String,String> m = TelemetryStore.read(OverlayService.this);
                String mode = TelemetryStore.get(m,"mode","—").toUpperCase();
                String profile = TelemetryStore.get(m,"profile","—");
                String pkg = TelemetryStore.get(m,"package","—");
                String gpu = TelemetryStore.get(m,"gpu_clock_mhz","—");
                String temp = TelemetryStore.get(m,"thermal_max_c","—");
                String cpu = TelemetryStore.get(m,"cpu_summary","—");
                String ram = TelemetryStore.get(m,"mem_available_mb","—");
                String log = "1".equals(TelemetryStore.get(m,"file_logging","0")) ? "REC" : "OFF";
                String body = mode + "  •  " + profile + "\n" +
                        "GPU " + gpu + " MHz   TEMP " + temp + "°C\n" +
                        "CPU " + cpu + "\n" +
                        "RAM " + ram + " MB   LOG " + log + "\n" +
                        pkg;
                handler.post(() -> {
                    if (metrics != null) metrics.setText(body);
                    if (title != null) title.setText("KB1  •  " + mode);
                });
            });
            handler.postDelayed(this, 1000);
        }
    };

    private Button mini(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(10);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(8), dp(3), dp(8), dp(3));
        b.setBackgroundResource(R.drawable.bg_button_secondary);
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return v;
    }

    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "KB1001 In-Game HUD", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Keeps the user-enabled performance overlay available over games.");
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_speed)
                .setContentTitle("KB1001 performance HUD")
                .setContentText("Live in-game telemetry overlay")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(updateLoop);
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
            overlay = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
