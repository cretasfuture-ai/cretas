#!/usr/bin/env bash
# Install only a NEW quick-check worker in the already-running Ubuntu-22.04, next to (not over)
# the lightweight worker. The downloaded runner archive and its expected checksum are explicit
# inputs, never credentials. No Node download: the quick jobs only use git, python3 and bash,
# and actions/checkout runs on the runner's bundled node.
set -euo pipefail
[[ $(id -u) == 0 && $# == 2 ]] || { echo 'usage: install.sh runner.tgz runner_sha256'; exit 2; }
runner_archive=$1; runner_sha=$2
[[ "$runner_sha" =~ ^[a-f0-9]{64}$ ]] || exit 2
[[ $(uname -m) == x86_64 ]] || { echo 'Unsupported architecture'; exit 2; }
for target in /opt/cretas-ci-quick /opt/cretas-ci-quick-tools /etc/cretas-ci-quick /var/lib/cretas-ci-quick /etc/systemd/system/cretas-ci-quick.service; do
  [[ ! -e "$target" && ! -L "$target" ]] || { echo "Existing target; refuse overwrite: $target"; exit 1; }
done
! getent passwd cretas-ci-quick >/dev/null || { echo 'Existing identity; refuse reuse'; exit 1; }
! systemctl is-active --quiet cretas-ci.service || { echo 'Old CI is active; ownership conflict'; exit 1; }
printf '%s  %s\n' "$runner_sha" "$runner_archive" | sha256sum --check --status
[[ -x /usr/local/bin/python3.11 ]] || { echo 'Missing existing prerequisite: /usr/local/bin/python3.11'; exit 1; }
script_dir=$(cd "$(dirname "$0")" && pwd)
useradd --system --user-group --home-dir /var/lib/cretas-ci-quick --create-home --shell /bin/bash cretas-ci-quick
install -d -m 0750 -o cretas-ci-quick -g cretas-ci-quick /opt/cretas-ci-quick /var/lib/cretas-ci-quick/work /var/lib/cretas-ci-quick/toolcache
install -d -m 0755 /etc/cretas-ci-quick /opt/cretas-ci-quick-tools/bin
tar -xzf "$runner_archive" -C /opt/cretas-ci-quick
ln -s /usr/local/bin/python3.11 /opt/cretas-ci-quick-tools/bin/python3
ln -s /usr/local/bin/python3.11 /opt/cretas-ci-quick-tools/bin/python
chown -R cretas-ci-quick:cretas-ci-quick /opt/cretas-ci-quick
install -m 0755 "$script_dir/job-started.sh" /etc/cretas-ci-quick/job-started.sh
install -m 0755 "$script_dir/register.sh" /etc/cretas-ci-quick/register.sh
install -m 0644 "$script_dir/cretas-ci-quick.service" /etc/systemd/system/cretas-ci-quick.service
systemctl daemon-reload
# Deliberately not registered/enabled/started until the isolated unit preflight passes.
echo 'QUICK_INSTALL=prepared NOT_REGISTERED NOT_STARTED'
