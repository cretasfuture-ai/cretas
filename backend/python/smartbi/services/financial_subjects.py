"""科目字典 — 财务看板唯一的「这一行是什么科目」判定。

所有图表都从这里取科目类别, 不再各自按关键词猜。三件事一次说清:

1. 类别 (subject_class): 收入 / 营业成本 / 各项费用 / 其他收益 / 营业外收支 / 税 / 表内利润行
2. 层级 (subject_level):
   - detail   明细, 参与汇总
   - subtotal 小计/合计 (含「营业收入」这类上级科目在其下级同时出现时), 只用来对账, 不参与汇总
   - reported 表内已算好的利润行 (毛利 / 营业利润 / 净利润), 只用来对账
3. 方向: 每个类别对利润是加还是减, 按带符号的原值计算, 不取绝对值
   (财务费用为负 = 利息收入大于支出, 应当增加利润)。
"""
import re
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional, Tuple

_ORDINAL_PREFIX = re.compile(r'^\s*[（(]?[0-9一二三四五六七八九十]{1,3}[）)]?\s*[、.．]\s*')

REVENUE = 'revenue'
COST = 'cost'
EXPENSE_SALES = 'expense_sales'
EXPENSE_ADMIN = 'expense_admin'
EXPENSE_RD = 'expense_rd'
EXPENSE_FINANCE = 'expense_finance'
EXPENSE_OTHER = 'expense_other'
SURCHARGE = 'surcharge'          # 税金及附加: 营业利润之前扣除
OTHER_GAIN = 'other_gain'        # 其他收益 / 投资收益 / 公允价值变动收益 等: 营业利润之前加
NON_OP_INCOME = 'non_op_income'  # 营业外收入
NON_OP_EXPENSE = 'non_op_expense'  # 营业外支出
INCOME_TAX = 'income_tax'
PROFIT_GROSS = 'profit_gross'
PROFIT_OPERATING = 'profit_operating'
PROFIT_TOTAL = 'profit_total'
PROFIT_NET = 'profit_net'
UNKNOWN = 'unknown'

EXPENSE_CLASSES = (EXPENSE_SALES, EXPENSE_ADMIN, EXPENSE_RD, EXPENSE_FINANCE, EXPENSE_OTHER)
REPORTED_PROFIT_CLASSES = (PROFIT_GROSS, PROFIT_OPERATING, PROFIT_TOTAL, PROFIT_NET)

DETAIL = 'detail'
SUBTOTAL = 'subtotal'
REPORTED = 'reported'

# 对利润的方向: +1 加, -1 减。按原值带符号参与计算。
PROFIT_SIGN: Dict[str, int] = {
    REVENUE: 1,
    COST: -1,
    EXPENSE_SALES: -1, EXPENSE_ADMIN: -1, EXPENSE_RD: -1, EXPENSE_FINANCE: -1, EXPENSE_OTHER: -1,
    SURCHARGE: -1,
    OTHER_GAIN: 1,
    NON_OP_INCOME: 1,
    NON_OP_EXPENSE: -1,
    INCOME_TAX: -1,
}

CLASS_LABELS: Dict[str, str] = {
    REVENUE: '营业收入', COST: '营业成本',
    EXPENSE_SALES: '销售费用', EXPENSE_ADMIN: '管理费用', EXPENSE_RD: '研发费用',
    EXPENSE_FINANCE: '财务费用', EXPENSE_OTHER: '其他费用',
    SURCHARGE: '税金及附加', OTHER_GAIN: '其他收益', NON_OP_INCOME: '营业外收入',
    NON_OP_EXPENSE: '营业外支出', INCOME_TAX: '所得税',
    PROFIT_GROSS: '毛利', PROFIT_OPERATING: '营业利润', PROFIT_TOTAL: '利润总额', PROFIT_NET: '净利润',
}

