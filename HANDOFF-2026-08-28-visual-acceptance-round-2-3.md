# 交接：逐屏视觉验收 第二 / 三轮（RN App）→ 第四轮

**日期**：2026-08-28
**租户**：F006「六膳门食品科技」（测试租户）+ MOCK_REST（唯一活跃餐饮租户）
**产出**：274 屏 / 520 张图 / 12 个角色 · PR **#287** 已合并并 OTA 推上生产 · 走查工具入库

台账（含全部发现、正面样板、仪器盲区、部署判据）：
`docs/visual-acceptance/2026-08-28-rn-app-444-screens.md`

---

## 一、这一轮做了什么

### 方法

App 没有 URL 路由，不能照搬第一轮 web-admin 的 `goto(route)`。做法：

```
expo start --web  (同一份 RN 代码 / 同一个后端)
  → AppNavigator 上的 navigationRef (只在 __DEV__ 暴露到 globalThis)
  → 登录一个角色, 从**运行中的 navigator** 读它自己报的 routeNames
  → 广度优先跳过去, 每屏截图 + 存「可见文本」
```

工具在 `frontend/CretasFoodTrace/tests/vshot/`，`npm i -D --no-save @playwright/test` 后即可跑。

### 覆盖

| | |
|---|---|
| 走到并逐屏看过 | **274 屏 / 520 张图 / 12 个角色** |
| 没走到（逐块写明原因，见台账「二、覆盖」） | 仓储主管白屏（已修）· 平台侧与餐饮侧两整棵树 · dispatcher 等角色无账号 |
| 崩溃 50 次的分类 | 46 次是「无参数进详情屏」（**仪器造成的，不计缺陷**）· 真正用户走得到的 2 个 |

### 修了并**在屏上看到了**

| | 改前 | 改后 |
|---|---|---|
| 仓储主管 | 登录后永久白屏，走查 **0 屏** | 正常首页，走查 **51 屏** |
| 销售分析 | `PAGEERROR: reading 'totalSales'` → 整屏白 | 「这段时间没有销售数据 / 当前时间范围内暂无销售数据」 |
| 采购异常 | `Unable to parse color: #66622` → 整屏白（线上 31 行 **100%** 命中） | 31 行正常，标签「超收 / 少收」与后端枚举一致 |
| 成品库存 | 品名是 UUID · 总量空白 · 进度条恒 0 · 无保质期 | 品名 `SOP-…-黄油鸡-成品800g` · `10 盒` · `有效期: 2027-02-21` |
| 添加员工/部门/白名单 | 字段名全是 `staff.add.fields.username` 这类 key | 全中文 |
| 考勤统计 | 接口失败 ⇒ 假装一家 45 人的公司 | 失败态 + 取不到显示「—」 |
| HR/质检「我的」 | `45/5/96%`、`12/11/7.5` 写死 | 「—」 |

新增闸 `navigatorRouteNamesAreUnique`（把缺陷放回去当场变红）。
OTA 已推：`rv=1.0.4 production ts=1787898056914`，新旧 bundle 各 grep 一次证明标记能区分。

---

## 二、还没做的（按业务口径排序）

### 🔴 第一：后端「没数据就编一个好看的数」——**这一条在 Java 侧，两端都吃它**

`ProductionReportServiceImpl.getKpiMetrics` `:1350-1364`：

```java
outputCompletionRate = 有计划且有产出 ? 实算 : new BigDecimal("85");
fpy                  = totalInspections > 0 ? 实算 : new BigDecimal("96");
otif                 = totalShipments   > 0 ? 实算 : new BigDecimal("95");
```

一家一条数据都没有的工厂会读到「产出完成率 85% / 一次合格率 96% / 准时交付 95%」。
`mtbf=168` / `mttr=2` 也是字面常量。这些还进了 `calculateOverallScore`。

**同一个方法往下三行就是正确写法**：`// 无产出…时返 0 (诚实空, 不造假)`。

实测对照：`KPIReport` 屏说「OTIF 95%」，而**同一个 tab 里**的 `OnTimeDeliveryReport` 说
「暂无OTIF数据 · 请确认工厂已配置交付监控」。说没有数据的那个是对的。

⇒ 需要一条后端线 + 一次后端发布。**web-admin 也在读同一批 KPI**，第一轮没抓到它。

### 🔴 第二：车间「任务执行」三步引导整套是写死的，而入口是真的

