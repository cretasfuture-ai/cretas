import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nuxtUiStubs } from './nuxtUiStubs';

/**
 * 供应商档案区（联系人 / 地址 / 银行账户，迁 Nuxt UI 之后）。
 *
 * 守的是迁移前就有的行为：
 * ① 空态都有下一步（有写权限时给「新增」按钮，没有时不给）；
 * ② 有数据时渲染成行，「主」标记只在主记录上、「设为主」只在非主记录上；
 * ③ 校验口径与原 Element rules 一致（手机号 / 邮箱 / 8-32 位纯数字账号）；
 * ④ 保存后用后端返回的整份列表替换本地列表（主标记由后端重算，前端不 patch）并通知抽屉重拉主档。
 */
const api = vi.hoisted(() => ({
  listSupplierContacts: vi.fn(), listSupplierAddresses: vi.fn(), listSupplierBankAccounts: vi.fn(),
  saveSupplierContact: vi.fn(), saveSupplierAddress: vi.fn(), saveSupplierBankAccount: vi.fn(),
  deleteSupplierContact: vi.fn(), deleteSupplierAddress: vi.fn(), deleteSupplierBankAccount: vi.fn(),
}));

vi.mock('@/api/supplierManagement', async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  ...api,
}));
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm'), prompt: vi.fn(), alert: vi.fn() },
}));

import SupplierProfileCollections from '../SupplierProfileCollections.vue';

type Vm = {
  validateContact: (s: Record<string, unknown>) => Array<{ name: string; message: string }>;
  validateAddress: (s: Record<string, unknown>) => Array<{ name: string; message: string }>;
  validateBank: (s: Record<string, unknown>) => Array<{ name: string; message: string }>;
  contactForm: Record<string, unknown>;
  openContact: (row?: unknown) => void;
  submitContact: () => Promise<void>;
  contacts: unknown[];
};

const CONTACT = (over: Record<string, unknown> = {}) => ({
  id: 'c1', name: '张三', contactType: 'SALES', phone: '13800138000', email: 'a@b.com',
  position: '经理', isPrimary: false, notes: '', version: 1, ...over,
});

async function mountCollections(canWrite: boolean) {
  const wrapper = mount(SupplierProfileCollections, {
    props: { factoryId: 'F006', supplierId: 'SUP-1', supplierName: '六膳门', canWrite },
    global: { stubs: nuxtUiStubs },
  });
  await flushPromises();
  return wrapper;
}

describe('供应商档案区 (联系人/地址/银行账户)', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.listSupplierContacts.mockResolvedValue([]);
    api.listSupplierAddresses.mockResolvedValue([]);
    api.listSupplierBankAccounts.mockResolvedValue([]);
  });

  it('① 三个空态都给下一步: 有写权限 ⇒ 每个空态里有「新增…」按钮', async () => {
    const wrapper = await mountCollections(true);
    const empties = wrapper.findAll('.stub-empty-state');
    expect(empties, '三个空态都应渲染').toHaveLength(3);
    expect(empties.map((e) => e.find('button').text())).toEqual(['新增联系人', '新增地址', '新增银行账户']);
    wrapper.unmount();
  });

  it('① 阴性对照: 无写权限 ⇒ 空态仍在, 但没有任何新增按钮', async () => {
    const wrapper = await mountCollections(false);
    expect(wrapper.findAll('.stub-empty-state')).toHaveLength(3);
    expect(wrapper.findAll('button').map((b) => b.text()).filter((t) => t.startsWith('新增'))).toEqual([]);
    wrapper.unmount();
  });

  it('② 有数据: 渲染成行, 「主」只在主记录上, 「设为主」只在非主记录上', async () => {
    api.listSupplierContacts.mockResolvedValue([CONTACT({ id: 'c1', name: '张三', isPrimary: true }), CONTACT({ id: 'c2', name: '李四' })]);
    const wrapper = await mountCollections(true);
    const nameCol = wrapper.find('[data-testid="supplier-contacts"] [data-col="姓名"]');
    const cells = nameCol.findAll('.cell');
    expect(cells, '联系人行没渲染出来 —— 后面的断言无从谈起').toHaveLength(2);
    expect(cells[0].text()).toContain('主');
    expect(cells[0].text()).toContain('张三');
    expect(cells[1].text()).not.toContain('主');
    const actions = wrapper.find('[data-testid="supplier-contacts"] [data-col="操作"]').findAll('.cell');
    expect(actions[0].text()).not.toContain('设为主');
    expect(actions[1].text()).toContain('设为主');
    wrapper.unmount();
  });

  it('③ 校验口径与原 Element rules 一致', async () => {
    const wrapper = await mountCollections(true);
    const vm = wrapper.vm as unknown as Vm;

    // 联系人: 姓名必填; 电话/邮箱选填但填了就要合法
    expect(vm.validateContact({ name: '', phone: '', email: '' }).map((e) => e.name)).toEqual(['name']);
    expect(vm.validateContact({ name: '张三', phone: '123', email: 'x' }).map((e) => e.name)).toEqual(['phone', 'email']);
    expect(vm.validateContact({ name: '张三', phone: '13800138000', email: 'a@b.com' })).toEqual([]);

    // 地址: 必填 + 必须可识别(不能只填符号)
    expect(vm.validateAddress({ address: '' }).map((e) => e.name)).toEqual(['address']);
    expect(vm.validateAddress({ address: '###' }).map((e) => e.name)).toEqual(['address']);
    expect(vm.validateAddress({ address: '上海市浦东新区', contactPhone: '12' }).map((e) => e.name)).toEqual(['contactPhone']);
    expect(vm.validateAddress({ address: '上海市浦东新区', contactPhone: '' })).toEqual([]);

    // 银行: 开户行必填; 账号 8-32 位纯数字
    expect(vm.validateBank({ bankName: '', accountNumber: '' }).map((e) => e.name)).toEqual(['bankName', 'accountNumber']);
    expect(vm.validateBank({ bankName: '工行', accountNumber: '1234 5678' }).map((e) => e.name)).toEqual(['accountNumber']);
    expect(vm.validateBank({ bankName: '工行', accountNumber: '1234567' }).map((e) => e.name)).toEqual(['accountNumber']);
    expect(vm.validateBank({ bankName: '工行', accountNumber: '62220212345678' })).toEqual([]);
    wrapper.unmount();
  });

  it('④ 保存: 用后端返回的整份列表替换本地, 并通知抽屉重拉主档', async () => {
    const wrapper = await mountCollections(true);
    const vm = wrapper.vm as unknown as Vm;
    const fromServer = [CONTACT({ id: 'c9', name: '王五', isPrimary: true })];
    api.saveSupplierContact.mockResolvedValue(fromServer);

    vm.openContact();
    vm.contactForm.name = '王五';
    await vm.submitContact();
    await flushPromises();

    expect(api.saveSupplierContact).toHaveBeenCalledWith('F006', 'SUP-1', expect.objectContaining({ name: '王五' }));
    expect(vm.contacts).toEqual(fromServer);
    expect(wrapper.emitted('changed')).toHaveLength(1);
    wrapper.unmount();
  });
});
