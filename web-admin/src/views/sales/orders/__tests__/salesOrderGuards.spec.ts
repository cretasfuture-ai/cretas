import { describe, expect, it } from 'vitest';
import {
  actionableDeliveryCount,
  deliveryActionState,
  deliveryCapacityByLine,
  deliveryMoney,
  deliveryTransportAggregate,
  formatBusinessDateTime,
  masterDeliveryRemainingByLine,
  pendingReshipmentMaster,
  productionActionState,
  resolveDeliveryAddress,
  shipmentValidationError,
  shortShipmentSummary,
} from '../salesOrderGuards';

const orderItems = [{ id: 726, productTypeId: 'SKU-BOX', quantity: 5, deliveredQuantity: 0, unit: 'box' }];

describe('M10 销售订单动作和发运门禁', () => {
  it('生产动作覆盖无计划、生产中、已完成，取消计划会释放动作', () => {
    expect(productionActionState(orderItems, [])).toMatchObject({ label: '开始生产', disabled: false });
    expect(productionActionState(orderItems, [{ sourceOrderItemId: 726, plannedQuantity: 5, status: 'IN_PROGRESS' }]))
      .toMatchObject({ label: '生产中', disabled: true });
    expect(productionActionState(orderItems, [{ sourceOrderItemId: 726, plannedQuantity: 5, status: 'COMPLETED' }]))
      .toMatchObject({ label: '已生产', disabled: true });
    expect(productionActionState(orderItems, [{ sourceOrderItemId: 726, plannedQuantity: 5, status: 'CANCELLED' }]))
      .toMatchObject({ label: '开始生产', disabled: false });
  });

  it('母发货单按每行有效安排量限制，取消子单释放母单容量', () => {
    const partial = [{ id: 'M1', recordRole: 'MASTER', status: 'PARTIALLY_SCHEDULED', items: [
      { salesOrderItemId: 726, productTypeId: 'SKU-BOX', deliveredQuantity: 2 },
    ] }];
    expect(deliveryCapacityByLine(orderItems, partial)[0]).toMatchObject({ ordered: 5, arranged: 2, remaining: 3 });
    expect(deliveryActionState(orderItems, partial)).toMatchObject({ label: '新建发货单', disabled: false });

    const full = [{ id: 'M1', recordRole: 'MASTER', status: 'FULLY_SCHEDULED', items: [
      { salesOrderItemId: 726, productTypeId: 'SKU-BOX', deliveredQuantity: 5 },
    ] }];
    expect(deliveryActionState(orderItems, full)).toMatchObject({ label: '已全部安排发货', disabled: true });
    expect(deliveryCapacityByLine(orderItems, [{ ...full[0], status: 'CANCELLED' }])[0].remaining).toBe(5);
  });

  it('红色角标只统计待处理叶子发运，签收后聚合为已签收', () => {
    const rows = [
      { id: 'M1', recordRole: 'MASTER', status: 'FULLY_SCHEDULED' },
      { id: 'S1', parentDeliveryId: 'M1', recordRole: 'SHIPMENT', status: 'SHIPPED' },
      { id: 'S2', parentDeliveryId: 'M1', recordRole: 'SHIPMENT', status: 'DELIVERED' },
      { id: 'S3', parentDeliveryId: 'M1', recordRole: 'SHIPMENT', status: 'CANCELLED' },
    ];
    expect(actionableDeliveryCount(rows)).toBe(1);
    expect(deliveryTransportAggregate(rows)).toBe('PARTIALLY_RECEIVED');
    expect(actionableDeliveryCount(rows.map((row) => row.id === 'S1' ? { ...row, status: 'DELIVERED' } : row))).toBe(0);
    expect(deliveryTransportAggregate(rows.map((row) => row.id === 'S1' ? { ...row, status: 'DELIVERED' } : row))).toBe('RECEIVED');
  });

  it('物流配送在发运前 fail-closed，自送/自提显式免填运单号', () => {
    expect(shipmentValidationError({ deliveryMethod: 'LOGISTICS', plannedShipmentDate: '2026-07-21' }))
      .toBe('物流配送必须填写物流公司');
    expect(shipmentValidationError({ deliveryMethod: 'LOGISTICS', plannedShipmentDate: '2026-07-21', logisticsCompany: '顺丰' }))
      .toBe('物流配送必须填写物流/运单号');
    expect(shipmentValidationError({ deliveryMethod: 'SELF_PICKUP', plannedShipmentDate: '2026-07-21' })).toBe('');
  });

  it('地址优先订单再客户，部分发货金额按本次数量与税率计算', () => {
    expect(resolveDeliveryAddress('订单地址', '客户地址')).toBe('订单地址');
    expect(resolveDeliveryAddress('', '客户地址')).toBe('客户地址');
    expect(resolveDeliveryAddress('', '')).toBe('');
    expect(deliveryMoney([{ deliveredQuantity: 2, unitPrice: 20, taxRate: 13 }]))
      .toEqual({ untaxed: 40, tax: 5.2, taxIncluded: 45.2 });
  });

  it('审批/业务时间本地化到秒并标注周几', () => {
    expect(formatBusinessDateTime('2026-07-20T12:30:10.619754')).toBe('2026-07-20（周一）12:30:10');
  });

  // T-SHORT-SHIP (owner 2026-09-04 拍板): 短交结案 — 实拍样本 SO-20260904-0001
  // (10kg 美式汉堡饼, 母单 DLV-...-4086 全量占了 arranged, 子单 -01 只实发 9.8kg)。
  describe('短交结案 — 实发缺口与补发目标', () => {
    const orderedItem = { id: 726, salesOrderItemId: 726, productTypeId: 'SKU-BURGER', quantity: 10, deliveredQuantity: 9.8, unit: 'kg' };

    it('缺口按【实发】算, 不是按【安排量】算 —— 母单占满不等于已经发出去', () => {
      // 母单把 10kg 全部安排掉 (deliveryCapacityByLine 会判定 remaining=0),
      // 但只有 9.8kg 真正 SHIPPED —— 短交结案要看到的是这 0.2kg。
      const summary = shortShipmentSummary([orderedItem]);
      expect(summary.ordered).toBe(10);
      expect(summary.shipped).toBe(9.8);
      expect(summary.outstanding).toBeCloseTo(0.2);
      expect(summary.unit).toBe('kg');
    });

    it('全部实发时缺口为 0 —— 不该再出现补发/结案两个按钮', () => {
      expect(shortShipmentSummary([{ ...orderedItem, deliveredQuantity: 10 }]).outstanding).toBe(0);
    });

    it('混单位时不标注单位, 避免拿一个单位标另一种的数', () => {
      expect(shortShipmentSummary([orderedItem, { ...orderedItem, id: 727, unit: 'box' }]).unit).toBe('');
    });

    it('母单自己的行仍有未安排数量时, 判给它补发', () => {
      const master = { id: 'M1', status: 'PARTIALLY_SCHEDULED', items: [
        { salesOrderItemId: 726, productTypeId: 'SKU-BURGER', deliveredQuantity: 10 },
      ] };
      const child = { id: 'S1', parentDeliveryId: 'M1', recordRole: 'SHIPMENT', status: 'SHIPPED', items: [
        { salesOrderItemId: 726, productTypeId: 'SKU-BURGER', deliveredQuantity: 9.8 },
      ] };
      expect(masterDeliveryRemainingByLine(master, [master, child]).get('726')).toBeCloseTo(0.2);
      expect(pendingReshipmentMaster([master], [master, child])).toBe(master);
    });

    it('母单自己也已排满(remaining=0)时不选它 —— 调用方应改走「新建发货单」', () => {
      const fullyScheduledMaster = { id: 'M1', status: 'FULLY_SCHEDULED', items: [
        { salesOrderItemId: 726, productTypeId: 'SKU-BURGER', deliveredQuantity: 9.8 },
      ] };
      const child = { id: 'S1', parentDeliveryId: 'M1', recordRole: 'SHIPMENT', status: 'SHIPPED', items: [
        { salesOrderItemId: 726, productTypeId: 'SKU-BURGER', deliveredQuantity: 9.8 },
      ] };
      expect(pendingReshipmentMaster([fullyScheduledMaster], [fullyScheduledMaster, child])).toBeNull();
    });

    it('已取消/已退回的子单不占用母单的已安排量', () => {
      const master = { id: 'M1', status: 'PARTIALLY_SCHEDULED', items: [
        { salesOrderItemId: 726, productTypeId: 'SKU-BURGER', deliveredQuantity: 10 },
      ] };
      const cancelledChild = { id: 'S1', parentDeliveryId: 'M1', recordRole: 'SHIPMENT', status: 'CANCELLED', items: [
        { salesOrderItemId: 726, productTypeId: 'SKU-BURGER', deliveredQuantity: 10 },
      ] };
      expect(masterDeliveryRemainingByLine(master, [master, cancelledChild]).get('726')).toBe(10);
    });

    it('没有任何母单时补发目标为 null', () => {
      expect(pendingReshipmentMaster([], [])).toBeNull();
    });
  });
});
