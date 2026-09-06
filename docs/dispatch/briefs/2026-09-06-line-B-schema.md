# Brief 卡 B：Schema / 持久化线（持续 session）

**发卡**: organizer `liushanmen-organizer-handoff-fa5fb5`（Fable）· 2026-09-06 · 基 `origin/main` ≥ `ffa87422ee`
**角色**: 这条线的 sister chat。多轮连续做；**每个任务做到 PR 停**，合并 / 部署只由 organizer 做。
**回执格式**: 任务 ID / 基 SHA / 改动文件 / census 等式 / 迁移四态读数 / 变异读数 / PR / 阻塞 / 登记未做。

## 铁律

- worktree off `origin/main`（`git worktree add -b codex/claude-<task> ../cretas-<task> origin/main`）；⛔ 不在主目录干活；commit 只带列出路径；squash 后重开分支。
- 🔒 Flyway / 实体 / 迁移 = 只到 PR，⛔ 不合并不部署。**每次提交含迁移前当场重数**：`git fetch origin main && git ls-tree -r --name-only origin/main -- backend/java/cretas-api/src/main/resources/db/flyway | grep -oE 'V20261101_[0-9]+' | sort -t_ -k2 -n | tail -1`。当前已用到 `_34`（`_35` 在 PR #643 里，合并前它就是下一个；合并后你从 `_36` 起）。`_30` 已退役不复用。⛔ 迁移里不写死 id。
- ⛔ 不写 prod。只读 prod SQL：文件 → `scp` 47 → `ssh root@47.100.235.168 "sudo -u postgres psql -d cretas_prod_db -At -f /tmp/x.sql"`。prod 主库 `cretas_prod_db`，SmartBI 库 `smartbi_prod_db`。
- **全新库不能从 Flyway 重放建起**（版本号是序号不是日期，prod 按墙钟长出来）⇒ 唯一诚实的 fixture 是 **prod schema-only dump** 的 docker 副本（`pgvector/pgvector:pg17`）。H2 profile 关 Flyway，看不见任何 DDL 层问题。
- 迁移验证四态（docker 副本）：存量容忍 / 新违规拒绝 / 目标行为成立 / 幂等重跑；负对照必须真跑（如删列 ⇒ validate 点名失败）。
- **Hibernate `validate` 只校验表存在 / 列存在 / 列类型**，不看唯一约束、索引、FK（[RUN] #629）。prod 实跑 `ddl-auto=none`；`none` 什么都不校验，报「全新库能起」时必须写明用的是哪个值。
- 测试环境 Mac mini `100.85.234.108`（`ssh canada-mini` 直连可用；脚本落盘再执行），后端 `launchd com.cretas.testenv.backend`，jar 在 `~/cretas-testenv-new/backend/java/cretas-api/target/`，库 `cretas_testenv`（用户 `cretas_user`，`psql` 在 `/opt/homebrew/bin/psql`）。要试起动用**备用端口**（如 10099），⛔ 不动 :10010 与 plist。
- 诊断输出先落文件再 grep；改共享结构前后各数一次（硬约束 8）。

## 已验事实

- `@SQLDelete` 在 `@MappedSuperclass BaseEntity` 上**不继承**（`@Where` 同坑）：352 个 `@Where` vs 23 个 `@SQLDelete`；#629 已给 `ProductionPlan/ProcessSheetRow/ProductionBatch` 补上，并加棘轮 `SoftDeleteAnnotationCoverageRatchetContractTest`（清单文件 `src/test/resources/soft-delete-known-violations.txt`，329 条只禁增长）。
- #629 已上 prod（发 42）：`process_sheet_rows.plan_id`、`report_reversal_logs.plan_id` → `production_plans(id)` **NOT VALID + RESTRICT**（存量孤儿 56 / 10 容忍，是历史记录不清）；`uk_production_plan_create_request` 改部分索引 `WHERE client_request_id IS NOT NULL AND deleted_at IS NULL`。
- **PR #643（终审中）**：163 处 validate 失败 = 138 实体 `columnDefinition`（`JSON/json/TEXT`→`jsonb`、7 处 `uuid`、`MEDIUMBLOB`→`bytea`、`LONGTEXT`→`TEXT`）+ 19 项 `V20261101_35`（16 个存成 text 的 boolean→boolean 带词表守卫、`ai_intent_configs.confidence_boost` float8→numeric(3,2)、`isapi_devices.equipment_id` varchar→bigint、`workflow_templates.deleted_at` ADD COLUMN）+ 6 张有实体无表。⚠️ `_35` 已被打到 `cretas_testenv`。
- 🔴 `WorkflowTemplate` 有 `@Where(deleted_at IS NULL)` 而 prod 无该列 ⇒ 该表**每次 JPA 读**都 `column does not exist`（[RUN] 复现）。`_35` 加列即修，合后要真跑一次读路径。
- 🔴 `employee_process_segments`、`tool_metadata` 两张表在任何库都没建过（同 9-04 `role_definitions` 事故形状：baseline 跳过 + 防御 DO-block 静默）。
- 🔴 4 张 `smart_bi_*` 实体的表在 `smartbi_prod_db`，但 `PrimaryDataSourceConfig` 扫 `com.cretas.aims.entity.smartbi`（含 `postgres` 子包），注释明写「因为 ddl-auto=none 所以不会建」——代码显式依赖 none。
- `scale_protocol_test_cases.expected_stable` 存的是 4 字符字面串 `\x00`/`\x01`（MySQL BIT 转义残留）。
- 手工多表 SQL 清理在 prod 发生过至少两次（7-15 同一微秒 18 表 1082 行；8-29 漏 `process_sheet_rows`），是纪律问题；代码侧能做的是让漏子表的清理下次被 FK 挡住。
- 软删孤儿日扫（47 cron 04:30）已复活（#627/#632），告警在 `/www/wwwroot/cretas/logs/soft-delete-orphans.alert`，分 `absent`（悬空引用）/ `soft_deleted` 两类；生成器 `scripts/check-soft-delete-orphans.sh`，产物 `scripts/cron/soft-delete-orphans.sql` 有漂移闸。

