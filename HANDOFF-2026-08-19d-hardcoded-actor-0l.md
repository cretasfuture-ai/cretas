# created_by=0 家族 —— 复核、修复与闸的盲区（2026-08-19 夜）

> 接的是 `HANDOFF-2026-08-19-SESSION-BOOK.md` 第 6 节 P1 的
> 「`created_by=0` 🔴 紧急」那一条。
>
> 分支 `codex/claude-hardcoded-actor-0l`，两个 commit：`a59f2985e1` / `97289f8c86`。

---

## 0 · 30 秒版

**交接手册那一条已经过期了。** 它点名的
`ProcurementSuggestionService.SYSTEM_USER_ID` 在同一天的 `c24d061c72` 里就已经删掉了，
还建了棘轮闸 `HardcodedActorIdContractTest`。

**但那道闸只认方法名**（`setCreatedBy` / `setUpdatedBy`），
所以它看不见「把常量当**位置参数**传给一个 actor 形参」这种形状 ——
而**同一个家族还有 6 处活的**，其中 2 处**已经在 prod 写出了脏数据**。

已修 5 处、冻结登记 1 处、闸补上了那一档。

---

## 1 · 先说结论：手册那一条为什么过期

| 手册说 | 实际 |
|---|---|
| `ProcurementSuggestionService.SYSTEM_USER_ID = 0L` 是紧急待办 | **已修**（`c24d061c72`，同日） |
| 「『还有几处没改』由闸回答」（闸自己的注释） | 闸**答错了**：它报存量 8 处，真实是 **14** |

⚠️ 这正是本仓记过的形状：**「零行 / 从来没有过」类结论有保质期**。
引用之前当场重测一次，成本是一条命令。

---

## 2 · 根因（不是「两处忘了改」）

`ProductionCompletedEvent` **不带任何操作人**。
它的两个监听器都需要一个 actor 来标注自己做的事，于是**各自发明了一个**
`private static final SYSTEM_USER_ID = 0L`。

⇒ 这不是两次疏忽，是**事件缺一个字段**逼出来的两次相同发明。
所以修在源头（事件补 `completedBy`），不是在两个监听器上各打一个补丁。

### prod 实测（`cretas_prod_db` 只读，全程有阳性对照）

```
users                     79 行, min(id) = 1309, id <= 10 的行数 = 0
                          ⇒ 那个「系统账号」从来不存在

全库 147 个 actor 列扫描  只有 2 个列有脏值:
  internal_transfers.requested_by   zero=2   orphan=0
  bom_versions.created_by           zero=0   orphan=1   ← 另一族(被清理的租户用户), 未处理

那 2 行:  TRF-20260816-5192 / TRF-20260817-9377
          都是 F006 / 都是 DRAFT / 都来自同一个计划 PLAN-1786724332978-6013FEBE
          remark 含 "[D1 自动] … PR #309 A3=A."  ⇒ 确认是 D1 自动反向调拨写的
```

**它为什么不炸**：`internal_transfers.requested_by` 与 `approval_history.actor_id`
**都可空、都没有外键指向 users**。所以这是一条**悬空引用**，不是一次违约 ——
安静地躺了九个月。

**用户看到的**：`TransferServiceImpl#resolveActorNames` 拿 `0` 去
`findAllById` 查不到人 → `requestedByName = null` →
**调拨详情页「申请人」空白**。
⚠️ 而 fool-proof-design Rule 2 正是 2026-07-04 为 **F006** 反馈
「申请人显示成裸 userId」才加的 —— 同一个工厂、同一栏，换了个形态又回来了。

### 被证伪的两个假设（都是我自己的）

1. ❌ 「这是潜伏的外键违约」——**不是**。那两个列没有外键。
   查了才知道，不是猜出来的。
2. ❌ 「`confirmTransfer` 会把 0L 写进 `material_batches.created_by`（那一列**有** NOT NULL + 外键）」
   ——**到不了**：`autoAdvanceIntraFactory` 早已停用，只剩一句 log。
   佐证：全量 error 日志里 `fkgrvetualxk9pfour43lqsv8x8` **0 次**，
   而同一条命令的两个阳性对照 `fk_po_created_by` / `fksdgf6ekee4yaptmucrps183qt`
   **各 104 次**（与闸的 javadoc 逐字相符 ⇒ 仪器是活的）。

---

## 3 · 改了什么

### 3.1 `a59f2985e1` —— 事件带上人

