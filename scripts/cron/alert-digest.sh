#!/usr/bin/env bash
# alert-digest.sh —— 把 47 上各定时脚本写的 *alert* 文件的**新增行**收成一段纯文本, 交给一个可插拔的投递函数。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (OPS-CRON-ALERT-FILES-HAVE-NO-CONSUMER-20260906)
# ═══════════════════════════════════════════════════════════════════════════
# 47 上 13 个 cron 脚本写 11 个 *alert* 文件, **零消费方** (无 postfix / mail spool / 面板)。孤儿日扫
# 8-21 首跑就报 3262 行, 16 天没人读; canary 5 周崩溃被记成账单告警也没人看见。owner 2026-09-06 裁定
# 暂不做投递渠道, 推荐企微/钉钉机器人日 digest, 等 webhook。这是那个 digest 的**骨架**:
#   · 读**新增行** (逐文件记游标, 游标只在投递成功后前进 —— 投递失败下次重发, ⛔ 不丢)
#   · 按脚本 (= 文件) 分组, 产出一段纯文本
#   · 投递函数可插拔: ALERT_SINK=stdout (默认) | webhook (要 ALERT_WEBHOOK_URL)
#
# ⛔ NOT_SCHEDULED: 未接 crontab, 等渠道 (inventory 里登记)。
#
# 退出码 (三态, 判定只测 -eq 0):
#   0  DIGEST empty —— 没有新增行 (硬约束 9: 这一行**必须**打出来, 「没喊」≠「没跑」)
#   1  有新增行, 已投递
#   2  没量到 (状态目录写不了 / 一个 alert 文件都找不到 / sink 失败 …); 游标不前进
#
# ⚠️ 本脚本自己的台账/告警写在 STATE_DIR 里 (⛔ 不写进任何 *alert* 文件 —— 那些是它的输入, 写进去就
#    成了自己读自己)。
set -uo pipefail

LOGS_DIR="${ALERT_DIGEST_LOGS_DIR:-/www/wwwroot/cretas/logs}"
STATE_DIR="${ALERT_DIGEST_STATE_DIR:-/www/wwwroot/cretas/logs/.alert-digest}"
FILES="${ALERT_DIGEST_FILES:-}"          # 空 = 自动发现 $LOGS_DIR/*alert* (排除 *.cron.log)
SINK="${ALERT_SINK:-stdout}"
WEBHOOK_URL="${ALERT_WEBHOOK_URL:-}"
MAX_LINES="${ALERT_DIGEST_MAX_LINES:-50}"   # 每个文件最多贴几行 (其余只报计数)
LEDGER="$STATE_DIR/digest.ledger"
PFX="ALERT DIGEST"

# shellcheck source=scripts/cron/cron-common.sh
. "$(dirname "${BASH_SOURCE[0]}")/cron-common.sh" || {
    echo "$PFX INSTRUMENT DEAD reason=cron-common-missing" >&2; exit 2; }
cron_pin_path
# 状态目录先于任何写台账的动作: 台账就住在它里面。建不了/不可写 ⇒ cron_dead 往里追加会当场报错到
# stderr 并退 2 (test-cron-common [7] 钉的形状), ⛔ 不吞。
mkdir -p "$STATE_DIR" 2>/dev/null && [ -w "$STATE_DIR" ] \
    || cron_dead "$LEDGER" "$PFX" "state-dir-unwritable" "$STATE_DIR 建不了或不可写 —— 没有游标就不能保证不重发"
cron_require_cmds "$LEDGER" "$PFX" stat tail wc date

STAMP="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
WORK="$(mktemp -d)"
VERDICT=0
on_exit() {
    rm -rf "$WORK"
    [ "$VERDICT" = 1 ] || echo "$STAMP INSTRUMENT_DEAD reason=aborted-before-verdict rc=2" >> "$LEDGER"
}
trap on_exit EXIT
verdict() { echo "$STAMP $*" >> "$LEDGER"; VERDICT=1; }
# rc=2 出口: cron_dead 自己会往 $LEDGER 写一行 `… INSTRUMENT DEAD … reason=<r>` 再 exit 2, 这里只标记
# 「判决已写」免得 on_exit 再补一行 aborted-before-verdict。
dead() { VERDICT=1; cron_dead "$LEDGER" "$PFX" "$@"; }

# ── 1. 输入文件 ───────────────────────────────────────────────────────────
declare -a INPUTS=()
if [ -n "$FILES" ]; then
    for f in $FILES; do INPUTS+=("$f"); done
