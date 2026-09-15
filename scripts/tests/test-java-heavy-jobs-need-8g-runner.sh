#!/usr/bin/env bash
# GUARDS=.github/workflows/java-gate-pr.yml,.github/workflows/java-full-nightly.yml,.github/workflows/jpa-gate-main.yml,.github/workflows/java-artifact.yml,.github/workflows/tool-inventory-gate.yml,scripts/ci/switch_ci_runner.py,backend/java/cretas-api/pom.xml
# 跑 Maven 测试 fork 的自托管 job 必须落在 8G 的 runner 上, 且 workflow 声明的堆要真的到达 fork。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (2026-09-15, Java Gate / fast-contracts 自 9-12 起每个 PR 都红)
# ═══════════════════════════════════════════════════════════════════════════
# 同一台 WSL 上有三个 runner: `windows-wsl-ci`(cgroup MemoryMax=8G) 与两个
# `windows-wsl-ci-{2,3}`(MemoryMax=4G, 本意是给 jest/web 用的轻 runner)。三者都带
# `[self-hosted, cretas]`, GitHub 随机派发。JVM 按 cgroup 算默认堆(¼): 8G → 2G 能跑完
# 423 个类; 4G → **1G**, 第 23 个类就 GC 抖动、第 61 个类 `Java heap space`。
#
# 而 workflow 里写了几个月的 `-DargLine="… -Xmx3g"` **从来没生效过**: pom 的 surefire
# 配置里是显式 `<argLine>` 字面量, Maven 显式配置优先于 `-D` 用户属性。实测: 传
# `-DargLine=-Xmx1m` 测试照样绿; 同一串写进 pom 那一行, JVM 当场起不来。
#
# 两道闸:
#   A. 结构: 每个在自托管上调 Maven 的 job, runs-on 必须带 `java` 标签(只有 8G runner 有)。
#   B. 行为: switch_ci_runner.py 在临时仓上 self-hosted → hosted → self-hosted 往返,
#      三标签形态必须**逐字**还原, 且 --check 不得报 unknown/mixed —— 否则月初切托管
#      会把 Java job 永久钉死在自托管上(或把它们的标签丢掉)。
#   C. 接线: pom 的 surefire `<argLine>` 必须引用 `${argLine}` 属性并给默认值,
#      这样 `-DargLine=` 才能覆盖。(行为对照在 PR 描述里: -Xmx1m 探针。)
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
WF_DIR="$ROOT_DIR/.github/workflows"
SWITCH="$ROOT_DIR/scripts/ci/switch_ci_runner.py"
POM="$ROOT_DIR/backend/java/cretas-api/pom.xml"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

PY=$(command -v python3 || command -v python) || fail "没有 python"

JAVA_SELF='    runs-on: [self-hosted, cretas, java]'

# ---------------------------------------------------------------------------
# A. 结构: 自托管 + 调 Maven 的 job ⇒ 必须带 java 标签
# ---------------------------------------------------------------------------
# 输出每行: <file> <job> <runs-on 原文> <mvn 行数>
scan_jobs() {
    "$PY" - "$1" <<'PY'
import re, sys, pathlib
sys.stdout.reconfigure(newline=chr(10))  # Windows 下别把 CR 混进 bash 的 read
for p in sorted(pathlib.Path(sys.argv[1]).glob("*.yml")):
    job = None; ro = None; mvn = 0; rows = []
    for line in p.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if m:
            if job: rows.append((job, ro, mvn))
            job, ro, mvn = m.group(1), None, 0
            continue
        s = line.strip()
        if s.startswith("#"):
            continue
        if job and re.match(r"^\s{4}runs-on:", line):
            ro = line.rstrip()
        if job and re.search(r'"\$MVN"|(^|\s)mvn\s|\./mvnw', line):
            mvn += 1
    if job: rows.append((job, ro, mvn))
    for job, ro, mvn in rows:
        if ro is None: continue
        print("%s\t%s\t%s\t%d" % (p.name, job, ro, mvn))
PY
}

violations=0; heavy=0
while IFS=$'\t' read -r f job ro mvn; do
    case "$ro" in *self-hosted*) ;; *) continue ;; esac
    [ "$mvn" -gt 0 ] || continue
    heavy=$((heavy + 1))
    if [ "$ro" != "$JAVA_SELF" ]; then
        echo "  ✗ $f/$job 调了 Maven($mvn 处) 但 runs-on 是: ${ro#    }" >&2
        violations=$((violations + 1))
    fi
done < <(scan_jobs "$WF_DIR")
[ "$heavy" -ge 5 ] || fail "阳性对照: 自托管 Maven job 只扫到 $heavy 个(预期 ≥5: fast-contracts / postgres-reversal-concurrency / full-suite / jpa-gate / package / tool-inventory-gate) —— 扫描器瞎了"
[ "$violations" -eq 0 ] || fail "$violations 个自托管 Maven job 没带 java 标签 —— 会被派到 4G 的 extra runner 上 OOM"
ok "A. $heavy 个自托管 Maven job 全部 runs-on 带 java 标签"

