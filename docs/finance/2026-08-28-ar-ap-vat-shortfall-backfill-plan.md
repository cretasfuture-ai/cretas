# AR/AP 价税口径存量补差方案

**状态**: 方案 (未执行) — 待 owner 拍板
**日期**: 2026-08-28
**关联**: PR #292 (`fix(finance+rbac): 应收/应付挂账改用价税合计`, 尚未合入/部署)、
PR #285 (`docs(handoff): 销售链跑完一圈`, 首次实测发现)
**只读查询**: `scripts/finance/2026-08-28-ar-ap-vat-backfill/audit_current_shortfall.sql`
**提交脚本 (默认 dry-run)**: `scripts/finance/2026-08-28-ar-ap-vat-backfill/submit_adjustments.sh`

---

## 0. 背景

`SalesServiceImpl.shipDelivery` 挂 `AR_INVOICE` 时传 `order.getTotalAmount()`（未税额），
`PurchaseServiceImpl.confirmReceive` 挂 `AP_INVOICE` 时传 `record.getTotalAmount()`（未税额），
两处都没有加税额。结果是每一张单的应收/应付都比客户/供应商实际欠款少记一个税额。

修复已写在 **PR #292**（`order.getPayableAmount()` = 未税−折扣+税；新增
`taxInclusiveReceivedValue()` 按入库单逐行 × 该行税率重算），**尚未合入、尚未部署**。

⚠️ **只要 PR #292 不合入部署，这份清单每天都在变大** —— 见下节。

---

## 1. 存量清单（本次实测，2026-08-28，生产 `cretas_prod_db`，全库无 factory 过滤）

⚠️ **与背景交接件里的数字不同，且数字更大**：交接件写的是「AR 7 条 ¥1,692.15 /
AP 18 条 13 条错 ¥249,988.54」。本次重新用只读查询核对，实际是 **AR 12 条 / AP 20 条**，
缺口也更大。**差异不是查询口径不同，是时间差** —— 交接件写完之后到现在，生产又发生了
新的发货和入库，而 bug 还在线上，每一单都继续按未税额挂账。**这本身就是最有力的证据：
这个缺口每天都在长大，越晚部署 PR #292，补差的工作量越大。**

### 1a. AR_INVOICE（应收）—— 12 条，12 条全部有缺口，合计少记 **¥2,025.78**

| 交易号 | 客户 | 订单号 | 当前应收 | 应为(价税合计) | 缺口 |
|---|---|---:|---:|---:|---:|
| AR-20260824-5001 | 叮咚-台州临海大洋东路冷藏仓 | SO-20260824-0003 | 10001.00 | 11301.13 | 1300.13 |
| AR-20260819-2283 | SHH0713客户1 | SO-20260819-0002 | 2000.00 | 2260.00 | 260.00 |
| AR-20260828-3725 | 叮咚 | SO-20260828-0006 | 1548.67 | 1750.00 | 201.33 |
| AR-20260819-8688 | SHH0713客户1 | SO-20260819-0001 | 500.00 | 565.00 | 65.00 |
| AR-20260828-3232 | 叮咚 | SO-20260828-0008 | 442.48 | 500.00 | 57.52 |
| AR-20260828-9918 | 叮咚 | SO-20260828-0009 | 442.48 | 500.00 | 57.52 |
| AR-20260820-8043 | SHH0713客户1 | SO-20260820-0001 | 250.00 | 282.50 | 32.50 |
| AR-20260828-8447 | 叮咚 | SO-20260828-0003 | 221.24 | 250.00 | 28.76 |
| AR-20260828-7217 | 叮咚 | SO-20260828-0013 | 88.50 | 100.01 | 11.51 |
| AR-20260825-7914 | SHH0713客户1 | SO-20260825-0003 | 44.25 | 50.00 | 5.75 |
| AR-20260828-9821 | 叮咚 | SO-20260828-0012 | 44.25 | 50.00 | 5.75 |
| AR-20260825-4807 | SHH0713客户1 | SO-20260825-0004 | 0.05 | 0.06 | 0.01 |

**其中 3 条已有对应的已完成 `SALES_RETURN`（见 §4，不能直接套用上表缺口补差）**：
`AR-20260820-8043`（¥32.50）、`AR-20260819-2283`（¥260.00）、`AR-20260828-8447`（¥28.76）。
**其余 9 条合计缺口 ¥1,704.52**，可按上表数字直接补差。

