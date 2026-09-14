# FEAT-RN-BOSS-FIELD-ENTRIES-20260915 — brief 全文

**登记**: 2026-09-15 06:20 organizer。**执行**: sister executor(Opus)。**分支**: `codex/claude-boss-field-entries-20260915`（off origin/main `e0e9afef75`）。

## 缺口（已核实）
- `AppNavigator.tsx:108`：`factory_super_admin`（非餐饮）→ `BossNavigator`；其 OverviewStack 只有 BossOverview / BossProductionPlans / BossSalesOrders / SalesOrderDetail / BossPurchaseOrders / PurchaseOrderDetail / BossFinishedGoods。
- `Traceability` → `TraceabilityDetail({batchNumber})`、`VisionVerify`、`SourceIndividualForm` 只注册在 `ProcessingStackNavigator`，而它只被 `MainNavigator`（platform_admin / equipment_admin / quality_manager / permission_admin）挂载。
- onboarding 默认用户只有 `_admin`(factory_super_admin) / `_workshop`(workshop_supervisor) / `_worker1`(operator)，三者都到不了这三个屏 ⇒ 演示租户没有任何账号能演示。
- 顺带：`PublicTraceScreen` 全仓无 navigate/linking 入口（只注册了路由），公开页那句「本批来自 N 头牛」改放 `TraceabilityDetailScreen`（已通知 Line A）。

## 要做
1. `BossNavigator.tsx` OverviewStack 追加 `Traceability`、`TraceabilityDetail`、`VisionVerify`、`SourceIndividualForm`（复用组件，不改屏文件）。
2. `BossOverviewScreen.tsx` 三个入口按钮：溯源查询(`qrcode-scan`) / 拍照核验(`camera-outline`) / 源头个体(`card-account-details-outline`)，testID `boss-entry-traceability` / `boss-entry-vision-verify` / `boss-entry-source-individual`。
3. 闸 `__tests__/gates/factoryBossReachesFieldEntries.test.ts`（仿 `factoryBossReachesReports.test.ts`）；同步既有按角色列可达屏的闭合清单闸。
4. 变异两枚：删 `TraceabilityDetail` 注册 → 红；「溯源查询」navigate 目标改成 `BossProductionPlans` → 红。先证明变异到达。
5. `npx tsc --noEmit` + `npx jest src/__tests__/gates src/__tests__/unit/navigation` 落文件读数；既有红用 detach 基线树比对归因。

## 纪律
只碰 BossNavigator.tsx / BossOverviewScreen.tsx / 闸文件 /（如需）types/navigation.ts Boss 段；⛔ 不碰 traceability/、shared/VisionVerifyScreen、LineageChainTimeline（Line A 在改）。safe-commit、一次性凭证推送、只到 PR、不部署不 OTA。
