"""Intent fine-tuning / labeling scripts only read consented factories (2026-10-22).

scripts/finetune/{export_training_data,shadow_adjudicate,zpd_label_batch}.py read
intent_match_records across tenants and send real user text into training files
or to an outside LLM. Each query must carry the consent clause with the list from
training_consent_gate.consented_factories(), and that list must fail closed.
"""
from __future__ import annotations

import importlib
import sys
from pathlib import Path

import pytest

pytest.importorskip("psycopg2")

_FINETUNE = Path(__file__).resolve().parents[3] / "scripts" / "finetune"
if str(_FINETUNE) not in sys.path:
    sys.path.insert(0, str(_FINETUNE))

gate = importlib.import_module("training_consent_gate")


class _Cursor:
    def __init__(self, calls, rows=()):
        self._calls = calls
        self._rows = list(rows)

    def execute(self, sql, params=None):
        self._calls.append((sql, params))

    def fetchall(self):
        return self._rows

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class _Conn:
    def __init__(self, rows=()):
        self.calls = []
        self._rows = rows

    def cursor(self, *a, **kw):
        return _Cursor(self.calls, self._rows)

    def close(self):
        pass


@pytest.fixture(autouse=True)
def _fresh_cache():
    gate.consented_factories.cache_clear()
    yield
    gate.consented_factories.cache_clear()


def test_only_private_and_shared_anon_factories_are_allowed(monkeypatch):
    rows = [("F_PRIV", "private"), ("F_SHARE", "shared_anon"), ("F_NONE", "none"),
            ("F_ODD", "all-of-it")]
    monkeypatch.setattr(gate, "_connect", lambda: _Conn(rows))
    assert gate.consented_factories() == ["F_PRIV", "F_SHARE"]


def test_unreadable_consent_table_stops_the_script(monkeypatch):
    def boom():
        raise RuntimeError("connection refused")

    monkeypatch.setattr(gate, "_connect", boom)
    with pytest.raises(SystemExit) as e:
        gate.consented_factories()
    assert "refusing to read factory data" in str(e.value)


@pytest.mark.parametrize("module_name, call", [
    ("export_training_data", lambda m, c: m.fetch_shadow_disagreements(c, 30)),
    ("export_training_data", lambda m, c: m.fetch_user_confirmed(c, 30)),
    ("shadow_adjudicate", lambda m, c: m.fetch_shadow_disagreements(c, 30)),
    ("zpd_label_batch", lambda m, c: m.fetch_zpd_records(c, 30, limit=5)),
])
def test_every_intent_record_query_is_limited_to_consented_factories(monkeypatch, module_name, call):
    module = importlib.import_module(module_name)
    monkeypatch.setattr(module, "consented_factories", lambda: ["F_PRIV"])
    conn = _Conn()
    call(module, conn)
    assert len(conn.calls) == 1
    sql, params = conn.calls[0]
    assert "intent_match_records" in sql
    assert gate.CONSENTED_FACTORY_CLAUSE in sql
    # the consent list is bound right after the day window, in placeholder order
    placeholders = sql.count("%s")
    assert placeholders == len(params)
    assert params[1] == ["F_PRIV"]
