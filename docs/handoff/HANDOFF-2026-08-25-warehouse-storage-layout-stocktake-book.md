# 仓储空间、现场盘点与仓库平面图交接手册

**交接日期**：2026-08-25，Asia/Singapore。  
**交接对象**：Claude Code 新任务。  
**范围**：工厂 Web Admin、React Native Android、Java 仓储接口、OA 审批衔接、仓库画布与 AI 底图识别。  
**当前阶段**：首版已经合入、生产部署并发布 Android OTA；下一阶段是基于真实现有功能继续验证、补缺、优化，而不是重新从零设计。  
**真实性边界**：本文分别记录实现、测试、PR、主分支、生产发布、OTA 和尚未完成的用户验收；任何一项不能替代另一项。

## 1. 接手前必须确认

按顺序读取：

1. `C:\Users\Steve\my-prototype-logistics\AGENTS.md`。
2. Claude Code 当前实际适用的 `CLAUDE.md`、项目规则及项目设计规范。
3. 最新 `origin/main` 的 `docs/dispatch/ACTIVE.md` 与 `docs/dispatch/archive/2026-08-25-active-history.md`。
4. 本交接手册以及同目录的 `PROMPT-2026-08-25-claude-code-warehouse-storage-layout.md`。
5. 本文第 5 节列出的实际生产代码，而不只是 `docs/demos` 里的原型。
6. 如需真实账号，只能只读使用主工作区 gitignored 的 `.codex/rules/test-credentials.md`；严禁把凭证写入仓库、截图、日志或回复。

启动后执行只读预检：

```powershell
git fetch origin
git worktree list --porcelain
git branch --show-current
git rev-parse HEAD
git rev-parse origin/main
git status --porcelain --untracked-files=normal
git show origin/main:docs/dispatch/ACTIVE.md
gh pr view 76 -R cretasfuture-ai/cretas --json state,mergedAt,mergeCommit,url
```

以重新 fetch 后的 `origin/main` 为真值；以下 SHA 是 2026-08-25 编写时的已核验快照，可能继续前进。

| 对象 | 已核验状态 |
| --- | --- |
| 功能 PR | `https://github.com/cretasfuture-ai/cretas/pull/76`，状态 `MERGED` |
| 功能 squash commit | `a05f534c5f8884df31e783474063cc88079db934` |
| 编写时最新主分支和生产发布 SHA | `130d7ba0ad322c8cb115346f6577b10a0a263a9d`；包含 PR #76，并额外包含成本修复 PR #77 |
| 原功能 worktree | `C:\Users\Steve\my-prototype-logistics\.worktrees\codex-storage-locations-20260825` |
| 原功能分支 | `codex/storage-locations-20260825`，HEAD `85630abf56bbc9409c56d6f1f38273752ec3a304`；它不是最新 main，不能直接用于下一轮生产发布 |
| 已核验发布 worktree | `C:\Users\Steve\cretas-release-warehouse-storage-a05f534c-20260825`；detached HEAD，当时实际为 `130d7ba0ad322c8cb115346f6577b10a0a263a9d` |
| 旧 `C:\Users\Steve\cretas-mainmerge` | 本地 `main` 指向旧 `legacy-steven/main` 历史，与当前远端历史无共同祖先；禁止对其 reset、强切、合并或用它发布 |
| 主工作区 | `C:\Users\Steve\my-prototype-logistics`，可能有其他 Owner 的 dirty 内容；禁止直接修改、reset、stash、clean 或覆盖 |

本手册和 prompt 是为本次交接新增的本地文档，放在原功能 worktree 内；它们尚未 commit、未进入 `origin/main`，不要误报为已合并或已部署。

## 2. 用户确认的产品真值

### 2.1 区域类型与具体储存位置

“仓位”实际是具体储存位置，不是一个位置只能放一种商品的固定货格。一个位置更接近小型存储空间，可以同时放多种物料、多个批次。

两层配置必须分开：

1. **低频维护：区域类型**，例如冷藏区 `LC`、普通冻库 `DK`、急冻区 `JD`、干仓 `GC`。配置的是中文区域名称和大写英文前缀，不是位置数量。
2. **高频维护：具体位置**，例如 `LC01`、`LC02`、`DK01`。位置属于区域；可以不断增删维护，而不需要重新配置区域类型。

