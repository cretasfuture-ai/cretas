"""Unit tests for the shared distillation-capture helper.

Covers:
- input_hash idempotence (same inputs → same hash; differs by input_text).
- fire-and-forget contract: NEVER raises, even when the pool's acquire blows up.
- pool=None / disabled-env / empty-input short-circuit cleanly (no write).
- a happy-path INSERT carries the expected positional args.

All DB access is mocked — NO real connection.
"""
from __future__ import annotations

from smartbi.services.distillation_capture import (
    compute_input_hash,
    persist_distillation_sample,
)


# ---- fakes ----

class _RecordingConn:
    def __init__(self, sink, consent=None, consent_error=None):
        self._sink = sink
        self._consent = consent or {}
        self._consent_error = consent_error

    async def fetchval(self, sql, *args):
        assert "training_consent" in sql
        if self._consent_error is not None:
            raise self._consent_error
        return self._consent.get(args[0])

    async def execute(self, sql, *args):
        if sql.lstrip().startswith("DELETE FROM smart_bi_distillation_samples"):
            EXPIRY_CALLS.append((sql, args))       # held-row expiry sweep (its own tests below)
            return "DELETE 0"
        self._sink.append((sql, args))
        return "INSERT 0 1"

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


EXPIRY_CALLS: list = []


class _RecordingPool:
    """Records INSERTs. ``consent`` = {factory_id: consent_scope} rows of training_consent."""

    def __init__(self, sink, consent=None, consent_error=None):
        self._sink = sink
        self._consent = consent
        self._consent_error = consent_error

    def acquire(self):
        return _RecordingConn(self._sink, self._consent, self._consent_error)


# A factory that explicitly authorized training use (scope 'private').
_CONSENTED = {"F_CONSENTED": "private"}


class _ExplodingPool:
    """acquire() raises — simulates a broken / exhausted pool."""

    def acquire(self):
        raise RuntimeError("pool acquire boom")


# ---- input_hash idempotence ----

def test_input_hash_is_deterministic():
    a = compute_input_hash("营业额是多少")
    b = compute_input_hash("营业额是多少")
    assert a == b
    assert len(a) == 64  # sha256 hex


def test_input_hash_differs_by_input_text():
    assert compute_input_hash("问题甲") != compute_input_hash("问题乙")


def test_input_hash_handles_none_safely():
    # never raises; empty string hash is stable
    assert compute_input_hash("") == compute_input_hash(None)  # type: ignore[arg-type]


# ---- fire-and-forget: never raises ----

async def test_never_raises_when_pool_acquire_errors():
    # The whole point: a broken pool must not propagate out of capture.
    await persist_distillation_sample(
        _ExplodingPool(),
        source="chat_qa",
        task_type="qa",
        input_text="问题",
        teacher_output="答案",
    )
    # reaching here (no exception) is the assertion


async def test_none_pool_is_quiet_noop():
    # No pool → quiet return, no error.
    await persist_distillation_sample(
        None,
        source="chat_qa",
        task_type="qa",
        input_text="问题",
        teacher_output="答案",
    )


async def test_empty_input_or_output_skips_write():
    sink: list = []
    pool = _RecordingPool(sink)
    await persist_distillation_sample(
        pool, source="chat_qa", task_type="qa",
        input_text="", teacher_output="答案",
    )
    await persist_distillation_sample(
        pool, source="chat_qa", task_type="qa",
        input_text="问题", teacher_output="",
    )
    assert sink == [], "NOT NULL columns guarded — no INSERT attempted"


async def test_disabled_by_env_skips_write(monkeypatch):
    sink: list = []
    monkeypatch.setenv("SMARTBI_DISTILL_CAPTURE", "0")
    await persist_distillation_sample(
        _RecordingPool(sink),
        source="chat_qa", task_type="qa",
        input_text="问题", teacher_output="答案",
    )
    assert sink == [], "capture disabled by env → no write"


# ---- happy path INSERT ----

