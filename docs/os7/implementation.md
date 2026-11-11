# OS 7 recreation branch

Reference target: BlackBerry Bold 9900/9930, stock OS 7.0. This is an Android recreation, not firmware or a binary port. Existing Nano content and features must remain available without migration loss.

## Sources and visual contract

- Official guide: https://www.att.com/support_static_files/manuals/BlackBerry_Bold_9900.pdf , PDF page 18 (printed16), pages18–20,28,214.
- Contemporary captures: https://www.gsmarena.com/blackberry_bold_touch_9900-review-645p3.php . Screens001–003,006–010,020–021 inspected during implementation.
- Original captures are reference material only, outside app assets. No BlackBerry font, logo, firmware, wallpaper or icon files are included.
- Genuine reference uses 640×480 landscape display above physical keyboard. Android portrait adapts to 4–6 columns and 48dp controls, retaining cutout clearances and gesture navigation. The OS7 home hides only the Android status bar in its app window; a swipe reveals the system shade. Other Android screens remain system-owned.
- Dark translucent tray, unboxed icons, neutral carousel with blue focus; four tray states (hidden/one/two/three rows); All/Favorites/Media/Downloads/Frequent.
- Actual launch-frequency ranking, not a renamed Recent view. Empty states do not invent history.
- Original Nano object artwork is used without its Spirit gem backplates in OS7 mode. Explicit icon packs still win. This is not pixel-identical BlackBerry artwork.

## Implementation sequence

1. Fresh clone of 1314fcdb6b169f2f780521bc6c21d372d9673d9a; feat/os7-recreation. Existing dirty checkout untouched.
2. Reversible OS7 mode. Keep original pages, widgets, dock and search state stores unchanged; Nano workspace reachable from Home menu.
3. Build OS7 header, notification/search access, panel carousel, staged tray, original app grid focus.
4. Add local folder organisation, ordering and panel visibility. Preserve hidden apps, favorites, recents and mode switching.
5. Apply coherent Settings/Options visuals to owned surfaces and bounded menus. Android system permission/picker screens remain system-owned.
6. Compile, tests, lint, emulator visual/navigation validation; independent review. No push, merge, signing or publication included.

## Acceptance and limits

- Home command must dismiss overlays, folder/name dialogs, search and tray, returning clean OS7 Home.
- Search, voice, models, widget workspace and original Spirit mode must remain reachable.
- New folder removal removes grouping only; does not delete apps or hidden flags.
- Count successful launches once; failures should not count. Existing recents aren't fabricated into frequency.
- Labels and controls must remain usable at larger font scales and narrow screens.
- Test repeated tray/menu interactions; rapid taps must not produce stale overlays.
- Status indicators must reflect actual device information; no fake carrier or connectivity bars.
- Phone verification cannot be claimed without an attached device. Emulator refresh rate does not establish 120fps phone performance.

## Validation ledger

Implementation and emulator acceptance complete; this is not a physical-device or release sign-off.

- Final QA APK SHA-256: `d091d79734a57080576656d57bad361d3f8e236fe9b2459ba6a52aec4184fcca`. It builds with the existing `clockQaBuild`, unsigned-release and no-personal-overlay flags. QA application ID is separate from production and does not register as a HOME candidate.
- Final full test run: 268 tests, 0 failures/errors, 10 existing skips (258 passed), including all 22 new OS7 tests. Initial test-only Robolectric shadow and macOS `/var` versus `/private/var` temp-path failures were corrected with a compatible ShadowView subclass and invocation-local canonical test-temp directory. Neither production validation nor assertions were relaxed. Final build plus full suite completed in 2m13s; targeted OS7 suite also passed independently.
- Emulator acceptance: all five panels; hidden/one/two/three-row tray; folder create/open/remove/Home reset; Camera search; keyboard-bounded dark menu with scroll-to-Options; Home reset from search/menu/IME/dialog/folder/workspace; deep Options/Models/Hidden Apps scrolling; Advanced rendering; mode-off/on round trip preserving baseline clock/dock. Earlier provisional build also verified folder rename/move and panel hide/restore. The final search-icon overlap fix was recaptured at default and320dp/130% text; notification controls restore after Home/Back. Existing progressive Back behavior remains: keyboard, query, focus, search closure.
- Original AVD was copied to a read-only Android35 ARM64 QA clone (1080×1920,420dpi). Narrow 840×1600 (~320dp) at130% text fit without observed header/menu/search clipping; display/font restored. Crash buffer empty. No physical device was attached. Production launcher, original checkout and original AVD are unchanged. No new permissions, account sign-ins, model downloads or default HOME changes.
- Final `lintDebug` passed in 1m24s: 0 errors, 316 warnings and 1 hint. Existing/style warnings remain. The earlier lint invocation was intentionally stopped after concurrent analyses caused host paging pressure; controlled builds used no parallel tasks/two workers, final lint one worker.
- Independent static review corrected folder order, filtered membership, folder-title accessibility, Home/workspace Back routing, implicit dock launch counting, background package metadata reads, and IME-safe menu bounds.

## Deliberate Android adaptations

- Touch controls, persistent app labels, minimum 48dp targets and 4–6 columns replace the 640×480 hardware-keyboard device geometry.
- App ordering uses Move earlier/later actions; it is not the original trackpad drag interaction.
- Connections and sound profile controls open Android-owned panels. Original BlackBerry services and OS applications are not emulated.
- Nano search/voice/models, notifications, widgets and Active Frames remain reachable. This branch is a visual/interaction recreation, not an exact firmware port or proof of original-service compatibility.
- Populated personal-workspace migration and cutout-specific rendering were not exercised; the QA package tested synthetic baseline data and explicit HOME intents, not default-launcher system registration.
- Final device performance and 120 Hz smoothness require the user's actual phone; emulator rendering cannot validate them.

## Subsequent physical-device QA

The same APK was installed on the user's authorized CPH2467 as the previously absent isolated `ai.nanosearch.launcher.clockqa` package. Original Nano/HD2 data and HD2 default HOME remained unchanged. No new personal-data permissions, accounts or model downloads were enabled.

Actual-device checks passed: header/cutout-safe inset, one/two/three-row and hidden tray, five panels, dark menus, search with existing keyboard, keyboard-bounded menu, explicit HOME reset and Options scrolling to Advanced. No blocking visual issue or crash/ANR was observed in the scoped app log sample. QA remained installed and open for review.

A bounded mixed-navigation sample recorded 564 frames,40 janky(7.09%),median8ms,p95 27ms. The display reported120Hz, but this is not sustained120fps; drops remain. The separate QA manifest omits HOME registration, so default-launcher system Home integration and populated production-data migration remain untested.

## Named v2 launcher build and real Home tests

User-requested display name is `Nano Search v2`, verified against original installed `Nano Search`. New debug-only `-Pos7QaLauncherBuild=true` retains the same isolated `.clockqa` ID and signer, enables HOME registration and keeps wallpaper provider disabled. Ordinary clock-QA remains non-HOME; regular debug remains unchanged. Packaged-manifest matrix and signer checks passed. APK SHA-256: `2f9847e2cb1c757cab6ea86550c90f44a93f26afa30ceddbe99af7e87b1cfa89`.

Installed in place preserving first-install time/data. Temporarily made v2 the actual default HOME on CPH2467. Real Home key from menu, search, folder and Android Settings returned clean All/one-row Home; repeated Home stable. Actual bottom swipe Home from Android Settings passed. Synthetic test folder removed. Exact original HD2 default restored and resolver verified; no additional grants or production-data changes. V2 remains installed with its new label.
