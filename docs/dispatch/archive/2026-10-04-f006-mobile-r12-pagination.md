# F006-MOBILE-R12-PAGINATION-20261004

状态 in-progress；Base 88de770f5dc9e9c3dcfb160a05851dbc30d44643；WT C:/Users/Steve/cretas-f006-mobile-r12-20261004；branch codex/f006-mobile-r12-pagination-20261004。root 为台账唯一写者，代码 Owner f006_mobile_r12_exec；旧 trace 已冻结退出，不再写此范围。

真机隔离 APK9 的 F006 老板库存页连续“下一页”正确显示1/22→2/22→3/22和不同批次；“上一页”识别路径未进入服务端重放，旧翻页工具返回“正在加载第4页”的消息，未得到反向页结果。证据在私有 candidate9-stock-previous-settled.json/png，相关流请求日志已脱敏。

唯一允许代码文件 backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/SseStreamingService.java、backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/SseConversationPaginationContractTest.java。修复手机流式上一页请求的精确识别、复用同一服务端成功查询、原始筛选条件/size/页基准、首尾页边界。保持 trusted factory/user/session ownership、READ tool、write guard 和当前角色权限验证，不将客户端分页元数据视作真值，不调用写工具，不新增 IntentHandler。若需要额外代码范围先回报 root。

验收命令：JDK21 Maven -o test -Dtest=SseConversationPaginationContractTest,PaginationNextOwnershipContractTest,MaterialBatchQueryPaginationContractTest,ConversationMemoryOwnershipContractTest,GateSelectorCoverageContractTest -Dcretas.testIncludes=**/*ContractTest.java -DargLine=-Xmx1g\ -XX:ActiveProcessorCount=2\ -XX:+EnableDynamicAgentLoading\ -Xshare:off；测试编译选择器可依实际旧类依赖合理扩大但不得跳过。真实同 WT Maven 串行，日志放私有 r12-*.log。至少覆盖 previous 3→2、next 2→3、first-page拒绝、hasMore=false尾页仍可previous、1/0起始基准、会话/租户/用户/权限拒绝与零业务调用；不得以纯源码字符串检查替代行为。

禁止子代理、Git commit/push/PR、ACTIVE/archive改动、数据库/线上请求/服务器/设备/依赖安装/runner改动。你不独占整个仓库，不回退他人改动。root 继续独立的人事统计发布与手机验收，不写本scope；交付 task/Base/文件/验证证据/阻塞/下一动作。本批尚未实现、验证、合入或发布。

Final implementation: server tool schema minimum 0/1 is authoritative; legal defaults may be above minimum, default-only previous safely refuses. Strict page/total/hasMore bounds, integer overflow, current READ/write guard and current ownership/permission controls. Previous refusal retains previous intent direction. Five target selectors 44/44 PASS (private r12-final-five-selector-v2.log); final independent risk review P0/P1 zero and frozen SHA verified. No Entity/Repository/query/migration changes. Final main integration, trusted build, PR/current CI, production/native previous-page acceptance pending.

Source frozen and scope released in combined R12/R16 candidate integrated with defc450e3e main. The two independent reviews remain valid: integration changed no reviewed production source. Final combined trusted build/test selector and current PR CI remain required; merge/deploy/native acceptance are separate pending facts.