`WSHomeScreen:443/:625` 拿**真实批次**跳进 `TaskGuide`，之后三个屏
（`TaskGuide` / `Step2` / `Step3`）`await|apiClient|useQuery` 出现 **0 次**：

- 车间 A区车间 · 1号生产线 · 切片工位 · 设备 切片机A (EQ-001)
- 产品「带鱼片 · 目标80kg」（F006 做的是黄油鸡）
- 人员 王建国/李明辉/刘晓峰 EMP-001/002/003（F006 的人是永珍/徐师傅/魏振江/莫云）
- 上次维护 2025-12-20（九个月前）
- 最后那个「**确认开始生产**」按钮 **什么都不写**，只是 `navigate('BatchDetail')`

同族还有 `WorkerDetail` / `WorkerAssign` / `BatchStart` / `BatchStage` / `BatchComplete` /
`AttendanceHistory`，以及仓储侧四屏（见下）。

⚠️ `BatchStage` 最需要注意：它把写死的数标注成「**自动采集数据（只读）· 数据来源：切片机A传感器**」——
假数据带了来源署名。

### 🔴 第三：仓储侧四屏虚构，其中一个是**召回管理**

`WHAlertList`（`aiAlerts` 三条写死，而**同屏真接口返回 0**，两者并排在同一个「共 3 条预警」下）·
`WHAlertHandle`（过期时间 2025-12-29「3天后」）· `WHRecallManage`（进行中的召回 RC-20251226-001，
「已发货 100kg → 3家客户 通知中」）· `WHConversionAnalysis`（转换率 92.3%、带鱼片、虾仁）。

召回是对外、不可逆的动作。仓管拿这一屏汇报，后果是真的。

### 🔴 第四：两整棵导航树没有账号进得去

| 账号 | 后端返回的 role | AppNavigator 的分支 | 落点 |
|---|---|---|---|
| `platform_admin` | `super_admin` | 写的是 `platform_admin` | 锁定页 |
| `mock_owner` / `mock_purchase` | `restaurant_owner` / `restaurant_purchaser` | 无 | 锁定页 |

⇒ `MainNavigator` 子树 ≈150 条注册、`FactoryAdminNavigator` 子树 ≈122 条注册，现有账号一条都到不了。
`roleMapping.ts:96` 把 `developer` 映射成 `platform_admin`，**没有映射 `super_admin`**。

⛔ 修法不是逐个补角色字符串 —— 先定「谁进哪棵树」，再让分派表只有一处。

### 🟡 第五：顶层 ErrorBoundary

`App.tsx` 里 `<AppNavigator/>` 上面没有任何错误边界（全仓唯一的在 `MarkdownRenderer` 内部）。
本轮 50 次崩溃全是同一个长相：整棵树卸载 → 一片白 → 车间里只能杀进程重开。

### 🟡 第六：剩余 519 个缺失译文键

本轮补了 45 个（`hr` 39 / `smartbi` 3 / 考勤失败态 2×2）。扫描脚本口径见台账 H2。
⇒ 做成闸，⛔ 不要盲改。

### 🟡 第七：数字口径（与第一轮「第二优先」同一件事，App 侧同样成立）

- 生产批次数：`2 / 8 / 0 / 5 / 21` 五个值分布在五屏；后端本身就有
  `totalBatches / todayBatches / allActiveBatches / inProgressBatches / todayInProgressBatches` 五个口径
- 合格率：`ReportDashboard` 68.8%（抽样率平均）vs `QualityReport` 46.2%（记录数）——
  **对的那一屏就在隔壁**，它用了后端专为移动端加的 `breakdown`
- 在岗人数：`0 / 8 / 22` 三个值；`WSWorkers` 说「未打卡」、`PersonnelReport` 说「缺勤 22」、
  `ClockIn` 说「缺勤 0」——「未打卡」是唯一站得住的说法
- 成本：`ReportDashboard` ¥0 vs `CostReport` ¥755.90，而给前者供数的接口自己返回 605.91

⛔ 逐屏改会造出第六个口径。

---

## 三、下一轮该怎么走（方法上的）

第二轮**只导航、不点按钮**。剩下的正经工作量在这里：

1. **从列表点进详情屏** —— 本轮 46 次崩溃全是「空参数进详情」，那不是缺陷；
   详情屏真正长什么样，这一轮**一屏都没看到**。
2. **把接口打成 404 再看** —— 第一轮「坑③」在 RN 侧还没做（web-admin 那套
   `vshot-force-fail` 的 RN 版没写）。接口通的时候，失败分支根本没被走到。
