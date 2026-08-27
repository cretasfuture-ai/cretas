# 交接：F006 全局视觉验收 —— 建了可重复的巡检，抓到 4 个真缺陷，也抓到我自己 4 次误报

> 这份只回答四件事：**接手第一件事做什么 / 什么已经查清了别再查 / 我错在哪 / 什么只有 owner 能做。**
> 上一棒：`HANDOFF-2026-08-27-nightly-ratchet-first-real-catch.md`（Java 存量红 143 → 0）。

---

## 🔴 接手第一件事：先看生产部署到哪一版了

**本轮所有巡检读数都量的是生产，而生产落后 main。** 实测：

```
生产 web-admin 资产 mtime   2026-08-27 16:42 (+8)
#255（修双 /api/mobile 前缀）  2026-08-28 03:29 (+8)   ← 晚于生产构建
```

⇒ 巡检看到的那个 403 是**旧包**，代码早修好了。

**⛔ 拿巡检结果报缺陷之前，逐条对 `origin/main` 核一遍**，否则会报一个已经修好的东西。
这句话已经写进 `menu-sweep.mjs` 的文件头，但它拦不住不读文件头的人。

同理，下面「待查」里的 `completeness/compute` 500，**必须先核生产 Python 跑的是哪一版**
再去看代码 —— main 上那个文件里的两处 `int()` 都作用在 COUNT 聚合上，对不上那个报错。

---

## 建成了什么：`scripts/e2e/production-readonly/menu-sweep.mjs`

把「全局视觉验收」从**一次性手点**变成**可重复跑批**。

```bash
E2E_USERNAME=... E2E_PASSWORD=... \
node scripts/e2e/production-readonly/menu-sweep.mjs \
  --base-url https://admin.cretaceousfuture.com [--offset N] [--limit M] [--only 关键词]
```

- 179 屏逐个打开 → 等 SPA 真正挂载 → 取证 → 截图
- **零写入**：复用 `core/mutation-guard.js`，非白名单写请求在发出之前被拦
- 判定：`OK` / `FORBIDDEN`(403，RBAC 正常，**正向证据**) / `GROUP_NO_INDEX`(分组节点无 index) /
  `WRITE_ON_LOAD` / `HTTP_ERROR` / `CONSOLE_ERROR` / `BLANK` / `NOT_FOUND` / `NOT_MOUNTED` / `TOOL_ERROR`

⚠️ **全量约 18 分钟，而后台任务 10 分钟会被杀** ⇒ 用 `--offset N --limit M` 分两段跑。

### 覆盖面的边界（有意的）

路由取自 `menuConfig.ts`（侧边栏），**故意不含那 83 条孤儿路由**。
直接敲 URL 去测它们恰好会**免疫「入口缺失」这一类缺陷** —— 本仓踩过：
5 条孤儿路由的 E2E 是绿的，而没有任何用户走得到那些页面。

### 最后一次完整读数（179 屏）

```
OK 122 · FORBIDDEN 49 · GROUP_NO_INDEX 2 · WRITE_ON_LOAD 2 · HTTP_ERROR 2 · NOT_FOUND 1 · CONSOLE_ERROR 1
```

⚠️ 那 1 条 `NOT_FOUND`（`/sales/returns`）**后来被证明是误报**，已修（#267）。

---

## 🔴 我错在哪：同一个根因，误报了四次

**报告的价值全在它不说假话。** 一份 9 条缺陷里混着 3 条假的，
下一个人会开始怀疑另外 6 条 —— 那时这份报告就废了。

