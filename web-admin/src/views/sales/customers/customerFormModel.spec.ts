import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  countFilledCustomerAdvancedFields,
  customerTypeLabel,
  isCustomerAdvancedField,
  isSupportedCustomerPhone,
  normalizeCustomerPayload,
} from './customerFormModel';

describe('customer progressive-disclosure form model', () => {
  it('accepts mobile, landline with extension and international phones', () => {
    expect(isSupportedCustomerPhone('13800138000')).toBe(true);
    expect(isSupportedCustomerPhone('021-12345678 转 806')).toBe(true);
    expect(isSupportedCustomerPhone('(010) 12345678 ext. 66')).toBe(true);
    expect(isSupportedCustomerPhone('+65 6123 4567')).toBe(true);
    expect(isSupportedCustomerPhone('123')).toBe(false);
  });

  it('writes the five required fields but omits untouched advanced defaults', () => {
    const payload = normalizeCustomerPayload({
      name: ' 客户A ', contactPerson: ' 张三 ', phone: ' 021-12345678 ',
      shippingAddress: ' 上海市 ', status: 'ACTIVE', type: '', customerStatus: '',
      importance: '', creditStatus: '', defaultTaxRate: null, creditLimit: 0,
    }, ['creditLimit']);
    expect(payload).toMatchObject({
      name: '客户A', contactPerson: '张三', phone: '021-12345678',
      shippingAddress: '上海市', status: 'ACTIVE', creditLimit: 0,
    });
    expect(payload).not.toHaveProperty('customerStatus');
    expect(payload).not.toHaveProperty('importance');
    expect(payload).not.toHaveProperty('creditStatus');
  });

  it('counts advanced values and exposes business customer type labels', () => {
    expect(countFilledCustomerAdvancedFields({ email: 'a@b.com', defaultTaxRate: 0, source: '' })).toBe(2);
    expect(isCustomerAdvancedField('email')).toBe(true);
    expect(isCustomerAdvancedField('notes')).toBe(false);
    expect(customerTypeLabel('RESTAURANT')).toBe('餐饮企业');
    expect(customerTypeLabel('')).toBe('未设置');
    expect(customerTypeLabel('ENTERPRISE')).toBe('企业客户');
    expect(customerTypeLabel('FOO_BAR')).toBe('未知类型');
  });

  it('客户详情头部用同一映射函数, 不直出原码', () => {
    const src = readFileSync(resolve(__dirname, 'detail/CustomerHeader.vue'), 'utf-8');
    expect(src).toContain('customerTypeLabel(customer.customerType || customer.type)');
    expect(src).not.toMatch(/\{\{\s*customer\.customerType \|\| customer\.type\s*\}\}/);
  });
});
