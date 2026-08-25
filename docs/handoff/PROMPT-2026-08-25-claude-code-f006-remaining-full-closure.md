# Claude Code 可直接粘贴执行 Prompt

接手并完整执行：

```text
C:\Users\Steve\cretas-f006-remaining-handoff-20260825\docs\handoff\HANDOFF-2026-08-25-f006-remaining-full-closure-book.md
```

上述绝对路径指向独立交接 worktree，立即可读；合入后也可从最新 `origin/main` 读取仓库相对路径 `docs/handoff/HANDOFF-2026-08-25-f006-remaining-full-closure-book.md`。不要要求脏主工作区切换分支或覆盖现有文件。

不要只输出方案。先完整读取最新仓库 `AGENTS.md`、适用 `CLAUDE.md`、最新 `docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-08-25-active-history.md`、上述 Handoff Book，以及：

```text
C:\Users\Steve\cretas-f006-production-gap-closure-20260825\.playwright-mcp\f006-production-gap-closure\E2E-F006-PROD-GAPS-20260825-082131\ACCEPTANCE-REPORT.md
C:\Users\Steve\cretas-f006-production-gap-closure-20260825\.playwright-mcp\f006-production-gap-closure\E2E-F006-PROD-GAPS-20260825-082131\VIDEO-HANDOFF-SUMMARY.md
```

## 接管与硬边界

1. 立即 `git fetch origin`，核对 `git worktree list --porcelain`、当前路径、branch、HEAD、`origin/main`、`git status --porcelain`、ACTIVE、每个条目的 Owner 和 scope 锁。
2. 主工作区 `C:\Users\Steve\my-prototype-logistics` 当前有大量其他 Owner 修改。禁止 reset、stash、clean、覆盖、移动或整理这些内容；禁止直接改主工作区、main worktree、旧 release worktree 和任何此前专项 worktree。
3. 从实时最新 `origin/main` 创建全新隔离 worktree/分支；创建前确认目标目录与分支不存在。首个修改前按最新 Base 在 ACTIVE 登记唯一协调者、允许修改 scope、验收命令和停止条件；有其他 Owner 未释放的条目先协调冲突，不并发写同一状态机。
4. DIRECT-first：一个协调者、一个写 Owner、零子代理。确定性只读预检可并行；采购、收料、库存、生产、质检、成本、付款、迁移、合并、发布和 F006 业务写入全部串行。
5. 最近已知 main 为 `8925b57ea5739b346433e7f274d4c5be53e07185`；最近已验证 Java 部署为 `89624e7562885e76cc4926a003bfa94fbc92807b`。两者只是历史基线；开始时重新核验真实 upstream、active systemd、启动时间、JAR mtime、直连和公网 health。
6. 旧三个专项已通过 PR #56 归档并释放：生产后续、Workflow 当前版本、生产 gap closure。不要把已完成任务重新登记为在飞，也不要把旧 runKey/旧截图当作当前生产 PASS。
7. 不要重复已完成普通流程：两轮报工/小结/撤销/停产、`0.04kg` 在制品处置、`11盒` 超收拒绝、`6+3+1` 入库、原料/WIP/成品盘点、常规三类质检、普通仓储/财务、价格掩码、Workflow A/B/C 历史绑定、BC013 `m` 采购。只对本轮改动做必要窄回归。

## P0：立即闭合生产实际成本

真实生产失败：成品已入库，但系统提示「没有归集到本计划的原料领用成本」，成品 `unitCost=null`。最小生产证据：

```text
planId       ef5392c7-a575-42a3-8691-ebca0d8306e1
settlementId 0242ccfa-aa9d-43c6-a9b8-6c01d9247fdc
fgBatchId    048b8ff2-1ffd-4481-b9e5-5e5593103542
```

以上实体只读，禁止修改、逆转或清理。

优先定位：

```text
backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java
backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/OrderCostBreakdownService.java
backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductionSettlementConsumptionRepository.java
```

注意：`OrderCostBreakdownService.settlementConsumptionsFor` 已经实现结单领用行的读侧桥接。不要按旧注释误判成“两张表完全没有接通”。先验证：原料批次真实 `unit_price`、计划/批次/领用关联、正式报工和结单行、soft delete、事务时序、`plansMerged`、WIP/包装/人工缺价、`missingCostItems` 与 `costComplete`。

补先失败再修复的真实 Service/PostgreSQL 测试，覆盖：

- 四种有价原料各 `2kg`，总成本可精确手算，产出 `10盒`，成品批次单位成本非空且正确。
- 未知价样本必须保持诚实 `null` 和完整中文原因，禁止填 `¥0` 或隐藏警告。
- 分次 `6+3+1`、重复请求、撤销/重结、多批次、共享 WIP、主副产、替代料、kg/g 和 m 包材不能重复计成本。
- `原料真实成本 + 合法人工/包装成本 = 计划总成本 = 产出分摊 = 成品批次成本 = 财务结转成本基数`。

## P0：收料来源与采购应付

继续 ACTIVE 中：

```text
ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001
ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001
BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001
```

按共享依赖顺序闭合：

