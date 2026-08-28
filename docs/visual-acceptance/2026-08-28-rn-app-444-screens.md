# 逐屏视觉验收 第二轮 — RN App (2026-08-28)

第一轮走的是 web-admin 145 屏（`2026-08-28-web-admin-145-screens.md`，84 条发现，10 个 PR）。
这一轮走 `frontend/CretasFoodTrace` 的 App —— 操作员 / 仓管 / 质检员在车间用的那一端。

租户 **F006「六膳门食品科技」**（测试租户，见第一轮交接件「坑①」；测试数据出现在里面是它的用途，不是缺陷）。
后端走 prod 网关 `139.196.165.140:8086`。

---

## 一、这一轮怎么看的（工具与判据）

App 没有 URL 路由，所以不能照搬 web-admin 的 `goto(route)`。做法是：

1. `expo start --web` 把同一份 RN 代码跑成网页（同一份 JS、同一个后端）
2. `AppNavigator` 上挂一个导航 ref，**只在 `__DEV__` 下**暴露到全局
3. Playwright 登录一个角色 → 从运行中的 navigator 读它自己报的 `routeNames`
   → 广度优先跳过去 → 每屏截图 + 存可见文本

⛔ 全程只导航，不点任何业务按钮。

工具在 `frontend/CretasFoodTrace/tests/vshot/`。

### 建这套仪器时它自己坏过三次（都写在代码注释里）

| # | 坏法 | 长相 | 判据 |
|---|---|---|---|
| 1 | 静态解析导航器源码数路由 | 把**成块注释掉**的 `<Stack.Screen>`（连 import 一起注释）数成活路由 | 改成读运行中 navigator 的 `routeNames`；并给静态扫描加剥注释 + 阴性对照 |
| 2 | `document.body.innerText` 当屏幕文本 | react-navigation 把上一屏留在 DOM 里，只用 opacity/aria-hidden 藏起来 —— **13 屏和 16 屏的文本都是第 12 屏的**，而截图是对的 | 只取**可见**文本（逐 text node 查祖先 display/visibility/opacity/aria-hidden + client rect），并同时记 raw 长度让差值可见 |
| 3 | `fullPage: true` + 设 `scrollTop` 滚动 | RN 把 app 钉在视口里滚内部 div，`fullPage` 只有一屏高；而我挑中的那个可滚容器**滚了但画面不动**，产出 5 张字节完全相同的图 | 改用 `mouse.wheel` 逐段滚，**两张相同就停** —— 「没滚动」不可能再被读成「下面没内容」 |

⚠️ 第 1 次的偏差方向是**把已下线的屏说成还在**；第 2 次是**内容对不上标签**；
第 3 次是**看起来截全了，其实只看了第一屏**。三次都不报错。

### 阳性对照

- 登录后必须从 `auth-storage-v3` 读到 `username` + `isAuthenticated`，否则整轮作废
  （444 张登录页截图和「跑完了」长得一模一样）
- 每屏记录**实际停在哪条路径**；与目标不符的单独标出来
- App 崩掉（白屏）会被检出并重建会话 —— 否则崩溃之后的每一张都是同一个白矩形，
  而白矩形在任何「有没有问题」的判据上都长得像「干净」

---

## 二、覆盖

`src/screens` 下 **444 个 .tsx**，其中 **320 个**被某个 navigator 注册成路由，
**124 个**没有（103 个是 `screens/` 下的子组件不是屏，21 个**谁都没引用**——见「六、静态盘点」）。
导航器共 **645 条路由注册**（剥注释后；未剥注释时会多数出 12 条**成块注释掉**的）。

### 逐角色实走

F006 有 15 个账号 / 10 个角色。逐个登录、按运行中 navigator 自报的 `routeNames` 广度优先走：

| 角色 | 屏数 | 图数 | 崩溃 | 落点 |
|---|---:|---:|---:|---|
| 车间主任 `f006_workshop` | 61 | 135 | 14 | `WSHomeTab/WSHome` |
| 人事 `f006_hr_admin` | 63 | 81 | 15 | `HRTabs/HomeTab/HRHome` |
| 老板 `f006_admin` | 45 | 84 | 3 | `BossOverviewTab/BossOverview` |
| 质检员 `f006_quality_insp` | 28 | 55 | 8 | `QIHomeTab/QIHome` |
| 采购 `f006_procurement_mgr` | 24 | 44 | 4 | `PMHomeTab` |
| 销售 `f006_sales_mgr` | 21 | 51 | 3 | `SMHomeTab` |
| 操作员 `f006_operator1` | 13 | 27 | 2 | `OperatorReportTab/OperatorAssignedProcess` |
| 财务 `f006_finance_mgr` | 13 | 24 | 1 | `FinanceHomeTab` |
| 生产经理 `f006_production_mgr` | 3 | 13 | 0 | `ProductionOverviewTab` |
| **仓储主管 `f006_warehouse_mgr`** | **0** | **0** | — | **白屏（见 R0）** |
| 平台管理员 `platform_admin` | 1 | 2 | 0 | **锁定页**（见 G1） |
| 餐饮老板 `mock_owner`(MOCK_REST) | 1 | 2 | 0 | **锁定页**（见 G1） |
| 餐饮采购 `mock_purchase`(MOCK_REST) | 1 | 2 | 0 | **锁定页**（见 G1） |
| **合计** | **274** | **520** | **50** | |

### ⚠️ 三块没走到的，逐块写明原因（⛔ 不算「已覆盖」）

1. **仓储主管全灭** —— 见 R0。修复后重跑，同一账号走通 29+ 屏。
2. **平台侧 + 餐饮侧两整棵树** —— 见 G1。约 **150 + 122** 条注册路由，
   现有账号一个都到不了。
3. **`dispatcher` / `warehouse_worker` / `viewer` / `operations_coordinator` 等角色没有账号**
   —— `.codex/rules/test-credentials.md` 里列的 F001 账号（`dispatcher1` / `factory_admin1` /
   `warehouse_mgr1` …）**实测全部 401**，F001 租户已被清理
   （`/api/platform/factories` 现在只剩 F006 / LIUSHANMEN / MOCK_REST）。那份凭证清单需要订正。

### 50 次崩溃的分类（⛔ 不要笼统写成「50 个 bug」）

| 类 | 次数 | 是什么 |
|---|---:|---|
| A 无参数进入详情屏 | 46（26 个不同屏） | **仪器造成的**：真实用户点列表进来一定带参数。⛔ 不作为缺陷计数 |
| B 其它成因 | 4 | `SalesAnalysis`(R1) · `PurchaseExceptionList`(P1) · `QILabelQcReview` / `QILabelQcSubmitted`（读 `route.params.taskId`，同 A 类） |

⇒ **真正与参数无关、用户走得到的崩溃是 2 个**：R1 和 P1。
但 A 类 46 次共同暴露一件事：**这个 App 没有顶层 ErrorBoundary，任何一屏渲染异常都是整屏白**。

---

## 三、发现台账

每条都在代码 / 接口里核实过再写下来 —— 视觉走查给的是线索，不是诊断。
「实测」一栏写的是**当场取到的读数**，不是推断。

### R0 🔴🔴🔴 仓储主管**打不开 App** —— 登录成功之后永久白屏，昨晚 23:25 合入的

**账号**：`f006_warehouse_mgr`（role `warehouse_manager`，F006 唯一的仓储主管账号）

**实测**：登录接口 200、`auth-storage-v3` 里 `isAuthenticated: true`、`factoryId: F006`。
之后 3 秒 / 6 秒 / 12 秒三次取样，**每次都是**：

```
rootState    = NULL (导航容器根本没挂起来)
可见文本      = ""
截图          = 一张空白
console      = A navigator cannot contain multiple 'Screen' components with the same name
               (found duplicate screen named 'WHLocationManage')
```

**代码**：`src/navigation/warehouse/WHHomeStackNavigator.tsx` 里，
「仓库地图 / 库位」那段注释 + `WHLocationManage` + `WHTempMonitor` 共 12 行
**一字不差地出现了两次**（`git grep -c 'name="WHLocationManage"'` = 2）。
React Navigation 在挂载时对重复路由名直接抛异常。

**引入**：`751b0d48d9 fix(rn): restore warehouse map routes on the home stack (#239)`，
**2026-08-27 23:25 合入 main** —— 那块内容当时已经存在，这个 PR 又加了一遍。

**影响面**（已核）：`WHHomeStackNavigator` 只被 `WarehouseManagerTabNavigator` 挂载
⇒ **只有 `warehouse_manager` 这一个角色全灭**；`warehouse_worker` 走的是另外三个栈，不受影响。

**为什么没有任何东西拦住它**：
- 重复路由名是 **React Navigation 的运行时不变量**，`navigatorMountContextReachability`
  那道闸是**静态 AST 分析**，它不挂载，看不见这一类
- `App.tsx` 里没有顶层 ErrorBoundary ⇒ 用户看到的不是报错，是**一片白**

⇒ 建议的闸：把每个角色的根 navigator **真挂载一次**并断言渲染出东西。
静态可达性闸再多也覆盖不了「挂载时就炸」。

### R1 🔴 销售分析：整个 App 白屏，而后端正确地回了「暂无数据」

**屏**：`BossAnalysisTab/SalesAnalysis`（老板 → 分析 → 销售分析），boss 走查第 33 屏

```
PAGEERROR: Cannot read properties of undefined (reading 'totalSales')
→ 整棵 React 树卸载，页面变白，导航 ref 也没了
```

**实测**（`GET /api/mobile/F006/smart-bi/analysis/sales`，HTTP 200）：

```
success = true
data 的键 = [customerRanking, dateRange, generatedAt, overview,
             productRanking, salespersonRanking, trendChart]      ← 没有 kpi
data.overview.aiInsights[0].message = "当前时间范围内暂无销售数据"
```

**代码**：`SalesAnalysisScreen.tsx:556` 守卫写的是 `{salesData && ...}`，
下一行 `salesData.kpi.totalSales` —— **守的是外层对象在不在，读的是里面一层**。
`data` 非空所以守卫通过，`data.kpi` 不存在所以当场抛。

⚠️ **后端把话说对了**（「暂无销售数据，请上传销售数据或调整时间范围」），
是前端把这句话变成了一次崩溃。

**为什么是最高一档**：`App.tsx` 里**没有任何顶层 ErrorBoundary**
（全仓唯一的错误边界在 `MarkdownRenderer` 内部）。任何一屏的渲染异常 = 整个 App 白屏，
车间里只能杀进程重开。

### R2 🔴 同一份契约错位，隔壁两屏是「编一个 0」，这屏是崩溃

同一批 SmartBI 屏都按 `X.kpi.<字段>` 读，而**三个后端端点没有一个返回 `kpi`**：

