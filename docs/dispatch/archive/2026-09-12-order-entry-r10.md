# VERIFY-ENTRY-R10-20260912

Owner：当前视觉验收 task。初始Base：0f0c52a3ddb9a8614c368fc7989fa66dc7aa455f。独立worktree C:/Users/Steve/cretas-entry-acceptance-r10-20260912，branch codex/entry-acceptance-r10。

只读补验main11采购编辑入口、main80销售来源仓库文案。仅新增canonical场景文件；R9锁释放前不修改registry/mcp-entry，不修改产品、供应商、API、权限或任何生产业务数据。

本地实际Vue：采购草稿筛选→更多→编辑→取消，两次往返均打开编辑表单并清除edit参数；销售添加本地产品→来源仓库下拉→选择→关闭本地草稿，通过。零业务写入、零拦截、页面错误0。空草稿列表反例返回UNVERIFIED，不创建测试订单。81项现有harness通过；新场景真实UI证据位于C:/Users/Steve/cretas-visual-fixes-20260912-r3/.playwright-mcp/order-entry-r10-local/。

首次脚本错误等待/purchase/orders?status=DRAFT，实际应用使用/purchase/orders/by-status?status=DRAFT；按真实路由修正后通过，不掩盖API失败。截图初次捕到弹窗/下拉过渡，后改为等待元素与祖先有限动画结束，人工核验稳定截图；不以半透明过渡画面给页面评分。

仅入口/文案/本地选择，不验证采购保存与后端持久化、销售提交、库存变动、所有字段语义。生产验收、接线、CI和合并另记，不因本地结果关闭原行。

同批增加main64公共表头对比度：实际销售/标签拍检×1440/1024×明暗主题的旧表头对比度均约3.08:1。表头改用Element Plus实际组件主文字色，均升至13.02:1；8组列宽、行数、文字、横滚范围不变，错误0。主题切到dark时组件表头仍是白底，不能套用外围主题白字；本次不修改背景/尺寸或宣称修复整个暗色主题。scope已登记公共表头颜色；对比度新场景与入口场景同批接线，正式构建和生产另验。

R9发布完成后已重放到71dad59413cdb4740e4e65169d8cffdb19bc709c，作为本批集成/发布Base；registry与生成bundle接线四个opt-in场景。82项harness通过，空/NaN/低对比度不能获得PASS。普通文本4.5:1阈值依据[W3C SC1.4.3说明](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)，只测具名表头，不宣称全站WCAG合规。
