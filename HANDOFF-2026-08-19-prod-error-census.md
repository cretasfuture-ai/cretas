# prod 后端报错普查 —— 2026-08-19

**问题**：「后台有没有任何操作报错的行为，为什么会报错，这大概都是不是 bug（我们有测试人员一直在测试）」

**结论先说**：曾被撞过的数据库约束共 **26 个**，8-19 当天仍在发生的 **3 个**，全部是真 bug，
**全部已修**（commit `76ffe5fa72`）。另有 3 类历史高频（枚举缺失 / Metaspace OOM / SSE 转换器）
近 7 天 **0 次**，已经自愈或早已修好，**不要再去修它们**。

---

## ⚠️ 口径（先说这个，否则下面的数对不上）

### 我第一版的数字全部约 2×，已重量

日志目录有**两条流**：

| 流 | 文件 | 收什么 | 覆盖 |
|---|---|---|---|
| 全量流 | `cretas-backend*.log` / `*.tmp` / `*.gz`（非 error） | 全级别 | 06-16 → 08-19（有留存缺口）|
| error 流 | `cretas-backend-error*` | 只收 ERROR | 06-20 → 08-19 |

**同一批 ERROR 在两条流里各存一份。** 我第一版的 `cat_all` 把两条都 glob 了 ⇒ 每条 ERROR 数两遍。

证据（逐日对照，两条流完全相等）：

```
uk_cache_key   2026-08-19  全量流=8    error流=8      ⇒ 是副本
               2026-08-18  全量流=6    error流=6      ⇒ 是副本
               2026-08-15  全量流=386  error流=386    ⇒ 是副本
阳性对照        INFO 行     全量流=1140399  error流=0  ⇒ 分流逻辑正确, 不是我 grep 错了
```

⇒ **权威口径 = 只用 error 流**（它覆盖更完整；全量流有留存缺口，2665 < 2960）。
本文所有数字都是这个口径，并且剔除了 `CLAUDER3|CLAUDEPF|claude-f[0-9]|probe-claude` 夹具噪声。

### 另一条自查：我把「近 7 天总数」读成了「当前速率」

`uk_cache_key` 近 7 天 2090（旧口径）看起来像每天 300 次。按天摊开才看见真相：
**8-15 一天尖峰 965 次，8-18 是 15、8-19 是 20。**
⇒ **报总数必须同时按天摊开**，否则一次尖峰会被读成一个持续故障。

---

## 曾被撞过的 26 个约束（error 流，全窗口 06-20 → 08-19）

| 全窗口 | 末见 | 8-13 | 8-14 | 8-15 | 8-16 | 8-17 | 8-18 | **8-19** | 约束 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| 2960 | 08-19 | 10 | 10 | **965** | 0 | 25 | 15 | **20** | `uk_cache_key` | 🔴 bug，**已修** |
| 412 | 08-17 | 0 | 0 | 0 | 4 | 4 | 0 | 0 | FK `material_consumptions` | 未查，已停 |
| 332 | 08-19 | 0 | 0 | 0 | 0 | 0 | 0 | **42** | `uk_voucher_factory_number` | 🔴 bug，**已修** |
| 98 | 07-14 | — | — | — | — | — | — | 0 | FK `fk_po_created_by`（`created_by=0`）| 同族，已停 |
| 91 | 08-19 | 0 | 0 | 0 | 0 | 14 | 7 | **28** | FK `created_by=1` | 🔴 bug，**已修** |
| 35 | 08-13 | 35 | 0 | 0 | 0 | 0 | 0 | 0 | FK `product_type_id=RMT_*` | 🔴 bug，潜伏（未修，见下）|
| 15 | 08-18 | — | — | — | — | — | 15 | 0 | `factory_material_requisitions_...` | 同族，8-18 当天已修 |
| 其余 19 个 | ≤08-08 | 全 0 | | | | | | 0 | — | 已停 |

⚠️ **8-18 / 8-19 的三条（`factory_material_requisitions_requisition_no_key` 15 /
`fk_production_input_allocation_batch` 6 / `fk_bri_material` 3）是我自己造的**
—— 全部 `[u:1309] [f:F006]` + `ProductTypeController` / `WorkProcessController`，
是 8-18 做「判据五坎2」时的夹具，不算测试人员发现的问题。

