#!/usr/bin/env bash
# Job-start hook of the quick-check slot: only the small allowlisted jobs may run here.
# Anything else (a routing mistake) fails fast instead of running inside 1 CPU / 1 GiB.
# ⚠️ The allowlist must equal QUICK_JOBS in scripts/ci/switch_ci_runner.py
#    (pinned by the routing test until the slot was retired on 2026-10-25).
set -euo pipefail
[[ $(id -un) == cretas-ci-quick && $(uname -s) == Linux ]] || { echo 'QUICK_PREFLIGHT=wrong_identity'; exit 75; }
case "${GITHUB_JOB:-preflight}" in
  tracked-secret-scan|no-literal-nul-bytes|scan-surface-in-sync|reap|preflight) ;;
  *) echo "QUICK_PREFLIGHT=job_not_allowlisted job=${GITHUB_JOB:-}"; exit 75 ;;
esac
[[ ! -r /mnt/c/Windows/win.ini && ! -r /opt/cretas-ci/.runner && ! -r /opt/cretas-ci-light/.runner ]] || {
  echo 'QUICK_PREFLIGHT=isolation_failed'; exit 75; }
for required in git python3 bash; do
  command -v "$required" >/dev/null || { echo "QUICK_PREFLIGHT=missing_tool name=$required"; exit 75; }
done
available_kib=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
[[ "$available_kib" =~ ^[0-9]+$ ]] || exit 75
if (( available_kib < 2 * 1024 * 1024 )); then
  echo "QUICK_PREFLIGHT=insufficient_headroom available_kib=$available_kib"; exit 75
fi
echo "QUICK_PREFLIGHT=pass job=${GITHUB_JOB:-preflight} cpu_quota=1 memory_max_gib=1 swap_max=0 available_kib=$available_kib"
