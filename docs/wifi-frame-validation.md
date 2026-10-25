# Validate the Settings Active Frame's Wi-Fi status

This fix reads the Wi-Fi **radio state**, not internet availability. An enabled
radio must say "Wi-Fi on" even when it is not connected to a network. A failed
read must never be presented as "Wi-Fi off".

## Android build prerequisites

Use a Linux or macOS Android development environment with the prerequisites in
the README: JDK 17 (including `javac`), Android SDK platform 36, NDK 27, and
CMake 3.22 or newer. Use the checked-in Gradle wrapper. Set `sdk.dir` in
`app/local.properties` to the installed SDK; set `cmake.dir` there if necessary.
Keep any existing local settings. The build also needs the pinned Git
submodules and access to its ordinary Gradle/Maven dependencies.

No release signing key, model download, personal data, or connected phone is
needed for the debug build. The project's native GPU task is a `preBuild`
dependency even though this fix is in Kotlin; do not skip that task and claim
a complete build.

## Portable build verification

From the repository root in the prepared environment:

```sh
git submodule update --init --recursive
cd app
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

This builds and checks the app; it does not install it, change networking,
publish an APK, or create a release. Check the command's exit status and retain:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`
- `app/build/outputs/apk/debug/app-debug.apk`

The Wi-Fi regression suite contains 14 tests in `WifiStateTest`,
`FrameRefreshStateTest`, and `WifiManifestTest`. It covers radio-state labels,
missing services and read failures, the source manifest permission, queued
refreshes, and swipe-delayed draws. Passing these tests is not an Activity or
device integration test.

With Android SDK command-line tools available, inspect the **built APK** as
well as the source manifest (run from `app/`):

```sh
apkanalyzer manifest permissions app/build/outputs/apk/debug/app-debug.apk
```

Confirm `android.permission.ACCESS_WIFI_STATE` is present. The fix must not
add `android.permission.CHANGE_WIFI_STATE`, a runtime location request, or a
nearby-device permission request.

## Separate runtime smoke check

Run only on an authorized test device/environment. Do not change a user's or
another person's live network connection as part of unattended verification.
The APK contains arm64 native libraries; choose a compatible device.

- Open Settings once, then return to the launcher and its Active Frames page.
- Compare the card with Android's Wi-Fi control: enabled and connected shows
  "Wi-Fi on"; enabled but disconnected also shows "Wi-Fi on"; disabled shows
  "Wi-Fi off". Permission/service failures should show "Wi-Fi status unavailable".
- In an explicitly authorized network-transition test, verify "Wi-Fi turning
  on" / "Wi-Fi turning off" while the corresponding transient state is reported.
- Verify a state change while the launcher remains visible updates the card
  after Quick Settings is dismissed, without requiring an unrelated notification.
- Leave and resume the launcher repeatedly; verify the latest state appears
  and there are no receiver-registration or cleanup crashes.
- Navigate away and back, swipe while a refresh completes, and trigger a newer
  state during an existing read. The final state must win; cards should not be
  rebuilt mid-swipe or remain stuck at the older value.
- Confirm the battery/storage lines and Settings tile's tap action still work.

Record the commit, APK checksum, device/Android version, and each check's
actual result. Mark unavailable stages as **not run**, not passed. Never
uninstall an existing personal installation just to resolve a signing mismatch.

## Mac and wireless-phone validation — 2026-10-25

Applied the supplied combined patch to base `31a2951044346019dfdfdb8b9a7ed0e84aa00d8e`
in the isolated local branch `fix/wifi-active-frame-validation`. The original
checkout remained clean. No push, release, merge, uninstall, or data clear occurred.

Environment: JDK 17, Android SDK 36, NDK 27.0.12077973, Homebrew CMake,
and the repository's exact pinned native dependencies. The host shader compiler
initially selected an incompatible Command Line Tools SDK. Setting `SDKROOT`
to the SDK bundled with the selected Xcode 26.6 compiler resolved that local
linker error; no build task was skipped and no source workaround was needed.

| Check | Actual result |
| --- | --- |
| `:app:testDebugUnitTest` | Passed: 14 tests, zero failures/errors/skips. |
| `:app:assembleDebug` | Passed, including CPU and Vulkan native builds. Final tests/build command exited successfully. |
| `:app:lintDebug` | Failed: 5 errors, 254 warnings, 1 hint. A separate clean-base lint run returned identical error messages and counts. |
| Built APK permissions | `ACCESS_WIFI_STATE` present; no `CHANGE_WIFI_STATE`, location, or nearby Wi-Fi permission added. |
| Signing compatibility | Built and installed APK certificates matched; SHA-256 `ec389e10ff92d8ed3337f8b270f612d6dc6329e023d869657514437c34116d38`. |
| Device | Existing authorized wireless OnePlus CPH2467, Android 17 / API 37, arm64-v8a. |
| Update install | `adb install -r` succeeded, updating 0.6.0 (9) to 0.6.1 (10); Wi-Fi read permission granted. |
| Bug reproduction | Before update: OS Wi-Fi enabled, Settings card displayed “Wi-Fi off.” |
| Fixed current state | After update: OS Wi-Fi enabled, Settings card displayed “Wi-Fi on.” Cropped before/after screenshots retained. |
| Settings action and resume | Three Settings-card launch/return cycles opened Android Settings and returned to “Wi-Fi on.” No settings changed. |
| Page navigation/swipe refresh | Five Home/Active Frames round trips each ended on the visible “Wi-Fi on” card. |
| Process/crash smoke check | Nano PID remained 28226 across the repeated checks; zero Nano process entries in the device crash buffer. |
| Battery/storage | Both remained visible in the cropped Settings card. |

APK SHA-256:
`2d56acccfead2fdacb040eb956f7f7386e4764a35dab27aba9903ac707c18f79`.

The five pre-existing lint errors are `MissingPermission` in `LocalVoice.kt`,
`UnusedCrossfadeTargetStateParameter` in `SettingsActivity.kt`, and manifest
`ProtectedPermissions`, `PermissionImpliesUnsupportedChromeOsHardware`, and
`QueryAllPackagesPermission`. No lint baseline or suppression was added.

Not run on the phone: disabled radio, enabled-but-disconnected radio, real
enabling/disabling transitions, a Wi-Fi broadcast caused by a real toggle, or
a newer radio state arriving during a read/swipe. Those require the user to
operate Wi-Fi or an independent test connection, because this phone's wireless
ADB depends on Wi-Fi. The unit tests cover the state mapping and refresh
bookkeeping, but do not replace those integration scenarios. No network,
airplane-mode, security, pairing, or permission-grant settings were changed.

Only whitelisted navigation/status UI labels were retained. Screenshots were
cropped to the confirmed Settings card; no adjacent message content was saved.
