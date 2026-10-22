# ERP-S308-WAREHOUSE-DELIVERY-20261023：原判据修复切片

Base eb299396dc7c0a27ca22f24efe73112c2e130289；代码唯一Owner /root/s308_backend；Root协调/接口/最终审阅；原QA设备/浏览器/CSV唯一Owner不变。工作树 C:/Users/Steve/cretas-s308-warehouse-delivery-20261023，分支codex/s308-staged-warehouse-delivery-20261023。你不是唯一在仓库工作的人，不覆盖/回滚其他Owner改动；不生成孙代理。

已证实原UI同厂只有一次确认，没有发运/分收，S308在建单前FAIL且业务写0。用户要求按原判据修复交付。保留全部旧原子同厂confirm和跨厂行为/断言；新仓内分段交付为独立显式动作，不改原ship/receive语义，不调用生产transit-ledger冒充。

## 精确允许文件
backend/java/cretas-api/src/main/java/com/cretas/aims/controller/inventory/TransferController.java
backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/TransferService.java
backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/TransferServiceImpl.java
backend/java/cretas-api/src/main/java/com/cretas/aims/entity/inventory/WarehouseTransferMovement.java (new)
backend/java/cretas-api/src/main/java/com/cretas/aims/repository/inventory/WarehouseTransferMovementRepository.java (new)
backend/java/cretas-api/src/main/java/com/cretas/aims/dto/inventory/WarehouseTransferDeliveryDTO.java (new)
backend/java/cretas-api/src/main/java/com/cretas/aims/dto/inventory/WarehouseTransferReceiptRequest.java (new)
backend/java/cretas-api/src/main/resources/db/flyway/V20261103_77__warehouse_transfer_movements.sql (new, reserve77;合入前再查main和其他Owner)
backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/impl/TransferWarehouseDeliveryContractTest.java (new)
backend/java/cretas-api/src/test/java/com/cretas/aims/repository/inventory/WarehouseTransferMovementRepositoryQueryValidationTest.java (new)
backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/impl/TransferWarehouseDeliveryPostgresContractTest.java (new)
backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/impl/WarehouseTransferMovementMigrationPostgresContractTest.java (new)
其他路径、Entity/InternalTransfer和Item/enum、Gateway/S6/采购/供应商/权限/Orchestrator/发布/工作流配置均无写scope，确需扩展先回EXPANSION_REQUEST。不要改pom/共享测试helper。禁止修改ACTIVE/CSV/生产/设备/浏览器/启动生产定时。


验收：目标selector见上述包；真实JPA及隔离PostgreSQL migration/数量守恒/回滚测试必需。既有同厂原子confirm与跨厂所有原断言保留。默认只发明确批次的公司物权原料，独立新增warehouse-dispatch/warehouse-receipts，不改既有ship/receive。禁止生产写/部署/merge。迁移77为候选，合入前与Claude75/76及fresh main再协调。


测试资源切片 `ERP-S308-PG13-RESOURCE-20261023`：唯一资源 Owner `/root/native_ci_queue_live`，仅允许在 `C:/Users/Steve/Documents/cretas-optimization-20261019/execution/coordination/s308-portable-pg13-20261023/` 新建私有 PG13 data/log/随机凭证，复用原 portable PG13.23 二进制只读；候选 loopback 端口55327现场证明空闲后启用。shared_buffers≤128MB/max_connections≤20，源码Owner单Maven2CPU/3GB。禁止碰原S107集群、其他容器/数据库/runner/WSL/Docker；无测试客户端后正常停自有集群，数据/证据保留，不递归清理。验收：真实server_version/数据库名/端口/进程归属及实际启动退出，凭证只在私有文件，不能入tracked或输出。该资源准备不算产品/JPA/PG门禁已通过。

## ERP-S308-UI-20261023
Base eb299396dc7c0a27ca22f24efe73112c2e130289；唯一UI Owner `/root/s308_ui`，worktree `C:/Users/Steve/cretas-s308-delivery-ui-20261023`/branch `codex/s308-delivery-ui-20261023`，与后端12文件不重叠。Root固定warehouse-delivery接口v1：supported/staged/stage、每行shipped/received/pending及receipts(requestKey/operationId/targetBatchId/quantity/unit/actorId/createdAt)、inTransitByUnit；收货请求为UUID requestKey与本次增量itemQuantities，未知响应先GET恢复。

