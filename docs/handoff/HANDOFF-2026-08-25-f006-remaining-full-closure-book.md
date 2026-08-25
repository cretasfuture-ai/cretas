# F006 剩余业务闭环交接 Handoff Book

**编写日期**：2026-08-25
**交接对象**：Claude Code 新任务 / 新会话
**目的**：在保留已验收生产链路的前提下，完成仍未闭环的成本、收料、供应商、客户、应付、工序、BOM 与设备验收。
**性质**：阶段交接、范围边界与验收契约；不是任何未完成场景已经通过的证明。
**执行模式**：DIRECT-first，一个协调者、一个写 Owner、零子代理；共享业务状态、F006 写入、迁移、合并和部署全部串行。

## 1. 阅读顺序与当前事实

开始前必须完整阅读：

1. `C:\Users\Steve\my-prototype-logistics\AGENTS.md`。
2. 当前 Claude Code 所适用的仓库 `CLAUDE.md` 与项目规则；主工作区有其他 Owner 的修改，只读获取，不覆盖。
3. 最新 `origin/main` 上的 `docs/dispatch/ACTIVE.md`。
4. 最新 `origin/main` 上的 `docs/dispatch/archive/2026-08-25-active-history.md`。
5. 本 Handoff Book 和同目录 `PROMPT-2026-08-25-claude-code-f006-remaining-full-closure.md`。
6. `C:\Users\Steve\cretas-f006-production-gap-closure-20260825\.playwright-mcp\f006-production-gap-closure\E2E-F006-PROD-GAPS-20260825-082131\ACCEPTANCE-REPORT.md`。
7. 上述证据目录内的 `VIDEO-HANDOFF-SUMMARY.md`、`video-source/action-log.json`、`video-source/asset-manifest.json` 与 `continuation-state.json`。
8. 主工作区 gitignored 的 `.codex/rules/test-credentials.md`、必要的数据库/阿里云凭证文件：只读使用，禁止复制到 tracked 文件、截图、终端输出或提交。

本交接建立时已经核对：

| 项目 | 已观察事实 | 接手要求 |
| --- | --- | --- |
| 最新已知 `origin/main` | `8925b57ea5739b346433e7f274d4c5be53e07185` | 必须重新 `git fetch origin`，实际最新 SHA 优先 |
| 最近已验证生产 Java 发布 | `89624e7562885e76cc4926a003bfa94fbc92807b`，PR #53 | 运行时可能变化，重新核对 upstream、进程和健康 |
| Java Git tree | `67ef157779345df6a4489cf35d000f0b549a25ab` | 当时与后继 main 的 Java tree 一致，不代表 Web/Python 一致 |
| 真实 upstream | `47.100.235.168:10010`，active `cretas-backend` | 蓝绿槽会切换，不得假设仍是 10010 |
| 上次进程启动 | `2026-08-25 09:13:05 CST`，PID `2148416` | 仅历史基线，不得冒充新一轮部署证据 |
| 上次 JAR | `/www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar`，mtime `09:12:58 +0800` | 使用真实 active 槽位和当前 JAR 重新核验 |
| 上次 release 回执 | `C:\Users\Steve\.cache\cretas\release-reports\cretas-1787620356-37791.json` | 只作为历史基线 |
| 已完成任务归档 | PR #56；原 production / Workflow / gap 三项均已移出 ACTIVE | 不得继续占用已经释放的 scope |
| 主工作区 | `C:\Users\Steve\my-prototype-logistics`；存在大量其他 Owner 的 dirty/untracked 内容 | 严禁直接修改、reset、stash、clean、覆盖或移动 |
| F006 浏览器/发布窗口 | 前序任务已实际退出、关闭浏览器并释放窗口 | 新会话必须重新登录，不能复用历史 storageState |

### 1.1 必做 Git / worktree 预检

```powershell
git fetch origin
git worktree list --porcelain
git branch --show-current
git rev-parse HEAD
git rev-parse origin/main
git status --porcelain
git show origin/main:docs/dispatch/ACTIVE.md
```