| 屏 | 后端实际返回的键 | 前端行为 | 结果 |
|---|---|---|---|
| 销售分析 | `overview / trendChart / …` | 直接读 `.kpi.` | **崩溃**（R1） |
| 经营驾驶舱 | `kpiCards: []` | adapter `toNum(undefined) → 0` | **销售额 0 / 订单数 0 / 完成率 0.0% / 利润 0**，每个还配一个绿色 `+0.0%` |
| 财务分析 | `overview / profitMetrics / costStructure` | — | 标签栏下面**整片空白**，无数据、无空态、无报错 |

**实测**（经营驾驶舱屏上的文本）：`0 销售额 +0.0% / 0 订单数 +0.0% / 0.0% 完成率 +0.0% / 0 利润 +0.0%`
**实测**（接口）：`kpiCards = []`

`ExecutiveDashboardScreen.tsx:129` 的 `toNum` 把 `undefined` 变成 `0`——
**「我不知道」被翻译成「是 0」**，而下游（看数的人）分不出这两件事。

⇒ 这三屏该改的是**同一个东西**：契约对齐 + 缺数据显示空态。
⛔ 逐屏加 `?? 0` 会把崩溃换成第四个假 0。

### R3 🔴 成品库存：每一行的品名都是 UUID，而正确的名字**就在同一个响应里**

**屏**：`BossOverviewTab/BossFinishedGoods`，boss 走查第 12 屏

**实测**（`GET /api/mobile/F006/sales/finished-goods`）：

```
productTypeName = None          ← 屏幕读的是这个字段，5/5 行全为 null
productTypeId   = eb0aa47b-a5dd-49dc-af20-bf48ce8e1207   ← 于是屏上显示它
productName     = 'SOP-20260817-01-黄油鸡-成品800g'       ← 正确答案就在同一行里
```

`FinishedGoodsListScreen.tsx:74`：`{item.productTypeName || item.productTypeId}`。

⚠️ 这是第一轮 V4 的**同一个 UUID**（web-admin 生产计划屏）。同一份数据，两端都没接上名字。

### R4 🔴 同一屏还读了两个后端从不产出的字段，其中一个让「库存偏低」这一档**永远不出现**

`FinishedGoodsBatch` 接口声明了 `totalQuantity: number`（必填），而接口响应里
**没有这个键**（只有 `producedQuantity / nominalQuantity / availableQuantity / reservedQuantity / shippedQuantity`）。TypeScript 因此全绿 —— 接口在撒谎。

三处后果，实测：

1. `总量 {item.totalQuantity} {item.unit}` → 屏上显示成 **「总量　盒」**（数字位是空的）
2. `ratio = availableQuantity / totalQuantity` → `NaN` → **进度条恒为 0**
3. `getStockStatus`：`if (ratio < 0.2) 库存偏低` —— `NaN < 0.2` 恒 `false`
   ⇒ **状态只可能是「已售罄」或「充足」，「库存偏低」是永远走不到的分支**

另外 `item.expiryDate` 也不存在（后端发的是 `expireDate`）⇒ **保质期从来不显示**。
这是一个食品溯源系统。

### R5 🟡 数据分析中心「质量概览」：并排的四个数互相矛盾

**屏**：`BossReportsTab/ReportDashboard`

屏上并排：`检验数 13 · 合格 6 · 不合格 7 · 合格率 68.8%`（红色进度条）。
**6 / 13 = 46.2%，不是 68.8%。**

**实测**（`GET /api/mobile/F006/reports/dashboard/quality`）：

```
totalInspections 13   passedInspections 6   failedInspections 7   passRate 68.77
```

**代码**（`ProcessingServiceImpl.getQualityStatistics`）：

| 屏上的词 | 实际口径 |
|---|---|
| 检验数 13 | 质检**记录条数** |
| 合格 6 | `result == PASS` 的**记录数** |
| 不合格 7 | `13 - 6` —— 是「**非 PASS**」，把有条件放行和待检都算进来了 |
| 合格率 68.77% | 每条记录**自己的抽样合格率**取算术平均 |

三个数三种基数，屏上没有任何一处标注。

⚠️ 同一个响应里**已经有**语义明确的 `breakdown`，而且它是**专门为移动端加的**
（`APP-CONTRACT-004`，注释原话「移动端质检首页要分开显示待检/通过/未通过」）——
`ReportDashboardScreen` 没有用它。

**实测 breakdown**：`{pass: 6, fail: 4, conditional: 1, pending: 0}` —— **合计 11，不是 13**。
另外 2 条记录的 `result` 不属于这四类中的任何一类，两边都数不到它们。

### R6 🟡 同一租户、相邻两屏，总成本一个是 ¥0 一个是 ¥755.90

- `BossReportsTab/ReportDashboard` → 成本概览：`¥0 总成本 / ¥0 材料 / ¥0 人工 / ¥0 制造费用`
- `BossReportsTab/CostReport` → 成本总览：`总成本 ¥755.90`（物料 ¥565.25 74.8% + 人工 ¥190.65 25.2%）

两屏都在报表 tab 里，相隔三行。同屏的「人效概览」也是全 0
（`0 工人数 / 0 总计件 / 0.0 件/小时 / ¥0.00 单件成本`）。

⚠️ 而 `GET /reports/dashboard/production` 这个**给 013 供数的接口本身**返回
`totalCost = 605.91` —— 屏上那个 `¥0` 连它自己的数据源都对不上。

### R7 🔴🔴 没有数据时，后端**发明一个好看的数**（三个常量，写死在同一段里）

`ProductionReportServiceImpl.getKpiMetrics`（`:1350-1364`）：

```java
outputCompletionRate = 有计划且有产出 ? 实算 : new BigDecimal("85");   // 产出完成率 → 85%
fpy                  = totalInspections > 0 ? 实算 : new BigDecimal("96");   // 一次合格率 → 96%
otif                 = totalShipments   > 0 ? 实算 : new BigDecimal("95");   // 准时交付 → 95%
```

**一家一条数据都没有的工厂，会读到「产出完成率 85% / 一次合格率 96% / 准时交付 95%」。**

**实测**（`GET /api/mobile/F006/reports/kpi-metrics?date=2026-08-28`）：

```
otif = 95.00     onTimeDeliveryRate = 95.00     inFullDeliveryRate = null
overallQualityRate = 95.00   scrapRate = 5.00
mtbf = 168   mttr = 2                      ← 这两个是字面常量, 与设备无关
materialCostRatio = 60  laborCostRatio = 25  overheadCostRatio = 15   ← 合计恰好 100
```

**屏上的样子**（`BossReportsTab/KPIReport`「数据洞察」）：`95 % OTIF`、`综合质量率 95.0%`、`废品率 5.0%`。
而**同一个 tab 里的**`BossReportsTab/OnTimeDeliveryReport` 说：
「**暂无OTIF数据 · 请确认工厂已配置交付监控**」。

▎两屏对同一个指标，一个说 95%，一个说没有数据。**说没有数据的那个是对的。**

⚠️ 这些数还进了 `calculateOverallScore` ⇒ 屏上的「综合评分 29.2 分 / 评级 D」有一部分是拿编出来的输入算的。

✅ **正确写法就在下面第三行**（同一个方法内）：

```java
// 单位成本 = 期间实际生产成本 / 期间产出. 无产出(如纯零售租户)或无成本数据时返 0 (诚实空, 不造假).
```

⇒ 这不是「该怎么做」的分歧，是同一个方法里两种做法并存。

⚠️ 这条在**后端**，所以 web-admin 那侧读同一批 KPI 的屏一样中招 —— 第一轮没抓到它。

### R8 🟡 质量数据在两屏之间是两个不同的答案，而**对的那一屏就在隔壁**

| 屏 | 显示 |
|---|---|
| `ReportDashboard` 质量概览 | 检验数 13 · 合格 6 · **不合格 7** · **合格率 68.8%** |
| `QualityReport` 质检统计 | 总质检数 13 · 合格 6 · **不合格 4** · **待定 1** · **合格率 46.2%** |

`QualityReport` 是对的：它用了后端专门为移动端加的 `breakdown`，把「待定」单列，
合格率按记录数算（6/13 = 46.2%）。`ReportDashboard` 两样都没做（见 R5）。

⚠️ 两屏都漏了同样 2 条记录：`pass 6 + fail 4 + conditional 1 + pending 0 = 11`，而总数 13。

### R9 🟡 生产批次数在四屏之间是四个数

| 屏 | 说法 |
|---|---|
| `ReportDashboard` 生产概览 | 总批次 **2** · 已完成 0 · 完成率 **0.0%** · 总产出 **0** |
| `ProductionReport` 生产统计 | 生产批次 **8** · 总产量 **230.00 kg** · 完成率 **100.0%** |
| `ProductionAnalysis` | 总产出量 **0** · 报工总数 **0** · 综合良率 0% |
| `RealtimeReport` | 进行中批次 **5** |
| `BossProductionPlans` 顶部 | 总计划数 **21** · 生产中 **10** · 已完成 **0** |

**实测两个接口**（同一租户、同一时刻）：

```
/reports/dashboard/production : totalBatches=2   completedBatches=0  totalOutput=0    totalCost=605.91
/reports/dashboard/overview   : todayBatches=2   allActiveBatches=20 inProgressBatches=20 monthlyOutput=230.0
```

同一个词「批次数」在后端就已经有 `totalBatches / todayBatches / allActiveBatches /
inProgressBatches / todayInProgressBatches` 五个口径，屏上一个都没标注。

⇒ 与第一轮交接件「第二优先：把数字口径定一次」是同一件事，**App 侧同样成立**。
⛔ 逐屏改会造出第六个口径。

### R10 🟡 人员报表：22 个人全部「缺勤」，而另一个接口说 22 个人全部「在岗」

**屏**（`BossReportsTab/PersonnelReport`）：`总人数 22 · 在岗 0 · 缺勤 22（红色）· 平均出勤率 0.0%`

**实测**：

```
/personnel/statistics    : totalEmployees=22  totalPresent=0   totalAbsent=22  avgAttendanceRate=0.0
/reports/dashboard/overview : onDutyWorkers=22  activeWorkers=22  totalWorkers=22
```

两个后端接口对同一天同一租户给出**相反**的答案。屏上取的是前者，于是断言
「22 人今天全部缺勤」—— 而真相多半是「没有打卡数据」。

▎**「没有考勤记录」和「22 个人都没来」不是一回事**，红色大字把前者说成了后者。
（第一轮 web-admin 的「总记录 0 / 打卡 0 人 而 缺勤 345」是同一个形态。）

### R11 🟡 成本差异：计划成本是「—」，却算得出 +768.60% 并判定「严重超支」

**屏**（`BossReportsTab/CostVarianceReport`）：

```
总差异金额  + ¥1,177.18   +768.60%   严重超支
计划成本  -          实际成本  ¥1,330.34          实际/计划  -
```

计划成本自己显示为 `-`（拿不到），差异率却给到小数点后两位，还配了一个结论标签。

