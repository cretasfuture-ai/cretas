#!/usr/bin/env bash
# 把「评测批次 / 无界面只读回归」丢到 Aurora Mac mini 上跑，结果拉回本机。
#
#   scripts/dev/remote-eval.sh eval                         # 工厂 AI 读路径电池 (factory_ai_eval, 只读)
#   scripts/dev/remote-eval.sh e2e [scenario,scenario,...]  # production-readonly 无界面回归 (只读, 零写入)
#
# 选项（放在子命令前）:
#   --host ALIAS   ssh 别名，默认 aurora-mini-pomelox
#   --ref REF      Aurora 上检出的 origin 分支/标签，默认 main
#   --out DIR      本机结果目录，默认 tmp/remote-eval/<时间戳>-<子命令>
#   --min-free N   Aurora 空闲内存低于 N% 就不跑（退 2），默认 25
#
# 退出码三态: 0 没问题 / 1 评测有失败 / 2 没量到（连不上、内存不够、凭证缺、登录失败、没产出报告）。
#
# ⛔ 本脚本只经 ssh 下发非交互命令: 不开 GUI、不 open/osascript、不启动 Claude、不装常驻服务。
# ⛔ 凭证只在 Aurora 的 ~/cretas-eval/secrets/eval.env (600) 里，不经命令行、不进日志、不进 git。
#    文件格式见仓库根 .env.test.example（FACTORY_EVAL_USERNAME/PASSWORD 与 E2E_* 变量）。
# 只读: eval 用 mode=READ 的 factory_ai_eval（不跑 factory_write_eval）; e2e 用 production-readonly
#       harness（变更守卫在首次导航前安装，业务写入必须为 0）。
# headless: Aurora 上浏览器一律 headless，那是后台回归；本机可视验收仍按 playwright-headed-mode 规则 headed。
set -u

HOST="aurora-mini-pomelox"
REF="main"
OUT=""
MIN_FREE=25
while [ $# -gt 0 ]; do
    case "$1" in
        --host) HOST="$2"; shift 2 ;;
        --ref) REF="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --min-free) MIN_FREE="$2"; shift 2 ;;
        --) shift; break ;;
        -*) echo "未知选项: $1" >&2; exit 2 ;;
        *) break ;;
    esac
done
KIND="${1:-}"
[ -n "$KIND" ] || { echo "用法: remote-eval.sh [选项] eval|e2e [scenarios]" >&2; exit 2; }
shift
SCENARIOS="${1:-tenant-isolation,workflow-readonly,finance-readonly}"
case "$KIND" in eval|e2e) ;; *) echo "子命令只能是 eval 或 e2e" >&2; exit 2 ;; esac
# 值只允许字母数字和 ._,/-，防止经 ssh 命令行注入
for v in "$REF" "$SCENARIOS" "$HOST"; do
    case "$v" in *[!A-Za-z0-9._,/-]*) echo "参数含非法字符: $v" >&2; exit 2 ;; esac
done

RUN_ID="$(date +%Y%m%d-%H%M%S)-$KIND"
[ -n "$OUT" ] || OUT="tmp/remote-eval/$RUN_ID"
mkdir -p "$OUT" || exit 2
SSH=(ssh -o BatchMode=yes -o ConnectTimeout=20 "$HOST")

