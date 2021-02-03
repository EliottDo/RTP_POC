package com.telefonica.myapplication2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import net.majorkernelpanic.streaming.MediaStream;
import net.majorkernelpanic.streaming.exceptions.ConfNotSupportedException;
import net.majorkernelpanic.streaming.exceptions.StorageUnavailableException;
import net.majorkernelpanic.streaming.hw.EncoderDebugger;
import net.majorkernelpanic.streaming.mp4.MP4Config;
import net.majorkernelpanic.streaming.rtp.H264Packetizer;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;
import net.majorkernelpanic.streaming.video.VideoQuality;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static android.content.Context.MEDIA_PROJECTION_SERVICE;
import static android.content.Context.WINDOW_SERVICE;

//import net.majorkernelpanic.streaming.authen.AuthenticationActivity;
//import net.majorkernelpanic.streaming.surface.SurfaceControl;


public class H264ScreenStream extends MediaStream {

    protected final static String TAG = "H264ScreenStream" ;

    protected static final int IFRAME_INTERVAL = 5;
    protected static final String MIME_TYPE = "video/avc";
    //protected static final String ENCODER_NAME="OMX.Intel.hw_ve.h264";
    protected static final int VIDEO_ControlRateConstant = 2;
    protected static final String ENCODER_NAME="OMX.google.h264.encoder";

    //private AuthenticationActivity mAuthenticationActivity;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private MediaCodec.BufferInfo mVideoBufferInfo;
    private IBinder mDisplay;
    private Surface mSurface;
    private int mScreenDensity = 320;
    private static int resultCode = -1;
    private static Intent data;
    private MediaProjectionManager mediaProjectionManager;

    private  DisplayManager mDisplayManager;

    private MediaProjection.Callback mMediaProjectionCallback = new MediaProjection.Callback() {
        @SuppressLint({"LongLogTag", "NewApi"})
       // @override
        public void onStop() {

            Log.v(TAG, "Recording Stopped");
            if (mMediaProjection != null) {
                mMediaProjection.unregisterCallback(this);
                mMediaProjection.stop();
                mMediaProjection = null;
            }
            stop();


        }
    };
    private Context mContext;
    protected VideoQuality mRequestedQuality = VideoQuality.DEFAULT_VIDEO_QUALITY.clone();
    protected VideoQuality mQuality = mRequestedQuality.clone();
    ;
    protected SharedPreferences mSettings = null;
    private Semaphore mLock = new Semaphore(0);
    private MP4Config mConfig;
    private DisplayMetrics mMetrics;

    protected int mMaxFps = 0;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    //public H264ScreenStream(AuthenticationActivity authenticationActivity) {
    @SuppressLint("NewApi")
    public H264ScreenStream(Context context) {
        //mAuthenticationActivity = authenticationActivity;
        //mContext = mAuthenticationActivity.getApplicationContext();
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mContext = context;
        mediaProjectionManager =
                ((MediaProjectionManager) mContext.getSystemService(MEDIA_PROJECTION_SERVICE));
        Log.i(TAG, "H264ScreenStream mContext= " +mContext );
        Log.i(TAG, "H264ScreenStream mediaProjectionManager= " +mediaProjectionManager );

        mMode = MODE_MEDIARECORDER_API;
        mPacketizer = new H264Packetizer();
//        createProjector();
    }

    private VirtualDisplay createVirtualDisplay() {
        return mDisplayManager.createVirtualDisplay("Cluster", mQuality.resX, mQuality.resY, mScreenDensity,
                null, 0 /* flags */, null, null );
    }

    public static void setIntentResult(int iResultCode, Intent iData) {
        resultCode = iResultCode;
        data = iData;
    }


    /**

     Sets the configuration of the stream. You can call this method at any time
     and changes will take effect next time you call {@link #configure()}.
     @param videoQuality Quality of the stream
     */
    @SuppressLint("LongLogTag")
    public void setVideoQuality(VideoQuality videoQuality) {
        if (!mRequestedQuality.equals(videoQuality)) {
            Log.d(TAG, "setVideoQuality: " + videoQuality.toString());
            mRequestedQuality = videoQuality.clone();
        }
    }
    public void setPreferences(SharedPreferences prefs) {
        mSettings = prefs;
    }

