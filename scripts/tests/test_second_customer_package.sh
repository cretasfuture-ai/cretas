#!/usr/bin/env bash
set -euo pipefail
package_repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$package_repo_root"
node --test scripts/onboarding/second-customer-package/validate-package.test.mjs
