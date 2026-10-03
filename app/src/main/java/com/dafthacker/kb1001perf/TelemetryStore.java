package com.dafthacker.kb1001perf;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TelemetryStore {
    private TelemetryStore() {}

    public static File ensureSnapshot(Context context) {
        File dir = context.getExternalFilesDir("telemetry");
        if (dir == null) dir = new File(context.getFilesDir(), "telemetry");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "current.txt");
        try {
            if (!file.exists()) file.createNewFile();
        } catch (Exception ignored) {}
        return file;
    }

    public static Map<String, String> read(Context context) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        try {
            File f = ensureSnapshot(context);
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                int i = line.indexOf('=');
                if (i > 0) map.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        } catch (Exception ignored) {}
        return map;
    }

    public static String get(Map<String,String> map, String key, String fallback) {
        String v = map.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    public static String pretty(Map<String,String> m) {
        if (m.isEmpty()) return "Waiting for root telemetry daemon…";
        return "MODE      " + get(m,"mode","—") + "\n" +
                "PROFILE   " + get(m,"profile","—") + "\n" +
                "GAME      " + get(m,"package","—") + "\n" +
                "GPU       " + get(m,"gpu_clock_mhz","—") + " MHz  •  " + get(m,"gpu_voltage","—") + "\n" +
                "THERMAL   " + get(m,"thermal_max_c","—") + " °C max\n" +
                "BATTERY   " + get(m,"battery_temp_c","—") + " °C\n" +
                "CPU       " + get(m,"cpu_policies","—") + "\n" +
                "RAM FREE  " + get(m,"mem_available_mb","—") + " MB\n" +
                "LOAD      " + get(m,"loadavg","—") + "\n" +
                "LOGGER    " + get(m,"file_logging","0") + "  " + get(m,"file_path","");
    }
}
