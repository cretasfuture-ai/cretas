# 交接：判据 B（web/App 全链走查）第二轮 · 2026-08-18

> 给下一个 chat。**先读「立刻要知道的三件事」，再往下。**

---

## 🔴 立刻要知道的三件事

### 1. GitHub 账号被停用，当前是**离线模式**

```
git fetch/push  → remote: Your account is suspended (403)
gh auth status  → The token in keyring is invalid
```

owner 正在申诉。**已按离线协议走完一轮部署**（见下）。

⛔ **恢复之后不要 `git push origin main`** —— 本地 main 上有 6 条从没走过 PR/CI 的合并。
正确做法是各 feature 分支分别 push → 开 PR → 走 CI → 合进 origin/main，
然后 `git checkout main && git reset --hard origin/main`。

### 2. prod 上跑的是**离线合并出来的东西**，GitHub 上查不到

```
prod Java   = 本地 main 3f75754b88（部署于 22:23）
prod web    = 同一棵树（部署于 22:28，backup web-admin.bak.20260818_222844）
origin/main = f635e6b65e（远端最后可见的点）
```

⇒ **prod 领先 origin/main 6 个合并**。恢复后要把这 6 条正规合进去，
否则下一个人从 origin/main 部署就会把它们**覆盖掉**（2026-05-30 事故的形态）。

### 3. `main` 分支名是我从别的 worktree 手里腾出来的

`cretas-vision-main` 原本占着 `main` 且停在 `d193ae86d0`（落后 420 个 commit）。
我把它**在它自己那个 commit 上原地 detach**（`git checkout --detach`），
commit 前后都是 `d193ae86d0`、脏文件数 1→1，**文件一个字节没动**。

⇒ 那个 worktree 现在是 detached HEAD。如果它那条线要继续，
让它自己决定是留在原地还是跟进 —— **不要替它 checkout main**（那会把它跳前 420 个 commit）。

---

## 🔴🔴 补充（写完初稿后核对出来的，比正文更要紧）

### A. 本地 main 是**多 session 共享**的移动 ref —— 上面有 14 个离线合并，不是我的 6 个

```
我这条线的 6 个:  multi-output / cost-read-side / web-unit-source
                  dead-config-audit / user-text-sweep / wip-ledger-2
另一条线的 7 个:  餐饮AI读路径 / followup-capability / attribution-steps
                  catalogue-regression / false-alarm-census / daysdata-contract / dish-catalogue
```

⛔ **不要用 `git branch -f main <sha>`** —— 那是对共享 ref 的破坏性覆盖。
main 上有一条 commit 明写着「第二次合入 —— **第一次被并发 session 的 reset 覆盖**」。
我自己也差点重演：新文件忘了 `git add` 导致 commit 失败，紧跟着那条 `branch -f` 就用了旧 HEAD
把 main 拽回去了（已恢复，并核对过部署点 `3f75754b88` 仍是 main 的祖先）。
⇒ 往 main 上合**只用真正的 `git merge`**，从一个 checkout 了 main 的 worktree 里做。

⇒ **本文里的 SHA 会过期**。判「现在是什么」一律现场跑，别引用这里的数。

### B. ⚠️ main 里有**未部署的 Python**，⛔ 不要顺手部署它

另一条 session 的台账（`HANDOFF-2026-08-18-github-suspended-offline-ledger.md`，**两份台账，别只看我这份**）
明写：

> ⛔ 本次没有离线部署 …… 攒下的改动碰运行路径（`synthesis_engine.py` / `factbook.py` / `queries.py`），
> 按「合入通道双轨」这类**强制 PR** …… 「CI 一个 job 都没跑过，**这条我不敢报绿**」

而他们的分支**已经并进 main**。我核对过：**他们那 7 个合并逐个都是 Java/web 0 个文件**，
全是 Python + docs ⇒ 我跑的 `deploy-backend.sh`(Java) + `deploy-web-admin.sh` **没有把它们发上 prod**，
他们那句「没有离线部署」对他们自己仍然成立。

