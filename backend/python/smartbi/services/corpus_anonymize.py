"""跨租户共享训练语料脱敏 (shared-model anonymization).

当一个真实租户授权 ``consent_scope='shared_anon'`` (跨租户共享模型) 时, 它的
(input_text → teacher_output) 对在进入**共享**训练集前必须经过本模块脱敏。

数据主权承诺 (pitch s4d):
  - 'private'      → 单租户私有模型: 原始数据直接用, **不经本模块**。
  - 'shared_anon'  → 跨租户共享模型: **必经本模块** —— 数值分桶 + 实体伪名化 +
                     保留分析形状 (按业态桶在导出层已分好)。

🔒 铁律 (no-fake-data / 不破坏分析意义):
  - **保留** 百分比 / 比率 / 占比 (``%`` / ``x.x倍`` / ``占比`` 等) —— 模型要学
    的"形状"(趋势/结构/相对关系)必须原样保留。
  - **只分桶绝对金额** (¥ / 万元 / 元 / 绝对数量) → 数量级区间 (如 "¥10-50万"),
    抹掉租户的精确财务数字, 但保留量级让模式仍可学。
  - **伪名化租户专属实体名** (门店/菜品/供应商/工厂名) → 一致占位符
    (门店A/菜品B/供应商C); 同一样本内同名映射同一占位符。
  - 输出仍是合法的分析教材: 同样的"X 比 Y 高 Z%, 集中在前 N 名"模式, 只是把
    精确值换成区间、把租户名换成占位符。

纯函数, 无 I/O, 可单测。
"""
from __future__ import annotations

import re
from typing import Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# 1. 绝对金额分桶 (value bucketing)
# ---------------------------------------------------------------------------
#
# 把"¥1234567" / "123.4万元" / "5000元" 这类**绝对金额**替换成数量级区间。
# 百分比 / 倍数 / 占比**不动** (它们是相对量, 是要学的形状)。

# 量级区间梯度 (单位: 元)。低→高, 第一个 >= 命中即用其 label。
_AMOUNT_BUCKETS: List[Tuple[float, str]] = [
    (1_000,         "¥1千以内"),
    (10_000,        "¥1千-1万"),
    (100_000,       "¥1-10万"),
    (500_000,       "¥10-50万"),
    (1_000_000,     "¥50-100万"),
    (5_000_000,     "¥100-500万"),
    (10_000_000,    "¥500-1000万"),
    (50_000_000,    "¥1000-5000万"),
    (100_000_000,   "¥5000万-1亿"),
]
_AMOUNT_BUCKET_TOP = "¥1亿以上"


def _bucket_amount(value_yuan: float) -> str:
    """绝对金额 (元) → 量级区间 label。"""
    v = abs(value_yuan)
    for ceiling, label in _AMOUNT_BUCKETS:
        if v < ceiling:
            return label
    return _AMOUNT_BUCKET_TOP


# 匹配带货币/金额语义的数字。捕获: 前缀符号(¥/￥)、数字、可选单位(万/亿/元/万元/亿元)。
# 关键: 不匹配纯百分比 (后面跟 % 的) 和纯倍数 (后面跟 倍)。
#
# 模式说明:
#   (?:¥|￥)?          可选货币符
#   ([0-9][0-9,]*\.?[0-9]*)  数字 (允许千分位逗号 + 小数)
#   \s*(亿元|万元|亿|万|元)?  可选金额单位
# 仅在 (有货币符) 或 (有金额单位) 时才认定为"绝对金额" → 避免误伤裸数字/占比。
_AMOUNT_RE = re.compile(
    r"(?P<sym>¥|￥)?\s*"
    r"(?P<num>[0-9][0-9,]*(?:\.[0-9]+)?)"
    r"\s*(?P<unit>亿元|万元|亿|万|元)?"
)

_UNIT_MULTIPLIER = {
    "亿元": 100_000_000.0,
    "亿":   100_000_000.0,
    "万元": 10_000.0,
    "万":   10_000.0,
    "元":   1.0,
}


