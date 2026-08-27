# 交接：CI 可信度四道闸 + Java 存量红 143 → 49（2026-08-27 夜）

> 细节在 `HANDOFF-2026-08-27-java-red-burndown.md`（分类表 + 全部 PR + 为什么剩下的不该动）。
> 这份只回答三件事：**接手第一件事做什么 / 什么不能碰 / 我错在哪。**

---

## 🔴 接手第一件事：读今晚 02:00 那次夜间跑批

`java-full-nightly` = `active` / cron `0 18 * * *` UTC / `[self-hosted, cretas]`。

剩下的 **49 条基线全部是同一个根因**（H2 schema 毒源，PR #197），
而 `ddl-auto=create`（#197）与 `forkedProcessTimeoutInSeconds`（#198）都已在 main。

| 那次的结果 | 含义 | 动作 |
|---|---|---|
| 49 条转绿 | 机制诊断成立 | 削基线到接近 0 |
| **仍然红** | **诊断错了** | 回去重查，完整证据链在 #197 的 PR 描述里 |

⛔ **在拿到那次读数之前，这 49 条一条都不要动。**
它们**本机全绿**（本机全量 11888 个测试里一条不红），只在 CI 上红 ——
surefire 的类执行顺序在 macOS 与 Windows 上不同，污染源没排在它们前面。

> **「本机跑绿」对这一类没有证明力。** 这句话已经写进 `application-test.properties` 的注释，
> 因为下一个人一定会用「我本机是绿的」来反驳那个改动。

**怎么拿完整日志**（⛔ `gh run view --log` **会截断** —— 实测 207446 行里搜不到关键那段）：

```bash
gh api "repos/cretasfuture-ai/cretas/actions/runs/<RUN_ID>/logs" > logs.zip
python -c "import zipfile; zipfile.ZipFile('logs.zip').extractall('.')"   # 多出 14 万行
```

---

## 这一轮在 main 上留下了什么

**四道闸（可信度层）**

| PR | 它守什么 | 建成当天抓到的 |
|---|---|---|
| #177 / #180 | **一道闸扫的东西，必须在它自己的触发面里**（web + RN 孪生）| 24 + 7 处「读着却没登记」 |
| #179 | Java PR 闸 —— 此前改 `service/**` 的 PR **合并前后都没有测试跑过** | #174 的 checks 里只有密钥扫描 |
| #182 | 全仓 NUL 扫描搬出 Java 套件（它扫全仓却只被 `backend/java` 触发）| 我自己的闸文件带着 2 个字面 `0x00` |
| #198 | surefire fork 超时 —— 挂死时**三态判定一步都执行不到** | `IntentParityTest` 本机挂 17 分钟 |
| #223 | 棘轮把**「删掉一个红测试」报成「已修好」** | 基线里真有一条改名后的幽灵 |

**存量红 143 → 49**：#186 / #189 / #191 / #197 / #200 / #210 / #213 / #214 / #215 / #216 / #219 / #225
+ 三次削基线（#203 / #205 / #217），每次都**在合并后的 main 上自己重跑过**。

> ⚠️ **为什么每次都要自己重跑**：`java-narrow-gate` 跑的是
> `*ContractTest,*RepositoryQueryValidationTest,FlywayVersionUniquenessTest,*StartupGuardTest`
> 这个窄选择器，被修的那些类**几乎都不在里面** ⇒ **CI 绿不能证明它们通过了。**

---

## ⛔ 挂账的，各有理由，别顺手做

| 项 | 为什么不做 |
|---|---|
| **BOM 草稿 Workflow 修订**（7 条，已不在基线）| 撞在飞任务 `F006-R2-WORKFLOW-FIRST-BOM-GATE` |
| **83 条 web 孤儿路由 / 29 条 RN 孤儿屏** | 被棘轮钉住只减不增；剩下的要**按名字逐条人工判断**。⛔ 静态启发式再推会开始误伤 |
| **`getActualQuantity()` 另外 3 处** | 快照 / DTO 投影，「该记存值还是算值」是**设计问题**，不替它决定 |
| **`SalesFinanceApproveVoucherListenerTest` 的断言对象** | 它断言原始实例，这**反过来强制**桩返回同一实例，于是守不住「必须用 `save()` 的返回值」。要用 `ArgumentCaptor` 重构断言，**不是改桩** |
| **`ToolRouterServiceImpl` 之外的第三个出口**（若将来新增）| #213 那道闸只钉了现有两个出口 |

