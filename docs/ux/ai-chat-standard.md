# AI 对话界面统一标准（RN 手机端 + web-admin）

**状态**：现行标准，仓库里唯一的一份。改任何 AI 对话界面之前先读本文件。
**最后更新**：2026-10-07
**来源**：
- Steve 2026-08-29 定的标准，对标 ChatGPT / Claude 手机端。
- 餐饮手机页 `mobile-rest-ai/` 的实现与闸（`style.css`、`layout-gate.mjs`、`followup-gate.mjs`）。这个目录只读，餐饮侧不改。
- organizer 2026-10-07 在 375×812 上验收工厂 AIChatScreen（质检员账号）量出的 C1–C8。

**入口登记**：`.claude/rules/ai-chat-ui.md`（按路径加载，碰到下面的文件会自动带出本文件）。

---

## 0. 适用范围

「AI 对话界面」指：用户输入（说或打字）一句话，AI 回一条消息，形成一串消息流。

| 算 | 不算 |
|---|---|
| 工厂 `AIChatScreen` 及其 `components/ai/*` 子组件 | 单次 AI 报表页（`AIReportScreen`、`StaffAIAnalysisScreen` 等）：只受 §2.6「值与文字」和 §2.7「遥测」约束 |
| 首页布局助手 `AILayoutAssistant`（Modal） | 用户走不到的死代码（见范围清单 `docs/ux/ai-chat-scope-20261007.md`） |
| web-admin `AIQuery.vue`、`AiEntryDrawer.vue`、配置助手面板 | 餐饮侧：`mobile-rest-ai/`、RN 的 `isRestaurantMode` 分支、web-admin `Restaurant*` 组件，以及和餐饮共用、未经裁定的组件 |

---

## 1. 不许削弱的东西（一线硬约束）

这几条优先于本文件其余所有条款。冲突时以这里为准。

| # | 约束 | 判据 |
|---|---|---|
| H1 | **语音优先输入保留**。一线角色进屏的默认态是居中大麦克风，键盘是次要入口 | 进屏不点任何东西，`voice-first-container` 存在、`ai-chat-input` 不存在 |
| H2 | **写操作「预览 → 确认卡 → 执行」不削弱**。没点确认，不发 confirm 请求 | 测试里不点确认，`confirmIntent` 调用 0 次；点了恰好 1 次 |
| H3 | **确认卡的业务行一行都不能藏**（Steve 2026-10-08 裁定，手机和 web 同一条规则）。只跳过系统键（`HIDDEN_KEYS`）；中文键原样；有 `KEY_LABELS` 的英文键显示中文；**没有 label 的英文键照原样显示键名**，藏掉一行写入内容比露出一个英文键名更糟（H3 优先于 V3）。新英文键要补进 `KEY_LABELS`（RN `aiWriteConfirm.ts` 和 web `aiWriteConfirmRows.ts` 两份同步，有逐键对比的测试），⛔ 不是在渲染时藏掉；⛔ 也不许回退成只认 `KEY_LABELS`（#954 空卡） | 同一份预览数据，改后 `ai-confirm-rows` 的子节点数 ≥ 改前，且等于「去掉系统键后的键数」 |
| H4 | **确认卡、追问、澄清按钮、成功结果卡的语义不变**。版式可以改，按钮触发的调用和参数不改 | 点澄清按钮发出的请求体（`intentCode`、`clarificationSelection`、`clarificationCandidates`），改前改后逐字段相等 |
| H5 | 超时、失败的文案必须明确说**没有执行任何写操作** | 超时和失败消息的文本都含「没有执行任何写操作」 |

⚠️ 一线屏幕（操作员、仓管、质检、出成率录入员能进的屏）开工前先跑 `ux-flow` skill，PR 描述里贴 UX Flow Analysis。

---

## 2. 标准（手机端，375 宽为基准）

每条都带「判据」：要么是 computed style 数值，要么是行为断言。⛔ 只写「看起来对了」不算判据。

### 2.1 消息版式

