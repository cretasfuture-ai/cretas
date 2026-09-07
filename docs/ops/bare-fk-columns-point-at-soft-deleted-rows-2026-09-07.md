# 裸 FK 列指向已软删的行 —— 现有孤儿扫描器**结构上**看不见的那一片

**票**: `OPS-BARE-FK-COLUMNS-POINT-AT-SOFT-DELETED-ROWS-20260907`
**日期**: 2026-09-07
**状态**: 🟡 只登记形状与证据。**没有修，也不会造那道闸** —— 产品口径已于 2026-09-07 裁定「指向已软删父行本身合法」（见「产品口径已裁定」一节，含失效条件）。剩余工作收窄成两步
**库**: `cretas_prod_db` @ 47.100.235.168，全程只读 `SELECT`

---

## 一句话

`scripts/check-soft-delete-orphans.sh` 按 **Java `@ManyToOne` 关联**枚举扫描面，而本仓大量
外键是**裸 `@Column String xxxId` 列**（没有 Java 关联，也没有数据库 FK 约束）。
裸列没有代理可解析 ⇒ 不会抛 `EntityNotFoundException` ⇒ **扫描器按设计看不见它们**，
而它们的失败形态更安静：不是 500，是查出来的父行**不存在**。

实测：**20 对**裸列当前指着已软删的父行，其中 **10 对**的子行自己还活着；
与扫描器覆盖的 62 条关联**交集为 0**。

---

## 已有机制的边界（先说清楚它没坏）

`scripts/check-soft-delete-orphans.sh` 的判据写得很清楚，是两个条件的**交集**：

- 条件 A：关联会被序列化（没有 `@JsonIgnore`）—— 有 `@JsonIgnore` 的代理根本不会被解析，永远不炸；
- 条件 B：真的有行指向已软删的目标。

这个交集是**对的**：它守的是 2026-08-21 那次「软删 26 个账号 ⇒ 客户列表整个 500」
（`EntityNotFoundException: Unable to find … User with id 1610`）。⛔ 本文档不建议放宽它。

**但条件 A 的前提是「有一个 Hibernate 代理要解析」。** 裸列没有代理，
于是它既不满足 A，也不会以那种方式炸 —— 它落在这套判据的**定义域之外**，
不是「漏了」，是「量的不是同一件事」。

---

## 读数

### 1. 面有多大（`information_schema` + `pg_constraint`）

| 读数 | 值 |
|---|---|
| `public` 下以 `_id` 结尾的列 | **1274** |
| 带数据库 FK 约束的列（`contype='f'`，含非 `_id` 列） | 242 |
| **裸 `_id` 列**（无 FK 约束） | **1064** |
| 其中按命名约定能解析到一张真实父表 | 628 |
| 且该父表带 `deleted_at` 列 | **627** |
| 且父子两张表都非空（`reltuples <> 0`） | **319**（本次实际扫的对数） |

⚠️ **「按命名约定解析父表」是代理判据**（`base+'s'` / `base+'es'` / `y⇒ies` / 原形四选一）。
猜不中的对**落在集合外**，所以 319 是**下界**，⛔ 不是完备扫描。
用 `reltuples <> 0`（而不是 `> 0`）是因为 PG14+ 里 `-1` 表示「从没 analyze 过」——
写成 `> 0` 会把没统计过的表当成空表悄悄排除掉。

### 2. 真的指着已软删父行的对（20 条，`n_total > 0`）

判据：`FROM 子表 ch JOIN 父表 pa ON pa.id::text = ch.<col>::text WHERE pa.deleted_at IS NOT NULL`。
`n_child_alive` 额外要求 `ch.deleted_at IS NULL`（子表没有软删列时记为全部）——
⛔ 不过滤子表的话，会把**本来就已经软删的行**也数成坏行（这正是 08-21 那次栽过的第二个坑）。