## 你这条线的任务（按序）

0. **先接 #643 的终审结论**（organizer 会把审查意见贴进 PR）；若需改，改在同一 PR。合并后由 organizer 发布；你随后做「合并后复验」：prod 上 `workflow_templates.deleted_at` 存在、16 个 boolean 列类型、`flyway_schema_history` 到 `.35`、真跑一次工作流模板列表接口 200。
1. **`OPS-SMARTBI-ENTITIES-IN-PRIMARY-PERSISTENCE-UNIT`**（新票，你开）：把 4 张 `smart_bi_*` 实体挪出主持久化单元（第二个 EntityManagerFactory 指 smartbi 数据源，或改成不经 JPA）。判据：主库 validate 起动 STARTED 且不需要 fixture 表；smartbi 侧读写不变（找它们的 repository 调用方，真跑一次）。⚠️ 这是架构改动，PR 前先在 PR body 写设计 3 段（现状 / 方案 / 爆炸半径），organizer 会先审设计再审代码。
2. **`OPS-ENTITIES-WITHOUT-TABLES`**：`employee_process_segments`、`tool_metadata` 各自定性——是死实体（删实体 + repository + 调用方，census 等式）还是漏建表（写迁移，⛔ 不用防御 DO-block 静默跳过，失败要炸）。看 git log 它们什么时候进来的、有没有调用方。
3. **prod 切 `validate` 的可行性报告**（不切）：在 docker 副本用与 prod 相同的 jar + `pg-prod` + `validate` 起动，列出仍失败的项与每项的处置；给 owner 一页决定书。
4. **`@SQLDelete` 棘轮往下走**：从 329 条清单里挑「有 `repository.delete()` 调用方」的实体（census：grep `delete(`/`deleteById(`/`deleteAll(` 的调用点反查实体），按批补 `@SQLDelete`，每批一 PR ≤ 20 个实体，每个实体一条 `@DataJpaTest` 证明软删（行在、`deleted_at` 非空）+ 变异去注解 ⇒ 行没了 ⇒ 红。
5. **软删阴性影响**：软删后「编号是否被占用」类查询会撒谎（memory `project_soft_delete_makes_every_jpql_lie_about_occupancy`，57 个发号器 20 个 BROKEN）——每补一批 `@SQLDelete` 就要同批核该实体的唯一键 / 发号器是否需要部分索引（同 #629 的 `uk_production_plan_create_request` 处置）。

## 相关文件 / 入口

- 迁移目录：`backend/java/cretas-api/src/main/resources/db/flyway/`；`FlywayVersionUniquenessTest`
- 数据源：`.../config/PrimaryDataSourceConfig.java`、`.../config/SmartBi*DataSourceConfig.java`
- 契约测试：`.../contract/SoftDeleteAnnotationCoverageRatchetContractTest.java`、`EntityColumnDefinitionIsPostgresTypeContractTest.java`（#643）、`*RepositoryQueryValidationTest`（H2，93 个）、`NotNullColumnsHaveEntityDefaultsContractTest`
- #643 的 docker 底座与 dump 在 organizer 的 scratchpad（`…/scratchpad/sv/`，`sv_base` 库保留）；你自己重建一份，dump ⛔ 不入库。
- 台账：`docs/dispatch/ACTIVE.md`；今日详情 `docs/dispatch/archive/2026-09-06-active-organizer-log.md`

## 交接协议（2026-09-06 Steve 定，三条线同样）

- **每到一个停点——PR 开好、被阻塞、一条任务收工、需要裁定——立刻 `send_message` 给 organizer session（标题含「organizer」），不要等被问。** 消息带：任务 ID、PR 号 + head SHA、一句话结论、下一步打算。
- organizer 的合并 / 发布 / 退回意见也会 `send_message` 回来；PR 评论是备份不是主渠道。
- 收到退回意见：改在同一 PR，推后再发一条消息（带新 head SHA）。
- 一条任务收工后**不要空等**：接卡里与在审 PR 文件不重叠的下一条，并在消息里说明接了哪条。
