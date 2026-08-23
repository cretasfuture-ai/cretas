# 交接手册 · 2026-08-19（web/App 全链路修复 + 离线部署轮次）

> **给下一个 session 的完整上下文。** 读完这一份就能接着干，不需要翻别的。
> 需要细节时再按第 9 节的索引去翻单项文件。
>
> **写于** 2026-08-19 深夜 ｜ **本地 main** `bd83687734` ｜ **GitHub 仍处于停用状态**

---

## 0 · 30 秒版

我们在给**白垩纪食品溯源系统**做一轮「客户实际用出来的问题」的集中修复。

来源有两条：
1. **prod 后端报错普查** —— 从线上日志里挖出真实 bug（不是测试噪声）
2. **客户的 Google 表格** —— 60 行客户/测试人员报的问题（`main` tab + `工厂app` tab）

**今天做完并且已经上线**：4 个线上真实 bug + 约 28 项表格问题 + 一批体验改进。
**部署走的是离线协议**（GitHub 账号被停用，push/fetch/gh 全线 403）。
**今晚最后解决的是 OTA**：现场 6 台设备里 5 台九天收不到更新，已经在真机上证实修好了。

**接下来最该做的**：第 6 节那三件（表格标注 / #4·#9 拍板 / 登记未做项）。

---

## 1 · 项目速览

| 组件 | 技术栈 | 端口 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA(Hibernate 6) | 10010(蓝) / 10020(绿) |
| web-admin | Vue 3 + Element Plus + Vite | — |
| App | Expo 53+ / React Native + TypeScript | 3010(dev) |
| AI 服务 | Python + FastAPI | 8083 |

- 主工作目录 `C:\Users\Steve\my-prototype-logistics`
- prod 服务器 `root@47.100.235.168`，部署根 `/www/wwwroot/cretas`
- **prod 数据库只读** —— 查可以，写不行

---

## 2 · ⛔ 先读这一节：本轮踩过的坑

**这一节是本文件最有价值的部分。** 下面每一条都是**今天实际栽进去过**的，
不是理论风险。它们的共同形状是：**判据是绿的，而结论是错的。**

### 2.1 仪器类（我量错了东西）

| # | 长相 | 后果 / 怎么发现的 |
|---|---|---|
| 1 | **两条日志流被重复计数** —— `cretas-backend-error*.log` 是 `cretas-backend*.log` 里 ERROR 的**副本**，我 glob 了两个 | **所有绝对数都是 2 倍**。靠「逐日相等」(8/8, 386/386) + INFO 阳性对照(1140399 vs 0)才证死。⇒ **只用 error 流** |
| 2 | **中文串当 jar/bundle 标记读出 0** | `strings` 提不出 Java class 常量池 / Hermes 字节码里的多字节中文。阳性对照（prod 上印了 28 次的串）也读 0 才暴露。⇒ **标记一律用 ASCII** |
| 3 | **包路径写错**（`service/impl/` 实际是 `service/reversal/impl/`） | 阳性对照同样读 0 ⇒ 被对照抓住 |
| 4 | **awk 时间窗过滤恒真** —— `Caused by:` 行按字典序排在 `2026-…` 之后 | 得出「窗口 0 行但命中 34 次」这种自相矛盾的读数。⇒ 过滤时**必须要求行首是日期** |
| 5 | **`grep -F "--cretas-cursor-…"`** 里的 `--` 被当成选项 | 需要 `grep -F -- "…"`。阳性对照（本轮之前就存在的变量）读 0 暴露的 |
| 6 | **`--reporter=basic`** 这个 vitest reporter 根本不存在 | `EXIT=1` 来自**我的参数**，不是测试失败 |
| 7 | **grep 排除了含答案的那个文件** | 我断言 `dashboardExecutor` 的 8 个方法「0 个调用方」，而排除列表里正是那个含全部 8 个调用的文件。⚠️ 结论侥幸仍成立，但成因完全不同（自调用绕过 Spring 代理 ⇒ `@Async` 是 no-op，整个「并行」仪表盘其实是串行的） |
| 8 | **`git log --all -S`** 在本仓超时（5 分钟） | 别用它做全历史搜索 |
| 9 | **闸匹配到了自己的注释** | 我的修复注释里原样引了那段代码 ⇒ 变异脚本 `replace(…, 1)` 改的是注释不是代码。新闸里加了 `stripComments()` |
| 10 | **`vue-tsc --noEmit` 在本仓检查 0 个文件** | 那条命令**永远绿**，它什么都没看。⇒ 别拿它当判据 |
| 11 | **gviz 跟随表格的当前筛选** | 所以它的行号和真实行号**不是固定偏移**。改用 `/export?format=csv&gid=…`（不受筛选影响），并用 3 个截图确认过的锚点(行 3/11/13) + 一条阴性对照校验 |