### 1b. AP_INVOICE（应付）—— 20 条，1 条孤儿、1 条恰好对平、18 条有缺口，合计少记 **¥258,973.68**

| 交易号 | 供应商 | 入库单 | 当前应付 | 应为(价税合计) | 缺口 |
|---|---|---|---:|---:|---:|
| AP-20260821-0946 | 北京飞熊 | RCV-20260821-9003 | 2,749,975.00 | 2,997,472.75 | 247,497.75 |
| AP-20260820-9164 | 北京飞熊 | RCV-20260820-7285 | 23,000.00 | 25,070.00 | 2,070.00 |
| AP-20260820-3286 | 北京飞熊 | RCV-20260820-2342 | 20,000.00 | 21,800.00 | 1,800.00 |
| AP-20260820-9170 | 北京飞熊 | RCV-20260820-8056 | 20,000.00 | 21,800.00 | 1,800.00 |
| AP-20260820-1718 | 北京飞熊 | RCV-20260820-1721 | 17,500.00 | 19,075.00 | 1,575.00 |
| AP-20260820-4845 | 北京飞熊 | RCV-20260820-3101 | 17,500.00 | 19,075.00 | 1,575.00 |
| AP-20260825-0153 | 领鲜六膳 (LIUSHANMEN) | RCV-20260825-8613 | 10,420.00 | 11,357.80 | 937.80 |
| AP-20260825-8856 | 领鲜六膳 (LIUSHANMEN) | RCV-20260825-7509 | 9,357.80 | 10,200.00 | 842.20 |
| AP-20260820-9327 | 北京飞熊 | RCV-20260820-5960 | 4,000.00 | 4,360.00 | 360.00 |
| AP-20260828-8052 | 北京飞熊 | RCV-20260828-1480 | 1,834.86 | 2,000.00 | 165.14 |
| AP-20260817-8585 | SHH0713供应商1 | RCV-20260817-6060 | 1,090.00 | 1,231.70 | 141.70 |
| AP-20260817-7802 | SHH0713供应商1 | RCV-20260817-0066 | 1,081.00 | 1,221.53 | 140.53 |
| AP-20260819-0166 | SHH0713供应商1 | RCV-20260819-2264 | 337.90 | 381.83 | 43.93 |
| AP-20260820-5723 | 北京飞熊 | RCV-20260820-9777 | 250.00 | 272.50 | 22.50 |
| AP-20260820-1701 | SHH0713供应商1 | RCV-20260820-9967 | 13.55 | 15.34 | 1.79 |
| AP-20260824-7380 | 北京飞熊 | RCV-20260824-5243 | 1.23 | 1.39 | 0.16 |
| AP-20260823-5830 | 北京飞熊 | RCV-20260823-4722 | 1.23 | 1.34 | 0.11 |
| AP-20260818-8499 | SHH0713供应商1 | RCV-20260818-0925 | 0.50 | 0.57 | 0.07 |

以上 18 条**全部可按上表数字直接补差**，无退货重叠（§4 的排查显示 0 条 AP 有对应
`PURCHASE_RETURN`）。合计 **¥258,973.68**。

另外 2 条不在上表内：
- **AP-20260819-2883**（SHH0713供应商1，¥0.01）—— 重算后应为 ¥0.01（四舍五入到 2 位
  小数与含税额相同），实际无缺口，不需要补差。
- **AP-20260817-0702**（SHH0713供应商1，¥100.00）—— **数据完整性问题，不是税口径问题**，
  见 §6a，需单独处理。

最大的一笔（¥247,497.75，占 AP 总缺口 95.6%）已做行级抽查核实：
`RCV-20260821-9003` 单行 109,999kg × ¥25/kg 胸肉，税率 9%，2,749,975 → 2,997,472.75，
数字真实，不是 join 错误造出来的假读数。

**验证方式**: 逐行读数见 `scripts/finance/2026-08-28-ar-ap-vat-backfill/audit_current_shortfall.sql`
的 `§1`（AR 全量）与 `§4`（AP 全量，按 `PurchaseServiceImpl.taxInclusiveReceivedValue()`
同口径逐行 `received_quantity × unit_price × (1 + tax_rate/100)` 重算，与代码里的取整方式
一致：行级 `HALF_UP` 到 2 位小数，税率除法 6 位 `HALF_UP`）。

