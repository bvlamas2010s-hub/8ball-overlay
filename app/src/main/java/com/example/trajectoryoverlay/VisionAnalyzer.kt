package com.example.trajectoryoverlay

import android.graphics.PointF
import android.graphics.RectF
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

class VisionAnalyzer(private val sensitivityProvider: () -> Int) {
    private var lastTable: RectF? = null

    fun analyze(frameRgba: Mat, direct:Boolean, banks:Boolean, secondary:Boolean): AnalysisResult {
        if(frameRgba.empty()) return empty(frameRgba.width(),frameRgba.height(),"Frame vazio")
        val originalW=frameRgba.width(); val originalH=frameRgba.height()
        val scale=if(originalW>1100)1100.0/originalW else 1.0
        val work=Mat()
        if(scale<1.0) Imgproc.resize(frameRgba,work,Size(originalW*scale,originalH*scale)) else frameRgba.copyTo(work)
        val tableSmall=detectTable(work) ?: fallbackTable(work.width(),work.height())
        val table=RectF((tableSmall.left/scale).toFloat(),(tableSmall.top/scale).toFloat(),(tableSmall.right/scale).toFloat(),(tableSmall.bottom/scale).toFloat())
        lastTable=table
        val ballsSmall=detectBalls(work,tableSmall)
        val balls=ballsSmall.map{Ball(PointF((it.center.x/scale).toFloat(),(it.center.y/scale).toFloat()),(it.radius/scale).toFloat(),it.cue,it.confidence)}
        work.release()
        if(balls.size<2 || balls.none{it.cue}) return AnalysisResult(table,balls,TrajectoryEngine.pockets(table),emptyList(),message="Detectando bolas… ${balls.size}")
        val pockets=TrajectoryEngine.pockets(table)
        val trajectories=TrajectoryEngine.calculate(table,balls,pockets,direct,banks,secondary)
        return AnalysisResult(table,balls,pockets,trajectories,message="${balls.size} bolas • ${trajectories.size} trajetórias")
    }

