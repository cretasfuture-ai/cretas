# 交接：F006 全量 179 屏视觉验收跑完 + 两个「从上线起就没工作过」的缺陷

> 只回答四件事：**接手第一件事做什么 / 什么已经查清了别再查 / 我错在哪 / 什么只有 owner 能做。**
> 上一棒：`HANDOFF-2026-08-28-f006-visual-acceptance.md`。

---

## 🔴 接手第一件事：换账号，别用凭证文件里那个

上一棒的巡检凭证**已经失效**，而失效的长相是「登录 401 用户名或密码错误」：

```
.codex/rules/test-credentials.md 里的 E2E_USERNAME  ->  g8testmr42keww 这类临时账号
库里查:  is_active = f   （已停用）
```

⇒ 用 `f006_admin` / `123456`（`factory_super_admin`，F006，权限 `*:*`，实测 200）。

⚠️ **那次失败的退出码是 `2` 不是 `1`** —— 巡检器正确地报了「没量到」而不是假绿。
接手时看到 `SEG_EXIT=2` 就该去查仪器，⛔ 不要当成「跑完了没问题」。

---

## 全量读数（179 屏，六段拼接无缺口）

```
OK 128 · FORBIDDEN 44 · WRITE_ON_LOAD 2 · NOT_MOUNTED 2 · GROUP_NO_INDEX 2 · HTTP_ERROR 1
```

分段覆盖 `60+22+25+25+25+22 = 179`，与路由总数吻合。

⚠️ **不要和上一棒那份（OK 122 / HTTP_ERROR 2 / NOT_FOUND 1 / CONSOLE_ERROR 1）直接比** ——
换了账号、判定器也改过，**口径不同**，只能当方向看。

### 两条 `NOT_MOUNTED` 都是误报（单独重跑已证伪）

| 路由 | 批量跑 | `--only` 单独跑 |
|---|---|---|
| `/workflow` 个人 OA | 30017ms 未挂载 | **OK** |
| `/workdesk/production-manager` | 30019ms 未挂载 | **OK** |

⇒ 与上一棒记录的形态一致：**同一轮里出现 NOT_MOUNTED，先怀疑浏览器上下文卡住**。
判定文案里已经写着「单独重跑确认后再当缺陷」，这次照做了，省下两条假缺陷。

### `WRITE_ON_LOAD` ×2 和 `GROUP_NO_INDEX` ×2 都是已知项

`/workdesk/finance-manager`、`/finance/sku-margin` 加载即调通用 AI 执行器 —— **待 owner 裁定**（见下）。
`/workdesk`、`/ops` 是分组节点，用户点父级只展开子项，无害。

---

## ✅ 真缺陷（已修，各带闸）

### 1. `completeness/compute` 生产 500 —— 密码里的 `/` 把 DSN 拼坏了（#273，已合入并部署）

上一棒把这条挂着，并写了「先核生产 Python 版本再看代码」。**核了：部署文件与 main
`md5` 完全相同** ⇒ 「生产落后 main」这条假设排除。真因在别处：

```
db_url = f"postgresql://{user}:{password}@{host}:{port}/{db}"
生产密码以 `XXXXXXX/` 开头 ⇒ `/` 把 netloc 在 `@` **之前**就截断
⇒ 解析成 host=<用户名> / port=<密码首段> ⇒ int(port) 当场抛
```

- **这条路 100% 从没通过**，不是偶发
- 🔴 **`detail=str(e)` 把生产数据库密码的前 7 位（共 48 位）返给了任何已认证调用方**

⚠️ 我最初的排查方向是错的：先去数 `int(` 的调用点，两处都作用在 COUNT 聚合上、对不上报错。
**`invalid literal for int()` + 一个不像数字的短串 ⇒ 先怀疑 DSN 被密码截断**，别去找 `int()`。

修法**不是补第 4 个 `quote()`**（仓里这个 DSN 有 6 份拷贝，3 份记得、3 份忘了）：
asyncpg 侧抽 `_pool_kwargs()` **分参数传**，连 DSN 都不拼 ⇒「哪个字符要转义」这个问题
从物理上消失。SQLAlchemy 侧必须是 URL 串，才用 `quote(pw, safe='')`。

**部署后按判据验过**：制品含标记 / 进程（09:10:08）晚于文件（09:07:25）/
**真跑一次** `POST completeness/compute` → **200 且返回真实数据**（PROCESSING_BATCH 48 条、
MATERIAL_BATCH 140 条）。⛔ 只看 `DEPLOY_EXIT=0` 不算。

