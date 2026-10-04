package com.dafthacker.kb1001perf;

public final class ModuleManager {
    static final String CONTROLLER = "/data/adb/modules/kb1001_gpu_profiles/kb1001ctl";
    private final RootBridge root;

    ModuleManager(RootBridge root) {
        this.root = root;
    }

    public BackendResult execute(String args) {
        RootBridge.Result r = root.exec("test -x " + CONTROLLER + " && " + CONTROLLER + " " + args);
        return new BackendResult("module", r.exitCode, r.output);
    }

    public boolean available() {
        RootBridge.Result r = root.exec("test -x " + CONTROLLER);
        return r.ok();
    }
}
