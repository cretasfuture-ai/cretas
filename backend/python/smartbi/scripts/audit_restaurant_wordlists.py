"""餐饮读路径的手工中文词表普查。

## 为什么有这个脚本

2026-08-17 实测: 老板追问「那最差的呢」, 产品把**「最差」当成了菜名**
(`dish='最差'`), 于是按菜品维度去查, 当场拒答。

成因是 `_verbatim_entity` 只做两件事: ① 防幻觉(必须是原句子串)
② 对一张**手工黑名单**排除。「最差」两个字在原句里、不含疑问词、不在名单 ⇒ 放行。

▎那张黑名单正是本仓当晚刚**退役掉**的疑问词做法 —— 手工词表永远补不全。

⇒ 先普查: 这个形状在读路径里还有多少个。

## ⛔ 这个脚本不猜

第一版自动判「这个词表跟谁比」, 用 ±2 行上下文匹配变量名。**实测漏判三个**
(`_FOLLOWUP_PREFIXES` / `leading_dependent` / `_STORE_MENTION_STOPWORDS`
分别用 `current` / `candidate` 命名消费点), 把它们错报成「未见消费点」。

⇒ 分类改成**显式登记表**(下面的 `REGISTRY`): 它是人的判断, 写下来可被质疑、
可被替换 —— 而不是一个会同时造出假阳性和假阴性的启发式(形态 E)。
脚本只负责两件机械的事: **数出来** 和 **把消费点打出来**。

## 四类, 只有前两类该动

| 类 | 该怎么办 |
|---|---|
| `ENTITY`   判用户说的是哪个实体/范围 | 换成**对租户目录校验**(查得到/查不到), ⛔ 不是补词表 |
| `INTENT`   判用户的意图/动作 | 本来是 planner 的活; 要动得先量出 planner 在同样输入上更准 |

⚠️ **`INTENT` 这批词表【不是】抢在 LLM 前面短路的** —— 这一点实测核过, ⛔ 别照
「关键词命中就 return 一个 intent」那种直觉去改::

    门店成本怎么提高毛利   LLM 规划器被调用 1 次  authority='llm'  intent=BUSINESS_OPTIMIZATION
    这周营收怎么提升       LLM 规划器被调用 1 次  authority='llm'
    哪家店卖得最好(对照)   LLM 规划器被调用 1 次

若真有短路, 计数应为 0。而且 `BUSINESS_OPTIMIZATION` 这个判断**是 LLM 自己做的**。
最大的那张 `_OPTIMIZATION_OBJECTIVE_TOKENS`(33 条)在 `parse_restaurant_query`
的第 7011 行被消费, 而 LLM 规划器在 6885 行 —— 它在**后面**, 且作用是**反过来的**:
命中就 `return None`(= 不要反问)。

⇒ 这批词表的真实岗位是「LLM 之后追加/抑制反问、补 LLM 没给的槽」。
  退役它们要回答的问题不是「该不该让 LLM 判」(已经是 LLM 在判了), 而是
  **「LLM 判完之后, 拿什么来验」** —— 与 `ENTITY` 那批是同一个问题的两半。
| `SAFETY`   写操作/变更意图的 fail-closed 闸 | ⛔ **不许退役** —— 漏判的代价是执行写操作 |
| `LEXICAL`  天然就是词法的(时间词、餐段词) | 「本月」就是「本月」, 退役没意义 |

## 登记一张新词表时, 按这三问判它属于哪类

    ① 这些词的意思是不是固定的?        是 → LEXICAL, 它就是个词典
    ② 这件事有没有数据源能查?          有 → ENTITY, 换成查(菜名查目录 /
                                          门店查门店表 / 维度查 schema)
    ③ 判错的代价是不是一边重得多?      是 → SAFETY, 留着当保守底线, 允许它误报
    三句都不是                          → INTENT, 它在替 LLM 做判断

🔴 关键的划分**不是**「词表 vs LLM」, 是「猜 vs 查」::

    词表   用【词长什么样】猜语义    「最差」看着像名词 ⇒ 当菜名
    LLM    用【语言模型】猜语义      「最差」在这个位置像实体 ⇒ 当菜名
    目录   【查】                    菜单里有没有「最差」这道菜 ⇒ 没有

「最差」那次 **LLM 和词表犯的是同一个错, 因为它俩都在猜** —— 而且是 LLM 先判错的
(`dish` 就是 LLM 填的), 词表只是没拦住。
⇒ 把词表换成 LLM **不解决问题**。该换的对象是「查」, ⛔ 不是换一个更聪明的猜。

⚠️ 还有第四种用法, 是对的但容易被本脚本算成问题: **把清单喂给 LLM 当封闭选项集**
(把这家店的菜单塞进 prompt, 让它只能从里面选)。同样一份词的清单, **位置不同
性质完全不同** —— 事后拦 vs 事前限定。登记时按用法判, ⛔ 不按长相判。

用法::

    python -m smartbi.scripts.audit_restaurant_wordlists          # 普查 + 消费点
    python -m smartbi.scripts.audit_restaurant_wordlists --ratchet  # 只查未登记数
    python -m smartbi.scripts.audit_restaurant_wordlists --vetoes   # 词表**单独**否决 LLM 槽位的点
    python -m smartbi.scripts.audit_restaurant_wordlists --inline   # 内联无名词表直接决定 return 的点

⚠️ 三道判据问的是三件不同的事, ⛔ 不是同一个数的三种算法::

    --ratchet   有多少张**命名**词表还没登记        对**用法**是盲的
    --vetoes    词表在哪些地方**说了算**(否决 LLM)   只看命名+内联的 guard
    --inline    **无名**词表直接决定 return 的有几处  第一道闸看不见的那一片

三道共用同一份 `REGISTRY` / 同一个 `_cjk_seq`, ⛔ 不是三套登记(形态 D)。
"""
from __future__ import annotations

import argparse
import ast
import pathlib
import re
import sys
from collections import defaultdict
from typing import NamedTuple

ROOT = pathlib.Path(__file__).resolve().parents[1] / "gold" / "restaurant"
CJK = re.compile(r"[一-鿿]")

ENTITY, INTENT, SAFETY, LEXICAL = "ENTITY", "INTENT", "SAFETY", "LEXICAL"

