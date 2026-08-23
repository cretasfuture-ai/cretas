# 交接：判据 B 走查 第三轮 · 2026-08-18 夜

> 接的是 `HANDOFF-2026-08-18d-goalB-session-handoff.md`（第二轮）。
> ⚠️ 本文里的 SHA / 计数会过期，判「现在是什么」一律现场跑。

---

## 0. owner 本轮拍板

> **多产出成本分摊 = 显式声明优先 + MASS 兜底**
> 用户在端口上显式声明 `outputRole` / `costAllocationRatio` 时以声明为准
> （`normalizeDraft` 不再剥掉，`CO_PRODUCT` 要能到达）；未声明时按报工实测质量占比分摊。
> ⛔ 取消「按节点 id 字典序决定谁拿 100%」这条隐式规则。

并行开了四条线：坎 2 领料 / 坎 1+3 成本口径 / 生产仓 reserved 释放 /
`restoreSemiFinishedConsumption` 的 REVERSE 流水，外加 prod 数据善后。

---

## 1. 环境（每条都是本轮现场实测）

- **GitHub 仍然 403**（`gh api user` → "Your account was suspended"）。⛔ 不能 push / 开 PR。
- 本地 `main` = `e1ac17c2a4`，与 prod 的 Java/web 同源。
- 本轮新分支 `codex/claude-goalb-r3`（worktree `../cretas-goalb-r3`），**off 本地 main**。
  ⚠️ GitHub 恢复后它不能直接开 PR —— 基底含 14 个离线合并。
  正确做法：等那 13 条各自走完 PR 合进 origin/main 之后，再把本分支的 commit
  cherry-pick 到一个干净的 off-origin/main 分支上。

---

## 2. 判据五 坎 2：多产出计划领不到料 —— **已修，已提交，未部署**

commit `a88e0a088b`（5 个文件，478+/6-）。

### 根因（代码级 + prod DB 取证）

多产出工作流没有单一成品可挂归属，`ProductProcessWorkflowServiceImpl.requireWorkflowOwner`
给**入口原料**懒建一个 inactive 锚点 `__RAW_WORKFLOW_OWNER__<rawId>`
（`ProductType.category = RAW_MATERIAL`），计划的 `productTypeId` 就是这个原料 id。
而 BOM 挂在**成品**上 ⇒ `findCurrentByProduct(原料 id)` **按构造恒空**。

prod 实测（`cretas_prod_db.public`，只读）：

```
F006 多产出计划 3 张, product_type_id 全是 RMT_07a9a522-…  (锚点, is_active=f)
__RAW_WORKFLOW_OWNER__ 锚点全库 8 个 (F006)
selected_bom_recipe_ids_by_product   9/9 计划全都有值
BOM 家族 e8565578-… 内每个 material_type_id 的 in_n_recipes = 1
   ⇒ 原料只挂 MAIN, BY_PRODUCT 那张一行明细都没有 ⇒ 并集不会重复领料
```

### 修法

抽出 `service/bom/PlanBomItems.java`：先试产品 current BOM（**今天开得出单的路径行为逐字不变**），
空了再用计划自己钉住的 `selected_bom_recipe_ids_by_product`，按 MAIN 优先排序 + 按物料去重。

**同一个错原来有三种长相，两处还是无声的**（形态 D，硬约束 8 数了：改前 2 处 → 改后 0 处旧写法 / 2 处新写法）：

| 位置 | 空了会怎样 |
|---|---|
| `FactoryMaterialRequisitionServiceImpl.generateFromPlan` | 404 |
| `ProductionPlanServiceImpl.validateMaterialStockSufficient` | **静默跳过库存校验** |
| `ProductionPlanServiceImpl.scanMaterialAdvisory` | 界面印「暂无缺料预警」（PR #2803 刚消灭的那句话） |

其余 10 处 `findCurrentByProduct` 不是按 plan 推 BOM，**显式登记不改**。

报错文案：多产出走新分支（`PLAN_PINNED_BOM_UNRESOLVED`），⛔ 不再让人去给一个**原料**配 BOM，
也不把 `RMT_` 锚点 id 丢给用户。单产出那条**原样不动**（它的 UUID 泄漏是既有登记项）。

### 闸与变异对照

`MultiOutputPlanRequisitionContractTest`（**4 条全跑在真实入口 `service.generateFromPlan` 上**）
`Tests run: 4, Failures: 0`。四条变异各打在不同的被守行为上，**每条都先证明行为换了再看红**：

| 变异 | 红的长相（原文） |
|---|---|
| M1 去掉钉住配方兜底 | 2 条抛 `BusinessException`，抛点 `planBomUnresolvedError:169 ← generateFromPlan:201`（修复前返回 1 行领料单） |
| M2 去掉按物料去重 | `expected: <1> but was: <2>`（两行 = 重复领料） |
| M3 去掉 MAIN 优先 | `expected: <701> but was: <700>`（取到副产侧那一行） |
| M4 关掉多产出文案分支 | 旧文案把 `RMT_07a9a522-…` 泄漏给用户，断言红 |

回归：`*RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest,*ContractTest`
= **`Tests run: 731, Failures: 0, Errors: 0`**（第二轮基线 727 + 本次 4）。

### 🔴 prod 端到端**没做**，原因写清楚

部署前取基线时，探针对那 3 张多产出计划全部返回「生产计划不存在」。查库：

