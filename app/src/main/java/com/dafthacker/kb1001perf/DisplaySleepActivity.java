package com.dafthacker.kb1001perf;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import java.util.Locale;

public final class DisplaySleepActivity extends Activity {
    private static final int BLUE=Color.rgb(102,163,255);
    private static final int PURPLE=Color.rgb(190,112,255);
    private static final int GREEN=Color.rgb(77,210,126);
    private static final int TEXT=Color.rgb(236,244,242);
    private static final int MUTED=Color.rgb(154,173,171);

    private TextView timeoutValue;
    private TextView writeStatus;
    private Switch keepAwakeSwitch;
    private SeekBar timeoutSlider;

    private static final int[] TIMEOUTS={
            60_000,120_000,300_000,600_000,1_800_000
    };
    private static final String[] TIMEOUT_LABELS={
            "1 min","2 min","5 min","10 min","30 min"
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setContentView(buildUi());
        if(!Settings.System.canWrite(this))openWriteSettings();
    }

    private View buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_app);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){
                Insets bars=insets.getInsets(
                        WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
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
        Button back=button("← Back",BLUE,v->finish());
        back.setTextColor(Color.rgb(7,16,30));
        top.addView(back,new LinearLayout.LayoutParams(dp(86),dp(44)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8),0,0,0);
        titles.addView(text("Display & Sleep",26,TEXT,true));
        titles.addView(text("Screen timeout and app wake behavior",11,BLUE,true));
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top);

        ScrollView scroll=new ScrollView(this);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(10),0,dp(24));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        section(content,"SCREEN TIMEOUT",
                "Changes Android's system screen-off timeout.",BLUE);

        LinearLayout status=card(BLUE);
        timeoutValue=text("Current timeout: —",15,TEXT,true);
        writeStatus=text("",10,MUTED,false);
        writeStatus.setPadding(0,dp(3),0,0);
        status.addView(timeoutValue);
        status.addView(writeStatus);
        content.addView(status,full());

        timeoutSlider=new SeekBar(this);
        timeoutSlider.setMax(TIMEOUTS.length-1);
        timeoutSlider.setKeyProgressIncrement(1);
        timeoutSlider.setPadding(dp(8),dp(10),dp(8),0);
        timeoutSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
                int i=Math.max(0,Math.min(TIMEOUT_LABELS.length-1,progress));
                timeoutValue.setText("Screen timeout: "+TIMEOUT_LABELS[i]);
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){
                int i=Math.max(0,Math.min(TIMEOUTS.length-1,bar.getProgress()));
                setSystemTimeout(TIMEOUTS[i]);
            }
        });
        content.addView(timeoutSlider,new LinearLayout.LayoutParams(-1,dp(54)));

        LinearLayout ticks=row();
        for(String label:TIMEOUT_LABELS){
            TextView tick=text(label,9,MUTED,false);
            tick.setGravity(Gravity.CENTER);
            ticks.addView(tick,new LinearLayout.LayoutParams(0,-2,1));
        }
        content.addView(ticks,new LinearLayout.LayoutParams(-1,-2));

        section(content,"KB1001 APP",
                "Controls only this app and does not change the global Android timeout.",GREEN);

        keepAwakeSwitch=toggleCard(
                "Keep screen awake while KB1001 is open",
                "Useful while monitoring graphs or tuning. Stress tests always stay awake while actively running.");
        keepAwakeSwitch.setChecked(DisplaySleepPolicy.keepAwakeInApp(this));
        keepAwakeSwitch.setOnCheckedChangeListener((b,checked)->{
            DisplaySleepPolicy.setKeepAwakeInApp(this,checked);
            DisplaySleepPolicy.apply(this,false);
        });
        content.addView((View)keepAwakeSwitch.getParent(),full());

        TextView note=text(
                "After a stress test finishes, normal app/system sleep behavior resumes. " +
                        "There is no separate stress-result wake override.",
                10,MUTED,false);
        content.addView(card(note,GREEN),full());

        return root;
    }

    private void setSystemTimeout(int millis){
        if(!Settings.System.canWrite(this)){
            openWriteSettings();
            return;
        }
        boolean ok=Settings.System.putInt(
                getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT,
                millis);
        if(!ok){
            Toast.makeText(this,"Android did not accept the timeout change.",Toast.LENGTH_LONG).show();
        }
        refreshState();
    }

    private void openWriteSettings(){
        try{
            startActivity(new Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:"+getPackageName())));
        }catch(Exception e){
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void refreshState(){
        long timeout=Settings.System.getLong(
                getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT,
                30_000L);
        int nearest=0;
        long best=Long.MAX_VALUE;
        for(int i=0;i<TIMEOUTS.length;i++){
            long d=Math.abs(timeout-TIMEOUTS[i]);
            if(d<best){best=d;nearest=i;}
        }
        timeoutValue.setText("Screen timeout: "+TIMEOUT_LABELS[nearest]);
        if(timeoutSlider!=null && timeoutSlider.getProgress()!=nearest){
            timeoutSlider.setProgress(nearest);
        }
        boolean canWrite=Settings.System.canWrite(this);
        writeStatus.setText(canWrite
                ? "System timeout control enabled"
                : "Permission required to change Android's system timeout");
        writeStatus.setTextColor(canWrite?GREEN:MUTED);

        if(keepAwakeSwitch!=null){
            keepAwakeSwitch.setChecked(DisplaySleepPolicy.keepAwakeInApp(this));
        }
        DisplaySleepPolicy.apply(this,false);
    }

    private String formatTimeout(long ms){
        if(ms<60_000)return Math.max(1,ms/1000)+" sec";
        long min=ms/60_000;
        if(min<60)return min+" min";
        return String.format(Locale.US,"%.1f hr",ms/3600000.0);
    }

    private Switch toggleCard(String title,String subtitle){
        LinearLayout wrapper=card(GREEN);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,14,TEXT,true));
        labels.addView(text(subtitle,10,MUTED,false));
        wrapper.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        Switch sw=new Switch(this);
        wrapper.addView(sw);
        return sw;
    }

    private void section(LinearLayout parent,String title,String subtitle,int color){
        TextView t=text(title,14,color,true);
        t.setPadding(dp(2),dp(16),0,dp(2));
        parent.addView(t);
        TextView s=text(subtitle,10,MUTED,false);
        s.setPadding(dp(2),0,0,dp(7));
        parent.addView(s);
    }

    private LinearLayout card(int color){
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(13),dp(11),dp(13),dp(11));
        c.setBackground(tintedCard(color,52));
        return c;
    }

    private LinearLayout card(View child,int color){
        LinearLayout c=card(color);
        c.addView(child);
        return c;
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

    private Button button(String label,int color,View.OnClickListener listener){
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(TEXT);
        b.setBackground(tintedCard(color,105));
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

    private LinearLayout row(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams full(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(4),0,dp(4));
        return p;
    }

    private int dp(float value){
        return Math.round(value*getResources().getDisplayMetrics().density);
    }

    @Override protected void onResume(){
        super.onResume();
        refreshState();
    }
}
