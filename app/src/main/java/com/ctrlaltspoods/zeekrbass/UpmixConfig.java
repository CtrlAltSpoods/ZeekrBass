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

import android.content.Context;
import android.media.AudioFormat;

/**
 * Live upmix settings, shared in-process. The DSP reads these every block, so algorithm and level
 * changes apply instantly; only a layout change (5.1 ↔ 7.1) needs the stream to restart.
 */
final class UpmixConfig {
    /** Slider value at or below this means "off". */
    static final int MIN_DB = -30;
    static final int MAX_DB_LEVEL = 6;
    static final int MAX_DB_LFE = 12;

    private static UpmixConfig instance;

    volatile boolean layout71;
    volatile UpmixAlgorithm algorithm = UpmixAlgorithm.MUSIC;
    volatile int centerDb = -3;
    volatile int surroundDb = -3;
    volatile int lfeDb = 0;
    volatile boolean driveFronts;

    static synchronized UpmixConfig get(Context c) {
        if (instance == null) {
            instance = new UpmixConfig();
            instance.load(new Prefs(c));
        }
        return instance;
    }

    private void load(Prefs p) {
        layout71 = p.upmixLayout71();
        algorithm = p.upmixAlgorithm();
        centerDb = p.centerDb();
        surroundDb = p.surroundDb();
        lfeDb = p.lfeDb();
        driveFronts = p.driveFronts();
    }

    void save(Context c) {
        Prefs p = new Prefs(c);
        p.setUpmix(layout71, algorithm, centerDb, surroundDb, lfeDb, driveFronts);
        if (p.rootUpmix()) Root.writeConfig(c, null); // keep the root helper in sync
    }

    int channels() {
        return layout71 ? 8 : 6;
    }

    int channelMask() {
        return layout71 ? AudioFormat.CHANNEL_OUT_7POINT1_SURROUND : AudioFormat.CHANNEL_OUT_5POINT1;
    }

    String layoutLabel() {
        return layout71 ? "7.1" : "5.1";
    }

    static float gain(int db) {
        return db <= MIN_DB ? 0f : (float) Math.pow(10.0, db / 20.0);
    }

    static String dbLabel(int db) {
        return db <= MIN_DB ? "Off" : (db > 0 ? "+" : "") + db + " dB";
    }
}