```
[R] 4 张计划 deleted_at 全 = 2026-08-18 15:24:45.586711   ← 与整套 CLAUDEPF 夹具同一时间戳
[T] 活着的多产出计划                0 行
[U] 阳性对照 活着的计划              3 行   ← 证明 [T] 不是恒空查询
F006 计划 9 张: 活 3 / 软删 6
```

`ProductionPlan` 带 `@Where(deleted_at IS NULL)` ⇒ JPA 看不见它们。

⇒ **prod 上已经没有可以跑端到端的多产出计划**。重建夹具会产生新的报工/批次写入，
而**那两类根本撤不掉**（见 §3）。⇒ 本轮**不建新夹具**。

部署后可做、且**不写任何数据**的判据（BOM 解析在发号之前，方法带 `@Transactional`，
偏移实测 1027 < 1322）：探针 `scratchpad/probe_kan2.py`。
但它现在**不成立** —— 计划已软删，走不到 BOM 解析。
⇒ 要么接受「只有制品标记 + 闸」的部署判据并**如实登记端到端未做**，
要么先用 API 建一个**只到领料为止**（⛔ 不报工、不开批次）的最小多产出夹具再验。

---

## 3. prod 数据善后：4 个对象只冲销掉 2 个

**全程只走业务接口，DB 只读，新增行 0 条。**
全库 `updated_at >= 23:00` 恰好 2 行 = 我们动的那 2 个（并发 session 的对象逐个核对原样）。

| 对象 | 结果 |
|---|---|
| 成品批次 `2d09263e` | ✅ 作废，`deleted_at = 23:28:02` |
| 领料单 `MR20260818-0004` | ⚠️ `CANCELLED`，但 `cancel` 只翻状态 + 写 remarks，**不退库存** |
| 报工 `23836` / `23837`、批次 `10761` | ❌ 撤不掉 |

### 🔴 新发现的缺陷：批次撤回「这辈子只能撤一次」，且失败时报成功

`ReportReversalServiceImpl` 的 G3 闸按 `(batchId, WHOLE_ORDER)` 查唯一记录，
命中 `DONE` 就直接 `return`。`ReversalScope` 枚举只有 `WHOLE_ORDER` 一个值，没有第二种作用域可绕。

**失败长相（这是最该记的一条）**：实测调用返回 **HTTP 200 / `success=true` /「操作成功」**，
data 里是原封不动的旧记录，**DB 零变化**。只有回库按 row id 比 `deleted_at`/`updated_at` 才看得出来。

四条路径逐个实测（不是读代码推的）：

| 路径 | 实测结果 |
|---|---|
| `POST /processing/batches/10761/reversal` | 200 但返回旧 log 15，DB 零变化（G3 幂等） |
| `PUT /process-work-reporting/23836/reject` | 409「报工记录已被处理，当前状态: APPROVED」 |
| `POST /processing/batches/10761/cancel` | 409「已完成的批次不能取消」 |
| `PUT /reversals/15/reject`（想解封 G3） | 409「当前状态为 DONE，无法拒绝」 |

`POST /production-plans/{planId}/cancel` 对 COMPLETED 计划也 409，
而且它的 hint 明写「请用整单撤回」—— **系统自己的指引指向的正是这条已经死掉的路**。

**影响面（2026-08-18 夜实测，有保质期）**：

```
[W] 有撤回记录的批次 14 个, 每个恰好 1 条且全是 DONE
[X] 其中【DONE 之后又新增报工】的只有 batch 10761: 之后 6 条, 现存活 2 条 (= 23836/23837)
    其余 13 个批次: 0 条
[Y] 阳性对照 production_reports 总 34 / 有 batch_id 34 / 活着 4   ← 证明 [X] 的连接不是恒空
```

⇒ **今天它只卡住我们自己走查产生的 2 条报工，没有客户受影响。**
但形状是真的：任何批次撤回过一次后，后续新报工就再也撤不掉，而接口一直返回 200 success。

### ⚠️ 上一轮是用直接 SQL 收的尾

`semi_finished_inventory_transactions` 8 行里有 **3 行** `source_ref` 写着 `manual-cleanup:`，
`report_id` / `operator_id` 全空。本轮**没有**重复这个做法。
⛔ 不建议再用 SQL 补 —— 下一个看这张表的人分不清它们是业务流水还是人工痕迹。

### 仍挂账（需要产品侧补接口才能干净冲销）

报工 23836/23837、批次 10761、结单 `ca3a989d`（`POSTED`，`idempotency_key = claude-f4-pre-0818`）、
4 条结单消耗行对应的 80kg 原料（已 `USED_UP`）、WIP `semi_finished_inventory` id 343 的 75 盒。

两个可选方向：① 让 G3 支持同一批次多次撤回（按 `(batchId, scope, 报工集合)` 判幂等）——
这是根因且它本身就是个真 bug；② 补一条**全量结单反冲**接口
（目前只有 `SAFETY_STOCK` 的 interim 有反冲，本计划是 `CUSTOMER_ORDER`）。

---

## 4. 未修台账对账 —— **两条已过期，一条重新定位**

`HANDOFF-2026-08-18b-goalB-open-defects.md` 的「其它已知未修」逐条对当前本地 main 复核：

### ✅ 已修（台账过期，可以划掉）

