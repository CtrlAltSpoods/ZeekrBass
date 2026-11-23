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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Root tools (app side). Nothing here changes system files: root is only used to
 *  - check what kind of root the car has,
 *  - grant ZeekrBass its own permissions, and
 *  - start/stop the root upmix helper ({@link RootMain}), a separate process that lives only in
 *    memory. If it stops or crashes, Android automatically restores normal audio routing.
 *
 * Blocking calls: run these off the main thread.
 */
final class Root {
    static final String HELPER_NAME = "zeekrbass_root";
    static final String CONFIG_FILE = "root_config.properties";
    static final String STATUS_FILE = "root_status.properties";
    static final String LOG_FILE = "root_log.txt";

    private Root() {
    }

    static final class Result {
        final int exit;
        final String out;

        Result(int exit, String out) {
            this.exit = exit;
            this.out = out;
        }

        boolean ok() {
            return exit == 0;
        }
    }

    /** Runs a command via su. exit = -1 if su isn't available or timed out. */
    static Result su(String command, long timeoutMs) {
        return exec(new String[]{"su", "-c", command}, timeoutMs);
    }

    static Result exec(String[] argv, long timeoutMs) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            p.getOutputStream().close();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] b = new byte[4096];
                    int n;
                    try {
                        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                    } catch (IOException ignored) {
                    }
                }
            });
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return new Result(-1, "timed out (a root prompt may be waiting on screen)");
            }
            reader.join(500);
            return new Result(p.exitValue(), buf.toString("UTF-8").trim());
        } catch (Exception e) {
            return new Result(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** A readable report of what root (if any) this head unit offers. */
    static String check(Context c) {
        StringBuilder r = new StringBuilder();
        r.append("Build type: ").append(prop("ro.build.type"))
                .append(" · debuggable: ").append(prop("ro.debuggable"))
                .append(" · secure: ").append(prop("ro.secure")).append('\n');
        r.append("Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        Result se = exec(new String[]{"getenforce"}, 3000);
        r.append("SELinux: ").append(se.out.isEmpty() ? "unknown" : se.out).append('\n');

        Result id = su("id", 15000);
        if (id.ok() && id.out.contains("uid=0")) {
            r.append("✅ su works for this app: ").append(id.out).append('\n');
            Result ver = su("su -v 2>/dev/null || true", 5000);
            if (!ver.out.isEmpty()) r.append("su version: ").append(ver.out).append('\n');
            Result magisk = su("command -v magisk >/dev/null && magisk -v || echo none", 5000);
            r.append("Magisk: ").append(magisk.out).append('\n');
        } else {
            r.append("❌ su did not work for this app (").append(id.exit).append("): ")
                    .append(id.out.isEmpty() ? "no output" : id.out).append('\n');
            r.append("If the car's root only works over ADB (e.g. `adb root`), use the ADB command "
                    + "shown below to start the root upmix instead.\n");
        }
        return r.toString().trim();
    }

    /** Grants ZeekrBass's own permissions so no prompts or ADB are needed. */
    static String grantPermissions(Context c) {
        String pkg = c.getPackageName();
        String[] cmds = {
                "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
                "pm grant " + pkg + " android.permission.RECORD_AUDIO",
                "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS",
                "appops set " + pkg + " PROJECT_MEDIA allow",
                "pm grant " + pkg + " android.permission.DUMP",
        };
        StringBuilder r = new StringBuilder();
        for (String cmd : cmds) {
            Result res = su(cmd, 15000);
            r.append(res.ok() ? "✅ " : "⚠️ ").append(cmd);
            if (!res.ok() && !res.out.isEmpty()) r.append("\n    ").append(res.out);
            r.append('\n');
        }
        return r.toString().trim();
    }

    /** Shell command that starts the helper. Works from `su -c` or an ADB root shell. */
    static String helperCommand(Context c) {
        String apk = c.getApplicationInfo().sourceDir;
        String dir = c.getFilesDir().getAbsolutePath();
        return "CLASSPATH=" + apk + " nohup app_process /system/bin --nice-name=" + HELPER_NAME
                + " " + RootMain.class.getName() + " " + dir + " >/dev/null 2>&1 &";
    }

    static String adbCommand(Context c) {
        return "adb shell \"su 0 sh -c '" + helperCommand(c) + "'\"";
    }

    static String startHelper(Context c) {
        writeConfig(c, true);
        su("pkill -f " + HELPER_NAME + " ; true", 5000);
        Result r = su(helperCommand(c), 15000);
        return r.ok() ? "Root upmix helper started" : "Couldn't start via su (" + r.out
                + "). Try the ADB command instead.";
    }

    static void stopHelper(Context c) {
        writeConfig(c, false); // the helper exits on its own within a second
        new Prefs(c).setRootUpmix(false);
    }

    static void killHelper() {
        su("pkill -f " + HELPER_NAME + " ; true", 5000);
    }

    /** Writes the settings the helper polls. Called whenever upmix settings change. */
    static void writeConfig(Context c, Boolean running) {
        UpmixConfig u = UpmixConfig.get(c);
        Prefs prefs = new Prefs(c);
        boolean run = running != null ? running : prefs.rootUpmix();
        if (running != null) prefs.setRootUpmix(running);
        Properties p = new Properties();
        p.setProperty("running", run ? "1" : "0");
        p.setProperty("layout71", u.layout71 ? "1" : "0");
        p.setProperty("algorithm", u.algorithm.name());
        p.setProperty("centerDb", Integer.toString(u.centerDb));
        p.setProperty("surroundDb", Integer.toString(u.surroundDb));
        p.setProperty("lfeDb", Integer.toString(u.lfeDb));
        // The helper takes the music away from the speakers, so it must drive the fronts itself.
        p.setProperty("fronts", "1");
        File f = new File(c.getFilesDir(), CONFIG_FILE);
        File tmp = new File(c.getFilesDir(), CONFIG_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "ZeekrBass root helper settings");
        } catch (IOException ignored) {
            return;
        }
        tmp.setReadable(true, false);
        tmp.renameTo(f);
    }

    static Properties readStatus(Context c) {
        Properties p = new Properties();
        File f = new File(c.getFilesDir(), STATUS_FILE);
        if (!f.exists()) return p;
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException ignored) {
        }
        return p;
    }

    static String tailLog(Context c, int maxChars) {
        File f = new File(c.getFilesDir(), LOG_FILE);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            long skip = Math.max(0, f.length() - maxChars);
            if (skip > 0) in.skip(skip);
            byte[] b = new byte[(int) Math.min(maxChars, f.length())];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String prop(String name) {
        Result r = exec(new String[]{"getprop", name}, 3000);
        return r.out.isEmpty() ? "?" : r.out;
    }
}
