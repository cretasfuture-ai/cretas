---
name: ux-flow
description: 低技术素养用户屏幕的 UX Flow Gate。设计或改动 React Native 里 operator/操作员/仓管/warehouse_worker/quality_inspector/质检员 使用的屏幕（screens/processing、screens/warehouse、screens/quality-inspector），或报工/入库/出库/盘点/质检/扫码收货/发货 功能时，在提出方案前先用本 skill 产出「UX Flow Analysis」章节。
---

# UX Flow Gate Skill

低技术素养用户屏幕的两阶段 UX 关卡适配器。

触发信号见 description（角色、路径、功能词任一命中）。命中时，在提出设计方案之前先完成 Phase 1。

## Phase 1：设计门控

### 执行顺序

1. 读取 `.claude/rules/fool-proof-design.md`，对照 5 条规则逐一检查
2. 应用内联 UX 规则（见下方「内联 UX 规则」）对屏幕功能逐项评估
3. 产出 spec 章节（格式见下方「spec 章节格式」）
4. 门控检查：进入实现之前，spec 必须包含完整的「UX Flow Analysis」章节

### 内联 UX 规则（低技术素养用户 RN 屏幕）

**触摸目标**
- 所有可点击元素 ≥ 44×44pt（Paper `TouchableRipple` / `Button` 默认满足，自定义 View 需显式设 minHeight/minWidth）
- 相邻可点击元素间距 ≥ 8px，防误触
- 点击后 80-150ms 内有视觉反馈（ripple / opacity）

**信息密度**
- 每屏只暴露当前步骤需要的信息，不堆叠下一步的字段
- 主操作按钮唯一，副操作降级为文本链接或 icon
- 数字输入用大字号（≥ 24px），避免仓管员看错

**错误恢复**
- 错误文案用「发生了什么 + 怎么解决」双句式，不要纯技术 message（"500 Internal Server Error"）
- 错误后保留已填数据，不清空表单
- 网络失败显示重试按钮，不显示空白屏

**扫码 / 批次选择**
- 扫码结果立即回显品名 + 数量，操作员目视确认后才允许提交
- 多批次选择屏每卡带「产品名 + 批次号 + 当前工序」（fool-proof Rule 2）
- 单批次自动跳转，不让用户多点一次

**Expo / React Native Paper 具体约束**
- 用 `TouchableRipple` 替代 `TouchableOpacity`（Material ripple 反馈更明显）
- 数量输入用 `keyboardType="numeric"` + `TextInput` Paper 组件
- 底部安全区用 `ScreenWrapper` / `SafeAreaView` edges={['bottom']}
- 加载中用 `ActivityIndicator`，不用 skeleton（仓管员不熟悉 skeleton 语义）

### spec 章节格式

spec 里要有下面这一章，缺了先补齐再实现（低技术素养用户点错的代价高，这一步是为了在写码前把摩擦点找出来）：

~~~markdown
## UX Flow Analysis

### 用户画像
[角色名称] — [技术素养描述] — [典型使用场景 1-2 句]

### 用户旅程
| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | ... | ... | ... |

### 摩擦点清单
| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | ... | HIGH/MED/LOW | fool-proof Rule N / 内联 UX 规则 |

### 每个摩擦点的设计回应
- F1 → [具体设计决策]
- F2 → [具体设计决策]
~~~

## Phase 2：实现 Advisory（由 PostToolUse hook 触发）

开发者编辑 P1 路径下的 `.tsx` 文件时，`.claude/hooks/ux-advisory.js` 自动运行，输出以下检查清单（advisory，不阻断）：

**P1 路径**: `screens/processing/`、`screens/warehouse/`、`screens/quality-inspector/`

检查清单内容（清单由 hook 脚本打印，不需要 skill 重复输出）：
- 触摸目标 ≥ 44×44pt，间距 ≥ 8px，点击反馈 80-150ms
- fool-proof Rule 1：边界防呆（max 限制 + disabled 按钮）
- fool-proof Rule 2：上下文显示（产品名 + 批次号/单据号）
- fool-proof Rule 5：Dead-end 改导航（错误/空状态有 next action）