⚠️ 同族第二处 `efficiency_recognition/database/repository.py` 也裸拼，且 `POSTGRES_PASSWORD`
同样含 `/`。但查证它在生产**从未被实例化**（成功/失败两条日志分支都是 0 行）⇒ **潜伏，非在燃**。
一并修了，⛔ 没写成「第二个线上故障」。

### 2. 工作流设计器三个动作全部 404（#280）

`/system/workflow-designer` 是本轮唯一的真 `HTTP_ERROR`，顺着查出**三层叠在一起**：

```
GET /rules/state-machines                              -> 200  阳性对照
GET /state-machines/{et}/versions                      -> 404  前端缺 /rules
GET /rules/state-machines/{et}/versions                -> 404  **补上 /rules 仍 404**
```

最后一条是关键：**不是前缀写错一个问题**。`StateMachineService` 实现着
`getVersionHistory` / `publishDraft`，而**没有任何 controller 调它们**。
只补前缀会得到一个仍然 404 的设计器。

⇒ 设计器实际上一直是**只读**的：保存草稿、发布、版本历史三个按钮全是 404。
另有类型谎言：`StateMachine.id` 是 `varchar(50)`，前端标成 `id: number`。

**部署后按判据验过**（#280 已发 prod）：

- Java：活跃 jar 含 `state-machines/{entityType}/versions` 标记；Blue-Green 已切到 **green:10020**
  （查 `:10010` 查不到进程是**对的** —— blue 已停，日志里写着「端口 10010 无残留」）
- **真跑一次**：`GET /rules/state-machines/PRODUCTION_WORKFLOW/versions` **从 404 → 200**
- web-admin：**真用浏览器打开那一屏**，它实际发出的请求已经带 `/rules`

⚠️ 那一屏现在仍报一个 404（`/rules/state-machines/PRODUCTION_WORKFLOW`）——
那是**数据不存在**（F006 还没配这条状态机），前端正是为此标了 `_silent: true`。
⛔ 没有把它加进巡检白名单：那会让这道闸对真的 404 也变瞎。
它会在有人保存第一份工作流后自然消失 —— 而「保存」正是这个 PR 修好的东西。

---

## 🔴 我错在哪

### ① 差点报一个**不会发生**的缺陷

我从代码推出：归档时只改 `publishStatus='archived'`、没置 `enabled=false`，
而加载走 `findBy...AndEnabledTrue` 返回 `Optional` ⇒ 两行都 enabled 时抛
`IncorrectResultSizeDataAccessException`，「发布一次就永久卡住」。

**推理链自洽，但错的。** `saveStateMachine` 是
`findByFactoryIdAndEntityType(...).orElse(new StateMachine())` 的**原地 upsert** ——
每个 entityType 只有一行，那个场景不成立。
否定它的东西就在我没读的那 40 行里。⇒ **不为不会发生的场景写代码。**

### ② 转义被 shell 吃掉，**同一天第三次**

写闸时用 heredoc 传 `//[^\n]*`，`\n` 变成真换行，正则当场报
`unterminated character set`。硬约束 6 说的就是这个，而我又踩了。
⇒ 含转义的内容**用 Write 直接落盘**，⛔ 不过 heredoc。

### ③ 「grep 部署产物」是个会骗人的仪器 —— 差点报「修复没上去」

验 web-admin 时我 grep 服务器上的 `assets/*.js`，结果：

```
含新写法的 chunk: 1 个
含旧写法的 chunk: 6 个     ← 看起来像「改了一半」
```

真相：`assets/` 里躺着 **2693 个 .js**，部署**只加不删**，那 6 个是历次部署的孤儿 chunk
（其中一个 mtime 是今天 09:26，比我这次早 50 分钟）。而入口 loader 里
**新旧 chunk 名一个都搜不到** —— chunk 图更深，grep 根本不是判断这件事的仪器。

⇒ 判「前端改动上没上」只有一条可信路径：**真用浏览器打开那一屏，看它实际发出的请求。**
实测拿到 `/api/mobile/F006/rules/state-machines/...`（带 `/rules`），这才算数。

⚠️ 顺带一条给 owner：`assets/` 累积 2693 个 js 从未清理，旧 bundle 仍可被直接 URL 取到。

### ④ 我的闸自己误报了三次（都记在测试文件注释里）

