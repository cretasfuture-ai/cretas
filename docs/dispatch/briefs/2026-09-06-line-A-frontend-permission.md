# Brief 卡 A：前端 / 权限线（持续 session）

**发卡**: organizer `liushanmen-organizer-handoff-fa5fb5`（Fable）· 2026-09-06 · 基 `origin/main` ≥ `ffa87422ee`
**模型**: 本线是执行车道 ⇒ **Opus 5 + effort high**（organizer 自己跑 Fable 负责统筹；⛔ 别用 Fable 做执行，2026-09-07 Steve 点名过一次）。开 chat 后在模型选择里切。

**角色**: 这条线的 sister chat。你有自己的 worktree 与记忆，多轮连续做；**每个任务做到 PR 停**，合并 / 部署 / OTA 只由 organizer 做。
**回执格式**（每个 PR 一份，贴到 PR body 末尾 + 回给 owner 转 organizer）: 任务 ID / 基 SHA / 改动文件 / 验证命令与读数 / 变异读数 / PR / 阻塞 / 登记未做。

## 铁律（不重复解释，违反即返工）

- worktree off `origin/main`：`git worktree add -b codex/claude-<task> ../cretas-<task> origin/main`；⛔ 不在主目录干活；⛔ 不 `mklink /J` 共享 `node_modules`（各自 `npm install --prefer-offline --legacy-peer-deps`）。
- commit 只带列出的路径：`git commit -m "..." -- <files>`；squash 合并后**重开分支**（硬约束 7）。
- 🔒 权限 / RLS / 多租户 / 架构 / 迁移 = 只到 PR。⛔ 不合并、不部署、不推 OTA。
- ⛔ 不写 prod。只读 prod SQL：写到文件 → `scp` 到 47 → `ssh root@47.100.235.168 "sudo -u postgres psql -d cretas_prod_db -At -f /tmp/x.sql"`。
- 测试环境：Mac mini `http://100.85.234.108:10010`，F006 账号（`f006_admin` / `f006_sales_mgr` / `f006_finance_mgr` …）密码 `123456`，profile 只能 `pg-prod`。后端已刷到 main `ae86d1606f`（2026-09-06 15:35，Flyway 至 `.34`）。要刷新找 organizer（流程在 memory `project_macmini_test_env_is_live_with_f006`）。
- 每个 PR：至少一条断言跑在产品真实入口；变异先打印可观测量再看红（形态 C″）；「没发生」必须配「一定会发生」的读数（硬约束 9）；诊断输出先落文件再 grep（硬约束 1）。
- CI：PR 上 Java 只跑 `*ContractTest`/`*PermissionTest`（`fast-contracts`），全量要标签 `java-full-ready`（organizer 打）；RN 有 `rn-gate`（jest + tsc）；web-admin 有 `web-admin-gate`（`vue-tsc -b` + vitest）。看 `gh pr checks <n>` 行数 ≥ 预期，别只看绿。
- `.claude/rules/` 常驻规则你自动可见；本卡不复制。

## 已验事实（[RUN] = 实测；引用前自己再 cite 一次行号）

