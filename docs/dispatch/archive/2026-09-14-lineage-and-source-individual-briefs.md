# 2026-09-14 分发卡：六膳门血缘闭环（Line A）+ 通用源头个体档案 & 拍照核验（Line B）

**Organizer**：本 chat（标题「Organizer · 胜龙牛业/六膳门血缘闭环」）。**Base SHA**：`8c04949d54`（origin/main 2026-09-14）。
**背景**：2026-09-17 与胜龙牛业（养殖+屠宰+加工一体，基因库 = 肉品 DNA 溯源）面谈。Steve 裁定：**先按六膳门的需求把血缘闭环做通，再让胜龙做展示层适配**；功能必须是通用产品能力，不能一看就是为胜龙做的。评估文档在 gitignored `docs/private/shenglong/2026-09-14-胜龙牛业-合作技术评估.md`（只 organizer 本机可见，卡里已把需要的事实抄全）。

## 已核实的事实（origin/main `8c04949d54`，别再重查）

1. 血缘引擎**已存在**：`entity/lineage/BatchLineageEdge`（多态 source/target：`MATERIAL_BATCH`/`PRODUCTION_BATCH`/`FINISHED_BATCH`/`SHIPMENT_RECORD`；edge_type 设计值 `RAW_TO_PRODUCTION`/`PRODUCTION_TO_FINISHED`/`FINISHED_TO_SHIPMENT`/`REWORK`/`BLEND`/`WIP_CONSUME`；forensic，无 `@Where`，`@PreRemove` 禁删）+ `BatchLineageClosure`（触发器 `fn_maintain_lineage_closure` 在 edges INSERT 时维护传递闭包，迁移 `db/flyway/V20260821_02__batch_lineage_schema.sql`）+ `repository/lineage/*` + `ai/tool/impl/lineage/LineageQueryTool`（ANCESTORS/DESCENDANTS/BOTH，maxDepth≤10）+ `CanvasIndicatorsController.batchLineage`（`GET /batch-lineage/{batchType}/{batchId}`）。已有测试：`repository/lineage/BatchLineage{Edge,Closure}RepositoryTest`、`LineageQueryToolTest`、`CanvasIndicatorsControllerTest`。
2. **全仓只有一处写边**：`service/wip/impl/WipInventoryServiceImpl.recordWipLineageEdge`（L1590 附近）写 `WIP_CONSUME`，PRODUCTION_BATCH→PRODUCTION_BATCH，fail-soft（catch 全部异常只 warn，不阻塞报工）。`"REWORK"` 的 9 处命中全是质检处置枚举，与 lineage 无关。⇒ 原料投入、成品入库、出货三段**没有边**，闭包里回不到原料批次，也走不到客户。
3. `TraceabilityServiceImpl.getFullTrace`（L92-153）与 `getPublicTrace` 只走一层：生产批次 → `MaterialConsumption` → `MaterialBatch` → 供应商，外加 `ProcessingStageRecord` + `ProcessSheetRow`（`buildProcessSheetStageInfoList`，其中 `upstreamSources` 只被拼成文本摘要）。**不读闭包**。
4. 三段写边的候选挂钩点（`new MaterialConsumption()` / `new FinishedGoodsBatch()` 站点，执行者按硬约束 8 先数后改）：
   - 原料投入：`ProductionPlanServiceImpl:8505`、`ClerkProcessEntryServiceImpl:858`、`FactoryMaterialRequisitionServiceImpl:1878`、`BatchConsumptionServiceImpl:122/240/276`、`ProcessingServiceImpl:848`、`MaterialBatchServiceImpl:1659/2087`、`TransferServiceImpl:900`、`OrderCostBreakdownService:1194`。只有带 `productionBatchId` 的消费才是 RAW_TO_PRODUCTION；调整/调拨/成本拆分类**显式登记「故意不写」**。
   - 成品入库：`ProductionPlanServiceImpl:7525/7599`、`InterimSettleServiceImpl:589`、`SupplyChainOrchestrator:753` 是生产→成品；`SalesController:203`、`ReturnOrderServiceImpl:701`、`TransferServiceImpl:2067` 不是（登记不写）。`FinishedGoodsBatch` 只有 `productionPlanId`（L141），没有 productionBatchId —— 执行者要确认成品批次到生产批次的映射键。
   - 出货：`new ShipmentRecord()` / `ShipmentRecord.builder()` 在 main 上 grep 为 0 命中，执行者自己找出货落库点（`shipmentRecordRepository`）。
