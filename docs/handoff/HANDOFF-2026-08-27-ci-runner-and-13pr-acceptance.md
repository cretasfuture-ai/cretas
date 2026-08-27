# HANDOFF 2026-08-27 —— CI 为什么不跑（追到底）+ 13 个 PR 的本地验收

> 上一轮交接件是 `HANDOFF-2026-08-27-blacklake-reachability-round.md`（可达性一轮，13 个 PR 全 OPEN）。
> 这一轮做两件事：**把 CI 不跑的成因追到底**（结论与上一轮不同），
> **把 13 个 PR 合成一棵树逐项验收**（结论：四个栈只有一条红，而它是个待拍的产品决策）。

---

## 0. 立刻要知道的

### ① 基底一直在动 —— 引用读数必须带 SHA

```
上一轮交接件写的   bdc75a54f5
本轮开始时         bdc75a54f5   (#157)
本轮验收所用基底   67617b409d   (#158 已合)   ← 下面所有读数都是在这个点上量的
本轮结束时         d6cf27fbca   (#160 已合)
```

⚠️ 这批 PR 落后 main **29~32 个 commit**。动手前一律 `git rev-list --count HEAD..origin/main`。

### ② CI 接回来了 —— #143 与 #159 都已合入，main 上是绿的

```
d9f9682914  fix(rn): 「上海今天」…每天 16:00 之后返回明天 (#159)
1246604531  ci: 三个 PR 闸改用自建 runner (#143)
```

`d9f9682914` 上 main 的两道闸实测：**RN Gate ✅ success / Secret regression gate ✅ success**
（跑在 `canada-mac-mini` 上，有真实时长，不是 2~4 秒的空转）。

⚠️ **只有 3 个工作流切了过去，还有 12 个起不来**：

```
已切自建   rn-gate.yml  secret-regression.yml  web-admin-gate.yml
仍托管     ci.yml  daily-integration.yml  e2e-post-deploy.yml  e2e-pr.yml
           java-full-nightly.yml  jpa-gate-main.yml  kb-drift-check.yml
           python-gate.yml  threshold-parity-check.yml  tool-inventory-gate.yml
           tool-isolation-audit.yml  web-dist.yml
```

那 12 个在托管额度用尽下 ⇒ **job 2~4 秒、0 个 step、无日志**地失败。

▎**判据是时长**：2~4 秒 = job 根本没启动；有真实时长 = 跑了、失败在内容。
▎ 别把「没启动」读成「测试红了」——`gh pr checks` 两者都显示 fail。

### ③ 🔴 runner 不在 139，在一台加拿大的 Mac mini

```
gh api orgs/cretasfuture-ai/actions/runners
→ 14  canada-mac-mini  macOS  online  self-hosted,cretas,macOS,ARM64   （全组织唯一一台）
```

三个 job 的 `runner_name` 逐个核过；工作目录 `/Users/rex/actions-runner/`。
139 上 `systemctl list-units --all | grep -i runner` 为空，
`/root/actions-runner`、`/opt/actions-runner`、`/www/actions-runner` 都不存在。

⇒ 三个 yml 里那段「139(4 核/7G) / 服务侧已加 Nice=10 CPUWeight=50 MemoryMax=3G /
实测 vitest 4分41秒 峰值 1.2G CPU 350%」**描述的是一台没在跑这些 job 的机器**。
在真正跑的那台上实测：**jest 173 套件 1920 用例 18 秒 / vitest 451 文件 29 秒**。

⚠️ 描述过期比没有描述更糟 —— 它让下一个人跳过核实。已在 #143 留言，未动别人正在写的文件。

---

## 1. 🔴 换机器炸出来两个真实缺陷 —— 同一个形状

▎**时区显式算完，最后一步又漏回本机分量。**
▎托管 runner 是 UTC，这类缺陷在 UTC 上**恰好抵消** ⇒ CI 从来碰不到它。
▎是换到 UTC−7 的机器才现形的。

### (a) RN `currentShanghaiIsoDate` —— PR #159

```ts
// AIChatScreen.tsx:81  (旧)
return formatDate(new Date(Date.now() + 8 * 60 * 60 * 1000));
//     ↑ formatters.ts:94 读的是 getFullYear()/getMonth()/getDate() —— **本机**分量
```

先加 8 小时、再按本机时区读日历日 ⇒ **只有设备处于 UTC+0 时才对**。
真实用户在 UTC+8 ⇒ 两段偏移叠加 = UTC+16。在一台 UTC+8 机器上实测（与用户同偏移）：

