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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Foreground service that owns the silent stream and the floating panel.
 * It keeps running while a stream is active or the panel is on screen, and stops itself otherwise.
 */
public class SilentStreamService extends Service implements SilentStreamEngine.Callback, OverlayPanel.Actions {
    static final String ACTION_SET_MODE = "com.ctrlaltspoods.zeekrbass.SET_MODE";
    static final String ACTION_SET_DITHER = "com.ctrlaltspoods.zeekrbass.SET_DITHER";
    static final String ACTION_SHOW_PANEL = "com.ctrlaltspoods.zeekrbass.SHOW_PANEL";
    static final String ACTION_HIDE_PANEL = "com.ctrlaltspoods.zeekrbass.HIDE_PANEL";
    static final String ACTION_TOGGLE_PANEL = "com.ctrlaltspoods.zeekrbass.TOGGLE_PANEL";
    static final String ACTION_RESTORE = "com.ctrlaltspoods.zeekrbass.RESTORE";
    static final String ACTION_QUIT = "com.ctrlaltspoods.zeekrbass.QUIT";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_ENABLED = "enabled";

    private static final String TAG = "ZeekrBass";
    private static final String CHANNEL_ID = "zeekrbass";
    private static final int NOTIFICATION_ID = 51;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private SilentStreamEngine engine;
    private OverlayPanel panel;
    private boolean inForeground;

    // ---- static helpers used by the activity, notification and boot receiver ----

    static Intent intent(Context c, String action) {
        return new Intent(c, SilentStreamService.class).setAction(action);
    }

    static void send(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void setMode(Context c, Mode mode) {
        send(c, intent(c, ACTION_SET_MODE).putExtra(EXTRA_MODE, mode.name()));
    }

    // ---- lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        engine = new SilentStreamEngine(this);
        engine.setDither(prefs.dither());
        panel = new OverlayPanel(this, prefs, this);
        StatusBus.dither = prefs.dither();
        StatusBus.serviceRunning = true;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enterForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? null : intent.getAction();
        if (action == null) action = ACTION_RESTORE; // sticky restart after being killed

        switch (action) {
            case ACTION_SET_MODE:
                applyMode(Mode.fromName(intent.getStringExtra(EXTRA_MODE)));
                break;
            case ACTION_SET_DITHER:
                applyDither(intent.getBooleanExtra(EXTRA_ENABLED, false));
                break;
            case ACTION_SHOW_PANEL:
                setPanelVisible(true);
                break;
            case ACTION_HIDE_PANEL:
                setPanelVisible(false);
                break;
            case ACTION_TOGGLE_PANEL:
                setPanelVisible(!panel.isShown());
                break;
            case ACTION_QUIT:
                applyMode(Mode.OFF);
                setPanelVisible(false);
                break;
            case ACTION_RESTORE:
            default:
                applyMode(prefs.mode());
                if (prefs.overlayVisible()) setPanelVisible(true);
                break;
        }
        stopIfIdle();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        engine.release();
        panel.hide();
        inForeground = false;
        StatusBus.serviceRunning = false;
        StatusBus.overlayShown = false;
        StatusBus.mode = Mode.OFF;
        StatusBus.detail = "Off";
        StatusBus.healthy = true;
        StatusBus.publish();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- panel callbacks (main thread) ----

    @Override
    public void onModeSelected(Mode mode) {
        applyMode(mode);
    }

    @Override
    public void onDitherToggled() {
        applyDither(!prefs.dither());
    }

    // ---- engine callback (audio thread) ----

    @Override
    public void onStreamStatus(final Mode mode, final String detail, final boolean healthy) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (mode != engine.mode()) return; // stale report from a stream we already replaced
                StatusBus.mode = mode;
                StatusBus.detail = detail;
                StatusBus.healthy = healthy;
                refreshUi();
            }
        });
    }

    // ---- state changes ----

    private void applyMode(Mode mode) {
        prefs.setMode(mode);
        if (mode != engine.mode()) {
            StatusBus.mode = mode;
            StatusBus.detail = mode == Mode.OFF ? "Off" : "Starting silent " + mode.label + " stream…";
            StatusBus.healthy = true;
        }
        engine.setMode(mode);
        refreshUi();
    }

    private void applyDither(boolean on) {
        prefs.setDither(on);
        engine.setDither(on);
        StatusBus.dither = on;
        refreshUi();
    }

    private void setPanelVisible(boolean visible) {
        if (visible) {
            boolean ok = panel.show();
            prefs.setOverlayVisible(ok);
            if (!ok) {
                StatusBus.detail = "Floating panel needs the “Display over other apps” permission";
                StatusBus.healthy = false;
            }
        } else {
            panel.hide();
            prefs.setOverlayVisible(false);
        }
        StatusBus.overlayShown = panel.isShown();
        refreshUi();
    }

    private void stopIfIdle() {
        if (engine.mode() == Mode.OFF && !panel.isShown()) {
            inForeground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void refreshUi() {
        panel.render();
        StatusBus.publish();
        if (inForeground) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    // ---- notification ----

    private boolean enterForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            inForeground = true;
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not enter foreground", e);
            return false;
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Silent stream",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shows while the ZeekrBass workaround is running");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Mode mode = engine == null ? Mode.OFF : engine.mode();
        String title = mode == Mode.OFF ? "Workaround off" : "Silent " + mode.label + " stream active";

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(StatusBus.detail)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(action(1, "5.1", intent(this, ACTION_SET_MODE).putExtra(EXTRA_MODE, Mode.SURROUND_51.name())))
                .addAction(action(2, "7.1", intent(this, ACTION_SET_MODE).putExtra(EXTRA_MODE, Mode.SURROUND_71.name())))
                .addAction(action(3, "Off", intent(this, ACTION_SET_MODE).putExtra(EXTRA_MODE, Mode.OFF.name())))
                .addAction(action(4, panel != null && panel.isShown() ? "Hide panel" : "Show panel",
                        intent(this, ACTION_TOGGLE_PANEL)));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private Notification.Action action(int requestCode, String label, Intent i) {
        PendingIntent pi = PendingIntent.getForegroundService(this, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder((Icon) null, label, pi).build();
    }
}
