# FADLOGIN27 — 登录挂载前置门禁

Base SHA 105fe29280dadfe2ca754e3fabcbc9da56acc6e6；Root DIRECT，源码冻结、review，最终提交归档并释放scope。仅修改只读harness core/ui-login.js、core/run-suite.js、生成的mcp-entry.js和tests/ui-login.test.js；不修改业务代码、凭据、mutation guard、白名单、服务器或发布脚本。

F006线上验收连续两次在登录输入框前超时，截图显示#app-loader而非登录失败。原入口只在登录后的落地页调用现有waitForAppMount；现补登录前同一门禁、向下传递已有appMountTimeoutMs，并在成功登录证据记录loginAppMounted/loginAppMountMs。挂载失败在填写凭据或发送认证请求之前明确失败；不降低租户、权限或业务断言。

冻结后node --test scripts/e2e/production-readonly/tests/*.test.js实际141项全部通过、0失败/跳过。新增两项覆盖挂载异步完成后才寻找输入框、未挂载时不得接触凭据或认证。统一入口已由build-mcp-entry.js重建，漂移检查通过。产品Java正式回执cretas-1793111309-65256.json为deployed/exit0/FINAL1，Python官方部署exit0及实际运行角色/强制RLS/四文件回读PASS；本修复为验收工具，不需要重建或重发产品。

F006 canonical MCP复验从已有仓库内sparse worktree的真实Git提交加载入口，使用单个干净UI登录会话、90秒挂载上限、零业务写保护；实际线上结果独立保存在ignored .playwright-mcp/smartbi-factory-ai-20261027/，未执行或失败不得记通过。旧两次加载超时和工作区外filename拒绝证据保留。完整联合正向样例当前数据不合格（31完成生产记录、1质检但批次10995未进入已完成Silver），不补造事实。
