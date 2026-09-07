# 新库用 baseline bootstrap，不再重放史前史

**票**: `OPS-FRESH-DB-BASELINE-BOOTSTRAP-20260907`
**日期**: 2026-09-07
**状态**: 🟢 **第一份真基线已产出**（2026-09-07，`ops/db-baseline/prod-schema-baseline.sql`，
采自 prod@`20261101.37`，四条判据全过 —— 见第八、九节）。⚠️ **bootstrap 尚未接进建库流程**
（`flyway baseline -baselineVersion=20261101.37` 那一步还没接），所以第五节预告的棘轮跳变**还没发生**。
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
| 两颗雷（`fix_f001` / `fix_f006` 会在 prod 执行） | 必须单独处置 | 🔴 **不出局** —— 见下 |
| 6 条数据态守卫 / 6 个撞号 / MySQL 语法 | 逐类处置 | 同上，出局 |

🔴 **⛔ 那两颗雷不因为换方向而消失。** 基线换掉的是「新库怎么建」，
⛔ 不是「那两个文件不会跑」——后者取决于**没有人动 `spring.flyway.locations`**，
而那是**一行配置的距离**。它们的版本号高于 prod baseline 且不在 prod 的 history 里，
⇒ 谁把死目录接进 `locations`，它们就会在 prod 上执行。
⇒ 已登记为 `FRESH_DB: skip` 集合的头两个成员。⚠️ 这一条我在旧文档的指针里第一版写错了
（写成「一次性出局」），⛔ 在这里也订正。

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
空 pg17 上重放: rc=0, 错误 1 条(已知良性, 见下)
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
⇒ rc=0, 非预期错误 0 条        ← 重放照样「成功」
⇒ tables=502 (不变)  indexes=2043 (**恰好少 1**)  被删那条 count=0 (点名得出来)
```

⇒ 它同时证明两件事：计数器真的在数；**少一个索引的重放依然零错误** ——
这就是「没报错」不能当判据的实物证据。

### 🔴 ⚠️ 订正：上面那个「ERROR 行数」，我原来根本没量到

采集器与验证器里数错误用的是 `grep -c "^ERROR:"`。**它恒为 0** ——
psql 把错误打成 `psql:/baseline.sql:38: ERROR:  …`，**行首是 `psql:`**，`^ERROR:` 一条都匹配不到。

```
旧判据 grep -c "^ERROR:"   ⇒ 0     ← 上线起就是这个值, 与实际无关
真实   grep -c  "ERROR:"   ⇒ 1     ← psql:/b.sql:38: ERROR: schema "public" already exists
```

▎**一个永远等于「没问题」的读数，比没有这个读数更坏 —— 它占着位置，**
而我拿它当过好几次证据（「重放零错误」）。⚠️ **结论没变**（五维逐位比对才是真正承重的那条），
但**支撑它的那一半是空的**。

⇒ 已修成 `total / benign / unexpected` 三个数，并配一条**阳性对照**：
故意灌一条 `SELECT 1/0`，计数器必须真的读到 1 —— ⛔ 没有它，修完的计数器与修之前一样不可信。

```
READING: BASELINE_REPLAY_ERRORS total=1 benign_public_schema=1 unexpected=0
```

⚠️ 那 1 条良性错误是 dump 第 38 行的 `CREATE SCHEMA public;`（新库上 public 天然存在）。
🔴 **⛔ 不要用「先 `DROP SCHEMA public` 再灌」去消它** —— 实测那会 **CASCADE 掉 `vector` 扩展**：

```
错误 1 → 56 条,  表 502 → 498
```

▎**消掉一条已知错误的办法，可能比那条错误糟得多。**

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

⚠️ **禁令要带失败形态，⛔ 不只带结论** —— 只写「不许把它放进 `db/flyway/`」的话，
下一个人会自己估一个后果，而他估的多半比真实的轻（「也就是多跑一次建表，能有多糟」）。
📌 这条纪律今天已经用过两次（`--adopt` 禁令、本条）。

### 禁令 2：dump 文件放**仓库根的** `ops/db-baseline/`，⛔ 不放 `src/main/resources/`，且 ⛔ 不许进 `spring.flyway.locations`

⚠️ **禁令写在注释里挡不住** —— 今天这条已经用过三次。⇒ 要**写成闸**：

- `ops/db-baseline/` 下的文件名 ⛔ 不许匹配 `V*__*.sql`
- `spring.flyway.locations` ⛔ 不许含 `baseline`
- 🔴 `src/main/resources/` 下 ⛔ 不许有 pg_dump 产物（按内容特征判）—— **第九节记着它为什么是三个后果，不是一个**

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

   🔴 ⚠️ **而如果字节不稳定，⛔ 千万不要把 dump 排序后落盘。** schema dump 是**有序的**
   （建表要在建 FK 之前、依赖类型要先建）—— **排序会破坏可重放性，而它照样「没报错」地
   产出一个坏基线**，正是本文档上面那条阴性对照证明的失效形态。
   ⇒ 文件保持 `pg_dump` 原样输出；需要读 diff 时用**只在比较那一刻排序的视图**，⛔ 不改落盘内容。
   ⚠️ 并且：**任何对 dump 的加工（规范化、去注释、改顺序）之后，必须重跑一次五维验证** ——
   ⛔ 不许「我只是排了个序，形状不会变」。

3. **带出处**：文件头记 prod 的 `flyway_schema_history` 最大版本 + 采集时刻 + 那五个数。
   ⇒ **基线的「新鲜度」必须是一个可读的数**，⛔ 不能靠 git 提交时间猜。

---

## 五、✅ 跳变发生了：棘轮改名 + 换判据（2026-09-07）

第一版这一节是**预告**。⇒ 现在它发生了，把实际读数记在这里。

### bootstrap 接进建库流程之后，这条链**跑完了**

```
READING: BASELINE_BOOTSTRAP  version=20261101.37 tables_after_load=501 indexes_after_load=2042 history_table_dropped=yes
READING: BASELINE_LOAD_SHAPE tables=501 expected=501   (= 头里 502 减去我们删掉的 flyway_schema_history)
READING: FRESH_DB_SHAPE      migrations_above_baseline=3 tables=502 indexes=2044
READING: FRESH_DB_FAILURE    file=<none> sqlstate=<none>      ← 🎉 **没有失败点**
```

⇒ 新库的形状 **`502 / 2044`，与 prod 逐位相同**。

### 🔴 跳变的两个口径**并排**（⛔ 只留新值的话，跳变本身就消失了）

```
旧口径最后一个值   migrations_applied=266   tables=158    indexes=538
新口径第一个值     (⛔ 不设下界)            tables=502    indexes=2044
```

⚠️ **`migrations_applied` 是 266 → 3，它往下走。**
三个数里**两个涨、一个跌** —— 而跌的那个在旧口径下与「真倒退」逐字同形。

### ⇒ 改名，⛔ 不是加个后缀

`fresh-db-progress-baseline.txt` → **`fresh-db-above-baseline-floor.txt`**
（旧名留 `fresh-db-progress-baseline.RENAMED.md`，两个口径与并排数字都在那里）。

▎**语义变了就改名** —— 名字是读者判断「这是不是同一个东西」的唯一线索。
沿用名字换语义 = **我们自己制造一次同名替换**，而且比那次事故更糟：
那次至少有 `Write 返回 updated` 这种信号，这次**一个信号都没有**。

### 判据也换了形状 —— 🔴 **三个读数三种形式，理由各不相同**

▎**闸的类型不是闸的属性，是每个读数各自的属性。**
📌 「这道闸该不该转成 pass/fail」**这个问法本身是错的** —— 它假设一道闸只有一种形式。

**⛔ `tables` / `indexes` 永远不该转 pass/fail**：新库 = 基线 + 基线**之上**的迁移
⇒ 每一个加表加索引的 PR 都让它们**合法地变**
⇒ 对固定值做 pass/fail = **红在每一次正常的 schema 演进上**。

⚠️ 棘轮唯一的边界：某条迁移**合法地删表删索引**时它会红 ——
那时要**有意识地下调下界并说明**，⛔ 不是自动跟随。

▎`tables/indexes` 不转 pass/fail 与 `migrations_*` 不设界，用的是**同一句**：
**会被正常操作触发的闸，迟早被关掉。**

### 📌 顺带一条一般形式：**先写出失效长什么样，再选判据**

灌完基线原本只断言 `tables >= 400`。**那是个下界，它挡不住「少 4 张」** ——
而 `DROP SCHEMA public` 那条歧路的实际表现正好是 **498**。

⇒ **下界只对「灾难」（少几百张）有效**，而这里的失效是**偏差**（少 4 张）。
⛔ 不要先挑一个「看起来稳妥」的下界。▎**失效形态决定判据形状。**


| | 旧 | 新 |
|---|---|---|
| `migrations_*` | 棘轮（只许涨） | ⛔ **不设下界** —— 每次重新采集基线它都掉回 1 |
| `tables` / `indexes` | 棘轮 | 棘轮（下界 502 / 2044） |
| 失败迁移条数 | 无 | 🔴 **pass/fail，必须为 0** |

⚠️ `migrations_*` 那条的理由：**会被正常操作触发的闸，迟早被关掉。**
给它设下界 ⇒ 每次刷新基线都红一次，而那次红与「真的少跑了迁移」逐字同形。
⇒ 闸里配了一条**反向断言**：下界文件里出现 `migrations_*` 就红（防止有人好心加回来）。

### 🔴 顺带查出：这道闸一直在量一个**没有任何部署会产生**的库

第一次跑通时 `indexes=2119`，而基线 2044 + 上面两条迁移应当是 2044（那 4 个索引 prod 早就有，
`IF NOT EXISTS` 全是 no-op）⇒ **多出 75 个**。

成因：闸跑 `pg` profile，那里 `ddl-auto=update`；**而 prod 跑 `pg-prod`，那里是 `none`**。
⇒ 那 75 个是 **Hibernate 启动时自己补的**。

⚠️ 后果不是「多几个索引」：本闸问的是「**迁移**能不能把库建出来」，
而 Hibernate 会在迁移之后**替它把缺的补上** ⇒ **一条缺失的迁移会被悄悄盖住，读数照样漂亮**。
▎**让被测对象自己修补自己，这个测量就不再回答原来的问题。**

⇒ 闸里显式覆盖 `--spring.jpa.hibernate.ddl-auto=none`（与 prod 对齐）⇒ 2119 → **2044**。

---

## 六、`OPS-FRESH-DB-CANNOT-BE-BUILT` 的处置

⇒ 修法部分**被本票取代**：⛔ 不再修第 267 条。

那份文档里的 A/B/C/D 四类分类、两颗雷、6 条数据态守卫、6 个撞号 ——
**全部登记为「在新基线之下，不再需要处置」，但⛔ 留着不删**：
万一将来要走方向 A，那份分类是现成的，重做一次要一整天。

---

## 七、判据自身的两条纪律（本轮实测得出）

**① 恒真与恒假是同一个错误的两面。** 我写「提示必须给出三个真值」那条断言时，
v1 **恒真**（窗口滑到了下面那句恰好含同样三个变量的 `log`），
v2 **恒假**（`head -1` 抓到了更早的另一处 `printf`）——**成因同一个：我匹配到的不是我想要的那一处。**

⚠️ **顺序关系，⛔ 不是替代**（免得与本仓既有那条撞车）：

```
变异不红时:  ① 先证明**变异真的到达了**(打印一个可观测量) —— 排除「没打中」
             ② 再怀疑断言 —— 排除「打中了, 但断言看的是别处」
