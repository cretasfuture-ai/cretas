# 交接：销售链跑完一圈了 —— 应收比客户实际欠款少一个税额

> 上一棒（`HANDOFF-2026-08-28d`）说「只推进了一步，一分钱一盒货都没流转」。
> 这一棒把 `SO-20260828-0003` 从**财务已批准**走到**收款已确认**，
> 库存真的动了、钱真的记了 —— 然后四个数**对不上**，缺口正是增值税。

**证据全部来自生产环境实操**（F006 团队测试租户，写入已授权），
每一步都是**真用浏览器点的**，每一步之后都回 `cretas_prod_db` 核过。

---

## 🔴 P0：应收挂账记的是【未税】金额，而发票和客户欠款是【含税】

### 一句话

货一发出，系统自动挂应收 —— 挂的是 `sales_orders.total_amount`（**未税**），
**没有加 `tax_amount`**。同一次发货生成的销项发票却是**含税**的。
⇒ **每一张销售订单的应收都少记一个税额（13% 的单据即少 11.5%）**，
而且客户一付未税金额，系统就判定**已结清**。

### 实测：SO-20260828-0003（叮咚 / 黄油鸡成品800g 10盒 / 含税单价 ¥25.00）

| 环节 | 落库金额 | 口径 |
|---|---:|---|
| 销售订单 未税总额 | 221.24 | `sales_orders.total_amount` |
| 销售订单 税额 | 28.76 | `sales_orders.tax_amount` |
| **销售订单 含税总额（客户实际应付）** | **250.00** | 详情页抬头就这么显示 |
| 出货金额 | 221.24 | `sales_delivery_records.total_amount`（未税，合理） |
| **应收 AR-20260828-8447** | **221.24** | ⛔ **应为 250.00** |
| **开票 INV-20260828-50266B812F 价税合计** | **250.00** | ✅ 正确 |
| 收款 PAY-20260828-0019（已确认） | 221.24 | 跟着应收错 |

**系统自己跟自己矛盾**：同一次发货事件产出的两条记录，
发票说客户欠 ¥250.00，应收台账说客户欠 ¥221.24。

收完款后 `ar_ap_transactions` 对这个客户净额归零 ⇒
**客户在账龄表/对账单上显示「无欠款」，而一张 ¥250.00 的销项发票已经开出去了。**
账面上永远看不到这 ¥28.76。

### 同屏两处给出不同的数（截图证据）

销售订单详情页 → 收款记录 tab，**同一屏**上下相邻：

```
已开票  ¥250.00          ← 抬头
订单总额 ¥221.24 / 已收 ¥0.00 / 待收 ¥221.24   ← 收款区
「一键收款 (全额)」对话框: 应收余额 ¥221.24, ... 一键登记?
```

出纳点「一键收款（全额）」→ 收 ¥221.24 → 单据显示已结清。
截图: `out/10-payment/payment-tab.png`

### 根因（一行）

`backend/java/.../service/inventory/impl/SalesServiceImpl.java:3136`

```java
arApService.recordReceivable(factoryId, order.getCustomerId(), order.getId(),
    order.getTotalAmount(),          // ← 未税。缺 .add(order.getTaxAmount())
    LocalDate.now().plusDays(30), userId,
    "销售发货自动挂账-" + record.getDeliveryNumber());
```

`recordReceivable` 本身没问题（如实记它拿到的 amount），
错在**调用点传了未税额**。这是 `recordReceivable` 在生产代码里的**唯一**业务调用点
（另一处是 `ArApController` 的手工挂账端点）。

### 生产存量：7/7，命中率 100%

```
factory_id | ar_rows | 有税额 | AR=未税 | AR=含税 | 少记税额合计
F006       |       7 |      7 |       7 |       0 |      1692.15
```

**没有一条是对的。** 一条都不是「偶发」。

