"""按工厂完整工作流出题 (多模型): 每个 业务域 x 角色 x 说法风格 一格, 由不同模型各出一批, 再由「另一家」模型不看答案盲标。

用法 (在本目录, 凭证见 creds.py):
  python -I -X utf8 gen_workflow_set.py gen   --work <dir> --catalog <catalog_rw.json> --gen qwen-max,gpt-5.4-mini --per-cell 10
  python -I -X utf8 gen_workflow_set.py label --work <dir> --catalog <catalog_rw.json> --gen qwen-max,gpt-5.4-mini
按格子断点续跑 (results-gen-*.jsonl / results-label-*.jsonl); 出题原始输出只留在 <dir>, 不进 git。
"""
import argparse
import itertools
import json
import pathlib
import random
import threading
from concurrent.futures import ThreadPoolExecutor

import llm_client
import workflow_spec as W

PROVIDER = {"qwen-max": "aliyun", "qwen-plus": "aliyun", "gpt-5.4-mini": "openai", "gpt-5.4": "openai"}
# 盲标交叉: 千问出的题交给 OpenAI 标, OpenAI 出的题交给千问标 (不同家模型, 互相不看对方答案)
LABELER_FOR = {"qwen-max": "gpt-5.4-mini", "qwen-plus": "gpt-5.4-mini", "gpt-5.4-mini": "qwen-max", "gpt-5.4": "qwen-max"}

WORLD = """虚构工厂「青禾食品」（只用这些名字，别编真实品牌）：
物料：牛腱子（冷冻库A、冷冻库B 各有一批，同名）、鸡胸肉、白砂糖、生抽酱油、大豆油、草莓、生牛乳、乳酸菌粉、包装袋（大号/小号）、纸箱、周转框
产品：草莓酸奶、原味酸奶、卤牛肉、香辣鸡肉丸、黑椒牛肉粒
人员：张三（一车间）与张三（二车间）同名、李娟（仓库）、王海（质检）、赵磊（二车间班长）、陈敏（财务）
客户：东城超市、东城便利（两个都叫「东城」）、鲜丰生鲜、北街食堂、南湖学校食堂；供应商：恒丰牧业、绿源农场、江南包材
单号形如 XS202611050012（销售单）、CG20261103001（采购单）；批次 PB-20261108-03（生产）、MB-20261101-07（原料）；设备：一号杀菌釜、二号灌装机、三号电子秤"""

POLICY = """先问还是直接办的产品口径（Steve 2026-11-14，默认「激进档」：能办就办，少打扰）：
1 只读、而且合理默认不会误导的，直接办并写明默认（没说时间默认最近 7 天；「过期的」只查已过期的；结果多先显示 20 条）。
2 同名的人或物：两个都列出来，不先问。
3 可能是写操作、或目标两可的，才先问：查还是录只差一个字、动词含糊（「弄一下」「处理一下」）、换了对象不知道看库存还是流水、指代不明（「那个」「刚才那批」且有两个候选）。
4 任何写操作（录入、登记、创建、审批、出库、放行、报工……）识别层都必须当写意图，之后必须出确认卡；绝不能当只读直接答。
5 系统做不到的需求：不支持（REFUSE）；与工厂无关的闲聊（OOD）。"""

STANCE_DOC = """stance 取值：
DIRECT：只读、应直接查（intent 必须是目录里「读」的意图）
WRITE：写操作（intent 必须是目录里「写」的意图；信息齐不齐都算 WRITE）
ASK：应先问（目标两可；intent 填最可能的一个，intent_alternates 填另一个合理候选，ask_options 给 2-3 个供用户选的中文短语）
REFUSE：目录里没有能做的意图，intent 填空字符串
OOD：与工厂业务无关的闲聊，intent 填 OUT_OF_DOMAIN"""


def cat_text(items):
    return "\n".join("%s|%s|%s" % (i["code"], "读" if i["mode"] == "READ" else "写", i["name"]) for i in items)


