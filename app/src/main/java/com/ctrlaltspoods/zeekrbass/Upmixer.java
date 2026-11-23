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

/**
 * Stereo → 5.1 / 7.1 upmixer. Pure Java (no Android classes) so it can be tested off-device.
 *
 * Output channel order follows Android's channel masks:
 *   5.1: FL FR FC LFE BL BR
 *   7.1: FL FR FC LFE BL BR SL SR
 * In 5.1, Android's "back" pair is the surround pair. In 7.1, SL/SR are the side surrounds and
 * BL/BR the rears (fed a slightly delayed copy for depth).
 *
 * LFE: 4th-order Linkwitz-Riley low-pass at 80 Hz of the mono sum.
 * The main speakers get NO high-pass filter — they stay full range, as requested.
 */
final class Upmixer {
    static final float LFE_CROSSOVER_HZ = 80f;
    private static final float SURROUND_LP_HZ = 7000f;   // Cinema surround softening
    private static final float REAR_DELAY_MS = 12f;      // 7.1 rear pair
    private static final float STEER_TIME_S = 0.15f;     // Music steering smoothing

    private final boolean is71;
    private final int channels;
    private final float sampleRate;

    // LFE: two cascaded 2nd-order Butterworth low-passes = 4th-order Linkwitz-Riley.
    private final Biquad lfeA;
    private final Biquad lfeB;

    // Cinema surround one-pole low-pass state.
    private final float surLpCoef;
    private float surLpL;
    private float surLpR;

    // 7.1 rear delay lines.
    private final float[] rearDelayL;
    private final float[] rearDelayR;
    private int rearPos;

    // Music steering state.
    private double envL = 1e-9, envR = 1e-9, envM = 1e-9, envS = 1e-9;
    private float centerSteer = 0.6f;
    private float surroundSteer = 0.7f;
    private float centred = 0.5f;          // 0 = wide/one-sided mix … 1 = mono/centred

    Upmixer(boolean is71, int sampleRate) {
        this.is71 = is71;
        this.channels = is71 ? 8 : 6;
        this.sampleRate = sampleRate;
        lfeA = Biquad.butterworthLowPass(LFE_CROSSOVER_HZ, sampleRate);
        lfeB = Biquad.butterworthLowPass(LFE_CROSSOVER_HZ, sampleRate);
        surLpCoef = (float) (1.0 - Math.exp(-2.0 * Math.PI * SURROUND_LP_HZ / sampleRate));
        int delay = Math.max(1, Math.round(REAR_DELAY_MS * sampleRate / 1000f));
        rearDelayL = new float[delay];
        rearDelayR = new float[delay];
    }

    int channels() {
        return channels;
    }

