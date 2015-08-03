package jp.co.megachips.sensorlogger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;
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

public class SensorLogger extends Service implements Runnable, SensorEventListener {
    private final String TAG = "SensorLogger";

    private NotificationManagerCompat mNotificationManager = null;

    private final int SamplingPeriodUs = 10 * 1000;
    private final int MaxReportLatencyUs = 0 * 1000 * 1000;
    private int mOverFlow = 0;

    private final Thread mThread = new Thread(this);
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWL;
    private SensorManager mSensorManager;
    private BufferedWriter mBW = null;

    private TargetSensorType mAcclType = new TargetSensorType(0, false, false);
    private TargetSensorType mMagnType = new TargetSensorType(0, false, false);
    private TargetSensorType mGyroType = new TargetSensorType(0, false, false);
    private TargetSensorType mPresType = new TargetSensorType(0, false, false);

    private long mCounter = 0;

    private String mOutputFileName;
    private File mOutputDir;

    private int mDivCount = 1;
    private long mFlushCounter = 0;
    public static final int FLUSH_COUNT_MAX = 60000;

    private Intent mBroadcastIntent = new Intent();

    class TargetSensorType {
        public int type;
        public boolean wakeUp;
        public boolean uncalibrated;
        TargetSensorType(int type, boolean wakeUp, boolean uncalibrated) { this.type = type; this.wakeUp = wakeUp; this.uncalibrated = uncalibrated; }
    }

    private final TargetSensorType[] mAcclPriorList = {
            new TargetSensorType(Sensor.TYPE_ACCELEROMETER, true, false),
            new TargetSensorType(Sensor.TYPE_ACCELEROMETER, false, false),
    };

    private final TargetSensorType[] mMagnPriorList = {
            new TargetSensorType(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, true, true),
            new TargetSensorType(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, false, true),
            new TargetSensorType(Sensor.TYPE_MAGNETIC_FIELD, true, false),
            new TargetSensorType(Sensor.TYPE_MAGNETIC_FIELD, false, false),
    };

    private final TargetSensorType[] mGyroPriorList = {
            new TargetSensorType(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, true, true),
            new TargetSensorType(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, false, true),
            new TargetSensorType(Sensor.TYPE_GYROSCOPE, true, false),
            new TargetSensorType(Sensor.TYPE_GYROSCOPE, false, false),
    };

    private final TargetSensorType[] mPresPriorList = {
            new TargetSensorType(Sensor.TYPE_PRESSURE, true, false),
            new TargetSensorType(Sensor.TYPE_PRESSURE, false, false),
    };

