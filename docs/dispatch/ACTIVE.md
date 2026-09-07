# Dispatch 台账 — ACTIVE
**最后更新**：2026-09-07 11:10（Steve 四条裁定落地：业务编号可重用 / 工号不管 / 权限读不到要报错 / 历史单据指向已删合法；#720/#722/#723/#724 合并；🔴 登记 9 张此前只存在于对话里的票，含「建不出新库」与根治方案）。此前：2026-09-07 10:25（发 50 上线；#682/#690/#692/#693/#695/#697/#698/#699/#701/#702/#703/#704/#705/#706/#707/#708/#709/#711/#712/#713/#715/#716/#717/#718/#719 合并；🔴 新票 CI-GATE-MERGES-A-STALE-BASE-SHA：闸合进的是 payload 里那个**过期** base.sha，17 秒竞态）。此前：2026-09-07 01:50（发 49 = #683 上线；#681/#685/#686/#687/#688/#689 合并；#682 终审过等发 50；#678 宿主栈拿到行为证明）。

**当前真值**：`origin/main` 为代码与合并真值；本文件只记在飞任务的一行摘要，由 `scripts/ci/check_dispatch_active.py` 钉住（每条 ≤ 300 字 / 全文 ≤ 20 KB）。条目全文与增量日志写 `docs/dispatch/archive/`，最近三份：[2026-09-06-active-organizer-log.md](archive/2026-09-06-active-organizer-log.md)、[2026-09-05-active-entries.md](archive/2026-09-05-active-entries.md)、[2026-09-05-active-organizer-log.md](archive/2026-09-05-active-organizer-log.md)；更早的按日期在同目录下查，⛔ 不在本文件维护链接清单（那份清单曾占本文件 2.6 KB，而它每 session 都被加载）。

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
- `QA-WAREHOUSE-STORAGE-UI-VERIFY-20260825` — `review` — Owner: `claude-code/warehouse-storage-ui-verify` — 第一批 PR #87 已部署复验；第二批（`codex/claude-warehouse-approval-closeout-20260826`）：App 端仓库地图入口、数量审批走通（凭证发号器无类型参数第 5 次必炸已修）、位置脏值清洗。未决：`ST-202608-C20566F3` 待上线后点「应用差异」。
- `LSM-WALKTHROUGH-ORGANIZER-LINE-20260902` — `in-progress` — Owner: `liushanmen-organizer-handoff-fa5fb5-0c` — 生产 = main（发 49 = #683）；OTA 推 #687/#688（标记 `common:pleaseSelect` 基线 0）。待 owner：lsm001 登权限矩阵页、zhipu 余额、DeepSeek key、prod 切 validate。
- `YIELD-STANDARD-LINE-20260905` — `blocked` — Owner: 无（f006 收工） — #594/#599/#600/#601 + **#612（发 36）** 已上线：头级出成率现在真的生效（此前被行级 100.00 哨兵门拦死，#599 三周一次没算过）。剩下卡数据：六膳门 8 配方出成率 0/8（张权填，填了即生效）、主料份额 1/12。F006 黄油鸡 100.00 已由迁移置 NULL，回滚项撤销。
- `OPS-CRON-ALERT-FILES-HAVE-NO-CONSUMER-20260906` — `blocked` — Owner: 无（owner 2026-09-06 裁定**暂不做, 留票**） — 47 上 13 个 cron 脚本写 11 个 `*alert*` 文件零消费方；孤儿日扫 8-21 首跑就报 3262 行无人读 16 天。推荐渠道=企微/钉钉机器人日 digest（只在 rc≠0 发），等 owner 给 webhook 再派。
- `ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906` — `in-progress` — Owner: Line A — #672 发 48、#683（登录路径一次预取两张 map；hasPermission 逐字未动）发 49 上线，运行 jar 有 `PermissionServiceImpl$LayerSnapshot`（阴性对照 0）。剩：lsm001 登页面 200；测试环境真登录读 modulePermissions 在跑。🔒 权限。
- `ARCH-MODULE-LISTS-CONVERGENCE-20260906` — `in-progress` — Owner: Line A — A #690 / B #695 / D #715 已合，棘轮 27→21→**19**（#703）。✅ 刀 C 解冻，Steve 授权 organizer 代定：⛔ 不静默兜底成零权限（那是假数据，与「确实无权限」不可区分，违核心原则 1）⇒ 改三态（读到 / **读不到显式报错** / 确实无权限）；兜底矩阵仍从 L1 快照生成但只作离线默认，⛔ 不冒充实时权限。
- `OPS-PROD-VALIDATE-FEASIBILITY-20260906` — `blocked` — Owner: 等 owner 裁定 — #669 决定书已合：发 47 后 prod 同款 jar 在 prod schema 副本零 fixture 起 validate ⇒ STARTED 71s、剩余失败 0；阳性对照删列即点名；none/validate 耗时无差。推荐切（先把 smartbi 单元写死的 `hbm2ddl.auto=update` 改 none，再一行 property 随发布切）。
- `ARCH-SQLDELETE-RATCHET-BATCHES-20260906` — `in-progress` — Owner: Line B — 批 1 #682 / 批 2 #701 / @Version #693 / 批 3-A #712 / #716 / 扫描根闸 #711 / 12 实体去全量唯一键 #717+#719 / 删用户级联 #710 全部已合。批 3-B（11 个实体）在做。✅ Steve 裁定「业务编号删掉后可重用」⇒ 部分唯一索引是唯一正解，实体全量声明一律摘；`employee_code` 自动生成、不管，只摘声明。🔒 实体。
- `UX-ALERT-ALERT-NOOP-ON-WEB-20260906` — `review` — Owner: Line A — #678 + #687 + #697（QIScan testID 漏进 main 的标记 / 清完 21 个未用 Alert import / 做成闸）已合。🔴 宿主栈行为证明拿到（Expo Web × `f006_warehouse_mgr`）：正向与「离开自带宿主屏后再触发」都 0→1，变异回单槽后回归红。剩：Snackbar 方案待定。
- `BUG-I18N-KEYS-LEAK-CUSTOMER-CREATE-DIALOG-20260906` — `review` — Owner: Line A — #688 已合（8 处泄漏：`useTranslation('management')` 而键写 `common.xxx`；补 modal 节 + 5 键 zh/en 双语；单屏键存在闸 4/4，变异红）。待 OTA 送达后真机复看。
- `OPS-CRON-SCRIPTS-PATH-AUDIT-20260906` — `in-progress` — Owner: Line C — 批 1–3 + canary + #679 已合装；#681 alert-digest 骨架（14 组测试 / 9 种 rc=2 / 游标只在送达后前进）与 #686（用例级 `# SCRIPT_TEST_TIMEOUT`）已合，#681 按 `# NOT_SCHEDULED` 不装。剩：#679 释放侧后续。🔒 cron。
- `OPS-CI-ARTIFACT-LANE-DEAD-IN-THIS-ORG-20260907` — `blocked` — Owner: Line C — #698 已合：`release-ci-artifact.sh` 默认 repo 指对 + 签不上时立刻退 `rc=3`（⛔ 不复用 2，那是用法错误码）而不是空等 7 分钟。剩余是**能力边界**：私有仓 GitHub 不给 attestation，ECS 按设计拒未签名制品 ⇒ 这条车道在本组织**永远**走不通，只能明说并退 3。⛔ 不放宽 ECS 信任。🔒 deploy。
- `UX-WAREHOUSE-VALIDATION-GUARDS-UNREACHABLE-20260907` — `review` — Owner: Line A — #699 已合：边界不可见的两屏补可见提示，另两屏标注防御性，全 App 普查 37 处上棘轮。待真机复看。
- `BUG-I18N-DUPLICATE-TOP-LEVEL-KEYS-20260907` — `review` — Owner: Line A — #709 已合：既有 i18n 闸修掉一类假阳性（预算 486 → 350），补重复顶层键检测与查表双向自检。⚠️ 该闸此前与另一道同名闸整份重复过一次，Write 返回 `updated` 就是撞名警报。
- `CI-GATE-MERGES-A-STALE-BASE-SHA-20260907` — `queued` — Owner: Line C — 🔴 #707/#713 没修完的一半：闸合进的是 payload 里的 `pull_request.base.sha`，**快照会滞后**。#710 run 34074881917 拿到 `base=f3ba01d172` 而 main 已 `ced5a45397`，全绿而 `--is-ancestor` 判否。抓手是**测试总数 13235 vs 13237**，⛔ 不是 `merged_ok`。建议运行时 fetch 活 tip。
- `OPS-FRESH-DB-CANNOT-BE-BUILT-20260907` — `in-progress` — Owner: Line C — 🔴 从零建不出库：Flyway 死在第 267 条（加列早于建表 152 位）。⇒ 新测试环境 / 灾备 / 新租户库今天一个都建不出。⛔ 不逐个修，先一趟 continue-on-error 量全深度。详情 [archive](archive/2026-09-07-schema-parity-and-ci-gates.md#ops-fresh-db-cannot-be-built-20260907)。
- `OPS-SCHEMA-PARITY-ROOT-FIX-20260907` — `queued` — Owner: Line C — 根治（Steve 指示）：从迁移建库 → 导 schema → 与 checked-in prod 快照 diff。🔴 首跑必须红且可枚举，开局全绿 = 仪器坏了。依赖 FRESH-DB。详情 [archive](archive/2026-09-07-schema-parity-and-ci-gates.md#ops-schema-parity-root-fix-20260907)。
- `CI-GATE-SCANS-DEAD-MIGRATION-DIRS-20260907` — `queued` — Owner: Line C — 🔴 P0：那道闸扫整个 `db/` 而 Flyway 只跑 `db/flyway/`；3 个部分唯一索引名只在死目录里 ⇒ 闸说「有」而新库拿不到。详情 [archive](archive/2026-09-07-schema-parity-and-ci-gates.md#ci-gate-scans-dead-migration-dirs-20260907)。
- `CI-GUARDED-PATHS-MUST-BE-DECLARED-20260907` — `queued` — Owner: Line C — 「被断言的东西不在触发面里」今天 4 次。⛔ 不反解（第 3 例根本不是 workflow）：用 `# GUARDS=<glob>` 声明。详情 [archive](archive/2026-09-07-schema-parity-and-ci-gates.md#ci-guarded-paths-must-be-declared-20260907)。
- `CI-NINE-OPTIN-PG-TESTS-RUN-NOWHERE-20260907` — `queued` — Owner: Line C — 15 个 opt-in 真 PG 测试只有 6 个被 `java-gate-pr.yml` 点名，其余 9 个任何 CI 都不跑。⚠️ 障碍**不是**「自建 runner 没 Docker」——那条车道用 runner 上原生 homebrew postgresql@17 ⇒ 接进来只是加名字。⚠️ 接之前每个先答「它当初为什么是 opt-in」，耗时长的进 PG 车道而非 fast-contracts。
- `CI-NO-GATE-GUARDS-SUITE-SIZE-20260907` — `queued` — Owner: Line C — 本仓无任何闸守套件规模（`Tests run` 只在日志里，从未被断言）。守的是「测试静默不跑」族（选择器 / profile / 聚合模块 / 批量 `@Disabled`），#722 三读数对这一族完全不可见。⛔ 不写死 `>= N`（并行线会天天误报）：只在下降超阈值时红 + 无条件打出当次数。⚠️ 抓不到「车道整个 skip」，那一半归 GUARDS 票。
- `OPS-DECISION-GRADE-PROBES-MUST-BE-COMMITTED-20260907` — `queued` — Owner: Line C — 产出决策数字的普查脚本不在版本控制里（prod-only 索引那次是临时脚本，`git grep` 找不到），⇒ 它的 v1（名字出现过）/ v2（区分 CREATE 与 DROP，漏 RENAME TO）各错一次，而下一个人会照 v1 重写。要求：凡产出进决策/PR/台账的读数，脚本必须进仓并带阳性对照。
- `OPS-BARE-FK-COLUMNS-POINT-AT-SOFT-DELETED-ROWS-20260907` — `queued` — Owner: Line C — 1064 个裸 `_id` 列（无 FK），319 对父子非空中 20 对指向已软删父行、10 对子行仍活。✅ Steve 裁定（organizer 代定）：指向已软删父行**合法，⛔ 不告警**；缺陷是取名字被 `@Where` 过滤成空白 ⇒ 显示路径要能读已删父行，取不到显示「已删除」。收窄为：10 对逐对判「显示 vs 查当前」。
- `OPS-PROD-ONLY-PARTIAL-INDEXES-20260907` — `review` — Owner: Line C — PR #725：`V20261101_39` 把两条只活在 prod 的部分唯一索引补进迁移；真 pg17 上 A/B/C/D 四态跑过。详情 [archive](archive/2026-09-07-schema-parity-and-ci-gates.md#ops-prod-only-partial-indexes-20260907)。

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
