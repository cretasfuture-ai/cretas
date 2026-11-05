# SOP 自动链写入口边界

任务 FOOD-DAG-SOP-WRITE-GATE-20261105；Base SHA 50ec3c63e1b08fde335895af4b6818169c0476b5。
Owner 食品DAG独立session 01a1a954-13e5-7182-9572-00ff306c8d31。
分支 codex/food-dag-sop-write-gate-20261105；工作树 C:/Users/Steve/cretas-food-dag-closure-20261105。

范围：SopAgentOrchestratorImpl.java、SopAgentWriteBoundaryContractTest.java、原SopAgentOrchestratorImplTest.java的真实分类器/READ夹具、本归档。
补 #1440 P1-1：可配置SOP链即使执行人权限合法，也不能直接调用任意业务写工具。
中央 WriteGuardService 分类判为写时拒绝；缺少分类器亦拒绝，不接受请求确认标记。
唯一明确自动写例外为既有 sku_update_complexity：上传SOP衍生的SKU复杂度元数据；仍须真实执行人、同工厂与权限链通过。未开放库存、财务或其他业务写入。

验证：新ContractTest从public executeRuleToolChain运行，包含拒绝任意写、声明/启发式不一致、未声明、伪造确认、READ阳性、元数据例外阳性与权限撤回/分类器缺失阴性；同时保留原SOP SpEL沙箱测试。
无Entity/Repository/迁移修改。源码冻结后只运行本切片目标及必需CI；测试、commit、push、PR、部署分别取证。
前批 #1460 已合并，202测试与候选stage不包含本补丁；不得混算。本组无生产发布锁，遵循三session前序发布协调。