| 订单 | 未税 | 税额 | 含税 | AR 记的 | 少记 |
|---|---:|---:|---:|---:|---:|
| SO-20260824-0003 | 10001.00 | 1300.13 | 11301.13 | 10001.00 | **1300.13** |
| SO-20260819-0002 | 2000.00 | 260.00 | 2260.00 | 2000.00 | 260.00 |
| SO-20260819-0001 | 500.00 | 65.00 | 565.00 | 500.00 | 65.00 |
| SO-20260820-0001 | 250.00 | 32.50 | 282.50 | 250.00 | 32.50 |
| SO-20260828-0003 | 221.24 | 28.76 | 250.00 | 221.24 | 28.76 |
| SO-20260825-0003 | 44.25 | 5.75 | 50.00 | 44.25 | 5.75 |
| SO-20260825-0004 | 0.05 | 0.01 | 0.06 | 0.05 | 0.01 |

⚠️ **目前只有 F006 有 AR_INVOICE 记录，真实客户 LIUSHANMEN 一条都没有**
⇒ 现在没有真金白银的损失，**但真实客户一旦开始走这条链，第一单就中**。

### 连带影响（同一个 amount 一路传下去）

`recordReceivable` 里 `customer.currentBalance += amount` ⇒
**客户信用余额也少记了税额** ⇒ `GET /finance/credit-check` 会放行超出额度的订单。

### 🔴 采购侧是**同一个形状**（本轮顺手查的，未实操验证）

交接件让我先跑销售链，但既然根因是「挂账时不加税额」，
采购那一头值得当场 grep 一眼 —— 结果是对称的：

`PurchaseServiceImpl.java:2273` 用 `record.getTotalAmount()`
（Σ 实收数量 × 单价，**未税**）挂 AP_INVOICE，同样**没加 `tax_amount`**。

```
AP_INVOICE 共 18 条 | 有税额 17 条 | 金额=未税 13 条 | 金额=含税 1 条 | 少记税额合计 ¥249,988.54
```

（另外 4 条是分批入库，等于整单的一部分，所以不等于整单未税额 —— 不是「对的」，是口径不同。）

实测样本（税率一致地是 9%，说明 `purchase_orders.total_amount` 确实是**未税基数**）：

| 采购单 | AP 记的 | 订单未税 | 税额 | 供应商实际应收 |
|---|---:|---:|---:|---:|
| PO-20260824-0001 | 10420.00 | 10420.00 | 937.80 | 11357.80 |
| PO-20260820-0006 | 2749975.00 | 2749975.00 | 247497.75 | 2997472.75 |

⚠️ **我没有实操验证采购链**（PO-20260828-0001 仍在 DRAFT）。
这一段是代码 + 存量数据的读数，**动手改之前请先跑一次收货确认坐实**。
但两边根因同源这件事，改 P0 时应该一起考虑，不要只修销售侧。

### ⛔ 这是财务口径变更，我没有动它

改这一行会**改变所有既有应收余额、账龄、对账单、信用额度**的读数。
本仓的规矩是「改动收款落库口径需单独评估」（`list.vue` 里就有同款先例注释）。
⇒ 需要 owner 拍两件事：
1. 应收挂账用含税额（我认为这是对的：中国增值税下应收账款 = 价税合计，
   且系统自己开出的发票就是含税的）
2. 存量 7 条要不要补差额调整（`AR_ADJUSTMENT` 走审批，不要直接 UPDATE）

---

## 🔴 P1：销售订单【列表】的「创建发货单」会造出一张仓库永远看不到的单子

### 长相

`/sales/orders` 列表行上的「创建发货单」→ 只 `POST /sales/deliveries`
→ 母发货单落 **`PENDING_SPLIT`**。

而仓库的「销售发货单待确认」队列过滤的是
`status IN (DRAFT, PENDING_WAREHOUSE_CONFIRM, PICKED)`
（`WarehouseDeliveryController.listPending`）⇒ **`PENDING_SPLIT` 不在里面。**

**实测**：我在生产上点了这个按钮，建出 `DLV-20260828-9106`，
然后去 `/warehouse/shipments`，待确认列表里**只有另一张 8-18 的旧单，没有我这张**。

### 而它给用户的三条提示全都指向「没事了」

