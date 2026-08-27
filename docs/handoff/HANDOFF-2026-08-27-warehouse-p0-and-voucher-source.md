# HANDOFF 2026-08-27 —— 仓储 P0 收尾 + 凭证来源口径

**上一棒**：`claude-code/wh-p0-isolation`（本文作者）
**交接时状态**：**干净停止点** —— 0 个未合 PR，本轮建的 worktree 全部清理，
16 个 PR 已合入 `main` 并**全部部署 prod 验收通过**。没有在飞的构建/写入/合并中间态。

⚠️ 与今天另外两份交接件**不重叠**，但相关：

| 文件 | 覆盖 |
|---|---|
| `HANDOFF-2026-08-27-ci-runner-and-13pr-acceptance.md` | CI 为什么不跑（追到底）+ 13 个 PR 本地验收 |
| `HANDOFF-2026-08-27-java-red-burndown.md` | Java 存量红的根因分解 |
| **本文** | **仓储业务侧**：交接件 §6 的 P0 逐条收尾 + 凭证来源口径 |

---

## 0. 接手前必须确认（30 秒，⛔ 不要跳）

```bash
git fetch origin main
git rev-list --count HEAD..origin/main    # 非 0 就先同步, 别在落后的分支上判断
```

⚠️ 本轮实测：**主工作目录几乎从不在 `main` 上**，且今天 `main` 前进极快
（一次本地构建 191 秒的窗口里合了 2 个 PR）。

**判 prod 跑什么，⛔ 不要看部署树的 `.git`**（那是陈旧 rsync 元数据）。看这两条：

```bash
# a) 活跃服务是哪个槽 —— 蓝绿会切, ⛔ 不要沿用上一次的端口
ssh root@47.100.235.168 "for s in cretas-backend cretas-backend-green cretas-backend-blue; do \
  printf '%-24s %s\n' \$s \$(systemctl is-active \$s.service 2>/dev/null); done"
# b) 在运行中的 jar 里 grep 这次改动的标记(带阴性对照)
ssh root@47.100.235.168 "unzip -l /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar | grep -c '<标记>'"
```

**当前 prod**：jar mtime `2026-08-27 21:49:08`，进程启动 `21:49:40`，
活跃槽 **GREEN(10020)**，nginx 上游已自动同步到 10020。

---

## 1. 已闭环：交接件 §6 的 P0

源头是 `HANDOFF-2026-08-25-warehouse-storage-layout-stocktake-book.md`（在
`.worktrees/codex-storage-locations-20260825/docs/handoff/`，**不在 main 上**）。

| P0 | 结论 | 关键证据 |
|---|---|---|
| 跨工厂隔离 | **不是缺陷** | F006 token → F006 **200**(2223B) / → LIUSHANMEN **403** / → MOCK_REST **403** / 无 token **401**；403 措辞正是 `JwtAuthInterceptor.sendForbiddenResponse` ⇒ 拦它的确实是那道闸 |
| 位置校验 | **已修**（PR #184） | 8 处写入点收敛成 `WarehouseLocationCatalog` 一份 + 棘轮闸 |
| 重复审批 | **守住** | 两条 409 **措辞不同** ⇒ 两道不同的闸；库存指纹 `139\|119843.52\|3459.3632` 写前写后逐字相同且**非空** |
| 并发审批 | **结构保证**（PR #196 附带上闸） | JPA `@Version`，⛔ 不是应用层 `if` |
| 未配置流程 | **分情况 fail-closed** | 配了→PENDING；没配+有差异→409；没配+零差异→APPROVED |

⚠️ **P0.2 那三条 403 有意义的前提**：`factory_super_admin` **不在**
`PLATFORM_ADMIN_ROLES`（只有 `super_admin`/`platform_admin`/`developer`）。
不确认这一点，那三个 403 可能只是被白名单放行的假绿。

---

## 2. 往下多走的四步（都已部署）