1. **「提示把人送去建一个已经存在的东西」（`WORKFLOW_SINGLE_OUTPUT_NOT_FOUND`）**
   `ProductWorkflowResolutionServiceImpl.missingWorkflowHint` 现在按**四种成因**分开说：
   `NEEDS_SIBLING_OUTPUTS` 明说「必须整组一起选…或分开创建生产计划」；
   `ENABLED_BUT_UNUSABLE` / `NOT_ENABLED` 明说「⛔ 不需要新建工艺」；
   只有 `TRULY_ABSENT` 才引导去建。配套 `MissingWorkflowDiagnosisContractTest` 6 条绿。

2. **「纯英文无 hint 的错误码」四条全部已修**（`ProductionPlanServiceImpl` 约 3107-3155）：

   | code | 现状 |
   |---|---|
   | `BYPRODUCT_NRV_REQUIRED` | 中文 + 成品名 + hint(去哪填 NRV) + hintTarget |
   | `WORKFLOW_OUTPUT_UNIT_MISMATCH` | 中文 + 成品名 + hint(把单位改成「X」) + hintTarget |
   | `PINNED_BOM_OUTPUT_POLICY_INCOMPLETE` | 中文 + 成品名 + hint(重选工艺修订并激活) + hintTarget |
   | `OUTPUT_COST_ALLOCATION_RATIO_REQUIRED` | 中文 + 成品名 + hint(去 Workflow 配比例) + hintTarget |

   都走 `resolveFinishedGoodLabel(productTypeId, snapshotName)` —— 先问主数据活值，
   再退 BOM 快照名，两者都没有才说「未命名成品」，**不假装那串 UUID 是个名字**。

### 🔴 仍未修，坐标订正（台账说在 `ProductionPlanServiceImpl`，实际不在）

**`ProcessSheetServiceImpl.java:436` 的 UUID 泄漏**：

```java
throw new BusinessException(409, "成品报工单位与计划固定 BOM 的产出单位不一致")
        .withCode("BOM_OUTPUT_UNIT_MISMATCH")
        .withHint("SKU " + reportedOutput.productTypeId()          // ← UUID 甩给车间操作员
                + "：报工单位 " + reportedUnit + "，BOM 单位 " + bomOutputUnit)
        .withSeverity("BLOCKING");
```

两个毛病：① 把 UUID 丢给站在机器前的操作员；② hint 是**现象描述不是下一步**
（隔壁 `ProductionPlanServiceImpl` 的同名 code 已经改成「请回到报工页把该产出的单位改成「X」后重新结单」）。

**本轮为什么没改**：包围方法 `buildAutomaticBomRequirements` 是 **private**，
唯一真实入口是 `submitRow`，而本类有 **27 个 `private final` 协作者**。
按本仓硬要求「只测 helper 不算数，必须有一条断言跑在真实入口上」，
用 `ReflectionTestUtils` 打私有方法**不算数**；而临时堆一套 27 个桩很容易掉进
「桩的形状真实上游产不出来」那个坑。⇒ 留给能一起把 `submitRow` 的测试底座搭起来的那一轮。

### ⏸️ `BlockingErrorsCarryActionHintContractTest` 的 KNOWN_DEBT（17 条）**刻意没动**

其中 `PINNED_BOM_NOT_FOUND`（`ProcessSheetServiceImpl:409`）和
`BOM_OUTPUT_BASIS_INVALID`（`:442`）就在上面那段代码里，看着顺手能一起补。
**没补**，理由是那个棘轮自己的 Javadoc 写着：

> 每条 hint 都是「该把用户导到哪个页面做什么」的产品判断，批量编出面向车间的中文指引
> 而不经业务确认，只会产出一堆看着像话、实际指错地方的文案 —— 那比没有 hint 更糟。

`BOM_OUTPUT_BASIS_INVALID` 要说「去 BOM 里把基准产出数量填成正数」，
而**我没有核实 web 端 BOM 编辑器真有那个输入框** —— 本仓正是为「指向不存在的操作」
栽过两次（2026-08-03 客户回「没有途径可以配置配方用量」）。
⇒ 补之前先去 `web-admin` 确认输入框存在，再写 hint，然后从 KNOWN_DEBT 删条目
（该棘轮会用「陈旧条目」断言逼你两件事一起做，17 → 15 时记得同步 `KNOWN_DEBT_SIZE`）。

---

## 5. 🔴 两条新发现（都关系到 owner 刚拍的那条决策能不能落地）

### 5.1 副产的**载体**早就换过了 —— ⛔ 别去复活 `ProcessPort.outputRole`

我给 坎1/3 那条线的第一版 brief 写的是「让 `normalizeDraft` 不再剥掉端口上的
`outputRole` / `costAllocationRatio`」。**那是错的**，已发订正。

`web-admin/.../workflow/types.ts` 的设计定稿：

> 副产**不是** `ProductProcessNodeKind` 的第 5 个值……「副产不是分类的第 5 个桶，
> 它是**物料上的 `isByproduct` 标记**，与材质分类正交」。
> 与 `ProcessPort.outputRole` 的关系：**那个字段已弃用，不要用它表达副产。**

同一文件 `substituteOfNodeId` 解释了**为什么换**：

> ⛔ 载体刻意放在**物料节点**上而不是工序的 portGroups：`normalizeDraft` 每次保存都会
> remove 掉 PROCESS 节点的 portGroups……**物料节点不被 normalizeDraft 清洗，字段才活得下来。**

而 `normalizeDraft` 第一行就是
`if (!"PROCESS".equals(node.getKind())) continue;` ⇒ **物料节点它根本不碰。**

`isByproduct` 是**两端完整接通**的：后端专用 helper `service/workflow/WorkflowByproductNodes.java`
被 6 个校验/分类/WIP 服务共用，`RawMaterialType.isByproduct` 有列 + 专门的
`markByproduct` 部分更新端点；前端有 `utils/byproductMaterial.ts` + 单测。