```

⛔ 不要写成「变异不红先怀疑断言」——那会让人跳过第一步。

**② 一个「看起来能代替」的计数，必须先证明它与被代替的量相等。**
实例：dump 里 **1253 条 `CREATE INDEX`** vs `pg_indexes` 的 **2044** ——
两者**天然不等**（差额是 PK/唯一约束自动建的）。用前者当判据会永远差 791，
而那个差**看起来像「基线不完整」**。

---

## 八、重新采集的触发条件与 owner

**Owner：organizer**（⛔ 写不出 owner 就说明它不会被执行）

这一节存在的理由，是验证器 scope 行推出来的那一句：

> **只有重新采集能证明基线是对的**；离线验证器只能证明它没有被**单侧**改坏。

⇒ 那**什么时候采集**？在本节之前，没有任何东西回答这个问题。
⚠️ 而同日刚立过：**一个有触发条件、没有 owner 的定期动作 = 不会发生的动作**
（`docs/ops/ci-runner-stays-self-hosted-2026-09-07.md`：日历规则喊了 7 天没人动）。

---

### 🔴 先说清一件事：`above_baseline_on_prod` **今天不能当触发条件**

它只由 `scripts/ops/capture-fresh-db-baseline.sh` 那一行产出：

```
READING: BASELINE_LAG baseline_max=… prod_max=… repo_max=… above_baseline_on_prod=N missing_files_above=M
```

而那个脚本**要连 prod、⛔ 不进 CI、人手跑**（这条边界没有例外）。

⇒ 拿它当触发条件是**循环的**：**要先做那个动作，才能知道该不该做那个动作。**
今天的实际后果是它**永远不会触发** —— 与「没有触发条件」是同一个结果。

### ⇒ 拆成两个数：一个在 CI 里烧，一个是权威值

| 数 | 谁产出 | 连 prod | 频率 | 角色 |
|---|---|---|---|---|
| `above_baseline_on_prod` | 采集器 | ✅ | **只在采集那一刻** | **权威值**，写进新基线的文件头 |
| `above_baseline_in_repo` | 验证器（离线） | ❌ | **每次 CI** | **触发用**，超阈值打 `::notice` |

⚠️ 代理**落地时改成了「计数」而不是「相减」**：版本号形如 `20261101.37`，
**两个版本号相减没有意义**（它们不是等距的）。⇒ 量的是「`db/flyway/` 里版本高于基线的**文件条数**」，
这样它才与权威值 `above_baseline_on_prod`（prod history 的**行数**）**同量纲、可直接比大小**。
▎⛔ 一个与被代替的量**量纲都不同**的数，谈不上「误差方向」。

两个数今天都已经在读数里（`repo_max` 从 `db/flyway/` 文件名数，`baseline_max` 从基线文件头读，
**两者都不连 prod**）⇒ 代理不需要新仪器，只需要一个阈值和一条 `::notice`。

**代理为什么成立**：prod 的迁移只从 `main` 来 ⇒ 仓里比基线多出的版本数，是 prod 迟早会跑的那批的**上界**。

⚠️ 它是**代理**，按形态 E 标出来并可替换：prod 落后于 `main` 是常态
⇒ 代理**恒 ≥** 权威值 ⇒ 它会**早于**真实滞后报警，⛔ 不会漏报。
**这个偏向是刻意选的** —— 采集一次的代价（人手 + prod 只读）远低于「基线悄悄过期」的代价。

### 🔴 2026-09-07 实测：两个数第一次同时有值

采集器在**已经有 checked-in 基线**之后再跑一次 —— 判据 ④ 第一次拿到真实输入：

```
READING: BASELINE_LAG baseline_max=20261101.37 prod_max=20261101.37 repo_max=20261101.39 above_baseline_on_prod=0 missing_files_above=0
READING: BASELINE_LAG_PROXY above_baseline_in_repo=2 baseline_max=20261101.37 repo_max=20261101.39
```

⇒ **权威值 0，代理 2** ⇒ 「代理 ≥ 权威值」**不再只是一句论证，它被量到了一次**。
⚠️ 一次 ⛔ 不等于恒成立 —— 但它至少排除了「方向反了」，
而在此之前那个方向只有一句推理撑着（`prod 的迁移只从 main 来`）。

⚠️ 顺带一条实测：**两次采集产出的文件 md5 必然不同**
（文件头的采集时刻 + PG 的 `\restrict` 随机 token），而字节数相同（1368345）。
⇒ ⛔ 跨采集不能拿 md5 当同一性判据 —— 那是五维的活。
md5 只在「**同一份文件**有没有被改过」这个问题上成立（扰动/还原那一对用的正是它）。

### 阈值：⛔ 现在不编一个

**阈值待定，依据是上线后 N 周的实际读数。** 先让那个数跑一阵，看它实际怎么长，再定。

⛔ 现在拍一个数出来，等于在没有分母的情况下定判据 ——
同日已立：**「0 违规」没有分母就不是证据**；一个凭空的阈值也一样，它只是看起来像判据。

### 报警形态：`::notice`，⛔ 不红

理由与 `payload_behind_by` 同：**落后是常态**。
红会让这道闸在第一周就被关掉（形态 E：**宁可窄而可信，不要宽而被关掉**）。

### 载体的落地时机：`--write` 那个 PR，⛔ 不是现在

在有 checked-in 基线之前 `baseline_max` 是 `<none>`，代理**恒无意义**
（今天的读数就是 `BASELINE_LAG baseline_max=<none> … **没量到**`）。

⇒ 那个 PR 必须带两样，⛔ 不是「代码写了」：

1. 代理在**那份真基线**上的实际读数；
2. 一次**超阈值的阴性对照** —— 把阈值临时调到 0 ⇒ `::notice` 真的打出来、且点得出差多少。

**✅ 2026-09-07 已落地**（第一份真基线 `prod-schema-baseline.sql`，`baseline_max=20261101.37`）：

```
READING: BASELINE_LAG_PROXY above_baseline_in_repo=2 baseline_max=20261101.37 repo_max=20261101.39 threshold=unset action=none-threshold-unset
READING: BASELINE_LAG_PROXY_ARMED=no ⇒ **阈值待定** ⇒ 这条告警今天**不会响**, ⛔ 不是「没超」
阴性对照 CRETAS_BASELINE_LAG_THRESHOLD=0 ⇒ ::notice 真打出来, 且 rc 仍为 0(⛔ 不红)
```

⚠️ **`ARMED=no` 这一行是刻意的**：阈值待定 ⇒ 这道告警今天**是哑的**。
⛔ 而「哑着」与「量过了没超」在退出码上完全同形 ——
所以它必须**自己说自己是哑的**，否则这一节读起来像已经在保护什么。

---

## 九、🔴 基线放错了地方：一个位置，**四个**静默后果

第一份真基线原本落在 `src/main/resources/db/baseline/`。**那个位置是错的**。

⚠️ 我第一次登记时只数出**一个**后果（「打包产物变大」），而且那个数还报错了（写的 +1.4 MB，实为 ~178 KB）。
最终是**四个**，其中第 ④ 个是挪走之后**另一道闸的读数自己动了**才暴露的。
▎**「这个位置有什么问题」这种问题，第一遍数出来的个数基本都是低估。**

### ① 它被打进了部署产物

两次 **`mvn clean package`** 实测（⚠️ 必须 `clean`，见下）：

```
基线在 resources 下:  jar = 177,618,520 字节   BOOT-INF/classes/db/baseline/ 条目 2 个
基线在 ops/ 下:       jar = 177,436,029 字节   baseline 条目 0 个
差                    =    182,491 字节   ≈ 该条目压缩后 182,157 + 两个 entry 头
```

一份 prod schema dump 是**运维制品**，⛔ 不是应用资源。

🔴 ⚠️ **理由是「它不是应用资源」，⛔ 不是「它太大」** —— 这点要写对：
我最初登记的是「+1.4 MB」（错的，**实际入 jar ~178 KB**，压缩后），
而那个量级当时被当成了「不该打进产物」的依据。
▎**依据错了，结论碰巧还对** —— 但下一个人完全可以拿「才 178 KB」来推翻它，
而真正的理由（运维制品 ≠ 应用资源、以及下面 ② 的 classpath）与体积无关。

🔴 ⚠️ **第一次量「挪走之后」时我没 `clean`** ⇒ `target/classes/` 里上一轮的副本被原样打了进去
⇒ 读数 **与挪走前逐字节相同（177,618,518）**。
▎那个「完全没变」看起来像一个结论（「挪了也不影响体积」），
**实际是这次测量根本没测到我以为的东西** —— 是 `jar 里 baseline 条目数=2` 那条
阴性对照把它捞出来的，⛔ 单看体积数会直接读成结论。

### 🔴 ② 它落在 `BOOT-INF/classes/` 下 ⇒ **它在 classpath 上**

⇒ `spring.flyway.locations=classpath:db/baseline` **是真能解析到的**。
⇒ 本文档第三节那条禁令（「⛔ 不许进 locations」），当时离失守只有**一行配置**。

▎**「放哪儿」不是整理问题** —— 它决定了那条禁令挡的是「一行配置」还是「一件做不到的事」。

### 🔴 ③ 它让一道闸**红着却已经瞎了**

`PartialUniqueIndexBackingSoftDeleteContractTest` 扫的是**父目录** `src/main/resources/db`
（全仓**只有它一个**扫父目录；另外 33 个契约测试扫 `db/flyway`，不受影响）⇒ dump 进了它的语料。

对照组是同一棵树、只把基线目录挪走再跑一次：

| | 有基线 | 无基线（对照） |
|---|---:|---:|
| `PARTIAL_UNIQUE_INDEX_STATEMENTS_IN_MIGRATIONS` | **217** | 113 |
| 闸的结论 | 🔴 红（`uk_encoding_rules`） | ✅ 绿 |

红的那半好办。**⚠️ 真正的问题是没红的那一半**：那 13 个受该闸守护的索引名，**dump 里一个不少**
（各命中 2–4 次）—— 因为 dump 是 prod 的快照，prod 上它们当然都在。

**实证**（删掉 `V20261101_39`，两种扫描面各跑一次）：

```
A  排除基线目录  ⇒ 🔴 红, 点名 uq_business_rules_factory_code_active   [absent]
                              uq_notify_templates_factory_code_active [absent]
