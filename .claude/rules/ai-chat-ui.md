---
paths:
  - "frontend/CretasFoodTrace/src/screens/factory-admin/ai-analysis/**"
  - "frontend/CretasFoodTrace/src/components/ai/**"
  - "frontend/CretasFoodTrace/src/screens/factory-admin/home/components/AILayoutAssistant.tsx"
  - "web-admin/src/views/smart-bi/**"
  - "web-admin/src/components/ai-entry/**"
  - "web-admin/src/views/platform/canvas-editor/**"
---

# AI 对话界面：改之前先读标准

**最后更新**: 2026-10-08

标准只有一份：`docs/ux/ai-chat-standard.md`（对标 ChatGPT / Claude 手机端，Steve 2026-08-29 定）。
范围和进度：`docs/ux/ai-chat-scope-20261007.md`。

动手前必须知道的五件事（细节和判据在标准里）：

1. **助手回答全宽**，没有头像、没有气泡。只有用户消息用浅色气泡。
2. **一线硬约束不许削弱**：语音优先输入；写操作「预览 → 确认卡 → 执行」；确认卡的业务行一行都不藏（只跳过系统键，没有中文名的英文键照原样显示；RN `aiWriteConfirm.ts` 与 web `aiWriteConfirmRows.ts` 同一条规则，Steve 2026-10-08）。
3. **遥测不给业务用户看**，⛔ 不用 `__DEV__` 当开关。RN 网页版是 `expo start` 开发服务器，`__DEV__` 恒为 true。
4. **只有完成的回答有 👍/👎**。澄清、追问、确认卡、失败都不出。
5. **餐饮侧不碰**：`mobile-rest-ai/`、RN `isRestaurantMode` 分支、web-admin `Restaurant*`，以及和餐饮共用的 `chat/ChatBubble.vue`、`ChatTypingIndicator.vue`（要改只能加可选 prop，默认值保持旧样子）。

验收交付：截图（375×812 或 1440 宽，改前、改后各一张）、computed style 读数表（脚本在标准 §4.1）、带阴性对照和变异的测试。
