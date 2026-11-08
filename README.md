# How to make the NKT Media Player app (APK) — step by step

You only need a web browser and a GitHub account. No coding, nothing to install.

You get these files from Claude:
- **NKT-Media-Player-for-GitHub.zip** — the app, split into folders **Part-1, Part-2, …** of at most 100 files each, because GitHub's website takes at most 100 files at a time
- **NKT-Media-Player-signing-key.zip** — the app's secret key (optional, keep it private)

---

## Part 1 — Unzip

1. Right-click **NKT-Media-Player-for-GitHub.zip** → **Extract All** (Windows) or double-click it (Mac).
2. Inside are the folders **Part-1**, **Part-2**, … (upload them in order).
   - The last part has a hidden `.github` folder on a Mac. Press **Cmd + Shift + .** (dot) in Finder to see it.

## Part 2 — Upload to GitHub (once per part)

1. Go to **github.com**, sign in, and open your **nkt-media-player** repository.
   (No repository yet? Click **+** → **New repository** → name it **nkt-media-player** → **Private** → tick **Add a README file** → **Create repository**.)
2. Make sure you're on the repository's main page (not inside a folder).
3. Click **Add file** → **Upload files**. Open **Part-1**, select **what's inside it** (the `app` folder), drag it onto the page. Click **Commit changes**.
4. Do the same with what's inside each next part, in order. Commit each time.
5. The last part also has `.github`, `build.gradle`, `README.md` and the rest — upload them the same way.

Every part has an `app` folder — GitHub merges them into one. Files with the same name are replaced with the new ones, so uploading over your existing repository updates it.

## Part 3 — Check the build file is there

1. On the repository's main page, look for a folder called **.github**.
2. If it's missing (GitHub sometimes skips folders starting with a dot):
   - Click **Add file** → **Create new file**.
   - In the name box type exactly: `.github/workflows/build-apk.yml`
   - On your computer, open **the last part → .github → workflows → build-apk.yml** with Notepad (or TextEdit), copy everything, paste it into GitHub's big box.
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

1. With the secret key from Part 4 it installs over the NKT Media Player already on the phone and keeps your playlists and settings. Without the key, uninstall the old one first.
2. Send **NKT-Media-Player.apk** to the phone (WhatsApp to yourself, Drive, email or cable) and tap it.
3. If asked, allow installing from this source. Tap **Install** → **Open** → **ALLOW ACCESS**.

## Making a new version later

1. Upload the changed files (or edit on GitHub with the pencil icon).
2. Open `app/build.gradle` and raise `versionCode 1` to `versionCode 2` (then 3, 4…). Commit.
3. Download the new APK from **Actions** and install it over the old one (needs the secret key from Part 4).

---

## How to use the app

**☰ menu** (top left, or swipe in from the left edge)
- Pages: **Songs · Videos · Gallery · Folders · Playlists · History**, each with how many items it has.
- **⌂** next to a page makes it the page the app opens on (the filled ⌂ shows the current one).
- Then **EQ & karaoke · Other devices · NKT Share · Settings**. At the bottom: NKT Media Player · Version 1.0.
- Back closes the menu first, then goes up a level, then to your start page.

**Fast start**: the app shows your library from last time at once and checks the phone in the background ("⟳ Updating" for a moment). New photos, songs and videos appear by themselves.

**Everything above the files** (title, search, sort and view) slides away when you scroll down and comes back when you scroll up.

**Sort & view** (the sort button on each page): every page starts with **A–Z, Z–A, Newest first, Oldest first**, then extras for that page (artist, album, length, size, most played, most items…). Views: Songs **list / grid / squares**, Videos **list / grid / folders**, Folders and Playlists **list / grid**. **Pinch** any grid for more or fewer per row (Videos: 1 to 4).

**Side bar**: drag the thin bar on the right of long lists to jump; a bubble shows the letter, date, length or size.

**Select many**: long-press, then select all · add to playlist · share · delete.

