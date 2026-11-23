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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;

/**
 * Root upmix helper. Started with `app_process` as root (see {@link Root#helperCommand}); it is
 * NOT an Android component and doesn't use the app's resources.
 *
 * What it does:
 *  1. Opens a 5.1 / 7.1 output stream (the same kind of stream Plex uses for surround).
 *  2. Registers an audio routing policy that sends media playback (USAGE_MEDIA) from every other
 *     app to this process instead of the speakers ("loop back" without "render"). Its own audio
 *     is excluded from the rule, so it can't capture itself.
 *  3. Upmixes that stereo into all channels (fronts included) and plays it.
 *
 * Safety: it only ever touches USAGE_MEDIA; navigation, calls, chimes and alerts are untouched.
 * Nothing is written to system files. The policy is unregistered on any error or when stopped,
 * and Android drops it automatically if this process dies, so normal audio always comes back.
 */
public final class RootMain {
    private static final int SAMPLE_RATE = 48000;
    private static final int BLOCK = 256;
    private static final int TARGET_QUEUE = BLOCK * 6;

    private static File dir;
    private static volatile Object policy;
    private static volatile Method unregisterMethod;
    private static volatile AudioManager audioManager;

    private RootMain() {
    }

    @SuppressWarnings("deprecation") // prepareMainLooper(): we are our own process's main thread
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: RootMain <app files dir>");
            System.exit(2);
        }
        dir = new File(args[0]);
        log("Starting as uid " + Process.myUid() + ", pid " + Process.myPid());
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                unregisterPolicy("shutdown");
            }
        }));
        try {
            Looper.prepareMainLooper();
            final Context ctx = systemContext();
            log("Got system context");
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    int code = 0;
                    try {
                        runLoop(ctx);
                        status("stopped", "Stopped", -120f, 0);
                    } catch (Throwable t) {
                        code = 1;
                        log("FAILED: " + stack(t));
                        status("error", describe(t), -120f, 0);
                    } finally {
                        unregisterPolicy("exit");
                        log("Exiting");
                        System.exit(code);
                    }
                }
            }, "zeekrbass-root");
            worker.start();
            Looper.loop();
        } catch (Throwable t) {
            log("FAILED during startup: " + stack(t));
            status("error", "Startup: " + describe(t), -120f, 0);
            unregisterPolicy("startup failure");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ main loop

    private static void runLoop(Context ctx) throws Exception {
        audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) throw new IllegalStateException("no AudioManager");

        while (true) {
            Properties cfg = readConfig();
            if (!"1".equals(cfg.getProperty("running"))) return;
            boolean is71 = "1".equals(cfg.getProperty("layout71"));
            boolean restart = runSession(cfg, is71);
            if (!restart) return;
            log("Layout changed, restarting stream");
        }
    }

    /** @return true if the layout changed and the session should restart. */
    private static boolean runSession(Properties cfg, boolean is71) throws Exception {
        final int ch = is71 ? 8 : 6;
        final int mask = is71 ? AudioFormat.CHANNEL_OUT_7POINT1_SURROUND : AudioFormat.CHANNEL_OUT_5POINT1;
        final String layout = is71 ? "7.1" : "5.1";

        // 1) Output first, so we never take audio away without somewhere to send it.
        AudioTrack track = buildOutput(mask, ch);
        AudioRecord rec = null;
        try {
            short[] cushion = new short[TARGET_QUEUE * ch];
            track.write(cushion, 0, cushion.length);
            track.play();
            log("Output " + layout + " open");

            // 2) Route media from other apps to us.
            Object[] pm = registerPolicy();
            rec = (AudioRecord) pm[1];
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("policy capture did not start");
            }
            log("Policy registered; media now routed through ZeekrBass");

            Upmixer dsp = new Upmixer(is71, SAMPLE_RATE);
            short[] in = new short[BLOCK * 2];
            short[] out = new short[(BLOCK + 1) * ch];
            long written = TARGET_QUEUE;
            long played = 0;
            int lastHead = 0;
            float peak = 0f;
            long tick = SystemClock.uptimeMillis();
            Settings s = Settings.from(cfg);
            status("running", "Upmixing " + layout + " · " + s.algo.label, -120f, 0);

            while (true) {
                int n = rec.read(in, 0, in.length);
                if (n < 0) throw new IllegalStateException("capture read() returned " + n);
                if (n == 0) continue;
                int frames = n / 2;
                for (int i = 0; i < n; i++) {
                    float a = Math.abs(in[i]) / 32768f;
                    if (a > peak) peak = a;
                }
                dsp.process(in, frames, out, s.algo, s.center, s.surround, s.lfe, s.fronts);

                int head = track.getPlaybackHeadPosition();
                played += (head - lastHead) & 0xFFFFFFFFL;
                lastHead = head;
                long queued = written - played;
                int outFrames = frames;
                if (queued > TARGET_QUEUE + BLOCK && outFrames > 1) {
                    outFrames--;
                } else if (queued < TARGET_QUEUE - BLOCK / 2) {
                    System.arraycopy(out, (frames - 1) * ch, out, frames * ch, ch);
                    outFrames++;
                }
                int w = track.write(out, 0, outFrames * ch);
                if (w < 0) throw new IllegalStateException("output write() returned " + w);
                written += w / ch;

                long now = SystemClock.uptimeMillis();
                if (now - tick >= 300) {
                    tick = now;
                    Properties next = readConfig();
                    if (!"1".equals(next.getProperty("running"))) {
                        log("Stop requested");
                        return false;
                    }
                    if ("1".equals(next.getProperty("layout71")) != is71) return true;
                    s = Settings.from(next);
                    float db = peak > 0 ? (float) (20 * Math.log10(peak)) : -120f;
                    peak = 0f;
                    status("running", "Upmixing " + layout + " · " + s.algo.label, db,
                            (int) (queued * 1000 / SAMPLE_RATE));
                }
            }
        } finally {
            // Give the speakers back BEFORE closing our output.
            unregisterPolicy("session end");
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Throwable ignored) {
                }
                rec.release();
            }
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            track.release();
        }
    }

    private static final class Settings {
        UpmixAlgorithm algo;
        float center, surround, lfe;
        boolean fronts;

        static Settings from(Properties p) {
            Settings s = new Settings();
            s.algo = UpmixAlgorithm.fromName(p.getProperty("algorithm"));
            s.center = gain(intProp(p, "centerDb", -3));
            s.surround = gain(intProp(p, "surroundDb", -3));
            s.lfe = gain(intProp(p, "lfeDb", 0));
            s.fronts = !"0".equals(p.getProperty("fronts"));
            return s;
        }
    }

    // ------------------------------------------------------------------ audio setup

    private static AudioTrack buildOutput(int mask, int ch) {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException(ch + "-channel output not supported (" + min + ")");
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(mask)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(min, TARGET_QUEUE * 2 * ch * 2))
                .build();
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            throw new IllegalStateException("output rejected the " + ch + "-channel stream");
        }
        return t;
    }

    /**
     * Registers a LOOP_BACK-only AudioPolicy for USAGE_MEDIA, excluding our own uid.
     * These are system APIs, so they're reached by reflection.
     * @return {policy, AudioRecord sink}
     */
    private static Object[] registerPolicy() throws Exception {
        Class<?> ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> mixCls = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> policyCls = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");

        int matchUsage = ruleCls.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
        int matchUid = ruleCls.getField("RULE_MATCH_UID").getInt(null);
        int loopBack = mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null);

        Object rb = ruleBuilderCls.getConstructor().newInstance();
        ruleBuilderCls.getMethod("addMixRule", int.class, Object.class).invoke(rb, matchUsage,
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
        ruleBuilderCls.getMethod("excludeMixRule", int.class, Object.class).invoke(rb, matchUid,
                Integer.valueOf(Process.myUid()));
        try {
            // Also take apps that only allow capture by the system (Android 11+).
            ruleBuilderCls.getMethod("allowPrivilegedPlaybackCapture", boolean.class).invoke(rb, true);
        } catch (NoSuchMethodException ignored) {
        }
        Object rule = ruleBuilderCls.getMethod("build").invoke(rb);

        AudioFormat mixFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        Object mb = mixBuilderCls.getConstructor(ruleCls).newInstance(rule);
        mixBuilderCls.getMethod("setFormat", AudioFormat.class).invoke(mb, mixFormat);
        mixBuilderCls.getMethod("setRouteFlags", int.class).invoke(mb, loopBack);
        Object mix = mixBuilderCls.getMethod("build").invoke(mb);

        Object pb = policyBuilderCls.getConstructor(Context.class).newInstance(systemContextCached);
        policyBuilderCls.getMethod("addMix", mixCls).invoke(pb, mix);
        try {
            policyBuilderCls.getMethod("setLooper", Looper.class).invoke(pb, Looper.getMainLooper());
        } catch (NoSuchMethodException ignored) {
        }
        Object pol = policyBuilderCls.getMethod("build").invoke(pb);

        Method register = AudioManager.class.getMethod("registerAudioPolicy", policyCls);
        int res = (Integer) register.invoke(audioManager, pol);
        if (res != 0) throw new IllegalStateException("registerAudioPolicy returned " + res);
        unregisterMethod = AudioManager.class.getMethod("unregisterAudioPolicy", policyCls);
        policy = pol;

        AudioRecord rec = (AudioRecord) policyCls.getMethod("createAudioRecordSink", mixCls)
                .invoke(pol, mix);
        if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("policy capture could not be created");
        }
        return new Object[]{pol, rec};
    }

    private static synchronized void unregisterPolicy(String why) {
        Object p = policy;
        policy = null;
        if (p == null || unregisterMethod == null || audioManager == null) return;
        try {
            unregisterMethod.invoke(audioManager, p);
            log("Policy unregistered (" + why + "); normal audio restored");
        } catch (Throwable t) {
            log("Unregister failed (" + why + "): " + describe(t));
        }
    }

    private static Context systemContextCached;

    private static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        systemContextCached = (Context) at.getMethod("getSystemContext").invoke(thread);
        return systemContextCached;
    }

    // ------------------------------------------------------------------ files

    private static Properties readConfig() {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(new File(dir, Root.CONFIG_FILE))) {
            p.load(in);
        } catch (IOException e) {
            p.setProperty("running", "0"); // no config = stop
        }
        return p;
    }

    private static void status(String state, String detail, float inputDb, int bufferMs) {
        Properties p = new Properties();
        p.setProperty("state", state);
        p.setProperty("detail", detail);
        p.setProperty("inputDb", String.format(Locale.US, "%.1f", inputDb));
        p.setProperty("bufferMs", Integer.toString(bufferMs));
        p.setProperty("uid", Integer.toString(Process.myUid()));
        p.setProperty("updated", Long.toString(System.currentTimeMillis()));
        File tmp = new File(dir, Root.STATUS_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, null);
        } catch (IOException e) {
            return;
        }
        tmp.setReadable(true, false);
        tmp.renameTo(new File(dir, Root.STATUS_FILE));
    }

    private static synchronized void log(String msg) {
        File f = new File(dir, Root.LOG_FILE);
        if (f.length() > 64 * 1024) f.delete();
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(ts + "  " + msg + "\n");
        } catch (IOException ignored) {
        }
        f.setReadable(true, false);
        System.out.println(msg);
    }

    private static String describe(Throwable t) {
        while (t.getCause() != null && (t instanceof java.lang.reflect.InvocationTargetException
                || t.getMessage() == null)) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 3000 ? s.substring(0, 3000) : s;
    }

    private static int intProp(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float gain(int db) {
        return db <= UpmixConfig.MIN_DB ? 0f : (float) Math.pow(10.0, db / 20.0);
    }
}
