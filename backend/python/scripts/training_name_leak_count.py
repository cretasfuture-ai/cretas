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
    ai_intent_configs        keywords (``updated_at >= --since``)
    keyword_effectiveness    keyword

Learned keywords: they are appended to an existing intent row, so ``created_at`` says nothing
about them and this table goes by ``updated_at``, which every JPA save moves. That time belongs to
the row, not to a keyword: a row touched since ``--since`` is read in full, so a hit there may be
an older keyword. ``keyword_effectiveness`` holds the same learned keywords one per row with their
own ``created_at``, so a hit there is dated: read the two together. Production judges one keyword
at a time, so does this script: the JSON list is split first, otherwise a word in one keyword and
a number in the next would read as a pay value.
Learning writes only a factory's own rows; platform rows (no factory) have no directory and show
up as ``rows_unchecked``.

Global rows: a row with no owning factory (empty, blank or ``*``, as Java ``isGlobalFactory``) has no
directory of its own and is ``rows_unchecked``. ``--global-union`` compares those rows with the
union of every factory's directory instead, count only, masked rows included. Such a row cannot
be attributed to a factory, so a hit is a possible name and never a proven one, and it is not
``rows_with_name``: it has its own columns (``global_rows_compared``, ``rows_with_possible_name``,
``possible_name_row_ids``, and ``possible_masked_name_row_ids`` for the part that matched a masked
form). A hit exits 1 like any other. Rows with a factory are still compared with their own
directory only. ``--factory`` is refused with it: that filter would drop every global row.

Time: ``--since`` is GMT+8 wall-clock time, whatever the database session zone is. A
``timestamp`` column is compared as it stands (Java stores ``LocalDateTime``, the wall clock of
the GMT+8 service); a ``timestamptz`` column is compared with that wall clock converted at
Asia/Shanghai. Any other column type stops the run. The report opens with the session zone, the
database clock in GMT+8, and per table the column read, its type and its min / max, so a shifted
clock can be seen. A ``--since`` with an offset is refused: a ``::timestamp`` cast would drop it.

Matchers: the production ones. Names: ``directory_by_factory`` over ``ALL_FACTORY_DIRECTORY_SQL``
and ``mentions_any`` (same verdicts as Java ``ProcessSecretTerms``: the shared case file runs
both); a row is only compared with its OWN factory's directory. Pay / attendance:
``pay_word_with_value`` (same verdicts as Java ``TrainingSampleRules.utteranceHasPayValue``), on
the same columns production checks: not ``top_candidates``, whose numbers are intent scores. It
needs no directory, so it also runs on rows whose factory has none.

Masked rows: a factory without consent stores its rows through ``mask_numbers``, so the stored
text says 「本月工资 <数值> 元」 and 「<数值>个核桃」, and neither matcher can see that as it stands.
The pay rule runs on the text with every mask token put back as a digit (``unmasked``); the four
tokens are the four things ``mask_numbers`` writes, and the shared cases pin that the verdict
survives the round trip. A name cannot be put back, so it is compared the other way round: a
numeral inside a directory name also matches a ``<数值>`` in the text (``masked_name_regex``).
The original digits are gone, so such a hit is a possible name, not a proven one: it counts in
``rows_with_name`` and exits 1 like any other, and ``rows_with_masked_name`` /
``masked_name_row_ids`` tell it apart for review. A name that is nothing but numerals (三十) masks
to the bare token, which every masked row contains: it cannot be compared on a masked row and is
counted in ``names_lost_when_masked``.

    python scripts/training_name_leak_count.py --self-test
    python scripts/training_name_leak_count.py --since '2026-11-04 12:00:00' [--factory F006]
    python scripts/training_name_leak_count.py --since '2026-11-04 12:00:00' --global-union

Needs POSTGRES_HOST / POSTGRES_PORT / POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD for the
cretas database. Every real run repeats the self-test first, plus a live positive control on
the directory it just loaded.

