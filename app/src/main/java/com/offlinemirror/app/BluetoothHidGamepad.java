package com.offlinemirror.app;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import java.util.concurrent.Executor;

/** Composite Bluetooth HID gamepad + relative mouse peripheral. */
public final class BluetoothHidGamepad {
    public interface StatusListener { void onStatus(String message); }
    private static final String TAG="OfflineMirror-BluetoothHID";
    private static final int GAMEPAD_REPORT_ID=1, MOUSE_REPORT_ID=2;
    private static final byte[] REPORT_DESCRIPTOR=new byte[] {
        0x05,0x01,0x09,0x05,(byte)0xA1,0x01,(byte)0x85,0x01,
        0x05,0x09,0x09,0x01,0x09,0x02,0x09,0x04,0x09,0x05,
        0x09,0x07,0x09,0x08,0x09,0x09,0x09,0x0A,0x09,0x0B,0x09,0x0C,
        0x15,0x00,0x25,0x01,0x75,0x01,(byte)0x95,0x0A,(byte)0x81,0x02,
        0x75,0x06,(byte)0x95,0x01,(byte)0x81,0x03,
        0x05,0x01,0x09,0x39,0x15,0x00,0x25,0x08,
        0x35,0x00,0x46,0x3B,0x01,0x65,0x14,0x75,0x04,(byte)0x95,0x01,(byte)0x81,0x42,
        0x75,0x04,(byte)0x95,0x01,(byte)0x81,0x03,
        0x09,0x30,0x09,0x31,0x09,0x32,0x09,0x35,
        0x15,0x00,0x26,(byte)0xFF,0x00,0x75,0x08,(byte)0x95,0x04,(byte)0x81,0x02,
        0x05,0x02,0x09,(byte)0xC5,0x09,(byte)0xC4,
        0x15,0x00,0x26,(byte)0xFF,0x00,0x75,0x08,(byte)0x95,0x02,(byte)0x81,0x02,
        (byte)0xC0,
        0x05,0x01,0x09,0x02,(byte)0xA1,0x01,(byte)0x85,0x02,
        0x09,0x01,(byte)0xA1,0x00,0x05,0x09,0x19,0x01,0x29,0x03,
        0x15,0x00,0x25,0x01,0x75,0x01,(byte)0x95,0x03,(byte)0x81,0x02,
        0x75,0x05,(byte)0x95,0x01,(byte)0x81,0x03,
        0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,
        0x15,(byte)0x81,0x25,0x7F,0x75,0x08,(byte)0x95,0x03,(byte)0x81,0x06,
        (byte)0xC0,(byte)0xC0
    };

    private final Context context;
    private final StatusListener listener;
    private final Object reportLock=new Object();
    private volatile BluetoothHidDevice hidDevice;
    private volatile BluetoothDevice target;
    private volatile boolean registered,connected;
    private HandlerThread reportThread;
    private Handler reportHandler;
    private byte[] pendingGamepadReport,pendingMouseReport;
    private int lastQueuedButtons=-1,lastQueuedMouseButtons=-1;
    private final Runnable sendLatestGamepadReport=()->sendPendingReport(GAMEPAD_REPORT_ID,true);
    private final Runnable sendLatestMouseReport=()->sendPendingReport(MOUSE_REPORT_ID,false);

