"""Offline-safe, factory-only liveness probe for the LLM router floor.

This is an operational probe, not an evaluation suite: it exercises the
registered provider/profile combinations with a small factory prompt and emits
one JSON decision file for the daily health cron. It intentionally has no
dependency on retired domain evaluators or capability-scoring scripts.

Exit codes: 0 all measured entries alive, 1 measured dead/partial, 2 no valid
instrument reading (including a dead positive control).
"""
import argparse
import asyncio
import json
import os
import sys
from collections import defaultdict

from common import llm_router as R
from common.process_secret_harness import with_process_secret_harness

POSITIVE_CONTROL = ("aliyun_a_vl", "qwen3.8-flash")
N_QUERIES = 2
PROFILE_REPS = [
    (R.SLOT.REVIEW, "A text"),
    (R.SLOT.CHART, "B JSON"),
    (R.SLOT.REASONING, "C reasoning"),
]


def _fingerprint(slot):
    return tuple(sorted((R._SLOT_PARAMS.get(slot) or {}).items()))


def build_units():
    units = defaultdict(dict)
    slots_of = defaultdict(list)
    for slot, chain in R.SLOT_MODELS.items():
        for pair in chain:
            slots_of[pair].append(slot.value)
    # Every registry pair is measured through each of the three representative
    # slot contracts.  This keeps the instrument stable even when two slots
    # currently share identical parameter dictionaries.
    for pair in slots_of:
        for slot, name in PROFILE_REPS:
            units[pair][name] = (slot, name)
    return units, slots_of


def _content(response):
    choices = response.get("choices") if isinstance(response, dict) else None
    first = choices[0] if choices else {}
    message = first.get("message") if isinstance(first, dict) else {}
    return message.get("content") if isinstance(message, dict) else None


async def _probe(pair, slot, profile, queries):
    """Probe exactly ``pair``; router account-only filtering is insufficient."""
    passed = 0
    reasons = []
    original_chain = R.SLOT_MODELS.get(slot)
    # call_chain accepts account names, not (account, model) pairs. Lock the
    # representative slot to this one pair for the duration of each probe.
    R.SLOT_MODELS[slot] = [pair]
    try:
        for query in queries:
            wants_json = profile == "B JSON"
            user_query = query + (" Return one JSON object." if wants_json else "")
            payload = {
                "messages": [
                    {"role": "system", "content": with_process_secret_harness(
                        "你是工厂经营分析助手。只回答工厂经营数据问题。")},
                    {"role": "user", "content": user_query},
                ],
                "max_tokens": 128,
            }
            try:
                response = await R.call_chain(
                    slot, payload, chain=[pair[0]], timeout=R._SLOT_HOP_BUDGET_SECONDS,
                )
                content = _content(response)
                if not isinstance(content, str) or not content.strip():
                    reasons.append("EMPTY_CONTENT")
                elif wants_json:
                    try:
                        json.loads(content)
                    except (TypeError, ValueError):
                        reasons.append("MALFORMED_JSON")
                    else:
                        passed += 1
                else:
                    passed += 1
            except Exception as exc:  # noqa: BLE001 - instrument failure is rc=2
                reasons.append(type(exc).__name__)
    finally:
        if original_chain is None:
            R.SLOT_MODELS.pop(slot, None)
        else:
            R.SLOT_MODELS[slot] = original_chain
    return passed, reasons


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scope", choices=("minimal", "all"), default="minimal")
    args = parser.parse_args()
    entries = sorted(R._MINIMAL_SAFE_SET if args.scope == "minimal" else R._SAFE_MODELS)
    units, slots_of = build_units()
    queries = [
        "本月工厂订单和营收概况如何？",
        "请给出工厂库存风险的简短判断。",
    ][:N_QUERIES]
    rows = []
    orphans = []
    for pair in entries:
        if pair not in units:
            orphans.append(pair)
            rows.append({"account": pair[0], "model": pair[1], "measured": False,
                         "passed": 0, "total": len(queries), "reasons": ["NOT_ON_CHAIN"]})
            continue
        for _fp, (slot, profile) in units[pair].items():
            passed, reasons = await _probe(pair, slot, profile, queries)
            rows.append({"account": pair[0], "model": pair[1], "profile": profile,
                         "slots": slots_of[pair], "measured": True,
                         "passed": passed, "total": len(queries), "reasons": reasons})
            print(f"{pair[0]}/{pair[1]} [{profile}] {passed}/{len(queries)} {reasons}")

    positive = [row for row in rows if (row["account"], row["model"]) == POSITIVE_CONTROL]
    instrument_ok = bool(positive) and any(row["passed"] > 0 for row in positive)
    grouped = defaultdict(list)
    for row in rows:
        if row["measured"]:
            grouped[(row["account"], row["model"])].append(row)
    dead = [pair for pair, values in grouped.items() if all(v["passed"] == 0 for v in values)]
    partial = [pair for pair, values in grouped.items()
               if any(v["passed"] < v["total"] for v in values) and pair not in dead]
    rc = 2 if not instrument_ok or not grouped or orphans else (1 if dead or partial else 0)
    output = {"rc": rc, "alive": sum(any(v["passed"] > 0 for v in rows_) for rows_ in grouped.values()),
              "checked": len(grouped), "dead": len(dead), "partial": len(partial),
              "positive_control": POSITIVE_CONTROL, "orphans": len(orphans)}
    out = os.environ.get("PROBE_OUT")
    if out:
        try:
            with open(out, "w", encoding="utf-8") as handle:
                json.dump(output, handle, ensure_ascii=False)
        except OSError as exc:
            print(f"PROBE_OUT_WRITE_FAILED: {exc}", file=sys.stderr)
            return 2
    print(json.dumps(output, ensure_ascii=False))
    return rc


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
