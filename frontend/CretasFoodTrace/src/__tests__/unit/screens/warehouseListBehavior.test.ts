import {
  countInboundTasks,
  filterPendingDeliveries,
  isConfirmableDeliveryStatus,
  isCompletedDeliveryStatus,
  mergeDeliveryRecords,
  sortReceivingOrders,
} from '../../../screens/warehouse/warehouseList.logic';

describe('仓库列表行为口径', () => {
  it('入库计数使用 totalElements，并把无订单入库加入待处理和全部', () => {
    const counts = countInboundTasks({
      FINANCE_APPROVED: { totalElements: 2 },
      PARTIAL_RECEIVED: { totalElements: 3 },
      COMPLETED: { totalElements: 9 },
      CLOSED: { totalElements: 1 },
    }, 4);

    expect(counts).toEqual({ all: 19, pending: 6, partial: 3, completed: 10 });
  });

  it('采购收货先按状态，再按业务时间倒序；分页合并后顺序仍全局稳定', () => {
    const pageOne = [
      { id: 'partial-old', status: 'PARTIAL_RECEIVED', expectedDeliveryDate: '2026-08-20' },
      { id: 'pending-old', status: 'FINANCE_APPROVED', expectedDeliveryDate: '2026-08-20' },
    ] as never[];
    const pageTwo = [
      { id: 'pending-new', status: 'FINANCE_APPROVED', expectedDeliveryDate: '2026-08-23' },
      { id: 'partial-new', status: 'PARTIAL_RECEIVED', expectedDeliveryDate: '2026-08-23' },
    ] as never[];

    expect(sortReceivingOrders([...pageOne, ...pageTwo]).map((order) => order.id)).toEqual([
      'pending-new',
      'pending-old',
      'partial-new',
      'partial-old',
    ]);
  });

  it('发货状态筛选只改变当前页列表，不把已确认历史混入待确认队列', () => {
    const deliveries = [
      { id: 'draft', status: 'DRAFT' },
      { id: 'pending', status: 'PENDING_WAREHOUSE_CONFIRM' },
      { id: 'picked', status: 'PICKED' },
      { id: 'shipped', status: 'SHIPPED' },
    ] as never[];

    expect(filterPendingDeliveries(deliveries, 'PENDING_WAREHOUSE_CONFIRM').map((row) => row.id)).toEqual(['pending']);
    expect(filterPendingDeliveries(deliveries, 'all').map((row) => row.id)).toEqual(['draft', 'pending', 'picked', 'shipped']);
  });

  it('发货搜索使用当前可得的单号、客户和货品字段', () => {
    const deliveries = [
      { id: 'a', status: 'PICKED', deliveryNumber: 'DLV-001', customerName: '甲客户' },
      { id: 'b', status: 'PICKED', deliveryNumber: 'DLV-002', customerName: '乙客户' },
    ] as never[];
    expect(filterPendingDeliveries(deliveries, 'all', '甲客户').map((row) => row.id)).toEqual(['a']);
  });

  it('发货历史终态进入已完成筛选且与待确认重复时只展示一次', () => {
    const pending = [{ id: 'same', status: 'PICKED' }, { id: 'p', status: 'DRAFT' }] as never[];
    const history = [
      { id: 'same', status: 'SHIPPED' },
      { id: 'h', status: 'DELIVERED' },
      { id: 'c', status: 'CANCELLED' },
    ] as never[];
    const merged = mergeDeliveryRecords(pending, history);

    expect(merged.map((row) => row.id)).toEqual(['same', 'p', 'h', 'c']);
    expect(filterPendingDeliveries(merged, 'completed').map((row) => row.id)).toEqual(['h', 'c']);
    expect(isCompletedDeliveryStatus('SIGNED')).toBe(true);
    expect(isCompletedDeliveryStatus('PICKED')).toBe(false);
    expect(isConfirmableDeliveryStatus('PICKED')).toBe(true);
    expect(isConfirmableDeliveryStatus('FULLY_SCHEDULED')).toBe(false);
  });
});
