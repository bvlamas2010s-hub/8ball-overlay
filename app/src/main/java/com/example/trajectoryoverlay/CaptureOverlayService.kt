package com.example.trajectoryoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
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

class CaptureOverlayService: Service() {
    companion object {
        const val ACTION_START="trajectory.START"
        const val ACTION_STOP="trajectory.STOP"
        const val ACTION_TEST="trajectory.TEST"
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
    private var analyzer:VisionAnalyzer?=null
    private var lastFrameAt=0L
    private var lastNotifyAt=0L
    private var running=false
    private var openCvReady=false
    private val mainHandler=Handler(Looper.getMainLooper())
    private val projectionCallback=object:MediaProjection.Callback(){override fun onStop(){stopEverything()}}

    override fun onCreate(){
        super.onCreate()
        createChannel()
        openCvReady=OpenCVLoader.initLocal()
        if(openCvReady) analyzer=VisionAnalyzer{Prefs.sensitivity(this)}
    }

    override fun onBind(intent:Intent?)=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_TEST -> {
                if(!Settings.canDrawOverlays(this)){ stopSelf(); return START_NOT_STICKY }
                stopEverythingResourcesOnly()
                running=true
                startAsForeground("Teste do overlay ativo")
                addOverlay(secure=false)
                showSyntheticTest()
                mainHandler.postDelayed({ stopEverything() },8000)
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> return START_NOT_STICKY
        }

        if(running) stopEverythingResourcesOnly()
        val code=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED)
        val data=if(Build.VERSION.SDK_INT>=33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if(code!=Activity.RESULT_OK||data==null||!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY}
        running=true
        startAsForeground("Iniciando captura…")
        startProjection(code,data)
        return START_NOT_STICKY
    }

    private fun startAsForeground(text:String){
        val n=notification(text)
        if(Build.VERSION.SDK_INT>=29) {
            ServiceCompat.startForeground(this,NOTIF,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF,n)
        }
    }

    private fun notification(text:String):Notification{
        val stopIntent=Intent(this,CaptureOverlayService::class.java).apply{action=ACTION_STOP}
        val stopPi=PendingIntent.getService(this,0,stopIntent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Trajectory Overlay")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .addAction(0,"Parar",stopPi)
            .build()
    }

    private fun updateNotification(text:String){
        val now=SystemClock.elapsedRealtime()
        if(now-lastNotifyAt<900)return
        lastNotifyAt=now
        getSystemService(NotificationManager::class.java).notify(NOTIF,notification(text))
    }

    private fun startProjection(code:Int,data:Intent){
        addOverlay(secure=true)
        val metrics=resources.displayMetrics
        val width=metrics.widthPixels
        val height=metrics.heightPixels
        val density=metrics.densityDpi

        overlay?.update(
            AnalysisResult(
                RectF(0f,0f,width.toFloat(),height.toFloat()),
                emptyList(),emptyList(),emptyList(),
                message="Overlay OK • aguardando frame \${width}×\${height}"
            )
        )

        if(!openCvReady || analyzer==null){
            val msg="ERRO: OpenCV não carregou"
            overlay?.update(AnalysisResult(RectF(0f,0f,width.toFloat(),height.toFloat()),emptyList(),emptyList(),emptyList(),message=msg))
            updateNotification(msg)
            return
        }

        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3)
        val mgr=getSystemService(MediaProjectionManager::class.java)
        projection=mgr.getMediaProjection(code,data).also{it.registerCallback(projectionCallback,mainHandler)}
        display=projection!!.createVirtualDisplay(
            "TrajectoryCapture",
            width,height,density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,null,null
        )
        thread=HandlerThread("trajectory-vision",Process.THREAD_PRIORITY_DISPLAY).also{it.start()}
        reader!!.setOnImageAvailableListener({r->analyzeLatest(r,width,height)},Handler(thread!!.looper))
    }

