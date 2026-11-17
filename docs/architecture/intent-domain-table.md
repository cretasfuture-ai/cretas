# 意图业务域分类表（catalog_version 2026-11-18.1）

意图蒸馏第③步业务域维度（方案第 2 节第 1 层）。#1772 建表与覆盖闸；PR-4 加次业务域、业务词并接进识别路径（见第 7 节）。

- 资源：`backend/java/cretas-api/src/main/resources/ai/intent-domain-table.json`
- 生成脚本：`scripts/ai/generate_intent_domain_table.py`（规则 + 人工清单都在脚本里；改脚本后重跑，`--check` 验证资源没过期）
- Python 同步副本：`backend/python/ai/data/intent-domain-table.json`（生成脚本同时写两份，逐字节相同由 `test_intent_domain_scope_contract.py` 钉住）
- CI 闸：`IntentDomainTableContractTest`（命中 fast-contracts 的 `*ContractTest`）；缩小规则闸 `backend/python/tests/test_intent_domain_scope_contract.py`

## 1. 意图目录的唯一真源

Java 资源 `ai/factory-intent-catalog.json` 的 `canonical_intents`（Python `backend/python/ai/factory_catalog.py` 读它的同步副本 `backend/python/ai/data/factory-intent-catalog.json`，不是第二个真源）。快照 2026-11-04，**在用平台意图 270 条**：259 条绑定 Tool（256 个不同 tool_name），11 条绑定 Skill。已退役 81 条、别名 1 条（`EFFICIENCY_COMPARE_ALIAS` -> `EFFICIENCY_COMPARISON`）不在表内，按 canonical 码建表。

范围边界：工厂自建意图（`factory_id` 非空的数据库行）不在目录里，本表不覆盖；PR-4 对「查不到业务域」的意图应走语义召回兜底，而不是当成某个域。

## 2. 枚举

业务域（12 个，上限 14）：

| 代码 | 名称 | 范围 | 意图数 |
|---|---|---|---|
| QUALITY | 质检与食安 | 质检/不良/处置/放行、HACCP、SSOP、添加剂合规、留样、召回 | 39 |
| WAREHOUSE | 仓储库存 | 原料与成品批次、入库/领用/盘点/调拨、库存预警、收货、周转耗材 | 37 |
| PRODUCTION | 生产 | 生产计划/批次/工序任务/排产/产能/生产效率与出成率、产品主数据 | 33 |
| SALES | 销售与客户 | 客户/商机/销售订单/发货出货/退货/客户跟进/销售分析 | 33 |
| FINANCE | 财务与成本 | 会计科目/三表/期间关账/开票收款/应收/成本与毛利分析 | 25 |
| SYSTEM | 系统与其它 | 账号权限/系统设置/审批待办/打印/提醒/对话控制/治理/域外与拒答 | 24 |
| EQUIPMENT | 设备与IoT | 设备告警/电子秤/摄像头/冷链设备/设备维护 | 21 |
| PROCUREMENT | 采购与供应商 | 供应商/资质/采购订单/请购/采购价格 | 19 |
| HR | 人事考勤 | 考勤打卡/请假加班报销/工资策略与工资成本 | 18 |
| TRACE | 溯源 | 批次溯源/全链路追溯/公开溯源/源头个体/业务链路 | 9 |
| ANALYTICS | 经营分析 | 跨域经营总览/KPI/趋势/异常/指标中心 | 8 |
| RND | 研发与样品 | 研发需求/样品/样品转 BOM | 4 |

合计 270。每个意图只有一个**主**业务域；跨域的在「存疑清单」里写明另一个候选域。

动作类型（6 个）：QUERY 查 111、ANALYZE 分析 60、CREATE 建 48、UPDATE 改 41、APPROVE 审批 10、DELETE 删 0。目录里没有任何删除类意图（取消订阅、关单、作废都按「改」），DELETE 先保留在枚举里，将来出现删除意图时不用改契约。

动作类型是**用户语义**，不是工具读写属性。读写与权限以 `ToolAccessModes` 为准，两者不一致的（申请开票、改密码等）在存疑清单里列出，PR-4 不得用动作类型替代写操作确认闸。

### 域 x 动作

