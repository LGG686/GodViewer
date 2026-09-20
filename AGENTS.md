# AGENTS.md

## Project
GodViewer (上帝视角) — an **Xposed / LSPosed** runtime view-debugging tool for Android.
It injects only into **LSPosed-scoped target apps** so the user can touch-select a View, edit
attributes (size, margin, padding, visibility, TextView text, ImageView URL/scaleType) live,
and persist JSON rules in the **target app's own data dir**
(`/data/data/<target>/files/godviewer/rules.json`) that auto-replay after restart.
Package/namespace: `com.godviewer.app`. License: GPL-3.0. Verified on Android 16 + LSPosed.

User preference: communicate in **中文** unless they write in English.

## Build
- **Windows shell is Git Bash**: run `./gradlew` (or `gradlew.bat`).
- Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- Prefer `assembleDebug` after feature work. **Do not commit unless the user asks.**
- Requirements: **JDK 17**, Android SDK **platform 35**. `local.properties` (`sdk.dir`) is
  gitignored and must exist locally (this machine often uses `tools-Android/`).
- Stack: Gradle 8.7 wrapper, AGP 8.5.2, Kotlin 1.9.24 (jvmTarget 1.8), minSdk 23 / targetSdk 35.
- UI is **Views + XML + ViewBinding** (`viewBinding = true`), **not Compose**. No flavors, no DI
  framework, no coroutines, no Room.
- CI (`.github/workflows/android-build.yml`): builds the **release variant only** (no debug — it is
  never published), on pushes to `main` and `feature/**` plus manual dispatch. Pushing to `main`
  publishes a GitHub Release with the release APK; `feature/**` and manual runs only upload the
  artifact unless the `publish` input is ticked.
- No real unit/instrumented tests; verify on device with LSPosed + logcat (`GvLog` / `GodViewer.*`).

## Architecture (single `:app` module)
Source root: `app/src/main/java/com/godviewer/app/`

Logical packages (phase-2):

| Package | Role |
|---------|------|
| `host/` | Host app process only: `GodViewerApp`, `MainActivity`, mirror Activities, `host/ui/*` fragments, `host/mirror` store, `host/control` notifier + `*Impl` receivers, prefs/entry UI helpers |
| `target/` | Injected into LSPosed-scoped apps: `target/hook` (entry `GodViewerModule`, META-INF/xposed), `target/rule`, `target/edit`, `target/ui` dialogs, `target/handler`, `target/dialog`, `target/dispatch`, `target/glide`, `target/mirror` push |
| `shared/` | Both sides, no Dialog/Activity/Hooker: `shared/model` (`ViewRule`…), `shared/mirror` protocol/DTO/codec, `shared/control` bridge, `shared/entry` mode keys, `GvLog`/`Hash`/`VeiwUtil`/`ViewSnapshot`/constants |
| `data/` | **Protocol-frozen FQCNs only**: thin stubs `RuleMirrorReceiver` / `HostControlReceiver` / `HostPrefsProvider` (subclass host `*Impl`) + `RuleMirror` facade API |
| `util/` | **Protocol-frozen**: `ModuleStatus` stays at `com.godviewer.app.util.ModuleStatus` (Xposed string + reflection) |
| root | `LegacyExports.kt` re-exports `IGNORE_HOOK` for historical imports |

Layers (conceptual): `target.hook` → `target.rule` / `shared` → `target.ui`/`handler` → host UI.
No MVVM; singletons are Kotlin `object`s. Large types keep **thin facades**
(`RuleMirror`, `ViewRuleManager`, `ModuleDialogUi`).

**Authority vs mirror:** rules live only under the target’s private dir. On save, the target
best-effort broadcasts a copy to the host (`filesDir/godviewer/mirror/<pkg>/`). Host cannot
read target private data directly.

### Boundary roadmap (optimization — no new features)
Decisions locked (grill-me): boundary/deps cleanup; internal refactor OK, **external protocols
frozen** (Manifest components, broadcast/Provider tokens/actions, `rules.json` schema);
logical packages `host` / `target` / `shared` in one Gradle module; practical shared (no
Dialog/Activity/Hooker).

