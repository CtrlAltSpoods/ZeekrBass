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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Bass EQ built on Android's own DynamicsProcessing effect (no capture, no added latency).
 *
 *  - Global: attached to the global output mix (audio session 0). This is the "legacy mode"
 *    other EQ apps offer. Android has deprecated it, so it depends on the car's audio framework.
 *  - Per-app: attached to each music app that announces its audio session (Spotify, YouTube
 *    Music, Plexamp and most players do).
 *
 * Curve: a bass shelf (full boost below 60 Hz, half boost 60–150 Hz, flat above), optional
 * loudness compensation (up to +6 dB more bass as the volume goes down), and a limiter so the
 * boost can't clip.
 */
final class BassEq {
    interface Listener {
        void onEqStatus(String status, boolean healthy);
    }

    private static final String TAG = "ZeekrBass";
    private static final int PRIORITY = 1000;
    private static final float[] CUTOFFS = {60f, 150f, 20000f};

    private final Context ctx;
    private final EqConfig cfg;
    private final Listener listener;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, DynamicsProcessing> sessions = new HashMap<>();
    private final Map<Integer, String> sessionApps = new HashMap<>();
    private DynamicsProcessing global;
    private String globalState = "off";
    private boolean receiverRegistered;
    private boolean running;
    private float lastAppliedDb = Float.NaN;

    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR);
            String pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
            if (session <= 0) return; // 0 = global mix, handled separately
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) {
                attachSession(session, pkg);
            } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) {
                releaseSession(session);
            }
            report();
        }
    };

    private final Runnable volumeTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            applyGains(false);
            main.postDelayed(this, 1000);
        }
    };

    BassEq(Context ctx, EqConfig cfg, Listener listener) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.listener = listener;
        this.am = ctx.getSystemService(AudioManager.class);
    }

    boolean isRunning() {
        return running;
    }

    /** Brings the effects in line with the config: start, stop, or re-apply settings. */
    void sync() {
        if (Build.VERSION.SDK_INT < 28) {
            listener.onEqStatus("Bass EQ needs Android 9 or newer", false);
            return;
        }
        if (!cfg.enabled) {
            stop();
            return;
        }
        running = true;

        if (cfg.global && global == null) {
            try {
                global = create(0);
                globalState = "attached";
            } catch (Throwable e) {
                Log.w(TAG, "Global EQ refused", e);
                global = null;
                globalState = "refused by the car (" + e.getClass().getSimpleName() + ")";
            }
        } else if (!cfg.global && global != null) {
            release(global);
            global = null;
            globalState = "off";
        } else if (!cfg.global) {
            globalState = "off";
        }

        if (cfg.perApp && !receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(sessionReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(sessionReceiver, f);
            }
            receiverRegistered = true;
        } else if (!cfg.perApp && receiverRegistered) {
            unregisterReceiver();
            releaseAllSessions();
        }

        applyGains(true);
        main.removeCallbacks(volumeTick);
        main.postDelayed(volumeTick, 1000);
        report();
    }

    void stop() {
        running = false;
        main.removeCallbacks(volumeTick);
        unregisterReceiver();
        releaseAllSessions();
        if (global != null) release(global);
        global = null;
        globalState = "off";
        lastAppliedDb = Float.NaN;
        listener.onEqStatus("Bass EQ off", true);
    }

    // ---------------------------------------------------------------- effects

    private void attachSession(int session, String pkg) {
        if (!running || !cfg.perApp || sessions.containsKey(session)) return;
        try {
            sessions.put(session, create(session));
            sessionApps.put(session, pkg == null ? "app" : shortName(pkg));
        } catch (Throwable e) {
            Log.w(TAG, "Could not attach to session " + session + " (" + pkg + ")", e);
        }
    }

    private void releaseSession(int session) {
        DynamicsProcessing dp = sessions.remove(session);
        sessionApps.remove(session);
        if (dp != null) release(dp);
    }

    private void releaseAllSessions() {
        for (DynamicsProcessing dp : sessions.values()) release(dp);
        sessions.clear();
        sessionApps.clear();
    }

    private DynamicsProcessing create(int session) {
        DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,                       // channels (Android adapts this to the real count)
                true, CUTOFFS.length,    // pre-EQ
                false, 0,                // multiband compressor
                false, 0,                // post-EQ
                true)                    // limiter
                .setPreferredFrameDuration(10f)
                .build();
        DynamicsProcessing dp = new DynamicsProcessing(PRIORITY, session, config);
        float db = targetBoostDb();
        for (int b = 0; b < CUTOFFS.length; b++) {
            dp.setPreEqBandAllChannelsTo(b, band(b, db));
        }
        dp.setLimiterAllChannelsTo(limiter());
        dp.setEnabled(true);
        return dp;
    }

    private static void release(DynamicsProcessing dp) {
        try {
            dp.setEnabled(false);
        } catch (Throwable ignored) {
        }
        try {
            dp.release();
        } catch (Throwable ignored) {
        }
    }

    private void applyGains(boolean force) {
        float db = targetBoostDb();
        if (!force && Math.abs(db - lastAppliedDb) < 0.25f) return;
        lastAppliedDb = db;
        if (global != null) applyTo(global, db);
        for (DynamicsProcessing dp : sessions.values()) applyTo(dp, db);
        if (!force) report();
    }

    private static void applyTo(DynamicsProcessing dp, float db) {
        try {
            for (int b = 0; b < CUTOFFS.length; b++) dp.setPreEqBandAllChannelsTo(b, band(b, db));
        } catch (Throwable e) {
            Log.w(TAG, "EQ update failed", e);
        }
    }

    private static DynamicsProcessing.EqBand band(int index, float boostDb) {
        float gain = index == 0 ? boostDb : index == 1 ? boostDb / 2f : 0f;
        return new DynamicsProcessing.EqBand(true, CUTOFFS[index], gain);
    }

    private static DynamicsProcessing.Limiter limiter() {
        // inUse, enabled, linkGroup, attack ms, release ms, ratio, threshold dB, post-gain dB
        return new DynamicsProcessing.Limiter(true, true, 0, 1f, 80f, 10f, -1.5f, 0f);
    }

    /** Base boost plus loudness compensation that grows as the media volume goes down. */
    private float targetBoostDb() {
        float db = cfg.boostDb;
        if (cfg.loudness) db += EqConfig.LOUDNESS_MAX_EXTRA_DB * (1f - volumeFraction());
        return db;
    }

    float volumeFraction() {
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            return max > 0 ? Math.max(0f, Math.min(1f, cur / (float) max)) : 1f;
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    // ---------------------------------------------------------------- status

    private void report() {
        if (!running) return;
        StringBuilder s = new StringBuilder();
        s.append(String.format("Bass +%.1f dB", lastAppliedDb));
        if (cfg.loudness) s.append(String.format(" (volume %d%%)", Math.round(volumeFraction() * 100)));
        s.append(" · Global: ").append(globalState);
        if (cfg.perApp) {
            s.append(" · Apps: ");
            if (sessionApps.isEmpty()) s.append("none announced yet (start playback)");
            else s.append(String.join(", ", sessionApps.values()));
        }
        boolean ok = global != null || !sessions.isEmpty();
        listener.onEqStatus(s.toString(), ok || (cfg.perApp && !cfg.global));
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        try {
            ctx.unregisterReceiver(sessionReceiver);
        } catch (RuntimeException ignored) {
        }
        receiverRegistered = false;
    }

    private static String shortName(String pkg) {
        int dot = pkg.lastIndexOf('.');
        return dot >= 0 && dot < pkg.length() - 1 ? pkg.substring(dot + 1) : pkg;
    }
}