```
上海 15:59 -> 2026-08-31 ✅     上海 16:00 -> 2026-09-01 ❌     上海 23:00 -> 2026-09-01 ❌
```

后果在同文件的 `parseRestaurantAgentLaunch`：`today` 跳到明天 ⇒ `currentMonth` 跟着跳月 ⇒
**每月最后一天 16:00 之后** `startDate !== ${currentMonth}-01` 恒成立 ⇒
餐饮老板的「毛利下降归因」运行卡**静默不渲染**（不报错、不提示）。
其余日子则是 `endDate > today` 这道防未来日期的闸被放松一天。

修法：抽出 `src/utils/shanghaiDate.ts`（「固定时区的日历日」在 RN 侧的唯一定义），
推 8 小时后读 **UTC** 分量。⛔ 没用截断 ISO 串 —— `noUtcDayTruncation` 闸禁掉了那个写法，
而且截断写法会让下一个人把这里读成「取 UTC 日」。

⚠️ 这条缺陷**无法靠「在本机跑一遍」发现** —— 本机是 UTC+8 时，坏掉的那版一天里仍有
16 个小时是对的。变异对照实测：把实现换回缺陷版，
`AIChatScreenRestaurantAgent` 那 20 条**仍然全绿**（当时是上海 03:xx），
只有新加的钉边界单测转红。**集成套件守不住它。**

### (b) web-admin `formatLocalDate` —— 另一 session 已修在 #143 里

`aiOrderNormalization.ts:19` 用 `Intl`（带 timeZone）算好分量之后，
又 `new Date(Date.UTC(...))` 交给读本机分量的 `toDateString` ⇒ 西半球差一天。

### (c) 同族的测试侧假设

`analysisDefaults.spec.ts` 的阴性对照写着 `if (getTimezoneOffset() !== 0)`，
**假设「非 UTC 就是东边」**。在 UTC 的托管 runner 上它一直被跳过（等于一直没守东西），
在 UTC−7 上才第一次执行、并且失败。已由另一 session 在 #143 修。

---

## 2. 13 个 PR 的整体验收

