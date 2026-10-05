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
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
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
    private int displayedFps=Integer.MIN_VALUE;
    private int displayedAverageFps=Integer.MIN_VALUE;
    private final java.util.ArrayDeque<AveragePoint> averageSamples=new java.util.ArrayDeque<>();
    private long averageSum;
    private String averageSource="";
    private volatile boolean samplerRestartRequested;
    private BufferedWriter validationWriter;
    private File validationFile;
    private int validationRowsSinceFlush;
    private long validationLastFlushMs;
    private static final String VALIDATOR_PACKAGE="com.dafthacker.fpsvalidator";
    private static final int DEFAULT_POLL_MS=250;
    private static final int MIN_POLL_MS=100;
    private static final int MAX_POLL_MS=1000;
    private static final int DEFAULT_AVERAGE_WINDOW_MS=2000;
    private static final int MIN_AVERAGE_WINDOW_MS=500;
    private static final int MAX_AVERAGE_WINDOW_MS=5000;

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
        }else if(intent!=null && "kb1001.refresh_fps_sampling".equals(intent.getAction())){
            samplerRestartRequested=true;
            try{if(sampler!=null)sampler.destroy();}catch(Exception ignored){}
        }
        return START_STICKY;
    }

    private void createOverlay(){
        android.content.SharedPreferences prefs=getSharedPreferences("fps_hud",MODE_PRIVATE);
        float scale=prefs.getFloat("scale",1f);
        int textColor=prefs.getInt("color",Color.WHITE);

        fpsText=new TextView(this);
        fpsText.setText("Current FPS: —\nAverage FPS: —");
        fpsText.setTextColor(textColor);
        fpsText.setTextSize(18f*scale);
        fpsText.setShadowLayer(3f,0f,0f,Color.BLACK);
        fpsText.setPadding(dp(7),dp(3),dp(7),dp(3));
        fpsText.setIncludeFontPadding(false);
        fpsText.setSingleLine(false);
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
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
                ()->{
                    RootBridge.get().ctl("overlay fps-manual-off");
                    RootBridge.get().ctl("overlay fps-hide");
                },
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
                    if(fpsText!=null)fpsText.setText("Current FPS: —\nAverage FPS: —");
                });
                return;
            }

            while(running && !Thread.currentThread().isInterrupted()){
                try{
                    android.content.SharedPreferences prefs=getSharedPreferences("fps_hud",MODE_PRIVATE);
                    int pollMs=clamp(
                            prefs.getInt("poll_ms",DEFAULT_POLL_MS),
                            MIN_POLL_MS,
                            MAX_POLL_MS);

                    samplerRestartRequested=false;
                    sampler=new ProcessBuilder(
                            "su","-c",
                            FPS_SAMPLER+" stream "+pollMs)
                            .redirectErrorStream(true)
                            .start();

                    BufferedReader in=new BufferedReader(
                            new InputStreamReader(sampler.getInputStream(), StandardCharsets.UTF_8));

                    String line;
                    while((line=in.readLine())!=null && running){
                        FpsSample sample=parseFps(line);

                        boolean sourceChanged=!sample.source.isEmpty() &&
                                !sample.source.equals(averageSource);
                        if(sourceChanged){
                            resetAverage(sample.source);
                        }

                        if(sample.fps<0){
                            // Resolver misses are not frame-rate measurements.
                            continue;
                        }

                        final int fps=sample.fps;
                        final long now=SystemClock.elapsedRealtime();
                        final int windowMs=getAverageWindowMs();

                        // Only primary SurfaceFlinger presentation samples and
                        // confirmed sustained stalls advance Average FPS.
                        // A held display value is not a new measurement, and a
                        // fallback source must not silently mix with the primary.
                        final int avg;
                        if("live".equals(sample.kind) || "stall".equals(sample.kind)){
                            avg=addAverageSample(fps,now,windowMs);
                        }else{
                            avg=currentAverage(now,windowMs);
                        }

                        syncValidationLogger(sample.source);
                        logValidationSample(sample,avg,pollMs,windowMs);

                        if(fps==displayedFps && avg==displayedAverageFps)continue;
                        displayedFps=fps;
                        displayedAverageFps=avg;

                        handler.post(()->{
                            if(fpsText==null)return;
                            String averageText=avg>=0?Integer.toString(avg):"—";
                            fpsText.setText(
                                    "Current FPS: "+fps+
                                            "\nAverage FPS ("+formatWindow(windowMs)+"): "+averageText);
                        });
                    }
                }catch(Exception ignored){
                    // Preserve the last valid reading while the sampler is restarted.
                }finally{
                    try{if(sampler!=null)sampler.destroy();}catch(Exception ignored){}
                    sampler=null;
                }

                if(!running)break;
                long delay=samplerRestartRequested?75L:1000L;
                samplerRestartRequested=false;
                try{Thread.sleep(delay);}catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private FpsSample parseFps(String line){
        if(line==null)return new FpsSample(-1,"","","",0);

        String[] parts=line.trim().split("\\|",5);
        int fps=-1;
        try{
            fps=Math.max(-1,Math.min(240,Integer.parseInt(parts[0].trim())));
        }catch(Exception ignored){}

        String source=parts.length>=2?parts[1].trim():"";
        String kind=parts.length>=3?parts[2].trim():"";
        String layer=parts.length>=4?parts[3].trim():"";
        int newFrames=0;
        if(parts.length>=5){
            try{newFrames=Math.max(0,Integer.parseInt(parts[4].trim()));}
            catch(Exception ignored){}
        }
        if(kind.isEmpty() && fps>=0)kind="live";
        return new FpsSample(fps,source,kind,layer,newFrames);
    }

    private void resetAverage(String source){
        averageSamples.clear();
        averageSum=0;
        averageSource=source==null?"":source;
        displayedAverageFps=Integer.MIN_VALUE;
    }

    private int addAverageSample(int fps,long now,int windowMs){
        pruneAverage(now,windowMs);
        AveragePoint point=new AveragePoint(now,fps);
        averageSamples.addLast(point);
        averageSum+=fps;
        return averageSamples.isEmpty()
                ? fps
                : Math.round((float)averageSum/averageSamples.size());
    }

    private int currentAverage(long now,int windowMs){
        pruneAverage(now,windowMs);
        return averageSamples.isEmpty()
                ? -1
                : Math.round((float)averageSum/averageSamples.size());
    }

    private void pruneAverage(long now,int windowMs){
        long cutoff=now-windowMs;
        while(!averageSamples.isEmpty() && averageSamples.peekFirst().timeMs<cutoff){
            averageSum-=averageSamples.removeFirst().fps;
        }
    }

    private int getAverageWindowMs(){
        return clamp(
                getSharedPreferences("fps_hud",MODE_PRIVATE)
                        .getInt("average_window_ms",DEFAULT_AVERAGE_WINDOW_MS),
                MIN_AVERAGE_WINDOW_MS,
                MAX_AVERAGE_WINDOW_MS);
    }

    private static int clamp(int value,int min,int max){
        return Math.max(min,Math.min(max,value));
    }

    private static String formatWindow(int windowMs){
        if(windowMs%1000==0)return (windowMs/1000)+"s";
        return String.format(java.util.Locale.US,"%.1fs",windowMs/1000f);
    }

    private static final class AveragePoint{
        final long timeMs;
        final int fps;

        AveragePoint(long timeMs,int fps){
            this.timeMs=timeMs;
            this.fps=fps;
        }
    }

    private static final class FpsSample{
        final int fps;
        final String source;
        final String kind;
        final String layer;
        final int newFrames;

        FpsSample(int fps,String source,String kind,String layer,int newFrames){
            this.fps=fps;
            this.source=source==null?"":source;
            this.kind=kind==null?"":kind;
            this.layer=layer==null?"":layer;
            this.newFrames=newFrames;
        }
    }

    private void syncValidationLogger(String source){
        boolean enabled=VALIDATOR_PACKAGE.equals(source);

        if(!enabled){
            closeValidationLogger();
            return;
        }
        if(validationWriter!=null)return;

        try{
            File dir=new File(getExternalFilesDir(null),"fps-validation");
            if(!dir.exists()&&!dir.mkdirs())return;

            String stamp=new java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss",java.util.Locale.US)
                    .format(new java.util.Date());
            validationFile=new File(dir,"overlay-"+stamp+".csv");
            validationWriter=new BufferedWriter(new FileWriter(validationFile,false),65536);
            validationWriter.write(
                    "elapsed_realtime_ns,wall_time_ms,current_fps,average_fps,"+
                    "package,kind,poll_ms,average_window_ms,layer,new_frames\n");
            validationWriter.flush();
            validationRowsSinceFlush=0;
            validationLastFlushMs=SystemClock.elapsedRealtime();

            getSharedPreferences("fps_hud",MODE_PRIVATE).edit()
                    .putString("validation_log_path",validationFile.getAbsolutePath())
                    .apply();
        }catch(Exception ignored){
            closeValidationLogger();
        }
    }

    private void logValidationSample(FpsSample sample,int avg,int pollMs,int windowMs){
        if(validationWriter==null)return;
        try{
            validationWriter.write(Long.toString(SystemClock.elapsedRealtimeNanos()));
            validationWriter.write(',');
            validationWriter.write(Long.toString(System.currentTimeMillis()));
            validationWriter.write(',');
            validationWriter.write(Integer.toString(sample.fps));
            validationWriter.write(',');
            validationWriter.write(Integer.toString(avg));
            validationWriter.write(',');
            validationWriter.write(csv(sample.source));
            validationWriter.write(',');
            validationWriter.write(csv(sample.kind));
            validationWriter.write(',');
            validationWriter.write(Integer.toString(pollMs));
            validationWriter.write(',');
            validationWriter.write(Integer.toString(windowMs));
            validationWriter.write(',');
            validationWriter.write(csv(sample.layer));
            validationWriter.write(',');
            validationWriter.write(Integer.toString(sample.newFrames));
            validationWriter.write('\n');

            validationRowsSinceFlush++;
            long now=SystemClock.elapsedRealtime();
            if(validationRowsSinceFlush>=32 || now-validationLastFlushMs>=1000){
                validationWriter.flush();
                validationRowsSinceFlush=0;
                validationLastFlushMs=now;
            }
        }catch(Exception ignored){
            closeValidationLogger();
        }
    }

    private static String csv(String value){
        if(value==null)return "";
        String s=value.replace("\"","\"\"");
        if(s.indexOf(',')>=0||s.indexOf('"')>=0||s.indexOf('\n')>=0){
            return "\""+s+"\"";
        }
        return s;
    }

    private void closeValidationLogger(){
        if(validationWriter!=null){
            try{validationWriter.flush();}catch(Exception ignored){}
            try{validationWriter.close();}catch(Exception ignored){}
        }
        validationWriter=null;
        validationFile=null;
        validationRowsSinceFlush=0;
        validationLastFlushMs=0;
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
            c.setSound(null,null);
            c.enableVibration(false);
            c.enableLights(false);
            c.setShowBadge(false);
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

        closeValidationLogger();
        reader.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