| 域 | 查 | 分析 | 建 | 改 | 审批 |
|---|---|---|---|---|---|
| PRODUCTION | 9 | 8 | 4 | 12 | 0 |
| WAREHOUSE | 11 | 4 | 12 | 9 | 1 |
| QUALITY | 22 | 6 | 7 | 3 | 1 |
| SALES | 16 | 8 | 6 | 2 | 1 |
| PROCUREMENT | 9 | 5 | 3 | 0 | 2 |
| FINANCE | 4 | 14 | 4 | 1 | 2 |
| HR | 10 | 4 | 3 | 0 | 1 |
| EQUIPMENT | 9 | 4 | 2 | 6 | 0 |
| TRACE | 9 | 0 | 0 | 0 | 0 |
| RND | 0 | 0 | 3 | 0 | 1 |
| ANALYTICS | 2 | 6 | 0 | 0 | 0 |
| SYSTEM | 10 | 1 | 4 | 8 | 1 |

## 3. 判定规则

判定顺序：规则优先，规则判不了或判错的才进脚本里的人工清单（每条带理由）；规则和人工清单都没命中，生成脚本 rc=1，不会静默兜底。

业务域规则（按意图码正则，先命中先赢，脚本里 `DOMAIN_RULES`）：

| 规则 | 意图码前缀 | 域 |
|---|---|---|
| D01 | `ATTENDANCE_` `CLOCK_IN/OUT` `HR_` `WAGE_` | HR |
| D02 | `TRACE_` `LINEAGE_` `SOURCE_INDIVIDUAL_` `BATCH_FULL_TRACE` `BATCH_TRACE_BY_CUSTOMER` `BUSINESS_LINK_` | TRACE |
| D03 | `QUALITY_` `HACCP_` `ADDITIVE_` `SSOP_` `FOOD_SAMPLE_` `FOOD_SAFETY_` `RELEASE_DECISION` `RECALL_` `REGULATORY_REPORT` `CUSTOMER_QUALITY_STANDARD` `RECEIVE_QUALITY_CHECK_` | QUALITY |
| D04 | `ALERT_` `CAMERA_` `SCALE_` `EQUIPMENT_` `COLD_CHAIN_` | EQUIPMENT |
| D05 | `RD_` `SAMPLE_TO_BOM` | RND |
| D06 | `FINANCE_` `FINANCIAL_` `ACCOUNT_` `ACCOUNTS_` `BALANCE_SHEET_` `INCOME_STATEMENT_` `CASHFLOW_` `PERIOD_` `TAX_GROUP_` `MONTHLY_FINANCIAL_` `COST_QUERY` `SKU_GROSS_MARGIN` `REPORT_(FINANCE/COST_/BOM_COST_/LABOR_COST_/SALES_MARGIN_)` | FINANCE |
| D07 | `SUPPLIER_` `PURCHASE_` `PROCUREMENT_` `REQUISITION_CREATE` `PRICE_HISTORY_` | PROCUREMENT |
| D08 | `MATERIAL_` `INVENTORY_` `RECEIVE_` `FREE_GIFT_` `REUSABLE_CONTAINER_` `TRANSFER_` `ABACA_` `WAREHOUSE_` `FACTORY_MR_` `STOCK_ALERT` `REPORT_INVENTORY` | WAREHOUSE |
| D09 | `CUSTOMER_` `ORDER_` `SALES_` `SHIPMENT_` `SPRINT10_SHIPMENT_` `OPPORTUNITY_` `DAILY_CUSTOMER_` `CALL_FOLLOWUP_` `WECHAT_` `RETURN_ORDER_` `COMMISSION_` `PRODUCT_PRODUCT_SALES_` | SALES |
| D10 | `WORK_PROCESS_` `PROCESS_TASK_` `PRODUCTION_` `BATCH_` `PLAN_UPDATE` `SCHEDULING_` `BOM_` `PROCESSING_` `EFFICIENCY_` `REPORT_(EFFICIENCY/PRODUCTION/BATCH_YIELD_RANK)` `PRODUCT_` `CONVERSION_RATE_` | PRODUCTION |
| D11 | `REPORT_` `INDICATOR_` `SMART_INDICATOR_`（其余落入的跨域报表） | ANALYTICS |
| D12 | `SYSTEM_` `FACTORY_CONFIG_` `FACTORY_NOTIFICATION_` `GOVERNANCE_` `CONTEXT_` `OUT_OF_DOMAIN` `PAGINATION_` `UNSUPPORTED_` `APPROVAL_` `RULE_CONFIG` `WORKFLOW_` `OPERATION_LOG_` `PRINT_` `REMINDER_` | SYSTEM |

