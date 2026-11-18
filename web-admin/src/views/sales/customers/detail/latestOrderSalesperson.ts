/**
 * 客户档案的「当前业务员」(customer.assignedSalesUserId) 与销售单上的「业务员」(sales_order.salesperson)
 * 是两套独立数据: 前者是档案归属, 后者是下单时填写。档案未分配时, 页头改显示
 * 「最近订单业务员」并标明来源, 不把销售单上的人冒充成档案归属。
 */
export interface OrderSalespersonRow {
  orderDate?: string | null;
  salesperson?: string | null;
}

/** 在给定订单里取下单日期最新、且业务员非空的那一单的业务员; 没有则返回 null (不猜)。 */
export function latestOrderSalesperson(rows: OrderSalespersonRow[] | null | undefined): string | null {
  let best: { date: string; name: string } | null = null;
  for (const r of rows ?? []) {
    const name = (r.salesperson ?? '').trim();
    if (!name) continue;
    const date = r.orderDate ?? '';
    if (best === null || date > best.date) best = { date, name };
  }
  return best ? best.name : null;
}
