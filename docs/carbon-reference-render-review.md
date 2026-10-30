# Carbon 0.8.4 case and battery review

The final case/scale compares production pixels with frozen candidate
`11166bf4ba6e8e955a8f03febff57179f4a2baa8`. `ReferenceReviewCandidateTest` recovers
the exact prior dial hash after identifier-only renames and hash-checks the
unchanged approved bridge and pusher geometry source. The earlier bridge/battery
revision tests use the frozen prior candidate; their original narrow masks and
assertions remain. Current regression coverage is independent of those historical
proofs.

The only new permitted regions are the outer case/pusher area and the fixed
assembly-coordinate battery rectangle `(615,625)–(725,752)`. The protected inner
face comes from literal approved dial coordinates, scaled 1.10 x 1.07 to include
the blue bezel. Every included pixel outside that battery rectangle must match the
prior candidate exactly at 240/360/720/1000px in Low/Medium/High/Unknown states.
No mask is built from changed pixels or revised case geometry; no color tolerance
is used. Counter/date geometry, the main bridge and its independent lower collar,
rear plates, removed jewels and engraving are preserved.

The new case has shallow side waists and flatter angled shoulders. Dark pusher
blocks have slim satin lips rather than polished hollow guards. Every original
fastener retains a complete 20-unit seat inside the new case. Existing pusher
hit-region source remains hash-identical; controls keep their established centers
and target extents, including the pre-existing constrained crown band on the
smallest raster size.

The dedicated battery renderer shares pivot `(704,712)`, radius 50 scale, radius 65
labels, and clockwise-from-up angles Low -105, Medium -65, High -30 degrees.
Pointer and sector geometry use that same datum. Short colored arcs have end ticks
only. A consistent limited tangent adjustment keeps labels within +/-14 degrees.
The pointer stops inside the scale; its tail is short and its orange cue sits
near the arrowhead. Unknown retains the scale without a moving pointer. Functional
thresholds remain Low 0–20, Medium 21–70, High 71–100; invalid readings are unknown.

The coordinating executor materialized and inspected original close-up Library
`libfile_52df795118f081918b33a9a0cef47479` version 0 and supplied measured design
constraints. This implementation executor's two supported transfer attempts failed
with generic download errors; no policy denial or alternate route was inferred.
The coordinator independently compared actual candidate images with the original
and accepted Library `libfile_117f3d24fb408191b30f1157622d86dd` version 1. Its SHA-256
is `f2165afc95591f73d880d1c8e706ee7459894995267f03a71353787ce5f644bc`.
No pixel changes followed acceptance.

Actual render export (new nonexistent output directory required):

```sh
CARBON_REFERENCE_CANDIDATE_OUTPUT=/absolute/new-directory ./gradlew \
  -PunsignedRelease=true -PincludePersonalOverlay=false \
  :app:testDebugUnitTest --tests '*ReferenceReviewCandidateTest'
```

Full faces at 240/360/720px, every band/unknown, native side-by-side comparisons and
3x nearest-neighbour battery/pusher crops are exported without retouching. They
use Robolectric/API35/native Skia, not a device/emulator or performance profiler.
No secret/private-font/model downloads, dispatch retry or owner-gate bypass is
part of this work. Physical phone host behavior, TalkBack, OEM behavior and frame
timing remain separate device QA.
