#!/usr/bin/env bash
# GUARDS=.github/workflows/rn-gate.yml,.github/workflows/web-admin-gate.yml,.github/workflows/python-gate.yml,.github/workflows/crosslang-contract-gate.yml,.github/workflows/source-hygiene.yml,.github/workflows/web-dist.yml,.github/workflows/java-gate-pr.yml,.github/workflows/java-full-nightly.yml,.github/workflows/jpa-gate-main.yml,.github/workflows/java-artifact.yml,.github/workflows/tool-inventory-gate.yml,.github/workflows/ops-script-tests.yml,.github/workflows/soft-delete-scan-surface.yml,.github/workflows/secret-regression.yml,.github/workflows/scheduler-canary.yml,.github/workflows/artifact-reaper.yml,.github/workflows/reap-orphan-testenvs.yml
# 每个自托管 job 必须带 timeout-minutes; rn-gate 的 jest 必须自己钉 JEST_MAX_WORKERS。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (2026-09-14/15, rn-gate jest 跑了 20 小时把主 runner 拖 offline)
# ═══════════════════════════════════════════════════════════════════════════
# jest.config.js 在 CI 下 maxWorkers='50%', WSL 报 16 核 → 8 个 worker × RN jest 各 ~1G →
# 撞主 runner cgroup MemoryHigh=7G → 被节流爬行。job 没有 timeout-minutes (GitHub 默认 6 小时),
# 而节流后 runner 连取消指令都收不到 (TLS 超时) → 20 小时、显示 offline、之后所有 Java job
# 落到 4G 的 extra 上 OOM (#814 那条线的起点)。
#
# 自托管 runner 与托管的根本区别: 它是**我们自己的机器**, 一个失控的 job 不是烧额度, 是把
# 别的 job 一起拖死。所以:
#   A. 每个 runs-on 含 self-hosted 的 job 必须有 timeout-minutes (兜底)。
#   B. rn-gate 的 jest job 必须在 workflow 里自己给 JEST_MAX_WORKERS (不让它撑爆的那一层),
#      ⛔ 不能依赖某台 runner 单元里恰好有这个 env —— extra 有、主 runner 没有, 这次就是这么漏的。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
WF_DIR="$ROOT_DIR/.github/workflows"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }
PY=$(command -v python3 || command -v python) || fail "没有 python"

# 每行: <file>\t<job>\t<self-hosted 0/1>\t<timeout-minutes 或 ->
scan() {
    "$PY" - "$1" <<'PY'
import re, sys, pathlib
sys.stdout.reconfigure(newline=chr(10))
for p in sorted(pathlib.Path(sys.argv[1]).glob("*.yml")):
    job=None; self=0; to="-"; rows=[]
    for line in p.read_text(encoding="utf-8").splitlines():
        m=re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if m:
            if job: rows.append((job,self,to))
            job,self,to=m.group(1),0,"-"; continue
        if line.strip().startswith("#"): continue
        if job and re.match(r"^\s{4}runs-on:.*self-hosted", line): self=1
        m2=re.match(r"^\s{4}timeout-minutes:\s*(\d+)", line)
        if job and m2: to=m2.group(1)
    if job: rows.append((job,self,to))
    for job,self,to in rows:
        # 只报真正的 job (有 runs-on 或 timeout 之一), 跳过 on: 下面的 push/pull_request 之类
        print("%s\t%s\t%d\t%s" % (p.name, job, self, to))
PY
}

# A. 自托管 job 全部带超时
missing=0; total=0
while IFS=$'\t' read -r f job self to; do
    [ "$self" = "1" ] || continue
    total=$((total + 1))
    if [ "$to" = "-" ]; then echo "  ✗ $f/$job 没有 timeout-minutes" >&2; missing=$((missing + 1)); fi
done < <(scan "$WF_DIR")
[ "$total" -ge 10 ] || fail "阳性对照: 自托管 job 只扫到 $total 个 (预期 ≥10) —— 扫描器瞎了"
[ "$missing" -eq 0 ] || fail "$missing 个自托管 job 没有 timeout-minutes —— 失控时会跑到 GitHub 默认 6 小时, 把同机的别的 job 一起拖死"
ok "A. $total 个自托管 job 全部带 timeout-minutes"

# A 的阴性对照: 抹掉一个, 扫描器必须抓到
mkdir -p "$TMP/neg"; cp "$WF_DIR/rn-gate.yml" "$TMP/neg/"
sed -i '/^    timeout-minutes: /d' "$TMP/neg/rn-gate.yml"
neg_rows=$(scan "$TMP/neg") || fail "阴性对照: 扫描器在副本上跑挂了"
neg=$(awk -F'\t' '$3==1 && $4=="-"' <<<"$neg_rows" | wc -l)
[ "$neg" -ge 1 ] || fail "阴性对照: 抹掉 timeout 后扫描器没报 —— 闸在守空气"
ok "A'. 阴性对照: 抹掉 timeout 能被抓到"

# B. rn-gate jest 自己钉 worker 数 (job 级 env, 不靠 runner 单元)
awk '/^  jest:$/{p=1;next} p&&/^  [A-Za-z0-9_-]+:$/{p=0} p' "$WF_DIR/rn-gate.yml" > "$TMP/jest-job.txt"
grep -Eq '^\s{6}JEST_MAX_WORKERS:\s*[1-4]\s*$' "$TMP/jest-job.txt" \
    || fail "rn-gate.yml 的 jest job 没有 job 级 env JEST_MAX_WORKERS (1–4) —— jest.config.js 在 CI 下 maxWorkers=50%, WSL 上就是 8 个 worker × ~1G, 撞 cgroup 7G 被节流"
ok "B. rn-gate jest 自带 JEST_MAX_WORKERS"

echo "PASS: $PASSED 条"
