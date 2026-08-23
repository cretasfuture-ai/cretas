# Dispatch 完成记录 — 2026-08-24

## 已汇入整合候选的子任务

以下 `merged` 只表示子任务已经合入 exact-origin 整合候选 `codex/f006-origin-integration-20260823` 的 `838234503a40bd4505e9fda1f6c09f6558b8f7e9`，不表示已进入 `origin/main`、已部署或已完成用户验收；剩余阶段由 ACTIVE 中的 `RELEASE-F006-WAREHOUSE-FINANCE-SALES-WORKFLOW-20260823` 统一承接。

- `UX-F006-WAREHOUSE-RN-SYMMETRY-20260823` / `FEATURE-F006-WAREHOUSE-DELIVERY-HISTORY-20260823` / `FIX-F006-WAREHOUSE-RN-HISTORY-PAGINATION-20260823` — `merged` — 仓管 RN 收敛为首页、入库、发货、我的；任务总数、筛选排序、扫码、单位与入/发货对称完成。采购完成统一显示“已收货”；发货待办与历史独立分页、部分失败保留成功数据，历史可只读核对状态、下一步、操作者、时间、物流、实际批次和数量。目标 RN 6 suites / 52 tests 通过；TypeScript 仅保留 `apiClient.ts` 5 个既有泛型基线错误。

- `UX-F006-FINANCE-ARAP-AUTO-INVOICE-20260823` / `FEATURE-F006-FINANCE-INVOICE-TODO-OUTBOX-20260823` / `FIX-F006-FINANCE-OUTBOX-RISK-20260823` / `FIX-F006-FINANCE-PURCHASE-TOMBSTONE-EVIDENCE-20260823` / `FEATURE-F006-FINANCE-MANUAL-INVOICE-TOMBSTONE-20260823` — `merged` — 财务菜单补齐应收应付、销售开票、采购收票、收款；销售全发货完成形成持久、可租约恢复/重试/死信重排的唯一待审核开票记录，采购完成进入待收票工作台；旧 REST/AI 手工开票入口明确退役，采购发票按租户加锁。V20 真实 PostgreSQL 兼容触发器测试通过。

- `FEATURE-F006-SALES-FULLPAGE-DELIVERY-20260823` / `TRACE-F006-SALES-DELIVERY-STATE-20260823` — `merged` — 销售订单新建改全屏；产品仅允许可售成品/半成品，并支持中文、编码、全拼、首字母检索；加工/供料方式保留，来源仓按生产相关、物流、成品三组限制。列表移除“锁/备/缺”并显示成品，多产品浮层支持键盘；一次性发货严格等于剩余量，分批发货继续显式建子单，销售与仓储两端展示状态、下一步、操作者、时间、物流、批次及数量。

- `FEATURE-F006-WORKFLOW-AUTO-FORK-ACTIVE-GATE-20260823` / `FIX-F006-WORKFLOW-IDENTITY-DEACTIVATION-RACE-20260823` / `REVIEW-F006-WORKFLOW-AUTO-FORK-RISK-20260823` / `TRACE-F006-WORKFLOW-AUTO-FORK-20260823` — `merged` — 未引用草稿原位修改；已引用版本保存时自动分叉。主辅料、工序、半成品、包装、连线与参数继续可编辑；历史 identity/lockVersion 防旧客户端覆盖，运行中旧版本不可修改或停用。V21 将停用与计划/批次进入运行态串行化，真实 PostgreSQL 两种竞争顺序与完整图回读共 8 tests 通过。

- `REVIEW-F006-FINANCE-AUTO-TODO-20260823` / `REVIEW-F006-FINANCE-OUTBOX-RISK-20260823` / `REVIEW-F006-WAREHOUSE-RN-SYMMETRY-20260823` / `TRACE-CRETAS-ORIGIN-RBAC-RECONCILE-20260823` — `merged` — 风险审查与 RBAC 对照已被整合修复吸收；既有权限漂移 4、当前 4、新增 0，旧本地 main/已部署 RBAC 仅作必须保留的兼容边界，未用其整树覆盖 `origin/main`。

## 整合候选验证

