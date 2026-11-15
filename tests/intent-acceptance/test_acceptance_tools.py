"""验收集工具的单测: 泄漏闸阴阳性对照、计分口径、多指标门槛 (含「总分涨但单项退步」必须被拦住)。

运行: python -m pytest tests/intent-acceptance -q
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
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