| 提示 | 出处 | 实际 |
|---|---|---|
| 「发货单已创建, **等待仓库确认实发数量**」 | `list.vue:2095` toast | 仓库队列里根本没有它 |
| 详情页按钮变成「**已全部安排发货**」(disabled) | `salesOrderGuards.ts:93` | 一件也没安排到仓库 |
| 发货记录里配送方式/运单号显示「**未记录（历史数据）**」 | 发货记录 tab | 这单 3 分钟前刚建的，不是历史数据 |

### 为什么详情页那条路是对的

`detail.vue:998` 的「新建发货单」在 `deliveryMode === 'FULL'` 时
**会接着自动 `POST /sales/deliveries/{master}/shipments`** 建子发运单
（`child.setStatus(PENDING_WAREHOUSE_CONFIRM)`）⇒ 仓库就看得到了。

**同一个业务动作两处实现，列表那份漏了第二步。**（形态 D + 形态 B）

### 有救，但用户找不到

详情页 → 发货记录 tab → 「**新建分批发运**」能补建子发运单。
但那个按钮的名字是「分批」，读起来像是可选的批次拆分，
**不像「你还差一步，货才发得出去」**。

### 建议（三选一，owner 定）

1. 列表按钮跟 detail.vue 对齐：建完母单顺手建全量子单（最省事，行为与详情页一致）
2. 或者干脆**撤掉列表上的这个入口**，只留详情页那条完整的路
3. 至少：toast 别说「等待仓库确认」，`PENDING_SPLIT` 的单子在详情页要显式提示「还需安排发运」

---

## 🔴 P1：出纳（cashier）登录后 100% 落在 403，一屏都进不去

### 实测

```
POST /api/mobile/auth/unified-login  f006_cashier → 200, role=cashier, perms=["finance:*"]
浏览器落地 → /403「访问被拒绝」(正文 36 字符)
逐角色巡检: 可巡检路由 = 0
```

### 根因

`platform_role_permissions` 里 **cashier 只有 3 行**：

```
cashier          | 3 行  | finance=rw procurement=r sales=r          ← 没有 dashboard
department_admin | 17 行 | ... dashboard=rw ...
dispatcher       | 17 行 | ... dashboard=rw ...
equipment_admin  | 17 行 | ... dashboard=r  ...
quality_manager  | 17 行 | ... dashboard=r  ...
viewer           | 17 行 | ... dashboard=r  ...
warehouse_worker | 17 行 | ... dashboard=r  ...
```

登录后默认落 `/dashboard`，路由守卫 `permissionStore.canAccess('dashboard')`
→ DB 里没有这一行 → `next('/403')`。

### ⚠️ 前端那道「修复」只在 DB 还没加载完的窗口里有效

`web-admin/src/store/modules/permission.ts:224` 的 hardcoded fallback 里
**写着 `cashier: { dashboard: 'r', ... }`**，注释还专门说明它是为了
「DB 异步加载未完成的窗口里 cashier 不被打成 unactivated 全 403」。

但 `canAccess` 的取数是 `isDbLoaded && dbPermissions ? DB : hardcoded`
⇒ **DB 一加载成功，fallback 就失效，403 照旧。**
那次修复瞄的是竞态，没瞄真因（DB 里缺行）。

### 修法

给 `platform_role_permissions` 补 `cashier / dashboard / r`。
⛔ **这张表是平台级的（不带 factory_id），改它影响所有租户** —— 我没有动。
建议走 migration，不要手工 UPDATE。

---

## 🟡 P2：仓库员（warehouse_worker）被判为移动端专属 —— 但那个页面把他的角色显示成「unactivated」

`f006_warehouse_worker` 登录 → `/mobile-only`，页面正文：

```
请使用移动端 App
unactivated                      ← ⛔ 应该是「仓库员」
您的角色被设置为移动端专属，...
```

**「仓库员只能用 App」本身是设计**（`MOBILE_ONLY_ROLES` 里明确列了
`operator / quality_inspector / warehouse_worker`），这条不是缺陷。

缺陷是那个角色名。`web-admin/src/router/guards.ts:138`：

```ts
if (MOBILE_ONLY_ROLES.includes(authStore.currentRole)) {
  authStore.clearAuth();                                       // ← 先清空
  next({ path: '/mobile-only', query: { role: authStore.currentRole } });  // ← 再读, 已经是空的
```

