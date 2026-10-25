"""Intent fine-tuning / labeling scripts apply the 2026-10-24 utterance rule.

scripts/finetune/{export_training_data,shadow_adjudicate,zpd_label_batch}.py read
intent_match_records across tenants and send user text into training files or to
an outside LLM. Every row goes through training_consent_gate.apply_utterance_rule:
recipe / process-parameter utterances are dropped for every factory; authorized
factories keep raw text; everyone else has numbers / identifiers masked; an
unreadable consent table means "nobody is authorized", never the reverse.
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
    gate.consent_map.cache_clear()
    yield
    gate.consent_map.cache_clear()


ROWS = [
    {"factory_id": "F_PRIV", "user_input": "今天入库 350 箱"},
    {"factory_id": "F006", "user_input": "今天入库 350 箱，联系 13812345678"},
    {"factory_id": "F006", "user_input": "这个产品配方里盐放多少"},
    {"factory_id": "F_PRIV", "user_input": "杀菌温度调到多少"},
    {"factory_id": None, "user_input": "我们腌制和滚揉一起做 2 次"},
]


def test_rule_drops_parameter_content_and_masks_unauthorized_factories(monkeypatch):
    monkeypatch.setattr(gate, "_connect", lambda: _Conn([("F_PRIV", "private"), ("F006", "none")]))
    kept = gate.apply_utterance_rule(ROWS, ("user_input",))
    assert [r["user_input"] for r in kept] == [
        "今天入库 350 箱",                       # authorized: raw
        "今天入库 <数值> 箱，联系 <电话>",        # un-authorized: masked
        "我们腌制和滚揉一起做 <数值> 次",          # step words allowed in utterances
    ]


def test_unreadable_consent_table_stops_the_script(monkeypatch):
    def boom():
        raise RuntimeError("connection refused")

    monkeypatch.setattr(gate, "_connect", boom)
    with pytest.raises(SystemExit) as e:
        gate.apply_utterance_rule(ROWS[:1], ("user_input",))
    assert "refusing to read factory data" in str(e.value)


def test_withdrawn_factory_rows_are_dropped(monkeypatch):
    monkeypatch.setattr(gate, "_connect", lambda: _Conn([("F_PRIV", "withdrawn")]))
    kept = gate.apply_utterance_rule(ROWS[:1] + [{"factory_id": "F006", "user_input": "库存多少"}],
                                     ("user_input",))
    assert [r["factory_id"] for r in kept] == ["F006"]


@pytest.mark.parametrize("module_name, call", [
    ("export_training_data", lambda m, c: m.fetch_shadow_disagreements(c, 30)),
    ("export_training_data", lambda m, c: m.fetch_user_confirmed(c, 30)),
    ("shadow_adjudicate", lambda m, c: m.fetch_shadow_disagreements(c, 30)),
    ("zpd_label_batch", lambda m, c: m.fetch_zpd_records(c, 30, limit=5)),
])
def test_every_intent_record_query_goes_through_the_rule(monkeypatch, module_name, call):
    module = importlib.import_module(module_name)
    seen = []

    def fake_rule(rows, keys):
        rows = list(rows)
        seen.append((rows, keys))
        return []

    monkeypatch.setattr(module, "apply_utterance_rule", fake_rule)
    raw = [{"factory_id": "F006", "user_input": "配方", "shadow_intent_code": "X",
            "shadow_confidence": 0.9, "matched_intent_code": "X", "user_selected_intent": None}]
    conn = _Conn(raw)
    out = call(module, conn)
    sql, _ = conn.calls[0]
    assert "intent_match_records" in sql
    assert "factory_id" in sql, "the rule needs each row's factory"
    assert len(seen) == 1 and seen[0][0][0]["user_input"] == "配方"
    assert "user_input" in seen[0][1]
    assert not out, "rows the rule drops never reach the caller"
