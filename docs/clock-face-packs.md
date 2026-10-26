# Clock Faces: data-only packs

Settings > Clock Faces manages built-in faces and private installed packs. Installing does **not** apply a face. In its detail screen, choose one existing Home clock or add one to a chosen page; Lock Clock has a separate selection. Home replacement preserves the widget's stable token, size, position and per-instance chronograph identity. Android's **Set as wallpaper…** picker still requires system confirmation. Selecting a face changes an already-active Nano Lock Clock immediately, so the UI warns first.

Imports use Android's document picker, without broad file permissions or a persistent document grant. Imported metadata is unverified and displayed as plain text. Imported and curated packs occupy separate namespaces. Removing a face keeps Home placement and falls back to Steel Dial only where that face was selected; unrelated Home/Lock choices, wallpaper backgrounds and photos remain untouched. Reimporting a removed pack creates a new identity and cannot silently reactivate old selections.

Collection previews are static, rendered off the UI thread. Only a selected detail can opt into a live preview, which stops when not resumed and respects reduced motion, power saving and disabled system animations. Home and wallpaper share the native renderer. A same-ID update refreshes by registry generation plus content digest.

## Availability and provenance

The production catalog currently contains **no published entries**. There is no placeholder download URL or claim that a hosted gallery has shipped. A reviewed pack must be published first; only then can its exact immutable first-party URL, byte count, pack identity/version and SHA-256 be added to `FacePackCatalog`.

`app/app/src/androidTest/assets/clock-packs/orbit-blue.zip` is an original, unbranded CC0 fixture with a reproducible generator in `tools/clock-packs`. It is test-only, not packaged in the app or listed in the production catalog. Local-server download tests inject a test catalog/transport without weakening production URL validation.

No private font, personal overlay, signing key, downloaded model or reference photograph is required for this feature. The public source assets are checked against the exact reviewed public-baseline hash allowlist, not only filename keywords.

## Version 1 format

A ZIP contains exactly `manifest.json`, `LICENSE.txt`, and any explicitly declared PNGs beneath `assets/`. There is no app code, class name, URL, intent, font, SVG, HTML, animation, script, reflection, plugin loading or custom drawing path in a pack. The discarded early prototype schema is not supported.

Required manifest fields are `format` (`nano.clock-face-pack`), `schemaVersion` (1), `packId`, `packVersion`, `name`, `author`, `license`, `minAppVersionCode`, `requires`, `template` (`analog-v1`), `dial`, `hands`, and `assets`. Optional fields are `description` and `dateWindow`. The fixture generator is an executable example of the exact strict schema.

`analog-v1` permits a background color, optional static PNG texture, fixed native ticks/numerals, a small system-font enum, normalized bar/needle hands, optional seconds, and a fixed right/bottom date window. Colors use `#AARRGGBB`. Assets name an exact path and SHA-256. Optional PNG use requires `png-v1`. Version 1 has only one artwork reference (`dial.textureAsset`), so its no-unreferenced-assets rule currently permits at most one texture; the four-image and 8 MiB limits are upper safety ceilings, not a four-texture rendering feature. Unknown fields/capabilities, duplicate keys, numeric coercions, non-finite/overflow values, malformed UTF-8 and display markup/control text are rejected. Pack author/license text is a claim, not a verified endorsement.

This renderer does not provide GMT hands, rotating bezels, chronographs, moon phases or bespoke watch-brand geometry. Those require a separately reviewed native capability, not additional executable pack data.

## Admission and resource limits

| Resource | Limit |
|---|---:|
| ZIP input | 4 MiB |
| ZIP entries | 16 |
| Aggregate expanded payload | 12 MiB |
| Manifest / license | 64 KiB / 32 KiB |
| JSON depth / values | 8 / 1,024 |
| Declared PNG assets | 4 |
| Individual PNG compressed bytes | 2 MiB |
| PNG dimensions / pixels | 1,024 per dimension / 1,048,576 |
| Aggregate RGBA image allocation | 8 MiB per pack |
| Compression ratio | 100:1 with 1 KiB compressed denominator floor |
| Installed packs | 20 |
| Store including staging/retained revisions | 100 MiB |
| Shared decoded-artwork cache | 32 MiB |
| Collection/detail preview cache | 8 MiB |

