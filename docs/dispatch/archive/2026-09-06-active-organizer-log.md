# 2026-09-06 organizer 日志（LIUSHANMEN 线）

> ACTIVE.md 只留一行摘要；本文件是当日全文。

## 00:26 f006 收工交接 + 路由换轨（原 #602 的 `###` 段，因台账格式闸迁至此）

### 📌 2026-09-06 00:26 增量（系统时钟）· f006 执行 chat 收工交接（标准出成率线）+ 模型路由换轨

**f006 收工原因**：长会话症状（读到落后 600 commit 的旧文件、时钟差一小时）。未完项由本台账承接，**不另写交接文档**（多一份就会漂）。

**① 已上线（merge + 部署 + 硬约束 2 核过；我核了四票都在 main）**：#594 标准出成率归人管（删自动回写 + 权限 9 角色→超管 + 新页「生产管理→标准出成率」）/ #599 建计划按主料份额算投入量（抽出 `PlanInputShareResolver`，领料与缺料预警共用）/ #600 工艺/BOM 导出 Excel / #601 热修该页角色判断读了不存在的 `user?.role`。

**② 卡在「没人填数据」，不是代码**（09-06 00:xx 实时读数）：六膳门 8 个 ACTIVE 配方出成率 **0/8**（张权按研发数据填）；12 个有工艺成品主料份额 **1/12**（画布 INPUT 端口配）；F006 黄油鸡出成率仍 **100.00**（需 F006 超管账号回滚到 93.75，当前只有 LIUSHANMEN 账号）。⇒ #599 现在能算出数的成品 **1 个**，机制不空转（算不出会明说）。
🔴 **回滚判据**：不是「库里是不是 93.75」，是**下次 F006 结单之后它还是不是 93.75**。对照：`bom_yield_suggestions` 现 12 条 APPLIED、最近 09-01 03:12:31、之后未涨；结单后应新增 **PENDING** 而非 APPLIED。
📌 缺陷形状（f006 发现）：自学习把值推到 `100.00`，而 `100.00` 正是 `resolveEffectiveProductYieldRate` /`isMainMaterialCandidate` 里「视为未配置」的哨兵 ⇒ **合法取值兼作哨兵，任何能到达它的过程都会静默关掉依赖它的机制**。同形多半不止一处，待扫。

**③ owner 已定方向、未做**：跨计划半成品抵扣 + 预扣占住可用量（「天天干」，两者同批不拆）；辅料成本标「按配比估算」。

**④ 登记未做**：人数默认 1（`headcount` NOT NULL，改 schema + 动成本口径 → 单独一票）/ 研发定版转 SKU（仅注释提到，是功能不是修复）/ 另 3 处角色字段 bug `CalibrationListView:44` `CalibrationDashboard:38` `hr/employees/list:26`（读 `user?.role|roleCode` 恒 false；改了会让平台管理员突然看到新入口，由各功能负责人定；钉在 `yieldStandardUsesNormalizedRole.source.spec.ts`）/ 质检链 `quality_check_items` 0 行从没配过（数据/流程）/ 审批按标准价放行（会上是**争议**非决定，owner 不点头不碰）。

**⑤ 模型路由换轨（`8b08945b51`，已核 frontmatter）**：organizer 跑 Fable 5.1；in-harness 三车道一律 Opus —— executor `inherit→opus`/effort `xhigh→high`、probe `sonnet→opus`/high、recon `sonnet→opus`/medium。⚠️ 原 `executor: inherit` 意味着 Fable 派出的 executor 也是 Fable 且不报错，已钉死。effort **只能在 frontmatter 设**（Agent 调用无该参数）；model 可按次传（调用参数 > frontmatter > 环境变量 > 会话）。**effort 的边界**：09-05 所有失误无一是「想得不够深」，全是「量错了东西」——提高 effort 抓不到这一类。


## 2026-09-06 00:34 organizer 裁定：释放两把陈旧 scope 锁

`F006-R2-WORKFLOW-FIRST-BOM-GATE`（Base `fee2509fa6`，08-25）与 `BUG-F006-PRODUCTION-COST-CLOSURE-001`（Base `7d51fe2259`，08-25）的 Owner `claude-code/f006-remaining-closure` 已无对应 worktree/分支，Base 落后 origin/main 572–573 commit；且 09-04/05 已有 #576/#580/#587 合入其锁定区域。⇒ 两条改 `blocked`，scope 锁释放；续做须重登 Base。

## 2026-09-06 00:34 派发（三车道全 Opus，effort 按 frontmatter：executor high / probe high / recon medium）

| ID | 车道 | 目标 | 🔒 |
|---|---|---|---|
| `PLAN-CROSS-SEMI-OFFSET-RESERVE-20260906` | probe（先）→ executor | 跨计划半成品抵扣 + 预扣占住可用量，同批不拆 | 🔒 库存写路径 |
| `COST-AUX-ESTIMATED-LABEL-20260906` | executor | 辅料未记实耗时按配比估算并标「按配比估算」，不改报工 UI | 🔒 成本口径 |
| `RECON-LEGAL-VALUE-SENTINEL-20260906` | recon | 「合法取值兼作哨兵」同形扫描，登记不改 | — |

⚠️ 待办：`.claude/skills/organizer/SKILL.md` 第 3 行仍写「Sonnet 5（执行）→ Opus 5（判断）」，与 09-06 全 Opus 裁定不一致，下次 fastlane 一并订正。


## 2026-09-06 01:09 probe 回报 ③ `BUG-SFI-LEDGER-DUP-OUT` → 订正为 `BUG-SFI-REVERSE-CLASSIFIER-LOCKOUT`

- **不存在重复扣减。** 权威不变式（`WipLedgerEntries:289-297` / `ReportReversalServiceImpl:2164-2170`）：`produced = ΣIN + Σ(REVERSE<0)`，`consumed = Σ|OUT| − Σ(REVERSE>0)`。按此重算：LIUSHANMEN 15/15 对平；F006 10/14 + 1 无台账 + 3 `PRODUCED_OFF`（produced 早于台账，`WipLedgerEntries:92-93` 已登记）。
- 370/371 时间线：02:55 IN → 02:57 OUT → 03:14 整单冲销 log 18（只冲 IN 腿，report_id 未盖章：当时代码在 #391 之前）→ 10:41 重提 IN（`balance_after=0`，操作员那两小时看到可用 0）→ 11:59 合入 `e774ebf298` (#391) → 12:23 第二遍补冲 OUT 腿 → 12:59 重提 OUT。`reverted_txn_ids=[141,142,143,145,146]` 完整。
- OUT 写者普查 5 处（`consumeSourceWip` 21 行 / `deductForSecondaryPlan` 2 行 / clerk / settlement / stocktake 写 ADJUST）= prod 23 条全部归属；余额与台账在同一 `@Transactional` 相邻语句 + 悲观锁，**无「余额一次、台账两次」的路径**。
- ⛔ 唯一键 `(sfi_id, source_ref)` 是错修法：F006 sfi 342 四条 OUT 同 `TASK-1789` 各 −60、`consumed=240` 对平，是合法跨天重复领用。
- **真缺陷**：`isExternalConsumptionReverse`（`:1179-1187`）按 `report_id IS NULL` + `reversal-log:` 前缀分类、**不看符号** ⇒ 141/142/143（负号）被判外部回流 ⇒ `validateFullWipLedgersBeforeMutation:1279` 抛 `PARTIAL_COMPENSATION`。锁住：LSM 370/371/372（B10888-S1/S2/S3）、378（`interim-settle-reverse-in:` 前缀不在白名单 :1300）、F006 343/344。`WIP_LEDGER_INCOMPLETE` 另锁 F006 331/342/343/344。
- `ClerkLedgerPairingContractTest` 抓不到：Mockito 手工种子、只管 clerk 家族、且其不变式（:121）**把 REVERSE 过滤掉** —— 对健康行 372 会误红。正确形状已在 `ReverseSignSemanticsContractTest` / `replayMovingAverage`，缺的是对**存量数据**断言 + 「REVERSE 符号与分类一致」的闸。
- 修法选项：A 分类器加符号（必做）；B 回填 141/142/143 的 report_id=23919/23921/23923（解锁 LSM 三行；迁移，`_33` 之后）；C 放宽白名单（形态 E 反例，不选）；D 三态对账闸（必做）。378 与 F006 两行待定（F006 可删）。
- ⚠️ 前次 probe 的「12/15 对平」在两种不变式下都不复现（朴素 11/15，正确 15/15）——两次仪器口径都要记。


## 2026-09-06 01:24 小米真机 OTA 读数（probe via adb，M2102K1AC / Android 14 / 1.0.4(15)）

- 触发机制（源码）：`App.tsx` → `scheduleBackgroundOtaCheck()`，启动与回前台各查一次（5 分钟节流）；有新版则 Alert「发现新版本 / 立即更新」，**`reloadAsync` 只在人点「立即更新」后执行**；手动入口只在 AboutScreen（登录后）。⇒ 包不会静默落地。
- 开 App 前：进程不在、logcat 无任何 expo-updates 行（`grep -F` 修正后的读数；首轮 `"expo.\?updates"` 把 `?` 当字面量，假 0 已剔除）。
- 01:05 启动 → 弹「发现新版本」（**证书修好 14 小时后设备仍是 9-01 旧版**）→ probe 点「立即更新」→ `Updates state change: Restart … latestManifest.id=98c3b3dd… launchAsset …/production/1788544899864/…1f13e55365d571065c625c8933cedfa9.hbc` → 冷启动 `Check → CheckCompleteUnavailable`，无弹框。标记：`98c3b3dd` 2 / `1788544899864` 2 / `3b60c7ef` **0**。APK 未动（lastUpdateTime 08-29 不变）。`run-as` 不可用（release 不可调试）——这是「量不到」不是「没有」。
- 行为层：App 登出，记住的账号全是 F006（质检/仓管/操作员），**需 owner 用六膳门能看销售订单的账号登录**后读 `DLV-20260904-4086` 对应 SO 的按钮。probe 未输密码、未点账号。
- ⇒ **判定拆两条**：包层 DELIVERED（此设备）；行为层 UNDECIDABLE（待登录）。**全部其他设备仍旧版**，直到各自有人开 App 并点更新。⇒ 待 owner 决定是否改为下次启动自动应用。

## 2026-09-06 01:24 recon：权限管理现状（owner 要「按侧边栏模块勾角色」的总页）