def _bucket_amounts_in_text(text: str) -> str:
    """把文本里的绝对金额替换为量级区间, 保留百分比/倍数等相对量。"""
    if not text:
        return text

    # 先标记出"%"和"倍"前的数字区段, 避免误伤。我们采用 finditer + 逐段判定:
    # 对每个 _AMOUNT_RE 匹配, 仅当它带货币符或金额单位时才算绝对金额。
    out: List[str] = []
    last = 0
    for m in _AMOUNT_RE.finditer(text):
        sym = m.group("sym")
        unit = m.group("unit")
        # 紧跟其后的字符: 若是 % 或 倍/折, 说明这是相对量, 不动。
        tail = text[m.end():m.end() + 1]
        is_relative_follow = tail in ("%", "％", "倍", "折")
        # 只有"带货币符"或"带金额单位"且非相对量后缀, 才是绝对金额。
        if (sym or unit) and not is_relative_follow:
            num_raw = m.group("num").replace(",", "")
            try:
                base = float(num_raw)
            except ValueError:
                continue
            mult = _UNIT_MULTIPLIER.get(unit or "", 1.0)
            yuan = base * mult
            out.append(text[last:m.start()])
            out.append(_bucket_amount(yuan))
            last = m.end()
    out.append(text[last:])
    return "".join(out)


# ---------------------------------------------------------------------------
# 2. 实体名伪名化 (label pseudonymization)
# ---------------------------------------------------------------------------
#
# 把租户专属实体名 (门店/菜品/供应商/工厂等) 替换成一致的占位符。
# 同一样本内同名映射同一占位符 (门店A/门店B/...), 跨样本不保证一致 (无需且不应)。

# 实体类别 → (识别正则, 占位符前缀)。
# 识别"〈名字〉+类别词"或"类别词:〈名字〉"等中文实体命名习惯。
# 我们捕获实体**修饰名**部分, 用占位符替换该名, 保留类别词让句子仍通顺。
_ENTITY_PATTERNS: List[Tuple[str, "re.Pattern[str]"]] = [
    # 公司 (2026-11-03 复审第二轮 N5): "XX有限公司" / "XX股份有限公司" / "XX集团" —— 只认这些后缀,
    # 光一个「公司」太常见 (「我们公司」「公司客户」), 不认。放最前: 先整段换掉, 后面的供应商 / 客户规则不再拆它。
    ("公司", re.compile(r"(?P<name>[一-龥A-Za-z0-9]{1,12}?)(?P<kind>股份有限公司|有限责任公司|有限公司|集团)")),
    # 供应商 / 供货方 (N5): "供应商：XX" / "供货方：XX" / "供应商「XX」" (只认显式分隔)
    ("供应商", re.compile(r"(?P<kind>供应商|供货商|供货方)(?:名称|名)?\s*[:：]\s*(?P<name>[一-龥A-Za-z0-9]{2,12})")),
    ("供应商", re.compile(r"(?P<kind>供应商|供货商|供货方)\s*[「“\"『](?P<name>[^」”\"』]{1,12})[」”\"』]")),
    # 门店 / 店: "XX店" / "XX门店" / "XX分店"
    ("门店", re.compile(r"(?P<name>[一-龥A-Za-z0-9]{1,12}?)(?P<kind>门店|分店|店)")),
    # 供应商: "XX供应商" / "XX供货商"
    ("供应商", re.compile(r"(?P<name>[一-龥A-Za-z0-9]{1,12}?)(?P<kind>供应商|供货商)")),
    # 菜品 / 产品: "XX菜品" / "XX产品" (放在门店之后, 避免吞并)
    ("菜品", re.compile(r"(?P<name>[一-龥A-Za-z0-9]{1,12}?)(?P<kind>菜品|产品)")),
    # 工厂: "XX工厂"
    ("工厂", re.compile(r"(?P<name>[一-龥A-Za-z0-9]{1,12}?)(?P<kind>工厂)")),
    # 客户 (Steve 2026-10-24): "XX客户" 与 "客户：XX" / "客户「XX」" (只认显式分隔, 避免吞掉
    # "客户数量增长" 这类正文)。
    ("客户", re.compile(r"(?P<name>[一-龥A-Za-z0-9]{1,12}?)(?P<kind>客户)")),
    ("客户", re.compile(r"(?P<kind>客户)(?:名称|名)?\s*[:：]\s*(?P<name>[一-龥A-Za-z0-9]{2,12})")),
    ("客户", re.compile(r"(?P<kind>客户)\s*[「“\"『](?P<name>[^」”\"』]{1,12})[」”\"』]")),
]