    /**

     Returns the quality of the stream.
     */
    public VideoQuality getVideoQuality() {
        return mRequestedQuality;
    }
    /**

     Configures the stream. You need to call this before calling {@link #getSessionDescription()}
     to apply your configuration of the stream.
     */
    @SuppressLint("LongLogTag")
    public synchronized void configure() throws IllegalStateException, IOException {
        super.configure();
        Log.i(TAG,"Config called");
        mQuality = mRequestedQuality.clone();
        mConfig = testMediaRecorderAPI();
    }
    @SuppressLint({"NewApi", "LongLogTag"})
    private MP4Config testMediaCodecAPI() throws RuntimeException, IOException {
        Log.d(TAG, "testMediaCodecAPI: ");
        try {
            if (mQuality.resX>=640) {
// Using the MediaCodec API with the buffer method for high resolutions is too slow
                mMode = MODE_MEDIARECORDER_API;
            }
            EncoderDebugger debugger = EncoderDebugger.debug(mSettings, mQuality.resX, mQuality.resY);
            return new MP4Config(debugger.getB64SPS(), debugger.getB64PPS());
        } catch (Exception e) {
// Fallback on the old streaming method using the MediaRecorder API
            Log.e(TAG,"Resolution not supported with the MediaCodec API, we fallback on the old streamign method.");
            mMode = MODE_MEDIARECORDER_API;
            return testMediaRecorderAPI();
        }
    }

    /**

     Starts the stream.
     */
    public synchronized void start() throws IllegalStateException, IOException {
        if (!mStreaming) {
            configure();
            byte[] pps = Base64.decode(mConfig.getB64PPS(), Base64.NO_WRAP);
            byte[] sps = Base64.decode(mConfig.getB64SPS(), Base64.NO_WRAP);
            ((H264Packetizer)mPacketizer).setStreamParameters(pps, sps);
            try {
                super.start();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
    }
    @SuppressLint("LongLogTag")
    public synchronized void stop() {
        super.stop();
        destroyDisplay(mDisplay);
        destroyVirtualDisplay();

        if (mMediaProjection != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mMediaProjection.stop();
            }
            mMediaProjection = null;
        }

        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        Log.d(TAG,"Stream Stoped");
    }
    /**

     Video encoding is done by a MediaRecorder.
     */
    @SuppressLint({"LongLogTag", "NewApi"})
    protected void encodeWithMediaRecorder() throws IOException {
        Log.d(TAG,"Video encoded using the MediaRecorder API sPipeApi=" + sPipeApi);
// We need a local socket to forward data output by the camera to the packetizer
        createSockets();

        try {
            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setVideoSize(mQuality.resX, mQuality.resY);
            mMediaRecorder.setVideoFrameRate(mQuality.framerate);

            // The bandwidth actually consumed is often above what was requested
            mMediaRecorder.setVideoEncodingBitRate((int)(mRequestedQuality.bitrate * 0.8));

            //int rotation =  mContext.getApplicationContext().getWindowManager().getDefaultDisplay().getRotation();
            int orientation = ORIENTATIONS.get(90);
            mMediaRecorder.setOrientationHint(orientation);

            // We write the output of the camera in a local socket instead of a file !
            // This one little trick makes streaming feasible quiet simply: data from the camera
            // can then be manipulated at the other end of the socket
            FileDescriptor fd = null;
            if (sPipeApi == PIPE_API_PFD) {
                fd = mParcelWrite.getFileDescriptor();
            } else  {
                fd = mSender.getFileDescriptor();
            }
            Log.d(TAG,"Set input= " + fd.valid());
            mMediaRecorder.setOutputFile(fd);

            mMediaRecorder.prepare();
// destroyVirtualDisplay();
            Log.d(TAG, "encodeWithMediaRecorder: mQuality: " + mQuality + " mScreenDensity " + mScreenDensity
                    + " mMediaRecorder " + mMediaRecorder.toString());
            mVirtualDisplay = createVirtualDisplay();

            mMediaRecorder.start();
            Log.d(TAG,"Set input= " + fd.valid() + " toString= " + fd.toString());


        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, e.toString());
            throw new ConfNotSupportedException(e.getMessage());
        }

        InputStream is = null;

        if (sPipeApi == PIPE_API_PFD) {
            is = new ParcelFileDescriptor.AutoCloseInputStream(mParcelRead);
        } else  {
            is = mReceiver.getInputStream();
        }

        // This will skip the MPEG4 header if this step fails we can't stream anything :(
        try {
            byte buffer[] = new byte[4];
            // Skip all atoms preceding mdat atom
            while (!Thread.interrupted()) {
                while (is.read() != 'm');
                is.read(buffer,0,3);
                if (buffer[0] == 'd' && buffer[1] == 'a' && buffer[2] == 't') break;
            }
        } catch (IOException e) {
            Log.e(TAG,"Couldn't skip mp4 header :/");
            stop();
            throw e;
        }

        // The packetizer encapsulates the bit stream in an RTP stream and send it over the network
        mPacketizer.setInputStream(is);
        mPacketizer.start();

        mStreaming = true;
        Log.i(TAG, "started");
    }