精确写scope：
- web-admin/src/api/warehouseTransferDelivery.ts（新）
- web-admin/src/views/transfer/detail.vue
- web-admin/src/views/transfer/WarehouseDeliveryPanel.vue（新）
- web-admin/src/views/transfer/__tests__/warehouseDeliveryPanel.spec.ts（新）
- frontend/CretasFoodTrace/src/services/api/transferApiClient.ts
- frontend/CretasFoodTrace/src/screens/factory-admin/inventory/TransferDetailScreen.tsx
- frontend/CretasFoodTrace/src/utils/warehouseTransferDelivery.ts（新）
- frontend/CretasFoodTrace/src/__tests__/unit/screens/warehouseTransferDelivery.contract.test.ts（新）

验收：Web `npm exec -- vitest run src/views/transfer/__tests__/warehouseDeliveryPanel.spec.ts src/views/transfer/__tests__/transferDetailUnit.spec.ts src/views/transfer/__tests__/transferAwaitingConfirm.source.spec.ts --maxWorkers=2 --minWorkers=1`；RN `npm exec -- jest --runInBand src/__tests__/unit/screens/warehouseTransferDelivery.contract.test.ts src/__tests__/unit/screens/transferEnumLabels.contract.test.ts`；正常hook和diff检查。只目标测试，依赖独立安装、禁止junction/锁文件改动、Node≤2GB；不构建/发布/合并/生产写。原QA独占验收资源，源码实现不冒称APK/OTA或真机通过。细节/UX门控与反例见私有coordination/s308-ui-workpack-20261023.md。

后端写Owner续行：原 `/root/s308_backend` 在 HEAD c87f4988d4bf05a6bce9781fc69684c6e31427e0 明确回传 SAFE_CODE_OWNER_RELEASED=true、activeTests=0、clean、无未收取session；Root自本安全点接管相同12文件继续DIRECT修复/目标验证。原WIP与失败保留，不并行写同scope。真实PG首个DDL仍是MySQL drop foreign key语法；未完成migration/PG守恒/rollback门禁，不能算通过。

必要门禁接线（Root DIRECT）：额外仅允许 `.github/workflows/java-gate-pr.yml` 的既有 PostgreSQL `-Dtest=` 行追加 `TransferWarehouseDeliveryPostgresContractTest,WarehouseTransferMovementMigrationPostgresContractTest` 两个新类，不改触发、路由、资源或任何既有选择器。原因：这两类必须真实PG执行，普通无PG环境禁用不算通过；已有opt-in治理任务仍保留，Root不领取其存量范围。现场ACTIVE无该文件独占写锁，当前仅本次两类接线；验收为diff精确追加、YAML解析、原PG门禁实跑与新类零跳过日志。scope由12产品文件加此单行扩为13文件。

PR1115门禁失败补件（Root DIRECT、独立现有WT `cretas-s107-timer-delivery-20261022`，Base e754b5e37006d13d0046a9f50f0e23df0b86344d）：新增精确scope `scripts/cron/soft-delete-orphans.sql`、`scripts/cron/soft-delete-orphans.hasdel.txt`，只由既有生成器 `bash scripts/check-soft-delete-orphans.sh --write` 只读生产后生成，禁止手改快照/SQL语义或cron运行配置。原失败真实diff仅实体计数514→515；验收 `bash scripts/tests/test-soft-delete-orphans-sql-drift.sh` 及生成diff/来源。Backend官方build在另一WT运行，不改其源码/target。Ops失败另待读取原断言后精确登记，不自动放宽范围。

后端冻结5bb99本地目标/真实JPA/PG/Flyway72项零失败零跳过；合流98bfa6仅文档，backend tree同为b442de1，按内容等价复用。PR1115完整检查已启动，不代替发布/业务验收。首次CI的Ops两项失败均来自同一生成物漂移（非选择器断言）；生成器在含真实候选实体的隔离副本重新读取生产后，只改变出处时间/实体统计514→515，关联62对及SQL语义不变；原断言未改，两项目标均通过。第一次仅main副本的生成结果不能修复候选，原件及纠正说明保留。

回滚边界：Java先Web，正式发布回执前不发运；首次分步发运后禁止回旧binary遗留同厂SHIPPED。故障冻结新发运并向前修复，或当前安全版本先合法收齐所有开放分步单后评估回滚，V77表/事实不删。main/生产现74且out-of-order=false，本批V77先交付时，S6未合入75/76须由原Owner在fresh-main重编号后另验，不启用乱序。

既有独立边界 `ERP-INV-DELAYED-CONSUMPTION-LOCK-20261023`：Clerk/ProcessSheet延迟消耗与旧调拨的同批并发协议在Base已同样存在，不是本次新增。原QA源夹具独占隔离，未宣称全局生产并发已解决；后续精确scope尚未释放，禁止顺带改共享报工/采购/Orchestrator。源码终审、局部PG、共享CI、正式部署与原QA签收分别记录，S3-08原FAIL保留。
