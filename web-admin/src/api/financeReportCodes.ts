/**
 * 后端完整性闸: 本期有出货记录缺扣库批次或单位, 不能算 SKU 毛利 (ShipmentCostRows.incomplete)。
 * 独立文件: 页面测试 mock 掉 financeReport 时仍能取到这个常量。
 */
export const SHIPMENT_COST_SOURCE_INCOMPLETE = 'SHIPMENT_COST_SOURCE_INCOMPLETE';