#: 显式登记表。⛔ 新增词表要在这里登记, 否则被棘轮拦住。
#: 登记是**留痕**不是豁免 —— 写进 ENTITY 就等于承认它该被目录校验取代。
REGISTRY: dict[str, str] = {
    # ── 判实体/范围: 这一类该换成对租户目录校验 ──────────────────────
    "_DISH_GENERIC_TOKENS": ENTITY,
    "_DISH_TOKENS": ENTITY,
    "_DISH_SCOPE_HINTS": ENTITY,
    "_INGREDIENT_TOKENS": ENTITY,
    "_INGREDIENT_GENERIC_TOKENS": ENTITY,
    "_STORE_TOKENS": ENTITY,
    "_ALL_STORE_SCOPE_TOKENS": ENTITY,
    "_STORE_RANK_SCOPE_TOKENS": ENTITY,
    "_STORE_BREAKDOWN_SCOPE_TOKENS": ENTITY,
    "_STORE_MENTION_STOPWORDS": ENTITY,
    "_STORE_DISH_METRIC_TOKENS": ENTITY,
    "_ALL_STORE_AGG_DISH_MARGIN_METRIC_TOKENS": ENTITY,
    "_INTERROGATIVE_MARKERS": ENTITY,
    "ANAPHORA": ENTITY,
    "NOT_ANAPHORA": ENTITY,
    "EXPLICIT_SCOPE": ENTITY,
    "_KITCHEN_OPS_NOUNS": ENTITY,
    # ── 判意图/动作: planner 的活, 词表在覆盖它 ─────────────────────
    "_OPTIMIZATION_OBJECTIVE_TOKENS": INTENT,
    "_COMPARISON_DIRECTION_TOKENS": INTENT,
    "_ATTRIBUTION_CUES": INTENT,
    "_WRITE_CUES": INTENT,
    "_EXPLICIT_RANKING_NEGATION_TOKENS": INTENT,
    "_SALES_VALUE_TOKENS": INTENT,
    "_WASTAGE_COST_AXIS_TOKENS": INTENT,
    "_NEW_TOPIC_TOKENS": INTENT,
    "_FOLLOWUP_PREFIXES": INTENT,
    "leading_dependent": INTENT,
    # ── 安全闸: ⛔ 不许退役 ───────────────────────────────────────
    "_READ_ONLY_MUTATION_TOKENS": SAFETY,
    "_EXPLICIT_READ_MUTATION_TOKENS": SAFETY,
    "_HISTORICAL_MUTATION_TOKENS": SAFETY,
    # ── 天然词法 ────────────────────────────────────────────────
    # ── 2026-08-19 登记（⛔ 登记是留痕不是豁免）────────────────────────
    #  「拖后腿**的那家店**」是**描述**不是店名。判据是**语法**的:
    #  指代量词（这/那/哪 + 家/个/间 + 店）+「的」结尾的定语。
    #  ⛔ 归 LEXICAL 而不是 ENTITY: 它判的不是「哪些字是实体」,
    #     而是「这串字是名字还是描述」—— 那是词法/语法, 目录校验取代不了它
    #     （目录只知道名单里有什么，不知道老板说的是不是一个名字）。
    #  📏 误伤实测 0/10 真店名（`_store_mention_is_a_name`）。
    #  ⚠️ 误伤面只有 10 个同租户店名 —— 守望 `store-mention-harm-sample`
    #     每天量它有多大, 够 100+ 跨租户时翻面, 那时要拿真样本重验。
    "_STORE_REFERENCE_TAIL_RE": LEXICAL,
    "_looks_like_a_description_not_a_name": LEXICAL,
    "_CALENDAR_PERIOD_TOKENS": LEXICAL,
    "_DAYPART_WORDS": LEXICAL,
    # ── 判产品自己的输出(不是判用户), 与本次课题无关 ──────────────────
    "_PROFIT_VERDICT_TOKENS": LEXICAL,
    "_EXPLICIT_GAP_TOKENS": LEXICAL,
    "_MARGIN_TOKENS": LEXICAL,
    "_ANALYSIS_CANNOT_TOKENS": LEXICAL,
    "_ANALYSIS_TOPIC_TOKENS": LEXICAL,
    "_APPROVED_TIME_ANSWERS": LEXICAL,
    # 判「无价格权限时正文有没有把三件事说清」(缺什么/怎么拿到/他自己要干什么)。
    # 与上面那几张同一类: **判产品自己产出的正文**, ⛔ 不是判用户输入,
    # 所以没有「换成对租户目录校验」这条出路。
    # ⚠️ 它们是**代理判据**, 真正的收敛是把面向用户的权限说明收到一处产出、
    #    契约改扫那一处 —— 见 docs/decisions/2026-08-18-契约认因权限而缺-设计卡.md
    #    「五、没做完的」第 4 条。登记是留痕, ⛔ 不是豁免。
    # 📌 `_PERMISSION_REASON_TOKENS` 只有 2 条, 低于 census 的 3 条门槛因而
    #    当前点不到名 —— 一并登记, 免得下一个人给它加第 3 个词时突然被拦。
    "_PERMISSION_REASON_TOKENS": LEXICAL,
    "_PERMISSION_REMEDY_TOKENS": LEXICAL,
    "_PERMISSION_ALTERNATIVE_TOKENS": LEXICAL,
    # ── 2026-08-18 扩到正则之后新点到名的, 逐条读过再登记 ────────────────
    #
    # ⛔ 这不是「为了让闸绿」批量豁免 —— 每一条都**读过定义**, 归到
    #    owner 划的那两类边界里(判产品自己输出的 / 单位日期归一)。
    #    剩下 87 个**没有**登记, 它们继续在棘轮里冻着。
    #
    # a) 判产品**自己输出的正文**, 不是判用户输入
    #    📏 `_comparison_present` 读的是 `baseline_label in answer_text`,
    #       `_request_coverage_present` 读的是 `"优先级" in answer_text`
    #       —— 两个的被测对象都是 `answer_text`。
    "_comparison_present": LEXICAL,
    "_request_coverage_present": LEXICAL,
    # 判产品正文里的数字有没有出处（`2026-07-14` 这类要先剔掉再算命中率）
    "_DATETIME_RE": LEXICAL,
    # 拿不到开价时产品自己给的三条建议措辞
    "_GENERIC_ACTIONS": LEXICAL,
    # `phrasing.py` 的措辞池 —— 产品自己说的话的轮转变体, ⛔ 不判用户
    "CHANNEL_MIX_CLOSING": LEXICAL,
    "DISCOUNT_CLOSING": LEXICAL,
    "DISH_MARGIN_CLOSING": LEXICAL,
    "HINT_LEAD_IN": LEXICAL,
    "STORE_SCOPE_DISCLOSURE": LEXICAL,
    "SUPPLIER_PRICE_CLOSING": LEXICAL,
    "TIME_RANGE_DISCLOSURE": LEXICAL,
    #
    # b) 单位 / 日期 / 时段的**词法归一** ——「本月」就是「本月」
    #    ⚠️ 它们确实拿用户问句在匹配, 但与 `_CALENDAR_PERIOD_TOKENS`
    #       `_DAYPART_WORDS`(早已登记 LEXICAL)同类: 这些词的意思是固定的,
    #       没有「换成查目录」这条出路。
    "_ABS_DATE_RANGE_RE": LEXICAL,
    "_ABSOLUTE_TIME_PREFIX_RE": LEXICAL,
    "_HALF_YEAR_QUARTER_RE": LEXICAL,
    "_TODAY_SO_FAR_RE": LEXICAL,
    "_EXPLICIT_TIME_RE": LEXICAL,
    "_SEMANTIC_TIME_OPTIONS": LEXICAL,
    "_SWITCHABLE_WINDOWS": LEXICAL,
    "DAYPART_ORDER": LEXICAL,
    "_relative_period_match": LEXICAL,
    #
    # ⚠️ **没有**登记的两个, 写下来免得下一个人以为是漏了:
    #    `_FALLBACK_OUTPUT_CLAUSE_RE` 名字像判产品输出, 实际匹配的是
    #        **用户问句里**的「…, 如果无法绘图就…」从句 ⇒ 判用户, 留在靶子里
    #    `_DATE_BACKREF_RE` / `_TIME_SLOT_ONLY_PATTERN` 名字像日期词法,
    #        实际是**追问指代**(「那上个月呢」) ⇒ 与 ANAPHORA 同类, 留在靶子里
}