    /**
     * @param in      interleaved stereo 16-bit samples
     * @param frames  number of stereo frames in {@code in}
     * @param out     interleaved output, at least frames * channels() long
     * @param gCenter linear centre gain
     * @param gSurr   linear surround gain
     * @param gLfe    linear sub gain
     * @param fronts  true = also send L/R to the front pair; false = leave fronts silent
     */
    void process(short[] in, int frames, short[] out, UpmixAlgorithm algo,
                 float gCenter, float gSurr, float gLfe, boolean fronts) {
        float cStart = centerSteer, sStart = surroundSteer, kStart = centred;
        float cEnd = cStart, sEnd = sStart, kEnd = kStart;
        if (algo == UpmixAlgorithm.MUSIC) {
            updateSteering(in, frames);
            cEnd = centerSteer;
            sEnd = surroundSteer;
            kEnd = centred;
        }
        float rampStep = frames > 1 ? 1f / (frames - 1) : 0f;

        int o = 0;
        for (int i = 0; i < frames; i++) {
            float l = in[2 * i] / 32768f;
            float r = in[2 * i + 1] / 32768f;
            float m = 0.5f * (l + r);

            float c, sl, sr;
            switch (algo) {
                case CINEMA: {
                    c = m;
                    // Matrix surround decode coefficients (cos/sin of the classic 60°/30° pair).
                    float rawL = 0.8718f * l - 0.4899f * r;
                    float rawR = 0.8718f * r - 0.4899f * l;
                    surLpL += surLpCoef * (rawL - surLpL);
                    surLpR += surLpCoef * (rawR - surLpR);
                    sl = surLpL;
                    sr = surLpR;
                    break;
                }
                case MUSIC: {
                    float t = i * rampStep;
                    float cg = cStart + (cEnd - cStart) * t;
                    float sg = sStart + (sEnd - sStart) * t;
                    float k = kStart + (kEnd - kStart) * t;
                    c = m * cg;
                    // Remove only the centred part of the mix from the surrounds, so vocals stay
                    // up front but hard-panned sounds don't leak into the opposite side.
                    sl = (l - k * m) * sg;
                    sr = (r - k * m) * sg;
                    break;
                }
                case MANUAL:
                default:
                    c = m;
                    sl = l;
                    sr = r;
                    break;
            }

            float lfe = (float) lfeB.process(lfeA.process(m));

            out[o++] = toPcm(fronts ? l : 0f);              // FL
            out[o++] = toPcm(fronts ? r : 0f);              // FR
            out[o++] = toPcm(c * gCenter);                  // FC
            out[o++] = toPcm(lfe * gLfe);                   // LFE
            if (is71) {
                float dl = rearDelayL[rearPos];
                float dr = rearDelayR[rearPos];
                rearDelayL[rearPos] = sl;
                rearDelayR[rearPos] = sr;
                rearPos = (rearPos + 1) % rearDelayL.length;
                out[o++] = toPcm(dl * gSurr);               // BL (rear, delayed)
                out[o++] = toPcm(dr * gSurr);               // BR
                out[o++] = toPcm(sl * gSurr);               // SL (side)
                out[o++] = toPcm(sr * gSurr);               // SR
            } else {
                out[o++] = toPcm(sl * gSurr);               // BL (= surround left in 5.1)
                out[o++] = toPcm(sr * gSurr);               // BR
            }
        }
    }

    /** Music mode: estimate how "centred" vs "wide" the mix is and smooth the gains. */
    private void updateSteering(short[] in, int frames) {
        double eL = 0, eR = 0, eM = 0, eS = 0;
        for (int i = 0; i < frames; i++) {
            double l = in[2 * i] / 32768.0;
            double r = in[2 * i + 1] / 32768.0;
            double m = 0.5 * (l + r);
            double s = 0.5 * (l - r);
            eL += l * l;
            eR += r * r;
            eM += m * m;
            eS += s * s;
        }
        double a = Math.exp(-frames / (sampleRate * STEER_TIME_S));
        envL = a * envL + (1 - a) * eL;
        envR = a * envR + (1 - a) * eR;
        envM = a * envM + (1 - a) * eM;
        envS = a * envS + (1 - a) * eS;

        double total = envM + envS;
        if (total < 1e-7) return; // silence: keep previous gains
        double cs = (envM - envS) / total;                       // +1 mono … 0 wide … -1 anti-phase
        double lr = Math.abs(envL - envR) / (envL + envR + 1e-12); // 0 balanced … 1 one-sided
        double c = clamp(cs, 0, 1) * (1 - 0.5 * lr);
        centred = (float) c;
        centerSteer = (float) (0.3 + 0.7 * c);
        surroundSteer = (float) (0.5 + 0.5 * clamp(1 - cs, 0, 1));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Soft clip above -2 dBFS instead of hard wrapping. */
    static short toPcm(float x) {
        final float knee = 0.8f;
        float a = Math.abs(x);
        if (a > knee) {
            float over = (a - knee) / (1f - knee);
            a = knee + (1f - knee) * (float) Math.tanh(over);
            x = x < 0 ? -a : a;
        }
        int v = Math.round(x * 32767f);
        if (v > 32767) v = 32767;
        if (v < -32768) v = -32768;
        return (short) v;
    }

    /** Transposed direct form II biquad in double precision (stable at 80 Hz / 48 kHz). */
    static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double z1, z2;

        private Biquad(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        static Biquad butterworthLowPass(double fc, double fs) {
            double w0 = 2 * Math.PI * fc / fs;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / (2 * Math.sqrt(0.5)); // Q = 1/√2
            double a0 = 1 + alpha;
            return new Biquad((1 - cos) / 2 / a0, (1 - cos) / a0, (1 - cos) / 2 / a0,
                    -2 * cos / a0, (1 - alpha) / a0);
        }

        double process(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }
}