首次初始化可以连续完成两层配置；长期使用应主要维护具体位置。用户不接受每次维护都重复第一步。

### 2.2 现场盘点

页面顶部按 **原料、成品、包材、辅材** 四类切换。每一类现实中可能有数百个物料，因此不能把全部内容一次堆在卡片里；需要真实搜索、分页或渐进加载。

操作路径：

```text
选择大类 → 搜索物料 → 选择物料 → 查看不同批次
→ 每个批次显示当前剩余数量、真实储存位置、在库天数
→ 需要移位时直接调整位置
→ 需要盘盈或盘亏时进入正式审批流程
```

位置过滤是两级选择：先选择区域类型/前缀，如 `LC`；再在该类型下选择具体位置，如 `LC01`。可以使用二级抽屉、分组选择器或等价的清晰交互。

在库天数只表示**现在仍留在仓库中的这一批物料已经存放多久**，不要加入“今天是否出库”“部分出库”等无关信息。界面用数字、位置、批次和状态表达；避免大量解释性文案。

### 2.3 数量与审批边界

- 只改储存位置：仓储授权人员可以直接执行，不进入数量审批。
- 只要调整数量，不论盘盈还是盘亏：必须通过已有 `INVENTORY_ADJUSTMENT` OA 工作流。
- 如果工厂没有发布可用的该工作流：必须明确阻止，不能自动审批、不能静默过账。
- 仓管员不能自己审批自己；审批人应按工厂配置交给上级。现有代码已接入工作流，但“提交人与审批人必须不同”的真实运行时验收仍未完成。
- 流程配置应复用现有 Canvas 审批编辑器 `/platform/canvas-editor`，审批处理复用 `/workflow/pending`，不要再新建一套孤立审批系统。

### 2.4 仓库平面图

层级：

```text
楼层 → 储存区域 → 货架/柜子 → 垂直层 → 具体位置 → 位置内多个物料/批次
```

Web 左侧显示仓库平面；选中货架后，右侧展示货架的垂直层与位置、批次、在库时间。工厂可能没有现成底图，需要逐步支持拖拽绘制区域、货架、门、通道及标注；也可上传照片或手绘图，由真实视觉 AI 识别，再人工确认和编辑。

工厂管理员 `factory_super_admin` 和仓管管理员 `warehouse_manager` 都能编辑和识别；其他用户按角色只读。手机也要能查看同一份仓库平面配置。

### 2.5 UI 偏好

- 必须延续已有 Web Admin 和 App 的设计与业务流程，而不是凭空另做一个系统。
- 字号、留白、对比度和点击区域要清晰；3～4 个信息字段适合卡片，但几百个物料必须搜索/分页。
- 去掉说明式废话，让页面结构本身表达操作路径。
- 不要为了形式把短字段做成长表格行，也不要用机械化的一整列重复卡片。
- 手机预览是未来可能跨模块复用的通用能力，并非本次已完成的平台级功能。

## 3. 已实际生产上线的实现

### 3.1 后端与持久化

`WarehouseLayoutController.java` 新增同厂仓储配置 API：

```text
GET  /api/mobile/{factoryId}/warehouse/layout
PUT  /api/mobile/{factoryId}/warehouse/layout
POST /api/mobile/{factoryId}/warehouse/layout/recognize
PUT  /api/mobile/{factoryId}/warehouse/layout/finished-goods/{batchId}/location
```

配置复用现有 `FactoryFeatureConfig` JSONB，`moduleId = WAREHOUSE_LAYOUT`，不新增 Flyway、Entity、Repository 方法或表。结构包含 `zones`、`locations`、`floors`，位置可关联 `rackId` 和 `level`。

已实现校验：区域前缀 2～8 位大写英文字母、前缀不重复、位置编码不重复、位置必须归属有效区域并使用对应前缀；最多 100 个区域、20,000 个位置。

