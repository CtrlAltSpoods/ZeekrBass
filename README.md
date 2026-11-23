# ZeekrBass

> **Unofficial community project, still in testing.**
> ZeekrBass is **not affiliated with, endorsed by, sponsored by or otherwise connected to Zeekr,
> Zeekr Intelligent Technology, Geely or any of their affiliates.** See the [Disclaimer](#disclaimer).

An experimental Android app that tries to work around a bass bug in the **Zeekr 7X** audio system.

## The problem

On the 7X, bass is heavily limited and cuts out at low volume. The bug goes away while full 5.1 or 7.1
audio is playing, for example a surround movie in a sideloaded Plex. Then the bass sounds the way it should.

## The idea

If the audio system only switches into its full-bass mode while a multichannel stream is active,
keeping a **silent** 5.1 or 7.1 stream open in the background might keep it in that mode for
everything else: Spotify, radio, podcasts and so on.

**This is a theory under test. It's not a confirmed fix.** Results so far are in [Testing status](#testing-status).

First result: silent 5.1 *does* change the sound. But stereo music still only plays on the front
speakers, because a stereo song has only two channels. That's what **Upmix** (v0.2) is for.

## What the app does

- Plays an endless silent PCM stream: **5.1** (6 ch) or **7.1** (8 ch), 16-bit / 48 kHz,
  flagged as movie media. That's the same kind of stream a video player sends for surround audio.
- **Never takes audio focus**, so it won't pause, duck or interrupt your music.
- No media session, so it won't take over the steering-wheel buttons or the "now playing" display.
- Runs as a foreground service with a quiet notification (5.1 / 7.1 / Off / panel buttons).
- **Floating control panel** over every app: `⠿ ● [5.1] [7.1] [Off] [Dither]`
  - Drag the `⠿` handle to move it. Its position is saved.
  - Tap the handle to collapse it to a small badge (`⠿ ● 5.1`), and tap again to expand.
  - Dot colours: green = streaming, amber = problem/retrying, grey = off.
- **Dither** (optional): sends inaudible ±1-bit noise (about −96 dBFS) instead of pure digital zeros,
  in case the DSP ignores channels that are completely silent.
- Optionally restores the last mode and the panel after the car restarts.

## Upmix (v0.2, experimental)

Upmix works like the surround modes on a home-theatre receiver. It captures the stereo audio
other apps are playing, and plays it back spread across the centre, surround and sub channels as a
5.1 or 7.1 stream.

```
   music app (stereo) ──────────────────────────────► front L / R   (untouched, as before)
         │
         └─ captured by ZeekrBass ─► upmixer ─► centre · surrounds · (7.1 rears) · sub < 80 Hz
```

- **The music app keeps playing on the front speakers as normal.** ZeekrBass adds the other
  channels. A sideloaded app can't silence another app's audio, so doing it this way avoids doubling
  the fronts. There's an option to send to the fronts too, for testing only.
- **Sub:** 4th-order (Linkwitz-Riley) low-pass at **80 Hz** of the mono sum. **No high-pass on any
  speaker.** The mains stay full range, so the car's own 5.1 tuning is untouched.
- **Processing modes:**
  - **Cinema**: fixed matrix decode, in the style of classic cinema surround processors. The
    surrounds bring out width and room sound, softened above 7 kHz. In 7.1, the rear pair gets
    a 12 ms delayed copy for depth.
  - **Music**: steered upmix. It tracks the mix in real time, keeps centred vocals in the centre
    speaker, and sends wide or ambient sound to the surrounds without leaking hard-panned sounds
    to the other side.
  - **Manual**: plain split, no processing. Centre = L+R, left surrounds = L, right surrounds = R,
    sub = everything below 80 Hz.
- Centre, surround and sub levels are adjustable live (−30 dB/off … +6 dB, sub up to +12 dB).
- In Upmix mode, the floating panel shows a second row: `[Cinema] [Music] [Manual] [Layout 5.1]`.

### Results so far

The capture-based Upmix didn't work in the car. v0.3 added Bass EQ, and v0.4 adds Effect Lab.

## Bass EQ (v0.3, experimental)

A bass boost made from Android's own audio effects, attached the same way system-wide EQ apps
do it. No capture and no added delay. It can run together with Silent 5.1.

- **Global (whole car)**: attaches to the global output mix (often called "legacy mode" in EQ apps).
  Android has deprecated this, so whether it works depends on the car's audio framework. The
  status line shows whether the car accepted it.
- **Music apps**: attaches to every music app that announces its audio session (Spotify, YouTube
  Music, Plexamp and most players do).
- Curve: bass shelf (full boost below 60 Hz, half up to 150 Hz, flat above), 0 to +12 dB.
- **Loudness**: adds up to +6 dB more bass as the media volume goes down, aimed at the
  "no bass at low volume" bug. The status line shows the volume ZeekrBass sees. If it never
  changes when you turn the volume down, the car handles volume outside Android and Loudness
  won't follow it.
- A limiter stops the boost from clipping.
- There's a **Bass EQ** button on the floating panel.

## Effect Lab (v0.4, experimental)

The car's Android system includes its own sound processors, including several from Qualcomm:
**SUMX**, **AVC**, **Bass-Mid-Treble** and **Fade-n-Balance**. Effect Lab attaches each one to the
whole-car output, the same way the global Bass EQ does, so you can hear what it does:

- **On / Off**: attach and enable, or detach. Off always restores things exactly as they were.
- **Bypass**: if the car already runs that effect, switches it off temporarily (only if Android
  gives ZeekrBass control of it).
- **Read settings**: reads (never writes) the effect's first 16 parameters.
- **Audio report**: Android's own report of each output's channel count and the effects running
  on it. Needs `adb shell pm grant com.ctrlaltspoods.zeekrbass android.permission.DUMP` once.

Why this matters for upmixing: Android's standard effects (like the one Bass EQ uses) process
each speaker channel separately. They can boost or cut, but can't copy sound from the left and
right channels into the centre and rears, so they can't upmix. A vendor effect *can* move sound
between channels. SUMX is the most likely candidate, but it's undocumented, so Effect Lab is how
we find out.

### About Dolby and DTS

Real Dolby Pro Logic / Dolby Surround and DTS Neo:X are licensed, closed technology. A sideloaded
app can't legally ship them. The three modes above are ZeekrBass's own open algorithms, based on the
same general ideas (matrix decoding and steering). They are **not** Dolby or DTS.

If the car's Android system happens to include a licensed Dolby/DTS effect, **Scan audio effects** on
the main screen will list it, and a future version could try to use it.

### Known limits

- **Only apps that allow audio capture can be upmixed.** Most third-party apps allow it by default.
  Some block it, and built-in system apps (often including FM/DAB radio and Bluetooth audio) usually
  can't be captured. CarPlay / Android Auto audio isn't captured either. If nothing is captured, the
  status line says so after a few seconds.
- **There's a small delay** (usually tens of milliseconds) between the native front speakers and the
  added channels. It's harmless for surrounds, but if the centre or sub sounds smeared, lower them.
- **Android asks for capture permission** each time Upmix starts (its "start recording/casting"
  dialog). If Android offers a choice, pick *entire screen*. Run
  `adb shell appops set com.ctrlaltspoods.zeekrbass PROJECT_MEDIA allow` once to skip the prompt.
  That also lets Upmix restart on its own after the car reboots.
- Needs Android 10 or newer.

## Download

Every push is built by GitHub Actions:

- **Latest build:** open the [Actions tab](../../actions), pick the newest **Build APK** run and download
  `ZeekrBass-apk` from its Artifacts. It's a zip containing `ZeekrBass.apk`. You need to be signed in to GitHub.
- **Releases:** tagged versions (e.g. `v0.1.0`) are also published on the [Releases page](../../releases)
  with a direct APK link.

## Install & first run

Install it the same way you'd sideload any other app, e.g.:

```
adb install -r ZeekrBass.apk
```

1. Open **ZeekrBass**.
2. Tap **Open permission settings** and allow "Display over other apps".
   If the car has no screen for that, run:
   ```
   adb shell appops set com.ctrlaltspoods.zeekrbass SYSTEM_ALERT_WINDOW allow
   ```
3. Tap **Show floating panel**, then choose 5.1 or 7.1.
4. For **Upmix**, also allow audio capture. If the car has no permission screens, run:
   ```
   adb shell pm grant com.ctrlaltspoods.zeekrbass android.permission.RECORD_AUDIO
   adb shell appops set com.ctrlaltspoods.zeekrbass PROJECT_MEDIA allow
   ```

## How to test

Use one bass-heavy track at the volume level where the bass normally drops out:

1. Panel on **Off**: listen (baseline).
2. **5.1**: same track, same volume.
3. **7.1**: same track, same volume.
4. Repeat 2 and 3 with **Dither** on.

The main screen shows where Android routed the stream (`→ device name`). That's useful to note if
the results differ between modes.

For **Upmix**: start some music, pick Upmix, and check that the **Input** meter on the main screen
moves. If it stays flat, that app's audio can't be captured. Then try Cinema, Music and Manual, and
adjust the centre / surround / sub levels to taste.

## Testing status

| Test | Result |
| --- | --- |
| Silent 5.1 | ✅ Changes the sound / bass, but stereo music only plays on the front speakers |
| Silent 7.1 | Not yet tested |
| With dither | Not yet tested |
| Upmix (capture): works on the 7X | ❌ Didn't work |
| Bass EQ: global (whole car) | ✅ Works |
| Bass EQ: music apps detected | ❌ No apps announced their audio |
| Bass EQ: loudness follows volume | Not yet tested |
| Root | ❌ Not available to apps on this car (removed in v0.4) |
| Effect Lab: SUMX | Not yet tested |
| Effect Lab: audio report (output channel count) | Not yet tested |
| Upmix: Cinema / Music / Manual | Not yet tested |
| Upmix: which music apps can be captured | Not yet tested |

## Troubleshooting

- Status says **"7.1 PCM is not supported here"**: the car's audio output doesn't accept that layout. Use 5.1.
- Status keeps showing **retrying**: the car's audio service is rejecting or dropping the stream.
  Check `adb logcat -s ZeekrBass`.
- Panel disappears after the car sleeps: open the app once. With "Restore after restart" on, it
  should come back on its own after a full reboot.

## Building it yourself

- **GitHub Actions** (`.github/workflows/build.yml`) builds on every push. Release builds are signed with a
  key stored in the repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.
  Without those secrets, the build falls back to a debug key.
- **Android Studio:** open the folder and run `./gradlew :app:assembleRelease`. Local builds use the debug
  key, so you'll need to uninstall a CI-built copy before installing a local one (and vice versa).

## Disclaimer

ZeekrBass is an independent, unofficial project made by a Zeekr owner. It is **not affiliated with,
endorsed by, sponsored by or otherwise connected to Zeekr, Zeekr Intelligent Technology, Geely or any of
their affiliates**. "Zeekr" and "7X" are trademarks of their respective owners. They're used here only
to describe the vehicle this app was made for.

This software is experimental and **still in testing**. It's provided "as is", without warranty of any
kind. Installing third-party apps on your vehicle may be unsupported by the manufacturer and is entirely
at your own risk. Never adjust the app while driving.
