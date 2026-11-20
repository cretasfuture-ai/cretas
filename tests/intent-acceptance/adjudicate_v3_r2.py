"""v3 开发半边歧义题 第二轮裁定: 对 adjudicate_v3 第一轮仍待复核的题再深一层, 可复跑, 每题留依据, 第一轮结果和 orig_* 原标签都保留可回滚。

第一轮 (adjudicate_v3.py) 之后还剩 251 题 (意图无多数 180 / 姿态三方各异 63 / 姿态与意图读写冲突 8)。本轮口径 (与第一轮一致, 偏执行):
  意图无多数: 给强模型候选意图的完整定义 (代码 + 中文名 + 类别 + 说明全文 + 读写属性 + 必填实体, 来自 Python 侧 factory-intent-catalog.json),
              两个强模型 (qwen-max / qwen3.5-plus) 各自独立在候选里选或判「都不对」并给理由; 两次一致 -> 定;
              不一致但第三次判断认定其中一个是另一个的明显上位/子集 (同读写) -> 取更具体的, 另一个放 intent_alternates (列表);
              仍不一致 -> 保持 ambiguous。
  姿态三方各异: 先套规则 (S4), 规则判不了再一次强模型复核 (S6)。
  读写冲突:   以意图目录里该意图的读写属性为准修姿态 (R3)。

规则编号 (adjudication.rule_id = 第一轮规则 + R2 + 第二轮规则):
  J1  两个强模型选同一个候选意图 -> 定
  J2  两个强模型选得不同, 另外两个强模型 (各自独立) 都认定其中同一个是另一个的明显上位/子集的「更具体者」(且读写相同) -> 取更具体的, 另一个进 intent_alternates
  J0  仍不一致 / 判「都不对」 / 给了不在候选里的代码 -> 保持 ambiguous
  Sm  姿态多数 (出题 / 盲标 / 第三票里至少两票相同)
  S4  姿态三方各异: 去掉无三类真歧义理由的 ASK 票、去掉 (意图是目录里真意图时的) OOD 票; 剩下的票里有与意图读写属性一致的 (读 -> DIRECT, 写 -> WRITE) 就取它
  S6  规则判不了 (含 REFUSE 票 / 带理由的 ASK 票 / 没有一票与读写属性一致) -> 一次强模型复核; ASK 仍须带三类理由 (否则 P1 按默认执行),
      只读意图的 ASK 理由是同名 / 缺必填 -> 仍按默认执行 (P1b / P1c); REFUSE 须另有一票支持; 复核判 OOD 但意图已是目录里的真意图 -> 冲突, 保持待复核
  S7  S4 / S6 判成 OOD, 但原话点名了出题世界观里的工厂实体 (人 / 物料 / 产品 / 客户 / 供应商 / 设备) -> 不是「与工厂无关的闲聊」, 保持待复核
  R3  姿态是 DIRECT / WRITE 但与所选意图的读写属性冲突 -> 以意图目录的读写属性为准修姿态
  (N0c 在 adjudicate_v3.normalize_lists: 确认题字符串字段改列表)

第二轮票 (判官意图 / 理由 / 上下位 / 复核) 写进 adjudication.round2.j, 所以不需要缓存也能复跑 apply2, 结果字节相同。
封存半边 / 隔离集 / sealed_v3_fingerprints.jsonl 不读不改。

子命令 (在本目录运行, 千问凭证见 creds.py, 只进内存):
  deepen  对第一轮待复核的题跑判官 A/B -> 上下位判断 -> 姿态复核, 结果写 <work>/r2-*.jsonl, 断点续跑
  apply2  读缓存 (没有就用行里存的第二轮票) + 规则, 重写 dev/items.jsonl, 输出统计 JSON 与剩余待复核 CSV
"""
import argparse
import collections
import csv
import json
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import adjudicate_v3 as A  # noqa: E402

RULESET2 = "adjudicate-v3-r2-20261115"
RULESETS = (A.RULESET, RULESET2)
J_A_MODEL, J_B_MODEL, J_REL_MODEL, J_REV_MODEL = "qwen-max", "qwen3.5-plus", "qwen-max", "qwen-max"
STANCES, ASK_REASONS, OOD_CODE = A.STANCES, A.ASK_REASONS, A.OOD_CODE
as_list = A.as_list


