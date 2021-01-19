package com.telefonica.myapplication2;


import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.video.VideoQuality;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;


/**
 * A straightforward example of how to stream AMR and H.263 to some public IP using libstreaming.
 * Note that this example may not be using the latest version of libstreaming !
 */
public class MainActivity extends Activity implements OnClickListener, Session.Callback, SurfaceHolder.Callback {

    private final static String TAG = "MainActivity";

    private Button mButton1;
    private EditText mEditText;
    private EditText editTextPort;
    private Session mSession;
    private TextView mTextBitrate;
    private int REQUEST_CODE_STREAM = 179; //random num

    private int REQUEST_CODE_RECORD = 180; //random num
    private MediaProjectionManager mediaProjectionManager;

    private final static int PERMISSIONS_OK = 10001;
    private static String[] PERMISSIONS_STORAGE = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE" };
    private SharedPreferences sharedPreferences;
    private SharedPreferences.Editor editor;

    @SuppressLint("NewApi")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT>22) {
            if (!checkPermissionAllGranted(PERMISSIONS_STORAGE)) {
                ActivityCompat.requestPermissions(MainActivity.this,
                        PERMISSIONS_STORAGE, PERMISSIONS_OK);
            }
        }

        mButton1 = findViewById(R.id.button1);
        mEditText = findViewById(R.id.editText1);
        mTextBitrate = findViewById(R.id.bitrate);
        editTextPort = findViewById(R.id.editTextPort);
        sharedPreferences= this.getSharedPreferences("PORT_CONFIG", Context.MODE_PRIVATE);
        editor = sharedPreferences.edit();
        editor.putString("PORT_CONFIG_VALUE", editTextPort.getText().toString());
        editor.apply();

        mButton1.setOnClickListener(this);

        this.mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

    }

    private boolean checkPermissionAllGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mSession.release();
    }

    @SuppressLint("NewApi")
    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button1) {
            editor.putString("PORT_CONFIG_VALUE", editTextPort.getText().toString());
            editor.apply();
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_STREAM);
            mButton1.setEnabled(false);
        } else {
            // Switch between the two cameras
            mSession.switchCamera();
        }
    }

    @Override
    public void onBitrateUpdate(long bitrate) {
        Log.d(TAG, "Bitrate: " + bitrate);
        mTextBitrate.setText("" + bitrate / 1000 + " kbps");
    }

    @Override
    public void onSessionError(int message, int streamType, Exception e) {
        mButton1.setEnabled(true);
        if (e != null) {
            logError(e.getMessage());
        }
    }

    @Override

    public void onPreviewStarted() {
        Log.d(TAG, "Preview started.");
    }

    @Override
    public void onSessionConfigured() {
        Log.d(TAG, "Preview configured.");
        // Once the stream is configured, you can get a SDP formated session description
        // that you can send to the receiver of the stream.
        // For example, to receive the stream in VLC, store the session description in a .sdp file
        // and open it with VLC while streming.
        Log.d(TAG, mSession.getSessionDescription());
        mSession.start();
    }

    @Override
    public void onSessionStarted() {
        Log.d(TAG, "Session started.");
        mButton1.setEnabled(true);
        mButton1.setText(R.string.stop);
    }

    @Override
    public void onSessionStopped() {
        Log.d(TAG, "Session stopped.");
        mButton1.setEnabled(true);
        mButton1.setText(R.string.start);
    }

    /**
     * Displays a popup to report the eror to the user
     */
    private void logError(final String msg) {
        final String error = (msg == null) ? "Error unknown" : msg;
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setMessage(error).setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
            }
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    public void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        Log.d(TAG, "Session stopped. data = " + data);
        Log.d(TAG, "Session stopped. requestCode = " + requestCode);
        Log.d(TAG, "Session stopped. resultCode = " + resultCode);
        super.onActivityResult(requestCode, resultCode, data);
        if (data != null && (requestCode == REQUEST_CODE_STREAM
                || requestCode == REQUEST_CODE_RECORD && resultCode == Activity.RESULT_OK)
        ) {
            Intent a = new Intent(getApplicationContext(), MyDisplayService.class);
            a.putExtra("RESULT_CODE", resultCode);
            a.putExtra("RESULT_DATA", data);
            startService(a);
            H264ScreenStream.setIntentResult(resultCode, data);

            // Configures the SessionBuilder
            mSession = MySessionBuilder.getInstance()
                    .setContext(getApplicationContext())
                    .setAudioEncoder(MySessionBuilder.AUDIO_NONE)
                    .setVideoEncoder(MySessionBuilder.VIDEO_H264)
                    .setCallback(this)
                    .setVideoQuality(new VideoQuality(1280, 720, 30, 5000000))
                    .build();

            mSession.setDestination(mEditText.getText().toString());
            if (!mSession.isStreaming()) {
                mSession.configure();
            } else {
                mSession.stop();
            }
        } else {
            Toast.makeText(this, "No permissions available", Toast.LENGTH_SHORT).show();
            mButton1.setText("Start");
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {

    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSession.startPreview();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSession.stop();
    }
}
