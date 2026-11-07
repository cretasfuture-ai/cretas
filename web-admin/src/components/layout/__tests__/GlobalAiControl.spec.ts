import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import type { PageAgentState } from '@/ai/pageAgentRuntime';

const mock = vi.hoisted(() => ({
  state: { active: false, phase: 'idle', goal: '', runKey: '', stage: '', error: '', scope: null, moduleStep: 1, actions: 0, remaining: 0, businessPending: false, uncertain: false, events: [], completed: [] } as PageAgentState,
  auth: { factoryId: 'F006', user: { id: 1309 }, currentRole: 'factory_super_admin' },
  canWrite: vi.fn(() => true), start: vi.fn(async () => {}), pause: vi.fn(), next: vi.fn(), resume: vi.fn(), end: vi.fn(async () => {}), install: vi.fn(() => vi.fn()),
}));
vi.mock('@/ai/pageAgentRuntime', async () => {
  const { reactive } = await import('vue'); mock.state = reactive(mock.state);
  return { pageAgentState: mock.state, installPageAgent: mock.install, startGlobalPageAgent: mock.start, pauseGlobalPageAgent: mock.pause, nextGlobalPageModule: mock.next, continueGlobalPageAgent: mock.resume, endGlobalPageAgent: mock.end };
});
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => mock.auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: mock.canWrite }) }));
vi.mock('@/demo/f006NativeRuntime', () => ({ nativePlayback: { enabled: false } }));
vi.mock('@/demo/f006Sandbox', () => ({ sandboxSession: { value: null } }));
vi.mock('vue-router', () => ({ useRouter: () => ({}) }));
import GlobalAiControl from '../GlobalAiControl.vue';

const stubs = {
  teleport: true,
  'el-button': { props: ['disabled', 'loading'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-checkbox': { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<label><input type="checkbox" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)"/><slot/></label>' },
  'el-popover': { template: '<div><slot name="reference"/><slot/></div>' },
  'el-alert': { props: ['title'], template: '<div role="alert">{{title}}</div>' },
};
function create(ready = true) { return mount(GlobalAiControl, { props: { bootstrapReady: ready }, global: { stubs } }); }
beforeEach(() => {
  vi.clearAllMocks(); mock.canWrite.mockReturnValue(true);
  mock.auth.factoryId = 'F006'; mock.auth.user.id = 1309;
  Object.assign(mock.state, { active: false, phase: 'idle', goal: '', runKey: '', stage: '', error: '', scope: null, moduleStep: 1, actions: 0, remaining: 0, businessPending: false, uncertain: false, events: [], completed: [] });
});

describe('global original-page AI controls', () => {
  it('starts a natural-language goal in an isolated F006 slot without a side execution panel', async () => {
    const wrapper = create();
    await wrapper.get('[data-testid="global-ai-open"]').trigger('click');
    await wrapper.get('textarea[aria-label="全局AI操作任务"]').setValue('采购100公斤原料，保存后创建销售单');
    await wrapper.get('[data-testid="global-ai-start"]').trigger('click'); await flushPromises();
    expect(mock.start).toHaveBeenCalledWith('采购100公斤原料，保存后创建销售单', true);
    expect(wrapper.find('[data-testid="demo-guide"]').exists()).toBe(false);
    wrapper.unmount();
  });
  it('does not offer actions to a read-only role and waits for authenticated bootstrap', () => {
    mock.canWrite.mockReturnValue(false);
    const viewer = create(); expect(viewer.find('[data-testid="global-ai-control"]').exists()).toBe(false); viewer.unmount();
    mock.canWrite.mockReturnValue(true);
    const preparing = create(false); expect(preparing.get('[data-testid="global-ai-open"]').attributes('disabled')).toBeDefined(); preparing.unmount();
  });
  it('does not expose the initial rollout to other production factories', () => {
    mock.auth.factoryId = 'OTHER';
    const wrapper = create();
    expect(wrapper.find('[data-testid="global-ai-control"]').exists()).toBe(false);
    expect(mock.start).not.toHaveBeenCalled(); wrapper.unmount();
  });
  it('only enables Next after the controller has finished submission and readback', async () => {
    Object.assign(mock.state, { active: true, scope: { factoryId: 'F006', actorId: '1309', sandboxId: 'own' }, phase: 'submitting', businessPending: false });
    const wrapper = create();
    expect(wrapper.get('[data-testid="global-ai-control"]').classes()).toContain('is-active');
    expect(document.body.classList.contains('global-page-agent-active')).toBe(true);
    expect(wrapper.get('[data-testid="global-ai-next"]').attributes('disabled')).toBeDefined();
    mock.state.phase = 'awaiting-next'; await flushPromises();
    expect(wrapper.get('[data-testid="global-ai-next"]').attributes('disabled')).toBeUndefined();
    await wrapper.get('[data-testid="global-ai-next"]').trigger('click'); expect(mock.next).toHaveBeenCalledOnce();
    expect(mock.resume).not.toHaveBeenCalled();
    wrapper.unmount();
    expect(document.body.classList.contains('global-page-agent-active')).toBe(false);
  });
  it('shows actual field changes and a submission result as escaped readable events', () => {
    Object.assign(mock.state, { active: true, scope: { factoryId: 'F006', actorId: '1309' }, phase: 'paused', events: [
      { at: '2026-11-07T01:00:00Z', kind: 'action', text: '填写“数量”', before: '0', after: '100' },
      { at: '2026-11-07T01:00:02Z', kind: 'readback', text: '已回读原单据：PO-123<script>x</script>' },
    ] });
    const wrapper = create();
    expect(wrapper.text()).toContain('0 → 100'); expect(wrapper.text()).toContain('已回读原单据：PO-123');
    expect(wrapper.find('script').exists()).toBe(false);
    wrapper.unmount();
  });
  it('cannot Continue an uncertain write and disables exit while a submit is in flight', () => {
    Object.assign(mock.state, { active: true, scope: { factoryId: 'F006', actorId: '1309' }, phase: 'error', uncertain: true, businessPending: true });
    const wrapper = create();
    expect(wrapper.find('[data-testid="global-ai-continue"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('交还手动操作');
    expect(wrapper.get('[data-testid="global-ai-next"]').attributes('disabled')).toBeDefined();
    expect(wrapper.get('[data-testid="global-ai-end"]').attributes('disabled')).toBeDefined();
    wrapper.unmount();
  });
  it('does not disclose the previous actor task or its journal after a factory switch', () => {
    Object.assign(mock.state, { active: true, scope: { factoryId: 'OTHER', actorId: '2' }, phase: 'paused', goal: 'private task', stage: 'secret supplier', events: [{ at: '', kind: 'action', text: 'private record' }] });
    const wrapper = create();
    expect(wrapper.text()).toContain('账号或工厂已变化');
    expect(wrapper.text()).not.toContain('private'); expect(wrapper.text()).not.toContain('secret supplier');
    wrapper.unmount();
  });
});