- 生效顺序实为 **4 层**：L4 用户级 GRANT/DENY → L2 `factory_module_configs.role_module_override`（JSONB，哨兵行 `__permissions__`）→ L1 `platform_role_permissions`（role×module，全局无工厂维度）→ Java `PERMISSION_MATRIX` 兜底。`checkAction` 的 `default` 分支按最严（要 `read_write`）；`w` 级过不了任何读检查（#812/#963 根因）。
- **三族门 AND**：`@RequirePermission`（方法注解**替换**类注解，不取交）、`@RequireModule`（另一码空间，`ProductionModuleRegistry` 15 条映射，未登记的按同名兜底；`finance`/`operations` 就是这么过的）、`@RequireRole`（**331 处/64 文件**，字面角色名单，4 个平台角色旁路）。矩阵管不到后两族。
- 模块清单 **7 份**互不一致：`ALL_MODULES` 18 / L1 seed 17 / 两个写接口 `ALLOWED_MODULES` 22（**缺 `operations`、`restaurantProcurement`**，保存会 IllegalArgument）/ `RoleController.DISPLAY_MODULES` 12（含幻影 `ai`）/ `ProductionModuleRegistry` 15 / `moduleRegistry.ts` 15 / menuConfig。
- 侧边栏 19 个顶层 ↔ 13 个模块码；`/restaurant` 与 AI 工作台按角色名单不按模块；`work_report`/`report`/`operations` 无侧边栏入口而 `operations_coordinator` 唯一写权限就是 `operations`；菜单模块 ≠ 路由模块（`check-menu-permission-drift.py` 就是为此存在）。可见性有 **6 个独立载体** AND（矩阵/roles/factoryType/restaurantRoles/画布禁用模块/演示租户裁剪）+ 路由 `ROLE_PATH_WHITELIST`。
- 现有 UI：L2 矩阵 `ModulePermissionMatrix.vue`（画布编辑器页签，可达）；L4 `UserModuleAccessMatrix.vue`（可达）；**L1 编辑页不存在**（`router/index.ts:1468-1473` 注释、目录空、`updatePlatformPermission` 零调用、`ModulePermissionMatrix.vue:36` 死链 `#/system/role-permissions`）；`/system/roles` 是空壳（编辑按钮「开发中」、14 角色写死、查看权限读的是 `PERMISSION_MATRIX` 而非生效值）；RN 无 UI 且自带第三份矩阵 `permissionHelper.ts:222-271`。
- 矩阵 vs L1 漂移坐实：`finance_manager` 的 sales/production/procurement/rd 矩阵 `read`、L1 `-`（`V20261030_14`）；矩阵缺 `cashier`/`quality_controller`；`restaurant_owner` 矩阵全 rw。**Java 侧无任何闸钉矩阵↔迁移**（test 树 18 处命中全是引用/断言兜底）。仍在读矩阵的生产路径：`getUserPermissions`（AI ToolRbacGuard/ToolPrincipalPolicy）、`getRolePermissions`（RoleController、UserMenuPermissionServiceImpl）、`getAccessibleModules`。
- **方案（给 owner）**：① 复用 `ModulePermissionMatrix.vue` 接 L1 端点建总页、模块清单收敛为一份、修两个写接口、改「角色权限」页显示生效值、加 Java 矩阵↔L1 闸；② 一份注册表同喂侧边栏与后端，标明按角色名单管的分组；③ 收编 330 处 `@RequireRole`。

## 2026-09-06 01:24 `COST-AUX-ESTIMATED-LABEL` executor 被 Opus 403 打断

`Failed to authenticate. API Error: 403 Request not allowed (model claude-opus-5)`，发生在变异测试起步。分支已有 2 commit，无 PR；同时另一条 Opus executor 仍在跑 ⇒ 疑单次/额度型拒绝而非全局。已 SendMessage 续派（先撤未提交的疑似变异）。若再 403 ⇒ 升 owner（Opus 额度/权限），⛔ 不擅自降 Sonnet。


## 2026-09-06 02:43 `COST-AUX-ESTIMATED-LABEL-20260906` 归档 — #608 发 35 上线

原条目：- `COST-AUX-ESTIMATED-LABEL-20260906` — `in-progress` — Owner: executor（organizer 派；01:2x 被 Opus 403 打断，已续派） — 分支 `worktree-agent-aa2581e8120356c3f` 已有 2 commit（主代码 + 16 条测试含 8 阴性对照），未开 PR；worktree 里有一处未提交改动疑为变异，续做时先撤。🔒 成本口径。

- **终审**（🔒 成本口径）：爆炸半径实测 **1 个**已结单计划会从「静默少算」变「诚实空」（上限 5；执行者自报 36 是把未结单也数了，且第二次复核又用了 `material_consumptions.production_plan_id` 这条只填 140/370 的路径——形态 A 两次）；两道门不是串联（门② 只在门① 放行即贡献恰为 0 时可达）；估算基数与主料成本同一份去重并集；`SeasoningRatioContract` 抽取单位集逐条一致、未走 `crossLanguageCode`。
- **需改已补**（`0d85baab9d`）：部分记录（两种调料只记一种）原本静默少算 ⇒ 加非阻断 `AUX_PARTIALLY_RECORDED:<辅料名>`，走独立清单结构上进不了 `missingCostItems`；报工只记总量的批次不打标记（防形态 E 噪音）。变异 M7/M8 先打印产出再红。
- **发 35**：首次 `unified release requires a clean worktree`——部署树里 `frontend/CretasFoodTrace/yarn.lock` 被 09-05 OTA 导出时的 `npm install` 改了。清理规则：先列脏项、只在全部是构建/安装产物时才清、否则停手。重发 `DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS` 1 次、jar 02:40:39 < 启动 02:40:58、`estimateAuxByBomRatio` 5 / `AUX_PARTIALLY_RECORDED` 1 / `SeasoningRatioContract.class` 1 / 假串 0、health 200；web 458 chunk `estimatedAuxNote` 1 / 对照 1 / 假串 0。
- 登记：`ESTIMATED_AUX_RATIO_MISSING` 与 `ESTIMATED_AUX_RAW_BASIS_UNVERIFIABLE` 在 prod 今天触发不了（0 条缺 dosage、126 个原料批次全 kg）；`AUX_PARTIALLY_RECORDED` 当前命中 0（17 个有记录计划辅料全覆盖），守的是缺口不是今天的数据。
- ⚠️ 运维：**从部署树做 OTA 导出会弄脏它**（`npm install` 改 lockfile、`expo export` 落 dist）——下次 OTA 用独立 worktree，或导出后立刻 `git checkout -- yarn.lock && git clean -fd dist`。


## 2026-09-06 02:59 #609 终审（半成品可见性预扣 + 来源状态早校验）

- 🔴 需改：`@Autowired(required=false) @Lazy PendingInterimFeedService` —— 三个测试类全 `MockitoExtension`，字段永远非 null ⇒ 原理上测不出接线失败；prod 今天预扣 0 ⇒「没扣」与「没接上」读数相同；M8 证明环真实存在。⇒ 必须补真实 Spring 上下文断言（`*ContractTest`）。
- 可合：`stockConsumedAtSubmit` drift fix 修的是盘点侧重复减账（与 `InterimSettleServiceImpl:293` / `ProductionPlanServiceImpl:6830` 两条既有跳过对齐，聚合器是第三份），今天 0 行；单位有钉子（盒/kg）、超扣 WARN 点名两数、折算不出标 `pendingFeedUnresolved`；早校验只查 AVAILABLE 不查量——量的防线在 picker，保存点查量会造假拒，接受。
- 非阻塞建议：超扣给 DTO 布尔；结单 409 的 hint 要说清「被别人待扣占掉」。
- 登记：(b) 幽灵预扣 → 开票 `BUG-GHOST-PENDING-FEED-DELETED-PLANS-20260906`；(c) PENDING SP2 计划聚合器看不见 → 已知覆盖缺口，写进特性说明。
- executor 抓到的 CI 盲区：`java-gate-pr.yml` 只跑 `*ContractTest`，`*Test` 类编译不执行（已记 memory）；全量要 `java-full-ready` 标签，本票已打。


## 2026-09-06 03:37 `RECON-LEGAL-VALUE-SENTINEL-20260906` 归档 — #612 发 36 上线

原条目：- `RECON-LEGAL-VALUE-SENTINEL-20260906` — `queued` — Owner: 无（等执行槽位） — **坐实**：prod 144 条配方行 yield_rate 全 100.00 ⇒ `mainMaterialCount=0` ⇒ 解析器读头级前返回 null 且无日志；#599 叠在这道门上 ⇒ 上线三小时一次没算出数。修法已定：头级为唯一来源（NULL=未填，100 合法）、拆主料计数门（主料按端口份额）、删读 APPLIED 建议分支、迁移置空 F006 那条 SYSTEM 100.00、放宽 `chk_br_yield`。🔒

- **终审可合**：迁移谓词两侧证据（SYSTEM APPLIED 100.00 且无 `changed_by` 非空的变更记录），人手优先、失败方向是少清；prod 实测只动 1 行；`f006_clear_71` 影子 schema 不受影响（Flyway 走 `public`）；留 10 处 `getYieldRate()` 的三个克隆点是纯搬运无分支；`calculateRuntimeRequiredQuantity` 双门**删了**而非登记（折算收归 `spreadToPorts` 一处）；静默 null 补了按 (factoryId, productTypeId) 节流 10 分钟的 INFO；回滚判据测试断言真实 `spreadToPorts` 产出（100 ÷ 0.9375 = 106.666667）带空表阳性对照。
- **终审留的一条**：「标准出成率」页面建议列会不会变空——recon（无 Bash）追链：列由 `recalculatePreview` 从完工批次 `cumulativeYieldRate` 中位数算出，链路五个仓库均不含 `BomYieldSuggestion`；被删分支的仓库方法在 `src/main` 唯一调用点就是解析器。我补跑 diff：#612 碰了 `BomYieldEstimateServiceImpl`(+46) 与 `YieldStandardView.vue`(+33/−7)，hunk 全是**写入侧**（`assertYieldRateInRange` 区间守卫、审计文案「系统更新」→「人工设置」、前端上限 100→300），不碰 preview ⇒ 列不受影响。
- ⚠️ **我的仪器错一次**：按 `service/impl/` 路径 diff `ProductionPlanStartValidationTest` 得到空，差点读成「删除没落到位」；实际文件在 `service/production/`，改动 +52/−34，APPLIED 建议的 mock 与「50.00 驱动 requiredQuantity=400」断言整段删除。靠 PR 文件清单交叉核对抓到（形态 A⁴：空读数先问「我看的是不是那个东西」）。
- **发 36**：`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS` 1 次、jar 03:33:43 < 启动 03:34:02、`assertYieldRateInRange` 1 / `YIELD_RATE_MAX_INCLUSIVE` 1 / `V20261101_32` 在 jar 1 / 假串 0、health 200、web 入口 03:30:52。**库内效果**：`flyway 20261101.32 success 03:34:10`；`chk_br_yield = (IS NULL) OR (>0 AND <=300)`；F006 `SOP-20260817-01-黄油鸡` `overall_yield_rate` 100.00 → **NULL**；`bom_recipe_items` 144 条 100.00 未动（设计）。（分布查询一条 SQL 写错 GROUP BY 未取到，决定性读数已齐，未重跑。）
- 登记：`BomYieldSuggestionRepository` 那个 `latestApplied` 方法现 0 个 `src/main` 调用点，仓库保留由契约测试登记；`GET /yield-suggestions` 端点无任何前端调用方（RN/web 均 0）。
- CI 盲区第二例：executor 的新闸类名不匹配选择器被 `GateSelectorCoverageContractTest` 抓住（已有元判据），改名；全量 12980/0/0。


## 2026-09-06 03:57 `PLAN-CROSS-SEMI-OFFSET-RESERVE-20260906` 归档 — #609 发 37 上线

原条目：- `PLAN-CROSS-SEMI-OFFSET-RESERVE-20260906` — `review` — Owner: executor（organizer 派） — PR #609 开；终审：设计过，卡一条阻塞——`@Autowired(required=false) @Lazy` 注入靠 Mockito 测不出没接上、prod 预扣读数恰为 0 分不清 ⇒ 补真实 Spring 上下文断言字段非 null。全量套件已打 `java-full-ready` 标签。🔒

