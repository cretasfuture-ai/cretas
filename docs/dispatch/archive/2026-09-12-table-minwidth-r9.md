# FIX-TABLE-MINWIDTH-R9-20260912

Owner：当前视觉验收 task。Base：0f0c52a3ddb9a8614c368fc7989fa66dc7aa455f。复用自身干净独立worktree C:/Users/Steve/cretas-table-visual-r8-20260912，branch codex/table-minwidth-r9。

main79生产反例：R8表头与正文几何对齐，但拖窄到30px时订单编号逐字竖排，数据行严重增高。不能用几何PASS关闭视觉缺陷。证据production-table-r8-final/.playwright-mcp/run/03-table-grid-sales-readonly-resized.png。

修复：窄范围适配Element Plus table-header/event-helper的30px硬编码拖拽下限，遵守列min-width；首拖时以WeakMap保留原始窄列宽度，避免原本40px列放大后无法缩回。销售订单号声明min-width120、默认170不变。拖拽指示线和落地宽度使用同一约束，不裁剪数据，不改业务/API/权限。上游匹配漂移直接报错；开发预打包和正式build共用同一补丁。

5项目标测试通过：执行实际安装的vendor鼠标handler，先证明原版压至30，再验证120下限、正常放大/缩小、原始窄列放大后缩回、布局调度及事件释放。81项canonical harness通过。初版本开发实际Vue8组几何/文字/行数/横滚对照及3个canonical场景通过，销售首行全程65px；加入窄列回缩保护后的最终正式制品/CI/生产证据另存回执。

只覆盖具名表格及交互；不宣称全部表格、原生指针、触摸、列宽跨刷新记忆、所有业务流程或整体性能通过。框线的R8生产四场景已完成并回写65单元格；本R9生产状态独立记录。