# "XX客户" 里 XX 以这些泛称结尾时不是客户名 (大客户、新客户、重点客户 ...)。
_GENERIC_CUSTOMER_WORDS = (
    "大", "新", "老", "重点", "核心", "主要", "所有", "全部", "各", "该", "这个", "这些", "那个",
    "某", "每个", "每位", "潜在", "流失", "活跃", "优质", "头部", "散", "目标", "意向", "终端",
    "零售", "批发", "经销", "直营", "线上", "线下", "个人", "企业", "长期", "合作", "VIP", "vip",
)

# 占位符字母序列。
_PLACEHOLDER_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

# 「名字」前面常见的虚词 / 指代 / 动词 (2026-10-29 登记的脱敏缺陷: 正则从这一串汉字的最左边起算,
# 把「按产品明细」的「按」、「分析以下工厂数据」的「分析以下」当成了名字, 每条未授权问答都被改坏)。
# 从名字开头逐个剥掉; 剥完不剩 2 个字的就不是名字, 原样保留。剥掉的部分照原样留在占位符前面。
_LEADING_FUNCTION_WORDS = (
    "分析", "统计", "查看", "查询", "对比", "比较", "汇总", "列出", "展示", "显示", "计算", "根据",
    "按照", "按", "针对", "关于", "对于", "对", "以下", "以上", "下列", "上述", "如下", "其中",
    "所有", "全部", "各个", "各", "每个", "每", "该", "此", "这个", "这些", "这", "那个", "那些", "那",
    "本", "我们的", "我们", "我的", "我", "贵", "其他", "其它", "同类", "相关", "主要", "重点",
    "哪个", "哪些", "哪", "从", "在", "把", "给", "向", "为", "是",
)
_NAME_SEPARATORS = ("的", "和", "与", "及", "或", "、")
# 「和」常常就是名字的一部分 (和盛、大东和盛达): 切在「和」上时把它前面最多这么多个字也算进名字
# (N5: 「大东和盛达工厂」不能变成「大东和工厂A」)。代价: 「上个月和盛牛业工厂」会多换掉「个月」两个字。
_HE_LOOKBACK = 3
# 名字连着的汉字 / 字母数字超过正则的 12 个字时, 往外延伸到整串 (N5: 长名字不能把开头留在原文里),
# 最多延伸到这么长 —— 再长就是一整句话了。
_MAX_NAME_RUN = 30
_NAME_CHAR_RE = re.compile(r"[一-龥A-Za-z0-9]")
# 已经换好的占位符 (公司A / 供应商B / 门店12): 后面的规则不再把它当名字换一遍
# 数量说法不是名字: 「两个门店」「三家工厂」「几个产品」「12 个客户」
_QUANTITY_RE = re.compile(r"[0-9一二三四五六七八九十百千两几多每某]+[个家间座所位名条种类批款]?")
_PLACEHOLDER_RE = re.compile(r"(公司|供应商|门店|产品|菜品|工厂|客户)([A-Z]|[0-9]+)")
# 剥完之后整个剩下的就是泛称时也不是名字 (「其他产品」「同类工厂」)。
_GENERIC_NAME_WORDS = ("产品", "菜品", "工厂", "门店", "客户", "供应商", "单品", "品类", "类别")


