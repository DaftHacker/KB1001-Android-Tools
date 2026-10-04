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

public final class BatteryActivity extends Activity {
    private static final int GREEN=Color.rgb(77,210,126);
    private static final int GREEN_SOFT=Color.rgb(119,229,154);
    private static final int BLUE=Color.rgb(102,163,255);
    private static final int YELLOW=Color.rgb(255,202,64);
    private static final int RED=Color.rgb(255,83,79);
    private static final int TEXT=Color.rgb(236,244,242);
    private static final int MUTED=Color.rgb(154,173,171);

    private final Handler handler=new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private SparklineView graph;
    private TextView percentValue;
    private TextView statusValue;
    private TextView tempValue;
    private TextView voltageValue;
    private TextView currentValue;
    private TextView chargeCurrentValue;

    private TextView sourceValue;
    private TextView inputLimitValue;
    private TextView inputMinVoltageValue;
    private TextView inputTempValue;

    private TextView healthValue;
    private TextView capacityLevelValue;
    private TextView manufacturerValue;
    private TextView manufactureDateValue;
    private TextView cycleValue;
    private TextView chargeCounterValue;
    private TextView chargeFullValue;
    private TextView designFullValue;
    private TextView healthEstimateValue;

    private TextView timeToFullValue;
    private TextView timeRemainingValue;

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
        back.setTextColor(Color.rgb(7,24,14));
        back.setBackground(tintedCard(GREEN,190));
        top.addView(back,new LinearLayout.LayoutParams(dp(86),dp(44)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8),0,0,0);
        titles.addView(text("Battery & Power",26,TEXT,true));
        titles.addView(text("Battery health, charger state and kernel power limits",11,GREEN,true));
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));

        root.addView(top);

        ScrollView scroll=new ScrollView(this);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(10),0,dp(24));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        liveCard();

        section("POWER SOURCE","Live connection and charger limits reported by the AXP2202.",BLUE);
        sourceValue=settingRow("Power source","—",BLUE);
        inputLimitValue=settingRow("Input current limit","—",BLUE);
        inputMinVoltageValue=settingRow("Configured minimum input voltage","—",BLUE);
        inputTempValue=settingRow("USB / PMIC temperature","—",BLUE);

        section("BATTERY HEALTH","Useful battery capacity, health and manufacturing information.",GREEN);
        healthValue=settingRow("Health","—",GREEN);
        capacityLevelValue=settingRow("Capacity level","—",GREEN);
        chargeCurrentValue=settingRow("Charging current","—",GREEN);
        chargeCounterValue=settingRow("Charge available","—",GREEN);
        chargeFullValue=settingRow("Full-charge capacity","—",GREEN);
        designFullValue=settingRow("Design capacity","—",GREEN);
        healthEstimateValue=settingRow("Capacity health estimate","—",GREEN);
        manufacturerValue=settingRow("Battery controller","—",GREEN);
        manufactureDateValue=settingRow("Manufacture date","—",GREEN);
        cycleValue=settingRow("Cycle count","—",GREEN);

        section("TIME ESTIMATES","Android estimate where available; discharge time is calculated only when the driver exposes usable charge/current data.",YELLOW);
        timeToFullValue=settingRow("Time until full","—",YELLOW);
        timeRemainingValue=settingRow("Estimated discharge remaining","—",YELLOW);

        TextView note=text(
                "Battery and charger data are read-only. Unsupported driver properties are labeled as unavailable instead of being guessed.",
                10,MUTED,false);
        content.addView(card(note,GREEN,38),full());

        return root;
    }

    private void liveCard(){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15),dp(13),dp(15),dp(13));
        card.setBackground(blackAccentCard(GREEN));

        LinearLayout head=row();
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("LIVE BATTERY",10,GREEN,true));
        percentValue=text("—%",27,TEXT,true);
        labels.addView(percentValue);
        head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        statusValue=text("—",11,GREEN_SOFT,true);
        statusValue.setGravity(Gravity.END);
        head.addView(statusValue);
        card.addView(head);

        graph=new SparklineView(this);
        graph.setAccentColor(GREEN);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(104));
        gp.setMargins(0,dp(8),0,dp(8));
        card.addView(graph,gp);

        LinearLayout stats=row();
        tempValue=miniStat(stats,"TEMP","—");
        voltageValue=miniStat(stats,"VOLTAGE","—");
        currentValue=miniStat(stats,"CURRENT","—");
        card.addView(stats);

        content.addView(card,full());
    }

    private TextView miniStat(LinearLayout parent,String label,String initial){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4),0,dp(4),0);
        box.addView(text(label,8,MUTED,true));
        TextView v=text(initial,11,TEXT,true);
        box.addView(v);
        parent.addView(box,new LinearLayout.LayoutParams(0,-2,1));
        return v;
    }

    private TextView settingRow(String title,String initial,int accent){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13),dp(11),dp(13),dp(11));
        row.setBackground(tintedCard(accent,52));

        TextView label=text(title,13,TEXT,true);
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));

        TextView value=text(initial,11,accent,true);
        value.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        value.setTextIsSelectable(true);
        row.addView(value,new LinearLayout.LayoutParams(0,-2,1));

        content.addView(row,full());
        return value;
    }

    private void section(String title,String subtitle,int color){
        TextView t=text(title,14,color,true);
        t.setPadding(dp(2),dp(16),0,dp(2));
        content.addView(t);
        TextView s=text(subtitle,10,MUTED,false);
        s.setPadding(dp(2),0,0,dp(7));
        content.addView(s);
    }

    private void refresh(){
        Map<String,String> m=TelemetryStore.read(this);
        BatteryManager bm=(BatteryManager)getSystemService(BATTERY_SERVICE);

        int capacity=parseInt(TelemetryStore.get(m,"battery_capacity","-1"));
        if(capacity<0 && bm!=null) capacity=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        capacity=Math.max(0,Math.min(100,capacity));

        String status=TelemetryStore.get(m,"battery_status","Unknown");
        boolean plugged="1".equals(TelemetryStore.get(m,"power_online","0"));
        float temp=parseFloat(TelemetryStore.get(m,"battery_temp_c","0"));
        long voltage=parseLong(TelemetryStore.get(m,"battery_voltage","0"));
        long current=parseLong(TelemetryStore.get(m,"battery_current","0"));
        long currentAvg=parseLong(TelemetryStore.get(m,"battery_current_avg","0"));

        percentValue.setText(capacity+"%");
        statusValue.setText(status+(plugged?" • plugged in":" • battery"));
        statusValue.setTextColor(statusColor(status,temp));
        graph.addValue(capacity);

        tempValue.setText(String.format(Locale.US,"%.1f°C",temp));
        voltageValue.setText(formatVoltage(voltage));
        long chargeCurrentNow=parseLong(TelemetryStore.get(m,"battery_charge_current","0"));
        currentValue.setText(current!=0
                ? formatCurrent(current)
                : (plugged && chargeCurrentNow>0 ? "~"+chargeCurrentNow+" mA" : "—"));

        String powerType=TelemetryStore.get(m,"power_type","");
        sourceValue.setText(plugged
                ? (powerType.isEmpty()?"External power":powerType)+" • Online"
                : "Battery");

        long inputLimit=parseLong(TelemetryStore.get(m,"power_input_limit","0"));
        inputLimitValue.setText(inputLimit>0 ? inputLimit+" mA" : "Not reported");

        long minInputMv=parseLong(TelemetryStore.get(m,"power_voltage_min_design","0"));
        inputMinVoltageValue.setText(minInputMv>0
                ? String.format(Locale.US,"%.3f V",minInputMv/1000.0)
                : "Not reported");

        long inputTempRaw=parseLong(TelemetryStore.get(m,"power_temp","0"));
        inputTempValue.setText(inputTempRaw>0
                ? String.format(Locale.US,"%.1f °C",inputTempRaw/10.0)
                : "Not reported");

        healthValue.setText(orUnknown(TelemetryStore.get(m,"battery_health","")));
        capacityLevelValue.setText(orUnknown(TelemetryStore.get(m,"battery_capacity_level","")));

        long chargeCurrent=parseLong(TelemetryStore.get(m,"battery_charge_current","0"));
        chargeCurrentValue.setText(chargeCurrent>0 && plugged
                ? "~"+chargeCurrent+" mA • driver-reported"
                : (plugged?"Not reported":"Not charging"));

        long counter=parseLong(TelemetryStore.get(m,"battery_charge_counter","0"));
        long full=parseLong(TelemetryStore.get(m,"battery_charge_full","0"));
        long design=parseLong(TelemetryStore.get(m,"battery_charge_full_design","0"));
        chargeCounterValue.setText(counter>0
                ? String.format(Locale.US,"%.0f mAh • %d%%",counter/1000.0,capacity)
                : "Not reported");
        chargeFullValue.setText(full>0
                ? String.format(Locale.US,"%.0f mAh",full/1000.0)
                : "Not reported");
        designFullValue.setText(design>0
                ? String.format(Locale.US,"%.0f mAh",design/1000.0)
                : "Not reported");

        if(full>0 && design>0){
            double health=full*100.0/design;
            healthEstimateValue.setText(String.format(Locale.US,"%.1f%% of design",health));
        }else{
            healthEstimateValue.setText("Not reported");
        }

        String manufacturer=TelemetryStore.get(m,"battery_manufacturer","");
        manufacturerValue.setText(orUnknown(manufacturer));

        int year=parseInt(TelemetryStore.get(m,"battery_mfg_year","-1"));
        int month=parseInt(TelemetryStore.get(m,"battery_mfg_month","-1"));
        int day=parseInt(TelemetryStore.get(m,"battery_mfg_day","-1"));
        manufactureDateValue.setText(year>0&&month>0&&day>0
                ? String.format(Locale.US,"%04d-%02d-%02d",year,month,day)
                : "Not reported");

        int cycles=parseInt(TelemetryStore.get(m,"battery_cycle_count","-1"));
        cycleValue.setText(cycles>=0 ? String.valueOf(cycles) : "Not supported by driver");

        long timeToFullSec=parseLong(TelemetryStore.get(m,"battery_time_to_full_s","0"));
        long timeToEmptySec=parseLong(TelemetryStore.get(m,"battery_time_to_empty_s","0"));

        if(plugged && timeToFullSec>0){
            timeToFullValue.setText(formatDuration(timeToFullSec*1000L)+" • driver estimate");
        }else{
            long chargeMs=-1;
            if(Build.VERSION.SDK_INT>=28 && bm!=null){
                try{chargeMs=bm.computeChargeTimeRemaining();}catch(Exception ignored){}
            }
            timeToFullValue.setText(plugged && chargeMs>0
                    ? formatDuration(chargeMs)+" • Android estimate"
                    : (plugged?"Not reported":"Not charging"));
        }

        if(!plugged && timeToEmptySec>0){
            timeRemainingValue.setText(formatDuration(timeToEmptySec*1000L)+" • driver estimate");
        }else{
            timeRemainingValue.setText(plugged?"Charging":"Not reported");
        }
    }

    private int statusColor(String status,float temp){
        if(temp>=45f)return RED;
        if("Charging".equalsIgnoreCase(status)||"Full".equalsIgnoreCase(status))return GREEN;
        if("Discharging".equalsIgnoreCase(status))return YELLOW;
        return GREEN_SOFT;
    }

    private String formatVoltage(long raw){
        if(raw==0)return "—";
        long a=Math.abs(raw);
        if(a>=100000)return String.format(Locale.US,"%.3f V",raw/1000000.0);
        if(a>=1000)return String.format(Locale.US,"%.3f V",raw/1000.0);
        return raw+" raw";
    }

    private String formatCurrent(long raw){
        if(raw==0)return "0 mA";
        long a=Math.abs(raw);
        if(a>=10000)return String.format(Locale.US,"%+.0f mA",raw/1000.0);
        return raw+" raw";
    }

    private String formatLimit(String raw){
        if(raw==null||raw.isEmpty())return "—";
        long v=parseLong(raw);
        long a=Math.abs(v);
        if(a>=10000)return String.format(Locale.US,"%.0f mA • raw %d",v/1000.0,v);
        return raw+" raw";
    }

    private String formatLimitVoltage(String raw){
        if(raw==null||raw.isEmpty())return "—";
        long v=parseLong(raw);
        long a=Math.abs(v);
        if(a>=100000)return String.format(Locale.US,"%.3f V • raw %d",v/1000000.0,v);
        if(a>=1000)return String.format(Locale.US,"%.3f V • raw %d",v/1000.0,v);
        return raw+" raw";
    }

    private String formatEnergy(String raw){
        if(raw==null||raw.isEmpty())return "—";
        long v=parseLong(raw);
        if(v==0)return raw+" raw";
        long a=Math.abs(v);
        if(a>=1000000)return String.format(Locale.US,"%.2f Wh • raw %d",v/1000000.0,v);
        return raw+" raw";
    }

    private String formatCapacityRaw(long raw){
        if(raw==0)return "—";
        long a=Math.abs(raw);
        if(a>=10000)return String.format(Locale.US,"%.0f mAh • raw %d",raw/1000.0,raw);
        return raw+" raw";
    }

    private String formatDuration(long ms){
        long minutes=Math.max(1,ms/60000);
        long hours=minutes/60;
        long mins=minutes%60;
        if(hours>0)return hours+"h "+mins+"m";
        return mins+"m";
    }

    private String orUnknown(String s){return s==null||s.isEmpty()?"Not reported":s;}
    private String orDash(String s){return s==null||s.isEmpty()?"—":s;}
    private long parseLong(String s){try{return Long.parseLong(s.trim());}catch(Exception e){return 0;}}
    private int parseInt(String s){try{return Integer.parseInt(s.trim());}catch(Exception e){return -1;}}
    private float parseFloat(String s){try{return Float.parseFloat(s.trim());}catch(Exception e){return 0f;}}

    private LinearLayout row(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private Button button(String label,int color,View.OnClickListener listener){
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(TEXT);
        b.setBackground(tintedCard(color,100));
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout card(View child,int color,int alpha){
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(13),dp(11),dp(13),dp(11));
        c.setBackground(tintedCard(color,alpha));
        c.addView(child);
        return c;
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

    private GradientDrawable pill(int color){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(999));
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

    private LinearLayout.LayoutParams full(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(4),0,dp(4));
        return p;
    }

    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}

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
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }
}
