# Maps for Pebble

Google Maps walking and cycling directions mirrored to a Pebble Time 2 with a phone-rendered map. Two halves: `android/` is the Kotlin companion, `watchapp/` is the C watchapp built with the Core Devices Pebble SDK. `scripts/emu-send.py` drives the watchapp in the emulator without a phone, `docs/` explains the design.

# Toolchain

Every version is pinned and asserted where it can be. Change a pin in one place and the assertion next to it.

| Piece | Version | Where |
|-------|---------|-------|
| JDK | 21 | `jvmToolchain(21)` in `android/app/build.gradle.kts`, asserted at the start of every CI job |
| Android Gradle plugin | 8.13.2, `compileSdk` 37 | `android/build.gradle.kts`, `platforms;android-37.0` in CI |
| Kotlin | 2.4.10 | `android/build.gradle.kts`, required by PebbleKit Android 2 1.3.0 |
| ktlint | 1.8.0 through plugin 14.2.0 | `android/app/build.gradle.kts`, style in `android/.editorconfig` |
| Kover | 0.9.9 | `android/app/build.gradle.kts` |
| pebble-tool | 5.0.40 | `PEBBLE_TOOL_VERSION` in `.github/workflows/ci.yml`, asserted after install |
| Pebble SDK | 4.33.1 | `PEBBLE_SDK_VERSION` in `.github/workflows/ci.yml` |
| pre-commit | 4.6.2 | the `check` CI job, hook revisions pinned to commits in `.pre-commit-config.yaml` |
| GitHub Actions | full commit SHAs | every `uses:`, kept current by dependabot |
| gitleaks | 8.30.1 | `GITLEAKS_VERSION` and its tarball checksum in the `secrets` job of `.github/workflows/ci.yml` |

# Code

- The user-facing name is Maps Navigation for Pebble, shown as Maps Navigation on the watch and the phone. Identifiers, packages, artifact names and the repository use the code name Maps for Pebble (`maps-for-pebble`, `org.peblum.mapsforpebble`, `MapsForPebble`). Never put the display name in an identifier or the code name in a string a user reads.
- No comments. Names, tests and the merge request description carry the reasoning. The one exception is a workaround for a specific external bug, stated in one line.
- No em dashes and no semicolons in prose (README, docs, strings, workflow names, merge requests). Sentence case headings.
- Kotlin is formatted by ktlint in the `ktlint_official` style with 140 columns, C by clang-format from `.clang-format`, Python by ruff from `ruff.toml`. The pre-commit hook and the `check` job run all of them, plus actionlint on the workflows.

# Error handling

- The companion lives in the notification listener process, which must never crash. Every entry point catches: the listener callbacks, the event loop (one `try` per event), the frame loop (one per frame), tile downloads, the watch IPC (`WatchLink.request` wraps the binder call and races it against a timeout because the PebbleKit binder call cannot be cancelled). Failures degrade to no map or no watch update, never to wrong data: a frame carries its id and zoom so the watch never shows a stale frame as current.
- All navigation state changes go through `Navigator`'s single event queue under the `session` mutex: notification posts and removals, service lifecycle, the demo. Posts for the same notification key are folded while draining. Nothing mutates `navState`, `navKey`, `serviceState` or `zoomOverride` outside that lock. Location fixes and watch messages only set volatile fields and request a frame.
- The watchapp drops a malformed message with an `APP_LOG` warning and keeps the last good state. Every allocation is checked for `NULL` and logged. A frame is only shown once every chunk arrived in order.

# Boundaries

- The packages `nav`, `map` and `pebble` are pure JVM code apart from the named exceptions (`GoogleMapsNotification`, `MapRenderer`, `TileStore`, `WatchLink`, `WatchListenerService`). Keep Android types out of the pure classes: bit operations instead of `android.graphics.Color`, `IntArray` pixels instead of `Bitmap`, so they stay measurable. Kover's exclusion list in `android/app/build.gradle.kts` is the boundary and is gated at 100% lines, instructions and branches.
- The phone and watch protocol is defined twice on purpose, in `watchapp/src/c/protocol.h` and `android/.../pebble/Protocol.kt`, with the table in `README.md` and the harness `scripts/emu-send.py` as the third and fourth copies. A protocol change touches all four in the same merge request.
- Time comes from `System.currentTimeMillis()` on the phone and `clock_copy_time_string` on the watch. Status text is formatted with `Locale.US` so logs stay parseable.

# Checks

Locally, `pre-commit install` once per clone, then every commit runs the formatters, the linters, `ktlintCheck`, the Android unit tests and `koverVerifyDebug` (the last two only when `android/` changed). The watchapp build needs the SDK, so it runs in CI only.

CI (`.github/workflows/ci.yml`): `check` and `secrets` first, then `android` (build, tests, coverage, APKs and the coverage report as artifacts) and `watchapp` (pbw as an artifact), then `measure` writes the artifact sizes to the job summary without gating. `codeql.yml` analyses Kotlin, Python and the workflows. `release.yml` runs on `v*` tags: it checks the tag against `watchapp/package.json`, builds, attaches the assets to a GitHub release and publishes the pbw to the appstore listing when `REBBLE_APP_ID` and `REBBLE_ACCESS_TOKEN` are set. `pages.yml` deploys `site/` to GitHub Pages and, in its `dns` job, makes sure Cloudflare holds `maps.peblum.org CNAME peblum.github.io`, unproxied with automatic TTL, when `CLOUDFLARE_DNS_TOKEN` is set.

# Testing

- JVM unit tests under `android/app/src/test` cover the pure code. A new pure function gets its tests in the same change, or the coverage gate fails.
- `scripts/emu-send.py --launch --demo --pattern` exercises the watchapp in the emery emulator, `--frame` replays a frame captured from the companion's cache directory.
- On a device: install the debug APK, grant the three permissions, start directions in Google Maps, read the status section of the app and `adb logcat -s Navigator TileStore`.
