import { describe, it, expect } from 'vitest';
import {
  resolveProductionAnalysisTab,
  canSeeFactoryTypeGatedTabs,
  PRODUCTION_ANALYSIS_TABS,
  FACTORY_TYPE_GATED_TABS,
  DEFAULT_PRODUCTION_ANALYSIS_TAB,
} from '../productionAnalysisTab';

describe('productionAnalysisTab — 生产分析 hub tab 解析 (LIUSHANMEN IA 收拢, 2026-09-02)', () => {
  it('6 个 tab: 车间实时生产报表/生产数据分析/BOM达成率分析/工序投入产出对比/人效双口径对比/成品出厂核算', () => {
    expect(PRODUCTION_ANALYSIS_TABS).toEqual([
      'workshop-report', 'data-analysis', 'bom-achievement', 'process-io', 'labor-efficiency', 'yield-cost',
    ]);
  });

  it('3 个 analytics 系 tab 需要业态门控 (合并前各自 hideForFactoryTypes:[RESTAURANT,FACTORY])', () => {
    expect(FACTORY_TYPE_GATED_TABS).toEqual(['workshop-report', 'data-analysis', 'yield-cost']);
  });

  it('默认 tab = bom-achievement (对所有业态都可见)', () => {
    expect(DEFAULT_PRODUCTION_ANALYSIS_TAB).toBe('bom-achievement');
    expect(resolveProductionAnalysisTab({})).toBe('bom-achievement');
    expect(resolveProductionAnalysisTab(null)).toBe('bom-achievement');
    expect(resolveProductionAnalysisTab(undefined)).toBe('bom-achievement');
  });

  describe('canSeeFactoryTypeGatedTabs — 与合并前 hideForFactoryTypes:[RESTAURANT,FACTORY] 逐字节等价', () => {
    it('LOGISTICS 能看 (合并前唯一能看这 3 个 tab 的业态)', () => {
      expect(canSeeFactoryTypeGatedTabs('LOGISTICS')).toBe(true);
    });
    it('FACTORY 看不到', () => {
      expect(canSeeFactoryTypeGatedTabs('FACTORY')).toBe(false);
    });
    it('RESTAURANT 看不到', () => {
      expect(canSeeFactoryTypeGatedTabs('RESTAURANT')).toBe(false);
    });
    it('未知/空 factoryType 按能看处理 (与 hideForFactoryTypes.includes(undefined) 恒 false 等价)', () => {
      expect(canSeeFactoryTypeGatedTabs(undefined)).toBe(true);
      expect(canSeeFactoryTypeGatedTabs(null)).toBe(true);
    });
  });

  it.each(['bom-achievement', 'process-io', 'labor-efficiency'] as const)(
    '3 个 production 系 tab (%s) 对任何业态都放行',
    (tab) => {
      expect(resolveProductionAnalysisTab({ tab }, 'FACTORY')).toBe(tab);
      expect(resolveProductionAnalysisTab({ tab }, 'RESTAURANT')).toBe(tab);
      expect(resolveProductionAnalysisTab({ tab }, 'LOGISTICS')).toBe(tab);
    },
  );

  it.each(['workshop-report', 'data-analysis', 'yield-cost'] as const)(
    '3 个 analytics 系 tab (%s) 只在 LOGISTICS 放行, FACTORY/RESTAURANT 回落默认',
    (tab) => {
      expect(resolveProductionAnalysisTab({ tab }, 'LOGISTICS')).toBe(tab);
      expect(resolveProductionAnalysisTab({ tab }, 'FACTORY')).toBe('bom-achievement');
      expect(resolveProductionAnalysisTab({ tab }, 'RESTAURANT')).toBe('bom-achievement');
    },
  );

  it('非法 tab → 默认 bom-achievement (与业态无关)', () => {
    expect(resolveProductionAnalysisTab({ tab: 'whatever' }, 'LOGISTICS')).toBe('bom-achievement');
    expect(resolveProductionAnalysisTab({ tab: '' }, 'FACTORY')).toBe('bom-achievement');
  });
});
