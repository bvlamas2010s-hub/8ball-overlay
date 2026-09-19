package com.example.pooltrajectory;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 501;
    private static final int REQ_NOTIFICATIONS = 502;
    private MediaProjectionManager projectionManager;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(245, 245, 245));

        TextView title = new TextView(this);
        title.setText("Pool Trajectory Lab");
        title.setTextSize(26f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(10)));

        TextView info = new TextView(this);
        info.setText("Clean-room prototype: screen capture → table validation → cue-ball/guide detection → trajectory overlay.\n\nIt refuses to draw when a pool-table scene cannot be validated.");
        info.setTextSize(16f);
        info.setTextColor(Color.DKGRAY);
        info.setGravity(Gravity.CENTER);
        root.addView(info, matchWrap(dp(28)));

        Button overlay = new Button(this);
        overlay.setText("1. Allow overlay");
        overlay.setOnClickListener(v -> requestOverlay());
        root.addView(overlay, matchWrap(dp(8)));

        Button start = new Button(this);
        start.setText("2. Start detector");
        start.setOnClickListener(v -> startDetector());
        root.addView(start, matchWrap(dp(8)));

        Button stop = new Button(this);
        stop.setText("Stop detector");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            setStatus("Stopped");
        });
        root.addView(stop, matchWrap(dp(18)));

        status = new TextView(this);
        status.setTextSize(15f);
        status.setTextColor(Color.BLACK);
        status.setText("Status: ready");
        status.setGravity(Gravity.CENTER);
        root.addView(status, matchWrap(0));

        setContentView(root);
        maybeAskNotificationPermission();
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = bottomMargin;
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            setStatus("Overlay permission already granted");
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void startDetector() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Grant overlay permission first");
            requestOverlay();
            return;
        }
        setStatus("Waiting for screen-capture permission…");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Screen capture denied");
            return;
        }

        Intent service = new Intent(this, CaptureService.class);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        setStatus("Detector started — open the pool game");
    }

    private void setStatus(String message) {
        if (status != null) status.setText("Status: " + message);
    }
}
