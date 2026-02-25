# Kaisoku Maintenance Status

## Current State

- Base app repo: `glitch-228/Kaisoku` (`devel`)
- Base parsers repo: `glitch-228/kaisoku-parsers` (`master`)
- User-facing app branding is `Kaisoku`.
- Fork sync automation exists in both repos:
  - `scripts/sync-fork-fixes.sh`
  - `.github/workflows/sync-fork-fixes.yml`
- Current app version: `9.6.5-10` (`versionCode 2017`)
- Current parser dependency pin: `cb1bdc33c6` (`[Grouple] Fix description parsing`)
- Latest release: `v9.6.5-10`
  - URL: `https://github.com/glitch-228/Kaisoku/releases/tag/v9.6.5-10`
  - Asset: `kaisoku-v9.6.5-10-installable.apk`
  - SHA-256: `056799c16a0370cd1fcf309332622266f400631a32ffab27e5b24e139de6b781`
  - Target commit: `6aa95d448`

## Recent Completed Work (2026-02-22 to 2026-02-23)

- Fixed provider authority conflict for side-by-side install compatibility with upstream Kotatsu.
- Added global performance/stability optimizations for intermittent long/empty loading:
  - Batch loading for list badges/counters (history/favorites/saved/tracker) instead of per-item lookups.
  - Coalescing/debouncing of realtime list remap triggers to reduce UI refresh storms.
  - Scoped manga override loading to visible IDs.
  - Debounced source catalog search refresh path.
- Added MangaOVH parser and completed follow-up fixes:
  - Domain migration to InkStory API.
  - Chapter image URL/404 handling.
  - Encrypted page support and decoding hardening.
- Fixed Grouple RU source chain regressions:
  - `div.subject-meta` compatibility chain for modern layout.
  - Description parsing fallback to `.cr-description__content` to avoid `"No description"`.
- UI behavior fixes:
  - Details title opens on first tap (no double-tap needed).
  - Additional titles are tap-openable with full text dialog/copy.
  - Manga card progress text no longer desyncs from progress circle when history refreshes.
- Built, signed, and published releases through `v9.6.5-10`.

## Repository Layout

- App repo: `Kaisoku/`
- Parsers repo: `kaisoku-parsers/`
- Upstream references used:
  - App: `KotatsuApp/Kotatsu`, `Kotatsu-Redo/Kotatsu-Redo`
  - Parsers: `KotatsuApp/kotatsu-parsers`, `Kotatsu-Redo/kotatsu-parsers-redo`

## How To Sync Fixes From Forks

### App (`Kaisoku`)

```bash
cd Kaisoku
scripts/sync-fork-fixes.sh            # dry run
scripts/sync-fork-fixes.sh --apply    # merge locally
scripts/sync-fork-fixes.sh --apply --push
```

### Parsers (`kaisoku-parsers`)

```bash
cd kaisoku-parsers
scripts/sync-fork-fixes.sh
scripts/sync-fork-fixes.sh --apply
scripts/sync-fork-fixes.sh --apply --push
```

## Parser Update Lifecycle

- The app does not fetch arbitrary parser repo HEAD at runtime.
- App consumes parser library pinned in `Kaisoku/gradle/libs.versions.toml` (`parsers = "cb1bdc33c6"`).
- For parser fixes to ship to users:
  1. Push parser fix commit to `kaisoku-parsers`.
  2. Bump parser pin in `Kaisoku`.
  3. Bump app version.
  4. Build/sign/publish new app release.

## Important Sync Notes

- Current default automation source set is intentionally mergeable:
  - App: `upstream,redo`
  - Parsers: `upstream,redo`
- `Yumemi` app history and `Yaka` parser history can require manual conflict work.
- If needed, selectively import fork fixes with manual cherry-pick:

```bash
git cherry-pick <commit-sha>
```

## Build Release APK

```bash
cd Kaisoku
./gradlew :app:assembleRelease --no-daemon
```

Primary output:

- `app/build/outputs/apk/release/app-release-unsigned.apk`

## Sign APK (Installable)

Example flow:

```bash
cd Kaisoku
apksigner sign \
  --ks releases/signing/kaisoku-release-<yyyymmdd>.jks \
  --ks-key-alias kaisoku-release \
  --ks-pass pass:'***' \
  --key-pass pass:'***' \
  --out releases/kaisoku-v<version>-installable.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

apksigner verify --verbose --print-certs releases/kaisoku-v<version>-installable.apk
sha256sum releases/kaisoku-v<version>-installable.apk
```

## Publish Release

```bash
gh release create <tag> releases/kaisoku-v<version>-installable.apk \
  -R glitch-228/Kaisoku \
  --target devel \
  --title "Kaisoku v<version>" \
  --notes "<notes>"
```

Replace asset in existing release:

```bash
gh release upload <tag> releases/kaisoku-v<version>-installable.apk -R glitch-228/Kaisoku --clobber
gh release view <tag> -R glitch-228/Kaisoku
```

## Recommended Ongoing Workflow

1. Sync app/parsers from upstream + redo.
2. Resolve conflicts and run build checks.
3. Push parser fixes first (if any), then bump parser pin in app.
4. Bump app version.
5. Build release APK and sign with production key.
6. Push app branch.
7. Publish release and attach signed APK.
8. Track user-reported issues in GitHub Issues.

## Operational Notes

- Local `releases/` artifacts are intentionally git-ignored.
- Current local rotated key files (not committed):
  - `releases/signing/kaisoku-release-20260222.jks`
  - `releases/signing/kaisoku-release-20260222.credentials.txt`
  - `releases/signing/kaisoku-release-20260222.cert.txt`
- Keep production signing keys outside the repo and backed up securely.
- Prefer small, scoped commits (sync commit, parser fix, app pin bump, release bump).
