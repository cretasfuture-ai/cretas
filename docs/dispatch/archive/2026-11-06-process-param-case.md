# ARTIFACT-PARAM-CASE-20261106

Base 2de52fb66e7caaf892d9430afba23e8b81d5e57d；Owner food/root，backend唯一写者param_executor；worktree C:/Users/Steve/cretas-food-dag-canvas-20261105，branch codex/process-param-deviation-case-20261106。复用本组已完成PhaseA的clean副本，不新增worktree，不清理他人。

Scope：ShortageCase.SourceType与必要source-specific action隔离；新增entity/shortage/ProcessParamDeviationCaseDetail、repository/shortage/ProcessParamDeviationCaseDetailRepository、service/shortage/ProcessParamDeviationCase*、controller/ProcessParamDeviationCaseController及DTO、现有YieldReportServiceImpl与ClerkProcessEntryService两个真实报工事务接点、相应窄Contract/真实JPA/PG测试；必要现有ProcessParamDeviationService/DTO仅本feature接线。迁移唯一预留V20261106_1__process_param_deviation_case.sql，正式发布前重新核主线/线上/各组冻结唯一号，不启out-of-order。Supplier、Material、SOP、AI Gateway、CI/发布脚本、原审批权限矩阵、其他组范围禁止。

普通LOW/HIGH参数偏离创建可追踪异常，用户确认偏离不豁免记录；稳定来源key区分Yield report或process sheet row和param key，Clerk三报告fanout只产生一来源事实。相同事实幂等，事实更新刷新；已关闭相同事实不重开，变更事实留审计。OK不能自动替人关闭。负责人归属本厂/有效且正常权限；分配/开始核查/填写结论及关闭按乐观版本与当前事实重验；generic shortage actions不得绕过。源工艺字段必须按现有secret guard，别泄漏受保护参数。

两阶段：先兼容enum/schema，新增writer开关缺省false（生产也false），无新source行；A正式部署并记录兼容回滚floor后才用独立启用提交。兼容层和完整实现可同候选禁用，不能新enum行先于兼容binary入库。验收：行为单测、真实JPA Context、私有PG唯一/并发/回滚/来源fanout/关闭后重放/权限边界；root统一集成、风险、官方制品/CI/PR/生产与F006自身QA验收。执行者只改声明scope、不改ACTIVE、不自部署/提交/开PR、不操作生产，不生成子代理。

root追加独立Web唯一scope：web-admin/src/views/quality/param-deviation/index.vue、同目录独立ProcessParamCasePanel.vue及tests、web-admin/src/api/processParamDeviationCase.ts及tests；只在后端DTO冻结后接线，不改后端执行者文件/全局路由/布局。UX Flow先见docs/specs/process-param-deviation-case.md。

Web执行授权冻结：任务ARTIFACT-PARAM-CASE-WEB-20261106，Base2de52fb66e7caaf892d9430afba23e8b81d5e57d（当前HEADc3f050b34e含此前main），唯一执行者artifact_merge_safety；允许文件仅上述index.vue、ProcessParamCasePanel.vue及同目录专测、api/processParamDeviationCase.ts及专测。不得改backend/ACTIVE/依赖/锁文件/共享路由；DTO按当前Controller+record冻结，分页0基List、assign/start/resolve返回Void须fresh read，closed只读/403不泄密/409保留说明。验收目标Vitest组件行为+API契约与vue-tsc（已有node_modules时）；root最终组合构建/commit/PR。无生产/设备/子代理；完成后停止写并报实际文件/命令/数字。

风险修订scope冻结：param_executor继续唯一backend Owner，允许最小追加ShortageCaseRepository中本sourceType专用包含closed的case悲观锁查询及对应真实JPA/PG门禁。实际迁移首句5s lock_timeout，测试helper不得替源码设置；统一case→detail锁序，apply/resolve锁后fresh读取，真实PG barrier刷新对关闭双方无Throwable。Web独立Owner仅Web不重叠；不改现有generic shortage语义/其他source。修后必须补真实JPA startup及真实PG、目标Service，root终审。
