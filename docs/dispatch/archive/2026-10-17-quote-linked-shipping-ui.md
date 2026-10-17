# QUOTE-SHIPPING-20261017

Base：a28f52ca309aeb46644b0501e09d83f8bb743e7a。Owner：root。分支：codex/quote-shipping-ui-20261017。

本机隔离新厂复用报价关联订单，真实完成发货安排与仓库出库2个，库存25→23，订单完成、母/子单已发货，应收和自动开票待审核金额均124.00，净额109.73、税14.27。修复发货预览、应收、自动开票净价反算损失0.01；旧NULL快照保留旧公式。无迁移/Entity/Repository/权限改动，无生产操作。

scope仅两个服务、sales/orders金额及对应测试/验收文档。相关Java41通过、前端22通过、类型检查/构建、Python16通过2跳过、选择器5通过；两项有效变异断言红、原字节恢复后8通过；独立风险复核通过。完整映射见[验收简报](../../acceptance/quote-linked-order-shipping-ui.md)。最终CI、head和手动合并以本分支关联PR记录为准。实现/本地UI已冻结，释放代码scope；最终CI/合并由本协调者收尾，不启动新业务切片。
