"""Replay the training-sample filter rules (Steve 2026-10-29, option A) over candidate rows, offline.

Acceptance (过滤规则待办第 5 条) re-runs the same batch of prod candidate rows and compares:
the Q&A block ratio (48.2% under the 2026-10-24 rule), distill#12884 still blocked, the 14
person-word + 5 customer sentences passing, F006's "398" batch numbers not hit. This script
only computes verdicts; it never writes anywhere.

Input rows: CSV (header) or JSONL with ``id, factory_id, source, business_type, metadata,
input_text, teacher_output`` (``smart_bi_distillation_samples`` columns; metadata may be a JSON
string). Lists (per factory): ``--lists-json`` — a file shaped

    {"F006": {"products": [{"id": ..., "code": ..., "name": ..., "category": ...,
                            "product_category": ...}],
              "people": ["王五"], "customers": ["华联超市"]}}

(products = the factory's process-secret products, i.e. joined through
process_secret_policies), or ``--live-lists`` to read them from the Cretas DB (read-only,
needs the app's DB env).

Output: one JSON line per row (``--out``) with the verdict and reasons, and a summary on
stdout. Exit codes are three-state: 0 = all ``--expect`` checks hold, 1 = an expectation
failed, 2 = nothing measured (no rows read, or an expected id is missing from the input).

    python scripts/training_filter_replay.py --rows distill.csv --lists-json lists.json \\
        --out verdicts.jsonl --expect 12884:blocked
"""
from __future__ import annotations

import argparse
import asyncio
import collections
import csv
import json
import os
import sys
from typing import Any, Dict, Iterable, List, Mapping, Optional

_PYTHON_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from smartbi.services import training_consent as tc  # noqa: E402
from smartbi.services.process_secret_names import (  # noqa: E402
    EMPTY_LISTS,
    FactoryLists,
    lists_for_row,
    lists_from_rows,
    metadata_dict,
)


def lists_from_json(data: Mapping[str, Any]) -> Dict[str, FactoryLists]:
    return {f: lists_from_rows(v.get("products", ()), list(v.get("people", ())) + list(v.get("customers", ())))
            for f, v in data.items()}


def explain_row(row: Mapping[str, Any], by_factory: Mapping[str, FactoryLists]) -> Dict[str, Any]:
    """Verdict for one row under the current rules.

    ``content_blocked``: the capture-time content rule (process word + value / per-person
    pay / the factory's own people & customers) — the number comparable to the 48.2%.
    ``usable``: the full export verdict (rule version, tenant, factory, content, secret terms).
    """
    fid = row.get("factory_id") or None
    meta = metadata_dict(row.get("metadata"))
    q, a = row.get("input_text") or "", row.get("teacher_output") or ""
    lists = lists_for_row(fid, by_factory) if fid else EMPTY_LISTS
    reasons: List[str] = []
    if tc.process_word_with_value(q, tc.QA_INPUT_PROCESS_MARKERS):
        reasons.append("process_value_in_input")
    if tc.process_word_with_value(a, tc.QA_ANSWER_PROCESS_MARKERS):
        reasons.append("process_value_in_answer")
    if tc.pay_word_with_value(q, a):
        reasons.append("personnel_value")
    if lists.directory and (tc_mentions(lists.directory, q, a)):
        reasons.append("factory_person_or_customer")
    content_blocked = bool(reasons)
    if tc_mentions(lists.terms, q, a):
        reasons.append("process_secret_term")
    synthetic = tc.is_synthetic_factory(fid)
    if not synthetic:
        if not fid:
            reasons.append("no_factory")
        elif not tc.is_training_tenant(fid):
            reasons.append("restaurant_or_test_tenant")
        if (row.get("business_type") or "") in tc.NON_TRAINING_BUSINESS_TYPES:
            reasons.append("restaurant_bucket")
        if not tc.has_real_rule_version(meta):
            reasons.append("no_real_rule_version")
        if meta.get(tc.RECHECK_KEY):
            reasons.append("held_for_rejudgement")
        if meta.get(tc.REJECTED_KEY):
            reasons.append("rejected_at_rejudgement")
    usable = synthetic or not reasons
    return {"id": row.get("id"), "factory_id": fid, "source": row.get("source"),
            "content_blocked": content_blocked, "usable": usable, "reasons": reasons}


def tc_mentions(terms: Iterable[str], *texts: str) -> bool:
    from common.process_secret_terms import mentions_any

    return mentions_any(terms, *texts)


def read_rows(path: str) -> List[Dict[str, Any]]:
    if path.endswith(".jsonl"):
        with open(path, encoding="utf-8") as f:
            return [json.loads(line) for line in f if line.strip()]
    csv.field_size_limit(10 ** 9)
    with open(path, encoding="utf-8", newline="") as f:
        return [dict(r) for r in csv.DictReader(f)]


def summarize(verdicts: List[Dict[str, Any]]) -> Dict[str, Any]:
    n = len(verdicts)
    blocked = sum(v["content_blocked"] for v in verdicts)
    reasons = collections.Counter(r for v in verdicts for r in v["reasons"])
    by_factory = collections.Counter((v["factory_id"] or "<none>", v["content_blocked"]) for v in verdicts)
    return {"rows": n, "content_blocked": blocked,
            "content_blocked_ratio": round(blocked / n, 4) if n else None,
            "usable": sum(v["usable"] for v in verdicts),
            "reasons": dict(reasons.most_common()),
            "by_factory": {f"{f}|{'blocked' if b else 'pass'}": c for (f, b), c in sorted(by_factory.items())}}


def check_expectations(verdicts: List[Dict[str, Any]], expects: List[str]) -> int:
    by_id = {str(v["id"]): v for v in verdicts}
    rc = 0
    for e in expects:
        rid, want = e.split(":", 1)
        v = by_id.get(rid)
        if v is None:
            print(f"EXPECT {e}: id not in the input (nothing measured)")
            return 2
        got = "blocked" if v["content_blocked"] else "pass"
        ok = got == want
        print(f"EXPECT {e}: got {got} {v['reasons']} -> {'ok' if ok else 'MISMATCH'}")
        rc = rc or (0 if ok else 1)
    return rc


async def _live_lists():
    from smartbi.services.process_secret_names import all_factory_lists

    return await all_factory_lists()


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--rows", required=True, help="CSV / JSONL of candidate rows")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--lists-json", help="per-factory lists file (see module doc)")
    src.add_argument("--live-lists", action="store_true", help="read the lists from the Cretas DB (read-only)")
    ap.add_argument("--out", help="write one JSON verdict per row here")
    ap.add_argument("--exclude-synthetic", action="store_true",
                    help="drop SEED_R / SEED_F rows from the denominator (the 48.2%% pool was real rows only)")
    ap.add_argument("--expect", action="append", default=[], help="ID:blocked or ID:pass (content rule)")
    args = ap.parse_args(argv)

    rows = read_rows(args.rows)
    if args.exclude_synthetic:
        rows = [r for r in rows if not tc.is_synthetic_factory(r.get("factory_id") or None)]
    if not rows:
        print("no rows read — nothing measured")
        return 2
    if args.lists_json:
        with open(args.lists_json, encoding="utf-8") as f:
            by_factory = lists_from_json(json.load(f))
    else:
        by_factory = asyncio.run(_live_lists())
    verdicts = [explain_row(r, by_factory) for r in rows]
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            for v in verdicts:
                f.write(json.dumps(v, ensure_ascii=False) + "\n")
    print(json.dumps(summarize(verdicts), ensure_ascii=False, indent=2))
    return check_expectations(verdicts, args.expect)


if __name__ == "__main__":
    sys.exit(main())