async def test_happy_path_inserts_expected_args(monkeypatch):
    sink: list = []
    monkeypatch.setenv("SMARTBI_DISTILL_CAPTURE", "1")
    await persist_distillation_sample(
        _RecordingPool(sink, _CONSENTED),
        source="agent_insight",
        task_type="insight",
        input_text="用户问：本月营收如何",
        teacher_output="本月营收稳健，建议...",
        business_type="factory",
        factory_id="F_CONSENTED",
        system_prompt="你是分析师",
        teacher_model="qwen3-max",
        template_codes=["top_n_by_dim", "monthly_trend"],
        metadata={"slot": "insights"},
    )
    assert len(sink) == 1
    sql, args = sink[0]
    assert "smart_bi_distillation_samples" in sql
    assert "ON CONFLICT (input_hash)" in sql
    # positional args: source, business_type, factory_id, task_type,
    #   template_codes, system_prompt, input_text, teacher_model,
    #   teacher_output, input_hash, quality, metadata
    assert args[0] == "agent_insight"
    assert args[1] == "factory"
    assert args[2] == "F_CONSENTED"
    assert args[3] == "insight"
    assert args[4] == "top_n_by_dim,monthly_trend"
    assert args[6] == "用户问：本月营收如何"
    assert args[7] == "qwen3-max"
    assert args[9] == compute_input_hash("用户问：本月营收如何")
    assert len(args[9]) == 64
    assert args[10] is None  # quality not provided
    assert '"slot"' in args[11]  # metadata json-encoded


async def test_business_type_none_normalized_to_unknown():
    sink: list = []
    await persist_distillation_sample(
        _RecordingPool(sink, _CONSENTED),
        source="chat_qa", task_type="qa",
        input_text="问题", teacher_output="答案",
        business_type=None,  # type: ignore[arg-type]
        factory_id="F_CONSENTED",
    )
    assert len(sink) == 1
    _, args = sink[0]
    assert args[1] == "unknown"


async def test_no_template_codes_and_no_metadata_pass_none():
    # synthetic seed tenant: no rule tags are added, so metadata stays NULL
    sink: list = []
    await persist_distillation_sample(
        _RecordingPool(sink, _CONSENTED),
        source="chat_qa", task_type="qa",
        input_text="问题", teacher_output="答案",
        factory_id="SEED_F",
    )
    assert len(sink) == 1
    _, args = sink[0]
    assert args[4] is None   # template_codes
    assert args[11] is None  # metadata




# ---- training rule (owner, 2026-10-24; content rule 2026-10-29 option A) ----
# A process word with a concrete value / per-person pay / the factory's own people
# and customers / its process-secret terms: never stored, for any factory.
# Everything else is stored: authorized factories raw, everyone else ('none',
# no row) desensitized. A sample without a factory, or of a restaurant / test
# tenant, is not stored at all.

import json as _json  # noqa: E402

from smartbi.services.process_secret_names import FactoryLists, lists_from_rows  # noqa: E402
from smartbi.services.training_consent import RECHECK_KEY, RULE_VERSION  # noqa: E402


async def _capture(pool, **kw):
    kw.setdefault("source", "chat_qa")
    kw.setdefault("task_type", "qa")
    kw.setdefault("input_text", "本月营收多少")
    kw.setdefault("teacher_output", "本月营收 128 万元，环比增长 8%")
    await persist_distillation_sample(pool, **kw)


def _meta(args):
    return _json.loads(args[11]) if args[11] else {}


def _lists(products=(), directory=()):
    return lists_from_rows([{"name": p} for p in products], directory)


def _patch_lists(monkeypatch, by_factory=None, *, error=None, stale=False, calls=None):
    """Stub the capture's lookup: ``by_factory`` = {factory: FactoryLists}."""
    from smartbi.services import distillation_capture as dc

    async def lookup(factory_id, *_a, **_k):
        if calls is not None:
            calls.append(factory_id)
        if error is not None:
            raise error
        return (by_factory or {}).get(factory_id, FactoryLists()), stale

    monkeypatch.setattr(dc, "factory_lists_or_last_good", lookup)


async def test_unauthorized_factory_qa_is_stored_desensitized():
    sink: list = []
    pool = _RecordingPool(sink, {"F_NONE": "none"})
    await _capture(pool, factory_id="F006",
                   input_text="华东工厂本月营收多少，联系 13812345678",
                   teacher_output="华东工厂本月营收 ¥2500000，毛利率 23.5%，环比增长 8%")
    await _capture(pool, factory_id="F_NONE", input_text="乙", teacher_output="丙")
    assert [a[2] for _, a in sink] == ["F006", "F_NONE"]
    _, args = sink[0]
    assert "13812345678" not in args[6] and "<电话>" in args[6]
    assert "华东" not in args[6] and "华东" not in args[8]      # entity placeholders
    # every number masked (Steve 2026-10-24): margins / ratios are business secrets too
    assert args[8] == "工厂A本月营收 ¥<数值>，毛利率 <数值>%，环比增长 <数值>%"
    assert args[9] == compute_input_hash(args[6])                # hash of what is stored
    assert _meta(args) == {"training_rule": RULE_VERSION, "desensitized": True}


