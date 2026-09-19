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
    private var aimCandidate: PointF? = null
    private var aimCandidateFrames = 0

    private var tableCandidate: RectF? = null
    private var tableCandidateFrames = 0
    private var confirmedTable: RectF? = null
    private var tableLostFrames = 0

    private var stablePlayFrames = 0
    private var previousCueCenter: PointF? = null
    private var previousBallCount = 0

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

        val rawTable=detectTable(work)
        val playableTable=if(rawTable!=null && tableLooksPlayable(work,rawTable)) rawTable else null
        val tableSmall=stabilizeTable(playableTable)

        if(tableSmall==null){
            work.release()
            resetAimState()
            stablePlayFrames=0
            previousCueCenter=null
            previousBallCount=0
            lastGood=null
            return AnalysisResult(
                fallbackTable(originalW,originalH),
                emptyList(),
                emptyList(),
                emptyList(),
                message="aguardando mesa de jogo"
            )
        }

        val table=RectF(
            (tableSmall.left/scale).toFloat(),
            (tableSmall.top/scale).toFloat(),
            (tableSmall.right/scale).toFloat(),
            (tableSmall.bottom/scale).toFloat()
        )

        val ballsSmall=detectBalls(work,tableSmall)
        val cueSmall=ballsSmall.firstOrNull{it.cue}
        val playStable=updatePlayStability(ballsSmall)
        val rawAimSmall=if(playStable && cueSmall!=null) detectAimDirection(work,tableSmall,cueSmall) else null

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

        if(balls.size<2 || balls.none{it.cue}){
            resetAimState()
            return AnalysisResult(
                table,balls,pockets,emptyList(),
                message="mesa detectada • aguardando bolas estáveis"
            )
        }

        if(!playStable){
            resetAimState(keepLastStable=true)
            return AnalysisResult(
                table,balls,pockets,emptyList(),
                message="mesa detectada • estabilizando bolas"
            )
        }

        val aimDir=smoothAim(rawAimSmall,now)

        if(aimDir==null){
            val progress=aimCandidateFrames.coerceIn(0,3)
            return AnalysisResult(
                table,balls,pockets,emptyList(),
                message=if(progress>0)
                    "mesa OK • calibrando mira "+progress+"/3"
                else
                    "mesa OK • aguardando linha de mira"
            )
        }

        val trajectories=TrajectoryEngine.calculateFromAim(
            table,balls,aimDir,
            banks=banks,
            secondary=secondary
        )

        val result=AnalysisResult(
            table,balls,pockets,trajectories,
            message="mesa OK • mira estável • "+trajectories.size+" trajetória"
        )

        if(trajectories.isNotEmpty()){
            lastGood=result
            lastGoodAt=now
        }
        return result
    }

    private fun smoothAim(current:PointF?,now:Long):PointF?{
        if(current==null){
            aimCandidate=null
            aimCandidateFrames=0
            return if(lastAimDir!=null && now-lastAimAt<220L) lastAimDir else null
        }

        val n=Geometry.norm(current)
        val candidate=aimCandidate
        if(candidate!=null && Geometry.dot(candidate,n)>0.965f){
            aimCandidate=Geometry.norm(
                PointF(
                    candidate.x*.45f+n.x*.55f,
                    candidate.y*.45f+n.y*.55f
                )
            )
            aimCandidateFrames++
        }else{
            aimCandidate=n
            aimCandidateFrames=1
        }

        if(aimCandidateFrames<3)return null

        val stable=aimCandidate ?: return null
        val previous=lastAimDir
        val merged=if(previous!=null && Geometry.dot(previous,stable)>0.94f){
            Geometry.norm(
                PointF(
                    previous.x*.60f+stable.x*.40f,
                    previous.y*.60f+stable.y*.40f
                )
            )
        }else stable

        lastAimDir=merged
        lastAimAt=now
        return merged
    }

    private fun detectAimDirection(rgba:Mat,table:RectF,cue:Ball):PointF?{
        val x=table.left.toInt().coerceAtLeast(0)
        val y=table.top.toInt().coerceAtLeast(0)
        val w=table.width().toInt().coerceAtMost(rgba.cols()-x)
        val h=table.height().toInt().coerceAtMost(rgba.rows()-y)
        if(w<160||h<90)return null

        val roi=rgba.submat(Rect(x,y,w,h))
        val rgb=Mat()
        val hsv=Mat()
        val mask=Mat()
        Imgproc.cvtColor(roi,rgb,Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV)

        Core.inRange(
            hsv,
            Scalar(0.0,0.0,175.0),
            Scalar(179.0,78.0,255.0),
            mask
        )

        val cueLocal=Point(
            (cue.center.x-x).toDouble(),
            (cue.center.y-y).toDouble()
        )

        Imgproc.circle(
            mask,
            cueLocal,
            max(4.0,cue.radius*.85).roundToInt(),
            Scalar(0.0),
            -1
        )

        val lines=Mat()
        val minLen=max(cue.radius*3.2,w*.055)
        val gap=max(7.0,w*.010)
        Imgproc.HoughLinesP(mask,lines,1.0,Math.PI/180.0,34,minLen,gap)

        var bestDir:PointF?=null
        var bestScore=Double.NEGATIVE_INFINITY
        val cueGlobal=cue.center
        val maxCueDist=max(cue.radius*2.1f,w*.020f)

        if(lines.rows()>0){
            for(i in 0 until lines.rows()){
                val l=lines.get(i,0)?:continue
                if(l.size<4)continue

                val p1=PointF((x+l[0]).toFloat(),(y+l[1]).toFloat())
                val p2=PointF((x+l[2]).toFloat(),(y+l[3]).toFloat())
                val length=Geometry.dist(p1,p2)
                if(length<minLen)continue

                val dToCue=Geometry.distancePointSegment(cueGlobal,p1,p2)
                if(dToCue>maxCueDist)continue

                val nearHorizontalEdge =
                    (abs(p1.y-table.top)<cue.radius*2.5f && abs(p2.y-table.top)<cue.radius*2.5f) ||
                    (abs(p1.y-table.bottom)<cue.radius*2.5f && abs(p2.y-table.bottom)<cue.radius*2.5f)
                val nearVerticalEdge =
                    (abs(p1.x-table.left)<cue.radius*2.5f && abs(p2.x-table.left)<cue.radius*2.5f) ||
                    (abs(p1.x-table.right)<cue.radius*2.5f && abs(p2.x-table.right)<cue.radius*2.5f)
                if(nearHorizontalEdge||nearVerticalEdge)continue

                val raw=Geometry.norm(Geometry.sub(p2,p1))
                val localCue=PointF((cue.center.x-x),(cue.center.y-y))
                val supportDistance=min(w*.30f,max(cue.radius*10f,w*.14f))
                val forwardSupport=raySupport(mask,localCue,raw,cue.radius,supportDistance)
                val backward=PointF(-raw.x,-raw.y)
                val backwardSupport=raySupport(mask,localCue,backward,cue.radius,supportDistance)

                val dir:PointF
                val support:Double
                if(forwardSupport>=backwardSupport){
                    dir=raw
                    support=forwardSupport
                }else{
                    dir=backward
                    support=backwardSupport
                }

                if(support<0.20)continue

                val score=length*1.25 + support*260.0 - dToCue*4.0
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

    private fun raySupport(mask:Mat,origin:PointF,dir:PointF,cueRadius:Float,maxDistance:Float):Double{
        val start=max(cueRadius*1.35f,8f)
        val samples=44
        var hits=0
        var valid=0

        for(i in 0 until samples){
            val t=start + (maxDistance-start)*(i.toFloat()/(samples-1).coerceAtLeast(1))
            val px=(origin.x+dir.x*t).roundToInt()
            val py=(origin.y+dir.y*t).roundToInt()
            if(px<2||py<2||px>=mask.cols()-2||py>=mask.rows()-2)continue

            valid++
            var on=false
            loop@ for(dy in -2..2){
                for(dx in -2..2){
                    val v=mask.get(py+dy,px+dx)
                    if(v!=null && v.isNotEmpty() && v[0]>0.0){
                        on=true
                        break@loop
                    }
                }
            }
            if(on)hits++
        }

        return if(valid<10)0.0 else hits.toDouble()/valid.toDouble()
    }

    private fun stabilizeTable(current:RectF?):RectF?{
        if(current==null){
            tableLostFrames++
            tableCandidateFrames=0
            tableCandidate=null
            if(tableLostFrames>2)confirmedTable=null
            return if(tableLostFrames<=2)confirmedTable else null
        }

        tableLostFrames=0
        val prev=tableCandidate
        val similar=prev!=null &&
            abs(prev.centerX()-current.centerX())<current.width()*.035f &&
            abs(prev.centerY()-current.centerY())<current.height()*.050f &&
            abs(prev.width()-current.width())<current.width()*.09f &&
            abs(prev.height()-current.height())<current.height()*.10f

        if(similar){
            tableCandidate=RectF(
                prev!!.left*.45f+current.left*.55f,
                prev.top*.45f+current.top*.55f,
                prev.right*.45f+current.right*.55f,
                prev.bottom*.45f+current.bottom*.55f
            )
            tableCandidateFrames++
        }else{
            tableCandidate=RectF(current)
            tableCandidateFrames=1
        }

        if(tableCandidateFrames>=3){
            confirmedTable=RectF(tableCandidate!!)
        }
        return confirmedTable
    }

    private fun tableLooksPlayable(rgba:Mat,table:RectF):Boolean{
        if(table.width()<rgba.cols()*.52f || table.height()<rgba.rows()*.28f)return false

        val centerBrightness=patchBrightness(
            rgba,
            table.centerX(),
            table.centerY(),
            min(table.width(),table.height())*.035f
        )
        if(centerBrightness<18.0)return false

        val points=listOf(
            PointF(table.left,table.top),
            PointF(table.centerX(),table.top),
            PointF(table.right,table.top),
            PointF(table.left,table.bottom),
            PointF(table.centerX(),table.bottom),
            PointF(table.right,table.bottom)
        )
        val radius=min(table.width()/32f,table.height()/14f).coerceAtLeast(8f)
        var dark=0
        for(p in points){
            val b=patchBrightness(rgba,p.x,p.y,radius)
            if(b<centerBrightness*.74 || centerBrightness-b>38.0)dark++
        }
        return dark>=3
    }

    private fun patchBrightness(rgba:Mat,cx:Float,cy:Float,radius:Float):Double{
        val x0=(cx-radius).roundToInt().coerceIn(0,rgba.cols()-1)
        val y0=(cy-radius).roundToInt().coerceIn(0,rgba.rows()-1)
        val x1=(cx+radius).roundToInt().coerceIn(0,rgba.cols()-1)
        val y1=(cy+radius).roundToInt().coerceIn(0,rgba.rows()-1)
        var sum=0.0
        var count=0

        var yy=y0
        while(yy<=y1){
            var xx=x0
            while(xx<=x1){
                val p=rgba.get(yy,xx)
                if(p!=null && p.size>=3){
                    sum+=(p[0]+p[1]+p[2])/3.0
                    count++
                }
                xx+=3
            }
            yy+=3
        }
        return if(count==0)255.0 else sum/count.toDouble()
    }

    private fun updatePlayStability(balls:List<Ball>):Boolean{
        val cue=balls.firstOrNull{it.cue}
        if(cue==null || balls.size<2){
            stablePlayFrames=0
            previousCueCenter=null
            previousBallCount=balls.size
            return false
        }

        val prevCue=previousCueCenter
        val countSimilar=abs(previousBallCount-balls.size)<=1
        val cueStable=prevCue!=null && Geometry.dist(prevCue,cue.center)<max(18f,cue.radius*1.5f)

        stablePlayFrames=if(cueStable && countSimilar) stablePlayFrames+1 else 1
        previousCueCenter=PointF(cue.center.x,cue.center.y)
        previousBallCount=balls.size
        return stablePlayFrames>=3
    }

    private fun resetAimState(keepLastStable:Boolean=false){
        aimCandidate=null
        aimCandidateFrames=0
        if(!keepLastStable){
            lastAimDir=null
            lastAimAt=0L
        }
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
        if(w<180||h<100)return emptyList()

        val roi=rgba.submat(Rect(x,y,w,h))
        val gray=Mat()
        val rgb=Mat()
        val hsv=Mat()
        Imgproc.cvtColor(roi,gray,Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(roi,rgb,Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV)
        Imgproc.GaussianBlur(gray,gray,Size(5.0,5.0),1.1)

        // Calibrado para o tamanho real das bolas no 8 Ball Pool:
        // em geral o raio fica perto de 1/70–1/85 da largura útil da mesa.
        val expectedR=(w/76.0).coerceAtLeast(6.0)
        val minR=max(5,(expectedR*.68).roundToInt())
        val maxR=max(minR+3,(expectedR*1.38).roundToInt())

        val feltHue=dominantFeltHue(hsv)
        val base=sensitivityProvider().coerceIn(10,30)
        val passes=listOf(
            (base+3).coerceAtMost(30),
            base,
            (base-3).coerceAtLeast(11)
        ).distinct()

        val innerLeft=table.left+w*.030f
        val innerRight=table.right-w*.030f
        val innerTop=table.top+h*.055f
        val innerBottom=table.bottom-h*.055f

        val raw=mutableListOf<Ball>()
        for(param2 in passes){
            val circles=Mat()
            Imgproc.HoughCircles(
                gray,circles,
                Imgproc.HOUGH_GRADIENT,
                1.18,
                expectedR*1.65,
                115.0,
                param2.toDouble(),
                minR,maxR
            )

            if(circles.cols()>0){
                for(i in 0 until circles.cols()){
                    val cc=circles.get(0,i)?:continue
                    if(cc.size<3)continue

                    val localX=cc[0].toFloat()
                    val localY=cc[1].toFloat()
                    val center=PointF(x+localX,y+localY)
                    val rad=cc[2].toFloat()

                    // Nada de HUD, trilho, caçapa ou decoração: só o pano útil.
                    if(center.x<innerLeft || center.x>innerRight ||
                       center.y<innerTop || center.y>innerBottom) continue

                    // Uma bola real fica cercada principalmente pelo pano.
                    val ringScore=feltRingFraction(hsv,localX,localY,rad,feltHue)
                    if(ringScore<0.56)continue

                    // O interior da bola precisa diferir do pano ao redor.
                    val contrast=ballVsFeltContrast(hsv,localX,localY,rad,feltHue)
                    if(contrast<0.17)continue

                    val cueScore=whiteness(rgba,center,rad)
                    val candidate=Ball(center,rad,false,cueScore)

                    if(raw.none{
                        Geometry.dist(it.center,candidate.center)<max(it.radius,candidate.radius)*1.20f
                    }){
                        raw.add(candidate)
                    }
                }
            }
            circles.release()

            // Não continue tornando a detecção mais permissiva se já houver
            // uma quantidade plausível de bolas.
            if(raw.size in 2..16)break
        }

        hsv.release()
        rgb.release()
        gray.release()
        roi.release()

        if(raw.isEmpty())return emptyList()

        // As bolas de uma mesma mesa têm praticamente o mesmo raio.
        val radii=raw.map{it.radius}.sorted()
        val medianRadius=radii[radii.size/2]
        val clustered=raw.filter{
            it.radius>=medianRadius*.82f && it.radius<=medianRadius*1.20f
        }

        val geometric=(if(clustered.size>=2)clustered else raw)
            .take(16)

        if(geometric.isEmpty())return emptyList()

        // Só aceitamos uma branca razoavelmente clara. Se não houver,
        // não inventamos uma cue ball a partir de um círculo aleatório.
        val cueIndex=geometric.indices.maxByOrNull{geometric[it].confidence}?:return emptyList()
        val sortedScores=geometric.map{it.confidence}.sortedDescending()
        val cueScore=geometric[cueIndex].confidence
        val second=sortedScores.getOrElse(1){-999f}
        if(cueScore<80f || cueScore-second<8f)return emptyList()

        return geometric.mapIndexed{idx,b->b.copy(cue=idx==cueIndex)}
    }

    private fun feltRingFraction(hsv:Mat,cx:Float,cy:Float,r:Float,feltHue:Double):Double{
        var good=0
        var total=0
        val radii=floatArrayOf(r*1.55f,r*1.85f)
        for(rr in radii){
            for(i in 0 until 24){
                val a=2.0*Math.PI*i/24.0
                val px=(cx+cos(a).toFloat()*rr).roundToInt()
                val py=(cy+sin(a).toFloat()*rr).roundToInt()
                if(px<0||py<0||px>=hsv.cols()||py>=hsv.rows())continue
                val p=hsv.get(py,px)?:continue
                if(p.size<3)continue
                total++
                val hueDist=hueDistance(p[0],feltHue)
                if(hueDist<22.0 && p[1]>28.0 && p[2]>20.0)good++
            }
        }
        return if(total<16)0.0 else good.toDouble()/total.toDouble()
    }

    private fun ballVsFeltContrast(hsv:Mat,cx:Float,cy:Float,r:Float,feltHue:Double):Double{
        var diff=0.0
        var total=0
        val rr=max(2f,r*.58f)
        for(iy in -2..2){
            for(ix in -2..2){
                val px=(cx+ix*rr/2.4f).roundToInt()
                val py=(cy+iy*rr/2.4f).roundToInt()
                if(px<0||py<0||px>=hsv.cols()||py>=hsv.rows())continue
                val p=hsv.get(py,px)?:continue
                if(p.size<3)continue

                val huePart=(hueDistance(p[0],feltHue)/90.0).coerceIn(0.0,1.0)
                val lowSatPart=((70.0-p[1])/70.0).coerceIn(0.0,1.0)
                val brightPart=((p[2]-150.0)/105.0).coerceIn(0.0,1.0)
                diff+=max(huePart,max(lowSatPart,brightPart))
                total++
            }
        }
        return if(total<8)0.0 else diff/total.toDouble()
    }

    private fun hueDistance(a:Double,b:Double):Double{
        val d=abs(a-b)
        return min(d,180.0-d)
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
