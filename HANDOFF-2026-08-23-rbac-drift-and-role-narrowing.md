# HANDOFF 2026-08-23 — RBAC 漂移普查 + 角色收窄（交接给 Codex）

> 上一轮 owner 在**真实点击**中连续暴露权限问题，本轮把「菜单里看得见、点进去必 403」
> 这一类做成了可扫描的东西，并按 owner 逐项裁定收窄了 6 个角色。
> **全部已上线**（离线部署，见下）。

---

## 0. 环境（先读这一段，否则会白跑）

GitHub **仍处停用**，`origin/main` 冻结在 `f635e6b6`。协议：

| 事 | 在哪做 |
|---|---|
| 合并/提交 | `C:\Users\Steve\cretas-mainmerge`（**本地 `main`** 是唯一汇合点） |
| 部署 | `C:\Users\Steve\cretas-deploy-offline`（detached，`SKIP_GIT_CHECK=1`） |

```bash
cd C:/Users/Steve/cretas-deploy-offline
git checkout -q --detach $(git -C C:/Users/Steve/cretas-mainmerge rev-parse main)
FORCE_REDEPLOY=1 SKIP_GIT_CHECK=1 ./scripts/deploy/deploy-backend.sh --env prod
SKIP_GIT_CHECK=1 ./scripts/deploy/deploy-web-admin.sh --env prod --confirm-prod YES-PROD
```

### 🔴 部署脚本有一个会骗人的缺陷 —— **本轮实测中招**

第二次运行时它打印：

```
✅ 无需重新部署：生产已运行相同后端制品
跳过: Maven / 上传 / Java 重启 / 蓝绿切流
结果  SUCCESS
```

**那句话是假的。** 它比的是**磁盘上的 jar**，不是**运行中的进程**。当天的经过是：

1. 第一次部署把 jar 装到磁盘（MD5 已验），然后**在 `systemctl start` 之前 ssh 掉线**（`exit=255`，`journalctl` 那一刻毫无记录）
2. 第二次部署看到磁盘 == 构建产物 ⇒ 断定「生产已在运行它」⇒ **跳过一切**，报 `SUCCESS`

结果：**进程比制品老 11 小时，迁移一行没跑**，而所有读数都是绿的。

⇒ **每次部署后照硬约束 2 验**（这是唯一抓到它的方式）：

```bash
ssh root@47.100.235.168 'A=$(systemctl is-active cretas-backend);
  SVC=$([ "$A" = active ] && echo cretas-backend || echo cretas-backend-green)
  PID=$(systemctl show -p MainPID --value $SVC)
  echo "进程启动: $(ps -o lstart= -p $PID)"
  stat -c "制品 mtime: %y" /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar'
```

**判据：进程启动时间必须晚于制品 mtime。** 卡住时用 `FORCE_REDEPLOY=1`。

📌 **这个短路判据本身建议修**（它应该比「运行中进程加载的 jar」而不是磁盘文件），
但 `scripts/deploy/*` 是强制 PR 路径，GitHub 停着 ⇒ 需要 owner 点头。**未做。**

---

## 1. 本轮上线了什么

### 1.1 角色权限（迁移 V20261030_10 … _15，**全部已在 prod 执行**）

prod 实测的最终矩阵（`platform_role_permissions`，权威表）：

| 角色 | 模块 |
|---|---|
| `production_manager` | `dashboard:rw inventory:r production:rw quality:r rd:r scheduling:rw warehouse:r work_report:rw` |
| `production_clerk` | `dashboard:r inventory:r production:rw report:r warehouse:r work_report:rw` |
| `sales_manager` | `dashboard:r rd:rw report:r restaurant:rw restaurantMarketing:rw sales:rw` |
| `procurement_manager` | `dashboard:r procurement:rw report:r` |
| `warehouse_manager` | `dashboard:rw inventory:rw report:r warehouse:rw` |
| `finance_manager` | `analytics:rw dashboard:r finance:rw report:r restaurant:rw restaurantFinance:rw` |

⛔ **restaurant / restaurantFinance / restaurantMarketing 一律没动** —— owner 明确划的禁区
（「餐饮那边是另外一个体系」）。

### 1.2 端点/前端修复（约 20 处，逐条见 commit message）

按类别：

- **端点声明纠偏**（该给的没给）：`/processing/material-consumptions/batch{,/summary}` 加
  production、`/disposal-records` 加 quality、`/price-lists/lookup` 加 sales、
  `/semi-finished/inventory` 与 `/labels/material-batch` 加 warehouse、
  `/sales/orders{,/*}` 加 finance/procurement、`/purchase/orders/*` 加 sales:read、
  `/price-lists` 写方法加 procurement、`InventoryLedgerController` 把 finance:read 提到类级
