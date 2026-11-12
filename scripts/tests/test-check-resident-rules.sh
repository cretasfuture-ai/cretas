#!/usr/bin/env bash
# 常驻规则闸 check_resident_rules.py 的阳性/阴性对照: 在临时仓库里构造违规, 证明它真会红。
set -u
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
CHK="$ROOT_DIR/scripts/ci/check_resident_rules.py"
PY=python3; command -v python3 >/dev/null 2>&1 || PY=python
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fails=0
expect() { # name want_rc got_rc
  if [ "$2" = "$3" ]; then echo "PASS: $1 (rc=$3)"; else echo "FAIL: $1 want rc=$2 got rc=$3"; fails=$((fails+1)); fi
}
mk() { # dir
  mkdir -p "$1/.claude/rules" "$1/scripts/ci"
  printf '# CLAUDE\n- `a.md` - 常驻\n- `b.md` - 按路径\n' > "$1/CLAUDE.md"
  printf '# a\nhello\n' > "$1/.claude/rules/a.md"
  printf -- '---\npaths:\n  - "x/**"\n---\n# b\n' > "$1/.claude/rules/b.md"
  printf '#!/bin/sh\n' > "$1/scripts/run.sh"
  ( cd "$1" && git init -q && git config user.email t@t && git config user.name t && git config core.fileMode true \
    && git add -A && git update-index --chmod=+x scripts/run.sh && git commit -q -m base )
}
# 1 阴性对照: 干净仓库通过
mk "$T/ok"; "$PY" "$CHK" --root "$T/ok" >/dev/null 2>&1; expect "clean passes" 0 $?
# 2 阳性: 超预算
"$PY" "$CHK" --root "$T/ok" --budget 10 >/dev/null 2>&1; expect "over budget fails" 1 $?
# 3 阳性: 带 paths 的大文件不计入预算(预算只算常驻)
head -c 50000 /dev/zero | tr '\0' 'x' >> "$T/ok/.claude/rules/b.md"; "$PY" "$CHK" --root "$T/ok" >/dev/null 2>&1; expect "path-scoped rule not counted" 0 $?
# 4 阳性: 未登记规则
printf '# c\n' > "$T/ok/.claude/rules/c.md"; "$PY" "$CHK" --root "$T/ok" >/dev/null 2>&1; expect "unregistered rule fails" 1 $?
rm "$T/ok/.claude/rules/c.md"
# 5 阳性: 丢可执行位
( cd "$T/ok" && git update-index --chmod=-x scripts/run.sh && git commit -q -m drop )
"$PY" "$CHK" --root "$T/ok" --skip-rules --exec-bit-base HEAD~1 >/dev/null 2>&1; expect "exec bit drop fails" 1 $?
# 6 阴性: 允许清单放行
printf 'scripts/run.sh\n' > "$T/ok/scripts/ci/exec-bit-allowlist.txt"
"$PY" "$CHK" --root "$T/ok" --skip-rules --exec-bit-base HEAD~1 >/dev/null 2>&1; expect "allowlisted drop passes" 0 $?
# 7 没量到: 坏 ref => rc=2 (不是 0)
"$PY" "$CHK" --root "$T/ok" --skip-rules --exec-bit-base no-such-ref >/dev/null 2>&1; expect "bad ref is rc=2" 2 $?
# 8 没量到: 空仓库
mkdir -p "$T/empty"; "$PY" "$CHK" --root "$T/empty" >/dev/null 2>&1; expect "no samples is rc=2" 2 $?
# 9 真实仓库当前状态通过
"$PY" "$CHK" --root "$ROOT_DIR" >/dev/null 2>&1; expect "real repo passes" 0 $?
[ "$fails" = 0 ] && echo "PASS: check_resident_rules" || { echo "FAIL: $fails"; exit 1; }