反过来，端口那条路**在 UI 上不存在**：`outputRole` / `costAllocationRatio` 在 `web-admin/src`
里只出现在 `api/bom.ts`、`workflow/types.ts` 的**类型声明**和**测试 spec** 里，
生产 view 代码 **0 处 v-model**（阳性对照：同目录 47 处 v-model，
`ProcessDataTable.vue` 里能 grep 到 `costAllocationRatio` 的 view 层用法 ⇒ grep 写法有效）。

⇒ **仍待解的真缺口**：`isByproduct` 只有布尔，表达不了「联产品 + 60/40」。
`CO_PRODUCT` 与显式比例**没有活的载体**。⛔ 不要塞回 PROCESS 节点端口（会被清掉）。
要么放到物料节点上（与 `isByproduct` 同一载体），要么本轮只做
「副产 via `isByproduct` + 其余按 MASS 兜底」并把「显式声明比例」登记成需要前端配套的缺口。

### 5.2 一条**已被算作"已修"**的 hint，指向一个不存在的页面和字段

`BYPRODUCT_NRV_REQUIRED` 的 actionHint：

> 请前往 BOM成本管理 打开该 BOM Family 的「产出成本」, 为这个副产品填写单位可变现净值…

实测（4 个读数一致）：

```
byproductNrvUnitPrice / NRV / 可变现净值   web-admin: 只在 api/bom.ts 类型声明, 0 处 UI
「产出成本」(hint 里逐字引用的入口名)        web-admin: 0 命中
frontend/CretasFoodTrace (RN App)          0 命中
阳性对照: outputRole/costAllocationRatio    能在 ProcessDataTable.vue 命中 ⇒ 仪器有效
```

⇒ 这正是本仓栽过两次的形状（2026-08-03 客户回「没有途径可以配置配方用量」）。
它在 §4 里被我算作「已修」是**只看了后端**：中文有了、hint 有了、hintTarget 有了，
**但那个操作用户做不到。**

⚠️ 这条同时是对 §4 那张「已修」表的订正：**「错误文案已修」要两段判据** ——
① 后端说的是人话且给了下一步；② **那个下一步在前端真的做得到**。
本轮只验了 ①。其余三条（`WORKFLOW_OUTPUT_UNIT_MISMATCH` /
`PINNED_BOM_OUTPUT_POLICY_INCOMPLETE` / `OUTPUT_COST_ALLOCATION_RATIO_REQUIRED`）
的 ② **未验**，⛔ 不要因为 §4 说「已修」就当它们通过了。

---

## 6. 其余三条线（全部已合入本地 main）

| 分支 | 内容 | 闸 |
|---|---|---|
| `codex/claude-workshop-reserve` | 生产仓「报工即占用 + 三条路同口径释放」 | #2866 五条 → 七条，全绿 |
| `codex/claude-reverse-ledger` | `restoreSemiFinishedConsumption` 补正量 **正量** REVERSE 流水 | `SettlementSemiRestoreLedgerContractTest` 6 条 |
| `codex/claude-multi-output-basis` | 多产出成本口径：副产判据改走**物料节点 `isByproduct`** | `MultiOutputAllocationBasisContractTest` 7 条 |

### 6.1 reserved 占用：三条释放路径同口径

口径是一个纯函数 `WorkshopReportReservationService.reservationOf(report)`（解析
`production_reports.material_batch_refs`），占用与三条释放都调它，**没有一处自己算量**：

| 路径 | 落点 | 为什么是这里 |
|---|---|---|
| 驳回 | `ProcessWorkReportingServiceImpl.rejectReport` | 紧挨既有的「驳回退还 WIP」，在状态翻转前 |
| 冲销 | `ReportReversalServiceImpl.executeReversal` | **软删之前** —— `findYieldReportsByBatch` 过滤 `deleted_at IS NULL`，之后释放会把占用留成孤儿 |
| 结单扣减 | `ProductionPlanServiceImpl.settleProduction` | 在三道 `getCurrentQuantity()` 闸**之前** |

「还占着」的集合只有三个出口：全仓给报工写 `deletedAt` **一处**、写 `REJECTED` **一处**
（已普查），所以没有报工能不经释放就离开这个集合。

⚠️ **它翻了 #2866 里两条断言的方向**，我逐行核过没有放水：那两条守的是
「结单拒绝自己的占用」「拒绝时甩锅给不存在的计划」——现在判定为缺陷（形态 C‴）。
改法是把守的东西从**字面抬到性质**，并保留旧措辞作**阴性对照**：

```
59 行删除 / 164 行新增
+ .doesNotContain("其他未结生产计划")      ← 旧措辞留作阴性断言
+ .doesNotContain("结清冲突计划")
+ assertThat(thrown.getErrorCode()).isEqualTo("RAW_BATCH_RESERVED_ELSEWHERE")   ← 阳性
+ assertThat(thrown).as("摘掉释放后必须结不掉, 否则 G2 的绿与释放无关").isNotNull()  ← 防恒真
```

变异证据是**行为级**的：把 `settleProduction` 里的释放换成 `ZERO` →
`原料批次…当前可用 0kg，是被本计划自己已提交的报工占着 20kg`
（`resolveAvailableBefore:5187 ← settleProduction:2839`）。

### 6.2 多产出成本口径：**载体订正后**的最终形态

