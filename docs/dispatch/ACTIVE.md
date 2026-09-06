# Dispatch 台账 — ACTIVE
**最后更新**：2026-09-06 09:02（#629 发 42 上线归档：ProductionPlan 软删 + FK NOT VALID；#632 终审有条件）。此前：2026-09-06 08:34（#628 发 41 上线归档；stocktake 3262 子行已对齐父行）。此前：2026-09-06 08:05（#627 合并+装机+手跑验证；stocktake 3262 孤儿开票；脚本测试 11 红开票；#628/#629 终审中）。此前：2026-09-06 06:29（#620 发 40 上线归档；孤儿监控修复派出）。此前：2026-09-06 06:13（孤儿监控真因=cron PATH；告警文件无消费方开票）。此前：2026-09-06 06:01（硬删取证完成：两层根因；孤儿监控死 16 天开票）。此前：2026-09-06 05:40（#618 发 39 上线归档）。此前：2026-09-06 05:15（#620 终审需改；硬删入口开票）。此前：2026-09-06 05:13（#620 终审中）。此前：2026-09-06 04:51（#618 终审中；客户名单余下路径派出）。此前：2026-09-06 04:11（#615 发 38 上线归档）。此前：2026-09-06 04:02（#615 终审中；幽灵预扣派出；客户名单余下 11 路登记）。此前：2026-09-06 03:57（#609 发 37 上线归档；冲销分类器派出）。此前：2026-09-06 03:37（#612 发 36 上线归档）。此前：2026-09-06 02:59（#609 终审需改；客户名单门在写；幽灵预扣登记）。此前：2026-09-06 02:43（#608 发 35 上线，归档）。此前：2026-09-06 01:24（手机 OTA 读数、权限 recon、aux 执行者 403 续派）。此前：2026-09-06 01:09（SFI 条目订正）。此前：2026-09-06 01:00（owner 三条裁定入账）。此前：2026-09-06 00:53（probe 回报两条；SFI 重复 OUT 登记）。此前：2026-09-06 00:34（organizer 释放 2 把陈旧锁、承接出成率线、派 3 条）

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
- `LSM-WALKTHROUGH-ORGANIZER-LINE-20260902` — `in-progress` — Owner: `liushanmen-organizer-handoff-fa5fb5-0c` — 生产 = main（发 34）。OTA：小米真机已应用 `98c3b3dd`（probe 点了更新），行为层待 owner 登六膳门销售账号后读「短交结案」；其余设备仍旧版（弹框需人点）。待 owner：生产经理进不进客户名单；权限总表第 1 步；OTA 改自动应用否。
- `YIELD-STANDARD-LINE-20260905` — `blocked` — Owner: 无（f006 收工） — #594/#599/#600/#601 + **#612（发 36）** 已上线：头级出成率现在真的生效（此前被行级 100.00 哨兵门拦死，#599 三周一次没算过）。剩下卡数据：六膳门 8 配方出成率 0/8（张权填，填了即生效）、主料份额 1/12。F006 黄油鸡 100.00 已由迁移置 NULL，回滚项撤销。
- `OPS-PROD-DDL-AUTO-NONE-AND-SCHEMA-CANNOT-VALIDATE-20260906` — `queued` — Owner: 无 — #629 实测副产品：prod 服务实际跑 `ddl-auto=none`；prod schema 今天过不了 validate（首处 `active_learning_samples.matched_keywords` jsonb≠json）；`scripts/restart-prod.sh:24` 的 `--ddl-auto=validate` 是陈旧脚本（MySQL DSN）。要不要把 validate 跑通是 owner 决定。
- `BUG-SEMI-STOCKTAKE-ITEMS-3262-ORPHANS-20260906` — `review` — Owner: executor（organizer 派） — 3262 子行已对齐父行（→0）。PR #632 生成器拆 absent/soft_deleted 两列 + 形状闸；终审条件：软删-only 按对**算出** `escalate`（子实体有 Repository 且 FK 无 `@NotFound`）而非统一降级，补改中。合后 organizer 装 3 文件。🔒 运维。
- `OPS-SCRIPT-TESTS-11-LEGACY-RED-20260906` — `queued` — Owner: 无 — #627 首次把 `scripts/tests/*` 接进 CI，48 个里 11 个红从没人看过；棘轮 `known-failing.txt` 钉 10 + FLAKY 1。疑似真回归 5（ci.yml paths / preflight 无执行位 / smartbi:401 管道 / embedding 缺 rsync / 制品名），详见 log。🔒 deploy 脚本。
- `OPS-CRON-ALERT-FILES-HAVE-NO-CONSUMER-20260906` — `queued` — Owner: 无（待 owner 定渠道） — 47 上 13 个 cron 脚本写 11 个 `*alert*` 文件，**零消费方**。实证：`soft-delete-orphans.alert` 8-21 第一次跑就写了 3262 行孤儿，没人读，16 天。需 owner 定投递渠道（钉钉/企微/邮件/CI 拉取），再做统一 digest。⛔ 单修某脚本不解决。
- `ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906` — `queued` — Owner: 无（待 owner 定范围） — owner 要「按侧边栏模块勾角色」的权限总页。recon：L1 总表无编辑页（路由注释、目录空、页内死链）；「角色权限」页是空壳且显示代码兜底表；模块清单 7 份不一致、两个写接口拒绝两个在用模块；330 处 `@RequireRole` 矩阵管不到。方案三步，第 1 步可派。

