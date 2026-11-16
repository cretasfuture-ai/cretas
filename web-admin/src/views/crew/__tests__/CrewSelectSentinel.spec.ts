import { defineComponent } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({ ledger: vi.fn(), settings: vi.fn(), updateRoleMapping: vi.fn(), updateMembers: vi.fn() }));
vi.mock('@/api/digitalCrew', async (orig) => ({ ...(await orig<typeof import('@/api/digitalCrew')>()), crewApi: api }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F1' }) }));

import CrewLedger from '../CrewLedger.vue';
import CrewSettings from '../CrewSettings.vue';

// reka-ui 的 SelectItem 不接受空字符串 value；桩按真实 items 渲染 option，便于断言与选择
const SelectStub = defineComponent({
  props: { modelValue: { type: String, default: '' }, items: { type: Array, default: () => [] } },
  emits: ['update:modelValue'],
  template: `<select v-bind="$attrs" :value="modelValue" @change="$emit('update:modelValue', $event.target.value)">
    <option v-for="i in items" :key="i.value" :value="i.value">{{ i.label }}</option></select>`,
});
const ButtonStub = defineComponent({
  props: { disabled: Boolean, loading: Boolean },
  emits: ['click'],
  template: '<button v-bind="$attrs" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
});
const InputStub = defineComponent({
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template: '<input v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
});
const Pass = defineComponent({ template: '<div v-bind="$attrs"><slot /><slot name="actions" /></div>' });
const stubs = { USelect: SelectStub, UButton: ButtonStub, UInput: InputStub, UAlert: Pass, UBadge: Pass };

const optionValues = (w: ReturnType<typeof mount>, sel: string) =>
  w.find(sel).findAll('option').map((o) => (o.element as HTMLOptionElement).value);

beforeEach(() => {
  setActivePinia(createPinia());
  Object.values(api).forEach((f) => f.mockReset());
});

describe('数字班组下拉不用空字符串作选项值', () => {
  it('工作记录：全部数字员工用哨兵值；选全部不传 member，选具体员工传 key', async () => {
    api.ledger.mockResolvedValue({
      today: '2026-11-16', quotaToday: 0, quotaThisMonth: 0, quotaTotal: 0, delegated: 0, done: 0, records: [],
      perMember: [{ memberKey: 'planner', memberName: '数字计划员', delegated: 0, confirming: 0, done: 0, notDone: 0, outcomeUnknown: 0, quota: 0 }],
    });
    const w = mount(CrewLedger, { global: { stubs } });
    await flushPromises();
    expect(api.ledger).toHaveBeenLastCalledWith('F1', undefined);
    const vals = optionValues(w, 'select');
    expect(vals).toEqual(['__all__', 'planner']);
    expect(vals).not.toContain('');
    await w.find('select').setValue('planner');
    await flushPromises();
    expect(api.ledger).toHaveBeenLastCalledWith('F1', 'planner');
    await w.find('select').setValue('__all__');
    await flushPromises();
    expect(api.ledger).toHaveBeenLastCalledWith('F1', undefined);
  });

  const member = (key: string, name: string) => ({
    key, name, shortName: name, duty: '', enabled: true, modules: [], moduleLevels: {}, level: 'rw', capabilities: [], workdesks: [],
  });
  const settings = () => ({
    members: [member('planner', '数字计划员'), member('qc', '数字质检员')],
    roleMapping: { a: 'planner', b: null, c: null }, roleLabels: { a: '岗位A', b: '岗位B', c: '岗位C' },
    logs: [], canEdit: true,
  });

  it('设置：不给入口用哨兵值；映射回写 null / 原 key，与改前请求体一致', async () => {
    api.settings.mockResolvedValue(settings());
    api.updateRoleMapping.mockResolvedValue(settings());
    const w = mount(CrewSettings, { global: { stubs } });
    await flushPromises();
    const sels = w.findAll('select');
    expect(sels.length).toBe(3);
    for (const s of sels) expect(s.findAll('option').map((o) => (o.element as HTMLOptionElement).value)).not.toContain('');
    // 读：null 映射显示为「不给入口」哨兵，未改动不算 dirty
    expect((sels[1].element as HTMLSelectElement).value).toBe('__none__');
    expect((sels[0].element as HTMLSelectElement).value).toBe('planner');
    const save = w.findAll('button').find((b) => b.text() === '保存岗位对应')!;
    expect(save.attributes('disabled')).toBeDefined();
    // 写：A 改成不给入口 -> null；B 选 qc -> 'qc'；未改的 C 不出现
    await sels[0].setValue('__none__');
    await sels[1].setValue('qc');
    const inputs = w.findAll('input').filter((i) => i.attributes('type') !== 'checkbox');
    await inputs[inputs.length - 1].setValue('调整岗位对应');
    await save.trigger('click');
    await flushPromises();
    expect(api.updateRoleMapping).toHaveBeenCalledTimes(1);
    expect(api.updateRoleMapping).toHaveBeenCalledWith('F1', { a: null, b: 'qc' }, '调整岗位对应');
  });
});
