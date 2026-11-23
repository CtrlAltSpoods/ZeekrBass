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
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Tiny code-only UI kit so the app needs no layout XML or support libraries. */
final class Ui {
    static final int BG = 0xFF111316;
    static final int PANEL_BG = 0xEB1B1D22;
    static final int TEXT = 0xFFF3F4F6;
    static final int TEXT_DIM = 0xFF9CA3AF;
    static final int PILL_IDLE = 0x26FFFFFF;
    static final int ACCENT = 0xFF3D7CFF;
    static final int ACCENT_DITHER = 0xFF8B5CF6;
    static final int ACCENT_UPMIX = 0xFF10B981;
    static final int ACCENT_EQ = 0xFFF97316;
    static final int OK = 0xFF34D399;
    static final int WARN = 0xFFF59E0B;
    static final int IDLE = 0xFF6B7280;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView pill(Context c, String label, float textSp) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(c, 76));
        t.setMinHeight(dp(c, 52));
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        t.setClickable(true);
        t.setFocusable(false);
        stylePill(t, false, ACCENT);
        return t;
    }

    static void stylePill(TextView t, boolean selected, int accent) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(t.getContext(), 14));
        shape.setColor(selected ? accent : PILL_IDLE);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null));
        t.setTextColor(selected ? 0xFFFFFFFF : TEXT);
    }

    static GradientDrawable dot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static int statusColor(Mode mode, boolean healthy) {
        if (mode == Mode.OFF) return IDLE;
        return healthy ? OK : WARN;
    }
}
