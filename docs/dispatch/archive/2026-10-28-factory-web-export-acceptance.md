# FADWEBEXP28 — 网页三格式实际下载验收

Base SHA 4ab8166017e62bc2b60ba222d3bc43d7e86d2ce7；协调者Root唯一台账/提交/发布写者，代码Owner web_export_acceptance。仅允许scripts/e2e/production-readonly/scenarios/smartbi-factory-analysis-readonly.js、tests/smartbi-factory-analysis.test.js、生成的mcp-entry.js；归档由Root维护。禁止修改产品、依赖、白名单、mutation guard、凭据、后端/RN或发布脚本；不得运行浏览器/手机/SSH/生产操作、生成子代理或提交推送。

用户要求F006网页与原生App完整验收。当前已完成：服务发布、12文件与角色回读、最新网页汇总/上下文追问/明细/Excel实际下载、原生SKU真实5行明细与三种格式实际分享chooser、原生产品澄清与特定关系缺口。尚须在同一个canonical入口、同一个干净登录会话和before-send guard下实际点网页Excel/PDF/PPT并下载，不能用临时脚本或无guard的后续点击冒充。

执行者增加可复用的三格式下载步骤并测试缺少某一格式/失败时不能总判通过，保留原业务断言；按实际Web按钮/endpoint匹配，保存下载至现有证据目录供Root验证文件格式。候选目录和joint无合格数据仍按现有判据，不伪造positive PASS。验收命令：node scripts/e2e/production-readonly/build-mcp-entry.js；node --test scripts/e2e/production-readonly/tests/smartbi-factory-analysis.test.js scripts/e2e/production-readonly/tests/mcp-entry.test.js scripts/e2e/production-readonly/tests/whitelist.test.js；git diff --check。Root冻结后统一harness全套、PR/CI/合并、已有sparse worktree真实Git入口F006 MCP复验。纯验收工具，不重复部署已通过的产品服务。

最终源码已冻结，状态review；同一最终提交归档并释放scope。执行者结构化回执含Task/Base/三文件/验收命令/无commit与PR/无阻塞；目标16项通过，builder及diff check通过。Root核对三文件diff：保留所有旧断言，三格式分别绑定具体readonly export endpoint，全部需要HTTP200、真实download及保存，joint仅在真实联合候选存在时执行；不修改guard/白名单/身份验证或业务数据。Root冻结后全套143项PASS、0失败/跳过（fadwebexp28-full-unit.log）。下载命名smartbi-factory-analysis-overview-{excel.xlsx,pdf.pdf,ppt.pptx}，最终浏览器下载与文件内容验证尚需各自实际回执，未执行不记通过。
