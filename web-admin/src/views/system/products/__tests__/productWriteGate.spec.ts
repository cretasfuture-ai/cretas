import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { usePermissionStore } from '@/store/modules/permission';
import { canWriteProductMaster, canWriteProductProcess } from '../productWriteGates';

const pageSource = readFileSync(resolve(__dirname, '..', 'index.vue'), 'utf8');
// 工序闸 2026-09-03 起长在 hub 页上, 见下方「工序闸搬到 hub 页」。
const hubSource = readFileSync(
  resolve(__dirname, '..', '..', 'product-processes', 'index.vue'), 'utf8');

const permissionApiMocks = vi.hoisted(() => ({
  getPlatformPermissions: vi.fn(),
  getFactoryOverride: vi.fn(),
  getUserModuleAccess: vi.fn(),
}));
vi.mock('@/api/permissionApi', () => permissionApiMocks);

/** 用真实权限矩阵造探针 —— 行为断言全部经它, 不拿手写的假权限自证。 */
function probeFor(role: string) {
  const store = usePermissionStore();
  store.setRole(role, 'F006', 'FACTORY', '1309', { skipDbLoad: true });
  return (module: string) => store.canWrite(module);
}

/**
 * 客户（张权）2026-08-05:「无法新建产品了」—— SKU 管理页右上角只剩 导出 / 导入 / SKU组装,
 * 「新增 SKU」整个不见了。报告人角色是**调度**。
 *
 * 根因是前后端判了两个不同的权限:
 *   后端 ProductTypeController 每一个写端点 = {"production:read_write","rd:read_write"}
 *   前端 products/index.vue 却按 canWrite('system') 决定显不显示
 * 调度是 production/rd 可写、system 只读 → 后端本来允许他建产品, 界面把入口藏了。
 */
describe('产品页写入闸必须与后端一致 (客户 2026-08-05「无法新建产品了」)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    permissionApiMocks.getPlatformPermissions.mockRejectedValue(new Error('offline'));
    permissionApiMocks.getFactoryOverride.mockRejectedValue(new Error('offline'));
    permissionApiMocks.getUserModuleAccess.mockRejectedValue(new Error('offline'));
  });

  it('调度能建产品 —— 这就是被修的那条', () => {
    const probe = probeFor('dispatcher');
    expect(canWriteProductMaster(probe)).toBe(true);
    expect(probe('system')).toBe(false);   // 缺陷成因: 页面原来取的是它
  });

  it('对产品主数据只读的角色仍然进不来 —— 修的是取错键, 不是把闸拆了', () => {
    // 仓管: production 'r' / rd '-'。刻意不用 operator —— 它 production 可写,
    // 拿它当「只读」例子会让断言恒真而失去意义 (第一版就这么写错, 被测试当场判红)。
    expect(canWriteProductMaster(probeFor('warehouse_manager'))).toBe(false);
  });

  it('🔴 两把闸不能合并: 销售经理能建产品, 但不能配工序', () => {
    // 后端 ProductWorkProcessController 的写端点只认 production, **不含 rd**;
    // sales_manager 是 production='r' / rd='rw', 正好落在两者之间。
    // 这条是「两把闸被合并」的唯一 oracle —— 源码 grep 对合并是沉默的。
    const probe = probeFor('sales_manager');
    expect(canWriteProductMaster(probe)).toBe(true);
    expect(canWriteProductProcess(probe)).toBe(false);
  });

  it('生产口径的角色两把闸都放行 —— 收紧不能收过头', () => {
    const probe = probeFor('dispatcher');
    expect(canWriteProductMaster(probe)).toBe(true);
    expect(canWriteProductProcess(probe)).toBe(true);
  });

  it('工序闸严格不宽于产品闸 —— 全角色扫一遍, 不靠挑例子', () => {
    const roles = ['dispatcher', 'sales_manager', 'warehouse_manager', 'quality_manager',
      'procurement_manager', 'equipment_admin', 'factory_super_admin', 'hr_admin', 'cashier'];
    for (const role of roles) {
      const probe = probeFor(role);
      if (canWriteProductProcess(probe)) {
        expect(canWriteProductMaster(probe), `${role}: 能配工序就必然能改产品`).toBe(true);
      }
    }
  });

  it('产品页经产品闸, 不再自己判 system', () => {
    expect(pageSource).toContain('canWriteProductMaster(probe)');
    expect(pageSource).not.toContain("permissionStore.canWrite('system')");
  });

  /**
   * 🔴 2026-09-03: 工序闸的**承载点搬家了**。
   *
   * SKU 管理页那个工序抽屉去掉了(它是 product_work_processes 的第二个编辑入口, 且比
   * /system/product-processes 少 update / copy-chain / 成品源 / 成本类别), 所以工序写权限
   * 不再由产品页判 —— 改由 hub 页判。
   *
   * ⚠️ 而 hub 页原本判的是 `canWrite('system')`, 正是 8-05 那个缺陷的同一形态:
   * 实测九个角色里 dispatcher 是 production=true / system=false, 后端放行、界面藏控件。
   * 抽屉去掉若不同时修这把闸, **调度就没有任何配工序的入口了**。
   *
   * ⇒ 这条断言守的是「工序闸仍然存在, 只是换了页面」, ⛔ 不是「抽屉还在」。
   */
  it('工序闸搬到 hub 页, 且 hub 页不再判 system', () => {
    expect(hubSource).toContain('canWriteProductProcess(');
    expect(hubSource).not.toContain("permissionStore.canWrite('system')");
  });

  it('产品页不再直接读写 product-work-processes —— 单一编辑入口', () => {
    for (const fn of ['createProductWorkProcess', 'deleteProductWorkProcess',
      'batchSortProductWorkProcesses', 'getProductWorkProcesses']) {
      expect(pageSource, `产品页又出现了 ${fn}`).not.toContain(fn);
    }
    // 阳性对照: hub 页确实是那个唯一入口(否则上面四条可能只是「两边都没了」)。
    expect(hubSource).toContain('updateProductWorkProcess');
  });

  it('「导入」也是写操作, 不能对只读角色敞着', () => {
    expect(pageSource).toMatch(/<el-button[^>]*v-if="canWrite"[^>]*@click="handleImport"/);
  });

  it('编辑入口不藏 (打开走 GET, 要能看), 但保存按写产品的闸禁用', () => {
    expect(pageSource).toMatch(/@click="handleEdit\(row\)"/);
    expect(pageSource).not.toMatch(/v-if="canWrite"[^>]*@click="handleEdit\(row\)"/);
    expect(pageSource).toMatch(/:disabled="!canWrite"[\s\S]{0,120}@click="handleSubmit"/);
  });

  it('保存产品仍然解释原因 —— 只 disable 不解释是另一种 dead-end', () => {
    expect(pageSource).toMatch(/需要生产或研发写权限才能保存产品资料/);
  });
});
