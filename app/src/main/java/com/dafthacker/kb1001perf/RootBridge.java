package com.dafthacker.kb1001perf;

import android.util.Base64;

import android.content.Context;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class RootBridge {
    private static final RootBridge INSTANCE = new RootBridge();

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private long sequence;

    private static final String[] SU_CANDIDATES = new String[]{
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/product/bin/su",
            "/vendor/bin/su"
    };

    private String activeSuPath;
    private String lastSuDiagnostic="";

    private RootBridge() {}

    public static RootBridge get() { return INSTANCE; }

    private Result probeSuCandidate(String candidate) {
        StringBuilder output=new StringBuilder();
        java.lang.Process probe=null;
        int rc=-1;

        try {
            if(candidate.startsWith("/") && !new File(candidate).canExecute()){
                return new Result(127,"not executable");
            }

            probe=new ProcessBuilder(candidate,"-c","id -u")
                    .redirectErrorStream(true)
                    .start();

            boolean finished=probe.waitFor(30,TimeUnit.SECONDS);
            if(!finished){
                try{probe.destroyForcibly();}catch(Exception ignored){}
                return new Result(124,"root probe timed out");
            }

            try(BufferedReader in=new BufferedReader(
                    new InputStreamReader(probe.getInputStream(),StandardCharsets.UTF_8))){
                String line;
                while((line=in.readLine())!=null){
                    if(output.length()>0)output.append('\n');
                    output.append(line);
                }
            }

            rc=probe.exitValue();
        } catch(Exception e) {
            output.append(e.getClass().getSimpleName())
                    .append(": ")
                    .append(e.getMessage());
            if(probe!=null){
                try{probe.destroyForcibly();}catch(Exception ignored){}
            }
        }

        String raw=output.toString().trim();
        if(rc==0){
            for(String line:raw.split("\\R")){
                if("0".equals(line.trim())){
                    return new Result(0,"uid=0");
                }
            }
            return new Result(1,raw.isEmpty()?"su returned no uid":raw);
        }

        return new Result(rc,raw.isEmpty()?"su probe failed":raw);
    }

    private void ensure() throws Exception {
        if(process!=null && process.isAlive())return;

        reset();

        StringBuilder failures=new StringBuilder();

        for(String candidate:SU_CANDIDATES){
            Result probe=probeSuCandidate(candidate);

            if(probe.ok()){
                try{
                    java.lang.Process candidateProcess=
                            new ProcessBuilder(candidate,"0")
                                    .redirectErrorStream(true)
                                    .start();

                    BufferedWriter candidateWriter=new BufferedWriter(
                            new OutputStreamWriter(
                                    candidateProcess.getOutputStream(),
                                    StandardCharsets.UTF_8));
                    BufferedReader candidateReader=new BufferedReader(
                            new InputStreamReader(
                                    candidateProcess.getInputStream(),
                                    StandardCharsets.UTF_8));

                    String marker="__KB1001_ROOT_"+System.nanoTime()+"__";
                    candidateWriter.write("id -u; echo "+marker+"$?\n");
                    candidateWriter.flush();

                    StringBuilder verify=new StringBuilder();
                    String line;
                    boolean sawRoot=false;
                    boolean sawMarker=false;

                    while((line=candidateReader.readLine())!=null){
                        if(line.startsWith(marker)){
                            sawMarker=true;
                            break;
                        }
                        if("0".equals(line.trim()))sawRoot=true;
                        if(verify.length()>0)verify.append('\n');
                        verify.append(line);
                    }

                    if(candidateProcess.isAlive() && sawMarker && sawRoot){
                        process=candidateProcess;
                        writer=candidateWriter;
                        reader=candidateReader;
                        activeSuPath=candidate;
                        lastSuDiagnostic="active_su="+candidate;
                        return;
                    }

                    try{candidateProcess.destroyForcibly();}catch(Exception ignored){}
                    if(failures.length()>0)failures.append(" | ");
                    failures.append(candidate)
                            .append(": interactive verification failed")
                            .append(verify.length()>0?" ("+verify+")":"");
                }catch(Exception e){
                    if(failures.length()>0)failures.append(" | ");
                    failures.append(candidate)
                            .append(": ")
                            .append(e.getClass().getSimpleName())
                            .append(": ")
                            .append(e.getMessage());
                }
            }else{
                if(failures.length()>0)failures.append(" | ");
                failures.append(candidate)
                        .append(": ")
                        .append(probe.output==null?"failed":probe.output.replace('\n',' '));
            }
        }

        activeSuPath=null;
        lastSuDiagnostic=failures.toString();

        String hint=lastSuDiagnostic.contains("Cannot connect to daemon")
                || lastSuDiagnostic.contains("Connection refused")
                ? "Magisk daemon is unreachable from the app process. ADB root may still work if the app is isolated from Magisk's daemon socket."
                : "No usable root client was found for the app process.";

        throw new IOException(hint+
                (lastSuDiagnostic.isEmpty()?"":" Candidates: "+lastSuDiagnostic));
    }


    public synchronized Result exec(String command) {
        StringBuilder out = new StringBuilder();
        int rc = -1;
        try {
            ensure();
            String marker = "__KB1001_DONE_" + (++sequence) + "__";
            writer.write(command + "; __kb_rc=$?; echo " + marker + "$__kb_rc\n");
            writer.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(marker)) {
                    rc = Integer.parseInt(line.substring(marker.length()).trim());
                    break;
                }
                out.append(line).append('\n');
            }
            if (line == null) reset();
        } catch (Exception e) {
            out.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
            reset();
        }
        return new Result(rc, out.toString().trim());
    }

    public Result ctl(String args) {
        Context context = BackendManager.context();
        if (context != null) {
            Result ready = BackendManager.ensureInstalled(context);
            if (!ready.ok()) return ready;
        }
        return exec("test -r " + shellQuote(BackendManager.CONTROLLER) +
                " && sh " + shellQuote(BackendManager.CONTROLLER) + " " + args);
    }

    public synchronized Result writeRootFile(String path, byte[] data, String mode) {
        if (path == null || path.isEmpty()) {
            return new Result(-1, "writeRootFile: empty path");
        }
        if (data == null) data = new byte[0];

        String parent = new File(path).getParent();
        if (parent == null || parent.isEmpty()) {
            return new Result(-1, "writeRootFile: path has no parent: " + path);
        }

        // Reuse the persistent root shell opened by exec(). The old
        // implementation spawned a fresh `su -c` process for every file.
        String encoded = Base64.encodeToString(data, Base64.NO_WRAP);
        String command =
                "mkdir -p " + shellQuote(parent) +
                " && printf '%s' " + shellQuote(encoded) +
                " | base64 -d > " + shellQuote(path) +
                " && chmod " + mode + " " + shellQuote(path);

        Result written = exec(command);
        if (!written.ok()) {
            String detail = written.output == null ? "" : written.output.trim();
            return new Result(
                    written.exitCode,
                    "writeRootFile failed: " + path +
                            (detail.isEmpty() ? "" : "\n" + detail));
        }

        Result verified = exec(
                "test -f " + shellQuote(path) +
                " && test \"$(wc -c < " + shellQuote(path) +
                " | tr -dc '0-9')\" = " + shellQuote(String.valueOf(data.length)));

        if (!verified.ok()) {
            String detail = verified.output == null ? "" : verified.output.trim();
            return new Result(
                    verified.exitCode,
                    "writeRootFile verification failed: " + path +
                            " expected_bytes=" + data.length +
                            (detail.isEmpty() ? "" : "\n" + detail));
        }

        return new Result(0, "written=" + path + "\nbytes=" + data.length);
    }


    public synchronized String rootDiagnostic() {
        return lastSuDiagnostic==null?"":lastSuDiagnostic;
    }

    public synchronized String activeSuPath() {
        return activeSuPath==null?"":activeSuPath;
    }

    public static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void reset() {
        try { if (process != null) process.destroy(); } catch (Exception ignored) {}
        process = null;
        writer = null;
        reader = null;
            activeSuPath = null;
}

    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean ok() { return exitCode == 0; }
    }
}