Exit codes: 0 = rows were scanned and none contains a name or a pay value; 1 = some do (ids in
the report); 2 = nothing measured: the self-test failed, the database is unreadable or not
read-only, the directory is empty, or no row was compared with a directory. A window with no rows
at all is also 2, but it is not a failure: the report says ``no_rows_in_window: true`` with a
``note`` (also on stderr), against an ``error`` for rows that exist and could not be compared. A 0 needs
``rows_scanned`` > 0 to mean anything: rows whose factory has no directory are reported as
``rows_unchecked``, never as clean, and a non-zero ``rows_unchecked`` is listed under ``warnings``
(also on stderr) with the table and factory. A 0 with warnings means "clean where compared".
``rows_scanned`` / ``rows_unchecked`` are about names; ``pay_rows_scanned`` counts every row the
pay rule read.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime
from typing import Any, Dict, FrozenSet, Iterable, List, Mapping, Optional, Pattern, Sequence, Tuple

_PYTHON_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from common import training_values as tv  # noqa: E402
from common.process_secret_terms import fold  # noqa: E402
from smartbi.services import training_consent as tc  # noqa: E402

# table, the column --since applies to, text expressions (jsonb columns are read as their text form)
TABLES: Tuple[Tuple[str, str, Tuple[str, ...]], ...] = (
    ("ai_training_samples", "created_at", ("user_input",)),
    ("active_learning_samples", "created_at",
     ("user_input", "normalized_input", "matched_keywords::text", "top_candidates::text")),
    ("ai_learned_expressions", "created_at", ("expression",)),
    ("ai_intent_configs", "updated_at", ("keywords::text",)),
    ("keyword_effectiveness", "created_at", ("keyword",)),
)

# One stored JSON list; production judges it one item at a time
LIST_COLUMNS = frozenset({"keywords::text"})

SINCE_ZONE = "Asia/Shanghai"
GMT8_SECONDS = 8 * 3600
# column type -> (the --since comparison, the column as GMT+8 wall clock). A timestamptz compared
# with a bare ::timestamp is converted at the SESSION zone, which is the bug this table prevents.
TIME_COLUMN_SQL: Mapping[str, Tuple[str, str]] = {
    "timestamp without time zone": ("{c} >= %s::timestamp", "{c}"),
    "timestamp with time zone": (f"{{c}} >= (%s::timestamp AT TIME ZONE '{SINCE_ZONE}')",
                                 f"{{c}} AT TIME ZONE '{SINCE_ZONE}'"),
}

# Production does not run the pay rule on these: the numbers in them are intent scores
NOT_PAY_CHECKED = frozenset({"top_candidates::text"})

# id, factory_id, texts for the name check, texts for the pay rule
Row = Tuple[Any, Optional[str], Sequence[Optional[str]], Sequence[Optional[str]]]

VALUE_TOKEN = "<数值>"
# Everything mask_numbers writes in place of digits; all four were a value to the pay rule
_MASK_TOKEN_RE = re.compile("<(?:数值|电话|证件号|卡号)>")
# What mask_values can take out of a name; ratios and fractions first, they are one value
_NUMERAL_RE = re.compile("|".join(r.pattern for r in (tv.CN_RATIO_RE, tv.CN_FRACTION_RE,
                                                      tv.DIGIT_NUM_RE, tv.CN_NUM_RE)))
_ASCII_WORD = "[a-z0-9_]"


def unmasked(text: Optional[str]) -> Optional[str]:
    """``text`` with every mask token back as a digit, for the pay rule."""
    return _MASK_TOKEN_RE.sub("1", text) if text else text


def masked_name_regex(names: Iterable[str]) -> Tuple[Optional[Pattern[str]], int]:
    """``(regex, lost)``: the regex finds a directory name whose numerals a mask may have replaced
    (``names`` are folded, as ``directory_by_factory`` returns them); ``lost`` counts the names
    that are numerals only and so cannot be compared on a masked row. Names without a numeral
    are not in the regex: masking leaves them alone and ``mentions_any`` already reads them.
    The ASCII word boundary is the same as ``mentions_any``."""
    forms: List[str] = []
    lost = 0
    for name in sorted(names):
        parts: List[str] = []
        literal = at = 0
        for m in _NUMERAL_RE.finditer(name):
            literal += m.start() - at
            parts += [re.escape(name[at:m.start()]), f"(?:{re.escape(m.group())}|{VALUE_TOKEN})"]
            at = m.end()
        if not parts:
            continue
        literal += len(name) - at
        if not literal:
            lost += 1
            continue
        head = f"(?<!{_ASCII_WORD})" if re.match(_ASCII_WORD, name) else ""
        tail = f"(?!{_ASCII_WORD})" if re.match(_ASCII_WORD, name[-1]) else ""
        forms.append(head + "".join(parts) + re.escape(name[at:]) + tail)
    return (re.compile("|".join(forms)) if forms else None), lost


