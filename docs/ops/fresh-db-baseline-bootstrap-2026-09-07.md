# 新库用 baseline bootstrap，不再重放史前史

**票**: `OPS-FRESH-DB-BASELINE-BOOTSTRAP-20260907`
**日期**: 2026-09-07
**状态**: 🟡 方向已定（organizer 拍板走方向 B），**⛔ 尚未生成任何基线**。本文件是判据与禁令。
**取代**: `OPS-FRESH-DB-CANNOT-BE-BUILT` 的修法部分（⛔ 那份分类**留着不删**，见文末）

---

## 🔴 第一段：这个库里任何按名字数的东西，都必须带 schema 过滤

prod 上有 **7 个 schema**，不是 1 个：

```
public, tenant_purge_68, f006_clear_71, legacy_retired,
bak_trf_20260809_1790, bak_e2e_trf_20260809_2699, bak_e2e_trf_20260809_8194
```

后 6 个是 purge / 备份残留。⇒ `pg_dump` **⛔ 不带 `-n public` 会把它们一起烙进基线**。

⚠️ 而且这不是个别失误。同一形状已经咬过一次：某次 census 的 `count(*)` **没带
`table_schema='public'`**，把 `tenant_purge_68` 里的同名列数进去读出 1，按 public 过滤是 0
（见 `docs/decisions/2026-09-06-prod切validate可行性…`）。

▎**这个库里任何按名字数的东西都必须带 schema 过滤。** 这句比「记得加 `-n public`」通用。

---

## 一、为什么换方向：那个 P0 不是被修好，是不再存在

| | 方向 A：修历史 | **方向 B：画基线** |
|---|---|---|
| 工作面 | 现有 baseline `20260416.99` **之上 684 条**要重放 | 新基线之上 **0 条** |
| 第 267 条（`production_settlements` 不存在） | 逐个试错修 | **落在新基线之下 ⇒ 不跑** |
| 两颗雷（`fix_f001` / `fix_f006` 会在 prod 执行） | 必须单独处置 | 同上，出局 |
| 6 条数据态守卫 / 6 个撞号 / MySQL 语法 | 逐类处置 | 同上，出局 |

⚠️ **684 与 0 是两个口径**，⛔ 别混：684 是「对**现有** baseline」，0 是「对**新** baseline」。
支撑方向 B 的是后者。

### 「丢掉重建历史某一时刻的能力」——**那个能力今天并不存在**

两条独立来源：

1. 今天从零重建**死在第 267 条** ⇒ 第 267 条之后的任何历史时刻都重建不出来；
2. `db/manual-rollback/` 的 20 个回滚脚本**只被契约测试与文档引用，没有任何 runner 执行它们**。

⇒ 方向 B 拿走的不是一个现有能力。⚠️ 但这是**仓内**的结论 —— 没搜服务器上的运维脚本与个人习惯。

---

## 二、可行性读数（2026-09-07 实测）

```
pg_dump --schema-only --no-owner --no-privileges -n public   (只读, 1.37 MB, stderr 空)
两侧 md5 一致后才执行 ← ⛔ 同名文件静默替换那次的教训
空 pg17 上重放: rc=0, ERROR 行数 = 0
```

**⛔「没报错」不是判据。** 五个维度与 prod 逐位比对：

| | 重放出的新库 | prod |
|---|---:|---:|
| tables | **502** | 502 |
| indexes | **2044** | 2044 |
| unique 约束 | **181** | 181 |
| FK 约束 | **227** | 227 |
| columns | **8812** | 8812 |

⚠️ 只比表和索引不够：**唯一约束 / FK / 列数不对得上时，那个「对」是假的** ——
而这条线整晚谈的正是唯一约束与索引。

### 🔴 阴性对照（没有它，「502/2044 对上了」与「我根本没在数」读数相同）

```
从 dump 里删掉 1 条 CREATE INDEX (idx_notification_factory_role)
⇒ rc=0, ERROR 行数 = 0        ← 重放照样「成功」
⇒ tables=502 (不变)  indexes=2043 (**恰好少 1**)  被删那条 count=0 (点名得出来)
```

⇒ 它同时证明两件事：计数器真的在数；**少一个索引的重放依然零错误** ——
这就是「没报错」不能当判据的实物证据。

### ⚠️ 一个容易走错的代替品

那份 dump 里有 **1253 条 `CREATE INDEX`** 语句，而 `pg_indexes` 数出 **2044** 个索引
（差额是主键/唯一约束自动建的）。
⇒ **⛔ 不能用「数 CREATE INDEX 语句」代替「查 `pg_indexes`」**，两者天然不等。

