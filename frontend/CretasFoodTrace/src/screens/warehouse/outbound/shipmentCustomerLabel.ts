/**
 * 发货记录的客户显示名 —— 只用后端补的 customerName, 查不到就说「未知客户」。
 * ⛔ 不回落成 customerId: 那是内部 UUID, 仓管员看到一串乱码不知道发给谁
 * (2026-08-20 真机走查, 2026-11-13 App 列表 / 详情屏仍在拿 id 兜底)。
 */
export const UNKNOWN_CUSTOMER_LABEL = '未知客户';

export function shipmentCustomerLabel(shipment: { customerName?: string | null }): string {
  const name = shipment.customerName?.trim();
  return name ? name : UNKNOWN_CUSTOMER_LABEL;
}
