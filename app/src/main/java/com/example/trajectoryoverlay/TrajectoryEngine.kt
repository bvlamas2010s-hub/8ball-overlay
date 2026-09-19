package com.example.trajectoryoverlay

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.*

object TrajectoryEngine {
    private enum class Rail { TOP, BOTTOM, LEFT, RIGHT }

    fun selfTest(): String? {
        return try {
            val table=RectF(50f,50f,1050f,550f)
            val cue=Ball(PointF(250f,300f),18f,true)
            val obj=Ball(PointF(650f,300f),18f,false)
            val result=calculate(table,listOf(cue,obj),pockets(table),direct=true,banks=true,secondary=true)
            if(result.isEmpty()) "motor sem trajetórias no teste sintético" else null
        } catch(t:Throwable) {
            "motor: ${t.javaClass.simpleName}: ${t.message ?: "erro"}"
        }
    }

    fun calculate(table: RectF, balls: List<Ball>, pockets: List<PointF>, direct: Boolean, banks: Boolean, secondary: Boolean): List<Trajectory> {
        val cue = balls.firstOrNull { it.cue } ?: return emptyList()
        val objects = balls.filterNot { it.cue }
        val out = mutableListOf<Trajectory>()
        val r = balls.map { it.radius }.average().toFloat().coerceAtLeast(6f)
        val playable = RectF(table.left+r*1.2f, table.top+r*1.2f, table.right-r*1.2f, table.bottom-r*1.2f)

        for (obj in objects) for (pocket in pockets) {
            if (direct) direct(cue,obj,pocket,balls,r,playable,secondary)?.let(out::add)
            if (banks) {
                for (rail in Rail.entries) {
                    objectBank(cue,obj,pocket,rail,balls,r,playable,secondary)?.let(out::add)
                    cueBank(cue,obj,pocket,rail,balls,r,playable,secondary)?.let(out::add)
                }
            }
        }
        return out.sortedBy { it.score }.distinctBy {
            val p=it.objectPath.last(); "${it.kind}:${(it.ghost.x/5).toInt()}:${(it.ghost.y/5).toInt()}:${(p.x/5).toInt()}:${(p.y/5).toInt()}"
        }.take(80)
    }

    private fun direct(cue: Ball, obj: Ball, pocket: PointF, balls: List<Ball>, r: Float, table: RectF, secondary: Boolean): Trajectory? {
        val outDir = Geometry.norm(Geometry.sub(pocket,obj.center))
        val ghost = Geometry.sub(obj.center,Geometry.mul(outDir,2*r))
        if (!table.contains(ghost.x,ghost.y)) return null
        if (blocked(cue.center,ghost,balls,setOf(cue,obj),r)) return null
        if (blocked(obj.center,pocket,balls,setOf(cue,obj),r)) return null
        val incoming=Geometry.norm(Geometry.sub(ghost,cue.center))
        val angle=angle(incoming,outDir)
        if (angle>88f) return null
        val sec = if(secondary) cueAfter(cue,obj,ghost,incoming,outDir,balls,r,table) else emptyList()
        val score=Geometry.dist(cue.center,ghost)+Geometry.dist(obj.center,pocket)+angle*5f
        return Trajectory(PathKind.DIRECT,listOf(cue.center,ghost),listOf(obj.center,pocket),ghost,angle,score,sec)
    }

    private fun objectBank(cue: Ball,obj: Ball,pocket: PointF,rail: Rail,balls: List<Ball>,r: Float,table: RectF,secondary:Boolean): Trajectory? {
        val mirror=reflect(pocket,rail,table)
        val hit=intersection(obj.center,mirror,rail,table)?:return null
        if (tooCloseToPocket(hit,table,r)) return null
        val firstDir=Geometry.norm(Geometry.sub(hit,obj.center))
        val ghost=Geometry.sub(obj.center,Geometry.mul(firstDir,2*r))
        if(!table.contains(ghost.x,ghost.y))return null
        if(blocked(cue.center,ghost,balls,setOf(cue,obj),r))return null
        if(blocked(obj.center,hit,balls,setOf(cue,obj),r))return null
        if(blocked(hit,pocket,balls,setOf(cue,obj),r))return null
        val incoming=Geometry.norm(Geometry.sub(ghost,cue.center))
        val angle=angle(incoming,firstDir); if(angle>88f)return null
        val sec=if(secondary) cueAfter(cue,obj,ghost,incoming,firstDir,balls,r,table) else emptyList()
        val score=Geometry.dist(cue.center,ghost)+Geometry.dist(obj.center,hit)+Geometry.dist(hit,pocket)+angle*5f+180f
        return Trajectory(PathKind.OBJECT_BANK,listOf(cue.center,ghost),listOf(obj.center,hit,pocket),ghost,angle,score,sec)
    }

