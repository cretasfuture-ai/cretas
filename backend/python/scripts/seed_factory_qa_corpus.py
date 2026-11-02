"""Factory Q&A seed corpus (SEED_F) from generic question seeds.

Steve 2026-10-29: 能通用的问法是训练重点, 不能被保密情况阻挡. 28 种从旧餐饮语料里挑出、
可迁移到工厂的通用问法 (改写成工厂用语后的 79 句种子, 22 个工厂意图) 当种子, 在**合成的**
工厂数据上生成问答, 写进 ``smart_bi_distillation_samples`` (factory_id=SEED_F)。

不碰任何真实租户数据: 产品/原料/供应商/岗位全是通用占位名, 数字由固定随机种子现算。

三步 (每步独立, 清单文件是评审与写库之间的唯一凭据):

    # 1. 生成 + 过质量闸 -> 清单 (jsonl); 同时可出「每种问法抽 2 条」的评审清单 (md)
    python -m scripts.seed_factory_qa_corpus generate --out /tmp/f_qa.jsonl --review /tmp/f_qa_review.md
    # 2. 评审通过后写库 (写入前后各计一次数, 行数必须等于清单条数)
    python -m scripts.seed_factory_qa_corpus write --manifest /tmp/f_qa.jsonl --confirm YES-WRITE
    # 3. 撤回整批
    python -m scripts.seed_factory_qa_corpus revoke --confirm YES-REVOKE

整批撤回标记: source = ``SOURCE`` (VARCHAR(32)), metadata.batch_id = ``BATCH_ID``。

质量闸 (任一不过整条丢弃, 并计入原因计数):
  * 回答里的每个数字必须能追溯到输入里给的合成数据 (原值或由原值现算的派生值)
  * 不得出现餐饮词 (菜品 / 门店 / 客单价 ...)
  * 回答里的产品 / 原料 / 供应商 / 岗位名必须是合成数据里出现过的通用名
  * 过现行训练规则 (``qa_has_blocked_content``: 工艺参数 / 结构 / 问题处理 / 人员薪酬 /
    具体客户 / 人名)
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import logging
import os
import random
import re
import sys
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Optional, Tuple

_PYTHON_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logger = logging.getLogger("seed_factory_qa_corpus")

SENTINEL_FACTORY = "SEED_F"
SOURCE = "seed_f_qa_20261103"            # 整批撤回: DELETE ... WHERE source = SOURCE
BATCH_ID = "SEED_F_QA_20261103"
SEED_ORIGIN = "generic_questions_from_removed_restaurant_corpus_20261029"
TABLE = "smart_bi_distillation_samples"
RANDOM_SEED = 20261103

# ---------------------------------------------------------------------------
# 1. 意图映射表: 种子问法 -> 工厂意图 -> 需要的数据口径
#    kind: data=按口径喂合成数据 / none=不需要数据 (闲聊拒答、能力说明、明确不支持)
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class IntentSpec:
    code: str
    name: str
    scope: str                 # 数据口径 (builder 名)
    scope_note: str            # 人读的口径说明
    seeds: Tuple[str, ...]
    kind: str = "data"


INTENTS: Tuple[IntentSpec, ...] = (
    IntentSpec("PRICE_HISTORY_QUERY", "价格历史查询", "supplier_price",
               "各供应商本月与上月的原料单价 (元/公斤)",
               ("哪个供应商报价最贵", "最近一个月供应商比价怎么样")),
    IntentSpec("CUSTOMER_REVENUE_TREND", "客户收入趋势", "monthly_sales",
               "12 个月的销售额 / 订单数",
               ("今年每个月的销售额是多少", "分析销售额的月度变化趋势", "历史以来的销售额趋势怎么样",
                "去年到现在每个月的销售额趋势", "销售额趋势怎么样")),
    IntentSpec("REPORT_FINANCE", "财务报表", "monthly_sales",
               "本月 / 上月销售额、订单数、各产品销量",
               ("上个月销售额多少", "去年8月销售额多少", "最近一个月各产品销量排名",
                "最近一个月销售额是多少", "看销售额", "这个月1号到15号的销售额是多少", "销售额", "销售额怎么样")),
    IntentSpec("REPORT_INVENTORY", "库存报表", "inventory",
               "各原料当前库存 / 安全库存 (公斤)",
               ("库存有什么要注意的", "最近一个月库存怎么样")),
    IntentSpec("CONTEXT_CONTINUE", "上下文继续", "monthly_sales",
               "带上一轮问题与口径, 本轮省略主语的追问",
               ("上个月呢", "它的成本呢", "损耗呢", "换成上个月呢", "最近一周呢", "那成本呢", "那最差的呢", "那毛利呢")),
    IntentSpec("OUT_OF_DOMAIN", "域外无关输入", "none", "无数据; 礼貌说明只答工厂经营问题",
               ("今天天气怎么样",), kind="none"),
    IntentSpec("SKU_GROSS_MARGIN", "SKU毛利率查询", "product_margin",
               "各产品销售额 / 成本 / 毛利率, 本月与上月",
               ("为什么这个月毛利率下降了", "加权毛利率是多少", "最近一个月整体毛利率是多少", "本月毛利率多少",
                "毛利", "毛利为什么下滑", "毛利怎么样", "毛利率怎么样", "这个月1号到10号的毛利率是多少", "这个月毛利多少")),
    IntentSpec("SCHEDULING_LIST", "排班计划查询", "scheduling",
               "明日各班次 需求人数 / 已排人数 (只到人数, 不含个人)",
               ("员工排班合理吗", "员工排班情况", "明天怎么排班")),
    IntentSpec("SYSTEM_HELP", "使用帮助", "none", "无数据; 介绍系统能查什么",
               ("你们能做什么",), kind="none"),
    IntentSpec("REPORT_TRENDS", "趋势报表", "monthly_sales",
               "12 个月销售额 / 毛利, 本月与上月, 按周拆分",
               ("上上个月的销售额和现在比怎么样", "上周销售额为什么跌了", "从上个月到现在每天的销售额趋势",
                "增长趋势", "按日期看销售额趋势", "最近一个月销售额为什么下滑", "本月和上个月销售额对比",
                "毛利这几个月的走势怎么样", "这个月和上个月按周看销售额", "这个月和上个月销售额差多少",
                "这个月销售额为什么变少了", "这个月销售额比上个月低是什么原因", "销售趋势", "销售额为什么下滑",
                "销售额同比增长了多少", "销售额环比增长多少")),
    IntentSpec("REPORT_EFFICIENCY", "效率分析报表", "efficiency",
               "各岗位在岗人数 / 产出 / 人均产出 (只到岗位, 不含个人)",
               ("上个月人效怎么样", "员工人效怎么样", "我们工厂的生产线效率如何")),
    IntentSpec("INCOME_STATEMENT_QUERY", "利润表", "monthly_sales",
               "本月 / 上月销售额、成本、利润",
               ("为什么最近利润下降", "扣掉成本还剩多少钱", "最近一个月销售额多少，净利润又是多少")),
    IntentSpec("EFFICIENCY_COMPARISON", "效率对比分析", "efficiency",
               "各岗位人均产出对比", ("各岗位这个月的人效怎么样",)),
    IntentSpec("REPORT_ANOMALY", "异常报表", "anomaly",
               "几项指标本月 vs 近 3 月均值的偏离", ("最近有什么不对劲的地方",)),
    IntentSpec("ORDER_LIST", "订单列表", "monthly_sales", "本月 / 上月订单数", ("本月订单数多少",)),
    IntentSpec("REPORT_DASHBOARD_OVERVIEW", "仪表盘总览", "monthly_sales",
               "本月销售额 + 订单数 + 毛利", ("销售额和订单情况",)),
    IntentSpec("MATERIAL_LOW_STOCK_ALERT", "低库存预警", "inventory",
               "各原料库存对安全库存", ("哪些原料库存不足", "库存够不够")),
    IntentSpec("COST_QUERY", "成本查询", "cost_components",
               "本月成本构成: 原料 / 人工(合计) / 厂房租金 / 水电 / 领料 (人工只到合计)",
               ("最近一个月厂房租金占销售额多少", "本月人力成本是多少", "本月厂房租金是多少", "领料花了多少钱")),
    IntentSpec("UNSUPPORTED_CAPABILITY", "能力不支持-明确拒答", "none",
               "无数据; 明确说明现在做不了并给出能做的替代查询",
               ("帮我做一条最近一个月销量的线性回归曲线", "按天气分组看一下最近一周的销售额", "涨价对销量的价格弹性有多大"),
               kind="none"),
    IntentSpec("BATCH_CONSUMPTION_QUERY", "生产耗料查询", "consumption",
               "近 4 周每周领料量 (公斤)", ("最近领料情况怎么样", "领料数量趋势")),
    IntentSpec("SALES_FORECAST_7DAY", "7 天销售预测", "forecast",
               "近 4 周每周销售额 + 简单均值预测区间", ("下周销售额能有多少",)),
    IntentSpec("SMART_INDICATOR_QUERY", "智能指标分析", "monthly_sales",
               "订单数 + 平均每单金额, 本月与上月", ("订单数和平均每单金额怎么样",)),
)
INTENT_BY_CODE: Dict[str, IntentSpec] = {i.code: i for i in INTENTS}

# 口语 / 省略 / 追问变体 (确定性套模板)。{q} 是种子句。
_PREFIX = ("帮我看下", "麻烦查一下", "老板问", "想知道", "你帮我查查", "请问")
_SUFFIX = ("啊", "呀", "，给我说说", "，简单点说", "，快点")
_COLLOQUIAL = (
    lambda q: q.replace("是多少", "多少").replace("怎么样", "咋样"),
    lambda q: q.replace("最近一个月", "这阵子").replace("这个月", "这月"),
    lambda q: q.replace("为什么", "怎么会").replace("多少", "有多少"),
)


def question_variants(seed: str, rng: random.Random, n: int) -> List[str]:
    """Deterministic variants of one seed sentence; the seed itself is always first."""
    out: List[str] = [seed]
    makers: List[Callable[[], str]] = [
        lambda: rng.choice(_PREFIX) + seed,
        lambda: seed.rstrip("？?") + rng.choice(_SUFFIX),
        lambda: rng.choice(_COLLOQUIAL)(seed),
        lambda: ("那" + seed) if not seed.startswith("那") else seed,
        lambda: seed + "？",
        lambda: seed.replace("销售额", "卖了多少钱") if "销售额" in seed else seed + "呢",
    ]
    tries = 0
    while len(out) < n and tries < 40:
        tries += 1
        v = makers[rng.randrange(len(makers))]()
        if v and v not in out:
            out.append(v)
    return out


# ---------------------------------------------------------------------------
# 2. 合成工厂数据 (通用占位名, 固定种子)
# ---------------------------------------------------------------------------
PRODUCTS = ("产品A", "产品B", "产品C", "产品D")
MATERIALS = ("原料甲", "原料乙", "原料丙", "原料丁")
SUPPLIERS = ("供应商甲", "供应商乙", "供应商丙")
POSITIONS = ("岗位一", "岗位二", "岗位三")
SHIFTS = ("早班", "中班", "晚班")
COST_ITEMS = ("原料", "人工合计", "厂房租金", "水电", "领料")


@dataclass
class Snapshot:
    """One synthetic factory snapshot: the text the model sees + traceable numbers."""
    text: str
    numbers: set = field(default_factory=set)
    names: set = field(default_factory=set)

    def add(self, *vals: float) -> None:
        for v in vals:
            self.numbers.add(_norm(v))


def _norm(v: float) -> float:
    return round(float(v), 2)


def _derived(snap: Snapshot, a: float, b: float) -> None:
    """Numbers an answer may legitimately compute from a pair (b -> a)."""
    snap.add(a - b, abs(a - b), a + b)
    if b:
        for d in (1, 2):
            snap.numbers.add(round((a - b) / b * 100, d))
            snap.numbers.add(round(abs(a - b) / b * 100, d))
        snap.numbers.add(round(a / b * 100, 1))
        snap.numbers.add(round(a / b, 2))


def _build_monthly(rng: random.Random) -> Snapshot:
    base = rng.randint(30, 60) * 10000
    months, sales, orders = [], [], []
    v = base
    for m in range(1, 13):
        v = int(v * rng.uniform(0.93, 1.09))
        months.append(m)
        sales.append(v)
        orders.append(int(v / rng.randint(900, 1500)))
    margin = [round(rng.uniform(18, 32), 1) for _ in months]
    lines = ["【合成工厂月度数据: 月份 / 销售额(元) / 订单数 / 毛利率(%)】"]
    s = Snapshot("")
    for m, sv, o, mg in zip(months, sales, orders, margin):
        lines.append(f"{m}月: {sv} / {o} / {mg}")
        s.add(m, sv, o, mg)
    cur, prev = sales[-1], sales[-2]
    s.add(sum(sales), sum(sales) / 12, sum(orders))
    _derived(s, cur, prev)
    _derived(s, orders[-1], orders[-2])
    for i in range(1, 12):
        _derived(s, sales[i], sales[i - 1])
        s.add(round(sales[i] * margin[i] / 100))
    s.add(round(cur / orders[-1]), round(prev / orders[-2]))
    _derived(s, round(cur / orders[-1]), round(prev / orders[-2]))
    _derived(s, margin[-1], margin[-2])
    gp_cur = round(cur * margin[-1] / 100)
    gp_prev = round(prev * margin[-2] / 100)
    s.add(gp_cur, gp_prev, cur - gp_cur, prev - gp_prev)
    _derived(s, gp_cur, gp_prev)
    # 按周拆分 (本月 4 周)
    wk = _split(cur, 4, rng)
    wkp = _split(prev, 4, rng)
    lines.append("本月按周销售额(元): " + " / ".join(map(str, wk)))
    lines.append("上月按周销售额(元): " + " / ".join(map(str, wkp)))
    for i in range(4):
        s.add(i + 1, wk[i], wkp[i])
        _derived(s, wk[i], wkp[i])
    for i in range(1, 4):
        _derived(s, wk[i], wk[i - 1])
    lines.append("本月各产品销量(件): " + " / ".join(
        f"{p} {q}" for p, q in zip(PRODUCTS, _qty(rng, s))))
    s.names.update(PRODUCTS)
    s.text = "\n".join(lines)
    return s


def _qty(rng: random.Random, s: Snapshot) -> List[int]:
    q = sorted((rng.randint(800, 6000) for _ in PRODUCTS), reverse=True)
    s.add(*q)
    s.add(sum(q))
    return q


def _split(total: int, parts: int, rng: random.Random) -> List[int]:
    w = [rng.uniform(0.8, 1.2) for _ in range(parts)]
    out = [int(total * x / sum(w)) for x in w]
    out[-1] += total - sum(out)
    return out


def _build_product_margin(rng: random.Random) -> Snapshot:
    s = Snapshot("")
    lines = ["【合成工厂产品毛利: 产品 / 本月销售额(元) / 本月成本(元) / 本月毛利率(%) / 上月毛利率(%)】"]
    tot_s = tot_c = 0
    for p in PRODUCTS:
        sv = rng.randint(8, 30) * 10000
        mg = round(rng.uniform(15, 38), 1)
        mgp = round(mg + rng.uniform(-6, 6), 1)
        c = round(sv * (1 - mg / 100))
        lines.append(f"{p} / {sv} / {c} / {mg} / {mgp}")
        s.add(sv, c, mg, mgp, sv - c)
        _derived(s, mg, mgp)
        tot_s += sv
        tot_c += c
    s.add(tot_s, tot_c, tot_s - tot_c, round((tot_s - tot_c) / tot_s * 100, 1))
    lines.append(f"合计: 销售额 {tot_s} / 成本 {tot_c}")
    s.names.update(PRODUCTS)
    s.text = "\n".join(lines)
    return s


def _build_inventory(rng: random.Random) -> Snapshot:
    s = Snapshot("")
    lines = ["【合成工厂原料库存(公斤): 原料 / 当前库存 / 安全库存】"]
    for m in MATERIALS:
        safe = rng.randint(2, 8) * 100
        cur = int(safe * rng.uniform(0.4, 1.8))
        lines.append(f"{m} / {cur} / {safe}")
        s.add(cur, safe, safe - cur, abs(cur - safe))
        _derived(s, cur, safe)
    s.names.update(MATERIALS)
    s.text = "\n".join(lines)
    return s


def _build_supplier_price(rng: random.Random) -> Snapshot:
    s = Snapshot("")
    lines = ["【合成工厂原料单价(元/公斤): 原料 / 供应商 / 本月 / 上月】"]
    m = MATERIALS[0]
    for sup in SUPPLIERS:
        prev = round(rng.uniform(8, 20), 1)
        cur = round(prev * rng.uniform(0.92, 1.12), 1)
        lines.append(f"{m} / {sup} / {cur} / {prev}")
        s.add(cur, prev)
        _derived(s, cur, prev)
    s.names.update(SUPPLIERS)
    s.names.add(m)
    s.text = "\n".join(lines)
    return s


def _build_scheduling(rng: random.Random) -> Snapshot:
    s = Snapshot("")
    lines = ["【合成工厂明日排班(人数): 班次 / 需求人数 / 已排人数】"]
    for sh in SHIFTS:
        need = rng.randint(8, 20)
        have = max(1, need + rng.randint(-5, 4))
        lines.append(f"{sh} / {need} / {have}")
        s.add(need, have, need - have, abs(need - have))
    s.names.update(SHIFTS)
    s.text = "\n".join(lines)
    return s


def _build_efficiency(rng: random.Random) -> Snapshot:
    s = Snapshot("")
    lines = ["【合成工厂岗位人效: 岗位 / 在岗人数 / 本月产出(件) / 人均产出(件)】"]
    for p in POSITIONS:
        n = rng.randint(5, 18)
        out = rng.randint(2000, 9000)
        per = round(out / n, 1)
        lines.append(f"{p} / {n} / {out} / {per}")
        s.add(n, out, per)
    s.names.update(POSITIONS)
    s.text = "\n".join(lines)
    return s


def _build_anomaly(rng: random.Random) -> Snapshot:
    s = Snapshot("")
    lines = ["【合成工厂本月指标 vs 近3月均值】"]
    for name, base, unit in (("损耗率", 3.2, "%"), ("返品率", 1.1, "%"), ("单位耗电", 42.0, "度")):
        cur = round(base * rng.uniform(0.8, 1.6), 1)
        lines.append(f"{name}: 本月 {cur}{unit} / 近3月均值 {base}{unit}")
        s.add(cur, base)
        _derived(s, cur, base)
    s.text = "\n".join(lines)
    return s


def _build_cost(rng: random.Random) -> Snapshot:
    s = Snapshot("")
    sales = rng.randint(30, 60) * 10000
    lines = [f"【合成工厂本月成本构成(元); 本月销售额 {sales}】"]
    s.add(sales)
    tot = 0
    for it in COST_ITEMS:
        v = int(sales * rng.uniform(0.02, 0.35))
        tot += v
        lines.append(f"{it}: {v}")
        s.add(v, round(v / sales * 100, 1), round(v / sales * 100, 2))
    s.add(tot, round(tot / sales * 100, 1))
    s.text = "\n".join(lines)
    return s


def _build_consumption(rng: random.Random) -> Snapshot:
    s = Snapshot("")
    v = rng.randint(800, 2000)
    vals = []
    for _ in range(4):
        v = int(v * rng.uniform(0.85, 1.2))
        vals.append(v)
    lines = ["【合成工厂近4周领料量(公斤): 第1周 ... 第4周】", " / ".join(map(str, vals))]
    s.add(*vals, sum(vals), sum(vals) / 4, 1, 2, 3, 4)
    for i in range(1, 4):
        _derived(s, vals[i], vals[i - 1])
    _derived(s, vals[3], vals[0])
    s.text = "\n".join(lines)
    return s


def _build_forecast(rng: random.Random) -> Snapshot:
    s = Snapshot("")
    v = rng.randint(8, 15) * 10000
    vals = []
    for _ in range(4):
        v = int(v * rng.uniform(0.92, 1.1))
        vals.append(v)
    avg = round(sum(vals) / 4)
    lo, hi = min(vals), max(vals)
    lines = ["【合成工厂近4周每周销售额(元)】", " / ".join(map(str, vals)),
             f"近4周均值 {avg}, 最低 {lo}, 最高 {hi}"]
    s.add(*vals, avg, lo, hi, 1, 2, 3, 4, 7)
    s.text = "\n".join(lines)
    return s


_BUILDERS: Dict[str, Callable[[random.Random], Snapshot]] = {
    "monthly_sales": _build_monthly, "product_margin": _build_product_margin,
    "inventory": _build_inventory, "supplier_price": _build_supplier_price,
    "scheduling": _build_scheduling, "efficiency": _build_efficiency,
    "anomaly": _build_anomaly, "cost_components": _build_cost,
    "consumption": _build_consumption, "forecast": _build_forecast,
}


# ---------------------------------------------------------------------------
# 3. 样本构造 (输入 = 问题 + 合成数据; 追问带上一轮)
# ---------------------------------------------------------------------------
SYSTEM_PROMPT = (
    "你是工厂经营助手, 回答生产型工厂老板和管理人员的经营问题。\n"
    "规则: 1) 只能使用【数据】里给出的数字, 或由这些数字直接算出的比例/差值, 不得编造任何数字; "
    "2) 用大白话, 短句, 先给结论再给依据; 3) 用观察动词(关注/排查/了解), 不下因果断言, 问『为什么』时只列数据上能看到的变化; "
    "4) 数据里没有的内容如实说明『现有数据看不出』; 5) 不涉及生产工艺步骤、配方参数、个人薪酬; "
    "6) 问题与工厂无关时, 礼貌说明只回答工厂经营问题; 问题要求做不到的分析时, 明确说现在做不了并给出能查的替代查询。"
)

_NONE_NOTES = {
    "OUT_OF_DOMAIN": "无数据。问题与工厂经营无关, 请礼貌说明只回答工厂经营相关问题, 并举 2 个能问的例子。",
    "SYSTEM_HELP": "无数据。请介绍你能帮忙查的内容: 销售额与趋势、毛利、成本、库存与预警、订单、排班与人效、领料、异常提醒、销售预测。",
    "UNSUPPORTED_CAPABILITY": "无数据。该分析目前做不了, 请明确说『现在做不了』, 不要假装给出结果, 并给出 2 个现在能查的替代问法。",
}


@dataclass
class Sample:
    intent_code: str
    seed: str
    question: str
    input_text: str
    snapshot: Snapshot
    followup_of: Optional[str] = None


def build_sample(spec: IntentSpec, seed: str, question: str, rng: random.Random) -> Sample:
    if spec.kind == "none":
        text = f"【说明】{_NONE_NOTES[spec.code]}\n【问题】{question}"
        return Sample(spec.code, seed, question, text, Snapshot(text))
    snap = _BUILDERS[spec.scope](rng)
    parts = [snap.text]
    prev_q = None
    if spec.code == "CONTEXT_CONTINUE":
        prev_q = rng.choice(("本月销售额是多少", "最近一个月毛利率怎么样", "本月销售额和上月比怎么样"))
        parts.insert(0, f"【上一轮问题】{prev_q}")
    parts.append(f"【问题】{question}")
    return Sample(spec.code, seed, question, "\n".join(parts), snap, prev_q)


def build_all_samples(per_seed: int = 3, seed: int = RANDOM_SEED) -> List[Sample]:
    out: List[Sample] = []
    for spec in INTENTS:
        for i, s in enumerate(spec.seeds):
            rng = random.Random(f"{seed}:{spec.code}:{i}")
            for j, q in enumerate(question_variants(s, rng, per_seed)):
                out.append(build_sample(spec, s, q, random.Random(f"{seed}:{spec.code}:{i}:{j}")))
    return out


# ---------------------------------------------------------------------------
# 4. 质量闸
# ---------------------------------------------------------------------------
RESTAURANT_WORDS = (
    "菜品", "门店", "客单价", "堂食", "外卖", "翻台", "桌台", "食客", "点餐", "店长", "美团", "饿了么",
    "菜单", "餐厅", "餐饮", "到店", "后厨", "招牌", "套餐", "就餐", "顾客",
)
_NUM_RE = re.compile(r"(?<![A-Za-z])\d+(?:,\d{3})*(?:\.\d+)?")
_NAME_RE = re.compile(r"(?:产品|原料|供应商|岗位)[A-Za-z0-9甲乙丙丁戊己一二三四五六七八九十]+")


def _tokens(sample: Sample) -> set:
    toks = set()
    for m in _NUM_RE.finditer(sample.input_text):
        toks.add(_norm(float(m.group().replace(",", ""))))
    return toks | sample.snapshot.numbers


def check_answer(sample: Sample, answer: str) -> Optional[str]:
    """None = accepted, else the rejection reason."""
    from smartbi.services.training_consent import qa_has_blocked_content

    if not answer or len(answer.strip()) < 8:
        return "empty"
    for w in RESTAURANT_WORDS:
        if w in answer or w in sample.input_text:
            return "restaurant_word"
    if qa_has_blocked_content(sample.input_text, answer):
        return "training_rule"
    # 名称: 回答里出现的产品/原料/供应商/岗位名必须来自合成数据
    for n in _NAME_RE.findall(answer):
        if n not in sample.snapshot.names and n not in sample.input_text:
            return "unknown_name"
    allowed = _tokens(sample)
    for m in _NUM_RE.finditer(answer):
        v = _norm(float(m.group().replace(",", "")))
        if v in allowed:
            continue
        if any(abs(v - a) <= max(0.051, abs(a) * 0.0051) for a in allowed):
            continue
        return f"untraceable_number:{m.group()}"
    return None


# ---------------------------------------------------------------------------
# 5. Teacher
# ---------------------------------------------------------------------------
Teacher = Callable[[str, str], Awaitable[Optional[str]]]


async def llm_teacher(system: str, user: str) -> Optional[str]:
    """The same router the other corpus seeders use (process-secret harness included)."""
    from common.llm_router import call_chain, SLOT
    from common.llm_metrics import llm_caller_context
    from common.process_secret_harness import with_process_secret_harness

    slot = SLOT[os.getenv("FACTORY_QA_LLM_SLOT", "REVIEW").strip().upper()]
    payload = {
        "messages": [{"role": "system", "content": with_process_secret_harness(system)},
                     {"role": "user", "content": user}],
        "temperature": 0.3, "max_tokens": 700,
    }
    try:
        with llm_caller_context("factory_qa_seed", factory_id=SENTINEL_FACTORY):
            resp = await call_chain(slot, payload)
        return (resp or {}).get("choices", [{}])[0].get("message", {}).get("content")
    except Exception as exc:  # noqa: BLE001 - counted as llm_error by the caller
        logger.warning("llm call failed: %s", exc)
        return None


def teacher_model_name() -> str:
    return os.getenv("FACTORY_QA_LLM_SLOT", "REVIEW").strip().lower()


async def generate(samples: List[Sample], teacher: Teacher,
                   concurrency: int = 4) -> Tuple[List[Dict[str, Any]], Dict[str, int]]:
    sem = asyncio.Semaphore(concurrency)
    rejects: Dict[str, int] = {}
    kept: List[Dict[str, Any]] = []

    async def one(s: Sample) -> None:
        async with sem:
            ans = await teacher(SYSTEM_PROMPT, s.input_text)
        reason = "llm_error" if ans is None else check_answer(s, ans)
        if reason:
            key = reason.split(":")[0]
            rejects[key] = rejects.get(key, 0) + 1
            return
        kept.append({
            "intent_code": s.intent_code, "seed": s.seed, "question": s.question,
            "input_text": s.input_text, "teacher_output": (ans or "").strip(),
        })

    await asyncio.gather(*(one(s) for s in samples))
    kept.sort(key=lambda r: (r["intent_code"], r["seed"], r["question"]))
    seen, uniq = set(), []
    for r in kept:                      # input_hash UNIQUE -> 同输入只留一条
        h = hashlib.sha256(r["input_text"].encode("utf-8")).hexdigest()
        if h not in seen:
            seen.add(h)
            uniq.append(r)
    return uniq, rejects


# ---------------------------------------------------------------------------
# 6. 清单 / 评审清单 / 写库 / 撤回
# ---------------------------------------------------------------------------
def review_markdown(rows: List[Dict[str, Any]], per_intent: int = 2) -> str:
    by: Dict[str, List[Dict[str, Any]]] = {}
    for r in rows:
        by.setdefault(r["intent_code"], []).append(r)
    out = [f"# SEED_F 工厂问答样本清单 (批次 {BATCH_ID}, 共 {len(rows)} 条)\n"]
    for spec in INTENTS:
        got = by.get(spec.code, [])
        out.append(f"## {spec.code} {spec.name}  (口径: {spec.scope_note}; 本意图 {len(got)} 条)")
        for r in got[:per_intent]:
            out.append(f"- 问: {r['question']}\n  输入:\n```\n{r['input_text']}\n```\n  答: {r['teacher_output']}\n")
        if not got:
            out.append("- (无通过质量闸的样本)\n")
    return "\n".join(out)


def mapping_markdown() -> str:
    out = ["# 问法 -> 工厂意图 -> 数据口径\n", "| 意图 | 名称 | 数据口径 | 种子句数 |", "|---|---|---|---|"]
    for i in INTENTS:
        out.append(f"| {i.code} | {i.name} | {i.scope_note} | {len(i.seeds)} |")
    return "\n".join(out)


def _sample_metadata(row: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "seeded": True, "source_kind": "synthetic_seed", "batch_id": BATCH_ID,
        "seed_origin": SEED_ORIGIN, "intent_code": row["intent_code"], "seed_question": row["seed"],
        "gate": "passed", "permission_tier": "finance_visible",
    }


async def _count(pool) -> int:
    async with pool.acquire() as conn:
        return int(await conn.fetchval(f"SELECT COUNT(*) FROM {TABLE} WHERE source = $1", SOURCE))


async def write_manifest(pool, rows: List[Dict[str, Any]]) -> Tuple[int, int]:
    from smartbi.services.distillation_capture import persist_distillation_sample

    before = await _count(pool)
    for r in rows:
        await persist_distillation_sample(
            pool, source=SOURCE, task_type="qa", input_text=r["input_text"],
            teacher_output=r["teacher_output"], business_type="factory",
            factory_id=SENTINEL_FACTORY, system_prompt=SYSTEM_PROMPT,
            teacher_model=teacher_model_name(), quality=5, metadata=_sample_metadata(r),
        )
    after = await _count(pool)
    return before, after


async def _pool():
    import asyncpg
    dsn = os.environ.get("SMARTBI_PROD_DSN")
    if not dsn:
        from smartbi.config import get_settings
        dsn = get_settings().postgres_url
    return await asyncpg.create_pool(dsn, min_size=1, max_size=2)


def load_manifest(path: str) -> List[Dict[str, Any]]:
    with open(path, "r", encoding="utf-8", newline="") as f:
        return [json.loads(x) for x in f if x.strip()]


async def _main(args: argparse.Namespace) -> int:
    if args.cmd == "generate":
        samples = build_all_samples(per_seed=args.per_seed)
        rows, rejects = await generate(samples, llm_teacher)
        with open(args.out, "w", encoding="utf-8", newline="") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        if args.review:
            with open(args.review, "w", encoding="utf-8", newline="") as f:
                f.write(review_markdown(rows))
        print(f"candidates={len(samples)} kept={len(rows)} rejected={rejects}")
        return 0 if rows else 2          # 0 条 = 没量到, 不是成功
    if args.cmd == "write":
        if args.confirm != "YES-WRITE":
            print("refused: need --confirm YES-WRITE")
            return 2
        rows = load_manifest(args.manifest)
        pool = await _pool()
        before, after = await write_manifest(pool, rows)
        print(f"before={before} after={after} manifest={len(rows)}")
        ok = after - before == len(rows)
        print("COUNT_OK" if ok else "COUNT_MISMATCH")
        return 0 if ok else 1
    if args.cmd == "revoke":
        if args.confirm != "YES-REVOKE":
            print("refused: need --confirm YES-REVOKE")
            return 2
        pool = await _pool()
        before = await _count(pool)
        async with pool.acquire() as conn:
            await conn.execute(f"DELETE FROM {TABLE} WHERE source = $1", SOURCE)
        print(f"before={before} after={await _count(pool)}")
        return 0
    return 2


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("generate")
    g.add_argument("--out", required=True)
    g.add_argument("--review")
    g.add_argument("--per-seed", type=int, default=3)
    w = sub.add_parser("write")
    w.add_argument("--manifest", required=True)
    w.add_argument("--confirm", default="")
    r = sub.add_parser("revoke")
    r.add_argument("--confirm", default="")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO)
    sys.exit(asyncio.run(_main(args)))


if __name__ == "__main__":
    main()
