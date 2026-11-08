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
- Pages: **Audio · Videos · Gallery · All Files · PDFs · Playlists · History · Online Music**, each with how many items it has.
- **⌂** next to a page selects the default page (the filled ⌂ shows the current one).
- Then **EQ & karaoke · Save WhatsApp status · Recycle bin · Other devices · NKT Share · Settings**. At the bottom: NKT Media Player · Version 1.0.
- Back closes the menu first, then goes up a level, then to your start page.

**Fast start**: the app shows your library from last time at once and checks the phone in the background ("⟳ Updating" for a moment). Android's library shows within a second; hidden folders are added when their scan finishes, without emptying the screen. New photos, songs and videos appear by themselves.

**Mini player**: while your music plays, it shows on every page (Online Music too). When it's paused — or closed with ✕ on another page, or paused because a song started in Online Music — it shows only on the Audio page, ready to carry on. ✕ on the Audio page stops it completely.

**Online Music** (☰ menu; ⌂ can make it the default page): "What do you want to play?" — tap the mic and say it (it listens right on the page — no pop-up; the first time, Android asks once to allow the microphone; tap the ■ when you're done, or just stop talking) or type a song name. The app searches YouTube itself (no account or key), plays the first result with sound straight away in YouTube's own player, and lines up the other results: tap any to play it, ‹‹ ›› to move, and it plays the next one when a song ends. Videos whose owners don't allow playing outside YouTube are skipped. RECENT shows your last searches. Your own music pauses while an online song plays; YouTube songs pause when you leave the page or lock the phone (YouTube's rules).

**Carries on where you left off**: the music player remembers the queue, the song and the moment, even after the phone restarts. Tap play to continue.

**Delete & Recycle bin**: deleted items leave the screen instantly, with **UNDO**. They go to the **Recycle bin** (☰ menu) for 30 days: Restore puts them back where they were, Delete forever removes them. They sit in the app's private folder, which other apps can't see (Android 11+), and they're removed if the app is uninstalled. After 30 days they're deleted permanently — checked once a day, even when the app isn't opened. If something can't be moved to the Recycle bin, the app tells you it won't be saved there and asks before deleting it permanently. Without "All files access", Android's own trash is used and Android asks first.

**Copy · Cut · Paste**: ⋮ (or long-press several → ⋮) → Copy or Cut, then open any folder in **All Files** — or a folder on **Other devices** — and tap **PASTE**. Works for files and folders, between phone storage, SD card, USB and other devices (NKT Share or any FTP app). Progress shows in the notification.

**Everything above the files** (title, search, sort and view) slides away when you scroll down and comes back when you scroll up.

**Sort & view** (the sort button on each page): every page starts with **A–Z, Z–A, Newest first, Oldest first**, then extras for that page (artist, album, length, size, most played, most items…). Views: Songs **list / grid / squares**, Videos **list / grid / folders**, Folders and Playlists **list / grid**. **Pinch** any grid for more or fewer per row (Videos: 1 to 4).

**Side bar**: drag the thin bar on the right of long lists to jump; a bubble shows the letter, date, length or size.

**Select many**: long-press files — or whole folders (Videos folder view, All Files) — then select all · ⓘ details (count, total size, kinds, length, dates) · share · delete · ⋮ (copy, cut, add to playlist).

**Properties** (⋮ → Properties, or ⓘ in the viewer and video player): name, place, size, date, length, resolution, type, with **OPEN WITH · RENAME · SHARE · DELETE**.

**Audio**: tap to play. ⋮: Play · ♥ · Add to playlist · Edit info · Set as ringtone · Share · Select · Copy · Cut · Rename · Delete · Properties.

**PDFs**: every PDF on the phone, newest first, list or grid; opens in the PDF reader.

**Save WhatsApp status** (☰ menu): the photos and videos of statuses you've watched in WhatsApp or WhatsApp Business. Tap to view, Save (or SAVE ALL) to keep them in Pictures / Movies → NKT Media Player Status. The SAVED tab shows what you kept.

**Lock screen & notification**: the NKT logo and the app's name; the song's own art when it has some, otherwise the NKT Media Player logo.

