package com.offlinemirror.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Individually movable touch controls which emit standard Bluetooth HID gamepad state. */
public final class GamepadOverlayView extends View {
    public interface Listener {
        void onGamepadState(int buttons, float leftX, float leftY, float rightX, float rightY);
    }
    public interface LayoutChangedListener {
        void onElementPositionChanged(int elementId, float offsetX, float offsetY);
    }

    public static final int BUTTON_A=1, BUTTON_B=2, BUTTON_X=4, BUTTON_Y=8;
    public static final int BUTTON_L1=16, BUTTON_R1=32, BUTTON_L2=64, BUTTON_R2=128;
    public static final int BUTTON_SELECT=256, BUTTON_START=512;
    public static final int BUTTON_DPAD_UP=1024, BUTTON_DPAD_DOWN=2048;
    public static final int BUTTON_DPAD_LEFT=4096, BUTTON_DPAD_RIGHT=8192;
    public static final int BUTTON_L3=16384, BUTTON_R3=32768;
    public static final int ELEMENT_LEFT_STICK=-1, ELEMENT_RIGHT_STICK=-2;
    public static final int[] LAYOUT_ELEMENT_IDS={
        BUTTON_A,BUTTON_B,BUTTON_X,BUTTON_Y,BUTTON_L1,BUTTON_L2,BUTTON_R1,BUTTON_R2,
        BUTTON_SELECT,BUTTON_START,BUTTON_DPAD_UP,BUTTON_DPAD_DOWN,BUTTON_DPAD_LEFT,
        BUTTON_DPAD_RIGHT,BUTTON_L3,BUTTON_R3,ELEMENT_LEFT_STICK,ELEMENT_RIGHT_STICK
    };

    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final long MIN_BUTTON_PRESS_MS = 0L;
    private final Map<Integer,Integer> buttonPointers=new HashMap<>();
    private final Map<Integer,Long> buttonDownTimes=new HashMap<>();
    private final Map<Integer,Runnable> delayedButtonReleases=new HashMap<>();
    private final Map<Integer,float[]> offsets=new HashMap<>();
    private Listener listener;
    private LayoutChangedListener layoutListener;
    private float sensitivity=1f;
    private float deadzone=0.04f;
    private int responseCurve=0;
    private int layoutPreset;
    private int buttons;
    private int leftPointer=-1,rightPointer=-1,editPointer=-1,editElement;
    private float leftX,leftY,rightX,rightY,editLastX,editLastY;
    private boolean editMode;

