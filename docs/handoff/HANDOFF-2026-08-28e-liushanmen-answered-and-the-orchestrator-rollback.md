# 交接：六膳门为什么停下来 —— 答案不是「没开工」，是**我们清了场，然后审批链塌了**

> 接 `HANDOFF-2026-08-28-next-cost-chain-and-liushanmen-onboarding.md` 的三件事，全部做完。
> 这一份只回答：**问出来的是什么 / 我推翻了上一棒哪个前提 / 我错在哪 / 下一棒第一件事。**

---

## 🔴 接手第一件事：去看 `SupplyChainOrchestrator.onSalesOrderFinanceApproved`

**整个生产库里有且只有 3 条异步失败台账，3 条全是它，全部 `OPEN`。**

```
LIUSHANMEN  SO-20260824-0002  ¥58,760   2026-08-24 14:48:23   OPEN
LIUSHANMEN  SO-20260824-0001  ¥33,500   2026-08-24 13:38:31   OPEN
F006                          —         2026-08-24 01:14:59   OPEN
```

异常：`UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`

台账自己写的补救提示（是我们自己写的，不是我编的）：

> 供应链联动没跑完: 成品库存没预留 / 生产计划(PP-AUTO-*)没建出来。需人工核对该销售单的
> 库存占用与生产计划, 缺什么补什么 —— ⚠️ 没有幂等的自动补偿入口, 重放会重复预留库存。

⚠️ **告警每天都在响，已经响了 5 天，一次都没人看。**

```
2026-08-24 09:05  ASYNC_FAILURE_BACKLOG open=1
2026-08-25 09:05  ASYNC_FAILURE_BACKLOG open=3
2026-08-26 09:05  ASYNC_FAILURE_BACKLOG open=3
2026-08-27 09:05  ASYNC_FAILURE_BACKLOG open=3
2026-08-28 09:05  ASYNC_FAILURE_BACKLOG open=3     <- 今天, 我读到的这一条
```

它自己那句话是这次最该记住的一句：**「这些失败在用户侧是无感的（接口当时返回了 200）」**。
店里点了「财务通过」，页面绿了，¥92,260 的两张单**什么都没往下走**。

🔴 **它没有 web-admin 界面。** 只有 `GET /api/mobile/{factoryId}/async-failures`
和一条 WARN 日志。⇒ 除非有人 grep 日志，否则这套告警在产品里等于不存在。

---

## 1. 上一棒的前提是反的：六膳门**不是没开工**

上一棒读了 4 张表（生产计划 0 / 消耗 0 / 凭证 0 / 期初没建）得出「根本没开工」。
我把**全部 411 张带 `factory_id` 的表**扫了一遍，画面完全不同：

| | 六膳门 |
|---|---:|
| **label_qc_tray_crops** | **11,409** |
| label_qc_annotations | 9,293 |
| attachments | 6,032 |
| label_qc_photos | 6,020 |
| label_qc_tasks | 1,550 |
| smart_bi_usage_records | 202 |
| raw_material_types | 139 |

**8 月每一天都在跑标注质检**，8-28 当天还有 26 条。31 个账号**全部登录过**，
最近一次是今天 09:25（`15062625428`，质检员，真实手机号账号）。

⇒ **系统在被用，而且是天天用。** 只是用的是标注质检那条线，不是生产成本这条线。

### 为什么生产那条线是空的 —— 三次删除都是我们自己干的

```
2026-08-01 10:09  V20261029_43  cleanup liushanmen plans boms workflows
2026-08-01 11:05  V20261029_44  cleanup liushanmen orders and stock
2026-08-07 12:20  V20261029_68  purge nonkept factory tenants        <- 连坐, 见 §3
2026-08-09 16:40  V20261029_78  purge legacy production batches
```

