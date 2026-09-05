> 语言 / Language: [中文](AGENTS.md)

# shso — AI Development Guide

A graphical execution tool for scripts / native binaries running in the Android ROOT environment (Kotlin + Jetpack Compose Material 3 native controls, aurora-glass dark theme, no external UI component libraries). Package name `com.mixradio.droid`, version 9.0.2/283. Cold-start goes straight to the four-tab home (Home / Terminal / File / Settings) with no startup detection flow.

## Behavior Guidelines

### 1. Think Before Coding
**Do not assume, do not hide confusion, show trade-offs.**
- Read project docs before development to understand structure, conventions, and constraints
- Navigate by the scenario table and read only task-relevant docs, not everything at once
- Follow docs first; search code / references only when something is not covered
- Ask when uncertain; do not guess
- When multiple interpretations exist, list all possibilities
- Propose a simpler solution if one exists
- Stop and clearly state what is unclear when stuck

### 2. Simplicity First
**Solve with minimal code; no speculative design.**
- Do not build features beyond the requirement scope
- Do not abstract code used only once
- Do not add unrequested "flexibility" or "configurability"
- Do not write error handling for impossible scenarios
- Avoid duplication: when identical logic appears 2+ times, consider abstraction and state the trade-off

### 3. Surgical Changes
**Change only what must change; clean up the mess you create.**
- Do not "tidy up" unrelated code along the way
- Do not refactor things that are not broken
- Follow existing style even if you disagree
- Flag unrelated dead code; do not delete it

### 4. Goal-Driven Execution
**Define success criteria; verify in loops.**
- "add verification" → "think clearly what 'pass' means, then implement"
- "fix bug" → "find the minimal reproduction first, then fix"
- "refactor X" → "ensure behavior unchanged; use diff to help verify"
- For multi-step tasks, list a plan: 1. [step] → verify: [check]

## Tech Stack Overview

| Item | Value |
|---|---|
| Language | Kotlin 2.4.0 (JVM Toolchain 21) |
| UI | AndroidX Compose Material 3 native controls (compose-bom 2026.08.00, namespace `androidx.compose.*`, `ui/theme/Aurora*` aurora-glass theme) |
| Build | Gradle (KTS) + AGP 9.2.1 + Version Catalog (`gradle/libs.versions.toml`) |
| Target | minSdk 26 / targetSdk 35 / compileSdk 37, applicationId `com.mixradio.droid` |
| DI | No framework; global `object` singletons (`RootService`, `AppSettings`, etc.) |

**Self-contained project (key constraint)**: depends on no out-of-repo source / modules; can be built standalone after clone. `settings.gradle.kts` only `include(":app")`; the one-click build script `build_apk.py` includes native Material 3 checks (forbids falling back to external UI component libraries). UI styles unify via Aurora tokens in `ui/theme/` (`AuroraTokens` / `AuroraGlass` / `AuroraComponents`); pages must not write decorative `Color(0x...)` literals.

**UI Form Iron Rules (must obey on changes)**:
- Zero rounded corners across the project: all Card / Button / TextField / dialogs / panels / status-dots / DockBar are right-angle rectangles. Implementation: ① `AuroraShapes` (M3 Shapes five slots `RoundedCornerShape(0.dp)`) injected into `MaterialTheme`; ② explicit `clip / shape / shadow / border` all use `RoundedCornerShape(0.dp)`. The `foundation 1.12.0` cached artifact's shape package has **no `RectangleShape` / `CircleShape` symbols** — do not import.
- Forbid outer Card / Container wrappers around list items: Settings / File / Home pages are all containerless lists; row content (icon + text Row) sits directly in the page Column.
- Page text uses inline font sizes per requirement (e.g. section title 14sp, preference main title body2, summary in comment color); uniform row height `heightIn(min = 48.dp)`; dividers are 0.7dp thin lines (`SurfaceHover.copy(0.6f)`) or pure zero-gap.
- **Adaptive Icon**: `AndroidManifest.xml` `icon` / `roundIcon` point to `@mipmap/ic_launcher` (background + foreground layers); transparent background, foreground is a de-white / de-black colored PNG indented into the center safe zone (≤72dp) to display fully under circular / rounded-rect / teardrop OEM masks; no need to produce separate images per shape.

## Scenario Navigation

| Scenario | Read doc |
|---|---|
| Tech stack, directory structure, architecture | `docs/PROJECT.md` |
| Change execution engine / ROOT logic | `docs/PROJECT.md` § Core Modules |
| Change UI pages / components | `docs/PROJECT.md` § UI Layer |
| Add dependency / change version | `gradle/libs.versions.toml` (do not hardcode versions in modules; exception: the 3 direct coordinates already in `app/build.gradle.kts`) |

## Common Commands

```bash
./gradlew :app:assembleDebug     # Debug APK
./gradlew :app:assembleRelease   # Release APK (output in app/build/outputs/apk/)
```

## Notes

- Code comment / commit convention: file header `// Copyright 2026, shso contributors` + `SPDX-License-Identifier: Apache-2.0`
- Signing config in `app/build.gradle.kts` (V2+V3, debug reuses release signing); `release.jks` is not in the repo
- All `su -c` paths must use single-quote escaping (`replace("'", "'\\''")`); path handling must filter `..`, `\`, `\0` (anti-injection / traversal) — mandatory when changing RootFileManager / RootService
- `allowBackup=false`, do not enable
- On Windows, use the `\\?\` prefix for overly long build paths
