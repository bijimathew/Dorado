# Kei Raw Source Install Spike

## Scope

Investigate how realistic it would be for Kaisoku to install Mihon-like sources:

- from a repo link such as `https://keiyoushi.github.io/add-repo` or `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json`
- from raw Keiyoushi source code without installing an APK

Assumption: "raw Kei source code" means the Kotlin/Gradle source tree published in `keiyoushi/extensions-source`, not the prebuilt APK repo.

## What The Keiyoushi Links Actually Provide

- `https://keiyoushi.github.io/add-repo` is only a redirect/manual-entry helper. The page tells Mihon users to add `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json`.
- `https://github.com/keiyoushi/extensions/blob/repo/repo.json` exposes repo metadata:
  - `name`
  - `website`
  - `signingKeyFingerprint`
- `index.min.json` is an extension catalog, not source code. Entries contain fields such as:
  - `pkg`
  - `apk`
  - `version`
  - `code`
  - `lang`
  - `nsfw`
  - `sources`

Conclusion: repo-link support is fundamentally "discover APK-backed extensions from a remote catalog".

## What Raw Keiyoushi Source Code Looks Like

`https://github.com/keiyoushi/extensions-source` is a real multi-module Kotlin/Gradle repository. The repo root includes `buildSrc`, `core`, `lib`, `lib-multisrc`, `src`, `build.gradle.kts`, and `settings.gradle.kts`.

Conclusion: this is not a lightweight script format that can be interpreted directly by Kaisoku. It is build input for producing extension APKs.

## Kaisoku Current State

Current Mihon support in Kaisoku is APK/package based:

- `app/src/main/kotlin/org/koitharu/kotatsu/core/parser/mihon/MihonExtensionManager.kt`
  - scans `PackageManager.getInstalledPackages(...)`
  - identifies extensions from manifest features/metadata such as `tachiyomi.extension`, `tachiyomi.extension.class`, and `tachiyomi.extension.factory`
  - loads extension code from `applicationInfo.sourceDir`
  - instantiates `Source` / `SourceFactory` classes via reflection
- `app/src/main/kotlin/org/koitharu/kotatsu/explore/data/MangaSourcesRepository.kt`
  - refreshes Mihon sources from package install/remove broadcasts only
- `app/src/main/kotlin/org/koitharu/kotatsu/core/parser/mihon/ChildFirstPathClassLoader.kt`
  - expects compiled dex/apk content, not raw Kotlin sources

Missing pieces for repo-link install:

- no extension repo storage
- no `repo.json` / `index.min.json` fetcher
- no trust model for signing fingerprints
- no private extension file store
- no download/install/update flow for remote extension APKs
- no UI for browsing repo-backed extensions
- no invalidation path for app-private extension files

## What Mihon Itself Does

Mihon does not install raw source code from repo links.

- `mihon/domain/.../CreateExtensionRepo.kt`
  - accepts URLs matching `https://.../index.min.json`
  - converts them to a base repo URL
  - fetches `repo.json`
- `mihon/app/.../ExtensionApi.kt`
  - reads `index.min.json`
  - builds available-extension models
  - derives APK download URLs as `repoUrl + /apk/ + apkName`
- `mihon/app/.../ExtensionInstaller.kt`
  - downloads the APK
  - either hands it to Android package installation or installs it into Mihon's private extension storage
- `mihon/app/.../ExtensionLoader.kt`
  - can load installed packages
  - can also load app-private extension APK files after signature checks

Important distinction:

- "Install from repo link" in Mihon means "download signed extension APKs from that repo"
- it does not mean "compile and run raw extension source code"

## Feasibility

### 1. Install from repo link by downloading extension APKs

Feasibility: moderate

This is the realistic path.

Why it is feasible:

- Kaisoku already has most of the Mihon runtime compatibility layer
- Kaisoku already uses a classloader-based extension runtime
- Mihon's private-extension approach can be adapted without requiring the user to install the APK as a normal Android app

Main work items:

- port or reimplement repo metadata storage (`repo.json`, `index.min.json`)
- add fingerprint trust handling
- add private extension file storage and signature verification
- teach `MihonExtensionManager` to scan both installed packages and app-private extension APKs
- add refresh/invalidation events for private extension changes
- add UI for:
  - add repo link
  - list available extensions
  - install/update/remove extension
  - trust/verify repo fingerprints

Rough effort: medium feature, likely several focused days for a narrow spike, roughly 1 to 2 weeks for a production-ready version with UI, update flow, and testing.

### 2. Install from raw Keiyoushi source code without APKs

Feasibility: very hard and not recommended

This is a different problem entirely.

Why it is hard:

- Keiyoushi sources are Kotlin/Gradle Android extension projects
- Kaisoku has no embedded Kotlin compiler, Gradle runner, D8/R8 pipeline, or packaging/signing flow on-device
- raw source cannot be loaded by `PathClassLoader`
- even if source compilation were added, dependency resolution, ABI compatibility, code signing, caching, and sandbox/security would become a large subsystem

Rough effort: large R&D project, likely multiple weeks at minimum and high maintenance risk afterward.

## Recommended Direction

If the goal is "install Mihon-like extensions from a link without making the user install a normal APK app", use this path:

1. Support Mihon repo URLs (`index.min.json` + `repo.json`)
2. Download signed extension APKs into Kaisoku-private storage
3. Load them with a private-extension variant of the current Mihon loader
4. Add update/uninstall/trust UI

Do not target raw source execution directly unless there is a separate builder service that converts source repos into signed extension artifacts first.

## Implementation Outline

Likely Kaisoku touch points:

- `app/src/main/kotlin/org/koitharu/kotatsu/core/parser/mihon/MihonExtensionManager.kt`
- `app/src/main/kotlin/org/koitharu/kotatsu/explore/data/MangaSourcesRepository.kt`
- new Mihon repo/domain/storage package for repo models and persistence
- new installer/downloader package for private extension APKs
- source/settings UI for repo management and extension installation

Suggested order:

1. Port private-extension loading first
2. Add repo metadata ingestion and trust storage
3. Add simple install/update/remove actions
4. Add deep-link handling for `add-repo`

## Recommendation Summary

- Repo link support: worth doing
- Private APK install without normal system install: worth doing
- Raw source install from `extensions-source`: not worth targeting directly