3. **真机/模拟器跑一遍** —— 「文本节点直接放在 `<View>` 里」在 web 只是 warning
   （`KPIReport` 实测），**在真机上是抛异常**。这一轮对这一类是**偏乐观**的。
4. **相机 / 扫码 / NFC / 定位那几屏** —— 本轮停在权限提示，等于没验收。

---

## 四、三个别踩的坑（本轮实测）

### ① 「注册了」≠「有入口」——我自己差点栽

走查用路由名跳转，**绕过了按钮上的守卫**。`ServerConnectivityTest` 注册在操作员栈里，
我一度要写「操作员能看到开发者工具」。实际 `ProfileScreen:421` 的守卫是 `{!isOperator && …}`，
对操作员是藏着的 —— 但**财务/销售/采购/查看员看得到**（共用 `ProfileStackNavigator`）。
⇒ 每条「用户能看到 X」的结论，回到那一屏的**入口条件**上核一次。

### ② 改了源码不重启 metro，拿到的是旧 bundle

仓储主管的重复路由删掉之后重跑，**仍然白屏**。不是没修好，是 `CI=1` 下 metro 关掉了文件监听。
重启（`--clear`）之后才正常。**「合并了」和「屏上看到了」之间还隔着一个 bundle。**

### ③ `.codex/rules/test-credentials.md` 里的 F001 账号全失效

`dispatcher1` / `factory_admin1` / `warehouse_mgr1` 实测**全部 401**。
`/api/platform/factories` 现在只剩 **F006 / LIUSHANMEN / MOCK_REST**，F001 租户已被清理。
⇒ 那份凭证清单需要订正；`dispatcher` / `warehouse_worker` / `viewer` 等角色目前**没有账号可测**。

⚠️ 顺带：`/api/platform/factories?page=1&size=200` 返回 `[]`，不带分页参数才返回 3 条。
（这是我探测时撞见的，不是屏上的发现，未深查。）

---

## 五、产物在哪

| | |
|---|---|
| 台账（发现 / 正面样板 / 仪器盲区 / 部署判据） | `docs/visual-acceptance/2026-08-28-rn-app-444-screens.md` |
| 走查工具 | `frontend/CretasFoodTrace/tests/vshot/` |
| 新增闸 | `frontend/CretasFoodTrace/src/__tests__/gates/navigatorRouteNamesAreUnique.test.ts` |
| PR | #287（已合并 `626bda9550`） |
| OTA | `rv=1.0.4 channel=production ts=1787898056914`；回滚 `./scripts/ota/rollback.sh 1.0.4 production 1787898056914` |
| 截图（第一轮 / 第二轮） | 会话临时目录 `vshots-r1/` `vshots-r2/`，**未入库** |

---

## 六、一句话交代这一轮

▎第一轮在 web-admin 上总结的主线是「**算不出来 / 查不到，就编一个数**」。
▎App 这一端同样成立，而且多了一种更重的形态：**编出来的整屏，配着一个真实的批次号或真实的入口。**

车间主任点一个**真实在产的批次**进「任务执行」，被指引到一个不存在的工位、
去启动一台不存在的设备、确认三个不存在的人到齐，然后按下一个**不开工**的「确认开始生产」。

而正确写法就在同一个 App 里，而且不止一处：
`MyWage`（「这个月的工资单还没做出来。工资单做出来之前，这里不会有金额。」）·
`WHTempMonitor`（「温控设备未接入…下方的告警记录来自真实设备告警接口，可正常查看。」）·
`QITrend`（「没有质检记录的日期已跳过，不按 0% 计入。」）·
`WHInventoryCheck`（「审批通过后库存才会调整。您无法直接修改库存数量。」）

⇒ **这不是「该怎么做」的分歧，是同一个应用里两种做法并存。**


---
---

# 追加：第三轮（同日）—— 把第二轮欠的方法债还掉

第二轮**只导航不点按钮**，所以详情屏一屏都没真看过、失败分支一次都没走过。这一轮补上。

## 做了什么

| | |
|---|---|
| 详情屏 | 从列表**点进去**：9 个角色 · 290 条路由 · **59 次进入 / 20 个不同详情屏 / 0 崩溃** |
| 失败态 | 写了 RN 版 `force-fail`（把 `/api/` 打成 404），补上第一轮「坑③」 |
| 只读 | 从**承诺**改成**量出来的**：记录每一个非 GET 请求 |