| # | 规则 | 判据（375×812，RN 网页版） |
|---|---|---|
| L1 | **助手回答全宽**：没有头像，没有气泡框（无背景色、无阴影、无边框）。正文直接排在页面背景上 | 助手正文列宽 = 屏宽 − 32 = **343px（≥ 屏宽 91%）**；正文容器 `background-color` 透明或等于页面背景，`box-shadow: none`；DOM 里没有头像节点 |
| L2 | **只有用户消息用浅色气泡**，靠右。最宽 `min(78%, 320px)`，圆角 12，内边距左右 12、上下 8–10 | 375 宽下一条长用户消息的气泡宽 ≤ 268px（内容列 343 × 78% = 267.5，比 320 小，取它）；`border-radius` 四角都是 12px；`padding` 为 8–10px / 12px |
| L3 | 欢迎语也是助手消息：全宽、无头像、无气泡 | 同 L1 |
| L4 | **不给每条消息打时间戳**（C8） | 消息流里不出现 `HH:MM` 格式的独立时间节点。带数据的回答若后端给了「数据截至」时间，作为正文的一部分写出来 |
| L5 | 结构化结果（记录卡、统计卡、确认卡）可以有卡片边框：卡片是「数据」，不是「气泡」。宽度占满正文列 | 卡片外框宽 = 正文列宽（343px），不再有 `maxWidth: 92%` 这类收缩 |

### 2.2 字体与字号

| # | 规则 | 判据 |
|---|---|---|
| T1 | **一套系统字体栈**：`-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif`。中文由栈尾的系统中文字体接住。⛔ 不写死 `Noto Sans SC`、`Noto Serif SC`、`Playfair Display` | 对话区内所有文字节点的 computed `font-family` 相同（C6：现在用户气泡、正文、时间戳是三套） |
| T2 | 正文 **16px**，行高 1.5–1.6（24–25.6px），字距 0 | 助手正文、用户气泡文字、澄清选项标签、追问芯片标签：`font-size: 16px`，`line-height` 24–25.6px，`letter-spacing: 0`（或 normal） |
| T3 | 小字（选项说明、表格标签、页脚）**12–13px**；⛔ 不出现 10–11px | 对话区内 computed `font-size` 的最小值 ≥ 12px |
| T4 | 标题 18–20px | 对话内出现的标题节点在此区间 |

**RN 实现要点**：Paper 的 MD3 主题在 web 上把字体写死成 `Roboto, "Helvetica Neue", Helvetica, Arial`（`node_modules/react-native-paper/src/styles/themes/v3/tokens.tsx:88-92`），`variant=bodySmall` 是 12/16，`bodyMedium` 是 14/20。所以：
- 对话面用一个嵌套的 Paper 主题，只在 web 上把 `fonts` 换成上面的栈；iOS / Android 保持 Paper 默认（System / sans-serif，本来就是系统字体）。⛔ 不改全局 `src/theme`，那会波及整个 App。
- 正文不用 `bodyMedium`（14px），用 `bodyLarge`（16/24）或显式 `fontSize: 16, lineHeight: 24`。
- jest 里 Paper 是手写替身，`fonts = {}`，variant 的字号测不出来。字号断言打在**显式 style** 上，computed style 在网页版上量。

**web-admin 实现要点**：全局 `--font-family` 以 `'Noto Sans SC'` 打头（`web-admin/src/style.css:92-97`）。对话容器自己声明 T1 的字体栈，⛔ 不改全局变量（改全局是另一个决定，要 Steve 拍板）。

### 2.3 间距与尺寸

| # | 规则 | 判据 |
|---|---|---|
| S1 | 8px 基准。屏幕左右 16px，消息之间 8px，组件之间 16–24px | 消息流容器左右 `padding` 16px；相邻两条消息的间距 8–16px（同一轮用户→助手 8px，轮与轮之间可到 16–24px） |
| S2 | 按钮 44–48px 高 | 重试、确认、取消、澄清选项、发送、键盘切换：`height` ≥ 44px |
| S3 | **芯片至少 40px 高**（一线单手点，比通用的 28–32px 大） | 追问芯片 `height` ≥ 40px，`max-width` 220px，标签单行省略 |
| S4 | **输入框** 40–44px 高（单行时），可随内容长高到约 120px，圆角 **8**，内边距 12，留出底部安全区 | 键盘态输入框外框 `height` 40–44px（C7：现在 66px）、`border-radius: 8px`（现在 20px）；输入 5 行文字后高度 ≤ 120px；外框底边在安全区之上 |
| S5 | 语音优先态的大麦克风不受 S4 约束（它是 72px 的语音按钮，不是输入框） | — |

