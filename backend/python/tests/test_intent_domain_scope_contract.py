"""业务域缩小候选 (意图蒸馏第③步 PR-4) 的合约闸。

守的是表第 1、3、6 节写死的兜底规则, 每条正向断言配一条能让它红的对照:
  - 次业务域命中算数 (对照: 同域没有次域的意图被收掉, 证明真的收窄了);
  - SYSTEM / ANALYTICS 不因为「域已确定」被排除;
  - 表外意图 (工厂自建) 不当成任何域, 留给语义召回兜底;
  - 跨域句子取并集, 不被收窄成一个域;
  - 判不出 / 太多 / 带上下文 / 版本没带或不对 ⇒ 原样不收窄 (宁可放宽);
  - 编排器里: 只缩小给模型提示词的列表, 精确表与语义召回候选不受限制; 决策层输出 domain。
"""
import asyncio

import pytest

from ai import intent_domain, registry
from ai import legacy_exit_metrics as exit_metrics
from ai.config import AIConfig
from ai.dto import CandidateIntentDto, MatchMethod
from ai.matcher.decision import RequestPurpose
from ai.orchestrator import Orchestrator

TABLE = intent_domain.load_table()
VERSION = TABLE.catalog_version
ALL = [{"id": str(i), "intent_code": c, "intent_name": c, "description": c, "is_active": True}
       for i, c in enumerate(sorted(TABLE.primary))]
CUSTOM = {"id": "x1", "intent_code": "F006_CUSTOM_WEIGHING", "intent_name": "自建称重", "description": "工厂自建",
          "is_active": True, "factory_id": "F006"}


def _codes(rows):
    return {r["intent_code"] for r in rows}


def _of(domain):
    return {c for c, d in TABLE.primary.items() if d == domain}


# ---------------- 表本身 ----------------

def test_packaged_copy_is_byte_identical_to_java_resource():
    assert intent_domain.PACKAGED_TABLE_PATH.read_bytes() == intent_domain.JAVA_TABLE_PATH.read_bytes()


def test_secondary_domains_are_known_and_differ_from_primary():
    assert TABLE.secondary, "instrument sanity: the table must carry secondary domains"
    for code, sec in TABLE.secondary.items():
        assert sec in TABLE.domains and sec != TABLE.domain_of(code), code


def test_cross_domain_entries_have_no_keywords_and_business_domains_do():
    for dom in TABLE.domains:
        if dom in intent_domain.ALWAYS_CANDIDATE_DOMAINS:
            assert not TABLE.keywords[dom], dom
        else:
            assert TABLE.keywords[dom], dom


# ---------------- 缩小规则 ----------------

def test_secondary_domain_hit_keeps_the_intent():
    s = intent_domain.scope("这周质检结果咋样", ALL, TABLE, VERSION)
    assert s.narrowed and "QUALITY" in s.domains and "EQUIPMENT" not in s.domains
    kept = _codes(s.rows)
    # 冷链温度: 主域 EQUIPMENT, 次域 QUALITY ⇒ 次域命中, 留下
    assert TABLE.domain_of("COLD_CHAIN_TEMP_QUERY") == "EQUIPMENT"
    assert TABLE.secondary_of("COLD_CHAIN_TEMP_QUERY") == "QUALITY"
    assert "COLD_CHAIN_TEMP_QUERY" in kept
    # 对照: 同为 EQUIPMENT、没有次域的摄像头订阅被收掉 —— 证明上面那条是次域救回来的, 不是没收窄
    assert TABLE.secondary_of("CAMERA_SUBSCRIBE") is None
    assert "CAMERA_SUBSCRIBE" not in kept
    assert not (_of("HR") & kept)


@pytest.mark.parametrize("query", ["这周质检结果咋样", "考勤看一下", "盘点一下"])
def test_system_and_analytics_are_never_excluded(query):
    s = intent_domain.scope(query, ALL, TABLE, VERSION)
    assert s.narrowed, query
    kept = _codes(s.rows)
    assert _of("SYSTEM") <= kept and _of("ANALYTICS") <= kept
    assert len(kept) < len(ALL)  # 对照: 确实收窄了


def test_intent_whose_secondary_is_a_cross_domain_entry_is_kept():
    # 业务单跨域关联: 主域 TRACE, 次域 SYSTEM ⇒ 跨域通用, 不因为句子判成 RND / QUALITY 被收掉
    assert TABLE.secondary_of("BUSINESS_LINK_QUERY") == "SYSTEM"
    s = intent_domain.scope("我想看样品单都关联了什么单据", ALL, TABLE, VERSION)
    assert "BUSINESS_LINK_QUERY" in _codes(s.rows)


def test_intent_outside_the_table_falls_through_to_semantic_recall():
    rows = ALL + [CUSTOM]
    s = intent_domain.scope("考勤看一下", rows, TABLE, VERSION)
    assert s.narrowed
    assert TABLE.domain_of(CUSTOM["intent_code"]) is None
    assert CUSTOM["intent_code"] in _codes(s.rows)


def test_cross_domain_sentence_keeps_every_mentioned_domain():
    s = intent_domain.scope("考勤和提成这个月对一下", ALL, TABLE, VERSION)
    assert s.narrowed and {"HR", "SALES"} <= s.domains
    kept = _codes(s.rows)
    assert _of("HR") <= kept and _of("SALES") <= kept
    assert not (_of("RND") & kept)  # 对照: 没提到的域照样收掉


def test_too_many_domains_is_not_narrowed():
    q = "供应商送来的原料质检不合格，客户那边要退货，账也得对"
    s = intent_domain.scope(q, ALL, TABLE, VERSION)
    assert len(s.domains) > intent_domain.MAX_DETECTED_DOMAINS
    assert not s.narrowed and s.reason == "too_broad" and len(s.rows) == len(ALL)