# 出题世界观 (gen_workflow_set.WORLD) 里的实体词; 原话点到其中任何一个, 就不能被判成 OOD (规则 S7)
FACTORY_TERMS = ("牛腱子", "鸡胸肉", "白砂糖", "酱油", "大豆油", "草莓", "牛乳", "牛奶", "乳酸菌", "包装袋", "纸箱", "周转框", "酸奶", "卤牛肉",
                 "鸡肉丸", "牛肉粒", "张三", "李娟", "王海", "赵磊", "陈敏", "东城", "鲜丰", "北街", "南湖", "恒丰", "绿源", "江南包材",
                 "杀菌釜", "灌装机", "电子秤", "冷冻库", "车间", "班长", "仓库",
                 "单子", "单号", "批次", "这批", "那批", "货", "报表", "报告", "生产", "库存", "订单", "质检", "放行", "发货", "出库", "入库")


def mode_stance(mode):
    return "DIRECT" if mode == "READ" else "WRITE"


def restore_pending(row):
    """把第一轮待复核的行 (含已被第二轮处理过的) 还原到第一轮之后的状态; 其它行返回 None。"""
    ad = row.get("adjudication")
    if not ad:
        return None
    r1 = ad.get("round1", ad)
    if r1.get("status") != "pending_review":
        return None
    r = dict(row)
    r.update(stance=row["orig_stance"], intent=row["orig_intent"], intent_alternates=as_list(row["orig_intent_alternates"]),
             ask_options=as_list(row["orig_ask_options"]), mode=row["orig_mode"], ambiguous=True,
             ambiguity_reason=row["orig_ambiguity_reason"])
    r["adjudication"] = r1
    return r


def _mode_ok(stance, mode):
    return stance == "ASK" or (stance == "DIRECT" and mode == "READ") or (stance == "WRITE" and mode == "WRITE")


def _vote_intent(row, by_code):
    """第一轮两票里已经有多数的意图 (与 decide() 的 I1/I2/I3 同口径); 没有返回 None。"""
    v = row["adjudication"]["votes"]
    cand_gen = [c for c in ([row["intent"]] + row["intent_alternates"]) if c in by_code]
    i1, i2 = v.get("v1_intent") or "", v.get("v2_intent") or ""
    if i1 in cand_gen:
        return i1
    if i2 in cand_gen:
        return i2
    if i1 and i1 == i2 and i1 in by_code:
        return i1
    return None