⇒ 与第一轮 V28（计划为 0 时 BOM 达成率显示 100%）同族：**分母未知时，差异没有定义。**

### R12 🟢 `ratios.status.danger` —— i18n key 直接印在屏上

**屏**（`BossAnalysisTab/FinancialRatios`）：`92 财务健康分 · 12 良好 · 1 警告 · 0 ratios.status.danger · 13 指标总数`

**代码**：`FinancialRatiosScreen.tsx` 四处用 `t('ratios.status.danger')` / `t('ratios.status.danger_below')`，
而 `zh-CN/smartbi.json` 和 `en-US/smartbi.json` 的 `ratios.status` 只有
`good / caution / warning / good_above / caution_near / warning_below` —— **没有 `danger`**。
i18next 取不到就把 key 本身打出来。两种语言都缺。

### R13 🟡 现金流分析：160.2 万经营现金流 + 一句「企业主营业务运营健康」

**屏**（`BossAnalysisTab/CashFlow`）：`160.2万 经营现金流 / -34.6万 投资 / -15.4万 筹资 / 110.3万 净现金流`，
下面跟着结论「经营活动产生正向现金流，企业主营业务运营健康。净现金流为正，资金状况良好。」

同一租户同一天，`CostReport` 的**总成本是 ¥755.90**，销售订单金额是 ¥221.24 / ¥354 / ¥0.05 这个量级。
160 万级的现金流与这家租户的实际业务量差四个数量级。

⚠️ 相邻两屏（`CustomerRFM`、`FinancialRatios`）都**明写**了「当前显示演示数据」，
**只有现金流这一屏没有**，还额外给了一句经营结论。
⇒ 判据不是「能不能演示」，是**演示数据必须自报家门**；这一屏是同族里唯一不报的。

### R14 🟢 五个分析屏是同一句「接口尚未实现」，但在菜单里和真功能并排

`ProductionDashboard / QualityDashboard / InventoryDashboard / ProcurementDashboard / SalesFunnel`
五屏正文都是同一句：「该功能数据接口尚未实现，请通过SmartBI上传Excel查看分析」。
`TrendReport`「即将上线」、`ForecastReport` 只列了四条 bullet。

话说得诚实，问题在**入口**：SmartBI 首页「全部功能」把它们和能用的功能排在一起，副标题
（「销售趋势与区域分析」「KPI总览与实时监控」）读起来完全像已经能用。

### R15 🟢 `Excel上传(旧)` —— 内部版本记号出现在屏幕标题里

`BossAnalysisTab/ExcelUpload` 的标题栏写着「**Excel上传(旧)**」。
与第一轮 V1（`Sprint 8 P1 (2026-05-20)`）同族：给自己看的记号发给了用户。

### R16 🟢 销售订单卡片上的 `锁:0 备:0 缺:10`，以及「已完成」却还「缺 10」

`BossOverviewTab/BossSalesOrders` 每张卡片底部三个单字缩写 `锁 / 备 / 缺`，无图例、无说明。
且 `SO-20260828-0003` 状态是**已完成**而 `缺:10`（红色），`SO-20260825-0004` 已完成而 `缺:5`。

⚠️ 这一条是**线索**：需要确认「缺」的口径是不是允许在已完成单上非零。尚未在代码里核实。

---

## 车间主任（`f006_workshop`）

### W1 🔴🔴 「任务执行」三步引导：**批次号是真的，其余全是写死的**，最后那个按钮什么都不做

**入口是真的**：`WSHomeScreen.tsx:443 / :625` 拿着**真实批次**
`navigate('TaskGuide', { batchId, batchNumber })` —— 车间主任在首页点一个在产批次就进来了。

进来之后（三个屏一次 API 都不调，`grep -c 'await|apiClient|useQuery'` = **0**）：

| 屏 | 屏上写的 | 代码里的来源 |
|---|---|---|
| 步骤 1 前往工位 | 车间 **A区车间** · 生产线 **1号生产线** · 工位 **切片工位** · 设备 **切片机A (EQ-001)** | `TaskGuideScreen.tsx:55-59` 字面量 |
| 标题 | `PB-…-61699`（真） · **带鱼片 · 目标80kg**（假） | `:87` 用参数，`:90` 写死 `product: '带鱼片', target: '80'` |
| 步骤 2 确认设备 | **切片机A / EQ-001 / OEE 92% / 上次维护 2025-12-20** + 「启动设备」 | `TaskGuideStep2Screen.tsx:67-71` 字面量 |
| 步骤 3 召集人员 | **王建国 EMP-001 96% / 李明辉 EMP-002 92% / 刘晓峰 EMP-003 88%**「3 已到齐」 | `TaskGuideStep3Screen.tsx:68-70` 字面量 |
| 收尾 | 按钮「**确认开始生产**」 | `:76-90` —— 弹个确认框，然后 `navigate('BatchDetail')`。**没有任何写入。** |

**F006 实际在产的是** `SOP-20260817-01-黄油鸡-成品800g`，实际员工是
六膳门操作员 / 永珍 / 徐师傅 / 魏振江 / 莫云。**带鱼片、王建国、切片机A 都不是这家的东西**，
日期停在 2025-12-20 / 2025-12-27（九个月前）。

⇒ 车间主任被指引到一个不存在的工位、去启动一台不存在的设备、确认三个不存在的人到齐，
   然后按下一个**不开工**的「确认开始生产」。
⚠️ 屏上**没有任何一处**说这是演示数据；而真实批次号在标题里，让整屏读起来是权威的。

**同族（同样零 API、同样无标注）**：

| 屏 | 写死的内容 |
|---|---|
| `WorkerDetail` 员工详情 | 王建国 / EMP-001 / 效率96% / 出勤率92% / 电话 138****5678 / 近期任务 PB-20251227-001 |
| `WorkerAssign` 分配员工 | 6 个人名 + 效率 + 可用/忙碌/离岗 |
| `BatchStart` 创建批次 | 产品下拉只有 **带鱼片 / 鲈鱼片 / 黄鱼片 / 银鲳鱼片** —— 这家做黄油鸡 |
| `BatchStage` 数据录入 | 「自动采集数据(只读)」时长45分钟 / 投入95kg / 产出52kg，署名「**切片机A传感器** · 更新于 10:30:15」；AI 置信度 92% |
| `BatchComplete` 完成批次 | 目标80 / 实际78 / 合格76 / 合格率97.4% / 总耗时3小时15分 |
| `AttendanceHistory` 考勤历史 | **2025年12月**，18/20 出勤天数，逐日打卡时间 08:02–17:05 |

⚠️ `BatchStage` 那屏最需要注意：它把写死的数标注成「**自动采集数据（只读）· 数据来源：切片机A传感器**」——
**假数据带了一个来源署名**。

### W2 🟡 同一屏两个面板对同一天给出两组数

`WSHome`（车间主任首页）：

```
今日生产工作流   11 待生产    10 进行中    16 已完成
今日任务概览      2 已分配    20 进行中     0 已完成
```

再加上 `WSBatches` 批次管理：`全部(28) 进行中(20) 待开始(0) 已完成(8)`（这一组自洽），
和 `WSProfile` 我的：`6 管理批次 · 8 在岗人员 · 4 设备运行`。

「进行中」在同一个角色下有 10 / 20 / 6 三个值。

### W3 🟡 「在岗人数」在四处是四个数，其中一处把「没打卡」写成「缺勤」

| 屏 | 在岗 | 备注 |
|---|---|---|
| `WSWorkers` 人员管理 | **0** | 「22 未打卡」——**用词是对的** |
| `WSProfile` 我的 | **8** | 无出处 |
| `ClockIn` 打卡管理 | — | `0 已打卡 / 0 迟到 / **0 缺勤** / 0 应到` |
| `PersonnelReport`（老板侧） | **0** | 「**缺勤 22**」红色大字 |
| `/reports/dashboard/overview` 接口 | **22** | `onDutyWorkers = 22` |

▎`WSWorkers` 说「未打卡」，`PersonnelReport` 说「缺勤 22」，`ClockIn` 说「缺勤 0」——
▎**三种说法，其中「未打卡」是唯一站得住的那个。**

### W4 🟢 设备统计：4 + 2 + 1 ≠ 8

`WSEquipment` 设备管理顶部：`4 运行中 · 2 空闲 · 1 需维护 · 8 总计`。少一台没有归类。

### W5 🟢 状态码与 UUID 直接给到车间

- `YieldBatchSelect`：`计划 8 · 状态 **IN_PROGRESS**`（枚举码，未翻译）
- `Notifications` 通知：「销售单 **449976de-e2ec-4174-8858-8e60dd46f8fc** 财务审核通过…」

### W6 🟢 计划量未设置时，进度一律显示 `0%`

`ProcessTaskHistory` 工序历史：`已完成 · 计划 未设置 · 完成 4 · 进度 **0%**`、
`完成 6.4 · 进度 **0%**`。已完成 4 而进度 0% —— 与第一轮 V28 同族（分母缺失时进度没有定义）。

### ✅ 车间侧的正面样板（照抄这几处，不要照抄上面那些）

| 屏 | 做对了什么 |
|---|---|
| `TeamBatchReport` / `NfcCheckin` | 「**当前显示全工厂批次（无分配给您的批次）**」—— 说清了看到的是什么、为什么 |
| `MaterialConsumption` | 「暂无BOM物料数据 · **请先在Web端设置产品的BOM清单**」—— 空态 + 去哪儿办 |
| `DraftReports` | 「暂无保存的草稿 · 在报工页面可点击「保存草稿」暂存未完成的报工」 |
| `ProcessTaskList` | 缺的字段逐个写成「**未排期 / 未分配 / 未设置**」，⛔ 没有拿 0 顶替 |

---

## 人事（`f006_hr_admin`）

### H1 🔴🔴 「添加员工」「添加部门」两个录入表单，**每个字段名都是 i18n key**

`StaffAdd` 屏上逐字：

```
添加员工
staff.add.sections.basicInfo
staff.add.fields.username
staff.add.fields.fullName
staff.add.fields.phone
staff.add.fields.email
staff.add.sections.workInfo
staff.add.selectDepartment
staff.add.roles.worker
...
```

`DepartmentAdd` 同样：`department.add.fields.name / .description / .manager / .fields.status / .submit`。
`WhitelistAdd` 混着来：其他标签是中文，只有 `whitelist.add.name` 是 key。

**成因**：`zh-CN/hr.json` 里 `staff.add` 只有 `{title, success, failed}` 三个键，
屏上用到的另外 21 个键**一个都没有**。i18next 取不到就打印 key 本身 —— 不报错、不抛异常。

⇒ HR 主管分不清哪个框填手机号、哪个填邮箱。**这是这个角色最主要的录入入口。**

### H2 🔴 这不是两三处 —— **全 App 有 564 个 t() 键没有译文**，分布在 40 个文件