B  不排除       ⇒ 🔴 也红 —— 但报的是 uk_encoding_rules,
                   **那两个被删的索引一个都没被报成 absent**
```

⚠️ **B 比「变绿」更坏**：闸**还是红的**、看起来在干活，而你关心的回归**已经隐形**。
⇒ 今晚一直在防的是「绿着瞎了」；**这是它的第二形态，而且没人会去查一个红着的闸。**

▎**一份 prod 快照放进「查迁移里有没有」的语料，会让这个问题不可证伪 —— 快照里什么都有，那正是它的用途。**

### 🔴 ④ 它还污染了另一道闸的读数 —— 而这一条是**数字自己动了**才暴露的

挪走之后 `READING: FLYWAY_LOCATIONS_FILES` 从 **8 掉到 7**。

⚠️ 我没有把这个下降当成「少了个文件很正常」，去查了成因：
`test-fresh-db-baseline-contract.sh` 第 3 条用
`grep -rl 'spring.flyway.locations' src/main/resources` 找配置文件 ——
而**基线文件头里就有这个串**（是采集脚本写进去的那句禁令本身）：

```
-- ⛔ 不许改名成 V…__*.sql, ⛔ 不许放进 db/flyway/, ⛔ 不许进 spring.flyway.locations。
```

⇒ 那道闸把**一份 SQL dump 当成了第 8 个配置文件**，并对它那行做了「含不含 `baseline`」的判定。

🔴 **而它离一次假红只有一个词**：那句禁令但凡写成「⛔ 不许把 **baseline** 放进
`spring.flyway.locations`」——一种完全自然的措辞——闸就会红，
**红在它自己陈述规则的那句话上**，而不是任何真实配置。

▎与「审查文档把它正在标记的口令抄了进去」同族：**描述一条规则的文本，
会被按那条规则去检查。** ⇒ 判据的语料里 ⛔ 不该混进「谈论这条判据」的文本。

---

---

### ⇒ 修法：把基线挪出去，⛔ 不是去改那道闸

基线移到仓库根的 **`ops/db-baseline/`**。一个动作同时解掉上面四条：
闸看不到它了 / 不进 jar / 不在 classpath / ⛔ 不再被当成第 8 个配置文件。

⛔ **不放根 `database/`** —— 那是那个已死的第二 schema 源，放进去会混。

新增闸（`test-fresh-db-baseline-contract.sh` 第 2b 条）：
**`src/main/resources/` 下按内容特征（`-- Dumped from database version`）⛔ 不许有 pg_dump 产物。**

⚠️ 守的是**它不被挪回来**：「SQL 不都该放 resources 吗」是一次看起来很自然的整理，
而三个后果全是静默的。⛔ 注释挡不住，所以写成闸。

### ⚠️ 但挪走 ⛔ 不根治那个洞

**那道闸扫父目录这件事仍在** ⇒ **任何未来放进 `db/` 的东西都会再次顶瞎它**。
⇒ 根治是把它的扫描根派生成只扫 `db/flyway`（在 Line B 手上），
并要求补一条断言：**基线目录必须不在扫描面里**。

### 🔴 附带教训：我判「没有并发编辑」的方法是结构性看不见的

我原本改了那道闸，理由是「`gh pr list` 全扫，没有在飞 PR 碰它」。**那个判据方向错了** ——
`gh pr list` **只能看见已推上远端并开了 PR 的编辑**，而 Line B 当时有 **7 个本地未推 commit**
正在改这个文件（扫描根派生 + `stripSqlComments`）。

▎**并发编辑的风险恰恰在「我看不到的地方」，而以「我没看到」结束的检查对它是盲的。**
⇒ 碰任何不在自己 scope 里的文件，**问有全局的人**，⛔ 不要扫一个只覆盖已推分支的接口。

---

## 十、🔴 任何「按文本特征找 X」的闸，都会把「谈论 X 的文本」算成 X

第九节 ④ 那件事不是巧合。**它是一个形状**，而我们今晚立的东西**大半都是文本特征**：
`# GUARDS=`、`READING:`、`-- Dumped from database version`、`spring.flyway.locations`。

