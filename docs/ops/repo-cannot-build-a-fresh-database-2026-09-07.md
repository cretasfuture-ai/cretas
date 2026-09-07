# 仓库现在建不出一个新数据库 —— 迁移历史是「发生过什么」，不是「怎么重建」

**票**: `CI-MIGRATION-GATE-READS-HISTORY-NOT-CURRENT-SCHEMA`
**日期**: 2026-09-07
**状态**: 🔴 **现在就成立的失败**，不是隐患。⛔ 未修（修法与代价见文末）
**被测树**: `origin/main` @ `345190f881`（⛔ 不含任何我的未合改动）

---

## 一句话

`scripts/testing/fresh-db-gate.sh` 真跑一次：**Flyway 在第 267 个迁移上死掉**。
prod 的 `flyway_schema_history` 里这条记着 `success=true` —— 因为在 prod 上那一刻，
它要 `ALTER` 的那张表**已经由 Flyway 之外的途径存在了**。

⇒ 迁移历史是一份**忠实的「发生过什么」记录**，但它不是一份**可重放的配方**。
而能发现这件事的那道闸，**没有任何 workflow 引用它**。

---

## 一、确凿的那一条（真跑出来的，不是推的）

```
FRESH_RC=1
Migration V20260827_01__settlement_defect_count_outcome.sql failed
SQL State : 42P01
Message   : ERROR: relation "production_settlements" does not exist
死掉时: 158 张表 / 538 索引 / 266 条迁移跑完
对照 prod: 502 张表 / 2044 索引 / 701 条历史
```

| 序列位置 | 迁移 | 干什么 |
|---:|---|---|
| **267** | `V20260827_01__settlement_defect_count_outcome.sql` | `ALTER TABLE production_settlements ADD COLUMN IF NOT EXISTS …` |
| **419** | `V20261024_04__production_settlement.sql` | `CREATE TABLE IF NOT EXISTS production_settlements` |

⚠️ `ADD COLUMN IF NOT EXISTS` 守的是**列**，表必须先在。
prod 上这两条的 `flyway_schema_history` 都是 `success=true`：baseline 是 `20260416.99`
（描述写着「262 legacy + 4 个手工应用」），第 267 位那一刻表已经在了。

---

## 二、更深的那一层：**29 张表，整个仓库里没有任何东西创建它们**

静态扫描全部 697 个迁移文件，按语句顺序追踪「谁建了哪张表」：

| 读数 | 值 |
|---|---|
| 迁移文件 | 697 |
| 迁移创建的表 | 293 |
| **引用了但迁移全程从没创建过的表** | **29**（去掉扫描器噪声 `public` / `_` 开头 5 个之后） |
| 其中在 `database/*.sql` 里有 CREATE 的 | 6 |
| 🔴 **仓库里任何地方都没有 CREATE 的** | **23** |
| 这 29 张在 **prod 上存在**的 | **28 / 28**（查得到的全部；1 张 `legacy_process_task_report_ids` 未查） |

**包括核心表**：`work_processes`、`whitelists`、`workflow_templates`、
`material_consumptions`、`encoding_rules`、`material_product_conversions`、
`purchase_receive_items`、`state_machines`、`sales_delivery_records` …

⇒ 这些表当年多半是 Hibernate `ddl-auto` 或未提交的脚本建的。
**仓库里没有它们的定义**，所以任何「从零重建」都拿不到它们。

**其中 14 张有「裸引用」**（引用它的迁移文件里没有 `to_regclass` 守卫）⇒
每一张都是修好第 267 条之后**下一个**会红的地方：

```
bom_process_injection_configs   disposal_records          internal_transfer_items
isapi_devices                   legacy_process_task_report_ids   material_consumptions
purchase_receive_items          sales_delivery_records    sales_order_prepayment_records_items
scale_brand_models              state_machines            whitelists
work_processes                  workflow_templates
```

另外 14 张被 `IF to_regclass(...) IS NULL THEN RETURN` 这类守卫挡着 ——
⚠️ 那说明**有人已经在一条一条打补丁**，但从没在根上修过。

---

## 三、仪器链与它的自证（⚠️ 这一节比上面的数字重要）

三个仪器，各自的失败方向不同，所以要互相校验：

