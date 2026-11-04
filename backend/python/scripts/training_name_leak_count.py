"""Count stored Java training rows that contain a person or customer name of their own factory,
or a pay / attendance word with a number (a separate column).

Steve 2026-11-04: person and customer names and pay / attendance numbers are privacy, always
filtered out of training, learning and distillation samples; no setting controls them. This is
the post-deploy measurement for that rule: did any such row get stored after a given time?

Read-only: the session is opened read-only and the script stops if PostgreSQL does not confirm
it. The output has counts per table / factory and row ids only — never a name, never row text.

Tables and text columns (rows with ``created_at >= --since``, soft-deleted rows included):

    ai_training_samples      user_input
    active_learning_samples  user_input, normalized_input, matched_keywords, top_candidates
    ai_learned_expressions   expression

Matchers: the production ones. Names: ``directory_by_factory`` over ``ALL_FACTORY_DIRECTORY_SQL``
and ``mentions_any`` (same verdicts as Java ``ProcessSecretTerms``: the shared case file runs
both); a row is only compared with its OWN factory's directory. Pay / attendance:
``pay_word_with_value`` (same verdicts as Java ``TrainingSampleRules.utteranceHasPayValue``), on
the same columns production checks: not ``top_candidates``, whose numbers are intent scores. It
needs no directory, so it also runs on rows whose factory has none.

    python scripts/training_name_leak_count.py --self-test
    python scripts/training_name_leak_count.py --since '2026-11-04 12:00:00' [--factory F006]

Needs POSTGRES_HOST / POSTGRES_PORT / POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD for the
cretas database. Every real run repeats the self-test first, plus a live positive control on
the directory it just loaded.

Exit codes: 0 = rows were scanned and none contains a name or a pay value; 1 = some do (ids in
the report); 2 = nothing measured: the self-test failed, the database is unreadable or not
read-only, the directory is empty, or no row was compared with a directory. A 0 needs
``rows_scanned`` > 0 to mean anything: rows whose factory has no directory are reported as
``rows_unchecked``, never as clean. ``rows_scanned`` / ``rows_unchecked`` are about names;
``pay_rows_scanned`` counts every row the pay rule read.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any, Dict, FrozenSet, Iterable, List, Mapping, Optional, Sequence, Tuple

_PYTHON_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from smartbi.services import training_consent as tc  # noqa: E402

# table -> text expressions (jsonb columns are read as their text form)
TABLES: Tuple[Tuple[str, Tuple[str, ...]], ...] = (
    ("ai_training_samples", ("user_input",)),
    ("active_learning_samples", ("user_input", "normalized_input", "matched_keywords::text", "top_candidates::text")),
    ("ai_learned_expressions", ("expression",)),
)

# Production does not run the pay rule on these: the numbers in them are intent scores
NOT_PAY_CHECKED = frozenset({"top_candidates::text"})

# id, factory_id, texts for the name check, texts for the pay rule
Row = Tuple[Any, Optional[str], Sequence[Optional[str]], Sequence[Optional[str]]]


def tally(table: str, rows: Iterable[Row], directory: Mapping[str, FrozenSet[str]]) -> List[Dict[str, Any]]:
    """Per-factory counts for one table. A row is compared with its own factory's directory; the
    pay rule needs no directory and reads every row."""
    per: Dict[Optional[str], Dict[str, Any]] = {}
    for row_id, factory_id, texts, pay_texts in rows:
        names = directory.get(factory_id) if factory_id else None
        entry = per.setdefault(factory_id, {"table": table, "factory_id": factory_id, "directory_size": len(names or ()),
                                            "rows_scanned": 0, "rows_unchecked": 0, "rows_with_name": 0, "row_ids": [],
                                            "pay_rows_scanned": 0, "rows_with_pay_value": 0, "pay_row_ids": []})
        entry["pay_rows_scanned"] += 1
        if tc.pay_word_with_value(*pay_texts):
            entry["rows_with_pay_value"] += 1
            entry["pay_row_ids"].append(str(row_id))
        if not names:
            entry["rows_unchecked"] += 1
            continue
        entry["rows_scanned"] += 1
        if tc.mentions_any(names, *texts):
            entry["rows_with_name"] += 1
            entry["row_ids"].append(str(row_id))
    return [per[k] for k in sorted(per, key=lambda f: f or "")]


def self_test() -> List[str]:
    """Known answers through the same code a real run uses: a synthetic row carrying a directory
    name must hit, a clean row must not, and another factory's name must not; a pay / attendance
    word with a number must hit the pay column, the word alone must not, and neither must a score
    in a column production does not check. Returns the failures."""
    directory = tc.directory_by_factory([("F_A", "测试甲某"), ("F_A", "测试客户有限公司"), ("F_B", "测试乙某")])
    report = tally("self_test", [
        ("person", "F_A", ("今天测试甲某报工", None), ("今天测试甲某报工", None)),
        ("customer", "F_A", (None, "[\"测试客户有限公司\"]"), (None, "[\"测试客户有限公司\"]")),
        ("clean", "F_A", ("今天产量多少",), ("今天产量多少",)),
        ("other_factory", "F_A", ("测试乙某今天报工",), ("测试乙某今天报工",)),
        ("pay_value", "F_A", ("本月工资 8000 元",), ("本月工资 8000 元",)),
        ("attendance_value", "F_A", (None, "考勤异常 3 人次"), (None, "考勤异常 3 人次")),
        ("pay_word_only", "F_A", ("查一下本月工资",), ("查一下本月工资",)),
        ("score_column", "F_A", ("考勤 0.81",), ()),
        ("no_directory", "F_C", ("测试甲某",), ("测试甲某",)),
        ("no_directory_pay", "F_C", ("工资 8000",), ("工资 8000",)),
    ], directory)
    by_factory = {e["factory_id"]: e for e in report}
    failures = []
    a = by_factory.get("F_A", {})
    if sorted(a.get("row_ids", [])) != ["customer", "person"]:
        failures.append(f"F_A hits {a.get('row_ids')} != ['customer', 'person']")
    if a.get("rows_scanned") != 8:
        failures.append(f"F_A rows_scanned {a.get('rows_scanned')} != 8")
    if sorted(a.get("pay_row_ids", [])) != ["attendance_value", "pay_value"]:
        failures.append(f"F_A pay hits {a.get('pay_row_ids')} != ['attendance_value', 'pay_value']")
    c = by_factory.get("F_C", {})
    if c.get("rows_unchecked") != 2:
        failures.append("a row without a directory was not reported as unchecked")
    if c.get("pay_row_ids") != ["no_directory_pay"]:
        failures.append(f"F_C pay hits {c.get('pay_row_ids')} != ['no_directory_pay']")
    return failures


def live_control(directory: Mapping[str, FrozenSet[str]]) -> List[str]:
    """Every loaded factory directory must match one of its own names inside a sentence. Reports
    factory ids only, never the name."""
    return [f for f, names in directory.items()
            if names and not tc.mentions_any(names, "前缀" + next(iter(names)) + "后缀")]


def read_directory(cur) -> Dict[str, FrozenSet[str]]:
    cur.execute(tc.ALL_FACTORY_DIRECTORY_SQL)
    return tc.directory_by_factory(cur.fetchall())


def read_rows(cur, table: str, columns: Sequence[str], since: str, factory: Optional[str]) -> Iterable[Row]:
    sql = f"SELECT id::text, factory_id, {', '.join(columns)} FROM {table} WHERE created_at >= %s::timestamp"
    args: List[Any] = [since]
    if factory:
        sql += " AND factory_id = %s"
        args.append(factory)
    cur.execute(sql + " ORDER BY id", args)
    for r in cur.fetchall():
        texts = r[2:]
        yield r[0], r[1], texts, [t for t, c in zip(texts, columns) if c not in NOT_PAY_CHECKED]


def measure(conn, since: str, factory: Optional[str] = None) -> Tuple[int, Dict[str, Any]]:
    """Returns ``(exit_code, report)``. ``conn`` is switched to a read-only session first."""
    conn.set_session(readonly=True, autocommit=False)
    try:
        with conn.cursor() as cur:
            cur.execute("SHOW transaction_read_only")
            if cur.fetchone()[0] != "on":
                return 2, {"error": "session is not read-only"}
            directory = read_directory(cur)
            if not any(directory.values()):
                return 2, {"error": "the people / customer directory is empty: nothing to compare with"}
            broken = live_control(directory)
            if broken:
                return 2, {"error": "live positive control failed", "factories": sorted(broken)}
            entries: List[Dict[str, Any]] = []
            for table, columns in TABLES:
                entries += tally(table, read_rows(cur, table, columns, since, factory), directory)
    finally:
        conn.rollback()
    scanned = sum(e["rows_scanned"] for e in entries)
    hits = sum(e["rows_with_name"] for e in entries)
    pay_hits = sum(e["rows_with_pay_value"] for e in entries)
    report = {"since": since, "factory": factory, "rows_scanned": scanned,
              "rows_unchecked": sum(e["rows_unchecked"] for e in entries), "rows_with_name": hits,
              "pay_rows_scanned": sum(e["pay_rows_scanned"] for e in entries), "rows_with_pay_value": pay_hits,
              "tables": entries}
    if hits or pay_hits:
        return 1, report
    if scanned == 0:
        return 2, {**report, "error": "no row compared with a directory: nothing measured"}
    return 0, report


def _connect():
    import psycopg2

    return psycopg2.connect(
        host=os.getenv("POSTGRES_HOST", "localhost"),
        port=int(os.getenv("POSTGRES_PORT", "5432")),
        dbname=os.getenv("POSTGRES_DB", "cretas_db"),
        user=os.getenv("POSTGRES_USER", "cretas_user"),
        password=os.getenv("POSTGRES_PASSWORD", ""),
        connect_timeout=10,
    )


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--since", help="rows created at or after this time, e.g. '2026-11-04 12:00:00'")
    ap.add_argument("--factory", help="only this factory")
    ap.add_argument("--self-test", action="store_true", help="positive and negative controls only, no database")
    args = ap.parse_args(argv)

    failures = self_test()
    if failures:
        print(json.dumps({"self_test": "FAILED", "failures": failures}, ensure_ascii=False, indent=2))
        return 2
    if args.self_test:
        print(json.dumps({"self_test": "ok"}))
        return 0
    if not args.since:
        ap.error("--since is required (or --self-test)")
    try:
        conn = _connect()
        try:
            code, report = measure(conn, args.since, args.factory)
        finally:
            conn.close()
    except Exception as e:  # noqa: BLE001 - not measured is its own exit code
        print(json.dumps({"error": f"not measured: {type(e).__name__}"}))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
