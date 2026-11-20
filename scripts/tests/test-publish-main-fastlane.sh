#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
FASTLANE="$ROOT_DIR/scripts/deploy/publish-main-fastlane.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

create_fixture() {
    local name="$1"
    local remote="$TMP_ROOT/$name-remote.git"
    local repo="$TMP_ROOT/$name"

    git init --bare --quiet "$remote"
    git init --quiet -b main "$repo"
    git -C "$repo" config user.name "Fastlane Test"
    git -C "$repo" config user.email "fastlane@example.test"
    mkdir -p "$repo/scripts/deploy" "$repo/docs/dispatch" "$repo/web-admin/src"
    cp "$FASTLANE" "$repo/scripts/deploy/publish-main-fastlane.sh"
    cat > "$repo/scripts/deploy/release-preflight.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
git diff --check
echo "fixture preflight passed $*"
EOF
    chmod +x "$repo/scripts/deploy/"*.sh
    printf '# ACTIVE\n\n## In Flight\n\n- None.\n\n## Scope Locks\n\n- None.\n' \
        > "$repo/docs/dispatch/ACTIVE.md"
    printf 'base\n' > "$repo/web-admin/src/app.ts"
    git -C "$repo" add .
    git -C "$repo" commit --quiet -m base
    git -C "$repo" remote add origin "$remote"
    git -C "$repo" push --quiet -u origin main
    git -C "$repo" switch --quiet -c codex/fixture
    printf 'feature\n' >> "$repo/web-admin/src/app.ts"
    git -C "$repo" add web-admin/src/app.ts
    git -C "$repo" commit --quiet -m feature
    printf '%s\n' "$repo"
}

# 旧用例验的是闸 (确认串/脏树/ACTIVE/过期 base/高风险/CRLF), 发布动作用 direct 模式落到本地裸仓;
# PR 模式 (默认) 在文件末尾用假 gh 单独验。
run_fastlane() {
    local repo="$1"
    shift
    (cd "$repo" && FASTLANE_PUBLISH_MODE=direct bash scripts/deploy/publish-main-fastlane.sh "$@")
}

success_repo=$(create_fixture success)
success_base=$(git -C "$success_repo" rev-parse origin/main)
success_head=$(git -C "$success_repo" rev-parse HEAD)
run_fastlane "$success_repo" --base-sha "$success_base" --confirm YES-DIRECT-MAIN \
    > "$TMP_ROOT/success.log"
[[ "$(git --git-dir="$TMP_ROOT/success-remote.git" rev-parse main)" == "$success_head" ]] \
    || fail "valid fastlane did not fast-forward main"
grep -Fq "FASTLANE_PUBLISHED main=$success_head" "$TMP_ROOT/success.log" \
    || fail "success receipt missing"

confirm_repo=$(create_fixture confirm)
confirm_base=$(git -C "$confirm_repo" rev-parse origin/main)
if run_fastlane "$confirm_repo" --base-sha "$confirm_base" --confirm NO >/dev/null 2>&1; then
    fail "invalid confirmation was accepted"
fi

dirty_repo=$(create_fixture dirty)
dirty_base=$(git -C "$dirty_repo" rev-parse origin/main)
printf 'dirty\n' > "$dirty_repo/untracked.txt"
if run_fastlane "$dirty_repo" --base-sha "$dirty_base" --confirm YES-DIRECT-MAIN >/dev/null 2>&1; then
    fail "dirty worktree was accepted"
fi

active_repo=$(create_fixture active)
active_base=$(git -C "$active_repo" rev-parse origin/main)
perl -0pi -e 's/- None\./- `TASK` - `review`/' "$active_repo/docs/dispatch/ACTIVE.md"
git -C "$active_repo" add docs/dispatch/ACTIVE.md
git -C "$active_repo" commit --quiet -m "leave task active"
if run_fastlane "$active_repo" --base-sha "$active_base" --confirm YES-DIRECT-MAIN >/dev/null 2>&1; then
    fail "unfinished ACTIVE task was accepted"
