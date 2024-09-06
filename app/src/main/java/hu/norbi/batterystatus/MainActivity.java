package hu.norbi.batterystatus;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;

import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;


import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {"state":57,"voltage":"4006 mV","temperature":"27.1","charging_state":"charging","power":"USB","device_class":"battery","unit_of_measurement":"%","health":"good","technology":"Li-poly","icon":"mdi:battery-charging-60"}
 * qos : 0, retain : false, cmd : publish, dup : false, topic : homeassistant/sensor/android_redmi_note_9_pro_battery/attributes, messageId : , length : 284
 */

public class MainActivity extends AppCompatActivity {
    private static final double THRESHOLD = 0.5;

    TextView batteryStatusTextView;
    ImageView connectionStatusImageView;
    TextView lastMqttMessageTextView;

    IntentFilter intentfilter;
    int batteryStatus;
    String currentBatteryStatus="Battery Info";
    Logger logger = Logger.getLogger(this.getClass().getName());
    private boolean mBounded;
    private MqttService mqttService;
    private ServiceConnection myServiceConnection;

    int oldBatteryLevel = 0;
    int oldBatteryStatus = 0;
    float oldBatteryTemperature = 0F;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Force the app to always use night mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);

        Button stopButton = findViewById(R.id.stopButton);
        stopButton.setOnClickListener(v -> forceStopAndQuit());

        // Set up the toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        batteryStatusTextView = findViewById(R.id.textViewBatteryStatus);
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
                MqttService.LocalBinder mLocalBinder = (MqttService.LocalBinder) service;
                mqttService = mLocalBinder.getService(connectionStatusImageView, lastMqttMessageTextView);
                mBounded = true;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Toast.makeText(MainActivity.this, "Service is disconnected", Toast.LENGTH_SHORT).show();
                mBounded = false;
                mqttService = null;
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Toast.makeText(MainActivity.this, "Service binding died", Toast.LENGTH_SHORT).show();
                mBounded = false;
                mqttService = null;
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Toast.makeText(MainActivity.this, "Service binding returned null", Toast.LENGTH_SHORT).show();
                mBounded = false;
                mqttService = null;
            }
        };
        context.bindService(mymqttservice_intent, myServiceConnection, Context.BIND_AUTO_CREATE);

        MainActivity.this.registerReceiver(broadcastreceiver,intentfilter);
    }

    private void forceStopAndQuit() {
        try {
            MainActivity.this.unregisterReceiver(broadcastreceiver);
            broadcastreceiver = null;
        } catch (IllegalArgumentException e) {
            // Receiver was not registered or already unregistered
        }

        if(mBounded) {
            try {
                unbindService(myServiceConnection);
                myServiceConnection = null;
            } catch (Exception ignored) {}
            mBounded = false;
        }

        stopService(new Intent(this, MqttService.class));

        // Finish all activities in the task
        finishAffinity();

        // Finish all activities and remove the task from recent apps
        finishAndRemoveTask();

        // Forcefully stop the application (optional)
        System.exit(0);
    }

//    @Override
//    protected void onStop() {
//        super.onStop();
//        if(mBounded) {
//            try {
//                unbindService(myServiceConnection);
//            } catch (Exception ignored) {}
//            mBounded = false;
//        }
//    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here
        if (item.getItemId() == R.id.action_configuration) {
            showConfigurationDialog();  // Show the dialog when "Configuration" is clicked
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showConfigurationDialog() {
        // Create a dialog with an EditText and a Submit button
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_configuration, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        EditText input = dialogView.findViewById(R.id.phone_id_input);
        Button submitButton = dialogView.findViewById(R.id.submit_button);

        // Pre-fill the EditText with the stored configuration
        input.setText(getStoredConfiguration());

        submitButton.setOnClickListener(v -> {
            String phoneId = input.getText().toString();
            //Toast.makeText(MainActivity.this, "Submitted: " + configValue, Toast.LENGTH_SHORT).show();
            storeConfiguration(phoneId);

            dialog.dismiss();
        });

        dialog.show();
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


        /**
         * On receive battery status message from Android system
         */
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
                    mqttService.publish(String.format("homeassistant/sensor/%1$s/attributes", getStoredConfiguration()), mqttMessage);

                    oldBatteryStatus = batteryStatus;
                    oldBatteryTemperature = temperature;
                    oldBatteryLevel = batteryLevel;
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage());
                }
            }

            String infoText = String.format(getString(R.string.TempInfo), currentBatteryStatus, getBatteryChargingState(batteryStatus), batteryLevel, temperature);
            batteryStatusTextView.setText(infoText);
            logger.info(infoText);
        }
    };

    private void storeConfiguration(String phoneId) {
        // Get the SharedPreferences object (private mode means only this app can access it)
        SharedPreferences sharedPref = getSharedPreferences("AppPreferences", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();

        // Store the submitted string with a key
        editor.putString("phone_id", phoneId);
        editor.apply(); // Or editor.commit() for immediate saving (apply is asynchronous)

        Toast.makeText(this, "Configuration saved!", Toast.LENGTH_SHORT).show();
    }

    private String getStoredConfiguration() {
        // Retrieve the SharedPreferences object
        SharedPreferences sharedPref = getSharedPreferences("AppPreferences", MODE_PRIVATE);

        // Get the stored string using the same key, and set a default value if it's not found
        return sharedPref.getString("phone_id", "android_redmi_note_9_pro_battery");
    }
}