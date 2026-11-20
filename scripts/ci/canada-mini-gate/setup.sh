#!/bin/bash
# 一次性: 建 ghgate 账户 (无密码 = 不能登录、非管理员、隐藏), 装 runner 包, 撤掉 rex 名下的常驻 runner, 起补位 LaunchAgent。
# 用法: setup.sh [槽数]   默认 4, 上限 4 (2026-11-19 21:5x Steve「不跑5个了……这4个槽不要只给pg用」: 共用槽池,
#        每个槽注册全部 mini 标签, 见 refill.sh 的 MAX_SLOTS / MINI_LABELS)。先排空编号大于槽数的旧槽，再执行安装。
set -euo pipefail
PLAN=0
if [ "${1:-}" = --plan-cleanup ]; then PLAN=1; shift; fi
SLOTS="${1:-4}"
case "$SLOTS" in 1|2|3|4) ;; *) echo "槽数须为 1 到 4: $SLOTS" >&2; exit 2 ;; esac
AGENTS_DIR="${GH_GATE_AGENTS_DIR:-/Users/rex/Library/LaunchAgents}"
obsolete_agents() {
  local services path label
  services=$(launchctl list) || return 1
  {
    printf '%s\n' "$services" | awk '{print $3}'
    for path in "$AGENTS_DIR"/com.cretas.gh-gate-*.plist; do
      [ -f "$path" ] || continue
      label=${path##*/}; printf '%s\n' "${label%.plist}"
    done
  } | awk -v limit="$SLOTS" '
    /^com\.cretas\.gh-gate-[1-9][0-9]*$/ && !seen[$0]++ {
      slot=$0; sub(/^.*-/, "", slot); if (slot + 0 > limit) print $0
    }
  '
}
check_drained() {
  local slot="${1##*-}" rc
  if pgrep -f "/Users/ghgate/slot-$slot/bin/Runner\.(Listener|Worker)" >/dev/null; then
    echo "槽 $slot 仍有 Runner.Listener 或 Runner.Worker，先排空再清理" >&2
    return 1
  else
    rc=$?
  fi
  if [ "$rc" != 1 ]; then
    echo "槽 $slot 进程状态读不到，停止清理" >&2
    return "$rc"
  fi
}
OBSOLETE_AGENTS=$(obsolete_agents)
while IFS= read -r obsolete; do
  [ -n "$obsolete" ] || continue
  check_drained "$obsolete"
done <<< "$OBSOLETE_AGENTS"
if [ "$PLAN" = 1 ]; then printf '%s' "$OBSOLETE_AGENTS"; exit 0; fi
export PATH=/opt/homebrew/bin:$PATH
U=ghgate
if ! dscl . -read /Users/$U >/dev/null 2>&1; then
  uid=550; while dscl . -list /Users UniqueID | awk '{print $2}' | grep -qx $uid; do uid=$((uid+1)); done
  sudo dscl . -create /Users/$U
  sudo dscl . -create /Users/$U UniqueID $uid
  # 自己的主组, ⛔ 不用 staff(20): rex 主目录是 750 rex:staff, 用 staff 当主组 ghgate 就能读整个 rex 主目录
  # (2026-11-18 实测: 任务里的隔离读数 rex_home=readable 报红)。
  sudo dscl . -create /Groups/$U; sudo dscl . -create /Groups/$U PrimaryGroupID $uid
  sudo dscl . -create /Users/$U PrimaryGroupID $uid
  sudo dscl . -create /Users/$U UserShell /usr/bin/false
  sudo dscl . -create /Users/$U RealName "GitHub gate runner"
  sudo dscl . -create /Users/$U NFSHomeDirectory /Users/$U
  sudo dscl . -create /Users/$U Password '*'
  sudo dscl . -create /Users/$U IsHidden 1
  sudo mkdir -p /Users/$U && sudo chown $U:$U /Users/$U && sudo chmod 700 /Users/$U
  echo "created $U uid=$uid"
fi
id $U
dseditgroup -o checkmember -m $U admin || true
sudo cp /Users/rex/gh-gate-pkg/r.tgz /Users/$U/r.tgz && sudo chown $U:$U /Users/$U/r.tgz
sudo -u $U -H /bin/bash -c 'mkdir -p ~/runner-pkg && cd ~/runner-pkg && { [ -x run.sh ] || tar xzf ~/r.tgz; } && rm -f ~/r.tgz' < /dev/null
ls -ld /Users/rex /Users/rex/cretas-build

# 撤掉 rex 名下的两台常驻 runner
for i in 1 2; do
  d=/Users/rex/gh-gate-$i
  [ -d "$d" ] || continue
  (cd "$d" && ./svc.sh stop >/dev/null 2>&1 || true; ./svc.sh uninstall >/dev/null 2>&1 || true)
done

mkdir -p /Users/rex/gh-gate
cp /tmp/gh-gate-queue-snapshot.sh /Users/rex/gh-gate/queue-snapshot.sh
cp /tmp/gh-gate-refill.sh /Users/rex/gh-gate/refill.sh && chmod 755 /Users/rex/gh-gate/refill.sh
# 失效合并组清理 (refill.sh beat 每 5 分钟后台调用); 先 scp 到 /tmp/gh-gate-reap.sh
[ -f /tmp/gh-gate-reap.sh ] && cp /tmp/gh-gate-reap.sh /Users/rex/gh-gate/reap-stale-merge-groups.sh && chmod 755 /Users/rex/gh-gate/reap-stale-merge-groups.sh
# 编号大于 $SLOTS 的旧槽 (如 2026-11-19 前的槽 5): 卸 LaunchAgent、删 plist; 它的检出目录 ~ghgate/work-N 留着无害。
while IFS= read -r obsolete; do
  [ -n "$obsolete" ] || continue
  check_drained "$obsolete"
  service="gui/$(id -u)/$obsolete"
  if launchctl print "$service" >/dev/null 2>&1; then launchctl bootout "$service"; fi
  rm -f "$AGENTS_DIR/$obsolete.plist"
done <<< "$OBSOLETE_AGENTS"
for i in $(seq 1 "$SLOTS"); do  # 按需起, 空闲不占内存; 新槽的检出目录 ~ghgate/work-N 由运维从 ~ghgate/seed 预置
  plist="$AGENTS_DIR/com.cretas.gh-gate-$i.plist"
  cat > "$plist" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>com.cretas.gh-gate-$i</string>
<key>ProgramArguments</key><array><string>/Users/rex/gh-gate/refill.sh</string><string>$i</string></array>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
<key>StandardErrorPath</key><string>/Users/rex/gh-gate/launchd-$i.err</string>
</dict></plist>
PL
  launchctl bootout gui/$(id -u)/com.cretas.gh-gate-$i 2>/dev/null || true
  launchctl bootstrap gui/$(id -u) "$plist"
done
launchctl list | grep gh-gate
