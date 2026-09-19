package com.example.pooltrajectory;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Clean-room image detector. No code or native libraries from Aim Pool are reused.
 * The detector intentionally fails closed: if scene confidence is weak, it returns
 * NO_TABLE/NO_CUE_BALL/NO_GUIDE instead of drawing guessed geometry.
 */
public final class VisionEngine {
    private static final int MAX_ANALYSIS_WIDTH = 560;

    public VisionResult analyze(Bitmap original) {
        if (original == null || original.getWidth() < 200 || original.getHeight() < 200) {
            return invalid(VisionResult.State.NO_TABLE, "frame too small");
        }

        float down = Math.min(1f, MAX_ANALYSIS_WIDTH / (float) original.getWidth());
        int w = Math.max(1, Math.round(original.getWidth() * down));
        int h = Math.max(1, Math.round(original.getHeight() * down));
        Bitmap bmp = down < 0.999f ? Bitmap.createScaledBitmap(original, w, h, true) : original;

        PixelFrame f = new PixelFrame(bmp);
        Table table = findTable(f);
        if (table == null) table = fallbackEightBallTable(f);
        if (table == null) {
            if (bmp != original) bmp.recycle();
            return invalid(VisionResult.State.NO_TABLE,
                    "capture=" + f.w + "x" + f.h + " • ROI not found");
        }

        List<Cue> cues = findCueBallCandidates(f, table);
        if (cues.isEmpty()) {
            VisionResult r = invalid(VisionResult.State.NO_CUE_BALL, "table ok; cue candidates=0");
            r.roi = table.roi;
            r.confidence = table.confidence * .7f;
            if (bmp != original) bmp.recycle();
            return rescale(r, down);
        }

        CueAim pair = null;
        for (Cue cue : cues) {
            Aim aim = findAimGuide(f, table, cue);
            if (aim == null) continue;
            float pairScore = cue.score * aim.score;
            if (pair == null || pairScore > pair.score) pair = new CueAim(cue, aim, pairScore);
        }

        if (pair == null) {
            Cue bestCue = cues.get(0);
            VisionResult r = invalid(VisionResult.State.NO_GUIDE,
                    "table+cue ok; no thin aiming guide • cues=" + cues.size());
            r.roi = table.roi;
            r.cueBall = new PointF(bestCue.x, bestCue.y);
            r.cueRadius = bestCue.r;
            r.confidence = Math.min(table.confidence, bestCue.score);
            if (bmp != original) bmp.recycle();
            return rescale(r, down);
        }

        Cue cue = pair.cue;
        Aim aim = pair.aim;
        List<VisionResult.Ball> balls = findObjectBalls(f, table, cue);
        VisionResult out = buildGeometry(table, cue, balls, aim);
        out.confidence = Math.min(table.confidence, Math.min(cue.score, aim.score));
        out.debug = "table=" + fmt(table.confidence) + " cue=" + fmt(cue.score)
                + " guide=" + fmt(aim.score) + " balls=" + balls.size();
        if (bmp != original) bmp.recycle();
        return rescale(out, down);
    }

    private VisionResult rescale(VisionResult r, float down) {
        if (down >= 0.999f) return r;
        float s = 1f / down;
        return r.scaled(s, s);
    }

    private static VisionResult invalid(VisionResult.State s, String why) {
        VisionResult r = new VisionResult();
        r.state = s;
        r.debug = why;
        return r;
    }