- 配置保存与 AI 识别：`factory_super_admin`、`warehouse_manager`。
- 成品批次移位：上述两种角色及 `warehouse_worker`；代码检查批次属于当前路径工厂。
- AI 识别接入现有 `PythonLLMClient.visionChat`，不是前端伪造识别结果。
- 原料/包材/辅材移位沿用已有 `PUT /material-batches/{batchId}`。
- `FactoryStocktakeServiceImpl.submitForApproval` 在有数量差异且无已发布 `INVENTORY_ADJUSTMENT` 工作流时抛出 `STOCKTAKE_APPROVAL_WORKFLOW_REQUIRED`；无差异的现有流程保持原样。

### 3.2 Web Admin

线上入口：

```text
https://admin.cretaceousfuture.com
/warehouse/storage-locations
```

实际现有系统内包含三个页签：`储存位置`、`现场盘点`、`仓库平面图`。已接通工厂持久化配置、四类库存、搜索、前缀/位置分组过滤、真实剩余数量、在库天数、现有盘点入口、拖拽货架、楼层、垂直层、位置绑定和 AI 上传识别。

已有盘点任务继续使用：

```text
/warehouse/stocktakes
/workflow/pending?moduleCode=INVENTORY_ADJUSTMENT
/platform/canvas-editor
```

### 3.3 Android App

现有 `WHPutawayScreen` 与 `WHLocationManageScreen` 读取与 Web 相同的同厂配置；上架保存真实位置编码，而不是固定写入假值。位置页面可打开楼层/货架地图，支持横向滚动和货架层查看。

生产 OTA 已发布：

```text
channel         production
platform        android
runtimeVersion  1.0.4
timestamp       1787659607345
```

已从真实生产 manifest 回读到 `1787659607345`。这证明 OTA 包已成为服务端当前更新，不代表任意具体客户手机已经下载、重启或完成用户验收。

## 4. 可核验发布与测试证据

### 4.1 正式生产发布回执

```text
C:\Users\Steve\.cache\cretas\release-reports\cretas-1787659120-30150.json
```

真实回执：

```text
status                  deployed
head_sha                130d7ba0ad322c8cb115346f6577b10a0a263a9d
components.java         deployed
components.web          deployed
backend_health          pass
web_http                200
upstream_at_acceptance  47.100.235.168:10010
web_index_sha256        69fe0874158a73b457c4e197993aa7417a910a568c1ab2ef92f178285a56bde5
```

Web 本地、服务器、网关 HTTP 和公网 HTTPS 四方哈希一致。`10010` 只是验收时刻的真实 active 槽位，后续蓝绿部署可能变化，严禁写死。

### 4.2 已通过测试

- `WarehouseLayoutControllerTest`：8 项，覆盖默认空配置、JSONB 保存、前缀、位置归属、视觉客户端接入、角色注解、同厂成品移位和跨厂批次拒绝。
- `StocktakeWorkflowIntegrationTest`：8 项，覆盖盘点 OA 提交、盘盈盘亏 fail closed 等场景。
- Web 定向 Vitest：2 个文件，7 项通过。
- Web 完整 GitHub `vitest`/Vue 类型检查：PR #76 通过；secret scan 通过。
- React Native 全量 TypeScript：通过。
- 真实 Web Vue 页面 + mock API 浏览器流程：`factory_super_admin`、`warehouse_manager` 均可编辑，`quality_manager` 只读。

浏览器脚本与截图：

```text
D:\Temp\codex-real-warehouse-layout-acceptance.cjs
D:\Temp\codex-real-warehouse-layout-acceptance.png
```

这些是真实页面、路由和组件的 UI 验证，但 API 是拦截 mock；不能把它描述为真实生产业务 E2E、数据库持久化验收或真实 AI 模型验收。

典型目标命令：

```powershell
# Web；在 web-admin 目录执行
node node_modules/vitest/vitest.mjs run src/views/warehouse/storage-locations/warehouseLayoutModel.spec.ts src/views/warehouse/stocktakes/stocktakeOa.source.spec.ts
node node_modules/vue-tsc/bin/vue-tsc.js --project tsconfig.app.json --noEmit --incremental false --pretty false

# Android；在 frontend/CretasFoodTrace 目录执行
node node_modules/typescript/bin/tsc --noEmit --pretty false --incremental false

# Java；发布候选最终制品只允许按现有统一发布/manifest 流程执行一次对应生命周期
./scripts/deploy/release-java-preflight.sh --tests WarehouseLayoutControllerTest,StocktakeWorkflowIntegrationTest
```

