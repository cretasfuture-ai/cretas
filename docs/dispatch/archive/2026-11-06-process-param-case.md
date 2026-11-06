# ARTIFACT-PARAM-CASE-20261106

原始Base 2de52fb66e7caaf892d9430afba23e8b81d5e57d；Owner food/root，backend唯一写者param_executor；worktree C:/Users/Steve/cretas-food-dag-canvas-20261105，branch codex/process-param-deviation-case-20261106。复用本组已完成PhaseA的clean副本，不新增worktree，不清理他人。

Scope：ShortageCase.SourceType与必要source-specific action隔离；新增entity/shortage/ProcessParamDeviationCaseDetail、repository/shortage/ProcessParamDeviationCaseDetailRepository、service/shortage/ProcessParamDeviationCase*、controller/ProcessParamDeviationCaseController及DTO、现有YieldReportServiceImpl与ClerkProcessEntryService两个真实报工事务接点、相应窄Contract/真实JPA/PG测试；必要现有ProcessParamDeviationService/DTO仅本feature接线。迁移唯一预留V20261106_1__process_param_deviation_case.sql，正式发布前重新核主线/线上/各组冻结唯一号，不启out-of-order。Supplier、Material、SOP、AI Gateway、CI/发布脚本、原审批权限矩阵、其他组范围禁止。

普通LOW/HIGH参数偏离创建可追踪异常，用户确认偏离不豁免记录；稳定来源key区分Yield report或process sheet row和param key，Clerk三报告fanout只产生一来源事实。相同事实幂等，事实更新刷新；已关闭相同事实不重开，变更事实留审计。OK不能自动替人关闭。负责人归属本厂/有效且正常权限；分配/开始核查/填写结论及关闭按乐观版本与当前事实重验；generic shortage actions不得绕过。源工艺字段必须按现有secret guard，别泄漏受保护参数。

两阶段：先兼容enum/schema，新增writer开关缺省false（生产也false），无新source行；A正式部署并记录兼容回滚floor后才用独立启用提交。兼容层和完整实现可同候选禁用，不能新enum行先于兼容binary入库。验收：行为单测、真实JPA Context、私有PG唯一/并发/回滚/来源fanout/关闭后重放/权限边界；root统一集成、风险、官方制品/CI/PR/生产与F006自身QA验收。执行者只改声明scope、不改ACTIVE、不自部署/提交/开PR、不操作生产，不生成子代理。

root追加独立Web唯一scope：web-admin/src/views/quality/param-deviation/index.vue、同目录独立ProcessParamCasePanel.vue及tests、web-admin/src/api/processParamDeviationCase.ts及tests；只在后端DTO冻结后接线，不改后端执行者文件/全局路由/布局。UX Flow先见docs/specs/process-param-deviation-case.md。

Web执行授权冻结：任务ARTIFACT-PARAM-CASE-WEB-20261106，Base2de52fb66e7caaf892d9430afba23e8b81d5e57d（当前HEADc3f050b34e含此前main），唯一执行者artifact_merge_safety；允许文件仅上述index.vue、ProcessParamCasePanel.vue及同目录专测、api/processParamDeviationCase.ts及专测。不得改backend/ACTIVE/依赖/锁文件/共享路由；DTO按当前Controller+record冻结，分页0基List、assign/start/resolve返回Void须fresh read，closed只读/403不泄密/409保留说明。验收目标Vitest组件行为+API契约与vue-tsc（已有node_modules时）；root最终组合构建/commit/PR。无生产/设备/子代理；完成后停止写并报实际文件/命令/数字。

风险修订scope冻结：param_executor继续唯一backend Owner，允许最小追加ShortageCaseRepository中本sourceType专用包含closed的case悲观锁查询及对应真实JPA/PG门禁。实际迁移首句5s lock_timeout，测试helper不得替源码设置；统一case→detail锁序，apply/resolve锁后fresh读取，真实PG barrier刷新对关闭双方无Throwable。Web独立Owner仅Web不重叠；不改现有generic shortage语义/其他source。修后必须补真实JPA startup及真实PG、目标Service，root终审。

