"""Read-only JSONL export of the intent flywheel (``ai_intent_flywheel_turns``, Flyway V20261114_87).

INTENT-DISTILL-20261111 (Steve 2026-11-14): every online AI turn leaves one row, for distilling the
decision model (online Luna) and later RL on usage habits. One JSONL line per turn: input -> decision
-> reaction.

Secrecy: the script only ever SELECTs the export columns (``user_input_export`` / ``context_export``).
It never reads ``user_input``, ``context_summary`` or ``user_input_cipher`` — not even for factories
whose secrecy is off (there the export column is the original sentence anyway, apart from staff names /
phone numbers, which are privacy). ``RAW_COLUMNS`` lists them and the self-test proves the SQL does
not mention them. ``TEXT_DROPPED`` rows (secret factory, name list unreadable at write time) come out
with ``input: null`` and keep their decision metadata.

Reaction: the stored value when the next turn / the confirm entry judged it. Rows without one get a
derived value, marked ``reactionDerived: true`` and never written back:

    PENDING      younger than the window (30 min): the user may still answer
    ABANDONED    older, and the turn asked something (clarification / buttons / write confirmation)
    NO_FOLLOWUP  older, and the turn was answered (nothing came after it)

    python scripts/intent_flywheel_export.py --self-test
    python scripts/intent_flywheel_export.py --since '2026-11-14 00:00:00' [--until ...] [--factory F006] > turns.jsonl

Needs POSTGRES_HOST / POSTGRES_PORT / POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD. The session is
switched to read-only and the run stops if PostgreSQL does not confirm it.

Exit codes: 0 = rows exported; 2 = nothing measured (self-test failed, database unreadable or not
read-only, or no row in the window — the summary on stderr says which).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timedelta
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

WINDOW = timedelta(minutes=30)
RAW_COLUMNS = ("user_input", "context_summary", "user_input_cipher")
ASKING_STATUSES = frozenset({"NEED_CLARIFICATION", "NEED_MORE_INFO", "WRITE_CONFIRM_REQUIRED", "NEED_CONFIRMATION"})
ASKING_ACTIONS = frozenset({"SHOW", "ASK"})

EXPORT_COLUMNS = (
    "turn_id", "factory_id", "user_id", "session_id", "entry", "input_kind", "catalog_version",
    "option_set_hash", "secrecy_mode", "user_input_export", "context_export", "redaction_json",
    "requested_intent_code", "decision_source", "decision_path", "decision_action", "decided_intent_code",
    "confidence", "candidates_json", "final_status", "response_intent_code", "latency_ms", "reaction",
    "reaction_option_rank", "reaction_turn_id", "reacted_at", "created_at",
)


def select_sql(with_factory: bool, with_until: bool) -> str:
    where = ["created_at >= %(since)s::timestamp"]
    if with_until:
        where.append("created_at < %(until)s::timestamp")
    if with_factory:
        where.append("factory_id = %(factory)s")
    return ("SELECT " + ", ".join(EXPORT_COLUMNS) + " FROM ai_intent_flywheel_turns WHERE "
            + " AND ".join(where) + " ORDER BY id")


def _json(value: Optional[str]) -> Any:
    if value is None:
        return None
    try:
        return json.loads(value)
    except (TypeError, ValueError):
        return None


def derive_reaction(row: Dict[str, Any], now: datetime) -> Tuple[Optional[str], bool]:
    """(reaction, derived). Stored reactions win; otherwise derived from age and what the turn did."""
    if row.get("reaction"):
        return row["reaction"], False
    created = row.get("created_at")
    if not isinstance(created, datetime) or now - created < WINDOW:
        return "PENDING", True
    asked = row.get("final_status") in ASKING_STATUSES or row.get("decision_action") in ASKING_ACTIONS
    return ("ABANDONED" if asked else "NO_FOLLOWUP"), True


def to_line(row: Dict[str, Any], now: datetime) -> Dict[str, Any]:
    reaction, derived = derive_reaction(row, now)
    confidence = row.get("confidence")
    return {
        "turnId": row.get("turn_id"),
        "factoryId": row.get("factory_id"),
        "sessionKey": None if row.get("session_id") is None else f"{row.get('user_id')}|{row.get('session_id')}",
        "entry": row.get("entry"),
        "inputKind": row.get("input_kind"),
        "catalogVersion": row.get("catalog_version"),
        "optionSetHash": row.get("option_set_hash"),
        "secrecyMode": row.get("secrecy_mode"),
        "input": row.get("user_input_export"),
        "context": row.get("context_export"),
        "redaction": _json(row.get("redaction_json")),
        "decision": {
            "requestedIntent": row.get("requested_intent_code"),
            "source": row.get("decision_source"),
            "path": _json(row.get("decision_path")),
            "action": row.get("decision_action"),
            "intent": row.get("decided_intent_code"),
            "confidence": None if confidence is None else float(confidence),
            "candidates": _json(row.get("candidates_json")) or [],
        },
        "finalStatus": row.get("final_status"),
        "responseIntent": row.get("response_intent_code"),
        "latencyMs": row.get("latency_ms"),
        "reaction": reaction,
        "reactionDerived": derived,
        "reactionOptionRank": row.get("reaction_option_rank"),
        "reactionTurnId": row.get("reaction_turn_id"),
        "createdAt": row["created_at"].isoformat() if isinstance(row.get("created_at"), datetime) else None,
    }


def read_rows(conn, since: str, until: Optional[str], factory: Optional[str]) -> List[Dict[str, Any]]:
    """Opens a read-only session; raises RuntimeError when PostgreSQL does not confirm it."""
    conn.set_session(readonly=True, autocommit=False)
    try:
        with conn.cursor() as cur:
            cur.execute("SHOW transaction_read_only")
            if cur.fetchone()[0] != "on":
                raise RuntimeError("session is not read-only")
            cur.execute(select_sql(factory is not None, until is not None),
                        {"since": since, "until": until, "factory": factory})
            names = [d[0] for d in cur.description]
            return [dict(zip(names, r)) for r in cur.fetchall()]
    finally:
        conn.rollback()


def connect():
    import psycopg2

    return psycopg2.connect(
        host=os.getenv("POSTGRES_HOST", "localhost"),
        port=int(os.getenv("POSTGRES_PORT", "5432")),
        dbname=os.getenv("POSTGRES_DB", "cretas_db"),
        user=os.getenv("POSTGRES_USER", "cretas_user"),
        password=os.getenv("POSTGRES_PASSWORD", ""),
        connect_timeout=10,
    )


def self_test() -> List[str]:
    """Pure checks; returns the failures (empty = passed)."""
    failures: List[str] = []
    for sql in (select_sql(False, False), select_sql(True, True)):
        selected = {c.strip() for c in sql[len("SELECT "):sql.index(" FROM ")].split(",")}
        for raw in RAW_COLUMNS:
            if raw in selected or raw in sql.split(" FROM ")[1]:
                failures.append(f"export SQL reads raw column {raw}")
    now = datetime(2026, 11, 14, 12, 0, 0)
    old = now - timedelta(hours=2)
    cases = [
        ({"reaction": "CONFIRMED", "created_at": old}, ("CONFIRMED", False)),
        ({"created_at": now - timedelta(minutes=5), "final_status": "NEED_CLARIFICATION"}, ("PENDING", True)),
        ({"created_at": old, "final_status": "NEED_CLARIFICATION"}, ("ABANDONED", True)),
        ({"created_at": old, "final_status": "COMPLETED", "decision_action": "SHOW"}, ("ABANDONED", True)),
        ({"created_at": old, "final_status": "COMPLETED", "decision_action": "DIRECT"}, ("NO_FOLLOWUP", True)),
    ]
    for row, expected in cases:
        got = derive_reaction(row, now)
        if got != expected:
            failures.append(f"derive_reaction({row}) = {got}, expected {expected}")
    line = to_line({"turn_id": "t", "user_input_export": "给客户A下五十斤卤鸭腿", "created_at": old,
                    "candidates_json": '[{"code":"A"}]', "confidence": 0.5}, now)
    if line["input"] != "给客户A下五十斤卤鸭腿" or line["decision"]["candidates"] != [{"code": "A"}]:
        failures.append(f"to_line mapping wrong: {line}")
    return failures


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--since")
    parser.add_argument("--until")
    parser.add_argument("--factory")
    args = parser.parse_args(argv)
    failures = self_test()
    if failures:
        print(json.dumps({"error": "self-test failed", "failures": failures}, ensure_ascii=False), file=sys.stderr)
        return 2
    if args.self_test:
        print(json.dumps({"self_test": "passed"}))
        return 0
    if not args.since:
        parser.error("--since is required (GMT+8 wall clock, no offset)")
    try:
        rows = read_rows(connect(), args.since, args.until, args.factory)
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"error": f"database unreadable: {type(e).__name__}: {e}"}, ensure_ascii=False), file=sys.stderr)
        return 2
    now = datetime.now()
    for row in rows:
        print(json.dumps(to_line(row, now), ensure_ascii=False))
    summary = {"rows": len(rows), "since": args.since, "until": args.until, "factory": args.factory,
               "by_secrecy_mode": _count(r.get("secrecy_mode") for r in rows)}
    print(json.dumps(summary, ensure_ascii=False), file=sys.stderr)
    if not rows:
        print(json.dumps({"note": "no row in the window: nothing exported"}), file=sys.stderr)
        return 2
    return 0


def _count(values: Iterable[Any]) -> Dict[str, int]:
    out: Dict[str, int] = {}
    for v in values:
        out[str(v)] = out.get(str(v), 0) + 1
    return out


if __name__ == "__main__":
    sys.exit(main())
