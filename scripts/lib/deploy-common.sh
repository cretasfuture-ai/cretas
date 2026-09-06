#!/bin/bash
# 部署脚本共享函数库 v1.0
# 用法: source scripts/lib/deploy-common.sh
#
# 提供: log(), wait_for_health(), archive_backup(), check_disk_space()

# ==================== 日志 ====================

# 日志级别: DEBUG=0, INFO=1, WARN=2, ERROR=3
_LOG_LEVEL="${LOG_LEVEL:-INFO}"
_LOG_FILE="${LOG_FILE:-}"

_log_level_num() {
    case "$1" in
        DEBUG) echo 0 ;;
        INFO)  echo 1 ;;
        WARN)  echo 2 ;;
        ERROR) echo 3 ;;
        *)     echo 1 ;;
    esac
}

# log <LEVEL> <message...>
# 结构化日志: [timestamp] [LEVEL] message
log() {
    local level="$1"
    shift
    local message="$*"
    local timestamp
    timestamp=$(date '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S')

    local current_level_num
    current_level_num=$(_log_level_num "$_LOG_LEVEL")
    local msg_level_num
    msg_level_num=$(_log_level_num "$level")

    # 跳过低于当前级别的消息
    [ "$msg_level_num" -lt "$current_level_num" ] && return 0

    local log_entry="[$timestamp] [$level] $message"

    # 输出到 stdout
    echo "$log_entry"

    # 如果设置了日志文件，同时写入
    if [ -n "$_LOG_FILE" ]; then
        echo "$log_entry" >> "$_LOG_FILE"
    fi
}

# ==================== 健康检查 ====================

# wait_for_health <url> [retries] [interval_seconds]
# 返回: 0=成功, 1=超时
wait_for_health() {
    local url="$1"
    local retries="${2:-60}"      # Round 5 fix: bumped default 30→60 (Spring Boot with
                                   # BERT intent loading needs >60s on first start)
    local interval="${3:-2}"
    local total_wait=$((retries * interval))

    log "INFO" "健康检查: $url (最多等待 ${total_wait}s)"

    for i in $(seq 1 "$retries"); do
        local status
        # Round 5 fix: added --max-time/--connect-timeout so curl can't hang forever
        # when the TCP connect stalls (previously each failed probe could wait minutes).
        status=$(curl -s -o /dev/null --connect-timeout 2 --max-time 3 -w "%{http_code}" "$url" 2>/dev/null || echo "000")

        if [ "$status" = "200" ]; then
            log "INFO" "服务正常 (HTTP 200, 等待 $((i * interval))s)"
            return 0
        fi

        if [ $((i % 5)) -eq 0 ]; then
            log "INFO" "等待服务启动... ($((i * interval))/${total_wait}s, HTTP $status)"
        fi

        sleep "$interval"
    done

    log "ERROR" "健康检查超时 (${total_wait}s), 最后状态: HTTP $status"
    return 1
}