- **前端守卫绑到「后端实际要什么」**：分类字典→system、原料类型→production、
  销售订单的开票/收款→finance、告警手动检测→equipment
- **路由归位**：`/rd/*` production→rd、`/transfer/*` warehouse→inventory、
  SKU管理/工序管理/产品-工序配置 system→**production**、产品级利润详情 sales→finance
- **新增 `meta.modulesAll`**（路由守卫支持「这些模块都要」）—— 补的是**表达能力**，
  ⛔ 不放宽/收紧任何角色
- **新端点 `GET /users/directory`**（员工名录，只读 6 字段，不含手机/邮箱）——
  替换 5 处「为了填下拉框而要求 hr 权限」的调用
- **`OrderPeekDrawer`**：财务页内**只读**订单速览，⛔ **不注册任何路由**
  （物理上进不了侧边栏，URL 始终停在 `/finance/*`）

### 1.3 顺带修的两个独立缺陷

- **登录态丢姓名**：`applyLoginData` 漏了 `fullName`，而后端一直在返回 `profile.name`
  ⇒ `authStore.user.fullName` **恒为 undefined** ⇒ 销售订单业务员预填每次落到**登录名**
  并写进 `sales_orders.salesperson`。
  实测该列：`f006_admin ×16 / f006_sales_mgr ×6 / 六膳门销售主管 ×5 / 空 ×7`
  —— 同一个人两种写法。**新单已修**，⛔ **存量 22 条未回填**（改历史数据，等 owner 发话）。
- **开票/收款在生产环境一张单都点不出来**：三处条件各自硬写数组，都含一个在订单
  `statusMap` 里**不存在**的状态值（那个值真正的归属是「出库状态」域），且三处都漏了
  `FINANCE_APPROVED`。实测 F006 全部 34 张单里 `CONFIRMED`/`PROCESSING` **零张**
  ⇒ 抽成 `salesOrderBillableStatus.ts` 单一来源。

---

## 2. 🔴 权限有 **4 个**承载点 —— 这是本轮最贵的教训

改任何角色的访问范围，**四处都要看**，漏一个就误判：

| # | 承载点 | 位置 | 关系 |
|---|---|---|---|
| ① | 模块权限矩阵 | `platform_role_permissions`（**权威表**，⛔ 不是 Java 的 `PERMISSION_MATRIX`） | — |
| ② | `roles` 允许式白名单 | `menuConfig.ts` 的菜单项 | **一票否决** |
| ③ | `hideForFactoryTypes` 业态黑名单 | `menuConfig.ts` | 一票否决 |
| ④ | **`ROLE_PATH_WHITELIST` 角色路径白名单** | `router/guards.ts` | 与①**串联(AND)** |

④ **只给 `finance_manager` 一个角色**（19 条路径，其中 3 条是本轮补的）。它的源码注释早就写过：

> 「删掉这两行会让 finance_manager 直接吃 403，即使模块权限是通的。**要改访问范围，两处都得改。**」

我只改了①，砍掉 finance 的 analytics ⇒ 它的**主入口** `/smart-bi/analysis-hub`
（注释里就这么标着）当场 403，owner 实测撞到。已回滚（V20261030_15）。

### 后端还有 **2 道**独立的闸（与关系）

| | 注解 | 判据 |
|---|---|---|
| 闸一 | `@RequirePermission` | 需要**任一**模块 |
| 闸二 | `@RequireModule` → `ProductionModuleRegistry.permissionModule()` → 同一张表 | 需要**每一个** |

规模：`@RequireModule` **387 处 / 96 个控制器 / 16 个模块码**。
⛔ **有 sales 也没用，缺 production 照样 403。** 两者都是「方法级优先，无则回落类级」。

---

## 3. 扫描器 `scripts/check-menu-permission-drift.py`

```bash
python scripts/check-menu-permission-drift.py                       # 扫 prod
python scripts/check-menu-permission-drift.py --baseline docs/audits/menu-permission-drift-baseline.json --check   # 闸模式
```

**当前读数**：主判据 **4 处**（全部写入基线，是**已登记的误报**，见下）/
已被前端守卫挡住 18 处 / 覆盖面缺口 31 处。

演进：`326 → 190 → 110 → 70 → 24 → 3 → 0 → 4`。
⚠️ **前几轮降的大部分是我自己的误报**（尺子的缺陷，不是产品修好了）。

### 🔴 ⛔ 别把「报 0」当成「没问题」—— 已登记的盲区

