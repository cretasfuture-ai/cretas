-- 工厂 / 通用意图描述: 去掉开发者字样, 改成一线看得懂的一句话 (澄清按钮的副标题直接显示 description)。
--
-- 依据 (2026-10-06, F006 prod 端到端实测): 澄清按钮上出现
--   「Sprint 8 P4c 质量主管放行前 HACCP 状态简述查询」「Sprint 10 Loop 5 灵魂 Tool — 创建 ProductionBatch」
--   「Sprint 10 Loop 1 — confirm 模式, 接 salesOrderId」 等开发者写法。
-- 范围: is_active 的 FACTORY / COMMON 意图 (餐饮不动)。prod 只读 SELECT 盘点 (2026-10-06):
--   254 条在用意图, 其中 106 条描述带开发者字样 (Sprint/Loop/灵魂/P 编号/R1-R4/Tool/Workdesk/
--   read-only/WRITE+Preview/驼峰字段名/枚举码/状态机箭头), 本文件逐条改写; 5 条按钮标题 (intent_name) 同样带开发者字样。
-- 改写原则: 一句话说清「这个功能做什么」; 保留领域词 (GB 2760 / HACCP / BOM / SC 证 / PO …) 和用户真会说的
--   「适用: …」触发短语; 保留区分近邻意图的那几句 (改写成中文)。描述同时是识别层 (向量 + 重排) 的文本,
--   所以只去开发者字样, 不删业务信息。
-- 幂等 + 不踩手改: 每条 UPDATE 只在 description / intent_name 仍等于上面盘点到的旧文本时才生效;
--   被人手改过的行不动; 重跑是 no-op (新文本 != 旧文本)。不动 tool_name / 权限 / 分类 / 关键词。
-- ⚠️ 识别层的向量缓存在服务重启或每日 04:00 刷新后才用上新描述。
--
-- 故意不改的候选 (登记留痕):
--   FINANCE_PPT_EXPORT: 只含 PPT / PowerPoint 这类业务词, 无开发者字样
--   MATERIAL_FIFO_RECOMMEND: 只含 FIFO 这类业务词
--   REPORT_KPI: 只含 KPI 这类业务词
--   SCALE_DEVICE_DETAIL: 只含 IoT 这类业务词
--   SCALE_LIST_DEVICES: 只含 IoT 这类业务词
--   TRACE_FULL: 箭头 → 表示溯源顺序, 是业务表达, 不是状态转换码
--   UNSUPPORTED_CAPABILITY: 内部路由意图 (拒答), 描述里的 OUT_OF_DOMAIN 是给路由层的区分说明, 不会作为按钮出现

-- ===== 描述改写 (106) =====