# wait_for_health_via_ssh <ssh_target> <port> [path] [retries] [interval]
# 返回: 0=成功, 1=超时
#
# 2026-05-18: SG Phase 3 收紧后 47:10010/10011/8083/8084 仅允许 nginx 139 source IP.
# 本地开发机 public-IP curl 拿 HTTP 000 (firewall reject), wait_for_health() 误判
# 服务挂掉. 这个 helper SSH 到 47 走 loopback (http://localhost:<port>) 绕过 SG.
# 测试服务 (10011, 8084) 没有 nginx upstream, **必须**走这个 helper.
# Prod 服务 (10010, 8083) 部署完用 nginx upstream 验证更标准; 但 in-place / rollback
# fallback 路径也可以用这个 helper.
wait_for_health_via_ssh() {
    local ssh_target="$1"
    local port="$2"
    local path="${3:-/api/mobile/health}"
    local retries="${4:-60}"
    local interval="${5:-2}"
    local total_wait=$((retries * interval))

    log "INFO" "健康检查 (SSH localhost): ${ssh_target} :${port}${path} (最多等待 ${total_wait}s)"

    local i status=""
    for i in $(seq 1 "$retries"); do
        # outer `|| echo "000"` 兜底 ssh 自身失败 (无网络/host down); inner `|| echo '000'`
        # 兜底 curl 失败 (服务还没起). 双层防 set -e 杀整个 deploy.
        status=$(ssh -o ConnectTimeout=3 "$ssh_target" \
            "curl -s -o /dev/null --connect-timeout 2 --max-time 3 -w '%{http_code}' http://localhost:${port}${path} 2>/dev/null || echo '000'" \
            2>/dev/null || echo "000")
        # 取最后一行 (有时 ssh banner / motd 会污染 stdout)
        status=$(echo "$status" | tail -1)

        if [ "$status" = "200" ]; then
            log "INFO" "服务正常 (HTTP 200, 等待 $((i * interval))s)"
            return 0
        fi

        if [ $((i % 5)) -eq 0 ]; then
            log "INFO" "等待服务启动... ($((i * interval))/${total_wait}s, HTTP $status)"
        fi

        sleep "$interval"
    done

    log "ERROR" "健康检查超时 (${total_wait}s), 最后状态: HTTP $status"
    return 1
}

# ==================== Git 同步检查 ====================

# check_git_sync <project_root> [step_label] [strict]
# 部署前确认本地 HEAD 与 origin/main 一致 (防 stale-local-deploy, May 11 2026 bug fix;
# per HARD rule feedback_organizer_must_git_pull_before_deploy.md):
# - 在 main 分支且与 origin/main 不一致 → ERROR + exit 1 (除非 SKIP_GIT_CHECK=1)
# - 非 main 分支 → WARN 提示确认部署源
# - 工作树有未提交改动 → WARN (非致命)
#
# strict="1" (Jul 7 2026 事故修复: 非 main 分支 + 脏工作树从主目录部署 prod 覆盖了
# origin/main 的代码): prod 部署默认严格模式 —
# - HEAD != origin/main (commit 层面比较, 不看分支名 — detached HEAD 落在
#   origin/main 上是合法的干净 worktree 部署形态) → ABORT (除非 SKIP_GIT_CHECK=1)
# - 工作树有未提交改动 (含 staged) → ABORT (除非 ALLOW_DIRTY_DEPLOY=1)
# strict 为空/非 "1" 时行为与之前完全一致 (test 环境等仍只 WARN).
# 依赖调用方已 source 本库 (用 log). 注意: 函数内 cd 会改变调用方 cwd (与原内联行为一致).
check_git_sync() {
    local project_root="$1"
    local step_label="${2:-Git sync pre-check...}"
    local strict="${3:-}"
    cd "$project_root" || { log "ERROR" "check_git_sync: 无法 cd 到 $project_root"; exit 1; }
    log "INFO" "$step_label"
    git fetch origin main 2>/dev/null || log "WARN" "git fetch origin main failed (offline?), continue with caution"

    local local_sha origin_sha current_branch
    local_sha=$(git rev-parse HEAD 2>/dev/null || echo "unknown")
    origin_sha=$(git rev-parse origin/main 2>/dev/null || echo "unknown")
    current_branch=$(git branch --show-current 2>/dev/null || echo "unknown")

    if [ "$local_sha" != "$origin_sha" ] && [ "$local_sha" != "unknown" ] && [ "$origin_sha" != "unknown" ]; then
        if [ "$strict" = "1" ]; then
            log "ERROR" "⛔ Strict git gate (prod deploy): HEAD != origin/main — ABORT"
            log "ERROR" "  local HEAD : $local_sha (branch: $current_branch)"
            log "ERROR" "  origin/main: $origin_sha"
            log "ERROR" "  正确做法: 从干净 worktree 部署 —"
            log "ERROR" "    git worktree add ../cretas-deploy-clean origin/main && cd ../cretas-deploy-clean"
            log "ERROR" "  或本地 main 分支同步: git checkout main && git pull origin main"
            log "ERROR" "  逃生门 (确认知道风险才用): SKIP_GIT_CHECK=1 重新运行部署脚本"
            if [ "${SKIP_GIT_CHECK:-}" != "1" ]; then
                exit 1
            fi
            log "WARN" "⚠️  SKIP_GIT_CHECK=1 set, continuing STRICT prod deploy with HEAD != origin/main"
        elif [ "$current_branch" = "main" ]; then
            log "ERROR" "Local main HEAD != origin/main HEAD"
            log "ERROR" "  local : $local_sha"
            log "ERROR" "  origin: $origin_sha"
            log "ERROR" "Run: cd $project_root && git pull origin main"
            log "ERROR" "Override: SKIP_GIT_CHECK=1 重新运行部署脚本"
            if [ "${SKIP_GIT_CHECK:-}" != "1" ]; then
                exit 1
            fi
            log "WARN" "SKIP_GIT_CHECK=1 set, continuing deploy with stale local"
        else
            log "WARN" "Current branch is '$current_branch' (not main). Verify intended deploy source."
        fi
    else
        log "INFO" "  Git: local HEAD matches origin/main ✓"
    fi

    # Dirty tree check: strict=1 → ABORT, 否则 WARN (non-fatal, 与之前一致)
    if ! git diff --quiet 2>/dev/null || ! git diff --cached --quiet 2>/dev/null; then
        if [ "$strict" = "1" ]; then
            log "ERROR" "⛔ Strict git gate (prod deploy): working tree has uncommitted changes — ABORT"
            log "ERROR" "  查看改动: git -C $project_root status"
            log "ERROR" "  正确做法: 从干净 worktree 部署 —"
            log "ERROR" "    git worktree add ../cretas-deploy-clean origin/main && cd ../cretas-deploy-clean"
            log "ERROR" "  逃生门 (确认知道风险才用): ALLOW_DIRTY_DEPLOY=1 重新运行部署脚本"
            if [ "${ALLOW_DIRTY_DEPLOY:-}" != "1" ]; then
                exit 1
            fi
            log "WARN" "⚠️  ALLOW_DIRTY_DEPLOY=1 set, continuing STRICT prod deploy with dirty working tree"
        else
            log "WARN" "Working tree has uncommitted changes — deploy will use local working tree state"
        fi
    fi
}

