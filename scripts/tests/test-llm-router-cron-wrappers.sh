#!/usr/bin/env bash
# scripts/deploy/llm-router-{billing-canary,rerank-advisor,rules-gap-heatmap}.sh 的测试。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (2026-09-06 审计实测, prod 47)
# ═══════════════════════════════════════════════════════════════════════════
# canary 与 rerank 原来 `source venv38/bin/activate` —— venv38 早已不在。`set -u` 无 `-e`
# 下 source 失败**不中止**, 掉回系统 python 3.6.8, `from __future__ import annotations`
# SyntaxError。日志里各 5 个 SyntaxError(2026-08-03 ~ 08-31, 每周一一次), 而 crontab 那行
# `|| echo "LLM canary FLAGGED"` 把 5 次崩溃写成了 5 次账单告警。
#
# 🔴 终审订正 (同日): venv 是必要不充分。三个脚本**共同的根因是密钥源** —— 它们用
# `systemctl show cretas-python -p Environment` 取密钥, 而那只列 unit 里 inline 的 5 个非密钥变量,
# 看不见 EnvironmentFile=.env.prod (07-20 起密钥全在那里)。于是 07-20 起: canary 每行 NO-KEY
# (81 entries · 0 billing-safe, 仍 rc=1 ⇒ 假账单告警); heatmap 7 次 psql 认证失败 (第一版本测试头
# 写它「还活着」是**错的**); rerank 空 stdout ⇒「✅ 无重排建议」exit 0 = 假绿。
# ⇒ 密钥改从 /proc/<MainPID>/environ 读 (cron_export_service_env), 读不到 ⇒ rc=2。
#
# 这里测的是 shell wrapper 的三态: 假 venv(activate 只把假 python 放进 PATH) + 假
# systemctl(prod 形状: Environment 里没密钥) + 假 /proc environ + 假 psql, ⛔ 不跑真 httpx/真库。
# 每种 rc=2 各构造一次。
#
# 变异 (2026-09-06, 先证到达再看红): canary 密钥源改回 `eval "$(systemctl show … -p Environment …)"`
# ⇒ [k1]/[c1] 都红 (桩的 Environment 里没密钥, 于是 no-llm-keys); rerank 去掉 returncode 判定的
# python 层只能静态断言 [k9]; 去掉 wrapper 的 password 存在性 ⇒ [k2] 红。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CANARY="$ROOT_DIR/scripts/deploy/llm-router-billing-canary.sh"
RERANK="$ROOT_DIR/scripts/deploy/llm-router-rerank-advisor.sh"
HEATMAP="$ROOT_DIR/scripts/deploy/llm-router-rules-gap-heatmap.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

BASH_ABS=$(command -v bash) || fail "本机缺 bash"
for s in "$CANARY" "$RERANK" "$HEATMAP"; do
    [ -s "$s" ] || fail "$s 不存在"; bash -n "$s" || fail "语法错误: $s"
    grep -q 'cron-common.sh' "$s" || fail "$(basename "$s") 没有 source cron-common.sh"
    # ⚠️ 只看非注释行 —— 脚本注释里**记着** venv38 这段历史, 闸不能把自己的注释当被测对象。
    grep -vE '^[[:space:]]*#' "$s" | grep -q 'venv38' && fail "$(basename "$s") 仍引用 venv38 (它不存在)"
done

# ── 桩 ────────────────────────────────────────────────────────────────────
FAKEBIN="$TMP/bin"; STUB="$TMP/stub"; PYDIR="$TMP/pydir"; PYBIN="$TMP/pyvenv/bin"
mkdir -p "$FAKEBIN" "$STUB" "$PYDIR/venv-current/bin" "$PYBIN"
for t in date mktemp rm grep head cut tr sed cat dirname awk mkdir; do
    abs=$(command -v "$t") || fail "本机缺 $t"
    printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$FAKEBIN/$t"; chmod +x "$FAKEBIN/$t"
