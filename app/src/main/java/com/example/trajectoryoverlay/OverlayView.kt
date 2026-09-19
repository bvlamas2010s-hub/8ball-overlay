package com.example.trajectoryoverlay

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context): View(context) {
    @Volatile private var result: AnalysisResult? = null
    @Volatile var showAll: Boolean = false
    @Volatile var visualDebug: Boolean = false

    private val directPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.WHITE;strokeWidth=6f;style=Paint.Style.STROKE
    }
    private val objectPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(94,242,139);strokeWidth=6f;style=Paint.Style.STROKE
    }
    private val bankPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(255,188,99);strokeWidth=6f;style=Paint.Style.STROKE
    }
    private val secondaryPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(255,105,105);strokeWidth=4f;style=Paint.Style.STROKE
        pathEffect=DashPathEffect(floatArrayOf(7f,9f),0f)
    }
    private val ghostPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.WHITE;strokeWidth=4f;style=Paint.Style.STROKE;alpha=180
    }
    private val ballPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        style=Paint.Style.STROKE;strokeWidth=3f
    }
    private val tablePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.CYAN;style=Paint.Style.STROKE;strokeWidth=2f;alpha=95
        pathEffect=DashPathEffect(floatArrayOf(10f,10f),0f)
    }
    private val pocketPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.YELLOW;style=Paint.Style.STROKE;strokeWidth=2f;alpha=140
    }
    private val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.WHITE;textSize=27f;setShadowLayer(4f,0f,1f,Color.BLACK)
    }
    private val bgPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(155,0,0,0)}
    private val diagPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.CYAN;strokeWidth=5f;alpha=220}

    fun update(r: AnalysisResult){result=r;postInvalidate()}

    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)

        val r=result
        val statusText=(r?.message ?: "Overlay ativo • aguardando frames").take(100)
        val bounds=Rect()
        textPaint.getTextBounds(statusText,0,statusText.length,bounds)
        val right=min(width-18f,bounds.width()+52f)
        canvas.drawRoundRect(18f,18f,right,66f,12f,12f,bgPaint)
        canvas.save()
        canvas.clipRect(18f,18f,right,66f)
        canvas.drawText(statusText,30f,51f,textPaint)
        canvas.restore()

        // Marca fixa: se este X não aparece, o problema é o overlay, não a física.
        canvas.drawLine(width-94f,26f,width-28f,92f,diagPaint)
        canvas.drawLine(width-28f,26f,width-94f,92f,diagPaint)

        if(r==null)return

        if(visualDebug){
            // Diagnóstico opcional: mesa, caçapas e bolas detectadas.
            canvas.drawRect(r.table,tablePaint)
            val pocketRadius=max(8f,r.balls.map{it.radius}.average().toFloat().coerceAtLeast(8f)*.55f)
            r.pockets.forEach{canvas.drawCircle(it.x,it.y,pocketRadius,pocketPaint)}
        }

        val trajectories=if(showAll)r.trajectories else r.trajectories.take(1)
        val maxLines=if(showAll)8 else 1
        trajectories.take(maxLines).forEachIndexed{i,t->
            val alpha=if(i==0)245 else max(34,115-i)
            drawPolyline(canvas,t.cuePath,directPaint,alpha)
            drawPolyline(canvas,t.objectPath,if(t.kind==PathKind.DIRECT)objectPaint else bankPaint,alpha)
            if(i<10 && t.secondaryCuePath.size>1){
                drawPolyline(canvas,t.secondaryCuePath,secondaryPaint,if(i==0)225 else 90)
            }
            if(i==0 && t.objectPath.isNotEmpty()){
                ghostPaint.alpha=220
                val rad=r.balls.map{it.radius}.average().toFloat().coerceAtLeast(8f)
                canvas.drawCircle(t.ghost.x,t.ghost.y,rad,ghostPaint)
            }
        }

        if(visualDebug){
            r.balls.forEach{b->
                ballPaint.color=if(b.cue)Color.CYAN else Color.argb(220,255,255,255)
                ballPaint.alpha=if(b.cue)255 else 190
                canvas.drawCircle(b.center.x,b.center.y,b.radius+3f,ballPaint)
            }
        }
    }

    private fun drawPolyline(c:Canvas,pts:List<PointF>,p:Paint,a:Int){
        if(pts.size<2)return
        p.alpha=a
        for(i in 0 until pts.size-1){
            c.drawLine(pts[i].x,pts[i].y,pts[i+1].x,pts[i+1].y,p)
        }
    }
}