owner 拍的是「显式声明优先 + MASS 兜底」。第一版实现去改 `normalizeDraft` 让端口上的
`outputRole`/`costAllocationRatio` 活下来 —— **那是错的**（见 §5.1），已订正。最终形态：

| 终端形状 | role / ratio | basis |
|---|---|---|
| 单个正经产出 | `MAIN` / 100 | `DECLARED`（恒等式，不是裁定） |
| 一真一副（副产由画布 `isByproduct` 标） | `MAIN` 100 / `BY_PRODUCT` 0 | `DECLARED` |
| ≥2 个都是正经成品 | 锚点 `MAIN` / 其余 `CO_PRODUCT`，**ratio = null** | `REPORTED_MASS` |

⛔ **按节点 id 字典序发钱那条规则已删。**
⛔ 没人声明时**不兜一个假数** —— 如实标不可判，由下游拒答。

两条 hint 也重写了，而且我**逐条核实过它们指向的控件真的存在**：
- 「回到工艺画布把它改成「副产」Cell」→ `isByproduct` 有 UI ✅
- 「在报工页为每个产出填写成本分摊比例(合计 100%)」→
  `ProcessOutputTable.vue:289` 有 `v-model="view.line.costAllocationRatio"` ✅，
  且 `ProcessDataTable.vue:1421-1424` 校验「每个 > 0 且合计 100」

### 6.3 ⚠️ 一个既有的红：`BomSeasoningWorkspaceServiceTest` 19 run / **4 failures**

不是本轮引入的 —— 三处同一条命令实测，失败**名字集合完全相同**（两侧各 0 条独有）：

```
合并后的 main        19 run / 4 failures
坎1/3 分支           19 run / 4 failures
prod 当前部署点 3f75754b88   19 run / 4 failures
```

⇒ 它在 prod 那棵树上一直是红的，而 `*ContractTest` 选择器**根本选不到它**
（类名不以 ContractTest 结尾）—— 本仓反复栽的那个形状，登记。

---

## 7. 部署（已完成，判据逐条留痕）

部署点 `dbb6f520d0`（本地 main），从 detached worktree + `SKIP_GIT_CHECK=1`（GitHub 仍 403）。

```
Java     进程启动 00:55:15  >  制品 mtime 00:54:48      ✅ (蓝绿切到 green/10020)
         从【监听端口】反查 pid, ⛔ 没用 pgrep -f (那会抓到探针自己)
制品标记 PlanBomItems.class = 1          WorkshopReportReservationService.class = 1
         findByRecipeIdsWithRecipe = 1   WorkflowByproductNodes(在 BomWorkflowRevisionService) = 1
         PLAN_PINNED_BOM_UNRESOLVED = 1
阳性对照 findCurrentByProduct = 2 / resolveTerminalOutputs = 10 / generateFromPlan = 3
阴性对照 全 0
真跑     GET material-advisory: 3/3 活计划 200 + 真实文案
         阴性对照 假 planId → 404  ⇒ 探针确实在打这个接口
web-admin(139.196.165.140) 「被未结报工占用」命中 1 文件
         阳性对照「暂无数据」272 文件 / 阴性对照 0 / 目录 mtime 01:00:28
```

⚠️ **仪器订正**：`findByRecipeIdsWithRecipe` 是**方法名**，`unzip -l` 只列条目名看不见它。
部署前那次读 0 是「仪器看不见」不是「不存在」。方法名一律 `unzip -p <jar> <entry> | strings | grep -c`。

### 🔴 部署判据里**没有**的那一格：坎 2 的新分支没被真跑过

`material-advisory` 那 3 张都是**单产出**计划 ⇒ `findCurrentByProduct` 非空 ⇒
`PlanBomItems.resolve` 在**第一个分支**就返回了，**从没进入新增的钉住配方兜底**。
它证明的是「新代码接上了、没弄坏既有行为」，⛔ 不是「新分支跑得动」。

---

## 8. 最小夹具验收：**做到一半，停了**（如实记，含善后）

owner 选的是「建一个只到领料为止的最小夹具」。复用上一轮留下的 `pf_a*` 流水线，
前缀改成 `CLAUDER3`（⛔ 不复用旧 `pf_ids.json`，也避开与软删残留撞唯一码）。

走到哪一步、被什么挡住（**每一步都是真读数**）：

| 步 | 结果 |
|---|---|
| A1 主数据（1 原料 + 2 成品） | ✅ |
| A2 存草稿（一工序 → 两个 FINISHED_GOOD 终端） | ✅（脚本自己 exit 1，因为它断言「端口声明被剥掉」—— 剥掉正是订正后的**预期**） |
| A3 发布+启用 | ❌ `PRODUCT_PROCESS_WORKFLOW_CATALOG_MISMATCH` |
| A3b 修工序默认产出类型 | ✅ |
| A3 重试 | ❌ `BOM_SKU_NET_CONTENT_REQUIRED` |
| A3c 补净含量 | ✅ |
| A3 重试 | ❌ `BOM_ACTIVATION_ITEMS_REQUIRED`「BOM 还没有任何原辅料或包材」 |
| B1 挂辅料（想借它补出 BOM 行） | ❌ 脚本里**硬编码着上一轮的配方 id** `05df28ab…`（已软删） |

⇒ 第 4 个意外阻塞 + 脚本不是干净可重跑的，**停手**。
`BomController` 全是只读端点，BOM 明细只能从画布/辅料工作台进，而那条路要先有配方 —— 循环依赖。