# A 的阴性对照: 把一份副本的 java 标签抹掉, 扫描器必须抓到
mkdir -p "$TMP/neg"
cp "$WF_DIR/java-gate-pr.yml" "$TMP/neg/"
sed -i 's/\[self-hosted, cretas, java\]/[self-hosted, cretas]/' "$TMP/neg/java-gate-pr.yml"
neg_rows=$(scan_jobs "$TMP/neg") || fail "阴性对照: 扫描器在副本上跑挂了"
neg=$(awk -F'\t' '$3 ~ /self-hosted/ && $4 > 0 && $3 !~ /java\]/' <<<"$neg_rows" | wc -l)
[ "$neg" -ge 1 ] || fail "阴性对照: 抹掉 java 标签后扫描器没报 —— 闸在守空气"
ok "A'. 阴性对照: 抹掉标签能被抓到 ($neg 个 job)"

# ---------------------------------------------------------------------------
# B. 行为: switch_ci_runner.py 往返, 三标签形态逐字还原
# ---------------------------------------------------------------------------
mkdir -p "$TMP/repo/scripts/ci" "$TMP/repo/.github/workflows"
cp "$SWITCH" "$TMP/repo/scripts/ci/"
cp "$WF_DIR"/*.yml "$TMP/repo/.github/workflows/"
before=$(cd "$TMP/repo" && cat .github/workflows/*.yml | md5sum)

run_switch() { (cd "$TMP/repo" && "$PY" scripts/ci/switch_ci_runner.py "$@") ; }

run_switch --check > "$TMP/check0.txt" 2>&1 || fail "--check 在当前树上失败:\n$(cat "$TMP/check0.txt")"
grep -q "当前 = self-hosted" "$TMP/check0.txt" || fail "--check 没把当前树认成 self-hosted:\n$(cat "$TMP/check0.txt")"
ok "B0. --check 认得三标签形态 (self-hosted, 非 unknown/mixed)"

run_switch hosted > "$TMP/to-hosted.txt" 2>&1 || fail "切 hosted 失败:\n$(cat "$TMP/to-hosted.txt")"
# ⚠️ java-gate-pr.yml 不在 switch 脚本的 WORKFLOWS 清单里(既有缺口, 与本闸无关), 这里只看
#    清单里的 Java 文件: 三标签形态必须被切走, 且托管侧带 java-heavy 标记以便切回。
for f in java-full-nightly.yml jpa-gate-main.yml java-artifact.yml tool-inventory-gate.yml; do
    if grep -q "runs-on: \[self-hosted" "$TMP/repo/.github/workflows/$f"; then
        fail "切 hosted 之后 $f 里还有 self-hosted —— Java job 被钉死在自托管上"
    fi
    grep -q "ubuntu-latest  # java-heavy" "$TMP/repo/.github/workflows/$f" \
        || fail "切 hosted 之后 $f 没带 java-heavy 标记 —— 切回时会丢 java 标签"
done
ok "B1. 切 hosted 后清单里的 Java job 一起切走, 且带回程标记"

run_switch self-hosted > "$TMP/to-self.txt" 2>&1 || fail "切回 self-hosted 失败:\n$(cat "$TMP/to-self.txt")"
after=$(cd "$TMP/repo" && cat .github/workflows/*.yml | md5sum)
[ "$before" = "$after" ] || fail "往返之后工作流不逐字相等 —— java 标签在切换中丢了:
$(cd "$TMP/repo" && for f in .github/workflows/*.yml; do diff -q "$f" "$WF_DIR/$(basename "$f")" >/dev/null || echo "  差异: $f"; done)"
ok "B2. self-hosted → hosted → self-hosted 逐字还原"

# ---------------------------------------------------------------------------
# C. 接线: pom 的 surefire argLine 引用属性, 属性有默认值
# ---------------------------------------------------------------------------
grep -Eq '<argLine>\$\{argLine\}</argLine>' "$POM" \
    || fail "pom surefire <argLine> 不是 \${argLine} —— workflow 的 -DargLine 到不了 fork (Maven 显式配置优先于 -D)"
grep -Eq '^\s*<argLine>-XX:\+EnableDynamicAgentLoading -Xshare:off</argLine>' "$POM" \
    || fail "pom <properties> 里没有 argLine 默认值 —— 本地 mvn test 会把字面量 \${argLine} 传给 JVM"
n=$(grep -c '<argLine>' "$POM")
[ "$n" -eq 2 ] || fail "pom 里 <argLine> 应恰好 2 处(属性默认 + surefire 引用), 实际 $n"
ok "C. pom surefire argLine = \${argLine}, 属性默认值在位"

echo "PASS: $PASSED 条"