async def test_authorized_factory_qa_is_stored_as_is():
    sink: list = []
    pool = _RecordingPool(sink, {"F_PRIV": "private", "F_SHARE": "shared_anon"})
    await _capture(pool, factory_id="F_PRIV", input_text="华东工厂本月营收多少")
    await _capture(pool, factory_id="F_SHARE", input_text="华北工厂本月营收多少")
    assert [a[6] for _, a in sink] == ["华东工厂本月营收多少", "华北工厂本月营收多少"]
    assert all(_meta(a) == {"training_rule": RULE_VERSION} for _, a in sink)


async def test_process_content_with_a_value_is_never_stored_even_when_authorized():
    sink: list = []
    pool = _RecordingPool(sink, {"F_PRIV": "private"})
    cases = [
        ("产品配方表如下：盐 2.5%", "已读取"),                  # parameter + value in input
        ("数据：工序 1 清洗、工序 2 切配", "按顺序执行即可"),     # step order in input
        ("怎么提升出成率", "建议把腌制时间延长到 24 小时再滚揉"),  # detail in answer
        ("为什么最近返工率 12%", "主要是原料批次差"),             # problem handling + value
        ("本月工资总额 35 万", "已统计"),                         # per-person pay with a value (Q2)
    ]
    for q, a in cases:
        await _capture(pool, factory_id="F_PRIV", input_text=q, teacher_output=a)
        await _capture(pool, factory_id="F006", input_text=q, teacher_output=a)
    assert sink == []


async def test_process_words_without_a_value_are_stored():
    # 2026-10-29 option A: the word alone no longer blocks (48.2% of Q&A were blocked)
    sink: list = []
    await _capture(_RecordingPool(sink, {}), factory_id="F006",
                   input_text="为什么最近返工多", teacher_output="主要是原料批次差，建议复核卤制工序")
    assert len(sink) == 1


async def test_generic_process_words_in_the_answer_do_not_block():
    # Steve chose "B": generic 工艺 / 工序 in model advice are ignored
    sink: list = []
    await _capture(_RecordingPool(sink, {}), factory_id="F006",
                   input_text="上周毛利率为什么下降",
                   teacher_output="主要是原料涨价，建议优化工艺、检查工序损耗")
    assert len(sink) == 1


async def test_consent_lookup_error_means_not_stored():
    # an unreadable consent row could hide a withdrawal (clause 10) -> store nothing
    sink: list = []
    pool = _RecordingPool(sink, {"F_PRIV": "private"}, consent_error=RuntimeError("relation does not exist"))
    await _capture(pool, factory_id="F_PRIV", input_text="华东工厂本月营收多少")
    assert sink == []


async def test_sample_without_factory_is_not_stored_and_looks_nothing_up(monkeypatch):
    # 2026-10-29: no factory -> no lists to match against (never the union of all factories)
    calls: list = []
    _patch_lists(monkeypatch, calls=calls)
    sink: list = []
    pool = _RecordingPool(sink, {}, consent_error=AssertionError("no factory -> no lookup"))
    await _capture(pool, input_text="华东工厂本月营收多少")
    await _capture(pool, factory_id="", input_text="华东工厂本月营收多少")
    await _capture(_RecordingPool(sink, {}), factory_id="F006", input_text="华东工厂本月营收多少")  # control
    assert [a[2] for _, a in sink] == ["F006"]
    assert calls == ["F006"]


async def test_restaurant_and_test_tenants_are_not_stored(monkeypatch):
    sink: list = []
    pool = _RecordingPool(sink, {"MOCK_REST": "private"})
    await _capture(pool, factory_id="MOCK_REST")
    await _capture(pool, factory_id="QATEST_1021_001")
    await _capture(pool, factory_id="F006BK")
    await _capture(pool, factory_id="F006", business_type="restaurant")
    await _capture(pool, factory_id="F006", business_type="factory")                # control
    assert [(a[2], a[1]) for _, a in sink] == [("F006", "factory")]


async def test_synthetic_seed_tenants_are_stored_as_is_without_lookup(monkeypatch):
    _patch_lists(monkeypatch, error=AssertionError("must not look up lists"))
    sink: list = []
    pool = _RecordingPool(sink, {}, consent_error=AssertionError("must not look up"))
    await _capture(pool, factory_id="SEED_R", input_text="合成甲：产品配方表")
    assert [a[2] for _, a in sink] == ["SEED_R"]
    assert sink[0][1][11] is None


