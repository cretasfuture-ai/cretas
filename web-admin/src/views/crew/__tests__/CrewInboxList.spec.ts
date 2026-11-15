import { defineComponent } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  inbox: vi.fn(), preview: vi.fn(), confirm: vi.fn(), reject: vi.fn(),
}));
vi.mock('@/api/digitalCrew', async (orig) => ({ ...(await orig<typeof import('@/api/digitalCrew')>()), crewApi: api }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F1' }) }));

import CrewInboxList from '../CrewInboxList.vue';

const ButtonStub = defineComponent({
  props: { disabled: Boolean, loading: Boolean },
  emits: ['click'],
  template: '<button v-bind="$attrs" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
});
const SelectStub = defineComponent({
  props: { modelValue: { type: String, default: '' }, items: { type: Array, default: () => [] } },
  emits: ['update:modelValue'],
  template: '<select v-bind="$attrs" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="i in items" :key="i" :value="i">{{ i }}</option></select>',
});
const TextareaStub = defineComponent({
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template: '<textarea v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
});
const Pass = defineComponent({ props: { label: { type: String, default: '' }, description: { type: String, default: '' } }, template: '<div v-bind="$attrs">{{ label }}{{ description }}<slot /><slot name="actions" /></div>' });

const item = {
  id: 7, memberKey: 'planner', memberName: '数字计划员', intentCode: 'PRODUCTION_PLAN_CREATE', intentName: '新建生产计划',
  title: '新建生产计划 · 明天加 500kg', userInput: '明天加 500kg', module: 'production', requiredPermission: 'production:read_write',
  status: 'DELEGATED', statusLabel: '已交办', quotaCost: 0, previewSummary: '数字计划员准备办', createdByName: '张三',
  createdAt: '2026-11-15T10:00:00', confirmedByName: null, confirmedAt: null, resolvedAt: null, resolvedDate: null,
  resultStatus: null, resultNote: null, evidence: null, previewedByMe: false, canConfirm: true,
};
const action = {
  confirmToken: 'tok', commandDigest: 'd'.repeat(64), expiresAt: '2026-11-15T10:05:00Z',
  currentValues: { quantity: 0, productName: '卤牛肉' }, newValues: { quantity: 500, productName: '卤牛肉', someNewKey: 'x' },
};

function open() {
  return mount(CrewInboxList, {
    global: { stubs: { UButton: ButtonStub, USelect: SelectStub, UTextarea: TextareaStub, UAlert: Pass, UBadge: Pass, RouterLink: Pass } },
  });
}

beforeEach(() => {
  setActivePinia(createPinia());
  Object.values(api).forEach((f) => f.mockReset());
  api.inbox.mockResolvedValue({ mine: [item], hiddenCount: 1, hiddenModules: ['quality'] });
});

describe('等我确认（P8）', () => {
  it('预览之后不点确认，就不发确认请求；点一次恰好一次；改前改后每一行都显示（新英文键也不藏）', async () => {
    api.preview.mockResolvedValue({ item, preview: { status: 'PREVIEW', confirmableAction: action } });
    api.confirm.mockResolvedValue({ ...item, status: 'DONE', statusLabel: '已办成', quotaCost: 1, evidence: '回读预览凭据：已确认执行' });
    const w = open();
    await flushPromises();
    expect(w.find('[data-testid="crew-inbox-hidden"]').text()).toContain('另有 1 件');
    expect(w.find('[data-testid="crew-confirm"]').exists()).toBe(false);
    await w.find('[data-testid="crew-preview"]').trigger('click');
    await flushPromises();
    const rows = w.findAll('[data-testid="ai-confirm-rows"] tr');
    expect(rows.length).toBe(3);
    expect(w.find('[data-testid="ai-confirm-rows"]').text()).toContain('someNewKey');
    expect(api.confirm).toHaveBeenCalledTimes(0);
    await w.find('[data-testid="crew-confirm"]').trigger('click');
    await flushPromises();
    expect(api.confirm).toHaveBeenCalledTimes(1);
    expect(api.confirm).toHaveBeenCalledWith('F1', 7, action);
    expect(w.find('[data-testid="crew-item-result"]').text()).toContain('已办成');
    expect(w.find('[data-testid="crew-item-result"]').text()).toContain('扣 1 个额度');
  });

  it('预览没给出凭据 ⇒ 确认按钮不可点', async () => {
    api.preview.mockResolvedValue({ item, preview: { status: 'NO_PERMISSION', message: '您没有权限执行此操作' } });
    const w = open();
    await flushPromises();
    await w.find('[data-testid="crew-preview"]').trigger('click');
    await flushPromises();
    expect(w.find('[data-testid="crew-confirm"]').attributes('disabled')).toBeDefined();
    expect(w.text()).toContain('您没有权限执行此操作');
  });

  it('服务端拒绝确认（403）⇒ 原样显示服务端的话，并提示先核对、勿重复办理', async () => {
    api.preview.mockResolvedValue({ item, preview: { status: 'PREVIEW', confirmableAction: action } });
    api.confirm.mockRejectedValue(new Error('你没有确认这件事的权限（需要 production:read_write），服务端已拒绝，没有执行任何写操作'));
    const w = open();
    await flushPromises();
    await w.find('[data-testid="crew-preview"]').trigger('click');
    await flushPromises();
    await w.find('[data-testid="crew-confirm"]').trigger('click');
    await flushPromises();
    expect(w.find('[data-testid="crew-item-error"]').text()).toContain('服务端已拒绝');
  });

  it('不办：选「其他」必须写原因；选标准原因直接记没办成', async () => {
    api.reject.mockResolvedValue({ ...item, status: 'NOT_DONE', statusLabel: '没办成', resultNote: '不办：其他原因' });
    const w = open();
    await flushPromises();
    await w.find('[data-testid="crew-reject"]').trigger('click');
    await w.find('select').setValue('其他');
    await w.find('[data-testid="crew-reject-submit"]').trigger('click');
    await flushPromises();
    expect(api.reject).not.toHaveBeenCalled();
    expect(w.find('[data-testid="crew-item-error"]').text()).toContain('至少 2 个字');
    await w.find('textarea').setValue('客户改主意了');
    await w.find('[data-testid="crew-reject-submit"]').trigger('click');
    await flushPromises();
    expect(api.reject).toHaveBeenCalledWith('F1', 7, '客户改主意了');
    expect(w.find('[data-testid="crew-item-result"]').text()).toContain('没办成');
  });

  it('读取失败 ⇒ 写明失败，不显示「没有等你确认的事」', async () => {
    api.inbox.mockRejectedValue(new Error('网络断了'));
    const w = open();
    await flushPromises();
    expect(w.find('[data-testid="crew-inbox-error"]').exists()).toBe(true);
    expect(w.find('[data-testid="crew-inbox-empty"]').exists()).toBe(false);
  });
});