### 2.2 流程类（工作会悄悄丢）

| # | 坑 | 判据 |
|---|---|---|
| 12 | **agent 的 worktree 是基于【冻结的 origin/main】开的** | 于是它的 `git diff main..分支` 会把 main 领先的**几百个文件**列成「我要改的」，全是假的。⇒ **问「我改了什么」要问自己的 commit**（`git show --name-only`），不要问两分支之差 |
| 13 | **agent 干完活不提交** | 今天两次差点丢成果。⇒ **每轮 agent 结束后扫一遍所有 worktree 的 `git status`**（第 8 节有现成命令） |
| 14 | **Windows 上 `mklink /J` 共享 `node_modules`** | worktree 清理会把**主仓的** node_modules 一起掏空。⛔ 绝对禁止 |

### 2.3 对客户实时表格的两次误写（都已完全回滚）

1. 点了 (30,104) 以为是名称框，其实是 **A1** ⇒ 把「F2」写进了表头「时间」
2. 名称框输 `F4`，因为**筛选生效**跳到了可见的第 11 行；而那格有下拉校验，敲字后回车**选中了高亮的第一项「已解决」** ⇒ 行错了、值也错了

**两次都用 API 核对过 0 差异地回滚了。** ⇒ **⛔ 永远不要盲写这张表**，
写之前必须用不受筛选影响的 CSV 导出确认行号。

### 2.4 归因类（我的假设被证伪）

- **409 冲突**我猜是软删号码生成器 —— **错**。`uk_po_factory_order` 从未冲突过，
  真因是一个**合法的拒绝**，而 `apiClient.ts` 把它的错误信息丢了
- **物料行分类是三态不是二态** —— 有个 id 既不在 `product_types` 也不在 `raw_material_types`
- **`created_by=0`**（`ProcurementSuggestionService.SYSTEM_USER_ID = 0L`）是**紧急的**不是已登记的：
  它之所以停了是因为上游坏了，而**今天的 PP 修复把它重新激活了**
- **2026-08-05 那个白色指针修复自上线起就是安慰剂**（Vue scoped 0-3-0 胜过 global 0-2-0），
  而它的测试注释断言了相反的事，且从没被测量过