后续代码、证据、临时脚本、截图和录屏全部放入新的独立任务 worktree。仅当现有 worktree 完全干净、基于当前 `origin/main`、Owner 与 scope 一致、没有其他 Chat 占用时才可复用。否则在确认目标目录和目标分支都不存在后，从最新 `origin/main` 创建新的 `codex/*` 或符合 Claude Code 仓库规范的隔离分支/worktree。禁止在主工作区、`main` worktree、任何旧 release worktree 或前序专项 worktree 上直接修改。

## 2. 已经闭环：不要重新开工

以下业务已有真实 F006 UI、截图、API/库存回读、缺陷修复或独立目标测试。只有本轮改动触及这些契约时才做窄回归，不得机械重复写入：

1. 客户订单生产、正式报工、合格质检与普通发货历史链路。
2. `SAFETY_STOCK` 两轮正式报工、S1/S2 各 `1盒` 的增量小结。
3. 小结撤销、工厂总监审批、合法重新小结、最终 `COMPLETED / POSTED`。
4. 四种原料各 `2.01kg` 形成 `8.04kg` WIP；包装消耗 `8kg`、产出 `10盒`，剩余 `0.04kg` 由独立二次加工真实开工后合法使用。
5. 报产 `10盒` 时超收 `11盒` 的中文 HTTP 409；待收、已收、库存、批次和流水均无部分写入；随后合法实收 `10盒`。
6. 历史专项中的 `6+3+1` 分次入库、短收、原料/WIP/成品盘点、盘盈盘亏审批。
7. 全部合格、部分不合格、全部不合格质检及隔离、返工、报废/报损、复检。
8. 销售/仓储对不合格和隔离批次的拦截。
9. Workflow 当前配置可编辑；未报工计划 A/C/D 重绑定新版本，已正式报工计划 B 固定旧版本；历史只显示已报工计划真正引用的旧版本。
10. Workflow 历史只读、用旧版本更新当前配置、无无用快照、双会话冲突、幂等、仓储主管 403，以及手工 `/snapshot` 契约退役。
11. BC013 长度物料以 `m` 作为合法采购单位，kg/m 同量纲门禁、税价实时计算、采购草稿详情、取消与关系停用。
12. 常规财务尾链、价格掩码、普通仓储/采购/质检页面；不要用这些已知路径替代后文真实缺口。

### 2.1 可用证据与视频

- 完整验收报告：`C:\Users\Steve\cretas-f006-production-gap-closure-20260825\.playwright-mcp\f006-production-gap-closure\E2E-F006-PROD-GAPS-20260825-082131\ACCEPTANCE-REPORT.md`。
- 上次当前生产专项：`C:\Users\Steve\cretas-f006-production-gap-closure-20260825\.playwright-mcp\f006-production-gap-closure\E2E-F006-PROD-GAPS-20260825-082131\video-edit\F006-PRODUCTION-FULL-CHAIN-postdeploy-89624e75.mp4`。
- 仓储/质检历史视频：`C:\Users\Steve\cretas-f006-production-gap-closure-20260825\.playwright-mcp\f006-production-gap-closure\E2E-F006-PROD-GAPS-20260825-082131\video-edit\F006-WAREHOUSE-QUALITY-HISTORICAL-BASELINE-20260824-25.mp4`。
- Workflow 独立视频：`C:\Users\Steve\cretas-workflow-current-version-e2e-20260825\.playwright-mcp\workflow-current-version\E2E-F006-WORKFLOW-VERSION-20260824-144217-CONT-20260825-071956\video-edit\E2E-F006-WORKFLOW-CURRENT-VERSION-postdeploy-035db9a8.mp4`。
- 采购单位独立视频：`C:\Users\Steve\cretas-f006-unit-dimension-scope-20260824\.playwright-mcp\unit-dimension\E2E-F006-UNIT-DIMENSION-20260824180630\video-edit\E2E-F006-UNIT-DIMENSION-demo.mp4`。
- 前序 `runKey = E2E-F006-PROD-GAPS-20260825-082131`，有 `185` 张截图、`37` 条 action；旧实体和素材只能只读，不得续写、清理或冒充新部署 PASS。

