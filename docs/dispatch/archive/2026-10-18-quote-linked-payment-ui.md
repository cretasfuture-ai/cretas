# QUOTE-PAYMENT-20261018

Base：`5d1f18cfaeeb400bbe88c332d2e97b997873ab03`。Owner：root。分支：`codex/quote-payment-ui-20261018`。

复用已开票124元订单，通过真实UI完成110.01元首笔和13.99元余款的登记、财务确认及刷新回读。发现并修复未税金额被当作收款上限/结清依据和浮点余款风险。订单PAID、应收核销为0，客户余额仅冲减124，两张收款单、两笔AR_PAYMENT及两条核销分配；库存、发票、其他订单和跨厂内容不变。124.01元页面阻断、0POST；未确认时余额保持不变。

scope：PaymentRecordServiceImpl、ArApServiceImpl收款结清判断、SalesOrder派生状态、订单/财务收款UI及目标测试、验收说明。未改映射、Repository、迁移、权限代码或生产；本机专用财务账号通过UI配置。Scope随本次归档释放，最终CI与手动合并以关联PR和私有交付回执为准，不宣称未完成CI已通过。

证据：[收款验收与映射](../../acceptance/quote-linked-order-payment-ui.md)。本地Java30+9通过，前端4通过、类型/构建通过、选择器5通过。旧代码8项中5个断言失败/0错误。资金风险独立复核已关闭；开发不跑本地全量，冻结后必需CI一次。未验证真实银行、生产和并发；无生产写入。