fi

# Scoped ACTIVE gate. The real ledger permanently carries dozens of unrelated
# in-flight tasks from other sessions, so the unscoped gate rejects every
# non-docs publish no matter how clean your own batch is. --task-id narrows the
# check to the caller's own row.
scoped_repo=$(create_fixture scoped)
scoped_base=$(git -C "$scoped_repo" rev-parse origin/main)
scoped_head=$(git -C "$scoped_repo" rev-parse HEAD)
printf '# ACTIVE\n\n## In Flight\n\n- `OTHER-TASK-1` - `in-progress` - someone else\n- `OTHER-TASK-2` - `review` - someone else\n\n## Scope Locks\n\n- None.\n' \
    > "$scoped_repo/docs/dispatch/ACTIVE.md"
# The caller archives its own row in the same commit — that archive entry is
# what proves the scoped ID is real rather than a typo.
mkdir -p "$scoped_repo/docs/dispatch/archive"
printf '# archive\n\n- `MY-TASK-42` - done\n' > "$scoped_repo/docs/dispatch/archive/2026-07-29.md"
git -C "$scoped_repo" add docs/dispatch
git -C "$scoped_repo" commit --quiet -m "unrelated tasks in flight; mine archived"
scoped_head=$(git -C "$scoped_repo" rev-parse HEAD)
if run_fastlane "$scoped_repo" --base-sha "$scoped_base" --confirm YES-DIRECT-MAIN >/dev/null 2>&1; then
    fail "unscoped gate accepted a ledger with unfinished tasks"
fi
run_fastlane "$scoped_repo" --base-sha "$scoped_base" --confirm YES-DIRECT-MAIN \
    --task-id MY-TASK-42 > "$TMP_ROOT/scoped.log"
[[ "$(git --git-dir="$TMP_ROOT/scoped-remote.git" rev-parse main)" == "$scoped_head" ]] \
    || fail "--task-id did not publish while unrelated tasks were still in flight"

# A typo'd or unknown --task-id must not silently disable the gate. Scoping is
# "my own row is archived", so an ID the ledger has never heard of means the
# caller mistyped it, and accepting that would turn the gate into a no-op.
typo_repo=$(create_fixture typo)
typo_base=$(git -C "$typo_repo" rev-parse origin/main)
printf '# ACTIVE\n\n## In Flight\n\n- `OTHER-TASK-1` - `in-progress` - someone else\n\n## Scope Locks\n\n- None.\n' \
    > "$typo_repo/docs/dispatch/ACTIVE.md"
mkdir -p "$typo_repo/docs/dispatch/archive"
printf '# archive\n\n- `MY-TASK-42` - done\n' > "$typo_repo/docs/dispatch/archive/2026-07-29.md"
git -C "$typo_repo" add docs/dispatch
git -C "$typo_repo" commit --quiet -m "archive my task"
if run_fastlane "$typo_repo" --base-sha "$typo_base" --confirm YES-DIRECT-MAIN \
    --task-id MY-TASK-4 >/dev/null 2>&1; then
    fail "a --task-id unknown to the ledger silently bypassed the ACTIVE gate"
fi
# The correctly spelled, archived ID still publishes.
typo_head=$(git -C "$typo_repo" rev-parse HEAD)
run_fastlane "$typo_repo" --base-sha "$typo_base" --confirm YES-DIRECT-MAIN \
    --task-id MY-TASK-42 > "$TMP_ROOT/typo.log"
[[ "$(git --git-dir="$TMP_ROOT/typo-remote.git" rev-parse main)" == "$typo_head" ]] \
    || fail "an archived task ID was rejected"