The extractor validates central and local ZIP records, paths, entry types, overlaps, flags, compression, sizes and CRCs before writing exclusively created staging directories. Links, traversal, encrypted/unsupported entries, ambiguous duplicate names, trailing records and undeclared content are rejected. PNG validation checks signature, chunk ordering/CRCs, zlib/scanlines and allocation bounds; animated PNG and unsupported ancillary content are rejected. Native Android full decode and a real template smoke render must pass before publication. Stored archives/payloads are revalidated on startup, with proven-bad revisions quarantined. Ordinary storage/read failures and memory exhaustion instead show a retryable unavailable state without durably declaring valid data corrupt. A failed registry load reaches a visible error state rather than remaining stuck on loading; explicit collection retry can recover transient failures.

Updates require a higher version and explicit review. Imported update confirmation is bound to the reviewed candidate digest, so changing a document between confirmation and the second read cannot substitute content. Same-version different bytes and downgrades are rejected.

## Storage, cancellation and networking

The app copies sources into private `noBackupFilesDir` staging, validates them, synchronizes the immutable revision, and publishes one durable `AtomicFile` registry pointer. Failures before pointer publication leave the old revision selected. An uncertain pointer write stops mutation/garbage collection until recovery; it never deletes both possible revisions. Rendering leases retain an old revision until its last reader closes. Removal commits a tombstone before preference/file cleanup, and IDs are never reused. Restored preferences without private pack data safely show a missing-face fallback.

Only the app's compiled catalog authorizes downloads. A dedicated OkHttp client sends no cookies or authentication, disables automatic redirects/retries, and applies a total deadline and cancellation watchdog. Production supports exact HTTPS first-party GitHub release or commit-pinned raw URLs and narrowly checked release-asset redirects; it rejects unknown hosts, downgrade URLs, user-info, fragments, traversal and unsupported content. Every completed transfer must match the pinned byte count and SHA-256 before archive admission. No catalog field, import or UI input can configure the production host policy.

An operation is retained over Activity recreation. Back/cancel waits for cleanup; a committed install is reported as installed even if cancellation arrived too late. Closing an unresponsive document provider is performed off the UI thread. No background installer or WorkManager retry is created.

## Verification

Use the public variant and the existing JDK 17 / SDK 36 / NDK 27 / CMake 3.22.1 toolchain:

```sh
cd app
./gradlew --no-daemon --max-workers=2 -PincludePersonalOverlay=false \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew --no-daemon --max-workers=2 -PincludePersonalOverlay=false \
  :app:testReleaseUnitTest :app:assembleRelease :app:assembleMinified
./gradlew --no-daemon --max-workers=2 -PincludePersonalOverlay=false :app:lintDebug
```

On a disposable Android 13+ target, install the matching debug app/test APK pair and run the AndroidJUnitRunner. Android tests exercise the real decoder/template, AtomicFile store, exact Home/Lock preferences, updates/quarantine/recovery, lifecycle/cancellation and original built-in controls/raster behavior. JVM tests use the real strict parser/archive/store logic, fault-injected durable boundaries and a local HTTP server. A portable JVM pass does not substitute for Android native decode or runtime tests.

The `minified` build type is debuggable, so it is **not** equivalent to true release/R8. Validate `release` separately. Without the private signing configuration a local release build is debug-signed and must never be presented as the production-signed update. Existing baseline lint findings are recorded separately; do not add blanket suppressions merely to force green.

Run final tests on the exact candidate revision and record suite counts, APK hashes/certificate, sample hash, lint delta and runtime evidence before publishing. Builds, tests, runtime probes and release publication are separate gates; this document is not a claim that pending gates passed.
