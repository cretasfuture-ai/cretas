# 食品 DAG 接手与收尾 2026-11-05

任务：FOOD-DAG-CLOSURE-20261105；唯一写 Owner：Codex 食品 DAG session。
Base SHA：5e59baa7e9f6a798cd66cd0032cc34aa2b27dd4f。
Worktree：C:/Users/Steve/cretas-food-dag-closure-20261105，分支 codex/food-dag-closure-20261105。

## 当前 scope

仅 ToolDispatchService.java、IntentExecutionOrchestrator.java、ToolRuntimeRegistry.java 及 service/execution 对应目标测试、ai/tool/gateway/ToolRuntimeClassificationContractTest.java；本归档和 ACTIVE 自己条目。原工作树、其他 AI 配置、其他任务改动不动。
先补 #1440 证实仍在主线的缓存/确认参数/权限/审批问题。审批暂无真实流程时失败关闭；不虚构审批记录。
验收命令：Maven ConfirmedWriteDispatchContractTest、ToolDispatchService*Test、IntentExecutionOrchestrator*Test、确认/写边界契约；最终发布按统一 build/prewarm/deploy。无 Entity/Repository/迁移修改。

## 已恢复事实

- 原食品DAG worktree rev-parse show-toplevel 精确命中、clean、af23a81f10。主仓 dirty 保留。
- #1440 OPEN，仅审计文档，两个文档检查通过，不是产品修复。
- #1426 fast-contracts 单失败为 ReportsFinanceAnalyticsGateContractTest.everyAppEntryToTheFinanceReportIsBehindTheAnalyticsModule；其余6942非失败，222 skipped。#1437 后续修复该入口清单；不代表DAG14线上失败。
- #1435 已合并，历史Java/Web发布回报不升级为当前验收。adb f79c50d6 已连接，App 1.0.5/versionCode 16。
- 原用户最新有效决定：客户归属“都有可能，需要专门配置”；黑湖分析暂停，不纳入本组。
- 原用户要求继续全部实现、测试、PR与已授权发布/验收；交接审计里的只读措辞不是执行限制。

## 未完成

AI边界目标测试与PR；#1440文档收尾；DAG16缺失配置/闭环核实；当前OTA内容/送达/真机；各DAG有效任务证据矩阵。

高风险终审发现并修复：claim中原始userInput不可被合成确认文字覆盖；治理分类异常不可回落legacy；权限/审批测试改名ContractTest进入CI匹配面。

## 当前验证与交付证据

- 冻结源码目标回归：190 tests，0 failures/errors/skipped；`ai-boundary-verified.log`，Maven rc=0。覆盖真实5项治理写政策的来源拒绝、异常不降级、写缓存/冻结参数/未知结果、权限撤回/审批阻止以及DAG16半成品/在制品/成品归属。
- 预期行为：现有5个治理写工具政策均不允许 AI_INTENT_DISPATCH；本改动遵循政策拒绝此来源，不扩政策、不伪造确认或审批。专用HTTP/定时等来源不变。其余legacy写仍由服务端预览确认闸保护。
- #1452 已合 a294f1078d；#1440历史审计已合66339d797e。无重复单位修复。
- OTA：使用已合main a294f1078d树、npm ci锁文件安装、官方push-bundle；rv1.0.5，ts1793843322838，总84秒成功。首次未锁依赖打包失败（未发布）；已纠正且未提交lockfile漂移。
- 真机 f79c50d6：原始SECONDARY批次11072的半成品选择区出现，证明新代码已实际运行；回料407可选。未选原因提交时提示100% > 3%，继续键disabled，库存与报工聚合均未变。选“库存回料需尽快消化”后投入0.1kg成功，库存1→0.9；report24059 INPUT/SUBMITTED，custom_fields.reworkRatio=OVER_WITH_REASON、actualPercent100、limitPercent3，reason实际落库。
- 私有业务截图/脱敏回执：B:/moved-from-d/Documents/Codex/2026-11-05/wo/outputs/食品DAG-恢复/。凭据/会话只在独立私有tmp，不进证据、Git、PR。
- 生产Java/Web发布尚未开始，按主协调排队；服务级验收与业务验收分开报告。

App清理：仅本组report24059申请撤回39，PENDING，提交者1309；保留0.1kg投入等待另一名主管真实批准，未换账号代批、未SQL删除库存。手机已释放。

最终补强的审批强制执行契约单独复验10/0/0/0，rc0（approval-force-contract.log）；只改测试，生产源码与190例验证时SHA256完全相同。三项新ContractTest进入PR自动执行选择器；真实治理政策5项写入口一律拒绝AI_INTENT_DISPATCH且不降级，此为遵守现有来源策略，不代表已提供完整审批/Agent身份/补偿框架。

## 全组交付映射（当前GitHub32条相关PR均MERGED）

