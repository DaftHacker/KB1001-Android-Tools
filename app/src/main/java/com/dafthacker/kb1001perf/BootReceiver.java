package com.dafthacker.kb1001perf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action=intent==null?null:intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        PendingResult pending=goAsync();
        Context app=context.getApplicationContext();

        Thread t=new Thread(()->{
            try{
                BackendManager.initialize(app);
                BackendManager.startBootBackend(app);
            }finally{
                pending.finish();
            }
        },"KB1001-boot-backend");
        t.start();
    }
}
