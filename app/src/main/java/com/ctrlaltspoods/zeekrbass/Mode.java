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

import android.media.AudioFormat;

/** The three workaround states offered by the control panel. */
enum Mode {
    OFF(0, "Off"),
    SURROUND_51(6, "5.1"),
    SURROUND_71(8, "7.1");

    final int channels;
    final String label;

    Mode(int channels, String label) {
        this.channels = channels;
        this.label = label;
    }

    int channelMask() {
        switch (this) {
            case SURROUND_51:
                // FL, FR, FC, LFE, BL, BR
                return AudioFormat.CHANNEL_OUT_5POINT1;
            case SURROUND_71:
                // FL, FR, FC, LFE, BL, BR, SL, SR
                return AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
            default:
                return AudioFormat.CHANNEL_INVALID;
        }
    }

    static Mode fromName(String name) {
        if (name == null) return OFF;
        try {
            return Mode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }
}
