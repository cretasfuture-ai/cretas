#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
"${PYTHON_BIN:-python3}" "$ROOT_DIR/scripts/tests/test-cron-writer-visibility.py"
