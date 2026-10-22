# 独立日期修复与 V72 被动切片登记

Base（登记现场 origin/main）：`56e43461dd59a8d22a07a8cf84fbe2af1373de42`。

Root 台账写 Owner：`01a1523e-881c-7a92-b7ab-f4b8c5a552df`。原源码 Owner `01a15272-374d-72c0-bb20-cdfa0bd823f1` 已于 2026-10-22 11:36 +08 明确仅释放 ACTIVE 写范围；代码、集成、合并、正式发布继续归原 Owner。未转移其 worktree。原 QA `01a15272-36e3-76c0-a6a1-977df6dba052` 继续独占手机、浏览器、canonical CSV。

当前登记在 `C:/Users/Steve/cretas-s1-native-date-20261022/docs/dispatch/ACTIVE.md`；登记先落本地，与生产发布隔离，不在在途发布时推 main 或取消任何检查。

## ERP-S1-NATIVE-DATE-REACHABILITY-20261022

- 唯一代码 Owner：`/root/s1_native_date_fix`；Root 仅写台账/本归档。
- Worktree：`C:/Users/Steve/cretas-s1-native-date-20261022`。
- Branch：`codex/s1-native-date-20261022`。
- 精确代码 scope：
  1. `frontend/CretasFoodTrace/src/screens/dispatcher/ai/AIRescheduleScreen.tsx`
  2. `frontend/CretasFoodTrace/src/__tests__/screens/dispatcher/AIRescheduleScreen.reasonOnly.test.tsx`
- 已证实：原 QA 1699/F006 正常生产计划详情 → 记录原因，手机实际今天为 2026-10-22；既存 confirmed 排程 `5522009a-ed4d-4ed8-993c-a0ee0b41d860` 日期 2026-10-20，页面只查询当天且无日期入口，无法选择。0 保存，Native 原缺陷仍 OPEN，Web S1-03 已通过证据不重做。
- 结果要求：通过正常 UI 可选择所需日期并查询正确日的第 0 页计划；显示所选日期，无虚假当天计划。保留 ID 领域、终态拒绝、工厂/角色边界、reason-only 保存、竞态防护和一次保存语义；不改持久化时间/资源/日期以适配测试。
- 目标验证：在 RN 目录执行 `npm test -- --runInBand --watch=false --runTestsByPath src/__tests__/screens/dispatcher/AIRescheduleScreen.reasonOnly.test.tsx --cacheDirectory <本任务独立缓存目录>`。先使真实日期相关回归在旧实现失败，再修复并跑该文件；保留全部原断言，不再常量 mock 日期掩盖 22 日选择 20 日。
- 安装/测试：依赖各自独立，禁止 node_modules/target junction；不改 package/lock/native 依赖。原 512MiB heap 两次启动 OOM、0 断言，保留工具启动 FAIL，不能算回归红灯。Root 根据该实测将此任务预算改为单 CPU、Node heap 1536MiB、任务总预算 2.5GiB，另留系统 2GiB，并预留 Windows light 增长至既有 6GiB 上限。启动前连续 3 次核对实际 CPU、内存及 cgroup 占用，至少剩余 2 个逻辑核；不满足则 HOLD，不抢占任务或降低共享保护。记录真实 RSS 峰值，无法测得则 UNKNOWN，不能把 heap 上限当 RSS。只跑登记目标文件，旧实现断言失败后恢复最终源码再验证通过。
- 禁止：Java/Web/共享权限/采购/供应商/Orchestrator、ACTIVE、canonical CSV、手机/浏览器、生产请求、迁移、构建发布和 main 合入。需扩 scope 先回报，不自行增加文件。最终交 commit 与证据，Source 统一必要检查/正式签名 OTA；原 QA 再做真实日期选择、空/取消、一次保存、刷新及独立 DB before/after。源码测试不替代真机通过。

## ERP-S6-V72-PASSIVE-CLAUDE-20261022

- 状态：`queued`。Owner 保留 `local_f6b118ce-f036-4ece-9a0b-322073b20c74`，不是新接管人。
- 当前该 Owner 原 worktree 已进入后续分支，不把它推定为本切片的独立 worktree。请回传实际拟用隔离 worktree/branch、精确 scope ACK 与命令；收到后 Root 将此条改为 claimed/in-progress，才释放本切片的写 scope。不得在有未提交变更的 B3A/B3B worktree 切分支覆盖工作。
- 待释放 exact5：
  1. `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/config/FactorySchedulerConfig.java`
  2. `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/config/FactoryTriggerChain.java`
  3. `backend/java/cretas-api/src/main/resources/db/flyway/V20261103_72__autonomous_run_as_user.sql`
  4. `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/config/AutonomousRunAsRepositoryQueryValidationTest.java`
  5. `backend/java/cretas-api/src/test/java/com/cretas/aims/integration/AutonomousRunAsMigrationPostgresContractTest.java`