⇒ 所以这一节不写「要小心」，而是**把四个都量了一遍**。

| 文本特征 | 扫描面 | 锚定 | 真命中 | **仅提及** | 结论 |
|---|---|---|---:|---:|---|
| `spring.flyway.locations` | `src/main/resources/` | ⛔ **无**（裸 `grep -rl`） | 2 | **5** | 🔴 **已经在错** |
| `# GUARDS=` | `scripts/tests/*.sh` | `^# GUARDS=` + `head -40` | 7 | 11 | ✅ 靠**锚定**幸免 |
| `-- Dumped from database version` | `db/flyway/`、`resources/` | 无锚定 | 0 | 2 | ⚠️ 靠**扫描面**幸免 |
| `READING: ` | 测试的 **stdout**（不是文件） | `^\s*READING: ` | — | — | ✅ 锚定 + 面都对 |

### 🔴 已经在错的那一个，错法有两层

```
grep -rl 'spring.flyway.locations' src/main/resources   ⇒ 7 个
真配置只有 2 个 (application-pg{,-prod}.properties)
另外 5 个是**迁移里的注释**, 例如:
  V20260416_11: -- Spring config: spring.flyway.locations=classpath:db/flyway — db/migration
  V20260424_08: -- db/migration/ 从未被 Flyway 扫描 (项目用 spring.flyway.locations=
```