# ==================== 备份管理 ====================

# archive_backup <file_path> [keep_count]
# 创建带时间戳的备份，保留最近 N 份
archive_backup() {
    local file="$1"
    local keep="${2:-3}"

    if [ ! -f "$file" ]; then
        log "WARN" "备份目标不存在: $file"
        return 0
    fi

    local backup_name="${file}.bak.$(date +%Y%m%d_%H%M%S)"
    cp "$file" "$backup_name"
    log "INFO" "备份: $backup_name"

    # 清理旧备份，保留最近 $keep 份
    local old_backups
    old_backups=$(ls -t "${file}.bak."* 2>/dev/null | tail -n +$((keep + 1)))
    if [ -n "$old_backups" ]; then
        echo "$old_backups" | xargs rm -f 2>/dev/null || true
        local cleaned
        cleaned=$(echo "$old_backups" | wc -l)
        log "DEBUG" "清理 $cleaned 份旧备份"
    fi
}

# ==================== 磁盘检查 ====================

# check_disk_space <path> <required_mb>
# 检查指定路径的可用磁盘空间
# 返回: 0=足够, 1=不足
check_disk_space() {
    local path="$1"
    local required_mb="$2"

    local available_kb
    available_kb=$(df "$path" 2>/dev/null | awk 'NR==2 {print $4}')

    if [ -z "$available_kb" ]; then
        log "WARN" "无法检查磁盘空间: $path"
        return 0  # 检查失败不阻塞部署
    fi

    local available_mb=$((available_kb / 1024))

    if [ "$available_mb" -lt "$required_mb" ]; then
        log "ERROR" "磁盘空间不足: ${available_mb}MB 可用, 需要 ${required_mb}MB ($path)"
        return 1
    fi

    log "DEBUG" "磁盘空间充足: ${available_mb}MB 可用 ($path)"
    return 0
}