- 对应原候选 PR1075/`41eda6951b56790a4d3989c35e234e4b0f54b204` 的被动基础；B2 实际整体 28 文件不接受整体并入。新 Base 重新从上述 main 建立，不以旧 logical parent 当当前 GitHub Base。
- 验收命令：`mvn -B test -Dtest=AutonomousRunAsRepositoryQueryValidationTest,AutonomousRunAsMigrationPostgresContractTest -Dspring.profiles.active=pg`，PG URL/认证通过 Owner 私有环境注入并按测试实际激活属性执行，不能跳过算通过。原 3 个 PG/JPA 案例全部执行成功；保留既有断言和租户边界。最终命令、激活属性、退出码需回传。
- 迁移门禁另行实测：隔离真实 PostgreSQL 的生产形态 V71 → Flyway V72 → Hibernate `ddl-auto=validate`；旧应用在同一已升级 schema 上冷启动并核对历史/旧行/nullable 默认兼容。`none` 启动健康、`create` Repository 测试、ScriptUtils 和 H2 不替代此门禁。数据库、端口、target、输出独占；不复用生产 DB，不重启共享 WSL/Docker。
- 不包含 Controller/Runner/Policy/DynamicScheduler、Gateway/身份/Skill/Orchestrator、采购/供应商、SOP 或生产定时写。B3A V73 必须等 V72 被接受合入后在该 main 重放与登记；B3B/B3C 未因本登记放开。新 HEAD/新字节仅跑相关目标与适用迁移/JPA门禁，冻结后统一必要完整检查。

以上两包均不因源码/CI/合并/部署就签收业务通过。S0/S1 原 FAIL 与未测保留；S2/S6 不作为当前已冻结 S1 发布的新等待条件。

## 日期修复目标验证回执

候选 commit `e810fa80eb91b2f04a141088bcd36019b0d69b14`，仅上述 screen/test 两文件。最终测试固定真实当前时钟 22 日，正常日期格式，22 日返回空列表，选择 20 日后第 0 页返回既有 confirmed 计划。最终测试在 Base 旧 screen 上真实失败（1 个缺日期入口失败、原 9 个通过、数字 exit1），恢复候选后源码/测试内容与已过的 10 项 Green/exit0 一致，复用 Green，不重复运行。原保存、终态、ID 领域、一次保存、失败保留断言均保留。

最终 screen SHA-256 `03174847394737efa007afe6285a4f0fd318eae214fc20e14f393c116b0fe8c5`；test SHA-256 `02e1ce9535489bdf2560b3cb46925aab97865eb48b010b1fdf505008eef4acdf`。旧 screen 的 `5310c294f4f06f6474b4e939aa9d342b63b35d53` 是 Git blob ID。

私有证据 `C:/Users/Steve/Documents/cretas-optimization-20261019/execution/native-date-fix-20261022/validation.md`、`target-red-final.*`、`red-final-hashes.log`、`target-green-resource-check.*`。3 次准入达到预算；最大采样 descendant RSS 红 1638.2MiB、绿 543MiB，缓存状态不同，不推导性能提升。one JS worker，未设置 CPU affinity，不能称硬限 1CPU；cgroup 实际占用 UNKNOWN，按 Windows light 完整6GiB保守预留。

最初 512MiB 启动 OOM 和 StyleSheet mock 启动错误均为 0 断言，未算产品红/绿。独立依赖安装未改 tracked package/lock，首次未带 legacy-peer-deps 的 npm ci 失败不证明现行 CI 相同失败；正式必要完整检查仍须按原 workflow 入口核验。不改依赖/lock/公共 Jest 配置消音。

仍未完成：push、PR、必要完整检查、合并、正式签名 OTA、真机、独立 DB 及原 QA 签收。源码 Owner 已停止写入、恢复最终内容；Root 仅提交三份协调文档，原 Source 继续负责接收候选和正式交付。S1-03 Web 已通过证据保留，Native 原缺陷保持 OPEN。


## 保留的旧页眉记录

**上一版**：2026-09-07 12:40（本批收口：29 个提交合进 main，软删四批 + prod-only 索引 + 唯一性推回写时全部落地；**新库第一次建得出来**(baseline bootstrap)；归档 5 张已完成票，新增 6 张。🔴 三件等 Steve：DeepSeek key 被拒 12 天 / 09-13 悬崖兜底只剩 1 条 / tenant_purge_68 删不删）。此前：2026-09-07 11:10（Steve 四条裁定落地：业务编号可重用 / 工号不管 / 权限读不到要报错 / 历史单据指向已删合法；#720/#722/#723/#724 合并；🔴 登记 9 张此前只存在于对话里的票，含「建不出新库」与根治方案）。

## 保留的原台账说明

**当前真值**：`origin/main` 为代码与合并真值；本文件只记在飞任务的一行摘要，由 `scripts/ci/check_dispatch_active.py` 钉住（每条 ≤ 300 字 / 全文 ≤ 30,000 bytes）。条目全文与增量日志写 `docs/dispatch/archive/`，最近三份：[2026-09-06-active-organizer-log.md](archive/2026-09-06-active-organizer-log.md)、[2026-09-05-active-entries.md](archive/2026-09-05-active-entries.md)、[2026-09-05-active-organizer-log.md](archive/2026-09-05-active-organizer-log.md)；更早的按日期在同目录下查，⛔ 不在本文件维护链接清单（那份清单曾占本文件 2.6 KB，而它每 session 都被加载）。
