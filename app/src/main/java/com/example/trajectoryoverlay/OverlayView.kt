package com.example.trajectoryoverlay

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.max

class OverlayView(context: Context): View(context) {
    @Volatile private var result: AnalysisResult? = null
    @Volatile var showAll: Boolean = true

    private val directPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(105,200,255);strokeWidth=5f;style=Paint.Style.STROKE;pathEffect=DashPathEffect(floatArrayOf(16f,10f),0f)}
    private val objectPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(94,242,139);strokeWidth=5f;style=Paint.Style.STROKE;pathEffect=DashPathEffect(floatArrayOf(16f,10f),0f)}
    private val bankPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(255,188,99);strokeWidth=5f;style=Paint.Style.STROKE;pathEffect=DashPathEffect(floatArrayOf(12f,9f),0f)}
    private val secondaryPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(255,105,105);strokeWidth=4f;style=Paint.Style.STROKE;pathEffect=DashPathEffect(floatArrayOf(7f,9f),0f)}
    private val ghostPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;strokeWidth=4f;style=Paint.Style.STROKE;alpha=180}
    private val ballPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=3f}
    private val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=28f;setShadowLayer(4f,0f,1f,Color.BLACK)}
    private val bgPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(115,0,0,0)}

    fun update(r: AnalysisResult){result=r;postInvalidate()}

    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        // Sempre mostra um indicador para confirmar que o overlay realmente está visível.
        val r=result
        val statusText = r?.message ?: "Overlay ativo • aguardando frames"
        val statusBounds=Rect(); textPaint.getTextBounds(statusText,0,statusText.length,statusBounds)
        canvas.drawRoundRect(18f,18f,statusBounds.width()+48f,64f,12f,12f,bgPaint)
        canvas.drawText(statusText,30f,50f,textPaint)
        // Marca visual de diagnóstico independente da detecção.
        val diagPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.CYAN;strokeWidth=4f;alpha=180}
        canvas.drawLine(width-90f,28f,width-28f,90f,diagPaint)
        canvas.drawLine(width-28f,28f,width-90f,90f,diagPaint)
        if(r==null) return
        val trajectories=if(showAll)r.trajectories else r.trajectories.take(1)
        val maxLines=if(showAll)60 else 1
        trajectories.take(maxLines).forEachIndexed{i,t->
            val alpha=if(i==0)230 else max(32,105-i)
            drawPolyline(canvas,t.cuePath,directPaint,alpha)
            drawPolyline(canvas,t.objectPath,if(t.kind==PathKind.DIRECT)objectPaint else bankPaint,alpha)
            if(i<8 && t.secondaryCuePath.size>1)drawPolyline(canvas,t.secondaryCuePath,secondaryPaint,if(i==0)220 else 85)
            if(i==0){
                ghostPaint.alpha=180; val rad=r.balls.map{it.radius}.average().toFloat().coerceAtLeast(8f);canvas.drawCircle(t.ghost.x,t.ghost.y,rad,ghostPaint)
            }
        }
        r.balls.forEach{b->ballPaint.color=if(b.cue)Color.WHITE else Color.argb(170,255,255,255);canvas.drawCircle(b.center.x,b.center.y,b.radius+2f,ballPaint)}
        // status já é desenhado no topo antes das trajetórias
    }
    private fun drawPolyline(c:Canvas,pts:List<PointF>,p:Paint,a:Int){if(pts.size<2)return;p.alpha=a;for(i in 0 until pts.size-1)c.drawLine(pts[i].x,pts[i].y,pts[i+1].x,pts[i+1].y,p)}
}
