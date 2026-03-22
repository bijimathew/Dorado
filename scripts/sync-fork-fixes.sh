#!/usr/bin/env bash
set -euo pipefail

APPLY=false
PUSH=false
TARGET_BRANCH="devel"
SOURCE_FILTER=""
MARK_REVIEWED=false

BASELINE_SPECS=(
  "upstream|https://github.com/KotatsuApp/Kotatsu.git|devel"
)

SOURCE_SPECS=(
  "redo|https://github.com/Kotatsu-Redo/Kotatsu-Redo.git|devel|upstream|auto"
  "futon|https://github.com/AppFuton/Futon.git|devel|upstream|manual"
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

Fetches upstream/fork branches and cherry-picks new stable commits into target.

Options:
  --apply                 Perform cherry-picks. Default is dry-run report only.
  --push                  Push target branch to origin after successful apply.
  --mark-reviewed         Record fetched source heads as reviewed so future syncs
                          only consider newer commits. This happens automatically
                          after successful auto-apply runs.
  --branch <name>         Target branch to sync (default: devel).
  --sources <csv>         Comma-separated source remotes to include.
                          Available: redo,futon
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

commit_subject() {
  git log -1 --format='%s' "$1"
}

reviewed_ref_name() {
  printf 'refs/fork-sync-reviewed/%s/%s' "${TARGET_BRANCH}" "$1"
}

get_reviewed_source_sha() {
  local source_name="$1"
  git rev-parse --verify -q "$(reviewed_ref_name "${source_name}")" 2>/dev/null || true
}

advance_reviewed_source() {
  local source_name="$1"
  local source_ref="$2"
  local source_sha ref_name
  source_sha="$(git rev-parse "${source_ref}")"
  ref_name="$(reviewed_ref_name "${source_name}")"
  git update-ref "${ref_name}" "${source_sha}"
  log "Recorded ${source_name} as reviewed at ${source_sha:0:12}"
}

commit_has_runtime_changes() {
  local sha="$1"
  local path
  while IFS= read -r path; do
    [[ -n "${path}" ]] || continue
    if [[ ! "${path}" =~ ^(README(\..*)?|docs/.*|\.github/.*|.*\.md|.*\.txt|LICENSE.*|COPYING.*|.*\.(png|jpg|jpeg|gif|svg|webp))$ ]]; then
      return 0
    fi
  done < <(git show --pretty='' --name-only "${sha}")
  return 1
}

commit_stability_reason() {
  local sha="$1"
  local subject lower_subject
  subject="$(commit_subject "${sha}")"
  lower_subject="$(printf '%s' "${subject}" | tr '[:upper:]' '[:lower:]')"

  if [[ "${subject}" =~ ^[0-9]+(\.[0-9]+)+([[:space:][:punct:]].*)?$ ]]; then
    printf 'release-version-bump'
    return 0
  fi

  if [[ "${lower_subject}" =~ (^merge\ |^revert\ |wip|draft|fixup\!|squash\!|tmp|temporary|experiment|debug|rebrand|workflow|ci\/cd|readme|changelog|badge|template|copilot|agents\.md|gitignore) ]]; then
    printf 'unstable-subject'
    return 0
  fi

  if grep -Eq '(^|/)io/github/landwarderer/futon/' < <(git show --pretty='' --name-only "${sha}"); then
    printf 'incompatible-package-path'
    return 0
  fi

  if ! commit_has_runtime_changes "${sha}"; then
    printf 'docs-meta-only'
    return 0
  fi

  printf 'stable'
  return 0
}

find_last_source_sha_from_history() {
  local source_name="$1"
  local source_branch="$2"
  local source_ref="$3"
  local base_ref="$4"
  local merge_commit source_sha
  local best_sha=""
  local best_distance=-1
  local distance

  merge_commit="$(git log --merges --grep="chore(sync): merge ${source_name}/${source_branch} into ${TARGET_BRANCH}" --format='%H' -n1 || true)"
  if [[ -n "${merge_commit}" ]]; then
    source_sha="$(git rev-parse "${merge_commit}^2" 2>/dev/null || true)"
    if [[ -n "${source_sha}" ]] &&
      git merge-base --is-ancestor "${source_sha}" "${source_ref}" 2>/dev/null &&
      { [[ -z "${base_ref}" ]] || ! git merge-base --is-ancestor "${source_sha}" "${base_ref}" 2>/dev/null; }; then
      distance="$(git rev-list --count "${source_sha}..${source_ref}")"
      best_sha="${source_sha}"
      best_distance="${distance}"
    fi
  fi

  while IFS= read -r source_sha; do
    if [[ -n "${source_sha}" ]] &&
      git merge-base --is-ancestor "${source_sha}" "${source_ref}" 2>/dev/null &&
      { [[ -z "${base_ref}" ]] || ! git merge-base --is-ancestor "${source_sha}" "${base_ref}" 2>/dev/null; }; then
      distance="$(git rev-list --count "${source_sha}..${source_ref}")"
      if [[ -z "${best_sha}" ]] || (( distance < best_distance )); then
        best_sha="${source_sha}"
        best_distance="${distance}"
      fi
    fi
  done < <(
    git log "${TARGET_BRANCH}" --grep='cherry picked from commit' --format='%B' |
      sed -n 's/.*cherry picked from commit \([0-9a-f]\{7,40\}\).*/\1/p'
  )

  if [[ -n "${best_sha}" ]]; then
    printf '%s' "${best_sha}"
    return 0
  fi

  return 1
}

resolve_baseline() {
  local source_name="$1"
  local source_branch="$2"
  local source_ref="$3"
  local base_ref="$4"
  local baseline source_sha

  source_sha="$(find_last_source_sha_from_history "${source_name}" "${source_branch}" "${source_ref}" "${base_ref}" || true)"
  if [[ -n "${source_sha}" ]]; then
    printf '%s' "${source_sha}"
    return 0
  fi

  if [[ -n "${base_ref}" ]]; then
    baseline="$(git merge-base "${source_ref}" "${base_ref}")"
    printf '%s' "${baseline}"
    return 0
  fi

  baseline="$(git merge-base HEAD "${source_ref}")"
  printf '%s' "${baseline}"
  return 0
}

apply_reviewed_baseline() {
  local baseline="$1"
  local reviewed_sha="$2"
  local source_ref="$3"
  if [[ -n "${reviewed_sha}" ]] &&
    git merge-base --is-ancestor "${reviewed_sha}" "${source_ref}" 2>/dev/null &&
    git merge-base --is-ancestor "${baseline}" "${reviewed_sha}" 2>/dev/null; then
    printf '%s' "${reviewed_sha}"
  else
    printf '%s' "${baseline}"
  fi
}

sync_source() {
  local source_name="$1"
  local source_branch="$2"
  local source_ref="$3"
  local base_source_name="$4"
  local source_mode="${5:-auto}"
  local base_ref=""
  local baseline range reviewed_sha baseline_note
  local candidate_count
  local stable_count skipped_count
  local sha reason subject
  local candidate_commits=()
  local stable_commits=()
  local skipped_commits=()

  if [[ -n "${base_source_name}" ]]; then
    base_ref="${SOURCE_REFS[${base_source_name}]:-}"
  fi

  baseline="$(resolve_baseline "${source_name}" "${source_branch}" "${source_ref}" "${base_ref}")"
  reviewed_sha="$(get_reviewed_source_sha "${source_name}")"
  baseline="$(apply_reviewed_baseline "${baseline}" "${reviewed_sha}" "${source_ref}")"
  range="${baseline}..${source_ref}"
  baseline_note=""
  if [[ -n "${reviewed_sha}" ]] && [[ "${baseline}" == "${reviewed_sha}" ]]; then
    baseline_note=", reviewed:${reviewed_sha:0:12}"
  fi

  if [[ -n "${base_ref}" ]]; then
    while IFS= read -r sha; do
      [[ -n "${sha}" ]] || continue
      candidate_commits+=("${sha}")
    done < <(git rev-list --reverse --no-merges "${range}" "^${base_ref}" "^HEAD")
  else
    while IFS= read -r sha; do
      [[ -n "${sha}" ]] || continue
      candidate_commits+=("${sha}")
    done < <(git rev-list --reverse --no-merges "${range}" "^HEAD")
  fi

  candidate_count="${#candidate_commits[@]}"
  if (( candidate_count == 0 )); then
    if [[ -n "${reviewed_sha}" ]] && [[ "${baseline}" == "${reviewed_sha}" ]]; then
      log "${source_ref} has no commits newer than reviewed cursor ${reviewed_sha:0:12}."
    else
      log "${source_ref} already included."
    fi
    if [[ "${MARK_REVIEWED}" == "true" ]]; then
      advance_reviewed_source "${source_name}" "${source_ref}"
    fi
    return 0
  fi

  for sha in "${candidate_commits[@]}"; do
    reason="$(commit_stability_reason "${sha}")"
    if [[ "${reason}" == "stable" ]]; then
      stable_commits+=("${sha}")
    else
      skipped_commits+=("${sha}")
    fi
  done

  stable_count="${#stable_commits[@]}"
  skipped_count="${#skipped_commits[@]}"

  if [[ "${APPLY}" != "true" ]]; then
    local mode_note=""
    if [[ "${source_mode}" == "manual" ]]; then
      mode_note=", mode:manual-review"
    fi
    if [[ -n "${base_ref}" ]]; then
      log "Dry-run: ${source_ref} => candidates:${candidate_count} stable:${stable_count} skipped:${skipped_count}${mode_note}${baseline_note} (range ${range}, excluding ${base_ref} and HEAD)"
    else
      log "Dry-run: ${source_ref} => candidates:${candidate_count} stable:${stable_count} skipped:${skipped_count}${mode_note}${baseline_note} (range ${range}, excluding HEAD)"
    fi
    if (( stable_count > 0 )); then
      for sha in "${stable_commits[@]:0:10}"; do
        subject="$(commit_subject "${sha}")"
        printf '    + %s %s\n' "${sha:0:12}" "${subject}"
      done
      if (( stable_count > 10 )); then
        printf '    ... (%d more stable commits)\n' "$((stable_count - 10))"
      fi
    fi
    if (( skipped_count > 0 )); then
      for sha in "${skipped_commits[@]:0:5}"; do
        subject="$(commit_subject "${sha}")"
        reason="$(commit_stability_reason "${sha}")"
        printf '    - %s %s [%s]\n' "${sha:0:12}" "${subject}" "${reason}"
      done
      if (( skipped_count > 5 )); then
        printf '    ... (%d more skipped commits)\n' "$((skipped_count - 5))"
      fi
    fi
    if [[ "${MARK_REVIEWED}" == "true" ]]; then
      advance_reviewed_source "${source_name}" "${source_ref}"
    elif [[ "${source_mode}" == "manual" ]]; then
      log "Tip: rerun with --sources ${source_name} --mark-reviewed after you decide to skip the current manual-review queue."
    fi
    return 0
  fi

  if [[ "${source_mode}" == "manual" ]]; then
    log "Skipping auto-apply for ${source_ref}: source is marked as manual-review."
    if [[ "${MARK_REVIEWED}" == "true" ]]; then
      advance_reviewed_source "${source_name}" "${source_ref}"
    fi
    return 0
  fi

  if (( stable_count == 0 )); then
    log "No stable commits to apply from ${source_ref} (skipped:${skipped_count})."
    advance_reviewed_source "${source_name}" "${source_ref}"
    return 0
  fi

  log "Applying ${stable_count} stable commits from ${source_ref}..."
  for sha in "${stable_commits[@]}"; do
    subject="$(commit_subject "${sha}")"
    log "Cherry-pick ${sha:0:12} ${subject}"
    if ! git cherry-pick -x "${sha}"; then
      if git diff --quiet && git diff --cached --quiet; then
        log "Commit ${sha:0:12} is already effectively applied. Skipping."
        git cherry-pick --skip || true
        continue
      fi
      log "Cherry-pick conflict on ${sha:0:12}; aborting."
      git cherry-pick --abort || true
      return 1
    fi
  done

  if (( skipped_count > 0 )); then
    log "Skipped ${skipped_count} non-stable commits from ${source_ref}."
  fi
  advance_reviewed_source "${source_name}" "${source_ref}"
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
    --mark-reviewed)
      MARK_REVIEWED=true
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

declare -A SOURCE_REFS
declare -A SOURCE_BRANCHES
declare -A SOURCE_BASES
declare -A SOURCE_MODES

for spec in "${BASELINE_SPECS[@]}"; do
  IFS='|' read -r name url branch <<<"${spec}"
  ensure_remote "${name}" "${url}"
  log "Fetching baseline ${name}/${branch}..."
  git fetch --prune "${name}" "${branch}"

  SOURCE_REFS["${name}"]="${name}/${branch}"
  SOURCE_BRANCHES["${name}"]="${branch}"
  SOURCE_BASES["${name}"]=""
  SOURCE_MODES["${name}"]="baseline"
done

for spec in "${SOURCE_SPECS[@]}"; do
  IFS='|' read -r name url branch base_name mode <<<"${spec}"
  if ! source_enabled "${name}"; then
    log "Skipping source ${name} due to --sources filter."
    continue
  fi

  ensure_remote "${name}" "${url}"
  log "Fetching ${name}/${branch}..."
  git fetch --prune "${name}" "${branch}"

  SOURCE_REFS["${name}"]="${name}/${branch}"
  SOURCE_BRANCHES["${name}"]="${branch}"
  SOURCE_BASES["${name}"]="${base_name}"
  SOURCE_MODES["${name}"]="${mode:-auto}"
done

for spec in "${SOURCE_SPECS[@]}"; do
  IFS='|' read -r name _ _ _ <<<"${spec}"
  if [[ -z "${SOURCE_REFS[${name}]:-}" ]]; then
    continue
  fi

  source_ref="${SOURCE_REFS[${name}]}"
  print_divergence "${source_ref}"
  sync_source "${name}" "${SOURCE_BRANCHES[${name}]}" "${source_ref}" "${SOURCE_BASES[${name}]}" "${SOURCE_MODES[${name}]}" || exit 2
done

if [[ "${PUSH}" == "true" ]]; then
  log "Pushing ${TARGET_BRANCH} to origin..."
  git push origin "${TARGET_BRANCH}"
fi

if [[ "${ORIGINAL_REF}" != "${TARGET_BRANCH}" ]]; then
  git checkout "${ORIGINAL_REF}" >/dev/null 2>&1 || true
fi

log "Sync complete."