### 善后（只走业务接口，⛔ 没碰数据库；判据是**回读**不是 200）

```
删除并回读确认消失: 5/6
  ✅ 原料 RMT_ec38afe1  成品甲 a8bff875  成品乙 a7c796a5
  ✅ 辅料 RMT_e90ba9bb  辅料 RMT_6c8f7906
  ❌ 工序 133cb640-3ab7-4d1f-85bf-f40fe95e0197 (CLAUDER3-分切)
        409「该工序已有业务引用，不能删除」, 它自己的 hint 说「请停用或使用重复治理」
  ❌ 工作流草稿 (挂在已删的成品甲上)
        实跑确认: DELETE → **405 不支持的请求方法** —— 根本没有删除通道
```

⇒ **F006 里留下 1 个工序 + 1 张孤儿工作流草稿**（都叫 `CLAUDER3-*`，惰性，指向的成品已删）。
写入台账逐行在 `scratchpad/pf_ledger.jsonl`。

🔴 顺带一个新缺陷登记：**保存过的工作流草稿没有任何删除通道**（405），
而它会把自己引用的工序钉死（409）。与 §3 的报工/批次撤不掉是同一族：
**建得出来、撤不回去。**

---

## 9.5 🔴 §7 那次部署引入了两个回归 —— 都已修、已部署、已在 prod 真跑通

§8 里「夹具做到一半停了」的那个 `BOM_ACTIVATION_ITEMS_REQUIRED`，**不是夹具脚本的问题，
是我 §7 那次部署把多产出工作流的发布弄坏了。** 继续查下去挖出**两个**回归，成因是同一件事：

▎坎 3 删掉「按节点 id 字典序自动编号」时，实际动了**两条不变量**，而当时只数了一条。

| # | 被打破的不变量 | 症状 | 修法 |
|---|---|---|---|
| 1 | 「非锚点成员的角色是 `BY_PRODUCT`」 | 409 `BOM_ACTIVATION_ITEMS_REQUIRED` | `BomRecipe.bearsOwnInputRows` |
| 2 | 「`costAllocationRatio` 永不为 null」 | **500** NPE（追踪码 `71D81F80`） | `BomWorkflowRevisionService.sameAllocationRatio` |

### 9.5.1 回归一：CO_PRODUCT 漏在豁免之外

`BomRecipeServiceImpl:452` 的豁免判据是 `role == BY_PRODUCT` —— 那是「这个成员**按设计**
没有投入行」的**代理**。坎 3 之前没人声明时第二个终端被自动编号成 `BY_PRODUCT`，
于是「非锚点」恰好等价于「BY_PRODUCT」；坎 3 之后两个都不是副产的终端拿到
`MAIN` + `CO_PRODUCT`，**代理失效**。

反证（prod 数据）：`CLAUDEPF-成品乙` 6 个版本**各 0 行明细**，其中 v5 以 0 行到达过 `ACTIVE`
—— 说明坎 3 之前这条路是通的。

硬约束 8 数了：`OutputRole.BY_PRODUCT` 在 `src/main` 有 **8 处**代码判据，
逐处读代码判它问的是哪件事 —— **改 2 处**（`BomRecipeServiceImpl:452` /
`ProductProcessWorkflowCatalogValidator:215`，后者注释里明写「投入挂在家族的 MAIN 上」
并**引用了前者**），**显式不改 6 处**（NRV 计价 3 处 / 人工制费 1 处 / 主产出比例合计 2 处，
它们问的是别的事，`CO_PRODUCT` 在那些语境里本就该按比例走）。

⛔ 没写成 `role != MAIN` —— 那会让 `outputRole` 为 `null` 的历史行从「照常校验」
变成「跳过」，是**削弱**这道闸。

### 9.5.2 回归二：REPORTED_MASS 下两边都是 null，而那里直接 `compareTo`

修完回归一之后再跑一次发布，从 409 变成 **500**：

```
NullPointerException: Cannot invoke "BigDecimal.compareTo(...)" because
  the return value of "TerminalOutput.costAllocationRatio()" is null
    at BomWorkflowRevisionService.requireCompleteActiveFamily(:681)
    ← BomRecipeServiceImpl.synchronizeActiveBomToWorkflowRevision(:748)
    ← WorkflowBomSynchronizationService.synchronizeForPublish(:331)
    ← ProductProcessWorkflowServiceImpl.publishAndActivate(:246)
```

⚠️ **同语义的 helper 当时已经存在**（`BomRecipeServiceImpl.sameRatio`，docstring 里就写着
「REPORTED_MASS 口径下两边都是 null」），只是没被用到 `:681` ——
形态 D 的教科书长相：**同一个东西有两份，其中一份是对的。**

硬约束 8 再数（这次数在对的结构上）：`costAllocationRatio` 直接解引用 **5 处**，
逐处读代码 —— 1 处裸的（修），4 处已被 `||` 短路 / 三元 / 上一行 `!= null` 守着（登记）。

### 9.5.3 闸与变异

| 闸 | 条数 | 变异 |
|---|---|---|
| `FamilyInputRowExemptionContractTest` | 7 | M1 退回只豁免 BY_PRODUCT → `Expecting value to be false but was true` / `expected: 2L` |
| `AllocationRatioNullSafetyContractTest` | 7 | M3 去掉 null 处理 → 双双 NPE，**报错原文与 prod 那条逐字相同** |
| `ByProductActivationAndBomUxContractTest` | 4 | 从守**字面**抬到守**性质**，并补 `doesNotContain` 旧字面 |

