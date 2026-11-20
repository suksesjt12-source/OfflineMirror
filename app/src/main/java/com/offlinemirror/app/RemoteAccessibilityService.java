package com.offlinemirror.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public class RemoteAccessibilityService extends AccessibilityService {
    private static volatile RemoteAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Object gamepadDispatcher;

    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* No screen content is read. */ }
    @Override public void onInterrupt() { releaseGamepadState(); }
    @Override public void onDestroy() {
        releaseGamepadState();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isEnabled() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && instance != null;
    }

    public static void dispatchRemoteGesture(int type, float x1, float y1,
                                             float x2, float y2, int durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        RemoteAccessibilityService service = instance;
        if (service != null) service.mainHandler.post(
                () -> Api24GestureDispatcher.dispatch(service, type, x1, y1, x2, y2, durationMs));
    }

    /** Touch emulation only; it does not create a system-wide HID gamepad. */
    public static void dispatchRemoteGamepadState(int buttons, float axisX, float axisY) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        RemoteAccessibilityService service = instance;
        if (service != null) service.mainHandler.post(
                () -> service.updateGamepadState(buttons, axisX, axisY));
    }

    private void updateGamepadState(int buttons, float axisX, float axisY) {
        if (gamepadDispatcher == null) gamepadDispatcher = new Api24GamepadDispatcher(this);
        ((Api24GamepadDispatcher) gamepadDispatcher).update(buttons, axisX, axisY);
    }

    private void releaseGamepadState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || gamepadDispatcher == null) return;
        mainHandler.post(() -> ((Api24GamepadDispatcher) gamepadDispatcher).release());
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    private static final class Api24GestureDispatcher {
        static void dispatch(RemoteAccessibilityService service, int type,
                             float x1, float y1, float x2, float y2, int durationMs) {
            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager wm = (WindowManager) service.getSystemService(WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            display.getRealMetrics(metrics);
            float startX = clamp(x1) * metrics.widthPixels;
            float startY = clamp(y1) * metrics.heightPixels;
            float endX = clamp(x2) * metrics.widthPixels;
            float endY = clamp(y2) * metrics.heightPixels;
            Path path = new Path();
            path.moveTo(startX, startY);
            if (type == 3) path.lineTo(endX, endY);
            else path.lineTo(startX + 1f, startY + 1f);
            long duration = Math.max(60, Math.min(1200, durationMs));
            long gestureDuration = type == 2 ? Math.max(600, duration) : duration;
            service.dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, gestureDuration))
                    .build(), null, null);
        }
    }

    private static final class Api24GamepadDispatcher {
        private static final int A=1, B=2, X=4, Y=8, L1=16, R1=32, SELECT=64, START=128,
                L2=256, R2=512, UP=1024, DOWN=2048, LEFT=4096, RIGHT=8192;
        private static final int ACTION_MASK=A|B|X|Y|L1|R1|SELECT|START|L2|R2;
        private static final int[] BITS={A,B,X,Y,L1,R1,SELECT,START,L2,R2};
        private static final float[][] POS={
                {0.88f,0.87f},{0.94f,0.76f},{0.82f,0.76f},{0.88f,0.65f},
                {0.08f,0.10f},{0.92f,0.10f},{0.43f,0.10f},{0.57f,0.10f},
                {0.20f,0.10f},{0.80f,0.10f}
        };

        private final RemoteAccessibilityService service;
        private int lastButtons, pendingTaps;
        private float axisX, axisY, activeX, activeY;
        private boolean inFlight;
        private GestureDescription.StrokeDescription activeStick;

        Api24GamepadDispatcher(RemoteAccessibilityService service) { this.service=service; }

        void update(int buttons,float x,float y) {
            pendingTaps |= (buttons & ~lastButtons) & ACTION_MASK;
            lastButtons=buttons;
            x=axis(x); y=axis(y);
            if((buttons&LEFT)!=0)x-=1f;
            if((buttons&RIGHT)!=0)x+=1f;
            if((buttons&UP)!=0)y-=1f;
            if((buttons&DOWN)!=0)y+=1f;
            float mag=(float)Math.hypot(x,y);
            if(mag>1f){x/=mag;y/=mag;}
            axisX=axis(x); axisY=axis(y);
            dispatchNext();
        }

        void release() { axisX=axisY=0f; lastButtons=0; pendingTaps=0; dispatchNext(); }

        private void dispatchNext() {
            if(inFlight)return;
            DisplayMetrics m=new DisplayMetrics();
            WindowManager wm=(WindowManager)service.getSystemService(WINDOW_SERVICE);
            Display d=wm.getDefaultDisplay(); d.getRealMetrics(m);
            float shortSide=Math.min(m.widthPixels,m.heightPixels);
            float cx=m.widthPixels*.16f, cy=m.heightPixels*.80f, radius=shortSide*.10f;
            boolean moving=Math.hypot(axisX,axisY)>.12d;
            float tx=cx+axisX*radius, ty=cy+axisY*radius;
            GestureDescription.Builder builder=new GestureDescription.Builder();
            GestureDescription.StrokeDescription nextStick=null;
            boolean keep=false, any=false;

            if(Build.VERSION.SDK_INT>=26) {
                if(moving) {
                    Path path=new Path();
                    if(activeStick==null)path.moveTo(cx,cy); else path.moveTo(activeX,activeY);
                    path.lineTo(tx,ty);
                    nextStick=activeStick==null
                            ? new GestureDescription.StrokeDescription(path,0,90,true)
                            : activeStick.continueStroke(path,0,90,true);
                    keep=true; builder.addStroke(nextStick); any=true;
                } else if(activeStick!=null) {
                    Path path=new Path(); path.moveTo(activeX,activeY);
                    path.lineTo(activeX+.5f,activeY+.5f);
                    nextStick=activeStick.continueStroke(path,0,45,false);
                    builder.addStroke(nextStick); any=true;
                }
            } else if(moving) {
                Path path=new Path(); path.moveTo(cx,cy); path.lineTo(tx,ty);
                builder.addStroke(new GestureDescription.StrokeDescription(path,0,120));
                any=true;
            }

            int sentTaps=pendingTaps;
            for(int i=0;i<BITS.length;i++) {
                if((sentTaps&BITS[i])==0)continue;
                float px=m.widthPixels*POS[i][0], py=m.heightPixels*POS[i][1];
                Path path=new Path(); path.moveTo(px,py); path.lineTo(px+1f,py+1f);
                builder.addStroke(new GestureDescription.StrokeDescription(path,0,65));
                any=true;
            }
            if(!any)return;

            inFlight=true;
            final GestureDescription.StrokeDescription submitted=nextStick;
            final boolean submittedKeep=keep;
            final int submittedTaps=sentTaps;
            boolean accepted=service.dispatchGesture(builder.build(),
                    new GestureDescription.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription description) {
                    inFlight=false; pendingTaps &= ~submittedTaps;
                    if(submittedKeep&&submitted!=null) {
                        activeStick=submitted; activeX=tx; activeY=ty;
                    } else if(submitted!=null) activeStick=null;
                    dispatchNext();
                }
                @Override public void onCancelled(GestureDescription description) {
                    inFlight=false; pendingTaps &= ~submittedTaps;
                    activeStick=null; axisX=axisY=0f;
                }
            },service.mainHandler);
            if(!accepted){inFlight=false;pendingTaps &= ~sentTaps;activeStick=null;}
        }

        private float axis(float value) {
            if(Float.isNaN(value)||Float.isInfinite(value))return 0f;
            return Math.max(-1f,Math.min(1f,value));
        }
    }
}