    /**
     * Video encoding is done by a MediaCodec.
     */
    @SuppressLint({"LongLogTag", "NewApi"})
    protected void encodeWithMediaCodec() throws RuntimeException, IOException {
        this.mVideoBufferInfo = new MediaCodec.BufferInfo();
        mMediaCodec = MediaCodec.createEncoderByType("video/avc");
        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mQuality.resX, mQuality.resY);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mQuality.bitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mQuality.framerate);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mMediaCodec.createInputSurface();
        mMediaCodec.start();

        Log.d(TAG,"MediaCodec started video content w= " + mQuality.resX + " h= " + mQuality.resY + " bitrate= "
                + mQuality.bitrate + " framerate= " + mQuality.framerate);
        mVirtualDisplay = createVirtualDisplay();


        Socket mSocket = new Socket("localhost", 5151);
        mPacketizer.setInputStream( mSocket.getInputStream());
//        mPacketizer.setInputStream(new MediaCodecInputStream(mMediaCodec));
        mPacketizer.start();
        mStreaming = true;
    }


    /**
     * Returns a description of the stream using SDP.
     * This method can only be called after {@link net.majorkernelpanic.streaming.Stream#configure()}.
     * @throws IllegalStateException Thrown when {@link net.majorkernelpanic.streaming.Stream#configure()}
     * was not called.
     */
    public String getSessionDescription() throws IllegalStateException {
        return "m=video "+String.valueOf(getDestinationPorts()[0])+" RTP/AVP 96\r\n" +
                "a=rtpmap:96 H264/90000\r\n" +
                "a=fmtp:96 packetization-mode=1;profile-level-id=" + mConfig.getProfileLevel()
                + ";sprop-parameter-sets=" + mConfig.getB64SPS() + ","+ mConfig.getB64PPS() + ";\r\n";

    }


    @SuppressLint({"LongLogTag", "NewApi"})
    private MP4Config testMediaRecorderAPI() throws RuntimeException, IOException {
        String key = PREF_PREFIX+"h264-mr-"+mRequestedQuality.framerate+","+mRequestedQuality.resX+","+mRequestedQuality.resY;
        Log.d(TAG, "testMediaRecorderAPI: " + key);

        if (mSettings != null && mSettings.contains(key) ) {
            String[] s = mSettings.getString(key, "").split(",");
            return new MP4Config(s[0],s[1],s[2]);
        }

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            throw new StorageUnavailableException("No external storage or external storage not ready !");
        }

        final String TESTFILE = Environment.getExternalStorageDirectory().getPath()+"/droid-test.mp4";

        Log.i(TAG,"Testing H264 support... Test file saved at: "+TESTFILE);

        try {
            File file = new File(TESTFILE);
            file.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            throw new StorageUnavailableException(e.getMessage());
        }
        Log.d(TAG, "Test file has been create.");

        try {
            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setVideoSize(mRequestedQuality.resX, mRequestedQuality.resY);
            mMediaRecorder.setVideoFrameRate(mRequestedQuality.framerate);
            mMediaRecorder.setVideoEncodingBitRate((int)(mRequestedQuality.bitrate * 0.8));
            mMediaRecorder.setOutputFile(TESTFILE);
            mMediaRecorder.setMaxDuration(3000);

            // We wait a little and stop recording
            mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                public void onInfo(MediaRecorder mr, int what, int extra) {
                    Log.d(TAG,"MediaRecorder callback called !");
                    if (what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.d(TAG,"MediaRecorder: MAX_DURATION_REACHED");
                    } else if (what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        Log.d(TAG,"MediaRecorder: MAX_FILESIZE_REACHED");
                    } else if (what==MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN) {
                        Log.d(TAG,"MediaRecorder: INFO_UNKNOWN");
                    } else {
                        Log.d(TAG,"WTF ?");
                    }
                    mLock.release();
                }
            });

            // Start recording
            mMediaRecorder.prepare();

            Log.i(TAG,"H264 Test start...");
