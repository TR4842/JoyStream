# JoyStream

**A native Android client for [MovieBox-Tui](https://github.com/mesamirh/MovieBox-Tui)** — browse, stream, download and watch live TV with a touch-first Material 3 interface.

<div align="center">
  <img src="docs/app-icon.png" width="112" alt="JoyStream icon" />
</div>

MovieBox-Tui is a Rust terminal app that scrapes streaming catalogs. JoyStream keeps
its provider engine — the same endpoints, the same request signing, the same
catalog — but replaces the terminal with a phone-shaped experience: a poster
grid home screen, a full-screen player with subtitles and resume, resumable
offline downloads, IPTV live channels, and **automatic updates that follow the
upstream repo**.

| | |
| :--- | :--- |
| **Platform** | Android 7.0+ (API 24), Kotlin + Jetpack Compose |
| **Player** | Media3 / ExoPlayer (DASH, HLS, progressive MP4) |
| **Providers** | MovieBox (signed BFF API), Dramachi, IPTV via M3U |
| **Distribution** | GitHub Releases APK, self-updating in app |

**Install the current build:**
[`joystream-1.0.2.apk`](https://github.com/TR4842/JoyStream/releases/latest) —
signed release APK, ~15 MB. Allow installs from unknown sources when prompted;
the in-app updater uses the same signature, so future versions install in place.

---

## Features

- **Home deck** — trending carousel plus titled rows pulled from the MovieBox
  `tab-operating` feed, merged with your own *Continue watching* and *My List*.
- **Unified search** — one query fans out to every enabled provider and merges
  the results (with per-provider error reporting instead of a dead screen).
- **Rich details** — backdrop, ratings, genres, cast, expandable synopsis,
  season/episode browsing, multi-audio (dub) editions and per-episode progress.
- **Quality picker** — explicit source list (4K/1080p/720p/480p, DASH vs direct
  MP4, codec, size) *before* playback starts, just like the TUI's resolution
  picker.
- **Subtitles** — MovieBox `get-ext-captions` tracks are attached to the media
  item and switchable from the player overlay.
- **Resume everywhere** — playback position is persisted every few seconds and
  restored from the home deck, the episode list or the library.
- **Offline downloads** — HTTP range-resumable downloads in a foreground
  service with pause/resume/cancel, playback straight from disk.
- **Live TV** — import an M3U/M3U8 playlist by URL, file or share-sheet intent;
  channels are cached, grouped and searchable.
- **Self-updating** — in-app update checks against GitHub Releases with a real
  `PackageInstaller` session (no browser hop). See below.
- **Mirror failover + diagnostics** — every MovieBox request walks the mirror
  list (a blocked or dead host usually answers 404/403 rather than timing out).
  *Settings → Test connection* shows what each mirror returned, and an optional
  custom API host covers networks that block all the built-in ones.
- **Zero telemetry** — library, history and settings never leave the device.

## How the auto-update chain works

JoyStream has *two* update layers, because it depends on an upstream project it
does not control:

```
mesamirh/MovieBox-Tui  ──(new commit)──►  upstream-sync.yml  (cron, every 6h)
                                             │  detects sha change
                                             │  bumps version.properties
                                             │  builds APK + publishes Release
                                             ▼
                              github.com/TR4842/JoyStream/releases
                                             │
                       in-app UpdateController (launch + manual check)
                                             ▼
                        PackageInstaller session → APK updated in place
```

1. **Upstream watcher** — `.github/workflows/upstream-sync.yml` polls the
   MovieBox-Tui repository. When the head commit differs from the sha recorded
   in `version.properties`, it:
   - bumps `versionCode`/`versionName` (patch),
   - records the new upstream sha + the upstream commit list in
     `UPSTREAM_CHANGELOG.md`,
   - rebuilds debug + release APKs,
   - commits the bump, tags `vX.Y.Z`, and publishes a GitHub release with the
     APK attached.
2. **In-app updater** — `UpdateRepository` reads `releases/latest`, compares the
   tag with `BuildConfig.VERSION_NAME`, downloads the APK and installs it
   through a `PackageInstaller` session. `Settings → Updates` shows the upstream
   commit the build was produced from next to the current upstream head, so you
   can always tell whether a rebuild is pending.

The whole chain runs on GitHub-hosted runners — nothing to host yourself.

## Install

### From a release (recommended)

1. Open [Releases](https://github.com/TR4842/JoyStream/releases).
2. Download `joystream-<version>.apk` and install it (allow "install unknown
   apps" for your browser/file manager).
3. Open JoyStream → **Settings → Updates** → *Check now* to confirm the updater
   is working. Subsequent versions install in place.

### Build from source

```bash
git clone https://github.com/TR4842/JoyStream.git
cd JoyStream
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # signed with the bundled dev keystore
./gradlew :app:testDebugUnitTest
```

Requires JDK 17 and the Android SDK (platform 34, build-tools 34). If you don't
have Gradle installed, open the project in Android Studio — the wrapper jar is
committed, so `./gradlew` works out of the box.

## Signing

CI needs a stable signing key so builds can replace each other in place. Two
options:

- **Bundled development keystore (default)** — `keystore/joystream-dev.jks`
  (alias `joystream`, store/key password `joystream`). CI uses it automatically
  when no secrets are configured. It is intentionally public: anyone can build
  an APK that Android considers an update to yours, so treat releases as
  community builds.
- **Your own keystore (recommended for a fork)** — add four repository secrets:

  | Secret | Value |
  | :--- | :--- |
  | `JOYSTREAM_KEYSTORE_BASE64` | `base64 -w0 your-release.jks` |
  | `JOYSTREAM_KEYSTORE_PASSWORD` | store password |
  | `JOYSTREAM_KEY_ALIAS` | key alias |
  | `JOYSTREAM_KEY_PASSWORD` | key password (optional, defaults to store password) |

> Switching keys after release means users must uninstall the old build once.

## Project layout

```
app/src/main/java/com/joystream/app/
├── core/                     # container, HTTP stack, update controller, JSON helpers
├── data/
│   ├── model/                # catalog, details, history, downloads, playback request
│   ├── providers/
│   │   ├── moviebox/         # signed BFF client, HMAC signing, session, JSON → models
│   │   ├── dramachi/         # asian drama JSON API
│   │   └── livetv/           # M3U parser + cached playlist repository
│   ├── library/              # favorites, history, resume positions, downloads (JSON on disk)
│   ├── download/             # foreground service, range-resumable transfers
│   └── update/               # GitHub release checks + PackageInstaller
└── ui/
    ├── home/ search/ details/ library/ livetv/ settings/
    ├── player/               # ExoPlayer activity, subtitle picker, progress persistence
    ├── components/ theme/ nav/
```

### Provider parity with upstream

| Upstream Rust module | JoyStream Kotlin |
| :--- | :--- |
| `providers/moviebox/crypto.rs` | `MovieBoxCrypto` — HMAC-MD5 signing, client token, device fingerprint |
| `providers/moviebox/client.rs` | `MovieBoxClient` — host pool failover, visitor sessions, `x-user` rotation |
| `providers/moviebox/adapt.rs` | `MovieBoxProvider` — homepage groups, subject details, play-info DASH policy decoding, captions |
| `providers/dramachi/*` | `DramachiProvider` — search/title/episodes/getFile |
| `providers/tv/parser.rs` | `M3uParser` — EXTINF attributes, EXTGRP, dedupe |
| `service.rs` (homepage/search fan-out) | `CatalogRepository` |

Not yet ported: 4KHDHub + HubCloud resolution chain, BDIX (CircleFTP /
DhakaFlix, LAN-only), and Stremio addons. `MediaProvider` is the seam to add
them — implement the interface and register it in `AppContainer`.

## Using the app

| Screen | What you can do |
| :--- | :--- |
| **Home** | Tap a poster or the hero card → details. Tap the header to refresh. Update banner appears when a new build exists. |
| **Search** | Type 2+ characters; results stream in from every enabled provider. Recent searches are stored locally. |
| **Details** | *Play* opens the quality sheet. Series show season chips and an episode list with progress bars. Bookmark saves to My List. |
| **Player** | Double-tap/scrub with ExoPlayer controls. Top overlay: back, title, subtitle picker, rotate. Position saves automatically. |
| **Live TV** | Set a playlist URL in Settings, then browse groups and tap a channel. |
| **Library** | Continue watching, My List and Downloads (pause / resume / delete / play offline). |
| **Settings** | Providers, preferred quality, Wi-Fi-only downloads, live TV playlist, update preferences, upstream status. |

## Notes & limitations

- MovieBox occasionally serves a placeholder "notice" clip instead of real
  media; JoyStream filters those URLs and reports *"no playable sources"*
  rather than playing a 10-second ad.
- Adaptive DASH sources stream fine but are not downloadable (there is no
  offline packager on device). Pick a direct MP4 source to download.
- BDIX providers only work inside Bangladesh ISP networks; they are absent from
  the Android build rather than shipped broken.
- Provider endpoints change. That is exactly why the upstream sync workflow
  exists — but if the Rust project changes a payload shape, the Kotlin mappers
  need the same change in a follow-up commit.

## Legal

JoyStream hosts nothing. It resolves publicly available stream URLs, the same
way a browser would, and stores only your local library. Respect the laws and
rights-holder agreements that apply to you. Upstream MovieBox-Tui is dual
licensed MIT / Apache-2.0; this client follows the same terms.

## Releases & the update chain

| Release | Contents |
| --- | --- |
| `v1.0.0` | First public APK (provider engine ported from upstream `b89065d`) |
| `v1.0.1` | Per-episode resume positions, real titles in history, upstream baseline recorded in `UPSTREAM_CHANGELOG.md` |
| `v1.0.2` | MovieBox mirror failover on every HTTP error, in-app connection test, optional custom API host, clearer empty states |

The in-app updater reads `releases/latest` of this repository, so every release
published here becomes an update offered to installed devices. Releases are
created by CI only — never upload an APK signed with a different key, or the
in-place update will fail with a signature mismatch.

### How upstream propagation works

1. `upstream-sync.yml` polls `mesamirh/MovieBox-Tui` every 6 hours.
2. When the head SHA differs from `version.properties`, it runs
   `tools/bump_version.py`, which increments `versionCode`, bumps the patch
   `versionName`, records the new SHA/date and appends to
   `UPSTREAM_CHANGELOG.md`.
3. The workflow pushes to `main`, then builds, tags `v<versionName>` and creates
   a release with the APKs attached.
4. Installed apps see the new release on their next update check (startup, or
   Settings → Check for updates) and can download/install it in place.
