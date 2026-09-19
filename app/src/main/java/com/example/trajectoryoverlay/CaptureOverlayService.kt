package com.example.trajectoryoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.content.ContentValues
import android.os.Environment
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat

class CaptureOverlayService: Service() {
    companion object {
        const val ACTION_START="trajectory.START"
        const val ACTION_STOP="trajectory.STOP"
        const val ACTION_TEST="trajectory.TEST"
        const val ACTION_SAVE_DEBUG="trajectory.SAVE_DEBUG"
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
    @Volatile private var saveDebugAfter=0L
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
                addOverlay(secure=false)
                showSyntheticTest()
                mainHandler.postDelayed({ stopEverything() },8000)
                return START_NOT_STICKY
            }
            ACTION_SAVE_DEBUG -> {
                if(running){
                    saveDebugAfter=SystemClock.elapsedRealtime()+650L
                    updateNotification("Print solicitado • volte para a mesa")
                }
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
        val saveIntent=Intent(this,CaptureOverlayService::class.java).apply{action=ACTION_SAVE_DEBUG}
        val savePi=PendingIntent.getService(this,1,saveIntent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Trajectory Overlay")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .addAction(0,"Salvar print",savePi)
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
                message="Overlay OK • aguardando frame ${width}×${height}"
            )
        )

        if(!openCvReady || analyzer==null){
            val msg="ERRO: OpenCV não carregou"
            overlay?.update(AnalysisResult(RectF(0f,0f,width.toFloat(),height.toFloat()),emptyList(),emptyList(),emptyList(),message=msg))
            updateNotification(msg)
            return
        }

        val engineError=TrajectoryEngine.selfTest()
        if(engineError!=null){
            val msg="ERRO interno: $engineError"
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
            if(pixelStride!=4) throw IllegalStateException("pixelStride=$pixelStride")
            val expected=rowStride*height
            if(buffer.remaining()<expected) throw IllegalStateException("buffer=${buffer.remaining()} esperado=$expected")
            val paddedWidth=rowStride/pixelStride
            val bytes=ByteArray(expected)
            buffer.get(bytes)

            val padded=Mat(height,paddedWidth,CvType.CV_8UC4)
            padded.put(0,0,bytes)
            val frame=padded.submat(0,height,0,width).clone()
            padded.release()

            try {
                val result=analyzer!!.analyze(
                    frame,
                    Prefs.showDirect(this),
                    Prefs.showBanks(this),
                    Prefs.showSecondary(this)
                )
                overlay?.showAll=Prefs.showAll(this)
                overlay?.visualDebug=Prefs.visualDebug(this)
                overlay?.update(result)

                if(saveDebugAfter>0L && now>=saveDebugAfter){
                    saveDebugAfter=0L
                    saveDebugImage(frame,result)
                }

                updateNotification(result.message)
            } finally {
                frame.release()
            }
        }catch(t:Throwable){
            val msg="Erro de análise: ${t.javaClass.simpleName}: ${t.message ?: "sem detalhe"}"
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
        overlay=OverlayView(this).also{ it.showAll=Prefs.showAll(this); it.visualDebug=Prefs.visualDebug(this) }
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

    private fun saveDebugImage(frame:Mat,result:AnalysisResult){
        if(Build.VERSION.SDK_INT<29){
            updateNotification("Salvar print requer Android 10 ou mais recente")
            return
        }

        val bitmap=Bitmap.createBitmap(frame.cols(),frame.rows(),Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(frame,bitmap)
        val canvas=Canvas(bitmap)

        val tablePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            color=Color.CYAN;style=Paint.Style.STROKE;strokeWidth=4f
        }
        val ballPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            style=Paint.Style.STROKE;strokeWidth=4f
        }
        val cuePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            color=Color.rgb(80,210,255);style=Paint.Style.STROKE;strokeWidth=6f
        }
        val objectPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            color=Color.rgb(100,245,140);style=Paint.Style.STROKE;strokeWidth=6f
        }
        val bankPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            color=Color.rgb(255,190,90);style=Paint.Style.STROKE;strokeWidth=6f
        }
        val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            color=Color.WHITE;textSize=34f;setShadowLayer(6f,0f,2f,Color.BLACK)
        }
        val bg=Paint().apply{color=Color.argb(175,0,0,0)}

        canvas.drawRect(result.table,tablePaint)
        result.balls.forEach{b->
            ballPaint.color=if(b.cue)Color.CYAN else Color.WHITE
            canvas.drawCircle(b.center.x,b.center.y,b.radius+3f,ballPaint)
        }

        result.trajectories.take(8).forEachIndexed{i,t->
            val alpha=if(i==0)255 else 110
            cuePaint.alpha=alpha
            objectPaint.alpha=alpha
            bankPaint.alpha=alpha
            drawPath(canvas,t.cuePath,cuePaint)
            drawPath(canvas,t.objectPath,if(t.kind==PathKind.DIRECT)objectPaint else bankPaint)
        }

        val text="DEBUG • "+result.message
        val bounds=android.graphics.Rect()
        textPaint.getTextBounds(text,0,text.length,bounds)
        canvas.drawRect(18f,18f,(bounds.width()+58).toFloat(),72f,bg)
        canvas.drawText(text,30f,55f,textPaint)

        val values=ContentValues().apply{
            put(MediaStore.Images.Media.DISPLAY_NAME,"TrajectoryDebug_"+System.currentTimeMillis()+".png")
            put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/TrajectoryOverlay")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
        if(uri!=null){
            contentResolver.openOutputStream(uri)?.use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING,0)
            contentResolver.update(uri,values,null,null)
            updateNotification("Print salvo em Fotos/Pictures/TrajectoryOverlay")
            mainHandler.post{
                android.widget.Toast.makeText(this,"Print de diagnóstico salvo na Galeria",android.widget.Toast.LENGTH_LONG).show()
            }
        }else{
            updateNotification("Falha ao salvar print")
        }
        bitmap.recycle()
    }

    private fun drawPath(canvas:Canvas,points:List<PointF>,paint:Paint){
        if(points.size<2)return
        for(i in 0 until points.size-1){
            canvas.drawLine(points[i].x,points[i].y,points[i+1].x,points[i+1].y,paint)
        }
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
