#!/usr/bin/env bash
# Exercise the real installed unit's sandbox before registering or listening.
set -euo pipefail
[[ $(id -u) == 0 && ! -e /opt/cretas-ci-light/.runner ]] || exit 2
[[ -f /etc/systemd/system/cretas-ci-light.service ]] || exit 2
override_dir=/run/systemd/system/cretas-ci-light.service.d
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
ExecStart=/etc/cretas-ci-light/job-started.sh
Restart=no
EOF
systemctl daemon-reload
systemctl start cretas-ci-light.service
[[ $(systemctl show cretas-ci-light.service -p ExecMainStatus --value) == 0 ]]
! systemctl is-active --quiet cretas-ci-light.service
systemctl show cretas-ci-light.service -p User -p CPUQuotaPerSecUSec -p MemoryHigh -p MemoryMax -p MemorySwapMax -p ProtectHome -p NoNewPrivileges -p ExecMainStatus
journalctl -u cretas-ci-light.service --since '-2 min' --no-pager -o cat | grep '^LIGHT_PREFLIGHT='
! systemctl is-active --quiet cretas-ci.service
[[ $(systemctl is-enabled cretas-ci.service) == disabled ]]
echo 'LIGHT_SANDBOX_PREFLIGHT=pass OLD_HEAVY_CI=inactive_disabled'
