# Claude Code 接管提示词：仓储空间、现场盘点、平面图与审批

将下面整段直接粘贴到 Claude Code 新任务：

```text
接手并持续完善已经生产上线的“仓储空间 / 储存位置 / 现场盘点 / 仓库平面图 / AI 底图识别 / Android 同步 / OA 数量审批”功能。不要只输出方案；先核实真实现状，然后按授权持续实施、测试、修复和验收。禁止把本地 HTML artifact 当作生产实现，也不要从零重建已经存在的 Web Admin、手机端或审批工作流。

首先完整读取：

C:\Users\Steve\my-prototype-logistics\AGENTS.md
当前 Claude Code 实际适用的 CLAUDE.md 和项目规则
C:\Users\Steve\my-prototype-logistics\.worktrees\codex-storage-locations-20260825\docs\handoff\HANDOFF-2026-08-25-warehouse-storage-layout-stocktake-book.md

然后立即只读检查：

git fetch origin
git worktree list --porcelain
git branch --show-current
git rev-parse HEAD
git rev-parse origin/main
git status --porcelain --untracked-files=normal
git show origin/main:docs/dispatch/ACTIVE.md
gh pr view 76 -R cretasfuture-ai/cretas --json state,mergedAt,mergeCommit,url

已知事实只作为历史快照，必须重验：PR #76 已 merged，功能 commit a05f534c5f8884df31e783474063cc88079db934；2026-08-25 生产部署 SHA 为 130d7ba0ad322c8cb115346f6577b10a0a263a9d；正式 Java 和 Web 已 deployed；Android OTA production/android/runtime 1.0.4/timestamp 1787659607345 已注册且生产 manifest 曾回读通过。发布回执：C:\Users\Steve\.cache\cretas\release-reports\cretas-1787659120-30150.json。主分支、生产进程、蓝绿 upstream 和 OTA 可能已变化，以实时证据为准。

不要修改脏主工作区 C:\Users\Steve\my-prototype-logistics；不要 reset、stash、clean、覆盖或整理其他 Owner 的工作。C:\Users\Steve\cretas-mainmerge 的旧 main 属于 legacy-steven 历史，与当前远端可能无共同祖先，禁止用它切换或发布。现有交接文档在原功能 worktree 内尚未 commit；原功能分支不是最新 main。真正开始改代码前，从 fresh origin/main 创建新隔离 worktree/分支，并按最新 docs/dispatch/ACTIVE.md 登记唯一 Owner 和明确 scope。

用户已经明确确认的产品契约：

1. 储存区域类型 = 中文名称 + 大写英文前缀，例如冷藏区 LC、普通冻库 DK、急冻 JD、干仓 GC。区域类型低频维护；具体储存位置 LC01、LC02、DK01 高频维护，两层配置分开，一个位置可以存放多个物料和多个批次。
2. 现场盘点四大类：原料、成品、包材、辅材。每类可能有数百个物料，必须服务端搜索和分页/渐进加载；选择物料后显示各批次真实剩余数量、位置和在库天数。不要展示无关的“今天是否出库”，不要堆砌解释文字。
3. 位置筛选必须按区域前缀→具体位置做二级选择。位置调整可以直接生效；任何数量增加或减少必须进入已有 INVENTORY_ADJUSTMENT OA 审批，未发布流程时 fail closed，审批人不得是提交仓管本人，审批前库存不变，审批后只变更一次。
4. 仓库平面图层级是楼层→区域→货架/柜子→垂直层→位置→多个批次；左侧平面、右侧货架立面。工厂管理员 factory_super_admin 和仓管管理员 warehouse_manager 均可编辑/AI识别，其他角色只读。
5. AI 底图需支持手绘/拍照识别和人工确认，不得假成功；画布应延续现有 Vue Flow/Canvas 能力，可逐步补画区域、货架、门、通道、标注。
6. Android 已通过 OTA 接通现有上架和库位页面，但没有独立四分类盘点页面，未做真实设备 E2E；全局 app.json 仍是 orientation=portrait，真正强制横屏必须重新构建 native APK，不能声称 OTA 已解决。

核心代码：

backend/java/cretas-api/src/main/java/com/cretas/aims/controller/WarehouseLayoutController.java
backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java
backend/java/cretas-api/src/test/java/com/cretas/aims/controller/WarehouseLayoutControllerTest.java
backend/java/cretas-api/src/test/java/com/cretas/aims/service/factory/impl/StocktakeWorkflowIntegrationTest.java
web-admin/src/api/warehouseLayout.ts
web-admin/src/views/warehouse/storage-locations/index.vue
web-admin/src/views/warehouse/storage-locations/warehouseLayoutModel.ts
web-admin/src/views/warehouse/stocktakes/index.vue
web-admin/src/views/workflow/pending.vue
web-admin/src/views/platform/canvas-editor/index.vue
frontend/CretasFoodTrace/src/services/api/warehouseLayoutApiClient.ts
frontend/CretasFoodTrace/src/screens/warehouse/inbound/WHPutawayScreen.tsx
frontend/CretasFoodTrace/src/screens/warehouse/inventory/WHLocationManageScreen.tsx
frontend/CretasFoodTrace/app.json

优先逐项验证和补强：

P0：真实登录 token 的工厂隔离及角色矩阵，包括 GET/PUT/AI识别/成品移位；确认 token.factoryId 与 URL factoryId 始终一致；位置必须属于当前工厂已配置位置；验证原料/包材/辅材旧接口移位权限与只改位置；真实审批覆盖盘盈/盘亏、无差异、未发布、禁用、自审拒绝、审批前后库存和幂等。

P1：修复当前 material-batches 只取 200 条、finished-goods 只取 500 条、卡片 slice(0,36)、成品只有前端搜索、Android 只统计 200 批的问题，做真实大数据量搜索、过滤、分页和同位置多批次汇总。

P1：使用真实手绘图/拍照调用视觉模型，验证低置信度、失败、超时、重复区域、未知 rack.zoneId、坐标换算，并增加预览确认；评估把 base64 背景长期塞进 JSONB 的体积/权限/存储问题。

P1：增强地图手工画区域/货架/门/通道、层级放大、货架层、批次日期和编辑冲突保护；优化 B 端 UI，少解释、清晰卡片和真实搜索。

P1：按用户后续授权补 Android 四分类现场盘点、真实设备验证；如果明确要求强制横屏，单独设计 native orientation 与 APK 交付，不得用 OTA 冒充 native 变更。

默认生产业务写入为 0。只有用户对具体 F006 写入场景明确授权，才允许按 AGENTS.md 的 F006 隔离规则执行；其他租户、全局配置、审批发布、迁移、生产部署或 APK 发布均不得自动扩大授权。涉及 Entity/Repository/Flyway 时必须增加真实 JPA 启动门禁。已有浏览器验收脚本 D:\Temp\codex-real-warehouse-layout-acceptance.cjs 是真实 Vue 页面但 mock API，不得当成生产持久化 E2E。

最终分别报告：修改文件、目标测试、真实权限/审批/AI证据、commit/PR/main 状态、生产部署状态、Android OTA 或 APK 状态、真实设备验收、剩余阻塞；没有发生的动作必须明确写未执行。
```