**验收树 = `origin/main` 67617b409d + 13 个 PR 全合。**
逐个本地试合（`git merge-tree`）：**只有 #126 冲突一处** ——
`ProcessTaskListScreen.tsx` 里 `formatPlannedAt`(#118) 与
`TaskCounts/NO_COUNTS/tabLabel`(#126) 纯相邻新增、零名字碰撞，两边都留即可。
其余两两干净，与上一轮交接件一致。

| 栈 | 判据 | 验收树 | 基线 (origin/main) |
|---|---|---|---|
| RN | 裸 `npx jest`（权威命令） | 187 suites · **2 failed** / 2069 passed | — |
| RN | `tsc --noEmit` | exit 0 | — |
| web | `npx vitest run` 全量 | **453 passed** / 1 skipped · 3694 tests · exit 0 | — |
| web | `vue-tsc -b --force` | 0 error | — |
| Java | BOM 目录 50 个类（同命令、串行） | 385 run · **红 16** | 385 run · **红 60** |
| Java | #141 闸 + Flyway 唯一性 + 选择器覆盖闸 | **18/18 全绿** | — |

⚠️ **阳性对照两条，都必须做**：
- `tsc --noEmit --listFiles` 确认真扫了 **1329** 个 src 文件（不是空转）。
- `vue-tsc -b --force`：注入一条 TS2322 → **exit 2 并点名该文件**，撤回用整份快照写回、
  md5 逐字节核对。本仓记过 `vue-tsc --noEmit` 在这个仓里**一个文件都不查** ——
  所以它的「exit 0」必须先证明它在查。

### 唯一的红：#146 的棘轮，触发它的**只有** #123

二分过：`#146 单独` / `+#116` / `+#150` / `+#156` **全绿**，`+#123` **当场红**。
把闸的报告导出来（`ROLE_REACH_REPORT=<path>`）逐行比：

```
#123 前   ManagementScreen L167   (!item.adminOnly || isAdmin)                        恒为真   1 条
#123 后   ManagementScreen L198   (item.adminOnly && !isAdmin)                        恒为假
          ManagementScreen L186   (section.items.every(i=>i.adminOnly) && !isAdmin)   恒为假   2 条
```

1 条换成 2 条 ⇒ **36 → 37 越预算**；同时 #146 那条阳性对照断言 detail 含「恒为真」，
现在拿到「对全部 2 个可达角色恒为假」⇒ 两条一起红。

⛔ **这不是 #123 的缺陷。** 读 #123 的实现，两个分支的设计都是对的（不按权限过滤、
整区全锁才隐藏，理由写在注释里）。它们之所以恒假，**只因为 ManagementScreen 目前
只有 `permission_admin` / `platform_admin` 走得到**（#146 另一条断言 `√` 确认了这一点）。

⇒ 这就是上一轮交接件 §3A 后半段那个没拍的问题：**是权限表写多了，还是菜单漏了？**

| | 做法 | 棘轮 |
|---|---|---|
| (a) 这屏就是管理员专用 | 摘掉 `locked` / `allLocked`（它们成了死代码） | 回到 36 |
| (b) 非管理员本该进得来 | 修可达性，两个分支自然变成活的 | 回到 36（判定变 LIVE） |
| (c) 暂不拍 | 棘轮抬到 37，并**显式登记理由**（硬约束 8 的「故意不改」要留痕） | 37 |

⚠️ 另外 #146 那条阳性对照断言的是**字面**「恒为真」，属于形态 C‴（守着旧行为）。
按本仓规矩应当**从守字面抬到守性质**（「这个分支是空转的」）+ 补阴性对照，
而不是简单把「恒为真」改成「恒为假」。

---

## 3. Java 侧：#142 的「修好 44 条」独立复现了

逐条比**名字**（⛔ 不比总数 —— 总数相同不排除一换一）：

```
新增失败 (comm -13):  0 条
已修复   (comm -23): 44 条
60 - 44 = 16 ✓
```

`AiCapabilityHonestyGateTest` 7/7、`UnsupportedCapabilityToolTest` 6/6、
`GateSelectorCoverageContractTest` 4/4、`FlywayVersionUniquenessTest` 1/1 ——
#141 那个 `V20261031_02` 没撞号，选择器覆盖闸在多了两个新测试类之后仍然守得住。

### 🔑 #142 挂账的 4 条 activation，被 **#153** 修掉了

已修复名单里有这四条：

```
BomRecipeServiceImplAddItemTest.activationRejectsEmptyDraft
BomRecipeServiceImplAddItemTest.activationRejectsPackagingWithoutQuantity
BomRecipeServiceImplAddItemTest.activateArchivesEveryCompetingActiveVersion
BomRecipeServiceImplAddItemTest.archivedVersionCanBeReactivated
```

而 #142 的 PR body 明确挂了账：「⛔ 用户看到的应该是哪一条报错，不该由我改测试来定」。
查下来是 **#153** 干的 —— 它除了标题说的前端吞 409，还改了 `src/main` 的
`BomWorkflowRevisionService.java`，顺带把这条报错路径理顺了。

▎**两个 PR 谁都没单独关掉的事，合在一起关掉了。**

⚠️ 但这意味着上一轮 §3C 那个「用户该看到哪条报错」的决策，**已经被 #153 事实上拍了**。
那条原本是留给 owner 的 —— 请确认它落的是不是想要的那一条。

### 剩下 16 条全是存量红，其中 8 条从没被量过

```
BomRecipePackagingSpecInferTest    8 条  ← §3B 待拍的「包装推算退役」决策
BomItemYieldRateTest              4 条  ← ❌ 在 #142 的选择器量程之外
BomSeasoningWorkspaceServiceTest  4 条  ← ❌ 同上
```

基线上同样红 ⇒ 不是这批 PR 引入的。且 **13 个 PR 里没有任何一个碰过这三个测试类**。

⚠️ 后 8 条值得单独记：#142 的基线命令是
`-Dtest='...,BomRecipe*Test,ProductionPlan*Test,...'`，
`BomRecipe*Test` 匹配得到 `BomRecipePackagingSpecInferTest`，
**匹配不到** `BomItemYieldRateTest` / `BomSeasoningWorkspaceServiceTest`。

▎**「57 → 13」在它自己的选择器里成立；BOM 目录里还有 8 条红是那个选择器从来没看过的。**

---

## 4. 通配符选择器造出来的盲区 —— 今天第三次

| # | 哪道闸 | 盲区长什么样 |
|---|---|---|
| 1 | CI push 选择器 | 4 个模式只匹配 57 条红里的 1 条 |
| 2 | nightly 全量基线 | 声明 10747 个 `@Test`，那次只跑了 **2866**（约 27%） |
| 3 | #142 自己的验收选择器 | `BomRecipe*Test` 看不见另外 8 条红 |

▎**它们都不报错，只是安静地少看一部分 —— 长得像「这里没有红」。**

判据：**报一个「红了几条」的数时，把选择器一起报**；换过选择器的两轮之间，数字可比性为零。

---

## 5. 🔴 那份 CI 基线只覆盖约四分之一的 Java 套件

```
known-failing-tests.txt  (CI artifact, run 32932028221, sha dffd6311f1):
    本次共跑 2866 个测试, 其中 29 个红
仓库实际声明:  1421 个测试类 / 10747 个 @Test   (@Disabled 只有 18 处 / 5 个类)
pom.xml surefire:  includes = *Test.java / *Tests.java / *IT.java, **没有任何 excludes**
```

⇒ 执行数与声明数差约 **4 倍**，「29 红」不是全量读数。
这也解释了基线里 **BOM 条目为 0** 而 #142 说的是「57 条长期红」——那 2866 条里没有 BOM 那批。
workflow 自己头部那句「跑 2866 条才崩，而后者**崩得像成功**」，说的正是这个数。

⚠️ 数 `@Test` 时注意：裸 `grep -o '@Test'` 会把 `@TestConfiguration` 这类前缀也数上
（10761 vs 加边界后的 10747）。**读数得是量对的那个。**

---

## 6. CI 侧的其它事实（owner 说之后走本地 runner，本轮未动）

| | 事实 |
|---|---|
| `java-full-nightly.yml` | GitHub 上是 **`disabled_manually`** ⇒ 那个 cron **一次都没触发过**（3 次运行全是 `workflow_dispatch`） |
| `tool-inventory-gate.yml` | 同样 disabled，**0 次运行** |
| `web-dist.yml` | 同样 disabled |
| `known-failing-tests.txt` | **CI 已经生成好了**，在 run 32932028221 的 artifact 里（未过期）。缺的只是「取出来提交进仓库」——workflow 头部写的正是这个流程 |
| push 选择器 | `*RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest,*ContractTest`；`GateSelectorCoverageContractTest` 已经把这个约定机械化了 |

⚠️ 上一轮交接件把成因归到「选择器窄 + 名单不存在」，**漏了更硬的一条：闸本身是关着的。**

---

## 7. #156 视觉验收（补完了 —— 上一轮 §10 第 4 条）

环境沿用上一轮：桩 `10099` + Expo web `3010`（从 `cretas-dispatcher` 起，`.env.visualcheck`）。
localStorage 种一个**构造的** dispatcher 会话（token 是明显假串）。

| 入口 | 落到哪 |
|---|---|
| 顶栏 `plan-tools-entry` → 排程时间轴 | **PlanGantt**（甘特图视图） |
| → 紧急插单 | **UrgentInsert** |
| → 合并同类批次 | **MixedBatch**（打开了，随后崩，见下） |
| → 产线与设备 | **ResourceOverview**（资源总览） |
| 首页铃铛 | **AlertList**（告警管理）✅ 不再是 NotificationList |

抽屉与设计一致：顶栏只多一个图标，四项各带一句人话说明。

### ⚠️ 上一轮说的「渲染器冻住」—— 不是冻住，重试一次就好

`Page.captureScreenshot` 会间歇性 30s 超时，但**同一时刻** `javascript_tool` 读 DOM
完全正常（title / innerText / 节点数都拿得到）。**第二次截图就成功**，
本轮踩 4 次、每次重试都过。⇒ 上一轮因此停在「只差截图」，其实只要再截一次。

### ⚠️ MixedBatch：响应形状不对就整个 App 白屏

```
TypeError: groups.filter is not a function   → React 树整个卸载, document 只剩 15 个节点
```

成因链：桩对未知路径返回 `data: {}` → `MixedBatchScreen.tsx:62` 的守卫是
`if (response.success && response.data)`（**真值**判断，`{}` 是真值）→ 塞进一个非数组 →
`:258 groups.filter(...)` 崩。

▎**要说清楚**：真实后端 `MixedBatchController.getGroups` 返回 `groups.getContent()`（List），
▎**这条路在正常后端下走不到**，⛔ 不报成线上缺陷。

但脆弱点是真的：守卫该用 `Array.isArray(...)`，而失败形态是**整个 App 白屏**、不是错误态 ——
按本仓「禁止降级处理，明确显示错误」，白屏不算「明确显示错误」。
这个脆弱点是 MixedBatchScreen 自带的，**#156 只是让它第一次有人走得到**。

---

## 8. 本轮踩的坑

1. **`gh run view --log` 拿不到日志** —— `results-receiver.actions.githubusercontent.com`
   解析到 `198.18.0.55`（代理 fake-IP 段）后超时。
   ⇒ 改用 `gh api repos/.../actions/jobs/<id>/logs`（走 blob 域名，多数分片能通）；
   还不行就把签名 URL 转给 139 用 `curl` 取（URL 有效期约 10 分钟）。

2. **Windows 上 Node 不认 `TZ` 环境变量** —— `TZ=America/Vancouver node -e ...`
   输出仍是本机时区。⇒ 验时区缺陷只能用「固定时间戳 + 手算」，别指望改 TZ。
   ⚠️ 这一步是**阳性对照救的**：先打印确认 TZ 没生效，才没据此得出错误结论。

3. **先看错了 Flyway 目录** —— `db/migration/` 的最大版本是 `V20260429`，
   而真正在用的是 `db/flyway/`（已到 `V20261031_01`）。差点把 #141 的 `V20261031_02`
   报成「未来日期、编号错」。⇒ **判版本号先确认那个文件在哪个目录。**

4. **`git checkout -B` 会把自己的 worktree 切走** —— 在 PR worktree 里做二分，
   最后必须切回来并 `git status` 确认干净。

5. **⛔ `grep -c $'\x00' <file>` 查不了 NUL 字节** —— bash 在 `$'...'` 里遇到 `\x00`
   会把字符串**截断成空串**，于是命令退化成 `grep -c ''`，**匹配每一行**。
   本轮实测：对一个干净文件返回 `331`（= 总行数），差点被读成「331 处 NUL」。
   阳性对照戳穿它：`printf 'ab' | grep -c $'\x00'` 也返回 `1`。

   ```bash
   # ✅ 让 tr 自己解释八进制, 不经过 bash 的 $'...'
   a=$(wc -c < "$F"); b=$(tr -d '\000' < "$F" | wc -c); echo "差=$((a-b))"   # 0 = 没有 NUL
   # ✅ 或者直接问 file: 有 NUL 会报 data/binary, 干净的报 "UTF-8 text"
   ```

   ⚠️ 这与 #119/#155 修的是同一个坑的另一面：**查 NUL 的工具自己被 NUL 的转义坑了。**

6. **并发**：`#143` 全程有另一条 session 在推进（三个 commit 落在本轮期间）。
   发现的方式是 `git status` 报了一个我没改过的文件。⇒ 动别人的 worktree 前先 `git status`，
   撞车就停手、改用留言协调，别接着写。

---

## 9. 建议的下一步

1. ~~合 #143~~ ✅ 已合（`1246604531`）。
2. ~~合 #159~~ ✅ 已合（`d9f9682914`）。合之前与 #143 撞了同一个文件 ——
   两边修的是同一个缺陷，取的是**并集**：
   保留抽出的 `utils/shanghaiDate.ts`（读 UTC 分量）⇒ 被禁的截断写法从代码里消失 ⇒
   **`noUtcDayTruncation` 闸那条具名豁免也随之摘掉**，回到零豁免；
   并给「豁免清单不许过期」那条测试补了 `ALLOWLIST.length === 0` ——
   否则清单为空时 `for` 循环零次迭代，是个恒真式（形态 B′）。
   ⚠️ 保留本分支实现的唯一理由：**main 那版有修复但没有任何测试会在它退化时变红**
   （实测：换回缺陷版，`AIChatScreenRestaurantAgent` 20 条在上海 03:xx 仍全绿）。

3. **⛔ 把三个 yml 里关于「139(4 核/7G) / systemd Nice=10 CPUWeight=50 MemoryMax=3G /
   实测 vitest 4分41秒」的那 15 行改掉** —— 见 §0③，它们描述的是一台没在跑这些 job
   的机器，而且**已经随 #143 合进 main 了**。
4. **把剩下 12 个工作流也切过去**（或按 owner 的计划换本地 runner）——
   在那之前，那 12 个的 fail 都要按「没启动」读，⛔ 不要读成「测试红了」。
5. **拍 §2 那条决策**（#123 的 `locked` / ManagementScreen 可达性）——
   它是 13 个 PR 里唯一的红。
6. **确认 §3 里 #153 事实上替 owner 拍掉的那条报错口径**。
7. 剩下的按上一轮交接件 §3 / §6。