# ==================== 文件大小 ====================

# get_file_size_bytes <file_path>
# 跨平台获取文件大小 (MSYS/Linux/macOS)
get_file_size_bytes() {
    local file="$1"
    wc -c < "$file" 2>/dev/null | tr -d ' '
}

# get_file_size_human <file_path>
# 人类可读的文件大小
get_file_size_human() {
    local file="$1"
    du -h "$file" 2>/dev/null | cut -f1
}

# ==================== 进程管理 ====================

# graceful_kill <pid...>
# 先 SIGTERM，等待 10s，再 SIGKILL
graceful_kill() {
    local pids=("$@")

    for pid in "${pids[@]}"; do
        kill -TERM "$pid" 2>/dev/null || true
    done

    # 等待优雅退出
    local elapsed=0
    while [ $elapsed -lt 10 ]; do
        local alive=0
        for pid in "${pids[@]}"; do
            kill -0 "$pid" 2>/dev/null && alive=1
        done
        [ "$alive" -eq 0 ] && return 0
        sleep 1
        elapsed=$((elapsed + 1))
    done

    # 强制终止
    for pid in "${pids[@]}"; do
        kill -9 "$pid" 2>/dev/null || true
    done
    log "WARN" "强制终止 ${#pids[@]} 个进程"
}

# ==================== 并发锁 (防多 chat 同时 deploy 覆盖 jar) ====================

# acquire_deploy_lock <lock_name>
# 获取 deploy 互斥锁. flock 可用则用 flock, 否则退化到 PID 文件.
# 在 deploy 脚本开头调用. 锁会在进程退出时自动释放.
#
# 示例:
#   acquire_deploy_lock "cretas-backend" || exit 1
# 锁文件里记的进程是否还活着。
#
# 🔴 2026-08-03 实证: 部署被杀后 /tmp/*.lock 里的 PID 在 Git Bash `ps` 里还挂着僵留记录,
#   `kill -0` 因此返回成功 → 锁被判为「活的」, 后续部署全被挡, 当晚三次要人工核实再手删。
#
# ⚠️ 修法有个坑, 第一版就踩了: lock 里写的是 `$$` (<b>MSYS PID</b>), 而 Windows 的
#   Get-Process 用的是 <b>Windows PID</b>, 两个命名空间不同 —— 拿 MSYS PID 去 Get-Process
#   必然查不到, 于是<b>把活着的部署判成死的并清掉它的锁</b> = 并发部署互相覆盖,
#   比原问题严重得多。所以这里写进锁文件的是 winpid (/proc/<pid>/winpid)。
#
# 判据方向<b>刻意保守</b>: 任何一步查不确定, 都当「活着」(挡住), 宁可让人手删,
#   也不能误清一把真锁。
deploy_lock_pid() {
    # 优先写 Windows PID; 拿不到就退回 MSYS PID (非 Windows 环境本就一致)
    cat "/proc/$$/winpid" 2>/dev/null || echo $$
}

deploy_pid_alive() {
    local pid="$1"
    [ -n "$pid" ] || return 1

    case "$(uname -s 2>/dev/null)" in
        MINGW*|MSYS*|CYGWIN*)
            # Windows: 只认 Get-Process。查不了(没有 powershell)就保守当活着。
            command -v powershell.exe >/dev/null 2>&1 || return 0
            powershell.exe -NoProfile -Command                 "if (Get-Process -Id $pid -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"                 >/dev/null 2>&1
            return $?
            ;;
        *) kill -0 "$pid" 2>/dev/null ;;
    esac
}

