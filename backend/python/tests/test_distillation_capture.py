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
        self._sink.append((sql, args))
        return "INSERT 0 1"

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


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
        business_type="restaurant",
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
    assert args[1] == "restaurant"
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




# ---- training rule (owner, 2026-10-24) ----
# Process parameters / structure / problem handling / per-person pay: never stored,
# for any factory. Everything else is stored: authorized factories raw, everyone
# else (no row, 'none', unknown scope, lookup error, no factory) desensitized.

import json as _json  # noqa: E402


async def _capture(pool, **kw):
    kw.setdefault("source", "chat_qa")
    kw.setdefault("task_type", "qa")
    kw.setdefault("input_text", "本月营收多少")
    kw.setdefault("teacher_output", "本月营收 128 万元，环比增长 8%")
    await persist_distillation_sample(pool, **kw)


def _meta(args):
    return _json.loads(args[11]) if args[11] else {}


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
    assert _meta(args) == {"training_rule": "2026-10-24", "desensitized": True}


async def test_authorized_factory_qa_is_stored_as_is():
    sink: list = []
    pool = _RecordingPool(sink, {"F_PRIV": "private", "F_SHARE": "shared_anon"})
    await _capture(pool, factory_id="F_PRIV", input_text="华东工厂本月营收多少")
    await _capture(pool, factory_id="F_SHARE", input_text="华北工厂本月营收多少")
    assert [a[6] for _, a in sink] == ["华东工厂本月营收多少", "华北工厂本月营收多少"]
    assert all(_meta(a) == {"training_rule": "2026-10-24"} for _, a in sink)


async def test_process_content_is_never_stored_even_when_authorized():
    sink: list = []
    pool = _RecordingPool(sink, {"F_PRIV": "private"})
    cases = [
        ("产品配方表如下：盐 2.5%", "已读取"),                  # parameter in input
        ("数据：工序 1 清洗、工序 2 切配", "按顺序执行即可"),     # structure in input
        ("怎么提升出成率", "建议把腌制时间延长到 24 小时再滚揉"),  # detail in answer
        ("为什么最近返工多", "主要是原料批次差"),                # problem handling
        ("本月工资总额", "人工成本 35 万"),                      # per-person pay
    ]
    for q, a in cases:
        await _capture(pool, factory_id="F_PRIV", input_text=q, teacher_output=a)
        await _capture(pool, factory_id="F006", input_text=q, teacher_output=a)
    assert sink == []


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


async def test_sample_without_factory_is_stored_desensitized():
    sink: list = []
    pool = _RecordingPool(sink, {}, consent_error=AssertionError("no factory -> no lookup"))
    await _capture(pool, input_text="华东工厂本月营收多少")
    assert len(sink) == 1
    _, args = sink[0]
    assert args[2] is None and _meta(args).get("desensitized") is True


async def test_synthetic_seed_tenants_are_stored_as_is_without_lookup():
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


async def test_specific_customer_or_person_name_is_never_stored():
    # Steve 2026-10-24: 问答里出现「客户」且带具体名称、或出现人名 → 整条不存 (任何工厂)
    sink: list = []
    pool = _RecordingPool(sink, {"F_PRIV": "private"})
    for q, a in [("张记食品有限公司这个客户回款怎么样", "回款正常"),
                 ("客户：华联 欠款多少", "欠款 12 万元"),
                 ("这批货谁签收的", "签收人：李四"),
                 ("王总要求加急，能排吗", "可以安排")]:
        await _capture(pool, factory_id="F_PRIV", input_text=q, teacher_output=a)
        await _capture(pool, factory_id="F006", input_text=q, teacher_output=a)
    assert sink == []


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


async def test_sample_naming_a_process_secret_product_is_never_stored(monkeypatch):
    # privacy clause 6: process-secret products are not used for training
    from smartbi.services import distillation_capture as dc

    async def names(factory_id, *_a, **_k):
        return frozenset({"秘制牛肉干"}) if factory_id == "F_PRIV" else frozenset()

    monkeypatch.setattr(dc, "protected_product_names", names)
    sink: list = []
    pool = _RecordingPool(sink, {"F_PRIV": "private"})
    await _capture(pool, factory_id="F_PRIV", input_text="秘制牛肉干本月销量多少")
    await _capture(pool, factory_id="F_PRIV", teacher_output="秘制牛肉干本月销量增长")
    await _capture(pool, factory_id="F_PRIV", input_text="普通牛肉干本月销量多少")   # control
    await _capture(pool, factory_id="F006", input_text="秘制牛肉干本月销量多少")     # not F006's product
    assert [(a[2], a[6]) for _, a in sink] == [
        ("F_PRIV", "普通牛肉干本月销量多少"), ("F006", "秘制牛肉干本月销量多少")]