#: 未登记的**命名**载体数量上限。⛔ 只许调小。
#:
#: 2026-08-17 建闸时 **22**（只数中文序列字面量）
#: 2026-08-18 扩到**正则 + 单串比较**后的实测值 **88**:
#:
#:     22  →  115   加正则/单串比较（含 18 个局部变量名当键的坏读数）
#:        →  108   载体名改成「模块级常量名 或 所在函数名」
#:        →   88   登记 owner 划的两类边界（判产品输出 / 日期词法）
#:
#: ⛔ 这个数**不许调得高于实测值** —— 留出的余量就是悄悄新增的空间。
#: `test_the_limit_is_not_slack` 守着它（容差 3）。
#: ⚠️ 88 里绝大多数是**存量**（`_DISH_*_RE` / `_STORE_*_RE` 那两批就占一半），
#:    它们正是这个课题的靶子。棘轮只保证**不再长**, ⛔ 不代表它们都合理。
MAX_UNREGISTERED = 88


#: 「几个词才算一张词表」的默认门槛。⛔ 这是**门槛**不是语义判断。
_SEQ_MIN_TERMS = 3


def _cjk_seq(node, min_terms: int = _SEQ_MIN_TERMS) -> list[str] | None:
    """一个多数元素是中文字符串的序列字面量。

    ⚠️ `min_terms` 只为**内联决策档**存在(它用 2, 见 `INLINE_DECISION_MIN_TERMS`)。
       默认值 3 保持既有四个调用点的行为**逐字不变** —— ⛔ 这里不是两份判据,
       是同一份判据把门槛露出来当参数(形态 D: 别让「什么算词表」长出第二份)。
    """
    if not isinstance(node, (ast.Tuple, ast.Set, ast.List)):
        return None
    vals = [e.value for e in node.elts
            if isinstance(e, ast.Constant) and isinstance(e.value, str)]
    if len(vals) < min_terms or len(vals) < len(node.elts) * 0.8:
        return None
    if sum(1 for v in vals if CJK.search(v)) < len(vals) * 0.8:
        return None
    return vals


# ── 🔴 2026-08-18 扩面：**正则** 与 **单串比较** ────────────────────────────
#
# 缺口是实测出来的（docs/decisions/2026-08-18-词表棘轮盲区-正则-登记.md）:
# 有人写了一个 `asks_about_store_distribution` 正则去判「有几家店」这句话
# 是什么意思, 并据此改系统行为 —— **本闸一个字都没说**, 因为
# `_cjk_seq` 只认序列字面量, 而正则是**一个字符串**。
#
#     ① _FOO = ("有几家店", "门店数量", "几个门店")     看得见 ✅
#     ② _FOO = re.compile(r"有几家店|门店数量|几个门店") 看不见 🔴
#     ③ if any(t in q for t in ("有几家店", "门店数量")) 看得见 ✅
#
# ▎闸建起来了，而它挡不住**触发它的那一类**。
#
# ⛔ 判据仍然只做机械的事: 数出来 + 打出载体名。分类走显式 `REGISTRY`,
#    ⛔ 不自动判「这是不是在判用户输入」—— 第一版分类器就是想自动判
#    「跟谁比」而漏判三个（见本文件头部）。
#    「判产品自己输出的 / SQL·日志解析 / 单位日期归一」通过**登记成 LEXICAL**
#    出列, ⛔ 不通过脚本猜。

#: 正则里的中文片段。⚠️ 量的是**片段数**不是字符数 —— `r"最近(\d+)天"`
#: 只有一个片段, 而 `r"有几家店|门店数量"` 有两个。
_CJK_RUN = re.compile(r"[一-鿿]+")

#: 一条正则要有几个中文片段才算「拿中文在匹配」。
#: ⚠️ 2 而不是 1: 单片段的绝大多数是**词法**（`r"最近(\d+)天"` 的「最近」「天」
#:    被 `(\d+)` 隔开算两段, 而 `r"^第(\d+)页"` 这种真的只有一段）。
#: ⛔ 这是**门槛**不是语义判断 —— 它回答「多小不算词表」, 不回答「它在判什么」。
_REGEX_MIN_CJK_RUNS = 2

#: 会把第一个参数当模式用的 `re` 函数。
_RE_FUNCS = frozenset({
    "compile", "search", "match", "fullmatch", "findall", "finditer",
    "sub", "subn", "split",
})


def _regex_cjk_terms(node) -> list[str] | None:
    """`re.compile(r"…中文…")` / `re.search(r"…中文…", x)` 的中文片段。

    ⚠️ 也认**动态拼接**的模式（`r"(?:%s)…(?:门店|店)" % ...`）——
       那正是本次缺口的实例长相, 它的字面量部分照样含中文。
    """
    if not isinstance(node, ast.Call):
        return None
    fn = node.func
    name = fn.attr if isinstance(fn, ast.Attribute) else (
        fn.id if isinstance(fn, ast.Name) else None)
    if name not in _RE_FUNCS or not node.args:
        return None
    pat = node.args[0]
    # 直接字面量, 或 `字面量 % (...)` / `字面量.format(...)` / f-string 的字面部分
    literals: list[str] = []
    for sub in ast.walk(pat):
        if isinstance(sub, ast.Constant) and isinstance(sub.value, str):
            literals.append(sub.value)
    if not literals:
        return None
    runs = _CJK_RUN.findall("".join(literals))
    return runs if len(runs) >= _REGEX_MIN_CJK_RUNS else None


def _cjk_membership(node) -> list[str] | None:
    """单个中文串被拿去匹配: `"有几家店" in q` / `q.startswith("哪家")`。

    ⚠️ 只认**中文串在左**的 `in`（`"哪家" in query`）——
       `query in _SOME_TOKENS` 是在查表, 那张表自己会被上面的判据数到。
    """
    if isinstance(node, ast.Compare):
        if len(node.ops) != 1 or not isinstance(node.ops[0], (ast.In, ast.NotIn)):
            return None
        left = node.left
        if (isinstance(left, ast.Constant) and isinstance(left.value, str)
                and CJK.search(left.value)):
            return [left.value]
        return None
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
        if node.func.attr not in ("startswith", "endswith") or not node.args:
            return None
        arg = node.args[0]
        vals = []
        if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
            vals = [arg.value]
        elif isinstance(arg, ast.Tuple):
            vals = [e.value for e in arg.elts
                    if isinstance(e, ast.Constant) and isinstance(e.value, str)]
        vals = [v for v in vals if CJK.search(v)]
        return vals or None
    return None


class _CarrierScan(ast.NodeVisitor):
    """给每个「拿中文做匹配」的点找一个**载体名**, 好让它能被登记。

    🔴 载体名 = 具名常量名, 或**所在函数名**。
       ⚠️ 内联的没有常量名, 但它有函数名 —— 而 owner 的判据是
          「凡是拿用户问句去匹配的新增点, 词表/正则/**内联 if** 都要登记」。
          不给它一个名字就登记不了, 于是本次那个缺口的实例
          （`asks_about_store_distribution` 里内联拼的正则）**照样点不到名**。
    """

    def __init__(self, module: str):
        self.module = module
        self.stack: list[str] = []
        self.found: dict[str, tuple[str, int, int]] = {}
        self._assigned: set[int] = set()

    # -- 载体：具名常量（⛔ 只在模块级）----------------------------------
    def _visit_assign(self, tgt, val, lineno):
        if not isinstance(tgt, ast.Name):
            return
        inner = val.args[0] if (isinstance(val, ast.Call)
                                and val.func.__class__ is ast.Name
                                and val.args) else val
        terms = _regex_cjk_terms(val) or _regex_cjk_terms(inner)
        if not terms:
            return
        self._assigned.add(id(val))
        # 🔴 函数**内部**的赋值一律归给函数, ⛔ 不用局部变量名当登记键 ——
        #    `body` / `match` / `raw` / `values` 这种名字在多个文件里都有,
        #    登记一个等于**豁免全部同名的**。第一版实测点出 18 个这种名字。
        #    ▎登记键必须是能被唯一指认的东西, 否则「登记」本身就是个漏洞。
        self._record(self._carrier() or tgt.id, len(terms), lineno)

    def visit_Assign(self, node):
        if len(node.targets) == 1:
            self._visit_assign(node.targets[0], node.value, node.lineno)
        self.generic_visit(node)

    def visit_AnnAssign(self, node):
        if node.value is not None:
            self._visit_assign(node.target, node.value, node.lineno)
        self.generic_visit(node)

    # -- 载体：所在函数 --------------------------------------------------
    def _enter_func(self, node):
        self.stack.append(node.name)
        self.generic_visit(node)
        self.stack.pop()

    visit_FunctionDef = _enter_func
    visit_AsyncFunctionDef = _enter_func

    def _record(self, name: str, count: int, lineno: int):
        prev = self.found.get(name)
        self.found[name] = (self.module, (prev[1] if prev else 0) + count, lineno)

    def _carrier(self) -> str | None:
        return self.stack[-1] if self.stack else None

    def visit_Call(self, node):
        if id(node) not in self._assigned:
            terms = _regex_cjk_terms(node) or _cjk_membership(node)
            if terms and (name := self._carrier()):
                self._record(name, len(terms), node.lineno)
        self.generic_visit(node)

    def visit_Compare(self, node):
        terms = _cjk_membership(node)
        if terms and (name := self._carrier()):
            self._record(name, len(terms), node.lineno)
        self.generic_visit(node)


