# 2026-08-26 交接：黑湖小工单竞品拆解 + 十条「最后一公里」

> **这份文件只写跨 PR 的东西。** 每个 PR 各自的验证读数、变异对照、已知缺口，
> 都在它自己的描述里，不重复。
> ⚠️ 查 PR 一律带 `-R cretasfuture-ai/cretas` —— `gh` 默认解析到 fork `Stevenjxie/cretas`，
> 同一个号在两边是**不同对象**（实测 `gh pr view 83` 会返回 fork 里一个已合并的 Flyway PR）。

---

## 一、结论：我们缺的不是功能，是接线

起点是拆一个竞品（黑湖小工单）。第一版分析写了十几条「我们没有 X」。
**逐条重核之后，推翻了大半：**

| 竞品卖点 | 他们 | 我们（真名） |
|---|---|---|
| 消息推送 | 微信模板消息一套 | **三套**：Expo App 推送（15 个触发点）+ 企微/钉钉/邮件/短信/站内信 `NotifySenderRegistry` + 告警派发 |
| 计件工资 | 只有计件（PDF P70 白纸黑字「计时一般不上系统」） | `WageMode` = PIECE_RATE / **HOURLY** / **MIXED**，三阶梯累进，18 个端点走完审批到支付 |
| 二维码标签 | 固定模板 | 15 个打印端点 + **可视化模板编辑器** + **AI 生成模板** + `Label` 全生命周期含扫码回查 |
| 委外 | 外协工单（发出方向） | `TOLL_PROCESSING`/`CUSTOMER_SUPPLIED`/`CUSTOMER_OWNED`（**来料方向**）+ DB CHECK + 发货 fail-closed |
| 超报限制 | 超 1.2 倍弹 toast，一档 | **三档**（软提示／可覆盖+留痕／硬阻断）+ 跨单位防呆 |
| 车间大屏 | 客户自己买 65/75 寸挂墙 | 2026-04-08 就做完了 |

### 为什么第一版会错得这么整齐

**我用中文概念名去找英文领域模型名。**

```
grep 超报    → 0 命中     真名 YieldLimitsDTO
grep 委外    → 0 命中     真名 TOLL_PROCESSING（且只有蛇形 toll_process 能开门，驼峰 0 命中）
grep 推送    → 0 命中     真名 NotifySender / NotifyChannel / PushNotificationService
```

⇒ **没通电的能力，在代码里长得和不存在一模一样 —— 除非你按它的真名去找。**

判断能力有无时，最少要覆盖六类词：中文业务词 / 英文直译 / **领域模型名** /
行业缩写 / 相邻概念 / **数据层名字（表名·DTO·枚举值·权限码字符串）**，
并且**至少查一次 DTO / entity / 权限码常量**（`YieldLimitsDTO` 就躺在 `dto/` 里，
报工屏文件里一个中文「超报」都没有）。

### 判定要三态，不要二态

「有 / 没有」这个二分法本身就是错误的来源：

| 判定 | 修法 |
|---|---|
| `A 不存在` | 要**造** |
| `B 存在但没接上` | 要**接线**。造新的 = 重复建设 |
| `C 已接上` | 断言错了，撤回 |
| `C- 接上了但用户看不见` | 接渲染，不碰后端 |

---

## 一·补、🔴 先看这组数字，它改变本文件里的优先级排序

**2026-08-26 在 `cretas_prod_db` 上实测（只读，带阳性对照）：**

```
production_reports           72 行
production_batches           61
production_plans             43
labels                       15
──────────────────────────────────
payroll_records               0
piece_rate_rules              0
wage_calculation              0
wage_policy                   0
worker_daily_efficiency       0
restaurant_piecework_configs  0
aps_efficiency_history        0
```

### 结论一：整个工资子系统从来没在生产上用过

7 张表全 0 行。`piece_rate_rules = 0` 意味着**没有任何一个工序配过计件单价**。

