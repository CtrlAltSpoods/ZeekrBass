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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.EnumMap;
import java.util.Map;

/** Settings / control screen. The common controls are also on the floating panel. */
public class MainActivity extends Activity implements StatusBus.Listener {
    private Prefs prefs;
    private UpmixConfig upmix;
    private EqConfig eq;
    private Switch eqSwitch;
    private Switch eqGlobalSwitch;
    private Switch eqAppsSwitch;
    private Switch eqLoudnessSwitch;
    private TextView eqStatusText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Mode, TextView> modeButtons = new EnumMap<>(Mode.class);
    private final Map<UpmixAlgorithm, TextView> algoButtons = new EnumMap<>(UpmixAlgorithm.class);
    private TextView layout51;
    private TextView layout71;
    private TextView algoDescription;
    private View statusDot;
    private TextView statusText;
    private TextView meterText;
    private TextView panelButton;
    private Switch ditherSwitch;
    private Switch bootSwitch;
    private Switch frontsSwitch;
    private LinearLayout permissionBox;
    private TextView effectsText;

    private final Runnable meterTick = new Runnable() {
        @Override
        public void run() {
            updateMeter();
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        upmix = UpmixConfig.get(this);
        eq = EqConfig.get(this);
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
        handler.post(meterTick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(meterTick);
        StatusBus.removeListener(this);
        super.onPause();
    }

    @Override
    public void onStatusChanged() {
        refresh();
    }

    // ------------------------------------------------------------------ layout

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
        col.addView(text("Silent 5.1/7.1 keeps the car's audio system in its multichannel mode. "
                + "Upmix also spreads the music from other apps across the centre, surround and "
                + "sub channels. Neither takes audio focus, so your music app keeps playing normally.",
                16, Ui.TEXT_DIM, false));

        // --- mode
        col.addView(section("Mode"));
        LinearLayout row = hRow();
        addModeButton(row, Mode.SURROUND_51, "Silent 5.1");
        addModeButton(row, Mode.SURROUND_71, "Silent 7.1");
        addModeButton(row, Mode.UPMIX, "Upmix");
        addModeButton(row, Mode.OFF, "Off");
        col.addView(row);

        LinearLayout statusRow = hRow();
        statusRow.setPadding(0, Ui.dp(this, 16), 0, 0);
        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 12));
        dotLp.setMargins(0, 0, Ui.dp(this, 10), 0);
        statusRow.addView(statusDot, dotLp);
        statusText = text("", 16, Ui.TEXT, false);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(statusRow);
        meterText = text("", 14, Ui.TEXT_DIM, false);
        meterText.setTypeface(Typeface.MONOSPACE);
        col.addView(meterText);