def names_masked_form(pattern: Optional[Pattern[str]], texts: Sequence[Optional[str]]) -> bool:
    """A name in masked form: only a text that really holds the token can count."""
    return pattern is not None and any(t and VALUE_TOKEN in t and pattern.search(fold(t)) for t in texts)


def is_global_row(factory_id: Optional[str]) -> bool:
    """No owning factory: empty, blank or ``*`` (same as Java ``LearningContentRules.isGlobalFactory``)."""
    return not factory_id or not factory_id.strip() or factory_id.strip() == "*"


def union_directory(directory: Mapping[str, FrozenSet[str]]) -> FrozenSet[str]:
    return frozenset(name for names in directory.values() for name in names)


def tally(table: str, rows: Iterable[Row], directory: Mapping[str, FrozenSet[str]],
          union: Optional[FrozenSet[str]] = None) -> List[Dict[str, Any]]:
    """Per-factory counts for one table. A row is compared with its own factory's directory; the
    pay rule needs no directory and reads every row. Masked rows are read too (module docstring).
    ``union`` (``--global-union``): rows with no owning factory are compared with it instead of
    being left unchecked; rows with a factory never are."""
    per: Dict[Optional[str], Dict[str, Any]] = {}
    masked: Dict[Optional[str], Optional[Pattern[str]]] = {}
    union_regex = masked_name_regex(union)[0] if union else None
    for row_id, factory_id, texts, pay_texts in rows:
        names = directory.get(factory_id) if factory_id else None
        entry = per.get(factory_id)
        if entry is None:
            masked[factory_id], lost = masked_name_regex(names or ())
            entry = per[factory_id] = {"table": table, "factory_id": factory_id, "directory_size": len(names or ()),
                                       "names_lost_when_masked": lost,
                                       "rows_scanned": 0, "rows_unchecked": 0, "rows_with_name": 0, "row_ids": [],
                                       "rows_with_masked_name": 0, "masked_name_row_ids": [],
                                       "global_rows_compared": 0, "rows_with_possible_name": 0,
                                       "possible_name_row_ids": [], "possible_masked_name_row_ids": [],
                                       "pay_rows_scanned": 0, "rows_with_pay_value": 0, "pay_row_ids": []}
        entry["pay_rows_scanned"] += 1
        if tc.pay_word_with_value(*(unmasked(t) for t in pay_texts)):
            entry["rows_with_pay_value"] += 1
            entry["pay_row_ids"].append(str(row_id))
        if union and is_global_row(factory_id):
            entry["global_rows_compared"] += 1
            in_masked_form = not tc.mentions_any(union, *texts) and names_masked_form(union_regex, texts)
            if in_masked_form or tc.mentions_any(union, *texts):
                entry["rows_with_possible_name"] += 1
                entry["possible_name_row_ids"].append(str(row_id))
            if in_masked_form:
                entry["possible_masked_name_row_ids"].append(str(row_id))
            continue
        if not names:
            entry["rows_unchecked"] += 1
            continue
        entry["rows_scanned"] += 1
        if tc.mentions_any(names, *texts):
            entry["rows_with_name"] += 1
            entry["row_ids"].append(str(row_id))
        elif names_masked_form(masked[factory_id], texts):
            entry["rows_with_name"] += 1
            entry["row_ids"].append(str(row_id))
            entry["rows_with_masked_name"] += 1
            entry["masked_name_row_ids"].append(str(row_id))
    return [per[k] for k in sorted(per, key=lambda f: f or "")]


