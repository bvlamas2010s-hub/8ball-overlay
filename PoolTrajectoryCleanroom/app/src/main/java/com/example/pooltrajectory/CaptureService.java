package com.example.pooltrajectory;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    private static final String CHANNEL = "pool_capture";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private final ExecutorService analysis = Executors.newSingleThreadExecutor();
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private final VisionEngine vision = new VisionEngine();
    private final ResultStabilizer stabilizer = new ResultStabilizer();
    private WindowManager wm;
    private OverlayView overlay;
    private long lastFrameMs;
    private int screenW, screenH, densityDpi;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(73, buildNotification());
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        addOverlay();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(projection!=null) return START_NOT_STICKY;
        if(intent==null) {stopSelf();return START_NOT_STICKY;}
        int code=intent.getIntExtra(EXTRA_RESULT_CODE,0);
        Intent data;
        if(Build.VERSION.SDK_INT>=33) data=intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent.class);
        else data=intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if(code==0||data==null){stopSelf();return START_NOT_STICKY;}
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection=m.getMediaProjection(code,data);
        if(projection==null){stopSelf();return START_NOT_STICKY;}
        projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){stopSelf();}},new Handler(getMainLooper()));
        startCapture();
        return START_NOT_STICKY;
    }

    private void startCapture(){
        android.util.DisplayMetrics dm=new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW=dm.widthPixels;screenH=dm.heightPixels;densityDpi=dm.densityDpi;
        reader=ImageReader.newInstance(screenW,screenH,PixelFormat.RGBA_8888,2);
        imageThread=new HandlerThread("pool-image");imageThread.start();imageHandler=new Handler(imageThread.getLooper());
        reader.setOnImageAvailableListener(r->{
            long now=android.os.SystemClock.elapsedRealtime();
            Image image=r.acquireLatestImage();
            if(image==null)return;
            if(now-lastFrameMs<85 || !analyzing.compareAndSet(false,true)){image.close();return;}
            lastFrameMs=now;
            Bitmap b=null;
            try{b=imageToBitmap(image);}finally{image.close();}
            if(b==null){analyzing.set(false);return;}
            Bitmap frame=b;
            analysis.execute(()->{
                try{
                    VisionResult raw=vision.analyze(frame);
                    VisionResult stable=stabilizer.push(raw);
                    overlay.setResult(stable);
                }catch(Throwable ignored){overlay.setResult(null);}finally{frame.recycle();analyzing.set(false);}
            });
        },imageHandler);
        virtualDisplay=projection.createVirtualDisplay("PoolTrajectoryCapture",screenW,screenH,densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,imageHandler);
    }

    private Bitmap imageToBitmap(Image image){
        Image.Plane[] planes=image.getPlanes();if(planes.length==0)return null;
        ByteBuffer buffer=planes[0].getBuffer();int pixelStride=planes[0].getPixelStride(),rowStride=planes[0].getRowStride();
        int rowPadding=rowStride-pixelStride*screenW;
        int paddedW=screenW+rowPadding/pixelStride;
        Bitmap padded=Bitmap.createBitmap(paddedW,screenH,Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped=Bitmap.createBitmap(padded,0,0,screenW,screenH);
        if(cropped!=padded)padded.recycle();
        return cropped;
    }

    private void addOverlay(){
        overlay=new OverlayView(this);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;wm.addView(overlay,p);
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"Pool screen analysis",NotificationManager.IMPORTANCE_LOW);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}
    }
    private Notification buildNotification(){
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setContentTitle("Pool Trajectory Lab").setContentText("Analyzing the captured screen").setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build();
    }

    @Override public void onDestroy(){
        super.onDestroy();
        if(reader!=null){reader.setOnImageAvailableListener(null,null);reader.close();reader=null;}
        if(virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
        if(projection!=null){projection.stop();projection=null;}
        if(imageThread!=null){imageThread.quitSafely();imageThread=null;}
        analysis.shutdownNow();
        if(overlay!=null&&wm!=null){try{wm.removeView(overlay);}catch(Exception ignored){}overlay=null;}
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
