#!/usr/bin/env bash
# Runs in hosted changes from the protected base. Unverified inputs route to GitHub.
set -uo pipefail

GATE_DIR="$(dirname "${BASH_SOURCE[0]}")/canada-mini-gate"
REFILL_SH="$GATE_DIR/refill.sh"
WINDOW="${MINI_WINDOW:-}"
[ -n "$WINDOW" ] || WINDOW=$(bash "$REFILL_SH" --window 2>/dev/null) || WINDOW=""
[ "$WINDOW" = full ] || WINDOW=day
if [ -n "${MINI_QUEUE_THRESHOLD:-}" ]; then THRESHOLD="$MINI_QUEUE_THRESHOLD"
elif [ "$WINDOW" = full ]; then THRESHOLD="${MINI_QUEUE_THRESHOLD_FULL:-3}"
else THRESHOLD="${MINI_QUEUE_THRESHOLD_DAY:-2}"; fi
LABEL=cretas-mini
COUNT_LABELS="${MINI_COUNT_LABELS:-cretas-mini cretas-mini-pg cretas-mini-fast}"
MINI_WORKFLOWS="${MINI_WORKFLOWS:-java-gate-pr.yml rn-gate.yml web-admin-gate.yml}"
GH="${GH:-gh}"
REPO="${GITHUB_REPOSITORY:-}"
RUN_ID="${GITHUB_RUN_ID:-}"
GROUP_VERIFIED=false

emit() {
  local route="$1" queued="$2" reason="$3" runner=ubuntu-latest
  [ "$route" != mini ] || runner="$LABEL"
  echo "ROUTE=$route queued=$queued threshold=$THRESHOLD reason=$reason label=$LABEL window=$WINDOW"
  case "$reason" in api-error|bad-threshold|mini-offline|group-unverified)
    echo "::warning::mini route unavailable ($reason); using GitHub-hosted runner" ;; esac
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf 'runner=%s\nmini_window=%s\nroute_policy=v2\nmini_group_verified=%s\n' \
      "$runner" "$WINDOW" "$GROUP_VERIFIED" >> "$GITHUB_OUTPUT"
  fi
  exit 0
}