def decide2(row, j, by_code):
    """第二轮裁定 (纯函数)。row = restore_pending 之后的行; j = 已有的第二轮票 {ja, jb, rel, review} (缺的键 = 还没问)。
    返回 dict(resolved, need, stance, intent, intent_alternates, ask_options, rule_ids, pending_cause); need 非空 = 还缺这类票, 先去问。"""
    j = j or {}
    v = row["adjudication"]["votes"]
    gen, lab, v1e = v.get("gen"), v.get("label"), v.get("v1_eff")
    out = dict(resolved=False, need="", stance=row["stance"], intent=row["intent"], intent_alternates=row["intent_alternates"],
               ask_options=row["ask_options"], rule_ids=[], pending_cause="")
    rules = out["rule_ids"]

    def pend(cause, rule):
        rules.append(rule)
        out["pending_cause"] = cause
        return out

    votes = [s for s in (gen, lab, v1e) if s]
    top = collections.Counter(votes).most_common()
    majority = top[0][0] if top and top[0][1] >= 2 else None
    if majority in ("REFUSE", "OOD"):
        rules.append("Sm")
        out.update(resolved=True, stance=majority, intent=OOD_CODE if majority == "OOD" else "", intent_alternates=[], ask_options=[])
        return out

    # ---- 意图
    cand_gen = [c for c in ([row["intent"]] + row["intent_alternates"]) if c in by_code]
    cands = []
    for c in cand_gen + [v.get("v1_intent") or "", v.get("v2_intent") or ""]:
        if c in by_code and c not in cands:
            cands.append(c)
    extra_alts = []
    primary = _vote_intent(row, by_code)
    if primary is None:
        ja, jb = j.get("ja"), j.get("jb")
        if ja is None or jb is None:
            out["need"] = "judge"
            return out
        a, b = ja.get("intent"), jb.get("intent")
        if a == b and a in cands:
            primary = a
            rules.append("J1")
        elif a in cands and b in cands:
            rel, rel2 = j.get("rel"), j.get("rel2")
            if rel is None or rel2 is None:
                out["need"] = "rel"
                return out
            spec = rel.get("specific")
            # 上下位要两个强模型独立给出同一个「更具体」才算 (单个模型很容易把「相近」说成「包含」)
            if spec in (a, b) and spec == rel2.get("specific") and by_code[a]["mode"] == by_code[b]["mode"]:
                primary = spec
                extra_alts = [b if spec == a else a]
                rules.append("J2")
            else:
                return pend("两个强模型意图不一致且无上下位: %s vs %s" % (a, b), "J0")
        else:
            return pend("强模型意图无共识 (NONE=都不对): %s vs %s" % (a, b), "J0")
    mode = by_code[primary]["mode"]
    want = "OOD" if primary == OOD_CODE else mode_stance(mode)

    # ---- 姿态
    if majority:
        stance = majority
        rules.append("Sm")
    else:
        pool = set(votes) - ({"OOD"} if primary != OOD_CODE else set())   # 意图是目录里的真意图, OOD 票作废
        asks_valid = v1e == "ASK"                          # 只有第三票的 ASK 带理由 (第一轮已校验); 其它 ASK 票无理由, 作废
        # OOD 只用于与工厂无关的闲聊, 「系统做不了 / 不该做」是 REFUSE, 两者容易混: OOD 一律过一次强模型复核, 不走规则直接定
        if "REFUSE" not in pool and not asks_valid and want in pool and want != "OOD":
            stance = want
            rules.append("S4")
        else:
            rv = j.get("review")
            if rv is None:
                out["need"] = "review"
                return out
            st = rv.get("stance")
            if st not in STANCES:
                return pend("复核给出非法姿态: %s" % st, "S6")
            if st == "ASK":
                if rv.get("ask_reason") not in ASK_REASONS:
                    st = want
                    rules.append("P1")
                elif rv.get("ask_reason") in ("SAME_NAME", "MISSING_REQUIRED") and mode == "READ":
                    st = "DIRECT"                          # 只读题同名两个都列出来 / 缺的参数按默认 (Steve 口径), 不先问
                    rules.append("P1b" if rv.get("ask_reason") == "SAME_NAME" else "P1c")
            if st == "OOD" and primary != OOD_CODE:        # 意图已是目录里的真意图 (OOD 只用于与工厂无关的闲聊): 冲突, 不硬判
                return pend("复核判 OOD 与已确定的真意图 %s 冲突" % primary, "S6")
            if st in ("REFUSE", "OOD") and st not in votes:
                return pend("复核判 %s 但三票里没有第二票支持" % st, "S6")
            stance = st
            rules.append("S6")
    if stance == "OOD" and not majority:
        hit = [t for t in FACTORY_TERMS if t in row["input"]]
        if hit:                                            # 话里点名了工厂里的人/物/客户/设备: 不是「与工厂无关的闲聊」, 不硬判 OOD
            return pend("判 OOD 但原话提到工厂实体 %s (OOD 只用于与工厂无关的闲聊)" % hit[:3], "S7")
    if stance in ("REFUSE", "OOD"):
        out.update(resolved=True, stance=stance, intent=OOD_CODE if stance == "OOD" else "", intent_alternates=[], ask_options=[])
        return out
    if primary == OOD_CODE:                                # 意图是「与工厂无关」而姿态要办事: 自相矛盾, 保持待复核
        return pend("意图 %s 与姿态 %s 矛盾" % (OOD_CODE, stance), "S6")
    if stance in ("DIRECT", "WRITE") and stance != want:
        stance = want
        rules.append("R3")

    alts = []
    for c in cand_gen + extra_alts:
        if c != primary and c != OOD_CODE and c not in alts and _mode_ok(stance, by_code[c]["mode"]):
            alts.append(c)
    out.update(resolved=True, stance=stance, intent=primary, intent_alternates=alts,
               ask_options=row["ask_options"] if stance == "ASK" else [])
    return out


