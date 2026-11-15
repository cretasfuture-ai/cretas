#!/usr/bin/env bash
# GUARDS=tests/intent-acceptance/leak_gate.py,tests/intent-acceptance/gate.py,tests/intent-acceptance/score_recognize.py,tests/intent-acceptance/corpus_guard.py,tests/intent-acceptance/sealed_v3_fingerprints.jsonl
# 意图验收集 v3 的隔离闸与多指标门槛 (tests/intent-acceptance) —— ⛔ 不连生产、不读封存原文 (仓库里只有指纹)。守三件事:
#   A. 隔离闸自检: 干净语料 rc=0 / 注入了封存题 (换全角、空格、标点写法) rc=1 / 空目录与缺指纹 rc=2。
#   B. 仓库当前状态: 真实封存指纹 vs 默认训练/开发语料根, 必须 rc=0 (有人把封存题抄进训练线, 这里变红)。
#      并且指纹文件非空 (空指纹 = 没量到 = 红, 不当作「没有泄漏」)。
#   C. 计分与多指标门槛的单测 (含「总分涨、单项退步必须被拦」)。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DIR="$ROOT_DIR/tests/intent-acceptance"
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }

PY=""
for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
        PY=$candidate; break
    fi
done
[ -n "$PY" ] || { echo "SKIPPED: 没有 Python 3.8+"; exit 77; }

# A 自检 (阳性必红 / 阴性必绿 / 空目录 rc=2)
"$PY" -I -X utf8 "$DIR/leak_gate.py" --self-test > "$(mktemp)" 2>&1 || fail "A 隔离闸自检不通过"
ok "A 隔离闸自检: 干净 0 / 注入 1 / 空 2 / 缺指纹 2"

# B 当前仓库: 指纹非空且无泄漏
[ -s "$DIR/sealed_v3_fingerprints.jsonl" ] || fail "B 封存指纹文件为空或缺失 (没量到, 不是通过)"
LOG=$(mktemp)
"$PY" -I -X utf8 "$DIR/leak_gate.py" > "$LOG" 2>&1; rc=$?
[ "$rc" -eq 0 ] || { cat "$LOG" >&2; fail "B 隔离闸 rc=$rc (1=封存题进了训练/开发语料, 2=没量到)"; }
grep -q '"files_scanned": [1-9]' "$LOG" || fail "B 隔离闸没扫到任何文件 (0 个 = 恒真)"
ok "B 仓库当前状态无泄漏 (扫到语料, 指纹非空)"

# C 单测
"$PY" -m pytest "$DIR" -q -p no:cacheprovider > "$LOG" 2>&1; rc=$?
[ "$rc" -eq 0 ] || { tail -30 "$LOG" >&2; fail "C pytest rc=$rc"; }
grep -Eq '[1-9][0-9]* passed' "$LOG" || fail "C 没有任何用例通过 (0 执行不算通过)"
ok "C 计分 / 门槛单测: $(grep -E 'passed' "$LOG" | tail -1)"

echo "PASS: test-intent-acceptance-leak-gate"