最终集成Base更新56a93c20e58f64281c0c584f02be5a0884597bbd（95已正式上线），源3b716+正常merge dab45。四项风险已闭：真实SQL首句5s锁超时且helper不替设；case→detail统一锁与刷新/关闭真实PG barrier；同厂active+PermissionService实际RW负责人；JPA固定隔离H2。App服务端权威工序名覆盖客户字段，Clerk旧来源保持。最终Service7/JPA1/PG1+4，Web未变API/page证据复用+新Panel9与type0，root staged diff-check实际补正仅自有新docs CRLF。
root最终追加Scope：ProcessParamDeviationCaseRolloutContractTest真实ConfigData分别prod/pg-prod默认禁写与显式覆盖，避免先前SOP实际profile遗漏复发；CI PG选择器保留最新main74类完整前缀，仅append本组3类。官方18类Java与一次Web build/stage、同headCI、正常PR/merge、兼容A发布/0新source验证后，再独立B启用与同F006 QA业务验收。当前source不能称已部署。

最终review Base为 bfe0f7481be2318e2f598b17a4818f8e91e7d541；source正常整合已发布APS f158及其docs bfe，原dab45的实际第二parent为f158（共享origin ref已由并行fetch推进），并非56a9，最终父链已核。只自有ACTIVE行已归档并释放source写锁，未删他组行。候选将以本组先行SOP编码修复PR1498作为stack base（36138083e0），单独PR只展示参数case；官方组合19类明确包含URI守卫。1498正常入main后再retarget main，必须证明Java/Web tree相等才复用制品，不部署缺main源码的旧jar。
PhaseA implementation状态review，真实迁移95已线上成功，V20261106_1仍唯一未发布，无out-of-order。默认及prod/pg-prod通过实际writer注入保持禁写，兼容A正式发布/0新source行后再独立启用；此归档不把未完成CI/部署/业务计为完成。

官方可信制品已完成：head7d2819 Java19类272总=270pass+既有ShortageCaseRepo两H2-only seed在PG条件skip，0fail/error；新增迁移1/生命周期4/JPA1/Service7/双profile7全无skip，Java package383秒、Web77秒，PG57048 finally stop0。初次统一stage被审计有效锁58024拒绝，未跑测试；随后官方manifest入口独立构建不占发布锁，未拼生产部署。
1499首轮scan-surface-in-sync实际FAIL只因新增Entity统计550→551；用canonical --regen-offline在既有476表hasdel快照上重放，只改生成出处stamp/统计注释，扫描62对/手工2条和实际SQL完全不变、drift gate PASS，不改阈值/生成器/数据库快照。最终补齐OK事实恢复中文状态且仍需人工核查，Panel目标10与type/build按新Web内容复验；Java源码/测试树不变复用272证据与JAR，不再Maven。正式head/stage/CI与生产A/B仍待，未称业务通过。

## 2026-11-06 CI soft-delete gate fix

Head2926 actual fast CI executed7612 tests: one failure in SoftDeleteAnnotationCoverageRatchetContractTest; PG lane succeeded. New detail Entity had @Where but no local @SQLDelete, so repository delete could physically remove a fact. No baseline/threshold was changed. Add version-bound SQLDelete using the real case_id primary key, plus real JPA delete+flush+clear and JDBC proof that the physical row remains with deleted_at while ORM hides it and the parent remains. Original failure log retained at pr1499-fast-first-failure.log.

Normal merge of published24c7 into this candidate changes no source tree and removes already-delivered SOP from PR three-dot diff. Source changed for the Entity fix, so Java trusted artifact must rebuild once; unchanged final Web tree6dcd03339c09 is reused. Final official selector is the prior19 plus SoftDeleteAnnotationCoverageRatchetContractTest and SqlDeleteBindsVersionColumnContractTest; current-head CI must pass again. PhaseA writer remains disabled, migration unchanged; no production writes from this fix.

Final reviewed composition Base3a377b0a05c7a37ed4a93feb90c3d6f5076deb40: normal feature merge retained all retirement changes. CI PG selector union preserves all76 main entries and appends three Food entries,79 unique total. Generated SQL was canonically regenerated offline: same62pairs/manual2/hasdel476, only derived entity census533 and stamp; drift gate PASS. Previous d9 official21-class artifact completed276total/274pass/0fail/0error/2existingH2-only skips, PG stopped0. The new main changes Java/Web trees, so a fresh final21-class trusted artifact and latest-head CI are required, not old JAR reuse. Full merge and formatting checks retained; no production mutation in this integration.
