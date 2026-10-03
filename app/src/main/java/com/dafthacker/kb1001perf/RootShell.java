package com.dafthacker.kb1001perf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class RootShell {
    public static final String CONTROLLER = "/data/adb/modules/kb1001_gpu_profiles/kb1001ctl";

    private RootShell() {}

    public static Result exec(String command) {
        StringBuilder out = new StringBuilder();
        int code = -1;
        try {
            Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            code = process.waitFor();
        } catch (Exception e) {
            out.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        }
        return new Result(code, out.toString().trim());
    }

    public static Result ctl(String args) {
        String cmd = "test -x " + CONTROLLER + " && " + CONTROLLER + " " + args;
        return exec(cmd);
    }

    public static final class Result {
        public final int exitCode;
        public final String output;

        public Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }
}
