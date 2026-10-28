# Cloud release build and private signing handoff

Nano 0.8.2 uses versionCode 17. Build the official package's **unsigned release input** in the cloud without accessing private signing configuration:

```sh
cd app
./gradlew -PunsignedRelease=true -PincludePersonalOverlay=false :app:assembleRelease
```

`unsignedRelease` accepts exactly `true` or `false`; an invalid value fails configuration rather than silently selecting a signer. With `true`, the build script does not resolve, probe or read `NANO_KEYSTORE_PROPERTIES` or the home-directory `keystore.properties`, and the `release` build has no signing configuration. Expected output is `app/app/build/outputs/apk/release/app-release-unsigned.apk` from the repository root; confirm the generated output metadata rather than selecting a stale APK by wildcard.

This remains the non-debuggable release variant with R8 and resource shrinking. It uses the official `ai.nanosearch.launcher` application ID, not the optional debug QA identity. Private `src/personal` overlays remain excluded from release regardless of the overlay flag; passing `false` also makes the cloud intent explicit. The isolated `-PclockQaBuild=true` debug profile is unchanged. The `minified` testing variant remains debug-signed and is not the publication artifact.

Before handoff, record the source commit and APK SHA-256; inspect the manifest for versionName `0.8.2`, versionCode `17`, official package ID and non-debuggable status. Verify public asset provenance and inspect the actual artifact for excluded private assets. Confirm the unsigned APK has no signer; it is not yet installable as a production update.

The separately authorized signing step must use the existing release identity where its private key already resides. Do not transfer signing keys, passwords, private fonts or personal overlays into the cloud. Align the APK before signing if alignment is needed; modifying it after signing invalidates the signature. After signing, verify the signer against the established release certificate, the APK signature and alignment, and record the signed artifact's separate SHA-256 before publishing. See the official [apksigner reference](https://developer.android.com/tools/apksigner).

Without `-PunsignedRelease=true` (or with `false`), the existing developer behavior is unchanged: load the external signing configuration when present, otherwise sign release with the debug key. That fallback is useful for developer builds but must not be confused with the official production-signed update.

## Protected GitHub Actions route

The workflow in `.github/workflows/release.yml` separates unsigned
builds from production signing on fresh GitHub-hosted jobs. No environment
protection, signing secret, workflow dispatch, tag or release is created by adding
these files. The existing local-signing route remains available. The production
key has not been read or transferred during preparation.

The reference is `JayMandava/bold-keys` at
`cde20679729fce28cbc1ca90ec42045c841945fa`. Nano uses its nested `app/` Gradle root,
public-only flags, pinned SDK 36 / Build Tools 35 / NDK 27.0.12077973 / CMake 3.22.1,
and its exact ten-asset SHA-256 allowlist. All five ARM64 native libraries are
checked for 16 KB ELF alignment; APK alignment, signature absence/presence,
package, version, release debug flag and artifact hashes are checked independently.

`validate` runs both JVM suites, both explicit lint variants and unsigned R8
assembly, then inspects the APK locally. It uploads no APK and reads no signing
credentials. Three former errors are fixed in the local review: explicit microphone
permission-denial handling, a captured Crossfade theme, and optional telephony.
The remaining `ProtectedPermissions` and `QueryAllPackagesPermission` findings were
reviewed as intentional declarations. The owner explicitly approved one
`tools:ignore` on each corresponding permission leaf, with rationale comments and
`ManifestLintPolicyTest` enforcing exactly those two declaration/check pairs.
These are two deliberate, disclosed exclusions; the permissions and their behavior
are unchanged. There is no manifest-wide ignore, baseline, Gradle check disable or
`continue-on-error`. All other/new/fatal lint findings continue to block release.
The broad-visibility declaration still requires separate necessity/declaration
review before Play distribution; this lint policy is not Play approval.

`publish` requires the reviewed full main commit, matching workflow commit, exact
source version and version code greater than the latest published source. It
rejects existing tags/releases and rechecks main after approval and before
publication. Reviewed `docs/releases/X.Y.Z.md` is mandatory. A partial publication
stops for manual inspection; there is no force, overwrite or automatic retry.