## 3. 剩余业务闭环总表

| 优先级 | 条目 / 现有 ACTIVE ID | 当前已知缺口 | 主要验收 |
| --- | --- | --- | --- |
| P0 | `BUG-F006-PRODUCTION-COST-CLOSURE-001`：生产真实成本闭环，需新登记 | 已真实入库，但提示未归集原料领用成本；成品单位成本为空 | 有价原料→领用/报工→结单→成品批次单位成本→财务结转，全链金额守恒 |
| P0 | `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001` | 需求来源、批次 identity、单位/日期/QC/cost 和库存交易仍不足 | 销售需求/BOM→采购或客户来料→合法来源任务→一次性收料→可追溯库存 |
| P0 | `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` | PURCHASE、CUSTOMER_SUPPLIED、TRANSFER、退回、退料、期初、调整未形成一致来源矩阵 | 来源合法、ownership 清晰、状态机唯一、幂等、权限和 transaction 守恒 |
| P0 | `BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001` | 仅基础 allocation/AP 付款已存在；付款里程碑、四维状态、三单匹配未闭环 | 采购/收货/质检/发票/付款计划一致，无越权旧入口、无真实外部支付 |
| P1 | `ENH-F006-SUPPLIER-IMPORT-001` | 必填资料、Excel preview/确认幂等、生命周期、采购准入与详情架构证据不完整 | 预览零写、确认只写一次、错误行可解释、停用供应商不可新采购 |
| P1 | `ENH-F006-CUSTOMER-CREATE-PROGRESSIVE-DISCLOSURE-001` | 前端渐进表单与后端仅 name 必填、原五项必填规则相矛盾 | 基于真实产品真值统一创建/编辑/导入/销售准入，不擅自决定必填 |
| P1 | `ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001` | 分类建议、同厂历史匹配与 Excel preview 复用缺少完整契约测试 | 候选、置信度、原因、租户隔离和 preview 共用同一真值 |
| P1 | `BUG-F006-R2-SKU-UNIT-001` | displayUnit、净含量单位、同量纲 canonical 化与历史兼容未核销 | SKU 创建/编辑、历史展示、m/kg/g/盒等不跨量纲且保持 BC013 采购修复 |
| P1 | `FEATURE-F006-R2-WORKPROCESS-DEDUP-001` | canonical/mergedInto、候选去重、引用保护、排序/单位归属缺证据 | 新候选唯一、旧 Workflow/SKU/计划/报工引用不改写，真实 JPA 门禁 |
| P1 | `F006-R2-WORKFLOW-FIRST-BOM-GATE` | Workflow 草稿、BOM 完整性、激活/发布与建单准入没有完整 E2E | 草稿可编辑，缺项中文阻断，完整后激活与建单，旧快照不改 |
| P1 | `FEATURE-F006-R2-BOM-SUBSTITUTE-001` | Java CORE 已存在，但 Web、计划、领料、报工、成本和追溯尚未闭环 | 授权替代料选择、比例/量纲、真实消耗、成本与追溯；不重复改 CORE |
| P2 | F006 独立销售经理角色 | 当前凭证文件缺少可用账户；此前由具备权限的工厂总监代操作 | 现有合法 F006 销售账号真实退出/重登与页面/API 权限一致；不创建权限或全局用户 |
| P2 | F006 Android 真机 / APK / OTA | 历史 Expo Web 不能证明 Android 真机、已部署签名 APK 或 OTA | 已连接真机、正确 runtime/build、逐角色真实操作、可恢复录屏 |
| P2 | 连续原始操作录像 | 前序 Playwright 直接集成只有同步截图，没有连续 WebM | 条件允许时开启真实录像；不能因此降低业务断言 |

非 F006 的餐饮 RN、VisionLab、LLM 续期和凭证轮换不是本交接的业务范围。禁止顺手吸收这些其他任务。