⇒ 59 次 0 崩溃**反证**第二轮那 46 次崩溃是仪器造成的（无参数进入），不是产品缺陷。

## 新修的（都在 PR #293）

| # | 改了什么 | 屏上看到的 |
|---|---|---|
| G2 | `App.tsx` 补顶层 ErrorBoundary | 白屏 → 「这一页打不开 / …/ 再试一次」；按下回到「经营总览」 |
| T1 | 缺料减掉已发货；已取消恒 0 | **12/12 张已完成订单在报「缺 = 全量」**（后端改动，待发布后核对） |
| T2 | 销售单详情产品名读 `productName` | `eb0aa47b-…` → `SOP-20260817-01-黄油鸡-成品800g` |
| T3 | 本月入职只认 `hireDate` | 「1 人 · 入职日期 2026-08-19」（其实是**建账号**的日子）→ 「0 人 · 有 22 名员工没有填入职日期…」 |
| T4 | 质检记录没存过评分显示「—」 | 「A · 0 分 · 合格」→「A · —」（同屏另两条仍是 100分/70分） |
| — | 考勤统计失败时把指标区一起藏掉 | **第二轮的半修**：横幅有了、底下照旧「0% 平均出勤率 / 0 迟到 / 0 早退」 |

🔴 最后那一条值得单独记：**它是把接口打成 404 才看出来的**。
第二轮改完只在接口通的情况下重新截图，看到的是正常数据，失败分支根本没执行。
**半修比不修更容易过关，因为最显眼的那一半（横幅）已经对了。**

## 第三轮新增的发现（未修，见台账 T5–T9）

- 三个详情屏把「人」显示成数字 id（`审批人 1309` / `申请人 1309 · 审批人 1551`）
- 退货详情：交易对手与原始订单都是 UUID
- **已审批**的盘点单里 0 个品项
- 批次详情状态显示「未知」（列表里是「生产中」）
- 操作记录里多条 `0kg` 的「确认入库 · 成功」

## 🔴 一条关于「在同一个租户上并行干活」的教训

第一次跑完点击走查，对比业务单据数读到 `销售订单 45 → 51`。
查下去：6 张新单 remark 全是「E2E-ACC 2026-08-28 **并发超发验收** #N」，
创建时间 15:15:43 与 15:17:22–23 **亚秒级成串** —— 是**另一条 session 的 E2E**。

▎但当时那个读数**两个方向都证明不了**：两条线同时在动同一个租户，计数差是两者之和。

⇒ 给走查加上「记录每一个非 GET 请求」，重跑仓储角色（唯一点进过 `*Create` 屏的那个）：
非 GET 共 5 条，除登录外 4 条全是 `POST /list-summary/internalTransfer`，
而本仓 `DemoReadOnlyInterceptor.java:60` 自己把它标注为「读性质 POST」⇒ **零业务写入**。

**判据**：在共享租户上做「我没有写」的声明，**计数对比不够**，必须记请求。

## 仪器坏掉的次数累计到 5 次

第二轮 3 次（注释块被数成活路由 / 相邻两屏文本串了 / 滚了但画面不动），
第三轮再 2 次：

| # | 坏法 | 长相 |
|---|---|---|
| 4 | 候选按「所有元素」下标记，点击时索引的是「只含可点元素」的另一个数组 | **点的根本不是选中的那个**，日志照常打「点了 X」 |
| 5 | 拿动作词过滤**整张卡片** | 卡片的状态 chip 自己就含「已完成/已发货」⇒ 列表行被自己的状态字挡掉，14 条路由只点进 2 个详情屏 |

⇒ 第 5 条的判据订正：打在「**这个元素是不是一个动作按钮**」（短文本）上，
不是「这段文字里有没有出现过这个词」。

⚠️ 还有一次是**判据**错而不是行为错：断言「错误边界接住之后 `navAlive()` 仍为 true」——
边界一旦接住，`NavigationContainer` 就在被替换掉的那棵子树里，`isReady()` 本来就该 false。
要证明的是「**能回来**」。

## 第四轮该做什么

1. **真机 / 模拟器跑一遍** —— 仍然没做。「文本节点直接放在 `<View>` 里」在 web 只是 warning
   （`KPIReport` 实测），真机会抛。这两轮对这一类**都是偏乐观的**。
   本机有 Android SDK + AVD `Medium_Phone` + Maestro（`~/.maestro/bin`）。
