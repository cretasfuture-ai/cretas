# ERP-S1-SUPPLIER-RELEASE-PREFLIGHT-20261021

状态：claimed。Owner：01a15272-374d-72c0-bb20-cdfa0bd823f1。Base：1fa62932cbc9fc9ab8789f84a1416a4d1be7807c。PR1066已合入，未部署；release-preflight因Demo测试EOF空白行拒绝，生产零操作。

精确产品scope：`backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/DemoConversationAdmissionContractTest.java`。仅移除最后一个空白行，生产源码、测试语句/断言、Entity/Repository/权限/租户/审批/恢复/审计不变。协调者维护本任务ACTIVE与本归档；不写其他Owner文件。

验收：`mvn -B -Dtest=DemoConversationAdmissionContractTest -Dspring.profiles.active=test test`（独立target/1GiB父进程+单3GiB子进程/2CPU/实时资源闸）；`git diff --check 1fa62932cbc9fc9ab8789f84a1416a4d1be7807c`；`python scripts/ci/check_dispatch_active.py`；合入后clean exact-main `bash scripts/deploy/release-preflight.sh`。

重用界限：原6aa4 Full16445PASS/139完全一致原SKIP/0FAIL/ERROR、PG124PASS及真实PG13/17存储证据保留；仅EOF格式修复不更改用例/断言或生产代码，不声明旧Full跑在新SHA。最终新backend-tree须经官方制品门禁，不将旧JAR改名/改来源；必要重建一次后官方发布、原QA独占UI/SQL/CSV。

冻结：只删除测试EOF最后1个LF，所有其他字节相同，diff-check与dispatch检查通过。独立目标启动被资源闸拒绝：其他Owner nativeJava214936，未运行Maven。新候选CI及官方构建将提供该类真实执行证据；不冒称该目标已通过。原6aa4 Full/PG完整证据按原SHA保留，不触发无行为变化的重复Full。源码scope冻结归档，唯一Owner保护不变，CI/合入/部署/原QA均待实际完成。

发布集成Base登记（2026-10-21 21:28+08）：源代码Base仍1fa62932cbc9fc9ab8789f84a1416a4d1be7807c，候选378129122fd91a2c2c3f52eda81894e8aeb994bb/PR1076。main新增5a0c01ff671857ff56fdba03ed27d46808a2fa76（他人PR1074，仅Python/Web），backend tree仍9b35c5ac7a896ab20938933532349cd062b6524c，src/main仍ffef754732808b5e40fcf6ce5dd6121d8855fba9，与1fa一致。正式deploy使用登记的releaseIntegrationBase=5a0c01ff671857ff56fdba03ed27d46808a2fa76（须已为合入后exact-main祖先），使统一入口自动仅选Java，避免捎带他人Web/Python发布。构建Base保持1fa、同61类selector不变；必须核对合入后backend tree=7112eb0a467a51393c966227e984bde6e0430e5d及JAR/manifest，不重标旧制品。未授予部署他人Python/Web，不回滚其授权变更。此登记由唯一协调Owner写入Ops台账，候选源码/文档冻结不改。
