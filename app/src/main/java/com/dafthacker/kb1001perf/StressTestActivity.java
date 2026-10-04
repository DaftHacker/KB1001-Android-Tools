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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class StressTestActivity extends Activity {
    private static final int CPU=Color.rgb(77,210,126);
    private static final int GPU=Color.rgb(255,151,61);
    private static final int TEMP=Color.rgb(91,205,223);
    private static final int COMBINED=Color.rgb(216,106,235);
    private static final int TEXT=Color.rgb(236,244,242);
    private static final int MUTED=Color.rgb(154,173,171);
    private static final float THERMAL_STOP_C=80f;

    private static volatile double cpuSink;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cpuBurn=new AtomicBoolean(false);
    private final AtomicLong cpuWork=new AtomicLong();

    private ExecutorService cpuPool;
    private LinearLayout content;
    private GpuStressView gpuStress;
    private StressGaugeView gauge;
    private GraphMetric cpuMetric;
    private GraphMetric gpuMetric;
    private GraphMetric tempMetric;
    private TextView timerValue;
    private TextView stateValue;
    private TextView renderFpsValue;
    private TextView workersValue;
    private Button testButton;
    private final Button[] modeButtons=new Button[3];
    private final Button[] durationButtons=new Button[3];

    private String mode="combined";
    private int durationSeconds=60;
    private boolean running;
    private long startedAt;
    private long endsAt;
    private long lastTelemetryKick;
    private float renderFps;

    private int samples;
    private double sumCpu;
    private double sumGpu;
    private double sumTemp;
    private double sumRenderFps;
    private double sumCpuUtil;
    private double sumGpuUtil;
    private double sumCpuScore;
    private double sumGpuScore;
    private double sumSystemScore;
    private float maxTemp;
    private long lastScoreAt;
    private long lastCpuWork;
    private int scoreSamples;
    private float cpuScore;
    private float gpuScore;
    private float systemScore;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        String requested=getIntent().getStringExtra("mode");
        if("cpu".equals(requested)||"gpu".equals(requested)||"combined".equals(requested)) mode=requested;
        TelemetryStore.ensureSnapshot(this);
        setContentView(buildUi());
        updateSelections();
        updateIdleState();
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
        Button back=button("← Back",Color.rgb(39,56,55),v->finish());
        top.addView(back,new LinearLayout.LayoutParams(dp(86),dp(44)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8),0,0,0);
        titles.addView(text("System Stress & Test",26,TEXT,true));
        titles.addView(text("Repeatable CPU/GPU benchmark with live telemetry",11,COMBINED,true));
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top);

        ScrollView scroll=new ScrollView(this);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(8),0,dp(28));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        section("MODE","Choose which part of the SoC to load.",COMBINED);
        LinearLayout modes=row();
        modeButtons[0]=modeButton("CPU",CPU,"cpu");
        modeButtons[1]=modeButton("GPU",GPU,"gpu");
        modeButtons[2]=modeButton("System",COMBINED,"combined");
        for(Button b:modeButtons)modes.addView(b,weight());
        content.addView(modes);

        section("DURATION","Tests stop automatically. Thermal safety cutoff: "+(int)THERMAL_STOP_C+"°C.",Color.rgb(102,163,255));
        LinearLayout durations=row();
        int[] secs={30,60,120};
        for(int i=0;i<secs.length;i++){
            final int value=secs[i];
            durationButtons[i]=button(value+" sec",Color.rgb(35,49,49),v->{
                if(running)return;
                durationSeconds=value;
                updateSelections();
            });
            durations.addView(durationButtons[i],weight());
        }
        content.addView(durations);

        section("LIVE BENCHMARK","Score, utilization, clocks and thermal headroom.",CPU);

        LinearLayout statusCard=new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(13),dp(10),dp(13),dp(10));
        statusCard.setBackground(cardBg(COMBINED,68));

        gauge=new StressGaugeView(this);
        statusCard.addView(gauge,new LinearLayout.LayoutParams(dp(170),dp(170)));

        LinearLayout status=new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(dp(10),0,0,0);

        stateValue=text("READY",11,COMBINED,true);
        timerValue=text("01:00",31,TEXT,true);
        renderFpsValue=text("Render FPS —",13,GPU,true);
        workersValue=text("",10,MUTED,false);

        status.addView(stateValue);
        status.addView(timerValue);
        status.addView(renderFpsValue);
        status.addView(workersValue);
        statusCard.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        content.addView(statusCard,full());

        cpuMetric=metric("CPU UTILIZATION / CLOCK",CPU);
        gpuMetric=metric("GPU UTILIZATION / CLOCK",GPU);
        tempMetric=metric("THERMAL",TEMP);
        content.addView(cpuMetric.root,full());
        content.addView(gpuMetric.root,full());
        content.addView(tempMetric.root,full());

        gpuStress=new GpuStressView(this);
        gpuStress.setFpsListener(fps->renderFps=fps);
        LinearLayout gpuSurfaceCard=new LinearLayout(this);
        gpuSurfaceCard.setOrientation(LinearLayout.VERTICAL);
        gpuSurfaceCard.setPadding(dp(9),dp(9),dp(9),dp(9));
        gpuSurfaceCard.setBackground(cardBg(GPU,45));
        gpuSurfaceCard.addView(text("GPU workload surface",10,GPU,true));
        gpuSurfaceCard.addView(gpuStress,new LinearLayout.LayoutParams(-1,dp(240)));
        content.addView(gpuSurfaceCard,full());

        testButton=button("Start Test",CPU,v->{
            if(running) stopTest("Stopped by user",true);
            else startTest();
        });
        content.addView(testButton,new LinearLayout.LayoutParams(-1,dp(50)));

        TextView note=text(
                "Scores are relative benchmark scores for comparing the same mode/duration across Stock, Performance and future OC profiles. CPU score measures completed math work per second; GPU score measures sustained render throughput; System score uses both. Android/kernel thermal throttling remains enabled and the test also stops at 80°C.",
                10,MUTED,false);
        content.addView(note,full());

        return root;
    }

    private void startTest(){
        if(running)return;

        running=true;
        startedAt=SystemClock.elapsedRealtime();
        endsAt=startedAt+durationSeconds*1000L;
        lastTelemetryKick=0;
        samples=0;
        scoreSamples=0;
        sumCpu=sumGpu=sumTemp=sumRenderFps=0;
        sumCpuUtil=sumGpuUtil=sumCpuScore=sumGpuScore=sumSystemScore=0;
        maxTemp=0;
        renderFps=0;
        cpuScore=gpuScore=systemScore=0;
        cpuWork.set(0);
        lastCpuWork=0;
        lastScoreAt=startedAt;

        cpuMetric.graph.clear();
        gpuMetric.graph.clear();
        tempMetric.graph.clear();

        if(hasCpu())startCpuStress();
        if(hasGpu())gpuStress.startStress();
        else gpuStress.stopStress();

        stateValue.setText("RUNNING • "+mode.toUpperCase(Locale.US));
        stateValue.setTextColor(modeColor());
        testButton.setText("Stop Test");
        testButton.setTextColor(TEXT);
        testButton.setBackground(cardBg(Color.rgb(210,74,74),155));
        workersValue.setText(hasCpu()
                ? Runtime.getRuntime().availableProcessors()+" CPU workers"
                : "GPU renderer active");

        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void startCpuStress(){
        cpuBurn.set(true);
        int workers=Math.max(1,Runtime.getRuntime().availableProcessors());
        cpuPool=Executors.newFixedThreadPool(workers);
        for(int i=0;i<workers;i++){
            final int seed=i+1;
            cpuPool.submit(()->{
                double x=seed*.713;
                long n=1;
                while(cpuBurn.get()){
                    for(int k=0;k<10000;k++){
                        x=Math.sin(x+n*.000001)*Math.cos(x*.73)+Math.sqrt(Math.abs(x)+1.0);
                        n++;
                    }
                    cpuSink=x;
                    cpuWork.addAndGet(10000);
                }
            });
        }
    }

    private void stopCpuStress(){
        cpuBurn.set(false);
        if(cpuPool!=null){
            cpuPool.shutdownNow();
            cpuPool=null;
        }
    }

    private final Runnable tick=new Runnable(){
        @Override public void run(){
            if(!running)return;

            long now=SystemClock.elapsedRealtime();
            long remaining=Math.max(0,endsAt-now);
            timerValue.setText(formatTime(remaining));

            if(now-lastTelemetryKick>=900){
                lastTelemetryKick=now;
                io.execute(()->RootBridge.get().ctl("logger refresh"));
            }

            Map<String,String> m=TelemetryStore.read(StressTestActivity.this);
            CpuPeak cpu=parseCpu(TelemetryStore.get(m,"cpu_policies",""));
            int cpuUtil=Math.max(0,Math.min(100,parseInt(TelemetryStore.get(m,"cpu_util_pct","0"))));
            int gpu=parseInt(TelemetryStore.get(m,"gpu_clock_mhz","0"));
            int gpuUtil=Math.max(0,Math.min(100,parseInt(TelemetryStore.get(m,"gpu_util_pct","0"))));
            float temp=parseFloat(TelemetryStore.get(m,"thermal_max_c","0"));
            boolean throttling="1".equals(TelemetryStore.get(m,"thermal_throttling","0"));

            int tempPct=Math.max(0,Math.min(100,Math.round(temp/90f*100)));

            long workNow=cpuWork.get();
            long scoreDt=Math.max(1,now-lastScoreAt);
            if(hasCpu() && now-lastScoreAt>=400){
                long workDelta=Math.max(0,workNow-lastCpuWork);
                double opsPerSec=workDelta*1000.0/scoreDt;
                cpuScore=(float)(opsPerSec/5000.0);
                lastCpuWork=workNow;
                lastScoreAt=now;
            }else if(!hasCpu()){
                cpuScore=0;
            }

            if(hasGpu()){
                gpuScore=Math.max(0f,renderFps*20f);
            }else{
                gpuScore=0;
            }

            if(hasCpu()&&hasGpu()){
                systemScore=(float)Math.sqrt(Math.max(0.0,cpuScore*gpuScore));
            }else if(hasCpu()){
                systemScore=cpuScore;
            }else{
                systemScore=gpuScore;
            }

            cpuMetric.set(cpuUtil+"% • "+cpu.current+" MHz",cpuUtil);
            gpuMetric.set(gpuUtil+"% • "+gpu+" MHz",gpuUtil);
            tempMetric.set(String.format(Locale.US,"%.1f °C%s",temp,throttling?" • THROTTLING":""),tempPct);

            float gaugePct=Math.max(0f,Math.min(100f,systemScore/20f));
            String scoreLabel="combined".equals(mode)?"SYSTEM SCORE":("cpu".equals(mode)?"CPU SCORE":"GPU SCORE");
            gauge.setGauge(gaugePct,String.format(Locale.US,"%.0f",systemScore),scoreLabel);

            renderFpsValue.setText(hasGpu()
                    ? String.format(Locale.US,"GPU %.0f score • %.0f FPS",gpuScore,renderFps)
                    : String.format(Locale.US,"CPU %.0f score",cpuScore));

            workersValue.setText(
                    "CPU "+cpuUtil+"% • GPU "+gpuUtil+"% • "+
                    (throttling?"thermal throttle active":"no thermal throttle"));

            if(temp>=0){
                samples++;
                sumCpu+=cpu.current;
                sumGpu+=gpu;
                sumTemp+=temp;
                sumCpuUtil+=cpuUtil;
                sumGpuUtil+=gpuUtil;
                if(hasGpu())sumRenderFps+=renderFps;
                maxTemp=Math.max(maxTemp,temp);

                if((hasCpu() && cpuScore>0)||(hasGpu() && gpuScore>0)){
                    scoreSamples++;
                    sumCpuScore+=cpuScore;
                    sumGpuScore+=gpuScore;
                    sumSystemScore+=systemScore;
                }
            }

            if(temp>=THERMAL_STOP_C){
                stopTest("Thermal safety stop at "+String.format(Locale.US,"%.1f°C",temp),true);
                return;
            }

            if(remaining<=0){
                stopTest("Completed",true);
                return;
            }

            handler.postDelayed(this,500);
        }
    };

    private void stopTest(String reason,boolean showSummary){
        if(!running)return;
        running=false;
        handler.removeCallbacks(tick);
        stopCpuStress();
        if(gpuStress!=null)gpuStress.stopStress();

        stateValue.setText(reason.toUpperCase(Locale.US));
        stateValue.setTextColor("Completed".equals(reason)?CPU:Color.rgb(255,196,73));
        testButton.setText("Start Test");
        testButton.setTextColor(TEXT);
        testButton.setBackground(cardBg(CPU,125));

        if(showSummary && !isFinishing()){
            double n=Math.max(1,samples);
            double sn=Math.max(1,scoreSamples);
            String profile=TelemetryStore.get(TelemetryStore.read(this),"profile","—");
            String message=
                    "Mode: "+("combined".equals(mode)?"SYSTEM":mode.toUpperCase(Locale.US))+"\n"+
                    "Profile: "+profile+"\n"+
                    "Duration: "+Math.max(1,(SystemClock.elapsedRealtime()-startedAt)/1000)+" sec\n\n"+
                    (hasCpu()?String.format(Locale.US,"CPU score: %.0f\n",sumCpuScore/sn):"")+
                    (hasGpu()?String.format(Locale.US,"GPU score: %.0f\n",sumGpuScore/sn):"")+
                    String.format(Locale.US,"%s score: %.0f\n",
                            "combined".equals(mode)?"System":("cpu".equals(mode)?"CPU":"GPU"),
                            sumSystemScore/sn)+
                    (hasCpu()?String.format(Locale.US,"Average CPU utilization: %.0f%%\n",sumCpuUtil/n):"")+
                    (hasGpu()?String.format(Locale.US,"Average GPU utilization: %.0f%%\n",sumGpuUtil/n):"")+
                    String.format(Locale.US,"Average CPU clock: %.0f MHz\n",sumCpu/n)+
                    String.format(Locale.US,"Average GPU clock: %.0f MHz\n",sumGpu/n)+
                    (hasGpu()?String.format(Locale.US,"Average render FPS: %.1f\n",sumRenderFps/n):"")+
                    String.format(Locale.US,"Average temperature: %.1f°C\n",sumTemp/n)+
                    String.format(Locale.US,"Peak temperature: %.1f°C\n",maxTemp)+
                    "\n"+reason+"\n\nCompare scores using the same mode and duration.";

            new AlertDialog.Builder(this)
                    .setTitle("Stress test result")
                    .setMessage(message)
                    .setPositiveButton("OK",null)
                    .show();
        }
    }

    private void updateIdleState(){
        timerValue.setText(formatTime(durationSeconds*1000L));
        stateValue.setText("READY");
        renderFpsValue.setText("Render FPS —");
        workersValue.setText("Thermal cutoff "+(int)THERMAL_STOP_C+"°C");
        gauge.setGauge(0,"—","BENCH SCORE");
        if(testButton!=null){
            testButton.setText("Start Test");
            testButton.setTextColor(TEXT);
            testButton.setBackground(cardBg(CPU,125));
        }
    }

    private void updateSelections(){
        String[] modes={"cpu","gpu","combined"};
        int[] colors={CPU,GPU,COMBINED};
        for(int i=0;i<modeButtons.length;i++){
            if(modeButtons[i]==null)continue;
            boolean selected=modes[i].equals(mode);
            modeButtons[i].setBackground(cardBg(colors[i],selected?185:45));
            modeButtons[i].setTextColor(selected?Color.rgb(8,14,14):TEXT);
        }

        int[] secs={30,60,120};
        for(int i=0;i<durationButtons.length;i++){
            if(durationButtons[i]==null)continue;
            boolean selected=secs[i]==durationSeconds;
            durationButtons[i].setBackground(cardBg(Color.rgb(102,163,255),selected?175:40));
        }

        if(!running)updateIdleState();
    }

    private Button modeButton(String label,int color,String value){
        return button(label,color,v->{
            if(running)return;
            mode=value;
            updateSelections();
        });
    }

    private boolean hasCpu(){return "cpu".equals(mode)||"combined".equals(mode);}
    private boolean hasGpu(){return "gpu".equals(mode)||"combined".equals(mode);}

    private int modeColor(){
        if("cpu".equals(mode))return CPU;
        if("gpu".equals(mode))return GPU;
        return COMBINED;
    }

    private GraphMetric metric(String name,int color){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(10),dp(12),dp(10));
        root.setBackground(cardBg(color,48));

        LinearLayout head=row();
        TextView label=text(name,10,color,true);
        TextView value=text("—",18,TEXT,true);
        value.setGravity(Gravity.END);
        head.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        head.addView(value);
        root.addView(head);

        SparklineView graph=new SparklineView(this);
        graph.setAccentColor(color);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(62));
        gp.setMargins(0,dp(4),0,0);
        root.addView(graph,gp);
        return new GraphMetric(root,value,graph);
    }

    private CpuPeak parseCpu(String policies){
        int cur=0,max=0;
        for(String item:policies.split(";")){
            int eq=item.indexOf('=');
            int slash=item.indexOf('/',eq+1);
            int dash=item.indexOf('-',slash+1);
            int at=item.indexOf('@',dash+1);
            if(eq<0||slash<0||dash<0)continue;
            try{
                cur=Math.max(cur,Integer.parseInt(item.substring(eq+1,slash).replaceAll("[^0-9]","")));
                max=Math.max(max,Integer.parseInt(item.substring(dash+1,at>dash?at:item.length()).replaceAll("[^0-9]","")));
            }catch(Exception ignored){}
        }
        return new CpuPeak(cur,max);
    }

    private void section(String title,String subtitle,int color){
        TextView t=text(title,14,color,true);
        t.setPadding(dp(2),dp(15),0,dp(2));
        content.addView(t);
        TextView s=text(subtitle,10,MUTED,false);
        s.setPadding(dp(2),0,0,dp(7));
        content.addView(s);
    }

    private Button button(String label,int color,View.OnClickListener listener){
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(TEXT);
        b.setBackground(cardBg(color,88));
        b.setOnClickListener(listener);
        return b;
    }

    private GradientDrawable cardBg(int color,int alpha){
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

    private TextView text(String value,int sp,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    private LinearLayout row(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weight(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);
        p.setMargins(dp(3),0,dp(3),0);
        return p;
    }

    private LinearLayout.LayoutParams full(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(4),0,dp(4));
        return p;
    }

    private String formatTime(long ms){
        long s=Math.max(0,(ms+999)/1000);
        return String.format(Locale.US,"%02d:%02d",s/60,s%60);
    }

    private int parseInt(String s){try{return Integer.parseInt(s);}catch(Exception e){return 0;}}
    private float parseFloat(String s){try{return Float.parseFloat(s);}catch(Exception e){return 0f;}}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override protected void onResume(){
        super.onResume();
        if(gpuStress!=null)gpuStress.onResume();
    }

    @Override protected void onPause(){
        if(running)stopTest("Stopped when test screen left foreground",false);
        if(gpuStress!=null)gpuStress.onPause();
        super.onPause();
    }

    @Override protected void onDestroy(){
        handler.removeCallbacks(tick);
        stopCpuStress();
        io.shutdownNow();
        super.onDestroy();
    }

    private static final class GraphMetric{
        final LinearLayout root;
        final TextView value;
        final SparklineView graph;
        GraphMetric(LinearLayout root,TextView value,SparklineView graph){
            this.root=root;this.value=value;this.graph=graph;
        }
        void set(String text,int percent){
            value.setText(text);
            graph.addValue(percent);
        }
    }

    private static final class CpuPeak{
        final int current,max;
        CpuPeak(int current,int max){this.current=current;this.max=max;}
    }
}