结果：规则直接判定 241 条，人工清单覆盖 29 条（例如 `REPORT_QUALITY` 前缀会落到经营分析，人工改归质检；`ORDER_STATUS` 旧分类 DATA_OP 但实为销售订单）。判定对象是意图码和描述，不用旧 `intent_category` 当依据（见第 4 节为什么）。

动作类型规则（`ACTION_RULES`，先命中先赢）：A01 审批（`_APPROVE` 等）-> A02 建（`_CREATE` `_REGISTER` `_GENERATE` `_LOG` `_RECORD` `_SUBMIT` `_INBOUND` `_SPAWN` 等）-> A03 改（`_UPDATE` `_ADJUST` `_CONFIG` `_ACKNOWLEDGE` `_RESOLVE` `_CLOSE` `_START` `_COMPLETE` `_ASSIGN` `_FREEZE` 等）-> A04 分析（`_STATS` `_ANALYSIS` `_RANK` `_TREND` `_COMPARISON` `_AGING` `_FORECAST` `_RECOMMEND` `_DIAGNOSE` `REPORT_` 等）-> A05 查（`_QUERY` `_LIST` `_STATUS` `_SEARCH` `_ACTIVE` `_LOOKUP` `_TODAY` `TRACE_` 等）。规则直接判定 233 条，人工 37 条。

「分析」与「查」的界线：列表、详情、状态、清单是查；排行、趋势、预测、对比、统计汇总、诊断、建议、报表是分析。名字叫 QUERY 但实为聚合排序的（如 `CUSTOMER_PRIORITY_QUERY`）按分析。

无业务动作的对话控制类（`OUT_OF_DOMAIN` `UNSUPPORTED_CAPABILITY` `PAGINATION_PREVIOUS` `CONTEXT_CONTINUE`）动作按「查」兜底，域为 SYSTEM。给 PR-4 的建议：SYSTEM 与 ANALYTICS 两个域的候选意图不应因为「域已确定」被排除，它们是跨域通用入口。

## 4. 与旧 intent_category 的对照

旧 `intent_category` 在目录里有 26 种取值（库里全量是 32 种），动作（QUERY/DATA_OP/WRITE）、业务域（HR/QUALITY/MATERIAL）、功能形态（REPORT/WORKDESK/SMARTBI）混在一起，同一个意图的旧分类常常只反映它是哪个 Sprint 加的。下表是旧分类落入新业务域的分布，说明旧分类不能直接当业务域用：

| 旧 intent_category | 条数 | 落入的业务域（条数） |
|---|---|---|
| QUERY | 41 | QUALITY 12、TRACE 5、PROCUREMENT 5、SALES 4、ANALYTICS 4、FINANCE 3、EQUIPMENT 2、WAREHOUSE 2、PRODUCTION 2、SYSTEM 1、HR 1 |
| DATA_OPERATION | 33 | WAREHOUSE 11、SALES 5、FINANCE 4、PRODUCTION 3、RND 3、HR 2、PROCUREMENT 2、QUALITY 2、SYSTEM 1 |
| DATA_OP | 26 | PRODUCTION 5、QUALITY 5、WAREHOUSE 5、SALES 4、FINANCE 3、PROCUREMENT 3、SYSTEM 1 |
| REPORT | 20 | FINANCE 7、PRODUCTION 5、ANALYTICS 4、HR 1、SALES 1、WAREHOUSE 1、QUALITY 1 |
| DATA_QUERY | 19 | PRODUCTION 6、HR 4、QUALITY 4、WAREHOUSE 3、SALES 1、SYSTEM 1 |
| SYSTEM | 19 | SYSTEM 16、PRODUCTION 3（排产模式开关） |
| ANALYSIS | 15 | SALES 5、FINANCE 4、WAREHOUSE 2、QUALITY 2、PRODUCTION 1、HR 1 |
| CRM | 14 | SALES 7、PROCUREMENT 6（供应商）、TRACE 1 |
| MATERIAL | 12 | WAREHOUSE 12 |
| WORKDESK | 10 | QUALITY 3、SALES 2、EQUIPMENT 1、FINANCE 1、PRODUCTION 1、PROCUREMENT 1、WAREHOUSE 1 |
| QUALITY | 10 | QUALITY 10 |
| HR | 9 | HR 9 |
| ALERT | 8 | EQUIPMENT 8 |
| PRODUCTION | 6 | PRODUCTION 6 |
| SHIPMENT | 6 | SALES 3、TRACE 3（`TRACE_*` 旧分类在 SHIPMENT 下） |
| SCALE | 4 | EQUIPMENT 4 |
| CAMERA | 3 | EQUIPMENT 3 |
| WRITE | 3 | EQUIPMENT 2、SALES 1 |
| CONFIG | 3 | PRODUCTION 1、EQUIPMENT 1、SYSTEM 1 |
| FINANCE | 3 | FINANCE 3 |
| DOCUMENT_OPERATION / PRINT / CONFIG_OPERATION | 1 + 1 + 1 | SYSTEM 3 |
| PROCUREMENT / SMARTBI | 1 + 1 | PROCUREMENT 2（`SUPPLIER_PRICE_ALERT` 旧分类 SMARTBI） |
| AI_GENERATE | 1 | RND 1 |

