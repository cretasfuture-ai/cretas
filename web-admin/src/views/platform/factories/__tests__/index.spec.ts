import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const apiMocks = vi.hoisted(() => ({
  listPlatformFactories: vi.fn(),
  getFactoryMenuConfig: vi.fn(),
  updateFactoryMenuConfig: vi.fn(),
}));
const contextMocks = vi.hoisted(() => ({
  context: { factoryId: '', factoryName: '', factoryType: '', readOnly: false },
  isViewingFactory: false,
  enterFactoryView: vi.fn(),
  leaveFactoryView: vi.fn(),
}));
const routerMock = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock('@/api/platformFactoryMenus', () => apiMocks);
vi.mock('@/store/modules/platformFactoryContext', () => ({
  usePlatformFactoryContextStore: () => contextMocks,
}));
vi.mock('vue-router', () => ({ useRouter: () => routerMock }));

import FactoryConsole from '../index.vue';

const menu = (factoryId: string) => ({
  success: true,
  data: {
    factoryId,
    factoryName: factoryId === 'F001' ? '一号厂' : '二号厂',
    modules: [{
      moduleId: 'analytics', moduleName: '数据与分析', visible: true,
      configured: factoryId === 'F002', disabledMenuPaths: [],
    }],
  },
});

function mountPage() {
  return shallowMount(FactoryConsole, { global: { stubs: {
    ElMessage: true,
    'el-alert': true,
    'el-button': true,
    'el-card': true,
    'el-checkbox': true,
    'el-checkbox-group': true,
    'el-empty': true,
    'el-input': true,
    'el-skeleton': true,
    'el-switch': true,
    'el-tag': true,
  } } });
}

describe('platform factory console', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiMocks.listPlatformFactories.mockResolvedValue([
      { id: 'F001', name: '一号厂', isActive: true },
      { id: 'F002', name: '二号厂', isActive: true },
    ]);
    apiMocks.getFactoryMenuConfig.mockImplementation((factoryId: string) => Promise.resolve(menu(factoryId)));
    apiMocks.updateFactoryMenuConfig.mockResolvedValue({ success: true, data: {} });
    contextMocks.enterFactoryView.mockResolvedValue({ factoryId: 'F001', readOnly: true });
  });

  it('loads the selected factory and isolates switching from the previous config', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(apiMocks.getFactoryMenuConfig).toHaveBeenLastCalledWith('F001');
    expect((wrapper.vm as unknown as { menuConfigState: { factoryName: string } }).menuConfigState.factoryName).toBe('一号厂');

    (wrapper.vm as unknown as { selectedFactoryId: string }).selectedFactoryId = 'F002';
    await wrapper.vm.$nextTick();
    await flushPromises();
    expect(apiMocks.getFactoryMenuConfig).toHaveBeenLastCalledWith('F002');
    expect((wrapper.vm as unknown as { menuConfigState: { factoryName: string } }).menuConfigState.factoryName).toBe('二号厂');
    expect((wrapper.vm as unknown as { menuConfigState: { factoryName: string } }).menuConfigState.factoryName).not.toBe('一号厂');
  });

  it('ignores an out-of-order response from the previously selected factory', async () => {
    let resolveFirst!: (value: unknown) => void;
    let resolveSecond!: (value: unknown) => void;
    apiMocks.getFactoryMenuConfig
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSecond = resolve; }));
    const wrapper = mountPage();
    await flushPromises();
    (wrapper.vm as unknown as { selectedFactoryId: string }).selectedFactoryId = 'F002';
    await wrapper.vm.$nextTick();
    resolveSecond(menu('F002'));
    await flushPromises();
    resolveFirst(menu('F001'));
    await flushPromises();
    expect((wrapper.vm as unknown as { menuConfigState: { factoryId: string } }).menuConfigState.factoryId).toBe('F002');
  });

  it('saves the selected factory/module and re-reads the response', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const module = (wrapper.vm as unknown as { menuModules: Array<{ moduleId: string; visible: boolean; disabledMenuPaths: string[] }> }).menuModules[0];
    await (wrapper.vm as unknown as { saveModule: (value: typeof module) => Promise<void> }).saveModule(module);
    expect(apiMocks.updateFactoryMenuConfig).toHaveBeenCalledWith('F001', 'analytics', {
      visible: true, disabledMenuPaths: [],
    });
    expect(apiMocks.getFactoryMenuConfig).toHaveBeenCalledTimes(2);
  });

  it('keeps the selected factory when the previous response arrives late', async () => {
    let resolveFirst!: (value: ReturnType<typeof menu>) => void;
    apiMocks.getFactoryMenuConfig.mockImplementation((factoryId: string) => factoryId === 'F001'
      ? new Promise<ReturnType<typeof menu>>((resolve) => { resolveFirst = resolve; })
      : Promise.resolve(menu(factoryId)));
    const wrapper = mountPage();
    await flushPromises();
    const state = wrapper.vm as unknown as {
      selectedFactoryId: string; menuConfigState: { factoryId: string };
    };
    state.selectedFactoryId = 'F002';
    await flushPromises();
    expect(state.menuConfigState.factoryId).toBe('F002');
    resolveFirst(menu('F001'));
    await flushPromises();
    expect(state.menuConfigState.factoryId).toBe('F002');
  });

  it('does not clear the current loading state or show stale factory errors', async () => {
    let rejectFirst!: (error: Error) => void;
    let resolveSecond!: (value: ReturnType<typeof menu>) => void;
    apiMocks.getFactoryMenuConfig.mockImplementation((factoryId: string) => factoryId === 'F001'
      ? new Promise<ReturnType<typeof menu>>((_resolve, reject) => { rejectFirst = reject; })
      : new Promise<ReturnType<typeof menu>>((resolve) => { resolveSecond = resolve; }));
    const wrapper = mountPage();
    await flushPromises();
    const state = wrapper.vm as unknown as {
      selectedFactoryId: string; loadingConfig: boolean; errorMessage: string;
      menuConfigState: { factoryId: string };
    };
    state.selectedFactoryId = 'F002';
    await flushPromises();
    rejectFirst(new Error('上一家工厂请求失败'));
    await flushPromises();
    expect(state.loadingConfig).toBe(true);
    expect(state.errorMessage).toBe('');
    resolveSecond(menu('F002'));
    await flushPromises();
    expect(state.loadingConfig).toBe(false);
    expect(state.menuConfigState.factoryId).toBe('F002');
  });

  it('enters read-only view through the short-lived context action', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const factory = { id: 'F001', name: '一号厂', isActive: true };
    await (wrapper.vm as unknown as { enterReadOnly: (value: typeof factory) => Promise<void> }).enterReadOnly(factory);
    expect(contextMocks.enterFactoryView).toHaveBeenCalledWith('F001');
    expect(routerMock.push).toHaveBeenCalledWith('/dashboard');
  });

  it('shows an honest error when the real factory list fails', async () => {
    apiMocks.listPlatformFactories.mockRejectedValue(new Error('平台工厂列表不可用'));
    const wrapper = mountPage();
    await flushPromises();
    expect((wrapper.vm as unknown as { errorMessage: string }).errorMessage).toContain('平台工厂列表不可用');
    expect(apiMocks.getFactoryMenuConfig).not.toHaveBeenCalled();
  });
});