    public GamepadOverlayView(Context c){super(c);init();}
    public GamepadOverlayView(Context c,AttributeSet a){super(c,a);init();}
    private void init(){
        setLayerType(View.LAYER_TYPE_HARDWARE,null);
        fill.setStyle(Paint.Style.FILL); stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2)); label.setColor(0xFFF4F7FC);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        setClickable(true);
        setContentDescription("Virtual gamepad. Mode LAYOUT dapat memindahkan setiap tombol satu per satu.");
    }
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    public void setListener(Listener l){listener=l;}
    public void setLayoutChangedListener(LayoutChangedListener l){layoutListener=l;}
    public void setSensitivity(float v){sensitivity=clamp(v,0.5f,1.8f);}
    public void setDeadzone(float v){deadzone=clamp(v,0f,0.20f);}
    /** 0=linear, 1=dynamic, 2=precision. */
    public void setResponseCurve(int curve){responseCurve=Math.max(0,Math.min(2,curve));}
    public void setLayoutPreset(int p){layoutPreset=Math.max(0,Math.min(2,p));invalidate();}
    public void setLayoutEditMode(boolean enabled){
        editMode=enabled;editPointer=-1;editElement=0;
        if(enabled)releaseAllInputs();invalidate();
    }
    private float[] offset(int id){
        float[] v=offsets.get(id);return v==null?new float[]{0f,0f}:v;
    }
    private float[] basePoint(int id){
        if(id==ELEMENT_LEFT_STICK)return new float[]{baseLeftX(),stickY()};
        if(id==ELEMENT_RIGHT_STICK)return new float[]{baseRightX(),stickY()};
        if(id==BUTTON_L3)return new float[]{baseLeftX(),stickY()-dp(62)};
        if(id==BUTTON_R3)return new float[]{baseRightX(),stickY()-dp(62)};
        if(isDpad(id)){
            float x=baseDpadX(),y=baseDpadY(),o=dpadRadius()*2.35f;
            if(id==BUTTON_DPAD_LEFT)x-=o;
            if(id==BUTTON_DPAD_RIGHT)x+=o;
            if(id==BUTTON_DPAD_UP)y-=o;
            if(id==BUTTON_DPAD_DOWN)y+=o;
            return new float[]{x,y};
        }
        return new float[]{baseButtonX(id),baseButtonY(id)};
    }
    private float[] point(int id){
        float[] b=basePoint(id),o=offset(id);
        return new float[]{b[0]+o[0]*getWidth(),b[1]+o[1]*getHeight()};
    }
    public void setElementOffset(int id,float x,float y){
        if(getWidth()>0&&getHeight()>0){
            float[] b=basePoint(id);float m=dp(24);
            float px=clamp(b[0]+x*getWidth(),m,getWidth()-m);
            float py=clamp(b[1]+y*getHeight(),m,getHeight()-m);
            x=(px-b[0])/getWidth();y=(py-b[1])/getHeight();
        }
        offsets.put(id,new float[]{x,y});invalidate();
    }
    public float getElementOffsetX(int id){return offset(id)[0];}
    public float getElementOffsetY(int id){return offset(id)[1];}
    private boolean landscape(){return getWidth()>getHeight();}
    private boolean compact(){return layoutPreset==1;}
    private boolean leftHanded(){return layoutPreset==2;}
    private boolean isDpad(int b){return b==BUTTON_DPAD_UP||b==BUTTON_DPAD_DOWN||b==BUTTON_DPAD_LEFT||b==BUTTON_DPAD_RIGHT;}
    private float baseLeftX(){return getWidth()*(leftHanded()?0.82f:(landscape()?0.14f:0.18f));}
    private float baseRightX(){return getWidth()*(leftHanded()?(landscape()?0.32f:0.48f):(landscape()?0.66f:0.52f));}
    private float stickY(){return getHeight()*(landscape()?0.82f:0.84f);}
    private float baseDpadX(){return baseLeftX();}
    private float baseDpadY(){return getHeight()*(landscape()?0.39f:0.41f);}
    private float scale(){return compact()?0.78f:1f;}
    private float dpadRadius(){return Math.min(dp(19)*scale(),Math.min(getWidth(),getHeight())*0.048f);}
    private float stickRadius(){return Math.min(dp(44)*scale(),Math.min(getWidth(),getHeight())*0.125f);}
    private float actionRadius(){return Math.min(dp(21)*scale(),Math.min(getWidth(),getHeight())*0.052f);}
    private float baseButtonX(int bit){
        float x;
        if(bit==BUTTON_A||bit==BUTTON_Y)x=getWidth()*(landscape()?0.88f:0.84f);
        else if(bit==BUTTON_B)x=getWidth()*0.94f;
        else if(bit==BUTTON_X)x=getWidth()*(landscape()?0.82f:0.76f);
        else if(bit==BUTTON_L1)x=getWidth()*0.10f;
        else if(bit==BUTTON_L2)x=getWidth()*0.27f;
        else if(bit==BUTTON_SELECT)x=getWidth()*(landscape()?0.40f:0.39f);
        else if(bit==BUTTON_START)x=getWidth()*(landscape()?0.60f:0.61f);
        else if(bit==BUTTON_R2)x=getWidth()*0.73f;
        else x=getWidth()*0.90f;
        return leftHanded()?getWidth()-x:x;
    }
    private float baseButtonY(int bit){
        if(landscape()){
            if(bit==BUTTON_Y)return getHeight()*0.65f;
            if(bit==BUTTON_A)return getHeight()*0.87f;
            if(bit==BUTTON_X||bit==BUTTON_B)return getHeight()*0.76f;
            return getHeight()*0.10f;
        }
        if(bit==BUTTON_Y)return getHeight()*0.56f;
        if(bit==BUTTON_X||bit==BUTTON_B)return getHeight()*0.65f;
        if(bit==BUTTON_A)return getHeight()*0.74f;
        return getHeight()*0.08f;
    }
    private float leftCX(){return point(ELEMENT_LEFT_STICK)[0];}
    private float rightCX(){return point(ELEMENT_RIGHT_STICK)[0];}
    private float stickCY(){return point(ELEMENT_LEFT_STICK)[1];}
    private float rightStickCY(){return point(ELEMENT_RIGHT_STICK)[1];}
    private float bx(int bit){return point(bit)[0];}
    private float by(int bit){return point(bit)[1];}
    private float dx(int bit){return point(bit)[0];}
    private float dy(int bit){return point(bit)[1];}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(getWidth()<=0||getHeight()<=0)return;
        drawStick(c,leftCX(),stickCY(),leftX,leftY,"L");
        drawStick(c,rightCX(),rightStickCY(),rightX,rightY,"R");
        drawDpad(c);
        drawAction(c,BUTTON_Y,"Y");drawAction(c,BUTTON_X,"X");
        drawAction(c,BUTTON_B,"B");drawAction(c,BUTTON_A,"A");
        drawPill(c,BUTTON_L1,"L1");drawPill(c,BUTTON_L2,"L2");
        drawPill(c,BUTTON_SELECT,"SELECT");drawPill(c,BUTTON_START,"START");
        drawPill(c,BUTTON_R2,"R2");drawPill(c,BUTTON_R1,"R1");
        drawStickClick(c,BUTTON_L3,"L3");drawStickClick(c,BUTTON_R3,"R3");
        if(editMode){
            stroke.setColor(0xFF75B9FF);stroke.setStrokeWidth(dp(2));
            if(editElement!=0){float[] p=point(editElement);c.drawCircle(p[0],p[1],dp(28),stroke);}
            label.setColor(0xFF8CC8FF);
            text(c,"SERET TIAP TOMBOL / ANALOG SENDIRI",getWidth()/2f,dp(24),dp(10));
            label.setColor(0xFFF4F7FC);
        }
    }
    private void text(Canvas c,String s,float x,float y,float size){label.setTextSize(size);c.drawText(s,x,y,label);}
    private void drawStick(Canvas c,float x,float y,float ax,float ay,String name){
        float r=stickRadius();fill.setColor(0x61303C50);c.drawCircle(x,y,r,fill);
        stroke.setColor(0xDCE2EAF5);c.drawCircle(x,y,r,stroke);
        float kx=x+ax*r*0.66f,ky=y+ay*r*0.66f;
        fill.setColor(0xA9303C50);c.drawCircle(kx,ky,r*0.39f,fill);
        stroke.setColor(0xE7EAF0F7);c.drawCircle(kx,ky,r*0.39f,stroke);
        text(c,name,kx,ky+dp(5),dp(15));
    }
    private void drawDpad(Canvas c){
        int[] bits={BUTTON_DPAD_UP,BUTTON_DPAD_DOWN,BUTTON_DPAD_LEFT,BUTTON_DPAD_RIGHT};
        String[] names={"↑","↓","←","→"};float r=dpadRadius();
        for(int i=0;i<bits.length;i++){
            int bit=bits[i];float x=dx(bit),y=dy(bit);
            fill.setColor((buttons&bit)!=0?0xD44B8CC8:0x71303C50);c.drawCircle(x,y,r,fill);
            stroke.setColor(0xE7EAF0F7);c.drawCircle(x,y,r,stroke);
            text(c,names[i],x,y+dp(5),dp(17));
        }
    }
    private void drawAction(Canvas c,int bit,String name){
        float x=bx(bit),y=by(bit),r=actionRadius();
        fill.setColor((buttons&bit)!=0?0xD44B8CC8:0x71303C50);c.drawCircle(x,y,r,fill);
        stroke.setColor(0xE7EAF0F7);c.drawCircle(x,y,r,stroke);
        text(c,name,x,y+dp(5),dp(14));
    }
    private void drawPill(Canvas c,int bit,String name){
        float x=bx(bit),y=by(bit);
        float w=(bit==BUTTON_SELECT||bit==BUTTON_START)?Math.min(dp(27),getWidth()*0.035f):Math.min(dp(21),getWidth()*0.028f);
        float h=dp(14);
        fill.setColor((buttons&bit)!=0?0xD44B8CC8:0x71303C50);
        c.drawRoundRect(x-w,y-h,x+w,y+h,dp(10),dp(10),fill);
        stroke.setColor(0xE7EAF0F7);c.drawRoundRect(x-w,y-h,x+w,y+h,dp(10),dp(10),stroke);
        text(c,name,x,y+dp(4),dp(10));
    }
    private void drawStickClick(Canvas c,int bit,String name){
        float[] p=point(bit);float r=dp(13)*scale();
        fill.setColor((buttons&bit)!=0?0xD44B8CC8:0xC1263448);c.drawCircle(p[0],p[1],r,fill);
        stroke.setColor(0xF0E2EAF5);c.drawCircle(p[0],p[1],r,stroke);
        text(c,name,p[0],p[1]+dp(3),dp(8));
    }

    private int hitDpad(float x,float y) {
        int[] directions = {
                BUTTON_DPAD_UP, BUTTON_DPAD_DOWN,
                BUTTON_DPAD_LEFT, BUTTON_DPAD_RIGHT
        };
        float limit = dpadRadius() * 1.12f;
        float bestDistance = limit * limit;
        int best = 0;
        for (int bit : directions) {
            float deltaX = x - dx(bit);
            float deltaY = y - dy(bit);
            float distance = deltaX * deltaX + deltaY * deltaY;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = bit;
            }
        }
        return best;
    }

    private int hitButton(float x,float y){
        // One touch resolves to the nearest single direction, never two.
        int dpad = hitDpad(x, y);
        if (dpad != 0) return dpad;
        int[] acts={BUTTON_A,BUTTON_B,BUTTON_X,BUTTON_Y};
        float ar=actionRadius()*1.18f;
        for(int bit:acts){float a=x-bx(bit),b=y-by(bit);if(a*a+b*b<=ar*ar)return bit;}
        int[] tops={BUTTON_L1,BUTTON_L2,BUTTON_R1,BUTTON_R2,BUTTON_SELECT,BUTTON_START};
        for(int bit:tops){
            float hw=(bit==BUTTON_SELECT||bit==BUTTON_START)?Math.min(dp(24),getWidth()*0.032f):Math.min(dp(17),getWidth()*0.026f);
            float hh=Math.min(dp(14),getHeight()*0.045f);
            if(Math.abs(x-bx(bit))<=hw&&Math.abs(y-by(bit))<=hh)return bit;
        }
        float r=dp(13)*scale();
        float[] l=point(BUTTON_L3),rr=point(BUTTON_R3);
        float a=x-l[0],b=y-l[1];if(a*a+b*b<=r*r)return BUTTON_L3;
        a=x-rr[0];b=y-rr[1];if(a*a+b*b<=r*r)return BUTTON_R3;
        return 0;
    }
    private int hitEditElement(float x,float y){
        int bit=hitButton(x,y);if(bit!=0)return bit;
        float r=stickRadius()+dp(18),a=x-leftCX(),b=y-stickCY();
        if(a*a+b*b<=r*r)return ELEMENT_LEFT_STICK;
        a=x-rightCX();b=y-rightStickCY();
        if(a*a+b*b<=r*r)return ELEMENT_RIGHT_STICK;
        return 0;
    }
    private void updateStick(float x,float y,boolean right){
        float cx=right?rightCX():leftCX();
        float ax=(x-cx)/Math.max(1f,stickRadius()*0.90f)*sensitivity;
        float ay=(y-(right?rightStickCY():stickCY()))/Math.max(1f,stickRadius()*0.90f)*sensitivity;
        float d=(float)Math.hypot(ax,ay),dead=deadzone;
        if(d<=dead){ax=ay=0f;}else{
            float n=Math.min(1f,(d-dead)/(1f-dead));
            if(responseCurve==1)n=n*n*(3f-2f*n);
            else if(responseCurve==2)n=n*n;
            ax=ax/d*n;ay=ay/d*n;
        }
        if(right){rightX=clamp(ax,-1,1);rightY=clamp(ay,-1,1);}
        else{leftX=clamp(ax,-1,1);leftY=clamp(ay,-1,1);}
    }
    private void rebuildMask(){int m=0;for(Integer bit:buttonPointers.values())m|=bit;buttons=m;}
    private void emit(){
        postInvalidateOnAnimation();
        if(listener!=null)listener.onGamepadState(buttons,leftX,leftY,rightX,rightY);
    }
    private boolean press(MotionEvent e,int i){
        int id=e.getPointerId(i);
        Runnable oldRelease=delayedButtonReleases.remove(id);
        if(oldRelease!=null){
            removeCallbacks(oldRelease);
            if(buttonPointers.remove(id)!=null){
                buttonDownTimes.remove(id);
                rebuildMask();emit();
            }
        }
        if(buttonPointers.containsKey(id)||id==leftPointer||id==rightPointer)return true;
        float x=e.getX(i),y=e.getY(i);int bit=hitButton(x,y);
        if(bit!=0){
            buttonPointers.put(id,bit);
            buttonDownTimes.put(id,SystemClock.uptimeMillis());
            rebuildMask();emit();return true;
        }
        float r=stickRadius()+dp(18),dy=y-stickCY(),dx=x-leftCX();
        if(leftPointer==-1&&dx*dx+dy*dy<=r*r){leftPointer=id;updateStick(x,y,false);emit();return true;}
        dx=x-rightCX();dy=y-rightStickCY();
        if(rightPointer==-1&&dx*dx+dy*dy<=r*r){rightPointer=id;updateStick(x,y,true);emit();return true;}
        return false;
    }
    private void release(MotionEvent e,int i){
        int id=e.getPointerId(i);
        Integer bit=buttonPointers.get(id);
        if(bit!=null){
            Long downAt=buttonDownTimes.get(id);
            long held=downAt==null?MIN_BUTTON_PRESS_MS:
                    Math.max(0L,SystemClock.uptimeMillis()-downAt);
            long remaining=MIN_BUTTON_PRESS_MS-held;
            if(remaining>0L){
                Runnable release=()->{
                    Runnable current=delayedButtonReleases.get(id);
                    if(current==null)return;
                    delayedButtonReleases.remove(id);
                    if(buttonPointers.get(id)!=null && buttonPointers.get(id).equals(bit)){
                        buttonPointers.remove(id);
                        buttonDownTimes.remove(id);
                        rebuildMask();emit();
                    }
                };
                delayedButtonReleases.put(id,release);
                postDelayed(release,remaining);
            }else{
                buttonPointers.remove(id);buttonDownTimes.remove(id);
                rebuildMask();emit();
            }
        }
        if(id==leftPointer){leftPointer=-1;leftX=leftY=0;emit();}
        if(id==rightPointer){rightPointer=-1;rightX=rightY=0;emit();}
    }
    public void releaseAllInputs(){
        for(Runnable release:delayedButtonReleases.values())removeCallbacks(release);
        delayedButtonReleases.clear();
        buttonPointers.clear();buttonDownTimes.clear();buttons=0;leftPointer=rightPointer=-1;
        leftX=leftY=rightX=rightY=0;emit();
    }
    private boolean layoutTouch(MotionEvent e){
        int action=e.getActionMasked(),i=e.getActionIndex();
        if(action==MotionEvent.ACTION_DOWN){
            editPointer=e.getPointerId(i);editElement=hitEditElement(e.getX(i),e.getY(i));
            editLastX=e.getX(i);editLastY=e.getY(i);invalidate();return editElement!=0;
        }
        if(action==MotionEvent.ACTION_MOVE&&editPointer!=-1){
            int idx=e.findPointerIndex(editPointer);
            if(idx>=0&&getWidth()>0&&getHeight()>0){
                float x=e.getX(idx),y=e.getY(idx),oldX=getElementOffsetX(editElement),oldY=getElementOffsetY(editElement);
                setElementOffset(editElement,oldX+(x-editLastX)/getWidth(),oldY+(y-editLastY)/getHeight());
                editLastX=x;editLastY=y;
                if(layoutListener!=null)layoutListener.onElementPositionChanged(editElement,getElementOffsetX(editElement),getElementOffsetY(editElement));
            }return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP||action==MotionEvent.ACTION_CANCEL){editPointer=-1;editElement=0;invalidate();}
        return true;
    }
    private void cleanupPointers(MotionEvent e){
        boolean changed=false;Iterator<Map.Entry<Integer,Integer>> it=buttonPointers.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<Integer,Integer> v=it.next();
            if(e.findPointerIndex(v.getKey())<0 && !delayedButtonReleases.containsKey(v.getKey())){
                it.remove();buttonDownTimes.remove(v.getKey());changed=true;
            }
        }
        if(leftPointer!=-1&&e.findPointerIndex(leftPointer)<0){leftPointer=-1;leftX=leftY=0;changed=true;}
        if(rightPointer!=-1&&e.findPointerIndex(rightPointer)<0){rightPointer=-1;rightX=rightY=0;changed=true;}
        if(changed){rebuildMask();emit();}
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);if(oldW>0||oldH>0)releaseAllInputs();}
    @Override public boolean onTouchEvent(MotionEvent e){
        if(editMode)return layoutTouch(e);
        int a=e.getActionMasked(),i=e.getActionIndex();
        if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN)
            return press(e,i)||leftPointer!=-1||rightPointer!=-1||!buttonPointers.isEmpty();
        if(a==MotionEvent.ACTION_MOVE){
            cleanupPointers(e);
            if(leftPointer!=-1){int n=e.findPointerIndex(leftPointer);if(n>=0)updateStick(e.getX(n),e.getY(n),false);}
            if(rightPointer!=-1){int n=e.findPointerIndex(rightPointer);if(n>=0)updateStick(e.getX(n),e.getY(n),true);}
            if(leftPointer!=-1||rightPointer!=-1)emit();
            return leftPointer!=-1||rightPointer!=-1||!buttonPointers.isEmpty();
        }
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP){release(e,i);return true;}
        if(a==MotionEvent.ACTION_CANCEL){releaseAllInputs();return true;}
        return leftPointer!=-1||rightPointer!=-1||!buttonPointers.isEmpty();
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){releaseAllInputs();super.onDetachedFromWindow();}
}
