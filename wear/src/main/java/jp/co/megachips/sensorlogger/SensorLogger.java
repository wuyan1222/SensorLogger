package jp.co.megachips.sensorlogger;

import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.support.wearable.activity.WearableActivity;
import android.support.wearable.view.WatchViewStub;
import android.widget.TextView;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.SensorEventListener;
import android.hardware.SensorEvent;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.io.File;
import java.io.IOException;

public class SensorLogger extends WearableActivity implements Runnable, SensorEventListener {

    private final int SamplingPeriodUs = 10 * 1000;
    private final int MaxReportLatencyUs = 0 * 1000 * 1000;
    private int mOverFlow = 0;

    private final Thread mThread = new Thread(this);
    private TextView mTextView = null;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWL;
    private SensorManager mSensorManager;
    private BufferedWriter mBW = null;

    private int mAcclType = 0;
    private int mMagnType = 0;
    private int mGyroType = 0;
    private long mCounter = 0;

    private long c = 0;

    class TargetSensorType {
        public int type;
        public boolean wakeUp;
        TargetSensorType(int type, boolean wakeUp) { this.type = type; this.wakeUp = wakeUp; }
    }

    private final TargetSensorType[] mAcclPriorList = {
            new TargetSensorType(Sensor.TYPE_ACCELEROMETER, true),
            new TargetSensorType(Sensor.TYPE_ACCELEROMETER, false),
    };

    private final TargetSensorType[] mMagnPriorList = {
            new TargetSensorType(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, true),
            new TargetSensorType(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, false),
            new TargetSensorType(Sensor.TYPE_MAGNETIC_FIELD, true),
            new TargetSensorType(Sensor.TYPE_MAGNETIC_FIELD, false),
    };

    private final TargetSensorType[] mGyroPriorList = {
            new TargetSensorType(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, true),
            new TargetSensorType(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, false),
            new TargetSensorType(Sensor.TYPE_GYROSCOPE, true),
            new TargetSensorType(Sensor.TYPE_GYROSCOPE, false),
    };

    private int SensorRegsit(TargetSensorType[] list, int max_report_latency_us) {
        for(TargetSensorType type: list){
            Sensor sensor;
            sensor = mSensorManager.getDefaultSensor(type.type, type.wakeUp);
            if(sensor != null) {
                mSensorManager.registerListener(this, sensor, SamplingPeriodUs, max_report_latency_us);
                return type.type;
            }
        }
        return 0;
    }

    public class SensorData {
        public long timestamp = 0;
        public final float[] values = new float[3];
    }
    class SensorEventQueue {
        private SensorData[] mRingData = null;
        private int mDepth;
        private int mNum;
        private int mTop, mBot;
        SensorEventQueue(int depth) {
            mDepth = depth;
            mRingData = new SensorData[depth];
            for(int i = 0; i < mDepth; i++){
                mRingData[i] = new SensorData();
            }
        }
        synchronized boolean push(SensorData data) {
            boolean ret = true;
            if(mNum == mDepth){
                ret = false;
            }
            else if(data != null){
                mRingData[mBot].timestamp = data.timestamp;
                mRingData[mBot].values[0] = data.values[0];
                mRingData[mBot].values[1] = data.values[1];
                mRingData[mBot].values[2] = data.values[2];
                mNum++; mBot++;
                if(mDepth <= mBot){
                    mBot = 0;
                }
            }
            return ret;
        }
        synchronized int size() { return mNum; }
        synchronized boolean peek(SensorData data) {
            boolean ret = true;
            if(mNum <= 0){
                mNum = 0;
                ret = false;
            }
            else if(data != null){
                data.timestamp = mRingData[mTop].timestamp;
                data.values[0] = mRingData[mTop].values[0];
                data.values[1] = mRingData[mTop].values[1];
                data.values[2] = mRingData[mTop].values[2];
            }
            return ret;
        }
        synchronized boolean pop(SensorData data) {
            boolean ret = peek(data);
            if(ret){
                mNum--; mTop++;
                if(mDepth <= mTop){
                    mTop = 0;
                }
            }
            return ret;
        }
    }
    SensorEventQueue mAcclQueue = null;
    SensorEventQueue mMagnQueue = null;
    SensorEventQueue mGyroQueue = null;
    SensorData mQueueR = null;
    SensorData mQueueW = null;