---

## 🔴 系统性根因：一个 `@SQLDelete` + 341 个 `@Where` + 非部分唯一索引

上表里 **`uk_cache_key`、`uk_voucher_factory_number`、`factory_material_requisitions_...`
三条是同一个成因**：

```
BaseEntity (@MappedSuperclass)
    @SQLDelete(sql = "UPDATE {h-domain} SET deleted_at = NOW() WHERE id = ?")
        ⇒ 全仓每一次 repository.delete() 都是**软删**, 行还在
341 个实体
    @Where(clause = "deleted_at IS NULL")
        ⇒ Hibernate 把它拼进**每一条** HQL/JPQL (含 @Query 写的 JPQL 和 Spring Data 派生查询)
        ⚠️ 原生 SQL **不**经过它
数据库
    146 张表有「deleted_at 列 + 非部分唯一索引」, 其中 **32 张已经有软删行**
```

于是这两类代码会问错问题：

| | 我想知道 | 我实际问的 | 后果 |
|---|---|---|---|
| 号段生成 | 这个号发到第几了（软删的也占着）| 还有几张**活着**的单 | 软删一张 → 永久错位一个号 → 每次发同一个已占用的号 |
| upsert 前查存在 | 这个键在不在（唯一索引看得见软删行）| 有没有一条**活着**的行 | 走「新建」→ INSERT → 永久撞 |

**用户侧看到的是「数据已存在，请勿重复提交」，而他一次都没重复点。**

### 为什么编译、单测、code review 全拦不住

代码看起来完全正确，注释甚至明写「不过滤 deletedAt」—— 而过滤是 Hibernate 在**生成 SQL 时静默加上去的**。
mock 掉 repository 的单测全绿。只有真库 + 真的存在软删行时才炸。

---

## 已修的 4 个（commit `76ffe5fa72`，均带闸 + 变异验证）

### ① `uk_voucher_factory_number` —— 8-19 当天 42 次，今天速率最高

`VoucherRepository.maxVoucherSequenceByFactoryIdAndYear` 是 JPQL。
2026-08-03 那次修复把 `COUNT` 改成 `MAX` 并写下「不过滤 deletedAt」，**但 `@Where` 让它从未生效**。

prod 实测（F006 / 2026 年，17 张凭证）：

```
V-2026-0001 .. 0013   DRAFT    活着   ⇒ MAX(未删) = 13 ⇒ 生成器发 V-2026-0014
V-2026-0014 .. 0017   POSTED   已软删  ⇒ 0014 正好是第一个软删的号 ⇒ 必撞
```

历史上号段更长的时代（302 条 / 未删 66）撞的是 `V-2026-0067`，350 次 ——
**两个时代都精确等于「未删条数 + 1」**，这就是「JPQL 被 `@Where` 过滤」的直接读数。

**修法**：`nativeQuery = true`。要写进代码的那条 SQL 先在 prod 上原样跑过：返回 **17**（不是 13），
阴性对照（加回 `deleted_at IS NULL`）退回 13，阳性对照（不存在的工厂）返回 0。

⛔ **不**把 `uk_voucher_factory_number` 改成部分索引 —— 号一旦发出就不该回收，软删的凭证**应该**继续占着它的号。

### ② `uk_cache_key` —— 全窗口最高频

`saveToCache` 是 `find(...).orElse(新建)`，而那个 find 被 `@Where` 过滤。
prod：`smart_bi_analysis_cache` 全表 5 行、**3 行已软删**，`(F006, dashboard:month)` 就在其中
（三行 `updated_at` 都是 `2026-07-15 00:24:08`，而该签名首见正是 07-15）。

**修法**：新增 native 的 `findIncludingDeletedByFactoryIdAndCacheKey` + 命中软删行时复活它。

⛔ **不**把 `uk_cache_key` 改成部分索引就完事 —— **读路径和写路径需要相反的可见性**：
读路径**必须**保持被过滤（软删的缓存不该端给用户），写路径**必须**看得见。改索引表达不了这个区别。

### ③ FK `created_by=1` —— 自动生产计划自 8-07 起全军覆没