条数最多的 QUERY、DATA_OPERATION、DATA_OP、REPORT、DATA_QUERY、ANALYSIS、CRM、WORKDESK 8 类各自横跨 3 个以上业务域，合计 178 条（占 66%）；与新业务域基本一一对应的只有 MATERIAL、QUALITY、HR、ALERT、PRODUCTION、SCALE、CAMERA、FINANCE 等小类。

## 5. 存疑条目（52 条，占 19%）

标注 `uncertain: true` 的行是判断题，不是规则能定的。表里已经取了一个主域，另一个候选域写在 `uncertain_note`，PR-4 起同时写进可选字段 `secondary_domain`（35 条：存疑点写明了另一个域的行；其余 17 条只关于动作或对话控制，没有次域；「生产 vs 采购 vs 仓储」的 `SHORTAGE_ANALYSIS` 只取采购）。识别时主域或次域命中都算；有争议的不只是域，也有动作（多动作合一的审批单、名为查实为分析的）和「动作与工具读写不一致」。**请 organizer / Steve 逐条或按类批量确认**，建议按类定口径（括号内是我的默认）：

1. 冷链报警/温度（设备）vs 食安；供应商食品资质（采购）vs 食安
2. 成本类三表（财务）vs 生产；工资成本（人事）vs 财务；提成（销售）vs 财务
3. 退货单（销售）vs 采购；物料需求单（仓储）vs 生产；产品主数据（生产）vs 销售

