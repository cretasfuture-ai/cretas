# 只读调查：两张卡死的发货单 + aistore key 疑云

**日期**: 2026-08-28
**性质**: 纯只读调查（生产库只读 SELECT + SSH 只读 `/proc`/日志/systemd 检查），不含任何写操作。

---

## 任务 A：两张卡死的销售发货单，到底还该不该发

### 结论先说：两张单的性质不一样，证据支持两种不同的处理方向

| | `DLV-20260812-6158` / `SO-20260812-0001` | `DLV-20260823-8189` / `SO-20260823-0001` |
|---|---|---|
| 客户 | SHH0713客户1（真实/长期使用中的客户，见下） | SOP-20260729-01-**测试客户**（客户名字面就是"测试客户"） |
| 金额 | ¥500.00（税额 ¥0） | ¥10.00（税额 ¥1.30） |
| 是否可发货（库存） | **不能**——涉及的 SKU 已停用且零库存 | **能**——现有库存 7 件，订单只要 1 件 |
| 倾向 | 更像真实业务的孤例卡单，建议走补救 | 更像测试流量，建议走取消/作废确认 |

以下是全部实测依据。

### 1. 订单/客户/金额（`sales_orders` + `customers`，factory_id=F006）

```
order_number      | customer_name              | status            | order_date | total_amount | tax_amount | invoice_status | invoiced_amount | paid_amount
SO-20260812-0001  | SHH0713客户1                | FINANCE_APPROVED  | 2026-08-12 |       500.00 |       0.00 | (空)           | (空)            | (空)
SO-20260823-0001  | SOP-20260729-01-测试客户    | FINANCE_APPROVED  | 2026-08-23 |        10.00 |       1.30 | (空)           | (空)            | (空)
```

两单都**没有开票、没有收款记录**（`invoice_status`/`invoiced_amount`/`paid_amount` 全空）。

⚠️ 踩坑记录：`order_number` 不是全局唯一，`SO-20260812-0001` 在另一个工厂 `LIUSHANMEN` 下也存在一张完全不同的单（客户"王伟"，¥10，状态 COMPLETED）——**已按 `factory_id='F006'` 过滤**，与此案无关。

### 2. 客户关系是否还在

**客户1（SHH0713客户1）**——F006 下共 **15 张订单**，跨度 2026-08-12 ~ 2026-08-28（今天）：

```
SO-20260812-0001 FINANCE_APPROVED   500.00   (本单)
SO-20260817-0001 FINANCE_APPROVED   500.00
SO-20260817-0002 FINANCE_APPROVED  4000.00
SO-20260818-0007 FINANCE_APPROVED  4000.00
SO-20260819-0001 COMPLETED          500.00
SO-20260819-0002 COMPLETED         2000.00
SO-20260819-0003 FINANCE_APPROVED   400.00
SO-20260819-0004 FINANCE_APPROVED    50.00
SO-20260820-0001 PARTIAL_DELIVERED  250.00
SO-20260820-0002 FINANCE_APPROVED   100.00
SO-20260820-0003 FINANCE_APPROVED    50.00
SO-20260821-0001 DRAFT              500.00
SO-20260825-0003 COMPLETED           44.25
SO-20260825-0004 COMPLETED            0.05
SO-20260828-0002 FINANCE_APPROVED   354.00   ← 今天新下的单
```

关系明显**没有断**——今天（8-28）还在下新单，且有多张 COMPLETED/PARTIAL_DELIVERED。这张卡在 PENDING_SPLIT 的 500 元单是这条持续流水里的一个孤例卡单，不是"客户已经走了所以无所谓"。

**客户2（SOP-20260729-01-测试客户）**——F006 下只有 **4 张订单**，且客户名本身就写着"测试客户"：

```
SO-20260809-0004 CANCELLED    1.00
SO-20260810-0001 CANCELLED    1.00
SO-20260810-0002 CANCELLED    1.00
SO-20260823-0001 FINANCE_APPROVED  10.00   ← 本单
```

前 3 张全部 ¥1、全部 CANCELLED，像是冒烟测试留下的痕迹；本单 ¥10 是这个"客户"唯一一张没被取消的单。