- **「单据追踪回不去」这个前提是错的** —— `7fc50ffb72`(#2071, 07-31) 是**按客户自己的话**故意移除那个跳转的

---

## 3 · ✅ 今天做完并且已上线的

**三轮部署，四个目标，全部带判据验证过**（新旧 jar 交叉对照 + 阳性/阴性对照 + 进程启动时间）。

### 3.1 线上真实 bug（从 prod 日志普查出来的）

| Bug | 修法 |
|---|---|
| **软删除让凭证号生成器恒撞号** | `VoucherRepository` 的 JPQL 改成 **native SQL**（`@Where` 不会拼进 native） |
| **SmartBI 缓存写入撞唯一键** | 加 `findIncludingDeletedByFactoryIdAndCacheKey`(native) + `setDeletedAt(null)` 复活 |
| **`plan.setCreatedBy(1L)` 硬编码导致 FK 违约** | 从 `event.getApprovedBy()` 一路传下来；为 null 时抛 `IllegalStateException`；`save`→`saveAndFlush` 让「自动创建生产计划」那条日志不再说谎 |
| **4 个凭证监听器丢弃 merge 结果** | `repo.save(x)` → `x = repo.save(x)` |
| **`@Async("dashboardExecutor")` 指向不存在的 bean**（8 处） | 改成 `reportExecutor` |

**新增的闸**（全部做过变异验证）：
`SoftDeleteBlindSpotContractTest` / `StaleDetachedMergeContractTest` / `AsyncQualifierResolvesToRealBeanContractTest`

**还建了**：`async_task_failures` 异步失败台账（含 Flyway 迁移 + 定时扫描器）

### 3.2 Google 表格的问题（约 28 项）

包括 AI 工序助手抽参、回答字数受限（三档深度预算）、App 销售订单缺字段、
英文未翻译（含后端车辆状态枚举）、单据追踪返回定位、白色指针、滚轮横向滚动等。

### 3.3 OTA —— 今晚最后解决的

**问题**：现场 6 台设备里 **5 台跑 1.0.3 二进制**，而我们把 bundle 推进 1.0.4 树
⇒ 它们每次拉取都是 `outcome=no-update`，**九天零送达**。

**根因**（这是 8-09 那次事故的本体）：一个 APK 里有**两个版本号**——
`versionName`(管强更判断) 和原生 `strings.xml` 里的 `expo_runtime_version`(管选哪棵 bundle 树)。
8-09 只改对了前者。⇒ 症状是版本显示正常、强更正常，**只有 OTA 悄悄失效**。

**解法**（owner 拍的板）：**往 1.0.3 树也推一份 bundle**。
动手前量过：从那个 APK 的构建基线到现在，`android/` 除版本号外零改动、原生依赖零改动
⇒ 当前 JS 跑在旧原生上不缺方法。

**真机端到端证实**（小米 M2102K1AC / Android 14 / ADB 连着）：

```
设备侧   Check → CheckCompleteAvailable(isUpdateAvailable=true) → Download
         Manifest code signing signature verified successfully
         重启后 CheckCompleteUnavailable ⇒ 已是最新
         两次冷启动进程都活着, 0 条 FATAL / 0 条 NoSuchMethod

服务端   19:26:55  outcome=update     current=344a19b9
         19:29:20  outcome=no-update  current=cd1f90ea   ← hash 真的变了
         同期主力现场设备 121.228.208.154 也拿到 outcome=update
```

▎**`current` 变了**才是判据 —— 「推送成功」不等于「送达」，这正是九天零送达那次缺的那一条。

---

## 4 · 环境状态（接手前必须知道）

### 4.1 🔴 GitHub 仍然是停用的

```
origin/main 冻结在   f635e6b65e
本地 main            bd83687734
本地领先             237 个 commit
```

**部署协议**（离线期）：

```bash
# 汇合点是【本地 main】, 不是 origin/main
git worktree add --detach ../cretas-deploy-offline $(git rev-parse main)
cd ../cretas-deploy-offline
SKIP_GIT_CHECK=1 ./scripts/deploy/deploy-backend.sh  --env prod
SKIP_GIT_CHECK=1 ./scripts/deploy/deploy-web-admin.sh --env prod --confirm-prod YES-PROD
```

⛔ **绝对不要 `git push origin main`** —— 恢复后要各分支走 PR。
⛔ 不要从 feature 分支部署 prod（那是 5-30 事故的形态：固定共享 jar 路径，last-write-wins）。

**恢复后要做的对账**：领先的 237 个 commit 里碰了源码的分布是
`backend/python` 199 / `backend/java` 157 / `web-admin/src` 84 / `frontend/CretasFoodTrace` 16，
**这些都必须走 PR**（按「合入通道双轨」规则）。docs/`.claude/` 类可以走 fastlane。

### 4.2 worktree 现状

**143 个 worktree** —— 已经很多了，值得清理一轮：

```bash
./scripts/safe-worktree-remove.sh --all-merged     # 只删已合并+干净+未锁定的
pwsh -File scripts/check-worktree-junctions.ps1    # 顺便扫危险 junction
```

⛔ 摘 junction 只能用非递归删除，`rmdir /S` 会穿透过去把 target 删空。

**已核实无遗失**：唯一一个未合并的分支 `worktree-agent-abc7c5eea0f4565d9`(2 commits)
的改动**逐字都已在 main 里**（被 squash 进 `38993509f2` #2775），main 反而更新。可以删。

### 4.3 设备与凭证

- **有一台小米手机通过 ADB 连在这台电脑上**（M2102K1AC / Android 14），
  装的是 `com.cretas.foodtrace` versionName **1.0.4**（但 runtime 报 1.0.3，见 3.3）
  ⇒ **「装起来跑一次」这条判据现在可以做了**，之前一直做不了
- `~/.ota-env` 里有 `OTA_ADMIN_TOKEN` —— **source 它，永远不要读或打印它的值**
- prod 数据库**只读**

---

## 5 · ⛔ 硬约束（本仓的，违反了会出事）

这些在 `.claude/rules/` 里有完整版，这里只列最容易犯的：

1. **诊断输出一律落文件再查** —— ⛔ 禁止 `cmd | tail -N` / `| head -N`
   （管道会截断你要看的那一段，**而且会把退出码替换成 tail 的**，失败被读成成功）
2. **多步流水线用 `&&` 串，不要用 `;`** —— 闸拦住一步，后面的步骤不知道它被拦了
3. **含转义字符的内容不经过 shell** —— ⛔ 禁止内联 heredoc 写 python/sql，用 Write 直接落盘
4. **部署判据是「在运行中的东西里找到这次改动的标记」** ——
   `DEPLOY_EXIT=0` 不是判据，`RELEASE_FINAL_STATUS` 也不是。而且**找到标记 ≠ 它能跑**，
   要真跑一次那条路径
5. **每条「零/没有」的读数都要配阳性对照** —— 否则分不清「没发生」和「仪器坏了」
6. **变异要先证明它落盘了**（grep 一次），再从红/绿下结论
7. **改共享结构前先数，改完再数，两个数必须相等**
8. **squash 合并之后本地分支必须重开**（cherry-pick 到干净的 origin/main 分支）
9. **commit 用 `git commit -m "…" -- 文件1 文件2`** 或 `./scripts/safe-commit.sh`
   （并发 session 会污染 staging 区）

---

## 6 · 🎯 接下来要做的（按优先级）

### P0 · 等 owner 一句话就能开工的

#### (a) #4 / #9 的范围拍板

- **#4 操作按钮统一**（按功能配色 / 位置固定 / 不可用灰掉加删除线）—— 实测涉及 **174 个视图**
- **#9 弹窗统一从右侧滑入 + 统一宽度** —— 实测涉及 **690 处**

**要 owner 定的是**：一次性全改，还是**先做 3–5 个高频模块当样板**
（建议：销售订单 / 采购订单 / 生产计划 / 物料需求单）。

**我给的建议是先做样板**，理由：一次性全改的风险是改出 174 处**新的**不一致，
漏改的那几处会比原来更刺眼；而先做 5 个，方案不对只白改 5 个页面。

#### (b) 表格标注

只有 **行 61**（鼠标在 workflow 画布/调整表格宽度显示白色）需要从「正在解决」→「已修复待验证」。

其余相关行（**13 / 15 / 16 / 23 / 24 / 33 / 43**）本来就标着「已修复待验证」——
只是**在今天之前那个标注不成立**，现在才是真的。⇒ 状态不用改。

⚠️ **行 62**（表头上方也要横向滚动条）**不要动** —— 只做了滚轮那个子项。

⛔ 要写这张表必须先用 `/export?format=csv&gid=…` 确认行号（见 2.1#11 和 2.3）。

### P1 · 已登记但没做的（有清单，可以直接开工）

| 项 | 说明 |
|---|---|
| **5 处 `setCreatedBy(1L)`** | 今天只修了 orchestrator 那一处，同族还有 5 处 |
| **18 个潜伏的号码生成器** | 软删除盲区家族，共 20 个，今天修了 4 个（含凭证），其余已分类登记 |
| **`created_by=0`** | 🔴 **紧急** —— `ProcurementSuggestionService.SYSTEM_USER_ID = 0L`。它之前不炸是因为上游坏了，**今天的 PP 修复把它重新激活了** |
| **`material_consumptions` 的 SHORTFALL 后续** | 本体修了，但那个写进 FK 列的 `SHORTFALL` 哨兵值（自 2026-03-18 起）后续没做完。「那个 FK 一直在替我们挡这笔假账」 |
| **`sales_order_items` 126/167 孤儿行** | **未核实**，需要先查清 |
| **`SecurityUtils.getCurrentUserId()` 永远返回 null** | 影响面未评估 |
| **RN 销售页调 `/active` 而不是 `/sellable`** | |
| **`getVehiclesByStatus` 把坏状态吞成 `List.of()`** | |
| **约 70 张其它私有状态表** | 与车辆状态枚举同族 |
| **`reverted_txn_ids` 台账那条腿从未接线** | C 卡已写；**G3 方案 A 卡在这上面** |

### P2 · 可以做但不紧急

- **重建 APK** —— **不再紧急**（OTA 双推已经解开死局）。价值降成「以后不用双推」，
  代价是所有用户要重装 + 风险是再发一个坏 APK。**建议放到下一次有计划的发版**。
  ⚠️ 真要做：`build-apk.sh` 是 macOS 专用的（写死 `/Library/Java/…`、`$HOME/Library/Android/sdk`），
  这台是 Windows / JDK 21（脚本要 17）/ `ANDROID_HOME` 未设（SDK 在 `~/AppData/Local/Android/Sdk`）。
  **做完必须在那台 ADB 设备上装起来跑一次** —— 上一个 APK 通过了所有既有判据却一启动就崩，
  因为**没有一条判据是「把它装起来跑一次」**。
- **worktree 清理**（143 个）
- **GitHub 恢复后的 237 commit 对账**

---

## 7 · 已经拍过板的决定（不要重新讨论）

| 事项 | 决定 | 理由 |
|---|---|---|
| **batch 10761** | **不动数据** —— 23836/23837 留着，75.00 盒留着 | 那天报了 4 轮撤了 3 轮，最后一轮数据自洽（75 盒的 `updated_at` 精确等于 23837 建成那刻）。代码修复(`REVERSAL_SUPERSEDED` 409)止住复发。**残余风险**：若真相相反则多了 2 条报工 + 75 盒，但只有车间能消掉，且量级是**一个批次** |
| **不问车间** | owner 决定自己定，不再去问 | |
| **OTA 1.0.3 双推** | 做了，已证实 | |
| **APK 重建** | 推迟到下次发版 | 见 P2 |

---

## 8 · 常用命令

```bash
# 每轮 agent 结束后: 扫未提交的成果 (今天两次差点丢东西)
git worktree list --porcelain | grep '^worktree ' | sed 's/^worktree //' | grep "agent-" > /tmp/_aw.txt
while read -r w; do n=$(git -C "$w" status --porcelain 2>/dev/null | wc -l); \
  [ "$n" != "0" ] && echo "🔴 $(basename $w): $n 未提交"; done < /tmp/_aw.txt

# 未合进 main 的分支
for b in $(git branch --list 'worktree-agent-*' --format='%(refname:short)'); do \
  n=$(git rev-list --count main.."$b" 2>/dev/null); \
  [ -n "$n" ] && [ "$n" != "0" ] && echo "🔴 $b: $n ahead"; done

# 并发安全提交
./scripts/safe-commit.sh "feat: 说明" 文件1 文件2

# 离线部署 (从本地 main 的 detached worktree)
cd C:/Users/Steve/cretas-deploy-offline && git fetch . main && git checkout -q $(git -C C:/Users/Steve/my-prototype-logistics rev-parse main)
SKIP_GIT_CHECK=1 ./scripts/deploy/deploy-backend.sh --env prod

# 部署后判据 (新旧 jar 交叉对照; 标记必须 ASCII)
ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar '<class>' | strings | grep -c '<ASCII标记>'"

# 表格: 拿不受筛选影响的真实行号
curl -s "https://docs.google.com/spreadsheets/d/1TyIqNP_z8bBiPyLeXbr3YYvwE1WCuHFhgYAHiJC4w4M/export?format=csv&gid=0"
```

---

## 9 · 文件索引

| 文件 | 内容 |
|---|---|
| `HANDOFF-2026-08-19-prod-error-census.md` | prod 报错普查全过程 + 分类 + 每条的判据 |
| `HANDOFF-2026-08-19-offline-deploy-ledger.md` | **三轮离线部署的账**（部署了哪个 commit、含哪些改动、跑了哪些迁移）—— GitHub 恢复后逐条核销 |
| `HANDOFF-2026-08-19-batch-10761-reconciliation.md` | 10761 的完整对账 |
| `HANDOFF-2026-08-19-ota-dead-for-5-of-6-devices.md` | OTA 诊断（**头部已注明结论作废**，诊断部分保留留痕） |
| `.claude/rules/measurement-and-wiring.md` | 量对东西 / 接上没有 / 变异打在被守的行为上 —— **最重要的一份** |
| `.claude/rules/concurrent-edit-safety.md` | 并发编辑安全 |
| `.claude/rules/worktree-and-main-only-deploy.md` | worktree 隔离 + 只从 main 部署 + **离线协议** |

---

## 10 · 接手第一件事

```bash
cd C:/Users/Steve/my-prototype-logistics
git log --oneline -1 main                    # 应该是 bd83687734 或更新
git status --short | head                    # 应该干净
git rev-list --count origin/main..main       # 237 = GitHub 还没恢复
```

然后问 owner 第 6 节 P0 的两件事（#4/#9 范围、表格行 61），或者直接开 P1 里的
**`created_by=0`**（那条是紧急的，且今天被重新激活了）。
