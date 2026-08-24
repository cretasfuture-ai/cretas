# Dispatch 台账 — ACTIVE
**最后更新**：2026-08-24

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-08-24-active-history.md](archive/2026-08-24-active-history.md)、[2026-08-21-active-history.md](archive/2026-08-21-active-history.md)、[2026-08-17-active-history.md](archive/2026-08-17-active-history.md)、[2026-08-16-active-history.md](archive/2026-08-16-active-history.md)、[2026-08-15-active-history.md](archive/2026-08-15-active-history.md)、[2026-08-14-active-history.md](archive/2026-08-14-active-history.md)、[2026-08-13-active-history.md](archive/2026-08-13-active-history.md)、[2026-08-12-active-history.md](archive/2026-08-12-active-history.md)、[2026-08-11-active-history.md](archive/2026-08-11-active-history.md)、[2026-08-10-active-history.md](archive/2026-08-10-active-history.md)、[2026-08-09-active-history.md](archive/2026-08-09-active-history.md)、[2026-08-07-active-history.md](archive/2026-08-07-active-history.md)、[2026-08-04-active-history.md](archive/2026-08-04-active-history.md)、[2026-08-03-active-history.md](archive/2026-08-03-active-history.md)、[2026-08-02-active-history.md](archive/2026-08-02-active-history.md)、[2026-08-01-active-history.md](archive/2026-08-01-active-history.md)、[2026-07-31-active-history.md](archive/2026-07-31-active-history.md)、[2026-07-30-active-history.md](archive/2026-07-30-active-history.md)、[2026-07-29-active-history.md](archive/2026-07-29-active-history.md)、[2026-07-28-active-history.md](archive/2026-07-28-active-history.md)、[2026-07-27-active-history.md](archive/2026-07-27-active-history.md) 与 [2026-07-26-active-history.md](archive/2026-07-26-active-history.md)，此前历史见 [2026-07-25-active-history.md](archive/2026-07-25-active-history.md)。

## 在飞任务

- `SOP-RAG-SYNC-20260824` — `in-progress` — Owner: `/root` — Base SHA `2b29eaea784d6f45af34093c1fde7d313a5ed151`。同步本次已合入 main 的工厂仓管四入口/真实采购收货、销售母单→子发运单与自动开票待办、Workflow 自动分叉和活动生产引用门禁；餐饮独立判定为 no-op，仅回归注册 source 与真实回答。验收：`python -m pytest backend/python/tests/test_food_kb_manual_chat_sop_contract.py -q`、`python -m py_compile backend/python/food_kb/services/manual_chat.py backend/python/food_kb/services/manual_ingester.py`、HTML/内联 JavaScript parse、`git diff --check`、三页哈希/生产健康/RAG `.NEW=0` 与工厂 3+、餐饮 1+ 真实问答。严格 `NOT_DEPLOYED`，仅合入 `origin/main` 后发布。

- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811` — `blocked` — Owner: `/root` — 已有代码进入公司迁移树，但正式 audit 仍因 `trayal_0e5a259f_030` unjudgeable fail closed；计划任务 `Cretas-Liushanmen-VisionLab` 今日 LastResult=1，仍指向旧 detached worktree，须先迁到公司受控代码源并核销失败。保留视觉 scope；LocateAnything hardening 与其串行，模型、人工真值、原图和生产不改。
- `LLM-PROVIDER-CLIFF-20260815` — `blocked` — Owner: `/root` — DeepSeek 接线、七槽、悬崖模拟、每日健康与复审 cron 已进入迁移树并在线运行；剩余仅 2026-09-13 aistore 续期换 key、2026-09-04/11-15 运营复审等外部动作。旧 `llm_router.py` 代码锁已释放；不得把供应商控制台动作扩大为代码改动。

- `UX-RN-RESTAURANT-PROCUREMENT-DELIVERY-20260802` — `blocked` — Owner: `/root` — 实现提交已进入迁移树，代码与 Expo Web 验收可核销；当前餐饮 RN OTA runtime 与真实设备验收无法确认。实现文件 scope 已释放；如继续，仅新建 exact-commit OTA/设备验收项。

- `SEC-CREDENTIAL-ROTATION-20260719` — `blocked` — Owner: `/root` — tracked tree、服务器副本、数据库/JWT/internal/BaoTa/OSS RAM 替换已完成；仍需各供应商控制台吊销旧 Aliyun 主账号 AK、旧 Model Studio/Zhipu/DeepSeek key 与微信长期凭证并验证失效。旧 broad 代码锁已释放；不得在本任务做历史重写。

- `ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001` — `blocked` — Owner: `/root` — 候选 DTO、置信度/reason、同厂历史匹配和 Web 展示已进入迁移树；分类建议目标测试与 Excel preview 复用未完整证明，当前无执行者。陈旧 scope 已释放；恢复时按最新 Base 重新登记。

- `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` — `blocked` — Owner: `/root` — CUSTOMER_OWNED 与销售 lineage 已有，但 PURCHASE/CUSTOMER_SUPPLIED/TRANSFER/退回/退料/期初/调整的统一任务与来源矩阵未完整实现。当前无执行者，旧高风险 scope 已释放；恢复时需重新登记 Entity/Flyway/JPA 门禁。

- `BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001` — `blocked` — Owner: `/root` — V94、allocation 核心、财务 AP 未清项付款与旧采购付款只读边界已进入迁移树；四维状态、付款条款里程碑/计划/三单匹配未完整实现。CORE 子任务已归档；父任务旧广域锁释放，恢复时按最新 Base 重锁。

- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001` — `blocked` — Owner: `/root` — 净需求、客户来料、ownership/idempotency 与旧直接收料 fail closed 已有；严格同批 identity/单位/日期/QC/cost 匹配及独立库存 transaction 验收不足。需求仍有效且跨模块，保留 scope，`NOT_DEPLOYED`。

- `ENH-F006-CUSTOMER-CREATE-PROGRESSIVE-DISCLOSURE-001` — `blocked` — Owner: `/root` — Web 渐进表单已存在，但当前后端仅 name 必填，与 ACTIVE 原五项强制及销售单补全门禁冲突。须先由 Owner 裁定产品真值；保留 scope，生产客户零修改。

- `ENH-F006-SUPPLIER-IMPORT-001` — `blocked` — Owner: `/root` — 供应商必填资料、Excel 预览/幂等确认、状态生命周期、采购门禁与详情信息架构尚无本轮完整 tree/test 核销证据；当前无专用 PR。需求和 scope 均保留，恢复时先核对公司 main 的现有实现，严格 `NOT_DEPLOYED`。

- `BUG-F006-UNIT-DIMENSION-SCOPE-001` — `blocked` — Owner: `/root` — UnitContractService、原料/采购/入库/BOM 单位门禁及“件/个/只”字面单位的完整验收尚未本轮核销；保留 scope 与真实 JPA/Service 门禁，生产业务写入为 0。

- `BUG-F006-R2-SKU-UNIT-001` — `blocked` — Owner: `/root/supplier_frontend` — SKU displayUnit、净含量单位、同维度 canonical 化与历史回填尚无本轮完整 tree/test 核销证据；保留 Web scope，Java 契约仍须协调者另行审计，严格 `NOT_DEPLOYED`。

- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001` — `blocked` — Owner: `/root/workprocess_governance` — canonical/mergedInto、未来候选排除、既有 Workflow/SKU/计划/报工引用不迁移，以及排序/单位从主数据移除的完整 JPA/Web 验收尚未本轮核销；保留 scope，生产重复组零治理。

- `FEATURE-F006-R2-BOM-SUBSTITUTE-001` — `queued` — Owner: `/root` — Java CORE 已归档，但父任务的 Web、计划、领料、报工、成本和追溯快照尚未证明完成；继续由统一 Workflow/BOM 任务串行吸收，保留父任务 scope，严格 `NOT_DEPLOYED`。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE` — `queued` — Owner: `/root` — Workflow 草稿结构、BOM 完整性、激活/发布和生产计划准入的端到端门禁尚未证明完成；与统一 Workflow/BOM 状态机串行，保留 scope、ACTIVE-only 运行契约与历史快照零改写。

