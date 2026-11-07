# How to make the NKT Media Player app (APK) — step by step

You only need a web browser and a GitHub account. No coding, nothing to install.

You get these files from Claude:
- **NKT-Media-Player-upload-in-3-parts.zip** — the app, split into 3 folders because GitHub's website takes at most 100 files at a time
- **NKT-Media-Player-signing-key.zip** — the app's secret key (optional, keep it private)

---

## Part 1 — Unzip

1. Right-click **NKT-Media-Player-upload-in-3-parts.zip** → **Extract All** (Windows) or double-click it (Mac).
2. Inside are three folders: **Part-1**, **Part-2**, **Part-3**.
   - On a Mac, Part-3 has a hidden `.github` folder. Press **Cmd + Shift + .** (dot) in Finder to see it.

## Part 2 — Upload to GitHub (3 times)

1. Go to **github.com**, sign in, and open your **nkt-media-player** repository.
   (No repository yet? Click **+** → **New repository** → name it **nkt-media-player** → **Private** → tick **Add a README file** → **Create repository**.)
2. Make sure you're on the repository's main page (not inside a folder).
3. Click **Add file** → **Upload files**. Open **Part-1**, select **what's inside it** (the `app` folder), drag it onto the page. Click **Commit changes**.
4. Do the same with what's inside **Part-2**. Commit.
5. Do the same with what's inside **Part-3** (`.github`, `app`, `build.gradle`, `README.md` and the rest). Commit.

All three parts have an `app` folder — GitHub merges them into one. Files with the same name are replaced with the new ones.

## Part 3 — Check the build file is there

1. On the repository's main page, look for a folder called **.github**.
2. If it's missing (GitHub sometimes skips folders starting with a dot):
   - Click **Add file** → **Create new file**.
   - In the name box type exactly: `.github/workflows/build-apk.yml`
   - On your computer, open **Part-3 → .github → workflows → build-apk.yml** with Notepad (or TextEdit), copy everything, paste it into GitHub's big box.
   - Click **Commit changes** → **Commit changes**.

## Part 4 — The secret key (optional, recommended)

With the key, every new version installs over the old one like a normal app update. Without it the app still builds, but you'll have to uninstall before installing each new version.

1. Unzip **NKT-Media-Player-signing-key.zip** and open **signing-secrets.txt**.
2. In your repository: **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.
3. Add the 4 names from the file, each with the value under it: `NKT_KEYSTORE_BASE64`, `NKT_KEYSTORE_PASSWORD`, `NKT_KEY_ALIAS`, `NKT_KEY_PASSWORD`.

Never upload signing-secrets.txt or the .jks file to the repository.

## Part 5 — Build the APK

1. Click the **Actions** tab. Each upload starts a build; ignore the older ones.
2. Wait for the newest **Build APK** run to show a green tick ✓ (4–6 minutes). If none is running: click **Build APK** on the left → **Run workflow** → **Run workflow**.
3. Click the run → under **Artifacts** click **NKT-Media-Player.apk**. Unzip the download.

A red ✗ means something went wrong: click the run, then the red step, copy the error text and send it to Claude.

## Part 6 — Install on the phone

1. Uninstall any older NKT Media Player first (this is version 1.0, a fresh start).
2. Send **NKT-Media-Player.apk** to the phone (WhatsApp to yourself, Drive, email or cable) and tap it.
3. If asked, allow installing from this source. Tap **Install** → **Open** → **ALLOW ACCESS**.

## Making a new version later

1. Upload the changed files (or edit on GitHub with the pencil icon).
2. Open `app/build.gradle` and raise `versionCode 1` to `versionCode 2` (then 3, 4…). Commit.
3. Download the new APK from **Actions** and install it over the old one (needs the secret key from Part 4).

---

## How to use the app

**Tabs**: SONGS · VIDEOS · GALLERY · FOLDERS · PLAYLISTS · SETTINGS (swipe the tab row sideways).

**Search bar and folder strip** slide away when you scroll down and come back when you scroll up.

**Side bar**: on long lists and grids a thin bar appears on the right while scrolling. Drag it to jump from top to bottom; a bubble shows the letter or date.

**Sort**: tap the sort button next to LIST/GRID. Songs: name, artist, album, newest, length, size, most played. Videos: name, date, length, size. Gallery: date, name, size. Folders: name, most items.

**Select many**: long-press any song, video or photo, then tap more. Top bar: **select all**, **add to playlist**, **share**, **delete** (Android asks you to confirm). The ✕ or Back stops selecting. The ⋮ on a row still opens its menu.