## Scope 锁地图

- `FIX-LSM-SUPPLIER-PRICE-REQUIRED-20260901`：仅 `web-admin/src/views/procurement/suppliers/SupplierDetailDrawer.vue` 与对应目标测试。⛔ 不改 `permission.ts` 权限矩阵；若需改后端/导入路径对空价格的行为 → 🔒 停在 PR 交协调者。
- `FIX-QC-VERSION-OTA-APK-20260829`：`frontend/CretasFoodTrace/src/screens/quality-inspector/QIProfileScreen.tsx`、`QISettingsScreen.tsx`、`src/i18n/locales/{zh-CN,en-US}/quality.json`、`src/__tests__/gates/nativeVersionConsistencyContract.test.ts`、ACTIVE 与当日归档；发布阶段只读使用官方 OTA/APK 脚本。
- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811`：仅 `tools/vision-lab/work_area*.py`、`mine_tray_queue.py`、`tray_workflow.py`、`evaluate_tray_candidate.py`、`tools/vision-lab/tests/`、`docs/ops/liushanmen-vision-lab.md`、ACTIVE 与当日归档；不得改写人工真值、保护集、原图、现有模型或生产。
- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001`：销售需求、BOM净需求、采购建议、客户来料、仓储来源任务及对应 Java/Web/测试/dispatch；涉及 Entity/Repository/Flyway 时必须重登记最新 Base 并通过真实 JPA/迁移门禁。不得开启普通手工收料写入口或修改生产数据。
- `ENH-F006-SUPPLIER-IMPORT-001`：供应商 Java Entity/DTO/Controller/Service/Repository、采购门禁、共享 Excel 导入与目标测试；Web 供应商列表/详情/新增编辑/导入及 API/types/tests；必须真实 JPA Context（如触及查询），生产业务写入为 0。
- `BUG-F006-R2-SKU-UNIT-001`：仅 Web SKU 新建/编辑基础规格、displayUnit/净含量单位 helper 与目标测试；Java 净含量契约须另行登记不重叠 scope。
- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001`：Java WorkProcess/Workflow/SKU 关联、DTO/Repository/Service/Controller、Flyway/JPA tests；Web 工序治理及排序/单位字段移除与目标测试；不得迁移既有业务引用或治理生产重复组。
- `FEATURE-F006-R2-BOM-SUBSTITUTE-001`：BOM 替代关系的 Web、版本/计划/领料/报工/成本/追溯快照与目标测试；Java CORE 已完成，父任务不得重复修改 CORE Flyway/Entity，必须与统一 Workflow/BOM 任务串行。


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