| 仪器 | 性质 | 已知偏差 |
|---|---|---|
| **真跑一次 fresh-db-gate** | 地面真相 | 只告诉你**第一个**失败在哪 |
| **静态顺序扫描** | 代理 | **高报** —— 看不见 DO 块里的 `to_regclass` 守卫 |
| **反查守卫** | 代理 | 文件级，**高估守卫**（同文件里守的可能是另一张表） |

**静态扫描的自证**：它标出 3 处顺序缺陷，其中 **2 处有 `to_regclass` 守卫、1 处没有**——
而**没守卫的那一处正是实跑炸掉的那一条**。⇒ 「扫描 + 守卫检查」合起来精确预测了真实失败。

### 🔴 这一轮我自己造出的两个假读数，都被对照抓住了

1. **`ORDER_DEFECTS=1011`** —— 第一版在**整文件**粒度上先查引用再记创建，于是每个建表迁移
   都「引用了它自己那个文件里才建的表」。改成**按语句顺序**后是 5（3 处唯一）。
   ▎抓住它的是输出第一行长得荒谬（`V20260415_01` 指向 `V20260415_01`），⛔ 不是断言。
2. **`NEVER_CREATED_AND_UNGUARDED=0`** —— 反查循环里的 `grep` **少了 `-i`**，而迁移写的是
   大写 `ALTER TABLE` ⇒ 文件列表全空 ⇒ 每一张都「没有无守卫引用」。
   ▎抓住它的是**阳性对照**：我已知 `bom_process_injection_configs` 无守卫，它必须出现在名单里。
   没有那条对照，这个 `0` 会被写成「修好 267 就没别的问题了」——**方向完全相反的结论**。

---

## 四、为什么一直没人知道

```
$ grep -rl fresh-db-gate .github/
(空)
```

`scripts/testing/fresh-db-gate.sh` 只被它自己的测试和 `docs/testing/fresh-db-local-gate.md` 引用。
**闸在，没接上**（形态 B）。⇒ 这一族 2026-09-07 当天已经是第四次
（#713 `java-gate-pr.yml` / #723 `web-dist.yml` / #724 测试源目录 / 本条）。

⚠️ 另外，仓里**没有任何东西**把「从迁移新建的 schema」与「prod 的 schema」比对
（`pg_dump` 只出现在备份脚本里）。所以即使新库建得出来，二者的差距也仍然是未测量的。

---

## 五、修法与代价（⛔ 本文档没有动手）

1. **第 267 条**：把它对 `production_settlements` 的依赖前移，或给它加 `to_regclass` 守卫
   （后者与既有 14 处补丁同形，但那是**继续打补丁**，不是修根）。
2. **那 23 张仓库里没有定义的表**：要把它们的 `CREATE TABLE` 补进 bootstrap ——
   ⚠️ 定义只能从 **prod 的现状**反推（`pg_dump --schema-only`），而那正是本票说的
   「历史与现状脱节」的另一面：**我们要用现状去补历史**。
3. **接闸**：`fresh-db-gate.sh` 进 CI。ubuntu runner 有 Docker，成本是一次完整 Flyway 跑
   （本机实测约 6 分钟，其中大部分是编译 1641 个测试类；CI 上只跑 Flyway 会更短）。

⚠️ **代价诚实说**：第 1、2 条是**逐个试错**，每轮一次完整的 Flyway 跑。
我不知道要跑几轮 —— 上面那 14 张只是**下一批**，修完它们可能还有第三批
（守卫挡住的 14 张里，有些在守卫之外还有别的引用形式，我没有逐条验）。

---

## 复现

```bash
FRESH_DB_PORT=55440 FRESH_DB_APP_PORT=10029 FRESH_DB_PROJECT=<你的名字> \
  bash scripts/testing/fresh-db-gate.sh --keep
# 判据: FRESH_RC / 日志里 `Migration V... failed` 那一段, ⛔ 不看 "健康检查过了没"
```

⚠️ `FRESH_DB_PROJECT` 一定要改成自己的 —— 默认值会和别人的容器撞名，
而撞名的症状是**你以为在看自己的库**（同「同名制品静默替换」那一族）。

关联：`docs/testing/fresh-db-local-gate.md`、`scripts/testing/fresh-db-gate.sh`、
`.claude/rules/measurement-and-wiring.md`（形态 B / 硬约束 9）。