def self_test() -> List[str]:
    """Known answers through the same code a real run uses: a synthetic row carrying a directory
    name must hit, a clean row must not, and another factory's name must not; a pay / attendance
    word with a number must hit the pay column, the word alone must not, and neither must a score
    in a column production does not check. The same on masked rows: a masked pay value and a
    masked name must hit, a masked row with neither must not, not even with a numerals-only name
    in the directory. Returns the failures."""
    directory = tc.directory_by_factory([("F_A", "测试甲某"), ("F_A", "测试客户有限公司"), ("F_A", "六个测试核桃"),
                                         ("F_A", "三十"), ("F_B", "测试乙某")])
    report = tally("self_test", [
        ("person", "F_A", ("今天测试甲某报工", None), ("今天测试甲某报工", None)),
        ("customer", "F_A", (None, "[\"测试客户有限公司\"]"), (None, "[\"测试客户有限公司\"]")),
        ("clean", "F_A", ("今天产量多少",), ("今天产量多少",)),
        ("other_factory", "F_A", ("测试乙某今天报工",), ("测试乙某今天报工",)),
        ("pay_value", "F_A", ("本月工资 8000 元",), ("本月工资 8000 元",)),
        ("attendance_value", "F_A", (None, "考勤异常 3 人次"), (None, "考勤异常 3 人次")),
        ("pay_word_only", "F_A", ("查一下本月工资",), ("查一下本月工资",)),
        ("score_column", "F_A", ("考勤 0.81",), ()),
        ("masked_pay", "F_A", ("本月工资 <数值> 元",), ("本月工资 <数值> 元",)),
        ("masked_card", "F_A", ("工资卡 <卡号>",), ("工资卡 <卡号>",)),
        ("masked_name", "F_A", ("<数值>个测试核桃发了多少",), ("<数值>个测试核桃发了多少",)),
        ("masked_clean", "F_A", ("今天入库 <数值> 箱",), ("今天入库 <数值> 箱",)),
        ("masked_far_word", "F_A", ("查一下本月工资。今天入库 <数值> 箱",), ("查一下本月工资。今天入库 <数值> 箱",)),
        ("no_directory", "F_C", ("测试甲某",), ("测试甲某",)),
        ("no_directory_pay", "F_C", ("工资 8000",), ("工资 8000",)),
    ], directory)
    by_factory = {e["factory_id"]: e for e in report}
    failures = []
    a = by_factory.get("F_A", {})
    if sorted(a.get("row_ids", [])) != ["customer", "masked_name", "person"]:
        failures.append(f"F_A hits {a.get('row_ids')} != ['customer', 'masked_name', 'person']")
    if a.get("masked_name_row_ids") != ["masked_name"]:
        failures.append(f"F_A masked hits {a.get('masked_name_row_ids')} != ['masked_name']")
    if a.get("names_lost_when_masked") != 1:
        failures.append(f"F_A names_lost_when_masked {a.get('names_lost_when_masked')} != 1")
    if a.get("rows_scanned") != 13:
        failures.append(f"F_A rows_scanned {a.get('rows_scanned')} != 13")
    if sorted(a.get("pay_row_ids", [])) != ["attendance_value", "masked_card", "masked_pay", "pay_value"]:
        failures.append(f"F_A pay hits {a.get('pay_row_ids')} != ['attendance_value', 'masked_card', 'masked_pay', 'pay_value']")
    c = by_factory.get("F_C", {})
    if c.get("rows_unchecked") != 2:
        failures.append("a row without a directory was not reported as unchecked")
    if c.get("pay_row_ids") != ["no_directory_pay"]:
        failures.append(f"F_C pay hits {c.get('pay_row_ids')} != ['no_directory_pay']")
    return failures + self_test_global(directory)


def self_test_global(directory: Mapping[str, FrozenSet[str]]) -> List[str]:
    """``--global-union``: a global row naming anyone in any factory is a possible hit, also in
    masked form; a clean or masked clean global row is not; a row with a factory is still compared
    with its own directory only; without the union nothing is compared."""
    rows = [("g_person", None, ("测试甲某报工",), ()), ("g_other_factory", "*", ("测试乙某报工",), ()),
            ("g_masked", " ", ("<数值>个测试核桃发了多少",), ()), ("g_clean", None, ("今天产量多少",), ()),
            ("g_masked_clean", None, ("今天入库 <数值> 箱",), ()), ("own", "F_A", ("测试乙某报工",), ())]
    report = tally("self_test_global", rows, directory, union_directory(directory))
    failures = []
    possible = sorted(i for e in report for i in e["possible_name_row_ids"])
    if possible != ["g_masked", "g_other_factory", "g_person"]:
        failures.append(f"global possible hits {possible} != ['g_masked', 'g_other_factory', 'g_person']")
    if sorted(i for e in report for i in e["possible_masked_name_row_ids"]) != ["g_masked"]:
        failures.append("global masked hit not told apart")
    if sum(e["global_rows_compared"] for e in report) != 5:
        failures.append("global rows compared != 5")
    if any(e["row_ids"] or e["rows_unchecked"] for e in report):
        failures.append("a global row or the own-factory row landed in the proven / unchecked columns")
    plain = tally("self_test_global", rows, directory)
    if any(e["possible_name_row_ids"] for e in plain) or sum(e["rows_unchecked"] for e in plain) != 5:
        failures.append("without --global-union a global row must stay unchecked")
    return failures


