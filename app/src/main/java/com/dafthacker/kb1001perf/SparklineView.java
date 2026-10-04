package com.dafthacker.kb1001perf;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

public class SparklineView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Float> values = new ArrayList<>();
    private int maxSamples = 52;
    private float scaleMax = 100f;
    private int accent = Color.rgb(158,218,226);
    private ValueAnimator animator;

    public SparklineView(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(dp(2.25f));
        grid.setColor(Color.rgb(27,43,44));
        grid.setStrokeWidth(dp(1));
        setAccentColor(accent);
    }

    public void setAccentColor(int color) {
        accent = color;
        line.setColor(color);
        line.setShadowLayer(dp(5),0,0,Color.argb(80,Color.red(color),Color.green(color),Color.blue(color)));
        invalidate();
    }

    public void setScaleMax(float value) {
        scaleMax = Math.max(1f, value);
        invalidate();
    }

    public void addValue(float value) {
        final float target = clamp(value);
        if (values.isEmpty()) {
            values.add(target);
            invalidate();
            return;
        }
        final float start = values.get(values.size()-1);
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(start,target);
        animator.setDuration(420);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float v = (Float)a.getAnimatedValue();
            if (values.size() >= maxSamples) values.remove(0);
            if (values.size() > 0 && animator != null && animator.isRunning()) {
                if (values.size() > 1) values.remove(values.size()-1);
            }
            values.add(v);
            invalidate();
        });
        animator.start();
    }

    public void clear() {
        values.clear();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w=getWidth(), h=getHeight();
        if (w<=2 || h<=2) return;

        canvas.drawLine(0,h*.34f,w,h*.34f,grid);
        canvas.drawLine(0,h*.67f,w,h*.67f,grid);

        if (values.size()<2) return;

        float left=dp(1), right=w-dp(1), top=dp(5), bottom=h-dp(4);
        float step=(right-left)/Math.max(1,values.size()-1);
        float[] xs=new float[values.size()];
        float[] ys=new float[values.size()];
        for(int i=0;i<values.size();i++){
            xs[i]=left+i*step;
            ys[i]=bottom-(values.get(i)/scaleMax)*(bottom-top);
        }

        Path curve=new Path();
        curve.moveTo(xs[0],ys[0]);
        for(int i=0;i<values.size()-1;i++){
            float x0=i>0?xs[i-1]:xs[i];
            float y0=i>0?ys[i-1]:ys[i];
            float x1=xs[i], y1=ys[i];
            float x2=xs[i+1], y2=ys[i+1];
            float x3=i+2<values.size()?xs[i+2]:x2;
            float y3=i+2<values.size()?ys[i+2]:y2;

            float c1x=x1+(x2-x0)/6f;
            float c1y=y1+(y2-y0)/6f;
            float c2x=x2-(x3-x1)/6f;
            float c2y=y2-(y3-y1)/6f;
            curve.cubicTo(c1x,c1y,c2x,c2y,x2,y2);
        }

        Path area=new Path(curve);
        area.lineTo(xs[xs.length-1],bottom);
        area.lineTo(xs[0],bottom);
        area.close();
        fill.setShader(new LinearGradient(
                0,top,0,bottom,
                Color.argb(76,Color.red(accent),Color.green(accent),Color.blue(accent)),
                Color.argb(5,Color.red(accent),Color.green(accent),Color.blue(accent)),
                Shader.TileMode.CLAMP));
        canvas.drawPath(area,fill);
        fill.setShader(null);
        canvas.drawPath(curve,line);

        Paint dot=new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(accent);
        dot.setShadowLayer(dp(5),0,0,accent);
        canvas.drawCircle(xs[xs.length-1],ys[ys.length-1],dp(2.8f),dot);
    }

    private float clamp(float v) {
        return Math.max(0f,Math.min(scaleMax,v));
    }

    private float dp(float v) {
        return v*getResources().getDisplayMetrics().density;
    }
}
