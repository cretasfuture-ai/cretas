# RN AI 对话屏：写操作确认卡（2026-10-04）

## 背景

`AIChatScreen`（`frontend/CretasFoodTrace/src/screens/factory-admin/ai-analysis/AIChatScreen.tsx`）
通过 SSE 走 `/ai-intents/execute/stream`。后端 W0 写闸对一切写工具 / 写意图返回
`status = WRITE_CONFIRM_REQUIRED`（`ToolDispatchService.java:195-210` SITE B、
`IntentExecutionOrchestrator.java:1060-1076` SITE A），RN 只把那句
「该操作会写入/修改数据，执行前需要确认。」当普通文字显示 —— **没有任何按钮能往下走**，
所以对话里的报工、入库、领料、审批全部是死胡同。

web-admin 早已打通同一条链路（`web-admin/src/views/smart-bi/AIQuery.vue:1276-1310`
+ `components/chat/AiOperationCard.vue`），本 spec 把 RN 对齐过去。

## 后端契约（照抄 web-admin 的调用序列）

1. 流式结果 `status = WRITE_CONFIRM_REQUIRED`（带 `intentCode`，SITE A 还带 `intentName`）。
2. 前端立刻用**同一句原话 + 被拦的 `intentCode` + `sessionId`** 请求预览。
   RN 走 `POST /ai-intents/preview`（`AIIntentConfigController.java:753-771`，服务端强制
   `previewOnly=true`，比客户端自己带 flag 更硬）。
3. 预览返回 `status = PREVIEW` + `confirmableAction`（`ToolDispatchService.java:812-859`）：
   `confirmToken / commandDigest / expiresAt / expiresInSeconds(300) / description /
   previewData / currentValues / newValues / impactSummary / actionType / riskLevel`。
   ⚠️ `previewData` 是工具预览 JSON：`{success:true, data:{...}}`，或失败形状
   `{success:false, error|message}`；业务边界拦截在 `data.canDo === false` + `data.message`。
   **失败/拦截的预览后端照样铸 token** —— 前端必须自己不给「确认执行」按钮。
4. 确认：`POST /ai-intents/confirm`，token 只走请求头 `X-Cretas-Confirmation-Token`，
   body **恰好四个字段** `{commandDigest, expiresAt, requestId, idempotencyKey}`
   （`IntentConfirmationRequest.java`，多一个字段即拒绝；`requestId/idempotencyKey`
   8–128 位客户端生成）。`IntentExecutionOrchestrator.confirm`（`:1158-1332`）原子认领 token，
   成功判据是内层 `status ∈ {SUCCESS, COMPLETED}`；认领失败回 `FAILED + 认领原因`
   （`PreviewTokenServiceImpl.java:168-270`）。HTTP 一律 200，失败时 envelope
   `success=false` 但 `data` 照带（`envelopeByInnerStatus`，`AIIntentConfigController.java:324-344`）。

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像
车间操作员 / 仓管员 / 质检员 / 生产主管 —— 技术素养低、常戴手套、主要用语音
（本屏默认是大号麦克风）—— 在产线边说「3 号任务做完了，实际 60 箱」「收货 PO-0123 第 2 行 50 公斤」，
希望 AI 直接帮他记上，而不是跳去找对应的表单页。

### 用户旅程
| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | 自己说的话 + AI 气泡「该操作会写入/修改数据，执行前需要确认。」 | 无 | 知道这是一个要改数据的操作，不是查询 |
| 2 | 气泡下方确认卡：「「完成工序」需要你确认」+ 转圈「正在生成操作预览…」 | 等待（通常 1–3 秒） | 不需要再说一遍 |
| 3 | 卡片：操作名、将要写入的关键内容（品名/单号/数量）、影响说明、「N 秒内有效」、大按钮「确认执行」+ 次按钮「取消」 | 目视核对后点「确认执行」 | 只执行一次 |
| 3′ | 预览被业务边界拦住（如超收）：卡片显示后端原话「⛔ 实收 60 箱超过…最多 50 箱」，**没有确认按钮** | 换个数量重新说 | 犯错前就被拦住 |
| 4 | 按钮变成转圈「正在执行…」且两个按钮都禁用 | 无（连点无效） | 不重复提交 |
| 5 | 卡片收起为「已执行」，下方新 AI 气泡显示执行结果原话 | 继续对话 | 有明确结果 |
| 4′ | 取消：卡片收起为「已取消」，AI 气泡「已取消，没有执行任何操作」 | 无 | 确定什么都没发生 |
| 4″ | 过期 / 已被处理：卡片红字说明 + 「重新生成预览」按钮 | 点「重新生成预览」→ 回到步骤 2 | 不用重说一遍 |