**① 假红只有一个词之遥。** 那 5 条注释里但凡有一条写成
「⛔ 不要把 **baseline** 放进 `spring.flyway.locations`」——一种完全自然的措辞——
闸就会红，**红在一句正确的注释上**。

**② 🔴 更坏：它把这道闸的阳性对照顶住了。**
原判据有一条「一处都找不到 ⇒ 报**没量到**」。而真配置**即使被改名或删除**，
那 5 个注释文件照样让结果非空 ⇒ **闸继续绿，此刻它一处真配置都没在看。**

▎**阳性对照被「谈论它的文本」满足 = 阳性对照失效** —— 而失效方向是「一切正常」。

#### 变异实证

```
A  真配置里塞进 baseline          ⇒ 🔴 红, 点名 application-pg.properties
B  把两个真配置改掉扩展名藏起来    ⇒ 🔴 红: CONFIG_FILES=0 MENTIONS_ONLY=7
   ⚠️ **旧判据在 B 下是绿的** —— 7 个「提及」文件顶满了它的阳性对照
```

### ⇒ 修法（`test-fresh-db-baseline-contract.sh` 第 3 条）

```
READING: FLYWAY_LOCATIONS_CONFIG_FILES=2 MENTIONS_ONLY=5 (⛔ 后者不是配置, 不参与判定)
```

