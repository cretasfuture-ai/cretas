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
