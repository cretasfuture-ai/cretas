# DAG19 OSIV 事务边界修复

任务 `FOOD-DAG19-OSIV-20261107`，Base `5001fadddc70a619b128e57d7f6aeea8f234e768`，唯一写 Owner 食品 Root / 01a1a954-13e5-7182-9572-00ff306c8d31。独立 WT `C:/Users/Steve/cretas-food-plan-readiness-20261107`，新 branch `codex/plan-readiness-osiv-transaction-20261107`，不在已合并分支续提交。

实际缺陷：1531 正式169秒服务级验收成功，但 canonical 新接口 GET 返回500/C0789EF4，业务写0。green 实际异常为 PostgreSQL `Cannot change transaction isolation level in the middle of a transaction`。生产非 autoCommit 与 OSIV 先前查询已开启物理事务；没有外层 Spring 事务时，单独 REQUIRES_NEW 仍可能复用请求绑定 EntityManager。旧 CI/真实 PG 直接调 service 未覆盖此 HTTP 请求形态，不用旧绿灯否认生产500。

Root scope 仅 `PlanConcurrentReadinessController.java`、既有 `PlanConcurrentReadinessPostgresContractTest.java` 和本任务 dispatch/archive。先用真实 PG、OSIV 请求绑定 EntityManager 与非 autoCommit 热连接复现，再做最小事务边界修正；保留 service REPEATABLE_READ/REQUIRES_NEW、全部厂/行/模块/权限/保密与审计门禁，不改全局 DataSource、OSIV、N1 或业务写入口。

验收：真实 PG 新反例/既有7项、readiness MVC/Service/标准料及成本相关目标，必要源码契约/必需CI，官方同组合制品构建、normal merge、exact-main 正式发布；复用原 CANCELLED 自有 F006 计划完成 canonical 零业务写 UI/API/SQL。原500报告与原正式回执独立保留，不覆盖，不造新业务夹具。

源码冻结验证：修前真实 PG 8 项中仅新增 OSIV 例报错，原7通过，错误调用栈与生产相同；修后7类56项全部通过，含真实PG8。OSIV holder 恢复、Spring事务清空、DB状态不变均实际断言。自有PG59055已guarded STOP0并回读marker/监听均无。独立风险复核 CLEAR：controller 外层仅默认隔离只读事务，让 service 真正挂起该请求 EM；service 的新连接快照及保密审计独立 REQUIRES_NEW、不吞失败均保持。

本最终候选提交归档源码任务并释放文件 scope；Root 仍独占本批 CI/官方构建、合并、发布与原QA安全验收责任，按本组协调状态接最近真实锁窗口。候选测试不表述为修复已上线或500已经业务实收关闭。无前端/RN/OTA/DDL/Entity/Repository/全局连接池配置改动。
