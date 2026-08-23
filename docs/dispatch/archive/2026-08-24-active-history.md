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
