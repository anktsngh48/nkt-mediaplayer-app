# NKT MP (NKT Media Player)

Native Android app (plain Java, Media3). Built only on GitHub Actions — no Android Studio needed.

## Build it (short version)
1. Upload everything in this folder to a new **private** GitHub repository.
2. Open the **Actions** tab → **Build APK** → wait for the green tick.
3. Download the **NKT-MP-apk** artifact → install `NKT-MP.apk` on your phone.

## Make updates install over the old app (do once)
1. Actions → **Make signing key (run once)** → Run workflow.
2. Download the **signing-secrets** artifact and open `signing-secrets.txt`.
3. Settings → Secrets and variables → Actions → add the 4 secrets exactly as named in the file.
4. Delete that workflow run. Keep the text file private.

## New version
Raise `versionCode` in `app/build.gradle` by 1, commit, download the new APK, install over the old one.

## What's inside
Songs / Videos / Gallery / Folders / Playlists / History with a menu drawer and a choosable start page,
sort (A–Z, Z–A, Newest first, Oldest first + more) and list/grid on every page, pinch-to-resize grids,
background music with lock-screen controls, sleep timer, speed, queue, equalizer, karaoke (vocal removal),
full-screen video player (gestures, live scrubbing on the main picture, save frame, PiP, background play,
"when a video ends" choice), photo + video viewer, file explorer from storage root with hidden-file switch,
text editor, PDF reader, APK install via Android's own prompt, NKT Share (FTP server on port 2121),
Other devices (FTP client with Wi-Fi discovery), play-a-link, YouTube embed, downloads.

Font: Orbitron (SIL Open Font License, see `app/src/main/assets/fonts/OFL.txt`).