- **终审需改已补**（`c75f515dbc`）：`PendingFeedWiringContractTest`（`@SpringBootTest`，真实容器）。⚠️ 执行者用变异 M9（给注入点一个匹配不到 bean 的 `@Qualifier`）证明**我给的判据「断言字段非 null」是恒真式**——`@Lazy` 注入的是代理 `jdk.proxy2.$Proxy433`，目标缺席时字段照样非 null、第一次调用才炸；改成真的调 `pendingSemiFeedSnapshot(...)` 才让 M9 变成点名「问不动这个协作者」的 Failure（已记 memory）。另坐实 `@DataJpaTest` 不加载 `@Service`，不能当接线证据。
- 非阻塞已补：`WipRowDTO.pendingFeedExceedsAvailable`（与 floor 共用同一比较）；结单 409 hint 改为只说下一步动作，⛔ 不断言「被别的计划占掉」（未量到原因，形态 D′）。
- 全量套件（`java-full-ready`）：12967/0/0，四个新 `*ContractTest` 各 4/9/7/6 条实际执行（按类名 grep 核过）。
- **发 37**：`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS` 1 次、jar 03:54:16 < 启动 03:54:33、`pendingFeedExceedsAvailable` 1 / `assertLiveSemiSourceStatus` 1 / `SFI_SOURCE_NOT_AVAILABLE` 1 / 假串 0、health 200。⚠️ prod 今天预扣读数为 0（无未结算 semiFinished 引用行），**「余量没变」不能当验收**；验收靠 F006 形状的 fixture。
- 登记：(c) PENDING SP2 计划对聚合器结构性不可见 —— 已知覆盖缺口，非缺陷；(b) 幽灵预扣已开票 `BUG-GHOST-PENDING-FEED-DELETED-PLANS-20260906`。
- 附：发 37 启动日志两条 `Startup warmup semanticIntentMatcher/intentEmbeddingCache FAILED: Embedding service unavailable`——9090 在线且 active，发 34 也出现过 3 次、35/36 没有 ⇒ 预热先于嵌入客户端就绪的启动竞态，非持续故障，登记不追。


## 2026-09-06 04:02 #615 客户名单门交付（终审中）

- 注解 `{sales:read_write, sales:read, finance:read_write, finance:read}`；`operations_coordinator` L1 已 `sales=r` 故未加 `operations:*`；超管 `PermissionServiceImpl:462` 短路。prod L1 23 活跃角色：**11 过 / 12 挡**（挡：production_manager、workshop_supervisor、operator、yield_operator、quality_inspector/manager、warehouse_manager/worker、procurement_manager、equipment_admin、hr_admin、permission_admin）；L2 覆盖 0 行；`restaurant_*` 通过但只在 MOCK_REST（0 客户）且无客户屏。
- production_manager 后果实测不变（7/20 计划挂客户）：403、下拉空、屏存活（`settleAllIndependently`）、只记日志无 toast。
- 额外：`ToolRbacEnforcer` 加 `customer_list` 同四码（否则问 AI 就能绕过 HTTP 门），parity 测试钉两门同码。变异：去注解 ⇒ javap 见 `RequirePermission` 10→9 ⇒ 7/7 红、production_manager 403→200；去 map 项 ⇒ 常量池 `customer_list` 3→0（对照 `customer_delete` 仍 3）⇒ 2/3 红。
- 两条待裁定：(1)「恰好四类」用权限码表达不出——`department_admin`/`dispatcher`/`viewer` 因 L1 本有 sales 或 finance 读权而过，收窄要改 L1（属未来权限总页的事）；(2) 客户名单 12 条读路径只关 1 条，余下已登记 `SEC-CUSTOMER-ROSTER-REMAINING-PATHS-20260906`。
- 顺手修的 `Promise.all` 同族：`ShipmentManagementScreen.tsx`、web-admin `plans/list.vue`、`system/products/index.vue`。


## 2026-09-06 04:11 `SEC-CUSTOMER-LIST-GATE-20260906` 归档 — #615 发 38 上线

原条目：- `SEC-CUSTOMER-LIST-GATE-20260906` — `review` — Owner: executor（organizer 派） — PR #615：`getCustomerList` 加 sales/finance 四码 + AI 工具 `customer_list` 同码；L1 23 角色 11 过/12 挡。⚠️「恰好四类」权限码表达不出（三个角色因 L1 本有读权而过）；12 条读路径只关 1 条，余下另开票。🔒 终审中。

- **终审可合**：12 条读路径只关 1 条是硬约束 8 本尊，但本票范围正确；三个搭便车角色（viewer 全模块只读设计、dispatcher 有 bug #45 原始理由「审 SO 要看客户」、department_admin 带销售读写）**按权限模型本来就该通过**——「恰好四类」是业务语言，模型粒度是模块，对不齐是表达力问题不是缺陷；AI 门委托 `permissionService.hasAnyPermission`（同一判定链），parity 钉的是两门同码；负向测试钉在真实 L1 角色 production_manager（sales=-、finance=-）；#590 的 `allSettled` 在位未被动坏。
- **下一票必带**（已写进 `SEC-CUSTOMER-ROSTER-REMAINING-PATHS`）：`/active` 的调用方有 `web-admin/src/views/warehouse/material-types/list.vue:272`，注释逐字写「后端另有专门为下拉场景准备的 `/customers/active`（无 DataScope 限制）」——有人特意迁过去绕范围；warehouse_* 角色 sales=-、finance=- ⇒ 照抄四码会打断仓库物料类型页。优先级：`/export`（全表）与 `/{id}/price-memory`（议价史）高于 `/by-industry` 之类。
- **发 38**：`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS` 1 次、jar 04:08:31 < 启动 04:08:50；javap 按方法块：`getCustomerList` 带 `RequirePermission`=True、`createCustomer` True（阳性）、`getActiveCustomers` False（阴性，本票故意未动）；`ToolRbacEnforcer` 常量池 `customer_list` 1；health 200。
- ⚠️ RN 侧改动（`ShipmentManagementScreen` `Promise.all`→`allSettled`、`settleAllIndependently` 注释）**未推 OTA**，与 #590 的 RN 半一起攒着，等 owner 定「OTA 是否改自动应用」后一并推；⛔ OTA 导出用独立 worktree，别弄脏部署树。


## 2026-09-06 05:13 #620 幽灵预扣交付（终审中）

- 谓词按两条盖章路径的真实前置推出：小结 `InterimSettleServiceImpl:115/128`（`@Where` + 拒 COMPLETED/CANCELLED/PENDING_APPROVAL）、结单 `validateSettlementRequest:6206`（只收 IN_PROGRESS/PENDING）。分支：计划缺失/软删 ⇒ 排除 + `orphanRowCount`；CANCELLED ⇒ 排除 + `cancelledRowCount`（`reverseBatchReportsForApprovedCancel` 已回滚，再计=重复占）；COMPLETED/PENDING_APPROVAL ⇒ 保留 + `anomalousTerminalPlanIds` + WARN；状态 NULL ⇒ 保留。在遍历单点做（排除与计数同时发生，⛔ 不用 repo JOIN 静默丢）。
- 普查：`findUnsettledStockFeedRows` 1 调用方改 1；`PendingInterimFeedService` 2 消费方经单点继承；`Snapshot` 构造 3 处改 2 留 1；`DeferredDeductionCompensationContractTest` 未动且绿。五个变异先打印再红（含「保留排除但静默计数器」M5）。47/0/0。
- prod：LSM 56 活行在 5 个硬删计划上、全已结算；**全 prod 唯一一条 `semiFinished:true`（row 1500，`CLK-SEMI-5c711e0b-c76f35be`）正坐在其中一个硬删计划上**（08-29 已结算）⇒ 今天 0 是运气。⚠️ 订正：F006「8 行在软删计划上」自身就是软删行（`@Where` 已排），活行在已删计划上 = 0；但 F006 有 **2 条未结算行挂在 1 个 COMPLETED 计划上**——漏盖章 vs 冲销后重新待结算，两种成因读数相同，PR 选择浮出。
- 登记：删除侧缺口——硬删 `production_plans` 不级联 `process_sheet_rows`；本仓 `@SQLDelete` 软删全局，这 5 个计划为何是硬删、谁在硬删，待查。56 行不清理（全已结算，修后贡献 0，清理纯风险）。


## 2026-09-06 05:40 `BUG-SFI-REVERSE-CLASSIFIER-LOCKOUT-20260906` 归档 — #618 发 39 上线

原条目：- `BUG-SFI-REVERSE-CLASSIFIER-LOCKOUT-20260906` — `review` — Owner: executor（organizer 派） — PR #618：分类器加符号 + `isLegacyInLegCompensation` 按冲销日志认领判遗留补偿；迁移 `_33` 只回填 141/142/143（歧义拒猜，docker 六态）；对账契约测试 + 三态探针（活 prod 29 测/25 对/4 已知差）。REVERSE 读者普查 10 改 2 留 8。🔒 终审中。

- **终审可合**：迁移谓词逐段判（`reverted_txn_ids @> to_jsonb(id)`、`n_logs=1` 歧义拒猜、IN 腿四重约束、`HAVING COUNT(*)=1`、幂等），prod dry-run 恰 3 行；分类器双路径按符号互斥、顺序无关；378 仍 409 是有意克制；四象限「不是替代关系」成立（代码修类、迁移修账）；探针基线按成因非 id。需改两条已补（`44f210c6b5`）：认领日志加 `deleted_at IS NULL`（今天 18 条日志全未软删，零变化；docker 第六态 + 变异 M3）；三份对账口径加可执行对拍（`crossCheckAgainstReplayMovingAverage` 反射调权威 `replayMovingAverage`，变异 C2 红）。
- 🔴 **执行者自抓一个假对拍**：第一版把账面种成正确答案，「两边相等」也可能是「两边都没跑」；改成故意错的种子 `2.50/0.00/2.50`（且须 ≤ ΣIN，否则权威先抛 `WIP_LEDGER_INCOMPLETE`——那个异常反过来证明权威真的执行了）。
- 全量套件 13031/0/0（7m04s），三个新类在 CI 各执行 5/4/6，`XCHECK` 行在 CI 日志里打出来了。
- **发 39**：`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS` 1 次、jar 05:37:20 < 启动 05:37:41、`isLegacyInLegCompensation` 1 / `V20261101_33` 在 jar 1 / 假串 0、health 200。**库内**：`flyway 20261101.33 success 05:37:51`；txn 141→23919 / 142→23921 / 143→23923（恰三行）、145/146 原样、遗留负号无章 REVERSE 剩 **0**、REVERSE 总数 13 不变。
- 登记：378（`interim-settle-reverse-in:`）与 F006 343/344 不动；`WIP_LEDGER_INCOMPLETE` 锁 F006 331/342/343/344 是另一缺陷（盘点 ADJUST）；探针接 cron 时基线按 `NO_LEDGER`/`PRODUCED_OFF` 成因过滤 + 配计数读数，⛔ 不硬编码 id。
- ⚠️ 运维：**子代理共用 organizer 的 scratchpad 目录**——#618 执行者的 `pr_body.md` 被 #620 执行者覆盖（它重新从 GitHub 拉 body 写到唯一命名文件）。以后 brief 里让执行者用带任务 ID 的文件名。


