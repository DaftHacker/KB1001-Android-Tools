package com.dafthacker.kb1001perf;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Small process-local state cache backed by SharedPreferences.
 *
 * UI renders from this cache immediately and reconciles with the privileged
 * backend in the background. This avoids rebuilding tabs in an all-off/loading
 * state every time the user navigates.
 */
public final class AppStateCache {
    private static final String PREFS="app_state_cache";
    private static final String KEY_MANUAL_METRICS="manual_metrics";
    private static final String KEY_MANUAL_FPS="manual_fps";
    private static final String KEY_AUTO_BOOST="auto_boost";
    private static final String KEY_FILE_LOGGING="file_logging";
    private static final String KEY_STATUS_TS="status_ts";
    private static final String KEY_GAMES_TS="games_ts";
    private static final String KEY_GAMES_LOADED="games_loaded";
    private static final String KEY_GAMES="games";
    private static final String KEY_METRICS_GAMES="metrics_games";
    private static final String KEY_FPS_GAMES="fps_games";

    private static boolean initialized;
    private static boolean manualMetrics;
    private static boolean manualFps;
    private static boolean autoBoost;
    private static boolean fileLogging;
    private static long statusUpdatedAt;
    private static long gamesUpdatedAt;
    private static boolean gamesLoaded;

    private static final LinkedHashSet<String> games=new LinkedHashSet<>();
    private static final LinkedHashSet<String> metricsGames=new LinkedHashSet<>();
    private static final LinkedHashSet<String> fpsGames=new LinkedHashSet<>();

    private AppStateCache(){}

    public static synchronized void initialize(Context context){
        if(initialized)return;
        SharedPreferences p=prefs(context);
        manualMetrics=p.getBoolean(KEY_MANUAL_METRICS,false);
        manualFps=p.getBoolean(KEY_MANUAL_FPS,false);
        autoBoost=p.getBoolean(KEY_AUTO_BOOST,false);
        fileLogging=p.getBoolean(KEY_FILE_LOGGING,false);
        statusUpdatedAt=p.getLong(KEY_STATUS_TS,0L);
        gamesUpdatedAt=p.getLong(KEY_GAMES_TS,0L);
        gamesLoaded=p.getBoolean(KEY_GAMES_LOADED,false);
        games.clear();
        games.addAll(p.getStringSet(KEY_GAMES,Collections.emptySet()));
        metricsGames.clear();
        metricsGames.addAll(p.getStringSet(KEY_METRICS_GAMES,Collections.emptySet()));
        fpsGames.clear();
        fpsGames.addAll(p.getStringSet(KEY_FPS_GAMES,Collections.emptySet()));
        initialized=true;
    }

    public static synchronized void updateStatus(Context context,Map<String,String> status){
        initialize(context);
        if(status==null)return;
        if(status.containsKey("Manual Metrics overlay")){
            manualMetrics="1".equals(status.get("Manual Metrics overlay"));
        }
        if(status.containsKey("Manual FPS overlay")){
            manualFps="1".equals(status.get("Manual FPS overlay"));
        }
        if(status.containsKey("Auto boost")){
            autoBoost="1".equals(status.get("Auto boost"));
        }
        if(status.containsKey("File logging")){
            fileLogging="1".equals(status.get("File logging"));
        }
        statusUpdatedAt=System.currentTimeMillis();
        prefs(context).edit()
                .putBoolean(KEY_MANUAL_METRICS,manualMetrics)
                .putBoolean(KEY_MANUAL_FPS,manualFps)
                .putBoolean(KEY_AUTO_BOOST,autoBoost)
                .putBoolean(KEY_FILE_LOGGING,fileLogging)
                .putLong(KEY_STATUS_TS,statusUpdatedAt)
                .apply();
    }

    public static synchronized void setManualMetrics(Context context,boolean value){
        initialize(context);
        manualMetrics=value;
        prefs(context).edit().putBoolean(KEY_MANUAL_METRICS,value).apply();
    }

    public static synchronized void setManualFps(Context context,boolean value){
        initialize(context);
        manualFps=value;
        prefs(context).edit().putBoolean(KEY_MANUAL_FPS,value).apply();
    }

