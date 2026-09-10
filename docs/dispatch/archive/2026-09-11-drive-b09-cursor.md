# Drive B09 光标修复集成归档

Tasks: DRIVE-20260911-B09-WORKFLOW-CURSOR / DRIVE-20260911-B09-INTEGRATION.
Owner: Codex task 01a08b76-18e8-7db3-8efb-424e3c4aadf0.
Release Base SHA: f6d987696d791c7db249ade955f9ecb71f2bf9b7.
Source Base SHA: 0a5d10347d0918c33cc5db2ffa1613690cdb158e.
Source commits: 1567e13b2ff87d5a436d928e60b526a20f20d521 and documentation-only 9b704c71a8f487b516b99e1360504c55a49578bd.

修复Workflow连接端点和实际VueFlow pane绕过既有双描边光标变量的问题；非法目标not-allowed、普通按钮pointer、平移与节点grab/grabbing及释放恢复继续保留。仅三个Vue、一个交互测试、Owner回执和本归档，未改全局CSS、资产、事件处理、业务规则、依赖、后端或原生包。

协调者fresh-fetch确认release base，证明Source Base至release base的workflow目录和全局style.css无差异。整合后四个源码/测试文件和全局CSS的SHA-256与B05独立只读复核全部一致。B05的Base/候选CSS级联阴性/阳性对照无阻塞finding，报告保留在其独立证据目录。

Owner最终107项目标测试、应用类型检查通过；完整Editor与真实VueFlow在80/100/125/200%浏览器zoom及浅/暗画布下8组、108条状态断言通过，50GET/12PUT由本地夹具拦截，生产业务写入0。调试阶段夹具失败和中止单独保留。相关源码及上下文均未再改变，因此复用目标验证；正式Web构建与CI在本干净集成候选完成。证据详见docs/dispatch/drive-20260910/B09-receipt.md及Owner的web-admin/.playwright-mcp/b09目录。

本批属于已复现的CSS传播Bug修复。未证明Windows原生指针像素、真实OS DPI、SVG fallback或用户线上会话；浏览器zoom与截图不能替代这些验收。Drive main13/main61/UX12仅更新实际阶段备注，保留原状态和未完成项，部署后追加真实版本与服务验收；不将整行提前标为已解决。

本归档与上述两个任务scope释放随最终PR合并生效；合并、部署、迁移、真实业务及原生指针验收分别记录，不能相互替代。