# (关键词, 类别) — 按顺序匹配, 更具体的写在前面。
# ⚠️「营业外收入/营业外支出」必须排在「收入」「支出」之前;「所得税」排在「税金」之前;
#    「毛利率」不是金额科目, 交给调用方当比率列处理, 这里不收。
_RULES: Tuple[Tuple[str, str], ...] = (
    ('营业外收入', NON_OP_INCOME),
    ('营业外支出', NON_OP_EXPENSE),
    ('所得税', INCOME_TAX),
    ('税金及附加', SURCHARGE), ('营业税金', SURCHARGE), ('附加税', SURCHARGE),
    ('净利润', PROFIT_NET), ('净利', PROFIT_NET), ('税后利润', PROFIT_NET),
    ('利润总额', PROFIT_TOTAL),
    ('营业利润', PROFIT_OPERATING),
    ('毛利润', PROFIT_GROSS), ('毛利额', PROFIT_GROSS),
    ('销售费用', EXPENSE_SALES), ('营业费用', EXPENSE_SALES),
    ('管理费用', EXPENSE_ADMIN),
    ('研发费用', EXPENSE_RD),
    ('财务费用', EXPENSE_FINANCE),
    ('期间费用', EXPENSE_OTHER),
    ('其他收益', OTHER_GAIN), ('投资收益', OTHER_GAIN), ('公允价值变动', OTHER_GAIN),
    ('资产处置收益', OTHER_GAIN),
    ('信用减值损失', EXPENSE_OTHER), ('资产减值损失', EXPENSE_OTHER),
    ('主营业务成本', COST), ('其他业务成本', COST), ('营业成本', COST), ('销售成本', COST),
    ('直接材料', COST), ('直接人工', COST), ('制造费用', COST), ('生产成本', COST),
    ('主营业务收入', REVENUE), ('其他业务收入', REVENUE), ('营业收入', REVENUE),
    ('销售收入', REVENUE), ('营业总收入', REVENUE),
)

# 只有表头名本身等于这些词时才当科目 (避免「收入」匹配到「营业外收入」以外的任意文本)
_EXACT_RULES: Dict[str, str] = {
    '收入': REVENUE, '成本': COST, '毛利': PROFIT_GROSS, '费用': EXPENSE_OTHER, '税金': SURCHARGE,
}

# 上级科目 -> 它的下级科目类别判定 (下级出现时上级降为 subtotal)
_PARENTS: Dict[str, Tuple[str, ...]] = {
    '营业收入': ('主营业务收入', '其他业务收入'),
    '营业总收入': ('营业收入', '主营业务收入', '其他业务收入'),
    '营业成本': ('主营业务成本', '其他业务成本', '直接材料', '直接人工', '制造费用'),
    '生产成本': ('直接材料', '直接人工', '制造费用'),
    '期间费用': ('销售费用', '管理费用', '研发费用', '财务费用'),
}

_TOTAL_MARKERS = ('合计', '总计', '小计', '汇总', '总额')


@dataclass(frozen=True)
class SubjectInfo:
    subject_class: str
    level: str


def _strip(name: str) -> str:
    s = str(name).strip()
    # 去掉序号前缀与常见括注: 「一、营业收入」「2.销售费用」「营业收入（万元）」
    s = _ORDINAL_PREFIX.sub('', s)
    for opener, closer in (('（', '）'), ('(', ')')):
        if opener in s and s.endswith(closer):
            s = s[: s.index(opener)]
    s = s.strip()
    for prefix in ('其中：', '其中:', '减：', '减:', '加：', '加:'):
        if s.startswith(prefix):
            s = s[len(prefix):]
    return s.strip()


def classify_subject(name: str) -> str:
    s = _strip(name)
    if not s:
        return UNKNOWN
    if s in _EXACT_RULES:
        return _EXACT_RULES[s]
    for kw, cls in _RULES:
        if kw in s:
            return cls
    return UNKNOWN


def is_total_label(name: str) -> bool:
    s = _strip(name)
    return any(m in s for m in _TOTAL_MARKERS)


def classify_items(names: Iterable[str]) -> Dict[str, SubjectInfo]:
    """对一个表里出现的全部科目名一起判定 (上级/下级要看同表里有没有对方)。"""
    unique: List[str] = []
    for n in names:
        if n is None:
            continue
        s = str(n)
        if s not in unique:
            unique.append(s)
    stripped = {n: _strip(n) for n in unique}
    present = set(stripped.values())

    result: Dict[str, SubjectInfo] = {}
    for n in unique:
        s = stripped[n]
        cls = classify_subject(n)
        if cls in REPORTED_PROFIT_CLASSES:
            level = REPORTED
        elif is_total_label(n):
            level = SUBTOTAL
        elif s in _PARENTS and any(child in present for child in _PARENTS[s]):
            level = SUBTOTAL
        else:
            level = DETAIL
        result[n] = SubjectInfo(cls, level)
    return result


def classify_sheet_name(name: Optional[str]) -> Optional[str]:
    """工作表名暗示的科目 (只在表内各行都认不出科目时使用)。

    「费用预算」「销售费用明细」→ 其他费用; 「收入」「业绩」「销售额」→ 收入; 其余 → None。
    """
    if not name:
        return None
    s = str(name)
    if '费用' in s:
        cls = classify_subject(s)
        return cls if cls in EXPENSE_CLASSES else EXPENSE_OTHER
    if any(kw in s for kw in ('收入', '营收', '业绩', '销售额', '销售')):
        return REVENUE
    return None


def subtotal_children(name: str) -> Optional[Tuple[str, ...]]:
    return _PARENTS.get(_strip(name))


def strip_subject(name: str) -> str:
    return _strip(name)