| PR | 做了什么 | ⚠️ 接手要知道的 |
|---|---|---|
| **#184** | 储存位置改成**受控词表** | 交接件写「原料/包材复用旧接口」，按那个描述改只覆盖 3 处 —— 实测是 **8 处**，漏掉的是 `SalesServiceImpl`（**成品的第二条写路**） |
| **#192** | 盘点提交进 OA 时带上**可判的差异量** | 此前手写 `Map.of` 三个字段 ⇒ 阈值分级**不是没人配，是配了也没得判** |
| **#196** | 半成品盘点**对齐 fail-closed** | 它 `apply` **不只调库存还过财务凭证**；与原料用**同一个** decision_type ⇒ 一条审批流接两种单子 |
| **#211** | 一条「现状钉住」改成守新行为 | 形态 C‴：**断言好好的，是需求变了**。症状是「红得理直气壮」，最易被读成「我改错了」而回退代码 |
| **#220** | 凭证**只收生产领用** | ⚠️ `MATERIAL_RETURN` **必须留在凭证里** —— 金额为负，是领用的**冲减**。照字面实现会让成本虚高 631.40 |

---

## 3. 待 Owner 裁定（四项，**都不需要改代码**）

| # | 事项 | 现状读数 | 定了之后怎么做 |
|---|---|---|---|
| ① | **盘点阈值定数** | 变量已能判（`shortageCount` / `shortageQuantityByUnit['kg']` …） | 配一个 condition 节点即生效 |
| ② | **LIUSHANMEN 配 `INVENTORY_ADJUSTMENT_APPROVAL`** | 全生产只有 F006 配了（08-25 23:15 发布） | ⛔ 非 F006 租户**禁止写入**，需 Owner 自己配 |
| ③ | **F006 调拨要不要审批** | 10 张单，**已批准的 7 张全部 `approved_by == requested_by`**，6 张已 CONFIRMED（货已动）。因为 F006 没配 `INVENTORY_TRANSFER_APPROVAL` | 配置操作 |
| ④ | **4 个不设 `sourceType` 的消耗写入点** | `MaterialBatchServiceImpl:2034` / `ProcessingServiceImpl:812` / `ProductionPlanServiceImpl:7607` / `OrderCostBreakdownService` 透传。生产库当前 0 行 NULL **只是巧合** | 每个都要裁定它写哪种来源 |

🔍 **调拨已查过，不是同一类缺陷**（⛔ 不要重复调查）：
`approveTransfer` **库存语句 0 处**，库存在 ship/confirm 才动；
`approvedBy = 发起人` 与采购一致（采购也保留 `approvedBy = initiator`，
只把 `financeReviewedBy` 置空，因为那是「财务**这个角色**看过了」的声明）。**代码不改。**

---

## 4. 已登记的独立观测（`OBS-JAVA-TEST-SELECTOR-COVERAGE-20260827`）

**1435 个 Java 测试类里只有 229 个在 CI 窄选择器内，1206 个（85%）跑不到。**

⚠️ 今天一天因它栽两次：一条「现状钉住」断言**在 main 上红了很久没人看见**；
我新写的两道闸类名不合规，**只编译不执行**。

⚠️ 元闸 `GateSelectorCoverageContractTest` **只管「读 src/main 源码的闸」**，
不管普通行为测试 ⇒ **它绿 ≠ 那 1206 个跑过**。

⛔ 不要靠一个个改类名去补（用类名兼任 CI 选择器本身就脆）。
⛔ 也不要一次性放开——会暴露未知数量的存量红，先量再定节奏。

---

## 5. 🔴 本轮反复咬人的三个形态（每条都带当天长相）

### ① 截断把「没有」变成「我没看到」

- `tail -26` 把 `INVENTORY_ADJUSTMENT_APPROVAL` 那行切掉 ⇒ 差点得出
  「一条盘点审批流都没配」的**反向**结论（F006 的行按 decision_type 排序时它在 Q 之前）
- 同一天在部署日志、CI 日志上各栽一次

⇒ **判存在性一律全量落盘再查，⛔ 不加 `head`/`tail`。**

