package com.dafthacker.kb1001perf;

import android.os.Handler;
import android.os.Looper;

public final class TelemetryDemand {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());

    private static int visibleActivities;
    private static boolean privilegedReady;
    private static boolean uiDesired;
    private static boolean hudDesired;

    private static final Runnable delayedOff=()->{
        synchronized(TelemetryDemand.class){
            uiDesired=false;
            sendIfReady("ui",false);
        }
    };

    private TelemetryDemand(){}

    public static synchronized void activityResumed(){
        MAIN.removeCallbacks(delayedOff);
        visibleActivities++;
        if(visibleActivities==1){
            uiDesired=true;
            sendIfReady("ui",true);
        }
    }

    public static synchronized void activityPaused(){
        if(visibleActivities>0)visibleActivities--;
        if(visibleActivities==0){
            // Avoid an off/on root round-trip when moving between app screens.
            MAIN.removeCallbacks(delayedOff);
            MAIN.postDelayed(delayedOff,700);
        }
    }

    public static synchronized void hud(boolean enabled){
        hudDesired=enabled;
        sendIfReady("hud",enabled);
    }

    /**
     * Root/backend commands are blocked until the foreground activity has
     * completed its one explicit Magisk initialization request.
     *
     * This prevents activity lifecycle callbacks and Android permission dialogs
     * from racing the Superuser prompt on a fresh install.
     */
    public static synchronized void setPrivilegedReady(boolean ready){
        boolean changed=privilegedReady!=ready;
        privilegedReady=ready;

        if(ready && changed){
            send("ui",uiDesired);
            send("hud",hudDesired);
        }
    }

    public static synchronized boolean isPrivilegedReady(){
        return privilegedReady;
    }

    private static void sendIfReady(String consumer,boolean enabled){
        if(!privilegedReady)return;
        send(consumer,enabled);
    }

    private static void send(String consumer,boolean enabled){
        Thread t=new Thread(()->RootBridge.get().ctl(
                "logger demand "+consumer+" "+(enabled?"on":"off")),
                "KB1001-telemetry-demand");
        t.setDaemon(true);
        t.start();
    }
}
