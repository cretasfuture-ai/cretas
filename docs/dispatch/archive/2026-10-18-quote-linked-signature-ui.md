# QUOTE-SIGNATURE-20261018

Owner：root。Base：57b442017536628356984307553318de3978ddc6。分支：codex/quote-signature-ui-20261018。

复用本机已发货报价订单及原测试OSS。真实文件上传、签收后发现凭证无法回看，补只读查看与受同厂/实体读权限保护的JSON临时链接。上传为明确标注TEST ONLY的合成凭证。真实UI刷新、放大及文件字节SHA-256一致；仅GET查看；母子DELIVERED、订单COMPLETED/运输RECEIVED，库存23个及唯一应收/待开票124.00保持不变，他厂和其他单据控制不变。

代码scope仅AttachmentController、api/attachment.ts、sales/orders及对应测试/验收文档。无Entity/Repository/迁移/Security配置或签收/库存业务变更。Java55通过、有效权限变异断言红/原字节恢复4通过；前端13及最终调用点4通过；类型检查/构建、选择器5通过；独立窄风险复核条件已补齐。完整映射见[验收简报](../../acceptance/quote-linked-order-signature-ui.md)。

源码与真实验收已冻结，释放代码scope；最终CI和手动合并由本协调者收尾，head、CI与merge状态以关联PR记录为准，不启动新切片。未碰生产，未做正式开票审核、出具或收款。
