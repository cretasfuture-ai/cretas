#!/usr/bin/env bash
# GUARDS=scripts/ops/f006_aurora_runtime.py
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
python3 -m unittest discover -s "$root/scripts/ops/tests" -p test_f006_aurora_runtime.py
