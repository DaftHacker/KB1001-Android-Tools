package com.dafthacker.kb1001perf;

import android.content.Context;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class RootBridge {
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
        Context context = BackendManager.context();
        if (context != null) {
            Result ready = BackendManager.ensureInstalled(context);
            if (!ready.ok()) return ready;
        }
        return exec("test -r " + shellQuote(BackendManager.CONTROLLER) +
                " && sh " + shellQuote(BackendManager.CONTROLLER) + " " + args);
    }

    public Result writeRootFile(String path, byte[] data, String mode) {
        String parent = new File(path).getParent();
        String command =
                "mkdir -p " + shellQuote(parent) +
                " && cat > " + shellQuote(path) +
                " && chmod " + mode + " " + shellQuote(path);

        StringBuilder output = new StringBuilder();
        int rc = -1;
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            try (OutputStream out = p.getOutputStream()) {
                out.write(data);
                out.flush();
            }

            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            rc = p.waitFor();
        } catch (Exception e) {
            output.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
            }
        }

        return new Result(rc, output.toString().trim());
    }

    public static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void reset() {
        try { if (process != null) process.destroy(); } catch (Exception ignored) {}
        process = null;
        writer = null;
        reader = null;
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
