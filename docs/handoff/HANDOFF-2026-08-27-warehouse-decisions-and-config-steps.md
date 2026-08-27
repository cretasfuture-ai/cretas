# HANDOFF 2026-08-27（夜）—— 仓储四项裁定的落地 + 两处被推翻的前提

**上一棒**：`HANDOFF-2026-08-27-warehouse-p0-and-voucher-source.md`（§3 留下四项待裁定）
**本轮**：四项全部拿到裁定并落地。⚠️ 其中**两项的前提被实测推翻**，Owner 据实测改判。

---

## 0. 接手前先看这个

| | |
|---|---|
| 裁定 ④ | **PR #235**（已开）—— 3 个活端点补 `sourceType` + 变异验证过的闸 |
| 裁定 ③ | **本 PR**（代码）+ **F006 调拨审批流已配好并发布**（见 §4b） |
| 裁定 ① | ✅ **已执行**（2026-08-27 23:41，F006 盘点流 v2 已发布）见 §4 |
| 裁定 ② | ✅ **已执行**（2026-08-28，LIUSHANMEN 盘点流 v1 已发布）见 §5b |

### ✅ 已完成的生产写入（2026-08-27 23:40~23:42，Owner 当轮授权）

租户证明：实时登录响应 `factoryId=F006` / `userId=1309` / `role=factory_super_admin`。

| 写入 | 结果 |
|---|---|
| ③ 新建 `INVENTORY_TRANSFER_APPROVAL` | `351b925a-13ac-4717-b134-2038ea7122a6`，v1 published，授权 **`finance_manager`** |
| ① 盘点流加 condition 节点 | clone-draft → `b4c8c82a-f0d5-4136-830e-6ae52f0c79da`，**v2 published**；v1 被自动置 `enabled=f` |

**写前写后计数**（`approval_workflows`，`deleted_at is null`）：

```
                     BEFORE   AFTER
f006_all               11  ->   13     (+2, 符合预期)
f006_adjustment         1  ->    2
f006_transfer           0  ->    1
LIUSHANMEN(阴性对照)    14  ->   14     一字未动
```

**内容回读**：v2 `has_condition_node=t`、自动边条件 `#hasDifference == false`；
调拨流 `approverRoles[0] = finance_manager`。

⚠️ **两个 v 都是 `published`，区分它们的是 `enabled` 而不是 `publish_status`。**
执行器查的是 `published AND enabled = true ORDER BY priority DESC, version DESC`
（`ApprovalWorkflowRepository.findActiveByDecisionType`）——
v1 `enabled=f` 被排除，v2 唯一命中。
⛔ 以后核对「哪一版在生效」**不要只看 `publish_status`**，那一列会显示两行 published。

### ⛔ 尚未做的：行为侧两侧验证

配置已就位并结构性核对过，但**没有**提交真实盘点单/调拨单去跑通两条路 ——
那是另一类业务写入，不在本轮授权范围内。判据见 §4 末与 §5 末，需要时单独授权。

---

## 1. 🔴 被推翻的前提之一：调拨自审**不是**因为「没配审批流」

上一棒交接件 §3 写：F006 调拨 7 张全是发起人自审，**「因为 F006 没配
`INVENTORY_TRANSFER_APPROVAL`」**。据此的自然结论是「配上就好了」。

**实测三条，结论相反：**

1. `SelfApprovalPolicy.allowsSelfApproval` 对 `factory_super_admin`
   **无条件**放行 —— 硬编码，配置改不动。
2. F006 那些自审**全部**是 `f006_admin`(1309)，正是该角色。
3. 🔴 **阳性对照**：LIUSHANMEN **配了并发布了** `INVENTORY_TRANSFER_APPROVAL`
   （v1，`published`/`enabled=t`），它唯一那张调拨 **照样自审**（1638 → 1638）。

⇒ **配了的租户和没配的租户行为一模一样。** 光配置不会改变任何行为。

### 1b. 顺带订正计数

交接件写「10 张单 / 已批准 7 张」。实测（`internal_transfers`，注意本表用
`source_factory_id`/`target_factory_id`，**没有** `factory_id` 列）：

