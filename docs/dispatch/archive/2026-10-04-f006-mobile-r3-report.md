# F006-MOBILE-R3-REPORT-20261003

状态 in-progress；Owner Codex 协调者，执行者 cretas_executor；Base `08bf57fc6910668f6c597eb8df801a1733cef796`。
Worktree `C:/Users/Steve/cretas-f006-mobile-r3-20261004`，branch `codex/f006-mobile-r3-reports-20261004`。R1/R2 独立 worktree，不在本批替换、复制或合并其未发布代码。

独占代码 scope（六文件）：
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProcessingServiceImpl.java`：仅生产统计新摘要及其私有 helper。
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProcessingServiceImplProductionStatisticsContractTest.java`（新增）。
- `frontend/CretasFoodTrace/src/services/api/dashboardApiClient.ts`。
- `frontend/CretasFoodTrace/src/screens/reports/ProductionReportScreen.tsx`。
- `frontend/CretasFoodTrace/src/__tests__/unit/api/dashboardApiClient.test.ts`。
- `frontend/CretasFoodTrace/src/__tests__/integration/screens/ProductionReportScreenStatistics.test.tsx`（新增）。

既有生产 stats legacy 顶层字段、查询和其他消费者口径保留；新增明确窗口 `productionSummary`：today 自然日、week 含今日最近7个自然日、month 自然月，截至今日结束；复用现存 Repository `findByFactoryIdAndCreatedAtBetween`，再核 factory/窗口。统计全部状态的实际批次，completed/总数比；逐原始存储单位分组 actualQuantity，不假换算、混单位相加或用计划量填 actual 0/NULL。单位缺失、实际量缺失单独计数，未知率不伪造0。不新增价格字段。手机概览使用新全量摘要并显示范围和单位，最近10条明确为列表样本，不用于总计；数据按 factory/user/role 世代隔离，三个 tab 不出现旧 actor 数据，迟到结果/旧回调无效。

验收：R3 Maven 目标 `ProcessingServiceImplProductionStatisticsContractTest,DashboardTodayScopeContractTest,R6V2ReportControllerRbacTest,ProcessingServiceImplEnhancedCostAnalysisTest`；多类 testIncludes 用 `**/*.java`（不可逗号）。RN 两文件 `jest --runInBand --runTestsByPath`、完整 TypeScript。覆盖不同周期/未来与异厂排除、混单位、actual0/NULL、非completed分母、无数据未知率、后台失败不显示假成功；组件实际点击切换周期和 actor/迟到响应阴性。

禁止 Entity/Repository/迁移/认证授权/质检方法/数据库写/生产请求/设备/网络/依赖更新/commit/PR/台账子代理写入/孙代理。依赖仅只读复用 R1 node_modules junction；Maven 独立 target 限 CPU2。scope 增加先回执请求。

终审后最小scope扩展：允许worker追加仅RN src/i18n/locales/{zh-CN,en-US}/reports.json生产报表键，合计八文件；近7天、按单位总实际量、缺失实际量/缺失单位批次数、最近10条样本、实际状态、权限受限与错误/重试必须有中英正确文案，不借旧kg/平均量键错误呈现。禁止修改quality/cost等其他命名空间、全局翻译体系、依赖或任何未登记文件。独立复核未通过，六项边界已退回修复，需真实RNTL组件替代原三项源码镜像。

第二轮终审 BLOCKED：统计/样本 Promise rejection 及 sample-only 权限会互相清空；capacity 使用不存在的 wire 字段并伪造0；含首尾的7日请求实际8天，week按钮误称本周。scope 追加 `frontend/CretasFoodTrace/src/services/api/reportApiClient.ts` 仅 CapacityUtilizationReport 类型与其真实响应、`frontend/CretasFoodTrace/src/__tests__/unit/api/reportApiClient.test.ts` 仅相应接口断言，合计十文件。保留其余类型/接口，后端产能服务不在scope。分别结算summary与sample、真实nullable产能字段，移除无来源的时间分配推算。验收追加该API测试、真实三个tab的actor首帧/迟到/撤权、分别权限、两方向rejection、nullable/wire、精确7日窗口；Java today/month精确窗口。仍不得改授权/Repository/Entity/迁移，不得创建junction、更新依赖、业务写、commit/PR。

权限专项核对：batches类RequireModule已注册ModuleEnabledInterceptor，校验工厂开关、同厂用户和账号模块有效权限。production_plan角色默认映射production read/write，账号GRANT/DENY覆盖优先，登录modulePermissions不包含这些覆盖。直接查production_plan权限键会误禁合法读者。追加 `frontend/CretasFoodTrace/src/services/api/canvasApiClient.ts` 仅类型安全的既有自查GET `/mobile/{factoryId}/canvas/user-module-access/{userId}` 与新增 `frontend/CretasFoodTrace/src/__tests__/unit/api/canvasApiClient.moduleAccess.test.ts`，合计十二文件；不改变旧canvas方法。page用production_plan.effectiveAllowed对近期样本做独立fail-closed/错误/重试并actor/generation绑定；不要AND角色production read误拒GRANT。不新增权限/覆盖、不改后端授权。新增真实组件role默认、显式GRANT/DENY、权限自查失败/旧响应无效，summary权限仍按其独立endpoint。验收追加上述canvas API测试。

最终冻结验收：Java四类19项F0/E0/S0（私有r3-java-target-final2.log）；RN四套45项PASS（r3-rn-target-final3.log，不使用之前43项r8日志）；TSC final3 exit0，JSON/diff/ACTIVE检查通过。独立只读终审确认原阻断闭合，无新增P0/P1源码问题；三个tab共用actor key/generation，样本self有效权限、两资源错误独立、nullable产能wire与7日口径正确。测试是组件点击/异步回包/权限案例，不等于真机。没有Controller/Security/Auth/Entity/Repository/迁移改动。未验证生产性能（月窗重复实体装载的非阻断风险）、真机、新接口上线。发布顺序仍后端先RN后，回滚RN先。仅保存本地14文件里程碑（代码12+根台账/归档），不push/PR/main/部署。

本地里程碑9e3aab3182后整合fresh main71b4e430cb：唯一ACTIVE冲突保留本批review条目与最新main organizer条目；其余源码自动合入，上游CI heap/PG port与组织角色修复均保留，不归本批新写/部署。生产摘要/self客户端本scope源码未因整合改变；未来R4质检与同Service文件必须顺序接管，不由另一WT并行写。