扫描口径（保守，只算板上钉钉的）：只取**字面量** key、只取 `useTranslation` 命名空间唯一的文件、
带 `defaultValue` 的不算缺失。

```
检查文件 249 个   检查键 5041 个   缺失 564 个 (11.2%)   涉及文件 40 个
跳过: 命名空间不唯一 1 个 / 取不到命名空间 23 个        ← 所以 564 是下界
命名空间分布: platform 128 · home 117 · management 88 · hr 51 · processing 37 · profile 29 · 其余 18
```

阳性对照：我在截图上**亲眼看到过**的四个 key（`smartbi:ratios.status.danger`、
`hr:staff.add.fields.username`、`hr:department.add.fields.name`、`hr:whitelist.add.name`）
在扫描结果里**全部命中** ⇒ 扫描器不是在空转。

最严重的几个文件：`QualityCheckItemDetailScreen`(87) · `EntityDataExportScreen`(57) ·
`BlueprintApplyScreen`(51) · `BlueprintBindingsScreen`(40) · `StaffAddScreen`(21)。

⇒ 天然的闸：断言每个字面量 `t()` 键都取得到译文。⛔ 类型检查、单测、接口检查对这一类**全瞎**。

### H3 🔴🔴 接口一失败，考勤统计就显示一家**虚构公司**（45 人 / 96.5% / 四个部门）

`AttendanceStatsScreen.tsx:69-85`，`catch` 分支里（注释原文 `// 设置默认数据`）：

```ts
setStats({
  totalEmployees: 45, avgAttendanceRate: 96.5, totalWorkHours: 1850,
  avgDailyHours: 8.2, lateCount: 12, earlyLeaveCount: 5, absentCount: 3,
  departmentStats: [
    { departmentName: '生产一部', attendanceRate: 98, avgHours: 8.5, employeeCount: 15 },
    { departmentName: '生产二部', attendanceRate: 95, avgHours: 8.0, employeeCount: 12 },
    { departmentName: '质检部',   attendanceRate: 97, avgHours: 8.3, employeeCount: 8  },
    { departmentName: '仓储部',   attendanceRate: 94, avgHours: 7.8, employeeCount: 10 },
  ],
});
```

F006 有 **22 名员工、0 个部门**。接口挂了的时候，HR 主管看到的是一家 45 人、四个部门、
出勤率 96.5% 的公司，**屏上没有任何一处提示请求失败了**。

⛔ 直接违反 `CLAUDE.md` 核心原则 1「禁止降级处理 —— 不返回假数据，明确显示错误」。

**同族（同一文件里的 try 分支）**：

```ts
totalWorkHours: 'totalWorkHours' in data ? data.totalWorkHours : data.totalEmployees * 8,
avgDailyHours : 'avgDailyHours'  in data ? data.avgDailyHours  : 8,
```

⇒ 后端不发工时，屏幕就用 **人数 × 8** 顶上。这就是实测到的
`平均出勤率 0.0% · 日均工时 8.0h · 在岗人数 22 · 总工时 176h`（176 = 22 × 8）——
**出勤率 0% 和总工时 176 小时同屏并列**，而 `AttendanceManage` 那屏说「暂无考勤记录 共 0 条」。

**再同族**：`EfficiencyReportScreen.tsx:95-105` 的 catch 把四项效率全设成 `0`，
于是「接口失败」和「效率真的是 0」在屏上**完全一样**（`EfficiencyReport` 实测就是全 0，
我无法从截图分辨它是哪一种）。

⚠️ `AttendanceStatisticsScreen.tsx` 里有三处同样的「使用模拟数据」，但那个文件
**没有被任何 navigator 注册**（见「静态盘点」），走不到 —— 不计入本条。

### H4 🟡 HR「个人中心」的三个数是写死的

`HRProfileScreen.tsx:172-183`：`45` 在职员工 / `5` 部门数量 / `96%` 本月出勤 —— 三个 JSX 字面量。
同一个角色的首页同时显示：`总员工 22 · 在岗人数 0 · 今日出勤率 0.0%`，员工列表里是 8 个人、
全部「未分配部门」。

⚠️ 这里的 `45` 与 H3 catch 分支里的 `45` 是同一家虚构公司。

### H5 🟡 「在岗」在 HR 这一侧同屏矛盾

`HRHome` 顶部：`0 在岗人数 · 总员工 22 · 今日出勤率 0.0%`
`StaffList` 员工列表：**每一个人**的状态都是「**在岗**」。

### H6 🟢 绩效分析：0 条记录，平均分 100.0

`Performance`：`100.0 平均分 · 0 优秀 · 0 需关注`，正下方「员工绩效列表 **暂无绩效数据**」。

### H7 🟢 角色码直接显示

员工列表里：「六膳门出纳　`finance` · 员工」「永珍　`operator`」—— 枚举码未翻译。


---

## 质检员（`f006_quality_insp`）· 操作员（`f006_operator1`）· 销售（`f006_sales_mgr`）

### Q1 🔴 同一屏同时显示 68.8% 和 46.2%，标签让人以为它们说的是两件事

`QIAnalysis` 数据分析：

```
13 检验批次    68.8 % 合格率    — 平均分
等级分布:  A 6批 46.2%   B 1批 7.7%   C 1批 7.7%   D 5批 38.5%
```

「合格率 68.8%」（每条记录抽样率的平均）和「A 等级 46.2%」（6/13，**才是按记录算的合格率**）
并排放着。用户没有任何线索知道这两个百分比的分母不同。

⚠️ `平均分` 显示 `—`，而 `QIRecords` 里每条记录都有分数（`A 0 分`、`A 100 分`）。

### Q2 🟡 一条 `0 分` 的记录，等级是 `A`，结论是「合格」

`QIRecords` 检验记录第一行：`PB-PLAN-…-16734 · SOP-…黄油鸡-成品800g · **A · 0 分** · 2 件 · 六膳门质检员 · 2026/08/25 · 合格`
下一行同批次：`A · 100 分 · 10 件`。

⚠️ 线索，未在代码里核实分数与等级的关系。

### Q3 🟡 质检员「我的」页三个数是写死的

`QIProfileScreen.tsx:52-56`：`useState({ inspected: 12, passed: 11, workHours: '7.5' })`，
文件里 `await` / `apiClient` **出现 0 次** ⇒ `setTodayStats` 永远不会被调用。

屏上写着「**12 今日检验 · 11 检验合格 · 7.5 工作时长**」，而
`QIClockIn` 同时说「暂无打卡记录」，`QIAnalysis` 说**整个月**只有 13 条。
「今日 12 条」与「本月 13 条」并存。

### O1 🔴 财务 / 销售 / 采购 / 查看员的「个人中心」里挂着两个开发者工具

`ProfileHome` 更多功能列表，实测截图（`f006_sales_mgr`）：

```
… 帮助文档 · 隐私设置 · 意见反馈 · 关于应用 · **服务器连接测试**〔开发〕· **意图执行测试**〔AI〕· 退出登录
```

- `服务器连接测试`：屏上直接印出 **`服务器: http://139.196.165.140:10010`**，
  并提供「运行全部测试」（Phase 1 网络健康 / Phase 2 认证 / Phase 3 **业务 API 20 项** / Phase 4 数据完整性）
- `意图执行测试`：「触发 NEED_MORE_INFO，需要补充批次号」这类开发者措辞

**范围（已核，⛔ 不要读成「所有角色」）**：`ProfileScreen.tsx:421/438` 的守卫是
`{!isOperator && …}`，而 `isOperator = roleCode === 'operator'` ——
**只给操作员藏了**。共用 `ProfileStackNavigator` 的其余角色都看得到：
`FinanceNavigator`（财务/出纳）、`SalesManagerNavigator`、`ProcurementManagerNavigator`、
`ViewerNavigator`、`MainNavigator`。

⚠️ **我第一眼读错了**：走查时我从操作员的路由树里跳到了这两个屏，差点写成「操作员能看到开发者工具」。
   实际上路由**注册**在操作员栈里（所以程序化跳得过去），但**入口对他隐藏**。
   ⇒ 「注册了」不等于「有入口」，这是本轮仪器的已知盲区，见「五、这套仪器看不见什么」。

### O2 🟡 销售主管的首页只有一个功能模块，而它是「系统设置」

`SMHomeTab`（销售主管首页）全文：

```
你好, 六膳门销售主管 · 销售主管
功能模块
  系统设置   个人设置、系统配置
```

订单和客户都在底部 tab 里，但首页的「功能模块」区只列了「系统设置」。

### O3 🟡 退货管理：交易对手是 UUID

`ReturnOrderList`：`RT-PUR-20260714-0001 · 交易对手 **770815f3-942e-4419-b7f1-875e9858ea3b** · ¥10,000.10`，
下一条 `交易对手 ce21ae10-e7e8-42d2-bc01-bf278f8d9538`。
同屏的单号是可读的 `RT-PUR-…`。与第一轮「采购收票后面跟三个 UUID」同族。

### O4 🟢 角色显示成 `sales_manager`

`SMProfileTab` 头部：「六膳门销售主管 @f006_sales_mgr · 工厂用户 · **sales_manager**」。
操作员那一屏同一位置显示的是中文「操作员」⇒ 角色名映射表覆盖不全。

---

## ✅ 这一轮遇到的正面样板（要统一，就统一到这几处）

| 屏 | 做对了什么 |
|---|---|
| **`MyWage` 我的工资** | 全屏没有一个编出来的数：金额是 `—` + 「**这个月的工资单还没做出来。工资单做出来之前, 这里不会有金额。**」；估算值明说「**这是机器按你的报工算出来的参考数, 不是发到手的钱**」；没有报工记录时给动作「**可以找班组长确认报工有没有提交上来**」。**面向工人的措辞，零黑话。** |
| `OperatorAssignedProcess` | 「你有 5 道可以报工，点击进入；**其余在等上一道完成**」+「⚠ 1 种原料库存不足，**点击查看缺口明细 →**」 |
| `QITrend` 趋势分析 | 「**没有质检记录的日期已跳过，不按 0% 计入。**」；等级趋势画不出来时写明「**质检趋势接口逐日只返回合格率与检验数，不返回 A/B/C/D**」—— 说清了**为什么**没有 |
| `QIReport` 报告导出 | 「报告导出尚未开放…**在它可用之前，这里不提供生成入口**」+ 指向替代路径 |
| `QILabelQcCreate` | 「**AI 只做疑似初筛 —— 所有照片都会进入人工审核，不会自动判定合格。**」 |
| `SalesOrderCreate` | 「请先选择客户，才能判断留空时用哪个默认税率」；「低毛利、超账期、授信异常由财务审批规则复核」 |
| `TeamBatchReport` / `NfcCheckin` | 「当前显示全工厂批次（**无分配给您的批次**）」 |
| `MaterialConsumption` / `DraftReports` / `ProcessTaskList` | 空态说清「没有什么」+「去哪儿办」；缺的字段写「未排期/未分配/未设置」而不是 0 |

