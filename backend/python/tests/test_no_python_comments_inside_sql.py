"""A Python comment inside a SQL string is not a comment — it is query text.

Found 2026-08-28 on the test environment: sales_preset.py carried
``# noqa: E501`` INSIDE two triple-quoted SQL literals, so asyncpg shipped that
text to PostgreSQL and got

    asyncpg.exceptions.PostgresSyntaxError: syntax error at or near ":"

``/sales-preset/daily-report`` and ``/sales-preset/product-rank`` therefore
returned 500 on every single call — they had never worked. The linter directive
was meant to silence a line-length warning; it silenced the endpoint instead.

The gate walks the AST rather than grepping, because a text scan cannot tell a
``#`` inside a string from a real comment on the next line — this repo has been
bitten by grep-shaped gates counting their own comments before.
"""
from __future__ import annotations

import ast
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Only strings actually handed to a DB execution call count. A first version of
# this gate matched any SQL-looking literal and lit up on module docstrings that
# contain usage examples with `#` lines — 4 false positives on the first run.
# A gate that cries wolf gets switched off, and then it guards nothing, so the
# scope is the CALL SITE, not the text.
DB_CALLS = {
    "execute", "executemany", "fetch", "fetchrow", "fetchval", "fetchall",
    "fetchone", "executescript", "text",
}

# A bare "#" is legal inside SQL — it appears in `-- Task #90` comments and in
# string literals like '(已删除食材 #' || id || ')'. Flagging those was the
# gate's second round of false positives. What actually leaks into queries is a
# PYTHON TOOLING DIRECTIVE that someone appended to a long line without noticing
# the line lived inside a triple-quoted SQL string.
PY_DIRECTIVES = (
    "# noqa", "# type:", "# pylint", "# fmt:", "# nosec", "# pragma", "# mypy",
)


def _sql_literals(tree: ast.AST):
    """Yield ``(lineno, sql_text)`` for strings passed to a DB execution call."""
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        fn = node.func
        name = fn.attr if isinstance(fn, ast.Attribute) else getattr(fn, "id", None)
        if name not in DB_CALLS:
            continue
        for arg in node.args:
            if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                yield arg.lineno, arg.value


def test_no_hash_comment_inside_sql_literals() -> None:
    offenders: list[str] = []
    for path in ROOT.rglob("*.py"):
        parts = set(path.parts)
        if parts & {"venv", "venv-current", ".venv", "site-packages", "__pycache__"}:
            continue
        if path.name == Path(__file__).name:
            continue
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        except SyntaxError:
            continue
        for lineno, sql in _sql_literals(tree):
            for i, line in enumerate(sql.splitlines(), start=1):
                if any(d in line for d in PY_DIRECTIVES):
                    offenders.append(
                        f"{path.relative_to(ROOT)}: SQL literal near line "
                        f"{lineno}, text line {i}: {line.strip()[:90]}"
                    )
    assert offenders == [], (
        "Python comment markers found inside SQL string literals — PostgreSQL "
        "will receive them as query text:\n  " + "\n  ".join(offenders)
    )
