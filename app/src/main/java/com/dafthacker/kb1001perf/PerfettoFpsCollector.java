package com.dafthacker.kb1001perf;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Long-lived SurfaceFlinger FrameTimeline consumer.
 *
 * Perfetto writes a normal Trace protobuf to stdout. We only decode the tiny
 * subset required for FPS: Trace.packet (field 1), TracePacket.timestamp
 * (field 8), the Android FrameTimelineEvent extension (field 76), and the
 * ActualSurfaceFrame/ActualDisplayFrame/FrameEnd messages inside it.
 */
final class PerfettoFpsCollector implements AutoCloseable {
    interface Listener {
        void onSample(Sample sample);
    }

    static final class Sample {
        final int fps;
        final String packageName;
        final String kind;
        final String layer;
        final int newFrames;
        final int candidateCount;
        final int cycleMs;

        Sample(int fps, String packageName, String kind, String layer,
               int newFrames, int candidateCount, int cycleMs) {
            this.fps = fps;
            this.packageName = packageName == null ? "" : packageName;
            this.kind = kind == null ? "" : kind;
            this.layer = layer == null ? "" : layer;
            this.newFrames = Math.max(0, newFrames);
            this.candidateCount = Math.max(0, candidateCount);
            this.cycleMs = Math.max(0, cycleMs);
        }
    }

    private static final String PERFETTO_CONFIG =
            "buffers { size_kb: 2048 fill_policy: RING_BUFFER }\n" +
            "data_sources { config { name: \"android.surfaceflinger.frametimeline\" target_buffer: 0 } }\n" +
            "write_into_file: true\n" +
            "file_write_period_ms: 100\n" +
            "flush_period_ms: 250\n";

    private static final long FPS_WINDOW_NS = 500_000_000L;
    private static final long STALL_NS = 750_000_000L;
    private static final long EMIT_NS = 100_000_000L;
    private static final long UNRESOLVED_EMIT_NS = 500_000_000L;
    private static final int MAX_CORRELATION_ENTRIES = 768;

    private final String targetStreamCommand;
    private final Listener listener;
    private final Object lock = new Object();

    private volatile boolean running;
    private volatile Process perfettoProcess;
    private volatile Process targetProcess;
    private Thread perfettoThread;
    private Thread targetThread;
    private Thread publishThread;

    private Target target = Target.EMPTY;
    private String currentLayer = "";
    private long targetGeneration;

    private final LinkedHashMap<Long, Long> displayTokenToCookie = new LinkedHashMap<>();
    private final LinkedHashSet<Long> targetDisplayTokens = new LinkedHashSet<>();
    private final LinkedHashSet<Long> targetDisplayCookies = new LinkedHashSet<>();
    private final LinkedHashMap<Long, Long> endedCookies = new LinkedHashMap<>();
    private final LinkedHashSet<Long> countedCookies = new LinkedHashSet<>();
    private final TreeSet<Long> presentationTimes = new TreeSet<>();

    private long lastPresentedWallNs;
    private long lastEmitWallNs;
    private long lastUnresolvedEmitNs;
    private int framesSinceEmit;
    private boolean hasLiveFrame;
    private String lastPerfettoError = "";

    PerfettoFpsCollector(String targetStreamCommand, Listener listener) {
        this.targetStreamCommand = targetStreamCommand;
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;

        targetThread = daemon("KB1001-fps-target", this::runTargetLoop);
        perfettoThread = daemon("KB1001-fps-perfetto", this::runPerfettoLoop);
        publishThread = daemon("KB1001-fps-publish", this::runPublishLoop);
        targetThread.start();
        perfettoThread.start();
        publishThread.start();
    }

    private static Thread daemon(String name, Runnable runnable) {
        Thread t = new Thread(runnable, name);
        t.setDaemon(true);
        return t;
    }