### 2.4 加载、超时、失败

| # | 规则 | 判据 |
|---|---|---|
| W1 | 加载用**骨架屏**：2–3 行闪烁条，周期约 1.5s，**一律配一行文字**（「正在理解你的话…」，3 秒后换「正在处理」）。⛔ 不用单个转圈。配文字是为了回应 ux-flow 的「一线员工看不懂骨架屏」，Steve 2026-10-08 同意这个折中 | 发出后 100ms 内出现 `ai-chat-loading-skeleton`，里面 2–3 个条；动画周期 1.2–1.8s；开了「减少动态效果」时条静止 |
| W2 | 客户端超时（现 30s）后说人话，并且说没执行写操作（H5） | 超时消息文本含「没有执行任何写操作」；⛔ 不显示 `error.message` 原文、HTTP 状态码、英文 |
| W3 | 失败是独立状态：错误横幅 + 「再试一次」按钮（≥44px） | `ai-chat-error-banner` 存在、`ai-chat-retry` 高 ≥ 44px；错误消息**不出**反馈栏 |

### 2.5 推荐、追问、澄清、反馈

| # | 规则 | 判据 |
|---|---|---|
| F1 | 推荐问题（快捷卡）**只在空对话出现** | 发出第一句后，快捷卡节点数 = 0 |
| F2 | 每个完成的回答下面放「接下来可以问」芯片：label 短，点了发完整问题。⛔ 不写死假芯片：只渲染后端明确给的 `followUps` / `nextQuestions`（每项有 `label` + `question`） | 后端不给 `followUps` 时芯片数 = 0（阴性对照）；给 2 个时 = 2，点第 1 个发出的是它的 `question`，气泡显示它的 `label` |
| F3 | **澄清选项一个样式**（C5）：竖排、占满正文列宽、每个 ≥ 48px 高、标签 16px。说明文字要么每个都有、要么都没有，⛔ 不许第一个是带说明的大卡、其余是小按钮 | 所有 `suggested-action-N` 的 computed 宽度相等（= 正文列宽），高度差 ≤ 说明文字一行的高度 |
| F4 | **只有完成的回答才有 👍/👎**。澄清、追问（NEED_MORE_INFO）、写确认卡、失败、流式未完成，都**不出**反馈（C4） | 「完成的回答」= 不在加载、没有 error、没有 `writeConfirm`、没有 `suggestedActions`、状态不是 `NEED_CLARIFICATION` / `NEED_MORE_INFO` / `CONVERSATION_CONTINUE`。澄清消息上反馈节点数 = 0，普通回答上 = 1 |
| F5 | 反馈栏没有贯穿横线（C3：`borderTopWidth: 1` 没给颜色，在 web 上画成黑线）。要分隔就用 `outlineVariant` 色的细线，宽度不超出正文列 | 反馈栏 `border-top-color` ≠ `rgb(0, 0, 0)`；或者 `border-top-width: 0` |
| F6 | 反馈按钮触摸区 ≥ 44px | 👍、👎 节点 `height` ≥ 44px |

### 2.6 值与文字

| # | 规则 | 判据 |
|---|---|---|
| V1 | 算不出的值用文字写状态（如「暂无数据」「缺成本卡」），⛔ 不显示 0、空、`-`、`—` | 喂 `null` 的字段，渲染文本不是 `0` / 空串 / `-` / `—` |
| V2 | ⛔ 不显示内部编号、UUID | 渲染文本不匹配 `/[0-9a-f]{8}-[0-9a-f]{4}-/i` |
| V3 | ⛔ 不显示英文字段名、英文状态码（`IN_PROGRESS`、`Batch Number`） | 未知英文键整行不显示（不是回落成驼峰拆分）；渲染文本不含 `/\b[A-Z][A-Z_]{3,}\b/` |

