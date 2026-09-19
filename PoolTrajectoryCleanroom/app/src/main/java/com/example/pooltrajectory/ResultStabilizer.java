package com.example.pooltrajectory;

import android.graphics.PointF;

/** Temporal gate to stop jitter and hallucinated overlays between scenes. */
public final class ResultStabilizer {
    private VisionResult stable;
    private VisionResult candidate;
    private int candidateFrames;
    private int invalidFrames;

    public synchronized VisionResult push(VisionResult now) {
        if (now == null || !now.isDrawable() || now.confidence < 0.28f) {
            invalidFrames++;
            candidate = null;
            candidateFrames = 0;
            if (invalidFrames >= 3) stable = null;
            return invalidFrames >= 3 ? null : stable;
        }
        invalidFrames = 0;
        if (stable == null) {
            if (candidate != null && compatible(candidate, now)) candidateFrames++; else {candidate=now;candidateFrames=1;}
            if (candidateFrames >= 2) {stable=now;candidate=null;candidateFrames=0;}
            return stable;
        }

        if (!compatible(stable, now)) {
            if (candidate != null && compatible(candidate, now)) candidateFrames++; else {candidate=now;candidateFrames=1;}
            if (candidateFrames >= 3) {stable=now;candidate=null;candidateFrames=0;}
            return stable;
        }

        stable = smooth(stable, now, .38f);
        candidate = null; candidateFrames = 0;
        return stable;
    }

    private boolean compatible(VisionResult a, VisionResult b) {
        if (a.cueBall == null || b.cueBall == null) return false;
        float d=(float)Math.hypot(a.cueBall.x-b.cueBall.x,a.cueBall.y-b.cueBall.y);
        float max=Math.max(16f,Math.max(a.cueRadius,b.cueRadius)*2.8f);
        float ad=angleDiff(a.aimAngleRad,b.aimAngleRad);
        return d<max && ad<(float)Math.toRadians(16);
    }

    private VisionResult smooth(VisionResult a, VisionResult b, float k) {
        VisionResult o=b;
        o.cueBall=lerp(a.cueBall,b.cueBall,k);
        o.primaryEnd=lerp(a.primaryEnd,b.primaryEnd,k);
        o.targetEnd=lerp(a.targetEnd,b.targetEnd,k);
        o.cueDeflectionEnd=lerp(a.cueDeflectionEnd,b.cueDeflectionEnd,k);
        o.collisionBall=lerp(a.collisionBall,b.collisionBall,k);
        o.cueRadius=a.cueRadius+(b.cueRadius-a.cueRadius)*k;
        o.aimAngleRad=lerpAngle(a.aimAngleRad,b.aimAngleRad,k);
        return o;
    }

    private static PointF lerp(PointF a,PointF b,float k){if(a==null)return b;if(b==null)return a;return new PointF(a.x+(b.x-a.x)*k,a.y+(b.y-a.y)*k);}
    private static float angleDiff(float a,float b){float d=Math.abs(a-b)%(float)(Math.PI*2);return d>(float)Math.PI?(float)(Math.PI*2)-d:d;}
    private static float lerpAngle(float a,float b,float k){
        float d=b-a;while(d>Math.PI)d-=Math.PI*2;while(d<-Math.PI)d+=Math.PI*2;return a+d*k;
    }
}