| # | 我报的 | 真相 | 修在 |
|---|---|---|---|
| 1 | **49 屏「空白」** | 是规规矩矩的 **403 访问被拒绝**页（`factory_super_admin` 越权访问平台/餐饮路由）。403 页正文恰好 36 字符，低于我设的长度阈值 ⇒ **差点把「RBAC 正常工作」报成「49 屏坏了」** | #259 |
| 2 | `/workdesk`、`/ops` **404 缺陷** | `/workdesk` 在 router 里**根本没有这条路由**（纯分组节点，点父级只展开子项）；`/ops` 配着 `hideForFactoryTypes:['FACTORY']` 且 `AppSidebar.vue:176` 确实在执行 ⇒ 对 F006 **根本不在侧边栏里** | #266 |
| 3 | `/sales/returns` **404 缺陷** | **那页好好的** —— 7 单真实退货数据。成因：判据写成 `/页面不存在\|404/`，**裸 `404` 命中了表格里的 UUID** | #267 |
| 4 | 5 屏 **`NOT_MOUNTED`** | 单独打开 `/production/plans` **2.2 秒**就挂载完（同时刻首页响应 0.5s）⇒ 是那一轮**浏览器上下文卡住**。⚠️ 同一轮里**连续多条** NOT_MOUNTED 尤其可疑 | #268（判定文案里标注「单独重跑确认后再当缺陷」） |

**四次同一个根因：判据太松，拿宽松 token 去匹配任意页面文本。**

现在的判据是「**错误页专属中文短语** 且 **正文短(≤80)**」，并配了两条阳性对照
证明**收紧之后没把真的 403/404 一起放掉** —— 那是收紧判据时最容易犯的错。

### ⚠️ 还有两次不在巡检器上的自坑

- **用未认证的 401 判端点是否存在**：`curl` 打两个前缀都返 401，看着像「两个都在」。
  网关对未认证请求一律 401 ⇒ 那读数分不出真假。换成页面里已登录的 token 才拿到 `404 / 200` 的真对照。
- **转义过 shell 被吃掉**：用 heredoc 改 `.ts` 时 `\n` 写成真换行、`\b` 写成**字面退格字节 0x08**，
  把源码搞坏。⇒ 含转义的内容**直接落盘重写**，并加控制字节自查（硬约束 6）。

---

## ✅ 真缺陷（已修并合入）

### 1. 财务主管工作台的主区块永远是一句拒答（#262 + #263）

用户打开 `/workdesk/finance-manager`，「月度经营摘要」下面是：

> 「月度财务复盘」是写入/修改操作，执行前需要确认。

**这个工作台的核心功能一天都没工作过。** 两层成因，各修一半：

- **#262 根因**：`WriteGuardService.hasWriteSuffix` 按后缀判写，`WRITE_SUFFIXES` 里有 `"_CLOSE"`
  ⇒ `MONTHLY_FINANCIAL_CLOSE` 被判成写意图。但那个 CLOSE 是「月度**复盘**」不是「执行结账」：
  DB 里 `sensitivity_level='LOW'`、`tool_name=NULL` 走 Skill、Skill 串的 **8 个 Tool 在权威清册里 8/8 全是 READ**。
  ⇒ 按仓里已有先例（`REVENUE_REPORT_GENERATE`）登记**具名例外**，⛔ 没放松后缀表，
  并配 `MonthlyFinancialCloseReadOnlyContractTest` **守住豁免的前提**：
  谁往那条 Skill 里加一个写 Tool 就红。
- **#263 另一半**：前端那一行 `formattedText || message || '(无输出)'` **完全没看 `status`**，
  任何非成功回执都被当答案渲染。判据收敛成 `utils/intentResponseStatus.ts`
  （状态清单是从**本来就写对的** `AIQuery.vue` 抄的）。其余 6 个工作台同一写法，
  登记成**清单式棘轮**只禁增长，⛔ 没盲改。

**影响面远不止一个工作台**：`IntentKnowledgeBase` 把「本月营收」「今日营收」「本月业绩如何」
等**纯查询短语**都短路到这个意图 ⇒ 任何人问这些话都拿不到数。
而 `NegationTwinPolicy` 那道「读形态问句命中写意图就把读的提上来」的网，
**被短语短路绕过了**（它只在候选重排时生效）。

### 2. 字段映射复核整页拿不到数据（#264）

`MappingReviewQueue.vue` 用 Java 的 base 去调一个 **Python** 端点：

```
/api/mobile/F006/smart-bi/mapping-review/pending              -> 404
/smartbi-api/api/mobile/F006/smart-bi/mapping-review/pending  -> 200 {"success":true,"data":[]}
```

改成仓里既有约定（`/smartbi-api` 前缀 + `baseURL: ''`）。生产实证修后 200，
且 `canonical-fields` 真返回了字段字典。