Public-repository Actions artifacts are downloadable by signed-in readers.
Consequently, `publish` first requires owner approval on the unsigned-build job,
authorizing the intended release and this unsigned artifact handoff. A second
protected approval gates signing after the APK digest is available. Both jobs use
`production-release`; secrets are referenced only by the isolated signing step.
Never treat Actions artifacts as private pre-release key or APK storage. The
signed APK is reverified before it is uploaded and again before creating a tag,
draft release and final published release. Only the publication job gets content
write permission. No Gradle/build code runs in the signing job, and no production
key is provided to build, setup, artifact download or publication steps.

## Independent v2 verification

Signed validation first uses `apksigner verify` with the actual manifest SDK range
and requires exactly the pinned production signer. It then verifies the same APK
separately with `--min-sdk-version 24 --max-sdk-version 27`, requiring a successful
v2 result and exactly the same pinned signer. This checks the v2 signature itself;
it does not change the app's minimum SDK (33) or its signing options. A missing or
invalid v2 signature still blocks publication.

With a minimum SDK of 28 or newer, Android's verifier can find v3 and skip v2.
Thus the default verbose `v2: false` result alone cannot establish v2 absence.
See the [Android verifier source](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/ApkVerifier.java)
and [apksigner verification options](https://developer.android.com/tools/apksigner#verify).

## Gate setup approval and current blocker

The owner approved this exact gate: sole reviewer `JayMandava`,
`prevent_self_review=false` (the sole owner can approve their own dispatch),
`can_admins_bypass=false`, and selected deployment policy allowing only `main` of
type `branch`, with no tag or wildcard policy. Preserve other unrelated settings.
The first official GitHub CLI GET of Nano's environment returned `Forbidden`, so
its state is unverified and no mutation was attempted. Do not retry through a
different credential or route.

Minimal manual action: repository **Settings > Environments > production-release**
(create only if absent); require `JayMandava`, leave **Prevent self-review** off,
disable admin bypass, and under selected deployment branches/tags allow only
branch `main`. Save and verify the configuration. Do not add secrets in this step.
The workflow checks this policy before scheduling an environment job so a missing
environment cannot silently be created as an unprotected signing gate.

## Existing key provisioning and verification boundary

The owner selected the protected GitHub production route and reports manually
provisioning all four environment secrets below. This is owner-reported setup,
not independent confirmation of their contents, scope or successful signing.
No key material has been read by this cloud task. GitHub signing must still pass
the owner gate and the pinned-certificate verification. No Personal Vault, new
debug key or private signer repository is part of this production route.

The owner manually provisions the **existing Nano
production** key into GitHub **Nano repository > production-release environment
secrets**, never repository-wide/organization-wide secrets or source files:

| Environment secret | Existing local source |
| --- | --- |
| `NANO_RELEASE_KEYSTORE_BASE64` | Base64 of the existing `storeFile` keystore |
| `NANO_RELEASE_STORE_PASSWORD` | Existing `storePassword` |
| `NANO_RELEASE_KEY_ALIAS` | Existing `keyAlias`; actual alias is not public |
| `NANO_RELEASE_KEY_PASSWORD` | Existing `keyPassword` |

The owner resolves these locally from `NANO_KEYSTORE_PROPERTIES` or the existing
`~/.nano-search-keystore/keystore.properties`. They must verify the alias is the
approved existing private-key entry and has certificate SHA-256
`1f08d7d9aa073e5d7c4f2c9fd8caca761fc42f108c05e7a5ac88ea9dbc5332ce`.
Do not generate or rotate a key, guess an alias, paste values into chat, or copy
private fonts/personal overlays. If the container includes unrelated keys, stop
for a separate scoped transfer plan. Base64 is encoding, not encryption.

Review secret-name metadata to ensure all four exist only in this environment and
that same-named broader secrets cannot act as GitHub's fallback. The workflow
cannot infer a secret's storage scope from its injected value. Only after owner
review should the workflow be pushed/merged and a specific release dispatched.
Signing credentials are task-readable in the approved signing step; this is a
cloud copy of the production identity, not hardware-backed signing. Compromise
could allow malicious updates; deleting a secret cannot revoke an already copied
key. Temporary files/diagnostics are removed on exit and by an always-run cleanup,
but no claim of forensic secure erasure is made.

The approved RM history ending at `f1c96f85dedc8f1bcb1167afa689d4c3778737c6`
is included with all four original commit objects and metadata preserved. Each
source-text patch and resulting Git tree was independently hash-verified before
integration. Native renderer/JVM checks are separate from Android device, host
lifecycle or phone QA; no new phone execution is claimed.