### 2.7 遥测

| # | 规则 | 判据 |
|---|---|---|
| E1 | 来源、耗时、token、模型名、置信度百分比、意图码、「快速响应」徽标，都**不给业务用户看**（C2） | 默认渲染里没有 `ai-telemetry-row`，文本不含 `快速响应`、`ms`、`置信度`、`token` |
| E2 | 开发者要看就用**显式开关**（RN：`EXPO_PUBLIC_AI_DEBUG_TELEMETRY=1`；web-admin：`?aiDebug=1`）。⛔ **不用 `__DEV__` 当开关**：RN 网页版（3010 / 3011 / 验收用的那一个）是 `expo start` 开发服务器，`__DEV__` 恒为 true，C2 就是这么漏给验收的 | 不设开关时，`__DEV__ = true` 下 `ai-telemetry-row` 也不存在 |

### 2.8 滚动、复制、新对话

| # | 规则 | 判据 |
|---|---|---|
| N1 | 只有用户本来就在底部附近（120px 内）才自动滚到底 | 往上翻 300px 后来新内容，`scrollTop` 不变 |
| N2 | 长按复制（web-admin 上用悬停出现的「复制」按钮） | 长按用户 / 助手消息，出现复制菜单；⛔ 不用 `Alert.alert`（Expo Web 上是空操作），用 `appAlert` |
| N3 | 顶栏有「新对话」：清空消息、取消进行中的请求。有未确认的写确认卡时先问一句；有正在执行的写操作时不许清 | `ai-chat-new-conversation` 存在；有 ready 卡时点它弹确认；有 executing 卡时不清空 |

### 2.9 表格

| # | 规则 | 判据 |
|---|---|---|
| G1 | 手机宽度下表格所有列都看得见，不横向滚动；宽表改成「一条记录一张卡」或拆行 | 375 宽下列表结果容器 `scrollWidth` ≤ `clientWidth`；没有横向 ScrollView |
| G2 | 单元格只放值，说明放到表下 | — |

---

## 3. web-admin 与手机端的差异

web-admin 是桌面宽屏（验收按 1440 宽）。原则：**语义规则全部照搬，尺寸规则按载体调整。**

| 项 | 照搬 | 调整为 |
|---|---|---|
| 助手全宽、无头像、无气泡（L1） | ✅ | 全页对话（`AIQuery.vue`）的消息列 `max-width: 768px` 居中，不铺满 1440 |
| 用户气泡（L2） | 圆角 12、浅色、靠右 | 最宽 `min(70%, 560px)` |
| 字体栈、16px 正文、行高 1.5–1.6（T1–T3） | ✅ | — |
| 按钮、芯片高度（S2、S3） | — | 鼠标操作：按钮 ≥ 32px、芯片 ≥ 32px（Element Plus `default` 尺寸即可） |
| 输入框（S4） | 圆角 8、内边距 12、可扩展 | 单行高 40–44px，最高 200px |
| 加载、超时、失败（W1–W3）、追问 / 澄清 / 反馈（F1–F6）、值与文字（V1–V3）、遥测（E1–E2）、条件滚动（N1）、新对话（N3） | ✅ | — |
| 复制（N2） | — | 悬停时出现「复制」按钮，不用长按 |
| 表格（G1） | 不出现整页横向滚动 | 全页对话里 ≤ 8 列用 `el-table` 全部列可见；> 8 列拆卡。**侧栏、抽屉（宽度 ≤ 420px）按手机规则**：记录卡，不用 `el-table` |
| 窄面板（侧栏 380px、配置助手 300px、抽屉） | 版式按手机规则（L1、L2、G1） | 尺寸按桌面规则（按钮、芯片 ≥ 32px） |

⚠️ web-admin 的 `chat/ChatBubble.vue`、`ChatTypingIndicator.vue` 和餐饮 `RestaurantChatPanel` 共用。改它们只能走「新增可选 prop，默认值保持旧样子」，并用 `RestaurantChatPanel` 的渲染结果做阴性对照（改前改后一致）。