    private fun cueBank(cue: Ball,obj: Ball,pocket: PointF,rail: Rail,balls: List<Ball>,r: Float,table: RectF,secondary:Boolean): Trajectory? {
        val objDir=Geometry.norm(Geometry.sub(pocket,obj.center))
        val ghost=Geometry.sub(obj.center,Geometry.mul(objDir,2*r))
        if(!table.contains(ghost.x,ghost.y))return null
        if(blocked(obj.center,pocket,balls,setOf(cue,obj),r))return null
        val mirroredGhost=reflect(ghost,rail,table)
        val hit=intersection(cue.center,mirroredGhost,rail,table)?:return null
        if(tooCloseToPocket(hit,table,r))return null
        if(blocked(cue.center,hit,balls,setOf(cue,obj),r))return null
        if(blocked(hit,ghost,balls,setOf(cue,obj),r))return null
        val incoming=Geometry.norm(Geometry.sub(ghost,hit))
        val angle=angle(incoming,objDir);if(angle>88f)return null
        val sec=if(secondary) cueAfter(cue,obj,ghost,incoming,objDir,balls,r,table) else emptyList()
        val score=Geometry.dist(cue.center,hit)+Geometry.dist(hit,ghost)+Geometry.dist(obj.center,pocket)+angle*5f+220f
        return Trajectory(PathKind.CUE_BANK,listOf(cue.center,hit,ghost),listOf(obj.center,pocket),ghost,angle,score,sec)
    }

    private fun cueAfter(cue:Ball,obj:Ball,ghost:PointF,incoming:PointF,targetDir:PointF,balls:List<Ball>,r:Float,table:RectF):List<PointF>{
        val normal=Geometry.norm(Geometry.sub(obj.center,ghost))
        val transferred=Geometry.mul(normal,Geometry.dot(incoming,normal))
        val residual=Geometry.sub(incoming,transferred)
        if(Geometry.len(residual)<0.08f)return emptyList()
        val dir=Geometry.norm(residual)
        val start=ghost
        var bestPoint=Geometry.lineToRectEdge(start,dir,table)?:return emptyList()
        var bestDist=Geometry.dist(start,bestPoint)
        for(b in balls){
            if(b===cue||b===obj)continue
            val rel=Geometry.sub(b.center,start)
            val t=Geometry.dot(rel,dir)
            if(t<=0f||t>=bestDist)continue
            val closest=Geometry.add(start,Geometry.mul(dir,t))
            if(Geometry.dist(closest,b.center)<=2*r){bestDist=t;bestPoint=closest}
        }
        return listOf(start,bestPoint)
    }

    private fun blocked(a:PointF,b:PointF,balls:List<Ball>,ignore:Set<Ball>,r:Float):Boolean = balls.any { it !in ignore && Geometry.distancePointSegment(it.center,a,b)<(it.radius+r)*0.92f }
    private fun angle(a:PointF,b:PointF)=Math.toDegrees(acos(Geometry.dot(Geometry.norm(a),Geometry.norm(b)).coerceIn(-1f,1f)).toDouble()).toFloat()

    private fun reflect(p:PointF,rail:Rail,t:RectF)=when(rail){
        Rail.TOP->PointF(p.x,2*t.top-p.y);Rail.BOTTOM->PointF(p.x,2*t.bottom-p.y);Rail.LEFT->PointF(2*t.left-p.x,p.y);Rail.RIGHT->PointF(2*t.right-p.x,p.y)
    }
    private fun intersection(a:PointF,b:PointF,rail:Rail,t:RectF):PointF?{
        val d=Geometry.sub(b,a)
        return when(rail){
            Rail.TOP,Rail.BOTTOM->{val y=if(rail==Rail.TOP)t.top else t.bottom;if(abs(d.y)<1e-5)return null;val q=(y-a.y)/d.y;if(q<=0||q>=1)return null;val x=a.x+d.x*q;if(x<t.left||x>t.right)null else PointF(x,y)}
            Rail.LEFT,Rail.RIGHT->{val x=if(rail==Rail.LEFT)t.left else t.right;if(abs(d.x)<1e-5)return null;val q=(x-a.x)/d.x;if(q<=0||q>=1)return null;val y=a.y+d.y*q;if(y<t.top||y>t.bottom)null else PointF(x,y)}
        }
    }
    private fun tooCloseToPocket(p:PointF,t:RectF,r:Float):Boolean{
        val ps=pockets(t);return ps.any{Geometry.dist(it,p)<r*3f}
    }
    fun pockets(t:RectF)=listOf(PointF(t.left,t.top),PointF(t.centerX(),t.top),PointF(t.right,t.top),PointF(t.left,t.bottom),PointF(t.centerX(),t.bottom),PointF(t.right,t.bottom))
}
