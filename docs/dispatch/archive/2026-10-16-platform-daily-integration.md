# 平台建厂与工厂日常能力收口及多SKU证据接线

任务 P13-DAILY-INTEGRATION-20261016；Base ad90a085828d8a18be7a2977a4e547b9de02cf5c；日常worktree C:/Users/Steve/cretas-analysis-provenance-20261015，原独立分支保留。root为台账/浏览器/运行时/私有DB唯一Owner。用户要求退款先收口，对照建厂共享代码及功能，梳理整体架构和使用顺序，再优先接通多SKU追溯与经营分析；不合并部署。

前批P0-P12收口为本地review，代码和全部archive/私有证据保留：P12原凭证V0044/红字V0045闭环UI/HTTP/PG通过、98+6目标及构建通过；存量质量菜单断言1失败明确保留。不继续扩展现金退款业务。ACTIVE仅合并本任务已有6条root记录，不改变他人任务或宣称merged。

只读比较冻结：共同祖先095ce82d2fc357fa415ea55f8612579cf011bbb1；fresh origin/main b45399188f805a531421943526084284164a8a48；建厂worktree同HEAD但采购退货并发dirty，归另一Chat，不改、不复制未提交实现、不重验其采购业务。主仓拉取只更新refs，不切换/合并/变基本分支。

root scope：本archive/ACTIVE、docs/architecture/platform-factory-daily-flow.md；web-admin/src/views/traceability/index.vue及其__tests__/index.spec.ts；私有只读代码对照/数据库回读/浏览器证据。复用原traceability和批次详情，补同页query/工厂/身份/异步失效保护及明确投入产出/成本跳转。无Java/Entity/Repository/迁移修改。

P13-METRIC-EVIDENCE-UI；Owner metric_evidence；Base同上；scope仅web-admin/src/views/indicator-center/IndicatorCenterDashboard.vue、IndicatorDetailDrawer.vue、__tests__/IndicatorCenterDashboard.spec.ts、__tests__/IndicatorDetailDrawer.spec.ts、新FinishedBatchEvidence.vue及其目标spec、同目录仅必要局部类型/helper。使用现有生产完工批次GET，禁止新核算公式/后端/权限/依赖/共享API修改。目标Vitest仅这些spec；root统一最后typecheck/build与真实daily_factory_a UI/HTTP/PG验收。root不并写这些文件；不改其他Owner或建厂worktree，不启Maven/服务/DB/浏览器，不改台账，不提交。

完成标准：退款证据冻结；交付已存在/同一实现/差异待集成/未接通矩阵及平台与日常边界、入口和使用顺序；经营指标按同一日期查看、完工批次按真实时间筛选并保留原单位，无资料不造零/猜值；复用追溯/批次明细；不同批次乱序和账号切换旧结果不显示。目标测试、必要Web构建、真实本地双SKU/空期间/错误对照，保留审计证据。平台迁移/生产集成、采购Owner范围、多产出分摊和模型泛化不算本批已验收。

## 实际交付与能力对照

整体逻辑、架构图、角色入口、7步使用顺序和已有/共享/待集成/未接通/P0-P2清单见 `docs/architecture/platform-factory-daily-flow.md`。两次fresh fetch的主仓均为b45399188f；本分支不合并、不变基。对照的502个双方改过的backend/web-admin代码及测试文件中376个blob相同、126个不同，不能把差异数量说成重复功能数。重点共享点为退货服务、平台生成网关、路由菜单、Java/Python桥接；日常新增视觉代理/准备链不在主仓，是尚未集成，非平台Owner删除。采购侧dirty未复制、未重写、未代签完成。

实际接线沿用既有API：指标列表/当前值/重算/版本；生产完工批次GET `/production/batches/finished?days=90`；追溯full以及已有批次详情。未写新的指标计算、成本分摊、血缘或训练引擎；无Java/Entity/Repository/迁移/依赖修改，无Maven重跑。最终所有前端文件由root接管并冻结，原worker不得继续写同scope。