## 2026-09-06 06:01 probe 回报 `ARCH-PRODUCTION-PLAN-HARD-DELETE-PATH`（只读；全部标 [RUN]/[INF]）

- **层①代码**：`ProductionPlan`/`ProcessSheetRow`/`ProductionBatch` 均 `@Where ✅ @SQLDelete ❌`；全仓 610 实体、`@SQLDelete` 23、`@Where` 342——`BaseEntity` 注释记着 `@Where` 在 `@MappedSuperclass` 上被忽略而批量补过，`@SQLDelete` 从没补。实证：`operation_logs` 里 3 条成功的 `PRODUCTION_PLAN/DELETE` ⇒ 行全 ROW_ABSENT；4 条失败的 ⇒ SOFT_DELETED（同一秒，API 409 后两分钟的手工 SQL）。`SalesServiceImpl.deleteDraft` 早已独立发现并显式 `softDelete()`。🔴 `SoftDeleteBlindSpotContractTest` docstring 粗体「全仓每一次 delete() 都是软删」为假且承重。
- **层②运维**：5 个孤儿计划（`39d06069/4d78ef7d/5c711e0b/7b19614a/d769a248`，08-29 03:12–10:08 活跃，全部走完开工→结单）**无任何 API/日志痕迹**（oplog 0 / 日志 0，阳性对照各 11 / 10；日志窗口 08-07→09-06 全覆盖）；同日 LIUSHANMEN `production_batches` 只剩 2、`material_consumptions` 0、5 张关联销售订单也物理消失且无 oplog ⇒ **手工多表清理漏了 `process_sheet_rows`**（形状与 `docs/audits/liushanmen/2026-06-12-prod-demo-cleanup-proposal.md` 21 表清单一致，该清单从未提 `process_sheet_rows`）。且非一次：`V20261029_43` 软删保留的 8 计划 + 14 行台账后来也被物理清掉。具体命令不可追（无 psql_history）。
- **悬空**（44 个 plan_id 列全扫，阳性对照活计划 9 列非零）：`process_sheet_rows` 56 + `_change_log` 56 + `business_links` 15 = **127 行**；引用 `production_plans` 的 FK 仅 3 条全 NO ACTION，`process_sheet_rows.plan_id` 无 FK。row 1500 = 全 prod 唯一 `semiFinished:true`（首轮 LIKE 模式无空格读 0 是假零——prod JSON 冒号后有空格，阳性对照 93 抓的）。
- **监控**：`soft-delete-orphans-watch.sh`（cron 04:30）08-22 起 16 天 `INSTRUMENT_DEAD no-backend-pid` + `STALE sql-older-than-jar`；其 60 对表不含本对。⇒ 死 + 盲。
- **修法选项**：(a) `ProductionPlan` 加 `@SQLDelete` + 改假 docstring + 「`@Where`⇒`@SQLDelete`」**棘轮**（起点 320 违例，⛔ 硬闸即死）；副作用：`PP-AUTO` 计数改为单调，反而修了潜在撞号。(b) FK `process_sheet_rows.plan_id → production_plans`——建议 `NOT VALID`（容存量 56、拦新增），否则先清 56（它们是那天生产唯一幸存记录，⛔ 不该删）；会打断 `tests/v1-e2e/scripts/seed-and-reset.sh:151`（它删 plans 不删 rows）。(c) 级联删 ⇒ 销毁唯一幸存记录，否。(d) 只靠 #620 读侧 ⇒ 不拦新增、不盖 43 列。**独立最便宜的赢**：救活 cron + 登记该对（已开 `OPS-SOFT-DELETE-ORPHAN-WATCH-DEAD`）。
- 给 owner 的一句话：**有人在生产库上手工跑多表 DELETE**，且不止一次；这是纪律问题不是代码问题，代码侧能做的是让它下次响。


## 2026-09-06 06:13 probe 回报 `OPS-SOFT-DELETE-ORPHAN-WATCH-DEAD`（只读；brief 的两个猜测都错）

- **真因 [RUN]**：脚本 :44 用 `ss -lntp` 按端口找 PID（蓝绿两端口都覆盖，方法正确）；但 job 在 root **用户 crontab**（无 `PATH=`，cronie 编译默认 `/usr/bin:/bin`，`strings /usr/sbin/crond` 验过），`ss` 只在 `/usr/sbin` ⇒ 找不到；`2>/dev/null` 把 `command not found` 吞掉 ⇒ 空串 ⇒ `no-backend-pid`。对照：09-06 04:30 那分钟后端日志 20 行、`/var/log/cron` 显示 job 跑了、`soft-delete-orphans.cron.log` 0 字节。蓝绿**不是**原因（手工跑同一行取到 PID 2592002）。
- **第二层潜伏 [RUN]**：`grep '^DB_'` 取凭证，绿单元 environ 只有 `DB_NAME`/`DB_PASSWORD`，密码在 `POSTGRES_PASSWORD`，`DB_USERNAME` 缺被静默兜底 `cretas_user`——修 PATH 后会撞到这里。
- **STALE [RUN]**：`[ jar -nt sql ]` mtime 比较，jar 每次部署都换 ⇒ 每次部署后必响（16/32 条台账行是它）；生成的 `.sql` 零注释无 provenance，且**不在仓库**（只在服务器）。
- **rc=2 去哪 [RUN]**：写进 `soft-delete-orphans.alert`（写对了），但 **11 个 `*alert*` 文件 / 13 个 cron 脚本 / 零消费方**：`/var/spool/mail/root` 不存在、postfix inactive、`MAILTO` 只在 `/etc/crontab`、crontab 行 `>> cron.log 2>&1`。⇒ 喊在没人的地方，系统性。
- **治理 [RUN]**：三份 md5 相同无漂移；但不在 `server-script-inventory.conf`（12 条全是 deploy 链），`test-server-script-drift.sh` 规则 6 只扫 `scripts/deploy/{lightsail,ecs}/*`；它是唯一从手工 scp 的 `/www/wwwroot/cretas/scripts/` 跑的 cron；服务器 `code/` checkout 停在 05-07（4 个月）。
- **登记盲区 [RUN]**：`check-soft-delete-orphans.sh` 只认 `@ManyToOne` 关联字段，`ProcessSheetRow.planId` 是 `String` 标量 ⇒ 结构性隐形。`NOT EXISTS` 模式对**缺失父行**有效（实跑该对 = 56 = 全部硬删父，软删父 0，阳性 132 = 76 + 56）。
- 🔴 **口径打架**：前一 probe 的「44 个 plan_id 列 / 43 个 0 行」不复现——`public` 8 个（`tenant_purge_68` 5 个），6 个无 FK 里 4 个有悬空：`scheduling_alerts` 170 / `_change_log` 67 / `process_sheet_rows` 56 / `report_reversal_logs` 10。建议只登记后两对（前两个是日志表，机制不同，登记会成噪音）。
- **修法**：`/usr/sbin/ss` 绝对路径或 crontab 加 `PATH=`；去 `2>/dev/null`；凭证 `^(DB_|POSTGRES_)` 且用户名缺=rc=2 不兜底；rc=2 原因可区分（`ss-not-found` vs `no-listener`）；STALE 改为 `-- generated-from <commit>` 头对比或删；`.sql` 入库；登记两对；脚本进 inventory + 规则 6 扩到 `scripts/cron/*`；**修完主动把 ss 从 PATH 拿掉一次验 rc=2 到人**。投递渠道是全仓问题 ⇒ 另开票待 owner。


## 2026-09-06 06:29 `BUG-GHOST-PENDING-FEED-DELETED-PLANS-20260906` 归档 — #620 发 40 上线

原条目：- `BUG-GHOST-PENDING-FEED-DELETED-PLANS-20260906` — `review` — Owner: executor（organizer 派） — PR #620 终审：方向全过（COMPLETED 行保留=可逆失败向；CANCELLED 三条路径全验已回滚；盘点经委托链继承）。需改一条：浮出 WARN 每次快照都刷 ⇒ 加节流或仅集合变化时打（形态 E）。🔒 全量套件已打标签。

- **终审可合**：COMPLETED 计划上的未结算行「保留」=可逆的保守失败向（超卖不可逆）；CANCELLED 三条路径全验（直接取消守 `hasProductionActivity` 409、审批取消先 `reverseBatchReportsForApprovedCancel`、加急插单拒绝时未开工）无未回滚；盘点经 `pendingSemiFeedByBatchNo → pendingSemiFeedSnapshot` 委托链自动继承；硬删缺口另票（已开）。需改一条已补（`a0beb568a4`）：WARN 由每次快照改为**边沿触发指纹**（三个计划 id 集合 TreeSet + 两个计数；指纹变才喊；清零时删指纹防复发永久静音）——⛔ 没用时间窗，因为时间窗会吞掉窗口内新出现的幽灵计划。三个节流变异 N1/N2/N3 先打印 `announce=`/`fpMapSize=` 再红；`DeferredDeductionCompensationContractTest` 一字未改且在 CI 执行。
- 全量套件 6m40s 绿；12/2/2 + 4 个相关类在 CI 实际执行。
- **发 40**：`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS` 1 次、jar 06:26:25 < 启动 06:26:46、`shouldAnnounce` 1 / `UnsettlablePlans` 5 / 假串 0（合并前后 0→5 / 0→10）、health 200。⚠️ prod 今天该分支剔除 0 行（LSM 56 孤儿行全已结算），验收靠 F006 形状 fixture；F006 2 条 COMPLETED 计划上的未结算行会被浮出（WARN 一次）。
- 执行者自伤记一笔：变异脚本开头 `git checkout -- <src>` 把**未提交**的节流实现回滚了，重做后改为先提交再变异。


## 2026-09-06 08:05 `OPS-SOFT-DELETE-ORPHAN-WATCH-DEAD-20260906` 归档 — #627 合并 `3cdd6d441c`，47 上装机并手跑验证

原条目：- `OPS-SOFT-DELETE-ORPHAN-WATCH-DEAD-20260906` — `in-progress` — Owner: executor（organizer 派） — 修 watcher（PATH/去吞错/凭证 `^(DB_|POSTGRES_)`/可区分 rc=2/provenance 替代 mtime），`.sql` 入库 + 手工对段登记两对，进 inventory + drift 规则 6 扩到 `scripts/cron/*`，并**主动构造 rc=2**。⛔ 不建投递渠道（另票）。服务器安装由 organizer 走 installer。🔒 运维。

