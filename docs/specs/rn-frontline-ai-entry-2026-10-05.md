# RN 一线角色 AI 助手入口（Line B）

**日期**: 2026-10-05
**分支**: `codex/claude-frontline-ai-entry`
**范围**: React Native（`frontend/CretasFoodTrace`）+ web-admin 功能配置页一行（让运营能在后台点掉开关）

## 背景与裁定

- 现状：只有工厂管理类角色（factory_super_admin / workshop_supervisor / warehouse_manager / dispatcher / hr_admin）能进 `AIChatScreen`。
  operator、warehouse_worker、quality_inspector 三个一线角色**没有任何 AI 入口**。
- 2026-10-05 owner 裁定（经 coordinator 转达）：**三个一线角色默认就有入口**；开关保留为**熔断开关（kill switch）**，
  可以远程 / OTA 关掉，不需要发商店版本。⛔ 不是「默认关、按需开」。
- 服务端是唯一的权限裁判：意图级 `IntentPermissionGate`（`requiredPermission` → 同 HTTP 层权限矩阵）+ 旧 `requiredRoles`。
  `/ai-intents/execute*` 端点本身不挂 `@RequirePermission`，角色取自 JWT，请求体里不带角色 ⇒ 客户端**无从绕过**。
  本改动只加入口，不碰任何服务端判定。

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像

- **operator（操作员 / 小组长）** — 年纪偏大、文化程度参差、戴手套、车间嘈杂；只会用「工序」一个 tab —
  典型场景：站在产线边想问「我下一道是什么」「刚才那批报了没有」，或者口述「包装好了 60 箱」完成报工。
- **warehouse_worker（仓库员）** — 同上，客户原话「年纪都比较大文化素质很低，你告诉他这个东西你要收多少就行了」 —
  典型场景：手上拿着货，想问「这个料还有多少」「今天要发哪几单」。
- **quality_inspector（现场质检员）** — 只做标签拍检；对 App 的熟悉程度最低的一类 —
  典型场景：想问「上一批拍检结果出来没有」「这个 SKU 要拍哪几面」。

### 用户旅程

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | 自己的首页（操作员=工序任务；仓库员=收货/发货/库存三个 tab 的首屏；质检员=质检拍照首页）底部一条大按钮：麦克风图标 +「AI 助手」+「点这里，说一句话」 | 戴手套点一下按钮（整条都能点，≥64pt 高） | 进入 AI 对话页，顶部有返回 |
| 2 | 对话页：欢迎语 + 一句「点下面的麦克风，直接说你要做的事」 + 底部大麦克风 | 点麦克风说话 | 识别出的文字先显示 1.5 秒预览，可「取消」 |
| 3 | 预览自动发出，AI 回答；如果说得不清楚，出现澄清按钮（「完成工序」「记录称重」…） | 点其中一个按钮 | 按选中的意图执行，不用重说 |
| 4 | 写操作（如报工开始/完成）先出**确认预览卡**，卡上带品名/批次 | 点「确认」或「取消」 | 确认后才写入；服务端无权限时显示「需要 X 权限，请联系管理员」 |
| 5 | 回答完成 | 点左上返回 | 回到原来的首页，原页面状态不变 |

### 摩擦点清单

| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | 入口太小 / 只有图标没字，戴手套点不中、认不出是什么 | HIGH | 内联 UX 规则 · 触摸目标 ≥44pt |
| F2 | 入口抢主操作：操作员首页的主操作是「点批次报工」，仓库员是「收货/发货任务」，AI 入口不能比它们更醒目 | HIGH | 内联 UX 规则 · 主操作按钮唯一 |
| F3 | 欢迎页写着「我可以帮您：」后面**什么都没有**（一线角色没有快捷卡组，`QuickActionCardGrid` 返回 null）——像坏了 | HIGH | fool-proof Rule 5（dead-end）|
| F4 | 一线用户不知道能问什么；给示例卡片又怕点下去被服务端拒（卡片对应的意图未逐角色核实）| MED | fool-proof Rule 5 / 形态 D′（会误报的提示比没有更糟）|
| F5 | 服务端拒绝（无权限）时只看到一句技术话 | MED | 4 位一体（message 原样 + next action）|
| F6 | 场景过滤（`SCENE_CONFIG` 按 `entityType` 过滤建议按钮）误伤澄清/确认按钮 | MED | 形态 B（机制在、接错信号）|
| F7 | 熔断开关关掉后，入口没了但路由还在（深链 / 返回栈还能进）| MED | 形态 B |
| F8 | 仓库员的首屏不固定：收货、发货 tab 可被工厂功能配置关掉，入口只放在某一个 tab 会消失 | MED | fool-proof Rule 5 |
| F9 | 进入对话再返回，首页任务列表被重置 / 重新加载丢位置 | LOW | 错误恢复 · 保留已填数据 |

### 每个摩擦点的设计回应

- F1 → 全宽条形按钮，`minHeight: 64`，麦克风图标 32px，主标题「AI 助手」18px 粗体 + 副标题「点这里，说一句话」；
  用 Paper `TouchableRipple`（ripple 反馈）；`accessibilityRole="button"` + `accessibilityLabel="AI 助手，点这里说一句话"`。
- F2 → **描边次级样式**（surface 底 + primary 描边，不用实心主色），放在首页**底部**（操作员 / 仓库员固定在列表下方、tab 栏上方；
  质检员放在两张任务卡之后），不压在任务列表上面，不改变既有主操作的位置。
- F3 → `AIChatScreen` 欢迎区：当前角色**解析不到卡组**时，不再显示「我可以帮您：」，改显示
  「点下面的麦克风，直接说你要做的事。」（`aiChat.voiceHint`）。有卡组的角色（老板 / 车间主任 / 仓储主管 / 餐饮 / 牛业）不变。
  ⚠️ 这一条同时影响其它无卡组角色（dispatcher、hr_admin 等）——它们今天也看到同一个悬空冒号，改了是同一处修复。
- F4 → **本轮不给一线角色加示例卡片**：卡片发出的句子会落到哪个意图、该意图对这个角色放不放行，
  需要逐条在服务端核实，未核实就上线，最先被点的那张卡可能直接被拒。候选卡组列入 owner 决策项（见末尾）。
- F5 → 不改：拒绝文案由服务端 `IntentPermissionGate` 回带缺失权限码，对话页既有渲染「需要 X 权限」。本入口不新增任何客户端判定。
- F6 → 入口导航到 `AIChat` **不带 `entityType`** ⇒ `sceneConfig` 为 undefined ⇒ 建议按钮不过滤。
  测试钉住：三个角色下澄清按钮和追问确认按钮都渲染；对照：带 `entityType` 时过滤确实生效（证明过滤器活着）。
- F7 → 熔断关掉时，三个角色导航器里**连 `AIChat` 路由都不注册**（与 `WarehouseWorkerNavigator` 现有的
  `isScreenEnabled(...) && <Tab.Screen/>` 同一写法）。
- F8 → 仓库员三个 tab 的首屏（收货 / 发货 / 库存）都放同一个入口，位置一致（底部）；组件自己按角色判断，
  `warehouse_manager` 共用这三屏但**不显示**（它已有首页 AI 入口，本轮不改它）。
- F9 → 用 stack push（`navigate('AIChat')`）进入，返回是 pop，首页不卸载，列表状态保留。

## 开关机制（熔断开关，默认 ON）

两层，任一层关掉入口就消失，**两层都默认开**：

