# 新厂出货前质检、隔离、复核放行与出货

任务 P16-PRESHIP-QC-20261016，Owner root，Base `9ef5e1f50b6fe69d0f7b78a1f12a8407e10bb790`。沿用独立分支，只有本地验收授权，不push/合并/部署。原P15已本地完成并释放执行scope，证据在对应盒装追溯归档，不重演其14/15批次。

初始scope：`scripts/e2e/daily-review/new-factory/` 内可复用的前置质检验收helper及相关档案/ACTIVE；隔离runtime10064/31764、库p14_new_factory_20261016、工厂FOOD_3101_001。沿用P0生产、现有采购库存和新厂运输helper，另建正常对照/异常两个生产批次；不以SQL写业务样本，不改原14/15批次、退款与训练线。发现产品缺口后再明确扩展到必要文件与目标测试。

完成条件：新厂真实账号通过正式接口/页面登记出货前质检；不合格批次库存隔离并拦截已分配发货，失败后库存、单据、应收、开票不变；本厂独立审批账号复核条件放行，未审批与自审批不得解隔离，拒绝重放；最终库存、出货、追溯与审计和先后时间读回一致。保留原始检验结论，不能把出货后补录当出货前放行；若展示/接口有问题直接窄修复，必要门禁通过后本地提交。

真实UI发现首个产品问题：抽样5后依次修改合格1→4，自动补出的不合格4变成固定值、反过来把抽样数改成8，原因为onPassChange/FailChange先重置lastEdited再判断它；保存现场截图，尚未提交错误质检。root scope扩展到 `web-admin/src/views/quality/inspections/list.vue` 与原挂载 `__tests__/batchTraceEntry.spec.ts`：保持已知抽样数，修改合格/不合格时只平衡另一结果；清空不作为0、超量仍阻断。使用项目原表单和规范，不新建质检器。

独立只读复核定位并经用户点名授权修复两项P0：FAIL记录可选择无需审批的RELEASE绕过复核；审批行先读PENDING但无互斥，批准/拒绝并发可覆盖，甚至多个同批次待审批动作冲突。root scope扩展至既有 QualityDispositionRuleServiceImpl / QualityDispositionController、`service/quality/QualityDispositionDecisionGuard` 和对应目标/真实JPA测试。复用现成 ProductionBatchRepository.findByIdAndFactoryIdForUpdate，统一批次决策互斥；锁后refresh审批实体，保留原始FAIL，直接放行必须真实PASS且达95%；同批次已有PENDING或同次质检最终处置时拒绝重复申请。无新增Entity、Repository查询或迁移。验收包含真实JPA事务锁、真实PG并发HTTP与失败不变读回。

用户追加明确要求审批、库存、追溯一致；原始FAIL不会被放行决定篡改。root追加范围为现有quality-disposition API的history类型/客户端、traceability中的复核历史只读展示与挂载测试，复用后端已有GET history/{batchId}，展示独立审批状态/人/时间，与原抽检及实际出货并列。没有另建审批或追溯引擎。

独立终审又定位同类绕过：旧PASS可直接RELEASE覆盖同批次更新FAIL。新增真实JPA反例先证实旧实现不抛异常（stale-pass-red.log：1 failure、0 error），再将“只可基于最新质检发起/批准处置”抽为共享断言，同时接到新申请和审批入口；不能借旧合格记录解开新不合格隔离。

本地证据目录：`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p16/`。使用工厂FOOD_3101_001的管理员id2和另人quality_manager id5，真实UI登记正常16批PASS(5/5/0)、异常17批FAIL(5/2/3)；原14/15批不动。原5/4/1的FAIL提交被现有阈值校验拒绝，未落业务记录；改用符合既有判定规则的合成数据，没有放宽阈值。

已取得真实PG/HTTP阴性证据：不合格库存DEFECTIVE时出货409，待审批仍409，自审批403；FAIL直接RELEASE和冲突重复申请409；对17批的库存AVAILABLE更新施加仅限本地克隆的临时CHECK，审批409后审批行、库存、出货、应收、开票均回到原状，finally已删除CHECK（approval-failure-rollback.json）。业务样本全部走现有接口，SQL业务写入0。