43/44 是 **Steve 2026-08-01 亲自拍板**的清场：
「清理掉所有生产计划 bom 还有工序workflow」/「就留单位逻辑 sku 原料字典，其他安排的订单和工序workflow全部删除」。
台账 `backup_lsm_cleanup_20260801` 逐条记着：生产计划 8、BOM 12、workflow 18、
workflow revisions 132、物料批次 27、成品批次 2、盘点 2、采购单 6、销售单 1。

78 又把 15 个 LEGACY 生产批次 + 16 条领料消耗搬进了 `legacy_retired`。

⇒ **「2 个物料批次」不是卡了一个月，是 8-25 才开始的重建，到今天才 3 天。**
   而且他们重建得不慢：8 月新建 product_types 17、bom_recipes 10、
   销售单 9、发货记录 14、采购单 2。

### 完整时间线

```
6-16  建租户, 31 个账号
7-02→7-30  正常跑业务(生产计划 / BOM / workflow / 采购 / 销售 / 27 个物料批次)
8-01  ← 我们清场(Steve 拍板), 只留单位/SKU/原料字典
8-01→8-13  他们重建: 客户 4 家、供应商 3 家、SKU 17、BOM 10、销售单 9、发货 14
8-07  ← purge 连坐删掉他们 1 张自动生产计划
8-09  ← LEGACY 下架搬走 15 批次 + 16 消耗
8-24 13:38  SO-20260824-0001 ¥33,500 财务通过 → 编排回滚, 无感
8-24 14:48  SO-20260824-0002 ¥58,760 财务通过 → 编排回滚, 无感
8-24 14:50  ← ERP 侧最后一条操作日志。此后再没有。
8-25 09:08  建了 2 个物料批次(就是那「2 个」), 之后无动作
8-28        标注质检照常, ERP 侧静默
```

▎**他们不是没开始。他们开始了，撞上一堵不说话的墙，然后不来了。**

`fg_reservation_ledger` 六膳门 = **0 行**（成品预留一条都没有），与台账提示互相印证。

---

## 2. ✅ 导出没坏 —— 是没人点。已用界面跑通并留下阴性对照

**上一棒的怀疑（`row_count=0` 可能是「导出坏了」）不成立。**

生产 web-admin `f006_admin/123456` → 财务管理/凭证导出 → 「导出金蝶云星空(.xlsx)」，
同一个按钮、同一个租户、同一个目标系统，只改期间：

| 期间 | `row_count` | 判读 |
|---|---:|---|
| **2026-08-01 → 08-31** | **4** | 正向：导出可用 |
| 2026-07-09 → 07-09 | **0** | 阴性对照：空期间就是 0 |

写前写后计数：`voucher_export_records` **106 → 107 → 108**，两次都落了记录。

**4 这个数是对的**：金蝶导入模板是 **POSTED-only**
（`VoucherExportServiceImpl` 第 1305 行 `.filter(v -> v.getStatus() == POSTED)`），
而 F006 八月只有 **1 张存活的 POSTED 凭证** `V-2026-0018`，它恰好有 **4 条分录**。

7-09 那两条 `row_count=0` 也是**诚实的**：那次期间是**单独一天** 7-09，
而 F006 在 `voucher_date = 2026-07-09` 上的凭证数实测 **0 张**。

### 🔴 但真正的洞在这里：八月 94% 的钱导不出去

```
八月存活凭证 16 张
  POSTED   1 张 /  4 条分录 /  ¥5.00        <- 只有这个能进金蝶
  DRAFT   15 张 / 40 条分录 / ¥50,950.82    <- 永远不会进金蝶
```

⇒ **凭证在生成，但停在 DRAFT 没人过账。** 就算以后每月按时点导出，
   94% 的金额也会**静默**留在系统里 —— 导出成功、文件有内容、账还是不平。

⚠️ 「按月点一次导出」这个运营动作**不足以**闭环，必须先有人过账。

---

## 3. ✅ 科目从哪来 —— 是**改代码**，不是补数据

问题原文：F006 有 20 条凭证却 0 条科目映射，那些科目是怎么来的。

**答案：8 个凭证生成器里 7 个把科目代码硬编码在 Java 里，根本不读那张表。**