def census(root: pathlib.Path | None = None
           ) -> tuple[dict[str, tuple[str, int, int]], list[tuple[str, int, int]]]:
    """返回 (命名词表, 内联无名词表)。

    ⚠️ 内联的最危险 —— 它连名字都没有, **无法被登记、被测试引用、被审**。

    ⚠️ `root` 只为**对照实验**存在(把闸指到一棵合成小树上)。默认仍是读路径全树,
       ⛔ 不许拿它把真实的树排除掉。
    """
    named: dict[str, tuple[str, int, int]] = {}
    inline: list[tuple[str, int, int]] = []
    for path in sorted((root or ROOT).rglob("*.py")):
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"))
        except SyntaxError:
            continue
        seen = set()
        for node in ast.walk(tree):
            tgt = val = None
            if isinstance(node, ast.Assign) and len(node.targets) == 1:
                tgt, val = node.targets[0], node.value
            elif isinstance(node, ast.AnnAssign) and node.value is not None:
                tgt, val = node.target, node.value
            if not isinstance(tgt, ast.Name):
                continue
            inner = val.args[0] if isinstance(val, ast.Call) and val.args else val
            vals = _cjk_seq(inner)
            if vals:
                seen.add(id(inner))
                named[tgt.id] = (path.name, len(vals), node.lineno)
        for node in ast.walk(tree):
            vals = _cjk_seq(node)
            if vals and id(node) not in seen:
                inline.append((path.name, len(vals), node.lineno))
        # 🔴 正则 / 单串比较 —— 与序列字面量同样进 `named`（按载体名登记）
        scan = _CarrierScan(path.name)
        scan.visit(tree)
        for name, row in scan.found.items():
            if name in named:          # 同名已被序列字面量占用 ⇒ 合并条数
                mod, cnt, ln = named[name]
                named[name] = (mod, cnt + row[1], ln)
            else:
                named[name] = row
    return named, inline


def consumers(name: str) -> list[str]:
    """这个词表在哪里被消费 —— ⛔ 不判断「跟谁比」, 只把行打出来让人读。"""
    out = []
    for path in sorted(ROOT.rglob("*.py")):
        for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if name in line and not line.lstrip().startswith("#"):
                out.append(f"{path.name}:{i}: {line.strip()[:96]}")
    return out[1:]  # 第一条是定义本身


# ══════════════════════════════════════════════════════════════════════════
# 🔴 第三道判据 (2026-08-20): 内联无名词表里, 【直接决定一次 return】的那一档
#
# 上面第一道棘轮只管**命名**词表(150 个 / 1856 条)。而同一次普查的另一半是:
#
#     内联无名  258 个 / 1592 条   ⚠️ 无法被登记 / 被测试引用 / 被审计
#
# ▎它们连名字都没有 —— 所以第一道棘轮**一条都看不见**, 2026-08-17 的普查卡
# ▎自己把这一片标成「本次普查最大的盲区」, 至今没治。
#
# ⛔ 不给整片 1592 条建闸。整片里绝大多数是产品自己的措辞池、SQL 片段、
#    日志解析 —— 对它们报红就是一道**当天被关掉**的闸(形态 E)。
#    只取**离决策最近**的那一档: `if <内联中文字面量>: return / continue`。
#
# 为什么是这一档: 「最差」那个 bug 的长相就是它 ——
# 一张手工列表直接决定「这句话按不按菜品去查」, 而列表补不全。
# ══════════════════════════════════════════════════════════════════════════

#: 内联决策档的词数门槛。⚠️ **2**, 比命名棘轮的 3 低一档。
#:
#: ⛔ 不是口径不一致 —— 两道闸问的是两个问题:
#:     命名棘轮问「这张表值不值得登记一类」  ⇒ 3 个词以下不值得单独立一类
#:     本档问「这个 if 是不是拿手写词在决定 return」 ⇒ 两个词照样决定
#: 📏 `if any(t in q for t in ("最好", "最差")): return` 正是那个 bug 的形状,
#:    门槛 3 会把它整个漏掉, 而且给出一条**廉价的规避路**: 拆成两两一组。
INLINE_DECISION_MIN_TERMS = 2

#: 直接终止这一支的语句。⛔ 只认 `if` **body 里第一层**的这两种。
#: ⚠️ 不含 `break`/`raise`: 实测它们在本树上一处都没有(加进来读数不变),
#:    ⇒ 收窄成实测存在的两种, 免得判据比它守的东西大。
_DECIDING_STMTS = (ast.Return, ast.Continue)


class InlineDecision(NamedTuple):
    """一处「内联无名中文字面量, 直接决定一次 return/continue」。"""
    module: str
    lineno: int          # 字面量自己的行
    if_lineno: int       # 那个 `if` 的行
    function: str
    terms: tuple[str, ...]

    @property
    def point(self) -> tuple[str, str, int]:
        """决策点 —— 同一个 `if` 里的多个字面量共用一个点。"""
        return (self.module, self.function, self.if_lineno)


def _named_literal_ids(tree, min_terms: int) -> set[int]:
    """被赋给某个名字的字面量 —— 那些是**命名**的, 归第一道棘轮管。

    ⛔ 本档只要**无名**的。同一份数不许被两道闸各算一遍(形态 D)。
    """
    seen: set[int] = set()
    for node in ast.walk(tree):
        tgt = val = None
        if isinstance(node, ast.Assign) and len(node.targets) == 1:
            tgt, val = node.targets[0], node.value
        elif isinstance(node, ast.AnnAssign) and node.value is not None:
            tgt, val = node.target, node.value
        if not isinstance(tgt, ast.Name):
            continue
        inner = val.args[0] if isinstance(val, ast.Call) and val.args else val
        if _cjk_seq(inner, min_terms):
            seen.add(id(inner))
    return seen