### ② 闸写了但从没执行

- 我新写的两道闸类名不以 `*ContractTest` 结尾 ⇒ **只编译不执行**
- 之后每次都去 CI 日志里 grep 类名确认它真跑了，本轮验到三次

⇒ **新增测试/闸之后，去 CI 日志里 grep 那个类名。** 「本地绿」不等于「CI 跑过」。

### ③ 仪器瞄错对象

- 精确路径 `git cat-file -e` 查文件 → 读出「main 上没有」，其实只是**路径变了**
- 扫 `setSourceType` → 扫出 `SALES_ORDER`（那是**提醒**的字段）同时**漏掉**真正的写入点
- 按上次的端口查健康 → 000，差点误判成故障（蓝绿切槽了）
- 第一版幂等探针**没传 `expectedVersion`** → 400「必填字段」，请求**从没走到**守卫，
  「mutation=0」是真的但**理由是错的**
- 库存指纹用了**不存在的列** `remaining_quantity` → 两次都是空串，「空==空」恒真

⇒ **每一次都是阳性/阴性对照把我拦下来的。** 动手前把
「我想知道的是 X ／ 我实际在量的是 Y」并排写一遍。

---

## 6. 部署路上的两个坑（下次会再遇到）

**① `RELEASE_FINAL_STATUS` 一次都没出现 = 失败信号**（不是「大概成功了」）。

本轮第一次发布 `DEPLOY_EXIT=1`，日志里 `BUILD SUCCESS` 但没有 `RELEASE_FINAL_STATUS`。
读发布报告（`~/.cache/cretas/release-reports/*.json`）查出真因：
`java.build=success` 但 `java.deploy=**not-selected**` ——
`ensure_exact_main_after_artifacts` 在制品建完后复查发现 `HEAD ≠ origin/main`。

**根因**：东京中继 `10.66.66.1:22` 不通 ⇒ 预热必然跳过 ⇒ 每次都本地构建（191 秒）
⇒ 那近 4 分钟的窗口里别的 session 合了 2 个 PR。**闸拒得对**，但这个窗口今天很容易撞上。
⇒ 建议：发布前先 `git fetch && git checkout --detach origin/main`，构建完若被拒就**再同步一次重跑**。

**② 蓝绿会切槽。** 本轮两次部署分别落在 **10010** 和 **10020**。
⛔ 判健康前先从 `cmdline` 确认这个进程监听哪个端口，不要沿用上次的。
（`/actuator/health` 返回 404 是正常的 —— 这套用的是 `/api/mobile/health`。）

---

## 7. 工作区与授权硬边界

- ⛔ **不在主工作目录直接干活**，每个任务开独立 worktree，**永远 off `origin/main`**
- ⛔ **非 F006 租户在生产环境的业务写入必须为 0**（含 LIUSHANMEN / MOCK_REST）
- F006 写入需 Owner 在**当前任务**明确授权，且写前必须用**实时登录响应**证明
  `factoryId == 'F006'`，写前写后各计一次数（见 `AGENTS.md` 第 13 条）
- ⛔ 不要 `mklink /J` 共享 `node_modules`（Windows worktree 清理会连坐掏空主仓）
- prod 只从 `main` 部署；`release-cretas.sh` 自己会校验 clean HEAD == origin/main

---

## 8. 下一步建议顺序

1. **先拿 ①③ 两个裁定**（阈值定数、F006 调拨要不要审批）—— 它们都只是配置，
   但没有它们，#192 发出去的那些变量没有消费方。
2. **④ 那 4 个不设 `sourceType` 的写入点**逐个裁定 —— 现在靠「生产库恰好 0 行 NULL」
   撑着，那不是保证。
3. **`OBS-JAVA-TEST-SELECTOR-COVERAGE`** 需要先量「一次性放开会红多少」再定节奏，
   ⚠️ 与 `HANDOFF-2026-08-27-java-red-burndown.md` 的存量红分解**是同一件事的两头**，
   接手时应当合并看，⛔ 不要各查一遍。
