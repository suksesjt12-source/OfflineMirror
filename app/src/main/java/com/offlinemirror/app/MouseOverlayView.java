package com.offlinemirror.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Transparent full-screen direct-touch mouse overlay. */
public final class MouseOverlayView extends View {
    public interface Listener { void onMouseState(int dx,int dy,int buttons,int wheel); }
    private final Paint cursorFill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorOutline=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private int primary=-1,secondary=-1,upSensitivity=1,downSensitivity=1;
    private float lastX,lastY,downX,downY,cursorX,cursorY,lastCenterY,remainder;
    private long downTime;
    private boolean positioned,twoFinger,moved;

    public MouseOverlayView(Context c){super(c);init();}
    public MouseOverlayView(Context c,AttributeSet a){super(c,a);init();}
    private void init(){
        setLayerType(View.LAYER_TYPE_HARDWARE,null);setBackgroundColor(0x00000000);
        setWillNotDraw(false);setClickable(true);
        setContentDescription("Mouse langsung: geser layar untuk memindahkan cursor, ketuk untuk klik, dua jari untuk scroll.");
        cursorFill.setColor(0xFFF9FBFF);cursorFill.setStyle(Paint.Style.FILL);
        cursorOutline.setColor(0xE6000000);cursorOutline.setStyle(Paint.Style.STROKE);
        cursorOutline.setStrokeWidth(dp(2));cursorOutline.setStrokeJoin(Paint.Join.ROUND);
    }
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    public void setListener(Listener l){listener=l;}
    public void setScrollSensitivity(int up,int down){upSensitivity=Math.max(1,Math.min(10,up));downSensitivity=Math.max(1,Math.min(10,down));}
    private void emit(int x,int y,int b,int w){if(listener!=null)listener.onMouseState(x,y,b,w);}
    private void click(int button){emit(0,0,button,0);emit(0,0,0,0);performClick();}
    private void moveTo(float x,float y,boolean send){
        if(getWidth()<=0||getHeight()<=0)return;
        if(!positioned){cursorX=getWidth()*0.5f;cursorY=getHeight()*0.5f;positioned=true;}
        float m=dp(2),nx=Math.max(m,Math.min(getWidth()-m,x)),ny=Math.max(m,Math.min(getHeight()-m,y));
        int dx=Math.round(nx-cursorX),dy=Math.round(ny-cursorY);cursorX=nx;cursorY=ny;
        if(send&&(dx!=0||dy!=0))emit(dx,dy,0,0);postInvalidateOnAnimation();
    }
    private void moveBy(float dx,float dy){
        if(!positioned){cursorX=getWidth()*0.5f;cursorY=getHeight()*0.5f;positioned=true;}
        moveTo(cursorX+dx,cursorY+dy,true);
    }
    private void wheel(float deltaY){
        if(Math.abs(deltaY)<0.1f)return;
        float wheelDelta=-deltaY;
        int sensitivity=wheelDelta>=0?upSensitivity:downSensitivity;
        remainder+=wheelDelta/Math.max(dp(30),dp(34))*sensitivity;
        int steps=(int)remainder;
        if(steps!=0){remainder-=steps;emit(0,0,0,steps);}
    }
    private void press(MotionEvent e,int i){
        int id=e.getPointerId(i);float x=e.getX(i),y=e.getY(i);
        if(primary==-1){
            primary=id;downX=lastX=x;downY=lastY=y;downTime=SystemClock.elapsedRealtime();
            moved=false;twoFinger=false;remainder=0;moveTo(x,y,true);return;
        }
        if(secondary==-1&&id!=primary){
            secondary=id;twoFinger=true;remainder=0;
            int p=e.findPointerIndex(primary);
            lastCenterY=p>=0?(e.getY(p)+y)*0.5f:y;
        }
    }
    private void move(MotionEvent e){
        if(primary==-1)return;
        if(twoFinger&&secondary!=-1){
            int a=e.findPointerIndex(primary),b=e.findPointerIndex(secondary);
            if(a<0||b<0)return;
            float center=(e.getY(a)+e.getY(b))*0.5f,delta=center-lastCenterY;
            lastCenterY=center;if(Math.abs(delta)>dp(2))moved=true;wheel(delta);return;
        }
        int i=e.findPointerIndex(primary);if(i<0)return;
        float x=e.getX(i),y=e.getY(i);
        if(Math.hypot(x-downX,y-downY)>dp(7))moved=true;
        float dx=x-lastX,dy=y-lastY;lastX=x;lastY=y;moveBy(dx,dy);
    }
    private void release(MotionEvent e,int i){
        int id=e.getPointerId(i);
        if(twoFinger&&(id==primary||id==secondary)){primary=secondary=-1;twoFinger=false;remainder=0;return;}
        if(id==primary){
            long held=SystemClock.elapsedRealtime()-downTime;
            if(!moved&&Math.hypot(lastX-downX,lastY-downY)<=dp(10)){
                if(held<=450)click(1);else if(held>=600)click(2);
            }
            primary=-1;
        }else if(id==secondary)secondary=-1;
    }
    public void releaseAllInputs(){primary=secondary=-1;twoFinger=false;remainder=0;emit(0,0,0,0);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(!positioned||getWidth()<=0||getHeight()<=0)return;
        float s=dp(22);Path p=new Path();p.moveTo(cursorX,cursorY);
        p.lineTo(cursorX,cursorY+s);p.lineTo(cursorX+s*0.27f,cursorY+s*0.73f);
        p.lineTo(cursorX+s*0.48f,cursorY+s*1.12f);p.lineTo(cursorX+s*0.64f,cursorY+s*1.03f);
        p.lineTo(cursorX+s*0.43f,cursorY+s*0.66f);p.lineTo(cursorX+s*0.88f,cursorY+s*0.66f);p.close();
        c.drawPath(p,cursorFill);c.drawPath(p,cursorOutline);
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);positioned=false;}
    @Override public boolean onTouchEvent(MotionEvent e){
        int a=e.getActionMasked(),i=e.getActionIndex();
        if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){press(e,i);return true;}
        if(a==MotionEvent.ACTION_MOVE){move(e);return true;}
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP){release(e,i);return true;}
        if(a==MotionEvent.ACTION_CANCEL){releaseAllInputs();return true;}
        if(a==MotionEvent.ACTION_SCROLL){wheel(-e.getAxisValue(MotionEvent.AXIS_VSCROLL)*dp(30));return true;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){releaseAllInputs();super.onDetachedFromWindow();}
}