def apply_row2(row, j, by_code):
    """第二轮: 返回新行。第一轮已裁定的行原样; 确认题只做 N0c。可重复运行 (先还原到第一轮之后的状态)。"""
    r = restore_pending(row)
    if r is None:
        return A.normalize_lists(row) if not row.get("ambiguous") else row
    if j is None:
        j = (row["adjudication"].get("round2") or {}).get("j")
    j = {k: ({x: (("…" if r["input"] in y else y.strip()[:80]) if x == "reason" and isinstance(y, str) else y) for x, y in v.items()}
             if isinstance(v, dict) else v) for k, v in (j or {}).items()}   # 理由里原话整句出现就截掉, 其余最多 80 字
    d = decide2(r, j, by_code)
    r1 = r["adjudication"]
    rnd2 = dict(ruleset=RULESET2, rule_id="+".join(d["rule_ids"]), need=d["need"], j=j or {})
    if d["resolved"]:
        new = dict(r)
        new.update(stance=d["stance"], intent=d["intent"], intent_alternates=d["intent_alternates"], ask_options=d["ask_options"],
                   ambiguous=False, ambiguity_reason="")
        new["mode"] = by_code[d["intent"]]["mode"] if d["intent"] in by_code else ""
        import assemble_v3
        bad = assemble_v3.validate(dict(input=new["input"], stance=new["stance"], intent=new["intent"]), by_code)
        if not bad:
            new["adjudication"] = dict(ruleset=RULESET2, status="resolved", rule_id=r1["rule_id"] + "+R2+" + "+".join(d["rule_ids"]),
                                       votes=r1["votes"], pending_cause="", round1=r1, round2=rnd2)
            return new
        d["rule_ids"].append("V0")
        d["pending_cause"] = "重装配校验失败: " + bad
        rnd2["rule_id"] = "+".join(d["rule_ids"])
    new = dict(r)
    new["adjudication"] = dict(ruleset=RULESET2, status="pending_review",
                               rule_id=r1["rule_id"] + "+R2+" + (rnd2["rule_id"] or "NEED_" + d["need"]),
                               votes=r1["votes"], pending_cause=d["pending_cause"] or ("缺第二轮票: " + d["need"]), round1=r1, round2=rnd2)
    return new


# ----------------------------------------------------------------- 提示词与调用
def full_catalog():
    """候选意图的完整定义: Python 侧 factory-intent-catalog.json (说明全文 / 必填实体) + scan_catalog 的读写属性。"""
    mode = {c["code"]: c["mode"] for c in A.load_catalog()}
    raw = json.loads((HERE.parents[1] / "backend/python/ai/data/factory-intent-catalog.json").read_text("utf-8-sig"))["canonical_intents"]
    out = {}
    for c in raw:
        if c["intent_code"] in mode:
            req = c.get("required_entities")
            out[c["intent_code"]] = dict(code=c["intent_code"], name=c["intent_name"], cat=c["intent_category"], tool=c["tool_name"],
                                         mode=mode[c["intent_code"]], desc=c.get("description") or "",
                                         req="" if req in (None, "", "[]") else req)
    return out


def cand_def(c):
    return "%s｜%s｜%s｜类别 %s｜%s%s" % (c["code"], "读" if c["mode"] == "READ" else "写", c["name"], c["cat"], c["desc"],
                                       ("｜必填实体 %s" % c["req"]) if c["req"] else "")


def _role(r):
    import workflow_spec as W
    return W.ROLES[r["role"]].split("（")[0]


