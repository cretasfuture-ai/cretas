import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(resolve(import.meta.dirname, 'PendingPurchaseReceivingPanel.vue'), 'utf8');

describe('采购收货多包装与基本单位落账契约', () => {
  it('实际到货包装可选，并即时展示折合基本量', () => {
    expect(source).toContain('label="实际到货包装"');
    expect(source).toContain('v-model="row.packagingKey"');
    expect(source).toContain('baseQuantityPreview(row)');
    expect(source).toContain('materialPackagingSpecId: item.materialPackagingSpecId');
  });

  it('收货上限按采购基本量与所选包装系数换算', () => {
    expect(source).toContain('remainingReceivableQuantity || 0) * orderFactor / selectedFactor');
    expect(source).toContain(':max="remainingLimit(row)"');
  });

  /**
   * M-16 (2026-08-20): owner 现场核对后收窄需求 —— 「这边其实只能让仓管去填…其他的都没有了」。
   * 采购收货表格只留实收/规格, 合同号/供应商批次号/厂号/产地/件数这 5 个可追溯字段
   * (2026-08-06 应客户台账要求加的, 见下方旧注释)整块拿掉, 连同折叠开关 showMoreFields 一起。
   * 客供料弹窗 customerForm 是另一套字段, 不受这次收窄影响, 见文件末尾那组用例。
   */
  it('🔴 采购收货表格不再展示合同号/供应商批次号/厂号/产地/件数, 折叠开关也已拿掉', () => {
    expect(source).not.toContain('v-model="row.contractNumber"');
    expect(source).not.toContain('v-model="row.supplierBatchNumber"');
    expect(source).not.toContain('v-model="row.factoryNumber"');
    expect(source).not.toContain('v-model="row.originPlace"');
    expect(source).not.toContain('v-model="row.boxCount"');
    expect(source).not.toContain('showMoreFields');
    expect(source).not.toContain('onFactoryNumberChange(row)');
    // listManufacturers 仍在(客供料弹窗还在用), 不是这条断言要守的东西
    expect(source).toContain('listManufacturers');
  });

  it('可追溯字段已从采购收货提交载荷里拿掉(customerForm/arrivalForm 走各自表单, 不受影响)', () => {
    expect(source).not.toContain('contractNumber: blankToUndefined(item.contractNumber)');
    expect(source).not.toContain('supplierBatchNumber: blankToUndefined(item.supplierBatchNumber)');
    expect(source).not.toContain('factoryNumber: blankToUndefined(item.factoryNumber)');
  });

  /**
   * 客供料与采购来料没有理由只有一边能记 —— 客户台账不区分来料是买的还是客户送的。
   * 2026-08-06 第一版只给采购收货加了字段, 客供料那半漏了。
   */
  it('客供料收货也要有同一套可追溯字段', () => {
    expect(source).toContain('customerForm.contractNumber');
    expect(source).toContain('customerForm.factoryNumber');
    expect(source).toContain('customerForm.boxCount');
    expect(source).toContain('onCustomerFactoryNumberChange');
  });
});
