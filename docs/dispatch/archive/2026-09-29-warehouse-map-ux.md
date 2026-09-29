# UX-WAREHOUSE-MAP-FALLBACK-20260929

- Owner: Codex; Base SHA: `abbe61cc7197585c62c687b669c331d1a05026c2`.
- Scope: `web-admin/src/views/warehouse/storage-locations/*`.
- 管理端：AI 识别失败时保留底图预览，用户可明确选择只保存底图；成功预览也可只保存底图。无确认不写配置。
- 管理端：货架中心明确落入其他区域时高亮并提示核对；重叠区域、未画区域不猜测归属，也不自动修改现有仓库数据。
- 验证：目标 Vitest 62/62；`npx vue-tsc -b --pretty false`；`npm run build`；`python scripts/ci/check_dispatch_active.py`。CI、合并、部署和生产 UI 复验分别以对应回执为准。
- 外部限制：生产视觉模型账号欠费或额度不足导致识别 503；本批不触发新的付费模型使用，也不声称 AI 已恢复。