    private void runTargetLoop() {
        while (running) {
            try {
                Process p = new ProcessBuilder("su", "-c", targetStreamCommand)
                        .redirectErrorStream(true)
                        .start();
                targetProcess = p;

                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (running && (line = in.readLine()) != null) {
                        Target parsed = Target.parse(line);
                        if (!parsed.packageName.isEmpty()) updateTarget(parsed);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                destroy(targetProcess);
                targetProcess = null;
            }

            sleepQuietly(500);
        }
    }

    private void updateTarget(Target next) {
        Sample unresolved = null;
        synchronized (lock) {
            // pidof can briefly add/remove secondary processes while the same
            // game remains foreground. Refresh the PID set without throwing
            // away valid presentation history; only a package switch resets
            // the FPS correlation state.
            if (target.packageName.equals(next.packageName)) {
                target = next;
                return;
            }
            target = next;
            targetGeneration++;
            currentLayer = "";
            clearCorrelationLocked();
            presentationTimes.clear();
            lastPresentedWallNs = 0;
            hasLiveFrame = false;
            framesSinceEmit = 0;
            lastEmitWallNs = 0;
            lastUnresolvedEmitNs = System.nanoTime();
            unresolved = new Sample(-1, next.packageName, "target", "", 0,
                    next.pids.size(), 0);
        }
        emit(unresolved);
    }

    private void runPerfettoLoop() {
        while (running) {
            Process p = null;
            Thread stderr = null;
            try {
                synchronized (lock) { lastPerfettoError = ""; }
                p = new ProcessBuilder(
                        "su", "-c", "exec /system/bin/perfetto --txt -c - -o -")
                        .start();
                perfettoProcess = p;

                final Process processForErr = p;
                stderr = daemon("KB1001-fps-perfetto-stderr", () -> drainPerfettoErrors(processForErr));
                stderr.start();

                try (OutputStream config = p.getOutputStream()) {
                    config.write(PERFETTO_CONFIG.getBytes(StandardCharsets.UTF_8));
                    config.flush();
                }

                parseTrace(p.getInputStream());

                if (running) {
                    int exit = waitForQuietly(p);
                    String detail;
                    synchronized (lock) {
                        detail = lastPerfettoError;
                    }
                    emitPerfettoError("perfetto exited " + exit +
                            (detail.isEmpty() ? "" : ": " + detail));
                }
            } catch (Exception e) {
                if (running) emitPerfettoError(e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            } finally {
                destroy(p);
                if (perfettoProcess == p) perfettoProcess = null;
                if (stderr != null) stderr.interrupt();
            }

            sleepQuietly(1000);
        }
    }

    private void drainPerfettoErrors(Process p) {
        try (BufferedReader err = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = err.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                synchronized (lock) {
                    lastPerfettoError = line.length() > 240 ? line.substring(0, 240) : line;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void emitPerfettoError(String detail) {
        Target snapshot;
        synchronized (lock) {
            snapshot = target;
        }
        emit(new Sample(-1, snapshot.packageName, "perfetto_error", detail,
                0, snapshot.pids.size(), 0));
    }

    private void runPublishLoop() {
        while (running) {
            sleepQuietly(100);
            if (!running) break;

            Sample sample = null;
            long now = System.nanoTime();
            synchronized (lock) {
                Target snapshot = target;
                if (snapshot.packageName.isEmpty()) continue;

                if (hasLiveFrame) {
                    if (lastPresentedWallNs > 0 && now - lastPresentedWallNs >= STALL_NS) {
                        if (now - lastEmitWallNs >= EMIT_NS) {
                            int frames = framesSinceEmit;
                            framesSinceEmit = 0;
                            lastEmitWallNs = now;
                            sample = new Sample(0, snapshot.packageName, "stall", currentLayer,
                                    frames, snapshot.pids.size(), 0);
                        }
                    } else if (framesSinceEmit > 0 && now - lastEmitWallNs >= EMIT_NS) {
                        int fps = rollingFpsLocked();
                        int frames = framesSinceEmit;
                        framesSinceEmit = 0;
                        lastEmitWallNs = now;
                        sample = new Sample(fps, snapshot.packageName, "live", currentLayer,
                                frames, snapshot.pids.size(), 0);
                    }
                } else if (now - lastUnresolvedEmitNs >= UNRESOLVED_EMIT_NS) {
                    lastUnresolvedEmitNs = now;
                    sample = new Sample(-1, snapshot.packageName, "unresolved", currentLayer,
                            0, snapshot.pids.size(), 0);
                }
            }
            if (sample != null) emit(sample);
        }
    }

    private void parseTrace(InputStream in) throws IOException {
        while (running) {
            long key = readVarint(in, true);
            if (key < 0) return;
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7L);

            if (field == 1 && wire == 2) {
                int len = checkedLength(readVarint(in, false));
                byte[] packet = readExact(in, len);
                parseTracePacket(packet);
            } else {
                skipValue(in, wire);
            }
        }
    }

    private void parseTracePacket(byte[] packet) throws IOException {
        ProtoReader r = new ProtoReader(packet);
        long timestampNs = 0;
        byte[] frameTimeline = null;

        while (r.hasRemaining()) {
            long key = r.readVarint();
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7L);
            if (field == 8 && wire == 0) {
                timestampNs = r.readVarint();
            } else if (field == 76 && wire == 2) {
                frameTimeline = r.readBytes();
            } else {
                r.skip(wire);
            }
        }

        if (frameTimeline != null && timestampNs > 0) {
            parseFrameTimeline(frameTimeline, timestampNs);
        }
    }

    private void parseFrameTimeline(byte[] data, long timestampNs) throws IOException {
        ProtoReader r = new ProtoReader(data);
        while (r.hasRemaining()) {
            long key = r.readVarint();
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7L);
            if (wire != 2) {
                r.skip(wire);
                continue;
            }

            byte[] nested = r.readBytes();
            if (field == 2) {
                parseActualDisplay(nested, timestampNs);
            } else if (field == 4) {
                parseActualSurface(nested, timestampNs);
            } else if (field == 5) {
                parseFrameEnd(nested, timestampNs);
            }
        }
    }

    private void parseActualSurface(byte[] data, long timestampNs) throws IOException {
        ProtoReader r = new ProtoReader(data);
        long displayToken = Long.MIN_VALUE;
        int pid = -1;
        int presentType = 0;
        String layer = "";

        while (r.hasRemaining()) {
            long key = r.readVarint();
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7L);
            if (wire == 0) {
                long value = r.readVarint();
                if (field == 3) displayToken = value;
                else if (field == 4) pid = (int) value;
                else if (field == 6) presentType = (int) value;
            } else if (wire == 2 && field == 5) {
                layer = r.readString();
            } else {
                r.skip(wire);
            }
        }

        if (displayToken == Long.MIN_VALUE || presentType == 4) return;

        synchronized (lock) {
            long generation = targetGeneration;
            if (!matchesTargetLocked(pid, layer)) return;
            if (generation != targetGeneration) return;

            currentLayer = layer;
            targetDisplayTokens.add(displayToken);
            trimSet(targetDisplayTokens);
            Long cookie = displayTokenToCookie.get(displayToken);
            if (cookie != null) markTargetCookieLocked(cookie);
        }
    }

    private void parseActualDisplay(byte[] data, long timestampNs) throws IOException {
        ProtoReader r = new ProtoReader(data);
        long cookie = Long.MIN_VALUE;
        long token = Long.MIN_VALUE;
        int presentType = 0;

        while (r.hasRemaining()) {
            long key = r.readVarint();
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7L);
            if (wire == 0) {
                long value = r.readVarint();
                if (field == 1) cookie = value;
                else if (field == 2) token = value;
                else if (field == 4) presentType = (int) value;
            } else {
                r.skip(wire);
            }
        }

        if (cookie == Long.MIN_VALUE || token == Long.MIN_VALUE || presentType == 4) return;

        synchronized (lock) {
            displayTokenToCookie.put(token, cookie);
            trimMap(displayTokenToCookie);
            if (targetDisplayTokens.contains(token)) markTargetCookieLocked(cookie);
        }
    }