def gen_prompt(stage, role, style, n, stage_items, other_items, ask_heavy=False):
    sys_p = ("你在为一个食品工厂的 AI 助手出独立验收题，并给出标准答案。提问的是工厂里的真人，用手机说话或打字：短、省略、口语、口头禅，"
             "偶有错别字或方言词，绝不念出功能名称，不要书面语。\n\n" + WORLD + "\n\n" + POLICY + "\n\n" + STANCE_DOC)
    d, w, k = max(1, round(n * 0.5)), max(1, round(n * 0.25)), max(1, round(n * 0.15))
    if ask_heavy:  # 补量轮: 多出「应先问」和「不支持/域外」
        d, w, k = max(1, round(n * 0.25)), max(1, round(n * 0.17)), max(1, round(n * 0.42))
    rest = max(0, n - d - w - k)
    user = ("工作流环节：%s\n提问角色：%s\n说法风格：%s\n\n本环节相关的意图目录（代码|读写|名称）：\n%s\n\n其他环节的意图（备选，也可以用）：\n%s\n\n"
            "请出 %d 句用户话，姿态大致分配：DIRECT %d、WRITE %d、ASK %d、剩下 %d 句给 REFUSE 或 OOD。"
            "要求：句子互不相同，覆盖该角色在本环节里不同的真实场景；intent 只能用目录里出现的代码；"
            "说法必须符合上面的风格；不要重复句式。\n"
            "只输出 JSON：{\"items\":[{\"input\":\"用户原话\",\"scenario\":\"一句情境\",\"stance\":\"...\",\"intent\":\"...\","
            "\"intent_alternates\":[],\"ask_options\":[],\"policy_rule\":0}]}"
            % (W.STAGES[stage], W.ROLES[role], W.STYLES[style], cat_text(stage_items), cat_text(other_items), n, d, w, k, rest))
    return sys_p, user


def label_prompt(batch, all_items):
    sys_p = ("你是食品工厂 AI 助手的标注员。下面是若干句用户原话（角色已标明）。请按产品口径独立判断：识别层应当把它归到目录里哪个意图，"
             "以及姿态。你看不到任何参考答案。\n\n" + WORLD + "\n\n" + POLICY + "\n\n" + STANCE_DOC +
             "\n\n意图目录（代码|读写|名称）：\n" + cat_text(all_items))
    lines = ["%d｜%s｜%s" % (i, W.ROLES[b["role"]].split("（")[0], b["input"]) for i, b in enumerate(batch, 1)]
    user = ("逐句标注（序号｜角色｜原话）：\n" + "\n".join(lines) +
            "\n\n只输出 JSON：{\"labels\":[{\"n\":1,\"stance\":\"...\",\"intent\":\"...\",\"intent_alternates\":[]}]}，每句都要有，不能漏。")
    return sys_p, user


def parse_json(text):
    t = text.strip()
    return json.loads(t[t.find("{"): t.rfind("}") + 1])


def read_jsonl(p):
    p = pathlib.Path(p)
    return [json.loads(x) for x in p.read_text("utf-8").splitlines() if x.strip()] if p.exists() else []