def inline_decision_census(root: pathlib.Path | None = None
                           ) -> tuple[InlineDecision, ...]:
    """所有「内联无名中文词表直接决定一次 return/continue」的点。

    ⚠️ `root` 只为**对照实验**存在(把闸指到合成小树上), ⛔ 不许拿它排除真实树。
    """
    out: list[InlineDecision] = []
    for path in sorted((root or ROOT).rglob("*.py")):
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"))
        except SyntaxError:
            continue
        named = _named_literal_ids(tree, INLINE_DECISION_MIN_TERMS)

        def walk(node, fn: str) -> None:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                fn = node.name
            # ⚠️ `elif` 在 AST 里是 orelse 里的另一个 `If` —— 靠下面的递归到达,
            #    ⛔ 不用特判。
            if isinstance(node, ast.If) and any(
                    isinstance(s, _DECIDING_STMTS) for s in node.body):
                for sub in ast.walk(node.test):
                    vals = _cjk_seq(sub, INLINE_DECISION_MIN_TERMS)
                    if vals and id(sub) not in named:
                        out.append(InlineDecision(
                            module=path.name, lineno=sub.lineno,
                            if_lineno=node.lineno, function=fn,
                            terms=tuple(vals)))
            for child in ast.iter_child_nodes(node):
                walk(child, fn)

        walk(tree, "<module>")
    return tuple(out)


def inline_decision_points(rows) -> tuple[tuple[str, str, int], ...]:
    """去重成**决策点**(同一个 `if` 算一个)。⚠️ 报数用, ⛔ 不是棘轮的计数单位。"""
    return tuple(sorted({r.point for r in rows}))


#: 【内联无名词表直接决定 return】的**处**数上限。⛔ 只许调小。
#:
#: 📏 2026-08-20 建闸当天实测(口径: 门槛 2, `if` body 第一层 return/continue,
#:    只数**未被赋名**的字面量, 全读路径 `gold/restaurant/**/*.py`):
#:
#:        88 处 / 482 条 / 去重后 61 个决策点
#:
#: ⚠️ **计数单位是「处」= 一个字面量出现一次**, ⛔ 不是「多少行代码」。
#:    为什么不按 `if` 去重成 61: `if x in (A) or y in (B): return` 是**两张**
#:    手写词表 OR 在一起, 不是一个判断被拆成两处写。
#:    (`MAX_LEXICAL_VETOES` 按 guard 去重是对的 —— 那里是**同一个**判断
#:     写了三个槽位; 两者形状不同, ⛔ 别照搬。)
MAX_INLINE_DECISION_SITES = 88

#: 同一档的**条**数上限。⛔ 只许调小。📏 当天实测 482。
#:
#: 🔴 为什么两个都要: 它们各自有一半是瞎的 ——
#:     只数「处」 ⇒ 把 30 条的表悄悄加到 45 条, 处数**不变**
#:     只数「条」 ⇒ 删掉 5 条老词、新开一处 5 条的表, 条数**不变**
#:    而「一张补不全的表越补越长」正是「最差」那个 bug 的机制本身。
MAX_INLINE_DECISION_TERMS = 482


# ══════════════════════════════════════════════════════════════════════════
# 🔴 第二道判据 (2026-08-19): 词表的结果, 能不能【单独】否决 LLM 已填的槽位?
#
#   能 = 决策    不能 = 辅助
#
# 上面那道棘轮问的是「有多少张词表」——它对**用法**是盲的。而 owner 的分界
# 从来不是「词表 vs LLM」, 是**猜 vs 查**: 词表和 LLM 都在猜, 目录/能力表在查。
#
#   ⛔ 用一个猜出来的东西去否决另一个猜出来的东西, 两边都没有依据。
#     （原话在 `restaurant_intent.py` 的 `llm_asked_store_grain` 那一段）
#
# ⇒ 本判据**不消灭词表**。词表当辅助(补一个 LLM 没填的槽 / 给 LLM 提候选 /
#   事后加注)一律放行, 只拦「词表**单独**把一个 LLM 已经填好的槽位收窄/清空」。
#
# 合规长相(仓里现成的一条, `_build_spec` 2670 行):
#
#     llm_asked_store_grain = bool(
#         llm_dimensions is not None and "store" in tuple(llm_dimensions)
#         and explicit_store_scope != "all"
#         and _resolver_serves_dimensions(code, ("store",))   # ← 查能力表
#     )
#     if store_scope == "all" and "store" in dimension_list \
#             and not _asks_store_breakdown(effective_query)  # ← 词表
#             and not llm_asked_store_grain:
#         dimension_list.remove("store")
#
# 词表在, 但它**说了不算** —— 能力表才是放行/否决的那一票。
# ══════════════════════════════════════════════════════════════════════════

#: LLM 填的槽位。来源是 `_build_spec` 的 11 个 `llm_*` 形参 → spec 字段。
#: ⛔ 显式登记, 与 `REGISTRY` 同一条纪律(人的判断, 写下来可被质疑)。
#: `test_the_llm_slot_list_still_matches_build_spec` 钉住它别漂。
LLM_SLOTS: frozenset[str] = frozenset({
    "wants_margin",        # llm_wants_margin
    "asks_profitability",  # llm_asks_profitability
    "dish_slot",           # llm_dish
    "store_slot",          # llm_store
    "store_slots",         # llm_stores
    "requested_metrics",   # llm_requested_metrics
    "dimensions",          # llm_dimensions
    "analysis_action",     # llm_analysis_action
    "aggregation",         # llm_aggregation
    "store_scope",         # llm_store_scope
})

#: 「查」—— 去问真实数据的东西。guard 里出现其中之一 ⇒ 这次否决**有依据**。
#:
#: ⛔ 这是**代理判据**: 静态分析判不出「这个调用有没有去查数据」, 只能靠登记。
#:    登记是留痕不是豁免 —— 往这里加一个名字等于当众声明「它真的去查了」。
#: ⚠️ 它同时是本闸最大的**可被规避点**: 把词表函数改个名塞进这里, 闸就闭嘴。
#:    ⇒ `test_the_evidence_registry_is_not_a_wordlist` 反向钉住:
#:      登记进来的东西**不许**同时被判定为词法判据。
EVIDENCE_SOURCES: frozenset[str] = frozenset({
    # 能力表: 这个 resolver 到底服务不服务这个粒度
    "_RESOLVER_DIMENSIONS", "_resolver_serves_dimensions",
    # 租户菜单目录
    "_catalogue_says_not_a_dish", "_catalogue_says_a_longer_name",
    "_DISH_CATALOGUE", "dish_catalogue", "_dish_catalogue",
    # 门店权威名单
    "_load_store_options", "resolve_store_name", "store_universe",
    # 指标登记表(指标存不存在 / 依赖哪些列)
    "METRICS", "_metric_requires",
})

