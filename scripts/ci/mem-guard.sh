#!/usr/bin/env bash
# 内存看门狗 (2026-11-14, Java Gate fast-contracts 分片卡死到 job 超时)。
#
# 现象: 分片日志约 7 分钟后整体断流, 0 失败; fork 里的 IdleWatchdog (7 分钟) 和 Maven 步骤 25 分钟超时
# 都没有开火, job 一直挂到 55 分钟被平台取消。能让这三层同时失效的只有「整台 runner 冻住」:
# 12g 堆 + Maven 2g + 非堆 (元空间/线程栈/直接内存) 在 16GB 的 ubuntu-latest 上超卖 ⇒ 换页风暴 ⇒ VM 无响应。
#
# 做法: 后台每 INTERVAL 秒采样 /proc/meminfo 和 surefire fork 的 RSS; 周期性打印一行 MEMGUARD 读数 (含堆/元空间);
# 可用内存连续 CONSECUTIVE 次低于阈值就在 VM 冻住之前打印诊断并 kill -9 fork ⇒ surefire 报
# "The forked VM terminated without properly saying goodbye", Maven 非零退出, 几秒内失败并带着最后一个类名。
#
# 用法: mem-guard.sh <mvn 输出文件>   (后台运行; 父进程退出时自行结束)
# 环境变量: MEMGUARD_MIN_AVAIL_MB (默认 500; 10g 堆实测正常运行最低到 1192MB) MEMGUARD_INTERVAL (默认 2) MEMGUARD_CONSECUTIVE (默认 3)
#           MEMGUARD_PRINT_EVERY (默认 30 秒)  MEMGUARD_FORK_PATTERN (默认 surefirebooter)
#           MEMGUARD_DRY_RUN=1 只打印不 kill (自测用)
set -u

OUT="${1:?usage: mem-guard.sh <mvn-output-file>}"
MIN_AVAIL_MB="${MEMGUARD_MIN_AVAIL_MB:-500}"
INTERVAL="${MEMGUARD_INTERVAL:-2}"
CONSECUTIVE="${MEMGUARD_CONSECUTIVE:-3}"
PRINT_EVERY="${MEMGUARD_PRINT_EVERY:-30}"
PATTERN="${MEMGUARD_FORK_PATTERN:-surefirebooter}"
PARENT="${MEMGUARD_PARENT_PID:-$PPID}"

meminfo_mb() { awk -v k="$1" '$1==k":" {printf "%d", $2/1024}' /proc/meminfo; }
last_class() { grep -a "Running com\." "$OUT" 2>/dev/null | tail -1 | sed 's/.*Running //' ; }
# surefire 用 `sh -c "... java ... surefirebooter.jar"` 起 fork, pgrep -f 会先命中 sh 壳 (RSS 1MB), 所以只认 comm=java。
fork_pid() { ps -eo pid=,comm=,args= 2>/dev/null | awk -v p="$PATTERN" '$2=="java" && index($0,p) {print $1; exit}'; }

low=0
tick=0
start=$(date +%s)
while kill -0 "$PARENT" 2>/dev/null; do
  avail=$(meminfo_mb MemAvailable)
  swap_total=$(meminfo_mb SwapTotal)
  swap_free=$(meminfo_mb SwapFree)
  swap_used=$((swap_total - swap_free))
  pid=$(fork_pid)
  rss=0
  if [ -n "$pid" ] && [ -r "/proc/$pid/status" ]; then
    rss=$(awk '$1=="VmRSS:" {printf "%d", $2/1024}' "/proc/$pid/status")
  fi
  now=$(( $(date +%s) - start ))

  if [ $((tick % (PRINT_EVERY / INTERVAL > 0 ? PRINT_EVERY / INTERVAL : 1))) -eq 0 ]; then
    heap=""
    if [ -n "$pid" ] && command -v jcmd >/dev/null 2>&1; then
      heap=$(timeout 5 jcmd "$pid" GC.heap_info 2>/dev/null | grep -E "garbage-first|Metaspace" | tr -s ' ' | tr '\n' ';' | cut -c1-260)
    fi
    echo "MEMGUARD t=${now}s avail=${avail}MB swap_used=${swap_used}MB fork_pid=${pid:-none} fork_rss=${rss}MB last_class=$(last_class) heap=[${heap}]"
  fi
  tick=$((tick + 1))

  if [ "$avail" -lt "$MIN_AVAIL_MB" ]; then
    low=$((low + 1))
  else
    low=0
  fi
  if [ "$low" -ge "$CONSECUTIVE" ]; then
    echo "::error::MEMGUARD: 可用内存 ${avail}MB < ${MIN_AVAIL_MB}MB 连续 ${low} 次 (swap_used=${swap_used}MB fork_rss=${rss}MB), 在 runner 冻住之前终止测试 fork。最后一个类: $(last_class)"
    ps -eo pid,rss,args --sort=-rss 2>/dev/null | head -6 | cut -c1-200
    if [ -n "$pid" ] && [ "${MEMGUARD_DRY_RUN:-0}" != "1" ]; then
      kill -9 "$pid" 2>/dev/null
      echo "MEMGUARD: 已 kill -9 fork pid=$pid"
    fi
    low=0
    # 给 Maven 时间报错退出; 不再重复开火
    sleep 20
  fi
  sleep "$INTERVAL"
done
