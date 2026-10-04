package com.dafthacker.kb1001perf;

import android.app.Application;

public final class KB1001App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        BackendManager.initialize(this);

        Thread t=new Thread(
                ()->BackendManager.ensureInstalled(this),
                "KB1001-backend-init");
        t.setDaemon(true);
        t.start();
    }
}
