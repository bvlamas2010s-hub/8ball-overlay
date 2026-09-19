package com.example.pooltrajectory;

import android.graphics.PointF;
import java.util.ArrayList;
import java.util.List;

/** Temporal gate to stop jitter and hallucinated overlays between scenes. */
public final class ResultStabilizer {
    private VisionResult stable;
    private VisionResult candidate;
    private int candidateFrames;
    private int invalidFrames;
    private int trajectoryRefreshCounter;

    public synchronized VisionResult push(VisionResult now) {
        boolean usable = now != null
                && now.roi != null
                && now.cueBall != null
                && now.confidence >= 0.28f
                && (now.isDrawable() || !now.trajectories.isEmpty());

        if (!usable) {
            invalidFrames++;
            candidate = null;
            candidateFrames = 0;
            if (invalidFrames >= 3) stable = null;
            return invalidFrames >= 3 ? null : stable;
        }

        invalidFrames = 0;

        if (stable == null) {
            if (candidate != null && compatible(candidate, now)) {
                candidateFrames++;
            } else {
                candidate = now;
                candidateFrames = 1;
            }
            if (candidateFrames >= 2) {
                stable = now;
                candidate = null;
                candidateFrames = 0;
                trajectoryRefreshCounter = 0;
            }
            return stable;
        }

        if (!compatible(stable, now)) {
            if (candidate != null && compatible(candidate, now)) {
                candidateFrames++;
            } else {
                candidate = now;
                candidateFrames = 1;
            }
            if (candidateFrames >= 3) {
                stable = now;
                candidate = null;
                candidateFrames = 0;
                trajectoryRefreshCounter = 0;
            }
            return stable;
        }

        List<VisionResult.Trajectory> oldTrajectories = new ArrayList<>(stable.trajectories);
        stable = smooth(stable, now, .38f);

        // Trajectory prediction is more sensitive than cue tracking. Refresh it only after
        // several compatible frames so one noisy frame cannot redraw the whole map.
        trajectoryRefreshCounter++;
        if (trajectoryRefreshCounter < 4 && !oldTrajectories.isEmpty()) {
            stable.trajectories.clear();
            stable.trajectories.addAll(oldTrajectories);
        } else {
            trajectoryRefreshCounter = 0;
        }

        candidate = null;
        candidateFrames = 0;
        return stable;
    }

    private boolean compatible(VisionResult a, VisionResult b) {
        if (a.cueBall == null || b.cueBall == null) return false;

        float d = (float)Math.hypot(
                a.cueBall.x - b.cueBall.x,
                a.cueBall.y - b.cueBall.y
        );
        float max = Math.max(16f, Math.max(a.cueRadius, b.cueRadius) * 2.8f);
        if (d >= max) return false;

        if (Math.abs(a.balls.size() - b.balls.size()) > 2) return false;

        boolean aAngle = Float.isFinite(a.aimAngleRad);
        boolean bAngle = Float.isFinite(b.aimAngleRad);
        if (aAngle && bAngle) {
            float ad = angleDiff(a.aimAngleRad, b.aimAngleRad);
            if (ad >= (float)Math.toRadians(16)) return false;
        }

        return true;
    }

    private VisionResult smooth(VisionResult a, VisionResult b, float k) {
        VisionResult o = b;
        o.cueBall = lerp(a.cueBall, b.cueBall, k);
        o.primaryEnd = lerp(a.primaryEnd, b.primaryEnd, k);
        o.targetEnd = lerp(a.targetEnd, b.targetEnd, k);
        o.cueDeflectionEnd = lerp(a.cueDeflectionEnd, b.cueDeflectionEnd, k);
        o.collisionBall = lerp(a.collisionBall, b.collisionBall, k);
        o.cueRadius = a.cueRadius + (b.cueRadius - a.cueRadius) * k;

        if (Float.isFinite(a.aimAngleRad) && Float.isFinite(b.aimAngleRad)) {
            o.aimAngleRad = lerpAngle(a.aimAngleRad, b.aimAngleRad, k);
        }
        return o;
    }

    private static PointF lerp(PointF a, PointF b, float k) {
        if (a == null) return b;
        if (b == null) return a;
        return new PointF(
                a.x + (b.x - a.x) * k,
                a.y + (b.y - a.y) * k
        );
    }

    private static float angleDiff(float a, float b) {
        float d = Math.abs(a - b) % (float)(Math.PI * 2);
        return d > (float)Math.PI ? (float)(Math.PI * 2) - d : d;
    }

    private static float lerpAngle(float a, float b, float k) {
        float d = b - a;
        while (d > Math.PI) d -= Math.PI * 2;
        while (d < -Math.PI) d += Math.PI * 2;
        return a + d * k;
    }
}