三件事一起做，⛔ 少一件都不够：

1. **锚到赋值形态**：`^\s*spring\.flyway\.locations\s*[=:]`，并限定 `*.properties|*.yml|*.yaml`；
2. **「仅提及」单独报数**，⛔ 不折进同一个读数 —— 折进去就等于把「不是配置的东西」当配置汇报；
3. **阴性对照**：现造一行只是**提到**它（且含 `baseline`）的注释，判据必须**不**把它算成配置。
   ⚠️ 没有这一条，「我锚到了赋值」与「我以为锚了、其实还是全匹配」读数完全相同。

### 另外三个为什么今天没事 —— ⚠️ 理由不同，牢固度也不同

- **`# GUARDS=`**：靠**锚定**（行首 + `head -40`）。11 处提及一处都没混进来。**结构上稳。**
- **`-- Dumped from database version`**：靠**扫描面** —— 那 2 处提及恰好在
  `scripts/` 和 `docs/`，都在扫描面外。⚠️ **这是运气不是设计**：
  谁在 `db/flyway/` 里写一句「本目录不许有 pg_dump 产物（它们带 `-- Dumped from…`）」，
  闸立刻红在那句注释上。⇒ 已登记，⛔ 本轮不改（改它要动 2/2b 两条判据）。
- **`READING: `**：扫描面是**测试的 stdout 而不是文件**，且带行首锚。两层都对。

