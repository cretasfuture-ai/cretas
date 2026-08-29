#!/usr/bin/env bash
# 收割自建 runner 上的【孤儿测试环境后端】—— 它们会把 PostgreSQL 连接槽占满, 让所有分支的 CI 全红。
#
# ## 为什么需要它 (2026-08-29 实测)
#
# 那天所有分支的 Java Gate 全红, 签名一致: Tests run ~12330, Failures 0, Errors 4,
# 全在 FactoryConfigServiceImplReorderIT (唯一一个连真 PostgreSQL 的测试类)。
#
#   max_connections = 100
#   实际连接        = 103        <- 连 psql 都进不去
#   其中 78 条来自 8 个临时库, 全部 idle
#
# 8 个跑了 8-17 小时的孤儿测试环境后端, 每个握着 10 条 Hikari 连接不放。
# 起它们的那些验证跑批早就结束了, 但【没有任何东西负责关掉它们】。
#
# ## ⛔ 两个必须知道的坑 (都是当天踩出来的)
#
# 1. **杀连接是无效的**。pg_terminate_backend 让 103 -> 25 看着像成了,
#    20 秒后全爬回来 —— 持有者进程还活着, 池立刻重连。
#    => 要杀的是【进程】不是【连接】。
#
# 2. **判进程身份看 `-jar` 的目标, 不看 `java` 可执行文件在哪**。
#    当天有 4 个进程的命令行里含 /Users/rex/actions-runner-2/... , 差点被读成
#    actions-runner agent 而放过 —— 那其实只是【那个 JVM 二进制的位置】
#    (runner 的工具缓存), 不是进程身份。它们同样是测试环境后端。
#
# ## 用法
#   ./scripts/ci/reap-orphan-testenvs.sh                 # 只报告 (默认)
#   ./scripts/ci/reap-orphan-testenvs.sh --kill          # 真的杀
#   ./scripts/ci/reap-orphan-testenvs.sh --kill --min-age-hours 4
#   SSH_TARGET=canada-mini ./scripts/ci/reap-orphan-testenvs.sh --kill
#   SSH_TARGET=local ./scripts/ci/reap-orphan-testenvs.sh --kill   # 已经在那台机器上时
#
# 退出码 (三态, 不是两态):
#   0 = 没有孤儿 / 已清理
#   1 = 有孤儿但本次只报告 (未加 --kill)
#   2 = 仪器坏了 (连不上 / psql 找不到 / 保护目标不见了) —— 读数作废,
#       ⛔ 不许当成「没问题」
set -uo pipefail

SSH_TARGET="${SSH_TARGET:-canada-mini}"

# 在目标机上执行一条命令。
#
# ⚠️ SSH_TARGET=local 时【直接本地执行】—— 定时任务是跑在 Mac mini 【自己身上】的,
#    让它 ssh 回自己既多一层依赖(key/agent)，失败模式还更难判。
#    ⇒ 本地模式下 SSH 连不上这一类故障从物理上不存在。
# ⛔ else 分支必须是【裸 ssh】。2026-08-29 实测: 用批量替换把
#    `ssh "$SSH_TARGET" "` 换成 `on_host "` 时, 连这一行函数体也被换掉了
#    => on_host 调用它自己 => 无限递归 => 段错误(exit 139)。
#    ▎机械改写会把 helper 变成调用它自己 —— 改完必须跑一次, 不能只看 diff。
on_host() {
    if [ "$SSH_TARGET" = local ]; then
        bash -c "$1"
    else
        ssh "$SSH_TARGET" "$1"
    fi
}

# 仅用于自检: 目标可达吗
host_reachable() {
    if [ "$SSH_TARGET" = local ]; then
        return 0
    fi
    ssh -o BatchMode=yes -o ConnectTimeout=15 "$SSH_TARGET" true 2>/dev/null
}
MIN_AGE_HOURS=2
DO_KILL=0

# ⛔ 绝对不能碰的: 团队在用的测试环境 (100.85.234.108:10010)。
#    它不在 testenvs/ 下, 但多加一道显式排除 —— 这个脚本会 kill, 宁可啰嗦。
PROTECTED_PATH_PATTERN='cretas-testenv-new'

# 孤儿的判据: jar 路径在 testenvs/ 下的 cretas 后端。
ORPHAN_JAR_PATTERN='/Users/rex/testenvs/.*cretas-backend-system.*\.jar'

