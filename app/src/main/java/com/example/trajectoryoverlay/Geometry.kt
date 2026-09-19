package com.example.trajectoryoverlay

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.*

object Geometry {
    fun add(a: PointF, b: PointF) = PointF(a.x + b.x, a.y + b.y)
    fun sub(a: PointF, b: PointF) = PointF(a.x - b.x, a.y - b.y)
    fun mul(a: PointF, s: Float) = PointF(a.x * s, a.y * s)
    fun len(a: PointF) = hypot(a.x, a.y)
    fun norm(a: PointF): PointF { val l = len(a).coerceAtLeast(1e-6f); return PointF(a.x/l, a.y/l) }
    fun dot(a: PointF, b: PointF) = a.x*b.x + a.y*b.y
    fun dist(a: PointF, b: PointF) = len(sub(a,b))

    fun distancePointSegment(p: PointF, a: PointF, b: PointF): Float {
        val ab = sub(b,a); val ap = sub(p,a)
        val den = dot(ab,ab).coerceAtLeast(1e-6f)
        val t = (dot(ap,ab)/den).coerceIn(0f,1f)
        return dist(p, add(a,mul(ab,t)))
    }

    fun lineToRectEdge(start: PointF, dir: PointF, rect: RectF): PointF? {
        val d = norm(dir)
        var bestT = Float.POSITIVE_INFINITY
        var best: PointF? = null
        fun tryT(t: Float) {
            if (t <= 0f || !t.isFinite() || t >= bestT) return
            val p = add(start, mul(d,t))
            if (p.x >= rect.left-1 && p.x <= rect.right+1 && p.y >= rect.top-1 && p.y <= rect.bottom+1) {
                bestT = t; best = p
            }
        }
        if (abs(d.x) > 1e-5f) { tryT((rect.left-start.x)/d.x); tryT((rect.right-start.x)/d.x) }
        if (abs(d.y) > 1e-5f) { tryT((rect.top-start.y)/d.y); tryT((rect.bottom-start.y)/d.y) }
        return best
    }
}
