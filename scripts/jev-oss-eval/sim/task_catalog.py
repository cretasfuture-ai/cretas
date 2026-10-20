"""模拟工厂的角色 / 人数 / 日常任务与频率 —— 整个模拟里最关键的假设, 改这里就改了「现实」。

每个任务:
  intent  prod ai_intent_configs 的 intent_code (金标)
  alt     也算对的意图 (同一件事的等价意图; 绑同一 tool 的会在评测时自动并入)
  rate    每人每个工作日平均说几次
  want    给生成器看的「这个人想干什么」
  slots   允许出现的参数占位符 (由程序用 F006 词表填)
  kind    read / write / consult / oos
数值是估计, 不是实测 —— 当前没有真实客户流量可校准 (见 memory project_factory_ai_intent_has_almost_no_real_traffic)。
"""
from __future__ import annotations

ROLES = {
    # role: (人数, 说明)
    "operator": (6, "车间操作工，文化程度一般，说话口语化、很短，常用语音输入"),
    "warehouse": (3, "仓管员，年纪偏大，说话直接，常带数量和物料名"),
    "qc": (2, "质检员，说话相对规范，会提批次号"),
    "prod_mgr": (2, "生产主管/调度，关心进度、产能、人员、缺料"),
    "boss": (1, "老板或厂长，关心经营数字、订单、发货、毛利"),
    "purchaser": (1, "采购员，关心缺什么料、价格、供应商、下单"),
    "sales": (1, "销售/发货员，关心订单、发货、客户"),
}

T = dict  # 便于阅读

