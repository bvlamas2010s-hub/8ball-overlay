package com.example.trajectoryoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.roundToInt

class CaptureOverlayService: Service() {
    companion object {
        const val ACTION_START="trajectory.START"
        const val ACTION_STOP="trajectory.STOP"
        const val EXTRA_RESULT_CODE="resultCode"
        const val EXTRA_RESULT_DATA="resultData"
        private const val CHANNEL="trajectory_capture"
        private const val NOTIF=501
    }
    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    private var reader:ImageReader?=null
    private var thread:HandlerThread?=null
    private var overlay:OverlayView?=null
    private var wm:WindowManager?=null
    private lateinit var analyzer:VisionAnalyzer
    private var lastFrameAt=0L
    private var running=false
    private val projectionCallback=object:MediaProjection.Callback(){override fun onStop(){stopEverything()}}

    override fun onCreate(){super.onCreate();createChannel();if(!OpenCVLoader.initLocal()){stopSelf();return};analyzer=VisionAnalyzer{Prefs.sensitivity(this)}}
    override fun onBind(intent:Intent?)=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopEverything();return START_NOT_STICKY}
        if(intent?.action!=ACTION_START||running)return START_NOT_STICKY
        val code=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED)
        val data=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if(code!=Activity.RESULT_OK||data==null||!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY}
        startAsForeground()
        startProjection(code,data)
        return START_NOT_STICKY
    }

    private fun startAsForeground(){
        val stopIntent=Intent(this,CaptureOverlayService::class.java).apply{action=ACTION_STOP}
        val stopPi=PendingIntent.getService(this,0,stopIntent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Trajectory Overlay ativo").setContentText("Capturando e analisando a tela. Toque em Parar para encerrar.").setOngoing(true).addAction(0,"Parar",stopPi).build()
        if(Build.VERSION.SDK_INT>=29) ServiceCompat.startForeground(this,NOTIF,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTIF,n)
    }

    private fun startProjection(code:Int,data:Intent){
        running=true
        addOverlay()
        val metrics=resources.displayMetrics
        val width=metrics.widthPixels;val height=metrics.heightPixels;val density=metrics.densityDpi
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
        val mgr=getSystemService(MediaProjectionManager::class.java)
        projection=mgr.getMediaProjection(code,data).also{it.registerCallback(projectionCallback,Handler(Looper.getMainLooper()))}
        display=projection!!.createVirtualDisplay("TrajectoryCapture",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,null)
        thread=HandlerThread("trajectory-vision",Process.THREAD_PRIORITY_DISPLAY).also{it.start()}
        reader!!.setOnImageAvailableListener({r->analyzeLatest(r,width,height)},Handler(thread!!.looper))
    }

    private fun analyzeLatest(r:ImageReader,width:Int,height:Int){
        val image=r.acquireLatestImage()?:return
        try{
            val now=SystemClock.elapsedRealtime();if(now-lastFrameAt<130)return;lastFrameAt=now
            val plane=image.planes[0];val buffer=plane.buffer;val pixelStride=plane.pixelStride;val rowStride=plane.rowStride
            val paddedWidth=rowStride/pixelStride
            val bytes=ByteArray(buffer.remaining());buffer.get(bytes)
            val padded=Mat(height,paddedWidth,CvType.CV_8UC4);padded.put(0,0,bytes)
            val frame=padded.submat(0,height,0,width).clone();padded.release()
            val result=analyzer.analyze(frame,Prefs.showDirect(this),Prefs.showBanks(this),Prefs.showSecondary(this));frame.release()
            overlay?.showAll=Prefs.showAll(this);overlay?.update(result)
        }catch(_:Throwable){}finally{image.close()}
    }

    private fun addOverlay(){
        wm=getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlay=OverlayView(this)
        val type=if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START}
        wm?.addView(overlay,lp)
    }

    private fun stopEverything(){
        if(!running){stopSelf();return};running=false
        try{reader?.setOnImageAvailableListener(null,null)}catch(_:Throwable){}
        try{display?.release()}catch(_:Throwable){};display=null
        try{projection?.unregisterCallback(projectionCallback)}catch(_:Throwable){}
        try{projection?.stop()}catch(_:Throwable){};projection=null
        try{reader?.close()}catch(_:Throwable){};reader=null
        try{thread?.quitSafely()}catch(_:Throwable){};thread=null
        try{overlay?.let{wm?.removeView(it)}}catch(_:Throwable){};overlay=null
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy(){stopEverything();super.onDestroy()}

    private fun createChannel(){
        if(Build.VERSION.SDK_INT>=26){val m=getSystemService(NotificationManager::class.java);m.createNotificationChannel(NotificationChannel(CHANNEL,"Captura de trajetória",NotificationManager.IMPORTANCE_LOW))}
    }
}