        // --- upmix settings
        col.addView(section("Upmix"));
        col.addView(label("Layout"));
        LinearLayout layoutRow = hRow();
        layout51 = pillIn(layoutRow, "5.1", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLayout(false);
            }
        });
        layout71 = pillIn(layoutRow, "7.1", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLayout(true);
            }
        });
        col.addView(layoutRow);

        col.addView(label("Processing"));
        LinearLayout algoRow = hRow();
        for (final UpmixAlgorithm a : UpmixAlgorithm.values()) {
            TextView b = pillIn(algoRow, a.label, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    upmix.algorithm = a;
                    upmix.save(MainActivity.this);
                    refresh();
                }
            });
            algoButtons.put(a, b);
        }
        col.addView(algoRow);
        algoDescription = text("", 14, Ui.TEXT_DIM, false);
        col.addView(algoDescription);

        col.addView(slider("Centre", UpmixConfig.MAX_DB_LEVEL, upmix.centerDb, new Level() {
            @Override
            public void set(int db) {
                upmix.centerDb = db;
            }
        }));
        col.addView(slider("Surrounds", UpmixConfig.MAX_DB_LEVEL, upmix.surroundDb, new Level() {
            @Override
            public void set(int db) {
                upmix.surroundDb = db;
            }
        }));
        col.addView(slider("Sub (below 80 Hz)", UpmixConfig.MAX_DB_LFE, upmix.lfeDb, new Level() {
            @Override
            public void set(int db) {
                upmix.lfeDb = db;
            }
        }));
        frontsSwitch = toggle("Also send the music to the front speakers. Leave this OFF: the music app "
                + "already plays on the fronts, so turning it on doubles them with a slight echo.");
        col.addView(frontsSwitch);
        col.addView(text("The front speakers are never high-passed. They stay full range, so the car's "
                + "own tuning is untouched.", 14, Ui.TEXT_DIM, false));

        // --- bass EQ
        col.addView(section("Bass EQ"));
        col.addView(text("Boosts bass with Android's own effects, attached the way system EQ apps do it. "
                + "No capture and no delay. It can run together with Silent 5.1.", 14, Ui.TEXT_DIM, false));
        eqSwitch = toggle("Bass EQ on");
        col.addView(eqSwitch);
        eqGlobalSwitch = toggle("Global: whole car output mix (\"legacy mode\"; depends on the car)");
        col.addView(eqGlobalSwitch);
        eqAppsSwitch = toggle("Music apps that announce their audio (Spotify, YouTube Music, Plexamp…)");
        col.addView(eqAppsSwitch);
        col.addView(rangeSlider("Bass boost", 0, EqConfig.MAX_BOOST_DB, eq.boostDb, new Level() {
            @Override
            public void set(int db) {
                eq.boostDb = db;
            }
        }, new Runnable() {
            @Override
            public void run() {
                saveEq();
            }
        }));
        eqLoudnessSwitch = toggle("Loudness: add up to +" + EqConfig.LOUDNESS_MAX_EXTRA_DB
                + " dB more bass as the volume goes down");
        col.addView(eqLoudnessSwitch);
        eqStatusText = text("", 14, Ui.TEXT, false);
        col.addView(eqStatusText);

        // --- silent stream / general
        col.addView(section("Silent stream"));
        ditherSwitch = toggle("Dither: send inaudible −96 dBFS noise instead of pure digital silence");
        col.addView(ditherSwitch);
        bootSwitch = toggle("Restore the last mode and panel after the car restarts");
        col.addView(bootSwitch);

        // --- panel
        col.addView(section("Floating panel"));
        panelButton = Ui.pill(this, "Show floating panel", 18);
        panelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel();
            }
        });
        col.addView(panelButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 60)));
        col.addView(text("Drag the panel by its ⠿ handle. Tap the handle to shrink it to a small "
                + "badge, and tap again to expand. In Upmix mode a second row lets you switch "
                + "processing and layout.", 14, Ui.TEXT_DIM, false));

        permissionBox = new LinearLayout(this);
        permissionBox.setOrientation(LinearLayout.VERTICAL);
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
        col.addView(permissionBox);

        // --- adb helpers
        col.addView(section("If the car hides permission screens"));
        col.addView(text("Run these once over ADB:", 14, Ui.TEXT_DIM, false));
        col.addView(code("adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow"));
        col.addView(code("adb shell pm grant " + getPackageName() + " android.permission.RECORD_AUDIO"));
        col.addView(text("Optional: approve audio capture automatically, so Upmix can start without a "
                + "prompt and come back after a restart:", 14, Ui.TEXT_DIM, false));
        col.addView(code("adb shell appops set " + getPackageName() + " PROJECT_MEDIA allow"));

        // --- effect lab
        col.addView(section("Effect Lab (the car's own effects)"));
        col.addView(text("Attaches the car's built-in sound processors to the whole-car output, the same "
                + "way the global Bass EQ works, so you can hear what each one does. Try SUMX first "
                + "with music playing. If it upmixes, you'll hear sound spread to the centre and rear "
                + "speakers. Off always puts things back the way they were.", 14, Ui.TEXT_DIM, false));
        TextView report = Ui.pill(this, "Audio report", 18);
        report.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                effectsText.setText("Reading Android's audio report…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String r = AudioReport.read(MainActivity.this);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                effectsText.setText(r);
                            }
                        });
                    }
                }, "zeekrbass-report").start();
            }
        });
        col.addView(report, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 60)));
        col.addView(text("Shows each output's channel count and which effects are running. Needs one "
                + "ADB command:", 14, Ui.TEXT_DIM, false));
        col.addView(code("adb shell pm grant " + getPackageName() + " android.permission.DUMP"));
        effectsText = text("", 13, Ui.TEXT, false);
        effectsText.setTypeface(Typeface.MONOSPACE);
        effectsText.setTextIsSelectable(true);
        col.addView(effectsText);
        for (EffectLab.Item item : EffectLab.get().items) col.addView(labRow(item));

        // --- about
        col.addView(section("About"));
        col.addView(text("Unofficial community project, still in testing. ZeekrBass is not affiliated "
                + "with, endorsed by or connected to Zeekr, Geely or any of their affiliates. “Zeekr” "
                + "and “7X” are trademarks of their respective owners. The processing modes are "
                + "ZeekrBass's own open algorithms, not licensed Dolby or DTS technology. "
                + "Use at your own risk.", 13, Ui.TEXT_DIM, false));
        col.addView(text("github.com/CtrlAltSpoods/ZeekrBass", 13, Ui.TEXT_DIM, false));

        return scroll;
    }

    private void addModeButton(LinearLayout row, final Mode m, String label) {
        TextView b = pillIn(row, label, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (m == Mode.OFF && !StatusBus.serviceRunning) {
                    prefs.setMode(Mode.OFF); // nothing running, nothing to stop
                    return;
                }
                ZeekrBassService.setMode(MainActivity.this, m);
            }
        });
        modeButtons.put(m, b);
    }

    private interface Level {
        void set(int db);
    }

    private View rangeSlider(final String name, final int min, final int max, int value,
                             final Level level, final Runnable onRelease) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 10), 0, 0);
        final TextView title = text(name + ": +" + value + " dB", 16, Ui.TEXT, false);
        box.addView(title);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, value - min));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = progress + min;
                level.set(v);
                title.setText(name + ": +" + v + " dB");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                onRelease.run();
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 48)));
        return box;
    }

    private View slider(final String name, final int maxDb, int value, final Level level) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 10), 0, 0);
        final TextView title = text(name + ": " + UpmixConfig.dbLabel(value), 16, Ui.TEXT, false);
        box.addView(title);
        SeekBar bar = new SeekBar(this);
        bar.setMax(maxDb - UpmixConfig.MIN_DB);
        bar.setProgress(Math.max(0, value - UpmixConfig.MIN_DB));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int db = progress + UpmixConfig.MIN_DB;
                level.set(db); // applied live by the DSP
                title.setText(name + ": " + UpmixConfig.dbLabel(db));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                upmix.save(MainActivity.this);
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 48)));
        return box;
    }

    // ------------------------------------------------------------------ state

    private void refresh() {
        Mode mode = StatusBus.serviceRunning ? StatusBus.mode : Mode.OFF;
        for (Map.Entry<Mode, TextView> e : modeButtons.entrySet()) {
            Ui.stylePill(e.getValue(), e.getKey() == mode,
                    e.getKey() == Mode.UPMIX ? Ui.ACCENT_UPMIX : Ui.ACCENT);
        }
        statusDot.setBackground(Ui.dot(Ui.statusColor(mode, StatusBus.healthy)));
        statusText.setText(StatusBus.serviceRunning ? StatusBus.detail : "Off");

        Ui.stylePill(layout51, !upmix.layout71, Ui.ACCENT_UPMIX);
        Ui.stylePill(layout71, upmix.layout71, Ui.ACCENT_UPMIX);
        for (Map.Entry<UpmixAlgorithm, TextView> e : algoButtons.entrySet()) {
            Ui.stylePill(e.getValue(), e.getKey() == upmix.algorithm, Ui.ACCENT_UPMIX);
        }
        algoDescription.setText(upmix.algorithm.description);

        boolean canOverlay = Settings.canDrawOverlays(this);
        permissionBox.setVisibility(canOverlay ? View.GONE : View.VISIBLE);
        boolean panelShown = StatusBus.serviceRunning && StatusBus.overlayShown;
        panelButton.setText(panelShown ? "Hide floating panel" : "Show floating panel");
        Ui.stylePill(panelButton, panelShown, Ui.ACCENT);

        bindSwitch(eqSwitch, eq.enabled, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                eq.enabled = on;
                saveEq();
            }
        });
        bindSwitch(eqGlobalSwitch, eq.global, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                eq.global = on;
                saveEq();
            }
        });
        bindSwitch(eqAppsSwitch, eq.perApp, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                eq.perApp = on;
                saveEq();
            }
        });
        bindSwitch(eqLoudnessSwitch, eq.loudness, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                eq.loudness = on;
                saveEq();
            }
        });
        eqStatusText.setText(eq.enabled ? StatusBus.eqStatus : "Bass EQ off");
        eqStatusText.setTextColor(!eq.enabled || StatusBus.eqHealthy ? Ui.TEXT : Ui.WARN);
        bindSwitch(frontsSwitch, upmix.driveFronts, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                upmix.driveFronts = on;
                upmix.save(MainActivity.this);
            }
        });
        bindSwitch(ditherSwitch, prefs.dither(), new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setDither(on);
                StatusBus.dither = on;
                if (StatusBus.serviceRunning) {
                    ZeekrBassService.send(MainActivity.this,
                            ZeekrBassService.intent(MainActivity.this, ZeekrBassService.ACTION_SET_DITHER)
                                    .putExtra(ZeekrBassService.EXTRA_ENABLED, on));
                }
            }
        });
        bindSwitch(bootSwitch, prefs.restoreOnBoot(), new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setRestoreOnBoot(on);
            }
        });
        updateMeter();
    }

    private void updateMeter() {
        boolean upmixing = StatusBus.serviceRunning && StatusBus.mode == Mode.UPMIX
                && StatusBus.inputPeakDb > -119f;
        if (!upmixing) {
            meterText.setText("");
            return;
        }
        float db = StatusBus.inputPeakDb;
        int bars = Math.max(0, Math.min(30, Math.round((db + 60f) / 2f)));
        StringBuilder sb = new StringBuilder("Input ");
        for (int i = 0; i < 30; i++) sb.append(i < bars ? '█' : '·');
        sb.append(String.format(" %5.1f dB   buffer %d ms", db, StatusBus.bufferedMs));
        meterText.setText(sb.toString());
    }

    // ------------------------------------------------------------------ bass EQ

    private void saveEq() {
        eq.save();
        if (eq.enabled || StatusBus.serviceRunning) {
            ZeekrBassService.send(this, ZeekrBassService.intent(this, ZeekrBassService.ACTION_EQ_SYNC));
        }
        refresh();
    }

    // ------------------------------------------------------------------ effect lab

    private View labRow(final EffectLab.Item item) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 18), 0, 0);
        box.addView(text(item.title(), 17, Ui.TEXT, true));
        final TextView state = text("", 14, Ui.TEXT_DIM, false);
        final TextView settings = text("", 13, Ui.TEXT, false);
        settings.setTypeface(Typeface.MONOSPACE);
        settings.setTextIsSelectable(true);
        final Runnable update = new Runnable() {
            @Override
            public void run() {
                state.setText(item.state);
                settings.setText(item.settings);
                settings.setVisibility(item.settings.isEmpty() ? View.GONE : View.VISIBLE);
            }
        };
        LinearLayout row = hRow();
        pillIn(row, "On", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EffectLab.get().turnOn(item);
                labChanged(update);
            }
        });
        pillIn(row, "Off", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EffectLab.get().turnOff(item);
                labChanged(update);
            }
        });
        pillIn(row, "Bypass", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (item.fx == null) {
                    item.state = "Tap On first. Bypass switches off an effect the car already runs.";
                } else {
                    EffectLab.get().bypass(item, !item.bypassed);
                }
                labChanged(update);
            }
        });
        pillIn(row, "Read settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EffectLab.get().readSettings(item);
                labChanged(update);
            }
        });
        box.addView(row);
        box.addView(state);
        box.addView(settings);
        update.run();
        return box;
    }

    private void labChanged(Runnable update) {
        update.run();
        if (EffectLab.get().anyAttached() || StatusBus.serviceRunning) {
            ZeekrBassService.send(this, ZeekrBassService.intent(this, ZeekrBassService.ACTION_LAB_SYNC));
        }
    }

    private void setLayout(boolean is71) {
        if (upmix.layout71 == is71) return;
        upmix.layout71 = is71;
        upmix.save(this);
        refresh();
        if (StatusBus.serviceRunning) {
            ZeekrBassService.send(this, ZeekrBassService.intent(this,
                    ZeekrBassService.ACTION_UPMIX_LAYOUT_CHANGED));
        }
    }

    private void togglePanel() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant “Display over other apps” first", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        boolean shown = StatusBus.serviceRunning && StatusBus.overlayShown;
        ZeekrBassService.send(this, ZeekrBassService.intent(this,
                shown ? ZeekrBassService.ACTION_HIDE_PANEL : ZeekrBassService.ACTION_SHOW_PANEL));
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "This head unit has no screen for that permission. Use the ADB command",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ small view helpers

    private static void bindSwitch(Switch s, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        s.setOnCheckedChangeListener(null);
        s.setChecked(checked);
        s.setOnCheckedChangeListener(l);
    }

    private LinearLayout hRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView pillIn(LinearLayout row, String label, View.OnClickListener l) {
        TextView b = Ui.pill(this, label, 18);
        b.setMinWidth(Ui.dp(this, 110));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 60));
        lp.setMargins(0, 0, Ui.dp(this, 12), 0);
        row.addView(b, lp);
        return b;
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

    private TextView label(String s) {
        TextView t = text(s, 15, Ui.TEXT, true);
        t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        return t;
    }

    private TextView code(String s) {
        TextView t = text(s, 14, Ui.TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
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
