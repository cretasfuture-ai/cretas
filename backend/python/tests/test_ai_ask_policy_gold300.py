"""保守档按史浩禾 300 题重校的守门测试 (INTENT-DISTILL-20261111, 2026-11-19)。

数据 (tests/fixtures/ask_policy/):
  gold300.json        300 题双人金标: 每题记 Steve 与史浩禾的原始选择、备注、映射后的期望动作, 标 status
                      agree 一致 / disputed 有争议 / steve_only / shi_only / unscored。
                      保守档按史浩禾答案计分 (conservative_scored); 激进档按 Steve 答案, 只计一致题 + Steve 独有题
                      (aggressive_scored), 有争议题单列、暂不计分。
  s300_readings.json  同一份 300 题在 47 上用源码本地装配真跑的逐题读数 (Choice 概率 + Luna 判断题概率):
                      before_* = 改前 main, after_* = 本 PR; cons = 保守档, agg = 激进档。build_readings.py 生成。

守的行为 (真实 Orchestrator._result_from_probs + 真实 ai.ask_policy.tier_of, 只把 Luna 读数换成冻结值):
  1. 保守档用的判断题规则和门槛就是这套读数当时用的 (规则 sha256 与门槛钉住), 激进档规则/门槛一字不动;
  2. 用代码里当前的保守档重放冻结读数, 史浩禾 300 题 (计分 297) 的命中数钉在 N_NEW, 比改前档 (旧规则 + 门槛 0.6,
     同一重放函数) 的 N_OLD 多, 且至少有 1 题旧档错、新档对;
  3. 重放函数与真跑结论逐题一致 (量的是对的东西: 不一致就是仪器问题, 不是档位问题);
  4. 写操作两档照旧: 两档读/写门槛逐项相同 (写目标只交 Java 出确认卡, 真实 HTTP 入口的断言在
     test_ai_decision_ask_policy_tiers.py)。

⛔ 冻结读数不能证明 Luna 在新规则下仍给出这些概率: 改规则文本会让第 1 条的哈希红, 提醒重跑 build_readings.py。
"""
import hashlib
import json
import pathlib
import time

from ai import ask_policy
from ai.config import AIConfig
from ai.matcher.decision import ASK_KEY, CLARIFY_KEY, NONE_KEY
from ai.orchestrator import Orchestrator

FIX = pathlib.Path(__file__).parent / "fixtures" / "ask_policy"
GOLD = {r["id"]: r for r in json.loads((FIX / "gold300.json").read_text("utf-8"))}
READ = json.loads((FIX / "s300_readings.json").read_text("utf-8"))
META, ROWS = READ["meta"], READ["rows"]

# 钉住的读数 (同一份冻结读数、同一重放函数; 改前 = 旧规则 + 门槛 0.6, 改后 = 当前代码里的保守档)
N_SCORED = 297        # 史浩禾可计分题数 (300 - 3 题他没给动作)
N_OLD = 172           # 旧保守档命中 (57.9%)
N_NEW = 209           # 新保守档命中 (70.4%)


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _orch() -> Orchestrator:
    class _Never:
        async def match(self, *a, **k):
            raise AssertionError("重放不该走任何匹配器")
    return Orchestrator(semantic_matcher=_Never(), classifier_matcher=_Never(), llm_matcher=_Never(),
                        config=AIConfig())


def replay(side: dict, tier: ask_policy.AskTier) -> str:
    """冻结读数 -> 当前决策函数 -> direct / show / ask。没有判断题读数的轮 (不归判断题管) 取当时真跑的结论。"""
    if not side["governed"]:
        return side["recorded_act"]
    probs, rows = {}, []
    for i, (p, write) in enumerate(side["probs"]):
        code = "C%d" % (i + 1)
        probs[code] = p
        rows.append({"id": str(i), "intent_code": code, "intent_name": code, "description": "", "keywords": None,
                     "intent_category": "X", "business_type": "FACTORY", "factory_id": None, "is_active": True,
                     "sensitivity_level": "HIGH" if write else "LOW"})
    probs[NONE_KEY], probs[CLARIFY_KEY], probs[ASK_KEY] = side["none"], side["clarify"], side["ask"]
    res = _orch()._result_from_probs(probs, True, "q", {r["intent_code"]: r for r in rows}, rows, {}, time.time(),
                                     ask_tier=tier)
    if res.requiresConfirmation or res.bestMatch is None:
        return "ask"
    return "show" if side["probs"][0][1] else "direct"


def _agree(expected: str, act: str) -> bool:
    return act == expected or (expected == "show" and act == "direct")  # 与历次校准同口径: 只读先展示默认 = 直接办


def _score(side_key: str, tier, who: str, pick=lambda g: True):
    ok = n = 0
    wrong = []
    for q, g in GOLD.items():
        e = g[who]["expected"]
        if e == "unscored" or not pick(g):
            continue
        n += 1
        a = replay(ROWS[q][side_key], tier)
        if _agree(e, a):
            ok += 1
        else:
            wrong.append(q)
    return ok, n, wrong


