package com.offlinemirror.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

/** Local trackpad surface for a Bluetooth HID relative mouse. */
public final class MouseOverlayView extends View {
    public interface Listener { void onMouseState(int dx,int dy,int buttons,int wheel); }
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer,Integer> clickPointers=new HashMap<>();
    private Listener listener;
    private int mousePointer=-1;
    private float lastX,lastY;
    private int buttons;

    public MouseOverlayView(Context c){super(c);init();}
    public MouseOverlayView(Context c,AttributeSet a){super(c,a);init();}
    private void init(){
        setLayerType(View.LAYER_TYPE_HARDWARE,null);fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(dp(1.5f));
        text.setColor(0xFFF3F6FC);text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));setClickable(true);
    }
    public void setListener(Listener l){listener=l;}
    public void releaseAllInputs(){clickPointers.clear();buttons=0;mousePointer=-1;emit(0,0,0);}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private float padRight(){return getWidth()*0.68f;}
    private void emit(int dx,int dy,int wheel){if(listener!=null)listener.onMouseState(dx,dy,buttons,wheel);postInvalidateOnAnimation();}
    private void rebuildButtons(){int n=0;for(Integer b:clickPointers.values())n|=b;buttons=n;}
    private void pressPointer(MotionEvent e,int i){
        int id=e.getPointerId(i);float x=e.getX(i),y=e.getY(i);
        if(x>=padRight()){clickPointers.put(id,y<getHeight()*0.5f?1:2);rebuildButtons();emit(0,0,0);}
        else if(mousePointer==-1){mousePointer=id;lastX=x;lastY=y;}
    }
    private void releasePointer(MotionEvent e,int i){
        int id=e.getPointerId(i);
        if(clickPointers.remove(id)!=null){rebuildButtons();emit(0,0,0);}
        if(id==mousePointer)mousePointer=-1;
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
        fill.setColor(0xCC172333);c.drawRoundRect(0,0,w,h,dp(12),dp(12),fill);
        stroke.setColor(0xDCE2EAF5);c.drawRoundRect(dp(1),dp(1),w-dp(1),h-dp(1),dp(12),dp(12),stroke);
        float split=padRight();fill.setColor(0x8037475E);
        c.drawRoundRect(dp(5),dp(5),split-dp(4),h-dp(5),dp(9),dp(9),fill);
        text.setTextSize(dp(11));c.drawText("MOUSE PAD",(split-dp(4))/2f,h/2f+dp(4),text);
        float left=split+dp(2),mid=h/2f;
        fill.setColor((buttons&1)!=0?0xD9538DCB:0xA6374A63);
        c.drawRoundRect(left,dp(5),w-dp(5),mid-dp(2),dp(7),dp(7),fill);
        fill.setColor((buttons&2)!=0?0xD9538DCB:0xA6374A63);
        c.drawRoundRect(left,mid+dp(2),w-dp(5),h-dp(5),dp(7),dp(7),fill);
        text.setTextSize(dp(10));c.drawText("LEFT",(left+w-dp(5))/2f,h*0.27f,text);c.drawText("RIGHT",(left+w-dp(5))/2f,h*0.77f,text);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        int a=e.getActionMasked(),i=e.getActionIndex();
        if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){pressPointer(e,i);return true;}
        if(a==MotionEvent.ACTION_MOVE){
            if(mousePointer!=-1){int p=e.findPointerIndex(mousePointer);if(p>=0){float x=e.getX(p),y=e.getY(p);int dx=Math.round((x-lastX)*1.65f),dy=Math.round((y-lastY)*1.65f);lastX=x;lastY=y;if(dx!=0||dy!=0)emit(dx,dy,0);}}
            return true;
        }
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP){releasePointer(e,i);return true;}
        if(a==MotionEvent.ACTION_CANCEL){releaseAllInputs();return true;}
        if(a==MotionEvent.ACTION_SCROLL){int wheel=-Math.round(e.getAxisValue(MotionEvent.AXIS_VSCROLL));if(wheel!=0)emit(0,0,wheel);return true;}
        return true;
    }
}
