# 交接：夜间棘轮的第一次真实读数 —— 49 → 4，外加它抓到的第一批新增红（2026-08-27 夜）

> 上一棒：`HANDOFF-2026-08-27-night-gates-and-red-burndown.md`（判据）
> ＋ `HANDOFF-2026-08-27-java-red-burndown.md`（分类表）。
> 这份只回答三件事：**上一棒那条判据的答案是什么 / 上一棒哪句话是错的 / 接手第一件事做什么。**

---

## 🔴 接手第一件事

**看 2026-08-27 18:00Z（北京 8-28 02:00）那次 `schedule` 事件有没有真的发生。**

它到今天为止**一次都没发生过** —— 这个工作流的全部运行都是 `workflow_dispatch`，
cron 直到 #178（今天 05:00Z）才把它从 `disabled_manually` 打开。

```bash
gh run list -R cretasfuture-ai/cretas --workflow java-full-nightly.yml -L 10 \
  --json databaseId,event,conclusion,createdAt \
  --jq '.[] | [.databaseId,.event,.conclusion,.createdAt] | @tsv'
```

| 看到什么 | 含义 | 动作 |
|---|---|---|
| 有一行 `schedule` | cron 真的接上了 | 往下按棘轮读数正常处理 |
| **还是全 `workflow_dispatch`** | **闸开着但触发没生效**（形态 B 第 N 例） | 回去查 workflow state / 默认分支 / cron 语法，⛔ 别再靠手工 dispatch 顶着 |

⚠️ 这不是形式检查：整套三态机制的价值全在「**每天自动跑一次**」上。
靠人记得手工触发的棘轮，和没有棘轮的区别只是多了一个可以指责的对象。

## 🔴 接手第二件事：一个需要 owner 拍板的二选一（不是一个测试的修法）

基线剩下的 4 条全是 `FactoryConfigServiceImplReorderIT`，它要**真 PostgreSQL**。
2026-08-27 逐条实测：**它当前在任何自动路径上都跑不了，也不可能绿**（详见
`known-failing-tests.txt` 的注释）。⇒ 二选一：

| | 做法 | 代价 |
|---|---|---|
| **(A)** | 在自建 runner 上装原生 PostgreSQL + 在 nightly 里加 bootstrap 步骤 | 给那台 Mac mini 装一个常驻服务（不需要服务容器，Docker 那条路已实测堵死） |
| **(B)** | 维持现状：4 条留在基线里，等托管额度恢复后由 `full_audit` 覆盖 | 这 4 条在此期间**没有任何东西在守** |

⛔ **在拍板之前不要加 `@Disabled` / `assumeTrue` 让它变绿** —— 那会让它在
**真有 PG 的那条路上**也一起沉默（硬约束 4：把「没量到」折叠进「没问题」）。

---

## 上一棒那条判据的答案

上一棒把下一步压成二选一：**49 条转绿 ⇒ 诊断成立，削基线；仍然红 ⇒ 诊断错了。**

**两支都不是。** run `33081570292`（on `9d26e655`，自建 runner）：

```
JAVA_RATCHET 共跑 12007 个测试 · 红 13 · 基线 49
✅ 已修好 44 条（跑了而且通过，可以从基线里删掉）
🔴 新增 8 条基线里没有的红
```

- **44/49 转绿** ⇒ #197（`ddl-auto` `create-drop` → `create`）的 H2 schema 毒源诊断**成立**
- **5 条仍红，而且不是那个根因**（其中 1 条本轮已查清并修掉，见「第二轮」；剩 **4** 条）
- **8 条新增红** —— 棘轮建成以来抓到的**第一批真·回归**

已落地：**#233**（修 8 条新增红）、**#234**（削基线 49 → 5）、**#241**（fan-out 夹具）
＋本 PR（削基线 5 → 4）。

### 收尾判据：合并后又跑了一次，rc=0

run `33085335136`（on `04f0ecbb`，两个 PR 都已合入）：

```
JAVA_RATCHET 共跑 12009 个测试 · 红 5 · 基线 5

没有新增红。
```

三态判定的两个失败分支都是 `skipped`，job `success`。
**没有 `已修好` 段、没有 `没跑到` 段、没有 `新增` 段** —— 失败集合与基线**逐条相等**。

⚠️ 顺带一个独立佐证：测试总数 **12007 → 12009**，正好 **+2** ——
就是 #233 里新增的两条阴性对照。它证明那次跑批**真的跑的是合并后的代码**，
而不是缓存或旧树（硬约束 9：验证「没发生」要配一个「必发生」的读数）。

