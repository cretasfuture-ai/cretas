# 本厂照片准备到训练提交

任务 CONNECT-VISION-PREP-20261015，Base 94c4247639cb312c9be64c53a081594d844a0aa3；沿用 codex/trace-analysis-vision-daily-review-20261015。root 为唯一协调者。仅独立分支、本地测试，不 push/PR/合并/部署，不修改原有客户照片、人工真值、模型或自动任务。

## UX Flow Analysis

### 用户画像

工厂质量负责人，熟悉产品和合格要求，不熟悉训练配置、版本、哈希。日常为已保存的本厂检查准备照片，逐张确认，再提交学习。

### 用户旅程

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|---|---|---|---|
| 照片准备 | 学习照片/另留试用照片、格式和大小边界 | 选择本机照片，填写拍摄批次 | 保存本厂原图；去重、独立试用隔离 |
| 标注审核 | 图片、中文合格/不合格/看不清、标记区域 | 逐张查看，框选检查对象，确认审核 | 真实人工身份/时间；看不清不进入训练 |
| 合格标准 | 什么算合格/不合格、漏检误报上限 | 质量负责人明确确认 | 绑定本批照片/审核版本，修改后重新确认 |
| 提交 | 本厂照片与审核计数、标准、执行条件 | 明确提交训练 | 冻结不可变资料与请求；只有与本厂资料精确绑定的已登记 runner 可以排队 |

### 摩擦点清单

| # | 摩擦点 | 严重程度 | 来源 |
|---|---|---|---|
| F1 | 技术配置挤进日常步骤 | HIGH | 信息密度 |
| F2 | 未审核、看不清照片当真值 | HIGH | 边界防呆 |
| F3 | 试用照片混入学习 | HIGH | 身份上下文 |
| F4 | 重复提交、过期页面覆盖审核 | HIGH | 幂等 |
| F5 | 保存资料被误解成已训练/已启用 | HIGH | 实际回执 |

### 每个摩擦点的设计回应

- F1 → 四步中文操作，每步一个主动作，维护细节折叠；复用原有页面、工厂权限和 runner。
- F2 → 逐张人工确认、有效框/文字校验、来源和确认时间由服务端产生；不清楚明确排除，不以 AI 候选代人工。
- F3 → 原图、解码像素哈希及拍摄批次隔离；另留试用照片不导入学习 manifest，留存来源与审核哈希。
- F4 → 服务端 revision 乐观并发与 SQLite 原子事务；内容去重、冻结提交幂等，冻结后不得改照片或标准。
- F5 → 资料完成、请求保存、队列排队、模型训练、独立验收、启用分别展示。未登记或与本厂资料不匹配时明确等待配置，禁止自动套用六膳门。

## Scope 锁

- root：新增 backend/python/vision_training/preparation.py、preparation_router.py 与对应测试；runtime.py 挂载；models.py 仅可选的资料绑定字段；现有本厂 Java Controller/client 的有限 preparation 操作及目标合同；必要本地导出 helper/worker 输入核验；文档/ACTIVE 和代码外验收。最终整链发现的 FactoryVisionTrainingSetup.vue / 对应目标测试由 root 收尾：区分冻结快照派生执行记录，避免重复照片向导及过时 collect 下一步；不改原任务或执行语义。
- VISION-PREP-UI-20261015：Owner vision_prep_ui；同 Base；仅新增 web-admin/src/api/visionPreparation.ts、components/vision-training/VisionPhotoPreparation.vue、PhotoRegionEditor.vue、对应测试；现有 FactoryVisionTrainingSetup.vue 只 import/挂载新增组件。不得改后台/原有API/菜单/路由/ACTIVE/已有任务逻辑。验收为新增组件/API目标 Vitest，类型检查由root最终统一执行。与root后台独立，完成后释放scope。
- VISION-PREP-WORKER-20261015：Owner vision_prep_worker；同 Base；独占 tools/vision-lab/prepare_sop_photos.py（新增）、sop_worker.py 仅已冻结资料验证入口、该helper新增测试。root不同时编辑这些路径。验收 Python目标测试及既有sop_worker目标回归；本地外部目录、只生成资料和核验，不执行训练或更改registry/计划任务/模型。必要扩锁回报root，不生成孙代理。
- 保留平台建厂 Owner 与全部其他 scope；无 Entity/Repository/Flyway/全局 Security 修改。

## 验收

真实图片上传/内容读回、人工框审核、看不清排除、标准确认、提交和刷新持久化；错厂/平台拒绝、重复图/像素/拍摄批次隔离、未审/未确认拒绝、并发revision、冻结不可改、重复提交同一请求、资料hash不匹配绝不排队。已登记任务复用真实队列/命名runner输入校验；无通用执行适配时如实等待配置。真实模型精度和生产启用不属于本地合成编排验收。

## 2026-10-15 本地交付回执

