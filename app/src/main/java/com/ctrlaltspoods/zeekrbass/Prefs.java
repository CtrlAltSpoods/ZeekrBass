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
import android.content.SharedPreferences;

/** Small wrapper around the app's saved settings. */
final class Prefs {
    private static final String FILE = "zeekrbass";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    Mode mode() {
        return Mode.fromName(sp.getString("mode", Mode.OFF.name()));
    }

    void setMode(Mode mode) {
        sp.edit().putString("mode", mode.name()).apply();
    }

    boolean dither() {
        return sp.getBoolean("dither", false);
    }

    void setDither(boolean on) {
        sp.edit().putBoolean("dither", on).apply();
    }

    boolean restoreOnBoot() {
        return sp.getBoolean("restore_on_boot", true);
    }

    void setRestoreOnBoot(boolean on) {
        sp.edit().putBoolean("restore_on_boot", on).apply();
    }

    boolean overlayVisible() {
        return sp.getBoolean("overlay_visible", false);
    }

    void setOverlayVisible(boolean visible) {
        sp.edit().putBoolean("overlay_visible", visible).apply();
    }

    boolean collapsed() {
        return sp.getBoolean("overlay_collapsed", false);
    }

    void setCollapsed(boolean collapsed) {
        sp.edit().putBoolean("overlay_collapsed", collapsed).apply();
    }

    int overlayX(int fallback) {
        return sp.getInt("overlay_x", fallback);
    }

    int overlayY(int fallback) {
        return sp.getInt("overlay_y", fallback);
    }

    void setOverlayPosition(int x, int y) {
        sp.edit().putInt("overlay_x", x).putInt("overlay_y", y).apply();
    }

    boolean upmixLayout71() {
        return sp.getBoolean("upmix_71", false);
    }

    UpmixAlgorithm upmixAlgorithm() {
        return UpmixAlgorithm.fromName(sp.getString("upmix_algo", UpmixAlgorithm.MUSIC.name()));
    }

    int centerDb() {
        return sp.getInt("upmix_center_db", -3);
    }

    int surroundDb() {
        return sp.getInt("upmix_surround_db", -3);
    }

    int lfeDb() {
        return sp.getInt("upmix_lfe_db", 0);
    }

    boolean driveFronts() {
        return sp.getBoolean("upmix_fronts", false);
    }

    boolean rootUpmix() {
        return sp.getBoolean("root_upmix", false);
    }

    void setRootUpmix(boolean on) {
        sp.edit().putBoolean("root_upmix", on).apply();
    }

    void setUpmix(boolean layout71, UpmixAlgorithm algo, int centerDb, int surroundDb, int lfeDb,
                  boolean fronts) {
        sp.edit()
                .putBoolean("upmix_71", layout71)
                .putString("upmix_algo", algo.name())
                .putInt("upmix_center_db", centerDb)
                .putInt("upmix_surround_db", surroundDb)
                .putInt("upmix_lfe_db", lfeDb)
                .putBoolean("upmix_fronts", fronts)
                .apply();
    }
}
