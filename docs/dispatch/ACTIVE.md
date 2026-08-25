# Dispatch 台账 — ACTIVE
**最后更新**：2026-08-25

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-08-25-active-history.md](archive/2026-08-25-active-history.md)、[2026-08-24-active-history.md](archive/2026-08-24-active-history.md)、[2026-08-21-active-history.md](archive/2026-08-21-active-history.md)、[2026-08-17-active-history.md](archive/2026-08-17-active-history.md)、[2026-08-16-active-history.md](archive/2026-08-16-active-history.md)、[2026-08-15-active-history.md](archive/2026-08-15-active-history.md)、[2026-08-14-active-history.md](archive/2026-08-14-active-history.md)、[2026-08-13-active-history.md](archive/2026-08-13-active-history.md)、[2026-08-12-active-history.md](archive/2026-08-12-active-history.md)、[2026-08-11-active-history.md](archive/2026-08-11-active-history.md)、[2026-08-10-active-history.md](archive/2026-08-10-active-history.md)、[2026-08-09-active-history.md](archive/2026-08-09-active-history.md)、[2026-08-07-active-history.md](archive/2026-08-07-active-history.md)、[2026-08-04-active-history.md](archive/2026-08-04-active-history.md)、[2026-08-03-active-history.md](archive/2026-08-03-active-history.md)、[2026-08-02-active-history.md](archive/2026-08-02-active-history.md)、[2026-08-01-active-history.md](archive/2026-08-01-active-history.md)、[2026-07-31-active-history.md](archive/2026-07-31-active-history.md)、[2026-07-30-active-history.md](archive/2026-07-30-active-history.md)、[2026-07-29-active-history.md](archive/2026-07-29-active-history.md)、[2026-07-28-active-history.md](archive/2026-07-28-active-history.md)、[2026-07-27-active-history.md](archive/2026-07-27-active-history.md) 与 [2026-07-26-active-history.md](archive/2026-07-26-active-history.md)，此前历史见 [2026-07-25-active-history.md](archive/2026-07-25-active-history.md)。

## 在飞任务

- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811` — `blocked` — Owner: `/root` — 已有代码进入公司迁移树，但正式 audit 仍因 `trayal_0e5a259f_030` unjudgeable fail closed。计划任务 `Cretas-Liushanmen-VisionLab` 已迁到 clean exact-main 工作树并取得 LastResult=0；剩余阻塞仅为该 ROI 人工裁决。保留视觉 scope；LocateAnything hardening 与其串行，模型、人工真值、原图和生产不改。
- `LLM-PROVIDER-CLIFF-20260815` — `blocked` — Owner: `/root` — DeepSeek 接线、七槽、悬崖模拟、每日健康与复审 cron 已进入迁移树并在线运行；剩余仅 2026-09-13 aistore 续期换 key、2026-09-04/11-15 运营复审等外部动作。旧 `llm_router.py` 代码锁已释放；不得把供应商控制台动作扩大为代码改动。

- `UX-RN-RESTAURANT-PROCUREMENT-DELIVERY-20260802` — `blocked` — Owner: `/root` — 实现提交已进入迁移树，代码与 Expo Web 验收可核销；当前餐饮 RN OTA runtime 与真实设备验收无法确认。实现文件 scope 已释放；如继续，仅新建 exact-commit OTA/设备验收项。

- `SEC-CREDENTIAL-ROTATION-20260719` — `blocked` — Owner: `/root` — tracked tree、服务器副本、数据库/JWT/internal/BaoTa/OSS RAM 替换已完成；仍需各供应商控制台吊销旧 Aliyun 主账号 AK、旧 Model Studio/Zhipu/DeepSeek key 与微信长期凭证并验证失效。旧 broad 代码锁已释放；不得在本任务做历史重写。

- `ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001` — `blocked` — Owner: `/root` — 候选 DTO、置信度/reason、同厂历史匹配和 Web 展示已进入迁移树；分类建议目标测试与 Excel preview 复用未完整证明，当前无执行者。陈旧 scope 已释放；恢复时按最新 Base 重新登记。

- `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` — `blocked` — Owner: `/root` — CUSTOMER_OWNED 与销售 lineage 已有，但 PURCHASE/CUSTOMER_SUPPLIED/TRANSFER/退回/退料/期初/调整的统一任务与来源矩阵未完整实现。当前无执行者，旧高风险 scope 已释放；恢复时需重新登记 Entity/Flyway/JPA 门禁。

- `BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001` — `blocked` — Owner: `/root` — V94、allocation 核心、财务 AP 未清项付款与旧采购付款只读边界已进入迁移树；四维状态、付款条款里程碑/计划/三单匹配未完整实现。CORE 子任务已归档；父任务旧广域锁释放，恢复时按最新 Base 重锁。

- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001` — `blocked` — Owner: `/root` — 净需求、客户来料、ownership/idempotency 与旧直接收料 fail closed 已有；严格同批 identity/单位/日期/QC/cost 匹配及独立库存 transaction 验收不足。需求仍有效且跨模块，保留 scope，`NOT_DEPLOYED`。


