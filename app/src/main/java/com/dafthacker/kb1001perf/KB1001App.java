package com.dafthacker.kb1001perf;

import android.app.Application;
import android.util.Log;

public final class KB1001App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        BackendManager.initialize(this);

        Thread t=new Thread(() -> {
            RootBridge.Result result=BackendManager.ensureInstalled(this);
            if(result.ok()){
                Log.i("KB1001Backend","Backend initialization complete.");
            }else{
                Log.e(
                        "KB1001Backend",
                        "Backend initialization failed: exit="+result.exitCode+
                                "\n"+(result.output==null?"":result.output));
            }
        },"KB1001-backend-init");
        t.setDaemon(true);
        t.start();
    }

}