| 层 | 在哪 | 怎么关 | 生效方式 | 作用范围 |
|---|---|---|---|---|
| ① 全局 OTA 常量 | `src/config/frontlineAIEntry.ts` 的 `FRONTLINE_AI_ENTRY_GLOBAL_ENABLED` | 改成 `false` → 合并 main → 推 OTA | OTA（无需商店发版） | 所有工厂 |
| ② 按工厂远程配置 | 既有 `feature-config`（`factoryFeatureStore.isScreenEnabled`），屏幕 ID `FrontlineAIChat`，归属模块 `smartbi` | web-admin「系统 → 功能配置 → 智能BI → 详细配置 → 禁用的屏幕」勾选「AI 助手入口 (操作员/仓库员/质检员首页)」；或整个 smartbi 模块关掉 | 下次登录 / App 重启加载功能配置时生效（无需发版、无需 OTA） | 单个工厂 |

⚠️ 第②层前提：该工厂要有 `smartbi` 这一行功能配置（`PUT /feature-config/smartbi` 对不存在的行回 400）。
没有的话先在同一页点「初始化默认配置」。没有这一行时入口是**开**的（默认开），所以这只影响「想关的时候关不关得掉」。

选择理由：
- 第②层**复用既有机制**：`feature-config` 本来就是「后台开关 → RN 隐藏屏幕」的通道，`WarehouseWorkerNavigator`
  已在用；默认语义正好是「没配置 = 开」，与 owner 的「默认开、可熔断」一致。归到 `smartbi` 是因为管理端 AI 入口
  （`AIAnalysis`）也归 `smartbi`——关了一个工厂的 AI 模块，一线入口不应比管理端更开放。
- 第①层补的是「全局一键关」：第②层是按工厂的，要全关得一家家点。
- ⚠️ 已知性质：功能配置加载失败时 `isScreenEnabled` 返回 true（既有的向后兼容行为）⇒ 第②层 **fail-open**。
  可接受的理由：入口只是 UI，服务端逐意图鉴权不受影响；需要「确定关掉」时用第①层。

## 测试与变异（2026-10-05）

| 文件 | 守什么 |
|---|---|
| `src/__tests__/integration/navigation/FrontlineAIEntryNavigation.test.tsx` | 真导航器 + 真首页：三角色默认有入口且点了到 AIChat；全局 / 本工厂 / smartbi 关 ⇒ 入口和路由都没有；运行中被关掉当场消失；yield_operator 没有 |
| `src/__tests__/integration/screens/AIChatScreenFrontlineRoles.test.tsx` | 三角色下欢迎区（F3）、澄清 / 追问确认 / 写确认卡都渲染、请求不带角色；场景过滤活着但入口路径不触发 |
| `src/__tests__/unit/config/frontlineAIEntry.test.ts` | 纯判定 + 真 factoryFeatureStore（含 fail-open 那一格） |
| `src/__tests__/unit/navigation/roleNavigatorBoundariesContract.test.ts` | 三个导航器里 AIChat 只注册一次、且只在开关后面（含正则阳性对照） |

变异（每条先证明打进去了，再看红）：M1 判定忽略两层开关 → 17 红；M2 hook 丢掉全局常量（纯函数单测看不见）→ 3 红；
M3 仓库员导航器无条件注册 AIChat → 4 红；M4 欢迎区恒显示「我可以帮您：」→ 3 红。全部用 Edit 还原，工作树干净。

## 不做的事

- 不改服务端任何权限 / 意图配置。
- 不 fork `AIChatScreen`；只改欢迎区一处文案分支。
- 不给 `yield_operator`（出成率录入员，同样落到 `OperatorNavigator`）开入口——owner 只点名了三个角色，列为决策项。
- 不 push / 不部署 / 不推 OTA。

## Owner 决策项

1. `yield_operator` 是否也给入口（同一个首页，加一个角色码即可）。
2. 一线角色的示例卡组（候选，**需先逐条核实服务端放行**）：
   操作员「我要报工」「我下一道工序是什么」；仓库员「查询原材料库存」「今天要发哪几单」；质检员「上一批拍检结果」。
3. 是否要对一线角色限制某些意图（例如禁止一线账号走 AI 写入、只读问答）——这属于服务端意图配置
   （`requiredPermission` / `requiredRoles`），不应在客户端做。