done
export STUB
# 🔴 systemctl 桩按 **prod 现状**的形状 (2026-09-06 终审): `-p Environment` 只回 unit 里 inline 的
#    5 个非密钥变量 —— 密钥在 EnvironmentFile=.env.prod, 只在活进程的 /proc/<MainPID>/environ 里。
#    第一版桩把密钥塞进 Environment= 的输出, 于是「从 -p Environment 读密钥」这条**已经断了 7 周**
#    的路在测试里是通的 (形态 B: 桩的形状在生产上不存在)。
cat > "$FAKEBIN/systemctl" <<'S'
#!/bin/sh
echo "systemctl $*" >> "$STUB/calls.log"
case "$*" in
  *"-p MainPID"*) [ -f "$STUB/mainpid" ] && cat "$STUB/mainpid" || echo 4242 ;;
  *"-p Environment"*) echo "Environment=POSTGRES_DB=smartbi_prod_db POSTGRES_ENABLED=true POSTGRES_HOST=localhost POSTGRES_PORT=5432 POSTGRES_USER=smartbi_user" ;;
esac
S
# 活进程 environ 夹具 (NUL 分隔, 与真 /proc/<pid>/environ 一样)。PATH 也放进去: 证明只 export 匹配前缀的。
PROC="$TMP/proc"; mkdir -p "$PROC/4242"
make_environ() {  # 参数 = KEY=VAL...
    : > "$PROC/4242/environ"; local kv
    for kv in "$@"; do printf '%s\0' "$kv" >> "$PROC/4242/environ"; done
}
FULL_ENVIRON=(LLM_ALIYUN_A_API_KEY=k LLM_TENCENT_API_KEY=k2 POSTGRES_PASSWORD=p POSTGRES_USER=u POSTGRES_HOST=h PATH=/should/not/leak)
cat > "$FAKEBIN/psql" <<'S'
#!/bin/sh
echo "psql $*" >> "$STUB/calls.log"
cat > /dev/null
[ -f "$STUB/psql.rc" ] && exit "$(cat "$STUB/psql.rc")"; exit 0
S
chmod +x "$FAKEBIN/systemctl" "$FAKEBIN/psql"
# 假 venv: activate 只把假 python 放进 PATH(真 activate 也就是干这个)。
printf 'export PATH="%s:$PATH"\n' "$PYBIN" > "$PYDIR/venv-current/bin/activate"
# 桩 python 在 `-` (heredoc) 时打 canary 的抬头行 —— wrapper 现在按「有没有 `[canary ` 抬头」判
# 探针是否跑到了判定那一步; $STUB/python.noheader 在时不打 (模拟崩在判定之前)。
cat > "$PYBIN/python" <<'S'
#!/bin/sh
echo "python $*" >> "$STUB/calls.log"
case "$1" in
  -c) [ -f "$STUB/python-old" ] && exit 1; exit 0 ;;      # 版本守卫
  --version) echo "Python 3.11.9"; exit 0 ;;
  -) cat > /dev/null
     [ -f "$STUB/python.noheader" ] || echo "[canary 2026-09-06] 3 entries · 2 billing-safe · registry age 1d"
     [ -f "$STUB/python.rc" ] && exit "$(cat "$STUB/python.rc")"; exit 0 ;;
esac
exit 0
S
chmod +x "$PYBIN/python"
# 桩自检: 在受限 PATH 下 python 找不到(只有 activate 之后才有), systemctl 找得到。
PATH="$FAKEBIN" command -v python >/dev/null 2>&1 && fail "受限 PATH 不该直接有 python"
PATH="$FAKEBIN" command -v systemctl >/dev/null 2>&1 || fail "受限 PATH 下应有 systemctl 桩"