- **真因确认**（执行者订正 probe）：只改 PATH 即复活；`DB_PASSWORD`+`cretas_user` 在 prod 直接能连，`POSTGRES_PASSWORD` 反而连不上——brief 里「第二层凭证潜伏」不成立。用户名缺回落应用默认并标 `src=app-default`（终审核 pg_hba 无 trust，peer/md5 下错用户名只会认证失败，假 0 不可达）。
- **三轮终审**：① `.sql` 入库两份 ⇒ 加离线重放比对闸（剔 provenance 与 MANUAL_SECTION；变异加 `@ManyToOne` 62→63 红 / 删快照拒绝 / 去标记红）；② `scripts/tests/*` 0/22 无 CI ⇒ 新 `ops-script-tests.yml`，第一次跑 48 个 12 红（1 条本 PR 自伤：`hasdel.txt` 入库未登记，已登记；11 条存量烂测试）⇒ 棘轮 `known-failing.txt`：不在名单红 / 在名单 rc=0 红「FIXED 请删」/ 文件不存在红 / 无理由红，退出码开放集合只测 `-eq 0`；CI 变异 A 删一行 `NEW_FAILURE`(33998380030)、B 加绿测试 `FIXED`(33998506319)；③ FLAKY 第四态出口：双向 run id（rc=0 与 rc≠0 各一、不得相同）+ 14 天过期，CI 变异 C 过期红(33999698318)、D 同 id 红(33999745657)；④ 快照 vs prod 对账：watch 线上读快照头的 `capture-sql` 原句重查，≠ ⇒ `rc=2 reason=hasdel-snapshot-stale`，prod 实测 A 同步 rc=1 `hasdel-in-sync tables=384`、B 强制去一张表 ⇒ rc=2 `only-live=[accounts]`。连带：快照必须装到服务器 ⇒ 安装 3 文件。
- **装机 [RUN]**：deploy worktree detach 到 origin/main `3cdd6d441c`，dry-run 与 PR 预期逐字一致（2 `REFUSED_DRIFT` + 1 `WOULD_INSTALL_NEW`）；`--accept-overwrite-drift --confirm YES-INSTALL` 三次，旧版备份 `*.bak.20260905T2357*Z`；自证 `MATCH=5 DRIFTED=0 MISSING_ON_SERVER=0`（`MISSING_IN_REPO=6` 是安装前就有的未登记服务器文件：backup-db.sh 等，非本次）；md5 仓库/服务器 3/3 相同；crontab 行与 inventory REMOTE_PATH 逐字相同未改。
- **手跑 [RUN]**：rc=1，台账 35→38 行：`INFO provenance-unknown sql-commit=f4fe4ea17f81`（分支 SHA 被 squash 抹掉，INFO 非告警，登记）/ `INFO hasdel-in-sync tables=384` / `ORPHANS_FOUND db=cretas_prod_db user=cretas_user(src=app-default) pw=DB_PASSWORD`。告警文件本次：`semi_finished_stocktake_items→semi_finished_stocktakes 3262` / `process_sheet_rows.plan_id→production_plans 56` / `report_reversal_logs.plan_id 10`。
- **未做**：告警投递渠道（`OPS-CRON-ALERT-FILES-HAVE-NO-CONSUMER`）；两个日志表对显式不登记；11 条存量红测试另票 `OPS-SCRIPT-TESTS-11-LEGACY-RED`（5 疑似真回归 / 5 疑似环境 / 1 未分类 `test-release-pipeline-acceleration`）；`m67_demo_seed.sql` 等不动。
- 执行者自伤两笔：本地绿是因为在 `git add` 前跑（`git ls-files` 看不见未跟踪文件）；受限 PATH 缺 `sort`/`comm` 时症状不是报错而是「快照陈旧」这个像样的结论——每个工具逐个证明可用而不是信清单。


## 2026-09-06 08:34 `SEC-CUSTOMER-ROSTER-REMAINING-PATHS-20260906` 归档 — #628 合并 `e81f9338d4`，发 41 上线

原条目：- `SEC-CUSTOMER-ROSTER-REMAINING-PATHS-20260906` — `review` — Owner: executor（organizer 派） — PR #628：18 GET 全点名，12 条四码（含漏的 `/vip` `/outstanding-balance`），6 条聚合留；仓储改 `warehouse-options`（`warehouse:read_write`）；AI 再堵 6。终审需改：`/overall-statistics` 键集、3 个未登记 tool 类、403 出处。🔒 权限。

- **范围**：控制器 18→19 个 GET 全点名 = 12 条四码（#615 的 1 + 本次 11，含 brief 漏的 `/vip`、`/outstanding-balance`）+ 1 `warehouse-options`（只回 id/name，`CustomerOptionDTO` 2 分量钉住，码 `warehouse:read_write`——prod L1 `production_manager` 在 `warehouse=r`，用 read 等于从侧门撤销 #615；能开表单的角色 ⊊ rw 集合，有余量）+ 6 聚合留（`/overall-statistics` 8 个 key 全聚合，`CustomerOverallStatisticsKeysContractTest` 钉 key 集合恰好 8）。
- **AI 侧**：字节码尺子（声明 `CustomerService`/`CustomerRepository` 字段）量出 9 个未登记工具（审查者 grep 尺子只见 3）：挂四码 4（`customer_priority_query` 200 条/次含联系电话、`call_record_followup_pending`、`opportunity_stage_alert`、`wechat_record_recent_query`）、挂 `sales:read_write` 2、登记不挂 6（判别线：必填 `customerId` 单客户定向可登记；无入参/逐行反查客户名=枚举必挂）、`sku_assemble` 的 `availableCustomers` 全名录改计数。完备性断言=两把尺子并集，码集须为四码非空子集，自带三个必发生读数。
- **403 出处**：`RequireRoleInterceptor` 的 code 也是 FORBIDDEN，只有 `PermissionInterceptor` 写 `meta.requiredPermissions` ⇒ 断言 meta 四码；变异拿掉 `/active` 注解 ⇒ 阴性对照 403→200（证明是这道注解在拦，不是外层）。13 个 HTTP 变异 javap 证注解离开方法块；AI parity 反射读控制器注解不抄第二份码。
- **终审两轮**：运营 `operations_coordinator` 靠自身 `sales=r`、调度 `dispatcher` 靠 `sales=rw` 过四码，裁定内角色零误伤；RN `SalesOrderCreateScreen` 仅 `sales_manager` 可达（两条既存契约测试钉）；web-admin 无第二个 `/active` 调用方。
- **发 41 [RUN]**：`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS` 1 次；绿槽 pid 2651921 jar 08:23:55 < 启动 08:24:15；`warehouse-options` 1 / `CustomerOptionDTO` 1 / `customer_priority_query`@Enforcer 1 / 假串 0（合并前后 0→1 / 0→5）；`/api/mobile/health` 200；`/F006/customers/warehouse-options` 未授权 401（存在）。web（139 `/www/wwwroot/web-admin`）：入口 `index-ChvsAa_P.js` 08:21，标记 chunk `list-GJFM_4ii.js` 从活 index BFS 可达；5 个含 `customers/active` 的 chunk 全为不可达孤儿（2288 文件仅 468 可达）。
- **登记未做**：`warehouse_worker`（rw）能调 `warehouse-options` 但开不了表单（收窄需角色白名单，另机制）；6 个登记不挂工具的安全前提是「拿 UUID 的路径全部带门」（`customer_search` 有门）——新增不带门却回吐客户 UUID 的工具会同时打开它们；被挡角色在 AI 对话里看到的是可见拒绝还是静默空未验（审查者提）。
- **probe `BUG-SEMI-STOCKTAKE-ITEMS-3262`**：父物理缺 0 / 父软删 3262（FK 强制，物理缺不可能）；21 个父 `deleted_at` 同一微秒 `2026-07-15 00:24:08.128981`、`updated_at` 未动 ⇒ 绕过 JPA 的手工 SQL；同微秒命中 18 表 1082 行，`semi_finished_stocktake_items` 不在其中（无 `factory_id`，正是 `V20261029_68` 警告的那类孙表）；100% F006（`f006_admin`/`f006_warehouse_mgr`，批次号 `DEMO-COST*`），LIUSHANMEN 0；无 delete/cancel 端点、`findByStocktakeId` 是死代码、所有读路径父先行 ⇒ 用户无感（列表不显、详情 404），告警「可能 500」为假。**organizer 写入 [RUN]**：单事务、只 `factory_id='F006'` 且父软删的子行，`deleted_at=父.deleted_at`，DO 块守 ROW_COUNT=3262 否则抛；前后：孤儿 3262→0、活父下 1654→1654、子行与父 deleted_at 相等 3262、总行 4916 不变。复跑日扫告警只剩 `process_sheet_rows` 56 / `report_reversal_logs` 10。


## 2026-09-06 09:02 `ARCH-PRODUCTION-PLAN-HARD-DELETE-PATH-20260906` 归档 — #629 合并 `ae86d1606f`，发 42 上线

原条目：- `ARCH-PRODUCTION-PLAN-HARD-DELETE-PATH-20260906` — `review` — Owner: executor（organizer 派） — PR #629：三实体加 `@SQLDelete`；棘轮 332→329；`V20261101_34` FK NOT VALID+RESTRICT（容 56/13 存量）+ 幂等唯一索引改部分索引（软删占键⇒23505）。终审需改：validate 实跑、棘轮改清单文件。🔒 Flyway/架构。

- **改动**：`ProductionPlan`/`ProductionBatch`/`ProcessSheetRow` 加类级 `@SQLDelete`（census：三仓库 delete 调用 1+0+0，`@Modifying DELETE` 0，入向 CascadeType.ALL 2 处登记，无 `@Version`/`@PreRemove`）；`SoftDeleteBlindSpotContractTest` 假 docstring 订正；新棘轮 `SoftDeleteAnnotationCoverageRatchetContractTest` 读清单文件 `soft-delete-known-violations.txt`（329 FQCN）三种红（未登记缺注解 / 已修仍在清单 / 清单类不存在）+ 空清单或 <300 实体 ⇒ 红；`V20261101_34`：`process_sheet_rows.plan_id`、`report_reversal_logs.plan_id` → `production_plans(id)` NOT VALID + ON DELETE RESTRICT，`uk_production_plan_create_request` 重建为 `WHERE client_request_id IS NOT NULL AND deleted_at IS NULL`（软删占键 ⇒ 幂等重试 23505；prod 8 个软删计划 7 个带 request_id），按 `pg_constraint` 分支 DROP（prod 是 INDEX、Hibernate 建库是 CONSTRAINT，两支都真跑）；`seed-and-reset.sh` 先删 `process_sheet_rows`。
- **执行者订正 brief**：计划号计数器行为不变（`countByFactoryId` 派生查询本就 @Where 过滤且 prod `plan_number` 无唯一索引）；`report_reversal_logs` 孤儿 13 非 10（口径差）；`@SQLDelete` 实体合前 20 非 23。
- **终审两轮**：RESTRICT 半径——全仓唯一物理删 `production_plans` 在 `scripts/m67_demo_seed.sql:14`（demo seed，该炸；建议同 seed-and-reset 先删子表，未改；该文件注释里有 `PGPASSWORD=cretas123`）；无租户注销路径；`GlobalExceptionHandler:579` 子串匹配 `violates foreign key constraint` ⇒ RESTRICT 拒绝落友好 409 非 500；软删读侧 3 处无 `deleted_at` 原生读：`countAllPhysicalBy…` 有意、`ExternalVerifierServiceImpl:347/:291` 诊断计数会漂（低危登记）、`:782` 精确 join 可忽略；看板/汇总有过滤。**validate 实跑**（docker pg17 + 真上下文 `pg-prod`）：E1 谓词索引 CONTEXT_STARTED、E2 唯一对象整个删掉也 STARTED、E3 删列才红 ⇒ Hibernate validate 只看表/列/类型不看唯一对象。副产品：prod 实跑 `ddl-auto=none`；prod schema 过不了 validate（jsonb≠json）—— 另票。约束名代码引用 0（`ON CONFLICT ON CONSTRAINT` 仅 `uk_intent_code_factory`）。`plan_id varchar(64)` vs 父 `varchar(191)`：PG 建得起，prod `max(length(id))=36`、>64 为 0，登记。
- **CI**：fast-contracts + `java-full-ready` 全量 6m36s 绿；Flyway 合前重数 origin/main 最高 `_33`。
- **发 42 [RUN]**：`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS` 1 次（Java deployed / web no-op 正确）；蓝槽 pid 2660912 jar 08:53:24 < 启动 08:53:43；迁移文件在 jar 1、`ProductionPlan.class` 软删 SQL 串 1、假串 0；`/api/mobile/health` 200。**库侧前后对照**：FK `process_sheet_rows`/`report_reversal_logs` 0→1（`convalidated=f`、`confdeltype=r`）；唯一索引谓词 `client_request_id IS NOT NULL` → `… AND deleted_at IS NULL`，`pg_constraint` 同名 0；`flyway_schema_history` `20261101.34 success 08:53:52`；存量孤儿 56/10 一行没动。
- **登记未做**：127 行悬空（56+56 change_log+15 business_links）保留为历史记录；两个日志表对不加 FK；`process_entry_idempotency`/`backup_stuck_plans_20260802` 不动；`uk_sheet_row` 未收紧（`ProcessSheetRowRepository` 零删除路径，要求写在实体注释）；08-29 与 07-15 两次手工多表 SQL 是纪律问题，代码侧 RESTRICT 现在会挡住漏子表的清理。