async def test_process_secret_lookup_failure_means_not_stored(monkeypatch):
    from smartbi.services import distillation_capture as dc

    async def boom(*_a, **_k):
        raise RuntimeError("cretas db unavailable")

    monkeypatch.setattr(dc, "protected_product_names", boom)
    sink: list = []
    await _capture(_RecordingPool(sink, {"F_PRIV": "private"}), factory_id="F_PRIV")
    await _capture(_RecordingPool(sink, {}), factory_id="SEED_R")   # synthetic: no lookup, stored
    assert [a[2] for _, a in sink] == ["SEED_R"]


# ---- samples with no factory: process-secret products of ANY factory count ----
# (read-only prod probe 2026-10-29: NULL-factory samples were never checked)

_ALL_PROTECTED = {"F_A": frozenset({"秘制牛肉干"}), "F_B": frozenset({"祖传卤汁鸭"})}


async def test_no_factory_sample_naming_any_factorys_secret_product_is_not_stored(monkeypatch):
    from smartbi.services import distillation_capture as dc

    calls: list = []

    async def all_names(*_a, **_k):
        calls.append(1)
        return _ALL_PROTECTED

    monkeypatch.setattr(dc, "all_protected_product_names", all_names)
    sink: list = []
    pool = _RecordingPool(sink, {}, consent_error=AssertionError("no factory -> no lookup"))
    await _capture(pool, input_text="秘制牛肉干本月销量多少")         # factory A's, in input
    await _capture(pool, teacher_output="祖传卤汁鸭本月销量增长")      # factory B's, in output
    await _capture(pool, input_text="普通牛肉干本月销量多少")         # control: stored
    assert len(calls) == 3          # the lookup really ran for every NULL-factory sample
    assert [a[2] for _, a in sink] == [None]
    assert "普通牛肉干" in sink[0][1][6]


async def test_factory_sample_does_not_use_the_all_factories_names(monkeypatch):
    # a factory's sample answers to its own factory's names only (unchanged)
    from smartbi.services import distillation_capture as dc

    async def all_names(*_a, **_k):
        raise AssertionError("factory sample must not use the all-factories lookup")

    monkeypatch.setattr(dc, "all_protected_product_names", all_names)
    sink: list = []
    await _capture(_RecordingPool(sink, {}), factory_id="F006", input_text="秘制牛肉干本月销量多少")
    assert [a[2] for _, a in sink] == ["F006"]


async def test_no_factory_sample_is_not_stored_when_the_all_factories_lookup_fails(monkeypatch):
    from smartbi.services import distillation_capture as dc

    async def boom(*_a, **_k):
        raise RuntimeError("cretas db unavailable")

    monkeypatch.setattr(dc, "all_protected_product_names", boom)
    sink: list = []
    await _capture(_RecordingPool(sink, {}), input_text="普通牛肉干本月销量多少")
    await _capture(_RecordingPool(sink, {}), factory_id="SEED_R")   # control: synthetic, no lookup
    assert [a[2] for _, a in sink] == ["SEED_R"]


def _export_row(factory_id, **kw):
    row = {"factory_id": factory_id, "input_text": "普通牛肉干本月销量多少",
           "teacher_output": "本月销量增长",
           "metadata": {"training_rule": "2026-10-24", "desensitized": True}}
    row.update(kw)
    return row


def test_export_rule_no_factory_row_naming_any_factorys_secret_product_fails():
    from scripts.export_distillation_dataset import passes_training_rule

    assert passes_training_rule(_export_row(None, input_text="秘制牛肉干本月销量"), _ALL_PROTECTED) is False
    assert passes_training_rule(_export_row("", teacher_output="祖传卤汁鸭销量增长"), _ALL_PROTECTED) is False
    assert passes_training_rule(_export_row(None), _ALL_PROTECTED) is True              # control
    # a factory row still answers to its own factory's names only (unchanged)
    assert passes_training_rule(_export_row("F006", input_text="秘制牛肉干本月销量"), _ALL_PROTECTED) is True
    assert passes_training_rule(_export_row("F_A", input_text="秘制牛肉干本月销量"), _ALL_PROTECTED) is False
