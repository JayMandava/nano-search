# Built-in clock faces (0.7.0)

Long-press Home, open Widgets, and choose Steel Dial or Carbon 01. Existing Steel placements retain their stable ID. Carbon is original procedural artwork inspired by RM 65-01 McLaren W1, with a local-date aperture, fractional running seconds at 6, and decorative moving gears. The central elapsed pointer is parked: this launcher face does not offer chronograph pushers or simulate real movement engineering. Seconds and the central pointer render beneath local-time hands. Tap a home clock to open the installed system Clock; if no permitted clock handler exists, Nano reports that it is unavailable.

Wallpaper & style → Clock on the lock screen offers an independent face selector, the existing backgrounds/photo/position choices, and Reduced motion. Set as wallpaper opens Android's preview; Android/OEM controls home/lock placement. This is a live wallpaper, not an always-on-display replacement. No automatic wallpaper activation or migration of personal selections occurs.

Home uses display-synchronized invalidation only while attached, visible and interactive. Wallpaper retains the existing 30fps ceiling. Reduced motion, disabled system animation and power saving park decorative mechanics and step time once per second. Hidden, screen-off and destroyed surfaces cancel frame callbacks. Time, timezone, date and locale callbacks refresh time state. Both hosts use the same renderer registry; future faces can register a stable ID, label, credit and renderer.

## Validation

- 24 JVM tests pass in debug and release variants: existing launcher tests plus registry, motion policy, DST/timezone/negative epoch and public-asset checks.
- 13 Android API35 emulator tests pass: exact Steel raster parity, Carbon/date/mechanical motion, reduced motion, seconds and central-pointer composition, selection/background/photo persistence, system Clock resolution/launch, accessible labels and attach/hide/pause/detach lifecycle.
- Production-signed API35 emulator: existing Steel default, Carbon picker/credit and home rendering, system Clock tap, and Android live-wallpaper preview/motion checked. Preview exited without applying it.
- Public release, public debug, local personal debug and minified builds succeed. Public APK excludes the optional personal font overlay. Local overlay remains available only to debug/minified builds; use `-PincludePersonalOverlay=false` for public debug tests.
- Lint retains five existing project errors in LocalVoice, the manifest and SettingsActivity. Clock changes introduce no non-style lint warnings; Canvas/Bitmap KTX suggestions remain. No lint baseline or assertion tolerance was introduced.

Physical-device validation of this integrated Nano version is still pending. OEM lockscreen/AOD placement and device-specific battery/frame pacing are not claimed. The separate clock prototype is archived locally; this release uses Nano's built-in renderer and has no Remote Compose alpha dependency, per-frame AppWidget broadcasts, alarm loop or new runtime permission.

## Development: Carbon home controls

The keyed Carbon home widget supports upper-right Start/Pause, orange-left Split/Release, lower-right Reset while stopped, and central-right crown illumination. Only the inner dial opens system Clock. There is no second left winding pusher. Timer state is independent per placed widget, uses monotonic elapsed time, survives same-boot recreation, and restores a paused checkpoint after reboot. Wallpaper remains display-only. Crown illumination holds then fades over three seconds; reduced motion holds a static glow then turns it off. Hiding, screen-off, detaching or resizing cancels the transient effect.

Controls commit only matching short taps. Scroll/drag, long-press, multiple pointers and cancelled gestures do not operate the timer. Native virtual buttons expose labels, enabled states, elapsed/split status, keyboard focus and touch exploration. Targets reach48dp when the square is at least300dp; at smaller existing sizes they shrink within disjoint bands. Existing widget tokens, positions, sizes and wallpaper preferences are retained.

Validation:33 JVM cases pass in both variants and33 API35 Android cases pass in public/private-font builds, including real touch input, screen-off and exact raster regression. Production-signed emulator Home also checked for controls, system Clock, long press and edit/resize behavior. OnePlus CPH2467 private-font development update passed existing-widget start/split/release/pause/reset, crown consumption, Clock launch and unchanged launcher preference/wallpaper comparisons. No physical screenshots were captured; glow appearance and fade evidence are emulator recordings. This checkpoint is local development, not a separate public release.
