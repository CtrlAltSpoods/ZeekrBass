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

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process status shared between the service, the floating panel and the main screen.
 * Fields are written on the main thread; listeners are always called on the main thread.
 */
final class StatusBus {
    interface Listener {
        void onStatusChanged();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    static volatile boolean serviceRunning;
    static volatile Mode mode = Mode.OFF;
    static volatile String detail = "Off";
    static volatile boolean healthy = true;
    static volatile boolean dither;
    static volatile boolean overlayShown;
    /** Upmix meters, updated a few times a second by the audio thread (not via publish()). */
    static volatile float inputPeakDb = -120f;
    static volatile int bufferedMs;
    static volatile String eqStatus = "Bass EQ off";
    static volatile boolean eqHealthy = true;

    private StatusBus() {
    }

    static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    static void publish() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) l.onStatusChanged();
            }
        });
    }
}