```
11 行总计  ->  2 行软删(CANCELLED, 从未批准)  ->  9 行存活  ->  6 张已批准, 6/6 自审
```

三种口径都对不上 10/7。⚠️ 这类计数**引用前当场重测**，别沿用。

---

## 2. 🔴 被推翻的前提之二：盘点阈值「没有分布可量」

交接件 §3① 说变量已能判、配个 condition 节点即生效。变量确实有了（#192），
但**要拿什么数当阈值**这件事，数据支撑不起来：

```
全生产库 factory_stocktakes 只有 6 张单, 全部属 F006,
全部落在 08-20 ~ 08-25 那个开发窗口内。
差异条数 p50=1 / p90=2 / max=2       最大盘亏量 1.0       零差异 2 张
```

⇒ 在 6 个点上拟合阈值线 = 给种子数据建检测器。
Owner 裁定：**只配「零差异自动通过」**，不设数量/件数阈值。

⚠️ 还有一层：代码里**已经**有一条硬规则「有差异就不得自审」（409）。
所以阈值的真实作用**不是收紧，是让琐碎的自动通过**。这一点交接件没说清，
容易让人以为在加闸。

---

## 3. 裁定 ③ 的落地：收窄，而不是配置

那条例外的**自述理由**一直是「单人工厂里超管必须能推进自己的单」，
但**实现不看厂里有没有别人** —— 文与行为不符，且是往松的方向不符。

收窄成：**超管例外仅当本厂再没有第二个人够格审当前节点时才成立。**

- 「够格」复用**审批引擎自己那条判据**（节点 `config.approverRoles` 含此人
  `roleCode`），⛔ 没有另写一套「谁算合格审批人」——两份判据一定会漂。
- **读不到节点角色时一律放行**（沿用旧行为）。绝大多数工厂没配审批流，
  在那里改成拒绝等于把单据全部锁死 —— 那正是这条例外当初要防的事。
  spec 记着实测代价：**某工厂曾因此一个多月没走通过一次调拨审批，单子全卡在草稿。**
  ⇒ 本次收窄**只在配了审批流的工厂上生效**。

### ⚠️ 给配置者的警告（已写进测试断言，不是口头提醒）

**只把节点配成 `approverRoles=[factory_super_admin]` 不足以实现「要求他审」。**
F006 的 9 个 `factory_super_admin` 里**只有 1309 是活的**，其余 8 个是停用的历史测试账号
——「够格的人」就只有发起人自己，例外照样成立。

要真正分离职责，节点必须授权给**本厂另有活跃账号**的角色。F006 可选：

| 角色 | F006 活跃数 |
|---|---|
| `finance_manager` | 1 |
| `warehouse_manager` | 1 |
| `production_manager` | 1 |

### ⚠️ 还有第二处超管无条件放行，本次**有意不动**

`WorkflowEngineServiceImpl.canTransition` 第 1 条也是
`factory_super_admin` → 总是放行。它回答的是**「这个人能不能操作」**，
与**「发起人能不能审自己」**不是同一个问题，所以本次只收窄后者。
📌 登记在此，免得下一棒以为「超管特权已经收干净了」。
（本仓有过先例：两道权限闸只扫了一道就报「漂移 0 处」。）

---

## 4. ① F006 盘点「零差异自动通过」—— 执行步骤

### ⚠️ 不要原地改 v1 的 `nodes_json`

spec `2026-08-01-oa-self-approval-and-budget-design.md` 明确排除过这个动作：
改已发布流程的 `nodes_json` 会让**在飞实例撞 definition-digest 校验 409**。

实测 2026-08-27：**全库 RUNNING 实例 = 0**，所以今天原地改其实撞不上。
但仍走 clone-draft —— 便宜，且不依赖「此刻恰好没有在飞的单」。

```
GET   /api/mobile/F006/approval-workflows/77a1ee8b-2b31-4b97-bb1e-9df743828661/running-instances/count
POST  /api/mobile/F006/approval-workflows/77a1ee8b-2b31-4b97-bb1e-9df743828661/clone-draft
PUT   /api/mobile/F006/approval-workflows/{draftId}          # 用下面的 nodes/edges
PATCH /api/mobile/F006/approval-workflows/{draftId}/publish
```

