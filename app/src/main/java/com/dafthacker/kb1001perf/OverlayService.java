package com.dafthacker.kb1001perf;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;

public class OverlayService extends Service {
    private static final String CHANNEL="kb1001_hud";

    private static final int CPU_COLOR=Color.rgb(77,210,126);
    private static final int GPU_COLOR=Color.rgb(255,151,61);
    private static final int RAM_COLOR=Color.rgb(255,211,64);
    private static final int THERMAL_COOL=Color.rgb(91,205,223);
    private static final int THERMAL_WARM=Color.rgb(255,175,59);
    private static final int THERMAL_HOT=Color.rgb(255,83,79);
    private static final int BATTERY_GOOD=Color.rgb(83,205,109);
    private static final int BATTERY_WARN=Color.rgb(255,207,69);
    private static final int BATTERY_LOW=Color.rgb(255,92,82);

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

    private OverlayMetric cpu;
    private OverlayMetric gpu;
    private OverlayMetric ram;
    private OverlayMetric thermal;
    private OverlayMetric battery;

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
        if(intent!=null&&intent.getAction()!=null){
            String action=intent.getAction();
            if("kb1001.stop_hud".equals(action)){
                stopSelf();
                return START_NOT_STICKY;
            }
            if("kb1001.dynamic744".equals(action)){
                ctl("persist dynamic744");
            }else if("kb1001.performance744".equals(action)){
                ctl("persist performance744");
            }
        }
        return START_STICKY;
    }

    private void createOverlay(){
        overlay=new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(12),dp(10),dp(12),dp(10));
        overlay.setBackground(overlayBackground(Color.rgb(58,105,107)));
        overlay.setElevation(dp(18));

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout names=new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        title=txt("Performance",13,Color.rgb(239,246,244),true);
        subtitle=txt("Waiting for game state",10,Color.rgb(162,184,181),false);
        names.addView(title);
        names.addView(subtitle);
        head.addView(names,new LinearLayout.LayoutParams(0,-2,1));

        Button fold=mini("—");
        fold.setOnClickListener(v->{
            boolean hide=details.getVisibility()==View.VISIBLE;
            details.setVisibility(hide?View.GONE:View.VISIBLE);
            fold.setText(hide?"+":"—");
        });
        head.addView(fold);

        Button close=mini("×");
        close.setOnClickListener(v->stopSelf());
        head.addView(close);

        overlay.addView(head);

        details=new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(0,dp(8),0,0);

        cpu=metric("CPU",CPU_COLOR);
        gpu=metric("GPU",GPU_COLOR);
        ram=metric("RAM",RAM_COLOR);
        thermal=metric("THERMAL",THERMAL_COOL);
        battery=metric("BATTERY",BATTERY_GOOD);

        details.addView(cpu.root);
        details.addView(gpu.root);
        details.addView(ram.root);
        details.addView(thermal.root);
        details.addView(battery.root);

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0,dp(6),0,0);

        Button profile=mini("GPU Profile ▾");
        profile.setOnClickListener(v->showProfileMenu(profile));

        Button rec=mini("Record");
        rec.setOnClickListener(v->ctl("logger file toggle"));

        actions.addView(profile,new LinearLayout.LayoutParams(0,-2,2));
        actions.addView(rec,new LinearLayout.LayoutParams(0,-2,1));
        details.addView(actions);

        footer=txt("",9,Color.rgb(144,164,162),false);
        footer.setPadding(0,dp(5),0,0);
        footer.setSingleLine(true);
        footer.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(footer);

        overlay.addView(details);

        params=new WindowManager.LayoutParams(
                dp(316),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;
        params.x=dp(14);
        params.y=dp(86);

        head.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=e.getRawX();
                    downY=e.getRawY();
                    startX=params.x;
                    startY=params.y;
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

            String mode=TelemetryStore.get(m,"mode","idle");
            String profile=TelemetryStore.get(m,"profile","—");
            String pkg=TelemetryStore.get(m,"package","");
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

            BatteryManager bm=(BatteryManager)getSystemService(BATTERY_SERVICE);
            int batt=bm==null?-1:bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

            int thermalColor=temp>=70f?THERMAL_HOT:(temp>=55f?THERMAL_WARM:THERMAL_COOL);
            int batteryColor=batt>=50?BATTERY_GOOD:(batt>=20?BATTERY_WARN:BATTERY_LOW);

            thermal.setAccent(thermalColor);
            battery.setAccent(batteryColor);

            title.setText("Performance • "+displayProfile(profile));
            subtitle.setText("game".equalsIgnoreCase(mode)&&!pkg.isEmpty()?pkg:"Live system monitor");

            cpu.set(clocks.current+" MHz",clocks.percent);
            gpu.set(gpuMhz>0?gpuMhz+" MHz":"—",gpuPct);
            ram.set(ramPct+"%",ramPct);
            thermal.set(String.format(Locale.US,"%.1f°C",temp),tempPct);
            battery.set(batt<0?"—":batt+"%",Math.max(0,batt));

            boolean recording="1".equals(TelemetryStore.get(m,"file_logging","0"));
            footer.setText((recording?"● RECORDING  •  ":"")+"drag header • collapse with —");

            handler.postDelayed(this,1000);
        }
    };

    private OverlayMetric metric(String name,int accent){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(7),dp(4),dp(7),dp(5));
        root.setBackground(metricBackground(accent));

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView label=txt(name,9,accent,true);
        TextView value=txt("—",11,Color.rgb(236,244,242),true);
        value.setGravity(Gravity.END);

        header.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(value,new LinearLayout.LayoutParams(dp(80),-2));
        root.addView(header);

        SparklineView graph=new SparklineView(this);
        graph.setAccentColor(accent);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(34));
        gp.setMargins(0,dp(1),0,0);
        root.addView(graph,gp);

        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);
        rp.setMargins(0,dp(2),0,dp(2));
        root.setLayoutParams(rp);

        return new OverlayMetric(root,label,value,graph);
    }

    private GradientDrawable metricBackground(int accent){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.rgb(13,22,22));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1),Color.argb(62,Color.red(accent),Color.green(accent),Color.blue(accent)));
        return bg;
    }

    private GradientDrawable overlayBackground(int accent){
        GradientDrawable bg=new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(9,16,18),Color.rgb(12,25,25),Color.rgb(7,14,16)});
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1),Color.argb(125,Color.red(accent),Color.green(accent),Color.blue(accent)));
        return bg;
    }

    private void ctl(String command){
        io.execute(()->RootBridge.get().ctl(command));
    }

    private CpuClock parseCpu(String policies){
        int peakCurrent=0;
        int peakMax=0;

        for(String item:policies.split(";")){
            int eq=item.indexOf('=');
            int slash=item.indexOf('/',eq+1);
            int dash=item.indexOf('-',slash+1);
            int at=item.indexOf('@',dash+1);
            if(eq<0||slash<0||dash<0)continue;

            try{
                int cur=Integer.parseInt(item.substring(eq+1,slash).replaceAll("[^0-9]",""));
                int max=Integer.parseInt(item.substring(dash+1,at>dash?at:item.length()).replaceAll("[^0-9]",""));
                peakCurrent=Math.max(peakCurrent,cur);
                peakMax=Math.max(peakMax,max);
            }catch(Exception ignored){}
        }

        int pct=peakMax>0?Math.max(0,Math.min(100,peakCurrent*100/peakMax)):0;
        return new CpuClock(peakCurrent,pct);
    }

    private String displayProfile(String p){
        if("stock".equals(p))return "Stock 696";
        if("dynamic744".equals(p))return "Dynamic 744";
        if("performance744".equals(p))return "Performance 744";
        if("experimental792".equals(p)||"extreme792".equals(p)||"extreme792_dynamic".equals(p))return "Extreme 792 Dynamic";
        if("extreme792_full".equals(p)||"performance792".equals(p))return "Extreme 792 Full";
        return p;
    }

    private void showProfileMenu(View anchor){
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add("Stock 696");
        menu.getMenu().add("Dynamic 744");
        menu.getMenu().add("Performance 744");
        menu.getMenu().add("Extreme 792 Dynamic");
        menu.getMenu().add("Extreme 792 Full Throttle");

        menu.setOnMenuItemClickListener(item->{
            String title=item.getTitle().toString();
            if(title.startsWith("Stock")) ctl("persist stock");
            else if(title.startsWith("Dynamic")) ctl("persist dynamic744");
            else if(title.startsWith("Performance")) ctl("persist performance744");
            else if(title.contains("Dynamic")) ctl("apply extreme792_dynamic");
            else if(title.contains("Full")) ctl("apply extreme792_full");
            return true;
        });
        menu.show();
    }

    private Button mini(String label){
        Button b=new Button(this);
        b.setText(label);
        b.setTextSize(8);
        b.setTextColor(Color.rgb(232,241,239));
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(7),dp(2),dp(7),dp(2));
        b.setBackgroundResource(R.drawable.bg_button_secondary);
        return b;
    }

    private TextView txt(String s,int sp,int color,boolean bold){
        TextView v=new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        return v;
    }

    private int parseInt(String s){
        try{return Integer.parseInt(s);}catch(Exception e){return 0;}
    }

    private float parseFloat(String s){
        try{return Float.parseFloat(s);}catch(Exception e){return 0f;}
    }

    private int dp(int x){
        return Math.round(x*getResources().getDisplayMetrics().density);
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(
                    CHANNEL,
                    "Performance HUD",
                    NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Live game performance graphs and quick controls.");
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent openPi=PendingIntent.getActivity(
                this,1,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Intent dyn=new Intent(this,OverlayService.class).setAction("kb1001.dynamic744");
        PendingIntent dynPi=PendingIntent.getService(
                this,2,dyn,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Intent perf=new Intent(this,OverlayService.class).setAction("kb1001.performance744");
        PendingIntent perfPi=PendingIntent.getService(
                this,3,perf,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Intent stop=new Intent(this,OverlayService.class).setAction("kb1001.stop_hud");
        PendingIntent stopPi=PendingIntent.getService(
                this,4,stop,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_speed)
                .setContentTitle("Performance Manager")
                .setContentText("Performance HUD is running")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null,"Dynamic",dynPi).build())
                .addAction(new Notification.Action.Builder(null,"Performance",perfPi).build())
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

    private static final class OverlayMetric{
        final LinearLayout root;
        final TextView label;
        final TextView value;
        final SparklineView graph;

        OverlayMetric(LinearLayout root,TextView label,TextView value,SparklineView graph){
            this.root=root;
            this.label=label;
            this.value=value;
            this.graph=graph;
        }

        void set(String text,int percent){
            value.setText(text);
            graph.addValue(Math.max(0,Math.min(100,percent)));
        }

        void setAccent(int color){
            label.setTextColor(color);
            graph.setAccentColor(color);
            GradientDrawable bg=new GradientDrawable();
            bg.setColor(Color.rgb(13,22,22));
            bg.setCornerRadius(10f*root.getResources().getDisplayMetrics().density);
            bg.setStroke(
                    Math.max(1,(int)root.getResources().getDisplayMetrics().density),
                    Color.argb(62,Color.red(color),Color.green(color),Color.blue(color)));
            root.setBackground(bg);
        }
    }

    private static final class CpuClock{
        final int current,percent;
        CpuClock(int current,int percent){
            this.current=current;
            this.percent=percent;
        }
    }
}
