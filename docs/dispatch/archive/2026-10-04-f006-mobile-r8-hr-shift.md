# F006-MOBILE-R8-HR-SHIFT-20261004

状态 review；root/Codex DIRECT；Base `d0db852ef565c0ca688f0e9e5586b9aef59299dd`；WT `C:/Users/Steve/cretas-f006-mobile-r8-20261004`；branch codex/f006-mobile-r8-hr-shift-20261004。

HR页面修复定位证实：已有EmployeeShiftAssignmentController单日upsert和bulk只验证shift工厂，不核员工工厂或有效性，inactive shift也可新分配。尚未做任何跨厂真实写入探测。本批先修服务端再允许本任务隔离F006真机排班写验收。

唯一两代码文件scope：
- backend/java/cretas-api/src/main/java/com/cretas/aims/controller/hr/EmployeeShiftAssignmentController.java。
- 新backend/java/cretas-api/src/test/java/com/cretas/aims/controller/hr/EmployeeShiftAssignmentControllerContractTest.java。

复用UserRepository.findByIdAndFactoryId，不增加/修改query或Entity，保留hr_employee模块与原HR read/write注解。单日和bulk须当前工厂有效员工、当前工厂活跃班次；bulk在任何save前解析/验证全部员工及日期。非整数/非正userId明确拒绝，不截断浮点。现有同user-date覆盖语义保留，既有行身份二检；delete原factory边界保持，不禁止清除历史inactive班次排班。错误不回显任意输入值。数据/权限映射/用户角色/迁移/Repository/其他控制器/服务与生产配置禁止改动。

验收：真实Controller方法+Mockito repositories行为测试，单日正常/跨厂/不存在/停用员工、停用班次、恶意错误返回、浮点/非正ID、bulk含后位非法员工/日期必须save0、正常bulk/覆盖及跨厂delete0；锁定现有模块和动作权限元数据。新Contract进入既有CI选择器；GateSelectorCoverageContractTest同批目标验证。独立安全复核后最后一次目标Maven，测试日志私有r8-hr-shift-*。本批无JPA query/Entity变更，不用纯编译冒充查询门禁；不跑生产业务POST/SQL，不共享node_modules/target，不变更其他worktree或runner。

2026-10-04本地完成：独立只读安全审查源码P0/P1=0，三项P1测试缺口已补齐（错误shift ID、错误既有user/date、真实bulk覆盖和统计）；最后目标Maven EmployeeShiftAssignmentControllerContractTest 25项、GateSelectorCoverageContractTest 4项，共29项通过，失败/错误/skip均0，日志私有r8-hr-shift-java-final.log。diff与台账门禁在提交前检查。源码scope收尾归档，PR/CI/合并/可信构建/部署仍各自待完成；合入时释放本批scope。Controller Mockito测试不等同JWT/模块拦截/事务HTTP或生产验收，注解反射只验元数据。
本厂合法单日与bulk正向、异厂/不存在/停用员工、停用或错误班次、既有行身份不一致、非法ID和bulk后位非法日期/员工、跨厂delete零保存均有行为对照；没有进行真实HTTP业务写或数据库修改。R6前端排班验机前必须先部署本服务端防线；F006当前active班次为0，不能把空配置误判完成创建流程。
