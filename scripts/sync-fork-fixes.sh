#!/usr/bin/env bash
set -euo pipefail

APPLY=false
PUSH=false
TARGET_BRANCH="devel"
SOURCE_FILTER=""

SOURCE_SPECS=(
  "upstream|https://github.com/KotatsuApp/Kotatsu.git|devel"
  "redo|https://github.com/Kotatsu-Redo/Kotatsu-Redo.git|devel"
)

log() {
  printf '[sync] %s\n' "$*"
}

die() {
  printf '[sync] ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: scripts/sync-fork-fixes.sh [options]

Fetches upstream/fork branches and merges them into the local target branch.

Options:
  --apply                 Perform merges. Default is dry-run report only.
  --push                  Push target branch to origin after successful apply.
  --branch <name>         Target branch to sync (default: devel).
  --sources <csv>         Comma-separated source remotes to include.
                          Available: upstream,redo
  -h, --help              Show this help.
EOF
}

source_enabled() {
  local source_name="$1"
  if [[ -z "${SOURCE_FILTER}" ]]; then
    return 0
  fi
  [[ ",${SOURCE_FILTER}," == *",${source_name},"* ]]
}

ensure_clean_tree() {
  if ! git diff --quiet || ! git diff --cached --quiet; then
    die "Working tree has uncommitted changes. Commit or stash before running."
  fi
}

ensure_remote() {
  local name="$1"
  local url="$2"
  if git remote get-url "$name" >/dev/null 2>&1; then
    local current
    current="$(git remote get-url "$name")"
    if [[ "${current}" != "${url}" ]]; then
      log "Updating remote ${name} URL."
      git remote set-url "$name" "$url"
    fi
  else
    log "Adding remote ${name} -> ${url}"
    git remote add "$name" "$url"
  fi
}

print_divergence() {
  local source_ref="$1"
  local counts local_only source_only
  counts="$(git rev-list --left-right --count "HEAD...${source_ref}")"
  read -r local_only source_only <<<"${counts}"
  log "Divergence HEAD...${source_ref} => local:${local_only} source:${source_only}"
}

merge_source() {
  local source_ref="$1"
  local source_only
  source_only="$(git rev-list --count "HEAD..${source_ref}")"

  if (( source_only == 0 )); then
    log "${source_ref} already included."
    return 0
  fi

  if [[ "${APPLY}" != "true" ]]; then
    log "Dry-run: would merge ${source_ref} (${source_only} commits)."
    git log --oneline --max-count=5 "HEAD..${source_ref}" | sed 's/^/    /'
    return 0
  fi

  log "Merging ${source_ref} (${source_only} commits)..."
  if ! git merge --no-ff --no-edit -m "chore(sync): merge ${source_ref} into ${TARGET_BRANCH}" "${source_ref}"; then
    log "Merge conflict on ${source_ref}; aborting merge."
    git merge --abort || true
    return 1
  fi
  return 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply)
      APPLY=true
      shift
      ;;
    --push)
      PUSH=true
      shift
      ;;
    --branch)
      [[ $# -ge 2 ]] || die "--branch requires a value."
      TARGET_BRANCH="$2"
      shift 2
      ;;
    --sources)
      [[ $# -ge 2 ]] || die "--sources requires a value."
      SOURCE_FILTER="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown option: $1"
      ;;
  esac
done

if [[ "${PUSH}" == "true" && "${APPLY}" != "true" ]]; then
  die "--push requires --apply."
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "${REPO_ROOT}" ]] || die "Run this script from inside the Kaisoku repository."
cd "${REPO_ROOT}"

ORIGINAL_REF="$(git rev-parse --abbrev-ref HEAD)"
DIRTY=false
if ! git diff --quiet || ! git diff --cached --quiet; then
  DIRTY=true
fi

if [[ "${APPLY}" == "true" ]]; then
  ensure_clean_tree
fi

log "Fetching origin/${TARGET_BRANCH}..."
git fetch --prune origin "${TARGET_BRANCH}"
if [[ "${ORIGINAL_REF}" != "${TARGET_BRANCH}" ]]; then
  if [[ "${DIRTY}" == "true" ]]; then
    die "Current branch is dirty. Switch to ${TARGET_BRANCH} or clean up before running."
  fi
  git checkout "${TARGET_BRANCH}"
fi

if [[ "${APPLY}" == "true" ]]; then
  log "Fast-forwarding ${TARGET_BRANCH} from origin/${TARGET_BRANCH}..."
  git merge --ff-only "origin/${TARGET_BRANCH}"
fi

for spec in "${SOURCE_SPECS[@]}"; do
  IFS='|' read -r name url branch <<<"${spec}"
  if ! source_enabled "${name}"; then
    log "Skipping source ${name} due to --sources filter."
    continue
  fi

  ensure_remote "${name}" "${url}"
  log "Fetching ${name}/${branch}..."
  git fetch --prune "${name}" "${branch}"

  source_ref="${name}/${branch}"
  print_divergence "${source_ref}"
  merge_source "${source_ref}" || exit 2
done

if [[ "${PUSH}" == "true" ]]; then
  log "Pushing ${TARGET_BRANCH} to origin..."
  git push origin "${TARGET_BRANCH}"
fi

if [[ "${ORIGINAL_REF}" != "${TARGET_BRANCH}" ]]; then
  git checkout "${ORIGINAL_REF}" >/dev/null 2>&1 || true
fi

log "Sync complete."