`SupplyChainOrchestrator` 写死 `plan.setCreatedBy(1L)`，注释是「使用 factory_admin1 的 user ID (1L)，避免外键违约」。

**它曾经是对的，被一次迁移打掉了：**

| 证据 | 读数 |
|---|---|
| `V20261029_68__purge_nonkept_factory_tenants` 在 prod 执行 | **2026-08-07 12:20:45** |
| 备份 schema `tenant_purge_68.users` | 还躺着 `id=1 \| factory_admin1 \| F001` |
| 首次 `Key (created_by)=(1)` | **2026-08-07 16:17:28**（迁移后 3h57m）|
| 迁移之前该错误 | **0 次**（阳性对照：同一仪器看得到迁移前 25 次该监听器的其它失败）|
| 备份里的 `PP-AUTO-%` 计划 | 3 张，**全部 `created_by=1`** |
| 现存 `production_plans` 里 `PP-AUTO-%` | **0 行** |

### ⚠️ 2026-08-19 稍后补测：上面两行都对，但精度不够，而且露出一条新的

```
public.production_plans          14 行, PP-AUTO 前缀 **0 行(从来没有过)**, 最早一张 2026-08-15
                                 阳性对照: 前缀分布只有 PLAN(14) ⇒ 我数的是对的列
tenant_purge_68.production_plans 3 张 PP-AUTO, 全部 created_by=1, 日期 08-03 / 08-04
                                 factory_id = **F006 ×2 / LIUSHANMEN ×1**
```

**⇒ 两件事：**

1. **自动链曾经真的产出过**（08-03、08-04 各有行落库）⇒「8-07 之后 0 产出」这个结论**成立**，
   而且现在有了「之前有」的正对照，不再只是「现在没有」。
2. 🔴 **那 3 张属于 F006 和 LIUSHANMEN —— 两个都是「保留」租户**，
   而那个迁移叫 `purge_nonkept_factory_tenants`。它们被备份走只可能是因为
   **它们的 `created_by=1` 指向了被删掉的那个用户**。
   ▎**那次租户清理连带删掉了保留租户的真实生产计划。**
   这是一条**新的、独立的**发现，不在原普查范围里，**未进一步核实**
   （还删了别的什么、有没有别的表同样连坐 —— 都没查）。

**后果是静默的**：`finance-approve` 返回 200「销售订单财务审核通过」，订单状态照常推进，
而生产计划根本没建出来。**8-19 那天有人在自动链路失败 15 秒 / 5 分钟后手工补建了两张计划**
（`created_by=1309` = f006_admin）—— 是人肉发现的。

**修法**：用事件自带的 `event.getApprovedBy()`；为 null 时**显式失败**。
⛔ 不换一个新的写死 id —— 这次的教训正是**写死的 user id 有保质期**。

**顺带修掉一条撒谎的日志**：`save` 改 `saveAndFlush`。
JPA 批处理让 INSERT 拖到事务提交才 flush，而提交由 `@Transactional(REQUIRES_NEW)` 代理在
方法（连同它的 `catch`）**返回之后**执行 ⇒ 外键违例既躲开 catch 也躲开日志。
prod 实证：`自动创建生产计划: PP=PP-AUTO-…` 打了 **28 次，落库 0 行**，
紧接着还打「BOM展开/采购建议生成失败（**不影响PP创建**）」—— 而 PP 压根没创建。

### ④ 乐观锁冲突 476 次 —— 不是并发，是同一个方法把同一个对象存了两次

```
:59  findById         → 无外层事务 ⇒ detached, version = V
:77  save(order)      → merge(): DB 升到 V+1, 返回【新的】托管副本; 本地 order 仍是 V
                        ★ 返回值被丢掉
:83  createFromBusiness → 成功, 凭证已落库
:85  save(order)      → 拿 V 去 merge, DB 已是 V+1 ⇒ StaleObjectStateException
```

`@Async` 线程上没有绑定的 EntityManager（OSIV 只绑请求线程）⇒ 实体 detached ⇒
`save()` 走 `merge()` ⇒ **merge 返回新实例，原对象的 `@Version` 不回写**。
**单线程、确定性、每次首发必炸**，不需要第二个写者。

