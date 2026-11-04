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
import json
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


class _CretasCursor:
    """The cretas DB as the gate reads it: protected products (with policy aliases) and the
    people / customer directory, keyed by which SQL ran."""

    def __init__(self, products, people):
        self._products, self._people = products, people
        self._rows, self.description = [], []

    def execute(self, sql, params=None):
        if "process_secret_policies" in sql:
            cols = ["factory_id", "id", "code", "name", "category", "product_category", "aliases"]
            self._rows = [tuple(p.get(c) for c in cols) for p in self._products]
        else:
            cols = ["factory_id", "name"]
            self._rows = list(self._people)
        self.description = [(c,) for c in cols]

    def fetchall(self):
        return self._rows

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class _CretasConn:
    def __init__(self, products=(), people=()):
        self._products, self._people = list(products), list(people)

    def cursor(self, *a, **kw):
        return _CretasCursor(self._products, self._people)

    def close(self):
        pass


@pytest.fixture(autouse=True)
def _fresh_cache(monkeypatch):
    gate.consent_map.cache_clear()
    gate.factory_lists_map.cache_clear()
    # factory lists come from the cretas DB: none by default (tests set their own)
    monkeypatch.setattr(gate, "_connect_cretas", lambda: _CretasConn())
    yield
    gate.consent_map.cache_clear()
    gate.factory_lists_map.cache_clear()


def test_the_factorys_own_people_are_dropped_and_others_kept(monkeypatch):
    """2026-10-29 option A: names are matched exactly against each factory's own tables."""
    monkeypatch.setattr(gate, "_connect", lambda: _Conn([]))
    monkeypatch.setattr(gate, "_connect_cretas", lambda: _CretasConn(people=[("F006", "王五"), ("F007", "华联超市")]))
    rows = [{"factory_id": "F006", "user_input": "操作员王五报工"},
            {"factory_id": "F007", "user_input": "操作员王五报工"},       # not F007's person -> kept
            {"factory_id": "F007", "user_input": "华联超市还欠多少"},
            {"factory_id": "F006", "user_input": "员工周报在哪看"}]         # names nobody -> kept
    kept = gate.apply_utterance_rule(rows, ("user_input",))
    assert [(r["factory_id"], r["user_input"]) for r in kept] == [
        ("F007", "操作员王五报工"), ("F006", "员工周报在哪看")]


def test_process_secret_terms_tenants_and_missing_factories_are_dropped(monkeypatch):
    """2026-11-03 review B2: the gate applies the same lists and tenant rules as capture/export."""
    monkeypatch.setattr(gate, "_connect", lambda: _Conn([]))
    products = [{"factory_id": "F006", "id": "PT_F006_LSM_61a43292299498bb", "code": "PTSHHPSKGYLYXGS0124",
                 "name": "398礼盒", "category": "原切", "product_category": None,
                 "aliases": "[\"六膳门礼盒\"]"}]
    monkeypatch.setattr(gate, "_connect_cretas", lambda: _CretasConn(products=products))
    rows = [{"factory_id": "F006", "user_input": "398礼盒的配方改一下"},     # secret full name -> out
            {"factory_id": "F006", "user_input": "六膳门礼盒发了多少"},       # registered alias -> out
            {"factory_id": "F006", "user_input": "PTSHHPSKGYLYXGS0124 库存"},  # code -> out
            {"factory_id": "MOCK_REST", "user_input": "今天卖了多少"},        # restaurant tenant -> out
            {"factory_id": "QATEST_1021_001", "user_input": "今天卖了多少"},  # test tenant -> out
            {"factory_id": None, "user_input": "今天卖了多少"},               # no factory -> out
            {"factory_id": "", "user_input": "今天卖了多少"},
            {"factory_id": "F006", "user_input": "398礼盒今天发了多少"},      # name check alone (no process word) -> out
            {"factory_id": "F007", "user_input": "398礼盒今天发了多少"},      # one model for all: F006's product -> out
            {"factory_id": "F007", "user_input": "今天发了多少礼盒"},         # nobody's term -> kept
            {"factory_id": "F006", "user_input": "批次 398 今天完工了吗"}]   # pure-numeric name not collected -> kept
    kept = gate.apply_utterance_rule(rows, ("user_input",))
    assert [(r["factory_id"], r["user_input"]) for r in kept] == [
        ("F007", "今天发了多少礼盒"), ("F006", "批次 <数值> 今天完工了吗")]


_PROTECTED = [{"factory_id": "F006", "id": "p1", "code": "C-1", "name": "398礼盒", "category": None,
               "product_category": None, "aliases": None},
              {"factory_id": "F006", "id": "p2", "code": "C-2", "name": "老卤牛肉2号", "category": None,
               "product_category": None, "aliases": None}]


def _export_files(monkeypatch, tmp_path, lines):
    """export_training_data.main with its two file sources and the database down."""
    export = importlib.import_module("export_training_data")

    def no_db():
        raise export.psycopg2.OperationalError("db down")

    monkeypatch.setattr(export, "get_db_connection", no_db)
    source = tmp_path / "labeled.jsonl"
    source.write_text("\n".join(lines), encoding="utf-8")
    out = tmp_path / "merged.jsonl"
    monkeypatch.setattr(sys, "argv", ["export", "--output", str(out), "--zpd-file", str(source),
                                      "--adjudicated-file", str(tmp_path / "none.jsonl")])
    export.main()
    return out


