# Carbon bridge: local renderer review

The current comparison is the actual Carbon Canvas renderer against the frozen
previous review candidate, `ed1d39cd871b81ee0ad84b86dd68e777af3ffd78`. The earlier
Nano 0.8.1 fixtures remain unchanged and independently hash-checked. New captures
use **ed1d39c versus the revised candidate**, not the released 0.8.1 image.

The harness uses Robolectric 4.17, API 35 framework classes and **native Skia
on the JVM**. It is not an Android emulator, device screenshot, integration test
or approval to publish/install. A focused standalone renderer run must not be
reported as a full app build, R8, lint or Android instrumentation pass.

## Requested revision

The final clarified design intentionally removes the central octagon's short
connection to the six-o'clock counter. The centre still joins three and nine;
the lower counter retains its own independent collar/support. The chronograph
hand still crosses that dark gap: a hand is not a bridge connection.

The front bridge has no visible screws, seats or enlarged bosses. Case, rear
plate and counter movement screws are deliberately retained. Rear supports use
one repeated concentric/radial pattern registered to the existing wheel centres,
with a common angular datum, rib width and slot rhythm. They render in the static
base, below the animated wheels, rather than as independently rotated hash grids
on the front overlay. Case, counters, hand/date geometry, pushers, layout, state
and font handling remain outside this revision.

## Reproduce in the normal Android build environment

From the `app/` Gradle root with the project Android dependencies installed:

```sh
./gradlew -PunsignedRelease=true -PincludePersonalOverlay=false \
  :app:testDebugUnitTest :app:testReleaseUnitTest
```

PNG export skips unless explicitly enabled. Select a **new, nonexistent absolute
directory**; the exporter refuses to overwrite evidence:

```sh
CARBON_REVIEW_OUTPUT=/absolute/path/to/new-review-directory \
CARBON_REVIEW_REVISION="$(git rev-parse HEAD) plus reviewed working tree" \
./gradlew -PunsignedRelease=true -PincludePersonalOverlay=false \
  :app:cleanTestDebugUnitTest :app:testDebugUnitTest \
  --tests 'ai.nanosearch.launcher.clocks.CarbonBridge*'
```

The images cover parked, running and illuminated states at 240, 360 and 720
pixels, fixed to 2026-10-15 10:10:30 UTC. Metadata records states, source identity,
changed-pixel counts and PNG hashes. Both sides use actual renderer code in the
same native-graphics process; neither side is an image mockup.

A separate support-only comparison omits hands and counter faces, making the
removed neck unambiguous. It calls the bridge painter only: the previous painter
contains recessed seats, but the separate screw-head painter is not included in
that detail. The revised rear supports are now in the base, so they are also
omitted from this front-layer diagnostic. The complete-dial comparison and regression tests remain the actual
front-screw-removal evidence. This detail must not be presented as a whole watch.

## Non-vacuous guardrails

- Frozen fixtures must recover exact source SHA-256 after identifier renames.
- The front path must have exactly two four-neighbour components at phone and
  design sizes: a substantial main 3/9 bridge, and the exact original lower
  counter collar. Extra fragments, deleted support and diagonal-only joins fail.
- The removed neck must be geometrically clear and paint-transparent away from
  boundary shadows; this does not incorrectly require the hand to disappear.
- Counter, date and spindle apertures remain clear; support stays in the same
  dial envelope. The lower collar is compared to its unchanged construction.
- Actual full-dial pixels at all six former front-screw centres must equal bare
  opaque bridge pixels. The screw-bearing frozen candidate must differ, proving
  the check exercises previously visible screw pixels. Source wiring additionally
  rejects front screw/seat painting while retaining unrelated screws.
- Every rear panel must contain its expected aligned ribs and concentric rings,
  while intervening slots remain genuinely open. Rear paths also avoid all
  apertures and the removed neck gap. Draw order is checked separately.
- Case/pusher, date and counter-interior pixels match the frozen candidate exactly
  across all states and 240/360/720/1000px masks. The masks are not adapted to the
  new drawing. Isolated hands, seconds and host geometry remain exact.
- Repeated painting is deterministic and preserves Canvas transform, clipping
  and save stack. Returned paths must not share mutable state. Graphite faces
  stay opaque after the shadow pass.

The Android instrumentation geometry test mirrors the revised topology, but its
presence does not mean it was executed on Android. No renderer test validates
touch routing, screen-off lifecycle, system Clock launch, live-wallpaper hosts,
OEM behaviour, frame timing or the private local font. Previous release/device
results must not be attributed to this revised draft.