### 用 `hasDifference`，⛔ 不要自己数条数

`buildStocktakeWorkflowContext` 已经把它放进 OA 上下文，而且它**就是驱动那道 409 的同一个量**：

```java
context.put("hasDifference", hasDifference);   // 来自 normalizeAndValidateEvidence 的返回值
context.put("differenceCount", surplus + shortage);
```

写 `#differenceCount == 0` 今天等价，但那是同一件事的第二份定义，早晚会漂。

### 改动：start 后面插一个 condition 节点

新增 1 节点 + 改 1 边 + 新增 2 边；审批节点与两个 end 节点**原样不动**。

```json
{
  "startNodeId": "start_1",
  "nodesJson": [
    { "id": "start_1", "type": "start", "label": "开始", "config": {},
      "position": { "x": 50, "y": 150 } },
    { "id": "cond_diff", "type": "condition", "label": "有没有盘盈/盘亏",
      "config": {}, "position": { "x": 180, "y": 150 } },
    { "id": "approval_boss", "type": "approval", "label": "厂长审批",
      "config": { "approverRoles": ["factory_super_admin"],
                  "requiredApprovers": 1, "timeoutMinutes": 1440 },
      "position": { "x": 380, "y": 150 } },
    { "id": "end_approved", "type": "end", "label": "通过",
      "config": { "outcome": "APPROVED" }, "position": { "x": 640, "y": 150 } },
    { "id": "end_rejected", "type": "end", "label": "驳回",
      "config": { "outcome": "REJECTED" }, "position": { "x": 640, "y": 300 } }
  ],
  "edgesJson": [
    { "id": "e_start_cond", "source": "start_1", "target": "cond_diff", "priority": 0 },
    { "id": "e_cond_auto", "label": "零差异 → 直接通过", "source": "cond_diff",
      "target": "end_approved", "priority": 0, "condition": "#hasDifference == false" },
    { "id": "e_cond_appr", "label": "有差异 → 送审", "source": "cond_diff",
      "target": "approval_boss", "priority": 1 },
    { "id": "e_appr_ok", "label": "通过", "source": "approval_boss",
      "target": "end_approved", "priority": 0, "condition": "#decision == 'APPROVE'" },
    { "id": "e_appr_no", "label": "驳回", "source": "approval_boss",
      "target": "end_rejected", "priority": 1, "condition": "#decision == 'REJECT'" }
  ]
}
```

⚠️ `e_cond_appr` **不带 condition**，靠 `priority: 1` 兜底（与既有 `e_appr_no` 同形状）。
⛔ 不要写成 `#hasDifference == true`：两条边都不匹配时单子会**卡在条件节点上**，
而「卡住」的长相和「还没人审」一模一样。

### 判据：两侧都要验

| 用例 | 期望 |
|---|---|
| 提交一张**零差异**盘点 | 直接 `APPROVED`，`approval_history` 无人工节点 |
| 提交一张**有差异**盘点 | 停在 `PENDING_APPROVAL`；发起人本人审 → **409** `STOCKTAKE_SELF_APPROVAL_FORBIDDEN` |

⚠️ 第二条是**阴性对照**：只验第一条的话，「全都自动通过了」和
「零差异自动通过了」读数一模一样。

---

## 5. ② LIUSHANMEN 配 `INVENTORY_ADJUSTMENT_APPROVAL` —— 由 Owner 执行

硬边界：非 F006 租户在生产环境的业务写入必须为 0。⇒ 只给步骤，不代执行。

### 先纠正一个理解：这是**解锁**，不是**收紧**

| 情形 | 现在的行为 |
|---|---|
| 配了审批流 | → `PENDING_APPROVAL` |
| **没配 + 有盘盈/盘亏** | **→ 409 拒绝**（fail-closed） |
| 没配 + 零差异 | → 直接 `APPROVED` |

