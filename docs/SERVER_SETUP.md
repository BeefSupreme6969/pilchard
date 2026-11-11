# Pilchard Player — Server Setup

Setup notes for the Pilchard Player, the custom Jellyfin external player, on the Nvidia Shield:
turning on **trickplay** (seek-bar preview thumbnails) and connecting the
player to your Jellyfin server.

> The player app is still in the design stage. Steps that depend on the
> finished app are marked **(once the app is built)**.
> Jellyfin menu names shift a little between versions. These steps follow the
> 10.9 / 10.10+ layout. If a name doesn't match exactly, look for the closest one.

---

## 1. Trickplay (seek-bar previews)

Trickplay makes the Jellyfin server create small preview pictures from each
video. When you select the progress bar in the player and press Left or Right,
the thumbnail for that moment shows above the bar.

Trickplay is built into Jellyfin 10.9 and newer. No plugin is needed. If you
used the old *Jellyscrub* plugin, uninstall it.

### 1.1 Turn it on for each library

Do this for every library you want previews in (Movies, Shows, and so on).

1. **Dashboard → Libraries** → click the **⋮** on the library → **Manage library**.
2. Scroll to the **Trickplay** section and turn on:
   - **Enable trickplay image extraction**: generates the images.
   - **Extract trickplay images during the library scan** (optional): new
     media gets previews as it's added. Leave it off if you'd rather generate
     them overnight with the scheduled task (step 1.3).
3. Choose where the images are stored:
   - **Save trickplay images next to media** *on*: a `<video name>.trickplay`
     folder is created beside each video. The images survive a server
     rebuild, but Jellyfin needs **write access** to your media folders.
   - *off* (default): the images are stored in Jellyfin's own data folder.
     This works with read-only media mounts. Include that folder in your backups.
4. **Save**.

### 1.2 Server-wide trickplay settings

**Dashboard → Playback → Trickplay**

| Setting | Recommendation |
|---|---|
| Enable hardware decoding | **On**, if Jellyfin hardware acceleration is already set up (Dashboard → Playback → Transcoding). |
| Hardware-accelerated MJPEG encoding | **On** if your GPU supports it (Intel Quick Sync usually does). |
| Only generate images from key frames | **On**. Much faster, slightly less precise thumbnails. |
| Interval | **10 s** (default). Lower means more precise previews but more disk space and longer generation. |
| Width resolutions | **320** (default) is plenty for a preview on a TV. |
| Tile width / height | Leave the defaults (**10 × 10**). |
| Process priority / threads | Lower the priority if generation slows down playback for others. |

### 1.3 Generate images for existing media

New libraries won't have previews for content that's already there until
images are generated:

1. **Dashboard → Scheduled Tasks → Generate Trickplay Images** → ▶ to run now.
2. Optionally set a trigger, such as daily at 3 AM, so it catches up overnight.

A large library can take hours the first time, especially without hardware
acceleration. That's normal, and previews appear as each video finishes.

### 1.4 Check that it works

- In the Jellyfin web app, play something and hover over the seek bar. A
  thumbnail should appear.
- If you chose **Save trickplay images next to media**, you'll see a
  `.trickplay` folder beside the video.

### 1.5 Troubleshooting

| Problem | Fix |
|---|---|
| Task finishes instantly, no images | Trickplay isn't enabled on that library (step 1.1). |
| Fails when saving next to media | Jellyfin can't write to the media folder. Turn that option off, or fix the folder/mount permissions. |
| Very slow | Turn on hardware decoding and **Only generate images from key frames**, and confirm transcoding hardware acceleration works. |
| Disk usage grew | Raise the interval (e.g. 15–20 s) and regenerate. |

---

## 2. Connecting the player to Jellyfin

### 2.1 Movies and shows (the Cinematic player)

The Jellyfin app hands the video to the player, so no login is needed.

1. Install the player APK on the Shield **(once the app is built)**: copy the
   APK over (for example with *Send Files to TV* or a USB drive) and open it
   with a file manager. Allow "install unknown apps" for that file manager
   when Android asks.
2. In the **Jellyfin app** on the Shield, open **Settings → Playback** and set
   the video player to **External player**.
3. Play anything and pick **Pilchard Player** from the chooser. Pick "Always"
   to skip the chooser next time.

Resume points and "watched" status are reported back to Jellyfin when you exit.

### 2.2 Live TV (the Minimal player)

Live TV features (Guide button, recent channels, channel blocks) need the
player to read your channel list from Jellyfin directly, so it signs in once.

1. Make sure Live TV works in Jellyfin first: **Dashboard → Live TV** with
   your tuner (e.g. Dispatcharr's HDHomeRun or M3U output) and guide data added.
2. Create an API key for the player: **Dashboard → API Keys → +** →
   name it `Pilchard Player`. Copy the key.
3. In the player, open **Settings → Server** **(once the app is built)** and enter:
   - **Server address**: e.g. `http://192.168.x.x:8096` (or your Tailscale address)
   - **API key**: the key from step 2
   - **User**: the Jellyfin user whose channels, favorites and watch history to use
4. The player loads the channel list and guide. Channel blocks (1–100,
   101–200, …) are built automatically from the channel numbers.

### 2.3 Skip Intro button

The Skip Intro button only shows when Jellyfin knows where the intro is.
That comes from media segments, usually created by the **Intro Skipper**
plugin. Install it from **Dashboard → Plugins → Catalog** (you may need
to add its repository first), then run its detection task.

---

## 3. Player settings that affect the server

| Player setting | What it does |
|---|---|
| Live pause buffer | How much of a live channel is kept while paused. Default about 1 minute, to keep memory use small. |
| Audio boost / Night mode / Voice boost | Turning any of these on makes the player decode the audio itself instead of passing it through to a soundbar or receiver. Dialogue gets clearer, but Atmos / DTS:X effects are lost for that video. |
| Force Minimal / Cinematic | Overrides the automatic live TV vs. local media detection. |