#: 【词表单独否决 LLM 槽位】的**决策点**数量上限。⛔ 只许调小。
#:
#: 2026-08-19 建闸当天实测 **4**(去重后), 分别是:
#:
#:   ① restaurant_intent.py:2621  `dimension_list.remove("ingredient")`
#:      —— 6 个中文词(食材/原料/配料/采购/领料/库存)单独剥掉 LLM 给的维度
#:   ② restaurant_intent.py:2689  `dimensions = tuple(v for v in dimensions …)`
#:      —— `_detect_comparison` 命中就剥掉 LLM 给的 time 维度
#:   ③ restaurant_intent.py:6913/7267 `_is_explicit_store_directory_query`
#:      —— 一条纯正则同时清掉 requested_metrics / wants_margin /
#:         asks_profitability(3 处写, 同一个决策 ⇒ 算 1)
#:   ④ restaurant_intent_service.py:1178 `changes["wants_margin"] = False`
#:      —— 「问句里没有毛利二字」就撤掉 planner 填的 wants_margin
#:
#: ⚠️ 棘轮量的是**决策点**(module, 函数, guard 原文)去重后的数,
#:    ⛔ 不是「有多少行收窄写」—— 同一个判断拆成三处赋值不该虚高成 3。
#: ⚠️ 这 4 个都是**存量**。棘轮只保证不再长, ⛔ 不代表它们都合理。
#:    ③ 的 `_is_explicit_store_directory_query` 自己的 docstring 就写着
#:    「a complete model response may not turn an explicit directory object
#:      into an unrelated metric clarification」—— 它是**有意**覆盖 LLM 的。
#
# ── 2026-08-20 棘轮下调 4 → 3 ────────────────────────────────────────────
#
# ② 已经不是「词表单独否决」了: `_detect_comparison` 命中之后**还要**
#    `_resolver_serves_dimensions(code, ("time",))` 才动手, 而词表
#    (`_TIME_GRAIN_TOKENS`) 挪到了**解锁侧** —— 命中就保留 LLM 给的维度,
#    漏词维持原行为。普查现在把它判成 ✅ 有旁证。
#
# ⚠️ ③ **仍然被计入**, 那是**代理判据的盲点、不是漏改**:
#    它的 guard 现在是 `_is_explicit_store_directory_query(query) and
#    parsed["clarification_needed"]` —— 只在**模型自己说不准**时才接管,
#    按本闸自己的定义(「词表单独把 LLM **已经填好**的槽位收窄」)已经出靶。
#    但 `clarification_needed` 是模型的**自述**, 既不是词表也不是「查」,
#    `EVIDENCE_SOURCES` 里没有它, 于是普查照旧点名。
#    ⛔ **不把它塞进 `EVIDENCE_SOURCES` 消音** —— 那个登记表的含义是
#       「它真的去查了数据」, 塞非查的东西进去正是本闸 docstring 里写的
#       「最大可被规避点」。宁可计数偏保守。
#
# ── 2026-08-20 棘轮下调 4 → 3 ────────────────────────────────────────────
#
# ① 不再是「词表单独否决」了: 那 6 个中文词照旧(它们在补
#    `extract_dish_candidate` 的假阳性, 详见调用点的登记), 但**再加了一票能力表**
#    —— resolver 真能按食材出时 ⛔ 不许剥。⇒ 词表说了不算。
#
# ⚠️ ② (`_detect_comparison` 剥 time) **仍然在册**: 收窄它试过、量过、撤了 ——
#    保留 time 维度会落到 `TREND_ANALYSIS`, 而它按月分桶又剔残月, 三条实测问句
#    全部变差(丢掉了唯一那个同期对比数字)。逐字读数与解冻条件写在
#    `docs/decisions/2026-08-20-比较句吃掉时间粒度-量过撤了.md`。
#
# ⚠️ ③ 也已收窄(只在模型自述 `clarification_needed=True` 时接管), 按本闸自己的
#    定义已出靶, 但 `clarification_needed` 既不是词表也不是 `EVIDENCE_SOURCES`
#    里的「查」, 普查照旧点名。⛔ 不把它塞进 `EVIDENCE_SOURCES` 消音。
MAX_LEXICAL_VETOES = 3

_BUILTIN_CALLS = frozenset({
    "bool", "any", "all", "len", "tuple", "list", "set", "str", "int",
    "isinstance", "getattr", "sorted", "min", "max", "sum", "frozenset",
    "dict", "enumerate", "zip", "next", "iter", "abs", "round",
})
_STR_METHODS = frozenset({
    "startswith", "endswith", "strip", "lstrip", "rstrip", "lower", "upper",
    "replace", "join", "split", "format", "count", "find",
    "get", "keys", "values", "items",
})
#: 收窄容器的方法。⛔ 不含 `append`/`add` —— 那是**加**, 不是否决。
_NARROWING_METHODS = frozenset({"remove", "pop", "clear", "discard"})
#: 「清空」的字面量。`False`/`0` 在这里算清空 —— 布尔槽位的否决就长这样。
_EMPTY_SOURCE = frozenset({
    "None", "()", "[]", "{}", "''", '""', "frozenset()", "set()",
    "tuple()", "list()", "0", "False",
})


class Veto(NamedTuple):
    """一次「在词表 guard 之下收窄 LLM 槽位」的写。"""
    module: str
    lineno: int
    function: str
    kind: str            # N1..N5, 见 `_narrowing_write`
    target: str
    guard: str
    wordlists: tuple[str, ...]
    evidence: tuple[str, ...]

    @property
    def point(self) -> tuple[str, str, str]:
        """决策点标识 —— 同一个 guard 下的多处写算**一个**决策。"""
        return (self.module, self.function, self.guard)


def _callee_name(node: ast.Call) -> str | None:
    fn = node.func
    if isinstance(fn, ast.Attribute):
        return fn.attr
    if isinstance(fn, ast.Name):
        return fn.id
    return None


def _cjk_dict_names(tree) -> set[str]:
    """模块级 `{键: (中文, 中文, …)}` —— `x in D["k"]` 同样是在查词表。

    🔴 缺了这一条会漏掉本闸命中的第 ④ 例:
       `any(tok in query for tok in _REQUEST_TEXT_TOKENS["gross_margin"])`
       —— 那张表是 dict, `_cjk_seq` 只认序列字面量。
    """
    out: set[str] = set()
    for node in ast.walk(tree):
        tgts, val = [], None
        if isinstance(node, ast.Assign):
            tgts, val = node.targets, node.value
        elif isinstance(node, ast.AnnAssign) and node.value is not None:
            tgts, val = [node.target], node.value
        if not isinstance(val, ast.Dict):
            continue
        if any(_cjk_seq(v) for v in val.values):
            out.update(t.id for t in tgts if isinstance(t, ast.Name))
    return out


def _names_a_wordlist(node, wordlist_names, dict_names) -> bool:
    if isinstance(node, ast.Name):
        return node.id in wordlist_names or node.id in dict_names
    if isinstance(node, ast.Subscript):
        return _names_a_wordlist(node.value, wordlist_names, dict_names)
    if isinstance(node, ast.Call):
        fn = node.func
        if isinstance(fn, ast.Attribute) and fn.attr == "get":
            return _names_a_wordlist(fn.value, wordlist_names, dict_names)
        if isinstance(fn, ast.Name) and fn.id in ("tuple", "list", "set",
                                                  "frozenset") and node.args:
            return _names_a_wordlist(node.args[0], wordlist_names, dict_names)
    if isinstance(node, ast.BoolOp):
        return any(_names_a_wordlist(v, wordlist_names, dict_names)
                   for v in node.values)
    return False


def _is_wordlist_probe(node, wordlist_names, dict_names) -> bool:
    """这一个节点是不是「拿中文在匹配」。⛔ 复用上面那三条判据, 不另写一份。"""
    if _regex_cjk_terms(node) or _cjk_membership(node) or _cjk_seq(node):
        return True
    if isinstance(node, ast.Compare) and len(node.ops) == 1 and isinstance(
            node.ops[0], (ast.In, ast.NotIn)):
        return _names_a_wordlist(node.comparators[0], wordlist_names, dict_names)
    if isinstance(node, ast.comprehension):
        return _names_a_wordlist(node.iter, wordlist_names, dict_names)
    return False


