"""验收集工具的单测: 泄漏闸阴阳性对照、计分口径、多指标门槛 (含「总分涨但单项退步」必须被拦住)。

运行: python -m pytest tests/intent-acceptance -q
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import adjudicate_v3 as adj  # noqa: E402
import adjudicate_v3_r2 as adj2  # noqa: E402
import corpus_guard as cg  # noqa: E402
import gate  # noqa: E402
import leak_gate  # noqa: E402
import score_recognize as sr  # noqa: E402
import workflow_spec as W  # noqa: E402

CAT = [
    dict(code="MATERIAL_BATCH_QUERY", mode="READ"),
    dict(code="MATERIAL_EXPIRED_QUERY", mode="READ"),
    dict(code="MATERIAL_BATCH_CREATE", mode="WRITE"),
    dict(code="SHIPMENT_CREATE", mode="WRITE"),
    dict(code="OUT_OF_DOMAIN", mode="READ"),
]


# ---------------- 泄漏闸 ----------------
def _fp_file(tmp_path, texts):
    p = tmp_path / "fp.jsonl"
    p.write_text("".join(json.dumps(dict(fp=cg.fingerprint(t))) + "\n" for t in texts), "utf-8")
    return p


def test_leak_gate_positive_negative_and_unmeasured(tmp_path):
    fp = _fp_file(tmp_path, ["牛腱子冷冻库B的那批今天能出吗"])
    clean, dirty, empty = tmp_path / "clean", tmp_path / "dirty", tmp_path / "empty"
    for d in (clean, dirty, empty):
        d.mkdir()
    (clean / "a.jsonl").write_text(json.dumps(dict(text="今天库存还有多少")) + "\n", "utf-8")
    # 阳性: 同一句换全角/空格/标点/大小写写法也必须命中
    (dirty / "a.json").write_text(json.dumps([dict(text="牛腱子 冷冻库Ｂ的那批，今天能出吗？")]), "utf-8")
    assert leak_gate.run_gate(fp, [clean])[0] == 0
    rc, rep = leak_gate.run_gate(fp, [dirty])
    assert rc == 1 and rep["exact_hits"]
    assert leak_gate.run_gate(fp, [empty])[0] == 2          # 没扫到语料 = 没量到, 不是通过
    assert leak_gate.run_gate(tmp_path / "none.jsonl", [clean])[0] == 2  # 没有指纹 = 没量到


def test_leak_gate_near_duplicate_with_sealed_text(tmp_path):
    sealed = tmp_path / "sealed.jsonl"
    sealed.write_text(json.dumps(dict(input="帮我看一下冷冻库里快过期的牛腱子")) + "\n", "utf-8")
    fp = _fp_file(tmp_path, ["帮我看一下冷冻库里快过期的牛腱子"])
    root = tmp_path / "r"
    root.mkdir()
    (root / "x.jsonl").write_text(json.dumps(dict(text="帮我看下冷冻库里快过期的牛腱子")) + "\n", "utf-8")  # 近似, 非整句
    rc, rep = leak_gate.run_gate(fp, [root], sealed_items=sealed)
    assert rc == 1 and rep["near_hits"] and not rep["exact_hits"]


def test_leak_gate_self_test_and_repo_state():
    assert leak_gate.self_test() == 0
    # 仓库当前状态: 真实指纹文件 + 默认语料根必须无泄漏 (指纹文件不存在时本条跳过由下一条断言兜底)
    if leak_gate.DEFAULT_FP.exists():
        rc, rep = leak_gate.run_gate(leak_gate.DEFAULT_FP, leak_gate.DEFAULT_ROOTS)
        assert rc == 0, rep


def test_dev_and_sealed_halves_are_disjoint():
    dev = HERE / "dev" / "items.jsonl"
    fp = leak_gate.DEFAULT_FP
    if not (dev.exists() and fp.exists()):
        return
    dev_fps = {json.loads(x)["fp"] for x in dev.read_text("utf-8").splitlines() if x.strip()}
    sealed_fps = leak_gate.load_fps(fp)
    assert sealed_fps and dev_fps
    assert not (dev_fps & sealed_fps)


# ---------------- 域归属 ----------------
def test_stage_assignment_is_deterministic_and_total():
    assert W.stage_of("PURCHASE_ORDER_LIST", "采购订单列表") == "PROCURE"
    assert W.stage_of("QUALITY_CHECK_CREATE", "创建质检记录") == "QUALITY"
    assert W.stage_of("SHIPMENT_CREATE", "创建出货") == "SHIP"
    assert W.stage_of("OUT_OF_DOMAIN", "域外无关输入") == "OTHER"


# ---------------- 计分 ----------------
def _item(i, stance, intent, role="OPERATOR", alts=(), amb=False):
    return dict(id=i, stance=stance, intent=intent, intent_alternates=list(alts), role=role, stage="RECEIVE", style="FRONTLINE", ambiguous=amb)


def _rec(i, intent, confirm=False, http=200):
    r = dict(id=i, http=http)
    if http == 200:
        r.update(intent=intent, needs_confirm=confirm, clarify=confirm)
    return r


def test_score_definitions():
    items = [_item("a", "DIRECT", "MATERIAL_BATCH_QUERY"), _item("b", "DIRECT", "MATERIAL_BATCH_QUERY"),
             _item("c", "WRITE", "MATERIAL_BATCH_CREATE"), _item("d", "WRITE", "MATERIAL_BATCH_CREATE"),
             _item("e", "ASK", "MATERIAL_BATCH_CREATE"), _item("f", "OOD", "OUT_OF_DOMAIN"),
             _item("g", "DIRECT", "MATERIAL_BATCH_QUERY", amb=True), _item("h", "DIRECT", "MATERIAL_BATCH_QUERY")]
    res = [_rec("a", "MATERIAL_BATCH_QUERY"),                       # 直接办对
           _rec("b", "MATERIAL_EXPIRED_QUERY", confirm=True),       # 错意图且先问: 错问
           _rec("c", "MATERIAL_BATCH_CREATE", confirm=True),        # 写对
           _rec("d", "MATERIAL_BATCH_QUERY"),                       # 写被当只读直接答
           _rec("e", "SHIPMENT_CREATE"),                            # 该问没问, 还选了别的写意图且没确认 -> 误执行
           _rec("f", None, confirm=True),                           # 域外: 无匹配
           _rec("g", "SHIPMENT_CREATE"),                            # ambiguous: 不计分
           _rec("h", None, http=503)]                               # 没读数
    s = sr.score(items, res, CAT)
    o = s["overall"]
    assert s["n_ambiguous_excluded"] == 1 and s["n_unmeasured"] == 1
    assert (o["intent_acc"]["k"], o["intent_acc"]["n"]) == (2, 4)    # a✓ b✗ c✓ d✗
    assert (o["false_ask_rate"]["k"], o["false_ask_rate"]["n"]) == (1, 2)
    assert (o["missed_ask_rate"]["k"], o["missed_ask_rate"]["n"]) == (1, 1)
    assert (o["write_safe_rate"]["k"], o["write_safe_rate"]["n"]) == (1, 2)
    assert (o["write_as_read_rate"]["k"], o["write_as_read_rate"]["n"]) == (1, 2)
    assert o["wrong_write_exec_rate"]["k"] == 1                       # 只有 e
    assert (o["refuse_ood_safe_rate"]["k"], o["refuse_ood_safe_rate"]["n"]) == (1, 1)
    assert s["slices"]["role:OPERATOR"]["intent_acc"]["n"] == 4


# ---------------- 门槛 ----------------
def _summ(sha, intent, ask, wrong, slices=None, n=200, unmeasured=0.0):
    def m(rate, nn=n):
        return dict(k=round(rate * nn), n=nn, rate=rate, ci=sr.wilson(round(rate * nn), nn))
    return dict(items_sha256=sha, unmeasured_rate=unmeasured,
                overall=dict(intent_acc=m(intent), ask_direct_agree=m(ask), wrong_write_exec_rate=m(wrong)),
                slices=slices or {})


CFG = dict(floors=dict(intent_acc=0.5, ask_direct_agree=0.5), ceilings=dict(wrong_write_exec_rate=0.02),
           max_regress_overall=0.02, max_regress_slice=0.05, min_slice_n=25, max_unmeasured_rate=0.02, min_n_overall=30)


def test_gate_passes_when_equal():
    b = _summ("x", 0.8, 0.7, 0.0)
    assert gate.evaluate(b, b, CFG)[0] == 0


def test_gate_blocks_total_up_but_one_slice_down():
    """总分涨了, 但某个角色单项明显退步: 旧口径放行, 新口径必须拦。"""
    def sl(rate, n=60):
        return {"intent_acc": dict(k=round(rate * n), n=n, rate=rate, ci=sr.wilson(round(rate * n), n))}
    base = _summ("x", 0.70, 0.7, 0.0, slices={"role:QC": sl(0.80), "role:BOSS": sl(0.60)})
    cand = _summ("x", 0.78, 0.7, 0.0, slices={"role:QC": sl(0.55), "role:BOSS": sl(0.95)})  # 总体 +8 点, QC -25 点
    rc, fails, _ = gate.evaluate(cand, base, CFG)
    assert rc == 1 and any("role:QC" in f for f in fails)


def test_gate_blocks_wrong_write_and_floor_and_unmeasured():
    base = _summ("x", 0.8, 0.7, 0.0)
    assert gate.evaluate(_summ("x", 0.8, 0.7, 0.05), base, CFG)[0] == 1        # 写误执行超上限
    assert gate.evaluate(_summ("x", 0.45, 0.7, 0.0), base, CFG)[0] == 1        # 低于下限
    assert gate.evaluate(_summ("x", 0.8, 0.7, 0.0, unmeasured=0.10), base, CFG)[0] == 1


def test_gate_absolute_lines_fire_without_any_regression():
    """绝对线独立于「相对基线退步」: 基线自己就越线, 候选与基线相同 (零退步) 也必须红。"""
    bad_wrong = _summ("x", 0.8, 0.7, 0.04)
    rc, fails, _ = gate.evaluate(bad_wrong, bad_wrong, CFG)
    assert rc == 1 and any("wrong_write_exec_rate" in f and "上限" in f for f in fails)
    low = _summ("x", 0.45, 0.7, 0.0)
    rc, fails, _ = gate.evaluate(low, low, CFG)
    assert rc == 1 and any("intent_acc" in f and "下限" in f for f in fails)
    unm = _summ("x", 0.8, 0.7, 0.0, unmeasured=0.10)
    rc, fails, _ = gate.evaluate(unm, unm, CFG)
    assert rc == 1 and any("没量到" in f for f in fails)


def test_gate_small_sample_noise_is_not_a_regression():
    def sl(rate, n):
        return {"intent_acc": dict(k=round(rate * n), n=n, rate=rate, ci=sr.wilson(round(rate * n), n))}
    base = _summ("x", 0.8, 0.7, 0.0, slices={"role:QC": sl(0.80, 30)})
    cand = _summ("x", 0.8, 0.7, 0.0, slices={"role:QC": sl(0.73, 30)})         # -7 点但 n=30, 区间仍含基线
    assert gate.evaluate(cand, base, CFG)[0] == 0
    small = _summ("x", 0.8, 0.7, 0.0, slices={"role:QC": sl(0.20, 10)})         # n < min_slice_n 不检查
    assert gate.evaluate(small, base, CFG)[0] == 0


def test_gate_not_comparable_and_not_measured_are_rc2():
    base = _summ("x", 0.8, 0.7, 0.0)
    assert gate.evaluate(_summ("y", 0.8, 0.7, 0.0), base, CFG)[0] == 2           # 不是同一份验收集
    assert gate.evaluate(_summ("x", 0.8, 0.7, 0.0, n=5), base, CFG)[0] == 2      # 分母不足


# ---------------- 歧义题裁定 (adjudicate_v3) ----------------
ACAT = {"R1": dict(code="R1", mode="READ"), "R2": dict(code="R2", mode="READ"),
        "W1": dict(code="W1", mode="WRITE"), "W2": dict(code="W2", mode="WRITE")}


def _arow(stance, intent, reason, alts=(), opts=()):
    return dict(id="X1", set="dev", stage="RECEIVE", role="OPERATOR", style="FRONTLINE", input="测试句", stance=stance, intent=intent,
                intent_alternates=list(alts), ask_options=list(opts), mode=ACAT[intent]["mode"] if intent in ACAT else "",
                ambiguous=True, ambiguity_reason=reason)


def _v(stance, intent="", reason=""):
    return dict(stance=stance, intent=intent, ask_reason=reason, intent_alternates=[])


def test_adjudicate_stance_majority_both_directions():
    d = adj.decide(_arow("DIRECT", "R1", "stance:DIRECT->ASK"), _v("DIRECT", "R1"), None, ACAT)   # 出题方 + 第三票
    assert d["resolved"] and d["stance"] == "DIRECT" and d["intent"] == "R1" and "S1" in d["rule_ids"]
    d = adj.decide(_arow("ASK", "R1", "stance:ASK->DIRECT", opts=["a", "b"]), _v("DIRECT", "R1"), None, ACAT)  # 盲标方 + 第三票
    assert d["resolved"] and d["stance"] == "DIRECT" and d["ask_options"] == [] and "S2" in d["rule_ids"]
    d = adj.decide(_arow("ASK", "R1", "stance:ASK->DIRECT"), _v("WRITE", "W1"), None, ACAT)        # 三方各执一词
    assert not d["resolved"] and d["stance"] == "ASK" and d["rule_ids"][-1] == "S0"


def test_adjudicate_ask_needs_a_real_reason_and_same_name_read_is_not_asked():
    # 第三票判 ASK 但没给真歧义理由 -> 按默认执行 (写意图 WRITE), 与出题方 WRITE 成多数
    d = adj.decide(_arow("WRITE", "W1", "stance:WRITE->ASK"), _v("ASK", "W1", ""), None, ACAT)
    assert d["resolved"] and d["stance"] == "WRITE" and "P1" in d["rule_ids"]
    # 给了理由 -> 与盲标方 ASK 成多数
    d = adj.decide(_arow("WRITE", "W1", "stance:WRITE->ASK", opts=["x"]), _v("ASK", "W1", "MISSING_REQUIRED"), None, ACAT)
    assert d["resolved"] and d["stance"] == "ASK" and d["ask_options"] == ["x"]
    # 只读题的同名: 两个都列出来, 判 DIRECT
    d = adj.decide(_arow("ASK", "R1", "stance:ASK->DIRECT"), _v("ASK", "R1", "SAME_NAME"), None, ACAT)
    assert d["resolved"] and d["stance"] == "DIRECT" and "P1b" in d["rule_ids"]
    # 写操作的同名保持 ASK
    d = adj.decide(_arow("ASK", "W1", "stance:ASK->WRITE"), _v("ASK", "W1", "SAME_NAME"), None, ACAT)
    assert d["resolved"] and d["stance"] == "ASK"


def test_adjudicate_intent_majority_and_mode_consistency():
    # 第三票意图不在出题方候选里, 第二票也不给 -> 保持歧义
    d = adj.decide(_arow("DIRECT", "R1", "intent"), _v("DIRECT", "R2"), _v("DIRECT", ""), ACAT)
    assert not d["resolved"] and d["rule_ids"][-1] == "I0"
    # 两票一致 -> 采纳, 出题方原意图留作替代
    d = adj.decide(_arow("DIRECT", "R1", "intent"), _v("DIRECT", "R2"), _v("DIRECT", "R2"), ACAT)
    assert d["resolved"] and d["intent"] == "R2" and d["intent_alternates"] == ["R1"] and "I3" in d["rule_ids"]
    # 第三票意图在出题方替代里 -> 出题方为主
    d = adj.decide(_arow("DIRECT", "R1", "intent", alts=["R2"]), _v("DIRECT", "R2"), None, ACAT)
    assert d["resolved"] and d["intent"] == "R2" and "I1" in d["rule_ids"]
    # 写操作一律 WRITE: 姿态多数是 WRITE 但候选意图全是只读 -> 不硬凑, 保持歧义
    d = adj.decide(_arow("ASK", "R1", "stance:ASK->WRITE"), _v("WRITE", "R1"), None, ACAT)
    assert not d["resolved"] and d["rule_ids"][-1] == "P3"
    # DIRECT 不得带写意图当主意图, 替代意图也按读写过滤
    d = adj.decide(_arow("ASK", "W1", "stance:ASK->DIRECT", alts=["R1", "W2"]), _v("DIRECT", "R1"), None, ACAT)
    assert d["resolved"] and d["intent"] == "R1" and d["intent_alternates"] == []


def test_adjudicate_refuse_ood_and_label_vocabulary():
    d = adj.decide(_arow("REFUSE", "", "stance:REFUSE->OOD"), _v("OOD", ""), None, ACAT)
    assert d["resolved"] and d["stance"] == "OOD" and d["intent"] == "OUT_OF_DOMAIN" and d["intent_alternates"] == []
    assert adj.label_stance_of(_arow("ASK", "R1", "stance:ASK->READ")) == "DIRECT"       # 标注方说 READ = 只读直接办
    assert adj.label_stance_of(_arow("OOD", "", "stance:OOD->SYSTEM_HELP")) is None      # 非法词不算票
    assert adj.as_list("A, B") == ["A", "B"] and adj.as_list("A") == ["A"] and adj.as_list([]) == []


def test_adjudicate_apply_is_reversible_and_idempotent():
    raw = _arow("ASK", "R1", "stance:ASK->DIRECT")
    raw["intent_alternates"], raw["ask_options"] = "R2", "甲, 乙"                            # 字符串字段是 assemble 的既有问题
    once = adj.apply_row(raw, _v("DIRECT", "R1"), None, ACAT)
    assert once["ambiguous"] is False and once["stance"] == "DIRECT" and once["orig_stance"] == "ASK"
    assert once["intent_alternates"] == ["R2"] and once["orig_intent_alternates"] == "R2"
    assert once["adjudication"]["status"] == "resolved" and "测试句" not in json.dumps(once["adjudication"], ensure_ascii=False)
    assert adj.base_row(once) == raw                                                       # 能整行回滚
    assert adj.apply_row(once, _v("DIRECT", "R1"), None, ACAT) == once                     # 重跑结果不变
    pend = adj.apply_row(raw, _v("WRITE", "W1"), None, ACAT)
    assert pend["ambiguous"] is True and pend["stance"] == "ASK" and pend["adjudication"]["status"] == "pending_review"


def test_dev_items_adjudication_invariants():
    dev = HERE / "dev" / "items.jsonl"
    rows = [json.loads(x) for x in dev.read_text("utf-8").splitlines() if x.strip()]
    assert len(rows) == 2208 and len({r["id"] for r in rows}) == 2208
    for r in rows:                                                                         # N0c: 字符串字段会被 score_recognize 按字符展开
        assert isinstance(r["intent_alternates"], list) and isinstance(r["ask_options"], list), r["id"]
    n_adj = 0
    for r in rows:
        ad = r.get("adjudication")
        if not ad:
            assert r["ambiguous"] is False and "orig_stance" not in r                      # 确认题一行不改
            continue
        n_adj += 1
        assert r["orig_ambiguity_reason"] and r["orig_stance"] in adj.STANCES
        assert ad["ruleset"] in adj2.RULESETS and ad["rule_id"]
        assert r["input"] not in json.dumps(ad, ensure_ascii=False)
        assert isinstance(r["intent_alternates"], list) and isinstance(r["ask_options"], list)
        if ad["status"] == "resolved":
            assert r["ambiguous"] is False and r["stance"] in adj.STANCES
            if r["stance"] in adj.INTENTFUL:
                assert r["mode"] != "READ" or r["stance"] in ("DIRECT", "ASK")             # 读意图不能是 WRITE 姿态
                assert r["mode"] != "WRITE" or r["stance"] in ("WRITE", "ASK")             # 写意图不能是 DIRECT 姿态
            if r["stance"] in ("REFUSE", "OOD"):
                assert r["intent"] in ("", adj.OOD_CODE) and not r["intent_alternates"]
        else:
            assert ad["status"] == "pending_review" and r["ambiguous"] is True and r["stance"] == r["orig_stance"] and ad["pending_cause"]
    assert n_adj == 980                                                                    # 原歧义题一条不少, 要么裁定要么待复核


# ---------------- 第二轮裁定 (decide2 / apply_row2) ----------------
def _pend(stance, intent, reason, v1, v2=None, alts=(), opts=(), text="测试句"):
    """第一轮后仍待复核的行 (用真实的 apply_row 造, 不手搓 adjudication)。"""
    row = adj.apply_row(_arow(stance, intent, reason, alts=alts, opts=opts), v1, v2, ACAT)
    row["input"] = text
    assert row["adjudication"]["status"] == "pending_review", row["adjudication"]
    return row


def _d2(row, j=None):
    return adj2.decide2(adj2.restore_pending(row), j, ACAT)


def _ji(intent, reason="r"):
    return dict(intent=intent, reason=reason)


def _rel(a, b="same"):
    return dict(rel=dict(specific=a), rel2=dict(specific=a if b == "same" else b))


def test_round2_intent_two_judges_agree_or_hierarchy_or_stay_pending():
    row = _pend("DIRECT", "R1", "intent", _v("DIRECT", "R2"), _v("DIRECT", "W1"))               # 意图无多数 (I0)
    assert _d2(row)["need"] == "judge"                                                          # 还没问判官 -> 先问, 不是裁定
    d = _d2(row, dict(ja=_ji("R2"), jb=_ji("R2")))                                              # 两判官一致
    assert d["resolved"] and d["intent"] == "R2" and d["intent_alternates"] == ["R1"] and "J1" in d["rule_ids"]
    d = _d2(row, dict(ja=_ji("R1"), jb=_ji("R2")))
    assert not d["resolved"] and d["need"] == "rel"                                             # 不一致 -> 要上下位判断
    d = _d2(row, dict(ja=_ji("R1"), jb=_ji("R2"), **_rel("R2")))                                 # 两个模型都认定 R2 更具体
    assert d["resolved"] and d["intent"] == "R2" and d["intent_alternates"] == ["R1"] and "J2" in d["rule_ids"]
    d = _d2(row, dict(ja=_ji("R1"), jb=_ji("R2"), **_rel("R2", "R1")))                           # 两个模型对谁更具体意见不同 -> 不算
    assert not d["resolved"] and d["rule_ids"][-1] == "J0"
    d = _d2(row, dict(ja=_ji("R1"), jb=_ji("R2"), **_rel("R2", "NONE")))
    assert not d["resolved"] and d["rule_ids"][-1] == "J0"
    d = _d2(row, dict(ja=_ji("R1"), jb=_ji("R2"), rel=dict(specific="NONE"), rel2=dict(specific="NONE")))
    assert not d["resolved"] and d["rule_ids"][-1] == "J0"
    d = _d2(row, dict(ja=_ji("R1"), jb=_ji("R1")))                                               # 一致且在候选内
    assert d["resolved"] and d["intent"] == "R1"
    d = _d2(row, dict(ja=_ji("NONE"), jb=_ji("NONE")))                                           # 都判都不对: 不许当成一致
    assert not d["resolved"] and d["rule_ids"][-1] == "J0"
    d = _d2(row, dict(ja=_ji("R2"), jb=_ji("NONE")))
    assert not d["resolved"] and d["rule_ids"][-1] == "J0"
    d = _d2(row, dict(ja=_ji("R9"), jb=_ji("R9")))                                               # 一致但不在候选里 (模型编的代码)
    assert not d["resolved"] and d["rule_ids"][-1] == "J0"


def test_round2_hierarchy_requires_same_mode():
    row = _pend("ASK", "R1", "intent", _v("ASK", "W1", "DIVERGENT_INTENTS"), _v("ASK", "W2"), opts=["a", "b"])
    # R1 (读) 与 W1 (写) 不能互为上下位, 即使两个模型都说 W1 更具体
    d = _d2(row, dict(ja=_ji("R1"), jb=_ji("W1"), **_rel("W1")))
    assert not d["resolved"] and d["rule_ids"][-1] == "J0"


def test_round2_stance_rules_and_review():
    # 三方各异: 出题 ASK / 盲标 DIRECT / 第三票 WRITE, 意图只读 -> 去掉无理由的 ASK, 取与读写属性一致的 DIRECT (S4)
    row = _pend("ASK", "R1", "stance:ASK->DIRECT", _v("WRITE", "R1"))
    d = _d2(row)
    assert d["resolved"] and d["stance"] == "DIRECT" and d["intent"] == "R1" and d["rule_ids"] == ["S4"] and d["ask_options"] == []
    # 同样三方各异, 但意图是写 -> WRITE
    row = _pend("ASK", "W1", "stance:ASK->DIRECT", _v("WRITE", "W1"))
    d = _d2(row)
    assert d["resolved"] and d["stance"] == "WRITE" and d["rule_ids"] == ["S4"]
    # 没有一票与读写属性一致 (写意图, 票是 ASK/DIRECT/REFUSE) -> 规则判不了, 先要复核
    row = _pend("ASK", "W1", "stance:ASK->DIRECT", _v("REFUSE", "W1"))
    assert _d2(row)["need"] == "review"
    d = _d2(row, dict(review=dict(stance="WRITE", ask_reason="")))
    assert d["resolved"] and d["stance"] == "WRITE" and "S6" in d["rule_ids"]
    d = _d2(row, dict(review=dict(stance="ASK", ask_reason="")))                                 # 复核判 ASK 没给三类理由 -> 默认执行
    assert d["resolved"] and d["stance"] == "WRITE" and "P1" in d["rule_ids"]
    d = _d2(row, dict(review=dict(stance="OOD", ask_reason="")))                                 # 意图是真意图时判 OOD -> 冲突, 保持待复核
    assert not d["resolved"] and d["rule_ids"][-1] == "S6"
    d = _d2(row, dict(review=dict(stance="REFUSE", ask_reason="")))                              # REFUSE 有第三票支持 -> 采纳
    assert d["resolved"] and d["stance"] == "REFUSE" and d["intent"] == ""
    row2 = _pend("ASK", "W1", "stance:ASK->DIRECT", _v("OOD", "W1"))                             # 票: ASK / DIRECT / OOD, 没有 REFUSE 票
    assert _d2(row2)["need"] == "review"                                                         # OOD 票作废, 剩下的都与写意图不一致 -> 复核
    d = _d2(row2, dict(review=dict(stance="REFUSE", ask_reason="")))                             # 复核单独判 REFUSE, 没有他票支持 -> 不采纳
    assert not d["resolved"] and d["rule_ids"][-1] == "S6"
    d = _d2(row2, dict(review=dict(stance="OOD", ask_reason="")))                                # 第三票确实投了 OOD, 但意图是目录里的真写意图 W1 -> 冲突, 不采纳
    assert not d["resolved"] and d["rule_ids"][-1] == "S6" and "冲突" in d["pending_cause"]
    d = _d2(row2, dict(review=dict(stance="WRITE", ask_reason="")))
    assert d["resolved"] and d["stance"] == "WRITE"
    # 第三票带理由的 ASK 不被规则吞掉, 要复核
    row = _pend("WRITE", "W1", "stance:WRITE->DIRECT", _v("ASK", "W1", "MISSING_REQUIRED"))
    assert _d2(row)["need"] == "review"
    d = _d2(row, dict(review=dict(stance="ASK", ask_reason="MISSING_REQUIRED")))                 # 写操作缺必填: 保持 ASK
    assert d["resolved"] and d["stance"] == "ASK"
    # 只读意图: 复核的 ASK 理由是同名 / 缺必填 -> 仍按默认直接办 (P1b / P1c)
    row = _pend("ASK", "R1", "stance:ASK->DIRECT", _v("REFUSE", "R1"))
    for why, rule in (("SAME_NAME", "P1b"), ("MISSING_REQUIRED", "P1c")):
        d = _d2(row, dict(review=dict(stance="ASK", ask_reason=why)))
        assert d["resolved"] and d["stance"] == "DIRECT" and rule in d["rule_ids"]
    d = _d2(row, dict(review=dict(stance="ASK", ask_reason="DIVERGENT_INTENTS")))                # 结果差别大且都说得通: 只读也可以先问
    assert d["resolved"] and d["stance"] == "ASK"


def test_round2_ood_needs_review_and_never_for_factory_text():
    # 三方各异且意图就是 OUT_OF_DOMAIN: OOD 也要过复核, 不由规则直接定
    ood_cat = dict(ACAT, OUT_OF_DOMAIN=dict(code="OUT_OF_DOMAIN", mode="READ"))
    row = adj.apply_row(_arow("OOD", "OUT_OF_DOMAIN", "stance:OOD->ASK"), _v("DIRECT", "OUT_OF_DOMAIN"), None, ACAT)
    row["input"] = "今天天气真好"
    assert row["adjudication"]["status"] == "pending_review"
    r = adj2.restore_pending(row)
    assert adj2.decide2(r, None, ood_cat)["need"] == "review"
    d = adj2.decide2(r, dict(review=dict(stance="OOD", ask_reason="")), ood_cat)
    assert d["resolved"] and d["stance"] == "OOD" and d["intent"] == "OUT_OF_DOMAIN" and d["intent_alternates"] == []
    # 同样的票, 但话里点名了工厂里的东西 (牛腱子) -> 不是闲聊, 保持待复核
    r["input"] = "牛腱子那批今天天气真热"
    d = adj2.decide2(r, dict(review=dict(stance="OOD", ask_reason="")), ood_cat)
    assert not d["resolved"] and d["rule_ids"][-1] == "S7"
    # 意图是 OUT_OF_DOMAIN 而复核说要办事 -> 自相矛盾
    d = adj2.decide2(r, dict(review=dict(stance="WRITE", ask_reason="")), ood_cat)
    assert not d["resolved"]


def test_round2_mode_fix_and_ask_keeps_options():
    # 姿态多数 WRITE 但所选意图只读 -> 以目录读写属性为准修成 DIRECT (R3)
    row = _pend("WRITE", "R1", "stance:WRITE->WRITE", _v("WRITE", "R1"))
    assert row["adjudication"]["rule_id"].endswith("P3")
    d = _d2(row)
    assert d["resolved"] and d["stance"] == "DIRECT" and d["intent"] == "R1" and "R3" in d["rule_ids"]
    # 反向: 姿态 DIRECT 多数而意图是写 -> WRITE
    row = _pend("DIRECT", "W1", "stance:DIRECT->DIRECT", _v("DIRECT", "W1"))
    d = _d2(row)
    assert d["resolved"] and d["stance"] == "WRITE" and "R3" in d["rule_ids"]
    # ASK 多数不动姿态, 询问选项保留
    row = _pend("ASK", "R1", "stance:ASK->ASK", _v("ASK", "R2", "DIVERGENT_INTENTS"), _v("ASK", "W1"), opts=["甲", "乙"])
    d = _d2(row, dict(ja=_ji("R2"), jb=_ji("R2")))
    assert d["resolved"] and d["stance"] == "ASK" and d["ask_options"] == ["甲", "乙"]


def test_round2_apply_is_reversible_idempotent_and_keeps_round1():
    raw = _arow("DIRECT", "R1", "intent")
    raw["intent_alternates"] = "W2"                                                            # 字符串字段 (assemble 的既有问题)
    r1 = adj.apply_row(raw, _v("DIRECT", "R2"), _v("DIRECT", "W1"), ACAT)
    j = dict(ja=_ji("R2"), jb=_ji("R2"))
    once = adj2.apply_row2(r1, j, ACAT)
    assert once["ambiguous"] is False and once["intent"] == "R2" and once["adjudication"]["status"] == "resolved"
    assert once["adjudication"]["round1"]["rule_id"] == r1["adjudication"]["rule_id"] and "+R2+" in once["adjudication"]["rule_id"]
    assert adj.base_row(once) == raw                                                           # 整行仍能回滚到最初
    assert adj2.apply_row2(once, None, ACAT) == once                                           # 第二轮票存在行里, 无缓存也能重跑
    assert adj2.apply_row2(once, j, ACAT) == once
    stay = adj2.apply_row2(r1, dict(ja=_ji("NONE"), jb=_ji("NONE")), ACAT)
    assert stay["ambiguous"] is True and stay["stance"] == r1["stance"] and stay["adjudication"]["status"] == "pending_review"
    assert stay["adjudication"]["pending_cause"] and adj.base_row(stay) == raw
    assert adj2.apply_row2(stay, None, ACAT) == stay
    done = adj.apply_row(_arow("ASK", "R1", "stance:ASK->DIRECT"), _v("DIRECT", "R1"), None, ACAT)   # 第一轮已裁定的不动
    assert adj2.apply_row2(done, None, ACAT) == done
    leak = adj2.apply_row2(r1, dict(ja=dict(intent="R2", reason="测试句"), jb=_ji("R2")), ACAT)       # 理由里整句抄原话 -> 截掉
    assert "测试句" not in json.dumps(leak["adjudication"], ensure_ascii=False)


def test_round2_normalizes_string_fields_on_confirmed_rows_only_in_type():
    raw = _arow("DIRECT", "R1", "")
    raw.update(ambiguous=False, intent_alternates="R2, W1", ask_options="甲，乙")
    out = adj2.apply_row2(raw, None, ACAT)
    assert out["intent_alternates"] == ["R2", "W1"] and out["ask_options"] == ["甲", "乙"]
    assert {k: v for k, v in out.items() if k not in ("intent_alternates", "ask_options")} == {k: v for k, v in raw.items() if k not in ("intent_alternates", "ask_options")}
    assert adj2.apply_row2(out, None, ACAT) == out
    assert all(len(a) > 1 for a in out["intent_alternates"])                                  # 不再是单字符 (被按字符展开的症状)
    # 产品入口: score_recognize 对字符串会按字符展开, 列表不会
    assert list(raw["intent_alternates"]) != out["intent_alternates"]


def test_dev_items_round2_invariants():
    dev = HERE / "dev" / "items.jsonl"
    rows = [json.loads(x) for x in dev.read_text("utf-8").splitlines() if x.strip()]
    r2 = [r for r in rows if "round2" in r.get("adjudication", {})]
    assert len(r2) == 251                                                                    # 第一轮留下的 251 条全部走过第二轮
    for r in r2:
        ad = r["adjudication"]
        assert ad["round1"]["status"] == "pending_review" and "+R2+" in ad["rule_id"]
        assert r["input"] not in json.dumps(ad, ensure_ascii=False)
        if ad["status"] == "resolved":
            assert ad["round2"]["rule_id"] and ad["ruleset"] == adj2.RULESET2
            assert r["intent"] not in r["intent_alternates"] and adj.OOD_CODE not in r["intent_alternates"]
            assert len(set(r["intent_alternates"])) == len(r["intent_alternates"])
            if r["stance"] == "OOD":                                                         # S7: 新判的 OOD 不许点名工厂实体
                assert not [t for t in adj2.FACTORY_TERMS if t in r["input"]] or "Sm" in ad["round2"]["rule_id"]

