# AI 对话界面改造范围清单（2026-10-07）

**标准**：`docs/ux/ai-chat-standard.md`（条款编号 L / T / S / W / F / V / E / N / G / H 都指它）。
**怎么来的**：一次只读侦察（违反项都有 file:line），我抽查了 AIChatScreen、FeedbackWidget、RichContentRenderer、AIWriteConfirmCard 和主题字体。下文「【推断】」表示没在代码里坐实。

## 结论

- 要改 **5 项（6 个 PR）**：RN 的 AIChatScreen（2 个 PR）、RN 的 AILayoutAssistant、web-admin 的 AIQuery、AiEntryDrawer、配置助手面板。
- **需要裁定 2 项**：web-admin 的 `AnalysisChatPanel` 和 `ChatBubble` / `ChatTypingIndicator`，它们和餐饮共用。
- **不改**：其余是报表页或死代码。

## 一、要改

| # | 界面 / 文件 | 谁在用 | 违反（条款） | 谁改 | PR / 分支 | 优先级 |
|---|---|---|---|---|---|---|
| R1 | RN `screens/factory-admin/ai-analysis/AIChatScreen.tsx`（1809 行）的版式部分，加上 `components/ai/FeedbackWidget.tsx`、`RichContentRenderer.tsx`、`AIWriteConfirmCard.tsx` 的版式 | 工厂管理员、调度、车间主任、仓库主管、HR，以及一线的操作员、仓管、质检（按一线开关）。注册位置：FAAIStack:56、Operator:105、WarehouseWorker:29、QualityInspector:115 等 | C1 / L1 头像和气泡（490-497、1320、1651）；C2 / E2 遥测靠 `__DEV__` 开关（1310、1527）；C3 / F5 黑线（FeedbackWidget:296）；C6 / T1 字体；C7 / S4 输入框圆角 20、高 66（1718、1723）；C8 / L4 时间戳（1300、1440）；W1 先转圈，3 秒后才出骨架（1284、1288）；L5 确认卡 `maxWidth: 92%`；欢迎语 14px（1461） | **Sonnet**（规格写死，jest 抓得到） | `codex/claude-aiui-factory-chat-layout` | P0 |
| R2 | 同一个 AIChatScreen 的语义部分：澄清选项统一样式、反馈出现条件 | 同上 | C4 / F4 澄清也出反馈（1425-1429 没有排除 `suggestedActions`）；C5 / F3 澄清按钮大小不一（1380-1403） | **我（Opus）**：要定「什么算完成的回答」，而且挨着写确认流程 | 叠在 R1 的分支上，在同一个 PR 里，排在 R1 之后提交 | P0 |
| R3 | RN `factory-admin/home/components/AILayoutAssistant.tsx`（1152 行，Modal） | 工厂管理员。入口：HomeLayoutEditorScreen:460 | L1 头像和气泡（579-586、959）；L2 75%（971）；T2 / T3 14px、12px、11px（974-1075）；S2 建议按钮没设 minHeight（1033）；S4 圆角 20（1124）；W1 / W2 只有转圈，没有超时（556）；N1 无条件 `scrollToEnd`（356）；N2 没有复制；N3 没有新对话 | **Sonnet** | `codex/claude-aiui-layout-assistant` | P1 |
| W1 | web-admin `views/smart-bi/AIQuery.vue`（3978 行，全页标签页）+ `chat/AiOperationCard.vue` | 老板、管理员。路由：smartbi.ts:62-69 | L1 头像和气泡（2673、3289）；L2 70%、圆角 0 角；T2 14px、行高 1.8（3344）；T3 11px（3223、3972）；W1 转圈；W2 超时文案没说「没执行写操作」（2691）；E1 置信度、毫秒、意图码、原始 SQL（2843-2856）；V3 表头是字段名（2871）、AiOperationCard 显示原始键（235、242）；G1 每列 min-width 100，会横滚（2867）；L4 时间戳（2680） | **Sonnet**，遥测怎么藏由我先定（定为：默认藏，`?aiDebug=1` 才显示） | `codex/claude-aiui-web-aiquery` | P1 |
| W2 | web-admin `components/ai-entry/AiEntryDrawer.vue`（1194 行，抽屉宽 45%） | 6 个列表页：采购订单、库存、生产计划、生产批次、销售订单、产品 | L1 头像和气泡（981、1001）；L2 80%；T2 14px；T3 11px（568、1189） | **Sonnet** | `codex/claude-aiui-web-entry-drawer` | P2 |
| W3 | web-admin 配置助手：`platform/canvas-editor/components/AIChatPanel.vue`（140 行，300px 侧栏），以及 `WorkProcessAIChatPanel.vue` | 平台 / 超级管理员（router/index.ts:2367-2375）；工序配置 | T3 13px、12px；E1 英文模式名 Autopilot / Plan / Action、`diff.type` 原样输出；W2 没有超时；N1 无条件滚动。⚠️ `WorkProcessAIChatPanel` 有两份（system/components 560 行、system/work-processes 315 行），先确认哪份在用 | **Sonnet** | `codex/claude-aiui-web-config-assistants` | P3（内部配置工具，用户少） |

