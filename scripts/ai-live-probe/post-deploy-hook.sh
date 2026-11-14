# shellcheck shell=bash
# 上线后自动实测关的发布钩子 —— 由 scripts/deploy/release-cretas.sh (Java) 与
# scripts/deploy/deploy-smartbi-python.sh (Python) 在「部署成功之后」source 并调用。
#
# 开关 (环境变量 AI_LIVE_PROBE):
#   report  (默认) 跑一遍 scripts/ai-live-probe/ai_live_probe.py, 打印结果与结果文件路径,
#           ⛔ 不改变发布脚本的退出码 —— 先攒读数, 不阻断。
#   enforce        同上; 实测 rc≠0 时打印报警 + 回滚提示, 函数返回非 0,
#           调用方据此以退出码 3 结束 (部署已发生, 但线上实测没过 —— 需要回滚或修)。
#   off            不跑 (打印一行 AI_LIVE_PROBE_GATE=skipped 留痕)。
# 透传给套件的变量: AI_PROBE_BASE / AI_PROBE_SERVER / AI_PROBE_ENV_FILE / AI_PROBE_OUT_DIR
#   以及凭证 AI_PROBE_READ_* / AI_PROBE_WRITE_* (见 ai_live_probe.py 顶部说明; ⛔ 不打印)。
#   AI_LIVE_PROBE_ARGS: 追加给套件的参数 (例如 "--no-write")。
#   AI_LIVE_PROBE_TIMEOUT: 整体超时秒数 (默认 600)。钩子跑在 release-cretas.sh 释放发布锁之前,
#           挂住会挡住后面所有发布 ⇒ 有 timeout 命令就用它硬杀; 套件自己也有同值的 deadline
#           (Windows Git Bash 可能没有 timeout)。超时 (124/137) 一律记 rc=2 没量到。
#
# ⚠️ release-cretas.sh 下 enforce 失败的读法: 终端已打 RELEASE_FINAL_STATUS=deployed (部署本身成了),
#    随后 exit 3, on_exit 把 release 报告 JSON 的 status 写成 failed。前者 = 部署发生了,
#    后者 = 整条发布没过关; 判据看 DEPLOY_EXIT=3 + AI_LIVE_PROBE_GATE。
#
# 回执行 (grep 用): AI_LIVE_PROBE_GATE=passed|failed|not-measured|skipped|error
#                   AI_LIVE_PROBE_RC=<0|1|2>   AI_LIVE_PROBE_RESULT=<结果文件>

_ai_live_probe_python() {
    # Windows 上 python3 可能是商店占位程序 (能 command -v 到, 一跑就 9009) —— 真跑一下再用。
    local candidate
    for candidate in python3 python py; do
        if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# 用法: ai_live_probe_after_deploy <component: java|python> [expected_python_release_commit]
ai_live_probe_after_deploy() {
    local component="${1:-unknown}" expect_commit="${2:-}"
    local mode="${AI_LIVE_PROBE:-report}"
    case "$mode" in
        off|0|false|no)
            printf 'AI_LIVE_PROBE_GATE=skipped (AI_LIVE_PROBE=%s)\n' "$mode"
            return 0
            ;;
        report|enforce) ;;
        *)
            printf 'AI_LIVE_PROBE=%s 不认识 (off|report|enforce), 按 report 跑\n' "$mode" >&2
            mode=report
            ;;
    esac

    local here py rc=0
    here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    printf '\n========== 上线后自动实测关 (%s, mode=%s) ==========\n' "$component" "$mode"
    if ! py="$(_ai_live_probe_python)"; then
        printf 'AI_LIVE_PROBE_GATE=error (本机没有可用的 Python 3.8+)\n'
        rc=2
    else
        local -a extra=()
        if [ -n "${AI_LIVE_PROBE_ARGS:-}" ]; then
            # shellcheck disable=SC2206 # 有意按空白拆分成多个参数
            extra=(${AI_LIVE_PROBE_ARGS})
        fi
        if [ -n "$expect_commit" ]; then
            extra+=(--expect-python-commit "$expect_commit")
        fi
        local limit="${AI_LIVE_PROBE_TIMEOUT:-600}"
        local -a guard=()
        if command -v timeout >/dev/null 2>&1; then
            guard=(timeout -k 15 "$limit")
        fi
        # 套件内 deadline 比硬超时早 30s, 让它自己收尾 (清理 QA-PROBE 行、写 summary) 再退
        ${guard[@]+"${guard[@]}"} "$py" -X utf8 "$here/ai_live_probe.py" --trigger "post-deploy-$component" \
            --deadline-seconds "$(( limit > 60 ? limit - 30 : limit ))" ${extra[@]+"${extra[@]}"} </dev/null || rc=$?
        if [ "$rc" = 124 ] || [ "$rc" = 137 ]; then
            printf 'AI_LIVE_PROBE_TIMEOUT_HIT=%ss (rc=%s) —— 记为没量到\n' "$limit" "$rc"
            rc=2
        fi
    fi

    case "$rc" in
        0) printf 'AI_LIVE_PROBE_GATE=passed\n' ;;
        2) printf 'AI_LIVE_PROBE_GATE=not-measured\n' ;;
        *) printf 'AI_LIVE_PROBE_GATE=failed\n' ;;
    esac
    if [ "$rc" -ne 0 ]; then
        {
            printf '\n🚨 上线后实测关没过 (rc=%s: %s)。部署已经发生, 线上行为与预期不符或没量到。\n' \
                "$rc" "$([ "$rc" = 2 ] && echo '没量到' || echo '有失败')"
            printf '   看结果文件 (AI_LIVE_PROBE_RESULT=...) 里 result=FAIL 的条目与 summary.reason。\n'
            case "$component" in
                java)
                    printf '   回滚 Java: ./scripts/deploy/deploy-backend.sh --rollback (切回上一备份, 蓝绿)\n' ;;
                python)
                    printf '   回滚 Python: 没有一键代码回滚 —— revert 出问题的 PR 合进 main, 再跑 deploy-smartbi-python.sh\n' ;;
            esac
            printf '   (mode=%s: %s)\n' "$mode" \
                "$([ "$mode" = enforce ] && echo '本次发布将以退出码 3 结束' || echo '只报告, 不改变发布退出码; 设 AI_LIVE_PROBE=enforce 改为失败即报警退出')"
        } >&2
        if [ "$mode" = enforce ]; then
            return "$rc"
        fi
    fi
    return 0
}
