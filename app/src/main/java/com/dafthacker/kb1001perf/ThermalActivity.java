package com.dafthacker.kb1001perf;

import android.app.*;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;

public class ThermalActivity extends Activity {
    private static final int CYAN=Color.rgb(68,205,220);
    private static final int GREEN=Color.rgb(77,210,126);
    private static final int YELLOW=Color.rgb(255,202,64);
    private static final int ORANGE=Color.rgb(255,151,61);
    private static final int RED=Color.rgb(255,83,79);
    private static final int TEXT=Color.rgb(236,244,242);
    private static final int MUTED=Color.rgb(154,173,171);

    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());

    private TextView modeValue;
    private TextView cpuTempValue;
    private TextView gpuTempValue;
    private TextView batteryTempValue;
    private TextView coolingValue;
    private TextView androidThresholdsValue;

    private ThermalControl cpuTarget;
    private ThermalControl cpuThrottle;
    private ThermalControl gpuTarget;
    private ThermalControl gpuThrottle;
    private ThermalControl idleThrottle;

    private boolean active;
    private long lastStatusRefresh;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        TelemetryStore.ensureSnapshot(this);
        setContentView(buildUi());
        refreshStatus();
    }

    private View buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_app);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){
                Insets bars=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
                top=bars.top; bottom=bars.bottom;
            }else{
                top=insets.getSystemWindowInsetTop();
                bottom=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(12),top+dp(10),dp(12),bottom+dp(10));
            return insets;
        });
        root.requestApplyInsets();

        LinearLayout top=row();
        Button back=button("← Back",CYAN,v->finish());
        top.addView(back,new LinearLayout.LayoutParams(dp(86),dp(44)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8),0,0,0);
        titles.addView(text("A333 Thermal Manager",26,TEXT,true));
        titles.addView(text("Kernel trip points • Android thermal status • cooling state",11,CYAN,true));
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top);

        ScrollView scroll=new ScrollView(this);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(10),0,dp(24));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        content.addView(liveCard(),full());

        section(content,"KERNEL THERMAL POLICY",
                "Only trip temperatures verified writable by recon are exposed. Hysteresis is read-only and critical shutdown trips remain locked.",CYAN);

        cpuTarget=controlCard(content,
                "CPU passive target",
                "Applied to cpul/cpum/cpub trip 0. Verified stock value: 70 °C.",
                "cpu-target",65,85,CYAN);
        cpuThrottle=controlCard(content,
                "CPU frequency throttle",
                "Applied to cpul/cpum/cpub trip 1. cpufreq cooling begins here. Stock: 90 °C.",
                "cpu-throttle",80,100,ORANGE);
        gpuTarget=controlCard(content,
                "GPU passive target",
                "GPU devfreq thermal trip 0. Stock: 70 °C.",
                "gpu-target",65,85,CYAN);
        gpuThrottle=controlCard(content,
                "GPU frequency throttle",
                "GPU devfreq thermal trip 1. Stock: 90 °C.",
                "gpu-throttle",80,100,ORANGE);
        idleThrottle=controlCard(content,
                "Fast-core idle cooling",
                "cpum/cpub idle cooling trip 1. Stock: 100 °C.",
                "idle-throttle",95,105,YELLOW);

        LinearLayout locked=card(RED);
        locked.addView(text("LOCKED SAFETY LIMITS",12,RED,true));
        locked.addView(text(
                "CPU/GPU critical shutdown: 110 °C\nSkin critical trip: 50 °C\nCritical trip writes are intentionally not implemented.",
                11,TEXT,false));
        content.addView(locked,full());

        Button restore=button("RESTORE STOCK THERMAL POLICY",GREEN,v->confirmRestore());
        LinearLayout restoreCard=card(GREEN);
        restoreCard.addView(restore,new LinearLayout.LayoutParams(-1,dp(46)));
        content.addView(restoreCard,full());

        section(content,"ANDROID THERMAL FRAMEWORK",
                "Framework severity thresholds are separate from the kernel cooling trips above.",YELLOW);
        LinearLayout framework=card(YELLOW);
        androidThresholdsValue=text("Framework thresholds • loading…",11,TEXT,true);
        framework.addView(androidThresholdsValue);
        framework.addView(text(
                "These framework thresholds are displayed for diagnostics only; this build does not rewrite Thermal HAL policy.",
                10,MUTED,false));
        content.addView(framework,full());

        return root;
    }

    private View liveCard(){
        LinearLayout card=card(CYAN);
        LinearLayout head=row();
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("LIVE THERMALS",10,CYAN,true));
        cpuTempValue=text("CPU • —",23,TEXT,true);
        labels.addView(cpuTempValue);
        head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        modeValue=text("—",11,CYAN,true);
        modeValue.setGravity(Gravity.END);
        head.addView(modeValue);
        card.addView(head);

        gpuTempValue=text("GPU • —",12,TEXT,true);
        batteryTempValue=text("Battery / USB • —",11,MUTED,false);
        coolingValue=text("Cooling • —",10,MUTED,false);
        gpuTempValue.setPadding(0,dp(7),0,0);
        card.addView(gpuTempValue);
        card.addView(batteryTempValue);
        card.addView(coolingValue);
        return card;
    }

    private ThermalControl controlCard(
            LinearLayout parent,String title,String subtitle,String command,int min,int max,int accent){
        LinearLayout card=card(accent);
        card.addView(text(title,15,TEXT,true));
        TextView detail=text(subtitle,10,MUTED,false);
        detail.setPadding(0,dp(2),0,dp(8));
        card.addView(detail);

        LinearLayout row=row();
        Button minus=button("−",accent,v->{});
        TextView value=text("— °C",20,accent,true);
        value.setGravity(Gravity.CENTER);
        Button plus=button("+",accent,v->{});
        row.addView(minus,new LinearLayout.LayoutParams(dp(54),dp(44)));
        row.addView(value,new LinearLayout.LayoutParams(0,dp(44),1));
        row.addView(plus,new LinearLayout.LayoutParams(dp(54),dp(44)));
        card.addView(row);

        Button apply=button("APPLY",accent,v->{});
        apply.setEnabled(false);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(44));
        ap.setMargins(0,dp(8),0,0);
        card.addView(apply,ap);
        parent.addView(card,full());

        ThermalControl control=new ThermalControl(command,min,max,value,minus,plus,apply);
        minus.setOnClickListener(v->control.step(-1));
        plus.setOnClickListener(v->control.step(1));
        apply.setOnClickListener(v->confirmApply(control,title));
        return control;
    }

    private void confirmApply(ThermalControl control,String title){
        if(control.current<=0)return;
        String warning=control.current>control.loaded
                ? "\n\nYou are raising a thermal threshold above its current value. This can increase sustained temperature."
                : "";
        new AlertDialog.Builder(this)
                .setTitle("Apply "+title+"?")
                .setMessage("Set this verified non-critical trip to "+control.current+" °C?"+warning+
                        "\n\nCritical shutdown protection remains unchanged.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Apply",(d,w)->applyThermal(control))
                .show();
    }

    private void applyThermal(ThermalControl control){
        control.apply.setEnabled(false);
        control.apply.setText("APPLYING…");
        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("thermal set "+control.command+" "+control.current);
            if(r.ok())RootBridge.get().ctl("logger refresh");
            runOnUiThread(()->{
                control.apply.setText("APPLY");
                if(r.ok()) control.dirty=false;
                if(!r.ok()){
                    Toast.makeText(this,
                            r.exitCode==2?"Requested thermal value violates the safe UI bounds or trip ordering.":"Thermal write failed.",
                            Toast.LENGTH_LONG).show();
                }
                refreshStatus();
            });
        });
    }

    private void confirmRestore(){
        new AlertDialog.Builder(this)
                .setTitle("Restore stock thermal policy?")
                .setMessage("Restore the non-critical thermal trip temperatures captured from this boot? Critical trips are not modified.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Restore",(d,w)->io.execute(()->{
                    RootBridge.Result r=RootBridge.get().ctl("thermal restore");
                    if(r.ok())RootBridge.get().ctl("logger refresh");
                    runOnUiThread(()->{
                        if(r.ok()){
                            cpuTarget.dirty=false;
                            cpuThrottle.dirty=false;
                            gpuTarget.dirty=false;
                            gpuThrottle.dirty=false;
                            idleThrottle.dirty=false;
                        }
                        Toast.makeText(this,r.ok()?"Stock thermal policy restored.":"Thermal restore failed.",Toast.LENGTH_LONG).show();
                        refreshStatus();
                    });
                }))
                .show();
    }

    private void refreshStatus(){
        lastStatusRefresh=SystemClock.elapsedRealtime();
        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("thermal status");
            Map<String,String> m=parseEquals(r.output);
            runOnUiThread(()->{
                if(!r.ok()){
                    if(modeValue!=null)modeValue.setText("backend unavailable");
                    return;
                }
                renderStatus(m);
            });
        });
    }

    private void renderStatus(Map<String,String> m){
        String mode=m.getOrDefault("mode","stock");
        modeValue.setText("custom".equals(mode)?"Custom":"Stock");

        cpuTarget.load(celsius(m.get("cpul_trip0_millic")));
        cpuThrottle.load(celsius(m.get("cpul_trip1_millic")));
        gpuTarget.load(celsius(m.get("gpu_trip0_millic")));
        gpuThrottle.load(celsius(m.get("gpu_trip1_millic")));
        int idle=celsius(m.get("cpub_idle_trip1_millic"));
        if(idle<=0)idle=celsius(m.get("cpum_idle_trip1_millic"));
        idleThrottle.load(idle);

        String framework=m.getOrDefault("android_hot_thresholds_c","75,80,85,100,105,110");
        androidThresholdsValue.setText("Thermal HAL hot severity thresholds • "+framework.replace(",", " / ")+" °C");
    }

    private void renderTelemetry(){
        Map<String,String> t=TelemetryStore.read(this);
        Map<String,String> zones=parseDelimited(TelemetryStore.get(t,"thermal_zones",""));
        Map<String,String> cooling=parseDelimited(TelemetryStore.get(t,"cooling_devices",""));

        String cpul=find(zones,"cpul_thermal_zone");
        String cpum=find(zones,"cpum_thermal_zone");
        String cpub=find(zones,"cpub_thermal_zone");
        String gpu=find(zones,"gpu_thermal_zone");
        String batt=find(zones,"axp2202-battery");
        String usb=find(zones,"axp2202-usb");

        cpuTempValue.setText("CPU • L "+clean(cpul)+" • M "+clean(cpum)+" • A73 "+clean(cpub));
        gpuTempValue.setText("GPU • "+clean(gpu));
        batteryTempValue.setText("Battery "+clean(batt)+" • USB "+clean(usb));

        List<String> activeCooling=new ArrayList<>();
        for(Map.Entry<String,String> e:cooling.entrySet()){
            String type=e.getKey();
            if(!(type.startsWith("cpufreq-")||type.startsWith("devfreq-")||type.startsWith("idle-cpu")))continue;
            String value=e.getValue();
            if(value!=null&&!value.startsWith("0/"))activeCooling.add(type+" "+value);
        }
        coolingValue.setText(activeCooling.isEmpty()?"Cooling • no CPU/GPU throttle active":
                "Cooling • "+android.text.TextUtils.join(" • ",activeCooling));
        coolingValue.setTextColor(activeCooling.isEmpty()?MUTED:RED);
    }

    private Map<String,String> parseEquals(String raw){
        Map<String,String> out=new LinkedHashMap<>();
        if(raw==null)return out;
        for(String line:raw.split("\\R")){
            int i=line.indexOf('=');
            if(i>0)out.put(line.substring(0,i).trim(),line.substring(i+1).trim());
        }
        return out;
    }

    private Map<String,String> parseDelimited(String raw){
        Map<String,String> out=new LinkedHashMap<>();
        if(raw==null)return out;
        for(String item:raw.split(";")){
            int i=item.indexOf(':');
            if(i>0)out.put(item.substring(0,i),item.substring(i+1));
        }
        return out;
    }

    private String find(Map<String,String> m,String key){
        String v=m.get(key);
        return v==null?"—":v;
    }

    private String clean(String v){
        if(v==null||v.isEmpty())return "—";
        return v.replace("C"," °C");
    }

    private int celsius(String raw){
        try{return Integer.parseInt(raw)/1000;}catch(Exception e){return 0;}
    }

    private void section(LinearLayout parent,String title,String subtitle,int accent){
        TextView t=text(title,14,accent,true);
        t.setPadding(dp(2),dp(16),0,dp(2));
        parent.addView(t);
        TextView s=text(subtitle,10,MUTED,false);
        s.setPadding(dp(2),0,0,dp(7));
        parent.addView(s);
    }

    private LinearLayout card(int accent){
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(13),dp(12),dp(13),dp(12));
        c.setBackground(tintedCard(accent,52));
        return c;
    }

    private LinearLayout row(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private Button button(String label,int accent,View.OnClickListener listener){
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackground(tintedCard(accent,115));
        b.setOnClickListener(listener);
        return b;
    }

    private TextView text(String value,int sp,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    private GradientDrawable tintedCard(int color,int alpha){
        GradientDrawable g=new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Color.rgb(11,18,19),
                        Color.argb(alpha,Color.red(color),Color.green(color),Color.blue(color)),
                        Color.rgb(8,14,15)
                });
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1),Color.argb(110,Color.red(color),Color.green(color),Color.blue(color)));
        return g;
    }

    private LinearLayout.LayoutParams full(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(4),0,dp(4));
        return p;
    }

    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}

    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(!active)return;
            renderTelemetry();
            if(SystemClock.elapsedRealtime()-lastStatusRefresh>=5000)refreshStatus();
            handler.postDelayed(this,1000);
        }
    };

    @Override protected void onResume(){
        super.onResume();
        DisplaySleepPolicy.apply(this,false);
        TelemetryDemand.activityResumed();
        active=true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override protected void onPause(){
        active=false;
        handler.removeCallbacks(ticker);
        TelemetryDemand.activityPaused();
        super.onPause();
    }

    @Override protected void onDestroy(){
        io.shutdownNow();
        super.onDestroy();
    }

    private static final class ThermalControl{
        final String command;
        final int min,max;
        final TextView value;
        final Button minus,plus,apply;
        int current;
        int loaded;
        boolean dirty;

        ThermalControl(String command,int min,int max,TextView value,Button minus,Button plus,Button apply){
            this.command=command;
            this.min=min;
            this.max=max;
            this.value=value;
            this.minus=minus;
            this.plus=plus;
            this.apply=apply;
        }

        void load(int c){
            if(c<=0)return;
            loaded=c;
            if(!dirty) current=c;
            render();
        }

        void step(int delta){
            if(current<=0)return;
            current=Math.max(min,Math.min(max,current+delta));
            dirty=current!=loaded;
            render();
        }

        void render(){
            value.setText(current+" °C");
            minus.setEnabled(current>min);
            plus.setEnabled(current<max);
            apply.setEnabled(current>0 && current!=loaded);
        }
    }
}
