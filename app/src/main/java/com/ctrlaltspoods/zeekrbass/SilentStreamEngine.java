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

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.Arrays;

/**
 * Plays an endless, silent multichannel PCM stream (5.1 or 7.1) on a background thread.
 *
 * The track is set up the way a video player outputs multichannel PCM: USAGE_MEDIA /
 * CONTENT_TYPE_MOVIE, 16-bit, 48 kHz, with a 5.1 or 7.1 channel mask. It deliberately does NOT
 * request audio focus, so it never pauses or ducks whatever else is playing.
 */
final class SilentStreamEngine {
    interface Callback {
        /** Called from the audio thread. */
        void onStreamStatus(Mode mode, String detail, boolean healthy);
    }

    private static final String TAG = "ZeekrBass";
    static final int SAMPLE_RATE = 48000;
    private static final int CHUNK_FRAMES = SAMPLE_RATE / 50; // 20 ms per write
    private static final long MAX_BACKOFF_MS = 10_000;

    private final Callback callback;
    private Thread worker;
    private Mode mode = Mode.OFF;
    private volatile boolean running;
    private volatile boolean dither;
    private volatile AudioTrack currentTrack;

    SilentStreamEngine(Callback callback) {
        this.callback = callback;
    }

    synchronized Mode mode() {
        return mode;
    }

    void setDither(boolean on) {
        dither = on;
    }

    synchronized void setMode(final Mode target) {
        boolean alive = worker != null && worker.isAlive();
        if (target == mode && (target == Mode.OFF || alive)) return;

        stopWorker();
        mode = target;
        if (target == Mode.OFF) {
            callback.onStreamStatus(Mode.OFF, "Off — no workaround stream", true);
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                streamLoop(target);
            }
        }, "ZeekrBass-" + target.label);
        worker.start();
    }

    synchronized void release() {
        stopWorker();
        mode = Mode.OFF;
    }

    private void stopWorker() {
        running = false;
        AudioTrack t = currentTrack;
        if (t != null) {
            // stop() wakes up a blocking write() so the thread can exit promptly.
            try {
                t.stop();
            } catch (Throwable ignored) {
            }
        }
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }

    private void streamLoop(Mode m) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        long backoff = 500;
        int noise = 0x2545F491; // xorshift state for optional dither

        while (running) {
            AudioTrack track = null;
            try {
                track = buildTrack(m);
                currentTrack = track;
                if (!running) break;

                short[] buf = new short[CHUNK_FRAMES * m.channels];
                boolean bufferHasNoise = false;
                boolean routeReported = false;

                track.play();
                callback.onStreamStatus(m, describe(m, null), true);
                backoff = 500;

                while (running) {
                    if (dither) {
                        // TPDF-ish values in {-1, 0, +1}: about -96 dBFS, inaudible.
                        for (int i = 0; i < buf.length; i++) {
                            noise ^= noise << 13;
                            noise ^= noise >>> 17;
                            noise ^= noise << 5;
                            buf[i] = (short) (((noise & 0xFF) % 3) - 1);
                        }
                        bufferHasNoise = true;
                    } else if (bufferHasNoise) {
                        Arrays.fill(buf, (short) 0);
                        bufferHasNoise = false;
                    }

                    int written = track.write(buf, 0, buf.length); // blocking
                    if (written < 0) {
                        throw new IllegalStateException("write() returned " + written);
                    }
                    if (written == 0 && running) {
                        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                        Thread.sleep(10);
                    }
                    if (!routeReported) {
                        AudioDeviceInfo dev = track.getRoutedDevice();
                        if (dev != null) {
                            routeReported = true;
                            callback.onStreamStatus(m, describe(m, dev), true);
                        }
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Throwable e) {
                if (!running) break;
                Log.w(TAG, "Silent " + m.label + " stream failed", e);
                callback.onStreamStatus(m, "Problem: " + e.getMessage() + " — retrying", false);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    break;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } finally {
                currentTrack = null;
                if (track != null) {
                    try {
                        track.stop();
                    } catch (Throwable ignored) {
                    }
                    track.release();
                }
            }
        }
    }

    private static String describe(Mode m, AudioDeviceInfo dev) {
        String s = "Streaming silent " + m.label + " · " + m.channels + " ch PCM @ 48 kHz";
        if (dev != null) {
            CharSequence name = dev.getProductName();
            s += " → " + (name == null || name.length() == 0 ? "device type " + dev.getType() : name);
        }
        return s;
    }

    private static AudioTrack buildTrack(Mode m) {
        int mask = m.channelMask();
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            throw new IllegalStateException(m.label + " PCM is not supported here (code " + minBuf + ")");
        }
        int frameBytes = m.channels * 2;
        int bufferBytes = Math.max(minBuf, (SAMPLE_RATE / 5) * frameBytes); // >= 200 ms

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(mask)
                .build();

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("the audio output rejected the " + m.label + " stream");
        }
        return track;
    }
}