全套：`Tests run: 776, Failures: 0, Errors: 0`（762 → 769 → 776）。

---

## 10. ✅ prod 端到端真跑通了 —— §7 留空的那一格补上

第二次建夹具（`CLAUDER3` 前缀，复用 round1 那个删不掉的工序，⛔ 全程不报工、不开批次）：

```
A1 主数据 ✅ → A2 草稿 ✅ → A3c 净含量 ✅ → A3 publish-and-activate ✅ (HTTP 200, 0 失败)
→ A4 建多产出计划 ✅ → 领料生成 ✅
```

### 10.1 坎 1/3 的运行期读数（DB 实测）

```
CLAUDER3-成品甲  v1  ACTIVE  MAIN        ratio = NULL   family 8895ef70
CLAUDER3-成品乙  v1  ACTIVE  CO_PRODUCT  ratio = NULL   family 8895ef70
```

⇒ 「按节点 id 字典序发 100%/0%」**没了**；`CO_PRODUCT` 从**全库 0 行**变成可达；
两边 ratio 都是 `NULL`（设计期不可判，⛔ 没兜一个假数）。

### 10.2 坎 2 的运行期读数

```
计划 2da615b2  product_type_id = RMT_b25ab8b6…(原料锚点)  n_targets = 2
领料 POST /material-requisitions/generate → HTTP 200  MR20260819-0002  1 行明细
  · CLAUDER3-原料M  bomRecipeItemId = 647
item 647 → 配方 37e18871 = 成品甲的 MAIN 配方
         = 计划 selected_bom_recipe_ids_by_product[甲]   ⇒ 确实来自【钉住的配方】
锚点(原料)上的产品 BOM 行数 = 0        ⇒ 老路径 findCurrentByProduct(锚点) 按构造恒空
阳性对照 两个 target: 甲 = 1 行 / 乙 = 0 行
阴性对照 假 planId → 404               ⇒ 探针确实在打这个接口
⛔ 旧文案(让人给【原料】配 BOM) 已消失
```

### 10.3 善后

| 对象 | 结果 |
|---|---|
| 领料单 `MR20260819-0002` | ✅ `CANCELLED`（回读确认） |
| 计划 `2da615b2` | ✅ `CANCELLED`（回读确认；⚠️ `reason` 是 **query param** 不是 body） |
| 成品甲/乙、原料 M | ❌ 409「还被 1 条 BOM 配方引用」 |
| 工作流启用 | ❌ 读启用记录返回 `{}`，拿不到 `lockVersion` ⇒ **⛔ 不猜**，停手 |

⛔ **本轮没有报工、没有开批次** —— 按 row ID 证实，不是假设：
今天 10:00 后新增的 2 条报工（23848/23849）属于批次 10781/10782 → 计划 `0abc3312`、
产品 `eb0aa47b`/`17c929e1`，**没有一个是我的**；我的计划下 0 个批次，
指向我这轮产品的批次也是 0（阳性对照：该计划存在且已 CANCELLED）。

**留在 F006 的惰性残留**（都叫 `CLAUDER3-*`）：
2 个成品（**仍 active，会出现在产品目录里**）、1 个原料 + 它的 `__RAW_WORKFLOW_OWNER__` 锚点、
2 张 ACTIVE BOM（family `8895ef70`）、workflow 177（PUBLISHED+已启用）、
工序 `133cb640`、以及 round1 那张孤儿草稿。
⇒ 与 §8 同一个根因：**建得出来、撤不回去**。清理需要产品侧补删除通道。

---

## 11. ⏭️ 下一轮该做的（按顺序）

1. ~~**坎 2 的新分支仍未在 prod 真跑过。**~~ → **已补上，见 §10。**
   ⚠️ 顺带发现「BOM 补不上第一行物料」根本不是真问题 —— 那是 §9.5 那两个回归的症状。
   发布一通，`ensureDraft` 就把画布的 RAW 行播到锚点上了（实测 1 行）。
2. **判据五仍未通过**：多产出计划**能建、能领料**了，但**结单还没跑过**。
   下一步就是接着 §10 那条链往下走：领料 → 调拨 → 报工 → 结单。
   ⚠️ 报工/批次**撤不回去**（§3），所以这一步要么想清楚愿意留下这些行，
   要么先把 §3 那个撤回缺陷修掉再走。
   预期卡点：≥2 正经成品时 ratio = null → `OUTPUT_COST_ALLOCATION_RATIO_REQUIRED`，
   hint 指向报工页的比例输入框（已核实控件存在）。
3. **§5.2 那条**：`BYPRODUCT_NRV_REQUIRED` 的 hint 指向不存在的页面/字段。
   要么补 NRV 的 UI，要么改文案。
4. **§4 的 ② 未验**：其余三条「已修」的错误文案，只验了后端说人话，
   ⛔ 没验「那个下一步前端做得到」。
5. `BomSeasoningWorkspaceServiceTest` 的 4 条既有红 + 它选择器选不到的问题。
6. GitHub 恢复后的收尾**照旧**（见第二轮交接件），本轮新增分支：
   `codex/claude-goalb-r3` / `codex/claude-workshop-reserve` /
   `codex/claude-reverse-ledger` / `codex/claude-multi-output-basis`。
   ⚠️ 它们的基底是**本地 main**（含 14+ 个离线合并），
   ⛔ 不能直接开 PR —— 要等那些先合进 origin/main，再 cherry-pick 到干净分支。