| 意图码 | 当前业务域 | 动作 | 存疑点 |
|---|---|---|---|
| BUSINESS_LINK_QUERY | TRACE | QUERY | 业务单跨域关联：溯源 vs 系统 |
| CAMERA_UNSUBSCRIBE | EQUIPMENT | UPDATE | 取消订阅是否算删：按改处理 |
| COLD_CHAIN_ALERT_ACKNOWLEDGE | EQUIPMENT | UPDATE | 冷链报警：设备 vs 质检与食安 |
| COLD_CHAIN_ALERT_ACTIVE | EQUIPMENT | QUERY | 冷链报警：设备 vs 质检与食安 |
| COLD_CHAIN_MONITORING | EQUIPMENT | QUERY | 冷链温控 skill：设备 vs 质检与食安（GB 14881） |
| COLD_CHAIN_TEMP_QUERY | EQUIPMENT | QUERY | 冷链温度：设备 vs 质检与食安 |
| COMMISSION_PENDING_TOTAL | SALES | ANALYZE | 待付提成汇总：销售 vs 财务 |
| CONTEXT_CONTINUE | SYSTEM | QUERY | 无业务动作，按查询兜底 |
| CONVERSION_RATE_UPDATE | PRODUCTION | UPDATE | 原材料转换率配置：生产 vs 仓储 |
| COST_QUERY | FINANCE | ANALYZE | 成本数据：财务 vs 生产 |
| CUSTOMER_NOTIFY_BATCH | QUALITY | CREATE | 召回通知客户：质检与食安 vs 销售与客户 |
| CUSTOMER_PRIORITY_QUERY | SALES | ANALYZE | 名为查询，实为聚合排序，取分析 |
| FACTORY_MR_CLOSE | WAREHOUSE | UPDATE | 物料需求单（备料）：仓储 vs 生产 |
| FACTORY_MR_GENERATE | WAREHOUSE | CREATE | 物料需求单（备料）：仓储 vs 生产 |
| FACTORY_MR_QUERY_PENDING | WAREHOUSE | QUERY | 物料需求单（备料）：仓储 vs 生产 |
| FINANCE_INVOICE_REQUEST | FINANCE | CREATE | 动作与工具读写不一致：语义建、工具只读 |
| FOOD_SAMPLE_TRACE | QUALITY | QUERY | 按批次列留样：质检与食安 vs 溯源 |
| GATE_DIAGNOSE_BATCH | WAREHOUSE | ANALYZE | 批次被闸拦原因：仓储 vs 质检与食安 |
| GOVERNANCE_SKILL_MANAGE | SYSTEM | UPDATE | 含列表/搜索/启停多种动作，取改 |
| INVENTORY_FREEZE | WAREHOUSE | UPDATE | 召回 SOP 的冻结库存：仓储 vs 质检与食安 |
| OUT_OF_DOMAIN | SYSTEM | QUERY | 无业务动作，按查询兜底 |
| PAGINATION_PREVIOUS | SYSTEM | QUERY | 无业务动作，按查询兜底 |
| PDA_SCAN_TASK_GENERATE | WAREHOUSE | CREATE | PDA 扫码任务：仓储 vs 系统 |
| PERIOD_CONFIRM_CLOSE | FINANCE | APPROVE | 确认关账：审批 vs 改 |
| PRODUCT_CREATE_FLYWHEEL | PRODUCTION | CREATE | 产品主数据：生产 vs 销售 |
| PRODUCT_TYPE_QUERY | PRODUCTION | QUERY | 产品主数据：生产 vs 销售 |
| PRODUCT_UPDATE | PRODUCTION | UPDATE | 产品主数据：生产 vs 销售 |
| PURCHASE_FINANCE_APPROVE | PROCUREMENT | APPROVE | 采购单的财务审核环节：采购 vs 财务 |
| PURCHASE_ORDER_APPROVE | PROCUREMENT | APPROVE | 含提交/审批/取消多种动作，取审批 |
| RECEIVE_QUALITY_CHECK_TODAY | QUALITY | QUERY | 今日待质检批次（提醒仓管通知质量主管）：质检 vs 仓储 |
| REPORT_ANOMALY | ANALYTICS | ANALYZE | 异常问题统计跨域，归经营分析是权宜之计 |
| REPORT_BOM_COST_RANK | FINANCE | ANALYZE | BOM 成本排行：财务 vs 生产 |
| REPORT_LABOR_COST_SHARE | FINANCE | ANALYZE | 人工成本占比：财务 vs 人事 |
| REPORT_COST_VARIANCE | FINANCE | ANALYZE | 成本差异：财务 vs 生产 |
| RETURN_ORDER_APPROVE | SALES | APPROVE | 含提交/审批/拒绝/完成多种动作，取审批 |
| RETURN_ORDER_CREATE | SALES | CREATE | 退货单含采购退货与销售退货：销售 vs 采购 |
| RETURN_ORDER_LIST | SALES | QUERY | 退货单含采购退货与销售退货：销售 vs 采购 |
| SCHEDULING_LIST | PRODUCTION | QUERY | 排班计划列表：生产（排产）vs 人事（班次排班） |
| SHORTAGE_ANALYSIS | PRODUCTION | ANALYZE | 销售订单缺料分析：生产 vs 采购 vs 仓储 |
| SMART_INDICATOR_QUERY | ANALYTICS | ANALYZE | 指标 skill，查与分析皆可，取分析 |
| STOCK_ALERT | WAREHOUSE | QUERY | 低库存预警文案面向采购员：仓储 vs 采购 |
| SUPPLIER_QUALIFICATION_ALERT | PROCUREMENT | QUERY | 供应商食品资质：采购 vs 质检与食安 |
| SUPPLIER_QUALIFICATION_CREATE | PROCUREMENT | CREATE | 供应商食品资质：采购 vs 质检与食安 |
| SUPPLIER_QUALIFICATION_LOOKUP | PROCUREMENT | QUERY | 供应商食品资质：采购 vs 质检与食安 |
| SUPPLIER_QUALIFICATION_QUERY_EXPIRING | PROCUREMENT | QUERY | 供应商食品资质：采购 vs 质检与食安 |
| SYSTEM_PASSWORD_RESET | SYSTEM | UPDATE | 动作与工具读写不一致：语义改、工具只引导 |
| SYSTEM_PROFILE_EDIT | SYSTEM | UPDATE | 动作与工具读写不一致：语义改、工具只引导 |
| SYSTEM_SETTINGS | SYSTEM | UPDATE | 动作与工具读写不一致：语义改、工具只引导 |
| TAX_GROUP_INVOICE | FINANCE | CREATE | 动作与工具读写不一致：语义建、工具只读 |
| TRANSFER_APPROVE | WAREHOUSE | APPROVE | 含提交/审批/发货/签收多种动作，取审批 |
| UNSUPPORTED_CAPABILITY | SYSTEM | QUERY | 无业务动作，按查询兜底 |
| WAGE_COST_SUMMARY | HR | ANALYZE | 工资成本汇总：人事 vs 财务 |