5. 六膳门 197 v2 族图（份额 `inputShareRatio`/回投 `reworkPolicy`/不良品端口/联产 family）已在 prod，回投扣减 `ReworkPolicyAutoDeduction` 已接进 `submitRow`。
6. 视觉：`PythonLLMClient.visionChat(byte[] imageBytes, String contentType, String prompt)`（L521）→ Python `backend/python/llm/api/endpoints.py` `POST /vision`；RN 拍照→base64→Java 的现成样板是 `screens/factory-admin/device/AIDeviceInputScreen.tsx` → `formAssistantApiClient.parseFormOCR` → `FormAssistantController.parseFormOCR`（L505 `@PostMapping("/ocr")`）。扫码样板 `components/processing/BarcodeScannerModal.tsx`。
7. Flyway 当前最大 `V20261101_41`；`_42` 预留 Line B，`_43` 预留 Line A（仅需索引时用）；其它在飞线从 `_44` 起。origin/codex/* 分支上无 `_42..._45`。
8. `MaterialBatchController` 权限注解样式：`@RequirePermission({"warehouse:read_write", "inventory:read_write"})`。RN 有 `__tests__/gates/` 一批闸（含 i18n 键存在性、每个 navigator 必须可达）。`ScaleDeviceController` 无秤读数 ingest 端点（与本两条线无关，只登记）。

## 共同约束（两张卡都适用）

- worktree 永远 off `origin/main`：`git worktree add -b codex/claude-<task> ../cretas-<task> origin/main`；开工前三验 `.git` 存在 / `git worktree list` 命中 / 分支名。
- commit 用 `./scripts/safe-commit.sh "<msg>" <files...>`；PR 前 `git diff origin/main...HEAD --stat` 确认无 sister 文件。**只做到 PR**，不 merge、不部署。
- 核心原则 1：禁止降级——读不到就报错，不返回假字段/假 0。
- 硬约束 8：改共享结构前先数（`git grep -c`），改完再数，「故意不改」处显式登记进 PR 描述。
- 硬约束 9：验证「X 没发生」必须配一个「Y 必发生」的读数。
- 变异对照：每条新断言先证明它能红（形态 B′/C″）。
- 每到停点（PR 开好 / 阻塞 / 收工 / 需裁定）立刻用 `mcp__ccd_session_mgmt__send_message` 发 organizer（session 标题「Organizer · 胜龙牛业/六膳门血缘闭环」，sessionId `local_f4accb66-7fc8-43d5-8d9e-f6af1b1eb986`），结构化回执：任务 ID / base SHA / 实际修改文件 / 验证命令与结果 / commit+PR / 阻塞 / 下一动作。不等 organizer 来问。
- 子代理不生孙代理；发现独立新工作面返回 `EXPANSION_REQUEST`。

---

## 卡 A → Line A（sister chat，Opus high）：`ARCH-LINEAGE-CLOSE-LOOP-20260914`

**目标**：把已有血缘引擎接成六膳门可用的闭环——**三段写边 + 历史回填 + 溯源页读闭包出多跳链 + 召回影响面**。先六膳门，胜龙只是后续在源头节点多显示几个字段。

**worktree**：`git worktree add -b codex/claude-lineage-loop-20260914 ../cretas-lineage-loop-20260914 origin/main`

**允许改**：见 ACTIVE.md scope 锁（`service/lineage/**` 新建 recorder；`Traceability*`；六个挂钩 Service 仅插入 recorder 调用行；回填命令；RN `screens/traceability/**` + `traceabilityApiClient.ts`；目标测试）。
**禁改**：`MaterialBatch.java`、`WipInventoryServiceImpl` 现有写边逻辑、Flyway（除 `_43` 且仅索引）、任何 vision/source-individual 文件。

**设计要点**：
1. `LineageEdgeRecorder`（一个 service，一处定义 fail-soft 策略，与 `recordWipLineageEdge` 同款 try/catch+warn）：`recordRawToProduction(materialBatchId, productionBatchId, qty, unit, operatorId, meta)`、`recordProductionToFinished(...)`、`recordFinishedToShipment(...)`。**幂等键**放 `meta`（如 `consumptionId`/`finishedBatchId`/`shipmentRecordId`），recorder 先查同 (factory, source, target, meta.refKey) 是否已存在再插——回填和在线路径共用同一个入口（形态 D：不许两份写边逻辑）。
2. 挂钩：在候选站点里**只对带生产批次的路径**插一行 recorder 调用；其余站点在 PR 描述里逐条登记「故意不写 + 原因」。先 `git grep -c "new MaterialConsumption()"` 记 BEFORE，改完 AFTER 相等。
3. 回填：`dev/` 或 `scripts/` 下一个幂等命令（Java `@Profile("backfill")` runner 或 SQL），从 `material_consumptions`（有 production_batch_id）/ `finished_goods_batches` / 出货记录派生边，走同一个 recorder 或同样的幂等键。**只写、不删**；输出「本次新增 N 条 / 已存在 M 条」两个数（硬约束 9）。⛔ 不在 prod 上跑——交 organizer，🔒。
4. 读侧：`TraceabilityDTO` **加法式**新增 `lineage` 节：`ancestors`（按 depth 分组，节点带 type/id/batchNumber/名称/供应商或产品/edge 的 qty+unit+eventTime）、`descendants`（内部 full trace 才给；公开接口只给 ancestors 且脱敏——沿用现有 `getPublicTrace` 的脱敏口径）。数据来自 `BatchLineageClosureRepository.findFullGraph` + edges，节点名称按 type 批量查（别 N+1）。**现有字段一个不动**。
5. 召回影响面：`GET /api/mobile/{factoryId}/traceability/impact/{batchType}/{batchId}` → 后代里的生产批次 / 成品批次 / 出货记录（含客户名）三组 + 计数。
6. RN：`PublicTraceScreen` 与 `TraceabilityDetailScreen` 加一段「来源链」时间线（按 depth 从远到近），空时显示「无上游血缘记录」而不是隐藏。

**验收**：
- Java：`mvn -o test -Dtest='LineageEdgeRecorder*Test,Traceability*Test,LineageQueryToolTest,BatchLineage*RepositoryTest,FlywayVersionUniquenessTest,*RepositoryQueryValidationTest'` 全绿；新测试含：① recorder 幂等（同键两次只一条边）② 三种 edge_type 各一条正向 + 变异（把 recorder 调用注掉→红）③ DTO lineage 组装（假闭包 3 层→ancestors 分 3 组）④ 公开接口不泄漏供应商字段（阴性对照）。
- RN：目标 jest 文件绿 + `__tests__/gates/` 全绿；截图一张 Expo Web 上带 3 层来源链的公开溯源页。
- PR 描述必须有：挂钩站点 BEFORE/AFTER 计数表 + 故意不写清单；回填命令 dry-run 在本地库上的「新增/已存在」两个数。
- `git diff origin/main...HEAD --stat` 无 scope 外文件。

**并行**：与卡 B 文件 disjoint；两边都碰不到对方的文件。
**🔒 收尾**：回填在 prod 执行、合并、部署全部由 organizer 做。

---

## 卡 B → Line B（sister chat，Opus high）：`FEAT-SOURCE-INDIVIDUAL-VISION-VERIFY-20260914`

**目标**：两个**通用**能力：① 「源头个体档案」——溯源链最前面那个有生命周期的个体（牛/鸭/猪都行），原料批次可挂它；② 「拍照核验」——按代码内模板拍一张照片出结构化字段并核对。周三用在牛业 demo，但命名、字段、UI 文案不出现「牛/胜龙」以外无法泛化的东西（物种是字段值，不是模型名）。

**worktree**：`git worktree add -b codex/claude-source-individual-20260914 ../cretas-source-individual-20260914 origin/main`

**允许改**：见 ACTIVE.md scope 锁。**禁改**：`Traceability*`、`lineage/**`、`WipInventoryServiceImpl`、`ProductionReport`、任何现有溯源屏。

**设计要点**：
1. Flyway `V20261101_42__source_individual.sql`：表 `source_individuals`（BaseEntity 标准列含 `deleted_at`/`version`；`factory_id`、`individual_code`、`tag_number`、`species`、`breed`、`sex`、`birth_date`、`origin_farm`、`external_gene_bank_id`、`dna_sample_code`、`dna_verification_status`(UNSAMPLED/SAMPLED/MATCHED/MISMATCH，用 CHECK)、`ext_attributes` JSONB、`notes`）；partial unique `(factory_id, individual_code) WHERE deleted_at IS NULL`；索引 `(factory_id, tag_number)`。`material_batches` 加 `source_individual_id VARCHAR(191) NULL` + 索引。⚠️ 本仓前科：NOT NULL DEFAULT 列 + 可空实体字段 = Hibernate 显式插 NULL；prod 可能有带外 CHECK；H2 profile 关 Flyway——所以跑 `FlywayVersionUniquenessTest` + 真 PG 的 `*RepositoryQueryValidationTest`。
2. Entity `SourceIndividual extends BaseEntity`，软删注解按当前实体惯例（看 `SoftDeleteAnnotationCoverageRatchetContractTest` 要求什么，别让棘轮红）。Repo：`findByFactoryIdAndTagNumber`、`findByFactoryIdAndIndividualCode`、分页列表。Controller `/api/mobile/{factoryId}/source-individuals`（list/get/create/update/soft-delete + `GET /by-tag/{tagNumber}`），权限先复用 `{"warehouse:read_write","inventory:read_write"}` 读写、读用对应 `:read`（照 `MaterialBatchController`）。
3. `MaterialBatch.sourceIndividualId`（可空）+ 在 MaterialBatch DTO/创建更新请求里透传（加法式）。
4. `POST /api/mobile/{factoryId}/vision/verify`，body `{templateCode, imageBase64, contentType}`。`VisionVerifyService` + 代码内 `VisionTemplateRegistry`（两个模板）：
   - `EAR_TAG`：prompt 要求只返回 JSON `{tagNumber, confidence, notes}`；后处理：tagNumber 只留数字/字母，查 `source_individuals` by tag → `matched: {id, individualCode, species, breed, originFarm, dnaVerificationStatus}` 或 `null`。
   - `MEAT_CUT`：JSON `{cut, marblingScore(1-5), foreignObjectSuspected, contaminationSuspected, confidence, notes}`；响应固定带 `assessmentLevel: "AI_PRELIMINARY"`（UI 显示「AI 初评 · 需人工复核」）。
   - 走 `PythonLLMClient.visionChat`；解析失败/非 JSON/缺 confidence → 422 带 `rawText`，**不造字段**。图片限制与 `FormAssistantController.parseFormOCR` 一致。
5. RN `screens/shared/VisionVerifyScreen.tsx`：模板 chip（EAR_TAG/MEAT_CUT）→ EAR_TAG 可「扫码」（`BarcodeScannerModal`，扫到的码直接走 by-tag 查询，不过 VL）或「拍照/相册」（`ImagePicker` base64，照 `AIDeviceInputScreen`）→ 结果卡 → 匹配到「查看档案」/ 没匹配「登记新个体」跳 `SourceIndividualFormScreen`（预填 tagNumber）。注册进一个已有的 shared/processing navigator（照 `LabelScanScreen` 的注册方式）。i18n 键 zh-CN/en-US 都加，跑 `__tests__/gates/`。
6. web-admin `views/source-individuals/`：列表（搜索 tag/code/species）+ 抽屉表单；api client + route + 菜单行；权限同上。

**验收**：
- Java：`mvn -o test -Dtest='SourceIndividual*Test,VisionVerify*Test,FlywayVersionUniquenessTest,*RepositoryQueryValidationTest,SoftDeleteAnnotationCoverageRatchetContractTest'` 全绿；新测试含：① 模板解析正向 + 非 JSON→422 + 缺 confidence→422（mock `PythonLLMClient`）② EAR_TAG 匹配/不匹配 ③ MaterialBatch 透传 sourceIndividualId 往返。
- RN：目标 jest + gates 全绿；Expo Web 截图：EAR_TAG 结果卡（匹配）+ MEAT_CUT 结果卡（带「AI 初评」标）。
- web-admin：`vue-tsc -b` + 目标 vitest + `vite build`。
- 真 VL 冒烟：本地起 Python `/vision` 或指向 prod Python，用 2 张样张各跑一次，把原始 JSON 贴 PR。
- `git diff origin/main...HEAD --stat` 无 scope 外文件。

**并行**：与卡 A disjoint。**🔒 收尾**：Flyway/Entity → 只到 PR；合并、部署、demo 租户种子由 organizer 做。

---

## Organizer 自留

- probe（已派，只读）：prod `batch_lineage_edges` 按 edge_type 计数 + 回填规模四个数 → 结果进本文件追加节。
- C1（合并后，🔒 prod 写）：`SHENGLONG_DEMO` 租户 + 屠宰→分割(20 端口)→加工族图配置 + 6 头个体 + 批次种子 + 二维码。
- V1：秤读数→报工接线 main 上确认无 ingest 端点（已核），现场不说「已接」。
- 周二：Steve 手机 4G 跑完整剧本。