async def test_request_tenant_wins_and_a_different_factory_id_is_refused():
    from smartbi import tenant_ctx

    sink: list = []
    pool = _RecordingPool(sink, {"F_CONSENTED": "private", "F_OTHER": "private"})
    token = tenant_ctx.set_factory_id("F_CONSENTED")
    try:
        await _capture(pool, factory_id="F_OTHER", input_text="甲")   # refused
        await _capture(pool, input_text="乙")                          # authenticated tenant used
    finally:
        tenant_ctx.reset_factory_id(token)
    assert [a[2] for _, a in sink] == ["F_CONSENTED"]


async def test_the_factorys_own_people_and_customers_are_never_stored(monkeypatch):
    # Steve 2026-10-29 option A: names are matched exactly against the factory's own tables
    _patch_lists(monkeypatch, {"F_PRIV": _lists(directory=["张记食品有限公司", "华联", "李四"])})
    sink: list = []
    pool = _RecordingPool(sink, {"F_PRIV": "private"})
    for q, a in [("张记食品有限公司这个客户回款怎么样", "回款正常"),
                 ("客户：华联 欠款多少", "欠款 12 万元"),
                 ("这批货谁签收的", "签收人：李四")]:
        await _capture(pool, factory_id="F_PRIV", input_text=q, teacher_output=a)   # its own -> out
        await _capture(pool, factory_id="F006", input_text=q, teacher_output=a)     # not F006's -> in
    assert [a[2] for _, a in sink] == ["F006", "F006", "F006"]


async def test_sentences_naming_nobody_are_stored(monkeypatch):
    # the old regex blocked these (role word + surname / company suffix); they name nobody
    _patch_lists(monkeypatch, {"F006": _lists(directory=["王五"])})
    sink: list = []
    for q in ["员工周报在哪看", "我们公司客户有哪些", "作为服务于贵公司的资深分析师"]:
        await _capture(_RecordingPool(sink, {}), factory_id="F006", input_text=q, teacher_output="好的")
    assert len(sink) == 3


async def test_generic_customer_words_are_stored():
    sink: list = []
    await _capture(_RecordingPool(sink, {}), factory_id="F006",
                   input_text="大客户占比多少", teacher_output="前十大客户占比 35%，新客户 12 家")
    assert len(sink) == 1
    assert sink[0][1][8] == "前十大客户占比 <数值>%，新客户 <数值> 家"   # generic words kept, numbers masked


async def test_withdrawn_factory_is_never_stored():
    sink: list = []
    pool = _RecordingPool(sink, {"F_GONE": "withdrawn", "F006": "none"})
    await _capture(pool, factory_id="F_GONE")
    await _capture(pool, factory_id="F006")          # control: same sample, not withdrawn
    assert [a[2] for _, a in sink] == ["F006"]


async def test_sample_naming_a_process_secret_term_is_never_stored(monkeypatch):
    # privacy clause 6: process-secret products are not used for training
    _patch_lists(monkeypatch, {"F_PRIV": _lists(products=["秘制牛肉干"])})
    sink: list = []
    pool = _RecordingPool(sink, {"F_PRIV": "private"})
    await _capture(pool, factory_id="F_PRIV", input_text="秘制牛肉干本月销量多少")
    await _capture(pool, factory_id="F_PRIV", teacher_output="秘制牛肉干本月销量增长")
    await _capture(pool, factory_id="F_PRIV", input_text="普通牛肉干本月销量多少")   # control
    await _capture(pool, factory_id="F006", input_text="秘制牛肉干本月销量多少")     # not F006's product
    assert [(a[2], a[6]) for _, a in sink] == [
        ("F_PRIV", "普通牛肉干本月销量多少"), ("F006", "秘制牛肉干本月销量多少")]


async def test_lists_lookup_failure_without_last_good_lists_means_not_stored(monkeypatch):
    _patch_lists(monkeypatch, error=RuntimeError("cretas db unavailable"))
    sink: list = []
    await _capture(_RecordingPool(sink, {"F_PRIV": "private"}), factory_id="F_PRIV")
    await _capture(_RecordingPool(sink, {}), factory_id="SEED_R")   # synthetic: no lookup, stored
    assert [a[2] for _, a in sink] == ["SEED_R"]


