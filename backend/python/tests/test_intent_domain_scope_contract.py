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
    async def run():
        # 痕迹在协程自己的上下文里开 (asyncio.run 复制上下文), 不漏到同线程后面的测试
        trace = exit_metrics.start_decision_trace()
        res = await orch.match(query=query, factoryId="F006", businessType="FACTORY", userId="u", role="operator",
                               visible_intents=ALL + [CUSTOM], history=history or [], min_confidence=0.7,
                               enable_llm=True, catalog_version=version)
        return res, trace

    return asyncio.run(run())


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


# ---------------- 终审修改项 (M1 / M2 / L1 / L2) ----------------

from dataclasses import replace  # noqa: E402

from ai.matcher.candidates import BlendRetriever  # noqa: E402
from ai.matcher.decision import Understanding  # noqa: E402


class SeqDecision(FakeDecision):
    """每次粗选记下看到的候选; Choice 按调用次序返回 probs_seq 里的分布。"""

    def __init__(self, short, probs_seq):
        super().__init__(short, probs_seq[0])
        self.probs_seq, self.shortlist_seen = list(probs_seq), []

    async def shortlist(self, query, role, visible, history=None):
        self.shortlist_seen.append(_codes(visible))
        self.shortlist_visible = _codes(visible)
        return self.short

    async def choose(self, query, role, options, history=None, special_options=False):
        self.choice_options = [o["intent_code"] for o in options]
        i = min(len(self.shortlist_seen), len(self.probs_seq)) - 1
        return dict(self.probs_seq[i])


def _match_cfg(dec, query, version, cfg, sem_retriever=None, rerank=None):
    orch = Orchestrator(semantic_matcher=Nothing(), classifier_matcher=Nothing(), llm_matcher=Nothing(),
                        decision_matcher=dec, candidate_retriever=sem_retriever or FakeSemantic([]), config=cfg,
                        rerank_client=rerank)

    async def run():
        trace = exit_metrics.start_decision_trace()
        res = await orch.match(query=query, factoryId="F006", businessType="FACTORY", userId="u", role="operator",
                               visible_intents=ALL + [CUSTOM], history=[], min_confidence=0.7,
                               enable_llm=True, catalog_version=version)
        return res, trace

    return asyncio.run(run())


def test_m1_narrowed_clarify_is_rejudged_with_every_intent():
    # 收窄后只拿到低把握 (= 先问清楚 / 出按钮) ⇒ 用全部意图再判一次, 不能就此收场
    dec = SeqDecision(["CLOCK_IN"], [{"CLOCK_IN": 0.4}, {"CLOCK_IN": 0.95}])
    res, trace = _match_cfg(dec, "考勤看一下", VERSION, CFG)
    assert len(dec.shortlist_seen) == 2
    assert dec.shortlist_seen[0] < _codes(ALL + [CUSTOM])          # 第一次: 收窄的列表
    assert dec.shortlist_seen[1] == _codes(ALL + [CUSTOM])          # 第二次: 全部可见意图
    assert res.bestMatch.intentCode == "CLOCK_IN" and not res.requiresConfirmation
    assert res.timingMs.get("domainRetryWide") == 1
    assert trace["domainScope"] == "narrowed_retry_wide"
    from ai.api import option_set_hash
    assert trace["optionSetHash"] == option_set_hash(ALL + [CUSTOM])


def test_m1_direct_answer_after_narrowing_is_not_rejudged():
    # 对照: 收窄后直接办 ⇒ 不重判 (重判只为兜「没答出来 / 以上都不是 / 先问」)
    dec = SeqDecision(["CLOCK_IN"], [{"CLOCK_IN": 0.95}, {"CLOCK_IN": 0.95}])
    res, trace = _match_cfg(dec, "考勤看一下", VERSION, CFG)
    assert len(dec.shortlist_seen) == 1 and trace["domainScope"] == "narrowed"
    assert "domainRetryWide" not in res.timingMs


def test_m1_no_answer_without_narrowing_is_not_rejudged():
    # 对照: 没带表版本 (没收窄) 时先问就是先问, 不会多调一次模型
    dec = SeqDecision(["CLOCK_IN"], [{"CLOCK_IN": 0.4}, {"CLOCK_IN": 0.95}])
    res, _trace = _match_cfg(dec, "考勤看一下", None, CFG)
    assert len(dec.shortlist_seen) == 1 and res.requiresConfirmation


def test_m1_craft_sentence_reaches_production_intents():
    s = intent_domain.scope("看下工艺标准", ALL, TABLE, VERSION)
    assert s.narrowed and {"PRODUCTION", "QUALITY"} <= s.domains
    assert _of("PRODUCTION") <= _codes(s.rows) and _of("QUALITY") <= _codes(s.rows)