▎**「靠锚定」与「靠扫描面恰好没覆盖」是两种牢固度** ——
后者会在有人写文档的那天失效，而写文档的人不会知道自己触发了一道闸。

### ⚠️ 而**本节自己**就是那份文档

第十节把四个特征串**原样写了一遍**。今天它安全，只因为这四道闸**没有一道扫 `docs/`** ——
实测：`spring.flyway.locations` 扫 `resources/`，`-- Dumped from…` 扫 `db/flyway/` 与 `resources/`，
`# GUARDS=` 扫 `scripts/tests/*.sh`，`READING:` 扫 stdout。

⇒ 📌 **谁哪天把扫描面扩到 `docs/`，本节会是命中最多的那个文件。**
▎讲这条规则的文本，正是这条规则最典型的受害者 —— ⛔ 这不是俏皮话，是扩扫描面之前要先查的一件事。

### 🔒 ⇒ 扩任何扫描面之前的**前置动作**（⛔ 不是「扩完看红不红」）

**⛔ 不要扩完再看红不红** —— 那时你已经在一堆红里面找答案，
而「真违规」和「解释违规的文档」在那堆红里**长得一模一样**。

⚠️ 本仓扩扫描面已经出过事：2026-08-28 给别人加宽 glob，
**把那边的阴性对照变成了恒真式** —— 加宽本身不报错，是对照悄悄失去了鉴别力。

⇒ 扩之前，在**新覆盖进来的那部分**里先把两个数分开量：

```bash
FEATURE='spring.flyway.locations'          # 换成你那道闸的特征串
ANCHOR='^[[:space:]]*spring\.flyway\.locations[[:space:]]*[=:]'   # 换成你的锚定正则
NEW_SCOPE='docs/'                          # 换成你要**新**覆盖的路径

grep -rl  -- "$FEATURE" $NEW_SCOPE | wc -l   # ① 新增命中总数
grep -rlE -- "$ANCHOR"  $NEW_SCOPE | wc -l   # ② 其中真违规
```

**判据：两个数分开报。**
① > ② 时，**扩之前**先决定那些「仅提及」怎么办 —— 排除目录 / 收紧锚定 / 改措辞。
⛔ 不要让它们以「扩完之后一堆新红」的形式出现。

▎**顺序就是判据本身：先数，再扩。** 反过来做，你拿到的是一个已经被污染的读数。

---

## 十一、🔴 回头审：今天新加的每一个读数，**它有没有取过第二个值？**

`errors=0` 那件事（第二节的订正）不是孤例风险 —— 它是一个可复查的问题：

> **一个永远等于「没问题」的读数，比没有这个读数更坏 —— 它占着位置。**

⇒ 对今天新加的每一个读数问一句：**它有没有取过第二个值？**
取过 ⇒ 活的；从没取过 ⇒ **做一次变异证明它能取到**；证不出来 ⇒ 它是 `errors=0` 那一类。

⚠️ **「应当恒 0」与「无法非 0」必须分开** —— 前者是判据的目的，后者是坏掉的仪器，
而两者在日志里长得一模一样。**判据就是变异。**

### A. 声明式：字面值，按设计恒定（⛔ 它们不是测量）

`VERIFIER_SCOPE` / `VERIFIER_BLIND_*` / `VERIFIER_PROOF_OF_CORRECT` /
`BASELINE_LAG_PROXY_DIRECTION` / `GATE_SCOPE` / `GATE_5_SCOPE`

⇒ 它们回答的是「这道闸守不住什么」，⛔ 不回答「今天是多少」。
📌 归到这一类的条件是：**值在源码里是字面量**。⚠️ 一个看起来像计数的读数 ⛔ 不能放进这一类。

### B. 今天已经取过第二个值（活的）

| 读数 | 观察到的两个值 |
|---|---|
| `BASELINE_DIR` | `absent` → `present` |
| `BASELINE_LAG` | `baseline_max=<none>` → `20261101.37` |
| `above_baseline_on_prod` / `missing_files_above` | `0` → `9`（阴性对照 `CRETAS_BASELINE_ASSUME_MAX`） |
| `BASELINE_REPLAY` | `indexes=2044` → `2043`（扰动真基线） |
| `BASELINE_REPLAY_ERRORS` | 内建阳性对照：灌 `SELECT 1/0` ⇒ `0` → `1` |
| `BASELINE_LAG_PROXY` | `action=none-threshold-unset` → `notice`（阈值调 0） |
| `FRESH_DB_SHAPE` | `indexes 2119` → `2044`；`migrations 266` → `3` |
| `FRESH_DB_FAILURE` | `file=V…sql` → `<none>` |
| `FLYWAY_LOCATIONS_CONFIG_FILES` | `2` → `0`（藏起两个真配置） |
| `TRIGGER_PATHS` / `GUARDS_REGISTERED` | `20`→`21` / `6`→`7` |
| `BASELINE_BOOTSTRAP tables_after_load` | `501` → `498`（`DROP SCHEMA public` 那条歧路） |
| `CRITERIA_ORDER` / `HINT_PRINTF_LINE` | 行号随文件变化 |
| `A1` / `D`（payload 滞后） | `payload_stale=no` / `=yes` |
| `NEGATIVE_CONTROL_PROBE` ×2 / `RATCHET_NEGATIVE_CONTROL` / `REGRESS_LOGIC_PROBE` | 构造出来的两态，本来就是对照 |