TASKS = [
    # ---------------- 通用 (所有人) ----------------
    T(id="clock_in", roles="*", intent="CLOCK_IN", rate=1.0, kind="write", want="上班签到打卡", slots=[]),
    T(id="clock_out", roles="*", intent="CLOCK_OUT", rate=1.0, kind="write", want="下班签退打卡", slots=[]),
    T(id="leave_balance", roles="*", intent="HR_LEAVE_BALANCE_QUERY", rate=0.05, kind="read", want="问自己还剩几天年假或调休", slots=[]),
    T(id="leave_submit", roles="*", intent="HR_LEAVE_SUBMIT", rate=0.04, kind="write", want="请假（事假/病假/调休），说明哪天", slots=["{日期}"]),
    T(id="help", roles="*", intent="SYSTEM_HELP", rate=0.08, kind="consult", want="问这个系统/AI 怎么用、某个功能在哪、能干什么", slots=[]),
    # ---------------- 咨询 (知识 / 标准 / 故障) ----------------
    # FOOD_KNOWLEDGE_QUERY 不在 prod ai_intent_configs 里, 是 Java 特判的虚拟意图 (路由到食品知识库, 见
    # IntentExecutionOrchestrator ~L1431); 校验时作为已知例外放行。
    T(id="food_knowledge", roles=["qc", "prod_mgr", "boss", "warehouse", "operator"], intent="FOOD_KNOWLEDGE_QUERY", rate=0.25, kind="consult",
      want="问食品安全标准、工艺要求或法规（如解冻、留样多久、微生物指标、消毒液浓度、冷库温度要求、生熟分开）", slots=["{物料}"]),
    T(id="additive", roles=["qc"], intent="ADDITIVE_SMART_MATCH", rate=0.2, kind="consult", want="问某种添加剂在产品里最多能加多少、合不合规", slots=["{产品}"]),
    T(id="scale_trouble", roles=["operator", "warehouse"], intent="SCALE_TROUBLESHOOT", alt=["SCALE_CALIBRATE"], rate=0.1, kind="consult", want="电子秤读数不准、乱跳或者没数，怎么办/怎么校准", slots=[]),
    T(id="chitchat", roles="*", intent="OUT_OF_DOMAIN", rate=0.03, kind="oos", want="跟工作无关的闲聊、问天气、问候、开玩笑", slots=[]),
    # ---------------- 操作工 ----------------
    T(id="task_start", roles=["operator"], intent="WORK_PROCESS_TASK_START", rate=2.0, kind="write", want="开始做某道工序（开工）", slots=["{工序}", "{产品}"]),
    T(id="task_complete", roles=["operator"], intent="WORK_PROCESS_TASK_COMPLETE", rate=3.0, kind="write", want="报工：某道工序做完了，报产量", slots=["{工序}", "{产品}", "{数量}{单位}"]),
    T(id="my_tasks", roles=["operator"], intent="PROCESS_TASK_QUERY", alt=["PROCESS_TASK_SUMMARY"], rate=1.0, kind="read", want="问今天自己要做哪些工序任务", slots=[]),
    T(id="consume_adjust", roles=["operator"], intent="BATCH_CONSUMPTION_ADJUST", rate=0.3, kind="write", want="说这批实际多用/少用了多少料", slots=["{物料}", "{数量}{单位}"]),
    T(id="ssop_done", roles=["operator"], intent="SSOP_EXECUTE_COMPLETE", rate=0.5, kind="write", want="车间/设备清洁消毒做完了，要签字确认", slots=["{设备}"]),
    T(id="attendance_today", roles=["operator"], intent="ATTENDANCE_TODAY", alt=["ATTENDANCE_STATUS"], rate=0.2, kind="read", want="查自己今天打卡了没、上了几小时班", slots=[]),
    # ---------------- 仓管 ----------------
    T(id="today_receiving", roles=["warehouse"], intent="MATERIAL_TODAY_RECEIVING_QUERY", alt=["WAREHOUSE_KEEPER_TODAY_TASKS", "WAREHOUSE_TODAY_RECEIVING_PENDING"], rate=2.0, kind="read", want="问今天要收什么货、哪些货还没到", slots=[]),
    T(id="receive", roles=["warehouse"], intent="RECEIVE_WITH_LIMIT", alt=["RECEIVE_CONFIRM_CREATE", "MATERIAL_BATCH_CREATE"], rate=4.0, kind="write", want="收货入库，说物料和数量（有时带供应商或采购单号）", slots=["{物料}", "{数量}{单位}", "{供应商}", "{单号}"]),
    T(id="stock_query", roles=["warehouse", "prod_mgr"], intent="MATERIAL_BATCH_QUERY", alt=["REPORT_INVENTORY"], rate=3.0, kind="read", want="查某种物料还有多少库存、还剩几批", slots=["{物料}"]),
    T(id="issue_out", roles=["warehouse"], intent="MATERIAL_BATCH_USE", rate=3.0, kind="write", want="领料出库给车间，说物料和数量", slots=["{物料}", "{数量}{单位}"]),
    # 清洗发现: 仓管说「X 快没了」多是针对单个物料 ⇒ 查该物料库存也算对 (见 filter_report.json)
    T(id="low_stock", roles=["warehouse"], intent="MATERIAL_LOW_STOCK_ALERT", alt=["STOCK_ALERT", "MATERIAL_BATCH_QUERY"], rate=0.5, kind="read", want="问哪些料快用完了要补", slots=[]),
    T(id="expiring", roles=["warehouse", "qc"], intent="MATERIAL_EXPIRING_ALERT", rate=0.5, kind="read", want="问哪些原料快过期了", slots=[]),
    T(id="stock_adjust", roles=["warehouse"], intent="MATERIAL_ADJUST_QUANTITY", rate=0.2, kind="write", want="盘点后把某批物料库存数量改成实际数", slots=["{物料}", "{批次号}", "{数量}{单位}"]),
    T(id="crate_out", roles=["warehouse", "sales"], intent="REUSABLE_CONTAINER_SHIP_OUT", rate=0.8, kind="write", want="随货发出周转框给客户，说个数", slots=["{客户}", "{个数}"]),
    T(id="crate_back", roles=["warehouse"], intent="REUSABLE_CONTAINER_RETURN_IN", rate=0.5, kind="write", want="客户还回来周转框，入库", slots=["{客户}", "{个数}"]),
    T(id="disposal_advice", roles=["warehouse"], intent="MATERIAL_DISPOSAL_RECOMMENDATION", rate=0.1, kind="consult", want="问快过期的料该怎么处理比较好", slots=["{物料}"]),
    # ---------------- 质检 ----------------
    T(id="qc_today", roles=["qc"], intent="RECEIVE_QUALITY_CHECK_TODAY", rate=1.0, kind="read", want="问今天哪些到货/批次还没做质检", slots=[]),
    T(id="qc_create", roles=["qc"], intent="QUALITY_CHECK_CREATE", alt=["QUALITY_CHECK_EXECUTE"], rate=2.0, kind="write", want="给某个批次登记一条质检记录（合格/合格率）", slots=["{批次号}", "{产品}"]),
    T(id="defect_record", roles=["qc"], intent="QUALITY_DEFECT_RECORD", rate=1.0, kind="write", want="登记不合格品：什么问题、几个", slots=["{产品}", "{个数}"]),
    T(id="release", roles=["qc"], intent="RELEASE_DECISION", alt=["QUALITY_DISPOSITION_EXECUTE"], rate=1.0, kind="write", want="决定某批放行", slots=["{批次号}"]),
    T(id="rework", roles=["qc"], intent="QUALITY_DISPOSITION_EXECUTE", rate=0.3, kind="write", want="某批不合格要返工或报废", slots=["{批次号}"]),
    T(id="qc_stats", roles=["qc", "prod_mgr", "boss"], intent="QUALITY_STATS", alt=["REPORT_QUALITY", "QUALITY_CHECK_SUMMARY"], rate=0.4, kind="read", want="问这周/这个月质检合格率", slots=[]),
    T(id="sample_create", roles=["qc"], intent="FOOD_SAMPLE_CREATE", rate=1.0, kind="write", want="登记今天的食品留样，说品名和克数", slots=["{产品}", "{克数}"]),
    T(id="sample_dispose_q", roles=["qc"], intent="FOOD_SAMPLE_QUERY_PENDING_DISPOSE", rate=0.5, kind="read", want="问哪些留样到期该销毁了", slots=[]),
    # prod 意图表里的近义重复: COLD_CHAIN_TEMPERATURE / COLD_CHAIN_MONITORING 与 COLD_CHAIN_TEMP_QUERY 是同一件事 (前两者未绑 tool)
    T(id="cold_temp", roles=["qc", "warehouse"], intent="COLD_CHAIN_TEMP_QUERY", alt=["COLD_CHAIN_ALERT_ACTIVE", "COLD_CHAIN_TEMPERATURE", "COLD_CHAIN_MONITORING"], rate=0.4, kind="read", want="问冷库温度正不正常", slots=[]),
    T(id="ssop_today", roles=["qc"], intent="SSOP_EXECUTION_TODAY", alt=["SSOP_DAILY_SANITATION"], rate=0.5, kind="read", want="问今天的清洁消毒任务做了没有", slots=[]),
    T(id="trace", roles=["qc", "boss"], intent="BATCH_FULL_TRACE", alt=["TRACE_FULL", "TRACE_BATCH", "LINEAGE_QUERY"], rate=0.2, kind="read", want="追溯某个批次的原料来源和去向", slots=["{批次号}"]),
    # ---------------- 生产主管 ----------------
    T(id="progress", roles=["prod_mgr", "boss"], intent="PRODUCTION_PROGRESS_DASHBOARD", alt=["PROCESSING_CAPACITY_TODAY", "REPORT_PRODUCTION"], rate=3.0, kind="read", want="问今天生产进度怎么样、做了多少", slots=[]),
    T(id="capacity", roles=["prod_mgr"], intent="PROCESSING_CAPACITY_TODAY", alt=["PRODUCTION_PROGRESS_DASHBOARD"], rate=0.8, kind="read", want="问今天产能、产量、良品数", slots=[]),
    T(id="demand", roles=["prod_mgr"], intent="PRODUCTION_DEMAND_QUERY", alt=["PRODUCTION_DEMAND_ANALYSIS"], rate=1.0, kind="read", want="问有哪些订单还缺产、该生产什么", slots=[]),
    T(id="delivery_warn", roles=["prod_mgr", "sales"], intent="PRODUCTION_DELIVERY_WARN_QUERY", rate=0.5, kind="read", want="问哪些订单快到交期还没做完", slots=[]),
    T(id="batch_create", roles=["prod_mgr"], intent="PRODUCTION_BATCH_CREATE", alt=["PRODUCTION_PLAN_CREATE"], rate=1.0, kind="write", want="新建一个生产批次/安排生产，说产品和计划量", slots=["{产品}", "{数量}{单位}"]),
    T(id="task_assign", roles=["prod_mgr"], intent="WORK_PROCESS_TASK_ASSIGN", rate=1.0, kind="write", want="把某道工序派给某个工人", slots=["{工序}", "{人名}"]),
    T(id="absent", roles=["prod_mgr"], intent="ATTENDANCE_ANOMALY", alt=["ATTENDANCE_TODAY"], rate=0.5, kind="read", want="问今天谁迟到/没来", slots=[]),
    T(id="alerts", roles=["prod_mgr"], intent="ALERT_ACTIVE", alt=["ALERT_LIST"], rate=0.5, kind="read", want="问有没有没处理的设备告警", slots=[]),
    T(id="wip", roles=["prod_mgr"], intent="MATERIAL_BATCH_WIP_QUERY", rate=0.3, kind="read", want="问哪些原料正在生产中占着", slots=[]),
    T(id="consume_q", roles=["prod_mgr"], intent="BATCH_CONSUMPTION_QUERY", rate=0.3, kind="read", want="问这批用了多少料、跟配方比超没超", slots=["{批次号}"]),
    T(id="leave_approve", roles=["prod_mgr"], intent="HR_LEAVE_APPROVE", alt=["APPROVAL_ACTION_EXECUTE"], rate=0.2, kind="write", want="同意/驳回某人的请假", slots=["{人名}"]),
    T(id="bom", roles=["prod_mgr"], intent="BOM_RECIPE_QUERY", rate=0.2, kind="read", want="问某个产品的配方用哪些料", slots=["{产品}"]),
    # ---------------- 老板 ----------------
    T(id="dashboard", roles=["boss"], intent="REPORT_DASHBOARD_OVERVIEW", alt=["REPORT_KPI"], rate=1.0, kind="read", want="问工厂今天整体情况怎么样", slots=[]),
    T(id="margin", roles=["boss"], intent="SKU_GROSS_MARGIN", rate=0.3, kind="read", want="问哪个产品最赚钱、毛利率多少", slots=["{产品}"]),
    T(id="cost", roles=["boss"], intent="COST_QUERY", alt=["COST_TREND_ANALYSIS"], rate=0.3, kind="read", want="问这个月成本、比上个月高在哪", slots=[]),
    T(id="ship_stats", roles=["boss", "sales"], intent="SHIPMENT_STATS", rate=0.5, kind="read", want="问这周/今天发了多少货", slots=[]),
    T(id="order_today", roles=["boss", "sales"], intent="ORDER_TODAY", alt=["ORDER_LIST"], rate=0.5, kind="read", want="问今天有哪些新订单", slots=[]),
    T(id="ar_aging", roles=["boss"], intent="ACCOUNTS_RECEIVABLE_AGING", rate=0.2, kind="read", want="问客户欠款情况、账龄", slots=[]),
    T(id="approvals", roles=["boss", "prod_mgr"], intent="APPROVAL_PENDING_QUERY", alt=["MY_APPROVAL_WORKDESK"], rate=0.5, kind="read", want="问有哪些单子等我审批", slots=[]),
    # ---------------- 采购 ----------------
    T(id="buy_what", roles=["purchaser"], intent="STOCK_ALERT", alt=["MATERIAL_LOW_STOCK_ALERT", "PURCHASER_WEEKLY_PLAN"], rate=1.0, kind="read", want="问该采购什么、哪些料低于安全库存", slots=[]),
    T(id="po_create", roles=["purchaser"], intent="PROCUREMENT_ORDER_CREATE", alt=["REQUISITION_CREATE"], rate=1.0, kind="write", want="给某供应商下采购单，说物料和数量", slots=["{供应商}", "{物料}", "{数量}{单位}"]),
    T(id="price_hist", roles=["purchaser"], intent="PRICE_HISTORY_QUERY", rate=0.5, kind="read", want="问某种料最近的采购价格涨没涨", slots=["{物料}"]),
    T(id="supplier_eta", roles=["purchaser"], intent="SUPPLIER_DELIVERY_ETA", rate=0.3, kind="read", want="问供应商什么时候能到货", slots=["{供应商}"]),
    T(id="qual_expiring", roles=["purchaser"], intent="SUPPLIER_QUALIFICATION_QUERY_EXPIRING", rate=0.1, kind="read", want="问哪些供应商资质快过期", slots=[]),
    # ---------------- 销售/发货 ----------------
    T(id="ship_pending", roles=["sales"], intent="SPRINT10_SHIPMENT_PENDING_TODAY", alt=["SHIPMENT_CONFIRM_CREATE"], rate=1.0, kind="read", want="问今天有哪些单要发货", slots=[]),
    T(id="ship_confirm", roles=["sales"], intent="SHIPMENT_CONFIRM_CREATE", alt=["SHIPMENT_CREATE"], rate=1.0, kind="write", want="确认给某客户发货，说产品和数量", slots=["{客户}", "{产品}", "{数量}{单位}"]),
    T(id="ship_query", roles=["sales"], intent="SHIPMENT_BY_CUSTOMER", alt=["SHIPMENT_QUERY", "SHIPMENT_BY_DATE"], rate=0.5, kind="read", want="查某客户最近的发货记录", slots=["{客户}"]),
    T(id="order_status", roles=["sales"], intent="ORDER_STATUS", alt=["ORDER_FILTER"], rate=0.5, kind="read", want="查某个订单现在什么状态", slots=["{单号}"]),
]