- `ProductionCompletedEvent` 新增**可空** `completedBy`
- 发布点 A（`SupplyChainOrchestrator`，批次完工）：抽出 `resolveBatchActor(batch)`
  = 生产负责人 → 批次建单人 → null。**抽成方法**是因为它已经有两个调用点（形态 D）
- 发布点 B（`ProductionPlanServiceImpl.completeProduction`）：从调用方拿真人。
  `completeProduction` 因此新增**必填** `completedBy`；
  controller 走既有的 `extractUserId(authorization)`，`PlanUpdateTool` 走 `getUserId(context)`
- 两个监听器删掉 `SYSTEM_USER_ID`

⛔ **为 null 时不兜底也不抛异常**：两个下游列都可空无外键，null 是合法值；
为「归属不明」拒绝建草稿会把「余料还在车间仓」这件事丢掉，比归属为空糟得多。
逐字同 `fg.setCreatedBy` 那处同日的判断。

> 🔴 **发布点 B 当初逃过了排查**，因为它写的是全限定名
> `new com.cretas.aims.event.ProductionCompletedEvent(...)` ——
> `grep "new ProductionCompletedEvent"` 一个字都看不见它，**是编译器抓出来的**。
> 形态 C⁸ 的又一例：数结构要用 AST / 编译器，不要用字符串。

### 3.2 `97289f8c86` —— 闸补上那一档

新增第二档检测器：**形参名是 actor 且类型是装箱 `Long`** 的位置收到数字常量。

同一天又找出 4 处（除去上面已修的 2 处）：

| 站点 | 处置 |
|---|---|
| `CanvasApplyTemplateTool` / `CanvasUpdateFieldTool` / `CanvasToggleModuleTool` 的 `0L → operatorId` | **已修**，见下面 4.3 —— 修法**不是** `getUserId(context)` |
| `SmartBIServiceImpl#executeIntent`（`execute(…, 0L, …)`，第 1561 行） | **冻结登记**，见下 |

两份冻结名单**分开**（`KNOWN_HARDCODED` / `KNOWN_ACTOR_ARG_SITES`）：
新检测器自带存量，倒进旧名单会把「旧账还清了没有」和「新检测器发现了什么」搅成一个数，
也违反旧名单「只许变短」的规则。

---

## 4 · 两次被自己的控制抓住（这一节比结论有用）

### 4.1 闸绿了，但**变异没红** —— 而变异已经证明落盘

第一版的判据是「同名同参数个数的**所有声明都必须同意**某一位是 actor 形参」。
把 `createTransfer(…, event.getCompletedBy())` 变异回 `(…, 0L)` 之后，
闸**纹丝不动**。

按形态 C″ 先证明变异真的生效（重读文件确认新串在、旧串不在）⇒
不是「断言没用」，是**判据有问题**。诊断出真因：

```
仓里有第三个 createTransfer/3 ——
TransferController 的 (String factoryId, String authorization, CreateTransferRequest request)
2 号位是 request ⇒「所有声明都同意」不成立 ⇒ 2 号位被整体剔除 ⇒ 检测器沉默
```

⇒ 判据换成：**某个声明在这一位是 actor 形参，且没有任何声明在这一位放了一个
「接得住数字」的非 actor 形参**。`CreateTransferRequest` 接不住 `0L`，
那个重载不可能是本次调用的目标，不构成歧义；
真歧义（`foo(Long userId)` vs `foo(Long count)`）照样被排除。

▎**这条判据是被变异抓出来的，不是想出来的。**

### 4.2 我的测试守卫拦下了一条本来会「恒真」的断言

`Publisher` 那三条测试一开始红在
「没有发布 ProductionCompletedEvent —— 前置条件没搭好」。
真因不是没发，是我用 `ArgumentCaptor.forClass(Object.class)` 去 verify，
而 `publishEvent` 是**重载**的（`publishEvent(Object)` / `publishEvent(ApplicationEvent)`），
`ProductionCompletedEvent extends ApplicationEvent` ⇒ 实际调的是后者。
Mockito 因此报「Wanted but not invoked」——**长得像事件根本没发**。

▎如果我当初写的是「捕到就断言、捕不到就跳过」，这三条会**绿着什么都没测**。

### 4.3 差点把「安静写错数据」换成「500」

三个 Canvas 工具的 `0L` 我第一版直接改成了 `getUserId(context)` —— **那是错的**。

顺手查了一下落点才发现：

```
factory_configurations.created_by   is_nullable = NO   (无外键)   prod 现有 1 行, 干净
```