实际批准/驳回竞争中，17批一次200、一次409，批准人id5，原FAIL保留。第一次监测依赖pg_stat_activity.query，隔离数据库账号无其他会话SQL文本权限，取证计数错误为0，脚本明确失败而非冒充通过；保留日志，不重放已成功的审批。改用pg_locks后在正常对照16批创建独立条件放行申请，捕获同批次锁上两个真实HTTP等待者，返回409/200，最终REJECTED且库存/单据/应收/开票完全未变（concurrent-approve-reject.json）。该补验不改变17批批准结果，16批原PASS仍保留。

浏览器恢复限制：测试服务先前退出，已在专用端口10064/18096/31764恢复，克隆库和既有数据保留；本机Web HTTP200。原体验标签停在data协议的“无法访问”错误页，自动恢复及用户要求重试均被浏览器URL安全策略拦截，未改策略、未换浏览器绕过。最终新增追溯复核展示仍须页面实际验收；不能把mounted测试/API数据当作已完成UI目验。

范围外后续：客户端传入的审批展示名仍是既有接口字段，身份权限及本次验收以服务端认证id、同厂users和审计approver_id交叉核对；未在本批扩大重构全部审批身份显示。原历史Entity/迁移兼容和正式生产发布/CI也没有被本地验收替代。

最终源码目标门禁：`java-final-accepted.log`，完整编译4697主源码，6个测试选择器共59项，0失败/0错误/0跳过，2026-10-16 23:56:51 BUILD SUCCESS；含真实Hibernate/JPA上下文和真实事务竞争。临时派生POM仅重定向独立build及已有testIncludes，不改根POM；验收后删除临时文件，私有证据保留派生副本。前端`web-final-target.log`为5文件66项通过，`web-accepted-build.log`为`npm run build:check`通过。Python验收helper已py_compile。无新Repository/Entity/迁移；原真实JPA与PG证据并存，不以Mockito替代事务证据。独立只读终审当前修复切片SCOPED_PASS。

最终运行时加载已通过门禁的P16独立classes后，业务health与Web均HTTP200，再次在16批新建独立并发对照申请：两数据库锁等待者、409/200、最终REJECTED，库存及其他业务单据不变（concurrent-approve-reject-final.json）。17批仍为id5对id2申请的有效条件放行，重放审批409。之后才通过正常仓库接口确认16批4kg、17批10盒真实本地模拟出货；helper先从数据库核对质检和另人批准存在、质检早于批准、尚未出货，再发送确认，未用出货后补录替代放行。

`final-business-acceptance.json`为LOCAL_BUSINESS_ACCEPTANCE_PASS：API完整追溯保留16批PASS与17批FAIL，17批复核历史已批准；数据库中原始抽样5/2/3和检验员id2未改，审批人同厂quality_manager id5；质检created_at < approved_at < 实际SHIPMENT updated_at。17批生产20盒、实际出货10盒、库存余10盒、预留0；16批生产8kg、出货4kg、库存余4kg。分配来源、数量与实际出货一致；正常/异常订单分别生成120元/150元应收和REQUESTED模拟开票记录，未真实开票。抽检合格率不覆盖实际产出8/1kg、20/2盒；原14/15批与库存逐行等于前置基线，临时故障约束不存在。最终只读SQL联查因存量delivery_item_id为varchar而主键为bigint，第一次显式失败；修正取证查询为id::text后复核通过，未改业务数据。

演练在午夜自然跨日：质检、审批为2026-10-16，最终出货为2026-10-17真实系统时间；未改时钟、未回填出货时间。最终页面目验仍受上述浏览器策略阻塞，API/PG通过不能写成页面验收通过。体验入口：`http://127.0.0.1:31764/production/traceability?batchNumber=PB-PLAN-1792162905579-2675C830-5661`。后续先人工打开该页核对“原始不合格 / 条件放行已批准 / 实际出货10盒”；本批不push、不开PR、不合并、不部署。

2026-10-17补验：用户手动打开正常追溯地址后浏览器连接恢复，P17已完成原始不合格、另人条件放行已批准、出货10盒的真实页面核验；并修正计划交付日被误显示为出货日的问题，实际时间复用既有出货血缘流水。后续时间口径、最终UI截图、20Java/47Web+build与前后业务不变证据见[2026-10-17-trace-shipment-time.md](2026-10-17-trace-shipment-time.md)。此前“UI待验/策略阻塞”是当时事实，本次已解除；P16审批、库存及原始质检未重写。