补充：F006 本身（"六膳门食品科技"）55 张订单集中在 2026-08-09~08-28 19 天内，且其产品目录（见下）以近乎每天一个版本号的节奏在滚动重建（`E2E-MVP-...` / `SOP-YYYYMMDD-NN-...`），是一个持续被用作测试/联调的工厂租户，不是自然增长的商户流水（这不代表客户1的往来是假的——客户1有真实的 COMPLETED/PARTIAL_DELIVERED 记录——但客户2的画像明显是冒烟测试数据）。

### 3. 订单行 `delivered_quantity` 是否为 0

```
order_number      | product_name                      | quantity | delivered_quantity
SO-20260812-0001  | SOP-20260730-01-黄油鸡-成品800g   |    10.00 |               0.00   ← 真的一件没发
SO-20260812-0001  | 1.75L李锦记薄盐生抽（RMT_...）     |     1.00 |               1.00   ← 见下方异常
SO-20260823-0001  | SOP-20260817-01-黄油鸡-成品800g   |     1.00 |               0.00   ← 真的一件没发
```

🔴 **异常发现（不在原任务清单里，但直接影响判断）**：`SO-20260812-0001` 有第二行（"1.75L李锦记薄盐生抽"，`product_type_id = RMT_1782891387679`）已经显示 `delivered_quantity = 1.00`，但：
- 这个 `product_type_id` 在 `product_types` 表里**根本不存在**（0 行）——`RMT_` 前缀是原料编码格式，不是成品 SKU；
- 母发货单 `DLV-20260812-6158` 的明细（`sales_delivery_items`）里**只有一行**（对应"黄油鸡"，`sales_order_item_id=753`），完全没有这行"生抽"（`sales_order_item_id=758`）。

也就是说这个 `delivered_quantity=1.00` **不是通过发货流程产生的**（走这条流程会在 `sales_delivery_items` 留下对应行），更像是测试数据直接写库时顺手带的字段。**卡住的、需要处理的只有"黄油鸡"这一行（10 件，¥500 里的全部金额）**，"生抽"那一行不需要动。

### 4. AR 挂账

```sql
SELECT * FROM ar_ap_transactions WHERE sales_order_id IN (...) ;
-- 0 rows（两单都没有）
```

两单都**没有任何 AR 挂账**——不存在"取消会破坏应收账"的顾虑。

### 5. 产品当前有没有库存能发（补救可行性）

**`SO-20260823-0001`（测试客户单）**：涉及 SKU `eb0aa47b-...`（`SOP-20260817-01-黄油鸡-成品800g`，`is_active=true`，当前在售版本）。`finished_goods_batches` 现有可用库存：
```
batch FG-PLAN-...-S1-eb0aa47b   AVAILABLE   剩 2.00
batch TRF-FG-20260828-3094      AVAILABLE   剩 5.00
合计可用 ≈ 7.00 件，订单只要 1 件 —— 库存充足，随时能发。
```

**`SO-20260812-0001`（真实客户单）**：涉及 SKU `707d0026-...`（`SOP-20260730-01-黄油鸡-成品800g`，`code=CPF0060021`）。查 `product_types`：

```
id                                    | name                              | is_active
707d0026-7236-430c-825d-5618c64b47ce  | SOP-20260730-01-黄油鸡-成品800g   | f   ← 已停用
```

且这个 SKU 在 `finished_goods_batches` 里**0 条批次记录**——不是"库存不足"，是**这个具体 SKU 版本从来没有、现在也不会再有库存**。F006 的"黄油鸡"这个品类过去一个月滚动了 15 个 SKU 版本（`E2E-MVP-...` → `SOP-20260723-01` → … → `SOP-20260730-01`（本单用的）→ … → `SOP-20260817-01`（当前在售，即 823 单用的那个）），旧版本全部 `is_active=false`。

⚠️ **这意味着"补救"对这一单没那么简单**：仓库/销售没法直接对着这个订单行点"发货"，因为压根没有这个 SKU 版本的库存可发。要恢复履约，实际上要么（a）把订单行的 `product_type_id` 改指到当前在售的 `eb0aa47b`（数据修复，不是简单操作），要么（b）确认客户能接受用新版本 SKU 履约后重新走一次分批发运。这一点请 owner 拍板——技术上我不建议在没有产品/销售确认的情况下改订单行数据。

