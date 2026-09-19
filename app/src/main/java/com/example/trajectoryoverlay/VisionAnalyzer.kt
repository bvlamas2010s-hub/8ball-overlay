package com.example.trajectoryoverlay

import android.graphics.PointF
import android.graphics.RectF
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

class VisionAnalyzer(private val sensitivityProvider: () -> Int) {
    private var lastGood: AnalysisResult? = null
    private var lastGoodAt: Long = 0L
    private var lastBalls: List<Ball> = emptyList()
    private var lastAimDir: PointF? = null
    private var lastAimAt: Long = 0L

    fun analyze(frameRgba: Mat, direct:Boolean, banks:Boolean, secondary:Boolean): AnalysisResult {
        if(frameRgba.empty()) return empty(frameRgba.width(),frameRgba.height(),"Frame vazio")

        val originalW=frameRgba.width()
        val originalH=frameRgba.height()
        val scale=if(originalW>1200)1200.0/originalW else 1.0

        val work=Mat()
        if(scale<1.0) {
            Imgproc.resize(frameRgba,work,Size(originalW*scale,originalH*scale),0.0,0.0,Imgproc.INTER_AREA)
        } else {
            frameRgba.copyTo(work)
        }

        val detectedTable=detectTable(work)
        val tableSmall=detectedTable ?: fallbackTable(work.width(),work.height())
        val tableSource=if(detectedTable!=null) "mesa auto" else "mesa fallback"

        val table=RectF(
            (tableSmall.left/scale).toFloat(),
            (tableSmall.top/scale).toFloat(),
            (tableSmall.right/scale).toFloat(),
            (tableSmall.bottom/scale).toFloat()
        )

        val ballsSmall=detectBalls(work,tableSmall)
        val cueSmall=ballsSmall.firstOrNull{it.cue}
        val rawAimSmall=if(cueSmall!=null) detectAimDirection(work,tableSmall,cueSmall) else null
        val detectedBalls=ballsSmall.map{
            Ball(
                PointF((it.center.x/scale).toFloat(),(it.center.y/scale).toFloat()),
                (it.radius/scale).toFloat(),
                it.cue,
                it.confidence
            )
        }
        work.release()

        val balls=smoothBalls(detectedBalls)
        val pockets=TrajectoryEngine.pockets(table)
        val now=android.os.SystemClock.elapsedRealtime()
        val aimDir=smoothAim(rawAimSmall,now)

        if(balls.size<2 || balls.none{it.cue}) {
            return AnalysisResult(
                table,balls,pockets,emptyList(),
                message="$tableSource • ${balls.size} bolas • aguardando detecção"
            )
        }

        if(aimDir==null){
            val cached=lastGood
            if(cached!=null && now-lastGoodAt<700L){
                return cached.copy(message="mira temporariamente perdida • mantendo última trajetória")
            }
            return AnalysisResult(
                table,balls,pockets,emptyList(),
                message="$tableSource • ${balls.size} bolas • mira não detectada"
            )
        }

        val trajectories=TrajectoryEngine.calculateFromAim(
            table,balls,aimDir,
            banks=banks,
            secondary=secondary
        )

        val result=AnalysisResult(
            table,balls,pockets,trajectories,
            message="$tableSource • ${balls.size} bolas • mira detectada • ${trajectories.size} trajetória"
        )

        if(trajectories.isNotEmpty()){
            lastGood=result
            lastGoodAt=now
        }
        return result
    }

    private fun smoothAim(current:PointF?,now:Long):PointF?{
        if(current!=null){
            val n=Geometry.norm(current)
            val prev=lastAimDir
            val merged=if(prev!=null && Geometry.dot(prev,n)>0.65f){
                Geometry.norm(
                    PointF(
                        prev.x*.28f+n.x*.72f,
                        prev.y*.28f+n.y*.72f
                    )
                )
            }else n
            lastAimDir=merged
            lastAimAt=now
            return merged
        }
        return if(lastAimDir!=null && now-lastAimAt<450L) lastAimDir else null
    }