真实 OTA 只读验证：

```powershell
ssh root@47.100.235.168 "curl -fsS -H 'expo-protocol-version: 1' -H 'expo-platform: android' -H 'expo-runtime-version: 1.0.4' -H 'expo-channel-name: production' http://localhost:8083/api/ota/manifest | grep -o '1787659607345' | head -n 1"
```

## 5. 生产代码定位

```text
backend/java/cretas-api/src/main/java/com/cretas/aims/controller/WarehouseLayoutController.java
backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java
backend/java/cretas-api/src/test/java/com/cretas/aims/controller/WarehouseLayoutControllerTest.java
backend/java/cretas-api/src/test/java/com/cretas/aims/service/factory/impl/StocktakeWorkflowIntegrationTest.java

web-admin/src/api/warehouseLayout.ts
web-admin/src/router/index.ts
web-admin/src/views/warehouse/storage-locations/index.vue
web-admin/src/views/warehouse/storage-locations/warehouseLayoutModel.ts
web-admin/src/views/warehouse/storage-locations/warehouseLayoutModel.spec.ts
web-admin/src/views/warehouse/stocktakes/index.vue
web-admin/src/views/workflow/pending.vue
web-admin/src/views/platform/canvas-editor/index.vue
web-admin/src/views/platform/approval-workflow-editor/index.vue

frontend/CretasFoodTrace/src/services/api/warehouseLayoutApiClient.ts
frontend/CretasFoodTrace/src/screens/warehouse/inbound/WHPutawayScreen.tsx
frontend/CretasFoodTrace/src/screens/warehouse/inventory/WHLocationManageScreen.tsx
frontend/CretasFoodTrace/app.json

docs/demos/storage-location-rule-preview.html
docs/demos/warehouse-spot-check-preview.html
```

两个 HTML 仅用于理解前期交互预期，已上线入口是 Vue 页面；不要把修改本地 HTML 当作修复生产系统。

## 6. 尚未闭环的问题：必须诚实交接

### P0：真实权限、工厂隔离与审批运行时

1. 当前 Controller 的角色测试主要通过反射检查 `@RequireRole`，不是带真实登录 token 的完整 HTTP/RBAC 集成测试。
2. `GET /warehouse/layout` 没有方法级 `@RequireRole`；要核实全局认证以及 token 工厂与路径 `{factoryId}` 的一致性是否确实被统一拦截器强制，尤其是 A 工厂 token 访问 B 工厂 GET/PUT/recognize。
3. 成品移位已检查批次工厂，但 `storageLocation` 当前只校验非空和长度，没有证明目标位置确实存在于该工厂配置；需要验证并视结果 fail closed。
4. 原料/包材/辅材移位复用旧材料批次接口；需要真实验证仓管角色权限、跨厂拒绝、只改位置不改数量、持久化回读。
5. 需用真实已发布的 `INVENTORY_ADJUSTMENT` 工作流验证盘盈、盘亏、无差异、禁用流程和未配置流程；验证提交人不是审批人，审批前库存不变，审批后只过账一次，重复审批/并发提交不能重复调整。
6. 生产业务写入默认 0。只有用户明确授权具体 F006 写入测试时，才可按 `AGENTS.md` 的 F006 租户规则控制写入；其他租户绝对零写。

### P1：数百物料的真实搜索与分页

目前 Web 首次只读取 `material-batches size=200`、`finished-goods size=500`，物料卡片最终 `.slice(0, 36)`；成品搜索还是本地过滤，非成品搜索虽然有 keyword 请求，但仍固定第一页。Android 位置统计也只读取 200 条原料批次。

因此不能宣称“几百/几千项已经全量可查”。后续需复用现有分页 API，补服务端类别、名称/编码/批号、前缀、具体位置条件，分页/无限加载、空态、加载态和跨页选择保持；同位置多个类别/批次计数必须准确。

### P1：AI 上传必须真实可用，而不是只有代码接线

