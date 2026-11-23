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

/** Live Bass EQ settings, shared in-process and saved to preferences. */
final class EqConfig {
    static final int MAX_BOOST_DB = 12;
    static final int LOUDNESS_MAX_EXTRA_DB = 6;

    private static EqConfig instance;
    private final SharedPreferences sp;

    volatile boolean enabled;
    volatile boolean global = true;     // method 2: the global output mix (session 0)
    volatile boolean perApp = true;     // music apps that announce their audio session
    volatile int boostDb = 6;
    volatile boolean loudness = true;   // extra bass at low volume

    private EqConfig(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("zeekrbass_eq", Context.MODE_PRIVATE);
        enabled = sp.getBoolean("enabled", false);
        global = sp.getBoolean("global", true);
        perApp = sp.getBoolean("per_app", true);
        boostDb = sp.getInt("boost_db", 6);
        loudness = sp.getBoolean("loudness", true);
    }

    static synchronized EqConfig get(Context c) {
        if (instance == null) instance = new EqConfig(c);
        return instance;
    }

    void save() {
        sp.edit()
                .putBoolean("enabled", enabled)
                .putBoolean("global", global)
                .putBoolean("per_app", perApp)
                .putInt("boost_db", boostDb)
                .putBoolean("loudness", loudness)
                .apply();
    }
}
