# OPS-CODEX-BATCH-20261002 / OPS-OTA-20261003 / ACCEPT-OPERATING-REPORTS-855-20261003

## OPS-CODEX-BATCH-20261002（done，补登）

- Codex 10-02/03 合并、未登记台账的八个 PR，全部在 #861 之前合入，随 2026-10-03 那次发布（base `7791a5826f`）上线：
  - #851 发布脚本：回滚只绑定已验证制品与当前在跑的槽。
  - #852 web-admin：生产墙刷新与全屏状态。
  - #857 web-admin：财务调整入口恢复，余额如实显示。
  - #853 Java：质检百分比不丢精度。
  - #850 Java：启动前显式 `flyway.validate()`，禁止自动 repair。
  - #855 Java + web-admin + RN：工厂 AI 经营报告（日/周/月）与报告历史。
  - #859 RN：建厂向导失败时不再报成功。
  - #860 Java：排班优化拒选不可用工人。
- 事故：#850 合入后第一个带新迁移的发布（V20261101_48）起不来，Flyway 9 的 `validate()` 把 pending 迁移判为漂移；蓝绿保住旧槽，线上未受影响。#861 改为在配置副本上追加 `*:pending` 再校验，已部署，运行 jar 内标记 `FLYWAY_VALIDATE_WITHOUT_REPAIR_V2_PENDING_OK`。

## OPS-OTA-20261003（done）

- 判断：线上 production android 包 createdAt `2026-09-29T17:26:13Z`；其后 RN 只有 #855（11 文件）与 #859（4 文件），零原生/包配置文件 ⇒ 纯 JS，OTA 足够。
- 推送：detached worktree @ main `7791a5826f`，`push-bundle.sh production android`，rc=0，`bundle live: rv=1.0.5 channel=production ts=1791018398052`，95 s。
- 送达核验：ota 域清单 createdAt `2026-10-03T09:07:59Z`，`launchAsset` 路径含同一 ts；从 dl 域 CDN 带证书校验下载新包（6,819,543 字节 gzip → 15,442,548 字节 hbc）。
- 标记：新包 `BossOperatingReportsTab`=1、`factoryAIWizard.unavailableTitle`=1、`WHOperatingReportsTab`=1；推送前的旧包三者均为 0（阴性对照）；`AIReportDetail`/`FactoryAIWizard` 新旧均为 1（阳性对照）。
- 缺口：本机无 adb 设备，没有真机行为读数（设备冷启两次后生效）。`npm install` 改写了临时 worktree 的 `yarn.lock`，未提交。

## ACCEPT-OPERATING-REPORTS-855-20261003（API 层已过，界面待 owner 登录）

- 租户：只用 F006 测试租户。写入：两份日报（超管生成的 14404；department_admin 生成的 1 份，department_admin 在价格白名单内，属合法生成），列表 9 → 11。
- 正向：`f006_admin` 生成日报 HTTP 200，`reportId=14404`；详情含「销售」「毛利」段。
- 读侧对照（同一份 14404）：超管与财务主管各 6867 字节、31 处「元」金额、16 处「毛利」；仓库主管（不在价格白名单）2152 字节、0 处金额、0 处毛利。价格敏感段落按角色整段不渲染。
- 生成侧对照：仓库主管生成被 `403 您的角色 [仓储主管] 在 [数据分析] 模块无 [读写] 权限` 拒绝，列表前后均 11。F006 token 打 F001 路径被工厂隔离拦截器 `403 无权访问该工厂数据` 拒绝（比 #855 自己的 actor 校验更早）。
- 发现：生成接口里「经营报告生成需要价格查看权限」这道闸，没有真实角色能触发——持有 `analytics:read_write` 的六个角色（factory_super_admin、permission_admin、platform_admin、dispatcher、department_admin、finance_manager）全部在 `PRICE_VIEW_ROLES` 里。这道闸目前只由单元测试守，API 层构造不出阴性对照。
- 剩余：web-admin「AI 报告」页截图验收（正向 + 仓库主管视角），需要 owner 在浏览器面板登录后进行。