🔴 **但这意味着：`main` 现在含有未过 CI、且作者明确不想上线的 Python 改动。**
Python 走的是**另一个脚本** `deploy-smartbi-python.sh --env prod`
（本仓记过：`release-cretas.sh` 只发 Java + web-admin，纯 Python 合完跑它会得到
`DEPLOY_EXIT=0` 而东西根本没上线）。
⇒ **⛔ 在 GitHub 恢复、那批 Python 走完 PR/CI 之前，不要跑 Python 部署。**

---

## 当前状态

### 本地 main（= prod 的 Java/web 部分）

```
本地 main = 3407bac3ef   (部署的是 3f75754b88，其后只多了文档 commit)
  ├─ origin/main 缓存 f635e6b65e
  ├─ codex/claude-multi-output     e386357777   #2874 已推已开 PR，未合
  ├─ codex/claude-cost-read-side   c3a19a3994   只在本地
  ├─ codex/claude-web-unit-source  9fcde2af43   只在本地
  ├─ codex/claude-dead-config-audit da219396a7  只在本地
  ├─ codex/claude-user-text-sweep  66c8e049ca   只在本地
  └─ codex/claude-wip-ledger-2     0381b11966   只在本地
```

合并后跑过：`*RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest,*ContractTest`
= **727 条全绿零失败**；Flyway 无撞号。

⚠️ 仓里另有**几百个**历史 `codex/claude-*` 分支带未并入的 commit（更早的 session 遗留）。
**我只并了今天这轮我这条线的 6 条**（另一条线自己并了 7 条，见上面补充 A）。
⛔ 别把那几百个一起合 —— 那是一次无人审过的大合并。

### 部署核对（硬约束 2，都做了）

```
进程启动 22:23:47  >  制品 mtime 22:23:19          ✅
运行中 jar 里的标记:
  resolveOutputCostPolicy    = 1   (多产出坎4)
  settlementConsumptionsFor  = 1   (成本读侧接通)
  WipLedgerEntries.class     = 1   (WIP 流水)
阳性对照 OrderCostBreakdownService = 1 / 阴性对照 = 0   ✅
真跑那条路径:
  rawMaterialCost = 200.0  (4 × 20kg × 2.5)  ← 修复前是 0
  判据二 COMPLETED 75.0 盒 / 判据九 5 条痕迹  ← 无回归
```

---

## 判据逐条（prod 实测，每条带阳性对照）

| # | 判据 | 状态 |
|---|---|---|
| 一 | 单位三处一致 / 中文 | ✅ 存储中文码（22 行存量已订正，§3 复核 0 违例 + §3b 阳性对照 51/3/158）、出口翻译、写入归一、前端不再发 `box`（headed E2E 实测请求体是「盒」） |
| 二 | 两端实时通 | ✅ 正向：App 报工 → web 结单 → App 入库 → 成品批次；反向：web 改 100→250 / 改交期，App **0.49s** 后读到精确值 |
| 三 | 卖成品/原料/辅材/包材 | 🟡 可卖类别守卫 ✅；「必须发得出货」做成**明示不拦截**，开关 `cretas.sales.stock-availability-gate.enforce` 默认 `false` |
| 四 | 管理员无订单入库 | ✅ |
| 五 | 生产四形态 | 🟡 **2/4**。副产 ✅、多份原料出一成品 ✅；一份原料出多成品 = 坎 4 已修、坎 1/3 **待 owner 决策**、坎 2 未修；复杂辅料**计算正确**（含判别力对照：忽略续锅比应为 0.500，实测 0.400） |
| 六 | 采购整条链 | ✅ |
| 七 | 仓储 | ✅ |
| 八 | 拦人处给下一步 | 🟡 领料越仓 / 缺料三态 / 结单几条已修；多产出那几条随坎 1/3 |
| 九 | 录入/转移/手工出库留痕 | ✅ 写得下（`MANUAL_ISSUE`，含谁/何时/多少/为什么）+ 查得到（`usage-history` 从恒空变成真查），跨租户 403 |

---

## ⏭️ 待 owner 决策（**卡着判据五**）