def _strip_leading_function_words(name: str) -> tuple:
    """``(prefix, rest, ambiguous)``: 名字开头的虚词剥到 ``prefix`` 里。

    ``ambiguous``: 剥掉的是可能属于名字本身的单字 (「和盛工厂」的「和」、「本味工厂」的「本」、
    「向阳工厂」的「向」), 或分隔符前面什么都没有 —— 这种情况调用方把整段当名字换掉
    (2026-11-03 复审 N5: 只换剩下的半个名字会把名字的一部分留在原文里)。"""
    prefix = ""
    ambiguous = False
    # 「XX的YY产品」「本工厂和其他产品」: 名字只可能在最后一个「的 / 和 / 与 / 及 / 或 / 、」之后;
    # 分隔符前面不到 2 个字 (「和盛」) 时分不清它是不是名字的一部分
    cut = max(name.rfind(sep) for sep in _NAME_SEPARATORS)
    if cut >= 0:
        if cut < 2:
            ambiguous = True
        head, tail = name[:cut + 1], name[cut + 1:]
        if name[cut] == "和" and not ambiguous and _names_something(tail):
            # 「和」可能是名字里的字: 把它前面最多 _HE_LOOKBACK 个字一起算进名字 (N5)
            keep = max(0, cut - _HE_LOOKBACK)
            head, tail = name[:keep], name[keep:]
        prefix, name = head, tail
    changed = True
    while changed and name:
        changed = False
        for w in _LEADING_FUNCTION_WORDS:
            if name.startswith(w):
                if len(w) == 1:
                    ambiguous = True
                prefix += w
                name = name[len(w):]
                changed = True
                break
    return prefix, name, ambiguous


def _names_something(tail: str) -> bool:
    """``tail`` with its leading function words stripped is a possible name (not empty, not generic)."""
    changed = True
    while changed and tail:
        changed = False
        for w in _LEADING_FUNCTION_WORDS:
            if tail.startswith(w):
                tail = tail[len(w):]
                changed = True
                break
    return bool(tail) and tail not in _GENERIC_NAME_WORDS and not _QUANTITY_RE.fullmatch(tail)


def _sub_whole_names(pat: "re.Pattern[str]", repl, text: str) -> str:
    """``pat.sub`` that hands ``repl`` the WHOLE name: when the regex's name group (at most 12
    characters) starts or ends in the middle of a run of name characters, the run is taken in up
    to ``_MAX_NAME_RUN`` characters (N5: 「华东区冷链物流配送中心第三分拣加工工厂」 must not keep
    「华东区冷链」). ``repl(match, lead, trail)`` returns the text for ``lead + match + trail``."""
    out: List[str] = []
    last = 0
    for m in pat.finditer(text):
        start, end = m.start(), m.end()
        if m.start("name") == start:          # name before the kind word: extend leftwards
            while (start > last and m.end("name") - start < _MAX_NAME_RUN
                   and _NAME_CHAR_RE.match(text[start - 1])):
                start -= 1
        elif m.end("name") == end:            # kind word before the name: extend rightwards
            while end < len(text) and end - m.start("name") < _MAX_NAME_RUN and _NAME_CHAR_RE.match(text[end]):
                end += 1
        out.append(text[last:start])
        out.append(repl(m, text[start:m.start()], text[m.end():end]))
        last = end
    out.append(text[last:])
    return "".join(out)