usage() {
    sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

while (($#)); do
    case "$1" in
        --kill) DO_KILL=1; shift ;;
        --min-age-hours) MIN_AGE_HOURS="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

say() { printf '%s\n' "$*"; }

# ── 仪器自检 ────────────────────────────────────────────────────────────
if ! host_reachable; then
    say "INSTRUMENT_DEAD: 连不上 $SSH_TARGET —— 本次读数作废, ⛔ 不代表「没有孤儿」"
    exit 2
fi

# ⚠️ 阳性对照: 保护目标必须在。它不在, 说明要么机器状态不对, 要么模式写错了,
#    两种情况下继续 kill 都是危险的。
if ! on_host "ps -eo args | grep -q '[c]retas-testenv-new'" 2>/dev/null; then
    say "INSTRUMENT_DEAD: 保护目标 ($PROTECTED_PATH_PATTERN) 不在运行 —— 拒绝继续"
    say "  这台机器上本应有团队测试环境。它不在 = 状态与预期不符, 此时 kill 有误伤风险。"
    exit 2
fi

# ── 枚举孤儿 (pid|etime|jar) ────────────────────────────────────────────
LIST=$(on_host "ps -eo pid,etime,args | grep -E '$ORPHAN_JAR_PATTERN' | grep -v grep | grep -v '$PROTECTED_PATH_PATTERN'" 2>/dev/null)

if [[ -z "$LIST" ]]; then
    say "ORPHANS=0  没有孤儿测试环境后端。"
    exit 0
fi

# etime 形如 DD-HH:MM:SS / HH:MM:SS / MM:SS —— 换算成小时
age_hours() {
    local e="$1" d=0 h=0 m=0
    if [[ "$e" == *-* ]]; then d="${e%%-*}"; e="${e#*-}"; fi
    local IFS=':' ; read -ra parts <<< "$e"
    case "${#parts[@]}" in
        3) h="${parts[0]}"; m="${parts[1]}" ;;
        2) m="${parts[0]}" ;;
    esac
    echo $(( 10#$d * 24 + 10#$h + (10#$m >= 30 ? 1 : 0) ))
}

say "=== testenvs/ 下的后端进程 (阈值: 运行 >= ${MIN_AGE_HOURS}h 判为孤儿) ==="
TARGETS=()
while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    pid=$(awk '{print $1}' <<< "$line")
    etime=$(awk '{print $2}' <<< "$line")
    jar=$(grep -oE '/Users/rex/testenvs/[^ ]*\.jar' <<< "$line" | head -1)
    hrs=$(age_hours "$etime")
    if (( hrs >= MIN_AGE_HOURS )); then
        TARGETS+=("$pid")
        say "  [孤儿] pid=$pid  已跑 $etime (~${hrs}h)  $jar"
    else
        say "  [保留] pid=$pid  已跑 $etime (~${hrs}h, 未达阈值)  $jar"
    fi
done <<< "$LIST"

if ((${#TARGETS[@]} == 0)); then
    say "ORPHANS=0  有 testenvs 后端在跑, 但都未达 ${MIN_AGE_HOURS}h 阈值。"
    exit 0
fi

say ""
say "ORPHANS=${#TARGETS[@]}  (每个约占 10 条 PG 连接 => 约 $(( ${#TARGETS[@]} * 10 )) 条)"

if (( DO_KILL == 0 )); then
    say "本次只报告。真的要清理请加 --kill"
    exit 1
fi

# ── 清理: 逐个 PID, 杀前回读它的 jar 路径再确认一次 ────────────────────
# ⛔ 不用模式批量 kill —— 模式匹配错一次就是误杀 runner 或测试环境。
say ""
say "=== 清理 ==="
for pid in "${TARGETS[@]}"; do
    owner=$(on_host "ps -p $pid -o args= 2>/dev/null | grep -oE '/Users/rex/testenvs/[^ ]*\.jar' | head -1" 2>/dev/null)
    if [[ -z "$owner" ]]; then
        say "  $pid 跳过: 已退出, 或不再是 testenvs 进程 (杀前复查未通过)"
        continue
    fi
    on_host "kill $pid" 2>/dev/null \
        && say "  $pid 已发 TERM  <- $owner" \
        || say "  $pid kill 失败"
done

sleep 8

# ── 验证: 正面证据, ⛔ 不是「没看到报错」 ────────────────────────────────
say ""
say "=== 验证 ==="
LEFT=$(on_host "ps -eo args | grep -E '$ORPHAN_JAR_PATTERN' | grep -v grep | grep -cv '$PROTECTED_PATH_PATTERN'" 2>/dev/null || echo 0)
say "  残留 testenvs 后端: ${LEFT:-0}"

PSQL_BIN='/opt/homebrew/opt/postgresql@17/bin/psql'
CONNS=$(on_host "$PSQL_BIN -U rex -d postgres -tAc 'select count(*) from pg_stat_activity'" 2>/dev/null | tr -d ' \r')
MAXC=$(on_host "$PSQL_BIN -U rex -d postgres -tAc 'show max_connections'" 2>/dev/null | tr -d ' \r')
say "  PG 连接: ${CONNS:-?} / ${MAXC:-?}"

# 阳性对照: 普通用户能连上才算真的腾出来了 (超级用户槽是预留的, 用 rex 连成功说明不了问题)
OK=$(on_host "$PSQL_BIN -U cretas_user -d postgres -tAc 'select 1'" 2>/dev/null | tr -d ' \r')
if [[ "$OK" == "1" ]]; then
    say "  阳性对照: 普通用户 cretas_user 连得上 ✓"
else
    say "  🔴 阳性对照失败: 普通用户仍连不上 —— 槽没真正腾出来, 还有别的持有者"
    exit 2
fi

# 阳性对照 2: 保护目标还活着
if on_host "ps -eo args | grep -q '[c]retas-testenv-new'" 2>/dev/null; then
    say "  阳性对照: 团队测试环境仍在运行 ✓"
else
    say "  🔴 团队测试环境不见了 —— 可能被误杀, 立即人工检查"
    exit 2
fi

say ""
say "REAP_STATUS=done  清理 ${#TARGETS[@]} 个孤儿"
exit 0
