# 追溯与日常质检接线

P1-QUALITY-TRACE-20261016；root DIRECT；Base 3fb00bfa28edc34928d15c6b0b4ecff15928b401。
沿用 codex/trace-analysis-vision-daily-review-20261015。本地体验与业务验证；不 push、PR、合并、部署、生产写入或模型训练。

## 评估与本次切片

盒装批次8已证明供应商→收料→调拨→生产→真实出货；当前暂无正式质检。现有 QualityInspectionService 已提供质检写回与失败库存隔离，销售可售状态闸与 HACCP 闸已存在。HACCP 缺记录仅告警是已有明确业务策略，本次不擅自改为全局强制拦截，也不把可售/已发货称为已经质检放行。

本次复用质检列表/新建表单与原业务 API，接通“追溯→当前批次质检→返回追溯”。核对发现质检百分数原实现已正确，不重复改计算，只明确抽检/生产质量口径；追溯头部区分含不良总产量与合格/不良产出。补录发生在出货后须明确说明，不能充当出货前放行证据。

## scope 与完成条件

- root 独占 TraceabilityDTO.ProductionInfo/QualityInfo 与 TraceabilityServiceImpl 对应只读投影及既有目标测试：当前批次ID、实际good/defect数量、真实抽检数量；不改 Entity/Repository/SQL/迁移/共享鉴权。
- root 独占 web-admin/src/api/traceability.ts、views/traceability/index.vue、traceDisplay.ts 与目标测试；views/quality/inspections/list.vue 与新增目标行为测试：批次上下文/原表单入口/返回、错误显示、抽检口径及PASS/FAIL/CONDITIONAL中文别名。沿用权限store，不能按query切厂，不新建质检引擎或另一个表单。
- scripts/e2e/daily-review 新质量接线验收脚本与必要守卫测试；复用 p0_business_flow.BusinessFlow 的精确loopback、工厂身份及日志约束。只对既有本地合成批次新增显式测试质检/出货安排；无SQL伪造业务流水，不改已有记录时间或清除失败证据。
- 本文与 ACTIVE 仅 root 修改。已有P0数据、历史版本及视觉照片/标注/两个queued训练任务保留。

完成：目标Java行为测试、Web行为测试/类型/构建；实际API/只读数据库确认抽检不改生产良品数与指标、FAIL隔离与出货拒绝/库存无变化、跨厂拒绝；真实UI提交一次标注为出货后补录的本地抽检并回到盒装追溯，截图留档，保留用户页面。

Java编译前停止本任务Java/Python运行时，仅使用现有runtime.py；不动另一任务18091、Web和PG。证据 C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p1/。

## 后续优先级

1. 当前：质检证据与追溯/出货异常接线，复用既有门禁。
2. 随后：按真实工厂需求配置放行规则及异常责任处理，覆盖退货/召回影响面；缺记录是否强制拦截须依据工厂明确规则，不能用通用模型代定。
3. 生产多输出共用投入的成本/质量分摊及新工厂指标自动配置另做独立切片；未证明归属时保持未知。
4. 视觉仍按工厂独立训练/验收，真实照片和独立验收集准备好才启动，不能拿六膳门效果替代。

## 已验证行为与保留证据

- 原质检表的百分数计算正确，直接复用；只补抽样数量/合格数/不合格数与中文口径。PASS/FAIL/CONDITIONAL 已在既有显示映射中补中文别名。
- 盒装批次8：总产量22盒、合格20盒、不良2盒；加工投入10kg→产出20盒；已发10盒。通过真实 Web 的原“新建质检”表单提交5个样本、5合格、0不合格、PASS，备注明确“出货后补录，不作为出货前放行证明”。返回追溯后读回记录；查询不造标签、不改原出货事实。
- 散装批次7：复用正常销售订单/子发运单与批次分配，安排1kg后在原质检API提交2个样本全不合格。原服务将剩余库存隔离为DEFECTIVE；实际仓库确认发货返回409 / FINISHED_GOODS_BATCH_NOT_SALEABLE，逐行数量/预留数不变。测试订单/发运单保留，未实际出货；已发4kg仍为历史事实。
- 测试账号未设姓名。首次18项业务验证中17通过、1项“有姓名”失败；保留 qc-verify-first-fail.log、quality-acceptance-first-fail.json。修复为投影真实 inspectorId，姓名为空时显示编号，不编造姓名；最终按认证userId与数据库记录核对责任人。
- 独立只读复核发现 keep-alive 页面遗留FAIL筛选会隐藏新批次PASS记录；切批次/工厂时清空筛选，并补缓存切换行为测试。返回追溯时重新查询，不展示缓存旧质检。
- 最终 Java selector `TraceabilityServiceImplTest,TraceabilityServiceImplLineageTest,QualityInspectionServiceImplQcQuarantineTest`：14通过、0失败/错误/跳过，java-target-final.log。无Entity/Repository/迁移变更，不声称新增JPA启动或Flyway门禁。
- 最终 Web 4个目标spec：53通过，web-target-frozen.log。Python复用transport守卫与新来源/写授权守卫：6通过、7 subtests，python-guards-frozen.log。真实隔离服务启动日志 runtime-start-final.log；最终类型/构建以 web-build-check-final.log 为准，前面的构建日志只作为增量证据。

截图与全部JSON在上述代码外证据目录。补录没有把抽检100%改写成生产良品率100%；全厂四个指标继续按实际生产事实核算。后续缺记录的硬拦规则、正式放行审批、召回与多输出分摊未在本切片冒签完成。

最终真实业务读回 `qc-verify-final.log` / `quality-acceptance.json`：19项通过，含UI记录真实业务表ID/检验员、A7/8生产数量与良品率逐行不变、B完整指标历史逐行不变、四项指标fresh重算仍90%/90%/13.9256元每kg/501.32元、真实B登录自厂读取成功与跨A403，以及全部视觉任务状态/attempts未变。原FAIL隔离/409证据单列 `failed-shipment-control.json`，未被后续读回覆盖。

最终体验入口仍是 `http://127.0.0.1:31761/production/traceability?batchNumber=PB-PLAN-1792082483014-E2B5C948-83123`，daily_factory_a；向上看供应商/收料/调拨，向下看投入产出、抽检与10盒实际出货。“查看或登记本批次质检”进入既有页面，自动锁定本批次；可通过“返回批次追溯”读回新记录。截图 `04-box-trace-source-final.png` / `05-box-trace-quality-shipment-final.png`；浏览器停在后者位置。

源码/测试11个文件冻结指纹 `24bebccbdfc277277681aa8a8f9e96a0f2afeedd7ebe0511ea859480eeeba1cc`，逐文件SHA-256见source-freeze.json；最终业务读回后复核无变化。Java/Web修复后的目标测试、Python守卫与真实UI/API/数据库证据分别记录，不把本地验收称为CI/生产/用户验收。独立风险复核在缓存筛选修复后无剩余源码阻断。当前状态review，等待用户体验；不push/PR/合并/部署。

最终冻结后 `npm run build:check`（vue-tsc -b && vite build）退出0，构建日志 web-build-check-final.log；不因本文或ACTIVE收尾再跑产品测试。dispatch-active（62条、scope10、全部≤300字）与git diff --check通过。所有实现与验收仅保留在独立本地分支，最终HEAD与分类回执见代码外 final-receipt.json。
