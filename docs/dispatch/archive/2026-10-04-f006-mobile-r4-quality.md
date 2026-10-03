# F006-MOBILE-R4-QUALITY-20261004

状态 review；协调者 root/Codex；执行 cretas_executor。Base `6afa25495c1d2ec9bea8a3e3b6f83a72b75e1692`，worktree `C:/Users/Steve/cretas-f006-mobile-r3-20261004`。

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像
老板及质检管理人员，手机查看周期质量，需快速区分尚待判断和已经失败，避免把样本误当全部。

### 用户旅程
| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | 明确周期和完整统计 | 切换今日/最近七天/本月 | 统计口径与日期同步，无旧周期结果混入 |
| 2 | 通过、失败、条件通过、待检、未知 | 查看概览或近期样本 | 每类有清晰含义，样本有数量说明 |
| 3 | 权限或网络问题与重试按钮 | 重试可读部分 | 保留有效区域，失败不伪装为零或暂无数据 |

### 摩擦点清单
| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | 最近20条被显示成全周期统计 | HIGH | 上下文明确 |
| F2 | 待检遗漏、条件通过被混叫待定 | HIGH | 信息含义明确 |
| F3 | 失败或无权限显示可信空数据 | HIGH | Rule 5 / 错误恢复 |
| F4 | 换账号仍收到上个账号结果 | HIGH | 边界防呆 |

### 每个摩擦点的设计回应
- F1 → 完整周期统计来自摘要，近期列表标明样本且独立失败。
- F2 → 使用真实状态分类，未知单列；无有效判定时合格率显示未有数据。
- F3 → 明确无法读取与重试，沿用现有界面控件并保证触控目标44pt和间隔8px。
- F4 → 工厂、账号、权限和请求代数绑定，旧响应不写入新界面。

实测：手机质量概览把最近20条当全周期；全17条含PASS7、FAIL4、CONDITIONAL1、PENDING5。既有周期接口全范围3条PENDING而breakdown全零。不能把legacy failedBatches定义偷改为严格FAIL。

唯一允许八个代码文件：
- backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProcessingServiceImpl.java，仅getQualityStatistics和新quality摘要helper，保留R3生产摘要及写路径。
- 新backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProcessingServiceImplQualityStatisticsContractTest.java。
- frontend/CretasFoodTrace/src/screens/reports/QualityReportScreen.tsx。
- frontend/CretasFoodTrace/src/services/api/qualityInspectionApiClient.ts。
- 新frontend/CretasFoodTrace/__tests__/unit/api/qualityInspectionApiClient.test.ts。
- 新frontend/CretasFoodTrace/__tests__/integration/screens/QualityReportScreenStatistics.test.tsx。
- frontend/CretasFoodTrace/src/i18n/locales/zh-CN/reports.json，quality命名空间。
- frontend/CretasFoodTrace/src/i18n/locales/en-US/reports.json，quality命名空间。

新增兼容qualitySummary，完整工厂/周期计数，PASS/FAIL/CONDITIONAL/PENDING/未知和空状态如实分开，0与缺失如实分开，检测平均合格率与判定通过率分开。全周期统计来自statistics；20条仅样本。摘要/样本/异常各自报错，账号及权限变化拒绝旧结果。processing父模块production_plan实际权限复用R3 canvasApiClient.getUserModuleAccess，不授予或扩大权限。异常tab先按实际wire和现有finance/price权限核对，不凭猜测伪造字段；发现需要scope外修改回报root。

验收：Java新QualityStatisticsContract、既有QualityDashboardTodayFields及R3ProductionSummaryContract目标测试；RN新API及真实组件行为测试，完整/空/PENDING/未知/非周期样本/独立失败/账号切换/有效GRANT-DENY覆盖；TSC；JSON解析、diff和scope检查。多类JavatestIncludes使用**/*.java，顺序使用独立target。日志落私有Documents证据目录。子代理不改台账，不commit/push/PR，不改其他scope/Entity/Repository/Security/迁移/helper/依赖，不碰设备、网络服务、数据库或业务写，不生成孙代理。现有node_modules链接只读保留，禁止install/delete/junction操作。R3已经保存和验证，不把本批结果当上线或手机验收。

R4最终完成：完整summary与legacy两者过滤null/foreign/null日期/window外；未知/空判定与条件通过准确分开，固定Oct4时钟。retained old actor/range/tab回调在新请求期间均0旧请求且不污染token/generation/spinner，context=owner|range|tab同步layout；真实已发旧sample迟到与新sample正向对照，异常price-only/DENY/GRANT分立。独立源码复核无剩余P0/P1；Java最新9（质量4/生产4/dashboard1）与RN13/TSC通过后，root最后六Java24项、六RN58项、TSC exit0通过（日志r3-r4-combined-*-final）。
源码scope归档，PR最终适用CI/可信制品/合并/后端发布/新手机包验收仍各自待执行，合入时释放本批scope。没有生产业务写/DB/权限映射/Entity/Repository/迁移改变。R3/R4新summary须后端先RN后，回滚RN先；不能用目标测试冒充新接口线上或真机已通过。后续验收由F006-MOBILE-CAMPAIGN-20261003协调。
