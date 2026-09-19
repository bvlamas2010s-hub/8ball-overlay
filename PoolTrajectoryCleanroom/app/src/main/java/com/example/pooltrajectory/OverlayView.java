package com.example.pooltrajectory;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;

public final class OverlayView extends View {
    private final Paint main = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondary = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debug = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile VisionResult result;

    public OverlayView(Context c) {
        super(c);
        setBackgroundColor(Color.TRANSPARENT);
        main.setColor(Color.WHITE); main.setStrokeWidth(dp(2.2f)); main.setStyle(Paint.Style.STROKE);
        secondary.setColor(Color.YELLOW); secondary.setStrokeWidth(dp(1.8f)); secondary.setStyle(Paint.Style.STROKE);
        cue.setColor(Color.CYAN); cue.setStrokeWidth(dp(1.7f)); cue.setStyle(Paint.Style.STROKE);
        debug.setColor(Color.argb(170,255,255,255)); debug.setTextSize(dp(12)); debug.setStyle(Paint.Style.FILL);
    }

    public void setResult(VisionResult r) { result=r; postInvalidateOnAnimation(); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        VisionResult r=result;
        if(r==null || !r.isDrawable()) return;
        if(r.cueBall!=null && r.primaryEnd!=null){
            c.drawCircle(r.cueBall.x,r.cueBall.y,Math.max(4,r.cueRadius),cue);
            c.drawLine(r.cueBall.x,r.cueBall.y,r.primaryEnd.x,r.primaryEnd.y,main);
        }
        if(r.collisionBall!=null)c.drawCircle(r.collisionBall.x,r.collisionBall.y,Math.max(5,r.cueRadius*.95f),secondary);
        if(r.collisionBall!=null&&r.targetEnd!=null)c.drawLine(r.collisionBall.x,r.collisionBall.y,r.targetEnd.x,r.targetEnd.y,secondary);
        if(r.primaryEnd!=null&&r.cueDeflectionEnd!=null)c.drawLine(r.primaryEnd.x,r.primaryEnd.y,r.cueDeflectionEnd.x,r.cueDeflectionEnd.y,cue);
        if(r.roi!=null){
            Paint p=new Paint(debug);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(Color.argb(90,255,255,255));c.drawRect(r.roi,p);
            c.drawText(r.debug==null?"":r.debug,r.roi.left+dp(6),Math.max(dp(18),r.roi.top-dp(7)),debug);
        }
    }
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}
