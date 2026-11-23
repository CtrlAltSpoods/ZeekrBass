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

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.EnumMap;
import java.util.Map;

/** Settings / control screen. Everything here is also reachable from the floating panel. */
public class MainActivity extends Activity implements StatusBus.Listener {
    private Prefs prefs;
    private final Map<Mode, TextView> modeButtons = new EnumMap<>(Mode.class);
    private View statusDot;
    private TextView statusText;
    private TextView panelButton;
    private Switch ditherSwitch;
    private Switch bootSwitch;
    private LinearLayout permissionBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatusBus.addListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        StatusBus.removeListener(this);
        super.onPause();
    }

    @Override
    public void onStatusChanged() {
        refresh();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 32);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        col.addView(text("ZeekrBass", 30, Ui.TEXT, true));
        col.addView(text("Keeps a silent 5.1 or 7.1 stream open in the background so the car's audio "
                + "system stays in its multichannel mode. It never takes audio focus, so your music "
                + "keeps playing normally.", 16, Ui.TEXT_DIM, false));

        col.addView(section("Workaround stream"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final Mode m : new Mode[]{Mode.SURROUND_51, Mode.SURROUND_71, Mode.OFF}) {
            TextView b = Ui.pill(this, m.label, 20);
            b.setMinWidth(Ui.dp(this, 120));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (m == Mode.OFF && !StatusBus.serviceRunning) {
                        prefs.setMode(Mode.OFF); // nothing running, nothing to stop
                        return;
                    }
                    SilentStreamService.setMode(MainActivity.this, m);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 60));
            lp.setMargins(0, 0, Ui.dp(this, 12), 0);
            row.addView(b, lp);
            modeButtons.put(m, b);
        }
        col.addView(row);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, Ui.dp(this, 16), 0, 0);
        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 12));
        dotLp.setMargins(0, 0, Ui.dp(this, 10), 0);
        statusRow.addView(statusDot, dotLp);
        statusText = text("", 16, Ui.TEXT, false);
        statusText.setPadding(0, 0, 0, 0);
        statusRow.addView(statusText);
        col.addView(statusRow);

        ditherSwitch = toggle("Dither — send inaudible −96 dBFS noise instead of pure digital silence "
                + "(try this if pure silence doesn't hold the surround mode)");
        col.addView(ditherSwitch);
        bootSwitch = toggle("Restore the last mode and panel after the car restarts");
        col.addView(bootSwitch);

        col.addView(section("Floating panel"));
        panelButton = Ui.pill(this, "Show floating panel", 18);
        panelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel();
            }
        });
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 60));
        col.addView(panelButton, panelLp);
        col.addView(text("Drag the panel by its ⠿ handle. Tap the handle to shrink it to a small "
                + "badge, tap again to expand.", 14, Ui.TEXT_DIM, false));

        permissionBox = new LinearLayout(this);
        permissionBox.setOrientation(LinearLayout.VERTICAL);
        permissionBox.addView(section("Permission needed"));
        permissionBox.addView(text("The floating panel needs “Display over other apps”.", 16, Ui.TEXT, false));
        TextView grant = Ui.pill(this, "Open permission settings", 18);
        grant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings();
            }
        });
        permissionBox.addView(grant, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 60)));
        permissionBox.addView(text("If the car has no settings screen for it, grant it over ADB:", 14,
                Ui.TEXT_DIM, false));
        TextView adb = text("adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow",
                15, Ui.TEXT, false);
        adb.setTypeface(Typeface.MONOSPACE);
        adb.setTextIsSelectable(true);
        permissionBox.addView(adb);
        col.addView(permissionBox);

        col.addView(section("About"));
        col.addView(text("Unofficial community project, still in testing. ZeekrBass is not affiliated "
                + "with, endorsed by or connected to Zeekr, Geely or any of their affiliates. “Zeekr” "
                + "and “7X” are trademarks of their respective owners. Use at your own risk.",
                13, Ui.TEXT_DIM, false));
        col.addView(text("github.com/CtrlAltSpoods/ZeekrBass", 13, Ui.TEXT_DIM, false));

        return scroll;
    }

    private void refresh() {
        Mode mode = StatusBus.serviceRunning ? StatusBus.mode : Mode.OFF;
        for (Map.Entry<Mode, TextView> e : modeButtons.entrySet()) {
            Ui.stylePill(e.getValue(), e.getKey() == mode, Ui.ACCENT);
        }
        statusDot.setBackground(Ui.dot(Ui.statusColor(mode, StatusBus.healthy)));
        statusText.setText(StatusBus.serviceRunning ? StatusBus.detail : "Off");

        boolean canOverlay = Settings.canDrawOverlays(this);
        permissionBox.setVisibility(canOverlay ? View.GONE : View.VISIBLE);
        boolean panelShown = StatusBus.serviceRunning && StatusBus.overlayShown;
        panelButton.setText(panelShown ? "Hide floating panel" : "Show floating panel");
        Ui.stylePill(panelButton, panelShown, Ui.ACCENT);

        ditherSwitch.setOnCheckedChangeListener(null);
        ditherSwitch.setChecked(prefs.dither());
        ditherSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setDither(on);
                StatusBus.dither = on;
                if (StatusBus.serviceRunning) {
                    SilentStreamService.send(MainActivity.this,
                            SilentStreamService.intent(MainActivity.this, SilentStreamService.ACTION_SET_DITHER)
                                    .putExtra(SilentStreamService.EXTRA_ENABLED, on));
                }
            }
        });

        bootSwitch.setOnCheckedChangeListener(null);
        bootSwitch.setChecked(prefs.restoreOnBoot());
        bootSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setRestoreOnBoot(on);
            }
        });
    }

    private void togglePanel() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant “Display over other apps” first", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        boolean shown = StatusBus.serviceRunning && StatusBus.overlayShown;
        SilentStreamService.send(this, SilentStreamService.intent(this,
                shown ? SilentStreamService.ACTION_HIDE_PANEL : SilentStreamService.ACTION_SHOW_PANEL));
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "This head unit has no screen for that permission — use the ADB command",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(), 13, Ui.TEXT_DIM, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 10));
        return t;
    }

    private Switch toggle(String label) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        s.setSwitchPadding(Ui.dp(this, 24));
        return s;
    }
}
