#!/usr/bin/env python3
"""Generate backend/java/cretas-api/src/main/resources/ai/intent-domain-table.json.

Input : ai/factory-intent-catalog.json (the reviewed platform catalog, single source of intents).
Output: one row per canonical intent with a primary business domain and an action type.

Rules decide first (ordered regex on intent_code; first match wins).  Anything a rule cannot decide
must be listed in MANUAL_DOMAIN / MANUAL_ACTION with a reason; otherwise this script exits 1
(it never silently defaults).  UNCERTAIN lists rows whose label is a judgement call.

Usage:
  python scripts/ai/generate_intent_domain_table.py            # rewrite the resource
  python scripts/ai/generate_intent_domain_table.py --check    # rc=1 if the resource differs from regenerated output

Exit codes: 0 ok / 1 problem (undecided intent, stale resource) / 2 could not measure (catalog unreadable or empty).
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "backend/java/cretas-api/src/main/resources/ai/factory-intent-catalog.json"
OUT = ROOT / "backend/java/cretas-api/src/main/resources/ai/intent-domain-table.json"
# Python 决策层读的同步副本 (逐字节相同, 由 backend/python/tests/test_intent_domain_scope_contract.py 钉住)
PY_OUT = ROOT / "backend/python/ai/data/intent-domain-table.json"

CATALOG_VERSION = "2026-11-18.1"
# 本版表内容的摘要 (content_sha256)。改了规则 / 人工清单 / 业务词 / 次域 ⇒ 生成脚本 rc=1, 直到同时升 CATALOG_VERSION 并更新这里。
CONTENT_SHA256 = "2c5ab438c88338bda767c478484374fb122fbc38139281b911a51c2dd4e86a6b"

DOMAINS = [
    ("PRODUCTION", "生产", "生产计划/批次/工序任务/排产/产能/生产效率与出成率"),
    ("WAREHOUSE", "仓储库存", "原料与成品批次、入库/领用/盘点/调拨、库存预警、收货、周转耗材"),
    ("QUALITY", "质检与食安", "质检/不良/处置/放行、HACCP、SSOP、添加剂合规、留样、召回"),
    ("SALES", "销售与客户", "客户/商机/销售订单/发货出货/退货/客户跟进/销售分析"),
    ("PROCUREMENT", "采购与供应商", "供应商/资质/采购订单/请购/采购价格"),
    ("FINANCE", "财务与成本", "会计科目/三表/期间关账/开票收款/应收/成本与毛利分析"),
    ("HR", "人事考勤", "考勤打卡/请假加班报销/工资策略与工资成本"),
    ("EQUIPMENT", "设备与IoT", "设备告警/电子秤/摄像头/冷链设备/设备维护"),
    ("TRACE", "溯源", "批次溯源/全链路追溯/公开溯源/源头个体/业务链路"),
    ("RND", "研发与样品", "研发需求/样品/样品转 BOM"),
    ("ANALYTICS", "经营分析", "跨域经营总览/KPI/趋势/异常/指标中心（不属于单一业务域的汇总分析）"),
    ("SYSTEM", "系统与其它", "账号权限/系统设置/审批待办/打印/提醒/对话控制/治理/域外与拒答"),
]
ACTIONS = [
    ("QUERY", "查"),
    ("CREATE", "建"),
    ("UPDATE", "改"),
    ("DELETE", "删"),
    ("APPROVE", "审批"),
    ("ANALYZE", "分析"),
]

# (rule_id, regex on intent_code, domain) -- first match wins
DOMAIN_RULES = [
    ("D01-hr", r"^(ATTENDANCE_|CLOCK_(IN|OUT)$|HR_|WAGE_)", "HR"),
    ("D02-trace", r"^(TRACE_|LINEAGE_|SOURCE_INDIVIDUAL_|BATCH_FULL_TRACE$|BATCH_TRACE_BY_CUSTOMER$|BUSINESS_LINK_)", "TRACE"),
    ("D03-quality", r"^(QUALITY_|HACCP_|ADDITIVE_|SSOP_|FOOD_SAMPLE_|FOOD_SAFETY_|RELEASE_DECISION$|RECALL_|REGULATORY_REPORT$|CUSTOMER_QUALITY_STANDARD$|RECEIVE_QUALITY_CHECK_)", "QUALITY"),
    ("D04-equipment", r"^(ALERT_|CAMERA_|SCALE_|EQUIPMENT_|COLD_CHAIN_)", "EQUIPMENT"),
    ("D05-rnd", r"^(RD_|SAMPLE_TO_BOM$)", "RND"),
    ("D06-finance", r"^(FINANCE_|FINANCIAL_|ACCOUNT_|ACCOUNTS_|BALANCE_SHEET_|INCOME_STATEMENT_|CASHFLOW_|PERIOD_|TAX_GROUP_|MONTHLY_FINANCIAL_|COST_QUERY$|SKU_GROSS_MARGIN$|REPORT_(FINANCE|COST_|BOM_COST_|LABOR_COST_|SALES_MARGIN_))", "FINANCE"),
    ("D07-procurement", r"^(SUPPLIER_|PURCHASE_|PROCUREMENT_|REQUISITION_CREATE$|PRICE_HISTORY_)", "PROCUREMENT"),
    ("D08-warehouse", r"^(MATERIAL_|INVENTORY_|RECEIVE_|FREE_GIFT_|REUSABLE_CONTAINER_|TRANSFER_|ABACA_|WAREHOUSE_|FACTORY_MR_|STOCK_ALERT$|REPORT_INVENTORY$)", "WAREHOUSE"),
    ("D09-sales", r"^(CUSTOMER_|ORDER_|SALES_|SHIPMENT_|SPRINT10_SHIPMENT_|OPPORTUNITY_|DAILY_CUSTOMER_|CALL_FOLLOWUP_|WECHAT_|RETURN_ORDER_|COMMISSION_|PRODUCT_PRODUCT_SALES_)", "SALES"),
    ("D10-production", r"^(WORK_PROCESS_|PROCESS_TASK_|PRODUCTION_|BATCH_|PLAN_UPDATE$|SCHEDULING_|BOM_|PROCESSING_|EFFICIENCY_|REPORT_(EFFICIENCY|PRODUCTION|BATCH_YIELD_RANK)$|PRODUCT_|CONVERSION_RATE_)", "PRODUCTION"),
    ("D11-analytics", r"^(REPORT_|INDICATOR_|SMART_INDICATOR_)", "ANALYTICS"),
    ("D12-system", r"^(SYSTEM_|FACTORY_CONFIG_|FACTORY_NOTIFICATION_|GOVERNANCE_|CONTEXT_|OUT_OF_DOMAIN$|PAGINATION_|UNSUPPORTED_|APPROVAL_|RULE_CONFIG$|WORKFLOW_|OPERATION_LOG_|PRINT_|REMINDER_)", "SYSTEM"),
]
# rules that need a quality override on a prefix they would otherwise swallow are placed before them (D03 before D08/D09).
# REPORT_QUALITY is not covered by D03 (prefix QUALITY_), so it is handled manually below.

# (rule_id, regex on intent_code, action) -- first match wins
ACTION_RULES = [
    ("A01-approve", r"(_APPROVE$|^APPROVAL_ACTION_EXECUTE$|^RELEASE_DECISION$)", "APPROVE"),
    ("A02-create", r"(_CREATE$|_CREATE_|_REGISTER$|_GENERATE$|_LOG$|_RECORD$|_SUBMIT$|_INBOUND$|_SPAWN$|_REQUEST_CLOSE$|_SHIP_OUT$|_RETURN_IN$|_LOSS$|^CLOCK_(IN|OUT)$|^RECEIVE_(CONFIRM|WITH)_|^REQUISITION_CREATE$|_NOTIFY_BATCH$|_CONVERT$|^REGULATORY_REPORT$|^PROCUREMENT_ORDER_CREATE$|^FINANCE_PAYMENT_RECORD$|^FINANCE_INVOICE_REQUEST$|^TAX_GROUP_INVOICE$|^PRINT_DOCUMENT$)", "CREATE"),
    ("A03-update", r"(_UPDATE$|_ADJUST$|_CONFIG$|_SET_[A-Z]+$|_ACKNOWLEDGE$|_RESOLVE$|_CLOSE$|_CONSUME$|_RESERVE$|_RELEASE$|_USE$|_START$|_COMPLETE$|_ASSIGN$|_TRANSITION$|_FREEZE$|_REOPEN$|_CALIBRATE$|_MARK_ABACA$|_DISPOSE$|_ALLOCATE$|_SUBSCRIBE$|_UNSUBSCRIBE$|_CONFIRM_CLOSE$|^SYSTEM_(PASSWORD_RESET|PROFILE_EDIT|SETTINGS|SWITCH_FACTORY)$|^CONVERSION_RATE_UPDATE$|^QUALITY_DISPOSITION_EXECUTE$|^EQUIPMENT_MAINTENANCE$|^BATCH_CONSUMPTION_ADJUST$|^PAGINATION_)", "UPDATE"),
    ("A04-analyze", r"(_STATS$|_STATS_BY_DEPT$|_ANALYSIS$|_RANK$|_RANKING$|_TREND$|_TRENDS$|_FORECAST_7DAY$|_COMPARISON$|_AGING$|_ESTIMATE$|_RECOMMENDATION$|_RECOMMEND$|_DIAGNOSE$|_TRIAGE$|_EVALUATE$|^REPORT_|_FUNNEL_STATS$|_PENDING_TOTAL$|_COST_SUMMARY$|^BALANCE_SHEET_QUERY$|^INCOME_STATEMENT_QUERY$|^CASHFLOW_STATEMENT_QUERY$|^FINANCIAL_CHART_GENERATE$|^FINANCE_PPT_EXPORT$|^COST_QUERY$|^SKU_GROSS_MARGIN$|^SHORTAGE_ANALYSIS$|_DEMAND_ANALYSIS$|^QUALITY_STATS$|^ALERT_DIAGNOSE$|_BOM_COMPLIANCE$)", "ANALYZE"),
    ("A05-query", r"(_QUERY$|_QUERY_|_LIST$|_LIST_MINE$|_STATUS$|_SEARCH$|_ACTIVE$|_LOOKUP$|_TODAY$|_HISTORY$|_DETAIL$|_TREE_LOOKUP$|_LIST_DEVICES$|_BY_TYPE$|_BY_CATEGORY$|_BY_EQUIPMENT$|^ATTENDANCE_(ANOMALY|DEPARTMENT|MONTHLY)$|^TRACE_|_TRACE$|_ALERT$|_EXPIRED_QUERY$|_STREAMS$|_PENDING$|_CAN_ORDER_CHECK$|_DELIVERY_ETA$|_PRICE_ALERT$|_QUALIFICATION_ALERT$|_PENDING_TODAY$|_RECEIVING_PENDING$|_CODE_FORMAT$|^ADDITIVE_(CHECK|SMART_MATCH|COMPLIANCE|COMPLIANCE_QUALITY)$|^HACCP_|_CHECK$|_SUMMARY$|_CHECK_TODAY$|_STANDARD$|_FOLLOWUP$|_ABACA_WEIGHT_)", "QUERY"),
]

# Manual domain overrides: code -> (domain, reason). Beats rules.
MANUAL_DOMAIN = {
    "REPORT_QUALITY": ("QUALITY", "报表名前缀 REPORT_ 会落入经营分析，但内容是质检统计，归质检域"),
    "REPORT_INVENTORY": ("WAREHOUSE", "库存统计报表，归仓储域"),
    "ATTENDANCE_STATS_BY_DEPT": ("HR", "旧分类为 REPORT，实际是考勤统计，归人事域"),
    "REPORT_ANOMALY": ("ANALYTICS", "跨域异常问题统计，不属于单一业务域"),
    "REPORT_DASHBOARD_OVERVIEW": ("ANALYTICS", "工厂仪表盘总览，跨域"),
    "REPORT_KPI": ("ANALYTICS", "KPI 绩效指标，跨域"),
    "REPORT_TRENDS": ("ANALYTICS", "趋势报表，跨域"),
    "COLD_CHAIN_MONITORING": ("EQUIPMENT", "冷链温控 Workdesk skill，编排温度与报警，归设备域"),
    "INVENTORY_FREEZE": ("WAREHOUSE", "冻结批次库存；虽来自召回 SOP，用户说法是操作库存，归仓储域"),
    "CUSTOMER_NOTIFY_BATCH": ("QUALITY", "批量通知客户召回事件（召回 SOP），归质检与食安；也可算销售域"),
    "GATE_DIAGNOSE_BATCH": ("WAREHOUSE", "诊断批次领不了料/出不了库，归仓储域"),
    "SHORTAGE_ANALYSIS": ("PRODUCTION", "销售订单缺料分析，产出采购+生产建议，归生产域；也可算采购域"),
    "PDA_SCAN_TASK_GENERATE": ("WAREHOUSE", "PDA/手机扫码任务，仓库作业入口"),
    "PURCHASE_FINANCE_APPROVE": ("PROCUREMENT", "采购单流程里的财务审核环节，单据属采购域；也可算财务域"),
    "RETURN_ORDER_LIST": ("SALES", "退货单含采购退货与销售退货，默认归销售域"),
    "RETURN_ORDER_CREATE": ("SALES", "退货单含采购退货与销售退货，默认归销售域"),
    "RETURN_ORDER_APPROVE": ("SALES", "退货单含采购退货与销售退货，默认归销售域"),
    "QUALITY_RETURN_CREATE": ("QUALITY", "上游质检退回单（供应商/委外），区别于客户销售退货"),
    "QUALITY_RETURN_QUERY": ("QUALITY", "上游质检退回单（供应商/委外），区别于客户销售退货"),
    "EFFICIENCY_COMPARISON": ("PRODUCTION", "不同时段或工人的生产效率对比"),
    "ORDER_STATUS": ("SALES", "旧分类 DATA_OP；实际是销售订单状态查询"),
    "ORDER_TODAY": ("SALES", "旧分类 DATA_OP；实际是今日订单查询"),
    "CONVERSION_RATE_UPDATE": ("PRODUCTION", "原材料转换率（出成率）配置，归生产域"),
    "PRODUCT_TYPE_QUERY": ("PRODUCTION", "产品主数据归在生产域（规格、转换率）"),
    "PRODUCT_UPDATE": ("PRODUCTION", "产品主数据归在生产域"),
    "PRODUCT_CREATE_FLYWHEEL": ("PRODUCTION", "产品主数据归在生产域"),
    "STOCK_ALERT": ("WAREHOUSE", "低库存预警（文案面向采购员，但对象是库存），归仓储域"),
    "OPERATION_LOG_QUERY": ("SYSTEM", "操作审计日志，系统域"),
    "WORKFLOW_RULE_TEST": ("SYSTEM", "流转规则测试，系统域"),
}

# Manual action overrides: code -> (action, reason). Beats rules.
MANUAL_ACTION = {
    "ADDITIVE_BOM_COMPLIANCE": ("QUERY", "合规校验结果查询，不改数据；旧分类 ANALYSIS"),
    "PERIOD_CONFIRM_CLOSE": ("APPROVE", "确认关账，是审批流末端的确认动作"),
    "SCALE_TROUBLESHOOT": ("ANALYZE", "诊断电子秤读数异常"),
    "COLD_CHAIN_MONITORING": ("QUERY", "冷链温控监测 skill，只读编排"),
    "EQUIPMENT_MAINTENANCE": ("CREATE", "记录设备维护信息，新增一条记录"),
    "CONTEXT_CONTINUE": ("QUERY", "恢复上次查询并重新执行，视作查询"),
    "OUT_OF_DOMAIN": ("QUERY", "域外闲聊，无业务动作，按查询兜底"),
    "UNSUPPORTED_CAPABILITY": ("QUERY", "明确拒答，无业务动作，按查询兜底"),
    "PAGINATION_PREVIOUS": ("QUERY", "返回上一页，对话控制，按查询兜底"),
    "SYSTEM_HELP": ("QUERY", "使用帮助"),
    "SYSTEM_FEEDBACK": ("CREATE", "提交反馈"),
    "FACTORY_CONFIG_AGENT": ("UPDATE", "工厂配置向导，目的是改配置"),
    "GOVERNANCE_PATTERN_DISCOVERY": ("ANALYZE", "分析工具使用模式"),
    "GOVERNANCE_SKILL_COMPOSE": ("CREATE", "把多个 Tool 组合成新 Skill"),
    "GOVERNANCE_SKILL_MANAGE": ("UPDATE", "管理（启停/搜索/列表）Skill 与 Tool，以改状态为主"),
    "WORKFLOW_RULE_TEST": ("QUERY", "测试流转规则，不落库，按查询"),
    "SMART_INDICATOR_QUERY": ("ANALYZE", "老板问指标/对比/告警的 skill，综合分析"),
    "CUSTOMER_PRIORITY_QUERY": ("ANALYZE", "按重要性+商机阶段聚合排序"),
    "SUPPLIER_PRICE_ALERT": ("ANALYZE", "按近 90 天均价找进价异常并按风险排序"),
    "PRICE_HISTORY_QUERY": ("ANALYZE", "最近 N 月加权均价+同比趋势"),
    "QUALITY_CRITICAL_ITEMS": ("QUERY", "查关键质检项"),
    "FOOD_SAMPLE_QUERY_PENDING_DISPOSE": ("QUERY", "查待销毁留样"),
    "FOOD_SAMPLE_LIFECYCLE": ("QUERY", "留样全生命周期 skill，只读联查"),
    "FOOD_SAFETY_RECALL": ("ANALYZE", "召回影响追溯+损失估算 skill，写动作须另行确认"),
    "QUALITY_CHIEF_WORKDESK": ("QUERY", "质量主管工作台 skill，聚合 4 项审计，只读"),
    "SSOP_DAILY_SANITATION": ("QUERY", "SSOP 每日清洁 skill，只读"),
    "SSOP_MONTHLY_AUDIT_REPORT": ("ANALYZE", "SSOP 月度审计报告"),
    "PRODUCTION_PROGRESS_DASHBOARD": ("QUERY", "生产进度看板查询"),
    "PROCESSING_CAPACITY_TODAY": ("ANALYZE", "今日产能汇总"),
    "SAMPLE_TO_BOM": ("CREATE", "由样品生成 BOM 草稿"),
    "BATCH_TRACE_BY_CUSTOMER": ("QUERY", "按客户+投诉日期反查批次"),
    "GATE_DIAGNOSE_BATCH": ("ANALYZE", "诊断批次被哪道闸拦及解法，只读"),
    "MONTHLY_FINANCIAL_CLOSE": ("ANALYZE", "月度财务复盘 skill，聚合三表与期间状态"),
    "FINANCIAL_CHART_GENERATE": ("ANALYZE", "生成财务分析图表"),
    "MATERIAL_ADJUST_QUANTITY": ("UPDATE", "调整批次数量"),
    "ATTENDANCE_DEPARTMENT": ("ANALYZE", "部门考勤统计"),
    "SUPPLIER_DELIVERY_ETA": ("ANALYZE", "供应商交期 ETA 估算"),
}

# Rows whose label is a judgement call (listed in the doc's 存疑清单). code -> note
UNCERTAIN = {
    "CUSTOMER_NOTIFY_BATCH": "召回通知客户：质检与食安 vs 销售与客户",
    "INVENTORY_FREEZE": "召回 SOP 的冻结库存：仓储 vs 质检与食安",
    "SHORTAGE_ANALYSIS": "销售订单缺料分析：生产 vs 采购 vs 仓储",
    "PURCHASE_FINANCE_APPROVE": "采购单的财务审核环节：采购 vs 财务",
    "RETURN_ORDER_LIST": "退货单含采购退货与销售退货：销售 vs 采购",
    "RETURN_ORDER_CREATE": "退货单含采购退货与销售退货：销售 vs 采购",
    "RETURN_ORDER_APPROVE": "退货单含采购退货与销售退货：销售 vs 采购",
    "STOCK_ALERT": "低库存预警文案面向采购员：仓储 vs 采购",
    "SUPPLIER_QUALIFICATION_CREATE": "供应商食品资质：采购 vs 质检与食安",
    "SUPPLIER_QUALIFICATION_LOOKUP": "供应商食品资质：采购 vs 质检与食安",
    "SUPPLIER_QUALIFICATION_QUERY_EXPIRING": "供应商食品资质：采购 vs 质检与食安",
    "SUPPLIER_QUALIFICATION_ALERT": "供应商食品资质：采购 vs 质检与食安",
    "FACTORY_MR_CLOSE": "物料需求单（备料）：仓储 vs 生产",
    "FACTORY_MR_GENERATE": "物料需求单（备料）：仓储 vs 生产",
    "FACTORY_MR_QUERY_PENDING": "物料需求单（备料）：仓储 vs 生产",
    "WAGE_COST_SUMMARY": "工资成本汇总：人事 vs 财务",
    "COMMISSION_PENDING_TOTAL": "待付提成汇总：销售 vs 财务",
    "COST_QUERY": "成本数据：财务 vs 生产",
    "REPORT_BOM_COST_RANK": "BOM 成本排行：财务 vs 生产",
    "REPORT_LABOR_COST_SHARE": "人工成本占比：财务 vs 人事",
    "REPORT_COST_VARIANCE": "成本差异：财务 vs 生产",
    "PRODUCT_TYPE_QUERY": "产品主数据：生产 vs 销售",
    "PRODUCT_UPDATE": "产品主数据：生产 vs 销售",
    "PRODUCT_CREATE_FLYWHEEL": "产品主数据：生产 vs 销售",
    "CONVERSION_RATE_UPDATE": "原材料转换率配置：生产 vs 仓储",
    "BUSINESS_LINK_QUERY": "业务单跨域关联：溯源 vs 系统",
    "GATE_DIAGNOSE_BATCH": "批次被闸拦原因：仓储 vs 质检与食安",
    "PDA_SCAN_TASK_GENERATE": "PDA 扫码任务：仓储 vs 系统",
    "FOOD_SAMPLE_TRACE": "按批次列留样：质检与食安 vs 溯源",
    "COLD_CHAIN_MONITORING": "冷链温控 skill：设备 vs 质检与食安（GB 14881）",
    "COLD_CHAIN_TEMP_QUERY": "冷链温度：设备 vs 质检与食安",
    "COLD_CHAIN_ALERT_ACTIVE": "冷链报警：设备 vs 质检与食安",
    "COLD_CHAIN_ALERT_ACKNOWLEDGE": "冷链报警：设备 vs 质检与食安",
    "REPORT_ANOMALY": "异常问题统计跨域，归经营分析是权宜之计",
    "FINANCE_INVOICE_REQUEST": "动作与工具读写不一致：语义建、工具只读",
    "TAX_GROUP_INVOICE": "动作与工具读写不一致：语义建、工具只读",
    "SYSTEM_PASSWORD_RESET": "动作与工具读写不一致：语义改、工具只引导",
    "SYSTEM_PROFILE_EDIT": "动作与工具读写不一致：语义改、工具只引导",
    "SYSTEM_SETTINGS": "动作与工具读写不一致：语义改、工具只引导",
    "GOVERNANCE_SKILL_MANAGE": "含列表/搜索/启停多种动作，取改",
    "PURCHASE_ORDER_APPROVE": "含提交/审批/取消多种动作，取审批",
    "TRANSFER_APPROVE": "含提交/审批/发货/签收多种动作，取审批",
    "RETURN_ORDER_APPROVE": "含提交/审批/拒绝/完成多种动作，取审批",
    "PERIOD_CONFIRM_CLOSE": "确认关账：审批 vs 改",
    "SMART_INDICATOR_QUERY": "指标 skill，查与分析皆可，取分析",
    "CUSTOMER_PRIORITY_QUERY": "名为查询，实为聚合排序，取分析",
    "OUT_OF_DOMAIN": "无业务动作，按查询兜底",
    "UNSUPPORTED_CAPABILITY": "无业务动作，按查询兜底",
    "PAGINATION_PREVIOUS": "无业务动作，按查询兜底",
    "CONTEXT_CONTINUE": "无业务动作，按查询兜底",
    "CAMERA_UNSUBSCRIBE": "取消订阅是否算删：按改处理",
    "SCHEDULING_LIST": "排班计划列表：生产（排产）vs 人事（班次排班）",
    "RECEIVE_QUALITY_CHECK_TODAY": "今日待质检批次（提醒仓管通知质量主管）：质检 vs 仓储",
}

# 次业务域 (PR-4): 存疑点里写明了「另一个候选域」的行, 取那个域。识别时主域或次域命中都算。
# 只有域存疑的行才有; 存疑点只关于动作 (审批 vs 改、名为查实为分析、读写不一致、对话控制) 的行没有。
# 「生产 vs 采购 vs 仓储」这种三选的只取一个次域 (见注释), 第三个靠「跨域句子并集」兜住。
SECONDARY_DOMAIN = {
    "CUSTOMER_NOTIFY_BATCH": "SALES",
    "INVENTORY_FREEZE": "QUALITY",
    "SHORTAGE_ANALYSIS": "PROCUREMENT",  # 三选: 生产 / 采购 / 仓储; 手工理由写「也可算采购域」
    "PURCHASE_FINANCE_APPROVE": "FINANCE",
    "RETURN_ORDER_LIST": "PROCUREMENT",
    "RETURN_ORDER_CREATE": "PROCUREMENT",
    "RETURN_ORDER_APPROVE": "PROCUREMENT",
    "STOCK_ALERT": "PROCUREMENT",
    "SUPPLIER_QUALIFICATION_CREATE": "QUALITY",
    "SUPPLIER_QUALIFICATION_LOOKUP": "QUALITY",
    "SUPPLIER_QUALIFICATION_QUERY_EXPIRING": "QUALITY",
    "SUPPLIER_QUALIFICATION_ALERT": "QUALITY",
    "FACTORY_MR_CLOSE": "PRODUCTION",
    "FACTORY_MR_GENERATE": "PRODUCTION",
    "FACTORY_MR_QUERY_PENDING": "PRODUCTION",
    "WAGE_COST_SUMMARY": "FINANCE",
    "COMMISSION_PENDING_TOTAL": "FINANCE",
    "COST_QUERY": "PRODUCTION",
    "REPORT_BOM_COST_RANK": "PRODUCTION",
    "REPORT_LABOR_COST_SHARE": "HR",
    "REPORT_COST_VARIANCE": "PRODUCTION",
    "PRODUCT_TYPE_QUERY": "SALES",
    "PRODUCT_UPDATE": "SALES",
    "PRODUCT_CREATE_FLYWHEEL": "SALES",
    "CONVERSION_RATE_UPDATE": "WAREHOUSE",
    "BUSINESS_LINK_QUERY": "SYSTEM",
    "GATE_DIAGNOSE_BATCH": "QUALITY",
    "PDA_SCAN_TASK_GENERATE": "SYSTEM",
    "FOOD_SAMPLE_TRACE": "TRACE",
    "COLD_CHAIN_MONITORING": "QUALITY",
    "COLD_CHAIN_TEMP_QUERY": "QUALITY",
    "COLD_CHAIN_ALERT_ACTIVE": "QUALITY",
    "COLD_CHAIN_ALERT_ACKNOWLEDGE": "QUALITY",
    "SCHEDULING_LIST": "HR",
    "RECEIVE_QUALITY_CHECK_TODAY": "WAREHOUSE",
}

# 业务词 (PR-4 识别第 1 层): 一句话里出现某个业务词 ⇒ 这句话可能属于它后面列的那几个域 (取并集)。
# 一个词常常牵着相邻的几个域 (「出库」既是仓储也是销售发货, 「到货」既是仓储也是采购交期, 「客户」牵着
# 质检的客户标准、溯源的按客户反查、财务的应收): 宁可多带一个域, 不能把本来能识别的意图挤出候选。
# SYSTEM / ANALYTICS 不设词: 它们是跨域通用入口, 永远在候选里。太泛的词 (批次、报表、数据、情况、货) 不放。
# 调词的口径: 验收集 v3 开发半边的「候选保留率」(金标意图仍在缩小后的候选里), 另用 Steve 金标 300 题核对,
# 读数见 PR 描述; 封存半边不碰。
P, W, Q, S, B, F, H, E, T, R = ("PRODUCTION", "WAREHOUSE", "QUALITY", "SALES", "PROCUREMENT", "FINANCE", "HR",
                                "EQUIPMENT", "TRACE", "RND")
KEYWORD_DOMAINS = {
    # 生产
    "生产": (P, W, Q, S, T), "排产": (P, W, B), "产能": (P,), "工序": (P, Q), "报工": (P, Q, H), "开工": (P, Q),
    "完工": (P,), "产量": (P, S), "出成率": (P,), "效率": (P, W, H, E), "加工": (P, W), "车间": (P, Q, H),
    "产线": (P, W, Q, E), "生产线": (P, W, Q, E), "工单": (P,), "工艺": (P, Q), "投料": (P, W), "配方": (P, Q, R), "bom": (P, F, R),
    "任务": (P, W, Q),
    # 仓储
    "库存": (W, S, B, P), "入库": (W, B, Q), "出库": (W, S, Q, P), "领料": (W, P), "领用": (W, P), "盘点": (W,),
    "调拨": (W,), "仓库": (W, P), "原料": (W, B, T, P, Q), "原材料": (W, B, T, P, Q, F), "物料": (W, B, P),
    "收货": (W, B, Q), "到货": (W, B), "备料": (W, P), "冻结": (W, Q), "周转": (W,), "临期": (W, Q),
    "过期": (W, Q, B), "保质期": (W, Q), "冷库": (W, E), "库": (W, E), "哪一批": (T, W),
    # 质检
    "质检": (Q, W), "质量": (Q, S, W, B), "检验": (Q,), "检测": (Q,), "合格": (Q, S, W), "放行": (Q, W, S),
    "留样": (Q,), "样品": (Q, R), "召回": (Q, S, T, W), "haccp": (Q,), "ssop": (Q,), "添加剂": (Q,), "食安": (Q,),
    "食品安全": (Q,), "卫生": (Q,), "消毒": (Q,), "清洁": (Q,), "抽检": (Q,), "不良": (Q,), "ccp": (Q,),
    "微生物": (Q,), "标准": (Q, S),
    # 销售
    "客户": (S, Q, T, F, P), "订单": (S, B, P, F), "销售": (S, F), "发货": (S, W, P), "出货": (S, W, P),
    "发走": (S, W), "退货": (S, Q, B), "商机": (S,), "报价": (S, B), "提成": (S, F), "送货": (S, B),
    "销量": (S, P), "卖": (S, W),
    # 采购
    "供应商": (B, Q, W), "采购": (B, W, S), "请购": (B,), "进价": (B, F), "进货": (B, W), "资质": (B, Q),
    "涨价": (B, F),
    # 财务
    "财务": (F,), "成本": (F, P, B, H), "毛利": (F, S), "利润": (F, S), "发票": (F,), "开票": (F, S), "应收": (F, S),
    "应付": (F, B), "收款": (F, S), "付款": (F, B), "回款": (F, S), "货款": (F, S, B), "对账": (F, S),
    "账单": (F, S), "科目": (F,), "凭证": (F,), "关账": (F,), "资产负债": (F,), "现金流": (F,), "税": (F,),
    # 人事
    "考勤": (H,), "打卡": (H,), "请假": (H,), "加班": (H,), "工资": (H, F), "薪资": (H, F), "报销": (H, F),
    "员工": (H, P), "排班": (H, P), "迟到": (H,), "晚到": (H,), "早退": (H,), "缺勤": (H,), "出勤": (H,),
    "工时": (H, P),
    # 设备
    "设备": (E, P), "告警": (E, W, Q, P), "报警": (E, W, Q), "警告": (E, W, Q, P), "电子秤": (E, W), "秤": (E, W),
    "称重": (E, W), "摄像头": (E,), "监控": (E, Q), "冷链": (E, Q), "温度": (E, Q), "维修": (E,), "保养": (E,),
    "校准": (E,), "故障": (E,),
    # 溯源
    "溯源": (T, Q), "追溯": (T, Q), "追踪": (T, S), "源头": (T,), "全链路": (T,), "流向": (T, S),
    "从哪来": (T, B, W), "去向": (T, S), "关联": (T,), "链路": (T,), "上下游": (T,),
    # 研发
    "研发": (R,), "打样": (R,), "新品": (R, P),
}
DOMAIN_KEYWORDS = {code: [] for code, _n, _d in DOMAINS}
for _word, _doms in KEYWORD_DOMAINS.items():
    for _dom in _doms:
        DOMAIN_KEYWORDS[_dom].append(_word)


def first(rules, code):
    for rid, pattern, value in rules:
        if re.search(pattern, code):
            return rid, value
    return None, None


def build():
    doc = json.loads(CATALOG.read_text(encoding="utf-8"))
    rows = doc["canonical_intents"]
    if not rows:
        print("catalog has no canonical intents", file=sys.stderr)
        sys.exit(2)
    domain_codes = {d[0] for d in DOMAINS}
    action_codes = {a[0] for a in ACTIONS}
    out, undecided = [], []
    codes = {r["intent_code"] for r in rows}
    for stale in [c for c in list(MANUAL_DOMAIN) + list(MANUAL_ACTION) + list(UNCERTAIN) + list(SECONDARY_DOMAIN)
                  if c not in codes]:
        undecided.append(f"manual/uncertain/secondary entry for unknown intent: {stale}")
    for code, sec in SECONDARY_DOMAIN.items():
        if code not in UNCERTAIN:
            undecided.append(f"secondary_domain only for uncertain rows: {code}")
        if sec not in domain_codes:
            undecided.append(f"secondary_domain {sec} is not a declared domain: {code}")
    if set(DOMAIN_KEYWORDS) != domain_codes:
        undecided.append(f"DOMAIN_KEYWORDS must list every domain: {sorted(set(DOMAIN_KEYWORDS) ^ domain_codes)}")
    for dom, words in DOMAIN_KEYWORDS.items():
        if dom in ("SYSTEM", "ANALYTICS") and words:
            undecided.append(f"{dom} is a cross-domain entry and must have no keywords")
        if dom not in ("SYSTEM", "ANALYTICS") and not words:
            undecided.append(f"{dom} has no keywords")
    for r in rows:
        code = r["intent_code"]
        if code in MANUAL_DOMAIN:
            domain, domain_basis, reason_d = MANUAL_DOMAIN[code][0], "manual", MANUAL_DOMAIN[code][1]
        else:
            rid, domain = first(DOMAIN_RULES, code)
            domain_basis, reason_d = rid, None
        if code in MANUAL_ACTION:
            action, action_basis, reason_a = MANUAL_ACTION[code][0], "manual", MANUAL_ACTION[code][1]
        else:
            rid, action = first(ACTION_RULES, code)
            action_basis, reason_a = rid, None
        if domain is None or action is None:
            undecided.append(f"{code}: domain={domain} action={action}")
            continue
        assert domain in domain_codes and action in action_codes, code
        row = {
            "intent_code": code,
            "domain": domain,
            "action": action,
            "domain_basis": domain_basis,
            "action_basis": action_basis,
            "legacy_category": r.get("intent_category"),
            "tool_name": r.get("tool_name"),
            "route_type": r.get("route_type"),
        }
        reasons = [x for x in (reason_d, reason_a) if x]
        if reasons:
            row["reason"] = "；".join(reasons)
        if code in UNCERTAIN:
            row["uncertain"] = True
            row["uncertain_note"] = UNCERTAIN[code]
        if code in SECONDARY_DOMAIN:
            if SECONDARY_DOMAIN[code] == domain:
                undecided.append(f"{code}: secondary_domain equals primary {domain}")
                continue
            row["secondary_domain"] = SECONDARY_DOMAIN[code]
        out.append(row)
    if undecided:
        print("UNDECIDED (add a rule or a manual entry with a reason):", file=sys.stderr)
        for u in undecided:
            print("  " + u, file=sys.stderr)
        sys.exit(1)
    return {
        "schema_version": 1,
        "catalog_version": CATALOG_VERSION,
        "source": "ai/factory-intent-catalog.json canonical_intents (generated by scripts/ai/generate_intent_domain_table.py; this file is the reviewed table, edit the script and regenerate)",
        "domains": [{"code": c, "name_zh": n, "description": d, "keywords": DOMAIN_KEYWORDS[c]} for c, n, d in DOMAINS],
        "actions": [{"code": c, "name_zh": n} for c, n in ACTIONS],
        "intents": out,
    }


def content_sha256(table) -> str:
    """表内容 (除版本号外的全部字段) 的摘要; 键排序, 与缩进 / 版本号无关。"""
    import hashlib
    body = {k: v for k, v in table.items() if k != "catalog_version"}
    return hashlib.sha256(json.dumps(body, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    try:
        table = build()
    except (OSError, ValueError, KeyError) as exc:
        print(f"cannot measure: {exc}", file=sys.stderr)
        return 2
    text = json.dumps(table, ensure_ascii=False, indent=2) + "\n"
    digest = content_sha256(table)
    if digest != CONTENT_SHA256:
        # 内容变了版本号必须跟着变: Python 只在 Java 下发的版本与自己副本相同时收窄, 同号不同内容会两边各按各的表判
        print(f"table content changed (sha256 {digest}) but CONTENT_SHA256 pins {CONTENT_SHA256} for "
              f"{CATALOG_VERSION}: bump CATALOG_VERSION and set CONTENT_SHA256 = \"{digest}\"", file=sys.stderr)
        return 1
    if args.check:
        stale = [p for p in (OUT, PY_OUT) if (p.read_text(encoding="utf-8") if p.exists() else "") != text]
        if stale:
            print(f"stale (rerun the generator): {[str(p) for p in stale]}", file=sys.stderr)
            return 1
        print(f"ok: {len(table['intents'])} intents, {CATALOG_VERSION} sha256 {digest[:12]}")
        return 0
    for p in (OUT, PY_OUT):
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print(f"wrote {p} ({len(table['intents'])} intents)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
