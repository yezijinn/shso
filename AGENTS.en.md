> 语言 / Language: [中文](AGENTS.md)

# shso — AI Collaboration Guide

Graphical execution tool for scripts and native binaries in the Android ROOT environment.
Kotlin + AndroidX Compose Material 3, aurora-glass dark theme, no external UI component libraries.
Package name `com.mixradio.droid`, version name `Jinn`, version code is the build date.
Cold start goes straight to the four-tab home (Home / Terminal / File / Settings),
with no startup detection flow.

## Behavior Guidelines

### 1. Task-Driven Autonomous Loop

Follow the `TASKS.md` state machine; do not jump across steps.

- `TASKS.md` is the single source of truth for progress: read it first,
  advance only the current task, do not expand scope on your own.
- States: `[ ]` TODO, `[/]` IN_PROGRESS, `[x]` DONE and self-verified, `[!]` blocked
  or awaiting human confirmation.
- Execution lifecycle:
  1. Read & Lock: resume a `[/]` task first; otherwise take the first `[ ]`
     and mark it `[/]` in place.
  2. Execute: touch only files related to that task. If more than 3 core files are
     involved, append sub-tasks under the current node and split the work first.
  3. Verify: run targeted verification (compile, unit test, or syntax check).
  4. Write Back: mark `[x]` and record changed files and artifacts with 2-space indent.
     Never rewrite `TASKS.md` in full; apply local incremental edits only.
- Two-round circuit breaker: if the same error is not fixed after 2 attempts,
  set `[!]`, record the core exception and diagnosis below the task, and stop for instructions.

### 2. Think Before Coding

Do not assume, do not hide confusion, show trade-offs.

- Read project docs first; navigate by the scenario table and read only what is relevant.
- Follow docs first; search code or references only when something is not covered.
- Ask when uncertain; do not guess. List all interpretations when several exist.
- Propose a simpler solution if one exists; stop and state the blocker when stuck.

### 3. Simplicity First

Solve with minimal code; no speculative design.

- No features beyond the requirement scope; no abstraction for code used once.
- No unrequested flexibility or configurability; no error handling for impossible cases.
- When identical logic appears 2+ times, consider abstraction and state the trade-off.

### 4. Surgical Changes

Change only what must change; clean up the mess you create.

- Do not tidy up unrelated code; do not refactor what is not broken.
- Follow existing style even if you disagree.
- Flag unrelated dead code; do not delete it.

### 5. Goal-Driven Execution

Define success criteria; verify in loops.

- Add verification → define what "pass" means first, then implement.
- Fix a bug → find the minimal reproduction first, then fix.
- Refactor → keep behavior unchanged; use diff to help verify.
- For multi-step tasks, list a plan: `1. [step] → verify: [check]`.

## Tech Stack

| Item | Value |
|---|---|
| Language | Kotlin 2.4.0 (JVM Toolchain 21) |
| UI | AndroidX Compose Material 3 (compose-bom 2026.08.00, `ui/theme/Aurora*`) |
| Build | Gradle KTS + AGP 9.2.1 + Version Catalog (`gradle/libs.versions.toml`) |
| Target | minSdk 26 / targetSdk 35 / compileSdk 37, applicationId `com.mixradio.droid` |
| DI | No framework; global `object` singletons (`RootService`, `AppSettings`, etc.) |

**Self-contained project**: no dependency on out-of-repo source or modules;
builds standalone after clone. `settings.gradle.kts` only contains `include(":app")`.
UI styles unify via Aurora tokens in `ui/theme/`
(`AuroraTokens` / `AuroraGlass` / `AuroraComponents`);
pages must not use decorative `Color(0x...)` literals.

**UI Form (must obey on changes)**