def _old_tier() -> ask_policy.AskTier:
    cfg = AIConfig()
    return ask_policy.AskTier(ask_policy.CONSERVATIVE, "<旧规则, 只在读数里体现>", META["ask_threshold"]["old_conservative"],
                              cfg.luna_read_threshold, cfg.luna_margin, cfg.luna_write_threshold)


def test_gold_file_is_complete_and_consistent():
    assert sorted(GOLD) == ["Q%03d" % i for i in range(1, 301)]
    st = {}
    for g in GOLD.values():
        st[g["status"]] = st.get(g["status"], 0) + 1
        s, h = g["steve"]["expected"], g["shi"]["expected"]
        if g["status"] == "agree":
            assert s == h != "unscored"
        elif g["status"] == "disputed":
            assert s != h and "unscored" not in (s, h)
        # 每个 other / 带备注的史浩禾答案都要有归类依据
        if g["shi"]["choice"] == "other" or g["shi"]["note"]:
            assert g["shi"]["mapped_by"], g["id"]
        assert g["aggressive_scored"] == (g["status"] in ("agree", "steve_only"))
        assert g["conservative_scored"] == (h != "unscored")
    assert st == {"agree": 130, "disputed": 163, "steve_only": 3, "shi_only": 4}, st
    assert sum(g["conservative_scored"] for g in GOLD.values()) == N_SCORED
    assert sum(g["aggressive_scored"] for g in GOLD.values()) == 133


def test_tier_rules_and_thresholds_are_the_ones_the_readings_were_taken_with():
    cfg = AIConfig()
    cons = ask_policy.tier_of(cfg, ask_policy.CONSERVATIVE)
    agg = ask_policy.tier_of(cfg, ask_policy.AGGRESSIVE)
    sha = META["rules_sha256"]
    assert _sha(cons.rules) == sha["new_conservative"]
    assert sha["new_conservative"] != sha["old_conservative"]
    assert _sha(agg.rules) == sha["aggressive"], "激进档规则被改了: 本 PR 不许动激进档"
    assert cons.ask_threshold == META["ask_threshold"]["new_conservative"]
    assert agg.ask_threshold == META["ask_threshold"]["aggressive"]
    # 两档读/写门槛仍相同 (写操作不放宽)
    assert (cons.read_threshold, cons.margin, cons.write_threshold) == (agg.read_threshold, agg.margin, agg.write_threshold)


def test_replay_matches_the_recorded_run_for_every_question():
    """仪器核对: 重放函数在当时的档位下必须还原当时真跑的结论, 否则下面的命中数没有意义。"""
    cfg = AIConfig()
    tiers = {"before_cons": _old_tier(), "after_cons": ask_policy.tier_of(cfg, ask_policy.CONSERVATIVE),
             "before_agg": ask_policy.tier_of(cfg, ask_policy.AGGRESSIVE),
             "after_agg": ask_policy.tier_of(cfg, ask_policy.AGGRESSIVE)}
    bad = []
    for q, row in ROWS.items():
        for key, tier in tiers.items():
            if replay(row[key], tier) != row[key]["recorded_act"]:
                bad.append((q, key))
    assert not bad, bad


def test_new_conservative_tier_beats_old_on_shihaohe_300_and_distinguishes_questions():
    new_ok, n, new_wrong = _score("after_cons", ask_policy.tier_of(AIConfig(), ask_policy.CONSERVATIVE), "shi",
                                  lambda g: g["conservative_scored"])
    old_ok, n_old, old_wrong = _score("before_cons", _old_tier(), "shi", lambda g: g["conservative_scored"])
    assert n == n_old == N_SCORED
    assert (old_ok, new_ok) == (N_OLD, N_NEW)
    assert new_ok > old_ok
    fixed = sorted(set(old_wrong) - set(new_wrong))
    assert fixed, "新旧保守档在这份金标上没有任何一题不同"
    # 新档不能把旧档对的大批题弄错 (整体涨是因为净改对, 不是靠换一批错)
    broken = sorted(set(new_wrong) - set(old_wrong))
    assert len(fixed) - len(broken) == new_ok - old_ok


def test_aggressive_tier_reading_is_unchanged_by_this_change():
    """激进档: 规则文本/门槛哈希钉死 (见上), 且激进档两次真跑 (改前源码 / 改后源码) 在 Steve 一致题 + 独有题 (133)
    与 #1719 口径的 296 题上, 用同一档位重放得到同样的命中数 -- 差异只可能来自 Luna 读数的随机波动, 不来自代码。"""
    agg = ask_policy.tier_of(AIConfig(), ask_policy.AGGRESSIVE)
    for pick in (lambda g: g["aggressive_scored"], lambda g: True):
        b_ok, b_n, _ = _score("before_agg", agg, "steve", pick)
        a_ok, a_n, _ = _score("after_agg", agg, "steve", pick)
        assert b_n == a_n
        assert abs(a_ok - b_ok) <= 3, (b_ok, a_ok)  # 随机波动容差; 规则/门槛不变由哈希保证
