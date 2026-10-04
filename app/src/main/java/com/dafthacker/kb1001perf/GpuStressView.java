package com.dafthacker.kb1001perf;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class GpuStressView extends GLSurfaceView {
    public interface FpsListener { void onFps(float fps); }

    private final StressRenderer renderer;
    private volatile float lastFps;

    public GpuStressView(Context context){
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer=new StressRenderer();
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void setFpsListener(FpsListener listener){
        renderer.listener=listener;
    }

    public float getLastFps(){
        return lastFps;
    }

    public void startStress(){
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void stopStress(){
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        requestRender();
    }

    private final class StressRenderer implements Renderer {
        private final FloatBuffer vertices;
        private int program;
        private int aPos;
        private int uTime;
        private int uResolution;
        private int width=1,height=1;
        private long startNs;
        private long fpsWindowNs;
        private int frames;
        private FpsListener listener;

        StressRenderer(){
            float[] data={-1f,-1f, 3f,-1f, -1f,3f};
            ByteBuffer bb=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder());
            vertices=bb.asFloatBuffer();
            vertices.put(data).position(0);
        }

        @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
            String vs=
                    "attribute vec2 aPos;"+
                    "void main(){ gl_Position=vec4(aPos,0.0,1.0); }";

            String fs=
                    "precision mediump float;"+
                    "uniform float uTime;"+
                    "uniform vec2 uResolution;"+
                    "void main(){"+
                    " vec2 uv=(gl_FragCoord.xy/uResolution.xy)*2.0-1.0;"+
                    " uv.x*=uResolution.x/max(uResolution.y,1.0);"+
                    " vec2 p=uv;"+
                    " float acc=0.0;"+
                    " for(int i=0;i<28;i++){"+
                    "  float d=max(dot(p,p),0.16);"+
                    "  p=abs(p)/d-vec2(0.72,0.63);"+
                    "  acc+=sin((p.x+p.y)*3.7+uTime*0.9)*0.018;"+
                    " }"+
                    " vec3 c=0.52+0.48*cos(vec3(0.0,2.1,4.2)+acc*9.0+uTime*0.35);"+
                    " gl_FragColor=vec4(c,1.0);"+
                    "}";

            program=link(vs,fs);
            aPos=GLES20.glGetAttribLocation(program,"aPos");
            uTime=GLES20.glGetUniformLocation(program,"uTime");
            uResolution=GLES20.glGetUniformLocation(program,"uResolution");
            startNs=System.nanoTime();
            fpsWindowNs=startNs;
        }

        @Override public void onSurfaceChanged(GL10 gl,int w,int h){
            width=Math.max(1,w);
            height=Math.max(1,h);
            GLES20.glViewport(0,0,width,height);
        }

        @Override public void onDrawFrame(GL10 gl){
            long now=System.nanoTime();
            float time=(now-startNs)/1_000_000_000f;

            GLES20.glUseProgram(program);
            GLES20.glUniform1f(uTime,time);
            GLES20.glUniform2f(uResolution,width,height);
            GLES20.glEnableVertexAttribArray(aPos);
            vertices.position(0);
            GLES20.glVertexAttribPointer(aPos,2,GLES20.GL_FLOAT,false,0,vertices);

            // Multiple full-screen passes intentionally keep the Mali busy.
            for(int i=0;i<4;i++){
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,3);
            }
            GLES20.glDisableVertexAttribArray(aPos);

            frames++;
            long span=now-fpsWindowNs;
            if(span>=500_000_000L){
                lastFps=frames*1_000_000_000f/span;
                frames=0;
                fpsWindowNs=now;
                FpsListener cb=listener;
                if(cb!=null){
                    final float fps=lastFps;
                    post(() -> cb.onFps(fps));
                }
            }
        }

        private int compile(int type,String src){
            int shader=GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader,src);
            GLES20.glCompileShader(shader);
            return shader;
        }

        private int link(String vs,String fs){
            int v=compile(GLES20.GL_VERTEX_SHADER,vs);
            int f=compile(GLES20.GL_FRAGMENT_SHADER,fs);
            int p=GLES20.glCreateProgram();
            GLES20.glAttachShader(p,v);
            GLES20.glAttachShader(p,f);
            GLES20.glLinkProgram(p);
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            return p;
        }
    }
}
