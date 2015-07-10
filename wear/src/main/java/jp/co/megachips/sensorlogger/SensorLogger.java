package jp.co.megachips.sensorlogger;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.support.wearable.activity.WearableActivity;
import android.support.wearable.view.WatchViewStub;
import android.widget.TextView;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.SensorEventListener;
import android.hardware.SensorEvent;

import java.util.Date;
import java.text.SimpleDateFormat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

public class SensorLogger extends WearableActivity implements SensorEventListener {

    private final int SamplingPeriodUs = 10 * 1000;
    private final int MaxReportLatencyUs = 0 * 1000 * 1000;

    private TextView mTextView = null;
    private SensorManager mSensorManager;
    private FileOutputStream mFS = null;
    private PrintWriter mPW = null;

    private int mAcclType = 0;
    private int mMagnType = 0;
    private int mGyroType = 0;
    private long mCounter = 0;

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

    private class SensorEventContainer {
        public long timestamp;
        public final float[] values = new float[3];

        public SensorEventContainer(SensorEvent event) {
            timestamp = event.timestamp;
            values[0] = event.values[0];
            values[1] = event.values[1];
            values[2] = event.values[2];
        }
    };

    Queue<SensorEventContainer> mAcclQueue = new LinkedList<SensorEventContainer>();
    Queue<SensorEventContainer> mMagnQueue = new LinkedList<SensorEventContainer>();
    Queue<SensorEventContainer> mGyroQueue = new LinkedList<SensorEventContainer>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        setAmbientEnabled();
        setContentView(R.layout.activity_sensor_logger);
        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mTextView = (TextView) stub.findViewById(R.id.text);
                String str = "";
                str += "Accl: "; str += (mAcclType == 0) ? "false\n" : "true\n";
                str += "Magn: "; str += (mMagnType == 0) ? "false\n" : "true\n";
                str += "Gyro: "; str += (mGyroType == 0) ? "false\n" : "true\n";
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
                mFS = new FileOutputStream(file);
                mPW = new PrintWriter(mFS);
            } catch (FileNotFoundException e) {
            }
        }
        if(mSensorManager != null) {
            mAcclType = SensorRegsit(mAcclPriorList, MaxReportLatencyUs);
            mMagnType = SensorRegsit(mMagnPriorList, 0);
            mGyroType = SensorRegsit(mGyroPriorList, 0);
            if(mTextView != null){
                String str = "";
                str += "Accl: "; str += (mAcclType == 0) ? "false\n" : "true\n";
                str += "Magn: "; str += (mMagnType == 0) ? "false\n" : "true\n";
                str += "Gyro: "; str += (mGyroType == 0) ? "false\n" : "true\n";
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
        if(mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
        if(mPW != null) {
            mPW.close();
        }
        if(mFS != null) {
            try {
                mFS.flush();
                mFS.close();
            } catch (IOException e) {
            }
        }
        super.onDestroy();
    }

    private boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
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
                long counter;
                if(mAcclType != 0) {
                    counter = mAcclQueue.peek().timestamp;
                    if (mCounter < counter) {
                        mCounter = counter;
                    }
                }
                if(mMagnType != 0) {
                    counter = mMagnQueue.peek().timestamp;
                    if (mCounter < counter) {
                        mCounter = counter;
                    }
                }
                if(mGyroType != 0) {
                    counter = mGyroQueue.peek().timestamp;
                    if (mCounter < counter) {
                        mCounter = counter;
                    }
                }
            }
            str += String.format("0x%x 0x%x", mCounter/1000000000, mCounter);
            SensorEventContainer event;
            if(mGyroType != 0) {
                while ((event = mGyroQueue.peek()) != null) {
                    if (mCounter <= event.timestamp) {
                        str += String.format(" %e %e %e", event.values[0], event.values[1], event.values[2]);
                        break;
                    }
                    else {
                        mGyroQueue.poll();
                    }
                }
                if(event == null){
                    enable = false;
                }
            }
            else {
                str += " na na na";
            }
            if(mAcclType != 0) {
                while ((event = mAcclQueue.peek()) != null) {
                    if (mCounter <= event.timestamp) {
                        str += String.format(" %e %e %e", event.values[0]/9.8, event.values[1]/9.8, event.values[2]/9.8);
                        break;
                    }
                    else {
                        mAcclQueue.poll();
                    }
                }
                if(event == null){
                    enable = false;
                }
            }
            else {
                str += " na na na";
            }
            if(mMagnType != 0) {
                while ((event = mMagnQueue.peek()) != null) {
                    if (mCounter <= event.timestamp) {
                        str += String.format(" %e %e %e", event.values[0], event.values[1], event.values[2]);
                        break;
                    }
                    else {
                        mMagnQueue.poll();
                    }
                }
                if(event == null){
                    enable = false;
                }
            }
            else {
                str += " na na na";
            }
            if (enable) {
                str += String.format(" na na na na na %f", (float)SamplingPeriodUs/1000000.0);
                mPW.println(str);
                mCounter += SamplingPeriodUs * 1000;
            }
        }
        return enable;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(mTextView != null) {
            int type = event.sensor.getType();
            if(type == mAcclType) {
                mAcclQueue.add(new SensorEventContainer(event));
            }
            else if(type == mMagnType) {
                mMagnQueue.add(new SensorEventContainer(event));
            }
            else if(type == mGyroType) {
                mGyroQueue.add(new SensorEventContainer(event));
            }
            if(mPW != null) {
                while(FlushData() == true);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
