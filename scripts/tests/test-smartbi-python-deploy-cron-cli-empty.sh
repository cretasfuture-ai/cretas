#!/usr/bin/env bash
set -uo pipefail

# deploy-smartbi-python.sh 第 3c2 段「cron 入口引用的 CLI」抽取的回归测试。
#
# 被修的 bug: 抽取用 `grep ... | sed | sort` 跑在 set -eo pipefail 下, grep 一个都没
# 命中时退出码 1 ⇒ 整个部署**不打日志**地 exit 1, 「没有引用, 跳过」分支永远到不了。
# 回归部署脚本在无 CLI 引用时仍能继续完成发布。
#
# 测试方式: 从生产脚本里原样抽出 `# >>> CRON_CLI_EXTRACT` .. `# <<< CRON_CLI_EXTRACT`,
# 在 set -eo pipefail 下对沙箱 PROJECT_ROOT 跑; 最后把修复去掉跑一次, 证明本测试能红。
#
# 用法: bash scripts/tests/test-smartbi-python-deploy-cron-cli-empty.sh

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SOURCE_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-smartbi-python.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT
FAILURES=0
fail() { echo "FAIL: $*" >&2; FAILURES=$((FAILURES + 1)); }

BLOCK=$(awk '$0 ~ /^# >>> CRON_CLI_EXTRACT$/ {inb=1} inb {print} $0 ~ /^# <<< CRON_CLI_EXTRACT$/ {exit}' "$SOURCE_SCRIPT")
[ -n "$BLOCK" ] || { echo "FATAL: 生产脚本里找不到 CRON_CLI_EXTRACT 块" >&2; exit 1; }

# run_block <block> <project_root>  -> 打印 rc 与输出
run_block() {
    local block=$1 root=$2
    bash -c '
        set -eo pipefail
        log() { echo "LOG[$1] $2"; }
        PROJECT_ROOT=$1
        eval "$2"
        echo "REACHED"
        printf "CLIS=%s\n" "$(printf "%s" "$_cron_referenced_clis" | tr "\n" " ")"
    ' _ "$root" "$block" 2>&1
    echo "RC=$?"
}

mk_root() {  # name, then file contents for the registry entry file
    local r="$TMP_ROOT/$1"
    mkdir -p "$r/scripts/cron"
    printf '%s\n' "$2" > "$r/scripts/cron/probe-llm-registry.sh"
    printf '%s' "$r"
}

# 1. 三个入口都没有 scripts/*.py 引用 (= #1322 之后的真实状态) -> 必须走到后面, 列表为空
R1=$(mk_root none 'true')
OUT1=$(run_block "$BLOCK" "$R1")
echo "$OUT1" | grep -Fq 'REACHED' || fail "无引用时没走到后面: $OUT1"
echo "$OUT1" | grep -Fxq 'RC=0' || fail "无引用时 rc 不是 0: $OUT1"
echo "$OUT1" | grep -Fxq 'CLIS=' || fail "无引用时列表应为空: $OUT1"

# 2. 有引用 (含 $CODEDIR/ 前缀与空白前缀两种) -> 正常列出, 去重
R2=$(mk_root some 'python $CODEDIR/scripts/a-b.py --x; python scripts/c.py')
OUT2=$(run_block "$BLOCK" "$R2")
echo "$OUT2" | grep -Fxq 'RC=0' || fail "有引用时 rc 不是 0: $OUT2"
echo "$OUT2" | grep -Fxq 'CLIS=scripts/a-b.py scripts/c.py' || fail "有引用时列表不对: $OUT2"

# 3. 入口文件缺失 (grep rc=2) -> 必须停下, 且有 ERROR 日志 (不再无声)
R3=$(mk_root missing 'python scripts/a.py'); rm "$R3/scripts/cron/probe-llm-registry.sh"
OUT3=$(run_block "$BLOCK" "$R3")
echo "$OUT3" | grep -Fq 'REACHED' && fail "入口文件缺失时不应继续: $OUT3"
echo "$OUT3" | grep -Fxq 'RC=0' && fail "入口文件缺失时 rc 不应为 0: $OUT3"
echo "$OUT3" | grep -Fq 'LOG[ERROR]' || fail "入口文件缺失时应有 ERROR 日志: $OUT3"
echo "$OUT3" | grep -Fq '(rc=2)' || fail "ERROR 日志应带 grep 的真实退出码 2: $OUT3"

# 4. 变异: 去掉「rc=1 视为没命中」, 用例 1 必须红 (证明本测试守的就是这个行为)
MUT=${BLOCK/ || \{ _r=\$?; \[ \"\$_r\" -eq 1 \] || exit \"\$_r\"; \}; \}/; \}}
[ "$MUT" != "$BLOCK" ] || fail "变异没生效 (sed 没改到任何东西)"
OUTM=$(run_block "$MUT" "$R1")
echo "$OUTM" | grep -Fq 'REACHED' && fail "变异后无引用用例仍然通过, 测试守不住: $OUTM"

if [ "$FAILURES" -ne 0 ]; then echo "test-smartbi-python-deploy-cron-cli-empty: $FAILURES failure(s)"; exit 1; fi
echo "test-smartbi-python-deploy-cron-cli-empty: OK (4 cases, mutation red)"