ALERTS="$TMP/alerts.log"; RC=0
run_it() {  # $1 = 脚本
    rm -f "$ALERTS" "$STUB/calls.log"
    # env -i 之后 PATH 只剩假目录, bash 自己要用绝对路径调。
    env -i PATH="$FAKEBIN" HOME="$TMP" STUB="$STUB" CRON_BIN_PATH="$FAKEBIN" CRON_PROC_ROOT="$PROC" \
        LLM_ROUTER_PYDIR="$PYDIR" LLM_ROUTER_ALERTS="$ALERTS" "$BASH_ABS" "$1" > "$TMP/out" 2>&1; RC=$?
    echo "    $(basename "$1") rc=$RC alerts: $( [ -f "$ALERTS" ] && head -c 200 "$ALERTS" | tr '\n' '|' || echo '<none>')"
}
reset() { rm -f "$STUB"/*; make_environ "${FULL_ENVIRON[@]}"; }
# 三个脚本都不许再从 `systemctl show … -p Environment` 取密钥 (非注释行)。
for s in "$CANARY" "$RERANK" "$HEATMAP"; do
    grep -vE '^[[:space:]]*#' "$s" | grep -q 'show cretas-python -p Environment' \
        && fail "$(basename "$s") 仍从 systemctl show -p Environment 取密钥 —— 那看不见 EnvironmentFile"
done
calls() { [ -f "$STUB/calls.log" ] && cat "$STUB/calls.log" || true; }
assert_alert() { grep -Fq -- "$1" "$ALERTS" || fail "$2: 告警里没有 [$1]"; }
RC2=""
note_rc2() { RC2="$RC2 $1"; }

# ═════════ canary ═════════════════════════════════════════════════════════
reset; run_it "$CANARY"
[ "$RC" = 0 ] || fail "[c1] 期望 rc=0 实得 $RC: $(cat "$TMP/out")"
[ ! -f "$ALERTS" ] || fail "[c1] PASS 不该写告警"
case "$(calls)" in *"python -"*) ;; *) fail "[c1] python 探针没被调用: $(calls)" ;; esac
ok "canary 阳性对照: venv 在 + python 0 ⇒ rc=0 无告警"

reset; printf '1' > "$STUB/python.rc"; run_it "$CANARY"
[ "$RC" = 1 ] || fail "[c2] 期望 rc=1 实得 $RC"; assert_alert "LLM CANARY FLAGGED rc=1" "[c2]"
grep -q "INSTRUMENT DEAD" "$ALERTS" && fail "[c2] 真 FLAGGED 不许写成 INSTRUMENT DEAD"
ok "canary python rc=1 ⇒ rc=1 FLAGGED (与崩溃措辞不同)"

reset; printf '3' > "$STUB/python.rc"; run_it "$CANARY"
[ "$RC" = 3 ] || fail "[c3] 期望 rc=3 实得 $RC"; assert_alert "注册表快照过期" "[c3]"
ok "canary python rc=3 ⇒ rc=3 注册表过期"

reset; mv "$PYDIR/venv-current/bin/activate" "$TMP/activate.bak"; run_it "$CANARY"
[ "$RC" = 2 ] || fail "[c4] 期望 rc=2 实得 $RC"; assert_alert "LLM CANARY INSTRUMENT DEAD" "[c4]"; assert_alert "reason=venv-missing" "[c4]"
case "$(calls)" in *python*) fail "[c4] venv 不在时不许调到任何 python(那就是掉回系统 python 的路)" ;; esac
mv "$TMP/activate.bak" "$PYDIR/venv-current/bin/activate"; note_rc2 venv-missing
ok "🔴 canary venv 不在 ⇒ rc=2 reason=venv-missing, python 零调用 (这就是 8-03 起每周崩的那条)"

reset; : > "$STUB/python-old"; run_it "$CANARY"
[ "$RC" = 2 ] || fail "[c5] 期望 rc=2 实得 $RC"; assert_alert "reason=python-too-old" "[c5]"
# ⚠️ 整行匹配 `python -`: 子串匹配会撞上 cron_dead 明细里那次 `python --version`。
grep -qx 'python -' "$STUB/calls.log" && fail "[c5] 版本太旧不该再跑探针"
note_rc2 python-too-old
ok "canary python 版本守卫不过 ⇒ rc=2 reason=python-too-old"

reset; mv "$FAKEBIN/systemctl" "$TMP/systemctl.bak"; run_it "$CANARY"
[ "$RC" = 2 ] || fail "[c6] 期望 rc=2 实得 $RC"; assert_alert "reason=cmd-not-found:systemctl" "[c6]"
mv "$TMP/systemctl.bak" "$FAKEBIN/systemctl"; note_rc2 cmd-not-found:systemctl
ok "canary 假 PATH 缺 systemctl ⇒ rc=2 reason=cmd-not-found:systemctl"

# ═════════ rerank ═════════════════════════════════════════════════════════
reset; run_it "$RERANK"
[ "$RC" = 0 ] || fail "[r1] 期望 rc=0 实得 $RC: $(cat "$TMP/out")"; [ ! -f "$ALERTS" ] || fail "[r1] 不该写告警"
ok "rerank 阳性对照 ⇒ rc=0"
reset; printf '1' > "$STUB/python.rc"; run_it "$RERANK"
[ "$RC" = 2 ] || fail "[r2] 期望 rc=2 实得 $RC"; assert_alert "LLM RERANK INSTRUMENT DEAD" "[r2]"; assert_alert "reason=python-failed" "[r2]"
note_rc2 python-failed
ok "rerank python 崩 ⇒ rc=2 reason=python-failed (它是只读建议, 非 0 只可能是崩)"
reset; mv "$PYDIR/venv-current/bin/activate" "$TMP/activate.bak"; run_it "$RERANK"
[ "$RC" = 2 ] || fail "[r3] 期望 rc=2 实得 $RC"; assert_alert "reason=venv-missing" "[r3]"
mv "$TMP/activate.bak" "$PYDIR/venv-current/bin/activate"
ok "rerank venv 不在 ⇒ rc=2 reason=venv-missing"
reset; mv "$FAKEBIN/psql" "$TMP/psql.bak"; run_it "$RERANK"
[ "$RC" = 2 ] || fail "[r4] 期望 rc=2 实得 $RC"; assert_alert "reason=cmd-not-found:psql" "[r4]"
mv "$TMP/psql.bak" "$FAKEBIN/psql"; note_rc2 cmd-not-found:psql
ok "rerank 假 PATH 缺 psql ⇒ rc=2 reason=cmd-not-found:psql"

# ═════════ heatmap ════════════════════════════════════════════════════════
reset; run_it "$HEATMAP"
[ "$RC" = 0 ] || fail "[h1] 期望 rc=0 实得 $RC: $(cat "$TMP/out")"; [ ! -f "$ALERTS" ] || fail "[h1] 不该写告警"
grep -q '规则缺口热力图' "$TMP/out" || fail "[h1] 报告抬头没打出来"
ok "heatmap 阳性对照 ⇒ rc=0"
reset; printf '2' > "$STUB/psql.rc"; run_it "$HEATMAP"
[ "$RC" = 2 ] || fail "[h2] 期望 rc=2 实得 $RC"; assert_alert "LLM RULES-GAP INSTRUMENT DEAD" "[h2]"; assert_alert "reason=psql-failed" "[h2]"
note_rc2 psql-failed
ok "heatmap psql 失败 ⇒ rc=2 reason=psql-failed"
reset; mv "$FAKEBIN/psql" "$TMP/psql.bak"; run_it "$HEATMAP"
[ "$RC" = 2 ] || fail "[h3] 期望 rc=2 实得 $RC"; assert_alert "reason=cmd-not-found:psql" "[h3]"
mv "$TMP/psql.bak" "$FAKEBIN/psql"
ok "heatmap 假 PATH 缺 psql ⇒ rc=2"

# ═════════ 密钥源 (2026-09-06 终审四条) ═══════════════════════════════════
# 探针在 environ 只有非密钥变量时 (= prod 07-20 ~ 09-06 的真实状态) 必须 rc=2, ⛔ 不是 FLAGGED / 假绿。
reset; make_environ POSTGRES_USER=u POSTGRES_HOST=h PATH=/x; run_it "$CANARY"
[ "$RC" = 2 ] || fail "[k1] 期望 rc=2 实得 $RC"; assert_alert "reason=no-llm-keys-in-service-environ" "[k1]"
grep -qx 'python -' "$STUB/calls.log" && fail "[k1] 没密钥不该发探针"
note_rc2 no-llm-keys-in-service-environ
ok "🔴 canary: environ 无 LLM_*_API_KEY (prod 7 周的真实状态) ⇒ rc=2, 不是 FLAGGED"
reset; make_environ LLM_ALIYUN_A_API_KEY=k POSTGRES_USER=u; run_it "$RERANK"
[ "$RC" = 2 ] || fail "[k2] 期望 rc=2 实得 $RC"; assert_alert "reason=no-db-password-in-service-environ" "[k2]"
grep -qx 'python -' "$STUB/calls.log" && fail "[k2] 没密码不该跑 python"
note_rc2 no-db-password-in-service-environ
ok "🔴 rerank: environ 无 POSTGRES_PASSWORD ⇒ rc=2 (原来空密码 → psql 失败 → 假绿)"
reset; make_environ LLM_ALIYUN_A_API_KEY=k POSTGRES_USER=u; run_it "$HEATMAP"
[ "$RC" = 2 ] || fail "[k3] 期望 rc=2 实得 $RC"; assert_alert "reason=no-db-password-in-service-environ" "[k3]"
assert_no_call_psql() { case "$(calls)" in *psql*) fail "[k3] 没密码不该去撞 psql 认证" ;; esac; }; assert_no_call_psql
ok "🔴 heatmap: environ 无 POSTGRES_PASSWORD ⇒ rc=2, 不带空密码撞认证"
reset; printf '0' > "$STUB/mainpid"; run_it "$CANARY"
[ "$RC" = 2 ] || fail "[k4] 期望 rc=2 实得 $RC"; assert_alert "reason=service-not-running:cretas-python" "[k4]"
note_rc2 service-not-running
ok "canary: MainPID=0 ⇒ rc=2 service-not-running"
reset; rm -f "$PROC/4242/environ"; run_it "$RERANK"
[ "$RC" = 2 ] || fail "[k5] 期望 rc=2 实得 $RC"; assert_alert "reason=service-environ-unreadable:cretas-python" "[k5]"
note_rc2 service-environ-unreadable
ok "rerank: /proc/<pid>/environ 读不了 ⇒ rc=2 service-environ-unreadable"
reset; printf '2' > "$STUB/python.rc"; run_it "$CANARY"
[ "$RC" = 2 ] || fail "[k6] 期望 rc=2 实得 $RC"; assert_alert "reason=probe-reported-dead" "[k6]"
grep -q 'FLAGGED' "$ALERTS" && fail "[k6] python 自报仪器死不许写成 FLAGGED"
note_rc2 probe-reported-dead
ok "canary: python 探针 exit 2 (全部 NO-KEY) ⇒ rc=2 probe-reported-dead, 不是 FLAGGED"
reset; printf '2' > "$STUB/python.rc"; run_it "$RERANK"
[ "$RC" = 2 ] || fail "[k7] 期望 rc=2 实得 $RC"; assert_alert "reason=psql-failed" "[k7]"
ok "rerank: python 自报 psql 失败 (exit 2) ⇒ rc=2 psql-failed"
# 只 export 匹配前缀的: 夹具里的 PATH=/should/not/leak 不许进脚本环境 (进了的话 python 桩就找不到了)。
reset; run_it "$CANARY"
[ "$RC" = 0 ] || fail "[k8] 期望 rc=0 实得 $RC: $(cat "$TMP/out")"
grep -q 'service-env exported=2 match=' "$TMP/out" || fail "[k8] 应只 export 2 个 LLM_*_API_KEY: $(grep service-env "$TMP/out")"
ok "canary: 只 export 匹配前缀的 2 个键, environ 里的 PATH 没泄进来"
# python 层 (heredoc 里) 的两处改动 —— 全 NO-KEY ⇒ sys.exit(2) / psql returncode ⇒ sys.exit(2) ——
# 这里只能读代码断言存在, ⛔ 桩 python 跑不到真 heredoc:
grep -q 'no_key == len(pairs)' "$CANARY" || fail "[k9] canary python 层缺「全部 NO-KEY ⇒ exit 2」"
grep -q 'if out.returncode != 0' "$RERANK" || fail "[k9] rerank python 层缺 returncode 判定"
ok "python 层两处守卫存在 (静态断言; 真 heredoc 需要 httpx/真库, 登记为只读代码验证)"

# ═════════ cron-common.sh 不在 (装序错 / 47 上 3d 还没同步) ⇒ rc=2, ⛔ 不许滑到 3.6.8 ═══════
# 构造法: 把 wrapper 复制到一棵**没有 ../cron/cron-common.sh** 的树里跑 (它按自己的位置解析
# 库路径), ⛔ 不动仓库里的真文件。终审实测: 没守卫时五个 cron_* 逐个 command not found, heredoc
# 落到 cron 默认 PATH 的 python ⇒ rc=1 ⇒「LLM CANARY FLAGGED rc=1」—— 正是要消灭的读数。
NOCOMMON="$TMP/nocommon/deploy"; mkdir -p "$NOCOMMON"
[ ! -e "$TMP/nocommon/cron/cron-common.sh" ] || fail "[m0] 夹具坏了: 不该有 cron-common.sh"
for s in "$CANARY" "$RERANK" "$HEATMAP"; do
    cp "$s" "$NOCOMMON/"; reset; run_it "$NOCOMMON/$(basename "$s")"
    [ "$RC" = 2 ] || fail "[m1 $(basename "$s")] 期望 rc=2 实得 $RC: $(head -3 "$TMP/out")"
    assert_alert "INSTRUMENT DEAD" "[m1 $(basename "$s")]"; assert_alert "reason=cron-common-missing" "[m1 $(basename "$s")]"
    grep -q 'FLAGGED' "$ALERTS" && fail "[m1 $(basename "$s")] 库不在不许写成 FLAGGED"
    case "$(calls)" in *python*) fail "[m1 $(basename "$s")] 库不在不该跑到 python" ;; esac
done
note_rc2 cron-common-missing
ok "🔴 三个 wrapper: cron-common.sh 不在 ⇒ rc=2 reason=cron-common-missing, 不写 FLAGGED, 不碰 python"

# ═════════ 探针崩在判定之前 (没打 `[canary ` 抬头) ⇒ rc=2 probe-crashed, ⛔ 不按 rc 落进 FLAGGED ═══
# 2026-09-06 首跑: heredoc `KeyError: 'aistore'` ⇒ python rc=1 ⇒ 写成「FLAGGED rc=1」。现在 python 侧
# excepthook 会 exit 2 (test-llm-router-canary-probe.sh 钉), 这里是 wrapper 侧的第二道: 抬头没打就是崩。
reset; : > "$STUB/python.noheader"; printf '1' > "$STUB/python.rc"; run_it "$CANARY"
[ "$RC" = 2 ] || fail "[p1] 期望 rc=2 实得 $RC"; assert_alert "reason=probe-crashed" "[p1]"
grep -q 'FLAGGED' "$ALERTS" && fail "[p1] 崩在判定前不许写 FLAGGED (这就是首跑那条假账单告警)"
note_rc2 probe-crashed
ok "🔴 canary: python rc=1 但没打抬头 ⇒ rc=2 probe-crashed, 不写 FLAGGED (首跑 KeyError 的长相)"

n=$(printf '%s' "$RC2" | wc -w | tr -d ' ')
echo "READING: RC2_REASONS_CONSTRUCTED=$n [${RC2# }]"
echo "PASS: test-llm-router-cron-wrappers.sh ($PASSED 组断言)"
