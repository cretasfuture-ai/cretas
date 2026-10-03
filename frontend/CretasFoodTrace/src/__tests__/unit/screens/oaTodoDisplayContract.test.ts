import fs from 'fs';
import path from 'path';
import { formatOATodoValue } from '../../../utils/oaTodoDisplay';

const srcRoot = path.resolve(__dirname, '../../../');
const read = (relativePath: string) => fs.readFileSync(path.join(srcRoot, relativePath), 'utf8');

describe('OA 待办详情显示合同', () => {
  const screenSource = read('screens/oa/TodoDetailScreen.tsx');

  it('renders the real sales fields through the shared display formatter', () => {
    for (const field of [
      'salesperson',
      'shippingIncluded',
      'shippingFee',
      'processingMode',
      'materialSupplyMode',
      'customerStockFulfillmentMode',
    ]) {
      expect(screenSource).toContain(`${field}:`);
    }
    expect(screenSource).toContain('formatOATodoValue(key, value)');
    expect(formatOATodoValue('shippingIncluded', true)).toBe('是');
    expect(formatOATodoValue('shippingIncluded', false)).toBe('否');
    expect(formatOATodoValue('shippingFee', 1234.5)).toBe('¥1,234.50');
    expect(formatOATodoValue('shippingFee', 0)).toBe('¥0');
    expect(formatOATodoValue('processingMode', 'STANDARD_SALE')).toBe('普通销售');
    expect(formatOATodoValue('materialSupplyMode', 'FACTORY_SUPPLIED')).toBe('工厂供料');
    expect(formatOATodoValue('customerStockFulfillmentMode', 'ORDER_DRIVEN')).toBe('订单驱动');
    expect(formatOATodoValue('orderDate', '2026-10-01')).toBe('2026-10-01');
  });

  it('keeps permission masking, nulls, and zero values visible with safe semantics', () => {
    expect(screenSource).toContain("key === 'submittedBy'");
    expect(screenSource).toContain('发起人需核对');
    expect(screenSource).toContain('客户/供应商需核对');
    expect(formatOATodoValue('shippingFee', null)).toBe('—');
    expect(formatOATodoValue('shippingIncluded', null)).toBe('—');
    expect(screenSource).toContain("'invoiceTodoAttempts'");
  });
});
