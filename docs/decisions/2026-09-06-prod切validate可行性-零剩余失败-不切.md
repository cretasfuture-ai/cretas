# prod 切 `ddl-auto=validate`：可行性决定书（不切，只给读数）

**日期**: 2026-09-06
**任务**: `OPS-PROD-VALIDATE-FEASIBILITY-20260906`（brief 卡 B 第 3 条）
**文件**: `backend/java/cretas-api/src/main/resources/application-pg-prod.properties`（`spring.jpa.hibernate.ddl-auto=none`，第 48 行）
**口径**: 全部读数来自 docker `pgvector/pgvector:pg17` 上的 **发 47 之后的 prod schema-only 副本**（`cretas_prod_db` 503 表 + `flyway_schema_history` 700 行 max `.36`；`smartbi_prod_db` 150 表）与**正在运行的那个 jar**（`/www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar`，mtime 21:18:55，含 `V20261101_36`）。⛔ prod 一字未改。

---

## 一句话

**发 47 之后，prod schema 对 `validate` 的剩余失败 = 0**：与 prod 完全相同的 jar（sha1 `4c1f3f4ca421`，两端相等）在 prod schema 副本上**零 fixture** 起动 `validate` ⇒ `CONTEXT_STARTED`（71.2s）。切与不切是 owner 的决定：切了得到的是「实体先改、迁移没跟上」在发布时被拦（2026-04-16 一天四次、`role_definitions` 四个月的那类事故）；代价是这类 PR 的发布会失败并回滚，以及 validate 看不见的那一整列（唯一/索引/FK/NOT NULL/默认值/多余对象）**照旧看不见**——它不是 schema 对账。⛔ 本文件不切；切是一个 property。

## 1. 剩余 validate 失败清单（零 fixture 起动）

Hibernate 每次只报第一条，所以做法是「红一条、补一条 fixture、再起」直到 `CONTEXT_STARTED`，fixture 清单就是失败清单。

| 轮 | fixture | `BOOT_STATUS` | 点名 |
|---|---|---|---|
| 1 | **0 张** | **`CONTEXT_STARTED`**（`Started CretasBackendApplication in 71.218 seconds`） | — |

**剩余失败清单为空。** #643 量出的 163 处（138 实体注解 / 19 迁移 `_35` / 6 缺表）到此全部闭合：6 缺表里 4 张 `smart_bi_*` 由 #651 剔出主单元（`AFTER_PRIMARY_SMARTBI_PG_TABLES=0`，主库副本里本来就没有它们，起动照样过），`employee_process_segments` 由 #660 删实体，`tool_metadata` 由 #660 的 `_36` 建表（`TASK2_TABLES=tool_metadata`）。

⚠️ 「零 fixture 起动过了」与「validate 被静默跳过」长得一样，所以同一 jar、同一副本做了阳性对照：删掉 `production_plans.plan_number` 再起 ⇒ `EXITED` 27s，`Schema-validation: missing column [plan_number] in table [production_plans]`——validate 在这个 jar、这份副本上是活的，零 fixture 那次「过了」是真的过了。（仪器自坑一条：fixture 里那句 `count(*)` 没带 `table_schema='public'`，把 prod dump 里 `tenant_purge_68` schema 的同名列也数进去读出 1；按 public 过滤是 0。）。

## 2. validate 看什么、不看什么

| 看 | 不看（[RUN] #629 与本次负对照都证实） |
|---|---|
| 表存在 | 唯一约束 / 唯一索引 |
| 列存在 | 普通索引 |
| 列类型（类型码相等，或期待类型串 startsWith 实际类型名；不看长度/精度） | 外键 |
| | NOT NULL / DEFAULT |
| | 多出来的表 / 列（库里比实体多的，validate 不管） |

负对照（同一副本、同一 jar）：在克隆上一次性做掉 7 件事再起 `validate`——

```
ALTER TABLE tool_metadata DROP CONSTRAINT uk_tool_metadata_tool_name;   -- 唯一约束
ALTER TABLE tool_metadata ALTER COLUMN action_type DROP NOT NULL;       -- NOT NULL
ALTER TABLE tool_metadata ALTER COLUMN risk_level DROP DEFAULT;         -- DEFAULT
DROP INDEX idx_tm_action_type;                                           -- 普通索引
ALTER TABLE process_sheet_rows DROP CONSTRAINT fk_process_sheet_rows_plan; -- #629 的 NOT VALID 外键
CREATE TABLE lineb_extra_table_validate_should_ignore (id int);         -- 多出来的表
ALTER TABLE tool_metadata ADD COLUMN lineb_extra_col text;              -- 多出来的列
NEGCTL_APPLIED uk=0 notnull=YES fk_dropped=true extra_table=true        ← 变异确实到达
BOOT_STATUS=CONTEXT_STARTED  (77.9s)  VALIDATION_MSG=(空)               ← 七件事一件都没被点名
```

⇒ `validate` 守的是「实体要的表/列/类型在库里都有」这一件事；它**不是** schema 对账，`FK NOT VALID`、部分唯一索引、软删+唯一键这些（#629 / brief 第 5 条）它一概看不见。

## 3. 代价