| 盲区 | 后果 |
|---|---|
| 只认 `` `/${factoryId}/xxx` `` 模板串调用 | 经 `api/` 封装的调用**扫不到**（本轮漏掉 2 处选人器就是这个） |
| **行内操作按钮**（「转生产任务」这类） | 只扫页面路由，**行内按钮完全是盲区** |
| 菜单是**树**，脚本按扁平项解析 | 父组的 `hideForFactoryTypes` 只能靠**路径前缀**近似继承，而 children 的 path 不一定以父 path 开头（实测 `/smart-bi` 组里有 `/analytics/*`）⇒ 会误报 |
| 可见性用**路由 module** 当代理 | 菜单已隐藏但路由 module 还在的页面会被误报（基线里那 4 条就是） |
| 31 处调用点认不出 HTTP 动词 | 配置对象里的 `url:` 而非函数调用 |
| `@RequireRole` 角色白名单 | 完全没纳入 |

### 它自带的三道自检（都会 `rc=2` 而不是静默放行）

- `check_module_registry_mirror()` — 钉住 py 侧映射表与 `ProductionModuleRegistry.java` 一致
- `check_role_path_whitelist_consistency()` — **双向**：A 类「白名单放行但没模块」（精确、可信）；
  B 类「菜单可见但不在白名单」（**会误报**，输出里已标注，需人工过一遍）
- 例外登记 `GUARDED_EXEMPTIONS` — ⛔ **不是白名单**：每条必须写出那道前端守卫的**字面量**，
  脚本去源码里验它还在；**守卫被删 → 例外自动失效、立刻恢复报警**。变异验过。

---

## 4. ⚠️ 本轮踩过的坑（Codex 请直接吸收，不要重蹈）

1. **「一个改动看起来生效了」≠「这个改动生效了」**
   同一 commit 里两个改动共享同一个可观测现象（红框不弹）。而那个现象只由其中一个产生，
   另一个（403 分支）**从没执行过** —— 判据写成 `err.response.status`，
   而本仓请求层抛的是 **`ApiError`**（`status`/`code` 在**顶层**，没有 `response`）。
   ⇒ N 个改动就要有 **N 个互不相同的可观测点**。

2. **读数变好先分清：修好了 / 仪器瞎了。** 本轮三次是后者：
   - 路由正则尾部用**捕获组**（消耗字符）吞掉下一条 ⇒ 路由数 203→130，漂移假性归零
   - 我给三条路由写的**注释**把 `module:` 挤出解析窗口 ⇒ 204→201
   - 为消 4 条假阳性加的过滤器**把整道闸滤瞎**（同一变异：加过滤前报 9 处，加过滤后报 **0**）⇒ 已撤销

3. **变异不红要分三种**：断言没用 / **变异没生效** / **检查根本没覆盖到**。
   ⛔ 别默认是第一种 —— 本轮三种都遇到过。

4. **把「我没改」写成「owner 要的」**。V20261030_07 里我写了
   「…已经是 owner 要的值」，而 owner 从没说过 —— 那只是 dispatcher 的种子值我没动。
   下一轮就有人（我自己）拿它当依据。

5. **砍权限会造出新漂移**（「页面还在、权限没了」是另一种「看得见点不开」）。
   ⇒ **部署前必须用扫描器预演**：把砍完后的权限集合喂进 `scan()`，确认新增 0 处。
   本轮采购主管预演出 2 处 ⇒ 先修端点声明再砍。

6. **硬约束 6 又中两次**：`\n` 经 heredoc 落盘被吃成真换行 ⇒ SyntaxError。
   ⇒ 含转义的内容用 **Write/Edit 直接落盘**，或用 `chr(10)` 显式构造。

7. **`mvn -o test -q` 会把 `Tests run` 吞掉** ⇒ `EXIT=0` 但一个测试都没跑也是这个读数。
   ⇒ 不加 `-q`，数 `Tests run:` 那一行。

8. **批量 `replace` 会误伤**：`" hideForFactoryTypes: ['FACTORY'],"`（单空格前导）
   把**独立成行**的那几处也吃掉了，误删 `/restaurant`、`/crm`、`/ops` 三个组的隐藏声明
   —— 被既有测试当场抓住。⇒ 硬约束 8：**改前数、改后数，两个数必须对得上**。

---

## 5. 测试进度（owner 手动走查）

| 线 | 状态 |
|---|---|
| 销售 `f006_sales_mgr` | ✅ 过（名录下拉出真名、业务员自动填姓名、关联采购 tab、红框消失、「生产状态不可见」） |
| 生产 `f006_production_mgr` | ✅ 过（SKU/工序/产品-工序配置点得开、生产计划页不再「加载参考数据失败」、BOM达成无金额） |
| 采购 `f006_procurement_mgr` | ⏳ 菜单已收窄，**流程未走** |
| 仓储 `f006_warehouse_mgr` | ⏳ 菜单已收窄，**流程未走** |
| 财务 `f006_finance_mgr` | ⏳ 三处 403 已修并部署，**owner 未复测** |