@pytest.mark.parametrize("query,version,contextual,reason", [
    ("看下仪表盘总览", VERSION, False, "no_domain"),
    ("考勤看一下", None, False, "no_catalog_version"),
    ("考勤看一下", "1999-01-01.1", False, "catalog_version_mismatch"),
    ("考勤看一下", VERSION, True, "contextual"),
])
def test_unsure_cases_widen_instead_of_narrowing(query, version, contextual, reason):
    s = intent_domain.scope(query, ALL, TABLE, version, contextual=contextual)
    assert not s.narrowed and s.reason == reason
    assert _codes(s.rows) == _codes(ALL)


def test_unreadable_table_widens(tmp_path):
    bad = tmp_path / "t.json"
    bad.write_text("{}", encoding="utf-8")
    with pytest.raises(intent_domain.IntentDomainTableError):
        intent_domain.load_table(bad)
    s = intent_domain.scope("考勤看一下", ALL, None, VERSION)
    assert not s.narrowed and s.reason == "table_unavailable"


# ---------------- 编排器接线 ----------------

class FakeSemantic:
    def __init__(self, cands):
        self.cands = cands

    async def match(self, query, factoryId=None, businessType=None):
        return [CandidateIntentDto(intentCode=c, confidence=p, matchMethod=MatchMethod.SEMANTIC) for c, p in self.cands]


class FakeDecision:
    def __init__(self, short, probs):
        self.short, self.probs = short, probs
        self.shortlist_visible = None
        self.choice_options = None

    async def request_purpose(self, query, role, history=None):
        return RequestPurpose.CURRENT

    async def shortlist(self, query, role, visible, history=None):
        self.shortlist_visible = _codes(visible)
        return self.short

    async def choose(self, query, role, options, history=None, special_options=False):
        self.choice_options = [o["intent_code"] for o in options]
        return dict(self.probs)


class Nothing:
    async def match(self, *a, **k):
        return []


CFG = AIConfig(decision_business_types="FACTORY", decision_auto_threshold=0.7, decision_fallback="legacy")


def _match(dec, query, version, sem=(), history=None):
    orch = Orchestrator(semantic_matcher=Nothing(), classifier_matcher=Nothing(), llm_matcher=Nothing(),
                        decision_matcher=dec, candidate_retriever=FakeSemantic(list(sem)), config=CFG)
    trace = exit_metrics.start_decision_trace()
    res = asyncio.run(orch.match(query=query, factoryId="F006", businessType="FACTORY", userId="u", role="operator",
                                 visible_intents=ALL + [CUSTOM], history=history or [], min_confidence=0.7,
                                 enable_llm=True, catalog_version=version))
    return res, trace


@pytest.fixture(autouse=True)
def _empty_registry():
    registry.set_registry(registry.RegistrySnapshot())
    yield
    registry.set_registry(registry.RegistrySnapshot())


def test_orchestrator_narrows_only_what_the_model_is_shown_and_outputs_the_domain():
    dec = FakeDecision(["CLOCK_IN"], {"CLOCK_IN": 0.95})
    # 语义召回给了一个域外候选 (摄像头订阅): 语义召回是兜底, 不受业务域限制
    res, trace = _match(dec, "考勤看一下", VERSION, sem=[("CAMERA_SUBSCRIBE", 0.6)])
    assert res.bestMatch.intentCode == "CLOCK_IN"
    assert "CAMERA_SUBSCRIBE" not in dec.shortlist_visible and "CLOCK_IN" in dec.shortlist_visible
    assert _of("SYSTEM") <= dec.shortlist_visible and CUSTOM["intent_code"] in dec.shortlist_visible
    assert "CAMERA_SUBSCRIBE" in dec.choice_options
    assert res.timingMs.get("domainNarrowed") == 1
    assert trace["domainScope"] == "narrowed" and trace["domains"] == ["HR"]
    assert trace["catalogVersion"] == VERSION and trace["domain"] == "HR"


def test_orchestrator_without_catalog_version_shows_every_intent():
    dec = FakeDecision(["CLOCK_IN"], {"CLOCK_IN": 0.95})
    res, trace = _match(dec, "考勤看一下", None)
    assert dec.shortlist_visible == _codes(ALL + [CUSTOM])
    assert "domainNarrowed" not in res.timingMs
    # 老请求 (不带表版本): 决策痕迹里没有任何业务域键, 与改动前逐字相同
    assert not {"catalogVersion", "domainScope", "domains", "domain", "secondaryDomain"} & set(trace)


def test_orchestrator_does_not_narrow_contextual_turns():
    dec = FakeDecision(["CLOCK_IN"], {"CLOCK_IN": 0.95})
    _res, trace = _match(dec, "考勤看一下", VERSION, history=[{"role": "user", "content": "看下摄像头"}])
    assert dec.shortlist_visible == _codes(ALL + [CUSTOM]) and trace["domainScope"] == "contextual"


def test_exact_phrase_hit_outside_the_detected_domain_still_wins():
    registry.set_registry(registry.build_snapshot(
        [{"factory_id": "F006", "business_type": "FACTORY", "normalized_text": "考勤看一下",
          "intent_code": "CAMERA_SUBSCRIBE"}], []))
    dec = FakeDecision(["CLOCK_IN"], {"CLOCK_IN": 0.95})
    res, _trace = _match(dec, "考勤看一下", VERSION)
    assert res.bestMatch.intentCode == "CAMERA_SUBSCRIBE" and res.matchMethod == MatchMethod.EXACT
    assert dec.shortlist_visible is None