    private Table findTable(PixelFrame f) {
        int[] hist = new int[36];
        int x0 = (int)(f.w * .06f), x1 = (int)(f.w * .94f);
        int y0 = (int)(f.h * .12f), y1 = (int)(f.h * .90f);

        for (int y=y0; y<y1; y+=4) {
            for (int x=x0; x<x1; x+=4) {
                HSV c=f.hsv(x,y);
                if(c.s>.28f && c.v>.16f && c.v<.96f)
                    hist[Math.min(35,(int)(c.h/10f))]++;
            }
        }
        int bestBin=0;
        for(int i=1;i<hist.length;i++) if(hist[i]>hist[bestBin]) bestBin=i;
        if(hist[bestBin]<80) return null;
        float hue=bestBin*10f+5f;

        // Use row coverage instead of uninterrupted runs. Balls, guide-lines and
        // specular highlights can split a valid table row into several pieces.
        int bestTop=-1,bestBottom=-1,bestRows=0;
        int curTop=-1,curBottom=-1,curRows=0,prevY=-999;
        for(int y=y0;y<y1;y+=2){
            int total=0,felt=0;
            for(int x=x0;x<x1;x+=2){ total++; if(isFelt(f.hsv(x,y),hue)) felt++; }
            float frac=felt/(float)Math.max(1,total);
            if(frac>.45f){
                if(curTop<0 || y-prevY>4){curTop=y;curRows=0;}
                curBottom=y;curRows++;prevY=y;
                if(curRows>bestRows){bestRows=curRows;bestTop=curTop;bestBottom=curBottom;}
            }
        }
        if(bestRows<20 || bestTop<0) return null;

        int bestLeft=-1,bestRight=-1,bestCols=0;
        int curLeft=-1,curRight=-1,curCols=0,prevX=-999;
        for(int x=x0;x<x1;x+=2){
            int total=0,felt=0;
            for(int y=bestTop;y<=bestBottom;y+=2){total++;if(isFelt(f.hsv(x,y),hue))felt++;}
            float frac=felt/(float)Math.max(1,total);
            if(frac>.40f){
                if(curLeft<0 || x-prevX>4){curLeft=x;curCols=0;}
                curRight=x;curCols++;prevX=x;
                if(curCols>bestCols){bestCols=curCols;bestLeft=curLeft;bestRight=curRight;}
            }
        }
        if(bestCols<30 || bestLeft<0) return null;

        RectF roi=new RectF(bestLeft,bestTop,bestRight,bestBottom);
        float aspect=roi.width()/Math.max(1f,roi.height());
        if(roi.width()<f.w*.42f || roi.height()<f.h*.22f || aspect<1.35f || aspect>3.40f) return null;

        int total=0,felt=0;float sat=0,val=0;
        for(int y=bestTop;y<=bestBottom;y+=6){
            for(int x=bestLeft;x<=bestRight;x+=6){
                HSV c=f.hsv(x,y);total++;
                if(isFelt(c,hue)){felt++;sat+=c.s;val+=c.v;}
            }
        }
        float coverage=felt/(float)Math.max(1,total);
        if(coverage<.44f) return null;
        float conf=clamp01((coverage-.40f)/.35f);
        float avgS=felt==0?.5f:sat/felt;
        float avgV=felt==0?.5f:val/felt;
        return new Table(roi,hue,avgS,avgV,conf);
    }

    /**
     * Fast fallback calibrated from the user's 8 Ball Pool landscape screenshots.
     * It is still validated by cloth color, so unrelated screens are rejected.
     */
    private Table fallbackEightBallTable(PixelFrame f) {
        float aspect=f.w/(float)Math.max(1,f.h);
        if(aspect<1.90f || aspect>2.40f) return null;

        RectF roi=new RectF(
                f.w*.176f,
                f.h*.210f,
                f.w*.824f,
                f.h*.900f
        );

        int[] hist=new int[36];
        int x0=(int)(f.w*.22f),x1=(int)(f.w*.78f);
        int y0=(int)(f.h*.30f),y1=(int)(f.h*.82f);
        for(int y=y0;y<y1;y+=3){
            for(int x=x0;x<x1;x+=3){
                HSV c=f.hsv(x,y);
                if(c.s>.25f&&c.v>.14f&&c.v<.98f)
                    hist[Math.min(35,(int)(c.h/10f))]++;
            }
        }
        int best=0;
        for(int i=1;i<hist.length;i++)if(hist[i]>hist[best])best=i;
        if(hist[best]<50)return null;
        float hue=best*10f+5f;

        int total=0,felt=0;float sat=0,val=0;
        for(int y=(int)roi.top;y<(int)roi.bottom;y+=5){
            for(int x=(int)roi.left;x<(int)roi.right;x+=5){
                total++;
                HSV c=f.hsv(x,y);
                if(isFelt(c,hue)){felt++;sat+=c.s;val+=c.v;}
            }
        }
        float coverage=felt/(float)Math.max(1,total);
        if(coverage<.52f)return null;
        float avgS=felt==0?.5f:sat/felt;
        float avgV=felt==0?.5f:val/felt;
        return new Table(roi,hue,avgS,avgV,Math.min(.92f,.55f+coverage*.38f));
    }