▎和第一轮的结论一样：**这不是「该怎么做」的分歧，是同一个应用里两种做法并存。**
▎`MyWage` 已经把标准写出来了，剩下的是让别的屏也照它做。


---

## 四、结构性的两条（不是某一屏的事）

### G1 🔴🔴 平台侧和餐饮侧两整棵导航树，**现有账号一个都进不去**

`AppNavigator.RoleBasedNavigator` 按**角色字符串**分派。实测三个账号的落点：

| 账号 | 后端返回的 role | 命中的分支 | 结果 |
|---|---|---|---|
| `platform_admin` | **`super_admin`** | 无 —— 分支里写的是 `platform_admin` | 锁定页 |
| `mock_owner` (MOCK_REST) | **`restaurant_owner`** | 无 | 锁定页 |
| `mock_purchase` (MOCK_REST) | **`restaurant_purchaser`** | 无 | 锁定页 |

锁定页的文案本身写得很好（「当前账号角色尚未配置 RN 工作路径…系统不会自动开放通用业务入口」），
**问题不在这一屏，在于它后面挂着的东西没人能到**：

- `MainNavigator` 子树（`PlatformStack` 67 + `ProcessingStack` 43 + `ManagementStack` 24 +
  `SmartBIStack` 15 + `MainNavigator` 8 …）≈ **150 条注册**
- `FactoryAdminNavigator` 子树（`FAManagementStack` 58 + `FAReportsStack` 18 + `FAHomeStack` 12 +
  `FAProfileStack` 9 + `FAAIStack` 9 + Tab 5 + `restaurant/R*Stack` 11）≈ **122 条注册**

⚠️ **口径**：以上是**按文件求和**，个别子栈被多棵树共用（`ProfileStackNavigator` 就是），
所以是量级，不是精确数。精确的部分是**落点**：三个账号都停在锁定页，我逐个截了图。

`roleMapping.ts:96` 把 `developer` 映射成 `platform_admin`，**却没有映射 `super_admin`**；
`authService.ts:244` 则是 `role: backendUser.role` 原样透传。

⇒ 这是第一轮「web-admin 92 条路由用户走不到」在 App 侧的同形，而且更彻底：
  那边是缺菜单项，这边是**整棵子树的入口条件永远为假**。
⛔ 修法不是逐个加角色字符串 —— 先定「谁应该进哪棵树」，再让分派表只有一处。

### G2 🔴 没有顶层 ErrorBoundary：任何一屏的渲染异常 = 整个 App 白屏

`App.tsx` 里 `<AppNavigator/>` 上面没有任何错误边界，全仓唯一的 `componentDidCatch`
在 `MarkdownRenderer` 内部。本轮 50 次崩溃全部是这个长相：

```
PAGEERROR: ...
→ 导航容器整个卸载, rootState 变 null, 可见文本变 ""
→ 截图是一张 3KB 的白矩形
```

⚠️ 白矩形在**任何**「有没有问题」的判据上都长得像「干净」——
本轮的走查脚本专门加了 `navAlive()` 才没有把崩溃之后的每一屏都记成正常。

⇒ 建议：`App.tsx` 包一层 ErrorBoundary，至少给用户一句话 + 一个「回首页 / 重试」。
  车间里白屏只能杀进程重开。


---

## 五、这套仪器看不见什么（⛔ 别把没看见读成没有）

写在前面，因为本轮我自己差点踩了第一条。

| # | 盲区 | 长相 | 怎么补 |
|---|---|---|---|
| 1 | **「注册了」≠「有入口」** | 走查用路由名跳转，**绕过了按钮上的守卫**。`ServerConnectivityTest` 注册在操作员栈里，但 `ProfileScreen` 的入口写着 `{!isOperator && …}` —— 我差点写成「操作员能看到开发者工具」 | 每条「用户能看到 X」的结论，回到那一屏的**入口条件**上核一次 |
| 2 | **RN-on-web ≠ 真机** | 「文本节点直接放在 `<View>` 里」在 web 只是 warning（`KPIReport` 实测），**在真机上是抛异常**。所以这一轮对这一类是**偏乐观**的 | 这一类要么读 console warning，要么真机/模拟器上跑一遍 |
| 3 | **相机 / NFC / 扫码 / 定位走不到** | `QIScan` 停在「需要相机权限」，`NfcCheckin` 只能到选批次那一步 | 这些屏本轮**没有验收**，不是「没问题」 |
| 4 | **只导航，不点按钮** | 表单校验、提交、审批动作全没走 | 下一轮的正经工作量在这里 |
| 5 | **详情屏只能空参数进入** | 46 次崩溃属于此类，⛔ 不是产品缺陷 | 要看真实详情屏，得从列表点进去（下一轮） |
| 6 | **接口通的时候，失败分支没被走到** | 与第一轮「坑③」同一条 | 用 `page.route` 打成 404 再看（web-admin 那套 `vshot-force-fail` 的 RN 版还没写） |
| 7 | **改了源码不重启 metro，拿到的是旧 bundle** | 修完仓储主管重跑**仍然白屏**，重启 metro 才正常 | 已写进 `vshot.config.ts` 顶部 |

---

## 六、静态盘点：注册在案但没人引用的 21 个屏

`src/screens` 下 444 个 .tsx 里，**124 个**没有被任何 navigator 注册。逐个分类之后：

- **103 个**是被别的屏 import 的子组件（`home/components/*`、`ModuleCard` 之类）—— 不是屏，正常
- **21 个**「没有 navigator 注册、也没有任何产品代码 import」：

```
attendance/TimeStatsScreen                       processing/AlertDashboardScreen
factory-admin/config/QualityCheckItemConfigScreen  processing/AlertDetailScreen
factory-admin/device/AIDeviceInputScreen         processing/CostAnalysisDashboard/index.tsx  ← 见下
factory-admin/device/CameraAddMethodScreen       processing/DeepSeekAnalysisScreen
factory-admin/device/SmartDeviceAddScreen        processing/NfcTagManagementScreen
factory-admin/device/UnifiedDeviceManagementScreen  production/LabelRecognitionMonitorScreen
factory-admin/isapi/DeviceSetupWizardScreen      rd/SamplePhotoGalleryScreen
factory-admin/management/QualityCheckItemDetailScreen  rd/SampleRequestDetailScreen
factory-admin/management/WorkReportApprovalScreen      rd/SampleRequestListScreen
                                                  smartbi/DynamicAnalysisScreen
                                                  smartbi/EfficiencyDashboardScreen
                                                  test/PushNotificationTestScreen
```

⚠️ 其中 **`processing/CostAnalysisDashboard/index.tsx` 是「同一个屏有两份」**：
同目录下同时存在 `CostAnalysisDashboard.tsx`（788 行）和 `CostAnalysisDashboard/`（index 147 行 +
`components/` + `hooks/` + `styles.ts` + `README.md`）。
`import ... from '../screens/processing/CostAnalysisDashboard'` 在 Metro/Node 的解析顺序里
**先命中同名 .tsx 文件**，所以跑的是 788 行那一份，**整个目录版本是死的**。

⛔ 这一份清单**不是**「可以删掉的 21 个文件」—— 其中几个（设备管理 / IoT / 质检项配置 /
报工审批）正是 `navigatorMountContextReachability` 那道闸记录的「注册在恒假分支里」那批的邻居，
需要的是**决定它们该不该有入口**，不是删。


---

## 七、这一轮修了什么，以及**在屏上看到了什么**

判据不是「合并了」，是**重新截图并用眼睛看到**。下面每一行的「改后」都是第二轮截图里的实际文本。

⚠️ 先记一条踩过的坑：**改完源码必须重启 metro**。第一次重跑仓储主管仍然白屏，
不是修得不对，是 `CI=1` 下 metro 关掉了文件监听、发的还是旧 bundle。
「合并了」和「屏上看到了」之间还隔着一个 bundle —— 已写进 `vshot.config.ts` 顶部。

| # | 改了什么 | 改前（第一轮截图） | 改后（第二轮截图） |
|---|---|---|---|
| R0 | `WHHomeStackNavigator` 删掉重复注册的 12 行 | 仓储主管登录后**永久白屏**，走查 **0 屏** | 「仓储首页 · 请选择今天要处理的工作 · 入库 / 发货 / 仓库地图」，走查 **51 屏** |
| R1 | `SalesAnalysis` 四处守卫改成守它真正要读的字段 + 空态 | `PAGEERROR: … reading 'totalSales'` → 整屏白 | 「**这段时间没有销售数据 · 当前时间范围内暂无销售数据**」（后半句直接用后端自己的话） |
| R3/R4 | `FinishedGoodsBatch` 对齐真实响应；品名读 `productName`，总量读 `producedQuantity`，保质期读 `expireDate` | 品名 `eb0aa47b-a5dd-49dc-af20-bf48ce8e1207` · 总量「　盒」· 进度条恒 0 · 无保质期 | 品名 **`SOP-20260817-01-黄油鸡-成品800g`** · 总量 **`10 盒`** · 进度条按比例 · **`有效期: 2027-02-21`** |
| R4b | `deriveStockStatus` 与 `getStockStatus` 收敛到一个 `totalOf` | 两份实现用同一个不存在的字段，得出**相反**的错误答案（一律 LOW_STOCK / 一律「充足」） | 两处同源；「库存偏低」这一档从**永远走不到**变成可达 |
| P1 | `PurchaseExceptionList` 类型对齐后端 `ReceiveExceptionType`，兜底色改 6 位 | `Unable to parse color from string: #66622` → 整屏白（**线上 31 行 100% 命中**） | 31 行正常渲染，标签 **超收 / 少收** 与后端枚举一致 |
| P1b | 决策弹窗的 `isOver` 判断改用 `OVER_RECEIVE` | 恒为假 ⇒ **超收异常弹出的是少收的处理选项** | 收窄类型后 TypeScript 自己报出了这两处，已改 |
| H1/R12 | 补齐 45 个缺失译文键（`hr` 39 + `smartbi` 3 + 考勤失败态 2×2） | 「添加员工」整屏是 `staff.add.fields.username` 这类 key；财务比率显示 `0 ratios.status.danger` | 「基本信息 / 登录用户名 / 姓名 / 手机号 / 邮箱 / 工作信息 / 选择部门 / 岗位 / 保存」；「**0 危险**」 |
| H3 | 考勤统计：删掉 catch 里那家虚构公司，加失败态；工时不再用 人数×8 | 接口失败 ⇒ 45 人 / 96.5% / 四个部门，**不提示失败** | 失败显示红框「考勤统计没取到…下拉可以重试」；工时取不到显示 **`—`**（原来是 `176h` / `8.0h`） |
| H4 | `HRProfileScreen` 三个写死的数 | `45 在职员工 · 5 部门数量 · 96% 本月出勤` | `— · — · —` |
| Q3 | `QIProfileScreen` 三个写死的数 | `12 今日检验 · 11 检验合格 · 7.5 工作时长` | `— · — · —` |
| 闸 | 新增 `navigatorRouteNamesAreUnique.test.ts` | — | 把 R0 的重复块放回去，闸**当场变红**并指名 `WHHomeStackNavigator.tsx:Stack:WHLocationManage 注册了 2 次`；删掉后转绿 |