# deploy_pid_cmdline <pid>
# 尽力而为取一条给人看的「这是谁的进程」线索(可执行路径/命令行), 只用于日志,
# 取不到就是 "unknown" —— 不让这条辅助信息拖累主判据 (deploy_pid_alive 才是判据)。
deploy_pid_cmdline() {
    local pid="$1"
    [ -n "$pid" ] || { echo "unknown"; return; }

    case "$(uname -s 2>/dev/null)" in
        MINGW*|MSYS*|CYGWIN*)
            if command -v powershell.exe >/dev/null 2>&1; then
                local out
                out=$(powershell.exe -NoProfile -Command                     "(Get-Process -Id $pid -ErrorAction SilentlyContinue).Path"                     2>/dev/null | tr -d '\r')
                if [ -n "$out" ]; then
                    echo "$out"
                    return
                fi
            fi
            echo "unknown"
            ;;
        *)
            if [ -r "/proc/$pid/cmdline" ]; then
                local out
                out=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)
                if [ -n "$out" ]; then
                    echo "$out"
                    return
                fi
            fi
            if command -v ps >/dev/null 2>&1; then
                ps -o command= -p "$pid" 2>/dev/null && return
            fi
            echo "unknown"
            ;;
    esac
}

# acquire_deploy_lock 成功获取锁后把锁文件路径写进这个全局变量, 供调用方自己的
# EXIT trap 兜底清理用 —— 见下面 "调用方必须自己兜底" 那段注释, 这不是可选项。
ACQUIRED_DEPLOY_LOCK_FILE=""