    private List<Cue> findCueBallCandidates(PixelFrame f, Table t) {
        int left=(int)t.roi.left,right=(int)t.roi.right;
        int top=(int)t.roi.top,bottom=(int)t.roi.bottom;
        int rw=Math.max(1,right-left);
        int baseR=Math.max(4,Math.round(rw*.015f));
        List<Cue> all=new ArrayList<>();

        for(int dr=-1;dr<=1;dr++){
            int r=Math.max(4,baseR+dr);
            for(int y=top+r*2;y<=bottom-r*2;y+=2){
                for(int x=left+r*2;x<=right-r*2;x+=2){
                    HSV center=f.hsv(x,y);
                    if(center.v<.45f || center.s>.50f) continue;

                    float sumS=0f;int n=0,pale=0,saturated=0;
                    HSV cc=f.hsv(x,y);sumS+=cc.s;n++;
                    if(cc.v>.52f&&cc.s<.40f)pale++;
                    if(cc.s>.45f)saturated++;

                    float[] radii={r*.45f,r*.80f};
                    for(float rr:radii){
                        for(int i=0;i<20;i++){
                            double a=i*Math.PI*2/20.0;
                            int xx=Math.round(x+(float)Math.cos(a)*rr);
                            int yy=Math.round(y+(float)Math.sin(a)*rr);
                            HSV c=f.hsv(xx,yy);sumS+=c.s;n++;
                            if(c.v>.52f&&c.s<.40f)pale++;
                            if(c.s>.45f)saturated++;
                        }
                    }
                    float paleFrac=pale/(float)n;
                    float satFrac=saturated/(float)n;
                    float avgInsideS=sumS/n;
                    if(paleFrac<.45f || satFrac>.22f || avgInsideS>.18f) continue;

                    float outerS=0f;int ring=0,felt=0;
                    for(int i=0;i<36;i++){
                        double a=i*Math.PI*2/36.0;
                        float rr=r*1.55f;
                        int xx=Math.round(x+(float)Math.cos(a)*rr);
                        int yy=Math.round(y+(float)Math.sin(a)*rr);
                        if(!t.roi.contains(xx,yy)) continue;
                        HSV c=f.hsv(xx,yy);outerS+=c.s;ring++;
                        if(isFelt(c,t.hue))felt++;
                    }
                    if(ring<24) continue;
                    outerS/=ring;
                    float feltFrac=felt/(float)ring;
                    float edgeSat=outerS-avgInsideS;
                    if(feltFrac<.50f || edgeSat<.12f) continue;

                    float score=paleFrac*.35f+feltFrac*.15f
                            +Math.min(.5f,edgeSat)*.20f
                            +(1f-avgInsideS)*.20f-satFrac*.80f;
                    if(score>.42f) all.add(new Cue(x,y,r,clamp01(score)));
                }
            }
        }

        all.sort((a,b)->Float.compare(b.score,a.score));
        List<Cue> unique=new ArrayList<>();
        for(Cue c:all){
            boolean near=false;
            for(Cue u:unique){
                if(dist(c.x,c.y,u.x,u.y)<baseR*1.25f){near=true;break;}
            }
            if(!near) unique.add(c);
            if(unique.size()>=12) break;
        }
        return unique;
    }

