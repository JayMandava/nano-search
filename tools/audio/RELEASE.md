# Private built-in release audio

This is local code preparation, not evidence that the external input has been
created or that its bytes were inspected in this executor.

The owner-approved recording is **Monza track side**, by **wandererscapes
(Freesound)**, published by **freesound_community** on **2022-07-21**:
https://pixabay.com/sound-effects/film-special-effects-monza-track-side-69992/
It is a generic race-car pass-by, not an authenticated McLaren recording or
endorsement. It is licensed under the Pixabay Content License, not Apache-2.0:
https://pixabay.com/service/terms/ and https://pixabay.com/service/license-summary/.
The full original MP3 stays outside Git. Its SHA-256 and the approved edit are in
`monza-release.json`. The packaged sound is part of the creative clock app; do
not separately distribute its raw/encoded bytes. Secret storage does not grant
additional rights or make audio embedded in a distributed APK confidential.

## Exact proposed external write — requires owner confirmation

- Existing environment: `JayMandava/nano-search` → `production-release`.
- One new **noncredential** environment secret: `NANO_RM_AUDIO_OGG_BASE64`.
- Value: only the approved fixed Ogg encoded as canonical one-line Base64,
  **43,512 ASCII characters**. Do not put it in chat, source, tests or logs.
- Decoded file: **32,632 bytes**, SHA-256
  `4fffd803c5a8a7f6fa40a5ea3dca493b75b191c4a2d0ffa6516b37cc07c77c6e`.
- Profile: Ogg Opus, mono, 48kHz, four decoded seconds, 64kbps CBR.
- No repository/organization-wide copy, credential changes, storage service,
  new approval gate, or changes to the four signing secrets.

The owner/authorized provisioning operator must use the actual approved local
file. This executor does not possess it; no payload has been copied or generated
here. Confirm the environment-only scope through metadata, never by reading a
stored secret value. The owner still performs both existing protected approvals
and the manual workflow dispatch; previous Forbidden access is not bypassed.

## Build path and isolation

Only the `build_unsigned` job's named prepare step references the asset secret.
It runs after the existing owner gate and source preflight. Strict decoding,
length, SHA-256, Ogg CRC/sequence/header, channel/rate, packet-rate and granule
checks run before any output is usable. Errors do not include input data. There
is no download and no CI re-encoding; builds use the byte-identical pinned input.

The verified input is a mode-600 file in a fresh private runner-temporary folder.
Gradle gets its absolute path via `-PnanoReleaseAudioFile=...`, never the secret.
`stageReleaseAudio` accepts only that verified file, generates only
`build/generated/nanoReleaseAudio/res/raw/nano_supercar_rev.ogg`, and is mandatory
for release pre-builds (including bundles and release unit tests). It always
validates anew. No option can override the production hash or silently choose
the public synthetic fallback. Only the release resource source set includes
the generated directory. Debug/minified builds keep the existing synthetic WAV.
The release overlay replaces the same `R.raw.nano_supercar_rev` resource; it must
not be added beside the WAV at equal source-set priority.

The publish build explicitly disables build/configuration caches. No workflow
cache action is configured. After APK collection, an always-run cleanup removes
the input folder and module build tree, including generated/intermediate audio
copies, before upload. The artifact handoff allowlist remains APK + manifest +
release notes (plus checksum text for signed output), never standalone audio.

APK inspection resolves the resource table rather than assuming an unshrunk ZIP
path, requires the exact pinned Ogg stored without ZIP compression for resource-FD
playback, and rejects the synthetic WAV. The same verification runs before and
after signing and before publication. Signing secrets and gates are unchanged.
The existing MediaPlayer attributes, 0.35 playback gain, explicit tap, single
voice, debounce, DND/focus policy and lifecycle cleanup are unchanged. The
accessibility action uses a generic race-car label without claiming ownership.

## Validation boundary

Public CI `validate` and local checks use debug/minified synthetic fixtures;
these are **not** production-audio verification. Production release packaging
fails when the approved private input is missing or wrong. Python tests generate
container fixtures in memory and cannot pass the production SHA-256 pin. They
check structural validation and failure isolation, not audible codec quality.

Still required with the actual fixed Ogg: complete validation in the provisioning
operator's environment, decoded waveform/duration/fade/peak checks, listening to
the compressed app-context preview at the unchanged 0.35 gain, full release tests,
lint/R8/APK checks and eventual device playback. The coordinator's PCM measurements
(-6.57dBFS peak, -19.76dBFS RMS) describe the pre-Opus preview, not a fresh decoded
Opus measurement in this executor. All accepted visual fixtures remain unchanged.