## 4. P0：原料领用、产出成本与财务结转

### 4.1 已有真实失败

独立订单计划 `ef5392c7-a575-42a3-8691-ebca0d8306e1`，结单 `0242ccfa-aa9d-43c6-a9b8-6c01d9247fdc`，最终成品批次 `048b8ff2-1ffd-4481-b9e5-5e5593103542`：

```text
本次未能算出成品单位成本: 没有归集到本计划的原料领用成本。
成品已按实收数量入库, 单位成本留空(不填 ¥0, 避免后续按零成本结转)。
下一步: 请在成本核算处人工核对本计划成本, 或联系管理员检查成本归集配置。
```

这是真实业务缺口；系统诚实留空并显示警告不是修复。禁止把缺价填成 `0`、隐藏警告、将查询失败吞掉或直接 SQL 补写旧生产数据。

### 4.2 先定位，不要按旧注释误修

优先只读检查：

- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java`：`allocateWorkflowOutputCosts` 与 `COST_UNAVAILABLE_WARNING`。
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/OrderCostBreakdownService.java`：`settlementConsumptionsFor`、生产批次/计划关联、缺价判定、成本完整性和半成品回溯。
- `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductionSettlementConsumptionRepository.java`。
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/OrderCostBreakdownServiceTest.java`。
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductionWorkflowOutputCostAllocationTest.java`。
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/MultiOutputCostPolicyContractTest.java`。

**重要：当前 main 已实现 `production_settlement_consumptions → settlementConsumptionsFor → MaterialConsumption 形状` 的读侧桥接。不能再按过期印象断言“两张表完全未接通”，更不能复制一套成本归集算法。**

必须对新隔离样本逐项回读：

1. `material_batches.unit_price` 是否真实非空、非零，单位与采购/领用一致。
2. 计划、生产批次、正式报工行、结单领用行和物料批次之间的 factoryId / planId / batchId 是否一致。
3. 结单领用是否真正写入；是否被 soft delete、来源类型、事务时序、重复过滤或多个 production batch 的 `plansMerged` 排除。
4. 在制品、半成品、包装、调料、主副产物是否存在未知单价而使 `costComplete=false`。
5. `OrderCostBreakdownService` 返回的原料成本、人工、包装、总成本、单位成本与 `missingCostItems`。
6. 成品 output line、成品批次、库存 transaction 与财务成本/COGS 是否共享同一可追溯金额口径。

至少构造两组新样本：

- **有价正向**：四原料各 `2kg`，以隔离测试批次记录真实单价；合计成本可精确手算；生产 `10盒` 后 `成品单位成本 = 可归集总成本 / 10`。
- **诚实失败**：某必需原料/半成品确实缺价；保持 `unitCost=null`，完整中文解释缺哪个来源，不能输出 `¥0` 或继续零成本结转。

补充变体：分次 `6+3+1` 入库、重复请求、撤销/重结、多生产批次、共享 WIP、主副产比例、kg/g 同量纲、m 包材、四舍五入与结转总额守恒。只重跑受影响成本断言，不重复整套历史仓储普通流程。

### 4.3 验收

```text
Σ(实际领用数量 × 批次单价)
  + 已授权的人工 / 包装 / 其他成本
  = 生产计划有效总成本
  = Σ(产出行分摊成本)
  = Σ(已入库成品批次成本)
  = 下游财务结转使用的成本基数
