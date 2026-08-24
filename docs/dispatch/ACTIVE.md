# Dispatch 台账 — ACTIVE
**最后更新**：2026-08-24

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-08-24-active-history.md](archive/2026-08-24-active-history.md)、[2026-08-21-active-history.md](archive/2026-08-21-active-history.md)、[2026-08-17-active-history.md](archive/2026-08-17-active-history.md)、[2026-08-16-active-history.md](archive/2026-08-16-active-history.md)、[2026-08-15-active-history.md](archive/2026-08-15-active-history.md)、[2026-08-14-active-history.md](archive/2026-08-14-active-history.md)、[2026-08-13-active-history.md](archive/2026-08-13-active-history.md)、[2026-08-12-active-history.md](archive/2026-08-12-active-history.md)、[2026-08-11-active-history.md](archive/2026-08-11-active-history.md)、[2026-08-10-active-history.md](archive/2026-08-10-active-history.md)、[2026-08-09-active-history.md](archive/2026-08-09-active-history.md)、[2026-08-07-active-history.md](archive/2026-08-07-active-history.md)、[2026-08-04-active-history.md](archive/2026-08-04-active-history.md)、[2026-08-03-active-history.md](archive/2026-08-03-active-history.md)、[2026-08-02-active-history.md](archive/2026-08-02-active-history.md)、[2026-08-01-active-history.md](archive/2026-08-01-active-history.md)、[2026-07-31-active-history.md](archive/2026-07-31-active-history.md)、[2026-07-30-active-history.md](archive/2026-07-30-active-history.md)、[2026-07-29-active-history.md](archive/2026-07-29-active-history.md)、[2026-07-28-active-history.md](archive/2026-07-28-active-history.md)、[2026-07-27-active-history.md](archive/2026-07-27-active-history.md) 与 [2026-07-26-active-history.md](archive/2026-07-26-active-history.md)，此前历史见 [2026-07-25-active-history.md](archive/2026-07-25-active-history.md)。

## 在飞任务

- `E2E-F006-WORKFLOW-CURRENT-VERSION-20260824` — `in-progress` — Owner: `/root` — Base SHA `3dfce77462cb8f24db5f3e56876ce7e714f852dd`; runKey `E2E-F006-WORKFLOW-VERSION-20260824-144217`。专项验证 Workflow“当前配置/版本记录”产品真值、F006 计划按首次正式报工分流、历史恢复、幂等/并发冲突、权限和 PostgreSQL 事务顺序；当前 Chat 是唯一写 Owner，F006 写入必须绑定本 runKey，其他租户写入为 0。

- `E2E-F006-PRODUCTION-POST-REPORT-20260824` — `in-progress` — Owner: `/root` — continuation Base SHA `0d20b8932914e41461460fdc646fee1f2444e85f`; 专用 worktree `C:\Users\Steve\cretas-f006-production-closeout-e2e-20260824`；当前 runKey `E2E-F006-PROD-CLOSEOUT-20260824-105104`。分次入库 6/3/1 与原料/在制品/成品盘点已在部署后完成。2026-08-25 质检专项实时复现 `BUG-F006-QC-RECORD-DETAIL-MAPPING-001`：RN 记录详情把 inspection notes 显示为产品名、未持久化的分类分数显示为 0/20，却把总分显示为 100/A；同时现有 RN 处置组件未接入质检失败结果页，Web 仅有废弃申请，没有隔离/返工/复检入口。当前 Owner 负责在本 worktree 内补齐质检记录详情真实映射、失败后处置入口及目标测试，合入发布后继续隔离、返工、复检、报废、销售/发货拦截、权限与数量守恒；写入仅限本 runKey 精确 F006 实体，其他租户写入为 0。

- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811` — `blocked` — Owner: `/root` — 已有代码进入公司迁移树，但正式 audit 仍因 `trayal_0e5a259f_030` unjudgeable fail closed。计划任务 `Cretas-Liushanmen-VisionLab` 已迁到 clean exact-main 工作树并取得 LastResult=0；剩余阻塞仅为该 ROI 人工裁决。保留视觉 scope；LocateAnything hardening 与其串行，模型、人工真值、原图和生产不改。
- `LLM-PROVIDER-CLIFF-20260815` — `blocked` — Owner: `/root` — DeepSeek 接线、七槽、悬崖模拟、每日健康与复审 cron 已进入迁移树并在线运行；剩余仅 2026-09-13 aistore 续期换 key、2026-09-04/11-15 运营复审等外部动作。旧 `llm_router.py` 代码锁已释放；不得把供应商控制台动作扩大为代码改动。

- `UX-RN-RESTAURANT-PROCUREMENT-DELIVERY-20260802` — `blocked` — Owner: `/root` — 实现提交已进入迁移树，代码与 Expo Web 验收可核销；当前餐饮 RN OTA runtime 与真实设备验收无法确认。实现文件 scope 已释放；如继续，仅新建 exact-commit OTA/设备验收项。

- `SEC-CREDENTIAL-ROTATION-20260719` — `blocked` — Owner: `/root` — tracked tree、服务器副本、数据库/JWT/internal/BaoTa/OSS RAM 替换已完成；仍需各供应商控制台吊销旧 Aliyun 主账号 AK、旧 Model Studio/Zhipu/DeepSeek key 与微信长期凭证并验证失效。旧 broad 代码锁已释放；不得在本任务做历史重写。

- `ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001` — `blocked` — Owner: `/root` — 候选 DTO、置信度/reason、同厂历史匹配和 Web 展示已进入迁移树；分类建议目标测试与 Excel preview 复用未完整证明，当前无执行者。陈旧 scope 已释放；恢复时按最新 Base 重新登记。

- `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` — `blocked` — Owner: `/root` — CUSTOMER_OWNED 与销售 lineage 已有，但 PURCHASE/CUSTOMER_SUPPLIED/TRANSFER/退回/退料/期初/调整的统一任务与来源矩阵未完整实现。当前无执行者，旧高风险 scope 已释放；恢复时需重新登记 Entity/Flyway/JPA 门禁。

- `BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001` — `blocked` — Owner: `/root` — V94、allocation 核心、财务 AP 未清项付款与旧采购付款只读边界已进入迁移树；四维状态、付款条款里程碑/计划/三单匹配未完整实现。CORE 子任务已归档；父任务旧广域锁释放，恢复时按最新 Base 重锁。

- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001` — `blocked` — Owner: `/root` — 净需求、客户来料、ownership/idempotency 与旧直接收料 fail closed 已有；严格同批 identity/单位/日期/QC/cost 匹配及独立库存 transaction 验收不足。需求仍有效且跨模块，保留 scope，`NOT_DEPLOYED`。

- `ENH-F006-CUSTOMER-CREATE-PROGRESSIVE-DISCLOSURE-001` — `blocked` — Owner: `/root` — Web 渐进表单已存在，但当前后端仅 name 必填，与 ACTIVE 原五项强制及销售单补全门禁冲突。须先由 Owner 裁定产品真值；保留 scope，生产客户零修改。

- `ENH-F006-SUPPLIER-IMPORT-001` — `blocked` — Owner: `/root` — 供应商必填资料、Excel 预览/幂等确认、状态生命周期、采购门禁与详情信息架构尚无本轮完整 tree/test 核销证据；当前无专用 PR。需求和 scope 均保留，恢复时先核对公司 main 的现有实现，严格 `NOT_DEPLOYED`。

- `BUG-F006-R2-SKU-UNIT-001` — `blocked` — Owner: `/root/supplier_frontend` — SKU displayUnit、净含量单位、同维度 canonical 化与历史回填尚无本轮完整 tree/test 核销证据；保留 Web scope，Java 契约仍须协调者另行审计，严格 `NOT_DEPLOYED`。

- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001` — `blocked` — Owner: `/root/workprocess_governance` — canonical/mergedInto、未来候选排除、既有 Workflow/SKU/计划/报工引用不迁移，以及排序/单位从主数据移除的完整 JPA/Web 验收尚未本轮核销；保留 scope，生产重复组零治理。

- `FEATURE-F006-R2-BOM-SUBSTITUTE-001` — `queued` — Owner: `/root` — Java CORE 已归档，但父任务的 Web、计划、领料、报工、成本和追溯快照尚未证明完成；继续由统一 Workflow/BOM 任务串行吸收，保留父任务 scope，严格 `NOT_DEPLOYED`。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE` — `queued` — Owner: `/root` — Workflow 草稿结构、BOM 完整性、激活/发布和生产计划准入的端到端门禁尚未证明完成；与统一 Workflow/BOM 状态机串行，保留 scope、ACTIVE-only 运行契约与历史快照零改写。

