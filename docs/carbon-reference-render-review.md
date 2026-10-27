# Carbon bridge: local renderer review

This review harness compares the actual Carbon Canvas renderer with the frozen
public Nano 0.8.1 renderer (`d515470860d410b127df088b571fd41ea7d3da8d`). It uses
Robolectric 4.17, API 35 framework classes and **native Skia graphics on the JVM**.
It is not an Android emulator, physical-device screenshot, integration test or
approval to publish/install a new version.

## Reproduce

From the `app/` Gradle root, with JDK 17 and the normal Android build dependencies:

```sh
./gradlew -PunsignedRelease=true -PincludePersonalOverlay=false \
  :app:testDebugUnitTest :app:testReleaseUnitTest
```

The capture export test skips unless explicitly enabled. For actual before/after
PNGs, select a **new, nonexistent absolute directory** (it refuses to overwrite
evidence):

```sh
CARBON_REVIEW_OUTPUT=/absolute/path/to/new-review-directory \
CARBON_REVIEW_REVISION="$(git rev-parse HEAD) plus reviewed working tree" \
./gradlew -PunsignedRelease=true -PincludePersonalOverlay=false \
  :app:cleanTestDebugUnitTest :app:testDebugUnitTest \
  --tests ai.nanosearch.launcher.clocks.CarbonBridgeNativeCaptureTest
```

The PNGs cover parked, running and illuminated states at 240, 360 and 720 pixels,
fixed to 2026-10-15 10:10:30 UTC. `metadata.json` records the state, source identity,
changed-pixel count and SHA-256 of each PNG. Both sides are drawn in the same
process with the same native graphics engine; neither side is an image mockup.

## Guardrails

- Frozen baseline source is checked byte-for-byte by SHA-256 after reversing
  identifier-only renames. Do not update fixtures to make a regression pass.
- The changed bridge must have one four-neighbor-connected material region at
  240, 360, 720 and 1000 pixels, not merely tangent or diagonal contacts.
- Counters, date and central pivot retain clear apertures; the bridge stays
  inside the unchanged dial envelope. Every fastener must have a complete seat.
- Protected case/pusher, date and counter-interior pixels must match 0.8.1 exactly
  in all three states at 240, 360, 720 and 1000 pixels. These masks are derived from
  frozen geometry, not adapted to the new image.
- Isolated time hands and the separate seconds layer must match exactly.
- Repeated drawing must be deterministic and preserve Canvas transform, clip and
  save stack; construction must not return shared mutable Path objects.

The six small flush anchor positions intentionally follow the new straight
spars. The Android instrumentation geometry test mirrors these coordinates; its
presence does not mean it was run on Android for this refinement.

No tests here exercise touch routing, screen-off lifecycle, system Clock launch,
live-wallpaper hosts, OEM behavior, frame timing or the private local font.
Existing device evidence belongs to the previous release, not this new draft.

## Reference-driven geometry, without a layout redesign

The refinement changes only `CarbonBridgeArt.kt` in production. The upper spars
spread outward into the counter shoulders; the lower legs are straight tapered
rails rather than swept, rounded feet. Smaller flush fasteners and an opaque
satin-graphite face with a clipped silver edge keep the material distinct from
the unchanged bright hand and counter details.

The central collar's upper facet is at design y=414, 86 units above the y=500
hand spindle. An initial 110-unit arch was visibly too tall against the supplied
photographs, so this shorter, wider facet was reviewed instead. One compact
lower shoulder replaces two distracting outlined triangular braces. All register
centers, date geometry, case, pushers, host layout, time/chronograph behavior and
fonts remain unchanged. The source is original procedural artwork, not a
simulation or reconstruction of the photographed movement.
