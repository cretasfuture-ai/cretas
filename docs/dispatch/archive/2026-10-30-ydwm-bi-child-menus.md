# YDWM-BI-MENU-20261030

- Owner: root；Base: `4543c06466afa4bc7ad234658477d93cd1be823b`。
- 工作分支：`codex/ydwm-bi-entry-20261030`；本批候选源码已封存，scope 随最终候选提交释放。合入事实以本分支 PR 为准，生产事实以统一发布回执和目标 UI 回执为准，不能由本归档推断已部署。
- Scope：AppSidebar、factoryMenuVisibility helper/测试、factory config 类型、system/features 配置抽屉；已有 nginx SmartBI snippet 的精确单租户名单及对应测试。未改 Entity、Repository、迁移或角色权限计算。

## 行为与边界

- 普通工厂仍默认隐藏数据与分析组。只有显式配置 `analytics.enabled=true` 且 `config.showInSidebar=true` 才解除该组的工厂类型隐藏；原有角色、模块、子菜单角色检查继续执行。
- 每个模块可保存 `config.disabledMenuPaths`。配置选项来自现有菜单树，支持同路由不同 query/tab；保存成功后通知侧栏重读，切厂响应有 token 防串保护。
- 这是网页菜单入口显示控制，**不是接口授权**，不删除数据，不撤销已有接口权限。
- 本次只对用户点名的新工厂配置所列仓储、生产、销售子菜单；质量菜单保留包装标签拍检。其他工厂配置不动。
- nginx 仅把 `FOOD_0000_001` 加入原有三个 SmartBI Python cutover location；没有新增路径类别、相邻工厂或生产/质检 Java authoritative 路由。

## 验证与回执

- 目标回归入口：`node node_modules/vitest/vitest.mjs run src/components/layout/__tests__/factoryMenuVisibility.spec.ts src/components/layout/__tests__/AppSidebar.reactivity.spec.ts --maxWorkers=1`。
- 完整 Web 回归：`node node_modules/vitest/vitest.mjs run --maxWorkers=4`；最终源码冻结在 Web tree，docs/ops 后续提交不改变该 tree。必须取得成功退出码后才能合并发布。
- nginx 对照：`python -m unittest discover -s ops/nginx-vhosts-139/tests -p test_smartbi_factory_allowlist.py -v`，4 项已通过，包括目标厂命中、相邻厂不命中、旧厂保持、Java 路由不被切走。
- 发布构建与部署：统一 `release-cretas.sh`，登记 Base 如上；Web-only，不发布 Java/Python。合入后先 prewarm，再从 clean exact origin/main 发布；成功须同时满足 `DEPLOY_EXIT=0` 与恰好一条 `RELEASE_FINAL_STATUS`。
- 独立只读终审未发现阻断性代码问题；测试、构建、提交与生产回执仍各自是必需状态，终审不替代它们。
- 本地脱敏证据目录：`C:/Users/Steve/.codex/tmp/yidaliweimei-20261030/`。全量测试、manifest 构建/部署、nginx 安装、目标 UI 分别保存独立日志/JSON。账号凭据不进入 tracked 文件。
- 生产只读正反查确认：目标 analytics 配置 enabled=true、deleted_at NULL；全表有效 showInSidebar=true 只有目标厂一行。nginx 旧文件绑定 SHA-256 `6d20b69e5ba5689d9cfc20400cc68fc7d37e4a87e0d9cdf16f6bfe06b89107a2`，与 Base blob 一致；三个 vhost 都 include 同一 snippet。

## 运行与验收边界

- 网关修改先核对旧哈希、独立备份，只允许三处精确 tenant 追加；`nginx -t` 通过才 reload，失败恢复旧文件并重新检验/reload。
- Web 与网关在同一发布窗口处理；若本批目标验收失败，按原有 Web 回滚和 snippet 备份一并恢复，不能把半套状态算交付。
- 目标 UI 用新工厂干净登录会话验证：数据与分析组可见；用户列出的五个子菜单隐藏；各保留模块的代表菜单仍可达；未开模块保持隐藏。实际照片识别、真实业务单据闭环与上传后 AI 分析结果未纳入本批验收。
- 新工厂未进入 capability layer 的既有灰度名单，能力接口 503 的旧体验回退不等于 BI 权限未开；本批不扩大该全局灰度范围、不重启 Python。
