package com.example.pooltrajectory;

import android.graphics.PointF;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;

public final class VisionResult {
    public enum State { NO_TABLE, NO_CUE_BALL, NO_GUIDE, VALID_SHOT }

    public State state = State.NO_TABLE;
    public float confidence = 0f;
    public RectF roi;
    public PointF cueBall;
    public float cueRadius;
    public float aimAngleRad = Float.NaN;
    public PointF primaryEnd;
    public PointF collisionBall;
    public PointF targetEnd;
    public PointF cueDeflectionEnd;
    public String debug = "";
    public final List<Ball> balls = new ArrayList<>();
    public final List<Trajectory> trajectories = new ArrayList<>();

    public boolean isDrawable() {
        return state == State.VALID_SHOT && roi != null && cueBall != null && primaryEnd != null;
    }

    public VisionResult scaled(float scaleX, float scaleY) {
        VisionResult out = new VisionResult();
        out.state = state;
        out.confidence = confidence;
        out.roi = scale(roi, scaleX, scaleY);
        out.cueBall = scale(cueBall, scaleX, scaleY);
        out.cueRadius = cueRadius * (scaleX + scaleY) * 0.5f;
        out.aimAngleRad = aimAngleRad;
        out.primaryEnd = scale(primaryEnd, scaleX, scaleY);
        out.collisionBall = scale(collisionBall, scaleX, scaleY);
        out.targetEnd = scale(targetEnd, scaleX, scaleY);
        out.cueDeflectionEnd = scale(cueDeflectionEnd, scaleX, scaleY);
        out.debug = debug;

        for (Ball b : balls) {
            out.balls.add(new Ball(
                    b.x * scaleX,
                    b.y * scaleY,
                    b.r * (scaleX + scaleY) * 0.5f,
                    b.score,
                    b.white
            ));
        }

        for (Trajectory trajectory : trajectories) {
            Trajectory copy = new Trajectory(trajectory.colorIndex, trajectory.cueBall);
            for (PointF p : trajectory.points) copy.points.add(scale(p, scaleX, scaleY));
            out.trajectories.add(copy);
        }
        return out;
    }

    private static PointF scale(PointF p, float sx, float sy) {
        return p == null ? null : new PointF(p.x * sx, p.y * sy);
    }

    private static RectF scale(RectF r, float sx, float sy) {
        return r == null ? null : new RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy);
    }

    public static final class Ball {
        public final float x, y, r, score;
        public final boolean white;

        public Ball(float x, float y, float r, float score, boolean white) {
            this.x = x;
            this.y = y;
            this.r = r;
            this.score = score;
            this.white = white;
        }
    }

    /** Predicted path of one ball after the current shot. */
    public static final class Trajectory {
        public final List<PointF> points = new ArrayList<>();
        public final int colorIndex;
        public final boolean cueBall;

        public Trajectory(int colorIndex, boolean cueBall) {
            this.colorIndex = colorIndex;
            this.cueBall = cueBall;
        }
    }
}
