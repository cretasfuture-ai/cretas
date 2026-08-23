# -*- coding: utf-8 -*-
"""22 项缺口的**当前状态**逐条实测。

⛔ 不凭印象、不从注释推断。每一项配一条机械判据 + 一条阳性对照
（会读出「全都没有」的仪器必须有对照，否则分不清真的没有 / 仪器坏了）。

判据分两类：
  AST  —— 「这个东西有没有被【调用】」，⛔ 不 grep 文本（docstring 会混进来）
  RUN  —— 「跑一次让它自己说」，用于行为类

输出三态：  ✅ 做了 / 🔴 没做 / ⚠️ 判不了(仪器不足, 单独列)
"""
from __future__ import annotations

import ast
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, ROOT)

R = os.path.join(ROOT, "smartbi", "gold", "restaurant")


def _src(name: str) -> str:
    with open(os.path.join(R, name), "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def _tree(name: str):
    return ast.parse(_src(name))


def calls_of(name: str, func: str) -> int:
    """AST 数**调用**次数 —— ⛔ 不数文本出现次数（docstring/注释会混进来）。"""
    n = 0
    for node in ast.walk(_tree(name)):
        if isinstance(node, ast.Call):
            f = node.func
            if isinstance(f, ast.Name) and f.id == func:
                n += 1
            elif isinstance(f, ast.Attribute) and f.attr == func:
                n += 1
    return n


def defines(name: str, func: str) -> bool:
    return any(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == func
               for n in ast.walk(_tree(name)))


def imports_module(name: str, needle: str) -> bool:
    for node in ast.walk(_tree(name)):
        if isinstance(node, ast.ImportFrom) and needle in (node.module or ""):
            return True
        if isinstance(node, ast.Import):
            for a in node.names:
                if needle in a.name:
                    return True
    return False


def has_param(name: str, func: str, param: str) -> bool:
    for node in ast.walk(_tree(name)):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == func:
            args = node.args
            names = ([a.arg for a in args.args] + [a.arg for a in args.kwonlyargs]
                     + [a.arg for a in args.posonlyargs])
            return param in names
    return False


FILES = ("restaurant_intent.py", "restaurant_intent_service.py",
         "restaurant_ops_router.py")

ROWS = []


def rec(no, title, ok, evidence):
    ROWS.append((no, title, ok, evidence))


#: 这些名字出现在证据串里是**格式化用的**，不是被忽略的判据依据。
#: ⚠️ 这是一份**代理判据的白名单** —— 它会漏（一个真正被忽略的依据如果恰好
#:    叫 `len` 就看不见），所以下面那条自检**只报不拦**（形态 E）。
_SELFCHECK_FORMATTING_NAMES = frozenset((
    "len", "sorted", "any", "all", "k", "v", "locals", "calls_of",
))


def _print_verdict_vs_evidence_selfcheck():
    """🔴 自检：**判定式有没有忽略它自己证据里提到的东西**。

    2026-08-19 加。起因是第 13 格：判定式只读 `_norm_store` ⇒ 打 **✅**，
    而同一行的证据串写着 `菜品=False ⚠️ 菜品侧仍是缺口`。
    ▎**内容对，标签假** —— 而读账的人看的是那个符号。

    ⛔ 这条自检**只报不拦**：判定它得靠一份「哪些名字只是格式化」的白名单，
       那是**代理判据**，会漏也会误报。一道会误报的闸最终会被关掉（形态 E），
       所以让它把可疑项打出来给人读，⛔ 不让它决定 rc。
    """
    import ast as _ast
    try:
        with open(__file__, "r", encoding="utf-8", newline="") as _fh:
            _self = _ast.parse(_fh.read())
    except (OSError, SyntaxError):
        print("\n  ⚠️ 自检没跑起来（读不到自己的源码）—— ⛔ 不当成「没问题」")
        return
    suspects = []
    for _n in _ast.walk(_self):
        if not (isinstance(_n, _ast.Call) and isinstance(_n.func, _ast.Name)
                and _n.func.id == "rec" and len(_n.args) >= 4):
            continue
        _no = (_n.args[0].value if isinstance(_n.args[0], _ast.Constant) else "?")
        _v = {x.id for x in _ast.walk(_n.args[2]) if isinstance(x, _ast.Name)}
        _e = {x.id for x in _ast.walk(_n.args[3]) if isinstance(x, _ast.Name)}
        _gap = (_e - _v) - _SELFCHECK_FORMATTING_NAMES
        if _gap:
            suspects.append((_no, sorted(_gap)))
    print("\n  自检 · 判定式忽略了自己证据里的名字（⚠️ 只报不拦，代理判据会误报）")
    if not suspects:
        print("     无")
        return
    for _no, _gap in suspects:
        print("     #%-3s 证据提到但判定没读: %s" % (_no, _gap))
    print("     ▎逐条读一遍：是**格式化变量**（无害，加进白名单）"
          "还是**半个缺口被记成 ✅**（那就是第 13 格那个坑）")


# ── 阳性对照：仪器本身活着吗 ────────────────────────────────────────────────
CONTROL = []
CONTROL.append(("AST 能数到调用",
                calls_of("restaurant_ops_router.py", "_markdown_table") > 0,
                "_markdown_table 调用 %d 次" % calls_of("restaurant_ops_router.py",
                                                        "_markdown_table")))
CONTROL.append(("AST 能认出 def",
                defines("restaurant_ops_router.py", "resolve_channel_mix"),
                "resolve_channel_mix 已定义"))
CONTROL.append(("AST 能认出形参",
                has_param("restaurant_ops_router.py", "resolve_daypart_performance",
                          "dimensions"),
                "resolve_daypart_performance(dimensions=…) 存在"))
CONTROL.append(("阴性对照：不存在的名字数出 0",
                calls_of("restaurant_ops_router.py", "__不存在的函数__") == 0,
                "拿一个必然不存在的名字，数出 0"))

# ── 1 菜单目录接进核对 ─────────────────────────────────────────────────────
# 🔴 2026-08-19 订正（**假阴性**，这条判据在量错的对象上）:
#    原判据要求 `restaurant_intent.py` 里有 `_catalogue_says_not_a_dish` 的调用点，
#    实测 0 ⇒ 报 🔴。但**核对根本不在规划期做** ——
#    它由执行入口 `dish_catalogue_scope` 把租户菜单绑在整轮上、
#    再由 `restaurant_ops_router` 内部那几处消费。
#    ▎形态 A：我量的这个数，不是我真正想知道的那个数。
#        想知道的 X：一个**不是菜**的东西被当成 dish 槽位时，产品会不会被挡住
#        实际量的 Y：某个函数名在**某一个文件**里出现几次
#
#    📏 行为实测（prod，MOCK_REST，2 轮，`smartbi.scripts._dish_catalogue_behaviour`）:
#      「佛跳墙卖得怎么样」（目录里没有这道菜）2/2 轮拿到 ——
#        **菜单里没查到「佛跳墙」这道菜**，所以没法单独给它的销量或毛利
#        —— 也不会拿别的菜的榜单顶替。
#        · 是刚上架的新菜吗？先把它加进菜单，加完这里就能单独看它。
#        · 还是我认错了字？在售的菜有：凉拌木耳、娃娃菜、…（共 10 道），报个名字就行。
#      判据用的是**结构**而不是文案：答案里报出了**问句里没有的目录菜名 8 道**
#      —— 只有真的拿到租户菜单才说得出来。
#      ⚠️ 第一版判据写的是词表（「菜单里没有」），而产品说的是「菜单里没**查到**」，
#         **差一个字**就把一个正确答案判成了「说不清」（形态 E：代理判据两头造假）。
#
# ⇒ 改成数**执行期**这两半都在：目录绑定 + 消费方调用点。
#   ⛔ 仍然是结构判据，不是行为判据 —— 行为那半在 `_dish_catalogue_behaviour` 里，
#      本条只保证「接线还在」，⛔ 不保证「它答得好」。
_cat_calls = sum(calls_of(f, "_catalogue_says_not_a_dish") for f in FILES)
_where = {f: calls_of(f, "_catalogue_says_not_a_dish") for f in FILES}
# 🔴 目录绑定在**生产入口** `smartbi/api/gold_reads.py`，⛔ 不在读路径三文件里。
#    （入口链：Java POST /ai-intents/execute → gold_reads.py → restaurant_intent_service）
#    ⚠️ 我这条判据的第一版去 `restaurant_intent_service.py` 里数，数出 0 ——
#       **本轮第三次「判据找错文件/符号」**（另两次：⑤ 找 `_REALWORLD_EVENT`
#       这个不存在的名字、#1 原判据只看 `restaurant_intent.py`）。
#       ▎判据要钉的是「生产上谁保证它被调用」，那就得去**入口**那一层找。
try:
    with open(os.path.join(ROOT, "smartbi", "api", "gold_reads.py"),
              "r", encoding="utf-8", newline="") as _gr_fh:
        _gr = _gr_fh.read()
    _gr_tree = ast.parse(_gr)
    _cat_scope_bound = sum(
        1 for n in ast.walk(_gr_tree)
        if isinstance(n, ast.Call)
        and getattr(n.func, "attr", getattr(n.func, "id", None)) == "dish_catalogue_scope"
    ) > 0
except (OSError, SyntaxError):
    _cat_scope_bound = False
rec(1, "菜单目录接进 dish 槽位核对",
    _cat_calls > 0 and _cat_scope_bound,
    "消费方调用点 %s（总 %d）+ 生产入口 gold_reads.py 绑目录 dish_catalogue_scope=%s；"
    "行为实测见 _dish_catalogue_behaviour（假菜 2/2 轮报出 8 道目录菜名）"
    % (_where, _cat_calls, _cat_scope_bound))

# ── 2 绝对日期核对「原话里有没有」 ────────────────────────────────────────
# 🔴 2026-08-18 先重测再判(owner 判据: 单轮观测不算数, 复现不了就登记不做)。
#    同一句原话「前天和昨天哪天好」直接调 T3(`_t3_llm_parse`, prefer_high_
#    accuracy=True, MOCK_REST, temperature=0)跑 10 轮(两批各 5), 每轮清
#    4 个缓存(semantic_plan/route/tenant_gate/promoted_routes)。
#    判据(逐字用 owner 的话): LLM 吐出的日期, 在原话里出现过吗? 没出现过而
#    它自己编了一个 ⇒ 复现。10 轮读数: **0/10** 命中 prompt 里写死的示例
#    日期(2026-06-03/2026-06-18)。⇒ **单轮观测未复现, 登记不做**, 详见
#    docs/decisions/2026-08-18-缺口2-绝对日期核对-复测登记.md。
#    ⚠️ 副产品(不在本条判据范围内, 单独记在那份文档里): 10 轮里 2 轮
#    (20%) LLM 把 type 判成 "absolute" 却填了 "前天"/"昨天" 这种非 ISO
#    日期的字面词——被 `_parse_t3_time_range` 的 ISO 正则挡下(不会污染成
#    错误日期, 但会让那一轮解不出时间窗)。这是「类型选择不稳定」, 不是
#    「编日期」, 与 #2 要判的缺口是两件事, 不改这里的判据。
#
# 🔴 2026-08-19 判据订正 —— 原判据**找了两个仓里不存在的函数名**。
#
#    `_absolute_date_in_user_wording` / `_date_backed_by_user_wording`
#    全仓只在**本文件自己**里出现 ⇒ 那条红是「按名字找」造出来的，
#    ⛔ 不是行为读数。（同形我今天已经踩过一次：`_REALWORLD_EVENT`。）
#
#    ⇒ 去 prod 量了行为（`_absolute_date_from_thin_air`，7 条问句 × 2 轮）:
#         阳性对照·原话有日期 ⇒ absolute = **6/6**（这一格真被触到了）
#         阴性对照·只有相对说法 ⇒ absolute = **0/8**
#         🔴 日期数字**原话里没有**的轮次 = **0/14**
#       ⇒ **模型没有编日期。** 这一格在行为上没有复现。
#
# ⇒ 判据改成登记这个事实，⛔ 不去实现一个当前没有证据需要的守卫
#    （撞反目标：⛔ 不为一个观察不到的形状建检测器）。
# ⚠️ 但也⛔ 不标 ✅ —— 现在挡住它的是 **prompt 规则 1**（「你绝对不能计算或
#    输出具体日期」），那是**指令不是闸**；实体侧有 `_verbatim_entity`，
#    日期侧没有对等的核对。模型换一家、prompt 改一版，它随时可能回来。
#    ⇒ 这一格是 `None`（判不了/登记），由行为探针每次给读数。
rec(2, "绝对日期核对「原话里有没有」", None,
    "代码侧**没有**对等于 `_verbatim_entity` 的日期核对（只校验 ISO 格式与区间"
    "合法性）；挡住它的是 prompt 规则 1 = **指令不是闸**。"
    "prod 行为实测（`_absolute_date_from_thin_air`，7 问句 × 2 轮）: "
    "编造的绝对日期 **0/14**，阳性对照 6/6 ⇒ 当前未复现，⛔ 不据此实现守卫")

# ── 3 实体向量检索（门店/菜品模糊匹配） ───────────────────────────────────
_ent = any(imports_module(f, "entity_resolution") for f in FILES)
rec(3, "实体向量检索接进读路径", _ent,
    "三个读路径文件里 import entity_resolution = %s" % _ent)

# ── 4 菜单 + 能力表走检索进 prompt ────────────────────────────────────────
_p = _src("restaurant_intent.py")   # 下游还有别的项在用它
#
# 🔴 2026-08-19 判据订正两次，两次都是我自己造的坑，都留痕:
#
#  ① 原判据 `"只能从这里选择，禁止编造" in _src(...)` 读的是**整份源码文本，
#     注释也算**。同日我改 prompt 时在注释里**引用**了那句旧文案，
#     于是这一项从此**恒为 True** —— 那句话已经不在 prompt 里了，
#     而这道闸永远报「未做」，将来真改成检索也不会翻面。
#     ▎**我亲手把它写成了恒真式。**
#
#  ② 我的第一版修法是「只数字符串字面量」。⛔ **那更糟** ——
#     它会把这一项标成**已做**，而 gap #4 要的是「门店**走检索**」，
#     我只改了措辞，**整份名单照样 `json.dumps` 塞进 prompt**。
#     ▎判据钉在**一句措辞**上，措辞一换它就说做完了。
#
#  ⇒ 判据钉**那件事本身**：prompt 里还在不在把**整份门店名单**序列化进去。
#     这是结构（AST 上的一次 `json.dumps(factual_stores…)`），⛔ 不是文本。
def _dumps_whole_store_list(name: str) -> bool:
    """`_build_t3_prompt` 里还在不在把整份门店名单序列化进 prompt。"""
    for node in ast.walk(_tree(name)):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        if node.name != "_build_t3_prompt":
            continue
        for call in ast.walk(node):
            if not isinstance(call, ast.Call):
                continue
            func = call.func
            if not (isinstance(func, ast.Attribute) and func.attr == "dumps"):
                continue
            for arg in call.args:
                if isinstance(arg, ast.Name) and "store" in arg.id:
                    return True
    return False


_full_store = _dumps_whole_store_list("restaurant_intent.py")
rec(4, "菜单/能力表走检索（⛔ 不全量塞 prompt）", not _full_store,
    "`_build_t3_prompt` 仍把整份门店名单 json.dumps 进 prompt = 全量塞"
    if _full_store else "已改成检索（prompt 里不再序列化整份名单）")

# ── 5 能力表登记「为什么不能」三态 ────────────────────────────────────────
# 🔴 2026-08-18 订正: 原判据找那三句**字面文案**，全判 0 ——
#    只读复测线用更硬的判据（读完整函数体 + 追跨调用点数据流）证明是**假阴性**:
#    结构上有 4 条互不相同的拒答路径，各自独立触发条件、独立措辞来源。
#    原三句字面文案在三个文件里 grep 为 0（**真的不在了**，是被替换掉的旧文案）。
#    ⇒ 判据换成「这四条路径各自存在」，⛔ 不再找措辞。
_five_paths = {
    "无维度 _dimension_gap_advice": any(
        defines(f, "_dimension_gap_advice") for f in FILES),
    "方法未实现 capability_clarification_question": any(
        defines(f, "capability_clarification_question") for f in FILES),
    "无基准 asks_reasonableness": "asks_reasonableness" in _src(
        "restaurant_ops_router.py"),
    "无数据 _UNSUPPORTED_REQUIREMENT_LABELS": (
        "_UNSUPPORTED_REQUIREMENT_LABELS" in _src("restaurant_intent.py")),
}
rec(5, "能力表「为什么不能」多态", all(_five_paths.values()),
    "四条独立拒答路径: %s" % {k: v for k, v in _five_paths.items()})

# ── 6 冲突批量收集 + 一次性重试 ───────────────────────────────────────────
_retry = any(defines(f, n) for f in FILES
             for n in ("_collect_all_conflicts", "_retry_with_conflicts"))
rec(6, "冲突批量收集 + 一次性重试", _retry,
    "找不到 _collect_all_conflicts / _retry_with_conflicts" if not _retry else "已定义")

# ── 7 prompt 加「信息不足必须 clarify + 列候选维度」 ──────────────────────
_clar = ("信息不足" in _p and "clarify" in _p.lower())
rec(7, "prompt 要求信息不足必须 clarify", _clar,
    "prompt 里同时有「信息不足」和 clarify = %s" % _clar)

# ── 8 反问一次问全 + 按钮 + 拼完整句重走 ─────────────────────────────────
# 🔴 2026-08-18 订正（假阴性）: 它**不是独立函数**，是 `_parse_continuation`
#    里的局部变量 concatenated（原句 + 答案片段拼接），随后被喂给
#    T1 关键词 / T2 向量 / T3 LLM 三层入口 —— 那正是「整句重走」。
#    ⇒ 判据换成数那个变量的引用数。
_rewalk_refs = sum(
    1 for n in ast.walk(_tree("restaurant_intent.py"))
    if isinstance(n, ast.Name) and n.id == "concatenated")
rec(8, "反问回答后拼完整句整句重走", _rewalk_refs >= 6,
    "`concatenated`（原句+答案片段）被引用 %d 次" % _rewalk_refs)

# ── 9 dimensions 传进执行器 ───────────────────────────────────────────────
_resolvers = [n.name for n in ast.walk(_tree("restaurant_ops_router.py"))
              if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
              and n.name.startswith("resolve_")]
_with_dims = [r for r in _resolvers
              if has_param("restaurant_ops_router.py", r, "dimensions")]
# 🔴 2026-08-19 **这一格改判「没量到」**（⚠️ 不是降级，是承认判据够不着）。
#
# 判定式原来是 `len(_with_dims) > 0` —— **1/18 也会读成 ✅**。
# 而这一条的判据 2026-08-18 已经**被重写过一次**，重写后的原话是：
#     「不是『让所有 resolver 都收 dimensions』（那是为形参而形参），
#       也不是『答案逐字相同』（后果实测不复现），
#       是**按门店问时那句判断出不出得来**。」
# ▎那是一句**行为**判据，AST 从物理上够不着它。
#
# ⇒ 继续打 ✅ 就是拿一个**量不了这件事**的尺子发合格证。
#   按硬约束 4 的三态，这一格应该是 `None`（没量到）——
#   ⛔ 不折叠进 ✅（那是假绿），⛔ 也不折叠进 🔴（那是冤枉，可能真的接上了）。
# ⚠️ 形参计数**留在证据串里**：它是有用的旁证，只是**不能当判据**。
rec(9, "dimensions 传进执行器", None,
    "⚠️ 没量到: 判据是「按门店问时那句判断出不出得来」= **行为**判据, AST 够不着。"
    "旁证(⛔ 不是判据): %d/%d 个 resolver 收 dimensions: %s"
    " ⇒ 去跑端到端探针，按维度问一遍看那一维在不在答案里"
    % (len(_with_dims), len(_resolvers), sorted(_with_dims)))

# ── 11 飞轮 B：拒答队列 ───────────────────────────────────────────────────
# 🔴 2026-08-18: 飞轮 B 已上线（PR #2834 模块 + #2838 SSE 接线）。
#    它在**自己的模块** refusal_queue.py 里，不在读路径三个文件里 ——
#    原判据只扫那三个文件 ⇒ 假阴性。
#    ⇒ 判据换成「模块存在 + 两个生产入口都接上了」（⛔ 数调用点，不数定义）。
_fb_mod = os.path.exists(os.path.join(R, "refusal_queue.py"))
_fb_wired = {}
for _api in ("gold_reads.py", "chat.py"):
    _rel = os.path.join(ROOT, "smartbi", "api", _api)
    try:
        with open(_rel, "r", encoding="utf-8", newline="") as fh:
            _t = ast.parse(fh.read())
        _fb_wired[_api] = sum(
            1 for n in ast.walk(_t)
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
            and n.func.id == "record_refusal")
    except OSError:
        _fb_wired[_api] = -1
rec(11, "飞轮 B（拒答队列 分类+频次）",
    _fb_mod and all(v > 0 for v in _fb_wired.values()),
    "模块存在=%s；record_refusal 调用点 %s" % (_fb_mod, _fb_wired))

# ── 13 实体命中后替换成库里规范名 + 抬头显示全名 ─────────────────────────
# 🔴 2026-08-18 订正（假阴性）: 它叫 `_canonicalize_store_mention`
#    （router，先精确查 dim_store 再模糊 LIKE，返回**库里的 name**），
#    返回值流入 store_name -> target_label -> 答案标题。
#    ⚠️ 只覆盖**门店**；菜品侧只有布尔判断 `_catalogue_says_not_a_dish`，
#       不返回规范全名 —— 那一半仍是缺口，读数里说清楚。
_norm_store = any(defines(f, "_canonicalize_store_mention") for f in FILES)
_norm_dish = any(
    defines(f, n) for f in FILES
    for n in ("_canonicalize_dish_mention", "_normalize_dish_to_catalogue"))
# 🔴 2026-08-19 **订正这一格的判定式**（⚠️ 不是订正证据，证据一直是对的）:
#    判定式原来只读 `_norm_store`，于是打出 **✅**，
#    而它自己同一行的证据串写着 `菜品=False ⚠️ 菜品侧仍是缺口`。
#    ▎**内容对，标签假** —— 而读账的人看的是那个符号，⛔ 不是证据串。
#    （形态 A⁗ 第 6 例：「结论标签必须由实际值算出来，⛔ 不能无条件打印」。）
#
# ⇒ 判定式改成**两半都要**。半个缺口不是 ✅。
# ⛔ 也不标 `None`：`None` 在本文件里专指「**没量到**」（硬约束 4 的 rc=2 那一态），
#    而这一格量到了 —— 量到的结果就是「一半没做」。
#    把它折进 `None` 会稀释 rc=2 的含义，那是这道闸最值钱的一态。
rec(13, "实体归一成库里规范名", _norm_store and _norm_dish,
    "门店=%s（_canonicalize_store_mention）/ 菜品=%s"
    " ⚠️ 菜品侧仍是缺口（只有布尔判断 `_catalogue_says_not_a_dish`，不返回规范全名）"
    % (_norm_store, _norm_dish))

# ── 14 部分能做时给部分结果 ───────────────────────────────────────────────
# 🔴 2026-08-18 订正（假阴性）: 它叫 `partial_coverage_answer`，
#    定义在 capability_answer.py（不在读路径三文件里），
#    唯一调用点在 restaurant_intent_service.py。
#    ⇒ 判据 = 定义存在 **且** 真的被调用（⛔ 数调用点，形态 B）。
try:
    with open(os.path.join(R, "capability_answer.py"), "r",
              encoding="utf-8", newline="") as fh:
        _cap_defined = "def partial_coverage_answer" in fh.read()
except OSError:
    _cap_defined = False
_partial_calls = sum(calls_of(f, "partial_coverage_answer") for f in FILES)
rec(14, "部分能做时给部分结果", _cap_defined and _partial_calls > 0,
    "partial_coverage_answer 定义=%s / 调用点 %d 处"
    % (_cap_defined, _partial_calls))

# ── 19 计算式指代：先算再核对 ─────────────────────────────────────────────
# 🔴 2026-08-18 订正（假阴性）: 它**不在读路径三文件里**，
#    也不是「两段执行」那个形状 —— 归因线给了四步实测证据:
#      ① plan_dimensions 识别指代 + 维度
#      ② compute_store_attribution 先算出 laggard
#      ③ FactReconciler + 接地闸 在第 7 步之前核对
#      ④ LLM 拿含 laggard 的 FactBook 写决策分析
#    反证也在: 维度没确定时 attribution is False，不会硬算。
#    ⇒ 判据换成扫 synthesis_engine / factbook 里那两个函数。
_calc_fns = {}
for _f in ("factbook.py", "synthesis_engine.py"):
    _rel = os.path.join(ROOT, "smartbi", "agent", _f)
    try:
        with open(_rel, "r", encoding="utf-8", newline="") as fh:
            _txt = fh.read()
        _calc_fns[_f] = [
            n for n in ("compute_store_attribution", "plan_dimensions")
            if ("def " + n) in _txt or (n + "(") in _txt
        ]
    except OSError:
        _calc_fns[_f] = []
rec(19, "计算式指代先算再核对", any(_calc_fns.values()),
    "先算再核对的两半: %s（⚠️ 不是两段执行，是同一趟里先算出 laggard 再核对）"
    % _calc_fns)

# ── 20 答案契约认「因权限而缺」一态 ───────────────────────────────────────
try:
    ac = open(os.path.join(R, "answer_contract.py"), "r",
              encoding="utf-8", newline="").read()
except OSError:
    ac = ""
_perm = ("权限" in ac) or ("price_view" in ac) or ("can_see_money" in ac)
rec(20, "答案契约认「因权限而缺」", _perm,
    "answer_contract.py 里提到权限/价格视角 = %s（文件 %d 字节）" % (_perm, len(ac)))

# ── 10 纠正记账 + 审 Top-50 + 补 feedback/embedding/history 三列 ──────────
# 🔴 2026-08-18 上线（PR #2847）: 生产餐饮流量 100% 走 `log_template_hit`，
#    而 `log_fallback`（唯一原生补两列的写法）**从未被餐饮路径调用过** ——
#    那是结构性结论，不是「没实现」。
#    ⇒ 判据 = 补值那两半都接上了（⛔ 数调用点，不数定义）。
_fa = {}
_fa_log = os.path.join(ROOT, "smartbi", "services", "llm_fallback_logger.py")
try:
    with open(_fa_log, "r", encoding="utf-8", newline="") as fh:
        _fa_src = fh.read()
    _fa["logger 里有 update_history"] = "def update_history" in _fa_src
    _fa["logger 里有 run_capture_with_history"] = (
        "def run_capture_with_history" in _fa_src)
except OSError:
    _fa["logger 读不到"] = False
_fa["service 真的调了 run_capture_with_history"] = (
    calls_of("restaurant_intent_service.py", "run_capture_with_history") > 0)
rec(10, "飞轮 A 输出端接上（三列值 + 晋升积压告警）", all(_fa.values()),
    "%s；service 调用点 %d 处"
    % (_fa, calls_of("restaurant_intent_service.py", "run_capture_with_history")))

# ── 12 拒答原因分「域外」一类，⛔ 不混进飞轮 B ────────────────────────────
# ⇒ 判据 = 飞轮 B 的聚合**在 SQL 里**排除域外（一处判定，⛔ 不 Python 侧再滤）
try:
    with open(os.path.join(R, "refusal_queue.py"), "r",
              encoding="utf-8", newline="") as fh:
        _rq = fh.read()
    _ood = "OUT_OF_DOMAIN" in _rq
except OSError:
    _rq, _ood = "", False
rec(12, "拒答原因分「域外」一类", _ood,
    "refusal_queue.py 里认 OUT_OF_DOMAIN = %s" % _ood)

# ── 15 老板的否定写进反馈通道 ─────────────────────────────────────────────
# 🔴 2026-08-18 第三轮重测, **判据整个换掉**（前两轮的判据都测错了东西）:
#
#   第一轮判据「端点文件存在」        —— 被打回: 那是形态 B, 存在 ≠ 被调用
#   第二轮判据「库里带反馈的行数」    —— 本轮实测证明它**也是错的仪器**:
#       我想知道的 X: 有没有人调用过这个通道
#       第二轮量的 Y: 现在库里还剩几行带 user_feedback 的捕获行
#     捕获表 smart_bi_llm_fallback_log **有保留期**(实测窗口只到
#     2026-07-29)，而访问日志里 2026-07-23 有 2 次**成功写库**的客户端
#     调用 —— 那两行今天已经不在表里了。⇒ 单看 DB 会把「调过」读成
#     「从来没调过」。X ≠ Y。
#
#   本轮判据（三态, 主证据=访问日志: append-only + 轮转 .gz 全留, 覆盖端点
#   上线日 2026-07-23 至今; DB 计数降级为佐证, 不进判据）:
#     ① 静态接线链任一层 = 0            ⇒ 🔴 断在第 N 层
#     ② 客户端来源调用(全历史) = 0      ⇒ 🔴 通道在、从未被调用
#     ③ 排除上线当天(构建期验收)后 = 0  ⇒ 🔴 通道在、上线后无人调  ← 当前
#     ④ 否则                            ⇒ ✅ 有真实调用
#   ⚠️ 第 ③ 步不能省: 实测那 3 次客户端调用**全部在 2026-07-23 20:01~20:06**,
#      夹在前端接通 commit 36bdfedf94(19:55) 和整型修复 9e67818556(20:05)
#      之间, 第一次还是 500(`'up'` 字符串撞 asyncpg 整型检查) —— 那是开发者
#      当场验收, 不是「有人在用」。不排除它, 判据就会被 3 次构建期点击永久
#      钉成 ✅ —— **「端点存在就打勾」换了身衣服**。
#   完整逐条读数用独立探针: `python -m smartbi.scripts._gap15_channel`
#   (需在 prod 上跑; 带阳性/阴性对照 + 变异对照, rc 三态)。
_G15_LOG_GLOBS = ("/www/wwwroot/cretas/python-prod.log",
                  "/www/wwwroot/cretas/python-prod.log-*.gz")
# GAP15_BUILD_DAY 只供**变异对照**: 换成一个没有调用的日期, 「自然使用」必须
# 从 0 变成 3。不变 ⇒ 这个排除根本没在排除, 那个 0 不作数（形态 C″）。
_G15_SHIPPED = os.environ.get("GAP15_BUILD_DAY", "2026-07-23")
_G15_FEEDBACK = "POST /api/smartbi/restaurant/feedback"
_G15_CONTROL = "POST /api/smartbi/gold/restaurant/tiered-answer"
# GAP15_REPO_ROOT 只供**变异对照**（构造「断线」那一态: 硬约束 4 的
# 「第三态平时永远不会自然发生, 不构造就不知道它能不能到达」）。
_REPO = os.environ.get(
    "GAP15_REPO_ROOT",
    os.path.dirname(os.path.dirname(ROOT)))      # backend/python -> backend -> repo


def _g15_static_chain():
    """静态接线链。返回 {层名: 计数 or None}；None = **没量到**（文件不在）。

    ⛔ 「文件在但计数 0」和「文件不在」必须分开 —— prod 的 code/web-admin 是
       2026-04 的**过期副本**（实测: restaurant-synthesis.ts 根本没有，
       RestaurantChatPanel.vue 里 restaurant/feedback 字面量 0 次）。把它当
       0 读, 会得出「前端没接线」这个完全相反的结论（形态 D: 两份会漂）。
       ⇒ 一条链上任一文件缺失 ⇒ 整条链记 None, ⛔ 不记 0。
    """
    chains = {
        "py-endpoint": [
            (os.path.join(ROOT, "smartbi", "api", "restaurant_feedback.py"),
             ('@router.post("/feedback")', "def post_restaurant_feedback")),
            (os.path.join(ROOT, "main.py"),
             ("restaurant_feedback.router",)),
        ],
        "web-admin": [
            (os.path.join(_REPO, "web-admin", "src", "api", "smartbi",
                          "restaurant-synthesis.ts"),
             ("/api/smartbi/restaurant/feedback",
              "export async function sendRestaurantAnswerFeedback")),
            (os.path.join(_REPO, "web-admin", "src", "views", "smart-bi",
                          "components", "chat", "RestaurantChatPanel.vue"),
             ("sendRestaurantAnswerFeedback", 'sendFeedback(turn, -1)')),
        ],
        "mobile-rest-ai": [
            (os.path.join(_REPO, "mobile-rest-ai", "src", "api.ts"),
             ("/api/smartbi/restaurant/feedback",
              "export async function sendAnswerFeedback")),
            (os.path.join(_REPO, "mobile-rest-ai", "src", "App.vue"),
             ("sendAnswerFeedback", "sendFeedback(message, -1)")),
        ],
    }
    out, detail = {}, {}
    for chain, layers in chains.items():
        per_layer = []
        for path, needles in layers:
            try:
                with open(path, "r", encoding="utf-8", newline="") as fh:
                    txt = fh.read()
            except OSError:
                per_layer.append(None)          # 这一层的源码不在这棵树里
                continue
            per_layer.append(sum(txt.count(n) for n in needles))
        if any(v is None for v in per_layer):
            # 🔴 这条链上**有文件整个不在** ⇒ 树不完整/是过期副本, 整链记 None。
            #    ⛔ 不能只把缺的那层记 None 而让存在的那层的 0 去当「断线」——
            #    prod 的 code/web-admin 正是这个长相（sibling 文件没有 +
            #    留下的那份是 2026-04 的旧版, 锚点 0 次）。
            out[chain] = None
        else:
            # 全部文件都在 ⇒ 这条链可判。任一层 0 命中 = **真的断在那一层**。
            out[chain] = 0 if any(v == 0 for v in per_layer) else sum(per_layer)
        detail[chain] = per_layer
    return out, detail


def _g15_scan_logs():
    """扫 prod 访问日志。⛔ 无 head/tail。返回 None = 不在 prod 上, 没量到。"""
    import gzip as _gz
    import glob as _glob
    import re as _re
    ts_re = _re.compile(r"^(\d{4}-\d{2}-\d{2}) \d{2}:\d{2}:\d{2}")
    ip_re = _re.compile(r"INFO:\s+([0-9a-fA-F:.]+):\d+\s+-")
    files = []
    for pat in _G15_LOG_GLOBS:
        files.extend(sorted(_glob.glob(pat)))
    if not files:
        return None
    calls, control = [], 0
    for path in files:
        opener = (lambda p: _gz.open(p, "rt", encoding="utf-8", errors="replace")) \
            if path.endswith(".gz") else \
            (lambda p: open(p, "r", encoding="utf-8", errors="replace"))
        last_day = "?"
        try:
            with opener(path) as fh:
                for line in fh:
                    m = ts_re.match(line)
                    if m:
                        last_day = m.group(1)
                    if _G15_FEEDBACK in line:
                        mi = ip_re.search(line)
                        ip = mi.group(1) if mi else "?"
                        local = ip in ("127.0.0.1", "::1") or ip.startswith(
                            ("172.16.", "172.17.", "172.18."))
                        calls.append({"day": last_day, "ip": ip, "local": local})
                    elif _G15_CONTROL in line:
                        control += 1
        except OSError:
            return None
    return {"calls": calls, "control": control, "files": len(files)}


_g15_chain, _g15_layers = _g15_static_chain()
_g15_log = _g15_scan_logs()
_g15_broken = [k for k, v in _g15_chain.items() if v == 0]
_g15_measured_chains = {k: v for k, v in _g15_chain.items() if v is not None}

if _g15_broken:
    # ⚠️ 这一态排在最前面: 接线断了就是断了, **不需要**运行时证据来确认。
    #    排在「没量到」后面的话, 一个断线的树在非 prod 上会读成 ⚠️ 而不是 🔴。
    _g15_ok = False
    _g15_ev = ("🔴 通道不存在/接线断了 —— 断在: %s（该链所有文件都在, 但某一层"
               "锚点 0 次）; 逐层计数: %s" % (_g15_broken, _g15_layers))
elif _g15_log is None or _g15_log["control"] <= 0 or not _g15_measured_chains:
    # 没量到 ≠ 没问题（硬约束 4 的 rc=2 那一态）
    _g15_ok = None
    _g15_ev = ("⚠️ 没量到: 访问日志%s; 同期问答阳性对照=%s; 可测接线链=%s "
               "⇒ 本格读数作废, 去 prod 上跑 `python -m smartbi.scripts."
               "_gap15_channel`"
               % ("不在(不在 prod 上)" if _g15_log is None else "在",
                  "N/A" if _g15_log is None else _g15_log["control"],
                  sorted(_g15_measured_chains)))
else:
    _client = [c for c in _g15_log["calls"] if not c["local"]]
    _organic = [c for c in _client if c["day"] != _G15_SHIPPED]
    _g15_ok = bool(_organic)
    _g15_ev = ("可测接线链 %s 无断点(None=树里没有那份源码, ⛔ 不读成 0); "
               "访问日志 %d 个文件, 同期问答 %d 次(阳性对照); "
               "feedback 调用 %d 次 = 客户端 %d + 本机诊断 %d; "
               "排除上线当天(%s, 构建期验收)后自然使用 **%d 次** ⇒ %s"
               % (_g15_chain, _g15_log["files"], _g15_log["control"],
                  len(_g15_log["calls"]), len(_client),
                  len(_g15_log["calls"]) - len(_client), _G15_SHIPPED,
                  len(_organic),
                  "✅ 有真实调用" if _organic else
                  ("🔴 通道在、上线后无人调（%d 次客户端调用全部在上线当天）"
                   % len(_client) if _client else "🔴 通道在、从未被客户端调用")))
rec(15, "否定写进反馈通道（真实调用证据, 非端点存在）", _g15_ok, _g15_ev)

# ── 16 实体模糊命中走确认式反问 ───────────────────────────────────────────
_amb = any(defines(f, n) for f in FILES
           for n in ("partial_store_alias_question", "_ambiguous_entity_question",
                     "_confirm_entity_question"))
rec(16, "实体模糊命中走确认式反问", _amb,
    "找不到 partial_store_alias_question / _ambiguous_entity_question"
    if not _amb else "已定义")

# ── 17 空结果只报「最后记录时间 + 请排查」 ───────────────────────────────
# 🔴 2026-08-18 上线（PR #2842）: 18 个 resolver 里 14 个有 no-data 分支，
#    只改了 3 个（同一张 agg_daily 表、同一种失败语义），其余逐条登记「不适用」。
_ew = defines("restaurant_ops_router.py", "_empty_window_last_record_date")
_ew_calls = calls_of("restaurant_ops_router.py", "_empty_window_last_record_date")
rec(17, "空结果只报最后记录时间", _ew and _ew_calls >= 3,
    "_empty_window_last_record_date 定义=%s / 调用点 %d 处（期望 ≥3）"
    % (_ew, _ew_calls))

# ── 18 归因分层下钻（七步链）─────────────────────────────────────────────
# 🟡 2026-08-18 ②⑥⑦ 已上线；⑤ 判据设计完成但**裁定不上线**（无触发样本）；
#    ③ 的外部客流对照无数据源。
#
# 🔴 2026-08-18 第三棒订正：这一条原本写死「只做了第②步 / ③⑤⑥⑦未做」，
#    而 ⑥⑦ 当时**已经在 `synthesize()` 里各有 2 处真实调用点并且出现在
#    老板看到的正文里**。那句话被下一棒当成结论引用，直接指错了方向。
#    ⇒ 判据改成**逐步去数**，⛔ 不再手写一句会过期的汇总
#      （硬约束：结论标签必须由实际值算出来，不能无条件打印）。
_attr_steps = {}
try:
    with open(os.path.join(ROOT, "smartbi", "agent", "synthesis_engine.py"),
              "r", encoding="utf-8", newline="") as fh:
        _se = fh.read()
    # ② 量价分解够得着是非决策问句
    _attr_steps["②"] = "_is_yes_no_decision" in _se
    # ⑥ 排查方向 / ⑦ 排除法收口 —— 数**真实调用点**, ⛔ 不是「定义在不在」
    _attr_tree = ast.parse(_se)
    _calls = [n for n in ast.walk(_attr_tree) if isinstance(n, ast.Call)]
    _n6 = sum(1 for n in _calls
              if getattr(n.func, "attr", getattr(n.func, "id", None))
              == "_append_investigation_directions")
    _attr_steps["⑥"] = _n6 >= 1
    _attr_steps["⑦"] = "_INVESTIGATION_LAST_RESORT" in _se and _n6 >= 1
    # ⑤ 反问现实事件
    #
    # 🔴 2026-08-19 订正（**假阴性**，第二次同形）: 这里原来找的是
    #    `_REALWORLD_EVENT` —— 一个**仓里根本不存在的名字**，于是恒为 False，
    #    再配上下面那句手写的「⑤ 判据设计完成但裁定不上线」，
    #    把一个**已经上线**的功能读成了「没做」。
    #    ▎与本条 2026-08-18 那次订正是同一个形状：判据写死了一句会过期的话。
    #    ⚠️ 成因：写那条判据的那一棒在 **prod** 上量，而 prod 当时 = `origin/main`，
    #       落后本地 main **46 个 commit**（GitHub suspended 期间的离线汇合）。
    #       ▎「⑤ 做没做」的答案**取决于你在问哪棵树**。
    #
    # 真实实现（commit 19a64ad335，2026-08-19 00:08 已部署 prod）：
    #   ⑤a 现实事件维度  synthesis_engine `mall_activity` / `nearby_event`
    #   ⑤b 具名反问触发线 factbook.traffic_drop_warrants_asking（由数据算，⛔ 不无条件追加）
    # 🔴 判据量**结构**（那张表的 dict 键），⛔ 不量文本。
    #    第一版写的是 `"mall_activity" in _se` —— 而这个串在文件里出现 5 处，
    #    变异对照当场抓到：把**那张表**里的键改名，判据纹丝不动（形态 C⁸）。
    # ⚠️ `_MISSING_DIMENSION_TERMS` 是 **AnnAssign**（带 `: Dict[...]` 注解），
    #    只扫 `ast.Assign` 会漏掉它 —— 本轮实测漏过一次。
    _ext_keys = set()
    for _n in ast.walk(_attr_tree):
        _tgt = None
        if isinstance(_n, ast.AnnAssign) and isinstance(_n.target, ast.Name):
            _tgt = _n.target.id
        elif isinstance(_n, ast.Assign):
            _tgt = next((t.id for t in _n.targets if isinstance(t, ast.Name)), None)
        if _tgt == "_MISSING_DIMENSION_TERMS" and isinstance(_n.value, ast.Dict):
            _ext_keys = {k.value for k in _n.value.keys if isinstance(k, ast.Constant)}
    _attr_steps["⑤a"] = {"mall_activity", "nearby_event"} <= _ext_keys
    try:
        with open(os.path.join(ROOT, "smartbi", "agent", "factbook.py"),
                  "r", encoding="utf-8", newline="") as _fb_fh:
            _fb = _fb_fh.read()
        _fb_tree = ast.parse(_fb)
        _attr_steps["⑤b"] = any(
            isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
            and n.name == "traffic_drop_warrants_asking"
            for n in ast.walk(_fb_tree))
    except (OSError, SyntaxError):
        _attr_steps["⑤b"] = False
except (OSError, SyntaxError):
    _attr_steps = {}
_attr_done = [k for k, v in _attr_steps.items() if v]
_attr_todo = [k for k, v in _attr_steps.items() if not v]
# 🔴 2026-08-19 订正（第三处同形）: 通过条件原来只看 `②` 和 `⑥`，
#    而 detail 里却在报 ⑤a/⑤b/⑦。⇒ **它的 ✅ 不覆盖它自己报的东西** ——
#    变异对照当场抓到：把 `traffic_drop_warrants_asking` 整个函数名改掉，
#    detail 里 ⑤b 翻成「未做」，而那一行**仍然是 ✅**。
#    ▎与本条前两次订正同源：结论标签必须由实际值算出来。
#    ⇒ 通过条件改成 `all(_attr_steps.values())` —— 这张字典报什么，就守什么。
#
# ⚠️ ③ 的**外部客流对照**（百度地图之类）**不在** `_attr_steps` 里，
#    因为它缺的是数据源不是代码（provider key 未配）。
#    ⇒ 它在下面被**单独列为「未纳入判据」**，⛔ 不混进 ✅ 里让人误读成做完了。
rec(18, "归因七步链",
    bool(_attr_steps) and all(_attr_steps.values()),
    "已上线=%s 未做=%s（⑥调用点 %d 处）；"
    "⚠️ 未纳入本判据: ③ 外部客流对照（缺数据源, provider key 未配, ⛔ 不是代码没写）"
    % ("、".join(_attr_done) or "<无>", "、".join(_attr_todo) or "<无>",
       locals().get("_n6", -1)))

# ── 21 不支持的指标给最接近替代 ───────────────────────────────────────────
# 🔴 2026-08-18 上线（PR #2841）: 「接近」不手写 —— 声明的是「这个量在定义式里
#    由哪几个已登记指标构成」，能不能给由 capability_answer 逐租户查库回答。
#    7 项里 2 项给替代、5 项实测算不出来就不给。
try:
    with open(os.path.join(R, "capability_answer.py"), "r",
              encoding="utf-8", newline="") as fh:
        _ca = fh.read()
    _alt = "眼下最接近的是" in _ca
except OSError:
    _alt = False
rec(21, "不支持的指标给最接近替代", _alt,
    "capability_answer 里有「眼下最接近的是」= %s" % _alt)


# ── 22 反问最多两轮 + 时间给按钮 ─────────────────────────────────────────
_btn = any(("clarification_options" in _src(f)) for f in FILES)
_two = any(("反问" in _src(f) and "两轮" in _src(f)) for f in FILES)
rec(22, "反问最多两轮 + 时间给按钮", _btn and _two,
    "clarification_options 存在=%s / 「两轮」上限存在=%s" % (_btn, _two))


def main() -> int:
    print("=" * 78)
    print("阳性/阴性对照（仪器活着吗）")
    print("=" * 78)
    bad = 0
    for name, ok, ev in CONTROL:
        print("  %s %-28s %s" % ("✅" if ok else "🔴", name, ev))
        if not ok:
            bad += 1
    _print_verdict_vs_evidence_selfcheck()
    if bad:
        print("\nrc=2 仪器对照没过 —— 本次读数作废")
        return 2

    print()
    print("=" * 78)
    print("22 项缺口 · 逐条实测（只覆盖能机械判定的那些）")
    print("=" * 78)
    done = 0
    unmeasured = 0
    # ok is None = 「没量到」——⛔ 不折叠进 🔴（硬约束 4: 三态, 「没量到」
    # 被折叠进「有问题」或「没问题」都会让一个瞎了的仪器安静地天天出读数）。
    for no, title, ok, ev in sorted(ROWS, key=lambda r: r[0]):
        mark = "⚠️" if ok is None else ("✅" if ok else "🔴")
        print("  %2d %s %-32s %s" % (no, mark, title, ev))
        if ok is None:
            unmeasured += 1
        else:
            done += bool(ok)
    print("\n  机械判定覆盖 %d 项，其中 ✅ %d / 🔴 %d / ⚠️ 没量到 %d"
          % (len(ROWS), done, len(ROWS) - done - unmeasured, unmeasured))
    print("\n  ⚠️ 未覆盖（判不了，需要跑行为或人读）:")
    covered = {r[0] for r in ROWS}
    for no in range(1, 23):
        if no not in covered:
            print("      %2d" % no)
    if unmeasured:
        print("\nrc=2 有 %d 格没量到 —— 那些格的读数作废（⛔ 不当成 🔴 也不当成 ✅）"
              % unmeasured)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