def lexical_predicates(trees, wordlist_names, dict_names) -> set[str]:
    """不动点: **整个函数体**只有中文匹配 + 内建/re/别的词法判据 ⇒ 它自己就是词表。

    🔴 少了这一层, `if not _asks_store_breakdown(q):` 这种 guard 看不出有词表 ——
       词表被藏在一个函数名后面, 而那正是仓里的主流写法。
    ⚠️ 名字是**跨模块**并集: 本仓靠 `_` 前缀 + 长名, 重名风险低;
       重名会让判据**偏保守**(把一个不是词法的函数当成词法 ⇒ 多报), 不会漏报。
    """
    funcs: dict[str, ast.AST] = {}
    for tree in trees:
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                funcs.setdefault(node.name, node)
    found: set[str] = set()
    for _ in range(8):
        grew = False
        for name, fn in funcs.items():
            if name in found:
                continue
            if not any(_is_wordlist_probe(s, wordlist_names, dict_names)
                       for s in ast.walk(fn)):
                continue
            allowed = _BUILTIN_CALLS | _STR_METHODS | _RE_FUNCS | found | {name}
            if [c for c in ast.walk(fn) if isinstance(c, ast.Call)
                    and (_callee_name(c) or "?") not in allowed]:
                continue
            found.add(name)
            grew = True
        if not grew:
            break
    return found


def _single_assignments(fn) -> dict[str, ast.AST]:
    """只赋值过**一次**的局部名 → 它的表达式。

    ⚠️ 赋值多次的**不展开** —— 展开哪一次都可能不是 guard 当时看到的那个。
    """
    seen: dict[str, list] = {}
    for node in ast.walk(fn):
        tgts, val = [], None
        if isinstance(node, ast.Assign):
            tgts, val = node.targets, node.value
        elif isinstance(node, ast.AnnAssign) and node.value is not None:
            tgts, val = [node.target], node.value
        if val is None:
            continue
        for t in tgts:
            if isinstance(t, ast.Name):
                seen.setdefault(t.id, []).append(val)
    return {k: v[0] for k, v in seen.items() if len(v) == 1}


def _expand(node, defs, depth=0, seen=()) -> list:
    """把 guard 里的局部名换成它的定义(有界, 深度 4)。

    🔴 不展开就两头都看不见: 词表藏在 `explicit_store_directory` 后面,
       证据也藏在 `llm_asked_store_grain` 后面。
    """
    out = [node]
    if depth > 4:
        return out
    for sub in ast.walk(node):
        if isinstance(sub, ast.Name) and sub.id in defs and sub.id not in seen:
            out.extend(_expand(defs[sub.id], defs, depth + 1, seen + (sub.id,)))
    return out


def _llm_tainted_locals(fn) -> set[str]:
    """函数里由 `llm_*` 形参传染出来的局部名(`dimension_list` 就是这么来的)。"""
    tainted = {a.arg for a in fn.args.args + fn.args.kwonlyargs
               if a.arg.startswith("llm_")}
    for _ in range(8):
        grew = False
        for node in ast.walk(fn):
            tgts, val = [], None
            if isinstance(node, ast.Assign):
                tgts, val = node.targets, node.value
            elif isinstance(node, ast.AnnAssign) and node.value is not None:
                tgts, val = [node.target], node.value
            if val is None:
                continue
            if not ({s.id for s in ast.walk(val)
                     if isinstance(s, ast.Name)} & tainted):
                continue
            for t in tgts:
                for sub in ast.walk(t):
                    if isinstance(sub, ast.Name) and sub.id not in tainted:
                        tainted.add(sub.id)
                        grew = True
        if not grew:
            break
    return tainted


def _is_empty_literal(node) -> bool:
    return ast.unparse(node).strip() in _EMPTY_SOURCE


def _filters_itself(name: str, value) -> bool:
    """`X = tuple(v for v in X if …)` —— 就地过滤掉一部分, 是收窄。"""
    for comp in ast.walk(value):
        if not isinstance(comp, (ast.GeneratorExp, ast.ListComp, ast.SetComp)):
            continue
        if not any(g.ifs for g in comp.generators):
            continue
        if any(isinstance(s, ast.Name) and s.id == name
               for g in comp.generators for s in ast.walk(g.iter)):
            return True
    return False


def _narrowing_write(node, slots) -> tuple[str, str] | None:
    """这条语句是不是在**收窄** LLM 槽位。⛔ 只认收窄, 不认新填。

    ⚠️ 「新填」(`if not spec.dish_slot and <词表>: dish_slot = …`)是**辅助**,
       本判据放行它 —— 那正是 owner 说的「补一个 LLM 没填的槽」。
    """
    if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call):
        call = node.value
        if isinstance(call.func, ast.Attribute) \
                and call.func.attr in _NARROWING_METHODS:
            base = call.func.value
            name = base.id if isinstance(base, ast.Name) else (
                base.attr if isinstance(base, ast.Attribute) else None)
            if name in slots:
                return "N1", f"{name}.{call.func.attr}()"
        return None
    if not isinstance(node, ast.Assign) or len(node.targets) != 1:
        return None
    tgt, val = node.targets[0], node.value
    if isinstance(tgt, ast.Name) and tgt.id in slots:
        if _is_empty_literal(val):
            return "N2", f"{tgt.id} = {ast.unparse(val)}"
        if _filters_itself(tgt.id, val):
            return "N3", f"{tgt.id} = {ast.unparse(val)[:60]}"
    if isinstance(tgt, ast.Subscript) and isinstance(tgt.slice, ast.Constant) \
            and tgt.slice.value in LLM_SLOTS and _is_empty_literal(val):
        return "N4", f"{ast.unparse(tgt)} = {ast.unparse(val)}"
    if isinstance(tgt, ast.Attribute) and tgt.attr in LLM_SLOTS \
            and _is_empty_literal(val):
        return "N5", f"{ast.unparse(tgt)} = {ast.unparse(val)}"
    if isinstance(tgt, ast.Tuple) and isinstance(val, ast.Tuple) \
            and len(tgt.elts) == len(val.elts):
        for elt, sub in zip(tgt.elts, val.elts):
            if isinstance(elt, ast.Name) and elt.id in slots \
                    and _is_empty_literal(sub):
                return "N2", f"{elt.id} = {ast.unparse(sub)}"
    return None


def _walk_with_guards(node, slots, guards, out) -> None:
    """带 guard 栈遍历。⛔ 不下潜进嵌套函数(它有自己的槽位与 guard)。"""
    if isinstance(node, ast.If):
        for stmt in node.body:
            _walk_with_guards(stmt, slots, guards + [node.test], out)
        for stmt in node.orelse:
            _walk_with_guards(stmt, slots, guards + [node.test], out)
        return
    if isinstance(node, ast.While):
        for stmt in node.body + node.orelse:
            _walk_with_guards(stmt, slots, guards + [node.test], out)
        return
    hit = _narrowing_write(node, slots)
    if hit:
        out.append((node.lineno, hit, list(guards)))
    for child in ast.iter_child_nodes(node):
        if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef,
                              ast.Lambda)):
            continue
        _walk_with_guards(child, slots, guards, out)


