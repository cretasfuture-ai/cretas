---
name: e2e-native
description: Cretas React Native 原生 E2E 测试。使用 Maestro 执行原生 UI 测试，涵盖 testID 扫描注入、Maestro YAML 生成、多角色测试流执行、测试报告。用户说 /e2e-native、Maestro、testID 覆盖率、原生 E2E 时触发。
allowed-tools:
  - Bash
  - Read
  - Edit
  - Write
  - Glob
  - Grep
---

# React Native 原生 E2E 测试

## 子命令（`$ARGUMENTS`）

| 输入 | 动作 |
|------|------|
| _(空)_ | 列出子命令和用法 |
| `scan` / `scan --fix` | 扫描 `.tsx` 的 testID 覆盖率；`--fix` 额外给出建议的 testID 名，审核后用 Edit 注入 |
| `inject {screen}` / `inject --batch {module}` | 为指定屏幕 / 模块下所有屏幕注入 testID |
| `gen {role}` / `gen all` | 生成角色的 Maestro 测试流（8 角色各有独立 Navigator） |
| `run {role}` / `run all` | 执行测试流；可选 `--apk <path>`、`--device <id>` |
| `report` | 基于最近一次 run 生成报告 |

## 配置

| 项目 | 值 |
|------|------|
| APP_ID | `com.cretas.foodtrace` |
| 屏幕 / 组件 | `frontend/CretasFoodTrace/src/screens`、`src/components` |
| Maestro 流 / 截图 / 报告 | `tests/e2e-native/.maestro`、`tests/e2e-native/screenshots`、`tests/e2e-native/reports` |
| 超时 | 单流 60s，总 600s |
| 脚本 | `scripts/scan-testids.py <src>`（CSV）、`scripts/run-maestro.sh {role} [--apk] [--device]`、`scripts/gen-report.py <reports-dir>` |

## 测试账号

口令不写进仓库：真值放本地 `.env.test`（模板 `.env.test.example`）。

| 角色 | 用户名 | 口令变量 | factoryId | Navigator |
|------|--------|---------|-----------|-----------|
| 工厂超级管理员 | `f006_admin` | `$TEST_FACTORY_ADMIN_PASS` | F006 | FactoryAdminNavigator |
| 平台管理员 | `platform_admin` | `$TEST_PLATFORM_ADMIN_PASS` | - | MainNavigator |
| 车间主管 | `f006_workshop` | `$TEST_WORKSHOP_SUP_PASS` | F006 | WorkshopSupervisorNavigator |
| 仓储主管 | `f006_warehouse_mgr` | `$TEST_WAREHOUSE_MGR_PASS` | F006 | WarehouseManagerNavigator |
| HR 管理员 | `f006_hr_admin` | `$TEST_HR_ADMIN_PASS` | F006 | HRNavigator |
| 调度员 | `f006_dispatcher` | `$TEST_DISPATCHER_PASS` | F006 | DispatcherNavigator |
| 质检员 | `f006_quality_insp` | `$TEST_QUALITY_INSP_PASS` | F006 | QualityInspectorNavigator |

⛔ F001 那套账号（`factory_admin1` / `workshop_sup1` …）是死值：迁移 `V20261029_68` 把工厂域收敛成只剩 F006 + LIUSHANMEN，F001 连同账号物理删除，用它们登录一律 401，长相是「E2E 全挂」，别误读成应用坏了。
✅ F006「六膳门食品科技」是团队自己的测试租户，可以写入。⛔ LIUSHANMEN「六膳门」是真客户，名字很像，只读。

## 环境检查（run 前）

`maestro --version`（未装：`curl -Ls "https://get.maestro.mobile.dev" | bash`）；`adb devices` 有设备；`adb shell pm list packages | grep com.cretas.foodtrace` 已装 APK（否则 `adb install`、`eas build --profile preview --platform android` 或 `npx expo run:android`）；`tests/e2e-native/.maestro/flows/` 存在（否则先 `gen`）。缺任一项就报清楚并停，⛔ 不降级处理。

## 关键约束

1. 所有选择器用 `id:`（基于 testID），⛔ 禁止文本匹配（i18n 双语文本不稳定）、禁止坐标定位；用 `assertVisible` + `timeout` 等元素，`env:` 参数化凭证，`runFlow:` 复用 `common/_login.yaml` / `_logout.yaml` / `_wait_for_app.yaml`。
2. 登录是同组件内状态切换（LandingView → LoginFormView），不是页面导航。
3. 封装组件（`NeoButton` 等）要先确认 testID 透传到根元素（`grep -n testID src/components/common/NeoButton.tsx`），不透传就先改组件定义再在调用处加；改公共组件要告知用户。
4. inject 只加 `testID` 属性，不动样式 / 逻辑；命名 `{screen}-{element}-{semantic}`，规则见 `references/testid-naming-convention.md`。扫描覆盖 TouchableOpacity / TextInput / Pressable / ScrollView / NeoButton / Paper Button·IconButton·FAB / FlatList·SectionList。
5. gen 时读 `references/maestro-flow-templates.md`（YAML 模板）与 `references/role-flow-matrix.md`（角色 → 屏幕）；每角色至少 `smoke.yaml`（登录 → 首页 → 核心卡片可见）+ 功能流。
6. macOS 推荐 EAS Build 或 `npx expo run:android` 构建 APK；Windows 用 `/build-android-apk`。

## 错误处理

| 场景 | 处理 |
|------|------|
| 登录失败 | 截图 + 核对账号是否 F006 那套 + 后端健康 |
| 元素未找到 | 截图 + `maestro hierarchy` 看 View 树，检查 testID 是否已注入 / 透传 |
| Flow 超时 | 截图、标记 TIMEOUT、继续下一 Flow |
| Maestro 崩溃 | 保存日志，重启模拟器重试 |

报告按 Flow 列状态 / 耗时 / 失败步骤 / 截图路径，并给 testID 覆盖率变化；格式自拟，不需要模板。

## 协作

`/build-android-apk` 或 `eas build` → `/e2e-native scan` → `inject {screen}` → `gen {role}` → `run {role}` → `report`。
