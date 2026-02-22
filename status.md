# Kaisoku Maintenance Status

## Current State

- Base app repo: `glitch-228/Kaisoku` (`devel`)
- Base parsers repo: `glitch-228/kaisoku-parsers` (`master`)
- User-facing app branding updated to `Kaisoku`.
- Fork sync automation exists in both repos:
  - `scripts/sync-fork-fixes.sh`
  - `.github/workflows/sync-fork-fixes.yml`
- Current app version: `9.6.5-2` (`versionCode 2009`)

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

## Important Sync Notes

- The current default automation source set is intentionally mergeable:
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
./gradlew :app:assembleRelease
```

Primary output:

- `app/build/outputs/apk/release/app-release-unsigned.apk`

## Sign APK (Installable)

Example flow for installable releases:

```bash
cd Kaisoku
keytool -genkeypair -v \
  -keystore releases/signing/kaisoku-release-<yyyymmdd>.jks \
  -storepass '***' -keypass '***' \
  -alias 'kaisoku-release' -keyalg RSA -keysize 4096 -validity 10000 \
  -dname 'CN=Kaisoku Release, OU=Development, O=Kaisoku, L=NA, ST=NA, C=US'

apksigner sign \
  --ks releases/signing/kaisoku-release-<yyyymmdd>.jks \
  --ks-key-alias kaisoku-release \
  --ks-pass pass:'***' \
  --key-pass pass:'***' \
  --out releases/kaisoku-v<version>-installable.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

apksigner verify --verbose --print-certs releases/kaisoku-v<version>-installable.apk
```

## Publish Release

```bash
gh release create <tag> releases/kaisoku-v<version>-installable.apk \
  -R glitch-228/Kaisoku \
  --target devel \
  --title "<title>" \
  --notes "<notes>"
```

Update assets in an existing release:

```bash
gh release upload <tag> releases/kaisoku-v<version>-installable.apk -R glitch-228/Kaisoku --clobber
gh release view <tag> -R glitch-228/Kaisoku
```

## Recommended Ongoing Workflow

1. Sync app/parsers from upstream + redo.
2. Resolve conflicts and run build checks.
3. Build release APK.
4. Sign APK with stable production keystore.
5. Push branches.
6. Publish release + attach signed APK.
7. Track user-reported issues in GitHub Issues.

## Operational Notes

- Local `releases/` artifacts are intentionally git-ignored.
- Current local rotated key files (not committed):
  - `releases/signing/kaisoku-release-20260222.jks`
  - `releases/signing/kaisoku-release-20260222.credentials.txt`
  - `releases/signing/kaisoku-release-20260222.cert.txt`
- Keep production signing keys outside the repo and backed up securely.
- Prefer small, scoped commits (sync commit, conflict fix commit, release prep commit).