**逐角色对照（第一轮 → 第二轮）**

```
warehouse_manager   0 屏  → 51 屏   （崩溃 15 全部是「无参数进详情屏」那一类）
boss               45 屏 崩 3 → 45 屏 崩 2   （少的那一个是 SalesAnalysis）
procurement_manager 24 屏 崩 4 → 24 屏 崩 3   （少的那一个是 PurchaseExceptionList）
hr_admin           63 屏 崩 15 → 63 屏 崩 15  （HR 的崩溃全是参数类，本轮没动）
quality_inspector  28 屏 崩 8  → 28 屏 崩 8   （同上）
```

`tsc --noEmit -p tsconfig.json` **0 error**；`src/__tests__/gates` 18 suite / 157 项全过。

⚠️ 口径：RN 的 `tsconfig.json` 的 `include` 只有 `src/**/*` + `App.tsx` + `types/**/*` ——
所以「0 error」**不包含** `tests/vshot/`（那套走查工具由 Playwright 自己转译，
且 `@playwright/test` 刻意不在 devDependencies 里）。别把它读成「全仓都查过了」。

### 修好之后才露出来的（P1 的下游）

采购异常屏原来根本打不开，修好之后才看得见：

- 「采购单: **39603589-739e-4094-ac50-ab745e977077**」—— 裸 UUID。
  响应里没有 `purchaseOrderNumber`，屏幕退回 `purchaseOrderId`；而同一个 App 的采购单列表
  用的是可读的 `PO-20260828-0001`
- 物料名多行是 `—`（响应里 `materialName: null`）；供应商名从不显示（响应里没有 `supplierName`）
- 「**处理异常**」是一个**大红色危险按钮**，而它只是走一次分诊 —— 与第一轮 V45
  「收货用红色危险按钮」是同一个颜色与风险倒置

⇒ 这三条与 R3/R4 同族（前端读后端不产出的字段），**已记账，本轮未改**。

### ⛔ 本轮没有动的（写清楚，避免被读成「都修了」）

- **R7 后端三个编出来的常量（85 / 96 / 95）** —— 在 Java 侧，改动会同时影响 web-admin，
  需要单独一条线和一次后端发布
- **W1 车间「任务执行」三步引导的写死数据** —— 那三屏零 API，要接真实数据是设计问题，不是改几行
- **G1 平台侧 / 餐饮侧进不去** —— 需要先定「谁进哪棵树」
- **G2 顶层 ErrorBoundary** —— 建议加，本轮没加
- **剩余 519 个缺失译文键**（本轮补掉 45 个）—— 建议做成闸，⛔ 不要盲改


---

## 八、仓储（`f006_warehouse_mgr`，修好白屏之后走的 51 屏）

▎**这一棵树是全 App 写得最好的一块**——除了四个屏是纯虚构。两件事都要说。

### WH1 🔴🔴 四个仓储屏是整套编出来的数据，日期停在 2025-12，而其中一个是**召回管理**

| 屏 | 屏上写着 | 代码 |
|---|---|---|
| `WHAlertList` 库存预警 | 「常规告警 **0** · AI智能告警 **3**」，下面是「🤖 过期风险预警 - **带鱼批次** / 批次 MB-20251220-001 (带鱼 85kg) 将在3天后过期，按当前消耗速率 **28kg/天**…」+ 三条「🤖 AI 建议」（联系**鲜食超市**加急订单可消化 50kg…） | `WHAlertListScreen.tsx:73` `const aiAlerts: AIAlert[] = [ … ]` —— 三条写死。⚠️ 这一屏**同时**调了 3 个真接口拿「常规告警」，真的那一路返回 0，假的那一路返回 3，**并排放在同一个「共 3 条预警」下面** |
| `WHAlertHandle` 预警处理 | 带鱼(鲜品) MB-20251223-001 / 当前库存 85 / 安全库存 200 / 缺口 115 / **过期时间 2025-12-29 (3天后)** / 推荐供应商 **舟山渔业合作社** / 建议采购量 150kg | 零 API |
| `WHRecallManage` 召回管理 | 「进行中(1)」`RC-20251226-001 · 紧急 · 执行中` / 召回原因 供应商质检问题 / 涉及 200kg / **发起时间 2025-12-26 09:00** / 追溯范围：库存在库 50kg 已冻结、已发货 100kg → **3家客户 通知中** | 零 API |
| `WHConversionAnalysis` 转换率分析 | 「本月整体转换率 **92.3%**（AI分析）↑1.2% 较上月 / 行业平均 91% / 带鱼片 94.5% / 虾仁 88.5%」 | 零 API |

F006 做的是**黄油鸡**，不是带鱼和虾仁；今天是 **2026-08-28**，而「3天后过期」指向的是
**2025-12-29**——八个月前。

🔴 `WHRecallManage` 最危险：**召回**是对外、不可逆的动作。屏上明确写着「已发货 100kg →
3家客户 通知中」。仓管按这一屏行动，或者只是拿它汇报，后果都是真的。

### WH2 🟡 `22222222222 kg` 待收，无任何异常标注

`WHInboundList` / `WHPurchaseReceiveList`：`PO-20260822-0001 · 2015胸肉：待收 **22222222222 kg**`。
第一轮 V45 在 web-admin 记过同一个值（`22,222,222,222kg`）。这里连千分位都没有。

### WH3 🟡 调拨单调出方与调入方都是 `F006`

`TransferList`：`TRF-20260828-5270 · 厂内调拨 · 类型 分部→总部 · 调出方 F006 · 调入方 F006 · ¥0`。
「从哪到哪」看不到——与第一轮 web-admin 的同一条完全一样，两端都没修。

### ✅ 仓储侧的正面样板（这一块该被当成全 App 的标准）

| 屏 | 做对了什么 |
|---|---|
| `WHTempMonitor` | 「**温控设备未接入** —— 本工厂还没有接入温控设备，这里不显示温度读数。**下方的告警记录来自真实设备告警接口，可正常查看。**」⇒ 一屏之内把「哪一半是空的、哪一半是真的」分开说清楚 |
| `WHOutboundList` | 「手工登记的发货单（**不自动扣成品库存**）。销售发货单的真发货请用『发货确认』入口。」 |
| `WHDeliveryConfirmList` | 「已发货、已签收、已取消等终态在『已完成/历史』中**只读**展示，**不会再次扣库存**。」 |
| `WHInventoryCheck` | 「盘点差异将自动报给财务审批，**审批通过后库存才会调整。您无法直接修改库存数量。**」 |
| `WHTransitLedger` | 「每行必须核对 SKU、批次、数量和单位；**不同单位不会合并成总数**。」 |
| `PriceAnomalyApproval` | 「**当前账号无审批权限**。请联系老板或店长处理价格异常。」⇒ 不是灰按钮，是说清楚为什么 |
| `WastageReport` | 「照片证据（**必须上传至少 1 张**）…无照片无法提交审批」，提交按钮禁用并写明「请先上传照片后方可提交」 |
| `WHStocktakeList` | 驳回记录带**原因原文**：「验收完毕: 为验证条件节点两侧行为而建的测试盘点, 予以驳回, 不调整库存」 |

▎这八处的共同点：**说清楚这个数是什么、哪一半没有、下一步该找谁。**
▎`MyWage` 和它们是同一种写法。⇒ 统一到这一种是可论证的，不需要谁拍板。


---

## 九、部署记录（OTA）

PR **#287** 合并进 main（`626bda9550`），三道 CI 全绿（jest / no-literal-nul-bytes / tracked-secret-scan）。
从 **detached worktree @ origin/main** 推 OTA（RN 子树 hash 与我的分支完全相同：`bc06d381e1…`）：

```
./scripts/ota/push-bundle.sh production android
PUSH_EXIT=0
✓ bundle live: rv=1.0.4 channel=production ts=1787898056914   (2026-08-28 14:23 CST)
```

### 判据：不是「推成功了」，是**在正在下发的那份制品里找到这次改动的标记**

Hermes `.hbc` 不以 UTF-8 直存中文，所以标记选 ASCII。取**新旧两份 bundle 各 grep 一次**
（旧的是昨晚 23:28 那份 —— 也就是把仓储主管弄白屏的那一份）：

| 标记 | 旧 bundle `1787844438192` | **新 bundle `1787898056914`（正在下发）** |
|---|---:|---:|
| `SHORT_DELIVERY` | 1 | **0** |
| `OVER_DELIVERY` | 1 | **0** |
| `UNDER_RECEIVE` | 0 | **1** |
| `OVER_RECEIVE` | 0 | **1** |
| `producedQuantity` | 0 | **1** |

⇒ 标记本身是能区分的（旧的有、新的没有；新的有、旧的没有），不是「两边都长这样」。

manifest 按设备的请求头取回来，`launchAsset` 指向新 ts；`createdAt 2026-08-28T06:23:16Z`。

### ⚠️ 「已发布」不等于「已送达」——这一条要说清楚

网关 `ota.cretaceousfuture.com` 的访问日志：**今天 2896 次 manifest 请求**，
其中有真实 Android 客户端（`okhttp/4.9.2`，Expo updates 的 Android 端），最近一次 **13:59**。
⇒ 这条通道是活的，不是「推上去没人取」。

**推送后 13 分钟（14:36:30）观察到一台真实设备取回 manifest**：

```
121.228.99.40 - - [28/Aug/2026:14:36:30 +0800] "GET /api/ota/manifest HTTP/2.0" 200 4708 "-" "okhttp/4.9.2"
```

⚠️ 日志里的 4708 字节**不能**用来判断新旧：okhttp 默认带 `Accept-Encoding: gzip`，
nginx 记的是压缩后的长度（我用 curl 不压缩取到的是 13873），而 manifest 的字段全是定长的
（13 位时间戳、定长 UUID、内容寻址的资产 hash），新旧本来就差不多大。
⇒ 判据换成**端点本身**：该端点对 (rv, channel) 只发最新那一份，无按设备钉版；
我在 14:23 之后两次取回的 manifest 都指向 `ts=1787898056914`、且不含旧的 `1787844438192`。

▎所以准确的说法是：**这台设备在 14:36 拿到的是新 manifest。
▎但它有没有把 bundle 下下来并生效，我没有观察到** —— `.hbc` 在 CDN 上（`dl.cretaceousfuture.com`），
▎不走这台网关，本地日志里看不到。
（本仓有过「OTA 九天零送达而每次推送四步验收全绿」的先例，所以这句话不能含糊。）

