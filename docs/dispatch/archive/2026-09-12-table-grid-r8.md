# UX-TABLE-GRID-R8-20260912

Owner: 当前视觉验收 task。初始 Base: b08b707a02125fa17b41a87c6764f5e507021378。
Scope: web-admin/src/style.css，表格视觉验收证据和本归档。原表 main59 / UX&UI10，分类为体验优化。

原销售表已有完整框线，标签拍检表缺竖线。全局表格增加内侧外框及单元格横竖线；覆盖 Element Plus 非 border 表格的移除规则。使用现有明暗边框 token，不改列宽、文本换行、业务按钮或接口。

本地真实 Vue 对照：销售表和标签拍检各 1440/1024、light/dark，共 8 组。原有销售竖线保持；标签表右边框由 0px 变 1px。修改前后所有实测列宽、行数、首行完整文本和最大横滚范围逐值一致，表头/数据宽差 <2px，顶部/表体横滚同步，console/page errors 为0。
证据：C:/Users/Steve/cretas-visual-fixes-20260912-r3/.playwright-mcp/table-grid-r8/；脚本同证据根目录上两级 table-grid-r8.mjs。

边界：仅代表性表格，不宣称全部页面通过；暗色模式原有白色内容卡片、暗色面包屑低对比度及窄屏固定列遮挡仍存在。没有耗时/触摸/原生鼠标验证，不宣称性能提高或零负优化。生产回归与发布另记。
