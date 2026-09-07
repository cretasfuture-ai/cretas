# prod 的 `flyway_schema_history` 被人手工动过 —— 被编辑的不是迁移，是账本

**票**: `OPS-FLYWAY-HISTORY-TABLE-WAS-HAND-EDITED-20260907`
**日期**: 2026-09-07
**状态**: 🟡 **只登记 + 量清楚。⛔ 不要动 prod 的 `flyway_schema_history`** —— 那是台账，
改它就是在做我们刚发现别人做过的那件事。
**库**: `cretas_prod_db` @ 47.100.235.168，全程只读 `SELECT`

---

## 一句话

**Flyway 不会把同一个 version 应用两次。** 而 prod 上有 **9 个 version 各有两行 `success=true`**，
另有 **5 个 version 的文件在仓库里已不存在**。

⇒ 这张台账**被人手工插入/删除过行**。

---

## 读数

```
HISTORY_ROWS=700   CHECKSUM_MATCH=691   MISMATCH=0   FILE_MISSING=9
重复 version 总数 = 9
```

| installed_rank | version | script | installed_on |
|---:|---|---|---|
| 205 | 20260622.99 | `V20260622_99__notify_templates_partial_unique.sql` | 2026-05-22 08:13:32 |
| **208** | **20260622.99** | 同上 | 2026-05-22 09:50:22 |
| 207 | 20260822.99 | `V20260822_99__business_rules_partial_unique.sql` | 2026-05-22 09:35:34 |
| **220** | **20260822.99** | 同上 | 2026-05-22 18:43:58 |
| 609 | 20261029.55 | `V20261029_55__restaurant_manager_reads_hr.sql` | 2026-08-06 20:47:39 |
| **611** | **20261029.55** | 同上 | 2026-08-06 21:12:35 |

⚠️ **版本号在应用日期的未来**：`V20260622_99` / `V20260822_99` 都应用于 **2026-05-22**，
号却是 06-22 / 08-22 ⇒ 这是**手挑大号的 hotfix**，跑完即从仓库删掉。

## 🎯 它顺手解开了一个谜

那两个「只活在 prod 上」的部分唯一索引（今天由 `V20261101_39` 补进迁移的那两个）——
**原本就是由迁移创建的**，只是那两个文件后来被从仓库删掉了：

```
uq_notify_templates_factory_code_active  ← V20260622_99__notify_templates_partial_unique.sql
uq_business_rules_factory_code_active    ← V20260822_99__business_rules_partial_unique.sql
```

⇒ 性质不是「定义丢了」，是「**跑过之后被删了**」。
⇒ `V20261101_39` 的定位也随之改变：它不是「补一个从没有过的定义」，
而是**把一条被删掉的迁移重新落进版本控制**。

---

## ⚠️ 一个我先立后破的读数，留档

第一版我用 **git 历史**量「已应用迁移在 apply 之后有没有被改过」，得到 **648 / 700（93%）**。
**那是假的。**

```
4f8f09c051  2026-08-23T11:46:03+08:00  Initial company baseline
```

整个仓库 2026-08-23 之前的历史被 squash 成这一个 commit ⇒
**任何在那之前 apply 的迁移，唯一的 commit 都晚于 `installed_on`** ⇒ 全部被误判。

⚠️ 93% 这个数**与「几乎全被改过」长得一模一样**，而它量的是 squash。
⇒ **git 历史根本答不了这个问题。** 换成直接比对 Flyway 存的 checksum
（Flyway 的 CRC32 口径），并配阳性对照（最近应用的 5 条几乎不可能被改过，必须 match ⇒ 5/5 ✅），
才拿到真数：**MISMATCH=0**。

▎**换仪器，⛔ 不是复量。** 同一个错方法量十次给同一个错数，一致性会被读成可信。

## ⇒ 与另一张票的关系：那张票要降级

`OPS-APPLIED-MIGRATIONS-ARE-EDITABLE-WITH-VALIDATION-OFF-20260907` 的前提是
「编辑已应用迁移在本仓是一个有意识、成习惯的做法」。

**读数推翻了它**：`MISMATCH=0` ⇒ **零处实际使用**。
那 5 处注释（`validate-on-migrate=false → 编辑已 apply migration 不破 prod checksum 校验`）
给的是**许可**，但没有人用过。

📌 形状值得记：**一句写在注释里的许可，读起来像一个已经在发生的实践。**
⚠️ 它比一般的臆测更难察觉 —— 那 5 处注释是**真的**，措辞也准确，
错的只是从「可以」推到了「已经」，而**中间那一步没有任何读数**。

⇒ 那张票改写为「**一条被关掉的校验 + 一条书面许可，零实际使用**」并降级：
仍值得关（许可还在，下一个人会用），但**不是既成事实**。

---

## 重新打开 `spring.flyway.validate-on-migrate` 的真实代价

| 类别 | 会不会红 |
|---|---|
| checksum 不符 | **0 处** |
| **5 个 version 的文件已不存在** | 🔴 会 —— `Detected applied migration not resolved locally` |
| **9 个重复 version** | 🔴 会 |

⇒ ⛔ **不是改一个 property。** 要打开，得先处置这两类。

⚠️ 并且**别把它与另一个 `validate` 搞混**：`docs/decisions/2026-09-06-prod切validate可行性…`
讲的是 **Hibernate `ddl-auto=validate`**，机制与失败形态都不同。那份文档给的能力边界是：

> Hibernate validate 看：表存在 / 列存在 / 列类型。
> ⛔ **它不看**：唯一约束 · 唯一索引 · 普通索引 · 外键 · NOT NULL · DEFAULT · 多出来的表和列。
> 负对照：一次做掉 7 件 ⇒ `CONTEXT_STARTED`，**七件一件都没被点名**。

⇒ **⛔ 别把 Hibernate validate 当 schema 对账**，它对这条线关心的东西整个是瞎的。

---

## 对 baseline bootstrap（方向 B）的影响：全部有利

| | 方向 A（修历史） | 方向 B（画基线） |
|---|---|---|
| 5 个文件不存在的 history 行 | 必须找回或忽略 | **基线之下，不再解析** |
| 9 个重复 version | 必须**清理台账**（= 做别人做过的那件事） | 同上 |
| 重新打开 validate | 先处置这两类 | 基线之后可以干净地打开 |

---

## ⛔ 本票不做的

- **⛔ 不动 prod 的 `flyway_schema_history`**（重复行、缺文件的行都不动）。
- ⛔ 不打开 `validate-on-migrate`。
- ⚠️ **没有查是谁、什么时候、为什么手工动的账本** —— 只证明了「被动过」。
  要追责任链得看服务器操作记录，本轮没做。

关联：`docs/ops/repo-cannot-build-a-fresh-database-2026-09-07.md`、
`docs/ops/fresh-db-baseline-bootstrap-2026-09-07.md`、
`docs/decisions/2026-09-06-prod切validate可行性-零剩余失败-不切.md`。