**回滚**：`./scripts/ota/rollback.sh 1.0.4 production 1787898056914`


---

# 第三轮（同日）：详情屏 —— 第二轮欠的那一块

第二轮**只导航不点按钮**，26 个详情屏全是「无参数进入 → 整屏白」，**一屏都没真看过**。
这一轮从列表**点进去**：9 个角色 · 走过 290 条路由 · **进到 59 次详情屏 / 20 个不同详情屏 / 0 次崩溃**。

⇒ 反过来证实第二轮那 46 次崩溃确实是**仪器**造成的，不是产品缺陷。

## 「只读」这次是量出来的，不是承诺的

第一次跑完只对比业务单据数，读到 `销售订单 45 → 51`。
查下去：6 张新单 remark 全是「**E2E-ACC 2026-08-28 并发超发验收 #N（可安全清理）**」，
创建时间 15:15:43 与 15:17:22–23 **亚秒级成串**（4 张在 0.5 秒内）——
是**另一条 session 的 E2E**，不是我。

▎但当时的读数**既不能证明我写了、也不能证明我没写** ——
▎两条线同时在动同一个租户，计数差是两者之和。

⇒ 给走查加上「记录每一个非 GET 请求」，重跑仓储角色（**唯一点进过 `*Create` 屏的那个**）：

```
非 GET 共 5 条；除登录外 4 条全部是
  POST /api/mobile/F006/list-summary/internalTransfer
```

而本仓 `DemoReadOnlyInterceptor.java:60` 自己把这条路径标注为
「列表底部合计/一表一分析上下文 (**读性质 POST**)」⇒ **零业务写入**。

## 仪器在这一轮又坏了两次（第 4、第 5 次）

| # | 坏法 | 长相 |
|---|---|---|
| 4 | 候选按「所有元素」的下标记，点击时却索引到「只含可点元素」的另一个数组 | **点的根本不是选中的那个**，而日志照常打出「点了 X」 |
| 5 | 拿动作词过滤**整张卡片** | 卡片上的状态 chip 自己就含「已完成 / 已发货」⇒ 绝大多数列表行被自己的状态字挡掉，14 条路由只点进 2 个详情屏 |

⇒ 第 5 条的判据订正：要打在「**这个元素是不是一个动作按钮**」上（短文本），
不是「这段文字里有没有出现过这个词」。

---

## 三、发现台账（第三轮追加）

### T1 🔴 发完货的单子恒报「缺 = 全量」，而那个红 chip 的用途是催货

`SalesOrderItem.getShortageQty` = `quantity - reservedQty`，**不减已发货**。
发货完成后预留被释放（`reservedQty` 归 0）⇒ 全部发完的单子算出来的缺料等于全量。

**实测**（F006 生产库）：

```
COMPLETED 订单 12 张 —— 12 张都在报缺（100%）
SO-20260828-0003: quantity=10  deliveredQuantity=10  pendingQuantity=0  →  shortageQty=10
```

原注释写明这个 chip 是给「销售员一眼判断催生产 / 紧急采购」用的
⇒ **它一直在催已经发完的货**。

⚠️ 正确的量**当时就在同一个文件里**：`getPendingQuantity()` 用的就是 `quantity - delivered`。
两个概念各写各的，其中一个漏了一项。

✅ 已修（行级减已发货；单级对 CANCELLED 恒 0）。变异对照：去掉 delivered 那一项 →
10 条测试里 4 条红，读数正是缺陷的真实形状（`expected 0 but was 10`）。
⚠️ **要等后端发布之后才在屏上可见** —— 它是 `@Transient` getter。

### T2 🔴 销售单详情的「产品」列是 UUID —— 同一个 `eb0aa47b-…` 的第三次露面

`SalesOrderDetail` 订单明细：`产品 = eb0aa47b-a5dd-49dc-af20-bf48ce8e1207 · 10 盒 · ¥884.96`。
实测同一个响应里 `productName = 'SOP-20260817-01-黄油鸡-成品800g'`，
而屏幕读的 `productTypeName` **恒为 undefined**。发货记录那一处同源。

这个 UUID 已经是第三次出现：第一轮 V4（web-admin 生产计划）· 第二轮 R3（成品库存）· 本轮（销售单详情）。
✅ 已修，屏上核对通过。

### T3 🟡 本月入职：拿**账号创建时间**冒充入职日期

`NewHiresScreen` 筛选与展示都走 `u.hireDate || u.createdAt`。
实测 F006 **22 名员工 `hireDate` 全是 null** ⇒ 屏上显示「本月入职 **1 人** · 入职日期 2026-08-19」，
而那一天是有人给他开了账号。**同一个员工在「员工详情」里入职日期显示 `-`（那一屏是对的）。**

后果不止一个数：第 54 行的**筛选**同源 ⇒「本月入职」实际是「本月建的账号」。
HR 首页那张「1 本月入职」的卡也是它。

✅ 已修：只认 `hireDate`；空列表时说清是哪一种空。

### T4 🟡 质检记录列表给没存过评分的记录印「0 分」

mapper：`totalScore: Number(customFields.totalScore ?? 0)` ⇒ 列表出现
「等级 **A** · **0 分** · 合格」这种自相矛盾的行。

⚠️ `scoreDetailsRecorded` 这个标志**就在同一个 mapper 里，而记录详情屏用对了**：
「该历史记录未保存分类评分；**页面不会用 0 分伪造明细**」。列表没用它。

✅ 已修（显示「—」）。屏上核对：那条变成「A —」，同屏另外两条仍是「A 100分」「C 70分」
⇒ **改的是该改的那一条，不是把整列抹平**（自带阴性对照）。

### T5 🟡 三个详情屏把「人」显示成数字 id

- `PurchaseOrderDetail`：**审批人 1309**
- `TransferDetail`：**申请人 1309 · 审批人 1551**

第一轮在 web-admin 记过「用户 #1309」，两端同源，都还在。

### T6 🟡 退货详情：交易对手与原始订单都是 UUID

`ReturnOrderDetail`：`交易对手 770815f3-942e-4419-b7f1-875e9858ea3b` ·
`原始订单 f0a0da70-9d0e-4721-9762-bbbcee141511`，
而同屏单号是可读的 `RT-PUR-20260714-0001`。

### T7 🟡 已审批的盘点单里一项都没有

`WHStocktakeDetail`：`ST-202608-2A4C1F02 · **已审批** · 品项明细（共 **0** 项，差异 0 项）· 暂无品项数据`。
一张**批准过**的盘点单不含任何品项 —— 要么数据没落，要么详情读错了源。**未核实，线索。**

### T8 🟡 批次详情的状态显示「未知」

`BatchDetail`：`PB-PLAN-…-61699 · **未知**`，而同一批次在列表上是「生产中 / 加工中」。
同屏「负责人」为空、「实际产量 `-`」。**未核实，线索。**

### T9 🟡 操作记录里多条 `0kg` 的「确认入库 · 成功」

`WHOperationLog`：`WIP-CLK-W-20260825-48433 **0kg** … 成功`（同类多条，另有 `0.04kg`）。
0 公斤的入库被记成一次成功的入库。**未核实，线索。**

### ✅ 第三轮新增的正面样板

| 屏 | 做对了什么 |
|---|---|
| `QIRecordDetail` | 「该历史记录未保存分类评分；**页面不会用 0 分伪造明细**。」—— 把本轮的主线**写进了产品文案** |
| `QILabelQcReview` | 「AI 判断：缺白标 · AI 识别：未识别到白色称重/条码标签…**该盒成像不够清晰，请人工确认**」 |
| `YieldStepReport` | 「✓ 2/2 道全部报完 · 批次累计出成率(进行中) 50.00% · 6.4kg → 4 盒」 |

---

## 第三轮的修复与判据

| # | 改了什么 | 改前 | 改后（屏上看到的） |
|---|---|---|---|
| G2 | `App.tsx` 补顶层 ErrorBoundary | 任一屏渲染异常 ⇒ **一片白**（第二轮 50 次） | 「这一页打不开 / …/ `Cannot destructure property 'orderId'…` / **再试一次**」；按下之后回到「经营总览」 |
| T1 | 缺料减掉已发货；已取消恒 0 | 12/12 张已完成订单报「缺 = 全量」 | 待后端发布后核对 |
| T2 | 产品名读 `productName` | `eb0aa47b-…` | `SOP-20260817-01-黄油鸡-成品800g` |
| T3 | 只认 `hireDate` | 「1 人 · 入职日期 2026-08-19」 | 「0 人 · 有 22 名员工没有填入职日期…」 |
| T4 | 没存过评分显示「—」 | 「A · 0 分 · 合格」 | 「A · —」（其余两条仍是 100分/70分） |
| 闸 | `appErrorBoundaryContract` · `i18nKeysResolve`（棘轮 519） | — | 各自变异后当场红 |

```
tsc --noEmit -p tsconfig.json    0 error
src/__tests__/gates              20 suites / 163 tests 全过
SalesOrderShortageQtyContractTest 10/10；变异后 4 条红
```

⚠️ 判据自己的一条错也记下来：第一版断言「边界接住之后 `navAlive()` 仍为 true」——
**红的是判据不是行为**。边界一旦接住，`NavigationContainer` 就在被替换掉的那棵子树里，
`isReady()` 本来就该是 false。要证明的是「**能回来**」，不是「导航还在」。


---

## 第三轮追加：一条**只在真机上才会炸**的，已确认症状但**没定位到源码**

走查捕到的 console 里有 6 次同一条：

```
Unexpected text node: . A text node cannot be a child of a <View>.
```

命中三个屏：`BossReportsTab/KPIReport` · `PMPurchaseTab/PurchaseOrderCreate` ·
`WHProfileTab/WHSettings`（那个文本节点的内容是**一个空格**）。

▎**web 上这只是一条 console error；真机上 RN 会抛**
▎`Text strings must be rendered within a <Text> component`。
⇒ 这三屏在真机上可能是直接崩的，而这两轮走查跑在 web 上，对这一类**系统性偏乐观**。

### 试过但没定位到的（写下来，免得下一个人重走）

| 做法 | 结果 |
|---|---|
| 读 console 消息本身 | **不带组件栈**，只有那一句 |
| 在 DOM 里找「直接文本子节点只有空白」的 `View` | **0 个** —— RN Web 渲染时把它剥掉了，只留一条警告，DOM 里查不到 |
| 源码 grep `}` 与 `{` 同行、`{' '}` | 三个文件里没有匹配；`PurchaseOrderCreate` 那处 `{' '}` 在 `<Text>` 里面，是合法的 |

⇒ **可靠的做法是把这三屏在真机/模拟器上打开一次，读 redbox**。
本机有 Android SDK + AVD `Medium_Phone` + Maestro（`~/.maestro/bin`），留给下一轮。

