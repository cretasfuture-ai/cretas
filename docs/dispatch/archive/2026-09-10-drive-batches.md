# DRIVE-20260910-INTEGRATION 合并收尾

Owner: Codex task 01a08b76-18e8-7db3-8efb-424e3c4aadf0。
Base SHA: e03fc7cf0c7c100d35cb8e907067e4b7e7e118af。
Scope: B01–B05候选实际文件及目标测试；包含发布前Canvas结果协议兼容门禁、对应API/Controller测试。未修改Entity/Repository/迁移、其他已有Owner的业务范围。

本归档和ACTIVE移除随本PR合入main生效，届时本批状态为merged并释放候选scope。在PR尚未合并时，协调者仍持有本地写锁；归档不代表预先合并或部署。用户已于本任务明确要求“合并部署，然后继续”。

本批已完成五个独立task候选整合与交叉审查，并纠正验收发现的负优化。75行分类和状态已回填，详见 ../drive-20260910/INTEGRATION.md 与 CLASSIFICATION.md。旧页签的Canvas调用缺少协议2标记时在写前拒绝，保留刷新提示；新客户端按逐项回执处理，部署按backend-first串行。

合并后从clean exact origin/main调用统一入口，Java必须正式构建并记录可信manifest，Web仅在tree匹配时复用。App JS按实际runtime兼容性使用现有OTA入口。服务级部署验收与真实业务/真机验收分别记录；发布事实以统一JSON回执及OTA回读为准。

未完成的整行业务要求继续保留在Drive及各批次回执，不因本次merge/deploy自动标为已解决。后续任务必须重新登记唯一scope。

## 2026-09-11 CI 隔离补修

`DRIVE-20260910-B08-CI-DB-ISOLATION` 由独立task 01a08b80-648a-73d1-88ab-f4030b763787完成，Base 4639d596ee619ae0e1567002c6d2b966e6d6130a，候选e2e0ad2873b744e6293c27189d257061a3170143。只给工作流create-drop测试指定独立H2数据库，保留生产代码、事务和原断言；修复前同JVM三类顺序复现10条缺表错误，修复后缓存容量2和1各13/13通过、零跳过。证据见B08-receipt.md与B08-root-cause.md。

该测试改动进入本PR最终候选，B08 scope也随本PR合并归档释放。后端Git tree发生变化，须重新以最终源码执行目标测试与JAR的同一正式构建生命周期；Web tree未变，应复用原可信制品。原4639全量13,298项中的10项错误和RN取消记录保留，不作为通过；最终候选仍须完成实际CI门禁。B06/B07属于下一批，未包含在本PR。
