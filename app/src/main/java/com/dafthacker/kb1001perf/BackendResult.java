package com.dafthacker.kb1001perf;

public final class BackendResult {
    public final int exitCode;
    public final String output;
    public final String backend;

    public BackendResult(int exitCode, String output, String backend) {
        this.exitCode = exitCode;
        this.output = output == null ? "" : output;
        this.backend = backend == null ? "unknown" : backend;
    }

    public boolean ok() {
        return exitCode == 0;
    }
}