### ⚠️ 上一棒有一句是错的：「剩下的 49 条全部是同一个根因」

对 5 条不成立。逐条读 CI 报错（用下载的完整日志，⛔ 不是 `gh run view --log`）：

| 条数 | 类 | 真实报错 | 定性 |
|---:|---|---|---|
| 4 | `FactoryConfigServiceImplReorderIT` | `Connection to localhost:5432 refused` | 它要**真 PostgreSQL**（`cretas_r10_it_test`，由 `ci.yml` 的 pgvector 服务容器提供）。而 `java-full-nightly` 没有 `services:`，自建 macOS runner 也起不了服务容器 ⇒ **仪器缺一味环境**，不是产品缺陷 |
| 1 | `SalesOrderFinanceApprovedFanOutIntegrationTest` | 断言 `:215`，轮询到超时，单条 10.17s | ✅ **已查清并修掉（#241）** —— 夹具把审核人 id 写死成 `1L`，外键炸在 `production_plans.created_by`。详见下面「第二轮」 |

🔴 **两条不同的路通向同一个长相**：H2 毒源和「缺 PG」都表现为「本机绿 / CI 红」。
上一棒把这个**长相**当成了根因的证据。
⇒ **「症状分布高度集中」不等于「根因只有一个」** —— 集中的是症状，不是成因。

---

## 那 8 条新增红：两个根因，都在 main 上红了半天没人看见

它们**从合并那天起就红在 main 上**，而 PR 上没有任何东西会执行它们 ——
窄选择器只跑 `*ContractTest` / `*StartupGuardTest` / `*RepositoryQueryValidationTest`
＋逐字列出的 `FlywayVersionUniquenessTest`，**这四个类一个都不在里面**。
（就是 ACTIVE 里 `OBS-JAVA-TEST-SELECTOR-COVERAGE-20260827` 那条观测的第三个实例。）

| 条数 | 根因 | 谁引入的 |
|---:|---|---|
| 7 | `WarehouseLocationCatalog` 是 `@Autowired` **字段注入**；`new MaterialBatchMapper()` / `new SalesServiceImpl(...)` 这种手工装配拿到 `null` ⇒ NPE | #184（储存位置改成受控词表） |
| 1 | 一条断言把三个兼容墓碑一起断言成 `domainTags == ["finance"]`，而 `sales_create_invoice` 已被回源码订正成 `["sales"]` ⇒ 断言守的是**历史** | #185（订正清册） |

**⛔ 第一族刻意没有 mock 掉那个协作者。** mock 会让「填了一个本厂没配过的位置」悄悄通过 ——
而那正是 #184 要拦的东西，**补桩等于把闸补没了**。注入的是**真判据 + 空配置（fail closed）**。
顺带补上这条写路缺的行为级覆盖（#184 加的是源码扫描型闸，不是行为断言）：

- 阴性对照：填「冷冻库」→ 400 且 `save()` 一次都没调
- `null` = 不改动，⛔ 判据不该把已有位置抹掉

**⛔ 第二族订正的是「域标签是哪个」，不是把断言删掉**：WRITE / HIGH /
`finance:read_write` 三项一个字没放松。

---

## 我错在哪 / 差点错在哪

**① 差点读到一次「修复之前」的跑批。**
上一棒写「读今晚 02:00 那次」。照字面去找，最近一次 nightly 跑在 `e99c7112` = **#183**，
也就是 **#197 / #198 之前**；而 `schedule` 事件**一次都没发生过**。
⇒ **拿一次跑批当判据之前，先确认它跑的是哪个 commit。**「最近一次」不等于「改动之后那一次」。

**② 「本机跑绿」这次是弱证据，我仍然跑了 —— 但没拿它当判据。**
删基线的 44 条本机本来就是绿的（上一棒写明「对这一类没有证明力」）。
本机重跑 44/44 绿只钉住一件事：**这 44 个名字确实还存在、还是那 44 个**。
真正的判据是棘轮自己的口径 + 那次 CI 跑批。

**③ 削基线之前先查了「幽灵」。** 49 条在源码里逐条都还在（49/49 类文件 + 方法名存在，
阳性对照用一个假类名正确判为不存在）。所以 `vanished` 段为空是**有意义的空**，
不是「反正也匹配不上」。#223 那个拆分在这里第一次真正派上用场。

