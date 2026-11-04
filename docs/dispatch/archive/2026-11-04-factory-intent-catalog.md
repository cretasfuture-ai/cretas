# 工厂意图目录整合

- Task: `FACTORY-INTENT-CATALOG-20261104`
- 状态：`in-progress`；用户已明确要求接管，唯一写 Owner `/root`（01a1a59f-7fcf-76f2-82a2-2cfd0d5fce98）。
- 独立 worktree：`C:/Users/Steve/cretas-factory-intent-catalog-20261104`
- Base: `d2eb41679d9575fc404ef260440de4523b49d010`（从原交接 Base 2c9578b59b fast-forward 到 fresh origin/main，无冲突）
- 用户授权：退出餐饮业务，删除餐饮专属意图及链路；保留转换未完成的工厂共用能力，整理合并、Tool/Skill、参数解析与训练目录。
- 不接管主线程训练 worktree，不替换生产模型，不操作生产业务数据。

## 范围

预定范围为 factory intent catalog、识别/执行入口的目录约束、餐饮专属 Tool/Skill 清理、目录生成和校验及相应测试。源目录快照347条，处置依据见 `docs/architecture/factory-intent-retirement-20261104.json`。主线程须核验现有训练安全检查点及唯一写 Owner 后开始；不得将本文件视为代码清理、集成或部署完成证据。

## 完成条件

1. 所有餐饮专属意图从可识别、可执行、可训练目录移除；不得仅根据旧业务域标志删除。
2. 工厂共用能力有明确 Tool/Skill 绑定，不能绑定到不存在的工具。
3. 标签别名归一不合并读写或不同业务状态，参数与实体解析继续保留租户及歧义检查。
4. 移除专属实现前追踪共享依赖；模型保护集与当前训练产物不被修改。
5. 相关源码验证、构建和适用迁移/JPA门禁通过后才集成。所有未完成项单列，不把目录生成称为线上删除。

## 初始状态

已建立独立分支并核对347条快照；81条餐饮专属意图列入删除，29条原餐饮域配置列入保留转换；其中效率别名可合并，初步规范目录265类。代码、配置及生产运行尚未删除或替换。

## Scope 与验证登记