- Flyway 654 个版本扫描无重复；新增版本为既有 V18/V19 后的 V20/V21。
- 真实 PostgreSQL：9 tests，0 failure、0 error、0 skipped；已知无关 Hibernate DDL 警告不影响目标断言。
- 发布构建：29 个 Java 测试类共 128 tests，0 failure、0 error、9 个显式 opt-in PG 用例在该生命周期内跳过（已在独立真实 PG 生命周期全部通过）；Java 与 Web 各构建 1 次，可信 JAR SHA-256 `a98d078772674ba822f0f9d5b8fc3e5a16cdf87840486a9fe32b1cf6d517d850`，Web archive SHA-256 `45beb17a54cf72832e7a985ec57bc72b25d0e50e1c56a5fb4567b2e2ae934b3b`。JAR 仅预上传缓存，未安装、未重启、未切流。
- Web：8 files / 122 tests 与 `vue-tsc --noEmit` 通过；仓储 RN 6 suites / 52 tests 通过。实现、测试、PR/合并、部署与用户验收继续分开记录。

## `RELEASE-F006-WAREHOUSE-FINANCE-SALES-WORKFLOW-20260823` — `merged`

- **合并真值**：PR #17 已 squash merge；`origin/main` 为 `96175083beba3570eeeb35d265f182a67a2b0a77`。最终 exact-main 候选完成 Java 29 个显式测试类（148 tests，0 failure / 0 error；9 个 opt-in PostgreSQL 用例在独立真实 PostgreSQL 生命周期通过）、Web 生产构建、Web 421 files / 3439 tests（5 skipped）、RN 6 suites / 52 tests、权限漂移基线 4 / 当前 4 / 新增 0。
- **部署真值**：统一发布回执 `C:\Users\Steve\.cache\cretas\release-reports\cretas-1787504466-41.json` 为 `deployed`。Java/Web 均复用可信 manifest，生产 upstream 已切至 `47.100.235.168:10010`；`cretas-backend` 启动于 `2026-08-24 01:01:45 CST`，JAR mtime 为 `2026-08-24 01:01:34.824708980 +0800`，进程晚于制品。直连 health 为 `UP`，Web HTTP 200 且线上 index SHA-256 为 `44ef691e6dab9f166ebee1774468115927e0be1f83c4a7623f677bf9a89e0296`。Flyway `20261030.20` 与 `20261030.21` 均成功。
- **生产只读验收**：F006 质检角色价格掩码通过，批次名称/数量可见且 price/cost/amount 键均不可见；租户隔离通过。生产只读 harness 的 blocked mutation attempts 与 actual business writes 均为 0。部分通用菜单爬虫因移动端角色落点和首屏渲染时机标记为工具 `UNVERIFIED`，未当作产品失败。
- **F006 写入型 E2E**：每次切换角色均使用新的浏览器上下文重新登录并证明 `factoryId=F006`。采购单 `PO-20260824-0001` 完成收货并显示 `COMPLETED/已收货`，AP `1784106f-1118-493c-979c-835163183206` 已核销为 `PAID`；销售单 `SO-20260824-0003` 完成生产、结单、RN 成品入库与 RN 质检，生产结单 `6505ded4-373a-4f7c-a251-487058a2d709` 为 `POSTED`、质检 `f0ed1469-fecc-420f-b58a-18ae6d4656bf` 为 `PASS`。销售 Web 一次性全量发货自动生成唯一子单 `DLV-20260824-4489-01`，FIFO 批次分配后由仓管 RN 确认为 `SHIPPED`；系统随后仅生成 1 张自动开票记录 `INV-20260824-ABD9413F80`，已审核并开具为 `ISSUED`，收款 `PAY-20260824-0016` 已确认为 `VERIFIED`。生产数据库最终回读采购单、销售单均为 `COMPLETED`，自动发票计数精确为 1。
- **数据收尾**：两张无可生产/脚本不兼容的失败销售草稿、其一失败生产计划/批次及对应 2 条审批实例和 1 条生产 Workflow 实例共 7 条记录已按 F006 + 精确实体 ID 做可恢复软删除，回读剩余 0。成功的采购、库存、生产、发货、发票与收付款凭证属于本次完整审计链，按财务/库存可追溯要求保留，不做破坏性回滚。
- **scope 释放**：父任务已从 ACTIVE 移除；仓储 RN、采购/销售/财务、Workflow、迁移、测试和发布 scope 全部释放。实现、测试、commit、合并、部署与上述生产验收均已分别取得证据。