## 2026-09-06 09:38 `BUG-SEMI-STOCKTAKE-ITEMS-3262-ORPHANS-20260906` 归档 — #632 合并 `19093e274f`，47 装机手跑

原条目：- `BUG-SEMI-STOCKTAKE-ITEMS-3262-ORPHANS-20260906` — `review` — Owner: executor（organizer 派） — 3262 子行已对齐父行（→0）。PR #632 生成器拆 absent/soft_deleted 两列 + 形状闸；终审条件：软删-only 按对**算出** `escalate`（子实体有 Repository 且 FK 无 `@NotFound`）而非统一降级，补改中。合后 organizer 装 3 文件。🔒 运维。

- **生成器**：每对两列 `absent`（父物理缺，悬空引用）/ `soft_deleted`（父软删子活），外层谓词与旧 `NOT EXISTS` 逐字同义（审查者逐例验三情形；LEFT JOIN 键为父 PK 故不放大；`::text` 旧写法即有；最大表 11.4k 行日跑成本可忽略）；第 4 列 `escalate_soft_deleted` **算出**：子实体有自己的 `*Repository` 且 FK 上无 `@NotFound(IGNORE)`，依据写进 `.sql` 头 62 行 `-- class …`（在漂移闸比对区内）；父 `@Id` 必须映射列 `id` 否则生成期 `PK_ASSUMPTION_VIOLATED` exit 2（阴/阳性对照）。诚实读数：**62/62 escalate=1**（431 个 `@Table` 实体只有 3 个无 Repository 且不在 62 对里；`@NotFound` 关联在采集期就被丢，0/62）⇒ 该列今天不过滤噪音，只分措辞与留依据，已写进头部防误读。去掉自己引入的形态 D（collector 与 classifier 各算一次 `@NotFound`，统一为 `has_notfound`，输出字节相同）。
- **watch**：`absent>0` ⇒ rc=1「悬空引用…可能 500」；`soft_deleted>0 且 escalate` ⇒ rc=1 单独一段「父软删但子表有独立读路径, 可能 EntityNotFoundException」；非 escalate ⇒ rc=0 + INFO；形状闸 4 列（缺列或 t/f ⇒ rc=2 `result-shape-unexpected`，半装旧 SQL 不假绿）。测试 25 + 新 `test-soft-delete-orphans-escalation-classify.sh` 6（真语料同实体对照：`Customer` 两个 `NotFoundAction.IGNORE` 关联不入产物、同实体 `factory_id` 入；变异 A collector 忽略 @NotFound ⇒ 红、B `has_notfound` 恒 False ⇒ 本应非 escalate 的对被升级 ⇒ 红）。CI run 34004026476 `TOTAL=50 PASS=39 FAIL=0 KNOWN_FAILING=10 FIXED=0 FLAKY=1`。
- **装机 [RUN]**：deploy worktree detach `19093e274f`；dry-run watch/sql `REFUSED_DRIFT`、hasdel `MATCH`；真装两文件 `OVERWROTE_DRIFT`，自证 `MATCH=5 DRIFTED=0`；md5 仓库/服务器 3/3 同；手跑 rc=1 台账 41→44：`hasdel-in-sync tables=384`、`ORPHANS_FOUND absent=66 soft-deleted-escalated=0 soft-deleted-info=0 pairs-absent=2`；告警仅 `process_sheet_rows` absent=56 / `report_reversal_logs` absent=10。`provenance-unknown sql-commit=b8e0beef33`（分支 SHA 被 squash 抹掉，INFO 非告警，已登记）。
- **未做**：告警投递渠道（`OPS-CRON-ALERT-FILES-HAVE-NO-CONSUMER`）；两条真悬空对（56/10）是历史记录不清理（#629 已加 NOT VALID FK 拦新增）。


## 2026-09-06 10:12 `OPS-SCRIPT-TESTS-11-LEGACY-RED-20260906` 归档 — #635 合并 `5ab4660331`（33 文件，全在 `scripts/**` + `ops-script-tests.yml`）

原条目：- `OPS-SCRIPT-TESTS-11-LEGACY-RED-20260906` — `queued` — Owner: 无 — #627 首次把 `scripts/tests/*` 接进 CI，48 个里 11 个红从没人看过；棘轮 `known-failing.txt` 钉 10 + FLAKY 1。疑似真回归 5（ci.yml paths / preflight 无执行位 / smartbi:401 管道 / embedding 缺 rsync / 制品名），详见 log。🔒 deploy 脚本。

- **分账**（硬约束 8）：脚本错 3 + 测试断言错 7 + 前提 1 = 11；`known-failing.txt` 11→0，CI run 34007949276 `TOTAL=51 PASS=51 FAIL=0 KNOWN_FAILING=0 SKIPPED=0`。前一执行者的疑似分类 11 条错 4 条（重测不重贴标签）。
- **脚本侧真缺陷（只在非 Windows 上存在）**：① `scripts/lib/deploy-common.sh` flock 分支 `exec 200>` 在判定前截断锁文件 ⇒ 持有者 PID 被抹 ⇒ 回收块整段跳过 ⇒ flock 仍被子进程握着 ⇒ 锁永久卡死；改 `exec 200<>` + 拿锁后截断。docker ubuntu 变异读数：被拒后 LOCKFILE `[13]` vs `[]`、持有者死后再获取 rc=0/回收块 yes vs rc=1/no，`test-deploy-mutex-coverage` 与 `lock-staleness` 双红。**登记未消除**：遗留子进程仍在被 unlink 的旧 inode 上握 flock，与新持有者并存的窄窗口互斥失效（仍是 5/30 形状，只比永久卡死好）；一行修法 `( exec 200>&- ; <后台任务> ) &` 只评估，现有用例量不到它。② 7 个发布链脚本 git 索引 100644（`release-java-preflight.sh` 被 `release-cretas.sh` 直接调 4 处 ⇒ POSIX 检出 rc=126）；新闸 `test-deploy-script-exec-bit.sh` 问 git index；第二组从 inventory 派生「有 shebang 的清单文件必须 100755」（代理判据已标注可替换；9 命中全 +x，0 假阳性，含 `scripts/cron/soft-delete-orphans-watch.sh`）。⚠️ 我的前提错：`install-server-scripts.sh:33/108` 是 `install -m 0750` 写死，不透传索引 mode ⇒ 安装那份不会不可执行，真被咬的是「从服务器 checkout 直跑」那 10 个。③ `deploy-smartbi-python.sh:401` 管道进 grep 改掉，等价性 `EQUIVALENT=yes`（1 行，阴性对照 0 行）。
- **测试侧**：`lock-staleness` A2 只编码了 PID 文件那套互斥，flock 下「PID 活但没人持 flock」正是 9-02 PID 复用假阳性（同文件 A5 的另一面）；`private-repo-readiness`/`release-ci-artifact` 陈旧字面量（打包已由 `e0f7a6dbcb35`/#175 移到 `java-artifact.yml`，其 paths 覆盖整棵 `backend/java/cretas-api/**`，不变量更强——原 ci.yml 只列 src/main+pom+lib 时最近 120 个 main commit 里 3 个漏触发）；`web-admin-entry-chunk` 禁令未动、变异改为可移植的「塞 `grep -oP`」；`post-switch-probe` 断言数字未动、错的是桩；JDK 前提在 workflow 修而非 skip；runner 加 rc=77 `SKIPPED`（无 `SKIPPED:` 行 ⇒ 红；名单内 77 ⇒ 红）；`ops-script-tests.yml` paths 补 `scripts/deploy/**`、`scripts/lib/**`。13 个变异各先绿后红；一个原本守空气的（`grep -F 'retention-days: 1'` 也匹配 14）改锚行尾。
- **踩坑登记**：`git commit -- <paths>` 丢索引 mode 变更（`core.fileMode=false`），第一次 +x 提交显示 8 文件实际 1 个 ⇒ 用 `git add` + `git diff --cached --summary` 证 `mode change`；`test-release-cretas.sh` Windows 本地 ~340s 超 300s 限（CI 8s），改前基线同量级，未改超时。
- **合并后 [RUN]**：origin/main `git ls-tree` `release-java-preflight.sh`/`release-cretas.sh`/`soft-delete-orphans-watch.sh` 均 100755。锁改动只在 flock 分支，Windows 发布走 PID 文件分支不受影响；下一次 `release-cretas.sh` 实跑即验证。


## 2026-09-06 14:45 owner 五项裁定 + OTA 推送

- **裁定**：① 告警渠道暂不做留票；② 权限矩阵 step-1 按推荐（L1 只读矩阵页）；③ validate 按推荐（修通、测试环境证明、不改 prod 参数）；④ 销售账号 = `lsm004`（六膳门销售主管，sales_manager，最近登录 09-04）已给 owner，owner 已在小米上登录；⑤ OTA 直接推。
- **OTA 推送 [RUN]**：deploy worktree detach `b65a7d4312`（`frontend` 与 `19093e274f` 无差异），`push-bundle.sh production android` `PUSH_EXIT=0`，82s，新包 `ts=1788676646980`（`createdAt 2026-09-06T06:38:37Z`），manifest 已切换，CDN `dl.` 上 hbc 200（6.7MB gzip）。现网前一包 `1788544899864`（09-05 02:03）**已含 #590**（`settleAllIndependently` before=1），本次新增只有 #615 的 RN 改动（`ShipmentManagementScreen` 三路独立 settle + util 14 行）。⚠️ 标识符层面正反两向都量不出（#615 全是复用已有符号；Hermes 字符串表去重），新旧 hbc md5 不同 + manifest ts 是仅有的制品证据；**送达**只能靠真机读 update id + 行为（probe `PROBE-XIAOMI-OTA-AND-SHORT-CLOSE` 在跑）。deploy worktree 推后 `git status` 干净（dist 未入树）。
- 同轮派出：`PROBE-XIAOMI-OTA-AND-SHORT-CLOSE-20260906`（读 OTA 送达 + `DLV-20260904-4086` 的「短交结案」按钮，只读不点）。


