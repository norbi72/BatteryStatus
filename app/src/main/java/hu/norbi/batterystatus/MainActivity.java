package hu.norbi.batterystatus;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;

import android.support.v7.app.AppCompatDelegate;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {"state":57,"voltage":"4006 mV","temperature":"27.1","charging_state":"charging","power":"USB","device_class":"battery","unit_of_measurement":"%","health":"good","technology":"Li-poly","icon":"mdi:battery-charging-60"}
 * qos : 0, retain : false, cmd : publish, dup : false, topic : homeassistant/sensor/android_redmi_note_9_pro_battery/attributes, messageId : , length : 284
 */

public class MainActivity extends AppCompatActivity {
    private static final double THRESHOLD = 0.3;
    TextView textview;
    ImageView connectionStatusImageView;
    TextView lastMqttMessageTextView;
    IntentFilter intentfilter;
    int batteryStatus;
    String currentBatteryStatus="Battery Info";
    Logger logger = Logger.getLogger(this.getClass().getName());
    private boolean mBounded;
    private MqttService myService;
    private ServiceConnection myServiceConnection;

    int oldBatteryLevel = 0;
    int oldBatteryStatus = 0;
    float oldBatteryTemperature = 0F;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Force the app to always use night mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);

        setContentView(R.layout.activity_main);
        textview = findViewById(R.id.textViewBatteryStatus);
        connectionStatusImageView = findViewById(R.id.connectionStatusImageView);
        lastMqttMessageTextView = findViewById(R.id.lastMqttMessage);
        intentfilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

        Context context = this.getApplicationContext();

        Intent mymqttservice_intent = new Intent(this, MqttService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(mymqttservice_intent);
        } else {
            context.startService(mymqttservice_intent);
        }

        myServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                //retrieve an instance of the service here from the IBinder returned
                //from the onBind method to communicate with
                //Toast.makeText(MainActivity.this, "Service is connected", Toast.LENGTH_SHORT).show();
                mBounded = true;
                MqttService.LocalBinder mLocalBinder = (MqttService.LocalBinder) service;
                myService = mLocalBinder.getService();
                connectionStatusImageView.setImageResource(R.drawable.ic_baseline_wifi_24);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Toast.makeText(MainActivity.this, "Service is disconnected", Toast.LENGTH_SHORT).show();
                mBounded = false;
                myService = null;
                connectionStatusImageView.setImageResource(R.drawable.ic_baseline_wifi_off_24);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Toast.makeText(MainActivity.this, "Service binding died", Toast.LENGTH_SHORT).show();
                mBounded = false;
                myService = null;
                connectionStatusImageView.setImageResource(R.drawable.ic_baseline_wifi_off_24);
            }
        };
        context.bindService(mymqttservice_intent, myServiceConnection, Context.BIND_AUTO_CREATE);

        MainActivity.this.registerReceiver(broadcastreceiver,intentfilter);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if(mBounded) {
            try {
                unbindService(myServiceConnection);
            } catch (Exception ignored) {}
            mBounded = false;
        }
    }

    public BroadcastReceiver broadcastreceiver = new BroadcastReceiver() {
        private float getBatteryVoltage(Intent intent){
            int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (voltage > 1000)
                return voltage / 1000f;
            else
                return voltage;
        }

        private String getBatteryChargingState(int deviceStatus) {
            switch (deviceStatus) {
                case BatteryManager.BATTERY_STATUS_DISCHARGING:
                    return "discharging";
                case BatteryManager.BATTERY_STATUS_CHARGING:
                    return "charging";
                case BatteryManager.BATTERY_STATUS_FULL:
                    return "charging full";
                case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                    return "not charging";
                case BatteryManager.BATTERY_STATUS_UNKNOWN:
                default:
                    return "unknown";
            }

        }

        @Override
        public void onReceive(Context context, Intent intent) {

            batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int batteryLevel=(int)(((float)level / (float)scale) * 100.0f);

            float temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0f;
            float voltage = getBatteryVoltage(intent);

            if (oldBatteryStatus != batteryStatus ||  oldBatteryLevel != batteryLevel || Math.abs(oldBatteryTemperature - temperature) > THRESHOLD) {
                int batteryLevel10 = (batteryLevel/10) * 10;
                final String icon = "mdi:battery-"+getBatteryChargingState(batteryStatus).replace(" ", "-")+"-"+batteryLevel10;

                try {
                    final String mqttMsgString = getString(R.string.StatusJsonTemplate, batteryLevel, voltage, Float.toString(temperature).replace(",", "."), getBatteryChargingState(batteryStatus), icon);
                    MqttMessage mqttMessage = new MqttMessage(mqttMsgString.getBytes(StandardCharsets.UTF_8));
                    myService.publish("homeassistant/sensor/android_redmi_note_9_pro_battery/attributes", mqttMessage);

                    oldBatteryStatus = batteryStatus;
                    oldBatteryTemperature = temperature;
                    oldBatteryLevel = batteryLevel;

                    Date now = new Date();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    String formattedDate = sdf.format(now);

                    lastMqttMessageTextView.setText(String.format("Last MQTT message:\n%1$s\nat %2$s", mqttMsgString, formattedDate));
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage());
                }
            }

            String infoText = String.format(getString(R.string.TempInfo), currentBatteryStatus, getBatteryChargingState(batteryStatus), batteryLevel, temperature);
            textview.setText(infoText);
            logger.info(infoText);
        }
    };
}