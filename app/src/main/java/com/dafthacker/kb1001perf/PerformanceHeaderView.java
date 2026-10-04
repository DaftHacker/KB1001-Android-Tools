package com.dafthacker.kb1001perf;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public final class PerformanceHeaderView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public PerformanceHeaderView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        text.setColor(Color.rgb(242,247,246));
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(dp(28));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(dp(2.2f));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        paint.setShader(new LinearGradient(
                0, 0, w, h,
                new int[]{Color.rgb(10,18,20), Color.rgb(13,36,37), Color.rgb(8,15,18)},
                new float[]{0f,.54f,1f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0,0,w,h,dp(20),dp(20),paint);
        paint.setShader(null);

        paint.setColor(Color.argb(30, 62, 209, 120));
        canvas.drawCircle(w*.82f, h*.16f, h*.48f, paint);
        paint.setColor(Color.argb(32, 255, 145, 54));
        canvas.drawCircle(w*.95f, h*.75f, h*.52f, paint);
        paint.setColor(Color.argb(22, 255, 215, 64));
        canvas.drawCircle(w*.62f, h*.92f, h*.38f, paint);

        drawWave(canvas, w, h*.60f, Color.rgb(75,214,128), 0f);
        drawWave(canvas, w, h*.68f, Color.rgb(255,143,52), 1.3f);
        drawWave(canvas, w, h*.76f, Color.rgb(255,210,62), 2.5f);
        drawWave(canvas, w, h*.84f, Color.rgb(91,205,223), 3.7f);

        paint.setColor(Color.argb(135,255,255,255));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawRoundRect(dp(.5f),dp(.5f),w-dp(.5f),h-dp(.5f),dp(20),dp(20),paint);
        paint.setStyle(Paint.Style.FILL);

        canvas.drawText("Performance Manager", dp(20), dp(42), text);

        Paint subtitle = new Paint(text);
        subtitle.setTextSize(dp(11));
        subtitle.setColor(Color.rgb(161,188,187));
        subtitle.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        canvas.drawText("Live system performance • tuning • game profiles", dp(21), dp(63), subtitle);
    }

    private void drawWave(Canvas canvas, float w, float y, int color, float phase) {
        path.reset();
        float amp = dp(6);
        path.moveTo(dp(18), y);
        float usable = w - dp(36);
        int points = 8;
        for (int i=1;i<=points;i++) {
            float x = dp(18) + usable * i / points;
            float px = dp(18) + usable * (i-.5f) / points;
            float yy = (float)(y + Math.sin(i*.95f + phase) * amp);
            float pyy = (float)(y + Math.sin((i-.5f)*.95f + phase) * amp);
            path.quadTo(px, pyy, x, yy);
        }
        line.setColor(color);
        line.setAlpha(175);
        line.setShadowLayer(dp(6),0,0,Color.argb(90,Color.red(color),Color.green(color),Color.blue(color)));
        canvas.drawPath(path,line);
        line.clearShadowLayer();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
