/*
 * ZeekrBass — https://github.com/CtrlAltSpoods/ZeekrBass
 *
 * Unofficial community project. NOT affiliated with, endorsed by, sponsored by or otherwise
 * connected to Zeekr, Zeekr Intelligent Technology, Geely or any of their affiliates.
 * "Zeekr" and "7X" are trademarks of their respective owners and are used here only to
 * describe the vehicle this app was made for.
 *
 * Experimental and still in testing. Provided "as is", without warranty of any kind.
 * Installing third-party apps on your vehicle is entirely at your own risk.
 */

package com.ctrlaltspoods.zeekrbass;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Invisible screen that asks Android for the two things the upmixer needs:
 *  1. the RECORD_AUDIO permission (required for any audio capture), and
 *  2. a one-off "capture" consent from MediaProjection (Android shows its own dialog).
 * The result is handed straight to the service, then this activity closes.
 *
 * Tip: `adb shell appops set com.ctrlaltspoods.zeekrbass PROJECT_MEDIA allow` makes Android
 * approve step 2 automatically, so upmix can also come back on its own after a restart.
 */
public class ProjectionActivity extends Activity {
    private static final int REQ_PERMISSION = 1;
    private static final int REQ_CAPTURE = 2;
    private boolean delivered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return; // a request is already in flight
        if (Build.VERSION.SDK_INT < 29) {
            deliver(RESULT_CANCELED, null, "Upmix needs Android 10 or newer");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSION);
        } else {
            askForCapture();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            askForCapture();
        } else {
            deliver(RESULT_CANCELED, null, "Audio capture permission was not granted");
        }
    }

    private void askForCapture() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (ActivityNotFoundException | SecurityException e) {
            deliver(RESULT_CANCELED, null, "This head unit has no capture consent screen");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_CAPTURE) return;
        deliver(resultCode, data, resultCode == RESULT_OK ? null : "Capture was declined");
    }

    private void deliver(int resultCode, Intent data, String error) {
        if (delivered) return;
        delivered = true;
        Intent i = ZeekrBassService.intent(this, ZeekrBassService.ACTION_PROJECTION_RESULT)
                .putExtra(ZeekrBassService.EXTRA_RESULT_CODE, resultCode);
        if (data != null) i.putExtra(ZeekrBassService.EXTRA_RESULT_DATA, data);
        if (error != null) i.putExtra(ZeekrBassService.EXTRA_ERROR, error);
        try {
            ZeekrBassService.send(this, i);
        } catch (RuntimeException ignored) {
        }
        finish();
    }
}