| 项 | 读数 |
|---|---|
| 起动耗时 `none` vs `validate` | 同一副本、同一 jar、串行各一次：`validate` **71.2s**，`none` **80.2s**（`Started … in` 读数）。单样本、差异反向，只能说「validate 没有可测的额外起动耗时」——它做的是一次 `information_schema` 级别的元数据比对，与 Flyway/Hibernate 元模型构建相比不在一个量级；⛔ 不要把 9 秒读成「validate 更快」。 |
| 失败形态 | validate 失败 = `primaryEntityManagerFactory` 建不出来 → 上下文不起 → 蓝绿发布链的 readiness 会判「起不来」并回滚 jar（memory `project_release_readiness_treats_a_dropped_ssh_as_a_dead_app`：同一条链已经对「9 秒后才起来」误判过一次，validate 只会让起动更慢、不会更快） |
| smartbi 单元 | **不受该 property 控制**：`SmartBIPostgresDataSourceConfig` 把 `hibernate.hbm2ddl.auto` 硬编码成 `update`（#651 登记项）。切 validate 只管主库 |
| H2 / 测试 | 不受影响：test profile 自己写 `create` |

## 4. 切换动作 / 回退动作

- 切：`application-pg-prod.properties` 第 48 行 `none → validate`（一个 property），走正常 Java 发布。⚠️ 第 46–47 行那两句注释（「BYTEA/BLOB 类型映射差异导致验证失败」）所指的原因 #643 已消灭，切的时候一并删。
- 回退：同一行改回 `none`，重新发布；或蓝绿链自动回滚到上一个 jar（但 jar 里没有这个 property，它在 jar 内的 `application-pg-prod.properties` 里——回滚 jar 就是回滚 property）。
- 不需要迁移、不需要改库。

## 5. 风险（切了以后会发生什么）

1. **任何一个「实体先改、迁移没跟上」的 PR 会在 prod 起动时被拦下**——这正是要它的理由（2026-04-16 一天四次「entity 字段加了但 DB 列没迁」；2026-09-04 `role_definitions` 从没建过却跑了 4 个月）。代价是那次发布失败并回滚，而不是带着坏 schema 跑。
2. **它拦不住的形状**（第 2 节右列）照旧要靠 #629 那类闸与 brief 第 4/5 条。
3. **误伤面**：validate 对「库里比实体多」不报，所以历史遗留的多余列/表不会挡发布；会挡的只有「实体要、库没有」和「类型不合」。本次零 fixture 起动的读数（第 1 节）就是现在会挡的全部。

## 6. 建议

**可以切，建议切，但不是现在这一发**：

1. 切之前先把 **#651 登记的 smartbi 单元 `hbm2ddl.auto=update` 硬编码**处理掉（另票）。否则「主库 validate、smartbi 库 update」两个单元两种纪律，读文件的人会以为一个 property 管了两边。
2. 切的那个 PR 只改 `application-pg-prod.properties` 一行 + 删两行过期注释；发布走正常 Java 链；发布后判据 = `Started CretasBackendApplication` 且日志里 `hibernate.hbm2ddl.auto` 生效值可查（或起动日志无 `Schema-validation`），⛔ 不能只看 `DEPLOY_EXIT=0`。
3. 切了之后，任何含实体改动的 PR 在合并前必须在 docker prod-schema 副本上做一次零 fixture 的 validate 起动（本线已有底座：`boot_validate.sh`，memory `project_line_b_schema_fixture_and_spare_port_boot_recipe`）——否则第一个漏迁移的 PR 会把一次发布打回。
4. 它守不住的那一列（第 2 节右）继续靠 #629 那类闸 + brief 第 4/5 条（`@SQLDelete` 棘轮、软删+唯一键/发号器）。

不切也不会更坏：今天 prod 之所以 0 失败，是 #643/#651/#660 三个 PR 把账对平了；不切，下一次漂移仍然只能靠人和事后 probe 发现（`role_definitions` 那样）。

## 读数附录

```
底座: docker pgvector/pgvector:pg17  cretas-lineb-pg17:55434
  lineb_cretas_base2  = pg_dump --schema-only cretas_prod_db (发 47 后, 503 表) + flyway_schema_history 700 行 max 20261101.36 + tool_metadata 存在
  lineb_smartbi_base2 = pg_dump --schema-only smartbi_prod_db (150 表)
  每次起动 CREATE DATABASE … TEMPLATE 新克隆, 基准不动; dump ⛔ 不入库
jar: /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar (mtime 2026-09-06 21:18:55, 含 V20261101_36, EmployeeProcessSegment 0)
  sha1 prod=4c1f3f4ca421  本机副本=4c1f3f4ca421
起动参数 = prod 的 pg-prod + smartbi.postgres.enabled=true + --spring.jpa.hibernate.ddl-auto=<validate|none>
  (dashscope / bert 关掉, 与 schema 无关; 本机没有 embedding gRPC ⇒ 日志有 UNAVAILABLE 噪音)

t3_validate  DDL=validate fixture=0   BOOT_STATUS=CONTEXT_STARTED 71.2s  fsh 700→700 max .36  smartbi update diff 0
t3_none      DDL=none     fixture=0   BOOT_STATUS=CONTEXT_STARTED 80.2s  fsh 700→700
t3_negctl    DDL=validate fixture=7 项 validate 盲区变异   BOOT_STATUS=CONTEXT_STARTED 77.9s  VALIDATION_MSG=(空)
t3_posctl    DDL=validate fixture=DROP COLUMN production_plans.plan_number   BOOT_STATUS=EXITED 27s  VALIDATION_MSG=missing column [plan_number] in table [production_plans]
```

相关: #643（163 处枚举 + `_35`）、#651（smartbi.postgres 实体剔出主单元）、#660（死实体删 / `_36` 建表）、#629（validate 不看 FK/唯一/索引的 [RUN] 首证）。
