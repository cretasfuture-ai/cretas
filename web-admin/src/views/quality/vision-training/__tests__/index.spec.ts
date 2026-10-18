import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, reactive } from 'vue';
import { shallowMount } from '@vue/test-utils';
import router from '@/router';
import { menuConfig, type MenuItem } from '@/components/layout/menuConfig';
import VisionTrainingPage from '../index.vue';

const authState = reactive({
  isFactory: true,
  factoryId: 'F006',
  factoryName: '胜龙牛业',
});

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const setupStub = defineComponent({
  name: 'FactoryVisionTrainingSetup',
  props: { factoryId: { type: String, required: true } },
  template: '<div data-testid="vision-training-setup-stub">{{ factoryId }}</div>',
});

function findMenu(items: MenuItem[], path: string): MenuItem | undefined {
  for (const item of items) {
    if (item.path === path) return item;
    const found = findMenu(item.children ?? [], path);
    if (found) return found;
  }
  return undefined;
}

beforeEach(() => {
  authState.isFactory = true;
  authState.factoryId = 'F006';
  authState.factoryName = '胜龙牛业';
});

describe('quality daily vision training entry', () => {
  it('registers one reachable route and menu item for the same factory roles', () => {
    const menuItem = findMenu(menuConfig, '/quality/vision-training');
    const resolved = router.resolve('/quality/vision-training');

    expect(menuItem?.title).toBe('视觉质检训练');
    expect(menuItem?.roles).toEqual(['factory_super_admin', 'quality_manager']);
    expect(resolved.name).toBe('QualityVisionTraining');
    expect(resolved.meta.title).toBe('视觉质检训练');
    expect(resolved.meta.roles).toEqual(['factory_super_admin', 'quality_manager']);
  });

  it('exposes factory operating indicators without reopening the hidden SmartBI group', () => {
    const menuItem = findMenu(menuConfig, '/analytics/indicators');
    const resolved = router.resolve('/analytics/indicators');

    expect(menuItem?.title).toBe('经营指标');
    expect(menuItem?.roles).toEqual(['factory_super_admin', 'quality_manager', 'finance_manager']);
    expect(menuItem?.hideForFactoryTypes).toEqual(['RESTAURANT']);
    expect(resolved.name).toBe('AnalyticsIndicators');
    expect(resolved.meta.roles).toEqual(['factory_super_admin', 'quality_manager', 'finance_manager']);
    expect(resolved.meta.hideForFactoryTypes).toEqual(['RESTAURANT']);
  });

  it('passes the factory store context to the setup and ignores a platform query factory', () => {
    const wrapper = shallowMount(VisionTrainingPage, {
      global: {
        stubs: { FactoryVisionTrainingSetup: setupStub },
        mocks: { $route: { query: { factoryId: 'PLATFORM-FACTORY' } } },
      },
    });

    expect(wrapper.find('[data-testid="vision-factory-scope"]').text()).toContain('胜龙牛业');
    expect(wrapper.find('[data-testid="vision-training-setup-stub"]').text()).toBe('F006');
    expect(wrapper.text()).not.toContain('PLATFORM-FACTORY');
  });

  it('shows an explicit no-auth state and does not mount the SOP for a platform account', () => {
    authState.isFactory = false;
    authState.factoryId = '';
    authState.factoryName = '';
    const wrapper = shallowMount(VisionTrainingPage, {
      global: { stubs: { FactoryVisionTrainingSetup: setupStub } },
    });

    expect(wrapper.find('[data-testid="vision-training-no-auth"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="vision-training-setup-stub"]').exists()).toBe(false);
  });
});