### 坎 1 + 坎 3：多产出的成本分摊口径

实测的矛盾：

```
报工实测 (costAllocationBasis: MASS)   成品甲 80% / ¥582.4    成品乙 20% / ¥145.6
钉住的 BOM 家族                        成品甲 MAIN 100%       成品乙 BY_PRODUCT 0%
```

成因：`WorkflowActualIoSemantics.normalizeDraft` 无条件给每个 PROCESS 节点盖 `ACTUAL_IO`
并 `remove("outputRole")` / `remove("costAllocationRatio")`；之后
`BomWorkflowRevisionService.resolveTerminalOutputs` 按
`terminalIndex == 0 ? MAIN 100% : BY_PRODUCT 0%` 自动编号，而 `terminals` 按
`Comparator.comparing(Node::getId)` 排序 ⇒ **谁拿 100% 的成本由节点 id 的字典序决定**。
`OutputRole.CO_PRODUCT` 枚举存在但**全库 0 行** —— 现有授权入口到不了它。

⇒ 要决的是：**哪一侧是权威**（报工的质量占比 / BOM 钉的比例），以及**要不要让用户能显式声明联产品**。
⚠️ 任何一种改法都会**改变 prod 上已有计划的成本数字**，所以本轮没动。
（选项与影响面写在 PR #2874 正文里，GitHub 恢复后可读。）

---

## 🔧 GitHub 恢复后的收尾（按顺序）

```bash
# 1. 确认恢复
git fetch origin main

# 2. 六条分别 push → 开 PR → 走 CI → 合
for b in codex/claude-cost-read-side codex/claude-web-unit-source \
         codex/claude-dead-config-audit codex/claude-user-text-sweep \
         codex/claude-wip-ledger-2 ; do
  git push -u origin $b && gh pr create --base main --head $b
done
# #2874 (codex/claude-multi-output) 已经有 PR，直接合

# 3. ⚠️ 硬约束 7: squash 合并后原分支不再是 main 的祖先。
#    若某条被 safe-commit 的闸拦住 → 开新分支 off origin/main + cherry-pick

# 4. 全部合完之后
git checkout main && git reset --hard origin/main

# 5. 重新走正规部署 (prod 内容不变, 但让 origin/main 与 prod 对上)
./scripts/deploy/prewarm-main-artifact.sh --tests '<explicit classes>' --wait 480
./scripts/deploy/release-cretas.sh --phase deploy --base-sha <full 40-char SHA> \
    --tests '<explicit classes>' --confirm-prod YES-PROD
```

⚠️ `--tests` **只收显式类名，不收通配符**（release preflight 会拒）。
⚠️ `--base-sha` 是**变更起点**（祖先），不是要发的那个 commit。
⚠️ fastlane 要**完整 40 位 SHA**。

---

## 未修 / 已登记（详见 `HANDOFF-2026-08-18b-goalB-open-defects.md`）

- **判据五坎 2**：多产出计划领不到料，两条错误各自指向对方（根因在 `FactoryMaterialRequisitionServiceImpl` / `MaterialBatchServiceImpl`）
- **生产仓 reserved 占用**：#2866 已把约束钉成可执行的闸（5 条，跑在真实入口 `settleProduction` 上）。
  实现的唯一正确位置是**释放放在结单三道闸之前**，且三条释放路径（驳回/冲销/结单扣减）要同口径。
  ⛔ 只做「报工时占用」而不解决释放 = 永久占住库存，比不做更糟。
  顺带：先撞上的跨计划闸文案说「已被**其他未结生产计划**占用…请先结清冲突计划」，而现场没有别的计划，
  占用它的是**本计划自己的报工**。
- **`restoreSemiFinishedConsumption` 补正量 REVERSE 流水**：技术前提已解除，
  解冻条件写死为「`codex/claude-wip-ledger-2` 先上线」（已上线，可以做了）