⇒ LIUSHANMEN 现在**根本做不完一次有差异的盘点**（实测它 `factory_stocktakes` **0 单**）。
配上去是让它能做。今天它是「安全但不可用」。

### 自审这件事代码已经管住，不靠配置

⚠️ 与调拨**不同**：盘点的自审禁令**没有超管例外**，直连路径与 OA 路径各一份，行为一致：

```java
boolean sameMaker = initiatedBy==actor || countedBy==actor || submittedBy==actor;
if (hasDifference && sameMaker) throw 409 STOCKTAKE_SELF_APPROVAL_FORBIDDEN;
```

### 不会锁死 —— 三种够格角色 LIUSHANMEN 都有活人

`STOCKTAKE_APPROVAL_ROLES = { finance_manager, factory_super_admin, platform_admin }`

| 角色 | 活跃账号 |
|---|---|
| `factory_super_admin` | 1638 `liushanmen_admin` |
| `finance_manager` | 1647 `lsm009` |
| `platform_admin` | 1669 |

⚠️ 这一步不能省 —— 单人租户配上审批流就会把自己锁死。

### 执行

```
POST  /api/mobile/LIUSHANMEN/approval-workflows          # 权限 system:read_write
PATCH /api/mobile/LIUSHANMEN/approval-workflows/{id}/publish
```

Body 用 F006 那份（`77a1ee8b…`）当模板，两处改：
`approverRoles` 改成 `["finance_manager", "factory_super_admin"]`（给第二个审批人），
`name`/`description` 换成本租户说法。

⚠️ **发布这一步最容易漏。** LIUSHANMEN 自己就有先例：
`INVENTORY_TRANSFER_APPROVAL` 有个 **v2 草稿停在 2026-07-29**，
`publish_status=draft` / `enabled=f`，有人改到一半没发布，至今躺在库里。

### 判据

```sql
select factory_id, decision_type, version, publish_status, enabled
from approval_workflows
where deleted_at is null
  and factory_id='LIUSHANMEN' and decision_type='INVENTORY_ADJUSTMENT_APPROVAL';
```

⛔ 不要只看 `count(*)` —— `publish_status` 仍是 `draft` 时那一行**存在但不生效**，
两者在计数上长得一模一样（就是 v2 草稿那个坑）。
**阴性对照**：同一查询换成 `F006` 应返回既有那 1 行，证明查询本身找得到东西。

---

## 5b. ② 已执行（2026-08-28，Owner 点名授权非-F006 写入）

租户证明：实时登录响应 `factoryId=LIUSHANMEN` / `userId=1638` / `factory_super_admin`。

| | |
|---|---|
| workflow id | `b49b6d52-8b04-4c84-a63c-026eb1830496` |
| 版本/状态 | v1 `published` / `enabled=t` |
| 授权角色 | **`["finance_manager", "factory_super_admin"]`** |

```
                       BEFORE   AFTER
lsm_all                  14  ->   15
lsm_adjustment            0  ->    1
F006(阴性对照)            13  ->   13     一字未动
MOCK_REST(阴性对照)        0  ->    0     一字未动
执行器口径 active 行数     -       1      (published AND enabled=true)
```

内容回读：`has_condition_node=t`、自动边条件 `#hasDifference == false`、
`approverRoles = ["finance_manager","factory_super_admin"]`。

### ⚠️ 与 §5 原步骤的两处偏离，都是有意的

**① 加了条件节点，不是配一个朴素的单级审批流。**
原步骤直接照抄 F006 v1（无条件节点）。那样会造成**回归**：

| | 配之前（无流程） | 配朴素流程 | 配本次这版 |
|---|---|---|---|
| 零差异盘点 | 直接 `APPROVED` | **变成 PENDING** ⬅ 回归 | 直接 `APPROVED` ✅ |
| 有差异盘点 | **409 走不完** | `PENDING` ✅ | `PENDING` ✅ |

⇒ 只解锁被卡住的那条路，**不动今天已经能走通的那条**。

