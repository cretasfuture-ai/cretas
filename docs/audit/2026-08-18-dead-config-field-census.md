# 死配置字段普查 (2026-08-18) —— 只读取证, 未改代码

> 触发: `work_processes.semi_finished_output_code` 实测 211 行工序里 55 行配了这个字段,
> 而这 55 行【全部】是 E2E/Codex 测试夹具, 0 行被任何真实 `work_process_task` 引用
> (已修, PR #2867)。本次普查回答: **这类"字段存在、有人填、但真实业务从来不走它"的配置,
> 还有多少个?**
>
> 分支: `codex/claude-dead-config-audit`(off `origin/main`, 未合并)。
> 本文件是本轮唯一交付物, **未改任何生产代码, 未部署, 未在 prod 写任何数据**。

---

## 口径

1. **候选枚举**: `information_schema.columns`, `table_schema='public'`, 列名匹配
   `(_code|_config|_mode|_strategy|_output|_rule|_method|_basis|_policy|_flag|_option|_ratio|_scheme|_template)` 结尾
   或 `^(config|strategy|policy|mode|template)_` 开头。**枚举到 350 列**, 不是手挑。
2. **总行数/非空行数**: 对每一列 `SELECT count(*), count(<col>)` 逐列跑(一次 PL/pgSQL DO 循环,
   全表扫描, 见附录完整表)。
3. **排除**: `migration_*` / `repair_backup_*` / `bak_*` / `backup_*` 前缀的表 —— 这些是一次性
   数据迁移/修复留下的审计快照表, 不是活的应用配置, 单独列出不逐列判定(18 列)。
4. **"真实业务引用"判据**(分两层, 逐候选执行, 不是统一公式):
   - **代码层**: grep `backend/java/cretas-api/src/main/java`, 找该字段的 getter 除
     entity/DTO/mapper 直传外, 有没有真被读来做分支/查询/计算。DTO 直传视为"代码不引用"。
   - **数据层**: 对代码层判定"有真实读取逻辑"的字段, 再查 prod 数据里非空行是不是
     真实业务产生的(排除按名称匹配 `E2E|Codex|测试|test|demo|mock|delme|probe|qa_|ztestc|claudepf`
     的测试夹具行, 并核对 `@Where(deleted_at IS NULL)` 这类会静默影响 JPA 查询但 SQL 直查看得见的软删)。
5. **判定三类**(见任务要求): **(a) 死配置**(有人填, 代码不读, 或代码读了但从来读到的都不是
   真实业务写的值) / **(b) 合法的空/合法默认**(空或单一值是正常状态, 代码有对应处理) /
   **(c) 判不准**(未做代码层核实, 或前后端行为不一致需要 owner 确认)。

## 阳性对照

`work_processes.semi_finished_output_code`: 扫描读出 **211 / 55**, 与今天早些时候独立实测的
基线完全一致 —— 仪器读数可信。

```
marker            | table_name     | column_name                | total_rows | non_null_rows
POSITIVE_CONTROL   | work_processes | semi_finished_output_code  |        211 |            55
```

## 环境说明(影响本次范围)

`cretas_prod_db.factories` 当前只有 **3 个租户**: `F006`(食品溯源主力测试/演示厂)、
`LIUSHANMEN`(食品溯源)、`MOCK_REST`(餐饮模拟租户)。没有独立的"E2E 租户" ——
测试夹具**混在 F006 里**, 靠命名模式(`Codex-CX...` / `ZTESTC-*` / `QA_AUDIT_*` /
`E2E测试设备-*` 等)区分, 不能按 `factory_id` 过滤。本次凡涉及"真实业务行"的判定
均按**命名模式排除法**执行, 已在下文逐条注明命中数。

---

## 一、(a) 死配置 —— 逐条实测

### 1. `product_types.settlement_method` / `.default_sop_config_id` / `.form_template_id`

| 列 | 总行 | 非空 | 引用 |
|---|---|---|---|
| `settlement_method` | 229 | **0** | **零引用**(entity 里存在, 连 DTO 都没进, grep 全仓零命中) |
| `default_sop_config_id` | 229 | **0** | 只在 `ProductTypeServiceImpl.java:261,392,1204` 做 DTO↔entity 拷贝, 从未拿去查 `SopConfig` |
| `form_template_id` | 229 | **0** | 同上, `ProductTypeServiceImpl.java:258,389,1202` 只拷贝 |

`product_types` 是核心产品目录表(229 行, 225 行未软删, 确认是活表), 但**这三个字段在
全库任何一行都从未被填过**, 且代码侧要么零引用要么只做拷贝。

**谁在读、读到空会怎样**: `settlement_method` 没有任何读取代码, 读到空≈不存在这个字段。
`default_sop_config_id`/`form_template_id` 只在 DTO 里透传空值给前端, 前端目前也没有依赖
它非空的渲染分支(未深查前端, 列为已知信息但非本次重点)。

### 2. SOP(标准作业程序)配置子系统整体是脚手架

`sop_configs` 表全库 **0 行**(`photo_config_json`/`validation_rules_json` 均 0/0)。
所有指向它的外键在真实数据里也全是空:

| 列 | 总行 | 非空 |
|---|---|---|
| `production_batches.sop_config_id` | 19 | **0** |
| `product_types.default_sop_config_id` | 229 | **0** |
| `smart_bi_sku_complexity.sop_config_id` | 0 | 0 |

代码侧确认: `SopConfig` entity 的唯一消费者是它自己和 `SopConfigRepository` ——
**没有 `SopConfigService`、没有 `SopConfigController`**, 全仓没有任何地方真正解析一条
`SopConfig` 去执行步骤/拍照/校验规则。`ProductionBatch.sopConfigId` 在
`ProcessingController.java:1033` 被写入、`ProcessingBatchDetailTool.java:138` 被读出,
但都是**纯拷贝**, 从未拿去反查 `SopConfig` 行。

**判定**: 整个 SOP 配置能力是"表+entity+repository 都在, 没有一条服务/控制器真正驱动它"。

### 3. `pricing_strategies` 表 —— 功能全链路真实存在, 但从未有过一条"真实业务能读到"的策略

- 代码: `PricingEngineImpl.calculate()` 是**完整实现**(非桩), 真被
  `SalesServiceImpl.java:585` 在真实建单流程里调用; `PricingStrategy` entity 有
  `@Where(clause = "deleted_at IS NULL")`(`PricingStrategy.java:60`)。
- Web-admin 有真实可达的管理界面: `web-admin/src/views/platform/pricing-strategy-editor/`,
  挂在 `/platform/canvas-editor` 路由下, `roles: ['factory_super_admin', ...]`, 菜单可见。
- **prod 数据**: `pricing_strategies` 全库 **36 行**, 名字清一色
  `QA_AUDIT_*` / `MATRIX_PN_P*` / `PROD_SMOKE_BUG1` / `ui_e2e_pricing_001` / `AUD3_PROBE`
  —— **100% 是 QA/E2E 测试夹具**。其中 **35 行 `deleted_at` 非空(软删)**, 唯一未软删的
  `AUD3_PROBE` 还 `enabled=false`。
- ⚠️ **这正是任务本身点名的陷阱**: SQL 直查能看到全部 36 行, 但 JPA 侧的
  `@Where(deleted_at IS NULL)` 会让 `strategyRepo.findActiveByFactoryIdOrderByPriorityAsc(...)`
  **恒定查到 0 条可用策略**。
- 下游实测: `pricing_application_logs` 全库 **69 行**(真实调用产生, 非测试),
  `strategy_id` 非空 **0**、`applied_strategies` 非空数组 **0**、`discount > 0` **0**。

**判定**: 不是"有人配了、代码不读"的经典死配置, 而是**"配置从未真正存在过"** ——
36 行配置全部是测试期间留下的残渣(且几乎全部自己软删了), 真实业务用户从建厂到今天
**从未成功配置过一条生效的定价策略**, 尽管功能界面/引擎/落表全部打通、真被调用 69 次。
建议措辞: 这不是代码 bug, 是"能力已交付但用户侧从未采纳"。

### 4. `bom_recipe_items.primary_code` / `.primary_code_ref`

- 总行 61, 非空各 **46**(经命名核对, **0 行是测试夹具** —— 是真实业务数据)。
- 代码: 仅 `BomRecipeServiceImpl.java:944-945`、`BomCopyServiceImpl.java:239,280-281`、
  `SkuAssemblyService.java:316-317` 做拷贝, **无查询/无分支**。
- entity 自己的 javadoc(`BomRecipeItem.java:222-227`)承认: 这是"搜索/统计用的冗余字段",
  而**当前没有任何搜索/统计功能真正用它**。
- ⚠️ 排查中发现一个容易踩的坑: `BomSeasoningWorkspaceServiceImpl.java:698` /
  `BomItemSubstituteServiceImpl.java:536-537` 也调用 `getPrimaryCode()`, 但那是
  **`RawMaterialType.primaryCode`**(另一张表的同名字段, 见下文第 11 条 —— 那个是真的在用),
  容易被误判成这里在用。

**判定**: 死配置, 且是 46 行真实业务数据在被拷贝却从不被消费。

### 5. `payment_requests.purpose_code`

- 总行 26(10 行未软删), 非空 **0**。
- 代码: **全仓零引用** —— 连 API 都没开放设置入口, 不是"配了没人读", 是"压根没接线"。

**判定**: 死配置, 且是最彻底的一种(没有任何写入路径, 纯 schema 遗留)。

### 6. `workflow_task_ports` —— 一列"配了但从不变"+ 三列"从未配过"

`conversion_mode` 总行 43, 非空 **43(100%)**, 但**全部 43 行的值都是同一个常量
`'ACTUAL_WEIGHT'`**(前端 `workflowModel.ts:439` 定义了 4 种可能值
`ACTUAL_WEIGHT|FIXED_RATIO|SUM_OUTPUTS|FORMULA`, prod 里另外三种**从未出现过**)。
代码侧(`ProductProcessWorkflowRuntimeServiceImpl.java:450-458`)只把它拷进 `PortDTO`,
全仓没有任何地方 `switch`/`equals` 这个值做分支。

同表另外三列, 总行同为 43, 非空 **0**:

| 列 | 非空 |
|---|---|
| `quantity_mode` | 0 |
| `conversion_from_unit_code` | 0 |
| `conversion_to_unit_code` | 0 |

⚠️ **值得单列出来的不一致(判不准, 归 c 类)**: `quantity_mode` 在后端从未有过非空值,
但 **web-admin 前端有真实的分支逻辑在处理它**——
`workflowModel.ts` 定义 `quantityMode?: 'AUTO_CONVERT' | 'FIXED_RATIO'`,
`ProductProcessWorkflowEditor.vue:4262,4394` 与 `workflowUnits.ts:332-336` 有
`withoutQuantityRelationship()` 之类的函数会**读它并据此删除/保留其它字段**,
测试夹具(`__tests__/WorkflowProcessNode.spec.ts` 等)大量构造带值的 `quantityMode`。
即: **前端把它当一个真实存在、会变化的字段在处理, 但它在后端持久层从未真正落过值。**
这是"前后端两端各自以为对方在处理"的经典断点, 建议 owner 单独确认: 是保存路径丢了这个字段,
还是这个字段目前所有真实工作流场景下都合法地不适用(需要产品侧确认哪一种)。

**判定**: `conversion_mode` 归 (a)(配了但只有一个值、从不驱动分支); 另外三列归 (a)(从未配过,
代码也不读); `quantity_mode` 单独标 (c), 附上文说明。

### 7. `bom_price_adjustment_proposals.recipe_code`

- 总行 249, 非空 **245**, 其中命名匹配测试夹具 **93 行**, 真实业务 **152 行**。
- 代码: 唯一写入点 `BomPriceAdjustmentServiceImpl.java:145`(`setRecipeCode` 做快照拷贝),
  **无查询、无比较、无分支**。

**判定**: 死配置, 且规模不小(152 行真实业务数据被拷贝进这张表却从未被读取消费)。

---

## 二、(b) 合法的空 / 合法默认 —— 逐条实测(排除误报)

### 8. `production_reports.report_mode`

- 总行 34, 非空 **34(100%)**, 全部是同一个值 `MODE_1`。
- **不是死配置**: `ReportMode` 是真实枚举(`MODE_1/MODE_2/MODE_3`), 代码里有真实分支
  (`WorkReportingServiceImpl.java:69` `mode == ReportMode.MODE_2 ...`、`:73` `MODE_3 ...`)。
  MODE_2/3 目前没有真实数据命中, 是因为**目前所有真实报工都走单人单批模式**,
  MODE_2(按批次)/MODE_3(多工人)是已实现但尚未被真实业务场景触发的能力, 不是漏读。

### 9. `bom_recipe_items.semi_finished_ref_code`

- 总行 61, 非空 **0**。
- 代码有真实读取逻辑: `NestedBomCostService.java:78-91` —— `if (semiFinishedRefCode != null)`
  走半成品库存直查成本, **否则回退到子 BOM 成本计算**。
- **判定为合法的空, 不是死配置**: 代码对"从未配置"这件事有明确、正确的兜底路径
  (走子 BOM 汇总成本), 61 条真实配方项从未需要覆盖这条路径, 属于设计内的可选项从未被
  行使, 不是"配了没人理"。

### 10. `factory_equipment.iot_device_code` / `.scale_brand_model_id`

- 总行 8, 非空 **0**, 且**这 8 行本身 100% 是测试/审计夹具**
  (`ZTESTC-*` / `E2E测试设备-*` / `审计测试设备-*`, 命名逐行核对)。
- 代码有真实读取逻辑: `DeviceManagementServiceImpl.java:436-477`(按 `iotDeviceCode` 兜底查设备)、
  `ScaleDeviceController.java:141-142,367-368`(按 `scaleBrandModelId` 查品牌型号)。
- **有真实、权限受控、RN 客户端可达的用户入口**: `EquipmentController.java:44-75`
  (`@RequirePermission("equipment:read_write")`), RN 侧
  `screens/workshop-supervisor/equipment/WSEquipmentScreen.tsx` 等真实屏幕在调它。

**判定**: 不是死配置(功能完整、代码会读、入口真实可达) —— 但是一个**更值得关注的相邻事实**:
**`factory_equipment` 全库 8 行, 全部是测试夹具, 零真实业务行**。设备管理功能"接好了",
但**从未被任何真实工厂使用过一次**。这不属于本次"字段死配置"的定义, 但作为伴生发现列出。

---

## 三、正常/已验证在用(排除法证据, 供交叉核对仪器可信度)

以下几列进入了候选扫描、非空占比高, 逐条代码核实后确认**真实在被消费**, 排除死配置:

| 列 | 总行 | 非空 | 结论 |
|---|---|---|---|
| `raw_material_types.primary_code` | 483 | 442(34 测试/408 真实) | 真实用于 BOM 替代料校验(`BomItemSubstituteServiceImpl.java:536-541`)、按码查料(`RawMaterialTypeController.java:189-196`)、辅料分类(`BomSeasoningWorkspaceServiceImpl.java:698`) |
| `bom_recipes.recipe_code` | 51 | 51(14 测试/37 真实) | 用于唯一性校验与编号生成(`BomRecipeRepository.java:100-121`) |
| `bom_recipes.cost_allocation_ratio` | 51 | 51 | 真实驱动成本分摊(`StandardCostServiceImpl.java:216-219`)、多产出配比闸(`BomWorkflowRevisionService.java:1028-1033` 强制求和=100%) |
| `bom_seasoning_items.subsequent_pot_ratio`("续锅比") | 42 | 11 | 真实驱动多锅调料成本非线性放大(`RecipeCostCalculator.java:70-76`), 与团队既有笔记吻合 |
| `product_types.template_id` | 229 | **0** | 代码有真实分支(`SkuAssemblyService.java:79` 阻止模板二次组装、`ProductWorkProcessRecommendTool.java:380` 工艺推荐族匹配), 但**目前没有任何产品被标记为模板**——功能正常但从未被使用, 归入 (b) |
| `work_processes.default_output_material_kind` | 211 | 211(100%, NOT NULL) | 真实驱动 workflow 目录校验(`ProductProcessWorkflowCatalogValidator.java:167-174`, 类型不符会拋校验错) —— 不是摆设默认值 |

---

## 四、判不准 / 未覆盖 —— 显式列出(不假装普查完了)

1. **AI/意图/对话子系统**(`ai_intent_configs` / `conversation_*` / `intent_match_records` /
   `tool_call_records` / `semantic_cache*` / `label_qc_*` / `active_learning_samples` 等, 约 60 列):
   属于另一套 AI 服务内部状态机, 判"是否被真实业务引用"需要理解 AI 意图路由/学习闭环,
   与本次种子案例(BOM/工序/生产)不是同一套判据体系。**本轮未深挖**, 只做了系统性计数
   (见附录), 建议单独立项审计。
2. **SmartBI 子系统**(`smart_bi_*`, 约 15 列): 独立 Python 服务 + 独立库
   (`smartbi_prod_db`), 这些列存在于 `cretas_prod_db`(Java 库)里可能是历史遗留或双写,
   **本轮未核实这是否是活功能还是被 smartbi_prod_db 侧取代的旧表**, 判不准。
3. **`users.employee_code`**(78 行, 非空 0): 有读取代码
   (`WorkerAssignmentServiceImpl.java:79` → `LinUCBServiceImpl.java:134,532`, 传入排班算法),
   但只是"取值传给下一张表", 全部 78 个真实用户从未填过, null 时上游有判空守卫不会报错。
   介于 (a)/(b) 之间, 未继续深挖排班算法拿到 null 之后的实际行为影响面, 列为 (c)。
4. **`material_requisitions` 表**(非本次候选列, 是排查副产品): 全库 **0 行** ——
   真实领料数据在 `factory_material_requisitions` / `factory_material_requisition_items`。
   这张 0 行表本身可能是废弃表, 超出"字段"范围, 只作为线索移交, 未展开。
5. **factory_module_configs / factory_type_blueprints / factory_templates**: 均只有 1 行
   (全字段 1/1), 像是"平台侧只建过一条示范配置", 未核实是否被任何工厂初始化流程真实读取过
   —— 数据量太小(n=1), 无法用命名/租户过滤区分真假, 判不准。

---

## 五、排除的表(migration/backup, 18 列, 不逐列判定)

`migration_f006_recode_20261029_72`(3列) / `migration_f006_segment_retire_20261029_72`(1列) /
`migration_liushanmen_code_retire_20261029_67`(2列) /
`migration_liushanmen_segment_retire_20261029_69`(1列) /
`migration_lsm_bizcode_clear_20261029_73`(1列) /
`repair_backup_liushanmen_batch_cost_20260627`(1列) /
`repair_backup_liushanmen_labor_reports_20260627_1027`(5列) /
`repair_backup_liushanmen_labor_batches_20260627_1027`(1列) /
`backup_e2e_w2_ventries_20260815`(1列) / `bak_bom_recipes_20260731`(2列)

这些是一次性数据迁移/修复动作留下的审计快照表, 不是应用运行时读取的配置, 与本次"死配置"
的定义(生产代码路径会读、但读不到真实数据)不是同一类问题。

---

## 附录: 系统性扫描完整读数(350 列, 按非空行数降序)

> 完整落库口径: `information_schema.columns` + 逐列 `count(*)/count(col)`,
> `table_schema='public'`, 见口径第 1-2 条。数值为 prod 实测(`cretas_prod_db`, 2026-08-18)。
> 本表只列 `表名 / 列名 / 总行 / 非空行`, 是否 (a)/(b)/(c) 见上文正文(仅覆盖正文提到的列;
> 未提及的列均属"四、判不准/未覆盖"里第 1/2 条覆盖的 AI/SmartBI 子系统, 或非空=0 且
> 未被列为重点的次要候选 —— 后者绝大多数是自身从未被使用过的可选字段, 空即合法, 未逐条深挖)。

```
table_name                                    | column_name                        | total_rows | non_null_rows
label_qc_photos                               | ai_model                           |       4194 |          4180
label_qc_tray_crops                           | padding_ratio                      |       3591 |          3591
label_qc_tasks                                | sku_code                           |       1066 |          1066
reflection_memories                           | correction_strategy                |        593 |           593
platform_role_permissions                     | module_code                        |        469 |           469
platform_role_permissions                     | role_code                          |        469 |           469
raw_material_types                            | primary_code                       |        483 |           442
ai_intent_configs                             | intent_code                        |        416 |           416
conversation_memory                           | last_intent_code                   |        368 |           366
migration_f006_recode_20261029_72             | new_code / old_code                |        305 |           305
ai_intent_configs                             | config_version                     |        416 |           266
migration_f006_segment_retire_20261029_72     | segment_code                       |        259 |           259
bom_price_adjustment_proposals                | recipe_code                        |        249 |           245
work_processes                                | default_output_material_kind       |        211 |           211
product_unit_conversions                      | from_unit_code / to_unit_code      |        178 |           178
tool_execution_audit_events                   | execution_mode / result_code       |        173 |           173
scheduling_training_data                      | temporary_worker_ratio             |        136 |           136
ai_learned_expressions                        | intent_code                        |        131 |           131
ai_learned_expressions                        | embedding_model                    |        131 |           123
behavior_calibration_metrics                  | total_output_tokens                |        113 |           113
intent_preview_tokens                         | execution_mode / intent_code       |         89 |            89
additive_limits                               | additive_code                      |         83 |            83
users                                         | role_code                          |         78 |            78
bom_recipe_items                              | is_optional                        |         61 |            61
migration_f006_recode_20261029_72             | old_business_code                  |        305 |            61
work_processes                                | semi_finished_output_code          |        211 |            55   ← 已修 PR#2867
ai_intent_config_history / food_intent_config | intent_code                        |         54 |            54
smart_bi_metric_formulas                      | metric_code                        |         54 |            54
permission_registry                           | permission_code / source_method    |         53 |            53
bom_recipes                                   | cost_allocation_ratio / recipe_code|         51 |            51
bom_recipe_items                              | primary_code / primary_code_ref    |         61 |            46   ← 死配置 (a)
payment_records                               | payment_method                     |         46 |            44
workflow_task_ports                           | conversion_mode / unit_code        |         43 |            43   ← 单值 (a) / 正常
workflow_task_ports                           | material_primary_unit_code         |         43 |            43
workflow_task_ports                           | selection_group_mode               |         43 |            43
voucher_entries                               | subject_code                       |         42 |            42
conversation_sessions                         | known_intent_code / session_mode   |         39 |            39
notify_templates                              | template_code                      |         38 |            38
factory_validation_rules                      | module_code / rule_code            |         36 |            36
intent_match_records                          | match_method                       |         36 |            36
pricing_strategies                            | strategy_code/name/type            |         36 |            36   ← (a) 全为测试夹具, 见正文3
ingredient_nutrition_facts                    | ingredient_code                    |         35 |            35
business_rules                                | action_config_json / rule_code     |         34 |            34
production_reports                            | report_mode                        |         34 |            34   ← (b) 单值但机制真实
sales_order_items                             | material_supply_mode/processing_mode|        158 |            31
customers                                     | customer_code                      |         29 |            29
sales_orders                                  | customer_stock_fulfillment_mode    |         29 |            29
smart_bi_query_templates                      | query_template                     |         29 |            29
sales_orders                                  | material_supply_mode/processing_mode|         29 |            28
payment_requests                              | payment_method                     |         26 |            26
factory_scheduler_configs                     | task_code / tool_or_method         |         25 |            25
indicators                                    | compute_strategy                   |         21 |            21
module_schemas                                | default_config / module_code       |         21 |            21
factory_default_values                        | field_code / module_code           |         19 |            19
production_batches                            | workflow_output_units_by_product   |         19 |            19
production_batches                            | workflow_selection_mode            |         19 |            19
suppliers                                     | supplier_code                      |         17 |            17
process_sheet_row_change_log                  | process_code                       |         16 |            16
inventory_ledger_snapshots                    | material_code                      |         14 |            14
sales_delivery_records                        | delivery_method                    |         29 |            14
labels                                        | label_code                         |         13 |            13
process_sheet_rows                            | process_code                       |         13 |            13
bom_seasoning_items                           | subsequent_pot_ratio               |         42 |            11   ← (b) "续锅比", 真实在用
sales_delivery_items                          | source_warehouse_code              |         34 |            11
smart_bi_chart_templates                      | template_code / template_name      |         11 |            11
production_plans                              | workflow_output_unit/units_by_product/selection_mode | 9 |     9
product_samples                               | sample_code                        |          9 |             9
sales_order_items                             | source_warehouse_code              |        158 |             9
supplier_materials                            | supplier_material_code             |         53 |             9
approval_workflow_instances                   | module_code                        |          8 |             8
factory_equipment                             | equipment_code                     |          8 |             8   ← 表 100% 测试夹具, 见正文10
factory_formulas                              | formula_code / module_code         |          8 |             8
scale_brand_models                            | brand_code / model_code            |          7 |             7
labels                                        | trace_code                         |         13 |             6
voucher_subject_mappings                      | credit/debit_subject_code          |          6 |             6
production_reports                            | semi_output_quantity/unit/semi_code|         34 |          1-2   ← 全库 100% NULL 已知(既有笔记), 交叉核对一致
product_types                                 | template_id                        |        229 |             0   ← (b) 机制真实但从未被使用, 见正文3
product_types                                 | default_sop_config_id              |        229 |             0   ← 死配置 (a), 见正文1
product_types                                 | form_template_id                   |        229 |             0   ← 死配置 (a), 见正文1
product_types                                 | settlement_method                  |        229 |             0   ← 死配置 (a), 最彻底, 见正文1
product_work_processes                        | aux_alloc_method / aux_basis       |          4 |             0   ← 已知(既有笔记), 交叉核对一致
pricing_application_logs                      | strategy_id                        |         69 |             0   ← 死配置 (a), 见正文3
payment_requests                              | purpose_code                       |         26 |             0   ← 死配置 (a), 见正文5
users                                         | employee_code                      |         78 |             0   ← (c) 判不准, 见正文四.3
workflow_task_ports                           | quantity_mode                      |         43 |             0   ← (c) 判不准(前端有逻辑, 后端不落值), 见正文6
workflow_task_ports                           | conversion_from_unit_code/to_unit_code |     43 |             0   ← 死配置 (a), 见正文6
factory_equipment                             | iot_device_code / scale_brand_model_id |      8 |             0   ← (b), 见正文10
bom_recipe_items                              | semi_finished_ref_code             |         61 |             0   ← (b) 合法回退, 见正文9
sop_configs                                   | photo_config_json / validation_rules_json |   0 |             0   ← 死子系统, 见正文2
production_batches                            | sop_config_id                      |         19 |             0   ← 死子系统, 见正文2
factories                                     | industry_code / region_code        |          3 |             0   ← n=3 太小, 未展开
factory_equipment                             | iot_device_code                    |          8 |             0   ← 见正文10(重复列出用于强调)
material_requisitions                         | section_code / stall_code          |          0 |             0   ← 表本身 0 行, 见正文四.4
(其余约 150 列: 全 0/0 或 AI/SmartBI 子系统列, 见正文"四、判不准/未覆盖", 未逐条展开)
```

> 说明: 上表为可读性做了同表同分组的折叠(如 `bom_recipes.cost_allocation_ratio / recipe_code`
> 写成一行), 完整逐列 350 行原始输出留在
> `D:\Temp\claude\...\af544de2.../scratchpad\dead-config-audit\03_scan_out.log`
> (本机临时目录, 未入库, 如需复核可重跑本文件"口径"章节的 SQL 重新生成 —— 该目录不在本仓
> git 历史里, 是本次会话的工作产物)。

---

## 六、小结(给 owner 的行动建议, 仅陈述事实, 不代为决策)

**确认为 (a) 死配置、建议排期清理或补上下游逻辑的**:

1. `product_types.settlement_method` —— 零引用, 建议确认是否该删列或该接上
2. `product_types.default_sop_config_id` / `form_template_id` + 整个 SOP 子系统 —— 要么补 SopConfigService, 要么砍掉这条从未启用的能力线
3. `pricing_strategies` 全表 —— 功能已完整交付但真实业务零采纳, 建议确认是否要主动推广/培训, 而不是继续当作"已完成"
4. `bom_recipe_items.primary_code` / `.primary_code_ref` —— entity 自己的 javadoc 承认是死冗余
5. `payment_requests.purpose_code` —— 无写入入口, 纯 schema 遗留
6. `workflow_task_ports.conversion_mode`(单值)+ `conversion_from_unit_code` / `conversion_to_unit_code`(从未配过)
7. `bom_price_adjustment_proposals.recipe_code` —— 152 行真实业务数据被拷贝却从不消费

**建议 owner 单独确认的 (c)**:

- `workflow_task_ports.quantity_mode` —— 前端有真实分支逻辑, 后端从未落值, 需要确认是保存链路丢字段还是当前场景下都不适用
- `users.employee_code` —— 排班算法在用但从未有真实数据
- AI/意图子系统与 SmartBI 子系统的死配置普查(本轮范围外, 建议另开一轮, 换一套针对 AI 服务的判据)