第一版 DSN 闸是正则，栽了三次：① 匹配到**解释这个坑的 docstring 本身**；
② 把 `smartbi/config.py` 三处**本来就对的**判成违例（上一行已 `quote()`，正则只看得见
`{passwd}`）；③ 漏认 `urllib.parse.quote`（AST 上是 `Attribute` 不是 `Name`）。
⇒ 改走 **AST**，并配三条阴性对照 + 一条「扫过 >100 个 .py」——
否则「0 处违例」与「一个文件都没扫」长得一模一样。

---

## 🔴 需要 owner 裁定 / 只有 owner 能做

| 项 | 状态 |
|---|---|
| **数据库密码轮换** | 那 7 位密码片段从这块功能上线起一直在被返给已认证调用方。已堵，**要不要轮换旧密钥是 owner 的判断** |
| **F006 有 7 个角色账号被停用** | `f006_warehouse_worker` / `f006_quality_mgr` / `f006_cashier` / `f006_dispatcher` / `f006_equipment_admin` / `f006_dept_admin` / `f006_viewer`。⇒ **本轮巡检只覆盖了 super admin 一个角色**，那 7 个角色的屏从未被视觉验收过 |
| **2 屏加载即自动调用通用 AI 执行器** | `/workdesk/finance-manager`、`/finance/sku-margin`。`ai-intents/execute` **故意不在只读白名单里**，这条策略应当保留 ⇒ 需产品裁定「页面该不该在加载时把自然语言交给通用执行器」 |
| **GitHub 定时触发仍未工作** | 两个仓 `event=schedule` 总数 0，整点混淆项已排除。最可能是账单。⛔ 只有 owner 能在 Billing 页面确认 |
| **工作流「版本历史」结构上只有 1 条** | 单行 upsert，没有历史行。端点补上是对的（否则连这一条都拿不到），但要名副其实得改版本机制 —— **产品设计决定，⛔ 不该在修 404 的 PR 里顺手做** |

---

## ⚠️ 我造成的一笔账：main 的 Python Gate 现在是红的

```
8-27 13:43 基线   9586 passed /  0 failed / 173 skipped
8-28 当下 main    9693 passed / 27 failed /  39 skipped
```

我这轮给自建 runner 装了 PostgreSQL（为修 Java IT），**134 条原本自跳过的测试开始真跑，
其中 27 条失败**。它们一直存在，只是被 skip 掩着 —— 又一次「绿 = 没跑」。

- **与任何 PR 的改动无关**：#273 的失败集与 main **逐条同名**，新增 0、修复 0
- 27 条全部落在 **smartbi / 餐饮 / LLM**（`analysis_*_pilot`、`provenance`、`gold_*`、
  `agent_orchestrator`、`price_strip_kpi`），**没有一条在工厂产品这条线上**
- Python Gate **不是必需检查**（#273 在 UNSTABLE 下正常合入）⇒ 它不挡人，是噪音
- Python 侧**没有** Java 那样的名单棘轮。⛔ 也**不要**塞进
  `ci-gate-excludes.self-hosted.txt` —— 那份是「这台机器缺件」台账，而这台机器现在
  **多**了 PostgreSQL，混进去就分不清谁欠的账（那个文件自己写着这条）

⇒ 建议下一棒：照 `scripts/ci/java-test-ratchet.py` 做一个读 pytest JUnit XML 的
Python 名单棘轮，把这 27 条按**名字**记进基线。

---

## 派并行 agent 时必须写进 brief 的（沿用，一条没减）

⛔ 不许用 `git stash`（多 agent 共享同一个 `.git`，栈是共享的）
⛔ 不许碰 `known-failing-tests.txt`（并发冲突，由协调者统一削）
⚠️ CI 绿不能证明修好了，**本机读数才是证据**
⚠️ 源码扫描型测试类名必须 `ContractTest` 结尾
⚠️ PR body 用 `--body-file`（反引号会被 bash 当命令替换执行）
⚠️ `git commit -- <paths>` 装不下新文件 —— 未跟踪文件先 `git add -N`
⚠️ 拿生产读数报缺陷前，逐条对 `origin/main` 核一遍
⚠️ **含转义的内容用 Write 直接落盘，⛔ 不过 heredoc**（本轮又踩一次）

＋本轮新增两条：
⚠️ **`vue-tsc -b` 是增量的** —— 「0 行输出」要配一条阳性对照（故意塞个类型错误看它报不报）
   才分得清「查过了、干净」和「判定为最新、没查」
⚠️ **巡检 `EXIT=2` 是「没量到」不是「没问题」** —— 先查仪器（本轮就是账号被停用）
