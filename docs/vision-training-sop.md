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

## 平台建厂和本地执行接线

平台建厂沿用该入口自己的已验证 `PLATFORM_ADMIN` 身份与目标工厂校验，Java 固定代理 `/api/platform/factories/{factoryId}/vision-training` 到 Python `/api/vision-training/platform/factories/{factory_id}`。平台身份不会借用同数字 ID 的工厂用户身份。预览、保存、已存项目、阶段计划、执行记录仍使用同一套 SOP 服务与存储，不另建训练向导或模型管理系统。平台建厂功能开关保持与原入口一致；封闭 v2 能力目录不新增虚构的视觉能力项。

步骤为：选择本厂任务和工位/产品/包装范围 → 选择已登记的本厂训练方案或先补齐标准 → 显式预览和保存 → 按顺序提交采集、数据集、候选训练、评测 → 刷新读回本厂执行记录。选择已登记配方时使用服务端绑定的数据、方案、人工标准和独立验收版本；修改版本须新建项目并重新登记输入。浏览器不传可执行命令、文件路径、模型 URL 或可信输入哈希。

后台将执行任务保存于代码树之外的 SQLite 队列。命名 Windows worker 仅能领取自身工厂/项目范围内的任务，通过租约和心跳持有执行权，使用既有 `collect`、`build_dataset`、`train_candidate`、`evaluate_candidate`，回传完整身份和产物回执。重复提交相同身份返回同一任务；过期或身份冲突不能自动重训。成功训练、独立验收和启用仍分别判断；HTTP 队列不接收独立验收或启用执行请求。

六膳门 adapter 是 `liushanmen-visionlab-v1`，只对明确登记的工厂与该专用任务开放。通用检测/OCR 模板没有已验证的通用 runner，未登记时可定义和保存任务，执行保持阻塞；不能将已有六膳门权重、类别、托盘几何或指标换个工厂名直接复用。

服务器通过绝对路径 `VISION_TRAINING_REGISTRY_FILE` 读取代码树之外的操作员配置，格式包含 `recipes`、`worker_bindings`、`specialized_factory_allowlist`。默认三者皆空，不启用任何工厂。每项配方须绑定工厂、任务族、模板/SOP/版本、配置 SHA-256 和当前阶段输入 SHA-256；新产物经本地核对后再登记下一阶段输入。worker 记录只含 `worker_id`、`token_env`、`factory_ids`、可选 `project_ids`；令牌由命名环境变量提供，不能写入配置或仓库。每个 worker 使用独立令牌，与服务间内部凭据分开。

本机执行入口为 `scripts/windows/vision-sop-worker.ps1 -Registration <本机外部注册文件> -Once`，只消费一个已排队任务；空队列不启动计算。注册文件限定本机项目根目录、配置/输入文件与其哈希、服务地址白名单、worker 名称及环境变量名。运行前须提供 worker 专属凭据及内部服务凭据，凭据不通过命令行输出。执行和提交失败返回非零结果；回执 hash 表示完整性，不能当作 GPU 实际运行或模型效果的独立证明。

本地计算遵守上海 Windows/RTX 3060 规则，不向 Mac 派工；日常原入口 `scripts/windows/liushanmen-vision-lab.ps1` 保留。队列不会启动客户训练、改变现有自动任务或切换生产模型。原图、人工 JSON、固定划分、保护集、正常集、ONNX 一致性、延迟和回退门禁继续使用已有实现。

草稿使用代码目录之外的绝对持久目录：Linux 默认 `/var/lib/cretas/vision-training`，Windows 默认用户目录 `.cretas-state/vision-training`，可由 `VISION_TRAINING_DRAFT_DIR` 指定。相对路径、代码树内路径拒绝；启动验证创建、写入、读回和原子替换。多 worker 按相同预览只能原子创建同一份草稿。

草稿与队列在应用导入时不创建目录或打开数据库，启动时再检查持久卷。队列终态回执是阶段读回的持久来源，避免“队列完成、草稿未更新”造成永久不一致。重新启动后仍须读回同一工厂项目、阶段和产物。

当前接线的单测、HTTP/数据库读回、实际平台入口验收和各厂真实模型训练/效果验收分别记录；不得用模拟 adapter 或合成样本的编排测试代签真实模型效果。平台建厂底座由其任务独立交付，未合入/释放前本批不覆盖其未提交入口或安全类型，也不宣称已完成该入口的端到端验收。
