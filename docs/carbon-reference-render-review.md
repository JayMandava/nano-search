# Carbon bridge: annotated rear-support review

The current comparison uses actual Carbon Canvas code against the frozen last
reviewed candidate, `a44fc7f566a355c30867e711e09f8b21e9382a6e`. Frozen a44 dial,
front bridge and rear-support renderers are identifier-only copies, independently
SHA-256 checked. The earlier ed1d39c and public Nano 0.8.1 fixtures are retained.

This is Robolectric 4.17 with API 35 framework classes and **native Skia on the
JVM**, not Android emulator/device evidence. A standalone renderer run is not a
full app build, R8, lint, Android instrumentation or permission to publish/install.

## Annotated request

The white marks identify the broad irregular plates behind the wheels, not the
already approved silver front bridge. The two circles identify only the side
jewels at design coordinates `(275,621)` and `(737,586)`. The unmarked top jewel
and all three counter jewels remain.

Four independently shaped recessed plates and the former radial backing pattern
are replaced by two mirrored pairs of octagonal window frames. Both pairs share
horizontal alignment, 45-degree chamfers and a common normal web thickness; only
their heights follow the available upper/lower spaces. They remain in the cached
base, below the animated wheels. The central engraved arcs remain unchanged.

The approved front bridge source and native pixels must remain exact. Its centre
joins three and nine, not six; the lower counter has its own independent collar.
The chronograph hand crosses the gap but is not a bridge connection. No front
bridge screws are reintroduced. Case, rear plate and counter movement screws,
case/pushers, hands, date, counter geometry, layout, state and font handling remain
outside this revision.

## Reproduce in the normal Android environment

From the `app/` Gradle root with Android dependencies installed:

```sh
./gradlew -PunsignedRelease=true -PincludePersonalOverlay=false \
  :app:testDebugUnitTest :app:testReleaseUnitTest
```

Export is opt-in and requires a **new, nonexistent absolute directory**:

```sh
CARBON_REVIEW_OUTPUT=/absolute/path/to/new-review-directory \
CARBON_REVIEW_REVISION="$(git rev-parse HEAD) plus reviewed working tree" \
./gradlew -PunsignedRelease=true -PincludePersonalOverlay=false \
  :app:cleanTestDebugUnitTest :app:testDebugUnitTest \
  --tests 'ai.nanosearch.launcher.clocks.CarbonBridge*'
```

Captures cover parked, running and illuminated states at 240, 360 and 720 pixels,
fixed to 2026-10-15 10:10:30 UTC. Metadata records the source identity, states,
changed-pixel counts and PNG hashes. Both sides use the actual renderer in the
same process, never retouched artwork.

The front-only diagnostic is intentionally pixel-identical: it proves this rear
revision preserves the approved bridge. It excludes rear supports, jewels, hands
and counter faces. A base-layer diagnostic shows the actual former broad plates
and radial backing versus the actual new rear assembly without wheels/overlay;
it also includes the unchanged case. Neither diagnostic is a whole watch. The
complete-dial images remain the primary design comparison.

## Guardrails

- Frozen fixtures recover exact source hashes after undoing identifier renames.
- Approved front source and actual native raster remain exact against a44.
- Actual front overlay pixels are exact everywhere except the two circled jewel
  patches. Their former opaque purple cores become transparent, not recoloured.
  The unmarked top and all counter jewels retain actual visible purple pixels.
- Full-dial changes are restricted to a fixed old/new rear-panel envelope plus
  the two jewel patches; case/date/counter-interior masks still match exactly at
  240/360/720/1000px in parked/running/lume states. The date uses actual opaque
  pixels from the frozen cached overlay; at240px, 11 outer-bezel antialias pixels
  mix with the intentionally changed rear material. Those fringe pixels are not
  called opaque date content. The whole isolated overlay, including its bezel,
  remains exact. No numeric colour tolerance is used. Hands, seconds and host geometry
  are independently compared exactly.
- Mirrored rear paths must agree exactly, have the common web thickness and
  chamfers, and retain genuinely open windows. Rear paths avoid all apertures and
  the centre-to-six gap. Actual layer order remains base, wheels, front overlay.
- The front path retains exactly two four-neighbour components, clear apertures,
  the original lower collar and a paint-transparent neck gap. Former front screw
  centres still equal bare opaque material; ed1's old screw-bearing renderer is
  retained as a positive control for those pixel checks.
- Painting is deterministic and preserves Canvas transforms, clipping and save
  stack. Returned paths cannot share mutable state.

The Android instrumentation geometry source mirrors the front topology, but was
not executed by this standalone renderer workflow. No current test validates
live-wallpaper/widget hosts, touch routing, screen-off lifecycle, system Clock
launch, OEM behaviour, frame timing or the private local font. Prior releases'
full-app/device results must not be attributed to this draft.