    private List<VisionResult.Ball> findObjectBalls(PixelFrame f, Table t, Cue cue) {
        int left = Math.max(0, (int)t.roi.left), right = Math.min(f.w-1, (int)t.roi.right);
        int top = Math.max(0, (int)t.roi.top), bottom = Math.min(f.h-1, (int)t.roi.bottom);
        int rw = Math.max(1, right-left);
        int minD = Math.max(6, Math.round(rw * .018f));
        int maxD = Math.max(minD+2, Math.round(rw * .075f));
        int ww = right-left+1, hh = bottom-top+1;
        boolean[] mask = new boolean[ww*hh];

        for (int yy=0; yy<hh; yy++) {
            int y=top+yy;
            for (int xx=0; xx<ww; xx++) {
                int x=left+xx;
                if (dist(x,y,cue.x,cue.y) < cue.r*1.7f) continue;
                HSV c=f.hsv(x,y);
                float hd=hueDiff(c.h,t.hue);
                boolean different = hd > 22f || Math.abs(c.s-t.sat)>.25f || Math.abs(c.v-t.val)>.22f;
                boolean strong = c.v < .16f || c.v > .72f || c.s > Math.min(1f,t.sat+.22f);
                mask[yy*ww+xx] = different && strong;
            }
        }

        boolean[] seen = new boolean[mask.length];
        int[] q = new int[mask.length];
        List<VisionResult.Ball> out = new ArrayList<>();
        int[] dirs={1,0,-1,0,0,1,0,-1};
        for(int sy=1;sy<hh-1;sy++) for(int sx=1;sx<ww-1;sx++) {
            int seed=sy*ww+sx;
            if(!mask[seed]||seen[seed]) continue;
            int qh=0,qt=0; q[qt++]=seed; seen[seed]=true;
            int count=0,minx=sx,maxx=sx,miny=sy,maxy=sy;
            float sumx=0,sumy=0,sumV=0,sumS=0;
            while(qh<qt) {
                int idx=q[qh++], cy=idx/ww, cx=idx-cy*ww;
                count++; sumx+=cx; sumy+=cy;
                HSV cc=f.hsv(left+cx,top+cy); sumV+=cc.v; sumS+=cc.s;
                if(cx<minx)minx=cx;if(cx>maxx)maxx=cx;if(cy<miny)miny=cy;if(cy>maxy)maxy=cy;
                for(int d=0;d<4;d++){
                    int nx=cx+dirs[d*2],ny=cy+dirs[d*2+1];
                    if(nx<0||ny<0||nx>=ww||ny>=hh)continue;
                    int ni=ny*ww+nx;
                    if(mask[ni]&&!seen[ni]){seen[ni]=true;q[qt++]=ni;}
                }
                if(count>maxD*maxD*2) break;
            }
            int bw=maxx-minx+1,bh=maxy-miny+1;
            if(bw<minD||bh<minD||bw>maxD||bh>maxD) continue;
            float aspect=bw/(float)Math.max(1,bh);
            if(aspect<.62f||aspect>1.62f)continue;
            float extent=count/(float)(bw*bh);
            if(extent<.28f)continue;
            float cx=left+sumx/count,cy=top+sumy/count;
            float r=(bw+bh)*.25f;
            float edge=Math.min(Math.min(cx-t.roi.left,t.roi.right-cx),Math.min(cy-t.roi.top,t.roi.bottom-cy));
            float avgV=sumV/count,avgS=sumS/count;
            // Dark blobs right on the rail are usually pockets, not balls.
            if(edge<r*.65f && avgV<.25f)continue;
            boolean white=avgV>.70f&&avgS<.32f;
            float score=clamp01(extent*.7f + (1f-Math.abs(1f-aspect))*.3f);
            out.add(new VisionResult.Ball(cx,cy,r,score,white));
        }
        out.sort(Comparator.comparingDouble(b -> dist(b.x,b.y,cue.x,cue.y)));
        if(out.size()>20) return new ArrayList<>(out.subList(0,20));
        return out;
    }

    private Aim findAimGuide(PixelFrame f, Table t, Cue cue) {
        Aim best=null;
        float sideOffset=Math.max(2f,cue.r*.70f);
        for(int deg=0;deg<360;deg+=2){
            float a=(float)Math.toRadians(deg);
            float center=guideLineScore(f,t,cue,a,0f);
            float side=(guideLineScore(f,t,cue,a,sideOffset)+guideLineScore(f,t,cue,a,-sideOffset))*.5f;
            float thin=Math.max(0f,center-side);
            float score=center*.62f+thin*.75f;
            if(best==null || score>best.score) best=new Aim(a,score);
        }
        if(best==null || best.score<.30f) return null;

        Aim refined=best;
        for(float off=-2f;off<=2f;off+=.25f){
            float a=best.angle+(float)Math.toRadians(off);
            float center=guideLineScore(f,t,cue,a,0f);
            float side=(guideLineScore(f,t,cue,a,sideOffset)+guideLineScore(f,t,cue,a,-sideOffset))*.5f;
            float thin=Math.max(0f,center-side);
            float score=center*.62f+thin*.75f;
            if(score>refined.score) refined=new Aim(a,score);
        }
        return refined;
    }

