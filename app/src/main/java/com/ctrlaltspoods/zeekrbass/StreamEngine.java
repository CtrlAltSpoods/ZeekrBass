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
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;

/**
 * Runs one audio job at a time on a background thread:
 *  - SURROUND_51 / SURROUND_71: an endless silent multichannel stream (the original workaround)
 *  - UPMIX: captures other apps' stereo playback and plays it back spread over 5.1 / 7.1.
 *
 * Neither job requests audio focus, so other apps keep playing. Our own output is marked as
 * not capturable so the upmixer never hears itself.
 */
final class StreamEngine {
    interface Callback {
        /** Called from the audio thread. {@code generation} identifies which job reported. */
        void onStreamStatus(int generation, String detail, boolean healthy);

        /** Capture can't continue (e.g. permission revoked). Called from the audio thread. */
        void onCaptureFailed(int generation, String reason);
    }

    private static final String TAG = "ZeekrBass";
    static final int SAMPLE_RATE = 48000;
    private static final int SILENT_CHUNK_FRAMES = SAMPLE_RATE / 50; // 20 ms
    private static final int UPMIX_BLOCK_FRAMES = 256;               // ~5.3 ms
    private static final int UPMIX_TARGET_QUEUE = UPMIX_BLOCK_FRAMES * 6; // ~32 ms output cushion
    private static final long MAX_BACKOFF_MS = 10_000;
    private static final float SILENCE_DB = -70f;
    private static final long NO_INPUT_WARNING_MS = 6_000;

    private final Callback callback;
    private final UpmixConfig config;
    private Thread worker;
    private Mode mode = Mode.OFF;
    private int generation;
    private volatile boolean running;
    private volatile boolean dither;
    private volatile AudioTrack currentTrack;
    private volatile AudioRecord currentRecord;

    StreamEngine(Callback callback, UpmixConfig config) {
        this.callback = callback;
        this.config = config;
    }

    synchronized Mode mode() {
        return mode;
    }

    synchronized int generation() {
        return generation;
    }

    void setDither(boolean on) {
        dither = on;
    }

