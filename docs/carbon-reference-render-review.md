# Carbon bridge: localized 12-o'clock cleanup review

The current comparison uses actual Carbon Canvas code against the approved
candidate `4058d215b5c08b68b072547e8ff3d544e013aa83`. Its dial/front/rear fixtures
are identifier-only source copies with exact SHA-256 provenance. Earlier a44,
ed1 and public Nano 0.8.1 fixtures remain for historical regression checks.

This is Robolectric4.17 with API35 framework classes and **native Skia on the
JVM**. It is not an Android emulator/device screenshot, full app build, R8, lint,
instrumentation pass, or permission to publish/install.

## The only new change

The zoom reference identifies faint parallel engraved strokes next to the orange
12 and across the upper rear upright. Only the eleven-oval engraving loop is
clipped above design y390. That boundary falls behind opaque counter interiors;
it does not introduce a visible cut edge. The prior explicit BUTT cap is retained.
The lower engraving, matching rear frames, front bridge, hands, date, counters,
case and every other approved element are unchanged.

In particular, the centre bridge still connects three and nine, not six; the lower
counter keeps its independent collar. Front bridge screws remain absent. The two
previously circled side jewels remain removed; the top/counter jewels and unrelated
case/rear/counter screws remain. No layout, pusher, state or font handling changes.

## Reproduction

From the normal Android `app/` Gradle root with dependencies installed:

```sh
./gradlew -PunsignedRelease=true -PincludePersonalOverlay=false \
  :app:testDebugUnitTest :app:testReleaseUnitTest
```

For PNG export, select a **new, nonexistent absolute directory**:

```sh
CARBON_REVIEW_OUTPUT=/absolute/path/to/new-review-directory \
CARBON_REVIEW_REVISION="$(git rev-parse HEAD) plus reviewed working tree" \
./gradlew -PunsignedRelease=true -PincludePersonalOverlay=false \
  :app:cleanTestDebugUnitTest :app:testDebugUnitTest \
  --tests 'ai.nanosearch.launcher.clocks.CarbonBridge*'
```

Standalone native-harness results must be identified separately from these full
Android commands. Export skips deliberately when its environment variable is absent.

## Actual capture evidence

Full-dial images cover parked, running and illuminated states at240/360/720px,
fixed to 2026-10-15 10:10:30 UTC. A labelled close-up is cropped from the actual
complete720px render at source rectangle `(297,164,173,110)` and enlarged exactly
4× with nearest-neighbour pixels—no smoothing, repainting or simulated marks.
It includes the orange12, upper upright and adjacent wheel. Metadata records
all source identities, states, changed-pixel counts, crop geometry and PNG hashes.

The front-only diagnostic is pixel-identical. The base-layer comparison shows
only the engraving cleanup, omitting moving wheels and the front overlay. These
diagnostics are not whole-watch screenshots; full-dial captures remain in evidence.

## Strict guardrails

- Approved4058 fixtures recover exact source hashes after identifier renames.
- Front and rear support sources remain hash-identical. Every pixel of the whole
  isolated front overlay is exact, including type, jewels, date and counters.
- Full-dial pixel changes are allowed only on the independently reconstructed
  eleven-oval upper cap, clipped at y390, with a fixed5-design-unit dilation for
  antialiasing/cache resampling. The mask is not derived from observed changes.
  Over95% of every complete bitmap must remain outside that mask and match exactly.
- Actual old upper engraved pixels must become bare dial, not merely change colour.
  Every visible lower base/engraving pixel remains exact, with substantial lower
  engraving still present. Clip-induced raw-buffer seam differences are allowed
  only behind an exactly unchanged, fully opaque overlay in the same assembly/cache
  coordinates (36 one-channel-level pixels at720px in the first diagnostic). This
  is an occlusion proof, not a colour tolerance or wider visible-change mask.
- Exact case/date/counter masks, hands, seconds and host geometry remain checked
  at240/360/720/1000px. The inherited date-content mask uses the frozen overlay's
  actual opacity, while whole-overlay pixels are compared independently and exactly.
- Existing two-component front topology, clear apertures/neck gap, lower collar,
  front-screw removal, mirrored rear-frame normal web width/chamfers, retained
  unmarked jewels, deterministic painting and Canvas-state checks remain in force.

Android instrumentation source is not execution evidence. No current renderer
check validates widget/live-wallpaper hosts, pusher launches, screen-off lifecycle,
OEM behaviour, frame timing or the private font. Prior release/device results must
not be attributed to this local review draft.