def append_jsonl(p, rec, lock):
    with lock, open(p, "a", encoding="utf-8", newline="") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def cmd_gen(a):
    work = pathlib.Path(a.work)
    work.mkdir(parents=True, exist_ok=True)
    catalog = json.load(open(a.catalog, encoding="utf-8"))
    by_stage = W.split_catalog(catalog)
    lock = threading.Lock()
    for spec in a.gen.split(","):
        model, _, tag = spec.partition("@")  # 例 qwen-max@r2: 第二轮补量 (ask 开头 = 多出先问题)
        out = work / ("results-gen-%s.jsonl" % spec)
        done = {r["cell"] for r in read_jsonl(out) if r.get("ok")}
        client = llm_client.LLM(PROVIDER[model], model, a.cap)
        cells = [c for c in itertools.product(W.STAGES, W.ROLES, W.STYLES) if "/".join(c) not in done]
        rnd = random.Random(20261115 + sum(map(ord, tag)))
        picks = {}
        for c in cells:
            others = rnd.sample(by_stage["OTHER"], 6) + rnd.sample([i for s in W.STAGES if s != c[0] for i in by_stage[s]], 12)
            picks["/".join(c)] = others

        def run(cell):
            stage, role, style = cell
            sp, up = gen_prompt(stage, role, style, a.per_cell, by_stage[stage], picks["/".join(cell)], ask_heavy=tag.startswith("ask"))
            try:
                text, fin = client.chat(sp, up, max_tokens=6000, temperature=0.9)
                items = parse_json(text)["items"]
                rec = dict(cell="/".join(cell), ok=True, model=spec, stage=stage, role=role, style=style, finish=fin, items=items)
            except SystemExit:
                raise
            except Exception as e:  # noqa: BLE001 失败记下来, 不吞掉
                rec = dict(cell="/".join(cell), ok=False, model=spec, error=repr(e)[:300])
            append_jsonl(out, rec, lock)
            return rec["ok"]

        with ThreadPoolExecutor(max_workers=a.workers) as ex:
            res = [bool(x) for x in ex.map(run, cells)]
        print("%s: cells run %d ok %d failed %d spent~%.2f USD calls %d" % (
            spec, len(res), sum(res), len(res) - sum(res), client.spent, client.calls))


def cmd_label(a):
    work = pathlib.Path(a.work)
    catalog = json.load(open(a.catalog, encoding="utf-8"))
    lock = threading.Lock()
    for spec in a.gen.split(","):
        model = spec.partition("@")[0]
        labeler = LABELER_FOR[model]
        raw = [r for r in read_jsonl(work / ("results-gen-%s.jsonl" % spec)) if r.get("ok")]
        rows = []
        for r in raw:
            for k, it in enumerate(r["items"]):
                if isinstance(it, dict) and it.get("input"):
                    rows.append(dict(rid="%s|%s|%d" % (spec, r["cell"], k), gen_model=spec, stage=r["stage"], role=r["role"],
                                     style=r["style"],
                                     **{f: it.get(f) for f in ("input", "scenario", "stance", "intent", "intent_alternates",
                                                               "ask_options", "policy_rule")}))
        out = work / ("results-label-%s.jsonl" % spec)
        done = {r["rid"] for r in read_jsonl(out) if r.get("ok")}
        todo = [r for r in rows if r["rid"] not in done]
        batches = [todo[i:i + a.batch] for i in range(0, len(todo), a.batch)]
        client = llm_client.LLM(PROVIDER[labeler], labeler, a.cap)

        def run(batch):
            sp, up = label_prompt(batch, catalog)
            try:
                text, fin = client.chat(sp, up, max_tokens=6000, temperature=0.0)
                labels = {int(x["n"]): x for x in parse_json(text)["labels"]}
                for i, b in enumerate(batch, 1):
                    lab = labels.get(i)
                    append_jsonl(out, dict(rid=b["rid"], ok=bool(lab), labeler=labeler, label=lab), lock)
            except SystemExit:
                raise
            except Exception as e:  # noqa: BLE001
                for b in batch:
                    append_jsonl(out, dict(rid=b["rid"], ok=False, labeler=labeler, error=repr(e)[:300]), lock)

        with ThreadPoolExecutor(max_workers=a.workers) as ex:
            list(ex.map(run, batches))
        (work / ("items-%s.json" % spec)).write_text(json.dumps(rows, ensure_ascii=False), "utf-8")
        print("%s labeled by %s: items %d, batches %d, spent~%.2f USD" % (spec, labeler, len(rows), len(batches), client.spent))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["gen", "label"])
    ap.add_argument("--work", required=True)
    ap.add_argument("--catalog", required=True)
    ap.add_argument("--gen", default="qwen-max,gpt-5.4-mini")
    ap.add_argument("--per-cell", type=int, default=10)
    ap.add_argument("--cap", type=float, default=6.0, help="单模型 USD 上限")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--batch", type=int, default=20)
    a = ap.parse_args()
    {"gen": cmd_gen, "label": cmd_label}[a.cmd](a)