```
service/voucher/impl/
  DepreciationVoucherGenerator      ✗ 不读
  ExpenseVoucherGenerator           ✗ 硬编码 "1405" 库存商品
  InventoryTransferVoucherGenerator ✗ 硬编码 "1405"
  ReturnVoucherGenerator            ✗ 硬编码 "1403"/"1405"/"2202"/"1122"/"6001"
  SalesReceiptVoucherGenerator      ✗
  WageVoucherGenerator              ✗
  WastageReportVoucherGenerator     ✗
  PurchasePaymentVoucherGenerator   ✅ 唯一一个读 voucher_subject_mappings
                                       —— 且只覆盖贷方, 查不到就 fall back 硬编码 1405/2202
```

`PurchasePaymentVoucherGenerator` 自己的注释已经承认了：
「科目代码 2221.02 与主科目 (1405/2202) 同为 hardcode 默认值」。

⇒ **补 6 条映射数据改变不了 20 条凭证里的任何一个科目。**
   要让客户的金蝶账套科目生效，得让另外 7 个生成器也走映射表 —— 那是代码工作。

### 附带查出来的一个数据缺陷：种子没了，自动初始化永久静默失效

`VoucherSubjectMappingServiceImpl.autoInitFromDefault()` 从 `__default__` 工厂复制 6 条。
`V20261011_20` 在 2026-06-10 写入过这 6 条种子，六膳门 2026-07-02 14:19 复制成功
（备注就是「自动从默认配置复制」）。

**今天 `__default__` 一行都没有了**（含软删，全库 `voucher_subject_mappings` 只有六膳门那 6 条），
任何 backup schema 里也没有。⇒ 代码里那条路现在是：

```java
if (defaults.isEmpty()) {
    log.warn("[SP11] __default__ 工厂无科目映射种子数据, 跳过自动初始化");
    return;                                  // <- 永久静默 no-op
}
```

**视觉验收当场证到了**：F006 打开凭证导出页（页面会调 `listByFactory` → 触发自动初始化），
「结算属性 → 会计科目映射」显示 **「暂无科目映射配置」**，
页面加载后再查库仍然 **0 条**。
📌 这条读数的对照是：同一时刻 `voucher_export_records` **有**增长 ——
   仪器活着，所以「没生成」是真的没生成，不是没跑到。

---

## 4. 🔴 顺带抓到：一次「只删死租户」的 purge 物理删掉了两个**活租户**的行

`V20261029_68 purge nonkept factory tenants`（8-07）显式声明「工厂侧只保留 F006 + LIUSHANMEN」，
按外键闭包扩散选行。备份 schema `tenant_purge_68` 里，**169 张带 factory_id 的表**中
恰好有 3 行属于被保留的租户：

```
F006        PP-AUTO-20260803-0006  PENDING     2026-08-03
F006        PP-AUTO-20260803-0007  PENDING     2026-08-03
LIUSHANMEN  PP-AUTO-20260804-0003  CANCELLED   2026-08-04
```

三条在 `public.production_plans` 里**都已经不存在**（物理删）。
数据没丢（整行备份在 `tenant_purge_68.production_plans`），但
**「闭包扩散」会把保留租户的行顺着外键卷进来**这件事之前没人知道。

⇒ 下次写这类 purge，闭包算完之后加一道**保留名单反查**：
   `SELECT ... WHERE factory_id IN (保留名单)` 必须为 0 行，非 0 就停。

---

## 5. 我错在哪 / 我没查清的

### 5.1 我差点在别人的生产会话里写数据

打开生产 web-admin 时，浏览器里是一个**已登录的「六膳门管理员／工厂总监」会话**
—— 那是真客户租户，不在我的写入授权内。我**先登出再用 `f006_admin` 重登**才动手。

⚠️ 副作用要说清楚：**我把那个六膳门会话登出了**。如果那是谁正在用的窗口，需要重新登录。