# 远端脚本经 stdin 传入（二进制安全，不经 shell 转义）。参数用位置参数传。
REMOTE_SCRIPT=$(cat <<'REMOTE'
set -u
KIND="$1"; REF="$2"; RUN_ID="$3"; MIN_FREE="$4"; SCENARIOS="$5"
BASE="$HOME/cretas-eval"
RUN="$BASE/out/$RUN_ID"
mkdir -p "$RUN" || exit 2
exec 3>&1; exec 1>"$RUN/remote.log" 2>&1   # 远端诊断全落文件, 回传

mp_out=$(memory_pressure 2>/dev/null); mp_rc=$?
free=""
if [ "$mp_rc" -eq 0 ]; then
    free=$(printf '%s\n' "$mp_out" | awk '/free percentage/ {gsub("%","",$NF); print $NF}')
fi
echo "aurora_free_memory_percent=${free:-unknown}"
if [ -z "${free:-}" ] || [ "$free" -lt "$MIN_FREE" ]; then
    echo "NOT-MEASURED: 空闲内存 ${free:-unknown}% < ${MIN_FREE}%"; echo 2 > "$RUN/rc"; exit 0
fi
[ -r "$BASE/secrets/eval.env" ] || { echo "NOT-MEASURED: 缺 $BASE/secrets/eval.env"; echo 2 > "$RUN/rc"; exit 0; }

cd "$BASE/repo" || { echo 2 > "$RUN/rc"; exit 0; }
git fetch -q --depth 1 origin "$REF" && git checkout -q FETCH_HEAD || { echo "NOT-MEASURED: 拉取 $REF 失败"; echo 2 > "$RUN/rc"; exit 0; }
echo "repo_head=$(git rev-parse --short HEAD) ref=$REF"

set -a; . "$BASE/secrets/eval.env"; set +a
export PATH="$HOME/Tools/node/bin:$PATH"
export PLAYWRIGHT_BROWSERS_PATH="$HOME/Tools/pw-browsers"
start=$(date +%s)

if [ "$KIND" = eval ]; then
    cd "$BASE/repo/backend/python" || { echo 2 > "$RUN/rc"; exit 0; }
    PYTHONIOENCODING=utf-8 nice -n 10 "$BASE/venv/bin/python" -X utf8 -m smartbi.scripts.factory_ai_eval > "$RUN/result.log" 2>&1
    rc=$?
    case "$rc" in 0|1|2) ;; *) rc=2 ;; esac
else
    cd "$BASE/repo" || { echo 2 > "$RUN/rc"; exit 0; }
    # harness 要求证据目录落在仓库内被 gitignore 的 tmp/ 之下; 跑完再搬进 $RUN 回传
    EVD="$BASE/repo/tmp/remote-eval/$RUN_ID/evidence"
    mkdir -p "$EVD"
    nice -n 10 node scripts/e2e/production-readonly/cli-runner.mjs --production-readonly \
        --scenario "$SCENARIOS" --evidence-dir "$EVD" > "$RUN/result.log" 2> "$RUN/stderr.log"
    rc=$?
    mkdir -p "$RUN/evidence" && cp -R "$EVD/." "$RUN/evidence/" && rm -rf "$BASE/repo/tmp/remote-eval/$RUN_ID"
    # CLI 把「没跑起来」和「场景失败」都退 1: 有 runId 的 JSON 报告才算量到。
    if ! grep -q '"runId"' "$RUN/result.log"; then rc=2; fi
    if [ "$rc" != 2 ] && ! grep -q '"actualBusinessWrites": 0' "$RUN/result.log"; then
        echo "ALERT: actualBusinessWrites 不为 0 或缺失"; rc=1
    fi
fi
echo "wall_seconds=$(( $(date +%s) - start ))"
echo "$rc" > "$RUN/rc"
REMOTE
)

# 经 base64 当 bash -c 的参数传，避免脚本占用 stdin 被 git/node 吃掉
B64="$(printf '%s\n' "$REMOTE_SCRIPT" | base64 | tr -d '\n')"
"${SSH[@]}" "bash -c \"\$(echo $B64 | base64 -d)\" remote $KIND $REF $RUN_ID $MIN_FREE $SCENARIOS" </dev/null
ssh_rc=$?
if [ "$ssh_rc" -ne 0 ]; then
    echo "NOT-MEASURED: ssh 下发失败 rc=$ssh_rc" >&2
    exit 2
fi

# 拉回结果（rsync 优先，没有就 scp -r）
if command -v rsync >/dev/null 2>&1; then
    rsync -a -e "ssh -o BatchMode=yes" "$HOST:cretas-eval/out/$RUN_ID/" "$OUT/" || { echo "NOT-MEASURED: 拉回结果失败" >&2; exit 2; }
else
    scp -q -r -o BatchMode=yes "$HOST:cretas-eval/out/$RUN_ID/." "$OUT/" || { echo "NOT-MEASURED: 拉回结果失败" >&2; exit 2; }
fi

RC="$(tr -d '[:space:]' < "$OUT/rc" 2>/dev/null || true)"
case "$RC" in 0|1|2) ;; *) echo "NOT-MEASURED: 远端没有写出 rc" >&2; exit 2 ;; esac
echo "--- 远端诊断 ---"; cat "$OUT/remote.log"
echo "--- 结果 (rc=$RC) → $OUT ---"
[ -f "$OUT/result.log" ] && tail -n 40 "$OUT/result.log"
exit "$RC"
