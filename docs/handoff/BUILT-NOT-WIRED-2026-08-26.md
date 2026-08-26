# 四个「做了没接上」——逐个结论

**日期**: 2026-08-26 ｜ 全部为只读核查（含生产库探针），未改动任何代码

## 摘要

| # | 东西 | 真实状态 | 建议 | 代价 |
|---|---|---|---|---|
| 1 | 离线队列（~2125 行） | **被草稿 store 取代**，且它自己的指示器从未被渲染 | **删** | 低（纯删） |
| 2 | `PermissionGuard`（74 行） | 从未被采纳，RN 用直接调 `hasPermission()` 的写法 | **删** | 低（纯删） |
| 3 | 可配置质检项 | 端到端都做了，但**没入口**且生产 **0 条绑定** | **产品裁定** | 接入口很便宜，但没人走完过 |
| 4 | 草稿持久化 | **存得进、取不出** —— 唯一有真实用户损失的一条 | **接上** | 极低（一行 navigate） |

---

## 1. 离线队列 —— 建议删

**实测**：

- `enqueue()` 的调用方：**0**（队列永远是空的，`sync` 没有东西可同步）
- 唯一消费 `useOfflineQueue` 的是 `src/components/offline/OfflineIndicator.tsx`（342 行）
- 🔴 **而那个组件从未被渲染** —— 全 `src` 搜不到它被引用

**真正显示在屏幕上的是另一个同名组件**：`src/components/common/OfflineIndicator.tsx`（55 行），
在 `WSHomeScreen:448` 渲染，背后接的是 **`draftReportStore`**（即第 4 条）。

⇒ **形态 D：同一个概念两份，用的是另一份。** 离线能力实际由草稿 store 承担，
`services/offline/*`（`NetworkMonitor` 294 + `OfflineQueueService` 406 + `SyncService` 434 +
`types` 283 + `index` 39）+ `hooks/useOfflineQueue` 327 + `components/offline/OfflineIndicator` 342
≈ **2125 行**从未参与运行。

⚠️ 若要「接上」而不是删：得把每一个写接口都改道走 `enqueue`，那是**重做写入层**，不是接根线。

---

## 2. PermissionGuard —— 建议删

74 行的 UI 权限包装组件，**零使用**。

RN 现行做法是**直接调 `hasPermission()`**（6 处：`MainNavigator` 3 处、
`MaterialBatchManagementScreen` 等）。也就是说这个包装组件写出来之后，团队选了另一种写法，
它从未被采纳 —— 与第 1 条同形：**不是坏了，是被取代了**。

⚠️ 一次**被我自己否掉的误报**（留作记录）：我一度以为 `hasPermission` 存在两种调用签名、
其中一处必然传错参数。**核实后不成立** —— 那是三个不同的东西：
`navigationHelper.hasPermission(user, perm)`（2 参，在 `MainNavigator` 里被**别名成**
`checkUserPermission`）、`MainNavigator:51` 自己定义的 1 参**局部包装**、
以及 `MaterialBatchManagementScreen` 从 `types/auth` 引入的**另一个** `hasPermission`。
调用都是对的。

⇒ 但这本身说明一件事：**全仓有三个叫 `hasPermission` 的东西**。不是缺陷，
是下一个人（包括我）很容易据此得出错误结论的地形。

---

## 3. 可配置质检项 —— 需要产品裁定

**后端齐活**：`QualityCheckItemController` 8 个端点、`quality_check_items` +
`quality_check_item_bindings` 两张表、完整 DTO 组。
**前端也齐活**：`QualityCheckItemConfigScreen`，已注册进 `FAManagementStackNavigator`。

**但是**：

- `navigate('QualityCheckItemConfig')` 的调用数：**0** —— 屏注册了，没有任何地方跳过去
- 生产库：`quality_check_items` **3 条**（1 个工厂）、`quality_check_item_bindings` **0 条**

🔴 **0 条绑定的含义**：即使那 3 个质检项存在，它们**没有绑定到任何检验点**，
所以不驱动任何一次质检。有人建了 3 条就停下了 —— 很可能正是因为**下一步没有入口**。

⇒ 「接个入口」本身很便宜，但**没有人走完过这个流程**，所以要先回答：这个功能还要吗？
要，我接入口并补一条端到端验证；不要，连表带屏一起删。

---

## 4. 草稿持久化 —— 建议接上（四条里唯一有真实用户损失的）

**写入侧是通的**：`addDraft` 有 3 个调用点（`useReportWorkflow:81`、
`DynamicReportScreen:419`、`TeamBatchReportScreen:236`），持久化正常，
离线时 `OfflineIndicator` 会显示草稿条数。

**读取侧断了**：

- `DraftReportsScreen`（草稿管理，功能完整：恢复 / 删除 / 清空，
  `handleRestore` 会跳回对应报工屏）**注册在 2 个 navigator 里**
- 🔴 **`navigate('DraftReports')` 调用数：0** —— 没有任何地方跳过去
- 🔴 `WSHomeScreen:97` 算了 `draftCount`，**在整个文件里再也没被用过**（死变量，数字没显示）

⇒ **报工失败 → 存成草稿 → 用户再也打不开它。** 工作在静默累积，无法恢复。
这是四条里唯一**已经在伤害用户**的一条，而且屏本身是好的。

**接法**（很便宜）：`WSHomeScreen` 里把已有的 `draftCount` 渲染出来，
点击 `navigation.navigate('DraftReports')`。

---

## 共同形态

四条里有 **3 条**（1、3、4）是同一个形状，也正是 `#87 仓储空间` / `#96 车间大屏` 的形状：

▎**东西做完了、后端通了、屏也注册了 —— 但没有任何地方能走过去。**

web-admin 侧这个形状已经有闸了（`routeReachability.spec.ts`，存量 91 条）。
**RN 侧目前没有等价的闸** —— 第 3、4 条正是它会抓到的那一类。
