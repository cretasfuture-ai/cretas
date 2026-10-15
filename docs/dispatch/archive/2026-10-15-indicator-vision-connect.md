# CONNECT-DATA-VISION-20261015

用户要求继续接通全部剩余项，先 Python 指标桥接并验收，再视觉 SOP 接入对话建厂及既有 runner。复用真实数据口径；各厂独立数据、训练、验收；六膳门模型不通用化。

Base SHA: a39038c68e36ff23d32eef5398bb87afe33fd746。

## AN2 scope lock

任务 ID CONNECT-DATA-VISION-20261015-AN2；Owner /root/implement_analysis_provenance。

允许：PythonSmartBIClient.java 仅指标方法及必要 private helper；IndicatorQueryServiceImpl.java 仅桥接能力/缓存按端点验证；对应 PythonIndicatorSourceContractTest 与新增桥接目标测试；新 backend/python/smartbi/api/indicator_bridge.py、新 backend/python/tests/test_indicator_bridge.py。

禁止：Entity/Repository/SQL/Flyway、其他客户端能力、main.py、auth_middleware.py、ACTIVE、视觉文件、平台建厂 scope、真实生产写入。Maven target 仅其独立 worktree 串行使用。

复用 smartbi.gold.restaurant.dish_margin.compute_dish_margins 的 date_range 与 avgRate 账上口径，不复制 SQL/业务公式，不纳入 avgRateWithEstimated。只登记实际支持的固定端点/别名，禁止任意 URL；未知来源不准复用旧缓存。真实零/无样本/错误分开，校验工厂、时间窗和有限数值；已有内部认证/安全 HttpClient 复用。HTTP 主服务挂载由 root 做。

验收：Python 新桥接目标测试、真实 ASGI/JWT 或 internal middleware 的跨厂/无身份对照；Java MockWebServer 实际请求含 factory/time + secret、zero/null、HTTP/协议/时间窗/工厂错配与非法端点拒绝；既有 indicator 目标单测及 transport security 不回归。先失败再修复的证据落 Documents/cretas-connect-20261015/indicator，不将 Mock HTTP 称为真实业务库验收。

## Root scope

root 唯一修改 main.py 路由挂载、docs/dispatch 与 docs/vision-training-sop.md，及后续新增 vision_training runner 接线/工厂限定配置/API；复用既有 tools/vision-lab 函数。本轮不动原图/人工 JSON/保护集/当前模型/计划任务，不自动训练真实客户数据或发布模型。

平台入口并行任务 PLATFORM-ASSEMBLY-20261015 位于 C:/Users/Steve/cretas-platform-assembly-20261015，当前独占建厂专属 Java/迁移/配置及 web-admin 平台入口；不接管其 worktree、不覆盖其未提交内容，先调查契约，集成按最终可复用入口收口。

用户澄清：视觉 SOP 接入正在建设的平台建厂入口，复用该任务成果；不另接旧独立版本。

Root 的 AN2 验收 scope 追加：新增 IndicatorPythonBridgePostgresAcceptanceTest.java、backend/python/tests/fixtures/indicator_bridge_app.py、scripts/windows/test-indicator-bridge.ps1。真实 Controller→QueryService→PythonSmartBIClient→真实 Python router/middleware/Gold function→私有 PostgreSQL，并回读 indicator_versions。Java 生产鉴权明确排除，Python 内部鉴权真实执行；测试只允许独占 loopback 55458 / cretas_workflow_verify_indicator_bridge_20261015，拒绝占用已有 10059/18085，不借用平台 PG55447。

已取得旧源红：1 test / 1 assertion failure / 0 errors / 0 skipped，期望 AVAILABLE 而实际 FAILED；日志 Documents/cretas-connect-20261015/indicator/java-python-pg-red.log。fixture 清理仅本专属 DB 的固定七张 Gold fixture 表，JPA 只扫描既有 indicator 包；不执行生产迁移或生产业务写。

Root 的指标展示 scope 追加：既有 IndicatorDetailDrawer.vue 及目标测试，显示 Python 菜品毛利的当前成本快照口径；只在相关 code/source 显示，不改变数值。新 Python wrapper 对部分成本覆盖不出全店 KPI（NO_DATA），不复制旧函数的计算逻辑。

VS2 实施设计约束：Linux API 只调度、持久化和验收回执；上海 Windows 本地 worker 仅调用命名函数，不调用 cycle/deploy，不接受客户端命令/URL/本地路径。任务、配方、数据、模型和独立验收必须绑定厂/项目/版本/哈希。租约超时不自动重跑训练；训练、评测、独立验收与启用分别留证。平台规划必须显式 PLATFORM_ADMIN namespace 与目标厂，不能伪装 FACTORY_USER。六膳门专用硬编码不可作为 generic/OCR 默认 runner。
