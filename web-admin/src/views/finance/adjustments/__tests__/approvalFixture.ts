import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus';
import Adjustments from '../list.vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import type { FactoryRole, User } from '@/types/auth';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('@/api/request', () => ({ ...api, ApiError: class extends Error {} }));
vi.mock('@/api/permissionApi', () => ({
  getPlatformPermissions: vi.fn().mockResolvedValue([]),
  getFactoryOverride: vi.fn().mockResolvedValue({}),
  getUserModuleAccess: vi.fn().mockResolvedValue([]),
}));

interface Row {
  id: string; factoryId?: string; transactionType: 'AR_ADJUSTMENT';
  counterpartyType: 'CUSTOMER'; counterpartyId: string; counterpartyName: string;
  amount: number | null; balanceAfter: number | null;
  approvalStatus: 'PENDING' | 'APPROVED'; operatedBy: number; createdAt: string;
}
const baseRow: Row = {
  id: 'FIN-UNIT-A01', factoryId: 'FIN-UNIT', transactionType: 'AR_ADJUSTMENT',
  counterpartyType: 'CUSTOMER', counterpartyId: 'FIN-UNIT-C01', counterpartyName: 'Synthetic customer',
  amount: 2, balanceAfter: 0, approvalStatus: 'PENDING', operatedBy: 90, createdAt: '2026-10-02T00:00:00Z',
};
const otherRow: Row = { ...baseRow, id: 'FIN-OTHER-A01', factoryId: 'FIN-OTHER', counterpartyName: 'Other factory customer' };
let row: Row;
let pinia: ReturnType<typeof createPinia>;
let wrapper: VueWrapper | undefined;
export function identity(factoryId = 'FIN-UNIT', id = 86, role: FactoryRole = 'finance_manager'): User {
  return { id, username: 'FIN-local-test', email: '', isActive: true, createdAt: '2026-10-02', updatedAt: '2026-10-02',
    userType: 'factory', factoryUser: { role, factoryId, factoryType: 'FACTORY', permissions: [] } };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
}
function listed(value: Row) { return { success: true, message: '', data: { content: [{ ...value }], totalElements: 1 } }; }
function approved(balanceAfter: number | null, extra: Partial<Row> = {}) {
  return { success: true, message: '', data: { ...row, approvalStatus: 'APPROVED', balanceAfter, ...extra } };
}
async function page() {
  wrapper = mount(Adjustments, { attachTo: document.body, global: { plugins: [pinia, ElementPlus] } });
  await flushPromises();
  return wrapper;
}
const successText = () => Array.from(document.body.querySelectorAll('.el-message--success')).map(n => n.textContent).join('\n');
async function open(w: VueWrapper) {
  const button = w.findAll('button').find(b => b.text() === '审批');
  expect(button, 'rendered approval action').toBeDefined();
  await button!.trigger('click'); await flushPromises();
  return Array.from(document.body.querySelectorAll('.el-message-box__message')).map(n => n.textContent).join('\n');
}
async function choice(text: string) {
  const button = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-message-box button')).find(n => n.textContent?.trim() === text);
  expect(button, 'real confirmation button').toBeDefined();
  button!.click(); await flushPromises();
}
async function refresh(w: VueWrapper) {
  const button = w.findAll('button').find(b => b.text() === '刷新');
  await button!.trigger('click'); await flushPromises();
}

const names = [
  'table labels submission snapshot and pending approval',
  'negative adjustment retains snapshot without predicting the posted balance',
  'missing snapshot is unknown rather than zero',
  'cancel sends zero POST and leaves pending fixture unchanged',
  'success uses fresh approved response balance after intervening live change',
  'negative approval uses returned balance rather than snapshot plus amount',
  'response without factory field uses verified request context',
  'missing approved balance reports unknown rather than fabricating zero',
  'mismatched response identity never claims approval success',
  'factory switch during confirmation sends zero POST',
  'account switch during confirmation sends zero POST',
  'late approval response after factory switch is ignored',
  'late old-factory pending list cannot overwrite current factory',
  'newer list request wins when older response arrives last',
  'unmounted view ignores late approval response',
  'price-hidden role sees no amounts in table, confirmation or success',
  'own submission hides single approval and bulk skips own records',
  'read-only finance exposes no approval writes',
  'unknown amount remains explicit in table and cancellation confirmation',
];

