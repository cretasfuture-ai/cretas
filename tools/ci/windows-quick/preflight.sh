#!/usr/bin/env bash
# Exercise the real installed unit's sandbox before registering or listening.
set -euo pipefail
[[ $(id -u) == 0 && ! -e /opt/cretas-ci-quick/.runner ]] || exit 2
[[ -f /etc/systemd/system/cretas-ci-quick.service ]] || exit 2
! systemctl is-active --quiet cretas-ci-quick.service || { echo 'Active listener; refuse preflight changes'; exit 1; }
override_dir=/run/systemd/system/cretas-ci-quick.service.d
[[ ! -e "$override_dir" ]] || { echo 'Existing runtime override; refuse overwrite'; exit 1; }
install -d -m 0755 "$override_dir"
cleanup() {
  rm -f "$override_dir/00-preflight.conf"
  rmdir "$override_dir"
  systemctl daemon-reload
}
trap cleanup EXIT
cat > "$override_dir/00-preflight.conf" <<'EOF'
[Service]
Type=oneshot
ExecStart=
ExecStart=/etc/cretas-ci-quick/job-started.sh
Restart=no
EOF
systemctl daemon-reload
systemctl start cretas-ci-quick.service
[[ $(systemctl show cretas-ci-quick.service -p ExecMainStatus --value) == 0 ]]
! systemctl is-active --quiet cretas-ci-quick.service
systemctl show cretas-ci-quick.service -p User -p CPUQuotaPerSecUSec -p MemoryHigh -p MemoryMax -p MemorySwapMax -p TasksMax -p ProtectHome -p NoNewPrivileges -p ExecMainStatus
journalctl -u cretas-ci-quick.service --since '-2 min' --no-pager -o cat | grep '^QUICK_PREFLIGHT='
! systemctl is-active --quiet cretas-ci.service
[[ $(systemctl is-enabled cretas-ci.service) == disabled ]]
systemctl is-active --quiet cretas-ci-light.service
echo 'QUICK_SANDBOX_PREFLIGHT=pass OLD_HEAVY_CI=inactive_disabled LIGHT_CI=still_active'
