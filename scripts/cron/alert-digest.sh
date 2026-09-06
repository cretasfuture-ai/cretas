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
# ⛔ NOT_SCHEDULED: 未接 crontab, 等渠道 (inventory 里登记)。接的那天 crontab 行的重定向**必须**叫
#    `alert-digest.cron.log` —— 任何含 "alert" 的别的名字 (如 alert-digest.log / digest-alerts.log)
#    会被本脚本的 *alert* 自动发现吃进去, 变成自己读自己 (#681 终审指出)。
#    示例: 0 9 * * * bash /www/wwwroot/cretas/code/scripts/cron/alert-digest.sh >> /www/wwwroot/cretas/logs/alert-digest.cron.log 2>&1
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

# 时间戳与其它 cron 脚本 / cron_dead 同口径 (本地时间, `%F %T`), ⛔ 不混 UTC —— 同一份台账两种时区读不了。
STAMP="$(date '+%F %T')"
WORK="$(mktemp -d)"
VERDICT=0
on_exit() {
    rm -rf "$WORK"
    [ "$VERDICT" = 1 ] || echo "$STAMP INSTRUMENT_DEAD reason=aborted-before-verdict rc=2" >> "$LEDGER"
}
trap on_exit EXIT
verdict() { echo "$STAMP $*" >> "$LEDGER"; VERDICT=1; }
# rc=2 出口: 台账**恰好一行**判决 (与 rc=0/1 同形), 明细行走 $STATE_DIR/digest.alerts (cron_dead 写)。
# ⛔ 不让 cron_dead 往台账里写多行 —— 「每次一行」是台账能被机器数的前提。
dead() { local r=$1; verdict "INSTRUMENT_DEAD reason=$r rc=2"; cron_dead "$STATE_DIR/digest.alerts" "$PFX" "$@"; }

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
TOTAL=0; NFILES=0; UNREADABLE=""
declare -A NEW_OFFSET=()
for f in "${INPUTS[@]}"; do
    [ -r "$f" ] || { UNREADABLE="$UNREADABLE $f"; continue; }
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

# 全部输入都读不了 = 没量到 (⛔ 不是「今天没告警」); 部分读不了要在 digest 里点名。
[ "$NFILES" -gt 0 ] || dead "no-readable-alert-files" "找到 ${#INPUTS[@]} 个 alert 文件但一个都读不了:$UNREADABLE"
[ -z "$UNREADABLE" ] || printf '== (读不了, 本次跳过:%s)\n\n' "$UNREADABLE" >> "$DIGEST"

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
# sink_webhook 的返回码是**本函数自己的**分派键, ⛔ 与 curl 的退出码不共用一个数字空间
# (第一版 `python … || return 5` 撞上了 curl 的 rc=5, 两种失败读成一个原因):
#   3 无 URL / 4 无 curl / 6 payload 拼不出 / 7 curl 本身失败(网络) / 8 HTTP 非 2xx / 9 机器人 errcode≠0
# 🔴 「送到了」的判据是 HTTP 2xx **且** 机器人回 errcode=0: 企微/钉钉在 404/500 和 200+errcode≠0 时都没
#    投递, 若按 curl rc=0 就前进游标, 那一批就**丢了** (#681 终审桩实测: rc=1、游标 32→39)。
SINK_DETAIL=""
sink_webhook() {
    [ -n "$WEBHOOK_URL" ] || return 3
    command -v curl >/dev/null || return 4
    python - "$HEADER" "$DIGEST" > "$WORK/payload.json" 2> "$WORK/payload.err" <<'PY' || { SINK_DETAIL="$(head -c 200 "$WORK/payload.err")"; return 6; }
import json, sys
print(json.dumps({"msgtype": "text", "text": {"content": sys.argv[1] + "\n\n" + open(sys.argv[2], encoding="utf-8").read()}}, ensure_ascii=False))
PY
    # 最后一行是 http_code (-w 追加), 前面是响应体。
    curl -sS -m 20 -X POST -H 'Content-Type: application/json' --data-binary @"$WORK/payload.json" \
        -w '\n%{http_code}' "$WEBHOOK_URL" > "$WORK/webhook.out" 2> "$WORK/webhook.err" \
        || { SINK_DETAIL="curl rc=$? $(head -c 200 "$WORK/webhook.err")"; return 7; }
    local code body
    code=$(tail -n 1 "$WORK/webhook.out"); body=$(head -n -1 "$WORK/webhook.out")
    case "$code" in 2[0-9][0-9]) ;; *) SINK_DETAIL="http=$code body=$(printf '%s' "$body" | head -c 200)"; return 8 ;; esac
    # 企微 / 钉钉都回 {"errcode":N,"errmsg":"…"}; 没有 errcode 字段 (别的机器人) 就按 2xx 算送到。
    local ec
    ec=$(printf '%s' "$body" | python -c 'import json,sys
try:
    d=json.load(sys.stdin); print(d.get("errcode", 0))
except Exception:
    print(0)' 2>/dev/null || echo 0)
    [ "${ec:-0}" = 0 ] || { SINK_DETAIL="errcode=$ec body=$(printf '%s' "$body" | head -c 200)"; return 9; }
    return 0
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
        6) dead "payload-build-failed" "python 拼 JSON 失败 (python 不在 / 版本?): $SINK_DETAIL" ;;
        7) dead "webhook-curl-failed" "curl 没拿到响应, 游标未前进, 下次重发: $SINK_DETAIL" ;;
        8) dead "webhook-http-non-2xx" "机器人 URL 回了非 2xx, 游标未前进, 下次重发: $SINK_DETAIL" ;;
        9) dead "webhook-errcode" "机器人回 2xx 但 errcode≠0 (没投递), 游标未前进, 下次重发: $SINK_DETAIL" ;;
        *) dead "sink-failed:$SINK" "投递返回 rc=$src, 游标未前进, 下次重发" ;;
    esac
fi

# ── 4. 投递成功才前进游标 ─────────────────────────────────────────────────
for f in "${!NEW_OFFSET[@]}"; do
    printf '%s\n' "${NEW_OFFSET[$f]}" > "$STATE_DIR/$(basename "$f").cursor"
done
verdict "DIGEST files=$NFILES new_lines=$TOTAL sink=$SINK delivered=yes rc=1"
exit 1
