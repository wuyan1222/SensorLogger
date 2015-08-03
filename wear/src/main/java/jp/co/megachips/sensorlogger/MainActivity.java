package jp.co.megachips.sensorlogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.wearable.activity.WearableActivity;
import android.support.wearable.view.WatchViewStub;
import android.widget.TextView;


public class MainActivity extends WearableActivity {

    private final String TAG = "SensorLogger";

    private int mAcclType = 0;
    private int mMagnType = 0;
    private int mGyroType = 0;
    private int mPresType = 0;
    private TextView mTextView = null;
    MyBroadcastReceiver mReceiver = new MyBroadcastReceiver();

    public String getVersionName(){
        PackageInfo packageInfo = null;
        try{
            packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_META_DATA);
        }catch (PackageManager.NameNotFoundException e){
        }
        return packageInfo.versionName;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setAmbientEnabled();
        setContentView(R.layout.activity_sensor_logger);

        Intent intent = new Intent(this, SensorLogger.class);
        startService(intent);

        final WatchViewStub stub = (WatchViewStub)findViewById(R.id.watch_view_stub);
        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener(){
            @Override
            public void onLayoutInflated(WatchViewStub stub){
                mTextView = (TextView)stub.findViewById(R.id.text);
                String str = "";
                str += "SensorLogger version: " + getVersionName() + "\n";
                str += "Accl: "; str += (mAcclType == 0) ? "false\n" : "true\n";
                str += "Magn: "; str += (mMagnType == 0) ? "false\n" : "true\n";
                str += "Gyro: "; str += (mGyroType == 0) ? "false\n" : "true\n";
                str += "Pres: "; str += (mPresType == 0) ? "false\n" : "true\n";
                mTextView.setText(str);
            }
        });

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TAG);
        registerReceiver(mReceiver, intentFilter);
    }

    @Override
    public void onDestroy(){
        Intent intent = new Intent(this, SensorLogger.class);
        stopService(intent);
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    public class MyBroadcastReceiver extends BroadcastReceiver{
        @Override
        public void onReceive(Context context, Intent intent){
            String str = "SensorLogger version: " + getVersionName() + "\n";
            str += intent.getExtras().getString("message");
            mTextView.setText(str);
        }
    }
}
