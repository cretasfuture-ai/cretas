#!/usr/bin/env bash
# 时间词语料积压告警。
#
# 🔴 三态(硬约束 4): rc=0 没问题 / rc=1 有积压 / rc=2 **这次没量到**。
#    ⛔ 两态会把「没量到」折叠进「没问题」—— 一个连不上库的跑批会安静地
#    天天绿, 而它一个样本都没看过。
#
# ⚙️ 路径/阈值全部可覆盖(裁定 A, 2026-08-16): 生产默认值不变(cron 零配置
#    照跑), 本地用环境变量覆盖来构造三态 —— rc=2(没量到)平时永远不会
#    自然发生, 不构造就不知道它到不到得了。⛔ 不要为了好测就改生产默认值,
#    只是把它们从字面量挪成 `${VAR:-<生产默认值>}`。
set -uo pipefail

CODE_DIR="${TIME_PHRASE_CODE_DIR:-/www/wwwroot/cretas/code/backend/python}"
VENV_ACTIVATE="${TIME_PHRASE_VENV_ACTIVATE:-$CODE_DIR/venv-current/bin/activate}"
ALERTS="${TIME_PHRASE_ALERTS:-/www/wwwroot/cretas/logs/time-phrase-backlog-alerts.log}"
THRESHOLD="${TIME_PHRASE_THRESHOLD:-20}"

OUT=$(cd "$CODE_DIR" \
      && source "$VENV_ACTIVATE" \
      && python -m smartbi.scripts.time_phrase_corpus_cli --counts 2>&1)
rc=$?

if [ "$rc" -ne 0 ]; then
    echo "XXX INSTRUMENT DEAD $(date '+%F %T') — 语料表读不到, 本次读数作废" >> "$ALERTS"
    echo "$OUT" >> "$ALERTS"
    exit 2
fi

# ⚠️ 2026-08-27: 原本写的是 `grep -oP 'total=\K[0-9]+'`。**BSD grep 不支持 -P**
#    —— macOS 上直接 `grep: invalid option -- P`, 脚本整段跑不起来并 exit 2。
#    自建 CI runner 就是 macOS(加拿大 Mac mini), 实测 8 条契约测试当场红。
#    GNU 与 BSD 都支持的只有 `-o`, 所以用「整段匹配 prefix=数字, 再 cut 掉 prefix」
#    替代 PCRE 的 \K。
#    ⛔ 语义与原来完全一致: 同样是子串匹配(`subtotal=5` 照样会被 `total=` 命中),
#       同样「多命中就产出多行」—— 下面那道纯数字锚定的守卫照常拦得住。
total=$(echo "$OUT" | grep -o 'total=[0-9][0-9]*' | cut -d= -f2)
unpromoted=$(echo "$OUT" | grep -o 'unpromoted=[0-9][0-9]*' | cut -d= -f2)

# ⚠️ 解析不出数字也是「没量到」, ⛔ 不是 0
# 🔴 用 -z 只能拦住「空串」, 拦不住「多行」: OUT 用 2>&1 合并了 stdout/stderr,
#    任何一行 stderr 诊断文本只要含 `total=` 子串, grep -o 就会命中两行,
#    $total 变成一个内嵌换行的多行字符串 —— 非空, 但也不是一个合法整数,
#    随后的 -eq / -gt 会报错、被 if 读成 false, 一路滑到 exit 0 (假绿)。
#    改用整体锚定的纯数字正则, 多行/非数字都能拦住。
if ! [[ "$total" =~ ^[0-9]+$ ]] || ! [[ "$unpromoted" =~ ^[0-9]+$ ]]; then
    echo "XXX INSTRUMENT DEAD $(date '+%F %T') — 读数解析失败: $OUT" >> "$ALERTS"
    exit 2
fi

# 🔴 「至今 0 条」单独说 —— 它要么是词表饱和, 要么是**写入路径根本没跑**,
#    ⛔ 与「无积压」是两件事(裁定 C): 「无积压」不写告警(正常路径静默),
#    这里必须写出一行**不同措辞**的记录, 否则两者在告警文件里长得一模一样。
if [ "$total" -eq 0 ]; then
    echo "--- 时间词语料至今 0 条 $(date '+%F %T') — 要么已饱和, 要么写入路径没跑" >> "$ALERTS"
    exit 0
fi

if [ "$unpromoted" -gt "$THRESHOLD" ]; then
    echo "XXX BACKLOG $(date '+%F %T') — 未晋升 $unpromoted 条 (阈值 $THRESHOLD)" >> "$ALERTS"
    exit 1
fi
exit 0