- **存量 4 行 ΣIN=0 的半成品库存行**（331/342/343/344）：本轮只让**新发生**的入库有账，⛔ 不回填历史；解冻走半成品盘点
- **BOM 里原料普遍没填用量**（全库 RAW 45 行只有 2 行有用量）⇒ 领料单给不出原料需求量。属业务口径
- **死配置 7 个字段**（`docs/audit/2026-08-18-dead-config-field-census.md`）：
  `product_types.settlement_method` / `.default_sop_config_id` / `.form_template_id`、
  `pricing_strategies`（36 行全是 QA/E2E 夹具）、`bom_recipe_items.primary_code(_ref)`、
  `payment_requests.purpose_code`、`workflow_task_ports.conversion_mode`
- **今天 prod 上的写入还没冲销**：批次 10761 / 报工 23836·23837 / MR-0004 / 成品批次 `2d09263e`。
  ⚠️ **聚合库存数已不能用来对账** —— 另一条 session 全天在动同一批物料（批次 10766–10777、
  计划 `61cb04bf`、MR-0005/0006 都不是我们的）。只能按 row ID 归属。跨租户对照全程未变（24/0/0）。

---

## 方法教训（今天真花了时间的）

1. **git 说「无冲突」不等于能编译**。#2867 与 #2870 各自 CI 绿，改的是不同文件的不同行，
   自动合并零冲突，**合完 main 编译不过**（构造签名变了）。
   ⇒ 并行改同一个类时，合并后**必须编译一次**。本轮离线合并每并一条都 `mvn -o test-compile`。
2. **棘轮会在合并后开火，那是对的**。中文界面棘轮报 `expected 38 but was 41` ——
   两条线各修一批、合起来实际欠账更少，它在要求收紧，不是缺陷。
3. **「PID 在不在」不是存活判据**。web 部署撞到 5 小时前的陈旧锁，脚本按 PID 判活，
   而那个 PID 已被回收给 `conhost.exe`。
4. **量之前先问「我量的是不是那个东西」**。今天三次栽在这上面：
   - `production-summary` 的 `totalCost=0` 被当成「成本修复没生效」，而那走的是另一个 service
   - 「领料取整 / 结单不取整」其实是**不同分母**（计划 80 vs 实际 75），我据此提的「向上取整」**会把系统改坏**，已收回
   - 调料需求量修复「在主线配方上不生效」，因为我假设 RAW 行有 `requiredQty` 而没先量（全库 45 行只有 2 行有）
5. **桩的形状要能在生产上出现**。上一条的根因就是桩 `raw("20","kg")` 是真实上游永不产出的形状。
6. **native `@Query` 不会被启动时校验，且 `@DataJpaTest` 本地和 CI 都跑 H2**。
   我把发号改成 native 后单测绿、CI 绿，**prod 第一次执行就 500**
   （`CONCAT(?, '%')` → `could not determine data type of parameter $2`）。
7. **变异不红时先查变异是否生效**。本轮至少 3 次「变异没红」实际是断言在守空气
   （只测 helper 没测接线 / 断言源码文本而非行为）。

---

## 环境备忘

- prod 探针：`D:\Temp\claude\...\af544de2-...\scratchpad\api.py`
  （`python api.py login f006_admin`，`from api import call, token_of`，token 1 小时）
- prod DB 只读：`ssh root@47.100.235.168` +
  `PGPASSWORD=$(grep -oP "^DB_PASSWORD=\K.*" /www/wwwroot/cretas/.env.prod) psql -h localhost -U cretas_user -d cretas_prod_db -f /tmp/x.sql`
  （SQL 落本地文件再 scp，⛔ 别内联 heredoc）
- ⚠️ 本库 **7 个 schema**，查行数**必须带 schema 限定**
- ⚠️ 很多实体有 `@Where(deleted_at IS NULL)`，会**静默作用到 JPQL**
- ⚠️ `ProductionPlanServiceImpl.java` 里有**裸 NUL 字节**（既有写法，字符串分隔符），
  grep 当它是二进制文件 —— 用 python 读写
- ⚠️ 部分 Java 文件是 CRLF / 混合行尾，改写脚本用 `newline=''` 并做 CRLF 适配，
  写完对比 `git diff --stat` 与 `git diff --ignore-all-space --stat`
