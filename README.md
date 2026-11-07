# How to make the NKT Media Player app (APK) — step by step

You only need a web browser and a GitHub account. No coding, nothing to install.
About 15 minutes the first time.

You need 2 files from Claude:
- **NKT-Media-Player.zip** — the app
- **NKT-Media-Player-signing-key.zip** — the app's secret key (keep it private)

---

## Part 1 — Unzip the files

1. Find **NKT-Media-Player.zip**. Right-click → **Extract All** (Windows) or double-click it (Mac).
2. Open the new **NKT-Media-Player** folder. Inside you see `app`, `.github`, `build.gradle`, `README.md` and a few more.
   - On a Mac, `.github` is hidden. Press **Cmd + Shift + .** (dot) in Finder to show it. It must be uploaded too.
3. Also unzip **NKT-Media-Player-signing-key.zip**. Open **signing-secrets.txt** inside; you'll copy from it in Part 3.

## Part 2 — Put the app on GitHub

1. Go to **github.com** and sign in.
2. Click **+** (top right) → **New repository**.
3. Name it **nkt-media-player**. Choose **Private**. Tick **Add a README file**. Click **Create repository**.
4. Click **Add file** → **Upload files**.
5. In the open **NKT-Media-Player** folder, select **everything inside it** (Ctrl + A, or Cmd + A on a Mac) and drag it onto the GitHub page.
   - Drag what is *inside* the folder, not the folder itself.
6. When the upload finishes, click the green **Commit changes** button.

GitHub starts a build straight away. Ignore that first one; it has no secret key yet.

## Part 3 — Add the secret key (do this once)

This makes every future version install over the old one, like a normal app update.

1. In your repository, click **Settings** (top menu, far right).
2. On the left: **Secrets and variables** → **Actions**.
3. Click **New repository secret**.
4. **signing-secrets.txt** has 4 names, each with a value under it. For each one:
   - **Name**: the name (for example `NKT_KEYSTORE_BASE64`)
   - **Secret**: the value under it (the first one is one very long line; copy all of it)
   - Click **Add secret**
5. Do it 4 times: `NKT_KEYSTORE_BASE64`, `NKT_KEYSTORE_PASSWORD`, `NKT_KEY_ALIAS`, `NKT_KEY_PASSWORD`.

Never upload signing-secrets.txt or the .jks file to the repository.

## Part 4 — Build the APK

1. Click the **Actions** tab.
2. On the left click **Build APK**, then the **Run workflow** button on the right → green **Run workflow**.
3. Wait 4–6 minutes. A yellow dot means it's still building.
4. When it turns into a green tick ✓, click the run.
5. Scroll down to **Artifacts** and click **NKT-Media-Player.apk**. A zip downloads.
6. Unzip it. Inside is **NKT-Media-Player.apk**.

A red ✗ means something went wrong. Click the run, then the red step, copy the error text and send it to Claude.

If **Actions** shows "Get started with GitHub Actions" instead, the hidden `.github` folder didn't upload. Ask Claude for the "create the workflow file by hand" steps.

## Part 5 — Install it on the phone

1. Send **NKT-Media-Player.apk** to the phone (WhatsApp to yourself, Google Drive, email or USB cable).
2. Tap the file on the phone.
3. If it says it can't install apps from unknown sources: tap **Settings**, turn on **Allow from this source**, go back.
4. Tap **Install**, then **Open**.
5. Tap **ALLOW ACCESS** and allow music and videos. Your songs and videos appear.

## Already have version 1? Update to version 2

1. Unzip the new **NKT-Media-Player.zip**.
2. In your **nkt-media-player** repository click **Add file** → **Upload files**.
3. Select everything inside the new folder and drag it onto the page (same as Part 2). Files with the same name get replaced; that's what we want.
4. Click **Commit changes**. The build starts by itself. Then do Part 4 and Part 5. It installs over version 1; your playlists stay.

The secret key from Part 3 is already set. Don't add it again.

## Making a new version later

1. Make the change on GitHub (pencil icon → **Commit changes**).
2. Open `app/build.gradle` and raise `versionCode 2` to `versionCode 3` (then 4, 5…). Commit.
3. Download the new APK from **Actions** (Part 4) and install it over the old one. No uninstall needed.

---

## How to use the app

**Tabs**: SONGS · VIDEOS · GALLERY · FOLDERS · PLAYLISTS. Swipe the tab row sideways if they don't all fit. The search box filters whatever tab you're on.

