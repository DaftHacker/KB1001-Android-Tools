package com.dafthacker.kb1001perf;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class RootBridge {
    private static final RootBridge INSTANCE = new RootBridge();
    private static final long ROOT_RETRY_BACKOFF_MS = 5000L;

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private long sequence;

    private String activeSuPath="";
    private String lastSuDiagnostic="";
    private long retryAfterElapsed;
    private String retryMessage="";

    private RootBridge() {}

    public static RootBridge get() { return INSTANCE; }

    private String resolveSuPath() {
        File systemXbin=new File("/system/xbin/su");
        if(systemXbin.canExecute())return "/system/xbin/su";

        File systemBin=new File("/system/bin/su");
        if(systemBin.canExecute())return "/system/bin/su";

        return "su";
    }

    private void resetProcess() {
        try {
            if(process!=null)process.destroy();
        } catch(Exception ignored) {
        }
        process=null;
        writer=null;
        reader=null;
        activeSuPath="";
    }

    private void rememberRootFailure(String detail) {
        String clean=detail==null?"":detail.trim();
        if(clean.isEmpty())clean="Root request ended before a privileged shell was established.";

        lastSuDiagnostic=clean;
        retryMessage=clean;
        retryAfterElapsed=SystemClock.elapsedRealtime()+ROOT_RETRY_BACKOFF_MS;
        resetProcess();
    }

    private void ensure() throws Exception {
        if(process!=null && process.isAlive())return;

        long now=SystemClock.elapsedRealtime();
        if(now<retryAfterElapsed){
            throw new IOException(
                    "Previous root request failed; retry suppressed briefly to avoid repeated Magisk prompts. "+
                            retryMessage);
        }

        resetProcess();

        String su=resolveSuPath();
        try {
            process=new ProcessBuilder(su)
                    .redirectErrorStream(true)
                    .start();
            writer=new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(),StandardCharsets.UTF_8));
            reader=new BufferedReader(new InputStreamReader(
                    process.getInputStream(),StandardCharsets.UTF_8));
            activeSuPath=su;
            lastSuDiagnostic="root_request_started_via="+su;
        } catch(Exception e) {
            String detail=e.getClass().getSimpleName()+": "+e.getMessage();
            rememberRootFailure(detail);
            throw e;
        }
    }

    public synchronized Result exec(String command) {
        StringBuilder out=new StringBuilder();
        int rc=-1;
        boolean sawMarker=false;

        try {
            ensure();

            String marker="__KB1001_DONE_"+(++sequence)+"__";
            writer.write(command+"; __kb_rc=$?; echo "+marker+"$__kb_rc\n");
            writer.flush();

            String line;
            while((line=reader.readLine())!=null){
                if(line.startsWith(marker)){
                    rc=Integer.parseInt(line.substring(marker.length()).trim());
                    sawMarker=true;
                    break;
                }
                out.append(line).append('\n');
            }

            if(!sawMarker){
                String detail=out.toString().trim();
                rememberRootFailure(detail);
                return new Result(
                        -1,
                        detail.isEmpty()
                                ?"Root shell exited before command completion."
                                :detail);
            }

            retryAfterElapsed=0;
            retryMessage="";
            lastSuDiagnostic="active_su="+activeSuPath;
        } catch(Exception e) {
            String detail=e.getClass().getSimpleName()+": "+e.getMessage();
            if(process!=null || retryMessage.isEmpty()){
                rememberRootFailure(detail);
            }
            if(out.length()>0)out.append('\n');
            out.append(detail);
        }

        return new Result(rc,out.toString().trim());
    }

    public Result ctl(String args) {
        Context context=BackendManager.context();
        if(context!=null){
            Result ready=BackendManager.ensureInstalled(context);
            if(!ready.ok())return ready;
        }

        return exec(
                "test -r "+shellQuote(BackendManager.CONTROLLER)+
                        " && sh "+shellQuote(BackendManager.CONTROLLER)+" "+args);
    }

    public synchronized Result writeRootFile(String path,byte[] data,String mode) {
        if(path==null || path.isEmpty()){
            return new Result(-1,"writeRootFile: empty path");
        }
        if(data==null)data=new byte[0];

        String parent=new File(path).getParent();
        if(parent==null || parent.isEmpty()){
            return new Result(-1,"writeRootFile: path has no parent: "+path);
        }

        String encoded=Base64.encodeToString(data,Base64.NO_WRAP);
        String command=
                "mkdir -p "+shellQuote(parent)+
                " && printf '%s' "+shellQuote(encoded)+
                " | base64 -d > "+shellQuote(path)+
                " && chmod "+mode+" "+shellQuote(path);

        Result written=exec(command);
        if(!written.ok()){
            String detail=written.output==null?"":written.output.trim();
            return new Result(
                    written.exitCode,
                    "writeRootFile failed: "+path+
                            (detail.isEmpty()?"":"\n"+detail));
        }

        Result verified=exec(
                "test -f "+shellQuote(path)+
                        " && test \"$(wc -c < "+shellQuote(path)+
                        " | tr -dc '0-9')\" = "+
                        shellQuote(String.valueOf(data.length)));

        if(!verified.ok()){
            String detail=verified.output==null?"":verified.output.trim();
            return new Result(
                    verified.exitCode,
                    "writeRootFile verification failed: "+path+
                            " expected_bytes="+data.length+
                            (detail.isEmpty()?"":"\n"+detail));
        }

        return new Result(0,"written="+path+"\nbytes="+data.length);
    }

    /**
     * User explicitly requested another foreground authorization attempt.
     * This clears only our local anti-spam timer; it does not alter Magisk's
     * Superuser policy.
     */
    public synchronized void clearRetryBackoff() {
        retryAfterElapsed=0;
        retryMessage="";
        lastSuDiagnostic="explicit_root_retry_requested";
        resetProcess();
    }

    public synchronized String rootDiagnostic() {
        return lastSuDiagnostic==null?"":lastSuDiagnostic;
    }

    public synchronized String activeSuPath() {
        return activeSuPath==null?"":activeSuPath;
    }

    public static String shellQuote(String s) {
        return "'"+s.replace("'","'\\''")+"'";
    }

    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode,String output) {
            this.exitCode=exitCode;
            this.output=output;
        }

        public boolean ok() { return exitCode==0; }
    }
}