- Zero rounded corners across the project: Card / Button / TextField / dialogs / panels /
  status dots / DockBar are all right-angle rectangles. Implemented by `AuroraShapes`
  (M3 Shapes, five slots, all `RoundedCornerShape(0.dp)`) injected into `MaterialTheme`;
  explicit `clip` / `shape` / `shadow` / `border` use the same value.
  The `foundation 1.12.0` cached artifact has no `RectangleShape` / `CircleShape` symbols.
- No outer Card / Container around list items: Settings, File, and Home pages are
  containerless lists; row content (icon + text Row) sits directly in the page Column.
- Page text uses inline font sizes as required (section title 14sp, preference title body2,
  summary in comment color); uniform row height `heightIn(min = 48.dp)`;
  dividers are 0.7dp lines (`SurfaceHover.copy(0.6f)`) or zero-gap.
- Adaptive Icon: `icon` / `roundIcon` point to `@mipmap/ic_launcher`
  (background + foreground layers); transparent background with a de-white / de-black
  foreground (currently lossless WebP, `mipmap-*/ic_launcher_foreground.webp`)
  indented into the center safe zone (≤72dp) to fit OEM masks.

**Background task constraint**: long-running scripts, downloads, and builds start
`ExecutionForegroundService` (`foregroundServiceType="dataSync"`) from
`RootService.executeFile()` as a foreground-service sentinel.
`RootService` still owns the Process, stdin, logs, cancellation, and task state;
the service only owns the notification, the kill-process action, and self-stopping.
Changes here must preserve the `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`
permissions, the non-exported service declaration, and the low-importance channel.

**DockBar layout constraint**: `innerPadding` of the `Scaffold` content in FilePage and
SettingsPage owns system insets; page content reserves an additional `56.dp` for the
bottom DockBar. Do not add `navigationBarsPadding()` for this purpose, and do not remove
the FilePage list wrapper's `weight(1f).fillMaxWidth().padding(bottom = 56.dp)`.

## Scenario Navigation

| Scenario | Document |
|---|---|
| Development plan and status | `TASKS.md` |
| Features and user-facing docs | `README.md` (no changelog; changes go to `更新日志.md`) |
| Record a change | `更新日志.md` (one line each: added / fixed / improved / security) |
| Tech stack, structure, architecture | `docs/PROJECT.md` |
| Change execution engine / ROOT logic | `docs/PROJECT.md` § RootService |
| Change UI pages / components | `docs/PROJECT.md` § UI Form |
| Change security logic | `docs/PROJECT.md` § Security Subsystem |
| Guard module / new wrappers | `module/shso_guard/README.md` |
| Which document to update | `docs/PROJECT.md` § Documentation Conventions |
| Documentation writing rules | `docs/文档规范.md` |
| Naming and code style | `docs/命名规范.md` |
| Online build / custom package name | `docs/在线编译.md` |
| Add dependency / change version | `gradle/libs.versions.toml` (no hardcoded versions in modules; exception: the 3 direct coordinates already in `app/build.gradle.kts`) |

## Common Commands

```bash
./gradlew :app:assembleDebug     # Debug build
./gradlew :app:assembleRelease   # Release build, output app/build/outputs/apk/
./gradlew :app:testDebugUnitTest # Unit tests
python build_apk.py              # One-click build: signing + version rules + payload checks
```

Only `arm64-v8a` is packaged (`ndk.abiFilters` in `app/build.gradle.kts`);
do not introduce multi-ABI native libraries.

## Notes

- File header: `// Copyright 2026, shso contributors` +
  `// SPDX-License-Identifier: Apache-2.0`; follow existing commit conventions.
- Signing config lives in `app/build.gradle.kts` (V2+V3, debug reuses release signing);
  `release.jks` is not in the repo.
- All `su -c` paths must use single-quote escaping (`replace("'", "'\\''")`);
  path handling must filter `..`, `\`, `\0`. Keep this when changing
  `RootFileManager` / `RootService`.
- `allowBackup=false`; do not enable it.
- On Windows, use the `\\?\` prefix for overly long build paths.