| Phase | Scope | Status |
|-------|--------|--------|
| 1 | Same-package responsibility split of large classes (Mirror → Manager → BaseAttr → ModuleDialogUi); thin facades; GvLog on touched paths | **done** |
| 2 | Move into `com.godviewer.app.host` / `.target` / `.shared`; relocate root Activities & util by role; freeze protocol FQCNs via `data/*` stubs + `util.ModuleStatus` | **done** |
| 3 | Boundary hygiene: no `shared→host/target`, no `target→host`; `HostPrefsNames` shared keys; `EntryMode` self-contained + host `EntryControlUi.setEntryMode`; `HiddenEntryNotifier` on host only | **done** (assembleDebug OK) |
| 4 | Optional: drop `data.RuleMirror` facade / more call-site cleanup once stable on device | pending |

**Frozen FQCNs / strings (do not rename without compatibility):**
- Manifest: `.data.RuleMirrorReceiver`, `.data.HostControlReceiver`, `.data.HostPrefsProvider`
- Authority `com.godviewer.app.hostprefs`; tokens/actions unchanged
- Explicit broadcast class names in `RuleMirrorProtocol.RECEIVER_CLASS` /
  `HostControlBridge.HOST_RECEIVER` → still `com.godviewer.app.data.*`
- `ModuleStatus` class name string `com.godviewer.app.util.ModuleStatus`
- libxposed entry: `META-INF/xposed/java_init.list` → `com.godviewer.app.target.hook.GodViewerModule`
  (replaces the old `assets/xposed_init` + `AnyHookPackage` / `AnyHookZygote`, removed in the
  API 102 migration)

Backup before large moves: desktop `GodViewer-backup-pre-optimize-*` +
`git stash` `backup-pre-optimize-*`.

## Critical gotchas
- **libxposed API 102 (2026-09 migration)**: module identity lives in
  `app/src/main/resources/META-INF/xposed/` (`java_init.list`, `module.prop` with
  `minApiVersion=101/targetApiVersion=102/staticScope=false/exceptionMode=protective`,
  empty `scope.list` — user picks targets in LSPosed). Manifest has **no** legacy
  `xposedmodule`/`xposedminversion` metadata; module description = `android:description`.
- **No legacy `de.robv.android.xposed.*` API**: with `targetApiVersion=102` LSPosed does not
  provide the legacy bridge. Entry is `GodViewerModule : XposedModule()` (public no-arg ctor,
  `onModuleLoaded` + `onPackageReady`); hooking goes through the **transitional in-project
  shim** `target/hook/GvHook.kt` (`GvMethodHook` / `MethodHookParam` over
  `hook(method).intercept { chain -> … }`). Migration notes live in `HANDOFF.md`.
- **Injected UI** must use `ModuleRes.moduleRes` (created in `onModuleLoaded` from
  `getModuleApplicationInfo()`, no `XModuleResources`) via `target.dialog.ModuleDialogUi`
  for layouts/strings — **not** the host app’s `R` / target `Context.getResources()` alone.
  Tag module UI with `IGNORE_HOOK` (`GODVIEWER_IGNORE_HOOK`) or edit mode intercepts itself.
- Host UI (`host.MainActivity`, mirror activities, `host.ui/`) uses normal `R` + ViewBinding.
- All user-visible strings: keep **`res/values/strings.xml`** (EN) and
  **`res/values-zh-rCN/strings.xml`** (zh) in parallel.
- Do not run business hooks on self: `GodViewerModule` checks `BuildConfig.PACKAGE_NAME`
  (**not** `APPLICATION_ID`) and only hooks `ModuleStatus.isActivated` → true. **Activation
  chip = OR of 3 signals** (`ModuleStatus.check()`): ① `libxposed/service` bound
  (`host/service/LspService.kt` — LSPosed only delivers the manager binder to *enabled*
  modules; primary signal, no self-scope needed), ② injected-self flag set via reflection
  (`markInjectedActivated`), ③ the self-hook. Without any of them the chip may stay
  “inactive” — e.g. LSPosed forks without XposedService support and no self-scope.
- Touch/click/Popup edit hooks are **lazy** — installed when edit mode enables
  (`target.edit.EditModeTouchInterceptor`), not always-on at package-ready time.
