## What this is

A **native Android app** (Kotlin + Jetpack Compose, min SDK 24) that ports the
[MovieBox-Tui](https://github.com/mesamirh/MovieBox-Tui) provider engine to a
touch-first movie-watching client — plus the automation that makes it
**check for updates and auto-update whenever the upstream repo changes**.

Builds green on this branch: unit tests, `assembleDebug`, `assembleRelease`,
APK signature/metadata verification. Releases `v1.0.0`, `v1.0.1`, `v1.0.2` exist
with signed APKs attached.

## The two update layers

```
mesamirh/MovieBox-Tui ──(new commit)──► upstream-sync.yml (cron 0 */6 * * *)
                                          │ sha differs from version.properties?
                                          │ tools/bump_version.py  → versionCode+1, patch bump
                                          │ build both APKs → tag vX.Y.Z → publish Release
                                          ▼
                        github.com/TR4842/JoyStream/releases/latest
                                          │ checked at launch + Settings → Check for updates
                                          │ (GitHub API vs BuildConfig.VERSION_NAME)
                                          ▼
                        UpdateController → PackageInstaller session → installs in place
```

- `upstream-sync.yml` — detects upstream movement, bumps the version, builds,
  tags and releases. Skips everything when the SHA is unchanged.
- `UpdateRepository` / `UpdateController` — GitHub Releases check, APK download
  with progress, `PackageInstaller` session, unknown-sources permission flow,
  external-installer fallback, 30-minute cache.
- Settings shows both layers: app version vs. latest release, and upstream head
  vs. the SHA this build was compiled from (`isNewerThanBuild`).

## Feature parity with the TUI

| Upstream (TUI) | JoyStream |
| --- | --- |
| Streaming movies/series/anime/dramas | Same catalog via signed MovieBox BFF API + Dramachi |
| Resolution picker (4K…Auto) | Source sheet: quality, DASH vs MP4, codec, size; `Settings.QUALITY_OPTIONS` cap |
| External players | Full-screen Media3/ExoPlayer, PiP-ready activity, subtitle side-loading |
| Resumable batch downloader | Foreground service, `.part` + HTTP Range resume, pause/resume/cancel |
| Live TV (M3U + EPG) | M3U import by URL/file/share intent, grouped + searchable channels |
| Local-only history/favourites | `LibraryStore` (JSON on disk), continue-watching, per-episode resume |
| Zero telemetry | No analytics, no network calls besides the providers and GitHub Releases |

## Bug fixed since the first builds (from a user screenshot)

`MovieBox: MovieBox responded with HTTP 404` — the client aborted on the first
non-retryable status instead of failing over. Now **every** non-2xx walks the
mirror list; the error reports the status and which hosts were tried. Added
*Settings → Test connection* (per-mirror health check), an optional custom API
host, and empty states that distinguish "provider off" from "catalog
unreachable" instead of blaming the user's settings.

## Review notes

- `keystore/joystream-dev.jks` is a **development** signing key (password
  `joystream`) used only when the four `JOYSTREAM_*` repo secrets are absent, so
  CI produces installable, self-updateable APKs out of the box. Supply the
  secrets to sign with a private key.
- `gradle/wrapper/gradle-wrapper.jar` is committed because the sandbox could not
  reach Gradle's distribution hosts; the wrapper launchers are minimal and exec
  `GradleWrapperMain` as usual.
- CI posts build failures and APK verification as commit comments (run logs are
  unreadable in this environment).

## After merge

`main` becomes the default-branch source for the cron, so the 6-hourly upstream
poll and the in-app update feed go live. Nothing else is required.