**Music player**: EQ · Timer · Speed · Lyrics · **Karaoke** · Queue · ♥. Lyrics follow the song when you change it from the queue (if a song still shows another song's words from before this update, tap 🔍 in the lyrics to search again). Karaoke turns the singer down with one tap (hold it for strength and key); with "Show lyrics while singing" the current line shows big.

**Equalizer & karaoke**: presets, bands, bass, surround, loudness. Karaoke works on songs and videos, stereo and surround: **Light / Normal / Strong**, and a **key** up or down for the song that's playing (remembered per song).

**Videos**
- **Seek bar or slide sideways**: the video itself moves to that moment while you drag (no small picture); it lands exactly where you let go. The middle shows how far and where, like "+0:15 · 1:23".
- **‹ ›** on the sides (with the controls) jump to the previous or next video; ⏮ ⏭ stay at the bottom too.
- ⋮: **Save this frame as image** (Pictures/NKT Media Player) · Karaoke on/off · **When this video ends: play next, go back, or stop on the last frame** (with a replay button) · Background play · Save sound · Zoom · Add to playlist · Share · Rename · Delete · Properties.
- Swipe up/down: brightness (left) and volume (right). Double-tap ±10 s. Pinch fill/fit. Hold for 2×.

**Gallery**: photos and videos together, in the same order as the grid — the viewer no longer skips videos. Videos start smoothly as you swipe onto them (no thumbnail jump) and have their own time and seek bar; ⛶ opens the full video player. **‹ ›** step to the previous or next photo or video; the film strip marks videos with ▶. Top bar: ♥ · share · ⓘ · delete · ⋮ (rename, copy, cut, **set as wallpaper**: home, lock or both).

**All Files**: every folder on the phone from the top, hidden ones too (hide them in Settings). Switch between phone storage, SD card and USB; the path shows where you are. **UP · NEW · PASTE** are buttons above the list, so they never look like files. Search looks through this folder **and every folder inside it**. Opens audio, videos, photos, text files and PDFs; anything else asks which app. Long-press folders or files to pick them; ⋮ on a folder: open, select, copy, cut, rename, delete, properties. In Videos' folder view, ⋮ on a folder also renames it (needs All files access).

**Text editor**: line numbers, undo/redo, find & replace, wrap, A− / A+, line/column, keeps the file's encoding, asks before leaving with unsaved changes and keeps a draft.

**PDF reader**: pages one under another, pinch to zoom, page bubble (tap to go to a page), night mode, opens where you left off.

**Other devices** (☰ menu)
- **Found on this Wi-Fi**: phones running **NKT Share** show up by themselves (marked NKT MEDIA PLAYER). **＋** adds any FTP server (a computer running FileZilla, a NAS…): address, port, guest or user & password, save.
- Browse folders, sort, list/grid. Songs and videos **play straight from the other device**; photos, text and PDFs open here.
- **Copy to this phone** (⋮, or long-press several): progress in the notification. **Copies save to · CHANGE**: pick any folder (phone or SD card, quick Download / Music / Movies / Pictures / DCIM, new folder) or **Ask every time** — remembered per device.
- **UP · NEW · PASTE · PLAY ALL · SAVE TO** are buttons above the list, styled apart from the files. NEW makes a folder on the other device.
- **Disconnect** at the top. Saved passwords are locked with the phone's keystore.

**Settings**: Video rotation · When a video ends · History · Version 1.0 · and last, **ALL FILES**: hide folders starting with a dot, folders with a .nomedia file, and .thumbnails folders — all three on by default, and what's hidden there is hidden everywhere (All Files, Audio, Videos, Gallery). Switch one off to see those files (needs All files access). (Equalizer, karaoke and karaoke strength are in ☰ → EQ & karaoke.) The start page is chosen with ⌂ in the ☰ menu; sharing is in the ☰ menu.

**Links** (🔗 at the top): paste a link → AUDIO, VIDEO or DOWNLOAD. YouTube links open in YouTube's own player.

**NKT Share** (computer button at the top): share files with a computer or other devices on Wi-Fi.
1. Tap **START** (first time: allow "All files access").
2. On a computer, type the address shown (like `ftp://192.168.1.5:2121`) into File Explorer (Mac: Finder → Go → Connect to Server). Other phones with NKT Media Player find it under **Other devices**.
3. Pick the shared folder, Read & write or Read only, and a password if you want one. Tap **STOP** when done.
4. **Quick settings tile**: pull down the status bar, tap ✎ (edit), and drag the **NKT Share** tile in. Tap it to start or stop sharing, like the torch. While sharing it lights up and shows the address.

Built on Google's Media3 (Apache 2.0), Apache FtpServer and Commons Net (Apache 2.0) and jaudiotagger (LGPL). Lyrics from LRCLIB (lrclib.net). Orbitron font: SIL Open Font License (`app/src/main/assets/fonts/OFL.txt`).
