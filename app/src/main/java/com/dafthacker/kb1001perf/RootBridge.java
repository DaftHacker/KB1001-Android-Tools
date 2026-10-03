package com.dafthacker.kb1001perf;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public final class RootBridge {
    public static final String CONTROLLER = "/data/adb/modules/kb1001_gpu_profiles/kb1001ctl";
    private static final RootBridge INSTANCE = new RootBridge();

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private long sequence;

    private RootBridge() {}

    public static RootBridge get() { return INSTANCE; }

    private void ensure() throws Exception {
        if (process != null && process.isAlive()) return;
        process = new ProcessBuilder("su").redirectErrorStream(true).start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
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
        return exec("test -x " + CONTROLLER + " && " + CONTROLLER + " " + args);
    }

    private void reset() {
        try { if (process != null) process.destroy(); } catch (Exception ignored) {}
        process = null; writer = null; reader = null;
    }

    public static final class Result {
        public final int exitCode;
        public final String output;
        Result(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
        public boolean ok() { return exitCode == 0; }
    }
}