    public void renameFile(){
        if(null != mBW){
            try {
                mBW.close();
                mBW = null;
            }catch (IOException e){
            }
        }
        if (isExternalStorageWritable()) {
            SimpleDateFormat form = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String fname = form.format(new Date());
            File root = android.os.Environment.getExternalStorageDirectory();
            File dir = new File(root.getAbsolutePath() + "/SensorLogger");
            File file = new File(dir, fname + ".txt");
            try {
                mBW = new BufferedWriter(new FileWriter(file));
            }
            catch (IOException e) {
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setAmbientEnabled();
        setContentView(R.layout.activity_sensor_logger);
        mPowerManager = (PowerManager)getSystemService(POWER_SERVICE);
        mWL = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorLogger");
        mWL.acquire();
        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mTextView = (TextView) stub.findViewById(R.id.text);
                String str = "";
                str += "Accl: ";
                str += (mAcclType == 0) ? "false\n" : "true\n";
                str += "Magn: ";
                str += (mMagnType == 0) ? "false\n" : "true\n";
                str += "Gyro: ";
                str += (mGyroType == 0) ? "false\n" : "true\n";
                mTextView.setText(str);
            }
        });
        if(isExternalStorageWritable()) {
            SimpleDateFormat form = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String fname = form.format( new Date() );
            File root = android.os.Environment.getExternalStorageDirectory();
            File dir = new File (root.getAbsolutePath() + "/SensorLogger");
            dir.mkdir();
            File file = new File (dir, fname + ".txt");
            try {
                mBW = new BufferedWriter(new FileWriter(file));
            }
            catch (IOException e) {
            }
        }
        if(mSensorManager != null) {
            mThread.setPriority(Thread.MAX_PRIORITY);
            mThread.start();
            // create Queue
            mAcclQueue = new SensorEventQueue(100);
            mMagnQueue = new SensorEventQueue(100);
            mGyroQueue = new SensorEventQueue(100);
            mQueueR = new SensorData();
            mQueueW = new SensorData();
            // Regist sensors
            mAcclType = SensorRegsit(mAcclPriorList, MaxReportLatencyUs);
            mMagnType = SensorRegsit(mMagnPriorList, MaxReportLatencyUs);
            mGyroType = SensorRegsit(mGyroPriorList, MaxReportLatencyUs);
            if(mTextView != null){
                String str = "";
                str += "Accl: "; str += (mAcclType == 0) ? "false\n" : "true\n";
                str += "Magn: "; str += (mMagnType == 0) ? "false\n" : "true\n";
                str += "Gyro: "; str += (mGyroType == 0) ? "false\n" : "true\n";
                mTextView.setText(str);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    public void onEnterAmbient(Bundle ambientDetails) {
        super.onEnterAmbient(ambientDetails);
    }

    @Override
    protected void onDestroy() {
        try {
            synchronized (mThread) {
                if(mSensorManager != null) {
                    mSensorManager.unregisterListener(this);
                    mWL.release();
                    mWL = null;
                    mSensorManager = null;
                }
                mThread.notify();
            }
            mThread.join();
            mAcclQueue = null;
            mMagnQueue = null;
            mGyroQueue = null;
            mQueueR = null;
            mQueueW = null;
        }
        catch (Exception e) {
        }
        if(mBW != null) {
            try {
                mBW.close();
                mBW = null;
            }
            catch (IOException e) {
            }
        }
        super.onDestroy();
    }

    private boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    private boolean FlushData() {
        boolean enable = true;
        String str ="";
        if ( (mAcclType != 0) && (mAcclQueue.size() == 0) ) {
            enable = false;
        }
        if ( (mMagnType != 0) && (mMagnQueue.size() == 0) ) {
            enable = false;
        }
        if ( (mGyroType != 0) && (mGyroQueue.size() == 0) ) {
            enable = false;
        }
        if(enable) {
            if (mCounter == 0) {
                if(mAcclType != 0) {
                    mAcclQueue.peek(mQueueR);
                    if (mCounter < mQueueR.timestamp) {
                        mCounter = mQueueR.timestamp;
                    }
                }
                if(mMagnType != 0) {
                    mMagnQueue.peek(mQueueR);
                    if (mCounter < mQueueR.timestamp) {
                        mCounter = mQueueR.timestamp;
                    }
                }
                if(mGyroType != 0) {
                    mGyroQueue.peek(mQueueR);
                    if (mCounter < mQueueR.timestamp) {
                        mCounter = mQueueR.timestamp;
                    }
                }
            }
            str += String.format("0x%x 0x%x", System.currentTimeMillis(), mCounter);
            if(mGyroType != 0) {
                while ( mGyroQueue.peek(mQueueR) ) {
                    if (mCounter <= mQueueR.timestamp) {
                        str += String.format(" %e %e %e", mQueueR.values[0], mQueueR.values[1], mQueueR.values[2]);
                        break;
                    }
                    else {
                        mGyroQueue.pop(null);
                    }
                }
                if(mGyroQueue.size() == 0){
                    enable = false;
                }
            }
            else {
                str += " na na na";
            }
            if(mAcclType != 0) {
                while ( mAcclQueue.peek(mQueueR) ) {
                    if (mCounter <= mQueueR.timestamp) {
                        str += String.format(" %e %e %e", mQueueR.values[0], mQueueR.values[1], mQueueR.values[2]);
                        break;
                    }
                    else {
                        mAcclQueue.pop(null);
                    }
                }
                if(mAcclQueue.size() == 0){
                    enable = false;
                }
            }
            else {
                str += " na na na";
            }
            if(mMagnType != 0) {
                while ( mMagnQueue.peek(mQueueR) ) {
                    if (mCounter <= mQueueR.timestamp) {
                        str += String.format(" %e %e %e", mQueueR.values[0], mQueueR.values[1], mQueueR.values[2]);
                        break;
                    }
                    else {
                        mMagnQueue.pop(null);
                    }
                }
                if(mMagnQueue.size() == 0){
                    enable = false;
                }
            }
            else {
                str += " na na na";
            }
            if (enable) {
                str += String.format(" na na na na na %f\n", (float)SamplingPeriodUs/1000000.0);
                try {
                    if(c>60000){
                        renameFile();
                        c=0;
                    }
                    mBW.write(str);
                }
                catch (IOException e){
                }
                mCounter += SamplingPeriodUs * 1000;
            }
        }
        return enable;
    }

    @Override
    public void run() {
        try {
            while (true) {
                synchronized (mThread) {
                    if(mSensorManager == null){
                        break;
                    }
                    mThread.wait();
                }
                while (true) {
                    if(!FlushData()){
                        break;
                    }else{
                        c++;
                    }
                }
            }
        }
        catch (Exception e) {
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(mTextView != null) {
            boolean push_ret = true;
            int type = event.sensor.getType();
            mQueueW.timestamp = event.timestamp;
            mQueueW.values[0] = event.values[0];
            mQueueW.values[1] = event.values[1];
            mQueueW.values[2] = event.values[2];
            if(type == mAcclType) {
                push_ret = mAcclQueue.push(mQueueW);
            }
            else if(type == mMagnType) {
                push_ret = mMagnQueue.push(mQueueW);
            }
            else if(type == mGyroType) {
                push_ret = mGyroQueue.push(mQueueW);
            }
            if(mBW != null) {
                synchronized (mThread) {
                    mThread.notify();
                }
            }
            if(!push_ret){
                mOverFlow++;
                mTextView.setText( String.format("OverFlow!!!: %d", mOverFlow) );
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
