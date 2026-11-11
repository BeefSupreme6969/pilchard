# Pilchard Player Privacy Policy

**Effective date:** November 9, 2026

Pilchard Player ("Pilchard", "the app") is a video player for Android TV that plays videos and live TV from a Jellyfin media server that you run or have access to. It is developed by **Beefsupreme69696969** ("we", "us").

**The short version:** Pilchard does not collect any personal information for us. It talks only to the Jellyfin server (and, if you set it up, the Dispatcharr server) that you choose. We never receive your data. There are no ads, no analytics, no tracking and no accounts with us.

## What Pilchard stores on your device

To work, Pilchard saves the following on your Android TV device only:

- **Your Jellyfin sign-in:** the server address you enter, your user name and user ID on that server, and an access token the server gives Pilchard when you sign in. **Your password is not stored.** It is sent once to your server to sign in and then discarded.
- **A device ID:** a random identifier Pilchard creates the first time it runs, so your Jellyfin server can tell this device apart from your other devices.
- **Live TV preferences:** your favorite channels, recently watched channels, the last channel you watched, and the optional Dispatcharr playlist address.
- **Player settings:** sound settings such as volume boost, voice boost, night mode and surround passthrough.

This information stays on your device. Pilchard turns off Android's cloud backup for its data, so it is not copied to your Google account.

## What Pilchard sends, and where

Pilchard connects only to the servers you set up:

- **Your Jellyfin server**, to sign in, play videos and channels, load the live TV guide, and save your playback progress and watched status. With each request, Pilchard tells your server its name and version, your device model (for example "SHIELD Android TV") and its device ID, so the device appears correctly in your server's list of devices.
- **Your Dispatcharr server**, only if you enter a Dispatcharr playlist address, to load channels directly from it.

These servers are run by you or by whoever manages them, not by us. What they store and how they use it is controlled by that server's owner and software.

Pilchard does not send any information to us or to any other company. It contains no advertising, analytics, crash-reporting or tracking code. The playback engines it uses (mpv and FFmpeg) run entirely on your device.

## Security

Pilchard uses the server address exactly as you enter it. Addresses that start with `https://` are encrypted. Addresses that start with `http://` are not encrypted, which is common on a home network but not recommended over the internet. We suggest using `https://` when connecting to your server from outside your home.

## Google Play

If you install Pilchard from Google Play, Google may collect information about your installation and use of the Play Store under its own policies. See the [Google Privacy Policy](https://policies.google.com/privacy).

## Children

Pilchard is not directed at children under 13 and does not knowingly collect information from anyone, including children.

## Deleting your data

- **Sign out** on Pilchard's home screen to remove your Jellyfin sign-in from the device.
- **Clear Pilchard's storage** (Settings → Apps → Pilchard Player → Clear data) or **uninstall** Pilchard to remove everything it has stored.
- Data held on your Jellyfin or Dispatcharr server, such as watch history, can be managed or deleted on that server.

## Open source

Pilchard Player is free software under the GNU General Public License v3. You can review exactly what it does in its source code at https://github.com/Beefsupreme6969/pilchard.

## Changes to this policy

If this policy changes, the new version will be posted at this address with a new effective date.

## Contact

Questions about this policy: **beefsupreme69696969@gmail.com**
