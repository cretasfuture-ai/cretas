# Dispatch 台账 — ACTIVE
**最后更新**：2026-09-06 01:09（SFI 条目订正）。此前：2026-09-06 01:00（owner 三条裁定入账）。此前：2026-09-06 00:53（probe 回报两条；SFI 重复 OUT 登记）。此前：2026-09-06 00:34（organizer 释放 2 把陈旧锁、承接出成率线、派 3 条）

**当前真值**：`origin/main` 为代码与合并真值；本文件只记在飞任务的一行摘要，由 `scripts/ci/check_dispatch_active.py` 钉住。条目全文与增量日志：[2026-09-06-active-organizer-log.md](archive/2026-09-06-active-organizer-log.md)、[2026-09-05-active-entries.md](archive/2026-09-05-active-entries.md)、[2026-09-05-active-organizer-log.md](archive/2026-09-05-active-organizer-log.md)；此前完成记录见 [2026-09-01-active-history.md](archive/2026-09-01-active-history.md)、[2026-08-31-active-history.md](archive/2026-08-31-active-history.md)、[2026-08-30-active-history.md](archive/2026-08-30-active-history.md)、[2026-08-29-active-history.md](archive/2026-08-29-active-history.md)、[2026-08-27-active-history.md](archive/2026-08-27-active-history.md)、[2026-08-26-active-history.md](archive/2026-08-26-active-history.md)、[2026-08-25-active-history.md](archive/2026-08-25-active-history.md)、[2026-08-24-active-history.md](archive/2026-08-24-active-history.md)、[2026-08-21-active-history.md](archive/2026-08-21-active-history.md)、[2026-08-17-active-history.md](archive/2026-08-17-active-history.md)、[2026-08-16-active-history.md](archive/2026-08-16-active-history.md)、[2026-08-15-active-history.md](archive/2026-08-15-active-history.md)、[2026-08-14-active-history.md](archive/2026-08-14-active-history.md)、[2026-08-13-active-history.md](archive/2026-08-13-active-history.md)、[2026-08-12-active-history.md](archive/2026-08-12-active-history.md)、[2026-08-11-active-history.md](archive/2026-08-11-active-history.md)、[2026-08-10-active-history.md](archive/2026-08-10-active-history.md)、[2026-08-09-active-history.md](archive/2026-08-09-active-history.md)、[2026-08-07-active-history.md](archive/2026-08-07-active-history.md)、[2026-08-04-active-history.md](archive/2026-08-04-active-history.md)、[2026-08-03-active-history.md](archive/2026-08-03-active-history.md)、[2026-08-02-active-history.md](archive/2026-08-02-active-history.md)、[2026-08-01-active-history.md](archive/2026-08-01-active-history.md)、[2026-07-31-active-history.md](archive/2026-07-31-active-history.md)、[2026-07-30-active-history.md](archive/2026-07-30-active-history.md)、[2026-07-29-active-history.md](archive/2026-07-29-active-history.md)、[2026-07-28-active-history.md](archive/2026-07-28-active-history.md)、[2026-07-27-active-history.md](archive/2026-07-27-active-history.md) 与 [2026-07-26-active-history.md](archive/2026-07-26-active-history.md)，此前历史见 [2026-07-25-active-history.md](archive/2026-07-25-active-history.md)。

## 在飞任务

每条 ≤ 300 字：ID / 状态 / Owner / Base / 一句话目标与当前阻塞点。详情锚点 = `archive/2026-09-05-active-entries.md#<小写 id>`。

