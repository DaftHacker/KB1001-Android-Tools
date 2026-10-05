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
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class FpsOverlayService extends Service {
    private static final String CHANNEL="kb1001_fps_hud";
    private static final String FPS_SAMPLER=BackendManager.FPS_SAMPLER;
    private static volatile boolean running;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService reader=Executors.newSingleThreadExecutor();

    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private TextView fpsText;
    private java.lang.Process sampler;

    private float downX,downY;
    private int startX,startY;
    private boolean moved;
    private long lastTapUp;

    public static boolean isRunning(){return running;}

    @Override public void onCreate(){
        super.onCreate();
        running=true;
        getSharedPreferences("fps_hud",MODE_PRIVATE).edit().putBoolean("runtime_running",true).apply();
        createChannel();
        startForeground(1002,notification());

        if(!Settings.canDrawOverlays(this)){
            stopSelf();
            return;
        }

        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        createOverlay();
        startSampler();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        running=true;
        getSharedPreferences("fps_hud",MODE_PRIVATE).edit().putBoolean("runtime_running",true).apply();
        if(intent!=null && "kb1001.refresh_fps_appearance".equals(intent.getAction())){
            applyAppearance();
        }
        return START_STICKY;
    }

    private void createOverlay(){
        android.content.SharedPreferences prefs=getSharedPreferences("fps_hud",MODE_PRIVATE);
        float scale=prefs.getFloat("scale",1f);
        int textColor=prefs.getInt("color",Color.WHITE);

        fpsText=new TextView(this);
        fpsText.setText("— FPS");
        fpsText.setTextColor(textColor);
        fpsText.setTextSize(18f*scale);
        fpsText.setShadowLayer(3f,0f,0f,Color.BLACK);
        fpsText.setPadding(dp(7),dp(3),dp(7),dp(3));
        fpsText.setIncludeFontPadding(false);
        fpsText.setSingleLine(true);
        fpsText.setContentDescription("FPS counter. Double tap to close.");

        GradientDrawable backing=new GradientDrawable();
        backing.setColor(Color.argb(112,0,0,0));
        backing.setCornerRadius(dp(6));
        backing.setStroke(dp(1),Color.argb(150,0,0,0));
        fpsText.setBackground(backing);

        params=new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;

        applySavedPosition();

        fpsText.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=e.getRawX();
                    downY=e.getRawY();
                    startX=params.x;
                    startY=params.y;
                    moved=false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx=e.getRawX()-downX;
                    float dy=e.getRawY()-downY;
                    if(Math.abs(dx)>dp(6)||Math.abs(dy)>dp(6))moved=true;

                    int desiredX=startX+Math.round(dx);
                    int desiredY=startY+Math.round(dy);
                    int maxX=Math.max(0,getResources().getDisplayMetrics().widthPixels-Math.max(1,fpsText.getWidth()));
                    int maxY=Math.max(0,getResources().getDisplayMetrics().heightPixels-Math.max(1,fpsText.getHeight()));
                    params.x=Math.max(0,Math.min(maxX,desiredX));
                    params.y=Math.max(0,Math.min(maxY,desiredY));
                    try{wm.updateViewLayout(fpsText,params);}catch(Exception ignored){}
                    return true;

                case MotionEvent.ACTION_UP:
                    if(moved){
                        getSharedPreferences("fps_hud",MODE_PRIVATE).edit()
                                .putString("position","custom")
                                .putInt("x",params.x)
                                .putInt("y",params.y)
                                .apply();
                        lastTapUp=0;
                    }else{
                        long now=SystemClock.uptimeMillis();
                        if(lastTapUp>0 && now-lastTapUp<=350){
                            lastTapUp=0;
                            closeManualOverlay();
                        }else{
                            lastTapUp=now;
                        }
                    }
                    return true;
            }
            return false;
        });

        wm.addView(fpsText,params);
    }

    private void closeManualOverlay(){
        AppStateCache.setManualFps(this,false);
        AppStateCache.notifyManualOverlayState(this,"fps",false);
        Thread t=new Thread(
                ()->RootBridge.get().ctl("overlay fps-manual-off"),
                "KB1001-fps-close");
        t.setDaemon(true);
        t.start();
        stopSelf();
    }

    private void applyAppearance(){
        if(fpsText==null)return;
        android.content.SharedPreferences prefs=getSharedPreferences("fps_hud",MODE_PRIVATE);
        float scale=prefs.getFloat("scale",1f);
        int textColor=prefs.getInt("color",Color.WHITE);
        fpsText.setTextColor(textColor);
        fpsText.setTextSize(18f*scale);
        fpsText.setShadowLayer(3f,0f,0f,Color.BLACK);
        fpsText.setPadding(dp(7),dp(3),dp(7),dp(3));
        if(wm!=null&&params!=null){
            try{wm.updateViewLayout(fpsText,params);}catch(Exception ignored){}
        }
    }

    private void applySavedPosition(){
        android.content.SharedPreferences p=getSharedPreferences("fps_hud",MODE_PRIVATE);
        String position=p.getString("position","top_right");

        int width=getResources().getDisplayMetrics().widthPixels;
        int height=getResources().getDisplayMetrics().heightPixels;

        float scale=p.getFloat("scale",1f);
        int margin=dp(14);
        int estimatedWidth=Math.round(dp(82)*scale);
        int estimatedHeight=Math.round(dp(34)*scale);

        if("custom".equals(position)){
            params.x=Math.max(0,p.getInt("x",margin));
            params.y=Math.max(0,p.getInt("y",dp(48)));
            return;
        }

        int x;
        if(position.endsWith("_left"))x=margin;
        else if(position.endsWith("_center"))x=Math.max(margin,(width-estimatedWidth)/2);
        else x=Math.max(margin,width-estimatedWidth-margin);

        int y=position.startsWith("bottom_")
                ? Math.max(margin,height-estimatedHeight-dp(76))
                : dp(48);

        params.x=x;
        params.y=y;
    }

    private void startSampler(){
        reader.execute(()->{
            RootBridge.Result ready=BackendManager.ensureInstalled(this);
            if(!ready.ok()){
                handler.post(()->{
                    if(fpsText!=null)fpsText.setText("— FPS");
                });
                return;
            }

            int lastGoodFps=-1;
            while(running && !Thread.currentThread().isInterrupted()){
                try{
                    sampler=new ProcessBuilder(
                            "su","-c",
                            FPS_SAMPLER+" stream")
                            .redirectErrorStream(true)
                            .start();

                    BufferedReader in=new BufferedReader(
                            new InputStreamReader(sampler.getInputStream(), StandardCharsets.UTF_8));

                    String line;
                    while((line=in.readLine())!=null && running){
                        int parsed=parseFps(line);
                        if(parsed<0){
                            // A transient sampler miss is not a new FPS value. Keep the most
                            // recent valid reading instead of flashing the placeholder.
                            continue;
                        }
                        lastGoodFps=parsed;
                        final int fps=lastGoodFps;
                        handler.post(()->{
                            if(fpsText!=null)fpsText.setText(fps+" FPS");
                        });
                    }
                }catch(Exception ignored){
                    // Preserve the last valid reading while the sampler is restarted.
                }finally{
                    try{if(sampler!=null)sampler.destroy();}catch(Exception ignored){}
                    sampler=null;
                }

                if(!running)break;
                try{Thread.sleep(1000);}catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private int parseFps(String line){
        try{
            int v=Integer.parseInt(line.trim());
            return Math.max(-1,Math.min(240,v));
        }catch(Exception e){
            return -1;
        }
    }

    private int dp(int value){
        return Math.round(value*getResources().getDisplayMetrics().density);
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(
                    CHANNEL,
                    "FPS Counter",
                    NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Minimal in-game FPS counter.");
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent openPi=PendingIntent.getActivity(
                this,11,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_speed)
                .setContentTitle("FPS counter")
                .setContentText("Minimal FPS overlay is running")
                .setOngoing(true)
                .setContentIntent(openPi)
                .build();
    }

    @Override public void onDestroy(){
        running=false;
        getSharedPreferences("fps_hud",MODE_PRIVATE).edit().putBoolean("runtime_running",false).apply();

        try{
            if(sampler!=null){
                sampler.destroy();
                if(Build.VERSION.SDK_INT>=26) sampler.destroyForcibly();
            }
        }catch(Exception ignored){}

        if(fpsText!=null&&wm!=null){
            try{wm.removeView(fpsText);}catch(Exception ignored){}
            fpsText=null;
        }

        reader.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
