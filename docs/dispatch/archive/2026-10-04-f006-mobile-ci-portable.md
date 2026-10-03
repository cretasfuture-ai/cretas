# F006-MOBILE-CI-PORTABLE-20261004

状态 in-progress；Owner root/Codex；执行 cretas_executor；Base `08bf57fc6910668f6c597eb8df801a1733cef796`。
独立 worktree `C:/Users/Steve/cretas-f006-mobile-ci-20261004`，branch `codex/f006-mobile-ci-portable-20261004`。

已证实仪器故障：R1 HEAD33ec8ca017、run37141156423，第1轮Mini3g test fork heap OOM，4183项后rc2；第2轮另Mini固定PG socket不存在，0项rc2。原Windows WSL runner已由外部Owner停止，不得恢复/改变；repo当前仅ARM64 Mini runners可用，org runner清单无权限读取，不得猜X64/native-pg标签。PG并发独立job通过，不代表full r10-it已接线。

唯一代码scope三文件：
- `.github/workflows/java-full-nightly.yml`
- `backend/java/cretas-api/src/test/resources/application-r10-it.properties`
- 新 `backend/java/cretas-api/src/test/java/com/cretas/aims/gate/JavaFullPortablePostgresContractTest.java`

使用既有 `scripts/ci/run-with-test-postgres.sh` 的随机loopback端口/独立库/自动清理；移除full job常驻socket数据库准备，不变更helper、runner/service/cache/labels、全量选择器/棘轮/基线、其他workflow或prod。r10 profile消费 helper URL/user/password，并保留没有helper环境时现有本机fallback。不得把generic SPRING_DATASOURCE_URL广播给所有测试profile。仅full test fork argLine追加Spring TestContext cache maxSize=2，保留现有Xmx3g、Mockito/共享内存参数，避免无根据增加单VM堆或强改fork复用；只有全量实际完成才可认定OOM修复。

wrapper的CRETAS_WORKFLOW_PG_VERIFY/SMARTBI_PG_DSN会影响其他opt-in tests，须记录原工作流的启用面并保留，不能无意开启或关闭与本修复无关的测试。r10-it本身不得skip，真实PG接线必须验证。子代理只能提出最小命令/配置选择，root负责最终workflow执行/范围判断。

验收：Maven `JavaFullPortablePostgresContractTest,JavaPrGateTieringContractTest,GateSelectorCoverageContractTest`，多类testIncludes **/*.java，CPU2/Maven3g/test1g；Spring真实property resolution正反向legacyfallback/随机loopbackURL/user/空密码；workflow实际调用wrapper/选择器与Mockito参数保持/缓存上限接线。既有PG wrapper测试和heavy runner shell guard按可用当前环境执行；一次隔离Linux PG实际child readback由root确认，不触常驻PG。最终候选CI全量必须真实完成、棘轮通过，不以源码镜像或目标测试代替。

禁止生产代码、Entity/Repository/迁移、共享pom/helper/scripts、baseline、角色/业务写、设备、网络写、commit/PR、台账写或孙代理。日志私有r1-ci-portable-*；既有tracked测试fallback不代表生产凭据，不读取/复制真实credentials。

根协调者接受最小执行scope扩展（仍只三个文件）：允许workflow把原默认full选择拆为两次互补执行，第一排除FactoryConfigServiceImplReorderIT且清除helper新增的五个PG变量，第二只该r10类并消费随机PG参数；总选择面必须等价原pom Test/Tests/IT，不漏/重复、不激活历史opt-in、不清理前段报告。新ContractTest须实际运行workflow抽取Bash证明环境隔离/后段接线和失败处理；Maven selector等价/报告累计需独立证据，只有全文contains不足。root将以WSL runner UID1000在私有临时PG实例实际验R10，禁止任何常驻服务、runner标签/进程或基线修改。之前12目标PASS、wrapper2skip、shell7PASS不代表随机PG或full执行成功。