def live_control(directory: Mapping[str, FrozenSet[str]]) -> List[str]:
    """Every loaded factory directory must match one of its own names inside a sentence. Reports
    factory ids only, never the name."""
    return [f for f, names in directory.items()
            if names and not tc.mentions_any(names, "前缀" + next(iter(names)) + "后缀")]


def list_items(text: Optional[str]) -> List[Optional[str]]:
    """The items of a stored JSON list. Anything else stays one text: it must still be read,
    an unparsable value is not an empty one."""
    if not text:
        return []
    try:
        value = json.loads(text)
    except ValueError:
        return [text]
    return [item if isinstance(item, str) else json.dumps(item, ensure_ascii=False) for item in value] \
        if isinstance(value, list) else [text]


def read_directory(cur) -> Dict[str, FrozenSet[str]]:
    cur.execute(tc.ALL_FACTORY_DIRECTORY_SQL)
    return tc.directory_by_factory(cur.fetchall())


def read_clock(cur) -> Dict[str, Any]:
    cur.execute(f"SELECT current_setting('TimeZone'), EXTRACT(TIMEZONE FROM now())::int, "
                f"(now() AT TIME ZONE '{SINCE_ZONE}')::text")
    zone, offset, now = cur.fetchone()
    return {"session_time_zone": zone, "session_utc_offset_seconds": offset, "now_gmt8": now,
            "since_is": f"GMT+8 wall clock ({SINCE_ZONE})"}


def time_column_type(cur, table: str, column: str) -> str:
    cur.execute("SELECT atttypid::regtype::text FROM pg_attribute "
                "WHERE attrelid = %s::regclass AND attname = %s AND NOT attisdropped", (table, column))
    row = cur.fetchone()
    if row is None or row[0] not in TIME_COLUMN_SQL:
        raise ValueError(f"{table}.{column}: cannot compare --since with column type {row and row[0]}")
    return row[0]


def read_time_column(cur, table: str, column: str, column_type: str, factory: Optional[str]) -> Dict[str, Any]:
    shown = TIME_COLUMN_SQL[column_type][1].format(c=column)
    sql = f"SELECT min({shown})::text, max({shown})::text FROM {table}"
    cur.execute(sql + (" WHERE factory_id = %s" if factory else ""), [factory] if factory else [])
    low, high = cur.fetchone()
    return {"table": table, "column": column, "column_type": column_type, "min_gmt8": low, "max_gmt8": high}


def read_rows(cur, table: str, time_column: str, column_type: str, columns: Sequence[str], since: str,
              factory: Optional[str]) -> Iterable[Row]:
    since_sql = TIME_COLUMN_SQL[column_type][0].format(c=time_column)
    sql = f"SELECT id::text, factory_id, {', '.join(columns)} FROM {table} WHERE {since_sql}"
    args: List[Any] = [since]
    if factory:
        sql += " AND factory_id = %s"
        args.append(factory)
    cur.execute(sql + " ORDER BY id", args)
    for r in cur.fetchall():
        texts: List[Optional[str]] = []
        pay_texts: List[Optional[str]] = []
        for text, column in zip(r[2:], columns):
            items = list_items(text) if column in LIST_COLUMNS else [text]
            texts += items
            if column not in NOT_PAY_CHECKED:
                pay_texts += items
        yield r[0], r[1], texts, pay_texts


NO_ROWS_NOTE = ("no rows in the window: exit 2 means nothing was measured, not that the run failed. "
                "Check since and time_columns, or run again once rows exist")


