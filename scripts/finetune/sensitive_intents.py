"""Direct-output risk classes for the local student (INTENT-DISTILL, Steve 2026-11-12):

  ordinary  read-only query (inventory, orders, batches, reports ...): direct error <= 2%
  sensitive read-only query touching pay, cost, margin, customer or supplier prices, money flows:
            direct error <= 0.5%, and if the sample cannot show that, the class never goes direct
  write     write proposals: never direct

How the sensitive list was made (rule: when unsure, sensitive):
  1. every READ intent whose name+description matches KEYWORDS (pay / cost / margin / price /
     receivables / payables / cash / invoice / tax / commission / revenue / amount ...);
  2. read by hand: each hit is either in SENSITIVE (with the reason) or in NOT_SENSITIVE (with the
     reason it carries no money figures);
  3. plus intents with no keyword hit whose output can still carry money figures (ADDED).
``check_complete`` fails if a new READ intent matches the keywords but is in neither list, so the
list cannot silently go stale when the catalog grows.
"""
import re

KEYWORDS = (r'工资|薪|成本|毛利|利润|价格|单价|报价|采购价|售价|应收|应付|财务|账|现金|发票|开票|提成|佣金|税|预算|费用|'
            r'收款|付款|资金|损益|资产|负债|营收|收入|金额|价')

SENSITIVE = {
    'ACCOUNTS_RECEIVABLE_AGING': '应收账款金额',
    'BALANCE_SHEET_QUERY': '资产负债表',
    'BOM_RECIPE_QUERY': '配方与原料成本（工艺保密 + 成本）',
    'CASHFLOW_STATEMENT_QUERY': '现金流量表',
    'COMMISSION_PENDING_TOTAL': '提成金额',
    'CUSTOMER_REVENUE_TREND': '客户收入',
    'FINANCE_INVOICE_REQUEST': '开票金额（名为申请，Tool 声明只读）',
    'FINANCE_INVOICE_STATUS': '开票金额',
    'INCOME_STATEMENT_QUERY': '利润表',
    'INDICATOR_COMPARISON': '指标可含单价/毛利/营收（拿不准，归敏感）',
    'INDICATOR_QUERY': '指标可含单价/毛利/营收（拿不准，归敏感）',
    'SMART_INDICATOR_QUERY': '指标可含单价/营收（拿不准，归敏感）',
    'MATERIAL_DISPOSAL_RECOMMENDATION': '处置建议可含价格（拿不准，归敏感）',
    'MONTHLY_FINANCIAL_CLOSE': '财务复盘含工资、应付',
    'OPPORTUNITY_FUNNEL_STATS': '商机金额',
    'PERIOD_STATUS_QUERY': '结账状态属财务口径（拿不准，归敏感）',
    'PRICE_HISTORY_QUERY': '价格历史',
    'PRINT_DOCUMENT': '可打印报价单（拿不准，归敏感）',
    'PURCHASE_ORDER_LIST': '采购单含供应商价格',
    'REMINDER_LIST_MINE': '提醒可含收款金额（拿不准，归敏感）',
    'REPORT_COST_VARIANCE': '单位成本与差异',
    'REPORT_FINANCE': '财务成本报表',
    'SKU_GROSS_MARGIN': '毛利率',
    'SUPPLIER_PRICE_ALERT': '供应商价格',
    'TAX_GROUP_INVOICE': '税额与开票',
    'WAGE_COST_SUMMARY': '工资成本',
    'WAGE_POLICY_QUERY': '工资策略',
}
ADDED = {
    'PRODUCT_PRODUCT_SALES_RANKING': '工具只按金额排名，输出销售额',
    'REPORT_DASHBOARD_OVERVIEW': '仪表盘总览可含营收（拿不准，归敏感）',
    'REPORT_KPI': 'KPI 报表可含财务指标（拿不准，归敏感）',
    'REPORT_TRENDS': '趋势报表可含营收趋势（拿不准，归敏感）',
}
NOT_SENSITIVE = {
    'SYSTEM_SWITCH_FACTORY': '只切换当前工厂，不返回业务数据（描述里的“财务”是可切换的模块名）',
}
SENSITIVE_ALL = dict(SENSITIVE, **ADDED)


def keyword_hits(table):
    return sorted(c for c, v in table.items() if v['read_only'] and re.search(KEYWORDS, (v['name'] or '') + (v['description'] or '')))


def check_complete(table):
    """Every READ keyword hit is classified; every listed intent exists and is READ."""
    missing = [c for c in keyword_hits(table) if c not in SENSITIVE_ALL and c not in NOT_SENSITIVE]
    stale = [c for c in list(SENSITIVE_ALL) + list(NOT_SENSITIVE) if c not in table or table[c]['read_only'] is not True]
    return missing, stale


def risk_class(intent, table):
    """'write' / 'sensitive' / 'ordinary' for a target intent; None (no target) is not a direct candidate."""
    if intent is None or intent not in table:
        return None
    if table[intent]['read_only'] is not True:
        return 'write'
    return 'sensitive' if intent in SENSITIVE_ALL else 'ordinary'


ERROR_CAP = {'ordinary': 0.02, 'sensitive': 0.005, 'write': 0.0}
