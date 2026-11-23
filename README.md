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

## How to test

Use one bass-heavy track at the volume level where the bass normally drops out:

1. Panel on **Off**: listen (baseline).
2. **5.1**: same track, same volume.
3. **7.1**: same track, same volume.
4. Repeat 2 and 3 with **Dither** on.

The main screen shows where Android routed the stream (`→ device name`). That's useful to note if
the results differ between modes.

## Testing status

| Test | Result |
| --- | --- |
| Silent 5.1 | Not yet tested |
| Silent 7.1 | Not yet tested |
| With dither | Not yet tested |

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
