# F006-MOBILE-R11-TIME-ACCESS-20261004

状态 in-progress；协调/台账写 Owner root，执行 f006_mobile_r3_production；Base 27756a50e702fb2fffcdf487c11e1c2ea4e68611；工作树 C:/Users/Steve/cretas-f006-mobile-r9-20261004；branch codex/f006-mobile-r9-time-stats-20261004。

实际只读证据：F006 cashier 无 HR 模块许可仍能 GET /time-stats/workers，HTTP 200 空数组；此窗口未观察到人员数据，不夸大已发生泄漏。viewer 自查、数据库与真实 canonical fallback 都具有 HR 只读权限，不能将其聚合读取定为越权。Controller 汇总未标权限/module，现有拦截器对未标方法放行。员工考勤页面实际使用 /workers/{ownId}，不能给整个类套 HR 限制而破坏本人入口。

唯一代码 scope：backend/java/cretas-api/src/main/java/com/cretas/aims/controller/TimeStatsController.java；新增 backend/java/cretas-api/src/test/java/com/cretas/aims/controller/TimeStatsControllerReadAccessContractTest.java。汇总 GET 所有别名按既有 hr:read/hr:read_write 与 hr_employee 有效模块限制；个人端点仅允许同厂有效 caller 查看本人，查看别人须 canonical HR read + 当前有效 module + 工厂配置许可。复用既有 UserRepository scoped lookup 与 PermissionService/UserModuleAccessService/FactoryConfigService；不改角色映射、Security配置、实体/Repository/查询/迁移/Service/DTO。现有写接口生命周期不扩展。

验收：真实 MockMvc + 实际 PermissionInterceptor/ModuleEnabledInterceptor；所有聚合 alias 未授权 403/业务Service零调用、HR合法读取成功、自身 operator 无HR能读本人、他人拒绝、self GRANT 不能扩 canonical、module DENY/工厂关闭、异厂/缺 actor/无效 ID 等。新类命名 ContractTest；目标 Maven TimeStatsControllerReadAccessContractTest,TimeStatsServiceImplContractTest,GateSelectorCoverageContractTest；cretas.testIncludes=**/*.java；本 WT Maven顺序、日志到私有 C:/Users/Steve/Documents/Cretas-F006-手机测试-20261003/r11-*.log。

禁止 Git commit/push/PR/台账写、数据库/业务HTTP/服务器/设备/runner/依赖安装/子代理。其他协作者存在，不回退其改动。R9 Service 源已冻结，不修改；返回 task/Base/实际文件/验证命令结果/阻塞/下一动作。root 最终 diff/权限终审、统一验证与发布，单测不代替生产/手机验收。

root 收回唯一写 Owner 后完成个人端点所有有效员工自读、真实 canonical viewer/hr_admin 允许而 cashier/operator 拒绝的 13 聚合别名测试。导出 CSV 仅生成报告，但能绕过账号模块 DENY，补充 export 的 hr_employee 模块注解并以真实拦截器覆盖工厂关闭 400、账号拒绝 403、合法导出 200/实际 service 调用，以及真实 canonical viewer 只读不能导出。cleanup/recalculate 生命周期仍未修改，不能据本批宣称已完成这些写接口的安全审计。独立权限复核及 export 增量复核均 P0=0/P1=0；最终五类 Maven 160 项 F0/E0/S0 通过（Controller 136、Service 11、JWT 6、Module 3、selector 4），日志私有 r9-r11-export-reviewed-final.log；尚未 commit/PR/合入/生产验收。

源码由 root 冻结保存；尚未创建 PR、合入或发布。终审包括导出新增模块闸，日志及 XML 汇总私有 r9-r11-export-final-test-receipt.json。