## 2026-09-06 15:05 `PROBE-XIAOMI-OTA-AND-SHORT-CLOSE-20260906` 回报（真机 M2102K1AC，lsm004 已登录；只读，未碰密码，未做业务写入）

- **OTA 送达 ✅（行为读数）**：`am start` 拉回前台（pid 11535 不变=resume）触发 `scheduleBackgroundOtaCheck`；`dev.expo.updates`：`14:41:47 CheckCompleteAvailable → DownloadComplete(1.08s) → 14:42:59 点「立即更新」Restart → ReactNativeJS Running main → 14:43:00 Check → CheckCompleteUnavailable`。设备读到 update `createdAt 2026-09-06T06:38:37.000Z` = 推送 manifest。决定性的是最后的 Unavailable（新 JS 问服务器答「已最新」）。附带：asset 仍走 `dl.` 域且 1 秒内下完，该域此刻可用。
- 🔴 **短交结案按钮不存在（形态 B）**：`SalesController.java:671` 端点在（`sales:read_write`，注释「只授予 sales_manager」）；`grep close-short-shipment` RN+web-admin = 0（阳性对照 `/cancel` 在 `salesApiClient.ts:330`）；RN 里「短交结案」只作状态文案（`SHORT_CLOSED`）与 `rowActionsConfig.ts:60` 事后动作；web-admin `短交结案|shortClose|SHORT_CLOSED` 0 处。实拍 `SO-20260902-0001`（部分发货，美式汉堡饼 订 10kg/已发 9.8）详情页零操作按钮。`ShipmentManagementScreen` 对 sales_manager 路由层（`SalesManagerNavigator` 13 屏无它）+ 屏内层（`canView` 仅 admin 三角色）双重不可达，且它是 legacy 只读 ⇒ #615 那半改动本轮没量到（要 `liushanmen_admin` 才能验）。⚠️ 无法从 UI 证明该单就是 `DLV-20260904-4086` 对应单（详情不显示发货单号）。
- **#615 阳性对照 ✅**：销售看客户管理 6/6/0 显示名称非 ID；新建订单客户选择器 6 个全列；阴性对照退出后订单列表仍 14 条 / ¥102,684.20 未产生草稿。
- **附带**：首页「功能模块」对 sales_manager 全空（截图 01/03）→ 开票 `BUG-HOME-MODULES-EMPTY-FOR-SALES`。截图 9 张 + logcat 原件在 scratchpad `PROBE-XIAOMI-OTA-*`。
- **执行者中断**：权限矩阵与 validate 两个执行者被 Opus 403 掐断；权限矩阵在原树续作（已有端点 commit `5715510400`）；validate 原树是别人的旧分支（`codex/claude-rn-product-md` 109 脏文件，未动）⇒ 新树重开、复用 scratchpad 里的 schema dump/枚举脚本。


## 2026-09-06 15:50 测试环境刷新 + #640 终审

- **Mac mini 测试环境后端刷到 main [RUN]**：此前 jar 8-28（早于 #581 端点，#640 验收 POST 404 没量到）。mini 无 mvn、java 不在 PATH ⇒ 传本机发 42 可信 jar（`build_commit=ae86d1606f`，Java 与 origin/main 零差异，sha256 `6884df5d…` 两侧一致，直连 scp 158s）；备份 `…jar.bak.20260906T073511Z`，`launchctl kickstart`，30s 后 health 200，pid 54329；Flyway 在测试库打了 `_32/_33/_34`（15:35:16 同秒 success），两条 NOT VALID FK 在；`f006_sales_mgr` token 下 `close-short-shipment` 假 id 返回控制器 404 文案（路由存在），`/cancel` 对照同 404；java.err 0 ERROR。刷新流程已写进 memory。
- **#640 终审可合**：RN `hasModulePermission` 读内置矩阵；prod L1 逐角色对照——sales_manager rw/rw 一致；viewer/cashier/运营 r ⇒ 隐藏+403 一致；dispatcher/restaurant_owner/super_admin 有权无按钮（允许方向，且导航器不注册该屏）；⚠️ `permission_admin` RN rw vs L1 `-`（存量漂移，屏不可达故不阻断）→ 开票。对话框字段 `quantity/deliveredQuantity` 与后端实体同名、缺口算法同式；`SHORT_CLOSED` 隐藏；错误 message+actionHint 经 `getErrorMsg` 到用户；按钮与 DRAFT 确认/取消同 actionBar；无新依赖，`closeShortShipment` 基线 0 命中可作 Hermes 标记；prod jar 08:53 已含路由串。审查者未验：真发 POST（正在测试环境量）、Alert 在 web 是 no-op、阴性对照用的 `f006_admin` 是 observer 闸不是权限闸（建议 viewer）。


## 2026-09-06 15:52 `BUG-SHORT-CLOSE-BUTTON-NOT-WIRED-20260906` 归档 — #640 合并 `dbb43c03ce`，OTA `1788680841522` 已推

原条目：- `BUG-SHORT-CLOSE-BUTTON-NOT-WIRED-20260906` — `review` — Owner: executor（organizer 派） — PR #640（RN 接线；web-admin 早已接在 `orders/detail.vue:1263`，brief 查错路径）终审可合。测试环境后端已刷到 main（`ae86d1606f`），执行者正在量「真结案」。合后推 OTA + 真机复验。🔒 RN/OTA。

- **改动**（RN 6 文件）：`utils/salesOrderShortClose.ts`（可见性谓词、逐行缺口、阻塞项、确认文案）、`salesApiClient.closeShortShipment`（与 `cancelOrder` 并列，axios 错误原样上抛）、`SalesOrderDetailScreen` 按钮 + 对话框（逐行 订购/已发/短交、原因单选、备注、ack 勾选后才可确认），客户端门 `hasModulePermission(user,'sales','write')`；⛔ 未进 `rowActionsConfig`（无 sales rw 轴，底部单不能装原因+ack）。web-admin 早已接在 `orders/detail.vue:1263`（brief 查的 `frontend/web-admin/src` 不存在，真路径根目录 `web-admin/`）。单测 22+3，全量 jest 217 套件/2267 绿，tsc 0；变异翻状态守卫 ⇒ 打印 `status=COMPLETED role=sales_manager => true` 后 2 红（`console.log` 被 setup 静音，要用 `console.warn`，否则读成「变异没落地」）。
- **终审可合**：RN↔prod L1 逐角色对照一致（`permission_admin` RN rw vs L1 `-` 存量漂移 → 票 `BUG-RN-PERMISSION-MATRIX-DRIFTS-FROM-L1`）；字段 `quantity/deliveredQuantity` 同名、算法同式；错误 message+actionHint 到用户；无新依赖。
- **测试环境验收 [RUN]**（后端刷到 main 后）：`SO-20260820-0001` PARTIAL_DELIVERED 5/4 → 对话框 5/4/1 → POST 200「订单已按短交结案」→ `SHORT_CLOSED`、`shortClosedQuantity=1.0`、`shortClosedBy=1556`、`version` 6→7；幂等重放不同 payload 200 且 version/reason 不变；阴性：`f006_admin` 同屏无按钮（observer 闸）、`f006_finance_mgr` GET 200 / POST 403 `meta.requiredPermissions=[{module:sales,action:read_write}]`（结构化对象，不是字面串）。
- **OTA [RUN]**：`push-bundle.sh` 100s，`ts=1788680841522`，manifest 切换，CDN 200（6.7MB）；hbc 标记 `closeShortShipment`/`canShortCloseSalesOrder`/`sales-order-short-close-button` 0→1，对照 1，假串 0。真机复验 probe `PROBE-XIAOMI-SHORT-CLOSE-AFTER-OTA` 在跑（只看不点）。
- **登记**：`Alert.alert` 在 react-native-web 是 no-op（全 App 既有形态，另票候选）；F006 测试环境只有 15 个活跃账号且每人一个模块通配，没有 `sales:read` 只读账号可做权限维度阴性（用财务 rw 替代）。


## 2026-09-06 16:02 `PROBE-XIAOMI-SHORT-CLOSE-AFTER-OTA-20260906` 回报（只读，未点结案）

- **OTA 送达 ✅**：15:50:19 Check（节流已过）→ CheckCompleteAvailable → DownloadComplete 1.2s → 点「立即更新」15:51:14 Restart → `Running "main"` → 再 Check → **CheckCompleteUnavailable**；设备 `launchAsset` 指向 `production/1788680841522/…index-7c6f5740….hbc`（全日志只有这一个 ts），`createdAt 07:48:48Z`（注册时刻，比目录 ts 晚 87s，自洽）。lsm004 会话跨重载存活。
- **按钮 ✅**：`SO-20260902-0001`（部分发货 10kg/9.8）与 `SO-20260802-0001`（部分发货 102/100）详情页都有「短交结案」`android.widget.Button enabled=true`（同 dump 里状态 chip 是 `enabled=false`，证明 enabled 不是常量）；`SO-20260902-0002`（财务已批准）与 `SO-20260804-0001`（已完成）0 个节点，且节点数 86 vs 89（Δ3 = 按钮+文本+容器），屏是完整渲染的。两阴性在状态机两侧。
- **仪器备注**：adb 不在 bash PATH（`%%LOCALAPPDATA%%/Android/Sdk/platform-tools/adb.exe`）；`uiautomator dump /sdcard/...` 在 Git Bash 下要 `MSYS_NO_PATHCONV=1`，否则路径被改写而命令仍 exit 0、pull 拿不到文件。
- **附带疑点** → `BUG-SALES-ORDER-LIST-STATUS-TAB-DOES-NOT-REFILTER`：已完成 tab 后底部 5 条/¥4,926.92 而卡片未重筛，单点采样。


## 2026-09-06 16:25 三条回报：#643 validate、首页模块 recon、列表 tab probe