---

## 4. 验收：每个 PR 要交的东西

1. **一线屏幕**：PR 描述里有 `ux-flow` 产出的 UX Flow Analysis。
2. **测试**（RN 用 jest + @testing-library/react-native 真实挂载；web-admin 用 vitest）：
   - 每条改动配阳性和阴性对照。
   - 必带的阴性对照：确认卡改前改后行数相同（H3）；澄清和追问不出反馈（F4）；后端不给 `followUps` 时没有芯片（F2）；默认不显示遥测，`__DEV__=true` 下也不显示（E2）。
   - 每条至少一个变异：先证明变异生效（打印一个可观测量），再看测试变红；还原用 Edit，不用 `git checkout`。
3. **截图**：375×812（RN 网页版）或 1440 宽（web-admin），改前、改后各一张，存 `D:/Temp/claude/aiui-shots/<PR名>/`。
4. **computed style 表**：用下面的脚本读数，对照本文件逐项打勾。
5. **基线**：在 origin/main 上当场重跑相关测试，和分支并排报读数；`npx tsc --noEmit` 不新增错误；web-admin 跑 `npm run lint` 和相关 vitest。

### 4.1 量 computed style 的脚本（RN 网页版，`testID` 在 DOM 上是 `data-testid`）

```js
(() => {
  const vw = innerWidth, q = (id) => document.querySelector(`[data-testid="${id}"]`);
  const cs = (el) => el && getComputedStyle(el);
  const body = q('ai-chat-assistant-body'), user = q('ai-chat-user-bubble');
  const input = q('ai-chat-input-frame'), btns = [...document.querySelectorAll('[data-testid^="suggested-action-"]')];
  const textNodes = [...document.querySelectorAll('[data-testid="ai-chat-thread"] *')]
    .filter((e) => e.childNodes.length && [...e.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
  const fonts = new Set(textNodes.map((e) => cs(e).fontFamily));
  const sizes = textNodes.map((e) => parseFloat(cs(e).fontSize));
  return {
    viewport: vw,
    bodyFontSize: cs(body)?.fontSize, bodyLineHeight: cs(body)?.lineHeight,
    bodyColumnPx: body?.getBoundingClientRect().width,
    bodyColumnRatio: body && +(body.getBoundingClientRect().width / vw).toFixed(3),
    bodyBackground: cs(body)?.backgroundColor, bodyShadow: cs(body)?.boxShadow,
    userBubbleMaxPx: user?.getBoundingClientRect().width, userBubbleRadius: cs(user)?.borderRadius,
    fontFamilies: [...fonts], minFontSize: Math.min(...sizes),
    clarifyHeights: btns.map((b) => b.getBoundingClientRect().height),
    clarifyWidths: btns.map((b) => b.getBoundingClientRect().width),
    inputHeight: input?.getBoundingClientRect().height, inputRadius: cs(input)?.borderRadius,
    telemetryNodes: document.querySelectorAll('[data-testid="ai-telemetry-row"]').length,
  };
})()
```

脚本依赖的 `testID`（实现时要加上，命名固定）：`ai-chat-thread`（消息流容器）、`ai-chat-assistant-body`（助手正文列）、`ai-chat-user-bubble`、`ai-chat-input-frame`（输入框外框）、`suggested-action-N`、`ai-chat-followup-N`、`ai-chat-feedback`、`ai-chat-loading-skeleton`、`ai-confirm-rows`。

---

## 5. C1–C8 与本标准的对应

| organizer 实测 | 条款 |
|---|---|
| C1 助手带 36px 圆形头像和白色气泡，正文列只有 228px（61%） | L1（目标 343px、91%） |
| C2「⚡快速响应 0.0s」遥测徽标，10px | E1、E2、T3 |
| C3 反馈栏上方一条贯穿的黑线 | F5 |
| C4 澄清回答下面也有「这个回答有帮助吗」 | F4 |
| C5 澄清按钮大小不一 | F3 |
| C6 字体混用三套 | T1 |
| C7 输入框圆角 20、整体高 66px | S4 |
| C8 每条消息都有时间戳 | L4 |
