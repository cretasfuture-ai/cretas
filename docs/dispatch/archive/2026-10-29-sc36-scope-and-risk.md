# SC36 采购库存联合与可信数量详情 scope

- ID：`SC36-PURCHASE-INVENTORY-20261029`；Base：`0655f609d365278261a3f5980e1ccd4479170f85`；协调者 Root，唯一代码 Owner `/root/wip_mc_executor`。
- 允许范围均在 `backend/python/smartbi/services/factory_charts/`：`trusted_business_sources.py`、`engine.py`、`conversation.py`、`details.py`、`purchase_inventory.py`、`test_purchase_inventory*.py`、`test_joint_planner.py`、`test_wip_plan_flow.py`、已有 trusted source/metric 单测。若必须修改 `detail_store.py`，先回 Root 说明具体原因，不自行扩大。
- 目的：复用既有已确认采购批次双向追溯，增加当前批次库存构成可选图，保留原 required 采购图；核对库存入库/已用/预留/可用数量及合法零余额，期间限定收货日期，库存是查询时点而非历史期末。
- 详情只为精确的可信 purchase.inventory / wip-plan-flow family 增加固定、非金额的凭证与数量字段；保留原价格列拒绝、owner/tenant/role/TTL/256/32MiB/8MiB 规则，不用候选自报任意字段覆盖权限。移除两个新候选导致混单位的 bypass policy，让既有 unit scope 生效。
- 原始批次 ID 必须非空且唯一，拒绝一批被多收货行重复计数；同单不同批可保留。业务 domain 用现有“库存”，不造“采购库存”混合分类。
- 验收：真实 PostgreSQL factory/状态/软删/来源/包装快照/重复批次/零与负库存矩阵；完整 engine 保留 required、可选开关；精确联合 planner；local/shared 详情混单位隔离、凭证和数量可追溯、can_price=False 无金额；在实际 backend/python cwd 运行并保存命令/退出码。最后由 Root 跑冻结全套与独立风险终审。
- 禁止：迁移、Java/Entity/Repository、全局配置、生产业务写入、提交推送、MCP/ACTIVE 写入及孙代理。

初审原始 blocker 保留：吞 required 图、把真实 0 当错误、同批多行双计、无价详情丢凭证/数量、trusted_quantity_rows 绕过单位隔离。以上均须修复后才可进入合并发布；本文件不表示已完成或线上通过。
