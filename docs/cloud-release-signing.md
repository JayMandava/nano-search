# Cloud release build and private signing handoff

Nano 0.8.1 uses versionCode 16. Build the official package's **unsigned release input** in the cloud without accessing private signing configuration:

```sh
cd app
./gradlew -PunsignedRelease=true -PincludePersonalOverlay=false :app:assembleRelease
```

`unsignedRelease` accepts exactly `true` or `false`; an invalid value fails configuration rather than silently selecting a signer. With `true`, the build script does not resolve, probe or read `NANO_KEYSTORE_PROPERTIES` or the home-directory `keystore.properties`, and the `release` build has no signing configuration. Expected output is `app/app/build/outputs/apk/release/app-release-unsigned.apk` from the repository root; confirm the generated output metadata rather than selecting a stale APK by wildcard.

This remains the non-debuggable release variant with R8 and resource shrinking. It uses the official `ai.nanosearch.launcher` application ID, not the optional debug QA identity. Private `src/personal` overlays remain excluded from release regardless of the overlay flag; passing `false` also makes the cloud intent explicit. The isolated `-PclockQaBuild=true` debug profile is unchanged. The `minified` testing variant remains debug-signed and is not the publication artifact.

Before handoff, record the source commit and APK SHA-256; inspect the manifest for versionName `0.8.1`, versionCode `16`, official package ID and non-debuggable status. Verify public asset provenance and inspect the actual artifact for excluded private assets. Confirm the unsigned APK has no signer; it is not yet installable as a production update.

The separately authorized signing step must use the existing release identity where its private key already resides. Do not transfer signing keys, passwords, private fonts or personal overlays into the cloud. Align the APK before signing if alignment is needed; modifying it after signing invalidates the signature. After signing, verify the signer against the established release certificate, the APK signature and alignment, and record the signed artifact's separate SHA-256 before publishing. See the official [apksigner reference](https://developer.android.com/tools/apksigner).

Without `-PunsignedRelease=true` (or with `false`), the existing developer behavior is unchanged: load the external signing configuration when present, otherwise sign release with the debug key. That fallback is useful for developer builds but must not be confused with the official production-signed update.
