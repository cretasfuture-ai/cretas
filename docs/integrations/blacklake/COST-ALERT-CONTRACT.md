# SmartBI 成本与数据异常契约 v1

目标：数据缺口也必须形成分析发现。规则独立计算、输出可读文字与机器可读证据；可选本地AI只选择核对动作及优先级，不生成或改写成本事实。

## 共用契约与接线

通用引擎：`backend/python/smartbi/services/cost_data_alerts.py`，只接受标准成本工单视图，不依赖黑湖接口、数据库或模型。当前适配链为Blacklake reader → 标准化成本事实 → 通用引擎 → 同一个受租户/角色校验的SmartBI analysis API → 测试页“成本与数据异常”。其他上传/连接器尚未接入此契约，不能称为全平台已上线。

`schema=smartbi.cost-alerts/1`；每个提醒含`id/type/family/severity/blocksUnitCost/entityRefs/fields/evidence/message/owner/action/status/detectedBy/ruleVersion/scope/runId/metric`。ID绑定来源租户摘要、归档、类型及实体集合；同一工单可有多个提醒。`needs_review`是待核实状态，当前不提供确认、忽略、源数据修改或自动闭环操作。

类别：

|family|用途|例子|
|---|---|---|
|data_quality|缺失、显式零值、冲突及归集完整性|缺价、缺数量、缺关联、有工时无工资、同代码不同名称|
|business_signal|规则触发的待核对经营信号|同产品同单位成本差异、材料占比偏低、人工占比偏高|
|readiness|核算或比较前提未满足|未完工、同产品可核算样本不足|

## 稳定类型及字段映射

|类型|确定性触发依据|黑湖原字段或证据|影响|
|---|---|---|---|
|NOT_FINISHED|状态非完工|status|就绪条件，不等于数据丢失|
|FINISHED_GOOD_INVALID|最终良品缺失或非正数|numberofGoodProducts|缺成本分母|
|FINISHED_UNIT_MISSING|成品单位缺失|projectUnit|禁止跨单位汇总|
|PRODUCT_IDENTITY_MISSING|缺稳定产品代码/单位|productCode/projectUnit|禁止peer比较，仍保留逐单金额|
|MATERIAL_LINK_MISSING|没有精确关联领退料|associatedBizOrderId/Code/Type|需核对实际用料归属|
|MATERIAL_VALUE_MISSING|材料金额缺失/非法|changedCostAmountSigned|不默认零|
|MATERIAL_VALUE_ZERO|有耗料且材料金额全部显式零|changeQty/changedCostAmountSigned|需确认历史估值|
|MATERIAL_VALUE_PARTIAL_ZERO|部分非零耗用行金额/单价零或缺失|costUnitPrice/changedCostAmountSigned|逐材料补核|
|MOVEMENT_INVALID|数量单位缺失、符号异常、负单价|unitName/changeQty/costUnitPrice/changedCostAmountSigned|不猜测单位换算|
|LABOR_COVERAGE_MISSING|无任务或工资已知任务数不足|task记录/sumOutputSalary|不把无任务当零工资|
|LABOR_WITH_HOURS_ZERO|有工时但工资为零/缺失|outputDurationForSecond/sumOutputSalary|核对计薪|
|LABOR_ZERO_UNVERIFIED|可算工单累计工资为零|sumOutputSalary|非阻断提醒，不认定效率更高|
|MATERIAL_SHARE_LOW|材料占已识别直接成本<10%|标准化材料/材料+人工|核对主原料完整性|
|LABOR_SHARE_HIGH|人工占已识别直接成本>65%|标准化人工/材料+人工|先核材料归集，再核人工|
|INPUT_ONLY_CONSUMABLES|关联材料名称均命中包装耗材词|productName|仅名称线索，非物料类别真值|
|PRODUCT_NAME_CONFLICT|同代码同单位可算工单有多个名称|productCode/productName|停止该组差异告警，待核工艺阶段|
|PEER_SAMPLE_SMALL|同产品同单位同名称可算样本<3|通过字段校验子集|不做统计异常判定|
|UNIT_COST_SPREAD|至少3样本、最高相对最低差异≥20%|unitIdentifiedDirectCost|候选信号，不是因果或统计显著性证明|
|CURRENCY_UNVERIFIED|来源币种尚未核实|接口未证实币种|不标人民币，不算毛利|
|UPSTREAM_COST_BLOCKED|其他源成本校验阻断|issues|新条件也不能静默丢失|

10%、65%、20%是本PoC可审查的初始核对阈值，不是行业标准。跨产品只能看金额水平，不直接判异常；同代码不同名称/工艺阶段不混算产品均值。本次按名称拆分成本水平表，保留8个产品代码、16张工单。

## 原料到成品口径

单位可识别直接成本＝（精确关联、审批通过的生产领退料净额＋任务累计工资）÷工单最终良品量。逐单列出材料/半成品的耗用及单位成品贡献、工序人工与产量，不重复加报工工资估算。

如领料包含半成品，仅采用该来源库存估值，不再加上游生产成本。缺少原料批次—半成品批次—下游领料批次链路、期初在制品和完整历史估值时，不能声称逐批完整制造成本已打通。字段可算与实际归集完整性分开：16张也会产生包装耗材、人工零值、原料占比等提醒。

## 本地AI约束与实测

已核对SSH真实主机与本地服务：加拿大Mac mini现有Ollama、已加载qwen3:8b；Aurora资源紧张，本次未加载模型。推理使用固定SSH别名`canada-mini`，远端`curl --noproxy '*'`仅调用127.0.0.1:11434；无外部provider回退。

输入每类一个代表提醒，仅含opaque alert ID、通用类型/标题/类别、该提醒实体数量、比例/阈值/样本量和允许动作码；没有源记录、工单编号、产品/人员名称、成本金额、凭据。返回仅允许`alertId/check/priority`；动作受对应提醒类型白名单约束，不能确认或编造业务原因。保存CurrentUser DPAPI，绑定scope/run/全部规则事实指纹、输入事实、model/host及零源记录/零外部provider标记。无法读取、损坏、过期或非法AI输出明确提示，规则及成本仍可用。

实测本地模型完成15类代表问题的动作/优先级建议。没有将模型选择当作业务核实或确定归因。

## 本次结果与限制

同一1879条归档，109工单：16可算、93阻断。143条可重叠提醒＝115数据质量＋23就绪条件＋5经营信号（3人工占比、1材料占比、1同名称产品差异）。另有名称阶段冲突、仅包装耗材、人工为零等，均有文字与证据。93张原成本阻断工单全部有机器可读阻断提醒；16张材料贡献加人工与单位直接成本逐单对账。

测试环境页面/API/数据库验收与真实工厂业务确认分开。源码/命令/结果见COST-ALERT-VALIDATION.json；真实工单分析文件仅本机仓库外交付，不进入Git。