### C. 🔴 本轮**第一次**证明它能动（此前只见过一个值）

```
LOCAL_COMPOSITE_ACTION_REFS   注入一处 `uses: ./.github/actions/…`  ⇒ 0 → 1, 且红并点名
INLINE_BODIES identical       三处内联 run 体改掉其中一处            ⇒ True → False, 且红
normalized_diff_lines         只差随机 token ⇒ 0 ; 真多一条语句 ⇒ 1
```

⚠️ 第三条我**取脚本里那一行 `normalize()` 原样**去测，⛔ 没有手抄 ——
手抄的话测的是我抄的那份，而漂掉的恰恰会是没抄对的地方。

### 🔴 ⚠️ 这次审计里我自己造了一个假读数

第一次测 `normalize()` 时我用 shell 内联复刻，`printf` 把 `\r`/`\u` 吃掉、`sed` 正则也没转义对：

```
只差随机 token (期望 0):   0     ← 看起来对
真的多一条语句 (期望 >0):  0     ← 🔴 也是 0
```

⇒ 两条都是 0，而**其中一条本该非 0**。⛔ 如果我只看第一条，会得到「归一化工作正常」这个结论。
▎**在审「读数会不会说谎」的过程中，我的探针自己说了一次谎，而它说谎的方向仍然是「一切正常」。**
⇒ 这正是为什么**每个探针都要带一个期望非 0 的分支** —— 只测期望为 0 的那一侧，探针坏了与结论成立同形。

📌 ⚠️ **这不是一条新规矩，是同一条规矩往上游走了一层。**
我们一直在给**被测对象**配阴性对照；这条说的是**探针本身**也要，而理由完全相同。
⇒ 它还能继续往上走（探针的探针也一样）
⇒ 所以判据 ⛔ 不是「加一层」，而是：**每一层都要有一个必然非 0 的读数。**

⚠️ 另一条同源的：我测 `normalize()` 时**取脚本里那一行原样**，⛔ 没手抄 ——
**手抄的话测的是我抄的那份，而漂掉的恰恰会是没抄对的地方。**
▎「两份必漂」不只适用于代码，**同样适用于探针与被测对象**。

### C2. 🔴 第二轮补证：**按「瞎了最贵」挑，⛔ 不是按容易做挑**

D 类不必全做。⇒ 只做两个**瞎掉代价最大**的：

| 读数 | 它守着什么 | 变异 ⇒ 读数 |
|---|---|---|
| `HEAD_SHA_PINS` | 整条「闸跑在**合并结果**上」 | 拆掉一处 `ref: …head.sha` ⇒ **3 → 2**，红 |
| `SELECTOR_PATTERNS` | 「新加的闸别逃掉 `-Dtest`」 | 选择器里拿掉 `*ContractsTest` ⇒ **14 → 13**，红 |
| ↳ `SELECTOR_MATCHED` / `FAMILY_KEYWORDS` / `ESCAPED` | 同上 | **360→359 / 13→12 / 6→7** |
| `KEYWORD_CARRYING` | 同上 | 新增一个带族关键词的类 ⇒ **365 → 366** |
| `REGISTERED_EXCEPTIONS` | 豁免登记表 | 加一行 ⇒ **6 → 7**，且因无理由而红 |

⚠️ **前两次变异没有让 `KEYWORD_CARRYING` 动**（365 → 365）——
成因：拿掉的 `Contracts` 关键词被 `Contract` **包含**，带前者的类同时带后者。
⇒ 所以我**没有把它算进「已证」**，而是另做了一次变异（新增一个类）才证到。
▎**一次变异让一组读数里的几个动了，⛔ 不代表其余几个也是活的。**

### D. 仍未单独变异（⚠️ 说清楚，⛔ 不算已证）

`EXTRACTED_LINES` / `OPS_SCRIPT_TESTS_PATHS` / `FLOOR_*`

⇒ 它们都绑着**会红的断言**，但我**没有逐个变异** ⇒ 写「未证」。
📌 留在这里是有意的：**「未证」不是待办清单，是一份带优先级的账** ——
这三项瞎掉的代价明显小于上面那两组。

---

关联：`docs/ops/repo-cannot-build-a-fresh-database-2026-09-07.md`、
`scripts/testing/fresh-db-gate.sh`、`.github/workflows/fresh-db-progress.yml`。