    /** Stops whatever is running and starts {@code target}. UPMIX needs a live projection. */
    synchronized void start(final Mode target, final MediaProjection projection) {
        stopWorker();
        mode = target;
        final int gen = ++generation;
        if (target == Mode.OFF) {
            callback.onStreamStatus(gen, "Off", true);
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                if (target == Mode.UPMIX) upmixLoop(gen, projection);
                else silentLoop(gen, target);
            }
        }, "ZeekrBass-" + target.label);
        worker.start();
    }

    synchronized void release() {
        stopWorker();
        mode = Mode.OFF;
        generation++;
    }

    private void stopWorker() {
        running = false;
        AudioRecord rec = currentRecord;
        if (rec != null) {
            try {
                rec.stop(); // unblocks read()
            } catch (Throwable ignored) {
            }
        }
        AudioTrack t = currentTrack;
        if (t != null) {
            try {
                t.stop(); // unblocks write()
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
        StatusBus.inputPeakDb = -120f;
        StatusBus.bufferedMs = 0;
    }

    // ------------------------------------------------------------------ silent keep-alive

    private void silentLoop(int gen, Mode m) {
        long backoff = 500;
        int noise = 0x2545F491;
        while (running) {
            AudioTrack track = null;
            try {
                track = buildTrack(m.channelMask(), m.channels, SAMPLE_RATE / 5, false);
                currentTrack = track;
                if (!running) break;

                short[] buf = new short[SILENT_CHUNK_FRAMES * m.channels];
                boolean bufferHasNoise = false;
                boolean routeReported = false;
                track.play();
                callback.onStreamStatus(gen, "Streaming silent " + m.label + " · " + m.channels
                        + " ch PCM @ 48 kHz", true);
                backoff = 500;

                while (running) {
                    if (dither) {
                        for (int i = 0; i < buf.length; i++) {
                            noise ^= noise << 13;
                            noise ^= noise >>> 17;
                            noise ^= noise << 5;
                            buf[i] = (short) (((noise & 0xFF) % 3) - 1); // ≈ -96 dBFS
                        }
                        bufferHasNoise = true;
                    } else if (bufferHasNoise) {
                        Arrays.fill(buf, (short) 0);
                        bufferHasNoise = false;
                    }
                    int written = track.write(buf, 0, buf.length);
                    if (written < 0) throw new IllegalStateException("write() returned " + written);
                    if (written == 0 && running) {
                        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                        Thread.sleep(10);
                    }
                    if (!routeReported) {
                        String route = routeName(track);
                        if (route != null) {
                            routeReported = true;
                            callback.onStreamStatus(gen, "Streaming silent " + m.label + " · "
                                    + m.channels + " ch PCM @ 48 kHz → " + route, true);
                        }
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Throwable e) {
                if (!running) break;
                Log.w(TAG, "Silent " + m.label + " stream failed", e);
                callback.onStreamStatus(gen, "Problem: " + e.getMessage() + " — retrying", false);
                if (!sleepQuietly(backoff)) break;
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } finally {
                currentTrack = null;
                releaseTrack(track);
            }
        }
    }

    // ------------------------------------------------------------------ upmix

    private void upmixLoop(int gen, MediaProjection projection) {
        if (Build.VERSION.SDK_INT < 29 || projection == null) {
            callback.onCaptureFailed(gen, "Audio capture needs Android 10 or newer");
            return;
        }
        final boolean is71 = config.layout71;
        final int ch = is71 ? 8 : 6;
        final int mask = config.channelMask();
        final String layout = is71 ? "7.1" : "5.1";
        long backoff = 500;
        int captureFailures = 0;

        while (running) {
            AudioRecord rec = null;
            AudioTrack track = null;
            try {
                try {
                    rec = buildCapture(projection);
                } catch (RuntimeException e) {
                    if (++captureFailures >= 3) {
                        callback.onCaptureFailed(gen, "Can't capture audio: " + e.getMessage());
                        return;
                    }
                    throw e;
                }
                captureFailures = 0;
                currentRecord = rec;
                track = buildTrack(mask, ch, UPMIX_TARGET_QUEUE * 2, true);
                currentTrack = track;
                if (!running) break;

                Upmixer dsp = new Upmixer(is71, SAMPLE_RATE);
                short[] in = new short[UPMIX_BLOCK_FRAMES * 2];
                short[] out = new short[(UPMIX_BLOCK_FRAMES + 1) * ch];

                rec.startRecording();
                if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("capture did not start");
                }
                // Pre-fill a small cushion of silence, then start playback.
                short[] cushion = new short[UPMIX_TARGET_QUEUE * ch];
                track.write(cushion, 0, cushion.length);
                long written = UPMIX_TARGET_QUEUE;
                long played = 0;
                int lastHead = 0;
                track.play();

                String base = "Upmixing to " + layout;
                String route = null;
                callback.onStreamStatus(gen, base + " · " + config.algorithm.label, true);
                backoff = 500;

                float peak = 0f;
                long meterAt = SystemClock.uptimeMillis();
                long lastSoundAt = SystemClock.uptimeMillis();
                boolean warnedNoInput = false;
                UpmixAlgorithm lastAlgo = config.algorithm;

                while (running) {
                    int n = rec.read(in, 0, in.length);
                    if (n < 0) throw new IllegalStateException("capture read() returned " + n);
                    if (n == 0) continue;
                    int frames = n / 2;

                    for (int i = 0; i < n; i++) {
                        float a = Math.abs(in[i]) / 32768f;
                        if (a > peak) peak = a;
                    }

                    UpmixAlgorithm algo = config.algorithm;
                    dsp.process(in, frames, out, algo,
                            UpmixConfig.gain(config.centerDb),
                            UpmixConfig.gain(config.surroundDb),
                            UpmixConfig.gain(config.lfeDb),
                            config.driveFronts);

                    // Keep the output queue near its target so the capture and car clocks
                    // can't drift apart: drop or repeat one frame per block when needed.
                    int head = track.getPlaybackHeadPosition();
                    played += (head - lastHead) & 0xFFFFFFFFL;
                    lastHead = head;
                    long queued = written - played;
                    int outFrames = frames;
                    if (queued > UPMIX_TARGET_QUEUE + UPMIX_BLOCK_FRAMES && outFrames > 1) {
                        outFrames--;
                    } else if (queued < UPMIX_TARGET_QUEUE - UPMIX_BLOCK_FRAMES / 2) {
                        System.arraycopy(out, (frames - 1) * ch, out, frames * ch, ch);
                        outFrames++;
                    }

                    int w = track.write(out, 0, outFrames * ch);
                    if (w < 0) throw new IllegalStateException("write() returned " + w);
                    written += w / ch;

                    long now = SystemClock.uptimeMillis();
                    if (now - meterAt >= 250) {
                        float db = peak > 0 ? (float) (20 * Math.log10(peak)) : -120f;
                        StatusBus.inputPeakDb = db;
                        StatusBus.bufferedMs = (int) (queued * 1000 / SAMPLE_RATE);
                        if (db > SILENCE_DB) lastSoundAt = now;
                        peak = 0f;
                        meterAt = now;

                        boolean newRoute = false;
                        if (route == null) {
                            route = routeName(track);
                            newRoute = route != null;
                        }
                        boolean silentTooLong = now - lastSoundAt > NO_INPUT_WARNING_MS;
                        if (silentTooLong != warnedNoInput || algo != lastAlgo || newRoute) {
                            warnedNoInput = silentTooLong;
                            String msg = base + " · " + algo.label + (silentTooLong
                                    ? " · no audio captured: nothing is playing, or the playing "
                                    + "app blocks capture"
                                    : (route != null ? " → " + route : ""));
                            callback.onStreamStatus(gen, msg, !silentTooLong);
                        }
                        lastAlgo = algo;
                    }
                }
            } catch (Throwable e) {
                if (!running) break;
                Log.w(TAG, "Upmix failed", e);
                callback.onStreamStatus(gen, "Problem: " + e.getMessage() + " — retrying", false);
                if (!sleepQuietly(backoff)) break;
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } finally {
                currentRecord = null;
                currentTrack = null;
                if (rec != null) {
                    try {
                        rec.stop();
                    } catch (Throwable ignored) {
                    }
                    rec.release();
                }
                releaseTrack(track);
            }
        }
    }

    private static AudioRecord buildCapture(MediaProjection projection) {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("needs Android 10+");
        AudioPlaybackCaptureConfiguration capture =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .excludeUid(Process.myUid())
                        .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min, UPMIX_BLOCK_FRAMES * 4 * 8);
        AudioRecord rec = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .setAudioPlaybackCaptureConfig(capture)
                .build();
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            throw new IllegalStateException("capture could not be initialised");
        }
        return rec;
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * Multichannel PCM track, set up like a video player's surround output: USAGE_MEDIA /
     * CONTENT_TYPE_MOVIE, 16-bit, 48 kHz.
     */
    private static AudioTrack buildTrack(int mask, int channels, int minFrames, boolean noCapture) {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            throw new IllegalStateException(channels + "-channel PCM is not supported here (code "
                    + minBuf + ")");
        }
        int bufferBytes = Math.max(minBuf, minFrames * channels * 2);

        AudioAttributes.Builder attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE);
        if (noCapture && Build.VERSION.SDK_INT >= 29) {
            attrs.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE);
        }
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrs.build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(mask)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("the audio output rejected the " + channels
                    + "-channel stream");
        }
        return track;
    }

    private static String routeName(AudioTrack track) {
        AudioDeviceInfo dev = track.getRoutedDevice();
        if (dev == null) return null;
        CharSequence name = dev.getProductName();
        return name == null || name.length() == 0 ? "device type " + dev.getType() : name.toString();
    }

    private static void releaseTrack(AudioTrack track) {
        if (track == null) return;
        try {
            track.stop();
        } catch (Throwable ignored) {
        }
        track.release();
    }

    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