⛔ **本文件此前把「『我的工资』屏」列为「全场 ROI 最高」—— 那句是错的。**
理由曾是「引擎已有 18 个端点，只缺一个只读视图」。
**引擎在代码里有，但从未被配置、从未被使用。**
⇒ 把那个屏做出来，工人打开看到的是**一个空屏**（诚实的空态，但没有任何内容）。

**真正的前置不是写代码，是运营动作**：有人去配计件单价、跑一次工资单。
在那之前，#85（我的工资屏）和 #99（工资引擎收敛）修的都是**一条从未跑过的路径**。
⇒ 这两个 PR 不该排在前面。

### 结论二：`vflag` 那条待裁定可以关掉

`payroll_records` 0 行 ⇒ **不存在 `PENDING + vflag=CREATED` 的记录**，
#99 的重算端点今天不可能改坏任何凭证。
⇒ 降级为「**加断言钉住，不阻塞合并**」。代码路径仍然可达，所以断言要加。

### 结论三：工厂侧整体使用量很小

`labels` 15 行、`production_reports` 72 行。
⇒ **排优先级时要问一句「这条路径一天跑几次」** ——
修一个每天跑几千次的路径和修一个总共 72 行数据的路径，价值差很远。

⚠️ **只量了 `cretas_prod_db`。餐饮那套在 `smartbi_prod_db`，未量。**
所以「使用量小」这个判断**只对工厂侧成立**，不要外推到餐饮。

### 怎么复现这组读数

```bash
ssh root@47.100.235.168
UNIT=cretas-backend-green.service          # ⚠️ 不是 cretas-backend, 蓝绿部署下名字不同
PID=$(systemctl show -p MainPID --value $UNIT)
eval "$(tr '\0' '\n' < /proc/$PID/environ | grep -E '^DB_PASSWORD=' | sed 's/^DB_PASSWORD=/export PGPASSWORD=/')"
psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db -c "<你的只读 SQL>"
```

⛔ 三个坑，都实测踩过：
1. **库名是 `cretas_prod_db` 不是 `cretas_db`**（配置里的默认值是后者）
2. **`.env.prod` 里没有独立的 `POSTGRES_DB`/`POSTGRES_USER`** ——
   `grep 'POSTGRES_DB='` 会匹配到 `FOOD_KB_POSTGRES_DB=` 的尾巴，给出假读数。凭证要从**活进程 environ** 读
3. **`ss -lntp | grep :10010` 返回空** —— 按端口反查 PID 在这台机器上不成立，要走 systemd unit 名

---

## 二、十条「最后一公里」的具体形状

本轮触到的每一条都长同一个样：**造好了，差最后一段**。

| 能力 | 差的那一段 | 处置 |
|---|---|---|
| 车间大屏 | **菜单里零命中** | PR #96 已接 |
| 标签打印 | **打出来的码扫不回来**（印 traceCode，查 labelCode） | PR #98 已修 |
| 工资引擎 18 个端点 | 工人看不到自己的工资 | PR #85 已做 |
| 两套工资算法 | 同一个人同一个月两个数 | PR #99 已收敛 |
| 超收容差后端可配 | 界面写死，改 DB 也没用 | PR #97 已收敛 |
| 采购超收有规则 | 默认 `0.00` 拦死一切，而界面说 30% | PR #100 已修 |
| 离线队列完整实现 | `enqueue()` **零业务调用方** | ⏸ 未动 |
| `PermissionGuard` 74 行 | **全仓 0 次使用** | ⏸ 未动 |
| 可配置检验项完整 | 种子数据只在 MySQL 迁移里，接上会把质检屏搞死 | ⏸ 未动 |
| 草稿完整持久化 | 零导航入口，「待同步」是假话 | ⏸ 未动 |

⚠️ **同一周同一形状出现两次**：`#87`（仓储位置）和 `#96`（车间大屏）都是
「做完了、部署了、RBAC 正确、**零菜单入口**，所以没人找得到」。
⇒ **做完功能没有一步是「确认用户能走到」。** 这是流程缺口不是偶发。

---

## 三、十个 PR 索引

