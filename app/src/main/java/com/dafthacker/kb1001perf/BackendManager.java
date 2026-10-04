package com.dafthacker.kb1001perf;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class BackendManager {
    public static final String ROOT = "/data/local/kb1001perf";
    public static final String BACKEND_DIR = ROOT + "/backend";
    public static final String STATE_DIR = ROOT + "/state";
    public static final String CONTROLLER = BACKEND_DIR + "/kb1001ctl";
    public static final String FPS_SAMPLER = BACKEND_DIR + "/fps_sampler.sh";

    public static final String LEGACY_MODULE =
            "/data/adb/modules/kb1001_gpu_profiles";
    public static final String LEGACY_STATE =
            "/data/adb/kb1001_gpu_profiles";

    private static final String VERSION_FILE = ROOT + "/backend.version";
    private static final String MIGRATION_MARKER = ROOT + "/.legacy_state_migrated";

    private static final String[] BACKEND_FILES = {
            "common.sh",
            "service.sh",
            "game_boost.sh",
            "perf_logger.sh",
            "fps_sampler.sh",
            "cpu_control.sh",
            "profile_switch.sh",
            "kb1001ctl"
    };

    private static Context appContext;
    private static boolean processReady;

    private BackendManager() {}

    public static synchronized void initialize(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static Context context() {
        return appContext;
    }

    public static synchronized RootBridge.Result ensureInstalled(Context context) {
        initialize(context);
        if (processReady) return new RootBridge.Result(0, "backend=ready");

        RootBridge bridge = RootBridge.get();
        RootBridge.Result root = bridge.exec("id -u");
        if (!root.ok() || !"0".equals(root.output.trim())) {
            return new RootBridge.Result(
                    root.exitCode,
                    root.output.isEmpty() ? "Root access is required for the privileged backend." : root.output);
        }

        String expectedVersion = BuildConfig.VERSION_CODE + "|" + BuildConfig.VERSION_NAME;

        RootBridge.Result prepare = bridge.exec(
                "mkdir -p " + RootBridge.shellQuote(BACKEND_DIR) + " " +
                        RootBridge.shellQuote(STATE_DIR) +
                        " && chmod 0700 " + RootBridge.shellQuote(ROOT) + " " +
                        RootBridge.shellQuote(BACKEND_DIR) + " " +
                        RootBridge.shellQuote(STATE_DIR));
        if (!prepare.ok()) return prepare;

        // Stop only processes that are still executing from the old Magisk module.
        // Do not kill already-migrated app-owned daemons.
        bridge.exec(
                "ps -A -o PID,ARGS 2>/dev/null | " +
                        "grep " + RootBridge.shellQuote(LEGACY_MODULE + "/") + " | " +
                        "grep -v grep | while read p rest; do " +
                        "case \"$p\" in ''|*[!0-9]*) ;; *) kill \"$p\" 2>/dev/null || true ;; esac; " +
                        "done");

        RootBridge.Result migrated = bridge.exec(
                "if [ -d " + RootBridge.shellQuote(LEGACY_STATE) + " ] && " +
                        "[ ! -e " + RootBridge.shellQuote(MIGRATION_MARKER) + " ]; then " +
                        "cp -a " + RootBridge.shellQuote(LEGACY_STATE + "/.") + " " +
                        RootBridge.shellQuote(STATE_DIR + "/") + " 2>/dev/null || " +
                        "cp -R " + RootBridge.shellQuote(LEGACY_STATE + "/.") + " " +
                        RootBridge.shellQuote(STATE_DIR + "/") + "; " +
                        "touch " + RootBridge.shellQuote(MIGRATION_MARKER) + "; " +
                        "echo migrated; " +
                        "else echo unchanged; fi");
        if (!migrated.ok()) return migrated;

        RootBridge.Result current = bridge.exec(
                "test -r " + RootBridge.shellQuote(CONTROLLER) +
                        " && test \"$(cat " + RootBridge.shellQuote(VERSION_FILE) +
                        " 2>/dev/null)\" = " + RootBridge.shellQuote(expectedVersion));

        if (!current.ok()) {
            try {
                AssetManager assets = appContext.getAssets();
                for (String name : BACKEND_FILES) {
                    byte[] data = readAll(assets.open("backend/" + name));
                    RootBridge.Result written = bridge.writeRootFile(
                            BACKEND_DIR + "/" + name,
                            data,
                            "0700");
                    if (!written.ok()) return written;
                }

                RootBridge.Result version = bridge.writeRootFile(
                        VERSION_FILE,
                        (expectedVersion + "\n").getBytes(StandardCharsets.UTF_8),
                        "0600");
                if (!version.ok()) return version;
            } catch (Exception e) {
                return new RootBridge.Result(
                        -1,
                        "Backend deployment failed: " + e.getClass().getSimpleName() +
                                ": " + e.getMessage());
            }
        }

        // The old module is no longer the runtime owner. Leave it installed but
        // disabled until the user explicitly removes it after validating migration.
        bridge.exec(
                "if [ -d " + RootBridge.shellQuote(LEGACY_MODULE) + " ]; then " +
                        "touch " + RootBridge.shellQuote(LEGACY_MODULE + "/disable") + "; fi");

        // Old daemons and the app-owned daemons share legacy runtime PID filenames.
        // If a migration happened this process, clear stale PID files before bootstrap.
        if ("migrated".equals(migrated.output.trim())) {
            bridge.exec(
                    "rm -f /data/local/tmp/kb1001_game_boost.pid " +
                            "/data/local/tmp/kb1001_perf_logger.pid " +
                            "/data/local/tmp/kb1001_manual_profile.pid " +
                            "/data/local/tmp/kb1001_cpu_stat.* 2>/dev/null || true");
        }

        RootBridge.Result boot = bridge.exec(
                "sh " + RootBridge.shellQuote(CONTROLLER) + " bootstrap");
        if (!boot.ok()) return boot;

        processReady = true;
        return new RootBridge.Result(0,
                "backend=ready\nstate=" + migrated.output.trim());
    }

    public static RootBridge.Result startBootBackend(Context context) {
        RootBridge.Result ready = ensureInstalled(context);
        if (!ready.ok()) return ready;

        return RootBridge.get().exec(
                "nohup sh " + RootBridge.shellQuote(BACKEND_DIR + "/service.sh") +
                        " >/dev/null 2>&1 </dev/null &");
    }

    public static boolean legacyModulePresent(Context context) {
        RootBridge.Result ready = ensureInstalled(context);
        if (!ready.ok()) return false;
        return RootBridge.get().exec(
                "test -d " + RootBridge.shellQuote(LEGACY_MODULE) +
                        " && test ! -e " + RootBridge.shellQuote(LEGACY_MODULE + "/remove")
        ).ok();
    }

    public static RootBridge.Result scheduleLegacyModuleRemoval(Context context) {
        RootBridge.Result ready = ensureInstalled(context);
        if (!ready.ok()) return ready;

        return RootBridge.get().exec(
                "if [ -d " + RootBridge.shellQuote(LEGACY_MODULE) + " ]; then " +
                        "touch " + RootBridge.shellQuote(LEGACY_MODULE + "/disable") + " " +
                        RootBridge.shellQuote(LEGACY_MODULE + "/remove") + "; " +
                        "echo scheduled; else echo absent; fi");
    }

    public static RootBridge.Result backendVersion(Context context) {
        RootBridge.Result ready = ensureInstalled(context);
        if (!ready.ok()) return ready;
        return RootBridge.get().exec(
                "cat " + RootBridge.shellQuote(VERSION_FILE) + " 2>/dev/null");
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