def judge_system(catalog):
    import gen_workflow_set as G
    return ("你是食品工厂 AI 助手的独立裁判。每题给出用户原话（角色已标明）和 2-4 个候选意图的完整定义。"
            "请判断识别层应当把这句话归到哪一个候选意图；如果没有一个合适就填 NONE。你看不到任何参考答案，必须给一句理由（40 字内）。\n\n"
            + G.WORLD + "\n\n" + G.POLICY + "\n\n"
            "判断要点：看动词和对象，意图的读写属性要与话的实际用途相符（想查看 -> 读，想办掉/录入/审批 -> 写）；"
            "说明里的必填参数缺了不影响归类。\n\n全目录（代码|读写|名称），仅供你了解相邻意图，只能在题目给的候选里选：\n"
            + "\n".join("%s|%s|%s" % (c["code"], "读" if c["mode"] == "READ" else "写", c["name"]) for c in catalog))


def judge_user(items):
    parts = []
    for i, (r, cands, fc) in enumerate(items, 1):
        parts.append("%d｜%s｜%s\n候选：\n%s" % (i, _role(r), r["input"], "\n".join("  - " + cand_def(fc[c]) for c in sorted(cands))))
    return ("逐句判断：\n" + "\n\n".join(parts) +
            "\n\n只输出 JSON：{\"labels\":[{\"n\":1,\"intent\":\"候选代码或NONE\",\"reason\":\"理由\"}]}，每句都要有。")


def rel_system():
    return ("你判断两个意图的包含关系。针对给定的一句用户原话，两个意图 A、B：只有当其中一个的功能范围被另一个完整包含"
            "（例如「查全部批次」与「查某个物料的批次」，或「列表」与「带筛选条件的列表」），"
            "且二者做的是同一类事（都是查询或都是写入），并且被包含的那个（更具体的）按说明能完整满足这句话，才返回更具体的那个的代码；"
            "两者只是相近、相邻、并列、各管一面、数据来源不同、或读写不同，一律返回 NONE。拿不准就返回 NONE，不要为了给出答案而勉强认定。")


def rel_user(items):
    parts = []
    for i, (r, a, b, fc) in enumerate(items, 1):
        parts.append("%d｜%s｜%s\n  A：%s\n  B：%s" % (i, _role(r), r["input"], cand_def(fc[a]), cand_def(fc[b])))
    return ("逐句判断：\n" + "\n\n".join(parts) +
            "\n\n只输出 JSON：{\"labels\":[{\"n\":1,\"specific\":\"A或B的代码或NONE\",\"reason\":\"理由\"}]}，每句都要有。")


def review_user(items):
    lines = ["%d｜%s｜%s｜意图已定为 %s（%s）" % (i, _role(r), r["input"], c["code"], "读" if c["mode"] == "READ" else "写")
             for i, (r, c) in enumerate(items, 1)]
    return ("逐句判断姿态（序号｜角色｜原话｜已确定的意图）：\n" + "\n".join(lines) +
            "\n\n只输出 JSON：{\"labels\":[{\"n\":1,\"stance\":\"DIRECT|ASK|WRITE|REFUSE|OOD\",\"ask_reason\":\"SAME_NAME|MISSING_REQUIRED|DIVERGENT_INTENTS 或空\","
            "\"reason\":\"理由\"}]}，每句都要有。")


def run_calls(client, jobs, sysp, user_fn, out_path, batch=8, workers=4, extra=None):
    """jobs = [(id, payload)], user_fn(list of payload) -> 提示词; 结果逐条追加到 out_path (键 id), 断点续跑。返回 {id: record}。"""
    done = {r["id"] for r in A.read_jsonl(out_path)}
    todo = [x for x in jobs if x[0] not in done]
    lock = threading.Lock()
    batches = [todo[i:i + batch] for i in range(0, len(todo), batch)]

    def one(b):
        text, _ = client.chat(sysp, user_fn([p for _, p in b]), max_tokens=4000, temperature=0, extra=extra)
        t = text.strip()
        data = json.loads(t[t.find("{"): t.rfind("}") + 1])
        got = {int(x["n"]): x for x in data.get("labels", []) if isinstance(x, dict) and "n" in x}
        with lock, open(out_path, "a", encoding="utf-8", newline="") as f:
            for i, (rid, payload) in enumerate(b, 1):
                x = got.get(i)
                if x:
                    rec = {k: v for k, v in x.items() if k != "n"}
                    if "reason" in rec:  # 理由里原话整句出现就截掉 (裁定依据里不重复题面)
                        rec["reason"] = ("…" if payload[0]["input"] in str(rec["reason"]) else str(rec["reason"]).strip()[:80])
                    f.write(json.dumps(dict(id=rid, model=client.model, **rec), ensure_ascii=False) + "\n")

    fails = 0
    with ThreadPoolExecutor(workers) as ex:
        for f in [ex.submit(one, b) for b in batches]:
            try:
                f.result()
            except Exception as e:  # noqa: BLE001 记录并计数, 不静默跳过
                fails += 1
                print("batch failed:", repr(e)[:160], file=sys.stderr)
    res = {r["id"]: r for r in A.read_jsonl(out_path)}
    print("calls %s: %d/%d jobs have a result, %d batch failures, spent %.3f USD, calls %d, tokens in/out %d/%d" %
          (client.model, sum(1 for i, _ in jobs if i in res), len(jobs), fails, client.spent, client.calls, client.in_tok, client.out_tok))
    return res