## 6. 与 PR-4 的衔接和维护

- 新增/改名/退役意图：先改目录 `factory-intent-catalog.json`，再改生成脚本（规则或人工清单）并重跑；闸会在目录与本表不一致（缺行/多行/缺域/缺动作）时变红。
- 版本：`catalog_version` 是本表版本（日期 + 序号），每次改标签就升一位；PR-4 把它随 `visible_intents` 下发时用它做目录版本。
- 目录里的意图中文名/描述有不少是 Sprint 编号式的内部文案，本表不依赖它们，只以意图码、Tool 名和描述里的业务含义判定。
- 本表没有用到的信息：`ai_intent_configs` 的 `sensitivity_level`、`required_roles`。权限与确认仍按现有链路走，业务域不授权任何东西。
- 已知不覆盖：工厂自建意图不在表里（见第 1 节）；库里 32 种 `intent_category` 中目录只用到 26 种，其余 6 种本片未核对来源。

## 7. 接线（PR-4）

- **在哪收窄**：Python 决策层 `ai/orchestrator.py::_match_decision`，在精确短语表之后、模型粗选 / 合并理解之前。规则在 `ai/intent_domain.py`，决策层入口 `ai/matcher/decision.py::domain_scope`。只换「粗选 / 合并理解提示词里列哪些意图」：精确短语表、语义召回补进细选的候选不受业务域限制，Java 侧的权限、写操作确认、审批、竞品拒答（`OwnFactoryDataOnlyGuard`）、带意图码先判审批全部照旧。
- **怎么判域**：表里每个业务域的 `keywords`（生成脚本 `KEYWORD_DOMAINS`，一个词可以牵几个相邻域，如「出库」= 仓储 + 销售 + 质检 + 生产），整句子串命中取并集。SYSTEM / ANALYTICS 不设词。
- **收窄后没判出来就全量重判**：只有粗选 / 合并理解真用上了缩小后的列表，且结论是「以上都不是」「先问清楚 / 置信不够出按钮」或决策层没答出来时，用全部可见意图再判一次（痕迹 `domainScope=narrowed_retry_wide`，结果 `timingMs.domainRetryWide=1`）。域判错不能静默答成「没有这个功能」。说话目的不是「现在办」或明确取消的不重判。
- **兜底（宁可放宽）**：表外意图（工厂自建）永远留下；SYSTEM / ANALYTICS 主域或次域的意图永远留下；判不出域、命中超过 4 个域、带对话历史或任务状态、Java 没带或带了不同的 `catalogVersion`、表读不了 ⇒ 一律不收窄。
- **版本握手**：Java `IntentDomainTable.catalogVersion()` 随识别请求下发 `catalogVersion`；Python 只在与自己副本版本相同时收窄。Java 与 Python 分开发布，先发哪个都不会收窄错。
- **决策层输出**：带了表版本的请求，决策痕迹（`decisionTrace`，随结果回 Java 进飞轮）多 `catalogVersion` / `domainScope` / `domains` / `domainCandidates` / `domain` / `secondaryDomain`；收窄时 `optionSetHash` 按缩小后的候选重算。不带表版本的老请求痕迹逐字不变。
- **开关**：Python 环境变量 `AI_DOMAIN_SCOPE`（默认开，`0` 关），关了不重发即可回到全目录（痕迹 `domainScope=disabled`）。
- **痕迹只写真用上的**：重排路不调粗选，记 `narrowed_unused`，不换 `optionSetHash`；决策层没答出来回落旧路径记 `narrowed_legacy_fallback` 并把 `optionSetHash` 换回全部可见意图。
- **版本与内容绑定**：生成脚本里 `CONTENT_SHA256` 钉住本版表内容的摘要；改了规则 / 人工清单 / 业务词 / 次域而不升 `CATALOG_VERSION`，脚本（含 `--check`）rc=1。
- **业务域不授权任何东西**：动作类型、业务域都不进任何闸。
- **改词的口径**：v3 开发半边的候选保留率（金标仍在缩小后的候选里）必须是 100%，另用 Steve 金标 300 题核对；封存半边不碰。读数与准确率前后对比见 PR 描述，复现脚本 `tests/semantic-read/domain_scope_eval.py`。