    private fun detectAimDirection(rgba:Mat,table:RectF,cue:Ball):PointF?{
        val x=table.left.toInt().coerceAtLeast(0)
        val y=table.top.toInt().coerceAtLeast(0)
        val w=table.width().toInt().coerceAtMost(rgba.cols()-x)
        val h=table.height().toInt().coerceAtMost(rgba.rows()-y)
        if(w<120||h<80)return null

        val roi=rgba.submat(Rect(x,y,w,h))
        val rgb=Mat()
        val hsv=Mat()
        val mask=Mat()
        Imgproc.cvtColor(roi,rgb,Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV)

        Core.inRange(
            hsv,
            Scalar(0.0,0.0,170.0),
            Scalar(179.0,88.0,255.0),
            mask
        )

        val cueLocal=Point(
            (cue.center.x-x).toDouble(),
            (cue.center.y-y).toDouble()
        )

        Imgproc.circle(
            mask,
            cueLocal,
            max(3.0,cue.radius*.72).roundToInt(),
            Scalar(0.0),
            -1
        )

        val lines=Mat()
        val minLen=max(24.0,w*.028)
        val gap=max(8.0,w*.012)
        Imgproc.HoughLinesP(mask,lines,1.0,Math.PI/180.0,28,minLen,gap)

        var bestDir:PointF?=null
        var bestScore=Double.NEGATIVE_INFINITY
        val cueGlobal=cue.center

        if(lines.rows()>0){
            for(i in 0 until lines.rows()){
                val l=lines.get(i,0)?:continue
                if(l.size<4)continue
                val p1=PointF((x+l[0]).toFloat(),(y+l[1]).toFloat())
                val p2=PointF((x+l[2]).toFloat(),(y+l[3]).toFloat())
                val length=Geometry.dist(p1,p2)
                if(length<minLen)continue

                val dToCue=Geometry.distancePointSegment(cueGlobal,p1,p2)
                val maxCueDist=max(cue.radius*5.0f,w*.075f)
                if(dToCue>maxCueDist)continue

                val mid=PointF((p1.x+p2.x)/2f,(p1.y+p2.y)/2f)
                val raw=Geometry.norm(Geometry.sub(p2,p1))
                val fromCue=Geometry.sub(mid,cueGlobal)
                val dir=if(Geometry.dot(raw,fromCue)>=0f) raw else PointF(-raw.x,-raw.y)

                val nearHorizontalEdge =
                    (abs(p1.y-table.top)<cue.radius*2f && abs(p2.y-table.top)<cue.radius*2f) ||
                    (abs(p1.y-table.bottom)<cue.radius*2f && abs(p2.y-table.bottom)<cue.radius*2f)
                val nearVerticalEdge =
                    (abs(p1.x-table.left)<cue.radius*2f && abs(p2.x-table.left)<cue.radius*2f) ||
                    (abs(p1.x-table.right)<cue.radius*2f && abs(p2.x-table.right)<cue.radius*2f)
                val edgePenalty=if(nearHorizontalEdge||nearVerticalEdge)180.0 else 0.0

                val score=length*1.45-dToCue*2.6-edgePenalty
                if(score>bestScore){
                    bestScore=score
                    bestDir=dir
                }
            }
        }

        lines.release()
        mask.release()
        hsv.release()
        rgb.release()
        roi.release()
        return bestDir
    }

    private fun smoothBalls(current:List<Ball>):List<Ball>{
        if(current.isEmpty()) return current
        if(lastBalls.isEmpty()){
            lastBalls=current
            return current
        }

        val smoothed=current.map{b->
            val match=lastBalls
                .filter{it.cue==b.cue}
                .minByOrNull{Geometry.dist(it.center,b.center)}
            if(match!=null){
                val d=Geometry.dist(match.center,b.center)
                val threshold=max(28f,b.radius*2.6f)
                if(d<threshold){
                    val x=match.center.x*.35f+b.center.x*.65f
                    val y=match.center.y*.35f+b.center.y*.65f
                    val r=match.radius*.35f+b.radius*.65f
                    b.copy(center=PointF(x,y),radius=r)
                } else b
            } else b
        }
        lastBalls=smoothed
        return smoothed
    }
    private fun detectTable(rgba:Mat):RectF?{
        val rgb=Mat()
        val hsv=Mat()
        val mask=Mat()
        Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV)

        val hue=dominantFeltHue(hsv)
        val tol=16.0
        val rawLo=hue-tol
        val rawHi=hue+tol