而 `AbstractTool#getUserId(context)` 取不到会返回 **null**，
落到 NOT NULL 列上是一次约束违例（500），报的还是
`null value in column "created_by" violates not-null constraint` ——
与「这个操作没有操作人」这件事完全对不上。

⚠️ 而且这条路**真的会走到**：`CanvasAIController#buildToolContext` 解析 token 失败时
只 `log.warn`，`userId` 根本不会进 context。

⇒ 改成 `AbstractTool#requireUserId(context, action)`：**取不到就显式失败**（401 + hint），
⛔ 既不兜底假 id，也不让 null 漏到 DB。
配套 `CanvasToolActorGuardTest` 六条：三个工具 ×（有真人 → 原样传 / 没有 → 抛且
`verifyNoInteractions(configService)`），每条都带一条「⛔ 0L 不许回来」的阴性对照。

▎**这条判据本来就写在闸自己的报错文案里**（「列可空且无外键时 null 是诚实的答案」）——
▎意思是**列不可空时 null 不是**。我第一版没照做，是查落点的 nullability 时才拦住的。

### 4.4 另外两条

- **`ProductionPlanSettlementTest` 的 5 条失败是既有的**，不是我引入的。
  在 `cretas-mainmerge`（干净的 main，`git status` = 0）上跑同一条命令，
  **同名同行同消息**。是这台机器的 `file.encoding` 问题（`expected: <box> but was: <?>`）。
  ⇒ 基线当场重跑，不引用记忆里的数（形态 A⁷）。
- **行尾污染**：`ProductionPlanController.java` 一度 `git diff --stat` 报 103/97 而
  `--ignore-all-space` 报 8/2。成因是 HEAD 里那个文件是**混合**的（1295 CRLF + 95 lone-LF），
  我的编辑把那 95 行归一成了 CRLF。已按 HEAD 逐行还原，两个读数现在一致。
  ⚠️ 第一版修复脚本假设「HEAD 是纯 CRLF」并打算整体归一，**它自己 abort 了并报出真相** ——
  在这个文件上「统一」才是污染。

---

## 5 · 没做的 / 需要 owner 定的

| 项 | 说明 |
|---|---|
| **prod 那 2 行 `requested_by = 0`** | **prod 库只读，没动。** 两张都是 DRAFT、同一个计划、F006。选项：(a) 留着（它们是草稿，仓管可直接取消）；(b) 补一条 UPDATE 把 `requested_by` 置 NULL（比 0 诚实，且详情页表现不变——两种都查不到人）；(c) 置成该计划的负责人。**建议 (b)**：0 会被误读成一个用户 id，NULL 不会 |
| **`SmartBIServiceImpl#executeIntent` 的 `0L`** | 冻结登记，没硬改。它只进意图权限判定、不写 FK 列；同文件 738 行有条注释把 `userId=0L` 定义成「匿名/系统调用」约定，而 1561 行是**硬写**的（相邻的 740 行至少还先试了真人）。改它要先弄清 0 在「低敏意图免审批」里到底意味着什么 |
| **`bom_versions.created_by` 的 1 条 orphan** | 非 0，是一个已不存在的 user id —— 大概率是 2026-08-07 租户清理的遗留。另一族，未查 |
| **闸仍然看不见的** | 跨文件常量引用（`SomeConstants.SYSTEM_USER`）、`int` 型 actor、以及形参名不在 `ACTOR_PARAM_NAMES` 里的。已写在闸自己的 javadoc 上 —— **一道闸看不见什么，要和它看得见什么一样写在脸上** |

---

## 6 · 判据清单（复核用）

```bash
# 1) 两个 commit
git -C C:/Users/Steve/cretas-actor0l log --oneline -2   # 97289f8c86 / a59f2985e1

# 2) 四条变异逐一转红 (脚本会先证明变异落盘)
bash <scratchpad>/run_mutations.sh                       # 4/4 VERDICT: RED

# 3) 闸对两处盲区站点转红
python <scratchpad>/mutate.py M2 apply && mvn -o test -Dtest=HardcodedActorIdContractTest
#   → ReverseTransferService#createReverseTransferDraft -> createTransfer(arg2)=0L
python <scratchpad>/mutate.py M3 apply && mvn -o test -Dtest=HardcodedActorIdContractTest
#   → ProductionPlanTransferLifecycleListener#onProductionCompleted
#       -> closeOpenTransfersForProductionPlan(arg2)=0L

# 4) 行尾没被污染
diff <(git diff --numstat | sort) <(git diff --ignore-all-space --numstat | sort)
```