async def test_last_good_lists_fallback_stores_the_row_held(monkeypatch):
    # lookup failed, the last good lists were used: stored, but held for re-judgement
    _patch_lists(monkeypatch, {"F006": _lists(products=["秘制牛肉干"])}, stale=True)
    sink: list = []
    await _capture(_RecordingPool(sink, {}), factory_id="F006", input_text="秘制牛肉干本月销量多少")
    await _capture(_RecordingPool(sink, {}), factory_id="F006", input_text="普通牛肉干本月销量多少")
    assert len(sink) == 1                                   # the stale list still blocks its own names
    # un-authorized factory: the row is stored desensitized, the hold marker keeps the RAW text
    # for the re-judgement (review N3) — it is dropped again on release
    assert _meta(sink[0][1]).get(RECHECK_KEY) == {
        "raw_input": "普通牛肉干本月销量多少", "raw_output": "本月营收 128 万元，环比增长 8%"}
    assert "128" not in sink[0][1][8]
    # second review, item 1: a row still carrying raw text is not stamped desensitized (factory
    # erasure keeps desensitized rows); the release adds the stamp once the raw copy is gone
    from smartbi.services.training_consent import DESENSITIZED_KEY
    assert DESENSITIZED_KEY not in _meta(sink[0][1])


async def test_fresh_lists_do_not_hold_the_row(monkeypatch):
    _patch_lists(monkeypatch, {"F006": _lists(products=["秘制牛肉干"])}, stale=False)
    sink: list = []
    await _capture(_RecordingPool(sink, {}), factory_id="F006", input_text="普通牛肉干本月销量多少")
    assert RECHECK_KEY not in _meta(sink[0][1])


async def test_lists_lookup_runs_before_a_capture_connection_is_taken(monkeypatch):
    from smartbi.services import distillation_capture as dc

    events: list = []

    async def lookup(factory_id, *_a, **_k):
        events.append("lookup")
        return _lists(products=["秘制牛肉干"]), False

    class _OrderPool(_RecordingPool):
        def acquire(self):
            events.append("acquire")
            return super().acquire()

    monkeypatch.setattr(dc, "factory_lists_or_last_good", lookup)
    sink: list = []
    await _capture(_OrderPool(sink, {}), factory_id="F006", input_text="秘制牛肉干本月销量多少")
    await _capture(_OrderPool(sink, {}), factory_id="F006", input_text="普通牛肉干本月销量多少")
    assert events == ["lookup", "lookup", "acquire"]     # named sample never took a connection
    assert len(sink) == 1


def _synthesis_metadata():
    # shape written by synthesis_engine: metadata.query = the raw question
    return {"query": "华东工厂本月营收 2500000 为什么下降", "question_family": "attribution",
            "plan": {"finance": True},
            "grounding": {"clean": False, "reconciled": True, "confidence_adj": -0.1,
                          "violations": ["华东工厂毛利率 23.5% 未在数据中"]}}


async def test_desensitized_sample_does_not_keep_the_raw_question_in_metadata():
    sink: list = []
    pool = _RecordingPool(sink, {"F_PRIV": "private"})
    await _capture(pool, factory_id="F006", input_text="华东工厂本月营收 2500000 为什么下降",
                   metadata=_synthesis_metadata())
    await _capture(pool, factory_id="F_PRIV", input_text="华北工厂本月营收为什么下降",
                   metadata=_synthesis_metadata())                       # control: authorized
    desensitized, raw = _meta(sink[0][1]), _meta(sink[1][1])
    assert desensitized["desensitized"] is True
    stored = _json.dumps(desensitized, ensure_ascii=False)
    assert "华东" not in stored and "2500000" not in stored and "23.5" not in stored
    assert desensitized["query"] == "工厂A本月营收 <数值> 为什么下降"
    assert desensitized["grounding"] == {"clean": False, "reconciled": True,
                                         "confidence_adj": -0.1, "violation_count": 1}
    assert desensitized["question_family"] == "attribution" and desensitized["plan"] == {"finance": True}
    # authorized factory: kept as is (no desensitization anywhere)
    assert raw["query"] == "华东工厂本月营收 2500000 为什么下降"
    assert raw["grounding"]["violations"] == ["华东工厂毛利率 23.5% 未在数据中"]
    assert "desensitized" not in raw


async def test_capture_only_consults_the_samples_own_factory(monkeypatch):
    calls: list = []
    _patch_lists(monkeypatch, {"F_A": _lists(products=["秘制牛肉干"])}, calls=calls)
    sink: list = []
    await _capture(_RecordingPool(sink, {}), factory_id="F006", input_text="秘制牛肉干本月销量多少")
    assert calls == ["F006"] and [a[2] for _, a in sink] == ["F006"]


# ---- export recheck (scripts/export_distillation_dataset.passes_training_rule) ----

