#!/bin/bash
# 一次性: 建 ghgate 账户 (无密码 = 不能登录、非管理员、隐藏), 装 runner 包, 撤掉 rex 名下的常驻 runner, 起补位 LaunchAgent。
set -euo pipefail
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
cp /tmp/gh-gate-refill.sh /Users/rex/gh-gate/refill.sh && chmod 755 /Users/rex/gh-gate/refill.sh
for i in 1 2 3 4; do  # 4 槽 (2026-11-18 Steve 要求 4 片并行; 按需起, 空闲不占内存)
  plist=/Users/rex/Library/LaunchAgents/com.cretas.gh-gate-$i.plist
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
