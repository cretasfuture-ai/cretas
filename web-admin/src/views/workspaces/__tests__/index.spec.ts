import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';

const route = { path: '/workspaces/overview' };
const push = vi.fn();
const resolve = vi.fn((path: string) => ({
  matched: [{}],
  path: path.split('?')[0],
  meta: { module: path.includes('ai-reports') ? 'analytics' : 'dashboard' },
}));
const identity = {
  userType: 'factory',
  currentRole: 'factory_super_admin',
  factoryType: 'FACTORY',
  factoryId: 'F006',
  isPlatform: false,
  user: { userType: 'factory', factoryUser: { factoryType: 'FACTORY' } },
};
const canAccess = vi.fn((module: string) => module === 'dashboard' || module === 'analytics');
const isPathVisible = vi.fn(() => true);
let navigationStatus: 'unavailable' | 'loading' | 'ready' | 'error' = 'ready';

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push, resolve }),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canAccess }) }));
vi.mock('@/store/modules/navigationVisibility', () => ({
  useNavigationVisibilityStore: () => ({
    isPathVisible,
    get status() { return navigationStatus; },
  }),
}));

import WorkspaceView from '../index.vue';

const stubs = {
  DecisionHistoryPanel: {
    props: ['factoryId', 'role'],
    template: '<section data-testid="history-review-panel" :data-factory="factoryId" :data-role="role"></section>',
  },
  'el-tag': { template: '<span><slot /></span>' },
  'el-empty': { props: ['description'], template: '<div data-testid="workspace-empty">{{ description }}</div>' },
};

describe('factory workspace view', () => {
  it('mounts recorded decision review only in the eligible trace workspace with system access', () => {
    route.path = '/workspaces/trace-review';
    canAccess.mockImplementation((module) => module === 'dashboard' || module === 'system');
    const wrapper = mount(WorkspaceView, { global: { stubs } });
    const panel = wrapper.find('[data-testid="history-review-panel"]');
    expect(panel.exists()).toBe(true);
    expect(panel.attributes('data-factory')).toBe('F006');
    expect(panel.attributes('data-role')).toBe('factory_super_admin');
    wrapper.unmount();
    route.path = '/workspaces/overview';
    const other = mount(WorkspaceView, { global: { stubs } });
    expect(other.find('[data-testid="history-review-panel"]').exists()).toBe(false);
    other.unmount();
    canAccess.mockImplementation((module) => module === 'dashboard' || module === 'analytics');
  });

  it.each(['loading', 'error', 'unavailable', 'ready'] as const)(
    'does not expose decision review with unready navigation or denied system access: %s', (status) => {
      route.path = '/workspaces/trace-review'; navigationStatus = status;
      canAccess.mockImplementation((module) => module === 'dashboard');
      const wrapper = mount(WorkspaceView, { global: { stubs } });
      expect(wrapper.find('[data-testid="history-review-panel"]').exists()).toBe(false);
      wrapper.unmount(); route.path = '/workspaces/overview'; navigationStatus = 'ready';
      canAccess.mockImplementation((module) => module === 'dashboard' || module === 'analytics');
    },
  );

  it('does not mount the historical read panel for a warehouse role', () => {
    route.path = '/workspaces/trace-review'; identity.currentRole = 'warehouse_manager';
    canAccess.mockImplementation(() => true);
    const wrapper = mount(WorkspaceView, { global: { stubs } });
    expect(wrapper.find('[data-testid="history-review-panel"]').exists()).toBe(false);
    wrapper.unmount(); route.path = '/workspaces/overview'; identity.currentRole = 'factory_super_admin';
    canAccess.mockImplementation((module) => module === 'dashboard' || module === 'analytics');
  });

  it('shows links from the selected workspace and navigates to the real page', async () => {
    const wrapper = mount(WorkspaceView, { global: { stubs } });

    expect(wrapper.find('h1').text()).toBe('经营总览');
    expect(wrapper.findAll('[data-testid="workspace-link"]')).toHaveLength(2);

    await wrapper.find('[data-testid="workspace-link"]').trigger('click');
    expect(push).toHaveBeenCalledWith('/dashboard');
    wrapper.unmount();
  });

  it('renders an explicit permission empty state when all target modules are denied', () => {
    route.path = '/workspaces/exceptions';
    canAccess.mockImplementation((module) => module === 'dashboard');
    const wrapper = mount(WorkspaceView, { global: { stubs } });

    expect(wrapper.find('[data-testid="workspace-empty"]').text()).toContain('暂无可用入口');
    expect(wrapper.findAll('[data-testid="workspace-link"]')).toHaveLength(0);
    wrapper.unmount();
    route.path = '/workspaces/overview';
  });

  it('does not render workspace business content without a real factory tenant', () => {
    identity.factoryId = '';
    const wrapper = mount(WorkspaceView, { global: { stubs } });

    expect(wrapper.find('[data-testid="workspace-denied"]').text()).toContain('无权访问工作区');
    expect(wrapper.find('h1').text()).not.toBe('经营总览');
    wrapper.unmount();
    identity.factoryId = 'F006';
  });

  it.each([undefined, ''])('denies a raw factory user with missing factoryType: %s', (rawFactoryType) => {
    (identity.user.factoryUser as { factoryType?: string }).factoryType = rawFactoryType;
    const wrapper = mount(WorkspaceView, { global: { stubs } });

    expect(wrapper.find('[data-testid="workspace-denied"]').exists()).toBe(true);
    wrapper.unmount();
    (identity.user.factoryUser as { factoryType?: string }).factoryType = 'FACTORY';
  });

  it.each([
    ['loading', 'workspace-loading'],
    ['error', 'workspace-error'],
    ['unavailable', 'workspace-unavailable'],
  ] as const)('distinguishes navigation state %s from a real permission-empty state', (status, testId) => {
    navigationStatus = status;
    const wrapper = mount(WorkspaceView, { global: { stubs } });

    expect(wrapper.find(`[data-testid="${testId}"]`).exists()).toBe(true);
    expect(wrapper.find('[data-testid="workspace-empty"]').exists()).toBe(false);
    wrapper.unmount();
    navigationStatus = 'ready';
  });
});