- Root：`docs/architecture/factory-intent-*`、本台账、`src/main/resources/ai/factory-intent-catalog.json`、Java目录/识别/执行/ToolRegistry/SkillRegistry接线与新迁移、对应Java目标测试。共享API/Entity/Repository变更按实际触发真实JPA门禁。
- Root 安全接线补充：保留能力源审发现 `FoodSampleCreateTool` 跨厂批次关联、待销毁查询误标WRITE，以及全局Skill compose/manage被工厂角色调用；允许修复这4个Tool及platform主体门、对应食品留样测试和既有MaterialBatch真实JPA测试。使用已存在的factory-scoped查询，不扩展Entity/Repository字段/方法。
- Root 读写契约补充：修复已知召回损失估算暗写、待发查询混用确认写Tool、上一页错绑下一页；摄像头订阅/取消补权限、preview与明确WRITE类型，密码重置引导按实际无副作用声明READ。新增只读待发Tool/共享查询及上一页Tool，对应目标契约测试。
- Python Owner `/root/catalog_python`：`backend/python/ai/{factory_catalog.py,db.py,api.py,orchestrator.py,registry.py,matcher/candidates.py,matcher/semantic.py,rag/retrieval.py}`、`scripts/finetune/{flywheel_reviewed.py,export_training_data.py,generate_full_training_data.py,generate_full_training_data_v2.py,training_label_contract.py}`、相关Python目标tests及必要的训练目录生成器；不得改模型、mapping、holdout或旧微调worktree。验收：相应pytest与目录契约。
- Python 第二切片同Owner：`smartbi/gold/restaurant/{restaurant_ops_router.py,restaurant_intent_service.py,restaurant_intent.py,restaurant_intent_promotion.py}`、`smartbi/agent/runtime/{descriptors.py,restaurant_read_tools.py}`、`smartbi/agent/synthesis_engine.py`、`smartbi/api/{chat.py,gold_reads.py,restaurant_ops_gold.py,synthesis.py}`、`backend/python/main.py`及直接目标tests；收掉专属AI resolver/dispatch与专属AI router挂载，通用日期/租户/澄清helpers有依赖则保留。不得删除餐饮业务数据、共享ETL/Entity/外部SQL表或整个gold.restaurant目录。验收新增retirement dispatch契约＋相关共享factory回归；完整Python相关验证由Root收敛。
- Java 专属Tool清理 Owner `/root/catalog_java_retire`：`src/main/java/com/cretas/aims/ai/tool/impl/restaurant/**` 中仅retired_tools或restaurant_专属实现及直接对应专属测试；必要非restaurant目录中的清单retired_tools先回报root再扩scope。不删除共享服务/Entity/Repository/HTTP业务路由，不碰Root的ToolRegistry/SkillRegistry/目录文件。先追依赖，返回删除/保留对照。验收由Root在同一worktree串行Maven目标测试与完整编译，worker不得竞争target目录。
- 数据与模型边界：本批不执行训练，不修改 `evaluation_holdout.jsonl`、旧35条、当前79条新测试或现有259头/候选产物。旧实验源码提交867de8ea8f留在独立训练分支；报告在本机Documents/Cretas意图微调-20261104。
- Java 第二切片同Owner `/root/catalog_java_retire`：专属清理已返回，下一scope为新 `ai/tool/impl/factorycatalog/**`、`service/intent/catalog/**` 与同包测试，补6个此前无等价实现的工厂共用入口；优先复用真实Service/DTO，不造完成结果。既有Repository/Entity/业务Service修改必须先向Root报精确文件再扩scope，任何新增查询需真实JPA/数据库验证。Root单写共享目录和迁移；worker不写Maven target，不运行生产操作。
- Java 扩scope已批准（仅该Owner）：`dto/material/CreateMaterialBatchRequest.java`、`service/impl/MaterialBatchServiceImpl.java`、`service/inventory/impl/InventoryLedgerServiceImpl.java`（仅FREE_GIFT零价/幂等/真实流水，不放宽采购价格）、`repository/inventory/SalesOrderItemRepository.java`及产品排名真实JPA测试、`service/impl/LabelServiceImpl.java`/必要`service/LabelService.java`（固定现行格式抽共享policy，不伪造配置）。赠品需新增唯一索引/表结构则先报Root；Root单写迁移。盘盈Tool必须只应用已OA审批APPROVED的正差盘点单，不代填盘点数/发起审批。税率分组开票保持人工入口410，不绕过自动开票政策。
- Java 最后接线scope同Owner：`repository/inventory/SalesDeliveryItemRepository.java` 增加真实factory-scoped发货明细lookup，并接入新allocation Tool及真实RepositoryQueryValidationTest。Gift并发唯一索引由Root新增，仅作用FREE_GIFT非空source_event_key，不改变采购/其他来源幂等约束。
- 下一必要验证：规范目录三分区/别名与真实Tool-Skill绑定闭包；退役标签在短语、候选、RAG、执行及训练入口均被拒绝。最终一次运行相关Python、Java与迁移门禁和完整编译，日志留独立证据目录。
- Java inventory补充同Owner：tool-descriptors.yaml与descriptor目标测试，按真实源同步8个新增Tool、摄像头metadata及关闭餐饮migration lane；不改catalog JSON，不运行Maven。Root串行验证。
- 绑定验证Owner /root/catalog_python：仅新增Java ai/catalog/FactoryCatalogRuntimeBindingContractTest.java；用真实Tool类metadata、ToolRegistry与SkillRegistry验证29保留能力绑定与11只读Skill闭包。不得运行Maven或改实现/JSON。Root负责最终验证。
- Python旧断言收敛Owner /root/catalog_python：仅backend/python/tests/test_restaurant_intent.py与后续完整门禁返回的专属AI正向测试（其它文件先报Root扩scope）。将已关闭入口改测不分类/410/不dispatch，不删除共享helper/租户/日期/ETL测试，不新增skip/exclude；相关pytest验证。
- 风险收敛同Java Owner：Gift Tool要求独立totalWeight公斤参数而非把箱数当重量，新增非kg DTO回归；排名JPA金额按原行单价合同校准；既存SupplyImpactQueryTool补显式READ与EXPLICIT descriptor，不扩legacy冻结基线。Root接管rankingService可信身份/scope及scope契约。
- Java路由闭包Owner /root/catalog_java_retire：接管IntentExecutionOrchestrator.java、DynamicToolSelectionService.java、SseStreamingService.java、保留TieredIntentDelegate.java中餐饮专属委派入口及直接对应旧餐饮AI测试。消除绕过catalog的自然语言餐饮/agent/fallback路由和retired默认建议；保留工厂/共享工具与日期helpers，Root不并发写上述四文件。新增关闭契约，不加skip/exclude。
- Python旧断言扩scope：完整门禁602失败均需逐项归类；/root/catalog_python允许修改门禁失败涉及的45个Python测试文件（仅旧餐饮AI入口/注册/成功断言），不改实现/CI/excludes/共享helper断言。删除的专属用例须列出nodeid与退役原因；混合测试保留工厂/安全/日期/歧义部分。验收失败文件目标pytest与退役契约联合；完整日志保留不伪称初次全绿。
- Skill执行结果Owner /root/catalog_skill_result：仅service/skill/impl/SkillExecutorImpl.java与新Skill结果ContractTest，修复单Tool/多Tool success=false及NEED_MORE_INFO传播、不继续执行失败链；接现有实体grounding参数链。不得改Dynamic(现归路由Owner)、catalog/model。Root最终Maven。
- 税率分组闭环Owner /root/catalog_skill_result：接管factorycatalog/TaxGroupInvoiceTool.java，新增service/intent/catalog/FactoryInvoiceTaxQueryService及对应真实Service/Repository契约测试；复用现有厂级发票/订单查询返回自动生成记录的taxBreakdown/status，缺ID澄清，人工ISSUE仍410，零写入。允许同步FactoryCatalogToolBehaviorTest税率用例与FactoryCatalogDispatchBoundaryContractTest税率方法；Root不并发改这些文件。
