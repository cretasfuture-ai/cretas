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

- **合并真值**：PR #17 已 squash merge为应用发布提交 `96175083beba3570eeeb35d265f182a67a2b0a77`；生产 E2E 与台账收尾随后由 PR #18 合入。最终 exact-main 候选完成 Java 29 个显式测试类（148 tests，0 failure / 0 error；9 个 opt-in PostgreSQL 用例在独立真实 PostgreSQL 生命周期通过）、Web 生产构建、Web 421 files / 3439 tests（5 skipped）、RN 6 suites / 52 tests、权限漂移基线 4 / 当前 4 / 新增 0。
- **部署真值**：统一发布回执 `C:\Users\Steve\.cache\cretas\release-reports\cretas-1787504466-41.json` 为 `deployed`。Java/Web 均复用可信 manifest，生产 upstream 已切至 `47.100.235.168:10010`；`cretas-backend` 启动于 `2026-08-24 01:01:45 CST`，JAR mtime 为 `2026-08-24 01:01:34.824708980 +0800`，进程晚于制品。直连 health 为 `UP`，Web HTTP 200 且线上 index SHA-256 为 `44ef691e6dab9f166ebee1774468115927e0be1f83c4a7623f677bf9a89e0296`。Flyway `20261030.20` 与 `20261030.21` 均成功。
- **生产只读验收**：F006 质检角色价格掩码通过，批次名称/数量可见且 price/cost/amount 键均不可见；租户隔离通过。生产只读 harness 的 blocked mutation attempts 与 actual business writes 均为 0。部分通用菜单爬虫因移动端角色落点和首屏渲染时机标记为工具 `UNVERIFIED`，未当作产品失败。
- **F006 写入型 E2E**：每次切换角色均使用新的浏览器上下文重新登录并证明 `factoryId=F006`。采购单 `PO-20260824-0001` 完成收货并显示 `COMPLETED/已收货`，AP `1784106f-1118-493c-979c-835163183206` 已核销为 `PAID`；销售单 `SO-20260824-0003` 完成生产、结单、RN 成品入库与 RN 质检，生产结单 `6505ded4-373a-4f7c-a251-487058a2d709` 为 `POSTED`、质检 `f0ed1469-fecc-420f-b58a-18ae6d4656bf` 为 `PASS`。销售 Web 一次性全量发货自动生成唯一子单 `DLV-20260824-4489-01`，FIFO 批次分配后由仓管 RN 确认为 `SHIPPED`；系统随后仅生成 1 张自动开票记录 `INV-20260824-ABD9413F80`，已审核并开具为 `ISSUED`，收款 `PAY-20260824-0016` 已确认为 `VERIFIED`。生产数据库最终回读采购单、销售单均为 `COMPLETED`，自动发票计数精确为 1。
- **数据收尾**：两张无可生产/脚本不兼容的失败销售草稿、其一失败生产计划/批次及对应 2 条审批实例和 1 条生产 Workflow 实例共 7 条记录已按 F006 + 精确实体 ID 做可恢复软删除，回读剩余 0。成功的采购、库存、生产、发货、发票与收付款凭证属于本次完整审计链，按财务/库存可追溯要求保留，不做破坏性回滚。
- **scope 释放**：父任务已从 ACTIVE 移除；仓储 RN、采购/销售/财务、Workflow、迁移、测试和发布 scope 全部释放。实现、测试、commit、合并、部署与上述生产验收均已分别取得证据。

## `SOP-RAG-SYNC-20260824` — `merged`

- **独立判定**：从上次运行时间有界审计新公司仓库的 14 个 `origin/main` commit；旧基线 SHA 与当前远端无共同祖先。工厂线因仓管四入口/真实采购收货、销售母单与子发运、自动开票、Workflow 自动分叉和活动生产门禁发生语义变化；餐饮 tab、三份已注册文档和 MANUAL_SOURCES 均无变化，独立判定为 no-op。
- **实现与合并**：PR #20 由候选 `401c5defcae05a2d3ea1b4fa6b7dbde98c64ef8e` squash merge 到 `897a55cf83ba8db86812729cb6e1be53dd55cabb`。工厂在线 SOP、唯一 F006 KB source、共享 AI Assist 的工厂建议和确定性回答合同完成同步；餐饮文件未修改。目标 pytest 132 项、Python compile、5 个 HTML parse、2 个内联 JavaScript parse、diff check、Secret gate 和 Web Admin gate 均通过。
- **生产同步**：Python 从 clean exact main 发布并于 `2026-08-24 09:43:44 CST` 启动；migration dry-run 为 142 已应用、0 待执行。共享 Web 通过可信 manifest 原子发布，回滚点 `web-admin.bak.20260824_094612`；工厂 SOP 回滚点 `/www/wwwroot/lsmsop/index.html.bak.20260824_094711`。AI Assist / 工厂 SOP / 餐饮 SOP 线上与仓库 SHA-256 分别为 `520de2a90bcca729fa7b560b2b66556bc3bde3483dee8cfea6acdb6d1ad565a4`、`a1bfc87c7a8b80a4032a981dfe4f272c252be515d84e64ae50fca49151afa8a8`、`e9e970dda61a87a85701ae9b14ef216b4e2c3e3a44e5b9f92d549e8e37488acd`。
- **RAG 与回答验收**：仅 `f006-production-full-chain-sop.md` 原子重建为 76 块（原 75），`.NEW=0`、非 canonical 工厂块为 0；餐饮三 source 保持 87 / 250 / 176 块及原创建时间。工厂 6 个真实问答覆盖固定 BOM 顺序、RN、销售/发运/开票和 Workflow，全部只引用 F006 source；餐饮固定毛利与导览 canary 2/2 通过且只引用餐饮注册 source。Python、Embedding、PostgreSQL、Redis 健康，生产 ERP 业务写入为 0。
- **scope 释放**：任务已从 ACTIVE 移除；AI Assist 工厂 tab、工厂 SOP、F006 KB、回答合同与测试 scope 全部释放。分支和 worktree 按规则保留，未清理。