---

## 2. 补差走什么通道

**⛔ 绝不直接 `UPDATE ar_ap_transactions`。** 通道是 `ArApService.recordAdjustment`：

```
POST /api/mobile/{factoryId}/finance/adjustment?counterpartyType=CUSTOMER|SUPPLIER
Content-Type: application/json
Authorization: Bearer <提交人 token>

{
  "counterpartyId": "<Customer.id 或 Supplier.id>",
  "amount": <正数, 补差额 = 应为 - 当前>,
  "remark": "VAT补差-<原交易号>(<订单/入库单号>)"
}
```

行为（`ArApServiceImpl.recordAdjustment` / `ArApController:145`）：
- AR 用 `counterpartyType=CUSTOMER`，AP 用 `SUPPLIER`；插入一行
  `AR_ADJUSTMENT`/`AP_ADJUSTMENT`，`approval_status=PENDING`。
- **不修改任何余额**（`Customer.currentBalance` / `Supplier.currentBalance` 保持不变），
  直到第二个人批准。
- 需要 `finance:read_write` 权限提交、`finance:approve_adjustment` 权限批准，且
  **审批人不能与提交人相同**（4 眼原则，`ArApServiceImpl:721` 强制校验）。

批准通道：

```
POST /api/mobile/{factoryId}/finance/adjustment/{transactionId}/approve
Authorization: Bearer <审批人 token, 需 finance:approve_adjustment, 且 ≠ 提交人>
```

批准后才会把 `amount` 累加进 `Customer.currentBalance` / `Supplier.currentBalance`
(`ArApServiceImpl:727-744`)，并把 `approval_status` 翻转成 `APPROVED`。

批量查看待审批队列: `GET /api/mobile/{factoryId}/finance/adjustments/pending`。

提交脚本（默认 **dry-run**，只打印不执行）:
`scripts/finance/2026-08-28-ar-ap-vat-backfill/submit_adjustments.sh`

---

## 3. 顺序约束（硬性）

**必须等 PR #292 合入 main 并部署到生产之后，才能提交这批补差。**

原因：
1. 只要 PR #292 没部署，`shipDelivery`/`confirmReceive` 还在继续按未税额挂新的
   `AR_INVOICE`/`AP_INVOICE`。如果现在就补差存量，补完之后系统还会继续产生新的
   同类缺口 —— 越补越补不完，而且没法用一个统一的「部署时间点」把「旧口径」和
   「新口径」的数据分开。
2. 提交补差前，**必须重新跑一遍** `audit_current_shortfall.sql`（不要用本文档
   1a/1b 的旧快照）—— PR #292 部署前的这段窗口期，每一笔新发货/新入库都在继续
   产生新的缺口，实际要补的条数只会比本文档写的更多。
3. 建议顺序：
   - PR #292 review → 合入 main → 按 `deploy-backend.sh` 标准流程部署 prod
   - 部署后验证：抽查部署后新产生的一笔 `AR_INVOICE`/`AP_INVOICE`，确认金额已经是
     价税合计（不是本方案要修正的未税额）
   - 重新跑 `audit_current_shortfall.sql` 拿到最新目标清单
   - 提交本批 + 部署窗口期新增的补差（PENDING）
   - 第二人审批

---

## 4. 风险与副作用

### 4a. 影响面

`approveAdjustment` 批准后会直接改 `Customer.currentBalance` / `Supplier.currentBalance`
(`ArApServiceImpl.java:733-744`)。这个字段被以下功能读取，补差之后都会跟着变：

| 功能 | 端点/位置 | 影响 |
|---|---|---|
| 客户/供应商信用额度检查 | `GET .../credit-check`, `checkCreditLimit()` | 补差后客户/供应商"已用额度"上升 |
| 财务概览 | `GET .../overview` | `totalReceivable`/`totalPayable` 上升 ¥2,025.78 (AR) / ¥258,973.68 (AP) 左右 (待最终确认净额，见 4b) |
| 账龄分析 | `GET .../aging` | **⚠️ 见 4c —— 调整行不会出现在账龄表里** |
| 对账单 | `GET .../statement` | 会看到多一行 `AR_ADJUSTMENT`/`AP_ADJUSTMENT`，`periodDebit` 增加 |
| 财务概览按类型统计 | `overview.transactionsByType` | 多出 `AR_ADJUSTMENT`/`AP_ADJUSTMENT` 类型的汇总行 |