## 二、需要裁定（裁定前不动）

| # | 文件 | 问题 | 我的建议 |
|---|---|---|---|
| Q1 | web-admin `smart-bi/components/analysis/AnalysisChatPanel.vue`（394 行，右侧栏 380px） | 它调用的是 `askRestaurantIntent`（149 行），说明是餐饮链路；但挂载它的页面里有工厂侧的（BusinessAnalysisHub、CRM 会员分析、运营分析）。算餐饮还是工厂？ | 先当餐饮处理、不改；如果裁定为工厂，按 W2 的做法改，排 P2 |
| Q2 | web-admin `chat/ChatBubble.vue`、`ChatTypingIndicator.vue` | 和餐饮 `RestaurantChatPanel` 共用。写死了 `Noto Serif SC` 和 `Playfair Display`（95、110、134、163） | 只在 Q1 裁定为工厂时才动：新增可选 prop、默认值保持旧样子，用餐饮渲染结果当阴性对照 |

## 三、不改（附理由）

| 文件 | 理由 |
|---|---|
| RN `processing/AIConversationHistoryScreen.tsx` | 用户走不到：全仓没有 navigate 到它，阳性对照的 grep 能命中别的路由。另外它会显示 token 用量和 sessionId，如果以后接上入口要先过 E1、V2 |
| RN `smartbi/NLQueryScreen.tsx`、`components/voice/VoiceAssistantOverlay.tsx`、`VoiceChatBubble.tsx` | 死代码：路由 `NLQuery` 实际挂的是 AIChatScreen，Voice 两个除 re-export 外没有任何 JSX 引用。建议另开任务删掉 |
| web-admin `chat/SectionCardRenderer.vue` | 孤儿组件，0 处 import |
| RN `processing/AIAnalysisScreen.tsx` | 报表页，带一个追问框。只有 E1（显示响应毫秒，425-427）和 T3（11px，755）两处，建议顺手修，不单开 PR |
| RN `AIAnalysisCenterScreen`、`AIReportScreen`、`StaffAIAnalysisScreen`、`dispatcher/ai/AIScheduleScreen` | 报表页或入口页，不是对话。AIAnalysisCenter 只是跳转到 AIChat |
| web-admin `smart-bi/analysis/AIInsightsStream.vue` | 报表组件，已经在用骨架屏 |
| `ApprovalWorkflowAIComposer`（245 行） | 这次没审，留到 W3 一并看 |

## 四、合并顺序与 Codex 的交叉

- Codex 同期在后端做三件事：工厂追问 `followUps` 生成、「登记不良」卡带意图码、REPORT_TRENDS 同比环比。它可能对 `AIChatScreen.tsx` 和 `components/ai/*` 做最小的字段映射。
- **R1+R2 和 Codex 的前端映射改的是同一批文件。** 建议 Codex 的映射先合，R1+R2 在它之上 rebase：我的改动是版式，好让路。如果 R1+R2 先合，Codex 那边只会在 `onResult` 的解析段（846-907 行附近）冲突，版式段不会。
- 前端的 `followUps` 解析和芯片渲染已经在 main 上（#954，860-870、1405-1423 行）。Codex 只要让后端吐出 `followUps: [{label, question}]`，芯片就会出现，前端不需要再改。
- R3、W1、W2、W3 彼此不碰同一个文件，可以并行，没有先后顺序。

## 五、文中提到但没处理的发现

- web-admin 全局 `--font-family` 以 `'Noto Sans SC'` 打头（style.css:92-97），`App.vue:24` 又写了一条系统字体栈的 body 规则，两条哪条生效取决于注入顺序【推断】。这是全站字体的决定，不在本线范围。本线只在对话容器内声明字体栈。
- 现在验收用的 RN 网页版是开发服务器，`__DEV__` 恒为 true。所以凡是靠 `__DEV__` 藏起来的东西，在验收截图里都看得见，在真机 OTA 包里看不见。读验收截图时要记住这一点（C2 就是这么来的）。
