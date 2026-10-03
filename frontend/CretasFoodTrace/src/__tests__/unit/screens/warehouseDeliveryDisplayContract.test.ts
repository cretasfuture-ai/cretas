import { deliveryItemLines } from '../../../screens/warehouse/outbound/WHDeliveryConfirmListScreen';

it('renders persisted count-unit codes without changing quantities or weight symbols', () => {
  const lines = deliveryItemLines({ id: 'own', deliveryNumber: 'DLV-own', status: 'SHIPPED',
    items: [
      { id: 1, productName: '产品一', deliveredQuantity: 80, unit: 'box' },
      { id: 2, productName: '产品二', deliveredQuantity: 0, unit: 'kg' },
      { id: 3, productName: '产品三', deliveredQuantity: 3.5, unit: '自定义单位' },
    ] });
  expect(lines.map(line => line.quantityText)).toEqual(['计划发 80 盒', '计划发 0 kg', '计划发 3.5 自定义单位']);
});