# ...but the caller's OWN unfinished row must still block.
mine_repo=$(create_fixture mine)
mine_base=$(git -C "$mine_repo" rev-parse origin/main)
printf '# ACTIVE\n\n## In Flight\n\n- `OTHER-TASK-1` - `in-progress` - someone else\n- `MY-TASK-42` - `review` - mine\n\n## Scope Locks\n\n- None.\n' \
    > "$mine_repo/docs/dispatch/ACTIVE.md"
git -C "$mine_repo" add docs/dispatch/ACTIVE.md
git -C "$mine_repo" commit --quiet -m "my task still active"
if run_fastlane "$mine_repo" --base-sha "$mine_base" --confirm YES-DIRECT-MAIN \
    --task-id MY-TASK-42 >/dev/null 2>&1; then
    fail "--task-id accepted a publish while the caller's own task was unfinished"
fi

stale_repo=$(create_fixture stale)
stale_base=$(git -C "$stale_repo" rev-parse origin/main)
git -C "$stale_repo" push --quiet origin HEAD:refs/heads/concurrent-advance
git --git-dir="$TMP_ROOT/stale-remote.git" update-ref refs/heads/main \
    "$(git --git-dir="$TMP_ROOT/stale-remote.git" rev-parse concurrent-advance)"
if run_fastlane "$stale_repo" --base-sha "$stale_base" --confirm YES-DIRECT-MAIN >/dev/null 2>&1; then
    fail "stale registered base was accepted"
fi

risk_repo=$(create_fixture risk)
risk_base=$(git -C "$risk_repo" rev-parse origin/main)
printf '# policy\n' > "$risk_repo/AGENTS.md"
git -C "$risk_repo" add AGENTS.md
git -C "$risk_repo" commit --quiet -m policy
if run_fastlane "$risk_repo" --base-sha "$risk_base" --confirm YES-DIRECT-MAIN >/dev/null 2>&1; then
    fail "high-risk change was accepted without explicit review override"
fi
run_fastlane "$risk_repo" --base-sha "$risk_base" --confirm YES-DIRECT-MAIN \
    --allow-high-risk YES-HIGH-RISK-REVIEWED > "$TMP_ROOT/risk.log"

dry_repo=$(create_fixture dry)
dry_base=$(git -C "$dry_repo" rev-parse origin/main)
dry_remote_before=$(git --git-dir="$TMP_ROOT/dry-remote.git" rev-parse main)
run_fastlane "$dry_repo" --base-sha "$dry_base" --confirm YES-DIRECT-MAIN --dry-run \
    > "$TMP_ROOT/dry.log"
[[ "$(git --git-dir="$TMP_ROOT/dry-remote.git" rev-parse main)" == "$dry_remote_before" ]] \
    || fail "dry-run changed remote main"
grep -Fq 'FASTLANE_DRY_RUN: git push origin HEAD:refs/heads/main' "$TMP_ROOT/dry.log" \
    || fail "dry-run receipt missing"

crlf_repo=$(create_fixture crlf)
crlf_base=$(git -C "$crlf_repo" rev-parse origin/main)
printf 'first\r\nsecond\r\n' > "$crlf_repo/web-admin/src/crlf.ts"
git -C "$crlf_repo" add web-admin/src/crlf.ts
git -C "$crlf_repo" commit --quiet -m "add CRLF source"
crlf_head=$(git -C "$crlf_repo" rev-parse HEAD)
run_fastlane "$crlf_repo" --base-sha "$crlf_base" --confirm YES-DIRECT-MAIN \
    > "$TMP_ROOT/crlf.log"
[[ "$(git --git-dir="$TMP_ROOT/crlf-remote.git" rev-parse main)" == "$crlf_head" ]] \
    || fail "valid CRLF commit did not fast-forward main"

if grep -Ev '^\s*#' "$FASTLANE" | grep -Eq 'git push.*(--force|--force-with-lease)'; then
    fail "fastlane script contains a force-push path"
fi

