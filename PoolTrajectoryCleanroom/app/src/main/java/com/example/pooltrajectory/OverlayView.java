package com.example.pooltrajectory;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public final class OverlayView extends View {
    private final Paint main = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondary = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debug = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile VisionResult result;

    public OverlayView(Context c) {
        super(c);
        setBackgroundColor(Color.TRANSPARENT);
        main.setColor(Color.WHITE); main.setStrokeWidth(dp(2.2f)); main.setStyle(Paint.Style.STROKE);
        secondary.setColor(Color.YELLOW); secondary.setStrokeWidth(dp(1.8f)); secondary.setStyle(Paint.Style.STROKE);
        cue.setColor(Color.CYAN); cue.setStrokeWidth(dp(1.7f)); cue.setStyle(Paint.Style.STROKE);
        debug.setColor(Color.WHITE); debug.setTextSize(dp(11)); debug.setStyle(Paint.Style.FILL);
        panel.setColor(Color.argb(185, 0, 0, 0)); panel.setStyle(Paint.Style.FILL);
        roiPaint.setColor(Color.argb(150, 0, 255, 255)); roiPaint.setStrokeWidth(dp(1)); roiPaint.setStyle(Paint.Style.STROKE);
    }

    public void setResult(VisionResult r) {
        result = r;
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        VisionResult r = result;

        int[] loc = new int[2];
        getLocationOnScreen(loc);

        String state = r == null ? "CAPTURE ACTIVE • waiting for frame" :
                "POOL LAB • " + r.state.name() + " • " + (r.debug == null ? "" : r.debug)
                        + " • win=" + loc[0] + "," + loc[1];

        float panelH = dp(28);
        c.drawRoundRect(new RectF(dp(8), dp(8), Math.min(getWidth()-dp(8), dp(690)), dp(8)+panelH),
                dp(6), dp(6), panel);
        c.drawText(state, dp(15), dp(27), debug);

        if (r == null) return;

        // Vision coordinates are absolute MediaProjection/screen coordinates.
        // Android overlay windows may start after a display-cutout safe inset in
        // landscape. Convert absolute screen coordinates to this window's canvas.
        c.save();
        c.translate(-loc[0], -loc[1]);

        if (r.roi != null) {
            c.drawRect(r.roi, roiPaint);
        }

        if (r.cueBall != null) {
            c.drawCircle(r.cueBall.x, r.cueBall.y, Math.max(dp(5), r.cueRadius), cue);
        }

        if (r.isDrawable()) {
            if (r.cueBall != null && r.primaryEnd != null) {
                c.drawLine(r.cueBall.x, r.cueBall.y, r.primaryEnd.x, r.primaryEnd.y, main);
            }
            if (r.collisionBall != null) {
                c.drawCircle(r.collisionBall.x, r.collisionBall.y, Math.max(dp(5), r.cueRadius*.95f), secondary);
            }
            if (r.collisionBall != null && r.targetEnd != null) {
                c.drawLine(r.collisionBall.x, r.collisionBall.y, r.targetEnd.x, r.targetEnd.y, secondary);
            }
            if (r.primaryEnd != null && r.cueDeflectionEnd != null) {
                c.drawLine(r.primaryEnd.x, r.primaryEnd.y, r.cueDeflectionEnd.x, r.cueDeflectionEnd.y, cue);
            }
        }

        c.restore();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
