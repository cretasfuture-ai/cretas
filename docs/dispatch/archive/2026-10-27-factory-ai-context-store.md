# FADCTX27 — 工厂问答跨 worker 短期上下文

状态：review；开发 Base SHA：`0c893cc2d790de791da6c7c5c310a2761c024f4e`；协调者 Root；执行 Owner shared_context_store。源码冻结，开发 scope 已释放；后续仅由 Root 合并、官方发布与只读验收。

授权：用户要求完成 AI 问答、汇总和多表分析全部修复，验证后直接合并部署。沿用本任务此前已发布的短期图表上下文存储与迁移权限；仅新增独立短期会话表，不回填、不修改业务表或其他租户业务数据。

生产已确认双 worker；进程内对话字典使产品澄清在返回后约20秒即410。不得将 worker 减为1或做静默内存降级。复用 detail_store 的 PostgreSQL pool、actor binding、RLS、TTL与双进程验证模式。

允许文件：`backend/python/smartbi/services/factory_charts/conversation.py`、新 `conversation_store.py`、`test_conversation.py`、新 `test_conversation_store.py` 与 `test_conversation_store_postgres.py`；`backend/python/smartbi/api/financial_dashboard.py`；唯一预留迁移 `backend/python/smartbi/database/migrations/V20261101_20__factory_analysis_conversations.sql`。若需生命周期/工厂缓存清理登记，先回传精确扩展路径再改。禁止其他源码、依赖、Entity/Repository、生产配置、凭据及业务写入；Java 仅允许下方明确扩展。

完成条件：身份绑定、TTL300秒、容量上限与跨进程串行更新真实有效；不在等待模型时占用通用业务池连接，存储不可用明确503；无本地降级。验证同厂不同用户/角色、外厂拒绝、过期、并发、worker重建及两进程实际读写。PostgreSQL测试只用隔离的本地测试库/命名空间，不清理别人数据，不停共享容器。

验收命令：`python -m pytest backend/python/smartbi/services/factory_charts/test_conversation_store.py backend/python/smartbi/services/factory_charts/test_conversation.py -q`；真实跨进程：`python -m pytest backend/python/smartbi/services/factory_charts/test_conversation_store_postgres.py -q`（沿用只允许本机的测试连接约束）。最终冻结后 Root 运行工厂图表相关集合与适用 CI。

执行者只改本 scope、不改台账、不提交、不派生代理。Root 负责迁移/权限独立终审、统一验证、PR/合并/官方 Python 发布、F006 网页与原生复验。原始410与时间证据保留。

`FADCTX27-PG` 测试 Owner context_pg_tests：仅运行已冻结的目标测试并写 ignored 日志；可在已运行的本机 PostgreSQL 创建唯一 `fadctx27_validation_*` 临时数据库供本批跨进程验证，严禁连接生产、清共享表、改/删已有全局角色、停共享容器。退出后仅回收实际创建且核验无其他连接的该临时库，否则保留并回报。验收命令为上面的真实 PostgreSQL 测试，必须执行而非 skip。context_risk_review 只读独立终审。
本机55439无监听；允许改用已运行的 Windows PostgreSQL17 localhost5432，仅限经监听PID/服务路径证实非隧道、数据库名前缀 `fadctx27_validation_` 的独立空库。新测试增加该强约束，不放开任意5432目标；不改旧测试的端口约束或启动/停止服务。

`FADCTX27-JAVA` Owner context_java_409：新上下文 lease/CAS 合理产生409，旧 Java 仅把403/410/422视作业务拒绝，会把409计入共享熔断器。仅允许 `client/PythonSmartBIClient.java`、`controller/FactoryChartAnalysisController.java`、`ai/tool/impl/smartbi/FactoryChartAnalysisFailureRelay.java` 和对应 `FactoryChartAnalysisFailureRelayContractTest.java`、`FactoryChartAnalysisControllerContractTest.java`（均在 cretas-api/src 下）。补充409无敏感内容的明确重试提示，保持原403/410/422/5xx语义。验收：`mvn -Dtest=FactoryChartAnalysisFailureRelayContractTest,FactoryChartAnalysisControllerContractTest test`；最终统一制品执行原七类工厂分析选择器。该扩展不改认证、Entity/Repository或其他Python方法。

TTL 300秒是读取有效期；物理过期清理沿用现有 transient store 的按厂写入清理，整厂数据删除自动发现并清除本表。不得声称300秒后物理介质和备份已擦除；无需新增全局后台清理权限。

本地 PG 最小权限测试扩展：已核实 Windows PostgreSQL17.7、127.0.0.1:5432/smartbi_db，当前 smartbi_user 为非 superuser、无 BYPASSRLS，允许建自有 schema，但不可建数据库。允许显式 opt-in schema-only 测试模式：仅此本机目标、固定当前角色与 PG17，schema 名严格为 fadctx27_validation_ 加十六进制随机后缀，初始为空且归当前角色。所有父/子进程连接固定该 search_path，在自有表上 FORCE RLS；不创建或修改全局角色，不触碰 public 或既有表。仅回收本批核验的自有 schema。预检已创建空 schema fadctx27_validation_615646ad5873，证据见 ignored test-output/FADCTX27-PG-readiness-5432-20261027.log。此扩展不放开远端/任意本机数据库，也不启停服务。

最终冻结证据：Python 工厂图表非 PG 集合 263 passed、23 warnings、exit0（test-output/fadctx27-python-frozen-final-v2.log）；Java 两个409契约类7 tests、0 failures/errors。Root 真实 PG 使用独立 schema fadctx27_validation_091ca83903ce，SCHEMA_WORKER=PASS、2 passed、1 deselected、0 skip、exit0（test-output/fadctx27-pg-root-1793099961.log），包括两进程、actor/RLS、busy409、过期租约接管与旧token CAS拒绝、TTL、并发容量、JSONB边界与跨厂删除保护；核实仅自有测试表后回收该 schema。旧管理员 fixture 为 deselected，不计入证据。先前工具超时/非空schema失败保留。

源码独立风险复核已清除旧逻辑阻塞；180秒租约配合 CAS 防旧请求覆盖，TTL300秒为访问有效期。无 Java Entity/Repository 改动，JPA启动门禁不适用。最终统一七类制品构建、CI、合并和发布回执待后续追加，不把本地测试表述为已上线。