- `FIX-LSM-SUPPLIER-PRICE-REQUIRED-20260901` — `in-progress` — Owner: `lucid-nobel-4a3795-7c` — 缺陷 16+17：`SupplierDetailDrawer.vue` 价格字段三处（关系表单/内联规格/独立规格）；`required` 是装饰，校验手写在 `saveRelation`/`savePurchaseSpec`。Owner 裁定：新增必填，编辑仅价格被改动时拦（存量 4 条空价）。未核后端/导入路径，需改后端 → 🔒 回协调者。
- `OBS-PRODUCTION-MANAGER-FALLBACK-DRIFT-20260901` — `queued` — Owner: 无 — `V20261030_11` 把 production_manager 的 analytics/report/procurement 收窄为 `-`，前端 store 与 Java fallback 仍 `r`（3×2=6 条陈旧），与 #441 同形但不在其 KNOWN_DRIFT 名单；只在 DB 不可达的 fallback 窗口显形。
- `FIX-QC-VERSION-OTA-APK-20260829` — `in-progress` — Owner: `/root` — Base `0f11d9a91b` — 质检员「关于/设置」版本号改读 APK 原生真值并补一致性闸；合入后向仍报 1.0.3 的设备发兼容 OTA，重建 1.0.4 签名 APK（保留最低版本门禁）。
- `HOTFIX-LIUSHANMEN-QC-CAPTURE-UPLOAD-20260829` — `in-progress` — Owner: `/root` — Base `0ed3f10da8` — Label QC 拍照本地压缩后立即添加；远程清晰度检查只作后台建议，5s 超时/断网/失败/拒绝均不删照片不阻塞提交，不伪称「已自动检查」。验收含 Android OTA 与真机上传回读。
- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811` — `blocked` — Owner: `/root` — 正式 audit 因 `trayal_0e5a259f_030` unjudgeable fail closed；计划任务已迁到 clean exact-main 树（LastResult=0）。剩余仅该 ROI 人工裁决；模型/人工真值/原图/生产不改。
- `LLM-PROVIDER-CLIFF-20260815` — `blocked` — Owner: `/root` — DeepSeek 接线、七槽、悬崖模拟、每日健康与复审 cron 已在线；剩余 2026-09-13 aistore 续期换 key 与 2026-11-15 复审，都是 owner/供应商控制台动作，不扩大为代码改动。
- `UX-RN-RESTAURANT-PROCUREMENT-DELIVERY-20260802` — `blocked` — Owner: `/root` — 实现已入 main，Expo Web 验收可核销；餐饮 RN OTA runtime 与真机验收未确认。实现 scope 已释放，如继续只新建 exact-commit 的 OTA/设备验收项。
- `SEC-CREDENTIAL-ROTATION-20260719` — `blocked` — Owner: `/root` — 仓内/服务器副本/DB/JWT/internal/BaoTa/OSS RAM 已轮换；仍需各控制台吊销旧 Aliyun 主账号 AK、旧模型供应商 key、微信长期凭证并验证失效。⚠️ 09-05 新增：47 宝塔面板口令曾在 tracked 文件与 git 历史，待轮换。
- `ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001` — `blocked` — Owner: `/root` — 分类建议契约测试已补（`MaterialTaxonomySuggestionContractTest` 10 条，在 CI 选择器内）。「Excel preview 复用」实测是未实现而非未证明（`suggestFields` 唯一调用点是 Controller），需 owner 裁定是否要接。无执行者。
- `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` — `blocked` — Owner: `/root` — 两个真缺口已清零（`TransferServiceImpl` 补 `TRANSFER_IN` 来源；并行入库端点 `POST material-receipt` 已下线）并上闸 `MaterialBatchSourceDocContractTest`。待 owner 裁定 `InboundType` 死字段：接真实消费方还是删除；裁定前不扩枚举。
- `BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001` — `blocked` — Owner: `/root` — AP 核心与付款核销已在 main；「四维状态」疑即 `PayablePaymentStatus` 四值，需 owner 确认本条描述是否过期。付款条款里程碑/付款计划、三单匹配确实未实现（全仓 0 命中）。
- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001` — `blocked` — Owner: `/root` — 净需求/客户来料/幂等/直接收料 fail closed 已有；未核销：严格同批 identity/单位/日期/QC/cost 匹配与独立库存事务验收。⚠️ `MATERIAL_REQUISITION`/`PRODUCTION_BATCH`/`CUSTOMER_MATERIAL_ARRIVAL` 不在 `validateSourceDoc` 的 switch 里，直接落库绕开守卫。
- `ENH-F006-SUPPLIER-IMPORT-001` — `blocked` — Owner: `/root` — 实现已在 main；两个测试类已改名进 CI 选择器并补 4 条幂等出口断言。未核销：状态生命周期、采购门禁、详情信息架构、Web 侧。⚠️ `suppliers` 成对重复列只有一半是活的（`contact_*`/`business_license` 全空），按字段名直觉写会写进死列。
- `BUG-F006-R2-SKU-UNIT-001` — `blocked` — Owner: `/root/supplier_frontend` — Web canonical 化已成熟且有 CI 背书；Java 净含量契约已补 `ProductTypeNetContentContractTest` 13 条（容量型绝不伪装为克重）。剩余未核销：历史回填。
- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001` — `blocked` — Owner: `/root/workprocess_governance` — Java 治理契约已补 11 条；生产审计表 0 行、F006 重名 3 组 8 行疑似按产线拆分而非重复录入，治理前需 owner 判定。剩余：Web 治理界面、排序/单位移除、Flyway/JPA 验收。
- `FEATURE-F006-R2-BOM-SUBSTITUTE-001` — `queued` — Owner: `/root` — Java CORE 已归档；父任务的 Web/计划/领料/报工/成本/追溯快照未证明完成，由统一 Workflow/BOM 任务串行吸收，`NOT_DEPLOYED`。
- `F006-R2-WORKFLOW-FIRST-BOM-GATE` — `blocked` — Owner: 无（原会话已消失） — Base `fee2509fa6`（落后 572 commit）。已删到不了的 BOM 准入分支并上闸；待裁定画布激活是否补写 `bom_versions` 快照。09-06 organizer 释放 scope 锁，续做须重登 Base；详见 archive/2026-09-06-active-organizer-log.md。
- `BUG-F006-PRODUCTION-COST-CLOSURE-001` — `blocked` — Owner: 无（原会话已消失） — Base `7d51fe2259`（落后 573 commit）。`totalCost` 非空 2/23→16/23，剩余 7 条全是 `PINNED_BOM_SNAPSHOT_MISSING`（归 BOM-GATE）；遗留 `PUT /costs` 写 plan 而归集读 batch。09-06 organizer 释放 scope 锁，续做须重登 Base。
- `QA-WAREHOUSE-STORAGE-UI-VERIFY-20260825` — `review` — Owner: `claude-code/warehouse-storage-ui-verify` — 第一批 PR #87 已部署复验；第二批（`codex/claude-warehouse-approval-closeout-20260826`）：App 端仓库地图入口、数量审批走通（凭证发号器无类型参数第 5 次必炸已修）、位置脏值清洗。未决：`ST-202608-C20566F3` 待上线后点「应用差异」。
- `LSM-WALKTHROUGH-ORGANIZER-LINE-20260902` — `in-progress` — Owner: `liushanmen-organizer-handoff-fa5fb5-0c` — 生产 = main（发 34；OTA 02:03 已推、证书修复后可拉）。owner 09-06 裁定：客户名单只给销售/财务/工厂管理员/运营（⚠️ 未含生产经理，已告知后果，待其一字确认）；OTA 送达用小米真机 adb 验（待连）；F006 全是测试数据、回滚撤销。
- `YIELD-STANDARD-LINE-20260905` — `blocked` — Owner: 无（f006 09-06 00:2x 收工） — #594/#599/#600/#601 已上线。卡数据非代码：六膳门配方出成率 0/8、成品主料份额 1/12、F006 黄油鸡仍 100.00（=「未配置」哨兵）。回滚判据 = 下次 F006 结单后仍 93.75 且 `bom_yield_suggestions` 新增 PENDING 而非 APPLIED。详情见 09-06 organizer log。
- `PLAN-CROSS-SEMI-OFFSET-RESERVE-20260906` — `in-progress` — Owner: executor（organizer 派，phase 2） — probe 结论：跨计划消耗已通两条路（SP2 + 逐道录入），缺的是「占住」：选料下拉读原始可用量，待扣量 `PendingInterimFeedService` 只接了盘点。organizer 裁定本批做可见性预扣 + 保存即校验，⛔ 不建第三套预留台账。🔒 终审必经。
- `COST-AUX-ESTIMATED-LABEL-20260906` — `in-progress` — Owner: executor（organizer 派） — owner 裁定「辅料先估」：结单成本里辅料未记实耗时按 BOM 配比 × 主料实耗估算，逐项标「按配比估算」，⛔ 不改报工界面。复用 #576/#580 的 `costContributions`/`*Note` 形状。🔒 成本口径，终审必经。
- `RECON-LEGAL-VALUE-SENTINEL-20260906` — `queued` — Owner: 无（等执行槽位） — **坐实**：prod 144 条配方行 yield_rate 全 100.00 ⇒ `mainMaterialCount=0` ⇒ 解析器读头级前返回 null 且无日志；#599 叠在这道门上 ⇒ 上线三小时一次没算出数。修法已定：头级为唯一来源（NULL=未填，100 合法）、拆主料计数门（主料按端口份额）、删读 APPLIED 建议分支、迁移置空 F006 那条 SYSTEM 100.00、放宽 `chk_br_yield`。🔒
- `BUG-SFI-REVERSE-CLASSIFIER-LOCKOUT-20260906` — `queued` — Owner: 无（等执行槽位；优先级低于出成率修复与客户名单门） — **订正**：无重复扣减，前次读数把带符号 REVERSE 无符号求和（仪器错，已记 memory）。真缺陷：`isExternalConsumptionReverse` 不看符号 ⇒ #391 前遗留的 6 行（LSM 370/371/372/378、F006 343/344）以后整单冲销必 409。修法：分类器加符号 + 迁移回填 3 行 report_id + 对账闸（三态）。🔒 冲销。
- `SEC-CUSTOMER-LIST-GATE-20260906` — `queued` — Owner: 无（等执行槽位） — owner 裁定：客户名单只给销售、财务、工厂管理员、运营。#590 撤回的那半按此重做：L1 核每个角色 pass/blocked 表；`operations_coordinator` 若无 sales/finance 读权需选门的形状。⚠️ production_manager 将失去客户下拉（7/20 计划挂客户），已告知 owner。🔒 权限。

## Scope 锁地图

- `FIX-LSM-SUPPLIER-PRICE-REQUIRED-20260901`：仅 `web-admin/src/views/procurement/suppliers/SupplierDetailDrawer.vue` 与对应目标测试。⛔ 不改 `permission.ts` 权限矩阵；若需改后端/导入路径对空价格的行为 → 🔒 停在 PR 交协调者。
- `FIX-QC-VERSION-OTA-APK-20260829`：`frontend/CretasFoodTrace/src/screens/quality-inspector/QIProfileScreen.tsx`、`QISettingsScreen.tsx`、`src/i18n/locales/{zh-CN,en-US}/quality.json`、`src/__tests__/gates/nativeVersionConsistencyContract.test.ts`、ACTIVE 与当日归档；发布阶段只读使用官方 OTA/APK 脚本。
- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811`：仅 `tools/vision-lab/work_area*.py`、`mine_tray_queue.py`、`tray_workflow.py`、`evaluate_tray_candidate.py`、`tools/vision-lab/tests/`、`docs/ops/liushanmen-vision-lab.md`、ACTIVE 与当日归档；不得改写人工真值、保护集、原图、现有模型或生产。
- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001`：销售需求、BOM净需求、采购建议、客户来料、仓储来源任务及对应 Java/Web/测试/dispatch；涉及 Entity/Repository/Flyway 时必须重登记最新 Base 并通过真实 JPA/迁移门禁。不得开启普通手工收料写入口或修改生产数据。
- `ENH-F006-SUPPLIER-IMPORT-001`：供应商 Java Entity/DTO/Controller/Service/Repository、采购门禁、共享 Excel 导入与目标测试；Web 供应商列表/详情/新增编辑/导入及 API/types/tests；必须真实 JPA Context（如触及查询），生产业务写入为 0。
- `BUG-F006-R2-SKU-UNIT-001`：仅 Web SKU 新建/编辑基础规格、displayUnit/净含量单位 helper 与目标测试；Java 净含量契约须另行登记不重叠 scope。
- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001`：Java WorkProcess/Workflow/SKU 关联、DTO/Repository/Service/Controller、Flyway/JPA tests；Web 工序治理及排序/单位字段移除与目标测试；不得迁移既有业务引用或治理生产重复组。
- `FEATURE-F006-R2-BOM-SUBSTITUTE-001`：BOM 替代关系的 Web、版本/计划/领料/报工/成本/追溯快照与目标测试；Java CORE 已完成，父任务不得重复修改 CORE Flyway/Entity，必须与统一 Workflow/BOM 任务串行。

