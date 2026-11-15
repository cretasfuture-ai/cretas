"""Read-only "intent gap" summary over the intent flywheel (``ai_intent_flywheel_turns``).

INTENT-DISTILL-20261111 (Steve 2026-11-14): find what the factory AI keeps failing at, as a candidate
list for a person to review. ⛔ It never creates or changes an intent; it only prints candidates.

Signals (per turn, from the export columns only — see ``intent_flywheel_export.py``):

    no_match            no intent: action NONE, or final status NO_MATCH / fell to chit-chat
    low_confidence      acted (DIRECT / SHOW) with confidence below ``--low`` (default 0.6)
    repeated_clarify    buttons / a question shown for the same top-2 intent pair ``--min-repeat`` times or more
    non_first_pick      the user picked option 2+ (the first candidate was wrong), per (first, chosen)
    changed_or_gave_up  after a question the user rephrased (CHANGED_REQUEST / OPTION_OUTSIDE_CANDIDATES)
                        or left (derived ABANDONED)

Texts of the same signal are grouped by a plain similarity (character bigram Jaccard >= ``--similar``
after removing punctuation and digits); no embedding, no model. Each cluster shows its size, the intents
involved and up to three example sentences (export text: secret factories only ever show the redacted one).

    python scripts/intent_flywheel_gaps.py --self-test
    python scripts/intent_flywheel_gaps.py --since '2026-11-07 00:00:00' [--factory F006] [--low 0.6]

Exit codes: 0 = turns scanned, no candidate; 1 = candidates listed; 2 = nothing measured (self-test
failed, database unreadable / not read-only, or no turn in the window).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from typing import Any, Dict, List, Optional, Sequence, Tuple

try:
    from scripts import intent_flywheel_export as export
except ImportError:  # run as a file: python scripts/intent_flywheel_gaps.py
    import intent_flywheel_export as export  # type: ignore[no-redef]

NO_MATCH_STATUSES = frozenset({"NO_MATCH", "CONVERSATIONAL", "CHAT", "CHITCHAT", "SMALL_TALK"})
CHANGED = frozenset({"CHANGED_REQUEST", "OPTION_OUTSIDE_CANDIDATES", "ABANDONED"})
_STRIP = re.compile(r"[\s0-9０-９,.，。!?！？;；:：、\"'“”‘’()（）<>《》\[\]【】-]+")


def normalized(text: Optional[str]) -> str:
    return _STRIP.sub("", text or "")


def bigrams(text: str) -> frozenset:
    return frozenset(text[i:i + 2] for i in range(len(text) - 1)) if len(text) > 1 else frozenset({text})


def similarity(a: str, b: str) -> float:
    x, y = bigrams(a), bigrams(b)
    return len(x & y) / len(x | y) if x or y else 1.0


def cluster(items: List[Dict[str, Any]], threshold: float) -> List[Dict[str, Any]]:
    """Greedy: each text joins the first cluster whose first text is similar enough."""
    clusters: List[Dict[str, Any]] = []
    for item in items:
        key = normalized(item.get("input"))
        for c in clusters:
            if similarity(c["_key"], key) >= threshold:
                c["items"].append(item)
                break
        else:
            clusters.append({"_key": key, "items": [item]})
    out = []
    for c in sorted(clusters, key=lambda c: -len(c["items"])):
        intents: Dict[str, int] = {}
        for it in c["items"]:
            for code in it.get("intents") or []:
                intents[code] = intents.get(code, 0) + 1
        examples = []
        for it in c["items"]:
            if it.get("input") and it["input"] not in examples:
                examples.append(it["input"])
            if len(examples) == 3:
                break
        out.append({"size": len(c["items"]), "intents": intents, "examples": examples,
                    "factories": sorted({it["factoryId"] for it in c["items"] if it.get("factoryId")})})
    return out


def signals(line: Dict[str, Any], low: float) -> List[Tuple[str, Dict[str, Any]]]:
    d = line["decision"]
    candidates = [c.get("code") for c in d.get("candidates") or [] if isinstance(c, dict) and c.get("code")]
    base = {"input": line.get("input"), "factoryId": line.get("factoryId")}
    out: List[Tuple[str, Dict[str, Any]]] = []
    if line.get("inputKind") == "BUTTON":
        return out
    if d.get("action") == "NONE" or line.get("finalStatus") in NO_MATCH_STATUSES:
        out.append(("no_match", {**base, "intents": candidates[:1]}))
    conf = d.get("confidence")
    if d.get("action") in ("DIRECT", "SHOW") and conf is not None and conf < low:
        out.append(("low_confidence", {**base, "intents": [d.get("intent")] if d.get("intent") else []}))
    if d.get("action") in ("SHOW", "ASK") and len(candidates) >= 2:
        out.append(("repeated_clarify", {**base, "pair": tuple(sorted(candidates[:2])), "intents": candidates[:2]}))
    rank = line.get("reactionOptionRank")
    if line.get("reaction") == "OPTION_SELECTED" and isinstance(rank, int) and rank > 1 and candidates:
        chosen = candidates[rank - 1] if rank - 1 < len(candidates) else None
        out.append(("non_first_pick", {**base, "pair": (candidates[0], chosen), "intents": [candidates[0], chosen]}))
    if line.get("reaction") in CHANGED and (d.get("action") in ("SHOW", "ASK")
                                            or line.get("finalStatus") in export.ASKING_STATUSES):
        out.append(("changed_or_gave_up", {**base, "intents": candidates[:2], "reaction": line.get("reaction")}))
    return out


def summarize(lines: List[Dict[str, Any]], low: float = 0.6, min_repeat: int = 3,
              similar: float = 0.5) -> Dict[str, Any]:
    buckets: Dict[str, List[Dict[str, Any]]] = {k: [] for k in
                                               ("no_match", "low_confidence", "repeated_clarify", "non_first_pick",
                                                "changed_or_gave_up")}
    for line in lines:
        for kind, item in signals(line, low):
            buckets[kind].append(item)
    report: Dict[str, Any] = {"turns_scanned": len(lines),
                              "signal_counts": {k: len(v) for k, v in buckets.items()}, "candidates": {}}
    for kind in ("no_match", "low_confidence", "changed_or_gave_up"):
        report["candidates"][kind] = cluster(buckets[kind], similar)
    for kind in ("repeated_clarify", "non_first_pick"):
        pairs: Dict[Tuple, List[Dict[str, Any]]] = {}
        for item in buckets[kind]:
            pairs.setdefault(item["pair"], []).append(item)
        keep = [(p, items) for p, items in pairs.items() if kind == "non_first_pick" or len(items) >= min_repeat]
        report["candidates"][kind] = [
            {"pair": list(p), "count": len(items), "phrasings": cluster(items, similar)}
            for p, items in sorted(keep, key=lambda kv: -len(kv[1]))]
    return report


def self_test() -> List[str]:
    failures: List[str] = []
    now = datetime(2026, 11, 14, 12, 0, 0)

    def line(**kw):
        row = {"turn_id": "t", "factory_id": "F", "created_at": now, "input_kind": "TEXT", "decision_action": "SHOW",
               "candidates_json": '[{"code":"ORDER_CREATE"},{"code":"ORDER_QUERY"}]', "confidence": 0.5,
               "final_status": "NEED_CLARIFICATION", "user_input_export": "帮我弄下订单"}
        row.update(kw)
        return export.to_line(row, now)
    lines = [line(), line(user_input_export="帮我弄一下订单"), line(user_input_export="帮我搞下订单"),
             line(reaction="OPTION_SELECTED", reaction_option_rank=2),
             line(decision_action="NONE", final_status="NO_MATCH", candidates_json=None, user_input_export="今天天气"),
             line(input_kind="BUTTON", decision_action="NONE", final_status="NO_MATCH")]
    r = summarize(lines)
    if r["signal_counts"]["no_match"] != 1:
        failures.append(f"no_match count {r['signal_counts']}")
    pairs = r["candidates"]["repeated_clarify"]
    if not pairs or pairs[0]["pair"] != ["ORDER_CREATE", "ORDER_QUERY"] or pairs[0]["count"] != 4:
        failures.append(f"repeated_clarify {pairs}")
    if pairs and pairs[0]["phrasings"][0]["size"] < 3:
        failures.append(f"similar phrasings not clustered: {pairs[0]['phrasings']}")
    nfp = r["candidates"]["non_first_pick"]
    if not nfp or nfp[0]["pair"] != ["ORDER_CREATE", "ORDER_QUERY"]:
        failures.append(f"non_first_pick {nfp}")
    if summarize([line(decision_action="DIRECT", final_status="COMPLETED", confidence=0.95,
                       candidates_json=None)])["signal_counts"] != {k: 0 for k in r["signal_counts"]}:
        failures.append("a confident direct answer must not be a gap")
    return failures


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--since")
    parser.add_argument("--until")
    parser.add_argument("--factory")
    parser.add_argument("--low", type=float, default=0.6)
    parser.add_argument("--min-repeat", type=int, default=3)
    parser.add_argument("--similar", type=float, default=0.5)
    args = parser.parse_args(argv)
    failures = self_test() + export.self_test()
    if failures:
        print(json.dumps({"error": "self-test failed", "failures": failures}, ensure_ascii=False), file=sys.stderr)
        return 2
    if args.self_test:
        print(json.dumps({"self_test": "passed"}))
        return 0
    if not args.since:
        parser.error("--since is required (GMT+8 wall clock, no offset)")
    try:
        rows = export.read_rows(export.connect(), args.since, args.until, args.factory)
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"error": f"database unreadable: {type(e).__name__}: {e}"}, ensure_ascii=False), file=sys.stderr)
        return 2
    now = datetime.now()
    report = summarize([export.to_line(r, now) for r in rows], args.low, args.min_repeat, args.similar)
    report.update({"since": args.since, "until": args.until, "factory": args.factory})
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not rows:
        print(json.dumps({"note": "no turn in the window: nothing measured"}), file=sys.stderr)
        return 2
    return 1 if any(report["candidates"][k] for k in report["candidates"]) else 0


if __name__ == "__main__":
    sys.exit(main())