`clearAuth()` 在读 `currentRole` **之前**执行 ⇒ 传给页面的 role 是空值
⇒ 枚举回落到 `unactivated`。**先清后读，顺序反了。**

修法：先把 role 存进局部变量再 `clearAuth()`。

⚠️ 顺带确认过：仓库侧发货确认（`/warehouse/deliveries/{id}/confirm`）
在 RN App 里有对应屏（`warehouseDeliveryApiClient.getDeliveryDetail`），
所以仓库员走 App 干活这条路是通的 —— **但本轮没有实测 App**，
`HANDOFF-...-28d` 的 ⑤ 仍然挂着。

---

## 🟡 P2：quality_manager 菜单里有 4 项，点进去必 403

逐角色巡检（只走该角色**侧边栏里真的有的**那 33 条路由）：

| 路由 | 菜单标题 | 403 的接口 |
|---|---|---|
| `/production/approval` | 报工审批 | `process-work-reporting/pending-approval` |
| `/production/reversals` | 撤回审批 | `reversals` |
| `/production/material-returns` | 退料记录 | `production-material-returns` |
| `/system/product-processes` | 产品-工序配置 | `material-segments/tree` ×2 |

`quality_manager` 在 DB 里是 `production=r`，而这四个端点要的更多。
**菜单给了入口，接口不放行** —— 与本仓记过的
「只有 finance / 只有 rd 的角色看得见入口，点进去必 403」同族。

---

## 🟡 P2：`none:*` / `view:*` 是两个没有任何端点会认的权限串

登录返回的 `permissions`：

```
f006_dept_admin  → ["none:*"]     ← FactoryUserRole.getPermissionModule() 的 default 分支
f006_viewer      → ["view:*"]     ← case viewer: return "view"
```

后端 `@RequirePermission` 判的是 `warehouse:read` / `sales:read_write` 这种串，
`none:*` 和 `view:*` **匹配不上任何一个**。

而它们在 `platform_role_permissions` 里各有 **17 行**权限
（`department_admin` 甚至有 `sales=rw scheduling=rw analytics=rw production=rw`，
和 `dispatcher` **一模一样**）⇒ **两套权限系统对同一个角色给出完全不同的答案。**

实测侧边栏：`dept_admin` 94 项、`viewer` 86 项、`dispatcher` 94 项
（对比 `equipment_admin` 10 项、`quality_mgr` 35 项）。

⚠️ 两条值得 owner 单独看：
- `department_admin` 是 `@Deprecated` 角色，却拿着和调度一样宽的写权限
- `viewer`（「查看者/只读访问」）在 DB 里有 `finance=r hr=r` ⇒ 看得到财务和人事数据

---

## ✅ 确认没问题的（别再查一遍）

- **库存真的动了**：确认出库前后 `finished_goods_batches` 汇总
  `shipped 61 → 71`、可用 `158 → 148`，正好 10 盒，FIFO 扣减。
- **发票金额链路是对的**：`invoice_records` 的 `amount/tax_amount/total_amount`
  = `221.24 / 28.76 / 250.00`，`taxBreakdown` 也对；开票对话框明写
  「申请价税合计 ¥250.00 (不含税 ¥221.24 + 税 ¥28.76)」。
- **订单详情页金额展示是诚实的**：未税/税额/含税三个数分开列，
  未税小计用的是**未舍入**单价（221.24 而不是 22.12×10=221.20）—— #282 的修复有效。
- **服务端拒绝无地址发货单**（`DELIVERY_ADDRESS_REQUIRED`）—— 正确。
  ⚠️ 但前端没有先校验，用户点了才被拒（小体验问题，非缺陷）。
- **开票必须传 PDF**，`确认开具` 在传之前是 disabled —— 正确的防呆。

---

## ⚠️ 我在生产上改了什么（可回滚）

### 1. 恢复了 7 个具名角色账号

`HANDOFF-...-28d` 说它们是 `is_active = f`。**不准确 —— 它们是被软删的**：
`deleted_at = 2026-08-21 18:50:04.256283`，和 19 个测试残留
（`g8test*` / `rbac_test_*` / `ztestc_*`）**同一批、同一秒**被删。
一次测试数据清理把工厂里真正有名有姓的 7 个角色账号一起扫掉了。

