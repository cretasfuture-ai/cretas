import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 报工审批 hub —— 行为验收 (硬约束 9)。
 *
 * 「菜单项消失了」不等于「合并后的新入口真的渲染出内容」——本文件真挂载 2 个
 * **原样未改动**的子页面组件（不是 stub/mock），逐 tab 切换，证明每个视角:
 *   ① 真的渲染进 DOM（找得到该子页面独有的文案标记，不是 el-tabs 的空壳）
 *   ② 真的按它自己原来的方式发起数据请求（endpoint 不变）
 *
 * 只 mock 网络边界 (@/api/request) 与 store —— 2 个子组件本身一行都没改。
 *
 * 🔴 2026-09-02 coordinator 复核 #487 指出的回归 + 判据: 「报工审批」tab 现在按
 * APPROVAL_TAB_ROLES 门控 (见 approvalQueryTab.ts)，「撤回审批」tab 对任何进得了
 * hub 的角色都可见 (与合并前 module:'production' 无 roles[] 等价)。用真实 mount +
 * mockRole 逐角色验证：
 *   dispatcher(不在白名单里, 但有 production:'rw')       → 看得到撤回审批, 看不到报工审批
 *   quality_manager(2026-09-01 那条缺陷的当事角色)         → 看不到报工审批 tab
 *   factory_super_admin(白名单里)                          → 两个 tab 都在
 * 并且默认激活 tab 要按角色回落 —— ⛔ 不能让 dispatcher 落地到一个被隐藏的空白 tab 上。
 */

const mockRoute: { query: Record<string, unknown> } = { query: { tab: 'approval' } };
const routerReplace = vi.fn();

vi.mock('vue-router', () => ({
  useRoute: () => mockRoute,
  useRouter: () => ({ replace: routerReplace, push: vi.fn() }),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'LIUSHANMEN', hasRole: () => false }),
}));

// 默认给一个能看到两个 tab 的角色 (白名单内)，逐角色测试再各自覆盖。
let mockRole = 'factory_super_admin';
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: () => true,
    canAccess: () => true,
    canViewPrice: true,
    get currentRole() { return mockRole; },
  }),
}));

const apiGet = vi.fn();
const apiPost = vi.fn();
const apiPut = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: (...args: unknown[]) => apiPut(...args),
}));

import ApprovalHub from '../index.vue';
import ProductionApproval from '@/views/production/approval/list.vue';
import ProductionReversals from '@/views/production/reversals/list.vue';

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
  apiPut.mockReset();
  routerReplace.mockReset();
  apiGet.mockResolvedValue({ success: true, data: [] });
  apiPost.mockResolvedValue({ success: true, data: [] });
  apiPut.mockResolvedValue({ success: true, data: [] });
  mockRole = 'factory_super_admin';
});

function mountHub() {
  return mount(ApprovalHub, { global: { plugins: [ElementPlus] } });
}