- **#643**（`codex/claude-schema-validate-20260906`，基 `d68d37fe62`）：prod schema-only dump → docker 克隆 → Hibernate 生成 DDL 与 `information_schema` 按 Hibernate 6 实际规则（`typeCode==` 或 `expectedSql.startsWith(foundTypeName)`）离线比对作候选集，端到端 validate 起动为权威。等式 138 实体 + 19 迁移 + 0 登记 + 6 缺表 = 163。实体侧：119 `JSON`→`jsonb`、6 `json`→`jsonb`、4 `TEXT`→`jsonb`、7 String id 补 `uuid`（prod 183 行全是 36 位 UUID）、1 `MEDIUMBLOB`→`bytea`、1 `LONGTEXT`→`TEXT`，前后计数对账（剩 1 处 `json` 是 javadoc 例子）。`V20261101_35`：16 个存成 text 的 boolean → boolean（显式词表守卫，未知值即 abort，负对照 `'maybe'` ⇒ rc=3 零部分应用）、`ai_intent_configs.confidence_boost` float8→numeric(3,2)、`isapi_devices.equipment_id` varchar→bigint、`workflow_templates.deleted_at` ADD COLUMN；无损性用 prod 只读 SQL 证。🔴 `scale_protocol_test_cases.expected_stable` 存的是 4 字符字面 ` `/``（MySQL BIT 转义残留）。6 缺表：`employee_process_segments`、`tool_metadata` 任何库都没建过（同 `role_definitions`）；4 张 `smart_bi_*` 在 `smartbi_prod_db`，`PrimaryDataSourceConfig` 却扫该包并注释「因为 ddl-auto=none 所以不会建」——代码显式依赖 none，另票。证据：copy 起动 STARTED（84s）、负对照删列 ⇒ 点名失败；Mac mini 备用端口 10099 起动 1 缺表失败 / 起动 2 加 6 张 fixture 表 STARTED（⚠️ `_35` 已打到 `cretas_testenv`）；新闸 `EntityColumnDefinitionIsPostgresTypeContractTest` 两变异红。CI 6 项绿、fast-contracts 1739；已加 `java-full-ready`。执行者备注：harness 拒绝在自身 worktree 外做 git 操作（`worktree add` 不可用），改在干净的自身树上做。
- **首页模块 recon**：`HomeScreen.tsx:32-92` 5 张卡 `requiredPermissions` 为 `production/warehouse/trace/system:read`，`:87-91` 用 `hasPermission(user, p)`（`types/auth.ts:475-489` 精确/`<module>:*`/`*:*`）对登录响应 `permissions`（后端 `User.getPermissions()` = `role.getPermissionPrefix()+":*"`，`sales_manager`→`sales:*`；`trace`/`system` 前缀任何角色都没有）⇒ 走共享 HomeScreen 的 9 个角色（MainNavigator 4 + 采购/财务/出纳/销售/viewer）里 7 个 `[]`，只有 `*:*` 的 `permission_admin`/`platform_admin` 有卡。二阶：4 张卡路由 `ProcessingTab/LogisticsTab/ManagementTab` 只在 MainNavigator 注册。无「每角色至少一模块」测试；`backendPermissionContract.test.ts:31` 把缺陷编码成期望；`roleReachability.ts:111 permission_admin: []` fixture 错（后端 `*:*`）。三份权限源（L1 / Java 矩阵 / RN 矩阵）都不喂首页。
- **列表 tab probe [RUN]**：已完成/全部/已确认三个 tab 各 4 个时间点（0.7s→10s）卡片一次不变，页脚每次都变（阳性对照）；「已确认」共 0 条 + 4 张卡。根因 `SalesOrderListScreen.tsx:62-72` `const params: any` 塞 `status` 给 `getOrders` → `SalesController.java:146-152` 无该参数；页脚 `useListSummary` → `ListSummaryServiceImpl:86-91 appendStatusFilter`。`PurchaseOrderListScreen.tsx:35-46` 正确用 `getOrdersByStatus`；`GET /orders/by-status`（`:452`）有 `PENDING_FINANCE_REVIEW` 403 分支。无 部分发货 tab（4 tab / 10 状态）。release 包不打请求 URL 日志（logcat 0 命中），设备+代码证据自足。


## 2026-09-06 16:40 `OPS-PROD-DDL-AUTO-NONE-AND-SCHEMA-CANNOT-VALIDATE` + `BUG-WORKFLOW-TEMPLATES-WHERE-ON-MISSING-COLUMN` 归档 — #643 合并 `8c5391ec2c`，发 43 上线

原条目：- `OPS-PROD-DDL-AUTO-NONE-AND-SCHEMA-CANNOT-VALIDATE-20260906` — `review` — Owner: executor（organizer 派） — PR #643（68 文件）：163 处 validate 失败 = 138 实体 `columnDefinition` + 19 项 `V20261101_35` + 6 张有实体无表。🔴 发现 `workflow_templates` 带 `@Where(deleted_at)` 而 prod 无此列（每次读都错）。全量套件已打标，终审中。🔒 Flyway/实体。
原条目：- `BUG-WORKFLOW-TEMPLATES-WHERE-ON-MISSING-COLUMN-20260906` — `queued` — Owner: 无（#643 合并即修） — #643 发现 `WorkflowTemplate` 实体 `@Where(deleted_at IS NULL)` 而 prod 表无 `deleted_at` ⇒ 该表**每次 JPA 读**都 `column does not exist`（复现，阳性对照同表去谓词返回正常）。`_35` 加列即修；合后要真跑一次工作流模板读路径确认。

- **终审可合**（审查者独立 prod 只读）：唯一从实体建表的 lane 是 H2 `test` profile（`MODE=PostgreSQL`, create），实测 H2 对 `jsonb/uuid/bytea` **和** `MEDIUMBLOB/LONGTEXT` 都接受 ⇒ H2 绿在这类问题上零信息；`postgres-reversal-concurrency` 是 `ddl-auto=none` ⇒ **没有 CI job 用真 PG 校验这些实体**，证据只有执行者在 prod schema 副本 + Mac mini 的 validate 起动（带负对照）。`_35` 涉及 18 个 ALTER TYPE 的表最大 1585 行（11 张 0 行），无视图/CHECK/FK 依赖，两个 btree 在 0 行表；词表全覆盖（审查者自数非词表值 = 0）；`workflow_templates.deleted_at` 可空无默认 ⇒ PG13 只改目录；单事务 + 末尾 19 列断言；重跑走 NOTICE skip。未测 PG13（副本与测试库都是 pg17）。可选未采纳：`SET lock_timeout`（改文件会让已打到测试库的 `_35` 校验和不一致）。行为变化（正向）：此前 `text = boolean` 谓词的 6 处 JPA 查询一直报错，prod 30 天日志 0 次说明从没被走到；`scale_protocol_test_cases` 经 JPA 不可读；迁移后开始能用。7 个 String id 的 `columnDefinition="uuid"` 只影响 DDL/校验，绑定仍 VARCHAR，prod URL 有 `stringtype=unspecified` ⇒ 无行为变化。`WorkflowTemplate` 无删除路径（`pg_stat_user_tables` 自 2-24 零写）。新闸 `EntityColumnDefinitionIsPostgresTypeContractTest` javadoc 过度声称（只禁 MySQL 名与裸 json）——登记。
- **发 43 [RUN]**：`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS` 1 次（Java deployed / web no-op）；绿槽 pid 2790254 jar 16:32:13 < 启动 16:32:35；`_35` 在 jar 1、`ProductionPlan.class` `jsonb` 1、假串 0；health 200；prod 库 `flyway_top 20261101.35 success`、`workflow_templates.deleted_at` 0→1、5 个采样 boolean 列 text 0 / boolean 5、`confidence_boost` double→numeric、`equipment_id` varchar→bigint、`expected_stable` 值 `false,true`、`SELECT … WHERE wt1_0.deleted_at IS NULL` 可跑（0 行，该表本就空）；重启后 `text = boolean` 错误 0。⚠️ JPA 读路径未用 token 真跑（无 prod 账号密码），SQL 层已证；由 Line B 做「合并后复验」。
- **登记**：prod 启动参数未改仍 `ddl-auto=none`；主库 validate 要过还差 4 张 `smart_bi_*` 实体挪出主持久化单元 + 2 张从没建过的表 → `OPS-SMARTBI-ENTITIES-IN-PRIMARY-PERSISTENCE-UNIT`（Line B）；`_35` 已在 `cretas_testenv` 应用，文件校验和不可再改。


## 2026-09-06 17:00 #646 权限矩阵只读页发 44 上线；#648 列表 tab 合并；`BUG-SALES-ORDER-LIST-STATUS-TAB` 归档

原条目：- `BUG-SALES-ORDER-LIST-STATUS-TAB-DOES-NOT-REFILTER-20260906` — `in-progress` — Owner: executor（organizer 派） — 真机四点采样坐实：列表把 `status` 发给 `GET /orders`（后端无此参数，静默丢），底部合计走会过滤的 summary ⇒「已确认」共 0 条 + 4 张卡。采购列表同屏用对的 `by-status`（形态 D）。修：改调 `getOrdersByStatus` + 契约测试。🔒 RN/OTA。

- **#646 终审可合**：prod 只读——能开页的角色：`factory_super_admin`×3 / `platform_admin`×1（`hasPermission` 短路）、L1 `system=r` 的 `department_admin`/`dispatcher`、`system=rw` 的 `restaurant_owner`；`permission_admin`=`lsm001` L1 `system=-` ⇒ 403，来源 `V20260422_01:394` 原样（PR 未动）。只读：新增行 0 写映射（`permissionGateMatchesBackend.spec` 钉「Java 无写映射 + 有 GET /matrix」）。硬编码侧读 `PermissionServiceImpl.hardcodedMatrixSnapshot()` 遍历真矩阵；`override`/`fallback` 两类与 `resolveLayer1→checkAction` 短路顺序一致；L1 独有角色保留。模块轴 `menuConfig.module` 与 `AppSidebar.canAccess` 同串；轴外模块只显名与计数（值不显示、无 vitest 覆盖——登记）。租户：`JwtAuthInterceptor` 按 URL factoryId 对 token 校验。gate paths 只加一个精确文件。顺带：`index.vue` 在 `factoryId===''` 直接报错（平台形账号，prod 目前没有）；`hardcodedModules()` 零调用方；PR 正文「17 个模块」少数了 `operations` 那行。
- **发 44 [RUN]**：`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS` 1 次（Java + web deployed）；蓝槽 pid 2796648 jar 16:52:36 < 启动 16:52:54；`PermissionMatrixController.class` 1、`hardcodedMatrixSnapshot` 1、假串 0；health 200；`/F006/permissions/matrix` 未授权 401；139 `index.html` 16:49:30，权限矩阵相关 chunk 10 个里 4 个从活 index 可达（含 `menuConfig-STQfr4Hp.js`），2654 文件仅 470 可达。
- **#648 合并 `90e9b9a2f2`**（未送终审：3 文件、根因实测 `GET /orders?status=X` 全状态 42 条 vs `by-status` 7/0/11）：`SalesOrderListScreen` 去 `: any`、按 tab 调 `getOrdersByStatus`（已存在 `salesApiClient.ts:325`），by-status 403 显示后端「无权查看待财审销售单」；变异 M1 回退 ⇒ 9/11 红、M2 通用文案 ⇒ 403 测试红；测试环境三 tab 卡片数=页脚数（42/42、11/11、0/0 空态）；jest 218/2278。仪器坑：RN Web 每卡渲染 `<id>` 与 `<id>-container` 两节点、FlatList 虚拟化 42 行只 38 在 DOM。登记：`GET /orders` 静默丢未知参数（后端 strict-params 闸另票）；无 部分发货 tab（产品决定）。
- **#652 交付**：`homeModules.ts`（标记 `HOME_MODULE_SETS_V1_NAVIGATOR_AWARE`）；执行者订正 brief：`f006_admin` 是 `factory_super_admin` 走 `FAHomeScreen` 不是 MainNavigator；测试环境无 MainNavigator 角色账号（`platform_admin` 账号角色串是 `super_admin` ⇒ 落 `RestrictedRoleNavigator`，既有分派缺陷）；`backendPermissionContract:63` 从字面抬到性质；`roleReachability.ts` fixture `permission_admin` 改 `*:*`；VACUOUS 棘轮 38→37。登记：`trace:read`/`system:read` 全角色不可达；`PlatformRole` 发 `system:all`；`isModuleEnabled('processing')` 查的 id 与特性表的 `production` 不一致，该开关从没管过生产卡。
