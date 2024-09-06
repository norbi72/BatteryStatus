package hu.norbi.batterystatus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
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
    private String ip = "192.168.31.111", port = "1883";
    private final IBinder mBinder = new LocalBinder();
    private Handler mHandler;

    private class ToastRunnable implements Runnable {//to toast to your main activity for some time
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
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mytoast.cancel();
                }
            }, mtime);
        }
    }

    private static final String TAG = "mqttservice";
    private static boolean hasWifi = false;
    private static boolean hasMobile = false;
    private ConnectivityManager mConnMan;
    private volatile IMqttAsyncClient mqttClient;
    private String uniqueID;
    private String lastMqttMessage;

    MQTTBroadcastReceiver mqttBroadcastReceiver;


    class MQTTBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {

            IMqttToken token;
            boolean hasConnectivity = false;
            boolean hasChanged = false;
            NetworkInfo infos[] = mConnMan.getAllNetworkInfo();
            for (int i = 0; i < infos.length; i++) {
                if (infos[i].getTypeName().equalsIgnoreCase("MOBILE")) {
                    if ((infos[i].isConnected() != hasMobile)) {
                        hasChanged = true;
                        hasMobile = infos[i].isConnected();
                    }
                    Log.d(TAG, infos[i].getTypeName() + " is " + infos[i].isConnected());
                } else if (infos[i].getTypeName().equalsIgnoreCase("WIFI")) {
                    if ((infos[i].isConnected() != hasWifi)) {
                        hasChanged = true;
                        hasWifi = infos[i].isConnected();
                    }
                    Log.d(TAG, infos[i].getTypeName() + " is " + infos[i].isConnected());
                }
            }
            hasConnectivity = hasMobile || hasWifi;
            Log.v(TAG, "hasConn: " + hasConnectivity + " hasChange: " + hasChanged + " - " + (mqttClient == null || !mqttClient.isConnected()));
            if (hasConnectivity && hasChanged && (mqttClient == null || !mqttClient.isConnected())) {
                doConnect();

            }


        }
    }


    public class LocalBinder extends Binder {
        private ImageView connectionStatusImageView;
        private TextView lastMqttMessageTextView;

        public MqttService getService(ImageView connectionStatusImageView, TextView lastMqttMessageTextView) {
            this.connectionStatusImageView = connectionStatusImageView;
            this.lastMqttMessageTextView = lastMqttMessageTextView;

            // Return this instance of LocalService so clients can call public methods
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

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);// we create a 'shared" memory where we will share our preferences for the limits and the values that we get from onsensorchanged
        try {

            lastMqttMessage = message.toString();
            mqttClient.publish(topic, message);

        } catch (MqttException e) {
            e.printStackTrace();
        }

    }


    @Override
    public void onCreate() {

        mHandler = new Handler();//for toasts
        IntentFilter intentf = new IntentFilter();
        setClientID();
        intentf.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        this.mqttBroadcastReceiver = new MQTTBroadcastReceiver();
        registerReceiver(this.mqttBroadcastReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        mConnMan = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        if (Build.VERSION.SDK_INT >= 26) {
            String CHANNEL_ID = "my_channel_01";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Channel human readable title",
                    NotificationManager.IMPORTANCE_DEFAULT);

            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("")
                    .setContentText("").build();

            startForeground(1, notification);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged()");
        android.os.Debug.waitForDebugger();
        super.onConfigurationChanged(newConfig);

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d("Service", "onDestroy");
        unregisterReceiver(this.mqttBroadcastReceiver);
    }


    private void setClientID() {
        uniqueID = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        Log.d(TAG, "uniqueID=" + uniqueID);

    }


    private void doConnect() {
        String broker = "tcp://" + ip + ":" + port;
        Log.d(TAG, "mqtt_doConnect()");
        IMqttToken token;
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setMaxInflight(100);//handle more messages!!so as not to disconnect
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(1000);
        try {

            mqttClient = new MqttAsyncClient(broker, uniqueID, new MemoryPersistence());
            token = mqttClient.connect(options);
            token.waitForCompletion(3500);

            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable throwable) {
                    Log.d(TAG, "Connection lost (in callback)");
                    mHandler.post(new ToastRunnable("CONNECTION LOST!", 4000));
                    ((LocalBinder) mBinder).getConnectionStatusImageView().post(() -> {
                        ((LocalBinder) mBinder).getConnectionStatusImageView().setImageResource(R.drawable.ic_baseline_wifi_off_24);
                    });

                    try {
                        mqttClient.disconnectForcibly();
                        mqttClient.connect();
                    } catch (MqttException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage msg) throws Exception {
                    Log.i(TAG, "Message arrived from topic " + topic);

//                    if (topic.equals("Sensors/message")) {
//
//
//                    } else if (topic.equals("Sensors/" + uniqueID)) {
//                    } else {
//
//                    }

                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
                    Log.d(TAG, "Message published");

                    Date now = new Date();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    String formattedDate = sdf.format(now);

                    ((LocalBinder) mBinder).getLastMqttMessageTextView().post(() -> {
                        ((LocalBinder) mBinder).getLastMqttMessageTextView().setText(
                                Html.fromHtml(String.format("Last MQTT message:<br><font color='#EEFF00'>%1$s</font><br>at <font color='#2FFF00'>%2$s</font>",
                                        MqttService.this.lastMqttMessage, formattedDate),
                                        Html.FROM_HTML_MODE_COMPACT)
                        );
                    });
                }
            });

            Log.i(TAG, "WE ARE ONLINE!");
            mHandler.post(new ToastRunnable("WE ARE ONLINE!", 4000));

            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> {
                // Code to update UI in the UI thread
                ((LocalBinder) mBinder).getConnectionStatusImageView().post(() -> {
                    ((LocalBinder) mBinder).getConnectionStatusImageView().setImageResource(R.drawable.ic_baseline_wifi_24);
                });
            });

        } catch (MqttSecurityException e) {
            e.printStackTrace();
        } catch (MqttException e) {
            switch (e.getReasonCode()) {
                case MqttException.REASON_CODE_BROKER_UNAVAILABLE:
                    Log.i(TAG, "WE ARE OFFLINE BROKER_UNAVAILABLE!");
                    mHandler.post(new ToastRunnable("WE ARE OFFLINE BROKER_UNAVAILABLE!", 4000));
                    ((LocalBinder) mBinder).getConnectionStatusImageView().setImageResource(R.drawable.ic_baseline_wifi_off_24);
                    break;
                case MqttException.REASON_CODE_CLIENT_TIMEOUT:
                    Log.i(TAG, "WE ARE OFFLINE CLIENT_TIMEOUT!");
                    mHandler.post(new ToastRunnable("WE ARE OFFLINE CLIENT_TIMEOUT!", 4000));
                    ((LocalBinder) mBinder).getConnectionStatusImageView().setImageResource(R.drawable.ic_baseline_wifi_off_24);
                    break;
                case MqttException.REASON_CODE_CONNECTION_LOST:
                    Log.i(TAG, "WE ARE OFFLINE CONNECTION_LOST!");
                    mHandler.post(new ToastRunnable("WE ARE OFFLINE CONNECTION_LOST!", 4000));
                    ((LocalBinder) mBinder).getConnectionStatusImageView().setImageResource(R.drawable.ic_baseline_wifi_off_24);
                    break;
                case MqttException.REASON_CODE_SERVER_CONNECT_ERROR:
                    Log.v(TAG, "connect error " + e.getMessage());
                    ((LocalBinder) mBinder).getConnectionStatusImageView().setImageResource(R.drawable.ic_baseline_wifi_off_24);
                    e.printStackTrace();
                    break;
                case MqttException.REASON_CODE_FAILED_AUTHENTICATION:
                    Log.e(TAG, "FAILED AUTH");
                    Intent i = new Intent("RAISEALLARM");
                    i.putExtra("ALLARM", e);
                    Log.e(TAG, "b" + e.getMessage());
                    ((LocalBinder) mBinder).getConnectionStatusImageView().setImageResource(R.drawable.ic_baseline_wifi_off_24);
                    break;
                default:
                    Log.e(TAG, "a" + e.getMessage());
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand()");
        // If START_NOT_STICKY is returned, the service won't be restarted if the system kills it.
        return START_NOT_STICKY;
//        return START_STICKY;
    }
}