现有单元测试 mock `PythonLLMClient`；没有真实手绘图/仓库照片经过实际视觉模型的端到端证据。后续需覆盖：

- 手绘、打印图、倾斜拍照、模糊图、光照不均、旋转图片及多楼层。
- 模型不可用、超时、图片类型不符、超过 8MB、输出非 JSON、低置信度、重复前缀、未知 zoneId。
- 识别结果先预览、人工确认和纠正，而不是未经确认自动覆盖。
- 模型返回百分比坐标与 Vue Flow 像素坐标的一致转换；保持楼层、区域、货架、门、通道、标注的可编辑关联。
- 当前背景图直接把 base64 存在 JSONB 配置里；需要评估请求大小、数据库膨胀、缓存、权限、对象存储与历史图更新策略。

### P1：画布能力仍是首版

当前页面已有楼层、拖拽货架、货架层、位置绑定和底图，但还没有完整的自由画区域、画墙、画门、画通道、尺寸缩放、撤销/重做、区域局部放大、删除保护、图层管理、批次明细展开或并发编辑冲突保护。

一个位置必须持续允许多个物料和批次，不得为了让画布好看而错误地把位置设计成“一格一个 SKU”。

### P1：手机现场盘点与地图

当前 Android 只是接通既有“上架”和“库位管理”页面，并增加地图查看；尚未交付独立的四类别现场盘点新页面，也没有真实 Android 设备的登录、移位、审批或地图 E2E。

`app.json` 当前为：

```json
{
  "orientation": "portrait",
  "runtimeVersion": "1.0.4"
}
```

仓库地图虽然支持横向滚动，但**没有实现真正的强制横屏**。全局 portrait 与缺失的 native orientation 能力不能通过当前 OTA 单独修复；若用户明确要求强制横屏，必须设计屏幕级 orientation 方案、评估 native 依赖与 runtimeVersion、重新构建和分发 Android 安装包，然后做真机验收。

### P2：通用手机预览

用户设想 Web Admin 各模块都能预览相同角色在手机中的真实操作路径，例如创建生产订单后查看对应 App 入口。当前只讨论过这个产品方向，尚未实现跨模块通用预览框架、角色映射或 Web→App 流程跳转；不得误报为已上线。

## 7. 下一阶段推荐执行顺序

1. fresh fetch、检查 ACTIVE 和 worktrees；从最新 `origin/main` 创建独立 worktree/分支，登记唯一 Owner 与明确 scope。
2. 先进行真实线上**只读**检查：Web 菜单、四种角色入口、仓库布局 GET、现有审批编辑器和待审批入口、OTA manifest、零写保护。
3. 在隔离环境补真实 HTTP/JWT/跨工厂/审批幂等测试，修复真正发现的问题；不要修改 Repository/Entity/Flyway，除非需求确实需要且同时补真实 JPA 启动门禁。
4. 补大数据量服务端搜索与分页、成品/原料/包辅四类一致性、同位置多批次聚合与位置选择二级交互。
5. 完成真实 AI 图像验证、人工确认流程和画布交互补强，保留既有 Web Admin 风格。
6. 若用户要求移动端专项，先补真实现场盘点页面和真机验证；强制横屏必须单列为 native APK 交付，不得假称 OTA 已解决。
7. 统一执行目标测试、类型检查、真实浏览器/设备验收；只有用户另行要求合并/部署时，才走 PR、exact-main、统一 release 和 OTA，并分开报告各个状态。

## 8. 工作区与授权硬边界

- 不要改动脏主工作区，也不要接管任何其他 ACTIVE Owner 的锁。
- 当前仓储首版 dispatch 条目已经归档释放；后续改动是新任务，必须新登记 scope。
- 本轮“整理交接”只授权生成文档，不授权再次合并、部署、生产写入、APK 构建或迁移。
- 不要因为某个旧会话使用了 Codex 的限制，就去删除或修改 Claude Code 自己的插件、skills 或配置；Claude Code 按其适用项目规则工作。
- 生产发布有全局锁，多任务并行时必须串行；不能为了抢速度杀掉其他发布进程。
- 实现、测试、commit、PR、merge、部署、OTA、真实设备和用户验收必须分别给证据；缺失就明确写未完成。
