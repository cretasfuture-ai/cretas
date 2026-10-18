# QUOTE-INVOICE-20261018

Owner：root。Base：d597a3b78bd502fc7703e2e6cf94f7d7eeeafa25。分支：codex/quote-invoice-ui-20261018。

复用已签收报价订单和唯一待审核开票记录，真实订单页审核、上传合成TEST ONLY PDF、应用登记出具与刷新下载通过。未税109.73+税14.27=124.00，订单已开票124.00/FULLY_INVOICED；唯一应收124.00、库存23个，其他业务及他厂控制不变。

修复实际私有PDF下载AccessDenied：InvoiceController新增同厂/源订单行级范围校验、ISSUED/PDF校验、300秒短链/no-store，订单和财务页复用InvoicePdfDownload。无审核/出具/金额/库存写逻辑、Entity、Repository查询、迁移或权限策略变更。构造器3处测试适配。

相关Java16通过，移除新源订单范围检查的有效变异1断言失败/0错误，原class恢复后10通过；前端6、类型检查、构建、选择器5通过，独立窄风险复核无剩余阻断。UI浏览器GET200、PDF哈希一致，跨厂403/匿名401。销售测试角色当前ALL，未当作受限拒权证据；财务路由当前账号403，实际操作在销售订单页。OCR未配置、实际税务出票/收款/重开/并发出具未验。完整映射见[验收简报](../../acceptance/quote-linked-order-invoice-ui.md)。

源码/测试与验收已冻结，代码scope释放；最终CI与手动合并由本协调者收尾，head与merge状态以关联PR和私有最终回执为准，不启动新切片。未碰生产。
