package com.dafthacker.kb1001perf;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;

public class OverlayService extends Service {
    private static final String CHANNEL="kb1001_hud";
    private static volatile boolean running;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();

    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private LinearLayout overlay;
    private LinearLayout details;
    private TextView title;
    private TextView subtitle;
    private TextView footer;
    private MiniMetric cpu;
    private MiniMetric gpu;
    private MiniMetric ram;
    private MiniMetric thermal;

    private float downX,downY;
    private int startX,startY;

    public static boolean isRunning(){return running;}

    @Override public void onCreate(){
        super.onCreate();
        running=true;
        TelemetryStore.ensureSnapshot(this);
        createChannel();
        startForeground(1001,notification());

        if(!Settings.canDrawOverlays(this)){
            stopSelf();
            return;
        }

        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        createOverlay();
        handler.post(updateLoop);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent != null && intent.getAction() != null){
            String action=intent.getAction();
            if("kb1001.stop_hud".equals(action)){
                stopSelf();
                return START_NOT_STICKY;
            }
            if("kb1001.dynamic744".equals(action)){
                ctl("persist dynamic744");
            } else if("kb1001.performance744".equals(action)){
                ctl("persist performance744");
            }
        }
        return START_STICKY;
    }

    private void createOverlay(){
        overlay=new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(11),dp(9),dp(11),dp(9));
        overlay.setBackgroundResource(R.drawable.bg_card);
        overlay.setElevation(dp(14));

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout names=new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        title=txt("KB1  •  READY",12,Color.rgb(92,232,255),true);
        subtitle=txt("Waiting for game state",10,Color.rgb(180,194,210),false);
        names.addView(title);
        names.addView(subtitle);
        head.addView(names,new LinearLayout.LayoutParams(0,-2,1));

        Button fold=mini("—");
        fold.setOnClickListener(v -> {
            boolean hide=details.getVisibility()==View.VISIBLE;
            details.setVisibility(hide?View.GONE:View.VISIBLE);
            fold.setText(hide?"+":"—");
        });
        head.addView(fold);

        Button close=mini("×");
        close.setOnClickListener(v -> stopSelf());
        head.addView(close);
        overlay.addView(head);

        details=new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(0,dp(7),0,0);

        cpu=miniMetric("CPU");
        gpu=miniMetric("GPU");
        ram=miniMetric("RAM");
        thermal=miniMetric("TEMP");

        details.addView(cpu.root);
        details.addView(gpu.root);
        details.addView(ram.root);
        details.addView(thermal.root);

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0,dp(5),0,0);

        Button dyn=mini("DYN");
        dyn.setOnClickListener(v -> ctl("persist dynamic744"));
        Button perf=mini("PERF");
        perf.setOnClickListener(v -> ctl("persist performance744"));
        Button rec=mini("REC");
        rec.setOnClickListener(v -> ctl("logger file toggle"));

        actions.addView(dyn);
        actions.addView(perf);
        actions.addView(rec);
        details.addView(actions);

        footer=txt("",9,Color.rgb(156,176,201),false);
        footer.setPadding(0,dp(5),0,0);
        footer.setSingleLine(true);
        footer.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(footer);

        overlay.addView(details);

        params=new WindowManager.LayoutParams(
                dp(286),WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;
        params.x=dp(14);
        params.y=dp(90);

        head.setOnTouchListener((v,e) -> {
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=e.getRawX(); downY=e.getRawY();
                    startX=params.x; startY=params.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x=startX+Math.round(e.getRawX()-downX);
                    params.y=startY+Math.round(e.getRawY()-downY);
                    wm.updateViewLayout(overlay,params);
                    return true;
            }
            return false;
        });

        wm.addView(overlay,params);
    }

    private final Runnable updateLoop=new Runnable(){
        @Override public void run(){
            if(overlay==null)return;

            Map<String,String> m=TelemetryStore.read(OverlayService.this);
            String mode=TelemetryStore.get(m,"mode","idle").toUpperCase(Locale.US);
            String profile=TelemetryStore.get(m,"profile","—");
            String pkg=TelemetryStore.get(m,"package","No selected game");
            int gpuMhz=parseInt(TelemetryStore.get(m,"gpu_clock_mhz","0"));
            float temp=parseFloat(TelemetryStore.get(m,"thermal_max_c","0"));

            ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo();
            ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
            long total=mi.totalMem/1024/1024;
            long avail=mi.availMem/1024/1024;
            long used=Math.max(0,total-avail);
            int ramPct=total>0?(int)Math.min(100,used*100/total):0;

            CpuClock clocks=parseCpu(TelemetryStore.get(m,"cpu_policies",""));
            int gpuPct=Math.max(0,Math.min(100,Math.round(gpuMhz*100f/792f)));
            int tempPct=Math.max(0,Math.min(100,Math.round(temp/85f*100)));
            boolean recording="1".equals(TelemetryStore.get(m,"file_logging","0"));

            title.setText("KB1  •  "+mode+"  •  "+profile);
            subtitle.setText(mode.equals("GAME")?pkg:"AutoBoost standby");

            cpu.set(clocks.current+" MHz",clocks.percent);
            gpu.set(gpuMhz+" MHz",gpuPct);
            ram.set(ramPct+"%",ramPct);
            thermal.set(String.format(Locale.US,"%.1f°C",temp),tempPct);
            footer.setText((recording?"● REC  •  ":"")+"drag header • tap — to collapse");

            handler.postDelayed(this,1000);
        }
    };

    private MiniMetric miniMetric(String name){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(0,dp(2),0,dp(2));

        TextView label=txt(name,10,Color.rgb(156,176,201),true);
        root.addView(label,new LinearLayout.LayoutParams(dp(42),-2));

        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar,new LinearLayout.LayoutParams(0,dp(5),1));

        TextView value=txt("—",10,Color.WHITE,true);
        value.setGravity(Gravity.END);
        root.addView(value,new LinearLayout.LayoutParams(dp(70),-2));

        return new MiniMetric(root,value,bar);
    }

    private void ctl(String command){
        io.execute(() -> RootBridge.get().ctl(command));
    }

    private CpuClock parseCpu(String policies){
        int cur=0,max=0,count=0;
        for(String item:policies.split(";")){
            int eq=item.indexOf('=');
            int slash=item.indexOf('/',eq+1);
            int dash=item.indexOf('-',slash+1);
            int at=item.indexOf('@',dash+1);
            if(eq<0||slash<0||dash<0)continue;
            try{
                int c=Integer.parseInt(item.substring(eq+1,slash));
                int mx=Integer.parseInt(item.substring(dash+1,at>dash?at:item.length()).replaceAll("[^0-9]",""));
                cur+=c;max+=mx;count++;
            }catch(Exception ignored){}
        }
        if(count==0)return new CpuClock(0,0);
        int avg=cur/count,limit=max/count;
        int pct=limit>0?Math.max(0,Math.min(100,avg*100/limit)):0;
        return new CpuClock(avg,pct);
    }

    private Button mini(String label){
        Button b=new Button(this);
        b.setText(label);
        b.setTextSize(9);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setMinHeight(0);b.setMinimumHeight(0);
        b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(8),dp(2),dp(8),dp(2));
        b.setBackgroundResource(R.drawable.bg_button_secondary);
        return b;
    }

    private TextView txt(String s,int sp,int color,boolean bold){
        TextView v=new TextView(this);
        v.setText(s);v.setTextSize(sp);v.setTextColor(color);
        if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        return v;
    }

    private int parseInt(String s){try{return Integer.parseInt(s);}catch(Exception e){return 0;}}
    private float parseFloat(String s){try{return Float.parseFloat(s);}catch(Exception e){return 0f;}}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(
                    CHANNEL,"KB1001 In-Game HUD",NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Live KB1001 game performance HUD.");
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent openPi=PendingIntent.getActivity(this,1,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Intent dyn=new Intent(this,OverlayService.class).setAction("kb1001.dynamic744");
        PendingIntent dynPi=PendingIntent.getService(this,2,dyn,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Intent perf=new Intent(this,OverlayService.class).setAction("kb1001.performance744");
        PendingIntent perfPi=PendingIntent.getService(this,3,perf,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Intent stop=new Intent(this,OverlayService.class).setAction("kb1001.stop_hud");
        PendingIntent stopPi=PendingIntent.getService(this,4,stop,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_speed)
                .setContentTitle("KB1001 performance monitor")
                .setContentText("HUD and live telemetry are running")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null,"Dynamic 744",dynPi).build())
                .addAction(new Notification.Action.Builder(null,"Performance 744",perfPi).build())
                .addAction(new Notification.Action.Builder(null,"Stop HUD",stopPi).build())
                .build();
    }

    @Override public void onDestroy(){
        running=false;
        handler.removeCallbacks(updateLoop);
        if(overlay!=null&&wm!=null){
            try{wm.removeView(overlay);}catch(Exception ignored){}
            overlay=null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}

    private static final class MiniMetric{
        final LinearLayout root;
        final TextView value;
        final ProgressBar bar;
        MiniMetric(LinearLayout root,TextView value,ProgressBar bar){
            this.root=root;this.value=value;this.bar=bar;
        }
        void set(String s,int percent){
            value.setText(s);
            bar.setProgress(Math.max(0,Math.min(100,percent)));
        }
    }

    private static final class CpuClock{
        final int current,percent;
        CpuClock(int current,int percent){this.current=current;this.percent=percent;}
    }
}
