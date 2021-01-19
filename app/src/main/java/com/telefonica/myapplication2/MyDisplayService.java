package com.telefonica.myapplication2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

public class MyDisplayService extends Service {

    private String TAG = "DisplayService";
    private String channelId = "rtpDisplayStreamChannel";
    private int notifyId = 123456;
    private NotificationManager notificationManager;
    private Context contextApp;
    private int resultCode;
    private Intent data;

    private String endpoint;

    @Override
    public void onCreate() {
        super.onCreate();


        Log.d(TAG, "RTP Display service create");
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, channelId, NotificationManager.IMPORTANCE_HIGH);
            notificationManager.createNotificationChannel(channel);
        }
        keepAliveTrick();
        contextApp = getApplicationContext();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    private void keepAliveTrick() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            Notification notification = new NotificationCompat.Builder(this, channelId)
                    .setOngoing(true)
                    .setContentTitle("")
                    .setContentText("").build();
            startForeground(1, notification);
        } else {
            startForeground(1, new Notification());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e(TAG, "RTP Display service started");
        //endpoint = intent.getExtras().getString("endpoint");
        //Log.e(TAG, "endpoint = " + endpoint);

       int result = intent.getIntExtra("RESULT_CODE", -1);
        Intent resultData = intent.getParcelableExtra("RESULT_DATA");
        resultCode = result;
        data = resultData;
        prepareStreamRtp();
        //startStreamRtp(endpoint!!)
        return START_STICKY;
    }

    private void prepareStreamRtp() {
        H264ScreenStream.setIntentResult(resultCode, data);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
    }
}
