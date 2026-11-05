# 餐饮退役最终 scope 归档

以下条目随 PR #1479 正常合入时归档并释放；未合入前仍由方案审计组保护，不能用本文冒充合并、部署或业务验收。已暂停四项新开发和商城没有恢复；其他 Owner 的条目及 S6-10 本人签署 blocked 状态原样保留。

## 内容与验证绑定

- 餐饮专属 Java/Python/App/Web/POS/模板/运行入口退役，工厂库存、供货、财务、OA/KPI 能力已迁出。未知租户元数据 fail-closed，附件查询先授权；Supplier raw→parent refresh→spec 锁序保留。
- 1fa2 的十个工作流成功：Java run42081341602 全量19490（19177通过/313跳过/零失败错误/基线0），PG346（零失败错误/2既有跳过）。54f6 的成本增量 v28 三类60全通过；该head全量CI42093630333收取自然终态，不冒充后续head验证。
- 最新main4e6e物料接线已正常整合到34e8067a234a436f15bce341e7dfccf32355150a。人工冲突仅PG selector双方追加，保留main原73类完整前缀并追加两项，75类无重复；独立安全终审 Source PASS/无新增P0/P1。
- 当前34e组合使用官方 release-cretas-artifacts.sh，显式65类（食品32与本组37并集；迁出的采购测试按新工厂类名运行），71 XML共542项，541通过/1外部OSS显式opt-in跳过/零失败错误。真实PG执行包括Supplier引用并发、Inventory软删除与JPA/native查询；普通real-service+mock采购测试不冒充真实PG。
- 原PG包装器45秒清理等待超时保留exit1；随后私有server日志已database system is shut down、pg_ctl显示server stopped、postmaster.pid消失且61569无监听。没有停止其他进程。测试/XML和制品成功与包装器失败分别保留。
- 官方 release-cretas.sh --phase build --base-sha 41f58a822cf --tests <上述65显式类> --stage-backend YES-STAGE --no-prefer-ci-artifact 成功，OFFICIAL_BUILD_EXIT=0、RELEASE_FINAL_STATUS=built恰一次；双制品复用，无重复Maven。receipt cretas-1793908645-2034；backend tree073cb09e56cca1832d00111c9aedfb11f0d470df，JAR SHA-256 342b239ffc9fab3c98436d53f0ce94696439a2c2cb40c13328f18cdbc33ef65c，已预上传不可变缓存，deploy_mode=none。
- V20261105_92放弃/释放，本组无新增DDL；组合候选继承main V94，发布保留其迁移及兼容回滚floor。保留全局photo_hash身份和历史数据，不删除真实工厂。无本批生产安装/重启/切流/迁移/业务写或Android验收事实。
- 证据位于本机 cretas-restaurant-removal-evidence-20261105；完整失败/成功日志及原XML留存不入Git。文档收尾不改变产品树，不触发本地产品测试重复执行；后续最终head必需CI、正常合并、exact-main官方发布和线上验证仍分别完成。