- `ENH-F006-SUPPLIER-IMPORT-001` — `blocked` — Owner: `/root` — 供应商必填资料、Excel 预览/幂等确认、状态生命周期、采购门禁与详情信息架构尚无本轮完整 tree/test 核销证据；当前无专用 PR。需求和 scope 均保留，恢复时先核对公司 main 的现有实现，严格 `NOT_DEPLOYED`。

- `BUG-F006-R2-SKU-UNIT-001` — `blocked` — Owner: `/root/supplier_frontend` — SKU displayUnit、净含量单位、同维度 canonical 化与历史回填尚无本轮完整 tree/test 核销证据；保留 Web scope，Java 契约仍须协调者另行审计，严格 `NOT_DEPLOYED`。

- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001` — `blocked` — Owner: `/root/workprocess_governance` — canonical/mergedInto、未来候选排除、既有 Workflow/SKU/计划/报工引用不迁移，以及排序/单位从主数据移除的完整 JPA/Web 验收尚未本轮核销；保留 scope，生产重复组零治理。

- `FEATURE-F006-R2-BOM-SUBSTITUTE-001` — `queued` — Owner: `/root` — Java CORE 已归档，但父任务的 Web、计划、领料、报工、成本和追溯快照尚未证明完成；继续由统一 Workflow/BOM 任务串行吸收，保留父任务 scope，严格 `NOT_DEPLOYED`。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE` — `in-progress` — Owner: `claude-code/f006-remaining-closure` — Base SHA `fee2509fa667344f7f8ba7a7b91a5fb2a32ae2a2`，worktree `cretas-f006-bom-gate` / branch `codex/f006-workflow-bom-gate-20260825`。**本批已做**：删掉 `applyPlanUnitAuthority` 里一道**到不了的** BOM 准入分支（`mode == WORKFLOW && activeRecipe.isEmpty()` 位于 `mode == WORKFLOW` 早退之后，条件恒为 false，`ACTIVE_BOM_REQUIRED` 从写下来那天起一次都没执行过）；WORKFLOW 模式的 BOM 准入由 `resolvePinnedBomAuthority` 的 `ACTIVE_BOM_FAMILY_REQUIRED` 唯一负责（活闸，已由新增闸钉住仍在）。新增 `PlanBomGateHasNoUnreachableBranchContractTest`（4 条，含阴性对照：`ProductConfigurationReadinessService` 那处**活的**同名码不许被顺手删掉；含阳性对照防「剥注释剥过头 ⇒ 恒真式」）。**实测定性（只读，生产库）**：① F006 共 13 个 ACTIVE `bom_recipes`，其中在 `bom_versions` 里有对应版本快照的是 **0 个**；`bom_versions` 全库仅 9 行、最近写入停在 2026-08-02 ⇒ **画布保存/激活 BOM 那条路根本不写 `bom_versions`，只有 ECN 手工流程（`BomVersionService`）才写**；而成本归集 `resolvePinnedPackaging` 读的正是 `bom_versions.snapshot_json` ⇒ 对画布建的 BOM 永远读不到（形态 D：同一个东西两份，读的是不再维护的那份）。② 但**退回实际耗用也救不了**：那 7 个批次 `material_consumptions` 里包材行数 **0**；`bom_recipe_items` 里 PACKAGING 的 `item_cost` 5 行全是 `0`（RAW 16 行全是 `null`）。⇒ 包材成本确实不可知，成本侧诚实阻断**是正确行为**，不是缺陷。**未决产品问题（需 Owner 裁定，本轮不猜）**：(a) BOM 完整性校验现在明确写着「工序辅料和包材可按实际需要补充」（包材可选），那么「包材可选 ⇒ 成本永远算不出」是否可接受？(b) `bom_recipe_items.item_cost = 0` 到底是「确认为零」还是「没填」？两者对 COGS 完全不同，而现在写的是 `0` 而不是 `null`。(c) 成本归集该不该从 `bom_versions` 改读 `bom_recipes`+`bom_recipe_items`，还是该让画布激活时补写 `bom_versions` 快照？停止条件：不得改写历史快照或生产数据；不得把「没填价」按 `0` 计入 COGS。