    private float guideLineScore(PixelFrame f, Table t, Cue cue, float a, float offset){
        float dx=(float)Math.cos(a),dy=(float)Math.sin(a);
        float px=-dy,py=dx;
        float maxLen=Math.min(t.roi.width(),t.roi.height())*.68f;
        float weighted=0f,weights=0f;int strong=0,samples=0;
        for(float rr=cue.r*1.55f;rr<maxLen;rr+=2f){
            int x=Math.round(cue.x+dx*rr+px*offset);
            int y=Math.round(cue.y+dy*rr+py*offset);
            if(!t.roi.contains(x,y))break;
            HSV c=f.hsv(x,y);
            boolean guide=(c.v>.67f&&c.s<.38f)
                    ||(c.v>Math.min(.98f,t.val+.24f)&&c.s<t.sat*.75f);
            float wt=1f/(1f+rr/(cue.r*8f));weights+=wt;samples++;
            if(guide){weighted+=wt;if(c.v>.80f&&c.s<.25f)strong++;}
            if(samples>100)break;
        }
        if(samples<8) return 0f;
        return weighted/Math.max(.001f,weights)*.82f+Math.min(1f,strong/8f)*.18f;
    }

    private VisionResult buildGeometry(Table t, Cue cue, List<VisionResult.Ball> balls, Aim aim) {
        VisionResult r=new VisionResult();
        r.state=VisionResult.State.VALID_SHOT;
        r.roi=t.roi;
        r.cueBall=new PointF(cue.x,cue.y);
        r.cueRadius=cue.r;
        r.aimAngleRad=aim.angle;
        r.balls.addAll(balls);

        float dx=(float)Math.cos(aim.angle),dy=(float)Math.sin(aim.angle);
        VisionResult.Ball hit=null;float hitT=Float.POSITIVE_INFINITY;
        for(VisionResult.Ball b:balls){
            float vx=b.x-cue.x,vy=b.y-cue.y;
            float proj=vx*dx+vy*dy;
            if(proj<=cue.r*1.5f)continue;
            float perp2=vx*vx+vy*vy-proj*proj;
            float rr=(cue.r+b.r)*1.03f;
            if(perp2>rr*rr)continue;
            float tcol=proj-(float)Math.sqrt(Math.max(0,rr*rr-perp2));
            if(tcol<hitT){hitT=tcol;hit=b;}
        }

        if(hit==null){
            float wall=rayToRect(cue.x,cue.y,dx,dy,t.roi,cue.r*.4f);
            r.primaryEnd=new PointF(cue.x+dx*wall,cue.y+dy*wall);
            return r;
        }

        PointF impactCue=new PointF(cue.x+dx*hitT,cue.y+dy*hitT);
        r.primaryEnd=impactCue;
        r.collisionBall=new PointF(hit.x,hit.y);

        float nx=hit.x-impactCue.x,ny=hit.y-impactCue.y;
        float nl=(float)Math.hypot(nx,ny);if(nl<.001f)nl=1f;nx/=nl;ny/=nl;
        float postLen=Math.min(t.roi.width(),t.roi.height())*.27f;
        r.targetEnd=new PointF(hit.x+nx*postLen,hit.y+ny*postLen);

        float dot=dx*nx+dy*ny;
        float tx=dx-nx*dot,ty=dy-ny*dot;
        float tl=(float)Math.hypot(tx,ty);
        if(tl>.08f){tx/=tl;ty/=tl;r.cueDeflectionEnd=new PointF(impactCue.x+tx*postLen*.72f,impactCue.y+ty*postLen*.72f);}
        return r;
    }