def load_j(work, ids):
    """从第二轮缓存拼出每题的 j。"""
    def rd(name):
        return {r["id"]: r for r in A.read_jsonl(Path(work) / name)}
    srcs = (("ja", rd("r2-judge-a.jsonl")), ("jb", rd("r2-judge-b.jsonl")), ("rel", rd("r2-rel.jsonl")), ("rel2", rd("r2-rel2.jsonl")), ("review", rd("r2-review.jsonl")))
    out = {}
    for i in ids:
        out[i] = {k: {x: y for x, y in src[i].items() if x != "id"} for k, src in srcs if i in src}
    return out


def _primary_intent(r, j, by_code):
    """与 decide2 同口径取出已确定的意图 (姿态复核提示词要用)。"""
    p = _vote_intent(r, by_code)
    if p:
        return p
    a, b = j["ja"]["intent"], j["jb"]["intent"]
    return a if a == b else (j.get("rel") or {}).get("specific")


def cmd_deepen(a):
    import llm_client
    work = Path(a.work)
    work.mkdir(parents=True, exist_ok=True)
    catalog = A.load_catalog()
    by_code = {c["code"]: c for c in catalog}
    fc = full_catalog()
    pend = [r for r in (restore_pending(x) for x in A.load_rows()) if r is not None]
    ids = [r["id"] for r in pend]
    print("pending rows entering round 2: %d" % len(pend))
    ca = llm_client.LLM("aliyun", J_A_MODEL, a.cap_a)
    cb = llm_client.LLM("aliyun", J_B_MODEL, a.cap_b)
    cr = llm_client.LLM("aliyun", J_REL_MODEL, a.cap_rel)
    cr2 = llm_client.LLM("aliyun", J_B_MODEL, a.cap_rel)
    cv = llm_client.LLM("aliyun", J_REV_MODEL, a.cap_rev)
    jsys = judge_system(catalog)
    for stage in ("judge", "rel", "review"):
        j = load_j(work, ids)
        need = [r for r in pend if decide2(r, j[r["id"]], by_code)["need"] == stage]
        print("stage %s: %d rows" % (stage, len(need)))
        if not need:
            continue
        if stage == "judge":
            jobs = []
            for r in need:
                v = r["adjudication"]["votes"]
                cs = []
                for c in [r["intent"]] + r["intent_alternates"] + [v.get("v1_intent") or "", v.get("v2_intent") or ""]:
                    if c in fc and c not in cs:
                        cs.append(c)
                jobs.append((r["id"], (r, cs, fc)))
            run_calls(ca, jobs, jsys, judge_user, work / "r2-judge-a.jsonl")
            run_calls(cb, jobs, jsys, judge_user, work / "r2-judge-b.jsonl", extra=A.NO_THINK)
        elif stage == "rel":
            jobs = [(r["id"], (r, j[r["id"]]["ja"]["intent"], j[r["id"]]["jb"]["intent"], fc)) for r in need]
            run_calls(cr, jobs, rel_system(), rel_user, work / "r2-rel.jsonl")
            run_calls(cr2, jobs, rel_system(), rel_user, work / "r2-rel2.jsonl", extra=A.NO_THINK)
        else:
            jobs = [(r["id"], (r, fc[_primary_intent(r, j[r["id"]], by_code)])) for r in need]  # decide2 到这一步意图已确定
            run_calls(cv, jobs, A.system_prompt(catalog), review_user, work / "r2-review.jsonl")
    print("total spent USD %.3f" % (ca.spent + cb.spent + cr.spent + cr2.spent + cv.spent))
    return 0


