package com.dafthacker.kb1001perf;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

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

    public static final String VERSION_FILE = ROOT + "/backend.version";
    private static final String MIGRATION_MARKER = ROOT + "/.legacy_state_migrated";
    private static final String ROOT_BOOT_HOOK = "/data/adb/service.d/kb1001perf.sh";

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
    private static String processReadyStamp;

    private BackendManager() {}

    public static synchronized void initialize(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static Context context() {
        return appContext;
    }

    public static synchronized RootBridge.Result ensureInstalled(Context context) {
        initialize(context);

        String backendDigest;
        try {
            backendDigest = bundledBackendDigest(appContext);
        } catch (Exception e) {
            return new RootBridge.Result(
                    -1,
                    "Could not fingerprint bundled backend: " +
                            e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        String expectedVersion =
                BuildConfig.VERSION_CODE + "|" + BuildConfig.VERSION_NAME + "|" + backendDigest;

        if (processReady && expectedVersion.equals(processReadyStamp)) {
            return new RootBridge.Result(0, "backend=ready\nversion=" + expectedVersion);
        }

        RootBridge bridge = RootBridge.get();
        RootBridge.Result root = bridge.exec("id -u");
        if (!root.ok() || !"0".equals(root.output.trim())) {
            return new RootBridge.Result(
                    root.exitCode,
                    root.output.isEmpty() ? "Root access is required for the privileged backend." : root.output);
        }

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

        boolean backendChanged = !current.ok();

        if (backendChanged) {
            // APK replacement does not guarantee that root-owned shell daemons die.
            // Stop every app-owned backend process before replacing scripts so no
            // old interpreter can continue executing a stale revision.
            stopAppOwnedBackendProcesses(bridge);

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

        RootBridge.Result hook = installRootBootHook(bridge);
        if (!hook.ok()) return hook;

        // The app-owned backend and service.d hook are now the runtime owner.
        // After state migration succeeds, retire any installed legacy module automatically.
        bridge.exec(
                "if [ -d " + RootBridge.shellQuote(LEGACY_MODULE) + " ]; then " +
                        "touch " + RootBridge.shellQuote(LEGACY_MODULE + "/disable") + " " +
                        RootBridge.shellQuote(LEGACY_MODULE + "/remove") + "; fi");

        // Clear runtime ownership markers whenever backend code changes.
        // This prevents a surviving/stale PID file from blocking a new daemon.
        if (backendChanged || "migrated".equals(migrated.output.trim())) {
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
        processReadyStamp = expectedVersion;
        return new RootBridge.Result(0,
                "backend=ready\nstate=" + migrated.output.trim() +
                        "\nversion=" + expectedVersion);
    }

    private static RootBridge.Result installRootBootHook(RootBridge bridge) {
        String hook =
                "#!/system/bin/sh\n" +
                "BACKEND=/data/local/kb1001perf/backend/service.sh\n" +
                "i=0\n" +
                "while [ $i -lt 120 ] && [ ! -r \"$BACKEND\" ]; do sleep 1; i=$((i+1)); done\n" +
                "[ -r \"$BACKEND\" ] || exit 0\n" +
                "nohup sh \"$BACKEND\" >/dev/null 2>&1 </dev/null &\n" +
                "exit 0\n";
        return bridge.writeRootFile(
                ROOT_BOOT_HOOK,
                hook.getBytes(StandardCharsets.UTF_8),
                "0700");
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

    public static RootBridge.Result backendHealth(Context context) {
        RootBridge.Result ready=ensureInstalled(context);
        if(!ready.ok())return ready;

        return RootBridge.get().exec(
                "if [ -r " + RootBridge.shellQuote(CONTROLLER) + " ] && " +
                        "[ -x " + RootBridge.shellQuote(ROOT_BOOT_HOOK) + " ]; then " +
                        "echo 'ready|boot-hook'; " +
                        "cat " + RootBridge.shellQuote(VERSION_FILE) + " 2>/dev/null; " +
                        "else echo 'missing'; exit 1; fi");
    }

    private static void stopAppOwnedBackendProcesses(RootBridge bridge) {
        bridge.exec(
                "for f in /data/local/tmp/kb1001_game_boost.pid " +
                        "/data/local/tmp/kb1001_perf_logger.pid; do " +
                        "p=$(cat \"$f\" 2>/dev/null); " +
                        "case \"$p\" in ''|*[!0-9]*) ;; *) kill \"$p\" 2>/dev/null || true ;; esac; " +
                        "done; " +
                        "ps -A -o PID,ARGS 2>/dev/null | " +
                        "grep " + RootBridge.shellQuote(BACKEND_DIR + "/") + " | " +
                        "grep -E 'game_boost[.]sh|perf_logger[.]sh|fps_sampler[.]sh' | " +
                        "grep -v grep | while read p rest; do " +
                        "case \"\$p\" in ''|*[!0-9]*) ;; *) kill \"\$p\" 2>/dev/null || true ;; esac; " +
                        "done");
    }

    private static String bundledBackendDigest(Context context) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        AssetManager assets = context.getAssets();
        for (String name : BACKEND_FILES) {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            byte[] data = readAll(assets.open("backend/" + name));
            digest.update(data);
            digest.update((byte)0);
        }

        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) {
            out.append(String.format(Locale.US, "%02x", b));
        }
        return out.toString();
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