def _pseudonymize_entities(text: str, mapping: Dict[str, str]) -> str:
    """把租户专属实体名替换成占位符, 同一样本内 ``mapping`` 复用保证一致。

    ``mapping`` 在调用方跨 input_text / teacher_output 共享 → 同一实体在两段里
    映射同一占位符。占位符沿用原文的类别词 (「XX产品」→「产品A」, 不再改成「菜品A」:
    工厂的样本里不该凭空出现餐饮的词)。
    """
    if not text:
        return text

    # 每个类别独立计数器 (门店A/门店B, 供应商A/供应商B)。从已有 mapping 推断。
    def _next_placeholder(label: str) -> str:
        used = sum(1 for k in mapping if k.startswith(f"{label}:"))
        if used < len(_PLACEHOLDER_LETTERS):
            return f"{label}{_PLACEHOLDER_LETTERS[used]}"
        return f"{label}{used + 1}"

    result = text
    for kind, pat in _ENTITY_PATTERNS:
        def _repl(m: "re.Match[str]", lead: str, trail: str) -> str:
            original = lead + m.group(0) + trail
            raw_name = lead + (m.group("name") or "") + trail
            if not raw_name or _PLACEHOLDER_RE.fullmatch(raw_name):
                return original
            prefix, name, ambiguous = _strip_leading_function_words(raw_name)
            # 剥完不剩东西 (「按产品」「分析以下工厂」「本工厂」「其他产品」) → 全是虚词, 不是名字
            if not name or name in _GENERIC_NAME_WORDS or _QUANTITY_RE.fullmatch(name):
                return original
            # 剥掉的可能是名字的一部分, 或剩下不到两个字 → 整段当名字换掉, 不留半个名字 (N5)
            if ambiguous or len(name) < 2:
                prefix, name = "", raw_name
            # "大客户 / 新客户 / 前十大客户" 是泛称不是名字 → 不动。
            if kind == "客户" and name.endswith(_GENERIC_CUSTOMER_WORDS):
                return original
            # 占位符的类别词跟原文走: 产品就是产品, 菜品才是菜品
            label = m.group("kind") if m.group("kind") in ("产品", "菜品") else kind
            key = f"{label}:{name}"
            if key not in mapping:
                mapping[key] = _next_placeholder(label)
            return prefix + mapping[key]
        result = _sub_whole_names(pat, _repl, result)
    return result


# ---------------------------------------------------------------------------
# 3. 公共入口
# ---------------------------------------------------------------------------


def anonymize_for_shared(
    input_text: str,
    teacher_output: str,
    business_type: Optional[str] = None,
) -> Tuple[str, str]:
    """脱敏一对 (input_text, teacher_output) 供**跨租户共享**训练集使用。

    流程 (顺序重要: 先伪名化实体, 再分桶金额):
      1. 实体伪名化 —— 门店/菜品/供应商/工厂专属名 → 占位符 (样本内一致)。
      2. 绝对金额分桶 —— ¥/万元/元 等绝对数值 → 量级区间; **保留** %/倍/占比。

    保留分析形状: 趋势、结构、相对关系 (百分比/倍数/排名) 原样保留, 只抹掉
    精确财务数字和租户身份。

    Args:
        input_text:     原始输入文本。
        teacher_output: teacher 模型原始输出。
        business_type:  业态 (restaurant/factory/...); 当前仅作签名占位, 业态桶
                        在导出层已分好, 本函数对所有业态行为一致。

    Returns:
        (anon_input, anon_output) —— 脱敏后的两段。纯函数, 无副作用。
    """
    # 跨两段共享的实体映射 → 同一实体名在 input / output 里映射同一占位符。
    shared_mapping: Dict[str, str] = {}

    anon_in = _pseudonymize_entities(input_text or "", shared_mapping)
    anon_out = _pseudonymize_entities(teacher_output or "", shared_mapping)

    anon_in = _bucket_amounts_in_text(anon_in)
    anon_out = _bucket_amounts_in_text(anon_out)

    return anon_in, anon_out


def desensitize_for_training(
    input_text: str,
    teacher_output: str,
) -> Tuple[str, str]:
    """Training-sample desensitization (Steve 2026-10-24) for un-authorized
    factories' Q&A and for shared exports.

    Names become generic placeholders (门店A / 客户A ..., consistent across the
    pair), phone / ID / bank numbers are removed, and then EVERY number becomes
    ``<数值>`` — revenue, cost, profit, margins and other ratios, output,
    inventory. Only the question form and the reasoning remain: operating
    figures such as a gross margin are business secrets and the general model
    does not need them. (``anonymize_for_shared`` keeps percentages; it is no
    longer used for training samples.)
    """
    from smartbi.services.training_consent import mask_numbers

    shared_mapping: Dict[str, str] = {}
    anon_in = _pseudonymize_entities(input_text or "", shared_mapping)
    anon_out = _pseudonymize_entities(teacher_output or "", shared_mapping)
    return mask_numbers(anon_in) or "", mask_numbers(anon_out) or ""