def warnings_for(entries: Sequence[Mapping[str, Any]], clock: Mapping[str, Any]) -> List[str]:
    """What a reader must not miss next to a clean-looking count. Table and factory ids only."""
    out: List[str] = []
    unchecked = [e for e in entries if e["rows_unchecked"]]
    if unchecked:
        where = ", ".join(f"{e['table']}/{e['factory_id'] or 'no factory'}={e['rows_unchecked']}" for e in unchecked)
        out.append(f"rows_unchecked={sum(e['rows_unchecked'] for e in unchecked)}: names were NOT compared on these "
                   f"rows (no directory for their factory), they are not clean: {where}")
    lost = {e["factory_id"]: e["names_lost_when_masked"] for e in entries if e["names_lost_when_masked"]}
    if lost:
        where = ", ".join(f"{f}={n}" for f, n in sorted(lost.items(), key=lambda kv: kv[0] or ""))
        out.append(f"names_lost_when_masked: numerals-only names cannot be compared on masked rows: {where}")
    if clock["session_utc_offset_seconds"] != GMT8_SECONDS:
        out.append(f"database session zone is {clock['session_time_zone']}, not GMT+8: a timestamp column filled by "
                   "a database DEFAULT holds that zone's wall clock, and --since reads it as GMT+8")
    return out


def measure(conn, since: str, factory: Optional[str] = None, global_union: bool = False) -> Tuple[int, Dict[str, Any]]:
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
            union = union_directory(directory) if global_union else None
            clock = read_clock(cur)
            time_columns: List[Dict[str, Any]] = []
            entries: List[Dict[str, Any]] = []
            for table, time_column, columns in TABLES:
                column_type = time_column_type(cur, table, time_column)
                time_columns.append(read_time_column(cur, table, time_column, column_type, factory))
                entries += tally(table, read_rows(cur, table, time_column, column_type, columns, since, factory),
                                 directory, union)
    finally:
        conn.rollback()
    scanned = sum(e["rows_scanned"] for e in entries)
    hits = sum(e["rows_with_name"] for e in entries)
    pay_hits = sum(e["rows_with_pay_value"] for e in entries)
    possible = sum(e["rows_with_possible_name"] for e in entries)
    global_compared = sum(e["global_rows_compared"] for e in entries)
    report = {"database": clock, "time_columns": time_columns, "warnings": warnings_for(entries, clock),
              "since": since, "factory": factory, "rows_scanned": scanned,
              "rows_unchecked": sum(e["rows_unchecked"] for e in entries), "rows_with_name": hits,
              "rows_with_masked_name": sum(e["rows_with_masked_name"] for e in entries),
              "global_union": global_union, "union_directory_size": len(union or ()),
              "union_names_lost_when_masked": masked_name_regex(union or ())[1],
              "global_rows_compared": global_compared, "rows_with_possible_name": possible,
              "rows_with_possible_masked_name": sum(len(e["possible_masked_name_row_ids"]) for e in entries),
              "pay_rows_scanned": sum(e["pay_rows_scanned"] for e in entries), "rows_with_pay_value": pay_hits,
              "tables": entries}
    if hits or pay_hits or possible:
        return 1, report
    if report["pay_rows_scanned"] == 0:     # every row read counts here, with a directory or not
        return 2, {**report, "no_rows_in_window": True, "note": NO_ROWS_NOTE}
    if scanned == 0 and global_compared == 0:
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


def since_gmt8(value: str) -> str:
    """``--since``: a date / time without an offset (a ``::timestamp`` cast would silently drop it)."""
    try:
        parsed = datetime.fromisoformat(value.strip())
    except ValueError:
        raise argparse.ArgumentTypeError(f"not a date / time: {value!r}") from None
    if parsed.tzinfo is not None:
        raise argparse.ArgumentTypeError(f"no offset please, --since is GMT+8 wall clock: {value!r}")
    return value.strip()


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--since", type=since_gmt8,
                    help="GMT+8 wall-clock time, whatever the database session zone is, no offset: rows created "
                         "(intent keywords: updated) at or after it, e.g. '2026-11-04 12:00:00'")
    ap.add_argument("--factory", help="only this factory")
    ap.add_argument("--global-union", action="store_true",
                    help="compare rows with no owning factory with the union of every factory's people and customers: "
                         "count only, a hit is a possible name, in its own columns; not with --factory")
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
    if args.global_union and args.factory:
        ap.error("--global-union reads rows with no factory: --factory would drop all of them")
    try:
        conn = _connect()
        try:
            code, report = measure(conn, args.since, args.factory, args.global_union)
        finally:
            conn.close()
    except Exception as e:  # noqa: BLE001 - not measured is its own exit code
        print(json.dumps({"error": f"not measured: {type(e).__name__}"}))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    for warning in report.get("warnings", ()):
        print(f"WARNING: {warning}", file=sys.stderr)
    if report.get("note"):
        print(f"NOTE: {report['note']}", file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