### 6. 有没有关联的生产计划/批次预留占住

```sql
SELECT * FROM fg_reservation_ledger WHERE sales_order_id IN (...);
-- 0 rows（两单都没有）
```

两单都**没有任何批次预留占用**——不存在"库存被这张卡死的单锁住，导致别的订单发不出去"的连带影响；同时也印证了这两单在库存侧从未真正进入过发货流程。

### 母发货单本身

```
delivery_number    | record_role | status        | 子发运单数 | remark
DLV-20260812-6158  | MASTER      | PENDING_SPLIT | 0         | 销售订单 SO-20260812-0001 发货单（模板文案，无信息量）
DLV-20260823-8189  | MASTER      | PENDING_SPLIT | 0         | （空）
```

两张都是 `record_role=MASTER`、`parent_delivery_id` 为空、**没有任何子发运单（SHIPMENT）记录**——与任务描述一致：仓库侧 `GET /warehouse/deliveries/pending`（`WarehouseDeliveryController.listPending`，过滤 `DRAFT/PENDING_WAREHOUSE_CONFIRM/PICKED`）永远看不到它们，因为它们的状态是 `PENDING_SPLIT`，不在这三个值里。

### 两条路各自的具体操作步骤（不替 owner 拍板，只列证据+步骤）

**路径 A：补救（继续履约）**

1. 前端：销售订单详情页 → 该发货单 → "新建分批发运"按钮。
2. 后端实际调用：`POST /api/mobile/{factoryId}/sales/deliveries/{parentDeliveryId}/shipments`（`SalesDeliveryShipmentController.create` → `SalesServiceImpl.createDeliveryShipment`，需要权限 `sales:read_write`）。
3. 该接口会为母单下的明细创建一条新的子发运单，**新子单的初始状态硬编码为 `PENDING_WAREHOUSE_CONFIRM`**（`SalesServiceImpl.java:2890`）——这个状态**在**仓库待确认队列的过滤范围内，创建后会立刻出现在仓库的"待确认发货单"列表里，仓库即可正常走"确认实发数量→扣库存→出送货单"流程。
4. **`SO-20260812-0001` 的特殊限制**：如上所述，该单涉及的 SKU 已停用且零库存，"新建分批发运"这一步技术上做得到（接口本身不检查库存），但**仓库确认发货这一步会因为找不到可用批次而卡住**——需要先解决 SKU 归属问题（见上文第 5 点）。
5. `SO-20260823-0001` 没有这个障碍，现有库存直接够发。

**路径 B：取消**

1. 后端取消逻辑：`SalesServiceImpl.cancelOrder`（`backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/SalesServiceImpl.java:2357-2377`），受 `OrderUsageWhitelists.SO_CANCELLABLE` 白名单约束：
   ```java
   SO_CANCELLABLE = EnumSet.of(DRAFT, CONFIRMED, PENDING_FINANCE_REVIEW, FINANCE_REJECTED)
   ```
   （`backend/java/cretas-api/src/main/java/com/cretas/aims/domain/OrderUsageWhitelists.java:96-100`，注释明确写着"R39 BUG-8: 排除 FINANCE_APPROVED+，因为下游（生产计划/AR 发票）已提交，取消需要显式回滚流程而非状态翻转"）。
2. **两单当前状态都是 `FINANCE_APPROVED`，不在这个白名单里**——直接调用取消接口会收到 `409`：
   > "当前订单状态(财务已批准)不允许取消。财务批准后请通过驳回/退款流程处理"
3. 本次实测的证据（第 4、6 点：两单都**没有 AR 挂账、没有库存预留**）说明白名单的"下游已提交"这条假设**对这两单不成立**——技术上取消它们不会破坏任何已提交的下游状态。但目前代码库里我没有找到"驳回/退款流程"对应的具体功能入口（搜索范围内未见），也就是说**现状下这两单没有一键取消的标准路径**，需要走人工/管理员介入，或者产品侧补一个"FINANCE_APPROVED 且零下游占用"的例外取消通道。这一点建议 owner 拍板：是扩大白名单例外，还是补一条驳回流程，还是走一次性人工数据修正。