1. 销售需求/BOM→采购或客户来料→合法来源任务→仓储入库→质检→库存 transaction→成本。
2. 覆盖 `PURCHASE`、`CUSTOMER_SUPPLIED/CUSTOMER_OWNED`、`TRANSFER`、生产退料、退回、合法期初/调整；逐一验证来源 id、批次、单位、数量、日期、QC、ownership、单价、仓库、幂等和拒绝后零部分写入。
3. 保持无来源手工收料 fail closed；不得开旧入口。
4. 补采购审批/履约、收货质检、发票应付、付款计划/里程碑四维状态；覆盖三单匹配、预付、分期、短收、部分不合格、冲销、重复付款和旧采购付款入口只读。
5. 禁止真实银行支付、外部资金转移、全局财务配置修改；生产写入约束优先服从 ACTIVE，默认使用本地/隔离 PostgreSQL。

## P1：供应商、客户、分类、SKU

串行完成：

```text
ENH-F006-SUPPLIER-IMPORT-001
ENH-F006-CUSTOMER-CREATE-PROGRESSIVE-DISCLOSURE-001
ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001
BUG-F006-R2-SKU-UNIT-001
```

- 供应商：Excel preview 零写、确认幂等、逐行中文错误、必填资料、生命周期、详情和采购准入；生产真实供应商零修改。
- 客户：先从权威用户指令/API/表单裁定“仅 name 创建草稿”还是“五项必填”；创建、编辑、导入和销售准入统一。真值无法裁定时只把此项记 `BLOCKED_PRODUCT_DECISION`，继续其他条目；生产客户零修改。
- 分类建议：候选、置信度、原因、同租户历史和 Excel preview 共源；禁止跨租户泄漏。
- SKU：displayUnit、净含量单位、基础单位、采购规格同源且同量纲；保持 BC013 `m`、kg/g、盒/片/卷契约，禁止任意输入和非法跨量纲。

## P1：工序、Workflow/BOM gate、替代料

同一 Owner 串行接管：

```text
FEATURE-F006-R2-WORKPROCESS-DEDUP-001
F006-R2-WORKFLOW-FIRST-BOM-GATE
FEATURE-F006-R2-BOM-SUBSTITUTE-001
```

1. 工序 canonical/mergedInto 只治理未来候选；历史 Workflow、SKU、生产计划和正式报工引用不得迁移或断开。
2. 不完整 Workflow/BOM 必须给出具体中文缺项并阻止发布/激活/建单；完整配置才允许生产。
3. 严格保持已完成版本契约：已正式报工计划固定旧快照，未报工计划使用当前版本，旧历史只读，`/snapshot` 入口保持退役。
4. BOM 替代料 Java CORE 已存在，不要重复改旧 CORE migration/entity。继续补 Web→计划快照→授权替代→领料→正式报工→成本→追溯全链。
5. 禁止未授权替代、跨租户、跨量纲、不合格/隔离批次和重复扣料；拒绝后证明零库存/零流水部分写入。

## P2：角色、设备和视频

- 仅使用现有合法 F006 销售经理凭证；角色切换必须退出重登并记录登录响应 `factoryId=F006`。没有凭证则记 `BLOCKED_MISSING_ROLE_CREDENTIAL`，不得创建用户或调整全局权限。
- 如存在已授权 Android 真机，验证正确 APK/签名/runtimeVersion/OTA、真实角色路径及竖屏录屏。没有设备或 OTA 授权则如实记录 `BLOCKED_DEVICE_OR_OTA`，不要把 Expo Web 写成真机 PASS。
- 新建 runKey 专属且 gitignored 的证据目录。每次写入保存：操作前、表单填好、提交反馈、刷新持久化、详情/API 回读，维护 `action-log.md`、`action-log.json`、`asset-manifest.json` 与 `VIDEO-HANDOFF-SUMMARY.md`。支持真实录屏时开启，不支持时保留真实同步截图；不得暴露密码、Token、手机号、银行账户。

## 生产写入、测试与发布

1. 不授权 F006 以外租户写入；不授权全局配置、权限变更、生产数据库直接改写、供应商批量导入、生产客户修改、真实付款或无范围清理。
2. 仅在用户当前授权明确覆盖的 F006 场景执行生产业务写入；首次写前实时登录证明 `factoryUser.factoryId == F006`，所有新对象必须绑定新 runKey，旧实体只读。现有 ACTIVE 明确要求生产零写的条目保持零写，使用隔离数据库验收。
3. 涉及 Entity、Repository、JPQL/HQL、Flyway：必须有真实 PostgreSQL/JPA Context、迁移唯一性和事务/并发门禁；Mockito 和 `-DskipTests` 不算通过。
4. Web 改动必须通过目标 Vitest、typecheck/build；RN 改动必须通过适用目标测试；金额、单位、权限和跨租户契约一致。
5. 最终发布只用一次可信 Maven release 生命周期；Web 复用可信 tree/archive；从 clean exact-main detached release worktree 使用统一发布入口；跨端不兼容改动安全串行。
6. 部署后验证真实 upstream、active systemd、进程启动、制品 mtime、直连与公网 health，以及受影响 F006 真实页面/API/库存/金额。不得只相信脚本 `SUCCESS`。
7. 发现产品缺陷后保存最小复现并直接修复；区分脚本失败、测试前置失败和产品失败；不要停在诊断或失败报告。
8. 单项缺少产品裁决、设备、凭证或授权时只阻塞该项并继续其他工作。完成后分别报告：实现、目标测试、commit、PR、main 合入、迁移、部署、生产验收、未闭环项及证据路径，并归档 ACTIVE/释放 scope。

现在从 Wave 0 开始执行，不要只给计划。