**Songs**
- Tap to play. **LIST / SQUARES**: squares group similar songs automatically.
- ⋮ menu: Play · ♥ Favourite · Add to playlist · Edit info · **Set as ringtone** (phone, notification or alarm — the first time, allow "Modify system settings") · Share · Select · Details · Delete.

**Mini player** (bottom): play/pause, next, and **✕** to stop and close it.

**Music player (Now Playing)**
- Option bar: **EQ** · **Timer** · **Speed** · **Lyrics** · **Queue** · **Love ♥**.
- **Lyrics**: shows the words and scrolls with the song; the line being sung is bright. Tap a line to jump there.
  - Found in this order: lyrics already saved → a `.lrc` file with the same name next to the song → lyrics inside the song file → downloaded from **LRCLIB** (free lyrics library). Downloaded lyrics are saved, so next time they work offline.
  - **Sync**: if the words are early or late, tap **+** (show sooner) or **−** (show later), half a second each. It's remembered for that song.
  - 🔍 searches online again — you can fix the title or artist first.
- ⋮ menu: Add to playlist · Edit info · Set as ringtone · Share · Download (online links) · Details.

**Equalizer** (EQ): presets, your own levels, Bass, Surround, Loudness.
- **Karaoke** removes the main singer's voice, frequency by frequency, and keeps the music, bass and drums. A line under the switch says "Working on this song", or tells you the song is mono (one channel), where the voice can't be separated. Works best on studio songs; very echoey or live recordings may keep a faint voice.

**Videos**
- **LIST / GRID** switch.
- Controls are **hidden** until you tap the video. Everything is at the bottom: seek bar, rotation, speed, ⏮ ▶ ⏭, ♥, mini window, ⋮.
- **Seek bar**: drag it and a picture of that moment appears above your finger.
- **Slide left/right** on the video for fine seeking: about 1 second per small step; the time and a picture show while sliding, it jumps when you let go.
- Swipe up/down: left half = brightness, right half = volume. Double-tap left/right = −/+10 s. Pinch = fill/fit. **Press and hold** = 2× while held.
- **Rotation** button: Auto rotate, Lock portrait or Lock landscape (remembered; also in Settings).
- **♥** adds the video to **♥ Favourite videos**.
- **Mini window** button: the video keeps playing in a small floating window.
- ⋮ menu: **Background play** (sound continues with the screen off; controls in the notification; tap the mini player → **WATCH VIDEO** to go back), **Save sound as audio** (saved in Music/NKT Media Player, usually as .m4a — same quality; .mp3 if the video's sound is MP3), **Auto-play next video** on/off, Zoom, Add to playlist, Share, Details, Download (online links).

**Gallery**: photos and videos by day. Pinch the grid for more or fewer per row; album strip and ALL / PHOTOS / VIDEOS. Photo viewer: swipe, zoom, ♥ (**♥ Favourite photos**), share, details.

**Playlists**: **History** at the top (everything you opened, newest first, with time), then **♥ Favourite songs**, **♥ Favourite videos**, **♥ Favourite photos**, then your own playlists.

**Settings tab**
- Show hidden files & folders.
- Show **.thumbnails** folders — the phone's little preview pictures; separate from the switch above.
- Equalizer & karaoke · Video rotation · Play next video automatically.
- Keep history (on by default) · Clear history.
- NKT Share · Version.

**Links** (🔗 at the top)
- Paste a link → **AUDIO**, **VIDEO** or **DOWNLOAD** (saved in Download/NKT Media Player, progress in the notification).
- **YouTube links** open in YouTube's own player inside the app (YouTube's rules: its controls and ads, no background play, no download).

**Share list**: NKT Media Player now appears when you tap **Share** in other apps (like VLC) — songs, videos, photos and links. Also in **Open with** for songs, videos and photos.

**NKT Share** (computer button at the top)
1. Tap **START** (first time: allow "All files access").
2. Type the address shown (like `ftp://192.168.1.5:2121`) into File Explorer's address bar on your computer (Mac: Finder → Go → Connect to Server).
3. **Shared folder**: tap it to browse and pick **any folder** (phone storage or SD card), then **SHARE THIS FOLDER**.
4. **Access**: **Read & write** (open, copy, add, rename, delete) or **Read only** (open and copy only).
5. **Use a password**: off by default. Sharing never stops by itself — tap **STOP** when done.

Built on Google's Media3 (Apache 2.0), Apache FtpServer (Apache 2.0) and jaudiotagger (LGPL). Lyrics from LRCLIB (lrclib.net). Orbitron font: SIL Open Font License (`app/src/main/assets/fonts/OFL.txt`).