- `BUG-F006-PRODUCTION-COST-CLOSURE-001` — `in-progress` — Owner: `claude-code/f006-remaining-closure` — Base SHA `7d51fe22597e80ee9cd572575f58539c249fb170`，worktree `cretas-f006-cost-labor-split` / branch `codex/f006-cost-labor-split-20260825`。**已合入并部署三批**（PR #58 / #59 / #60）：① 算不出成本时说出真实原因；② 「设备/其他不强制填写」裁定补到 `costComplete`；③ 人工「明细不全」与「金额未知」拆开（`LABOR_TIME_DETAIL_MISSING` 不阻断 / `LABOR_COST_UNKNOWN` 照旧阻断）。生产核验：active `10010`/`cretas-backend`，PID `2237799` 启动 `14:53:57`，JAR mtime `14:53:34`，运行制品含 `LABOR_TIME_DETAIL_MISSING`×2 / `LABOR_COST_UNKNOWN`×1。**部署后只读探针（23 个 F006 生产批次，真跑接口非推断）**：`totalCost` 非空由 `2/23` 提升到 `16/23`；交接件点名的 `PB-PLAN-1787622818112-B3898275-15036` 已算出 `totalCost=93.12` / `perBoxCost=9.31`（= 原料 40.32 + 人工 51.57 + 包材 1.23，逐项对得上）；`LABOR_RATE_OR_TIME` 已从阻断码分布中消失；判据「无阻断项 ⟺ totalCost 非空」不一致 `0` 条；`calculationStatus` 全部仍为 `PARTIAL`（不阻断 ≠ 藏起来）。**剩余 7/23 条阻断码全部是 `PINNED_BOM_SNAPSHOT_MISSING`，且已定性为「成本侧行为正确」**：实测 F006 共 8 个 `bom_versions`（全部有 snapshot），而这些计划锁定的 4 个 `bom_recipe_id` 在 `bom_versions` 里**一个版本都没有** ⇒ 包材成本确实不可知，诚实阻断是对的。真正的修复属于 `F006-R2-WORKFLOW-FIRST-BOM-GATE`：不该让「没有激活 BOM 版本」的计划建单。**另一遗留（形态 D 两条轨）**：`PUT /{planId}/costs` 写 `production_plans.actual_equipment_cost`（喂分析/AI 报表），而成本归集读 `production_batches.equipment_cost`，用户补录进不了单位成本。停止条件：`totalCost` 的 COGS 口径不得松动（缺**单价**类照旧阻断），生产实体只读、零业务写入。

