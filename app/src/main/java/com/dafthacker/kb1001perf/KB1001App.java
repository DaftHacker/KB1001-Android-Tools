package com.dafthacker.kb1001perf;

import android.app.Application;

public final class KB1001App extends Application {
    @Override public void onCreate() {
        super.onCreate();

        // Registration only. Do not request root from Application.onCreate():
        // this also runs for receivers/services and can happen before the user
        // has an activity on screen to answer Magisk's Superuser prompt.
        BackendManager.initialize(this);
    }
}