| PR | 分支 | 内容 |
|---|---|---|
| #82 | `codex/claude-fake-data-purge` | 四处假数据（随机趋势／预填 93 分／盘点锚定／假成功文案） |
| #83 | `codex/claude-ws-mock-data-purge` | 车间主管三个主列表页改读真实接口 + 删两个假屏 |
| #84 | `codex/claude-blind-stocktake` | 盘点改盲盘（提交后揭示并锁定）+ SP7 spec 重写 |
| #85 | `codex/claude-my-wage-screen` | 「我的工资」屏 + self-scoped 端点 + `hr:self_wage:view` |
| #96 | `codex/claude-dashboard-consolidate` | 车间大屏接菜单 + 修「空态同时渲染 6 张全 0 卡」 |
| #97 | `codex/claude-tolerance-converge` | 出成容差 61 处收敛 + 打通配置写入 |
| #98 | `codex/claude-label-scan-roundtrip` | 标签打印/扫码往返 + trace 端点跨租户 |
| #99 | `codex/claude-wage-engine-unify` | 两套工资引擎收敛 + 重算冻结（PAID/APPROVED 冻结） |
| #100 | `codex/claude-overreceive-rule` | 采购超收（默认不限制 + 工厂级可配） |
| #101 | `codex/claude-security-fixes` | mojibake 权限闸 / food-kb 跨租户读 / mockDemo 旁路 |

**⚠️ 合并顺序**：#97 ↔ #100 在 3 个文件重叠（`FactorySettingsDTO.java` 自动合，
2 个测试文件是两条分支各自在订正同一段错注释）。**后合的那条取 #100 那版更准**
（默认已是 `null` 不限制，不是 `0.00`）。

---

## 四、⛔ 待裁定（按急迫度）

| # | 事 | 为什么急 |
|---|---|---|
| ~~1~~ | ~~**生产上存不存在 `PENDING` + `vflag=CREATED` 的工资单**~~ | ✅ **已量，关掉**：`payroll_records` **0 行**（见「一·补」）⇒ 今天不可能发生。但 `VoucherServiceImpl.listUncreatedBusinessIds()` 的 `PAYROLL_RECORD` 分支**确实没有 status 过滤**（同 switch 里 `WASTAGE_RECORD` 有、`SALES_ORDER` 有），代码路径可达 ⇒ **加断言钉住，不阻塞合并** |
| 2 | **`tracked-secret-scan` 离永久变红只有 3 秒** | `timeout-minutes: 5`，而光 `actions/checkout@v4` 就 5m02s。它是**唯一所有 PR 都跑的闸**，一变红大家就开始忽略红色 |
| 3 | **8 个物流 GET 端点零权限注解** | `LogisticsResourceController`(4) + `LogisticsOrderImportController`(4)。#101 补了路由闸，**绕过路由直接打接口照样通** |
| 4 | **`good = actual` 兜底**（`WorkReportingServiceImpl.java:428-435, 462-464`） | 把「没人填不良品」写成「零不良」，**已进库不可追溯**。这是车间大屏刻意不放不良品率的原因 |
| 5 | **超收「打回」这个动作不存在** | 现在超限额是**整单拒收**（409，不生成任何单据、不通知任何人）。「超出的部分打回去」需要「部分收货 + 超出部分自动开异常单」，是新功能 |
| 6 | **409 文案指向一个不存在的页面** | 「系统设置 → 库存设置」在 web-admin 里零命中。补入口 or 改文案 |
| 7 | **容差闸里有一条恒真断言** | 它重新声明了自己的局部副本再断言（真函数模块私有导不出）。守的正好是「比率 vs 乘数差 30%」那个陷阱。一行修法：导出那两个纯函数 |
| 8 | **食品 KB 存量行 `factory_id` 回填口径** | 现在留 NULL ⇒ 对所有租户不可见（故意 fail-closed） |
| 9 | **那 22 条对 FACTORY 隐藏的菜单** | owner 8-22 说的是「**暂时**用不着」。那个「暂时」到期没有 |

