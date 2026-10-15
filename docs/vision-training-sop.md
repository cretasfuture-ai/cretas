# 工厂独立视觉训练 SOP v1

本 SOP 复用现有 VisionLab 的采集、数据集、候选训练、评测、导出一致性和发布门禁。通用的是流程和证据契约，每个工厂、工位、产品与包装范围都需单独建立数据与验收。六膳门权重、类别、托盘几何与阈值不作为其他工厂的默认值。

## 对话建厂接入契约

Python 主服务注册 `/api/vision-training`。沿用 JWT 中可信的 factoryId、userId 和工厂角色；JSON 中的 factory_id 仅声明请求范围，不构成身份。平台身份不能冒充工厂身份。

1. `GET /templates`：取当前工厂可用模板及 TaskSpec JSON Schema，据此生成表单或对话补问。
2. `POST /preview`，提交 `{spec, template_id?}`：校验、预览阶段与适用范围，不保存。
3. 用户在建厂流程明确保存时，`POST /drafts`，提交 `{preview}`：服务端重建模板并验证预览，按内容幂等创建草稿。
4. `GET /drafts/{project_id}` 与 `/drafts/{project_id}/plan`：读回持久化草稿、待办、阻塞与阶段证据。

TaskSpec 必填：factory_id、workstation_id、task_family、product_scope、packaging_scope、data_version、recipe_version、human_standard_version、independent_acceptance_version；OCR 另需 ocr_rules_version。工厂、工位、产品/包装或标准变化时建立新规格，不悄悄复用旧验收。

有限模板：`packaging-detection`（有无/缺件检测）、`packaging-ocr`（独立 OCR 规则），以及显式服务端工厂白名单内的六膳门专用模板。模板描述流程，不能证明某个模型已经具有该检测能力。未登记任务族直接拒绝；通用模板没有默认训练 runner 或模型。

## 阶段和证据

| 阶段 | 复用入口/证据 | 通过条件 |
|---|---|---|
| 定义任务 | 建厂草稿、人工验收标准、适用范围 | 明确可见缺陷、采集条件和独立验收版本 |
| 采集 | 既有 collect 阶段 | 原图保留；工厂/工位、时间与来源可审计 |
| 数据集 | 既有 build_dataset、人工审核与去重机制 | 人工审核数据；训练集排除保护集；冻结版本与 SHA-256 |
| 候选训练 | 既有 train_candidate | 候选训练回执；不等于验收或启用 |
| 评测 | 既有 evaluate_candidate、verify_export_parity、evaluate_gate | 按本厂任务的标准完成保护集、独立正常集、一致性、延迟及回归门禁 |
| 独立验收 | 本厂独立数据和评测回执 | 工厂/项目/任务、数据/配方/人工标准版本一致，独立验收通过 |
| 启用 | 既有 deploy_candidate 受控发布 | 单独发布与线上读回；保留可回退模型 |

任何阶段失败、无 runner、无回执、跨厂证据或未经人工审核的数据都不能变成成功。回执必须携带工厂、项目、任务族、版本和真实哈希；训练成功不推导独立验收通过，验收通过不推导已启用。

## 本版运行边界

本版 HTTP 入口只提供模板、预览、保存和计划读回，没有训练、发布或启用端点。六膳门 adapter 仅提供命名阶段契约；未注入经审核的本地 runner 时保持 pending，默认无任何专用工厂绑定。实际本地计算继续走 `scripts/windows/liushanmen-vision-lab.ps1`，遵守上海 Windows/RTX 3060 计算规则，不向 Mac 派工。

草稿使用代码目录之外的绝对持久目录：Linux 默认 `/var/lib/cretas/vision-training`，Windows 默认用户目录 `.cretas-state/vision-training`，可由 `VISION_TRAINING_DRAFT_DIR` 指定。相对路径、代码树内路径拒绝；启动验证创建、写入、读回和原子替换。多 worker 按相同预览只能原子创建同一份草稿。

建厂仓库现有封闭 v2 能力目录未在本批更改；本接口供后续版本显式接入。完整对话建厂 UI、真实训练 runner、各厂数据采集及模型效果验收分别交付，不能用本版编排测试代签。
