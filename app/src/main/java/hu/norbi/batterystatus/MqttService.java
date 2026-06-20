package hu.norbi.batterystatus;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.text.Html;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MqttService extends Service {
    private static final String TAG = "MqttService";
    private static final String IP = "192.168.31.111";
    private static final String PORT = "1883";
    private static final double TEMP_THRESHOLD = 0.5;

    private final IBinder mBinder = new LocalBinder();
    private Handler mHandler;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager mConnMan;
    private volatile IMqttAsyncClient mqttClient;
    
    private String uniqueID;
    private String lastMqttMessage;
    private String lastMqttTimestamp;
    private boolean hasWifi = false;
    private boolean hasMobile = false;

    private int oldBatteryLevel = -1;
    private int oldBatteryStatus = -1;
    private float oldBatteryTemperature = -1.0f;

    public interface OnBatteryChangedListener {
        void onBatteryChanged(int level, int status, float temperature, float voltage, String iconName);
    }

    private OnBatteryChangedListener batteryListener;

    public void setOnBatteryChangedListener(OnBatteryChangedListener listener) {
        this.batteryListener = listener;
        forceRefresh();
    }

    public void forceRefresh() {
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent != null) {
            oldBatteryLevel = -1; 
            processBatteryIntent(intent);
        }
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }

    public String getLastMqttMessage() {
        return lastMqttMessage;
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            processBatteryIntent(intent);
        }
    };

    private void processBatteryIntent(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int batteryLevel = (int) (((float) level / (float) scale) * 100.0f);
        int batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        float temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0f;
        float voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) / 1000f;

        if (oldBatteryStatus != batteryStatus || oldBatteryLevel != batteryLevel || Math.abs(oldBatteryTemperature - temperature) > TEMP_THRESHOLD) {
            String chargingState = getBatteryChargingState(batteryStatus).toLowerCase(Locale.ROOT);
            String iconCategory = chargingState.contains("charging") && (chargingState.startsWith("not") || chargingState.startsWith("dis")) ? "discharging" : "charging";
            int batteryLevel10 = (batteryLevel / 10) * 10;
            String iconName = "mdi:battery-" + iconCategory + "-" + batteryLevel10;

            publishBatteryStatus(batteryLevel, voltage, temperature, chargingState, iconName);

            if (batteryListener != null) {
                batteryListener.onBatteryChanged(batteryLevel, batteryStatus, temperature, voltage, iconName);
            }

            oldBatteryStatus = batteryStatus;
            oldBatteryLevel = batteryLevel;
            oldBatteryTemperature = temperature;
        }
    }

    private void publishBatteryStatus(int level, float voltage, float temp, String state, String icon) {
        try {
            SharedPreferences prefs = getSharedPreferences("AppPreferences", MODE_PRIVATE);
            String phoneId = prefs.getString("phone_id", "android_redmi_note_9_pro_battery");
            
            String json = String.format(Locale.US, "{\"state\":%d,\"voltage\":\"%.4f V\",\"temperature\":%s,\"charging_state\":\"%s\",\"power\":\"USB\",\"device_class\":\"battery\",\"unit_of_measurement\":\"%%\",\"health\":\"good\",\"technology\":\"Li-poly\",\"icon\":\"%s\"}",
                    level, voltage, Float.toString(temp).replace(",", "."), state, icon);
            
            publish("homeassistant/sensor/" + phoneId + "/attributes", new MqttMessage(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.e(TAG, "Publish failed", e);
        }
    }

    private String getBatteryChargingState(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "charging";
            case BatteryManager.BATTERY_STATUS_FULL: return "charging full";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "discharging";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not charging";
            default: return "unknown";
        }
    }

    public class LocalBinder extends Binder {
        private ImageView connectionStatusImageView;
        private TextView lastMqttMessageTextView;

        public MqttService getService(ImageView connectionStatusImageView, TextView lastMqttMessageTextView) {
            this.connectionStatusImageView = connectionStatusImageView;
            this.lastMqttMessageTextView = lastMqttMessageTextView;
            updateConnectionUI();
            updateLastMessageUI(lastMqttMessageTextView);
            return MqttService.this;
        }

        public ImageView getConnectionStatusImageView() { return connectionStatusImageView; }
        public TextView getLastMqttMessageTextView() { return lastMqttMessageTextView; }
    }

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    public void publish(String topic, MqttMessage message) {
        lastMqttMessage = new String(message.getPayload());
        if (mqttClient != null && mqttClient.isConnected()) {
            try { mqttClient.publish(topic, message); } catch (MqttException e) { Log.e(TAG, "Publish error", e); }
        } else {
            doConnect();
        }
    }

    @SuppressLint("WakelockTimeout")
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
        setClientID();
        mConnMan = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (mConnMan != null) {
            mConnMan.registerDefaultNetworkCallback(networkCallback);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryStatus::MqttWakeLock");
            wakeLock.acquire();
        }

        setupNotificationChannel();
        startForeground(1, createNotification(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);

        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("my_channel_01",
                    "Battery Status Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, "my_channel_01")
                .setContentTitle("Battery Status")
                .setContentText("Monitoring battery and MQTT")
                .setSmallIcon(R.drawable.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        if (mConnMan != null) mConnMan.unregisterNetworkCallback(networkCallback);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (mqttClient != null) { try { mqttClient.disconnect(); } catch (MqttException ignored) {} }
        super.onDestroy();
    }

    private void setClientID() {
        @SuppressLint("HardwareIds")
        String id = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        uniqueID = (id != null) ? id : "android_" + System.currentTimeMillis();
    }

    private synchronized void doConnect() {
        if (mqttClient != null && mqttClient.isConnected()) return;
        String broker = "tcp://" + IP + ":" + PORT;
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);

        try {
            if (mqttClient == null) mqttClient = new MqttAsyncClient(broker, uniqueID, new MemoryPersistence());
            mqttClient.setCallback(new MqttCallback() {
                @Override public void connectionLost(Throwable t) { updateConnectionUI(); }
                @Override public void messageArrived(String t, MqttMessage m) {
                    if ("off".equalsIgnoreCase(new String(m.getPayload()))) {
                        mHandler.post(() -> {
                            Intent intent = new Intent(MqttService.this, MainActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            intent.putExtra("ACTION_EXIT", true);
                            startActivity(intent);
                        });
                    }
                }
                @Override public void deliveryComplete(IMqttDeliveryToken t) {
                    lastMqttTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                    updateLastMessageUI(((LocalBinder) mBinder).getLastMqttMessageTextView());
                }
            });
            mqttClient.connect(options, null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken t) {
                    updateConnectionUI();
                    subscribeToExitTopic();
                    forceRefresh();
                }
                @Override public void onFailure(IMqttToken t, Throwable e) { updateConnectionUI(); }
            });
        } catch (MqttException e) { Log.e(TAG, "Connect error", e); }
    }

    private void subscribeToExitTopic() {
        SharedPreferences sharedPref = getSharedPreferences("AppPreferences", MODE_PRIVATE);
        String exitTopic = sharedPref.getString("exit_mqtt_topic", "");
        if (sharedPref.getBoolean("mqtt_exit_enabled", false) && !exitTopic.isEmpty() && isConnected()) {
            try { mqttClient.subscribe(exitTopic, 0); } catch (MqttException ignored) {}
        }
    }

    public void refreshSubscriptions() { subscribeToExitTopic(); }

    private void updateLastMessageUI(TextView tv) {
        if (tv != null && lastMqttMessage != null && lastMqttTimestamp != null) {
            String msg = lastMqttMessage;
            try { msg = new JSONObject(lastMqttMessage).toString(2); } catch (Exception ignored) {}
            final String finalMsg = msg.replace("\n", "<br>").replace(" ", "&nbsp;");
            tv.post(() -> tv.setText(Html.fromHtml(String.format("Last MQTT message:<br><font color='#EEFF00'><small>%s</small></font><br>at <font color='#2FFF00'>%s</font>",
                    finalMsg, lastMqttTimestamp), Html.FROM_HTML_MODE_COMPACT)));
        }
    }

    public void updateConnectionUI() {
        mHandler.post(() -> {
            ImageView iv = ((LocalBinder) mBinder).getConnectionStatusImageView();
            if (iv != null) iv.setImageResource(isConnected() ? R.drawable.ic_baseline_wifi_24 : R.drawable.ic_baseline_wifi_off_24);
        });
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network n) { checkConnectivity(); }
        @Override public void onLost(@NonNull Network n) { checkConnectivity(); }
    };

    private void checkConnectivity() {
        if (mConnMan == null) return;
        Network active = mConnMan.getActiveNetwork();
        NetworkCapabilities caps = mConnMan.getNetworkCapabilities(active);
        hasWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        hasMobile = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        if ((hasWifi || hasMobile) && !isConnected()) doConnect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        checkConnectivity();
        return START_STICKY;
    }
}