# ---- PR 模式 (默认): 假 gh 记录调用; merge 时把裸仓 main 指到 PR 头, 模拟 GitHub 合并 ----
FAKE_BIN="$TMP_ROOT/fake-bin"
mkdir -p "$FAKE_BIN"
cat > "$FAKE_BIN/gh" <<'EOF'
#!/usr/bin/env bash
# 只认 fastlane 用到的几种调用; 状态放在 $FAKE_GH_DIR。
set -euo pipefail
echo "$*" >> "$FAKE_GH_DIR/calls.log"
head=$(git rev-parse HEAD)
case "$1 $2" in
    "pr list") echo "" ;;
    "pr create") echo "https://github.example/o/r/pull/77" ;;
    "pr view")
        case "$*" in
            *statusCheckRollup*) echo "$head 0 ${FAKE_BAD:-0} 3" ;;
            *mergeCommit*) echo "$head" ;;
            *state*) if [[ -f "$FAKE_GH_DIR/merged" ]]; then echo MERGED; else echo OPEN; fi ;;
        esac ;;
    "pr merge")
        remote=$(git remote get-url origin)
        git --git-dir="$remote" update-ref refs/heads/main "$head"
        touch "$FAKE_GH_DIR/merged" ;;
    *) echo "fake gh: unexpected call: $*" >&2; exit 9 ;;
esac
EOF
chmod +x "$FAKE_BIN/gh"

run_pr_mode() {
    local repo="$1"
    shift
    (cd "$repo" && PATH="$FAKE_BIN:$PATH" FASTLANE_SETTLE_SECONDS=0 FASTLANE_POLL_SECONDS=0 \
        bash scripts/deploy/publish-main-fastlane.sh "$@")
}

pr_repo=$(create_fixture prmode)
pr_base=$(git -C "$pr_repo" rev-parse origin/main)
pr_head=$(git -C "$pr_repo" rev-parse HEAD)
export FAKE_GH_DIR="$TMP_ROOT/gh-ok"
mkdir -p "$FAKE_GH_DIR"
run_pr_mode "$pr_repo" --base-sha "$pr_base" --confirm YES-DIRECT-MAIN > "$TMP_ROOT/pr.log"
[[ "$(git --git-dir="$TMP_ROOT/prmode-remote.git" rev-parse main)" == "$pr_head" ]] \
    || fail "PR mode did not land the head on main"
[[ "$(git --git-dir="$TMP_ROOT/prmode-remote.git" rev-parse codex/fixture)" == "$pr_head" ]] \
    || fail "PR mode did not push the branch"
grep -Fq "FASTLANE_PUBLISHED main=$pr_head pr=77" "$TMP_ROOT/pr.log" || fail "PR mode receipt missing"
grep -Eq "^pr merge 77 --squash --match-head-commit $pr_head$" "$FAKE_GH_DIR/calls.log" \
    || fail "PR mode did not merge with --match-head-commit"

# 有红检查: 不许合并, main 不动 (阴性对照)
red_repo=$(create_fixture prred)
red_base=$(git -C "$red_repo" rev-parse origin/main)
export FAKE_GH_DIR="$TMP_ROOT/gh-red"
mkdir -p "$FAKE_GH_DIR"
if FAKE_BAD=1 run_pr_mode "$red_repo" --base-sha "$red_base" --confirm YES-DIRECT-MAIN > "$TMP_ROOT/prred.log" 2>&1; then
    fail "PR mode merged despite a failing check"
fi
[[ "$(git --git-dir="$TMP_ROOT/prred-remote.git" rev-parse main)" == "$red_base" ]] \
    || fail "PR mode moved main despite a failing check"
if grep -q "^pr merge" "$FAKE_GH_DIR/calls.log"; then
    fail "PR mode called gh pr merge despite a failing check"
fi

echo "PASS: fastlane guards confirmation, cleanliness, ACTIVE, stale base, risk, CRLF, no-force push; PR mode merges only after green checks"