    private static float rayToRect(float x,float y,float dx,float dy,RectF r,float inset){
        float left=r.left+inset,right=r.right-inset,top=r.top+inset,bottom=r.bottom-inset;
        float t=Float.POSITIVE_INFINITY;
        if(dx>1e-5)t=Math.min(t,(right-x)/dx);else if(dx<-1e-5)t=Math.min(t,(left-x)/dx);
        if(dy>1e-5)t=Math.min(t,(bottom-y)/dy);else if(dy<-1e-5)t=Math.min(t,(top-y)/dy);
        return Float.isFinite(t)&&t>0?t:0;
    }

    private static float ringFeltFraction(PixelFrame f,Table t,float x,float y,float radius){
        int n=40,ok=0,total=0;
        for(int i=0;i<n;i++){
            double a=i*Math.PI*2/n;int xx=Math.round(x+(float)Math.cos(a)*radius),yy=Math.round(y+(float)Math.sin(a)*radius);
            if(t.roi.contains(xx,yy)){total++;if(isFelt(f.hsv(xx,yy),t.hue))ok++;}
        }
        return ok/(float)Math.max(1,total);
    }

    private static boolean isFelt(HSV c,float hue){
        return hueDiff(c.h,hue)<24f && c.s>.22f && c.v>.14f && c.v<.97f;
    }
    private static float hueDiff(float a,float b){float d=Math.abs(a-b)%360f;return d>180f?360f-d:d;}
    private static int percentile(List<Integer> v,float p){return v.get(Math.min(v.size()-1,Math.max(0,Math.round((v.size()-1)*p))));}
    private static int sumIntegral(int[] in,int stride,int x0,int y0,int x1,int y1){
        x0=Math.max(0,x0);y0=Math.max(0,y0);x1=Math.min(stride-2,x1);int h=in.length/stride-1;y1=Math.min(h-1,y1);
        int A=in[y0*stride+x0],B=in[y0*stride+x1+1],C=in[(y1+1)*stride+x0],D=in[(y1+1)*stride+x1+1];
        return D-B-C+A;
    }
    private static float dist(float x1,float y1,float x2,float y2){return (float)Math.hypot(x1-x2,y1-y2);}
    private static float clamp01(float v){return Math.max(0f,Math.min(1f,v));}
    private static String fmt(float v){return String.format(java.util.Locale.US,"%.2f",v);}

    private static final class RowRun {int y,start,end;RowRun(int y,int s,int e){this.y=y;start=s;end=e;}}
    private static final class Table {RectF roi;float hue,sat,val,confidence;Table(RectF r,float h,float s,float v,float c){roi=r;hue=h;sat=s;val=v;confidence=c;}}
    private static final class Cue {float x,y,r,score;Cue(float x,float y,float r,float s){this.x=x;this.y=y;this.r=r;score=s;}}
    private static final class Aim {float angle,score;Aim(float a,float s){angle=a;score=s;}}
    private static final class CueAim {Cue cue;Aim aim;float score;CueAim(Cue c,Aim a,float s){cue=c;aim=a;score=s;}}
    private static final class HSV {float h,s,v;HSV(float h,float s,float v){this.h=h;this.s=s;this.v=v;}}

    private static final class PixelFrame {
        final int w,h; final int[] px; final float[] hsvCache;
        PixelFrame(Bitmap b){w=b.getWidth();h=b.getHeight();px=new int[w*h];b.getPixels(px,0,w,0,0,w,h);hsvCache=new float[w*h*3];Arrays.fill(hsvCache,-1f);}
        HSV hsv(int x,int y){
            x=Math.max(0,Math.min(w-1,x));y=Math.max(0,Math.min(h-1,y));int i=y*w+x,ci=i*3;
            if(hsvCache[ci]>=0)return new HSV(hsvCache[ci],hsvCache[ci+1],hsvCache[ci+2]);
            int c=px[i];float r=((c>>16)&255)/255f,g=((c>>8)&255)/255f,b=(c&255)/255f;
            float max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),d=max-min,hue;
            if(d==0)hue=0;else if(max==r)hue=60f*(((g-b)/d)%6f);else if(max==g)hue=60f*((b-r)/d+2f);else hue=60f*((r-g)/d+4f);
            if(hue<0)hue+=360f;float sat=max==0?0:d/max;
            hsvCache[ci]=hue;hsvCache[ci+1]=sat;hsvCache[ci+2]=max;return new HSV(hue,sat,max);
        }
    }
}