describe('报工审批 hub —— 2 个视角逐一挂载真实子页面 (LIUSHANMEN IA 收拢)', () => {
  it('默认 tab (报工审批): 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'approval' };
    const wrapper = mountHub();
    await flushPromises();
    // approval/list.vue onMounted → loadData() → get(`/${factoryId}/process-work-reporting/pending-approval`)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/process-work-reporting/pending-approval'), expect.anything());
    // 硬约束 9: 用组件身份 (不是文案子串) 证明「只有这一个视角真的挂载了」——
    // el-tab-pane 的 lazy 语义: 没激活过的 pane 连子组件实例都不存在。
    expect(wrapper.findComponent(ProductionApproval).exists()).toBe(true);
    expect(wrapper.findComponent(ProductionReversals).exists()).toBe(false);
    wrapper.unmount();
  });

  it('撤回审批 tab: 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'reversal' };
    const wrapper = mountHub();
    await flushPromises();
    // reversals/list.vue onMounted → loadData() → get(`/${factoryId}/reversals`)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/reversals'));
    expect(wrapper.findComponent(ProductionReversals).exists()).toBe(true);
    expect(wrapper.findComponent(ProductionApproval).exists()).toBe(false);
    wrapper.unmount();
  });

  it('2 个顶层 tab 导航标签都在 (报工审批/撤回审批) —— 不多不少 (白名单角色)', async () => {
    mockRoute.query = { tab: 'approval' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    expect(outerHeader.exists()).toBe(true);
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual(['报工审批', '撤回审批']);
    wrapper.unmount();
  });

  it('切换到未曾激活的 tab 后, router.replace 被调用且带上新的 tab query', async () => {
    mockRoute.query = { tab: 'approval' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const reversalTabItem = outerHeader.findAll('.el-tabs__item')[1];
    expect(reversalTabItem.text()).toBe('撤回审批');
    await reversalTabItem.trigger('click');
    await flushPromises();
    expect(routerReplace).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: 'reversal' }) }),
    );
    expect(wrapper.findComponent(ProductionReversals).exists()).toBe(true);
    wrapper.unmount();
  });

  describe('🔴 tab 门禁按角色区分 (coordinator 复核 #487)', () => {
    it('dispatcher (有 production:rw 但不在 APPROVAL_TAB_ROLES 里): 只看得到撤回审批, 看不到报工审批', async () => {
      mockRole = 'dispatcher';
      mockRoute.query = {}; // 无 tab query, 走默认回落
      const wrapper = mountHub();
      await flushPromises();
      const outerHeader = wrapper.find('.el-tabs__header');
      const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
      expect(labels).toEqual(['撤回审批']);
      expect(labels).not.toContain('报工审批');
      // 默认激活 tab 必须落在 dispatcher 能看到的那个 —— ⛔ 不能是空白页。
      expect(wrapper.findComponent(ProductionReversals).exists()).toBe(true);
      expect(wrapper.findComponent(ProductionApproval).exists()).toBe(false);
      expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/reversals'));
      wrapper.unmount();
    });

    it('quality_manager (2026-09-01 缺陷当事角色): 看不到报工审批 tab', async () => {
      mockRole = 'quality_manager';
      mockRoute.query = {};
      const wrapper = mountHub();
      await flushPromises();
      const outerHeader = wrapper.find('.el-tabs__header');
      const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
      expect(labels).not.toContain('报工审批');
      wrapper.unmount();
    });

    it('factory_super_admin (白名单里): 两个 tab 都在', async () => {
      mockRole = 'factory_super_admin';
      mockRoute.query = {};
      const wrapper = mountHub();
      await flushPromises();
      const outerHeader = wrapper.find('.el-tabs__header');
      const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
      expect(labels).toEqual(['报工审批', '撤回审批']);
      wrapper.unmount();
    });

    it('workshop_supervisor (白名单里, 后端 @RequireRole 直接列名的角色): 两个 tab 都在', async () => {
      mockRole = 'workshop_supervisor';
      mockRoute.query = {};
      const wrapper = mountHub();
      await flushPromises();
      const outerHeader = wrapper.find('.el-tabs__header');
      const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
      expect(labels).toEqual(['报工审批', '撤回审批']);
      wrapper.unmount();
    });

    it('dispatcher 显式请求 ?tab=approval (不可见的 tab) → 回落到撤回审批, 不是空白页', async () => {
      mockRole = 'dispatcher';
      mockRoute.query = { tab: 'approval' };
      const wrapper = mountHub();
      await flushPromises();
      expect(wrapper.findComponent(ProductionReversals).exists()).toBe(true);
      expect(wrapper.findComponent(ProductionApproval).exists()).toBe(false);
      wrapper.unmount();
    });

    it('阳性对照: 同一份 hub 组件在白名单角色下确实能渲染出「报工审批」tab (证明上面的隐藏不是组件本身画不出来)', async () => {
      mockRole = 'production_manager';
      mockRoute.query = {};
      const wrapper = mountHub();
      await flushPromises();
      const outerHeader = wrapper.find('.el-tabs__header');
      const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
      expect(labels).toContain('报工审批');
      wrapper.unmount();
    });
  });
});
