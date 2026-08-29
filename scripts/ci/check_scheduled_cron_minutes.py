#!/usr/bin/env python3
"""Reject GitHub Actions schedules placed at minute zero.

GitHub documents that scheduled workflows can be delayed during high-load
periods, especially at the start of an hour.  This gate reports the exact
workflow and line instead of allowing a nightly safety gate to drift back to
an unreliable top-of-hour schedule.

Exit codes: 0 compliant, 1 top-of-hour cron found, 2 no cron was measured.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


CRON_RE = re.compile(r"^\s*-?\s*cron:\s*['\"](?P<expr>[^'\"]+)['\"]\s*(?:#.*)?$")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=".")
    args = parser.parse_args()

    workflows = Path(args.repo) / ".github" / "workflows"
    if not workflows.is_dir():
        print("✗ .github/workflows is missing; scheduled cron was not measured")
        return 2

    measured: list[tuple[Path, int, str]] = []
    violations: list[tuple[Path, int, str]] = []
    for path in sorted(workflows.glob("*.yml")):
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = CRON_RE.match(line)
            if not match:
                continue
            expression = match.group("expr")
            measured.append((path, line_number, expression))
            if expression.split()[0] == "0":
                violations.append((path, line_number, expression))

    if not measured:
        print("✗ no schedule cron was found; this reading is invalid")
        return 2

    print(f"measured {len(measured)} scheduled workflow cron expressions")
    if violations:
        print(f"✗ {len(violations)} top-of-hour schedule(s):")
        for path, line_number, expression in violations:
            print(f"  {path.relative_to(args.repo)}:{line_number} cron '{expression}' starts at minute 0")
        return 1

    print("✓ every scheduled workflow avoids minute 0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