- 实现完成，待用户体验；独立 worktree `C:/Users/Steve/cretas-analysis-provenance-20261015`，branch `codex/trace-analysis-vision-daily-review-20261015`，Base `94c4247639cb312c9be64c53a081594d844a0aa3`。未 push、未 PR、未合并、未远程部署。UI/worker 子任务 scope 均已释放给 root，最终 Owner root。
- 工厂长期入口 `质量管理 → 视觉质检训练`，平台建厂开关关闭。复用已存任务、Java 当前工厂成员校验、Python SOP/SQLite 队列和 Windows VisionLab 的命名函数。未修改 Entity、Repository、Flyway、全局 Security、客户原图/标注/权重、自动任务或其他 Owner 文件。
- 新增照片持久化和四步中文操作；保留原图、逐张人工身份/时间、审核框和标准。revision 防覆盖，同厂跨项目防训练/试用混用。先冻结再排队，失败后只刷新恢复；精确绑定快照、输入和源产物哈希，重复提交不重复计算。命名 worker 逐文件认证传输，源项目读取和执行项目范围分别授权。
- 专用实测源项目 `vtp-ea7d94807ec60b302c36`，本厂 `VISION-ENTRY-A`，用户 `daily_factory_a`。真实浏览器上传 8 张明确标记的合成照片、人工拖框和逐张确认、标准确认、提交冻结，先得到真实 waiting_configuration；随后外部显式注册只针对该快照/工厂的 runner，通过真实下载转为原 manifest。执行项目 `vtp-20470a506d4bacfd5498`：原 `run_once → build_dataset → complete` 成功，train=2、val=2，独立试用 4 张均未入学习数据。登记实际数据集 manifest 和回执来源 SHA 后，由页面明确提交 TRAIN，读回 queued、attempts=0、receipt=null。没有运行 GPU 训练，没有声称基础权重就绪、效果验收或模型启用。
- 实测 6 项阴性控制：匿名、本厂路径上的他厂账号、他厂下的源项目访问、冻结后标准修改、错误 worker token、错误快照 SHA。均拒绝；冻结源状态未变。独立 SQLite 读回 8 张审核来源/身份和同一队列任务；原 5 份 SOP 草稿 JSON 哈希保持不变。准备/任务库仍在本地隔离状态目录，不触碰生产。
- 用户可操作项目 `vtp-199dcbdd8482f20bb164`，检查位置“照片四步体验（可操作）”。通用缺件任务，有 4 张学习、4 张另留试用合成样本；全部保持未审、无标准、未冻结，方便本人体验。通用/OCR 没有通用 runner 时，资料提交明确等待接通，绝不借用专用托盘模型。

### 已完成验证

证据目录：`C:/Users/Steve/Documents/cretas-connect-20261015/vision-preparation`，代码外保存，不包含在 Git 提交中。

| 验证 | 命令/范围 | 结果与证据 |
|---|---|---|
| Python 视觉回归 | pytest，tests 下 `test_vision_training*.py` 对应文件列表 | 100 pass，exit 0，`python-final.log`；随后绑定/就绪状态最终改动仅重跑 `test_vision_training_preparation.py`，18 pass，exit 0，`python-preparation-frozen.log` |
| Java 合同 | Maven `test`，5 个 factory/platform/client `*VisionTraining*ContractTest` 相关类 | 20 tests，0 failure/error/skip，BUILD SUCCESS，exit 0，`java-final.log` |
| Web 目标 | Vitest：新 API、两个照片组件、既有 SOP 组件/API/日常入口共 6 文件 | 最终 41 pass，exit 0，`web-ui-final.log` |
| Web 类型/构建 | `npx vue-tsc -b`、`npx vite build` | 最终 exit 0，`web-typecheck-last.log`、`web-build-last.log` |
| 真实业务链 | 当前应用内浏览器、真实 Java/Python HTTP、本地持久库、命名 worker | `live-acceptance.json`、`real-dataset-worker.json`、`train-input-binding.json`；截图 `01`–`06`，最终体验截图另存 |
| Source/台账 | `git diff --check`、`python scripts/ci/check_dispatch_active.py` | pass；无 Entity/Repository 变更，不触发新增 JPA 查询门禁 |

实操额外发现并修复：保存后坐标字段顺序变化导致前端误判未保存（新增用例先 red 再 green）；冻结提交后标准确认展示被错误清空；派生执行项目和原检查同名、误开空照片准备及旧采集下一步（新增回归保护）。派生项目标为“学习执行记录”，复用原进度面板，从数据整理阶段显示；原始所有阶段/项目不改写。源码最终冻结后完成上述前端验证；文档/台账变化不重复产品构建。

私有 Java 使用 target/classes；首次编译期间旧进程热读文件曾产生 ClassNotFound，证据保留为 `java-hot-recompile-environment.log`。最终编译完成后仅重启自有 Java/Python，再完成真实流程；不把该环境失败算作产品通过。过程中一次在私有 Python 重启窗口读取失败，恢复后明确重新读取成功；不是业务成功证据。

### 尚未执行与后续边界

真实工厂照片数量、完整通用检测/OCR 执行适配、基础模型选择、GPU 训练、独立效果验收、导出一致性/延迟、模型启用、生产保留/备份策略均不能由本地合成流程代签。本次完成范围到资料审核/标准/数据整理与训练排队，用户体验后再决定后续；继续遵守不合并部署。