def _all_lists():
    return {"F_A": _lists(products=["秘制牛肉干"]), "F_B": _lists(products=["祖传卤汁鸭"], directory=["王五"])}


def _export_row(factory_id, **kw):
    row = {"factory_id": factory_id, "business_type": "factory",
           "input_text": "普通牛肉干本月销量多少", "teacher_output": "本月销量增长",
           "metadata": {"training_rule": RULE_VERSION, "desensitized": True}}
    row.update(kw)
    return row


def test_export_rule_rows_without_a_factory_never_pass():
    from scripts.export_distillation_dataset import passes_training_rule

    lists = _all_lists()
    assert passes_training_rule(_export_row(None), lists) is False
    assert passes_training_rule(_export_row(""), lists) is False
    assert passes_training_rule(_export_row("F006"), lists) is True                    # control


def test_export_rule_matches_each_row_against_its_own_factory_only():
    from scripts.export_distillation_dataset import passes_training_rule

    lists = _all_lists()
    assert passes_training_rule(_export_row("F006", input_text="秘制牛肉干本月销量"), lists) is True
    assert passes_training_rule(_export_row("F_A", input_text="秘制牛肉干本月销量"), lists) is False
    assert passes_training_rule(_export_row("F_B", teacher_output="王五负责的那条线"), lists) is False
    assert passes_training_rule(_export_row("F_A", teacher_output="王五负责的那条线"), lists) is True


def test_export_rule_needs_a_real_rule_version_and_a_factory_tenant():
    """Negative controls (2026-10-29 待办第 7 条): the drill row (prod id 16743 shape:
    training_rule="drill", no factory) and a MOCK_REST row (restaurant tenant, a real
    rule version) must not pass; the same F006 row with a real version does."""
    from scripts.export_distillation_dataset import passes_training_rule

    drill = {"factory_id": None, "business_type": "unknown", "input_text": "演练脱敏样本",
             "teacher_output": "演练", "metadata": {"desensitized": "true", "training_rule": "drill"}}
    drill_with_factory = dict(drill, factory_id="F006", business_type="factory")
    mock_rest = _export_row("MOCK_REST", business_type="restaurant",
                            metadata={"training_rule": "2026-10-24", "desensitized": True})
    mock_rest_bucket_factory = _export_row("MOCK_REST", metadata={"training_rule": "2026-10-24"})
    restaurant_bucket = _export_row("F006", business_type="restaurant")
    assert passes_training_rule(drill, {}) is False
    assert passes_training_rule(drill_with_factory, {}) is False          # "drill" is not a version
    assert passes_training_rule(mock_rest, {}) is False
    assert passes_training_rule(mock_rest_bucket_factory, {}) is False    # by tenant id alone
    assert passes_training_rule(restaurant_bucket, {}) is False           # by bucket alone
    assert passes_training_rule(_export_row("QATEST_1021_001"), {}) is False
    assert passes_training_rule(_export_row("F006BK"), {}) is False
    # controls: both real versions pass for a factory tenant
    assert passes_training_rule(_export_row("F006"), {}) is True
    assert passes_training_rule(_export_row("F006", metadata={"training_rule": "2026-10-24"}), {}) is True



async def test_authorized_held_row_keeps_no_extra_raw_copy(monkeypatch):
    _patch_lists(monkeypatch, {"F_PRIV": _lists(products=["秘制牛肉干"])}, stale=True)
    sink: list = []
    await _capture(_RecordingPool(sink, {"F_PRIV": "private"}), factory_id="F_PRIV", input_text="今天产量多少")
    assert _meta(sink[0][1]).get(RECHECK_KEY) is True       # the stored text is already raw


# ---- review N7b: the same input captured for another factory never overwrites the row ----

def test_upsert_only_updates_a_row_of_the_same_factory():
    from smartbi.services import distillation_capture as dc

    assert "WHERE smart_bi_distillation_samples.factory_id IS NOT DISTINCT FROM EXCLUDED.factory_id" in dc._INSERT_SQL


async def test_cross_factory_conflict_is_counted_and_logged(caplog):
    from smartbi.services import distillation_capture as dc

    class _ConflictConn(_RecordingConn):
        async def execute(self, sql, *args):
            self._sink.append((sql, args))
            return "INSERT 0 0"          # what PG returns when the conditional DO UPDATE skips

    class _ConflictPool(_RecordingPool):
        def acquire(self):
            return _ConflictConn(self._sink, self._consent, self._consent_error)

    before = dc.CROSS_FACTORY_CONFLICTS["count"]
    sink: list = []
    with caplog.at_level("WARNING", logger="smartbi.services.distillation_capture"):
        await _capture(_ConflictPool(sink, {}), factory_id="F006")
    assert dc.CROSS_FACTORY_CONFLICTS["count"] == before + 1
    assert any("another factory" in r.getMessage() for r in caplog.records)