    private TargetSensorType SensorRegsit(TargetSensorType[] list, int max_report_latency_us) {
        TargetSensorType ret = new TargetSensorType(0, false, false);
        for(TargetSensorType type: list){
            Sensor sensor;
            sensor = mSensorManager.getDefaultSensor(type.type, type.wakeUp);
            if(sensor != null) {
                mSensorManager.registerListener(this, sensor, SamplingPeriodUs, max_report_latency_us);
                ret.type = type.type;
                ret.uncalibrated = type.uncalibrated;
                return ret;
            }
        }
        return ret;
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
        synchronized boolean peek(SensorData data, int dist) {
            boolean ret = true;
            if(mNum <= dist){
                if(0 == dist) {
                    mNum = 0;
                }
                ret = false;
            }
            else if(data != null) {
                if (mTop + dist < mDepth) {
                    data.timestamp = mRingData[mTop + dist].timestamp;
                    data.values[0] = mRingData[mTop + dist].values[0];
                    data.values[1] = mRingData[mTop + dist].values[1];
                    data.values[2] = mRingData[mTop + dist].values[2];
                }else{
                    data.timestamp = mRingData[mTop + dist - mDepth].timestamp;
                    data.values[0] = mRingData[mTop + dist - mDepth].values[0];
                    data.values[1] = mRingData[mTop + dist - mDepth].values[1];
                    data.values[2] = mRingData[mTop + dist - mDepth].values[2];
                }
            }
            return ret;
        }
        synchronized boolean peek(SensorData data){
            return peek(data, 0);
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
    SensorEventQueue mPresQueue = null;
    SensorData mQueueR = null;
    SensorData mQueueW = null;

    public void changeWriteFile(){
        if(null != mBW){
            try {
                mBW.close();
                mBW = null;
            }catch (IOException e){
            }
        }
        if (isExternalStorageWritable()) {
            String serialNum = String.format("_%03d", mDivCount);
            File file = new File(mOutputDir, mOutputFileName + serialNum + ".txt");
            try {
                mBW = new BufferedWriter(new FileWriter(file));
                mDivCount++;
            }
            catch (IOException e) {
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mPowerManager = (PowerManager)getSystemService(POWER_SERVICE);
        mWL = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorLogger");
        mWL.acquire();
        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        for(Sensor p : mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
            Log.i(TAG, p.toString());
        }
        if(isExternalStorageWritable()) {
            SimpleDateFormat form = new SimpleDateFormat("yyyyMMdd_HHmmss");
            mOutputFileName = form.format(new Date());
            File root = android.os.Environment.getExternalStorageDirectory();
            mOutputDir = new File (root.getAbsolutePath() + "/SensorLogger");
            mOutputDir.mkdir();
            changeWriteFile();
        }
        if(mSensorManager != null) {
            mThread.setPriority(Thread.MAX_PRIORITY);
            mThread.start();
            // create Queue
            mAcclQueue = new SensorEventQueue(100);
            mMagnQueue = new SensorEventQueue(100);
            mGyroQueue = new SensorEventQueue(100);
            mPresQueue = new SensorEventQueue(100);
            mQueueR = new SensorData();
            mQueueW = new SensorData();
            // Regist sensors
            mAcclType = SensorRegsit(mAcclPriorList, MaxReportLatencyUs);
            mMagnType = SensorRegsit(mMagnPriorList, MaxReportLatencyUs);
            mGyroType = SensorRegsit(mGyroPriorList, MaxReportLatencyUs);
            mPresType = SensorRegsit(mPresPriorList, MaxReportLatencyUs);

            String str = "";
            str += "Accl"; str += (mAcclType.uncalibrated) ? "uncalibrated: " : ": "; str += (mAcclType.type == 0) ? "false\n" : "true\n";
            str += "Magn"; str += (mMagnType.uncalibrated) ? "uncalibrated: " : ": "; str += (mMagnType.type == 0) ? "false\n" : "true\n";
            str += "Gyro"; str += (mGyroType.uncalibrated) ? "uncalibrated: " : ": "; str += (mGyroType.type == 0) ? "false\n" : "true\n";
            str += "Pres"; str += (mPresType.uncalibrated) ? "uncalibrated: " : ": "; str += (mPresType.type == 0) ? "false\n" : "true\n";

            mBroadcastIntent.putExtra("message", str);
            mBroadcastIntent.setAction(TAG);
            getBaseContext().sendBroadcast(mBroadcastIntent);
        }
        showNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
            mQueueW.timestamp = mCounter + SamplingPeriodUs * 1000 ;
            for(int i=0; i<3; i++) {
                mQueueW.values[i] = 0;
            }
            mAcclQueue.push(mQueueW);
            mMagnQueue.push(mQueueW);
            mGyroQueue.push(mQueueW);
            mPresQueue.push(mQueueW);
            while(FlushData()){
            }
            mAcclQueue = null;
            mMagnQueue = null;
            mGyroQueue = null;
            mPresQueue = null;
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
        if(mNotificationManager != null) {
            mNotificationManager.cancelAll();
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
        if ( (mAcclType.type != 0) && (mAcclQueue.size() == 0) ) {
            enable = false;
        }
        if ( (mMagnType.type != 0) && (mMagnQueue.size() == 0) ) {
            enable = false;
        }
        if ( (mGyroType.type != 0) && (mGyroQueue.size() == 0) ) {
            enable = false;
        }
        if(enable) {
            if (mCounter == 0) {
                if(mAcclType.type != 0) {
                    mAcclQueue.peek(mQueueR);
                    if (mCounter < mQueueR.timestamp) {
                        mCounter = mQueueR.timestamp;
                    }
                }
                if(mMagnType.type != 0) {
                    mMagnQueue.peek(mQueueR);
                    if (mCounter < mQueueR.timestamp) {
                        mCounter = mQueueR.timestamp;
                    }
                }
                if(mGyroType.type != 0) {
                    mGyroQueue.peek(mQueueR);
                    if (mCounter < mQueueR.timestamp) {
                        mCounter = mQueueR.timestamp;
                    }
                }
                if(mPresType.type != 0) {
                    mGyroQueue.peek(mQueueR);
                    if (mCounter < mQueueR.timestamp) {
                        mCounter = mQueueR.timestamp;
                    }
                }
            }
            str += String.format("0x%x 0x%x", System.currentTimeMillis(), mCounter);
            if(mGyroType.type != 0) {
                while ( mGyroQueue.peek(mQueueR, 1) ) {
                    if (mCounter < mQueueR.timestamp) {
                        mGyroQueue.peek(mQueueR);
                        str += String.format(" %e %e %e", mQueueR.values[0], mQueueR.values[1], mQueueR.values[2]);
                        break;
                    }
                    else {
                        mGyroQueue.pop(null);
                    }
                }
                if (mGyroQueue.size() <= 1) {
                    enable = false;
                }
            }
            else {
                str += " na na na";
            }
            if(mAcclType.type != 0) {
                while ( mAcclQueue.peek(mQueueR, 1) ) {
                    if (mCounter < mQueueR.timestamp) {
                        mAcclQueue.peek(mQueueR);
                        str += String.format(" %e %e %e", mQueueR.values[0]/SensorManager.STANDARD_GRAVITY, mQueueR.values[1]/SensorManager.STANDARD_GRAVITY, mQueueR.values[2]/SensorManager.STANDARD_GRAVITY);
                        break;
                    }
                    else {
                        mAcclQueue.pop(null);
                    }
                }
                if (mAcclQueue.size() <= 1) {
                    enable = false;
                }
            }
            else {
                str += " na na na";
            }
            if(mMagnType.type != 0) {
                while ( mMagnQueue.peek(mQueueR, 1) ) {
                    if (mCounter < mQueueR.timestamp) {
                        mMagnQueue.peek(mQueueR);
                        str += String.format(" %e %e %e", mQueueR.values[0]/100, mQueueR.values[1]/100, mQueueR.values[2]/100);
                        break;
                    }
                    else {
                        mMagnQueue.pop(null);
                    }
                }
                if (mMagnQueue.size() <= 1) {
                    enable = false;
                }
            }
            else {
                str += " na na na";
            }
            // temperature
            str += " na na na";
            if(mPresType.type != 0) {
                while ( mPresQueue.peek(mQueueR, 1) ) {
                    if (mCounter < mQueueR.timestamp) {
                        mPresQueue.peek(mQueueR);
                        str += String.format(" na %e", mQueueR.values[0]);
                        break;
                    }
                    else {
                        mPresQueue.pop(null);
                    }
                }
                if(mPresQueue.size() <= 1){
                    enable = false;
                }
            }
            else {
                str += " na na";
            }
            if (enable) {
                str += String.format(" %f\n", (float)SamplingPeriodUs/1000000.0);
                try {
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
                        mFlushCounter++;
                        if(mFlushCounter > FLUSH_COUNT_MAX){
                            mFlushCounter=0;
                            changeWriteFile();
                        }
                    }
                }
            }
        }
        catch (Exception e) {
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        boolean push_ret = true;
        int type = event.sensor.getType();
        mQueueW.timestamp = event.timestamp;
        mQueueW.values[0] = event.values[0];
        mQueueW.values[1] = event.values[1];
        mQueueW.values[2] = event.values[2];
        if(type == mAcclType.type) {
            push_ret = mAcclQueue.push(mQueueW);
        }
        else if(type == mMagnType.type) {
            push_ret = mMagnQueue.push(mQueueW);
        }
        else if(type == mGyroType.type) {
            push_ret = mGyroQueue.push(mQueueW);
        }
        else if(type == mPresType.type) {
            push_ret = mPresQueue.push(mQueueW);
        }
        if(mBW != null) {
            synchronized (mThread) {
                mThread.notify();
            }
        }
        if(!push_ret){
            mOverFlow++;
            if (mOverFlow <= 1000 || mOverFlow % 100 == 0) {
                mBroadcastIntent.putExtra("message", String.format("OverFlow!!!: %d", mOverFlow));
                mBroadcastIntent.setAction(TAG);
                getBaseContext().sendBroadcast(mBroadcastIntent);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void showNotification(){
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext());
        builder.setContentIntent(pendingIntent);
        builder.setContentTitle("SensorLogger is running.");
        builder.setContentText("Touch to open the app.");
        builder.setSmallIcon(R.drawable.card_background);
        builder.extend(new NotificationCompat.WearableExtender().setContentAction(0)
                .addAction(new NotificationCompat.Action.Builder(R.drawable.card_background, "", pendingIntent).build()));
        mNotificationManager = NotificationManagerCompat.from(this);
        startForeground(1, builder.build());
    }

    @Override
    public IBinder onBind(Intent intent){
        throw new UnsupportedOperationException("Not yet supported");
    }
}
