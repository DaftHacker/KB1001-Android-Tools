package com.dafthacker.fpsvalidator;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.net.Uri;

import java.io.FileInputStream;
import java.io.OutputStream;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
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

    private static final int MODE_AUTO = 5;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SurfaceView surfaceView;
    private Surface surface;
    private TextView status;
    private TextView progress;
    private TextView logPath;
    private boolean rendering;
    private boolean autoStarted;
    private File activeCsv;

    private native boolean nativeStart(Surface surface,String csvPath,int mode,float targetFps);
    private native void nativeStop();
    private native String nativeStatus();
    private native boolean nativeIsRunning();

    private final Runnable statusTicker=new Runnable(){
        @Override public void run(){
            if(rendering){
                String s=nativeStatus();
                if(s!=null&&!s.isEmpty()){
                    status.setText(s);
                    progress.setText(
                            "Automatic suite: warm-up → fixed 10/15/20/24/30/40/45/50/60 FPS → "+
                            "sweep → step transitions → jitter → deliberate stalls");
                }

                if(!nativeIsRunning()){
                    rendering=false;
                    publishToDownloads(activeCsv,"KB1001-FPS-Validator.csv","text/csv");
                    progress.setText("Validation complete. Latest truth log replaced in Downloads.");
                }
            }
            handler.postDelayed(this,250);
        }
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(10),dp(14),dp(10));
        root.setBackgroundColor(Color.rgb(8,13,18));

        root.addView(text("KB1001 GPU / FPS Validation Harness",20,Color.WHITE,true));

        TextView help=text(
                "One-launch validation. Performance Manager's normal foreground/game hook recognizes this "+
                        "validator automatically and starts the regular FPS overlay/logger. This app only "+
                        "renders the independent truth workload and never sends expected FPS values to the monitor.",
                11,Color.rgb(170,185,200),false);
        help.setPadding(0,dp(3),0,dp(8));
        root.addView(help);

        surfaceView=new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1f));

        status=text("Surface initializing…",15,Color.rgb(105,235,170),true);
        status.setPadding(0,dp(7),0,dp(3));
        root.addView(status);

        progress=text("The full validation suite will start automatically.",10,
                Color.rgb(175,190,205),false);
        root.addView(progress);

        Button restart=new Button(this);
        restart.setText("Restart Full Validation");
        restart.setAllCaps(false);
        restart.setOnClickListener(v->startAutomaticSuite());
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(48));
        rp.setMargins(0,dp(6),0,0);
        root.addView(restart,rp);

        logPath=text("No session log yet.",9,Color.rgb(150,165,180),false);
        logPath.setPadding(0,dp(4),0,0);
        logPath.setTextIsSelectable(true);
        root.addView(logPath);

        setContentView(root);
        handler.post(statusTicker);
    }

    private void startAutomaticSuite(){
        if(surface==null||!surface.isValid()){
            status.setText("Surface is not ready.");
            return;
        }

        stopSession();

        File dir=new File(getExternalFilesDir(null),"fps-validation");
        if(!dir.exists()&&!dir.mkdirs()){
            status.setText("Could not create validation log directory.");
            return;
        }

        File csv=new File(dir,"validator-latest-automatic.csv");
        activeCsv=csv;

        rendering=nativeStart(surface,csv.getAbsolutePath(),MODE_AUTO,0f);
        if(rendering){
            logPath.setText("Truth log: "+csv.getAbsolutePath());
            progress.setText("Automatic suite starting…");
        }else{
            status.setText("Native renderer failed to start.");
        }
    }


    private void publishToDownloads(File source,String name,String mime){
        if(source==null||!source.isFile())return;
        try{
            android.content.ContentResolver cr=getContentResolver();
            android.net.Uri collection=MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            cr.delete(collection,
                    MediaStore.MediaColumns.DISPLAY_NAME+"=?",
                    new String[]{name});

            ContentValues values=new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
            values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/");
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri uri=cr.insert(collection,values);
            if(uri==null)return;
            try(FileInputStream in=new FileInputStream(source);
                OutputStream out=cr.openOutputStream(uri,"w")){
                if(out==null)return;
                byte[] buf=new byte[65536];
                int n;
                while((n=in.read(buf))>0)out.write(buf,0,n);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING,0);
            cr.update(uri,values,null,null);
        }catch(Exception ignored){}
    }

    private void stopSession(){
        if(rendering)nativeStop();
        rendering=false;
    }

    @Override public void surfaceCreated(SurfaceHolder holder){
        surface=holder.getSurface();
        status.setText("Ready.");
        if(!autoStarted){
            autoStarted=true;
            // Give the window/compositor a short moment to settle before the truth run begins.
            handler.postDelayed(this::startAutomaticSuite,750);
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){
        surface=holder.getSurface();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder){
        stopSession();
        surface=null;
        status.setText("Surface destroyed.");
    }

    @Override protected void onDestroy(){
        stopSession();
        handler.removeCallbacks(statusTicker);
        super.onDestroy();
    }

    private TextView text(String value,int sp,int color,boolean bold){
        TextView v=new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        return v;
    }

    private int dp(int value){
        return Math.round(value*getResources().getDisplayMetrics().density);
    }
}