- libxposed **API 102** is a Maven `compileOnly` dep (`io.github.libxposed:api:102.0.0`);
  the old `app/libs/api-82.jar` is no longer on the classpath (kept on disk only).
- `proguard-rules.pro` keeps broadly `com.godviewer.app.**` plus the official libxposed
  rules (`-adaptresourcefilecontents META-INF/xposed/java_init.list` + entry-class keep).
  New entry classes must be listed in `META-INF/xposed/java_init.list`.
- Persistence uses version-sensitive reflection (`ReflectUtil`, `ListenerInfo`,
  `View.mAttachInfo.mDebugLayout`, etc.) — stay API-safe. Corrupt `rules.json` → empty list;
  **never crash the target app**. ImageView original URL is not fully recoverable on reset.
- Rule match (`target/rule/ViewRuleUtil.findViewBestMatch`): activityClass + hierarchy `depth[]`
  + viewClass, with resourceName/text fallbacks, guarded by `matchVersionCode`. **`depth` is a
  position, not an identity** — drop one view anywhere above the target and every following
  sibling index shifts, so the path silently points at another control. Therefore:
  - local rule + unchanged app version → position trusted, depth hit is enough
  - version changed → depth hit additionally requires the resource name to match
  - **imported rule** (`ViewRule.imported`) → depth hit requires a non-empty resource name that
    matches; no resource name means it falls through to the resourceName/text fallbacks. It never
    accepts a bare positional hit, because a same-version app on another phone can still have a
    different layout (channel build, A/B flag, account state, screen class, dynamic list data).
  Saving a rule locally clears `imported`/`batchId` — the user has confirmed the control.
- Replay captures thumbnails **before** applying the rule: hiding sets the view to `GONE`, and
  `ViewSnapshot.capture` returns null for `GONE`. Capturing afterwards would mean hidden rules
  never get a picture (imported ones would show the placeholder forever).
- Rule mirror is **broadcast-only** best-effort. Do **not** re-add Service/ContentProvider cold-start
  delivery unless the user explicitly asks (already tried and reverted). Soft token:
  `godviewer-rule-mirror-v1`.