⚠️ 记成「已确认症状 / 未定位成因」，⛔ 不要写成「已修」或「不影响」——
第一轮交接件里那条「已定位是 X」其实只是推断，本仓吃过这个亏。


---

## T10 🟡 同一套 `/api/mobile/{factoryId}/*` 里并存两种分页口径 —— 目前**潜伏**，不是活缺陷

核对「已审批的盘点单里 0 个品项」（T7）时撞见的：我按全仓通用写法请求
`stocktakes?page=1&size=20`，拿到 `content: []` 而 `totalElements: 9`。

不是缺陷，是**我请求错了** —— 但错得很自然，因为这个接口是 **0 起算**的：

| 接口 | `page=0` | `page=1` | 口径 |
|---|---|---|---|
| `stocktakes` | 9 条（offset 0） | **0 条**（offset 20） | **0 起算** |
| `material-requisitions` | 5 条（offset 0） | 4 条（offset 5，尾页） | **0 起算** |
| `sales/orders` | 无 data | 20 条（首页） | **1 起算** |

RN 侧的调用点分布：

```
page: 1   97 处
page: 0    6 处   ← shipments ×3 · material-requisitions · sample-requests · stocktakes
```

⇒ **97 : 6**。那 6 处是各自试出来的；`WHStocktakeListScreen:73` 就老老实实写着 `page: 0`，
所以**这一屏目前是对的**，用户看到的 4 条盘点记录是真的。

▎危险在于下一个人：按 97 处的通用写法给一个 0 起算的接口传 `page: 1`，
▎拿到的是**第二页**；数据一少，第二页就是空的 —— 而**空列表和「没有数据」长得一模一样**，
▎不报错、不红、没有任何提示。

⇒ 记成**潜伏**（当前无用户可见后果），⛔ 不写成「已修复」也不写成「有 bug」。
真正的修法是把分页口径收敛成一个，或者让 0 起算的那几个接口在收到 `page>=1` 且
`totalElements>0` 而 `content` 为空时显式报错，⛔ 不要逐屏记住「这个要传 0」。

⚠️ 顺带：T7「已审批的盘点单 0 个品项」**仍未核实** —— 这一轮只查清了分页，
没查明细为什么是空的。别把这两件事混成一件。


---

# 第三轮追加：**真机**上跑了一次（这是前两轮唯一系统性偏乐观的那一类）

设备：小米 M2102K1AC / Android 14，已装 `com.cretas.foodtrace`
`versionName 1.0.4 · versionCode 15`（与 OTA 的 `runtimeVersion 1.0.4` 对得上）。
做法：`adb logcat -c` → 强停 → 冷启动 → 读 logcat。**只读，没有装任何东西。**

## D1 🔴 推送在**真机上**也从来没注册过 —— 而代码注释把它写成「只是 Web 不行」

冷启动 logcat：

```
E/ReactNativeJS: [PushNotification] 未找到 EAS Project ID
W/ReactNativeJS: [usePushNotifications] 无法获取 Push Token，跳过设备注册
```

`pushNotificationService.ts:194` 读的是 `Constants.expoConfig?.extra?.eas?.projectId`，
而 `app.json` 里 `extra` 只有 `{"router": {}}` —— **没有 `eas.projectId`**。
所以它在**任何平台**上都取不到，不只是 Web。

⚠️ 而同一个文件 `:108-111` 的注释把这件事框成了 Web 专属：

> APP-WEB-007 (2026-08-02): Expo 的原生推送在 **Web 上**不可用 —— 没有 EAS projectId 概念，
> 于是 12 个角色每次登录都会走完…稳定产出「未找到 EAS Project ID」…

▎照这段注释读，下一个人会以为**真机是好的**。真机日志说不是。
▎`AppNavigator` 里 `autoRegisterOnLogin: true`，仓里还有三份推送文档 ——
▎这是一个**打算要用**的功能，而它在生产包上一次都没注册成功过。

⇒ 与「描述过期比没有描述更糟」同族：注释描述的是写它那天的**意图**，不是今天的行为。
**只有真机能产出这条读数** —— Web 上这两条同样会打，但会被那段注释解释成「意料之中」。

## D2 ✅ 顺带把 OTA 的「已送达」往前推了一格（但仍**没有**证到底）

同一次冷启动：

```
Updates state change: StartStartup  → EndStartup   (isUpdateAvailable=false)
Updates state change: Check         → CheckCompleteUnavailable
```

⇒ 设备**确实联到了 OTA 服务器**并被告知「没有更新」。而服务器上最新的一份就是我推的
`ts=1787898056914` ⇒ 一致的读法是**这台设备已经拿到了它**（它在我推送后 13 分钟轮询过）。

⚠️ 但**我没有证到「正在跑的就是那一份」**：
- 想用「屏上有没有我的修复」来证 —— 点 `成品库存` 那一下没跳转，`uiautomator dump`
  在 MIUI 上产出空文件，两条路都断了，**没有继续硬试**
- app 私有目录读不到（release 包不能 `run-as`）

⇒ 记成「**联到了、被告知没有更新**」，⛔ 不写成「已确认设备跑的是新 bundle」。

## D3 ✅ 运行中的 JS 解析到的是生产地址

```
I/ReactNativeJS: '[DEBUG-CONFIG] REACT_APP_API_URL from @env:', '"https://www.cretaceousfuture.com"'
```

值得单独记，因为 `.env.production` 顶部专门写过一条事故：
`ENVFILE` 对不上时 `@env` 会全空、`API_BASE_URL` 兜底到 `http://10.0.2.2:10010`（模拟器回环），
真机上就是一句英文 Network Error。**这次真机上没有踩到**。

⚠️ 并且我核过我推的那份 OTA bundle：`www.cretaceousfuture.com` 出现 1 次、
`10.0.2.2:10010` 出现 1 次（那是 `config.ts` 里的兜底常量），
与**上一份已知可用的 bundle 形状完全一致** ⇒ 我没有推出一个地址被清空的包。

## ⚠️ 真机这一轮**没有**做到的

那三个「web 只警告、真机会抛」的屏（`KPIReport` / `PurchaseOrderCreate` / `WHSettings`）
**一个都没打开** —— 驱动 UI 那条路断在 uiautomator 上，我没有继续硬试。
⇒ 「冷启动没看到 `Text strings must be rendered`」**不能**读成「那三屏没问题」，
   它只说明**启动路径上**没有。这一条仍然挂账。

---

## 附：那道红**不是**用户侧故障 —— 实测生产上的 AI 会话是好的

`java-full-gate` 红在 `ToolExecutionTest`（Java → Python `chatWithTools` 那条路，HTTP 500）。
为了判断「用户是不是也用不了 AI」，直接打了一次**App 侧**的入口：

```
POST /api/mobile/F006/conversation/start   {"userInput":"查一下成品库存"}
→ HTTP 200
→ "您想查询的成品库存是指哪方面的信息？请选择：
   A. 查询成品（菜品）的食材库存情况 …"
   candidates: RESTAURANT_INGREDIENT_STOCK(0.4) / RESTAURANT_DISH_COST_QUERY …
```

⇒ **生产上的 AI 会话正常**，会做澄清、会给候选意图。
那条 CI 红是**另一条路径**（或自建 runner 上的另一个 Python 实例），
⛔ 不能读成「线上 AI 挂了」。

⚠️ **仪器自己又错了一次（本轮第 6 次，同一族第 3 次）**：
第一次打这个接口拿到 `400 请求格式不正确` —— 不是接口的问题，
是我在 shell 里内联中文 JSON，body 的编码被吃掉了。
换成「先用 Python 以 UTF-8 落盘、再 `curl --data-binary @file`」立刻 200。

▎同一族的另外两次：`document.body.innerText` 串屏（第二轮）、
▎commit message 里的反引号被 shell 当成命令替换吃掉（第三轮两次）。
▎**共同形状：内容要经过 shell 或某一层解释器时，先落盘，别内联。**
（本仓 `measurement-and-wiring` 硬约束 6 说的就是这件事，我照犯了。）


---

# 第三轮部署记录（合并 + OTA + **真机送达**）

## 合并

PR **#293** 合入 main `ba1817a8f1`。合并前 RN 子树 hash 与我的分支完全相同（`40dba656…`）。

⚠️ **是在 `java-full-gate` 红的情况下合的**，理由逐条写在 PR 的三条评论里，摘要：

1. **红不是这个 PR 的** —— 对照：无关分支 `codex/claude-vat-caliber-fix` 在**同一条测试**上
   同样形状红；我最后两次推送是**纯文档**，红仍在且从 1 条涨到 2 条。
2. **不是用户侧故障** —— 实测生产 App 侧 AI 入口 HTTP 200 且给出正常澄清（见上一节）。
3. **这道闸已经在挡真活** —— #294 作者自己写着它「连挡了四个 PR」。
4. ⛔ **没有动基线** —— 闸对别人依然有效；这里只留一次有据可查的越过。

## OTA

```
./scripts/ota/push-bundle.sh production android     PUSH_EXIT=0
✓ bundle live: rv=1.0.4 channel=production ts=1787911909437
manifest 取回: 指向新 ts=1787911909437, 不含上一份 1787898056914
```

**判据：新旧两份 bundle 各 grep 一次**（Hermes 不以 UTF-8 存中文，所以标记取 ASCII）：

| marker | 第二轮(旧) | **第三轮(正在下发)** |
|---|---:|---:|
| `app-error-boundary` | 0 | **2** |
| `missingHireDate` | 0 | **1** |
| `scoreDetailsRecorded` | 1 | 1 |
| `deliveredQuantity` | 1 | 1 |
| `UNDER_RECEIVE` | 1 | 1 |

前两个是这一轮新增的，**旧的里面一个都没有**；后三个是既有的，两侧相同
⇒ 标记确实能区分，⛔ 不是「两边都长这样」。

## 🔴 真机送达 —— 这一次是**测**出来的，不是推断

同一台小米真机（`com.cretas.foodtrace 1.0.4 / versionCode 15`），冷启动 logcat：

| | 推送**之前**那次冷启动 | 推送**之后**这次冷启动 |
|---|---|---|
| updates 状态 | `Check → **CheckCompleteUnavailable**` | `Check → **CheckCompleteAvailable** (isUpdateAvailable=true)` |
| 签名 | — | `Manifest code signing signature verified successfully` |
| 下载 | — | `Download → **DownloadComplete**`，`isUpdatePending=true` |
| 日志里出现我的 ts | — | `1787911909437` **×4** |

▎前后两次的差别只有「我推了一份」这一件事 ⇒ 这是一个**带对照的送达读数**，
▎不是「manifest 指向新的所以应该送到了」那种推断。

⚠️ 仍然没有证到的那一格：`isUpdatePending=true` 意味着**下次重启才生效**，
所以「设备正在跑的就是这一份」目前仍未观测到 —— ⛔ 不写成「已生效」。

**回滚**：`./scripts/ota/rollback.sh 1.0.4 production 1787911909437`