**④ 两条变异都先证明变异到达了被测代码，再看红不红。**
改 `SalesServiceImpl` 那行之后，阴性对照红了，**并且 happy path 的配置桩变成
`UnnecessaryStubbing`** —— 后者是额外的独立佐证：判据真的被调用了。

---

## 第二轮：5 → 4，(b) 那条查清了 —— 两个猜测都不对

`SalesOrderFinanceApprovedFanOutIntegrationTest` 上一段里被我挂成
「真回归 还是 轮询窗口不够」。**两个都不是**（#241 已修，run `33090976753` 报
「已修好 1 条（跑了而且通过）」）。

CI 日志里那条真实报错：

```
Referential integrity constraint violation: "fksdgf6ekee4yaptmucrps183qt:
  public.production_plans FOREIGN KEY(created_by) REFERENCES public.users(id) (CAST(1 AS BIGINT))"
  at SupplyChainOrchestrator.createProductionPlanFromSO(SupplyChainOrchestrator.java:532)
```

夹具建了 operator 并拿到了它的 id（`customer`/`productType`/`salesOrder` 三处都用了），
**唯独发事件时写死 `1L`**。而 `approvedBy` 会被写进 `production_plans.created_by`，
那一列有外键指向 `users(id)` ⇒ **这个测试是否通过，取决于「此刻库里有没有 id=1 的用户」**：
单独跑时 operator 自己就是第一条 user、恰好 id=1，蒙对；CI 全量跑到它时自增列早被推进
（大量 `@Transactional` 测试插入后回滚，**行没了而自增列不退**），`users(1)` 不存在 ⇒ 外键炸
⇒ PP 永远落不了库 ⇒ 第二条断言轮询满 10s 超时。

### 🔴 这一条最值得记的是「我两次尝试都失败了，而失败方式各自都能骗人」

| # | 我做了什么 | 结果 | 如果不追下去会怎样 |
|---|---|---|---|
| 1 | 本机单独跑这个类想复现 | **绿的** | 会得出「本机复现不了 ⇒ 是 CI 环境问题」——方向反了 |
| 2 | 用 `jdbcTemplate` 硬删占位用户，造 `users(1)` 缺席 | 打印出 `usersWithId1=1`，**删除根本没生效**（另开连接看不到未提交的行） | 会得出「造不出这个状态 ⇒ 假设不成立」 |
| 3 | **变异**：把字面量换成必然不存在的 `999_999L` | 失败签名与 CI **逐字同源**（同一外键名 / 同一调用点 / 同一断言行号 / 同样是第一条过第二条超时） | ✅ |

⇒ **「复现不出来」不等于「假设不对」**，尤其当复现动作本身可能失败时。
第 2 次我是靠**把中间量打出来**（`usersWithId1=1`）才知道删除没生效 ——
没有那个打印，它会长成「假设被证伪」。

### 同族普查

`grep -rn "new SalesOrderFinanceApprovedEvent("` 全仓 17 处：生产侧 1 处传真 `reviewerId`；
测试侧 16 处里除本类外**全部是 Mockito 单测**（无真库 ⇒ 无外键）⇒ **真实风险面就是这 1 处**。

---

## 其它仍然挂账的（沿用上一棒，理由未变）

BOM 草稿 Workflow 修订（撞在飞任务 `F006-R2-WORKFLOW-FIRST-BOM-GATE`）／
83 条 web 孤儿路由 + 29 条 RN 孤儿屏（要按名字逐条人工判断，⛔ 静态启发式不再往下推）／
`getActualQuantity()` 另外 3 处（设计问题，不替它决定）／
`SalesFinanceApproveVoucherListenerTest` 的断言对象（要用 `ArgumentCaptor` 重构，不是改桩）。

---

## 派并行 agent 时必须写进 brief 的（沿用，一条没减）

⛔ 不许用 `git stash`（多 agent 共享同一个 `.git`，栈是共享的）
⛔ 不许碰 `known-failing-tests.txt`（并发冲突，由协调者统一削）
⚠️ CI 绿不能证明修好了，**本机读数才是证据**
⚠️ 源码扫描型测试类名必须 `ContractTest` 结尾
⚠️ PR body 用 `--body-file`（反引号会被 bash 当命令替换执行）

＋本轮新增一条：
⚠️ **`git commit -- <paths>` 装不下新文件** —— 未跟踪文件先 `git add -N`，
否则 `safe-commit.sh` 报 `pathspec did not match any file(s)`，而那看起来像文件写错了。