    public static synchronized void setAutoBoost(Context context,boolean value){
        initialize(context);
        autoBoost=value;
        prefs(context).edit().putBoolean(KEY_AUTO_BOOST,value).apply();
    }

    public static synchronized void setFileLogging(Context context,boolean value){
        initialize(context);
        fileLogging=value;
        prefs(context).edit().putBoolean(KEY_FILE_LOGGING,value).apply();
    }

    public static synchronized boolean manualMetrics(Context context){
        initialize(context);
        return manualMetrics;
    }

    public static synchronized boolean manualFps(Context context){
        initialize(context);
        return manualFps;
    }

    public static synchronized boolean autoBoost(Context context){
        initialize(context);
        return autoBoost;
    }

    public static synchronized boolean fileLogging(Context context){
        initialize(context);
        return fileLogging;
    }

    public static synchronized boolean statusFresh(Context context,long maxAgeMs){
        initialize(context);
        return statusUpdatedAt>0 &&
                System.currentTimeMillis()-statusUpdatedAt<=maxAgeMs;
    }

    public static synchronized void updateGameSnapshot(
            Context context,Set<String> selected,Set<String> metrics,Set<String> fps){
        initialize(context);
        games.clear();
        if(selected!=null)games.addAll(selected);
        metricsGames.clear();
        if(metrics!=null)metricsGames.addAll(metrics);
        fpsGames.clear();
        if(fps!=null)fpsGames.addAll(fps);
        gamesLoaded=true;
        gamesUpdatedAt=System.currentTimeMillis();
        persistGames(context);
    }

    public static synchronized void addGame(Context context,String pkg){
        initialize(context);
        if(pkg==null||pkg.isEmpty())return;
        games.add(pkg);
        gamesLoaded=true;
        gamesUpdatedAt=System.currentTimeMillis();
        persistGames(context);
    }

    public static synchronized void removeGame(Context context,String pkg){
        initialize(context);
        games.remove(pkg);
        metricsGames.remove(pkg);
        fpsGames.remove(pkg);
        gamesUpdatedAt=System.currentTimeMillis();
        persistGames(context);
    }

    public static synchronized void setGameOverlay(Context context,String pkg,String type,boolean enabled){
        initialize(context);
        Set<String> target="metrics".equals(type)?metricsGames:fpsGames;
        if(enabled)target.add(pkg); else target.remove(pkg);
        gamesUpdatedAt=System.currentTimeMillis();
        persistGames(context);
    }

    public static synchronized GameSnapshot gameSnapshot(Context context){
        initialize(context);
        return new GameSnapshot(
                new LinkedHashSet<>(games),
                new LinkedHashSet<>(metricsGames),
                new LinkedHashSet<>(fpsGames),
                gamesLoaded,
                gamesUpdatedAt);
    }

    public static synchronized boolean gamesFresh(Context context,long maxAgeMs){
        initialize(context);
        return gamesLoaded && gamesUpdatedAt>0 &&
                System.currentTimeMillis()-gamesUpdatedAt<=maxAgeMs;
    }

    private static void persistGames(Context context){
        prefs(context).edit()
                .putStringSet(KEY_GAMES,new LinkedHashSet<>(games))
                .putStringSet(KEY_METRICS_GAMES,new LinkedHashSet<>(metricsGames))
                .putStringSet(KEY_FPS_GAMES,new LinkedHashSet<>(fpsGames))
                .putBoolean(KEY_GAMES_LOADED,gamesLoaded)
                .putLong(KEY_GAMES_TS,gamesUpdatedAt)
                .apply();
    }

    private static SharedPreferences prefs(Context context){
        return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    public static final class GameSnapshot{
        public final Set<String> games;
        public final Set<String> metricsGames;
        public final Set<String> fpsGames;
        public final boolean loaded;
        public final long updatedAt;

        GameSnapshot(Set<String> games,Set<String> metricsGames,Set<String> fpsGames,
                     boolean loaded,long updatedAt){
            this.games=games;
            this.metricsGames=metricsGames;
            this.fpsGames=fpsGames;
            this.loaded=loaded;
            this.updatedAt=updatedAt;
        }
    }
}