⚠️ 同源风险值得单独记一笔：**F006 的租户名叫「六膳门食品科技」，LIUSHANMEN 叫「六膳门」**。
   两个租户在界面上只差三个字，而一个是测试、一个是真客户。上一棒的
   `f006_admin` 首页显示「六膳门食品科技」，极易被读成真客户。

### 5.2 一次点击落空，而它的长相是「导出没产生记录」

阴性对照第一次点下去，`voucher_export_records` 停在 107 没动。
真因是**页面重排后按钮从 (614,256) 移到了 (814,393)，我点在了空白处** ——
不是「空期间导出失败」。

▎按硬约束 9：那次「没发生」的读数，正是靠「导出记录**必须**增长」这个对照
▎才没被读成结论。补点一次之后 107 → 108，`row_count=0` 才成为真读数。

### 5.3 ⛔ 没查清：六膳门主数据的账对不上，我**没有**确认成因

`V20261029_44` 的注释白纸黑字写着当时（8-01）保留了
`raw_material_types(229)` / `product_types(152)`。今天实测 **139 / 22**，
且**零条软删**（`total == alive`），所有 backup / purge schema 里也**没有**六膳门的这两张表的行。

我查过的、可以排除的：
- 没有任何 migration 对这两张表做 `DELETE FROM`（全量 grep）
- `tenant_purge_68` / `f006_clear_71` / `legacy_retired` 里六膳门这两张表都是 0 行

**我不知道它们去哪了，也不能排除「那两个括号里的数当时就写错了」。**
⚠️ 那两个数是**注释里的二手读数**，无法重放，我不打算据此下结论 ——
   本仓的教训是「从注释推出行为要先验它」，这次验不了。
⇒ 要落实只有一条路：找 8-01 前后的库备份比一次。**没查之前不要写进任何对客材料。**

### 5.4 没做的

- **手机端真实业务链**（交接件 §4 的第 4 条）：没碰。adb 三个坑照抄上一棒即可。
- §4 的四条技术残留：没碰，都不阻塞。
- 六膳门调拨流仍只授权 `factory_super_admin`（上一棒 §4.3）：**真客户侧，没动**。

---

## 6. 下一棒建议顺序

| # | 事 | 为什么 |
|---|---|---|
| 1 | **修 `SupplyChainOrchestrator` 的 rollback-only** | 唯一一个「真客户撞上就停手」的缺陷，且全库 3 条失败全是它 |
| 2 | **人工补 SO-20260824-0001/0002 的生产计划与库存预留** | 台账说没有幂等补偿入口，只能人工；¥92,260 挂着 |
| 3 | **给异步失败台账做一个界面 / 接进通知** | 现在只有 WARN 日志，响了 5 天没人看 |
| 4 | **DRAFT 凭证过账这一步谁来做** | 不解决它，导出永远只带得走 6% 的金额 |
| 5 | 让另外 7 个生成器走 `voucher_subject_mappings` + 补 `__default__` 种子 | 客户金蝶科目才可能对得上 |
| 6 | purge 类 migration 加「保留名单反查 = 0 行」闸 | §4 |

---

## 7. 本轮在生产上做的写入（全部 F006，可核）

```
voucher_export_records  106 -> 108   新增 2 条, 都是我建的
  2026-08-28 12:25:56  KINGDEE_YXSKY  2026-08-01..08-31  row_count=4   正向
  2026-08-28 12:29:38  KINGDEE_YXSKY  2026-07-09..07-09  row_count=0   阴性对照
```

**阴性对照（不该动的没动）**：`vouchers` / `voucher_entries` / `voucher_subject_mappings`
/ `material_batches` 本轮零变化；非-F006 租户**零写入**（六膳门只做了只读查询 + 登出）。

📌 证据截图：`D:\Temp\claude-chrome-screenshots-s7idbT\screenshot-1787891136312-2.jpg`（F006 凭证导出页，
「暂无科目映射配置」）、`...-4.jpg`（阴性对照期间 2026-07-09）。
