# Carbon 0.8.4 refinement review

This revision compares the production renderer with identifier-only frozen v0.8.3
sources at `6c722e3a9cf731354efa29cc24dbe1a3181c0225`. Frozen dial, bridge and rear
sources recover their exact original SHA-256 in `CarbonRefinement084Test`.
Historical 0.8.1/a44/4058 review tests now use these frozen fixtures as their final
historical target; their original assertions remain. Current production geometry,
painting and source wiring checks still apply directly to production sources.

The upper spars and lower feet receive mirrored curved/flared joins. No front
screws, six-o'clock neck, removed jewels or upper engraving return. The rear plates,
case envelope, counters, date and main hands remain unchanged. Exact full-dial
pixel comparisons protect everything outside fixed four-spar envelopes, a narrow
frozen-prior-material bevel envelope and the battery detail. Counter/date interior
pixels are checked independently even inside those envelopes. Path union may
retessellate the pre-existing bevel's antialiasing; no numeric color tolerance is
used. Existing native geometry tests retain exactly two substantial components,
clear apertures and a transparent centre-to-six gap at phone and design sizes.

Battery L/M/H thresholds and invalid/unknown cases are tested independently, then
actual renderer images verify immediate band changes and exact preservation of
all other pixels. Unknown readings draw no pointer. Event observers are tested for
sticky initialization, duplicate start, updates, stop/restart, View/window/screen
lifecycle and wallpaper hide/show/destroy. A test protects M-label readability.

To export actual new before/after evidence into a new, nonexistent directory:

```sh
CARBON_084_OUTPUT=/absolute/new-directory ./gradlew \
  -PunsignedRelease=true -PincludePersonalOverlay=false \
  :app:testDebugUnitTest --tests '*CarbonRefinement084Test'
```

Exports include 240/360/720px light/dark-background comparisons, low/medium/high/
unknown battery examples and lume. Labels are composed by native Android Canvas.
The face itself retains its dark material; “light/dark” refers to its surrounding
background, not invented alternate artwork. Results use native Skia on the JVM,
not an emulator/device or performance profiler.

Original references found in Library were `IMG_8117.jpeg`, `IMG_8118.webp` and
`IMG_8119.jpeg`; supported consumer-local materialization failed with a network
error. No original reference pixels were available for this revision. Review
therefore uses actual before/after render pixels and preserves that limitation.

No secrets/private fonts/model downloads, workflow dispatch or approval bypass
is part of this review. Device host behavior, OEM insets, physical accessibility
services and frame timing remain unverified here.
