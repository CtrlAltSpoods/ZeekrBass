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

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Foreground service that owns the audio engine, the capture permission and the floating panel.
 * It keeps running while a stream is active or the panel is on screen, and stops itself otherwise.
 */
public class ZeekrBassService extends Service
        implements StreamEngine.Callback, OverlayPanel.Actions, BassEq.Listener {
    static final String ACTION_SET_MODE = "com.ctrlaltspoods.zeekrbass.SET_MODE";
    static final String ACTION_SET_DITHER = "com.ctrlaltspoods.zeekrbass.SET_DITHER";
    static final String ACTION_SHOW_PANEL = "com.ctrlaltspoods.zeekrbass.SHOW_PANEL";
    static final String ACTION_HIDE_PANEL = "com.ctrlaltspoods.zeekrbass.HIDE_PANEL";
    static final String ACTION_TOGGLE_PANEL = "com.ctrlaltspoods.zeekrbass.TOGGLE_PANEL";
    static final String ACTION_RESTORE = "com.ctrlaltspoods.zeekrbass.RESTORE";
    static final String ACTION_QUIT = "com.ctrlaltspoods.zeekrbass.QUIT";
    static final String ACTION_UPMIX_LAYOUT_CHANGED = "com.ctrlaltspoods.zeekrbass.UPMIX_LAYOUT";
    static final String ACTION_PROJECTION_RESULT = "com.ctrlaltspoods.zeekrbass.PROJECTION_RESULT";
    static final String ACTION_EQ_SYNC = "com.ctrlaltspoods.zeekrbass.EQ_SYNC";
    /** Effect Lab changed: keeps the service (and the effects it holds) alive, or lets it stop. */
    static final String ACTION_LAB_SYNC = "com.ctrlaltspoods.zeekrbass.LAB_SYNC";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_ENABLED = "enabled";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    static final String EXTRA_ERROR = "error";

    private static final String TAG = "ZeekrBass";
    private static final String CHANNEL_ID = "zeekrbass";
    private static final int NOTIFICATION_ID = 51;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private UpmixConfig upmix;
    private StreamEngine engine;
    private OverlayPanel panel;
    private EqConfig eqConfig;
    private BassEq eq;
    private MediaProjection projection;
    private Mode currentMode = Mode.OFF;   // what the user asked for
    private boolean waitingForProjection;
    private boolean inForeground;

    // ---- static helpers used by the activities, notification and boot receiver ----

    static Intent intent(Context c, String action) {
        return new Intent(c, ZeekrBassService.class).setAction(action);
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
        upmix = UpmixConfig.get(this);
        engine = new StreamEngine(this, upmix);
        engine.setDither(prefs.dither());
        panel = new OverlayPanel(this, prefs, upmix, this);
        eqConfig = EqConfig.get(this);
        eq = new BassEq(this, eqConfig, this);
        StatusBus.dither = prefs.dither();
        StatusBus.serviceRunning = true;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enterForeground(false)) {
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
            case ACTION_UPMIX_LAYOUT_CHANGED:
                onUpmixLayoutChanged();
                break;
            case ACTION_PROJECTION_RESULT:
                onProjectionResult(intent);
                break;
            case ACTION_EQ_SYNC:
                eq.sync();
                break;
            case ACTION_LAB_SYNC:
                refreshUi();
                break;
            case ACTION_QUIT:
                applyMode(Mode.OFF);
                setPanelVisible(false);
                eqConfig.enabled = false;
                eqConfig.save();
                eq.sync();
                break;
            case ACTION_RESTORE:
            default:
                applyMode(prefs.mode());
                if (prefs.overlayVisible()) setPanelVisible(true);
                eq.sync();
                break;
        }
        stopIfIdle();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        eq.stop();
        EffectLab.get().releaseAll();
        engine.release();
        releaseProjection();
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

    @Override
    public void onAlgorithmSelected(UpmixAlgorithm algorithm) {
        upmix.algorithm = algorithm; // applied live by the DSP
        upmix.save(this);
        refreshUi();
    }

    @Override
    public void onEqToggled() {
        eqConfig.enabled = !eqConfig.enabled;
        eqConfig.save();
        eq.sync();
        refreshUi();
        stopIfIdle();
    }

    @Override
    public void onEqStatus(String status, boolean healthy) {
        StatusBus.eqStatus = status;
        StatusBus.eqHealthy = healthy;
        refreshUi();
    }

    @Override
    public void onLayoutToggled() {
        upmix.layout71 = !upmix.layout71;
        upmix.save(this);
        onUpmixLayoutChanged();
    }

    // ---- engine callbacks (audio thread) ----

    @Override
    public void onStreamStatus(final int generation, final String detail, final boolean healthy) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (generation != engine.generation()) return; // stale report
                StatusBus.detail = detail;
                StatusBus.healthy = healthy;
                refreshUi();
            }
        });
    }

    @Override
    public void onCaptureFailed(final int generation, final String reason) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (generation != engine.generation()) return;
                releaseProjection();
                fallBackToSilent(reason + ". Keeping a silent " + upmix.layoutLabel()
                        + " stream running instead.");
            }
        });
    }

    // ---- state changes ----

    private void applyMode(Mode mode) {
        prefs.setMode(mode);
        currentMode = mode;
        StatusBus.mode = mode;

        if (mode == Mode.UPMIX) {
            if (Build.VERSION.SDK_INT < 29) {
                fallBackToSilent("Upmix needs Android 10 or newer");
                return;
            }
            if (projection == null) {
                // Keep the car in multichannel mode while we wait for the capture consent.
                startEngine(silentFor(upmix), false);
                StatusBus.detail = "Waiting for audio capture permission…";
                StatusBus.healthy = true;
                requestProjection();
            } else if (engine.mode() != Mode.UPMIX) {
                startEngine(Mode.UPMIX, false);
                StatusBus.detail = "Starting upmix to " + upmix.layoutLabel() + "…";
                StatusBus.healthy = true;
            }
        } else {
            waitingForProjection = false;
            releaseProjection();
            if (engine.mode() != mode) {
                StatusBus.detail = mode == Mode.OFF ? "Off" : "Starting silent " + mode.label + " stream…";
                StatusBus.healthy = true;
            }
            startEngine(mode, false);
        }
        refreshUi();
    }

    private void startEngine(Mode mode, boolean force) {
        if (!force && engine.mode() == mode) return;
        engine.start(mode, projection);
    }

    private static Mode silentFor(UpmixConfig cfg) {
        return cfg.layout71 ? Mode.SURROUND_71 : Mode.SURROUND_51;
    }

    private void fallBackToSilent(String why) {
        startEngine(silentFor(upmix), false);
        StatusBus.mode = currentMode;
        StatusBus.detail = why;
        StatusBus.healthy = false;
        refreshUi();
    }

    private void onUpmixLayoutChanged() {
        if (currentMode != Mode.UPMIX) {
            refreshUi();
            return;
        }
        if (projection != null) {
            startEngine(Mode.UPMIX, true); // channel count changed: restart the stream
        } else {
            startEngine(silentFor(upmix), false);
        }
        refreshUi();
    }

    private void requestProjection() {
        if (waitingForProjection) return;
        waitingForProjection = true;
        Intent i = new Intent(this, ProjectionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            waitingForProjection = false;
            Log.w(TAG, "Could not open the capture prompt", e);
            fallBackToSilent("Open ZeekrBass and tap Upmix to allow audio capture");
        }
    }

    private void onProjectionResult(Intent intent) {
        waitingForProjection = false;
        int code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = resultData(intent);
        String error = intent.getStringExtra(EXTRA_ERROR);

        if (code != Activity.RESULT_OK || data == null || Build.VERSION.SDK_INT < 29) {
            fallBackToSilent((error != null ? error : "Capture was declined")
                    + ". Keeping a silent " + upmix.layoutLabel() + " stream running.");
            return;
        }
        releaseProjection(); // drop any older capture session first
        // Android 14 requires the mediaProjection service type before getMediaProjection().
        if (!enterForeground(true)) {
            fallBackToSilent("Android refused the capture service");
            return;
        }
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            final MediaProjection p = mpm.getMediaProjection(code, data);
            if (p == null) throw new IllegalStateException("no projection");
            p.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (projection != p) return; // an old session we replaced or released
                    projection = null;
                    if (currentMode == Mode.UPMIX) {
                        fallBackToSilent("Audio capture was stopped by the system. Tap Upmix to start it again");
                    }
                }
            }, main);
            projection = p;
        } catch (RuntimeException e) {
            Log.w(TAG, "getMediaProjection failed", e);
            projection = null;
            fallBackToSilent("Couldn't start audio capture: " + e.getMessage());
            return;
        }
        if (currentMode == Mode.UPMIX) {
            startEngine(Mode.UPMIX, true);
            StatusBus.mode = Mode.UPMIX;
            StatusBus.detail = "Starting upmix to " + upmix.layoutLabel() + "…";
            StatusBus.healthy = true;
            refreshUi();
        } else {
            releaseProjection(); // user switched away while the prompt was open
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent resultData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private void releaseProjection() {
        MediaProjection p = projection;
        projection = null;
        if (p != null) {
            try {
                p.stop();
            } catch (RuntimeException ignored) {
            }
            if (inForeground) enterForeground(false); // drop the capture service types
        }
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
        if (currentMode == Mode.OFF && engine.mode() == Mode.OFF && !panel.isShown()
                && !eqConfig.enabled && !EffectLab.get().anyAttached()) {
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

    // ---- notification / foreground ----

    /**
     * @param capture true while audio capture is active: adds the mediaProjection (and, where
     *                possible, microphone) service types that Android requires for capture.
     */
    private boolean enterForeground(boolean capture) {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
                if (capture || projection != null) {
                    types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            startForeground(NOTIFICATION_ID, n,
                                    types | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                            inForeground = true;
                            return true;
                        } catch (RuntimeException e) {
                            Log.i(TAG, "Microphone service type refused, continuing without it", e);
                        }
                    }
                }
                startForeground(NOTIFICATION_ID, n, types);
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
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "ZeekrBass",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shows while ZeekrBass is running");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        String title;
        switch (currentMode) {
            case UPMIX:
                title = "Upmix " + upmix.layoutLabel() + " · " + upmix.algorithm.label;
                break;
            case OFF:
                title = eqConfig != null && eqConfig.enabled ? "Bass EQ on" : "ZeekrBass off";
                break;
            default:
                title = "Silent " + currentMode.label + " stream active";
                break;
        }
        if (eqConfig != null && eqConfig.enabled && currentMode != Mode.OFF) title += " + Bass EQ";
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
                .addAction(action(2, "Upmix", intent(this, ACTION_SET_MODE).putExtra(EXTRA_MODE, Mode.UPMIX.name())))
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