    private fun detectTable(rgba:Mat):RectF?{
        val rgb=Mat(); val hsv=Mat(); val mask=Mat();
        Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV)
        val cx=(hsv.cols()/2).coerceIn(0,hsv.cols()-1); val cy=(hsv.rows()/2).coerceIn(0,hsv.rows()-1)
        val center=hsv.get(cy,cx) ?: doubleArrayOf(60.0,120.0,100.0)
        val h=center[0]; val s=center[1]; val v=center[2]
        val lowS=max(25.0,s-100.0); val lowV=max(20.0,v-100.0)
        val loH=max(0.0,h-20.0); val hiH=min(179.0,h+20.0)
        if(loH<=hiH) Core.inRange(hsv,Scalar(loH,lowS,lowV),Scalar(hiH,255.0,255.0),mask)
        else Core.inRange(hsv,Scalar(0.0,lowS,lowV),Scalar(179.0,255.0,255.0),mask)
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(19.0,19.0))
        Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel)
        val contours=mutableListOf<MatOfPoint>(); Imgproc.findContours(mask,contours,Mat(),Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
        var best:RectF?=null; var score=-1.0
        val frameArea=rgba.cols().toDouble()*rgba.rows().toDouble()
        for(c in contours){
            val pts=c.toArray()
            if(pts.isEmpty()) continue
            var minX=Double.POSITIVE_INFINITY
            var minY=Double.POSITIVE_INFINITY
            var maxX=Double.NEGATIVE_INFINITY
            var maxY=Double.NEGATIVE_INFINITY
            for(p in pts){
                if(p.x<minX) minX=p.x
                if(p.y<minY) minY=p.y
                if(p.x>maxX) maxX=p.x
                if(p.y>maxY) maxY=p.y
            }
            val rw=(maxX-minX).coerceAtLeast(1.0)
            val rh=(maxY-minY).coerceAtLeast(1.0)
            val ar=rw/rh
            val area=rw*rh
            if(area<frameArea*.18 || ar<1.35 || ar>2.8 || rw<rgba.cols()*.5) continue
            val centerPenalty =
                kotlin.math.abs((minX+rw/2.0)-rgba.cols()/2.0)/rgba.cols().toDouble() +
                kotlin.math.abs((minY+rh/2.0)-rgba.rows()/2.0)/rgba.rows().toDouble()
            val sc=area*(1.0-centerPenalty*.35)-kotlin.math.abs(ar-2.0)*area*.12
            if(sc>score){
                score=sc
                best=RectF(minX.toFloat(),minY.toFloat(),maxX.toFloat(),maxY.toFloat())
            }
        }
        contours.forEach{it.release()};kernel.release();mask.release();hsv.release();rgb.release()
        return best?.let{expandClamp(it,rgba.cols(),rgba.rows())}
    }

    private fun expandClamp(r:RectF,w:Int,h:Int):RectF{
        val ex=r.width()*.035f; val ey=r.height()*.06f
        return RectF(
            (r.left-ex).coerceAtLeast(0f),
            (r.top-ey).coerceAtLeast(0f),
            (r.right+ex).coerceAtMost(w.toFloat()),
            (r.bottom+ey).coerceAtMost(h.toFloat())
        )
    }
    private fun fallbackTable(w:Int,h:Int):RectF{
        val width=w*.92f; val height=min(h*.72f,width/1.9f)
        return RectF((w-width)/2f,(h-height)/2f,(w+width)/2f,(h+height)/2f)
    }

    private fun detectBalls(rgba:Mat,table:RectF):List<Ball>{
        val x=table.left.toInt().coerceAtLeast(0);val y=table.top.toInt().coerceAtLeast(0)
        val w=(table.width()).toInt().coerceAtMost(rgba.cols()-x);val h=(table.height()).toInt().coerceAtMost(rgba.rows()-y)
        if(w<100||h<60)return emptyList()
        val roi=rgba.submat(Rect(x,y,w,h));val gray=Mat();Imgproc.cvtColor(roi,gray,Imgproc.COLOR_RGBA2GRAY);Imgproc.medianBlur(gray,gray,5)
        val circles=Mat();val minR=max(5,(w/105.0).toInt());val maxR=max(minR+3,(w/34.0).toInt())
        Imgproc.HoughCircles(gray,circles,Imgproc.HOUGH_GRADIENT,1.25,minR*2.1,110.0,sensitivityProvider().toDouble(),minR,maxR)
        val raw=mutableListOf<Ball>()
        if(circles.cols()>0){
            for(i in 0 until circles.cols()){
                val c=circles.get(0,i)?:continue; if(c.size<3)continue
                val center=PointF((x+c[0]).toFloat(),(y+c[1]).toFloat());val rad=c[2].toFloat()
                if(center.x<table.left+rad||center.x>table.right-rad||center.y<table.top+rad||center.y>table.bottom-rad)continue
                val pockets=TrajectoryEngine.pockets(table)
                if(pockets.any{Geometry.dist(it,center)<rad*2.6f})continue
                val cueScore=whiteness(rgba,center,rad)
                raw.add(Ball(center,rad,false,cueScore))
            }
        }
        circles.release();gray.release();roi.release()
        if(raw.isEmpty())return emptyList()
        val dedup=mutableListOf<Ball>()
        for(b in raw.sortedByDescending{it.confidence}) if(dedup.none{Geometry.dist(it.center,b.center)<max(it.radius,b.radius)*1.1f})dedup.add(b)
        val limited=dedup.take(16).toMutableList(); if(limited.isEmpty())return limited
        val cueIndex=limited.indices.maxByOrNull{limited[it].confidence}?:0
        return limited.mapIndexed{idx,b->b.copy(cue=idx==cueIndex)}
    }

    private fun whiteness(rgba:Mat,c:PointF,r:Float):Float{
        val rr=max(2,(r*.55f).toInt());val x0=(c.x-rr).toInt().coerceAtLeast(0);val y0=(c.y-rr).toInt().coerceAtLeast(0)
        val x1=(c.x+rr).toInt().coerceAtMost(rgba.cols()-1);val y1=(c.y+rr).toInt().coerceAtMost(rgba.rows()-1)
        if(x1<=x0||y1<=y0)return 0f
        val patch=rgba.submat(Rect(x0,y0,x1-x0,y1-y0));val rgb=Mat();val hsv=Mat();Imgproc.cvtColor(patch,rgb,Imgproc.COLOR_RGBA2RGB);Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV)
        val m=Core.mean(hsv);patch.release();rgb.release();hsv.release()
        return (m.`val`[2]-m.`val`[1]*1.25).toFloat()
    }

    private fun empty(w:Int,h:Int,msg:String)=AnalysisResult(fallbackTable(w,h),emptyList(),emptyList(),emptyList(),message=msg)
}