```

排除撤销、未正式报工草稿、软删除和重复请求；缺价时显示明确原因且不伪造零成本。涉及 Repository/Entity/Flyway 时先重登记 scope，并通过真实 PostgreSQL/JPA/迁移版本唯一性门禁。

## 5. P0：需求驱动收料与统一来源矩阵

串行处理 `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001` 和 `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001`；两者共享采购、仓储、库存、QC、ownership 与成本，不得交给并发 Owner。

必须逐来源明确：

| 来源 | 必要上游凭证 | ownership / 断言 |
| --- | --- | --- |
| `PURCHASE` | 已批准采购单 / 收货任务 | 自有库存；数量、单位、采购税价、批次、质检和成本一致 |
| `CUSTOMER_SUPPLIED` / `CUSTOMER_OWNED` | 客户来料通知 / 销售订单 lineage | 客户归属不能误转自有库存，销售链路可追溯 |
| `TRANSFER` | 已批准调拨单 | 来源仓扣减与目标仓增加同一事务，不可凭空增库 |
| 生产退料 / 退回 | 已正式领用 / 生产关联 | 精确批次与数量回退，不允许重复返还 |
| 合法期初 / 调整 | 授权单据和审批 | 权限、审计和审批门禁真实生效 |

每类验证：上游状态、来源 id/line id、物料 id、批次、单位/换算、数量、日期、QC、单价/成本、ownership、仓库、操作者、幂等键、交易流水、库存余额及拒绝后零部分写入。

禁止恢复旧的“无来源手工直接收料”写入口；现有 fail-closed 行为必须保留。当前 ACTIVE 对此 scope 要求生产业务零修改；优先使用真实 PostgreSQL 集成测试、隔离测试库及本地 Web E2E。未获得新的明确授权前，不修改生产 F006 既有收料数据。

## 6. P0：采购、应付和付款边界

`BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001` 的 CORE 与 V94 已存在，不要重复实现旧采购付款入口。以现有真实 main 与测试为准：

- `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/PayableSettlementControllerTest.java`。
- `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/PaymentRequestControllerLegacyWriteBoundaryTest.java`。
- `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/finance/PurchaseApPaymentContractRepositoryQueryValidationTest.java`。
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finance/PayableSettlementServiceImplTest.java`。
- `web-admin/src/views/finance/ar-ap/index.vue`。
- `web-admin/src/views/finance/ar-ap/__tests__/payableSettlementBoundary.source.spec.ts`。

必须闭合以下四维口径，并据实际代码确认字段命名：

1. 采购单审批/履约状态。
2. 实际收货与质检通过数量。
3. 发票/应付确认与未税/税额/含税金额。
4. 付款计划、里程碑、已付/未付、冲销及重复支付防护。

验证预付、到货付款、分期、短收、部分不合格、取消、退款/冲销、跨订单错误分配、重复付款、三单匹配失败和角色越权。禁止发起真实银行支付、外部支付、真实资金转移、全局财务配置修改；付款状态机优先在隔离库完成。旧采购页面只读，唯一付款入口必须服从财务权限与审批。

## 7. P1：供应商导入、客户与物料主数据

### 7.1 供应商导入

复核已有：