### 摩擦点清单
| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | 现状：确认提示是纯文字，没有任何下一步，用户只能放弃 | HIGH | fool-proof Rule 5（Dead-end 改导航） |
| F2 | 预览数据是工具 JSON（英文键、嵌套 `data`），直接平铺老员工看不懂 | HIGH | fool-proof Rule 2 / 内联「信息密度」 |
| F3 | 后端对被拦截 / 失败的预览照样发 token，若照样给确认按钮 = 让用户去点一个必然失败或不该做的操作 | HIGH | fool-proof Rule 1（预先显示边界） |
| F4 | 手套 + 网络慢 → 连点「确认执行」，可能重复报工 / 重复入库 | HIGH | fool-proof Rule 4（幂等防重复） |
| F5 | token 5 分钟过期，用户去拿东西回来再点会失败；失败文案若是「Confirmation proof is invalid」等英文/技术话 | MED | 内联「错误恢复」+ 4 位一体 a/b/d |
| F6 | 确认请求网络中断：服务端可能已执行，盲目重试会双写 | HIGH | fool-proof Rule 4 + 禁止降级处理 |
| F7 | 无权限的人点了确认，只看到「失败」 | MED | fool-proof Rule 5（权限不足给出路） |
| F8 | 部分写工具（如工序任务开始/完成 = 报工）后端尚未实现安全预览，预览返回「预览模式暂不支持此意图类型」 | HIGH | fool-proof Rule 5 |
| F9 | 按钮太小 / 两个按钮挨太近 | MED | 内联「触摸目标 ≥44pt、间距 ≥8px」 |

### 每个摩擦点的设计回应
- F1 → 收到 `WRITE_CONFIRM_REQUIRED` 自动请求预览并渲染确认卡；全程不需要用户再说一遍。
- F2 → 只展示 `previewData.data`（剥掉 `success/data` 外壳），跳过系统键
  （factoryId/userId/role/intentCode/userInput/status/canDo/message 等），已确认存在的业务键用中文标签
  （单号/供应商/物料/规格/单位/订购/已收/未收/还可入/本次数量…，取自 `ReceiveWithLimitTool.doPreview`），
  其余键原样显示；有 `currentValues/newValues` 时改为「当前 → 修改后」对比行。
- F3 → `previewData.success === false` 或 `data.canDo === false` ⇒ 卡片显示后端原话、**不渲染**「确认执行」。
- F4 → 同步 ref 锁（与本屏 `sendPendingRef` 同一模式）+ 进行中禁用两个按钮 + 后端原子认领三层兜底。
- F5 → 卡片显示「N 秒内有效」倒计时；到 0 禁用确认并给「重新生成预览」；后端认领失败文案原样显示 +「重新生成预览」。
- F6 → 网络错误不自动重试，明确告知「无法确认这次操作是否已执行，请先到对应页面查看结果」，
  且不提供一键重试（避免双写），用户核实后可重新说一遍。
- F7 → `PERMISSION_DENIED / NO_PERMISSION / HTTP 403` ⇒「你的账号没有执行「X」的权限，请联系工厂管理员开通」+ 后端原话。
- F8 → 预览失败时原样显示后端原因 +「这个操作暂时不能在对话里确认执行，请到对应功能页面手工操作。」；
  后端补预览能力列为待办（见下）。
- F9 → 两个按钮 `minHeight: 48`、间距 12、主按钮唯一（实心主色），取消为描边次按钮；用 `TouchableRipple`。

## 不做 / 待决

- **二次确认弹窗**：web-admin 在卡片按钮之后再弹一次 `ElMessageBox`。RN 不做：
  卡片本身就是确认步骤，且 `Alert.alert` 在 Expo Web 上是 no-op。高风险（DELETE/HIGH/CRITICAL）
  改为卡片内红色影响说明 + 主按钮文案「我已了解影响，确认执行」。是否要求 RN 也双重确认待 owner 裁定。
- **工序任务开始/完成（报工）的后端预览**：`WorkProcessTaskStartTool` / `WorkProcessTaskCompleteTool`
  没有覆盖 `supportsPreview()`（默认 false），因此对话报工在后端就止于「预览模式暂不支持」。
  本次前端会如实显示；要真正打通需后端补安全预览（只读 `doPreview`）。
