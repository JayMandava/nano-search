# Post-0.8.2 phone feedback: local review

Base: `29caeb2e6b494524f361fbe7150b98075078c8f8`. These changes are local review
work; they do not prepare or authorize another public release. Version remains
0.8.2 / code 17. Approved RM artwork and production signing policy are unchanged.

## Clock-change notice

The notice was a persistent, content-width Card. Its TextButton padding also put
the Dismiss label farther in than the message. The card now fills the available
content width within Scaffold safe-drawing insets, and both labels share a start
edge. Dismiss keeps a minimum 48dp target and the notice has polite live-region
semantics.

A notice expires after six foreground seconds, adjusted through Compose's
accessibility timeout recommendation with text and controls enabled. Extended
and indefinite recommendations are respected. Repeated identical messages get
new revisions; stale expiry cannot dismiss a replacement. Scrolling does not
restart the timer, while pause/resume preserves a full readable interval.

## Model choice and download state

Download did not record the requested model choice. Gemma could show download
progress while the installed Qwen fallback remained selected. Catalog IDs are
unique; Gemma and Qwen have different files. Qwen weights are intentionally shared
between Understanding and Answering, whose preference IDs remain independent.

Starting a download records that model's ID for its job. One requested row is
selected during the transfer, with explicit text explaining any currently usable
fallback until verification completes. Cancellation/failure displays the usable
fallback again. Inference continues using installed weights during download.
Cards have stable per-model semantics and installed rows are selectable. The
Models screen stops its progress polling when its lifecycle is stopped.

## Verification

- Debug and release JVM suites: 177 tests each, zero failures/errors; one deliberate
  opt-in PNG-export skip per suite.
- Seven actual Compose UI tests per variant, using Robolectric/API 35/native
  graphics: notice width/label alignment at normal and doubled font scale, 48dp
  Dismiss target, accessibility-adjusted/repeated/indefinite timing, manual
  dismissal, navigation cancellation, pause/resume, pending-choice cancellation,
  and real Models-screen selection across lazy-list scrolling.
- State tests cover stale notice expiry, slot-specific download preference,
  fallback/cancellation/failure, catalog IDs and shared Qwen transfer identity.
  DownloadManager is shadowed. Sparse length-only fixtures are cleaned up;
  no model weights or real downloads are used.
- Explicit lintDebug/lintRelease: zero errors/fatals. The two previously approved
  permission-leaf exclusions remain unchanged and enforced by their policy test.
- Unsigned release R8 assembly and debug instrumentation-source compilation pass.
- All 24 release safeguard tests pass; release workflow/tools and RM renderer
  sources have no diff from the released baseline.

The selected environment has no installed adb or emulator. Compose/Robolectric
checks are not device instrumentation, phone smoothness, frame-time profiling,
OEM inset/gesture behavior, TalkBack service, or real-network download evidence.
A physical follow-up should check portrait/landscape/cutout and large-text notice
layout, repeated clock changes, TalkBack timing, model downloads over interrupted
connections, and the installed-model fallback during an actual verified download.
No push, tag, workflow dispatch, signing, phone install or publication is part of
this review.
