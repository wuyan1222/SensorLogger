package jp.co.megachips.sensorlogger;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;


public class MainActivity extends Activity{

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
        setContentView(R.layout.activity_sensor_logger);

        mTextView = (TextView)findViewById(R.id.text_view);
        String str = "";
        str += "SensorLogger version: " + getVersionName() + "\n";
        str += "Accl: "; str += (mAcclType == 0) ? "false\n" : "true\n";
        str += "Magn: "; str += (mMagnType == 0) ? "false\n" : "true\n";
        str += "Gyro: "; str += (mGyroType == 0) ? "false\n" : "true\n";
        str += "Pres: "; str += (mPresType == 0) ? "false\n" : "true\n";
        mTextView.setText(str);

        Button button = (Button)findViewById(R.id.button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, SensorLogger.class);
                stopService(intent);
                finish();
            }
        });

        Intent intent = new Intent(this, SensorLogger.class);
        startService(intent);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TAG);
        registerReceiver(mReceiver, intentFilter);
    }

    @Override
    public void onDestroy(){
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
