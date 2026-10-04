package com.dafthacker.kb1001perf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public final class UpdateManager {
    public static final String RELEASE_API =
            "https://api.github.com/repos/DaftHacker/KB1001-Android-Tools/releases/tags/dev-latest";

    private UpdateManager() {}

    public static final class ReleaseInfo {
        public int appVersionCode;
        public String appVersionName;
        public String commit;
        public Asset app;
    }

    public static final class Asset {
        public String name;
        public String apiUrl;
        public String sha256;
        public long size;
    }

    public static ReleaseInfo check(Context context) throws Exception {
        JSONObject release=getJson(RELEASE_API);
        JSONArray assets=release.getJSONArray("assets");

        JSONObject manifestAsset=null;
        for(int i=0;i<assets.length();i++){
            JSONObject a=assets.getJSONObject(i);
            if("update.json".equals(a.optString("name"))){
                manifestAsset=a;
                break;
            }
        }
        if(manifestAsset==null) throw new IOException("update.json is missing from dev-latest");

        byte[] manifestBytes=downloadBytes(
                manifestAsset.getString("url"),true,null);
        JSONObject manifest=new JSONObject(
                new String(manifestBytes,java.nio.charset.StandardCharsets.UTF_8));

        ReleaseInfo out=new ReleaseInfo();
        out.appVersionCode=manifest.getInt("app_version_code");
        out.appVersionName=manifest.getString("app_version_name");
        out.commit=manifest.optString("commit");

        String appName=manifest.getString("app_asset");
        out.app=assetFromRelease(
                assets,
                appName,
                manifest.getString("app_sha256"));
        return out;
    }

    private static Asset assetFromRelease(JSONArray assets,String name,String sha) throws Exception {
        for(int i=0;i<assets.length();i++){
            JSONObject a=assets.getJSONObject(i);
            if(name.equals(a.optString("name"))){
                Asset x=new Asset();
                x.name=name;
                x.apiUrl=a.getString("url");
                x.sha256=sha.toLowerCase(Locale.US);
                x.size=a.optLong("size",-1);
                return x;
            }
        }
        throw new IOException("Release asset missing: "+name);
    }

    public static int installedAppVersion(Context context){
        try{
            if(Build.VERSION.SDK_INT>=33){
                return (int)context.getPackageManager()
                        .getPackageInfo(
                                context.getPackageName(),
                                android.content.pm.PackageManager.PackageInfoFlags.of(0))
                        .getLongVersionCode();
            }
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(),0)
                    .versionCode;
        }catch(Exception e){
            return 0;
        }
    }

    public static File download(Context context,Asset asset,Progress progress) throws Exception {
        File dir=context.getExternalFilesDir("updates");
        if(dir==null)dir=new File(context.getFilesDir(),"updates");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("Could not create update directory");

        File dst=new File(dir,asset.name);
        downloadBytes(asset.apiUrl,true,new FileSink(dst,progress,asset.size));

        String actual=sha256(dst);
        if(!actual.equalsIgnoreCase(asset.sha256)){
            dst.delete();
            throw new IOException("SHA-256 mismatch for "+asset.name);
        }
        return dst;
    }

    public interface Progress{
        void onProgress(long done,long total);
    }

    private static final class FileSink{
        final File file;
        final Progress progress;
        final long expected;

        FileSink(File file,Progress progress,long expected){
            this.file=file;
            this.progress=progress;
            this.expected=expected;
        }
    }

    private static byte[] downloadBytes(String url,boolean apiAsset,FileSink sink) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent","KB1001-Performance-Manager");
        connection.setRequestProperty(
                "Accept",
                apiAsset?"application/octet-stream":"application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
        connection.setInstanceFollowRedirects(true);

        int code=connection.getResponseCode();
        if(code<200||code>=300){
            String msg="";
            try(InputStream error=connection.getErrorStream()){
                if(error!=null){
                    msg=new String(readAll(error),java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            throw new IOException(
                    "GitHub HTTP "+code+(msg.isEmpty()?"":": "+msg));
        }

        long total=connection.getContentLengthLong();
        if(sink==null){
            try(InputStream in=connection.getInputStream()){
                return readAll(in);
            }
        }

        try(InputStream in=connection.getInputStream();
            OutputStream out=new FileOutputStream(sink.file)){
            byte[] buffer=new byte[64*1024];
            long done=0;
            int n;
            while((n=in.read(buffer))>=0){
                out.write(buffer,0,n);
                done+=n;
                if(sink.progress!=null){
                    sink.progress.onProgress(
                            done,
                            total>0?total:sink.expected);
                }
            }
        }
        return new byte[0];
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent","KB1001-Performance-Manager");
        connection.setRequestProperty("Accept","application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");

        int code=connection.getResponseCode();
        if(code<200||code>=300){
            throw new IOException("GitHub HTTP "+code);
        }

        try(InputStream in=connection.getInputStream()){
            return new JSONObject(
                    new String(readAll(in),java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buffer=new byte[32*1024];
        int n;
        while((n=in.read(buffer))>=0)out.write(buffer,0,n);
        return out.toByteArray();
    }

    public static String sha256(File file) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){
            byte[] buffer=new byte[64*1024];
            int n;
            while((n=in.read(buffer))>=0)md.update(buffer,0,n);
        }

        StringBuilder s=new StringBuilder();
        for(byte x:md.digest()){
            s.append(String.format(Locale.US,"%02x",x));
        }
        return s.toString();
    }

    public static void installApk(Activity activity,File apk){
        if(Build.VERSION.SDK_INT>=26 &&
                !activity.getPackageManager().canRequestPackageInstalls()){
            Intent settings=new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:"+activity.getPackageName()));
            activity.startActivity(settings);
            throw new IllegalStateException(
                    "Allow 'Install unknown apps' for KB1001 Performance Manager, then tap Install again.");
        }

        Uri uri=FileProvider.getUriForFile(
                activity,
                activity.getPackageName()+".files",
                apk);

        Intent intent=new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION|
                                Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }
}