// destroyVirtualDisplay();
            Log.d(TAG, "encodeWithMediaRecorder: mQuality: " + mQuality + " mScreenDensity " + mScreenDensity
                    + " mMediaRecorder " + mMediaRecorder.toString());
            mVirtualDisplay = mMediaProjection.createVirtualDisplay("ScreenSharingDemo",
                    mQuality.resX,mQuality.resY, mScreenDensity,
                    0,
                    mMediaRecorder.getSurface(), null , null );
            mMediaRecorder.start();
            Log.i(TAG,"H264 Test started...");
            if (mLock.tryAcquire(6, TimeUnit.SECONDS)) {
                Log.d(TAG,"MediaRecorder callback was called :)");
                Thread.sleep(400);
            } else {
                Log.d(TAG,"MediaRecorder callback was not called after 6 seconds... :(");
            }
        } catch (IOException e) {
            throw new ConfNotSupportedException(e.getMessage());
        } catch (RuntimeException e) {
            throw new ConfNotSupportedException(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            try {
                mMediaRecorder.stop();
            } catch (Exception e) {}
            mMediaRecorder.release();
            mMediaRecorder = null;
        }

        // Retrieve SPS & PPS & ProfileId with MP4Config
        MP4Config config = new MP4Config(TESTFILE);

        // Delete dummy video
        //File file = new File(TESTFILE);
        //if (!file.delete()) Log.e(TAG,"Temp file could not be erased");

        Log.i(TAG,"H264 Test succeeded...");
        Log.i(TAG, "SPS= " + config.getB64SPS() + " PPS= " + config.getB64PPS() + " getPlevel= " + config.getProfileLevel());
        // Save test result
        if (mSettings != null) {
            SharedPreferences.Editor editor = mSettings.edit();
            editor.putString(key, config.getProfileLevel()+","+config.getB64SPS()+","+config.getB64PPS());
            editor.commit();
        }

        return config;
    }

    @SuppressLint({"LongLogTag", "NewApi"})
    private void createProjector() {
        //mMediaProjection = mAuthenticationActivity.getMediaProjection();
        Log.e(TAG, "resultCode = " + resultCode);
        Log.e(TAG, "data = " + data);
        if (mMediaProjection == null) {
            mMediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        }
        mMediaProjection.registerCallback(mMediaProjectionCallback, null);
        mMetrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) mContext.getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "recover activity to get context windowManager = null");
            return;
        }
        if (mMediaProjection == null) {
            Log.e(TAG, "recover activity to get context  mediaProjector = null");
            return;
        }
        windowManager.getDefaultDisplay().getMetrics(mMetrics);
        mScreenDensity = mMetrics.densityDpi;

        Log.i(TAG, "createProjector x= " + mMetrics.widthPixels + " y= " + mMetrics.heightPixels);
    }

    @SuppressLint({"LongLogTag", "NewApi"})
    private void destroyVirtualDisplay() {
        if (mVirtualDisplay != null) {
            Log.i(TAG, "virtual release");
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
    }

//    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect) {
//        SurfaceControl.openTransaction();
//        try {
//            SurfaceControl.setDisplaySurface(display, surface);
//            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
//            SurfaceControl.setDisplayLayerStack(display, 0);
//        } finally {
//            SurfaceControl.closeTransaction();
//        }
//    }
//
//    private static IBinder createDisplay() {
//        return SurfaceControl.createDisplay("scrcpy", true);
//    }

    private static void destroyDisplay(IBinder display) {
        //SurfaceControl.destroyDisplay(display);
    }
}