**② 授权两个角色而不是只给 `factory_super_admin`。**
六膳门实测 5 次审批**全部**是 1638 自己批自己。若只授权超管，而盘点的自审禁令
（`STOCKTAKE_SELF_APPROVAL_FORBIDDEN`，⛔ **没有**超管例外）会拦住他 ——
1638 发起的有差异盘点就**没人能审**，等于把一条死路换成另一条死路。
加上 `finance_manager`（1647 `lsm009` 活跃）才真的有第二个人。

### 失败方向是安全的

`e_cond_appr` **不带 condition**、`priority=1` 兜底。所以：

- `hasDifference == false` → 命中 priority 0 → 自动通过
- `hasDifference == true` → 不命中 → 落到 priority 1 → 送审
- **表达式求值失败/取不到值** → 不命中 → 同样落到送审

⇒ 最坏情况是**多送一次审**，⛔ 不会卡在条件节点上。

### ⛔ 尚未做的：行为侧验证

结构核对完了，但**没有提交真实盘点单**去跑通两条路 —— 那是另一类业务写入。
判据：零差异单应直达 `APPROVED`；有差异单应停 `PENDING`，且 1638 自审得到
409 `STOCKTAKE_SELF_APPROVAL_FORBIDDEN`、由 1647 `lsm009` 审才过。

---

## 6. OBS-JAVA-TEST-SELECTOR-COVERAGE：两头合并看，不需要再量一次

上一棒 §8.3 要求「先量一次性放开会红多少」。**那个仪器已经存在，就是夜间跑批。**

| | PR 闸 (`java-gate-pr.yml`) | 夜间 (`java-full-nightly.yml`) |
|---|---|---|
| 选择器 | `*ContractTest,*RepositoryQueryValidationTest,FlywayVersionUniquenessTest,*StartupGuardTest` | **无** —— `mvn -B test` 全量 |
| runner | `[self-hosted, cretas]` | **同一台** |

实测：**1448 个测试类 / 230 个在窄选择器内 / 1218 个（84.1%）PR 上跑不到**
（与 OBS 的 1435-229-1206 一致，差值是当天新增文件）。

⇒ 那 1218 个**每晚都在跑**，且被棘轮钉住。同一台 runner、同一个全量口径，
所以夜间的红数**可直接当作**「放开 PR 选择器的代价」。

**而那个数今晚已经出来了**：`04f0ecbb90` —— 棘轮基线 **49 → 5**，
#197 的 H2 毒源诊断成立，44 条转绿。⇒ 代价现在约 **5 条**，不是 49。

### 真正的缺口不是覆盖，是**时机**

那 1218 个类在 PR 上跑不到 ⇒ 一个 PR 引入的回归**要等到当晚 02:00** 才暴露，
而且是**在 main 上**、混在别人的改动里。这与「跑没跑过」是两件事，
⛔ 不要用「夜间跑得到」把这个缺口也一并当成不存在。

---

## 7. 留给下一棒

- **② LIUSHANMEN 盘点审批流**待 Owner 执行（步骤见 §5），需写前写后计数
- **③ 的行为侧复验**：#237 合并并部署后，由 1309 发起一张 F006 调拨并尝试自审 ——
  期望**被拒**（现在节点授权给 `finance_manager`，而 F006 有一个活跃的 `finance_manager`，
  所以「本厂还有别人够格」成立，超管例外不再放行）。
  ⚠️ **在 #237 部署之前，配置已经生效但收窄还没生效** —— 此刻 1309 仍能自审，
  因为线上跑的还是无条件放行那版。⛔ 别把这个中间态读成「配置没起作用」。
- **① 的行为侧复验**：提交一张零差异盘点（应直接 APPROVED）+ 一张有差异盘点
  （应停在 PENDING，且发起人自审 409）。两侧都要验，只验第一条分不清
  「全都自动通过了」和「零差异自动通过了」。
- `WorkflowEngineServiceImpl.canTransition` 的超管无条件放行**仍在**（§3 末）
- ⚠️ **F006 调拨从此需要财务经理复核**。这是 ③ 的本意，但它改变了 F006 的日常流程 ——
  之前调拨是提交即可自批，现在会停在 `PENDING`。若挡到测试节奏，
  把那条流 `enabled=false` 即可回退（⛔ 不要删，留痕）。
