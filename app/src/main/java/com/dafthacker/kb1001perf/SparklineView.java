package com.dafthacker.kb1001perf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;

public class SparklineView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Deque<Float> values = new ArrayDeque<>();
    private int maxSamples = 60;
    private float scaleMax = 100f;

    public SparklineView(Context context) {
        super(context);
        line.setColor(Color.rgb(158,218,226));
        line.setStrokeWidth(dp(2));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);

        grid.setColor(Color.rgb(28,49,47));
        grid.setStrokeWidth(dp(1));
    }

    public void setScaleMax(float value) {
        scaleMax = Math.max(1f, value);
        invalidate();
    }

    public void addValue(float value) {
        if (values.size() >= maxSamples) values.removeFirst();
        values.addLast(Math.max(0f, value));
        invalidate();
    }

    public void clear() {
        values.clear();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 1 || h <= 1) return;

        canvas.drawLine(0,h-1,w,h-1,grid);
        canvas.drawLine(0,h/2f,w,h/2f,grid);

        if (values.size() < 2) return;

        float step = (float)w / Math.max(1, values.size()-1);
        float prevX = 0f;
        float prevY = h - clamp(values.peekFirst()) / scaleMax * h;
        int i = 0;

        for (Float value : values) {
            float x = i * step;
            float y = h - clamp(value) / scaleMax * h;
            if (i > 0) canvas.drawLine(prevX,prevY,x,y,line);
            prevX = x;
            prevY = y;
            i++;
        }
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(scaleMax, value));
    }

    private float dp(float x) {
        return x * getResources().getDisplayMetrics().density;
    }
}