- Mirror thumbnails travel in their own `ACTION_MIRROR_THUMBS` batches, never inside the rules
  broadcast: a batch carries at most `MAX_THUMB_BATCH_BYTES` and several batches are sent until
  every rule with a thumbnail has been pushed. `thumbnails_mode` is `merge` (write / overwrite)
  or `replace` (drop files outside the announced key set, values left empty = "keep only, do not
  write"); deleting a rule pushes the full remaining key set with `replace` to prune orphans.
  Replay-captured thumbnails are pushed back on a 10-minute throttle (`target.mirror.ThumbnailSync`)
  so mirrors missing images heal themselves. Thumbnails are encoded as JPEG on a white matte at
  128px — do **not** switch icons to JPEG, they need alpha.
- Rule backup (`shared/backup/RuleBackupModels`, `host/backup/*`): one **zip**,
  `manifest.json` (`format: "godviewer-backup"`, `schema_version: 1`, `container: "zip"`) plus
  `packages/<pkg>/rules.json` and `packages/<pkg>/thumbnails/<key>.png|.jpg` stored raw with
  `STORED` (they are already PNG/JPEG — deflating them gains nothing). Contents are rules +
  thumbnails **only** — never settings (the hidden-app-icon toggle must not travel between
  devices) and never app icons (PackageManager provides them). Thumbnail keys are the same short
  hashes used by the mirror directory, so they land straight into `mirror/<pkg>/thumbs/<key>.png`.
  Import still reads the old single-file JSON container (base64 thumbnails) — sniffed by zip
  magic, not by extension. Read side is guarded: entry count, uncompressed byte budget, `..` /
  absolute paths rejected.
  Restore merges by rule key keeping the newer `timestamp`, writes the mirror, then delivers with
  `HostControlBridge.ACTION_IMPORT_RULES` — an **explicit package** broadcast, deliberately not
  `dispatchToTarget()` (that one needs a "last target" record the user may never have created).
  A running target writes and replays immediately; a pending copy in
  `filesDir/godviewer/backup_pending/<pkg>.json` is re-delivered when the target next reports
  foreground (`HostControlReceiverImpl` → `RuleBackupDelivery.flush`), which is what makes
  restore-after-reinstall and phone switches work. Re-delivery is idempotent by design.
- Restore delivers **only the incoming rules**, never the merged list — otherwise locally created
  rules would be re-stamped as imported (breaking their matching) and become eligible for
  "undo last restore". Each restore gets a `batchId` stamped on every rule it delivers; the host
  remembers it in `godviewer_backup` prefs so `RuleBackupImporter.undoLast` can strip that batch
  from the mirror (dropping the package dir entirely when nothing stays) and broadcast
  `ACTION_UNDO_IMPORT`. The target removes the batch, restores the affected views in live
  activities, and pushes the remaining key set so mirror thumbnails get pruned.
- Restore also carries thumbnails: `ACTION_IMPORT_THUMBS` batches (`ThumbPayload`: key → base64)
  land in the target's own `files/godviewer/thumbnails/<key>.png`, which is what the rule manager
  dialog reads. Without this, restored rules have no picture at all — the host mirror has one, but
  the in-target dialog does not share that storage.
- Rule backup / restore ships **knowingly unfinished** (documented as such in the changelog and in
  the restore preview dialog): matching still depends on the anchors a control happens to have, so
  a rule with neither resource name nor text silently does not apply, and a sufficiently different
  layout can still point at a neighbour. Do not quietly drop those caveats from user-facing copy
  — `Undo last restore` is the escape hatch and every mention of the feature should keep it
  visible.
- **Host-side rule management** (4.3.6): the host rules pages are no longer read-only. Supported
  ops are exactly `delete` / `visibility` / `text` / `restore` (adding new rules from the host is
  deliberately **not** implemented — the host has no screen-accurate view tree to pick from).
  Do not move whole rules over the bridge: only `op` + the rule key + guards + payload go out
  (`shared/control/RuleCommand`, action `HostControlBridge.ACTION_EDIT_RULES`), because a single
  broadcast has a ~1 MB binder limit and batched thumbnails already saturate it during restore.
  `ViewRuleManager.applyHostCommands` owns the target side; `host/manage/RuleMirrorManage` owns
  the host side (mirror edit → command → deliver).
  Three invariants hold it together and none may be dropped:
  - `expectedTimestamp`: the host holds a possibly stale mirror, so the target refuses a command
    whose rule `timestamp` differs (`stale++`) and, when nothing applied, calls
    `pushCurrentToHost()` so the host UI self-corrects. `stamp` is therefore **assigned by the
    host** and written to both sides — otherwise clock/timezone skew makes it fail for itself.
  - `imported` / `batchId` are **not** cleared here (unlike the target's own `saveRule`). The host
    edits a *list*; it never confirmed to the user which control is which, so downgrading an
    imported rule to "trust position" would undo exactly what `findViewBestMatch` distrusts.
  - After any applied change the target re-replays and **force**-flushes thumbnails
    (`ThumbnailSync.flushNewThumbnails(force = true)`, bypassing the 10-minute throttle), so the
    host list shows the *post-edit* look; rules whose visibility is `GONE` are skipped so they
    keep the pre-hide picture (a GONE view cannot be captured).
  Commands are queued in `filesDir/godviewer/pending_cmds/<pkg>.json`
  (`host/manage/RuleCommandStore`, merged by key+op, capped at 200) because the target receiver is
  registered in `Application.onCreate` and misses broadcasts while the process is dead; the queue
  flushes on `ACTION_TARGET_FOREGROUND` next to `RuleBackupDelivery.flush`. Redelivery is idempotent
  (a mismatched timestamp is skipped). Never claim "applied" in the UI — `ManageResult.dispatched`
  decides between "applied" and "queued until you reopen the app", and
  `RuleMirrorManage.pendingCount` drives the list hint.
  The mirror is updated **optimistically** (`RuleMirrorStore.saveManagedRules`, whole-file rewrite
  plus pruning orphan thumbs), so a delete removes the row immediately.
- Notification "Manage rules" (`TargetControlReceiver.ACTION_MANAGE_RULES`) used to be dropped
  whenever no `resumedActivity` existed, which reads as a dead button on OEM-restricted builds.
  It now parks itself via `ActivityLifecycleHooker.requestManageRulesDialog()` (60 s TTL) and the
  dialog opens 200 ms after the next `onPostResume`. Keep the `isFinishing`/`isDestroyed` guard.
- Host entry/control: `EntryMode` (`target` vs `host` vs `none`), `HostPrefs` + `HostPrefsProvider`
  (`content://com.godviewer.app.hostprefs/...`), control token `godviewer-host-control-v1`
  (soft guard, not crypto).
- `EntryMode.NONE`: never post the target-app notification (rules still replay — replay lives in
  `ActivityLifecycleHooker`, decoupled from notifications). Reachable from the **「Hide」button on
  either notification** — target: `EditModeNotification.ACTION_HIDE`; host:
  `HostControlNotifier.ACTION_HIDE` (both exit edit mode so touch interception cannot linger) —
  and from the host settings picker; target → host writeback is
  `HostControlBridge.ACTION_SET_ENTRY_MODE` (best-effort, same as rule mirror).
  Both notifications share the same contract: tapping the body toggles edit mode
  (`ACTION_TOGGLE` / `HostControlBridge.ACTION_TOGGLE_EDIT`), the body text states the current
  mode and what a tap will do, and **Hide is the first action** (SystemUI renders at most 3).
  The 600 ms `scheduleConfirmFallback` in `EditModeNotification` must use
  `shouldShowTargetNotification()` — using `isHostEntryInTarget()` makes a hidden notification
  reappear on cold start.
- Quick Settings tile (`host/tile/EntryModeTileService`, API 24+): a one-tap switch for the entry,
  labelled "God mode" (`qs_tile_label`). It stays a plain 1x1 tile — icon + label (plus a
  subtitle on API 29+) and no custom / large-tile layout, because large tiles are unreliable on
  many OEM builds; nothing in the app lets the user pick a tile size. Tile size cannot be pinned
  from the app (no size API), so Settings offers one-tap adding via
  `StatusBarManager.requestAddTileService` (API 33+) and a manual hint below that.
  Off → `EntryControlUi.hideEntry()` = `EntryMode.set(NONE)` **first**, then a best-effort
  `HostControlBridge.ACTION_DISABLE_EDIT` to the last target (idempotent). On →
  `EntryControlUi.restoreEntry()`: dispatch `ACTION_ENABLE_EDIT` **before** restoring
  `EntryMode.lastVisible()`, otherwise the target posts a "tap to enable" notification first and
  the text flickers; the host entry falls back to the target entry when
  `areNotificationsEnabled()` is false, because a tile cannot show the permission dialog.
  `EntryMode.set()` broadcasts **without a package** on purpose — only the last target would
  otherwise be updated, and targets sitting on `NONE` never re-query the host
  (`syncFromHostBeforePost` only runs for `TARGET`), so their notification would never come back.
  The target side exits edit mode whenever `ACTION_ENTRY_MODE_CHANGED` carries `mode == NONE`.
  `entry_mode_last_visible` keeps the last non-`NONE` mode for the tile. Turning the tile on
  enters edit mode in the last target unless the Settings switch
  (`settings_tile_enter_edit_*`) is turned off.
- Do **not** restore deleted AppList* UI (`AppListActivity` / adapters / layouts).
- Keep load-bearing misspellings: `PupupWindowHooker.kt`, `VeiwUtil.kt`.
- Logging: prefer `GvLog` (`GodViewer.<tag>`, forwarded to the framework log via the sink
  injected by `GodViewerModule`; host process falls back to logcat only).

## Conventions
- Kotlin only, official style, defensive null-safe (`?: return`, `runCatching`).
- Comments mixed English (older, `@author hhvvg`) and Chinese (newer `data/` / `util/`) — match nearby.
- Host layouts: `activity_*` / `fragment_*`. Injected dialogs: `layout_*`, inflated from `moduleRes`.
- Leave local/agent-only trees alone unless asked: `.zcode/`, `tools-Android/`, `HANDOFF.md`, `a.py`.

## Before changing sensitive areas
1. `README.md` — features, persistence design/limits, Android 16 + LSPosed checklist.
2. `HANDOFF.md` — session constraints, reverted experiments, “don’t restore X” notes.