## Scope 锁地图

- `E2E-F006-WORKFLOW-CURRENT-VERSION-20260824`：Workflow 当前配置编辑、版本记录/绑定清单/历史恢复/乐观锁与权限的 Web 页面、API/types/tests；Java `ProductProcessWorkflow` Controller/Service/DTO/Repository 及生产计划首次正式报工 started 判定/版本重绑定；真实 PostgreSQL 集成测试、专项 Playwright driver、gitignored 本 runKey 证据和 dispatch。只有发现并证明本专项缺陷后才修改实现；若触及 Repository/Entity/Flyway，必须补真实 JPA/迁移门禁。明确排除 WorkProcess 去重、BOM substitutes、Workflow/BOM 完整性准入、上一 runKey 后段生产实体、其他租户和全局配置；与现有三个 Workflow/BOM backlog 串行且不吸收其 scope。

- `E2E-F006-PRODUCTION-POST-REPORT-20260824`：既有小结/分次入库/三类盘点范围保持不变；新增 `BUG-F006-QC-RECORD-DETAIL-MAPPING-001` 窄锁：RN `quality-inspector` 质检表单/结果/记录详情、既有 quality disposition 组件/API 接线、对应 types/API 真实映射与目标测试；仅在证明响应契约不足时触及 Java QualityInspection/QualityDisposition DTO/Service/Controller，并补相应目标测试。专项 E2E driver、dispatch 和本 runKey gitignored 证据继续在锁内。不得改变其他租户、全局配置、销售/采购价格 scope 或共享发布脚本；生产写入保持串行。

- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811`：仅 `tools/vision-lab/work_area*.py`、`tools/vision-lab/mine_tray_queue.py`、`tools/vision-lab/tray_workflow.py`、`tools/vision-lab/evaluate_tray_candidate.py`、对应 `tools/vision-lab/tests/`、`docs/ops/liushanmen-vision-lab.md`、`docs/dispatch/ACTIVE.md` 与当日归档；不得改写人工真值、保护集、原图、现有模型或生产。

- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001`：销售需求、BOM净需求、采购建议、客户来料、仓储来源任务及对应 Java/Web/测试/dispatch；涉及 Entity/Repository/Flyway 时必须重登记最新 Base 并通过真实 JPA/迁移门禁。不得开启普通手工收料写入口或修改生产数据。

- `ENH-F006-CUSTOMER-CREATE-PROGRESSIVE-DISCLOSURE-001`：客户 Web 共享表单与 Java Customer DTO/Service/Repository/API/import、销售使用门禁、目标测试/dispatch；先裁定联系人/电话/地址是否强制，再实施。不得修改生产客户数据。

- `ENH-F006-SUPPLIER-IMPORT-001`：供应商 Java Entity/DTO/Controller/Service/Repository、采购门禁、共享 Excel 导入与目标测试；Web 供应商列表/详情/新增编辑/导入及 API/types/tests；必须真实 JPA Context（如触及查询），生产业务写入为 0。

- `BUG-F006-R2-SKU-UNIT-001`：仅 Web SKU 新建/编辑基础规格、displayUnit/净含量单位 helper 与目标测试；Java 净含量契约须另行登记不重叠 scope。

- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001`：Java WorkProcess/Workflow/SKU 关联、DTO/Repository/Service/Controller、Flyway/JPA tests；Web 工序治理及排序/单位字段移除与目标测试；不得迁移既有业务引用或治理生产重复组。

- `FEATURE-F006-R2-BOM-SUBSTITUTE-001`：BOM 替代关系的 Web、版本/计划/领料/报工/成本/追溯快照与目标测试；Java CORE 已完成，父任务不得重复修改 CORE Flyway/Entity，必须与统一 Workflow/BOM 任务串行。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE`：Workflow 草稿结构、BOM 完整性 DTO/Service/API、激活/发布/生产计划准入、Web 阶段引导与目标测试；不得改写历史快照或生产数据。

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
