package hu.norbi.batterystatus;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.text.Html;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public class MqttService extends Service {
    private static final String IP = "192.168.31.111";
    private static final String PORT = "1883";
    private final IBinder mBinder = new LocalBinder();
    private Handler mHandler;

    private class ToastRunnable implements Runnable {
        String mText;
        int mtime;

        public ToastRunnable(String text, int time) {
            mText = text;
            mtime = time;
        }

        @Override
        public void run() {
            final Toast mytoast = Toast.makeText(getApplicationContext(), mText, Toast.LENGTH_SHORT);
            mytoast.show();
            new Handler(Looper.getMainLooper()).postDelayed(mytoast::cancel, mtime);
        }
    }

    private static final String TAG = "mqttservice";
    private static boolean hasWifi = false;
    private static boolean hasMobile = false;
    private ConnectivityManager mConnMan;
    private volatile IMqttAsyncClient mqttClient;
    private String uniqueID;
    private String lastMqttMessage;

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            super.onAvailable(network);
            checkConnectivity();
        }

        @Override
        public void onLost(@NonNull Network network) {
            super.onLost(network);
            checkConnectivity();
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);
            checkConnectivity();
        }
    };

    private synchronized void checkConnectivity() {
        if (mConnMan == null) return;

        boolean newHasWifi = false;
        boolean newHasMobile = false;

        Network activeNetwork = mConnMan.getActiveNetwork();
        if (activeNetwork != null) {
            NetworkCapabilities caps = mConnMan.getNetworkCapabilities(activeNetwork);
            if (caps != null) {
                newHasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                newHasMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            }
        }

        boolean hasChanged = (newHasWifi != hasWifi) || (newHasMobile != hasMobile);
        hasWifi = newHasWifi;
        hasMobile = newHasMobile;

        boolean hasConnectivity = hasMobile || hasWifi;
        Log.v(TAG, "hasConn: " + hasConnectivity + " hasChange: " + hasChanged + " - " + (mqttClient == null || !mqttClient.isConnected()));
        
        if (hasConnectivity && hasChanged && (mqttClient == null || !mqttClient.isConnected())) {
            doConnect();
        }
    }


    public class LocalBinder extends Binder {
        private ImageView connectionStatusImageView;
        private TextView lastMqttMessageTextView;

        public MqttService getService(ImageView connectionStatusImageView, TextView lastMqttMessageTextView) {
            this.connectionStatusImageView = connectionStatusImageView;
            this.lastMqttMessageTextView = lastMqttMessageTextView;
            return MqttService.this;
        }

        public ImageView getConnectionStatusImageView() {
            return connectionStatusImageView;
        }

        public TextView getLastMqttMessageTextView() {
            return lastMqttMessageTextView;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void publish(String topic, MqttMessage message) {
        if (!hasWifi) {
            Log.i(TAG, "Publish status only on WiFi. Skipped.");
            return;
        }

        try {
            lastMqttMessage = message.toString();
            IMqttAsyncClient client = mqttClient;
            if (client != null && client.isConnected()) {
                client.publish(topic, message);
            }
        } catch (MqttException e) {
            Log.e(TAG, "Error publishing message", e);
        }
    }


    @Override
    public void onCreate() {
        mHandler = new Handler(Looper.getMainLooper());
        setClientID();
        mConnMan = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (mConnMan != null) {
            mConnMan.registerDefaultNetworkCallback(networkCallback);
        }

        if (Build.VERSION.SDK_INT >= 26) {
            String CHANNEL_ID = "my_channel_01";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Channel human readable title",
                    NotificationManager.IMPORTANCE_DEFAULT);

            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("")
                    .setContentText("").build();

            startForeground(1, notification);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged()");
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d("Service", "onDestroy");
        if (mConnMan != null) {
            mConnMan.unregisterNetworkCallback(networkCallback);
        }
    }


    private void setClientID() {
        @SuppressLint("HardwareIds")
        String id = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        uniqueID = id;
        Log.d(TAG, "uniqueID=" + uniqueID);
    }


    private void doConnect() {
        String broker = "tcp://" + IP + ":" + PORT;
        Log.d(TAG, "mqtt_doConnect(" + broker + ")");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setMaxInflight(100);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(1000);
        try {
            mqttClient = new MqttAsyncClient(broker, uniqueID, new MemoryPersistence());
            IMqttToken token = mqttClient.connect(options);
            token.waitForCompletion(3500);

            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable throwable) {
                    Log.d(TAG, "Connection lost (in callback)");
                    mHandler.post(new ToastRunnable("CONNECTION LOST!", 4000));
                    ImageView iv = ((LocalBinder) mBinder).getConnectionStatusImageView();
                    if (iv != null) {
                        iv.post(() -> iv.setImageResource(R.drawable.ic_baseline_wifi_off_24));
                    }

                    try {
                        IMqttAsyncClient client = mqttClient;
                        if (client != null) {
                            client.disconnectForcibly();
                            client.connect();
                        }
                    } catch (MqttException e) {
                        Log.e(TAG, "Error reconnecting", e);
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage msg) {
                    Log.i(TAG, "Message arrived from topic " + topic);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
                    Log.d(TAG, "Message published");

                    Date now = new Date();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    String formattedDate = sdf.format(now);

                    TextView tv = ((LocalBinder) mBinder).getLastMqttMessageTextView();
                    if (tv != null) {
                        tv.post(() -> tv.setText(
                                Html.fromHtml(String.format("Last MQTT message:<br><font color='#EEFF00'>%1$s</font><br>at <font color='#2FFF00'>%2$s</font>",
                                        MqttService.this.lastMqttMessage, formattedDate),
                                        Html.FROM_HTML_MODE_COMPACT)
                        ));
                    }
                }
            });

            Log.i(TAG, "WE ARE ONLINE!");
            mHandler.post(new ToastRunnable("WE ARE ONLINE!", 4000));

            new Handler(Looper.getMainLooper()).post(() -> {
                ImageView iv = ((LocalBinder) mBinder).getConnectionStatusImageView();
                if (iv != null) {
                    iv.post(() -> {
                        Log.i(TAG, "UI THREAD -> ONLINE");
                        iv.setImageResource(R.drawable.ic_baseline_wifi_24);
                    });
                }
            });

        } catch (MqttSecurityException e) {
            Log.e(TAG, "Security exception", e);
        } catch (MqttException e) {
            ImageView iv = ((LocalBinder) mBinder).getConnectionStatusImageView();
            switch (e.getReasonCode()) {
                case MqttException.REASON_CODE_BROKER_UNAVAILABLE:
                case MqttException.REASON_CODE_CLIENT_TIMEOUT:
                case MqttException.REASON_CODE_CONNECTION_LOST:
                    String msg = "WE ARE OFFLINE: " + e.getReasonCode();
                    Log.i(TAG, msg);
                    mHandler.post(new ToastRunnable(msg, 4000));
                    if (iv != null) iv.setImageResource(R.drawable.ic_baseline_wifi_off_24);
                    break;
                case MqttException.REASON_CODE_SERVER_CONNECT_ERROR:
                    Log.e(TAG, "Server connect error", e);
                    if (iv != null) iv.setImageResource(R.drawable.ic_baseline_wifi_off_24);
                    break;
                case MqttException.REASON_CODE_FAILED_AUTHENTICATION:
                    Log.e(TAG, "FAILED AUTH");
                    if (iv != null) iv.setImageResource(R.drawable.ic_baseline_wifi_off_24);
                    break;
                default:
                    Log.e(TAG, "MqttException: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand()");
        return START_NOT_STICKY;
    }
}
