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
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Reads Android's own audio report (`dumpsys media.audio_flinger`). Needs the DUMP permission,
 * granted once over ADB. It shows each output's channel count and which effects are attached
 * and switched on. That tells us whether the car's output is multichannel and whether SUMX, AVC
 * and the others are already running. Blocking: call off the main thread.
 */
final class AudioReport {
    private AudioReport() {
    }

    static boolean hasDumpPermission(Context c) {
        return c.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED;
    }

    static String read(Context c) {
        if (!hasDumpPermission(c)) {
            return "Needs the DUMP permission. Run once over ADB:\n  adb shell pm grant "
                    + c.getPackageName() + " android.permission.DUMP";
        }
        StringBuilder all = new StringBuilder();
        try {
            Process p = new ProcessBuilder("dumpsys", "media.audio_flinger")
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && all.length() < 400_000) {
                    all.append(line).append('\n');
                }
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Couldn't read the audio report: " + e.getMessage();
        }
        return summarise(all.toString());
    }

    /** Keeps only the lines that matter: outputs, channel layouts and effect chains. */
    static String summarise(String dump) {
        StringBuilder out = new StringBuilder();
        boolean inEffects = false;
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            String l = line.toLowerCase();
            boolean header = l.startsWith("output thread") || l.startsWith("mixer thread")
                    || l.startsWith("direct thread") || l.startsWith("offload thread")
                    || l.startsWith("duplicating thread") || l.contains("thread name");
            if (header) {
                inEffects = false;
                out.append('\n').append(line).append('\n');
                continue;
            }
            if (l.startsWith("effect chains") || l.contains("effect chain")) inEffects = true;
            if (l.startsWith("tracks") || l.startsWith("local log") || l.startsWith("timestamp")) {
                inEffects = false;
            }
            boolean keep = l.startsWith("channel count") || l.startsWith("channel mask")
                    || l.startsWith("sample rate") || l.startsWith("output devices")
                    || l.startsWith("output device") || l.startsWith("standby:")
                    || l.startsWith("io handle") || l.startsWith("frame count")
                    || (inEffects && !line.isEmpty());
            if (keep) out.append("  ").append(line).append('\n');
            if (out.length() > 20_000) {
                out.append("… (trimmed)");
                break;
            }
        }
        String s = out.toString().trim();
        return s.isEmpty() ? "The report was empty or in an unexpected format." : s;
    }
}
