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
    private final java.util.Map<Integer,TextView> oppLabels=new java.util.LinkedHashMap<>();
    private final java.util.Map<Integer,Button> lockButtons=new java.util.LinkedHashMap<>();
    private final java.util.Map<Integer,Button> unlockButtons=new java.util.LinkedHashMap<>();
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
        warning.setText("Prepare a real firmware-level CPU frequency Lock/Unlock candidate. " +
            "This tool NEVER flashes or reboots. Installation is a separate, currently disabled operation. " +
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
            LinearLayout row=new LinearLayout(this);
            Button lock=new Button(this);
            lock.setText("Prepare LOCK");
            lock.setEnabled(false);
            lockButtons.put(mhz,lock);
            lock.setOnClickListener(v->confirm(mhz,false));
            row.addView(lock,new LinearLayout.LayoutParams(0,-2,1));
            Button unlock=new Button(this);
            unlock.setText("Prepare UNLOCK");
            unlock.setEnabled(false);
            unlockButtons.put(mhz,unlock);
            unlock.setOnClickListener(v->confirm(mhz,true));
            row.addView(unlock,new LinearLayout.LayoutParams(0,-2,1));
            layout.addView(row);
        }
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
            info(r.ok()?r.output:"Firmware status unavailable:\n"+r.output);
            busy=false;
        });
    }
    private void confirm(int mhz,boolean unlock){
        if(busy)return;
        new AlertDialog.Builder(this)
            .setTitle("Prepare "+(unlock?"UNLOCK":"LOCK")+" "+mhz+" MHz?")
            .setMessage("Build a candidate vendor_boot image and save a recovery copy. " +
                "This does not install or activate the change. No reboot or partition writes.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Prepare",(d,w)->prepare(mhz,unlock))
            .show();
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
    private void prepare(int mhz,boolean unlock){
        if(busy)return;
        busy=true;
        info("Preparing "+mhz+" MHz "+(unlock?"unlock":"lock")+" candidate. NO FLASH.");
        io.execute(()->{
            String op=unlock?"unlock":"lock";
            String tx="tx-"+System.currentTimeMillis();
            RootBridge root=RootBridge.get();
            try{
                RootBridge.Result started=root.ctl("firmware start "+tx+" "+mhz+" "+op);
                if(!started.ok())throw new IllegalStateException("Preflight: "+started.output);
                String path=key(started.output,"original_dtb");
                if(!path.equals(ROOT+"/"+tx+"/work/dtb"))
                    throw new IllegalStateException("Unexpected extracted DTB path");
                byte[] original=readDtb(root,path);
                if(CpuOppDtbPatcher.inspect(original,mhz).enabled==unlock)
                    throw new IllegalStateException("Already "+(unlock?"unlocked":"locked"));
                byte[] changed=CpuOppDtbPatcher.patch(original,mhz,unlock);
                if(CpuOppDtbPatcher.inspect(changed,mhz).enabled!=unlock)
                    throw new IllegalStateException("OPP patch verification mismatch");
                writeDtb(root,path,changed);
                RootBridge.Result finished=root.ctl("firmware finish "+tx+" "+mhz+" "+op+" "+sha(changed));
                if(!finished.ok())throw new IllegalStateException("Repack rejected: "+finished.output);
                info("CANDIDATE READY — NOT INSTALLED\n"+finished.output+
                     "\nKeep the recovery image and manifest. Do not flash without independent verification.");
            }catch(Exception e){
                info("FAILED — NO FLASH:\n"+e.getMessage());
            }finally{busy=false;}
        });
    }
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