-- ABACA_WEIGHT_LOG (记录抄码品称重, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$记录抄码品入库时每箱的实际称重（第几箱、实际重量）。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'ABACA_WEIGHT_LOG' AND deleted_at IS NULL
  AND description = $d$记录抄码品入库时单箱实际称重 (boxIndex + actualWeight)$d$;

-- ACCOUNTS_RECEIVABLE_AGING (应收账龄, COMMON)
UPDATE ai_intent_configs SET
    description = $d$应收账龄分析（分 6 个账龄档），对账龄 60 天以上的高风险款项做标记；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'ACCOUNTS_RECEIVABLE_AGING' AND deleted_at IS NULL
  AND description = $d$应收账龄 6 桶分析, 60+ 天高风险标记. read-only.$d$;

-- ACCOUNT_QUERY (查询会计科目, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询工厂可见的会计科目（含国家会计准则标准科目和工厂自定义科目），可按科目类别和余额方向筛选；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'ACCOUNT_QUERY' AND deleted_at IS NULL
  AND description = $d$查 factory 可见会计科目 (含 GAAP 标准 + factory 自定义), 支持 category / balanceType 过滤. read-only.$d$;

-- ACCOUNT_TREE_LOOKUP (查询会计科目树, COMMON)
UPDATE ai_intent_configs SET
    description = $d$返回工厂可见的完整会计科目树（共 4 级）；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'ACCOUNT_TREE_LOOKUP' AND deleted_at IS NULL
  AND description = $d$返回 factory 可见的完整会计科目树 (parent-child 4 级). read-only.$d$;

-- ADDITIVE_BOM_COMPLIANCE (BOM 添加剂综合校验, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按产品的 BOM 配方逐项核对添加剂用量是否符合 GB 2760-2014 国家标准（用量在上限 80% 以内正常，80%–100% 提醒，超过 100% 超限），只需说明是哪个产品或哪份配方；找不到配方会提示先去配置，只查询不修改数据。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'ADDITIVE_BOM_COMPLIANCE' AND deleted_at IS NULL
  AND description = $d$Sprint 9 P2.F V2 — BOM 反查 + GB 2760-2014 综合合规校验. 操作员只需提供 productTypeId / bomRecipeId, Tool 自动: 查 BomRecipe → 遍历 items → fuzzy match GB 2760 → 算 perKgUsage → 0-80% green / 80-100% yellow / >100% red 综合 report. 防呆: 含 productName + recipeCode + outputQty 上下文, 找不到 BOM 提示去配置页. read-only.$d$;

-- ADDITIVE_CHECK (添加剂限量校验, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按 GB 2760-2014 校验添加剂限量：把配料用量和最大限量比较，标出超限项；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'ADDITIVE_CHECK' AND deleted_at IS NULL
  AND description = $d$GB 2760-2014 添加剂限量校验 — 查 ingredients 跟 max_limit 比较, 标记超限. read-only.$d$;

-- ADDITIVE_COMPLIANCE_QUALITY (放行前添加剂合规判定, COMMON)
UPDATE ai_intent_configs SET
    description = $d$质量主管放行前判断一批产品的添加剂是否合规，列出超限的添加剂并给出能否放行的提示；没有配料信息时会引导去配方页面补充。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'ADDITIVE_COMPLIANCE_QUALITY' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P4c 质量主管放行前添加剂合规判定 (P3 additive_compliance_check 质量语境 wrapper). 输出: compliant + 超限项 + releaseHint. R5 dead-end: 无 ingredients 时引导去 BOM 页面.$d$;

-- ADDITIVE_SMART_MATCH (添加剂智能匹配, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按俗名、英文名、化学名或不完整的 INS 编码，在 GB 2760-2014 里找到对应的添加剂（先精确匹配编码和名称，再匹配别名和相近名称，最多给出 3 个相近的），同一种添加剂涉及的多个食品类目会合并显示；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'ADDITIVE_SMART_MATCH' AND deleted_at IS NULL
  AND description = $d$Sprint 9 P2.F V2 — GB 2760-2014 添加剂智能匹配 (Tool 直接执行). 用户输入俗名/英文/化学名/INS code 不全 → 自动找 GB 2760 entry. 5 级匹配策略: 精确 code → 精确 name → aliases → name LIKE → Levenshtein top-3. 跨食品类目聚合 (一个 additive 多个类目). read-only. Cretas vs HJ 食品垂直差异化护城河 +1.$d$;

-- ALERT_ACTIVE (活跃告警, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$查询当前活跃、未处理的告警，仅指设备或系统的告警事件；库存不足、缺料、补货类预警不属于本意图（那是「低库存预警」）。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'ALERT_ACTIVE' AND deleted_at IS NULL
  AND description = $d$查询当前活跃未处理的告警 仅指设备/系统的活跃告警事件；库存不足、缺料、补货类预警不属于本意图（那是 STOCK_ALERT 库存预警）。$d$;

-- APPROVAL_ACTION_EXECUTE (执行审批操作, COMMON)
UPDATE ai_intent_configs SET
    description = $d$执行审批操作：批准或驳回待审批的单据。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'APPROVAL_ACTION_EXECUTE' AND deleted_at IS NULL
  AND description = $d$Sprint 10 Loop 4 — 执行 APPROVE / REJECT 审批转换$d$;

-- APPROVAL_PENDING_QUERY (待审批列表查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查看当前用户（按角色）名下待审批的流程单据列表。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'APPROVAL_PENDING_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint 10 Loop 4 — 查当前用户 role 待审批 workflow 实例列表$d$;

-- BALANCE_SHEET_QUERY (资产负债表, COMMON)
UPDATE ai_intent_configs SET
    description = $d$生成工厂指定年月的期末资产负债表（按中国会计准则）；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'BALANCE_SHEET_QUERY' AND deleted_at IS NULL
  AND description = $d$生成 factory 指定 (year, month) 期末资产负债表 (中国 GAAP). read-only.$d$;

-- BATCH_FULL_TRACE (批次完整追溯, COMMON)
UPDATE ai_intent_configs SET
    description = $d$批次完整追溯：原料、供应商、加工记录和受影响的客户名单，是食品召回闭环的第二步；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'BATCH_FULL_TRACE' AND deleted_at IS NULL
  AND description = $d$批次完整追溯 — 原料 + 供应商 + 加工记录 + 受影响客户列表. 食品召回闭环 step 2. read-only.$d$;

-- BATCH_TRACE_BY_CUSTOMER (按客户日期追溯批次, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按客户和投诉日期反查对应的产品批次（食品召回的第一步），通过发货记录找出批次号；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'BATCH_TRACE_BY_CUSTOMER' AND deleted_at IS NULL
  AND description = $d$按客户 + 投诉日期反查产品批次 (食品召回首步). 通过 ShipmentRecord 反查 batch_number. read-only.$d$;

-- BOM_RECIPE_QUERY (查询 BOM 配方, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$查询某个产品当前生效的 BOM 配方，包括配方明细、出成率和成本。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'BOM_RECIPE_QUERY' AND deleted_at IS NULL
  AND description = $d$查询某产品当前生效的 BOM 配方, 含主子表完整结构 (主表 + 配方项 + 出成率 + 成本).$d$;

-- BUSINESS_LINK_QUERY (业务链路查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询一张业务单据在各业务环节之间的关联（销售、采购、生产、退货、样品等）：它关联了哪些单据，以及哪些单据关联了它；需说明单据类型和编号。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'BUSINESS_LINK_QUERY' AND deleted_at IS NULL
  AND description = $d$查询业务单跨域关联 (sales/purchase/production/return/sample 等)，返回 outbound (我关联了谁) + inbound (谁关联了我). 输入 ownerType + ownerId.$d$;

-- CALL_FOLLOWUP_PENDING (missed call 跟进, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查最近几天（默认 7 天）的未接来电，用于跟进；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'CALL_FOLLOWUP_PENDING' AND deleted_at IS NULL
  AND description = $d$查近 N 天 MISSED 类型通话, 默认 7 天. read-only.$d$;

-- CASHFLOW_STATEMENT_QUERY (现金流量表, COMMON)
UPDATE ai_intent_configs SET
    description = $d$生成工厂指定期间的现金流量表（直接法，分经营、投资、筹资三类活动）；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'CASHFLOW_STATEMENT_QUERY' AND deleted_at IS NULL
  AND description = $d$生成 factory 期间内现金流量表 (直接法, 三类活动). read-only.$d$;

-- COLD_CHAIN_ALERT_ACKNOWLEDGE (确认冷链报警, COMMON)
UPDATE ai_intent_configs SET
    description = $d$确认冷链温控报警（从「未处理」变为「已确认」），并记录确认原因。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'COLD_CHAIN_ALERT_ACKNOWLEDGE' AND deleted_at IS NULL
  AND description = $d$确认冷链温控报警, status OPEN → ACKNOWLEDGED + ack 原因$d$;

-- COLD_CHAIN_ALERT_ACTIVE (查当前冷链报警, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询当前处于未处理、已确认状态的冷链温控报警。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'COLD_CHAIN_ALERT_ACTIVE' AND deleted_at IS NULL
  AND description = $d$查询当前 OPEN/ACKNOWLEDGED 状态的冷链温控报警$d$;

-- COLD_CHAIN_TEMP_QUERY (查询温度记录, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询冷链设备最近 24 小时的温度曲线，并标出偏离正常范围的时段。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'COLD_CHAIN_TEMP_QUERY' AND deleted_at IS NULL
  AND description = $d$查询冷链设备 24h 温度曲线 + 偏离 mark$d$;

-- COMMISSION_PENDING_TOTAL (待付提成汇总, COMMON)
UPDATE ai_intent_configs SET
    description = $d$汇总工厂待支付的提成总金额，并按销售员拆分明细；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'COMMISSION_PENDING_TOTAL' AND deleted_at IS NULL
  AND description = $d$汇总 factory 待付 (status=PENDING) 提成总金额 + 按销售员拆分. read-only.$d$;

-- CUSTOMER_NOTIFY_BATCH (批量通知客户, COMMON)
UPDATE ai_intent_configs SET
    description = $d$批量通知客户召回事件：根据发货记录找出受影响的客户并发送通知，同时留下召回处理记录；执行前会先预览。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'CUSTOMER_NOTIFY_BATCH' AND deleted_at IS NULL
  AND description = $d$批量通知客户召回事件 (基于 ShipmentRecord 反查影响客户). WRITE + Preview. NotificationService 推送 + RecallAction audit.$d$;

-- CUSTOMER_PRIORITY_QUERY (客户优先级排序, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按客户重要性和商机阶段汇总排序，输出客户清单；只查询。（餐饮门店的VIP食客/会员查询有专用餐饮意图，不属于本意图）$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'CUSTOMER_PRIORITY_QUERY' AND deleted_at IS NULL
  AND description = $d$按客户重要性 + 商机阶段聚合排序输出客户清单. read-only.（餐饮门店的VIP食客/会员查询有专用餐饮意图，不属于本意图）$d$;

-- CUSTOMER_QUALITY_STANDARD (客户质量标准查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询指定客户对产品的质量要求、质检证明（COA）和到货检测项目；客户还没登记标准时按工厂默认标准显示，并提示去客户资料里配置；只查询不修改。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'CUSTOMER_QUALITY_STANDARD' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P4c 客户质量标准查询 — 查指定 customer 的产品质量要求 / COA / 接收检测项. R5 dead-end: 客户暂无登记标准则返工厂默认 + actionHint 引导去 CRM 配置. read-only.$d$;

-- CUSTOMER_REVENUE_TREND (客户收入趋势, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按月汇总销售订单，输出收入趋势，可只看某一个客户；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'CUSTOMER_REVENUE_TREND' AND deleted_at IS NULL
  AND description = $d$聚合销售订单按月输出收入趋势. 可选单客户. read-only.$d$;

-- CUSTOMER_TRACKING_RECENT_QUERY (客户跟进记录查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询客户最近的跟进记录（拜访、电话、邮件），可指定条数和往前查几天，结果会带上客户名和内容摘要；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'CUSTOMER_TRACKING_RECENT_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint 5 Tool 2: 查询客户最近的跟进记录 (拜访/电话/邮件), 支持 limit + daysBack 参数. 含客户名+内容摘要回出明确上下文 (防呆 R2). read-only.$d$;

-- DAILY_CUSTOMER_FOLLOWUP (今日客户跟进, COMMON)
UPDATE ai_intent_configs SET
    description = $d$卤味老板的今日客户跟进入口：汇总出今天需要跟进的客户清单，按紧急程度分红、黄、绿三档。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'DAILY_CUSTOMER_FOLLOWUP' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P1 卤味老板 Workdesk V1 入口 — 聚合 5 Tool 输出今日 🔴🟡🟢 客户跟进清单. 触发 daily-customer-followup Skill (定义于 SkillRegistryImpl.initializeDefaultSkills).$d$;

-- FOOD_SAFETY_RECALL (食品安全召回, COMMON)
UPDATE ai_intent_configs SET
    description = $d$质量主管的食品安全召回入口：依次完成追溯批次、核对 HACCP 和添加剂、冻结库存、通知客户、监管上报和损失评估，走完召回闭环。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'FOOD_SAFETY_RECALL' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P3 质量主管 Workdesk 入口 — 串 8 Tool 完成召回 6 步闭环 (追溯/HACCP/添加剂/冻结/通知/上报/损失). 触发 food-safety-recall Skill (定义于 SkillRegistryImpl.initializeDefaultSkills). Cretas vs HJ 永久差异化护城河 — HJ 0 食品行业垂直能力.$d$;

-- FOOD_SAMPLE_CREATE (创建食品留样, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按 GB 31654-2021 创建食品留样记录：自动计算留样到期时间（48 小时），默认冷藏温度不高于 8 ℃，留样量不少于 125 克；执行前先预览，5 分钟内重复提交会被拦下。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'FOOD_SAMPLE_CREATE' AND deleted_at IS NULL
  AND description = $d$GB 31654-2021 创建食品留样记录 — 自动算 expireTime = now+48h, 默认冷藏 <= 8.0 ℃, 法规 sampleQuantity >= 125g. WRITE + Preview + R4 5min dedup.$d$;

-- FOOD_SAMPLE_DISPOSE (销毁/送检留样, COMMON)
UPDATE ai_intent_configs SET
    description = $d$把留样标记为已销毁或已送检，原因可选：按期销毁、送检、污染；执行前先预览并要求确认，重复操作会被拦下。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'FOOD_SAMPLE_DISPOSE' AND deleted_at IS NULL
  AND description = $d$把留样标记为 DISPOSED / USED_FOR_INSPECTION. reason enum: SCHEDULED (按期销毁) / INSPECTION (送检) / CONTAMINATED (污染). WRITE + Preview BLOCKING + R4 幂等.$d$;

-- FOOD_SAMPLE_QUERY_PENDING_DISPOSE (查待销毁留样, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询已留满 48 小时、应该销毁的留样（状态为留存中且已过到期时间），可用于工作台每日巡检或定时提醒；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'FOOD_SAMPLE_QUERY_PENDING_DISPOSE' AND deleted_at IS NULL
  AND description = $d$查 status=RETAINED + expire_time < now 的留样 (已过 48h 应销毁). 工作台每日巡检 / AI 定时提醒. read-only.$d$;

-- FOOD_SAMPLE_TRACE (按批次追溯留样, COMMON)
UPDATE ai_intent_configs SET
    description = $d$客户投诉时按批次号列出该批次的全部留样及状态，判断是否还有留存中的留样可以送检；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'FOOD_SAMPLE_TRACE' AND deleted_at IS NULL
  AND description = $d$客户投诉场景: 按 batchNumber 列出该批次全部留样 + status. 判断是否有 RETAINED 留样可送检. read-only.$d$;

-- HACCP_REVIEW (HACCP 监控审查, COMMON)
UPDATE ai_intent_configs SET
    description = $d$HACCP 关键控制点审查：查指定批次的全部监控记录，标出偏离项和纠正措施；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'HACCP_REVIEW' AND deleted_at IS NULL
  AND description = $d$HACCP CCP 审查 — 查指定批次的全部监控记录 + 标记 deviation + correctiveAction. read-only.$d$;

-- HACCP_STATUS_QUERY (放行前 HACCP 状态查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$质量主管放行前快速查看批次的 HACCP 情况：整体是否通过、有几个监控点偏离，并给出能否放行的提示；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'HACCP_STATUS_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P4c 质量主管放行前 HACCP 状态简洁查询 (P3 haccp_checkpoint_review wrapper). 输出: 整体通过状态 + 偏离监控点数 + releaseHint. read-only.$d$;

-- HR_EXPENSE_REQUEST_QUERY (报销申请查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询报销申请，按月汇总并按类别（差旅、餐费、办公、招待、培训、其他）分组；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'HR_EXPENSE_REQUEST_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint 4 W2 H-EXP-1: 查询报销申请, 月汇总按类目分组 (TRAVEL/MEAL/OFFICE/ENTERTAIN/TRAINING/OTHER). read-only.$d$;

-- HR_LEAVE_APPROVE (批准/驳回请假申请, COMMON)
UPDATE ai_intent_configs SET
    description = $d$批准或驳回请假申请：批准后自动扣减假期余额并同步调休记录，驳回时必须写明原因；确认前会显示申请人、假别、起止时间、时长和当前状态，已处理过的申请不能重复审批。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'HR_LEAVE_APPROVE' AND deleted_at IS NULL
  AND description = $d$HR_LEAVE_APPROVE (#848 follow-up): 批准或驳回请假申请 (WRITE with preview). 包装 LeaveRequestService.approve / reject. APPROVE 后自动扣减余额 + 发布 LeaveApprovedEvent (CompTime ledger 出账); REJECT 时 notes 必填作为 rejectReason. 防呆 R1 (preview 显示申请人/类型/期间/时长/当前状态) + R2 (context 含 审批人/申请人/决定/leaveId/终态) + R3 (decision enum APPROVE/REJECT + REJECT 强制 notes) + R4 (Service state 校验 重复 approve 抛 BusinessException, Tool 转 DUPLICATE + actionHint).$d$;

-- HR_LEAVE_BALANCE_QUERY (假期余额查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询当月假期余额（年假、病假、调休等），显示已获得、已使用和剩余天数；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'HR_LEAVE_BALANCE_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint 4 W2 H-LEAVE-1: 查询月度假期余额 (ANNUAL/SICK/COMPTIME/OTHER 跟踪类型), 显示已获/已用/剩余. read-only.$d$;

-- HR_LEAVE_REQUEST_QUERY (请假申请查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询请假申请，可选看自己的、待审批的或全部，并按月汇总；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'HR_LEAVE_REQUEST_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint 4 W2 H-LEAVE-1: 查询请假申请, 支持 mine/pending/all scope + 月度汇总. read-only.$d$;

-- HR_LEAVE_SUBMIT (提交请假申请, COMMON)
UPDATE ai_intent_configs SET
    description = $d$提交请假申请，支持年假、病假、事假、调休、婚假、丧假、产假、陪产假等；提交前会显示假期余额，自动检查余额并发起审批，与已有请假时间重叠时会提示。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'HR_LEAVE_SUBMIT' AND deleted_at IS NULL
  AND description = $d$Sprint 5 Tool 4: 提交请假申请 (WRITE with preview). 自动检查余额 + 触发审批. 支持 ANNUAL/SICK/PERSONAL/COMPTIME/MARRIAGE/FUNERAL/MATERNITY/PATERNITY/OTHER. 防呆 R1 (preview 显示余额) + R2 (context 含 用户/类型/起止/时长) + R4 (Service 内 时段重叠 dedup, 409 转 actionHint).$d$;

-- HR_OVERTIME_REQUEST_QUERY (加班调休申请查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询加班和调休申请，按月汇总，并区分发加班费与换调休两种；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'HR_OVERTIME_REQUEST_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint 4 W2 H-OVT-1: 查询加班/调休申请, 月汇总按 CASH/COMPTIME 分组. read-only.$d$;

-- INCOME_STATEMENT_QUERY (利润表, COMMON)
UPDATE ai_intent_configs SET
    description = $d$生成工厂指定期间的利润表（收入、成本、毛利、净利润四层）；只查询。（餐饮租户的菜品毛利、门店毛利分析有专用餐饮意图，不属于本意图）$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'INCOME_STATEMENT_QUERY' AND deleted_at IS NULL
  AND description = $d$生成 factory 期间内利润表 (4 层: 收入 → 成本 → 毛利 → 净利润). read-only.（餐饮租户的菜品毛利、门店毛利分析有专用餐饮意图，不属于本意图）$d$;

-- INDICATOR_ALERT (指标告警扫描, COMMON)
UPDATE ai_intent_configs SET
    description = $d$扫描工厂所有已启用的指标，列出当前触发红灯、黄灯的指标（按严重程度从高到低），可只看某个严重级别或某一类指标。适用：现在有几个红灯 / 什么需要关注 / 告警列表 / 哪个有问题。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'INDICATOR_ALERT' AND deleted_at IS NULL
  AND description = $d$Sprint 11 D5 — 指标告警 Tool. 扫描工厂全部启用指标当前 breach 状态, 返回 RED/YELLOW 列表 (按 severity 倒序). 可选 min_severity (ALL/WARNING/ALERT) + category 过滤. 触发: 现在有几个红灯 / 什么需要关注 / 告警列表 / 哪个有问题. Sprint 12 Day 9 RecomputeScheduler merge 后接 schedule hook 触发实时 alert.$d$;

-- INDICATOR_COMPARISON (指标对比, COMMON)
UPDATE ai_intent_configs SET
    description = $d$把多个指标（1–10 个）放在一起对比，显示每个指标当前值和告警级别，并标出最严重的一个。适用：老板问「客单价和翻台率哪个有问题」「几个指标摆一起」「对比 3 个指标」。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'INDICATOR_COMPARISON' AND deleted_at IS NULL
  AND description = $d$Sprint 11 D4 — 多指标对比 Tool. 输入 indicator_codes (1-10 个), 返回每个指标当前值 + 告警级别 + worst-state 标识. 适用: 老板问 "客单价和翻台率哪个有问题" / "几个指标摆一起" / "对比 3 个指标". 复用 D3 IndicatorBreachEvaluator 共用工具评估 threshold 命中.$d$;

-- INDICATOR_QUERY (指标查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询某个经营指标（如客单价、良品率、食安通过率）当前的值、近 30 天走势和是否触发预警（绿、黄、红）。适用：客单价 / 翻台率 / 良品率 / 食安通过率 / 损耗率 / 计划达成率 / 菜品毛利 / 看指标走势。（注意：餐饮门店租户询问客单价、翻台率、食材损耗、营收等经营数据时，应使用餐饮专用意图，不属于本意图）$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'INDICATOR_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint 11 D3 — 指标中心查询 Tool. 输入 indicator_code (例 AVG_TICKET_PRICE/FACTORY_YIELD_RATE/FOOD_SAFETY_PASS_RATE), 返回当前值 + 趋势 (默认 30 天) + 阈值命中状态 (GREEN/YELLOW/RED 或 WARNING/ALERT). 数据源 Sprint 11 D2 已 seed F999_MOCK 7 indicator × 30 天 = 210 行 indicator_versions (mock 数据, Sprint 12 切回 prod F006). 触发: 客单价 / 翻台率 / 良品率 / 食安通过率 / 损耗率 / 计划达成率 / 菜品毛利 / 看指标走势.（注意：餐饮门店租户询问客单价、翻台率、食材损耗、营收等经营数据时，应使用餐饮专用意图，不属于本意图）$d$;

-- INVENTORY_FREEZE (冻结库存, COMMON)
UPDATE ai_intent_configs SET
    description = $d$冻结指定批次的库存（状态变为冻结），是召回 SOP 的第一步；执行前先预览并要求确认。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'INVENTORY_FREEZE' AND deleted_at IS NULL
  AND description = $d$冻结指定批次库存 (status → FROZEN). WRITE + Preview BLOCKING. 召回 SOP 第一步.$d$;

-- LINEAGE_QUERY (批次溯源查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按批次号查询批次溯源：往上追到全部源头（原料批次，以及每头源头个体的耳标、品种、来源场和 DNA 状态），往下追到下游批次和客户，并给出一段概括（来自几头牛）；批次类型可以是生产批次、原料批次、成品批次或发货记录，不说类型默认按生产批次。适用：这批来自哪几头牛 / 这批用了哪批原料 / 召回这批查哪些客户。含批次明细和客户信息，属于中等敏感数据。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'LINEAGE_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint 11 D4 + 2026-09-15 -- 批次溯源 Tool. 直接给 batch_number (批次号, 如 SL-DEMO-BATCH-V1) 即可, batch_type 缺省 PRODUCTION_BATCH (也支持 MATERIAL_BATCH/FINISHED_BATCH/SHIPMENT_RECORD); 老用法 batch_type + batch_id 仍可用. 返回全部祖先 (上游/源批次, 含每头源头个体的耳标/品种/来源场/DNA 状态) + 后代 (下游/客户) + 直接边 metadata + 一段人话摘要 (来自几头牛). 触发: 这批来自哪几头牛 / 这批用了哪批原料 / 召回这批查哪些客户. Sensitivity MEDIUM 因含批次明细 + 客户信息.$d$;

-- MATERIAL_BATCH_WIP_QUERY (在制品批次查询, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$查询在制品（WIP）物料批次：当前被生产任务占用（生产预留状态）的原材料批次，返回批次号、物料名、剩余和预留数量、供应商、入库日期。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'MATERIAL_BATCH_WIP_QUERY' AND deleted_at IS NULL
  AND description = $d$查询在制品 (WIP) 物料批次 — 当前被生产任务占用 (PRODUCING_RESERVED 状态) 的原材料批次。返回批次号、物料名、剩余/预留数量、供应商、入库日期。$d$;

-- MATERIAL_DISPOSAL_RECOMMENDATION (临期物料处置建议, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$对 7 天内临期和已过期的批次给出处置建议（调拨、降价、退货、报废）；只给建议，不实际执行。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'MATERIAL_DISPOSAL_RECOMMENDATION' AND deleted_at IS NULL
  AND description = $d$对 7 天内临期 + 已过期批次输出处置建议 (调拨/降价/退货/报废). read-only — 不实际执行.$d$;

-- MONTHLY_FINANCIAL_CLOSE (月度财务复盘, COMMON)
UPDATE ai_intent_configs SET
    description = $d$财务主管的月度复盘入口：汇总当月经营情况，包括结账期间状态、三大财务报表、工资成本、商机漏斗、应付提成和应收账龄。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'MONTHLY_FINANCIAL_CLOSE' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P2 财务主管 Workdesk 入口 — 聚合 8 Tool 输出月度经营摘要 (期间状态 + 三表 + 工资 + 漏斗 + 应付提成 + 应收账龄). 触发 monthly-financial-close Skill (定义于 SkillRegistryImpl.initializeDefaultSkills).$d$;

-- MY_APPROVAL_WORKDESK (我的审批工作台, COMMON)
UPDATE ai_intent_configs SET
    description = $d$我的审批工作台：集中查看等我审批的单据。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'MY_APPROVAL_WORKDESK' AND deleted_at IS NULL
  AND description = $d$Sprint 10 Loop 4 审批工作台入口 — recovery via V_22_08 after V_22_05 slot collision$d$;

-- OPPORTUNITY_FUNNEL_STATS (商机漏斗统计, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按 8 个阶段汇总销售商机漏斗：每个阶段的商机数、总金额和加权预期金额；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'OPPORTUNITY_FUNNEL_STATS' AND deleted_at IS NULL
  AND description = $d$聚合销售商机 8 阶段 funnel 统计, 每阶段 count + 总金额 + 加权预期金额. read-only.$d$;

-- OPPORTUNITY_STAGE_ALERT (商机超 SLA 警告, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查超过规定天数（默认 21 天）还没推进的活跃商机；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'OPPORTUNITY_STAGE_ALERT' AND deleted_at IS NULL
  AND description = $d$查超 SLA 天数 (默认 21d) 未推进的活跃商机. read-only.$d$;

-- OPPORTUNITY_TRANSITION (推进商机阶段, COMMON)
UPDATE ai_intent_configs SET
    description = $d$把商机推进到下一阶段（支持跳级、回退，已结束的商机可重新激活，并检查是否允许）；执行前先预览。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'OPPORTUNITY_TRANSITION' AND deleted_at IS NULL
  AND description = $d$推进商机到下一阶段 (state machine 含跳级 / 回退 / 终态重激活校验). WRITE + Preview.$d$;

-- PDA_SCAN_TASK_GENERATE (PDA 扫码任务生成, COMMON)
UPDATE ai_intent_configs SET
    description = $d$生成 PDA / 手机扫码任务（含任务令牌、明细和二维码内容）；只生成任务，不写入数据库。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PDA_SCAN_TASK_GENERATE' AND deleted_at IS NULL
  AND description = $d$生成 PDA / 手机扫码任务 (token + items 数组 + qrPayload). GENERATE — 不写库.$d$;

-- PERIOD_CONFIRM_CLOSE (确认期间关账, COMMON)
UPDATE ai_intent_configs SET
    description = $d$确认期间关账（从「待关账」变为「已关账」）；关账后凭证写入立即被锁定，执行前先预览并要求确认。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PERIOD_CONFIRM_CLOSE' AND deleted_at IS NULL
  AND description = $d$确认期间关账 (PENDING_CLOSE → CLOSED). BLOCKING — voucher 写入立即锁. WRITE + Preview.$d$;

-- PERIOD_REOPEN (反结账, COMMON)
UPDATE ai_intent_configs SET
    description = $d$反结账（已关账的期间重新打开），必须填写原因并留审计记录；执行前先预览。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PERIOD_REOPEN' AND deleted_at IS NULL
  AND description = $d$反结账 (CLOSED → OPEN), 必填 reason audit log. WRITE + Preview.$d$;

-- PERIOD_REQUEST_CLOSE (发起期间结账请求, COMMON)
UPDATE ai_intent_configs SET
    description = $d$发起期间结账（从「未关账」变为「待关账」），并触发预算审批流程；执行前先预览。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PERIOD_REQUEST_CLOSE' AND deleted_at IS NULL
  AND description = $d$发起期间结账 (OPEN → PENDING_CLOSE), 触发 BUDGET_APPROVAL workflow. WRITE + Preview.$d$;

-- PERIOD_STATUS_QUERY (查询期间结账状态, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询工厂指定年月的期间结账状态（未关账、待关账、已关账），默认本月；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PERIOD_STATUS_QUERY' AND deleted_at IS NULL
  AND description = $d$查 factory 指定 (year, month) 期间结账状态 (OPEN/PENDING_CLOSE/CLOSED). 默认本月. read-only.$d$;

-- PRICE_HISTORY_QUERY (价格历史查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按物料类型输出最近几个月的加权平均价格和同比趋势；只查询。受价格敏感权限控制，仓管员角色看不到单价。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PRICE_HISTORY_QUERY' AND deleted_at IS NULL
  AND description = $d$按 materialTypeId 输出最近 N 个月加权 AVG + 同比趋势. read-only. 受 PriceSensitive 影响, 仓管员角色看不到 unitPrice.$d$;

-- PRINT_DOCUMENT (打印单据 PDF, COMMON)
UPDATE ai_intent_configs SET
    description = $d$打印 5 类业务单据 PDF（销售订单 / 采购订单 / 报价单 / 生产任务单 / 领料单）；采购订单带二维码，供仓管员扫码入库；返回可直接打开的下载链接。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PRINT_DOCUMENT' AND deleted_at IS NULL
  AND description = $d$打印 5 类业务单据 PDF (销售订单 / 采购订单 / 报价单 / 生产任务单 / 领料单). 采购订单含二维码 (供仓管员扫码入库). 返回前端可直接打开的 download URL.$d$;

-- PRINT_TEMPLATE_CREATE_FROM_AI (生成打印模板, COMMON)
UPDATE ai_intent_configs SET
    description = $d$按用户描述的版式要求，由 AI 生成打印模板并保存为可继续编辑的模板。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PRINT_TEMPLATE_CREATE_FROM_AI' AND deleted_at IS NULL
  AND description = $d$AI 生成 schema-driven 打印模板 — 用户描述布局需求, AI 输出 schema_json 并保存为可编辑的 FormTemplate (PRINT_* entityType)$d$;

-- PROCESSING_CAPACITY_TODAY (今日产能, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$汇总今天各生产批次的产能情况；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PROCESSING_CAPACITY_TODAY' AND deleted_at IS NULL
  AND description = $d$聚合今天的 ProductionBatch 输出当日产能汇总. read-only.$d$;

-- PROCUREMENT_ORDER_CREATE (一键采购下单, COMMON)
UPDATE ai_intent_configs SET
    description = $d$一键采购下单：先预览物料、数量（不超过月均用量的 3 倍）、推荐供应商和期望到货时间，确认后才生成采购单草稿；5 分钟内重复提交会被拦下。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PROCUREMENT_ORDER_CREATE' AND deleted_at IS NULL
  AND description = $d$Sprint 10 Loop 3 — 一键采购下单 AI 闭环灵魂 Tool. Preview 显物料/数量 (max=月均×3)/推荐供应商/期望到货. WRITE + Preview — preview 用户确认后才创 PurchaseOrder DRAFT. 防呆 R1+R2+R3+R4: 数量上限 / 物料+供应商身份 / 供应商 dropdown ranked / 5min idempotent. 复用 PurchaseService.createPurchaseOrder.$d$;

-- PRODUCTION_BATCH_CREATE (创建生产批次, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$创建生产批次（初始状态为计划中）并锁定对应销售订单行；预览会显示数量上限（不超过待产量）、品名和订单、生产线选择（专用线 A / 专用线 B / 共用线 / 其他），5 分钟内同一订单、产品、日期的重复提交会被拦下。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PRODUCTION_BATCH_CREATE' AND deleted_at IS NULL
  AND description = $d$Sprint 10 Loop 5 灵魂 Tool — 创建 ProductionBatch (status=PLANNED) 并锁定 SO 行项目. WRITE + Preview. 防呆 R1 max=待产量 + R2 品名+SO + R3 productionLine dropdown (DEDICATED_LINE_A/DEDICATED_LINE_B/SHARED_LINE/OTHER) + R4 idempotent (salesOrderId+productTypeId+scheduledDate+5min). 标记 ai_invocation_metadata = {source: sprint-10-loop-5, testRun, createdAt}.$d$;

-- PRODUCTION_DELIVERY_WARN_QUERY (交货预警查询, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$查询交货预警（已逾期、紧急、预警、正常四档），默认看未来 7 天，可只看某一档；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PRODUCTION_DELIVERY_WARN_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint 5 Tool 1: 查询交货预警 (OVERDUE/URGENT/WARN/NORMAL), 默认 7 天窗口, 可选 warnLevel 过滤. read-only.$d$;

-- PRODUCTION_DEMAND_ANALYSIS (订单缺产分析与排产建议, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$生产经理的订单缺产分析入口：找出还没产够的订单（订单总量减去已发和已产，仍大于 0 的产品），推荐起产量和生产线。适用：订单缺产 / 今天要起产什么 / 排产建议 / 什么订单缺货要做 / 该生产什么。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PRODUCTION_DEMAND_ANALYSIS' AND deleted_at IS NULL
  AND description = $d$Sprint 10 Loop 5 生产经理 Workdesk 入口 — AI 分析待产订单 (SO 总量 - 已发已产) > 0 的 productId 列表 + 推荐起产量 + 推荐生产线. 路由到 production-demand-analysis Skill (或 AI 兜底). 触发: 订单缺产 / 今天要起产什么 / 排产建议 / 什么订单缺货要做 / 该生产什么. Cretas vs HJ 差异化 — HJ 仅列订单, 不主动推 起产 建议.$d$;

-- PURCHASER_WEEKLY_PLAN (采购员周计划, COMMON)
UPDATE ai_intent_configs SET
    description = $d$采购员的周计划入口：列出库存偏低的物料并给出补货建议，回答「下周采购什么」，可一键请购。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PURCHASER_WEEKLY_PLAN' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P4b 采购员 Workdesk 入口 — 当前 default 走 stock_alert Tool 输出低库存清单 + 补货建议. 采购员小赵场景 (F006): "下周采购什么? 系统直接告诉我". Cretas vs HJ 防呆护城河 — HJ 4 菜单 + 手工算交期 vs Cretas 1 屏 30 秒 AI 输出 + 一键请购.$d$;

-- QUALITY_CHECK_SUMMARY (质检综合汇总, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$质量主管查看某批次全部质检记录的综合汇总：通过率和各结果的分布，用于放行决策；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'QUALITY_CHECK_SUMMARY' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P4c 质量主管批次质检综合汇总 — 查 batch 的全部 QualityInspection 记录 + 通过率聚合 + result 分布. 用于放行决策综合判断. read-only.$d$;

-- QUALITY_CHIEF_WORKDESK (质量主管工作台, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$质量主管工作台：一屏综合质检、HACCP、添加剂、客户标准四项检查，并可一键放行或退货。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'QUALITY_CHIEF_WORKDESK' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P4c 质量主管 Workdesk 入口 — 综合 4 项 audit (质检 + HACCP + 添加剂 + 客户标准) + 一键放行/退货. Cretas vs HJ 差异化 — HJ 要点 4 菜单, Cretas 1 屏综合判断.$d$;

-- QUALITY_DEFECT_QUERY (查询质检不良, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$查询工序质检不良记录列表，可按状态（待处理、处理中、已关闭）和缺陷类型筛选，支持翻页。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'QUALITY_DEFECT_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint4-H Q-PROCESS-1: 查询工序质检不良列表, 支持按状态(OPEN/IN_PROGRESS/CLOSED)/缺陷类型过滤, 分页.$d$;

-- QUALITY_DEFECT_RECORD (登记质检不良, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$登记一条工序质检不良记录，需关联质检单、缺陷类型和数量，登记后进入待处理状态等待分派。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'QUALITY_DEFECT_RECORD' AND deleted_at IS NULL
  AND description = $d$Sprint4-H Q-PROCESS-1: 登记一条工序质检不良记录, 关联质检 ID + 缺陷类型 + 数量, 进入 OPEN 状态等待分派.$d$;

-- QUALITY_RETURN_CREATE (创建质检退回单, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$创建质检退回单，用于向上游退回（供应商或委外加工厂）；与客户的销售退货不同，销售退货走销售退货流程。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'QUALITY_RETURN_CREATE' AND deleted_at IS NULL
  AND description = $d$Sprint4-H Q-RETURN-1: 创建质检退回单 (上游退回, 供应商或委外加工厂). 区分客户销售退货 (后者走 sales_returns).$d$;

-- QUALITY_RETURN_QUERY (查询质检退回单, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$查询质检退回单列表，可按状态（草稿、已确认、已发出）和目标类型（供应商、委外加工厂）筛选。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'QUALITY_RETURN_QUERY' AND deleted_at IS NULL
  AND description = $d$Sprint4-H Q-RETURN-1: 查询质检退回单列表, 按状态(DRAFT/CONFIRMED/SHIPPED)/目标类型(SUPPLIER/SUBCONTRACT)过滤.$d$;

-- RECALL_LOSS_ESTIMATE (召回损失预估, COMMON)
UPDATE ai_intent_configs SET
    description = $d$召回损失预估：汇总冻结库存、退货、行政和品牌四类损失，得出召回的预估损失并记到召回事件上。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'RECALL_LOSS_ESTIMATE' AND deleted_at IS NULL
  AND description = $d$召回损失预估 — 聚合冻结库存 + 退货 + 行政 + 品牌损失. 更新 RecallEvent.estimatedLoss. read-only.$d$;

-- RECEIVE_CONFIRM_CREATE (一键收货确认 (Sprint 10 Loop 2), COMMON)
UPDATE ai_intent_configs SET
    description = $d$仓管员选定某张采购单的某一行后直接确认收货：一步完成收货记录和确认，立即更新库存、生成原料批次并自动挂应付；与「快速入库」不同，它不会停在草稿。收货数量有上限，5 分钟内同一采购单重复提交会被拦下。适用：在工作台选某采购单行点「确认收货」，或说「签收 PO-X N 件」。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'RECEIVE_CONFIRM_CREATE' AND deleted_at IS NULL
  AND description = $d$Sprint 10 Loop 2 一气呵成 收货 Tool — R1 max + R2 context + R3 status dropdown + R4 5min 同 PO 幂等. 不同于 Sprint 8 receive_with_limit (创建 DRAFT 仍需 confirm), receive_confirm_create 直接 createReceiveRecord → confirmReceive 串联, 立即更新库存 + 创建 MaterialBatch + 自动挂应付. 触发场景: 仓管员在 Workdesk 选某 PO 行点"确认收货", 或 AI 上下文识别"签收 PO-X N 件".$d$;

-- RECEIVE_QUALITY_CHECK_TODAY (今日待质检批次, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查检验中的批次和今天新入库的批次清单，提醒仓管员通知质量主管；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'RECEIVE_QUALITY_CHECK_TODAY' AND deleted_at IS NULL
  AND description = $d$查 INSPECTING 状态批次 + 今日新入库批次清单, 提醒仓管员通知质量主管. read-only.$d$;

-- RECEIVE_WITH_LIMIT (快速入库 (R1 max), COMMON)
UPDATE ai_intent_configs SET
    description = $d$仓管员快速入库：预览会显示已订、已收、还能收多少（含 30% 超收上限），超限会被拦下；直接告诉仓管员要收多少，不用自己算账。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'RECEIVE_WITH_LIMIT' AND deleted_at IS NULL
  AND description = $d$仓管员快速入库, preview 显已订/已收/可入 (含 30% 超收上限) + 超限阻止. WRITE + Preview. 防呆 R1 灵魂 Tool — 直接告诉仓管员要收多少, 不让他算账.$d$;

-- REGULATORY_REPORT (生成监管上报文件, COMMON)
UPDATE ai_intent_configs SET
    description = $d$生成监管上报文件（文字报告加 PDF 链接），是召回 SOP 的监管上报环节，生成后召回事件变为「已上报」。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'REGULATORY_REPORT' AND deleted_at IS NULL
  AND description = $d$生成监管上报文件 (markdown report + PDF link). 召回 SOP 监管上报 step. RecallEvent → REPORTED.$d$;

-- RELEASE_DECISION (批次放行决策, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$质量主管对批次做放行决策（会拦截并要求确认）：放行则批次变为可用，拒绝则判为不良品；预览会显示当前状态和新状态，重复决策会被拦下。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'RELEASE_DECISION' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P4c 质量主管批次放行决策 — WRITE + Preview BLOCKING. RELEASED → AVAILABLE / REJECTED → DEFECTIVE. 防呆 R1+R2+R3+R4: preview 必显当前/新 status + R3 decision enum + R4 幂等防重复决策.$d$;

-- REMINDER_LIST_MINE (我的提醒列表, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询当前用户的待办提醒（默认含待处理和已推迟，如收款逾期、即将到期等），按到期日从早到晚排列，每条带业务单号、到期日和提醒内容。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'REMINDER_LIST_MINE' AND deleted_at IS NULL
  AND description = $d$查询当前用户的待办提醒 (默认 PENDING+SNOOZED, 含收款逾期/即将到期等). 按 dueDate 升序返回, 包含 sourceId 业务单号 + dueDate + message 上下文, 顶层 actionHint 指向 /sales/reminders.$d$;

-- REQUISITION_CREATE (一键创建请购单, COMMON)
UPDATE ai_intent_configs SET
    description = $d$一键创建请购单（草稿）：预览物料、数量、预算、供应商和审批流程，确认后才真正创建。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'REQUISITION_CREATE' AND deleted_at IS NULL
  AND description = $d$一键创建请购单 (DRAFT). preview 显物料/数量/预算/供应商/审批 chain. WRITE + Preview — preview 用户确认后才真创建. 防呆 R1+R2+R4 灵魂 Tool. 复用 PurchaseRequisitionService.createRequisition (Sprint 5 D ship).$d$;

-- RETURN_ORDER_LIST (退货单查询, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$查询退货单列表、退货记录与最近退货情况（只读）：展示退货单号、退货商品、退货原因、退货状态。仅查看；创建退货和审批退货各有专门的功能。不是销售订单查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'RETURN_ORDER_LIST' AND deleted_at IS NULL
  AND description = $d$查询退货单列表、退货记录与最近退货情况 (只读)。展示退货单号、退货商品、退货原因、退货状态。仅查看; 创建退货用 RETURN_ORDER_CREATE, 审批用 RETURN_ORDER_APPROVE。不是销售订单查询。$d$;

-- SALES_FORECAST_7DAY (7 天销售预测, COMMON)
UPDATE ai_intent_configs SET
    description = $d$未来 7 天销售预测：按最近 14 天销售订单的日均量乘 7 天再加 10% 余量做简单预测（不是机器学习模型），列出销量前 20 的产品；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SALES_FORECAST_7DAY' AND deleted_at IS NULL
  AND description = $d$简单线性预测 — 最近 14 天 SO history AVG × 7 × 1.1 buffer (不上 ML, Sprint 9 升级 SmartBI LightGBM). read-only. Top 20 产品.$d$;

-- SALES_NEED_CONVERT (客户需求转销售订单, COMMON)
UPDATE ai_intent_configs SET
    description = $d$把已确认的客户需求一键转成销售订单，自动创建订单并与需求双向关联。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SALES_NEED_CONVERT' AND deleted_at IS NULL
  AND description = $d$将 CONFIRMED 客户需求一键转为销售订单. 自动建 SalesOrder + 双向关联 needId.$d$;

-- SALES_NEED_CREATE (创建客户需求, COMMON)
UPDATE ai_intent_configs SET
    description = $d$创建客户需求（草稿状态）：记录客户提出的购买意向，销售员后续确认并转成销售订单；需要客户、产品、数量和单位。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SALES_NEED_CREATE' AND deleted_at IS NULL
  AND description = $d$创建客户需求 (DRAFT 状态). 客户提的购买意向, 销售员后续确认并转销售订单. 需客户ID/产品ID/数量/单位.$d$;

-- SAMPLE_TO_BOM (样品建 BOM, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$根据已有的产品样品生成 BOM 草稿，可参考某个现有 SKU，并按自然语言说明调整；只生成草稿不保存，用户在前端编辑确认后再正式创建配方。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SAMPLE_TO_BOM' AND deleted_at IS NULL
  AND description = $d$根据已存在的 ProductSample 生成 BOM 草稿 JSON. 接受可选 referenceSku + 自然语言 adjustments. 仅生成草稿不写库 — 用户在前端编辑确认后再 bom_recipe_create.$d$;

-- SHIPMENT_CONFIRM_CREATE (确认发货创单, COMMON)
UPDATE ai_intent_configs SET
    description = $d$确认发货并创建发货单：按销售订单和各订单行的实发数量创建，数量不能超过待发量，5 分钟内重复提交会被拦下。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SHIPMENT_CONFIRM_CREATE' AND deleted_at IS NULL
  AND description = $d$Sprint 10 Loop 1 — confirm 模式. 接 salesOrderId + items[{salesOrderItemId, actualQty}] 创发货单, R1 max + R2 context + R4 5min idempotent.$d$;

-- SHORTAGE_ANALYSIS (销售订单缺料分析, COMMON)
UPDATE ai_intent_configs SET
    description = $d$分析销售订单的成品库存和 BOM 原料缺口，给出采购建议和生产建议；只分析，不创建采购单或生产单。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SHORTAGE_ANALYSIS' AND deleted_at IS NULL
  AND description = $d$S-MRP-1 N31: 分析销售订单成品库存 + BOM 原料缺口, 返回采购建议 + 生产建议 + chain-card 提示。read-only 不创建采购/生产单。$d$;

-- SMART_INDICATOR_QUERY (智能指标分析, COMMON)
UPDATE ai_intent_configs SET
    description = $d$智能指标分析入口：老板问指标、对比、告警、溯源时，由系统自动选择合适的查询（指标查询、指标对比、指标告警、批次溯源）。（注意：餐饮门店租户询问客单价、翻台率、食材损耗、营收等经营数据时，应使用餐饮专用意图，不属于本意图）$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SMART_INDICATOR_QUERY' AND deleted_at IS NULL
  AND description = $d$AI 工厂 chat 智能指标入口 — 老板问指标/对比/告警/溯源, 自动选 Tool 调度. tool_name=NULL 走 smart-indicator-query Skill (LLM 路由 4 个 indicator/lineage Tool).（注意：餐饮门店租户询问客单价、翻台率、食材损耗、营收等经营数据时，应使用餐饮专用意图，不属于本意图）$d$;

-- SOURCE_INDIVIDUAL_QUERY (源头个体查询, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询源头个体（一头牛）：用个体编码或耳标号找到某一头，可看档案、系谱、后代、同胞或去向；只说 DNA 比对状态（未采样、已采样、已比中、不匹配）时列出该状态的个体（最多 50 个）；找不到就说找不到，不模糊匹配，摘要不含基因库编号和 DNA 样本号。适用：这头公牛有哪些后代 / 哪一头牛的 DNA 比对不匹配 / 这头牛去了哪。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SOURCE_INDIVIDUAL_QUERY' AND deleted_at IS NULL
  AND description = $d$FEAT-AI-LINEAGE-INDIVIDUAL-TOOLS-20260915 -- 源头个体 (一头牛) 查询 Tool. 参数 individual_code / tag_number 二选一定位到一头, ask=profile|pedigree|offspring|siblings|downstream 选要看的段; 只给 dna_status (UNSAMPLED/SAMPLED/MATCHED/MISMATCH, 也接受中文) 时列出该状态的个体 (上限 50). 找不到就说找不到, 不模糊匹配. 摘要不含基因库 ID / DNA 样本号. 触发: 这头公牛有哪些后代 / 哪一头牛的 DNA 比对不匹配 / 这头牛去了哪.$d$;

-- STOCK_ALERT (低库存预警 (采购员), COMMON)
UPDATE ai_intent_configs SET
    description = $d$采购员的低库存预警：列出库存偏低的物料，推荐补货量（缺口的 1.5 倍）和推荐供应商（参考上次采购和价格排序），可按物料类别筛选；只查询。库存预警 / 库存有哪些预警 / 缺料补货提醒 都属于本意图。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'STOCK_ALERT' AND deleted_at IS NULL
  AND description = $d$Sprint 10 Loop 3 extended — 采购员低库存预警 + 推荐补货量 (缺口 × 1.5) + 推荐供应商 (last-PO + price ranked). 复用 MaterialBatchService.getLowStockWarnings. 支持按 materialCategory 过滤. read-only. Path A keyword match + Path B LLM-routed synonym 双路径触发. 库存预警/库存有哪些预警/缺料补货提醒 都属于本意图。$d$;

-- SUPPLIER_CAN_ORDER_CHECK (供应商下单 gate 检查, COMMON)
UPDATE ai_intent_configs SET
    description = $d$下单前检查供应商资质：营业执照和 SC 证都有效或即将到期才能下单，返回能否下单及原因；只查询，是创建采购单前的关卡。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SUPPLIER_CAN_ORDER_CHECK' AND deleted_at IS NULL
  AND description = $d$供应商下单前资质检查 (PO BLOCKING gate) — 强制资质 (SC 证 + 营业执照) VALID/EXPIRING 才能下单. 返 canOrder=true/false + reason. read-only. PO 创建前 gate.$d$;

-- SUPPLIER_DELIVERY_ETA (供应商交期 ETA, COMMON)
UPDATE ai_intent_configs SET
    description = $d$估算供应商交期：综合供应商设定的交货天数和最近 90 天历史采购单的平均实际交期（实收日减下单日），可按供应商或物料类型筛选；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SUPPLIER_DELIVERY_ETA' AND deleted_at IS NULL
  AND description = $d$综合 supplier.deliveryDays + 最近 90 天历史 PO 平均 (实收日-下单日). read-only. 可选按 supplierId / materialTypeId 过滤.$d$;

-- SUPPLIER_QUALIFICATION_CREATE (登记供应商资质, COMMON)
UPDATE ai_intent_configs SET
    description = $d$登记供应商食品资质（SC 证、进口商资质、HACCP、营业执照、ISO 22000、FSSC 22000、第三方检测），按到期日自动判断初始状态；执行前先预览。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SUPPLIER_QUALIFICATION_CREATE' AND deleted_at IS NULL
  AND description = $d$登记供应商食品资质 (SC 证 / 进口商资质 / HACCP / 营业执照 / ISO 22000 / FSSC 22000 / 第三方检测). WRITE + Preview MEDIUM. 自动按 expiry_date 算初始 status.$d$;

-- SUPPLIER_QUALIFICATION_LOOKUP (查询供应商资质, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询指定供应商的全部食品资质，可按资质类型筛选，返回有效、即将到期、已过期、已撤销各种状态；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SUPPLIER_QUALIFICATION_LOOKUP' AND deleted_at IS NULL
  AND description = $d$查询指定供应商的全部食品资质 — 按 supplierId + 可选 qualificationType filter. read-only. 返全部状态 (VALID/EXPIRING/EXPIRED/REVOKED).$d$;

-- SUPPLIER_QUALIFICATION_QUERY_EXPIRING (查询即将过期资质, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询即将过期（或已过期）的供应商食品资质，用于工作台预警，默认看 30 天内，分三档：已过期、紧急（7 天内）、预警（30 天内）；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SUPPLIER_QUALIFICATION_QUERY_EXPIRING' AND deleted_at IS NULL
  AND description = $d$查询即将过期 (或已过期) 的供应商食品资质 — Workdesk 预警 widget 用. read-only. 默认 30 天. 返三档: EXPIRED ⛔ / URGENT 🔴 (7天) / WARNING 🟡 (30天).$d$;

-- TRACE_BATCH (批次溯源, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$查询批次溯源信息。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'TRACE_BATCH' AND deleted_at IS NULL
  AND description = $d$查询批次溯源信息 - 版本快照测试 v2$d$;

-- WAGE_COST_SUMMARY (工资成本汇总, COMMON)
UPDATE ai_intent_configs SET
    description = $d$汇总工厂指定月份的工资成本，按计件、计时、混合三类拆分；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'WAGE_COST_SUMMARY' AND deleted_at IS NULL
  AND description = $d$汇总 factory 指定月份工资成本, 按 PIECE_RATE/HOURLY/MIXED 三类拆分. read-only.$d$;

-- WAGE_POLICY_QUERY (工资策略, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查询工厂或某位员工的工资策略设置（计薪方式和混合公式）；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'WAGE_POLICY_QUERY' AND deleted_at IS NULL
  AND description = $d$查 factory / 员工的工资策略配置 (mode + mixed formula). read-only.$d$;

-- WAREHOUSE_KEEPER_TODAY_TASKS (仓管员今日任务, COMMON)
UPDATE ai_intent_configs SET
    description = $d$仓管员的今日任务入口：列出今天要收的货，直接告诉他要收多少。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'WAREHOUSE_KEEPER_TODAY_TASKS' AND deleted_at IS NULL
  AND description = $d$Sprint 8 P4a 仓管员 Workdesk 入口 — 当前 default 走 material_today_receiving_query Tool 输出今日待收清单. 客户原话 (F006 张权): "做仓管的年纪大文化素质低, 最好告诉他要收多少就行". Cretas vs HJ 防呆护城河 — HJ 5 屏 5 分钟 vs Cretas 1 屏 30 秒.$d$;

-- WECHAT_CREATE (补录微信记录, COMMON)
UPDATE ai_intent_configs SET
    description = $d$补录客户微信对话记录；执行前先预览，5 分钟内重复补录会被拦下。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'WECHAT_CREATE' AND deleted_at IS NULL
  AND description = $d$补录客户微信对话. WRITE + Preview. 防呆 R4 5min dedup window.$d$;

-- WECHAT_RECENT_QUERY (查询近期微信, COMMON)
UPDATE ai_intent_configs SET
    description = $d$查最近几天（默认 7 天）的微信记录，可只看某个客户；只查询。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'WECHAT_RECENT_QUERY' AND deleted_at IS NULL
  AND description = $d$查近 N 天微信记录, 默认 7 天. 可选客户过滤. read-only.$d$;

-- WORK_PROCESS_CONFIG_UPDATE (配置产品工序, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$为指定产品配置完整工序流程（整体替换，按顺序写入），支持先预览变更。一句话给产品加工序：「给猪蹄加工序: 拆包→分割→卤制→分切→装筐」。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'WORK_PROCESS_CONFIG_UPDATE' AND deleted_at IS NULL
  AND description = $d$为指定产品配置完整工序流程 (REPLACE 语义, 按顺序写入)。支持 preview 预览变更。一句话给产品加工序: "给猪蹄加工序: 拆包→分割→卤制→分切→装筐"。$d$;

-- WORK_PROCESS_TASK_ASSIGN (分配工序责任人, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$为工序任务分配责任人（指定员工）；不改变任务状态。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'WORK_PROCESS_TASK_ASSIGN' AND deleted_at IS NULL
  AND description = $d$为工序任务分配责任人 (assignedTo userId)。不改变任务状态。$d$;

-- WORK_PROCESS_TASK_SPAWN (生成工序任务, FACTORY)
UPDATE ai_intent_configs SET
    description = $d$按产品的工序模板，为生产批次生成工序任务（一道工序对应一个任务，初始状态为待开始）；需要生产批次和产品。$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'WORK_PROCESS_TASK_SPAWN' AND deleted_at IS NULL
  AND description = $d$为生产批次从 product_work_processes 模板生成工序任务实例 (1 个工序 = 1 个任务, status=PENDING)。需 productionBatchId + productTypeId。$d$;

-- ===== 按钮标题改写 (5) =====

-- CALL_FOLLOWUP_PENDING: 「missed call 跟进」 -> 「未接来电跟进」
UPDATE ai_intent_configs SET
    intent_name = $d$未接来电跟进$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'CALL_FOLLOWUP_PENDING' AND deleted_at IS NULL
  AND intent_name = $d$missed call 跟进$d$;

-- RECEIVE_CONFIRM_CREATE: 「一键收货确认 (Sprint 10 Loop 2)」 -> 「一键收货确认」
UPDATE ai_intent_configs SET
    intent_name = $d$一键收货确认$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'RECEIVE_CONFIRM_CREATE' AND deleted_at IS NULL
  AND intent_name = $d$一键收货确认 (Sprint 10 Loop 2)$d$;

-- RECEIVE_WITH_LIMIT: 「快速入库 (R1 max)」 -> 「快速入库」
UPDATE ai_intent_configs SET
    intent_name = $d$快速入库$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'RECEIVE_WITH_LIMIT' AND deleted_at IS NULL
  AND intent_name = $d$快速入库 (R1 max)$d$;

-- SSOP_PRODUCTION_GATE_CHECK: 「SSOP 生产开工 gate 检查」 -> 「SSOP 生产开工前检查」
UPDATE ai_intent_configs SET
    intent_name = $d$SSOP 生产开工前检查$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SSOP_PRODUCTION_GATE_CHECK' AND deleted_at IS NULL
  AND intent_name = $d$SSOP 生产开工 gate 检查$d$;

-- SUPPLIER_CAN_ORDER_CHECK: 「供应商下单 gate 检查」 -> 「供应商下单资质检查」
UPDATE ai_intent_configs SET
    intent_name = $d$供应商下单资质检查$d$,
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SUPPLIER_CAN_ORDER_CHECK' AND deleted_at IS NULL
  AND intent_name = $d$供应商下单 gate 检查$d$;
