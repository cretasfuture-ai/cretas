#!/usr/bin/env python3
"""Regression probe for the AI Assist factory/restaurant manual-chat answers.

Reads a probe set (JSON), POSTs each question to ``/api/food-kb/manual-chat`` and
checks the answer against behavioural criteria (``must_contain`` /
``must_not_contain`` / ``expect_source_prefix``).

Exit code is three-state (see .claude/rules/measurement-and-wiring.md, 硬约束 4):

* 0 — every probe passed
* 1 — at least one probe failed (the reading is valid and points at a defect)
* 2 — the instrument did not measure anything usable (HTTP != 200, empty answer,
      empty sources, unparsable body, or a probe set with zero entries)

Usage::

    python scripts/food-kb/probe_manual_chat.py \
        --base https://aiassist.cretaceousfuture.com \
        --set scripts/food-kb/probes/factory-2026-09-02.json \
        --out /tmp/probe-result.json

Probe set format::

    {
      "category": "factory",
      "probes": [
        {
          "id": "rework-auto-deduct",
          "question": "正式报工时回料会不会按配比自动扣减？",
          "must_contain": ["按配比", "自动扣减"],
          "must_contain_any": ["守卫", "不重复"],
          "must_not_contain": ["不会按配比自动扣减"],
          "expect_source_prefix": "f006-production-full-chain-sop",
          "refs": ["#514"]
        }
      ]
    }

``must_contain`` — every phrase must appear. ``must_contain_any`` — at least one
phrase must appear. ``must_not_contain`` — none may appear. All comparisons are
case-insensitive on a whitespace-collapsed answer.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


def _norm(text: str) -> str:
    return re.sub(r"\s+", "", text or "").lower()


def _post(base: str, path: str, payload: dict[str, Any], timeout: float) -> tuple[int, str]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + path,
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 - fixed https host
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, TimeoutError) as exc:
        return 0, str(exc)


def _extract(raw: str) -> tuple[str, list[str], dict[str, Any]]:
    data = json.loads(raw)
    inner = data.get("data") if isinstance(data.get("data"), dict) else data
    answer = inner.get("answer") or ""
    sources = inner.get("sources") or []
    names = [s.get("source", "") for s in sources if isinstance(s, dict)]
    return answer, names, data


def run(base: str, probe_set: dict[str, Any], timeout: float, only: set[str] | None) -> tuple[int, list[dict[str, Any]]]:
    category = probe_set.get("category")
    probes = [p for p in probe_set.get("probes", []) if not only or p.get("id") in only]
    if not probes:
        print("INSTRUMENT DEAD: probe set has zero entries")
        return 2, []

    results: list[dict[str, Any]] = []
    instrument_dead = 0
    failed = 0
    for probe in probes:
        pid = probe.get("id") or probe["question"][:20]
        payload: dict[str, Any] = {"question": probe["question"]}
        if category:
            payload["category"] = category
        t0 = time.time()
        status, raw = _post(base, "/api/food-kb/manual-chat", payload, timeout)
        elapsed = time.time() - t0
        row: dict[str, Any] = {"id": pid, "question": probe["question"], "http": status, "seconds": round(elapsed, 1), "refs": probe.get("refs", [])}
        if status != 200:
            row["verdict"] = "DEAD"
            row["why"] = f"http={status} body={raw[:160]}"
            instrument_dead += 1
            results.append(row)
            print(f"[DEAD] {pid}: {row['why']}")
            continue
        try:
            answer, sources, _ = _extract(raw)
        except (ValueError, AttributeError) as exc:
            row["verdict"] = "DEAD"
            row["why"] = f"unparsable body: {exc}"
            instrument_dead += 1
            results.append(row)
            print(f"[DEAD] {pid}: {row['why']}")
            continue
        row["answer"] = answer
        row["sources"] = sorted(set(sources))
        if not answer.strip() or not sources:
            row["verdict"] = "DEAD"
            row["why"] = "empty answer or empty sources"
            instrument_dead += 1
            results.append(row)
            print(f"[DEAD] {pid}: {row['why']}")
            continue

        n = _norm(answer)
        problems: list[str] = []
        for phrase in probe.get("must_contain", []):
            if _norm(phrase) not in n:
                problems.append(f"missing «{phrase}»")
        # one or more "at least one of" groups: must_contain_any, must_contain_any_2, ...
        for key in sorted(k for k in probe if k.startswith("must_contain_any")):
            anyset = probe.get(key) or []
            if anyset and not any(_norm(p) in n for p in anyset):
                problems.append(f"none of {key}: " + " / ".join(anyset))
        for phrase in probe.get("must_not_contain", []):
            if _norm(phrase) in n:
                problems.append(f"forbidden «{phrase}» present")
        prefix = probe.get("expect_source_prefix")
        if prefix and not all(s.startswith(prefix) for s in sources):
            problems.append(f"sources not all {prefix}*: {sorted(set(sources))}")

        if problems:
            failed += 1
            row["verdict"] = "FAIL"
            row["why"] = "; ".join(problems)
            print(f"[FAIL] {pid} ({elapsed:.1f}s): {row['why']}")
            print("       A: " + answer[:260].replace("\n", " / "))
        else:
            row["verdict"] = "PASS"
            print(f"[PASS] {pid} ({elapsed:.1f}s)")
        results.append(row)

    total = len(results)
    passed = sum(1 for r in results if r["verdict"] == "PASS")
    print(f"\nprobes={total} pass={passed} fail={failed} dead={instrument_dead}")
    if instrument_dead:
        return 2, results
    if failed:
        return 1, results
    return 0, results


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="https://aiassist.cretaceousfuture.com")
    ap.add_argument("--set", required=True, help="probe set JSON")
    ap.add_argument("--out", help="write full results JSON here")
    ap.add_argument("--only", action="append", help="probe id to run (repeatable)")
    ap.add_argument("--timeout", type=float, default=150.0)
    args = ap.parse_args()

    probe_set = json.loads(Path(args.set).read_text(encoding="utf-8"))
    rc, results = run(args.base, probe_set, args.timeout, set(args.only) if args.only else None)
    if args.out:
        Path(args.out).write_text(json.dumps({"rc": rc, "base": args.base, "results": results}, ensure_ascii=False, indent=1), encoding="utf-8")
    return rc


if __name__ == "__main__":
    sys.exit(main())
