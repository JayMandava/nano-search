# Core restoration and direct gesture acceptance

This follow-up restores models and indexing as core launcher functionality. Standalone means separate launcher UI, not app-only search: on-device models, voice, query parsing, indexing sources, Files, index counts/rebuild and existing search are restored. Widgets, Active Frames and theme-mode framing remain excluded. Source permissions are declared again but not automatically granted; no model download was initiated by this work.

- Empty Favorites previously replaced the three-row grid with wrap-content text and collapsed the tray. A fixed-height content container now reserves the same space for empty, one-app and populated panels. Unit coverage checks these states.
- Battery/date share a left gutter; carrier and connection control sit together. Date opens the calendar. Clock opens the resolved clock app's launcher activity, avoiding a headless alarm API handler where available.
- Immersive navigation consumed bottom swipes on Home/menu/Hub. Standalone now keeps Android navigation available instead of hiding it. This is separate from default HOME selection: Android routes Home to the selected launcher.
- Notification title has extra cutout-safe top clearance and tabs have symmetric side gutters. Unchanged app lists no longer invalidate the grid adapter on each refresh.
- The search-hiding test was corrected to refer to the actual tray ancestor after the new fixed-height container was introduced; the requirement that the full tray is hidden during search and restored afterward remains unchanged.

## Follow-up verification

- Final private device APK: `ec30268b44b641290a50059dc7ae7218e64f19c777fdfc4740e7ce0ee4d4fff9`. It was installed as an in-place v2 update; original Nano/HTC data was preserved. Private font/APK remain local-only.
- Actual bottom-edge Home swipes, not only Home key injection, passed from Home menu, keyboard-visible search, Hub, Settings, Models, Calendar, Clock and a completed local Ask response while v2 was temporarily default. Original HD2 default was restored and verified afterward. To use v2 as daily Home, it must be selected as Android's default launcher.
- Empty and one-item Favorites retained the same tray position on the phone; unit tests cover empty, one and multiple favorites. Test favorite state was restored. Header alignment, compact network group and date/clock targets were checked visually; clock text now opens DeskClock's main screen.
- Models and index controls are present. App/settings indexing and app search were populated. The existing Qwen model answered a harmless arithmetic query locally in roughly 30 seconds; no model download or selection change was initiated. This is a functional smoke test, not a model-quality or inference-speed benchmark.
- Hub title began below the 100 px cutout; tabs had 45 px side gutters at the phone's density. Populated Hub layout was inspected without copying personal payloads. Real notifications were not opened, pinned, replied to or dismissed. Synthetic posting was skipped because the available shell command lacked a corresponding safe cancellation operation.
- A bounded navigation sample recorded 182 frames, 14 janky (7.69%), p50 7 ms, p95 21 ms, p99 32 ms. The sample differs from the earlier run and does not establish a speed gain or sustained 120 fps. Adapter invalidation regression coverage verifies unchanged lists no longer trigger a redraw.
- Device logs included SQLite connection/WAL warnings during indexing, without a crash; app search and inference completed. This is not a claim that all logging/performance concerns are eliminated.
- Standalone and original Nano profiles each passed 282 tests: 272 passed, ten existing skips, zero failures/errors. Final lint passed with zero errors, 334 warnings and one hint. The private device artifact hash remained unchanged.

---

# Current standalone v2 refinement

This section supersedes the earlier experimental workspace/theme behavior documented below. The OS7 launcher build is selected by `os7QaLauncherBuild`; it remains a separate application from original Nano and HTC.

- Fixed three-row app tray: title taps and vertical swipes no longer resize it.
- Compact top status region uses the sides of the verified center cutout. Corner cutouts use a safe below-cutout fallback. Clock/header use condensed typography, restrained translucent charcoal, and yellow notification counts informed by the owner-provided BlackBerry 7 OS Demo video.
- Opening search hides app tray/footer. Outside taps and Back/Home dismiss search; no AI/model or personal-data search UI is exposed. Search is app-only, including query-validated Enter launch.
- Standalone settings/setup omit Nano workspace, widgets, Active Frames, model downloads, indexing sources and theme-mode framing. Hidden apps and app menus retain hide/unhide support. Old preference values are preserved but do not re-enable removed features.
- Notifications use only authorized notification-listener data, with accessible utility-list styling. No contact/SMS/call/calendar provider reads in standalone Hub. The demo does not show an opened notification Hub; this list is an Android adaptation, not an asserted exact copy.
- Steel Dial live-wallpaper settings/preview and provider are restored only for this build. Android confirms wallpaper application; destination support and system lock-clock overlays are device-dependent.
- Original owner-supplied 911 Porscha font/notice remain in ignored local personal assets only. They are not committed or uploaded, and release/public source sets retain the redistributable fallback. The device APK containing the private font must remain local.
- A standalone-specific manifest excludes unrelated personal-search/audio/widget-binding permissions while preserving original Nano permissions in other profiles.

## Refinement validation

- Standalone and original Nano profiles each passed 279 tests with zero failures/errors and ten existing skips (269 passed), including four new indexed-hidden-state regressions. Final standalone lint passed with zero errors, 333 warnings and one hint.
- Actual CPH2467 phone verification covered fixed three-row tray, center-cutout clearance, search hiding tray/footer, outside/Home search dismissal, menu Back/Home, standalone Settings controls and the original Steel Dial with private 911 Porscha numerals. Android wallpaper preview opened; wallpaper was not applied. Existing notification access was preserved, and only the empty Hub was viewed. Notification selection/filtering has unit coverage; populated live notification interactions were not exercised.
- QA caught stale search results after hide then unhide. The fix compares against the hidden state used for the last successful index rebuild, not the previous activity lifecycle observation. Final phone retest verified Calculator disappears while hidden and returns immediately after unhiding, without a process restart. Options also now clears its child stack: Steel Dial → Home → Options returned the Settings root.
- Temporary HOME testing restored and verified the original HD2 default. Hidden app state was restored. Original Nano/HTC package data was not changed.
- A short mixed-navigation sample measured 146 frames, 10 janky (6.85%), p50 9 ms, p95 21 ms and p99 28 ms. This is not evidence of sustained 120 fps or zero jank.
- Final private installed APK SHA-256: `e51d0ef9240671a41f727db29a6e9cf42f48bf2e577222653404cda33592385b`. It remains local and is not a public release artifact.
- Reference captures and private APKs remain local. Only source and documentation are authorized for the feature-branch push. No private APK, font, owner video or device captures are included.

---

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
