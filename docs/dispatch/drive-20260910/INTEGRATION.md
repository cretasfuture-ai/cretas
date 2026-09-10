# 2026-09-10 集成验收

Owner: Codex task 01a08b76-18e8-7db3-8efb-424e3c4aadf0。
Base: e03fc7cf0c7c100d35cb8e907067e4b7e7e118af。
候选：4333be3989 / 2e57130ffa / a576140eba / 0bfa8ffacf / b31907752c。
Scope：上述候选代码文件、对应目录回归测试、本批验收文档。原批次任务只读交叉审查，协调者独占源码。

## 分类与取舍

| 改动 | 分类 | 实证风险 / 决定 |
|---|---|---|
| 表格头身列宽一致 | bug 修复 | 保留；验证完整文本可读 |
| 全局单行裁剪 | 展示优化 | 已复现隐藏完整品名且无 tooltip；撤除裁剪 |
| 顶部横滚、页签排序、参考窗滚动 | 交互优化 | 候选；检查卸载、滚动、关闭和点击副作用 |
| 日期手输与严格解析 | 交互优化兼输入校验 | 无效提交与纠正后点击丢失均已修正并复验；保留候选 |
| 物品/成品全量库存汇总 | 新能力 | 货主混合、同名行不可区分和旧分页扫描均已纠正；整行kg及规模性能仍未完成 |
| QC 写回错误传播与原子事务 | bug 修复 | 保留候选；服务代理+真实 PG 证据不等于 JPA/生产验收 |
| 图片手势与按钮分层 | bug 修复 | 保留候选；Web 不等于原生手势 |
| 图片工具栏尺寸/位置 | 交互优化 | 已修正可视边界，高框、缩放平移及点击通过；保留候选 |
| Canvas 区分执行结果 | bug 修复 | 执行后success:false和待DDL按未完成处理；前置拒绝才允许重试 |
| Canvas 按 payload 永久去重 | 防重优化 | 撤除对成功payload的永久拦截，按提案身份防重；新enable-disable-enable通过 |

## 当前状态

五批无冲突集成。独立交叉审查发现的候选回归已在集成版本纠正，详下表；这不是75条问题全部解决。未合并、未部署、未作生产业务写入。
初始 Web 类型检查的 null 隐式 any 已纠正。业务源码候选提交 c73eac7fb6；随后只补正一个测试断言与验收文档，业务实现和Web tree保持一致。

| 范围 | 最终窄验收结果 | 证据位置 |
|---|---|---|
| Web类型 | vue-tsc应用检查通过 | web-admin/.playwright-mcp/drive-integration/typecheck-final.log |
| Web集成 | 34文件209测试通过；日期最后局部15项通过 | 同目录 vitest-final.log、date-final.log |
| App类型与逻辑 | tsc通过，5套34项Jest通过 | frontend/CretasFoodTrace/integration-typecheck.log、integration-jest.log |
| Java目标 | 6类51个不同用例最终通过，0跳过；其中8项真实PG事务 | web-admin/.playwright-mcp/drive-integration/java-final.log、java-result-recheck.log |
| 表格 | 真实销售页1440/1100宽完整长品名，7列表头/正文宽一致，横滚同步 | C:/Users/Steve/cretas-drive-20260910-b02/docs/dispatch/drive-20260910/B01-integration-recheck-by-B02.md |
| 日期 | 新建/编辑无效输入0误提交；纠正后一次正确PUT，按钮位置不变 | C:/Users/Steve/cretas-drive-20260910-b03/docs/dispatch/drive-20260910/B02-date-focused-final.md |
| 库存 | 货主隔离/可区分行/卸载/刷新/切厂/KeepAlive共6项组件回归通过 | C:/Users/Steve/cretas-drive-20260910-b01/docs/dispatch/drive-20260910/B03-integration-recheck.md |
| 图片工具栏 | 810几何组合、鼠标6/6、浏览器触摸6/6，完整44px按钮；删除不新增/移动框 | C:/Users/Steve/cretas-drive-20260910-b05/.codex-evidence/b05/B04-integration-toolbar-recheck.md |
| AI消费者 | 26项目标测试与真实Vue补充实验：新提案可执行，同提案raw/proxy不重放，UNKNOWN保持隔离 | C:/Users/Steve/cretas-drive-20260910-b04/docs/dispatch/drive-20260910/B04-review-integration-final.md |

## 验收边界

- Web/触摸实验使用本地真实组件和隔离API夹具；不证明线上持久化或Android/iOS原生手势。真实业务写入为0。
- 库存汇总是新能力；没有实现kg换算，也没有真实规模性能、价格角色与分页事务快照证明。因此main66/UX16/UX26保持处理中。取消旧查询不代表全量查询已优化。
- 表格撤除信息裁剪后保留换行；main60关于不换行且完整展示、语义对齐的整行要求仍未完成。
- 工具栏当前完整可见范围为画布宽至少238px、高至少58px；正常320px手机的296px画布已验，极窄分屏未支持。真机、真实图片/对象框与OTA未验。
- AI UNKNOWN隔离仅限当前客户端会话；未实现持久幂等、核对恢复流程、所有工具/真实LLM三模式验收。PENDING_DDL不能显示为已完成。
- 75行已追加Bug/优化/新能力分类，并逐行回读确认原问题、原备注、格式与校验规则保留；另15行追加最终复验结果并回读，无记录标为已解决。回执 INTEGRATION-drive-update.json、FINAL-acceptance-drive-update.json。此批状态为正在解决33、已修复待验证10、修复中15、待部署6、待测试2、暂缓1、原空白保留8。
- Web最终构建通过：仅一次Vite构建，1m35s；build commit c73eac7fb63fe63079236ec256e8b86feff12d30，Web tree b8654b9f650974968d066a084f1c3a4cb7bb56a2。可信manifest及archive位于 web-admin/.playwright-mcp/drive-integration/cache/current/；archive SHA256 59b6da4de14abced1182d95620cd20c74083f18184e8b2ce2365afacfd9f9511。
- Java运行记录：首次 `mvn clean package -Dtest=CanvasAIApplyDiffsResultTest,WorkProcessCatalogChatContractTest,CanvasAIWorkflowConfigTest,QualityInspectionAtomicWritebackIntegrationTest,QualityInspectionServiceImplQcQuarantineTest,QualityInspectionServiceImplTemplateTest` 编译通过、51项执行、1项错误预期失败。该断言误将工具包装内部验证当作控制器前置拒绝；改为UNKNOWN，并保留success=false、applied=0、writes=0断言。仅重跑 `mvn test -Dtest=CanvasAIApplyDiffsResultTest`，14项通过；其他37项原运行通过。没有跳过测试，没有改业务实现或放宽重试；首次失败日志保留，未生成Java发布JAR，不能拿Maven的BUILD SUCCESS字样代替发布制品。
- 隔离副本位于 /home/ciworker/drive-integration-validation-20260910。7185文件集合与SHA在最终目标测试前后均与Windows源码一致；原始与最终manifest分别保存。首次复制期间新增DDL测试导致比对拒绝，测试前同步这两份已审查文件并全量重新核对通过，未用旧副本测试。最后只有上述测试断言变化，没有业务源码漂移。