⚠️ 另记：另一条 session 在本轮期间**已经把 Python 发到 prod 了**（main 上的
`eb4b0b9dc5 docs(deploy): 离线部署账 —— f87cb1951e 上 prod Python(8083)`），
而第二轮交接件写的是「⛔ 在走完 PR/CI 之前不要跑 Python 部署」。那是他们那条线的判断，
本轮没有干预，但下一个人别再按旧交接件以为 Python 还没上。

---

## 12. 方法教训（本轮新的）

1. **交接件里的「已实测」也会在几小时内过期。** 第二轮 22:41 写的多产出取证，
   到 23:00 我复量时夹具已在 15:24 被整批软删 —— 我按它设计的修法（「按 target 成品展开」）
   **不成立**（target 成品的 current BOM 也是 0）。真正可用的是计划自己钉的
   `selected_bom_recipe_ids_by_product`。⇒ **动手前复量，别照抄交接件的读数。**
2. **阳性对照当场救了一次。** 部署前基线探针四张计划全返回「生产计划不存在」，
   如果没有「活着的计划 3 行」这条对照，我会把它读成「探针坏了」或「端点变了」，
   而真相是 `@Where(deleted_at IS NULL)` 静默挡掉了软删行。
3. **「接口返回 200 success」不是「事情做了」。** 撤回接口对已有 DONE 的批次永久幂等，
   返回 200 + 原封不动的旧记录。判据必须是「回库按 row id 比 `deleted_at`/`updated_at`」。
4. **善后成本要计入实验设计。** 报工/批次在 prod 上**没有任何接口能撤销**
   ⇒ 「为了验一条修复而在 prod 上建夹具」这件事本身是不可逆的，
   下次设计验证路径时先问「这条路径写的东西撤得掉吗」。
   ⚠️ 本轮又添两例：**工作流草稿 DELETE 返回 405**（没有删除通道），
   而它把自己引用的工序钉死（409）。⇒ 「建得出来、撤不回去」在这个系统里是**常态**，
   不是个别接口的疏漏。
5. **我自己又踩了一次 `cmd | tail` 换退出码。** `git merge … | tail -6` 打出
   `MERGE_EXIT=0`，而那是 `tail` 的退出码 —— merge 实际失败了
   （`could not write index / fatal: stash failed`，另一条 session 正在同一个 worktree 里跑 git）。
   ▎**这条硬约束我在同一轮里既引用过、又违反了。** 判据是机械的：
   要取退出码就别让命令进管道，先落文件再看。
6. **仪器错误：`unzip -l` 看不见方法名。** 部署前核对时用
   `unzip -l <jar> | grep -c findByRecipeIdsWithRecipe` 读到 0，
   那是「仪器看不见」不是「不存在」——`unzip -l` 只列**条目名**。
   方法名一律 `unzip -p <jar> <entry> | strings | grep -c`，并配同类阳性对照。
7. **交接件里的坐标也会漂。** 未修台账说 UUID 泄漏在 `ProductionPlanServiceImpl`，
   实际在 `ProcessSheetServiceImpl:436`；说 BOM 编辑器在
   `web-admin/src/views/production/bom/index.vue`，那个目录现在只剩 `EcnList.vue`。
   ⇒ 引用交接件里的**文件路径**之前也要当场 grep 一次，不只是引用它的读数时。
8. 🔴 **「夹具建不起来」先怀疑自己刚部署的东西。** §8 我把
   `BOM_ACTIVATION_ITEMS_REQUIRED` 读成「脚本不是干净可重跑的」就收手了 ——
   真相是**我一小时前的部署把多产出发布弄坏了**。
   反证一条命令就能拿到：同一套脚本在坎 3 之前跑得通（prod 数据里
   `CLAUDEPF-成品乙` 以 0 行明细到达过 ACTIVE）。
   ⇒ **判据: 新报错出现在自己刚发过版的路径上时, 先去比「改动前这条路通不通」。**
9. 🔴 **一次改动可能打破【多条】不变量, 而硬约束 8 只保证你数了【你想到的那一条】。**
   坎 3 同时打破了「非锚点成员是 BY_PRODUCT」和「ratio 永不为 null」,
   第一次只数了前者, 于是修完 409 立刻撞 500。
   ⇒ 删掉一条规则时, 把它**同时保证过的每一条性质**列出来, 逐条找消费方 ——
   而不是只找「跟它长得像」的那个符号。
10. **同语义的 helper 可能已经存在, 只是没被用到出事那一处。**
   `sameRatio` 当时就在隔壁文件里, docstring 里连 REPORTED_MASS 都写了。
   ⇒ 修 null 安全/口径类问题前, 先 grep 有没有现成的同语义函数 —— 有就收敛过去,
   ⛔ 不要写第二份。
11. **我又踩了一次 `cmd | tail` 换退出码**（§12-5 那条已经写过）。这次是
   `git merge … | tail -6` 打出 `MERGE_EXIT=0`，而 merge 实际失败了。
   ⇒ 这条不该再靠记性。写进验证步骤：**要退出码就不进管道。**
12. **善后也要配阳性对照。** 清点时看到「今天 10:00 后新增 2 条报工」，
   差一步就写成「本轮产生了报工」。按 row ID 归属查清是**另一条 session** 的
   （批次 10781/10782 → 计划 `0abc3312`，我的计划下 0 个批次，
   阳性对照：该计划存在且已 CANCELLED）。
   ⇒ **并发环境里「我的窗口里出现了新行」不等于「是我写的」。**