**自然实验（推翻并发假设）**：`TransferVoucherListener` 是逐字相同的双 save 形状、
同样 `@Async` + AFTER_COMMIT，但 `InternalTransfer` **没有 `@Version`**
（实测：`SalesOrder`/`PurchaseOrder`/`ReturnOrder` 各 1 个，它 0 个）
⇒ `internal_transfers` **零次**冲突，而它内层 catch 触发过 10 次（阳性对照，证明那条路真在跑）。
**判别量是 `@Version`，不是并发。**

**用户侧后果**：全部冲突都在 `SimpleAsyncTaskExecutor` 线程上，`http-nio` 上 **0** 次，
`GlobalExceptionHandler` 的 409 `OPTIMISTIC_LOCK_CONFLICT` **一次都没触发过** ⇒ 用户完全无感。
丢掉的正是 `PENDING → CREATED/FAILED` 那次写：

```
sales_orders     19/38 停在 PENDING     FAILED = 0
purchase_orders   6/7  停在 PENDING     FAILED = 0
return_orders    10/29 停在 PENDING     FAILED = 0
```

**三张表 FAILED 全是 0，因为写 FAILED 的正是抛异常的那次 save** —— fail-soft 设计 100% 失效。
界面上，一张**已经有凭证**的单子显示「凭证生成中」。

**修法**：四处全部 `x = repo.save(x)`。

---

## 两道闸（都做过变异，都红过）

### `SoftDeleteBlindSpotContractTest`

登记「语义上必须看得到软删行」的查询，断言它们是 `nativeQuery = true`。
**变异**：`nativeQuery = true → false` ⇒ 闸红，且报错精确点名该方法。
**两条阳性对照**证明检测器能返回 false（派生查询 / JPQL `@Query` 各一条）。

⚠️ 它**只钉登记过的那几条**（形态 E：宁可窄而可信，不要宽而被关掉），不自动发现新盲点。

### `StaleDetachedMergeContractTest`

四个凭证监听器：第二次 `save` 必须用第一次 `merge` 返回的实例。

⚠️ **关键在桩的形状**：这个测试特意让 `save()` 返回**另一个**实例。
以前的单测让 `save` 返回同一个对象（或 null），于是「有没有接住返回值」**在测试里没有可观测差别**
—— 这就是为什么这个 bug 活了两个月还全绿。

**变异**：四处一起打，**4/4 各自红**。

---

## 登记「故意不改」（硬约束 8）

### A. 另外 5 处 `setCreatedBy(1L)`

`ProductTypeServiceImpl:1163` / `FactoryBlueprintServiceImpl:374,442` /
`RawMaterialTypeServiceImpl:984,1321` / `SupplierServiceImpl:660`
—— 其中 **3 处的目标表同样有 `created_by → users` 外键**。

**不改的理由**：日志窗口内零报错；且各自该归属给谁需要逐处判断（不像销售财审那条有现成的 `approvedBy`）。
**它们是同一形状的潜伏实例**，下一次租户清理会同时引爆。

### B. 另外 18 个发号器有同样的「JPQL 被 `@Where` 过滤」形状

全量分类（57 个发号器逐个读方法体，三条判据全验：调了哪个 repository 方法 / 是否 native / 实体有无 `@Where`）：

| 分类 | 数量 |
|---|---|
| 🔴 BROKEN 且表**已有软删行** | **4** |
| 🔴 BROKEN 但表暂无软删行（定时炸弹）| **16** |
| `SAFE_NATIVE`（已修的两个样板 + 1）| 3 |
| `SAFE_RETRY`（有基于真实失败反馈的重试）| 4 |
| `SAFE_RANDOM`（UUID / 时间戳 / 随机）| ~26 |
| 结构性免疫（走 `EncodingRule.currentSequence` 持久化计数器）| 6 |
| `NEEDS_CHECK` | 0 |

「BROKEN 且表已有软删行」的 4 个：

| 位置 | 表 | 软删/总 |
|---|---|---|
| `ReturnOrderServiceImpl:777 generateReturnNumber` | `return_orders` | **21/29** ← 最险 |
| `SupplyChainOrchestrator:640 generatePlanNumber` | `production_plans` | 6/14 |
| `PurchaseServiceImpl:3216 generateOrderNumber` | `purchase_orders` | 2/7 |
| `SalesServiceImpl:3671 generateSalesOrderNumber` | `sales_orders` | 1/38 |