class MergedDecision(FakeDecision):
    def __init__(self, target, candidates):
        super().__init__([], {})
        self.target, self.cands, self.understand_visible = target, candidates, None

    async def understand(self, query, role, visible, history=None):
        self.understand_visible = _codes(visible)
        return Understanding(purpose=RequestPurpose.CURRENT, act="NEW_TASK", candidates=list(self.cands),
                             target=self.target, confidence=0.95, alternatives=[], clarify_probability=0.0,
                             clarify_reason="", parameters=[])


def test_m2_merged_understanding_sees_the_narrowed_list_and_recalled_candidates_stay_valid():
    cfg = replace(CFG, decision_merged=True)
    # 合并理解只看到 HR 域 (+ 通用入口); 目标却是语义召回补回来的域外意图 (摄像头订阅) —— 它仍是合法候选
    dec = MergedDecision("CAMERA_SUBSCRIBE", ["CLOCK_IN"])
    res, trace = _match_cfg(dec, "考勤看一下", VERSION, cfg, sem_retriever=FakeSemantic([("CAMERA_SUBSCRIBE", 0.6)]))
    assert dec.understand_visible is not None and "CAMERA_SUBSCRIBE" not in dec.understand_visible
    assert "CLOCK_IN" in dec.understand_visible and _of("SYSTEM") <= dec.understand_visible
    assert dec.understand_visible < _codes(ALL + [CUSTOM])
    assert res.bestMatch.intentCode == "CAMERA_SUBSCRIBE" and res.timingMs.get("choiceSkipped") == 1
    assert trace["domainScope"] == "narrowed"


class FakeBlend(BlendRetriever):
    def __init__(self, order):
        super().__init__(pool=None)
        self.order = order

    async def match(self, query, factoryId, businessType, top_k=None):
        return [CandidateIntentDto(intentCode=c, confidence=0.5, matchMethod=MatchMethod.SEMANTIC) for c in self.order]


class FakeRerank:
    async def scores(self, query, documents):
        return [1.0 - 0.1 * i for i in range(len(documents))]


def test_l1_rerank_path_does_not_claim_narrowing():
    # 重排路不调粗选: 收窄的列表没交给模型, 痕迹里不能写 narrowed, optionSetHash 也不换
    cfg = replace(CFG, decision_candidates="rerank", rerank_pool_k=50, rerank_top_k=2)
    dec = SeqDecision(["CLOCK_IN"], [{"CLOCK_IN": 0.95}])
    res, trace = _match_cfg(dec, "考勤看一下", VERSION, cfg, sem_retriever=FakeBlend(["CLOCK_IN", "CLOCK_OUT"]),
                            rerank=FakeRerank())
    assert dec.shortlist_seen == []  # 证明真走了重排路
    assert res.bestMatch.intentCode == "CLOCK_IN"
    assert trace["domainScope"] == "narrowed_unused" and "domainNarrowed" not in res.timingMs
    assert "optionSetHash" not in trace and "domainCandidates" not in trace


def test_l1_legacy_fallback_after_narrowing_is_not_recorded_as_narrowed():
    # 粗选空、语义召回空 ⇒ 决策层没答出来 ⇒ 全量重判仍没答出来 ⇒ 旧路径答: 痕迹不能停在 narrowed
    dec = SeqDecision([], [{}])
    _res, trace = _match_cfg(dec, "考勤看一下", VERSION, CFG)
    assert len(dec.shortlist_seen) == 2
    assert trace["domainScope"] == "narrowed_retry_wide"
    from ai.api import option_set_hash
    assert trace["optionSetHash"] == option_set_hash(ALL + [CUSTOM])


def test_l2_switch_off_shows_every_intent():
    cfg = replace(CFG, domain_scope_enabled=False)
    dec = SeqDecision(["CLOCK_IN"], [{"CLOCK_IN": 0.95}])
    res, trace = _match_cfg(dec, "考勤看一下", VERSION, cfg)
    assert dec.shortlist_seen == [_codes(ALL + [CUSTOM])]
    assert trace["domainScope"] == "disabled" and "domainNarrowed" not in res.timingMs


@pytest.mark.parametrize("value,enabled", [(None, True), ("1", True), ("0", False), ("off", False)])
def test_l2_switch_reads_env(monkeypatch, value, enabled):
    if value is None:
        monkeypatch.delenv("AI_DOMAIN_SCOPE", raising=False)
    else:
        monkeypatch.setenv("AI_DOMAIN_SCOPE", value)
    assert AIConfig.from_env().domain_scope_enabled is enabled
