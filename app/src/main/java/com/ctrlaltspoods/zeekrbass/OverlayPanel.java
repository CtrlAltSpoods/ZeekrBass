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
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.Map;

/**
 * Floating, draggable control panel drawn over other apps.
 *
 * [⠿] ● [5.1] [7.1] [Off] [Dither]
 *
 * Drag the ⠿ handle to move it; tap the handle to collapse it down to "⠿ ● 5.1".
 */
final class OverlayPanel {
    interface Actions {
        void onModeSelected(Mode mode);

        void onDitherToggled();
    }

    private static final String TAG = "ZeekrBass";

    private final Context ctx;
    private final WindowManager wm;
    private final Prefs prefs;
    private final Actions actions;
    private final Map<Mode, TextView> modeButtons = new EnumMap<>(Mode.class);

    private LinearLayout root;
    private LinearLayout controls;
    private TextView compactLabel;
    private TextView ditherButton;
    private View dot;
    private WindowManager.LayoutParams lp;
    private boolean shown;

    OverlayPanel(Context ctx, Prefs prefs, Actions actions) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = prefs;
        this.actions = actions;
    }

    boolean isShown() {
        return shown;
    }

    boolean show() {
        if (shown) return true;
        if (!Settings.canDrawOverlays(ctx)) return false;
        if (root == null) build();

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.overlayX(Ui.dp(ctx, 32));
        lp.y = prefs.overlayY(Ui.dp(ctx, 140));
        lp.setTitle("ZeekrBass panel");

        try {
            wm.addView(root, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not add overlay", e);
            return false;
        }
        shown = true;
        render();
        root.post(new Runnable() {
            @Override
            public void run() {
                clampToScreen();
                updateLayout();
            }
        });
        return true;
    }

    void hide() {
        if (!shown) return;
        try {
            wm.removeView(root);
        } catch (RuntimeException ignored) {
        }
        shown = false;
    }

    /** Refreshes button highlights and the status dot from {@link StatusBus}. */
    void render() {
        if (root == null) return;
        Mode mode = StatusBus.mode;
        for (Map.Entry<Mode, TextView> e : modeButtons.entrySet()) {
            Ui.stylePill(e.getValue(), e.getKey() == mode, Ui.ACCENT);
        }
        Ui.stylePill(ditherButton, StatusBus.dither, Ui.ACCENT_DITHER);
        dot.setBackground(Ui.dot(Ui.statusColor(mode, StatusBus.healthy)));
        compactLabel.setText(mode == Mode.OFF ? "Off" : mode.label);
    }

    private void build() {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.PANEL_BG);
        bg.setCornerRadius(Ui.dp(ctx, 20));
        bg.setStroke(Ui.dp(ctx, 1), 0x33FFFFFF);
        root.setBackground(bg);

        TextView handle = new TextView(ctx);
        handle.setText("⠿");
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        handle.setTextColor(Ui.TEXT_DIM);
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription("Drag to move. Tap to collapse or expand.");
        root.addView(handle, new LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 56)));

        dot = new View(ctx);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(Ui.dp(ctx, 10), Ui.dp(ctx, 10));
        dotLp.setMargins(0, 0, Ui.dp(ctx, 6), 0);
        root.addView(dot, dotLp);

        compactLabel = new TextView(ctx);
        compactLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        compactLabel.setTextColor(Ui.TEXT);
        compactLabel.setGravity(Gravity.CENTER);
        compactLabel.setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 8), 0);
        root.addView(compactLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 56)));

        controls = new LinearLayout(ctx);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        for (final Mode m : new Mode[]{Mode.SURROUND_51, Mode.SURROUND_71, Mode.OFF}) {
            TextView b = Ui.pill(ctx, m.label, 18);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    actions.onModeSelected(m);
                }
            });
            controls.addView(b, buttonLp());
            modeButtons.put(m, b);
        }
        ditherButton = Ui.pill(ctx, "Dither", 14);
        ditherButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.onDitherToggled();
            }
        });
        LinearLayout.LayoutParams ditherLp = buttonLp();
        ditherLp.setMargins(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4), 0);
        controls.addView(ditherButton, ditherLp);
        root.addView(controls);

        DragListener drag = new DragListener();
        handle.setOnTouchListener(drag);
        compactLabel.setOnTouchListener(drag);

        applyCollapsed(prefs.collapsed());
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 52));
        p.setMargins(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), 0);
        return p;
    }

    private void applyCollapsed(boolean collapsed) {
        controls.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        compactLabel.setVisibility(collapsed ? View.VISIBLE : View.GONE);
    }

    private void toggleCollapsed() {
        boolean collapsed = !prefs.collapsed();
        prefs.setCollapsed(collapsed);
        applyCollapsed(collapsed);
        root.post(new Runnable() {
            @Override
            public void run() {
                clampToScreen();
                updateLayout();
            }
        });
    }

    private void clampToScreen() {
        if (lp == null || root == null) return;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int maxX = Math.max(0, dm.widthPixels - root.getWidth());
        int maxY = Math.max(0, dm.heightPixels - root.getHeight());
        lp.x = Math.max(0, Math.min(lp.x, maxX));
        lp.y = Math.max(0, Math.min(lp.y, maxY));
    }

    private void updateLayout() {
        if (!shown) return;
        try {
            wm.updateViewLayout(root, lp);
        } catch (RuntimeException ignored) {
        }
    }

    private final class DragListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (lp == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && dx * dx + dy * dy > (float) slop * slop) dragging = true;
                    if (dragging) {
                        lp.x = startX + Math.round(dx);
                        lp.y = startY + Math.round(dy);
                        clampToScreen();
                        updateLayout();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        prefs.setOverlayPosition(lp.x, lp.y);
                    } else {
                        v.performClick();
                        toggleCollapsed();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) prefs.setOverlayPosition(lp.x, lp.y);
                    return true;
                default:
                    return false;
            }
        }
    }
}