def test_labeled_files_cannot_carry_a_protected_term_into_the_shared_training_file(monkeypatch, tmp_path):
    """They carry no factory and skip the utterance rule, so the check is on the way out."""
    monkeypatch.setattr(gate, "_connect_cretas", lambda: _CretasConn(products=_PROTECTED))
    lines = ['{"text": "398礼盒今天发了多少", "label": "A"}', '{"text": "老卤牛肉<数值>号库存", "label": "A"}',
             '{"text": "今天的产量是多少", "label": "A"}']
    out = _export_files(monkeypatch, tmp_path, lines)
    assert [json.loads(line)["text"] for line in out.read_text(encoding="utf-8").splitlines()] == ["今天的产量是多少"]


def test_unreadable_lists_stop_the_export_even_with_only_file_rows(monkeypatch, tmp_path):
    def down():
        raise RuntimeError("cretas db down")

    monkeypatch.setattr(gate, "_connect_cretas", down)
    with pytest.raises(SystemExit) as stopped:
        _export_files(monkeypatch, tmp_path, ['{"text": "今天的产量是多少", "label": "A"}'])
    assert stopped.value.code not in (0, None)
    assert not (tmp_path / "merged.jsonl").exists()


def test_keywords_and_examples_for_the_outside_llm_lose_protected_terms(monkeypatch):
    synth = importlib.import_module("synthesize_weak_class_data")
    monkeypatch.setattr(gate, "_connect_cretas", lambda: _CretasConn(products=_PROTECTED))
    row = ("X", "名称", "说明", json.dumps(["398礼盒", "库存"], ensure_ascii=False),
           json.dumps(["老卤牛肉<数值>号还有多少", "还有多少库存"], ensure_ascii=False))

    class _Cur(_Cursor):
        def fetchall(self):
            return [row]

    class _DbConn:
        def cursor(self):
            return _Cur([])

        def close(self):
            pass

    monkeypatch.setattr(synth.psycopg2, "connect", lambda **_k: _DbConn())
    meta = synth.fetch_intent_metadata(["X"])["X"]
    assert meta["keywords"] == ["库存"] and meta["examples"] == ["还有多少库存"]


def test_no_secrecy_switch_opens_the_gate(monkeypatch):
    """What the gate feeds is one model for every factory, so it keeps the whole rule whatever the
    factory set (item d defaults to off). An authorized factory is the worst case: its text stays raw."""
    seen = []

    class _SpyCursor(_CretasCursor):
        def execute(self, sql, params=None):
            seen.append(sql)
            super().execute(sql, params)

    class _SpyConn(_CretasConn):
        def cursor(self, *a, **kw):
            return _SpyCursor(self._products, self._people)

    product = {"factory_id": "F_D", "id": "p1", "code": "C-1", "name": "秘制牛肉干", "category": None,
               "product_category": None, "aliases": None}
    monkeypatch.setattr(gate, "_connect", lambda: _Conn([("F_D", "private")]))
    monkeypatch.setattr(gate, "_connect_cretas", lambda: _SpyConn(products=[product], people=[("F_D", "王五")]))
    samples = ["本月产量多少", "秘制牛肉干本月销量多少", "这个产品配方里盐放 3 克", "王五本月产量多少", "本月工资 8000 元"]
    kept = gate.apply_utterance_rule([{"factory_id": "F_D", "user_input": t} for t in samples], ("user_input",))
    assert [r["user_input"] for r in kept] == ["本月产量多少"]
    assert any("process_secret_policies" in s for s in seen)          # the spy saw the gate's reads
    assert not any("factory_secrecy_settings" in s for s in seen)


def test_the_export_really_loses_the_factory_so_the_gate_must_not_follow_it():
    """Why the test above holds: if a later change keeps factory_id on the merged records, the
    training file can be partitioned and this reason needs looking at again."""
    source = (_FINETUNE / "export_training_data.py").read_text(encoding="utf-8")
    assert '"text": text,' in source and '"factory_id"' not in source


def test_unreadable_names_stop_the_script(monkeypatch):
    def boom():
        raise RuntimeError("connection refused")

    monkeypatch.setattr(gate, "_connect", lambda: _Conn([]))
    monkeypatch.setattr(gate, "_connect_cretas", boom)
    with pytest.raises(SystemExit) as e:
        gate.apply_utterance_rule(ROWS[:1], ("user_input",))
    assert "factory lists" in str(e.value) and "unreadable" in str(e.value)


ROWS = [
    {"factory_id": "F_PRIV", "user_input": "今天入库 350 箱"},
    {"factory_id": "F006", "user_input": "今天入库 350 箱，联系 13812345678"},
    {"factory_id": "F006", "user_input": "这个产品配方里盐放 3 克"},      # parameter + value
    {"factory_id": "F_PRIV", "user_input": "杀菌温度调到 121 度"},
    {"factory_id": "F006", "user_input": "我们腌制和滚揉一起做 2 次"},
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
