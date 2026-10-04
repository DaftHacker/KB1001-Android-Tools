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

public class GpuActivity extends Activity {
    private static final int ORANGE = Color.rgb(255,151,61);
    private static final int ORANGE_SOFT = Color.rgb(255,183,92);
    private static final int RED = Color.rgb(255,88,78);
    private static final int MUTED = Color.rgb(154,173,171);
    private static final int TEXT = Color.rgb(236,244,242);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private SparklineView graph;
    private TextView clockValue;
    private TextView runtimeValue;
    private TextView governorValue;
    private TextView dvfsValue;
    private TextView defaultValue;
    private TextView gameValue;
    private TextView outsideValue;
    private volatile String pendingProfile;
    private volatile String pendingMode;
    private boolean active;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TelemetryStore.ensureSnapshot(this);
        setContentView(buildUi());
        refreshStatus();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(12),dp(12),dp(12));
        root.setBackgroundResource(R.drawable.bg_app);

        root.setOnApplyWindowInsetsListener((v,insets) -> {
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

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button back = smallButton("← Back", v -> finish());
        back.setTextSize(12);
        back.setTextColor(Color.rgb(35,18,6));
        back.setBackground(tintedCard(ORANGE,190));
        top.addView(back,new LinearLayout.LayoutParams(dp(86),dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8),0,0,0);
        titles.addView(text("Mali-G57",26,TEXT,true));
        titles.addView(text("GPU Control",11,ORANGE,true));
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));

        TextView badge=text("GPU",11,Color.rgb(30,18,9),true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(pill(ORANGE));
        top.addView(badge,new LinearLayout.LayoutParams(dp(54),dp(30)));

        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(10),0,dp(24));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        liveCard();

        section("PROFILES","Choose a GPU behavior. Extreme modes are session-only unless selected for game automation.",ORANGE);
        content.addView(profileChoice(
                "Stock 696 MHz",
                "Factory frequency table and DVFS behavior.",
                "stock",
                true),full());
        content.addView(profileChoice(
                "Dynamic 744 MHz",
                "744 MHz ceiling with simple_ondemand DVFS.",
                "dynamic744",
                true),full());
        content.addView(profileChoice(
                "Performance 744 MHz",
                "Pins the GPU at 744 MHz for maximum sustained clock.",
                "performance744",
                true),full());

        content.addView(customClockCard(),full());

        content.addView(actionCard(
                "Extreme 792 • Dynamic",
                "Adds the 792 MHz OPP but keeps DVFS active so the GPU can clock down.",
                "APPLY DYNAMIC 792",
                () -> confirmExtreme("Dynamic 792","extreme792_dynamic")),full());

        content.addView(actionCard(
                "Extreme 792 • Full Throttle",
                "Pins the GPU at 792 MHz. Thermal protection remains enabled.",
                "APPLY FULL THROTTLE",
                () -> confirmExtreme("Full Throttle 792","extreme792_full")),full());

        section("GAME AUTOMATION","These profiles are used only when game profile switching is enabled.",Color.rgb(192,112,255));

        gameValue = settingValueRow(
                "Game GPU profile",
                "Applied while a listed game is foreground.",
                v -> chooseGameProfile());
        content.addView((View)gameValue.getParent(),full());

        outsideValue = settingValueRow(
                "Outside-game GPU profile",
                "Restored after leaving a listed game.",
                v -> chooseOutsideProfile());
        content.addView((View)outsideValue.getParent(),full());

        return root;
    }

    private void liveCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15),dp(13),dp(15),dp(13));
        card.setBackground(blackAccentCard(ORANGE));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("LIVE GPU",10,ORANGE,true));
        clockValue=text("— MHz",27,TEXT,true);
        labels.addView(clockValue);
        head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        runtimeValue=text("—",11,ORANGE_SOFT,true);
        runtimeValue.setGravity(Gravity.END);
        head.addView(runtimeValue);
        card.addView(head);

        graph=new SparklineView(this);
        graph.setAccentColor(ORANGE);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(104));
        gp.setMargins(0,dp(8),0,dp(8));
        card.addView(graph,gp);

        LinearLayout info=new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        governorValue=miniStat(info,"GOVERNOR","—");
        dvfsValue=miniStat(info,"DVFS","—");
        defaultValue=miniStat(info,"DEFAULT","—");
        card.addView(info);

        content.addView(card,full());
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

    private View profileChoice(String title,String subtitle,String key,boolean persistent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13),dp(11),dp(13),dp(11));
        row.setBackground(tintedCard(ORANGE,58));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,15,TEXT,true));
        labels.addView(text(subtitle,10,MUTED,false));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        row.setOnClickListener(v -> requestProfile(persistent ? "persist" : "apply", key));
        return row;
    }

    private View customClockCard(){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13),dp(12),dp(13),dp(12));
        card.setBackground(tintedCard(ORANGE,58));

        card.addView(text("Custom MHz",15,TEXT,true));

        TextView note=text(
                "Session-only pinned clock. Supported OPPs: 200, 300, 400, 600, 696, 744, 792 MHz.",
                10,MUTED,false);
        note.setPadding(0,dp(2),0,dp(8));
        card.addView(note);

        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText input=new EditText(this);
        input.setHint("MHz");
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setPadding(dp(10),0,dp(10),0);
        input.setBackground(blackAccentCard(ORANGE));
        row.addView(input,new LinearLayout.LayoutParams(0,dp(46),1));

        Button apply=smallButton("Apply",v->{
            String raw=input.getText().toString().trim();
            int mhz=parseInt(raw);
            if(!isSupportedCustomMhz(mhz)){
                Toast.makeText(this,
                        "Supported values: 200, 300, 400, 600, 696, 744, 792 MHz",
                        Toast.LENGTH_LONG).show();
                return;
            }
            requestProfile("apply","custom_"+mhz);
        });
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(100),dp(46));
        bp.setMargins(dp(8),0,0,0);
        row.addView(apply,bp);

        card.addView(row);
        return card;
    }

    private boolean isSupportedCustomMhz(int mhz){
        return mhz==200||mhz==300||mhz==400||mhz==600||mhz==696||mhz==744||mhz==792;
    }

    private TextView settingValueRow(String title,String subtitle,View.OnClickListener listener){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13),dp(11),dp(13),dp(11));
        row.setBackground(tintedCard(Color.rgb(192,112,255),56));

        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,14,TEXT,true));
        labels.addView(text(subtitle,10,MUTED,false));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        TextView value=text("—",12,Color.rgb(218,166,255),true);
        value.setGravity(Gravity.END);
        row.addView(value,new LinearLayout.LayoutParams(dp(150),-2));
        row.setOnClickListener(listener);
        return value;
    }

    private View actionCard(String title,String subtitle,String action,Runnable run){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13),dp(12),dp(13),dp(12));
        card.setBackground(tintedCard(RED,72));

        card.addView(text(title,15,TEXT,true));

        TextView sub=text(subtitle,10,Color.rgb(195,169,166),false);
        sub.setPadding(0,dp(2),0,dp(8));
        card.addView(sub);

        Button b=smallButton(action,v->run.run());
        b.setTextColor(Color.rgb(255,215,209));
        b.setBackground(tintedCard(RED,110));
        card.addView(b,new LinearLayout.LayoutParams(-1,dp(44)));
        return card;
    }

    private void chooseGameProfile(){
        String[] labels={
                "Dynamic 744 MHz",
                "Performance 744 MHz",
                "Extreme 792 Dynamic",
                "Extreme 792 Full Throttle"
        };
        String[] values={
                "dynamic744",
                "performance744",
                "extreme792_dynamic",
                "extreme792_full"
        };
        new AlertDialog.Builder(this)
                .setTitle("Game GPU profile")
                .setSingleChoiceItems(labels,currentIndex(gameValue.getText().toString(),labels), (d,which)->{
                    d.dismiss();
                    ctl("auto profile "+values[which]);
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void chooseOutsideProfile(){
        String[] labels={"Stock 696 MHz","Dynamic 744 MHz","Performance 744 MHz"};
        String[] values={"stock","dynamic744","performance744"};
        new AlertDialog.Builder(this)
                .setTitle("Outside-game GPU profile")
                .setSingleChoiceItems(labels,currentIndex(outsideValue.getText().toString(),labels),(d,which)->{
                    d.dismiss();
                    ctl("auto idle "+values[which]);
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private int currentIndex(String current,String[] labels){
        for(int i=0;i<labels.length;i++){
            if(current.toLowerCase(Locale.US).contains(labels[i].split(" MHz")[0].toLowerCase(Locale.US))) return i;
        }
        return -1;
    }

    private void confirmExtreme(String name,String profile){
        new AlertDialog.Builder(this)
                .setTitle("Experimental "+name)
                .setMessage("This 792 MHz mode is session-only and has not yet been validated as a safe long-run profile on this tablet. Thermal protection stays enabled and reboot fallback remains Dynamic 744.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Apply",(d,w)->requestProfile("apply",profile))
                .show();
    }

    private void requestProfile(String mode,String profile){
        pendingProfile=profile;
        pendingMode=mode;

        String display=displayProfile(profile);
        if(runtimeValue!=null) runtimeValue.setText(display+" • switching");
        if("persist".equals(mode) && defaultValue!=null) defaultValue.setText(display+" • requested");

        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("profile request "+mode+" "+profile);
            if(r.ok()) RootBridge.get().ctl("logger refresh");
            runOnUiThread(()->{
                if(!r.ok()){
                    Toast.makeText(this,"GPU profile request failed",Toast.LENGTH_LONG).show();
                }
                refreshStatus();
                handler.postDelayed(this::refreshStatus,250);
            });
        });
    }

    private void ctl(String command){
        applyOptimisticState(command);

        io.execute(() -> {
            RootBridge.Result r=RootBridge.get().ctl(command);
            if(r.ok()){
                RootBridge.get().ctl("logger refresh");
            }
            runOnUiThread(() -> {
                if(!r.ok()){
                    Toast.makeText(this,"GPU command failed",Toast.LENGTH_LONG).show();
                }
                refreshStatus();
                handler.postDelayed(this::refreshStatus,250);
                handler.postDelayed(this::refreshStatus,750);
            });
        });
    }

    private void applyOptimisticState(String command){
        if(command==null)return;
        String[] p=command.trim().split("\\s+");
        if(p.length<2)return;

        if("persist".equals(p[0])){
            String profile=p[1];
            if(defaultValue!=null) defaultValue.setText(displayProfile(profile));
            if(runtimeValue!=null) runtimeValue.setText(displayProfile(profile)+" • applying");
            if(dvfsValue!=null) dvfsValue.setText(isPinned(profile)?"Pinned":"Dynamic");
        }else if("apply".equals(p[0])){
            String profile=p[1];
            if(runtimeValue!=null) runtimeValue.setText(displayProfile(profile)+" • applying");
            if(dvfsValue!=null) dvfsValue.setText(isPinned(profile)?"Pinned":"Dynamic");
        }else if("auto".equals(p[0]) && p.length>=3){
            if("profile".equals(p[1]) && gameValue!=null){
                gameValue.setText(displayProfile(p[2]));
            }else if("idle".equals(p[1]) && outsideValue!=null){
                outsideValue.setText(displayProfile(p[2]));
            }
        }
    }

    private boolean isPinned(String profile){
        return "performance744".equals(profile) ||
                "extreme792_full".equals(profile) ||
                "performance792".equals(profile);
    }

    private void refreshStatus(){
        io.execute(() -> {
            RootBridge.Result r=RootBridge.get().ctl("status");
            Map<String,String> status=parseStatus(r.output);
            Map<String,String> telemetry=TelemetryStore.read(this);

            runOnUiThread(() -> {
                String persistent=status.get("Persistent profile");
                String runtime=status.get("Runtime profile");
                String requestState=status.get("Profile request state");
                String requested=status.get("Requested profile");

                if(runtime==null||runtime.isEmpty()) runtime=TelemetryStore.get(telemetry,"profile","—");

                if(pendingProfile!=null){
                    if(pendingProfile.equals(runtime) && !"waiting".equals(requestState) && !"applying".equals(requestState)){
                        pendingProfile=null;
                        pendingMode=null;
                    }else if("error".equals(requestState) && pendingProfile.equals(requested)){
                        pendingProfile=null;
                        pendingMode=null;
                    }
                }

                if(defaultValue!=null){
                    if(pendingProfile!=null && "persist".equals(pendingMode)){
                        defaultValue.setText(displayProfile(pendingProfile)+" • requested");
                    }else{
                        defaultValue.setText(displayProfile(persistent));
                    }
                }

                if(gameValue!=null) gameValue.setText(displayProfile(status.get("Game profile")));
                if(outsideValue!=null) outsideValue.setText(displayProfile(status.get("Idle profile")));

                int mhz=parseInt(TelemetryStore.get(telemetry,"gpu_clock_mhz","0"));
                int util=Math.max(0,Math.min(100,parseInt(TelemetryStore.get(telemetry,"gpu_util_pct","0"))));
                if(clockValue!=null) clockValue.setText(util+"% • "+(mhz>0?mhz+" MHz":"— MHz"));
                if(graph!=null) graph.addValue(util);
                if(governorValue!=null) governorValue.setText(TelemetryStore.get(telemetry,"gpu_governor","—"));
                if(dvfsValue!=null) dvfsValue.setText("0".equals(TelemetryStore.get(telemetry,"gpu_dvfs",""))?"Pinned":"Dynamic");
                if(runtimeValue!=null){
                    String uiTarget=pendingProfile;
                    if(uiTarget==null && ("waiting".equals(requestState)||"applying".equals(requestState))){
                        uiTarget=requested;
                    }

                    if(uiTarget!=null && !uiTarget.isEmpty() && !uiTarget.equals(runtime)){
                        runtimeValue.setText(displayProfile(uiTarget)+
                                ("waiting".equals(requestState) ? " • waiting for GPU idle" : " • switching"));
                    }else{
                        runtimeValue.setText(displayProfile(runtime));
                    }
                }
            });
        });
    }

    private Map<String,String> parseStatus(String out){
        Map<String,String> map=new HashMap<>();
        for(String line:out.split("\\R")){
            int i=line.indexOf(':');
            if(i>0) map.put(line.substring(0,i).trim(),line.substring(i+1).trim());
        }
        return map;
    }

    private String displayProfile(String p){
        if(p==null)return "—";
        if("stock".equals(p))return "Stock 696";
        if("dynamic744".equals(p))return "Dynamic 744";
        if("performance744".equals(p))return "Performance 744";
        if("extreme792_dynamic".equals(p)||"extreme792".equals(p))return "Extreme 792 Dynamic";
        if("extreme792_full".equals(p)||"performance792".equals(p))return "Extreme 792 Full";
        if(p.startsWith("custom_"))return "Custom "+p.substring("custom_".length())+" MHz";
        return p;
    }

    private void section(String title,String subtitle,int color){
        TextView t=text(title,14,color,true);
        t.setPadding(dp(2),dp(16),0,dp(2));
        content.addView(t);
        TextView s=text(subtitle,10,MUTED,false);
        s.setPadding(dp(2),0,0,dp(7));
        content.addView(s);
    }

    private GradientDrawable blackAccentCard(int accent){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.rgb(8,12,13));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1),Color.argb(165,Color.red(accent),Color.green(accent),Color.blue(accent)));
        return bg;
    }

    private GradientDrawable tintedCard(int accent,int alpha){
        GradientDrawable g=new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Color.rgb(13,22,22),
                        Color.argb(alpha,Color.red(accent),Color.green(accent),Color.blue(accent)),
                        Color.rgb(10,17,18)
                });
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1),Color.argb(110,Color.red(accent),Color.green(accent),Color.blue(accent)));
        return g;
    }

    private GradientDrawable pill(int color){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(99));
        return g;
    }

    private Button smallButton(String label,View.OnClickListener listener){
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setPadding(dp(8),0,dp(8),0);
        b.setBackgroundResource(R.drawable.bg_button_secondary);
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

    private LinearLayout.LayoutParams full(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(4),0,dp(4));
        return p;
    }

    private int parseInt(String s){try{return Integer.parseInt(s);}catch(Exception e){return 0;}}
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}

    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(!active)return;
            refreshStatus();
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
}