    private void parseFrameEnd(byte[] data, long timestampNs) throws IOException {
        ProtoReader r = new ProtoReader(data);
        long cookie = Long.MIN_VALUE;
        while (r.hasRemaining()) {
            long key = r.readVarint();
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7L);
            if (field == 1 && wire == 0) cookie = r.readVarint();
            else r.skip(wire);
        }
        if (cookie == Long.MIN_VALUE) return;

        synchronized (lock) {
            endedCookies.put(cookie, timestampNs);
            trimMap(endedCookies);
            if (targetDisplayCookies.contains(cookie)) countCookieLocked(cookie, timestampNs);
        }
    }

    private void markTargetCookieLocked(long cookie) {
        targetDisplayCookies.add(cookie);
        trimSet(targetDisplayCookies);
        Long endedAt = endedCookies.get(cookie);
        if (endedAt != null) countCookieLocked(cookie, endedAt);
    }

    private void countCookieLocked(long cookie, long timestampNs) {
        if (!countedCookies.add(cookie)) return;
        trimSet(countedCookies);

        presentationTimes.add(timestampNs);
        long newest = presentationTimes.last();
        long cutoff = newest - FPS_WINDOW_NS;
        while (!presentationTimes.isEmpty() && presentationTimes.first() < cutoff) {
            presentationTimes.pollFirst();
        }

        lastPresentedWallNs = System.nanoTime();
        hasLiveFrame = true;
        framesSinceEmit++;
    }

    private int rollingFpsLocked() {
        if (presentationTimes.size() < 2) return -1;
        long first = presentationTimes.first();
        long last = presentationTimes.last();
        long span = last - first;
        if (span <= 0) return -1;
        double fps = (presentationTimes.size() - 1) * 1_000_000_000.0 / span;
        int rounded = (int) Math.round(fps);
        if (rounded < 0) return 0;
        return Math.min(240, rounded);
    }

    private boolean matchesTargetLocked(int pid, String layer) {
        if (target.packageName.isEmpty()) return false;
        if (pid > 0 && target.pids.contains(pid)) return true;
        return layer != null && !layer.isEmpty() &&
                layer.toLowerCase(Locale.US).contains(target.packageName.toLowerCase(Locale.US));
    }

    private void clearCorrelationLocked() {
        displayTokenToCookie.clear();
        targetDisplayTokens.clear();
        targetDisplayCookies.clear();
        endedCookies.clear();
        countedCookies.clear();
    }

    private static <K, V> void trimMap(LinkedHashMap<K, V> map) {
        while (map.size() > MAX_CORRELATION_ENTRIES) {
            K first = map.keySet().iterator().next();
            map.remove(first);
        }
    }

    private static <T> void trimSet(LinkedHashSet<T> set) {
        while (set.size() > MAX_CORRELATION_ENTRIES) {
            T first = set.iterator().next();
            set.remove(first);
        }
    }

    private void emit(Sample sample) {
        if (!running || sample == null || listener == null) return;
        try {
            listener.onSample(sample);
        } catch (Throwable ignored) {
        }
    }

    @Override public void close() {
        running = false;
        destroy(perfettoProcess);
        destroy(targetProcess);
        if (perfettoThread != null) perfettoThread.interrupt();
        if (targetThread != null) targetThread.interrupt();
        if (publishThread != null) publishThread.interrupt();
    }

    private static void destroy(Process p) {
        if (p == null) return;
        try { p.destroy(); } catch (Exception ignored) {}
    }

    private static int waitForQuietly(Process p) {
        if (p == null) return -1;
        try { return p.waitFor(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static long readVarint(InputStream in, boolean allowCleanEof) throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.read();
            if (b < 0) {
                if (shift == 0 && allowCleanEof) return -1;
                throw new EOFException("truncated varint");
            }
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("varint too long");
    }

    private static int checkedLength(long len) throws IOException {
        if (len < 0 || len > 8 * 1024 * 1024L) throw new IOException("invalid protobuf length " + len);
        return (int) len;
    }

    private static byte[] readExact(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(out, off, len - off);
            if (n < 0) throw new EOFException("truncated protobuf field");
            off += n;
        }
        return out;
    }

    private static void skipValue(InputStream in, int wire) throws IOException {
        switch (wire) {
            case 0:
                readVarint(in, false);
                return;
            case 1:
                skipExact(in, 8);
                return;
            case 2:
                skipExact(in, checkedLength(readVarint(in, false)));
                return;
            case 5:
                skipExact(in, 4);
                return;
            default:
                throw new IOException("unsupported protobuf wire type " + wire);
        }
    }

    private static void skipExact(InputStream in, int len) throws IOException {
        int remaining = len;
        byte[] scratch = new byte[Math.min(8192, Math.max(1, len))];
        while (remaining > 0) {
            int n = in.read(scratch, 0, Math.min(scratch.length, remaining));
            if (n < 0) throw new EOFException("truncated protobuf skip");
            remaining -= n;
        }
    }

    private static final class ProtoReader {
        private final byte[] data;
        private int pos;

        ProtoReader(byte[] data) { this.data = data == null ? new byte[0] : data; }
        boolean hasRemaining() { return pos < data.length; }

        long readVarint() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= data.length) throw new EOFException("truncated varint");
                int b = data[pos++] & 0xff;
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw new IOException("varint too long");
        }

        byte[] readBytes() throws IOException {
            int len = checkedLength(readVarint());
            require(len);
            byte[] out = new byte[len];
            System.arraycopy(data, pos, out, 0, len);
            pos += len;
            return out;
        }

        String readString() throws IOException {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        void skip(int wire) throws IOException {
            switch (wire) {
                case 0: readVarint(); return;
                case 1: require(8); pos += 8; return;
                case 2:
                    int len = checkedLength(readVarint());
                    require(len);
                    pos += len;
                    return;
                case 5: require(4); pos += 4; return;
                default: throw new IOException("unsupported protobuf wire type " + wire);
            }
        }

        private void require(int len) throws EOFException {
            if (len < 0 || pos + len > data.length) throw new EOFException("truncated protobuf field");
        }
    }

    private static final class Target {
        static final Target EMPTY = new Target("", new HashSet<>());
        final String packageName;
        final Set<Integer> pids;

        Target(String packageName, Set<Integer> pids) {
            this.packageName = packageName == null ? "" : packageName.trim();
            this.pids = pids == null ? new HashSet<>() : pids;
        }

        static Target parse(String line) {
            if (line == null) return EMPTY;
            String[] parts = line.trim().split("\\|", 2);
            String pkg = parts.length > 0 ? parts[0].trim() : "";
            Set<Integer> pids = new HashSet<>();
            if (parts.length > 1) {
                for (String raw : parts[1].split(",")) {
                    try {
                        int pid = Integer.parseInt(raw.trim());
                        if (pid > 0) pids.add(pid);
                    } catch (Exception ignored) {
                    }
                }
            }
            return new Target(pkg, pids);
        }
    }
}
