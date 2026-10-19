#!/usr/bin/env bash
set -euo pipefail
[[ $(id -un) == cretas-ci-light && $(uname -s) == Linux ]] || { echo 'LIGHT_PREFLIGHT=wrong_identity'; exit 75; }
case "${GITHUB_JOB:-}" in
  fast-contracts|postgres-reversal-concurrency|full-suite|jpa-gate|package|tool-inventory-gate)
    echo 'LIGHT_PREFLIGHT=heavy_job_refused'; exit 75 ;;
esac
[[ ! -r /mnt/c/Windows/win.ini && ! -r /opt/cretas-ci/.runner ]] || { echo 'LIGHT_PREFLIGHT=isolation_failed'; exit 75; }
for required in git node npm python3 jq; do
  command -v "$required" >/dev/null || { echo "LIGHT_PREFLIGHT=missing_tool name=$required"; exit 75; }
done
available_kib=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
[[ "$available_kib" =~ ^[0-9]+$ ]] || exit 75
if (( available_kib < 8 * 1024 * 1024 )); then
  echo "LIGHT_PREFLIGHT=insufficient_headroom available_kib=$available_kib"; exit 75
fi
echo "LIGHT_PREFLIGHT=pass job=${GITHUB_JOB:-preflight} cpu_quota=3 memory_max_gib=6 swap_max=0 available_kib=$available_kib"