2. **后端那三个编出来的常量（85 / 96 / 95）** —— 仍未动，且 **web-admin 也在读同一批 KPI**。
3. **车间 / 仓储的虚构屏** —— `TaskGuide` 三步、`WHAlertList`/`WHAlertHandle`/`WHRecallManage`/
   `WHConversionAnalysis`。⚠️ 其中**召回管理**是对外不可逆动作。
4. **G1 两整棵树没有账号进得去** —— 需要先定「谁进哪棵树」。
5. **剩余 519 个缺失译文键** —— 棘轮已建（`i18nKeysResolve`），可以按屏逐块降。
6. **表单的提交路径** —— 两轮都只到「打开表单」为止，校验/提交/审批动作全没走。

---

## 第三轮收尾：合并 / OTA / **真机送达**（2026-08-28 夜）

| 步骤 | 结果 |
|---|---|
| PR **#293** | 合入 main `ba1817a8f1`（合并前 RN 子树 hash 与分支一致 `40dba656…`） |
| OTA | `✓ bundle live: rv=1.0.4 channel=production ts=1787911909437`，manifest 只含新 ts |
| 制品判据 | 新旧两份 bundle 各 grep：`app-error-boundary` **0→2**、`missingHireDate` **0→1**；`scoreDetailsRecorded`/`deliveredQuantity`/`UNDER_RECEIVE` 两侧都是 1（既有的，正确地不区分） |
| **真机** | 冷启动：`CheckCompleteAvailable(isUpdateAvailable=true)` → `Download` → `DownloadComplete`，`isUpdatePending=true`，日志里我的 ts ×4 |

▎**这一格是第二轮欠的账**。第二轮只能说「已发布 ≠ 已送达」；
▎这一轮有了**前后对照**：推送前同一台机器同样的冷启动读到的是 `CheckCompleteUnavailable`。
▎两次之间唯一变化是「我推了一份」⇒ 送达是**测**出来的，不是推断的。

✅ **那一格当场闭掉了**：再冷启一次，`StartStartup` 的 `isUpdatePending` 从 true 变回
**false**（pending 被消费 ⇒ 这次启动加载了它），而服务器上只有我这一份却回答
`CheckCompleteUnavailable` ⇒ **设备正在跑的就是它**。
⚠️ `CheckCompleteUnavailable` 自己不是判据 —— 推送前也是这个值。判据是三次之间的
**转移**：Unavailable → Available/pending=true → Unavailable/pending=false。

## 🔴 第三轮的另一半：Java 合了但没上线，我差点按 OTA 的回执收工

`prod jar mtime = 16:11`，而 PR #293 合并于 `18:10` —— **销售单缺料那个后端修复根本没上 prod**。
OTA 的每一项判据都只覆盖 RN 侧，所以它们**全绿，且对这半边完全失明**。

已补做（详情见台账「第三轮的另一半」节）：

- 部署第 1 次被并发推送拦下（`deploy_mode=none` / `DEPLOY_EXIT=1`）；
  第 2 次脚本自动前进到新 main 重跑，`DEPLOY_EXIT=0` + `RELEASE_FINAL_STATUS` 恰好 1 次 +
  `BACKEND_HEALTH=pass`；jar mtime `18:40:14` < 进程 `lstart 18:40:42`。
- **行为验证（该改动无字符串标记可 grep）**：13 张 `COMPLETED` 订单 `shortageQty` 全 0
  （含 `SO-20260828-0003`，上线前报 10）；**阳性对照**：38 行真的没着落的照旧报缺，
  `SO-20260828-0013` 部分发货报 **2**（`4-2-0`）—— 排除「一律返回 0」。
- ⚠️ 这次部署捎带上线了别的 session 已合未部署的三个后端 commit。那是 `prod = main` 的
  定义而不是 scope creep，但**必须写下来**，否则出问题时会去查错的 commit。

⚠️ **合并时 `java-full-gate` 是红的**，理由写在 PR #293 的三条评论 + 台账「部署记录」节：
红不是这个 PR 的（无关分支同一条测试同样形状红；我最后两次推送是纯文档而红从 1 涨到 2）、
不是用户侧故障（生产 App 侧 AI 入口实测 HTTP 200）、⛔ 没有动基线。
**下一轮开工前先看这道闸有没有人修** —— 它当时已在挡至少四个 PR。

**回滚**：`./scripts/ota/rollback.sh 1.0.4 production 1787911909437`
