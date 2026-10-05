/**
 * 一线 AI 助手入口 · 熔断开关的纯判定（spec docs/specs/rn-frontline-ai-entry-2026-10-05.md）。
 * 导航层的同一组行为在 integration/navigation/FrontlineAIEntryNavigation.test.tsx。
 */
import {
  FRONTLINE_AI_ENTRY_GLOBAL_ENABLED,
  FRONTLINE_AI_ROLES,
  FRONTLINE_AI_SCREEN_ID,
  isFrontlineAIEntryEnabled,
} from '../../../config/frontlineAIEntry';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';

const remoteOn = () => true;
const remoteOff = () => false;

describe('isFrontlineAIEntryEnabled', () => {
  it('owner 裁定: 默认开（发版里的全局常量就是 true）', () => {
    expect(FRONTLINE_AI_ENTRY_GLOBAL_ENABLED).toBe(true);
  });

  it('owner 点名的四个角色, 不多不少', () => {
    expect([...FRONTLINE_AI_ROLES].sort()).toEqual(['operator', 'quality_inspector', 'warehouse_worker', 'yield_operator']);
  });

  it.each(['operator', 'yield_operator', 'warehouse_worker', 'quality_inspector'])('%s: 两层都开 ⇒ 显示', (role) => {
    expect(isFrontlineAIEntryEnabled(role, remoteOn, true)).toBe(true);
  });

  it.each(['operator', 'yield_operator', 'warehouse_worker', 'quality_inspector'])('%s: 全局关 ⇒ 不显示', (role) => {
    expect(isFrontlineAIEntryEnabled(role, remoteOn, false)).toBe(false);
  });

  it.each(['operator', 'yield_operator', 'warehouse_worker', 'quality_inspector'])('%s: 本工厂关 ⇒ 不显示', (role) => {
    expect(isFrontlineAIEntryEnabled(role, remoteOff, true)).toBe(false);
  });

  // warehouse_manager 与仓库员共用收货/发货/库存三屏 —— 组件自己判角色, 主管不多出一个入口
  it.each(['warehouse_manager', 'factory_super_admin', 'workshop_supervisor', 'viewer', '', null, undefined])(
    '非一线角色 %p ⇒ 不显示（即使两层都开）',
    (role) => {
      expect(isFrontlineAIEntryEnabled(role as string | null | undefined, remoteOn, true)).toBe(false);
    },
  );

  it('第②层问的就是 FrontlineAIChat 这个屏幕 ID', () => {
    const asked: string[] = [];
    isFrontlineAIEntryEnabled('operator', (id) => { asked.push(id); return true; }, true);
    expect(asked).toEqual([FRONTLINE_AI_SCREEN_ID]);
    expect(FRONTLINE_AI_SCREEN_ID).toBe('FrontlineAIChat');
  });
});

describe('第②层接的是真的 factoryFeatureStore（同 web-admin 功能配置页）', () => {
  const setSmartbi = (enabled: boolean, disabledScreens: string[] = []) =>
    useFactoryFeatureStore.setState({
      modules: { smartbi: { enabled, moduleName: '智能BI', config: { disabledScreens } } } as never,
      loaded: true,
      loading: false,
    });

  it('smartbi 开、没禁用 ⇒ 开', () => {
    setSmartbi(true);
    expect(useFactoryFeatureStore.getState().isScreenEnabled(FRONTLINE_AI_SCREEN_ID)).toBe(true);
  });

  it('disabledScreens 里勾了 FrontlineAIChat ⇒ 关', () => {
    setSmartbi(true, [FRONTLINE_AI_SCREEN_ID]);
    expect(useFactoryFeatureStore.getState().isScreenEnabled(FRONTLINE_AI_SCREEN_ID)).toBe(false);
  });

  it('整个 smartbi 模块关 ⇒ 关（FrontlineAIChat 归属 smartbi）', () => {
    setSmartbi(false);
    expect(useFactoryFeatureStore.getState().isScreenEnabled(FRONTLINE_AI_SCREEN_ID)).toBe(false);
  });

  it('功能配置没加载到（失败/新工厂）⇒ 开 —— 已知的 fail-open, 见 config/frontlineAIEntry.ts 注释', () => {
    useFactoryFeatureStore.setState({ modules: {}, loaded: true, loading: false });
    expect(useFactoryFeatureStore.getState().isScreenEnabled(FRONTLINE_AI_SCREEN_ID)).toBe(true);
  });
});
