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

# deploy_lock_holders <lock_file>
# 列出所有把 <lock_file> 打开着的进程, **每行一个** `pid:cmdline` (cmdline 截到 160 字符 ——
# 要够看见 rsync/ssh 的目标主机与路径, 操作者凭它判断「这个孤儿还在写生产制品吗」)。
# ⛔ 不只看锁文件里登记的那个 PID: 持有者死后, 按着锁的是它的孤儿子进程, 它们的 pid
# 只在 /proc/*/fd 里能找到。Linux 扫 /proc (锁文件若已被 unlink, readlink 会带
# " (deleted)" 后缀, 同样算); 没有 /proc 时退回 lsof (只有 pid, cmdline 记 unknown)。
deploy_lock_holders() {
    local lock_file="$1" fd target pid out="" seen=" "
    [ -n "$lock_file" ] || return 0
    if [ -d /proc/self/fd ]; then
        for fd in /proc/[0-9]*/fd/*; do
            target=$(readlink "$fd" 2>/dev/null) || continue
            case "$target" in
                "$lock_file"|"$lock_file (deleted)")
                    pid=${fd#/proc/}; pid=${pid%%/*}
                    # 跳过自己: 本函数几乎总在 $(…) 里被调, 那个子 shell 也继承了 fd 200 ——
                    # $$ 是主 shell, $BASHPID 才是子 shell 自己 (2026-09-06 实测漏掉过后者)。
                    [ "$pid" = "$$" ] && continue
                    [ "$pid" = "${BASHPID:-}" ] && continue
                    case "$seen" in *" $pid "*) continue ;; esac
                    seen="$seen$pid "
                    out="${out:+$out
}$pid:$(deploy_pid_cmdline "$pid" | cut -c1-160)"
                    ;;
            esac
        done
    elif command -v lsof >/dev/null 2>&1; then
        out=$(lsof -t "$lock_file" 2>/dev/null | sed 's/$/:unknown/')
    fi
    printf '%s' "$out"
}

# acquire_deploy_lock 成功获取锁后把锁文件路径写进这个全局变量, 供调用方自己的
# EXIT trap 兜底清理用 —— 见下面 "调用方必须自己兜底" 那段注释, 这不是可选项。
# ⚠️ 兜底要调 release_deploy_lock, ⛔ 不要自己 `rm -f` 这个路径 (见该函数注释)。
ACQUIRED_DEPLOY_LOCK_FILE=""
# 与之配对: 这把锁是怎么拿到的 —— flock | pidfile。释放动作按它分叉。
ACQUIRED_DEPLOY_LOCK_MODE=""
# 获取那一刻锁文件的 inode。释放时拿它比一次, 分得出「路径还是我们那个文件」与
# 「路径被换掉了」—— 见 release_deploy_lock 里为什么不能无条件按路径截断。
ACQUIRED_DEPLOY_LOCK_INODE=""

# deploy_file_inode <path>
# 可移植取 inode: GNU coreutils 是 `stat -c %i`, BSD/macOS 是 `stat -f %i`。
# 取不到返回空串 —— 调用方按「没量到」处理, ⛔ 不要把空串当成「相同」。
deploy_file_inode() {
    local p="${1:-}"
    [ -n "$p" ] || return 0
    stat -c %i "$p" 2>/dev/null || stat -f %i "$p" 2>/dev/null || true
}

# release_deploy_lock
# 调用方 EXIT trap 里的清锁兜底。三个调用方 (release-cretas.sh / deploy-backend.sh /
# deploy-web-admin.sh) 都在 acquire 之后再设自己的 EXIT trap, 而 bash 的 trap 不叠加 ——
# acquire 里注册的那条不会跑, 必须由它们显式调这里 (R43)。
# 🔴 2026-09-06 起按模式分叉, ⛔ 不再 `rm -f` 路径:
#   · flock 模式: 先把登记的 PID 截掉 (正常释放不是 stale, 下一次不该报 reclaiming),
#     再只关 fd 200。内核在**最后一个**持有 fd 的进程退出时放锁 —— 若我们的子进程
#     (rsync/ssh/mvn) 还没退, 锁**应该**留在它们手里, 那正是下一次 acquire 要挡的。
#     `rm -f` 会把路径摘掉: 下一次 acquire `exec 200<>` 建的是**新 inode**, 孤儿按着旧的,
#     两把锁互不可见 = 互斥失效 (test-deploy-lock-orphan-holder.sh 的 D 读数就是这个窗口)。
#   · PID 文件模式 (没有 flock, Windows Git Bash): 文件本身就是锁, 必须 rm。
#   · 没拿到锁 (被拒 / 还没 acquire) ⇒ 什么都不做。⛔ 旧写法 `rm -f "${ACQUIRED:-/tmp/x.lock}"`
#     在**被拒那次**退出时会把别人正持有的锁文件删掉 (同一测试的 E 读数)。
release_deploy_lock() {
    local lock_file="${ACQUIRED_DEPLOY_LOCK_FILE:-}"
    [ -n "$lock_file" ] || return 0
    case "${ACQUIRED_DEPLOY_LOCK_MODE:-pidfile}" in
        flock)
            # 清掉登记的 PID: 正常释放不是 stale, 下一次获取不该报 reclaiming, 拒绝措辞也要
            # 分得出「已正常释放」与「持有者已死」。
            #
            # 要截的是【我们自己那个 inode】, ⛔ 不能无条件按路径截 —— acquire 与 release 之间
            # 那个路径可能已被换掉(灰度期跑旧写法的对端 / 有人手工 rm 又重建), 那样抹掉的是
            # 别人的 PID 戳。互斥不受影响(flock 挂在 fd 200 的 OFD 上), 坏的是**诊断**:
            # 下一次拒绝会说「登记持有者已正常释放」, 而其实有个活着的持有者就坐在那。
            #
            # 🔴 2026-09-07 第二版。前一版写 `: >/dev/fd/200`, **只在 Linux 上成立**:
            #   · Linux: /proc 式的 /dev/fd/N 是指向文件的符号链接, open(O_TRUNC) 真的截断。
            #   · macOS: man 页写明「打开 /dev/fd/N 等价于 dup(N)」⇒ **O_TRUNC 被忽略**。
            #     Mac mini (macOS 26.4 / Darwin 25.4.0) 实测: 8 字节, `: >&200` 之后 8, `: >/dev/fd/200` 之后**仍是 8**,
            #     而且 **open 成功、返回 0** ⇒ `2>/dev/null || true` 那一支不触发 = **静默 no-op**,
            #     rc 检查抓不到。那台机器是自建 CI runner, 也可能有人在 Mac 上本地跑部署脚本。
            #   ⇒ ⛔ 不再依赖 /dev/fd 的平台语义。改成**记 inode + 比一次**, 三种结果各有各的读数:
            #     inode 相同 ⇒ 按路径截断(所有平台都成立); inode 变了 / 文件没了 ⇒ **不截断并 WARN**
            #     (⚠️ 承重理由是「**对下一次 acquire 没有影响**」——路径已经不指向我们那个 inode,
            #      下一次 `exec 200<>` 开的是现在占着这个路径的那个文件, 碰不到我们这份。
            #      ⛔ 别把理由写成「我们那个 inode 会随 fd 关闭被内核释放」: 那只在**被 unlink**
            #      时成立; 测试实际构造的是 `mv`(rename), inode 带着旧名字和旧 PID 戳活得好好的
            #      (F2 实测它仍是 5 字节)。结论不变, 但理由要准 —— #706 终审 ②。
            #      而抹掉别人的 PID 戳是实打实的害处)。
            local cur_ino
            cur_ino=$(deploy_file_inode "$lock_file")
            if [ -n "${ACQUIRED_DEPLOY_LOCK_INODE:-}" ] && [ "$cur_ino" = "$ACQUIRED_DEPLOY_LOCK_INODE" ]; then
                : >"$lock_file" 2>/dev/null || true
                # 🔴 INFO 不是 DEBUG: 三个调用方一个都没抬过 LOG_LEVEL(默认 INFO), 写 DEBUG 等于
                # **在每一次真实部署里都把这条读数抑制掉** —— 那样本函数声称的「说得出自己做了什么」
                # 在最常见的那条路径上根本不成立(#706 终审 F-1)。两条分支必须同样看得见。
                log "INFO" "释放锁: 已清空登记 PID ($lock_file, inode $cur_ino)"
            else
                log "WARN" "释放锁: $lock_file 现在是 inode ${cur_ino:-<不存在>}, 与获取时的 ${ACQUIRED_DEPLOY_LOCK_INODE:-<未记录>} 不同 —— ⛔ 不截断(那会抹掉别人的 PID 戳)。我们那个 inode 关掉 fd 就由内核释放, 不影响下一次获取."
            fi
            { exec 200>&-; } 2>/dev/null || true
            ;;
        *)
            rm -f "$lock_file" 2>/dev/null || true
            ;;
    esac
    ACQUIRED_DEPLOY_LOCK_FILE=""
    ACQUIRED_DEPLOY_LOCK_MODE=""
    ACQUIRED_DEPLOY_LOCK_INODE=""
    return 0
}

acquire_deploy_lock() {
    local lock_name="${1:-cretas-deploy}"
    local lock_file="/tmp/${lock_name}.lock"
    ACQUIRED_DEPLOY_LOCK_FILE=""
    ACQUIRED_DEPLOY_LOCK_INODE=""

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
        # exec 保留 fd 200, 所以重入后锁的模式与第一次相同: 有 flock 就是 flock 模式。
        if command -v flock >/dev/null 2>&1; then ACQUIRED_DEPLOY_LOCK_MODE=flock; else ACQUIRED_DEPLOY_LOCK_MODE=pidfile; fi
        # 重入是 exec 同一个进程, 全局变量已被新一轮脚本执行重置 ⇒ 这里重新记一次 inode,
        # 否则释放时会拿空值去比, 落进「不截断」那一支(读数变成一条无谓的 WARN)。
        ACQUIRED_DEPLOY_LOCK_INODE=$(deploy_file_inode "$lock_file")
        return 0
    fi

    # Stale-PID precheck —— ⚠️ 2026-09-06 起**只给 PID 文件分支**用 (见下)。
    # SIGKILL / Ctrl-C race / harness termination skips the EXIT trap;
    # flock fd auto-release is also brittle on Git Bash for Windows.
    # If the file contains a dead PID, treat as stale and remove.
    #
    # 🔴 flock 分支**不再**走这段 rm: 「PID 已死」不等于「没人按着锁」—— 死掉的持有者
    #    fork 出去的子进程 (ssh / rsync / mvn / 后台 CI fetch) 继承了 fd 200, 锁还在它们
    #    手里。这里 rm 掉路径之后再 `exec 200<>` 建的是**新 inode**, 于是新部署和孤儿
    #    各按一把互不可见的锁 —— 互斥在那个窗口是失效的 (5/30 事故的形状)。
    #    test-deploy-lock-orphan-holder.sh 在 2026-09-06 把这个窗口量了出来:
    #    `second_acquire_rc=0 orphan_fd200_after=<lock> (deleted)`。
    #    flock 分支改成: **先** flock, 拿不到再看持有者是活是死 —— 内核比 PID 文件更知道
    #    谁还按着锁; 持有者死而无子进程时 flock 本来就能拿到, 不需要 rm。
    if ! command -v flock >/dev/null 2>&1 && [ -f "$lock_file" ]; then
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
        # 🔴 遗留子进程持锁 —— 2026-09-06 (brief 卡 C 任务 3) 起的语义: **挡**。
        #      · flock 挂在**打开文件描述**上, 子进程继承 fd 200 就一起持有它。
        #        父进程死了、子进程(rsync / ssh / mvn / 后台 CI fetch)还在, 锁就还在。
        #      · 2026-09-06 之前这里先 `rm -f` 路径再 `exec 200<>` 建**新 inode**, 于是新部署
        #        与孤儿各按一把互不可见的锁 —— test-deploy-lock-orphan-holder.sh 量出来的读数:
        #        `second_acquire_rc=0 orphan_fd200_after=<lock> (deleted)`, 就是 5/30 事故的形状。
        #      · 现在**先 flock**: 拿不到就看登记的 PID 活不活 —— 活着是「另一个 deploy」,
        #        死了是「孤儿还按着」, 后者**拒绝**并点名 fd 200 的持有者(deploy_lock_holders),
        #        让操作者决定等还是杀; 孤儿一退出内核就放锁, 不会永久卡死 (同一测试的第 ②③ 条)。
        #      · ⛔ 没走「在 fork 点加 `200>&-`」那条路: 前台子进程 (deploy-backend.sh / ssh /
        #        rsync) 同样继承 fd 200, 只关后台那几处会让读数「时通时不通」(形态 B);
        #        而挡住一个可能还在写共享 jar 的孤儿 rsync, 正是这把锁存在的理由。
        #        代价如实登记: 死掉的 release 若留下一个**挂住**的孤儿, 下一次部署会被拒,
        #        直到有人按拒绝信息里的 pid 把它杀掉 —— 这是 2026-09-02「陈旧锁拒掉健康部署」
        #        的另一面, 但拒绝信息现在会点名到进程, 不再是光秃秃一个死 PID。
        exec 200<>"$lock_file"
        # 🔴 inode 必须在**紧挨着 open 之后**记, ⛔ 不能等到写完 PID 才记(#706 终审 F-2):
        #    open 与「记 inode」之间的每一行都是窗口 —— 路径若在那段里被换掉, 记下的就是
        #    **冒名者的** inode, 于是 release 会截断冒名者并打印「已清空」, 正好是这道守卫
        #    要防的那件事的**静默假阴性**。记在这里把窗口压到 open→stat, 并且顺带罩住下面
        #    那句按路径的 `: >"$lock_file"`(它若截到冒名者, 这里记的 inode 会与之不符)。
        # ⚠️ 这时还不知道 flock 抢不抢得到; 抢不到时 ACQUIRED_DEPLOY_LOCK_FILE 不会被设,
        #    release_deploy_lock 一上来就 return, 这个值不会被用到。
        ACQUIRED_DEPLOY_LOCK_INODE=$(deploy_file_inode "$lock_file")
        if ! flock -n 200; then
            # 拿不到 = **有人按着**。分两种, 措辞与处置都不同 (⛔ 不折叠):
            #   a) 锁文件里的 PID 还活着 —— 另一个 deploy 正在跑, 等它。
            #   b) 那个 PID 已死, 锁却还被按着 —— 是它的**孤儿子进程** (ssh/rsync/mvn/CI fetch)
            #      继承了 fd 200 还没退出。这时候放行 = 与一个可能还在写共享 jar 的 rsync 并发
            #      (5/30 形状); 所以**挡**, 并把按着锁的 pid 逐个点名 —— 操作者决定等还是杀。
            #      ⛔ 不 rm 锁文件: 那只会造出第二把互不可见的锁 (见上)。
            local holder_pid holder_cmdline holders holder_pids holder_line orphaned=0
            holder_pid=$(cat "$lock_file" 2>/dev/null | tr -d '[:space:]')
            holders=$(deploy_lock_holders "$lock_file")
            holder_pids=$(printf '%s\n' "$holders" | cut -d: -f1 | tr '\n' ' ')
            holder_pids=${holder_pids% }
            if [ -n "$holder_pid" ] && deploy_pid_alive "$holder_pid"; then
                holder_cmdline=$(deploy_pid_cmdline "$holder_pid")
                log "ERROR" "另一个 deploy 进程持有锁 ($lock_file), PID $holder_pid, cmdline: $holder_cmdline. 等它完成后重试."
            elif [ -n "$holder_pid" ]; then
                orphaned=1
                log "ERROR" "锁 ($lock_file) 的登记持有者 PID $holder_pid 已死, 但锁仍被按着 —— 是它的孤儿子进程还没退出 (继承了 fd 200)."
            else
                orphaned=1
                log "ERROR" "锁 ($lock_file) 的登记持有者已正常释放, 但锁仍被按着 —— 是它留下的孤儿子进程 (继承了 fd 200) 还没退出."
            fi
            if [ -n "$holders" ]; then
                log "ERROR" "  按着锁 (fd 200) 的进程 (pid:cmdline):"
                while IFS= read -r holder_line; do
                    [ -n "$holder_line" ] && log "ERROR" "    $holder_line"
                done <<<"$holders"
            else
                log "ERROR" "  按着锁 (fd 200) 的进程: <扫不到, 试 lsof $lock_file>"
            fi
            if [ "$orphaned" -eq 1 ]; then
                # 逃生路要能直接复制: 上面每行 cmdline 里就有 rsync/ssh 的目标, 先看它们还在不在写
                # 生产制品; 都不在了就 kill; 复核那行全部退出后直接重发, ⛔ 不 rm 锁文件。
                if [ -n "$holder_pids" ]; then
                    local holder_pids_csv
                    holder_pids_csv=$(printf '%s' "$holder_pids" | tr ' ' ',')
                    # ⚠️ pid 会被系统回收再分配。上面那份 cmdline 是【拒绝这一刻】拍的快照, 而运维
                    # 往往几分钟后才来粘贴 —— 那时同一个数字可能已经是别的进程(2026-09-02 的陈旧锁
                    # 事故就是同一族: 拿一个不再成立的读数当真)。所以 kill 之前先确认一次。
                    log "ERROR" "  处置 —— ⚠️ pid 会被回收再分配, 上面是拒绝那一刻的快照。**先确认再杀**:"
                    log "ERROR" "    ps -o pid,args -p $holder_pids_csv"
                    log "ERROR" "  确认 cmdline 仍是那几个孤儿(rsync/ssh/mvn)且不再写生产制品之后:"
                    log "ERROR" "    kill $holder_pids"
                    log "ERROR" "  复核 (打印 checked 且没有 'still alive' 就直接重发; ⛔ 不要 rm $lock_file —— 那会造出第二把互不可见的锁):"
                    log "ERROR" "    for p in $holder_pids; do kill -0 \$p 2>/dev/null && echo \"still alive: \$p\"; done; echo checked"
                else
                    log "ERROR" "  处置 —— 先找到按着锁的进程: lsof $lock_file ; 确认它们不再写生产制品后 kill; ⛔ 不要 rm 锁文件 —— 那会造出第二把互不可见的锁."
                fi
            fi
            # ⛔ 失败要把自己这个 fd 关掉 —— 否则我们自己的子进程也会带着一个多余的锁 fd 出去。
            exec 200>&-
            return 1
        fi
        # flock 拿到了。文件里若还留着上一个(已死的)持有者的 PID, 那是它 SIGKILL 时没清掉的
        # 尾巴 —— 内核已经把锁释放了, 这里只是把接管**记在日志里**(带 mtime, 给排障看),
        # ⛔ 不 rm 路径 (见上: rm 会造出第二把互不可见的锁; 内核释放了就不需要 rm)。
        local prev_pid prev_mtime
        prev_pid=$(cat "$lock_file" 2>/dev/null | tr -d '[:space:]')
        # ⚠️ 不排除 prev_pid == my_pid: 那正是 A5 (PID 回收撞库) 的形状 —— 文件里的数字碰巧等于我,
        #    但它已死、我也没有重入凭证, 这是接管一把死锁, 照样要记 reclaiming。
        if [ -n "$prev_pid" ] && ! deploy_pid_alive "$prev_pid"; then
            prev_mtime=$(stat -c '%y' "$lock_file" 2>/dev/null || echo unknown)
            log "WARN" "stale lock from PID $prev_pid (dead), mtime $prev_mtime — reclaiming ($lock_file, flock released by kernel)"
        fi
        # 写 PID 进 lock 文件 —— ⛔ 它不是互斥判据 (互斥是内核的 flock, SIGKILL 也会释放),
        # 只给三件事用: ① 拒绝信息里点名「登记持有者是谁、活没活」; ② 拿到锁时判要不要记一条
        # reclaiming 日志; ③ release-cretas.sh 的自我重入判据 (文件里的 PID == 我 + 重入凭证)。
        # ⚠️ 截断挪到**拿到锁之后**: 上面改成不截断的 `<>` 之后, 旧内容比新 PID 长时
        #    会留下尾巴(旧 1234567 + 新 999 = "9994567"), 而那串垃圾会被上面那段
        #    prev_pid 判活当成 PID 去问死活。
        : >"$lock_file"
        deploy_lock_pid >&200
        # fd 200 保持打开; 正常退出走 release_deploy_lock (只关 fd), 被杀时内核自动释放。
        log "DEBUG" "获取 flock: $lock_file (PID $$)"
        ACQUIRED_DEPLOY_LOCK_FILE="$lock_file"
        ACQUIRED_DEPLOY_LOCK_MODE=flock
        # (inode 已在 `exec 200<>` 紧后面记过 —— 见那里的注释: 记晚了会记成冒名者的。)
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
        ACQUIRED_DEPLOY_LOCK_MODE=pidfile
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
