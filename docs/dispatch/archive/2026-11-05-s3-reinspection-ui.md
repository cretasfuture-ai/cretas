# S3 复检合格后的人工复核入口

- Owner：01a1a954/root；任务 TASKS-S3-REINSPECTION-UI-20261105。
- Base：538f71ad9a72a1bcf0025553efbe0291bd76aa97（1480 最终 Java 候选）。
- 当前缺口：FAILED 批次新增 PASS 不自动放行，但质检记录只对 FAIL/CONDITIONAL 提供复核按钮，处置页又过滤 PASS，后续审批入口不可达。
- scope：质量两页面、共享复检资格 helper 和对应测试；不改 Java、迁移、模块/角色权限、库存或审批门禁。
- 仅在已核验同批次 FAILED 上下文允许展示 PASS 复检入口；全厂普通待处置列表仍服务端过滤 FAIL/CONDITIONAL。最终是否最新、能否处置、是否须另一人审批均由现有服务端守卫判断。
- 目标验证：4 套 Vitest / 24 tests 全部通过，包含真实组件 PASS 自动打开/提交既有处置与普通合格批阴性；6GB vue-tsc -b exit 0。未更改 Java/RN 树，沿用 1480 对应验证。Web 官方构建及合并后 F006 申请/主管审批业务读回待执行，不将单测算业务验收。

2026-11-05 状态 merged：该窄修复已整合#1480，feature92ef/merge c24e11bb0a2c8e2714981d51b86849bd6fa2a8de；最终整合Web74及typecheck、官方构建通过，Java最终full19664零红/真实PG通过。代码scope随本记录归档释放；正式部署、Android OTA及F006申请/另一人审批/质检放行仍由父任务推进，未测项不计通过。