### 4b. 3 条 AR 有已完成退货重叠 —— **不能直接套用整单口径的缺口**

`return_orders` 表结构上就没有 `tax_amount` 列（详见 §6），所以已完成的
`SALES_RETURN` 冲减的是**未税额**。这 3 条订单同时踩了「挂账少税」和「退货少冲税」
两个缺陷，两者会互相抵消一部分：

| 订单 | 未税额 | 应付(价税合计) | 已退货(未税) | 退货占比 | 说明 |
|---|---:|---:|---:|---:|---|
| SO-20260820-0001 | 250.00 | 282.50 | 200.00 | 80%（部分退货） | 剩余 20% 的税额差仍需补，但不是整单的 ¥32.50 |
| SO-20260819-0002 | 2000.00 | 2260.00 | 1000.00 | 50%（部分退货） | 同上，需按未退部分重算 |
| SO-20260828-0003 | 221.24 | 250.00 | 221.24 | **100%（全额退货）** | 退货金额 = 全部未税额，说明这单商品**全部**退回；此时正确的净应收缺口应接近 **¥0**，而不是整单缺口 ¥28.76 —— 因为退货本身也应该冲减价税合计但实际只冲了未税额，这个"退货少冲的税"和"挂账少记的税"在全额退货场景下正好互相抵消 |

⚠️ 补充: 这 3 条对应的退货冲减 `AR_ADJUSTMENT`（`AR-20260828-1027` / `AR-20260820-0035` /
`AR-20260820-3544`）目前都还是 **`PENDING`**，尚未审批，客户余额还没被退货冲减。
这意味着现在这一刻客户账上仍然是"挂了全额未税应收、退货还没生效"的状态 ——
补差方案落地时这 3 张退货是否已经被批准，会改变净额计算。**建议这 3 条从本批
机械补差里剔除，由 owner/财务另行核算净额后单独提交**（本方案脚本已默认排除）。

### 4c. 账龄分析读不到调整行 —— 补差不会反映在逾期账龄表里

`ArApTransactionRepository.findOverdueReceivables`/`findOverduePayables`
的 JPQL **写死了 `transactionType = 'AR_INVOICE'`/`'AP_INVOICE'`**
(`ArApTransactionRepository.java:84-100`)，`AR_ADJUSTMENT`/`AP_ADJUSTMENT`
**不会出现在账龄分析里**，无论到期日是多久以前。补差之后，账龄表按到期日分桶的
金额**仍然是补差前的旧数字**，只有 `财务概览`(`sumReceivables`/`sumPayables`) 和
`对账单` 会反映新增的调整行。这是 review 时容易产生"补了怎么账龄没变"疑问的地方，
应提前告知财务同学。

### 4d. AP 的 `outstandingAmount`（逐笔核销）不会被补差更新

`AP_INVOICE` 行在创建时有 `settled_amount`/`outstanding_amount`/`payment_status`
三个字段用于逐笔核销跟踪 (`ArApServiceImpl.initializePayableOpenItem`)。
补差走的是新增一行 `AP_ADJUSTMENT`，**不会更新原 `AP_INVOICE` 行的
`outstanding_amount`**——原发票的"未核销余额"仍然是补差前的（偏低的）数字。
供应商按发票号对账、或做逐票核销支付时，会看到发票金额与供应商余额（已含补差）
不一致。这不是 bug，是"调整"这个机制本身的设计（银行流水式台账，调整是独立分录），
但需要让财务和供应商对账的同学知道口径。

### 4e. 补差金额会计入"本月交易笔数/客户数/供应商数"等统计

`getFinanceOverview` 里的 `monthlyTransactionCount` 等按 `transactionDate` 统计，
补差发生在 2026-08 会让 8 月统计数字包含这批调整行，如果有月度对比报表要注意。

---

## 5. 回退方案

**PENDING 状态（未审批）**: 直接调用
`POST /api/mobile/{factoryId}/finance/adjustment/{transactionId}/reject`（需
`finance:approve_adjustment` 权限，`reason` 可选）。`rejectAdjustment` 不改变任何余额，
只是把 `approval_status` 置为 `REJECTED` 并在 `remark` 追加驳回原因——是最干净的回退方式。

