#!/usr/bin/env bash
# Install only a NEW lightweight worker in the already-running Ubuntu-22.04.
# Downloaded archives and expected checksums are explicit inputs, never credentials.
set -euo pipefail
[[ $(id -u) == 0 && $# == 4 ]] || { echo 'usage: install.sh runner.tgz runner_sha256 node.tar.xz node_sha256'; exit 2; }
runner_archive=$1; runner_sha=$2; node_archive=$3; node_sha=$4
[[ "$runner_sha" =~ ^[a-f0-9]{64}$ && "$node_sha" =~ ^[a-f0-9]{64}$ ]] || exit 2
[[ $(uname -m) == x86_64 ]] || { echo 'Unsupported architecture'; exit 2; }
for target in /opt/cretas-ci-light /opt/cretas-ci-light-tools /etc/cretas-ci-light /var/lib/cretas-ci-light /etc/systemd/system/cretas-ci-light.service; do
  [[ ! -e "$target" && ! -L "$target" ]] || { echo "Existing target; refuse overwrite: $target"; exit 1; }
done
! getent passwd cretas-ci-light >/dev/null || { echo 'Existing identity; refuse reuse'; exit 1; }
! systemctl is-active --quiet cretas-ci.service || { echo 'Old CI is active; ownership conflict'; exit 1; }
printf '%s  %s\n' "$runner_sha" "$runner_archive" | sha256sum --check --status
printf '%s  %s\n' "$node_sha" "$node_archive" | sha256sum --check --status
for required in /usr/local/bin/python3.11 /usr/lib/postgresql/17/bin/initdb; do
  [[ -x "$required" ]] || { echo "Missing existing prerequisite: $required"; exit 1; }
done
script_dir=$(cd "$(dirname "$0")" && pwd)
useradd --system --user-group --home-dir /var/lib/cretas-ci-light --create-home --shell /bin/bash cretas-ci-light
install -d -m 0750 -o cretas-ci-light -g cretas-ci-light /opt/cretas-ci-light /var/lib/cretas-ci-light/work /var/lib/cretas-ci-light/toolcache
install -d -m 0755 /etc/cretas-ci-light /opt/cretas-ci-light-tools/bin /opt/cretas-ci-light-tools/node
tar -xzf "$runner_archive" -C /opt/cretas-ci-light
tar -xJf "$node_archive" --strip-components=1 -C /opt/cretas-ci-light-tools/node
ln -s /usr/local/bin/python3.11 /opt/cretas-ci-light-tools/bin/python3
ln -s /usr/local/bin/python3.11 /opt/cretas-ci-light-tools/bin/python
chown -R cretas-ci-light:cretas-ci-light /opt/cretas-ci-light
install -m 0755 "$script_dir/job-started.sh" /etc/cretas-ci-light/job-started.sh
install -m 0755 "$script_dir/register.sh" /etc/cretas-ci-light/register.sh
install -m 0644 "$script_dir/cretas-ci-light.service" /etc/systemd/system/cretas-ci-light.service
systemctl daemon-reload
# Deliberately not registered/enabled/started until the isolated unit preflight passes.
echo 'LIGHT_INSTALL=prepared NOT_REGISTERED NOT_STARTED'
