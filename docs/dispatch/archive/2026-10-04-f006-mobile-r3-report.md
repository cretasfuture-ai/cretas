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