def lexical_veto_census(root: pathlib.Path | None = None) -> tuple[Veto, ...]:
    """所有「在词表 guard 之下收窄 LLM 槽位」的写。

    `evidence` 非空 = 同一个 guard 里有登记过的「查」 ⇒ 词表说了不算 ⇒ 合规。
    """
    base = root or ROOT
    trees: dict[str, ast.AST] = {}
    for path in sorted(base.rglob("*.py")):
        try:
            trees[path.name] = ast.parse(path.read_text(encoding="utf-8"))
        except SyntaxError:
            continue
    named, _inline = census(base)
    wordlist_names = set(named)
    dict_names: set[str] = set()
    for tree in trees.values():
        dict_names |= _cjk_dict_names(tree)
    lex = lexical_predicates(trees.values(), wordlist_names, dict_names)

    out: list[Veto] = []
    for module, tree in trees.items():
        for fn in ast.walk(tree):
            if not isinstance(fn, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            slots = LLM_SLOTS | _llm_tainted_locals(fn)
            defs = _single_assignments(fn)
            hits: list = []
            for stmt in fn.body:
                _walk_with_guards(stmt, slots, [], hits)
            for lineno, (kind, target), guards in hits:
                if not guards:
                    continue          # 无条件写不是「否决」, 是流程本身
                expanded = [e for g in guards for e in _expand(g, defs)]
                probes = tuple(sorted({
                    ast.unparse(s)[:60] for e in expanded for s in ast.walk(e)
                    if _is_wordlist_probe(s, wordlist_names, dict_names)
                } | {
                    _callee_name(c) for e in expanded for c in ast.walk(e)
                    if isinstance(c, ast.Call) and _callee_name(c) in lex
                }))
                if not probes:
                    continue
                evidence = tuple(sorted({
                    n.id for e in expanded for n in ast.walk(e)
                    if isinstance(n, ast.Name) and n.id in EVIDENCE_SOURCES
                } | {
                    _callee_name(c) for e in expanded for c in ast.walk(e)
                    if isinstance(c, ast.Call)
                    and _callee_name(c) in EVIDENCE_SOURCES
                }))
                out.append(Veto(
                    module=module, lineno=lineno, function=fn.name, kind=kind,
                    target=target,
                    guard=" AND ".join(ast.unparse(g) for g in guards),
                    wordlists=probes, evidence=evidence,
                ))
    return tuple(out)


def lexical_veto_points(vetoes) -> tuple[tuple[str, str, str], ...]:
    """去重成**决策点** —— 同一个 guard 下写三个槽位算一个决策, ⛔ 不算三个。"""
    return tuple(sorted({v.point for v in vetoes}))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ratchet", action="store_true",
                    help="只查未登记数量, 超过上限退出码 1")
    ap.add_argument("--vetoes", action="store_true",
                    help="只查【词表单独否决 LLM 槽位】的决策点, 超过上限退出码 1")
    ap.add_argument("--inline", action="store_true",
                    help="只查【内联无名词表直接决定 return】的处/条, 超过上限退出码 1")
    args = ap.parse_args()

    if args.inline:
        rows = inline_decision_census()
        terms = sum(len(r.terms) for r in rows)
        points = inline_decision_points(rows)
        print(f"内联无名词表【直接决定 return/continue】: "
              f"{len(rows)} 处 / 上限 {MAX_INLINE_DECISION_SITES}")
        print(f"                                          "
              f"{terms} 条 / 上限 {MAX_INLINE_DECISION_TERMS}")
        print(f"  (去重后 {len(points)} 个决策点 —— 仅供参考, ⛔ 不是棘轮单位)\n")
        for r in sorted(rows, key=lambda x: -len(x.terms)):
            print(f"  {r.module}:{r.lineno:<6} if@{r.if_lineno:<6} "
                  f"{len(r.terms):>3} 条  fn={r.function}")
            print(f"        {list(r.terms)[:8]}")
        if (len(rows) > MAX_INLINE_DECISION_SITES
                or terms > MAX_INLINE_DECISION_TERMS):
            print(f"\n⛔ 新增了「内联无名词表直接决定 return」的点"
                  f"（{len(rows)} 处 / {terms} 条）。\n"
                  "   这一档连名字都没有 ⇒ 无法被登记、被测试引用、被审计。\n"
                  "   出路(按优先级):\n"
                  "     ① 改成对目录 / 能力表 / 指标登记表**查** —— ⛔ 不是补词表\n"
                  "     ② 实在要留, 提成模块级常量并在 REGISTRY 里登记它属于哪一类,\n"
                  "        至少让它有名字、能被引用、能被下一个人质疑")
            return 1
        return 0

    if args.vetoes:
        rows = lexical_veto_census()
        bad = [v for v in rows if not v.evidence]
        points = lexical_veto_points(bad)
        print(f"词表 guard 下收窄 LLM 槽位的写: {len(rows)} 处 "
              f"({len(rows) - len(bad)} 处有证据源)")
        print(f"【词表单独否决】决策点: {len(points)} / 上限 {MAX_LEXICAL_VETOES}\n")
        for v in rows:
            tag = "✅ 有旁证" if v.evidence else "🔴 单独否决"
            print(f"{tag}  {v.module}:{v.lineno}  fn={v.function}  "
                  f"[{v.kind}] {v.target}")
            print(f"     词表: {list(v.wordlists)[:3]}   证据源: {list(v.evidence)}")
            print(f"     guard: {v.guard[:150]}")
        if len(points) > MAX_LEXICAL_VETOES:
            print("\n⛔ 新增了「词表单独否决 LLM 槽位」的点。\n"
                  "   判据: 一个词表的结果, 能不能【单独】否决 LLM 已经填好的槽位?\n"
                  "         能 = 决策(不许新增)   不能 = 辅助(放行)\n"
                  "   ⛔ 用一个猜出来的东西去否决另一个猜出来的东西, 两边都没有依据。\n"
                  "   出路: 把否决那一票交给「查」(目录 / 能力表 / 指标登记表),\n"
                  "         词表退回辅助位; 或把那个「查」登记进 EVIDENCE_SOURCES。")
            return 1
        return 0

    named, inline = census()
    unregistered = sorted(n for n in named if n not in REGISTRY)

    if args.ratchet:
        print(f"未登记的命名词表: {len(unregistered)} / 上限 {MAX_UNREGISTERED}")
        for n in unregistered:
            mod, cnt, ln = named[n]
            print(f"  {n:<44} {cnt:>3} 条  {mod}:{ln}")
        if len(unregistered) > MAX_UNREGISTERED:
            print("\n⛔ 新增了未登记的手工词表。请在 REGISTRY 里登记它属于哪一类, "
                  "并顺手问一句: 这件事能不能改成对目录/闭集校验?")
            return 1
        return 0

    buckets: dict[str, list[tuple[str, str, int]]] = defaultdict(list)
    for name, (mod, cnt, _ln) in named.items():
        buckets[REGISTRY.get(name, "未登记")].append((name, mod, cnt))

    for kind in (ENTITY, INTENT, SAFETY, LEXICAL, "未登记"):
        rows = sorted(buckets.get(kind, []), key=lambda r: -r[2])
        if not rows:
            continue
        print(f"\n{'=' * 96}")
        print(f"== {kind} ==  {len(rows)} 个词表 / {sum(r[2] for r in rows)} 条")
        for name, mod, cnt in rows:
            print(f"  {name:<44} {cnt:>3} 条  {mod}")
            for line in consumers(name)[:1]:
                print(f"        {line}")

    print(f"\n{'=' * 96}\n== 汇总 ==")
    print(f"  命名词表   {len(named):>4} 个 / {sum(v[1] for v in named.values()):>5} 条")
    print(f"  内联无名   {len(inline):>4} 个 / {sum(v[1] for v in inline):>5} 条"
          f"   ⚠️ 无法被登记/测试引用/审计")
    dec = inline_decision_census()
    print(f"    └ 直接决定 return  {len(dec):>3} 处 / "
          f"{sum(len(r.terms) for r in dec):>4} 条"
          f"   (上限 {MAX_INLINE_DECISION_SITES} / {MAX_INLINE_DECISION_TERMS},"
          f" 见 --inline)")
    print(f"  未登记     {len(unregistered):>4} 个 (上限 {MAX_UNREGISTERED})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
