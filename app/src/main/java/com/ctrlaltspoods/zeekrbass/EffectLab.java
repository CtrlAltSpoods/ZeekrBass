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

import android.media.audiofx.AudioEffect;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Effect Lab: attaches the car's own audio effects (Qualcomm SUMX, AVC, Bass-Mid-Treble,
 * Fade-n-Balance, NXP effects…) to the whole-car output mix (audio session 0), the same way the
 * global Bass EQ attaches. Lets you switch each one on/off while music plays and read its settings.
 *
 * Creating an effect by its ID uses a hidden Android constructor (other EQ apps use it too).
 * Everything here is undone by tapping Off, closing ZeekrBass, or restarting the car.
 *
 * Note: if the car already runs the same effect on the output mix, Android hands us a second
 * handle to the car's own instance instead of a new one. The state line shows when that happens
 * ("was already on").
 */
final class EffectLab {
    static final class Item {
        final AudioEffect.Descriptor d;
        AudioEffect fx;
        boolean wasAlreadyOn;
        boolean bypassed;
        String state = "off";
        String settings = "";

        Item(AudioEffect.Descriptor d) {
            this.d = d;
        }

        String title() {
            return d.name + "  (" + d.implementor + ")";
        }
    }

    private static final String TAG = "ZeekrBass";
    private static final int PRIORITY = 0;
    private static EffectLab instance;

    final List<Item> items = new ArrayList<>();

    private EffectLab() {
        AudioEffect.Descriptor[] all;
        try {
            all = AudioEffect.queryEffects();
        } catch (RuntimeException e) {
            all = new AudioEffect.Descriptor[0];
        }
        List<Item> first = new ArrayList<>();
        List<Item> rest = new ArrayList<>();
        if (all != null) {
            for (AudioEffect.Descriptor d : all) {
                if (!AudioEffect.EFFECT_INSERT.equals(d.connectMode)) continue; // output effects only
                String n = d.name.toLowerCase(Locale.ROOT);
                if (n.contains("visualizer")) continue;
                boolean qcom = d.implementor.toLowerCase(Locale.ROOT).contains("qualcomm");
                (qcom ? first : rest).add(new Item(d));
            }
        }
        // SUMX first: it's the one most likely to do any channel mixing.
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i).d.name.toUpperCase(Locale.ROOT).contains("SUMX")) {
                first.add(0, first.remove(i));
                break;
            }
        }
        items.addAll(first);
        items.addAll(rest);
    }

    static synchronized EffectLab get() {
        if (instance == null) instance = new EffectLab();
        return instance;
    }

    boolean anyAttached() {
        for (Item it : items) if (it.fx != null) return true;
        return false;
    }

    /** Attaches the effect to the whole-car output and enables it. */
    void turnOn(Item it) {
        if (it.fx == null) {
            try {
                it.fx = create(it.d);
            } catch (Throwable e) {
                Log.w(TAG, "Effect Lab: could not attach " + it.d.name, e);
                it.state = "refused: " + rootMessage(e);
                return;
            }
            try {
                it.wasAlreadyOn = it.fx.getEnabled();
            } catch (RuntimeException e) {
                it.wasAlreadyOn = false;
            }
        }
        try {
            int res = it.fx.setEnabled(true);
            it.state = describe(it, res);
        } catch (RuntimeException e) {
            it.state = "attached, but enabling failed: " + rootMessage(e);
        }
    }

    /** Disables and releases our handle. If the car owns the effect, control returns to it. */
    void turnOff(Item it) {
        if (it.fx == null) {
            it.state = "off";
            return;
        }
        try {
            // Leave the car's own effects exactly as we found them.
            if (it.wasAlreadyOn) {
                if (it.bypassed) it.fx.setEnabled(true);
            } else {
                it.fx.setEnabled(false);
            }
        } catch (RuntimeException ignored) {
        }
        it.bypassed = false;
        try {
            it.fx.release();
        } catch (RuntimeException ignored) {
        }
        it.fx = null;
        it.state = "off";
    }

    /** Briefly switches a car-owned effect OFF (only possible if we got control of it). */
    void bypass(Item it, boolean bypass) {
        if (it.fx == null) return;
        try {
            int res = it.fx.setEnabled(!bypass);
            it.bypassed = bypass;
            it.state = (bypass ? "bypassed (switched off)" : "back on") + " · " + describe(it, res);
        } catch (RuntimeException e) {
            it.state = "couldn't change: " + rootMessage(e);
        }
    }

    void releaseAll() {
        for (Item it : items) turnOff(it);
    }

    /** Reads parameters 0–15 (read-only) so we can see what the effect exposes. */
    void readSettings(Item it) {
        boolean temporary = false;
        try {
            if (it.fx == null) {
                it.fx = create(it.d);
                temporary = true;
            }
            Method get = AudioEffect.class.getMethod("getParameter", int.class, byte[].class);
            StringBuilder sb = new StringBuilder();
            int found = 0;
            for (int p = 0; p < 16; p++) {
                byte[] value = new byte[16];
                int res;
                try {
                    res = (Integer) get.invoke(it.fx, p, value);
                } catch (Throwable e) {
                    continue;
                }
                if (res < 0) continue;
                found++;
                sb.append("  param ").append(p).append(" → ");
                int ints = Math.max(1, Math.min(4, res / 4));
                for (int k = 0; k < ints; k++) {
                    int v = (value[4 * k] & 0xFF) | (value[4 * k + 1] & 0xFF) << 8
                            | (value[4 * k + 2] & 0xFF) << 16 | (value[4 * k + 3] & 0xFF) << 24;
                    sb.append(v).append(k < ints - 1 ? ", " : "");
                }
                sb.append("  (").append(res).append(" bytes)\n");
            }
            it.settings = found == 0 ? "No readable settings in params 0–15."
                    : "Readable settings:\n" + sb.toString().replaceAll("\\s+$", "");
        } catch (Throwable e) {
            it.settings = "Couldn't read settings: " + rootMessage(e);
        } finally {
            if (temporary && it.fx != null) {
                try {
                    it.fx.release();
                } catch (RuntimeException ignored) {
                }
                it.fx = null;
            }
        }
    }

    private static AudioEffect create(AudioEffect.Descriptor d) throws Exception {
        Constructor<AudioEffect> c = AudioEffect.class.getDeclaredConstructor(
                UUID.class, UUID.class, int.class, int.class);
        c.setAccessible(true);
        return c.newInstance(d.type, d.uuid, PRIORITY, 0 /* whole-car output mix */);
    }

    private static String describe(Item it, int setEnabledResult) {
        StringBuilder s = new StringBuilder("attached to whole-car output");
        boolean enabled = false;
        boolean control = false;
        try {
            enabled = it.fx.getEnabled();
            control = it.fx.hasControl();
        } catch (RuntimeException ignored) {
        }
        s.append(enabled ? " · ON" : " · not enabled");
        s.append(control ? " · we have control" : " · the car has control");
        if (it.wasAlreadyOn) s.append(" · was already on (the car runs this one)");
        if (setEnabledResult != AudioEffect.SUCCESS) s.append(" · result ").append(setEnabledResult);
        return s.toString();
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }
}