## 归档条目
- `SEC-RESTAURANT-SSE-FIXTURE-20261105` — `review` — Owner root — Base c21afba0f7 — scope PaymentSseActor/ClarificationRoleAudience/SkillRouteGuard/SseConversationPagination/SseF006BossMini/SseSemanticCacheAdmission/SupplierComparisonStreaming七阳性tests；exact Factory；root测试。
- `SEC-RESTAURANT-FACTORY-COMPAT-20261105` — `review` — Owner root — Base 41f58a822cf — scope Python warmup及materialized共享模板tests；已冻结f883/Python full5983pass，PR1479最终交付，详归档。
- `SEC-RESTAURANT-OPS-20261105` — `review` — Owner root — Base 41f58a822cf — scope scripts餐饮cron/ETL/演示/E2E及Python部署引用；清除运行入口/重新生成readonly bundle，保留工厂场景。验收Node/shell/部署契约；4执行者目录产物隔离，冻结即释放。
- `SEC-RESTAURANT-JAVA-20261105` — `review` — Owner root — Base 41f58a822cf — scope backend/java及tests；餐饮退役、工厂供货、权限/配置/模板拦截；Canvas仅退休角色deny，不改DAG/缺料/采购审批业务。验收Maven、真实PG/JPA、factory回归，详归档。
- `SEC-RESTAURANT-PYTHON-20261105` — `review` — Owner root — Base 41f58a822cf — scope synthesis_engine tests与test_synthesis_auto_dimensions/dish_margin_plan；恢复被改名隐藏的factory断言，删纯餐饮case，禁改生产源码/其他tests；pytest目标及_retired_test零扫描。
- `SEC-RESTAURANT-UI-20261105` — `review` — Owner root — Base 41f58a822cf — scope web-admin及frontend/CretasFoodTrace；删除餐饮专属页面/导航/API/角色入口，迁出工厂复用。验收Web/RN类型/构建/入口契约；禁止Canvas/缺料/演示业务修改。
- `SEC-RESTAURANT-FACTORY-STORE-20261105` — `review` — Owner root — Base 41f58a822cf — scope Python AgentOps store/validation及factory ACL/trace回归；保留旧factory精确重放，pytest+独立PG证据见归档。
- `SEC-SUPPLIER-SPEC-LOCK-20261105` — `review` — Owner root — Base 80ba92ace2 — scope 独立supplier-guard-verify WT的SupplierMaterialPurchaseSpecServiceImpl及其单测/PG引用契约/JPA查询测试；接eb guard、锁后父关系复核；root独占Maven/commit。
- `SEC-RESTAURANT-INTENT-FIXTURE-20261105` — `review` — Owner root — Base 19923121a6 — scope 两库存工具Java、gateway双YAML/declared清单及GatedData/Demo/DescriptorPolicyResolver/ToolGatewayPriceBoundary/RuntimeToolPolicyLoader/ToolDescriptorInventoryDrift测试；最小权限与精确描述，root Maven/SCM。
- `SEC-RESTAURANT-RETIRED-TESTS-20261105` — `review` — Owner root — Base 19923121a6 — scope inventory的SupplierDeliveryNote/WastageRecord及FactoryInventorySoftDeleteRepositoryQueryValidationTest、soft-delete-known-violations.txt；补类级软删除并真实PG验证；root Maven/SCM。
- `SEC-RESTAURANT-JAVA-CLIENTS-20261105` — `review` — Owner root — Base 5fa4d12c5d — scope PythonSmartBIClient/GoldFinanceClient、KB/SemanticMatcher、IndicatorQueryServiceImpl/LlmIntentFallbackClientImpl及tests；迁工厂共用、删餐饮HTTP/phrase/domain闭包；root Maven，禁Orch/权限/Supplier。

## 随后 main SOP Phase B 整合

main342dc816fd（1490）新增生产SOP写入开关及Web/App文件入口，已无冲突整合；本组不回退其已授权开关。Java生产类、Entity/Repository及锁序均未变，Attachment先授权后查询和typed actor继续保留。配置/rollout测试与Web/App构建按新内容补验证，旧542项与34e产品树继续单独绑定，不冒充新资源树已通过。回滚floor仍为095558或后续actor兼容版本；未发本批App OTA。

## 后续全量四红与最终主线整合

8acd的Java42101571730完整19554项中19207通过/343跳过/4失败，PG与其余九工作流成功；未扩大失败基线。四红均精确复现于旧采购超收夹具：新增真实物料guard所需Repository为空或无活动物料快照。仅两测试补齐真实guard依赖，保留409/零库存写并增加合法边界仪器，生产逻辑/guard/阈值未改。补丁首轮缺import编译失败、零测试保留；修正后两类14项全过。

正常整合main95及1495实际pg-prod启用/格式修复至c2e031，官方12类77项全过、零跳过，真实PGcommand0/stop0，双制品成功。候选诊断preflight完整PASS（包括原版diff门禁），不是exact-main生产preflight。1495正式回执cretas-1793913702-402由主协调确认95紧接94、宽191、原70/85行数保持，不能把此前a23合并冒称当时已发布。

最新APS1496/f158原样整合，无源码冲突；保留76类PG并集、pg-prod true、typed actor与本人签署blocked。其新增三类APS契约与本组十二类进行组合验证，并由当前冻结head的正式CI验证；全部结果按实际版本报告，不把旧8acd失败称通过。Java生产类之外的Python/App/Web内容未随此次后端整合变化，同内容证据复用。