**已批准（APPROVED）但发现算错了**: 没有"撤销审批"的端点。正确做法是**再提交一笔
反向调整**（`amount` 取负号），走同样的 PENDING → 审批流程冲平：

```
POST /api/mobile/{factoryId}/finance/adjustment?counterpartyType=...
{"counterpartyId": "...", "amount": -<原补差额>, "remark": "冲正-<原调整交易号>-原因"}
```

这样两条 `AR_ADJUSTMENT`/`AP_ADJUSTMENT` 都留痕（银行流水式台账的设计意图就是
"只增不改"），审计时能看到完整的"补差→发现错误→冲正"链条，而不是删除或覆盖历史记录。

**批量场景**: `POST /api/mobile/{factoryId}/finance/adjustments/batch-reject`
可以一次驳回多条 PENDING（body: `{ids: [...], reason: "..."}`）。

---

## 6. `return_orders` 无 `tax_amount` 列的影响

`ReturnVoucherGenerator.java:36-39`（注释原文）：

> `ReturnOrder` **无 `tax_amount` 列**（只有 `total_amount`），故退货凭证按 SP11 spec
> §5"无税率数据视为 0 税 → 退化两行"处理 ... 历史含税精确回填脚本按需单独做。

已用只读查询确认（`ar_ap_transactions` ⨝ `return_orders`）：**目前只有 3 条已完成的
`SALES_RETURN` 与本次 AR 缺口清单有重叠**（就是 §4b 那 3 条），**AP 侧 0 条重叠**
（PURCHASE_RETURN 目前没有命中任何本次要补差的 AP_INVOICE）。

**影响**：
1. 任何"实物已退回"的退货，冲减客户/供应商余额时用的是未税额，天然比正确值少冲一个
   税额——这个缺陷**和本次要修的挂账缺陷是同一个根因家族**（金额取值时漏了税），
   但**修复范围不同**：`PurchaseServiceImpl`/`SalesServiceImpl` 的挂账口径在 PR #292
   里修了；`return_orders` 表结构本身没有 `tax_amount` 列，**PR #292 没有改这个**，
   需要单独一次表结构变更 + 历史退货回填（`ReturnVoucherGenerator` 注释里也写着
   "待 ReturnOrder 补 tax_amount 列后才能拆"）。
2. 对本次补差的直接影响：§4b 的 3 条 AR **不能**用"整单价税合计 − 当前挂账额"
   这个机械公式，因为退货那一侧也在用错误的（偏低的）口径，两个缺陷在退货比例
   为 100% 时会互相抵消、部分退货时会部分抵消。这 3 条已从 `submit_adjustments.sh`
   的默认目标清单里剔除。
3. **本方案不处理 `return_orders` 缺 `tax_amount` 列本身** —— 这是一个独立的、
   范围更大的修复（表结构变更 + 所有历史退货凭证重算），建议单开一个 PR/方案，
   不要和这次的挂账口径补差混在一起。

---

## 7. Owner 需要拍板的问题

1. **是否批准先合并部署 PR #292，再执行本方案的补差？**（本方案的前提，§3）
2. **§4b 的 3 条 AR（尤其是 SO-20260828-0003 全额退货那条）净额怎么算？**
   本方案建议排除在机械补差之外，交给财务用"应为价税合计 − 已生效的退货冲减
   (含税重算)"人工算净额后单独提交——是否同意，还是有别的口径？
3. **`AP-20260817-0702`（¥100.00，source_id/purchase_order_id 均指向不存在的记录）
   要不要单独立案？** 这是一个数据完整性问题（挂账时引用的入库单/采购单后来消失了，
   不是软删，是完全查不到），不属于税口径缺陷，本方案没有给出修正金额（无从算起）。
4. **`return_orders` 缺 `tax_amount` 列这个更大的缺口要不要跟进？**（§6）
   如果同意跟进，是否单开一个方案；如果不跟进，需要接受"退货冲减长期偏少"这个
   已知限制。
5. **补差金额（AR ¥1,704.52 / AP ¥258,973.68，均为提交时点快照，实际会因部署时间
   而变化）对财务报表/纳税申报是否有跨期影响？** 这批缺口横跨 8 月17日–28日的
   业务，如果涉及月结/申报周期，需要财务确认补差应该计入哪个会计期间。