### 3. 只读白名单缺 2 条（#265）

`list-summary/attendance`（后端自己标注为「读性质 POST」）与
`client-requirement/completeness/compute`（整个文件 0 处写操作）。
⛔ 仍逐个实体登记，没写成 `list-summary/.*` 通配。

---

## 🔴 待办：需要 owner 裁定或先核环境

| 项 | 状态 |
|---|---|
| **GitHub 定时触发没在工作** | 两个仓 `event=schedule` 总数 **0**，5 次 tick 全空，**整点混淆项已排除**（canary 用 `17,47` 也不触发）。最可能是账单（托管 job 2 秒失败，注解 "recent account payments have failed or your spending limit needs to be increased"）。**⛔ 只有 owner 能在 Billing 页面确认。** 在此之前夜间棘轮一直靠手工触发 |
| **2 屏加载即自动调用通用 AI 执行器** | `/workdesk/finance-manager`、`/finance/sku-margin`。当次路由到的都是读意图，**但执行什么由运行时决定** —— 同一机制在财务工作台上选中的就是一个被判为写的意图。`ai-intents/execute` **故意不在只读白名单里**，这条策略应当保留 ⇒ 需要产品裁定「页面该不该在加载时把自然语言交给通用执行器」 |
| `completeness/compute` 生产 500 | `invalid literal for int() with base 10: 'OVy1IC0'`（对非数字业务 ID 做 `int()`）。⛔ **先核生产 Python 版本再看代码** |
| `/sales/returns` 渲染裸 UUID | 「客户」「源订单」两列显示 `3e8cc819-0d5b-…` 而不是客户名/订单号。与 #257「8 行产品名渲染成裸 UUID」同族 |

---

## 写入验收：生产链走通了（F006）

按 F006 协议做的（写前报备、写后回读、`E2E-ACC` 标记）：

| 环节 | 实测 |
|---|---|
| 建计划 | 选成品后**自动固定工序路线**，报工模式自动切逐道 |
| 第 1 道 | 投 原料A 100kg → 产 半成品 82kg，**出成率自动 82.00%**，生成 `CLK-W-20260828-31710` |
| 第 2 道 | 上游批次自动带出、产出单位自动 kg→**盒**（显示 800g/盒） |
| 单位换算 | 102 盒 ⇒ **出成率 99.51%**（102×0.8÷82）—— 本仓踩过「单位契约半迁移」，这里**算对了** |
| 追溯链 | 成品批次 ← 来源批次 ← 领用 82kg / 占比 100%，**完整** |
| 防呆 | `正式报工` 默认禁用；确认框逐字说明「扣什么、入什么、只扣一次不重复扣」 |

**写入的东西**（都带 `E2E-ACC` 标记，可安全清理）：
`PLAN-1787852674752-1816CDF2` · `CLK-W-20260828-31710`(82kg) · `PB-PLAN-…-41401`(102盒)

**顺带抓到 2 个真缺陷**：
1. 生产计划列表新建后**底部汇总行不刷新**（表头/分页变 20，汇总仍显示 19，刷新才对）
2. 生产批次**归组行「实际数量」恒为 0**（子批次是 10，归组显示 0；**存量数据可复现**，不依赖我写的那条）

⚠️ 销售 / 采购 / 仓储链**还没走**。

---

## 派并行 agent 时必须写进 brief 的（沿用上一棒，一条没减）

⛔ 不许用 `git stash`（多 agent 共享同一个 `.git`，栈是共享的）
⛔ 不许碰 `known-failing-tests.txt`（并发冲突，由协调者统一削）
⚠️ CI 绿不能证明修好了，**本机读数才是证据**
⚠️ 源码扫描型测试类名必须 `ContractTest` 结尾
⚠️ PR body 用 `--body-file`（反引号会被 bash 当命令替换执行）
⚠️ `git commit -- <paths>` 装不下新文件 —— 未跟踪文件先 `git add -N`

＋本轮新增两条：
⚠️ **拿生产读数报缺陷前，逐条对 `origin/main` 核一遍**（生产落后 main）
⚠️ **含转义的内容直接落盘重写，⛔ 不要过 shell heredoc**（`\b` 会变成字面 0x08 字节）