def cmd_apply2(a):
    work = Path(a.work)
    by_code = {c["code"]: c for c in A.load_catalog()}
    cur = A.load_rows()
    j = load_j(work, [r["id"] for r in cur]) if (work / "r2-judge-a.jsonl").exists() else {}
    new = [apply_row2(r, j.get(r["id"]) or None, by_code) for r in cur]
    summ = A.summarize(new, [A.base_row(r) for r in cur])
    r2 = [r for r in new if r.get("adjudication", {}).get("round2")]
    done2 = [r for r in r2 if r["adjudication"]["status"] == "resolved"]
    left = [r for r in r2 if r["adjudication"]["status"] != "resolved"]
    summ["round2_rows"] = len(r2)
    summ["round2_resolved"] = len(done2)
    summ["round2_resolved_by_stance"] = dict(collections.Counter(r["stance"] for r in done2))
    summ["round2_resolved_transitions"] = dict(collections.Counter("%s->%s" % (r["orig_stance"], r["stance"]) for r in done2))
    summ["round2_rule_usage"] = dict(collections.Counter(x for r in done2 for x in r["adjudication"]["round2"]["rule_id"].split("+")))
    summ["round2_pending_by_cause"] = dict(collections.Counter(r["adjudication"]["round2"]["rule_id"].split("+")[-1] or "NEED" for r in left))
    summ["round2_pending_missing_votes"] = sum(1 for r in left if r["adjudication"]["round2"]["need"])
    summ["intent_alternates_not_list_after"] = sum(1 for r in new if not isinstance(r["intent_alternates"], list))
    summ["ask_options_not_list_after"] = sum(1 for r in new if not isinstance(r["ask_options"], list))
    if not a.dry_run:
        A.DEV.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in new), "utf-8", newline="")
    if a.csv:
        Path(a.csv).parent.mkdir(parents=True, exist_ok=True)
        with open(a.csv, "w", encoding="utf-8-sig", newline="") as f:
            w = csv.writer(f)
            w.writerow(["id", "stage", "role", "style", "input", "orig_stance", "orig_intent", "orig_alternates", "label_stance", "v1_stance",
                        "v1_intent", "v2_intent", "judge_a", "judge_b", "relation", "rule_id", "为什么定不下", "your_stance", "your_intent"])
            for r in left:
                ad = r["adjudication"]
                v, jj = ad["votes"], ad["round2"]["j"]
                w.writerow([r["id"], r["stage"], r["role"], r["style"], r["input"], r["orig_stance"], r["orig_intent"],
                            ";".join(as_list(r["orig_intent_alternates"])), v["label"], v["v1"], v["v1_intent"], v["v2_intent"],
                            (jj.get("ja") or {}).get("intent", ""), (jj.get("jb") or {}).get("intent", ""),
                            (jj.get("rel") or {}).get("specific", ""), ad["rule_id"], ad["pending_cause"], "", ""])
    print(json.dumps(summ, ensure_ascii=False, indent=1))
    if a.summary_out:
        Path(a.summary_out).write_text(json.dumps(summ, ensure_ascii=False, indent=1), "utf-8", newline="")
    return 0


def main():
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd", required=True)
    p = sp.add_parser("deepen")
    p.add_argument("--work", required=True)
    for k, v in (("cap-a", 1.2), ("cap-b", 1.0), ("cap-rel", 0.3), ("cap-rev", 0.5)):
        p.add_argument("--" + k, type=float, default=v, help="花费上限 USD")
    p = sp.add_parser("apply2")
    p.add_argument("--work", required=True)
    p.add_argument("--csv", default="")
    p.add_argument("--summary-out", default="")
    p.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    return dict(deepen=cmd_deepen, apply2=cmd_apply2)[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