acquire_deploy_lock() {
    local lock_name="${1:-cretas-deploy}"
    local lock_file="/tmp/${lock_name}.lock"
    ACQUIRED_DEPLOY_LOCK_FILE=""

    # 自我重入: release-cretas.sh 的 drift-retry 用 `exec bash "$0" ...` 重新跑
    # 自己 (main_guard 期间 origin/main 前进了), PID 不变但脚本从头重新执行, 会
    # 再次跑到这里。lock 文件里此刻已经是【我自己】的 PID —— 这不是"另一个进程
    # 持锁", 是重入, 必须放行, 否则会把自己当成别的持锁者拒绝, 整次发布在 main
    # 前进的那一刻自杀。
    #
    # 🔴 光比 PID 不够: PID 是操作系统回收再分配的。一次崩溃的 release 会留下
    # 死锁文件(记着它当时的 PID); 之后系统把那个 PID 重新派给一个**全新、无关**
    # 的 release 进程, 纯属巧合命中同一个数字。如果判据只看"文件里的 PID == 我
    # 的 PID", 这个全新进程会被误判成"重入", 直接 return 0 —— **完全跳过 flock/
    # PID 文件那一层真正的互斥**, 于是第三个进程还能同时拿到锁, 两个 deploy 并发
    # 上传同一份共享 jar/dist (5/30 事故的形态)。
    #
    # 判据因此是合取, 缺一不可:
    #   1. 锁文件里的 PID == 我的 PID (原有判据, 单靠它不够)
    #   2. $CRETAS_DEPLOY_REENTRY_PID == 我的 PID —— 这个环境变量只在
    #      recover_from_main_drift() 真正 `exec bash "$0" ...` 之前才会被设置成
    #      "重新执行前那个进程自己的 PID"; exec 保留环境, 所以真重入时这个变量
    #      会原样带过来并且值等于重入后的 $$ (同一个 OS 进程, PID 不变)。
    #      PID 回收撞上的全新进程从来没经过这条 exec 路径, 环境里根本没有这个
    #      变量 —— 合取因此为假, 落回下面正常的 stale 回收 + flock/PID 文件判据,
    #      不会被这个巧合的 PID 数字骗过去。
    #      ⚠️ 两个判据故意用了两套 PID 表示: 条件 1 比的是 $my_pid(winpid 优先,
    #      与锁文件里存的、deploy_lock_pid() 写入的值同一表示); 条件 2 比的是
    #      裸 $$(MSYS PID, 与 release-cretas.sh 设置时用的同一表示)。只要两处
    #      各自的表示分别自洽(写入和读出用同一套), 合取本身不需要两个表示相同。
    local my_pid
    my_pid=$(deploy_lock_pid)
    if [ -f "$lock_file" ] \
        && [ "$(cat "$lock_file" 2>/dev/null)" = "$my_pid" ] \
        && [ "${CRETAS_DEPLOY_REENTRY_PID:-}" = "$$" ]; then
        log "DEBUG" "已持有锁 (自我重入, PID $my_pid): $lock_file"
        ACQUIRED_DEPLOY_LOCK_FILE="$lock_file"
        return 0
    fi

    # Stale-PID precheck (covers both branches below).
    # SIGKILL / Ctrl-C race / harness termination skips the EXIT trap;
    # flock fd auto-release is also brittle on Git Bash for Windows.
    # If the file contains a dead PID, treat as stale and remove.
    if [ -f "$lock_file" ]; then
        local stale_pid stale_mtime
        stale_pid=$(cat "$lock_file" 2>/dev/null)
        if [ -n "$stale_pid" ] && ! deploy_pid_alive "$stale_pid"; then
            stale_mtime=$(stat -c '%y' "$lock_file" 2>/dev/null || echo unknown)
            log "WARN" "stale lock from PID $stale_pid (dead), mtime $stale_mtime — reclaiming ($lock_file)"
            rm -f "$lock_file"
        fi
    fi

    if command -v flock >/dev/null 2>&1; then
        # flock 模式 (POSIX advisory lock, Linux/Mac/Git Bash)
        #
        # 🔴 ⛔ 不能写 `exec 200>"$lock_file"` —— `>` 在**判定之前**就把文件截断了,
        #    持有者的 PID 当场被抹掉。两个后果都实测到过(2026-09-06, ubuntu):
        #      ① 拒绝日志退化成 "PID , cmdline: unknown", 操作者无从判断该不该手删 ——
        #         而「日志要带 cmdline / mtime, 不是光秃秃一个 PID」正是 2026-09-02
        #         那次凌晨事故之后加的要求。
        #      ② 更要命: 下一次调用读到**空** stale_pid, 而 stale 回收那段要求
        #         `[ -n "$stale_pid" ]` ⇒ 整段被跳过。与此同时 flock 还被持有者的
        #         **子进程**按着(子进程继承了 fd 200, 父进程死了它没死), 于是这把锁
        #         **永久卡住**, 后续每一次部署都被拒。CI 里
        #         test-deploy-mutex-coverage.sh 报的 "lock stayed held after its
        #         owner died; deploys would be permanently blocked" 就是这条路径。
        #    ⚠️ Windows Git Bash **没有 flock**, 走下面的 PID 文件分支 —— 所以这个
        #       缺陷在开发机上一直是绿的, 只有 Linux/macOS 上才看得见。
        # `<>` 是 O_RDWR|O_CREAT 且**不截断**: 文件不存在照样建, 已有内容原样保留。
        #
        # 🔴🔴 ⚠️ 这个改法**绕过**了遗留子进程持锁, ⛔ 没有消除它 —— 别把它读成
        #    「已解决」(#635 终审提出, 如实登记):
        #      · flock 挂在**打开文件描述**上, 子进程继承 fd 200 就一起持有它。
        #        父进程死了、子进程(rsync / ssh / mvn 之类)还在, 锁就还在。
        #      · 现在能往下走, 是因为 stale 回收 `rm -f` 了那个**路径**, 随后
        #        `exec 200<>` 建了一个**新 inode**。于是同一时刻有两个持有者:
        #        新持有者按着新 inode 的锁, 而遗留子进程仍按着那个**已被 unlink
        #        的旧 inode** 的锁 —— 两把锁互不可见, 互斥在这一刻是**失效的**。
        #      · 它比原来好, 只是因为原来的形态是「永久卡死, 每次部署都被拒」,
        #        而现在是「持有者已死 + 只剩孤儿子进程」这一个窄窗口里互斥失效。
        #        ⛔ 但那仍然是 5/30 事故的形状(两个 deploy 同时写共享 jar)。
        #
        #    一行修法(**只评估, 本 PR 不改**): bash 没有 FD_CLOEXEC 的内建写法,
        #    但调用方在 fork 出后台任务 / 子 shell 之前可以显式关掉这个 fd:
        #        ( exec 200>&- ; 那个后台任务 ) &
        #    即在 release-cretas.sh / deploy-cretas-parallel.sh 起并行子任务的那几处
        #    加 `200>&-`。为什么不在本 PR 做:
        #      ① 它要改的是**发布编排**的并发路径, 不是锁原语, 与本票的 scope 不同;
        #      ② 漏掉任何一处 fork 点, 读数与「改好了」完全同形(形态 B), 必须先有一条
        #         能把「子进程仍持锁」量出来的断言, 而现在的用例量不到它 ——
        #         test-deploy-mutex-coverage 的第二次获取之所以成功, 走的正是上面那条
        #         「rm 掉旧 inode」的路, 它对遗留子进程持锁**不敏感**;
        #      ③ 先得决定语义: 孤儿子进程还在跑的时候, 到底该放行还是该挡。挡住更安全,
        #         但那正是 2026-09-02 「陈旧锁拒掉健康部署」那次投诉的另一面。
        exec 200<>"$lock_file"
        if ! flock -n 200; then
            local holder_pid holder_cmdline
            holder_pid=$(cat "$lock_file" 2>/dev/null)
            holder_cmdline=$(deploy_pid_cmdline "$holder_pid")
            log "ERROR" "另一个 deploy 进程持有锁 ($lock_file), PID $holder_pid, cmdline: $holder_cmdline. 等它完成后重试."
            log "ERROR" "  或 rm $lock_file 如果确认无其他进程."
            # 尝试显示持有者
            if command -v lsof >/dev/null 2>&1; then
                lsof "$lock_file" 2>/dev/null | tail -5
            fi
            return 1
        fi
        # 写 PID 进 lock 文件, 让下次 precheck 能验证 (flock fd 在 SIGKILL 时不一定释放).
        # ⚠️ 截断挪到**拿到锁之后**: 上面改成不截断的 `<>` 之后, 旧内容比新 PID 长时
        #    会留下尾巴(旧 1234567 + 新 999 = "9994567"), 而那串垃圾会被下一次
        #    precheck 当成 PID 去问死活。
        : >"$lock_file"
        deploy_lock_pid >&200
        # fd 200 保持打开, 进程退出时 flock 自动释放
        log "DEBUG" "获取 flock: $lock_file (PID $$)"
        ACQUIRED_DEPLOY_LOCK_FILE="$lock_file"
    else
        # PID 文件模式 (fallback)
        if [ -f "$lock_file" ]; then
            local old_pid old_cmdline old_mtime
            old_pid=$(cat "$lock_file" 2>/dev/null)
            if [ -n "$old_pid" ] && deploy_pid_alive "$old_pid"; then
                old_cmdline=$(deploy_pid_cmdline "$old_pid")
                log "ERROR" "另一个 deploy 进程正在跑 (PID $old_pid, cmdline: $old_cmdline, $lock_file). 退出."
                return 1
            fi
            old_mtime=$(stat -c '%y' "$lock_file" 2>/dev/null || echo unknown)
            log "WARN" "stale lock from PID $old_pid (dead), mtime $old_mtime — reclaiming ($lock_file)"
            rm -f "$lock_file"
        fi
        deploy_lock_pid > "$lock_file"
        # 🔴 这条 trap 只在【调用方之后再也不设自己的 EXIT trap】时才会生效 —— bash
        # 的 trap 不叠加, 后设的会【整个替换】先设的, 不是"追加"。deploy-backend.sh
        # 的 R43 fix 就是因为这个坑踩出来的("trap cleanup 会覆盖 acquire_deploy_lock
        # 注册的 lock cleanup trap, 导致 stale lock leak, 反复出现'另一 deploy 进程在
        # 跑'")。release-cretas.sh / deploy-web-admin.sh 都会在这之后设自己的
        # `trap on_exit EXIT`, 这条 trap 届时【不会执行】——
        # 调用方必须在自己的 EXIT handler 里用 $ACQUIRED_DEPLOY_LOCK_FILE 显式兜底清理,
        # 不能假设这里注册的 trap 还活着。这里仍然注册它, 是为了覆盖"调用方之后不再
        # 设 trap"的场景(例如本文件自己的单测), 而不是承诺它总会跑。
        trap 'rm -f "'"$lock_file"'" 2>/dev/null; true' EXIT
        log "DEBUG" "获取 PID 锁: $lock_file (PID $$)"
        ACQUIRED_DEPLOY_LOCK_FILE="$lock_file"
    fi

    return 0
}