export function registerBalanceCase(number: number) {
  beforeEach(() => {
    vi.clearAllMocks(); api.get.mockReset(); api.post.mockReset();
    pinia = createPinia(); setActivePinia(pinia);
    useAuthStore().setUser(identity());
    usePermissionStore().setRole('finance_manager', 'FIN-UNIT', 'FACTORY', 86, { skipDbLoad: true });
    row = { ...baseRow };
    api.get.mockImplementation(async (path: string) => listed(path.includes('/FIN-OTHER/') ? otherRow : row));
  });
  afterEach(async () => {
    // The repository setup's enableAutoUnmount owns component teardown.
    wrapper = undefined;
    ElMessageBox.close(); ElMessage.closeAll(); await flushPromises();
  });
  describe('FIN fresh rendered balance contract', () => {
    it(names[number - 1], async () => {
      if (number === 1) {
        const w = await page();
        const headers = w.findAll('.el-table__header-wrapper th').map(n => n.text()).join('|');
        expect(headers).toContain('提交时余额'); expect(headers).toContain('待审批'); expect(headers).not.toContain('变动后余额');
      } else if (number === 2) {
        row.amount = -4; row.balanceAfter = 15;
        const text = await open(await page());
        expect(text).toContain('应收 -4.00 元'); expect(text).toContain('提交时余额: 15.00');
        expect(text).toContain('审批前未计入余额'); expect(text).not.toContain('11.00');
        await choice('取消'); expect(api.post).not.toHaveBeenCalled();
      } else if (number === 3) {
        row.balanceAfter = null; const text = await open(await page());
        expect(text).toContain('提交时余额: 未知'); expect(text).not.toContain('提交时余额: 0.00');
        await choice('取消'); expect(api.post).not.toHaveBeenCalled();
      } else if (number === 4) {
        const before = JSON.stringify(row); const w = await page(); await open(w); await choice('取消');
        expect(api.post).not.toHaveBeenCalled(); expect(JSON.stringify(row)).toBe(before); expect(w.text()).toContain(row.counterpartyName);
      } else if ([5, 6, 7, 8, 9, 16].includes(number)) {
        if (number === 6) { row.amount = -4; row.balanceAfter = 15; }
        if (number === 16) {
          const p = usePermissionStore(); useAuthStore().setUser(identity('FIN-UNIT', 86, 'warehouse_manager'));
          p.setRole('warehouse_manager', 'FIN-UNIT', 'FACTORY', 86, { skipDbLoad: true });
          p.dbPermissions = { ...p.currentPermissions, finance: 'rw' }; p.isDbLoaded = true;
          expect(p.canViewPrice).toBe(false);
        }
        const w = await page(); const text = await open(w);
        if (number === 16) { expect(text).not.toContain('金额:'); expect(text).not.toContain('提交时余额:'); expect(w.text()).not.toContain('应收 +2.00 元'); }
        api.post.mockResolvedValue(approved(number === 8 ? null : number === 6 ? 21 : 9,
          number === 7 ? { factoryId: undefined } : number === 9 ? { id: 'WRONG-RESPONSE' } : {}));
        await choice('确认审批');
        expect(api.post).toHaveBeenCalledTimes(1); expect(api.post).toHaveBeenCalledWith('/FIN-UNIT/finance/adjustment/FIN-UNIT-A01/approve');
        if (number === 9) expect(successText()).toBe('');
        else if (number === 8) { expect(successText()).toContain('审批后余额: 未知'); expect(successText()).not.toContain('0.00'); }
        else if (number === 16) { expect(successText()).toContain('已审批通过'); expect(successText()).not.toContain('余额'); expect(successText()).not.toContain('9.00'); }
        else expect(successText()).toContain(`审批后余额: ${number === 6 ? '21.00' : '9.00'}`);
      } else if (number === 10 || number === 11) {
        await open(await page());
        useAuthStore().setUser(identity(number === 10 ? 'FIN-OTHER' : 'FIN-UNIT', number === 11 ? 87 : 86));
        await flushPromises();
        const confirm = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-message-box button')).find(n => n.textContent?.trim() === '确认审批');
        confirm?.click(); await flushPromises();
        expect(api.post).not.toHaveBeenCalled(); expect(successText()).toBe('');
      } else if (number === 12 || number === 15) {
        const response = deferred<ReturnType<typeof approved>>(); api.post.mockReturnValue(response.promise);
        const w = await page(); await open(w); await choice('确认审批'); expect(api.post).toHaveBeenCalledTimes(1);
        if (number === 12) { useAuthStore().setUser(identity('FIN-OTHER')); await flushPromises(); }
        else { w.unmount(); wrapper = undefined; }
        response.resolve(approved(9)); await flushPromises(); expect(successText()).toBe('');
        if (number === 12) { expect(w.text()).toContain(otherRow.counterpartyName); expect(w.text()).not.toContain(row.counterpartyName); }
      } else if (number === 13) {
        const old = deferred<ReturnType<typeof listed>>(); api.get.mockImplementation((path: string) => path.includes('/FIN-OTHER/') ? Promise.resolve(listed(otherRow)) : old.promise);
        const w = await page(); useAuthStore().setUser(identity('FIN-OTHER')); await flushPromises();
        old.resolve(listed(row)); await flushPromises(); expect(w.text()).toContain(otherRow.counterpartyName); expect(w.text()).not.toContain(row.counterpartyName);
      } else if (number === 14) {
        const old = deferred<ReturnType<typeof listed>>(); const w = await page();
        api.get.mockReturnValueOnce(old.promise); await refresh(w);
        api.get.mockResolvedValueOnce(listed({ ...row, counterpartyName: 'Newest list' })); await refresh(w);
        old.resolve(listed({ ...row, counterpartyName: 'Stale list' })); await flushPromises();
        expect(w.text()).toContain('Newest list'); expect(w.text()).not.toContain('Stale list');
      } else if (number === 17) {
        row.operatedBy = 86; const w = await page(); expect(w.findAll('button').filter(b => b.text() === '审批')).toHaveLength(0);
        await w.find('.el-table__body-wrapper input[type=checkbox]').setValue(true); await flushPromises();
        const bulk = w.findAll('button').find(b => b.text() === '批量审批'); expect(bulk).toBeDefined(); await bulk!.trigger('click'); await flushPromises();
        expect(document.body.textContent).toContain('均为您本人提交'); expect(api.post).not.toHaveBeenCalled();
      } else if (number === 18) {
        const p = usePermissionStore(); p.dbPermissions = { ...p.currentPermissions, finance: 'r' }; p.isDbLoaded = true;
        const w = await page(); expect(w.text()).toContain(row.counterpartyName); expect(w.findAll('button').filter(b => ['审批', '驳回'].includes(b.text()))).toHaveLength(0);
        await w.find('.el-table__body-wrapper input[type=checkbox]').setValue(true); await flushPromises();
        const bulk = w.findAll('button').find(b => b.text() === '批量审批'); expect(bulk?.attributes('disabled')).toBeDefined(); expect(api.post).not.toHaveBeenCalled();
      } else if (number === 19) {
        row.amount = null; const w = await page(); expect(w.text()).toContain('应收 未知');
        const text = await open(w); expect(text).toContain('金额: 应收 未知'); expect(text).not.toContain('NaN'); await choice('取消'); expect(api.post).not.toHaveBeenCalled();
      }
    });
  });
}
