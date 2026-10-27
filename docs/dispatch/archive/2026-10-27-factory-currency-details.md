# FADCURRENCY27 — SKU 金额图保留真实数量单位明细

状态review，源码/测试已冻结，最终提交归档并释放scope；Base SHA 494e85ed66d702174673fc0f66b9a23d34c3bcef；Root DIRECT唯一源码/台账/提交/发布写者。允许scope仅backend/python/smartbi/services/factory_charts/business_metrics.py、details.py、detail_store.py、新test_currency_details.py及本归档；不改SQL、迁移、权限定义、Java、Web、RN、依赖或发布脚本。

F006真机SKU销售图2932.66元可用，但原始明细actualButton enabled=false。已确认：逐单对账来源sales-sku的金额图含不同数量单位，旧guard把候选“元”与kg/盒/袋相比而拒绝context。不是点击/导航失败，失败截图XML保留。

修复只允许最终family sales-sku.sales.sku、exact source match且sales-sku来源、CNY数据集、整单位元、server engine显式currency_rows策略的明细保留全部原始行单位。禁止将kg/盒相加；通用金额、单价/单位成本与百分比保留原有unit guard，actor/tenant/金额权限继续校验。共享/内存实现复用同一helper。

窄源码复核先发现实际引擎会加来源family前缀；最终正例改为真实engine.build取得最终候选，不能仅用business_metrics原始family的绿灯冒充生产接线。初始fixture证据保留但不作最终通过依据。

最终验证已实测：真实引擎目标9项PASS/0skip；冻结后的完整factory_charts套件275 PASS、6项可选真实PG未执行、23 warnings、退出0（fadcurrency27-frozen-factory.log）。6项skip明确为未提供独立可丢弃PG，不算通过；本批未改SQL/RLS/迁移，既有实际PG上下文证据单独保留，不冒称重跑。独立context_risk_review最终PASS，确认真实family、CNY与来源/策略联合约束、原始行单位保留、价格遮罩与actor/tenant隔离、单价/ratio原守卫均保留。仅文档收尾不重跑同一已冻结产品测试；PR/CI/合并/部署/F006 UI仍各自记录实际结果。

验收：新货币明细回归；冻结后factory_charts相关完整Python套件（可选真实PG项单列未执行，不冒充通过，SQL/迁移未变）；context_risk_review独立权限/数据终审。通过后正常PR/CI合并，clean exact main官方Python发布target V20261101_20（已应用，只校验/跳过）；追加实际文件hash与运行角色回读，只F006只读验收受影响SKU原始行/Excel-PDF-PPT及产品澄清。Java/Web/OTA源码不变，复用已通过发布与业务证据，不重复构建/发布。