- `PLAN-CROSS-SEMI-OFFSET-RESERVE-20260906`：probe 阶段只读。executor 阶段预计：`SemiFinishedInventory*`、`ProcessSheetServiceImpl` 可用量/领用、`MaterialBatchRepository`/`FinishedGoodsBatchRepository` 的 reserved 口径与目标测试；⛔ 不碰 `OrderCostBreakdownService`。
- `COST-AUX-ESTIMATED-LABEL-20260906`：`OrderCostBreakdownService`、`OrderCostBreakdownDTO`/`ProductionCostRecalculationResponse`、`ProductionPlanServiceImpl` 成本备注接线、web-admin 重算提示追加与目标测试；⛔ 不改报工 UI、不改 `BomSeasoningItem` 实体、无 Flyway。

## 阻塞项

- LIUSHANMEN：21 条空 `storage_type` 等待 Owner 向客户取得真实储存类型；结论到达前保持零写入，不猜填。
- VisionLab：正式 ROI audit 仍有 1 个 unjudgeable；计划任务迁移与运行失败已核销。
- LLM 运营：2026-09-13 aistore 续期换 key，以及 2026-09-04/2026-11-15 复审需要 Owner/供应商控制台动作。
- 餐饮 RN：实现已入 main，但当前 OTA runtime 与真实设备验收未确认。
- 凭证：旧 Aliyun 主账号 AK、旧模型供应商 key、Mall 微信长期凭证仍需各控制台吊销/重置并验证失效。
- F006 产品/架构：taxonomy suggestion、统一收料来源、AP 付款完整边界仍是无执行者 backlog；需求驱动收料与客户渐进创建保留 scope，后者须先裁定必填规则。

## 维护规则

- 只记当前在飞（`queued`/`claimed`/`in-progress`/`review`/`blocked`）；`merged`/`done` 立即移入带日期的 `archive/`，同一 commit 释放 scope 锁。
- **每条 ≤ 300 字**（含缩进续行）：ID、状态、Owner、Base SHA、一句话目标与阻塞点。判据读数、终审记录、增量日志一律写 `archive/<日期>-*.md` 并链接，⛔ 不在本文件开 `###` 小节。全文 ≤ 20 KB。
- 上述规则由 `scripts/ci/check_dispatch_active.py` 钉住：PR 与 push main 的 Source Hygiene 里跑；fastlane 推送碰到本文件时先跑。本地：`python scripts/ci/check_dispatch_active.py`。
- organizer 只在派活时读本表；创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理按 `AGENTS.md` 的结构化回执格式报告。
- 同一仓库默认并发最多 2 个代码执行代理和 2 个只读/测试代理；无独立价值的工作保持 DIRECT。