| 子表 | 列 | 父表 | n_total | **n_child_alive** |
|---|---|---|---:|---:|
| indicator_computations | indicator_id | indicators | 10 | **10** |
| indicator_thresholds | indicator_id | indicators | 9 | **9** |
| sales_order_shortage_report | sales_order_id | sales_orders | 3 | **3** |
| product_unit_conversions | product_type_id | product_types | 3 | **3** |
| bom_recipes | product_type_id | product_types | 16 | **2** |
| product_process_workflow_revisions | product_type_id | product_types | 3 | **1** |
| product_process_workflows | product_type_id | product_types | 3 | **1** |
| product_process_workflow_activations | product_type_id | product_types | 2 | **1** |
| production_settlement_output_lines | finished_goods_batch_id | finished_goods_batches | 1 | **1** |
| production_settlements | finished_goods_batch_id | finished_goods_batches | 1 | **1** |
| production_batches | production_plan_id | production_plans | 15 | 0 |
| production_input_allocations | production_plan_id | production_plans | 11 | 0 |
| production_batches | product_type_id | product_types | 11 | 0 |
| bom_seasoning_items | work_process_id | work_processes | 6 | 0 |
| work_process_tasks | product_type_id | product_types | 4 | 0 |
| work_process_tasks | work_process_id | work_processes | 4 | 0 |
| expense_requests | payment_record_id | payment_records | 2 | 0 |
| ar_ap_transactions | purchase_order_id | purchase_orders | 1 | 0 |
| purchase_receive_items | purchase_order_item_id | purchase_order_items | 1 | 0 |
| factory_material_requisitions | production_plan_id | production_plans | 1 | 0 |

后 10 对的子行自己也已软删 ⇒ 优先级低（父子一起被删掉，通常是正常的级联结果）。

### 3. 与已有扫描器的交集 = 0（**带阳性对照**）

```
命中对 20 条; 扫描器覆盖 62 条; 交集 0 条
```

⚠️ 「交集 0」这个读数最容易是**我的匹配式压根匹配不上**造成的。阳性对照：
拿 `scripts/cron/soft-delete-orphans.sql` 里它**自己的**第一条
（`batch_equipment_usage.batch_id`）用同一个匹配式去查，`hits=1` ⇒ 匹配式有效，
0 是真的 0。

### 4. Java 侧抽样：它们确实是裸列，不是带 `@JsonIgnore` 的关联

「交集 0」有两种成因，是两张不同的票：(a) Java 侧根本没有关联；(b) 有关联但带 `@JsonIgnore`
（那样它就是扫描器**故意**排除的，不该重复登记）。抽 5 个 `n_child_alive > 0` 的看：

| 实体 | 映射 |
|---|---|
| `IndicatorComputation` | `@Column(name="indicator_id", nullable=false, length=191) private String indicatorId;` |
| `IndicatorThreshold` | `@Column(name="indicator_id", nullable=false, length=191) private String indicatorId;` |
| `BomRecipe` | `@Column(name="product_type_id", nullable=false, length=50) private String productTypeId;` |
| `ProductUnitConversion` | `@Column(name="product_type_id", nullable=false, length=100) private String productTypeId;` |
| `ProductionSettlement` | `@Column(name="finished_goods_batch_id", length=191) private String finishedGoodsBatchId;` |

5/5 是**纯 `@Column` 字符串列**，没有 `@ManyToOne`。⇒ 成因是 (a)，登记成立。

---

## 失败形态：为什么它比那个 500 更难发现

| | `@ManyToOne`（扫描器守的那一片） | 裸列（本票） |
|---|---|---|
| 触发 | Hibernate 解析代理 | 业务代码拿着 id 去查 |
| 症状 | `EntityNotFoundException` ⇒ **接口 500** | 仓储带 `@Where(deleted_at IS NULL)` ⇒ **查出空** |
| 谁会注意到 | 用户当场看到报错、日志有栈 | 页面少一行 / 字段为空 / 汇总少算 |
| 现有监控 | `soft-delete-orphans.sql` cron | **无** |

⚠️ `BomRecipe` 这类还把 `product_name` **冗余存了一份**（`@Column(name="product_name")`），
所以界面上**看起来是好的** —— 名字照常显示，只有真正按 `product_type_id` 去查的那条路径拿不到东西。
这正是「失败长成面板空着」那一族。

---

## 我**没有**建立的东西（⛔ 别把本文档当成结论）

1. **每一对的用户可见后果未验证**。上表证明的是「这些行指着已软删的父行」，
   ⛔ 不是「这 10 条各自导致了某个具体的界面缺陷」。要下那个结论，得逐条走业务链
   （视觉验收），本轮没做。
2. **319 是下界**。命名约定猜不中的父表（复数不规则、跨模块命名）全部落在集合外。
3. ~~**没有区分「合法的历史引用」**~~ —— ✅ **2026-09-07 已裁定**（见上一节）：
   指向已软删父行**本身是合法的**，缺陷在「取不到名字 / 取不到时显示空白」。
   当时写的猜测（`production_batches.production_plan_id` 指向已归档软删的旧计划**本来就该保留**，
   与 15 条 `n_child_alive=0` 一致）被裁定证实了 —— ⚠️ 但那是**事后**证实：写它的时候
   它只是一个说得通的解释，⛔ 当时不该当结论用，现在也不该因为猜对了就以为方法对了。
