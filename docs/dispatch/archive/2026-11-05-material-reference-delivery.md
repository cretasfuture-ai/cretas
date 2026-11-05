# ARTIFACT-MATERIAL-DELIVERY-20261105

Owner food/root；Base 5bef001c9256efad02cd59361822184dfd24678f；branch codex/material-reference-delivery-20261105。

接手未交付物料自动替换1439，在同一合并事务里重写未生效的Workflow/开放PO与供应关系，已发布或已收货引用继续阻断。新引用写入口共同参与物料锁，锁后读取fresh scalar，防一级缓存把已停用/合并的物料重新写回。既有Workflow/PO先锁owner再按序锁raw；供应关系/spec按审计冻结raw→刷新parent→spec，不反取Workflow/PO。

开放PO同时保持supplierMaterialId、采购spec与原料包装spec身份一致；主关系停用碰撞保留同一最终关系，包装规格必须唯一商业等价，未税/含税/币种不同或无唯一映射则整事务拒绝。历史订单/收货不回写；无PO未完成收货阻断合并。采购copy/quote/approval、Workflow raw-owner/save/publish均封口。

Supplier源码精确来自审计31a3eb0287、731c05ee09、323f1db35c，食品未独立改其实现。helper实际签名lockActive(String, Collection<String>)，eb908e的Guard+RawRepo两文件契约不变。

本组组合源e2a576686e的最终真实PG19+409Hint5=24/0/0/0，其他相同内容目标与Supplier的PG11/两unit15、RawRepo启动1已通过；隔离后34文件逐字等价。完整官方24类build/stage、最终CI、合并、生产部署、F006 UI业务验收仍分别记录在PR与外部回执，不把源码/旧目标证据升级成部署。无DDL/Entity改动，Repository有真实JPA与PG门禁。原1439在新交付合并后才关闭为superseded，原分支/worktree保留。