# Use the event’s immutable first-parent chain, with one GitHub merge-queue entry
# matched to each commit from the base (including earlier PRs). An incomplete or
# changed queue, an unsupported history shape, or an API error has no proof.
verify_merge_group() {
  local base="${GROUP_BASE_SHA:-}" head="${GROUP_HEAD_SHA:-}" ref="${GROUP_BASE_REF:-}"
  local chain commits= expected commit parent rest response
  [[ "$base" =~ ^[a-f0-9]{40}$ && "$head" =~ ^[a-f0-9]{40}$ && "$ref" == refs/heads/* ]] || return 1
  chain=$(git rev-list --first-parent --parents "$base..$head") || return 1
  [ -n "$chain" ] || return 1
  expected="$head"
  while read -r commit parent rest; do
    [ "$commit" = "$expected" ] && [ -n "$parent" ] || return 1
    commits="$commit $commits"
    expected="$parent"
  done <<< "$chain"
  [ "$expected" = "$base" ] || return 1
  local query='query($owner:String!,$name:String!,$branch:String!) {
    repository(owner:$owner,name:$name) { mergeQueue(branch:$branch) { entries(first:100) {
      nodes { position headCommit { oid } pullRequest { number headRepository { nameWithOwner }
        labels(first:100) { nodes { name } pageInfo { hasNextPage } } } }
    } } }
  }'
  response=$("$GH" api graphql -f query="$query" -f owner="${REPO%%/*}" -f name="${REPO#*/}" -f branch="${ref#refs/heads/}") || return 1
  jq -er --arg commits "$commits" --arg repo "$REPO" '
    ($commits | split(" ") | map(select(length > 0))) as $sha
    | if ((.errors // []) | length) != 0 then error("merge queue query failed") else . end
    | .data.repository.mergeQueue.entries.nodes
    | if type == "array" then sort_by(.position)[:($sha | length)] else error("no queue entries") end
    | if map(.headCommit.oid) != $sha or map(.position) != [range(1; ($sha | length) + 1)]
      then error("incomplete or changed merge group") else . end
    | if any(.[]; (.pullRequest.number | type) != "number" or .pullRequest.headRepository.nameWithOwner != $repo
            or (.pullRequest.labels.nodes | type) != "array" or .pullRequest.labels.pageInfo.hasNextPage != false)
      then error("fork or unverified PR") else . end
    | if any(.[]; any(.pullRequest.labels.nodes[]; .name == "ci-hosted")) then "ci-hosted"
      else map(.pullRequest.number | tostring) | join(",") end
  ' <<< "$response"
}

check_merge_group() {
  local group
  group=$(verify_merge_group 2>/dev/null) || emit hosted na group-unverified
  [ "$group" != ci-hosted ] || emit hosted na ci-hosted-label
  echo "READING: verified_merge_group_prs=$group head=${GROUP_HEAD_SHA:-}"
  GROUP_VERIFIED=true
}

case "${EVENT_NAME:-}" in
  pull_request) [ -n "$REPO" ] && [ "${HEAD_REPO:-}" = "$REPO" ] || emit hosted na fork ;;
  merge_group) ;;
  *) emit hosted na unsupported-event ;;
esac
[ "${GITHUB_RUN_ATTEMPT:-1}" = 1 ] || emit hosted na retry-hosted
# Explicit 0 is a hosted-only merge queue probe using the real workflow token.
if [ "${MINI_POOL_V2_ENABLED:-}" = 0 ] && [ "$EVENT_NAME" = merge_group ]; then
  check_merge_group
fi
[ "${MINI_POOL_V2_ENABLED:-}" = 1 ] || emit hosted na rollout-disabled
if jq -e 'index("ci-hosted") != null' <<< "${PR_LABELS:-[]}" >/dev/null 2>&1; then
  emit hosted na ci-hosted-label
fi
case "$THRESHOLD" in ''|*[!0-9]*) emit hosted na bad-threshold ;; esac

# Old heartbeats and run-start snapshots cannot prove capacity at dispatch.
# Snapshot format is unix-seconds:eligible-slots:idle-slots, published by refill.
read_capacity() {
  local stamp="${MINI_POOL_CAPACITY:-}" now="${ROUTE_NOW:-$(date +%s)}" age
  [[ "$stamp" =~ ^([1-9][0-9]{0,10}):([0-4]):([0-4])$ ]] || return 1
  age=$((now - BASH_REMATCH[1]))
  SLOTS=${BASH_REMATCH[2]}
  IDLE=${BASH_REMATCH[3]}
  echo "READING: capacity_age=$age slots=$SLOTS idle=$IDLE"
  [ "$age" -ge 0 ] && [ "$age" -le 180 ] && [ "$IDLE" -le "$SLOTS" ] # HEARTBEAT-AGE-CHECK
}
read_capacity || emit hosted na mini-offline
[ "$IDLE" -gt 0 ] || emit hosted na mini-full

if [ "${EVENT_NAME:-}" = merge_group ]; then
  check_merge_group
fi

if ! source "$GATE_DIR/queue-snapshot.sh"; then emit hosted na api-error; fi
if ! SNAPSHOT=$(mini_queue_snapshot "$REPO" "$RUN_ID" "$MINI_WORKFLOWS" "$COUNT_LABELS" 2>/dev/null); then
  emit hosted na api-error
fi
read -r QUEUED BUSY <<< "$SNAPSHOT"
echo "READING: queued=$QUEUED busy=$BUSY"
# The GitHub read can take time. Recheck the heartbeat immediately before dispatch.
read_capacity || emit hosted "$QUEUED" mini-offline
[ "$BUSY" -lt "$SLOTS" ] || emit hosted "$QUEUED" mini-full # BUSY-SLOT-CHECK
if [ "$QUEUED" -lt "$THRESHOLD" ]; then
  emit mini "$QUEUED" below-threshold
else
  emit hosted "$QUEUED" mini-full
fi