else
    shopt -s nullglob
    for f in "$LOGS_DIR"/*alert*; do
        case "$f" in *.cron.log) continue ;; esac
        [ -f "$f" ] && INPUTS+=("$f")
    done
    shopt -u nullglob
fi
# 阳性对照: 一个文件都没有 = 目录指错了 / 权限, ⛔ 不是「今天没告警」。
[ "${#INPUTS[@]}" -gt 0 ] || dead "no-alert-files" "在 $LOGS_DIR 下没找到任何 *alert* 文件 (ALERT_DIGEST_FILES 也没给)"

# ── 2. 逐文件读游标之后的新增行 ───────────────────────────────────────────
# 游标文件: $STATE_DIR/<basename>.cursor = "<inode>:<offset>"。inode 变了或文件变短 = 轮转/截断,
# 从 0 读并注明 rotated (⛔ 不静默: 轮转当天的行不能丢, 但也要让人知道是从头读的)。
DIGEST="$WORK/digest.txt"
TOTAL=0; NFILES=0
declare -A NEW_OFFSET=()
for f in "${INPUTS[@]}"; do
    [ -r "$f" ] || { echo "== $(basename "$f")  (读不了, 跳过)" >> "$DIGEST"; continue; }
    base=$(basename "$f"); cur="$STATE_DIR/$base.cursor"
    inode=$(stat -c %i "$f"); size=$(stat -c %s "$f")
    offset=0; note=""
    if [ -f "$cur" ]; then
        IFS=: read -r c_inode c_offset < "$cur"
        if [ "$c_inode" = "$inode" ] && [ "${c_offset:-0}" -le "$size" ]; then
            offset=${c_offset:-0}
        else
            note=" (rotated/truncated: 从头读)"
        fi
    else
        note=" (首次: 从头读)"
    fi
    if [ "$size" -gt "$offset" ]; then
        tail -c +"$((offset + 1))" "$f" | head -c "$((size - offset))" > "$WORK/$base.new"
        n=$(grep -c . "$WORK/$base.new" || true)
    else
        : > "$WORK/$base.new"; n=0
    fi
    NEW_OFFSET["$f"]="$inode:$size"
    NFILES=$((NFILES + 1))
    [ "$n" -gt 0 ] || continue
    TOTAL=$((TOTAL + n))
    {
        echo "== $base  (+$n)$note"
        head -n "$MAX_LINES" "$WORK/$base.new"
        [ "$n" -le "$MAX_LINES" ] || echo "   … 还有 $((n - MAX_LINES)) 行, 见 $f"
        echo
    } >> "$DIGEST"
done

# ── 3. 投递 (可插拔) ──────────────────────────────────────────────────────
HOST=$(hostname 2>/dev/null || echo unknown)
HEADER="$PFX $STAMP host=$HOST files=$NFILES new_lines=$TOTAL"
if [ "$TOTAL" -eq 0 ]; then
    # 硬约束 9: 「没有新增」必须是一条**打出来**的读数, 与「没跑到这里」长得不一样。
    echo "$HEADER"
    echo "DIGEST empty"
    verdict "DIGEST files=$NFILES new_lines=0 sink=$SINK delivered=empty rc=0"
    exit 0
fi

sink_stdout() { echo "$HEADER"; echo; cat "$DIGEST"; }
sink_webhook() {
    # 企微/钉钉机器人共同的最小形状 {"msgtype":"text","text":{"content":…}}。等 owner 给 URL。
    [ -n "$WEBHOOK_URL" ] || return 3
    command -v curl >/dev/null || return 4
    python - "$HEADER" "$DIGEST" > "$WORK/payload.json" <<'PY' || return 5
import json, sys
print(json.dumps({"msgtype": "text", "text": {"content": sys.argv[1] + "\n\n" + open(sys.argv[2], encoding="utf-8").read()}}, ensure_ascii=False))
PY
    curl -sS -m 20 -X POST -H 'Content-Type: application/json' --data-binary @"$WORK/payload.json" "$WEBHOOK_URL" > "$WORK/webhook.out" 2>&1
}
case "$SINK" in
    stdout)  sink_stdout; src=$? ;;
    webhook) sink_webhook; src=$? ;;
    *) dead "unknown-sink:$SINK" "ALERT_SINK 只认 stdout|webhook" ;;
esac
if [ "$src" -ne 0 ]; then
    # ⛔ 游标不前进: 这次没送出去的行下次还会收进来。
    case "$src" in
        3) dead "webhook-url-missing" "ALERT_SINK=webhook 但 ALERT_WEBHOOK_URL 为空" ;;
        4) dead "cmd-not-found:curl" "webhook 投递要 curl" ;;
        *) dead "sink-failed:$SINK" "投递返回 rc=$src, 游标未前进, 下次重发: $(head -c 200 "$WORK/webhook.out" 2>/dev/null)" ;;
    esac
fi

# ── 4. 投递成功才前进游标 ─────────────────────────────────────────────────
for f in "${!NEW_OFFSET[@]}"; do
    printf '%s\n' "${NEW_OFFSET[$f]}" > "$STATE_DIR/$(basename "$f").cursor"
done
verdict "DIGEST files=$NFILES new_lines=$TOTAL sink=$SINK delivered=yes rc=1"
exit 1
