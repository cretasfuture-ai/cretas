import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  customerMaterialReceivingStatusLabel,
  customerMaterialCategoryLabel,
  customerMaterialCategoryOf,
  materialSupplyModeLabel,
  newSalesOrderSupplyContract,
  processingModeLabel,
  suppliedMaterialsValidationError,
  supplyContractValidationError,
  warehouseReceivingRoute,
} from '../salesOrderSupplyContract';

const listSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
const detailSource = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');
const newSource = readFileSync(resolve(import.meta.dirname, '../new.vue'), 'utf8');

describe('sales order supply contract', () => {
  it('defaults a new order to explicit standard sale and factory supply', () => {
    expect(newSalesOrderSupplyContract()).toEqual({
      processingMode: 'STANDARD_SALE',
      materialSupplyMode: 'FACTORY_SUPPLIED',
      customerStockFulfillmentMode: 'ORDER_DRIVEN',
    });
  });

  it('blocks invalid supply combinations and incomplete customer material rows', () => {
    expect(supplyContractValidationError({
      processingMode: 'STANDARD_SALE',
      materialSupplyMode: 'CUSTOMER_SUPPLIED',
      customerStockFulfillmentMode: 'ORDER_DRIVEN',
    })).toContain('普通销售不能选择客户自带物料');
    const contract = {
      processingMode: 'TOLL_PROCESSING' as const,
      materialSupplyMode: 'CUSTOMER_SUPPLIED' as const,
      customerStockFulfillmentMode: 'ORDER_DRIVEN' as const,
    };
    expect(suppliedMaterialsValidationError(contract, [])).toContain('至少添加一项');
    expect(suppliedMaterialsValidationError(contract, [{
      materialTypeId: 'm1', materialName: '原料A', expectedQuantity: 5, unit: 'kg',
      expectedArrivalAt: '2026-07-23', targetWarehouseId: 'w1',
    }])).toBeNull();
    expect(suppliedMaterialsValidationError(contract, [
      { materialTypeId: 'm1', materialName: '原料A', expectedQuantity: 5, unit: 'kg', expectedArrivalAt: '2026-07-23', targetWarehouseId: 'w1' },
      { materialTypeId: 'm1', materialName: '原料A', expectedQuantity: 2, unit: 'kg', expectedArrivalAt: '2026-07-24', targetWarehouseId: 'w1' },
    ])).toContain('不能重复添加');

    expect(supplyContractValidationError({
      processingMode: 'STANDARD_SALE',
      materialSupplyMode: 'FACTORY_SUPPLIED',
      customerStockFulfillmentMode: 'PRESTOCKED',
    })).toContain('只适用于');
    expect(suppliedMaterialsValidationError({
      ...contract,
      customerStockFulfillmentMode: 'PRESTOCKED',
    }, [])).toBeNull();
  });

  it('uses Chinese labels and explicit legacy fallbacks', () => {
    expect(processingModeLabel('TOLL_PROCESSING')).toBe('代加工');
    expect(materialSupplyModeLabel('CUSTOMER_SUPPLIED')).toBe('客户自带物料');
    expect(processingModeLabel(null)).toBe('未设置（历史数据）');
    expect(customerMaterialReceivingStatusLabel('PARTIALLY_RECEIVED')).toBe('部分收货');
  });

  it('routes to the unified warehouse page without performing a mutation', () => {
    expect(warehouseReceivingRoute({ id: 'so-1', orderNumber: 'SO-001' })).toEqual({
      path: '/warehouse/materials',
      query: {
        view: 'receiving',
        sourceType: 'SALES_ORDER_CUSTOMER_SUPPLIED',
        salesOrderId: 'so-1',
        salesOrderNo: 'SO-001',
      },
    });
    const routeFunction = detailSource.slice(
      detailSource.indexOf('function goWarehouseReceiving'),
      detailSource.indexOf('const deliveryDialogVisible'),
    );
    expect(routeFunction).not.toContain('post(');
  });

  it('persists create and edit values and keeps customer receiving read-only in sales', () => {
    expect(listSource).toContain('v-model="form.processingMode"');
    expect(listSource).toContain('v-model="form.materialSupplyMode"');
    expect(listSource).toContain('v-model="form.customerStockFulfillmentMode"');
    expect(listSource).toContain('suppliedMaterials: suppliedMaterialsPayload()');
    expect(listSource).toContain('value-format="YYYY-MM-DDTHH:mm:ss"');
    expect(listSource).toContain('expectedArrivalAt: row.expectedArrivalAt.length === 10');
    expect(listSource).toContain('Array.isArray(row.suppliedMaterials)');
    expect(listSource).toContain('const detailResponse = await get(`/${factoryId.value}/sales/orders/${orderId}`)');
    expect(detailSource).toContain('processingModeLabel(order.processingMode)');
    expect(detailSource).toContain('客户自带物料需求');
    expect(detailSource).toContain('isOrderDrivenCustomerSuppliedOrder');
    expect(detailSource).toContain('客户已有成品库存');
    expect(detailSource).toContain('/sales/orders/${orderId.value}/reservations');
    expect(detailSource).toContain('精确批次');
    expect(detailSource).toContain('前往仓储收货任务');
    expect(detailSource).not.toContain('customer-material-receipts');
    expect(detailSource).not.toContain('客供料入库');
  });

  it('maps all three material-master categories for display without changing the payload contract', () => {
    expect(customerMaterialCategoryOf('原料')).toBe('RAW');
    expect(customerMaterialCategoryOf('辅料')).toBe('AUXILIARY');
    expect(customerMaterialCategoryOf('PACKAGING')).toBe('PACKAGING');
    expect(customerMaterialCategoryOf('包装')).toBeNull();
    expect(customerMaterialCategoryLabel('unclassified')).toBe('未知分类');
    expect(newSource).toContain('suppliedMaterialCategory(material)');
    expect(listSource).toContain('suppliedMaterialCategory(material)');
    expect(detailSource).toContain('customerMaterialCategory(row)');
    expect(listSource).toContain('const { category: _category, ...payload } = row');
    expect(newSource).toContain('customerMaterialCategoryLabel(material.mainCategory)');
    expect(listSource).toContain('customerMaterialCategoryLabel(mainCategory || option?.mainCategory)');
    expect(detailSource).toContain('customerMaterialCategoryLabel(row.mainCategory || option?.mainCategory)');
    expect(listSource).toContain("ElMessage.error('客供物料分类加载失败，请刷新后重试')");
    expect(detailSource).toContain("ElMessage.error('客供物料分类加载失败，请刷新后重试')");
  });

  it('renders a real DOM focus target around the customer-material arrival picker', () => {
    expect(newSource).toContain('<div data-field="arrival"><el-date-picker');
    expect(newSource).toContain('target?.querySelector<HTMLElement>(\'input\')?.focus()');
  });
});
