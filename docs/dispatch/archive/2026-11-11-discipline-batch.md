# DISCIPLINE-BATCH-20261111 — 工厂纪律 18 条收尾（已完成）

- Owner：organizer（接手 local_539738b8 → local_a8a574b9 派出的收尾会话）
- 合并：#1598 B5/B6/B8/B10/B13/B14（squash 7ec081f339，头 82dad6c5d6 全绿）；#1606 库存账 A4/A5/A9/C3/C6（squash 265057cb25，头 13ccc902cb 全绿）；台账 #1605。
- #1598 合并前发现迁移号 V20261111_95/_96 小于 prod 已应用的 20261112.22（out-of-order=false），改为 V20261112_23/_24。
- 发布：main c240f26aaf，release-cretas --phase deploy --base-sha a89e8c70f9，DEPLOY_EXIT=0，RELEASE_FINAL_STATUS=deployed×1，JAVA/WEB 均 deployed；green 10020；进程 21:00:56 晚于 jar 21:00:30，MD5 d756437b…一致，无 deleted fd；readiness/liveness/ai UP（根 health DOWN，未展示细节）；flyway 20261112.24；web 四方哈希一致。
- 前两次发布失败、prod 未动：① SessionStart 钩子 worktree-hygiene --auto 在构建中摘掉发布树 .git；② fetch origin/main SSL 抖动。发布树须 git worktree lock。
- Android OTA：rv 1.0.5 production ts=1794402526429，OTA_EXIT=0（真机冷启动未确认）。
- 待 Steve：统一验收（见会话交付清单）与 139↔47 VPC 对等连接。
- 后续未开工：#1598「没做」两项（报废审批网页入口仍岗位名单、允许本人审批开关未接 OA 自审）；#1606 审查遗留四项（中途结算/文员录入/工艺单原料归属、扫码出库无计划字段、AI 出库丢数据范围、C3 PG 测试只模拟调拨）；交接单原后续待办。