**Songs**
- Tap a song to play it. The rest of the list plays after it. The bar at the bottom controls it; tap the bar for the big player.
- **LIST / SQUARES** switch: Squares groups similar songs automatically (same artist, genre, album, decade, folder, recently added, most played). Tap a square → **Play all**, **Shuffle** or **Save as playlist**.
- Long-press a song (or ⋮): Play · Add to playlist · **Edit info** · Share · Details.

**Big player (Now Playing)**
- Shuffle, previous, play/pause, next, repeat (off → all → one), seek bar.
- Option bar: **EQ** · **Timer** · **Speed** · **Queue** · **Love ♥** (adds to the "♥ Favourites" playlist) · **Share**.
- **Timer**: stop after 15/30/45/60 minutes (fades out gently) or at the end of the song. **Speed**: 0.25× to 2×. **Skip silence** switch.
- **Queue**: drag ≡ to reorder, swipe or ✕ to remove, tap to jump. **Save** the queue as a playlist, or **Clear** it.
- **UP NEXT** shows the next song; tap it to open the queue.
- ⋮ (top right): Add to playlist · Edit info · Details.
- **Lock screen and notification** show the album art, title, artist, play/pause, previous, next and a seek bar.

**Equalizer** (EQ button)
- On/off switch, the phone's presets (Normal, Rock, Pop…), your own band levels, Bass, Surround and Loudness. Saved for next time.
- **Karaoke**: removes the main vocals. It works best on studio songs. It does nothing on mono songs, and live recordings may keep a faint "ghost" of the voice. You can switch it while a song plays.

**Edit info** (long-press a song → Edit info)
- Change the title, artist, album, album artist, genre, year and track number.
- **Album art**: **Change** picks a picture from your phone, **Remove** deletes it.
- Tap **SAVE**. The first time, Android asks if NKT Media Player may change the file: tap **Allow**. The new info is written into the song file, so other apps see it too. Needs Android 8 or newer.

**Gallery**
- All photos and videos, newest first, grouped by day.
- **Pinch** the grid like Google Photos: fingers apart = bigger pictures, fingers together = more per row (7 per row groups by month).
- Album strip at the top (Camera, Screenshots, WhatsApp…), and **ALL / PHOTOS / VIDEOS** filter.
- Tap a photo to open it: swipe left/right, pinch or double-tap to zoom, **Share**, **ⓘ** details, filmstrip at the bottom. Tap a video to play it.

**Hidden files**: FOLDERS tab → turn on **Show hidden files & folders**. Android asks for "All files access" once: turn it on and come back. Hidden folders get an eye icon and "Hidden ·".

**NKT Share** (the computer button at the top): share the phone's files with a computer on the same Wi-Fi.
1. Tap **START**. The first time, turn on "All files access" and come back. Sharing starts by itself.
2. The screen shows an address like `ftp://192.168.1.5:2121`. Tap the copy button if you need to.
3. On a **Windows** PC: open **File Explorer**, click the address bar at the top, type the address, press **Enter**. On a **Mac**: Finder → **Go** → **Connect to Server**, type the address. On another phone, use a file manager that supports FTP.
4. The computer can open, copy, add, rename and delete files.
5. Sharing **never turns off by itself**. Tap **STOP** (on the screen or in the notification) when you're done.
- Settings: **Shared folder** (whole storage, Music, Download, Camera…) and **Use a password** (off by default, so anyone on your Wi-Fi can connect. Turn it on in places like offices or cafés; the user name and password are shown on the screen).

**Online links**: the 🔗 button at the top. Paste a link to an MP3, MP4, M3U8 stream and so on, then choose **AUDIO** (plays in the background) or **VIDEO**.

**Open with**: tapping a song or video in WhatsApp or the file manager can open it in NKT Media Player.

**Video player**
- Swipe up/down on the **left** half: brightness. On the **right** half: volume.
- **Double-tap** left / right: back / forward 10 seconds.
- **Press and hold** anywhere: plays at **2×** while you hold; let go and it's back to normal.
- **Speed** button (top bar): 0.25× to 2×; voices keep their normal pitch.
- **Pinch** out: zoom to fill the screen; pinch in: fit. **Tap**: show/hide controls. Top bar also has zoom and rotation lock 🔒.
- Starting a video pauses the music; videos pause when you leave the app.

Built on Google's Media3 player (Apache 2.0), Apache FtpServer (Apache 2.0) and jaudiotagger (LGPL). Orbitron font: SIL Open Font License (`app/src/main/assets/fonts/OFL.txt`).
