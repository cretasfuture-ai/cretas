# `fresh-db-progress-baseline.txt` 已改名 → `fresh-db-above-baseline-floor.txt`

**日期**: 2026-09-07 ｜ **票**: `OPS-FRESH-DB-BASELINE-BOOTSTRAP-20260907`

## ⚠️ 改名的理由是**语义变了**，⛔ 不是嫌名字不好听

名字是读者判断「这是不是同一个东西」的**唯一线索**。
沿用名字而换语义 ⇒ `git blame` 上会看到「同一个基线从 266 变成 502」，
**读起来像棘轮坏了**，而真相是它量的是另一件事。

▎这是「同名不是巧合，是一次静默替换」的**镜像** —— 那条讲的是「路径上已经有别的东西」，
这条是**我们自己制造一次同名替换**。⚠️ 而且更糟：那条至少有 `Write 返回 updated` 这种信号，
这条**一个信号都没有**。

## 两个口径

| | 旧：`fresh-db-progress-baseline.txt` | 新：`fresh-db-above-baseline-floor.txt` |
|---|---|---|
| 建库方式 | 从**史前史**开始重放全部迁移 | 灌 `ops/db-baseline/` 的 prod schema 基线 → `flyway baseline` → 只跑基线**之上**的 |
| 三个数的含义 | 「死在第 267 条**之前**能走多远」 | 「基线之上全部跑完之后的**形状下界**」 |
| 那条链能不能跑完 | ❌ 死在第 267 条（`production_settlements` 不存在） | ✅ **跑完了**，无失败点 |
| `migrations_applied` | 已重放的迁移条数 | ⛔ **不再设下界**（见下） |

## 🔴 跳变的那一刻：两个口径的数**并排**

⛔ 只留新值的话，「跳变」这件事本身就在记录里消失了。

```
旧口径最后一个值   migrations_applied=266   tables=158    indexes=538
新口径第一个值     (不设下界)               tables=502    indexes=2044
```

⚠️ **注意 `migrations_applied` 是 266 → 3，它是往下走的。**
三个数里**两个涨、一个跌** —— 而跌的那个在旧口径下与「真倒退」逐字同形。
（新库现在恰好是 prod 的形状：`502 / 2044`，与基线文件头逐位相同。）

### 为什么新口径里 `migrations_applied` 不设下界

它 = `baseline` 行 + 基线之上已跑的迁移条数。
**每次重新采集基线，这个数都会掉回 1**（基线往前挪 ⇒ 上面的迁移被吸收进去）。
⇒ 给它设下界 = **每次刷新基线都要红一次**，而那次红与「真的少跑了迁移」逐字同形。

▎**会被正常操作触发的闸，迟早被关掉。**

⇒ 它只报数。真正承重的两条是：**失败迁移数必须为 0**（pass/fail，⛔ 不是棘轮）
与**形状下界只许涨**。

## 关联

`scripts/testing/fresh-db-gate.sh`、`docs/ops/fresh-db-baseline-bootstrap-2026-09-07.md`（第五节）、
`scripts/tests/test-fresh-db-progress-ratchet.sh`。