### 独立佐证：应用能在它上面起来

`docs/decisions/2026-09-06-prod切validate可行性…` 里，与 prod **同 sha1** 的 jar 在一份
prod schema-only 副本上起 `validate` ⇒ `CONTEXT_STARTED`（71.2s）。
⇒ 不只 schema 重放得出来，**应用在它上面能起来**。

---

## 三、⛔ 两条禁令

### 禁令 1：基线**不是**一条 Flyway 迁移

**⛔ 不要把 schema dump 命名成 `V…__baseline.sql` 放进 `db/flyway/`。**

prod 的 `flyway_schema_history` 里没有它 ⇒ **prod 下次部署会把它当成一条新迁移执行**，
在一个已经有 502 张表的库上重跑整份建表脚本。

⚠️ 而且失败形态很坏：dump 里满是 `CREATE TABLE IF NOT EXISTS`，在已有库上多半是
**大部分语句静默 no-op、少数报错** ⇒ 读起来像「一次有点吵但成功的部署」。

正确形状：

```
新库 = ① psql 灌 schema dump          （Flyway 完全不参与）
       ② flyway baseline -baselineVersion=<dump 对应的最高已应用版本>
       ③ flyway migrate                （只跑 baselineVersion 之上的）
prod = 一个字都不动                    （它的 history 已经有那 697 行）
```

### 禁令 2：dump 文件放 `db/baseline/`，**⛔ 不许进 `spring.flyway.locations`**

⚠️ **禁令写在注释里挡不住** —— 今天这条已经用过三次。⇒ 要**写成闸**：

- `db/baseline/` 下的文件名 ⛔ 不许匹配 `V*__*.sql`
- `spring.flyway.locations` ⛔ 不许含 `baseline`

---

## 四、生成基线的三条判据（⛔ 手工 dump 一次不算数）

1. **可复现**：脚本化，跑两次产出的 dump **除下述归一化外逐字相同**。
   🔴 ⚠️ 实测：**两次 `pg_dump` 不是逐字相同 —— 但差异只有 2 行**，且**不是时间戳**：

   ```
   \restrict   zhWkbmwk4TJSs0g5CSzwluRhEL7K8wNnX97qoOI8esnY5mWs0uk2iW3mDT3GB9Q
   \unrestrict …                     ← PG17 每次生成的**随机 token**
   其余 42959 行逐字相同
   ```

   ⇒ **判据必须先归一化掉这两行再比**，否则它**恒假**，会把一个其实可复现的做法误判成不可复现。
   ⛔ 别写成「除时间戳外相同」——差的不是时间戳。

2. **可验证**：落盘即在空库上重放一次，**打出那五个数**并与当时的 prod 逐位比对；
   不等就**拒绝落盘**。⛔ 不是「跑完没报错」。

3. **带出处**：文件头记 prod 的 `flyway_schema_history` 最大版本 + 采集时刻 + 那五个数。
   ⇒ **基线的「新鲜度」必须是一个可读的数**，⛔ 不能靠 git 提交时间猜。

---

## 五、⚠️ 预告：进度棘轮的语义将来会变

`scripts/testing/fresh-db-gate.sh --progress` 现在的基线是 **266 / 158 / 538**，
它量的是**方向 A 的进度**（从史前史开始能走多远）。

基线 bootstrap 落地后，它量的会变成「**基线之上那部分能不能跑完**」⇒ 三个数会**跳变**
（大概率一次性跳到接近 502 / 2044）。

⇒ 到时候**必须重置那份 `fresh-db-progress-baseline.txt`**，并在同一个 PR 里说明为什么跳。
⚠️ **这件事现在就预告**，⛔ 不能等到那天有人看见数字跳变才去查 —— 一个没解释的跳变
与「棘轮坏了」读数相同。

---

## 六、`OPS-FRESH-DB-CANNOT-BE-BUILT` 的处置

⇒ 修法部分**被本票取代**：⛔ 不再修第 267 条。

那份文档里的 A/B/C/D 四类分类、两颗雷、6 条数据态守卫、6 个撞号 ——
**全部登记为「在新基线之下，不再需要处置」，但⛔ 留着不删**：
万一将来要走方向 A，那份分类是现成的，重做一次要一整天。

关联：`docs/ops/repo-cannot-build-a-fresh-database-2026-09-07.md`、
`scripts/testing/fresh-db-gate.sh`、`.github/workflows/fresh-db-progress.yml`。