## Scope 锁地图

- `SOP-RAG-SYNC-20260824`：仅 `web-admin/public/aiassist.html` 工厂 tab、`docs/manual/F006-production-full-chain-manual-test-sop.html`、`backend/python/food_kb/data/f006_production_sop.md`、必要的 `backend/python/food_kb/api/manual_chat.py` 确定性回答合同、`backend/python/tests/test_food_kb_manual_chat_sop_contract.py`、`docs/dispatch/ACTIVE.md` 与当日归档；餐饮 tab、`/cysop/` 和三个餐饮 source 只读，不改 Java/RN/Web 业务实现，不写任何生产 ERP 数据。

- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811`：仅 `tools/vision-lab/work_area*.py`、`tools/vision-lab/mine_tray_queue.py`、`tools/vision-lab/tray_workflow.py`、`tools/vision-lab/evaluate_tray_candidate.py`、对应 `tools/vision-lab/tests/`、`docs/ops/liushanmen-vision-lab.md`、`docs/dispatch/ACTIVE.md` 与当日归档；不得改写人工真值、保护集、原图、现有模型或生产。

- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001`：销售需求、BOM净需求、采购建议、客户来料、仓储来源任务及对应 Java/Web/测试/dispatch；涉及 Entity/Repository/Flyway 时必须重登记最新 Base 并通过真实 JPA/迁移门禁。不得开启普通手工收料写入口或修改生产数据。

- `ENH-F006-CUSTOMER-CREATE-PROGRESSIVE-DISCLOSURE-001`：客户 Web 共享表单与 Java Customer DTO/Service/Repository/API/import、销售使用门禁、目标测试/dispatch；先裁定联系人/电话/地址是否强制，再实施。不得修改生产客户数据。

- `ENH-F006-SUPPLIER-IMPORT-001`：供应商 Java Entity/DTO/Controller/Service/Repository、采购门禁、共享 Excel 导入与目标测试；Web 供应商列表/详情/新增编辑/导入及 API/types/tests；必须真实 JPA Context（如触及查询），生产业务写入为 0。

- `BUG-F006-UNIT-DIMENSION-SCOPE-001`：Java 单位 Entity/DTO/Repository/UnitContractService、原料/采购/入库/BOM 场景校验及真实 JPA/Service 测试；Web UnitSelect/API/types/display helper 与目标测试；不得改变历史异常记录或生产数据。

- `BUG-F006-R2-SKU-UNIT-001`：仅 Web SKU 新建/编辑基础规格、displayUnit/净含量单位 helper 与目标测试；Java 净含量契约须另行登记不重叠 scope。

- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001`：Java WorkProcess/Workflow/SKU 关联、DTO/Repository/Service/Controller、Flyway/JPA tests；Web 工序治理及排序/单位字段移除与目标测试；不得迁移既有业务引用或治理生产重复组。

- `FEATURE-F006-R2-BOM-SUBSTITUTE-001`：BOM 替代关系的 Web、版本/计划/领料/报工/成本/追溯快照与目标测试；Java CORE 已完成，父任务不得重复修改 CORE Flyway/Entity，必须与统一 Workflow/BOM 任务串行。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE`：Workflow 草稿结构、BOM 完整性 DTO/Service/API、激活/发布/生产计划准入、Web 阶段引导与目标测试；不得改写历史快照或生产数据。

## 阻塞项

- VisionLab：正式 ROI audit 仍有 1 个 unjudgeable；旧计划任务 LastResult=1 且代码源尚未迁到公司受控 worktree。
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
