import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const apiMocks = vi.hoisted(() => ({
  getPlatformFactorySecrecy: vi.fn(),
  updatePlatformFactorySecrecy: vi.fn(),
  bulkPlatformFactorySecrecy: vi.fn(),
}));
const messageMocks = vi.hoisted(() => ({
  confirm: vi.fn().mockResolvedValue(true),
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
}));

vi.mock('@/api/factorySecrecy', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/factorySecrecy')>()),
  ...apiMocks,
}));
vi.mock('element-plus', () => ({
  ElMessage: { success: messageMocks.success, error: messageMocks.error, warning: messageMocks.warning },
  ElMessageBox: { confirm: messageMocks.confirm },
}));

import FactorySecrecyPanel from '../FactorySecrecyPanel.vue';
import { SECRECY_ITEMS } from '@/api/factorySecrecy';

const settings = (overrides: Record<string, { stored: string; effective: boolean; inEffect?: boolean }> = {}) => ({
  factoryId: 'F006',
  items: SECRECY_ITEMS.map((item) => ({
    code: item.code, letter: item.letter, label: item.label,
    stored: overrides[item.code]?.stored ?? 'UNSET',
    effective: overrides[item.code]?.effective ?? false,
    inEffect: overrides[item.code]?.inEffect ?? overrides[item.code]?.effective ?? false,
  })),
  recentAudit: [{
    id: 1, itemCode: 'PROCESS_SECRET', oldValue: 'UNSET', newValue: 'ON', actorId: 11, actorRole: 'super_admin',
    reason: '客户要求', confirmed: false, bulk: false, changedAt: '2026-11-03T10:00:00',
  }],
});

function mountPanel() {
  return shallowMount(FactorySecrecyPanel, {
    props: { factoryId: 'F006' },
    global: { stubs: {
      'el-alert': true, 'el-button': true, 'el-empty': true, 'el-input': true, 'el-skeleton': true,
      'el-switch': true, 'el-tag': true, 'el-radio-group': true, 'el-radio-button': true,
      'el-table': true, 'el-table-column': true,
    } },
  });
}

describe('FactorySecrecyPanel (平台「工厂管理 → 保密设置」)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    messageMocks.confirm.mockResolvedValue(true);
    apiMocks.getPlatformFactorySecrecy.mockResolvedValue({ success: true, data: settings() });
    apiMocks.updatePlatformFactorySecrecy.mockResolvedValue({ success: true, data: {} });
    apiMocks.bulkPlatformFactorySecrecy.mockResolvedValue({ success: true, data: settings() });
  });

  it('shows one control per item with the plain-language off effect under it', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    for (const item of SECRECY_ITEMS) {
      const card = wrapper.find(`[data-testid="secrecy-item-${item.code}"]`);
      expect(card.exists()).toBe(true);
      expect(card.text()).toContain(item.label);
      expect(card.text()).toContain(item.offEffect);
    }
    // b 的说明写清只在工艺保密开着时起作用
    expect(wrapper.find('[data-testid="secrecy-item-PLATFORM_RECIPE_BLOCK"]').text()).toContain('只在「工艺保密」开启时起作用');
    // Steve 2026-11-04: b 开 = 平台人员不能看 / 改配方和成本, 但授权的平台 AI 入口不受影响、每次留痕
    expect(wrapper.find('[data-testid="secrecy-item-PLATFORM_RECIPE_BLOCK"]').text()).toContain('开启时，平台人员不能自己查看、编辑配方和成本');
    expect(wrapper.find('[data-testid="secrecy-item-PLATFORM_RECIPE_BLOCK"]').text()).toContain('这一项不影响平台授权的 AI 分析入口');
    // f 是三态
    expect(wrapper.find('[data-testid="secrecy-item-AI_SECRECY_AUDIT"] el-radio-group-stub').exists()).toBe(true);
  });

  it('refuses to change a reason-required item without a reason and sends nothing', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    const vm = wrapper.vm as unknown as { onToggle: (code: string, enabled: boolean) => Promise<void>; reason: string };
    await vm.onToggle('PROCESS_SECRET', true);
    expect(apiMocks.updatePlatformFactorySecrecy).not.toHaveBeenCalled();
    expect(messageMocks.warning).toHaveBeenCalled();
    vm.reason = '客户要求';
    await vm.onToggle('PROCESS_SECRET', true);
    expect(apiMocks.updatePlatformFactorySecrecy).toHaveBeenCalledWith('F006', 'PROCESS_SECRET',
      expect.objectContaining({ enabled: true, reason: '客户要求' }));
  });

  it('asks for confirmation with the contract warning before turning customer-held off', async () => {
    apiMocks.getPlatformFactorySecrecy.mockResolvedValue({ success: true,
      data: settings({ CUSTOMER_HELD: { stored: 'ON', effective: true } }) });
    const wrapper = mountPanel();
    await flushPromises();
    const vm = wrapper.vm as unknown as { onToggle: (code: string, enabled: boolean) => Promise<void>; reason: string };
    vm.reason = '客户撤约';
    messageMocks.confirm.mockRejectedValueOnce('cancel');
    await vm.onToggle('CUSTOMER_HELD', false);
    expect(messageMocks.confirm.mock.calls[0][0]).toContain('这会撤销与客户合同中约定的云端不保管工艺');
    expect(apiMocks.updatePlatformFactorySecrecy).not.toHaveBeenCalled();
    await vm.onToggle('CUSTOMER_HELD', false);
    expect(apiMocks.updatePlatformFactorySecrecy).toHaveBeenCalledWith('F006', 'CUSTOMER_HELD',
      expect.objectContaining({ enabled: false, confirmed: true, reason: '客户撤约' }));
  });

  it('bulk off goes through the server bulk endpoint with confirmation when customer-held is on', async () => {
    apiMocks.getPlatformFactorySecrecy.mockResolvedValue({ success: true,
      data: settings({ CUSTOMER_HELD: { stored: 'ON', effective: true } }) });
    const wrapper = mountPanel();
    await flushPromises();
    const vm = wrapper.vm as unknown as { onBulk: (enabled: boolean) => Promise<void>; reason: string };
    await vm.onBulk(false);
    expect(apiMocks.bulkPlatformFactorySecrecy).not.toHaveBeenCalled();   // 没理由
    vm.reason = '合同终止';
    await vm.onBulk(false);
    expect(messageMocks.confirm).toHaveBeenCalledTimes(2);   // 全关确认 + 关 e 的合同确认
    expect(apiMocks.bulkPlatformFactorySecrecy).toHaveBeenCalledWith('F006',
      { enabled: false, reason: '合同终止', confirmed: true });
    expect(apiMocks.updatePlatformFactorySecrecy).not.toHaveBeenCalled();   // 不是前端逐项调
  });

  it('the audit-mode radio can follow process secret', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    const vm = wrapper.vm as unknown as { onAuditMode: (mode: string) => Promise<void> };
    await vm.onAuditMode('FOLLOW');
    expect(apiMocks.updatePlatformFactorySecrecy).toHaveBeenCalledWith('F006', 'AI_SECRECY_AUDIT',
      expect.objectContaining({ followProcessSecret: true }));
  });
});
