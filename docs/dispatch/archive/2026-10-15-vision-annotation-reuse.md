# 复用现有照片标注能力

任务 CONNECT-DATA-VISION-20261015，Base d29add746089a504d4195e34512eec99ee65dbf7。Owner root，DIRECT；沿用 codex/trace-analysis-vision-daily-review-20261015。只在本地独立分支，不 push/PR/合并/部署，不启动客户训练，不改旧队列、原图、人工标注或模型。

## 已核实与复用方案

- `tools/vision-lab/tray_annotator_standalone.py` 已有放大、移动框、角点调整、单框删除、重拉框、撤销和保存下一张。`tray_annotator_local.py` 是现有启动入口。
- `label_annotator_local.py` 的专用标签工作流还依赖外部旧脚本；本机默认 D:/Temp 目标当前不存在。不能把该外部依赖宣称为已融合的通用编辑器，也不复制六膳门类别/托盘判断规则。
- 将旧标注器的坐标命中、移动、拉伸、规范化、缩放锚点和撤销栈提取为一个无业务类别的共享 JS 模块；旧标注器和当前 Vue 图片组件均消费它，避免两份几何实现。旧页面继续保留其队列和专用规则。
- 四步日常流程、Java/Python 工厂权限、冻结标准与现有训练 runner 保持复用。编辑器只接当前受权照片及动态类别，所有持久化继续走已验收的 preparation API。

## UX Flow Analysis

质量人员先在原四步流程选照片，再在图片上新增、选择、移动/拉伸或重拉标记，必要时放大；逐张明确判断并保存，随后确认合格标准并提交。主要摩擦是框画错只能全清、细标签看不清、误改无法撤销。对应提供中文工具、选中态、单框删除、撤销、最大六倍缩放；图片/项目切换清除未保存编辑历史，冻结资料只读但仍可放大查看。预标注不自动成为人工真值。

## Scope 锁与完成条件

仅 `web-admin/src/components/vision-training/PhotoRegionEditor.vue`、新增共享 `boxEditorCore.js` 及声明、对应目标测试；`tools/vision-lab/tray_annotator_standalone.py` 和原 annotator 目标测试；本说明、现有 SOP 文档和 ACTIVE。无后端/权限/实体/训练引擎修改。

目标验证：共享核心与照片组件 Vitest，原四步组件/API/入口回归，vue-tsc、Vite build；旧标注器 Python 目标测试；当前应用内浏览器真实拖框、移动/调整、重拉/删除/撤销、缩放与保存后刷新读回；合成独立队列验证旧标注器继续加载共享模块并实际保存。已有未变化后端验证直接复用。

## 本地验收回执

- 共用核心来自原托盘标注器，旧页面改为加载固定第一方 JS 资源，当前 Vue 组件直接 import 同一文件。共用命中/移动/拉伸/归一化/缩放锚点/撤销栈；旧工具通过既有启动入口使用。类别选择只决定下一个新框，不自动改写旧框；重拉在有效新框完成前保留原标记。
- 当前应用内浏览器、`daily_factory_a`、项目 `vtp-199dcbdd8482f20bb164`：新增框、移动、角点调整、删除→撤销、重拉替换、放大/适应、明确审核保存、刷新读回均通过。以可见 SVG 属性为基准，实际 API 与 SQLite 坐标一致。8 张原图哈希均未变；其中第 1 张作为已保存演示，其余 7 张留给用户体验；无标准确认或训练提交。
- 原工具只在新建的代码外合成队列开启 `127.0.0.1:18774`，通过现有 `tray_annotator_local.py --review-limit 2` 入口。真实页面加载共享模块，与 Web 源文件字节一致；拖框、移框撤销、放大/重置、确认下一张与人工 JSON/统计读回通过。临时标签页与自有进程已关闭，未启动旧客户队列。
- 原冻结项目 `vtp-ea7d94807ec60b302c36` 的准备状态完全不变，既有排队训练未被领取；没有模型训练、验收、启用或生产写入。工厂数据/标准/执行隔离继续复用既有后端。
- 前端目标回归：7 文件 49 pass，exit 0，`web-final.log`；Python 原标注器目标：18 pass / 3 subtests pass，exit 0，`python-annotators.log`。类型检查与最终构建日志为 `typecheck-final.log`、`build.log`；构建在最终产品源码冻结后启动并通过。Java/Python preparation 后端未改，不重复上一批已通过测试。
- 证据：`C:/Users/Steve/Documents/cretas-connect-20261015/vision-annotation-reuse`，含 `daily-editor-actions.json`、`daily-readback.json`、`legacy-core-source.json`、`legacy-readback.json` 与截图 01–04。前后界面对照复用上一批 `vision-preparation/09-user-experience.png`。测试过程的初始失败来自不完整 DOMRect fixture 和严格浮点比较，按真实 DOMRect/浮点误差修正，未删除行为断言；一次实操在撤销清空选中后直接点重拉被正确禁用，重新明确选中后继续通过。

仅本地独立分支交付，待用户体验；无 push、PR、合并或远程部署。当前融合的是通用标注交互与审核保存接线；六膳门专用缺失确认、目标托盘与四点工作区等规则不被泛化，真实训练效果不由合成样本代签。
