package com.dafthacker.kb1001perf;

import android.app.Activity;
import android.content.Context;
import android.view.WindowManager;

public final class DisplaySleepPolicy {
    public static final String PREFS="display_settings";
    public static final String KEY_KEEP_AWAKE_IN_APP="keep_awake_in_app";

    private DisplaySleepPolicy(){}

    public static boolean keepAwakeInApp(Context context){
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getBoolean(KEY_KEEP_AWAKE_IN_APP,false);
    }

    public static void setKeepAwakeInApp(Context context,boolean enabled){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_KEEP_AWAKE_IN_APP,enabled)
                .apply();
    }

    public static void apply(Activity activity,boolean forceAwake){
        boolean keep=forceAwake||keepAwakeInApp(activity);
        if(keep){
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }else{
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
