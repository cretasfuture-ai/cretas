# P17 追溯实际出货时间

任务P17-TRACE-SHIPMENT-TIME-20261017，Owner root，Base c4c6cfa6f0630a2e0a12d0264763909d22267f46。沿用codex/trace-analysis-vision-daily-review-20261015独立分支，只在本地10064/31764及p14_new_factory_20261016验收，不push/PR/合并/部署。

P16页面验收已恢复：用户手动离开浏览器data协议错误页后，原浏览器会话可复用；页面为新厂双SKU体验厂、P14验收联系人，盒装17批显示原始FAIL(5/2/3、40%)、另人新厂质检复核员条件放行已批准(2026-10-16 23:49:51)、已发货10盒。截图/可访问树在代码外factory-data-p17/trace-before-date-fix.*，未改业务数据。

发现真实展示口径问题：现有TraceabilityService将SalesDeliveryRecord.deliveryDate计划交付日期投影为shipmentDate，实际00:00确认出货的17批页面仍显示前一天10月16日。SalesDeliveryRecord.updatedAt还会在签收等操作变更，不能拿最后修改时间填补实际出货事实。

scope仅TraceabilityServiceImpl、TraceabilityDTO.ShipmentInfo、既有TraceabilityServiceImplTest、Web traceability API类型/页面/已有挂载测试及本档案/ACTIVE。复用既有BatchLineageEdgeRepository按本厂/目标出货单查询与FINISHED_TO_SHIPMENT.eventTime；同时校验源成品批次和sales-delivery carrier，保留计划交付日独立字段。无时间证据明确未留存，不以updatedAt/createdAt或计划日期猜测；旧ShipmentRecord仅保留其已有日期精度。无新增Entity、Repository查询、迁移或审批/出货引擎。

完成条件：先复现跨午夜计划日!=实际流水时间的反例，再修复并覆盖跨厂/错误批次/错误carrier/无时间的阴性对照与旧记录日期兼容；目标Java和Web测试/构建通过后使用独立classes恢复同一克隆，HTTP/PG/UI读回三条记录及实际出货时间一致，未再提交审批/出货、库存与应收不变。最终停在盒装追溯页面供用户体验。

修复复用既有本厂BatchLineageEdgeRepository.findByFactoryIdAndTargetIdAndTargetType，没有新增查询、Entity或迁移。实际时间只取匹配FINISHED_TO_SHIPMENT、FINISHED_BATCH、目标SHIPMENT_RECORD、源成品ID及shipmentCarrier=SALES_DELIVERY_RECORD的eventTime；按出货单缓存读数，同一出货单不重复查询。缺少证据时实际时间为空且source=UNRECORDED；旧ShipmentRecord保留原日期，source=LEGACY_DATE_ONLY。DTO额外返回plannedDeliveryDate、shippedAt、shipmentTimeSource，既有shipmentDate语义修正为实际日期；Web并列“实际出货时间 / 计划交付日期”，不把计划日、创建或修改时间补成实际时间。App既有shipmentDate字符串直接展示无日期解析/写入，暂无App新增时间列或真机验收。

行为反例：冻结P16旧main classes下运行新Java反例，20项中6项断言失败、0错误（实际17日被旧逻辑输出16日；缺证据被旧逻辑输出计划16日）；Web旧实现2项失败/39项通过，失败指向缺“实际出货时间”和“未留存实际时间”。修复后同三Java选择器20项通过，完整编译4697主源码，2026-10-17 08:05:05 BUILD SUCCESS；47项Web挂载测试通过。旧P16的59项审批/回滚门禁源码不变，直接复用其通过证据，不重演已完成审批/出货。

本地证据在`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p17/`：existing-shipment-time-source.json确认既有出货血缘流水event_time为2026-10-17 00:00:21.517081；business-before.json保存两批生产/库存版本、出货单、应收、原始质检和审批行；trace-before.json保存旧API日期16日，UI截图同样复现。真值查询最初对数量列的两次猜测失败，随后按Entity的quantity_used更正后读取成功，SQL写入始终0；不将这些取证失败算产品失败。

最终验证完成：`npm run build:check`类型检查和构建退出0（web-build.log）。P17独立classes在本地同一克隆启动，Java业务health/Web HTTP200；`business_readback.py after`真实认证FOOD_3101_001/id2/food_3101_001_admin/factory_super_admin，trace-after.json的shipmentDate=2026-10-17、plannedDeliveryDate=2026-10-16、shippedAt=2026-10-17T00:00:21.517081，与PG血缘event_time逐字一致，quantity=10盒、SHIPPED，原质检FAIL保留且审批时间早于出货。两批生产/库存版本、出货单、应收、质检和审批行的business-after.json与before逐值相等，业务写入0。

最终真页面已验：新厂账号同一浏览器会话刷新盒装追溯，断言新厂名称、原始不合格5/2/3、另人复核员、已批准2026-10-16 23:49:51、实际出货2026-10-17 00:00:21、计划交付16日、10盒及已发货均在当前页面可访问树中；重新加载后的console error为0。保存trace-final-ui.txt、trace-final-full.png、trace-final-visible.png与trace-final-console.json，并目验截图。浏览器保留在盒装追溯且滚动到复核/质检/出货三块记录；无需再手工离开错误页。没有重复审批、出货或补录业务，未push/PR/合并/部署；本地Java目标20、Web目标47及build通过，P16昂贵审批/回滚测试无改动直接复用。
