# Clock audio profiles

Official 0.8.5 release builds require the separately licensed, privately provisioned
Monza recording. See [release provisioning](RELEASE.md). The recording is not
covered by this repository's Apache-2.0 license. No stock binary is stored here.
Ordinary debug/minified builds retain the public synthetic fixture below.

## Original developer-fixture rev

`nano_supercar_rev.wav` is an original deterministic synthesis for Nano's explicit
upper-left Carbon pusher. No sampled recording, downloaded audio or model is used.
It is V8/supercar-inspired, not an authentic McLaren recording or affiliation.
The generator uses an RPM envelope, paired harmonic exhaust pulses, induction tone
and seeded filtered turbulence, with quiet endpoints and fades. Source is covered
by this repository's license.

```sh
python3 tools/audio/synthesize_supercar_rev.py \
  --output app/app/src/main/res/raw/nano_supercar_rev.wav \
  --preview /absolute/path/Nano-original-supercar-rev-preview.wav
```

Asset: mono 48 kHz / 16-bit PCM WAV, 2.65 seconds, peak about -3.74 dBFS.
App playback gain is 0.35; the separately rendered preview applies that same gain
(peak about -12.86 dBFS, RMS about -19.87 dBFS). Playback still follows the device's
media volume. WAV SHA-256:
`ae92fdbfe61629238b0f1e89cedbfb5f1d64d374a022a612d06d67ecc2da3586`.
Preview SHA-256:
`86c7843aa981c37a97f426fc6114007cfab53eb8225847e931ee838409c75036`.

No autoplay: resource preparation occurs only after an explicit valid pusher tap,
audible media volume, DND-off policy and granted transient audio focus. One process
voice is allowed; busy taps are ignored rather than queued/restarted. Hiding or
tearing down the host, or losing focus, stops and releases playback. Android may
reject focus for a background wallpaper host; the action then remains silent,
with no foreground-service or permission workaround.
