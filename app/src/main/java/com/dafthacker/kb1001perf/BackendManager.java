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

    private static final String DIAG_PREFS = "backend_diag";
    private static final String DIAG_LAST_ERROR = "last_install_error";

    private static final String[] BACKEND_FILES = {
            "common.sh",
            "service.sh",
            "game_boost.sh",
            "perf_logger.sh",
            "fps_sampler.sh",
            "renderer_recon.sh",
            "cpu_control.sh",
            "thermal_control.sh",
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


    private static RootBridge.Result stageFailure(String stage, RootBridge.Result cause) {
        int code = cause == null ? -1 : cause.exitCode;
        String detail = cause == null || cause.output == null ? "" : cause.output.trim();
        if (detail.isEmpty()) detail = "no diagnostic output";
        return new RootBridge.Result(code, "stage=" + stage + "\n" + detail);
    }

    private static RootBridge.Result installResult(Context context, RootBridge.Result result) {
        if (context != null) {
            try {
                if (result != null && result.ok()) {
                    context.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .remove(DIAG_LAST_ERROR)
                            .apply();
                } else {
                    String detail = result == null
                            ? "Backend installation returned no result."
                            : "exit=" + result.exitCode + "\n" +
                              (result.output == null ? "" : result.output.trim());
                    context.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString(DIAG_LAST_ERROR, detail.trim())
                            .apply();
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public static String lastInstallError(Context context) {
        if (context == null) return "";
        try {
            return context.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
                    .getString(DIAG_LAST_ERROR, "");
        } catch (Exception ignored) {
            return "";
        }
    }


    private static String requiredBackendFilesTest() {
        StringBuilder command=new StringBuilder();
        for(String name:BACKEND_FILES){
            if(command.length()>0)command.append(" && ");
            command.append("test -s ")
                    .append(RootBridge.shellQuote(BACKEND_DIR+"/"+name));
        }
        return command.toString();
    }

    private static RootBridge.Result verifyBackendFiles(RootBridge bridge) {
        StringBuilder command=new StringBuilder(requiredBackendFilesTest());

        // Validate shell syntax before any bootstrap code can execute. This is
        // intentionally read-only and catches truncated/corrupt deployments.
        for(String name:BACKEND_FILES){
            if(name.endsWith(".sh") || "kb1001ctl".equals(name)){
                command.append(" && sh -n ")
                        .append(RootBridge.shellQuote(BACKEND_DIR+"/"+name));
            }
        }

        return bridge.exec(command.toString());
    }

    public static synchronized RootBridge.Result ensureInstalled(Context context) {
        initialize(context);
        Context ctx=appContext!=null?appContext:context;

        String backendDigest;
        try {
            backendDigest=bundledBackendDigest(ctx);
        } catch(Exception e) {
            return installResult(ctx,new RootBridge.Result(
                    -1,
                    "stage=bundle_fingerprint\nCould not fingerprint bundled backend: "+
                            e.getClass().getSimpleName()+": "+e.getMessage()));
        }

        String expectedVersion=
                BuildConfig.VERSION_CODE+"|"+BuildConfig.VERSION_NAME+"|"+backendDigest;

        if(processReady && expectedVersion.equals(processReadyStamp)){
            return installResult(ctx,
                    new RootBridge.Result(0,"backend=ready\nversion="+expectedVersion));
        }

        RootBridge bridge=RootBridge.get();

        RootBridge.Result root=bridge.exec("id -u");
        if(!root.ok() || !"0".equals(root.output.trim())){
            RootBridge.Result reason=root.ok()
                    ? new RootBridge.Result(1,"Root shell returned uid="+root.output.trim())
                    : root;
            return installResult(ctx,stageFailure("root_check",reason));
        }

        RootBridge.Result prepare=bridge.exec(
                "mkdir -p "+RootBridge.shellQuote(BACKEND_DIR)+" "+
                        RootBridge.shellQuote(STATE_DIR)+
                        " && chmod 0700 "+RootBridge.shellQuote(ROOT)+" "+
                        RootBridge.shellQuote(BACKEND_DIR)+" "+
                        RootBridge.shellQuote(STATE_DIR));
        if(!prepare.ok()){
            return installResult(ctx,stageFailure("prepare_directories",prepare));
        }

        bridge.exec(
                "ps -A -o PID,ARGS 2>/dev/null | "+
                        "grep "+RootBridge.shellQuote(LEGACY_MODULE+"/")+" | "+
                        "grep -v grep | while read p rest; do "+
                        "case \"$p\" in ''|*[!0-9]*) ;; *) kill \"$p\" 2>/dev/null || true ;; esac; "+
                        "done");

        RootBridge.Result migrated=bridge.exec(
                "if [ -d "+RootBridge.shellQuote(LEGACY_STATE)+" ] && "+
                        "[ ! -e "+RootBridge.shellQuote(MIGRATION_MARKER)+" ]; then "+
                        "cp -a "+RootBridge.shellQuote(LEGACY_STATE+"/.")+" "+
                        RootBridge.shellQuote(STATE_DIR+"/")+" 2>/dev/null || "+
                        "cp -R "+RootBridge.shellQuote(LEGACY_STATE+"/.")+" "+
                        RootBridge.shellQuote(STATE_DIR+"/")+"; "+
                        "touch "+RootBridge.shellQuote(MIGRATION_MARKER)+"; "+
                        "echo migrated; "+
                        "else echo unchanged; fi");
        if(!migrated.ok()){
            return installResult(ctx,stageFailure("migrate_legacy_state",migrated));
        }

        // A matching version stamp is not enough. Every required backend file
        // must still exist and be non-empty before a deployment can be skipped.
        RootBridge.Result current=bridge.exec(
                requiredBackendFilesTest()+
                        " && test \"$(cat "+RootBridge.shellQuote(VERSION_FILE)+
                        " 2>/dev/null)\" = "+RootBridge.shellQuote(expectedVersion));

        boolean backendChanged=!current.ok();

        if(backendChanged){
            processReady=false;
            processReadyStamp=null;

            stopAppOwnedBackendProcesses(bridge);

            try {
                AssetManager assets=ctx.getAssets();
                for(String name:BACKEND_FILES){
                    byte[] data=readAll(assets.open("backend/"+name));
                    RootBridge.Result written=bridge.writeRootFile(
                            BACKEND_DIR+"/"+name,
                            data,
                            "0700");
                    if(!written.ok()){
                        return installResult(
                                ctx,
                                stageFailure("deploy_backend/"+name,written));
                    }
                }
            } catch(Exception e) {
                return installResult(ctx,new RootBridge.Result(
                        -1,
                        "stage=deploy_backend\nBackend deployment failed: "+
                                e.getClass().getSimpleName()+": "+e.getMessage()));
            }
        }

        // Verify the complete file set even when no deployment was needed.
        RootBridge.Result verified=verifyBackendFiles(bridge);
        if(!verified.ok()){
            // Never preserve a success stamp for an incomplete/corrupt backend.
            bridge.exec("rm -f "+RootBridge.shellQuote(VERSION_FILE));
            return installResult(ctx,stageFailure("verify_backend_files",verified));
        }

        RootBridge.Result hook=installRootBootHook(bridge);
        if(!hook.ok()){
            bridge.exec("rm -f "+RootBridge.shellQuote(VERSION_FILE));
            return installResult(ctx,stageFailure("install_boot_hook",hook));
        }

        bridge.exec(
                "if [ -d "+RootBridge.shellQuote(LEGACY_MODULE)+" ]; then "+
                        "touch "+RootBridge.shellQuote(LEGACY_MODULE+"/disable")+" "+
                        RootBridge.shellQuote(LEGACY_MODULE+"/remove")+"; fi");

        if(backendChanged || "migrated".equals(migrated.output.trim())){
            bridge.exec(
                    "rm -f /data/local/tmp/kb1001_game_boost.pid "+
                            "/data/local/tmp/kb1001_perf_logger.pid "+
                            "/data/local/tmp/kb1001_manual_profile.pid "+
                            "/data/local/tmp/kb1001_cpu_stat.* 2>/dev/null || true");
        }

        RootBridge.Result boot=bridge.exec(
                "sh "+RootBridge.shellQuote(CONTROLLER)+" bootstrap");
        if(!boot.ok()){
            // A backend that cannot bootstrap must never be stamped current.
            bridge.exec("rm -f "+RootBridge.shellQuote(VERSION_FILE));
            return installResult(ctx,stageFailure("bootstrap_backend",boot));
        }

        // Commit the version stamp only after the complete backend has passed
        // file verification, boot-hook installation, and bootstrap.
        RootBridge.Result version=bridge.writeRootFile(
                VERSION_FILE,
                (expectedVersion+"\n").getBytes(StandardCharsets.UTF_8),
                "0600");
        if(!version.ok()){
            return installResult(ctx,stageFailure("commit_backend_version",version));
        }

        processReady=true;
        processReadyStamp=expectedVersion;

        return installResult(ctx,new RootBridge.Result(
                0,
                "backend=ready\nstate="+migrated.output.trim()+
                        "\nversion="+expectedVersion));
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
                        "case \"$p\" in ''|*[!0-9]*) ;; *) kill \"$p\" 2>/dev/null || true ;; esac; " +
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
