"""Unit tests for the synthesis demand-signal report (read-only helper logic)."""
import importlib


mod = importlib.import_module("scripts.synthesis_family_report")


def test_build_candidates_uses_stored_family():
    rows = [
        {"query": "哪家店拖后腿", "family": "attribution", "occurrence_count": 3,
         "business_type": "restaurant", "last_seen": None},
    ]
    cands = mod._build_candidates(rows)
    assert cands[0]["family"] == "attribution"
    assert cands[0]["occurrence_count"] == 3


def test_build_candidates_falls_back_to_classifier_when_family_missing():
    # legacy row without a stored question_family → classify from the query text
    rows = [
        {"query": "帮我建个领料单", "family": None, "occurrence_count": 1,
         "business_type": "restaurant", "last_seen": None},
        {"query": "这个月营业额多少", "family": None, "occurrence_count": 1,
         "business_type": "restaurant", "last_seen": None},
    ]
    cands = mod._build_candidates(rows)
    fams = {c["query"]: c["family"] for c in cands}
    assert fams["帮我建个领料单"] == "write"
    assert fams["这个月营业额多少"] == "query"


def test_family_breakdown_reused_over_candidates():
    rows = [
        {"query": "为什么利润下降", "family": "attribution", "occurrence_count": 2,
         "business_type": "restaurant", "last_seen": None},
        {"query": "新建盘点单", "family": "write", "occurrence_count": 1,
         "business_type": "restaurant", "last_seen": None},
    ]
    cands = mod._build_candidates(rows)
    bd = mod.family_breakdown(cands)
    assert bd["attribution"] == 1
    assert bd["write"] == 1
    assert bd["query"] == 0


def test_stray_family_clamped_no_keyerror():
    # A row with a family value outside the known set (legacy / future 4th family)
    # must be clamped to "query" so family_breakdown() doesn't KeyError-crash the
    # whole report.
    rows = [
        {"query": "某个问题", "family": "sentiment", "occurrence_count": 1,
         "business_type": "restaurant", "last_seen": None},
    ]
    cands = mod._build_candidates(rows)
    assert cands[0]["family"] == "query"
    bd = mod.family_breakdown(cands)  # must not raise
    assert bd["query"] == 1


# ---- privacy clause 6 + training rule (review 2026-10-29) ----

import pytest  # noqa: E402

_PROTECTED = {"F_A": frozenset({"秘制牛肉干"}), "F_B": frozenset({"祖传卤汁鸭"})}


def _sample(fid, query, answer="原因是原料涨价"):
    return {"factory_id": fid, "query": query, "input_text": f"{query}\n\n【数据上下文】",
            "teacher_output": answer, "family": "attribution",
            "business_type": "factory", "created_at": None}


_SAMPLES = [
    _sample("F_A", "秘制牛肉干利润为什么下降"),                 # own factory's product -> hidden
    _sample(None, "为什么上周退货变多", "祖传卤汁鸭退货最多"),     # no factory, F_B's product -> hidden
    _sample("F006", "秘制牛肉干销量为什么下滑"),                 # not F006's product -> shown
    _sample(None, "为什么毛利率下降"),                          # control, twice -> count 2
    _sample(None, "为什么毛利率下降"),
]


class _Pool:
    def __init__(self, rows):
        self.rows = rows
        self.sql = []

    def acquire(self):
        pool = self

        class _C:
            async def fetch(self, sql, *args):
                pool.sql.append(sql)
                return pool.rows

            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                return False

        return _C()


def _patch_pool(monkeypatch, pool):
    import smartbi.config as cfg

    async def get_pool():
        return pool
    monkeypatch.setattr(cfg, "get_pg_pool", get_pool)


async def test_report_hides_rows_naming_a_process_secret_product(monkeypatch, capsys):
    async def names(*_a, **_k):
        return _PROTECTED

    pool = _Pool(_SAMPLES)
    _patch_pool(monkeypatch, pool)
    monkeypatch.setattr(mod, "all_protected_product_names", names)
    await mod._run(None, min_count=1)
    out = capsys.readouterr().out
    assert "training_rule" in pool.sql[0]                   # usable_rows_sql is applied
    assert "秘制牛肉干利润为什么下降" not in out
    assert "为什么上周退货变多" not in out and "祖传卤汁鸭" not in out
    assert "秘制牛肉干销量为什么下滑" in out                  # F006: not its product
    assert "snapshots=2" in out and "为什么毛利率下降" in out  # control, aggregated in Python
    assert "2 sample(s) naming a process-secret product not shown" in out


async def test_report_aborts_when_the_name_lookup_fails(monkeypatch, capsys):
    async def boom(*_a, **_k):
        raise RuntimeError("cretas db unavailable")

    _patch_pool(monkeypatch, _Pool(_SAMPLES))
    monkeypatch.setattr(mod, "all_protected_product_names", boom)
    with pytest.raises(RuntimeError, match="cretas db unavailable"):
        await mod._run(None, min_count=1)
    assert "为什么毛利率下降" not in capsys.readouterr().out