- 经营指标可选今天/本月/自定义；四项实际流指标和初次打开的抽屉沿用同一日期；其他指标明确沿用各自口径。空/非法日期不回退今天、不保留旧值；读取失败可见。
- 完工批次按真实完工日期和成品名称查找，保留kg/盒原单位，不做跨单位加总。完工列表只作事实入口，不宣称指标精确分母；最近90天不足完整覆盖时明确阻断。返回结构、缺日期/数量、原始身份重复均可见；重复身份即使伴随异常字段也全部排除，不给误导链接。
- 追溯按已读回正整数batchId和真实生产权限进入投入产出/成本；同页换批次、工厂或账号变化、keep-alive失活、旧响应晚到都不能覆盖当前结果。
- today/month日期在刷新、重新激活和重算前同步真实日历日，覆盖跨午夜及月末；重算POST的单飞锁只由该POST结束释放，关闭重开或切账号不释放，不因界面失效重复写版本。该保护为当前组件生命周期内，不替代后端跨设备幂等。

## 本地验收

继续复用Java10062/Python18094/Web31762与私有PG55447/p10_refund_http_20261016；不改主机时间、测试时钟、后端制品、源库或训练任务。身份为daily_factory_a。浏览器无mock、认证注入或SQL业务写入。

- 真API读取2026-10-16：实际出成率87.6543%、净重良品率94.6667%、完整单位成本14.5911元/kg、完整成本1035.97元，均来自actual-flow-v2。本地后续异常演练新增了批次，不能沿用早期仅四批时的90%/501.32当作当前期总值。本期完工事实列表11条，不作为指标分母证明。
- 页面选择同日，展示四值与两种SKU/单位；按盒装筛选，通过精确批次行进入追溯，再进入既有批次8明细；10kg投料、20盒合格产出、2盒不良、10盒实际出货、128.33元成本可对应。散装同页切换批次7，10kg→8kg、1kg不良、实际发4kg，无旧盒装覆盖。
- 真页面强制重算实际出成率：PG版本64→65，仅新增1条对应期间/来源快照；原有版本逐行不变。payment_requests/ar_ap_transactions/vouchers/voucher_entries/customers五表逐行不变，退款仍CANCELLED、客户余额525；退款线未重演或扩展。
- 空期间2026-10-01显示NO_DATA/暂无业务数据，完工列表无本期记录；无效书签2026-02-31明确要求有效日期，未显示旧值或回退今天。跨厂/账号、异常接口、90天边界、旧请求等负控由挂载测试覆盖；本轮真实浏览器只用A账号，不冒称多角色浏览器验收。

私有证据目录 `C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p13/`：shared-code-audit.json、business-before.json、before/after-recompute.json、source-frozen.json与各阶段截图/可访问树。read_business.py、observe_recompute.py仅普通登录/GET与只读SQL，退款及资金没有新业务写入。未对源库执行恢复、补边或清理。

## 验证状态

- 目标5文件93测试通过（42指标/面板 + 51追溯）；`factory-data-p13-accepted-target.log`。新增追溯四项先真实断言失败再修复；该红日志保留，不把单纯挂载/编译失败当产品变异。
- 初步新增测试的树组件浅桩缺loadTree导致2个用例工具失败，已补正确组件mock，未用生产fallback或削弱断言；原日志factory-data-p13-review-final-fixes.log保留。
- 独立只读终审的重复身份、跨日日期和重算期间锁问题均修正后SCOPED PASS。首次构建在类型检查阶段由root精确停止自有进程，为最终修复让路；记录主动中止，不当产品失败，不触碰Vite本地服务。最终冻结后只跑一轮必要target/build。
- 前批菜单全文件存量质量分组断言1失败证据沿用P12，未改相关源码，不宣称全套仓库测试全绿；本轮不重复已通过的退款/Maven/GPU门禁。

最终 `npm run build:check`（vue-tsc -b && vite build）退出0，Vite1分43秒，日志 `factory-data-p13-accepted-build.log`。Git diff --check / dispatch checker通过；两个新文件仅归一化行尾，已机器证明decoded lines逐行不变，不重复无语义变化的产品测试；9个前端源码/测试最终hash与source-frozen.json一致。结构化 acceptance-receipt.json 保存93测试、构建、UI/PG、源版本、未完成边界与最终本地commit/干净工作区回读。

浏览器以daily_factory_a停在 `http://127.0.0.1:31762/analytics/indicators?periodStart=2026-10-16&periodEnd=2026-10-16`，可按成品筛选、由批次行查看追溯，再查看投入产出与成本；点击指标看同期间口径/重算/计算记录。仍保持独立分支、仅本地验收，未push/PR/merge/deploy，用户本人体验及合并后的整体新厂链验收仍待进行。
