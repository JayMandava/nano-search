# Original face-pack fixture

`generate_sample.py` creates **Orbit Blue**, an original, unbranded `analog-v1`
clock-face pack. The manifest and small programmatically drawn PNG are dedicated
to CC0-1.0 in the included `LICENSE.txt`. There are no supplied watch images,
logos, private fonts, external assets, scripts, or executable content.

## Reproduce

From the repository root, using Python 3 (standard library only):

```sh
python3 tools/clock-packs/generate_sample.py
sha256sum app/app/src/androidTest/assets/clock-packs/orbit-blue.zip
```

An optional output path is accepted as the first argument. ZIP timestamps and
attributes are fixed, entries use STORED compression, and PNG zlib data uses an
explicit uncompressed block, so generation does not depend on compression-library
version or wall-clock time. Output contains only `manifest.json`, `LICENSE.txt`,
and `assets/texture.png`.

Pinned fixture identity:

- pack ID `orbit-blue`, version `1`, display name `Orbit Blue`
- 14,303 archive bytes; 13,969 expanded payload bytes; 16,384 decoded RGBA bytes
- SHA-256 `f12204deb3d998843c77048be0f7e5803307246d49bba047aa017e8ac1e0363f`
- `minAppVersionCode: 14`, requires `analog-v1` and `png-v1`

## Test usage and published demo

The generated ZIP is an Android **test** asset; obtain it with the instrumentation
context's assets, not the target application's assets. Supply its bytes to a
loopback-only test server and construct an explicit test catalog entry with the
pinned length/digest above. Only a test-injected downloader transport may permit
loopback HTTP; production downloads must retain HTTPS, host/redirect and
certificate validation. Do not put a placeholder URL into the production catalog.

Exercise download → bounded archive/PNG validation → native Android decode →
transactional store → renderer, then wrong length/digest, interrupted transfer,
cancellation, duplicate/update, restart, and removal cases. The exact source ZIP
must be retained outside the published immutable payload for authenticated
read-only recovery validation.

For manual testing, this ZIP can be selected via the import UI after copying it
into an emulator/device's Downloads folder. Copying it is not evidence that an
import, render, Home selection, or independent Lock selection passed: run and
record those checks separately. This exact fixture was published as **Orbit Blue (demo)** at commit
`19072b6ed38b944142b1aaf7b4f1f3d87a00a3a6` and is now pinned in the production
catalog. Local tests remain separate from that real public download; they do not
claim that a local server is a public delivery service.

## Security profile notes

The strict schema is **not** compatible with the discarded early parser draft.
It uses normalized hand geometry, declared/hash-bound PNG assets, required author
and license metadata, and an explicit capability list. Only `dial.textureAsset`
references artwork in this version: do not invent gears, preview, overlay, SVG,
font, or script fields. Unknown JSON fields, unreferenced files, and archive
features outside the validator's deliberately narrow ZIP profile are rejected.

The fixture's portable archive validation and read-only recovery checks were run
against production Kotlin code on the JVM. Android bitmap decoding and actual
rendering must be established by the Android instrumentation/runtime test results;
portable validation does not claim those results.
