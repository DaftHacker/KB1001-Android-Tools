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
    private TextView governorValue;
    private TextView primeModeValue;
    private TextView defaultModeValue;
    private TextView vfValue;
    private TextView vfCodeValue;
    private TextView oppMapValue;
    private TextView bootCarrierValue;
    private TextView ocRuntimeValue;
    private TextView ocStage1Value;
    private TextView ocStage2Value;
    private TextView a53OcValue;
    private Button ocDynamicButton;
    private Button ocPerformanceButton;
    private Button ocDynamic1608Button;
    private Button ocPerformance1608Button;
    private Button ocDynamic1776Button;
    private Button ocPerformance1776Button;
    private Button ocDisableButton;

    private ClusterUi efficiency;
    private ClusterUi performance;
    private ClusterUi prime;

    private boolean active;
    private boolean ocLoaded;
    private String pendingCpuMode;
    private String confirmedCpuMode;
    private int confirmedTelemetryMatches;
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

        section(content,"CPU OVERCLOCK",
                "Validated vendor_boot turbo OPPs: CPU4 1560/1608 MHz and CPU2-3 1776 MHz. Generic MIN/MAX controls remain stock-only.",PURPLE);

        LinearLayout oc=card(PURPLE);
        vfValue=text("Silicon profile • checking…",14,TEXT,true);
        vfCodeValue=text("VF selector • checking…",10,MUTED,false);
        oppMapValue=text("Kernel OPP map • checking…",10,MUTED,false);
        bootCarrierValue=text("Boot OPP carrier • checking…",10,MUTED,false);
        ocRuntimeValue=text("Live OC • waiting for telemetry",11,TEXT,true);
        ocStage1Value=text("A73 Stage 1 • 1560 MHz • checking…",11,MUTED,false);
        ocStage2Value=text("A73 Stage 2 • 1608 MHz • checking…",11,MUTED,false);
        a53OcValue=text("Fast A53 Stage 1 • 1776 MHz • checking…",11,MUTED,false);
        oc.addView(vfValue);
        oc.addView(vfCodeValue);
        oc.addView(oppMapValue);
        oc.addView(bootCarrierValue);
        ocRuntimeValue.setPadding(0,dp(6),0,0);
        oc.addView(ocRuntimeValue);
        addGap(oc,4);
        oc.addView(ocStage1Value);
        oc.addView(ocStage2Value);
        oc.addView(a53OcValue);

        LinearLayout ocButtons=row();
        ocDynamicButton=button("DYNAMIC 1560",PURPLE,v->confirmOcMode("dynamic1560"));
        ocPerformanceButton=button("PERFORMANCE 1560",ORANGE,v->confirmOcMode("performance1560"));
        LinearLayout.LayoutParams ocBp=new LinearLayout.LayoutParams(0,dp(44),1);
        ocBp.setMargins(dp(2),dp(8),dp(2),0);
        ocButtons.addView(ocDynamicButton,ocBp);
        ocButtons.addView(ocPerformanceButton,ocBp);
        oc.addView(ocButtons);

        LinearLayout oc1608Buttons=row();
        ocDynamic1608Button=button("DYNAMIC 1608",PURPLE,v->confirmOcMode("dynamic1608"));
        ocPerformance1608Button=button("PERFORMANCE 1608",ORANGE,v->confirmOcMode("performance1608"));
        LinearLayout.LayoutParams oc1608Bp=new LinearLayout.LayoutParams(0,dp(44),1);
        oc1608Bp.setMargins(dp(2),dp(6),dp(2),0);
        oc1608Buttons.addView(ocDynamic1608Button,oc1608Bp);
        oc1608Buttons.addView(ocPerformance1608Button,oc1608Bp);
        oc.addView(oc1608Buttons);

        LinearLayout oc1776Buttons=row();
        ocDynamic1776Button=button("DYNAMIC 1776",PURPLE,v->confirmOcMode("dynamic1776"));
        ocPerformance1776Button=button("PERFORMANCE 1776",ORANGE,v->confirmOcMode("performance1776"));
        LinearLayout.LayoutParams oc1776Bp=new LinearLayout.LayoutParams(0,dp(44),1);
        oc1776Bp.setMargins(dp(2),dp(6),dp(2),0);
        oc1776Buttons.addView(ocDynamic1776Button,oc1776Bp);
        oc1776Buttons.addView(ocPerformance1776Button,oc1776Bp);
        oc.addView(oc1776Buttons);

        ocDisableButton=button("DISABLE CPU OC / RESTORE STOCK",GREEN,v->applyOcMode("off"));
        LinearLayout.LayoutParams ocOff=new LinearLayout.LayoutParams(-1,dp(44));
        ocOff.setMargins(dp(2),dp(6),dp(2),0);
        oc.addView(ocDisableButton,ocOff);

        TextView warning=text(
                "CPU4 1560/1608 MHz and CPU2-3 1776 MHz at 1.15 V passed staged boot, transition, idle, and short pinned-load validation. The Linux boost switch is global, so the backend holds all policies low across boost transitions and re-clamps non-target clusters before applying an OC.",
                10,MUTED,false);
        warning.setPadding(0,dp(8),0,0);
        oc.addView(warning);
        content.addView(oc,full());

        return root;
    }

    private View liveCard(){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15),dp(13),dp(15),dp(13));
        card.setBackground(blackAccentCard(GREEN));

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

        TextView overallGraphLabel=text("UTILIZATION HISTORY",9,MUTED,true);
        overallGraphLabel.setPadding(0,dp(7),0,0);
        card.addView(overallGraphLabel);

        overallGraph=new SparklineView(this);
        overallGraph.setAccentColor(GREEN);
        overallGraph.setScaleMax(100f);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(104));
        gp.setMargins(0,dp(8),0,dp(8));
        card.addView(overallGraph,gp);

        LinearLayout info=new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        governorValue=miniStat(info,"GOVERNOR","—");
        primeModeValue=miniStat(info,"PRIME","—");
        defaultModeValue=miniStat(info,"DEFAULT","—");
        card.addView(info);
        return card;
    }

    private TextView miniStat(LinearLayout parent,String label,String initial){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4),0,dp(4),0);
        TextView l=text(label,8,MUTED,true);
        TextView v=text(initial,11,TEXT,true);
        box.addView(l);
        box.addView(v);
        parent.addView(box,new LinearLayout.LayoutParams(0,-2,1));
        return v;
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

        LinearLayout controls=row();
        Button minButton=button("MIN",accent,v->chooseCpuFrequency(policyName,false));
        Button maxButton=button("MAX",accent,v->chooseCpuFrequency(policyName,true));
        Button govButton=button("GOV",accent,v->chooseCpuGovernor(policyName));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(42),1);
        cp.setMargins(dp(2),dp(7),dp(2),0);
        controls.addView(minButton,cp);
        controls.addView(maxButton,cp);
        controls.addView(govButton,cp);
        card.addView(controls);

        TextView graphLabel=text("CLOCK HISTORY • waiting",9,MUTED,true);
        graphLabel.setPadding(0,dp(7),0,0);
        card.addView(graphLabel);

        SparklineView graph=new SparklineView(this);
        graph.setAccentColor(accent);
        graph.setScaleMax(2000f);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(60));
        gp.setMargins(0,dp(6),0,0);
        card.addView(graph,gp);

        parent.addView(card,full());
        return new ClusterUi(policyName,cores,knownCapacity,thermalPrefix,coolingType,
                util,coreUtil,clock,governor,thermal,cooling,graphLabel,graph);
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

            runOnUiThread(()->{
                if(applied){
                    confirmedCpuMode=modeKey;
                    confirmedTelemetryMatches=0;
                    pendingCpuMode=null;
                    updateCpuButtons(modeKey);
                    if(modeValue!=null)modeValue.setText(friendlyMode(modeKey));
                }else{
                    pendingCpuMode=null;
                    confirmedCpuMode=null;
                    confirmedTelemetryMatches=0;
                    updateCpuButtons(TelemetryStore.get(TelemetryStore.read(this),"cpu_mode","stock"));
                    Toast.makeText(this,"CPU profile request failed",Toast.LENGTH_LONG).show();
                }
                handler.postDelayed(this::refresh,350);
            });

            // Refresh telemetry after the UI has accepted the confirmed backend state.
            // confirmedCpuMode prevents stale telemetry from visually reverting the button.
            if(applied)RootBridge.get().ctl("logger refresh");
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
            vfCodeValue.setText("VF selector • unavailable");
            oppMapValue.setText("Kernel OPP map • unavailable");
            bootCarrierValue.setText("Boot OPP carrier • unavailable");
            ocStage1Value.setText("A73 Stage 1 • status unavailable");
            ocStage2Value.setText("A73 Stage 2 • status unavailable");
            a53OcValue.setText("Fast A53 Stage 1 • status unavailable");
            if(ocDynamicButton!=null)ocDynamicButton.setEnabled(false);
            if(ocPerformanceButton!=null)ocPerformanceButton.setEnabled(false);
            if(ocDynamic1608Button!=null)ocDynamic1608Button.setEnabled(false);
            if(ocPerformance1608Button!=null)ocPerformance1608Button.setEnabled(false);
            if(ocDynamic1776Button!=null)ocDynamic1776Button.setEnabled(false);
            if(ocPerformance1776Button!=null)ocPerformance1776Button.setEnabled(false);
            if(ocDisableButton!=null)ocDisableButton.setEnabled(true);
            return;
        }

        String vf=m.getOrDefault("vf_profile","unknown");
        vfValue.setText("Observed VF profile • "+vf.toUpperCase(Locale.US));
        vfValue.setTextColor("vf0403".equals(vf)?YELLOW:PURPLE);

        String vfVersion=m.getOrDefault("vf_version","—");
        String dvfsCode=m.getOrDefault("dvfs_code","—");
        vfCodeValue.setText("VF version "+vfVersion+" • DVFS code "+dvfsCode);

        int p0=countOpps(m.get("policy0_opp_map"));
        int p2=countOpps(m.get("policy2_opp_map"));
        int p4=countOpps(m.get("policy4_opp_map"));
        int gpu=countOpps(m.get("gpu_opp_map"));
        oppMapValue.setText("Kernel OPP entries • P0 "+p0+" • P2 "+p2+" • P4 "+p4+" • GPU "+gpu);

        String carrier=m.getOrDefault("boot_opp_carrier","unknown");
        String carrierState=m.getOrDefault("vendor_boot_state","unknown");
        String blocker=m.getOrDefault("boot_opp_install_blocker","unknown");
        boolean patched1776="verified_cpu2_1776_patch".equals(carrierState);
        boolean patched1608="verified_cpu4_1608_patch".equals(carrierState);
        boolean patched1560="verified_cpu4_1560_patch".equals(carrierState);
        boolean patched=patched1776||patched1608||patched1560;
        boolean stock="verified_stock".equals(carrierState);
        String installState;
        if("vendor_boot_patcher_not_implemented".equals(blocker)){
            installState="patcher pending";
        }else if("none".equals(blocker)){
            installState="install path ready";
        }else{
            installState="install blocked";
        }
        String carrierLabel=patched1776?"VALIDATED STAGE 8 / 1776 PATCH":
                (patched1608?"VALIDATED 1608 PATCH":
                (patched1560?"VALIDATED 1560 PATCH":(stock?"VERIFIED STOCK":"UNVERIFIED")));
        bootCarrierValue.setText(
                "Boot OPP carrier • "+carrier+
                " • "+carrierLabel+
                " • "+installState);
        bootCarrierValue.setTextColor(patched?GREEN:(stock?YELLOW:MUTED));

        setOcLine(ocStage1Value,"A73 Stage 1","1560 MHz",m.get("a73_stage1_1560"));
        setOcLine(ocStage2Value,"A73 Stage 2","1608 MHz",m.get("a73_stage2_1608"));
        setOcLine(a53OcValue,"Fast A53 Stage 1","1776 MHz",m.get("a53_stage1_1776"));

        boolean applySupported="1".equals(m.get("oc_apply_supported"));
        boolean apply1608Supported="1".equals(m.get("oc_1608_apply_supported"));
        boolean apply1776Supported="1".equals(m.get("oc_1776_apply_supported"));
        if(ocDynamicButton!=null)ocDynamicButton.setEnabled(applySupported);
        if(ocPerformanceButton!=null)ocPerformanceButton.setEnabled(applySupported);
        if(ocDynamic1608Button!=null)ocDynamic1608Button.setEnabled(apply1608Supported);
        if(ocPerformance1608Button!=null)ocPerformance1608Button.setEnabled(apply1608Supported);
        if(ocDynamic1776Button!=null)ocDynamic1776Button.setEnabled(apply1776Supported);
        if(ocPerformance1776Button!=null)ocPerformance1776Button.setEnabled(apply1776Supported);
        if(ocDisableButton!=null)ocDisableButton.setEnabled(true);
}

    private void confirmOcMode(String mode){
        boolean stage8=mode.endsWith("1776");
        boolean stage7=mode.endsWith("1608");
        boolean dynamic=mode.startsWith("dynamic");
        String mhz=stage8?"1776":(stage7?"1608":"1560");
        String cluster=stage8?"CPU2-3":"CPU4";
        String label=(dynamic?"Dynamic ":"Performance ")+mhz;
        String behavior=dynamic
                ?"schedutil may scale "+cluster+" between 408 and "+mhz+" MHz."
                :"performance governor will hold "+cluster+" at the "+mhz+" MHz ceiling while thermal cooling remains active.";
        String validation=stage8
                ?"validated Stage 8C CPU2-3 overclock"
                :(stage7?"validated Stage 7C CPU4 overclock":"validated Stage 6C CPU4 overclock");
        new AlertDialog.Builder(this)
                .setTitle("Enable "+label+"?")
                .setMessage(behavior+
                        "\n\nThis is the "+validation+": "+mhz+" MHz at 1.15 V on VF0403. "+
                        "Non-target CPU policies are re-clamped after the global boost transition. "+
                        "Critical thermal protection is unchanged.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Apply",(d,w)->applyOcMode(mode))
                .show();
    }

    private void applyOcMode(String mode){
        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("cpu oc "+mode);
            if(r.ok())RootBridge.get().ctl("logger refresh");
            runOnUiThread(()->{
                if(!r.ok()){
                    Toast.makeText(this,
                            r.exitCode==3
                                    ?"Validated CPU OC support is not installed or does not match this device."
                                    :"CPU OC request failed.",
                            Toast.LENGTH_LONG).show();
                }
                ocLoaded=false;
                loadOcStatus();
                handler.postDelayed(this::refresh,300);
            });
        });
    }

    private int countOpps(String raw){
        if(raw==null||raw.trim().isEmpty())return 0;
        int n=0;
        for(String item:raw.split(",")) if(!item.trim().isEmpty()) n++;
        return n;
    }

    private void setOcLine(TextView view,String label,String clock,String state){
        boolean validated="validated_available".equals(state);
        boolean candidate="candidate_available".equals(state);
        boolean present=candidate||validated||"available".equals(state)||"present_unverified".equals(state);
        String suffix=validated?"VALIDATED / READY":
                (candidate?"BOOT CANDIDATE / VALIDATION REQUIRED":
                        (present?"PRESENT / VALIDATION REQUIRED":"NOT ENABLED / BOOT PATCH REQUIRED"));
        view.setText(label+" • "+clock+" • "+suffix);
        view.setTextColor(validated?GREEN:(present?YELLOW:MUTED));
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
        if(confirmedCpuMode!=null){
            if(confirmedCpuMode.equals(telemetryCpuMode)){
                confirmedTelemetryMatches++;
                if(confirmedTelemetryMatches>=2){
                    confirmedCpuMode=null;
                    confirmedTelemetryMatches=0;
                }
            }else{
                confirmedTelemetryMatches=0;
            }
        }
        String cpuMode=pendingCpuMode!=null?pendingCpuMode:
                (confirmedCpuMode!=null?confirmedCpuMode:telemetryCpuMode);
        updateCpuButtons(cpuMode);

        PolicyState performancePolicy=policies.get("policy2");
        PolicyState primePolicy=policies.get("policy4");
        int primeUtil=util.getOrDefault(4,0);
        int primeClock=primePolicy==null?0:primePolicy.current;

        overallValue.setText(overall+"%");
        if(pendingCpuMode==null){
            modeValue.setText(friendlyMode(cpuMode));
        }
        primeValue.setText("Prime A73 • "+primeUtil+"% • "+primeClock+" MHz");
        if(ocRuntimeValue!=null){
            boolean a53Mode="oc_dynamic1776".equals(cpuMode)||"oc_performance1776".equals(cpuMode);
            PolicyState livePolicy=a53Mode?performancePolicy:primePolicy;
            String liveCluster=a53Mode?"CPU2-3":"CPU4";
            String liveGov=livePolicy==null?"—":livePolicy.governor;
            int liveClock=livePolicy==null?0:livePolicy.current;
            int liveMax=livePolicy==null?0:livePolicy.max;
            boolean boosted=a53Mode || primeClock>1512 ||
                    "oc_dynamic1560".equals(cpuMode) || "oc_performance1560".equals(cpuMode) ||
                    "oc_dynamic1608".equals(cpuMode) || "oc_performance1608".equals(cpuMode);
            ocRuntimeValue.setText(
                    "Live OC • "+liveCluster+" "+liveClock+" / "+liveMax+" MHz • "+liveGov+
                            (boosted?" • BOOST PATH ACTIVE":" • stock ceiling"));
            ocRuntimeValue.setTextColor(boosted?GREEN:TEXT);
        }
        if(governorValue!=null){
            PolicyState p0=policies.get("policy0");
            governorValue.setText(p0==null?"—":p0.governor);
        }
        if(primeModeValue!=null){
            primeModeValue.setText(primePolicy==null?"—":primePolicy.governor+" • "+primeClock);
        }
        if(defaultModeValue!=null){
            defaultModeValue.setText(friendlyMode(cpuMode));
        }
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
            ui.graphLabel.setText("CLOCK HISTORY • "+p.current+" MHz • scale "+p.max+" MHz");
            ui.graph.setScaleMax(Math.max(1f,p.max));
            ui.graph.addValue(p.current);
        }else{
            ui.clock.setText("Clock • unavailable");
            ui.governor.setText("Governor • unavailable");
            ui.graphLabel.setText("CLOCK HISTORY • unavailable");
        }

        int measuredCapacity=0;
        for(int core:ui.cores) measuredCapacity=Math.max(measuredCapacity,capacity.getOrDefault(core,0));
        String temp=findThermal(thermals,ui.thermalPrefix);
        ui.thermal.setText("Thermal • "+temp+
                (measuredCapacity>0 ? " • scheduler capacity "+measuredCapacity : ""));

        String cool=findCooling(cooling,ui.coolingType);
        ui.cooling.setText("Cooling • "+(cool==null?"not reported":cool));
        ui.cooling.setTextColor(cool!=null && !cool.startsWith("0/")?RED:MUTED);
    }

    private void chooseCpuFrequency(String policyName,boolean maximum){
        Map<String,String> telemetry=TelemetryStore.read(this);
        Map<String,PolicyState> policies=parsePolicies(TelemetryStore.get(telemetry,"cpu_policies",""));
        Map<String,AvailablePolicy> available=parseAvailablePolicies(TelemetryStore.get(telemetry,"cpu_available",""));
        PolicyState policy=policies.get(policyName);
        AvailablePolicy choices=available.get(policyName);
        if(policy==null||choices==null||choices.frequenciesKhz.isEmpty()){
            Toast.makeText(this,"CPU frequency list is not available yet.",Toast.LENGTH_SHORT).show();
            return;
        }

        List<Integer> allowed=new ArrayList<>();
        for(int khz:choices.frequenciesKhz){
            int mhz=khz/1000;
            if(maximum){
                if(mhz>=policy.min)allowed.add(khz);
            }else{
                if(mhz<=policy.max)allowed.add(khz);
            }
        }
        if(allowed.isEmpty())return;

        String[] labels=new String[allowed.size()];
        int selected=-1;
        int current=maximum?policy.max:policy.min;
        for(int i=0;i<allowed.size();i++){
            int mhz=allowed.get(i)/1000;
            labels[i]=mhz+" MHz";
            if(mhz==current)selected=i;
        }

        new AlertDialog.Builder(this)
                .setTitle(policyName+" • "+(maximum?"maximum":"minimum"))
                .setSingleChoiceItems(labels,selected,(d,which)->{
                    d.dismiss();
                    applyCpuPolicy(policyName,maximum?"max":"min",String.valueOf(allowed.get(which)));
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void chooseCpuGovernor(String policyName){
        Map<String,String> telemetry=TelemetryStore.read(this);
        Map<String,PolicyState> policies=parsePolicies(TelemetryStore.get(telemetry,"cpu_policies",""));
        Map<String,AvailablePolicy> available=parseAvailablePolicies(TelemetryStore.get(telemetry,"cpu_available",""));
        PolicyState policy=policies.get(policyName);
        AvailablePolicy choices=available.get(policyName);
        if(policy==null||choices==null||choices.governors.isEmpty()){
            Toast.makeText(this,"CPU governor list is not available yet.",Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels=choices.governors.toArray(new String[0]);
        int selected=choices.governors.indexOf(policy.governor);
        new AlertDialog.Builder(this)
                .setTitle(policyName+" • governor")
                .setSingleChoiceItems(labels,selected,(d,which)->{
                    d.dismiss();
                    applyCpuPolicy(policyName,"governor",choices.governors.get(which));
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void applyCpuPolicy(String policy,String field,String value){
        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("cpu policy "+policy+" "+field+" "+value);
            if(r.ok())RootBridge.get().ctl("logger refresh");
            runOnUiThread(()->{
                if(!r.ok()){
                    Toast.makeText(this,
                            r.exitCode==2?"CPU setting rejected by the kernel-advertised policy limits.":"CPU setting failed.",
                            Toast.LENGTH_LONG).show();
                }
                handler.postDelayed(this::refresh,250);
            });
        });
    }

    private Map<String,AvailablePolicy> parseAvailablePolicies(String raw){
        Map<String,AvailablePolicy> out=new LinkedHashMap<>();
        if(raw==null)return out;
        for(String item:raw.split(";")){
            int bracket=item.indexOf('[');
            int eq=item.indexOf('=');
            int at=item.indexOf('@',eq+1);
            if(bracket<1||eq<bracket||at<eq)continue;
            String name=item.substring(0,bracket).trim();
            AvailablePolicy a=new AvailablePolicy();

            for(String freq:item.substring(eq+1,at).split(",")){
                try{
                    int khz=Integer.parseInt(freq.trim());
                    if(khz>0)a.frequenciesKhz.add(khz);
                }catch(Exception ignored){}
            }
            for(String gov:item.substring(at+1).split(",")){
                String g=gov.trim();
                if(!g.isEmpty())a.governors.add(g);
            }
            out.put(name,a);
        }
        return out;
    }

    private String friendlyMode(String mode){
        if("balanced".equals(mode))return "Balanced";
        if("performance".equals(mode))return "Locked Maximum";
        if("oc_dynamic1560".equals(mode))return "Dynamic 1560";
        if("oc_performance1560".equals(mode))return "Performance 1560";
        if("oc_dynamic1608".equals(mode))return "Dynamic 1608";
        if("oc_performance1608".equals(mode))return "Performance 1608";
        if("oc_dynamic1776".equals(mode))return "Dynamic 1776";
        if("oc_performance1776".equals(mode))return "Performance 1776";
        if("custom".equals(mode))return "Custom";
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

    private GradientDrawable blackAccentCard(int accent){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.rgb(8,12,13));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1),Color.argb(165,Color.red(accent),Color.green(accent),Color.blue(accent)));
        return bg;
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

    private static final class AvailablePolicy{
        final List<Integer> frequenciesKhz=new ArrayList<>();
        final List<String> governors=new ArrayList<>();
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
        final TextView graphLabel;
        final SparklineView graph;

        ClusterUi(
                String policy,int[] cores,int knownCapacity,String thermalPrefix,String coolingType,
                TextView util,TextView coreUtil,TextView clock,TextView governor,
                TextView thermal,TextView cooling,TextView graphLabel,SparklineView graph){
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
            this.graphLabel=graphLabel;
            this.graph=graph;
        }
    }
}