- `QA-WAREHOUSE-STORAGE-UI-VERIFY-20260825` — `review` — Owner: `claude-code/warehouse-storage-ui-verify` — Base SHA `ff768d15e0078d7b9efa169a41ad4f0c406c679d`，worktree `cretas-wh-ui-verify` / branch `codex/claude-warehouse-storage-ui-verify-20260825`。**任务**：对 PR#76 已上线的「仓储空间 / 现场盘点 / 仓库平面图 / AI 底图 / OA 数量审批」做真实生产视觉验收（Web Admin + 小米 Mi 11 真机），并修实测缺陷。**只读核验（全部重测，非引用快照）**：`origin/main` 已前进到 `ff768d15e0`；生产部署 SHA `2a585cf568`（是 main 祖先，差集纯 docs）；Java active 槽已由 `10010` 切到 **`10020`/`cretas-backend-green`**（PID 2331770，启动 20:33:42 晚于 jar mtime 20:33:19，运行制品含 `WAREHOUSE_LAYOUT`×2 与 `STOCKTAKE_APPROVAL_WORKFLOW_REQUIRED`×1）；Web index sha256 `69fe0874…` 与回执一致；OTA manifest 仍是 `1787659607345`，真机重启后 `CheckCompleteUnavailable` ⇒ 今天的包**已真正加载运行**。**P0 已闭环（真实 HTTP，非反射）**：无 token/伪 token→401；跨厂 GET/PUT/recognize→403 且零写入；本厂非法前缀→400。**修复 6 项**：① `menuConfig.ts` 从未登记 `/warehouse/storage-locations` ⇒ 功能上线即不可达，实测生产 `factory_feature_config` 里 `WAREHOUSE_LAYOUT` **0 行**（因果对上）；② 保存后 150ms 内再点「+」弹窗永久卡在 `is-closing`、全部按钮失效（确定性复现 + 摘/加 class 的变异证明因果），修法为 dialog `:key` 重建；③ 成品移位后端只校验非空+长度，实测 `ZZ99-NOT-CONFIGURED` 返回 200 并写入 ⇒ 补本厂位置存在性 fail closed；④ 位置绑到货架层后无法解绑/换层/删除 ⇒ 补 unbind + 区域/位置/货架/楼层删除（带护栏，不做静默级联）；⑤ 浮点误差 `168.96999999999997` 直接打给仓管且撑破卡片 ⇒ 展示层收敛 + 断行；⑥ AI 识别原本**未经确认直接覆盖配置** ⇒ 改为预览确认。另修：物料名缺失时标注「按批次号显示」（F006 139 条中 22 条 `materialName` 为 null）、类别计数与列表同口径、图标按钮补可访问名、拖货架不再每次全量 PUT 底图。**验证**：`vue-tsc --project tsconfig.app.json` EXIT=0；Vitest 19 条；Java `WarehouseLayoutControllerTest` 11 + `StocktakeWorkflowIntegrationTest` 8（surefire 报告为准）；四处修复各做过变异对照并确认变红。**未解决（需 Owner 裁定，本轮不擅自改）**：(a) App 端仓储配置的两个消费屏当前对所有可用账号不可达 —— `SIMPLIFIED_WAREHOUSE_NAV = true` 按 owner 决策隐藏了仓管的「库存」Tab，而 `f006_warehouse_worker` 账号 `is_active=false`，`factory_super_admin` App 端无任何仓储入口；这与产品契约「手机也要能查看同一份仓库平面配置」冲突。(b) 生产成品批次的 `storage_location` 里被别的模块写入了来源描述，含英文调试串 `Workflow terminal output warehouse receipt`（16/18 条），本轮只在 UI 上标为「位置无效」，存量清洗未做。F006 写入已获 Owner 明确授权，仅限 WAREHOUSE_LAYOUT 配置与 1 条成品移位（已还原为 LC01），库存数量零变更。

## Scope 锁地图

- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811`：仅 `tools/vision-lab/work_area*.py`、`tools/vision-lab/mine_tray_queue.py`、`tools/vision-lab/tray_workflow.py`、`tools/vision-lab/evaluate_tray_candidate.py`、对应 `tools/vision-lab/tests/`、`docs/ops/liushanmen-vision-lab.md`、`docs/dispatch/ACTIVE.md` 与当日归档；不得改写人工真值、保护集、原图、现有模型或生产。


- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001`：销售需求、BOM净需求、采购建议、客户来料、仓储来源任务及对应 Java/Web/测试/dispatch；涉及 Entity/Repository/Flyway 时必须重登记最新 Base 并通过真实 JPA/迁移门禁。不得开启普通手工收料写入口或修改生产数据。


- `ENH-F006-SUPPLIER-IMPORT-001`：供应商 Java Entity/DTO/Controller/Service/Repository、采购门禁、共享 Excel 导入与目标测试；Web 供应商列表/详情/新增编辑/导入及 API/types/tests；必须真实 JPA Context（如触及查询），生产业务写入为 0。

- `BUG-F006-R2-SKU-UNIT-001`：仅 Web SKU 新建/编辑基础规格、displayUnit/净含量单位 helper 与目标测试；Java 净含量契约须另行登记不重叠 scope。

- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001`：Java WorkProcess/Workflow/SKU 关联、DTO/Repository/Service/Controller、Flyway/JPA tests；Web 工序治理及排序/单位字段移除与目标测试；不得迁移既有业务引用或治理生产重复组。

- `FEATURE-F006-R2-BOM-SUBSTITUTE-001`：BOM 替代关系的 Web、版本/计划/领料/报工/成本/追溯快照与目标测试；Java CORE 已完成，父任务不得重复修改 CORE Flyway/Entity，必须与统一 Workflow/BOM 任务串行。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE`：Workflow 草稿结构、BOM 完整性 DTO/Service/API、激活/发布/生产计划准入、Web 阶段引导与目标测试；不得改写历史快照或生产数据。

- `BUG-F006-PRODUCTION-COST-CLOSURE-001`：仅 `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java` 的成本原因组装（`describeCostUnavailableReason` / `usableTotalCost` / 缺项中文标签）与对应 `src/test/java/com/cretas/aims/service/impl/*ContractTest`、`docs/dispatch/ACTIVE.md`。⛔ 不改 `OrderCostBreakdownService` 的成本算法、不改 Entity/Repository/Flyway、不动 `totalCost` 的完整性口径、生产 F006 业务写入为 0（本轮只用只读 GET 与只读 SQL 取证）。

- `QA-WAREHOUSE-STORAGE-UI-VERIFY-20260825`：仅 `web-admin/src/views/warehouse/storage-locations/**`、`web-admin/src/components/layout/menuConfig.ts`、`backend/java/cretas-api/.../controller/WarehouseLayoutController.java` 与其目标测试、`docs/dispatch/ACTIVE.md`。⛔ 不改 Entity/Repository/Flyway、不动 RN 导航的 `SIMPLIFIED_WAREHOUSE_NAV`（owner 决策）、不清洗存量 `storage_location`、F006 之外零业务写入。

## 阻塞项

- VisionLab：正式 ROI audit 仍有 1 个 unjudgeable；计划任务迁移与运行失败已核销。
- LLM 运营：2026-09-13 aistore 续期换 key，以及 2026-09-04/2026-11-15 复审需要 Owner/供应商控制台动作。
- 餐饮 RN：实现已入 main，但当前 OTA runtime 与真实设备验收未确认。
- 凭证：旧 Aliyun 主账号 AK、旧模型供应商 key、Mall 微信长期凭证仍需各控制台吊销/重置并验证失效。
- F006 产品/架构：taxonomy suggestion、统一收料来源、AP 付款完整边界仍是无执行者 backlog；需求驱动收料与客户渐进创建保留 scope，后者须先裁定必填规则。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认并发最多 2 个代码执行代理和 2 个只读/测试代理；不得超过当前运行时槽位，且无独立价值的工作保持 DIRECT。