**Properties** (⋮ → Properties, or ⓘ in the viewer and video player): name, place, size, date, length, resolution, type, with **OPEN WITH · RENAME · SHARE · DELETE**.

**Songs**: tap to play. ⋮: Play · ♥ · Add to playlist · Edit info · Set as ringtone · Share · Select · Properties · Delete.

**Music player**: EQ · Timer · Speed · Lyrics · **Karaoke** · Queue · ♥. Karaoke turns the singer down with one tap (hold it for strength and key); with "Show lyrics while singing" the current line shows big.

**Equalizer & karaoke**: presets, bands, bass, surround, loudness. Karaoke works on songs and videos, stereo and surround: **Light / Normal / Strong**, and a **key** up or down for the song that's playing (remembered per song).

**Videos**
- **Seek bar or slide sideways**: the video itself moves to that moment while you drag (no small picture); it lands exactly where you let go. The middle shows how far and where, like "+0:15 · 1:23".
- ⋮: **Save this frame as image** (Pictures/NKT Media Player) · Karaoke on/off · **When this video ends: play next, go back, or stop on the last frame** (with a replay button) · Background play · Save sound · Zoom · Add to playlist · Share · Properties.
- Swipe up/down: brightness (left) and volume (right). Double-tap ±10 s. Pinch fill/fit. Hold for 2×.

**Gallery**: photos and videos together, in the same order as the grid — the viewer no longer skips videos. Videos play right in the viewer (⛶ opens the full video player). **‹ ›** step to the previous or next photo or video; the film strip marks videos with ▶.

**Folders**: every folder on the phone from the top, hidden ones too (hide them in Settings). Switch between phone storage, SD card and USB; the path shows where you are. Opens songs, videos, photos, text files and PDFs; anything else asks which app. **＋** makes a new folder or text file. Long-press a folder for its properties.

**Text editor**: line numbers, undo/redo, find & replace, wrap, A− / A+, line/column, keeps the file's encoding, asks before leaving with unsaved changes and keeps a draft.

**PDF reader**: pages one under another, pinch to zoom, page bubble (tap to go to a page), night mode, opens where you left off.

**Other devices** (☰ menu)
- **Found on this Wi-Fi**: phones running **NKT Share** show up by themselves (marked NKT MEDIA PLAYER). **＋** adds any FTP server (a computer running FileZilla, a NAS…): address, port, guest or user & password, save.
- Browse folders, sort, list/grid. Songs and videos **play straight from the other device**; photos, text and PDFs open here.
- **Copy to this phone** (⋮, or long-press several): progress in the notification. **Copies save to · CHANGE**: pick any folder (phone or SD card, quick Download / Music / Movies / Pictures / DCIM, new folder) or **Ask every time** — remembered per device.
- **Disconnect** at the top. Saved passwords are locked with the phone's keystore.

**Settings**: show hidden media · show .thumbnails · Folders: hide dot-folders, .nomedia folders, .thumbnails (Folders page only) · EQ & karaoke · Karaoke strength · Video rotation · When a video ends · History · Version 1.0. The start page is chosen with ⌂ in the ☰ menu; sharing is in the ☰ menu.

**Links** (🔗 at the top): paste a link → AUDIO, VIDEO or DOWNLOAD. YouTube links open in YouTube's own player.

**NKT Share** (computer button at the top): share files with a computer or other devices on Wi-Fi.
1. Tap **START** (first time: allow "All files access").
2. On a computer, type the address shown (like `ftp://192.168.1.5:2121`) into File Explorer (Mac: Finder → Go → Connect to Server). Other phones with NKT Media Player find it under **Other devices**.
3. Pick the shared folder, Read & write or Read only, and a password if you want one. Tap **STOP** when done.

Built on Google's Media3 (Apache 2.0), Apache FtpServer and Commons Net (Apache 2.0) and jaudiotagger (LGPL). Lyrics from LRCLIB (lrclib.net). Orbitron font: SIL Open Font License (`app/src/main/assets/fonts/OFL.txt`).