---

## 任务 B：prod 的 aistore 一直没有 key —— **premise 不成立，aistore 目前工作正常**

### ⚠️ 先纠正任务给定的前提

任务给的前提是"生产 `.env.prod` 里没有 `LLM_AISTORE_API_KEY`，所以 aistore 每次都会被跳过"。**这个前提部分为真、结论是假的**：

- `.env.prod` 里确实没有 `LLM_AISTORE_API_KEY` 这一行（`grep -in aistore /www/wwwroot/cretas/.env.prod` 0 命中）——**这部分是真的**。
- 但 key **另外存放在一个专用文件** `/www/wwwroot/cretas/.env.aistore`（`git blame`/mtime 显示 2026-08-13 创建），并通过一个 **systemd drop-in override** 加载：
  ```
  /etc/systemd/system/cretas-python.service.d/20-aistore.conf:
  [Service]
  EnvironmentFile=/www/wwwroot/cretas/.env.aistore
  ```
  这个 drop-in 不在基础 `.service` 文件里（`cat` 基础文件看不到），必须专门去看 `.service.d/` 目录才能发现。
- **实测当前跑着的进程（master pid 3501151，worker pid 3501159）的 `/proc/<pid>/environ` 里确实有 `LLM_AISTORE_API_KEY`（非空）**——两个进程都验证过。

代码里的注释其实交代了这个设计意图（`llm_router.py:1651-1653`）："Shanghai Telecom AI Store: OpenAI-compatible endpoint. The API key is production-secret-only; no fallback to another env name prevents an unrelated credential from being sent to this provider by mistake." —— 也就是说**把 aistore 的 key 单独放一个文件、不放进 `.env.prod`，是故意的隔离设计**，不是漏配。

### 1. `_build_chain` / `_SLOT_POOLS` 里 aistore 实际排位（不是看注释，是实际跑代码算出来的）

直接 `import common.llm_router` 后打印 `SLOT_MODELS`（今天日期 2026-08-28，此时 aistore 三个 SKU 到期日 2026-09-13 尚未到期）：

```
chat        -> [('aistore', 'DeepSeek-V4-Flash-A'), ('deepseek', 'deepseek-v4-flash'), ('zhipu', 'glm-4.5-air')]
simple_text -> [('aistore', 'Qwen3-32B'),            ('deepseek', 'deepseek-v4-flash'), ('zhipu', 'glm-4.5-air')]
insights    -> [('aistore', 'DeepSeek-V4-Flash-A'), ('deepseek', 'deepseek-v4-flash'), ('zhipu', 'glm-4.5-air')]
chart       -> [('aistore', 'DeepSeek-V4-Flash-A'), ('aistore', 'Qwen3-235B-A22B'), ('deepseek', 'deepseek-v4-flash'), ('zhipu', 'glm-4.5-air')]
mapper      -> [('aistore', 'DeepSeek-V4-Flash-A'), ('deepseek', 'deepseek-v4-flash'), ('zhipu', 'glm-4.5-air')]
reasoning   -> [('aistore', 'DeepSeek-V4-Flash-A'), ('deepseek', 'deepseek-v4-flash'), ('zhipu', 'glm-4.5-air')]
vl          -> []
review      -> [('aistore', 'DeepSeek-V4-Flash-A'), ('deepseek', 'deepseek-v4-flash'), ('zhipu', 'glm-4.5-air')]
```

aistore 是 **7 个非空槽（除 VL 外全部）的链首**——比任务描述的"5 个槽"还要多两个（`simple_text`、`reasoning` 也是）。当前 SLOT 枚举里已经没有单独的 `INSIGHTS`/`CHART`/`MAPPER`/`REVIEW` 之外的划分变化，任务描述的 5 槽应该是一个更早版本的槽名集合（另参考 git log 里 `45b7cf7bfd`「七槽统一 aistore→deepseek→zhipu」这次重构）。

