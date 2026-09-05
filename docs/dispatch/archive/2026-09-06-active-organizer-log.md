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