    private fun analyzeLatest(r:ImageReader,width:Int,height:Int){
        val image=r.acquireLatestImage()?:return
        try{
            val now=SystemClock.elapsedRealtime()
            if(now-lastFrameAt<120)return
            lastFrameAt=now

            val plane=image.planes[0]
            val buffer=plane.buffer
            val pixelStride=plane.pixelStride
            val rowStride=plane.rowStride
            if(pixelStride!=4) throw IllegalStateException("pixelStride=\$pixelStride")
            val expected=rowStride*height
            if(buffer.remaining()<expected) throw IllegalStateException("buffer=\${buffer.remaining()} esperado=\$expected")
            val paddedWidth=rowStride/pixelStride
            val bytes=ByteArray(expected)
            buffer.get(bytes)

            val padded=Mat(height,paddedWidth,CvType.CV_8UC4)
            padded.put(0,0,bytes)
            val frame=padded.submat(0,height,0,width).clone()
            padded.release()

            val result=analyzer!!.analyze(
                frame,
                Prefs.showDirect(this),
                Prefs.showBanks(this),
                Prefs.showSecondary(this)
            )
            frame.release()
            overlay?.showAll=Prefs.showAll(this)
            overlay?.update(result)
            updateNotification(result.message)
        }catch(t:Throwable){
            val msg="Erro de análise: \${t.javaClass.simpleName}: \${t.message ?: "sem detalhe"}"
            val table=RectF(0f,0f,width.toFloat(),height.toFloat())
            overlay?.update(AnalysisResult(table,emptyList(),emptyList(),emptyList(),message=msg))
            updateNotification(msg)
        }finally{
            image.close()
        }
    }

    private fun addOverlay(secure:Boolean){
        try{overlay?.let{wm?.removeView(it)}}catch(_:Throwable){}
        wm=getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlay=OverlayView(this)
        val type=if(Build.VERSION.SDK_INT>=26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        var flags=
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if(secure) flags=flags or WindowManager.LayoutParams.FLAG_SECURE

        val lp=WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,flags,PixelFormat.TRANSLUCENT
        ).apply{gravity=Gravity.TOP or Gravity.START}

        wm?.addView(overlay,lp)
    }

    private fun showSyntheticTest(){
        val m=resources.displayMetrics
        val w=m.widthPixels.toFloat()
        val h=m.heightPixels.toFloat()
        val table=RectF(w*.08f,h*.15f,w*.92f,h*.85f)
        val cue=Ball(PointF(table.left+table.width()*.22f,table.centerY()),22f,true)
        val obj=Ball(PointF(table.left+table.width()*.58f,table.centerY()),22f,false)
        val pocket=PointF(table.right,table.top)
        val ghost=PointF(obj.center.x-44f,obj.center.y)
        val traj=Trajectory(
            PathKind.DIRECT,
            listOf(cue.center,ghost),
            listOf(obj.center,pocket),
            ghost,22f,1f,
            listOf(ghost,PointF(ghost.x,table.bottom))
        )
        overlay?.update(
            AnalysisResult(
                table,
                listOf(cue,obj),
                TrajectoryEngine.pockets(table),
                listOf(traj),
                message="TESTE: overlay e linhas visíveis ✓"
            )
        )
        updateNotification("TESTE: se você vê X azul + linhas, o overlay funciona")
    }

    private fun stopEverythingResourcesOnly(){
        mainHandler.removeCallbacksAndMessages(null)
        try{reader?.setOnImageAvailableListener(null,null)}catch(_:Throwable){}
        try{display?.release()}catch(_:Throwable){};display=null
        try{projection?.unregisterCallback(projectionCallback)}catch(_:Throwable){}
        try{projection?.stop()}catch(_:Throwable){};projection=null
        try{reader?.close()}catch(_:Throwable){};reader=null
        try{thread?.quitSafely()}catch(_:Throwable){};thread=null
        try{overlay?.let{wm?.removeView(it)}}catch(_:Throwable){};overlay=null
    }

    private fun stopEverything(){
        if(!running){
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        running=false
        stopEverythingResourcesOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy(){
        running=false
        stopEverythingResourcesOnly()
        super.onDestroy()
    }

    private fun createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL,"Captura de trajetória",NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
