# TASKS-ACCEPTANCE-FINAL-20261105

Owner：01a1a954/root。状态：review（代码冻结、最终PR门禁待跑，未合并或部署）。

登记 Base SHA：`0b2bdd6b8844ff865f898bcea1cea763d6442d76`。
审计依赖的精确 parent：`3db120cfe88432f44019e62eb6ce86af0da14cd5`（PR1438远端codex/claude-platform-ai-analysis）。暂采用stacked PR，仅交付本组diff，依赖入main后转main，不改他组分支。

## 归档与范围

本次统一交付原PR1459（D-S5-01）、PR1461（S4 PG/幽灵占线）及TASKS-S4-BASIS-20261105。旧PR的测试/commit证据保留，由本统一PR替代，不能把旧PR的状态称为独立部署。

允许修改仅三个已登记切片：

- PurchaseInvoiceThreeWayMatcher与既有HTTP测试：厂内物料名称/业务编号，未知或外厂引用明确未知，不泄露外厂标签。
- PurchaseOrderRepository自动采购计数与ShortageScheduleQueryRepository活跃父计划条件，两个采购真实JPA/PG测试及既有Shortage真实JPA验证；CI PG选择器只在parent64类上追加本组1类，无删减。
- ShortageRescheduleService及既有ContractTest：正式Workflow/BOM族、APS日产能/日量/单位/日期依据，漂移409、锁后刷新及严格厂区检查；缺料Web/API与三条组件测试。旧proposal不得显示旧日期或可应用按钮。

不修改两个Shortage Controller、Entity、迁移、单位字典、权限、发布脚本或他组代码。各切片实施/审阅/命令分别见同目录tasks-acceptance、s4-po-postgres、s4-basis归档。代码scope在本候选合入后释放；主任务ACTIVE仅保留部署后的业务复测职责，不保留代码写锁。

## 已有验证与下一次必要验证

- 原发票32目标通过（含HTTP/JPA）；采购PG1、排程PG7通过及独立终审。
- 新S4最终真实PG31/0/0/0（Service23+JPA8），包括managed旧值→锁仍旧值→refresh新值与异厂不变；Web3 suites/12 tests及6GB vue-tsc通过，6文件哈希一致，独立终审无P0/P1/P2。
- 原整合14类119项的build/stage成功仅作历史制品准备，不含后续新S4，不能当最终制品。
- 最终整合将执行下列15个显式类，覆盖本组与已整合的共享修复。源码最终冻结后统一build/stage，再按当前HEAD的PR快速/真实PG/full门禁通过后合并；最终main预热及正式统一发布仍需遵守队列/锁，不把stage当deploy。

`PurchaseInvoiceServiceTest,PurchaseInvoiceThreeWayMatchHttpTest,PurchaseAutoSuggestionPostgresContractTest,PurchaseAutoSuggestionRepositoryQueryValidationTest,ShortageScheduleRepositoryQueryValidationTest,ShortageRescheduleServiceContractTest,ToolParameterDescriptionLeakScanTest,ToolParameterDescriptionScannerScopeContractTest,AIIntentServiceImplPythonIntegrationTest,WriteGuardWiringTest,RestaurantSkillsRegistrationTest,FoodSafetySkillRegistrationTest,FactoryIntentCatalogContractTest,FactoryCatalogRuntimeBindingContractTest,InventoryLedgerServiceTest`

部署后仅复测受影响项：同一第四S4案例在原ghost产线真实UI应用、旧基线409、受控丢响应后按台账恢复；财务UI登记本组错料测试票并标不符释放占量。全部F006，脚本默认dry且记录实际ID/清理或保留。47行唯一场景表和具体业务对象保存在私有证据，不在此公开。真实业务签署不代签，联产成本原豁免保留，全部既有同版本通过证据复用。
