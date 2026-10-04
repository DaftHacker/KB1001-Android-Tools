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
    private static final int TEXT=Color.rgb(236,244,242);
    private static final int MUTED=Color.rgb(154,173,171);

    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private SparklineView graph;
    private TextView clockValue;
    private TextView modeValue;
    private LinearLayout policies;
    private boolean active;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        TelemetryStore.ensureSnapshot(this);
        setContentView(buildUi());
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
                top=bars.top; bottom=bars.bottom;
            }else{
                top=insets.getSystemWindowInsetTop();
                bottom=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(12),top+dp(10),dp(12),bottom+dp(10));
            return insets;
        });
        root.requestApplyInsets();

        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button back=button("← Back",v->finish());
        top.addView(back,new LinearLayout.LayoutParams(dp(86),dp(44)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8),0,0,0);
        titles.addView(text("A333 CPU",26,TEXT,true));
        titles.addView(text("1× Cortex-A73 + 4× Cortex-A53",11,GREEN,true));
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top);

        ScrollView scroll=new ScrollView(this);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(10),0,dp(24));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        liveCard();

        section("CPU MODE","First pass uses only frequency ceilings and governors already exposed by your kernel.");

        content.addView(actionCard(
                "Stock / Dynamic",
                "Restore the original per-policy governor, minimum, and maximum clocks captured from this tablet.",
                "RESTORE STOCK",
                ()->ctl("cpu restore")),full());

        content.addView(actionCard(
                "Full Performance",
                "Pins each CPU policy to its kernel-advertised stock maximum. Thermal throttling remains active.",
                "APPLY FULL PERFORMANCE",
                ()->confirmPerformance()),full());

        section("POLICIES","These are the actual cpufreq policies and available frequencies exposed by the running kernel.");
        policies=new LinearLayout(this);
        policies.setOrientation(LinearLayout.VERTICAL);
        content.addView(policies,new LinearLayout.LayoutParams(-1,-2));

        TextView note=text(
                "This intentionally does not add new CPU OPPs yet. Test Halo with Full Performance first; if CPU clocks are the bottleneck, the next pass can investigate the A73/A53 OPP tables and voltage margins.",
                10,MUTED,false);
        content.addView(card(note),full());

        return root;
    }

    private void liveCard(){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15),dp(13),dp(15),dp(13));
        card.setBackground(blackAccentCard());

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("LIVE CPU",10,GREEN,true));
        clockValue=text("— MHz",27,TEXT,true);
        labels.addView(clockValue);
        head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        modeValue=text("—",11,GREEN_SOFT,true);
        modeValue.setGravity(Gravity.END);
        head.addView(modeValue);
        card.addView(head);

        graph=new SparklineView(this);
        graph.setAccentColor(GREEN);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(104));
        gp.setMargins(0,dp(8),0,dp(4));
        card.addView(graph,gp);

        content.addView(card,full());
    }

    private void confirmPerformance(){
        new AlertDialog.Builder(this)
                .setTitle("Full CPU performance?")
                .setMessage("This pins every cpufreq policy to the highest frequency your current kernel already advertises. It is not an overclock, but it will increase power and heat.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Apply",(d,w)->ctl("cpu performance"))
                .show();
    }

    private void ctl(String command){
        if(command.endsWith("performance") && modeValue!=null) modeValue.setText("Full Performance");
        if(command.endsWith("restore") && modeValue!=null) modeValue.setText("Stock / Dynamic");

        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl(command);
            if(r.ok()) RootBridge.get().ctl("logger refresh");
            runOnUiThread(()->{
                if(!r.ok()) Toast.makeText(this,"CPU command failed",Toast.LENGTH_LONG).show();
                refresh();
                handler.postDelayed(this::refresh,350);
            });
        });
    }

    private void refresh(){
        io.execute(()->{
            RootBridge.Result status=RootBridge.get().ctl("cpu status");
            Map<String,String> telemetry=TelemetryStore.read(this);

            runOnUiThread(()->{
                String policiesText=TelemetryStore.get(telemetry,"cpu_policies","");
                CpuPeak peak=parsePeak(policiesText);
                if(clockValue!=null) clockValue.setText(peak.current>0?peak.current+" MHz":"— MHz");
                if(graph!=null) graph.addValue(peak.max>0?Math.max(0,Math.min(100,peak.current*100f/peak.max)):0);

                String first=status.output.split("\\R",2)[0];
                if(first.startsWith("CPU mode:")) first=first.substring("CPU mode:".length()).trim();
                if(modeValue!=null) modeValue.setText("performance".equals(first)?"Full Performance":"Stock / Dynamic");

                renderPolicies(
                        policiesText,
                        TelemetryStore.get(telemetry,"cpu_available",""));
            });
        });
    }

    private void renderPolicies(String current,String available){
        if(policies==null)return;
        policies.removeAllViews();

        Map<String,String> availMap=new LinkedHashMap<>();
        for(String part:available.split(";")){
            int eq=part.indexOf('=');
            if(eq>0) availMap.put(part.substring(0,eq),part.substring(eq+1));
        }

        for(String part:current.split(";")){
            if(part.trim().isEmpty())continue;
            int eq=part.indexOf('=');
            if(eq<=0)continue;

            String name=part.substring(0,eq);
            String state=part.substring(eq+1);
            String caps=availMap.get(name);

            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(13),dp(11),dp(13),dp(11));
            card.setBackground(tintedCard());

            card.addView(text(name.replace("policy","Policy "),14,GREEN,true));
            card.addView(text(state,12,TEXT,true));

            if(caps!=null){
                String[] pieces=caps.split("@",2);
                String freqs=pieces.length>0?formatFreqs(pieces[0]):"";
                String govs=pieces.length>1?pieces[1].replace(',', ' '):"";
                if(!freqs.isEmpty()) card.addView(text("Available: "+freqs,9,MUTED,false));
                if(!govs.isEmpty()) card.addView(text("Governors: "+govs,9,MUTED,false));
            }

            policies.addView(card,full());
        }

        if(policies.getChildCount()==0){
            policies.addView(card(text("CPU policy telemetry is not available yet.",11,MUTED,false)),full());
        }
    }

    private String formatFreqs(String raw){
        ArrayList<String> out=new ArrayList<>();
        for(String s:raw.split(",")){
            try{
                long khz=Long.parseLong(s.trim());
                out.add((khz/1000)+" MHz");
            }catch(Exception ignored){}
        }
        return android.text.TextUtils.join(" • ",out);
    }

    private CpuPeak parsePeak(String policies){
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

    private void section(String title,String subtitle){
        TextView t=text(title,14,GREEN,true);
        t.setPadding(dp(2),dp(16),0,dp(2));
        content.addView(t);
        TextView s=text(subtitle,10,MUTED,false);
        s.setPadding(dp(2),0,0,dp(7));
        content.addView(s);
    }

    private View actionCard(String title,String subtitle,String action,Runnable run){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13),dp(12),dp(13),dp(12));
        card.setBackground(tintedCard());
        card.addView(text(title,15,TEXT,true));
        TextView sub=text(subtitle,10,MUTED,false);
        sub.setPadding(0,dp(2),0,dp(8));
        card.addView(sub);
        Button b=button(action,v->run.run());
        card.addView(b,new LinearLayout.LayoutParams(-1,dp(44)));
        return card;
    }

    private GradientDrawable blackAccentCard(){
        GradientDrawable g=new GradientDrawable();
        g.setColor(Color.rgb(8,12,13));
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1),Color.argb(165,Color.red(GREEN),Color.green(GREEN),Color.blue(GREEN)));
        return g;
    }

    private GradientDrawable tintedCard(){
        GradientDrawable g=new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(13,22,22),Color.argb(52,Color.red(GREEN),Color.green(GREEN),Color.blue(GREEN)),Color.rgb(9,16,17)});
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1),Color.argb(105,Color.red(GREEN),Color.green(GREEN),Color.blue(GREEN)));
        return g;
    }

    private LinearLayout card(View child){
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12),dp(10),dp(12),dp(10));
        c.setBackground(tintedCard());
        c.addView(child);
        return c;
    }

    private Button button(String label,View.OnClickListener l){
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackground(tintedCard());
        b.setOnClickListener(l);
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

    private LinearLayout.LayoutParams full(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(4),0,dp(4));
        return p;
    }

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
        active=true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override protected void onPause(){
        active=false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy(){
        io.shutdownNow();
        super.onDestroy();
    }

    private static final class CpuPeak{
        final int current,max;
        CpuPeak(int current,int max){this.current=current;this.max=max;}
    }
}
