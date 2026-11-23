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

import java.util.Locale;

/**
 * Lists the audio effects the car's Android build exposes. If the head unit ships a licensed
 * Dolby / DTS / upmix effect, it shows up here, which tells us whether a "real" Dolby or DTS
 * mode is even possible on this car.
 */
final class EffectsScanner {
    private static final String[] INTERESTING = {
            "dolby", "dts", "atmos", "surround", "upmix", "virtualiz", "spatial", "yamaha", "harman",
            "bose", "sound", "bass", "dirac", "arkamys", "qualcomm"
    };

    private EffectsScanner() {
    }

    static String describe() {
        AudioEffect.Descriptor[] all;
        try {
            all = AudioEffect.queryEffects();
        } catch (RuntimeException e) {
            return "Couldn't list effects: " + e.getMessage();
        }
        if (all == null || all.length == 0) return "No audio effects reported by this system.";

        StringBuilder highlights = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        for (AudioEffect.Descriptor d : all) {
            String line = "• " + d.name + "  (" + d.implementor + ", " + d.connectMode + ")\n"
                    + "    type " + d.type + "\n";
            String hay = (d.name + " " + d.implementor).toLowerCase(Locale.ROOT);
            boolean hit = false;
            for (String k : INTERESTING) {
                if (hay.contains(k)) {
                    hit = true;
                    break;
                }
            }
            (hit ? highlights : rest).append(line);
        }
        StringBuilder out = new StringBuilder();
        out.append(all.length).append(" effect(s) found.\n\n");
        if (highlights.length() > 0) {
            out.append("Possibly interesting:\n").append(highlights).append('\n');
        }
        if (rest.length() > 0) out.append("Others:\n").append(rest);
        return out.toString().trim();
    }
}
