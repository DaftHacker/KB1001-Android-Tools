package com.dafthacker.kb1001perf;

import android.os.Handler;
import android.os.Looper;

public final class TelemetryDemand {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static int visibleActivities;

    private static final Runnable delayedOff=()->set("ui",false);

    private TelemetryDemand(){}

    public static synchronized void activityResumed(){
        MAIN.removeCallbacks(delayedOff);
        visibleActivities++;
        if(visibleActivities==1) set("ui",true);
    }

    public static synchronized void activityPaused(){
        if(visibleActivities>0) visibleActivities--;
        if(visibleActivities==0){
            // Avoid an off/on root round-trip when moving between app screens.
            MAIN.removeCallbacks(delayedOff);
            MAIN.postDelayed(delayedOff,700);
        }
    }

    public static void hud(boolean enabled){
        set("hud",enabled);
    }

    private static void set(String consumer,boolean enabled){
        Thread t=new Thread(()->RootBridge.get().ctl(
                "logger demand "+consumer+" "+(enabled?"on":"off")),
                "KB1001-telemetry-demand");
        t.setDaemon(true);
        t.start();
    }
}
