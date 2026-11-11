# Pilchard Player

A custom external video player for Jellyfin on the Nvidia Shield, built on mpv.

## Getting the APK

Every push to `main` builds the app automatically.

1. Open the **Actions** tab on GitHub → the latest **Build APK** run.
2. Under **Artifacts**, download **PilchardPlayer-apk** (a zip) and unzip it.
3. Copy the APK to the Shield (for example with *Send Files to TV*) and open it
   with a file manager to install.

Versions tagged `v…` (like `v0.1.0`) also appear under **Releases** with the APK
attached directly.

Every build is signed with the same key, so new versions install over old ones and keep
your settings. The key is not in this repository: the build server gets it from the
repository's secrets (`PILCHARD_KEYSTORE_BASE64` and `PILCHARD_KEYSTORE_PASSWORD`). To
build signed copies yourself, put a `keystore.properties` file next to `build.gradle.kts`:

```
PILCHARD_KEYSTORE=keystore/pilchard.jks
PILCHARD_KEYSTORE_PASSWORD=...
```

Without a key, debug builds use Android's standard debug key.

## Two versions

Each build makes two copies of the player:

- **PilchardPlayer-x.y.z.apk** (family): the one used at home, with the app ID explained below.
- **PilchardPlayer-Play-x.y.z.apk** (Play Store): app ID `sugar.beefsupreme.pilchard`. Jellyfin
  doesn't recognize this ID, so after a video is stopped part way, Pilchard checks the server
  for about 30 seconds and puts the resume point back if Jellyfin wiped it (`ResumeFixer.kt`).
  This can be removed if Jellyfin's TV app adds Pilchard to its list of known players.

They have different app IDs, so both can be installed on the same Shield. Each one needs its
own sign-in. The Pilchard Live TV tile only opens the family version.

## Why the app ID is `is.xyz.mpv`

Jellyfin for Android TV only reads the stopping position back from external players it
recognizes by app ID (MX Player, VLC, mpv, Vimu). For any other player it assumes the
video was finished, which marks it watched and wipes the resume point. Pilchard is built
on mpv and returns results in mpv-android's format, so it uses mpv-android's app ID.
The real mpv-android app can't be installed at the same time.

## Using it

In the Jellyfin app on the Shield: **Settings → Playback**, set the video player to
**External player**, then pick **Pilchard Player** when a video starts.

**Sign in once:** open Pilchard Player from the Shield's home screen and sign in with your
Jellyfin username and password. Jellyfin's TV app hands Pilchard stream links without an
access token, so live TV (channel list, guide, switching) and direct resume saving need
Pilchard's own sign-in.

Server setup (trickplay previews, live TV): see [docs/SERVER_SETUP.md](docs/SERVER_SETUP.md).

## Roadmap

| Version | What's in it |
|---|---|
| v0.1 | Movies and shows: Cinematic look, seekable progress bar, chapters, audio & subtitle picker, resume, English audio and subtitles by default |
| v0.2 | Audio boost (dB), Night mode, Voice boost (in Audio & Subtitles → Sound), turquoise look |
| **v0.3** | Live TV: Minimal look, Guide, recent channels, channel blocks, pause with Jump to live |
| v0.4 | Trickplay previews, Skip Intro, Next Episode, polish |

## v0.1 test checklist

- [ ] Pilchard shows up in Jellyfin's player chooser and plays a movie
- [ ] Picture and sound look right (try a 4K / HDR file too)
- [ ] English audio and English subtitles are picked automatically
- [ ] Audio & Subtitles menu lists tracks and switching works
- [ ] Up selects the progress bar; Left/Right move, holding speeds up; OK jumps; Back cancels
- [ ] Chapters button appears on files with chapters and jumps correctly
- [ ] Remote volume changes the TV volume
- [ ] Back out mid-movie, then play again in Jellyfin: it resumes at the right spot
- [ ] Watching to the end marks it as watched in Jellyfin

## v0.2 test checklist

- [ ] Audio & Subtitles menu shows a **Sound** section at the top
- [ ] Volume boost: Left / Right changes it (+2 dB steps up to +20 dB); louder without crackling
- [ ] Voice boost makes dialogue clearer on a 5.1 movie and on a stereo show
- [ ] Night mode makes explosions softer and quiet talking easier to hear
- [ ] Settings are still on when the next movie starts

## v0.3 test checklist (live TV)

- [ ] Picking a channel in Jellyfin opens Pilchard in the Minimal live look (red LIVE badge, channel, program)
- [ ] Channel up / down buttons (and the remote's channel keys, if it has them) switch channels
- [ ] **Down** shows recent channels; OK switches; Up closes
- [ ] **Left** shows channel blocks with the video still playing on the right; OK switches; Right or Back closes
- [ ] **Guide** button shows the full guide with what's on now and next
- [ ] Pause shows "PAUSED · m:ss BEHIND LIVE"; Play resumes where you paused; **Jump to live** catches up
- [ ] Audio & Subtitles (and Volume boost / Voice boost / Night mode) work on live channels

## License

Pilchard Player is free software: you can redistribute it and/or modify it under the terms
of the GNU General Public License, version 3 or (at your option) any later version. It is
distributed without any warranty. See [LICENSE](LICENSE).

It's built on [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android), which
bundles [mpv](https://mpv.io) (GPL-2.0-or-later), [FFmpeg](https://ffmpeg.org)
(GPL-3.0-or-later) and other libraries; their licenses are listed in that project.
