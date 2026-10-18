# 融合标注后的本地整链体验

任务 CONNECT-DATA-VISION-20261015；Owner root，DIRECT；Base 7c3387248d27b06c5796618d551b4c9f33c578f2。沿用独立分支 codex/trace-analysis-vision-daily-review-20261015，仅本地测试，不 push、PR、合并或部署。

## 当前切片与完成条件

以应用内浏览器 daily_factory_a 新建本厂专用检查，复用 8 张既有合成照片，实际执行融合标注、逐张审核、合格标准确认、冻结提交、现有 DATASET runner 与 TRAIN 入队。保留学习/独立试用分离，核对 API/SQLite、工厂与快照绑定和旧项目不变。仅提交训练，不运行 GPU/客户训练、不代签模型验收或启用。

通用包装缺件任务尚无通用执行适配，保持其真实状态；专用托盘标签任务只证明本厂注册适配链路，不推广为通用模型。既有编辑器和后端直接复用，不重建工作流。

## Scope 与验证

默认只修改本说明和 ACTIVE；发现问题才对当前照片组件、对应目标测试或现有 preparation 接线作最小修复并补证。外部本地合成 fixture、项目专属 runner 资料和 registry 仅在 Documents/cretas-connect-20261015 下维护，不进入 Git。不改旧照片、人工 JSON、客户配置、旧排队任务、共享发布/认证/Entity/Repository。

未改产品源码则复用 7c3387248d 的 49 项前端测试、18 项旧标注器测试、类型检查/构建及前批后端证据；本次新增实际完整 UI 与业务数据库读回。若发现源码问题，冻结后仅跑相应目标回归及必要类型/构建。

## 实操发现与最小修复范围

- 同项目点击“重新读取”会退回照片准备第一步，审核时也丢失当前照片定位。修复范围为 `VisionPhotoPreparation.vue` 及其组件测试；切换工厂/项目仍完整重置，不沿用旧工厂位置或内容。
- 冻结资料生成独立执行项目后，原检查折叠区仍显示旧 collect 计划，与本次 DATASET/TRAIN 事实不一致。扩展 scope 为 `FactoryVisionTrainingSetup.vue` 及其组件测试，通过 preparation 服务端返回的执行项目关联到现有项目进度入口；不复制进度/队列实现，不伪造原检查已完成收集。
- 合成照片仅校验链路；1%/2% 为这次演示标准输入，没有统计验收意义。准备资料的 4 张学习照片整理为 train=2、val=2，4 张独立试用照片完全排除。
- 重新打开已冻结资料时，审核导航误用编辑冻结条件，导致图片无法自动打开或切换。仅解除只读切图限制；框选、判定、文字、标准及保存仍冻结，服务端冻结门禁不变。

## 真实入口与业务读回

- 环境：本机 Web 31761 → Java 10060 → Python 18090 → 独立 PostgreSQL 55447 / preparation 与 jobs SQLite。账户 daily_factory_a，工厂 VISION-ENTRY-A，审核/标准确认 actor 93001。应用内浏览器同一登录会话，不注入 token 或修改页面内部状态。
- 新增源检查：`vtp-aa2fe64c6be3a7b3d79f`（融合标注整链体验线）。现有合成图 8 张，逐张实际拖框、选择类别/判断并保存；首张删除→撤销和放大→适应后保存。可见 SVG 与 API、SQLite 8 张坐标逐项一致，原图 SHA-256 全部不变。
- 合格标准通过中文表单显式确认；冻结 SHA-256 `3fe2e4f79c6789633c1791fd976a9c8f2c497e455a34260be8a9e1b15eb9a19e`。未审核提交禁用；未勾选质量确认时标准保存禁用；冻结后审核与标准不能编辑，但可只读切图、放大和查看标准。
- 为这批资料增加独立的本厂 recipe `local-integrated-photo-e2e-20261015`，精确绑定 factory、task/template/SOP、照片/标准/独立试用版本、配置与输入哈希、源项目与冻结 SHA。仅在代码外私有 registry 增加新项目授权/绑定；复用命名 worker，无通用适配伪装。
- 实际执行项目 `vtp-1bd45814305bc38194e9`。DATASET `vjob-a80edde0b74843f2df09ba52690ceb43` 由现有 `sop_worker.run_once` 领取并成功，原 build_dataset 结果 train=2、val=2，4 张独立试用图与学习集 ID 无交集；TRAIN 输入绑定实际生成的 dataset manifest 及服务端 dataset asset receipt。
- 在实际页面点击“提交本厂模型学习”后，TRAIN 已入队。SQLite 直接读回 DATASET succeeded / attempts=1 / 有 receipt，TRAIN queued / attempts=0 / 无 receipt；无 GPU 训练、模型效果验收、启用或自动部署。独立试用仍待完成。
- 6 项对照通过：匿名 401、异厂路径 403、异厂项目 403、冻结标准写入 409、worker 错 token 401、错冻结 SHA 409。对照前后 preparation 完全一致。
- 8 个旧项目的 jobs API 内容完全不变，8 个原 draft JSON 哈希不变，2 个原 recipe 完全不变；旧 TRAIN 未领取。通用“照片四步体验（可操作）”保持原状。
- 修复后真实 UI：原检查的“查看本次学习进度”通过既有项目 loader 进入上述执行项目，显示“确认照片与标记：已完成 / 让系统学习：等待处理”；原检查不再展示旧 collect 计划。刷新第 4 步仍留第 4 步；冻结审核切到第 5 张后刷新，仍在第 5 张且不可编辑；标准确认值与 1%/2% 读回一致。最后回到源检查第 4 步供用户体验。

## 验证与证据

- 新增 5 项交互回归。刷新定位 2 项、执行关联 2 项、冻结照片只读浏览 1 项分别先失败，修复后最终 7 文件 54 pass，exit 0。执行关联测试包含错误工厂、旧源项目事件不挂载入口，点击入口只读取现有 draft/plan/jobs、不会自动训练。
- 最终前端命令：`npx vitest run src/components/vision-training/__tests__ src/api/__tests__/visionPreparation.spec.ts src/api/__tests__/visionTraining.spec.ts src/views/quality/vision-training/__tests__/index.spec.ts`。最终类型/构建命令 `npm run build:check`。
- 最终源码冻结后的 `npm run build:check` exit 0：vue-tsc 与 Vite 均通过，Vite 构建 1m19s。仅本地制品验证，未发布。
- 刷新修复初版已有一次类型/构建成功；发现新的进度接线与冻结浏览问题后更新源码。中间构建主动终止，保存 `superseded-build.log`，不作为最终通过证据；最终构建只取源码/测试冻结后的 `type-build-final.log`。
- 无 Java/Python/共享编辑核心改动；复用前批对应源码下已通过的后端和原标注器验证，不重复训练或后端构建。`check_dispatch_active.py`、`git diff --check` 通过。
- 证据根目录：`C:/Users/Steve/Documents/cretas-connect-20261015/vision-integrated-full-flow`。主要文件：`annotation-readback.json`、`live-acceptance.json`、`jobs-database-readback.json`、`old-records-preserved.json`、`readonly-review-preserved.json`、`real-dataset-worker.json`、`train-input-binding.json`、前端测试/构建日志与 01–13 截图。`delivery-receipt.json` 记录最终源码/测试及日志哈希和 Git 状态。

仅证明本地合成数据完整流程至训练提交及隔离保护；实际训练、独立效果验收、模型启用未执行，基础模型就绪性未验证。仍保留独立分支，本地体验，无 push/PR/合并/远程部署。
