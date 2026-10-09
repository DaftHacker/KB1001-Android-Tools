package com.dafthacker.kb1001perf;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.*;

public final class CpuOppFirmwareActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView status;
    private volatile boolean busy;
    private final java.util.Map<Integer,Switch> toggles=new java.util.LinkedHashMap<>();
    private Button applyConfiguration;
    private boolean loadingStates;
    private final java.util.Map<Integer,TextView> oppLabels=new java.util.LinkedHashMap<>();
    private static final int[] CLOCKS={1296,1344,1368,1416,1464,1512,1560,1608,1776};
    private static final String ROOT="/data/local/kb1001perf/opp_firmware";

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        ScrollView scroll=new ScrollView(this);
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(1);
        layout.setPadding(24,28,24,32);
        layout.setBackgroundColor(Color.rgb(16,23,28));
        scroll.addView(layout);setContentView(scroll);
        TextView heading=new TextView(this);
        heading.setText("CPU Firmware OPP Manager");
        heading.setTextSize(22);heading.setTextColor(Color.WHITE);
        layout.addView(heading);
        TextView warning=new TextView(this);
        warning.setText("Select any combination of nine approved frequencies, then prepare one verified firmware image. " +
            "No partition write or reboot occurs here; installation is not enabled. " +
            "1800 MHz is not validated and is excluded.");
        warning.setTextColor(Color.LTGRAY);warning.setPadding(0,8,0,14);
        layout.addView(warning);
        Button reload=new Button(this);
        reload.setText("Refresh actual OPP state");
        reload.setOnClickListener(v->refresh());
        layout.addView(reload);
        status=new TextView(this);
        status.setTextColor(Color.WHITE);status.setPadding(0,10,0,14);
        layout.addView(status);
        for(int mhz:CLOCKS) {
            String cluster=mhz<=1512?"CPU0–1":mhz<1700?"CPU4":"CPU2–3";
            TextView label=new TextView(this);
            label.setText(cluster+" • "+mhz+" MHz");
            label.setTextColor(Color.WHITE);label.setTextSize(15);
            layout.addView(label);
            TextView oppStatus=new TextView(this);
            oppStatus.setText("Reading actual firmware state…");
            oppStatus.setTextColor(Color.LTGRAY);
            layout.addView(oppStatus);
            oppLabels.put(mhz,oppStatus);
            Switch toggle=new Switch(this);
            toggle.setText("Unlocked");
            toggle.setTextColor(Color.WHITE);
            toggle.setEnabled(false);
            toggle.setOnCheckedChangeListener((v,checked)->{
                toggle.setText(checked?"Unlocked":"Locked");
                if(!loadingStates) status.setText("Configuration edited. Press Prepare configuration to build one candidate. Changes are not installed.");
            });
            toggles.put(mhz,toggle);
            layout.addView(toggle);
        }
        applyConfiguration=new Button(this);
        applyConfiguration.setText("PREPARE NINE-OPP CONFIGURATION");
        applyConfiguration.setEnabled(false);
        applyConfiguration.setOnClickListener(v->confirmConfiguration());
        layout.addView(applyConfiguration);
        refresh();
    }

    private void info(String msg){runOnUiThread(()->status.setText(msg));}
    private static String key(String data,String key){
        for(String line:data.split("\n"))if(line.startsWith(key+"="))
            return line.substring(key.length()+1).trim();
        return "";
    }
    private static String sha(byte[] bytes)throws Exception{
        StringBuilder s=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))
            s.append(String.format(Locale.US,"%02x",b&255));
        return s.toString();
    }
    private static String q(String v){return RootBridge.shellQuote(v);}
    private void refresh(){
        if(busy)return;
        busy=true;
        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("firmware status");
            final String output=r.output;
            runOnUiThread(()->{
                status.setText(r.ok()?output:"Firmware status unavailable:\n"+output);
                boolean recognized=r.ok() && "recognized".equals(key(output,"firmware_source"));
                loadingStates=true;
                boolean complete=true;
                for(int mhz:CLOCKS){
                    String[] fields=key(output,"opp_"+mhz).split(",",-1);
                    boolean unlocked=fields.length==4 && "Y".equals(fields[0]) && "Y".equals(fields[1]) && fields[2].equals(fields[3]);
                    boolean locked=fields.length==4 && "N".equals(fields[0]) && "N".equals(fields[1]) && "0".equals(fields[2]);
                    TextView label=oppLabels.get(mhz);
                    label.setText(unlocked?"UNLOCKED • "+fields[2]+" µV":locked?"LOCKED":"UNKNOWN / inconsistent");
                    label.setTextColor(unlocked?Color.rgb(112,226,162):locked?Color.rgb(253,195,88):Color.LTGRAY);
                    Switch toggle=toggles.get(mhz);
                    toggle.setChecked(unlocked);
                    toggle.setEnabled(recognized && (unlocked||locked));
                    if(!unlocked&&!locked) complete=false;
                }
                loadingStates=false;
                applyConfiguration.setEnabled(recognized && complete);
                busy=false;
            });
        });
    }
    private void confirmConfiguration(){
        if(busy)return;
        StringBuilder mask=new StringBuilder();
        for(int mhz:CLOCKS)mask.append(toggles.get(mhz).isChecked()?'1':'0');
        String selection=mask.toString();
        new AlertDialog.Builder(this)
            .setTitle("Prepare one firmware configuration?")
            .setMessage("Build one verified vendor_boot candidate for all nine selected OPPs. The existing firmware is backed up. Nothing will be flashed or rebooted.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Prepare", (dialog,which)->prepareConfiguration(selection))
            .show();
    }
    private void prepareConfiguration(String mask){
        if(busy)return;
        busy=true;
        info("Preparing combined OPP configuration. NO FLASH.");
        io.execute(()->{
            String tx="tx-"+System.currentTimeMillis();
            RootBridge root=RootBridge.get();
            try{
                RootBridge.Result started=root.ctl("firmware start "+tx+" 0 config");
                if(!started.ok())throw new IllegalStateException("Preflight: "+started.output);
                String path=key(started.output,"original_dtb");
                if(!path.equals(ROOT+"/"+tx+"/work/dtb"))throw new IllegalStateException("Unexpected DTB path");
                byte[] original=readDtb(root,path);
                boolean changed=false;
                for(int i=0;i<CLOCKS.length;i++)
                    if(CpuOppDtbPatcher.inspect(original,CLOCKS[i]).enabled!=(mask.charAt(i)=='1'))changed=true;
                if(!changed)throw new IllegalStateException("All nine OPPs already match the selected configuration");
                byte[] patched=CpuOppDtbPatcher.patchConfiguration(original,mask);
                for(int i=0;i<CLOCKS.length;i++)
                    if(CpuOppDtbPatcher.inspect(patched,CLOCKS[i]).enabled!=(mask.charAt(i)=='1'))
                        throw new IllegalStateException("Combined OPP validation failed");
                writeDtb(root,path,patched);
                RootBridge.Result result=root.ctl("firmware finish "+tx+" 0 config "+sha(patched));
                if(!result.ok())throw new IllegalStateException("Candidate verification failed: "+result.output);
                info("ONE CONFIGURATION READY — NOT INSTALLED\n"+result.output+
                    "\nKeep recovery.img and the manifest. Do not flash without independent verification.");
            }catch(Exception error){
                info("FAILED — NO FLASH:\n"+error.getMessage());
            }finally{busy=false;}
        });
    }
    private static byte[] readDtb(RootBridge root,String path)throws Exception{
        RootBridge.Result r=root.exec("base64 "+q(path)+" | tr -d '\\r\\n'; echo");
        if(!r.ok())throw new IllegalStateException("Reading DTB failed: "+r.output);
        String b64=r.output.trim();
        if(b64.length()<100 || b64.length()>5000000)
            throw new IllegalStateException("Unexpected DTB content size");
        return Base64.decode(b64,Base64.DEFAULT);
    }
    private static void writeDtb(RootBridge root,String path,byte[] data)throws Exception{
        RootBridge.Result init=root.exec("umask 077; : > "+q(path));
        if(!init.ok())throw new IllegalStateException("Cannot prepare DTB staging file");
        for(int i=0;i<data.length;i+=2048){
            byte[] chunk=new byte[Math.min(2048,data.length-i)];
            System.arraycopy(data,i,chunk,0,chunk.length);
            String b64=Base64.encodeToString(chunk,Base64.NO_WRAP);
            RootBridge.Result r=root.exec("printf '%s' "+q(b64)+" | base64 -d >> "+q(path));
            if(!r.ok())throw new IllegalStateException("DTB staging write failed at "+i);
        }
        RootBridge.Result proof=root.exec("sha256sum "+q(path));
        if(!proof.ok() || !proof.output.startsWith(sha(data)))
            throw new IllegalStateException("Staged DTB checksum mismatch");
    }
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