        if(rawLo>=0.0 && rawHi<=179.0){
            Core.inRange(hsv,Scalar(rawLo,35.0,25.0),Scalar(rawHi,255.0,255.0),mask)
        } else {
            val a=Mat()
            val b=Mat()
            if(rawLo<0.0){
                Core.inRange(hsv,Scalar(0.0,35.0,25.0),Scalar(rawHi,255.0,255.0),a)
                Core.inRange(hsv,Scalar(180.0+rawLo,35.0,25.0),Scalar(179.0,255.0,255.0),b)
            } else {
                Core.inRange(hsv,Scalar(rawLo,35.0,25.0),Scalar(179.0,255.0,255.0),a)
                Core.inRange(hsv,Scalar(0.0,35.0,25.0),Scalar(rawHi-180.0,255.0,255.0),b)
            }
            Core.bitwise_or(a,b,mask)
            a.release()
            b.release()
        }

        val closeKernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(23.0,23.0))
        val openKernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(7.0,7.0))
        Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,closeKernel)
        Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_OPEN,openKernel)

        val contours=mutableListOf<MatOfPoint>()
        val hierarchy=Mat()
        Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)

        var best:RectF?=null
        var bestScore=-1.0
        val frameArea=rgba.cols().toDouble()*rgba.rows().toDouble()

        for(c in contours){
            val box=manualBounds(c) ?: continue
            val rw=box.width().toDouble()
            val rh=box.height().toDouble().coerceAtLeast(1.0)
            val ar=rw/rh
            val area=rw*rh
            if(area<frameArea*.16 || ar<1.45 || ar>2.65 || rw<rgba.cols()*.52) continue

            val centerPenalty=
                abs(box.centerX()-rgba.cols()/2f)/rgba.cols().toDouble() +
                abs(box.centerY()-rgba.rows()/2f)/rgba.rows().toDouble()
            val aspectPenalty=abs(ar-2.0)
            val score=area*(1.0-centerPenalty*.32-aspectPenalty*.10)

            if(score>bestScore){
                bestScore=score
                best=box
            }
        }

        contours.forEach{it.release()}
        hierarchy.release()
        closeKernel.release()
        openKernel.release()
        mask.release()
        hsv.release()
        rgb.release()

        return best?.let{expandClamp(it,rgba.cols(),rgba.rows())}
    }

    private fun dominantFeltHue(hsv:Mat):Double{
        val small=Mat()
        Imgproc.resize(hsv,small,Size(80.0,45.0),0.0,0.0,Imgproc.INTER_AREA)
        val hist=DoubleArray(180)
        for(y in 0 until small.rows()){
            for(x in 0 until small.cols()){
                val p=small.get(y,x)?:continue
                if(p.size<3)continue
                val h=p[0].toInt().coerceIn(0,179)
                val s=p[1]
                val v=p[2]
                if(s>45.0 && v>30.0) hist[h]+=1.0+s/255.0
            }
        }
        small.release()

        var bestHue=60
        var best=-1.0
        for(h in hist.indices){
            var score=0.0
            for(d in -4..4){
                val idx=(h+d).coerceIn(0,179)
                score+=hist[idx]
            }
            if(score>best){best=score;bestHue=h}
        }
        return bestHue.toDouble()
    }

    private fun manualBounds(c:MatOfPoint):RectF?{
        val pts=c.toArray()
        if(pts.isEmpty())return null
        var minX=Double.POSITIVE_INFINITY
        var minY=Double.POSITIVE_INFINITY
        var maxX=Double.NEGATIVE_INFINITY
        var maxY=Double.NEGATIVE_INFINITY
        for(p in pts){
            if(p.x<minX)minX=p.x
            if(p.y<minY)minY=p.y
            if(p.x>maxX)maxX=p.x
            if(p.y>maxY)maxY=p.y
        }
        return RectF(minX.toFloat(),minY.toFloat(),maxX.toFloat(),maxY.toFloat())
    }

    private fun expandClamp(r:RectF,w:Int,h:Int):RectF{
        val ex=r.width()*.035f
        val ey=r.height()*.075f
        return RectF(
            (r.left-ex).coerceAtLeast(0f),
            (r.top-ey).coerceAtLeast(0f),
            (r.right+ex).coerceAtMost(w.toFloat()),
            (r.bottom+ey).coerceAtMost(h.toFloat())
        )
    }

    private fun fallbackTable(w:Int,h:Int):RectF{
        val width=w*.94f
        val height=min(h*.76f,width/1.92f)
        return RectF((w-width)/2f,(h-height)/2f,(w+width)/2f,(h+height)/2f)
    }

    private fun detectBalls(rgba:Mat,table:RectF):List<Ball>{
        val x=table.left.toInt().coerceAtLeast(0)
        val y=table.top.toInt().coerceAtLeast(0)
        val w=table.width().toInt().coerceAtMost(rgba.cols()-x)
        val h=table.height().toInt().coerceAtMost(rgba.rows()-y)
        if(w<120||h<70)return emptyList()

        val roi=rgba.submat(Rect(x,y,w,h))
        val gray=Mat()
        Imgproc.cvtColor(roi,gray,Imgproc.COLOR_RGBA2GRAY)
        Imgproc.equalizeHist(gray,gray)
        Imgproc.GaussianBlur(gray,gray,Size(5.0,5.0),1.2)

        val minR=max(5,(w/125.0).roundToInt())
        val maxR=max(minR+4,(w/30.0).roundToInt())
        val base=sensitivityProvider().coerceIn(10,30)
        val passes=listOf(base,(base-4).coerceAtLeast(10),(base+4).coerceAtMost(30)).distinct()

        val raw=mutableListOf<Ball>()
        for(param2 in passes){
            val circles=Mat()
            Imgproc.HoughCircles(
                gray,circles,
                Imgproc.HOUGH_GRADIENT,
                1.15,
                minR*2.0,
                105.0,
                param2.toDouble(),
                minR,maxR
            )

            if(circles.cols()>0){
                for(i in 0 until circles.cols()){
                    val c=circles.get(0,i)?:continue
                    if(c.size<3)continue
                    val center=PointF((x+c[0]).toFloat(),(y+c[1]).toFloat())
                    val rad=c[2].toFloat()

                    if(center.x<table.left+rad*1.1f || center.x>table.right-rad*1.1f ||
                       center.y<table.top+rad*1.1f || center.y>table.bottom-rad*1.1f) continue

                    val pockets=TrajectoryEngine.pockets(table)
                    if(pockets.any{Geometry.dist(it,center)<rad*2.2f})continue

                    val cueScore=whiteness(rgba,center,rad)
                    val candidate=Ball(center,rad,false,cueScore)
                    if(raw.none{Geometry.dist(it.center,candidate.center)<max(it.radius,candidate.radius)*1.05f}){
                        raw.add(candidate)
                    }
                }
            }
            circles.release()

            if(raw.size in 2..16) break
            if(raw.size>16) break
        }

        gray.release()
        roi.release()
        if(raw.isEmpty())return emptyList()

        val plausible=raw.filter{it.radius in minR.toFloat()..(maxR*1.15f)}
        if(plausible.isEmpty())return emptyList()

        val radii=plausible.map{it.radius}.sorted()
        val medianRadius=radii[radii.size/2]
        val clustered=plausible.filter{it.radius>=medianRadius*.72f && it.radius<=medianRadius*1.38f}
        val geometric=(if(clustered.size>=2)clustered else plausible)
            .sortedBy{it.center.x}
            .take(16)

        if(geometric.isEmpty())return emptyList()

        val cueIndex=geometric.indices.maxByOrNull{geometric[it].confidence}?:0
        return geometric.mapIndexed{idx,b->b.copy(cue=idx==cueIndex)}
    }

    private fun whiteness(rgba:Mat,c:PointF,r:Float):Float{
        val rr=max(2,(r*.55f).roundToInt())
        val x0=(c.x-rr).toInt().coerceAtLeast(0)
        val y0=(c.y-rr).toInt().coerceAtLeast(0)
        val x1=(c.x+rr).toInt().coerceAtMost(rgba.cols()-1)
        val y1=(c.y+rr).toInt().coerceAtMost(rgba.rows()-1)
        if(x1<=x0||y1<=y0)return -999f

        val patch=rgba.submat(Rect(x0,y0,x1-x0,y1-y0))
        val rgb=Mat()
        val hsv=Mat()
        Imgproc.cvtColor(patch,rgb,Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV)
        val m=Core.mean(hsv)
        patch.release()
        rgb.release()
        hsv.release()

        val sat=m.`val`[1]
        val value=m.`val`[2]
        return (value*1.1-sat*1.35).toFloat()
    }

    private fun empty(w:Int,h:Int,msg:String)=AnalysisResult(
        fallbackTable(w,h),
        emptyList(),emptyList(),emptyList(),
        message=msg
    )
}
