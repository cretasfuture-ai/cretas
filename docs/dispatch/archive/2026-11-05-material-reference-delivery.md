# ARTIFACT-MATERIAL-DELIVERY-20261105

ARTIFACT-MATERIAL-CI-FIXTURES-20261106：Base09555864bf，Owner food/root。687必需CI实际失败：BareUnit发现Replacer两处新增裸单位比较，root唯一修Replacer及其窄单位语义测试（不增baseline）；merge_executor仅service/intent的AiPurchaseTaskGrantServiceContractTest、DeferredPurchaseGrantPostgresLifecycleContractTest、ControlledPurchaseScopeBoundaryContractTest、PurchaseGrantTimerPostgresLifecycleContractTest四fixture；param_executor仅inventory/impl的PurchaseDraftQuoteFillContractTest、PurchaseSubmitPriceBackfillContractTest两fixture。角色、撤权、消耗、幂等和并发断言保留；不改Supplier/原权限矩阵/阈值/共享helper。root统一真PG/失败七类、风险/最终官方构建、CI与发布；源级fixture无证明则回报，不假定线上缺陷。

Owner food/root；Base 09555864bf513a87285c96f7fd6b137dd164f150；branch codex/material-reference-delivery-20261105。

接手未交付物料自动替换1439，在同一合并事务里重写未生效的Workflow/开放PO与供应关系，已发布或已收货引用继续阻断。新引用写入口共同参与物料锁，锁后读取fresh scalar，防一级缓存把已停用/合并的物料重新写回。既有Workflow/PO先锁owner再按序锁raw；供应关系/spec按审计冻结raw→刷新parent→spec，不反取Workflow/PO。

开放PO同时保持supplierMaterialId、采购spec与原料包装spec身份一致；主关系停用碰撞保留同一最终关系，包装规格必须唯一商业等价，未税/含税/币种不同或无唯一映射则整事务拒绝。历史订单/收货不回写；无PO未完成收货阻断合并。采购copy/quote/approval、Workflow raw-owner/save/publish均封口。

Supplier源码精确来自审计31a3eb0287、731c05ee09、323f1db35c，食品未独立改其实现。helper实际签名lockActive(String, Collection<String>)，eb908e的Guard+RawRepo两文件契约不变。

本组组合源e2a576686e的最终真实PG19+409Hint5=24/0/0/0，其他相同内容目标与Supplier的PG11/两unit15、RawRepo启动1已通过；隔离后34文件逐字等价。完整官方24类build/stage、最终CI、合并、生产部署、F006 UI业务验收仍分别记录在PR与外部回执，不把源码/旧目标证据升级成部署。无DDL/Entity改动，Repository有真实JPA与PG门禁。原1439在新交付合并后才关闭为superseded，原分支/worktree保留。

2026-11-06补充唯一scope：ARTIFACT-MATERIAL-WORKFLOW-AUTH-20261106，Base5bef，Owner food/merge_executor。新增MaterialMergeWorkflowWriteGuard、MaterialAliasMergeService finalPlan写前接线及对应Contract/真实PG权限负例/必要fixture；正常生产写角色+production RW、当前可信actor、owner与全部graph SKU的ProcessSecret EDIT，解析失败allProtected。沿用既有ProcessSecretWorkflowOutputs，读预检产品名例外不扩大；不改Supplier/其他组/权限矩阵/Repo新查询。root统一目标、JPA、风险及发布。

最终发布基准更新为 eacf526cf33daa707f4f45f86eae471d076cf4a5（已本地汇合1484主线，未改其功能）。新增权限P1现已实现当前DB角色/平台身份复核、正常Workflow生产写权限和owner+graph全部产品EDIT，解析失败全保密检查；新Contract与真实PG六项事务零写负例已纳入最终官方选择器，尚未执行不得称通过。新写Guard无额外锁，保持原锁序。

2026-11-06最终集成：当前Base09555864bf513a87285c96f7fd6b137dd164f150（原始Base5bef，已依次汇合1484/1477/1486/1485）；SOP兼容层来自正式主线，不重做其实现。共享PG选择器严格保留最新main前缀，只追加Material/Supplier两项；fast的SOP Spring类保留，pr-test-selection.py选1083/基线未增并PASS。

额外角色/production RW/production_plan模块enabled与显式账号DENY封口源码2b51经独立复核clear。官方25类首次因测试fixture失败（502秒）完整保留；无产品改动，修真实持久化User ID/请求上下文、created_by外键、RAW owner anchor及严格OWNER/OUTPUT mock。最终失败三类分两次窄复验：真实PG27+Guard16=43通过，Alias7通过，合计50/0/0/0，非一次组合运行；源码hash/命令/log绑定外部material-auth-fixture-final-target-proof.json。未弱化锁等待/optimistic 409/权限/外键或扩大baseline。Web此前官方80秒成功，树未变应由最终入口校验复用。最终25类可信JAR/build/stage、最新head required CI、合并、部署及F006物料业务验收仍待各自完成；本次物料无DDL，不把已合SOP94算本次新迁移。

2026-11-06 CI七类修复冻结：Replacer按真实UnitContract.storageUnit比较别名身份，bag/袋、box/盒、case/箱与公斤/kg允许；只/个/件及kg/g拒绝，不隐式改数量、价格、计价单位或换算。原料包装与供应规格均要求唯一等价；Supplier文件未改。六个既有fixture补真实排序锁/fresh状态投影、锁PO header及spec relation，不降断言。开发验证分次：BareUnit5、AiGrant15、Quote7、Submit4、Deferred25、Controlled6、Timer22通过；Material PG34中30先通过、4个新负例补齐数据库必填fixture后最终4/0/0/0通过，私有PG均finally stop0。旧687 CI确为失败，旧212制品不能背书新源码；本次需最新main组合官方32类构建及新head必需CI。
