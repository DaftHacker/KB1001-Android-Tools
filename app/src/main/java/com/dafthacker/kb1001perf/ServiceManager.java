package com.dafthacker.kb1001perf;

import android.content.Context;

public final class ServiceManager {
    private final Context context;

    ServiceManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public BackendResult execute(String args) {
        return new BackendResult(125, "App service backend is not available yet.", "service");
    }

    public boolean available() {
        return false;
    }
}
