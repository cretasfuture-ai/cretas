#!/usr/bin/env python3
"""Build ``clarify_rubric.json``: when a factory request must be clarified (INTENT-DISTILL-20261111 F-pre).

Both labeling teachers (Luna and Terra) judge NEED_CLARIFY from this one rubric, independently.
Everything here is derived from source, never from labeled data:

* decision rules restate the production decision prompt (``backend/python/ai/matcher/decision.py``)
  and the pipeline's dialogue-act definition, with their source lines;
* per read-only intent, the parameters come from the bound Tool's ``getParametersSchema()`` and
  ``getRequiredParameters()``: required with no default -> ``must_ask``; a declared ``default`` or a
  description saying 默认 -> ``defaults`` (never ask); everything else -> ``optional`` (absent = no filter);
* confusions are curated pairs of real intents whose everyday wording overlaps; each one records
  both intents' Tool source lines (computed here, not typed) and the question to ask.

The generator refuses to write if a curated confusion names an intent that is not a training label,
or a read-only intent whose Tool schema cannot be located. Usage:

    python scripts/finetune/build_clarify_rubric.py            # writes scripts/finetune/clarify_rubric.json
    python scripts/finetune/build_clarify_rubric.py --check    # rc=1 if the committed file is stale
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from distill_common import ROOT, build_intent_table  # noqa: E402
from tool_access_modes import TOOL_IMPL, strip_comments  # noqa: E402

OUT = HERE / 'clarify_rubric.json'
OUT_V3 = HERE / 'clarify_rubric_v3.json'
DECISION_PY = 'backend/python/ai/matcher/decision.py'
PIPELINE_PY = 'scripts/finetune/distill_pipeline.py'


def _line_of(rel, needle):
    text = (ROOT / rel).read_text(encoding='utf-8').splitlines()
    for i, line in enumerate(text, 1):
        if needle in line:
            return '%s:%d' % (rel, i)
    raise SystemExit('source anchor not found: %s :: %s' % (rel, needle))


# Each rule cites the line that states it. ``label`` is what the rule implies for dialogue_act.
RULES = [
    dict(id='G1_ACTION_UNCLEAR', label='NEED_CLARIFY',
         rule='只有含糊动词（弄/处理/整/搞/安排/看着办/弄一下），说不出是查看还是新建、修改、删除、审批、确认，'
              '而该对象在候选里既有只读查询又有会改数据的功能：必须先问用户要查还是要办。不要替用户补出未表达的操作意愿。',
         source=[_line_of(DECISION_PY, '不要擅自补出用户未表达的操作意愿')],
         note='含糊动词 + 只读功能唯一（候选里该对象没有写功能）时不适用本条，按 G5 直接选查询。'),
    dict(id='G2_SEVERAL_FIT', label='NEED_CLARIFY',
         rule='候选里有两个以上功能都说得通、含义不同，state 也无法排除其中之一：先问用户要哪一个。',
         source=[_line_of(DECISION_PY, '上面有两个以上的功能都说得通')]),
    dict(id='G3_REFERENT_UNKNOWN', label='NEED_CLARIFY',
         rule='用“这个/那个/这批/这单/那台/它”等指代具体对象，但 state 为空或 state 里没有可对应的对象（confirmed_params 与 last_action 都对不上），'
              '且目标功能需要该对象才能执行：先问是哪一个。state 能对上时按 state 续接（CHANGE_PARAM/PAGE），不要澄清。',
         source=[_line_of(PIPELINE_PY, "'NEED_CLARIFY=信息不足以判断要做什么、指代不明或口径含糊，必须先问用户。'")]),
    dict(id='G4_MUST_ASK_MISSING', label='NEED_CLARIFY',
         rule='功能已经确定，但该功能 intents[*].must_ask 里的参数（必填且无默认值）在当前句和 state 里都拿不到：先问这个参数。'
              '只看 must_ask；defaults 和 optional 里的参数缺了不问。',
         source=[_line_of(DECISION_PY, '缺了关键信息必须先问用户')]),
    dict(id='G5_DEFAULTS_DO_NOT_ASK', label='NEW_TASK',
         rule='功能确定、must_ask 都有了（或该功能没有 must_ask），其余参数有默认值或可以不填：直接选该功能，不要澄清。'
              '例如“帮我看看订单”对应订单列表且无必填参数 → NEW_TASK；时间范围、条数、排序缺省时用默认值。'
              '也不要把具体对象的查询扩大成全局总览。',
         source=[_line_of(DECISION_PY, '不要把具体对象的数据查询扩大成全局总览')]),
    dict(id='G6_CONFUSABLE_TERM', label='NEED_CLARIFY',
         rule='句子命中 confusions 里的易混口径，且没有任何限定词能分出是哪一种（见每条 resolve_when）：按该条的 question 先问。'
              '有限定词能分清时直接选对应功能。',
         source=[_line_of(PIPELINE_PY, "'NEED_CLARIFY=信息不足以判断要做什么、指代不明或口径含糊，必须先问用户。'")]),
    dict(id='G7_CONTINUATION_FIRST', label='CHANGE_PARAM',
         rule='state.prev_intent 存在，且当前句真的给出了新的参数值、对象或口径（含回答 state.pending_fields 里的字段）：是 CHANGE_PARAM，沿用 prev_intent，不要澄清。'
              '但如果改动后的说法让读/写含义变得不明（如上一轮在查，这一轮只说“处理一下”），按 G1。',
         source=[_line_of(PIPELINE_PY, "'CHANGE_PARAM=沿用上一轮任务")]),
    dict(id='G8_PENDING_NOT_ANSWERED', label='NEED_CLARIFY',
         rule='state.pending_fields 不为空（系统正在等用户回答这些字段），而当前句没有给出其中任何一个字段的值，'
              '只是含糊地催促或重复任务（如“这个先弄一下”“看一下”“帮我处理”），也没有换任务或取消：是 NEED_CLARIFY（继续追问该字段），'
              '不是 CHANGE_PARAM——CHANGE_PARAM 要求句子里真的有新参数值。',
         source=[_line_of(DECISION_PY, '缺了关键信息必须先问用户'),
                 _line_of(PIPELINE_PY, "'CHANGE_PARAM=沿用上一轮任务")]),
]

# Compact wording of each rule for the teachers' prompts (same meaning, fewer tokens; the full
# text above stays in the JSON for humans). Both teachers get exactly this text.
BRIEFS = {
    'G1_ACTION_UNCLEAR': '只有含糊动词（弄/处理/整/搞一下），候选里该对象既有查询又有改数据的功能 → 问要查还是要办；该对象只有查询功能则直接选查询',
    'G2_SEVERAL_FIT': '两个以上候选都说得通、含义不同，state 也排除不了 → 问',
    'G3_REFERENT_UNKNOWN': '“这个/那批/那台”指代的对象在 state 里找不到，而功能需要它 → 问；state 能对上就续接',
    'G4_MUST_ASK_MISSING': '功能已定，但它的 must_ask 参数在句子和 state 里都没有 → 问；defaults/其余参数缺了不问',
    'G5_DEFAULTS_DO_NOT_ASK': '功能已定、must_ask 齐全或没有 → 直接选（如“看看订单”→订单列表），不要扩大成总览',
    'G6_CONFUSABLE_TERM': '命中候选 confusions 的易混口径且没有限定词能分清 → 问；有限定词按 resolve_when 选',
    'G7_CONTINUATION_FIRST': '有 prev_intent 且句子给出了新的参数值/对象/口径（含回答 pending_fields）→ CHANGE_PARAM，不问',
    'G8_PENDING_NOT_ANSWERED': 'pending_fields 不为空，句子没给出其中任何字段的值，只是含糊催促/重复任务 → NEED_CLARIFY（不是 CHANGE_PARAM）',
}
assert set(BRIEFS) == {r['id'] for r in RULES}, 'every rule needs exactly one brief'

# v3 (2026-11-12): Steve's product policy, answered in plain words to 8 policy questions
# (Documents/Cretas意图蒸馏-20261111/steve-policy-rules-20261112.md). Principles: read-only requests
# whose reasonable default cannot mislead are EXECUTED and the answer states the default; requests
# that may be a write, or whose target is genuinely two-way, are ASKED; what the system cannot do is
# said plainly together with the closest result it can give. v1 labels stay alongside; the
# independent acceptance set and shadow runs decide. Not fitted to any reviewed item set.
STEVE_V3 = 'Steve 产品口径 8 问（2026-11-12，steve-policy-rules-20261112.md）'
RULES_V3 = [
    dict(id='C1_VAGUE_VERB', label='NEED_CLARIFY',
         rule='只有含糊动词（弄一下/处理一下/搞一下/整一下），没说要做什么：先问要做什么（回复里给几个常用操作）。'
              '例：正在看某批次质检结果时说“弄一下”→ 问。',
         source=[STEVE_V3 + ' 第 4 条']),
    dict(id='C2_READ_OR_WRITE_TWO_WAY', label='NEED_CLARIFY',
         rule='这句话既可能是查询、也可能是录入/新建/修改（查还是改只差一个字），且没有别的词能分清：先问是查还是要录入。'
              '例：“录下草莓酸奶下周要多少”→ 问是查需求量还是录入一个数。动词明确是写（录入/新建/提交/审批/入库……）且没有查询读法时不问，按写操作选，执行前系统会再确认。',
         source=[STEVE_V3 + ' 第 5 条']),
    dict(id='C3_OBJECT_SWITCH_TWO_WAY', label='NEED_CLARIFY',
         rule='接着上一轮，只换了查询对象（“那原料呢”“那客户呢”），而新对象在候选里对应两种以上意思不同的查询，上文也定不下是哪一种：先问。'
              '例：刚查完上月订单，说“那原料呢”→ 问看原料库存，还是看上个月的原料入库。',
         source=[STEVE_V3 + ' 第 3 条']),
    dict(id='C4_TARGET_UNKNOWN', label='NEED_CLARIFY',
         rule='用“这个/那个/它”指代，句子里没有具体业务名词，state 里也对不上任何对象，看不出要做哪件事：先问。'
              '必填参数缺了、又没有合理默认（不是时间、条数、范围这类能默认的）也先问。',
         source=[STEVE_V3 + ' 总原则“目标本身两可的，先问”', _line_of(DECISION_PY, '缺了关键信息必须先问用户')]),
    dict(id='C5_PENDING_NOT_ANSWERED', label='NEED_CLARIFY',
         rule='state.pending_fields 不为空，当前句没有给出其中任何字段的值，也没有换任务或取消：继续追问该字段。',
         source=[_line_of(DECISION_PY, '缺了关键信息必须先问用户'), _line_of(PIPELINE_PY, "'CHANGE_PARAM=沿用上一轮任务")]),
    dict(id='E1_READ_WITH_DEFAULTS', label='NEW_TASK',
         rule='只读查询、缺的参数有合理默认且不会误导：直接执行，回复里写明用了什么默认。'
              '没说时间默认最近 7 天；“过期”只指已经过期（不混入快过期）；结果多时先给最近 20 条并写明总条数。',
         source=[STEVE_V3 + ' 第 1、2、7 条']),
    dict(id='E2_SAME_NAME_LIST_ALL', label='NEW_TASK',
         rule='同名的人或物（两个张三）：不追问，按查询执行，把同名的都列出来并标明车间/部门。',
         source=[STEVE_V3 + ' 第 8 条']),
    dict(id='E3_CANNOT_DO_GIVE_CLOSEST', label='NEW_TASK',
         rule='功能明确但要的口径系统做不到（如按数量排销量而工具只支持按金额）：不追问，选该功能执行，回复里说明做不到并给出最接近的结果。',
         source=[STEVE_V3 + ' 第 6 条']),
    dict(id='E4_CONTINUATION', label='CHANGE_PARAM',
         rule='state.prev_intent 存在，当前句给出了新的参数值或口径（含回答 pending_fields）、任务没变：CHANGE_PARAM 沿用 prev_intent，不问。'
              '只换对象且两可时按 C3。',
         source=[_line_of(PIPELINE_PY, "'CHANGE_PARAM=沿用上一轮任务")]),
]
BRIEFS_V3 = {
    'C1_VAGUE_VERB': '只有“弄一下/处理一下”这类含糊动词、没说做什么 → 问要做什么',
    'C2_READ_OR_WRITE_TWO_WAY': '查还是录入/改两种读法都通（如“录下…要多少”）→ 问；明确的写动词不问，按写选',
    'C3_OBJECT_SWITCH_TWO_WAY': '接着上轮只换对象（“那原料呢”），新对象有两种以上不同查询、上文定不下 → 问',
    'C4_TARGET_UNKNOWN': '只有“这个/那个”、无业务名词、state 对不上 → 问；必填参数缺且无合理默认 → 问',
    'C5_PENDING_NOT_ANSWERED': 'pending_fields 不为空、没回答也没换任务 → 继续追问',
    'E1_READ_WITH_DEFAULTS': '只读且默认不会误导 → 执行：没说时间=最近7天；“过期”=已过期；结果多先20条写总数',
    'E2_SAME_NAME_LIST_ALL': '同名的人/物 → 不问，都列出来标明车间',
    'E3_CANNOT_DO_GIVE_CLOSEST': '口径做不到（如按数量排）→ 不问，执行该功能并说明，给最接近结果',
    'E4_CONTINUATION': '有 prev_intent、给了新参数/口径、任务没变 → CHANGE_PARAM',
}
assert set(BRIEFS_V3) == {r['id'] for r in RULES_V3}, 'every v3 rule needs exactly one brief'
for _r in RULES_V3:
    _r['brief'] = BRIEFS_V3[_r['id']]
for _r in RULES:
    _r['brief'] = BRIEFS[_r['id']]

# Curated confusable wording. ``intents`` are real training labels; their Tool source lines are
# attached at build time. ``resolve_when`` lists the qualifiers that make it unambiguous.
CONFUSIONS = [
    dict(id='C_EXPIRED_VS_EXPIRING', term='过期/到期（原料）',
         intents=['MATERIAL_EXPIRED_QUERY', 'MATERIAL_EXPIRING_ALERT', 'MATERIAL_DISPOSAL_RECOMMENDATION'],
         question='您要看已经过期的批次、快过期（临期）的批次，还是临期料怎么处理的建议？',
         resolve_when=['已经过期/过期了/超期 → MATERIAL_EXPIRED_QUERY', '快过期/临期/还有几天到期 → MATERIAL_EXPIRING_ALERT',
                       '临期的怎么处理/该怎么办 → MATERIAL_DISPOSAL_RECOMMENDATION']),
    dict(id='C_SUPPLIER_QUALIFICATION', term='供应商资质/证照',
         intents=['SUPPLIER_QUALIFICATION_QUERY_EXPIRING', 'SUPPLIER_QUALIFICATION_ALERT', 'SUPPLIER_QUALIFICATION_LOOKUP',
                  'SUPPLIER_QUALIFICATION_CREATE'],
         question='您要查某家供应商的资质、看哪些资质快到期，还是登记一份新资质？',
         resolve_when=['点名某家供应商查资质 → SUPPLIER_QUALIFICATION_LOOKUP', '哪些快到期/要续证 → SUPPLIER_QUALIFICATION_QUERY_EXPIRING',
                       '资质预警/有问题的 → SUPPLIER_QUALIFICATION_ALERT', '登记/录入/上传资质 → SUPPLIER_QUALIFICATION_CREATE（写）']),
    dict(id='C_RANKING_OBJECT', term='排行/排名/前几名（没说排谁）',
         intents=['SUPPLIER_RANKING', 'PRODUCT_PRODUCT_SALES_RANKING', 'CUSTOMER_PRIORITY_QUERY'],
         question='您要看供应商排名、产品销量排名，还是客户优先级？',
         resolve_when=['供应商 → SUPPLIER_RANKING', '产品/卖得最好 → PRODUCT_PRODUCT_SALES_RANKING', '客户/大客户 → CUSTOMER_PRIORITY_QUERY'],
         note='对象明确后，排序维度、条数有默认值（见 defaults），不再因“按什么排”澄清。'),
    dict(id='C_LOW_STOCK', term='库存不够/库存情况',
         intents=['MATERIAL_LOW_STOCK_ALERT', 'STOCK_ALERT', 'REPORT_INVENTORY'],
         question='您要看哪些原料低于安全库存，还是看库存报表？',
         resolve_when=['不够/缺/快没了/低于 → MATERIAL_LOW_STOCK_ALERT 或 STOCK_ALERT（采购视角、要补货）',
                       '报表/周报/月报/整体库存 → REPORT_INVENTORY']),
    dict(id='C_ATTENDANCE_SCOPE', term='考勤/打卡情况',
         intents=['ATTENDANCE_TODAY', 'ATTENDANCE_STATUS', 'ATTENDANCE_MONTHLY', 'ATTENDANCE_ANOMALY', 'ATTENDANCE_HISTORY',
                  'ATTENDANCE_STATS', 'ATTENDANCE_DEPARTMENT', 'CLOCK_IN'],
         question='您要看今天的打卡、本月汇总、异常记录、某段时间的历史，还是现在要打卡？',
         resolve_when=['今天谁到了/今日打卡 → ATTENDANCE_TODAY', '我打没打卡/现在状态 → ATTENDANCE_STATUS', '这个月/月度 → ATTENDANCE_MONTHLY',
                       '迟到/缺卡/异常/不对 → ATTENDANCE_ANOMALY', '上周/某天/最近几天 → ATTENDANCE_HISTORY', '出勤率/统计 → ATTENDANCE_STATS',
                       '某部门 → ATTENDANCE_DEPARTMENT', '帮我打卡/上班了 → CLOCK_IN（写）']),
    dict(id='C_SHIPMENT', term='发货/出货',
         intents=['SHIPMENT_QUERY', 'SHIPMENT_STATS', 'SPRINT10_SHIPMENT_PENDING_TODAY', 'SHIPMENT_CREATE', 'SHIPMENT_CONFIRM_CREATE'],
         question='您要查发货记录、看发货统计、看今天待发的，还是现在要建发货单/确认发货？',
         resolve_when=['查/有没有发/发到哪 → SHIPMENT_QUERY', '发了多少/统计 → SHIPMENT_STATS', '今天要发什么/待发 → SPRINT10_SHIPMENT_PENDING_TODAY',
                       '开发货单/安排发货 → SHIPMENT_CREATE（写）', '确认已发/发出去了 → SHIPMENT_CONFIRM_CREATE（写）']),
    dict(id='C_COST_SCOPE', term='成本/赚多少',
         intents=['REPORT_COST_VARIANCE', 'SKU_GROSS_MARGIN', 'WAGE_COST_SUMMARY', 'COST_QUERY'],
         question='您要看成本差异（实际比标准多了多少）、产品毛利率、人工工资成本，还是成本明细？',
         resolve_when=['差多少/超了/偏差/差异 → REPORT_COST_VARIANCE', '毛利/赚多少/利润率（按产品） → SKU_GROSS_MARGIN',
                       '工资/人工成本 → WAGE_COST_SUMMARY', '成本多少（未限定） → COST_QUERY（Tool 声明为 WRITE，不可直出）']),
    dict(id='C_QUALITY_VIEW', term='质检情况/质量怎么样',
         intents=['QUALITY_CHECK_QUERY', 'QUALITY_STATS', 'QUALITY_CRITICAL_ITEMS', 'QUALITY_CHECK_SUMMARY', 'REPORT_QUALITY',
                  'QUALITY_DEFECT_QUERY'],
         question='您要看具体质检项、合格率统计、关键质检项、综合汇总、质量报表，还是不良记录？',
         resolve_when=['某批/某项检了什么 → QUALITY_CHECK_QUERY', '合格率/统计 → QUALITY_STATS', '关键项/CCP → QUALITY_CRITICAL_ITEMS',
                       '汇总/总体 → QUALITY_CHECK_SUMMARY', '报表/周报 → REPORT_QUALITY', '不良/次品/缺陷 → QUALITY_DEFECT_QUERY']),
    dict(id='C_TRACE', term='追溯/溯源',
         intents=['TRACE_BATCH', 'TRACE_FULL', 'TRACE_PUBLIC', 'BATCH_FULL_TRACE', 'LINEAGE_QUERY', 'BATCH_TRACE_BY_CUSTOMER'],
         question='您要追某个批次、看完整链路、看消费者扫码的公开溯源，还是按客户和日期找批次？',
         resolve_when=['消费者/扫码/公开 → TRACE_PUBLIC', '完整/全链路/从原料到发货 → TRACE_FULL 或 BATCH_FULL_TRACE',
                       '按客户+日期 → BATCH_TRACE_BY_CUSTOMER', '上下游/血缘/用了哪些料 → LINEAGE_QUERY', '某批次（有批号） → TRACE_BATCH']),
    dict(id='C_ORDER_KIND', term='订单（没说销售还是采购）',
         intents=['ORDER_LIST', 'ORDER_TODAY', 'ORDER_STATUS', 'PURCHASE_ORDER_LIST'],
         question='您要看客户的销售订单，还是我们的采购订单？',
         resolve_when=['客户/销售/卖 → ORDER_*（今天 → ORDER_TODAY；某张单到哪了 → ORDER_STATUS；否则 ORDER_LIST）',
                       '采购/进货/供应商 → PURCHASE_ORDER_LIST'],
         note='一般说“订单”默认指销售订单（ORDER_LIST），只有句子同时带进货/采购线索又不明确时才问。'),
    dict(id='C_MONEY_VIEW', term='收入/钱/利润',
         intents=['PRODUCT_PRODUCT_SALES_RANKING', 'REPORT_FINANCE', 'INCOME_STATEMENT_QUERY', 'ACCOUNTS_RECEIVABLE_AGING',
                  'CUSTOMER_REVENUE_TREND'],
         question='您要看哪方面：产品卖得怎么样、财务报表、利润表、客户欠款账龄，还是某客户的收入趋势？',
         resolve_when=['欠款/没收回/账龄 → ACCOUNTS_RECEIVABLE_AGING', '利润表/损益 → INCOME_STATEMENT_QUERY', '财务报表/总体财务 → REPORT_FINANCE',
                       '某客户收入走势 → CUSTOMER_REVENUE_TREND', '哪个产品卖得好 → PRODUCT_PRODUCT_SALES_RANKING']),
    dict(id='C_ALERT_KIND', term='告警/报警',
         intents=['ALERT_ACTIVE', 'ALERT_LIST', 'ALERT_STATS', 'COLD_CHAIN_ALERT_ACTIVE', 'INDICATOR_ALERT', 'ALERT_ACKNOWLEDGE',
                  'ALERT_RESOLVE'],
         question='您要看哪类告警（设备告警、冷链温度报警、指标告警），是查看还是要确认/处理掉？',
         resolve_when=['冷库/温度 → COLD_CHAIN_ALERT_ACTIVE', '指标/超标扫描 → INDICATOR_ALERT', '现在还没处理的 → ALERT_ACTIVE',
                       '全部/历史 → ALERT_LIST', '多少条/统计 → ALERT_STATS', '确认/知道了 → ALERT_ACKNOWLEDGE（写）',
                       '处理掉/解决 → ALERT_RESOLVE（写）']),
    dict(id='C_RECEIVING_TODAY', term='今天到货/收货',
         intents=['MATERIAL_TODAY_RECEIVING_QUERY', 'WAREHOUSE_TODAY_RECEIVING_PENDING', 'RECEIVE_QUALITY_CHECK_TODAY',
                  'RECEIVE_CONFIRM_CREATE'],
         question='您要看今天要收的货、今天待收的采购单、今天到货待质检的批次，还是现在确认收货？',
         resolve_when=['今天到/要收什么料 → MATERIAL_TODAY_RECEIVING_QUERY', '采购单/PO 待收 → WAREHOUSE_TODAY_RECEIVING_PENDING',
                       '待质检/要检的 → RECEIVE_QUALITY_CHECK_TODAY', '收了/确认收货 → RECEIVE_CONFIRM_CREATE（写）']),
    dict(id='C_SHORTAGE', term='缺/不够做',
         intents=['PRODUCTION_DEMAND_QUERY', 'PRODUCTION_DEMAND_ANALYSIS', 'SHORTAGE_ANALYSIS'],
         question='您是问哪些订单产量不够（缺产），还是做订单缺哪些原料（缺料）？',
         resolve_when=['订单做不出来/缺产 → PRODUCTION_DEMAND_QUERY（要排产建议 → PRODUCTION_DEMAND_ANALYSIS）',
                       '缺料/原料不够 → SHORTAGE_ANALYSIS']),
    dict(id='C_LEAVE', term='请假',
         intents=['HR_LEAVE_REQUEST_QUERY', 'HR_LEAVE_BALANCE_QUERY', 'HR_LEAVE_SUBMIT', 'HR_LEAVE_APPROVE'],
         question='您要查请假申请、查还剩几天假、提交请假，还是审批别人的请假？',
         resolve_when=['还剩几天/余额 → HR_LEAVE_BALANCE_QUERY', '查申请/批了没 → HR_LEAVE_REQUEST_QUERY', '我要请假 → HR_LEAVE_SUBMIT（写）',
                       '批准/驳回/同意 → HR_LEAVE_APPROVE（写；只说“处理一下”时还要问批准还是驳回）']),
    dict(id='C_HACCP', term='HACCP',
         intents=['HACCP_CHECKPOINT', 'HACCP_REVIEW', 'HACCP_STATUS_QUERY'],
         question='您要看 HACCP 检查点、监控审查记录，还是放行前的 HACCP 状态？',
         resolve_when=['检查点/CCP 点 → HACCP_CHECKPOINT', '审查/复核记录 → HACCP_REVIEW', '能不能放行/放行前状态 → HACCP_STATUS_QUERY']),
    dict(id='C_ADDITIVE', term='添加剂合规',
         intents=['ADDITIVE_CHECK', 'ADDITIVE_COMPLIANCE', 'ADDITIVE_BOM_COMPLIANCE', 'ADDITIVE_COMPLIANCE_QUALITY', 'ADDITIVE_SMART_MATCH'],
         question='您要校验某个添加剂的限量、做整体合规检查、按配方综合校验，还是放行前判定？',
         resolve_when=['限量/用量超没超 → ADDITIVE_CHECK', '配方/BOM → ADDITIVE_BOM_COMPLIANCE', '放行前 → ADDITIVE_COMPLIANCE_QUALITY',
                       '换个能用的/匹配 → ADDITIVE_SMART_MATCH', '合规不合规（未限定） → ADDITIVE_COMPLIANCE']),
    dict(id='C_INDICATOR', term='指标',
         intents=['INDICATOR_QUERY', 'SMART_INDICATOR_QUERY', 'REPORT_KPI', 'INDICATOR_COMPARISON'],
         question='您要查某个指标的数值、做指标分析、看 KPI 报表，还是对比两个时期？',
         resolve_when=['对比/同比/环比 → INDICATOR_COMPARISON', 'KPI 报表 → REPORT_KPI', '分析/为什么 → SMART_INDICATOR_QUERY',
                       '某指标多少 → INDICATOR_QUERY']),
]


# ---------------------------------------------------------------- Java schema extraction
def _match_paren(code, start):
    depth, i = 0, start
    while i < len(code):
        ch = code[i]
        if ch == '"':
            j = i + 1
            while j < len(code) and code[j] != '"':
                j += 2 if code[j] == '\\' else 1
            i = j + 1
            continue
        if ch in '({':
            depth += 1
        elif ch in ')}':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(code) - 1


def _lineno(code, idx):
    return code.count('\n', 0, idx) + 1


def _strings(chunk):
    return re.findall(r'"((?:\\.|[^"\\])*)"', chunk)


def _list_literal(code, m_end):
    """Strings inside List.of(...)/Arrays.asList(...)/singletonList(...) starting at m_end."""
    m = re.match(r'\s*(?:List\.of|Arrays\.asList|Collections\.singletonList|Set\.of)\s*\(', code[m_end:])
    if not m:
        return [] if re.match(r'\s*(?:Collections\.emptyList|List\.of)\s*\(\s*\)', code[m_end:]) else None
    start = m_end + m.end() - 1
    return _strings(code[start:_match_paren(code, start) + 1])


def _var_facts(code, var):
    facts = {}
    for key in ('description', 'default'):
        m = re.search(r'\b' + re.escape(var) + r'\.put\(\s*"' + key + r'"\s*,\s*("(?:\\.|[^"\\])*"|[^;]+?)\s*\)\s*;', code)
        if m:
            facts[key] = m.group(1).strip().strip('"')
    return facts


def _inline_facts(chunk):
    facts = {}
    m = re.search(r'"description"\s*,\s*"((?:\\.|[^"\\])*)"', chunk)
    if m:
        facts['description'] = m.group(1)
    m = re.search(r'"default"\s*,\s*("(?:\\.|[^"\\])*"|[\w.\-]+)', chunk)
    if m:
        facts['default'] = m.group(1).strip('"')
    return facts


def tool_schema(path):
    """-> dict(params={name: facts+line}, required=[..], schema_line) from one Tool source file."""
    rel = path.relative_to(ROOT).as_posix()
    code = strip_comments(path.read_text(encoding='utf-8'))
    m = re.search(r'getParametersSchema\s*\(\s*\)\s*\{', code)
    if not m:
        return None
    body_end = _match_paren(code, m.end() - 1)
    body = code[m.end():body_end]
    off = m.end()
    params = {}
    # properties.put("x", var)  /  properties.put("x", Map.of(...))  /  "x", Map.of("type", ...)
    for pm in re.finditer(r'\.put\(\s*"(\w+)"\s*,\s*(\w+)\s*\)', body):
        name, var = pm.group(1), pm.group(2)
        if name in ('properties', 'required', 'items') or var in ('true', 'false', 'null'):
            continue
        facts = _var_facts(code, var)
        built = re.search(r'\b' + re.escape(var) + r'\s*=\s*[A-Z]\w*\.\w*[Pp]roperty\(\s*"((?:\\.|[^"\\])*)"', code)
        if not facts and built:                     # var = StatusFilter.property("desc", ...)
            facts = dict(description=built.group(1))
        if not facts and not built and not re.search(r'\b' + re.escape(var) + r'\.put\(\s*"type"', code):
            continue
        params[name] = dict(facts, source='%s:%d' % (rel, _lineno(code, off + pm.start())))
    for pm in re.finditer(r'"(\w+)"\s*,\s*(?:Map\.of|Map\.ofEntries|new LinkedHashMap<>\(Map\.of)\s*\(', body):
        name = pm.group(1)
        if name in ('type', 'properties', 'items', 'required') or name in params:
            continue
        start = off + pm.end() - 1
        chunk = code[start:_match_paren(code, start) + 1]
        if '"type"' not in chunk:
            continue
        params[name] = dict(_inline_facts(chunk), source='%s:%d' % (rel, _lineno(code, off + pm.start())))
    # properties.put("x", StatusFilter.property("desc", ...)) / TimeRangeFilter.dateProperty("desc")
    for pm in re.finditer(r'\.put\(\s*"(\w+)"\s*,\s*[A-Z]\w*\.\w*[Pp]roperty\(\s*"((?:\\.|[^"\\])*)"', body):
        if pm.group(1) not in params:
            params[pm.group(1)] = dict(description=pm.group(2), source='%s:%d' % (rel, _lineno(code, off + pm.start())))
    required, req_line = [], None
    rm = re.search(r'getRequiredParameters\s*\(\s*\)\s*\{\s*return\s*', code)
    if rm:
        got = _list_literal(code, rm.end())
        if got is not None:
            required, req_line = got, '%s:%d' % (rel, _lineno(code, rm.start()))
    if not required:
        sm = re.search(r'"required"\s*,\s*', body)
        if sm:
            got = _list_literal(body, sm.end())
            if got:
                required, req_line = got, '%s:%d' % (rel, _lineno(code, off + sm.start()))
    return dict(params=params, required=required, required_source=req_line,
                schema_source='%s:%d' % (rel, _lineno(code, m.start())))


def tool_files():
    out = {}
    for p in TOOL_IMPL.rglob('*.java'):
        code = strip_comments(p.read_text(encoding='utf-8'))
        m = re.search(r'String\s+getToolName\s*\(\s*\)\s*\{\s*return\s+(?:"([^"]+)"|(\w+))\s*;', code)
        if not m:
            continue
        name = m.group(1)
        if not name:
            c = re.search(r'String\s+' + re.escape(m.group(2)) + r'\s*=\s*"([^"]+)"', code)
            name = c.group(1) if c else None
        if name:
            out[name] = p
    return out


def _parent_file(path, files_by_class):
    code = strip_comments(path.read_text(encoding='utf-8'))
    m = re.search(r'\bclass\s+\w+(?:\s*<[^{]*?>)?\s+extends\s+(\w+)', code)
    return files_by_class.get(m.group(1)) if m else None


def tool_sources(cat, code, files):
    """Tool file:line (getToolName) for an intent, read or write."""
    r = cat[code]
    tools = (r.get('skill_tools') or []) if r.get('route_type') == 'SKILL' else [r.get('tool_name')]
    out = []
    for t in tools:
        p = files.get(t)
        if p is None:
            raise SystemExit('no Tool source for %s/%s' % (code, t))
        text = p.read_text(encoding='utf-8').splitlines()
        line = next((i for i, x in enumerate(text, 1) if 'getToolName' in x), 1)
        out.append('%s:%d' % (p.relative_to(ROOT).as_posix(), line))
    return out


def _has_default_text(desc):
    return bool(desc) and bool(re.search(r'默认|缺省|不填(?:则|时|就)|不传(?:则|时)|为空(?:则|时)', desc))


def build(version=1):
    sys.path.insert(0, str(ROOT / 'backend' / 'python'))
    from ai import factory_catalog as fc
    table = build_intent_table()
    cat = fc.get_catalog()['canonical']
    files = tool_files()
    by_class = {p.stem: p for p in TOOL_IMPL.rglob('*.java')}
    intents, missing = {}, []
    for code, v in sorted(table.items()):
        if not v['read_only']:
            continue
        r = cat[code]
        tools = (r.get('skill_tools') or []) if r.get('route_type') == 'SKILL' else [r.get('tool_name')]
        entry = dict(name=v['name'], family=v['family'], tools=tools, must_ask=[], defaults=[], optional=[])
        for t in tools:
            p = files.get(t)
            sch = tool_schema(p) if p else None
            while p is not None and sch is None:      # schema inherited from a parent Tool class
                p = _parent_file(p, by_class)
                sch = tool_schema(p) if p else None
            if sch is None:
                missing.append('%s/%s' % (code, t))
                continue
            entry.setdefault('schema_source', []).append(sch['schema_source'])
            if sch['required_source']:
                entry.setdefault('required_source', []).append(sch['required_source'])
            for name, f in sorted(sch['params'].items()):
                item = dict(param=name, description=f.get('description', ''), source=f['source'])
                if 'default' in f:
                    entry['defaults'].append(dict(item, default=f['default']))
                elif name in sch['required']:
                    entry['must_ask'].append(item)
                elif _has_default_text(f.get('description')):
                    entry['defaults'].append(dict(item, default='见描述'))
                else:
                    entry['optional'].append(item)
            for name in sch['required']:            # required but not in properties: still must ask
                if name not in sch['params']:
                    entry['must_ask'].append(dict(param=name, description='', source=sch['required_source']))
        intents[code] = entry
    if missing:
        raise SystemExit('read-only intents without a locatable Tool schema: ' + ', '.join(missing))
    confusions = []
    for c in CONFUSIONS:
        bad = [i for i in c['intents'] if i not in table]
        if bad:
            raise SystemExit('confusion %s names non-label intents: %s' % (c['id'], bad))
        c = dict(c, access={i: table[i]['access_mode'] for i in c['intents']},
                 sources={i: intents[i]['schema_source'] if i in intents else tool_sources(cat, i, files)
                          for i in c['intents']})
        confusions.append(c)
        for i in c['intents']:
            if i in intents:
                intents[i].setdefault('confusions', []).append(c['id'])
    head = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    stats = dict(read_intents=len(intents),
                 with_must_ask=sum(bool(v['must_ask']) for v in intents.values()),
                 with_defaults=sum(bool(v['defaults']) for v in intents.values()),
                 with_no_params=sum(not (v['must_ask'] or v['defaults'] or v['optional']) for v in intents.values()),
                 confusions=len(confusions))
    return dict(
        version=version, batch='INTENT-DISTILL-20261111', generated_by='scripts/finetune/build_clarify_rubric.py',
        source_head=head,
        purpose=('Luna 与 Terra 各自独立依据本标准判断 NEED_CLARIFY；只读意图的参数来自 Java Tool 源码。老师一致仍只是银标。' if version == 1 else
                 'v3（Steve 口径）：只有缺了必填且无合理默认、或对象确实指不定时才 NEED_CLARIFY，其余按默认执行；老师一致仍只是银标。'),
        rules=RULES if version == 1 else RULES_V3, confusions=confusions, stats=stats, intents=intents)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true')
    ap.add_argument('--version', type=int, choices=[1, 3], default=1)
    a = ap.parse_args()
    rub = build(a.version)
    OUT = globals()['OUT'] if a.version == 1 else OUT_V3
    body = json.dumps({k: v for k, v in rub.items() if k != 'source_head'}, ensure_ascii=False, indent=1, sort_keys=False)
    if a.check:
        cur = json.loads(OUT.read_text(encoding='utf-8')) if OUT.exists() else {}
        cur.pop('source_head', None)
        same = json.dumps(cur, ensure_ascii=False, indent=1) == body
        print('RUBRIC_FRESH' if same else 'RUBRIC_STALE')
        return 0 if same else 1
    OUT.write_text(json.dumps(rub, ensure_ascii=False, indent=1) + '\n', encoding='utf-8', newline='\n')
    print('WROTE', OUT, json.dumps(rub['stats'], ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