    public BluetoothHidGamepad(Context context,StatusListener listener) {
        this.context=context.getApplicationContext(); this.listener=listener;
    }
    public void start(BluetoothDevice targetDevice) {
        if(Build.VERSION.SDK_INT<28){status("Bluetooth HID membutuhkan Android 9+");return;}
        target=targetDevice;
        try {
            android.bluetooth.BluetoothAdapter adapter=android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if(adapter==null||!adapter.isEnabled()){status("Bluetooth belum aktif");return;}
            ensureReportWorker();
            boolean requested=adapter.getProfileProxy(context,profileListener,BluetoothProfile.HID_DEVICE);
            if(!requested)status("Bluetooth HID Device tidak didukung firmware HP ini");
            else status("Mengaktifkan Bluetooth HID gamepad + mouse...");
        } catch(SecurityException denied){status("Izin Bluetooth tidak diberikan");}
        catch(Exception error){status("Bluetooth HID gagal dibuka: "+error.getMessage());Log.e(TAG,"Could not open HID profile",error);}
    }
    private void ensureReportWorker() {
        synchronized(reportLock) {
            if(reportThread!=null&&reportThread.isAlive())return;
            reportThread=new HandlerThread("OfflineMirror-HID-Reports",Process.THREAD_PRIORITY_DISPLAY);
            reportThread.start(); reportHandler=new Handler(reportThread.getLooper());
        }
    }
    private final BluetoothProfile.ServiceListener profileListener=new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile,BluetoothProfile proxy) {
            if(profile!=BluetoothProfile.HID_DEVICE||!(proxy instanceof BluetoothHidDevice)){status("Profil Bluetooth HID tidak tersedia");return;}
            hidDevice=(BluetoothHidDevice)proxy;
            BluetoothHidDeviceAppSdpSettings sdp=new BluetoothHidDeviceAppSdpSettings(
                "OfflineMirror Gamepad + Mouse","OfflineMirror Composite Bluetooth Controller",
                "OfflineMirror",BluetoothHidDevice.SUBCLASS2_GAMEPAD,REPORT_DESCRIPTOR);
            Executor executor=context.getMainExecutor();
            boolean accepted=hidDevice.registerApp(sdp,null,null,executor,callback);
            status(accepted?(target==null?"HID siap dipair dari Bluetooth Host...":"Mendaftarkan gamepad + mouse Bluetooth..."):"Pendaftaran Bluetooth HID ditolak sistem");
        }
        @Override public void onServiceDisconnected(int profile){connected=false;registered=false;hidDevice=null;status("Layanan Bluetooth HID terputus");}
    };
    private final BluetoothHidDevice.Callback callback=new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice,boolean isRegistered) {
            registered=isRegistered;
            if(!isRegistered){connected=false;status("Bluetooth HID tidak terdaftar");return;}
            if(target==null)status("HID siap • pair OfflineMirror dari Bluetooth Host");
            else {
                status("HID siap • menyambungkan ke Host...");
                try{if(hidDevice!=null&&!hidDevice.connect(target))status("Koneksi ditolak • periksa pairing Bluetooth Host");}
                catch(SecurityException denied){status("Izin Bluetooth Connect diperlukan");}
            }
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device,int state) {
            connected=state==BluetoothProfile.STATE_CONNECTED;
            if(connected){target=device;status("Bluetooth terhubung • gamepad + mouse aktif");sendState(0,0f,0f,0f,0f);sendMouse(0,0,0,0);}
            else if(state==BluetoothProfile.STATE_CONNECTING)status("Bluetooth HID sedang menyambungkan...");
            else if(state==BluetoothProfile.STATE_DISCONNECTED)status("Bluetooth HID terputus • sambungkan ulang dari Viewer");
        }
        @Override public void onGetReport(BluetoothDevice device,byte type,byte id,int bufferSize) {
            if(hidDevice!=null&&type==BluetoothHidDevice.REPORT_TYPE_INPUT)try{hidDevice.replyReport(device,type,id,neutralReport(id));}
            catch(SecurityException ignored){}
        }
    };
    /** Android-standard axes: left X/Y, right Z/Rz, and analog trigger axes. */
    public void sendState(int buttons,float leftX,float leftY,float rightX,float rightY) {
        if(!connected||reportHandler==null)return;
        byte[] report=buildGamepadReport(buttons,leftX,leftY,rightX,rightY);
        boolean edge=buttons!=lastQueuedButtons;lastQueuedButtons=buttons;
        synchronized(reportLock){pendingGamepadReport=report;reportHandler.removeCallbacks(sendLatestGamepadReport);if(edge)reportHandler.post(sendLatestGamepadReport);else reportHandler.postDelayed(sendLatestGamepadReport,8L);}
    }
    /** Relative mouse report. Button bits: left, right, middle. */
    public void sendMouse(int dx,int dy,int buttons,int wheel) {
        if(!connected||reportHandler==null)return;
        byte[] report={(byte)(buttons&7),(byte)clampSignedByte(dx),(byte)clampSignedByte(dy),(byte)clampSignedByte(wheel)};
        boolean edge=(buttons&7)!=lastQueuedMouseButtons;lastQueuedMouseButtons=buttons&7;
        synchronized(reportLock){pendingMouseReport=report;reportHandler.removeCallbacks(sendLatestMouseReport);if(edge)reportHandler.post(sendLatestMouseReport);else reportHandler.postDelayed(sendLatestMouseReport,2L);}
    }
    private void sendPendingReport(int reportId,boolean gamepad) {
        byte[] report;BluetoothHidDevice device;BluetoothDevice receiver;
        synchronized(reportLock){if(gamepad){report=pendingGamepadReport;pendingGamepadReport=null;}else{report=pendingMouseReport;pendingMouseReport=null;}device=hidDevice;receiver=target;}
        if(report==null||!connected||device==null||receiver==null)return;
        try{if(!device.sendReport(receiver,reportId,report))Log.w(TAG,"Bluetooth stack rejected HID report "+reportId);}
        catch(SecurityException denied){status("Izin Bluetooth Connect diperlukan");}
        catch(Exception error){Log.w(TAG,"HID input report failed",error);}
    }
    private byte[] buildGamepadReport(int buttons,float lx,float ly,float rx,float ry) {
        byte[] report=new byte[9];int mask=buttons&0x03FF;
        report[0]=(byte)(mask&0xFF);report[1]=(byte)((mask>>>8)&3);
        report[2]=(byte)hatValue(buttons);
        report[3]=toAxis(lx);report[4]=toAxis(ly);report[5]=toAxis(rx);report[6]=toAxis(ry);
        report[7]=(byte)((buttons&(1<<8))!=0?255:0);
        report[8]=(byte)((buttons&(1<<9))!=0?255:0);
        return report;
    }
    private byte[] neutralReport(int id) {
        if(id==MOUSE_REPORT_ID)return new byte[]{0,0,0,0};
        return new byte[]{0,0,8,(byte)128,(byte)128,(byte)128,(byte)128,0,0};
    }
    // Standard hat values: 0=up, clockwise by 45 degrees, 8=neutral.
    private int hatValue(int b) {
        boolean u=(b&(1<<10))!=0,d=(b&(1<<11))!=0,l=(b&(1<<12))!=0,r=(b&(1<<13))!=0;
        if(u&&r)return 1;if(r&&d)return 3;if(d&&l)return 5;if(l&&u)return 7;
        if(u)return 0;if(r)return 2;if(d)return 4;if(l)return 6;return 8;
    }
    private byte toAxis(float v) {
        if(Float.isNaN(v)||Float.isInfinite(v))v=0f;
        int n=Math.round((Math.max(-1f,Math.min(1f,v))+1f)*127.5f);
        return (byte)Math.max(0,Math.min(255,n));
    }
    private int clampSignedByte(int v){return Math.max(-127,Math.min(127,v));}
    public void stop() {
        try {
            if(connected&&hidDevice!=null&&target!=null){
                hidDevice.sendReport(target,GAMEPAD_REPORT_ID,neutralReport(GAMEPAD_REPORT_ID));
                hidDevice.sendReport(target,MOUSE_REPORT_ID,neutralReport(MOUSE_REPORT_ID));
            }
            if(hidDevice!=null){if(registered)hidDevice.unregisterApp();android.bluetooth.BluetoothAdapter a=android.bluetooth.BluetoothAdapter.getDefaultAdapter();if(a!=null)a.closeProfileProxy(BluetoothProfile.HID_DEVICE,hidDevice);}
        } catch(Exception error){Log.w(TAG,"Error stopping HID controller",error);}
        finally {
            connected=false;registered=false;hidDevice=null;target=null;
            synchronized(reportLock){if(reportHandler!=null)reportHandler.removeCallbacksAndMessages(null);if(reportThread!=null)reportThread.quitSafely();reportHandler=null;reportThread=null;pendingGamepadReport=null;pendingMouseReport=null;lastQueuedButtons=-1;lastQueuedMouseButtons=-1;}
        }
    }
    private void status(String message){if(listener!=null)listener.onStatus(message);}
}