**一条查了但决定不做的**：「`save()` 返回值被丢弃」今天在凭证路径栽了三次，
扫出 **89 个候选** —— 收窄到真实风险形状（**无绑定 EntityManager**）后**真值是 0**，
全在 `@Transactional` 里。⇒ **不建那道闸**，89 个误报的闸建了就是当天被关掉的闸。

---

## 🔴 我错在哪（这些都长得很像对的）

**三个被证伪的判断，全是跑一次就能否掉的：**

| 判断 | 怎么否掉的 |
|---|---|
| 「7 个在产批次的 BOM 被人改过」 | 那 8 条记录**共享同一个微秒级时间戳** ⇒ 批量操作，是我自己那条迁移 |
| 「ProcessSheet 那 38 条是测试隔离问题」 | 本机**全量**跑它们也全绿 —— 假设要用**同样规模**的跑批去验 |
| 「ProcessSheet 是时区问题」 | `-Duser.timezone=America/Toronto` 跑，**仍然 28/28 绿** |

⚠️ 时区那条**推理链完全自洽**（CI 与 pom 都没钉时区、应用只在 Jackson 层设 GMT+8、
`LocalDate.now()` 用 JVM 默认时区、runner 在加拿大、工序单按日结算）。
**一条自洽的推理链不是证据。**

**我自己的仪器坏了三次：**

1. 阳性对照断言 `PATHS.length > 2` —— **它绿着而解析器是坏的**（读到 4 条重复的，
   要紧的 3 条一条没进来）⇒ **阳性对照要量决定成败的那一维，不是量条数**
2. 改写脚本的守卫写成 `if 'findByIdAndFactoryId' in s` —— 匹配到了**另一个 repository** 的桩，
   四个文件全被误判成「已有」跳过 ⇒ **守卫要带前缀**
3. 阳性对照钉了**被测对象本身** —— 变异时它和真正的发现**一起红**，
   「仪器坏了」与「发现本身」分不开 ⇒ **阳性对照要钉变异之后仍然成立的东西**

**一次定性错误**：SITE E 的确认标记外泄，我第一反应是「请求伪造口子又开了」。
**不对** —— marker 是裸 `new Object()`、`isConfirmed` 用 `==` 比对象身份 ⇒
跨序列化边界不可伪造。是**卫生问题不是鉴权漏洞**。
⇒ **读一眼那个比较到底在比什么。**

**一次错误归因**：#202 的 `java-narrow-gate` 红了，差点去查「agent 碰坏了什么」——
**整个日志 187 行，一个测试都没跑**，是 `actions/checkout` 在自建 runner 上 `.git` 残缺。
⇒ **判「谁弄红的」之前先确认它跑没跑。**

---

## 派并行 agent 时必须写进 brief 的（第一轮我漏了，第二轮补上）

⛔ **不许用 `git stash`** —— 多个 agent 共享同一个 `.git`，**stash 栈是共享的**。
我是查 #200 时才发现栈顶压着另一个 agent 的在途工作，**再晚一步就可能被 pop 走**。
阴性对照改用 `cp` 备份 + `git checkout --`。

⚠️ **CI 绿不能证明你修好了** —— 窄选择器不覆盖大多数类，**本机读数才是证据**。

⚠️ **源码扫描型测试类名必须 `ContractTest` 结尾**，否则 `GateSelectorCoverageContractTest` 打回。

⚠️ **PR body 用 `--body-file`** —— 反引号写进命令行会被 bash 当命令替换执行（今天吃了三次亏，
一次把 commit message 里整行吃成空的）。

⛔ **不许碰 `known-failing-tests.txt`** —— 多路并发会冲突，由协调者统一削。

> 八路 agent 没有一路走捷径。四个证据：用仓里**已有的通过测试**反驳断言 /
> 把守护方向反过来（改成阴性对照让被删的机制不能回来）/
> 唯一碰生产代码的那路方向是**收紧** / 登记**具名例外**而不是删掉不变式。

---

## 相关记忆

- `project_one_context_close_drops_the_shared_h2_schema_for_everyone.md`（含棘轮那条）
- `feedback_my_positive_control_counted_entries_not_the_kind_that_mattered.md`
- `feedback_i_grepped_for_a_convention_without_reading_its_guard.md`