async def test_upsert_conflict_on_real_postgres_keeps_the_first_factorys_row():
    """N7b on a real PostgreSQL: factory B's capture of the same input does not touch A's row."""
    import os
    import uuid

    import pytest

    dsn = os.getenv("SMARTBI_PG_DSN")
    if not dsn:
        if os.getenv("CI"):
            pytest.fail("SMARTBI_PG_DSN is required on CI")
        pytest.skip("no local PostgreSQL")
    asyncpg = pytest.importorskip("asyncpg")
    from smartbi.services import distillation_capture as dc

    conn = await asyncpg.connect(dsn)
    schema = "n7b_" + uuid.uuid4().hex[:10]
    try:
        await conn.execute(f"CREATE SCHEMA {schema}")
        await conn.execute(f"SET search_path TO {schema}")
        await conn.execute("""CREATE TABLE smart_bi_distillation_samples (
            id BIGSERIAL PRIMARY KEY, source VARCHAR(32), business_type VARCHAR(32) NOT NULL, factory_id VARCHAR(64),
            task_type VARCHAR(32), template_codes TEXT, system_prompt TEXT, input_text TEXT NOT NULL,
            teacher_model VARCHAR(64), teacher_output TEXT NOT NULL, input_hash VARCHAR(64) UNIQUE, quality INT,
            metadata JSONB, created_at TIMESTAMP DEFAULT now())""")
        args = ("chat_qa", "factory", "F_A", "qa", None, None, "同一个问题", None, "A 的答案", "h1", None, None)
        assert await conn.execute(dc._INSERT_SQL, *args) == "INSERT 0 1"
        status = await conn.execute(dc._INSERT_SQL, *(args[:2] + ("F_B",) + args[3:8] + ("B 的答案",) + args[9:]))
        assert status == "INSERT 0 0"
        row = await conn.fetchrow("SELECT factory_id, teacher_output FROM smart_bi_distillation_samples")
        assert (row["factory_id"], row["teacher_output"]) == ("F_A", "A 的答案")
        # control: the same factory still refreshes its own row
        assert await conn.execute(dc._INSERT_SQL, *(args[:8] + ("A 的新答案",) + args[9:])) == "INSERT 0 1"
        assert await conn.fetchval("SELECT teacher_output FROM smart_bi_distillation_samples") == "A 的新答案"
    finally:
        await conn.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
        await conn.close()


# ---- second review, item 1: held rows expire, and no raw text survives a judgement ----

async def test_capture_runs_the_held_row_expiry_at_most_once_per_interval(monkeypatch):
    """The re-judge only runs from corpus_judge; the capture path (which always runs) sweeps
    held rows past the retention, at most once per interval, and a failing sweep never blocks."""
    from smartbi.services import distillation_capture as dc

    _patch_lists(monkeypatch, {"F006": _lists()})
    monkeypatch.setitem(dc._LAST_EXPIRY, "at", None)
    EXPIRY_CALLS.clear()
    sink: list = []
    await _capture(_RecordingPool(sink, {}), factory_id="F006", input_text="今天产量多少")
    await _capture(_RecordingPool(sink, {}), factory_id="F006", input_text="今天销量多少")
    assert len(sink) == 2
    assert len(EXPIRY_CALLS) == 1                                    # throttled
    sql, args = EXPIRY_CALLS[0]
    assert "metadata ? 'secrecy_recheck'" in sql and args == (7,)
    monkeypatch.setitem(dc._LAST_EXPIRY, "at", dc._LAST_EXPIRY["at"] - dc.EXPIRY_INTERVAL_S - 1)
    await _capture(_RecordingPool(sink, {}), factory_id="F006", input_text="今天入库多少")
    assert len(EXPIRY_CALLS) == 2