⚠️ **但它们一次都没撞过**（`uk_return_factory_number` / `uk_po_factory_order` /
`uk_so_factory_order` 都不在那 26 个被撞过的约束里）。
原因多半是它们按天计数（`countByFactoryIdAndDate`），旧日期的软删行不影响当天。

⇒ **修已经炸过的，登记其余** —— 一次改 20 处的风险大于它防住的东西。
但这份清单要留着：任何一天有人软删了**当天**的单，对应的那个就会立刻卡死一整天。

⚠️ 一个容易误判的：`SkuAssemblyService:353 generateRecipeCode` **看起来像 `SAFE_RETRY`**
（有 do-while 判重循环），实际不是 —— 种子计数和循环里的判重查的是同一张被 `@Where` 过滤的表，
两者对软删行「一致地看不见」，循环第一次就把撞号的候选当成「不存在」直接接受。

### C. FK `product_type_id=RMT_*`（8-13 那 35 次，当前静默但完全潜伏）

销售订单**可以卖物料**，而 `InventoryMatchingService:93` 的循环不区分成品行与物料行。
`sales_order_items.product_type_id` **没有**外键，`production_plans.product_type_id` **有**。
物料行的可用量查 `finished_goods_batches`（用 RMT id 必然 0）⇒ 判为缺货 ⇒ 走自动建计划 ⇒ 撞外键。

**判别器仓里早就有** —— `SalesServiceImpl:3844 isMaterialLine(factoryId, productTypeId)`，
发货扣库存那条路在用，orchestration 这条路没调。典型的**形态 B（机制在、没接上）**。

8-18 又建了 4 张带物料行的 SO，但都是 `CANCELLED`、没走到财审 ⇒ 没触发。
**任何一张带物料行的 SO 一旦财审通过就复现。**

### D. `@Async` 失败当前**零出口**

无自定义 `AsyncUncaughtExceptionHandler`、无失败表 / 补偿队列 / 死信 / outbox、
60 个 `@Scheduled` 里没有一个重试凭证 / 缺料报告 / 生产计划。
⇒ `@Async` 里任何异常都是**用户侧无感 + 无记录 + 无补偿**，只有一行 ERROR 日志。
这是本次三个 bug 都能活两个月的共同原因。

---

## 不是 bug / 已经修好的（⛔ 不要再去修）

| 签名 | 全窗口 | 末见 | 说明 |
|---|---|---|---|
| `SalesOrderShortageReportListener` | 82 事件 | **2026-08-01** | 已由 `5572e20371` 修好（`LineItemMatch.isFullySatisfied` 加 `@JsonIgnore`）。三条独立验证：部署 jar 的常量池含 `JsonIgnore` / `sales_order_shortage_report` 表首行 08-02 至今 22 行 / async-uncaught 自 08-01 归零。**我差点把它当成在飞的 bug 报出去。** |
| `AlertType.PAYMENT_OVERDUE` 枚举缺失 | 1084 | 06-25 | 近 7 天 0 |
| `Metaspace` OOM | 871 | 07-16 | 近 7 天 0 |
| SSE 转换器缺失 | 115 | 08-12 | 近 7 天 0 |
| 缺料报告里的 6 条 `FAILED` | 6 | — | **合法拒绝**：「产品尚无已激活的新版 BOM 配方」/「BOM 单位与库存单位无法换算」，配置缺口，已被正确记成 FAILED 行 |

---

## 下一步（未做）

1. **部署**（需要决定）：GitHub 仍 403，走离线协议 —— 合进本地 `main` → detached worktree +
   `SKIP_GIT_CHECK=1` 部署 → 部署后**在运行中的 jar 里 grep 到本次改动的标记**，
   并**真跑一次财审**看 `production_plans` 有没有落行（硬约束 2：找到标记 ≠ 它能跑）。
2. **修 FK `product_type_id=RMT_*`**（上面 C）—— 复用现成的 `isMaterialLine`。
3. **给 `@Async` 一个出口**（上面 D）—— 这是最能防住下一次的一条，但它是独立的一件事。
4. `material_consumptions` FK（412 次，末见 08-17）**未查**。
