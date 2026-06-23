package hu.norbi.batterystatus;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView batteryStatusTextView;
    private ImageView connectionStatusImageView;
    private ImageView batteryIconImageView;
    private TextView lastMqttMessageTextView;

    private boolean mBounded;
    private MqttService mqttService;
    private ServiceConnection myServiceConnection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent().getBooleanExtra("ACTION_EXIT", false)) {
            forceStopAndQuit();
            return;
        }

        initializeUI();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }

        startMqttService();
    }

    private void startMqttService() {
        Intent serviceIntent = new Intent(this, MqttService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(serviceIntent);
        } else {
            getApplicationContext().startService(serviceIntent);
        }

        myServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                MqttService.LocalBinder mLocalBinder = (MqttService.LocalBinder) service;
                mqttService = mLocalBinder.getService(connectionStatusImageView, lastMqttMessageTextView);
                mBounded = true;

                // Sync UI with existing data in the service
                updateBatteryIconFromLastMessage();
                
                // Set listener to handle future battery updates
                mqttService.setOnBatteryChangedListener((level, status, temperature, voltage, iconName) -> runOnUiThread(() -> {
                    updateBatteryUI(level, status, temperature);
                    updateBatteryIcon(iconName);
                }));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mBounded = false;
                mqttService = null;
            }
        };
        getApplicationContext().bindService(serviceIntent, myServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void initializeUI() {
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ImageButton sendNowButton = findViewById(R.id.sendNowButton);
        sendNowButton.setOnClickListener(v -> {
            if (mqttService != null) {
                mqttService.forceRefresh();
                Toast.makeText(MainActivity.this, "Refreshing battery status...", Toast.LENGTH_SHORT).show();
            }
        });

        TextView versionTextView = findViewById(R.id.versionTextView);
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionTextView.setText(String.format(Locale.getDefault(), "Version: %s (%d)", pInfo.versionName, pInfo.versionCode));
        } catch (PackageManager.NameNotFoundException ignored) {}

        batteryStatusTextView = findViewById(R.id.textViewBatteryStatus);
        connectionStatusImageView = findViewById(R.id.connectionStatusImageView);
        batteryIconImageView = findViewById(R.id.batteryIconImageView);
        lastMqttMessageTextView = findViewById(R.id.lastMqttMessage);
    }

    private void updateBatteryUI(int level, int status, float temperature) {
        String chargingState = getBatteryChargingState(status);
        String info = String.format(Locale.getDefault(), "Battery: %s at %d%%  %.1f°C", chargingState, level, temperature);
        batteryStatusTextView.setText(info);
    }

    private String getBatteryChargingState(int status) {
        switch (status) {
            case android.os.BatteryManager.BATTERY_STATUS_CHARGING: return "charging";
            case android.os.BatteryManager.BATTERY_STATUS_FULL: return "charging full";
            case android.os.BatteryManager.BATTERY_STATUS_DISCHARGING: return "discharging";
            case android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not charging";
            default: return "unknown";
        }
    }

    private void updateBatteryIconFromLastMessage() {
        if (mqttService != null) {
            String lastMsg = mqttService.getLastMqttMessage();
            if (lastMsg != null) {
                try {
                    JSONObject json = new JSONObject(lastMsg);
                    if (json.has("icon")) {
                        updateBatteryIcon(json.getString("icon"));
                    }
                } catch (JSONException ignored) {}
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (mBounded) {
            getApplicationContext().unbindService(myServiceConnection);
            mBounded = false;
        }
        super.onDestroy();
    }

    public void forceStopAndQuit() {
        stopService(new Intent(this, MqttService.class));
        finishAndRemoveTask();
        System.exit(0);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_configuration) {
            showConfigurationDialog();
            return true;
        } else if (id == R.id.action_quit) {
            forceStopAndQuit();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showConfigurationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_configuration, null);
        builder.setView(view);
        AlertDialog dialog = builder.create();

        EditText inputId = view.findViewById(R.id.phone_id_input);
        ToggleButton toggle = view.findViewById(R.id.toggleButton);
        EditText inputTopic = view.findViewById(R.id.phone_id_input2);
        Button btn = view.findViewById(R.id.submit_button);

        SharedPreferences prefs = getSharedPreferences("AppPreferences", MODE_PRIVATE);
        inputId.setText(prefs.getString("phone_id", "android_redmi_note_9_pro_battery"));
        toggle.setChecked(prefs.getBoolean("mqtt_exit_enabled", false));
        inputTopic.setText(prefs.getString("exit_mqtt_topic", "/switch/norbi-phone-app"));

        btn.setOnClickListener(v -> {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("phone_id", inputId.getText().toString());
            editor.putBoolean("mqtt_exit_enabled", toggle.isChecked());
            editor.putString("exit_mqtt_topic", inputTopic.getText().toString());
            editor.apply();
            if (mqttService != null) mqttService.refreshSubscriptions();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void updateBatteryIcon(String iconName) {
        if (batteryIconImageView == null || iconName == null) return;
        
        String resName = iconName.replace(":", "_").replace("-", "_");
        int resId = getResources().getIdentifier(resName, "drawable", getPackageName());
        
        if (resId == 0) {
            try {
                String[] parts = resName.split("_");
                int level = -1;
                int idx = -1;
                for (int i = parts.length - 1; i >= 0; i--) {
                    try {
                        level = Integer.parseInt(parts[i]);
                        idx = i;
                        break;
                    } catch (NumberFormatException ignored) {}
                }

                if (idx != -1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < idx; i++) sb.append(parts[i]).append("_");
                    String prefix = sb.toString();
                    for (int i = (level / 10) * 10; i >= 0; i -= 10) {
                        resId = getResources().getIdentifier(prefix + i, "drawable", getPackageName());
                        if (resId != 0) break;
                    }
                }
            } catch (Exception ignored) {}
        }
        
        if (resId != 0) {
            batteryIconImageView.setImageResource(resId);
        } else {
            batteryIconImageView.setImageResource(iconName.contains("charging") ? 
                android.R.drawable.ic_lock_idle_charging : android.R.drawable.ic_lock_idle_low_battery);
        }
    }
}