| 有效任务 | 代码交付 | 当前证据边界 |
|---|---|---|
| 出库阻塞诊断 | #1403 #1450 #1455 | 诊断Tool/意图/只读声明已合，不能依旧14号报告重复实现 |
| 交付风险异常闭环 | #1443 | 已合；本次后续统一部署覆盖，未重新虚构业务处置验收 |
| DAG03前端/07标签 | #1423 #1435 #1452 | #1452本轮合并且OTA送达，真机超限/选原因成功/库存及审计回读 |
| 成本损耗异常 | #1444 #1447 | 已合；后续UI修复同样纳入，非只看最初PR |
| DAG14工序间/投料时限 | #1426 #1434 #1442 | 原有效决定全部可配置；历史F006未超时/超时/复检等已有UI证据；不得把#1426财务清单红等同产品失败 |
| Capability/AI写入口契约 | #1440 + 本批代码 | 历史审计已合；本批修证实的缓存、确认参数、权限、审批、治理、未知结果风险；不宣称12项完整治理已达成 |
| 供应商/原料影响 | #1404 | 已合，现有只读能力保留 |
| DAG21单位/称重单位 | #1385 #1410 | 已合；不重复单位修复，硬件真秤接入原裁定后置，不能以种子配置充硬件验收 |
| AI执行网关/缺料结果 | #1407 #1420 + 本批代码 | 已合与本批目标回归；无假成功缓存或写自动重试 |
| DAG09/10标准成本/分摊 | #1383 #1392 #1398 #1406 | 实际交付晚于最初报告，避免重做已合修复 |
| DAG07物料平衡 | #1386 #1423 | 已合，工序名兜底已交付 |
| DAG02追溯可靠性 | #1391 #1397 | 血缘补写与验厂证据包均已交付，旧笔记“未做证据包”已过期 |
| DAG03回料上限 | #1382 #1423 #1435 #1452 | Web/App两条入口已有实现，本轮App真机留痕与负向零写入验证 |
| DAG20 AI关键属性 | #1384 #1396 | 份额/回料/副产/CCP与入口原料份额均已合；新增普通工艺标准参数生成不冒充已实现 |
| DAG15超温批次 | #1388 | 软件仓库级受影响批次与转质检已合；真实传感器采集仍服从后置裁定 |
| DAG16跨客户领料 | #1233 #1239 | 半成品/在制品/成品三条归属闸已合且生产回填历史已执行；本轮3类目标测试复验通过。F006实查20条SFI/17条FG无客户归属库存，未伪造线上跨客户UI成功证据 |

仍需真实人工动作：撤回申请39的另一名主管审批；没有绕过四眼原则或直接删除已提交报工。后续测试/部署/验收回执继续独立记录。

最终独立风险复审：先前P0/P1全部解决，无剩余阻塞，可以commit/PR；源码/测试scope完成，ACTIVE本批临时行撤出并由本归档保留任务ID、Base与完整证据。未提前声称已合并/部署。

候选发布准备完成：统一 release --phase build（31显式类，190/0/0/0，构建rc0）+ YES-STAGE预上传，RELEASE_FINAL_STATUS=built恰好1次，deploy_mode=none，未安装/重启/切流。JAR SHA256 8d387282b1fb41a166118e5befb2c04b706a809e4333501e48c5836d1e5d4c1c，回执 ~/.cache/cretas/release-reports/cretas-1793844464-158.json。
PR #1460 commit c0f8590975；CI脚本闸发现测试改名配套基线需收缩，按pr-test-selection.py --write仅移除旧ConfirmationTest一行（1325→1324），本地selection gate PASS，不改源码/测试，不重跑产品用例。
原任务产品59e35032上限3%已恢复null，仅上限/updatedAt变化；撤回39仍PENDING，审计快照保留。

#1440 P1-6实质遗漏补齐scope：ShortageCaseActionController.java、ShortageRescheduleController.java及两者ContractTest；只修写入服务已成功返回后的核对异常回执。写入失败不得吞错，不改权限、DTO/Entity/Repository/迁移。验收：两ControllerContractTest真实HTTP对照，核对抛错但返回原写入ID/明确未知，原写入409保持失败。候选旧制品不冒充包含这次补齐，需最终重构建。

P1-6 HTTP契约首轮实际11 tests、0 failures/errors/skipped；新增最后一条台账异常用例需单独重编译确认后才升级为最终证据；覆盖采购/重排各自的后置台账异常与核对异常，保留原PO/APPLIED结果并明确交期未确认；写服务失败维持409，跨厂/缺身份/缺理由仍原闸。核心190例生产源码未变，已验证证据复用；最终制品选择器扩大为33显式类（202例预计，以实际回执为准）。

P1-6最终证据收齐：ActionController 7/0/0/0，RescheduleController单类重新编译5/0/0/0（shortage-reschedule-final.log），共12唯一HTTP用例；XML包含两个台账读取异常用例。独立窄风险复审无剩余阻塞，可以commit。只有后置核对异常被承认未知，核心写失败未吞。