- `web-admin/src/views/procurement/suppliers/SupplierImportDialog.vue`。
- `web-admin/src/views/procurement/suppliers/supplierImport.ts` 和 `supplierImport.spec.ts`。
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/supplier/SupplierImportServiceImplTest.java`。

完成：模板/列映射、必填资料、重复主体识别、Excel preview 零写、逐行中文错误、确认幂等、重复文件/重复提交、状态生命周期、启用/停用、供应商详情、采购准入。只处理本 runKey 隔离测试数据；现有 ACTIVE 要求生产供应商业务写入为 `0`，生产不得批量导入或修改真实供应商。

### 7.2 客户渐进创建

当前矛盾是真实后端只要求 `name`，而旧要求可能把联系人、电话、地址等五项都设为强制。先核对仓库现行产品文档、API、已有 Web 表单、业务使用门禁和用户既有明确指令：

- 若客户草稿允许最少字段，则创建阶段只要求最少字段；销售订单进入需交付/开票/履约阶段时，按实际业务要求明确提示补齐。
- 若确有明确产品真值要求五项一次性必填，则 Web/API/导入/编辑统一强制，并保留完整中文错误。
- 若两种口径无法从现有权威材料裁定，此项标记 `BLOCKED_PRODUCT_DECISION`，继续其他任务；不得偷偷替用户决定，也不得修改现有生产客户。

### 7.3 分类建议与 SKU 单位

分类建议必须包含候选、置信度、原因和 F006 内历史匹配；Excel preview 与手工创建共用同一服务，不可跨租户泄露。

SKU 单位必须保证库存基本单位、展示单位、净含量单位、包装规格和采购单位各司其职：`kg/g`、`m`、`盒/片/卷` 只能经过明确同量纲或合法包装转换。保持已完成的 BC013 `m` 采购修复；禁止重新允许任意字符串输入或跨量纲选择。旧 SKU 兼容只读证明，不对生产历史数据批量回填。

## 8. P1：工序治理、Workflow/BOM 准入与替代料

三个范围共享工序、Workflow、BOM、计划、报工和成本，必须由同一 Owner 串行完成。

### 8.1 工序去重

- 为未来工序候选建立稳定 canonical / mergedInto 规则。
- 历史 Workflow、SKU、生产计划和正式报工引用不得迁移、覆盖或断开。
- 确认排序、单位等业务归属是否属于配置层，不在主数据层重复维护。
- Web 候选、API、查询、权限和并发更新一致。
- 涉及 Flyway/Entity/Repository 必须先检查迁移唯一性并运行真实 JPA Context。
- 不得治理生产中既有重复组，除非用户另行明确授权。

### 8.2 Workflow-first / BOM gate

- 草稿允许用户逐步编辑，但不可把不完整配置发布为可生产版本。
- 明确列出缺少的原料、工序、产出、单位、批次要求、BOM 激活状态。
- 未完成 BOM 时禁止激活/发布或创建正式生产计划，并返回完整中文原因。
- 完整配置激活后可创建计划；未正式报工计划使用最新当前版本，已正式报工计划继续绑定旧快照。
- 历史版本保持只读，不能恢复 `/snapshot` 手工入口，也不能把历史快照写回。

### 8.3 BOM 替代料

- Java CORE 已归档，先核对现有实现，不得重复修改 CORE Flyway/Entity。
- 验证 Web 授权配置、可替代关系、优先级、库存候选、数量比例、量纲/规格、过敏原或质量规则。
- 生产计划快照固定授权替代关系；报工时仅允许选择该计划可用替代批次。
- 领料与报工真实记录“原本 BOM 物料 / 实际替代物料 / 数量 / 单位 / 批次 / 原因 / 操作者”。
- 替代料实际成本必须进入 P0 成本归集，追溯可查，不重复扣原料。
- 拒绝未授权、跨租户、跨量纲、缺 QC、隔离、不合格或库存不足的替代批次，并证明零部分写入。

## 9. P2：角色、Android 和录像

### 9.1 独立销售经理

只从 gitignored 的既有凭证文件中寻找合法 F006 账号；每次角色切换真实退出并重新登录，登录响应证明 `factoryUser.factoryId=F006`。前序工厂总监有权限代操作，但不等于已验证独立销售经理。

没有可用账号时标记 `BLOCKED_MISSING_ROLE_CREDENTIAL`，继续其他工作；不得创建全局用户、修改角色、权限矩阵或真实员工账户来凑测试。

### 9.2 Android 真机 / APK / OTA

先只读确认已连接 Android 设备、ADB/scrcpy、app 包名、版本、runtimeVersion、签名与当前生产 API。已有 Expo Web 截图不能替代真机。

如有合法可用设备：

1. 录制统一竖屏；登录凭证输入不录制。
2. 以当前 F006 新 runKey 操作生产/入库/发货/我的等受本轮改动影响的最小路径。
3. 每个业务写入同步保留前置、填表、反馈、刷新、详情/API 五类素材。
4. 核对实际设备版本和 OTA runtime；没有明确授权不得发布 APK、OTA、改签名或覆盖设备用户数据。

没有设备或安装/OTA 权限时标记明确 blocker，不得把浏览器页面写成真机 PASS。

### 9.3 连续录屏

Playwright 或 Claude Code 浏览器若支持原始视频，则对隔离上下文启用；否则继续逐操作截图并按真实时间线生成 MP4。禁止 AI 生成/修改系统画面，禁止展示密码、Token、手机号、银行账号和浏览器自动填充；失败截图保留，不得当成功素材。

## 10. Scope、生产写入与数据安全

1. 恢复任何 `blocked`/`queued` 条目之前，先从最新 ACTIVE 判断是否仍有其他 Owner。`/root/supplier_frontend`、`/root/workprocess_governance` 等旧 Owner 名称不能自动视为已释放；先取得明确无冲突证据，再由唯一协调者登记 scope、Base SHA、Owner、验收命令和停止条件。
2. 已释放旧 scope 的 taxonomy、统一来源和 AP 父任务必须按新的最新 Base 重新登记；不能沿用早期 worktree 或旧 SHA。
3. 本交接不授权 F006 以外任何生产业务写入，不授权全局配置、跨租户数据、真实付款、权限变更、无范围清理或生产数据库直接修复。
4. 对供应商导入、客户修改、需求收料、统一来源等 ACTIVE 明确要求生产写入为 `0` 的范围，默认在本地/隔离 PostgreSQL 完成；只有用户后来明确扩大授权，才能按新授权调整。
5. 对已被用户明确授权的 F006 写入型验收，首次写入前必须用实时登录响应证明 `factoryUser.factoryId == F006`；请求路径、payload、目标父子实体和写后回读均属于 F006。
6. 必须创建全新 `runKey`；前序计划、销售订单、采购单、质检单、盘点单、成品批次和在制品仅可只读，不得续写、取消、逆转或清理。
7. 每次写入前记录预计 mutation；写后记录实体 ID、业务单号、数量、单位、状态、操作者、时间、截图和 API 回读。
8. 拒绝边界需证明无部分写入：单据、库存批次、交易流水、金额、状态和其他租户均不变。
9. F006 写入、迁移、同一 Maven `target/`、同一生产计划/库存/付款状态、合并和部署严格串行。
10. 禁止 reset、stash、clean、覆盖、移动或删除主工作区、其他 worktree 与其他 Owner 修改。

## 11. 推荐波次

| Wave | 内容 | 退出条件 |
| --- | --- | --- |
| 0 | 当前 Git / ACTIVE / worktree / runtime / F006 零写预检 | 独立最新 worktree、Owner、scope 与授权清楚 |
| 1 | P0 成本缺口的真实根因诊断和先失败目标测试 | 最小复现、缺价/关联/时序分类清晰 |
| 2 | 真实成本归集 + 产出分摊 + 财务基数 | 有价样本非空正确；缺价样本诚实 null；守恒成立 |
| 3 | 需求驱动收料与统一来源矩阵 | 每类来源、ownership、QC、成本、幂等和零部分写入通过 |
| 4 | 采购 AP / 三单匹配 / 付款计划 | 四维状态、里程碑、越权与重复防护通过；无真实资金动作 |
| 5 | 供应商导入 / 分类建议 / SKU 单位 | 隔离库目标测试和 UI 契约通过，BC013 无回归 |
| 6 | 客户必填规则裁定与渐进创建 | 统一真值通过；无法裁定时单独 blocked，继续后续波次 |
| 7 | 工序去重 → Workflow/BOM gate → 替代料 | 历史引用与已开工快照不变，替代成本/追溯闭环 |
| 8 | Web / Java / PostgreSQL / RN / 权限目标验证 | 与实际改动范围相称；JPA/Flyway 深度门禁全部通过 |
| 9 | 唯一可信构建、PR、main 合入、生产部署 | clean exact-main；跨端契约按安全顺序发布 |
| 10 | 部署后真实 F006 窄回归、Android/录像可用性、证据归档 | 新部署 SHA 上的 UI/API/库存/金额断言和独立 blocker 清单 |

每个波次优先完成当前已明确且可安全执行的工作；不要因客户必填规则或设备缺失而停止所有其他流程。

## 12. 测试与发布门禁

- Java：先跑修改范围目标测试；Repository/Entity/JPQL/Flyway 变更必须追加真实 JPA Context、真实 PostgreSQL 事务/并发和迁移版本唯一性。
- Web：相关 Vitest、`typecheck` 与生产 build；跨端 schema/enum/单位/金额契约共源。
- RN：仅在本轮确有 RN 修改时执行对应 Jest/类型检查和真实 Expo/RN 路径；Android 真机独立标注。
- 权限：前端入口、直接 URL、API 与跨租户工厂 id 一致；价格/财务敏感字段继续掩码。
- Maven：最终 release 目标测试与 JAR 只使用一次可信 `clean package` 生命周期；复用 manifest 时证明 Git tree、SHA-256、JAR 完整性和 worktree clean。
- Web：使用可信 archive/tree manifest；不得根据 dist mtime 或存在性绕过验证。
- 发布：只从干净 exact `origin/main` 的 detached release worktree，使用仓库统一发布入口；Java/Web 不兼容时串行，生产 active 槽位每次实时读取。
- 生产验收：真实 upstream、systemd、进程启动时间、JAR mtime、直连及公网 health；Web 适用时验证四方 hash。历史脚本 `SUCCESS` 不能代替当前生产事实。
- 分别报告：实现、目标测试、commit、PR、main 合入、迁移、部署和用户验收；任何一项未完成均不得写成已完成。
- 完成后归档 ACTIVE 并释放 scope；保留新 runKey 的 action-log、视频素材、FAIL 证据和最终验收表。

## 13. 最终验收表

| 验收项 | 结果：PASS / FAIL / BLOCKED / UNVERIFIED | 关键实体 / 金额 / 数量 | 证据 | 原因 |
| --- | --- | --- | --- | --- |
| 有价原料批次与正式领用关联 |  |  |  |  |
| 生产计划原料/人工/包装真实成本归集 |  |  |  |  |
| 成品单位成本非空且计算正确 |  |  |  |  |
| 缺价时诚实 null 与中文解释 |  |  |  |  |
| 分次入库/重复请求不重复分摊成本 |  |  |  |  |
| WIP/主副产/替代料成本与金额守恒 |  |  |  |  |
| 财务 COGS/应付基数一致 |  |  |  |  |
| 采购来源收料 |  |  |  |  |
| 客户来料 ownership 与 lineage |  |  |  |  |
| 调拨 / 退料 / 退回 / 期初 / 调整来源矩阵 |  |  |  |  |
| 无来源收料 fail closed |  |  |  |  |
| 采购 / 收货 / 质检 / 发票三单匹配 |  |  |  |  |
| 付款计划、里程碑、分期与冲销 |  |  |  |  |
| 供应商 Excel preview 零写 |  |  |  |  |
| 供应商导入确认幂等与采购准入 |  |  |  |  |
| 客户必填产品真值已裁定 |  |  |  |  |
| 客户创建 / 编辑 / 导入 / 销售准入一致 |  |  |  |  |
| 物料分类建议与 preview 共源 |  |  |  |  |
| SKU displayUnit / 净含量 / BC013 m 量纲 |  |  |  |  |
| 工序 canonical 去重与旧引用保护 |  |  |  |  |
| Workflow 草稿 / BOM 完整性 / 发布 / 建单门禁 |  |  |  |  |
| 替代料 Web / 计划 / 领料 / 报工 / 成本 / 追溯 |  |  |  |  |
| 独立销售经理真实重登 |  |  |  |  |
| Android 真机 / 正确 runtime / OTA |  |  |  |  |
| 原始连续录像 / 五类截图 |  |  |  |  |
| F006 以外生产业务写入为 0 |  |  |  |  |
| 实施 / 测试 / commit / PR / merge / deploy / acceptance 分项状态 |  |  |  |  |

若缺少明确产品裁决、设备、凭证或生产写入授权，只将对应条目标记 BLOCKED，保留证据并完成其余独立闭环；不得把阻塞扩大成提前停止所有任务。
