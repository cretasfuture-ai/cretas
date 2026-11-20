import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import DigitalEmployeeFrame from '../DigitalEmployeeFrame.vue';

describe('embedded role workdesks', () => {
  it.each([
    ['sales', '销售数字专员工作台', 'clerk', '/sales/orders'],
    ['purchase', '采购数字专员工作台', 'clerk', '/procurement/orders'],
    ['inventory', '库存数字专员工作台', 'keeper', '/warehouse/materials'],
    ['finance', '财务数字专员工作台', 'accountant', '/finance/ar-ap'],
    ['quality-manager', '质量追溯数字专员工作台', 'inspector', '/quality/recalls'],
    ['production', '生产数字专员工作台', 'planner', '/production/batches'],
    ['quality-chief', '质检数字专员工作台', 'inspector', '/quality/inspections'],
    ['ai-value', 'AI价值数字专员工作台', 'clerk', '/dashboard'],
    ['logistics', '排线数字专员工作台', 'planner', '/scheduling/logistics/records'],
  ] as const)('renders %s using the updated Nuxt UI employee frame and a real business route', async (role, title, avatar, path) => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] });
    await router.push('/');
    const wrapper = mount(DigitalEmployeeFrame, { props: { enabled: true, role, busy: false, error: '', factoryId: 'WHOLESALE_TEST', paths: [path], canWrite: true }, global: { plugins: [router] }, slots: { default: '<p>原工作台的真实查询结果</p>' } });
    expect(wrapper.get('h1').text()).toBe(title);
    expect(wrapper.get('uavatar').attributes('src')).toBe(`/digital-employees/${avatar}.png`);
    expect(wrapper.get('uchip').attributes('color')).toBe('neutral');
    expect(wrapper.text()).toContain('原工作台的真实查询结果');
    expect(wrapper.text()).toContain('WHOLESALE_TEST');
    await wrapper.get('nav a').trigger('click');
    await router.isReady();
    expect(router.resolve(wrapper.get('nav a').attributes('href')).path).toBe(path);
    expect(wrapper.findAll('nav a').every((link) => link.attributes('href').startsWith(path))).toBe(true);
    await wrapper.setProps({ busy: true });
    expect(wrapper.get('[role="status"]').text()).toContain('正在核对');
    await wrapper.setProps({ busy: false, error: '接口未响应' });
    expect(wrapper.get('.crew-avatar').attributes('data-state')).toBe('alert');
    expect(wrapper.get('uchip').attributes('color')).toBe('error');
    expect(wrapper.text()).not.toContain('已办成');
    wrapper.unmount();
  });
  it('honors an explicitly disabled frame for embedded legacy consumers', () => {
    const wrapper = mount(DigitalEmployeeFrame, { props: { role: 'sales', busy: false, error: '', factoryId: 'F006' }, slots: { default: '原工作台' } });
    expect(wrapper.text()).toBe('原工作台');
    expect(wrapper.find('img').exists()).toBe(false);
  });
  it('keeps order reading available without presenting a dead AI write link to read-only users', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/sales/orders', component: { template: '<div />' } }] });
    await router.push('/sales/orders');
    const wrapper = mount(DigitalEmployeeFrame, { props: { enabled: true, role: 'sales', busy: false, error: '', factoryId: 'WHOLESALE_TEST', paths: ['/sales/orders'], canWrite: false }, global: { plugins: [router] } });
    expect(wrapper.get('nav a').text()).toContain('销售订单');
    expect(wrapper.find('a[href*="assistant="]').exists()).toBe(false);
    expect(wrapper.text()).toContain('只能查看');
  });
});