### 三条 migration 都没在真库跑过

1. `V20261031_01__payroll_unified_wage_engine.sql`（#99）
2. `V20260825_01_food_kb_feedback_tenant_isolation.sql`（#101）
   ⚠️ 打 **`cretas_db`** 且这两张表没有 tracker ⇒ 要带 `--db-name` / `--migs-dir`
3. （#100 不加列，无 migration）

---

## 五、本轮的仪器教训（会再咬人的）

| 教训 | 长相 |
|---|---|
| **`gh` 默认指 fork** | `gh pr view 83` 返回另一个仓里一个已合并的 PR。⇒ 一律 `-R cretasfuture-ai/cretas` |
| **本地 `main` 与 `origin/main` 零共同祖先** | 仓库 8-23 压平重建（origin/main 75 commit，本地 main 8768）。「领先 8768」是假读数，**本地 main 对合入/部署已无意义** |
| **中文进了仪器 ⇒ 静默假读数**（一晚三次） | MockMvc 默认 ISO-8859-1 让阴性断言恒真／控制台 GBK 让 `grep` 数出 0／**一个 GBK 往返的 `'楼'`（= UTF-8 `'¥'`）让权限闸恒真** |
| **一个真实 `0x00` 让整份文件对 grep 隐形** | `grep` 报 `Binary file matches` 直接跳过。git 因只探前 8000 字节而侥幸没报（NUL 在 offset 14211） |
| **`mvn -Dtest` 是类名 glob 不是子串** | `*FooTest*` 匹配 0 个类**照样 BUILD SUCCESS**。跑完必须核对 `Tests run:` 数字 |
| **`vue-tsc -b` 一个 spec 都不查** | `tsconfig.app.json` include 只有 `src/**` 且 exclude spec；`tests/e2e/` 在 `src/` 外。全仓 spec 有 227 个类型错误没人管 |
| **管道/尾部 echo 替换退出码** | `cmd \| tail` 和 `cmd; echo` 一样吃掉真实 rc。有一次 `vitest --reporter=basic`（vitest 4 无此 reporter）启动就崩、零测试，而后台报 exit 0 |
| **蓝绿部署下进程名 ≠ 服务名** | 线上单元是 `cretas-backend-**green**`；按 `cretas-backend` 查 MainPID=0、`ss \| grep :10010` 也空 ⇒ 假读数 |
| **MSYS 吃冒号** | `git show origin/main:path` 静默返回空。要 `MSYS_NO_PATHCONV=1` |
| **`target/surefire-reports/` 有陈旧报告** | 从那里取失败名报出「新增 24 条失败」是假的，`mtime` 一比就露馅。权威读数是 maven 日志里**这一次**的汇总行 |
| **scratchpad 是并发共享的** | 有代理的 `pr-body.md` 被另一条 session 整份覆盖。写共享目录要带唯一名 |

### 断言问得太宽（一晚四次）

`isRowLocked` 在同一弹窗出现 3 次／中文断言恒真／文件级 grep 命中姊妹函数／
`maxReceivableQuantity` 变异后整份文件仍有 4 处。
⇒ **按函数体/元素切片再断言**，⛔ 不要整份文件 `toContain`。

### 变异不红的第 N 种成因

除了「断言在守空气」「变异没生效」「守着旧行为」，本轮新增两种：
- **断言重新声明了自己的副本再断言**（真实现模块私有导不出）⇒ 恒真式
- **变异同时把第二个缺陷也放回去了** ⇒ 红的是另一条断言，需要设计一条只隔离字面量的对照变异

---

## 六、素材

竞品拆解报告（活链接，含逐帧/逐页证据与三级置信度标注）：
https://claude.ai/code/artifact/7940e9d8-76c0-4569-a009-41afbc462df0

原始产出（280 帧逐帧读取 / 106 页 PDF 逐页 / 我方 64 屏逐字段 / 19 条断言重核）在
本轮 session 的 scratchpad `out/` 下，未入库。
