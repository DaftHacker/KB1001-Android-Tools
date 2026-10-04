package com.dafthacker.kb1001perf;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;

public class CpuActivity extends Activity {
    private static final int GREEN=Color.rgb(77,210,126);
    private static final int GREEN_SOFT=Color.rgb(128,230,164);
    private static final int YELLOW=Color.rgb(255,202,64);
    private static final int ORANGE=Color.rgb(255,151,61);
    private static final int RED=Color.rgb(255,83,79);
    private static final int BLUE=Color.rgb(102,163,255);
    private static final int PURPLE=Color.rgb(190,112,255);
    private static final int TEXT=Color.rgb(236,244,242);
    private static final int MUTED=Color.rgb(154,173,171);

    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());

    private SparklineView overallGraph;
    private TextView overallValue;
    private TextView primeValue;
    private TextView modeValue;
    private TextView vfValue;
    private TextView ocStage1Value;
    private TextView ocStage2Value;
    private TextView a53OcValue;

    private ClusterUi efficiency;
    private ClusterUi performance;
    private ClusterUi prime;

    private boolean active;
    private boolean ocLoaded;
    private String pendingCpuMode;
    private String confirmedCpuMode;
    private final Map<String,Button> cpuModeButtons=new LinkedHashMap<>();
    private final Map<String,String> cpuModeButtonLabels=new LinkedHashMap<>();

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        TelemetryStore.ensureSnapshot(this);
        setContentView(buildUi());
        loadOcStatus();
        refresh();
    }

    private View buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_app);

        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){
                Insets bars=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
                top=bars.top;
                bottom=bars.bottom;
            }else{
                top=insets.getSystemWindowInsetTop();
                bottom=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(12),top+dp(10),dp(12),bottom+dp(10));
            return insets;
        });
        root.requestApplyInsets();

        LinearLayout top=row();
        Button back=button("← Back",GREEN,v->finish());
        back.setTextColor(Color.rgb(8,30,16));
        back.setBackground(tintedCard(GREEN,175));
        top.addView(back,new LinearLayout.LayoutParams(dp(86),dp(44)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8),0,0,0);
        titles.addView(text("A333 CPU Manager",26,TEXT,true));
        titles.addView(text("3 performance domains • 4× Cortex-A53 + 1× Cortex-A73",11,GREEN,true));
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top);

        ScrollView scroll=new ScrollView(this);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(10),0,dp(24));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        content.addView(liveCard(),full());

        section(content,"CPU PROFILE",
                "These modes use only frequencies and governors already exposed by the current kernel.",GREEN);

        content.addView(profileCard(
                "Firmware Stock",
                "Restores the exact per-policy governor, minimum and maximum captured at boot. On this firmware the stock governor is already performance.",
                "RESTORE FIRMWARE STOCK",
                GREEN,
                "stock",
                ()->applyCpuMode("cpu restore","Firmware Stock","stock")),full());

        content.addView(profileCard(
                "Balanced",
                "Uses schedutil with the normal firmware min/max range. This lets Android scale clocks down when full performance is not needed.",
                "APPLY BALANCED",
                BLUE,
                "balanced",
                ()->applyCpuMode("cpu balanced","Balanced","balanced")),full());

        content.addView(profileCard(
                "Locked Maximum",
                "Pins all three CPU policies to their current kernel-advertised maximum. Thermal cooling remains enabled. This is not an overclock.",
                "LOCK STOCK MAXIMUM",
                ORANGE,
                "performance",
                this::confirmLockedMaximum),full());

        section(content,"LIVE CPU DOMAINS",
                "Actual scheduler capacity, per-core load, frequency, governor, thermal zone and cooling state.",GREEN);

        efficiency=clusterCard(
                content,
                "Efficiency A53",
                "CPU0–1 • Cortex-A53",
                "policy0",
                new int[]{0,1},
                469,
                "cpul",
                "cpufreq-cpu0",
                GREEN_SOFT);

        performance=clusterCard(
                content,
                "Performance A53",
                "CPU2–3 • Cortex-A53",
                "policy2",
                new int[]{2,3},
                685,
                "cpum",
                "cpufreq-cpu2",
                BLUE);

        prime=clusterCard(
                content,
                "Prime A73",
                "CPU4 • Cortex-A73",
                "policy4",
                new int[]{4},
                1024,
                "cpub",
                "cpufreq-cpu4",
                ORANGE);

        section(content,"EXPERIMENTAL CPU OC",
                "Readiness for staged boot-time OPP work. No new CPU OPP or voltage is applied by this build.",PURPLE);

        LinearLayout oc=card(PURPLE);
        vfValue=text("Silicon profile • checking…",14,TEXT,true);
        ocStage1Value=text("A73 Stage 1 • 1560 MHz • checking…",11,MUTED,false);
        ocStage2Value=text("A73 Stage 2 • 1608 MHz • checking…",11,MUTED,false);
        a53OcValue=text("Fast A53 Stage 1 • 1776 MHz • checking…",11,MUTED,false);
        oc.addView(vfValue);
        addGap(oc,4);
        oc.addView(ocStage1Value);
        oc.addView(ocStage2Value);
        oc.addView(a53OcValue);

        TextView warning=text(
                "The current VF profile exposes 1200 / 1752 / 1512 MHz as its validated maxima. Higher OPPs remain locked until a separate boot-time DT/OPP patch is installed and validated.",
                10,MUTED,false);
        warning.setPadding(0,dp(8),0,0);
        oc.addView(warning);
        content.addView(oc,full());

        return root;
    }

    private View liveCard(){
        LinearLayout card=card(GREEN);

        LinearLayout head=row();
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("LIVE CPU",10,GREEN,true));
        overallValue=text("—",27,TEXT,true);
        labels.addView(overallValue);
        head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        modeValue=text("—",11,GREEN_SOFT,true);
        modeValue.setGravity(Gravity.END);
        head.addView(modeValue);
        card.addView(head);

        primeValue=text("Prime core • waiting for telemetry",11,MUTED,false);
        card.addView(primeValue);

        overallGraph=new SparklineView(this);
        overallGraph.setAccentColor(GREEN);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(104));
        gp.setMargins(0,dp(8),0,0);
        card.addView(overallGraph,gp);
        return card;
    }

    private ClusterUi clusterCard(
            LinearLayout parent,
            String title,
            String subtitle,
            String policyName,
            int[] cores,
            int knownCapacity,
            String thermalPrefix,
            String coolingType,
            int accent){

        LinearLayout card=card(accent);

        LinearLayout head=row();
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,16,TEXT,true));
        labels.addView(text(subtitle+" • capacity "+knownCapacity,9,MUTED,false));
        head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        TextView util=text("—%",20,accent,true);
        util.setGravity(Gravity.END);
        head.addView(util);
        card.addView(head);

        TextView coreUtil=text("cores • waiting",11,TEXT,true);
        TextView clock=text("clock • waiting",11,MUTED,false);
        TextView governor=text("governor • waiting",10,MUTED,false);
        TextView thermal=text("thermal • waiting",10,MUTED,false);
        TextView cooling=text("cooling • waiting",10,MUTED,false);

        coreUtil.setPadding(0,dp(7),0,0);
        card.addView(coreUtil);
        card.addView(clock);
        card.addView(governor);
        card.addView(thermal);
        card.addView(cooling);

        SparklineView graph=new SparklineView(this);
        graph.setAccentColor(accent);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(60));
        gp.setMargins(0,dp(6),0,0);
        card.addView(graph,gp);

        parent.addView(card,full());
        return new ClusterUi(policyName,cores,knownCapacity,thermalPrefix,coolingType,
                util,coreUtil,clock,governor,thermal,cooling,graph);
    }

    private View profileCard(
            String title,String subtitle,String action,int accent,String modeKey,Runnable run){
        LinearLayout card=card(accent);
        card.addView(text(title,15,TEXT,true));

        TextView detail=text(subtitle,10,MUTED,false);
        detail.setPadding(0,dp(3),0,dp(8));
        card.addView(detail);

        Button b=button(action,accent,v->run.run());
        cpuModeButtons.put(modeKey,b);
        cpuModeButtonLabels.put(modeKey,action);
        card.addView(b,new LinearLayout.LayoutParams(-1,dp(44)));
        return card;
    }

    private void confirmLockedMaximum(){
        new AlertDialog.Builder(this)
                .setTitle("Lock CPU at stock maximum?")
                .setMessage(
                        "This keeps each CPU domain at the highest frequency already validated and exposed by the current kernel. " +
                        "It is not an overclock. Kernel thermal protection remains enabled.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Apply",(d,w)->applyCpuMode("cpu performance","Locked Maximum","performance"))
                .show();
    }

    private void applyCpuMode(String command,String friendly,String modeKey){
        pendingCpuMode=modeKey;
        updateCpuButtons(TelemetryStore.get(TelemetryStore.read(this),"cpu_mode","stock"));

        Button target=cpuModeButtons.get(modeKey);
        if(target!=null)target.setText("REQUESTED…");
        if(modeValue!=null)modeValue.setText(friendly+" • requested");

        handler.postDelayed(()->{
            if(modeKey.equals(pendingCpuMode)){
                Button b=cpuModeButtons.get(modeKey);
                if(b!=null)b.setText("SWITCHING…");
                if(modeValue!=null)modeValue.setText(friendly+" • switching");
            }
        },140);

        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("cpu request "+modeKey);
            Map<String,String> state=parseKeyValue(r.output);
            boolean applied=r.ok() && "applied".equals(state.get("state")) &&
                    modeKey.equals(state.get("mode"));
            if(applied)RootBridge.get().ctl("logger refresh");

            runOnUiThread(()->{
                if(applied){
                    confirmedCpuMode=modeKey;
                    pendingCpuMode=null;
                    updateCpuButtons(modeKey);
                    if(modeValue!=null)modeValue.setText(friendlyMode(modeKey));
                }else{
                    pendingCpuMode=null;
                    confirmedCpuMode=null;
                    updateCpuButtons(TelemetryStore.get(TelemetryStore.read(this),"cpu_mode","stock"));
                    Toast.makeText(this,"CPU profile request failed",Toast.LENGTH_LONG).show();
                }
                handler.postDelayed(this::refresh,350);
            });
        });
    }

    private void updateCpuButtons(String currentMode){
        for(Map.Entry<String,Button> e:cpuModeButtons.entrySet()){
            String key=e.getKey();
            Button b=e.getValue();
            String normal=cpuModeButtonLabels.get(key);

            if(pendingCpuMode!=null&&pendingCpuMode.equals(key)){
                b.setEnabled(false);
                String now=b.getText().toString();
                if(!now.contains("REQUESTED")&&!now.contains("SWITCHING"))b.setText("SWITCHING…");
            }else{
                b.setEnabled(pendingCpuMode==null);
                b.setText(key.equals(currentMode)?"ACTIVE":normal);
            }
        }
    }

    private void loadOcStatus(){
        if(ocLoaded)return;
        ocLoaded=true;
        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("cpu oc-status");
            Map<String,String> values=parseKeyValue(r.output);
            runOnUiThread(()->renderOc(values,r.ok()));
        });
    }

    private void renderOc(Map<String,String> m,boolean ok){
        if(vfValue==null)return;
        if(!ok){
            vfValue.setText("OC readiness • unavailable");
            ocStage1Value.setText("A73 Stage 1 • status unavailable");
            ocStage2Value.setText("A73 Stage 2 • status unavailable");
            a53OcValue.setText("Fast A53 Stage 1 • status unavailable");
            return;
        }

        String vf=m.getOrDefault("vf_profile","unknown");
        vfValue.setText("Observed VF profile • "+vf.toUpperCase(Locale.US));
        vfValue.setTextColor("vf0403".equals(vf)?YELLOW:PURPLE);

        setOcLine(ocStage1Value,"A73 Stage 1","1560 MHz",m.get("a73_stage1_1560"));
        setOcLine(ocStage2Value,"A73 Stage 2","1608 MHz",m.get("a73_stage2_1608"));
        setOcLine(a53OcValue,"Fast A53 Stage 1","1776 MHz",m.get("a53_stage1_1776"));
    }

    private void setOcLine(TextView view,String label,String clock,String state){
        boolean available="available".equals(state);
        view.setText(label+" • "+clock+" • "+(available?"OPP PRESENT / APPLY LOCKED":"BOOT OPP REQUIRED"));
        view.setTextColor(available?YELLOW:MUTED);
    }

    private void refresh(){
        Map<String,String> telemetry=TelemetryStore.read(this);

        int overall=clamp(parseInt(TelemetryStore.get(telemetry,"cpu_util_pct","0")));
        Map<Integer,Integer> util=parseCoreMap(TelemetryStore.get(telemetry,"cpu_core_util",""));
        Map<Integer,Integer> capacity=parseCoreMap(TelemetryStore.get(telemetry,"cpu_core_capacity",""));
        Map<String,PolicyState> policies=parsePolicies(TelemetryStore.get(telemetry,"cpu_policies",""));
        Map<String,String> thermals=parseDelimited(TelemetryStore.get(telemetry,"thermal_zones",""));
        Map<String,String> cooling=parseDelimited(TelemetryStore.get(telemetry,"cooling_devices",""));

        String telemetryCpuMode=TelemetryStore.get(telemetry,"cpu_mode","stock");
        if(confirmedCpuMode!=null && confirmedCpuMode.equals(telemetryCpuMode)){
            confirmedCpuMode=null;
        }
        String cpuMode=confirmedCpuMode!=null?confirmedCpuMode:telemetryCpuMode;
        updateCpuButtons(cpuMode);

        PolicyState primePolicy=policies.get("policy4");
        int primeUtil=util.getOrDefault(4,0);
        int primeClock=primePolicy==null?0:primePolicy.current;

        overallValue.setText(overall+"%");
        modeValue.setText(friendlyMode(cpuMode));
        primeValue.setText("Prime A73 • "+primeUtil+"% • "+primeClock+" MHz");
        overallGraph.addValue(overall);

        updateCluster(efficiency,util,capacity,policies,thermals,cooling);
        updateCluster(performance,util,capacity,policies,thermals,cooling);
        updateCluster(prime,util,capacity,policies,thermals,cooling);

    }

    private void updateCluster(
            ClusterUi ui,
            Map<Integer,Integer> util,
            Map<Integer,Integer> capacity,
            Map<String,PolicyState> policies,
            Map<String,String> thermals,
            Map<String,String> cooling){

        int sum=0;
        StringBuilder coreText=new StringBuilder();
        for(int core:ui.cores){
            int u=util.getOrDefault(core,0);
            sum+=u;
            if(coreText.length()>0) coreText.append(" • ");
            coreText.append("CPU").append(core).append(" ").append(u).append("%");
        }
        int avg=ui.cores.length==0?0:sum/ui.cores.length;

        ui.util.setText(avg+"%");
        ui.coreUtil.setText(coreText.toString());

        PolicyState p=policies.get(ui.policy);
        if(p!=null){
            ui.clock.setText("Clock • "+p.current+" MHz / "+p.max+" MHz max • min "+p.min+" MHz");
            ui.governor.setText("Governor • "+p.governor);
        }else{
            ui.clock.setText("Clock • unavailable");
            ui.governor.setText("Governor • unavailable");
        }

        int measuredCapacity=0;
        for(int core:ui.cores) measuredCapacity=Math.max(measuredCapacity,capacity.getOrDefault(core,0));
        String temp=findThermal(thermals,ui.thermalPrefix);
        ui.thermal.setText("Thermal • "+temp+
                (measuredCapacity>0 ? " • scheduler capacity "+measuredCapacity : ""));

        String cool=findCooling(cooling,ui.coolingType);
        ui.cooling.setText("Cooling • "+(cool==null?"not reported":cool));
        ui.cooling.setTextColor(cool!=null && !cool.startsWith("0/")?RED:MUTED);
        ui.graph.addValue(avg);
    }

    private String friendlyMode(String mode){
        if("balanced".equals(mode))return "Balanced";
        if("performance".equals(mode))return "Locked Maximum";
        return "Firmware Stock";
    }

    private Map<String,String> parseKeyValue(String raw){
        Map<String,String> out=new LinkedHashMap<>();
        if(raw==null)return out;
        for(String line:raw.split("\\R")){
            int eq=line.indexOf('=');
            if(eq>0)out.put(line.substring(0,eq).trim(),line.substring(eq+1).trim());
        }
        return out;
    }

    private Map<Integer,Integer> parseCoreMap(String raw){
        Map<Integer,Integer> out=new LinkedHashMap<>();
        if(raw==null)return out;
        for(String item:raw.split(";")){
            int colon=item.indexOf(':');
            if(colon<1)continue;
            String left=item.substring(0,colon).replaceAll("[^0-9]","");
            if(left.isEmpty())continue;
            try{
                out.put(Integer.parseInt(left),Integer.parseInt(item.substring(colon+1).replaceAll("[^0-9-]","")));
            }catch(Exception ignored){}
        }
        return out;
    }

    private Map<String,PolicyState> parsePolicies(String raw){
        Map<String,PolicyState> out=new LinkedHashMap<>();
        if(raw==null)return out;

        for(String item:raw.split(";")){
            int eq=item.indexOf('=');
            int bracket=item.indexOf('[');
            int slash=item.indexOf('/',eq+1);
            int dash=item.indexOf('-',slash+1);
            int at=item.indexOf('@',dash+1);
            if(eq<0||slash<0||dash<0||at<0)continue;

            String name=(bracket>0?item.substring(0,bracket):item.substring(0,eq)).trim();
            try{
                int current=Integer.parseInt(item.substring(eq+1,slash).replaceAll("[^0-9]",""));
                int min=Integer.parseInt(item.substring(slash+1,dash).replaceAll("[^0-9]",""));
                int max=Integer.parseInt(item.substring(dash+1,at).replaceAll("[^0-9]",""));
                String gov=item.substring(at+1).trim();
                out.put(name,new PolicyState(current,min,max,gov));
            }catch(Exception ignored){}
        }
        return out;
    }

    private Map<String,String> parseDelimited(String raw){
        Map<String,String> out=new LinkedHashMap<>();
        if(raw==null)return out;
        for(String item:raw.split(";")){
            int colon=item.indexOf(':');
            if(colon>0)out.put(item.substring(0,colon),item.substring(colon+1));
        }
        return out;
    }

    private String findThermal(Map<String,String> map,String prefix){
        for(Map.Entry<String,String> e:map.entrySet()){
            if(e.getKey().toLowerCase(Locale.US).contains(prefix.toLowerCase(Locale.US))){
                return e.getValue();
            }
        }
        return "—";
    }

    private String findCooling(Map<String,String> map,String type){
        for(Map.Entry<String,String> e:map.entrySet()){
            if(e.getKey().equals(type)||e.getKey().contains(type)) return e.getValue();
        }
        return null;
    }

    private void section(LinearLayout content,String title,String subtitle,int color){
        TextView t=text(title,14,color,true);
        t.setPadding(dp(2),dp(16),0,dp(2));
        content.addView(t);

        TextView s=text(subtitle,10,MUTED,false);
        s.setPadding(dp(2),0,0,dp(7));
        content.addView(s);
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

    private void addGap(LinearLayout l,int px){
        Space s=new Space(this);
        l.addView(s,new LinearLayout.LayoutParams(1,dp(px)));
    }

    private LinearLayout.LayoutParams full(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(4),0,dp(4));
        return p;
    }

    private int clamp(int v){return Math.max(0,Math.min(100,v));}
    private int parseInt(String s){try{return Integer.parseInt(s.trim());}catch(Exception e){return 0;}}
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}

    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(!active)return;
            refresh();
            handler.postDelayed(this,1000);
        }
    };

    @Override protected void onResume(){
        super.onResume();
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

    private static final class PolicyState{
        final int current,min,max;
        final String governor;
        PolicyState(int current,int min,int max,String governor){
            this.current=current;
            this.min=min;
            this.max=max;
            this.governor=governor;
        }
    }

    private static final class ClusterUi{
        final String policy;
        final int[] cores;
        final int knownCapacity;
        final String thermalPrefix;
        final String coolingType;
        final TextView util;
        final TextView coreUtil;
        final TextView clock;
        final TextView governor;
        final TextView thermal;
        final TextView cooling;
        final SparklineView graph;

        ClusterUi(
                String policy,int[] cores,int knownCapacity,String thermalPrefix,String coolingType,
                TextView util,TextView coreUtil,TextView clock,TextView governor,
                TextView thermal,TextView cooling,SparklineView graph){
            this.policy=policy;
            this.cores=cores;
            this.knownCapacity=knownCapacity;
            this.thermalPrefix=thermalPrefix;
            this.coolingType=coolingType;
            this.util=util;
            this.coreUtil=coreUtil;
            this.clock=clock;
            this.governor=governor;
            this.thermal=thermal;
            this.cooling=cooling;
            this.graph=graph;
        }
    }
}
