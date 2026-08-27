# HANDOFF 2026-08-27（夜）—— 仓储四项裁定的落地 + 两处被推翻的前提

**上一棒**：`HANDOFF-2026-08-27-warehouse-p0-and-voucher-source.md`（§3 留下四项待裁定）
**本轮**：四项全部拿到裁定并落地。⚠️ 其中**两项的前提被实测推翻**，Owner 据实测改判。

---

## 0. 接手前先看这个

| | |
|---|---|
| 裁定 ④ | **PR #235**（已开）—— 3 个活端点补 `sourceType` + 变异验证过的闸 |
| 裁定 ③ | 本 PR —— 收窄超管自审例外 |
| 裁定 ① | ⏳ 待执行的 F006 生产配置，步骤见 §4 |
| 裁定 ② | ⏳ 待 Owner 执行的 LIUSHANMEN 配置，步骤见 §5 |

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

- **①②** 两项生产配置待执行（步骤见 §4/§5），都需要 Owner 授权与写前写后计数
- **③ 配好之后要复验**：F006 配上一个 `approverRoles` 含活跃他人角色的调拨流，
  再由 1309 发起并尝试自审 —— 期望**被拒**。⚠️ 这一步之前，收窄对 F006 **不产生任何可观测变化**
- `WorkflowEngineServiceImpl.canTransition` 的超管无条件放行**仍在**（§3 末）