# ==================== 回滚 ====================

# rollback_jar <jar_path>
# 恢复最新备份
rollback_jar() {
    local jar_path="$1"
    local latest_backup
    latest_backup=$(ls -t "${jar_path}.bak."* 2>/dev/null | head -1)

    if [ -z "$latest_backup" ]; then
        log "ERROR" "无可用备份: ${jar_path}.bak.*"
        return 1
    fi

    log "INFO" "回滚到: $latest_backup"
    cp "$latest_backup" "$jar_path"
    log "INFO" "回滚完成"
    return 0
}

# ============================================================================
# ssh_local_path <path>
# ----------------------------------------------------------------------------
# 把 Windows 盘符路径归一化成 MSYS 形式, 供 rsync / scp 使用。
#
# rsync 和 scp 都按 "host:path" 语法解析参数, 所以 "C:/Users/x.jar" 里的 "C" 会被
# 当成【主机名】—— rsync 报 remote→remote 不支持, scp 去连一台叫 "C" 的机器。传输
# 直接失败。
#
# Git Bash 下 $HOME 和 $(cd .. && pwd) 天然给 MSYS 形式 (/c/...), 所以默认路径不会
# 踩到。但只要有环境变量 (CRETAS_JAR_CACHE_DIR 等) 或外部调用方传进 Windows 风格
# 路径就会中招 —— 而且过去是【静默】的: 上传竞速里另一条通道会赢, 操作者只看到
# "胜出: scp" 和一次变慢的部署, 没有任何迹象表明主通道其实死了。
#
# 实现注意 (两个坑都实测踩过):
#   1. 用两个独立模式而不是 [/\\] 字符组 —— bash glob 的 bracket 表达式对反斜杠的
#      处理有歧义, 实测 [A-Za-z]:[/\\]* 连 "C:/..." 都匹配不到, 而 [A-Za-z]:/* 可以。
#   2. 用 [[:alpha:]] / [:upper:] 而不是 [A-Za-z] / A-Z 范围 —— 范围表达式按 locale
#      collation 展开, en_US.UTF-8 下会把小写字母也覆盖进去 (本仓库 release
#      preflight 曾因此假报 "project import cannot be resolved")。
ssh_local_path() {
    local p=$1 drive rest
    case "$p" in
        [[:alpha:]]:/*|[[:alpha:]]:\\*)
            drive=$(printf '%s' "${p%%:*}" | tr '[:upper:]' '[:lower:]')
            rest=${p#*:}
            rest=${rest#/}
            rest=${rest#\\}
            rest=$(printf '%s' "$rest" | tr '\\' '/')
            printf '/%s/%s\n' "$drive" "$rest"
            ;;
        *)
            printf '%s\n' "$p"
            ;;
    esac
}