- 权限链：L4 用户覆盖 → L2 `factory_module_configs` → **L1 `platform_role_permissions`（权威）** → 硬编码 `PERMISSION_MATRIX`（Java `PermissionServiceImpl`，与 L1 漂移）。RN 另有一份内置矩阵 `frontend/CretasFoodTrace/src/utils/permissionHelper.ts:248`，也漂移：`permission_admin` sales 在 RN 是 rw、prod L1 是 `-`（[RUN] 2026-09-06）。三道后端闸：`@RequirePermission` / `@RequireModule` / `@RequireRole`（~331 处）。代码里**7 份互不一致的模块清单**（recon 已列，在 PR #646 或权限矩阵 PR body 里找表）。
- 只有 `PermissionInterceptor` 在 403 body 写 `meta.requiredPermissions`（结构化 `{module,action}` 对象，不是字面串）；`RequireRoleInterceptor` 的 code 也是 FORBIDDEN，所以断言 403 出处必须看 `meta`。
- 登录响应 `permissions` = `role.getPermissionPrefix()+":*"`（`FactoryUserRole.java:330-375`），如 `sales_manager` → `["sales:*"]`；`trace` / `system` 前缀任何角色都没有。RN `hasPermission`（`types/auth.ts:475-489`）只认精确 / `<module>:*` / `*:*`。
- RN 导航按角色字符串分派（`AppNavigator.tsx:108-193`，16 个分支 / 24 个角色串）；`sales_manager` → `SalesManagerNavigator`（13 屏），`ShipmentManagementScreen` 对它不可达且是 legacy 只读。
- OTA：自托管 `ota.cretaceousfuture.com`，`checkAutomatically: NEVER`，回前台触发检查 + Alert 门控 reload；送达判据是真机 `dev.expo.updates` 日志 `CheckCompleteAvailable → … → CheckCompleteUnavailable`；Hermes 字符串表去重，标记要挑**基线为 0 的新标识符**（[RUN] 今天两包）。
- 小米 M2102K1AC（adb `f79c50d6`）连在 owner 机器上，登录 `lsm004`（LIUSHANMEN sales_manager）。adb 在 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`；`uiautomator dump /sdcard/...` 在 Git Bash 要 `MSYS_NO_PATHCONV=1`。⛔ 不碰密码框；⛔ 不在真实订单上做业务写入（`SO-20260902-0001` 是六膳门真单）。

## 在飞（别撞）

| 任务 | 状态 | 谁 |
|---|---|---|
| `ARCH-PERMISSION-ADMIN-L1-EDITOR` step-1 只读矩阵页（后端端点已 commit `5715510400`，web-admin 页在做） | executor 在跑 | organizer 的 subagent，worktree `agent-ada25eb60b7a73790` |
| `BUG-HOME-MODULES-EMPTY-FOR-SALES`（共享 HomeScreen 7/9 角色模块区全空） | executor 在跑 | subagent |
| `BUG-SALES-ORDER-LIST-STATUS-TAB-DOES-NOT-REFILTER`（列表把 status 发给不认它的端点） | executor 在跑 | subagent |

这三条落地后，它们的 PR / 回执会出现在 `docs/dispatch/ACTIVE.md` 与 archive；你从那里接。

## 你这条线的任务（按序，每个一 PR）

1. **`BUG-RN-PERMISSION-MATRIX-DRIFTS-FROM-L1-20260906`**：让 RN 不再自带一份会漂的矩阵。方案二选一并说明：(a) 登录响应带上 L1 解析后的 `module → r/rw/-` 映射，RN `hasModulePermission` 改读它，内置矩阵只做离线兜底并加「与登录响应一致」的契约测试；(b) 保留内置矩阵但加一道 CI 闸：从 `V20261030_*`/seed SQL 或 prod 只读导出的 L1 快照与 RN 矩阵逐格比对，漂移即红。先量：`permissionHelper.ts` 矩阵 vs prod L1 逐角色逐模块的差异格数（把表贴进 PR）。阴性对照：故意改一格 ⇒ 红。
2. **权限矩阵 step-2（可写）**：等 step-1 合并、owner 看过只读页之后再做——owner 要「按侧边栏模块勾角色」。范围：只写 L1（`platform_role_permissions`），每次改动写审计行，写后使 `PermissionServiceImpl` 缓存失效（先找有没有缓存）；⛔ 不碰硬编码矩阵、不碰 L2/L4。UI 用 step-1 的表加编辑态。契约：一次写入 ⇒ 后端下一次 `@RequirePermission` 判定立即变化（真跑，不 mock）；阴性：无 `permission_admin`/super_admin 码的角色 403。
3. **7 份模块清单收敛**：先做一道「N 份清单两两一致」的闸（读源码，不改行为），把差异表贴出来给 owner 定哪一份是真的；⛔ 别自己挑一份改其它六份。
4. **Alert.alert 在 react-native-web 是 no-op**：全 App 既有形态。先数有多少处 `Alert.alert` 在 Expo Web 也会走到，出一张表，提一个统一替代（Snackbar/Portal Dialog）方案；改动等 owner 点头。
5. 每次 RN 合并后由 organizer 推 OTA；你负责给出**基线为 0 的 ASCII 标记**与真机验收脚本（`tests/vshot/*.spec.ts` 形态，见 `short-close-acceptance.spec.ts`）。

## 相关文件 / 入口

- 台账：`docs/dispatch/ACTIVE.md`（只有 organizer 写）；今日详情：`docs/dispatch/archive/2026-09-06-active-organizer-log.md`
- 权限：`backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/PermissionServiceImpl.java`、`.../interceptor/PermissionInterceptor.java`、`.../security/DataScopeResolver.java`；RN `src/utils/permissionHelper.ts`、`src/types/auth.ts`；web-admin `src/stores/permission*`
- RN 导航：`frontend/CretasFoodTrace/src/navigation/*Navigator.tsx`；可达性闸 `src/__tests__/gates/roleReachability.ts`、`screenReachability.test.ts`
- OTA：`scripts/ota/push-bundle.sh`（只 organizer 跑）；`src/services/otaUpdateService.ts`

## 交接协议（2026-09-06 Steve 定，三条线同样）

- **每到一个停点——PR 开好、被阻塞、一条任务收工、需要裁定——立刻 `send_message` 给 organizer session（标题含「organizer」），不要等被问。** 消息带：任务 ID、PR 号 + head SHA、一句话结论、下一步打算。
- organizer 的合并 / 发布 / 退回意见也会 `send_message` 回来；PR 评论是备份不是主渠道。
- 收到退回意见：改在同一 PR，推后再发一条消息（带新 head SHA）。
- 一条任务收工后**不要空等**：接卡里与在审 PR 文件不重叠的下一条，并在消息里说明接了哪条。