```sql
-- 已执行 (只这 7 个; 同批的 19 个测试残留原样留着, 复核仍为 19)
UPDATE users SET deleted_at = NULL, is_active = true, updated_at = now()
 WHERE factory_id='F006' AND deleted_at = TIMESTAMP '2026-08-21 18:50:04.256283'
   AND username IN ('f006_warehouse_worker','f006_quality_mgr','f006_cashier',
                    'f006_dispatcher','f006_equipment_admin','f006_dept_admin','f006_viewer');
```

同时把这 7 个账号的 `password_hash` 设成与 `f006_admin` 相同（即 `123456`），
以便逐角色巡检。改动前的指纹存于 `out/roles-before.txt`。

⚠️ 注意：**只改 `is_active` 是不够的** —— 我先只改了 `is_active`，
7 个账号仍然全部 401。软删是全局生效的（`@SQLDelete` + `@Where`），
登录查询根本看不见它们。**阳性对照（先确认能登录）救了这一轮**，
否则后面所有「这个角色看不到 X」的读数都会被读成权限结论。

### 2. 走完了一条销售链（留在生产上，都带 `E2E-ACC` 标记）

```
SO-20260828-0003      COMPLETED / FULLY_INVOICED / 已收款
  └ DLV-20260828-9106     MASTER    SHIPPED  ¥221.24
     └ DLV-20260828-9106-01 SHIPMENT SHIPPED  ¥221.24   (扣了 10 盒库存)
  ├ INV-20260828-50266B812F  ISSUED   价税合计 ¥250.00 (附了一个占位 PDF)
  ├ AR-20260828-8447         AR_INVOICE ¥221.24  ← 本轮 P0 的物证, 建议保留别删
  └ PAY-20260828-0019        VERIFIED   ¥221.24
```

---

## 给下一棒

### 已经查清楚了，别再查一遍

- **应收少记税额的根因就是 `SalesServiceImpl:3136` 那一个参数**，
  不是 `recordReceivable` 内部、不是发票侧、不是四舍五入。
  发票侧**是对的**，别去改它。
- **`recordReceivable` 在业务代码里只有这一个调用点**（另一个是手工挂账端点），
  不存在「还有别处也这么写」——已 grep 全仓确认。
- **仓库员上不了 web-admin 是设计**（`MOBILE_ONLY_ROLES`），
  缺陷只是那页把角色显示成 `unactivated`。别去改 `MOBILE_ONLY_ROLES`。
- **出纳 403 不是路由白名单问题** —— `ROLE_PATH_WHITELIST` 里只有
  `finance_manager` 一个角色，cashier 不在里面。是 `platform_role_permissions` 缺行。

### 还没做的

- **出货金额 221.24 是未税，而应收/开票该是含税** —— 本轮判定
  「出货单记未税是合理的」（它是物流单据不是账单），但**如果 owner 认为
  出货金额也该含税，那是另一处要改**，我没动。
- **RN App 一次没碰**（同 28d ⑤）。仓库员的活全在 App 上，
  而本轮证明了他**在 web 上一屏都走不到** ⇒ App 那端是他唯一的路，
  优先级比 28d 写的时候更高。
- **采购链 / 仓储调拨链**（`PO-20260828-0001` / `TRF-20260828-5270`）仍停在 DRAFT。
  ⚠️ 采购侧多半有**对称的问题**：应付挂账用的是含税还是未税？
  `recordPayable` 的调用点值得先 grep 一眼再动手。
- **异常路径一条没试**（库存不足、超发少发、退货冲销、审批驳回、并发下单）。
- **`dispatcher` / `dept_admin` / `viewer` 的逐屏巡检**本轮跑到一半，
  见 `out/23-role-sweep/report.json`。

### 一条本轮新发现的、28d 里没有的

`sales_orders` 表上有 **30 多个 `cf_conflict_test_*` / `cf_e2e_*` 列**
（`cf_conflict_test_1775841401654` 这种），是历次 E2E 建自定义字段留下的真实 DDL。
它们是**生产表上的真列**，不是软数据。清理需要 migration，本轮没动。
