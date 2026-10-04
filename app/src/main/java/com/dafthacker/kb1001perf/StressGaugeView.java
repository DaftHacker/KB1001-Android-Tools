package com.dafthacker.kb1001perf;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public final class StressGaugeView extends View {
    private final Paint track=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float value;
    private String center="—";
    private String label="THERMAL";

    public StressGaugeView(Context context){
        super(context);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(dp(14));
        track.setColor(Color.rgb(30,43,43));

        valuePaint.setStyle(Paint.Style.STROKE);
        valuePaint.setStrokeCap(Paint.Cap.ROUND);
        valuePaint.setStrokeWidth(dp(14));

        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setGauge(float percent,String centerText,String labelText){
        value=Math.max(0f,Math.min(100f,percent));
        center=centerText==null?"—":centerText;
        label=labelText==null?"THERMAL":labelText;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float w=getWidth(),h=getHeight();
        float size=Math.min(w,h)*.78f;
        float left=(w-size)/2f, top=(h-size)/2f+dp(8);
        RectF arc=new RectF(left,top,left+size,top+size);

        canvas.drawArc(arc,140,260,false,track);

        int color;
        if(value<60f) color=Color.rgb(77,210,126);
        else if(value<78f) color=Color.rgb(255,202,64);
        else color=Color.rgb(255,83,79);
        valuePaint.setColor(color);
        valuePaint.setShadowLayer(dp(7),0,0,Color.argb(100,Color.red(color),Color.green(color),Color.blue(color)));
        canvas.drawArc(arc,140,260*(value/100f),false,valuePaint);
        valuePaint.clearShadowLayer();

        text.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
        text.setTextSize(dp(27));
        text.setColor(Color.rgb(238,245,243));
        canvas.drawText(center,w/2f,h*.57f,text);

        text.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.NORMAL));
        text.setTextSize(dp(10));
        text.setColor(Color.rgb(149,170,168));
        canvas.drawText(label,w/2f,h*.69f,text);
    }

    private float dp(float v){
        return v*getResources().getDisplayMetrics().density;
    }
}