### 2. 生产日志里有没有"API key 为空"的 warning

```bash
grep -c 'API key 为空' /www/wwwroot/cretas/python-prod.log        # 0
zgrep -c 'API key 为空' /www/wwwroot/cretas/python-prod.log-*.gz  # 全部 0（覆盖到 2026-06-04）
```

**没有**——从可查到的最早日志（6-04）到现在，这条 warning 一次都没出现过。反而日志里能看到大量 aistore **成功应答**的记录，例如：

```
2026-08-28 20:00:15,014 ... [llm_router] slot=insights OK via aistore/DeepSeek-V4-Flash-A
2026-08-28 20:00:31,619 ... [llm_router] slot=insights OK via aistore/DeepSeek-V4-Flash-A
2026-08-27 04:40:13,046 ... [llm_router] slot=review OK via aistore/DeepSeek-V4-Flash-A
```

今天（8-28）当前日志文件里统计：`OK via aistore` 命中 **121 次**，`OK via (aistore|deepseek|zhipu|aliyun_*)` 总命中 138 次——**aistore 占了今天全部成功应答的 87.7%**，是当前实际承载绝大多数流量的供应商，不是"每次都被跳过"的死配置。

### 3. 如果 key 真的为空，量的是纯 CPU 判断还是会先发一次网络请求（代码层面，与本案是否成立无关，供以后参考）

`call_chain` 里的检查顺序（`llm_router.py:2014-2017`）：

```python
base_url, api_key = _provider_config(account)
if not api_key:
    _note_empty_key("llm_router", account, errors)
    continue
```

`_provider_config` 只是 `os.getenv(...)` 读环境变量（`llm_router.py:1654-1657`），**在发起任何 `httpx` 请求之前**就会判断并 `continue` 到下一个候选。也就是说：**如果 key 真的是空的，这是一次零成本的纯 CPU 分支判断，不会发出任何网络请求**——不存在"每次调用都先白跑一次网络请求再超时/失败"的性能顾虑。`_note_empty_key` 本身还带一个"每进程每账号只 WARNING 一次"的去重（`_EMPTY_KEY_WARNED` 集合），避免刷屏。

### 4. 结论：这是"有意的"还是"漏配"？

**是有意的隔离设计，不是漏配，也没有故障。** 依据：

- key 存在（`.env.aistore` + systemd drop-in），且**已验证注入进了实际运行的进程环境**；
- 生产日志显示 aistore 今天正在正常应答，占比 87.7%；
- 从 6-04 至今全部可查日志里，"API key 为空"的 warning 一次都没触发过；
- 代码注释明确说明"不放进 `.env.prod`、不设回退到其他 env 名字"是刻意的凭证隔离（防止把不相关的 credential 误发给这个供应商）。

**唯一值得 owner 关注的点**：这种"关键供应商的 key 存放在一个不在 `.env.prod` 里、且只能通过 `systemctl cat`/查 `.service.d/` 目录才能发现的独立文件"的方式，**对下一个排查这个问题的人不友好**——如果不知道 `20-aistore.conf` 这个 drop-in 的存在，光看 `.env.prod` 会得出"key 没配"的错误结论（我自己在这次调查里也先被这个误导过一次，靠 `/proc/<pid>/environ` 才发现真相）。建议至少在 `.env.prod` 里留一行注释指向 `.env.aistore` 和对应的 drop-in 路径，避免下次同样的误判。

---

## 硬性规矩执行记录

- worktree: `../cretas-stranded`，分支 `codex/claude-stranded-deliveries`，off `origin/main`（`1f7acb3d84`）
- 动手前 `gh pr list` 检查：无冲突（#298/#297/#292/#285 均与本任务无关文件）
- 全程只读：数据库只跑 `SELECT`（经 `pq.sh`，脚本本身只允许 `psql -f`，未执行任何 DDL/DML）；服务器侧只读 `cat`/`grep`/`zgrep`/`ps`/`systemctl show`/`/proc/<pid>/environ`/`systemctl cat`，未重启、未改配置、未写文件（除本机 scratchpad 的查询脚本）
- 未碰 `known-failing-tests.txt`，未使用 `git stash`