async def test_the_expiry_sweep_also_runs_on_captures_that_return_early(monkeypatch):
    """Review round 3, item 3: a factory whose captures are all skipped (no factory, a restaurant
    tenant, capture switched off) must still get its held rows expired."""
    from smartbi.services import distillation_capture as dc

    for case in ("disabled", "no_factory", "restaurant"):
        monkeypatch.setitem(dc._LAST_EXPIRY, "at", None)
        EXPIRY_CALLS.clear()
        sink: list = []
        with monkeypatch.context() as m:
            if case == "disabled":
                m.setenv("SMARTBI_DISTILL_CAPTURE", "0")
                await _capture(_RecordingPool(sink, {}), factory_id="F006")
            elif case == "no_factory":
                await _capture(_RecordingPool(sink, {}), factory_id=None)
            else:
                await _capture(_RecordingPool(sink, {}), factory_id="MOCK_REST")
        assert sink == [], case                                          # nothing captured ...
        assert len(EXPIRY_CALLS) == 1, case                              # ... but the sweep ran
    # a sweep that cannot get a connection never raises out of the capture
    monkeypatch.setitem(dc._LAST_EXPIRY, "at", None)
    await _capture(_ExplodingPool(), factory_id="F006")


def test_python_hold_retention_equals_the_java_default():
    from pathlib import Path

    from smartbi.services.process_secret_names import HOLD_MAX_AGE_DAYS

    java = (Path(__file__).resolve().parents[2] / "java" / "cretas-api" / "src" / "main" / "java" / "com" / "cretas"
            / "aims" / "service" / "consent" / "TrainingSampleHoldRejudger.java").read_text(encoding="utf-8")
    assert f"cretas.training.secrecy-hold.max-age:P{HOLD_MAX_AGE_DAYS}D" in java


class _OneConnPool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *a):
                return False
        return _Ctx()


async def test_held_rows_on_real_postgres_leave_no_raw_text_behind():
    """Capture -> re-judge -> expiry on a real PostgreSQL: the rejected row keeps reason / list
    version / time only, the released row loses the raw copy and gains the desensitized stamp,
    the expired row is gone. Nowhere does the raw text survive."""
    import os
    import uuid

    import pytest

    dsn = os.getenv("SMARTBI_PG_DSN")
    if not dsn:
        if os.getenv("CI"):
            pytest.fail("SMARTBI_PG_DSN is required on CI")
        pytest.skip("no local PostgreSQL")
    asyncpg = pytest.importorskip("asyncpg")
    from smartbi.services.process_secret_names import rejudge_held_rows

    conn = await asyncpg.connect(dsn)
    schema = "hold_" + uuid.uuid4().hex[:10]
    try:
        await conn.execute(f"CREATE SCHEMA {schema}")
        await conn.execute(f"SET search_path TO {schema}")
        await conn.execute("""CREATE TABLE smart_bi_distillation_samples (
            id BIGSERIAL PRIMARY KEY, factory_id VARCHAR(64), input_text TEXT NOT NULL,
            teacher_output TEXT NOT NULL, metadata JSONB, created_at TIMESTAMP DEFAULT now())""")

        def held(raw_in):
            return _json.dumps({"training_rule": "2026-11-03",
                                "secrecy_recheck": {"raw_input": raw_in, "raw_output": "RAW-OUT"}}, ensure_ascii=False)
        await conn.execute(
            "INSERT INTO smart_bi_distillation_samples (id, factory_id, input_text, teacher_output, metadata, created_at)"
            " VALUES (1, 'F_A', '产品A销量', 'x', $1::jsonb, now()),"
            "        (2, 'F_A', '普通牛肉干销量', 'x', $2::jsonb, now()),"
            "        (3, 'F_A', '旧的', 'x', $3::jsonb, now() - interval '8 days')",
            held("秘制牛肉干销量"), held("RAW-CLEAN 普通牛肉干销量"), held("RAW-OLD 秘制牛肉干"))
        out = await rejudge_held_rows(_OneConnPool(conn), {"F_A": _lists(products=["秘制牛肉干"])})
        assert out == {"released": 1, "rejected": 1, "expired": 1}
        rows = {r["id"]: _json.loads(r["metadata"]) for r in
                await conn.fetch("SELECT id, metadata FROM smart_bi_distillation_samples ORDER BY id")}
        assert sorted(rows) == [1, 2]                                    # 3 expired with its raw copy
        assert set(rows[1]["secrecy_rejected"]) == {"reason", "lists", "at"}
        assert "secrecy_recheck" not in rows[1] and "desensitized" not in rows[1]
        assert rows[2] == {"training_rule": "2026-11-03", "desensitized": True}
        dump = await conn.fetchval("SELECT string_agg(metadata::text || input_text || teacher_output, '|')"
                                   " FROM smart_bi_distillation_samples")
        for raw in ("秘制牛肉干", "RAW-CLEAN", "RAW-OLD", "RAW-OUT"):
            assert raw not in dump, raw
    finally:
        await conn.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
        await conn.close()
