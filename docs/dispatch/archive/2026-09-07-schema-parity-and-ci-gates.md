# 2026-09-07 schema parity 与 CI 闸 —— 台账条目详情

本文件是 `ACTIVE.md` 在飞条目的详情锚点。ACTIVE 只留一句话，判据读数与来历写在这里。

## 背景：Steve 2026-09-07 给的方向是「根治」

今晚所有关于 schema 的闸，量的都是「迁移文本里写了什么」，而它们想知道的是「跑完迁移之后库长什么样」。
判据连续两次以「少一个动词」的形式不完整（v1 漏 DROP / v2 漏 RENAME TO）⇒ ⛔ 不补第三个动词，
改成让 PostgreSQL 自己记账：把迁移跑一遍，库的最终状态就是答案。

## OPS-FRESH-DB-CANNOT-BE-BUILT-20260907

状态：`in-progress` 

- `OPS-FRESH-DB-CANNOT-BE-BUILT-20260907` — `in-progress` — Owner: Line C — 🔴 从零建不出库：Flyway 死在第 267 条（`V20260827_01` 对 `production_settlements` 加列，而建表在第 419 条）。prod 两条都 success，因为 baseline 之下 262 个 legacy 脚本先建了表。⇒ 新测试环境 / 灾备 / 新租户库今天一个都建不出。⛔ 不逐个修：先一趟 continue-on-error 量全深度（失败清单 / 按缺失对象去重 / 末尾阳性对照）。详情 `docs/ops/`。

## OPS-SCHEMA-PARITY-ROOT-FIX-20260907

状态：`queued` 

- `OPS-SCHEMA-PARITY-ROOT-FIX-20260907` — `queued` — Owner: Line C — 根治（Steve 2026-09-07 指示）：从迁移新建库 → 导出 schema → 与 checked-in prod 快照逐行 diff（采集日期本身是读数）。判据 = diff 行 + 显式豁免清单（每条写理由），⛔ 不是「必须完全一致」。🔴 首跑必须红且可枚举（`database/*.sql` 7 个 + `uk_encoding_rules` + 3 个 `uk_report_worker_*`）；**开局全绿 = 仪器坏了**。依赖 FRESH-DB。

## CI-GATE-SCANS-DEAD-MIGRATION-DIRS-20260907

状态：`queued` 

- `CI-GATE-SCANS-DEAD-MIGRATION-DIRS-20260907` — `queued` — Owner: Line C — 🔴 P0：`PartialUniqueIndexBackingSoftDeleteContractTest:48` 扫 `db/`（5 个子目录）而 Flyway 只跑 `db/flyway/`。实测 flyway 108 条部分唯一 / 死目录 `db/migration` 3 条，其中 3 个名字活目录里一次都不出现（`uk_report_worker_batch_date` / `_batch_type_date` / `_type_date_no_batch`）⇒ 闸说「迁移里有」而新库拿不到。收窄扫描根 + 新旧两个 census 一起贴。

## CI-GUARDED-PATHS-MUST-BE-DECLARED-20260907

状态：`queued` 

- `CI-GUARDED-PATHS-MUST-BE-DECLARED-20260907` — `queued` — Owner: Line C — 「被断言的东西不在触发面里」今天 4 次（#713 java-gate-pr / #723 web-dist / #724 test 源码树 / `fresh-db-gate.sh` 压根不在任何 workflow）。⛔ 不反解（第 3 例根本不是 workflow）：用 `# GUARDS=<glob>` 声明（同 `# SCRIPT_TEST_TIMEOUT` 形状），闸校验声明 ⊆ paths；自指那格必须显式占上。另报告 `scripts/` 下无人调用的闸。

## OPS-PROD-ONLY-PARTIAL-INDEXES-20260907

状态：`review` 

- `OPS-PROD-ONLY-PARTIAL-INDEXES-20260907` — `review` — Owner: Line C — PR #725：`V20261101_39` 把 `uq_business_rules_factory_code_active` / `uq_notify_templates_factory_code_active` 补进迁移（prod 有、迁移无 ⇒ 新库比 prod 严）。存量重复 0（阴性对照：去掉 `deleted_at` 过滤数出 1 组 ⇒ 谓词承重）。真 pg17 上 A/B/C/D 四态跑过。`uk_encoding_rules` 登记为良性；`uq_bpic_recipe_wp` 实为 RENAME 创建、非缺口。

## 其余五张（详情即 ACTIVE 那一行，无额外读数）

`CI-NINE-OPTIN-PG-TESTS-RUN-NOWHERE` / `CI-NO-GATE-GUARDS-SUITE-SIZE` /
`OPS-DECISION-GRADE-PROBES-MUST-BE-COMMITTED` / `OPS-BARE-FK-COLUMNS-POINT-AT-SOFT-DELETED-ROWS`
（后者另有 `docs/ops/bare-fk-columns-point-at-soft-deleted-rows-2026-09-07.md`）。

## 2026-09-07 从 ACTIVE 移出：无 owner 且 Base 已失效，不是「在飞」

这两条 `Owner: 无（原会话已消失）`、Base 落后 572/573 个 commit、scope 锁已于 09-06 释放。
按维护规则「只记当前在飞」，它们留在 ACTIVE 里只会让每 session 加载的那份表更长而不产生动作。
⚠️ **不是取消**：续做时须重开 worktree off `origin/main` 并重登 Base（硬约束 7），
原文如下，⛔ 不要照抄里面那个已经失效的 Base SHA。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE` — `blocked` — Owner: 无（原会话已消失） — Base `fee2509fa6`（落后 572 commit）。已删到不了的 BOM 准入分支并上闸；待裁定画布激活是否补写 `bom_versions` 快照。09-06 organizer 释放 scope 锁，续做须重登 Base；详见 archive/2026-09-06-active-organizer-log.md。

- `BUG-F006-PRODUCTION-COST-CLOSURE-001` — `blocked` — Owner: 无（原会话已消失） — Base `7d51fe2259`（落后 573 commit）。`totalCost` 非空 2/23→16/23，剩余 7 条全是 `PINNED_BOM_SNAPSHOT_MISSING`（归 BOM-GATE）；遗留 `PUT /costs` 写 plan 而归集读 batch。09-06 organizer 释放 scope 锁，续做须重登 Base。