4. **没有查 `deleted_at` 是谁写的**。08-29 有人在 prod 手工多表 DELETE，
   与这些行有没有关系，本轮没有追。

---

## 为什么这次不顺手补一道闸

按形态 E（宁可窄而可信，不要宽而被关掉）：现在能写出来的闸只能基于**命名约定**这个代理判据，
而它的误报来源有两类且都不小 ——「合法的历史引用」（第 3 条）和「猜错父表」（第 2 条）。
一道 20 行里有若干条是设计如此的告警，会在第一周被关掉，然后**比没有更糟**（形态 D 的 D′：
一个会误报的提示先打在最不重要的地方，看的人查完没问题，从此不再信）。

⇒ 先要的是**产品口径**：哪些列的历史指向是合法的。拿到之后再决定闸的形状。

📌 **那个口径当天就拿到了 —— 见下一节。** ⛔ 别停在这一句上：本节写的是**当时**的状态，
结论已经被下一节取代（不造闸这一点没变，但理由从「判据不可信」变成了「那些行本来就是对的」）。

---

## ✅ 产品口径已裁定（2026-09-07）—— 结论是**这道闸不该造**

**裁定**（Steve 交由 organizer 定）：

| 现象 | 裁定 |
|---|---|
| 子行指向**已软删的父行** | ✅ **合法，不是缺陷，⛔ 不告警** |
| 取父行名字时被 `@Where` 过滤成**空** | 🔴 **这才是缺陷** —— 用户看到空白 |
| 取不到时**显示空白** | 🔴 **也是缺陷** —— 应显示「已删除」，而不是什么都没有 |

**背书**：本仓已有同侧实测（软删批 3 census）——7 处 `@Formula` 按 id 取客户/供应商名字填进
历史单据，**必须不加 `deleted_at` 过滤**，否则客户一被软删，老单据上的名字就变成空。
⇒ 这条区分是「**显示历史 ≠ 查询现状**」，本文档这批裸 FK 落在**同一侧**。

⇒ **⛔ 不造那道孤儿闸。** 前一节「误报两类都不小、会在第一周被关掉」的判断成立；
有了口径之后更清楚：**那 20 对里大部分本来就是对的**，告警它们等于制造噪声。

### 这条裁定的失效条件

⚠️ 哪天产品口径改成「**删掉的客户不该出现在老单据上**」，本裁定作废，
那 7 处 `@Formula` 与本文档这 20 对要一起重新过。
（同 `uk_encoding_rules` 那条良性判定一样：**判定要带它自己的作废条件**，
否则下一个人读到的是一条不知何时已经过期的结论。）

### ⇒ 剩下的工作收窄成两步，⛔ 不要扩

1. **那 10 对「子行自己还活着」的**，逐对回答一个问题：这个字段是**拿来显示**的，
   还是**拿来做当前查询**的？
   - 显示 ⇒ 取值路径必须能读到已删父行（用 including-deleted 的查询），取不到显示「已删除」
   - 查当前 ⇒ 才是真缺口
   ⚠️ 判据必须是**用户看得见的读数**，⛔ 不是「代码里长什么样」。
   最大那对 `indicator_computations.indicator_id → indicators`（10/10）先答：
   **它在界面上出现吗？出现在哪一屏？** 不出现就登记为「不影响用户」，优先级降到最低。
2. **剩下 10 对（子行自己也已软删）** ⇒ 两边都是历史，**登记即可，⛔ 不动。**

---

## 复现（只读）

生成器与扫描 SQL 都是纯 `SELECT`，不写库。步骤：

1. 用本文「读数 1」那段 CTE 生成每对的计数 SQL（`format(...)` + `string_agg`）；
2. `psql cretas_prod_db -Atf` 跑生成器，输出重定向到文件；
3. 把输出包一层 `SELECT * FROM ( … ) s WHERE n_total > 0 ORDER BY n_child_alive DESC` 再跑。

⚠️ 传文件到服务器之前**先比对两侧 md5** —— 本轮第一次就因为 `/tmp` 下有一个**同名旧文件**
（8-21 那次扫描留下的），差点拿一份我没写的 SQL 去跑 prod（只读，无副作用，但流程是错的）。
判据：`md5sum` 两侧一致再执行，⛔ 不靠「我刚写过所以它一定是我的」。

关联：`scripts/check-soft-delete-orphans.sh`、`scripts/cron/soft-delete-orphans.sql`、
`.claude/rules/measurement-and-wiring.md`（形态 A 的代理判据 / 形态 E 的闸存活）。