### 🔴 唯一**没有端到端验过**的东西：物料消耗的价格掩码

`/processing/material-consumptions/batch/{id}/summary` **原本没有价格掩码**
（返回 `totalPlannedCost` / `totalActualCost`，每个 item 还带 `unitPrice`/`cost`），
一直靠「只有采购能进」挡着。我放行给生产/质检的**同时补了掩码**，但**从没跑通过**。

⚠️ 而且**验它要挑对角色**：`procurement:price:view` **不走模块矩阵**，走
`PermissionServiceImpl.PRICE_VIEW_ROLES` 这个**独立角色白名单**，
`production_manager` / `sales_manager` / `finance_manager` **都在里面**（掩码对他们本就不生效）。

**正确验法**：用 **`f006_quality_insp`**（不在名单里）打开一个**真有成本**的批次：

| 批次 | 消耗 | 总成本 |
|---|---|---|
| `PB-PLAN-1787036063746-8719B078-74084` | 4 条 | **¥238.31** |
| `PB-PLAN-1787103789364-E944B5EB-67132` | 5 条 | ¥137.70 |

判据：**物料名和数量该有，单价/成本/小计不该有**。看到 ¥238.31 = 掩码没接上。

⛔ 批次 `10789` 验不了任何东西（库里成本全 NULL、消耗 0 条）——
没有钱可泄露的样本，接上和没接上长得一模一样。

---

## 6. 待办（owner 未拍板 / 未做）

| # | 事 | 备注 |
|---|---|---|
| 1 | **部署脚本「相同制品」误判成功** | 🔴 最危险 —— 它把失败部署报成 `SUCCESS`。碰 `scripts/deploy/*` 属强制 PR 路径 |
| 2 | **行内操作按钮的权限守卫** | 扫描器盲区。生产经理的「转生产任务」大概率点不动（他没 production_plan？—— 有，但其它角色的行内按钮未查） |
| 3 | 存量 **22 条**业务员回填成姓名 | 改历史数据，owner 决定 |
| 4 | 批次详情把「没数据」渲染成 `¥0.00` | 同卡片里别的字段渲染成 `-`。有真实成本时会误导 |
| 5 | 「共 34 条 / 全部 10」两个口径并排 | 前者是服务端总数，后者是当前页 |
| 6 | `sales_manager` 仍有 `rd:rw` | owner 未审过这一项 |
| 7 | analytics 只是**菜单隐藏**，权限还在 | 手输 URL 仍进得去。要彻底关得**两处一起改**（模块 + 路径白名单） |
| 8 | F006 缺 4 个角色账号 | `production_clerk` / `warehouse_worker` / `team_leader` / `cashier` —— 涉及它们的步骤走不了 |

---

## 7. 建议的下一步

owner 已定：**按职业走流程测试**，链是有依赖的：

```
采购下单 → 财务付款申请审批 → 仓储收货入库 → 销售下单
   → 生产计划/批次/逐道录入 → 质检(🔴 顺带验掩码) → 仓储发货 → 财务开票收款
```

⚠️ **交接点最容易断** —— 上一个角色的产出，下一个角色看不看得见。
撞到「列表是空的」先别当权限问题，多半是数据没串上。

⚠️ **每换一个角色必须退出重登** —— 权限缓存在登录态里；姓名修复也要重登才生效。

---

## 附：关键文件

| 文件 | 作用 |
|---|---|
| `scripts/check-menu-permission-drift.py` | 漂移扫描器（头部有完整的「看不见什么」清单，**动手前先读**） |
| `docs/audits/menu-permission-drift-baseline.json` | 棘轮基线（当前 4 条，全是已登记误报） |
| `web-admin/src/router/guards.ts` | `ROLE_PATH_WHITELIST`（第 4 个承载点）+ `modulesAll` |
| `web-admin/src/components/layout/menuConfig.ts` | 菜单 module / `roles` / `hideForFactoryTypes` |
| `backend/.../permission/ProductionModuleRegistry.java` | `@RequireModule` → 权限模块的映射（有一致性闸钉着） |
| `backend/.../service/impl/PermissionServiceImpl.java` | `PRICE_VIEW_ROLES`（价格权限**不走模块矩阵**） |
| `web-admin/src/views/finance/components/OrderPeekDrawer.vue` | 只读速览抽屉（有源码闸钉住「只读」+「不注册路由」） |