## `BUG-F006-WORKFLOW-EDITOR-EDITABILITY-20260824` — `review complete`

- **业务语义**：用户始终管理当前配置，不再手工“另存独立版本”。保存后，新建计划以及所有尚未开始报工的既有计划（包括已锁定计划）自动跟随当前配置；已经报工、扣料、产出或结算的计划继续固定旧版本。只有仍被已开始报工计划绑定的旧版本才进入“生产版本记录”，并展示实际绑定的生产计划。
- **实现与验证**：Web 已移除独立版本入口并呈现当前配置、自动应用结果和真实计划绑定；Java 在同一事务内发布新版本并重钉未报工计划，保留已开工快照，Repository 新查询通过真实 JPA 启动门禁。Web 4 files / 74 tests、typecheck/build、Java 58 tests 均通过；隔离 PostgreSQL Workflow 集成测试 4/4 通过且 0 skipped；权限漂移基线 4 / 当前 4 / 新增 0。
- **候选制品**：统一 build 回执 `C:\Users\Steve\.cache\cretas\release-reports\cretas-1787539339-5836.json` 为 `built`；Java/Web 各构建一次，Java SHA-256 `22e64b727c6f981473c081874066240abc70586bfca216f7b09aaad5463ec903`，Web archive SHA-256 `2362384b54463fe72e72849aec01fff0064cbdb7526f6f083bf386724fa73125`。Java 仅预上传，未安装、未重启、未切流。
- **scope 释放**：实现、目标验证、最终审查和候选制品均完成，任务已从 ACTIVE 移除并释放 Workflow editor、Java Workflow/ProductionPlan 及对应测试 scope。PR/远端合并、生产部署和 F006 用户验收仍是后续独立事实，须以各自回执为准。

## `FIX-F006-WORKFLOW-EDITOR-RELEASE-GATES-20260824` — `review complete`

- **发布阻断修复**：销售、采购单据追踪端点的只读权限已与各自详情端点逐字对齐，补齐 `finance:read`，未扩大任何写权限；V21 Workflow 迁移源码闸改为 CI 可选择的 `*ContractTest` 命名，迁移 SQL 未修改。
- **权限边界**：四个前端承载点保持原边界，财务仍无销售/采购菜单或跨模块路由；后端 `@RequirePermission` 已对齐，追踪控制器没有额外 `@RequireModule` 串联误拦。权限漂移基线 4 / 当前 4 / 新增 0。
- **验证与候选制品**：三类发布阻断契约 7 tests 通过；统一最终生命周期共 65 tests，0 failure / 0 error / 0 skipped，隔离 PostgreSQL Workflow 4/4 真实执行。回执 `C:\Users\Steve\.cache\cretas\release-reports\cretas-1787541774-7002.json` 为 `built`，JAR SHA-256 `32e7fefc405d7186d709fe8815a1ce23d1dff69c8f2c11820112e5c5cb686d3d`，仅预上传、未安装/重启/切流。
- **scope 释放**：任务已从 ACTIVE 移除，追踪只读权限、迁移源码闸与对应测试 scope 释放；PR/远端合并、生产部署和 F006 验收继续按独立事实记录。

## `BUG-RELEASE-SSH-COMMENT-SUBSTITUTION-20260824` — `review complete`

- **发现与修复**：最新 exact-main 发布日志暴露 `deploy-backend.sh` 的双引号 SSH 远端安装字符串中含反引号注释；Bash 在发送 SSH 前执行了本机 `ls -t` 命令替换，把仓库文件名拼进远端脚本。删除注释中的命令替换语法，未修改任何发布行为、远端命令、业务代码或生产配置。
- **回归门禁**：新增静态测试，扫描双引号 SSH 段内的注释反引号并 fail closed；`bash -n scripts/deploy/deploy-backend.sh`、新契约测试和 `git diff --check` 均通过。
- **状态边界**：本修复只完成实现与目标验证，未触发新的生产部署；scope 已从 ACTIVE 移除并释放。后续 PR/远端合并须以独立回执为准。
